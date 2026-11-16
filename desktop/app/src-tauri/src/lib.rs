mod config;
mod sync;

use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

static LAST: Mutex<Option<(u64, Vec<String>)>> = Mutex::new(None);
static PENDING_LOGIN: Mutex<Option<Value>> = Mutex::new(None);

fn do_sync() -> Vec<String> {
    let lines = sync::run();
    *LAST.lock().unwrap() = Some((sync::now_secs(), lines.clone()));
    lines
}

#[tauri::command]
fn status() -> Value {
    let c = config::load();
    let last = LAST.lock().unwrap().clone();
    json!({
        "thors": c.thors.iter().map(|t| json!({"name": t.name, "address": t.address})).collect::<Vec<_>>(),
        "romm": { "url": c.romm_url, "connected": !c.romm_token.is_empty(), "pending": PENDING_LOGIN.lock().unwrap().clone() },
        "items": c.items.iter().map(|i| json!({"name": i.name, "dir": i.dir, "exists": std::path::Path::new(&i.dir).is_dir()})).collect::<Vec<_>>(),
        "auto": c.auto_sync,
        "last": last.map(|(t, l)| json!({"at": t, "lines": l})),
    })
}

/// looks for ThorBro on the local network (it announces itself as _thorbro._tcp)
#[tauri::command]
async fn discover() -> Vec<Value> {
    tauri::async_runtime::spawn_blocking(|| {
        let mut out: Vec<Value> = vec![];
        let Ok(d) = mdns_sd::ServiceDaemon::new() else { return out };
        let Ok(rx) = d.browse("_thorbro._tcp.local.") else { return out };
        let until = std::time::Instant::now() + Duration::from_secs(4);
        while let Some(left) = until.checked_duration_since(std::time::Instant::now()) {
            match rx.recv_timeout(left) {
                Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) => {
                    let name = info.get_fullname().split("._thorbro").next().unwrap_or("ThorBro").to_string();
                    for ip in info.get_addresses_v4() {
                        let address = format!("{}:{}", ip, info.get_port());
                        if !out.iter().any(|o| o["address"] == address) { out.push(json!({"name": name, "address": address})); }
                    }
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        let _ = d.shutdown();
        out
    }).await.unwrap_or_default()
}

/// checks the pairing code shown on the handheld and remembers this Thor
#[tauri::command]
async fn pair(address: String, code: String) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let address = address.trim().trim_start_matches("http://").trim_end_matches('/').to_string();
        let address = if address.contains(':') { address } else { format!("{address}:7330") };
        let code = code.trim().to_uppercase();
        let r = sync::client().get(format!("http://{address}/api/info")).bearer_auth(&code).send().map_err(|e| format!("Thor nicht erreichbar: {e}"))?;
        if r.status().as_u16() == 401 { return Err("Kopplungscode stimmt nicht".into()); }
        let info: Value = r.json().map_err(|e| e.to_string())?;
        let mut c = config::load();
        let name = format!("{} {}", info["manufacturer"].as_str().unwrap_or(""), info["model"].as_str().unwrap_or("Thor")).trim().to_string();
        c.thors.retain(|t| t.address != address);
        c.thors.push(config::Thor { name: name.clone(), address: address.clone(), token: code });
        config::save(&c);
        Ok(json!({"name": name, "address": address}))
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn forget_thor(address: String) {
    let mut c = config::load();
    c.thors.retain(|t| t.address != address);
    config::save(&c);
}

/// opens ThorBro's own interface from the handheld in a window
#[tauri::command]
fn open_thor(app: AppHandle, address: String) -> Result<(), String> {
    let c = config::load();
    let t = c.thors.iter().find(|t| t.address == address).ok_or("unbekannter Thor")?;
    let url = format!("http://{}/?token={}", t.address, t.token);
    let label = format!("thor-{}", address.replace(['.', ':'], "-"));
    if let Some(w) = app.get_webview_window(&label) { let _ = w.show(); let _ = w.set_focus(); return Ok(()); }
    WebviewWindowBuilder::new(&app, label, WebviewUrl::External(url.parse().map_err(|e| format!("{e}"))?))
        .title(format!("ThorBro – {}", t.name)).inner_size(1180.0, 800.0).build().map_err(|e| e.to_string())?;
    Ok(())
}

/// RomM device sign-in: returns the code to approve in RomM, then waits for it in the background
#[tauri::command]
async fn romm_login(url: String) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let mut c = config::load();
        c.romm_url = url.trim().trim_end_matches('/').to_string();
        config::save(&c);
        let init: Value = sync::client().post(format!("{}/api/auth/device/init", c.romm_url))
            .json(&json!({
                "client_device_identifier": c.device, "name": format!("ThorBro Desktop ({})", hostname()),
                "client": "ThorBro", "platform": std::env::consts::OS,
                "requested_scopes": ["me.read", "platforms.read", "roms.read", "assets.read", "assets.write"],
            })).send().map_err(|e| format!("RomM nicht erreichbar: {e}"))?.json().map_err(|e| e.to_string())?;
        let pending = json!({"code": init["user_code"], "verify": format!("{}{}", c.romm_url, init["verification_path_complete"].as_str().unwrap_or(""))});
        *PENDING_LOGIN.lock().unwrap() = Some(pending.clone());
        let device_code = init["device_code"].as_str().unwrap_or("").to_string();
        let interval = init["interval"].as_u64().unwrap_or(5).max(2);
        let expires = init["expires_in"].as_u64().unwrap_or(600);
        let base = c.romm_url.clone();
        std::thread::spawn(move || {
            let until = sync::now_secs() + expires;
            while sync::now_secs() < until {
                std::thread::sleep(Duration::from_secs(interval));
                let r = sync::client().post(format!("{base}/api/auth/device/token")).json(&json!({"device_code": device_code})).send();
                if let Ok(r) = r {
                    if let Ok(v) = r.json::<Value>() {
                        if let Some(tok) = v["access_token"].as_str() {
                            let mut c = config::load();
                            c.romm_token = tok.to_string();
                            config::save(&c);
                            break;
                        }
                    }
                }
            }
            *PENDING_LOGIN.lock().unwrap() = None;
        });
        Ok(pending)
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn sync_now() -> Vec<String> {
    tauri::async_runtime::spawn_blocking(do_sync).await.unwrap_or_default()
}

#[tauri::command]
fn set_auto(on: bool) {
    let mut c = config::load();
    c.auto_sync = on;
    config::save(&c);
}

fn hostname() -> String { sysinfo::System::host_name().unwrap_or_else(|_| "Computer".into()) }

/// syncs every 5 minutes and shortly after a game wrote its save folder
fn background() {
    use notify::{RecursiveMode, Watcher};
    std::thread::spawn(|| {
        let (tx, rx) = std::sync::mpsc::channel();
        let mut watcher = notify::recommended_watcher(move |_res: notify::Result<notify::Event>| { let _ = tx.send(()); }).ok();
        let mut watched: Vec<String> = vec![];
        let mut last_run = 0u64;
        loop {
            let c = config::load();
            if let Some(w) = watcher.as_mut() {
                for it in &c.items {
                    if !watched.contains(&it.dir) && std::path::Path::new(&it.dir).is_dir() {
                        if w.watch(std::path::Path::new(&it.dir), RecursiveMode::Recursive).is_ok() { watched.push(it.dir.clone()); }
                    }
                }
            }
            // a change: wait until the game finished writing (no events for 30 s)
            let changed = rx.recv_timeout(Duration::from_secs(60)).is_ok();
            if changed { while rx.recv_timeout(Duration::from_secs(30)).is_ok() {} }
            let due = sync::now_secs() - last_run >= 300;
            if c.auto_sync && !c.romm_token.is_empty() && (changed || due) {
                do_sync();
                last_run = sync::now_secs();
            }
        }
    });
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![status, discover, pair, forget_thor, open_thor, romm_login, sync_now, set_auto])
        .setup(|app| {
            // start with the computer, once; the user can turn it off in the system settings
            let al = app.autolaunch();
            if !al.is_enabled().unwrap_or(true) { let _ = al.enable(); }
            if std::env::args().any(|a| a == "--hidden") {
                if let Some(w) = app.get_webview_window("main") { let _ = w.hide(); }
            }
            let open = MenuItem::with_id(app, "open", "ThorBro öffnen", true, None::<&str>)?;
            let sync_item = MenuItem::with_id(app, "sync", "Spielstände jetzt synchronisieren", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Beenden", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &sync_item, &quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("ThorBro")
                .menu(&menu)
                .on_menu_event(|app, e| match e.id.as_ref() {
                    "open" => { if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.set_focus(); } }
                    "sync" => { std::thread::spawn(do_sync); }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;
            background();
            Ok(())
        })
        .on_window_event(|w, e| {
            // closing the main window keeps ThorBro running in the menu bar / tray
            if let tauri::WindowEvent::CloseRequested { api, .. } = e {
                if w.label() == "main" { api.prevent_close(); let _ = w.hide(); }
            }
        })
        .run(tauri::generate_context!())
        .expect("ThorBro konnte nicht starten");
}
