//! Сценарии спецификации `relay` на настоящем HTTPS-сервере (порт 0, самоподписанный сертификат,
//! клиент проверяет отпечаток). Время двигается руками через `ManualClock`.

mod common;

use std::time::{Duration, Instant};

use ampulse_server::api::{ApiOptions, MAX_WAIT_SECS};
use ampulse_server::codec::{b64_encode, random_bytes};
use ampulse_server::ratelimit::RateConfig;
use ampulse_server::store::{Limits, MAX_BOX_BYTES, MAX_MESSAGE_BYTES};
use common::{quiet_rate, request, Env, Pair, DAY};

// ---------- Протокол: здоровье, создание, запись, чтение ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn health_answers_without_touching_boxes() {
    let env = Env::start().await;
    let reply = env.call("GET", "/v1/health", None, b"").await;
    assert_eq!(reply.status, 200);
    let json = reply.json();
    assert_eq!(json["ok"], true);
    assert_eq!(json["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(reply.header("cache-control"), Some("no-store"));
    assert_eq!(env.store.stats().unwrap().boxes, 0);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn create_post_read_acknowledge_round_trip() {
    let env = Env::start().await;
    let pair = Pair::random();
    assert_eq!(env.create(&pair).await.status, 201);

    let posted = env.post(&pair, b"opaque-bytes-1").await;
    assert_eq!(posted.status, 201);
    assert_eq!(posted.json()["seq"], 1);
    assert_eq!(env.post(&pair, b"opaque-bytes-2").await.json()["seq"], 2);

    let read = env.read(&pair, 0, 0).await;
    assert_eq!(read.status, 200);
    assert_eq!(
        read.messages(),
        vec![(1, b"opaque-bytes-1".to_vec()), (2, b"opaque-bytes-2".to_vec())]
    );
    assert!(read.json()["messages"][0]["at"].as_i64().unwrap() >= common::START);

    assert_eq!(env.read(&pair, 1, 0).await.seqs(), vec![2]);
    assert_eq!(env.ack(&pair, 2).await.status, 204);
    assert!(env.read(&pair, 0, 0).await.seqs().is_empty());
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn create_is_repeatable_with_same_hashes_and_conflicts_with_others() {
    let env = Env::start().await;
    let pair = Pair::random();
    assert_eq!(env.create(&pair).await.status, 201);
    assert_eq!(env.create(&pair).await.status, 204);
    let other = Pair {
        id: pair.id.clone(),
        ..Pair::random()
    };
    let reply = env.create(&other).await;
    assert_eq!(reply.status, 409);
    assert_eq!(reply.json()["error"], "conflict");
    // Прежние токены продолжают работать.
    assert_eq!(env.post(&pair, b"x").await.status, 201);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn malformed_requests_are_rejected_cleanly() {
    let env = Env::start().await;
    let pair = Pair::random();
    // Тело создания — не JSON, хэш не той длины, неверный идентификатор.
    assert_eq!(env.call("PUT", &pair.path(), None, b"nope").await.status, 400);
    let short = serde_json::json!({ "write_hash": "AAAA", "read_hash": pair.read_hash() });
    assert_eq!(
        env.call("PUT", &pair.path(), None, short.to_string().as_bytes())
            .await
            .status,
        400
    );
    let body = serde_json::json!({ "write_hash": pair.write_hash(), "read_hash": pair.read_hash() });
    assert_eq!(
        env.call("PUT", "/v1/box/short", None, body.to_string().as_bytes())
            .await
            .status,
        400
    );
    // Пустое сообщение и ack без номера.
    let pair = env.new_box().await;
    assert_eq!(env.post(&pair, b"").await.status, 400);
    assert_eq!(
        env.call("DELETE", &pair.path(), Some(&pair.read), b"").await.status,
        400
    );
    assert_eq!(
        env.call("GET", &format!("{}?after=abc", pair.path()), Some(&pair.read), b"")
            .await
            .status,
        400
    );
    env.stop().await;
}

// ---------- Требование: Box access by hashed tokens ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wrong_token_answers_404_and_stores_nothing() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let stranger = Pair::random();

    let wrong = env.call("POST", &pair.path(), Some(&stranger.write), b"secret").await;
    assert_eq!(wrong.status, 404);
    assert_eq!(
        env.store.stats().unwrap().messages,
        0,
        "a refused message must not be stored"
    );
    assert_eq!(env.post(&pair, b"ok").await.status, 201);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unknown_box_and_wrong_token_are_indistinguishable() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let ghost = Pair::random();
    let stranger = Pair::random();

    let mut replies = Vec::new();
    // Чужой токен в настоящем ящике.
    replies.push(env.call("POST", &pair.path(), Some(&stranger.write), b"x").await);
    // Настоящий токен, но неизвестный ящик.
    replies.push(env.call("POST", &ghost.path(), Some(&pair.write), b"x").await);
    // Токен не той роли: чтение токеном записи и запись токеном чтения.
    replies.push(env.call("GET", &pair.path(), Some(&pair.write), b"").await);
    replies.push(env.call("POST", &pair.path(), Some(&pair.read), b"x").await);
    // Нет заголовка, мусор вместо токена, неверный вид идентификатора.
    replies.push(env.call("GET", &pair.path(), None, b"").await);
    replies.push(env.call("GET", &pair.path(), Some("***"), b"").await);
    replies.push(env.call("GET", "/v1/box/not-an-id", Some(&pair.read), b"").await);
    // Подтверждение чужим токеном и токеном записи.
    replies.push(
        env.call("DELETE", &format!("{}?upto=1", pair.path()), Some(&pair.write), b"")
            .await,
    );
    replies.push(
        env.call("DELETE", &format!("{}?upto=1", ghost.path()), Some(&ghost.read), b"")
            .await,
    );

    for reply in &replies {
        assert_eq!(reply.status, 404);
        assert_eq!(reply.body, replies[0].body, "all refusals must have the same body");
        assert_eq!(reply.json()["error"], "not_found");
    }
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_hashes_are_registered_and_token_hash_must_match_exactly() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    // Хэш токена, присланный как токен, не подходит: сервер сравнивает SHA-256 присланного.
    let hash_as_token = pair.write_hash();
    assert_eq!(
        env.call("POST", &pair.path(), Some(&hash_as_token), b"x").await.status,
        404
    );
    // Токен с лишним знаком или без одного.
    let longer = format!("{}A", pair.write);
    assert_eq!(env.call("POST", &pair.path(), Some(&longer), b"x").await.status, 404);
    let shorter = &pair.write[..pair.write.len() - 2];
    assert_eq!(env.call("POST", &pair.path(), Some(shorter), b"x").await.status, 404);
    assert_eq!(env.post(&pair, b"x").await.status, 201);
    env.stop().await;
}

// ---------- Требование: Opaque storage ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn database_and_backup_hold_only_ciphertext_hashes_and_metadata() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    // «Шифртекст»: случайные байты; клиент не отдаёт серверу ни ключа, ни открытого текста.
    let ciphertext = random_bytes::<256>();
    assert_eq!(env.post(&pair, &ciphertext).await.status, 201);

    // Копия базы и сама база: сырых токенов и их Base64URL-записи в них нет, шифртекст лежит как есть.
    let copy_dir = common::temp_dir("backup");
    ampulse_server::backup::run(&env.dir, &copy_dir).unwrap();
    for file in [env.dir.join("ampulse.db"), copy_dir.join("ampulse.db")] {
        let bytes = std::fs::read(&file).unwrap();
        let contains = |needle: &[u8]| bytes.windows(needle.len()).any(|w| w == needle);
        assert!(contains(&ciphertext), "ciphertext is stored as received");
        assert!(
            !contains(pair.write.as_bytes()) && !contains(pair.read.as_bytes()),
            "raw tokens are never stored"
        );
        assert!(
            !contains(&ampulse_server::codec::b64_decode(&pair.write).unwrap()),
            "raw token bytes are never stored"
        );
    }

    // В схеме нет ни одного столбца, куда мог бы лечь читаемый текст.
    let conn = ampulse_server::store::open_read_only(&copy_dir.join("ampulse.db")).unwrap();
    let mut columns: Vec<String> = Vec::new();
    for table in ["boxes", "messages"] {
        let mut stmt = conn
            .prepare(&format!("SELECT name FROM pragma_table_info('{table}')"))
            .unwrap();
        columns.extend(
            stmt.query_map([], |r| r.get::<_, String>(0))
                .unwrap()
                .map(|r| format!("{table}.{}", r.unwrap())),
        );
    }
    columns.sort();
    assert_eq!(
        columns,
        [
            "boxes.bytes",
            "boxes.created",
            "boxes.id",
            "boxes.last_used",
            "boxes.msgs",
            "boxes.next_seq",
            "boxes.read_hash",
            "boxes.write_hash",
            "messages.body",
            "messages.box_id",
            "messages.created",
            "messages.seq",
            "messages.size"
        ]
    );
    drop(conn);
    let _ = std::fs::remove_dir_all(&copy_dir);
    env.stop().await;
}

// ---------- Требование: Retention and quotas ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn full_box_answers_429_and_keeps_old_messages_until_acknowledged() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    for n in 1..=200u64 {
        let reply = env.post(&pair, format!("m{n}").as_bytes()).await;
        assert_eq!(reply.status, 201, "message {n}");
    }
    let refused = env.post(&pair, b"one too many").await;
    assert_eq!(refused.status, 429);
    assert_eq!(refused.json()["error"], "box_full");
    // Старые остались, новое не записано.
    let all = env.read(&pair, 0, 0).await.messages();
    assert_eq!(all.len(), 100, "one answer carries at most one page");
    assert_eq!(all[0], (1, b"m1".to_vec()));
    assert_eq!(env.store.stats().unwrap().messages, 200);
    // После подтверждения место появляется.
    assert_eq!(env.ack(&pair, 10).await.status, 204);
    assert_eq!(env.post(&pair, b"fits now").await.status, 201);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn message_over_8_kib_is_refused_with_413() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let big = vec![7u8; MAX_MESSAGE_BYTES + 1];
    let reply = env.post(&pair, &big).await;
    assert_eq!(reply.status, 413);
    assert_eq!(reply.json()["error"], "too_large");
    assert_eq!(env.store.stats().unwrap().messages, 0);
    // Ровно 8 КиБ принимается.
    assert_eq!(env.post(&pair, &vec![7u8; MAX_MESSAGE_BYTES]).await.status, 201);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn box_over_1_mib_is_refused_with_429() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let chunk = vec![1u8; MAX_MESSAGE_BYTES];
    for n in 0..(MAX_BOX_BYTES as usize / MAX_MESSAGE_BYTES) {
        assert_eq!(env.post(&pair, &chunk).await.status, 201, "chunk {n}");
    }
    let reply = env.post(&pair, b"x").await;
    assert_eq!(reply.status, 429);
    assert_eq!(reply.json()["error"], "box_full");
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn server_wide_ceiling_answers_507() {
    let limits = Limits {
        max_total_bytes: 20,
        max_boxes: 2,
        ..Limits::default()
    };
    let env = Env::with(limits, quiet_rate(), ApiOptions::default()).await;
    let (a, b) = (env.new_box().await, env.new_box().await);
    let third = Pair::random();
    assert_eq!(env.create(&third).await.status, 507);
    assert_eq!(env.post(&a, &[1u8; 15]).await.status, 201);
    let refused = env.post(&b, &[1u8; 6]).await;
    assert_eq!(refused.status, 507);
    assert_eq!(refused.json()["error"], "server_full");
    assert_eq!(env.post(&b, &[1u8; 5]).await.status, 201);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn messages_are_deleted_after_7_days() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    env.post(&pair, b"old").await;
    env.clock.advance_secs(6 * DAY);
    env.post(&pair, b"new").await;
    assert_eq!(env.read(&pair, 0, 0).await.seqs(), vec![1, 2]);

    env.clock.advance_secs(DAY + 1);
    // Старше 7 суток не отдаётся, даже пока уборка не пришла.
    assert_eq!(env.read(&pair, 0, 0).await.seqs(), vec![2]);
    let swept = env.store.sweep().unwrap();
    assert_eq!(swept.messages, 1);
    assert_eq!(env.store.stats().unwrap().messages, 1);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn box_unused_for_90_days_is_deleted_and_use_keeps_it_alive() {
    let env = Env::start().await;
    let idle = env.new_box().await;
    let used = env.new_box().await;
    env.post(&idle, b"m").await;

    env.clock.advance_secs(60 * DAY);
    assert_eq!(env.read(&used, 0, 0).await.status, 200);
    env.clock.advance_secs(31 * DAY);

    // Ящик без обращений 91 день исчез: 404, как будто его и не было.
    assert_eq!(env.read(&idle, 0, 0).await.status, 404);
    assert_eq!(env.post(&idle, b"m").await.status, 404);
    // Тот, к которому обращались, жив.
    assert_eq!(env.read(&used, 0, 0).await.status, 200);
    assert_eq!(env.store.stats().unwrap().boxes, 1);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn background_sweep_removes_expired_data() {
    let options = ApiOptions {
        sweep_every: Duration::from_millis(100),
        ..ApiOptions::default()
    };
    let env = Env::with(Limits::default(), quiet_rate(), options).await;
    let pair = env.new_box().await;
    env.post(&pair, b"m").await;
    env.clock.advance_secs(8 * DAY);
    let deadline = Instant::now() + Duration::from_secs(10);
    while env.store.stats().unwrap().messages != 0 {
        assert!(Instant::now() < deadline, "the sweeper did not run");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    env.stop().await;
}

// ---------- Требование: Acknowledged delete ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn acknowledge_up_to_5_removes_1_to_5_and_later_read_returns_newer() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    for n in 1..=8 {
        env.post(&pair, format!("m{n}").as_bytes()).await;
    }
    assert_eq!(env.ack(&pair, 5).await.status, 204);
    assert_eq!(env.read(&pair, 0, 0).await.seqs(), vec![6, 7, 8]);
    assert_eq!(env.read(&pair, 5, 0).await.seqs(), vec![6, 7, 8]);
    // Номера не используются повторно.
    assert_eq!(env.post(&pair, b"m9").await.json()["seq"], 9);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn acknowledge_needs_the_read_token() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    env.post(&pair, b"m1").await;
    let with_write = env
        .call("DELETE", &format!("{}?upto=1", pair.path()), Some(&pair.write), b"")
        .await;
    assert_eq!(with_write.status, 404);
    assert_eq!(env.read(&pair, 0, 0).await.seqs(), vec![1], "nothing was deleted");
    env.stop().await;
}

// ---------- Требование: Long poll ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn message_posted_during_a_wait_is_returned_at_once() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let started = Instant::now();

    let (addr, fp, path, token) = (env.addr, env.fingerprint.clone(), pair.path(), pair.read.clone());
    let poll = tokio::spawn(async move {
        request(addr, &fp, "GET", &format!("{path}?after=0&wait=25"), Some(&token), b"")
            .await
            .unwrap()
    });
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert!(!poll.is_finished(), "an empty box must keep the poll waiting");
    assert_eq!(env.post(&pair, b"arrived").await.status, 201);

    let reply = tokio::time::timeout(Duration::from_secs(5), poll)
        .await
        .expect("poll must return promptly")
        .unwrap();
    assert_eq!(reply.status, 200);
    assert_eq!(reply.messages(), vec![(1, b"arrived".to_vec())]);
    assert!(
        started.elapsed() < Duration::from_secs(10),
        "must not wait out the interval"
    );
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wait_is_capped_and_an_empty_wait_returns_an_empty_list() {
    assert_eq!(ApiOptions::default().max_wait, Duration::from_secs(25));
    assert_eq!(MAX_WAIT_SECS, 25);
    // Для теста потолок уменьшен до секунды; запрос просит 25 и получает не больше потолка.
    let options = ApiOptions {
        max_wait: Duration::from_secs(1),
        ..ApiOptions::default()
    };
    let env = Env::with(Limits::default(), quiet_rate(), options).await;
    let pair = env.new_box().await;
    let started = Instant::now();
    let reply = env.read(&pair, 0, 25).await;
    let took = started.elapsed();
    assert_eq!(reply.status, 200);
    assert!(reply.seqs().is_empty());
    assert!(took >= Duration::from_millis(900), "waited only {took:?}");
    assert!(took < Duration::from_secs(5), "waited {took:?}, the cap did not apply");
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn read_with_waiting_messages_answers_without_waiting() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    env.post(&pair, b"already here").await;
    let started = Instant::now();
    let reply = env.read(&pair, 0, 25).await;
    assert_eq!(reply.seqs(), vec![1]);
    assert!(started.elapsed() < Duration::from_secs(5));
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_waiting_poll_with_the_wrong_token_is_refused_immediately() {
    let env = Env::start().await;
    let pair = env.new_box().await;
    let started = Instant::now();
    let reply = env
        .call(
            "GET",
            &format!("{}?wait=25", pair.path()),
            Some(&Pair::random().read),
            b"",
        )
        .await;
    assert_eq!(reply.status, 404);
    assert!(started.elapsed() < Duration::from_secs(5));
    env.stop().await;
}

// ---------- Ограничение частоты ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn requests_are_limited_per_address_and_recover_with_time() {
    let rate = RateConfig {
        burst: 5.0,
        per_sec: 1.0,
        failure_cost: 4.0,
    };
    let env = Env::with(Limits::default(), rate, ApiOptions::default()).await;
    let pair = Pair::random();
    for _ in 0..5 {
        assert_ne!(env.create(&pair).await.status, 429);
    }
    let limited = env.create(&pair).await;
    assert_eq!(limited.status, 429);
    assert_eq!(limited.json()["error"], "rate_limited");
    assert!(limited.header("retry-after").unwrap().parse::<u64>().unwrap() >= 1);
    // Здоровье не ограничивается: по нему проверяют живость.
    assert_eq!(env.call("GET", "/v1/health", None, b"").await.status, 200);

    env.clock.advance_secs(10);
    assert_ne!(env.create(&pair).await.status, 429);
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn guessing_tokens_exhausts_the_limit_faster_than_normal_use() {
    let rate = RateConfig {
        burst: 12.0,
        per_sec: 0.5,
        failure_cost: 4.0,
    };
    let env = Env::with(Limits::default(), rate, ApiOptions::default()).await;
    let pair = Pair::random();
    assert_eq!(env.create(&pair).await.status, 201);
    let stranger = Pair::random();
    let mut statuses = Vec::new();
    for _ in 0..6 {
        statuses.push(env.call("POST", &pair.path(), Some(&stranger.write), b"x").await.status);
    }
    // Три-четыре попытки с чужим токеном вычерпывают ведро, после этого — 429 даже тому, кто угадал.
    assert_eq!(&statuses[..2], &[404, 404]);
    assert!(statuses.contains(&429), "statuses: {statuses:?}");
    assert_eq!(env.post(&pair, b"x").await.status, 429);
    env.clock.advance_secs(120);
    assert_eq!(env.post(&pair, b"x").await.status, 201);
    env.stop().await;
}

// ---------- Требование: Certificate pin ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn client_pins_the_certificate_fingerprint() {
    let env = Env::start().await;
    assert_eq!(env.fingerprint.len(), 64);
    // Верный отпечаток - соединение есть.
    let ok = request(env.addr, &env.fingerprint, "GET", "/v1/health", None, b"")
        .await
        .unwrap();
    assert_eq!(ok.status, 200);
    // Любой другой - отказ при рукопожатии, запрос до сервера не доходит.
    let wrong = b64_encode(&random_bytes::<8>()).to_lowercase();
    let err = request(env.addr, &wrong, "GET", "/v1/health", None, b"")
        .await
        .err()
        .expect("must be refused");
    assert!(err.contains("tls"), "unexpected error: {err}");
    // Отпечаток, который видит клиент, тот же, что сервер пишет в status.
    let on_disk = ampulse_server::tls::fingerprint_of_dir(&env.dir).unwrap();
    assert_eq!(on_disk, env.fingerprint);
    let status = ampulse_server::status::gather(&env.dir, true, None);
    assert_eq!(status.fingerprint.as_deref(), Some(env.fingerprint.as_str()));
    env.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_new_server_run_keeps_the_same_certificate() {
    let env = Env::start().await;
    let first = env.fingerprint.clone();
    let dir = env.dir.clone();
    env.server.shutdown().await;
    // Тот же каталог данных, новый запуск: отпечаток не меняется.
    let identity =
        ampulse_server::tls::Identity::load_or_create(&dir, Some("127.0.0.1"), common::START + 1000).unwrap();
    assert_eq!(identity.fingerprint(), first);
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn plain_tls12_clients_are_refused() {
    use tokio::io::AsyncWriteExt;
    let env = Env::start().await;
    let provider = std::sync::Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS12])
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(std::sync::Arc::new(NoCheck))
        .with_no_client_auth();
    let connector = tokio_rustls::TlsConnector::from(std::sync::Arc::new(config));
    let tcp = tokio::net::TcpStream::connect(env.addr).await.unwrap();
    let name = rustls::pki_types::ServerName::IpAddress(env.addr.ip().into());
    let result = connector.connect(name, tcp).await;
    assert!(result.is_err(), "TLS 1.2 must not be negotiated");
    if let Ok(mut stream) = result {
        let _ = stream.shutdown().await;
    }
    env.stop().await;
}

/// Проверка, которая ничего не проверяет: нужна только чтобы дойти до выбора версии TLS.
#[derive(Debug)]
struct NoCheck;

impl rustls::client::danger::ServerCertVerifier for NoCheck {
    fn verify_server_cert(
        &self,
        _: &rustls::pki_types::CertificateDer<'_>,
        _: &[rustls::pki_types::CertificateDer<'_>],
        _: &rustls::pki_types::ServerName<'_>,
        _: &[u8],
        _: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &rustls::pki_types::CertificateDer<'_>,
        _: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _: &[u8],
        _: &rustls::pki_types::CertificateDer<'_>,
        _: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}
