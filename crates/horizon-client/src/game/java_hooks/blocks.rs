//! Hooks in the level's blocks without the `java-mods` feature (bodies:
//! `java_server/blocks.rs`, `java_server/items.rs`): random ticks, fluids, neighbour updates,
//! breaks and places, crafted takes, hoppers on mods' blocks. None of them is a mod's.
use super::super::{Game, java_server::JavaBreak};
use horizon_core::{geometry::Direction, position::BlockPos};
use horizon_world::registry::BlockStateId;

impl Game<'_> {
    /// Whether the random tick at `pos` is the mods' instead of Horizon's.
    pub(in crate::game) fn java_random_tick(_pos: BlockPos, _state: BlockStateId) -> bool {
        false
    }
    /// `LevelAccess::foreign_fluid`: whether `state` holds a Java mod's fluid.
    pub(in crate::game) fn java_fluid(_state: BlockStateId) -> bool {
        false
    }
    /// `LevelAccess::foreign_fluid_changed`.
    pub(in crate::game) fn java_fluid_changed(_pos: BlockPos) {}
    /// `LevelAccess::liquid_interaction`: whether the lava at `pos` is the mods'.
    pub(in crate::game) fn java_liquid_interaction(_pos: BlockPos) -> bool {
        false
    }
    /// `LevelAccess::observe_neighbours_updated`.
    pub(in crate::game) fn java_neighbours_updated(
        _source: BlockPos,
        _state_at: impl Fn(BlockPos) -> BlockStateId,
    ) {
    }
    /// Whether the precipitation tick at the column topped by `top` is the mods'.
    pub(in crate::game) fn java_precipitation(_top: BlockPos) -> bool {
        false
    }
    /// Whether a Java mod's block holds the item entity `id` this tick.
    pub(in crate::game) fn java_holds_item(_id: u32) -> bool {
        false
    }
    pub(in crate::game) fn java_random_ticks(&mut self) {}
    /// `/data get block` on a mod's block entity: its NBT.
    pub(in crate::game) fn java_block_entity_data(
        &self,
        _pos: [i32; 3],
    ) -> Option<horizon_world::nbt::Tag> {
        None
    }
    pub(in crate::game) fn apply_java_level_writes(&mut self) {}
    pub(in crate::game) fn java_block_event(
        &mut self,
        _pos: [i32; 3],
        _state: BlockStateId,
        _placed: bool,
    ) {
    }
    pub(in crate::game) fn java_break(
        &mut self,
        _pos: [i32; 3],
        _state: BlockStateId,
    ) -> JavaBreak {
        JavaBreak::Horizon { after: false }
    }
    pub(in crate::game) fn java_after_break(&mut self, _pos: [i32; 3], _state: BlockStateId) {}
    pub(in crate::game) fn java_attack_block(&mut self, _pos: [i32; 3], _face: i32) -> bool {
        false
    }
    pub(in crate::game) fn java_use_block(
        &mut self,
        _pos: [i32; 3],
        _face: i32,
        _point: [f64; 3],
        _off_hand: bool,
    ) -> Option<bool> {
        None
    }
    pub(in crate::game) fn java_crafted(&mut self, _takes: &[horizon_client::menu::CraftedTake]) {}
    pub(in crate::game) fn java_hopper_insert(
        &mut self,
        _pos: [i32; 3],
        _face: Direction,
        _stacks: &[Option<&horizon_client::items::ItemStack>],
    ) -> Option<Option<usize>> {
        None
    }
    pub(in crate::game) fn java_hopper_extract(
        &mut self,
        _pos: [i32; 3],
        _face: Direction,
        _any_room: bool,
        _partial: &[&horizon_client::items::ItemStack],
    ) -> Option<Option<horizon_client::items::ItemStack>> {
        None
    }
}
