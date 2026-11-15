//! The oracle's blocks and items of a namespace the mod owns, declared under it.
use super::*;

/// Declares the oracle's blocks and items under `ns` (the mod's id or another namespace it
/// owns: BuildCraft's are under `buildcraftenergy`...) in `data/<ns>/horizon/`, with their
/// self-drop loot tables, into `report`: under the id by path, under another as `ns:path`.
#[allow(clippy::too_many_arguments)]
pub(super) fn namespace(
    ns: &str,
    id: &str,
    facts: &Facts,
    allowed: &dyn Fn(&str) -> bool,
    owned: &[&str],
    block_loot: &BTreeSet<String>,
    inside: &Path,
    report: &mut Report,
) -> Result<(), String> {
    let mut declared = BTreeSet::new();
    let prefix = format!("{ns}:");
    let decl = inside.join(format!("data/{ns}/horizon"));
    let write_decl = |kind: &str, name: &str, v: &Value| {
        // Compact: a block with many states (Diagonal Fences' snow fence: 9 layers x 8 sides) has a state
        // table of millions of entries, 19 MiB pretty-printed (over the content reader's 16 MiB per file) and
        // 5.6 MiB compact. Pretty-printing only cost load time and size. (UMP, 2026-11-11)
        let text = serde_json::to_vec(v).unwrap_or_default();
        write(&decl.join(format!("{kind}/{name}.json")), &text)
    };
    // The report's names: the path under the id, `ns:path` under another namespace.
    let key = |b: &str| {
        if ns == id {
            b.to_owned()
        } else {
            format!("{ns}:{b}")
        }
    };
    for (full, block) in facts.blocks.range(prefix.clone()..) {
        let Some(b) = full.strip_prefix(&prefix) else {
            break;
        };
        let (choice, own) = match facts.template(full, allowed) {
            Some(choice) => (choice, None),
            None => match facts.own_states(full, allowed) {
                Some((choice, states)) => (choice, Some(states)),
                None => {
                    let props: Vec<&str> = block.properties.keys().map(String::as_str).collect();
                    let why = format!(
                        "no vanilla {} with properties [{}]",
                        block.vanilla_class().rsplit('.').next().unwrap_or(""),
                        props.join(", ")
                    );
                    report.blocks_unknown.insert(key(b), why);
                    continue;
                }
            },
        };
        let mut v = json!({"template": choice.template, "properties": choice.properties});
        if let Some(states) = own {
            let key = if states.is_array() {
                "states"
            } else {
                "state_table"
            };
            v[key] = states;
        }
        let template_item = facts.blocks.get(&choice.template).map(|t| t.item.as_str());
        if block.item == "minecraft:air" || template_item == Some("minecraft:air") {
            v["item"] = json!(false);
        } else if block.item != *full {
            v["item"] = json!(false);
            report
                .differences
                .entry(key(b))
                .or_default()
                .push(format!("its item is {}", block.item));
        } else {
            declared.insert(full.clone());
            let drop = (!block_loot.contains(b))
                .then(|| block.drops_itself(full))
                .flatten();
            if let Some(drop) = drop {
                let mut entry = json!({"type": "minecraft:item", "name": full});
                if let crate::oracle::SelfDrop::When(property, value) = drop {
                    entry["condition"] = json!({
                        "type": "minecraft:match_block",
                        "blocks": full,
                        "state": {property: value}
                    });
                }
                let table = json!({
                    "type": "minecraft:block",
                    "pools": [{
                        "condition": {"type": "minecraft:survives_explosion"},
                        "entries": [entry],
                        "rolls": 1
                    }],
                    "random_sequence": format!("{ns}:blocks/{b}")
                });
                let file = inside.join(format!("data/{ns}/loot_table/blocks/{b}.json"));
                write(
                    &file,
                    &serde_json::to_vec_pretty(&table).unwrap_or_default(),
                )?;
                report.loot_tables_added += 1;
            }
        }
        write_decl("block", b, &v)?;
        report.blocks.insert(key(b), choice.template);
        if !choice.differences.is_empty() {
            report
                .differences
                .entry(key(b))
                .or_default()
                .extend(choice.differences);
        }
    }
    for (full, item) in facts.items.range(prefix.clone()..) {
        let Some(name) = full.strip_prefix(&prefix) else {
            break;
        };
        if declared.contains(full) {
            continue;
        }
        if item
            .block
            .as_ref()
            .is_some_and(|b| facts.blocks.contains_key(b))
            && report.blocks_unknown.contains_key(&key(name))
        {
            report
                .items_unknown
                .insert(key(name), "its block is left out".into());
            continue;
        }
        match facts.item(full, owned) {
            Ok((v, differences)) => {
                write_decl("item", name, &v)?;
                report.items += 1;
                if !differences.is_empty() {
                    report.differences.insert(key(name), differences);
                }
            }
            Err(e) => {
                report.items_unknown.insert(key(name), e);
            }
        }
    }
    Ok(())
}
