//! Java mods' entities (a Naturalist bear): they live and tick in horizon-jvm's mirrored
//! levels (`HorizonEntities`), and their own renderers, run in the headless client, say each
//! tick what to draw (`HorizonEntityRender`): models as part trees, with their parts' poses,
//! matrices, textures, tints and overlays. The server sends each player those near it on
//! [`CHANNEL`] (game/java_server.rs); this game draws them with its entity pass, and aims at
//! them: an attack or a use of one goes to the server as vanilla's packets do, which hands
//! it to the mods.
#![cfg(feature = "java-mods")]
use super::Game;
use horizon_client::mesher::Vertex;
use horizon_client::mobs::{Part, anim::Poses, render};
use horizon_core::geometry::{Aabb, Vec3};
use horizon_world::registry::BlockStateId;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;

/// Server to client, each tick: `{"entities": [...], "models": {key: tree}}`
/// (`HorizonEntityRender.snapshot`'s entities of the player's level, near it, and the trees
/// this client has not had yet).
pub(super) const CHANNEL: &str = "horizon:java_entities";
/// Mods' own particle types (`HorizonPackets.modParticles`): a level particles packet by the
/// type's name, as JSON.
pub(super) const PARTICLES: &str = "horizon:java_particles";

/// A level particles packet of a mod's particle type.
#[derive(serde::Deserialize)]
struct ModParticles {
    t: String,
    p: [f64; 3],
    d: [f64; 3],
    s: [f64; 3],
    n: i32,
    #[serde(default)]
    f: bool,
}

/// Entity ids the bridge gives Java's entities (`HorizonServer.nextEntityId`), apart from
/// Horizon's own.
pub(super) const FIRST_ID: i32 = 0x7fff_0000;

/// One model an entity's renderer submitted.
#[derive(Clone)]
struct Drawn {
    key: String,
    /// The atlas sprite of its texture.
    sprite: String,
    /// The pose stack's matrix, relative to the entity's position (column-major).
    matrix: [f32; 16],
    /// Java's ARGB tint.
    tint: u32,
    /// Java's packed overlay (OverlayTexture: u white, v red).
    overlay: u32,
    /// Parts' poses that differ from their initial ones, by path.
    poses: HashMap<String, [f32; 9]>,
    hidden: Vec<String>,
    skip: Vec<String>,
}

/// An item its renderer submitted (held, worn on the head): the stack, how it is shown and
/// the pose stack's matrix then, relative to the entity's position.
#[derive(Clone)]
struct Item {
    stack: horizon_client::items::ItemStack,
    context: horizon_assets::item_model::DisplayContext,
    matrix: [f32; 16],
}

/// Custom geometry its renderer wrote (GeckoLib's bones): quads relative to the entity.
#[derive(Clone)]
struct Mesh {
    sprite: String,
    overlay: u32,
    /// Full light (an emissive render type).
    glow: bool,
    /// Per vertex: x, y, z, u, v, nx, ny, nz.
    vertices: Vec<[f32; 8]>,
    /// Per vertex: Java's ARGB.
    colors: Vec<u32>,
    /// A player's model parts the mod draws its own way (3D skin layers' voxels), as
    /// `PlayerModelPart` bits (`mobs::anim`'s): Horizon leaves its own out.
    parts: u8,
}

/// `PlayerModelPart`'s bit of a player model's outer-layer part, by its name in the model.
fn model_part_bit(name: &str) -> u8 {
    match name {
        "jacket" => 1 << 1,
        "left_sleeve" => 1 << 2,
        "right_sleeve" => 1 << 3,
        "left_pants" => 1 << 4,
        "right_pants" => 1 << 5,
        "hat" => 1 << 6,
        _ => 0,
    }
}

#[derive(Clone)]
struct Shown {
    id: i32,
    /// Its type (`ns:name`): an entity's, or a block entity's for `block`.
    kind: String,
    position: [f64; 3],
    width: f64,
    height: f64,
    models: Vec<Drawn>,
    meshes: Vec<Mesh>,
    items: Vec<Item>,
    /// Its name tag (its renderer's `nameTag`, else its custom name), whether it shows when
    /// not looked at, and its height (the renderer's attachment, else the box's top).
    name: Option<horizon_client::text::Text>,
    name_visible: bool,
    name_y: Option<f64>,
    /// The shadow's radius its renderer gives (0: none).
    shadow: f32,
    /// A mod's block entity its renderer draws (a cutting board's item), at its block's
    /// corner: no box to hit, drawn as block entities are (their range, the light above).
    block: bool,
    /// Ticks since its look was new (0: this tick's), and the ticks between the look before it
    /// and this one: the bridge draws a still or far entity every 2nd or 4th tick, and its pose
    /// moves over that whole gap, not in one tick and then held (stepped animations, user
    /// 2026-11-14).
    age: u32,
    span: u32,
}

/// The Java entities this game was last told of, and the tick before (to move between them).
#[derive(Default)]
pub(super) struct JavaEntities {
    trees: HashMap<String, Arc<Part>>,
    now: Vec<Shown>,
    /// Each entity's look before its current one (the pose it moves from).
    before: HashMap<i32, Shown>,
    /// Each entity's place the tick before (it moves every tick, its look may not).
    last_position: HashMap<i32, [f64; 3]>,
    own: Vec<Shown>,
    pub(super) hands: Option<Hands>,
    pub(super) ride: Option<Ride>,
    /// Textures received this payload (atlas key, `{"width","height","rgba"}`), for the
    /// game's Java image slots.
    images: Vec<(String, Value)>,
    /// Other players riding mods' mobs: player entity id → the mob's id.
    riders: HashMap<u32, u32>,
}

/// The atlas key of an entity texture: `ns:textures/entity/x.png` is `ns:entity/x`
/// (`HudAtlas` packs mods' `textures/entity/` under their namespace), vanilla's `entity/x`.
fn sprite_key(id: &str) -> String {
    let (ns, path) = id.split_once(':').unwrap_or(("minecraft", id));
    let path = path
        .trim_start_matches("textures/")
        .trim_end_matches(".png");
    if ns == "minecraft" {
        path.to_owned()
    } else {
        format!("{ns}:{path}")
    }
}

fn floats<const N: usize>(v: &Value) -> Option<[f32; N]> {
    let a = v.as_array()?;
    (a.len() == N).then(|| std::array::from_fn(|i| a[i].as_f64().unwrap_or(0.0) as f32))
}

fn item(v: &Value) -> Option<Item> {
    let (tag, _) = horizon_world::nbt::parse_snbt(v.get("item")?.as_str()?).ok()?;
    Some(Item {
        stack: horizon_items::item_nbt::item_from_nbt(&tag)?,
        context: horizon_assets::item_model::DisplayContext::by_name(v.get("ctx")?.as_str()?)?,
        matrix: floats(v.get("m")?)?,
    })
}

/// The payload as sent (read straight into these, not through a `Value` tree: it comes
/// every tick).
#[derive(serde::Deserialize)]
struct RawPayload {
    #[serde(default)]
    entities: Vec<RawShown>,
    /// What mods draw on this game's own player (their layers: Carry On's carried block),
    /// drawn in third person.
    #[serde(default)]
    own: Vec<RawShown>,
    /// What mods draw in this player's first person, in view space.
    #[serde(default)]
    hands: Option<RawHands>,
    /// The mod's mob this game's player rides, and its seat this tick.
    #[serde(default)]
    ride: Option<Ride>,
    /// Players riding mods' mobs, by their entity id: the mob's id.
    #[serde(default)]
    riders: HashMap<String, i32>,
    #[serde(default)]
    models: HashMap<String, Value>,
}

/// A mod's mob the player rides (`HorizonEntityRender`'s "rides"): the mirror moves it from
/// the player's keys and puts the player on it (`positionRider`); the game sits it there.
#[derive(serde::Deserialize, Clone, Copy)]
pub(super) struct Ride {
    pub(super) id: i32,
    /// The seat: where the mirror's `positionRider` put the player this tick.
    pub(super) p: [f64; 3],
    /// It jumps as a horse (PlayerRideableJumping): the game charges the jump bar.
    #[serde(default)]
    pub(super) jump: bool,
    /// Its health and max health, for the rider's HUD (none: it shows no hearts).
    #[serde(default)]
    pub(super) hp: Option<[f32; 2]>,
}

#[derive(serde::Deserialize)]
struct RawHands {
    #[serde(default)]
    models: Vec<RawModel>,
    #[serde(default)]
    items: Vec<Value>,
    /// Per hand (main, off): mods drew instead of vanilla's arm and item.
    #[serde(default)]
    hide: [bool; 2],
}

/// What mods draw in first person (`HorizonEntityRender.firstPerson`): view space.
#[derive(Clone, Default)]
pub(super) struct Hands {
    models: Vec<Drawn>,
    meshes: Vec<Mesh>,
    items: Vec<Item>,
    pub(super) hide: [bool; 2],
}

#[derive(serde::Deserialize)]
struct RawShown {
    id: i64,
    /// Its type (`ns:name`).
    #[serde(rename = "type", default)]
    kind: String,
    x: f64,
    y: f64,
    z: f64,
    w: Option<f64>,
    h: Option<f64>,
    #[serde(default)]
    block: bool,
    /// A block entity whose renderer draws the whole block (its block's model draws nothing
    /// in Java's world): the block's chunk model is left out.
    #[serde(default)]
    whole: bool,
    #[serde(default)]
    models: Vec<RawModel>,
    #[serde(default)]
    items: Vec<Value>,
    name: Option<Value>,
    #[serde(default)]
    name_visible: bool,
    tag: Option<Value>,
    #[serde(default)]
    tag_always: bool,
    tag_y: Option<f64>,
    #[serde(default)]
    shadow: f32,
    /// It didn't tick (far and idle): it looks as it did, only its place is new.
    #[serde(default)]
    keep: bool,
}

#[derive(serde::Deserialize)]
struct RawModel {
    key: Option<String>,
    tex: Option<String>,
    m: Option<[f32; 16]>,
    tint: Option<i64>,
    overlay: Option<i64>,
    #[serde(default)]
    poses: HashMap<String, [f32; 9]>,
    #[serde(default)]
    hidden: Vec<String>,
    #[serde(default)]
    skip: Vec<String>,
    /// Custom geometry (GeckoLib's): x, y, z, u, v, nx, ny, nz, colour per vertex.
    v: Option<Vec<f64>>,
    /// An emissive render type (eyes, glow layers): drawn at full light.
    #[serde(default)]
    glow: bool,
    /// A player model's parts the mod draws its own way (`HorizonEntityRender.modParts`).
    #[serde(default)]
    parts: Vec<String>,
}

impl RawModel {
    fn drawn(self) -> Option<Drawn> {
        Some(Drawn {
            key: self.key?,
            sprite: sprite_key(self.tex.as_deref()?),
            matrix: self.m?,
            tint: self.tint.unwrap_or(-1) as u32,
            overlay: self.overlay.unwrap_or(0) as u32,
            poses: self.poses,
            hidden: self.hidden,
            skip: self.skip,
        })
    }

    fn mesh(self) -> Option<Mesh> {
        let raw = self.v?;
        let mut vertices = Vec::with_capacity(raw.len() / 9);
        let mut colors = Vec::with_capacity(raw.len() / 9);
        for c in raw.chunks_exact(9) {
            vertices.push(std::array::from_fn(|i| c[i] as f32));
            colors.push(c[8] as u32);
        }
        Some(Mesh {
            sprite: sprite_key(self.tex.as_deref()?),
            overlay: self.overlay.unwrap_or(0) as u32,
            glow: self.glow,
            vertices,
            colors,
            parts: self
                .parts
                .iter()
                .fold(0, |bits, p| bits | model_part_bit(p)),
        })
    }
}

impl RawShown {
    fn shown(self) -> Shown {
        let (tag, visible) = match self.tag {
            Some(tag) => (Some(tag), self.tag_always),
            None => (self.name, self.name_visible),
        };
        let (mut models, mut meshes) = (Vec::new(), Vec::new());
        for m in self.models {
            if m.v.is_some() {
                meshes.extend(m.mesh());
            } else {
                models.extend(m.drawn());
            }
        }
        Shown {
            id: self.id as i32,
            kind: self.kind,
            position: [self.x, self.y, self.z],
            width: self.w.unwrap_or(0.6),
            height: self.h.unwrap_or(1.8),
            models,
            meshes,
            items: self.items.iter().filter_map(item).collect(),
            name: tag.map(|n| {
                horizon_client::component::to_text(&n, &mut |k| {
                    horizon_client::text::Text::literal(k)
                })
            }),
            name_visible: visible,
            name_y: self.tag_y,
            shadow: self.shadow,
            block: self.block,
            age: 0,
            span: 1,
        }
    }
}

/// Packed light at block and sky 15 (LightTexture.FULL_BRIGHT).
const FULL_BRIGHT: u32 = 240 | (240 << 8);

/// Java's ARGB as a vertex colour (ABGR).
fn abgr(argb: u32) -> u32 {
    let (a, r, g, b) = (
        argb >> 24,
        (argb >> 16) & 255,
        (argb >> 8) & 255,
        argb & 255,
    );
    a << 24 | b << 16 | g << 8 | r
}

/// Java's packed overlay as Horizon's overlay code: v 3 (OverlayTexture.RED_OVERLAY_V) is the
/// hurt flash, u the white flash's column (a creeper's swelling).
fn overlay_code(packed: u32) -> u32 {
    let (u, v) = (packed & 0xFFFF, packed >> 16);
    if v < 8 {
        1
    } else if u == 0 {
        0
    } else {
        horizon_client::overlay::WHITE + u.min(15)
    }
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// How far an entity's pose is from the look before its current one to the current one: a look
/// that stands `age` ticks after a gap of `span` ticks moves over the whole gap, `alpha` of the
/// way into this tick, and then holds.
fn pose_progress(age: u32, span: u32, alpha: f32) -> f32 {
    ((age as f32 + alpha) / span.max(1) as f32).min(1.0)
}

impl JavaEntities {
    /// A tick's entities from the server.
    /// The places of block entities drawn whole (`whole`), for the chunk models to leave out.
    fn receive(&mut self, data: &[u8]) -> Vec<[i32; 3]> {
        let Ok(payload) = serde_json::from_slice::<RawPayload>(data) else {
            return Vec::new();
        };
        let whole: Vec<[i32; 3]> = payload
            .entities
            .iter()
            .filter(|raw| raw.whole && !raw.keep)
            .map(|raw| {
                [
                    raw.x.floor() as i32,
                    raw.y.floor() as i32,
                    raw.z.floor() as i32,
                ]
            })
            .collect();
        for (key, tree) in payload.models {
            // A texture Horizon's atlas hasn't (`img:<texture>`: TACZ's gun packs, which it
            // reads at run time): its pixels, for a Java image slot.
            if let Some(texture) = key.strip_prefix("img:") {
                self.images.push((sprite_key(texture), tree));
                continue;
            }
            self.trees.insert(key, Arc::new(Part::parse("root", &tree)));
        }
        let last: HashMap<i32, &Shown> = self.now.iter().map(|s| (s.id, s)).collect();
        let mut new_before: Vec<(i32, Shown)> = Vec::new();
        let now: Vec<Shown> = payload
            .entities
            .into_iter()
            .filter_map(|raw| {
                let id = raw.id as i32;
                if !raw.keep {
                    let mut shown = raw.shown();
                    // A new look: it moves from the last one over the ticks since that was new.
                    if let Some(prev) = last.get(&id) {
                        shown.span = (prev.age + 1).clamp(1, 8);
                        new_before.push((id, (*prev).clone()));
                    }
                    return Some(shown);
                }
                // A resting entity: its last look, where it is now (none yet: not drawn).
                let mut kept = (*last.get(&id)?).clone();
                kept.position = [raw.x, raw.y, raw.z];
                kept.age = kept.age.saturating_add(1);
                Some(kept)
            })
            .collect();
        self.own = payload.own.into_iter().map(RawShown::shown).collect();
        self.ride = payload.ride;
        self.riders = payload
            .riders
            .iter()
            .filter_map(|(player, mob)| Some((player.parse().ok()?, *mob as u32)))
            .collect();
        self.hands = payload.hands.map(|h| {
            // Custom geometry too (TACZ's guns in the hands, its own quads).
            let (mut models, mut meshes) = (Vec::new(), Vec::new());
            for m in h.models {
                if m.v.is_some() {
                    meshes.extend(m.mesh());
                } else {
                    models.extend(m.drawn());
                }
            }
            Hands {
                models,
                meshes,
                items: h.items.iter().filter_map(item).collect(),
                hide: h.hide,
            }
        });
        // Where each was last tick; the looks before stay until a new look replaces them.
        self.last_position = self.now.iter().map(|s| (s.id, s.position)).collect();
        let alive: std::collections::HashSet<i32> = now.iter().map(|s| s.id).collect();
        self.before.retain(|id, _| alive.contains(id));
        for (id, prev) in new_before {
            self.before.insert(id, prev);
        }
        self.now = now;
        whole
    }

    /// Where an entity is `alpha` of the way from the last tick to this one.
    fn position(&self, s: &Shown, alpha: f64) -> [f64; 3] {
        let Some(b) = self.last_position.get(&s.id) else {
            return s.position;
        };
        std::array::from_fn(|i| b[i] + (s.position[i] - b[i]) * alpha)
    }

    fn bounds(&self, s: &Shown, alpha: f64) -> Aabb {
        let [x, y, z] = self.position(s, alpha);
        let half = s.width / 2.0;
        Aabb::new(
            Vec3::new(x - half, y, z - half),
            Vec3::new(x + half, y + s.height, z + half),
        )
    }
}

impl Game<'_> {
    /// The Java mods' fluids as their client models draw them (`horizon_jvm::take_mod_fluids`,
    /// sent when the models load): each liquid block's states drawn with those sprites by the
    /// mesher, not as lava (their template). Only where this process runs the mods (single
    /// player, a LAN host): a server's other players don't get them yet.
    pub(super) fn java_mod_fluids(&mut self) {
        let Some(json) = horizon_jvm::take_mod_fluids() else {
            return;
        };
        let Ok(looks) = serde_json::from_str::<HashMap<String, serde_json::Value>>(&json) else {
            return;
        };
        let mut by_state = HashMap::new();
        let registry = &self.pack.assets.registry;
        for id in 0..registry.len() as u32 {
            let Some(state) = registry.state(BlockStateId(id)) else {
                continue;
            };
            let Some(look) = looks.get(&state.name) else {
                continue;
            };
            let text = |k: &str| {
                look.get(k)
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_owned()
            };
            by_state.insert(
                id,
                horizon_client::mesher::ModFluidLook {
                    still: text("still"),
                    flow: text("flow"),
                    tint: look.get("tint").and_then(|v| v.as_i64()).unwrap_or(-1) as u32,
                    translucent: look
                        .get("translucent")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false),
                },
            );
        }
        log::info!(
            "Java mods: {} fluid(s) drawn with their own sprites ({} states)",
            looks.len(),
            by_state.len()
        );
        if horizon_client::mesher::set_mod_fluids(by_state) {
            self.all_sections_changed();
        }
    }

    /// The Java mods' items drawn by their own item model types (`horizon_jvm::take_mod_item_models`,
    /// sent when the models load): Horizon's definitions of them draw nothing (Create's
    /// wrench), so they take the quads Java's models made. Only where this process runs the
    /// mods (single player, a LAN host), as the fluids.
    pub(super) fn java_mod_item_models(&mut self) {
        let Some(json) = horizon_jvm::take_mod_item_models() else {
            return;
        };
        let models = horizon_client::item_render::java_item_models_from_json(&json);
        log::info!(
            "Java mods: {} item(s) drawn with their Java models' quads",
            models.len()
        );
        horizon_client::item_render::set_java_item_models(models);
        self.pack.item_renderer.forget_drawn();
    }

    /// The server's Java entities of this tick ([`CHANNEL`]).
    pub(super) fn remote_java_entities(&mut self, data: &[u8]) {
        let _timed = super::java_profile::Timed::named("(Rust) entities: the player's game reads");
        let whole = self.java.entities.receive(data);
        for (name, mut image) in std::mem::take(&mut self.java.entities.images) {
            image["name"] = Value::from(name);
            self.store_java_image(&image);
        }
        // Blocks their renderers draw whole (Create's shafts and cogs, turning): their chunk
        // models go, as in Java, where those models have no geometry in a world.
        let mut changed = false;
        for [x, y, z] in whole {
            let state = self.world.block(x, y, z);
            if state.0 != 0 {
                changed |= horizon_client::mesher::hide_model_drawn_by_block_entity(state);
            }
        }
        if changed {
            self.all_sections_changed();
        }
        self.java_mod_drawn_parts();
        // Other players on mods' mobs sit there (HumanoidModel's riding pose), as a
        // passengers packet would put them on a vehicle of Horizon's.
        let riders = &self.java.entities.riders;
        for m in &mut self.mobs {
            if let Some(&mob) = riders.get(&m.id) {
                m.extra.mount = Some(mob);
                m.extra.passenger = true;
            } else if m.extra.mount.is_some_and(|v| v >= FIRST_ID as u32) {
                m.extra.mount = None;
                m.extra.passenger = false;
            }
        }
    }

    /// The players' outer-layer parts mods draw their own way (3D skin layers' voxels), which
    /// Horizon then leaves out of its own model of them: those of meshes it can draw (their
    /// skin in the atlas), so a player whose skin this game hasn't keeps Horizon's.
    fn java_mod_drawn_parts(&mut self) {
        let drawn = |s: &Shown| {
            s.meshes
                .iter()
                .filter(|m| m.parts != 0 && self.java_sprite(&m.sprite).is_some())
                .fold(0u8, |bits, m| bits | m.parts)
        };
        let own: u8 = self
            .java
            .entities
            .own
            .iter()
            .map(drawn)
            .fold(0, |a, b| a | b);
        // A mirrored player's entry: 0x7ff00000 and its entity id's low bits
        // (HorizonEntityRender.player).
        let others: HashMap<u32, u8> = self
            .java
            .entities
            .now
            .iter()
            .filter(|s| s.kind == "minecraft:player")
            .map(|s| (s.id as u32 & 0xfffff, drawn(s)))
            .filter(|(_, bits)| *bits != 0)
            .collect();
        let mut seen = own;
        if let Some(m) = self.player_model.as_mut() {
            m.extra.mod_drawn_parts = own;
        }
        for m in &mut self.mobs {
            if &*m.kind.id == "minecraft:player" {
                let bits = others.get(&(m.id & 0xfffff)).copied().unwrap_or(0);
                m.extra.mod_drawn_parts = bits;
                seen |= bits;
            }
        }
        static LOGGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        if seen != 0 && !LOGGED.swap(true, std::sync::atomic::Ordering::Relaxed) {
            let names: Vec<&str> = [
                "jacket",
                "left_sleeve",
                "right_sleeve",
                "left_pants",
                "right_pants",
                "hat",
            ]
            .into_iter()
            .filter(|n| seen & model_part_bit(n) != 0)
            .collect();
            log::info!(
                "Java mods: Horizon leaves out its own layers mods draw on players: {}",
                names.join(" ")
            );
        }
    }

    /// The Java entities for the entity pass: each model its renderer submitted, posed as it
    /// posed it, between the last two ticks.
    pub(super) fn java_entity_geometry(
        &self,
        camera: glam::DVec3,
        alpha: f64,
        out: &mut Vec<Vertex>,
    ) {
        let java = &self.java.entities;
        if java.now.is_empty() && java.own.is_empty() {
            return;
        }
        let t = alpha as f32;
        for s in &java.now {
            let at = java.position(s, alpha);
            let d = glam::DVec3::from_array(at) - camera;
            // Entity.shouldRenderAtSqrDistance: 64 times the box's mean edge; a block entity:
            // BlockEntityRenderer.getViewDistance's 64 blocks.
            let range = if s.block {
                64.0
            } else {
                (s.width * 2.0 + s.height) / 3.0 * 64.0 * self.view_scale()
            };
            if d.length_squared() >= range * range {
                continue;
            }
            // A block entity's light: the block above it (a full block's own is dark inside,
            // and what its renderer draws sits on top: a stove's grill).
            let lit_y = if s.block {
                at[1] + 1.0
            } else {
                at[1] + s.height * 0.85
            };
            let light = self.world.light(
                at[0].floor() as i32,
                lit_y.floor() as i32,
                at[2].floor() as i32,
            );
            let packed = (u32::from(light >> 4) * 16) | ((u32::from(light & 15) * 16) << 8);
            let before = java.before.get(&s.id);
            let base = glam::Mat4::from_translation(d.as_vec3());
            let pose_t = pose_progress(s.age, s.span, t);
            self.draw_look(
                s.id, &s.models, &s.meshes, &s.items, before, base, packed, pose_t, out,
            );
        }
        // What mods draw on this game's own player, seen from outside (third person): at the
        // player as drawn.
        if !self.camera_type.first_person()
            && let Some(m) = self.player_model.as_ref()
        {
            let at = glam::DVec3::new(m.position.x, m.position.y, m.position.z);
            let light = self.world.light(
                at.x.floor() as i32,
                (at.y + 1.62).floor() as i32,
                at.z.floor() as i32,
            );
            let packed = (u32::from(light >> 4) * 16) | ((u32::from(light & 15) * 16) << 8);
            for s in &java.own {
                let base = glam::Mat4::from_translation((at - camera).as_vec3());
                self.draw_look(
                    s.id, &s.models, &s.meshes, &s.items, None, base, packed, t, out,
                );
            }
        }
    }

    /// Where a Java model's texture is in the GUI atlas: its sprite, else the part of the Java
    /// image slot its pixels were stored in (a texture the server sent: `img:` trees).
    fn java_sprite(&self, key: &str) -> Option<horizon_client::hud::Rect> {
        let atlas = &self.gui.atlas;
        if let Some(r) = atlas.sprites.get(key) {
            return Some(*r);
        }
        // A game's own offline skin (`HorizonSkins.ownId`: `horizon:offline_skin/<uuid>`): this
        // game's launcher skin when the uuid is its player's, in its skin slot. A downloaded
        // skin (`skins/<sha1>`) is in the atlas under that key already, once Horizon draws it.
        if let Some(uuid) = key.strip_prefix("horizon:offline_skin/") {
            let own = self.remote.as_ref().map(|r| r.profile())?;
            return (uuid.replace('-', "") == format!("{own:032x}"))
                .then(|| atlas.sprites.get("horizon/offline_skin").copied())
                .flatten();
        }
        let (slot, w, h) = self.java_image_slot(key)?;
        let (sprite, side) = horizon_client::hud::java_image_slot(slot);
        let mut r = *atlas.sprites.get(&sprite)?;
        r.u1 = r.u0 + (r.u1 - r.u0) * w.min(side) as f32 / side as f32;
        r.v1 = r.v0 + (r.v1 - r.v0) * h.min(side) as f32 / side as f32;
        Some(r)
    }

    /// What mods draw in first person (Carry On's carried block or mob in the hands): view
    /// space through `to_world` (the hands' own transform, game.rs `hand_geometry`). Returns
    /// the hands they draw instead of vanilla's arm and item (vanilla's draw skips them).
    pub(super) fn java_hand_geometry(
        &self,
        to_world: glam::Mat4,
        packed: u32,
        out: &mut Vec<Vertex>,
    ) -> [bool; 2] {
        if let Some(h) = &self.java.entities.hands {
            self.draw_look(
                -1, &h.models, &h.meshes, &h.items, None, to_world, packed, 1.0, out,
            );
        }
        self.java
            .entities
            .hands
            .as_ref()
            .map_or([false; 2], |h| h.hide)
    }

    /// One entity's look (its models posed between the last two ticks, its custom geometry and
    /// items), with `base` before each matrix.
    #[allow(clippy::too_many_arguments)]
    fn draw_look(
        &self,
        id: i32,
        models: &[Drawn],
        meshes: &[Mesh],
        items: &[Item],
        before: Option<&Shown>,
        base: glam::Mat4,
        packed: u32,
        t: f32,
        out: &mut Vec<Vertex>,
    ) {
        let java = &self.java.entities;
        for (i, m) in models.iter().enumerate() {
            let Some(tree) = java.trees.get(&m.key) else {
                continue;
            };
            let Some(r) = self.java_sprite(&m.sprite) else {
                continue;
            };
            // The same model the tick before: its matrix and poses, to move from.
            let last = before
                .and_then(|b| b.models.get(i))
                .filter(|b| b.key == m.key);
            let matrix: [f32; 16] = match last {
                Some(b) => std::array::from_fn(|k| lerp(b.matrix[k], m.matrix[k], t)),
                None => m.matrix,
            };
            let mut poses = Poses::new(tree);
            for (path, pose) in &m.poses {
                let from = last.and_then(|b| b.poses.get(path));
                *poses.get(path) = match from {
                    Some(f) => std::array::from_fn(|k| lerp(f[k], pose[k], t)),
                    None => *pose,
                };
            }
            // A part posed last tick and back to its initial pose now.
            if let Some(b) = last {
                for (path, f) in &b.poses {
                    if !m.poses.contains_key(path) && poses.has(path) {
                        let initial = *poses.get(path);
                        *poses.get(path) = std::array::from_fn(|k| lerp(f[k], initial[k], t));
                    }
                }
            }
            for path in &m.hidden {
                poses.visible(path, false);
            }
            for path in &m.skip {
                poses.skip_draw(path);
            }
            render::draw(
                tree,
                base * glam::Mat4::from_cols_array(&matrix),
                &poses,
                [r.u0, r.v0, r.u1, r.v1],
                abgr(m.tint),
                packed,
                overlay_code(m.overlay),
                out,
            );
        }
        // Custom geometry (GeckoLib's): its quads as written, moved with the entity
        // between ticks.
        for (i, m) in meshes.iter().enumerate() {
            let Some(r) = self.java_sprite(&m.sprite) else {
                continue;
            };
            let overlay = overlay_code(m.overlay);
            // The same mesh the look before (a GeckoLib model's bones, vertex for vertex): its
            // vertices move between the two, as a vanilla model's parts do, instead of jumping
            // once a tick.
            let from = before
                .and_then(|b| b.meshes.get(i))
                .filter(|b| b.sprite == m.sprite && b.vertices.len() == m.vertices.len());
            for (k, (v, &argb)) in m.vertices.iter().zip(&m.colors).enumerate() {
                let v = match from {
                    Some(b) => {
                        let f = &b.vertices[k];
                        std::array::from_fn::<f32, 8, _>(|c| {
                            if (3..5).contains(&c) { v[c] } else { lerp(f[c], v[c], t) }
                        })
                    }
                    None => *v,
                };
                let n = [v[5], v[6], v[7]].map(|c| (c.clamp(-1.0, 1.0) * 127.0) as i8);
                out.push(Vertex {
                    pos: base
                        .transform_point3(glam::Vec3::new(v[0], v[1], v[2]))
                        .to_array(),
                    uv: [r.u0 + (r.u1 - r.u0) * v[3], r.v0 + (r.v1 - r.v0) * v[4]],
                    layer: overlay,
                    color: abgr(argb),
                    light: if m.glow { FULL_BRIGHT } else { packed },
                    normal: [n[0], n[1], n[2], -127],
                    glint_uv: [0.0, 0.0],
                });
            }
        }
        // ItemInHandLayer and the like: the items, as Horizon's item renderer draws them
        // (ItemModelResolver.updateForLiving's seed: the entity id plus the context).
        for item in items {
            let seed = id.wrapping_add(item.context as i32);
            let state = self.resolve_item(&item.stack, item.context, None, seed);
            let pose = base * glam::Mat4::from_cols_array(&item.matrix);
            self.submit_item_world(&state, pose, packed, out);
        }
    }

    /// Where each Java entity's shadow is (between the last two ticks), its radius and its
    /// strength (1).
    pub(super) fn java_entity_shadows(&self, alpha: f64) -> Vec<(glam::DVec3, f32, f32)> {
        let java = &self.java.entities;
        java.now
            .iter()
            .filter(|s| s.shadow > 0.0 && !s.block)
            .map(|s| {
                (
                    glam::DVec3::from_array(java.position(s, alpha)),
                    s.shadow,
                    1.0,
                )
            })
            .collect()
    }

    /// The Java entities with a name tag: id, position, the tag's height, the name and whether
    /// it shows when not looked at.
    pub(super) fn java_entity_names(
        &self,
        alpha: f64,
    ) -> Vec<(i32, glam::DVec3, f64, horizon_client::text::Text, bool)> {
        let java = &self.java.entities;
        java.now
            .iter()
            .filter_map(|s| {
                let name = s.name.clone()?;
                let at = glam::DVec3::from_array(java.position(s, alpha));
                Some((s.id, at, s.name_y.unwrap_or(s.height), name, s.name_visible))
            })
            .collect()
    }

    /// The Java entity the crosshair is on, nearer than `reach`: its id and distance.
    /// The Java mods' entities shown now (not block entities): id, type, position, for
    /// horizon-ctl's `entities`.
    pub(super) fn java_entity_list(&self) -> Vec<(i32, &str, [f64; 3])> {
        self.java
            .entities
            .now
            .iter()
            .filter(|e| !e.block)
            .map(|e| (e.id, e.kind.as_str(), e.position))
            .collect()
    }

    pub(super) fn java_entity_hit(&self) -> Option<(i32, f64)> {
        let eye = self.eye_position()?;
        let dir = super::view_vector(self.yaw, self.pitch);
        let reach: f64 = if self.creative { 5.0 } else { 3.0 };
        let java = &self.java.entities;
        // Not the mob it rides (ProjectileUtil.getEntityHitResult skips the shooter's vehicle).
        let riding = java.ride.map(|r| r.id);
        java.now
            .iter()
            .filter(|s| !s.block && Some(s.id) != riding)
            .filter_map(|s| Some((s.id, super::ray_box(eye, dir, reach, java.bounds(s, 1.0))?)))
            .min_by(|a, b| a.1.total_cmp(&b.1))
    }

    /// Where on the Java entity with this id the crosshair is, relative to its position (the
    /// interact packet's location).
    pub(super) fn java_entity_location(&self, id: i32, distance: f64) -> [f64; 3] {
        let (Some(eye), Some(s)) = (
            self.eye_position(),
            self.java.entities.now.iter().find(|s| s.id == id),
        ) else {
            return [0.0; 3];
        };
        let dir = super::view_vector(self.yaw, self.pitch);
        std::array::from_fn(|i| eye[i] + dir[i] * distance - s.position[i])
    }
}

impl Game<'_> {
    /// `ClientPacketListener.handleParticleEvent` for a mod's particle type: drawn with its
    /// sprites (`ParticleEngine::add_mod`), spread as vanilla spreads a packet's particles.
    pub(super) fn remote_java_particles(&mut self, data: &[u8]) {
        use horizon_core::random::RandomSource;
        let Ok(p) = serde_json::from_slice::<ModParticles>(data) else {
            return;
        };
        if !self.particles.has_mod(&p.t) {
            return;
        }
        if p.n == 0 {
            let v = std::array::from_fn(|i| p.s[i] * p.d[i]);
            self.particles.add_mod(&p.t, p.p, v, p.f);
            return;
        }
        for _ in 0..p.n.min(4096) {
            let r = self.particles.random();
            let at: [f64; 3] = std::array::from_fn(|i| p.p[i] + r.next_gaussian() * p.d[i]);
            let v: [f64; 3] = std::array::from_fn(|i| r.next_gaussian() * p.s[i]);
            self.particles.add_mod(&p.t, at, v, p.f);
        }
    }
}

/// Items whose use is aimed past an entity, never taken by one (vanilla's `Mob.interact` passes
/// on them): a right-click on a mod's entity with one also uses it (a bow drawn at a mod mob).
pub(super) fn used_past_entities(item: &str) -> bool {
    matches!(
        item,
        "minecraft:bow"
            | "minecraft:crossbow"
            | "minecraft:trident"
            | "minecraft:snowball"
            | "minecraft:egg"
            | "minecraft:blue_egg"
            | "minecraft:brown_egg"
            | "minecraft:ender_pearl"
            | "minecraft:splash_potion"
            | "minecraft:lingering_potion"
            | "minecraft:wind_charge"
            | "minecraft:fishing_rod"
            | "minecraft:shield"
            | "minecraft:spyglass"
            | "minecraft:goat_horn"
            | "minecraft:experience_bottle"
    )
}

/// One of the mods' living entities as Horizon's mobs see it (`HorizonNeighbours.seen`): its id,
/// the vanilla type they take it for, where it stands, its box, health and facing, and whether it
/// is an `Enemy` (what golems attack).
#[derive(serde::Deserialize, Clone)]
pub(super) struct Neighbour(
    pub(super) u32,
    pub(super) String,
    pub(super) f64,
    pub(super) f64,
    pub(super) f64,
    pub(super) f32,
    pub(super) f32,
    pub(super) f32,
    pub(super) f32,
    pub(super) u8,
    /// The UUID of the player riding it, empty when none: a player's own shots pass their
    /// mount (`Projectile.canHitEntity`: not the owner's vehicle).
    #[serde(default)]
    pub(super) String,
);

/// The mods' living entities by dimension, from the last snapshot (game/java_server.rs's
/// `send_java_entities`), for Horizon's mobs' ticks.
pub(super) static NEIGHBOURS: std::sync::Mutex<Option<HashMap<String, Vec<Neighbour>>>> =
    std::sync::Mutex::new(None);

impl Game<'_> {
    /// What Horizon's mobs see of the mods' living entities in this level: each as the vanilla
    /// type it is taken for (`HorizonNeighbours.like`), with its own box, place and health. Its
    /// id is the bridge's, so a target or a hurt-by of one names it; a hit goes to Java
    /// (`java_mob_hits_entity`).
    pub(super) fn java_neighbours_seen(&self) -> Vec<horizon_client::mobs::Seen> {
        let neighbours = NEIGHBOURS.lock().unwrap_or_else(|e| e.into_inner());
        let Some(list) = neighbours.as_ref().and_then(|n| n.get(self.dim.id())) else {
            return Vec::new();
        };
        let uuid = self.level.player_uuid;
        list.iter()
            .filter_map(|n| {
                let kind = self.res.mob_types().get(&n.1).cloned()?;
                let at = Vec3::new(n.2, n.3, n.4);
                let mut m = horizon_client::mobs::Mob::new(n.0, kind, at, n.8);
                m.health = n.7;
                let mut seen = m.seen(uuid);
                let half = f64::from(n.5) / 2.0;
                seen.bounds = Aabb::new(
                    Vec3::new(at.x - half, at.y, at.z - half),
                    Vec3::new(at.x + half, at.y + f64::from(n.6), at.z + half),
                );
                seen.eye_y = at.y + f64::from(n.6) * 0.85;
                seen.hostile = n.9 != 0;
                Some(seen)
            })
            .collect()
    }

    /// Horizon's mob hit `target` (`MobEvent::AttackMob`): one of the mods' entities takes it
    /// on the mirror (`HorizonNeighbours.hurtByHorizon`). None when `target` isn't theirs.
    pub(super) fn java_mob_hits_entity(
        &mut self,
        target: u32,
        amount: f32,
        source: horizon_client::mobs::Damage,
    ) -> Option<bool> {
        if target < FIRST_ID as u32 {
            return None;
        }
        let attacker = match source.attacker {
            Some(horizon_client::mobs::Who::Mob(id)) => id as i32,
            _ => -1,
        };
        let hurt = self.with_java_world(|| {
            horizon_jvm::events::hurt_by_horizon(target as i32, attacker, amount)
        });
        Some(hurt.unwrap_or_else(|e| {
            log::warn!("Java mods: a mob's hit on a mod entity failed: {e}");
            false
        }))
    }

    /// The mods' living entities in this level as Horizon's projectiles meet them (an arrow's,
    /// a trident's, a snowball's targets): id and box. Those a player rides are left out (a
    /// rider's shots pass their own mount; the rider is hit instead).
    pub(super) fn java_projectile_targets(&self) -> Vec<(horizon_client::mobs::Who, Aabb)> {
        let neighbours = NEIGHBOURS.lock().unwrap_or_else(|e| e.into_inner());
        let Some(list) = neighbours.as_ref().and_then(|n| n.get(self.dim.id())) else {
            return Vec::new();
        };
        list.iter()
            .filter(|n| n.10.is_empty())
            .map(|n| {
                let (at, half) = (Vec3::new(n.2, n.3, n.4), f64::from(n.5) / 2.0);
                let b = Aabb::new(
                    Vec3::new(at.x - half, at.y, at.z - half),
                    Vec3::new(at.x + half, at.y + f64::from(n.6), at.z + half),
                );
                (horizon_client::mobs::Who::Mob(n.0), b)
            })
            .collect()
    }

    /// Whether `id` is one of the mods' living entities in this level.
    pub(super) fn is_java_neighbour(&self, id: u32) -> bool {
        if id < FIRST_ID as u32 {
            return false;
        }
        let neighbours = NEIGHBOURS.lock().unwrap_or_else(|e| e.into_inner());
        neighbours
            .as_ref()
            .and_then(|n| n.get(self.dim.id()))
            .is_some_and(|l| l.iter().any(|n| n.0 == id))
    }

    /// Horizon's projectile `p` hit the mods' entity `id` for `amount`
    /// (`Projectile.onHitEntity`): the mirror hurts it with the projectile's damage type, by a
    /// projectile of the same type standing where this one is (its knockback follows the
    /// flight) and owned by the shooter (a mirrored player, or a Horizon mob's shadow), so the
    /// entity strikes back at the shooter. False when it took nothing.
    pub(super) fn java_projectile_hits_entity(
        &mut self,
        p: &horizon_client::projectiles::Projectile,
        id: u32,
        amount: f32,
    ) -> bool {
        use horizon_client::mobs::Who;
        use horizon_client::projectiles::ProjectileKind as K;
        let kind = match p.kind {
            K::Arrow { .. }
                if p.arrow
                    .as_ref()
                    .is_some_and(|a| &*a.pickup_item.item == "minecraft:spectral_arrow") =>
            {
                "minecraft:spectral_arrow"
            }
            _ => p.kind.id(),
        };
        let (player, mob) = match p.owner {
            Some(Who::Player) => {
                let u = horizon_client::lan::playerdata::ints_uuid(
                    p.owner_uuid.unwrap_or_else(|| self.acting_player_uuid()),
                );
                let h = format!("{u:032x}");
                let uuid = format!(
                    "{}-{}-{}-{}-{}",
                    &h[..8],
                    &h[8..12],
                    &h[12..16],
                    &h[16..20],
                    &h[20..]
                );
                (uuid, -1)
            }
            Some(Who::Mob(m)) => (String::new(), m as i32),
            None => (String::new(), -1),
        };
        let fire = p.arrow.as_ref().is_some_and(|a| a.on_fire());
        let at = [p.position.x, p.position.y, p.position.z];
        let v = [p.velocity.x, p.velocity.y, p.velocity.z];
        let hurt = self.with_java_world(|| {
            horizon_jvm::events::hurt_by_projectile(
                id as i32, kind, &player, mob, amount, at, v, fire,
            )
        });
        hurt.unwrap_or_else(|e| {
            log::warn!("Java mods: a projectile's hit on a mod entity failed: {e}");
            false
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pose_redrawn_every_few_ticks_moves_over_the_whole_gap() {
        // Redrawn every tick: the usual lerp over the tick.
        assert_eq!(pose_progress(0, 1, 0.5), 0.5);
        // Redrawn every 4th tick: a quarter of the way per tick, never a jump then a hold.
        assert_eq!(pose_progress(0, 4, 0.0), 0.0);
        assert_eq!(pose_progress(1, 4, 0.0), 0.25);
        assert_eq!(pose_progress(3, 4, 1.0), 1.0);
        // Kept longer than the gap (a resting mob): it holds its pose.
        assert_eq!(pose_progress(9, 4, 0.5), 1.0);
        // A broken span counts as one tick.
        assert_eq!(pose_progress(0, 0, 0.5), 0.5);
    }
}
