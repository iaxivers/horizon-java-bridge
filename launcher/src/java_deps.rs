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
    /// The outer mod's own id, name and version (fabric.mod.json).
    pub id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    /// `depends` with their version predicates (as written), and `breaks`.
    pub wants: BTreeMap<String, serde_json::Value>,
    pub breaks: BTreeMap<String, serde_json::Value>,
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
                    if depth == 0 {
                        out.id = Some(id.to_string());
                        out.name = meta["name"].as_str().map(str::to_string);
                        out.version = meta["version"].as_str().map(str::to_string);
                    }
                }
                for p in meta["provides"].as_array().into_iter().flatten().filter_map(|p| p.as_str()) {
                    out.ids.insert(p.to_string());
                }
                // Only the outer jar's requirements count: nested jars' are the outer mod's business.
                if depth == 0 {
                    for (id, wants) in meta["depends"].as_object().into_iter().flatten() {
                        out.requires.insert(id.clone());
                        out.wants.insert(id.clone(), wants.clone());
                    }
                    for (id, which) in meta["breaks"].as_object().into_iter().flatten() {
                        out.breaks.insert(id.clone(), which.clone());
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

/// A mod a jar requires at a version the folder's copy isn't (Fabric refuses to start for it).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Outdated {
    pub needed_by: String,
    pub id: String,
    pub have: String,
    pub wants: String,
}

fn predicate_text(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::Array(a) => a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(" or "),
        other => other.as_str().unwrap_or("").to_string(),
    }
}

fn title(f: &JarFacts) -> String {
    f.name.clone().or_else(|| f.id.clone()).unwrap_or_else(|| "a mod".into())
}

/// The required mods present at a version the requiring mod excludes.
pub fn outdated(all: &[JarFacts]) -> Vec<Outdated> {
    let mut out = Vec::new();
    for f in all {
        for (id, wants) in &f.wants {
            if provided(id) {
                continue;
            }
            let Some(have) = all.iter().find(|o| o.id.as_deref() == Some(id.as_str())) else { continue };
            let Some(version) = &have.version else { continue };
            if crate::java_mods::runs_on(wants, version) == Some(false) {
                out.push(Outdated { needed_by: title(f), id: id.clone(), have: version.clone(), wants: predicate_text(wants) });
            }
        }
    }
    out
}

/// Pairs of mods where one says it breaks the other at the version present: plain sentences.
pub fn clashes(all: &[JarFacts]) -> Vec<String> {
    let mut out = Vec::new();
    for f in all {
        for (id, which) in &f.breaks {
            let Some(other) = all.iter().find(|o| o.id.as_deref() == Some(id.as_str())) else { continue };
            let hits = match &other.version {
                Some(v) => crate::java_mods::runs_on(which, v) != Some(false),
                None => true,
            };
            if hits {
                out.push(format!(
                    "{} doesn't work together with {}{}: take one of them out of your mods.",
                    title(f),
                    title(other),
                    other.version.as_deref().map(|v| format!(" {v}")).unwrap_or_default()
                ));
            }
        }
    }
    out
}

/// What a run of [`resolve`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Report {
    /// Mod id → the file downloaded for it.
    pub added: BTreeMap<String, String>,
    /// Mod id → why it couldn't be had.
    pub unresolved: BTreeMap<String, String>,
    /// Mod id → (old file, new file): replaced by a version its mods accept.
    pub updated: BTreeMap<String, (String, String)>,
    /// What still keeps the set from starting, said plainly (outdated with no fitting version, clashes).
    pub problems: Vec<String>,
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
        for (id, (old, new)) in &self.updated {
            out.push(format!("Updated {id} ({old} -> {new}): your mods need a newer one. The old file is kept as {old}.disabled."));
        }
        out.extend(self.problems.iter().cloned());
        out
    }
}

/// Finds a Fabric 26.3 file for mod id `id` on `source` (Modrinth): the project whose slug is the
/// id, else the first search hit whose slug or title matches it.
fn find(source: &dyn Source, id: &str) -> Result<(browser::Project, browser::Version), String> {
    let candidates: Vec<String> = {
        // Mod ids and Modrinth slugs differ in common ways: snake_case, and libraries listed as
        // "<id>-api" (Architectury is architectury-api on Modrinth).
        let dashed = id.replace('_', "-");
        let mut c = vec![id.to_string(), dashed.clone(), format!("{dashed}-api")];
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
        let norm = |s: &str| s.to_ascii_lowercase().replace(['-', '_', ' ', '\'', '.'], "");
        let want = norm(id);
        // A search for an id written as one word ("forgeconfigapiport") finds nothing on
        // Modrinth, whose names have spaces ("Forge Config API Port"): its leading part does.
        let mut queries = vec![id.replace(['_', '-'], " ")];
        for cut in [want.len() * 2 / 3, 8, 5] {
            if cut >= 4 && cut < want.len() && want.is_char_boundary(cut) {
                queries.push(want[..cut].to_string());
            }
        }
        queries.dedup();
        'search: for text in queries {
            let mut q = browser::Query::new(&text, Kind::Mod);
            q.limit = 20;
            for p in source.search(&q).unwrap_or_default() {
                let (slug, title) = (norm(&p.slug), norm(&p.title));
                let api = format!("{want}api");
                if slug == want || title == want || slug == api || title == api {
                    project = Some(p);
                    break 'search;
                }
            }
        }
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
        let mut provided_now: BTreeSet<String> = BTreeSet::new();
        for id in need {
            // A jar downloaded earlier in this round may provide it (Balm provides balm-fabric).
            if provided_now.contains(&id) {
                continue;
            }
            log(&format!("Java mods: looking for \"{id}\", which a mod needs"));
            match find(source, &id).and_then(|(project, version)| {
                let file = version.file().cloned().ok_or("no file")?;
                if dir.join(&file.name).exists() {
                    return Err(format!("{} is there but doesn't provide \"{id}\"", file.name));
                }
                let path = browser::download::fetch(&file, dir, &|_| {})?;
                Ok((project.title, file.name, facts(&path).ids))
            }) {
                Ok((title, file, ids)) => {
                    provided_now.extend(ids);
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
    // Required mods present at a version the requiring mod excludes: the newest Fabric 26.3 version
    // replaces the folder's copy when it fits; else the player is told which mod wants what.
    let jars: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .collect();
    let all: Vec<JarFacts> = jars.iter().map(|j| facts(j)).collect();
    for o in outdated(&all) {
        if report.updated.contains_key(&o.id) {
            continue;
        }
        let old = jars
            .iter()
            .zip(&all)
            .find(|(_, f)| f.id.as_deref() == Some(o.id.as_str()))
            .map(|(p, _)| p.clone());
        let fitted = find(source, &o.id).and_then(|(_, version)| {
            let file = version.file().cloned().ok_or("no file")?;
            let wants: serde_json::Value = all
                .iter()
                .find(|f| title(f) == o.needed_by)
                .and_then(|f| f.wants.get(&o.id).cloned())
                .unwrap_or(serde_json::Value::Null);
            if crate::java_mods::runs_on(&wants, version.number.trim_start_matches('v')) == Some(false) {
                return Err(format!("Modrinth's newest is {}", version.number));
            }
            let old = old.clone().ok_or("no file")?;
            if old.file_name().is_some_and(|n| n.to_string_lossy() == file.name) {
                return Err("the newest is the one there".into());
            }
            browser::download::fetch(&file, dir, &|_| {})?;
            let aside = old.with_extension("jar.disabled");
            std::fs::rename(&old, &aside).map_err(|e| e.to_string())?;
            Ok((old.file_name().unwrap_or_default().to_string_lossy().into_owned(), file.name))
        });
        match fitted {
            Ok((old, new)) => {
                log(&format!("Java mods: updated {} ({old} -> {new})", o.id));
                report.updated.insert(o.id.clone(), (old, new));
            }
            Err(why) => report.problems.push(format!(
                "{} needs {} {}, but you have {} ({why}): update {} or take {} out.",
                o.needed_by, o.id, o.wants, o.have, o.id, o.needed_by
            )),
        }
    }
    let after: Vec<JarFacts> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .map(|p| facts(&p))
        .collect();
    report.problems.extend(clashes(&after));
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(ids: &[&str], requires: &[&str]) -> JarFacts {
        JarFacts {
            ids: ids.iter().map(|s| s.to_string()).collect(),
            requires: requires.iter().map(|s| s.to_string()).collect(),
            ..Default::default()
        }
    }

    fn m(id: &str, version: &str, wants: &[(&str, &str)], breaks: &[(&str, &str)]) -> JarFacts {
        JarFacts {
            ids: [id.to_string()].into(),
            requires: wants.iter().map(|(k, _)| k.to_string()).collect(),
            id: Some(id.into()),
            name: Some(id.to_uppercase()),
            version: Some(version.into()),
            wants: wants.iter().map(|(k, v)| (k.to_string(), serde_json::json!(v))).collect(),
            breaks: breaks.iter().map(|(k, v)| (k.to_string(), serde_json::json!(v))).collect(),
        }
    }

    #[test]
    fn an_old_required_mod_is_found() {
        let all = vec![m("waystones", "1.0", &[("balm", ">=26.3.0.3")], &[]), m("balm", "26.3.0.1", &[], &[])];
        let o = outdated(&all);
        assert_eq!(o.len(), 1);
        assert_eq!((o[0].id.as_str(), o[0].have.as_str(), o[0].wants.as_str()), ("balm", "26.3.0.1", ">=26.3.0.3"));
        let ok = vec![m("waystones", "1.0", &[("balm", ">=26.3.0.3")], &[]), m("balm", "26.3.0.4", &[], &[])];
        assert!(outdated(&ok).is_empty());
    }

    #[test]
    fn breaking_pairs_are_said_plainly() {
        let all = vec![m("a", "1.0", &[], &[("b", "*")]), m("b", "2.0", &[], &[])];
        assert_eq!(clashes(&all), ["A doesn't work together with B 2.0: take one of them out of your mods."]);
        let fixed = vec![m("a", "1.0", &[], &[("b", "<2.0")]), m("b", "2.0", &[], &[])];
        assert!(clashes(&fixed).is_empty());
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
