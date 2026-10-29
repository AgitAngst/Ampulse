//! Журнал на английском. Уровень — переменная `AMPULSE_LOG` (`error`, `warn`, `info`, `debug`, `trace`).
//! Под systemd время в строку не пишем: его ставит journald.

use tracing::level_filters::LevelFilter;

pub fn init() {
    let level = std::env::var("AMPULSE_LOG")
        .ok()
        .and_then(|v| v.trim().parse::<LevelFilter>().ok())
        .unwrap_or(LevelFilter::INFO);
    let builder = tracing_subscriber::fmt().with_max_level(level).with_target(false);
    if std::env::var_os("JOURNAL_STREAM").is_some() {
        builder.without_time().init();
    } else {
        builder.init();
    }
}
