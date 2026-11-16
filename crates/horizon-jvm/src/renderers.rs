//! Mods that replace Minecraft's renderer (Sodium, Iris...): Horizon draws the world with its
//! own, so they have nothing to do in it, and their mixins into vanilla's renderer fail. They
//! are left out of the mods Fabric loads, with the mods that need them (Fabric would refuse to
//! start otherwise), and the players are told they aren't needed rather than shown a failure.
//! For every jar in `mods-java/`, whoever put it there.

use std::collections::BTreeSet;
use std::io::Read;
use std::path::{Path, PathBuf};

/// Fabric ids of mods that replace the renderer.
pub const RENDERERS: [&str; 5] = ["sodium", "iris", "canvas", "nvidium", "vulkanmod"];

/// Fabric ids of performance-only mods: they make vanilla Java Minecraft faster without changing what
/// it does. Horizon is its own (Rust) engine, so their patches have nothing to speed up and only
/// risk failing; they are left out like the renderers. (UMP, 2026-11-10.)
pub const PERFORMANCE: [&str; 18] = [
    "lithium", "ferritecore", "c2me", "krypton", "moreculling", "modernfix", "scalablelux", "noisium",
    "vmp", "immediatelyfast", "entityculling", "dynamic_fps", "lazydfu", "starlight",
    "enhancedblockentities", "memoryleakfix", "cullleaves", "badoptimizations",
];

/// Mods Fabric loads that only partly work in Horizon, with the line players read at their
/// join (told as the left-out ones are).
pub const PARTIAL: [(&str, &str); 1] = [(
    "flashback",
    "Flashback can't record or replay in Horizon yet: it needs Minecraft's own client world and renderer \
     (ReplayMod records, and Horizon plays its recordings: --replay <file>)",
)];

/// The [`PARTIAL`] lines for the mods loaded (`mods`: Fabric's "id version" list).
pub fn partial_lines(mods: &[String]) -> Vec<String> {
    PARTIAL
        .iter()
        .filter(|(id, _)| mods.iter().any(|m| m.split(' ').next() == Some(id)))
        .map(|(_, line)| (*line).to_string())
        .collect()
}

/// A jar left out, and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LeftOut {
    pub jar: PathBuf,
    /// The mod's name (`fabric.mod.json`'s `name`, else its id).
    pub name: String,
    /// The renderer mod it needs; None for a renderer itself.
    pub needs: Option<String>,
    /// A performance mod (or one needing only performance mods), not a renderer.
    pub performance: bool,
}

impl LeftOut {
    /// The line players read: "Sodium isn't needed in Horizon: Horizon has its own renderer".
    pub fn line(&self) -> String {
        match &self.needs {
            None if self.performance => format!(
                "{} isn't needed in Horizon: Horizon's own engine is already fast (left out)",
                self.name
            ),
            None => format!(
                "{} isn't needed in Horizon: Horizon has its own renderer (left out)",
                self.name
            ),
            Some(id) => format!(
                "{} isn't needed in Horizon: it adds to {id}, which Horizon doesn't need (left out)",
                self.name
            ),
        }
    }
}

struct Info {
    id: Option<String>,
    name: Option<String>,
    requires: Vec<String>,
}

fn read_info(jar: &Path) -> Info {
    let json: Option<serde_json::Value> = std::fs::File::open(jar)
        .ok()
        .and_then(|f| zip::ZipArchive::new(f).ok())
        .and_then(|mut z| {
            let mut text = String::new();
            z.by_name("fabric.mod.json")
                .ok()?
                .read_to_string(&mut text)
                .ok()?;
            // Fabric's reader takes raw line breaks inside strings, and mods use them.
            serde_json::from_str(&text.replace(['\n', '\r', '\t'], " ")).ok()
        });
    let Some(json) = json else {
        return Info {
            id: None,
            name: None,
            requires: Vec::new(),
        };
    };
    Info {
        id: json["id"].as_str().map(str::to_string),
        name: json["name"].as_str().map(str::to_string),
        requires: json["depends"]
            .as_object()
            .map(|d| d.keys().cloned().collect())
            .unwrap_or_default(),
    }
}

/// The jar's Fabric mod id (`fabric.mod.json`'s `id`), if it has one.
pub fn mod_id(jar: &Path) -> Option<String> {
    read_info(jar).id
}

/// `jars` split into the ones Fabric loads and the ones left out (in `jars`' order).
pub fn split(jars: &[PathBuf]) -> (Vec<PathBuf>, Vec<LeftOut>) {
    let infos: Vec<Info> = jars.iter().map(|j| read_info(j)).collect();
    split_infos(jars, &infos)
}

fn split_infos(jars: &[PathBuf], infos: &[Info]) -> (Vec<PathBuf>, Vec<LeftOut>) {
    let mut gone: BTreeSet<String> = BTreeSet::new();
    let mut perf_gone: BTreeSet<String> = BTreeSet::new();
    let mut needs: Vec<Option<Option<String>>> = vec![None; jars.len()];
    for (i, info) in infos.iter().enumerate() {
        if let Some(id) = info.id.as_deref().filter(|id| RENDERERS.contains(id) || PERFORMANCE.contains(id)) {
            needs[i] = Some(None);
            gone.insert(id.to_string());
            if PERFORMANCE.contains(&id) {
                perf_gone.insert(id.to_string());
            }
        }
    }
    // What needs a left-out mod goes too, and what needs that.
    loop {
        let mut changed = false;
        for (i, info) in infos.iter().enumerate() {
            if needs[i].is_some() {
                continue;
            }
            if let Some(need) = info.requires.iter().find(|r| gone.contains(r.as_str())) {
                needs[i] = Some(Some(need.clone()));
                if let Some(id) = &info.id {
                    gone.insert(id.clone());
                }
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    let mut kept = Vec::new();
    let mut left = Vec::new();
    for ((jar, info), need) in jars.iter().zip(infos).zip(needs) {
        match need {
            None => kept.push(jar.clone()),
            Some(needs) => left.push(LeftOut {
                jar: jar.clone(),
                name: info
                    .name
                    .clone()
                    .or_else(|| info.id.clone())
                    .unwrap_or_default(),
                performance: match &needs {
                    None => info.id.as_deref().is_some_and(|id| perf_gone.contains(id)),
                    Some(dep) => perf_gone.contains(dep),
                },
                needs,
            }),
        }
    }
    (kept, left)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(id: &str, name: &str, requires: &[&str]) -> Info {
        Info {
            id: Some(id.into()),
            name: Some(name.into()),
            requires: requires.iter().map(|r| r.to_string()).collect(),
        }
    }

    #[test]
    fn renderers_and_what_needs_them_are_left_out() {
        let jars: Vec<PathBuf> = ["fd.jar", "sodium.jar", "extra.jar", "iris.jar", "api.jar"]
            .iter()
            .map(PathBuf::from)
            .collect();
        let infos = vec![
            info("farmersdelight", "Farmer's Delight", &["fabric-api"]),
            info("sodium", "Sodium", &[]),
            info("sodium-extra", "Sodium Extra", &["sodium"]),
            info("iris", "Iris", &["sodium"]),
            info("fabric-api", "Fabric API", &[]),
        ];
        let (kept, left) = split_infos(&jars, &infos);
        assert_eq!(kept, [PathBuf::from("fd.jar"), PathBuf::from("api.jar")]);
        let names: Vec<_> = left
            .iter()
            .map(|l| (l.name.as_str(), l.needs.clone()))
            .collect();
        assert_eq!(
            names,
            [
                ("Sodium", None),
                ("Sodium Extra", Some("sodium".into())),
                ("Iris", None)
            ]
        );
    }

    #[test]
    fn performance_mods_are_left_out_with_their_own_line() {
        let jars: Vec<PathBuf> = ["fd.jar", "lith.jar", "fc.jar"].iter().map(PathBuf::from).collect();
        let infos = vec![
            info("farmersdelight", "Farmer's Delight", &[]),
            info("lithium", "Lithium", &[]),
            info("ferritecore", "FerriteCore", &[]),
        ];
        let (kept, left) = split_infos(&jars, &infos);
        assert_eq!(kept, [PathBuf::from("fd.jar")]);
        assert!(left.iter().all(|l| l.performance));
        assert_eq!(
            left[0].line(),
            "Lithium isn't needed in Horizon: Horizon's own engine is already fast (left out)"
        );
    }

    #[test]
    fn a_pack_without_renderers_is_untouched() {
        let jars = vec![PathBuf::from("a.jar"), PathBuf::from("b.jar")];
        let infos = vec![info("a", "A", &["b"]), info("b", "B", &[])];
        let (kept, left) = split_infos(&jars, &infos);
        assert_eq!(kept, jars);
        assert!(left.is_empty());
    }
}
