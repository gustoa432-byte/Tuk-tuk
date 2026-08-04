//! Oracle — predictive routing hints from device social orbits.
//!
//! Modules:
//! - [`domain`] — pure scoring / decay (unit-tested, no I/O)
//! - [`store`] — libSQL schema + upsert/query helpers

pub mod domain;
pub mod store;
