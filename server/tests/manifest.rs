//! Сверка `module.toml` с контрактом центра и с самой программой. Разбор построчный: манифест
//! плоский, а отдельный разборщик TOML ради него в зависимости тащить не нужно.

use ampulse_server::cli::{self, Command};
use ampulse_server::config::DEFAULT_PORT;
use ampulse_server::install::{EXE, SERVICE};

const MANIFEST: &str = include_str!("../module.toml");

/// Значение `key = "..."` или `key = 123` в разделе (`""` — верхний уровень), без комментария.
fn value(section: &str, key: &str) -> Option<String> {
    let mut current = String::new();
    for line in MANIFEST.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            let head = line.split('#').next().unwrap_or(line);
            current = head.trim().trim_matches(|c| c == '[' || c == ']').trim().to_owned();
            continue;
        }
        if current != section {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            if k.trim() == key {
                // Комментарий после значения: ищем `#` вне кавычек.
                let v = v.trim();
                let end = if let Some(rest) = v.strip_prefix('"') {
                    rest.find('"').map(|i| i + 2)
                } else {
                    v.find('#')
                };
                let v = end.map_or(v, |e| &v[..e]).trim();
                return Some(v.trim_matches('"').to_owned());
            }
        }
    }
    None
}

#[test]
fn identity_matches_the_program() {
    assert_eq!(value("", "id").as_deref(), Some("ampulse"));
    assert_eq!(value("", "service").as_deref(), Some(SERVICE));
    assert_eq!(value("", "binary").as_deref(), Some(EXE));
    assert_eq!(value("", "instances").as_deref(), Some("false"));
    assert_eq!(value("", "hidden").as_deref(), Some("false"));
}

#[test]
fn port_matches_the_default() {
    assert!(MANIFEST.contains(&format!("tcp = [{DEFAULT_PORT}]")));
    assert_eq!(value("form", "default").as_deref(), Some("8740"));
}

#[test]
fn commands_are_the_contract_commands() {
    assert_eq!(value("commands", "install").as_deref(), Some("{bin} install {args}"));
    assert_eq!(value("commands", "status").as_deref(), Some("{bin} status --json"));
    assert_eq!(value("commands", "backup").as_deref(), Some("{bin} backup --to {dir}"));
    assert_eq!(value("commands", "uninstall").as_deref(), Some("{bin} uninstall"));

    // Каждую команду программа разбирает без ошибок.
    for (line, expected) in [
        ("status --json", "status"),
        ("backup --to /tmp/x", "backup"),
        ("uninstall", "uninstall"),
    ] {
        let args: Vec<String> = line.split_whitespace().map(str::to_owned).collect();
        let command = cli::parse(args).unwrap_or_else(|e| panic!("{line}: {e}"));
        let name = match command {
            Command::Status(_) => "status",
            Command::Backup(_) => "backup",
            Command::Uninstall(_) => "uninstall",
            other => panic!("unexpected {other:?}"),
        };
        assert_eq!(name, expected);
    }
}

#[test]
fn form_arguments_are_flags_install_accepts() {
    let args: Vec<&str> = MANIFEST
        .lines()
        .filter_map(|l| l.trim().strip_prefix("arg = "))
        .collect();
    assert_eq!(args.len(), 2, "port and address");
    for arg in args {
        let template = arg.trim_matches('"');
        let line = template.replace(
            "{value}",
            if template.contains("port") {
                "9000"
            } else {
                "203.0.113.7"
            },
        );
        let mut parts = vec!["install".to_owned()];
        parts.extend(line.split_whitespace().map(str::to_owned));
        assert!(
            matches!(cli::parse(parts), Ok(Command::Install(_))),
            "install must accept: {line}"
        );
    }
}

#[test]
fn source_and_actions_are_declared() {
    assert_eq!(value("source", "checksums").as_deref(), Some("SHA256SUMS"));
    assert!(value("source", "repo").is_some());
    assert!(MANIFEST.contains("x86_64 = ") && MANIFEST.contains("arm64 = "));
    // Перезапуск требует подтверждения: он обрывает длинные опросы.
    assert_eq!(MANIFEST.matches("[[action]]").count(), 2);
    assert!(MANIFEST.contains("systemctl restart {service}"));
}
