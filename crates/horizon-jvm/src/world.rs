//! Horizon's blocks while Java runs: a mod that reads a block of a mirrored level
//! (`Level.getBlockState`) reads Horizon's own world, by name (`minecraft:oak_log[axis=y]`),
//! through the view the game lends for the call ([`with_blocks`]). What mods set is made in
//! Horizon's world inside the call ([`Blocks::set_block`]: vanilla's `Level.setBlock` with its
//! updates), so the mod's next read sees it, as in vanilla. Where the view can't (another
//! dimension, a chunk not loaded, no view) it comes back as an action
//! ([`crate::events::Action::SetBlock`]) and the Java side keeps it until Horizon has made it.

use std::cell::{Cell, RefCell};

/// What one `Level.setBlock` made in Horizon's world ([`Blocks::set_block`]).
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Written {
    /// Whether the block changed (vanilla's `setBlock` result).
    pub changed: bool,
    /// Every block the set changed, its cascade's included (a torch popping off), in order:
    /// the position and Horizon's new state id. Java's copies of the world follow them.
    pub changes: Vec<[i32; 4]>,
    /// Java mods' blocks placed or removed by it (the position and the state id it replaced;
    /// -1: a mod's fluid beside a change): their block entities, `onPlace` and
    /// `affectNeighborsAfterRemoval` run in Java next.
    pub sets: Vec<[i32; 4]>,
    /// Positions whose neighbours Horizon updated with a mod's block among them: the mods'
    /// blocks there hear it in Java next (their shape updates and neighbour changes).
    pub neighbours: Vec<[i32; 3]>,
}

impl Written {
    /// As Java reads it (`Natives.setBlockNow`): changed, then each list with its count first.
    pub fn flat(&self) -> Vec<i32> {
        let mut out = Vec::with_capacity(
            4 + self.changes.len() * 4 + self.sets.len() * 4 + self.neighbours.len() * 3,
        );
        out.push(i32::from(self.changed));
        out.push(self.changes.len() as i32);
        out.extend(self.changes.iter().flatten());
        out.push(self.sets.len() as i32);
        out.extend(self.sets.iter().flatten());
        out.push(self.neighbours.len() as i32);
        out.extend(self.neighbours.iter().flatten());
        out
    }
}

thread_local! {
    /// The blocks changed during the writes running on this thread ([`set_block`]), innermost
    /// last; a nested write's changes join the one around it.
    static CHANGES: RefCell<Vec<Vec<[i32; 4]>>> = const { RefCell::new(Vec::new()) };
}

/// A block of Horizon's world changed (the game's world hook calls this for every change):
/// kept for the write running on this thread, if any.
pub fn note_change(x: i32, y: i32, z: i32, state: u32) {
    CHANGES.with_borrow_mut(|c| {
        if let Some(top) = c.last_mut() {
            top.push([x, y, z, state as i32]);
        }
    });
}

/// Horizon's world as Java mods read it.
pub trait Blocks {
    /// The block state at `x y z` of `dimension` (`minecraft:overworld`), with its
    /// properties: `minecraft:oak_log[axis=y]`. None where no chunk is loaded.
    fn block(&self, dimension: &str, x: i32, y: i32, z: i32) -> Option<String>;
    /// The light at `x y z`: sky light in the high four bits, block light in the low. None
    /// where no chunk is loaded.
    fn light(&self, _dimension: &str, _x: i32, _y: i32, _z: i32) -> Option<u8> {
        None
    }
    /// The block entity at `x y z` as vanilla saves it (`saveWithFullMetadata`), in SNBT:
    /// a chest's items, for a mod that reads them. None where there is none.
    fn block_entity(&self, _dimension: &str, _x: i32, _y: i32, _z: i32) -> Option<String> {
        None
    }
    /// The blocks of chunk `cx cz` from `min_y` up `height` blocks, at once: `out[i]` (`i` =
    /// `((y - min_y) * 16 + z) * 16 + x`) is an index into the returned palette of states
    /// (`minecraft:oak_log[axis=y]`). None where the chunk isn't loaded.
    fn column(
        &self,
        _dimension: &str,
        _cx: i32,
        _cz: i32,
        _min_y: i32,
        _out: &mut [i32],
    ) -> Option<Vec<String>> {
        None
    }
    /// Horizon's state ids of the 16x16x16 section `cx sy cz` (`out[(y * 16 + z) * 16 + x]`),
    /// named by [`Blocks::state_name`]: Some(false) where its chunk isn't loaded, None when
    /// this view can't (Java then reads [`Blocks::column`]).
    fn section_ids(
        &self,
        _dimension: &str,
        _cx: i32,
        _sy: i32,
        _cz: i32,
        _out: &mut [i32],
    ) -> Option<bool> {
        None
    }
    /// A number for chunk `cx cz`'s blocks as they are now (new when one changes, or the
    /// chunk is another): what was read of it stays true while it stays. -2 where it isn't
    /// loaded, -1 when this view can't say.
    fn chunk_revision(&self, _dimension: &str, _cx: i32, _cz: i32) -> i64 {
        -1
    }
    /// The state a [`Blocks::section_ids`] id is (`minecraft:oak_log[axis=y]`).
    fn state_name(&self, _id: i32) -> Option<String> {
        None
    }
    /// `getHeight(Heightmap.Types.WORLD_SURFACE, x, z)`: one above the column's highest
    /// block that isn't air. None where the chunk isn't loaded.
    fn surface(&self, _dimension: &str, _x: i32, _z: i32) -> Option<i32> {
        None
    }
    /// The biomes of chunk `cx cz` as [`Blocks::column`] gives its blocks, one a quart (4x4x4
    /// blocks; `i` = `((qy - min_y / 4) * 4 + qz) * 4 + qx`), with a palette of biome ids.
    fn column_biomes(
        &self,
        _dimension: &str,
        _cx: i32,
        _cz: i32,
        _min_y: i32,
        _out: &mut [i32],
    ) -> Option<Vec<String>> {
        None
    }
    /// `Level.setBlock(pos, state, flags)` made now in Horizon's world, with vanilla's updates
    /// (neighbours, shapes, block entities): what it did ([`Written`]; `changes` is filled by
    /// [`set_block`]). None where this view can't write there now (another dimension, a chunk
    /// not loaded): the write is queued instead.
    fn set_block(
        &mut self,
        _dimension: &str,
        _x: i32,
        _y: i32,
        _z: i32,
        _state: &str,
        _flags: i32,
    ) -> Option<Written> {
        None
    }
}

/// [`Blocks::set_block`] of this thread's view, with every block it changed; None outside a
/// view or where it can't write.
pub(crate) fn set_block(
    dimension: &str,
    x: i32,
    y: i32,
    z: i32,
    state: &str,
    flags: i32,
) -> Option<Written> {
    /// Takes this write's changes off the stack, even if the write panics.
    struct Taken(usize);
    impl Taken {
        fn take(&self) -> Vec<[i32; 4]> {
            CHANGES.with_borrow_mut(|c| {
                if c.len() <= self.0 {
                    return Vec::new();
                }
                let mine = c.split_off(self.0).into_iter().flatten().collect::<Vec<_>>();
                // The write around this one (a cascade that called Java) sees these too.
                if let Some(outer) = c.last_mut() {
                    outer.extend_from_slice(&mine);
                }
                mine
            })
        }
    }
    impl Drop for Taken {
        fn drop(&mut self) {
            self.take();
        }
    }
    let writer = WRITER.get()?;
    let depth = CHANGES.with_borrow_mut(|c| {
        c.push(Vec::new());
        c.len() - 1
    });
    let taken = Taken(depth);
    // SAFETY: a writer in WRITER is alive (with_blocks_mut), and Java calls this on the thread
    // that lent it, from inside the call it was lent for: nothing else uses it meanwhile.
    let written = unsafe { (*writer).set_block(dimension, x, y, z, state, flags) };
    let changes = taken.take();
    written.map(|mut w| {
        w.changes = changes;
        w
    })
}

/// Counts the views lent ([`with_blocks`]): what Java read of one may not hold in the next.
static EPOCH: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

/// The number of the view lent last (Java's chunk copies are good for one view only).
pub(crate) fn epoch() -> i64 {
    EPOCH.load(std::sync::atomic::Ordering::SeqCst)
}

/// [`Blocks::column`] of this thread's view; None outside one.
pub(crate) fn column(
    dimension: &str,
    cx: i32,
    cz: i32,
    min_y: i32,
    out: &mut [i32],
) -> Option<Vec<String>> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).column(dimension, cx, cz, min_y, out) })
}

/// [`Blocks::section_ids`] of this thread's view; None outside one (or when it can't).
pub(crate) fn section_ids(
    dimension: &str,
    cx: i32,
    sy: i32,
    cz: i32,
    out: &mut [i32],
) -> Option<bool> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).section_ids(dimension, cx, sy, cz, out) })
}

/// [`Blocks::chunk_revision`] of this thread's view; -1 outside one.
pub(crate) fn chunk_revision(dimension: &str, cx: i32, cz: i32) -> i64 {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get().map_or(-1, |view| unsafe {
        (*view).chunk_revision(dimension, cx, cz)
    })
}

/// [`Blocks::state_name`] of this thread's view.
pub(crate) fn state_name(id: i32) -> Option<String> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).state_name(id) })
}

/// [`Blocks::surface`] of this thread's view.
pub(crate) fn surface(dimension: &str, x: i32, z: i32) -> Option<i32> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).surface(dimension, x, z) })
}

/// Mods' block entities that came up (true) or went, by dimension, in order, for the game's
/// block entity list (a placeholder each: their ticks come in vanilla's order).
static TICKERS: std::sync::Mutex<Vec<(String, [i32; 3], bool)>> = std::sync::Mutex::new(Vec::new());

/// A mod's block entity came up at `pos` of `dimension`, or went. Kept up to 1 << 16.
pub(crate) fn block_entity_ticker(dimension: &str, pos: [i32; 3], up: bool) {
    let mut tickers = TICKERS.lock().unwrap_or_else(|e| e.into_inner());
    if tickers.len() < 1 << 16 {
        tickers.push((dimension.to_owned(), pos, up));
    }
}

/// The mods' block entities of `dimension` that came up (true) or went since last asked, in
/// the order they did.
pub fn take_block_entity_tickers(dimension: &str) -> Vec<([i32; 3], bool)> {
    let mut tickers = TICKERS.lock().unwrap_or_else(|e| e.into_inner());
    if tickers.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    tickers.retain(|(d, p, up)| {
        if d == dimension {
            out.push((*p, *up));
            false
        } else {
            true
        }
    });
    out
}

/// Chunks Horizon generated (not loaded from a save) since Java last asked, by dimension:
/// `NaturalSpawner.spawnMobsForChunkGeneration` for the mods' mobs (`HorizonSpawner`).
static FRESH: std::sync::Mutex<Vec<(String, i32, i32)>> = std::sync::Mutex::new(Vec::new());

/// A chunk of `dimension` newly generated. Kept up to 4096 (Java takes them each tick).
pub fn fresh_chunk(dimension: &str, cx: i32, cz: i32) {
    let mut fresh = FRESH.lock().unwrap_or_else(|e| e.into_inner());
    if fresh.len() < 4096 {
        fresh.push((dimension.to_owned(), cx, cz));
    }
}

/// The fresh chunks of `dimension`, taken.
pub(crate) fn take_fresh(dimension: &str) -> Vec<i32> {
    let mut fresh = FRESH.lock().unwrap_or_else(|e| e.into_inner());
    let mut out = Vec::new();
    fresh.retain(|(d, cx, cz)| {
        if d == dimension {
            out.extend([*cx, *cz]);
            false
        } else {
            true
        }
    });
    out
}

/// [`Blocks::column_biomes`] of this thread's view; None outside one.
pub(crate) fn column_biomes(
    dimension: &str,
    cx: i32,
    cz: i32,
    min_y: i32,
    out: &mut [i32],
) -> Option<Vec<String>> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).column_biomes(dimension, cx, cz, min_y, out) })
}

type View = *const (dyn Blocks + 'static);
type Writer = *mut (dyn Blocks + 'static);

thread_local! {
    /// The view of the call running on this thread (Java runs mods on the caller's thread).
    static VIEW: Cell<Option<View>> = const { Cell::new(None) };
    /// The same view when it was lent to write through ([`with_blocks_mut`]); None in a
    /// read-only one.
    static WRITER: Cell<Option<Writer>> = const { Cell::new(None) };
    /// This thread's view changes (a view lent, or one put back): Java's per-thread caches of
    /// the world are good while it stays.
    static THREAD_EPOCH: Cell<i64> = const { Cell::new(0) };
}

/// This thread's view changes so far ([`with_blocks`] counts one in, one out).
pub(crate) fn thread_epoch() -> i64 {
    THREAD_EPOCH.get()
}

/// Where this thread's view counter lives (`Natives.threadEpochBuffer`: Java reads it in
/// place). A `Cell<i64>` has `i64`'s layout.
pub(crate) fn thread_epoch_cell() -> *mut i64 {
    THREAD_EPOCH.with(|c| c.as_ptr())
}

/// Runs `f` (a call into Java) with `blocks` as what mods read of the world (their writes
/// are queued: [`set_block`] answers None).
pub fn with_blocks<R>(blocks: &dyn Blocks, f: impl FnOnce() -> R) -> R {
    let view: *const (dyn Blocks + '_) = blocks;
    // SAFETY: the pointer only lives in VIEW until the reset drops, before `blocks` can.
    let view: View = unsafe { std::mem::transmute(view) };
    lend(view, None, f)
}

/// Runs `f` (a call into Java) with `blocks` as what mods read of the world and write to:
/// a mod's `Level.setBlock` is made through [`Blocks::set_block`] inside the call.
pub fn with_blocks_mut<R>(blocks: &mut dyn Blocks, f: impl FnOnce() -> R) -> R {
    let writer: *mut (dyn Blocks + '_) = blocks;
    // SAFETY: the pointers only live in VIEW and WRITER until the reset drops, before
    // `blocks` can; `blocks` is borrowed mutably for that long, so only Java's calls back
    // (on this thread, inside `f`) use it.
    let writer: Writer = unsafe { std::mem::transmute(writer) };
    lend(writer as View, Some(writer), f)
}

fn lend<R>(view: View, writer: Option<Writer>, f: impl FnOnce() -> R) -> R {
    struct Reset(Option<View>, Option<Writer>);
    impl Drop for Reset {
        fn drop(&mut self) {
            VIEW.set(self.0);
            WRITER.set(self.1);
            THREAD_EPOCH.set(THREAD_EPOCH.get() + 1);
        }
    }
    let _reset = Reset(VIEW.replace(Some(view)), WRITER.replace(writer));
    EPOCH.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    THREAD_EPOCH.set(THREAD_EPOCH.get() + 1);
    f()
}

/// The light a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn light(dimension: &str, x: i32, y: i32, z: i32) -> Option<u8> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).light(dimension, x, y, z) })
}

/// The block entity a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn block_entity(dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).block_entity(dimension, x, y, z) })
}

/// The block a mod reads, from the view of this thread's call; None outside one.
pub(crate) fn block(dimension: &str, x: i32, y: i32, z: i32) -> Option<String> {
    // SAFETY: a view in VIEW is alive (with_blocks).
    VIEW.get()
        .and_then(|view| unsafe { (*view).block(dimension, x, y, z) })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Column;
    impl Blocks for Column {
        fn block(&self, _: &str, x: i32, y: i32, z: i32) -> Option<String> {
            Some(
                if x == 0 && z == 0 && y >= 64 {
                    "minecraft:oak_log[axis=y]"
                } else {
                    "minecraft:air"
                }
                .into(),
            )
        }
    }

    /// A view whose writes change the block and, as a cascade, the one above (a torch on it),
    /// whose own change writes again through the view (a mod's code called back).
    struct Torch;
    impl Blocks for Torch {
        fn block(&self, _: &str, _: i32, _: i32, _: i32) -> Option<String> {
            None
        }
        fn set_block(
            &mut self,
            dimension: &str,
            x: i32,
            y: i32,
            z: i32,
            _: &str,
            _: i32,
        ) -> Option<Written> {
            if dimension != "minecraft:overworld" {
                return None;
            }
            note_change(x, y, z, 7);
            if y == 64 {
                note_change(x, y + 1, z, 0);
                let inner = set_block(dimension, x, y + 1, z, "minecraft:stone", 3)?;
                assert_eq!(inner.changes, vec![[x, y + 1, z, 7]]);
            }
            Some(Written {
                changed: true,
                ..Default::default()
            })
        }
    }

    #[test]
    fn a_write_reports_every_change_its_cascade_made_in_order() {
        let written =
            with_blocks_mut(&mut Torch, || set_block("minecraft:overworld", 1, 64, 2, "x", 3))
                .unwrap();
        // The inner write's change joins the outer one's, after what the outer made before it:
        // read in order, the last change at a position is what stands there.
        assert_eq!(
            written.changes,
            vec![[1, 64, 2, 7], [1, 65, 2, 0], [1, 65, 2, 7]]
        );
        assert_eq!(written.flat()[..2], [1, 3]);
        // Another dimension, or no view at all: queued, nothing noted.
        assert!(
            with_blocks_mut(&mut Torch, || set_block("minecraft:the_nether", 0, 0, 0, "x", 3))
                .is_none()
        );
        // A read-only view, or none at all: queued.
        assert!(with_blocks(&Torch, || set_block("minecraft:overworld", 0, 0, 0, "x", 3)).is_none());
        assert!(set_block("minecraft:overworld", 0, 0, 0, "x", 3).is_none());
        note_change(0, 0, 0, 1);
        assert!(CHANGES.with_borrow(Vec::is_empty));
    }

    #[test]
    fn a_view_holds_for_its_call_only() {
        assert_eq!(block("minecraft:overworld", 0, 64, 0), None);
        let read = with_blocks(&Column, || block("minecraft:overworld", 0, 64, 0));
        assert_eq!(read.as_deref(), Some("minecraft:oak_log[axis=y]"));
        assert_eq!(block("minecraft:overworld", 0, 64, 0), None);
    }
}
