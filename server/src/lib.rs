//! `ampulse-server`: необязательный ретранслятор зашифрованных «почтовых ящиков» для Ampulse.
//!
//! Сервер видит только шифртекст, случайные идентификаторы, размеры и время. Протокол — в [`api`],
//! хранение и сроки — в [`store`], установка как службы — в [`install`].

pub mod api;
pub mod backup;
pub mod cli;
pub mod clock;
pub mod codec;
pub mod config;
pub mod fsutil;
pub mod install;
pub mod logging;
pub mod ratelimit;
pub mod status;
pub mod store;
pub mod tls;
