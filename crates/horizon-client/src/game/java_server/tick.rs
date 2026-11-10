//! The Java mods' server tick: who joined and left, what players did, the mods' tick, and the
//! actions they ask back (`events::Action`), applied here.
use super::*;
pub(super) use crate::game::lan::LanHost;
use horizon_client::chunk_map::ChunkMap;
use horizon_client::{command::args::GameMode as Mode, lan::data::component_nbt, text::Text};
/// What Java mods hear of a tick (crate horizon-jvm).
use horizon_jvm::events::Event as JavaEvent;
use horizon_protocol::packets::play::{self, Clientbound as C};
use serde_json::Value;

/// The players the Java mods saw last tick (joins and leaves), and what players did this tick:
/// `ModsState::java`.
#[derive(Default)]
pub(in crate::game) struct JavaServerState {
    pub(super) players: Vec<JavaPlayer>,
    pub(super) events: Vec<ModEvent>,
    /// Blocks Java mods set, by dimension, made before that level's next tick.
    pub(super) block_writes: Vec<(String, [i32; 3], String)>,
    /// The connection a Java mod's level event being played now leaves out (its source
    /// player's: `Action::LevelEvent`'s `except`), for `lan_record_events`.
    pub(super) event_except: Option<u32>,
}

/// A player on this game's server as the Java mods see them.
#[derive(Clone)]
pub(super) struct JavaPlayer {
    pub(super) id: String,
    pub(super) name: String,
    pub(super) x: f64,
    pub(super) y: f64,
    pub(super) z: f64,
    pub(super) dimension: String,
    /// 0 survival, 1 creative, 2 adventure, 3 spectator.
    pub(super) game_mode: i32,
    pub(super) health: f32,
}

/// The connection of each player the Java mods saw last (by UUID, as they name them): whom a
/// Java action that names a player is about, where no hub is at hand.
pub(super) static JAVA_CONNS: std::sync::Mutex<Vec<(String, u32)>> =
    std::sync::Mutex::new(Vec::new());

impl Game<'_> {
    /// The connection the level event being played now leaves out, when a Java mod made it
    /// with a player as its source (vanilla's broadcast leaves its source out).
    pub(in crate::game) fn java_event_except(&self) -> Option<u32> {
        self.mods.java.event_except
    }
}

/// The actions besides breaking, using and random ticks that Java mods patch
/// (`events::routed_actions`: "item_pickup", "experience_orbs"), asked once Fabric runs.
pub(super) static JAVA_ROUTED: std::sync::OnceLock<Vec<String>> = std::sync::OnceLock::new();

/// Whether Java mods patch `action` (an `events::routed_actions` name), so the game sends it to
/// them.
pub(in crate::game) fn java_routed(action: &str) -> bool {
    if JAVA_ROUTED.get().is_none() {
        if !horizon_jvm::events::ready() {
            return false;
        }
        let routed = horizon_jvm::events::routed_actions().unwrap_or_else(|e| {
            log::warn!("Java mods: routed actions: {e}");
            Vec::new()
        });
        if !routed.is_empty() {
            log::info!("Java mods: {} go to the mods", routed.join(", "));
        }
        let _ = JAVA_ROUTED.set(routed);
    }
    JAVA_ROUTED
        .get()
        .is_some_and(|r| r.iter().any(|a| a == action))
}

/// A Java mod's chat line (`Action::Tell`, `Broadcast`): a JSON component, resolved with
/// the game's language (mods' lang files are in the content root) and sent on as it is to
/// players of the server; anything else (an older Java side) as literal text.
pub(super) fn java_chat(text: &str) -> Text {
    match serde_json::from_str::<Value>(text) {
        Ok(v) if v.is_object() || v.is_array() || v.is_string() => {
            horizon_client::packs::component(&v).with_component(v)
        }
        _ => Text::literal(text),
    }
}

/// Whether Java mods run here: the bridge started Fabric with its platform mod.
pub(super) fn java_mods_on() -> bool {
    horizon_jvm::events::enabled()
}

/// `UUID.toString()`.
pub(super) fn uuid_string(u: u128) -> String {
    let h = format!("{u:032x}");
    format!(
        "{}-{}-{}-{}-{}",
        &h[..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..]
    )
}

impl Game<'_> {
    /// The player's profile id: the saved id, else the offline id of the player's name
    /// (upstream dropped its `player_uuid`; the mirror's ids were made with it).
    pub(super) fn player_uuid(&self) -> u128 {
        self.level.player_uuid.map_or_else(
            || horizon_protocol::crypto::offline_uuid(crate::game::chat::PLAYER_NAME),
            |[a, b, c, d]| {
                (u128::from(a as u32) << 96)
                    | (u128::from(b as u32) << 64)
                    | (u128::from(c as u32) << 32)
                    | u128::from(d as u32)
            },
        )
    }

    /// The window's own player's id as `UUID.toString()`.
    pub(super) fn java_local_id(&self) -> String {
        uuid_string(self.player_uuid())
    }

    /// A Java mod's line in the window's chat; not through system_message: the mods must
    /// not hear their own lines. Written by this client, so the Chat option doesn't hide it.
    pub(super) fn add_java_chat_line(&mut self, line: Text) {
        let focused = self.screen == crate::game::Screen::Chat;
        self.ui.chat.add(
            &self.gui.atlas,
            line,
            None,
            horizon_client::chat::ChatSource::Client,
            self.ticks,
            focused,
        );
    }

    /// The players on this game's server: the window's own player
    /// in-frame, then every connection's. `host` is the hub when it is
    /// out of `self.lan`.
    pub(super) fn java_players(&self, host: Option<&LanHost>) -> Vec<JavaPlayer> {
        let mut out = Vec::new();
        if self.options.execution_role.is_client()
            && self.remote.is_none()
            && let Some(p) = &self.player
        {
            out.push(JavaPlayer {
                id: self.java_local_id(),
                name: crate::game::chat::PLAYER_NAME.to_owned(),
                x: p.position.x,
                y: p.position.y,
                z: p.position.z,
                dimension: self.dim.id().to_owned(),
                game_mode: match self.mode {
                    Mode::Survival => 0,
                    Mode::Creative => 1,
                    Mode::Adventure => 2,
                    Mode::Spectator => 3,
                },
                health: p.health.health(),
            });
        }
        let guests = host
            .or(self.lan.as_ref())
            .into_iter()
            .flat_map(|h| &h.hub.guests);
        let conns: Vec<(String, u32)> = guests
            .clone()
            .filter(|g| g.placed)
            .map(|g| (uuid_string(g.uuid), g.conn))
            .collect();
        if !conns.is_empty() {
            *super::JAVA_CONNS.lock().unwrap_or_else(|e| e.into_inner()) = conns;
        }
        out.extend(guests.filter(|g| g.placed).map(|g| JavaPlayer {
            id: uuid_string(g.uuid),
            name: g.name.clone(),
            x: g.position[0],
            y: g.position[1],
            z: g.position[2],
            dimension: g.dimension.clone(),
            game_mode: g.game_mode,
            health: g.health,
        }));
        out
    }

    /// Whether what players do now reaches the Java mods: they run and this game
    /// simulates the world.
    pub(super) fn hears_players(&self) -> bool {
        java_mods_on() && self.remote.is_none()
    }

    /// The tick's end: joins and leaves, the tick's events, then the mods' tick.
    pub(in crate::game) fn tick_java_server(&mut self) {
        super::super::java_profile::tick();
        // Mod block entities their chunks brought: the mods make them (a frozen world's too).
        if java_mods_on() && horizon_jvm::events::ready() {
            self.java_take_loaded_block_entities();
        }
        self.send_java_commands();
        let owner = self.lan.as_ref().and_then(|h| h.hub.singleplayer_owner);
        self.java_device_own_player(owner);
        if !self.hears_players() {
            return;
        }
        let players = self.java_players(None);
        let before = std::mem::take(&mut self.mods.java.players);
        let mut java_events = Vec::new();
        for p in before
            .iter()
            .filter(|p| !players.iter().any(|q| q.id == p.id))
        {
            java_events.push(JavaEvent::Left { uuid: p.id.clone() });
        }
        for p in players
            .iter()
            .filter(|p| !before.iter().any(|q| q.id == p.id))
        {
            java_events.push(JavaEvent::Joined { uuid: p.id.clone() });
        }
        for event in std::mem::take(&mut self.mods.java.events) {
            let who = |w: &Who| {
                let id = match w {
                    Who::Local => self.java_local_id(),
                    Who::Uuid(u) => uuid_string(*u),
                    Who::Conn(c) => {
                        let guests = self.lan.iter().flat_map(|h| &h.hub.guests);
                        let g = guests.into_iter().find(|g| g.conn == *c);
                        g.map_or_else(String::new, |g| uuid_string(g.uuid))
                    }
                };
                players.iter().chain(&before).find(|p| p.id == id).cloned()
            };
            match event {
                ModEvent::Chat(w, text) => {
                    let Some(p) = who(&w) else { continue };
                    java_events.push(JavaEvent::Chat { uuid: p.id, text });
                }
                ModEvent::Block {
                    who: w,
                    pos,
                    state,
                    placed,
                } => {
                    let Some(p) = who(&w) else { continue };
                    java_events.push(JavaEvent::Block {
                        uuid: p.id,
                        position: pos,
                        state,
                        placed,
                    });
                }
            }
        }
        self.tick_java_mods(&players, &java_events);
        self.mods.java.players = players;
    }

    /// Hands the tick to the Java mods (crate horizon-jvm: Fabric API's events), then sends
    /// what they asked: system messages to a player or everyone.
    pub(super) fn tick_java_mods(&mut self, players: &[JavaPlayer], events: &[JavaEvent]) {
        // Teleports Java mods asked for while the hub was out of `self.lan`.
        let pending =
            std::mem::take(&mut *PENDING_TELEPORTS.lock().unwrap_or_else(|e| e.into_inner()));
        for action in pending {
            self.java_teleport(action);
        }
        let hurts = std::mem::take(&mut *PENDING_HURTS.lock().unwrap_or_else(|e| e.into_inner()));
        for action in hurts {
            self.java_hurt(action);
        }
        // A connection's facing, or the window's own player's in-frame.
        let rotation = |id: &str| -> [f32; 2] {
            self.lan
                .iter()
                .flat_map(|h| &h.hub.guests)
                .find(|g| uuid_string(g.uuid) == id)
                .map_or([self.yaw, self.pitch], |g| [g.yaw, g.pitch])
        };
        // A connection's food, or the window's own player's in-frame.
        let food = |id: &str| -> (i32, f32) {
            self.lan
                .iter()
                .flat_map(|h| &h.hub.guests)
                .find(|g| uuid_string(g.uuid) == id)
                .map(|g| (g.food, g.saturation))
                .or_else(|| {
                    self.player
                        .as_ref()
                        .map(|p| (p.health.food.food, p.health.food.saturation))
                })
                .unwrap_or((20, 5.0))
        };
        let players: Vec<horizon_jvm::events::Player> = players
            .iter()
            .map(|p| horizon_jvm::events::Player {
                uuid: p.id.clone(),
                name: p.name.clone(),
                position: [p.x, p.y, p.z],
                yaw: rotation(&p.id)[0],
                pitch: rotation(&p.id)[1],
                dimension: p.dimension.clone(),
                game_mode: p.game_mode,
                health: p.health,
                food: food(&p.id).0,
                saturation: food(&p.id).1,
                // A connection's level (ops, the LAN host's grant); the window's own player
                // in-frame owns its world.
                permission: self
                    .lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == p.id)
                    .map_or(4, |g| g.permission),
                sneaking: self
                    .lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .any(|g| uuid_string(g.uuid) == p.id && g.sneaking),
                // A connection's keys (PlayerInput): a mod's mob it rides steers by them.
                input: self
                    .lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == p.id)
                    .map_or(0, |g| g.input),
                inventory: self
                    .lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == p.id)
                    .and_then(java_inventory),
            })
            .collect();
        // Not `options.dedicated`: single player's server thread sets it too.
        let dedicated = matches!(
            self.options.execution_role,
            crate::game::execution_role::ExecutionRole::DedicatedServer
        );
        // Deaths and respawns, from each player's health since the last tick.
        let mut events = events.to_vec();
        {
            let mut dead = JAVA_DEAD.lock().unwrap_or_else(|e| e.into_inner());
            let dead = dead.get_or_insert_with(Default::default);
            for p in &players {
                // A connection's player is dead once the server's ServerPlayer.die ran for it
                // (guest_die: its message and drops made); the window's own at no health.
                let now = self
                    .lan
                    .iter()
                    .flat_map(|h| &h.hub.guests)
                    .find(|g| uuid_string(g.uuid) == p.uuid)
                    .map_or(p.health <= 0.0, |g| g.dead);
                let was = dead.insert(p.uuid.clone(), now).unwrap_or(false);
                if now && !was {
                    let conn = self
                        .lan
                        .iter()
                        .flat_map(|h| &h.hub.guests)
                        .find(|g| uuid_string(g.uuid) == p.uuid)
                        .map(|g| g.conn);
                    let tracker = match conn {
                        Some(conn) => self.guest_combat.get(&conn),
                        None => Some(&self.combat),
                    };
                    // Noted where the death message is made, else the tracker's last hit.
                    let noted = JAVA_DEATH_CAUSES
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .as_mut()
                        .and_then(|m| {
                            let by_conn = conn.and_then(|c| m.remove(&format!("conn:{c}")));
                            m.remove(&p.uuid).or(by_conn)
                        });
                    let cause = noted.unwrap_or_else(|| {
                        tracker
                            .and_then(|t| t.last_damage_type())
                            .unwrap_or("minecraft:generic")
                            .to_string()
                    });
                    events.push(JavaEvent::Died {
                        uuid: p.uuid.clone(),
                        cause,
                    });
                } else if was && !now {
                    events.push(JavaEvent::Respawned {
                        uuid: p.uuid.clone(),
                    });
                }
            }
        }
        // The Java side's world folder: the Overworld's, whichever level is in place (a
        // player alone in the Nether or a mod's level ticks only that one). Its files (mods'
        // block entities and entities by level, saved data) have always been there; the
        // level in place's folder made the mirror take a level change for a new world (its
        // entities unloaded mid-iteration: a ConcurrentModificationException each tick).
        let (ticks, root) = (
            self.ticks as i64,
            horizon_client::save::dimension_dir(&self.world_root, "minecraft:overworld"),
        );
        // The level in place keeps what Java mods' chunk loaders last asked for, though its
        // streaming was made after they asked (a world loading, a level swapped in).
        let wanted = Game::java_tickets(self.dim.id());
        let held = self.streaming.ticketed();
        if wanted.len() != held.len() || wanted.iter().any(|c| !held.contains(c)) {
            self.streaming.set_ticketed(wanted);
        }
        let (game_time, seed) = (self.world.game_time, self.level.seed);
        // `/tick freeze`: the world stands (Game::tick's `running`), and so do the mods' block
        // entities, scheduled ticks and entities (Level.tickBlockEntities, ServerLevel.tick).
        let running = self
            .level_pass
            .running
            .unwrap_or_else(|| self.clock.world_runs());
        // Horizon's game rules and difficulty, when they changed: the mods' spawner, their
        // mobs' griefing and despawning in peaceful read the mirror's (HorizonServer.gameRules).
        let rules = {
            use horizon_client::gamerules::{RULES, Value};
            static LAST: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());
            let now: String = RULES
                .iter()
                .filter_map(|r| {
                    Some(match self.ui.rules.get(r.name)? {
                        Value::Bool(b) => format!("minecraft:{}={b}", r.name),
                        Value::Int(i) => format!("minecraft:{}={i}", r.name),
                    })
                })
                .chain(std::iter::once(format!(
                    "difficulty={}",
                    self.ui.difficulty
                )))
                .collect::<Vec<_>>()
                .join(";");
            let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
            (*last != now).then(|| {
                *last = now.clone();
                now
            })
        };
        // The entities' snapshot in the same view as the tick: what the tick read of the
        // world (whole sections, HorizonServer.section) serves it too.
        let neighbours = self.java_neighbours_json(&players);
        let items = self.java_items_json(&players);
        // Players' `textures` (the profiles they logged in with) the mods' client hasn't
        // yet: their skins, for what mods build from them (3D skin layers).
        let textures: Vec<(String, String, Option<String>)> = {
            static TOLD: std::sync::Mutex<Option<std::collections::HashMap<String, String>>> =
                std::sync::Mutex::new(None);
            let mut told = TOLD.lock().unwrap_or_else(|e| e.into_inner());
            let told = told.get_or_insert_with(std::collections::HashMap::new);
            self.lan
                .iter()
                .flat_map(|h| &h.hub.guests)
                .filter_map(|g| {
                    let t = g.properties.iter().find(|p| p.name == "textures")?;
                    let uuid = uuid_string(g.uuid);
                    (told.get(&uuid) != Some(&t.value)).then(|| {
                        told.insert(uuid.clone(), t.value.clone());
                        (uuid, t.value.clone(), t.signature.clone())
                    })
                })
                .collect()
        };
        let (ticked, entities) = self.with_java_world(|| {
            for (uuid, value, signature) in &textures {
                if let Err(e) =
                    horizon_jvm::events::profile_textures(uuid, value, signature.as_deref())
                {
                    log::warn!("Java mods: a player's skin wasn't passed: {e}");
                }
            }
            if let Some(rules) = &rules
                && let Err(e) = horizon_jvm::events::game_rules(rules)
            {
                log::warn!("Java mods: game rules not passed: {e}");
            }
            // Horizon's mobs near players, for the mods' mobs to see (HorizonNeighbours).
            if let Err(e) = horizon_jvm::events::horizon_mobs(&neighbours) {
                log::warn!("Java mods: Horizon's mobs not passed: {e}");
            }
            // Horizon's item entities near players, for the mods' blocks (HorizonItems).
            if let Err(e) = horizon_jvm::events::horizon_items(&items) {
                log::warn!("Java mods: Horizon's items not passed: {e}");
            }
            // What the server sent the window's own player, for the mods' client
            // connection (recording mods read it there): before the tick, whose client
            // tick records the player's own moves after them.
            if let Some((uuid, bodies)) = horizon_client::lan::packet_tap::take()
                && let Err(e) = horizon_jvm::events::client_packets(&uuid_string(uuid), &bodies)
            {
                log::warn!("Java mods: the client's packets not passed: {e}");
            }
            let ticked = horizon_jvm::events::game_time(game_time, seed, running).and_then(|()| {
                horizon_jvm::events::server_tick(ticks, dedicated, &root, &players, &events)
            });
            let entities = java_mods_on().then(horizon_jvm::events::entities);
            (ticked, entities)
        });
        if let Err(e) = ticked {
            log::warn!("Java mods: a tick failed: {e}");
        }
        self.apply_java_actions(None);
        match entities {
            Some(Ok(json)) => self.send_java_entities(&json),
            Some(Err(e)) => log::warn!("Java mods: their entities could not be read: {e}"),
            None => {}
        }
    }

    /// Sends what the Java mods asked since the last call: system messages to a player or
    /// everyone. `host` is the hub when it is out of `self.lan`.
    /// Each player's game is told which keys Java mods bind (game/java_screen.rs's KEYS),
    /// so it sends them while playing; again when they change (mods that register theirs
    /// once the client runs: JourneyMap's map key), read every 100 calls.
    pub(super) fn send_java_keys(host: &mut LanHost) {
        #[derive(Default)]
        struct Told {
            keys: Option<Vec<i32>>,
            /// The mods' key bindings for the players' Controls (KEY_MAPPINGS' JSON).
            mappings: Option<String>,
            calls: u32,
            /// The players' games told the keys as they are, and those greeted at all.
            told: std::collections::HashSet<u32>,
            greeted: std::collections::HashSet<u32>,
        }
        static TOLD: std::sync::Mutex<Option<Told>> = std::sync::Mutex::new(None);
        let mut state = TOLD.lock().unwrap_or_else(|e| e.into_inner());
        let state = state.get_or_insert_with(Default::default);
        let placed: Vec<u32> = host
            .hub
            .guests
            .iter()
            .filter(|g| g.placed)
            .map(|g| g.conn)
            .collect();
        if placed.is_empty() {
            return;
        }
        state.calls = state.calls.wrapping_add(1);
        if state.keys.is_none() || state.calls.is_multiple_of(100) {
            let keys = horizon_jvm::events::bound_keys().unwrap_or_else(|e| {
                log::warn!("Java mods: their key bindings can't be read: {e}");
                Vec::new()
            });
            if state.keys.as_ref() != Some(&keys) {
                state.keys = Some(keys);
                state.told.clear();
            }
            let mappings = horizon_jvm::events::key_mappings().unwrap_or_else(|e| {
                log::warn!("Java mods: their key bindings can't be listed: {e}");
                "[]".into()
            });
            if state.mappings.as_ref() != Some(&mappings) {
                state.mappings = Some(mappings);
                state.told.clear();
            }
        }
        let keys = state.keys.clone().unwrap_or_default();
        let mappings = state.mappings.clone().unwrap_or_default();
        // The Java mods with a settings screen, for the players' Mods pages (read once:
        // the mods' entrypoints don't change while the game runs).
        static CONFIG_MODS: std::sync::OnceLock<Vec<(String, String)>> = std::sync::OnceLock::new();
        let config_mods = CONFIG_MODS.get_or_init(|| {
            horizon_jvm::events::config_mods().unwrap_or_else(|e| {
                log::warn!("Java mods: their settings screens can't be listed: {e}");
                Vec::new()
            })
        });
        // Every Java mod's id: a joining game runs the client half of those it has and
        // this server doesn't (game/java_device.rs).
        static MOD_IDS: std::sync::OnceLock<String> = std::sync::OnceLock::new();
        let mod_ids = MOD_IDS.get_or_init(|| {
            horizon_jvm::device::mod_ids().unwrap_or_else(|e| {
                log::warn!("Java mods: their ids can't be listed: {e}");
                String::new()
            })
        });
        for conn in placed {
            if state.greeted.insert(conn) {
                if !mod_ids.is_empty() {
                    host.hub.send_to(
                        conn,
                        &C::CustomPayload(
                            horizon_protocol::packets::configuration::CustomPayload {
                                channel: horizon_jvm::device::SERVER_MODS_CHANNEL.into(),
                                data: mod_ids.clone().into_bytes(),
                            },
                        ),
                    );
                }
                // The inventory overlay is there (REI's item list): its first, empty, frame.
                Self::send_java_overlay_frame(host, conn, "[]");
                if !config_mods.is_empty() {
                    host.hub.send_to(
                        conn,
                        &C::CustomPayload(
                            horizon_protocol::packets::configuration::CustomPayload {
                                channel: super::super::java_screen::CONFIG_MODS.into(),
                                data: serde_json::to_vec(config_mods).unwrap_or_default(),
                            },
                        ),
                    );
                }
            }
            if !state.told.insert(conn) {
                continue;
            }
            if !mappings.is_empty() && mappings != "[]" {
                host.hub.send_to(
                    conn,
                    &C::CustomPayload(horizon_protocol::packets::configuration::CustomPayload {
                        channel: super::super::java_screen::KEY_MAPPINGS.into(),
                        data: mappings.clone().into_bytes(),
                    }),
                );
            }
            if keys.is_empty() {
                continue;
            }
            host.hub.send_to(
                conn,
                &C::CustomPayload(horizon_protocol::packets::configuration::CustomPayload {
                    channel: super::super::java_screen::KEYS.into(),
                    data: serde_json::to_vec(&keys).unwrap_or_default(),
                }),
            );
        }
    }

    pub(super) fn apply_java_actions(&mut self, mut host: Option<&mut LanHost>) {
        if let Some(h) = host.as_deref_mut() {
            Self::send_java_keys(h);
        } else if let Some(h) = self.lan.as_mut() {
            Self::send_java_keys(h);
        }
        let mut wrote = false;
        for action in horizon_jvm::events::take_actions() {
            wrote |= matches!(
                action,
                horizon_jvm::events::Action::SetBlock { .. }
                    | horizon_jvm::events::Action::DropItem { .. }
                    | horizon_jvm::events::Action::SpawnEntity { .. }
            );
            match action {
                horizon_jvm::events::Action::Tell { uuid, text } => {
                    self.tell_server_player(host.as_deref_mut(), &uuid, java_chat(&text));
                }
                horizon_jvm::events::Action::Overlay { uuid, text } => {
                    self.overlay_server_player(host.as_deref_mut(), &uuid, java_chat(&text));
                }
                horizon_jvm::events::Action::GameRule { id, value } => {
                    // GameRules.set from Java: Horizon's rule takes the value (as
                    // `/gamerule` sets it), so the world plays by the pack's rules.
                    use horizon_client::gamerules::{self, Value};
                    if let Some(rule) = gamerules::rule(&id) {
                        let parsed = match rule.default {
                            Value::Bool(_) => value.parse().ok().map(Value::Bool),
                            Value::Int(_) => value.parse().ok().map(Value::Int),
                        };
                        if let Some(v) = parsed {
                            self.ui.rules.set(rule.name, v);
                            self.sync_rules();
                        }
                    }
                }
                horizon_jvm::events::Action::WorldSpawn {
                    dimension,
                    position,
                    yaw,
                    pitch,
                } => {
                    // LevelData.setRespawnData, as /setworldspawn sets it (the
                    // overworld's: Horizon's world spawn is the overworld's).
                    if dimension == "minecraft:overworld" {
                        let old = self.level.world_spawn.replace(position);
                        self.level.world_spawn_angle = [yaw, pitch];
                        // Players respawning at the world spawn follow it (as
                        // /setworldspawn's `spawn`), and so do the ones not placed yet.
                        let floor = |s: horizon_core::geometry::Vec3| {
                            [s.x.floor() as i32, s.y.floor() as i32, s.z.floor() as i32]
                        };
                        if self.spawn.map(floor) == old || self.spawn.is_none() {
                            let [x, y, z] = position;
                            self.spawn = Some(horizon_core::geometry::Vec3::new(
                                f64::from(x) + 0.5,
                                f64::from(y),
                                f64::from(z) + 0.5,
                            ));
                            self.level.spawn = Some([f64::from(x), f64::from(y), f64::from(z)]);
                        }
                    }
                }
                teleport @ horizon_jvm::events::Action::Teleport { .. } => {
                    if host.is_some() {
                        // The hub is out of `self.lan` (a connection's command): the
                        // player's /tp needs it, so the next tick does it.
                        PENDING_TELEPORTS
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .push(teleport);
                    } else {
                        self.java_teleport(teleport);
                    }
                }
                horizon_jvm::events::Action::Abilities {
                    uuid,
                    game_mode,
                    flags,
                    flying_speed,
                    walking_speed,
                } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let to = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn);
                    if let Some(conn) = to {
                        host.hub.set_mod_abilities(
                            conn,
                            horizon_client::lan::guest::ModAbilities {
                                game_mode,
                                flags,
                                flying_speed,
                                walking_speed,
                            },
                        );
                    }
                }
                horizon_jvm::events::Action::PlayerMotion { uuid, velocity } => {
                    // As vanilla's server tells a client its player's motion
                    // (ClientboundSetEntityMotionPacket): the client moves it from there.
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    if let Some(g) = host.hub.guests.iter().find(|g| uuid_string(g.uuid) == uuid) {
                        g.send(&C::SetEntityMotion(play::SetEntityMotion {
                            id: g.entity_id,
                            velocity,
                        }));
                    }
                }
                horizon_jvm::events::Action::TabName { uuid, json } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let Some(id) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.uuid)
                    else {
                        continue;
                    };
                    let name = json
                        .and_then(|j| serde_json::from_str::<serde_json::Value>(&j).ok())
                        .map(|v| {
                            Self::uuid_int_arrays(horizon_client::lan::data::component_nbt(&v))
                        });
                    host.hub.set_tab_name(id, name);
                }
                horizon_jvm::events::Action::OpenMenu { uuid, menu, title } => {
                    if let Some(h) = host.as_deref_mut() {
                        self.java_open_menu(h, &uuid, &menu, title.as_deref());
                    } else if let Some(mut lan) = self.lan.take() {
                        self.java_open_menu(&mut lan, &uuid, &menu, title.as_deref());
                        self.lan = Some(lan);
                    }
                }
                horizon_jvm::events::Action::SetBlockEntity {
                    dimension,
                    position,
                    snbt,
                } => self.set_java_block_entity(&dimension, position, &snbt),
                horizon_jvm::events::Action::ScreenFrame { uuid, frame } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let window = JAVA_WINDOWS
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get(&uuid)
                        .copied();
                    if let Some((conn, window)) = window {
                        Self::send_java_screen_frame(host, conn, window, &frame);
                    }
                }
                horizon_jvm::events::Action::ItemsChanged { json } => {
                    self.java_items_changed(&json);
                }
                horizon_jvm::events::Action::OverlayFrame { uuid, frame } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    if let Some(conn) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn)
                    {
                        Self::send_java_overlay_frame(host, conn, &frame);
                    }
                }
                horizon_jvm::events::Action::HudFrame { uuid, frame } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    if let Some(conn) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn)
                    {
                        Self::send_java_hud_frame(host, conn, &frame);
                    }
                }
                horizon_jvm::events::Action::View { uuid, scale } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    if let Some(conn) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn)
                    {
                        host.hub.send_to(
                            conn,
                            &C::CustomPayload(
                                horizon_protocol::packets::configuration::CustomPayload {
                                    channel: super::super::java_screen::VIEW.into(),
                                    data: serde_json::to_vec(&scale).unwrap_or_default(),
                                },
                            ),
                        );
                    }
                }
                horizon_jvm::events::Action::ClientScreen { uuid, open } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let Some(conn) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn)
                    else {
                        continue;
                    };
                    let mut windows = JAVA_WINDOWS.lock().unwrap_or_else(|e| e.into_inner());
                    let mut under = JAVA_UNDER.lock().unwrap_or_else(|e| e.into_inner());
                    let client = (conn, super::super::java_screen::CLIENT_WINDOW);
                    if open {
                        // Its frames and input go by the client-only screen's window; a
                        // menu's window under it waits.
                        if let Some(menu) = windows.insert(uuid.clone(), client)
                            && menu != client
                        {
                            under.insert(uuid, menu);
                        }
                    } else if windows.get(&uuid) == Some(&client) {
                        windows.remove(&uuid);
                        Self::send_java_screen_close(host, conn);
                        // Back to the menu's screen, if its menu is still open (the
                        // menu's next frame shows it in place of the stand-in).
                        if let Some(menu) = under.remove(&uuid) {
                            windows.insert(uuid, menu);
                        }
                    }
                }
                horizon_jvm::events::Action::Food {
                    uuid,
                    food,
                    saturation,
                } => {
                    let guest = host.as_deref_mut().or(self.lan.as_mut()).and_then(|h| {
                        h.hub
                            .guests
                            .iter_mut()
                            .find(|g| uuid_string(g.uuid) == uuid)
                    });
                    if let Some(g) = guest {
                        g.food = food.clamp(0, 20);
                        g.saturation = saturation.max(0.0);
                    } else if uuid == self.java_local_id()
                        && let Some(p) = self.player.as_mut()
                    {
                        p.health.food.food = food.clamp(0, 20);
                        p.health.food.saturation = saturation.max(0.0);
                    }
                }
                horizon_jvm::events::Action::MenuContent {
                    uuid,
                    open,
                    items,
                    carried,
                } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let Some(conn) = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn)
                    else {
                        continue;
                    };
                    let mut windows = JAVA_WINDOWS.lock().unwrap_or_else(|e| e.into_inner());
                    let Some(items) = items else {
                        if let Some((c, w)) = windows.remove(&uuid)
                            && w != super::super::java_screen::CLIENT_WINDOW
                        {
                            host.hub.close_window(c, w);
                        }
                        // A menu under a client-only screen closed too.
                        if let Some((c, w)) = JAVA_UNDER
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .remove(&uuid)
                        {
                            host.hub.close_window(c, w);
                        }
                        continue;
                    };
                    if let Some((menu, title)) = open {
                        let title = title
                            .and_then(|j| serde_json::from_str::<Value>(&j).ok())
                            .unwrap_or(Value::Null);
                        let title = horizon_client::lan::data::component_nbt(&title);
                        match host.hub.open_window(conn, &menu, title) {
                            Some(w) => {
                                windows.insert(uuid.clone(), (conn, w));
                            }
                            None => {
                                log::warn!("Java mods: a menu of type {menu} can't be shown")
                            }
                        }
                    }
                    let Some(&(c, w)) = windows.get(&uuid) else {
                        continue;
                    };
                    // A client-only screen over the menu: no window to fill.
                    if w == super::super::java_screen::CLIENT_WINDOW {
                        continue;
                    }
                    let item = |s: &Option<String>| {
                        let snbt = s.as_deref()?;
                        match horizon_world::nbt::parse_snbt(snbt) {
                            Ok((tag, _)) => horizon_items::item_nbt::item_from_nbt(&tag),
                            Err(e) => {
                                log::warn!("Java mods: a mod's item {snbt}: {e}");
                                None
                            }
                        }
                    };
                    let stacks = items.iter().map(item).collect();
                    let carried = item(&carried);
                    host.hub.send_window_content(c, w, stacks, carried);
                }
                horizon_jvm::events::Action::SetBlock {
                    dimension,
                    position: [x, y, z],
                    state,
                } => {
                    let queued = &mut self.mods.java.block_writes;
                    queued.push((dimension, [x, y, z], state));
                    if queued.len() > MAX_QUEUED_WRITES {
                        let over = queued.len() - MAX_QUEUED_WRITES;
                        queued.drain(..over);
                        log::warn!(
                            "Java mods: more blocks set than wait for their level: {over} dropped"
                        );
                    }
                }
                drop @ (horizon_jvm::events::Action::DropItem { .. }
                | horizon_jvm::events::Action::LevelEvent { .. }
                | horizon_jvm::events::Action::SpawnEntity { .. }) => {
                    let (horizon_jvm::events::Action::DropItem { dimension, .. }
                    | horizon_jvm::events::Action::LevelEvent { dimension, .. }
                    | horizon_jvm::events::Action::SpawnEntity { dimension, .. }) = &drop
                    else {
                        continue;
                    };
                    let mut drops = JAVA_DROPS.lock().unwrap_or_else(|e| e.into_inner());
                    if drops.len() < 4096 {
                        drops.push((dimension.clone(), drop));
                    }
                }
                hurt @ (horizon_jvm::events::Action::HurtPlayer { .. }
                | horizon_jvm::events::Action::HurtMob { .. }) => {
                    PENDING_HURTS
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .push(hurt);
                }
                horizon_jvm::events::Action::Packet { uuid, data } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    // The packet's id (a VarInt), then its body, as vanilla encoded it.
                    let (mut id, mut shift, mut at) = (0i32, 0, 0);
                    while let Some(&b) = data.get(at) {
                        id |= i32::from(b & 0x7F) << shift;
                        at += 1;
                        shift += 7;
                        if b & 0x80 == 0 || shift >= 35 {
                            break;
                        }
                    }
                    let conn = host
                        .hub
                        .guests
                        .iter()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        .map(|g| g.conn);
                    if let Some(conn) = conn {
                        host.hub.send_to(conn, &C::Unknown(id, data[at..].to_vec()));
                    }
                }
                horizon_jvm::events::Action::SetSlot { uuid, slot, item } => {
                    let Some(host) = host.as_deref_mut().or(self.lan.as_mut()) else {
                        continue;
                    };
                    let stack = match &item {
                        Some(snbt) => match horizon_world::nbt::parse_snbt(snbt) {
                            Ok((tag, _)) => horizon_items::item_nbt::item_from_nbt(&tag),
                            Err(e) => {
                                log::warn!("Java mods: a mod's item {snbt}: {e}");
                                continue;
                            }
                        },
                        None => None,
                    };
                    if let Some(g) = host
                        .hub
                        .guests
                        .iter_mut()
                        .find(|g| uuid_string(g.uuid) == uuid)
                        && let Some(at) = guest_slot(g, slot)
                    {
                        // The hub sends the changed slot with the tick's other changes.
                        *at = stack;
                    }
                }
                horizon_jvm::events::Action::ChunkTickets { dimension, chunks } => {
                    super::JAVA_TICKETS
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get_or_insert_with(Default::default)
                        .insert(dimension.clone(), chunks.clone());
                    let dim = super::super::Dim::from_id(&dimension);
                    if dim == self.dim {
                        self.streaming.set_ticketed(chunks);
                    } else if let Some(level) = self.levels.parked_mut(dim) {
                        level.set_ticketed(chunks);
                    }
                }
                horizon_jvm::events::Action::Broadcast { text } => {
                    let line = java_chat(&text);
                    let text = line.resolved().plain();
                    if self.options.dedicated.is_some() {
                        super::super::dedicated::log("INFO", &text);
                    } else {
                        log::info!("{text}");
                    }
                    for p in self.java_players(host.as_deref()) {
                        self.tell_server_player(host.as_deref_mut(), &p.id, line.clone());
                    }
                }
            }
        }
        // The level in place gets the mods' blocks and drops now; the others' wait.
        if wrote {
            self.apply_java_level_writes();
        }
    }

    /// UUIDs in a component (a hover's `show_entity`) as vanilla's NBT has them: int arrays
    /// (UUIDUtil.CODEC), not the lists of ints JSON gives.
    pub(super) fn uuid_int_arrays(tag: horizon_world::nbt::Tag) -> horizon_world::nbt::Tag {
        use horizon_world::nbt::Tag;
        match tag {
            Tag::Compound(entries) => Tag::Compound(
                entries
                    .into_iter()
                    .map(|(k, v)| {
                        let v = match v {
                            Tag::List(items)
                                if k.to_string().is_ok_and(|k| k == "uuid") && items.len() == 4 =>
                            {
                                let ints: Option<Vec<i32>> = items
                                    .iter()
                                    .map(|t| match t {
                                        Tag::Int(i) => Some(*i),
                                        _ => None,
                                    })
                                    .collect();
                                ints.map_or(Tag::List(items), Tag::IntArray)
                            }
                            other => Self::uuid_int_arrays(other),
                        };
                        (k, v)
                    })
                    .collect(),
            ),
            Tag::List(items) => Tag::List(items.into_iter().map(Self::uuid_int_arrays).collect()),
            other => other,
        }
    }

    /// Runs `f`, a call into Java, with the levels as what Java mods read of the world.
    #[track_caller]
    pub(in crate::game) fn with_java_world<R>(&self, f: impl FnOnce() -> R) -> R {
        let _timed = super::super::java_profile::Timed::here();
        let registry = self.pack.assets.registry;
        let mut dims: Vec<(&'static str, &ChunkMap)> = vec![(self.dim.id(), &self.world.chunks)];
        for dim in self.levels.parked_dims() {
            if let Some(world) = self.levels.parked_world(dim) {
                dims.push((dim.id(), &world.chunks));
            }
        }
        let entities = &self.world.block_entities;
        horizon_jvm::world::with_blocks(
            &JavaBlocks {
                registry,
                dims,
                entities,
                biomes: self.res.biomes(),
            },
            f,
        )
    }

    /// The integrated world closes: the mods' client connection ends as a client's does
    /// when its server stops (recording mods save their recording then).
    pub(in crate::game) fn java_world_closing(&mut self) {
        if java_mods_on()
            && let Err(e) = horizon_jvm::events::client_closed()
        {
            log::warn!("Java mods: the client's connection didn't close: {e}");
        }
    }

    /// The world is saved (autosave, /save-all, closing): the Java mods' files beside it
    /// too (their entities, block entities, saved data), not only every 10 s.
    pub(in crate::game) fn save_java_mods(&mut self) {
        if java_mods_on()
            && let Err(e) = horizon_jvm::events::save_all()
        {
            log::warn!("Java mods: saving failed: {e}");
        }
    }

    /// A line for one player's action bar (`SystemChat` with `overlay`); the window's own
    /// player in-frame gets it in chat.
    pub(super) fn overlay_server_player(
        &mut self,
        host: Option<&mut LanHost>,
        id: &str,
        line: Text,
    ) {
        let local = self.options.execution_role.is_client()
            && self.remote.is_none()
            && self.player.is_some();
        if local && self.java_local_id() == id {
            self.add_java_chat_line(line);
            return;
        }
        let Some(host) = host.or(self.lan.as_mut()) else {
            return;
        };
        let to = host
            .hub
            .guests
            .iter()
            .find(|g| g.placed && uuid_string(g.uuid) == id);
        if let Some(conn) = to.map(|g| g.conn) {
            host.hub.send_to(
                conn,
                &C::SystemChat(play::SystemChat {
                    content: component_nbt(&line.component()),
                    overlay: true,
                }),
            );
        }
    }

    /// Sends a Java mod's line to the player with this id: the window's own chat for the
    /// local player, a system message for a connection's. `host` is the hub when it is out
    /// of `self.lan`.
    pub(super) fn tell_server_player(&mut self, host: Option<&mut LanHost>, id: &str, line: Text) {
        let local = self.options.execution_role.is_client()
            && self.remote.is_none()
            && self.player.is_some();
        if local && self.java_local_id() == id {
            self.add_java_chat_line(line);
            return;
        }
        let Some(host) = host.or(self.lan.as_mut()) else {
            return;
        };
        let to = host
            .hub
            .guests
            .iter()
            .find(|g| g.placed && uuid_string(g.uuid) == id);
        if let Some(conn) = to.map(|g| g.conn) {
            host.hub.send_to(
                conn,
                &C::SystemChat(play::SystemChat {
                    content: component_nbt(&line.component()),
                    overlay: false,
                }),
            );
        }
    }
}
