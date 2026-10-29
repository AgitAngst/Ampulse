//! Команды настоящего бинарника: `status --json`, `backup`, `install` (где её нет), `serve`.

mod common;

use std::process::{Command, Output, Stdio};
use std::time::{Duration, Instant};

use ampulse_server::clock::ManualClock;
use ampulse_server::codec::hash_token;
use ampulse_server::config::{Settings, DB_FILE};
use ampulse_server::store::{Limits, Store};
use ampulse_server::tls::Identity;
use common::{request, temp_dir, START};

fn bin() -> Command {
    Command::new(env!("CARGO_BIN_EXE_ampulse-server"))
}

fn run(args: &[&str]) -> Output {
    bin().args(args).output().expect("binary must start")
}

fn stdout(out: &Output) -> String {
    String::from_utf8_lossy(&out.stdout).into_owned()
}

fn stderr(out: &Output) -> String {
    String::from_utf8_lossy(&out.stderr).into_owned()
}

/// Каталог данных, каким его оставляет установка: сертификат, настройки, база с одним сообщением.
fn installed_dir(tag: &str) -> (std::path::PathBuf, String) {
    let dir = temp_dir(tag);
    let identity = Identity::load_or_create(&dir, Some("203.0.113.7"), START).unwrap();
    Settings {
        port: 8740,
        address: "203.0.113.7:8740".into(),
    }
    .save(&dir)
    .unwrap();
    let store = Store::open(
        &dir.join(DB_FILE),
        std::sync::Arc::new(ManualClock::new(START)),
        Limits::default(),
    )
    .unwrap();
    let (w, r) = (hash_token(b"w"), hash_token(b"r"));
    store.create_box("a", &w, &r).unwrap();
    store.post("a", &w, b"cipher").unwrap().unwrap();
    store.create_box("b", &w, &r).unwrap();
    (dir, identity.fingerprint())
}

#[test]
fn version_and_help() {
    let out = run(&["--version"]);
    assert!(out.status.success());
    assert_eq!(
        stdout(&out).trim(),
        format!("ampulse-server {}", env!("CARGO_PKG_VERSION"))
    );
    let help = run(&["--help"]);
    assert!(help.status.success());
    for command in ["serve", "install", "status", "backup", "uninstall"] {
        assert!(stdout(&help).contains(command), "help lacks {command}");
    }
}

#[test]
fn unknown_command_and_bad_flags_exit_with_2() {
    for args in [&["frobnicate"][..], &["install", "--port", "x"], &["status", "--nope"]] {
        let out = run(args);
        assert_eq!(out.status.code(), Some(2), "{args:?}");
        assert!(stderr(&out).starts_with("error:"), "{args:?}: {}", stderr(&out));
    }
}

#[test]
fn status_json_has_every_field_of_the_contract() {
    let (dir, fingerprint) = installed_dir("status");
    let out = run(&["status", "--json", "--data", dir.to_str().unwrap()]);
    assert!(out.status.success(), "{}", stderr(&out));
    let json: serde_json::Value = serde_json::from_str(stdout(&out).trim()).expect("one JSON object on stdout");
    assert_eq!(json["name"], "ampulse");
    assert_eq!(json["version"], env!("CARGO_PKG_VERSION"));
    assert!(json["running"].is_boolean());
    assert_eq!(json["address"], "203.0.113.7:8740");
    assert_eq!(json["fingerprint"], fingerprint.as_str());
    assert_eq!(json["boxes"], 2);
    assert_eq!(json["messages"], 1);
    assert!(json.get("disk_free").is_some());
    // Человеческий вид показывает то же.
    let text = stdout(&run(&["status", "--data", dir.to_str().unwrap()]));
    assert!(text.contains("203.0.113.7:8740") && text.contains(&fingerprint));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn status_of_a_machine_without_the_relay_still_prints_the_whole_object() {
    let dir = temp_dir("status-empty");
    let out = run(&["status", "--json", "--data", dir.to_str().unwrap()]);
    assert!(out.status.success());
    let json: serde_json::Value = serde_json::from_str(stdout(&out).trim()).unwrap();
    for key in [
        "name",
        "version",
        "running",
        "address",
        "fingerprint",
        "boxes",
        "messages",
        "disk_free",
    ] {
        assert!(json.get(key).is_some(), "missing {key}");
    }
    assert_eq!(json["running"], false);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn backup_writes_a_copy_and_refuses_to_overwrite_one() {
    let (dir, fingerprint) = installed_dir("backup");
    let to = temp_dir("backup-to").join("copy");
    let args = ["backup", "--data", dir.to_str().unwrap(), "--to", to.to_str().unwrap()];
    let out = run(&args);
    assert!(out.status.success(), "{}", stderr(&out));
    assert_eq!(
        ampulse_server::store::read_stats(&to.join(DB_FILE)).unwrap().messages,
        1
    );
    assert_eq!(ampulse_server::tls::fingerprint_of_dir(&to).unwrap(), fingerprint);
    let again = run(&args);
    assert_eq!(again.status.code(), Some(1));
    assert!(stderr(&again).contains("already exists"));
    // Без базы копировать нечего.
    let empty = temp_dir("backup-empty");
    let none = run(&[
        "backup",
        "--data",
        empty.to_str().unwrap(),
        "--to",
        to.join("x").to_str().unwrap(),
    ]);
    assert_eq!(none.status.code(), Some(1));
    let _ = std::fs::remove_dir_all(&dir);
}

#[cfg(not(target_os = "linux"))]
#[test]
fn install_and_uninstall_explain_that_they_need_linux() {
    for args in [&["install", "--address", "203.0.113.7"][..], &["uninstall"]] {
        let out = run(args);
        assert_eq!(out.status.code(), Some(1));
        assert!(stderr(&out).contains("Linux"), "{}", stderr(&out));
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn serve_starts_answers_over_pinned_tls_and_keeps_its_certificate() {
    let dir = temp_dir("serve");
    let port = {
        let probe = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        probe.local_addr().unwrap().port()
    };
    let addr = format!("127.0.0.1:{port}");
    let spawn = || {
        bin()
            .args(["serve", "--data", dir.to_str().unwrap(), "--addr", &addr])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap()
    };

    let mut first = spawn();
    let fingerprint = wait_until_healthy(&dir, &addr).await;
    assert_eq!(fingerprint.len(), 64);
    first.kill().unwrap();
    first.wait().unwrap();

    // Второй запуск на тех же данных: тот же отпечаток.
    let mut second = spawn();
    let again = wait_until_healthy(&dir, &addr).await;
    assert_eq!(again, fingerprint);
    second.kill().unwrap();
    second.wait().unwrap();
    let _ = std::fs::remove_dir_all(&dir);
}

/// Ждать, пока служба не ответит на `/v1/health`; возвращает отпечаток из каталога данных.
async fn wait_until_healthy(dir: &std::path::Path, addr: &str) -> String {
    let addr: std::net::SocketAddr = addr.parse().unwrap();
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        if let Some(fingerprint) = ampulse_server::tls::fingerprint_of_dir(dir) {
            if let Ok(reply) = request(addr, &fingerprint, "GET", "/v1/health", None, b"").await {
                if reply.status == 200 {
                    return fingerprint;
                }
            }
        }
        assert!(Instant::now() < deadline, "the server did not start");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}
