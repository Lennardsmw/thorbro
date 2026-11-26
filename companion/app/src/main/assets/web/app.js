'use strict';
const $ = (s) => document.querySelector(s);
const CHUNK = 8 * 1024 * 1024;
const store = {
  get(k, d) { try { return localStorage.getItem('thorbro.' + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem('thorbro.' + k, v); } catch {} },
};
let token = store.get('token', '');
// opened inside the ThorBro app on the device: token comes with the URL
const urlParams = new URLSearchParams(location.search);
const IN_APP = urlParams.get('app') === '1';
if (urlParams.get('token')) { token = urlParams.get('token'); store.set('token', token); }
if (IN_APP) { document.documentElement.classList.add('in-app'); history.replaceState(null, '', '/?app=1'); }
// gamepad B / back: close a dialog, then go to the previous tab
const tabHistory = [];
window.thorbroBack = () => {
  const d = document.querySelector('dialog[open]');
  if (d) { d.close(); return true; }
  if (tabHistory.length > 1) { tabHistory.pop(); showTab(tabHistory.pop()); return true; }
  return false;
};

// ---------- API ----------
class AuthError extends Error {}
async function api(path, opts = {}) {
  opts.headers = { ...(opts.headers || {}), Authorization: 'Bearer ' + token };
  const r = await fetch(path, opts);
  if (r.status === 401) { showLogin(); throw new AuthError('Kopplungscode fehlt'); }
  const body = await r.json().catch(() => ({}));
  if (!r.ok && r.status !== 409) throw new Error(body.error || r.statusText);
  return { status: r.status, body };
}
const q = (o) => new URLSearchParams(o).toString();
const fmt = (n) => {
  if (n < 1024) return n + ' B';
  const u = ['KB', 'MB', 'GB', 'TB']; let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return n.toFixed(n < 10 ? 1 : 0) + ' ' + u[i];
};
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ---------- tabs & login ----------
function showTab(name) {
  document.querySelectorAll('.tab').forEach((t) => (t.hidden = t.id !== name));
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  $('#login').hidden = true;
  store.set('tab', name);
  if (tabHistory[tabHistory.length - 1] !== name) tabHistory.push(name);
  if (name === 'roms' && !romData) loadRoms(false);
  if (name === 'apps') loadApps();
  if (name === 'saves') loadSaves();
  if (name === 'library') loadLibrary();
  if (name === 'screen') screenStatus();
  if (name === 'stream') loadStream();
  if (name === 'netplay') { loadNetplay(); loadDirect(); }
  if (name === 'store') loadStore(false);
  if (name === 'drivers') loadDrivers(false);
  if (name === 'files') loadFiles(currentDir);
  if (name === 'device') loadDevice();
}
document.querySelectorAll('nav button').forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));

function showLogin() {
  document.querySelectorAll('.tab').forEach((t) => (t.hidden = true));
  $('#login').hidden = false;
  $('#codeInput').focus();
}
$('#loginForm').onsubmit = async (e) => {
  e.preventDefault();
  token = $('#codeInput').value.trim().toUpperCase();
  try {
    await api('/api/info');
    store.set('token', token);
    $('#loginErr').textContent = '';
    start();
  } catch (err) { $('#loginErr').textContent = 'Code stimmt nicht.'; }
};
$('#cocoonRestart').onclick = async () => { try { await api('/api/apps/rip.moth.cocoonshell/restart', { method: 'POST' }); toast('Cocoon startet neu …'); } catch (err) { alert(err.message); } };
$('#logout').onclick = () => { token = ''; store.set('token', ''); showLogin(); };

// ---------- device ----------
let info = null;
async function loadDevice() {
  const { body } = await api('/api/info');
  info = body;
  // the build string lives once in Gerät › ThorBro, not in a footer
  updMood();
  $('#conn').textContent = `${body.manufacturer} ${body.model} · ${body.ip}:${body.port}`;
  const p = body.permissions;
  const perm = (ok, t) => `<div class="${ok ? 'ok' : 'err'}">${ok ? '✓' : '✗'} ${t}</div>`;
  $('#deviceInfo').innerHTML = `
    <div class="card"><h3>Gerät</h3>${esc(body.manufacturer)} ${esc(body.model)}<br>Android ${esc(body.android)} · ${body.displays} Bildschirme<br>ThorBro ${esc(body.appVersion)}</div>
    <div class="card"><h3>Akku</h3>${body.battery.percent}%${body.battery.charging ? ' · lädt' : ''}</div>
    ${body.storage.map((v) => `<div class="card"><h3>${esc(v.label)}</h3>${fmt(v.free)} frei von ${fmt(v.total)}<br><span class="muted">${esc(v.path)}</span></div>`).join('')}
    <div class="card"><h3>ThorBro-Update</h3>Installiert: <strong>${esc(body.appVersion)}</strong>
      <div class="row" style="margin:8px 0"><button id="updCheck" class="primary">Nach Updates suchen</button> <button id="devOpen">Dev-Versionen</button></div>
      <div id="selfStatus" class="muted"></div>
      <label style="display:block;margin-top:8px"><input id="updAuto" type="checkbox"> Automatisch aktualisieren (alle 6 h)</label>
      <details style="margin-top:6px"><summary class="muted">Weitere Optionen</summary>
        <label class="muted" style="display:block;margin-top:6px">Update-Quelle (GitHub-Release oder JSON)</label>
        <input id="updFeed" style="width:100%">
        <label class="btn" style="margin-top:6px">APK von diesem Gerät installieren<input id="selfApk" type="file" accept=".apk" hidden></label>
      </details></div>
    ${IN_APP ? '<div class="card"><h3>App</h3><button onclick="ThorBroApp.openSettings()">Einstellungen und Berechtigungen</button></div>' : ''}
    <div class="card"><h3>RetroAchievements</h3>
      <div class="muted" id="raState"></div>
      <input id="raUser" placeholder="Benutzername" style="width:100%;margin-top:6px">
      <input id="raKey" type="password" placeholder="Web-API-Key (retroachievements.org/settings)" style="width:100%;margin-top:6px" autocomplete="off">
      <button id="raSave" style="margin-top:6px">Speichern</button></div>
    <div class="card"><h3>Berechtigungen</h3>
      ${perm(p.allFiles, 'Zugriff auf alle Dateien')}${perm(p.overlay, 'Apps fernstarten')}${perm(p.install, 'APKs installieren')}
      ${p.allFiles && p.overlay && p.install ? '' : '<p class="muted">Fehlende Rechte in der ThorBro-App auf dem Thor erteilen.</p>'}</div>`;
  loadUpdateConfig();
  loadStorage(false);
  api('/api/ra').then(({ body }) => {
    raStatus = body;
    $('#raUser').value = body.user || '';
    $('#raState').textContent = body.configured ? `Verbunden als ${body.user}. Erfolge erscheinen im ROMs-Tab.` : 'Noch nicht eingerichtet.';
  }).catch(() => {});
  fillVolumes();
}

document.addEventListener('click', async (e) => {
  if (e.target.id !== 'raSave') return;
  const body = JSON.stringify({ user: $('#raUser').value, key: $('#raKey').value });
  raStatus = (await api('/api/ra/config', { method: 'POST', body, headers: { 'Content-Type': 'application/json' } })).body;
  $('#raKey').value = '';
  Object.keys(raCache).forEach((k) => delete raCache[k]);
  $('#raState').textContent = raStatus.configured ? `Verbunden als ${raStatus.user}.` : 'Benutzername und Key fehlen.';
  toast('RetroAchievements gespeichert');
});

// ---------- screen mirror ----------
async function screenStatus() {
  const { body } = await api('/api/screen');
  $('#screenInfo').textContent = body.running ? `Live, ${body.width}×${body.height}` : 'Aus. „Bildschirm anzeigen“ und auf dem Thor bestätigen.';
  $('#screenShot').href = `/api/screen/frame.jpg?${authQ()}`;
  if (body.running && !$('#screenImg').src.includes('stream')) $('#screenImg').src = `/api/screen/stream?${authQ()}&t=${Date.now()}`;
  if (!body.running) $('#screenImg').removeAttribute('src');
  // while it streams there is only "Beenden"
  $('#screenStart').hidden = body.running; $('#screenStop').hidden = !body.running;
  if (body.running) $('#screenStart').disabled = false;
  return body.running;
}
$('#screenFull').onclick = () => {
  const el = $('.screenwrap');
  (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
};
$('#screenImg').ondblclick = () => $('#screenFull').click();
$('#screenStart').onclick = async () => {
  if ($('#screenStart').disabled) return;
  $('#screenStart').disabled = true;
  setTimeout(() => ($('#screenStart').disabled = false), 30000);
  await api('/api/screen/start', { method: 'POST' });
  $('#screenInfo').textContent = 'Auf dem Thor „Jetzt starten“ tippen …';
  for (let i = 0; i < 60; i++) { await sleep(1000); if (await screenStatus()) return; }
};
$('#screenStop').onclick = async () => { await api('/api/screen/stop', { method: 'POST' }); screenStatus(); };

// ---------- storage overview ----------
async function loadStorage(refresh) {
  const el = $('#storageView'); if (!el) return;
  el.innerHTML = '<p class="muted">Berechne Speicherbelegung …</p>';
  const { body } = await api('/api/storage' + (refresh ? '?refresh=1' : ''));
  const bar = (part, total) => `<div class="bar"><div style="width:${Math.max(1, Math.round(part / total * 100))}%"></div></div>`;
  el.innerHTML = body.volumes.map((v) => {
    const used = v.total - v.free;
    return `<div class="panel"><div class="row" style="margin:0"><h3 class="grow">${esc(v.label)}</h3><span class="muted">${fmt(v.free)} frei von ${fmt(v.total)}</span></div>
      ${bar(used, v.total)}
      ${v.systems.length ? `<table class="meta" style="margin-top:8px">${v.systems.map((s) => `<tr><td>${esc(s.name)}</td><td style="width:50%">${bar(s.size, used)}</td><td class="muted" style="text-align:right">${fmt(s.size)}</td></tr>`).join('')}</table>` : ''}
      ${v.folders.length ? `<details style="margin-top:6px"><summary class="muted">Größte Ordner</summary><table class="meta">${v.folders.map((f) => `<tr><td>${esc(f.name)}</td><td class="muted" style="text-align:right">${fmt(f.size)}</td></tr>`).join('')}</table></details>` : ''}
    </div>`;
  }).join('') + `<button id="storageRefresh">Neu berechnen</button>`;
}
document.addEventListener('click', (e) => { if (e.target.id === 'storageRefresh') loadStorage(true); });

// ---------- self-update ----------
async function loadUpdateConfig() {
  try {
    const r = await fetch('/api/update', { headers: { Authorization: 'Bearer ' + token } });
    const c = await r.json();
    if ($('#updAuto')) $('#updAuto').checked = !!c.auto;
    if ($('#updFeed')) $('#updFeed').value = c.feed || '';
    if ($('#selfStatus')) $('#selfStatus').textContent = c.error ? c.error : c.available ? `Neue Version ${c.latest} verfügbar.` : `Aktuell (neueste: ${c.latest}).`;
    if (c.available && $('#updCheck')) $('#updCheck').textContent = `Auf ${c.latest} aktualisieren`;
  } catch {}
}
document.addEventListener('click', async (e) => {
  if (e.target.id !== 'updCheck') return;
  const say = (t) => { $('#selfStatus').textContent = t; };
  e.target.disabled = true; say('Suche und lade Update …');
  try {
    const { body } = await api('/api/update', { method: 'POST' });
    if (body.error) say(body.error);
    else if (!body.session) say(`ThorBro ist aktuell (${body.current}).`);
    else await waitForRestart(body.session, body.current, say);
  } catch (err) { say('Fehler: ' + err.message); }
  e.target.disabled = false;
});
document.addEventListener('change', async (e) => {
  if (e.target.id === 'updAuto') await api('/api/update/config?' + q({ auto: e.target.checked ? 1 : 0 }), { method: 'POST' });
  if (e.target.id === 'updFeed') { await api('/api/update/config?' + q({ feed: e.target.value }), { method: 'POST' }); loadUpdateConfig(); }
});
// ---------- Dev-Versionen: test builds from GitHub / home network / own URL ----------
const DEV_SOURCES = [['github', 'GitHub'], ['home', 'Heimnetz'], ['custom', 'Eigene URL']];
const devAgo = (iso) => {
  const t = Date.parse(iso); if (!t) return '';
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 60) return `vor ${Math.max(m, 1)} Min.`;
  if (m < 1440) return `vor ${Math.round(m / 60)} Std.`;
  const d = Math.round(m / 1440); return d === 1 ? 'gestern' : `vor ${d} Tagen`;
};
document.addEventListener('click', (e) => { if (e.target.id === 'devOpen' || e.target.id === 'verTag') openDev(); });
function openDev() {
  const d = $('#devDlg');
  d.innerHTML = `<h3>Dev-Versionen</h3>
    <div class="row devsrc">${DEV_SOURCES.map(([id, label]) => `<button data-devsrc="${id}">${label}</button>`).join('')}</div>
    <div class="row" id="devCustomRow" style="display:none"><input id="devCustom" class="grow" placeholder="https://…/index.json"><button id="devCustomGo">Laden</button></div>
    <div id="devStatus" class="muted">Lade …</div>
    <div id="devList"></div>
    <div class="row" style="margin-top:14px"><button id="devClose">Schließen</button></div>`;
  d.showModal();
  $('#devClose').onclick = () => d.close();
  d.querySelectorAll('[data-devsrc]').forEach((b) => { b.onclick = () => devLoad({ source: b.dataset.devsrc }); });
  $('#devCustomGo').onclick = () => devLoad({ source: 'custom', custom: $('#devCustom').value.trim() });
  devLoad({});
}
async function devLoad(params) {
  const d = $('#devDlg');
  const say = (t) => { $('#devStatus').textContent = t; };
  const mark = (src) => d.querySelectorAll('[data-devsrc]').forEach((b) => b.classList.toggle('primary', b.dataset.devsrc === src));
  say('Lade …'); $('#devList').innerHTML = '';
  if (params.source) mark(params.source);
  let c;
  try { c = (await api('/api/update/dev?' + q(params))).body; } catch (err) { return say('Fehler: ' + err.message); }
  mark(c.source);
  $('#devCustomRow').style.display = c.source === 'custom' ? '' : 'none';
  if (c.source === 'custom' && !$('#devCustom').value) $('#devCustom').value = c.custom || '';
  const vs = c.versions || [];
  say(c.error ? c.error : vs.length ? `Installiert: ${c.current}` : 'Keine Test-Builds in dieser Quelle.');
  $('#devList').innerHTML = vs.map((v, i) => `<div class="devitem${v.current ? ' cur' : ''}">
      <div class="devhead"><div><strong>${esc(v.versionName)}</strong>${v.current ? ' <span class="devbadge">installiert</span>' : ''}
        <div class="muted">${esc(v.branch || '')}${v.published ? ' · ' + devAgo(v.published) : ''}${v.size ? ' · ' + fmt(v.size) : ''}</div></div>
        ${v.current ? '' : v.older ? '<span class="muted devold">älter – nur nach Deinstallation</span>' : `<button class="primary" data-devinst="${i}">Installieren</button>`}</div>
      ${(v.features || []).length ? `<details><summary class="muted">${v.features.length} Features</summary><ul>${v.features.map((f) => `<li>${esc(f)}</li>`).join('')}</ul></details>` : ''}
    </div>`).join('');
  const btns = () => d.querySelectorAll('[data-devinst]');
  btns().forEach((b) => { b.onclick = async () => {
    const v = vs[+b.dataset.devinst];
    btns().forEach((x) => { x.disabled = true; });
    say(`Lade ${v.versionName} …`);
    try {
      const { body } = await api('/api/update/dev/install?' + q({ url: v.url }), { method: 'POST' });
      if (body.error) { say(body.error); btns().forEach((x) => { x.disabled = false; }); }
      else await waitForRestart(body.session, body.current, say);
    } catch (err) { say('Fehler: ' + err.message); }
  }; });
}
async function waitForRestart(session, before, say) {
  say('Installiere … falls der Thor fragt: „Aktualisieren“ tippen (oder die Benachrichtigung).');
  for (let i = 0; i < 300; i++) {
    await sleep(1000);
    try {
      const r = await fetch('/api/info', { headers: { Authorization: 'Bearer ' + token } });
      if (!r.ok) continue;
      const now = await r.json();
      if (now.appVersion !== before) { say('Aktualisiert auf ' + now.appVersion + '. Seite lädt neu …'); await sleep(1200); return location.reload(); }
      const st = (await fetch('/api/install/' + session, { headers: { Authorization: 'Bearer ' + token } }).then((x) => x.json())).status;
      if (st.startsWith('failed')) return say('Installation fehlgeschlagen: ' + st);
    } catch { say('ThorBro startet neu …'); }
  }
  say('Keine Antwort. Ist ThorBro auf dem Thor wieder gestartet?');
}
document.addEventListener('change', (e) => { if (e.target.id === 'selfApk' && e.target.files[0]) selfUpdate(e.target.files[0]); });
async function selfUpdate(file) {
  const say = (t) => { const el = $('#selfStatus'); if (el) el.textContent = t; };
  const before = info?.appVersion;
  say('Lade hoch …');
  let res;
  try {
    const r = await fetch('/api/self-update', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/vnd.android.package-archive' }, body: file });
    res = await r.json();
    if (!r.ok) return say('Fehler: ' + (res.error || r.status));
  } catch (err) { return say('Fehler: ' + err.message); }
  return waitForRestart(res.session, before, say);
  for (let i = 0; i < 180; i++) {
    await sleep(1000);
    try {
      const r = await fetch('/api/info', { headers: { Authorization: 'Bearer ' + token } });
      if (!r.ok) continue;
      const now = await r.json();
      if (now.appVersion !== before || i > 5 && (await fetch('/api/install/' + res.session, { headers: { Authorization: 'Bearer ' + token } }).then((x) => x.json())).status === 'success') {
        say('Aktualisiert auf ' + now.appVersion + '. Seite lädt neu …');
        await sleep(1200);
        return location.reload();
      }
      const st = (await fetch('/api/install/' + res.session, { headers: { Authorization: 'Bearer ' + token } }).then((x) => x.json())).status;
      if (st.startsWith('failed')) return say('Installation fehlgeschlagen: ' + st);
    } catch { say('ThorBro startet neu …'); }
  }
  say('Keine Antwort. Ist ThorBro auf dem Thor wieder gestartet?');
}

// ---------- upload queue ----------
// Folder names follow the ES-DE layout, which most Android frontends understand.
const SYSTEMS = {
  nds: ['nds'], n3ds: ['3ds', 'cia', 'cci', '3dsx'], gba: ['gba'], gb: ['gb'], gbc: ['gbc'],
  nes: ['nes'], snes: ['sfc', 'smc'], n64: ['n64', 'z64', 'v64'], gc: ['rvz', 'gcz'], wii: ['wbfs'],
  switch: ['nsp', 'xci', 'nsz', 'xcz'], psx: ['pbp'], ps2: [], psp: ['cso'], dreamcast: ['gdi', 'cdi'],
  megadrive: ['md', 'gen'], mastersystem: ['sms'], gamegear: ['gg'], saturn: [], pcengine: ['pce'],
};
const EXT_TO_SYS = {};
Object.entries(SYSTEMS).forEach(([s, exts]) => exts.forEach((e) => (EXT_TO_SYS[e] = s)));
const ext = (n) => (n.includes('.') ? n.split('.').pop().toLowerCase() : '');
const isArchiveName = (n) => ['zip', '7z'].includes(ext(n));
// RetroArch cores read these zipped; everything else is unpacked by default
const KEEP_ZIPPED = new Set(['nes', 'snes', 'gb', 'gbc', 'gba', 'megadrive', 'mastersystem', 'gamegear', 'pcengine', 'atari2600', 'lynx', 'ngp', 'wonderswan', 'arcade', 'n64']);
const APK_DIR = '/storage/emulated/0/Download/ThorBro';

// the Thor's ROM folder (Settings → ROM-Ordner); changing it here changes it on the Thor
$('#romRoot').value = '/storage/emulated/0/ROMs';
$('#romRoot').onchange = async () => {
  const path = $('#romRoot').value.replace(/\/+$/, '');
  try { const r = await api('/api/roms/root?path=' + encodeURIComponent(path), { method: 'POST' }); $('#romRoot').value = r.body.root || path; }
  catch (err) { alert('ROM-Ordner: ' + err.message); }
  renderQueue();
};

const queue = [];
let running = false;

function guessSystem(files) {
  const count = {};
  for (const f of files) { const s = EXT_TO_SYS[ext(f.file.name)]; if (s) count[s] = (count[s] || 0) + 1; }
  const best = Object.entries(count).sort((a, b) => b[1] - a[1])[0];
  return best ? best[0] : '';
}

// files: [{file, rel}] where rel is the path inside the dropped folder ('' for a single file)
function enqueue(name, files, isFolder) {
  const isApk = !isFolder && ext(name) === 'apk';
  queue.push({
    id: Math.random().toString(36).slice(2), name, files, isFolder, isApk,
    system: isApk ? 'apk' : guessSystem(files),
    total: files.reduce((a, f) => a + f.file.size, 0),
    extract: !isFolder && isArchiveName(name) && !KEEP_ZIPPED.has(guessSystem(files)),
    sent: 0, state: 'wartet', error: '', paused: false,
  });
  renderQueue();
}

function targetDir(item) {
  if (item.isApk) return APK_DIR;
  const root = $('#romRoot').value.replace(/\/+$/, '');
  return item.system ? `${root}/${item.system}` : root;
}

function renderQueue() {
  const opts = (sel) => ['', ...Object.keys(SYSTEMS)].map((s) =>
    `<option value="${s}" ${s === sel ? 'selected' : ''}>${s || '(ROM-Ordner selbst)'}</option>`).join('');
  $('#queue').innerHTML = queue.map((it) => {
    const pct = it.total ? Math.floor((it.sent / it.total) * 100) : (it.state === 'fertig' ? 100 : 0);
    const cls = it.state === 'fertig' ? 'done' : it.state === 'fehler' ? 'failed' : '';
    const sys = it.isApk ? '<span class="muted">APK → installieren</span>'
      : `<select data-sys="${it.id}" ${it.state !== 'wartet' ? 'disabled' : ''}>${opts(it.system)}</select>`;
    const btn = it.state === 'läuft' ? `<button data-pause="${it.id}">Pause</button>`
      : it.state === 'pausiert' || it.state === 'fehler' ? `<button data-resume="${it.id}">Weiter</button>` : '';
    const rm = it.state !== 'läuft' ? `<button data-rm="${it.id}">✕</button>` : '';
    const ex = isArchiveName(it.name) && !it.isFolder ? `<label class="muted"><input type="checkbox" data-extract="${it.id}" ${it.extract ? 'checked' : ''} ${it.state !== 'wartet' ? 'disabled' : ''}> entpacken</label>` : '';
    return `<li class="${cls}"><div class="qhead"><span class="qname">${it.isFolder ? '📁 ' : ''}${esc(it.name)}</span>${sys}${ex}${btn}${rm}</div>
      <div class="qmeta">${it.files.length > 1 ? it.files.length + ' Dateien · ' : ''}${fmt(it.sent)} / ${fmt(it.total)} · ${esc(it.state)}${it.speed ? ' · ' + fmt(it.speed) + '/s' : ''}${it.eta ? ' · noch ' + it.eta : ''}
      → ${esc(targetDir(it))}${it.error ? '<br>' + esc(it.error) : ''}</div>
      <div class="bar"><div style="width:${pct}%"></div></div></li>`;
  }).join('');
}

$('#queue').onclick = (e) => {
  const t = e.target, find = (id) => queue.find((x) => x.id === id);
  if (t.dataset.pause) { find(t.dataset.pause).paused = true; }
  if (t.dataset.resume) { const it = find(t.dataset.resume); it.paused = false; it.state = 'wartet'; it.error = ''; renderQueue(); runQueue(); }
  if (t.dataset.rm) { queue.splice(queue.findIndex((x) => x.id === t.dataset.rm), 1); renderQueue(); }
};
$('#queue').onchange = (e) => {
  if (e.target.dataset.extract) { queue.find((x) => x.id === e.target.dataset.extract).extract = e.target.checked; return; }
  if (e.target.dataset.sys) { queue.find((x) => x.id === e.target.dataset.sys).system = e.target.value; renderQueue(); }
};
$('#startAll').onclick = () => runQueue();
$('#clearDone').onclick = () => { for (let i = queue.length - 1; i >= 0; i--) if (queue[i].state === 'fertig') queue.splice(i, 1); renderQueue(); };

class Paused extends Error {}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PARALLEL = 3;
async function uploadOne(item, file, dest, before) {
  const probe = (await api('/api/upload?' + q({ path: dest, size: file.size }))).body;
  // old servers or a sequential upload already in progress: keep the sequential path
  if (!probe.parallel || (probe.offset > 0 && !probe.chunks.length) || file.size <= CHUNK) return uploadSeq(item, file, dest, before);
  if (probe.exists && !$('#overwrite').checked) {
    if (probe.existingSize === file.size) return 'skipped';
    throw new Error(`${dest} gibt es schon (andere Größe). "Überschreiben" aktivieren.`);
  }
  const have = new Set(probe.chunks);
  const todo = [];
  for (let off = 0; off < file.size; off += CHUNK) if (!have.has(off)) todo.push(off);
  const doneBytes = () => [...have].reduce((a, o) => a + Math.min(CHUNK, file.size - o), 0);
  if (probe.free >= 0 && probe.free < file.size - doneBytes()) throw new Error(`Nicht genug Speicher (${fmt(probe.free)} frei)`);
  const t0 = performance.now(), start = doneBytes();
  const worker = async () => {
    while (todo.length) {
      if (item.paused) throw new Paused();
      const off = todo.shift();
      for (let tries = 0; ; tries++) {
        try {
          await api('/api/upload?' + q({ path: dest, offset: off, pos: 1 }), {
            method: 'PUT', body: file.slice(off, off + CHUNK), headers: { 'Content-Type': 'application/octet-stream' },
          });
          break;
        } catch (err) {
          if (err instanceof AuthError || tries >= 5) throw err;
          item.state = `Verbindung weg, neuer Versuch ${tries + 1}/6`; renderQueue();
          await sleep(1000 * (tries + 1));
        }
      }
      have.add(off);
      item.state = 'läuft';
      item.sent = before + doneBytes();
      const secs = (performance.now() - t0) / 1000;
      if (secs > 1) {
        item.speed = (doneBytes() - start) / secs;
        const left = (item.total - item.sent) / item.speed;
        item.eta = left > 90 ? Math.round(left / 60) + ' min' : Math.round(left) + ' s';
      }
      renderQueue();
    }
  };
  await Promise.all(Array.from({ length: PARALLEL }, worker));
  await api('/api/upload/finish?' + q({ path: dest, size: file.size, overwrite: $('#overwrite').checked ? 1 : 0 }), { method: 'POST' });
  return 'ok';
}

async function uploadSeq(item, file, dest, before) {
  let { body: st } = await api('/api/upload?' + q({ path: dest, size: file.size }));
  if (st.exists && !$('#overwrite').checked) {
    if (st.existingSize === file.size) return 'skipped';
    throw new Error(`${dest} gibt es schon (andere Größe). "Überschreiben" aktivieren.`);
  }
  if (st.free >= 0 && st.free < file.size - st.offset) throw new Error(`Nicht genug Speicher (${fmt(st.free)} frei)`);
  let off = st.offset, tries = 0, t0 = performance.now(), off0 = off;
  while (off < file.size) {
    if (item.paused) throw new Paused();
    try {
      const r = await api('/api/upload?' + q({ path: dest, offset: off }), {
        method: 'PUT', body: file.slice(off, off + CHUNK), headers: { 'Content-Type': 'application/octet-stream' },
      });
      off = r.body.offset; tries = 0;
    } catch (err) {
      if (err instanceof AuthError || ++tries > 6) throw err;
      item.state = `Verbindung weg, neuer Versuch ${tries}/6`; renderQueue();
      await sleep(1000 * tries);
      ({ body: st } = await api('/api/upload?' + q({ path: dest, size: file.size })));
      off = st.offset; item.state = 'läuft';
    }
    item.sent = before + off;
    const secs = (performance.now() - t0) / 1000;
    if (secs > 1) {
      item.speed = (off - off0) / secs;
      const left = (item.total - item.sent) / item.speed;
      item.eta = left > 90 ? Math.round(left / 60) + ' min' : Math.round(left) + ' s';
    }
    renderQueue();
  }
  await api('/api/upload/finish?' + q({ path: dest, size: file.size, overwrite: $('#overwrite').checked ? 1 : 0 }), { method: 'POST' });
  return 'ok';
}

async function runQueue() {
  if (running) return;
  running = true;
  try {
    let item;
    while ((item = queue.find((x) => x.state === 'wartet'))) {
      item.state = 'läuft'; item.error = ''; renderQueue();
      const dir = targetDir(item);
      let before = 0;
      try {
        for (const f of item.files) {
          const dest = `${dir}/${item.isFolder ? item.name + '/' : ''}${f.rel || f.file.name}`;
          await uploadOne(item, f.file, dest, before);
          before += f.file.size;
          item.sent = before;
        }
        if (item.extract) {
          item.state = 'entpacke auf dem Thor …'; renderQueue();
          const { body: job } = await api('/api/extract?' + q({ path: `${dir}/${item.name}`, dest: dir, delete: 1 }), { method: 'POST' });
          const res = await waitJob(job.id, (j) => { item.state = `entpacke ${Math.round(j.progress * 100)} %`; renderQueue(); });
          if (res.error) throw new Error('Entpacken: ' + res.error);
          romData = null;
        }
        if (item.isApk) {
          item.state = 'installiere (auf dem Thor bestätigen)'; renderQueue();
          const { body } = await api('/api/install?' + q({ path: `${dir}/${item.name}` }), { method: 'POST' });
          item.state = await waitInstall(body.session);
        } else {
          item.state = 'fertig';
        }
        if (item.state.startsWith('failed')) { item.error = item.state; item.state = 'fehler'; }
        else if (item.isApk) item.state = 'fertig';
      } catch (err) {
        if (err instanceof Paused) item.state = 'pausiert';
        else { item.state = 'fehler'; item.error = err.message; }
        if (err instanceof AuthError) break;
      }
      item.speed = 0; item.eta = '';
      renderQueue();
    }
  } finally { running = false; }
}

async function waitJob(id, onTick) {
  for (;;) {
    await sleep(1000);
    const { body } = await api('/api/jobs/' + id);
    if (onTick) onTick(body);
    if (body.done) return body;
  }
}

async function waitInstall(session) {
  for (let i = 0; i < 300; i++) {
    const { body } = await api('/api/install/' + session);
    if (body.status === 'success' || body.status.startsWith('failed')) return body.status;
    await sleep(1000);
  }
  return 'failed: keine Antwort vom Installer';
}

// drag & drop, including whole folders
async function readEntry(entry, prefix, out) {
  if (entry.isFile) {
    out.push({ file: await new Promise((res, rej) => entry.file(res, rej)), rel: prefix + entry.name });
  } else if (entry.isDirectory) {
    const reader = entry.createReader();
    let batch;
    do {
      batch = await new Promise((res, rej) => reader.readEntries(res, rej));
      for (const e of batch) await readEntry(e, prefix + entry.name + '/', out);
    } while (batch.length);
  }
}
const drop = $('#drop');
drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
drop.ondragleave = () => drop.classList.remove('over');
drop.ondrop = async (e) => {
  e.preventDefault(); drop.classList.remove('over');
  const entries = [...e.dataTransfer.items].map((i) => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
  for (const entry of entries) {
    if (entry.isDirectory) {
      const files = [];
      const reader = entry.createReader();
      let batch;
      do {
        batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        for (const c of batch) await readEntry(c, '', files);
      } while (batch.length);
      enqueue(entry.name, files, true);
    } else {
      const file = await new Promise((res, rej) => entry.file(res, rej));
      enqueue(file.name, [{ file, rel: '' }], false);
    }
  }
};
$('#pick').onchange = (e) => { for (const f of e.target.files) enqueue(f.name, [{ file: f, rel: '' }], false); e.target.value = ''; };
$('#pickDir').onchange = (e) => {
  const files = [...e.target.files];
  if (!files.length) return;
  const top = files[0].webkitRelativePath.split('/')[0];
  enqueue(top, files.map((f) => ({ file: f, rel: f.webkitRelativePath.split('/').slice(1).join('/') })), true);
  e.target.value = '';
};

// ---------- ROM library ----------
let romData = null;
let romSys = store.get('romSys', '');
const metaCache = {};          // system id -> {path: meta}
const metaLoading = {};
const volLabel = (p) => {
  const v = (info?.storage || []).find((s) => p.startsWith(s.path));
  return v ? v.label : '';
};
const authQ = () => 'token=' + encodeURIComponent(token);
const iconUrl = (id) => `/api/consoles/${encodeURIComponent(id)}/icon?v=bro1&${authQ()}`; // v: bust the 30-day cache when the icon set changes
const coverUrl = (sys, name) => `/api/roms/cover?${q({ system: sys, name })}&${authQ()}`;
const mediaUrl = (sys, path, kind) => `/api/roms/media?${q({ system: sys, path, kind })}&${authQ()}`;
// Cocoon's box art first, libretro cover second
const coverOf = (sysId, r, m) => (m.media || []).includes('icon') ? mediaUrl(sysId, r.path, 'icon') : m.cover ? coverUrl(sysId, m.cover) : '';
const fmtTime = (s) => (s >= 3600 ? Math.floor(s / 3600) + ' h ' : '') + Math.round((s % 3600) / 60) + ' min';

let emus = null;
let lib = null;
async function loadLib(refresh) {
  try { lib = (await api('/api/library/status' + (refresh ? '?refresh=1' : ''))).body; } catch { lib = null; }
  renderRoms();
}
async function loadEmus() {
  try { emus = (await api('/api/emulators')).body; } catch { emus = null; }
  try { raStatus = (await api('/api/ra')).body; } catch { raStatus = null; }
}
async function loadRoms(refresh) {
  $('#romStats').textContent = 'Suche ROMs …';
  loadEmus().then(renderRoms);
  loadLib(refresh);
  try { romData = (await api('/api/roms' + (refresh ? '?refresh=1' : ''))).body; }
  catch (err) { $('#romStats').textContent = 'Fehler: ' + err.message; return; }
  if (refresh) Object.keys(metaCache).forEach((k) => delete metaCache[k]);
  // Upload target: the Thor's ROM folder (Settings)
  if (romData.romRoot && document.activeElement !== $('#romRoot')) { $('#romRoot').value = romData.romRoot; renderQueue(); }
  // '' = all consoles as folders (the start of the Spiele page)
  if (romSys && !romData.systems.some((s) => s.id === romSys)) romSys = '';
  renderRoms();
}

const raCache = {};
async function loadRA(id) {
  if (raCache[id] || !raStatus?.configured || !raStatus.systems.includes(id)) return;
  raCache[id] = {};
  try { raCache[id] = (await api('/api/ra/system?' + q({ system: id }))).body; } catch {}
  renderRoms();
}
let raStatus = null;
async function loadMeta(id) {
  loadRA(id);
  if (metaCache[id] || metaLoading[id] || id === '_unassigned') return;
  metaLoading[id] = true;
  $('#romStats').dataset.extra = ' · lade Cover & Infos …'; renderStats();
  try { metaCache[id] = (await api('/api/roms/meta?' + q({ system: id }))).body; }
  catch { metaCache[id] = {}; }
  delete metaLoading[id];
  $('#romStats').dataset.extra = '';
  renderRoms();
}

function renderStats() {
  if (!romData) return;
  const sys = romData.systems.filter((s) => s.id !== '_unassigned');
  $('#romStats').textContent = `${sys.reduce((a, s) => a + s.count, 0)} ROMs auf ${sys.length} Konsolen · ` +
    `gescannt ${new Date(romData.scannedAt).toLocaleTimeString()}` + ($('#romStats').dataset.extra || '');
}

/** sort names as they are said: "Legend of Zelda, The - Breath of the Wild" → "The Legend of Zelda: Breath of the Wild" */
const displayTitle = (t) => {
  const m = /^(.+?), (The|A|An|Der|Die|Das|Le|La|Les|El|Il)((?:\s*[-:].*)?)$/.exec(String(t || '').trim());
  return m ? `${m[2]} ${m[1]}${m[3].replace(/^\s*-\s*/, ': ')}` : t;
};
const romTitle = (r, m) => displayTitle(m.title || r.name.replace(/\s*[([].*$/, '') || r.name);

/** a console as a folder: a 2×2 collage of its covers (Cocoon art), the console as a small badge; no covers → the console big */
function folderTile(s) {
  const games = s.id === 'switch' ? groupSwitch(s.roms) : s.roms;
  const four = games.slice(0, 4);
  const size = games.reduce((a, r) => a + (r.group ? r.group.size : r.size), 0);
  const imgs = s.id === 'ports' ? '' : four.map((r) => `<img alt="" loading="lazy" src="${mediaUrl(s.id, r.path, 'icon')}" onerror="this.remove()">`).join('');
  return `<button class="folder" data-sys-pick="${esc(s.id)}" title="${esc(s.name)}">
    <div class="fart">${sysIcon(s.id, 'fbig')}<div class="collage">${imgs}</div>${sysIcon(s.id, 'cbadge')}</div>
    <div class="rtitle">${esc(s.name)}</div><div class="rmeta">${games.length} · ${fmt(size)}</div></button>`;
}

const sysIcon = (id, cls = 'sysicon') => id === '_unassigned'
  ? `<span class="${cls} noicon">?</span>`
  : `<img class="${cls}" alt="" loading="lazy" src="${iconUrl(id)}" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'${cls} noicon',textContent:'${id.slice(0, 2).toUpperCase()}'}))">`;

function romCard(r, sysId) {
  const m = (metaCache[sysId] || {})[r.path] || {};
  const title = romTitle(r, m);
  const line = [m.year, m.developer || m.publisher].filter(Boolean).join(' · ');
  const src = coverOf(sysId, r, m);
  const cover = src
    ? `<img class="cover" loading="lazy" alt="${esc(title)}" src="${src}" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'cover nocover',textContent:this.alt}))">`
    : `<div class="cover nocover">${sysIcon(sysId, 'bigicon')}<span>${esc(title)}</span></div>`;
  const canPlay = emus && emus.systems[sysId];
  const sw = r.group ? r.group : null;
  const swBadges = sw ? [
    sw.update ? `<span class="badge">Update ${esc(sw.update.versionLabel)}</span>` : '',
    sw.dlc.length ? `<span class="badge">+${sw.dlc.length} DLC</span>` : '',
    !sw.base ? '<span class="badge warnbadge">Basisspiel fehlt</span>' : '',
    sw.oldUpdates.length ? `<span class="badge warnbadge">${sw.oldUpdates.length} alte Updates</span>` : '',
  ].join('') : '';
  return `<div class="romcard" tabindex="0" data-rom="${esc(r.path)}" data-sys="${esc(sysId)}" title="${esc(r.file)}">
    ${canPlay && (!sw || sw.base) ? `<button class="playbtn primary" data-play="${esc(r.path)}" data-play-sys="${esc(sysId)}">▶ Spielen</button>` : ''}
    ${cover}
    <div class="rtitle">${m.favorite ? '<span class="fav">★</span> ' : ''}${esc(title)}</div>
    ${(() => { const ra = (raCache[sysId] || {})[r.path]; return ra && ra.gameId ? `<div class="rmeta ra">🏆 ${ra.awarded ?? 0}/${ra.achievements} Erfolge</div>` : ''; })()}
    <div class="rmeta">${esc(line) || '&nbsp;'}</div>
    <div class="rbadges">${swBadges}<span class="muted">${[m.region, fmt(sw ? sw.size : r.size)].filter(Boolean).join(' · ')}</span></div>
  </div>`;
}

function renderRoms() {
  if (!romData) return;
  renderStats();
  $('#sysList').innerHTML = `<button data-sys-pick="" class="${romSys ? '' : 'active'}"><span class="grow">▦ Alle Konsolen</span></button>` + romData.systems.map((s) =>
    `<button data-sys-pick="${s.id}" class="${s.id === romSys ? 'active' : ''}">${sysIcon(s.id)}<span class="grow ${s.id === '_unassigned' ? 'warn' : ''}">${esc(s.name)}</span><span class="n">${s.count}</span></button>`).join('');
  const f = $('#romFilter').value.trim().toLowerCase();
  const region = $('#romRegion').value;
  const match = (r, sysId) => {
    const m = (metaCache[sysId] || {})[r.path] || {};
    if (region && m.region !== region) return false;
    return !f || (r.name + ' ' + (m.title || '') + ' ' + (m.developer || '')).toLowerCase().includes(f);
  };
  // region filter options from what is loaded
  const regions = new Set(Object.values(metaCache).flatMap((mm) => Object.values(mm).map((m) => m.region).filter(Boolean)));
  $('#romRegion').innerHTML = '<option value="">Alle Regionen</option>' +
    [...regions].sort().map((r) => `<option ${r === region ? 'selected' : ''}>${esc(r)}</option>`).join('');

  let html;
  if (f) {
    const hits = romData.systems.flatMap((s) => s.roms.filter((r) => match(r, s.id)).map((r) => romCard(r, s.id)));
    html = `<div class="romhead"><h2>Suche</h2><span class="muted">${hits.length} Treffer</span></div>` +
      (hits.length ? `<div class="covergrid">${hits.join('')}</div>` : '<p class="muted">Nichts gefunden.</p>');
  } else {
    const s = romData.systems.find((x) => x.id === romSys);
    const consoles = romData.systems.filter((x) => x.id !== '_unassigned' && x.count > 0);
    if (!romSys && consoles.length) html = `<div class="covergrid folders">${consoles.map(folderTile).join('')}</div>`;
    else if (!s) html = '<p class="muted">Keine ROMs gefunden. Lege sie z. B. unter ROMs/&lt;konsole&gt; ab.</p>';
    else {
      loadMeta(s.id);
      let list = s.roms.filter((r) => match(r, s.id));
      if (s.id === 'switch') list = groupSwitch(list);
      const ev = emus && emus.systems[s.id];
      const emuSel = ev ? `<label class="muted">Emulator <select data-emu-sys="${s.id}">${ev.options.map((o) => `<option value="${o.id}" ${o.id === ev.current ? 'selected' : ''}>${esc(o.name)}</option>`).join('')}</select></label>` : '';
      const grantHint = ev && emus.folderGrants.length === 0 && !(ev.current === 'retroarch')
        ? `<p class="muted">Zum Starten braucht ThorBro einmal die Freigabe deines ROM-Ordners. <button data-grant>Freigabe auf dem Thor öffnen</button></p>` : '';
      html = `<div class="romhead"><button data-sys-pick="" class="back">← Alle Konsolen</button>${sysIcon(s.id, 'headicon')}<h2>${esc(s.name)}</h2><span class="muted">${s.id === 'switch' ? list.length + ' Spiele · ' + s.count + ' Dateien' : s.count + ' Spiele'} · ${fmt(s.size)}</span><span class="grow"></span>${emuSel}</div>` + grantHint +
        (s.id === '_unassigned' ? '<p class="muted">Diese Dateien liegen nicht in einem Konsolen-Ordner und die Endung passt zu mehreren Konsolen. Öffne eine, um sie einer Konsole zuzuordnen.</p>' : '') +
        `<div class="covergrid">${list.map((r) => romCard(r, s.id)).join('')}</div>`;
    }
  }
  $('#romList').innerHTML = html;
}

function openRom(path, sysId) {
  const sys = romData.systems.find((s) => s.id === sysId);
  let r = sys.roms.find((x) => x.path === path);
  if (sysId === 'switch') r = groupSwitch(sys.roms).find((x) => x.path === path) || r;
  const m = (metaCache[sysId] || {})[path] || {};
  const folder = r.dir ? r.path : r.path.slice(0, r.path.lastIndexOf('/'));
  const row = (k, v) => (v ? `<tr><td class="muted">${k}</td><td>${esc(v)}</td></tr>` : '');
  const tags = (r.name.match(/[([][^)\]]*[)\]]/g) || []).join(' ');
  const src = coverOf(sysId, r, m);
  const media = m.media || [];
  const shots = media.filter((k) => k.startsWith('screenshot'));
  $('#romDetail').innerHTML = `
    ${media.includes('hero') ? `<div class="hero" style="background-image:url('${mediaUrl(sysId, r.path, 'hero')}')">${media.includes('logo') ? `<img class="herologo" alt="" src="${mediaUrl(sysId, r.path, 'logo')}">` : ''}</div>` : ''}
    <div class="detail">
      ${src ? `<img class="dcover" alt="" src="${src}">` : `<div class="dcover nocover">${sysIcon(sysId, 'bigicon')}</div>`}
      <div class="dinfo">
        <div class="romhead">${sysIcon(sysId, 'headicon')}<span class="muted">${esc(sys.name)}</span></div>
        <h2>${esc(romTitle(r, m))}</h2>
        <p class="quiet">${[fmt(r.group ? r.group.size : r.size), lib?.games?.[r.path] ? (lib.games[r.path].inRomm ? 'in RomM' : 'nicht in RomM') : '',
          ({ synced: 'Spielstand in RomM', pending: 'Spielstand geändert', local: 'Spielstand nur auf dem Thor' })[lib?.games?.[r.path]?.save] || ''].filter(Boolean).join(' · ')}</p>
        <table class="meta">
          ${row('Jahr', m.year)}${row('Entwickler', m.developer)}${row('Publisher', m.publisher)}
          ${row('Genre', m.genre)}${row('Spieler', m.players)}${row('Bewertung', m.rating ? m.rating + ' / 10' : '')}
          ${row('Gespielt', m.playtime ? fmtTime(m.playtime) + (m.playcount ? ` · ${m.playcount}×` : '') : '')}
          ${row('Region', m.region)}${row('Tags', tags)}
          ${(() => { const ra = (raCache[sysId] || {})[r.path]; if (!ra) return ''; return ra.gameId
            ? `<tr><td class="muted">RetroAchievements</td><td><a href="https://retroachievements.org/game/${ra.gameId}" target="_blank" rel="noopener">${ra.achievements} Erfolge, ${ra.points} Punkte</a> · freigeschaltet ${ra.awarded ?? 0}${ra.awardedHardcore ? ` (Hardcore ${ra.awardedHardcore})` : ''}</td></tr>`
            : row('RetroAchievements', 'nicht erkannt (Hash ' + ra.hash.slice(0, 8) + '…)'); })()}${row('Quelle', m.source === 'cocoon' ? 'Cocoon' : (m.matched || m.cover ? 'libretro' : ''))}
          ${row('Title-ID', m.titleId)}${row('Erkannt als', m.matched)}
          ${row('Datei', r.file)}${row('Größe', fmt(r.size))}${row('Ort', volLabel(r.path) + ' ' + folder.replace(r.volume, ''))}
        </table>
        ${m.desc ? `<p class="desc">${esc(m.desc)}</p>` : ''}
        ${shots.length ? `<div class="shots">${shots.map((k) => `<img alt="" loading="lazy" src="${mediaUrl(sysId, r.path, k)}">`).join('')}</div>` : ''}
        ${r.group ? `<h3 style="margin:12px 0 4px;font-size:15px">Dateien dieses Spiels</h3><table class="meta">${r.group.all.map((x) =>
          `<tr><td class="muted">${x.switchKind === 'base' ? 'Spiel' : x.switchKind === 'update' ? 'Update ' + esc(x.versionLabel) : 'DLC'}${r.group.oldUpdates.includes(x) ? ' <span class="badge warnbadge">veraltet</span>' : ''}</td><td>${esc(x.file)} <span class="muted">${fmt(x.size)}</span></td><td><button data-romdel="${esc(x.path)}">Löschen</button></td></tr>`).join('')}</table>` : ''}
        <div class="row">
          ${emus && emus.systems[sysId] && (!r.group || r.group.base) ? `<button class="primary" data-play="${esc(r.path)}" data-play-sys="${esc(sysId)}">▶ Spielen</button>` : ''}
          ${sysId === 'ports' ? '' : `<button data-send="${esc(r.path)}" data-send-sys="${esc(sysId)}" data-send-kind="game" data-send-title="${esc(r.name || r.file || '')}">Weitergeben</button>`}
          <button data-dir-roms="${esc(folder)}">Im Datei-Tab öffnen</button>
          ${r.dir ? '' : `<a class="btn" href="/api/files/raw?${q({ path: r.path, token })}">Herunterladen</a>`}
          <button data-romdel="${esc(r.path)}">Löschen</button>
          ${lib?.connected && sysId !== 'ports' && sysId !== '_unassigned' ? `<button data-offload="${esc(JSON.stringify(r.group ? r.group.all.map((x) => x.path) : [r.path]))}" data-offload-size="${r.group ? r.group.size : r.size}" data-offload-in="${lib.games?.[r.path]?.inRomm ? 1 : 0}" data-offload-name="${esc(m.title || r.name.replace(/\s*[([].*$/, ''))}">${lib.games?.[r.path]?.inRomm ? 'In RomM auslagern' : 'Hochladen + auslagern'}</button>` : ''}
          ${lib?.connected && sysId !== 'ports' && sysId !== '_unassigned' && lib.games?.[r.path] && !lib.games[r.path].inRomm
            ? (lib.games[r.path].uploaded ? '<button disabled title="RomM zeigt es nach dem nächsten Scan">✓ In RomM hochgeladen</button>'
              : `<button data-romm-up="${esc(JSON.stringify(r.group ? r.group.all.map((x) => x.path) : [r.path]))}">☁ Zu RomM hochladen</button>`) : ''}
          <span class="grow"></span>
          <button data-close>Schließen</button>
        </div>
        ${sysId !== '_unassigned' ? `<div class="panel" id="romSaves" style="margin-top:12px"><h3>Spielstände</h3><div class="sub">Lade …</div></div>` : ''}
        ${sysId === '_unassigned' ? `<div class="row"><select data-assign-sel="${esc(r.path)}"><option value="">Konsole wählen …</option>${romData.systemIds.map((s) => `<option value="${s.id}">${esc(s.name)}</option>`).join('')}</select><button data-assign="${esc(r.path)}" class="primary">In Konsolen-Ordner verschieben</button></div>` : ''}
      </div>
    </div>`;
  $('#romDetail').showModal();
  if (sysId !== '_unassigned') loadRomSaves(path, sysId);
}

// ---------- saves of one game: profiles, own RomM saves, shared ones ----------
let romSavesFor = null;
/** where a save was uploaded from ("vom Mac …", "von diesem Thor") */
const devTag = (s, d) => (s.device ? ` · <span class="muted">${s.device === d.thisDevice ? 'von diesem Gerät' : 'von ' + esc(s.device)}</span>` : '');
const when = (t) => (t ? new Date(t).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' }) : '');
const CMP = {
  same: ['Thor und RomM gleich', '#7fe0a8'], 'cloud-newer': ['RomM ist neuer', '#ffd27a'], 'device-newer': ['Thor ist neuer', '#9cc4ff'],
  'device-only': ['nur auf dem Thor', '#a7adb8'], 'cloud-only': ['nur in RomM', '#ffd27a'],
};
/** Thor vs. RomM, buttons, per-game backups */
function gameSaveHtml(g) {
  const cmp = CMP[g.compare];
  const cloudOk = g.rommAllowed && g.connected && g.inRomm;
  const card = (label, s, empty, hi) => `<div class="card${hi ? ' hi' : ''}"><div class="lbl">${label}</div>${s
    ? `<div class="t">${when(s.time)}</div><div class="muted">${fmt(s.size)}${s.device ? ' · von ' + esc(s.device) : ''} · ${esc(s.name)}</div>`
    : `<div class="muted">${empty}</div>`}</div>`;
  const cloudEmpty = !g.rommAllowed ? '„Nur lokal“ ist an (Spielstände → Daten sparen)' : !g.connected ? 'RomM nicht verbunden'
    : g.cloudError ? 'RomM antwortet nicht: ' + esc(g.cloudError) : g.inRomm === false ? 'Das Spiel ist nicht in RomM' : 'Noch kein Spielstand in RomM';
  const pullFirst = g.compare === 'cloud-newer' || g.compare === 'cloud-only';
  const pull = `<button data-gs="pull" ${g.cloud ? '' : 'disabled'} class="${pullFirst ? 'primary' : ''}">☁↓ Cloud-Stand aufs Thor holen</button>`;
  const push = `<button data-gs="push" ${g.device ? '' : 'disabled'} class="${!pullFirst && g.compare !== 'same' ? 'primary' : ''}">☁↑ Thor-Stand hochladen</button>`;
  const b = g.backups || [];
  return `<h3>Spielstände${g.multiProfile ? ` <span class="muted">· Profil ${esc(g.profile)}</span>` : ''}${cmp ? `<span class="cmpchip" style="color:${cmp[1]}">${cmp[0]}</span>` : ''}</h3>
    <div class="gsave">${card('Auf dem Thor', g.device, 'Noch kein Spielstand auf dem Thor', g.compare === 'device-newer' || g.compare === 'device-only')}
      ${card('Neuester in RomM', g.cloud, cloudEmpty, pullFirst)}</div>
    <div class="row">${cloudOk ? (pullFirst ? pull + push : push + pull) : ''}<button data-send-save ${g.device ? '' : 'disabled'}>🎁 An Freund schicken …</button></div>
    <details class="snaps"><summary>Backups · ${b.length ? `${b.length} ältere Stände · ${fmt(b.reduce((a, x) => a + x.size, 0))}` : 'noch keins – entsteht beim nächsten Speichern'}</summary>
      <table class="savetable">${b.map((x) => `<tr><td>${when(x.time)}<div class="muted">${esc(x.reasonLabel)}${g.multiProfile && x.profile ? ' · ' + esc(x.profile) : ''} · ${fmt(x.size)}</div></td>
        <td><button data-gs-restore="${esc(x.path)}" data-gs-time="${esc(when(x.time))}">Zurückholen</button></td></tr>`).join('')}</table>
      <div class="row"><button data-gs="backup">Jetzt sichern</button></div></details>`;
}
async function loadRomSaves(path, sys) {
  romSavesFor = { path, sys };
  const box = $('#romSaves');
  if (!box) return;
  let d, g;
  try {
    [d, g] = await Promise.all([api('/api/saves/game?' + q({ path, system: sys })).then((r) => r.body), api('/api/saves/state?' + q({ path, system: sys })).then((r) => r.body)]);
  } catch (err) { box.innerHTML = `<h3>Spielstände</h3><div class="sub">${esc(err.message)}</div>`; return; }
  if (romSavesFor?.path !== path || !$('#romSaves')) return;
  const profiles = d.profiles || [];
  const opts = profiles.map((p) => `<option value="${esc(p.id)}"${p.active ? ' selected' : ''}>${esc(p.name)}</option>`).join('');
  const installBtn = (id) => d.supported
    ? `<select data-sv-for="${id}">${opts}</select> <button data-sv-install="${id}">Installieren</button>` : '';
  box.innerHTML = gameSaveHtml(g) + `
    ${d.supported && profiles.length > 1 ? `<h4 style="margin:12px 0 4px">Profile</h4><div class="sub">Aktiv ist der Spielstand von <b>${esc(profiles.find((p) => p.active)?.name || '–')}</b>. Wechseln tauscht die Dateien, der alte Stand wird vorher gesichert.</div>
      <div class="proflist">${profiles.map((p) => `<button class="prof${p.active ? ' active' : ''}" data-sv-activate="${esc(p.id)}" ${p.active ? 'disabled' : ''}>${profDot(p)}${esc(p.name)}${p.hasSave ? ' 💾' : ''}</button>`).join('')}</div>`
      : ''}
    ${!d.connected || d.inRomm === false || d.error || !g.rommAllowed ? '' : `
    <h4 style="margin:12px 0 4px">Deine Spielstände in RomM</h4>
    ${d.own.length ? `<table class="savetable">${d.own.map((s) => `<tr><td>${esc(s.profile)}${devTag(s, d)}<div class="muted">${when(s.updated)} · ${fmt(s.size)}</div></td>
      <td><button data-sv-share="${s.id}" data-sv-on="${s.public ? 0 : 1}">${s.public ? '🌐 Geteilt – zurücknehmen' : 'Teilen'}</button> ${installBtn(s.id)}</td></tr>`).join('')}</table>`
      : '<div class="sub">Noch keiner – nach dem ersten Speichern lädt ThorBro ihn hoch.</div>'}
    <h4 style="margin:12px 0 4px">Geteilte Spielstände</h4>
    ${d.shared.length ? `<table class="savetable">${d.shared.map((s) => `<tr><td>${esc(s.user)}${s.slot ? ' · ' + esc(s.slot) : ''}${devTag(s, d)}<div class="muted">${esc(s.name)} · ${when(s.updated)} · ${fmt(s.size)}</div></td>
      <td>${installBtn(s.id)}</td></tr>`).join('')}</table>`
      : '<div class="sub">Niemand hat für dieses Spiel einen Spielstand geteilt. In RomM beim Spielstand „öffentlich“ setzen, dann taucht er hier auf.</div>'}`}`;
}
$('#romDetail').addEventListener('click', async (e) => {
  const s = e.target.closest('[data-send],[data-send-save]');
  if (s) {
    if (s.dataset.send) return openSend(s.dataset.send, s.dataset.sendSys, s.dataset.sendKind, s.dataset.sendTitle);
    if (romSavesFor) return openSend(romSavesFor.path, romSavesFor.sys, 'save', $('#romDetail h2')?.textContent || '');
    return;
  }
  const t = e.target.closest('[data-sv-activate],[data-sv-share],[data-sv-install],[data-gs],[data-gs-restore]');
  if (!t || !romSavesFor) return;
  const { path, sys } = romSavesFor;
  try {
    if (t.dataset.gs) {
      if (t.dataset.gs === 'pull' && !confirm('Cloud-Stand holen?\nEr ersetzt den Spielstand auf dem Thor. Der jetzige wird vorher als Backup gesichert.')) return;
      t.disabled = true; t.textContent = t.dataset.gs === 'backup' ? 'Sichert …' : 'Läuft …';
      const ep = t.dataset.gs === 'backup' ? '/api/saves/game/backup?' + q({ path }) : '/api/saves/cloud?' + q({ path, system: sys, do: t.dataset.gs });
      const { body } = await api(ep, { method: 'POST' });
      if (body.error) { alert(body.error); return loadRomSaves(path, sys); }
      toast(body.done || 'Fertig');
    }
    if (t.dataset.gsRestore) {
      if (!confirm(`Backup vom ${t.dataset.gsTime} zurückholen?\nDer jetzige Spielstand wird vorher gesichert.`)) return;
      t.disabled = true;
      const { body } = await api('/api/saves/game/restore?' + q({ path, backup: t.dataset.gsRestore }), { method: 'POST' });
      if (body.error) { alert(body.error); return loadRomSaves(path, sys); }
      toast(body.done || 'Zurückgeholt');
    }
    if (t.dataset.svActivate) {
      const { body } = await api('/api/saves/profile?' + q({ path, system: sys, id: t.dataset.svActivate }), { method: 'POST' });
      if (body.error) return alert(body.error);
      toast(`Spielstand von ${body.profile} ist jetzt aktiv`);
    }
    if (t.dataset.svShare) {
      const { body } = await api('/api/saves/share?' + q({ save: t.dataset.svShare, on: t.dataset.svOn }), { method: 'POST' });
      if (body.error) return alert(body.error);
      toast(body.public ? 'Geteilt – alle RomM-Nutzer sehen ihn jetzt' : 'Nicht mehr geteilt');
    }
    if (t.dataset.svInstall) {
      const sel = $('#romDetail').querySelector(`[data-sv-for="${t.dataset.svInstall}"]`);
      const prof = sel?.value;
      const name = sel?.selectedOptions[0]?.textContent || '';
      if (!confirm(`Spielstand für ${name} installieren?\nDer jetzige Spielstand von ${name} wird vorher gesichert.`)) return;
      t.disabled = true;
      const { body } = await api('/api/saves/install?' + q({ path, system: sys, save: t.dataset.svInstall, profile: prof }), { method: 'POST' });
      if (body.error) { t.disabled = false; return alert(body.error); }
      toast(`Installiert für ${body.profile}`);
    }
    loadRomSaves(path, sys);
  } catch (err) { alert(err.message); }
});

$('#romDetail').addEventListener('click', async (e) => {
  const b = e.target.closest('[data-offload]');
  if (!b) return;
  const paths = JSON.parse(b.dataset.offload);
  const size = +b.dataset.offloadSize;
  if (!confirm((b.dataset.offloadIn === '1' ? '' : 'Das Spiel wird zuerst zu RomM hochgeladen.\n') +
    `Danach wird es vom Thor gelöscht (${fmt(size)} frei). Spielstände bleiben auf dem Thor und kommen nach RomM.\nZurückholen geht im Store.`)) return;
  try {
    await api('/api/library/offload?' + paths.map((p) => 'path=' + encodeURIComponent(p)).join('&') + '&' + q({ name: b.dataset.offloadName || '' }), { method: 'POST' });
    toast('Wird ausgelagert – Fortschritt oben rechts');
    $('#romDetail').close();
    pollActivity();
  } catch (err) { alert(err.message); }
});

// one game to RomM without deleting it; progress on the button, the bubble top right shows it too
$('#romDetail').addEventListener('click', async (e) => {
  const b = e.target.closest('[data-romm-up]');
  if (!b) return;
  const paths = JSON.parse(b.dataset.rommUp);
  b.disabled = true; b.textContent = 'Verbinde …';
  try {
    const { body: job } = await api('/api/romm/upload?' + paths.map((p) => 'path=' + encodeURIComponent(p)).join('&') + '&' + q({ name: $('#romDetail h2')?.textContent || '' }), { method: 'POST' });
    if (job.error && !job.id) throw new Error(job.error);
    pollActivity();
    // the button keeps one short label (a changing text re-wrapped the whole page); progress, speed and time left are in the activity box
    const res = await waitJob(job.id, (j) => { b.textContent = j.state === 'queued' ? 'Wartet auf Upload …' : j.state === 'paused' ? 'Upload pausiert' : 'Lädt hoch …'; });
    const err = res.error || res.result?.errors?.[0];
    if (err) { b.disabled = false; b.textContent = '☁ Zu RomM hochladen'; alert('Upload fehlgeschlagen: ' + err); return; }
    b.textContent = '✓ In RomM hochgeladen'; b.title = 'RomM zeigt es nach dem nächsten Scan';
    const g = lib?.games?.[paths[0]]; if (g) g.uploaded = true;
    toast('In RomM hochgeladen');
  } catch (err) { b.disabled = false; b.textContent = '☁ Zu RomM hochladen'; alert(err.message); }
});

// ---------- store: games only in RomM ----------
let storeData = null;
async function loadStore(refresh) {
  $('#storeInfo').textContent = 'Lade RomM-Bibliothek …';
  try { storeData = (await api('/api/store' + (refresh ? '?refresh=1' : ''))).body; }
  catch (err) { $('#storeInfo').textContent = 'Fehler: ' + err.message; return; }
  renderStore();
}
function renderStore() {
  const d = storeData;
  if (d.error) { $('#storeInfo').textContent = 'RomM nicht erreichbar: ' + d.error; return; }
  if (!d.connected) { $('#storeInfo').textContent = 'RomM ist nicht verbunden (Tab Bibliothek).'; $('#storeList').innerHTML = ''; return; }
  const f = $('#storeFilter').value.trim().toLowerCase();
  // what counts for loading: the free space on the Thor's ROM volume, not how many games RomM has
  const free = info?.storage?.[0]?.free;
  $('#storeInfo').textContent = free != null ? `${fmt(free)} frei` : '';
  // one row per console with its name; no cover → the console icon + the title
  $('#storeList').innerHTML = d.systems.map((s) => {
    const games = s.games.filter((g) => !f || g.name.toLowerCase().includes(f));
    if (!games.length) return '';
    return `<div class="romhead">${sysIcon(s.system, 'headicon')}<h2>${esc(s.name)}</h2><span class="muted">${games.length}</span></div>
      <div class="shelf">${games.map((g) => `<div class="romcard" tabindex="0" data-store="${g.id}" title="Laden · ${fmt(g.size)}">
        ${g.hasCover ? `<img class="cover" loading="lazy" alt="" src="/api/romm/cover/${g.id}?token=${encodeURIComponent(token)}">` : `<div class="cover nocover">${sysIcon(s.system, 'bigicon')}<span>${esc(displayTitle(g.name))}</span></div>`}
        <div class="rtitle">${esc(displayTitle(g.name))}</div>
        <div class="rmeta" id="st-${g.id}">Laden · ${fmt(g.size)}</div>
        <div class="bar"><i id="sb-${g.id}" style="width:0"></i></div>
      </div>`).join('')}</div>`;
  }).join('');
  pollStore();
}
let storePoll = null;
const dur = (s) => s >= 3600 ? `${Math.floor(s / 3600)} h ${String(Math.floor(s % 3600 / 60)).padStart(2, '0')} min` : s >= 60 ? `${Math.floor(s / 60)} min ${String(Math.round(s % 60)).padStart(2, '0')} s` : `${Math.round(s)} s`;
const etaText = (j) => j.eta >= 0 ? ` · noch ${dur(j.eta)}` : '';
async function pollStore() {
  clearTimeout(storePoll);
  let busy = false;
  try {
    const { body } = await api('/api/store/downloads');
    for (const [id, j] of Object.entries(body)) {
      const t = $('#st-' + id), bar = $('#sb-' + id);
      if (!t) continue;
      const p = j.gameProgress ?? j.progress;
      if (j.error) t.textContent = 'Fehler: ' + j.error;
      else if (j.done) { t.textContent = '✓ auf dem Thor'; bar.style.width = '100%'; }
      else if (j.state === 'queued') { busy = true; t.textContent = 'Wartet …'; }
      else if (j.state === 'paused') { t.textContent = `Pausiert · ${Math.round(p * 100)} %`; bar.style.width = (p * 100) + '%'; }
      else { busy = true; t.textContent = `${Math.round(p * 100)} %${etaText(j)}`; bar.style.width = (p * 100) + '%'; }
    }
  } catch {}
  if (busy && !$('#store').hidden) storePoll = setTimeout(pollStore, 1000);
}
$('#storeList').addEventListener('click', async (e) => {
  const c = e.target.closest('[data-store]');
  if (!c) return;
  const id = c.dataset.store;
  const t = $('#st-' + id);
  if (t.textContent.includes('%') || t.textContent.startsWith('✓') || t.textContent.startsWith('Wartet')) return;
  const g = storeData?.systems?.flatMap((x) => x.games).find((x) => String(x.id) === id);
  try { await api('/api/store/download?' + q({ id, name: g ? displayTitle(g.name) : '', size: g?.size || 0 }), { method: 'POST' }); t.textContent = 'Wartet …'; pollStore(); pollActivity(); }
  catch (err) { alert(err.message); }
});
$('#storeFilter').oninput = renderStore;
$('#storeReload').onclick = () => loadStore(true);

$('#romFilter').oninput = renderRoms;
$('#romRegion').onchange = renderRoms;
$('#romsRescan').onclick = () => loadRoms(true);
$('#romsExport').onclick = async () => {
  if (!confirm('Cover und Infos im ES-DE-Format nach /storage/emulated/0/ES-DE schreiben?\nES-DE liest das direkt, iiSU über „ES-DE-Metadaten verknüpfen“. Vorhandene ES-DE-Einträge bleiben erhalten.')) return;
  const { body: job } = await api('/api/export/esde', { method: 'POST' });
  const res = await waitJob(job.id, (j) => toast(`Export ${Math.round(j.progress * 100)} % · ${j.message}`));
  alert(res.error ? 'Fehler: ' + res.error : `Export fertig: ${res.result.games} Spiele, ${res.result.images} Bilder nach ${res.result.dest}`);
};
$('#sysList').onclick = (e) => {
  const b = e.target.closest('[data-sys-pick]');
  if (!b) return;
  romSys = b.dataset.sysPick; store.set('romSys', romSys);
  $('#romFilter').value = '';
  renderRoms();
};
// folders and the back button live in the list itself
$('#romList').addEventListener('click', (e) => {
  const b = e.target.closest('[data-sys-pick]');
  if (!b) return;
  romSys = b.dataset.sysPick; store.set('romSys', romSys);
  renderRoms();
  $('#romList').querySelector('.romcard, .folder')?.focus();
});
// ---------- profiles (like the Switch: who is playing?) ----------
const profColor = (p) => p.color || '#4F8EF7';
const profDot = (p) => `<span class="pdot" style="background:${esc(profColor(p))}">${esc((p.name || '?').slice(0, 1).toUpperCase())}</span>`;
// resolves to a profile id, or null when cancelled; undefined = no picker needed
async function pickProfile(path, sys) {
  if (sys === 'switch') return undefined;
  const { body } = await api('/api/profiles?' + q({ path }));
  if (!body.needsPicker) return undefined;
  const dlg = $('#profilePick');
  const list = body.game || body.profiles;
  dlg.innerHTML = `<h2 style="margin:0;text-align:center">Wer spielt?</h2>
    <div class="pickgrid">${list.map((p) => `<button data-pick="${esc(p.id)}">${profDot(p)}<span>${esc(p.name)}</span><small>${p.active ? 'zuletzt gespielt' : p.hasSave ? 'hat Spielstand' : 'neu'}</small></button>`).join('')}</div>
    <div class="row"><span class="grow"></span><button data-pick-cancel>Abbrechen</button></div>`;
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; dlg.onclick = null; dlg.onclose = null; if (dlg.open) dlg.close(); resolve(v); };
    dlg.onclick = (e) => {
      const b = e.target.closest('[data-pick]');
      if (b) return finish(b.dataset.pick);
      if (e.target === dlg || e.target.closest('[data-pick-cancel]')) finish(null);
    };
    dlg.onclose = () => finish(null);
    dlg.showModal();
    (dlg.querySelector('.pickgrid button') || dlg).focus();
  });
}
async function play(path, sys) {
  try {
    const profile = await pickProfile(path, sys);
    if (profile === null) return;
    const r = await api('/api/roms/launch?' + q({ path, system: sys, ...(profile ? { profile } : {}) }), { method: 'POST' });
    if (r.body.error) {
      if (r.body.code === 'need_folder_grant' && confirm(r.body.error + '\n\nOrdnerauswahl jetzt auf dem Thor öffnen?')) await api('/api/emulators/grant', { method: 'POST' });
      else if (r.body.code !== 'need_folder_grant') alert(r.body.error);
    } else toast(`Startet in ${r.body.emulator} …`);
  } catch (err) { alert(err.message); }
}
function toast(t) {
  let el = $('#toast');
  if (!el) { el = Object.assign(document.createElement('div'), { id: 'toast' }); document.body.append(el); }
  el.textContent = t; el.className = 'show';
  clearTimeout(el._t); el._t = setTimeout(() => (el.className = ''), 2500);
}
// Switch: one entry per game (base + newest update + DLC)
function groupSwitch(list) {
  const groups = {};
  for (const r of list) {
    const key = r.baseId || r.path;
    (groups[key] ||= []).push(r);
  }
  return Object.values(groups).map((items) => {
    const base = items.find((x) => x.switchKind === 'base');
    const ups = items.filter((x) => x.switchKind === 'update').sort((a, b) => (b.version || 0) - (a.version || 0));
    const dlc = items.filter((x) => x.switchKind === 'dlc');
    const head = base || ups[0] || items[0];
    return { ...head, group: { base, update: ups[0], oldUpdates: ups.slice(1), dlc, all: items, size: items.reduce((a, x) => a + x.size, 0) } };
  }).sort((a, b) => a.name.localeCompare(b.name));
}
document.addEventListener('change', async (e) => {
  if (!e.target.dataset.emuSys) return;
  emus = (await api('/api/emulators/choose?' + q({ system: e.target.dataset.emuSys, emulator: e.target.value }), { method: 'POST' })).body;
  toast('Emulator gespeichert');
});
document.addEventListener('click', async (e) => {
  if (e.target.dataset.grant !== undefined) { await api('/api/emulators/grant', { method: 'POST' }); toast('Ordnerauswahl auf dem Thor geöffnet – ROMs-Ordner wählen und „Zugriff erlauben“'); }
});
$('#romList').onclick = (e) => {
  const p = e.target.closest('[data-play]');
  if (p) { e.stopPropagation(); return play(p.dataset.play, p.dataset.playSys); }
  const c = e.target.closest('[data-rom]');
  if (c) openRom(c.dataset.rom, c.dataset.sys);
};
$('#romDetail').onclick = async (e) => {
  const t = e.target, dlg = $('#romDetail');
  if (t === dlg || t.dataset.close !== undefined) return dlg.close();
  if (t.dataset.play) return play(t.dataset.play, t.dataset.playSys);
  try {
    if (t.dataset.dirRoms) { dlg.close(); showTab('files'); await loadFiles(t.dataset.dirRoms); }
    if (t.dataset.romdel && confirm(`${t.dataset.romdel} löschen?`)) {
      await api('/api/files?' + q({ path: t.dataset.romdel }), { method: 'DELETE' }); dlg.close(); await loadRoms(true);
    }
    if (t.dataset.assign) {
      const sel = dlg.querySelector('[data-assign-sel]');
      if (!sel.value) return alert('Erst eine Konsole wählen.');
      const vol = (info?.storage || []).find((v) => t.dataset.assign.startsWith(v.path));
      const root = romData.romRoots.find((r) => vol && r.startsWith(vol.path)) || (vol ? vol.path : '/storage/emulated/0') + '/ROMs';
      const to = `${root}/${sel.value}/${t.dataset.assign.split('/').pop()}`;
      await api('/api/files/move?' + q({ from: t.dataset.assign, to }), { method: 'POST' });
      dlg.close(); await loadRoms(true);
    }
  } catch (err) { alert(err.message); }
};

// ---------- RomM library ----------
let rommPlatforms = [], rommPid = +store.get('rommPid', 0);
async function loadLibrary() {
  const { body: st } = await api('/api/romm');
  $('#rommSetup').hidden = st.connected; $('#rommBrowse').hidden = !st.connected;
  if (!st.connected) { $('#rommUrl').value = st.url || ''; if (st.pending) showRommCode(st.pending); return; }
  const { body } = await api('/api/romm/platforms');
  if (body.error) { $('#rommInfo').textContent = body.error; return; }
  rommPlatforms = body.items;
  if (!rommPlatforms.some((p) => p.id === rommPid)) rommPid = rommPlatforms[0]?.id || 0;
  $('#rommInfo').textContent = `${st.url} · ${rommPlatforms.reduce((a, p) => a + p.count, 0)} Spiele`;
  renderRommPlatforms(); loadRommRoms(); loadSyncPlan();
}
async function loadSyncPlan() {
  const [{ body: p }, { body: st }] = await Promise.all([api('/api/romm/sync'), api('/api/romm')]);
  if (p.error) { $('#syncBody').textContent = p.error; return; }
  const missing = p.systems.filter((s) => s.missing > 0);
  $('#syncSummary').textContent = `· ${missing.reduce((a, s) => a + s.missing, 0)} Spiele fehlen in RomM · ${p.saves} Spielstände erkannt`;
  $('#syncBody').innerHTML = `
    <label style="display:block;margin-bottom:6px"><input id="syncAuto" type="checkbox" ${p.auto ? 'checked' : ''}> Spielstände automatisch hochladen, sobald ein Spiel speichert (nur im WLAN)</label>
    <div class="muted">Status: ${esc(p.watcher?.state || '–')}${p.watcher?.at ? ' · ' + new Date(p.watcher.at).toLocaleTimeString() : ''} · ${p.wifi ? 'WLAN verbunden' : 'kein WLAN'} · ${p.savesPending} Spielstände noch nicht hochgeladen</div>
    ${p.canWrite ? '' : '<p class="err">Für das Hochladen von Spielen braucht ThorBro Schreibrechte in RomM. <button id="rommRepair">Neu koppeln</button></p>'}
    <h4 style="margin:12px 0 4px">Spiele, die RomM noch nicht hat</h4>
    ${missing.length ? missing.map((s) => `<label style="display:block"><input type="checkbox" data-sync-sys="${s.system}"> ${esc((romData?.systems.find((x) => x.id === s.system) || {}).name || s.system)}: ${s.missing} Spiele, ${fmt(s.missingSize)}</label>`).join('') : '<span class="ok">✓ RomM hat alle Spiele vom Thor</span>'}
    <div class="row"><label class="muted">Heimnetz-Adresse (direkt statt übers Internet):</label><input id="rommLan" value="${esc(st.lanUrl || '')}" placeholder="aus" style="flex:1"><button id="rommLanSave">Speichern</button><span class="muted">${st.viaLan ? '✓ gerade im Heimnetz' : 'zuletzt nicht erreichbar'}</span></div>
    <div class="row"><button id="syncRun" class="primary">Jetzt abgleichen</button><span id="syncState" class="muted"></span></div>`;
  // Switch-Keys & Firmware live under Gerät (System); only the missing right is pointed out there
  $('#fwRight').innerHTML = p.canFirmware ? '' : '<p class="err">Dafür braucht ThorBro das Recht firmware.write: RomM neu koppeln für Firmware. <button id="rommRepair2">Neu koppeln</button></p>';
}
document.addEventListener('change', (e) => { if (e.target.id === 'syncAuto') api('/api/romm/sync/config?' + q({ auto: e.target.checked ? 1 : 0 }), { method: 'POST' }); });
document.addEventListener('click', async (e) => {
  if (e.target.id === 'rommRepair' || e.target.id === 'rommRepair2') { $('#rommSetup').hidden = false; $('#rommConnect').click(); }
  if (e.target.id === 'rommLanSave') { await api('/api/romm/lan?' + q({ url: $('#rommLan').value }), { method: 'POST' }); loadSyncPlan(); return; }
  if (e.target.id === 'fwRun') {
    e.target.disabled = true;
    const { body: job } = await api('/api/romm/firmware', { method: 'POST' });
    const res = await waitJob(job.id, (j) => { $('#fwState').textContent = j.message || '…'; });
    e.target.disabled = false;
    const r = res.result || {};
    $('#fwState').textContent = res.error ? 'Fehler: ' + res.error
      : r.needsRepair ? 'RomM neu koppeln für Firmware'
      : r.error ? 'Fehler: ' + r.error
      : r.missing ? r.missing
      : r.uploaded?.length ? 'Hochgeladen: ' + r.uploaded.join(', ') : 'RomM hat Keys & Firmware schon';
    return;
  }
  if (e.target.id !== 'syncRun') return;
  const systems = [...document.querySelectorAll('[data-sync-sys]:checked')].map((x) => x.dataset.syncSys).join(',');
  e.target.disabled = true;
  const { body: job } = await api('/api/romm/sync?' + q({ systems, saves: 1 }), { method: 'POST' });
  const res = await waitJob(job.id, (j) => { $('#syncState').textContent = `${Math.round(j.progress * 100)} % · ${j.message}${j.transfer ? ' · ' + j.transfer : ''}`; });
  e.target.disabled = false;
  $('#syncState').textContent = res.error ? 'Fehler: ' + res.error
    : `${res.result.romsUploaded} Spiele, ${res.result.savesUploaded} Spielstände hochgeladen${res.result.errors.length ? ' · ' + res.result.errors.length + ' Fehler: ' + res.result.errors.slice(0, 2).join('; ') : ''}`;
  loadRommRoms();
});
function renderRommPlatforms() {
  $('#rommPlatforms').innerHTML = rommPlatforms.map((p) => `<button data-romm-pid="${p.id}" class="${p.id === rommPid ? 'active' : ''}">${sysIcon(p.system)}<span class="grow">${esc(p.name)}</span><span class="n">${p.count}</span></button>`).join('');
}
async function loadRommRoms() {
  if (!rommPid) return;
  $('#rommList').innerHTML = '<p class="muted">Lade …</p>';
  const { body } = await api('/api/romm/roms?' + q({ platform: rommPid, search: $('#rommSearch').value }));
  if (body.error) { $('#rommList').innerHTML = `<p class="err">${esc(body.error)}</p>`; return; }
  const p = rommPlatforms.find((x) => x.id === rommPid);
  $('#rommList').innerHTML = `<div class="romhead">${sysIcon(p.system, 'headicon')}<h2>${esc(p.name)}</h2><span class="muted">${body.total} Spiele · ${fmt(p.size)}</span></div>
    <div class="covergrid">${body.items.map((r) => `<div class="romcard" title="${esc(r.file)}">
      ${r.hasCover ? `<img class="cover" loading="lazy" alt="${esc(r.name)}" src="/api/romm/cover/${r.id}?${authQ()}">` : `<div class="cover nocover">${sysIcon(r.system, 'bigicon')}<span>${esc(r.name)}</span></div>`}
      <div class="rtitle">${esc(r.name)}</div>
      <div class="rbadges"><span class="muted">${fmt(r.size)}</span>${r.multi ? '<span class="badge">Ordner</span>' : ''}</div>
      <div style="margin-top:6px">${r.onDevice ? '<span class="ok">✓ auf dem Thor</span>' : `<button class="primary" data-romm-get="${r.id}">Auf den Thor</button>`}</div>
    </div>`).join('')}</div>`;
}
function showRommCode(p) {
  $('#rommCode').innerHTML = `<p>Code: <strong style="font-size:22px;letter-spacing:2px">${esc(p.userCode)}</strong></p>
    <p><a class="btn primary" href="${esc(p.verifyUrl)}" target="_blank" rel="noopener">In RomM bestätigen</a> <span class="muted">Danach verbindet sich ThorBro von selbst.</span></p>`;
}
$('#rommConnect').onclick = async () => {
  const { body } = await api('/api/romm/connect?' + q({ url: $('#rommUrl').value }), { method: 'POST' });
  if (body.error) return ($('#rommCode').innerHTML = `<p class="err">${esc(body.error)}</p>`);
  showRommCode(body);
  for (let i = 0; i < body.expiresIn; i += 3) { await sleep(3000); const { body: st } = await api('/api/romm'); if (st.connected) return loadLibrary(); }
};
$('#rommDisconnect').onclick = async () => { await api('/api/romm/disconnect', { method: 'POST' }); loadLibrary(); };
$('#rommPlatforms').onclick = (e) => { const b = e.target.closest('[data-romm-pid]'); if (!b) return; rommPid = +b.dataset.rommPid; store.set('rommPid', rommPid); renderRommPlatforms(); loadRommRoms(); };
let rommSearchT; $('#rommSearch').oninput = () => { clearTimeout(rommSearchT); rommSearchT = setTimeout(loadRommRoms, 300); };
$('#rommList').onclick = async (e) => {
  const id = e.target.dataset.rommGet; if (!id) return;
  e.target.disabled = true;
  const { body: job } = await api('/api/romm/download?' + q({ id }), { method: 'POST' });
  const res = await waitJob(job.id, (j) => { e.target.textContent = `${Math.round(j.progress * 100)} %`; });
  if (res.error) { e.target.textContent = 'Fehler'; alert(res.error); } else { e.target.outerHTML = '<span class="ok">✓ auf dem Thor</span>'; romData = null; }
};

// ---------- save backups ----------
const histLabel = { save_up: '⬆ Spielstand hochgeladen', save_down: '⬇ Spielstand von RomM', rom_up: '⬆ Spiel zu RomM', offload: '📦 Ausgelagert', download: '⬇ Aus RomM geladen', restore: '↺ Spielstand zurückgesetzt', save_install: '⤓ Spielstand installiert', firmware_up: '🔑 Switch-Keys & Firmware zu RomM' };
async function loadHistory() {
  try {
    const list = (await api('/api/history?limit=30')).body;
    $('#historyList').innerHTML = list.length ? `<table class="files"><tbody>${list.map((e) => `<tr><td>${esc((e.name || '–').replace(/\s*\(.*$/, ''))}<div class="muted">${histLabel[e.kind] || esc(e.kind)}${e.system ? ' · ' + esc(e.system) : ''}${e.detail ? ' · ' + esc(e.detail) : ''}</div></td><td class="muted">${new Date(e.t).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })}</td></tr>`).join('')}</tbody></table>`
      : '<p class="muted">Noch nichts passiert.</p>';
  } catch (err) { $('#historyList').textContent = 'Fehler: ' + err.message; }
}
async function loadProfiles() {
  const { body } = await api('/api/profiles');
  $('#profileList').innerHTML = body.profiles.map((p) => `<span class="prof">${profDot(p)}${esc(p.name)}
    <button data-prof-rename="${esc(p.id)}" data-prof-name="${esc(p.name)}" title="Umbenennen">✎</button>
    ${p.id === 'main' ? '' : `<button data-prof-del="${esc(p.id)}" data-prof-name="${esc(p.name)}" title="Löschen">✕</button>`}</span>`).join('');
}
$('#profileAdd').onclick = async () => {
  const name = $('#profileName').value.trim();
  if (!name) return;
  const { body } = await api('/api/profiles?' + q({ name }), { method: 'POST' });
  if (body.error) return alert(body.error);
  $('#profileName').value = ''; toast(`Profil ${name} angelegt`); loadProfiles();
};
$('#profileList').onclick = async (e) => {
  const t = e.target;
  try {
    if (t.dataset.profRename) {
      const name = prompt('Neuer Name', t.dataset.profName);
      if (!name) return;
      const { body } = await api('/api/profiles/rename?' + q({ id: t.dataset.profRename, name }), { method: 'POST' });
      if (body.error) return alert(body.error);
    }
    if (t.dataset.profDel) {
      if (!confirm(`Profil ${t.dataset.profName} löschen?\nSeine Spielstände bleiben als Sicherung in save-backups und in RomM (Slot ${t.dataset.profName}).`)) return;
      const { body } = await api('/api/profiles/delete?' + q({ id: t.dataset.profDel }), { method: 'POST' });
      if (body.error) return alert(body.error);
    }
    loadProfiles();
  } catch (err) { alert(err.message); }
};
async function loadSaves() {
  loadHistory();
  loadProfiles().catch(() => {});
  loadDataSaver().catch(() => {});
  $('#savesInfo').textContent = 'Lade …';
  const { body } = await api('/api/saves');
  $('#savesAuto').checked = body.auto;
  $('#savesInfo').textContent = `Sicherungen liegen in ${body.root}. Pro Speicherort bleiben die letzten 30.`;
  $('#savesList').innerHTML = body.locations.map((l) => `
    <div class="panel">
      <div class="row" style="margin:0"><div class="grow"><h3>${esc(l.name)}</h3>
        <div class="sub">${esc(l.emulator)} · ${l.files} Dateien · ${fmt(l.size)}${l.lastChange ? ' · zuletzt geändert ' + new Date(l.lastChange).toLocaleString() : ''}<br>${esc(l.path)}</div></div>
        <button data-save-now="${esc(l.id)}">Jetzt sichern</button></div>
      ${l.snapshots.length ? `<details class="snaps"><summary>${l.snapshots.length} Sicherungen, neueste ${new Date(l.snapshots[0].time).toLocaleString()}</summary><table>
        ${l.snapshots.map((s) => `<tr><td>${esc(s.name.replace('.zip', ''))}</td><td class="muted">${fmt(s.size)}</td>
          <td><a class="btn" href="/api/files/raw?${q({ path: s.path, token })}">Laden</a></td>
          <td><button data-restore="${esc(s.path)}">Wiederherstellen</button></td></tr>`).join('')}</table></details>` : '<div class="sub" style="margin-top:6px">Noch keine Sicherung.</div>'}
    </div>`).join('') || '<p class="muted">Keine Spielstände gefunden.</p>';
}
$('#savesReload').onclick = loadSaves;
$('#savesAuto').onchange = (e) => api('/api/saves/config?' + q({ auto: e.target.checked ? 1 : 0 }), { method: 'POST' });
$('#savesAll').onclick = async () => {
  $('#savesInfo').textContent = 'Sichere …';
  const { body } = await api('/api/saves/backup', { method: 'POST' });
  const made = body.results.filter((r) => r.snapshot).length;
  toast(made ? `${made} neue Sicherung(en)` : 'Nichts geändert seit der letzten Sicherung');
  loadSaves();
};
$('#savesList').onclick = async (e) => {
  const t = e.target;
  try {
    if (t.dataset.saveNow) { await api('/api/saves/backup?' + q({ id: t.dataset.saveNow, force: 1 }), { method: 'POST' }); toast('Gesichert'); loadSaves(); }
    if (t.dataset.restore && confirm('Diese Sicherung zurückspielen?\nDer aktuelle Stand wird vorher automatisch gesichert. Den Emulator vorher schließen.')) {
      const { body } = await api('/api/saves/restore?' + q({ path: t.dataset.restore }), { method: 'POST' });
      alert(body.error || `${body.restored} Dateien wiederhergestellt nach ${body.into}`); loadSaves();
    }
  } catch (err) { alert(err.message); }
};

// ---------- cleanup ----------
$('#cleanScan').onclick = async () => {
  $('#cleanInfo').textContent = 'Prüfe den Speicher … (kann ein paar Sekunden dauern)';
  $('#cleanResult').innerHTML = '';
  let c;
  try { c = (await api('/api/cleanup')).body; } catch (err) { $('#cleanInfo').textContent = err.message; return; }
  $('#cleanInfo').textContent = '';
  const dupFiles = c.duplicates.flatMap((d) => d.files.filter((f) => !f.keep));
  $('#cleanResult').innerHTML = `
    <div class="panel"><div class="row" style="margin:0"><div class="grow"><h3>macOS-Reste</h3>
      <div class="sub">${c.macos.count} Dateien (._* und .DS_Store), ${fmt(c.macos.size)}. Entstehen beim Kopieren vom Mac und sind auf dem Thor nutzlos.
      ${c.parts.count ? `<br>Dazu ${c.parts.count} abgebrochene Uploads (${fmt(c.parts.size)}), älter als 1 Tag.` : ''}</div></div>
      ${c.macos.count + c.parts.count ? '<button id="cleanJunk" class="primary">Löschen</button>' : '<span class="ok">✓ sauber</span>'}</div></div>
    <div class="panel"><h3>Doppelte Spiele</h3>
      ${c.duplicates.length ? `<div class="sub">Vorausgewählt sind nur Kopien mit identischem Inhalt. Das jeweils behaltene Exemplar ist nicht wählbar.</div>
      ${c.duplicates.map((d) => `<div class="dupe" style="margin-top:8px"><strong>${esc(d.title)}</strong> <span class="muted">${esc(d.system)}</span>
        ${d.files.map((f) => `<label><input type="checkbox" data-clean="${esc(f.path)}" ${f.keep ? 'disabled' : f.identical ? 'checked' : ''}>
          ${esc(f.file)} <span class="muted">${fmt(f.size)}${f.keep ? ' · wird behalten' : f.identical ? ' · identisch' : ' · andere Version'}</span></label>`).join('')}</div>`).join('')}`
      : '<span class="ok">✓ keine Duplikate</span>'}</div>
    <div class="panel"><h3>Veraltete oder doppelte Switch-Updates</h3>
      ${c.oldUpdates.length ? `<div class="sub">Es gibt eine neuere Version desselben Updates.</div>${c.oldUpdates.map((u) => `<div class="dupe"><label><input type="checkbox" data-clean="${esc(u.path)}" checked> ${esc(u.file)} <span class="muted">${fmt(u.size)} · ${u.reason === 'doppelt' ? 'gleiche Version doppelt' : 'neuere Version vorhanden'}</span></label></div>`).join('')}`
      : '<span class="ok">✓ keine</span>'}</div>
    ${dupFiles.length + c.oldUpdates.length ? '<div class="row"><button id="cleanSelected" class="primary">Ausgewählte löschen</button><span id="cleanSel" class="muted"></span></div>' : ''}`;
  updateCleanSel();
};
function updateCleanSel() {
  const sel = [...document.querySelectorAll('[data-clean]:checked')];
  const el = $('#cleanSel'); if (el) el.textContent = `${sel.length} Dateien ausgewählt`;
}
$('#cleanResult').onchange = updateCleanSel;
$('#cleanResult').onclick = async (e) => {
  const t = e.target;
  if (t.id === 'cleanJunk') {
    const { body } = await api('/api/cleanup/junk', { method: 'POST' });
    toast(`${body.deleted} Dateien gelöscht, ${fmt(body.freed)} frei`); $('#cleanScan').click();
  }
  if (t.id === 'cleanSelected') {
    const sel = [...document.querySelectorAll('[data-clean]:checked')].map((x) => x.dataset.clean);
    if (!sel.length || !confirm(`${sel.length} Dateien endgültig löschen?`)) return;
    for (const p of sel) { try { await api('/api/files?' + q({ path: p }), { method: 'DELETE' }); } catch (err) { alert(p + ': ' + err.message); } }
    toast(`${sel.length} Dateien gelöscht`); romData = null; $('#cleanScan').click();
  }
};

// ---------- apps ----------
let apps = [];
async function loadApps() { apps = (await api('/api/apps')).body; renderApps(); }
function renderApps() {
  const f = $('#appFilter').value.toLowerCase(), sys = $('#showSystem').checked;
  $('#appGrid').innerHTML = apps
    .filter((a) => (sys || !a.system) && (a.label + a.package).toLowerCase().includes(f))
    .map((a) => `<div class="app"><img loading="lazy" alt="" src="/api/apps/${encodeURIComponent(a.package)}/icon?token=${encodeURIComponent(token)}">
      <div class="name">${esc(a.label)}</div><div class="ver">${esc(a.version)}</div>
      <div class="acts"><button data-launch="${esc(a.package)}">Starten</button><button data-uninstall="${esc(a.package)}">Löschen</button></div></div>`)
    .join('');
}
$('#appFilter').oninput = renderApps;
$('#showSystem').onchange = renderApps;
$('#appsReload').onclick = loadApps;
$('#appGrid').onclick = async (e) => {
  const t = e.target;
  try {
    if (t.dataset.launch) await api(`/api/apps/${encodeURIComponent(t.dataset.launch)}/launch`, { method: 'POST' });
    if (t.dataset.uninstall) await api(`/api/apps/${encodeURIComponent(t.dataset.uninstall)}/uninstall`, { method: 'POST' });
  } catch (err) { alert(err.message); }
};

// ---------- files ----------
let currentDir = store.get('dir', '/storage/emulated/0');
function fillVolumes() {
  if (!info) return;
  $('#volumes').innerHTML = info.storage.map((v) => `<option value="${esc(v.path)}">${esc(v.label)}</option>`).join('');
  const vol = info.storage.find((v) => currentDir.startsWith(v.path));
  if (vol) $('#volumes').value = vol.path;
}
$('#volumes').onchange = () => loadFiles($('#volumes').value);
async function loadFiles(path) {
  let res;
  try { res = (await api('/api/files?' + q({ path }))).body; }
  catch (err) { if (path !== '/storage/emulated/0') return loadFiles('/storage/emulated/0'); throw err; }
  currentDir = res.path; store.set('dir', currentDir);
  if (!info) await loadDevice().catch(() => {});
  const vol = (info?.storage || []).find((v) => currentDir.startsWith(v.path));
  const rootPath = vol ? vol.path : '/storage/emulated/0';
  const parts = currentDir.slice(rootPath.length).split('/').filter(Boolean);
  let acc = rootPath;
  $('#crumbs').innerHTML = `<a data-dir="${esc(rootPath)}">${esc(vol ? vol.label : 'Speicher')}</a>` +
    parts.map((p) => { acc += '/' + p; return ` / <a data-dir="${esc(acc)}">${esc(p)}</a>`; }).join('');
  const up = currentDir !== rootPath ? `<tr><td><a class="dir" data-dir="${esc(currentDir.replace(/\/[^/]+$/, ''))}">..</a></td><td></td><td></td></tr>` : '';
  $('#fileRows').innerHTML = up + res.entries.map((e) => {
    const full = currentDir + '/' + e.name;
    const name = e.dir ? `<a class="dir" data-dir="${esc(full)}">📁 ${esc(e.name)}</a>`
      : `<a href="/api/files/raw?${q({ path: full, token })}">${esc(e.name)}</a>`;
    const install = !e.dir && ext(e.name) === 'apk' ? `<button data-install="${esc(full)}">Installieren</button> `
      : !e.dir && isArchiveName(e.name) ? `<button data-unpack="${esc(full)}">Entpacken</button> ` : '';
    return `<tr><td>${name}</td><td class="size">${e.dir ? '' : fmt(e.size)}</td><td class="acts">${install}<button data-del="${esc(full)}">Löschen</button></td></tr>`;
  }).join('');
}
document.addEventListener('click', async (e) => {
  const t = e.target;
  try {
    if (t.dataset.dir) { e.preventDefault(); await loadFiles(t.dataset.dir); }
    if (t.dataset.del && confirm(`${t.dataset.del} löschen?`)) {
      await api('/api/files?' + q({ path: t.dataset.del }), { method: 'DELETE' }); await loadFiles(currentDir);
    }
    if (t.dataset.unpack) {
      const { body: job } = await api('/api/extract?' + q({ path: t.dataset.unpack }), { method: 'POST' });
      toast('Entpacke …');
      const res = await waitJob(job.id, (j) => toast(`Entpacke ${Math.round(j.progress * 100)} %`));
      toast(res.error ? 'Fehler: ' + res.error : `${res.result.files} Dateien entpackt`);
      loadFiles(currentDir);
    }
    if (t.dataset.install) {
      const { body } = await api('/api/install?' + q({ path: t.dataset.install }), { method: 'POST' });
      alert('Installation gestartet, bitte auf dem Thor bestätigen. Status: ' + body.status);
    }
  } catch (err) { alert(err.message); }
});
$('#mkdir').onclick = async () => {
  const name = prompt('Name des neuen Ordners');
  if (!name) return;
  try { await api('/api/files/mkdir?' + q({ path: currentDir + '/' + name }), { method: 'POST' }); loadFiles(currentDir); }
  catch (err) { alert(err.message); }
};
$('#filesReload').onclick = () => loadFiles(currentDir);

// ---------- start ----------
// ---------- streaming (Moonlight / scrcpy) ----------
async function loadStream() {
  let s;
  try { s = (await api('/api/stream')).body; } catch (err) { $('#mlStatus').textContent = 'Fehler: ' + err.message; return; }
  const btn = $('#mlInstall');
  if (!s.installed) {
    $('#mlStatus').textContent = 'Moonlight ist nicht installiert.';
    btn.textContent = 'Moonlight installieren' + (s.latest ? ` (${s.latest})` : ''); btn.hidden = false;
  } else if (s.updateAvailable) {
    $('#mlStatus').textContent = `Moonlight ${s.version} installiert, ${s.latest} verfügbar.`;
    btn.textContent = 'Aktualisieren'; btn.hidden = false;
  } else {
    $('#mlStatus').innerHTML = `<span class="ok">Moonlight ${esc(s.version || '')} installiert</span>`;
    btn.hidden = true;
  }
  const t = s.tunnel || {};
  $('#mlVia').textContent = !s.installed ? '' : !s.usb ? 'über WLAN – Moonlight spricht direkt mit dem Mac'
    : 'über USB' + (t.connected ? ' ✓ (Tunnel steht)' : t.running ? ' – Tunnel wartet auf den Mac' : ' – RomBro startet den Tunnel');
  $('#mlLaunch').hidden = !s.installed;
  loadScrcpy();
}
async function loadScrcpy() {
  let s;
  try { s = (await api('/api/stream/scrcpy')).body; } catch (err) { $('#scStatus').textContent = 'Fehler: ' + err.message; return; }
  $('#scWifi').hidden = s.wifi;
  const ip = s.ip || '<Thor-IP>';
  const pair = `<p>Einmal koppeln: am Thor „Koppeln am Thor öffnen“ → <em>Gerät über einen Kopplungscode koppeln</em>, dann auf dem Mac:</p>
    <pre class="code">adb pair ${esc(ip)}:&lt;Kopplungsport&gt; &lt;Code&gt;</pre>`;
  if (s.usb) {
    $('#scStatus').innerHTML = '<span class="ok">USB-Kabel steckt – läuft über USB</span>';
    $('#scHow').innerHTML = `<p>Kabel schlägt WLAN: RomBro nimmt USB von selbst und wechselt beim Abziehen auf WLAN. Von Hand auf dem Mac:</p><pre class="code">scrcpy -d</pre>`;
  } else if (!s.wifi) {
    $('#scStatus').textContent = s.shell === 'ready' ? 'Kabelloses Debugging ist aus.' : 'Kabelloses Debugging ist aus. Ohne Shizuku in den Entwickleroptionen einschalten.';
    $('#scHow').innerHTML = pair;
  } else if (!s.command) {
    $('#scStatus').textContent = 'Kabelloses Debugging ist an, Port nicht lesbar (Shizuku starten) – steht am Thor unter Kabelloses Debugging.';
    $('#scHow').innerHTML = pair + `<p>Dann auf dem Mac:</p><pre class="code">scrcpy --tcpip=${esc(ip)}:&lt;Port&gt;</pre>`;
  } else {
    $('#scStatus').innerHTML = `<span class="ok">Bereit auf ${esc(s.address)}</span>`;
    $('#scHow').innerHTML = pair + `<p>Dann auf dem Mac (der Port ändert sich, wenn Debugging neu startet):</p><pre class="code">${esc(s.command)}</pre>`;
  }
}
$('#scWifi').onclick = async () => {
  $('#scWifi').disabled = true;
  try { await api('/api/stream/scrcpy/wifi', { method: 'POST' }); toast('Kabelloses Debugging an'); } catch (err) { alert(err.message); }
  $('#scWifi').disabled = false; loadScrcpy();
};
$('#scPair').onclick = async () => {
  try { await api('/api/stream/scrcpy/pair', { method: 'POST' }); toast('Am Thor: Gerät über einen Kopplungscode koppeln'); } catch (err) { alert(err.message); }
};
$('#scReload').onclick = () => loadScrcpy();
$('#mlInstall').onclick = async () => {
  const btn = $('#mlInstall'); btn.disabled = true;
  try {
    const { body } = await api('/api/stream/install', { method: 'POST' });
    const j = await waitJob(body.id, (j) => ($('#mlStatus').textContent = `${j.message || 'Lade'} … ${Math.round(j.progress * 100)} %`));
    if (j.error) throw new Error(j.error);
    $('#mlStatus').textContent = 'Installation auf dem Thor bestätigen …';
    const st = await waitInstall(j.result.session);
    toast(st === 'success' ? 'Moonlight installiert' : st);
  } catch (err) { alert(err.message); }
  btn.disabled = false; loadStream();
};
$('#mlScreen').onclick = () => { showTab('screen'); $('#screenStart').click(); };
$('#mlLaunch').onclick = async () => {
  try { await api('/api/stream/moonlight/open', { method: 'POST' }); toast('Moonlight startet …'); } catch (err) { alert(err.message); }
};
$('#mlScan').onclick = async () => {
  $('#mlScanInfo').textContent = 'Suche Sunshine-Rechner …'; $('#mlHosts').innerHTML = '';
  try {
    const { hosts } = (await api('/api/stream/hosts')).body;
    $('#mlScanInfo').textContent = hosts.length ? `${hosts.length} gefunden` : 'Keiner gefunden. Läuft Sunshine und ist der Rechner im selben WLAN?';
    $('#mlHosts').innerHTML = hosts.map((h) => `<div class="card"><h3>${esc(h.name)}</h3><div>${esc(h.address)}</div>
      <p><a class="btn" href="${esc(h.web)}" target="_blank" rel="noopener">PIN eingeben</a></p></div>`).join('');
  } catch (err) { $('#mlScanInfo').textContent = 'Fehler: ' + err.message; }
};

// ---------- multiplayer (the party plays together) ----------
let np = null;
const npModes = { retroarch: 'RetroArch', ppsspp: 'PPSSPP', eden: 'Eden', azahar: 'Azahar', share: 'Weitergeben' };
// ---------- avatars: a console robot per person (same pick as the Thor app: Java hashCode of the name) ----------
const AVATAR_CONSOLES = ['snes', 'n64', 'gba', 'psx', 'megadrive', 'gc', 'nes', 'ps2', 'dreamcast', 'switch', 'nds', 'psp',
  'gb', 'saturn', 'wii', 'xbox', 'n3ds', 'gbc', 'pcengine', 'wonderswan'];
const avatarConsole = (name) => {
  let h = 0; for (const c of String(name || '').trim().toLowerCase()) h = (Math.imul(31, h) + c.charCodeAt(0)) | 0;
  return AVATAR_CONSOLES[((h % AVATAR_CONSOLES.length) + AVATAR_CONSOLES.length) % AVATAR_CONSOLES.length];
};
const avatar = (name, cls = '') => `<div class="avatar ${cls}"><img alt="" src="${iconUrl(avatarConsole(name))}"></div>`;

async function loadNetplay() {
  try { np = (await api('/api/netplay')).body; } catch (err) { $('#npStatus').textContent = 'Fehler: ' + err.message; return; }
  const p = np.party, n = np.partyNet || {}, l = np.lobby;
  const others = p ? p.members.filter((m) => !m.me).map((m) => m.name) : [];
  $('#npStatus').textContent = !p ? 'Keine Party – Freunde einladen, dann spielt ihr zusammen.'
    : p.leaderMe ? `Du leitest die Party${others.length ? ' mit ' + others.join(', ') : ' (noch allein)'} · ein Spiel, das du startest, startet bei allen`
    : n.connected ? `In der Party von ${n.leader} · verbunden ✓`
    : n.following ? `In der Party von ${n.leader} · verbinde …` : 'In einer Party · warte auf den Leiter';
  $('#npClose').hidden = !l;
  // the party at a glance: you, the others, a seat to invite someone
  $('#npParty').innerHTML = `<div class="face">${avatar(friends?.me?.name || 'Du', 'big me')}<span>Du</span></div>` +
    (p ? p.members.filter((m) => !m.me).map((m) => `<div class="face">${avatar(m.name, 'big')}<span>${esc(m.name)}</span></div>`).join('') : '') +
    `<button class="face invite" data-np-invite><div class="avatar big">+</div><span>Einladen</span></button>`;
  const room = np.azahar && np.azahar.server;
  $('#npLobby').innerHTML = l ? `<p><strong>${esc(l.game)}</strong> · ${esc(npModes[l.mode] || l.mode)}${l.mode === 'azahar' && room ? ' · im 3DS-Raum: ' + esc((room.members || []).join(', ') || 'niemand') : ''}</p>` : '';
  $('#npHow').textContent = (np.services || []).map((x) => `${x.name}\n  ${x.how}`).join('\n\n');
  clearTimeout(loadNetplay.t); loadNetplay.t = setTimeout(() => { if (!$('#netplay').hidden) loadNetplay(); }, 4000);
}
$('#npFriends').onclick = () => $('#friendsBtn').click();
$('#npParty').addEventListener('click', (e) => { if (e.target.closest('[data-np-invite]')) $('#friendsBtn').click(); });
$('#npClose').onclick = async () => { await api('/api/netplay/close', { method: 'POST' }); loadNetplay(); };
$('#npPrepare').onclick = async () => {
  $('#npInfo').textContent = 'Richte ein …';
  try { const { body } = await api('/api/netplay/prepare', { method: 'POST' }); $('#npInfo').textContent = Object.entries(body).map(([k, v]) => `${npModes[k] || k}: ${v}`).join(' · ') || 'Nichts zu tun'; }
  catch (err) { $('#npInfo').textContent = 'Fehler: ' + err.message; }
};

// ---------- Wi-Fi Direct ----------
const wifiQr = (ssid, pass) => { const e = (x) => String(x).replace(/([\\;,:"])/g, '\\$1'); return `WIFI:T:WPA;S:${e(ssid)};P:${e(pass)};;`; };
const qrImg = (text, caption) => `<figure style="display:inline-block;margin:10px 16px 0 0;text-align:center">
  <img src="/qr.svg?${q({ t: text })}" alt="QR-Code" width="180" height="180" style="background:#fff;border-radius:8px">
  <figcaption class="muted" style="max-width:180px">${esc(caption)}</figcaption></figure>`;
function showDirect(d) {
  $('#dxStop').hidden = !d.active; $('#dxStart').hidden = d.active;
  $('#dxInfo').innerHTML = d.active
    ? `Netz <strong>${esc(d.ssid)}</strong> · Passwort <code>${esc(d.pass)}</code> · ThorBro laden: <a href="${esc(d.download)}">${esc(d.download)}</a><br>`
      + qrImg(wifiQr(d.ssid, d.pass), '1. Scannen: Netz beitreten') + qrImg(d.download, '2. Scannen: ThorBro laden')
    : (d.permission ? 'aus' : 'Beim ersten Mal am Thor unter Multiplayer → Wi-Fi Direct die Berechtigung erlauben.')
      + (d.lanDownload ? ` · Im selben WLAN gibt es ThorBro unter <a href="${esc(d.lanDownload)}">${esc(d.lanDownload)}</a><br>` + qrImg(d.lanDownload, 'Im selben WLAN scannen') : '');
}
async function loadDirect() { try { showDirect((await api('/api/direct')).body); } catch {} }
$('#dxStart').onclick = async () => {
  $('#dxInfo').textContent = 'Startet …';
  try { const { body } = await api('/api/direct/start', { method: 'POST' }); if (body.error) alert(body.error); showDirect(body); } catch (err) { alert(err.message); }
};
$('#dxStop').onclick = async () => { try { showDirect((await api('/api/direct/stop', { method: 'POST' })).body); } catch (err) { alert(err.message); } };

// ---------- GPU drivers ----------
let drv = null;
async function loadDrivers(refresh) {
  $('#drvInfo').textContent = 'Lade Treiberlisten …';
  try { drv = (await api('/api/drivers' + (refresh ? '?refresh=1' : ''))).body; }
  catch (err) { $('#drvInfo').textContent = 'Fehler: ' + err.message; return; }
  renderDrivers();
}
function renderDrivers() {
  const gpu = drv.gpu ? `${drv.soc} · ${drv.gpu}` : drv.soc;
  $('#drvInfo').textContent = `Chip: ${gpu}. Turnip-Treiber für Adreno 6xx/7xx passen; „a8xx“-Builds nur für Adreno 8xx.`;
  const have = new Set(drv.downloaded.map((d) => d.name));
  $('#drvLocal').innerHTML = drv.downloaded.length ? `<table class="files"><tbody>${drv.downloaded.map((d) => `<tr>
      <td>${esc(d.driver?.name || d.name)}<div class="muted">${esc(d.name)}${d.driver?.version ? ' · ' + esc(d.driver.version) : ''}${d.driver ? '' : ' · <span class="err">keine meta.json</span>'}</div></td>
      <td>${fmt(d.size)}</td><td><button data-del="${esc(d.name)}">Löschen</button></td></tr>`).join('')}</tbody></table>`
    : '<p class="muted">Noch keine. Unten einen Treiber laden.</p>';
  const emus = drv.emulators.filter((e) => e.installed);
  $('#drvHow').innerHTML = `Die Dateien liegen in <code>${esc(drv.dir)}</code>. Importieren im Emulator:` +
    (emus.length ? '<ul>' + emus.map((e) => `<li><strong>${esc(e.name)}</strong>: ${esc(e.where)} <button data-open="${esc(e.pkg)}">Öffnen</button></li>`).join('') + '</ul>'
      : ' kein passender Emulator installiert (Eden, Azahar, Dolphin).');
  $('#drvSources').innerHTML = drv.sources.map((s) => `<details class="panel" ${s.id === 'k11' ? 'open' : ''}>
    <summary><strong>${esc(s.name)}</strong> <span class="muted">${esc(s.note)}</span></summary>
    ${s.error ? `<p class="err">${esc(s.error)}</p>` : ''}
    <table class="files"><tbody>${s.releases.map((r) => r.assets.map((a) => `<tr>
      <td>${esc(a.name)}<div class="muted">${esc(r.name)} · ${esc(r.date)}</div></td><td>${fmt(a.size)}</td>
      <td>${have.has(a.name) ? '<span class="ok">geladen</span>' : `<button data-get="${esc(a.url)}">Laden</button>`}</td></tr>`).join('')).join('')}</tbody></table>
    </details>`).join('');
}
$('#drivers').addEventListener('click', async (e) => {
  const t = e.target;
  try {
    if (t.dataset.get) {
      t.disabled = true;
      const { body } = await api('/api/drivers/download?' + q({ url: t.dataset.get }), { method: 'POST' });
      const j = await waitJob(body.id, (j) => (t.textContent = Math.round(j.progress * 100) + ' %'));
      if (j.error) throw new Error(j.error);
      toast(j.result.warning || 'Treiber geladen');
      loadDrivers(false);
    } else if (t.dataset.del) {
      if (!confirm(t.dataset.del + ' löschen?')) return;
      await api('/api/drivers?' + q({ name: t.dataset.del }), { method: 'DELETE' });
      loadDrivers(false);
    } else if (t.dataset.open) {
      await api(`/api/apps/${t.dataset.open}/launch`, { method: 'POST' });
      toast('Emulator startet …');
    }
  } catch (err) { alert(err.message); t.disabled = false; }
});
$('#drvReload').onclick = () => loadDrivers(true);

// ---------- background activity, top right (offload queue, downloads, RomM upload) ----------
let actTimer = null, actWasBusy = false;
async function pollActivity() {
  clearTimeout(actTimer);
  let list = [];
  try { list = (await api('/api/activity')).body; } catch {}
  let box = $('#activity');
  if (!box) { box = Object.assign(document.createElement('div'), { id: 'activity' }); document.body.append(box); }
  box.innerHTML = list.map((a) => {
    const n = a.total > 1 || (a.total === 1 && !a.done) ? `<b>${a.index}/${a.total}</b>` : '';
    const err = a.error && a.error !== 'null' ? a.error : '';
    const pr = a.gameProgress ?? a.progress;
    const line = a.transfer || (a.step && a.step !== a.name ? a.step : '');
    return `<div class="act ${a.done ? (err ? 'bad' : 'ok') : ''}" data-xfers title="Downloads &amp; Uploads öffnen"><div class="acthead"><span>${a.done ? (err ? '⚠' : '✓') : a.state === 'paused' ? '⏸' : a.kind === 'download' ? '⬇' : '⬆'} ${esc(a.title)}</span>${n}</div>
      <div class="actname">${esc(a.game || a.name || '')}</div>
      ${a.done ? (err ? `<div class="err">${esc(err)}</div>` : '') : `${line ? `<div class="muted">${esc(line)}</div>` : ''}<div class="bar"><i style="width:${Math.round(pr * 100)}%"></i></div><div class="muted">${Math.round(pr * 100)} %${a.waiting ? ` · +${a.waiting} wartend` : ''}</div>`}</div>`;
  }).join('');
  const busy = list.some((a) => !a.done);
  moodState.busy = busy; moodState.err = list.some((a) => a.done && a.error && a.error !== 'null'); moodState.done = !busy && list.length > 0 && !moodState.err;
  updMood();
  // something finished: refresh what changed
  if (actWasBusy && !busy) { if (romData) loadRoms(true); if (storeData && !$('#store').hidden) loadStore(true); }
  actWasBusy = busy;
  // hidden tab / app in the background: stop polling until it is visible again (saves battery on the Thor)
  if (!document.hidden) actTimer = setTimeout(pollActivity, list.length ? 1000 : 5000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && token) pollActivity(); });

// ---------- Downloads & Uploads: the queue page (pause, resume, cancel, how many at once) ----------
let xfersTimer = null;
async function renderTransfers() {
  clearTimeout(xfersTimer);
  const dlg = $('#xfers');
  if (!dlg.open) return;
  let d;
  try { d = (await api('/api/transfers')).body; } catch { xfersTimer = setTimeout(renderTransfers, 3000); return; }
  const order = { running: 0, queued: 1, paused: 2, error: 3, done: 4 };
  const items = d.items.slice().sort((a, b) => order[a.state] - order[b.state] || a.added - b.added);
  const label = { running: '', queued: 'Wartet', paused: 'Pausiert', error: 'Fehler', done: 'Fertig' };
  $('#xfersList').innerHTML = items.length ? items.map((i) => {
    const p = Math.round((i.progress || 0) * 100);
    const sub = [i.kind === 'download' ? 'Aus RomM laden' : 'Zu RomM hochladen', label[i.state], i.state === 'running' ? (i.transfer || i.step) : '', i.state === 'paused' ? p + ' %' : '', i.error].filter(Boolean).join(' · ');
    const btns = (i.state === 'running' || i.state === 'queued' ? `<button data-xf="pause" data-id="${i.id}">⏸ Pause</button>` : '') +
      (i.state === 'paused' ? `<button data-xf="resume" data-id="${i.id}">▶ Weiter</button>` : '') +
      (i.state === 'error' ? `<button data-xf="resume" data-id="${i.id}">↻ Nochmal</button>` : '') +
      (i.state !== 'done' ? `<button data-xf="cancel" data-id="${i.id}">✕ Abbrechen</button>` : '');
    return `<div class="xfrow ${i.state}"><div class="xfmain"><b>${i.kind === 'download' ? '⬇' : '⬆'} ${esc(i.title)}</b><div class="muted">${esc(sub)}</div>
      ${i.state === 'running' || i.state === 'paused' ? `<div class="bar"><i style="width:${p}%"></i></div>` : ''}</div><div class="xfbtns">${btns}</div></div>`;
  }).join('') : '<p class="muted">Gerade nichts in der Warteschlange.</p>';
  $('#xfersPar').value = String(d.parallel);
  xfersTimer = setTimeout(renderTransfers, 1000);
}
function openTransfers() { const dlg = $('#xfers'); if (!dlg.open) dlg.showModal(); renderTransfers(); }
$('#xfersBtn').onclick = openTransfers;
document.addEventListener('click', (e) => { if (e.target.closest('#activity .act')) openTransfers(); });
$('#xfersClose').onclick = () => $('#xfers').close();
$('#xfers').addEventListener('close', () => clearTimeout(xfersTimer));
$('#xfersClear').onclick = async () => { await api('/api/transfers/clear', { method: 'POST' }); renderTransfers(); };
$('#xfersPar').onchange = async (e) => { await api('/api/transfers/parallel?n=' + e.target.value, { method: 'POST' }); renderTransfers(); };
$('#xfersList').addEventListener('click', async (e) => {
  const b = e.target.closest('[data-xf]'); if (!b) return;
  if (b.dataset.xf === 'cancel' && !confirm('Auftrag abbrechen? Schon Geladenes wird verworfen.')) return;
  await api(`/api/transfers/${b.dataset.xf}?id=${encodeURIComponent(b.dataset.id)}`, { method: 'POST' });
  renderTransfers(); pollActivity();
});

// ---------- look (Glas / Doodle): the Thor keeps the setting, the browser remembers it to avoid a flash ----------
function applyTheme(id) {
  document.documentElement.dataset.theme = id;
  store.set('theme', id);
  $('#themeSel').value = id;
}
applyTheme(store.get('theme', 'glass'));
$('#themeSel').onchange = async (e) => {
  applyTheme(e.target.value);
  try { await api('/api/theme?' + q({ id: e.target.value }), { method: 'POST' }); } catch {}
};
async function loadTheme() {
  try { const { body } = await api('/api/theme'); if (body.theme) applyTheme(body.theme); } catch {}
}
// the robot next to the wordmark (doodle): busy while something runs, tired on low battery, stuffed when full
const MOODS = { base: 'Mir geht\'s prima!', busy: 'Bin beschäftigt …', done: 'Erledigt!', ask: 'Da stimmt was nicht.',
  tired: 'Ich brauche Strom …', stuffed: 'Ich bin ziemlich voll.', wait: 'Ich warte auf den Thor …' };
const moodState = { busy: false, err: false, done: false };
function updMood() {
  let m = 'base';
  const b = info?.battery, st = info?.storage?.[0];
  if (!info) m = 'wait';
  else if (moodState.busy) m = 'busy';
  else if (moodState.err) m = 'ask';
  else if (moodState.done) m = 'done';
  else if (b && b.percent <= 15 && !b.charging) m = 'tired';
  else if (st && st.total && st.free / st.total < 0.05) m = 'stuffed';
  const img = $('#mascot');
  if (!img.src.endsWith('/' + m + '.svg')) img.src = 'mascot/' + m + '.svg';
  img.alt = img.title = MOODS[m];
  $('#mood').textContent = MOODS[m];
}

async function start() {
  if (!token) return showLogin();
  loadTheme();
  pollActivity();
  try { await loadDevice(); showTab(store.get('tab', 'upload')); if (!romData) loadRoms(false); }
  catch (err) { if (!(err instanceof AuthError)) $('#conn').textContent = 'Thor nicht erreichbar'; }
}
window.addEventListener('beforeunload', (e) => { if (running) e.preventDefault(); });
start();

// gamepad A / Enter on a focused card opens it
document.addEventListener('keydown', (e) => {
  if ((e.key === 'Enter' || e.key === ' ') && e.target.classList?.contains('romcard')) { e.preventDefault(); e.target.click(); }
});

// ---------- Daten sparen ----------
async function loadDataSaver(set) {
  const { body } = await api('/api/datasaver' + (set ? '?' + q(set) : ''), set ? { method: 'POST' } : {});
  $('#dataModes').innerHTML = body.modes.map((m) => `<button class="prof${m.id === body.mode ? ' active' : ''}" data-dmode="${m.id}" title="${esc(m.sub)}">${esc(m.label)}<div class="muted" style="font-size:12px">${esc(m.sub)}</div></button>`).join('');
  $('#dataKeep').innerHTML = body.keepChoices.map((n) => `<option value="${n}"${n === body.keep ? ' selected' : ''}>${n}</option>`).join('');
  $('#giftAuto').checked = !!body.giftAuto;
  $('#giftMax').innerHTML = (body.giftMbChoices || []).map((n) => `<option value="${n}"${n === body.giftMaxMobileMb ? ' selected' : ''}>${n >= 1000 ? n / 1000 + ' GB' : n + ' MB'}</option>`).join('');
}
$('#giftAuto').onchange = (e) => loadDataSaver({ giftAuto: e.target.checked ? '1' : '0' });
$('#giftMax').onchange = (e) => loadDataSaver({ giftMaxMobileMb: e.target.value });
$('#dataModes').addEventListener('click', (e) => { const b = e.target.closest('[data-dmode]'); if (b) loadDataSaver({ mode: b.dataset.dmode }); });
$('#dataKeep').onchange = (e) => loadDataSaver({ keep: e.target.value });

// ---------- friends (Pals.kt): button top right, panel on the right, "Freunde hinzufügen" popup ----------
let friends = null;
const ago = (ms) => { if (!ms) return ''; const m = Math.floor((Date.now() - ms) / 60000);
  return m < 2 ? 'gerade eben' : m < 60 ? `vor ${m} Min.` : m < 2880 ? `vor ${Math.floor(m / 60)} Std.` : `vor ${Math.floor(m / 1440)} Tagen`; };
let gifts = null;
async function friendsLoad(path = '/api/friends', method = 'GET') {
  try { const { body } = await api(path, { method }); if (body.error) alert(body.error); else friends = body; } catch (e) { if (!(e instanceof AuthError)) console.warn(e); }
  try { gifts = (await api('/api/gifts')).body; } catch (e) { /* older ThorBro */ }
  renderFriends();
}
const giftWhat = (k) => ({ save: 'einen Spielstand', both: 'ein Spiel mit Spielstand' }[k] || 'ein Spiel');
const WAY = { lan: 'lokal', romm: 'RomM', tunnel: 'Tunnel' };
function giftHtml(g) {
  const from = g.from?.name || 'Freund';
  const live = g.live;
  const size = g.size || g.save?.size || 0;
  const ways = g.kind === 'save' ? '' : `<div class="muted">Wege: ${['lan', 'romm', 'tunnel'].map((w) => `${WAY[w]} ${!g.ways?.[w] ? '–' : g.probe?.[w] ? g.probe[w] + ' MB/s' : '✓'}`).join(' · ')}</div>`;
  let sub, btns = '';
  if (g.state === 'loading') {
    const pct = live?.total ? Math.floor(live.done * 100 / live.total) : 0;
    sub = `von ${esc(from)}${live?.way ? ' · ' + WAY[live.way] : ''}${live?.total ? ` · ${fmt(live.done)} von ${fmt(live.total)}` : ''}<div class="bar"><i style="width:${pct}%"></i></div>`;
  } else if (g.state === 'done') {
    sub = `von ${esc(from)} · ${g.result?.save ? 'Spielstand ' + esc(g.result.save) : 'fertig'}`;
  } else {
    sub = [esc(g.title), size ? fmt(size) : '', g.kind === 'both' ? 'mit Spielstand' : '', g.missing ? 'Spiel fehlt' : ''].filter(Boolean).join(' · ')
      + (g.state === 'failed' || g.state === 'wifi' ? `<div class="bad">${esc(g.error || '')}</div>` : '') + ways;
    if (g.state !== 'expired') {
      if (g.kind === 'save' && g.missing) btns = g.asked ? '<span class="muted">angefragt</span>' : `<button data-gift="ask" data-gid="${g.gid}">Spiel auch anfragen</button>`;
      else if (g.kind === 'save') btns = `<button class="primary" data-gift="take" data-gid="${g.gid}">Übernehmen</button><button data-gift="backup" data-gid="${g.gid}">Als Backup</button>`;
      else btns = `<button class="primary" data-gift="load" data-gid="${g.gid}">${g.state === 'new' ? 'Annehmen & laden' : 'Weiter laden'}</button>`;
      btns += `<button data-gift="decline" data-gid="${g.gid}" title="ablehnen">✕</button>`;
    }
  }
  const title = g.state === 'loading' ? `Lädt: ${esc(g.title)}` : g.state === 'done' ? `${esc(g.title)} ist da` : `${esc(from)} schickt dir ${giftWhat(g.kind)}`;
  return `<div class="person gift">${avatar(from)}<div class="grow"><strong>${title}</strong><div class="muted">${sub}</div></div>${btns}</div>`;
}
function renderFriends() {
  const f = friends; if (!f) return;
  const n = f.requests.length + (f.invites || []).length + (gifts?.waiting || 0); const b = $('#friendsBadge'); b.hidden = !n; b.textContent = n;
  const d = $('#friendsDlg'); if (!d.open) return;
  const person = (p, sub, btns) => `<div class="person">${avatar(p.name, p.online ? 'on' : '')}
    <div class="grow"><strong>${esc(p.name)}</strong><div class="muted">${esc(sub)}</div></div>${btns}</div>`;
  d.innerHTML = `<div class="me">${avatar(f.me.name, 'big me')}<div class="grow"><strong style="font-size:19px">${esc(f.me.name)}</strong>
      <div class="muted">ID ${esc(f.me.id.slice(0, 6))}</div></div><button data-fr="name">Name</button></div>
    <div class="row"><button data-fr="party" class="grow">${f.partyOpen ? 'Party: offen' : 'Party: nur Einladung'}</button></div>
    ${(() => {
      const inc = (gifts?.incoming || []).filter((g) => g.state !== 'done' || Date.now() - g.at < 86400000);
      const wants = (gifts?.outgoing || []).flatMap((o) => (o.to || []).filter((t) => t.want).map((t) => ({ o, t })));
      if (!inc.length && !wants.length) return '';
      return '<h4>Für dich</h4>' + inc.map(giftHtml).join('') + wants.map(({ o, t }) => person({ name: t.name }, `möchte auch das Spiel: ${o.title}`,
        `<button class="primary" data-gift="sendwanted" data-gid="${o.gid}" data-id="${esc(t.id)}">Schicken</button>`)).join('');
    })()}
    ${f.party ? `<h4>${f.party.pending ? 'Party – warte auf den Leiter …' : 'Party (' + f.party.members.length + ')'}</h4>` + f.party.members.map((m) => person({ ...m, online: true },
      [m.me ? 'du' : '', m.id === f.party.leader ? 'Leiter' : ''].filter(Boolean).join(' · '), '')).join('')
      + `<div class="row"><button data-fr="leave">${f.party.leaderMe ? 'Party auflösen' : 'Party verlassen'}</button></div>` : ''}
    ${(f.invites || []).length ? '<h4>Party-Einladungen</h4>' + f.invites.map((i) => person(i, `lädt dich ein (${i.members} drin)`,
      `<button class="primary" data-fr="pjoin" data-id="${esc(i.id)}" data-party="${esc(i.party)}">Beitreten</button><button data-fr="pdecline" data-id="${esc(i.id)}">✕</button>`)).join('') : ''}
    ${f.requests.length ? '<h4>Anfragen</h4>' + f.requests.map((r) => person(r, 'möchte dein Freund sein',
      `<button class="primary" data-fr="accept" data-id="${esc(r.id)}">Annehmen</button><button data-fr="decline" data-id="${esc(r.id)}">✕</button>`)).join('') : ''}
    <h4>Freunde (${f.friends.length})</h4>
    ${f.friends.length ? f.friends.map((x) => person(x, x.online ? (x.joinable ? 'online · Party offen' : x.inParty ? 'online · in einer Party' : 'online') : [x.app, x.seen ? 'zuletzt ' + ago(x.seen) : ''].filter(Boolean).join(' · '),
      (x.joinable ? `<button class="primary" data-fr="popen" data-id="${esc(x.id)}">Beitreten</button>` : '')
      + `<button data-fr="invite" data-id="${esc(x.id)}">Einladen</button><button data-fr="remove" data-id="${esc(x.id)}" data-name="${esc(x.name)}" title="entfernen">✕</button>`)).join('')
      : '<div class="muted">Noch keine. Unten „Freunde hinzufügen“ – per Code oder LocalSend.</div>'}
    ${f.nearby.length ? '<h4>In der Nähe</h4>' + f.nearby.map((n) => person(n, (n.app || 'ThorBro') + ' · gleiches Netz',
      n.asked ? '<span class="muted">angefragt</span>' : `<button data-fr="add" data-id="${esc(n.id)}">Hinzufügen</button>`)).join('') : ''}
    <div class="row" style="margin-top:18px"><button class="primary grow" data-fr="addDlg">Freunde hinzufügen</button><button data-fr="close">Schließen</button></div>`;
}
let nearbyTimer = null;
$('#friendsBtn').onclick = () => {
  $('#friendsDlg').showModal(); renderFriends(); friendsLoad();
  friendsLoad('/api/friends/scan', 'POST');
  clearInterval(nearbyTimer); nearbyTimer = setInterval(() => $('#friendsDlg').open ? friendsLoad('/api/friends/scan', 'POST') : clearInterval(nearbyTimer), 15000);
};
$('#friendsDlg').addEventListener('click', async (e) => {
  const t = e.target.closest('[data-gift]'); if (!t) return;
  const a = t.dataset.gift, gid = t.dataset.gid;
  try {
    if (a === 'decline' && !confirm('Geschenk ablehnen?')) return;
    const act = { take: 'accept?' + q({ gid, how: 'take' }), backup: 'accept?' + q({ gid, how: 'backup' }), load: 'accept?' + q({ gid, how: 'load' }),
      decline: 'decline?' + q({ gid }), ask: 'ask?' + q({ gid }), sendwanted: 'sendwanted?' + q({ gid, id: t.dataset.id }) }[a];
    t.disabled = true;
    const { body } = await api('/api/gifts/' + act, { method: 'POST' });
    if (body.error) return alert(body.error);
    if (body.id && !body.done) {
      toast(a === 'sendwanted' ? 'Wird verschickt …' : 'Wird geladen – Fortschritt hier und oben rechts');
      friendsLoad();
      const timer = setInterval(() => $('#friendsDlg').open ? friendsLoad() : clearInterval(timer), 1500);
      const j = await waitJob(body.id);
      clearInterval(timer);
      if (j.error) alert(j.error); else toast(j.result?.done || 'Fertig');
    }
  } catch (err) { alert(err.message); }
  friendsLoad();
});
$('#friendsDlg').onclick = async (e) => {
  if (e.target.closest('[data-gift]')) return;
  const t = e.target.closest('[data-fr]'); if (!t) { if (e.target === $('#friendsDlg')) $('#friendsDlg').close(); return; }
  const a = t.dataset.fr, id = t.dataset.id;
  if (a === 'close') return $('#friendsDlg').close();
  if (a === 'addDlg') return friendAddOpen();
  if (a === 'name') { const n = prompt('Dein Name (so sehen dich deine Freunde):', friends.me.name); if (n && n.trim()) friendsLoad('/api/friends/name?' + q({ name: n.trim() }), 'POST'); return; }
  if (a === 'party') return friendsLoad('/api/friends/party?' + q({ open: friends.partyOpen ? '0' : '1' }), 'POST');
  const party = { leave: 'leave', invite: 'invite?' + q({ id }), pjoin: 'join?' + q({ leader: id, party: t.dataset.party }), pdecline: 'decline?' + q({ id }), popen: 'open?' + q({ id }) }[a];
  if (party) return friendsLoad('/api/party/' + party, 'POST');
  if (a === 'remove' && !confirm(`${t.dataset.name} als Freund entfernen?`)) return;
  friendsLoad(`/api/friends/${a}?` + q({ id }), 'POST');
};
async function friendAddOpen() {
  const d = $('#friendAdd');
  d.innerHTML = `<h3>Freunde hinzufügen</h3>
    <div class="sub">Code eines Freundes (Tasten oder Ziffern 1–8):</div>
    <div class="row"><input id="frCode" class="grow" placeholder="z. B. ↓ ↓ A B X X → Y oder 22561487"><button id="frRedeem" class="primary">Hinzufügen</button></div>
    <div id="frInfo" class="muted"></div>
    <h4 style="margin-top:18px">Dein Code – der andere gibt ihn bei sich ein</h4>
    <div id="frMine" class="keycode">…</div><div id="frMineSub" class="muted"></div>
    <div class="row" style="margin-top:10px"><button id="frNew">Neuer Code</button><button id="frFile">Als Datei (LocalSend)</button>
      <label class="btn"><input id="frImport" type="file" accept=".thorfriend" hidden>Datei importieren</label></div>
    <div class="sub">Die Datei „Name.thorfriend“ per LocalSend o. ä. schicken. Landet sie auf dem anderen Thor im Download-Ordner, seid ihr beim nächsten Öffnen des Freunde-Menüs befreundet.</div>
    <div class="row" style="margin-top:14px"><button id="frClose">Schließen</button></div>`;
  d.showModal();
  const mine = async (fresh) => { const { body } = await api('/api/friends/code' + (fresh ? '?fresh=1' : ''));
    $('#frMine').textContent = body.code; $('#frMineSub').textContent = `als Ziffern ${body.digits} · gilt noch ${body.minutes} Min.`; };
  mine(false);
  $('#frNew').onclick = () => mine(true);
  $('#frClose').onclick = () => { d.close(); friendsLoad(); };
  $('#frFile').onclick = async () => {
    const r = await fetch('/api/friends/file', { headers: { Authorization: 'Bearer ' + token } });
    const name = (r.headers.get('Content-Disposition') || '').match(/filename="(.+)"/)?.[1] || 'ThorBro.thorfriend';
    const a = document.createElement('a'); a.href = URL.createObjectURL(await r.blob()); a.download = name; a.click();
  };
  $('#frImport').onchange = async (e) => {
    const file = e.target.files[0]; if (!file) return;
    const { body } = await api('/api/friends/import', { method: 'POST', body: await file.text() });
    $('#frInfo').textContent = body.error ? body.error : 'Anfrage gesendet – sobald der andere online ist, seid ihr Freunde.'; friendsLoad();
  };
  $('#frRedeem').onclick = async () => {
    const code = $('#frCode').value.trim(); if (!code) return;
    $('#frRedeem').disabled = true; $('#frInfo').textContent = 'suche …';
    try {
      const { body } = await api('/api/friends/redeem?' + q({ code }), { method: 'POST' });
      for (;;) {
        await new Promise((r) => setTimeout(r, 700));
        const { body: j } = await api('/api/jobs/' + body.id);
        if (j.done) { $('#frInfo').textContent = j.error ? j.error : `${j.result.name} ist jetzt dein Freund`; break; }
      }
    } catch (err) { $('#frInfo').textContent = err.message; }
    $('#frRedeem').disabled = false; friendsLoad();
  };
}
setTimeout(() => friendsLoad(), 1500);
setInterval(() => { if (document.visibilityState === 'visible') friendsLoad(); }, 30000);

// ---------- Verschicken (Gifts.kt): a save, a game or both to friends; the old nearby hand-over at the bottom ----------
const KINDS = [['save', 'Nur Spielstand'], ['game', 'Nur Spiel'], ['both', 'Spiel + Spielstand']];
async function openSend(path, sys, kind, title) {
  const d = $('#sendDlg');
  let what = kind, picked = new Set(), routes = null, busy = false, msg = '';
  const kinds = sys === 'ports' ? KINDS.slice(0, 1) : KINDS;
  const render = () => {
    const fr = routes?.friends || [];
    const names = fr.filter((f) => picked.has(f.id)).map((f) => f.name);
    d.innerHTML = `<div class="muted">Verschicken</div><h3 style="margin:2px 0 10px">${esc(title || '')}</h3>
      <div class="row">${kinds.map(([k, l]) => `<button class="${k === what ? 'primary' : ''}" data-sk="${k}">${l}</button>`).join('')}</div>
      <h4>An wen</h4>
      ${!routes ? '<div class="muted">Schaue, wer wie erreichbar ist …</div>' : routes.error ? `<div class="bad">${esc(routes.error)}</div>`
        : !fr.length ? '<div class="muted">Noch keine Freunde – oben rechts im Freunde-Menü hinzufügen.</div>'
        : fr.map((f) => `<label class="person${picked.has(f.id) ? ' on' : ''}"><input type="checkbox" data-sf="${esc(f.id)}" ${picked.has(f.id) ? 'checked' : ''}>
          ${avatar(f.name, f.online ? 'on' : '')}
          <div class="grow"><strong>${esc(f.name)}</strong><div class="muted">${f.near ? 'online · im selben Netz' : f.online ? 'online' : 'zuletzt ' + ago(f.seen)}</div></div>
          <span class="cmpchip">${esc(f.label)}</span></label>`).join('')}
      ${what === 'game' ? `<div class="person"><div class="grow"><strong>Thor in der Nähe (ohne Freundschaft)</strong><div class="muted">wie bisher: Wi-Fi Direct oder WLAN + PIN</div></div><button data-snear>Anbieten</button></div>` : ''}
      ${msg ? `<div class="sub">${esc(msg)}</div>` : ''}
      <div class="row" style="margin-top:14px"><button class="primary grow" data-sgo ${names.length && !busy ? '' : 'disabled'}>${busy ? 'Wird verschickt …' : !names.length ? 'Freunde auswählen'
        : `An ${names.length <= 2 ? names.join(' & ') : names.length + ' Freunde'} schicken${routes?.size ? ' · ' + fmt(routes.size) : ''}`}</button><button data-sclose>Schließen</button></div>`;
  };
  const load = async () => { routes = null; render(); try { routes = (await api('/api/gifts/routes?' + q({ system: sys, path, kind: what }))).body; } catch (e) { routes = { error: e.message }; } render(); };
  d.onclick = async (e) => {
    const t = e.target;
    if (t === d || t.dataset.sclose !== undefined) return d.close();
    if (t.dataset.sk) { what = t.dataset.sk; return load(); }
    if (t.dataset.sf) { t.checked ? picked.add(t.dataset.sf) : picked.delete(t.dataset.sf); return render(); }
    if (t.dataset.snear !== undefined) {
      try {
        const { body } = await api('/api/netplay/host?' + q({ system: sys, path, share: '1' }), { method: 'POST' });
        msg = body.error || (body.direct ? 'Wird angeboten – der Thor in der Nähe bekommt eine Einladung' : `Wird angeboten · PIN ${body.pin}`);
      } catch (err) { msg = err.message; }
      return render();
    }
    if (t.dataset.sgo !== undefined) {
      busy = true; msg = 'Bereite vor …'; render();
      try {
        const { body } = await api('/api/gifts/send?' + q({ system: sys, path, kind: what, to: [...picked].join(',') }), { method: 'POST' });
        if (body.error) throw new Error(body.error);
        const j = await waitJob(body.id, (b) => { if (!b.done) { msg = (b.message || 'Bereite vor') + (b.progress ? ` · ${Math.floor(b.progress * 100)} %` : ''); render(); } });
        if (j.error) throw new Error(j.error);
        toast(j.result?.done || 'Verschickt'); d.close(); return;
      } catch (err) { msg = err.message; }
      busy = false; render();
    }
  };
  d.showModal(); load();
}
