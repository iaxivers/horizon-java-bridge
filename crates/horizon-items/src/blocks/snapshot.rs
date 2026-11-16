//! A disposable binary snapshot of the decoded block facts (and the gameplay projection the
//! behaviour stage reads), beside the game's other caches. Decoding gameplay-facts.json (3.3 GB
//! with 184 mods) and block-behaviours.json took 13 s of every start (UMP startup trace); the
//! snapshot loads in a fraction. The JSON stays authoritative: a snapshot made from other files
//! (their sizes and times), by another decoder (this module's and the decoder's sources) or for
//! another registry is no snapshot, and anything unreadable falls back to the JSON. (UMP, 2026-11-14)
use super::*;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Write;

const MAGIC: &[u8; 8] = b"HZFACTS\0";
const VERSION: u32 = 1;
const FILE_NAME: &str = "block-facts-v1.bin";

#[derive(Serialize, Deserialize)]
struct Snapshot {
    strings: Vec<String>,
    shapes: Vec<Vec<[f64; 6]>>,
    chains: Vec<Vec<u32>>,
    states: Vec<State>,
    woods: Vec<(u32, u32)>,
    air: u32,
    projection: Vec<Projection>,
}

#[derive(Serialize, Deserialize)]
struct State {
    name: u32,
    emission: u8,
    dampening: u8,
    flags: u32,
    hardness: f32,
    render_shape: u8,
    friction: f32,
    explosion_resistance: f32,
    item: u32,
    sound_volume: f32,
    sound_pitch: f32,
    sounds: [u32; 5],
    fluid: u8,
    fluid_level: u8,
    sturdy_full: u8,
    classes: u32,
    outline: u32,
    outline_edges: u32,
    map_color: u8,
}

#[derive(Serialize, Deserialize)]
struct Projection {
    flags: u16,
    emission: u64,
    dampening: u64,
    hardness: f64,
    push: u32,
    volume: f64,
    pitch: f64,
    place: u32,
    break_sound: u32,
    fall: u32,
}

/// The source files the facts are decoded from.
const SOURCES: [&str; 3] = ["gameplay-facts.json", "block-behaviours.json", "facts.json"];

fn fingerprint(root: &Path, registry: &BlockRegistry) -> Option<[u8; 32]> {
    let mut hasher = Sha256::new();
    hasher.update(b"horizon block facts 1");
    // The decoder: a change to how facts are read never reads an older snapshot.
    hasher.update(include_str!("../blocks.rs").as_bytes());
    hasher.update(include_str!("decode.rs").as_bytes());
    hasher.update(include_str!("snapshot.rs").as_bytes());
    for name in SOURCES {
        let meta = fs::metadata(root.join(name)).ok()?;
        let modified = meta
            .modified()
            .ok()?
            .duration_since(std::time::UNIX_EPOCH)
            .ok()?
            .as_nanos();
        hasher.update(name.as_bytes());
        hasher.update(meta.len().to_le_bytes());
        hasher.update(modified.to_le_bytes());
    }
    hasher.update((registry.len() as u64).to_le_bytes());
    Some(hasher.finalize().into())
}

#[derive(Default)]
struct Strings {
    list: Vec<String>,
    index: HashMap<Arc<str>, u32>,
}
impl Strings {
    fn id(&mut self, s: &Arc<str>) -> u32 {
        if let Some(&id) = self.index.get(s) {
            return id;
        }
        let id = self.list.len() as u32;
        self.list.push(s.to_string());
        self.index.insert(s.clone(), id);
        id
    }
}

/// Shared slices (outlines, class chains) by identity, as the decoder interned them.
struct Shared<T: Clone> {
    list: Vec<T>,
    index: HashMap<usize, u32>,
}
impl<T: Clone> Default for Shared<T> {
    fn default() -> Self {
        Self { list: Vec::new(), index: HashMap::new() }
    }
}
impl<T: Clone> Shared<T> {
    fn id(&mut self, key: usize, value: impl FnOnce() -> T) -> u32 {
        if let Some(&id) = self.index.get(&key) {
            return id;
        }
        let id = self.list.len() as u32;
        self.list.push(value());
        self.index.insert(key, id);
        id
    }
}

fn state_flags(s: &StateFacts) -> u32 {
    [
        s.skylight_down,
        s.shape_occlusion,
        s.can_occlude,
        s.solid_render,
        s.requires_tool,
        s.replaceable,
        s.random_ticks,
        s.block_entity,
        s.full_collision,
        s.fluid_falling,
        s.waterlogged,
        s.random_offset,
        s.outline_offset,
        s.legacy_solid,
    ]
    .iter()
    .enumerate()
    .fold(0, |f, (i, &b)| f | (u32::from(b) << i))
}

fn capture(facts: &BlockFacts, projection: &[GameplayProjection]) -> Snapshot {
    let mut strings = Strings::default();
    let mut shapes: Shared<Vec<[f64; 6]>> = Shared::default();
    let mut chains: Shared<Vec<Arc<str>>> = Shared::default();
    let states = facts
        .states
        .iter()
        .map(|s| State {
            name: strings.id(&s.name),
            emission: s.emission,
            dampening: s.dampening,
            flags: state_flags(s),
            hardness: s.hardness,
            render_shape: match s.render_shape {
                RenderShape::Model => 0,
                RenderShape::Invisible => 1,
                RenderShape::Animated => 2,
            },
            friction: s.friction,
            explosion_resistance: s.explosion_resistance,
            item: strings.id(&s.item),
            sound_volume: s.sounds.volume,
            sound_pitch: s.sounds.pitch,
            sounds: [
                strings.id(&s.sounds.break_sound),
                strings.id(&s.sounds.step),
                strings.id(&s.sounds.place),
                strings.id(&s.sounds.hit),
                strings.id(&s.sounds.fall),
            ],
            fluid: match s.fluid {
                Fluid::None => 0,
                Fluid::Water => 1,
                Fluid::Lava => 2,
            },
            fluid_level: s.fluid_level,
            sturdy_full: s.sturdy_full,
            classes: chains.id(Arc::as_ptr(&s.classes) as *const () as usize, || s.classes.to_vec()),
            outline: shapes.id(Arc::as_ptr(&s.outline) as *const () as usize, || s.outline.to_vec()),
            outline_edges: shapes.id(Arc::as_ptr(&s.outline_edges) as *const () as usize, || {
                s.outline_edges.to_vec()
            }),
            map_color: s.map_color,
        })
        .collect();
    let chains_ids = chains
        .list
        .iter()
        .map(|chain| chain.iter().map(|c| strings.id(c)).collect())
        .collect();
    let woods = facts
        .woods
        .iter()
        .map(|(k, v)| (strings.id(k), strings.id(v)))
        .collect();
    let projection = projection
        .iter()
        .map(|p| Projection {
            flags: p.flags,
            emission: p.emission,
            dampening: p.dampening,
            hardness: p.hardness,
            push: strings.id(&p.push),
            volume: p.volume,
            pitch: p.pitch,
            place: strings.id(&p.place),
            break_sound: strings.id(&p.break_sound),
            fall: strings.id(&p.fall),
        })
        .collect();
    Snapshot {
        strings: strings.list,
        shapes: shapes.list,
        chains: chains_ids,
        states,
        woods,
        air: facts.air.0,
        projection,
    }
}

fn restore(snap: Snapshot, registry: &BlockRegistry) -> Option<(BlockFacts, Vec<GameplayProjection>)> {
    restore_len(snap, registry.len())
}

fn restore_len(snap: Snapshot, len: usize) -> Option<(BlockFacts, Vec<GameplayProjection>)> {
    if snap.states.len() != len || snap.projection.len() != len {
        return None;
    }
    let strings: Vec<Arc<str>> = snap.strings.iter().map(|s| Arc::from(s.as_str())).collect();
    let s = |i: u32| strings.get(i as usize).cloned();
    let shapes: Vec<Arc<[[f64; 6]]>> = snap.shapes.into_iter().map(Arc::from).collect();
    let chains: Vec<Arc<[Arc<str>]>> = snap
        .chains
        .iter()
        .map(|c| c.iter().map(|&i| s(i)).collect::<Option<Vec<_>>>().map(Arc::from))
        .collect::<Option<_>>()?;
    let mut states = Vec::with_capacity(snap.states.len());
    for st in snap.states {
        let flag = |i: u32| st.flags & (1 << i) != 0;
        states.push(StateFacts {
            name: s(st.name)?,
            emission: st.emission,
            dampening: st.dampening,
            skylight_down: flag(0),
            shape_occlusion: flag(1),
            can_occlude: flag(2),
            solid_render: flag(3),
            hardness: st.hardness,
            requires_tool: flag(4),
            replaceable: flag(5),
            random_ticks: flag(6),
            block_entity: flag(7),
            render_shape: match st.render_shape {
                1 => RenderShape::Invisible,
                2 => RenderShape::Animated,
                _ => RenderShape::Model,
            },
            full_collision: flag(8),
            friction: st.friction,
            explosion_resistance: st.explosion_resistance,
            item: s(st.item)?,
            sounds: Sounds {
                volume: st.sound_volume,
                pitch: st.sound_pitch,
                break_sound: s(st.sounds[0])?,
                step: s(st.sounds[1])?,
                place: s(st.sounds[2])?,
                hit: s(st.sounds[3])?,
                fall: s(st.sounds[4])?,
            },
            fluid: match st.fluid {
                1 => Fluid::Water,
                2 => Fluid::Lava,
                _ => Fluid::None,
            },
            fluid_level: st.fluid_level,
            fluid_falling: flag(9),
            waterlogged: flag(10),
            random_offset: flag(11),
            outline_offset: flag(12),
            legacy_solid: flag(13),
            sturdy_full: st.sturdy_full,
            classes: chains.get(st.classes as usize)?.clone(),
            outline: shapes.get(st.outline as usize)?.clone(),
            outline_edges: shapes.get(st.outline_edges as usize)?.clone(),
            map_color: st.map_color,
        });
    }
    let woods = snap
        .woods
        .iter()
        .map(|&(k, v)| Some((s(k)?, s(v)?)))
        .collect::<Option<_>>()?;
    let projection = snap
        .projection
        .iter()
        .map(|p| {
            Some(GameplayProjection {
                flags: p.flags,
                emission: p.emission,
                dampening: p.dampening,
                hardness: p.hardness,
                push: s(p.push)?,
                volume: p.volume,
                pitch: p.pitch,
                place: s(p.place)?,
                break_sound: s(p.break_sound)?,
                fall: s(p.fall)?,
            })
        })
        .collect::<Option<_>>()?;
    Some((BlockFacts { states, air: BlockStateId(snap.air), woods }, projection))
}

/// The snapshot in `dir` when it was made from `root`'s files for this registry by this build.
pub(super) fn load(dir: &Path, root: &Path, registry: &BlockRegistry) -> Option<(BlockFacts, Vec<GameplayProjection>)> {
    let bytes = fs::read(dir.join(FILE_NAME)).ok()?;
    if bytes.len() < 44 || &bytes[..8] != MAGIC || u32::from_le_bytes(bytes[8..12].try_into().ok()?) != VERSION {
        return None;
    }
    if bytes[12..44] != fingerprint(root, registry)?[..] {
        return None;
    }
    let body = lz4_flex::decompress_size_prepended(&bytes[44..]).ok()?;
    let snap: Snapshot = postcard::from_bytes(&body).ok()?;
    restore(snap, registry)
}

/// Writes the snapshot (atomically: a temporary file renamed); failures leave none.
pub(super) fn store(dir: &Path, root: &Path, registry: &BlockRegistry, facts: &BlockFacts, projection: &[GameplayProjection]) {
    let Some(fingerprint) = fingerprint(root, registry) else { return };
    let Ok(serialized) = postcard::to_allocvec(&capture(facts, projection)) else { return };
    let body = lz4_flex::compress_prepend_size(&serialized);
    let tmp = dir.join(format!("{FILE_NAME}.tmp-{}", std::process::id()));
    let written = (|| -> std::io::Result<()> {
        fs::create_dir_all(dir)?;
        let mut file = fs::File::create(&tmp)?;
        file.write_all(MAGIC)?;
        file.write_all(&VERSION.to_le_bytes())?;
        file.write_all(&fingerprint)?;
        file.write_all(&body)?;
        file.sync_all()?;
        fs::rename(&tmp, dir.join(FILE_NAME))
    })();
    if written.is_err() {
        let _ = fs::remove_file(&tmp);
    }
}

/// Whether two fact tables hold the same values (for the equivalence check on real content).
pub(super) fn same(a: &BlockFacts, b: &BlockFacts) -> bool {
    if a.air != b.air || a.states.len() != b.states.len() {
        log::warn!("Horizon: block facts differ: air or state count");
        return false;
    }
    let ca = capture(a, &[]);
    let cb = capture(b, &[]);
    for (i, (x, y)) in ca.states.iter().zip(&cb.states).enumerate() {
        let fields = [
            ("name", ca.strings[x.name as usize] == cb.strings[y.name as usize]),
            ("flags", x.flags == y.flags),
            ("numbers", x.emission == y.emission && x.dampening == y.dampening && x.hardness.to_bits() == y.hardness.to_bits() && x.friction.to_bits() == y.friction.to_bits() && x.explosion_resistance.to_bits() == y.explosion_resistance.to_bits()),
            ("kinds", x.render_shape == y.render_shape && x.fluid == y.fluid && x.fluid_level == y.fluid_level && x.sturdy_full == y.sturdy_full && x.map_color == y.map_color),
            ("item", ca.strings[x.item as usize] == cb.strings[y.item as usize]),
            ("sounds", x.sound_volume.to_bits() == y.sound_volume.to_bits() && x.sound_pitch.to_bits() == y.sound_pitch.to_bits() && x.sounds.iter().zip(&y.sounds).all(|(p, q)| ca.strings[*p as usize] == cb.strings[*q as usize])),
            ("classes", ca.chains[x.classes as usize].iter().map(|c| &ca.strings[*c as usize]).eq(cb.chains[y.classes as usize].iter().map(|c| &cb.strings[*c as usize]))),
            ("outline", ca.shapes[x.outline as usize].iter().flatten().map(|v| v.to_bits()).eq(cb.shapes[y.outline as usize].iter().flatten().map(|v| v.to_bits()))),
            ("edges", ca.shapes[x.outline_edges as usize].iter().flatten().map(|v| v.to_bits()).eq(cb.shapes[y.outline_edges as usize].iter().flatten().map(|v| v.to_bits()))),
        ];
        if let Some((field, _)) = fields.iter().find(|(_, same)| !same) {
            log::warn!("Horizon: block facts differ first at state {i} ({}): {field}", ca.strings[x.name as usize]);
            return false;
        }
    }
    let woods = |f: &BlockFacts| {
        let mut w: Vec<_> = f.woods.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        w.sort();
        w
    };
    if woods(a) != woods(b) {
        log::warn!("Horizon: block facts differ: woods");
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arc(s: &str) -> Arc<str> {
        Arc::from(s)
    }

    #[test]
    fn a_snapshot_restores_the_same_facts() {
        let chain: Arc<[Arc<str>]> = Arc::from(vec![arc("LeavesBlock"), arc("Block")]);
        let outline: Arc<[[f64; 6]]> = Arc::from(vec![[0.0, 0.0, 0.0, 1.0, 0.5, 1.0]]);
        let state = StateFacts {
            name: arc("mod:leaves"),
            emission: 3,
            dampening: 1,
            skylight_down: true,
            shape_occlusion: false,
            can_occlude: true,
            solid_render: false,
            hardness: 0.2,
            requires_tool: false,
            replaceable: false,
            random_ticks: true,
            block_entity: false,
            render_shape: RenderShape::Animated,
            full_collision: false,
            friction: 0.6,
            explosion_resistance: 0.2,
            item: arc("mod:leaves"),
            sounds: Sounds {
                volume: 1.0,
                pitch: 0.8,
                break_sound: arc("a"),
                step: arc("b"),
                place: arc("c"),
                hit: arc("d"),
                fall: arc("e"),
            },
            fluid: Fluid::Water,
            fluid_level: 8,
            fluid_falling: false,
            waterlogged: true,
            random_offset: true,
            outline_offset: false,
            legacy_solid: true,
            sturdy_full: 42,
            classes: chain.clone(),
            outline: outline.clone(),
            outline_edges: outline,
            map_color: 7,
        };
        let facts = BlockFacts {
            states: vec![state.clone(), state],
            air: BlockStateId(1),
            woods: HashMap::from([(arc("mod:sign"), arc("oak"))]),
        };
        let projection: Vec<GameplayProjection> = (0..2)
            .map(|i| GameplayProjection {
                flags: 0b101 + i,
                emission: 3,
                dampening: 1,
                hardness: 0.25,
                push: arc("NORMAL"),
                volume: 1.0,
                pitch: 0.8,
                place: arc("c"),
                break_sound: arc("a"),
                fall: arc("e"),
            })
            .collect();
        let bytes = postcard::to_allocvec(&capture(&facts, &projection)).unwrap();
        let snap: Snapshot = postcard::from_bytes(&bytes).unwrap();
        // restore needs a registry only for its length.
        assert_eq!(snap.states.len(), 2);
        let strings: Vec<Arc<str>> = snap.strings.iter().map(|s| Arc::from(s.as_str())).collect();
        assert!(strings.iter().any(|s| &**s == "LeavesBlock"));
        let again = capture(&facts, &projection);
        assert_eq!(postcard::to_allocvec(&again).unwrap(), bytes, "capture is deterministic");
        // A restore of the snapshot captures to the same bytes: every field round-trips.
        let registry_len = facts.states.len();
        let snap: Snapshot = postcard::from_bytes(&bytes).unwrap();
        assert_eq!(snap.states.len(), registry_len);
        let (back, back_projection) = restore_len(snap, registry_len).unwrap();
        assert_eq!(postcard::to_allocvec(&capture(&back, &back_projection)).unwrap(), bytes);
    }
}
