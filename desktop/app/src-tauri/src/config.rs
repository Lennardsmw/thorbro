//! Settings in <config dir>/ThorBro/desktop.json (Thors, RomM sign-in, save folders, sync state).
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Thor {
    pub name: String,
    pub address: String, // host:port
    pub token: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct SaveItem {
    pub name: String,
    pub dir: String,
    pub rom_file: String,
    pub platform: String,
    /// don't write while a process with this name runs
    #[serde(default)]
    pub process: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct ItemState {
    pub local: String,
    pub remote: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Config {
    #[serde(default)]
    pub thors: Vec<Thor>,
    #[serde(default)]
    pub romm_url: String,
    #[serde(default)]
    pub romm_token: String,
    #[serde(default)]
    pub device: String,
    #[serde(default)]
    pub items: Vec<SaveItem>,
    #[serde(default)]
    pub state: HashMap<String, ItemState>,
    #[serde(default = "yes")]
    pub auto_sync: bool,
}

fn yes() -> bool { true }

pub fn path() -> PathBuf {
    dirs::config_dir().unwrap_or_else(std::env::temp_dir).join("ThorBro").join("desktop.json")
}

/// where nxnative keeps the Ocarina of Time (Switch 2 version) save on this OS
fn default_items() -> Vec<SaveItem> {
    let base = if cfg!(target_os = "macos") {
        dirs::home_dir().map(|h| h.join("Library/Application Support/nxnative"))
    } else {
        dirs::data_dir().map(|d| d.join("nxnative"))
    };
    let dir = base.map(|b| b.join("0400486024DEC000").join("save")).unwrap_or_default();
    vec![SaveItem {
        name: "Ocarina of Time (Banana Native)".into(),
        dir: dir.to_string_lossy().into(),
        rom_file: "Ocarina of Time (Banana Native).port".into(),
        platform: "ports".into(),
        process: "Ocarina of Time".into(),
    }]
}

pub fn load() -> Config {
    let mut c: Config = std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    if c.items.is_empty() {
        c.items = default_items();
    }
    if c.device.is_empty() {
        c.device = uuid::Uuid::new_v4().to_string();
    }
    // take over the sign-in of the earlier command line tool (~/.config/thorbro/sync.json)
    if c.romm_token.is_empty() {
        if let Some(old) = dirs::home_dir().map(|h| h.join(".config/thorbro/sync.json")) {
            if let Ok(v) = std::fs::read_to_string(old).map(|t| serde_json::from_str::<serde_json::Value>(&t)) {
                if let Ok(v) = v {
                    c.romm_url = v["url"].as_str().unwrap_or_default().to_string();
                    c.romm_token = v["token"].as_str().unwrap_or_default().to_string();
                    if let Some(st) = v["state"].as_object() {
                        for (k, s) in st {
                            c.state.insert(k.clone(), ItemState {
                                local: s["local"].as_str().unwrap_or_default().into(),
                                remote: s["remote"].as_str().unwrap_or_default().into(),
                            });
                        }
                    }
                }
            }
        }
    }
    c
}

pub fn save(c: &Config) {
    let p = path();
    if let Some(d) = p.parent() { let _ = std::fs::create_dir_all(d); }
    if std::fs::write(&p, serde_json::to_string_pretty(c).unwrap_or_default()).is_ok() {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o600));
        }
    }
}
