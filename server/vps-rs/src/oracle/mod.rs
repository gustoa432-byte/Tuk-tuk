//! Oracle — predictive routing hints from device social orbits.
//!
//! Modules:
//! - [`domain`] — pure scoring / decay (unit-tested, no I/O)
//! - [`store`] — libSQL schema + upsert/query helpers
//! - [`auth`] — JWT → `node_id` principal
//! - [`api`] — Axum handlers (`/v1/oracle/*`)

pub mod api;
pub mod auth;
pub mod domain;
pub mod store;
