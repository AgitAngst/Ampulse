//! Протокол v1: HTTPS + JSON.
//!
//! | Запрос | Что делает | Ответ |
//! |---|---|---|
//! | `PUT /v1/box/{id}` | создать ящик; тело `{"write_hash","read_hash"}` (Base64URL от SHA-256 токенов) | 201 создан, 204 повтор с теми же хэшами, 409 другие хэши |
//! | `POST /v1/box/{id}` | положить сообщение; `Authorization: Bearer <write>`, тело — шифртекст до 8 КиБ | 201 `{"seq":N}` |
//! | `GET /v1/box/{id}?after=N&wait=S` | новые сообщения; `Bearer <read>`; `wait` до 25 с — длинный опрос | 200 `{"messages":[{"seq","at","data"}]}` |
//! | `DELETE /v1/box/{id}?upto=N` | подтвердить и стереть до `N` включительно; `Bearer <read>` | 204 |
//! | `GET /v1/health` | живость, ящиков не касается | 200 `{"ok":true,"version":"…"}` |
//!
//! Идентификатор ящика — Base64URL от 16 случайных байт (22 знака). Токен — Base64URL от
//! секретных байт клиента; сервер хранит SHA-256 этих байт. Неизвестный ящик, чужой токен,
//! токен не той роли, неверный вид идентификатора и пропавший заголовок отвечают одинаково:
//! 404 `{"error":"not_found"}`. Прочие ошибки: 400, 413 (сообщение больше 8 КиБ),
//! 429 (`rate_limited` с `Retry-After` или `box_full`), 507 (`server_full`), 500.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::{to_bytes, Body};
use axum::extract::rejection::QueryRejection;
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, put};
use axum::{middleware, Json, Router};
use serde::{Deserialize, Serialize};
use tokio::sync::Notify;

use crate::clock::Clock;
use crate::codec::{b64_decode, b64_encode, hash_token, random_bytes, valid_box_id, TokenHash, HASH_LEN};
use crate::ratelimit::{RateConfig, RateLimiter};
use crate::store::{CreateBox, PostError, Store, StoredMessage};
use crate::tls::Identity;

/// Длинный опрос ждёт не дольше.
pub const MAX_WAIT_SECS: u64 = 25;
/// Сколько сообщений отдаёт один ответ.
const PAGE: usize = 100;
/// Тело запроса на создание ящика — крошечное.
const CREATE_BODY_LIMIT: usize = 1024;

/// Настройки, которые тесты вправе менять (в боевом запуске — умолчания).
#[derive(Debug, Clone)]
pub struct ApiOptions {
    /// Потолок `wait`.
    pub max_wait: Duration,
    /// Сколько опросов может ждать одновременно; сверх этого отвечаем сразу.
    pub max_waiters: usize,
    /// Как часто убирать просроченное.
    pub sweep_every: Duration,
}

impl Default for ApiOptions {
    fn default() -> Self {
        Self {
            max_wait: Duration::from_secs(MAX_WAIT_SECS),
            max_waiters: 1000,
            sweep_every: Duration::from_secs(600),
        }
    }
}

/// Будильники по ящикам: кто ждёт в длинном опросе, того будит новое сообщение.
#[derive(Default)]
struct Notifier {
    map: Mutex<HashMap<String, Arc<Notify>>>,
}

impl Notifier {
    fn subscribe(&self, id: &str) -> Arc<Notify> {
        let mut map = self.map.lock().unwrap_or_else(|p| p.into_inner());
        map.entry(id.to_owned()).or_default().clone()
    }

    fn wake(&self, id: &str) {
        let map = self.map.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(notify) = map.get(id) {
            notify.notify_waiters();
        }
    }

    /// Убрать будильник, если его больше никто не держит.
    fn release(&self, id: &str) {
        let mut map = self.map.lock().unwrap_or_else(|p| p.into_inner());
        if map.get(id).is_some_and(|n| Arc::strong_count(n) <= 2) {
            map.remove(id);
        }
    }
}

struct Inner {
    store: Arc<Store>,
    limiter: RateLimiter,
    notifier: Notifier,
    options: ApiOptions,
    waiters: AtomicUsize,
}

#[derive(Clone)]
pub struct AppState(Arc<Inner>);

/// Отдаёт место в очереди ожидающих и будильник, когда опрос закончился (в том числе отменой).
struct WaitSlot<'a> {
    inner: &'a Inner,
    id: &'a str,
    notify: Arc<Notify>,
}

impl Drop for WaitSlot<'_> {
    fn drop(&mut self) {
        // Свой `Arc` ещё жив: его и запись в таблице считает `release`.
        self.inner.notifier.release(self.id);
        self.inner.waiters.fetch_sub(1, Ordering::SeqCst);
    }
}

enum ApiError {
    NotFound,
    BadRequest(&'static str),
    TooLarge,
    Conflict,
    BoxFull,
    ServerFull,
    RateLimited(u64),
    Internal,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code) = match &self {
            ApiError::NotFound => (StatusCode::NOT_FOUND, "not_found"),
            ApiError::BadRequest(code) => (StatusCode::BAD_REQUEST, *code),
            ApiError::TooLarge => (StatusCode::PAYLOAD_TOO_LARGE, "too_large"),
            ApiError::Conflict => (StatusCode::CONFLICT, "conflict"),
            ApiError::BoxFull => (StatusCode::TOO_MANY_REQUESTS, "box_full"),
            ApiError::ServerFull => (StatusCode::INSUFFICIENT_STORAGE, "server_full"),
            ApiError::RateLimited(_) => (StatusCode::TOO_MANY_REQUESTS, "rate_limited"),
            ApiError::Internal => (StatusCode::INTERNAL_SERVER_ERROR, "internal"),
        };
        let mut response = (status, Json(serde_json::json!({ "error": code }))).into_response();
        if let ApiError::RateLimited(secs) = self {
            if let Ok(value) = HeaderValue::from_str(&secs.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
        }
        response
    }
}

fn db_error(e: rusqlite::Error) -> ApiError {
    tracing::error!("database error: {e}");
    ApiError::Internal
}

impl Inner {
    /// Взять жетон у клиента или отказать с 429.
    fn rate(&self, peer: SocketAddr) -> Result<(), ApiError> {
        self.limiter.check(peer.ip()).map_err(ApiError::RateLimited)
    }

    /// Ящик не найден или токен не подошёл: наказать клиента жетонами и ответить одинаково.
    fn denied(&self, peer: SocketAddr) -> ApiError {
        self.limiter.penalize(peer.ip());
        ApiError::NotFound
    }

    fn fetch(
        &self,
        peer: SocketAddr,
        id: &str,
        hash: &TokenHash,
        after: u64,
        touch: bool,
    ) -> Result<Vec<StoredMessage>, ApiError> {
        match self.store.fetch(id, hash, after, PAGE, touch).map_err(db_error)? {
            Ok(messages) => Ok(messages),
            Err(_) => Err(self.denied(peer)),
        }
    }

    /// Забрать новые сообщения; при пустом ящике и `wait > 0` — ждать до `wait`.
    async fn read(
        &self,
        peer: SocketAddr,
        id: &str,
        hash: &TokenHash,
        after: u64,
        wait: Duration,
    ) -> Result<Vec<StoredMessage>, ApiError> {
        let first = self.fetch(peer, id, hash, after, true)?;
        if !first.is_empty() || wait.is_zero() {
            return Ok(first);
        }
        if self.waiters.fetch_add(1, Ordering::SeqCst) >= self.options.max_waiters {
            self.waiters.fetch_sub(1, Ordering::SeqCst);
            return Ok(first);
        }
        let slot = WaitSlot {
            inner: self,
            id,
            notify: self.notifier.subscribe(id),
        };
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            // Сначала встаём в очередь на будильник, потом смотрим ящик: сообщение, пришедшее
            // между этими шагами, не потеряется.
            let notified = slot.notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            let messages = self.fetch(peer, id, hash, after, false)?;
            if !messages.is_empty() {
                return Ok(messages);
            }
            if tokio::time::timeout_at(deadline, notified).await.is_err() {
                return Ok(Vec::new());
            }
        }
    }
}

/// Токен из заголовка → его хэш. Нет заголовка или он испорчен — случайный хэш, который ничему
/// не равен: запрос всё равно доходит до базы, и отказ выглядит как любой другой.
fn bearer_hash(headers: &HeaderMap) -> TokenHash {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .and_then(|token| b64_decode(token.trim()))
        .filter(|raw| !raw.is_empty())
        .map(|raw| hash_token(&raw))
        .unwrap_or_else(random_bytes::<HASH_LEN>)
}

#[derive(Deserialize)]
struct CreateRequest {
    write_hash: String,
    read_hash: String,
}

fn parse_hash(text: &str) -> Option<TokenHash> {
    b64_decode(text)?.try_into().ok()
}

async fn create(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(id): Path<String>,
    body: Body,
) -> Result<Response, ApiError> {
    let app = &app.0;
    app.rate(peer)?;
    if !valid_box_id(&id) {
        return Err(ApiError::BadRequest("bad_id"));
    }
    let bytes = to_bytes(body, CREATE_BODY_LIMIT)
        .await
        .map_err(|_| ApiError::TooLarge)?;
    let request: CreateRequest = serde_json::from_slice(&bytes).map_err(|_| ApiError::BadRequest("bad_body"))?;
    let (Some(write), Some(read)) = (parse_hash(&request.write_hash), parse_hash(&request.read_hash)) else {
        return Err(ApiError::BadRequest("bad_hash"));
    };
    match app.store.create_box(&id, &write, &read).map_err(db_error)? {
        CreateBox::Created => Ok((StatusCode::CREATED, Json(serde_json::json!({}))).into_response()),
        CreateBox::Exists => Ok(StatusCode::NO_CONTENT.into_response()),
        CreateBox::Conflict => Err(ApiError::Conflict),
        CreateBox::ServerFull => Err(ApiError::ServerFull),
    }
}

#[derive(Serialize)]
struct PostReply {
    seq: u64,
}

async fn post_message(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(id): Path<String>,
    headers: HeaderMap,
    body: Body,
) -> Result<Response, ApiError> {
    let app = &app.0;
    app.rate(peer)?;
    if !valid_box_id(&id) {
        return Err(app.denied(peer));
    }
    let limit = app.store.limits().max_message_bytes;
    let bytes = to_bytes(body, limit).await.map_err(|_| ApiError::TooLarge)?;
    if bytes.is_empty() {
        return Err(ApiError::BadRequest("empty_body"));
    }
    let hash = bearer_hash(&headers);
    match app.store.post(&id, &hash, &bytes).map_err(db_error)? {
        Ok(seq) => {
            app.notifier.wake(&id);
            Ok((StatusCode::CREATED, Json(PostReply { seq })).into_response())
        }
        Err(PostError::Denied) => Err(app.denied(peer)),
        Err(PostError::TooLarge) => Err(ApiError::TooLarge),
        Err(PostError::BoxFull) => Err(ApiError::BoxFull),
        Err(PostError::ServerFull) => Err(ApiError::ServerFull),
    }
}

#[derive(Deserialize)]
struct ReadParams {
    after: Option<u64>,
    wait: Option<u64>,
    upto: Option<u64>,
}

#[derive(Serialize)]
struct MessageOut {
    seq: u64,
    at: i64,
    data: String,
}

#[derive(Serialize)]
struct ReadReply {
    messages: Vec<MessageOut>,
}

async fn read_messages(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(id): Path<String>,
    headers: HeaderMap,
    params: Result<Query<ReadParams>, QueryRejection>,
) -> Result<Response, ApiError> {
    let app = &app.0;
    app.rate(peer)?;
    if !valid_box_id(&id) {
        return Err(app.denied(peer));
    }
    let Ok(Query(params)) = params else {
        return Err(ApiError::BadRequest("bad_query"));
    };
    let hash = bearer_hash(&headers);
    let wait = Duration::from_secs(params.wait.unwrap_or(0)).min(app.options.max_wait);
    let messages = app.read(peer, &id, &hash, params.after.unwrap_or(0), wait).await?;
    let messages = messages
        .into_iter()
        .map(|m| MessageOut {
            seq: m.seq,
            at: m.created,
            data: b64_encode(&m.body),
        })
        .collect();
    Ok(Json(ReadReply { messages }).into_response())
}

async fn acknowledge(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(id): Path<String>,
    headers: HeaderMap,
    params: Result<Query<ReadParams>, QueryRejection>,
) -> Result<Response, ApiError> {
    let app = &app.0;
    app.rate(peer)?;
    if !valid_box_id(&id) {
        return Err(app.denied(peer));
    }
    let Ok(Query(params)) = params else {
        return Err(ApiError::BadRequest("bad_query"));
    };
    let Some(upto) = params.upto else {
        return Err(ApiError::BadRequest("upto_required"));
    };
    let hash = bearer_hash(&headers);
    match app.store.ack(&id, &hash, upto).map_err(db_error)? {
        Ok(()) => Ok(StatusCode::NO_CONTENT.into_response()),
        Err(_) => Err(app.denied(peer)),
    }
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({ "ok": true, "version": env!("CARGO_PKG_VERSION") }))
}

/// Маршруты сервера вместе с состоянием.
pub fn router(store: Arc<Store>, clock: Arc<dyn Clock>, rate: RateConfig, options: ApiOptions) -> (Router, AppState) {
    let state = AppState(Arc::new(Inner {
        store,
        limiter: RateLimiter::new(rate, clock),
        notifier: Notifier::default(),
        options,
        waiters: AtomicUsize::new(0),
    }));
    let router = Router::new()
        .route("/v1/health", get(health))
        .route(
            "/v1/box/{id}",
            put(create).post(post_message).get(read_messages).delete(acknowledge),
        )
        .layer(middleware::map_response(|mut response: Response| async move {
            response
                .headers_mut()
                .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }))
        .with_state(state.clone());
    (router, state)
}

/// Запущенный сервер.
pub struct RunningServer {
    /// Адрес, на котором слушает (при порте 0 — выбранный системой).
    pub addr: SocketAddr,
    /// Отпечаток сертификата, SHA-256, строчный hex.
    pub fingerprint: String,
    handle: axum_server::Handle<SocketAddr>,
    serve: tokio::task::JoinHandle<std::io::Result<()>>,
    sweep: tokio::task::JoinHandle<()>,
}

impl RunningServer {
    /// Остановить: дать текущим запросам до 5 секунд.
    pub async fn shutdown(self) {
        self.handle.graceful_shutdown(Some(Duration::from_secs(5)));
        self.sweep.abort();
        let _ = self.serve.await;
    }

    /// Ждать, пока сервер не остановится сам (ошибка).
    pub async fn wait(self) -> std::io::Result<()> {
        let result = self.serve.await.unwrap_or_else(|e| Err(std::io::Error::other(e)));
        self.sweep.abort();
        result
    }
}

/// Запустить HTTPS-сервер и фоновую уборку.
pub async fn start(
    addr: SocketAddr,
    store: Arc<Store>,
    identity: &Identity,
    clock: Arc<dyn Clock>,
    rate: RateConfig,
    options: ApiOptions,
) -> Result<RunningServer, String> {
    let tls = axum_server::tls_rustls::RustlsConfig::from_config(identity.server_config()?);
    let sweep_every = options.sweep_every;
    let (router, state) = router(store.clone(), clock, rate, options);
    let handle = axum_server::Handle::new();
    let server = axum_server::bind_rustls(addr, tls).handle(handle.clone());
    let serve = tokio::spawn(async move {
        server
            .serve(router.into_make_service_with_connect_info::<SocketAddr>())
            .await
    });
    let Some(bound) = handle.listening().await else {
        return match serve.await {
            Ok(Err(e)) => Err(format!("cannot listen on {addr}: {e}")),
            _ => Err(format!("cannot listen on {addr}")),
        };
    };
    let sweep = tokio::spawn(async move {
        loop {
            tokio::time::sleep(sweep_every).await;
            match store.sweep() {
                Ok(done) if done.messages + done.boxes > 0 => {
                    tracing::info!("sweep removed {} messages and {} boxes", done.messages, done.boxes)
                }
                Ok(_) => {}
                Err(e) => tracing::error!("sweep failed: {e}"),
            }
            state.0.limiter.prune();
        }
    });
    Ok(RunningServer {
        addr: bound,
        fingerprint: identity.fingerprint(),
        handle,
        serve,
        sweep,
    })
}
