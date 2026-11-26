<p align="center">
  <img src="companion/app/src/main/assets/web/mascot/base.svg" width="140" alt="ThorBro mascot">
</p>

<h1 align="center">ThorBro</h1>

<p align="center">
  <b>Your handheld's best bro.</b><br>
  Run your AYN Thor (or any Android handheld) from your Mac, PC or browser:<br>
  upload games, browse your ROM library, sync saves, stream, and play together with friends.
</p>

<p align="center">
  <img alt="Status: work in progress" src="https://img.shields.io/badge/status-work%20in%20progress-orange?style=for-the-badge">
  <img alt="Made with Claude" src="https://img.shields.io/badge/made%20with-Claude-D97757?style=for-the-badge&logo=anthropic&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-8FA876?style=for-the-badge"></a>
</p>

<p align="center">
  <a href="#-the-mission">Mission</a> ·
  <a href="#-features">Features</a> ·
  <a href="#-getting-started">Getting started</a> ·
  <a href="#-credits">Credits</a>
</p>

> [!WARNING]
> **ThorBro is a work in progress.** Things move fast, break sometimes and the UI changes a lot.
> Try it, have fun, and expect rough edges. Bug reports and ideas are very welcome.

---

## 🎯 The mission

Emulation is amazing, but getting started is way too hard. You need to know which folder each console
wants, which file format works, where the emulator hides its saves, how to get BIOS and firmware in place,
how to move gigabytes of games onto an SD card, and how to keep your progress when you switch devices.
Most people give up before they play their first game.

**ThorBro wants to make emulation easy for anyone, not just for people who enjoy reading forum threads.**

- **Drop it in and play.** You drop a game in, ThorBro finds the right console folder, unpacks it and puts it there.
- **Your saves follow you.** Your progress is backed up and synced between the handheld, your computer and your own server.
- **Play together.** Add friends, start a party and play online without setting up port forwarding.
- **No cables, no adb, no terminal.** Everything runs from a friendly UI on the handheld or in your browser.

The goal: hand someone a handheld, and they're playing their favourite childhood game a few minutes later.

## ✨ Features

| | |
|---|---|
| 📦 **Uploads** | Resumable upload queue from browser or desktop. ROMs land in the right console folder, APKs get installed. |
| 🕹️ **ROM library** | All your games grouped by console, with covers, info and hand-drawn console icons. |
| 💾 **Saves** | Saves per game, Thor ↔ [RomM](https://github.com/rommapp/romm) sync, a backup before every overwrite. |
| ☁️ **RomM** | Download from and upload to your own RomM server, including Switch keys and firmware. |
| 👯 **Friends & party** | Add friends with a controller-button code, form a party, send games and saves as gifts. |
| 🌍 **Online play** | Encrypted peer-to-peer tunnel (STUN, hole punching, AES-GCM) for netplay, even over mobile data. |
| 📺 **Streaming** | Mirror the Thor to your Mac with scrcpy, or play PC/Mac games on the Thor with Moonlight. |
| 🗂️ **Apps & files** | Launch and uninstall apps, browse internal storage and SD card. |
| 🎨 **Themes** | Pick between the "Glass" and "Doodle" look, with a mascot that reacts to what's going on. |
| 🔄 **Self-updating** | Installs new versions from GitHub releases on its own. |

## 🚀 Getting started

**1. Install the companion app on the Thor**

```bash
cd companion
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You only need to do this once. After that ThorBro updates itself from GitHub releases
(it checks every 6 h, or tap "Check for updates").

**2. Pair**

Open ThorBro on the Thor and grant the three permissions. Then open `http://<thor-ip>:7330` in a browser
and enter the code shown on the Thor. The desktop app (`desktop/app`, Tauri) finds the Thor on your network
by itself. Builds are under releases `desktop-v*`.

## 🏗️ How it works

```
Browser / desktop app (Tauri)
        │  HTTP, pairing code as bearer token
        ▼
companion/  Android app on the Thor (Kotlin + Ktor CIO, port 7330)
            ├─ foreground service, announced on the LAN via mDNS as _thorbro._tcp
            ├─ /api/...   device, apps, files, upload, install, saves, friends, streaming
            └─ /          web UI (assets/web), served directly by the Thor
```

<details>
<summary><b>API (short version)</b></summary>

Everything under `/api` except `/api/ping` needs `Authorization: Bearer <code>` (or `?token=`).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/info` | Device, battery, storage, permissions |
| GET | `/api/apps` · `/api/apps/{pkg}/icon` | Apps with a launcher entry |
| POST | `/api/apps/{pkg}/launch` · `/uninstall` | Launch / uninstall dialog |
| GET/DELETE | `/api/files?path=` | List folder / delete |
| GET | `/api/files/raw?path=` | Download a file |
| POST | `/api/files/mkdir` · `/api/files/move` | Create folder / move |
| GET | `/api/upload?path=&size=` | Upload state: `{offset, exists, free}` |
| PUT | `/api/upload?path=&offset=` | Append raw data; 409 + `{offset}` on wrong offset |
| POST | `/api/upload/finish?path=&size=&overwrite=` | Rename the `.thorbro-part` file |
| POST | `/api/install?path=` · GET `/api/install/{id}` | Install an APK (confirmed on the Thor) |

Paths are limited to internal storage and SD cards.
</details>

## 💛 Credits

**The idea for ThorBro comes from me ([@Lennardsmw](https://github.com/Lennardsmw)) and
[@catzeyea](https://github.com/catzeyea).** catzeyea builds a ThorBro too: the desktop app
[catzeyea/ThorBro](https://github.com/catzeyea/ThorBro) and its Android counterpart
[catzeyea/ThorBroAndroid](https://github.com/catzeyea/ThorBroAndroid). Go check them out and leave a ⭐!

From catzeyea's work (MIT licensed) we use:

- 🤖 **The ThorBro mascot** and the **"Doodle" look** (warm colours, cream outlines, the little robot bro),
  from [`ui/assets/mascot`](https://github.com/catzeyea/ThorBro/tree/main/ui/assets/mascot). See
  [`mascot/LICENSE`](companion/app/src/main/assets/web/mascot/LICENSE).
- 🎮 **"Play on Thor" for Moonlight**: starting a streamed game directly on the Thor through Moonlight's shortcut
  trampoline, ported from [`src/pcstream/thor.rs`](https://github.com/catzeyea/ThorBro/blob/main/src/pcstream/thor.rs)
  into [`Streaming.kt`](companion/app/src/main/java/wtf/lennard/thorbro/Streaming.kt).

Also thanks to:

- [PS5Upload](https://github.com/phantomptr/ps5upload): the original inspiration for uploading from a browser (no code taken)
- [RomM](https://github.com/rommapp/romm), [scrcpy](https://github.com/Genymobile/scrcpy),
  [Moonlight](https://github.com/moonlight-stream/moonlight-android) and [Sunshine](https://github.com/LizardByte/Sunshine)
- [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) and
  [libretro-database](https://github.com/libretro/libretro-database) for covers and game info
- [Nunito](https://github.com/googlefonts/nunito) font (SIL Open Font License 1.1)

### 🤖 Made with Claude

ThorBro is built together with [Claude](https://claude.com/claude-code), Anthropic's AI coding assistant.
I bring the ideas, test on real hardware and decide what ships. Claude writes most of the code with me.

## 📄 License

[MIT](LICENSE). Contributions are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).
