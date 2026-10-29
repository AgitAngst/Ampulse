//! Хранилище на SQLite: ящики и шифртекст сообщений.
//!
//! В базе лежат только случайные идентификаторы, SHA-256 токенов, размеры, времена и
//! шифртекст как есть: ключа, которым его можно прочитать, у сервера нет. Журнал —
//! обычный (`DELETE`, не WAL): так базу безопасно открывает на чтение и копирует сам
//! `status`/`backup` от имени root, не оставляя рядом файлов с чужим владельцем.

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use rusqlite::{params, Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};

use crate::clock::Clock;
use crate::codec::{ct_eq, TokenHash, HASH_LEN};

/// Срок хранения сообщения: 7 дней.
pub const MESSAGE_TTL_SECS: i64 = 7 * 86_400;
/// Ящик без обращений столько живёт: 90 дней.
pub const BOX_IDLE_SECS: i64 = 90 * 86_400;
/// Самое большое сообщение (шифртекст): 8 КиБ.
pub const MAX_MESSAGE_BYTES: usize = 8 * 1024;
/// Сообщений в ящике не больше.
pub const MAX_BOX_MESSAGES: u64 = 200;
/// Байт сообщений в ящике не больше: 1 МиБ.
pub const MAX_BOX_BYTES: u64 = 1024 * 1024;
/// Общий потолок сообщений на сервере по умолчанию: 512 МиБ.
pub const DEFAULT_MAX_TOTAL_BYTES: u64 = 512 * 1024 * 1024;
/// Общий потолок числа ящиков по умолчанию.
pub const DEFAULT_MAX_BOXES: u64 = 10_000;
/// «Последнее обращение» при чтении обновляется не чаще раза в час: срок считается сутками.
const TOUCH_EVERY_SECS: i64 = 3_600;

/// Сроки и квоты. Умолчания — по спецификации; тесты и `serve` могут их менять.
#[derive(Debug, Clone)]
pub struct Limits {
    pub message_ttl_secs: i64,
    pub box_idle_secs: i64,
    pub max_message_bytes: usize,
    pub max_box_messages: u64,
    pub max_box_bytes: u64,
    pub max_total_bytes: u64,
    pub max_boxes: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            message_ttl_secs: MESSAGE_TTL_SECS,
            box_idle_secs: BOX_IDLE_SECS,
            max_message_bytes: MAX_MESSAGE_BYTES,
            max_box_messages: MAX_BOX_MESSAGES,
            max_box_bytes: MAX_BOX_BYTES,
            max_total_bytes: DEFAULT_MAX_TOTAL_BYTES,
            max_boxes: DEFAULT_MAX_BOXES,
        }
    }
}

/// Каким токеном ящик открывают.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Write,
    Read,
}

/// Итог создания ящика.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CreateBox {
    Created,
    /// Ящик уже есть с теми же хэшами (повтор запроса).
    Exists,
    /// Ящик уже есть с другими хэшами.
    Conflict,
    /// На сервере больше нет места для ящиков.
    ServerFull,
}

/// Почему сообщение не принято.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PostError {
    /// Нет такого ящика или токен не подошёл — снаружи это одно и то же.
    Denied,
    TooLarge,
    /// В ящике 200 сообщений или 1 МиБ.
    BoxFull,
    /// Общий потолок сервера.
    ServerFull,
}

/// Нет такого ящика или токен не подошёл.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Denied;

/// Сообщение как оно лежит в ящике.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredMessage {
    pub seq: u64,
    /// Когда принято, секунды от 1970 года.
    pub created: i64,
    pub body: Vec<u8>,
}

/// Счётчики для `status`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Stats {
    pub boxes: u64,
    pub messages: u64,
    pub bytes: u64,
}

/// Что убрала уборка.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SweepStats {
    pub messages: u64,
    pub boxes: u64,
}

pub struct Store {
    conn: Mutex<Connection>,
    clock: Arc<dyn Clock>,
    limits: Limits,
}

struct BoxRow {
    write_hash: Vec<u8>,
    read_hash: Vec<u8>,
    last_used: i64,
    // SQLite хранит только знаковые целые, поэтому и счётчики здесь знаковые.
    msgs: i64,
    bytes: i64,
    next_seq: i64,
}

const SCHEMA: &str = "
PRAGMA foreign_keys = ON;
PRAGMA secure_delete = ON;
CREATE TABLE IF NOT EXISTS boxes (
    id         TEXT PRIMARY KEY,
    write_hash BLOB NOT NULL,
    read_hash  BLOB NOT NULL,
    created    INTEGER NOT NULL,
    last_used  INTEGER NOT NULL,
    next_seq   INTEGER NOT NULL DEFAULT 1,
    msgs       INTEGER NOT NULL DEFAULT 0,
    bytes      INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS messages (
    box_id  TEXT NOT NULL,
    seq     INTEGER NOT NULL,
    created INTEGER NOT NULL,
    size    INTEGER NOT NULL,
    body    BLOB NOT NULL,
    PRIMARY KEY (box_id, seq)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS messages_created ON messages (created);
CREATE INDEX IF NOT EXISTS boxes_last_used ON boxes (last_used);
PRAGMA user_version = 1;
";

impl Store {
    /// Открыть (и при первом разе создать) базу. На Linux файл получает права 0600.
    pub fn open(path: &Path, clock: Arc<dyn Clock>, limits: Limits) -> rusqlite::Result<Self> {
        let conn =
            Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE)?;
        crate::fsutil::secure_file(path);
        Self::init(conn, clock, limits)
    }

    /// База в памяти — для тестов.
    pub fn open_in_memory(clock: Arc<dyn Clock>, limits: Limits) -> rusqlite::Result<Self> {
        Self::init(Connection::open_in_memory()?, clock, limits)
    }

    fn init(conn: Connection, clock: Arc<dyn Clock>, limits: Limits) -> rusqlite::Result<Self> {
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self {
            conn: Mutex::new(conn),
            clock,
            limits,
        })
    }

    pub fn limits(&self) -> &Limits {
        &self.limits
    }

    fn lock(&self) -> MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Создать ящик. Повтор с теми же хэшами — [`CreateBox::Exists`], с другими — [`CreateBox::Conflict`].
    pub fn create_box(&self, id: &str, write_hash: &TokenHash, read_hash: &TokenHash) -> rusqlite::Result<CreateBox> {
        let now = self.clock.now_secs();
        let mut conn = self.lock();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let outcome = match find_box(&tx, id, now, &self.limits)? {
            Some(row) => {
                let same = ct_eq(&row.write_hash, write_hash) & ct_eq(&row.read_hash, read_hash);
                if same {
                    tx.execute("UPDATE boxes SET last_used = ?2 WHERE id = ?1", params![id, now])?;
                    CreateBox::Exists
                } else {
                    CreateBox::Conflict
                }
            }
            None => {
                let boxes: i64 = tx.query_row("SELECT COUNT(*) FROM boxes", [], |r| r.get(0))?;
                if boxes as u64 >= self.limits.max_boxes {
                    CreateBox::ServerFull
                } else {
                    tx.execute(
                        "INSERT INTO boxes (id, write_hash, read_hash, created, last_used) VALUES (?1, ?2, ?3, ?4, ?4)",
                        params![id, &write_hash[..], &read_hash[..], now],
                    )?;
                    CreateBox::Created
                }
            }
        };
        tx.commit()?;
        Ok(outcome)
    }

    /// Положить сообщение; возвращает его номер в ящике.
    pub fn post(&self, id: &str, write_hash: &TokenHash, body: &[u8]) -> rusqlite::Result<Result<u64, PostError>> {
        let now = self.clock.now_secs();
        let mut conn = self.lock();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let Some(mut row) = authorize(&tx, id, Role::Write, write_hash, now, &self.limits)? else {
            // Ящик, у которого вышел срок, убран по дороге: это надо сохранить.
            tx.commit()?;
            return Ok(Err(PostError::Denied));
        };
        if body.len() > self.limits.max_message_bytes {
            return Ok(Err(PostError::TooLarge));
        }
        // Просроченное в этом ящике не занимает квоту.
        let (gone, gone_bytes) = delete_where(
            &tx,
            "box_id = ?1 AND created <= ?2",
            params![id, now - self.limits.message_ttl_secs],
        )?;
        row.msgs -= gone;
        row.bytes -= gone_bytes;
        let size = body.len() as i64;
        if row.msgs as u64 >= self.limits.max_box_messages || (row.bytes + size) as u64 > self.limits.max_box_bytes {
            // Чистку просроченного оставляем: она законна и без этого сообщения.
            tx.execute(
                "UPDATE boxes SET msgs = ?2, bytes = ?3 WHERE id = ?1",
                params![id, row.msgs, row.bytes],
            )?;
            tx.commit()?;
            return Ok(Err(PostError::BoxFull));
        }
        let total: i64 = tx.query_row("SELECT COALESCE(SUM(bytes), 0) FROM boxes", [], |r| r.get(0))?;
        if (total + size) as u64 > self.limits.max_total_bytes {
            tx.commit()?;
            return Ok(Err(PostError::ServerFull));
        }
        let seq = row.next_seq;
        tx.execute(
            "INSERT INTO messages (box_id, seq, created, size, body) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, seq, now, size, body],
        )?;
        tx.execute(
            "UPDATE boxes SET next_seq = ?2, msgs = ?3, bytes = ?4, last_used = ?5 WHERE id = ?1",
            params![id, seq + 1, row.msgs + 1, row.bytes + size, now],
        )?;
        tx.commit()?;
        Ok(Ok(seq as u64))
    }

    /// Новые сообщения с номером больше `after` (не больше `limit` штук). `touch` отмечает обращение к
    /// ящику; повторные заглядывания внутри одного длинного опроса его не отмечают.
    pub fn fetch(
        &self,
        id: &str,
        read_hash: &TokenHash,
        after: u64,
        limit: usize,
        touch: bool,
    ) -> rusqlite::Result<Result<Vec<StoredMessage>, Denied>> {
        let now = self.clock.now_secs();
        let mut conn = self.lock();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let Some(row) = authorize(&tx, id, Role::Read, read_hash, now, &self.limits)? else {
            tx.commit()?;
            return Ok(Err(Denied));
        };
        if touch && row.last_used + TOUCH_EVERY_SECS <= now {
            tx.execute("UPDATE boxes SET last_used = ?2 WHERE id = ?1", params![id, now])?;
        }
        let messages = {
            let mut stmt = tx.prepare(
                "SELECT seq, created, body FROM messages
                 WHERE box_id = ?1 AND seq > ?2 AND created > ?3 ORDER BY seq LIMIT ?4",
            )?;
            let rows = stmt.query_map(
                params![id, to_db(after), now - self.limits.message_ttl_secs, limit as i64],
                |r| {
                    Ok(StoredMessage {
                        seq: r.get::<_, i64>(0)? as u64,
                        created: r.get(1)?,
                        body: r.get(2)?,
                    })
                },
            )?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        tx.commit()?;
        Ok(Ok(messages))
    }

    /// Подтвердить получение: стереть сообщения с номерами до `upto` включительно.
    pub fn ack(&self, id: &str, read_hash: &TokenHash, upto: u64) -> rusqlite::Result<Result<(), Denied>> {
        let now = self.clock.now_secs();
        let mut conn = self.lock();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let Some(row) = authorize(&tx, id, Role::Read, read_hash, now, &self.limits)? else {
            tx.commit()?;
            return Ok(Err(Denied));
        };
        let (gone, gone_bytes) = delete_where(&tx, "box_id = ?1 AND seq <= ?2", params![id, to_db(upto)])?;
        tx.execute(
            "UPDATE boxes SET msgs = ?2, bytes = ?3, last_used = ?4 WHERE id = ?1",
            params![id, row.msgs - gone, row.bytes - gone_bytes, now],
        )?;
        tx.commit()?;
        Ok(Ok(()))
    }

    /// Уборка: просроченные сообщения и ящики без обращений.
    pub fn sweep(&self) -> rusqlite::Result<SweepStats> {
        let now = self.clock.now_secs();
        let mut conn = self.lock();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut stats = SweepStats::default();

        let cutoff = now - self.limits.message_ttl_secs;
        let per_box: Vec<(String, i64, i64)> = {
            let mut stmt = tx.prepare(
                "SELECT box_id, COUNT(*), COALESCE(SUM(size), 0) FROM messages WHERE created <= ?1 GROUP BY box_id",
            )?;
            let rows = stmt.query_map([cutoff], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        tx.execute("DELETE FROM messages WHERE created <= ?1", [cutoff])?;
        for (id, count, bytes) in per_box {
            tx.execute(
                "UPDATE boxes SET msgs = msgs - ?2, bytes = bytes - ?3 WHERE id = ?1",
                params![id, count, bytes],
            )?;
            stats.messages += count as u64;
        }

        let idle: Vec<String> = {
            let mut stmt = tx.prepare("SELECT id FROM boxes WHERE last_used <= ?1")?;
            let rows = stmt.query_map([now - self.limits.box_idle_secs], |r| r.get(0))?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        for id in &idle {
            let (gone, _) = delete_where(&tx, "box_id = ?1", params![id])?;
            stats.messages += gone as u64;
            tx.execute("DELETE FROM boxes WHERE id = ?1", [id])?;
            stats.boxes += 1;
        }
        tx.commit()?;
        Ok(stats)
    }

    pub fn stats(&self) -> rusqlite::Result<Stats> {
        read_stats_of(&self.lock())
    }
}

/// Ящик по номеру. Ящик без обращений дольше срока здесь же и убирается.
fn find_box(tx: &Transaction<'_>, id: &str, now: i64, limits: &Limits) -> rusqlite::Result<Option<BoxRow>> {
    let row = tx
        .query_row(
            "SELECT write_hash, read_hash, last_used, msgs, bytes, next_seq FROM boxes WHERE id = ?1",
            [id],
            |r| {
                Ok(BoxRow {
                    write_hash: r.get(0)?,
                    read_hash: r.get(1)?,
                    last_used: r.get(2)?,
                    msgs: r.get(3)?,
                    bytes: r.get(4)?,
                    next_seq: r.get(5)?,
                })
            },
        )
        .optional()?;
    match row {
        Some(row) if row.last_used + limits.box_idle_secs <= now => {
            delete_where(tx, "box_id = ?1", params![id])?;
            tx.execute("DELETE FROM boxes WHERE id = ?1", [id])?;
            Ok(None)
        }
        other => Ok(other),
    }
}

/// Ящик, если он есть и токен подошёл. Время работы не зависит от того, чего не оказалось: нет
/// ящика — сравнивается с пустышкой, как и при чужом токене.
fn authorize(
    tx: &Transaction<'_>,
    id: &str,
    role: Role,
    hash: &TokenHash,
    now: i64,
    limits: &Limits,
) -> rusqlite::Result<Option<BoxRow>> {
    const DUMMY: [u8; HASH_LEN] = [0; HASH_LEN];
    let row = find_box(tx, id, now, limits)?;
    let expected: &[u8] = match (&row, role) {
        (Some(row), Role::Write) => &row.write_hash,
        (Some(row), Role::Read) => &row.read_hash,
        (None, _) => &DUMMY,
    };
    let ok = ct_eq(expected, hash);
    Ok(if ok { row } else { None })
}

/// Стереть сообщения по условию; возвращает их число и размер.
fn delete_where(tx: &Transaction<'_>, cond: &str, args: &[&dyn rusqlite::ToSql]) -> rusqlite::Result<(i64, i64)> {
    let (count, bytes): (i64, i64) = tx.query_row(
        &format!("SELECT COUNT(*), COALESCE(SUM(size), 0) FROM messages WHERE {cond}"),
        args,
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    if count > 0 {
        tx.execute(&format!("DELETE FROM messages WHERE {cond}"), args)?;
    }
    Ok((count, bytes))
}

fn read_stats_of(conn: &Connection) -> rusqlite::Result<Stats> {
    conn.query_row(
        "SELECT COUNT(*), COALESCE(SUM(msgs), 0), COALESCE(SUM(bytes), 0) FROM boxes",
        [],
        |r| {
            Ok(Stats {
                boxes: r.get::<_, i64>(0)? as u64,
                messages: r.get::<_, i64>(1)? as u64,
                bytes: r.get::<_, i64>(2)? as u64,
            })
        },
    )
}

/// Число для SQLite: он не знает беззнаковых.
fn to_db(n: u64) -> i64 {
    n.min(i64::MAX as u64) as i64
}

/// Открыть базу только для чтения (для `status` и `backup`: файлов рядом не появляется).
pub fn open_read_only(path: &Path) -> rusqlite::Result<Connection> {
    let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    conn.busy_timeout(Duration::from_secs(5))?;
    Ok(conn)
}

/// Счётчики базы без запуска службы.
pub fn read_stats(path: &Path) -> rusqlite::Result<Stats> {
    read_stats_of(&open_read_only(path)?)
}

/// Согласованная копия базы в новый файл (`VACUUM INTO`): служба при этом может работать.
pub fn copy_database(src: &Path, dest: &Path) -> rusqlite::Result<()> {
    let conn = open_read_only(src)?;
    conn.execute("VACUUM INTO ?1", [dest.to_string_lossy().as_ref()])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::ManualClock;
    use crate::codec::hash_token;

    const DAY: i64 = 86_400;

    fn store_with(limits: Limits) -> (Store, Arc<ManualClock>) {
        let clock = Arc::new(ManualClock::new(1_800_000_000));
        (Store::open_in_memory(clock.clone(), limits).unwrap(), clock)
    }

    fn tokens() -> (TokenHash, TokenHash) {
        (hash_token(b"write-token"), hash_token(b"read-token"))
    }

    #[test]
    fn create_is_idempotent_and_detects_conflict() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        assert_eq!(store.create_box("a", &w, &r).unwrap(), CreateBox::Created);
        assert_eq!(store.create_box("a", &w, &r).unwrap(), CreateBox::Exists);
        assert_eq!(store.create_box("a", &r, &w).unwrap(), CreateBox::Conflict);
        assert_eq!(store.stats().unwrap().boxes, 1);
    }

    #[test]
    fn write_token_does_not_read_and_read_token_does_not_write() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        assert_eq!(store.post("a", &r, b"x").unwrap(), Err(PostError::Denied));
        assert_eq!(store.post("a", &w, b"x").unwrap(), Ok(1));
        assert_eq!(store.fetch("a", &w, 0, 10, true).unwrap(), Err(Denied));
        assert_eq!(store.fetch("zzz", &r, 0, 10, true).unwrap(), Err(Denied));
        assert_eq!(store.fetch("a", &r, 0, 10, true).unwrap().unwrap().len(), 1);
    }

    #[test]
    fn unknown_box_with_zero_hash_is_still_denied() {
        let (store, _) = store_with(Limits::default());
        assert_eq!(store.post("none", &[0; 32], b"x").unwrap(), Err(PostError::Denied));
    }

    #[test]
    fn sequence_numbers_grow_and_are_not_reused_after_ack() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        for expected in 1..=5 {
            assert_eq!(store.post("a", &w, b"m").unwrap(), Ok(expected));
        }
        store.ack("a", &r, 5).unwrap().unwrap();
        assert_eq!(store.post("a", &w, b"m").unwrap(), Ok(6));
        let left = store.fetch("a", &r, 0, 10, true).unwrap().unwrap();
        assert_eq!(left.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![6]);
    }

    #[test]
    fn ack_removes_up_to_and_keeps_newer() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        for _ in 0..7 {
            store.post("a", &w, b"m").unwrap().unwrap();
        }
        store.ack("a", &r, 5).unwrap().unwrap();
        let rest = store.fetch("a", &r, 0, 10, true).unwrap().unwrap();
        assert_eq!(rest.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![6, 7]);
        assert_eq!(
            store.stats().unwrap(),
            Stats {
                boxes: 1,
                messages: 2,
                bytes: 2
            }
        );
        assert_eq!(store.ack("a", &w, 7).unwrap(), Err(Denied));
    }

    #[test]
    fn fetch_after_skips_older() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        for _ in 0..4 {
            store.post("a", &w, b"m").unwrap().unwrap();
        }
        let got = store.fetch("a", &r, 2, 10, true).unwrap().unwrap();
        assert_eq!(got.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![3, 4]);
        let one = store.fetch("a", &r, 0, 1, true).unwrap().unwrap();
        assert_eq!(one.len(), 1);
    }

    #[test]
    fn message_larger_than_8_kib_is_refused() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        assert_eq!(
            store.post("a", &w, &vec![0; MAX_MESSAGE_BYTES + 1]).unwrap(),
            Err(PostError::TooLarge)
        );
        assert_eq!(store.post("a", &w, &vec![0; MAX_MESSAGE_BYTES]).unwrap(), Ok(1));
    }

    #[test]
    fn box_holds_at_most_200_messages() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        for _ in 0..200 {
            store.post("a", &w, b"m").unwrap().unwrap();
        }
        assert_eq!(store.post("a", &w, b"m").unwrap(), Err(PostError::BoxFull));
        // Старые остаются, пока не подтверждены.
        let first = store.fetch("a", &r, 0, 1, true).unwrap().unwrap();
        assert_eq!(first[0].seq, 1);
        store.ack("a", &r, 1).unwrap().unwrap();
        assert_eq!(store.post("a", &w, b"m").unwrap(), Ok(201));
    }

    #[test]
    fn box_holds_at_most_1_mib() {
        let (store, _) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        let chunk = vec![1u8; MAX_MESSAGE_BYTES];
        for _ in 0..128 {
            store.post("a", &w, &chunk).unwrap().unwrap();
        }
        assert_eq!(store.post("a", &w, b"x").unwrap(), Err(PostError::BoxFull));
        assert_eq!(store.stats().unwrap().bytes, MAX_BOX_BYTES);
    }

    #[test]
    fn server_wide_ceiling_applies_across_boxes() {
        let limits = Limits {
            max_total_bytes: 10,
            max_boxes: 2,
            ..Limits::default()
        };
        let (store, _) = store_with(limits);
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        store.create_box("b", &w, &r).unwrap();
        assert_eq!(store.create_box("c", &w, &r).unwrap(), CreateBox::ServerFull);
        store.post("a", &w, b"123456").unwrap().unwrap();
        assert_eq!(store.post("b", &w, b"12345").unwrap(), Err(PostError::ServerFull));
        assert_eq!(store.post("b", &w, b"1234").unwrap(), Ok(1));
    }

    #[test]
    fn messages_expire_after_7_days() {
        let (store, clock) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        store.post("a", &w, b"old").unwrap().unwrap();
        clock.advance_secs(6 * DAY);
        store.post("a", &w, b"newer").unwrap().unwrap();
        assert_eq!(store.fetch("a", &r, 0, 10, true).unwrap().unwrap().len(), 2);
        clock.advance_secs(DAY + 1);
        // Первое старше 7 суток: не отдаётся даже до уборки.
        let seen = store.fetch("a", &r, 0, 10, true).unwrap().unwrap();
        assert_eq!(seen.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![2]);
        let swept = store.sweep().unwrap();
        assert_eq!(swept, SweepStats { messages: 1, boxes: 0 });
        assert_eq!(
            store.stats().unwrap(),
            Stats {
                boxes: 1,
                messages: 1,
                bytes: 5
            }
        );
    }

    #[test]
    fn expired_messages_free_the_box_quota() {
        let (store, clock) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        for _ in 0..200 {
            store.post("a", &w, b"m").unwrap().unwrap();
        }
        assert_eq!(store.post("a", &w, b"m").unwrap(), Err(PostError::BoxFull));
        clock.advance_secs(7 * DAY);
        // Ящик не трогали 7 суток, до 90 далеко: он жив, а старые сообщения не мешают.
        assert_eq!(store.post("a", &w, b"m").unwrap(), Ok(201));
        assert_eq!(store.stats().unwrap().messages, 1);
    }

    #[test]
    fn idle_box_is_deleted_after_90_days_and_use_keeps_it() {
        let (store, clock) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("idle", &w, &r).unwrap();
        store.create_box("used", &w, &r).unwrap();
        store.post("idle", &w, b"m").unwrap().unwrap();
        clock.advance_secs(60 * DAY);
        store.fetch("used", &r, 0, 1, true).unwrap().unwrap();
        clock.advance_secs(31 * DAY);
        assert_eq!(store.sweep().unwrap(), SweepStats { messages: 1, boxes: 1 });
        assert_eq!(store.fetch("idle", &r, 0, 1, true).unwrap(), Err(Denied));
        assert!(store.fetch("used", &r, 0, 1, true).unwrap().is_ok());
        assert_eq!(store.stats().unwrap().boxes, 1);
    }

    #[test]
    fn idle_box_is_gone_on_access_even_before_sweep() {
        let (store, clock) = store_with(Limits::default());
        let (w, r) = tokens();
        store.create_box("a", &w, &r).unwrap();
        clock.advance_secs(91 * DAY);
        assert_eq!(store.post("a", &w, b"m").unwrap(), Err(PostError::Denied));
        // Теперь тот же номер можно занять заново, и с другими хэшами.
        assert_eq!(store.create_box("a", &r, &w).unwrap(), CreateBox::Created);
    }

    #[test]
    fn database_file_survives_reopen_and_copy() {
        let tag = crate::codec::b64_encode(&crate::codec::random_bytes::<6>());
        let dir = std::env::temp_dir().join(format!("ampulse-store-{tag}"));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("a.db");
        let clock = Arc::new(ManualClock::new(1_800_000_000));
        let (w, r) = tokens();
        {
            let store = Store::open(&path, clock.clone(), Limits::default()).unwrap();
            store.create_box("a", &w, &r).unwrap();
            store.post("a", &w, b"cipher").unwrap().unwrap();
        }
        let store = Store::open(&path, clock, Limits::default()).unwrap();
        assert_eq!(store.fetch("a", &r, 0, 10, true).unwrap().unwrap()[0].body, b"cipher");
        let copy = dir.join("copy.db");
        copy_database(&path, &copy).unwrap();
        assert_eq!(
            read_stats(&copy).unwrap(),
            Stats {
                boxes: 1,
                messages: 1,
                bytes: 6
            }
        );
        drop(store);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
