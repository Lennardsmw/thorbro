//! Light bar of Sony controllers connected to this computer (DualSense, DualShock 4), USB and Bluetooth.
use hidapi::{BusType, HidApi};
use serde_json::{json, Value};

const SONY: u16 = 0x054C;
const DUALSENSE: &[u16] = &[0x0CE6, 0x0DF2];
const DS4: &[u16] = &[0x05C4, 0x09CC];

pub fn list() -> Vec<Value> {
    let Ok(api) = HidApi::new() else { return vec![] };
    let mut out: Vec<Value> = vec![];
    for d in api.device_list() {
        if d.vendor_id() != SONY { continue; }
        let kind = if DUALSENSE.contains(&d.product_id()) { "DualSense" } else if DS4.contains(&d.product_id()) { "DualShock 4" } else { continue };
        let path = d.path().to_string_lossy().to_string();
        if out.iter().any(|o| o["path"] == path) { continue; }
        let bt = matches!(d.bus_type(), BusType::Bluetooth);
        out.push(json!({ "path": path, "name": format!("{kind} ({})", if bt { "Bluetooth" } else { "USB" }) }));
    }
    out
}

fn crc(report: &[u8]) -> [u8; 4] {
    // Bluetooth output reports carry a CRC32 over 0xA2 + report
    let mut h = crc32fast::Hasher::new();
    h.update(&[0xA2]);
    h.update(report);
    h.finalize().to_le_bytes()
}

/// rgb = 0 switches the light bar off
pub fn set(path: &str, rgb: u32) -> Result<(), String> {
    let api = HidApi::new().map_err(|e| e.to_string())?;
    let info = api.device_list().find(|d| d.path().to_string_lossy() == path).ok_or("Controller nicht gefunden")?;
    let (r, g, b) = (((rgb >> 16) & 0xFF) as u8, ((rgb >> 8) & 0xFF) as u8, (rgb & 0xFF) as u8);
    let bt = matches!(info.bus_type(), BusType::Bluetooth);
    let ds = DUALSENSE.contains(&info.product_id());
    let dev = info.open_device(&api).map_err(|e| e.to_string())?;
    let report: Vec<u8> = match (ds, bt) {
        (true, false) => {
            let mut p = vec![0u8; 48];
            p[0] = 0x02; p[2] = 0x04; // valid flag: light bar color
            p[45] = r; p[46] = g; p[47] = b;
            p
        }
        (true, true) => {
            let mut p = vec![0u8; 78];
            p[0] = 0x31; p[1] = 0x02;
            p[3] = 0x04;
            p[46] = r; p[47] = g; p[48] = b;
            let c = crc(&p[..74]); p[74..78].copy_from_slice(&c);
            p
        }
        (false, false) => {
            let mut p = vec![0u8; 32];
            p[0] = 0x05; p[1] = 0x02; // flag: LED
            p[6] = r; p[7] = g; p[8] = b;
            p
        }
        (false, true) => {
            let mut p = vec![0u8; 78];
            p[0] = 0x11; p[1] = 0xC0; p[3] = 0x02;
            p[8] = r; p[9] = g; p[10] = b;
            let c = crc(&p[..74]); p[74..78].copy_from_slice(&c);
            p
        }
    };
    dev.write(&report).map_err(|e| e.to_string())?;
    Ok(())
}
