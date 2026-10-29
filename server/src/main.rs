use std::process::ExitCode;
use std::sync::Arc;

use ampulse_server::api::{self, ApiOptions};
use ampulse_server::cli::{self, Command, ServeArgs};
use ampulse_server::clock::{Clock, SystemClock};
use ampulse_server::config::{self, ServeConfig, Settings};
use ampulse_server::ratelimit::RateConfig;
use ampulse_server::store::Store;
use ampulse_server::tls::Identity;
use ampulse_server::{backup, fsutil, install, logging, status};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = match cli::parse(args) {
        Ok(command) => command,
        Err(e) => {
            eprintln!("error: {e}\n\n{}", cli::USAGE);
            return ExitCode::from(2);
        }
    };
    let result = match command {
        Command::Help => {
            print!("{}", cli::USAGE);
            Ok(())
        }
        Command::Version => {
            println!("ampulse-server {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        Command::Serve(args) => serve(&args),
        Command::Install(args) => install::run(&args),
        Command::Uninstall(args) => install::uninstall(&args),
        Command::Status(args) => {
            let data = config::data_dir(&args.data);
            let state = status::gather(&data, install::is_running(), install::disk_free(&data));
            if args.json {
                println!("{}", state.to_json());
            } else {
                print!("{}", state.to_text());
            }
            Ok(())
        }
        Command::Backup(args) => {
            let data = config::data_dir(&args.data);
            let to = args
                .to
                .unwrap_or_else(|| backup::default_target(SystemClock.now_secs()));
            backup::run(&data, &to).map(|files| {
                println!("Backup written to {}", to.display());
                for file in files {
                    println!("  {}", file.display());
                }
            })
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn serve(args: &ServeArgs) -> Result<(), String> {
    logging::init();
    let cfg = ServeConfig::from_args(args);
    fsutil::secure_dir(&cfg.data_dir).map_err(|e| format!("cannot create {}: {e}", cfg.data_dir.display()))?;
    let clock: Arc<dyn Clock> = Arc::new(SystemClock);
    let store = Arc::new(
        Store::open(&cfg.db_path(), clock.clone(), cfg.limits.clone())
            .map_err(|e| format!("cannot open the database: {e}"))?,
    );
    let host = Settings::load(&cfg.data_dir).map(|s| s.host().to_owned());
    let identity = Identity::load_or_create(&cfg.data_dir, host.as_deref(), clock.now_secs())?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("cannot start the runtime: {e}"))?;
    runtime.block_on(async {
        let server = api::start(
            cfg.addr,
            store,
            &identity,
            clock,
            RateConfig::default(),
            ApiOptions::default(),
        )
        .await?;
        tracing::info!(
            "ampulse-server {} listening on {}",
            env!("CARGO_PKG_VERSION"),
            server.addr
        );
        tracing::info!("certificate fingerprint (SHA-256): {}", server.fingerprint);
        wait_for_stop().await;
        tracing::info!("stopping");
        server.shutdown().await;
        Ok(())
    })
}

#[cfg(unix)]
async fn wait_for_stop() {
    use tokio::signal::unix::{signal, SignalKind};
    let Ok(mut term) = signal(SignalKind::terminate()) else {
        let _ = tokio::signal::ctrl_c().await;
        return;
    };
    tokio::select! {
        _ = term.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
}

#[cfg(not(unix))]
async fn wait_for_stop() {
    let _ = tokio::signal::ctrl_c().await;
}
