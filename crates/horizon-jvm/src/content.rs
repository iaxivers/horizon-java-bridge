//! The Java mods' blocks, items and assets as Horizon content (docs/modpacks.md, registries
//! 0.2): the bridge's running game writes the oracle facts of what the mods register
//! ([`crate::events::export_facts`]), and `horizon-port-pack`'s carry-over turns each jar into a
//! folder content mod with them: block declarations from the vanilla template of the same class
//! and shapes, the mods' own numbers, models, textures, recipes, loot and tags. The game loads
//! those as it loads any content mod, before its tables are read.
//!
//! The result is kept in a cache folder under a stamp of the jars (names, sizes, times) and of
//! this crate's version, so a start with the same mods reads it back without starting Java.

use std::path::{Path, PathBuf};

/// The version of what this module writes: bump it when the facts or the carry-over change.
const FORMAT: u32 = 17;

/// The stamp of a set of jars: their names, sizes and modification times, and [`FORMAT`].
pub fn stamp(jars: &[PathBuf]) -> String {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    FORMAT.hash(&mut hasher);
    env!("CARGO_PKG_VERSION").hash(&mut hasher);
    let mut sorted = jars.to_vec();
    sorted.sort();
    for jar in &sorted {
        jar.file_name().hash(&mut hasher);
        if let Ok(meta) = std::fs::metadata(jar) {
            meta.len().hash(&mut hasher);
            if let Ok(t) = meta.modified() {
                t.hash(&mut hasher);
            }
        }
    }
    format!("{:016x}", hasher.finish())
}

/// The content mod folders built for `jars` under `cache` before, when their stamp matches.
pub fn cached(jars: &[PathBuf], cache: &Path) -> Option<Vec<PathBuf>> {
    let stamp_file = cache.join("stamp");
    if std::fs::read_to_string(&stamp_file).ok()?.trim() != stamp(jars) {
        return None;
    }
    Some(carried(&cache.join("work")))
}

/// The folder content mods under a work folder (`mods/<key>/carry`), in key order.
fn carried(work: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = std::fs::read_dir(work.join("mods"))
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path().join("carry"))
        .filter(|p| p.join("horizon-mod.toml").is_file())
        .collect();
    out.sort();
    out
}

/// The creative inventory the mods' content build exports, in the cache.
pub const CREATIVE: &str = "creative-inventory.json";
/// The mods' block tints exported with the cached content (`events::export_tints`).
pub const TINTS: &str = "block-tints.json";

/// The block tints exported with the cached content, if any.
pub fn tints(cache: &Path) -> Option<PathBuf> {
    let path = cache.join(TINTS);
    path.is_file().then_some(path)
}

/// The creative inventory exported with the cached content: the game's resources take it in
/// place of the prepared one (horizon-prepare's, vanilla's tabs only).
pub fn creative(cache: &Path) -> Option<PathBuf> {
    let path = cache.join(CREATIVE);
    path.is_file().then_some(path)
}

/// The mods a jar carries inside it (Fabric's jar-in-jar, `META-INF/jars/`: RebornCore in Tech
/// Reborn) that have textures, written into `mods` as mods of their own, so their assets are
/// carried too (Tech Reborn's screens draw RebornCore's GUI sprites). Libraries and Fabric
/// API's modules (no textures) stay inside. Nested ones are looked into `depth` levels down.
fn nested_jars(jar: &Path, mods: &Path, depth: u32) {
    use std::io::Read;
    let Ok(file) = std::fs::File::open(jar) else {
        return;
    };
    let Ok(mut zip) = zip::ZipArchive::new(file) else {
        return;
    };
    let inner: Vec<String> = zip
        .file_names()
        .filter(|n| n.starts_with("META-INF/jars/") && n.ends_with(".jar"))
        .map(str::to_owned)
        .collect();
    for name in inner {
        let mut bytes = Vec::new();
        let Ok(mut entry) = zip.by_name(&name) else {
            continue;
        };
        if entry.read_to_end(&mut bytes).is_err() {
            continue;
        }
        let Ok(nested) = zip::ZipArchive::new(std::io::Cursor::new(&bytes)) else {
            continue;
        };
        let textures = nested
            .file_names()
            .any(|n| n.starts_with("assets/") && n.contains("/textures/") && n.ends_with(".png"));
        let file_name = name.rsplit('/').next().unwrap_or(&name);
        let out = mods.join(file_name);
        if !textures || out.exists() {
            continue;
        }
        if std::fs::write(&out, &bytes).is_ok() && depth > 1 {
            nested_jars(&out, mods, depth - 1);
        }
    }
}

/// Builds the content mods of `jars` into `cache` (the JVM and Fabric running, with these
/// jars as mods), for the prepared game at `resources`: the folders to load.
pub fn build(
    jars: &[PathBuf],
    resources: &Path,
    cache: &Path,
    log: &dyn Fn(String),
) -> Result<Vec<PathBuf>, String> {
    let _ = std::fs::remove_dir_all(cache);
    let work = cache.join("work");
    // An instance folder as the porter imports one: the jars in mods/, the loader named.
    let instance = cache.join("instance");
    let mods = instance.join("mods");
    std::fs::create_dir_all(&mods).map_err(|e| format!("{}: {e}", mods.display()))?;
    for jar in jars {
        let name = jar.file_name().ok_or("a jar without a name")?;
        std::fs::copy(jar, mods.join(name)).map_err(|e| format!("{}: {e}", jar.display()))?;
        nested_jars(jar, &mods, 2);
    }
    let pack = serde_json::json!({
        "components": [
            {"uid": "net.minecraft", "version": "26.3"},
            {"uid": "net.fabricmc.fabric-loader", "version": "0.20.1"},
        ]
    });
    std::fs::write(instance.join("mmc-pack.json"), pack.to_string())
        .map_err(|e| format!("mmc-pack.json: {e}"))?;
    let log_line = |line: String| log(format!("Java mods' content: {line}"));
    horizon_port_pack::pipeline::import(&instance, &work, &log_line)?;
    let records = horizon_port_pack::pipeline::inventory(&work, true, &log_line)?;
    let namespaces = crate::events::export_facts(&work.join("oracle/facts.json"))?;
    log(format!(
        "Java mods' content: the mods register in {}",
        if namespaces.is_empty() {
            "no namespace of their own".to_string()
        } else {
            namespaces.join(", ")
        }
    ));
    // Every jar is carried: its assets and data under its own namespaces (a mod with no
    // blocks still has names, recipes and loot Horizon reads).
    let all: Vec<String> = records.iter().map(|r| r.key.clone()).collect();
    horizon_port_pack::pipeline::carry(&work, Some(resources), &all, &log_line)?;
    // The creative tabs with the mods' (the game puts it over the prepared one: `creative`).
    match crate::events::export_creative(&cache.join(CREATIVE)) {
        Ok(()) => log("Java mods' content: creative tabs exported".into()),
        Err(e) => log(format!("Java mods' content: no creative tabs: {e}")),
    }
    // The mods' block tints (the client's: none on a dedicated server, which draws nothing).
    match crate::events::export_tints(&cache.join(TINTS)) {
        Ok(()) => log("Java mods' content: block tints exported".into()),
        Err(e) => log(format!("Java mods' content: no block tints: {e}")),
    }
    let _ = std::fs::remove_dir_all(&instance);
    // The build's own inputs: only the carried folders are read back (`cached`). The oracle
    // facts were 1.2 GB with 184 mods, the files no mod could claim another 50 MB.
    let _ = std::fs::remove_dir_all(work.join("oracle"));
    for dir in std::fs::read_dir(work.join("mods")).into_iter().flatten().flatten() {
        let _ = std::fs::remove_dir_all(dir.path().join("outside"));
    }
    std::fs::write(cache.join("stamp"), stamp(jars)).map_err(|e| format!("stamp: {e}"))?;
    Ok(carried(&work))
}
