//! Plug and play for Java mods (UMP, 2026-11-11): the required mods the jars of `mods-java/` name in their
//! `fabric.mod.json` but nobody put there are found on Modrinth (Fabric, Minecraft 26.3) and downloaded,
//! checked against Modrinth's SHA-512, before the game starts. What can't be found is said in plain words,
//! the game still starts (Fabric then names what's missing).
//!
//! Ids the game provides itself are never fetched: Minecraft, Java, Fabric Loader, Fabric API and its
//! modules (Horizon brings them pinned), and what a jar carries inside it (`META-INF/jars/`).
use crate::browser::{self, Kind, Source};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};

/// Ids that are never downloaded: the game itself and what Horizon brings.
const PROVIDED: [&str; 7] = ["minecraft", "java", "fabricloader", "fabric", "fabric-api", "fabric-api-base", "mixinextras"];

/// How many rounds of "download what's missing, read what those need" are made.
const MAX_ROUNDS: usize = 4;

/// What a jar says about itself: its own id, the ids it provides, what it requires.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct JarFacts {
    pub ids: BTreeSet<String>,
    pub requires: BTreeSet<String>,
}

/// The facts of one jar: its `fabric.mod.json` and those of the jars it carries (jar-in-jar).
pub fn facts(jar: &Path) -> JarFacts {
    let mut out = JarFacts::default();
    let Ok(file) = std::fs::File::open(jar) else { return out };
    let Ok(mut zip) = zip::ZipArchive::new(file) else { return out };
    read_into(&mut zip, &mut out, 0);
    out
}

fn read_into<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, out: &mut JarFacts, depth: u32) {
    if let Ok(entry) = zip.by_name("fabric.mod.json") {
        let mut text = String::new();
        if entry.take(1 << 20).read_to_string(&mut text).is_ok() {
            let text: String = text.chars().map(|c| if c == '\n' || c == '\r' { ' ' } else { c }).collect();
            if let Ok(meta) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(id) = meta["id"].as_str() {
                    out.ids.insert(id.to_string());
                }
                for p in meta["provides"].as_array().into_iter().flatten().filter_map(|p| p.as_str()) {
                    out.ids.insert(p.to_string());
                }
                // Only the outer jar's requirements count: nested jars' are the outer mod's business.
                if depth == 0 {
                    for (id, _) in meta["depends"].as_object().into_iter().flatten() {
                        out.requires.insert(id.clone());
                    }
                }
            }
        }
    }
    if depth >= 3 {
        return;
    }
    let mut nested: Vec<String> = Vec::new();
    for i in 0..zip.len() {
        if let Ok(e) = zip.by_index_raw(i) {
            let Ok(n) = e.name() else { continue };
            let n = n.into_owned();
            if n.starts_with("META-INF/jars/") && n.ends_with(".jar") {
                nested.push(n);
            }
        }
    }
    for name in nested {
        let mut bytes = Vec::new();
        if let Ok(mut e) = zip.by_name(&name) {
            if e.read_to_end(&mut bytes).is_err() {
                continue;
            }
        }
        if let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) {
            read_into(&mut inner, out, depth + 1);
        }
    }
}

/// Whether an id is the game's or Horizon's own (never downloaded).
pub fn provided(id: &str) -> bool {
    PROVIDED.contains(&id) || (id.starts_with("fabric-") && (id.ends_with("-v0") || id.ends_with("-v1") || id.ends_with("-v2") || id.ends_with("-v3") || id.contains("-api")))
}

/// The ids required by the jars and provided by none of them (nor by the game): what is missing.
pub fn missing(all: &[JarFacts]) -> BTreeSet<String> {
    let have: BTreeSet<&str> = all.iter().flat_map(|f| f.ids.iter().map(String::as_str)).collect();
    all.iter()
        .flat_map(|f| f.requires.iter())
        .filter(|id| !provided(id) && !have.contains(id.as_str()))
        .cloned()
        .collect()
}

/// What a run of [`resolve`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Report {
    /// Mod id → the file downloaded for it.
    pub added: BTreeMap<String, String>,
    /// Mod id → why it couldn't be had.
    pub unresolved: BTreeMap<String, String>,
}

impl Report {
    /// The lines a player reads.
    pub fn lines(&self) -> Vec<String> {
        let mut out = Vec::new();
        if !self.added.is_empty() {
            out.push(format!(
                "Downloaded {} mod(s) your mods need: {}.",
                self.added.len(),
                self.added.keys().cloned().collect::<Vec<_>>().join(", ")
            ));
        }
        for (id, why) in &self.unresolved {
            out.push(format!("A mod needs \"{id}\", which couldn't be downloaded: {why}"));
        }
        out
    }
}

/// Finds a Fabric 26.3 file for mod id `id` on `source` (Modrinth): the project whose slug is the
/// id, else the first search hit whose slug or title matches it.
fn find(source: &dyn Source, id: &str) -> Result<(browser::Project, browser::Version), String> {
    let candidates: Vec<String> = {
        let mut c = vec![id.to_string(), id.replace('_', "-")];
        c.dedup();
        c
    };
    let mut project = None;
    for slug in &candidates {
        if let Ok(p) = source.project(slug) {
            project = Some(p);
            break;
        }
    }
    if project.is_none() {
        let mut q = browser::Query::new(id, Kind::Mod);
        q.limit = 10;
        let norm = |s: &str| s.to_ascii_lowercase().replace(['-', '_', ' ', '\''], "");
        project = source
            .search(&q)?
            .into_iter()
            .find(|p| norm(&p.slug) == norm(id) || norm(&p.title) == norm(id));
    }
    let project = project.ok_or_else(|| "not found on Modrinth".to_string())?;
    let version = source
        .versions(&project)?
        .into_iter()
        .find(|v| {
            v.loaders.iter().any(|l| l == "fabric")
                && v.game_versions.iter().any(|g| g == crate::java_mods::MINECRAFT)
                && v.file().is_some()
        })
        .ok_or_else(|| format!("{} has no Fabric {} version", project.title, crate::java_mods::MINECRAFT))?;
    Ok((project, version))
}

/// Downloads into `dir` (`mods-java/`) every required mod its jars lack, and what those need in turn.
pub fn resolve(source: &dyn Source, dir: &Path, log: &dyn Fn(&str)) -> Report {
    let mut report = Report::default();
    for _ in 0..MAX_ROUNDS {
        let jars: Vec<PathBuf> = std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|e| e == "jar"))
            .collect();
        let all: Vec<JarFacts> = jars.iter().map(|j| facts(j)).collect();
        let need: Vec<String> = missing(&all)
            .into_iter()
            .filter(|id| !report.unresolved.contains_key(id) && !report.added.contains_key(id))
            .collect();
        if need.is_empty() {
            break;
        }
        for id in need {
            log(&format!("Java mods: looking for \"{id}\", which a mod needs"));
            match find(source, &id).and_then(|(project, version)| {
                let file = version.file().cloned().ok_or("no file")?;
                if dir.join(&file.name).exists() {
                    return Err(format!("{} is there but doesn't provide \"{id}\"", file.name));
                }
                browser::download::fetch(&file, dir, &|_| {})?;
                Ok((project.title, file.name))
            }) {
                Ok((title, file)) => {
                    log(&format!("Java mods: downloaded {title} ({file})"));
                    report.added.insert(id, file);
                }
                Err(why) => {
                    log(&format!("Java mods: \"{id}\" couldn't be downloaded: {why}"));
                    report.unresolved.insert(id, why);
                }
            }
        }
    }
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(ids: &[&str], requires: &[&str]) -> JarFacts {
        JarFacts {
            ids: ids.iter().map(|s| s.to_string()).collect(),
            requires: requires.iter().map(|s| s.to_string()).collect(),
        }
    }

    #[test]
    fn missing_skips_the_game_horizon_and_what_jars_provide() {
        let all = vec![
            f(&["farmersdelight"], &["minecraft", "fabricloader", "fabric-api", "java"]),
            f(&["waystones"], &["balm", "fabric-lifecycle-events-v1", "minecraft"]),
            f(&["biomesoplenty"], &["terrablender", "glitchcore"]),
            f(&["glitchcore", "glitch_core_alias"], &[]),
        ];
        let m: Vec<String> = missing(&all).into_iter().collect();
        assert_eq!(m, ["balm", "terrablender"]);
    }

    #[test]
    fn nothing_missing_when_all_present() {
        let all = vec![f(&["a"], &["b"]), f(&["b"], &["minecraft"])];
        assert!(missing(&all).is_empty());
    }

    #[test]
    fn report_lines_are_plain() {
        let mut r = Report::default();
        r.added.insert("balm".into(), "balm-fabric.jar".into());
        r.unresolved.insert("weird".into(), "not found on Modrinth".into());
        let lines = r.lines();
        assert_eq!(lines[0], "Downloaded 1 mod(s) your mods need: balm.");
        assert!(lines[1].contains("\"weird\"") && lines[1].contains("not found"));
    }
}
