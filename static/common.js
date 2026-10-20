'use strict';

/* Shared by every page: name, avatars, header, toasts and small API helpers. */
const SN = (() => {
  const API = (window.API_BASE || '').replace(/\/+$/, '');
  const NAME_RE = /^[\p{L}\p{N} _.-]{1,20}$/u;
  const $ = (sel, root = document) => root.querySelector(sel);
  const state = { user: sessionStorage.getItem('sharedNotesUser') };

  /* ------------------------------------------------------------ avatars */
  const HUES = [205, 340, 28, 150, 268, 48, 8, 312];   // distinct, all readable with white text
  function hue(name) {
    let h = 7;
    for (const ch of name) h = (h * 31 + ch.codePointAt(0)) >>> 0;
    return HUES[h % HUES.length];
  }

  function avatar(name, extraClass = '') {
    const el = document.createElement('span');
    el.className = ('avatar ' + extraClass).trim();
    el.textContent = (name.trim()[0] || '?').toUpperCase();
    el.style.setProperty('--h', hue(name));
    el.title = name;
    return el;
  }

  /* -------------------------------------------------------------- toasts */
  function toast(msg, type = 'info', ms = 4200) {
    const t = $('#toast');
    t.textContent = msg;
    t.dataset.type = type;
    t.classList.add('show');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('show'), ms);
  }

  /* ---------------------------------------------------------------- API */
  async function api(path, params = {}, method = 'POST') {
    const body = new URLSearchParams({ user: state.user || '', ...params });
    try {
      const res = method === 'POST'
        ? await fetch(`${API}/api/${path}`, { method, body })
        : await fetch(`${API}/api/${path}?${body}`, { cache: 'no-store' });
      const data = await res.json().catch(() => ({}));
      return { status: res.status, body: data };
    } catch (err) {
      toast('Cannot reach the server. Check that it is running, then try again.', 'error');
      return { status: 0, body: {} };
    }
  }

  async function getJson(path) {
    const res = await fetch(`${API}/api/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  /* ------------------------------------------------------------- header */
  const LOGO = '<svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="#16222c"/><circle cx="10.5" cy="11" r="3.3" fill="#4cc3cf"/><circle cx="21.5" cy="11" r="3.3" fill="#4cc3cf"/><rect x="7.5" y="18.5" width="17" height="5.5" rx="2.7" fill="#f0a05a"/></svg>';

  function renderHeader(active, withConnection) {
    const top = $('#top');
    top.className = 'topbar';
    top.innerHTML = `
      <a class="brand" href="index.html">${LOGO}
        <span><strong>Shared Notes</strong><small>Everyone reads. One person edits each section.</small></span></a>
      <nav class="nav" aria-label="Pages">
        <a href="index.html" ${active === 'editor' ? 'aria-current="page"' : ''}>Editor</a>
        <a href="lab.html" ${active === 'lab' ? 'aria-current="page"' : ''}>Race Lab</a>
      </nav>
      <div class="who">
        ${withConnection ? '<span id="conn" class="conn" data-state="connecting">Connecting</span>' : ''}
        <div id="people" class="people" role="group" aria-label="People here now"></div>
        <button id="nameBtn" class="chip" type="button" title="Change your name"></button>
      </div>`;
    $('#nameBtn').addEventListener('click', () => document.dispatchEvent(new CustomEvent('sn:rename')));
    updateNameChip();
  }

  function updateNameChip() {
    const b = $('#nameBtn');
    if (!b) return;
    b.replaceChildren();
    if (state.user) b.append(avatar(state.user), document.createTextNode(state.user));
    else b.textContent = 'Set your name';
  }

  function setConn(kind) {
    const c = $('#conn');
    if (!c) return;
    c.dataset.state = kind;
    c.textContent = kind === 'live' ? 'Live' : kind === 'reconnecting' ? 'Reconnecting' : 'Connecting';
  }

  /** Shows who is online in the header and (if present) in the side panel. */
  function setPeople(names) {
    const p = $('#people');
    if (p) {
      p.replaceChildren(...names.slice(0, 5).map((n) => avatar(n, n === state.user ? 'me' : '')));
      if (names.length > 5) {
        const more = document.createElement('span');
        more.className = 'avatar more';
        more.textContent = `+${names.length - 5}`;
        p.append(more);
      }
      p.title = names.length ? `Here now: ${names.join(', ')}` : '';
    }
    const list = $('#peopleList');
    if (list) {
      if (!names.length) { list.innerHTML = '<li class="muted">Nobody yet.</li>'; return; }
      list.replaceChildren(...names.map((n) => {
        const li = document.createElement('li');
        const label = document.createElement('span');
        label.textContent = n === state.user ? `${n} (you)` : n;
        li.append(avatar(n), label);
        return li;
      }));
    }
  }

  /* ---------------------------------------------------------- name dialog */
  function buildNameDialog() {
    if ($('#nameDialog')) return;
    const d = document.createElement('dialog');
    d.id = 'nameDialog';
    d.setAttribute('aria-labelledby', 'nameTitle');
    d.innerHTML = `
      <form method="dialog" id="nameForm">
        <div class="dlg-head"><span id="namePreview"></span><h2 id="nameTitle">What should we call you?</h2></div>
        <p class="hint">Other people see this name next to the section you are editing.</p>
        <label for="nameInput">Your name</label>
        <input id="nameInput" maxlength="20" autocomplete="off" required>
        <p id="nameError" class="error" role="alert" hidden>Use 1 to 20 letters, numbers, spaces, dots, dashes or underscores.</p>
        <button class="btn" type="submit">Start</button>
      </form>`;
    document.body.append(d);
    d.addEventListener('cancel', (e) => { if (!state.user) e.preventDefault(); });
    $('#nameInput').addEventListener('input', previewName);
    $('#nameForm').addEventListener('submit', (e) => {
      const name = $('#nameInput').value.trim();
      if (!NAME_RE.test(name)) { e.preventDefault(); $('#nameError').hidden = false; return; }
      state.user = name;
      sessionStorage.setItem('sharedNotesUser', name);
      updateNameChip();
      hello();
      document.dispatchEvent(new CustomEvent('sn:user', { detail: name }));
    });
  }

  function previewName() {
    const v = $('#nameInput').value.trim();
    $('#namePreview').replaceChildren(avatar(v || '?', 'big'));
  }

  function askName() {
    buildNameDialog();
    const input = $('#nameInput');
    input.value = state.user || ('Guest' + Math.floor(Math.random() * 90 + 10));
    $('#nameError').hidden = true;
    previewName();
    const d = $('#nameDialog');
    if (!d.open) d.showModal();
    input.select();
  }

  /* -------------------------------------------------------------- presence */
  function hello() { if (state.user) api('hello'); }
  setInterval(hello, 5000);

  return { API, $, state, avatar, toast, api, getJson, renderHeader, setConn, setPeople, askName, hello, NAME_RE };
})();
