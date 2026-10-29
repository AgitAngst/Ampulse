//! Разбор командной строки вручную (без clap): команд мало, а лишние зависимости сервер не нужны.

use std::net::SocketAddr;
use std::path::PathBuf;

pub const USAGE: &str = "\
ampulse-server - optional encrypted mailbox relay for Ampulse

Usage:
  ampulse-server serve [--data DIR] [--addr IP:PORT] [--max-boxes N] [--max-total-mib N]
  ampulse-server install [--port N] [--address HOST] [--no-firewall] [--no-start]
  ampulse-server status [--json] [--data DIR]
  ampulse-server backup [--to DIR] [--data DIR]
  ampulse-server uninstall [--purge] [--yes]
  ampulse-server --version | --help
";

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ServeArgs {
    pub data: Option<PathBuf>,
    pub addr: Option<SocketAddr>,
    pub max_boxes: Option<u64>,
    pub max_total_mib: Option<u64>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct InstallArgs {
    pub port: Option<u16>,
    pub address: Option<String>,
    pub no_firewall: bool,
    pub no_start: bool,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct StatusArgs {
    pub json: bool,
    pub data: Option<PathBuf>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct BackupArgs {
    pub to: Option<PathBuf>,
    pub data: Option<PathBuf>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct UninstallArgs {
    pub purge: bool,
    pub yes: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Serve(ServeArgs),
    Install(InstallArgs),
    Status(StatusArgs),
    Backup(BackupArgs),
    Uninstall(UninstallArgs),
    Help,
    Version,
}

/// Перебор `--флаг`, `--флаг значение` и `--флаг=значение`.
struct Flags {
    items: std::vec::IntoIter<String>,
    pending: Option<String>,
}

impl Flags {
    fn new(args: Vec<String>) -> Self {
        Self {
            items: args.into_iter(),
            pending: None,
        }
    }

    /// Следующий флаг (без значения, если оно ещё не прочитано).
    fn next_flag(&mut self) -> Result<Option<String>, String> {
        if let Some(extra) = self.pending.take() {
            return Err(format!("flag needs no value: {extra}"));
        }
        let Some(arg) = self.items.next() else { return Ok(None) };
        if !arg.starts_with("--") {
            return Err(format!("unexpected argument: {arg}"));
        }
        match arg.split_once('=') {
            Some((flag, value)) => {
                self.pending = Some(value.to_owned());
                Ok(Some(flag.to_owned()))
            }
            None => Ok(Some(arg)),
        }
    }

    /// Значение флага, который его требует.
    fn value(&mut self, flag: &str) -> Result<String, String> {
        self.pending
            .take()
            .or_else(|| self.items.next())
            .ok_or_else(|| format!("{flag} needs a value"))
    }

    fn parsed<T: std::str::FromStr>(&mut self, flag: &str) -> Result<T, String> {
        let text = self.value(flag)?;
        text.parse().map_err(|_| format!("bad value for {flag}: {text}"))
    }
}

/// Разобрать аргументы (без имени программы).
pub fn parse(args: Vec<String>) -> Result<Command, String> {
    let mut args = args.into_iter();
    let Some(name) = args.next() else {
        return Ok(Command::Help);
    };
    let rest: Vec<String> = args.collect();
    match name.as_str() {
        "--help" | "-h" | "help" => Ok(Command::Help),
        "--version" | "-V" | "version" => Ok(Command::Version),
        "serve" => {
            let mut out = ServeArgs::default();
            let mut flags = Flags::new(rest);
            while let Some(flag) = flags.next_flag()? {
                match flag.as_str() {
                    "--data" => out.data = Some(PathBuf::from(flags.value(&flag)?)),
                    "--addr" => out.addr = Some(flags.parsed(&flag)?),
                    "--max-boxes" => out.max_boxes = Some(flags.parsed(&flag)?),
                    "--max-total-mib" => out.max_total_mib = Some(flags.parsed(&flag)?),
                    other => return Err(format!("unknown flag for serve: {other}")),
                }
            }
            Ok(Command::Serve(out))
        }
        "install" => {
            let mut out = InstallArgs::default();
            let mut flags = Flags::new(rest);
            while let Some(flag) = flags.next_flag()? {
                match flag.as_str() {
                    "--port" => out.port = Some(flags.parsed(&flag)?),
                    "--address" => out.address = Some(flags.value(&flag)?),
                    "--no-firewall" => out.no_firewall = true,
                    "--no-start" => out.no_start = true,
                    other => return Err(format!("unknown flag for install: {other}")),
                }
            }
            if out.port == Some(0) {
                return Err("--port must not be 0".to_owned());
            }
            Ok(Command::Install(out))
        }
        "status" => {
            let mut out = StatusArgs::default();
            let mut flags = Flags::new(rest);
            while let Some(flag) = flags.next_flag()? {
                match flag.as_str() {
                    "--json" => out.json = true,
                    "--data" => out.data = Some(PathBuf::from(flags.value(&flag)?)),
                    other => return Err(format!("unknown flag for status: {other}")),
                }
            }
            Ok(Command::Status(out))
        }
        "backup" => {
            let mut out = BackupArgs::default();
            let mut flags = Flags::new(rest);
            while let Some(flag) = flags.next_flag()? {
                match flag.as_str() {
                    "--to" => out.to = Some(PathBuf::from(flags.value(&flag)?)),
                    "--data" => out.data = Some(PathBuf::from(flags.value(&flag)?)),
                    other => return Err(format!("unknown flag for backup: {other}")),
                }
            }
            Ok(Command::Backup(out))
        }
        "uninstall" => {
            let mut out = UninstallArgs::default();
            let mut flags = Flags::new(rest);
            while let Some(flag) = flags.next_flag()? {
                match flag.as_str() {
                    "--purge" => out.purge = true,
                    "--yes" => out.yes = true,
                    other => return Err(format!("unknown flag for uninstall: {other}")),
                }
            }
            Ok(Command::Uninstall(out))
        }
        other => Err(format!("unknown command: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(args: &[&str]) -> Result<Command, String> {
        parse(args.iter().map(|s| s.to_string()).collect())
    }

    #[test]
    fn no_arguments_show_help() {
        assert_eq!(p(&[]).unwrap(), Command::Help);
        assert_eq!(p(&["--help"]).unwrap(), Command::Help);
        assert_eq!(p(&["--version"]).unwrap(), Command::Version);
    }

    #[test]
    fn serve_flags_in_both_spellings() {
        let Command::Serve(a) = p(&["serve", "--data", "/x", "--addr=127.0.0.1:9", "--max-boxes", "5"]).unwrap() else {
            panic!("not serve")
        };
        assert_eq!(a.data, Some(PathBuf::from("/x")));
        assert_eq!(a.addr, Some("127.0.0.1:9".parse().unwrap()));
        assert_eq!(a.max_boxes, Some(5));
        assert_eq!(a.max_total_mib, None);
    }

    #[test]
    fn install_flags() {
        let Command::Install(a) = p(&["install", "--port", "9000", "--address=203.0.113.7", "--no-firewall"]).unwrap()
        else {
            panic!("not install")
        };
        assert_eq!(a.port, Some(9000));
        assert_eq!(a.address.as_deref(), Some("203.0.113.7"));
        assert!(a.no_firewall && !a.no_start);
        assert_eq!(p(&["install"]).unwrap(), Command::Install(InstallArgs::default()));
    }

    #[test]
    fn status_backup_uninstall() {
        assert_eq!(
            p(&["status", "--json"]).unwrap(),
            Command::Status(StatusArgs { json: true, data: None })
        );
        assert_eq!(
            p(&["backup", "--to", "/b"]).unwrap(),
            Command::Backup(BackupArgs {
                to: Some(PathBuf::from("/b")),
                data: None
            })
        );
        assert_eq!(
            p(&["uninstall", "--purge", "--yes"]).unwrap(),
            Command::Uninstall(UninstallArgs { purge: true, yes: true })
        );
    }

    #[test]
    fn bad_input_is_an_error() {
        assert!(p(&["frobnicate"]).is_err());
        assert!(p(&["install", "--port"]).is_err());
        assert!(p(&["install", "--port", "abc"]).is_err());
        assert!(p(&["install", "--port", "0"]).is_err());
        assert!(p(&["install", "--bogus"]).is_err());
        assert!(p(&["install", "stray"]).is_err());
        assert!(p(&["status", "--json=1"]).is_err());
        assert!(p(&["serve", "--addr", "not-an-address"]).is_err());
    }
}
