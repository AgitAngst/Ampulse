//! `install` и `uninstall`: поставить сервер на Linux с systemd одной командой и убрать.
//!
//! `install` идемпотентна: повтор заменяет бинарник и перезапускает службу, а база, сертификат
//! и настройки остаются теми же. Системные шаги (пользователь, файлы в `/opt`, systemd, `ufw`)
//! есть только под Linux; всё чистое — рендер файла службы, выбор адреса и порта, разбор вывода
//! `ufw` и `df` — работает везде и покрыто тестами.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};

use crate::cli::InstallArgs;
use crate::config::{Settings, DEFAULT_PORT};

/// Системный пользователь службы.
pub const USER: &str = "ampulse";
/// Служба systemd.
pub const SERVICE: &str = "ampulse";
/// Каталог с бинарником.
pub const OPT_DIR: &str = "/opt/ampulse";
/// Бинарник службы.
pub const EXE: &str = "/opt/ampulse/ampulse-server";
/// Файл службы systemd.
pub const UNIT_PATH: &str = "/etc/systemd/system/ampulse.service";
/// Каталог данных (на Linux совпадает с умолчанием `serve`).
pub const DATA_DIR: &str = "/var/lib/ampulse";

/// Файл службы systemd.
pub fn unit_file(port: u16) -> String {
    // Порт ниже 1024 без прав root открыть нельзя: разрешаем это одной способностью, не больше.
    let caps = if port < 1024 {
        "AmbientCapabilities=CAP_NET_BIND_SERVICE\n"
    } else {
        ""
    };
    format!(
        "[Unit]
Description=Ampulse relay (encrypted mailboxes)
After=network-online.target
Wants=network-online.target

[Service]
User={USER}
ExecStart={EXE} serve --data {DATA_DIR} --addr 0.0.0.0:{port}
WorkingDirectory={DATA_DIR}
Restart=always
RestartSec=2
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths={DATA_DIR}
{caps}
[Install]
WantedBy=multi-user.target
"
    )
}

/// Порт из строки `ExecStart` файла службы.
pub fn port_of_unit(unit: &str) -> Option<u16> {
    let rest = &unit[unit.find("--addr ")? + "--addr ".len()..];
    rest.split_whitespace().next()?.rsplit_once(':')?.1.parse().ok()
}

/// `203.0.113.7` + `8740` → `203.0.113.7:8740`; IPv6 — в квадратных скобках; адрес с портом остаётся как есть.
pub fn authority(host: &str, port: u16) -> String {
    let host = host.trim();
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    if bare.parse::<Ipv6Addr>().is_ok() {
        return format!("[{bare}]:{port}");
    }
    let has_port = match host.strip_prefix('[') {
        Some(v6) => v6.contains("]:"),
        None => host.matches(':').count() == 1,
    };
    if has_port {
        host.to_owned()
    } else {
        format!("{host}:{port}")
    }
}

/// Адрес виден из интернета, а не только из локальной сети?
pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let [a, b, ..] = v4.octets();
            !(v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_unspecified()
                // 100.64.0.0/10 - общий NAT провайдера.
                || (a == 100 && (64..128).contains(&b)))
        }
        IpAddr::V6(v6) => {
            let first = v6.segments()[0];
            !(v6.is_loopback() || v6.is_unspecified() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80)
        }
    }
}

/// С какого адреса машина ходит в интернет. Пакеты не уходят: сокет только «подключается».
fn outgoing_address() -> Option<IpAddr> {
    let probes: [(IpAddr, &str); 2] = [
        (IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), "0.0.0.0:0"),
        (IpAddr::V6(Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)), "[::]:0"),
    ];
    probes.into_iter().find_map(|(target, bind)| {
        let socket = UdpSocket::bind(bind).ok()?;
        socket.connect(SocketAddr::new(target, 9)).ok()?;
        Some(socket.local_addr().ok()?.ip())
    })
}

/// Какие настройки применить. Явные флаги главнее запомненного, запомненное главнее угадывания.
/// `detect` вызывается, только если адрес взять больше неоткуда.
pub fn resolve_settings(
    kept: Option<&Settings>,
    args: &InstallArgs,
    detect: impl FnOnce() -> Result<String, String>,
) -> Result<Settings, String> {
    let port = args.port.or(kept.map(|k| k.port)).unwrap_or(DEFAULT_PORT);
    let address = if let Some(host) = &args.address {
        authority(host, port)
    } else if let Some(kept) = kept {
        // Тот же хост, порт — выбранный сейчас.
        authority(kept.host(), port)
    } else {
        authority(&detect()?, port)
    };
    Ok(Settings { port, address })
}

/// Определить внешний адрес машины или объяснить, что указать самому.
pub fn detect_host() -> Result<String, String> {
    match outgoing_address() {
        Some(ip) if is_public(ip) => Ok(ip.to_string()),
        Some(ip) => Err(format!(
            "this machine has the internal address {ip}; clients reach it differently. Pass the public address: install --address 203.0.113.7"
        )),
        None => Err("cannot detect the server address. Pass it: install --address 203.0.113.7".to_owned()),
    }
}

/// `ufw status` говорит, что брандмауэр включён?
pub fn ufw_is_active(status_output: &str) -> bool {
    status_output.lines().any(|l| l.trim() == "Status: active")
}

/// Свободное место из вывода `df -Pk`: четвёртая колонка последней строки, в КиБ → байты.
pub fn parse_df_free(output: &str) -> Option<u64> {
    let line = output.lines().rfind(|l| !l.trim().is_empty())?;
    let kib: u64 = line.split_whitespace().nth(3)?.parse().ok()?;
    kib.checked_mul(1024)
}

/// Что центр вызывает как `status --json`: служба запущена?
#[cfg(target_os = "linux")]
pub fn is_running() -> bool {
    exec_output("systemctl", &["is-active", "--quiet", SERVICE]).is_ok()
}

#[cfg(not(target_os = "linux"))]
pub fn is_running() -> bool {
    false
}

/// Свободно на диске с каталогом данных.
#[cfg(target_os = "linux")]
pub fn disk_free(path: &std::path::Path) -> Option<u64> {
    parse_df_free(&exec_output("df", &["-Pk", &path.to_string_lossy()]).ok()?)
}

#[cfg(not(target_os = "linux"))]
pub fn disk_free(_path: &std::path::Path) -> Option<u64> {
    None
}

#[cfg(not(target_os = "linux"))]
pub fn run(args: &InstallArgs) -> Result<(), String> {
    // Адрес и порт всё равно разбираем: опечатку полезно увидеть и здесь.
    resolve_settings(None, args, detect_host)?;
    Err("install sets the relay up as a systemd service and works on Linux only".to_owned())
}

#[cfg(not(target_os = "linux"))]
pub fn uninstall(_args: &crate::cli::UninstallArgs) -> Result<(), String> {
    Err("uninstall removes the systemd service and works on Linux only".to_owned())
}

#[cfg(target_os = "linux")]
pub use linux::{run, uninstall};

#[cfg(target_os = "linux")]
use linux::exec_output;

#[cfg(target_os = "linux")]
mod linux {
    use std::net::TcpStream;
    use std::path::Path;
    use std::process::Command;
    use std::time::Duration;

    use super::*;
    use crate::cli::UninstallArgs;
    use crate::clock::{Clock, SystemClock};
    use crate::fsutil;
    use crate::store::{Limits, Store};
    use crate::tls::Identity;

    pub(super) fn exec_output(program: &str, args: &[&str]) -> Result<String, String> {
        let output = Command::new(program)
            .args(args)
            .output()
            .map_err(|e| format!("cannot run {program}: {e}"))?;
        if !output.status.success() {
            return Err(format!(
                "{program} {}: {}",
                args.join(" "),
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    fn exec(program: &str, args: &[&str]) -> Result<(), String> {
        exec_output(program, args).map(|_| ())
    }

    fn require_root(what: &str) -> Result<(), String> {
        if exec_output("id", &["-u"])?.trim() != "0" {
            return Err(format!("{what} changes the system: run it with sudo"));
        }
        if exec_output("systemctl", &["--version"]).is_err() {
            return Err("systemd not found: the relay is installed as a systemd service only".to_owned());
        }
        Ok(())
    }

    fn set_mode(path: &str, mode: u32) -> Result<(), String> {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
            .map_err(|e| format!("cannot set permissions on {path}: {e}"))
    }

    /// Положить себя в `/opt/ampulse`. Работающий файл заменяем переименованием: запись поверх
    /// дала бы «text file busy». Тот же файл уже на месте — не трогаем.
    fn install_binary() -> Result<bool, String> {
        let me = std::env::current_exe().map_err(|e| format!("cannot find own binary: {e}"))?;
        let target = Path::new(EXE);
        if std::fs::canonicalize(&me).ok() == std::fs::canonicalize(target).ok() {
            return Ok(false);
        }
        if std::fs::read(target).is_ok_and(|theirs| std::fs::read(&me).is_ok_and(|mine| mine == theirs)) {
            return Ok(false);
        }
        let staging = Path::new(OPT_DIR).join("ampulse-server.new");
        std::fs::copy(&me, &staging).map_err(|e| format!("cannot copy the binary to {}: {e}", staging.display()))?;
        set_mode(&staging.to_string_lossy(), 0o755)?;
        std::fs::rename(&staging, target).map_err(|e| format!("cannot replace {EXE}: {e}"))?;
        Ok(true)
    }

    /// Порт свободен или занят нашей же службой.
    fn check_port(port: u16, unit: Option<&str>) -> Result<(), String> {
        if unit.and_then(port_of_unit) == Some(port) {
            return Ok(());
        }
        std::net::TcpListener::bind(("0.0.0.0", port))
            .map(|_| ())
            .map_err(|_| format!("port {port} is already taken by another program"))
    }

    fn ufw_active() -> bool {
        exec_output("ufw", &["status"]).is_ok_and(|out| ufw_is_active(&out))
    }

    fn wait_for_service(port: u16) -> Result<(), String> {
        let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        for _ in 0..40 {
            if TcpStream::connect_timeout(&addr, Duration::from_millis(250)).is_ok() {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(250));
        }
        let log = exec_output("journalctl", &["-u", SERVICE, "-n", "30", "--no-pager"]).unwrap_or_default();
        Err(format!(
            "the service did not start listening on port {port}. Last log lines:\n{log}"
        ))
    }

    pub fn run(args: &InstallArgs) -> Result<(), String> {
        require_root("install")?;
        let data = Path::new(DATA_DIR);
        let kept = Settings::load(data);
        let settings = resolve_settings(kept.as_ref(), args, detect_host)?;
        let unit_before = std::fs::read_to_string(UNIT_PATH).ok();
        check_port(settings.port, unit_before.as_deref())?;

        println!("Installing the Ampulse relay...");
        if exec_output("id", &["-u", USER]).is_err() {
            exec(
                "useradd",
                &["--system", "--home-dir", DATA_DIR, "--shell", "/usr/sbin/nologin", USER],
            )?;
            println!("  user {USER} created");
        }
        std::fs::create_dir_all(OPT_DIR).map_err(|e| format!("cannot create {OPT_DIR}: {e}"))?;
        fsutil::secure_dir(data).map_err(|e| format!("cannot create {DATA_DIR}: {e}"))?;
        if install_binary()? {
            println!("  binary: {EXE}");
        }

        // Служба могла работать: SQLite спокойно пускает вторую запись. Ссылки в каталоге
        // данных не читаем: root пошёл бы по ним туда, куда служба писать не вправе.
        let db_path = data.join(crate::config::DB_FILE);
        for name in [
            crate::config::DB_FILE,
            "ampulse.db-journal",
            crate::tls::CERT_FILE,
            crate::tls::KEY_FILE,
            crate::config::SETTINGS_FILE,
        ] {
            if fsutil::is_symlink(&data.join(name)) {
                return Err(format!(
                    "{} is a symbolic link; the installation is stopped",
                    data.join(name).display()
                ));
            }
        }
        let clock = SystemClock;
        let identity = Identity::load_or_create(data, Some(settings.host()), clock.now_secs())?;
        let _ = Store::open(&db_path, std::sync::Arc::new(SystemClock), Limits::default())
            .map_err(|e| format!("cannot open the database: {e}"))?;
        settings
            .save(data)
            .map_err(|e| format!("cannot save the settings: {e}"))?;
        exec("chown", &["-R", &format!("{USER}:{USER}"), DATA_DIR])?;
        fsutil::secure_dir(data).map_err(|e| format!("cannot close {DATA_DIR}: {e}"))?;
        for name in [
            crate::config::DB_FILE,
            crate::tls::CERT_FILE,
            crate::tls::KEY_FILE,
            crate::config::SETTINGS_FILE,
        ] {
            fsutil::secure_file(&data.join(name));
        }

        std::fs::write(UNIT_PATH, unit_file(settings.port)).map_err(|e| format!("cannot write {UNIT_PATH}: {e}"))?;
        if !args.no_firewall && ufw_active() {
            let rule = format!("{}/tcp", settings.port);
            match exec("ufw", &["allow", &rule]) {
                Ok(()) => println!("  ufw: opened {rule}"),
                Err(e) => println!("  ufw: cannot open {rule}: {e}"),
            }
        }
        exec("systemctl", &["daemon-reload"])?;
        if !args.no_start {
            exec("systemctl", &["enable", "--quiet", SERVICE])?;
            exec("systemctl", &["restart", SERVICE])?;
            wait_for_service(settings.port)?;
        }

        println!();
        if args.no_start {
            println!(
                "The relay is installed at {}; the service was not started.",
                settings.address
            );
        } else {
            println!("The relay is running at {}", settings.address);
        }
        println!("Certificate fingerprint (SHA-256): {}", identity.fingerprint());
        println!();
        println!("Status:  {EXE} status --json");
        println!("Log:     journalctl -u {SERVICE} -f");
        println!(
            "If your hosting has its own firewall in the control panel, open {}/tcp there.",
            settings.port
        );
        Ok(())
    }

    fn confirm_purge() -> Result<bool, String> {
        use std::io::{BufRead, IsTerminal, Write};
        if !std::io::stdin().is_terminal() {
            return Err(
                "--purge deletes all mailboxes for good and asks for confirmation, but there is no terminal. Run it in a terminal (ssh -t) or add --yes"
                    .to_owned(),
            );
        }
        print!("Delete all mailboxes, the certificate and the user for good? Type yes: ");
        std::io::stdout().flush().map_err(|e| e.to_string())?;
        let mut answer = String::new();
        std::io::stdin()
            .lock()
            .read_line(&mut answer)
            .map_err(|e| e.to_string())?;
        Ok(answer.trim().eq_ignore_ascii_case("yes"))
    }

    /// Удаление того, чего и так нет, — не ошибка.
    fn remove(result: std::io::Result<()>, what: &str) -> Result<(), String> {
        match result {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(format!("cannot remove {what}: {e}")),
            _ => Ok(()),
        }
    }

    pub fn uninstall(args: &UninstallArgs) -> Result<(), String> {
        require_root("uninstall")?;
        if args.purge && !args.yes && !confirm_purge()? {
            return Err("nothing was removed".to_owned());
        }
        let unit = std::fs::read_to_string(UNIT_PATH).ok();
        // Службы может уже не быть - это не повод останавливаться.
        let _ = exec("systemctl", &["disable", "--now", "--quiet", SERVICE]);
        if let Some(port) = unit.as_deref().and_then(port_of_unit) {
            if ufw_active() {
                let rule = format!("{port}/tcp");
                if exec("ufw", &["delete", "allow", &rule]).is_ok() {
                    println!("  ufw: closed {rule}");
                }
            }
        }
        remove(std::fs::remove_file(UNIT_PATH), UNIT_PATH)?;
        let _ = exec("systemctl", &["daemon-reload"]);
        remove(std::fs::remove_dir_all(OPT_DIR), OPT_DIR)?;
        println!("The service {SERVICE} is stopped and removed, {OPT_DIR} is deleted.");
        if args.purge {
            remove(std::fs::remove_dir_all(DATA_DIR), DATA_DIR)?;
            if exec_output("id", &["-u", USER]).is_ok() {
                exec("userdel", &[USER])?;
            }
            println!("Mailboxes, certificate and user {USER} are deleted.");
        } else {
            println!();
            println!(
                "The data stays in {DATA_DIR}: installing again picks up the same mailboxes and the same certificate."
            );
            println!("Delete it too, if you do not need it: rm -rf {DATA_DIR} && userdel {USER}");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(port: Option<u16>, address: Option<&str>) -> InstallArgs {
        InstallArgs {
            port,
            address: address.map(str::to_owned),
            ..Default::default()
        }
    }

    fn no_detect() -> Result<String, String> {
        panic!("must not detect the address")
    }

    #[test]
    fn unit_file_runs_as_service_user_on_the_chosen_port() {
        let unit = unit_file(8740);
        assert!(unit.contains("User=ampulse\n"));
        assert!(
            unit.contains("ExecStart=/opt/ampulse/ampulse-server serve --data /var/lib/ampulse --addr 0.0.0.0:8740\n")
        );
        assert!(unit.contains("ReadWritePaths=/var/lib/ampulse\n"));
        assert!(unit.contains("NoNewPrivileges=true"));
        assert!(unit.contains("UMask=0077"));
        assert!(unit.contains("WantedBy=multi-user.target"));
        assert!(!unit.contains("AmbientCapabilities"));
    }

    #[test]
    fn privileged_port_gets_one_capability() {
        assert!(unit_file(443).contains("AmbientCapabilities=CAP_NET_BIND_SERVICE\n"));
        assert!(!unit_file(1024).contains("AmbientCapabilities"));
    }

    #[test]
    fn unit_rendering_is_deterministic_and_port_round_trips() {
        assert_eq!(unit_file(9000), unit_file(9000));
        assert_eq!(port_of_unit(&unit_file(9000)), Some(9000));
        assert_eq!(port_of_unit("nothing here"), None);
    }

    #[test]
    fn authority_shapes() {
        assert_eq!(authority("203.0.113.7", 8740), "203.0.113.7:8740");
        assert_eq!(authority("relay.example.org", 443), "relay.example.org:443");
        assert_eq!(authority("2001:db8::1", 8740), "[2001:db8::1]:8740");
        assert_eq!(authority("[2001:db8::1]", 8740), "[2001:db8::1]:8740");
        assert_eq!(authority("203.0.113.7:9999", 8740), "203.0.113.7:9999");
        assert_eq!(authority("[2001:db8::1]:9999", 8740), "[2001:db8::1]:9999");
    }

    #[test]
    fn public_and_private_addresses() {
        assert!(is_public("203.0.113.7".parse().unwrap()));
        assert!(!is_public("10.1.2.3".parse().unwrap()));
        assert!(!is_public("192.168.0.9".parse().unwrap()));
        assert!(!is_public("100.64.1.1".parse().unwrap()));
        assert!(!is_public("127.0.0.1".parse().unwrap()));
        assert!(is_public("2001:db8::1".parse().unwrap()));
        assert!(!is_public("fd00::1".parse().unwrap()));
        assert!(!is_public("fe80::1".parse().unwrap()));
    }

    #[test]
    fn first_install_uses_defaults_and_detection() {
        let s = resolve_settings(None, &args(None, None), || Ok("203.0.113.7".into())).unwrap();
        assert_eq!(
            s,
            Settings {
                port: 8740,
                address: "203.0.113.7:8740".into()
            }
        );
        assert!(resolve_settings(None, &args(None, None), || Err("no address".into())).is_err());
    }

    #[test]
    fn explicit_flags_beat_everything() {
        let kept = Settings {
            port: 8740,
            address: "198.51.100.1:8740".into(),
        };
        let s = resolve_settings(Some(&kept), &args(Some(9000), Some("relay.example.org")), no_detect).unwrap();
        assert_eq!(
            s,
            Settings {
                port: 9000,
                address: "relay.example.org:9000".into()
            }
        );
    }

    #[test]
    fn reinstall_without_flags_keeps_settings() {
        let kept = Settings {
            port: 9000,
            address: "relay.example.org:443".into(),
        };
        // Хост запомнен; порт, с которым ставили, — 9000, внешний адрес пересобирается из него.
        let again = resolve_settings(Some(&kept), &args(None, None), no_detect).unwrap();
        assert_eq!(again.port, 9000);
        assert_eq!(again.address, "relay.example.org:9000");
        let second = resolve_settings(Some(&again), &args(None, None), no_detect).unwrap();
        assert_eq!(second, again, "repeating install must not change anything");
    }

    #[test]
    fn new_port_moves_the_kept_host() {
        let kept = Settings {
            port: 8740,
            address: "203.0.113.7:8740".into(),
        };
        let s = resolve_settings(Some(&kept), &args(Some(9100), None), no_detect).unwrap();
        assert_eq!(s.address, "203.0.113.7:9100");
    }

    #[test]
    fn ufw_output_is_understood() {
        assert!(ufw_is_active("Status: active\n\nTo   Action  From\n"));
        assert!(!ufw_is_active("Status: inactive\n"));
        assert!(!ufw_is_active(""));
    }

    #[test]
    fn df_output_is_understood() {
        let out = "Filesystem     1024-blocks    Used Available Capacity Mounted on\n/dev/vda1         41152812 9876543  31276269      24% /\n";
        assert_eq!(parse_df_free(out), Some(31_276_269 * 1024));
        assert_eq!(parse_df_free(""), None);
        assert_eq!(parse_df_free("garbage\n"), None);
    }

    #[cfg(not(target_os = "linux"))]
    #[test]
    fn install_refuses_politely_off_linux() {
        let err = run(&args(None, Some("203.0.113.7"))).unwrap_err();
        assert!(err.contains("Linux"));
        assert!(uninstall(&Default::default()).unwrap_err().contains("Linux"));
        assert!(!is_running());
        assert_eq!(disk_free(std::path::Path::new(".")), None);
    }
}
