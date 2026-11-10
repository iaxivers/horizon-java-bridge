//! The Java mods' server side in the game (docs/java-bridge.md): the game that simulates the
//! world (in-frame single player, the server thread, a LAN host, the dedicated server) hands
//! each tick to the mods' mirror (crate horizon-jvm), with who joined and left and what players
//! said, broke and placed since the last one; a player's command for a mod's command reaches
//! them at once. What the mods ask back (`events::Action`) is applied here: routed actions,
//! menus, block entities, keys, entities. With no Java mods on, each hook here is one check.
//! (Was the Java half of `server_mods.rs`, which upstream deleted with WebAssembly code mods.)
//!
//! This file is in every build: the types Horizon's files name and what is the same in both.
//! The bodies are feature-only, by area (`tick`, `blocks`, `players`, `menus`, `commands`,
//! `items`, `entities`); without the feature, `off.rs` has this file's state and
//! `game/java_hooks/` the hooks' plain answers.
use super::Game;

/// Who did something the Java mods hear about, resolved at the tick's end.
#[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
pub(super) enum Who {
    /// The window's own player, in-frame.
    Local,
    /// A connection's player (the server thread's owner is one).
    Conn(u32),
    Uuid(u128),
}

/// What Java mods make of a player's break of a block (`Game::java_break`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
pub(super) enum JavaBreak {
    /// A mod stopped it: the client puts the block back on the server's acknowledgement.
    Cancelled,
    /// The game breaks it; `after`: then `Game::java_after_break`.
    Horizon { after: bool },
    /// The mods' vanilla code broke it (a mod patches the inside of breaking: a vein miner):
    /// its removal, drops and the tool's wear come as Java's actions; nothing more to do.
    Java,
}
impl JavaBreak {
    /// Whether the game breaks the block (`Some`, with `after`), at
    /// `Game::break_block_as_player`'s `ServerPlayerGameMode.destroyBlock`: Java mods'
    /// injections at destroyBlock's head and Fabric's PlayerBlockBreakEvents.BEFORE may stop
    /// the break (the client puts the block back on the server's acknowledgement); a mod that
    /// patches the inside of breaking (a vein miner) has vanilla's whole break run on the Java
    /// side instead.
    pub(super) fn horizon(self) -> Option<bool> {
        match self {
            JavaBreak::Cancelled | JavaBreak::Java => None,
            JavaBreak::Horizon { after } => Some(after),
        }
    }
}

/// What players did during a tick, for the Java mods at its end.
#[cfg_attr(not(feature = "java-mods"), allow(dead_code))]
pub(super) enum ModEvent {
    Chat(Who, String),
    Block {
        who: Who,
        pos: [i32; 3],
        state: String,
        placed: bool,
    },
}

impl Game<'_> {
    /// Adds `effect` to the player acting now unless a Java mod's head of
    /// `LivingEntity.addEffect` refuses it: the effects Horizon adds itself (beacons, conduits,
    /// raids' Hero of the Village, Bad Omen, the Warden's darkness). Whether it was added.
    pub(super) fn add_effect_asking_mods(
        &mut self,
        effect: horizon_client::effects::EffectInstance,
    ) -> bool {
        if self.java_add_effect(&effect) {
            return false;
        }
        self.potions.effects.add(effect);
        true
    }

    /// Whether the Java mod's block (or a mod's container entity) at `pos` holds items on
    /// `face`: Fabric's `ItemStorage.SIDED` finds a storage there (asked with no item).
    pub(super) fn java_item_storage(
        &mut self,
        pos: [i32; 3],
        face: horizon_core::geometry::Direction,
    ) -> bool {
        self.java_hopper_insert(pos, face, &[]).is_some()
    }
}

#[cfg(not(feature = "java-mods"))]
mod off;
#[cfg(not(feature = "java-mods"))]
pub(super) use off::*;

#[cfg(feature = "java-mods")]
mod blocks;
#[cfg(feature = "java-mods")]
mod commands;
#[cfg(feature = "java-mods")]
mod entities;
#[cfg(feature = "java-mods")]
mod items;
#[cfg(feature = "java-mods")]
mod menus;
#[cfg(feature = "java-mods")]
mod phases;
#[cfg(feature = "java-mods")]
mod players;
#[cfg(feature = "java-mods")]
mod saves;
#[cfg(feature = "java-mods")]
mod tick;
#[cfg(feature = "java-mods")]
pub(super) use self::{blocks::java_block_set, tick::JavaServerState};
#[cfg(feature = "java-mods")]
use self::{blocks::*, entities::*, items::*, menus::*, players::*, tick::*};
