//! The mirror's tick inside Horizon's (docs/java-bridge.md "How a tick flows"): its start runs
//! where the server's tick starts (`tick_java_server`), each level's parts inside Horizon's own
//! tick of that level (its start and end around it, the mods' scheduled ticks after Horizon's,
//! the mods' block entities in Horizon's block entity list, where each has a placeholder in the
//! order it was made), each player's hooks where Horizon ticks that player, and its end after
//! every level ticked. Vanilla's order: `MinecraftServer.tickChildren`, `ServerLevel.tick`,
//! `Level.tickBlockEntities`.
use super::*;
use horizon_jvm::events::LevelPhase;

impl Game<'_> {
    /// Whether this game's level ticks run the mirror's level parts now: Java mods on, this game
    /// the world's simulation, the mirror's tick started this server tick.
    fn java_phases_on(&self) -> bool {
        self.mods.java.tick_started && java_mods_on() && self.remote.is_none()
    }

    /// The level in place's tick starts (`START_LEVEL_TICK`, `ServerLevel.tick`'s head).
    pub(in crate::game) fn java_level_start(&mut self) {
        self.java_level_phase(LevelPhase::Start);
    }

    /// The mods' scheduled block and fluid ticks of the level in place, after Horizon's own.
    pub(in crate::game) fn java_level_scheduled_ticks(&mut self) {
        self.java_level_phase(LevelPhase::ScheduledTicks);
    }

    /// The level in place's block entities ended: what the mods did to Horizon's items, to Horizon.
    pub(in crate::game) fn java_block_entities_end(&mut self) {
        self.java_level_phase(LevelPhase::BlockEntitiesEnd);
    }

    /// The level in place's tick ends (`ServerLevel.tick`'s return, `END_LEVEL_TICK`).
    pub(in crate::game) fn java_level_end(&mut self) {
        self.java_level_phase(LevelPhase::End);
    }

    /// A part of the level in place's tick on the mirror (`HorizonServer.levelPhase`), then
    /// what it asked of Horizon.
    fn java_level_phase(&mut self, phase: LevelPhase) {
        if !self.java_phases_on() {
            return;
        }
        let dim = self.dim.id();
        let ran = self.with_java_world(|| horizon_jvm::events::level_phase(dim, phase));
        if let Err(e) = ran {
            log::warn!("Java mods: a level's tick ({phase:?}) failed: {e}");
        }
        self.apply_java_actions(None);
    }

    /// The level in place's block entities are about to tick: the mods' block entities that came
    /// up or went since, as placeholders in its list (`BlockEntities::add_foreign`), in that
    /// order; Horizon's items as its item tick left them, to the mirror, where those on or in
    /// mods' blocks touch them (`HorizonServer.levelItems`).
    pub(in crate::game) fn java_block_entities_start(&mut self) {
        if !java_mods_on() {
            return;
        }
        if self.java_phases_on() {
            let players = std::mem::take(&mut self.mods.java.tick_players);
            let items = self.java_items_json(&players);
            self.mods.java.tick_players = players;
            let dim = self.dim.id();
            let ran = self.with_java_world(|| horizon_jvm::events::level_items(dim, &items));
            if let Err(e) = ran {
                log::warn!("Java mods: Horizon's items not passed: {e}");
            }
            self.apply_java_actions(None);
        }
        for (pos, up) in horizon_jvm::world::take_block_entity_tickers(self.dim.id()) {
            let pos = (pos[0], pos[1], pos[2]);
            if up {
                self.world.block_entities.add_foreign(pos);
            } else {
                self.world.block_entities.remove_foreign(&pos);
            }
        }
    }

    /// The mods' block entities Horizon's block entity tick reached (their placeholders, in the
    /// list's order) since the last of Horizon's own: they tick on the mirror now, before the
    /// next of Horizon's, then what they asked of Horizon is made.
    pub(in crate::game) fn java_tick_block_entities(&mut self, reached: &mut Vec<[i32; 3]>) {
        if reached.is_empty() {
            return;
        }
        let positions = std::mem::take(reached);
        if !self.java_phases_on() {
            return;
        }
        let dim = self.dim.id();
        let ran =
            self.with_java_world(|| horizon_jvm::events::level_block_entities(dim, &positions));
        if let Err(e) = ran {
            log::warn!("Java mods: block entities failed: {e}");
        }
        self.apply_java_actions(None);
    }

    /// A player's tick on the mirror (the mods' hooks in `ServerPlayer.tick`), from where the
    /// server ticks that player. `host` is the hub, out of `self.lan` meanwhile.
    pub(in crate::game) fn java_player_tick(&mut self, host: &mut LanHost, uuid: u128) {
        if !self.java_phases_on() {
            return;
        }
        let uuid = uuid_string(uuid);
        let ran = self.with_java_world(|| horizon_jvm::events::player_tick(&uuid));
        if let Err(e) = ran {
            log::warn!("Java mods: a player's tick failed: {e}");
        }
        self.apply_java_actions(Some(host));
    }

    /// The server tick's end, after every level ticked (`HorizonServer.tickEnd`): the mods'
    /// entities, menus, screens, `END_SERVER_TICK`; then their entities to the players.
    pub(in crate::game) fn java_tick_end(&mut self) {
        if !std::mem::take(&mut self.mods.java.tick_started)
            || !java_mods_on()
            || self.remote.is_some()
        {
            return;
        }
        let (ended, entities) = self.with_java_world(|| {
            let ended = horizon_jvm::events::tick_end();
            (ended, horizon_jvm::events::entities())
        });
        if let Err(e) = ended {
            log::warn!("Java mods: a tick's end failed: {e}");
        }
        self.apply_java_actions(None);
        match entities {
            Ok(json) => self.send_java_entities(&json),
            Err(e) => log::warn!("Java mods: their entities could not be read: {e}"),
        }
    }
}
