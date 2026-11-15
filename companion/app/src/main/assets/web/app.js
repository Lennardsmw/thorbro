'use strict';
const $ = (s) => document.querySelector(s);
const CHUNK = 8 * 1024 * 1024;
const store = {
  get(k, d) { try { return localStorage.getItem('thorbro.' + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem('thorbro.' + k, v); } catch {} },
};
let token = store.get('token', '');

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
  if (name === 'roms' && !romData) loadRoms(false);
  if (name === 'apps') loadApps();
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
$('#logout').onclick = () => { token = ''; store.set('token', ''); showLogin(); };

// ---------- device ----------
let info = null;
async function loadDevice() {
  const { body } = await api('/api/info');
  info = body;
  $('#conn').textContent = `${body.manufacturer} ${body.model} · ${body.ip}:${body.port}`;
  const p = body.permissions;
  const perm = (ok, t) => `<div class="${ok ? 'ok' : 'err'}">${ok ? '✓' : '✗'} ${t}</div>`;
  $('#deviceInfo').innerHTML = `
    <div class="card"><h3>Gerät</h3>${esc(body.manufacturer)} ${esc(body.model)}<br>Android ${esc(body.android)} · ${body.displays} Bildschirme<br>ThorBro ${esc(body.appVersion)}</div>
    <div class="card"><h3>Akku</h3>${body.battery.percent}%${body.battery.charging ? ' · lädt' : ''}</div>
    ${body.storage.map((v) => `<div class="card"><h3>${esc(v.label)}</h3>${fmt(v.free)} frei von ${fmt(v.total)}<br><span class="muted">${esc(v.path)}</span></div>`).join('')}
    <div class="card"><h3>ThorBro-Update</h3>Installiert: <strong>${esc(body.appVersion)}</strong>
      <div class="row" style="margin:8px 0"><button id="updCheck" class="primary">Nach Updates suchen</button></div>
      <div id="selfStatus" class="muted"></div>
      <label style="display:block;margin-top:8px"><input id="updAuto" type="checkbox"> Automatisch aktualisieren (alle 6 h)</label>
      <details style="margin-top:6px"><summary class="muted">Weitere Optionen</summary>
        <label class="muted" style="display:block;margin-top:6px">Update-Quelle (GitHub-Release oder JSON)</label>
        <input id="updFeed" style="width:100%">
        <label class="btn" style="margin-top:6px">APK von diesem Gerät installieren<input id="selfApk" type="file" accept=".apk" hidden></label>
      </details></div>
    <div class="card"><h3>Berechtigungen</h3>
      ${perm(p.allFiles, 'Zugriff auf alle Dateien')}${perm(p.overlay, 'Apps fernstarten')}${perm(p.install, 'APKs installieren')}
      ${p.allFiles && p.overlay && p.install ? '' : '<p class="muted">Fehlende Rechte in der ThorBro-App auf dem Thor erteilen.</p>'}</div>`;
  loadUpdateConfig();
  fillVolumes();
}

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
const APK_DIR = '/storage/emulated/0/Download/ThorBro';

$('#romRoot').value = store.get('romRoot', '') || '/storage/emulated/0/ROMs';
$('#romRoot').onchange = () => store.set('romRoot', $('#romRoot').value.replace(/\/+$/, ''));

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
    return `<li class="${cls}"><div class="qhead"><span class="qname">${it.isFolder ? '📁 ' : ''}${esc(it.name)}</span>${sys}${btn}${rm}</div>
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
  if (e.target.dataset.sys) { queue.find((x) => x.id === e.target.dataset.sys).system = e.target.value; renderQueue(); }
};
$('#startAll').onclick = () => runQueue();
$('#clearDone').onclick = () => { for (let i = queue.length - 1; i >= 0; i--) if (queue[i].state === 'fertig') queue.splice(i, 1); renderQueue(); };

class Paused extends Error {}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function uploadOne(item, file, dest, before) {
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
const iconUrl = (id) => `/api/consoles/${encodeURIComponent(id)}/icon?${authQ()}`;
const coverUrl = (sys, name) => `/api/roms/cover?${q({ system: sys, name })}&${authQ()}`;
const mediaUrl = (sys, path, kind) => `/api/roms/media?${q({ system: sys, path, kind })}&${authQ()}`;
// Cocoon's box art first, libretro cover second
const coverOf = (sysId, r, m) => (m.media || []).includes('icon') ? mediaUrl(sysId, r.path, 'icon') : m.cover ? coverUrl(sysId, m.cover) : '';
const fmtTime = (s) => (s >= 3600 ? Math.floor(s / 3600) + ' h ' : '') + Math.round((s % 3600) / 60) + ' min';

async function loadRoms(refresh) {
  $('#romStats').textContent = 'Suche ROMs …';
  try { romData = (await api('/api/roms' + (refresh ? '?refresh=1' : ''))).body; }
  catch (err) { $('#romStats').textContent = 'Fehler: ' + err.message; return; }
  if (refresh) Object.keys(metaCache).forEach((k) => delete metaCache[k]);
  // Upload target: use the ROMs folder the scan found, unless the user picked one.
  if (!store.get('romRoot', '') && romData.romRoots.length) {
    $('#romRoot').value = romData.romRoots[romData.romRoots.length - 1];
    renderQueue();
  }
  if (!romData.systems.some((s) => s.id === romSys)) romSys = romData.systems[0]?.id || '';
  renderRoms();
}

async function loadMeta(id) {
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

const sysIcon = (id, cls = 'sysicon') => id === '_unassigned'
  ? `<span class="${cls} noicon">?</span>`
  : `<img class="${cls}" alt="" loading="lazy" src="${iconUrl(id)}" onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'${cls} noicon',textContent:'${id.slice(0, 2).toUpperCase()}'}))">`;

function romCard(r, sysId) {
  const m = (metaCache[sysId] || {})[r.path] || {};
  const title = m.title || r.name.replace(/\s*[([].*$/, '') || r.name;
  const line = [m.year, m.developer || m.publisher].filter(Boolean).join(' · ');
  const src = coverOf(sysId, r, m);
  const cover = src
    ? `<img class="cover" loading="lazy" alt="${esc(title)}" src="${src}" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'cover nocover',textContent:this.alt}))">`
    : `<div class="cover nocover">${sysIcon(sysId, 'bigicon')}<span>${esc(title)}</span></div>`;
  return `<div class="romcard" data-rom="${esc(r.path)}" data-sys="${esc(sysId)}" title="${esc(r.file)}">
    ${cover}
    <div class="rtitle">${m.favorite ? '<span class="fav">★</span> ' : ''}${esc(title)}</div>
    <div class="rmeta">${esc(line) || '&nbsp;'}</div>
    <div class="rbadges">${m.region ? `<span class="badge">${esc(m.region)}</span>` : ''}<span class="badge">${esc(r.ext)}</span><span class="muted">${fmt(r.size)}</span></div>
  </div>`;
}

function renderRoms() {
  if (!romData) return;
  renderStats();
  $('#sysList').innerHTML = romData.systems.map((s) =>
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
    if (!s) html = '<p class="muted">Keine ROMs gefunden. Lege sie z. B. unter ROMs/&lt;konsole&gt; ab.</p>';
    else {
      loadMeta(s.id);
      const list = s.roms.filter((r) => match(r, s.id));
      html = `<div class="romhead">${sysIcon(s.id, 'headicon')}<h2>${esc(s.name)}</h2><span class="muted">${s.count} Spiele · ${fmt(s.size)}</span></div>` +
        (s.id === '_unassigned' ? '<p class="muted">Diese Dateien liegen nicht in einem Konsolen-Ordner und die Endung passt zu mehreren Konsolen. Öffne eine, um sie einer Konsole zuzuordnen.</p>' : '') +
        `<div class="covergrid">${list.map((r) => romCard(r, s.id)).join('')}</div>`;
    }
  }
  $('#romList').innerHTML = html;
}

function openRom(path, sysId) {
  const sys = romData.systems.find((s) => s.id === sysId);
  const r = sys.roms.find((x) => x.path === path);
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
        <h2>${esc(m.title || r.name.replace(/\s*[([].*$/, ''))}</h2>
        <table class="meta">
          ${row('Jahr', m.year)}${row('Entwickler', m.developer)}${row('Publisher', m.publisher)}
          ${row('Genre', m.genre)}${row('Spieler', m.players)}${row('Bewertung', m.rating ? m.rating + ' / 10' : '')}
          ${row('Gespielt', m.playtime ? fmtTime(m.playtime) + (m.playcount ? ` · ${m.playcount}×` : '') : '')}
          ${row('Region', m.region)}${row('Tags', tags)}${row('Quelle', m.source === 'cocoon' ? 'Cocoon' : (m.matched || m.cover ? 'libretro' : ''))}
          ${row('Title-ID', m.titleId)}${row('Erkannt als', m.matched)}
          ${row('Datei', r.file)}${row('Größe', fmt(r.size))}${row('Ort', volLabel(r.path) + ' ' + folder.replace(r.volume, ''))}
        </table>
        ${m.desc ? `<p class="desc">${esc(m.desc)}</p>` : ''}
        ${shots.length ? `<div class="shots">${shots.map((k) => `<img alt="" loading="lazy" src="${mediaUrl(sysId, r.path, k)}">`).join('')}</div>` : ''}
        <div class="row">
          <button data-dir-roms="${esc(folder)}">Im Datei-Tab öffnen</button>
          ${r.dir ? '' : `<a class="btn" href="/api/files/raw?${q({ path: r.path, token })}">Herunterladen</a>`}
          <button data-romdel="${esc(r.path)}">Löschen</button>
          <span class="grow"></span>
          <button data-close>Schließen</button>
        </div>
        ${sysId === '_unassigned' ? `<div class="row"><select data-assign-sel="${esc(r.path)}"><option value="">Konsole wählen …</option>${romData.systemIds.map((s) => `<option value="${s.id}">${esc(s.name)}</option>`).join('')}</select><button data-assign="${esc(r.path)}" class="primary">In Konsolen-Ordner verschieben</button></div>` : ''}
      </div>
    </div>`;
  $('#romDetail').showModal();
}

$('#romFilter').oninput = renderRoms;
$('#romRegion').onchange = renderRoms;
$('#romsRescan').onclick = () => loadRoms(true);
$('#sysList').onclick = (e) => {
  const b = e.target.closest('[data-sys-pick]');
  if (!b) return;
  romSys = b.dataset.sysPick; store.set('romSys', romSys);
  $('#romFilter').value = '';
  renderRoms();
};
$('#romList').onclick = (e) => {
  const c = e.target.closest('[data-rom]');
  if (c) openRom(c.dataset.rom, c.dataset.sys);
};
$('#romDetail').onclick = async (e) => {
  const t = e.target, dlg = $('#romDetail');
  if (t === dlg || t.dataset.close !== undefined) return dlg.close();
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
    const install = !e.dir && ext(e.name) === 'apk' ? `<button data-install="${esc(full)}">Installieren</button> ` : '';
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
async function start() {
  if (!token) return showLogin();
  try { await loadDevice(); showTab(store.get('tab', 'upload')); if (!romData) loadRoms(false); }
  catch (err) { if (!(err instanceof AuthError)) $('#conn').textContent = 'Thor nicht erreichbar'; }
}
window.addEventListener('beforeunload', (e) => { if (running) e.preventDefault(); });
start();
