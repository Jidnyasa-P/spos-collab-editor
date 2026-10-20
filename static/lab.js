'use strict';

/* Race Lab: starts a run on the server, then polls for events (works through any proxy). */
const { $, api, toast } = SN;
SN.renderHeader('lab', false);
document.addEventListener('sn:rename', () => SN.askName());
async function refreshPeople() {
  try { SN.setPeople((await SN.getJson('status')).people || []); } catch (e) { /* ignore */ }
  setTimeout(refreshPeople, 4000);
}
SN.hello();
refreshPeople();

const PACE = [
  { label: 'Very slow', ms: 400 }, { label: 'Slow', ms: 250 }, { label: 'Medium', ms: 140 },
  { label: 'Fast', ms: 80 }, { label: 'Very fast', ms: 40 }
];

const LANES = {
  unsafe: { title: 'Without a lock', sub: 'Everyone just goes ahead. Nothing stops them colliding.', metricClass: 'bad' },
  safe:   { title: 'With the reader-priority lock', sub: 'Writers take the write lock. Readers share the read lock.', metricClass: 'ok' }
};

const ui = { run: null, timer: null, lanes: {} };

/* ------------------------------------------------------------- build lanes */
function buildLane(tag) {
  const cfg = LANES[tag];
  const el = document.createElement('article');
  el.className = `lane ${tag}`;
  el.setAttribute('aria-labelledby', `h-${tag}`);
  el.innerHTML = `
    <div><h2 id="h-${tag}">${cfg.title}</h2><p class="sub">${cfg.sub}</p></div>
    <div class="bigcount"><span class="num" data-f="counter">0</span><span class="of" data-f="of">counter</span></div>
    <div class="progress" role="progressbar" aria-label="Increments finished" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><span data-f="bar"></span></div>
    <dl class="metrics">
      <div class="metric ${cfg.metricClass}"><dt>Updates lost</dt><dd data-f="lost">0</dd></div>
      <div class="metric ${cfg.metricClass}"><dt>Inconsistent reads</dt><dd data-f="inconsistent">0</dd></div>
      <div class="metric"><dt>Readers inside now</dt><dd data-f="readersInside">0</dd></div>
      <div class="metric"><dt>Writers waiting</dt><dd data-f="waiting">0</dd></div>
    </dl>
    <div class="actors">
      <h3>Writers</h3><div class="chips" data-f="writers"></div>
      <h3>Readers</h3><div class="chips" data-f="readers"></div>
    </div>
    <ol class="lab-log" data-f="log" aria-label="Events in this run"></ol>`;
  const f = {};
  el.querySelectorAll('[data-f]').forEach((n) => { f[n.dataset.f] = n; });
  ui.lanes[tag] = { el, f, actors: new Map(), logCount: 0 };
  return el;
}

function resetLane(tag, writers, readers, expected) {
  const l = ui.lanes[tag];
  l.actors.clear();
  l.logCount = 0;
  l.f.log.replaceChildren();
  for (const [kind, n, box] of [['Writer', writers, l.f.writers], ['Reader', readers, l.f.readers]]) {
    box.replaceChildren();
    for (let i = 1; i <= n; i++) {
      const chip = document.createElement('div');
      chip.className = 'actor';
      chip.dataset.state = 'idle';
      chip.innerHTML = `<b>${kind} ${i}</b><span>idle</span>`;
      box.append(chip);
      l.actors.set(`${kind} ${i}`, chip);
    }
  }
  setStats(tag, { counter: 0, opsDone: 0, expected, lost: 0, inconsistent: 0, readersInside: 0, waiting: 0 });
}

function setStats(tag, s) {
  const l = ui.lanes[tag];
  l.f.counter.textContent = s.counter;
  l.f.of.textContent = `counter, ${s.expected} expected`;
  const pct = Math.round((s.opsDone / Math.max(1, s.expected)) * 100);
  l.f.bar.style.width = pct + '%';
  l.f.bar.parentElement.setAttribute('aria-valuenow', pct);
  l.f.lost.textContent = s.lost;
  l.f.lost.classList.toggle('nonzero', s.lost > 0);
  l.f.inconsistent.textContent = s.inconsistent;
  l.f.inconsistent.classList.toggle('nonzero', s.inconsistent > 0);
  l.f.readersInside.textContent = s.readersInside;
  l.f.waiting.textContent = s.waiting;
}

function setActor(tag, who, state) {
  const chip = ui.lanes[tag].actors.get(who);
  if (!chip) return;
  chip.dataset.state = state;
  chip.querySelector('span').textContent = state;
}

function addLog(tag, kind, text) {
  const l = ui.lanes[tag];
  const li = document.createElement('li');
  li.className = kind;
  li.textContent = text;
  l.f.log.prepend(li);
  if (++l.logCount > 60) l.f.log.lastChild.remove();
}

/* ------------------------------------------------------------------- run */
async function start() {
  stopPolling();
  $('#verdict').hidden = true;
  const params = {
    writers: $('#writers').value, readers: $('#readers').value, ops: $('#ops').value,
    step: PACE[$('#pace').value - 1].ms, busy: $('#busy').checked ? '1' : '0'
  };
  $('#run').disabled = true;
  const r = await api('lab/start', params);
  if (r.status === 429) { $('#run').disabled = false; return toast('Several demos are running already. Wait a moment and try again.', 'error'); }
  if (r.status !== 200) { $('#run').disabled = false; return toast('Could not start the demo.', 'error'); }

  const p = r.body;
  ui.run = { id: p.id, next: 0, expected: p.expected };
  for (const tag of ['unsafe', 'safe']) resetLane(tag, p.writers, p.readers, p.expected);
  $('#stop').disabled = false;
  $('#runInfo').textContent = `${p.writers} writers x ${p.ops} increments = ${p.expected} expected${p.busy ? ', busy readers' : ''}`;
  poll();
}

async function poll() {
  if (!ui.run) return;
  const r = await api('lab/poll', { id: ui.run.id, since: ui.run.next }, 'GET');
  if (!ui.run) return;
  if (r.status !== 200) { finish(); return toast('The demo run was lost. Press Run both again.', 'error'); }

  const d = r.body;
  ui.run.next = d.next;
  for (const e of d.events) {
    if (e.k === 'actor') setActor(e.s, e.who, e.state);
    else if (e.k === 'log') addLog(e.s, e.kind, e.text);
  }
  for (const tag of ['unsafe', 'safe']) if (d.stats[tag]) setStats(tag, d.stats[tag]);

  if (d.done) {
    finish();
    showVerdict(d.results.unsafe, d.results.safe);
  } else {
    ui.timer = setTimeout(poll, 150);
  }
}

function stopPolling() { clearTimeout(ui.timer); ui.timer = null; }

function finish() {
  stopPolling();
  ui.run = null;
  $('#run').disabled = false;
  $('#stop').disabled = true;
}

async function stop() {
  if (ui.run) await api('lab/stop', { id: ui.run.id });
}

/* --------------------------------------------------------------- verdict */
function showVerdict(no, yes) {
  if (!no || !yes) return;
  const secs = (ms) => (ms / 1000).toFixed(1) + ' s';
  const noText = no.lost > 0
    ? `The counter ended at <b>${no.counter}</b> instead of <b>${no.expected}</b>. <b>${no.lost}</b> increment${no.lost === 1 ? '' : 's'} vanished without any error, and readers saw half-finished data <b>${no.inconsistent}</b> time${no.inconsistent === 1 ? '' : 's'}.`
    : `This run got lucky: nothing collided. Collisions are random, so run it again (more writers or a faster pace makes them likelier).`;
  const yesText = `The counter ended at <b>${yes.counter}</b> of <b>${yes.expected}</b>. <b>${yes.lost}</b> lost, <b>${yes.inconsistent}</b> inconsistent reads. Up to <b>${yes.maxReaders}</b> reader${yes.maxReaders === 1 ? ' was' : 's were'} inside at once. It took ${secs(yes.elapsedMs)} instead of ${secs(no.elapsedMs)}: writers take turns, and that is the price of being correct.`;
  const v = $('#verdict');
  v.innerHTML = `<h2>What the numbers say</h2>
    <div class="verdict-grid">
      <div class="no"><strong>Without a lock</strong><p>${noText}</p></div>
      <div class="yes"><strong>With the reader-priority lock</strong><p>${yesText}</p></div>
    </div>`;
  v.hidden = false;
  v.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'nearest' });
}

/* ------------------------------------------------------------ controls */
function bindOutput(id, fmt = (v) => v) {
  const input = $('#' + id), out = $('#' + id + 'Out');
  const update = () => { out.textContent = fmt(input.value); };
  input.addEventListener('input', update);
  update();
}
bindOutput('writers'); bindOutput('readers'); bindOutput('ops');
bindOutput('pace', (v) => PACE[v - 1].label);

$('#run').addEventListener('click', start);
$('#stop').addEventListener('click', stop);

const duel = $('#duel');
duel.append(buildLane('unsafe'), buildLane('safe'));
resetLane('unsafe', 4, 2, 20);
resetLane('safe', 4, 2, 20);
