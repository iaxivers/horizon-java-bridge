//! Without the `java-mods` feature: no JVM, no Java mods. What Horizon's files call to start
//! them, read their content and name their levels answers "none".
use super::super::{Dim, Game};
use std::path::{Path, PathBuf};

impl Game<'_> {
    pub(in crate::game) fn start_java_mods(&mut self) {}
}

/// The process's start (entry.rs): no owner record to read.
pub(crate) fn owner_record_early() {}
/// The process's end (entry.rs): it ends as any other.
pub(crate) fn exit_through_java() {}
pub(in crate::game) fn start_early() {}
/// The Java mods' content mods: none.
pub(in crate::game) fn java_content(_base: &Path) -> Vec<PathBuf> {
    Vec::new()
}
pub(in crate::game) fn java_creative(_out: &Path) {}
/// The level a saved dimension id names: Horizon's own.
pub(in crate::game) fn level_named(id: &str) -> Dim {
    Dim::from_id(id)
}
/// The Java mods' client at the title: there is none.
pub(in crate::game) fn client_start_at_title() {}
/// Whether Java mods run: never.
pub(in crate::game) fn has_java_mods() -> bool {
    false
}
/// Whether the Java mods still load: they don't.
pub(in crate::game) fn java_loading() -> bool {
    false
}
