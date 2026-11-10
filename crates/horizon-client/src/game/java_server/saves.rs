//! The Java mods' block entities in their chunks (`block_entities`), as vanilla saves them: a
//! saved chunk carries the mods' (`HorizonBlockEntities.chunkData`), a loaded chunk's go to
//! the mods' side, which makes them (`chunkLoaded`) and has Horizon's world let go of them. The
//! chunks whose mods' block entities changed are saved again (`LevelChunk.markUnsaved`).
use super::*;

/// Mod block entities chunks brought while the mods' side wasn't up (or not for this level
/// yet), by dimension: handed over at the level's next tick once it is.
static LOADED: std::sync::Mutex<Vec<(&'static str, [i32; 3])>> = std::sync::Mutex::new(Vec::new());

impl Game<'_> {
    /// Chunks came into the level in place (`native_chunks_changed`): its mod block entities
    /// go to the mods' side (Horizon's world keeps them until they are taken), and the chunks
    /// the mods changed are marked for saving before the unloaded ones are saved.
    pub(in crate::game) fn java_chunks_loaded(&mut self, loaded: &[(i32, i32, bool)]) {
        if !super::super::java_mods::has_java_mods() {
            return;
        }
        if !loaded.is_empty() && self.world.block_entities.has_mods() {
            let dim = self.dim.id();
            let mut held = LOADED.lock().unwrap_or_else(|e| e.into_inner());
            for &(x, z, _) in loaded {
                for p in self.world.block_entities.mods_in_chunk((x, z)) {
                    held.push((dim, [p.0, p.1, p.2]));
                }
            }
        }
        self.java_mark_block_entity_chunks();
    }

    /// Hands the level in place's waiting mod block entities to the mods' side (at the start
    /// of its tick, frozen or not, once Fabric runs).
    pub(super) fn java_take_loaded_block_entities(&mut self) {
        let dim = self.dim.id();
        let positions: Vec<[i32; 3]> = {
            let mut held = LOADED.lock().unwrap_or_else(|e| e.into_inner());
            if held.is_empty() {
                return;
            }
            let mut mine = Vec::new();
            held.retain(|(d, p)| {
                if *d == dim {
                    mine.push(*p);
                    false
                } else {
                    true
                }
            });
            mine
        };
        // Still Horizon's mod block entities (not broken or replaced since).
        let positions: Vec<[i32; 3]> = positions
            .into_iter()
            .filter(|p| {
                matches!(
                    self.world.block_entities.get(&(p[0], p[1], p[2])),
                    Some(horizon_client::containers::BlockEntity::Mod(_))
                )
            })
            .collect();
        if positions.is_empty() {
            return;
        }
        let ran = self
            .with_java_world(|| horizon_jvm::events::chunk_block_entities_loaded(dim, &positions));
        if let Err(e) = ran {
            log::warn!("Java mods: block entities of loaded chunks: {e}");
        }
        // What they took leaves Horizon's world with the actions (Action::SetBlockEntity "");
        // a save before that writes the mods' data over Horizon's copy.
    }

    /// The chunks whose mods' block entities changed since last asked are marked modified in
    /// their levels, so they are saved again.
    pub(in crate::game) fn java_mark_block_entity_chunks(&mut self) {
        if !java_mods_on() || !horizon_jvm::events::ready() {
            return;
        }
        let changed = match horizon_jvm::events::changed_block_entity_chunks() {
            Ok(changed) => changed,
            Err(e) => {
                log::warn!("Java mods: changed block entities: {e}");
                return;
            }
        };
        for (dimension, x, z) in changed {
            let world = if dimension == self.dim.id() {
                Some(&mut self.world)
            } else {
                super::super::Dim::known(&dimension)
                    .and_then(|dim| self.levels.parked_world_mut(dim))
            };
            if let Some(world) = world
                && world.chunks.contains_key(&(x, z))
            {
                world.modified.insert((x, z));
            }
        }
    }

    /// A save batch of the level in place: the mods' block entities go into their chunks'
    /// `block_entities` (their data wins over a copy Horizon's world still holds).
    pub(in crate::game) fn java_save_block_entities(
        &self,
        chunks: &mut [horizon_client::chunk::Chunk],
    ) {
        if chunks.is_empty() || !java_mods_on() || !horizon_jvm::events::ready() {
            return;
        }
        let keys: Vec<(i32, i32)> = chunks.iter().map(|c| (c.x, c.z)).collect();
        let dim = self.dim.id();
        let tags =
            match self.with_java_world(|| horizon_jvm::events::chunk_block_entities(dim, &keys)) {
                Ok(tags) => tags,
                Err(e) => {
                    log::warn!("Java mods: saving block entities: {e}");
                    return;
                }
            };
        for snbt in tags {
            let tag = match horizon_world::nbt::parse_snbt(&snbt) {
                Ok((tag, _)) => tag,
                Err(e) => {
                    log::warn!("Java mods: a block entity to save: {e}");
                    continue;
                }
            };
            let int = |k: &str| horizon_client::save::number(tag.get(k)).map(|v| v as i32);
            let (Some(x), Some(y), Some(z)) = (int("x"), int("y"), int("z")) else {
                continue;
            };
            let Some(chunk) = chunks.iter_mut().find(|c| (c.x, c.z) == (x >> 4, z >> 4)) else {
                continue;
            };
            chunk.block_entities.retain(|(p, _)| *p != (x, y, z));
            let extras = std::sync::Arc::make_mut(chunk.saved.get_or_insert_with(Default::default));
            extras.block_entities.retain(|t| {
                let at = |k: &str| horizon_client::save::number(t.get(k)).map(|v| v as i32);
                (at("x"), at("y"), at("z")) != (Some(x), Some(y), Some(z))
            });
            extras.block_entities.push(tag);
        }
    }
}

impl Game<'_> {
    /// Whether a server waits for the Java mods' start before its levels tick.
    pub(in crate::game) fn java_holds_server() -> bool {
        super::super::java_mods::java_starting()
    }

    /// A dedicated server says it is up ("Done") once its Java mods are, as a Fabric server
    /// loads its mods before (with the mods' content cached, Java's start ended ~14 s after).
    pub(in crate::game) fn java_wait_for_start() {
        if super::super::java_mods::java_starting() {
            log::info!("Java mods: waiting for their start");
            super::super::java_mods::wait_for_java(std::time::Duration::from_secs(900));
        }
    }
}
