'use strict';

/* Shared Notes: browser side.
   The browser decides nothing about locking. It asks the Java server, shows the answer,
   and refreshes when the server pushes a change. */

const API = (window.API_BASE || '').replace(/\/+$/, '');
const NAME_RE = /^[\p{L}\p{N} _.-]{1,20}$/u;
const HEARTBEAT_MS = 15000;

const $ = (sel, root = document) => root.querySelector(sel);
const docEl = $('#doc');
const cards = new Map();            // section id -> { root, ...elements }
const state = { user: null, status: null, editing: null, docSeq: 0, statusSeq: 0, heartbeat: null };

/* ---------------------------------------------------------------- helpers */
function toast(msg, ms = 3800) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), ms);
}

async function api(path, params = {}, method = 'POST') {
  const body = new URLSearchParams({ user: state.user, ...params });
  try {
    const res = method === 'POST'
      ? await fetch(`${API}/api/${path}`, { method, body })
      : await fetch(`${API}/api/${path}?${body}`);
    const data = await res.json().catch(() => ({}));
    return { status: res.status, body: data };
  } catch (err) {
    toast('Cannot reach the server. Check your connection and try again.');
    return { status: 0, body: {} };
  }
}

async function getJson(path) {
  const res = await fetch(`${API}/api/${path}`, { cache: 'no-store' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/* ------------------------------------------------------------------ name */
function askName() {
  const dlg = $('#nameDialog');
  const input = $('#nameInput');
  input.value = state.user || ('Guest' + Math.floor(Math.random() * 90 + 10));
  $('#nameError').hidden = true;
  if (!dlg.open) dlg.showModal();
  input.select();
}

$('#nameForm').addEventListener('submit', (e) => {
  const name = $('#nameInput').value.trim();
  if (!NAME_RE.test(name)) {
    e.preventDefault();
    $('#nameError').hidden = false;
    return;
  }
  state.user = name;
  sessionStorage.setItem('sharedNotesUser', name);
  $('#nameBtn').textContent = name;
  refreshAll();
});

$('#nameDialog').addEventListener('cancel', (e) => { if (!state.user) e.preventDefault(); });

$('#nameBtn').addEventListener('click', () => {
  if (state.editing) return toast('Save or cancel your edit before changing your name.');
  askName();
});

/* ------------------------------------------------------------ build cards */
const LOCK_SVG = '<svg class="lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>';

function makeCard(s) {
  const root = document.createElement('article');
  root.className = 'card';
  root.dataset.id = s.id;
  root.innerHTML = `
    <div class="rail" aria-hidden="true"><div class="dots"></div>${LOCK_SVG}</div>
    <div class="content">
      <div class="card-head"><h2></h2><span class="ver"></span></div>
      <p class="state" aria-live="polite"></p>
      <p class="text"></p>
      <div class="edit" hidden>
        <textarea maxlength="5000" spellcheck="true"></textarea>
        <p class="count"></p>
      </div>
      <div class="actions">
        <button type="button" class="btn edit-btn">Edit</button>
        <button type="button" class="btn save-btn" hidden>Save</button>
        <button type="button" class="btn secondary cancel-btn" hidden>Cancel</button>
      </div>
    </div>`;
  const c = {
    id: s.id, root,
    title: $('h2', root), ver: $('.ver', root), stateEl: $('.state', root), text: $('.text', root),
    dots: $('.dots', root), edit: $('.edit', root), area: $('textarea', root), count: $('.count', root),
    editBtn: $('.edit-btn', root), saveBtn: $('.save-btn', root), cancelBtn: $('.cancel-btn', root),
    version: 0
  };
  c.title.textContent = s.title;
  c.editBtn.addEventListener('click', () => startEdit(c));
  c.saveBtn.addEventListener('click', () => save(c));
  c.cancelBtn.addEventListener('click', () => cancel(c));
  c.area.addEventListener('input', () => { c.count.textContent = `${c.area.value.length} / 5000`; });
  c.area.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.preventDefault(); cancel(c); }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(c); }
  });
  cards.set(s.id, c);
  docEl.appendChild(root);
  return c;
}

/* ------------------------------------------------------------- load data */
async function loadDoc() {
  const seq = ++state.docSeq;
  try {
    const data = await getJson('doc');
    if (seq < state.docSeq) return;            // a newer answer is already on screen
    $('#loading')?.remove();
    for (const s of data.sections) {
      const c = cards.get(s.id) || makeCard(s);
      c.title.textContent = s.title;
      c.version = s.version;
      c.ver.textContent = `version ${s.version}`;
      if (!(state.editing && state.editing.id === s.id)) c.text.textContent = s.text;
    }
    if (state.status) applyStatus(state.status);
  } catch (e) { /* the next push or poll will retry */ }
}

async function loadStatus() {
  const seq = ++state.statusSeq;
  try {
    const st = await getJson('status');
    if (seq < state.statusSeq) return;
    state.status = st;
    applyStatus(st);
  } catch (e) { /* ignore */ }
}

function refreshAll() { loadDoc(); loadStatus(); }

/* ----------------------------------------------------------- show status */
function applyStatus(st) {
  $('#online').textContent = st.online === 1 ? '1 tab connected' : `${st.online} tabs connected`;

  for (const s of st.sections) {
    const c = cards.get(s.id);
    if (!c) continue;
    const mine = s.editor && s.editor === state.user;
    c.root.dataset.claim = s.editor ? 'yes' : 'no';
    c.root.dataset.reading = s.readers > 0 ? 'yes' : 'no';
    c.root.dataset.writing = s.writerActive ? 'yes' : 'no';

    c.dots.replaceChildren(...Array.from({ length: Math.min(s.readers, 6) }, () => {
      const d = document.createElement('span');
      d.className = 'dot';
      return d;
    }));

    const parts = [];
    if (s.writerActive) parts.push('<strong>Saving now.</strong> Readers wait until it finishes');
    else if (s.editor) parts.push(mine ? '<strong>You are editing this section</strong>' : `<strong>${esc(s.editor)}</strong> is editing this section`);
    if (s.readers > 0) parts.push(`${s.readers} ${s.readers === 1 ? 'person' : 'people'} reading`);
    if (s.waitingWriters > 0) parts.push('a save is waiting for readers to leave');
    c.stateEl.innerHTML = parts.length ? parts.join(' · ') : 'Free. Anyone can read or claim it.';
  }

  const cfg = st.config;
  syncSlider('#readHold', '#readHoldOut', cfg.readHoldMs);
  syncSlider('#writeHold', '#writeHoldOut', cfg.writeHoldMs);

  const log = $('#log');
  if (!st.log.length) {
    log.innerHTML = '<li class="muted">Nothing yet.</li>';
  } else {
    log.replaceChildren(...st.log.map((e) => {
      const li = document.createElement('li');
      const t = document.createElement('time');
      t.textContent = new Date(e.t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      li.append(t, document.createTextNode(e.msg));
      return li;
    }));
  }
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

/* ----------------------------------------------------------- edit flow */
async function startEdit(c) {
  if (state.editing) return toast('Finish or cancel your current edit first.');
  c.editBtn.disabled = true;
  const r = await api('claim', { section: c.id });
  c.editBtn.disabled = false;
  if (r.status === 409) return toast(`${r.body.holder} is already editing this section. You can keep reading it.`);
  if (r.status !== 200) return toast('Could not claim the section. Try again.');

  state.editing = c;
  c.area.value = c.text.textContent;
  c.count.textContent = `${c.area.value.length} / 5000`;
  setEditUi(c, true);
  c.area.focus();
  state.heartbeat = setInterval(async () => {
    const hb = await api('renew', { section: c.id });
    if (hb.status === 403) toast('Your claim expired. Press Save to try to claim it again, or copy your text.');
  }, HEARTBEAT_MS);
}

function setEditUi(c, editing) {
  c.edit.hidden = !editing;
  c.text.hidden = editing;
  c.editBtn.hidden = editing;
  c.saveBtn.hidden = !editing;
  c.cancelBtn.hidden = !editing;
}

function stopEditing(c) {
  clearInterval(state.heartbeat);
  state.heartbeat = null;
  state.editing = null;
  setEditUi(c, false);
}

async function cancel(c) {
  await api('release', { section: c.id });
  stopEditing(c);
  refreshAll();
}

async function save(c) {
  c.saveBtn.disabled = true;
  try {
    // Claiming again is harmless if we still hold it, and recovers a lapsed lease if nobody took it.
    const re = await api('claim', { section: c.id });
    if (re.status === 409) return toast(`${re.body.holder} claimed this section first. Your text is still in the box.`);
    if (re.status !== 200) return;

    const r = await api('commit', { section: c.id, text: c.area.value });
    if (r.status === 200) {
      c.text.textContent = c.area.value;
      stopEditing(c);
      toast(r.body.waitedMs > 50 ? `Saved as version ${r.body.version} after waiting ${r.body.waitedMs} ms for readers.` : `Saved as version ${r.body.version}.`);
      refreshAll();
    } else if (r.status === 503) {
      toast('Readers are still inside this section. Press Save again in a moment.');
    } else if (r.status === 413) {
      toast('That text is longer than 5000 characters. Shorten it and save again.');
    } else if (r.status === 403) {
      toast('You no longer hold this section. Copy your text, then claim it again.');
    }
  } finally {
    c.saveBtn.disabled = false;
  }
}

window.addEventListener('pagehide', () => {
  if (state.editing && state.user) {
    navigator.sendBeacon(`${API}/api/release`, new URLSearchParams({ user: state.user, section: state.editing.id }));
  }
});

/* ---------------------------------------------------------- demo controls */
function syncSlider(inputSel, outSel, value) {
  const input = $(inputSel);
  $(outSel).textContent = `${value} ms`;
  if (document.activeElement !== input) input.value = value;
}

function wireSlider(inputSel, outSel) {
  const input = $(inputSel);
  input.addEventListener('input', () => { $(outSel).textContent = `${input.value} ms`; });
  input.addEventListener('change', async () => {
    const r = await api('config', { readHoldMs: $('#readHold').value, writeHoldMs: $('#writeHold').value });
    if (r.status !== 200) toast('Could not change the setting.');
  });
}
wireSlider('#readHold', '#readHoldOut');
wireSlider('#writeHold', '#writeHoldOut');

$('#resetBtn').addEventListener('click', async () => {
  if (!confirm('Reset every section to its original text? This affects everyone.')) return;
  if (state.editing) cancel(state.editing);
  const r = await api('reset');
  toast(r.status === 200 ? (r.body.skipped ? 'Reset, but some sections were busy.' : 'Document reset.') : 'Could not reset the document.');
});

/* ------------------------------------------------------------- live link */
function connect() {
  const es = new EventSource(`${API}/api/events`);
  const conn = $('#conn');
  es.onopen = () => { conn.dataset.state = 'live'; conn.textContent = 'Live'; refreshAll(); };
  es.onerror = () => { conn.dataset.state = 'reconnecting'; conn.textContent = 'Reconnecting'; };
  es.addEventListener('doc', loadDoc);
  es.addEventListener('status', loadStatus);
}

/* ----------------------------------------------------------------- start */
state.user = sessionStorage.getItem('sharedNotesUser');
if (state.user) $('#nameBtn').textContent = state.user; else askName();
connect();
refreshAll();
setInterval(loadStatus, 2000);      // safety net if a push is missed
