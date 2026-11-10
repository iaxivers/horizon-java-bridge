//! Horizon's game events for Java mods, and what mods ask back.
//!
//! Once per server tick the game hands [`server_tick`] the players on the server and what they
//! did since the last tick. The bridge's platform mod (`java/platform/`, the builtin mod
//! `horizon`) mirrors them into vanilla objects (a `MinecraftServer`, `ServerLevel`s,
//! `ServerPlayer`s made without their constructors) and fires Fabric API's events with them:
//! server start and tick, level tick, connection init, join and disconnect, entity load, chat,
//! block break.
//!
//! What mods ask for comes back as [`Action`]s through the natives (`Natives.tell`,
//! `Natives.broadcast`), queued here and taken by the game with [`take_actions`] after the
//! call, on the server thread, where it applies them as server mods' output.

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::{JNIEnv, sys};
use std::sync::{Mutex, OnceLock};

/// A player on the server, as the game sees it this tick.
#[derive(Clone, Debug, PartialEq)]
pub struct Player {
    /// `UUID.toString()`.
    pub uuid: String,
    pub name: String,
    pub position: [f64; 3],
    pub yaw: f32,
    pub pitch: f32,
    /// `minecraft:overworld`, ...
    pub dimension: String,
    /// Vanilla's game mode ids: 0 survival, 1 creative, 2 adventure, 3 spectator.
    pub game_mode: i32,
    pub health: f32,
    /// `FoodData`'s food level and saturation.
    pub food: i32,
    pub saturation: f32,
    /// Vanilla's permission level, 0 (everyone) to 4 (owner): what the player's commands may do.
    pub permission: u8,
    /// The sneak key is held (`Player.isShiftKeyDown`: a mod's sneak-click, a wrench's mode).
    pub sneaking: bool,
    /// The movement keys held (vanilla's PlayerInput flags: forward 1, backward 2, left 4,
    /// right 8, jump 16, shift 32, sprint 64): a mod's mob it rides steers by them.
    pub input: u8,
    /// The inventory, when it changed since the last tick (None: as it was): the
    /// [`INVENTORY_SLOTS`] slots in vanilla's `Inventory` order (hotbar 0-8, the rest 9-35,
    /// feet, legs, chest, head 36-39, off hand 40), each an item stack as vanilla's
    /// `ItemStack.CODEC` writes it in SNBT, `""` for an empty slot.
    pub inventory: Option<Vec<String>>,
}

/// The slots of a player's inventory the mods see (vanilla's `Inventory`).
pub const INVENTORY_SLOTS: usize = 41;

/// Something a player did since the last tick.
#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    Joined {
        uuid: String,
    },
    Left {
        uuid: String,
    },
    Chat {
        uuid: String,
        text: String,
    },
    /// A block a player broke or placed, with its state as `minecraft:oak_log[axis=y]` (for a
    /// break, the state before).
    Block {
        uuid: String,
        position: [i32; 3],
        state: String,
        placed: bool,
    },
    /// A player died (its health reached 0): `cause` is the damage type that killed it
    /// (`minecraft:fall`, ...). The game has dropped what it drops by then.
    Died {
        uuid: String,
        cause: String,
    },
    /// A dead player respawned.
    Respawned {
        uuid: String,
    },
}

/// What a mod asked of the game.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// `ServerPlayer.sendSystemMessage`: a line to one player, as a JSON component (the
    /// game resolves its translations: Java's server side has vanilla's English only).
    Tell { uuid: String, text: String },
    /// `ServerPlayer.sendSystemMessage(_, true)`, a client's `sendOverlayMessage`: a line for one
    /// player's action bar, a JSON component as `Tell`'s.
    Overlay { uuid: String, text: String },
    /// `PlayerList.broadcastSystemMessage`: a line to every player, and the server log; a
    /// JSON component, as `Tell`'s.
    Broadcast { text: String },
    /// Mods' chunk tickets (chunk loaders, `setChunkForced`) in a dimension: every chunk they
    /// keep loaded and ticking, as forced chunks are. The whole set, sent when it changes.
    ChunkTickets {
        dimension: String,
        chunks: Vec<(i32, i32)>,
    },
    /// `MinecraftServer.onGameRuleChanged` (a mod's or a data pack's `/gamerule`): the rule's
    /// id and its value as vanilla serializes it (`true`, `6`).
    GameRule { id: String, value: String },
    /// `ServerLevel.setRespawnData` (a mod's or a data pack's `/setworldspawn`): the world's
    /// spawn, where players without a spawn point of their own respawn.
    WorldSpawn {
        dimension: String,
        position: [i32; 3],
        yaw: f32,
        pitch: f32,
    },
    /// A mod's block entity set a player's motion (Create's fan blowing, its belt carrying):
    /// the player's client is to move at `velocity` (vanilla's ClientboundSetEntityMotionPacket).
    PlayerMotion { uuid: String, velocity: [f64; 3] },
    /// `ServerPlayer.teleport` and the connection's: the player is to be moved there.
    Teleport {
        uuid: String,
        /// `minecraft:overworld`, ...
        dimension: String,
        position: [f64; 3],
        yaw: f32,
        pitch: f32,
    },
    /// `ServerPlayer.onUpdateAbilities`: the player's abilities (`/fly`, `/invuln`), as
    /// vanilla computed them in that game mode.
    Abilities {
        uuid: String,
        /// The game mode the Java side had the player in (`GameType.getId`).
        game_mode: i32,
        /// invulnerable 1, flying 2, may fly 4, instabuild 8.
        flags: i8,
        flying_speed: f32,
        walking_speed: f32,
    },
    /// ClientboundPlayerInfoUpdatePacket's UPDATE_DISPLAY_NAME: the name a player shows in
    /// the tab list (`ServerPlayer.getTabListDisplayName`), as a JSON text component; None
    /// for the player's own name.
    TabName { uuid: String, json: Option<String> },
    /// `ServerPlayer.openMenu`: a mod opened a menu for the player (`/workbench`). Horizon
    /// opens its own menu of that type, when it has one.
    OpenMenu {
        uuid: String,
        /// The `MenuType`'s id, `minecraft:crafting`, ...
        menu: String,
        /// The title, a JSON text component.
        title: Option<String>,
    },
    /// A mod's menu the player has open, which Horizon shows and Java runs (a cabinet's):
    /// `open` (the drawn menu type and title) when it opens, its slots' items as SNBT and the
    /// carried one; `items` None when it closed.
    MenuContent {
        uuid: String,
        open: Option<(String, Option<String>)>,
        items: Option<Vec<Option<String>>>,
        carried: Option<String>,
    },
    /// A mod changed a vanilla block's block entity (a storage mod took from a chest): it is
    /// now this, as vanilla saves it (`saveWithFullMetadata`), in SNBT.
    SetBlockEntity {
        dimension: String,
        position: [i32; 3],
        snbt: String,
    },
    /// A frame of the player's open mod screen, drawn by the mod's own client code in the
    /// headless client: its operations as JSON (`HorizonGraphics`), for the player's game to draw.
    ScreenFrame { uuid: String, frame: String },
    /// A player's client-only screen (`Gui.setScreen` with no menu: a mod's settings, a guide
    /// book, Nature's Compass's list) opened or closed: their game shows it with no menu's
    /// window under it, its frames as a menu screen's.
    ClientScreen { uuid: String, open: bool },
    /// What mods draw on a player's HUD (Jade, AppleSkin), drawn by their client code in the
    /// headless client each tick: operations as a screen frame's, sent when they change.
    HudFrame { uuid: String, frame: String },
    /// A player's field of view as mods make it (`Camera.calculateFov`'s hooks: a zoom mod),
    /// over vanilla's: the scale their game's camera takes (1: none), sent when it changes.
    View { uuid: String, scale: f32 },
    /// What mods draw over a player's inventory (REI's item list), drawn in the headless
    /// client's inventory screen while the player's is open: sent when it changes.
    OverlayFrame { uuid: String, frame: String },
    /// What the mods did this tick to Horizon's item entities they were shown
    /// (`HorizonItems`: a depot or crushing wheel took one in, a fan pushed or processed it):
    /// `{"changes": [{"d": dimension, "id": n, "removed": bool, "stack": snbt|null,
    /// "pos": [x, y, z]|null, "vel": [x, y, z]|null}], "held": [id, ...]}`; the items a mod's
    /// block holds, which the mirror moves (Horizon leaves their tick), until the next report.
    ItemsChanged { json: String },
    /// A mod fed (or starved) a player: its `FoodData` now (a pie's slice).
    Food {
        uuid: String,
        food: i32,
        saturation: f32,
    },
    /// `Level.setBlock` on a mirrored level: the block is to be that state (`/setblock`).
    SetBlock {
        dimension: String,
        position: [i32; 3],
        /// `minecraft:oak_log[axis=y]`.
        state: String,
    },
    /// `ServerLevel.addFreshEntity` of an item (a block's drops): an item entity there.
    DropItem {
        dimension: String,
        position: [f64; 3],
        velocity: [f64; 3],
        /// `minecraft:oak_log`.
        item: String,
        count: i32,
    },
    /// A vanilla entity a mod's code added to a mirrored level (Carry On putting a carried
    /// mob down): vanilla's save of it (SNBT, with its `id`), for the game to load as its own.
    SpawnEntity { dimension: String, snbt: String },
    /// `ServerLevel.levelEvent`: a level event there (`LevelEvent`, 2001 a block's break
    /// particles and sound), as Horizon's level events go.
    LevelEvent {
        dimension: String,
        id: i32,
        position: [i32; 3],
        data: i32,
        /// For the events whose data is a block state (2001): that state, by name.
        state: Option<String>,
        /// The player it leaves out (by UUID): its source, whose own game made the effect
        /// (vanilla's `levelEvent(player, ...)` broadcast leaves it out).
        except: Option<String>,
    },
    /// A mod changed slot `slot` of a player's inventory (vanilla's `Inventory` order, as in
    /// [`Player::inventory`]): the item stack in SNBT, None for empty.
    SetSlot {
        uuid: String,
        slot: usize,
        item: Option<String>,
    },
    /// A clientbound packet a mod sent the player, to send as it is (a mod entity's sound,
    /// particles): vanilla's encoding, its id (a VarInt) then its body.
    Packet { uuid: String, data: Vec<u8> },
    /// Java hurt a Horizon player (a mod's mob's bite): the damage type, the amount and where
    /// it came from (none: no direction).
    HurtPlayer {
        uuid: String,
        damage_type: String,
        amount: f32,
        from: Option<[f64; 3]>,
        /// The attacker's name (the death message's killer).
        by: Option<String>,
    },
    /// A mod's mob hurt one of Horizon's (its shadow, HorizonNeighbours): Horizon's mob `id` of
    /// `dimension`, the damage type, the amount, where it came from, the mods' attacker's id.
    HurtMob {
        dimension: String,
        id: i32,
        damage_type: String,
        amount: f32,
        from: Option<[f64; 3]>,
        by: Option<i32>,
        /// The mod cleared the shadow's invulnerable ticks before the hurt (TACZ's bullets,
        /// for fire faster than vanilla's 10 ticks): Horizon's mob's go too.
        reset_invulnerable: bool,
    },
}

static ACTIONS: Mutex<Vec<Action>> = Mutex::new(Vec::new());
/// At most this many actions wait; a mod flooding them loses the oldest.
const MAX_ACTIONS: usize = 4096;
/// `dev.horizonlab.platform.HorizonServer`, through Fabric's class loader.
static SERVER_CLASS: OnceLock<Option<GlobalRef>> = OnceLock::new();

pub(crate) fn push(action: Action) {
    let mut actions = ACTIONS.lock().unwrap_or_else(|e| e.into_inner());
    if actions.len() >= MAX_ACTIONS {
        actions.remove(0);
    }
    actions.push(action);
}

/// The actions mods asked for since the last call, oldest first.
/// Queues an action again (one the game took but leaves for later).
pub fn push_action(action: Action) {
    push(action);
}

pub fn take_actions() -> Vec<Action> {
    std::mem::take(&mut *ACTIONS.lock().unwrap_or_else(|e| e.into_inner()))
}

/// Whether the platform mod runs: Fabric started and found `HorizonServer`.
pub fn enabled() -> bool {
    crate::get().is_some_and(|b| b.fabric()) && server_class().is_some()
}

fn server_class() -> Option<&'static GlobalRef> {
    SERVER_CLASS
        .get_or_init(|| {
            let bridge = crate::get()?;
            let mut env = bridge.vm().attach_current_thread_permanently().ok()?;
            match bridge.find_class(&mut env, "dev/horizonlab/platform/HorizonServer") {
                Ok(class) => env.new_global_ref(class).ok(),
                Err(_) => {
                    let _ = env.exception_clear();
                    log::warn!("Java mods: no platform mod, so no game events for mods");
                    None
                }
            }
        })
        .as_ref()
}

fn optional_strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[Option<&str>],
) -> jni::errors::Result<JObject<'local>> {
    let array = env.new_object_array(
        items.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, item) in items.iter().enumerate() {
        if let Some(item) = item {
            let s = env.new_string(item)?;
            env.set_object_array_element(&array, i as sys::jsize, &s)?;
            env.delete_local_ref(s)?;
        }
    }
    Ok(array.into())
}

fn strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[&str],
) -> jni::errors::Result<JObject<'local>> {
    let array = env.new_object_array(
        items.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, item) in items.iter().enumerate() {
        let s = env.new_string(item)?;
        env.set_object_array_element(&array, i as sys::jsize, &s)?;
        env.delete_local_ref(s)?;
    }
    Ok(array.into())
}

/// Hands the tick to the platform mod, which fires Fabric API's events. Call it on the server
/// thread at the tick's end; then apply [`take_actions`]. An exception a mod throws is logged
/// by the platform mod and never stops the tick.
pub fn server_tick(
    tick: i64,
    dedicated: bool,
    world: &std::path::Path,
    players: &[Player],
    events: &[Event],
) -> Result<(), String> {
    let Some(class) = server_class() else {
        return Ok(());
    };
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    // Local references made here are freed when the frame pops.
    let result = env.with_local_frame(64, |env| -> jni::errors::Result<()> {
        call_tick(env, class, tick, dedicated, world, players, events)
    });
    result.map_err(|e| crate::describe(&mut env, e))
}

fn call_tick(
    env: &mut JNIEnv,
    class: &GlobalRef,
    tick: i64,
    dedicated: bool,
    world: &std::path::Path,
    players: &[Player],
    events: &[Event],
) -> jni::errors::Result<()> {
    let world = env.new_string(crate::plain_path(world).to_string_lossy())?;
    let ids: Vec<&str> = players.iter().map(|p| p.uuid.as_str()).collect();
    let names: Vec<&str> = players.iter().map(|p| p.name.as_str()).collect();
    let dims: Vec<&str> = players.iter().map(|p| p.dimension.as_str()).collect();
    let mut motion = Vec::with_capacity(players.len() * 5);
    for p in players {
        motion.extend_from_slice(&[
            p.position[0],
            p.position[1],
            p.position[2],
            f64::from(p.yaw),
            f64::from(p.pitch),
        ]);
    }
    let modes: Vec<i32> = players.iter().map(|p| p.game_mode).collect();
    let health: Vec<f32> = players.iter().map(|p| p.health).collect();
    let food: Vec<f32> = players
        .iter()
        .flat_map(|p| [p.food as f32, p.saturation])
        .collect();
    // Per player: the permission level in the low 8 bits, bit 8 the sneak key, bits 16-22
    // the movement keys (HorizonServer.tick's `permissions`).
    let permissions: Vec<i32> = players
        .iter()
        .map(|p| {
            i32::from(p.permission)
                | (i32::from(p.sneaking) << 8)
                | (i32::from(p.input & 0x7f) << 16)
        })
        .collect();
    // INVENTORY_SLOTS per player; nulls for a player whose inventory didn't change.
    let mut inventories: Vec<Option<&str>> = Vec::with_capacity(players.len() * INVENTORY_SLOTS);
    for p in players {
        match &p.inventory {
            Some(slots) => inventories.extend(
                (0..INVENTORY_SLOTS).map(|i| Some(slots.get(i).map_or("", String::as_str))),
            ),
            None => inventories.extend(std::iter::repeat_n(None, INVENTORY_SLOTS)),
        }
    }

    // Events as parallel arrays: a kind per event (0 joined, 1 left, 2 chat, 3 broken,
    // 4 placed, 5 died, 6 respawned), its player, a text (chat line or block state) and a position.
    let mut kinds = Vec::with_capacity(events.len());
    let mut who = Vec::with_capacity(events.len());
    let mut texts = Vec::with_capacity(events.len());
    let mut positions = Vec::with_capacity(events.len() * 3);
    for event in events {
        let (kind, uuid, text, pos) = match event {
            Event::Joined { uuid } => (0, uuid, "", [0; 3]),
            Event::Left { uuid } => (1, uuid, "", [0; 3]),
            Event::Chat { uuid, text } => (2, uuid, text.as_str(), [0; 3]),
            Event::Block {
                uuid,
                position,
                state,
                placed,
            } => (if *placed { 4 } else { 3 }, uuid, state.as_str(), *position),
            Event::Died { uuid, cause } => (5, uuid, cause.as_str(), [0; 3]),
            Event::Respawned { uuid } => (6, uuid, "", [0; 3]),
        };
        kinds.push(kind);
        who.push(uuid.as_str());
        texts.push(text);
        positions.extend_from_slice(&pos);
    }

    let ids = strings(env, &ids)?;
    let names = strings(env, &names)?;
    let dims = strings(env, &dims)?;
    let motion_array = env.new_double_array(motion.len() as sys::jsize)?;
    env.set_double_array_region(&motion_array, 0, &motion)?;
    let modes_array = env.new_int_array(modes.len() as sys::jsize)?;
    env.set_int_array_region(&modes_array, 0, &modes)?;
    let health_array = env.new_float_array(health.len() as sys::jsize)?;
    env.set_float_array_region(&health_array, 0, &health)?;
    let food_array = env.new_float_array(food.len() as sys::jsize)?;
    env.set_float_array_region(&food_array, 0, &food)?;
    let permissions_array = env.new_int_array(permissions.len() as sys::jsize)?;
    env.set_int_array_region(&permissions_array, 0, &permissions)?;
    let kinds_array = env.new_int_array(kinds.len() as sys::jsize)?;
    env.set_int_array_region(&kinds_array, 0, &kinds)?;
    let who = strings(env, &who)?;
    let texts = strings(env, &texts)?;
    let positions_array = env.new_int_array(positions.len() as sys::jsize)?;
    env.set_int_array_region(&positions_array, 0, &positions)?;
    let inventories = optional_strings(env, &inventories)?;

    let class = <&JClass>::from(class.as_obj());
    env.call_static_method(
        class,
        "tick",
        "(JZLjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[D[I[F[F[I[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;)V",
        &[
            JValue::Long(tick),
            JValue::Bool(dedicated.into()),
            JValue::Object(&world),
            JValue::Object(&ids),
            JValue::Object(&names),
            JValue::Object(&dims),
            JValue::Object(&motion_array),
            JValue::Object(&modes_array),
            JValue::Object(&health_array),
            JValue::Object(&food_array),
            JValue::Object(&permissions_array),
            JValue::Object(&kinds_array),
            JValue::Object(&who),
            JValue::Object(&texts),
            JValue::Object(&positions_array),
            JValue::Object(&inventories),
        ],
    )?;
    Ok(())
}

/// Runs `f` on this thread's JNI env with the platform's `HorizonServer` class, in a local frame.
fn with_server<T>(
    f: impl FnOnce(&mut JNIEnv, &JClass) -> jni::errors::Result<T>,
) -> Result<Option<T>, String> {
    let Some(class) = server_class() else {
        return Ok(None);
    };
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(32, |env| {
        let class = <&JClass>::from(class.as_obj());
        f(env, class)
    });
    result.map(Some).map_err(|e| crate::describe(&mut env, e))
}

fn string_array(env: &mut JNIEnv, array: JObject) -> jni::errors::Result<Vec<String>> {
    let array = jni::objects::JObjectArray::from(array);
    let len = env.get_array_length(&array)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = jni::objects::JString::from(env.get_object_array_element(&array, i)?);
        out.push(env.get_string(&item)?.into());
        env.delete_local_ref(item)?;
    }
    Ok(out)
}

/// Every command the server's dispatcher has (vanilla's and the mods'), by name, sorted.
pub fn command_names() -> Result<Vec<String>, String> {
    let names = with_server(|env, class| {
        let names = env
            .call_static_method(class, "commandNames", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, names)
    })?;
    Ok(names.unwrap_or_default())
}

/// These commands as a root of the commands report (Horizon's command tree format), as JSON.
pub fn command_tree(names: &[String]) -> Result<String, String> {
    let tree = with_server(|env, class| {
        let refs: Vec<&str> = names.iter().map(String::as_str).collect();
        let names = strings(env, &refs)?;
        let tree = env
            .call_static_method(
                class,
                "commandTree",
                "([Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&names)],
            )?
            .l()?;
        Ok(String::from(
            env.get_string(&jni::objects::JString::from(tree))?,
        ))
    })?;
    Ok(tree.unwrap_or_else(|| r#"{"type":"root","children":{}}"#.into()))
}

/// Runs a command line (no slash) as the player with this UUID; what it says comes back as
/// [`Action`]s. False when that player isn't on the mirrored server.
pub fn run_command(uuid: &str, line: &str) -> Result<bool, String> {
    let ran = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let line = env.new_string(line)?;
        env.call_static_method(
            class,
            "command",
            "(Ljava/lang/String;Ljava/lang/String;)Z",
            &[JValue::Object(&uuid), JValue::Object(&line)],
        )?
        .z()
    })?;
    Ok(ran.unwrap_or(false))
}

/// `PlayerBlockBreakEvents.BEFORE`: player `uuid` is about to break `state` at `position` of
/// `dimension`. False when a mod cancels the break. Run it inside [`crate::world::with_blocks`]
/// so mods read the world as it is; what they change comes back as actions.
pub fn before_break(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    state: &str,
) -> Result<bool, String> {
    let [x, y, z] = position;
    let allowed = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let state = env.new_string(state)?;
        env.call_static_method(
            class,
            "beforeBreak",
            "(Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Object(&state),
            ],
        )?
        .z()
    })?;
    Ok(allowed.unwrap_or(true))
}

/// Player `uuid` starts digging `position` from `face` (vanilla's `Direction` id) holding
/// hotbar slot `selected`: Fabric's `AttackBlockCallback`. True when a mod took it (the game
/// doesn't dig). Run it inside [`crate::world::with_blocks`].
pub fn attack_block(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    face: i32,
    selected: i32,
) -> Result<bool, String> {
    let [x, y, z] = position;
    let taken = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "attackBlock",
            "(Ljava/lang/String;Ljava/lang/String;IIIII)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Int(face),
                JValue::Int(selected),
            ],
        )?
        .z()
    })?;
    Ok(taken.unwrap_or(false))
}

/// What mods made of a player's attack on, or use of, one of the game's own entities
/// ([`vanilla_entity`]).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct EntityResult {
    /// A mod took it: the game does nothing more.
    pub taken: bool,
    /// The arm swings.
    pub swing: bool,
    /// The mods removed the entity (picked it up: Carry On): the game removes its own.
    pub removed: bool,
}

/// Player `uuid` attacks (`using` false) or uses the game's entity saved as `snbt` (vanilla's
/// entity NBT with its `id`), with `hand` (0 main, 1 off) at `hit`, holding hotbar slot
/// `selected`: Fabric's `AttackEntityCallback`/`UseEntityCallback` and the mods' injections at
/// the head of `Player.attack`/`interactOn`, on a copy of the entity. Run it inside
/// [`crate::world::with_blocks`].
#[allow(clippy::too_many_arguments)]
pub fn vanilla_entity(
    uuid: &str,
    dimension: &str,
    snbt: &str,
    using: bool,
    hand: i32,
    hit: [f64; 3],
    selected: i32,
) -> Result<EntityResult, String> {
    let bits = with_server(|env, _| {
        let class = vanilla_class(env)?;
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let snbt = env.new_string(snbt)?;
        env.call_static_method(
            &class,
            "entity",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIDDDI)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Object(&snbt),
                JValue::Bool(u8::from(using)),
                JValue::Int(hand),
                JValue::Double(hit[0]),
                JValue::Double(hit[1]),
                JValue::Double(hit[2]),
                JValue::Int(selected),
            ],
        )?
        .i()
    })?
    .unwrap_or(0);
    Ok(EntityResult {
        taken: bits & 1 != 0,
        swing: bits & 2 != 0,
        removed: bits & 4 != 0,
    })
}

/// Player `uuid` took `count` of the crafting result `result` (SNBT) of recipe `recipe` from a
/// grid `width` wide (`grid`: each cell's SNBT, `""` for empty, as before the take): vanilla's
/// `ResultSlot.onTake` on copies, for the mods' crafted hooks (Architectury, Balm, Modonomicon).
/// What they give comes back as slots. Run it inside [`crate::world::with_blocks`].
pub fn crafted(
    uuid: &str,
    recipe: &str,
    result: &str,
    count: i32,
    width: i32,
    grid: &[String],
) -> Result<(), String> {
    with_server(|env, _| {
        let class = vanilla_class(env)?;
        let uuid = env.new_string(uuid)?;
        let recipe = env.new_string(recipe)?;
        let result = env.new_string(result)?;
        let cells = env.new_object_array(
            grid.len() as sys::jsize,
            "java/lang/String",
            JObject::null(),
        )?;
        for (i, cell) in grid.iter().enumerate() {
            let s = env.new_string(cell)?;
            env.set_object_array_element(&cells, i as sys::jsize, &s)?;
        }
        env.call_static_method(
            &class,
            "crafted",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II[Ljava/lang/String;)V",
            &[
                JValue::Object(&uuid),
                JValue::Object(&recipe),
                JValue::Object(&result),
                JValue::Int(count),
                JValue::Int(width),
                JValue::Object(&cells),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// `dev.horizonlab.platform.HorizonVanillaActions`, from Fabric's class loader.
fn vanilla_class<'a>(env: &mut JNIEnv<'a>) -> jni::errors::Result<JClass<'a>> {
    let bridge = crate::get().ok_or(jni::errors::Error::NullPtr("the JVM is not running"))?;
    bridge.find_class(env, "dev/horizonlab/platform/HorizonVanillaActions")
}

/// One mob a routed attack hit ([`player_attack`]): what its copy's `hurtServer` got and the
/// knockback it was given (vanilla's `knockback(strength, x, z)`).
#[derive(Clone, Debug, PartialEq)]
pub struct AttackHit {
    /// The game's entity id.
    pub id: u32,
    pub damage: f32,
    /// The damage type (`minecraft:player_attack`).
    pub damage_type: String,
    pub knockback: f64,
    pub knock_x: f64,
    pub knock_z: f64,
}

/// Player `uuid` attacks the game's mob `ids[0]`; `nbts` are it and the mobs around (vanilla's
/// entity NBT), `ticks` the player's ticks since its last swing, `yaw`/`pitch` its view:
/// the click's client half (the mods' head of `Minecraft.startAttack`; a mod that takes it
/// makes its own attack), else vanilla's `Player.attack` with the mods' patches on copies of
/// them. Each mob hit, for the game to hurt; None: the game attacks
/// itself. Run it inside [`crate::world::with_blocks`].
#[allow(clippy::too_many_arguments)]
pub fn player_attack(
    uuid: &str,
    dimension: &str,
    nbts: &[String],
    ids: &[u32],
    ticks: i32,
    yaw: f32,
    pitch: f32,
    selected: i32,
) -> Result<Option<Vec<AttackHit>>, String> {
    let out = with_server(|env, _| {
        let bridge = crate::get().ok_or(jni::errors::Error::NullPtr("the JVM is not running"))?;
        let class = bridge.find_class(env, "dev/horizonlab/platform/HorizonCombat")?;
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let array = env.new_object_array(
            nbts.len() as sys::jsize,
            "java/lang/String",
            JObject::null(),
        )?;
        for (i, n) in nbts.iter().enumerate() {
            let s = env.new_string(n)?;
            env.set_object_array_element(&array, i as sys::jsize, &s)?;
        }
        let id_array = env.new_int_array(ids.len() as sys::jsize)?;
        let flat: Vec<i32> = ids.iter().map(|&i| i as i32).collect();
        env.set_int_array_region(&id_array, 0, &flat)?;
        let types = env.new_object_array(
            nbts.len() as sys::jsize,
            "java/lang/String",
            JObject::null(),
        )?;
        let result = env
            .call_static_method(
                &class,
                "attack",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[IIFFI[Ljava/lang/String;)[D",
                &[
                    JValue::Object(&uuid),
                    JValue::Object(&dimension),
                    JValue::Object(&array),
                    JValue::Object(&id_array),
                    JValue::Int(ticks),
                    JValue::Float(yaw),
                    JValue::Float(pitch),
                    JValue::Int(selected),
                    JValue::Object(&types),
                ],
            )?
            .l()?;
        if result.is_null() {
            return Ok(None);
        }
        let result = jni::objects::JDoubleArray::from(result);
        let len = env.get_array_length(&result)? as usize;
        let mut v = vec![0.0f64; len];
        env.get_double_array_region(&result, 0, &mut v)?;
        let mut hits = Vec::new();
        for (k, h) in v.chunks_exact(5).enumerate() {
            let t = env.get_object_array_element(&types, k as sys::jsize)?;
            let damage_type = if t.is_null() {
                String::new()
            } else {
                env.get_string(&JString::from(t))?.into()
            };
            hits.push(AttackHit {
                id: h[0] as u32,
                damage: h[1] as f32,
                damage_type,
                knockback: h[2],
                knock_x: h[3],
                knock_z: h[4],
            });
        }
        Ok(Some(hits))
    })?;
    Ok(out.flatten())
}

/// `dev.horizonlab.platform.HorizonVanillaActions`'s static `method` (a JNI signature).
fn call_vanilla<'a>(
    env: &mut JNIEnv<'a>,
    method: &str,
    sig: &str,
    args: &[JValue],
) -> jni::errors::Result<jni::objects::JValueOwned<'a>> {
    let class = vanilla_class(env)?;
    env.call_static_method(&class, method, sig, args)
}

/// Player `uuid` picks hotbar slot `slot`: the mods' heads of `Inventory.setSelectedSlot`.
/// True when one cancelled (the game keeps the old slot).
pub fn select_slot(uuid: &str, slot: i32) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        call_vanilla(
            env,
            "selectSlot",
            "(Ljava/lang/String;I)Z",
            &[JValue::Object(&uuid), JValue::Int(slot)],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// Player `uuid` got onto the game's vehicle of type `ty` (`minecraft:oak_boat`) at
/// `position`, `snbt` its NBT when it is a mob (else empty): the mods' heads of
/// `Entity.startRiding`. True when one cancelled (the game takes the player off).
pub fn ride(
    uuid: &str,
    dimension: &str,
    ty: &str,
    position: [f64; 3],
    snbt: &str,
) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let ty = env.new_string(ty)?;
        let snbt = env.new_string(snbt)?;
        call_vanilla(
            env,
            "ride",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;DDDLjava/lang/String;)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Object(&ty),
                JValue::Double(position[0]),
                JValue::Double(position[1]),
                JValue::Double(position[2]),
                JValue::Object(&snbt),
            ],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// Player `uuid` gets off the game's vehicle (as [`ride`]'s): before the game takes it off
/// (`after` false), the mods' heads of `stopRiding`/`removeVehicle`, true when one cancelled;
/// after (`after` true), their ends.
pub fn dismount(
    uuid: &str,
    dimension: &str,
    ty: &str,
    position: [f64; 3],
    snbt: &str,
    after: bool,
) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let ty = env.new_string(ty)?;
        let snbt = env.new_string(snbt)?;
        call_vanilla(
            env,
            "dismount",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;DDDLjava/lang/String;Z)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Object(&ty),
                JValue::Double(position[0]),
                JValue::Double(position[1]),
                JValue::Double(position[2]),
                JValue::Object(&snbt),
                JValue::Bool(u8::from(after)),
            ],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// A player lands from `distance` (`multiplier` and `stalagmite` from the block it lands on):
/// vanilla's `causeFallDamage` on the mirror with the mods' patches, Horizon's `safe` fall
/// distance, `damage_multiplier` and immunity answering `calculateFallDamage`
/// (`HorizonVanillaActions.fall`). The damage to deal; `None` when the mirror has no such
/// player.
#[allow(clippy::too_many_arguments)]
pub fn fall(
    uuid: &str,
    dimension: &str,
    distance: f64,
    multiplier: f32,
    stalagmite: bool,
    safe: f64,
    damage_multiplier: f64,
    immune: bool,
) -> Result<Option<f32>, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        call_vanilla(
            env,
            "fall",
            "(Ljava/lang/String;Ljava/lang/String;DFZDDZ)F",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Double(distance),
                JValue::Float(multiplier),
                JValue::Bool(u8::from(stalagmite)),
                JValue::Double(safe),
                JValue::Double(damage_multiplier),
                JValue::Bool(u8::from(immune)),
            ],
        )?
        .f()
    })?;
    Ok(r.filter(|d| *d >= 0.0))
}

/// Horizon adds effect `id` (namespaced) to a player: the mods' heads of
/// `LivingEntity.addEffect` (`HorizonVanillaActions.addEffect`). True when one cancelled.
pub fn add_effect(
    uuid: &str,
    id: &str,
    duration: i32,
    amplifier: i32,
    ambient: bool,
    visible: bool,
) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let id = env.new_string(id)?;
        call_vanilla(
            env,
            "addEffect",
            "(Ljava/lang/String;Ljava/lang/String;IIZZ)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&id),
                JValue::Int(duration),
                JValue::Int(amplifier),
                JValue::Bool(u8::from(ambient)),
                JValue::Bool(u8::from(visible)),
            ],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// Player `uuid` is about to jump (`HorizonVanillaActions.jumpStart`): the mods' heads of
/// `Player/LivingEntity.jumpFromGround`. True when one cancelled (the game doesn't jump it).
pub fn jump_start(uuid: &str) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        call_vanilla(
            env,
            "jumpStart",
            "(Ljava/lang/String;)Z",
            &[JValue::Object(&uuid)],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// Player `uuid` jumped: the ends of `jumpFromGround` (`HorizonVanillaActions.jumpEnd`).
pub fn jump_end(uuid: &str) -> Result<(), String> {
    with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        call_vanilla(
            env,
            "jumpEnd",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&uuid)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Player `uuid` is to be teleported to `at` in `dimension` facing `look`, or was (`end`):
/// the mods' heads of `ServerPlayer.teleport` (true when one cancelled: the game leaves the
/// player where it is), or its ends.
pub fn teleport(
    uuid: &str,
    dimension: &str,
    at: [f64; 3],
    look: [f32; 2],
    end: bool,
) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let args = [
            JValue::Object(&uuid),
            JValue::Object(&dimension),
            JValue::Double(at[0]),
            JValue::Double(at[1]),
            JValue::Double(at[2]),
            JValue::Float(look[0]),
            JValue::Float(look[1]),
        ];
        if end {
            call_vanilla(
                env,
                "teleportEnd",
                "(Ljava/lang/String;Ljava/lang/String;DDDFF)V",
                &args,
            )?;
            Ok(false)
        } else {
            call_vanilla(
                env,
                "teleportStart",
                "(Ljava/lang/String;Ljava/lang/String;DDDFF)Z",
                &args,
            )?
            .z()
        }
    })?;
    Ok(r.unwrap_or(false))
}

/// Player `uuid` is to get `criterion` of advancement `id`, `done` being the criteria it has:
/// vanilla's `PlayerAdvancements.award` on the mirror with the mods' patches
/// (`HorizonVanillaActions.award`). True when the mods refused it.
pub fn award(uuid: &str, id: &str, criterion: &str, done: &[&str]) -> Result<bool, String> {
    let r = with_server(|env, _| {
        let uuid = env.new_string(uuid)?;
        let id = env.new_string(id)?;
        let criterion = env.new_string(criterion)?;
        let string = env.find_class("java/lang/String")?;
        let list = env.new_object_array(done.len() as i32, &string, JObject::null())?;
        for (i, c) in done.iter().enumerate() {
            let c = env.new_string(c)?;
            env.set_object_array_element(&list, i as i32, &c)?;
        }
        call_vanilla(
            env,
            "award",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Z",
            &[
                JValue::Object(&uuid),
                JValue::Object(&id),
                JValue::Object(&criterion),
                JValue::Object(&list),
            ],
        )?
        .z()
    })?;
    Ok(r.unwrap_or(false))
}

/// Precipitation ticks of `dimension` at columns topped by `tops` (x, y, z each): the
/// biome's answers on the Java side with the mods' patches (Serene Seasons): per column
/// {freezes below the top, snows at it, precipitation below it: 0 none, 1 rain, 2 snow}.
pub fn precipitation(dimension: &str, tops: &[i32]) -> Result<Vec<i32>, String> {
    let out = with_server(|env, _| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(tops.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, tops)?;
        let result = call_vanilla(
            env,
            "precipitation",
            "(Ljava/lang/String;[I)[I",
            &[JValue::Object(&dimension), JValue::Object(&array)],
        )?
        .l()?;
        let result = jni::objects::JIntArray::from(result);
        let len = env.get_array_length(&result)? as usize;
        let mut v = vec![0i32; len];
        env.get_int_array_region(&result, 0, &mut v)?;
        Ok(v)
    })?;
    Ok(out.unwrap_or_default())
}

/// What mods make of a player's break of a block ([`break_block`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreakResult {
    /// A mod stopped it (`PlayerBlockBreakEvents.BEFORE`, a cancelled `destroyBlock` head).
    Cancelled,
    /// The game breaks it; `after`: then call [`after_break`] (mods' injections at
    /// `destroyBlock`'s end).
    Horizon { after: bool },
    /// The mods' vanilla code broke it on the Java side (a mod patches the inside of
    /// breaking: a vein miner): the block's removal, drops and the tool's wear come back as
    /// actions and slots; the game does nothing more.
    Java,
}

/// Player `uuid` is about to break `state` at `position` of `dimension`, holding hotbar slot
/// `selected`: the mods' injections at `ServerPlayerGameMode.destroyBlock`'s head and Fabric's
/// `PlayerBlockBreakEvents.BEFORE`, or, when a mod patches the inside of breaking, vanilla's
/// whole `destroyBlock` on the Java side. Run it inside [`crate::world::with_blocks`] so mods
/// read the world as it is; what they change comes back as actions.
pub fn break_block(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    state: &str,
    selected: i32,
) -> Result<BreakResult, String> {
    let [x, y, z] = position;
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let state = env.new_string(state)?;
        env.call_static_method(
            class,
            "breakBlock",
            "(Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;I)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Object(&state),
                JValue::Int(selected),
            ],
        )?
        .i()
    })?;
    Ok(match result {
        Some(0) => BreakResult::Cancelled,
        Some(2) => BreakResult::Horizon { after: true },
        Some(3) => BreakResult::Java,
        _ => BreakResult::Horizon { after: false },
    })
}

/// The game broke the block [`break_block`] answered `Horizon { after: true }` for: the mods'
/// injections at `destroyBlock`'s end.
pub fn after_break(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    state: &str,
) -> Result<(), String> {
    let [x, y, z] = position;
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let state = env.new_string(state)?;
        env.call_static_method(
            class,
            "afterBreak",
            "(Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;)V",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Object(&state),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// What mods made of a player's use of a block ([`use_block`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UseResult {
    /// No mod took the click: the game uses the block.
    Pass,
    /// A mod took it (UseBlockCallback's SUCCESS, FAIL, ...): the game does nothing more.
    Taken,
    /// A mod took it, and the player's arm swings.
    TakenSwing,
}

/// A player uses (right-clicks) a block: vanilla's `ServerPlayerGameMode.useItemOn` up to the
/// end of the mods' injections at its head (Fabric API's `UseBlockCallback`). `face` is
/// vanilla's `Direction` id (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), `hand` 0 main,
/// 1 off, `selected` the hotbar slot. Run it inside [`crate::world::with_blocks`].
#[allow(clippy::too_many_arguments)]
pub fn use_block(
    uuid: &str,
    dimension: &str,
    position: [i32; 3],
    face: i32,
    hit: [f64; 3],
    hand: i32,
    selected: i32,
) -> Result<UseResult, String> {
    let [x, y, z] = position;
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "useBlock",
            "(Ljava/lang/String;Ljava/lang/String;IIIIDDDII)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(y),
                JValue::Int(z),
                JValue::Int(face),
                JValue::Double(hit[0]),
                JValue::Double(hit[1]),
                JValue::Double(hit[2]),
                JValue::Int(hand),
                JValue::Int(selected),
            ],
        )?
        .i()
    })?;
    Ok(match result {
        Some(2) => UseResult::TakenSwing,
        Some(1) => UseResult::Taken,
        _ => UseResult::Pass,
    })
}

/// A player uses the item in `hand` (0 main, 1 off) on nothing (right-click in the air):
/// vanilla's `ServerPlayerGameMode.useItem` for Java mods (Fabric API's `UseItemCallback`, a
/// mod item's own use). `yaw` and `pitch` are where the player looked as it clicked.
pub fn use_item(
    uuid: &str,
    dimension: &str,
    hand: i32,
    selected: i32,
    yaw: f32,
    pitch: f32,
) -> Result<UseResult, String> {
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "useItem",
            "(Ljava/lang/String;Ljava/lang/String;IIFF)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Int(hand),
                JValue::Int(selected),
                JValue::Float(yaw),
                JValue::Float(pitch),
            ],
        )?
        .i()
    })?;
    Ok(match result {
        Some(2) => UseResult::TakenSwing,
        Some(1) => UseResult::Taken,
        _ => UseResult::Pass,
    })
}

/// The vanilla blocks (`minecraft:wheat`, ...) whose random tick is the mods': a mod patches
/// the block's `randomTick` or what it calls (`growCrops`, `getGrowthSpeed`, `advanceTree`)
/// other than through Horizon's own hooks. The game sends their random ticks to
/// [`random_ticks`] instead of running them.
pub fn routed_random_ticks() -> Result<Vec<String>, String> {
    let names = with_server(|env, class| {
        let array = env
            .call_static_method(class, "routedRandomTicks", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, array)
    })?;
    Ok(names.unwrap_or_default())
}

/// The Java mods' fluid blocks (their `LiquidBlock`s of a mod's fluid), by id: the mods run
/// their flow (`HorizonServer.modFluidBlocks`).
pub fn mod_fluid_blocks() -> Result<Vec<String>, String> {
    let names = with_server(|env, class| {
        let array = env
            .call_static_method(class, "modFluidBlocks", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, array)
    })?;
    Ok(names.unwrap_or_default())
}

/// The other actions the game sends to the mods because a mod patches them:
/// `"item_pickup"` ([`item_touch`]) and `"experience_orbs"` ([`orb_touch`]).
pub fn routed_actions() -> Result<Vec<String>, String> {
    let names = with_server(|env, class| {
        let array = env
            .call_static_method(class, "routedActions", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, array)
    })?;
    Ok(names.unwrap_or_default())
}

/// What vanilla's `ExperienceOrb.playerTouch` with the mods' patches made of a touch
/// ([`orb_touch`]).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OrbTouch {
    /// What is left of the orb's count (0: it is gone).
    pub count: i32,
    /// The player's `takeXpDelay` now.
    pub delay: i32,
    /// The player's experience now: level, progress in it, total points.
    pub level: i32,
    pub progress: f32,
    pub total: i32,
}

/// Player `uuid` touches the game's experience orb of `value` (`count` merged into it) at
/// `position`, their experience `level`/`progress`/`total`: vanilla's `playerTouch` with the
/// mods' patches (Clumps). Mending's repairs come back as slots. None: the game does it.
#[allow(clippy::too_many_arguments)]
pub fn orb_touch(
    uuid: &str,
    dimension: &str,
    position: [f64; 3],
    value: i32,
    count: i32,
    level: i32,
    progress: f32,
    total: i32,
) -> Result<Option<OrbTouch>, String> {
    let out = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let array = env
            .call_static_method(
                class,
                "orbTouch",
                "(Ljava/lang/String;Ljava/lang/String;DDDIIIFI)[D",
                &[
                    JValue::Object(&uuid),
                    JValue::Object(&dimension),
                    JValue::Double(position[0]),
                    JValue::Double(position[1]),
                    JValue::Double(position[2]),
                    JValue::Int(value),
                    JValue::Int(count),
                    JValue::Int(level),
                    JValue::Float(progress),
                    JValue::Int(total),
                ],
            )?
            .l()?;
        if array.is_null() {
            return Ok(None);
        }
        let array = jni::objects::JDoubleArray::from(array);
        let mut v = [0.0f64; 5];
        if env.get_array_length(&array)? < 5 {
            return Ok(None);
        }
        env.get_double_array_region(&array, 0, &mut v)?;
        Ok(Some(OrbTouch {
            count: v[0] as i32,
            delay: v[1] as i32,
            level: v[2] as i32,
            progress: v[3] as f32,
            total: v[4] as i32,
        }))
    })?;
    Ok(out.flatten())
}

/// Player `uuid` touches the game's dropped item `stack` (SNBT, as [`Player::inventory`]'s)
/// at `position`: vanilla's `ItemEntity.playerTouch` with the mods' patches. How many of the
/// stack are left (what was taken comes back as slots); None: the game does it.
pub fn item_touch(
    uuid: &str,
    dimension: &str,
    position: [f64; 3],
    stack: &str,
) -> Result<Option<i32>, String> {
    let left = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let dimension = env.new_string(dimension)?;
        let stack = env.new_string(stack)?;
        env.call_static_method(
            class,
            "itemTouch",
            "(Ljava/lang/String;Ljava/lang/String;DDDLjava/lang/String;)I",
            &[
                JValue::Object(&uuid),
                JValue::Object(&dimension),
                JValue::Double(position[0]),
                JValue::Double(position[1]),
                JValue::Double(position[2]),
                JValue::Object(&stack),
            ],
        )?
        .i()
    })?;
    Ok(left.filter(|&l| l >= 0))
}

/// What a hopper got from a Java mod's block ([`hopper_insert`], [`hopper_extract`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HopperMove<T> {
    /// The block holds no items on that face (Fabric's `ItemStorage.SIDED` found none).
    NoStorage,
    /// It does, and nothing moved.
    Nothing,
    Moved(T),
}

/// A Horizon hopper pushes one item into the Java mod's block at `pos` through `face`
/// (`Direction.get3DDataValue`): `stacks` are the hopper's slots in order (one-item SNBT, None
/// when empty); the index of the slot whose item went in.
pub fn hopper_insert(
    dimension: &str,
    pos: [i32; 3],
    face: i32,
    stacks: &[Option<&str>],
) -> Result<HopperMove<usize>, String> {
    let slot = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let stacks = optional_strings(env, stacks)?;
        env.call_static_method(
            class,
            "hopperInsert",
            "(Ljava/lang/String;IIII[Ljava/lang/String;)I",
            &[
                JValue::Object(&dimension),
                JValue::Int(pos[0]),
                JValue::Int(pos[1]),
                JValue::Int(pos[2]),
                JValue::Int(face),
                JValue::Object(&stacks),
            ],
        )?
        .i()
    })?;
    Ok(match slot {
        Some(i) if i >= 0 => HopperMove::Moved(i as usize),
        Some(-1) => HopperMove::Nothing,
        _ => HopperMove::NoStorage,
    })
}

/// A Horizon hopper pulls one item out of the Java mod's block at `pos` through `face`: the
/// first it offers that the hopper has room for (`any_room`: an empty slot; else one of
/// `partial`, one-item SNBT of its stacks not full yet). The item as SNBT.
pub fn hopper_extract(
    dimension: &str,
    pos: [i32; 3],
    face: i32,
    any_room: bool,
    partial: &[&str],
) -> Result<HopperMove<String>, String> {
    let out = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let partial = strings(env, partial)?;
        let value = env
            .call_static_method(
                class,
                "hopperExtract",
                "(Ljava/lang/String;IIIIZ[Ljava/lang/String;)Ljava/lang/String;",
                &[
                    JValue::Object(&dimension),
                    JValue::Int(pos[0]),
                    JValue::Int(pos[1]),
                    JValue::Int(pos[2]),
                    JValue::Int(face),
                    JValue::Bool(u8::from(any_room)),
                    JValue::Object(&partial),
                ],
            )?
            .l()?;
        if value.is_null() {
            return Ok(None);
        }
        Ok(Some(String::from(env.get_string(&JString::from(value))?)))
    })?;
    Ok(match out.flatten() {
        None => HopperMove::NoStorage,
        Some(s) if s.is_empty() => HopperMove::Nothing,
        Some(s) => HopperMove::Moved(s),
    })
}

/// Random ticks that reached Java mods' blocks in `dimension` (`ServerLevel.tickChunk`'s), and
/// vanilla blocks [`routed_random_ticks`] names: each still such a block that ticks randomly
/// runs `BlockState.randomTick`.
pub fn random_ticks(dimension: &str, positions: &[[i32; 3]]) -> Result<(), String> {
    let flat: Vec<i32> = positions.iter().flatten().copied().collect();
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        env.call_static_method(
            class,
            "randomTicks",
            "(Ljava/lang/String;[I)V",
            &[JValue::Object(&dimension), JValue::Object(&array)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Lava blocks whose `LiquidBlock.shouldSpreadLiquid` the mods patch (their own fluid
/// interactions: Create's honey and chocolate with lava), placed or next to a change this tick:
/// vanilla's `shouldSpreadLiquid` runs there on the mirror, the mods' injections first; what it
/// sets (obsidian, a mod's stone) comes back as the mods' block writes.
pub fn liquid_interactions(dimension: &str, positions: &[[i32; 3]]) -> Result<(), String> {
    let flat: Vec<i32> = positions.iter().flatten().copied().collect();
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        env.call_static_method(
            class,
            "liquidInteractions",
            "(Ljava/lang/String;[I)V",
            &[JValue::Object(&dimension), JValue::Object(&array)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The world's game time (`Level.getGameTime`, which mods time their work by: Tom's Storage's
/// connectors scan every second of it), set before each tick, and whether the world runs this
/// tick (`/tick freeze` stops the mods' block entities, scheduled ticks and entities too).
pub fn game_time(time: i64, seed: i64, running: bool) -> Result<(), String> {
    with_server(|env, class| {
        env.call_static_method(
            class,
            "gameTime",
            "(JJZ)V",
            &[
                JValue::Long(time),
                JValue::Long(seed),
                JValue::Bool(running.into()),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Horizon's game rules (`minecraft:spawn_mobs=false;...`) into the mirror's, which mods
/// read (their spawner, their mobs' griefing). Sent when they change.
pub fn game_rules(rules: &str) -> Result<(), String> {
    with_server(|env, class| {
        let rules = env.new_string(rules)?;
        env.call_static_method(
            class,
            "gameRules",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&rules)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Mods left out of Fabric's (`renderers::split`: Sodium, Iris), one line each: the players
/// are told they aren't needed, at their join (`HorizonFailures.notNeeded`).
pub fn not_needed(lines: &[String]) -> Result<(), String> {
    with_server(|env, class| {
        let lines = env.new_string(lines.join("\n"))?;
        env.call_static_method(
            class,
            "notNeeded",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&lines)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Java mods' blocks placed or removed at `positions` of `dimension`: their block entities made
/// (or, gone, put away), so they tick from their placement as vanilla's do.
pub fn block_entities(
    dimension: &str,
    positions: &[[i32; 3]],
    previous: &[String],
) -> Result<(), String> {
    let flat: Vec<i32> = positions.iter().flatten().copied().collect();
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        let previous = env.new_string(previous.join("\n"))?;
        env.call_static_method(
            class,
            "blockEntities",
            "(Ljava/lang/String;[ILjava/lang/String;)V",
            &[
                JValue::Object(&dimension),
                JValue::Object(&array),
                JValue::Object(&previous),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Horizon updated the neighbours of `positions` of `dimension` beside Java mods' blocks: the
/// mods' blocks hear it (`HorizonServer.neighbours`).
pub fn neighbours(dimension: &str, positions: &[[i32; 3]]) -> Result<(), String> {
    let flat: Vec<i32> = positions.iter().flatten().copied().collect();
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        env.call_static_method(
            class,
            "neighbours",
            "(Ljava/lang/String;[I)V",
            &[JValue::Object(&dimension), JValue::Object(&array)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Input to the player's open mod screen (JSON: `size`, `move`, `press`, `release`, `drag`,
/// `scroll`, `key`, `char`), run by the screen's own code.
/// The keys Java mods' key bindings are set to (`KeyMapping`s they registered), as 26.3's
/// keyboard scancodes: what a player's game sends while playing (`HorizonClient.input`'s
/// `bind`). Empty without a headless client.
pub fn bound_keys() -> Result<Vec<i32>, String> {
    let Some(class) = client_class() else {
        return Ok(Vec::new());
    };
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    let json = env
        .with_local_frame(8, |env| -> jni::errors::Result<String> {
            let class = <&JClass>::from(class.as_obj());
            let value = env
                .call_static_method(class, "boundKeys", "()Ljava/lang/String;", &[])?
                .l()?;
            Ok(env.get_string(&JString::from(value))?.into())
        })
        .map_err(|e| crate::describe(&mut env, e))?;
    serde_json::from_str(&json).map_err(|e| e.to_string())
}

/// The Java mods' key bindings (HorizonClient.keyMappings): `[{name, category, key, default,
/// label, categoryLabel}]` as JSON, for players' Controls pages. "[]" without a headless client.
pub fn key_mappings() -> Result<String, String> {
    let Some(class) = client_class() else {
        return Ok("[]".into());
    };
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    env.with_local_frame(8, |env| -> jni::errors::Result<String> {
        let class = <&JClass>::from(class.as_obj());
        let value = env
            .call_static_method(class, "keyMappings", "()Ljava/lang/String;", &[])?
            .l()?;
        Ok(env.get_string(&JString::from(value))?.into())
    })
    .map_err(|e| crate::describe(&mut env, e))
}

/// The Java mods with a settings screen (HorizonClient.configMods): `[id, name]` pairs, for
/// players' Mods pages.
pub fn config_mods() -> Result<Vec<(String, String)>, String> {
    let Some(class) = client_class() else {
        return Ok(Vec::new());
    };
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    let json = env
        .with_local_frame(8, |env| -> jni::errors::Result<String> {
            let class = <&JClass>::from(class.as_obj());
            let value = env
                .call_static_method(class, "configMods", "()Ljava/lang/String;", &[])?
                .l()?;
            Ok(env.get_string(&JString::from(value))?.into())
        })
        .map_err(|e| crate::describe(&mut env, e))?;
    serde_json::from_str(&json).map_err(|e| e.to_string())
}

pub fn screen_input(uuid: &str, input: &str) -> Result<(), String> {
    let class = client_class().ok_or("no headless client")?;
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    env.with_local_frame(8, |env| -> jni::errors::Result<()> {
        let uuid = env.new_string(uuid)?;
        let input = env.new_string(input)?;
        let class = <&JClass>::from(class.as_obj());
        env.call_static_method(
            class,
            "input",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&uuid), JValue::Object(&input)],
        )?;
        Ok(())
    })
    .map_err(|e| crate::describe(&mut env, e))
}

/// A game's own skin (an offline name's, from its launcher: `horizon:java_own_skin`) for a
/// player: 64x64 RGBA rows and whether its arms are slim. Mods that build from a player's skin
/// (3D skin layers) use it (`HorizonSkins.ownSkin`).
pub fn own_skin(uuid: &str, slim: bool, rgba: &[u8]) -> Result<(), String> {
    call_skins(|env, class| {
        let uuid = env.new_string(uuid)?;
        let rgba = env.byte_array_from_slice(rgba)?;
        env.call_static_method(
            class,
            "ownSkin",
            "(Ljava/lang/String;Z[B)V",
            &[
                JValue::Object(&uuid),
                JValue::Bool(slim as u8),
                JValue::Object(&rgba),
            ],
        )?;
        Ok(())
    })
}

/// A player's `textures` profile property as Horizon's server has it (`HorizonSkins.textures`):
/// the mirrored player's profile carries it, and its skin is fetched for mods that read it.
pub fn profile_textures(uuid: &str, value: &str, signature: Option<&str>) -> Result<(), String> {
    call_skins(|env, class| {
        let uuid = env.new_string(uuid)?;
        let value = env.new_string(value)?;
        let signature = env.new_string(signature.unwrap_or(""))?;
        env.call_static_method(
            class,
            "textures",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Object(&uuid),
                JValue::Object(&value),
                JValue::Object(&signature),
            ],
        )?;
        Ok(())
    })
}

fn call_skins(
    f: impl FnOnce(&mut JNIEnv, &JClass) -> jni::errors::Result<()>,
) -> Result<(), String> {
    static SKINS_CLASS: std::sync::OnceLock<Option<GlobalRef>> = std::sync::OnceLock::new();
    let class = SKINS_CLASS
        .get_or_init(|| {
            let bridge = crate::get()?;
            let mut env = bridge.vm().attach_current_thread_permanently().ok()?;
            match bridge.find_class(&mut env, "dev/horizonlab/platform/HorizonSkins") {
                Ok(class) => env.new_global_ref(class).ok(),
                Err(_) => {
                    let _ = env.exception_clear();
                    None
                }
            }
        })
        .as_ref()
        .ok_or("no platform mod")?;
    let bridge = crate::get().ok_or("Java isn't running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| e.to_string())?;
    env.with_local_frame(8, |env| f(env, <&JClass>::from(class.as_obj())))
        .map_err(|e| crate::describe(&mut env, e))
}

/// The platform's `HorizonClient` (the headless client), when Fabric runs its client side.
fn client_class() -> Option<&'static GlobalRef> {
    static CLIENT_CLASS: std::sync::OnceLock<Option<GlobalRef>> = std::sync::OnceLock::new();
    CLIENT_CLASS
        .get_or_init(|| {
            let bridge = crate::get()?;
            let mut env = bridge.vm().attach_current_thread_permanently().ok()?;
            let class = bridge
                .find_class(&mut env, "dev/horizonlab/platform/HorizonClient")
                .ok()?;
            env.new_global_ref(class).ok()
        })
        .as_ref()
}

/// A click in the window of a mod's menu ([`Action::MenuContent`]): `ServerboundContainerClickPacket`'s
/// slot, button and input, run by the menu.
pub fn menu_click(uuid: &str, slot: i32, button: i32, input: i32) -> Result<(), String> {
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "menuClick",
            "(Ljava/lang/String;III)V",
            &[
                JValue::Object(&uuid),
                JValue::Int(slot),
                JValue::Int(button),
                JValue::Int(input),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The player closed the window of a mod's menu.
pub fn menu_close(uuid: &str) -> Result<(), String> {
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "menuClose",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&uuid)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The head of a player's `hurtServer`: Fabric API's `ALLOW_DAMAGE`, then the mods'
/// injections at the heads of `ServerPlayer`, `Player` and `LivingEntity.hurtServer`. The
/// amount to hurt by, or None when a mod stops the hit.
pub fn hurt(uuid: &str, damage_type: &str, amount: f32) -> Result<Option<f32>, String> {
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let damage_type = env.new_string(damage_type)?;
        env.call_static_method(
            class,
            "hurt",
            "(Ljava/lang/String;Ljava/lang/String;F)F",
            &[
                JValue::Object(&uuid),
                JValue::Object(&damage_type),
                JValue::Float(amount),
            ],
        )?
        .f()
    })?;
    Ok(match result {
        Some(a) if a < 0.0 => None,
        Some(a) => Some(a),
        None => Some(amount),
    })
}

/// Writes the oracle facts of the Java mods' blocks and items (and vanilla's, for templates)
/// to `out` (`HorizonContent.exportFacts`): the namespaces the mods register in.
/// The creative inventory as the mods change it (HorizonContent.exportCreative), written to
/// `out` in horizon-prepare's `creative-inventory.json` form.
/// Writes the mods' block tints (`HorizonTints.export`: their BlockColors tint sources by
/// block, as the mesher's kinds) to `out`. Needs the headless client's side (client classes).
pub fn export_tints(out: &std::path::Path) -> Result<(), String> {
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(16, |env| -> jni::errors::Result<()> {
        let name = env.new_string("dev.horizonlab.platform.HorizonTints")?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        let out = env.new_string(out.to_string_lossy())?;
        env.call_static_method(
            JClass::from(class),
            "export",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&out)],
        )?;
        Ok(())
    });
    result.map_err(|e| crate::describe(&mut env, e))
}

pub fn export_creative(out: &std::path::Path) -> Result<(), String> {
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(16, |env| -> jni::errors::Result<()> {
        let name = env.new_string("dev.horizonlab.platform.HorizonContent")?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        let out = env.new_string(out.to_string_lossy())?;
        env.call_static_method(
            JClass::from(class),
            "exportCreative",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&out)],
        )?;
        Ok(())
    });
    result.map_err(|e| crate::describe(&mut env, e))
}

pub fn export_facts(out: &std::path::Path) -> Result<Vec<String>, String> {
    let bridge = crate::get().ok_or("the JVM is not running")?;
    let mut env = bridge
        .vm()
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let result = env.with_local_frame(16, |env| -> jni::errors::Result<String> {
        let name = env.new_string("dev.horizonlab.platform.HorizonContent")?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        let out = env.new_string(out.to_string_lossy())?;
        let namespaces = env
            .call_static_method(
                JClass::from(class),
                "exportFacts",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&out)],
            )?
            .l()?;
        Ok(env.get_string(&JString::from(namespaces))?.into())
    });
    let namespaces = result.map_err(|e| crate::describe(&mut env, e))?;
    Ok(namespaces
        .split(',')
        .filter(|n| !n.is_empty())
        .map(str::to_string)
        .collect())
}

/// Every mod entity type's id (`HorizonEntities.types`): `/summon` of one runs on the mods'
/// server.
pub fn entity_types() -> Result<Vec<String>, String> {
    let types = with_server(|env, class| {
        let types = env
            .call_static_method(class, "entityTypes", "()[Ljava/lang/String;", &[])?
            .l()?;
        string_array(env, types)
    })?;
    Ok(types.unwrap_or_default())
}

/// Horizon saved the world: the mirror's own files beside it (the mods' entities, block
/// entities and saved data) are written now (`HorizonServer.saveAll`).
pub fn save_all() -> Result<(), String> {
    with_server(|env, class| {
        env.call_static_method(class, "saveAll", "()V", &[])?;
        Ok(())
    })?;
    Ok(())
}

/// `/data get block` on a mod's block entity: its NBT as SNBT (`BlockEntity.saveWithFullMetadata`,
/// `HorizonServer.blockEntityData`), `None` where no mod block entity is up at `pos`.
pub fn block_entity_data(dimension: &str, pos: [i32; 3]) -> Result<Option<String>, String> {
    let out = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let value = env
            .call_static_method(
                class,
                "blockEntityData",
                "(Ljava/lang/String;III)Ljava/lang/String;",
                &[
                    JValue::Object(&dimension),
                    JValue::Int(pos[0]),
                    JValue::Int(pos[1]),
                    JValue::Int(pos[2]),
                ],
            )?
            .l()?;
        if value.is_null() {
            return Ok(None);
        }
        Ok(Some(String::from(env.get_string(&JString::from(value))?)))
    })?;
    Ok(out.flatten())
}

/// The mods' entities of every level as JSON (`HorizonEntityRender.snapshot`): per dimension
/// each one's id, type, position, box and the models its renderer submitted, and the model
/// trees not given before. Call it after [`server_tick`].
pub fn entities() -> Result<String, String> {
    let json = with_server(|env, class| {
        let json = env
            .call_static_method(class, "entities", "()Ljava/lang/String;", &[])?
            .l()?;
        Ok(String::from(
            env.get_string(&jni::objects::JString::from(json))?,
        ))
    })?;
    Ok(json.unwrap_or_else(|| "{}".into()))
}

/// Horizon's item entities near its players this tick, for the mods' blocks and machines
/// (`HorizonItems.update`): `{"<dimension>": [[id, x, y, z, vx, vy, vz, on_ground, age,
/// pickup_delay, "stack snbt"], ...]}`.
pub fn horizon_items(json: &str) -> Result<(), String> {
    with_server(|env, class| {
        let json = env.new_string(json)?;
        env.call_static_method(
            class,
            "horizonItems",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&json)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Horizon's mobs near its players this tick, for the mods' mobs (`HorizonNeighbours.update`):
/// `{"<dimension>": [[id, "type", x, y, z, yaw, health], ...]}`.
pub fn horizon_mobs(json: &str) -> Result<(), String> {
    with_server(|env, class| {
        let json = env.new_string(json)?;
        env.call_static_method(
            class,
            "horizonMobs",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&json)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Horizon's mob `attacker` hit the mods' entity `id` for `amount` (a golem's swing, a wolf's
/// bite): a mob attack by its shadow, so the entity strikes back. False when it took nothing.
pub fn hurt_by_horizon(id: i32, attacker: i32, amount: f32) -> Result<bool, String> {
    let hurt = with_server(|env, class| {
        env.call_static_method(
            class,
            "hurtByHorizon",
            "(IIF)Z",
            &[
                JValue::Int(id),
                JValue::Int(attacker),
                JValue::Float(amount),
            ],
        )?
        .z()
    })?;
    Ok(hurt.unwrap_or(false))
}

/// Horizon's projectile of type `kind` (an arrow, a trident, a snowball) hit the mods' entity
/// `id` for `amount`, at `at` flying `velocity`: shot by the player `player` (a UUID, empty
/// when not a player's) or Horizon's mob `mob` (-1 for none), burning or not
/// (`HorizonNeighbours.hurtByProjectile`). False when it took nothing.
#[allow(clippy::too_many_arguments)]
pub fn hurt_by_projectile(
    id: i32,
    kind: &str,
    player: &str,
    mob: i32,
    amount: f32,
    at: [f64; 3],
    velocity: [f64; 3],
    burning: bool,
) -> Result<bool, String> {
    let hurt = with_server(|env, class| {
        let kind = env.new_string(kind)?;
        let player = env.new_string(player)?;
        env.call_static_method(
            class,
            "hurtByProjectile",
            "(ILjava/lang/String;Ljava/lang/String;IFDDDDDDZ)Z",
            &[
                JValue::Int(id),
                JValue::Object(&kind),
                JValue::Object(&player),
                JValue::Int(mob),
                JValue::Float(amount),
                JValue::Double(at[0]),
                JValue::Double(at[1]),
                JValue::Double(at[2]),
                JValue::Double(velocity[0]),
                JValue::Double(velocity[1]),
                JValue::Double(velocity[2]),
                JValue::Bool(u8::from(burning)),
            ],
        )?
        .z()
    })?;
    Ok(hurt.unwrap_or(false))
}

/// A player hit the mod entity with this id (`Player.attack` on the mods' server). False when
/// no mod entity has it.
pub fn attack_entity(uuid: &str, id: i32) -> Result<bool, String> {
    let hit = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "attackEntity",
            "(Ljava/lang/String;I)Z",
            &[JValue::Object(&uuid), JValue::Int(id)],
        )?
        .z()
    })?;
    Ok(hit.unwrap_or(false))
}

/// A player used the mod entity with this id (`Player.interactOn`), at `point` (the world
/// position looked at), with the hand (1: off hand) and the hotbar slot selected.
pub fn interact_entity(
    uuid: &str,
    id: i32,
    off_hand: bool,
    point: [f64; 3],
    selected: i32,
) -> Result<UseResult, String> {
    let result = with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        env.call_static_method(
            class,
            "interactEntity",
            "(Ljava/lang/String;IIDDDI)I",
            &[
                JValue::Object(&uuid),
                JValue::Int(id),
                JValue::Int(i32::from(off_hand)),
                JValue::Double(point[0]),
                JValue::Double(point[1]),
                JValue::Double(point[2]),
                JValue::Int(selected),
            ],
        )?
        .i()
    })?;
    Ok(match result.unwrap_or(0) {
        1 => UseResult::Taken,
        2 => UseResult::TakenSwing,
        _ => UseResult::Pass,
    })
}

/// Whether the JVM and Fabric are up (the mods' entrypoints ran, their data loaded): calls
/// into the platform mod made before then find no server class, for good.
pub fn ready() -> bool {
    crate::get().is_some_and(|b| b.fabric())
}

/// Readies the mods' world generation of `dimension` (`HorizonWorldgen.start`) for a world
/// with this seed and structure setting, in the world folder `world`: whether mods change that dimension's generation (a
/// mod's biomes in its biome source, a mod's features in its biomes, or `horizon_cannot`:
/// structures there, of a mod's own type, that Horizon's generator can't make), so its
/// chunks are generated on the Java side. Call once a dimension, after [`ready`].
pub fn worldgen_start(
    dimension: &str,
    seed: i64,
    structures: bool,
    world: &std::path::Path,
    horizon_cannot: &[(String, String)],
) -> Result<bool, String> {
    let modded = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let world = env.new_string(crate::plain_path(world).to_string_lossy())?;
        // "structure type" for each structure Horizon's generator can't make there.
        let cannot = env.new_object_array(
            horizon_cannot.len() as i32,
            "java/lang/String",
            JObject::null(),
        )?;
        for (i, (structure, kind)) in horizon_cannot.iter().enumerate() {
            let entry = env.new_string(format!("{structure} {kind}"))?;
            env.set_object_array_element(&cannot, i as i32, entry)?;
        }
        env.call_static_method(
            class,
            "worldgenStart",
            "(Ljava/lang/String;JZLjava/lang/String;[Ljava/lang/String;)Z",
            &[
                JValue::Object(&dimension),
                JValue::Long(seed),
                JValue::Bool(structures.into()),
                JValue::Object(&world),
                JValue::Object(&cannot),
            ],
        )?
        .z()
    })?;
    Ok(modded.unwrap_or(false))
}

/// Where a dimension the mods generate starts: ChunkGenerator.getOrigin's chunk and
/// getSpawnHeight, and whether the generator is a mod's own (not vanilla's noise generator).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WorldgenOrigin {
    pub x: i32,
    pub z: i32,
    pub height: i32,
    pub custom: bool,
    /// The generator is vanilla's noise generator or a mod's on it (Grid World's): its land's
    /// shape is the noise's, so Horizon's far terrain stands for it.
    pub noise: bool,
}

/// [`WorldgenOrigin`] of `dimension`; None if the mods don't generate it.
pub fn worldgen_origin(dimension: &str) -> Result<Option<WorldgenOrigin>, String> {
    let found = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env
            .call_static_method(
                class,
                "worldgenOrigin",
                "(Ljava/lang/String;)[I",
                &[JValue::Object(&dimension)],
            )?
            .l()?;
        if array.is_null() {
            return Ok(None);
        }
        let array = jni::objects::JIntArray::from(array);
        let mut out = [0i32; 5];
        if env.get_array_length(&array)? < 5 {
            return Ok(None);
        }
        env.get_int_array_region(&array, 0, &mut out)?;
        Ok(Some(WorldgenOrigin {
            x: out[0],
            z: out[1],
            height: out[2],
            custom: out[3] != 0,
            noise: out[4] != 0,
        }))
    })?;
    Ok(found.flatten())
}

/// A level stem beyond vanilla's three (a mod's or data pack's dimension), as the mirror's
/// registries have it (`HorizonServer.extraLevels`).
#[derive(Clone, Debug, PartialEq)]
pub struct ExtraLevel {
    pub id: String,
    pub dimension_type: String,
    pub min_y: i32,
    pub height: i32,
    pub logical_height: i32,
    pub has_skylight: bool,
    pub has_ceiling: bool,
    pub coordinate_scale: f64,
}

/// The level stems the mirror's registries hold besides the Overworld, the Nether and the
/// End: the levels a server with these mods has (`MinecraftServer.createLevels`).
pub fn extra_levels() -> Result<Vec<ExtraLevel>, String> {
    let json = with_server(|env, class| {
        let value = env
            .call_static_method(class, "extraLevels", "()Ljava/lang/String;", &[])?
            .l()?;
        if value.is_null() {
            return Ok(String::from("[]"));
        }
        Ok(env.get_string(&JString::from(value))?.into())
    })?;
    let Some(json) = json else {
        return Ok(Vec::new());
    };
    let list: Vec<serde_json::Value> = serde_json::from_str(&json).map_err(|e| e.to_string())?;
    Ok(list.iter().filter_map(extra_level).collect())
}

fn extra_level(v: &serde_json::Value) -> Option<ExtraLevel> {
    let int = |k: &str| v.get(k)?.as_i64().and_then(|n| i32::try_from(n).ok());
    let flag = |k: &str| {
        v.get(k)
            .and_then(serde_json::Value::as_bool)
            .unwrap_or(false)
    };
    Some(ExtraLevel {
        id: v.get("id")?.as_str()?.to_owned(),
        dimension_type: v.get("type")?.as_str()?.to_owned(),
        min_y: int("min_y")?,
        height: int("height")?,
        logical_height: int("logical_height")?,
        has_skylight: flag("has_skylight"),
        has_ceiling: flag("has_ceiling"),
        coordinate_scale: v
            .get("coordinate_scale")
            .and_then(serde_json::Value::as_f64)
            .unwrap_or(1.0),
    })
}

/// Whether the mods' data-pack load functions have run on the mirror (or there is nothing to
/// run, or they can't): `HorizonServer.functionsReady`.
pub fn functions_ready() -> bool {
    with_server(|env, class| {
        env.call_static_method(class, "functionsReady", "()Z", &[])?
            .z()
    })
    .ok()
    .flatten()
    .unwrap_or(true)
}

/// Whether the Java mods' client reads its network connection's packets (a mod hooks how a
/// client's `Connection` reads them: ReplayMod, Flashback): `HorizonPacketTap.wanted`. Then
/// the game keeps what the server sends the window's own player and passes it each tick
/// ([`client_packets`]).
pub fn wants_packets() -> bool {
    with_server(|env, class| {
        env.call_static_method(class, "wantsPackets", "()Z", &[])?
            .z()
    })
    .ok()
    .flatten()
    .unwrap_or(false)
}

/// What the server sent player `uuid` (the window's own) since the last call, in order: each
/// packet's body (id and data, as on the wire before framing) after its length as an int.
/// Java runs them through the client connection's own pipeline (`HorizonPacketTap.feed`).
pub fn client_packets(uuid: &str, bodies: &[Vec<u8>]) -> Result<(), String> {
    let mut flat = Vec::with_capacity(bodies.iter().map(|b| b.len() + 4).sum());
    for body in bodies {
        flat.extend_from_slice(&(body.len() as i32).to_be_bytes());
        flat.extend_from_slice(body);
    }
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let array = env.byte_array_from_slice(&flat)?;
        env.call_static_method(
            class,
            "clientPackets",
            "(Ljava/lang/String;[B)V",
            &[JValue::Object(&uuid), JValue::Object(&array)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The integrated world closes: the mods' client connection is closed as a client's is when
/// its server stops, and what mods do then (a recording saved) is waited for, up to 30 s.
pub fn client_closed() -> Result<(), String> {
    with_server(|env, class| {
        env.call_static_method(class, "clientClosed", "()V", &[])?;
        Ok(())
    })?;
    Ok(())
}

/// Brings chunk `x z` of `dimension` to vanilla's TERRAIN status on the Java side and holds
/// it: a token for [`worldgen_finish`] and [`worldgen_forget`], None when it failed.
pub fn worldgen_proto(dimension: &str, x: i32, z: i32) -> Result<Option<i64>, String> {
    let token = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "worldgenProto",
            "(Ljava/lang/String;II)J",
            &[JValue::Object(&dimension), JValue::Int(x), JValue::Int(z)],
        )?
        .j()
    })?;
    Ok(token.filter(|&t| t != 0))
}

/// Runs FEATURES on held chunk `x z` (its eight neighbours held too: features write into them).
pub fn worldgen_decorate(dimension: &str, x: i32, z: i32) -> Result<(), String> {
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "worldgenDecorate",
            "(Ljava/lang/String;II)V",
            &[JValue::Object(&dimension), JValue::Int(x), JValue::Int(z)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Held chunk `x z` as vanilla saves a chunk (uncompressed NBT, a full chunk), let go.
pub fn worldgen_finish(
    dimension: &str,
    x: i32,
    z: i32,
    token: i64,
) -> Result<Option<Vec<u8>>, String> {
    let bytes = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env
            .call_static_method(
                class,
                "worldgenFinish",
                "(Ljava/lang/String;IIJ)[B",
                &[
                    JValue::Object(&dimension),
                    JValue::Int(x),
                    JValue::Int(z),
                    JValue::Long(token),
                ],
            )?
            .l()?;
        if array.is_null() {
            return Ok(None);
        }
        env.convert_byte_array(jni::objects::JByteArray::from(array))
            .map(Some)
    })?;
    Ok(bytes.flatten())
}

/// Lets held chunk `x z` go unfinished.
pub fn worldgen_forget(dimension: &str, x: i32, z: i32, token: i64) -> Result<(), String> {
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "worldgenForget",
            "(Ljava/lang/String;IIJ)V",
            &[
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(z),
                JValue::Long(token),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// Runs a command line as the player for the mods' entities only (`@e` and `@n` selectors
/// find no players), its output not shown: what Horizon's own run of it can't reach.
pub fn run_for_entities(uuid: &str, line: &str) -> Result<(), String> {
    with_server(|env, class| {
        let uuid = env.new_string(uuid)?;
        let line = env.new_string(line)?;
        env.call_static_method(
            class,
            "runForEntities",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&uuid), JValue::Object(&line)],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// The mods' biomes at quart positions of a dimension they generate (`HorizonWorldgen.biomes`):
/// one name a position (None where it has no id), or None if the mods don't generate it.
pub fn worldgen_biomes(
    dimension: &str,
    quarts: &[[i32; 3]],
) -> Result<Option<Vec<Option<String>>>, String> {
    let flat: Vec<i32> = quarts.iter().flatten().copied().collect();
    let names = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        let names = env
            .call_static_method(
                class,
                "worldgenBiomes",
                "(Ljava/lang/String;[I)[Ljava/lang/String;",
                &[JValue::Object(&dimension), JValue::Object(&array)],
            )?
            .l()?;
        optional_string_array(env, names)
    })?;
    Ok(names.flatten())
}

/// The mods' surface rules' top block at each far column's land top (`HorizonWorldgen.lodTops`:
/// x y z of the top block, the height water reaches over it or `i32::MIN`): a state
/// (`name[props]`) or None where the rules leave it. None if the mods don't generate the
/// dimension with a noise generator.
pub fn worldgen_lod_tops(
    dimension: &str,
    tops: &[[i32; 3]],
    water: &[i32],
) -> Result<Option<Vec<Option<String>>>, String> {
    let flat: Vec<i32> = tops.iter().flatten().copied().collect();
    let states = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        let heights = env.new_int_array(water.len() as sys::jsize)?;
        env.set_int_array_region(&heights, 0, water)?;
        let states = env
            .call_static_method(
                class,
                "worldgenLodTops",
                "(Ljava/lang/String;[I[I)[Ljava/lang/String;",
                &[
                    JValue::Object(&dimension),
                    JValue::Object(&array),
                    JValue::Object(&heights),
                ],
            )?
            .l()?;
        optional_string_array(env, states)
    })?;
    Ok(states.flatten())
}

/// What far terrain's noise is for a dimension the mods generate ([`worldgen_lod_noise`]).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LodNoise {
    /// Vanilla's: Horizon's own noise stands for the land.
    Vanilla,
    /// The mods' (Tectonic's), written as a data pack into the folder asked.
    Written,
    /// The mods' but not writable as vanilla data (a mod's own density function type).
    Failed(String),
}

/// Writes the mods' noise of `dimension` into `dir` as a data pack when it isn't vanilla's
/// (`HorizonWorldgen.lodNoise`): far terrain's land from Horizon's noise with it.
pub fn worldgen_lod_noise(dimension: &str, dir: &std::path::Path) -> Result<LodNoise, String> {
    let said = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let dir = env.new_string(dir.to_string_lossy())?;
        let said = env
            .call_static_method(
                class,
                "worldgenLodNoise",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&dimension), JValue::Object(&dir)],
            )?
            .l()?;
        if said.is_null() {
            return Ok(String::from("no answer"));
        }
        Ok(env.get_string(&JString::from(said))?.into())
    })?
    .unwrap_or_else(|| String::from("Java isn't running"));
    Ok(match said.as_str() {
        "vanilla" => LodNoise::Vanilla,
        "" => LodNoise::Written,
        _ => LodNoise::Failed(said),
    })
}

/// The blocks a dimension's surface rules can give and its default block
/// (`HorizonWorldgen.lodMaterials`): a far column's land, under its features. None if the
/// mods don't generate it with a noise generator.
pub fn worldgen_lod_materials(dimension: &str) -> Result<Option<Vec<String>>, String> {
    let names = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let names = env
            .call_static_method(
                class,
                "worldgenLodMaterials",
                "(Ljava/lang/String;)[Ljava/lang/String;",
                &[JValue::Object(&dimension)],
            )?
            .l()?;
        if names.is_null() {
            return Ok(None);
        }
        string_array(env, names).map(Some)
    })?;
    Ok(names.flatten())
}

/// Far columns of a dimension a mod's own generator makes (`HorizonWorldgen.lodColumns`:
/// ChunkGenerator.getBaseColumn), each as runs from the bottom, `(from_y, to_y, state)` with
/// `to_y` exclusive. None if the mods don't generate the dimension.
#[allow(clippy::type_complexity)]
pub fn worldgen_lod_columns(
    dimension: &str,
    columns: &[(i32, i32)],
) -> Result<Option<Vec<Vec<(i32, i32, String)>>>, String> {
    let flat: Vec<i32> = columns.iter().flat_map(|&(x, z)| [x, z]).collect();
    let runs = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env.new_int_array(flat.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, &flat)?;
        let runs = env
            .call_static_method(
                class,
                "worldgenLodColumns",
                "(Ljava/lang/String;[I)[Ljava/lang/String;",
                &[JValue::Object(&dimension), JValue::Object(&array)],
            )?
            .l()?;
        optional_string_array(env, runs)
    })?;
    Ok(runs.flatten().map(|columns| {
        columns
            .into_iter()
            .map(|runs| {
                runs.unwrap_or_default()
                    .split(';')
                    .filter_map(|run| {
                        let mut parts = run.splitn(3, ' ');
                        let from = parts.next()?.parse().ok()?;
                        let to = parts.next()?.parse().ok()?;
                        Some((from, to, parts.next()?.to_owned()))
                    })
                    .collect()
            })
            .collect()
    }))
}

/// The biomes a dimension the mods generate can have; None if they don't generate it.
pub fn worldgen_possible_biomes(dimension: &str) -> Result<Option<Vec<String>>, String> {
    let names = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let names = env
            .call_static_method(
                class,
                "worldgenPossibleBiomes",
                "(Ljava/lang/String;)[Ljava/lang/String;",
                &[JValue::Object(&dimension)],
            )?
            .l()?;
        if names.is_null() {
            return Ok(None);
        }
        string_array(env, names).map(Some)
    })?;
    Ok(names.flatten())
}

/// `/locate biome` with the mods' biome source (`BiomeSource.findClosestBiome3d`) for any of
/// `names`: Some(None) when none is in range, None if the mods don't generate `dimension`.
#[allow(clippy::too_many_arguments)]
pub fn worldgen_find_biome(
    dimension: &str,
    names: &[String],
    origin: [i32; 3],
    radius: i32,
    horizontal: i32,
    vertical: i32,
) -> Result<Option<Option<(String, [i32; 3])>>, String> {
    let found = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let names: Vec<&str> = names.iter().map(String::as_str).collect();
        let names = strings(env, &names)?;
        let found = env
            .call_static_method(
                class,
                "worldgenFindBiome",
                "(Ljava/lang/String;[Ljava/lang/String;IIIIII)[Ljava/lang/String;",
                &[
                    JValue::Object(&dimension),
                    JValue::Object(&names),
                    JValue::Int(origin[0]),
                    JValue::Int(origin[1]),
                    JValue::Int(origin[2]),
                    JValue::Int(radius),
                    JValue::Int(horizontal),
                    JValue::Int(vertical),
                ],
            )?
            .l()?;
        if found.is_null() {
            return Ok(None);
        }
        let found = string_array(env, found)?;
        let parsed = (found.len() == 4)
            .then(|| {
                let n = |i: usize| found[i].parse::<i32>().ok();
                Some((found[0].clone(), [n(1)?, n(2)?, n(3)?]))
            })
            .flatten();
        Ok(Some(parsed))
    })?;
    Ok(found.flatten())
}

/// A Java `String[]` that may be null and hold nulls.
fn optional_string_array(
    env: &mut JNIEnv,
    array: JObject,
) -> jni::errors::Result<Option<Vec<Option<String>>>> {
    if array.is_null() {
        return Ok(None);
    }
    let array = jni::objects::JObjectArray::from(array);
    let len = env.get_array_length(&array)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = env.get_object_array_element(&array, i)?;
        if item.is_null() {
            out.push(None);
        } else {
            let item = jni::objects::JString::from(item);
            out.push(Some(env.get_string(&item)?.into()));
            env.delete_local_ref(item)?;
        }
    }
    Ok(Some(out))
}

/// Whether Horizon's own noise fills `dimension`'s Java protos (`HorizonWorldgen.horizonNoise`:
/// vanilla's noise settings, no mod's terrain-adapting structure).
pub fn worldgen_noise_from_horizon(dimension: &str) -> Result<bool, String> {
    let yes = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        env.call_static_method(
            class,
            "worldgenNoiseFromHorizon",
            "(Ljava/lang/String;)Z",
            &[JValue::Object(&dimension)],
        )?
        .z()
    })?;
    Ok(yes.unwrap_or(false))
}

/// [`worldgen_proto`] with Horizon's noise fill of the chunk (`palette` of states, `blocks`
/// indexing it from the chunk's bottom, `postprocess` x y z triples of fluids to update):
/// the TERRAIN step takes it in place of vanilla's noise sampling.
pub fn worldgen_proto_filled(
    dimension: &str,
    x: i32,
    z: i32,
    palette: &[String],
    blocks: &[i32],
    postprocess: &[i32],
) -> Result<Option<i64>, String> {
    let token = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let names: Vec<&str> = palette.iter().map(String::as_str).collect();
        let names = strings(env, &names)?;
        let array = env.new_int_array(blocks.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, blocks)?;
        let post = env.new_int_array(postprocess.len() as sys::jsize)?;
        env.set_int_array_region(&post, 0, postprocess)?;
        env.call_static_method(
            class,
            "worldgenProtoFilled",
            "(Ljava/lang/String;II[Ljava/lang/String;[I[I)J",
            &[
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(z),
                JValue::Object(&names),
                JValue::Object(&array),
                JValue::Object(&post),
            ],
        )?
        .j()
    })?;
    Ok(token.filter(|&t| t != 0))
}

/// Held chunk `x z` as [`worldgen_finish`] gives it, still held (to save a proto Horizon drops
/// unfinished), with `horizon:decorated` when its own features ran.
pub fn worldgen_snapshot(
    dimension: &str,
    x: i32,
    z: i32,
    token: i64,
) -> Result<Option<Vec<u8>>, String> {
    let bytes = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let array = env
            .call_static_method(
                class,
                "worldgenSnapshot",
                "(Ljava/lang/String;IIJ)[B",
                &[
                    JValue::Object(&dimension),
                    JValue::Int(x),
                    JValue::Int(z),
                    JValue::Long(token),
                ],
            )?
            .l()?;
        if array.is_null() {
            return Ok(None);
        }
        env.convert_byte_array(jni::objects::JByteArray::from(array))
            .map(Some)
    })?;
    Ok(bytes.flatten())
}

/// Puts a saved proto back onto held chunk `x z` (made again to TERRAIN): its blocks
/// (`palette`, `blocks` from `from_y` up), block entities and entities (SNBT), and
/// FEATURES when `decorated`.
#[allow(clippy::too_many_arguments)]
pub fn worldgen_restore(
    dimension: &str,
    x: i32,
    z: i32,
    token: i64,
    palette: &[String],
    from_y: i32,
    blocks: &[i32],
    block_entities: &[String],
    entities: &[String],
    decorated: bool,
) -> Result<(), String> {
    with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        fn refs(v: &[String]) -> Vec<&str> {
            v.iter().map(String::as_str).collect()
        }
        let palette = strings(env, &refs(palette))?;
        let array = env.new_int_array(blocks.len() as sys::jsize)?;
        env.set_int_array_region(&array, 0, blocks)?;
        let block_entities = strings(env, &refs(block_entities))?;
        let entities = strings(env, &refs(entities))?;
        env.call_static_method(
            class,
            "worldgenRestore",
            "(Ljava/lang/String;IIJ[Ljava/lang/String;I[I[Ljava/lang/String;[Ljava/lang/String;Z)V",
            &[
                JValue::Object(&dimension),
                JValue::Int(x),
                JValue::Int(z),
                JValue::Long(token),
                JValue::Object(&palette),
                JValue::Int(from_y),
                JValue::Object(&array),
                JValue::Object(&block_entities),
                JValue::Object(&entities),
                JValue::Bool(decorated.into()),
            ],
        )?;
        Ok(())
    })?;
    Ok(())
}

/// `/locate structure` with the mods' generator (`ChunkGenerator.findNearestMapStructure`) of
/// `selector` (an id or a `#tag`) from `origin` within `radius` chunks: Some(None) when none,
/// None if the mods don't generate `dimension` (or don't know the selector).
pub fn worldgen_locate_structure(
    dimension: &str,
    selector: &str,
    origin: [i32; 3],
    radius: i32,
) -> Result<Option<Option<(String, [i32; 3])>>, String> {
    let found = with_server(|env, class| {
        let dimension = env.new_string(dimension)?;
        let selector = env.new_string(selector)?;
        let found = env
            .call_static_method(
                class,
                "worldgenLocateStructure",
                "(Ljava/lang/String;Ljava/lang/String;IIII)[Ljava/lang/String;",
                &[
                    JValue::Object(&dimension),
                    JValue::Object(&selector),
                    JValue::Int(origin[0]),
                    JValue::Int(origin[1]),
                    JValue::Int(origin[2]),
                    JValue::Int(radius),
                ],
            )?
            .l()?;
        if found.is_null() {
            return Ok(None);
        }
        let found = string_array(env, found)?;
        let parsed = (found.len() == 4)
            .then(|| {
                let n = |i: usize| found[i].parse::<i32>().ok();
                Some((found[0].clone(), [n(1)?, n(2)?, n(3)?]))
            })
            .flatten();
        Ok(Some(parsed))
    })?;
    Ok(found.flatten())
}
