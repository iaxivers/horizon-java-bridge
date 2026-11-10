//! Hooks about players without the `java-mods` feature (bodies: `java_server/players.rs`,
//! `tick.rs`, `commands.rs`, `items.rs`): the server's tick, hurts, effects, falls, jumps,
//! teleports, deaths, chat, commands, slots, advancements, pickups. No mod has a say.
use super::super::{Game, java_server::Who, lan::LanHost};
use horizon_client::effects::EffectInstance;

impl Game<'_> {
    /// The player a server turn acts for now: nobody hears it.
    pub(in crate::game) fn set_java_actor(_actor: Option<String>) -> Option<String> {
        None
    }
    /// A player is about to be hurt: no mod cancels or changes it.
    pub(in crate::game) fn java_hurt_player(
        _hurting: &horizon_client::health::Hurting<'_>,
        _amount: &mut f32,
    ) -> bool {
        false
    }
    pub(in crate::game) fn tick_java_server(&mut self) {}
    pub(in crate::game) fn java_tick_end(&mut self) {}
    pub(in crate::game) fn java_player_tick(&mut self, _host: &mut LanHost, _uuid: u128) {}
    pub(in crate::game) fn save_java_mods(&mut self) {}
    pub(in crate::game) fn java_world_closing(&mut self) {}
    pub(in crate::game) fn java_player_chat(&mut self, _who: Who, _text: &str) {}
    pub(in crate::game) fn local_java_command(&mut self, _line: &str) -> bool {
        false
    }
    pub(in crate::game) fn guest_java_command(
        &mut self,
        _host: &mut LanHost,
        _conn: u32,
        _line: &str,
    ) -> bool {
        false
    }
    pub(in crate::game) fn java_select_slot(&mut self, _slot: i32) -> bool {
        false
    }
    pub(in crate::game) fn java_add_effect(&mut self, _effect: &EffectInstance) -> bool {
        false
    }
    pub(in crate::game) fn java_add_effect_to(
        &mut self,
        _player: Option<u128>,
        _effect: &EffectInstance,
    ) -> bool {
        false
    }
    pub(in crate::game) fn java_jump_refused(&mut self) -> bool {
        false
    }
    pub(in crate::game) fn java_jumped(&mut self) {}
    pub(in crate::game) fn java_player_teleport(
        &mut self,
        _player: u128,
        _dim: &str,
        _at: [f64; 3],
        _look: [f32; 2],
        _end: bool,
    ) -> bool {
        false
    }
    pub(in crate::game) fn java_award_refused(
        &mut self,
        _id: &str,
        _criterion: &str,
        _done: &[&str],
    ) -> bool {
        false
    }
    pub(in crate::game) fn java_fall_routed(&self) -> bool {
        false
    }
    pub(in crate::game) fn java_fall(
        &mut self,
        _distance: f64,
        _multiplier: f32,
        _stalagmite: bool,
        _safe: f64,
        _damage_multiplier: f64,
    ) -> Option<f32> {
        None
    }
    pub(in crate::game) fn java_orb_touch(
        &mut self,
        _orb: &horizon_client::entities::ExperienceOrb,
    ) -> Option<(u32, i32)> {
        None
    }
    pub(in crate::game) fn java_item_touch(
        &mut self,
        _item: &horizon_client::entities::ItemEntity,
    ) -> Option<u32> {
        None
    }
    pub(in crate::game) fn java_player_died(
        &mut self,
        _player: u128,
        _message: Option<&str>,
        _conn: Option<u32>,
    ) {
    }
}
