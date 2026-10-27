//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

'use strict';
// The OpenMCX client in the browser, drawn as the Android app: state from /api/events, actions to /api/command, voice
// over /api/audio (the dispatcher service runs mcx-client as its engine). The chat keeps its conversations in this
// browser, since the engine keeps only its last 50 messages each way.
const $ = (id) => document.getElementById(id);
const RATE = 16000, FRAME = 320; // the engine's live audio: 16 kHz s16le, 20 ms frames
const MAX_TEXT = 1000;

// Browser storage may be missing (private windows, blocked site data): everything works without it.
const saved = {
  get(key, fallback) {
    try { const v = localStorage.getItem(key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* not kept */ } },
};

// ---- small helpers ----
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
// "sip:alice.mcdata@mcx.test" → "alice": the user part without the service suffix, as the app shows names.
function short(uri) {
  if (!uri) return '';
  const user = String(uri).replace(/^sips?:/, '').split('@')[0];
  return user.replace(/\.(mcptt|mcdata|mcvideo|fa|alias)$/, '') || uri;
}
const initials = (name) => (name.replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ').map((w) => w[0]).join('').slice(0, 2) || '?').toUpperCase();
const clockOf = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
const timeOf = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const dayOf = (ms) => {
  const d = new Date(ms), today = new Date();
  if (d.toDateString() === today.toDateString()) return 'Today';
  const y = new Date(today); y.setDate(today.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
};

// ---- activity log ----
function log(text, error = false) {
  const li = el('li', error ? 'error' : '', new Date().toLocaleTimeString() + '  ' + text);
  $('log').prepend(li);
  while ($('log').children.length > 200) $('log').lastChild.remove();
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
// An engine command; the error is logged and given back as null.
const act = (body) => post('/api/command', body).catch((e) => { log('error: ' + e.message, true); return Promise.reject(e); });
const tryAct = (body) => act(body).catch(() => null);

// ---- theme ----
function applyTheme(choice) {
  if (choice === 'light' || choice === 'dark') document.documentElement.dataset.theme = choice;
  else delete document.documentElement.dataset.theme;
  document.querySelectorAll('[data-theme-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.themeChoice === (choice || 'system'))));
}
applyTheme(saved.get('mcx-theme', 'system'));
document.querySelectorAll('[data-theme-choice]').forEach((b) => b.addEventListener('click', () => {
  saved.set('mcx-theme', b.dataset.themeChoice);
  applyTheme(b.dataset.themeChoice);
}));

// ---- icons for the floor (the app's key signs) ----
const ICONS = {
  mic: '<svg width="SIZE" height="SIZE" viewBox="0 0 24 24" fill="currentColor"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11h-2z"/></svg>',
  speaker: '<svg width="SIZE" height="SIZE" viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z"/></svg>',
  wait: '<svg width="SIZE" height="SIZE" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  muted: '<svg width="SIZE" height="SIZE" viewBox="0 0 24 24" fill="currentColor"><path d="M19 11h-2a5 5 0 0 1-.6 2.4l1.5 1.5A7 7 0 0 0 19 11zM15 11V5a3 3 0 0 0-5.9-.8L15 10.1V11zM4.3 3 3 4.3l6 6V11a3 3 0 0 0 4.5 2.6l1.6 1.6A5 5 0 0 1 7 11H5a7 7 0 0 0 6 6.9V21h2v-3.1a7 7 0 0 0 3.5-1.5l3.2 3.2 1.3-1.3L4.3 3z"/></svg>',
};
const icon = (name, size) => ICONS[name].replaceAll('SIZE', String(size));

// ---- state ----
let state = {};
let lastRender = 0;

// The sign-in screen (position mode, or sign-in on IdMS's page): shown whenever this browser has no session.
function renderSession(session) {
  const out = !session.signed_in;
  $('signin').hidden = !out;
  $('app').hidden = out;
  $('open-settings').hidden = out;
  $('signout-section').hidden = out || session.mode === 'lab';
  if (!out) return false;
  const browser = session.mode === 'browser';
  $('idms-login').hidden = !browser;
  $('idms-host').textContent = session.idms ? ', ' + session.idms : '';
  $('position-login').hidden = browser;
  $('signin-reason').textContent = session.reason || '';
  $('accounts').textContent = (session.accounts || []).join(', ') || 'none';
  $('signin-busy').textContent = session.busy ? 'Someone is signed in on this computer; they must sign out first.' : '';
  setPhase('warn', 'signed out');
  if (talking) pttUp();
  return true;
}

function setPhase(kind, text) {
  $('phase').className = 'pill ' + kind;
  $('phase-text').textContent = text;
}

function render(s) {
  state = s;
  lastRender = Date.now();
  if (renderSession(s.session || {})) return;
  const id = s.identity || {};
  $('user').textContent = short(id.user) || 'OpenMCX';
  $('user').title = id.user || '';
  if (!s.registered) setPhase('warn', 'offline');
  else if (!s.authorized) setPhase('warn', 'authorising');
  else setPhase('ok', 'ready');
  $('offline').hidden = !!s.registered;
  $('offline-detail').textContent = s.last_error ? String(s.last_error) : 'The client keeps trying by itself.';
  renderIncoming(s);
  renderCall(s);
  chat.merge(s);
  renderChat();
  renderStatus(s);
  renderProfile(s);
}

// ---- incoming call ----
function renderIncoming(s) {
  const ringing = s.call_state === 'ringing';
  $('incoming').hidden = !ringing;
  if (!ringing) return;
  $('incoming-label').textContent = 'Incoming ' + (s.priority === 'emergency' ? 'emergency ' : '') + 'private call';
  $('incoming-title').textContent = short(s.private_peer) || 'Unknown caller';
  $('incoming-alias').textContent = s.calling_functional_alias ? short(s.calling_functional_alias) : '';
}

// ---- Call tab ----
const callActive = (s) => !!s.call_state && s.call_state !== 'idle' && s.call_state !== 'ringing';
let activeSince = 0, pendingGroupCall = null, dismissedFailure = saved.get('mcx-dismissed-failure', '');

// How a floor state looks: the fill for the key and badge, the text colour on the background, a sign
// (android/.../Screens.kt floorLook). The engine's states: TS 24.380 clause 6.2.4 (HasPermission, NoPermission,
// PendingRequest, Queued, PendingRelease).
function floorLook(s) {
  if (s.floor_control === false) {
    return { fill: 'talking', ink: 'on-talking', label: 'Both can talk', hint: 'Hold the key to send your voice', word: 'LIVE', sub: 'Hold to talk', sign: 'mic' };
  }
  if (s.call_state !== 'active') {
    const what = { calling: 'Calling…', progress: 'Ringing…', answering: 'Connecting…' }[s.call_state] || 'Setting up…';
    return { fill: 'idle', ink: 'on-idle', label: what, hint: 'Setting up the call', word: 'PTT', sub: 'Hold to talk', sign: 'wait', pulse: false };
  }
  switch (s.floor_state) {
    case 'HasPermission':
      return { fill: 'talking', ink: 'on-talking', label: 'You are talking', hint: 'Release PTT when done', word: 'PTT', sub: 'Release to stop', sign: 'mic' };
    case 'PendingRequest':
      return { fill: 'requesting', ink: 'on-requesting', label: 'Requesting the floor…', hint: 'Keep holding', word: 'PTT', sub: 'Waiting…', sign: 'wait', pulse: true };
    case 'Queued':
      return {
        fill: 'requesting', ink: 'on-requesting', label: s.queue_position ? 'Queued, position ' + s.queue_position : 'Queued',
        hint: 'Keep holding to stay in the queue; release to leave it', word: 'PTT', sub: 'In queue', sign: 'wait', pulse: true,
      };
    case 'PendingRelease':
      return { fill: 'releasing', ink: 'on-releasing', label: 'Releasing…', hint: 'Handing the floor back', word: 'PTT', sub: 'Hold to talk', sign: 'wait' };
    default:
      if (s.floor_owner) {
        return { fill: 'taken', ink: 'on-taken', label: short(s.floor_owner) + ' is talking', hint: 'Wait for the floor to be free', word: 'PTT', sub: 'Floor busy', sign: 'speaker' };
      }
      return { fill: 'idle', ink: 'on-idle', label: 'Floor idle', hint: 'Press and hold PTT to talk', word: 'PTT', sub: 'Hold to talk', sign: 'mic' };
  }
}

let groupsShown = '';
function renderGroups(s) {
  const id = s.identity || {};
  const groups = [...new Set([...(s.profile_groups || []), ...(id.group ? [id.group] : [])])];
  const affiliations = s.affiliations || {};
  const busy = callActive(s) || !s.registered;
  const key = JSON.stringify([groups, id.group, affiliations, busy]);
  if (key === groupsShown) return;
  groupsShown = key;
  const list = $('groups');
  list.replaceChildren();
  if (!groups.length) list.append(el('p', 'hint', 'No groups in your profile.'));
  for (const g of groups) {
    const row = el('div', 'list-row');
    const radio = el('input');
    radio.type = 'radio';
    radio.name = 'group';
    radio.checked = g === id.group;
    radio.disabled = busy;
    radio.setAttribute('aria-label', 'Select ' + short(g));
    radio.onchange = () => tryAct({ action: 'group', id: g });
    const text = el('div', 'grow');
    text.append(el('div', 'name', short(g)));
    const affiliated = affiliations[g] === 'affiliated';
    const affil = el('span', 'affil' + (affiliated ? ' on' : ''));
    affil.append(el('i'), document.createTextNode(affiliated ? 'affiliated' : String(affiliations[g] || 'not affiliated')));
    text.append(affil);
    text.title = g;
    const call = el('button', 'btn', 'Call');
    call.disabled = busy;
    call.onclick = () => groupCall(g, 'normal');
    const menu = el('div', 'menu');
    const more = el('button', 'icon-button', '⋮');
    more.type = 'button';
    more.disabled = busy;
    more.setAttribute('aria-label', 'More call options for ' + short(g));
    const items = el('div', 'menu-list');
    items.hidden = true;
    for (const [kind, label, cls] of [['broadcast', 'Broadcast call', ''], ['imminent', 'Imminent peril call', ''], ['emergency', 'Emergency call', 'danger']]) {
      const b = el('button', cls, label);
      b.type = 'button';
      b.onclick = () => { items.hidden = true; groupCall(g, kind); };
      items.append(b);
    }
    more.onclick = (e) => { e.stopPropagation(); closeMenus(); items.hidden = false; };
    menu.append(more, items);
    row.append(radio, text, call, menu);
    list.append(row);
  }
}

function closeMenus() { document.querySelectorAll('.menu-list').forEach((m) => { m.hidden = true; }); }
document.addEventListener('click', closeMenus);

// A call on a group: the group is selected first (the engine calls the selected group), then the call is placed once
// the engine reports the selection.
async function groupCall(group, kind) {
  await audio();
  if ((state.identity || {}).group === group) {
    tryAct({ action: 'call', kind });
    return;
  }
  pendingGroupCall = { group, kind, at: Date.now() };
  tryAct({ action: 'group', id: group });
}

function renderCall(s) {
  if (pendingGroupCall) {
    if ((s.identity || {}).group === pendingGroupCall.group) {
      tryAct({ action: 'call', kind: pendingGroupCall.kind });
      pendingGroupCall = null;
    } else if (Date.now() - pendingGroupCall.at > 8000) {
      log('error: ' + short(pendingGroupCall.group) + ' could not be selected', true);
      pendingGroupCall = null;
    }
  }
  const active = callActive(s);
  $('call-idle').hidden = active;
  $('call-active').hidden = !active;
  if (!active) {
    activeSince = 0;
    if (talking) pttUp();
    renderGroups(s);
    // The refusal of the last call set up: its SIP status and Warning header field.
    const f = s.last_call_failure;
    const failure = f ? 'The call was refused (' + f.status + ')' + (f.warning ? ': ' + f.warning : '') : '';
    $('call-failure').hidden = !failure || failure === dismissedFailure;
    $('call-failure-text').textContent = failure;
    return;
  }
  if (s.call_state === 'active' && !activeSince) activeSince = Date.now();
  const id = s.identity || {};
  const type = String(s.call_type || '');
  const priv = type.includes('private') || (!!s.private_peer && !s.call_group);
  $('call-kind').textContent = priv ? 'Private call' : type.includes('adhoc') ? 'Ad hoc call'
    : type.includes('broadcast') ? 'Broadcast call' : type.includes('chat') ? 'Chat group call' : 'Group call';
  $('call-title').textContent = priv ? short(s.private_peer) : short(s.call_group || id.group);
  const alias = s.calling_functional_alias || s.called_functional_alias;
  $('call-alias').hidden = !alias;
  $('call-alias').textContent = alias ? short(alias) : '';
  const prio = s.priority === 'emergency' || s.priority === 'imminent' ? s.priority : '';
  $('call-priority').hidden = !prio;
  $('call-priority').className = 'priority ' + prio;
  $('call-priority-text').textContent = prio === 'emergency' ? 'EMERGENCY' : 'IMMINENT PERIL';
  $('call-emergency').textContent = prio === 'emergency' ? 'Cancel emergency' : 'Emergency';
  $('call-imminent').hidden = priv || prio === 'emergency';
  $('call-imminent').textContent = prio === 'imminent' ? 'End imminent peril' : 'Imminent peril';
  $('call-emergency').disabled = $('call-imminent').disabled = s.call_state !== 'active';

  const look = floorLook(s);
  $('floor-label').textContent = look.label;
  $('floor-label').style.color = `var(--${look.ink})`;
  $('floor-hint').textContent = look.hint;
  $('floor-badge').style.background = `var(--${look.fill})`;
  $('floor-badge').innerHTML = icon(look.sign, 22);
  $('ptt').style.setProperty('--key', `var(--${look.fill})`);
  $('ring').style.setProperty('--key', `var(--${look.fill})`);
  $('ring').classList.toggle('pulse', !!look.pulse);
  $('ptt-icon').innerHTML = icon(look.sign, 36);
  $('ptt-word').textContent = look.word;
  $('ptt-sub').textContent = look.sub;
  $('ptt').disabled = s.call_state !== 'active';
}
setInterval(() => { $('call-clock').textContent = activeSince ? clockOf(Date.now() - activeSince) : ''; }, 500);

$('call-failure-dismiss').onclick = () => {
  dismissedFailure = $('call-failure-text').textContent;
  saved.set('mcx-dismissed-failure', dismissedFailure);
  $('call-failure').hidden = true;
};

// Private calls: automatic commencement, or ring first, without floor control, or emergency.
function privateCall(extra = {}) {
  const target = $('private-target').value.trim();
  if (!target) { $('private-target').focus(); return; }
  audio();
  tryAct({ action: 'private', target, ...extra });
}
$('private-form').onsubmit = (e) => { e.preventDefault(); privateCall(); };
$('private-more').onclick = (e) => { e.stopPropagation(); closeMenus(); $('private-menu').hidden = false; };
document.querySelectorAll('[data-private]').forEach((b) => b.addEventListener('click', () => {
  $('private-menu').hidden = true;
  privateCall({ [b.dataset.private]: true });
}));

$('answer').onclick = () => { audio(); tryAct({ action: 'answer' }); };
$('reject').onclick = () => tryAct({ action: 'reject' });
$('hangup').onclick = () => { if (talking) pttUp(); tryAct({ action: 'hangup' }); };
$('call-emergency').onclick = () => tryAct({ action: 'priority', level: 'emergency', on: state.priority !== 'emergency' });
$('call-imminent').onclick = () => tryAct({ action: 'priority', level: 'imminent', on: state.priority !== 'imminent' });
let speakerOn = saved.get('mcx-speaker', true);
function showSpeaker() {
  $('speaker').setAttribute('aria-pressed', String(speakerOn));
  $('speaker').textContent = speakerOn ? 'Speaker on' : 'Speaker off';
}
showSpeaker();
$('speaker').onclick = () => { speakerOn = !speakerOn; saved.set('mcx-speaker', speakerOn); showSpeaker(); };

// ---- audio: the browser microphone and speaker, bridged to the engine's live audio ----
let ctx, socket, mic, talking = false, playAt = 0;
let pendingPcm = new Int16Array(0);

async function audio() {
  if (ctx) return;
  ctx = new AudioContext({ sampleRate: RATE });
  socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/audio');
  socket.binaryType = 'arraybuffer';
  socket.onmessage = (e) => play(new Int16Array(e.data));
  socket.onclose = () => { if (ctx) ctx.close(); ctx = null; log('audio closed'); };
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    await ctx.audioWorklet.addModule('/mic-worklet.js');
    mic = new AudioWorkletNode(ctx, 'mic');
    ctx.createMediaStreamSource(stream).connect(mic);
    mic.port.onmessage = (e) => { if (talking) capture(e.data); };
  } catch (e) {
    log('microphone unavailable: ' + e.message + ' (the page needs https or localhost)', true);
  }
}

function capture(samples) {
  const next = new Int16Array(pendingPcm.length + samples.length);
  next.set(pendingPcm);
  for (let i = 0; i < samples.length; i++) next[pendingPcm.length + i] = Math.max(-1, Math.min(1, samples[i])) * 0x7fff;
  let offset = 0;
  while (next.length - offset >= FRAME && socket.readyState === WebSocket.OPEN) {
    socket.send(next.slice(offset, offset + FRAME).buffer);
    offset += FRAME;
  }
  pendingPcm = next.slice(offset);
}

function play(pcm) {
  if (!speakerOn || !pcm.length || !ctx) return;
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
  if (e) e.preventDefault();
  if (talking || $('ptt').disabled) return;
  await audio();
  talking = true;
  pendingPcm = new Int16Array(0);
  $('ptt').classList.add('held');
  tryAct({ action: 'ptt' });
}
function pttUp() {
  if (!talking) return;
  talking = false;
  $('ptt').classList.remove('held');
  tryAct({ action: 'release' });
}
const ptt = $('ptt');
ptt.addEventListener('pointerdown', (e) => { ptt.setPointerCapture(e.pointerId); pttDown(e); });
ptt.addEventListener('pointerup', pttUp);
ptt.addEventListener('pointercancel', pttUp);
ptt.addEventListener('contextmenu', (e) => e.preventDefault());
// Space is the PTT key while the Call tab shows (not while typing); with no call it calls the selected group.
const typing = (t) => t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
document.addEventListener('keydown', (e) => {
  if (e.code !== 'Space' || e.repeat || typing(e.target) || currentTab !== 'call' || !$('settings').hidden) return;
  e.preventDefault();
  if (callActive(state)) pttDown(e);
  else if (state.registered && (state.identity || {}).group) groupCall(state.identity.group, 'normal');
});
document.addEventListener('keyup', (e) => { if (e.code === 'Space' && !typing(e.target)) pttUp(); });
window.addEventListener('blur', pttUp);

// ---- Chat ----
// Messages come from the engine's status (mcdata_inbox received, mcdata_sent sent, mcdata_notifications the
// recipients' reports) and are kept here by message ID. A conversation is the other party: a user, a group, or a
// functional alias (a message sent to the alias, or from a user presenting it, goes there).
const chat = {
  key: '',
  msgs: {},        // message ID → message
  pending: [],     // sent from this page, not yet in the engine's status
  open: null,      // the conversation shown
  composing: false, // a new conversation: the To field shows
  // The order messages came to this page in: the SDS date and time counts whole seconds, so a quick answer has the
  // same time as the question. A sent message takes the place of its pending copy.
  seq: 0,
  load(user) {
    const key = 'mcx-chat:' + user;
    if (key === this.key) return;
    this.key = key;
    this.msgs = saved.get(key, {});
    this.pending = [];
    this.seq = Math.max(0, ...Object.values(this.msgs).map((m) => m.seq || 0));
  },
  save() {
    const all = Object.values(this.msgs).sort((a, b) => b.at - a.at).slice(0, 500);
    this.msgs = Object.fromEntries(all.map((m) => [m.id, m]));
    saved.set(this.key, this.msgs);
  },
  merge(s) {
    this.load((s.identity || {}).mcdata_user || (s.identity || {}).user || 'me');
    let changed = false;
    const put = (m) => {
      const old = this.msgs[m.id];
      const next = { ...old, ...m, at: old ? old.at : m.at, seq: old ? old.seq : m.seq || ++this.seq };
      if (!old || JSON.stringify(old) !== JSON.stringify(next)) { this.msgs[m.id] = next; changed = true; }
    };
    for (const e of s.mcdata_inbox || []) {
      const texts = (e.payloads || []).map((p) => (p.content_type === 1 ? p.text : '[content of another type]'));
      const kind = e.group ? 'group' : e.functional_alias ? 'alias' : 'user';
      put({
        id: e.message_id, dir: 'in', peer: e.group || e.functional_alias || e.from, kind, from: e.from,
        text: texts.join('\n'), at: e.date_time ? e.date_time * 1000 : Date.now(), displayed: !!e.displayed,
        emergency: !!(e.emergency || e.imminent_peril), toAlias: e.called_functional_alias || '',
      });
    }
    const last = s.mcdata_last_result || {};
    for (const r of s.mcdata_sent || []) {
      if (r.status === 300) { // the request to an alias, redirected: its follow-up is the message
        // A poll may have shown the request while it was in flight; its bubble goes to the follow-up.
        const stale = this.msgs[r.message_id];
        if (stale) {
          delete this.msgs[r.message_id];
          const followed = (s.mcdata_sent || []).some((f) => f.conversation_id === r.conversation_id && f.status !== 300 && this.msgs[f.message_id]);
          if (!followed) this.pending.push({ ...stale, id: 'local-' + r.message_id, status: 'sending' });
          changed = true;
        }
        continue;
      }
      const group = r.request_type === 'group-sds';
      const peer = group ? r.target : r.called_alias || r.target;
      const kind = group ? 'group' : r.called_alias ? 'alias' : 'user';
      const old = this.msgs[r.message_id];
      let text = r.text != null ? r.text : old ? old.text : '';
      const match = this.pending.findIndex((p) => p.peer === peer && (r.text == null || p.text === r.text));
      let seq, at = r.date_time ? r.date_time * 1000 : Date.now();
      if (!old && match >= 0) {
        if (!text) text = this.pending[match].text;
        ({ seq, at } = this.pending[match]);
        this.pending.splice(match, 1);
        changed = true;
      }
      let status = r.status == null ? 'sending' : r.status < 300 ? 'sent' : 'failed';
      if (old && (old.status === 'delivered' || old.status === 'read') && status === 'sent') status = old.status;
      const reason = status === 'failed' ? (last.message_id === r.message_id && last.warning ? last.warning : 'SIP ' + r.status) : '';
      put({ id: r.message_id, dir: 'out', peer, kind, text, at, seq, status, reason });
    }
    // TS 24.282 table 15.2.5-1: 1 undelivered, 2 delivered, 3 read, 4 delivered and read, 5 prevented.
    for (const n of s.mcdata_notifications || []) {
      const m = this.msgs[n.message_id];
      if (!m || m.dir !== 'out') continue;
      const status = { 1: 'failed', 2: 'delivered', 3: 'read', 4: 'read', 5: 'failed' }[n.type];
      const rank = { sending: 0, sent: 1, delivered: 2, read: 3, failed: 4 };
      if (status && rank[status] > rank[m.status]) {
        put({ ...m, status, reason: n.type === 1 ? 'not delivered' : n.type === 5 ? 'prevented by the system' : m.reason });
      }
    }
    for (const p of this.pending) {
      if (p.status === 'sending' && Date.now() - p.at > 15000) { p.status = 'failed'; p.reason = p.reason || 'no answer from the engine'; changed = true; }
    }
    if (changed) this.save();
  },
};

// The ops agent configured on this page (--ops-agent), offered first.
const agentOf = () => state.ops_agent || null;
const isAgent = (peer) => { const a = agentOf(); return !!a && peer === a.id; };

function conversations() {
  const map = new Map();
  const agent = agentOf();
  if (agent) map.set(agent.id, { peer: agent.id, kind: 'agent', msgs: [] });
  const groups = new Set(state.profile_groups || []);
  for (const m of [...Object.values(chat.msgs), ...chat.pending]) {
    if (!map.has(m.peer)) map.set(m.peer, { peer: m.peer, kind: isAgent(m.peer) ? 'agent' : groups.has(m.peer) ? 'group' : m.kind, msgs: [] });
    map.get(m.peer).msgs.push(m);
  }
  for (const c of map.values()) {
    c.msgs.sort((a, b) => (a.seq || 0) - (b.seq || 0) || a.at - b.at);
    c.last = c.msgs[c.msgs.length - 1];
    c.unread = c.msgs.filter((m) => m.dir === 'in' && !m.displayed && !m.seen).length;
  }
  return [...map.values()].sort((a, b) => (b.kind === 'agent') - (a.kind === 'agent') || ((b.last || {}).at || 0) - ((a.last || {}).at || 0));
}

const nameOf = (c) => (c.kind === 'agent' ? agentOf().name : short(c.peer));

function avatar(c) {
  const a = el('div', 'avatar' + (c.kind === 'agent' ? ' agent' : c.kind === 'group' ? ' group' : ''));
  if (c.kind === 'agent') {
    a.innerHTML = '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2a1.5 1.5 0 0 1 1 2.6V6h4a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3h4V4.6A1.5 1.5 0 0 1 12 2zM9 11.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zM1 11h1.5v5H1zm20.5 0H23v5h-1.5z"/></svg>';
  } else {
    a.textContent = initials(short(c.peer));
  }
  return a;
}

function statusMark(m) {
  if (m.status === 'sending') return el('span', '', 'sending…');
  if (m.status === 'failed') return el('span', '', 'not sent: ' + (m.reason || 'failed'));
  const ticks = el('span', 'ticks' + (m.status === 'read' ? ' read' : ''), m.status === 'sent' ? '✓' : '✓✓');
  ticks.setAttribute('aria-label', m.status);
  ticks.title = m.status;
  return ticks;
}

let chatShown = '';
function renderChat() {
  markRead(conversations());
  const list = conversations();
  const unread = list.reduce((n, c) => n + c.unread, 0);
  $('chat-badge').hidden = !unread;
  $('chat-badge').textContent = unread > 99 ? '99+' : String(unread);
  if (chat.open == null && !chat.composing && list.length && window.innerWidth > 820) { chat.open = list[0].peer; chatShown = ''; return renderChat(); }
  const key = JSON.stringify([list.map((c) => [c.peer, c.kind, c.unread, c.msgs.length, c.last && [c.last.id, c.last.status, c.last.displayed]]),
    chat.open, chat.composing, agentOf(), state.mcdata_authorized, Math.floor(Date.now() / 30000)]);
  if (key === chatShown) return;
  chatShown = key;

  const convoList = $('convo-list');
  convoList.replaceChildren();
  for (const c of list) {
    const b = el('button', 'convo');
    b.type = 'button';
    b.setAttribute('aria-current', String(c.peer === chat.open && !chat.composing));
    const body = el('div', 'body');
    const top = el('div', 'top');
    top.append(el('span', 'title', nameOf(c)));
    if (c.last) top.append(el('span', 'time', timeOf(c.last.at)));
    if (c.unread) top.append(el('span', 'badge', String(c.unread)));
    const preview = el('div', 'preview');
    if (c.kind === 'agent') preview.append(el('span', 'tag', 'AGENT'));
    if (c.kind === 'group') preview.append(el('span', 'tag', 'GROUP'));
    preview.append(el('span', '', c.last ? (c.last.dir === 'out' ? 'You: ' : '') + c.last.text.split('\n')[0] : c.kind === 'agent' ? 'Ask the ops agent' : ''));
    body.append(top, preview);
    b.append(avatar(c), body);
    b.title = c.peer;
    b.onclick = () => openConversation(c.peer);
    convoList.append(b);
  }
  renderThread(list);
}

function openConversation(peer) {
  chat.open = peer;
  chat.composing = false;
  chatShown = '';
  renderChat();
  $('text').focus();
}

function renderThread(list) {
  const c = list.find((x) => x.peer === chat.open);
  $('chat').classList.toggle('open', chat.composing || !!c);
  $('to-row').hidden = !chat.composing;
  const messages = $('messages');
  const nearBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 80;
  messages.replaceChildren();
  if (chat.composing || !c) {
    $('thread-title').textContent = 'New chat';
    $('thread-sub').textContent = 'A user, a group or a functional alias';
    messages.append(el('div', 'empty', chat.composing ? 'Write to a user, a group or a functional alias.' : 'Choose a conversation.'));
    $('send').disabled = !state.mcdata_authorized;
    return;
  }
  const agent = c.kind === 'agent' ? agentOf() : null;
  $('thread-title').textContent = nameOf(c);
  $('thread-sub').textContent = agent ? (agent.alias ? 'Functional alias · ' : '') + agent.id
    : (c.kind === 'group' ? 'Group · ' : c.kind === 'alias' ? 'Functional alias · ' : '') + c.peer;
  $('send').disabled = !state.mcdata_authorized;
  if (!state.mcdata_authorized) $('thread-sub').textContent += ' · MCData not available';

  if (!c.msgs.length) {
    const empty = el('div', 'empty');
    empty.append(avatar(c));
    if (agent) {
      empty.append(el('div', '', 'Ask ' + agent.name + ' about incidents, units and procedures. Answers come back here as messages.'));
      const sugg = el('div', 'suggestions');
      for (const q of ['What can you help me with?', 'Any open incidents near me?', 'Which units are available?']) {
        const b = el('button', '', q);
        b.type = 'button';
        b.onclick = () => { $('text').value = q; grow(); $('text').focus(); };
        sugg.append(b);
      }
      empty.append(sugg);
    } else {
      empty.append(el('div', '', 'No messages yet.'));
    }
    messages.append(empty);
    return;
  }
  let day = '';
  for (const m of c.msgs) {
    const d = dayOf(m.at);
    if (d !== day) { day = d; messages.append(el('div', 'day', d)); }
    const bubble = el('div', 'bubble ' + m.dir + (m.emergency ? ' emergency' : ''));
    if (m.dir === 'in' && (c.kind === 'group' || (c.kind === 'alias' && !agent))) bubble.append(el('div', 'who', short(m.from)));
    if (m.dir === 'in' && m.emergency) bubble.append(el('div', 'who', 'EMERGENCY'));
    bubble.append(el('div', 'text', m.text));
    const meta = el('div', 'meta' + (m.status === 'failed' ? ' failed' : ''));
    meta.append(el('span', '', timeOf(m.at)));
    if (m.dir === 'out') meta.append(statusMark(m));
    bubble.append(meta);
    messages.append(bubble);
  }
  // The agent is at work between its read report and its answer.
  const last = c.msgs[c.msgs.length - 1];
  if (agent && last.dir === 'out' && last.status !== 'failed' && Date.now() - last.at < 120000) {
    const w = el('div', 'working');
    const dots = el('span', 'dots');
    dots.append(el('i'), el('i'), el('i'));
    w.append(dots, el('span', '', last.status === 'read' ? agent.name + ' is working on it…'
      : last.status === 'delivered' ? 'Delivered to ' + agent.name : 'Sending to ' + agent.name + '…'));
    messages.append(w);
  }
  if (nearBottom || c.last.dir === 'out') messages.scrollTop = messages.scrollHeight;
}

// A message shown is a message read (TS 24.282 clause 9.2.1.3): the engine sends the read report its sender asked
// for. Only while the conversation is on screen.
function markRead(list) {
  if (currentTab !== 'chat' || document.visibilityState !== 'visible' || chat.composing) return;
  const c = list.find((x) => x.peer === chat.open);
  if (!c || (window.innerWidth <= 820 && !$('chat').classList.contains('open'))) return;
  let changed = false;
  for (const m of c.msgs) {
    if (m.dir !== 'in' || m.displayed || m.seen) continue;
    m.seen = true;
    chat.msgs[m.id] = { ...chat.msgs[m.id], seen: true };
    tryAct({ action: 'sds-display', id: m.id });
    changed = true;
  }
  if (changed) chat.save();
}

// Sending: to the ops agent through its alias (or MCData ID), to a group, an alias or a user, asking for delivery
// and read reports one-to-one.
function send(text) {
  let peer, kind;
  if (chat.composing) {
    peer = $('to').value.trim();
    kind = $('to-kind').value;
    if (!/^sips?:[^\s@]+@\S+$/.test(peer)) { log('error: the recipient must be a SIP URI (sip:user@domain)', true); $('to').focus(); return false; }
  } else {
    peer = chat.open;
    const c = conversations().find((x) => x.peer === peer);
    kind = c ? c.kind : 'user';
  }
  const agent = agentOf();
  const body = { action: 'sds', target: peer, text };
  if (kind === 'agent') { body.to_alias = !!agent.alias; body.report = 'both'; }
  else if (kind === 'group') body.group = true;
  else if (kind === 'alias') { body.to_alias = true; body.report = 'both'; }
  else body.report = 'both';
  const p = { id: 'local-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7), dir: 'out', peer, kind: kind === 'agent' ? 'alias' : kind, text, at: Date.now(), seq: ++chat.seq, status: 'sending' };
  chat.pending.push(p);
  act(body).catch((e) => { p.status = 'failed'; p.reason = e.message; chatShown = ''; renderChat(); });
  chat.open = peer;
  chat.composing = false;
  chatShown = '';
  renderChat();
  return true;
}

function grow() {
  const t = $('text');
  t.style.height = 'auto';
  t.style.height = Math.min(t.scrollHeight, 160) + 'px';
  const n = [...t.value].length;
  $('count').hidden = n < MAX_TEXT - 200;
  $('count').textContent = n + ' / ' + MAX_TEXT;
}
$('text').addEventListener('input', grow);
$('text').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('composer').requestSubmit(); }
});
$('composer').onsubmit = (e) => {
  e.preventDefault();
  const text = $('text').value.trim();
  if (!text || $('send').disabled) return;
  if ([...text].length > MAX_TEXT) { log('error: a message has at most ' + MAX_TEXT + ' characters', true); return; }
  if (send(text)) { $('text').value = ''; grow(); }
};
$('new-chat').onclick = () => { chat.composing = true; chat.open = null; chatShown = ''; renderChat(); $('to').focus(); };
$('back').onclick = () => { chat.open = null; chat.composing = false; chatShown = ''; renderChat(); };
document.addEventListener('visibilitychange', () => { chatShown = ''; renderChat(); });

// ---- Logs and settings ----
function kv(list, pairs) {
  list.replaceChildren();
  for (const [k, v] of pairs) list.append(el('dt', '', k), el('dd', '', v == null || v === '' ? '—' : String(v)));
}
function renderStatus(s) {
  kv($('status-kv'), [
    ['Registered', s.registered ? 'yes' : 'no'], ['MCPTT', s.authorized ? 'authorised' : 'not authorised'],
    ['MCData', s.mcdata_authorized ? 'authorised' : 'not authorised'], ['Affiliated', s.affiliated ? 'yes' : 'no'],
    ['Call', s.call_state || 'idle'], ['Floor', s.floor_state], ['Last error', s.last_error],
  ]);
}
function renderProfile(s) {
  const id = s.identity || {};
  const a = agentOf();
  kv($('profile-kv'), [['MCPTT ID', id.user], ['MCData ID', id.mcdata_user], ['Group', id.group], ['MCData group', id.mcdata_group],
    ['Ops agent', a ? a.name + ' (' + a.id + ')' : 'none configured'], ['Session', (s.session || {}).mode]]);
}
$('clear-log').onclick = () => $('log').replaceChildren();
$('open-settings').onclick = () => { $('settings').hidden = false; };
$('close-settings').onclick = () => { $('settings').hidden = true; };
$('clear-chat').onclick = () => {
  if (!confirm('Clear the chat history kept in this browser?')) return;
  chat.msgs = {};
  chat.pending = [];
  chat.save();
  chatShown = '';
  renderChat();
};
$('signout').onclick = () => post('/api/logout', {}).then(() => { $('settings').hidden = true; connect(); }).catch((e) => log('error: ' + e.message, true));

// ---- tabs ----
let currentTab = saved.get('mcx-tab', 'call');
function showTab(name) {
  currentTab = name;
  saved.set('mcx-tab', name);
  for (const t of ['call', 'chat', 'logs']) {
    $('tab-' + t).hidden = t !== name;
    $('tab-btn-' + t).setAttribute('aria-selected', String(t === name));
  }
  if (name !== 'call' && talking) pttUp();
  chatShown = '';
  if (state.session) renderChat();
}
for (const t of ['call', 'chat', 'logs']) $('tab-btn-' + t).onclick = () => showTab(t);
showTab(currentTab);

// ---- sign-in: a few seconds, while the engine signs in at IdMS and registers ----
function gate(form, url, fields) {
  form.onsubmit = (e) => {
    e.preventDefault();
    const button = form.querySelector('button');
    button.disabled = true;
    $('signin-error').textContent = 'Signing in…';
    const body = Object.fromEntries(fields.map((k) => [k, form[k].value.trim()]));
    post(url, body)
      .then(() => { form.password.value = ''; $('signin-error').textContent = ''; connect(); })
      .catch((err) => { $('signin-error').textContent = err.message; })
      .finally(() => { button.disabled = false; });
  };
}
gate($('login'), '/api/login', ['username', 'password']);
gate($('activate'), '/api/activate', ['url', 'password']);

// ---- the state stream: opened again after a sign-in or sign-out, which change the session it carries ----
let events;
function connect() {
  if (events) events.close();
  events = new EventSource('/api/events');
  events.onmessage = (e) => render(JSON.parse(e.data));
  events.onerror = () => setPhase('off', 'unreachable');
}
// The stream sends only changes: the working indicator and relative times still move.
setInterval(() => { if (state.session && Date.now() - lastRender > 5000) { chatShown = ''; renderChat(); } }, 5000);
connect();
