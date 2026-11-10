//! Java mods and the level's blocks: the mirror's view of Horizon's blocks, random ticks,
//! fluids, neighbour updates, breaks and places, block entities, the blocks they set.
use super::*;
use horizon_client::chunk_map::ChunkMap;
use horizon_world::registry::{BlockRegistry, BlockStateId};

impl Game<'_> {
    /// The chunks Java mods' chunk loaders keep in `dimension` (none without Java mods).
    pub(in crate::game) fn java_tickets(dimension: &str) -> Vec<(i32, i32)> {
        JAVA_TICKETS
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .and_then(|m| m.get(dimension).cloned())
            .unwrap_or_default()
    }

    /// The precipitation tick at the column topped by `top` is kept for the Java mods (true), when
    /// they patch the biomes' weather (`events::routed_actions`' "precipitation"): Java mods that
    /// do (Serene Seasons) answer it at the end of the level's tick (`Game::java_random_ticks`),
    /// and `GameLevel::precipitation` (game.rs) has none for it.
    pub(in crate::game) fn java_precipitation(top: horizon_core::position::BlockPos) -> bool {
        if !JAVA_ROUTED
            .get()
            .is_some_and(|r| r.iter().any(|a| a == "precipitation"))
        {
            return false;
        }
        let mut kept = JAVA_PRECIPITATION.lock().unwrap_or_else(|e| e.into_inner());
        if kept.len() >= 4096 {
            return false;
        }
        kept.push(top);
        true
    }

    /// `LevelAccess::liquid_interaction`: the lava at `pos` is kept for the Java mods' own fluid
    /// interactions (true), when they patch `LiquidBlock.shouldSpreadLiquid`.
    pub(in crate::game) fn java_liquid_interaction(pos: horizon_core::position::BlockPos) -> bool {
        if !JAVA_ROUTED
            .get()
            .is_some_and(|r| r.iter().any(|a| a == "liquid_interactions"))
        {
            return false;
        }
        let mut kept = JAVA_LIQUIDS.lock().unwrap_or_else(|e| e.into_inner());
        if kept.len() >= 4096 {
            return false;
        }
        if !kept.contains(&[pos.x, pos.y, pos.z]) {
            kept.push([pos.x, pos.y, pos.z]);
        }
        true
    }

    /// True when the tick is the mods' instead of Horizon's (a routed vanilla block's); a Java
    /// mod's own block is kept for the mods too (Game::java_random_ticks), and Horizon still runs
    /// its template block's tick. Asked where native mods get theirs (GameLevel::before_random_tick).
    pub(in crate::game) fn java_random_tick(
        pos: horizon_core::position::BlockPos,
        state: horizon_world::registry::BlockStateId,
    ) -> bool {
        let Some(states) = JAVA_STATES.get() else {
            return false;
        };
        let routed = JAVA_ROUTED_TICKS
            .get()
            .and_then(|r| r.get(state.0 as usize).copied())
            .unwrap_or(false);
        if routed || states.get(state.0 as usize).copied().unwrap_or(false) {
            let mut ticks = JAVA_RANDOM_TICKS.lock().unwrap_or_else(|e| e.into_inner());
            if ticks.len() < 65_536 {
                ticks.push(pos);
            } else {
                return false;
            }
        }
        routed
    }

    /// `LevelAccess::foreign_fluid`: whether `state` holds a Java mod's fluid.
    pub(in crate::game) fn java_fluid(state: horizon_world::registry::BlockStateId) -> bool {
        JAVA_FLUID_STATES
            .get()
            .and_then(|s| s.get(state.0 as usize).copied())
            .unwrap_or(false)
    }

    /// `LevelAccess::foreign_fluid_changed`: a Java mod's fluid at `pos` was placed or a neighbour
    /// of it changed; the mods schedule its tick (`HorizonServer.blockEntities`, with the blocks set).
    pub(in crate::game) fn java_fluid_changed(pos: horizon_core::position::BlockPos) {
        let mut sets = JAVA_SETS.lock().unwrap_or_else(|e| e.into_inner());
        if sets.len() < 65_536 {
            sets.push(([pos.x, pos.y, pos.z], None));
        }
    }

    /// `LevelAccess::observe_neighbours_updated`: kept for the mods when a neighbour of `source`
    /// is a Java mod's block (`state_at` reads the level).
    pub(in crate::game) fn java_neighbours_updated(
        source: horizon_core::position::BlockPos,
        state_at: impl Fn(horizon_core::position::BlockPos) -> horizon_world::registry::BlockStateId,
    ) {
        let Some(states) = JAVA_STATES.get() else {
            return;
        };
        let java = |id: u32| states.get(id as usize).copied().unwrap_or(false);
        let near = [
            (1, 0, 0),
            (-1, 0, 0),
            (0, 1, 0),
            (0, -1, 0),
            (0, 0, 1),
            (0, 0, -1),
        ]
        .iter()
        .any(|&(x, y, z)| {
            java(
                state_at(horizon_core::position::BlockPos::new(
                    source.x + x,
                    source.y + y,
                    source.z + z,
                ))
                .0,
            )
        });
        if near {
            let mut all = JAVA_NEIGHBOURS.lock().unwrap_or_else(|e| e.into_inner());
            let p = [source.x, source.y, source.z];
            if all.len() < 65_536 && all.last() != Some(&p) {
                all.push(p);
            }
        }
    }
}

/// Random ticks that reached a block only a Java mod knows, for the mods at the end of the
/// level's block tick (Game::java_random_ticks).
pub(super) static JAVA_RANDOM_TICKS: std::sync::Mutex<Vec<horizon_core::position::BlockPos>> =
    std::sync::Mutex::new(Vec::new());
/// Which states are a Java mod's (not vanilla's), by state id: made with the first random tick.
pub(super) static JAVA_STATES: std::sync::OnceLock<Vec<bool>> = std::sync::OnceLock::new();
/// Vanilla states whose random tick is the Java mods' (a mod patches the block's `randomTick`
/// or what it calls: Serene Seasons' crops), by state id: made with JAVA_STATES.
pub(super) static JAVA_ROUTED_TICKS: std::sync::OnceLock<Vec<bool>> = std::sync::OnceLock::new();

/// The chunks Java mods' tickets keep in each dimension (`Action::ChunkTickets`), kept here
/// too for a level that opens later.
pub(super) static JAVA_TICKETS: std::sync::Mutex<
    Option<std::collections::HashMap<String, Vec<(i32, i32)>>>,
> = std::sync::Mutex::new(None);

/// Precipitation ticks' columns (their tops) kept for Java mods that patch the biomes'
/// weather, answered at the end of the level's block tick (Game::java_random_ticks).
pub(super) static JAVA_PRECIPITATION: std::sync::Mutex<Vec<horizon_core::position::BlockPos>> =
    std::sync::Mutex::new(Vec::new());

/// Lava blocks whose fluid interactions are the mods' (`events::routed_actions`'
/// "liquid_interactions"), kept for the end of the level's block tick
/// (Game::java_random_ticks).
pub(super) static JAVA_LIQUIDS: std::sync::Mutex<Vec<[i32; 3]>> = std::sync::Mutex::new(Vec::new());

/// Blocks only Java mods know that a world's `set` placed or removed, for the mods (their block
/// entities exist from their placement, as vanilla's do: Game::java_random_ticks).
/// Each with the state it replaced (None: a fluid's neighbour changed, nothing was set there).
pub(super) static JAVA_SETS: std::sync::Mutex<Vec<([i32; 3], Option<u32>)>> =
    std::sync::Mutex::new(Vec::new());

/// The states that hold a Java mod's fluid (its `LiquidBlock`s: BuildCraft's oil, Create's
/// honey), by state id: made with JAVA_STATES. The mods' mirror runs their flow
/// (`HorizonTicks`); Horizon's fluid code leaves them (`LevelAccess::foreign_fluid`).
pub(super) static JAVA_FLUID_STATES: std::sync::OnceLock<Vec<bool>> = std::sync::OnceLock::new();

/// Positions whose neighbours Horizon updated with a Java mod's block among them
/// (`LevelAccess::observe_neighbours_updated`): the mods' blocks there hear it in the mirror
/// (`HorizonServer.neighbours`: their shape updates and neighbour changes, as vanilla's own
/// neighbour updater runs them; a hand crank pops off when its cogwheel breaks).
pub(super) static JAVA_NEIGHBOURS: std::sync::Mutex<Vec<[i32; 3]>> =
    std::sync::Mutex::new(Vec::new());

/// horizon_client::world::SET_HOOK: a Java mod's block placed or removed is kept for the mods.
pub(in crate::game) fn java_block_set(pos: (i32, i32, i32), previous: u32, state: u32) {
    // Before the mods' states are known (the first ticks), every set is kept: the mods look
    // at what is there (a blaze burner placed then had no block entity, ever).
    let java = |id: u32| {
        JAVA_STATES
            .get()
            .is_none_or(|states| states.get(id as usize).copied().unwrap_or(false))
    };
    if java(previous) || java(state) {
        let mut sets = JAVA_SETS.lock().unwrap_or_else(|e| e.into_inner());
        if sets.len() < 65_536 {
            sets.push(([pos.0, pos.1, pos.2], Some(previous)));
        }
    }
}

/// Blocks queued for levels that don't tick (another dimension, in-frame)
/// are kept up to this many; the oldest go first.
pub(super) const MAX_QUEUED_WRITES: usize = 1 << 16;

/// Horizon's levels as Java mods read them during a call (crate horizon-jvm's `world`):
/// borrowed, not lent, so nothing moves.
pub(super) struct JavaBlocks<'a> {
    pub(super) registry: &'static BlockRegistry,
    pub(super) dims: Vec<(&'static str, &'a ChunkMap)>,
    /// The level running now (the first of `dims`) and its block entities.
    pub(super) entities: &'a horizon_client::block_entities::BlockEntities,
    pub(super) biomes: &'a horizon_client::biome::Biomes,
}
impl horizon_jvm::world::Blocks for JavaBlocks<'_> {
    fn block(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
        let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
        let chunk = chunks.get(&(x >> 4, z >> 4))?;
        Some(state_string(self.registry, chunk.block(x & 15, y, z & 15)))
    }
    fn block_entity(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
        let (current, chunks) = self.dims.first()?;
        if *current != dimension {
            return None;
        }
        let e = self.entities.get(&(x, y, z))?;
        let state = chunks.get(&(x >> 4, z >> 4))?.block(x & 15, y, z & 15);
        let name = self.registry.state(state)?.name.clone();
        // BlockEntity.saveWithFullMetadata: no chunk-only keepPacked.
        let mut tag = horizon_client::save::encode_block_entity((x, y, z), e, &name);
        if let horizon_world::nbt::Tag::Compound(c) = &mut tag {
            c.remove(&"keepPacked".into());
        }
        Some(horizon_client::snbt::to_snbt(&tag))
    }
    fn light(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<u8> {
        let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
        Some(chunks.get(&(x >> 4, z >> 4))?.light(x & 15, y, z & 15))
    }
    fn column(
        &self,
        dimension: &str,
        cx: i32,
        cz: i32,
        min_y: i32,
        out: &mut [i32],
    ) -> Option<Vec<String>> {
        let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
        let chunk = chunks.get(&(cx, cz))?;
        // Whole sections unpacked at once (Java asks for one section at a time, or the
        // whole column): a few states each, found by a short search, not a hash.
        thread_local! {
            /// Each state's place in the palette being made (-1: none yet), by state id.
            static PLACES: std::cell::RefCell<Vec<i32>> = const { std::cell::RefCell::new(Vec::new()) };
        }
        let mut ids: Vec<u32> = Vec::new();
        let mut unpacked = [0u32; 4096];
        for (n, part) in out.chunks_mut(4096).enumerate() {
            let base = min_y + n as i32 * 16;
            let s = (base - horizon_client::chunk::MIN_Y) >> 4;
            let aligned = base & 15 == 0 && part.len() == 4096;
            match chunk.sections.get(s as usize) {
                Some(section) if aligned && s >= 0 => section.unpack(&mut unpacked),
                _ => {
                    for (i, v) in unpacked.iter_mut().enumerate().take(part.len()) {
                        let (x, z, y) = (
                            (i & 15) as i32,
                            ((i >> 4) & 15) as i32,
                            base + (i >> 8) as i32,
                        );
                        *v = chunk.block(x, y, z).0;
                    }
                }
            }
            PLACES.with_borrow_mut(|places| {
                for (slot, &state) in part.iter_mut().zip(unpacked.iter()) {
                    let i = state as usize;
                    if i >= places.len() {
                        places.resize(i + 1, -1);
                    }
                    if places[i] < 0 {
                        places[i] = ids.len() as i32;
                        ids.push(state);
                    }
                    *slot = places[i];
                }
            });
        }
        PLACES.with_borrow_mut(|places| {
            for &id in &ids {
                places[id as usize] = -1;
            }
        });
        Some(
            ids.into_iter()
                .map(|id| state_string(self.registry, BlockStateId(id)))
                .collect(),
        )
    }
    fn section_ids(
        &self,
        dimension: &str,
        cx: i32,
        sy: i32,
        cz: i32,
        out: &mut [i32],
    ) -> Option<bool> {
        let Some((_, chunks)) = self.dims.iter().find(|(d, _)| *d == dimension) else {
            return Some(false);
        };
        let Some(chunk) = chunks.get(&(cx, cz)) else {
            return Some(false);
        };
        let s = sy - (horizon_client::chunk::MIN_Y >> 4);
        let mut unpacked = [0u32; 4096];
        match usize::try_from(s).ok().and_then(|s| chunk.sections.get(s)) {
            Some(section) => section.unpack(&mut unpacked),
            // Above or below the world: air.
            None => unpacked.fill(0),
        }
        for (slot, id) in out.iter_mut().zip(unpacked) {
            *slot = id as i32;
        }
        Some(true)
    }
    fn chunk_revision(&self, dimension: &str, cx: i32, cz: i32) -> i64 {
        let Some((_, chunks)) = self.dims.iter().find(|(d, _)| *d == dimension) else {
            return -2;
        };
        chunks
            .get(&(cx, cz))
            .map_or(-2, |chunk| chunk.block_revision.get() as i64)
    }
    fn surface(&self, dimension: &str, x: i32, z: i32) -> Option<i32> {
        let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
        let chunk = chunks.get(&(x >> 4, z >> 4))?;
        let registry = self.registry;
        Some(chunk.world_surface(x & 15, z & 15, |v| {
            registry.state(BlockStateId(v)).is_some_and(|s| {
                matches!(
                    s.name.as_str(),
                    "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
                )
            })
        }))
    }
    fn state_name(&self, id: i32) -> Option<String> {
        let id = u32::try_from(id).ok()?;
        self.registry
            .state(BlockStateId(id))
            .map(|_| state_string(self.registry, BlockStateId(id)))
    }
    fn column_biomes(
        &self,
        dimension: &str,
        cx: i32,
        cz: i32,
        min_y: i32,
        out: &mut [i32],
    ) -> Option<Vec<String>> {
        let (_, chunks) = self.dims.iter().find(|(d, _)| *d == dimension)?;
        let chunk = chunks.get(&(cx, cz))?;
        let mut palette = Vec::new();
        let mut index: std::collections::HashMap<u16, i32> = std::collections::HashMap::new();
        for (i, slot) in out.iter_mut().enumerate() {
            let (qx, qz, qy) = ((i & 3) as i32, ((i >> 2) & 3) as i32, (i >> 4) as i32);
            let biome = chunk.biome(qx * 4, min_y + qy * 4, qz * 4);
            *slot = *index.entry(biome).or_insert_with(|| {
                palette.push(self.biomes.get(biome).name.clone());
                palette.len() as i32 - 1
            });
        }
        Some(palette)
    }
}

/// `minecraft:oak_log[axis=y]`.
pub(super) fn state_string(registry: &BlockRegistry, state: BlockStateId) -> String {
    let Some(s) = registry.state(state) else {
        return "minecraft:air".into();
    };
    if s.properties.is_empty() {
        return s.name.clone();
    }
    let props: Vec<String> = s
        .properties
        .iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect();
    format!("{}[{}]", s.name, props.join(","))
}

impl Game<'_> {
    /// `/data get block` on a mod's block entity (`HorizonServer.blockEntityData`): its NBT
    /// from the mods' mirror, where it lives. None where no mod block entity is up.
    pub(in crate::game) fn java_block_entity_data(
        &self,
        pos: [i32; 3],
    ) -> Option<horizon_world::nbt::Tag> {
        if !java_mods_on() {
            return None;
        }
        let snbt = horizon_jvm::events::block_entity_data(self.dim.id(), pos).ok()??;
        horizon_world::nbt::parse_snbt(&snbt)
            .ok()
            .map(|(tag, _)| tag)
    }
    /// Horizon's mobs within 32 blocks of a player, per dimension, for the mods' mobs to
    /// see (`HorizonNeighbours`): `{"<dimension>": [[id, "type", x, y, z, yaw, health]]}`,
    /// at most 128 a level.
    pub(super) fn java_neighbours_json(&self, players: &[horizon_jvm::events::Player]) -> String {
        use std::fmt::Write;
        let mut out = String::from("{");
        let level = |out: &mut String, dim: &str, mobs: &[horizon_client::mobs::Mob]| {
            let near: Vec<[f64; 3]> = players
                .iter()
                .filter(|p| p.dimension == dim)
                .map(|p| p.position)
                .collect();
            if near.is_empty() {
                return;
            }
            if out.len() > 1 {
                out.push(',');
            }
            let _ = write!(out, "{}:[", serde_json::to_string(dim).unwrap_or_default());
            let mut n = 0;
            for m in mobs.iter().filter(|m| m.alive() && !m.removed) {
                let at = [m.position.x, m.position.y, m.position.z];
                if !near.iter().any(|p| {
                    (p[0] - at[0]).powi(2) + (p[1] - at[1]).powi(2) + (p[2] - at[2]).powi(2)
                        <= 32.0 * 32.0
                }) {
                    continue;
                }
                if n > 0 {
                    out.push(',');
                }
                let _ = write!(
                    out,
                    "[{},\"minecraft:{}\",{},{},{},{},{},{}]",
                    m.id,
                    m.kind.id.trim_start_matches("minecraft:"),
                    at[0],
                    at[1],
                    at[2],
                    m.yaw,
                    m.health,
                    m.invulnerable
                );
                n += 1;
                if n >= 128 {
                    break;
                }
            }
            out.push(']');
        };
        level(&mut out, self.dim.id(), &self.mobs);
        for dim in self.levels.parked_dims() {
            if let Some(mobs) = self.levels.parked_mobs(dim) {
                level(&mut out, dim.id(), mobs);
            }
        }
        out.push('}');
        out
    }

    /// The blocks a Java mod set in the level in place while it used a block or an item
    /// (a mod's `BlockItem.useOn` placing its block in the mirror), made now, before the
    /// click's correction and its acknowledgement go to the player: vanilla's
    /// `useItemOn` sets the block in the handler. Left for the next tick, the correction
    /// sent air where the player's game had predicted the block and the acknowledgement
    /// made its game take that air: the placed block vanished until the next tick's update
    /// brought it back, and stayed gone where that update didn't come.
    pub(super) fn apply_java_use_writes(&mut self) {
        let dim = self.dim.id();
        let mut later = Vec::new();
        for action in horizon_jvm::events::take_actions() {
            match action {
                horizon_jvm::events::Action::SetBlock {
                    dimension,
                    position,
                    state,
                } if dimension == dim => self
                    .mods
                    .java
                    .block_writes
                    .push((dimension, position, state)),
                other => later.push(other),
            }
        }
        for action in later {
            horizon_jvm::events::push_action(action);
        }
        self.apply_java_level_writes();
    }

    /// The player acting now uses (right-clicks) the block `pos` on `face` (vanilla's
    /// `Direction` id) at `point`: vanilla's `useItemOn` head for Java mods (Fabric API's
    /// `UseBlockCallback`, library events). Some(swing) when a mod took the click, so the
    /// game does nothing more.
    pub(in crate::game) fn java_use_block(
        &mut self,
        pos: [i32; 3],
        face: i32,
        point: [f64; 3],
        off_hand: bool,
    ) -> Option<bool> {
        {
            if !java_mods_on() || !self.hears_players() {
                return None;
            }
            let uuid = self
                .lan_acting_uuid
                .map(uuid_string)
                .unwrap_or_else(|| self.java_local_id());
            let (dim, selected) = (self.dim.id(), self.selected as i32);
            let used = self.with_java_world(|| {
                horizon_jvm::events::use_block(
                    &uuid,
                    dim,
                    pos,
                    face,
                    point,
                    i32::from(off_hand),
                    selected,
                )
            });
            self.apply_java_actor_slots(&uuid);
            self.apply_java_use_writes();
            match used {
                Ok(horizon_jvm::events::UseResult::Pass) => None,
                Ok(horizon_jvm::events::UseResult::Taken) => Some(false),
                Ok(horizon_jvm::events::UseResult::TakenSwing) => Some(true),
                Err(e) => {
                    log::warn!("Java mods: using a block failed: {e}");
                    None
                }
            }
        }
    }

    /// A Java mod's change to a vanilla block entity (a storage mod took from a chest): the
    /// block entity there is now what the mod saved, in the level running now.
    pub(super) fn set_java_block_entity(
        &mut self,
        dimension: &str,
        [x, y, z]: [i32; 3],
        snbt: &str,
    ) {
        if dimension != self.dim.id() {
            return;
        }
        let tag = match horizon_world::nbt::parse_snbt(snbt) {
            Ok((tag, _)) => tag,
            Err(e) => {
                log::warn!("Java mods: a block entity at {x} {y} {z}: {e}");
                return;
            }
        };
        if let Some((_, entity)) = horizon_client::save::decode_block_entity(&tag) {
            self.world.block_entities.insert((x, y, z), entity);
            // Redraw and resend what shows it.
            let state = self.world.block(x, y, z);
            let _ = self.world.set(x, y, z, state);
        }
    }

    /// The random ticks this level's blocks of Java mods got: the mods run them
    /// (`BlockState.randomTick`), on the world as it is now.
    pub(in crate::game) fn java_random_ticks(&mut self) {
        {
            if !java_mods_on() {
                return;
            }
            if super::JAVA_STATES.get().is_none() {
                // The mods' Mixins are read once Fabric runs (the routed ticks below).
                if !horizon_jvm::events::ready() {
                    return;
                }
                let registry = self.pack.assets.registry;
                let states = (0..registry.len() as u32)
                    .map(|id| {
                        registry
                            .state(BlockStateId(id))
                            .is_some_and(|s| !s.name.starts_with("minecraft:"))
                    })
                    .collect();
                // Vanilla blocks whose random tick a mod patches: vanilla's code with
                // the patches runs them (HorizonServer.routedRandomTicks).
                let routed: std::collections::HashSet<String> =
                    match horizon_jvm::events::routed_random_ticks() {
                        Ok(names) => names.into_iter().collect(),
                        Err(e) => {
                            log::warn!("Java mods: routed random ticks: {e}");
                            Default::default()
                        }
                    };
                if !routed.is_empty() {
                    log::info!(
                        "Java mods: random ticks of {} vanilla blocks go to the mods",
                        routed.len()
                    );
                }
                let routed = (0..registry.len() as u32)
                    .map(|id| {
                        registry.state(BlockStateId(id)).is_some_and(|s| {
                            let block = s.name.split('[').next().unwrap_or(&s.name);
                            routed.contains(block)
                        })
                    })
                    .collect();
                let _ = super::JAVA_ROUTED_TICKS.set(routed);
                // The mods' fluids: their blocks' states (every state of a LiquidBlock).
                let fluids: std::collections::HashSet<String> =
                    match horizon_jvm::events::mod_fluid_blocks() {
                        Ok(names) => names.into_iter().collect(),
                        Err(e) => {
                            log::warn!("Java mods: their fluids: {e}");
                            Default::default()
                        }
                    };
                if !fluids.is_empty() {
                    log::info!(
                        "Java mods: {} fluid blocks flow by the mods' code",
                        fluids.len()
                    );
                }
                let fluids = (0..registry.len() as u32)
                    .map(|id| {
                        registry.state(BlockStateId(id)).is_some_and(|s| {
                            let block = s.name.split('[').next().unwrap_or(&s.name);
                            fluids.contains(block)
                        })
                    })
                    .collect();
                let _ = super::JAVA_FLUID_STATES.set(fluids);
                let _ = super::JAVA_STATES.set(states);
            }
            // Java mods' blocks placed or removed: their block entities made or put away.
            let sets =
                std::mem::take(&mut *super::JAVA_SETS.lock().unwrap_or_else(|e| e.into_inner()));
            if !sets.is_empty() {
                let dim = self.dim.id();
                // What each block replaced, by name ("" when nothing was set there): the
                // mods' blocks' onPlace and affectNeighborsAfterRemoval run with it.
                let registry = self.pack.assets.registry;
                let previous: Vec<String> = sets
                    .iter()
                    .map(|(_, p)| {
                        p.and_then(|id| registry.state(BlockStateId(id)))
                            .map(|s| s.name.to_string())
                            .unwrap_or_default()
                    })
                    .collect();
                let positions: Vec<[i32; 3]> = sets.iter().map(|(p, _)| *p).collect();
                let ran = self.with_java_world(|| {
                    horizon_jvm::events::block_entities(dim, &positions, &previous)
                });
                if let Err(e) = ran {
                    log::warn!("Java mods: block entities failed: {e}");
                }
            }
            // Neighbours Horizon updated beside the mods' blocks: they hear it.
            let mut updated = std::mem::take(
                &mut *super::JAVA_NEIGHBOURS
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
            );
            updated.dedup();
            if !updated.is_empty() {
                let dim = self.dim.id();
                let ran = self.with_java_world(|| horizon_jvm::events::neighbours(dim, &updated));
                if let Err(e) = ran {
                    log::warn!("Java mods: neighbour updates failed: {e}");
                }
            }
            // The routed actions are asked once Fabric runs (precipitation's flag too).
            let _ = super::java_routed("precipitation");
            self.java_precipitation_ticks();
            // Lava the mods' fluid interactions took this tick: theirs run now.
            let liquids = std::mem::take(
                &mut *super::JAVA_LIQUIDS
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
            );
            if !liquids.is_empty() {
                let dim = self.dim.id();
                let ran = self
                    .with_java_world(|| horizon_jvm::events::liquid_interactions(dim, &liquids));
                if let Err(e) = ran {
                    log::warn!("Java mods: fluid interactions failed: {e}");
                }
            }
            let ticks = std::mem::take(
                &mut *super::JAVA_RANDOM_TICKS
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
            );
            if ticks.is_empty() {
                return;
            }
            let positions: Vec<[i32; 3]> = ticks.iter().map(|p| [p.x, p.y, p.z]).collect();
            let dim = self.dim.id();
            let ran = self.with_java_world(|| horizon_jvm::events::random_ticks(dim, &positions));
            if let Err(e) = ran {
                log::warn!("Java mods: random ticks failed: {e}");
            }
        }
    }

    /// The player acting now is about to break `state` at `pos`: the mods' injections at
    /// `destroyBlock`'s head and Fabric's `PlayerBlockBreakEvents.BEFORE` (a mod may stop
    /// it), or vanilla's whole break on the Java side when a mod patches its inside (a vein
    /// miner). What the mods change (a tree feller's logs) is made with this tick's other
    /// actions.
    pub(in crate::game) fn java_break(
        &mut self,
        pos: [i32; 3],
        state: BlockStateId,
    ) -> super::JavaBreak {
        use super::JavaBreak;
        {
            if !java_mods_on() || !self.hears_players() {
                return JavaBreak::Horizon { after: false };
            }
            let uuid = self
                .lan_acting_uuid
                .map(uuid_string)
                .unwrap_or_else(|| self.java_local_id());
            let state = state_string(self.pack.assets.registry, state);
            let (dim, selected) = (self.dim.id(), self.selected as i32);
            let result = self.with_java_world(|| {
                horizon_jvm::events::break_block(&uuid, dim, pos, &state, selected)
            });
            self.apply_java_actor_slots(&uuid);
            match result {
                Ok(horizon_jvm::events::BreakResult::Cancelled) => JavaBreak::Cancelled,
                Ok(horizon_jvm::events::BreakResult::Horizon { after }) => {
                    JavaBreak::Horizon { after }
                }
                Ok(horizon_jvm::events::BreakResult::Java) => JavaBreak::Java,
                Err(e) => {
                    log::warn!("Java mods: a block break failed: {e}");
                    JavaBreak::Horizon { after: false }
                }
            }
        }
    }

    /// The player acting now starts digging `pos` from `face` (vanilla's `Direction` id):
    /// Fabric's `AttackBlockCallback`. True when a Java mod took it (no dig starts).
    pub(in crate::game) fn java_attack_block(&mut self, pos: [i32; 3], face: i32) -> bool {
        {
            if !java_mods_on() || !self.hears_players() {
                return false;
            }
            let uuid = self
                .lan_acting_uuid
                .map(uuid_string)
                .unwrap_or_else(|| self.java_local_id());
            let (dim, selected) = (self.dim.id(), self.selected as i32);
            let taken = self.with_java_world(|| {
                horizon_jvm::events::attack_block(&uuid, dim, pos, face, selected)
            });
            self.apply_java_actor_slots(&uuid);
            taken.unwrap_or_else(|e| {
                log::warn!("Java mods: attacking a block failed: {e}");
                false
            })
        }
    }

    /// The precipitation ticks kept for the Java mods this tick: the biome's answers on the
    /// mirror (`Biome.shouldFreeze`, `shouldSnow`, `getPrecipitationAt` with Serene
    /// Seasons' patches), then Horizon's `tickPrecipitation` with them.
    pub(super) fn java_precipitation_ticks(&mut self) {
        let tops = std::mem::take(
            &mut *super::JAVA_PRECIPITATION
                .lock()
                .unwrap_or_else(|e| e.into_inner()),
        );
        if tops.is_empty() {
            return;
        }
        let flat: Vec<i32> = tops.iter().flat_map(|p| [p.x, p.y, p.z]).collect();
        let dim = self.dim.id();
        let answers = match self.with_java_world(|| horizon_jvm::events::precipitation(dim, &flat))
        {
            Ok(a) => a,
            Err(e) => {
                log::warn!("Java mods: precipitation failed: {e}");
                return;
            }
        };
        use horizon_world::level::{Precipitation, PrecipitationKind};
        for (top, a) in tops.into_iter().zip(answers.chunks_exact(3)) {
            let info = Precipitation {
                top,
                freezes_below: a[0] != 0,
                snows_at_top: a[1] != 0,
                kind: match a[2] {
                    1 => PrecipitationKind::Rain,
                    2 => PrecipitationKind::Snow,
                    _ => PrecipitationKind::None,
                },
            };
            self.with_level(|b, level, ticks| b.precipitation_tick_with(level, ticks, info));
        }
    }

    /// The game broke `state` at `pos` after [`Game::java_break`] said `after`: the mods'
    /// injections at `destroyBlock`'s end.
    pub(in crate::game) fn java_after_break(&mut self, pos: [i32; 3], state: BlockStateId) {
        {
            if !java_mods_on() || !self.hears_players() {
                return;
            }
            let uuid = self
                .lan_acting_uuid
                .map(uuid_string)
                .unwrap_or_else(|| self.java_local_id());
            let state = state_string(self.pack.assets.registry, state);
            let dim = self.dim.id();
            let ran =
                self.with_java_world(|| horizon_jvm::events::after_break(&uuid, dim, pos, &state));
            self.apply_java_actor_slots(&uuid);
            if let Err(e) = ran {
                log::warn!("Java mods: after a block break: {e}");
            }
        }
    }

    /// Makes the blocks Java mods set in the level in place, in order, as `/setblock` does,
    /// and their drops and level events. Those of other dimensions wait for their level.
    pub(in crate::game) fn apply_java_level_writes(&mut self) {
        self.apply_java_drops();
        if self.mods.java.block_writes.is_empty() {
            return;
        }
        let dim = self.dim.id();
        let (now, later): (Vec<_>, Vec<_>) = std::mem::take(&mut self.mods.java.block_writes)
            .into_iter()
            .partition(|(d, _, _)| d == dim);
        self.mods.java.block_writes = later;
        let registry = self.pack.assets.registry;
        // Writes into chunks Horizon hasn't loaded yet wait for them, as vanilla's setBlock
        // loads the chunk (One Block's join sets its grass at 0 64 0 before the spawn's
        // chunks are in; dropped, its safety check found air and put a random block there).
        // At most ~20 s each.
        static WAITING: std::sync::Mutex<
            Option<std::collections::HashMap<(String, [i32; 3]), u32>>,
        > = std::sync::Mutex::new(None);
        let mut waiting = WAITING.lock().unwrap_or_else(|e| e.into_inner());
        let waiting = waiting.get_or_insert_with(Default::default);
        for (d, pos, state) in now {
            if !self.world.loaded(pos[0], pos[2]) {
                let key = (d.clone(), pos);
                let waited = waiting.entry(key).or_insert(0);
                *waited += 1;
                if *waited < 400 {
                    self.mods.java.block_writes.push((d, pos, state));
                } else {
                    log::debug!("Java mods: a mod's block at {pos:?} never loaded");
                }
                continue;
            }
            waiting.remove(&(d.clone(), pos));
            let parsed = match crate::game::render_control::parse_state(registry, &state) {
                Ok(parsed) => parsed,
                Err(e) => {
                    log::warn!("Java mods: a mod set {state}: {e}");
                    continue;
                }
            };
            if let Err(e) = self.place_state(pos, parsed, false) {
                log::debug!("Java mods: a mod's block at {pos:?}: {e:?}");
            }
        }
        waiting.retain(|_, w| *w < 400);
    }

    /// A player broke (or placed) `state` at `pos`: the player acting now.
    pub(in crate::game) fn java_block_event(
        &mut self,
        pos: [i32; 3],
        state: BlockStateId,
        placed: bool,
    ) {
        if !self.hears_players() {
            return;
        }
        let who = self.lan_acting.map_or(Who::Local, Who::Conn);
        let state = self.state_string(state);
        self.mods.java.events.push(ModEvent::Block {
            who,
            pos,
            state,
            placed,
        });
    }
}
