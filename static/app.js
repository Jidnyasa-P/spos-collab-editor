'use strict';

/* Editor page. The browser decides nothing about locking: it asks the Java server,
   shows the answer, and refreshes when the server pushes a change (or when polling notices one). */

const { $, state, api, getJson, toast, avatar } = SN;
const HEARTBEAT_MS = 15000;
const docEl = $('#doc');
const cards = new Map();            // section id -> card parts
const ui = { editing: null, docSeq: 0, statusSeq: 0, heartbeat: null, live: false, status: null };

SN.renderHeader('editor', true);

/* ---------------------------------------------------------------- icons */
const ICON = {
  lock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>',
  pencil: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>'
};

function esc(s) {
  return String(s).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

/* ----------------------------------------------------------------- cards */
function makeCard(s) {
  const root = document.createElement('article');
  root.className = 'card';
  root.dataset.id = s.id;
  root.innerHTML = `
    <div class="rail" aria-hidden="true"><div class="dots"></div>${ICON.lock.replace('<svg', '<svg class="lock"')}</div>
    <div class="content">
      <div class="card-head"><h2></h2><span class="ver"></span></div>
      <p class="state" aria-live="polite"></p>
      <p class="text"></p>
      <div class="edit" hidden>
        <textarea maxlength="5000" spellcheck="true"></textarea>
        <div class="edit-foot"><span class="keys">Ctrl+Enter saves. Esc cancels.</span><span class="count"></span></div>
      </div>
      <div class="actions">
        <button type="button" class="btn edit-btn">${ICON.pencil}<span>Edit</span></button>
        <button type="button" class="btn save-btn" hidden>Save</button>
        <button type="button" class="btn secondary cancel-btn" hidden>Cancel</button>
      </div>
    </div>`;
  const c = {
    id: s.id, root, version: -1, title: s.title,
    h2: $('h2', root), ver: $('.ver', root), stateEl: $('.state', root), text: $('.text', root),
    dots: $('.dots', root), edit: $('.edit', root), area: $('textarea', root), count: $('.count', root),
    editBtn: $('.edit-btn', root), saveBtn: $('.save-btn', root), cancelBtn: $('.cancel-btn', root)
  };
  c.h2.textContent = s.title;
  c.area.setAttribute('aria-label', `Text of the section "${s.title}"`);
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
  const seq = ++ui.docSeq;
  try {
    const data = await getJson('doc');
    if (seq < ui.docSeq) return;                 // a newer answer is already on screen
    if (docEl.getAttribute('aria-busy') === 'true') {
      docEl.replaceChildren();
      docEl.setAttribute('aria-busy', 'false');
    }
    for (const s of data.sections) {
      const c = cards.get(s.id) || makeCard(s);
      c.title = s.title;
      c.h2.textContent = s.title;
      c.version = s.version;
      c.ver.textContent = `version ${s.version}`;
      if (!(ui.editing && ui.editing.id === s.id)) c.text.textContent = s.text;
    }
    if (ui.status) applyStatus(ui.status, false);
  } catch (e) { /* the next push or poll retries */ }
}

async function loadStatus() {
  const seq = ++ui.statusSeq;
  try {
    const st = await getJson('status');
    if (seq < ui.statusSeq) return;
    ui.status = st;
    applyStatus(st, true);
  } catch (e) { /* ignore */ }
}

function refreshAll() { loadDoc(); loadStatus(); }

/* ----------------------------------------------------------- show status */
function applyStatus(st, mayReload) {
  SN.setPeople(st.people || []);
  let stale = false;

  for (const s of st.sections) {
    const c = cards.get(s.id);
    if (!c) continue;
    if (mayReload && c.version !== s.version && !(ui.editing && ui.editing.id === s.id)) stale = true;

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
    else if (s.editor) parts.push(mine ? '<strong>You are editing this section</strong>' : `<span class="who-line" data-who="${esc(s.editor)}"></span> is editing this section`);
    if (s.readers > 0) parts.push(`${s.readers} ${s.readers === 1 ? 'person' : 'people'} reading`);
    if (s.waitingWriters > 0) parts.push('a save is waiting for readers to leave');
    const stateKey = parts.join('|');
    if (c.stateKey !== stateKey) {                 // only touch the DOM when something changed
      c.stateKey = stateKey;
      c.stateEl.innerHTML = parts.length ? parts.join(' · ') : 'Free. Anyone can read it or claim it.';
      const who = $('.who-line', c.stateEl);
      if (who) who.append(avatar(who.dataset.who, 'small'), Object.assign(document.createElement('strong'), { textContent: who.dataset.who }));
    }

    // The button tells the truth about the lock; the server still has the final say.
    const lockedByOther = s.editor && !mine && !(ui.editing && ui.editing.id === s.id);
    c.editBtn.classList.toggle('locked', !!lockedByOther);
    c.editBtn.setAttribute('aria-disabled', lockedByOther ? 'true' : 'false');
    const btnHtml = lockedByOther ? `${ICON.lock}<span>Locked by ${esc(s.editor)}</span>` : `${ICON.pencil}<span>Edit</span>`;
    if (c.btnHtml !== btnHtml) { c.btnHtml = btnHtml; c.editBtn.innerHTML = btnHtml; }
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
      t.dateTime = new Date(e.t).toISOString();
      t.textContent = new Date(e.t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      li.append(t, document.createTextNode(e.msg));
      return li;
    }));
  }

  if (stale) loadDoc();            // works even when the live stream is unavailable
}

/* ------------------------------------------------------------- edit flow */
async function startEdit(c) {
  if (ui.editing) return toast('Finish or cancel your current edit first.');
  c.editBtn.disabled = true;
  const r = await api('claim', { section: c.id });
  c.editBtn.disabled = false;
  if (r.status === 409) { loadStatus(); return toast(`${r.body.holder} is editing this section. You can keep reading it.`); }
  if (r.status !== 200) return toast('Could not claim the section. Try again.', 'error');

  ui.editing = c;
  c.area.value = c.text.textContent;
  c.count.textContent = `${c.area.value.length} / 5000`;
  setEditUi(c, true);
  c.area.focus();
  ui.heartbeat = setInterval(async () => {
    const hb = await api('renew', { section: c.id });
    if (hb.status === 403) toast('Your claim expired. Press Save to try to claim it again, or copy your text.', 'error');
  }, HEARTBEAT_MS);
}

function setEditUi(c, editing) {
  c.edit.hidden = !editing;
  c.text.hidden = editing;
  c.editBtn.hidden = editing;
  c.saveBtn.hidden = !editing;
  c.cancelBtn.hidden = !editing;
  c.root.classList.toggle('is-editing', editing);
}

function stopEditing(c) {
  clearInterval(ui.heartbeat);
  ui.heartbeat = null;
  ui.editing = null;
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
    // Claiming again is harmless if we still hold it, and it recovers a lapsed lease if nobody took it.
    const re = await api('claim', { section: c.id });
    if (re.status === 409) return toast(`${re.body.holder} claimed this section first. Your text is still in the box.`, 'error');
    if (re.status !== 200) return;

    const r = await api('commit', { section: c.id, text: c.area.value });
    if (r.status === 200) {
      c.text.textContent = c.area.value;
      stopEditing(c);
      toast(r.body.waitedMs > 50
        ? `Saved as version ${r.body.version} after waiting ${r.body.waitedMs} ms for readers.`
        : `Saved as version ${r.body.version}.`, 'success');
      refreshAll();
    } else if (r.status === 503) {
      toast('Readers are still inside this section. Press Save again in a moment.', 'error');
    } else if (r.status === 413) {
      toast('That text is longer than 5000 characters. Shorten it and save again.', 'error');
    } else if (r.status === 403) {
      toast('You no longer hold this section. Copy your text, then claim it again.', 'error');
    }
  } finally {
    c.saveBtn.disabled = false;
  }
}

window.addEventListener('pagehide', () => {
  if (ui.editing && state.user) {
    navigator.sendBeacon(`${SN.API}/api/release`, new URLSearchParams({ user: state.user, section: ui.editing.id }));
  }
});

/* ---------------------------------------------------------- demo controls */
function syncSlider(inputSel, outSel, value) {
  const input = $(inputSel);
  $(outSel).textContent = `${value} ms`;
  if (document.activeElement !== input) input.value = value;
}

async function sendConfig() {
  const r = await api('config', { readHoldMs: $('#readHold').value, writeHoldMs: $('#writeHold').value });
  if (r.status !== 200) toast('Could not change the setting.', 'error');
}

for (const [inputSel, outSel] of [['#readHold', '#readHoldOut'], ['#writeHold', '#writeHoldOut']]) {
  const input = $(inputSel);
  input.addEventListener('input', () => { $(outSel).textContent = `${input.value} ms`; });
  input.addEventListener('change', sendConfig);
}

document.querySelectorAll('[data-preset]').forEach((b) => b.addEventListener('click', () => {
  const [r, w] = b.dataset.preset.split(',');
  $('#readHold').value = r;
  $('#writeHold').value = w;
  $('#readHoldOut').textContent = `${r} ms`;
  $('#writeHoldOut').textContent = `${w} ms`;
  sendConfig();
}));

$('#resetBtn').addEventListener('click', async () => {
  if (!confirm('Reset every section to its original text? This affects everyone.')) return;
  if (ui.editing) await cancel(ui.editing);
  const r = await api('reset');
  toast(r.status === 200 ? (r.body.skipped ? 'Reset, but some sections were busy.' : 'Document reset.') : 'Could not reset the document.',
        r.status === 200 ? 'success' : 'error');
});

/* ---------------------------------------------------------------- welcome */
if (!localStorage.getItem('sharedNotesWelcome')) $('#welcome').hidden = false;
$('#welcomeClose').addEventListener('click', () => {
  $('#welcome').hidden = true;
  try { localStorage.setItem('sharedNotesWelcome', '1'); } catch (e) { /* private mode */ }
});

/* -------------------------------------------------------------- live link */
function connect() {
  if (!window.EventSource) return;
  const es = new EventSource(`${SN.API}/api/events`);
  es.onopen = () => { ui.live = true; SN.setConn('live'); refreshAll(); };
  es.onerror = () => { ui.live = false; SN.setConn('reconnecting'); };
  es.addEventListener('doc', loadDoc);
  es.addEventListener('status', loadStatus);
}

function pollLoop() {
  loadStatus();
  setTimeout(pollLoop, ui.live ? 3000 : 1000);   // faster when the live stream is not available
}

/* ------------------------------------------------------------------ start */
document.addEventListener('sn:rename', () => {
  if (ui.editing) return toast('Save or cancel your edit before changing your name.');
  SN.askName();
});
document.addEventListener('sn:user', refreshAll);

if (!state.user) SN.askName();
SN.hello();
connect();
loadDoc();
pollLoop();
