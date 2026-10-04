'use strict';
// openMCX dispatcher UI: state from /api/events, actions to /api/command, voice over /api/audio.
const $ = (id) => document.getElementById(id);
const RATE = 16000, FRAME = 320; // the engine's live audio: 16 kHz s16le, 20 ms frames

function log(text) {
  const li = document.createElement('li');
  li.textContent = new Date().toLocaleTimeString() + '  ' + text;
  $('log').prepend(li);
  while ($('log').children.length > 100) $('log').lastChild.remove();
}

async function post(url, body, json = true) {
  const res = await fetch(url, {
    method: 'POST',
    headers: json ? { 'Content-Type': 'application/json', 'X-Dispatcher': '1' } : { 'X-Dispatcher': '1' },
    body: json ? JSON.stringify(body) : body,
  });
  const reply = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(reply.error || res.statusText);
  log(reply.sent || 'done');
  return reply;
}
const act = (body) => post('/api/command', body).catch((e) => log('error: ' + e.message));

// ---- state ----
let state = {};
function text(el, value) { el.textContent = value; }
function item(list, title, detail, buttons = []) {
  const div = document.createElement('div');
  div.className = 'item';
  const b = document.createElement('div');
  b.textContent = title;
  div.append(b);
  if (detail) { const s = document.createElement('small'); s.textContent = detail; div.append(s); }
  if (buttons.length) {
    const row = document.createElement('div');
    row.className = 'row';
    for (const [label, cls, fn] of buttons) {
      const btn = typeof fn === 'string' ? document.createElement('a') : document.createElement('button');
      btn.textContent = label;
      if (cls) btn.className = cls;
      if (typeof fn === 'string') { btn.href = fn; btn.download = ''; } else btn.onclick = fn;
      row.append(btn);
    }
    div.append(row);
  }
  list.append(div);
}

// The sign-in screen (position mode): shown whenever this browser has no session.
function renderSession(session) {
  const out = !session.signed_in;
  $('signin').hidden = !out;
  $('signout').hidden = out || session.mode !== 'position';
  if (!out) return false;
  if (talking) pttUp();
  text($('signin-reason'), session.reason || '');
  text($('accounts'), (session.accounts || []).join(', ') || 'none');
  text($('signin-busy'), session.busy ? 'An operator is signed in on this position; they must sign out first ' +
    '(a session whose page was closed signs out by itself after a short while).' : '');
  return true;
}

// The group picker: the groups of the user profile, and the group in use.
let groupsShown = '';
function renderGroups(current, groups) {
  const list = [...new Set([...(groups || []), ...(current ? [current] : [])])];
  const key = JSON.stringify([list, current]);
  if (key === groupsShown) return;
  groupsShown = key;
  const select = $('group-select');
  select.replaceChildren(...list.map((g) => {
    const option = document.createElement('option');
    option.value = g;
    option.textContent = g + (groups && !groups.includes(g) ? ' (no longer in your profile)' : '');
    option.selected = g === current;
    return option;
  }));
  select.disabled = list.length < 2;
}

function render(s) {
  state = s;
  if (renderSession(s.session || {})) return;
  const id = s.identity || {};
  text($('identity'), (s.session.operator ? s.session.operator + ' · ' : '') + (id.user || '?') +
    (id.mcdata_user && id.mcdata_user !== id.user ? ' · ' + id.mcdata_user : ''));
  renderGroups(id.group, s.profile_groups);
  $('group-select').disabled = $('group-select').options.length < 2 || (s.call_state || 'idle') !== 'idle';
  const reg = $('registration');
  reg.textContent = s.registered ? (s.affiliated ? 'registered, affiliated' : 'registered') : 'offline';
  reg.classList.toggle('off', !s.registered);

  const call = s.call_state || 'idle';
  text($('call-state'), call + (s.call_type ? ' (' + s.call_type + (s.private_peer ? ' ' + s.private_peer : '') + ')' : ''));
  text($('floor-state'), (s.floor_state || '–') + (s.floor_owner ? ' · ' + s.floor_owner : '') +
    (s.queue_position ? ' · queue ' + s.queue_position : ''));
  const failure = s.last_call_failure;
  text($('call-failure'), failure && call === 'idle' ? (typeof failure === 'string' ? failure : JSON.stringify(failure)) : '');
  $('ptt').disabled = call !== 'active';
  // Someone else has the floor (Floor Taken, TS 24.380 clause 6.2.4.3.3): the button says so, as on the phones.
  const owner = s.floor_owner && s.floor_owner !== id.user ? s.floor_owner : '';
  const busy = call === 'active' && !talking && !!owner && s.floor_state !== 'HasPermission';
  $('ptt').classList.toggle('busy', busy);
  if (!talking) $('ptt').textContent = busy ? 'Floor busy · ' + owner.replace(/^sips?:/, '').split('@')[0].replace(/\.mcptt$/, '') + ' is talking' : 'Hold to talk';
  $('incoming').hidden = call !== 'ringing';
  text($('incoming-text'), 'Incoming ' + (s.call_type || '') + ' call' + (s.private_peer ? ' from ' + s.private_peer : ''));

  const alerts = $('alerts');
  alerts.replaceChildren();
  for (const [who, on] of Object.entries(s.alerts || {})) if (on) item(alerts, 'Emergency alert: ' + who);

  const inbox = $('inbox');
  inbox.replaceChildren();
  for (const m of [...(s.mcdata_inbox || [])].reverse()) {
    const words = (m.payloads || []).map((p) => p.text ?? `[${p.bytes} bytes]`).join(' ');
    item(inbox, words, 'from ' + (m.from || '?') + (m.group ? ' in ' + m.group : ''));
  }
  const files = $('files');
  files.replaceChildren();
  for (const f of [...(s.mcdata_files || [])].reverse()) {
    const buttons = [];
    if (['offered', 'deferred'].includes(f.state))
      buttons.push(['Accept', 'go', () => act({ action: 'fd-accept', id: f.message_id })],
                   ['Reject', 'stop', () => act({ action: 'fd-reject', id: f.message_id })]);
    if (f.state === 'downloaded') buttons.push(['Save', '', '/api/files/' + encodeURIComponent(f.message_id)]);
    item(files, (f.name || 'file') + ' (' + (f.size || 0) + ' bytes) — ' + f.state,
         'from ' + (f.from || '?') + (f.group ? ' in ' + f.group : ''), buttons);
  }
  for (const f of [...(s.mcdata_files_sent || [])].reverse())
    item(files, 'sent: ' + (f.name || 'file') + ' (' + (f.bytes || 0) + ' bytes)', 'to ' + (f.target || '?') + (f.status ? ' — ' + f.status : ''));
  if (!talking) $('ptt').classList.toggle('talking', false);
}

// The event stream carries this browser's session as it was when the stream opened, so it
// is opened again after a sign-in or sign-out.
let events;
function connect() {
  if (events) events.close();
  events = new EventSource('/api/events');
  events.onmessage = (e) => render(JSON.parse(e.data));
  events.onerror = () => { $('registration').textContent = 'service unreachable'; $('registration').classList.add('off'); };
}

// ---- audio ----
let ctx, socket, mic, talking = false, playAt = 0;
let pending = new Int16Array(0);

async function audio() {
  if (ctx) return;
  ctx = new AudioContext({ sampleRate: RATE });
  socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/audio');
  socket.binaryType = 'arraybuffer';
  socket.onmessage = (e) => play(new Int16Array(e.data));
  socket.onclose = () => { ctx.close(); ctx = null; log('audio closed'); };
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    await ctx.audioWorklet.addModule('mic-worklet.js');
    mic = new AudioWorkletNode(ctx, 'mic');
    ctx.createMediaStreamSource(stream).connect(mic);
    mic.port.onmessage = (e) => { if (talking) capture(e.data); };
  } catch (e) {
    log('microphone unavailable: ' + e.message);
  }
}

function capture(samples) {
  const next = new Int16Array(pending.length + samples.length);
  next.set(pending);
  for (let i = 0; i < samples.length; i++) next[pending.length + i] = Math.max(-1, Math.min(1, samples[i])) * 0x7fff;
  let offset = 0;
  while (next.length - offset >= FRAME && socket.readyState === WebSocket.OPEN) {
    socket.send(next.slice(offset, offset + FRAME).buffer);
    offset += FRAME;
  }
  pending = next.slice(offset);
}

function play(pcm) {
  if (!$('speaker').checked || !pcm.length) return;
  const buffer = ctx.createBuffer(1, pcm.length, RATE);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < pcm.length; i++) data[i] = pcm[i] / 0x8000;
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  playAt = Math.max(playAt, ctx.currentTime + 0.06); // a small jitter buffer
  source.start(playAt);
  playAt += buffer.duration;
}

async function pttDown(e) {
  e.preventDefault();
  if ($('ptt').disabled || talking) return;
  await audio();
  talking = true;
  pending = new Int16Array(0);
  $('ptt').classList.add('talking');
  $('ptt').textContent = 'Talking… release to stop';
  act({ action: 'ptt' });
}
function pttUp() {
  if (!talking) return;
  talking = false;
  $('ptt').classList.remove('talking');
  $('ptt').textContent = 'Hold to talk';
  act({ action: 'release' });
}

// ---- wiring ----
document.querySelectorAll('button[data-action]').forEach((b) => b.addEventListener('click', () => {
  audio();
  const body = { action: b.dataset.action };
  if (b.dataset.kind) body.kind = b.dataset.kind;
  if (b.dataset.on) body.on = b.dataset.on === 'true';
  act(body);
}));
$('answer').onclick = () => { audio(); act({ action: 'answer' }); };
$('reject').onclick = () => act({ action: 'reject' });
$('private').onsubmit = (e) => {
  e.preventDefault();
  audio();
  const f = e.target;
  act({ action: 'private', target: f.target.value.trim(), emergency: f.emergency.checked });
};
$('sds').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  post('/api/command', { action: 'sds', target: f.target.value.trim(), group: f.group.checked, text: f.text.value })
    .then(() => { f.text.value = ''; }).catch((err) => log('error: ' + err.message));
};
$('fd').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, body = new FormData();
  body.append('file', f.file.files[0]);
  body.append('target', f.target.value.trim());
  body.append('group', f.group.checked);
  body.append('mandatory', f.mandatory.checked);
  post('/api/fd', body, false).then(() => f.file.value = '').catch((err) => log('error: ' + err.message));
};
$('group-select').onchange = (e) => act({ action: 'group', id: e.target.value });
// Sign-in and activation take a few seconds: the engine signs in at IdMS and registers.
function gate(form, url, fields) {
  form.onsubmit = (e) => {
    e.preventDefault();
    const button = form.querySelector('button');
    button.disabled = true;
    text($('signin-error'), 'Signing in…');
    const body = Object.fromEntries(fields.map((k) => [k, form[k].value.trim()]));
    post(url, body)
      .then(() => { form.password.value = ''; text($('signin-error'), ''); connect(); })
      .catch((err) => text($('signin-error'), err.message))
      .finally(() => { button.disabled = false; });
  };
}
gate($('login'), '/api/login', ['username', 'password']);
gate($('activate'), '/api/activate', ['url', 'password']);
$('signout').onclick = () => post('/api/logout', {}).then(connect).catch((err) => log('error: ' + err.message));
const ptt = $('ptt');
ptt.addEventListener('pointerdown', pttDown);
ptt.addEventListener('pointerup', pttUp);
ptt.addEventListener('pointerleave', pttUp);
ptt.addEventListener('pointercancel', pttUp);
document.addEventListener('keydown', (e) => { if (e.code === 'Space' && e.target === document.body && !e.repeat) pttDown(e); });
document.addEventListener('keyup', (e) => { if (e.code === 'Space' && e.target === document.body) pttUp(); });
connect();
