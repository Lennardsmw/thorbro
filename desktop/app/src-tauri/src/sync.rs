//! Save sync with RomM, same rules as ThorBro on the handheld:
//! only local changes → upload, only RomM changes → download, both → the newer one wins.
//! The older state is always zipped into the backups folder first.
use crate::config::{self, Config, ItemState, SaveItem};
use md5::{Digest, Md5};
use reqwest::blocking::{multipart, Client};
use serde_json::Value;
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub fn client() -> Client {
    Client::builder().timeout(Duration::from_secs(60)).user_agent("ThorBro-Desktop").build().unwrap()
}

fn api(c: &Config, path: &str) -> String { format!("{}{}", c.romm_url.trim_end_matches('/'), path) }

fn get_json(c: &Config, path: &str) -> Result<Value, String> {
    let r = client().get(api(c, path)).bearer_auth(&c.romm_token).send().map_err(|e| e.to_string())?;
    if !r.status().is_success() { return Err(format!("RomM HTTP {}", r.status())); }
    r.json().map_err(|e| e.to_string())
}

fn files(dir: &Path) -> Vec<PathBuf> {
    let mut out = vec![];
    fn walk(d: &Path, out: &mut Vec<PathBuf>) {
        if let Ok(rd) = std::fs::read_dir(d) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() { walk(&p, out) } else { out.push(p) }
            }
        }
    }
    walk(dir, &mut out);
    out.sort();
    out
}

/// same hash as on the handheld: relative path + content of every file, sorted by path
pub fn folder_hash(dir: &Path) -> String {
    let mut md = Md5::new();
    for f in files(dir) {
        md.update(f.strip_prefix(dir).unwrap().to_string_lossy().replace('\\', "/").as_bytes());
        if let Ok(b) = std::fs::read(&f) { md.update(&b) }
    }
    md.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

fn newest_mtime(dir: &Path) -> f64 {
    files(dir).iter().filter_map(|f| f.metadata().ok()?.modified().ok())
        .map(|t| t.duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)).fold(0.0, f64::max)
}

fn zip_folder(dir: &Path) -> Vec<u8> {
    let top = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "save".into());
    let mut buf = Cursor::new(vec![]);
    {
        let mut z = zip::ZipWriter::new(&mut buf);
        let opts: zip::write::SimpleFileOptions = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for f in files(dir) {
            let rel = f.strip_prefix(dir).unwrap().to_string_lossy().replace('\\', "/");
            if z.start_file(format!("{top}/{rel}"), opts).is_ok() {
                if let Ok(b) = std::fs::read(&f) { let _ = z.write_all(&b); }
            }
        }
        let _ = z.finish();
    }
    buf.into_inner()
}

fn unzip_stripped(data: &[u8], dir: &Path) -> Result<(), String> {
    let mut z = zip::ZipArchive::new(Cursor::new(data)).map_err(|e| e.to_string())?;
    for i in 0..z.len() {
        let mut e = z.by_index(i).map_err(|e| e.to_string())?;
        if e.is_dir() { continue; }
        let name = e.name().map(|n| n.to_string()).unwrap_or_default();
        let rel = name.split_once('/').map(|(_, r)| r.to_string()).unwrap_or(name);
        if rel.is_empty() || rel.contains("..") { continue; }
        let target = dir.join(&rel);
        if let Some(p) = target.parent() { let _ = std::fs::create_dir_all(p); }
        let mut b = vec![];
        e.read_to_end(&mut b).map_err(|e| e.to_string())?;
        std::fs::write(&target, b).map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn running(name: &str) -> bool {
    if name.is_empty() { return false; }
    let Ok(mut s) = sysinfo::System::new() else { return false };
    s.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
    s.processes().values().any(|p| {
        p.exe().map(|e| e.to_string_lossy().contains(name)).unwrap_or(false) || p.name().to_string_lossy().contains(name)
    })
}

fn backup_dir() -> PathBuf { dirs::data_dir().unwrap_or_else(std::env::temp_dir).join("ThorBro").join("save-backups") }

fn find_rom(c: &Config, it: &SaveItem) -> Result<Option<i64>, String> {
    let plats = get_json(c, "/api/platforms")?;
    for p in plats.as_array().cloned().unwrap_or_default() {
        if p["fs_slug"].as_str() != Some(it.platform.as_str()) { continue; }
        let page = get_json(c, &format!("/api/roms?platform_ids={}&limit=500", p["id"]))?;
        for r in page["items"].as_array().cloned().unwrap_or_default() {
            if r["fs_name"].as_str() == Some(it.rom_file.as_str()) { return Ok(r["id"].as_i64()); }
        }
    }
    Ok(None)
}

fn time_of(s: &Value) -> f64 {
    let t = s["updated_at"].as_str().unwrap_or("");
    chrono::DateTime::parse_from_rfc3339(t).map(|d| d.timestamp() as f64)
        .or_else(|_| chrono::NaiveDateTime::parse_from_str(&t[..t.len().min(19)], "%Y-%m-%dT%H:%M:%S").map(|d| d.and_utc().timestamp() as f64))
        .unwrap_or(0.0)
}

/// one sync round over all save folders; returns a line per folder
pub fn run() -> Vec<String> {
    let mut c = config::load();
    if c.romm_token.is_empty() { return vec!["Noch nicht bei RomM angemeldet".into()]; }
    let mut out = vec![];
    for it in c.items.clone() {
        let dir = PathBuf::from(&it.dir);
        if !dir.is_dir() { out.push(format!("{}: Ordner fehlt", it.name)); continue; }
        let res = (|| -> Result<String, String> {
            let Some(rom) = find_rom(&c, &it)? else { return Ok("noch nicht in RomM".into()) };
            let saves = get_json(&c, &format!("/api/saves?rom_id={rom}"))?;
            let newest = saves.as_array().cloned().unwrap_or_default().into_iter().max_by_key(|s| s["updated_at"].as_str().unwrap_or("").to_string());
            let local = folder_hash(&dir);
            let st = c.state.get(&it.name).cloned().unwrap_or_default();
            let rkey = newest.as_ref().map(|s| format!("{}@{}", s["id"], s["updated_at"].as_str().unwrap_or("")));
            let local_changed = local != st.local;
            let remote_changed = rkey.is_some() && rkey.as_deref() != Some(st.remote.as_str());
            if !local_changed && !remote_changed { return Ok("aktuell".into()); }
            let mut download = remote_changed && !local_changed;
            if remote_changed && local_changed {
                download = time_of(newest.as_ref().unwrap()) > newest_mtime(&dir) + 30.0;
            }
            if download {
                if running(&it.process) { return Ok("neuerer Stand in RomM – wird geholt, sobald das Spiel zu ist".into()); }
                let b = backup_dir(); let _ = std::fs::create_dir_all(&b);
                let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S");
                let _ = std::fs::write(b.join(format!("{stamp}-{}.zip", it.name)), zip_folder(&dir));
                let s = newest.unwrap();
                let data = client().get(api(&c, &format!("/api/saves/{}/content", s["id"]))).bearer_auth(&c.romm_token)
                    .send().map_err(|e| e.to_string())?.bytes().map_err(|e| e.to_string())?;
                unzip_stripped(&data, &dir)?;
                c.state.insert(it.name.clone(), ItemState { local: folder_hash(&dir), remote: rkey.unwrap() });
                Ok("neuerer Stand aus RomM geholt (alter Stand gesichert)".into())
            } else {
                let name = format!("{}.zip", dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or("save".into()));
                let form = multipart::Form::new().part("saveFile", multipart::Part::bytes(zip_folder(&dir)).file_name(name));
                // no device_id: RomM would then also require the devices.write scope
                let r = client().post(api(&c, &format!("/api/saves?rom_id={rom}&emulator={}&overwrite=true", it.platform)))
                    .bearer_auth(&c.romm_token).multipart(form).send().map_err(|e| e.to_string())?;
                if !r.status().is_success() { return Err(format!("Upload: RomM HTTP {}", r.status())); }
                let s: Value = r.json().map_err(|e| e.to_string())?;
                c.state.insert(it.name.clone(), ItemState { local, remote: format!("{}@{}", s["id"], s["updated_at"].as_str().unwrap_or("")) });
                Ok("hochgeladen".into())
            }
        })();
        out.push(format!("{}: {}", it.name, res.unwrap_or_else(|e| format!("Fehler – {e}"))));
    }
    config::save(&c);
    out
}

pub fn now_secs() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

#[cfg(test)]
mod tests {
    /// THORBRO_HASH_DIR=<folder> THORBRO_HASH_EXPECT=<python hash> cargo test
    #[test]
    fn hash_matches_python() {
        if let (Ok(d), Ok(e)) = (std::env::var("THORBRO_HASH_DIR"), std::env::var("THORBRO_HASH_EXPECT")) {
            assert_eq!(super::folder_hash(std::path::Path::new(&d)), e);
        }
    }
}
