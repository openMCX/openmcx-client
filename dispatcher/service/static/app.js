//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

'use strict';
// OpenMCX dispatcher UI: state from /api/events, actions to /api/command, voice over /api/audio.
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
// The private call service responses (TS 24.379 annex F.1 <response-type>), as the page names them.
const OUTCOMES = {
  'remotely-initiated-private-call-response': 'Asked private call',
  'remotely-initiated-group-call-response': 'Asked group call',
  'transfer-private-call-response': 'Transfer',
  'forward-private-call-response': 'Forwarding',
};
let state = {};
// The functional aliases of the call (TS 24.379 clause 10.1.1.3.1.1, clause 11.1.1.3.1.1): the caller's, and the one
// called (a private call to an alias, clause 11.1.1.4.1).
function aliasesOfCall(s) {
  return (s.calling_functional_alias ? ' · as ' + s.calling_functional_alias : '') +
    (s.called_functional_alias ? ' · to ' + s.called_functional_alias : '');
}
// Regroups using a preconfigured group (TS 24.379 clause 16) and temporary groups (TS 24.481 clause 6.3.14): the ones
// the dispatcher was told of, each selectable for a call and removable; those it formed at the group management
// server are deleted there (clause 6.3.15). The page remembers which ones it formed.
const formed = new Set((() => { try { return JSON.parse(localStorage.getItem('formed-temporary') || '[]'); } catch { return []; } })());
function keepFormed() { try { localStorage.setItem('formed-temporary', JSON.stringify([...formed])); } catch { /* none */ } }
let regroupGroupsShown = '';
const announced = new Set();
function renderRegroups(s) {
  const groups = s.profile_groups || [];
  if (JSON.stringify(groups) !== regroupGroupsShown) {
    regroupGroupsShown = JSON.stringify(groups);
    $('regroup-groups').replaceChildren(...groups.map((g) => Object.assign(document.createElement('option'), { value: g })));
  }
  const list = $('regroup-list');
  list.replaceChildren();
  const current = (s.identity || {}).group;
  for (const [tgi, r] of Object.entries(s.regroups || {})) {
    const members = (r.groups && r.groups.length ? r.groups : r.users || []).join(', ');
    const buttons = [];
    if (tgi !== current) buttons.push(['Select', 'go', () => act({ action: 'group', id: tgi })]);
    buttons.push(formed.has(tgi) ? ['Delete', '', () => act({ action: 'temporary', op: 'delete', tgi })]
      : ['Remove', '', () => act({ action: 'regroup', op: 'remove', tgi })]);
    item(list, tgi, (r.users && r.users.length ? 'users: ' : 'groups: ') + members +
         (r.preconfigured ? ' · as ' + r.preconfigured : ''), buttons);
  }
  // A temporary group it formed that was announced and is gone again is forgotten.
  for (const tgi of formed) if (announced.has(tgi) && !(s.regroups || {})[tgi]) { formed.delete(tgi); announced.delete(tgi); keepFormed(); }
  for (const tgi of Object.keys(s.regroups || {})) announced.add(tgi);
  const r = s.regroup_result, x = s.xcap_last;
  text($('regroup-result'), [r && r.regroup ? 'Regroup ' + r.action + ' ' + r.regroup + ': ' +
    (r.status || 'pending') + (r.warning ? ' · ' + r.warning : '') : '',
    x && x.method && /^(POST|DELETE)$/.test(x.method) ? 'Group management ' + x.method + ': ' + (x.status || 'pending') : '']
    .filter(Boolean).join(' — '));
}
// The MCData message store (TS 24.282 clause 21.2): the objects the engine knows from synchronisation, with their
// flags and folder, the folder hierarchy, the last search, the payload read, the notification channel and the
// storage of the dispatcher's communications (clause 21.3.2).
let storeFoldersShown = '';
function renderStore(m) {
  const folders = m.folders || {};
  if (JSON.stringify(folders) !== storeFoldersShown) {
    storeFoldersShown = JSON.stringify(folders);
    const chosen = $('store-folder').value;
    $('store-folder').replaceChildren(Object.assign(document.createElement('option'), { value: '', textContent: 'no folder chosen' }),
      ...Object.entries(folders).map(([path, fid]) =>
      Object.assign(document.createElement('option'), { value: fid, textContent: path || '/', selected: fid === chosen })));
    const list = $('store-folder-list');
    list.replaceChildren();
    for (const [path, fid] of Object.entries(folders))
      item(list, path || '/', '', [['Open', '', () => storeAct({ op: 'folder', id: fid })],
        ...(path && path !== '/' ? [['Delete', '', () => storeAct({ op: 'folder-delete', id: fid })]] : [])]);
  }
  const found = new Set(m.search || []);
  const shown = new Set(m.folder && m.folder.objects ? m.folder.objects : []);
  const list = $('store-objects');
  list.replaceChildren();
  for (const [oid, o] of Object.entries(m.objects || {})) {
    if ($('store-only-found').checked && !found.has(oid)) continue;
    const flags = o.flags || [];
    const seen = flags.includes('\\Seen'), flagged = flags.includes('\\Flagged');
    const toggle = (flag, on) => storeAct({ op: on ? 'unflag' : 'flag', ids: [oid], flag });
    const target = () => $('store-folder').value;
    // The stored object's attributes (TS 24.282 clause 21.2.5A: Text, From, To, Direction, Date, Request-Type...).
    const peer = o.Direction === 'Out' ? 'to ' + (o.To || '?') : 'from ' + (o.From || '?');
    item(list, (flagged ? '★ ' : '') + (o.Text || o.Subject || oid) + (seen ? '' : ' (unread)'),
      [o['Request-Type'] || '', o.From || o.To ? peer : '', o.Date, 'in ' + (o.folder || '/'), flags.join(' '),
       found.has(oid) ? 'search result' : '', shown.has(oid) ? 'in the folder opened' : ''].filter(Boolean).join(' · '),
      [['Read', 'go', () => { storeAct({ op: 'payload', id: oid }); if (!seen) toggle('\\Seen', false); }],
       [seen ? 'Mark unread' : 'Mark read', '', () => toggle('\\Seen', seen)],
       [flagged ? 'Unflag' : 'Flag', '', () => toggle('\\Flagged', flagged)],
       ['Move to folder', '', () => target() && storeAct({ op: 'move', folder: target(), ids: [oid] })],
       ['Copy to folder', '', () => target() && storeAct({ op: 'copy', folder: target(), ids: [oid] })],
       ['Delete', 'stop', () => storeAct({ op: 'delete', ids: [oid] })]]);
  }
  const payload = m.payload;
  text($('store-view'), [m.folder && m.folder.path !== undefined ? 'Folder ' + (m.folder.path || '/') + ': ' +
    (m.folder.objects || []).length + ' object(s)' : '', m.search ? 'Search: ' + m.search.length + ' found' : '',
    payload && payload.object ? 'Payload of ' + payload.object + ': ' + payload.bytes + ' bytes' +
      (payload.content_type ? ', ' + payload.content_type.split(';')[0] : '') : '']
    .filter(Boolean).join(' — '));
  const subscribed = !!(m.subscription && m.subscription.resourceURL);
  $('store-subscribe').disabled = subscribed;
  $('store-unsubscribe').disabled = !subscribed;
  text($('store-channel'), subscribed ? 'Notifications: ' + (m.events || 0) + ' change(s) received' : '');
  const c = m.control, l = m.last;
  text($('store-last'), [c && c.action ? 'Storage ' + c.action + ': ' + c.status + (c.warning ? ' · ' + c.warning : '') : '',
    l && l.method ? 'Last request: ' + l.method + ' ' + String(l.url || '').replace(/^https?:\/\/[^/]+/, '') + ' → ' + l.status : '']
    .filter(Boolean).join(' — '));
}
const storeAct = (body) => act({ action: 'msgstore', ...body });
// The dispatcher's functional aliases (TS 24.379 clause 9A.2.1.3, from its status subscription): activated (one of
// them presented on its calls), offered for take-over, asked for.
// The MCData ones (TS 24.282 clause 22.2.1) the same way, presented on SDS, files and IP connectivity.
const ALIAS_SERVICES = [
  { action: 'fa', use: 'fa-use', active: 'active_functional_aliases', using: 'functional_alias', possible: 'takeover_possible_functional_aliases',
    last: 'last_fa_result', label: '', on: 'calls' },
  { action: 'mcdata-fa', use: 'mcdata-fa-use', active: 'mcdata_functional_aliases', using: 'mcdata_functional_alias_in_use',
    possible: 'mcdata_functional_aliases_take_over_possible', last: 'mcdata_fa_last_result', label: ' (MCData)', on: 'messages' },
];
function renderAliases(s) {
  const list = $('fa-list'), notes = [];
  list.replaceChildren();
  for (const k of ALIAS_SERVICES) {
    const active = s[k.active] || [], using = s[k.using] || '';
    for (const alias of active)
      item(list, alias + k.label, 'activated' + (alias === using ? ' · presented on ' + k.on : ''), [
        alias === using ? ['Stop using', '', () => act({ action: k.use, alias: '' })]
          : ['Use on ' + k.on, 'go', () => act({ action: k.use, alias })],
        ['Deactivate', '', () => act({ action: k.action, op: 'deactivate', aliases: [alias] })]]);
    for (const alias of s[k.possible] || [])
      item(list, alias + k.label, 'held by another user, can be taken over',
           [['Take over', 'warn', () => act({ action: k.action, op: 'takeover', aliases: [alias] })]]);
    // The alias is presented only while it is activated (TS 24.379 clause 10.1.1.2.1.1, TS 24.282 clause 9.2.1.1).
    if (using) notes.push((k.on === 'calls' ? 'Calls' : 'Messages') + ' present ' + using + (active.includes(using) ? '' : ' (not activated: not presented)'));
    const last = s[k.last];
    if (last && last.aliases && last.aliases.length) {
      const outcomes = Object.entries(last.outcomes || {}).map(([a, o]) => a + ': ' + o).join(', ');
      item(list, 'Last change' + k.label + ': ' + last.action + ' ' + last.aliases.join(' '),
           (last.status ? String(last.status) : 'pending') + (last.warning ? ' · ' + last.warning : '') +
           (outcomes ? ' · ' + outcomes : ''));
    }
  }
  text($('fa-using'), notes.join(' · '));
}
// An ad hoc group call (TS 24.379 clause 17.2): the group the server named, its priority, the last participant change
// (clause 17.2.6.1.1), which needs the call in progress.
function renderAdhoc(s, call) {
  const inAdhoc = call === 'active' && s.call_type === 'adhoc';
  $('adhoc-add').disabled = $('adhoc-remove').disabled = !inAdhoc;
  const m = s.last_modify_result;
  text($('adhoc-state'), [s.call_type === 'adhoc' && call !== 'idle' ? 'Ad hoc group ' + (s.call_group || '(being formed)') +
    (s.adhoc_priority && s.adhoc_priority !== 'normal' ? ' · ' + s.adhoc_priority : '') + (s.adhoc_broadcast ? ' · broadcast' : '') : '',
    m && m.status ? 'Last change: ' + m.status + (m.warning ? ' · ' + m.warning : '') : ''].filter(Boolean).join(' — '));
}
// The SDS session over the media plane (TS 24.282 clause 9.2.4): opened by the dispatcher or offered to it, its state,
// and the messages sent and received over the media plane.
function renderSdsSession(s) {
  const r = s.mcdata_media_result || {}, open = !!s.mcdata_media_session;
  $('session').querySelector('button').disabled = open;
  $('session-send').querySelector('button').disabled = !open;
  $('session-close').disabled = !open;
  $('session-close-all').disabled = !open || !r.adhoc;
  const peer = r.target || r.from || '', group = s.mcdata_session_group || r.group || '';
  text($('session-state'), r.state && (r.session || open) ? 'Session ' + (r.adhoc ? 'ad hoc group ' : group ? 'group ' : '') +
    (group || peer) + ': ' + r.state + (r.status ? ' · ' + r.status : '') + (r.priority && r.priority !== 'normal' ? ' · ' + r.priority : '') +
    (r.warning ? ' · ' + r.warning : '') : '');
  const list = $('session-messages');
  list.replaceChildren();
  for (const m of [...(s.mcdata_media_received || [])].reverse())
    item(list, (m.payloads || []).map((p) => p.text ?? `[${p.bytes} bytes]`).join(' '), 'from ' + (m.from || '?') + (m.group ? ' in ' + m.group : ''));
  for (const m of [...(s.mcdata_media_sent || [])].reverse()) item(list, m.text || '', 'sent');
}
// Enhanced status (TS 24.282 clause 14.2): the operational values of a group, each one sent with a click, and the
// statuses received.
function renderStatus(s) {
  const list = $('status-values');
  list.replaceChildren();
  for (const [group, v] of Object.entries(s.mcdata_enhanced_status_values || {})) {
    if (!v.allowed) { item(list, group, 'enhanced status is not allowed on this group'); continue; }
    item(list, group, (v.values || []).length ? 'send a status:' : 'no operational values',
      (v.values || []).map((o) => [o.value, '', () => act({ action: 'status', group, value: o.id })]));
  }
  const got = $('statuses');
  got.replaceChildren();
  for (const e of [...(s.mcdata_enhanced_status || [])].reverse()) item(got, 'Status: ' + e.value, 'from ' + (e.from || '?') + ' in ' + e.group);
}
// Location (TS 24.379 clause 13.3): the last report received for each user, with a link to the position on a map,
// and the answer to the last request or configuration.
function renderLocation(s) {
  const latest = {};
  for (const r of (s.location || {}).received || []) if (r.reporting_uri) latest[r.reporting_uri] = r;
  const list = $('location-list');
  list.replaceChildren();
  for (const [who, r] of Object.entries(latest)) {
    const c = r.coordinate;
    const where = c ? c.latitude.toFixed(5) + ', ' + c.longitude.toFixed(5) : 'no coordinate';
    item(list, who + (r.emergency ? ' (emergency)' : ''), [where, r.functional_alias ? 'as ' + r.functional_alias : '',
      r.serving_ecgi ? 'cell ' + r.serving_ecgi : '', r.timestamp || ''].filter(Boolean).join(' · '),
      c ? [['Map', '', 'https://www.openstreetmap.org/?mlat=' + c.latitude + '&mlon=' + c.longitude + '#map=16/' + c.latitude + '/' + c.longitude]] : []);
  }
  const r = s.location_last_result;
  text($('location-result'), r && r.action ? 'Location ' + r.action + ': ' + r.status + (r.warning ? ' · ' + r.warning : '') : '');
}
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
      // A received file is saved; another site (a map) opens apart from the page.
      if (typeof fn === 'string') { btn.href = fn; if (/^https:/.test(fn)) { btn.target = '_blank'; btn.rel = 'noopener'; } else btn.download = ''; } else btn.onclick = fn;
      row.append(btn);
    }
    div.append(row);
  }
  list.append(div);
}

// The sign-in screen (position mode, or browser: on IdMS's page): shown whenever this browser has no session.
function renderSession(session) {
  const out = !session.signed_in;
  const browser = session.mode === 'browser';
  $('signin').hidden = !out;
  $('signout').hidden = out || (session.mode !== 'position' && !browser);
  if (!out) return false;
  $('idms-login').hidden = !browser;
  text($('idms-host'), session.idms ? ', ' + session.idms : '');
  $('position-login').hidden = browser;
  if (talking) pttUp();
  text($('signin-reason'), session.reason || '');
  text($('accounts'), (session.accounts || []).join(', ') || 'none');
  text($('signin-busy'), session.busy ? 'An operator is signed in on this position; they must sign out first ' +
    '(a session whose page was closed signs out by itself after a short while).' : '');
  return true;
}

// The group picker: the groups of the user profile, the regroups and temporary groups the dispatcher was told of
// (TS 24.379 clause 16.2.1.3), and the group in use.
let groupsShown = '';
function renderGroups(current, groups, regroups) {
  const list = [...new Set([...(groups || []), ...Object.keys(regroups || {}), ...(current ? [current] : [])])];
  const key = JSON.stringify([list, current]);
  if (key === groupsShown) return;
  groupsShown = key;
  const select = $('group-select');
  select.replaceChildren(...list.map((g) => {
    const option = document.createElement('option');
    option.value = g;
    option.textContent = g + (regroups && regroups[g] ? ' (regroup)'
      : groups && !groups.includes(g) ? ' (no longer in your profile)' : '');
    option.selected = g === current;
    return option;
  }));
  select.disabled = list.length < 2;
}

function render(s) {
  state = s;
  if (renderSession(s.session || {})) return;
  const id = s.identity || {};
  const operator = s.session.operator && s.session.operator !== id.user ? s.session.operator + ' · ' : '';
  // The IdMS the operator signed in at (sign-in on IdMS's page).
  text($('identity'), operator + (id.user || '?') +
    (id.mcdata_user && id.mcdata_user !== id.user ? ' · ' + id.mcdata_user : '') +
    (s.session.idms ? ' · signed in at ' + s.session.idms : ''));
  renderGroups(id.group, s.profile_groups, s.regroups);
  $('group-select').disabled = $('group-select').options.length < 2 || (s.call_state || 'idle') !== 'idle';
  const reg = $('registration');
  reg.textContent = s.registered ? (s.affiliated ? 'registered, affiliated' : 'registered') : 'offline';
  reg.classList.toggle('off', !s.registered);

  const call = s.call_state || 'idle';
  text($('call-state'), call + (s.call_type ? ' (' + s.call_type + (s.private_peer ? ' ' + s.private_peer : '') + ')' : '') +
    (call === 'active' && s.floor_control === false ? ' · without floor control' : '') +
    // Ambient listening (TS 24.379 clause 11.1.6): the listened-to side has no visible call, so it is named here.
    (s.ambient ? ' · ambient listening: ' + (s.ambient.role === 'listening' ? 'listening to ' : 'listened to by ') +
      s.ambient.peer : '') + (call !== 'idle' ? aliasesOfCall(s) : ''));
  text($('floor-state'), (s.floor_state || '–') + (s.floor_owner && call === 'active' ? ' · ' + s.floor_owner : '') +
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
  text($('incoming-text'), 'Incoming ' + (s.call_type || '') + ' call' + (s.private_peer ? ' from ' + s.private_peer : '') +
    aliasesOfCall(s));

  const alerts = $('alerts');
  alerts.replaceChildren();
  // Another user's alert can be cancelled from here (TS 24.379 clause 12.1.1.2), by a user with the right to.
  // The engine lists the users whose emergency alert is active (TS 24.379 clause 12.1.1).
  for (const who of s.alerts || []) {
    const others = who !== id.user ? [['Cancel', '', () => act({ action: 'alert', on: false, user: who })]] : [];
    item(alerts, 'Emergency alert: ' + who, '', others);
  }
  // The call in progress raised to emergency or imminent peril, or brought back (TS 24.379 clause 10.1.1.2.1.3).
  const inCall = call === 'active';
  // A private call's emergency state is its own (TS 24.379 annex G.11, MEPC 2 requested, MEPC 3 granted).
  const priority = s.call_type === 'private'
    ? (/^MEPC [23]/.test(s.private_emergency_state || '') ? 'emergency' : 'normal') : (s.priority || 'normal');
  state.effectivePriority = priority;
  $('raise-emergency').disabled = !inCall || priority === 'emergency';
  $('raise-imminent').disabled = !inCall || priority !== 'normal' || s.call_type === 'private';
  $('priority-off').disabled = !inCall || priority === 'normal';

  // Call-back requests (TS 24.379 clause 11.1.5, annex G PCCB states): received ones are called back, sent ones
  // cancelled.
  const callbacks = $('callbacks');
  callbacks.replaceChildren();
  for (const [who, r] of Object.entries(s.callback_requests_received || {}))
    item(callbacks, 'Call-back requested by ' + who, (r.urgency || 'normal') + ' urgency' + (r.time ? ' · ' + r.time : ''),
         [['Call back', 'go', () => { audio(); act({ action: 'callback-fulfil', target: who }); }]]);
  for (const [who, st] of Object.entries(s.callback_requests_sent || {}))
    item(callbacks, 'Call-back asked of ' + who, st,
         /cancel-pending/.test(st) ? [] : [['Cancel', '', () => act({ action: 'callback-cancel', target: who })]]);

  // The conference event package of the call (TS 24.379 clause 10.1.3.2): who is connected.
  const participants = s.conference_participants || [];
  text($('participants'), call === 'active' && participants.length ? 'In the call: ' + participants.join(', ') : '');
  // MCData emergency alerts by group (TS 24.282 clause 16.2): another user's can be cancelled, with the right to.
  const mcdataAlerts = $('mcdata-alerts');
  mcdataAlerts.replaceChildren();
  if (/^MDEA [234]/.test(s.mcdata_alert_state || '')) item(mcdataAlerts, 'Own MCData alert: ' + s.mcdata_alert_state, '');
  for (const [group, users] of Object.entries(s.mcdata_alerts_received || {}))
    for (const [who, a] of Object.entries(users || {}))
      if (who !== id.mcdata_user)
        item(mcdataAlerts, 'MCData alert: ' + who, 'in ' + group + (a.organization ? ' · ' + a.organization : ''),
             [['Cancel', '', () => act({ action: 'mcdata-alert', on: false, user: who, group })]]);
  // Remotely initiated call requests: the outcome the asked user reported (TS 24.379 clause 11.1.7.2.1).
  const outcomes = $('outcomes');
  outcomes.replaceChildren();
  for (const [kind, outcome] of Object.entries(s.service_outcomes || {}))
    item(outcomes, (OUTCOMES[kind] || kind) + ': ' + outcome, '');
  // Transfer (TS 24.379 clause 11.1.8.2) and forwarding (clause 11.1.9.2) need a private call: in progress or ringing.
  const inPrivate = call === 'active' && s.call_type === 'private';
  const announcing = s.announced_transfer && s.announced_transfer.state === 'announcing';
  $('transfer-now').disabled = !inPrivate || !!announcing;
  $('transfer-announce').disabled = !inPrivate || !!announcing;
  $('transfer-complete').disabled = !announcing;
  $('transfer-cancel').disabled = !announcing;
  $('forward').disabled = !(call === 'ringing' && s.call_type === 'private');
  text($('transfer-state'), announcing ? 'Announcing the transfer of ' + s.announced_transfer.transferred + ' to ' +
    s.announced_transfer.target : (s.last_forwarding && s.last_forwarding.by ? 'Last forwarded call: by ' +
    s.last_forwarding.by + ' to ' + s.last_forwarding.target : ''));
  const idle = call === 'idle';
  for (const f of ['fta', 'ambient', 'adhoc']) $(f).querySelector('button').disabled = !idle;
  renderAdhoc(s, call);
  renderSdsSession(s);
  renderStatus(s);
  renderAliases(s);
  renderRegroups(s);
  renderLocation(s);
  renderStore(s.msgstore || {});

  const inbox = $('inbox');
  inbox.replaceChildren();
  for (const m of [...(s.mcdata_inbox || [])].reverse()) {
    const words = (m.payloads || []).map((p) => p.text ?? `[${p.bytes} bytes]`).join(' ');
    // TS 24.282 clause 9.2.1.2 step 6c: the sender's functional alias, and the one the dispatcher was reached through.
    item(inbox, words, 'from ' + (m.from || '?') + (m.functional_alias ? ' as ' + m.functional_alias : '') +
         (m.called_functional_alias ? ' to ' + m.called_functional_alias : '') + (m.group ? ' in ' + m.group : ''));
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
  if (b.dataset.level) body.level = b.dataset.level;
  if (body.action === 'call' && $('chat').checked) body.chat = true;
  act(body);
}));
$('answer').onclick = () => { audio(); act({ action: 'answer' }); };
// Back to normal: the call's emergency or imminent peril condition cancelled.
$('priority-off').onclick = () => act({ action: 'priority', level: state.effectivePriority === 'imminent' ? 'imminent' : 'emergency', on: false });
$('reject').onclick = () => act({ action: 'reject' });
// TS 24.379 clause 11.1.5.2.1: ask the user in the private call field to call the dispatcher back.
$('callback-request').onclick = () => {
  const f = $('private');
  if (f.target.reportValidity()) act({ action: 'callback', target: f.target.value.trim(), urgency: f.urgency.value });
};
$('chat').onchange = () => { $('broadcast').disabled = $('chat').checked; };
$('fta').onsubmit = (e) => {
  e.preventDefault();
  audio();
  const f = e.target;
  act(f.to_alias.checked ? { action: 'first-to-answer', alias: f.targets.value.trim(), emergency: f.emergency.checked }
    : { action: 'first-to-answer', targets: f.targets.value.trim().split(/[\s,]+/), emergency: f.emergency.checked });
};
$('adhoc').onsubmit = (e) => {
  e.preventDefault();
  audio();
  act({ action: 'adhoc', kind: e.target.kind.value, targets: e.target.targets.value.trim().split(/[\s,]+/) });
};
for (const key of ['add', 'remove'])
  $('adhoc-' + key).onclick = () => {
    const users = $('adhoc').change.value.trim();
    if (users) act({ action: 'adhoc-modify', [key]: users.split(/[\s,]+/) });
  };
$('ambient').onsubmit = (e) => {
  e.preventDefault();
  audio();
  act({ action: 'ambient', kind: e.target.kind.value, target: e.target.target.value.trim() });
};
$('remote').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, body = { action: f.kind.value, remote: f.remote.value.trim(), target: f.target.value.trim(),
                               silent: f.silent.checked };
  if (f.kind.value === 'remote-call' && f.mode.value) body.mode = f.mode.value;
  act(body);
};
$('remote').kind.onchange = (e) => { $('remote').mode.disabled = e.target.value !== 'remote-call'; };
// The transfer target is the one being announced to, once announcing.
function transfer(step) {
  const f = $('transfer');
  const target = state.announced_transfer && state.announced_transfer.target || f.target.value.trim();
  if (!target) { f.target.reportValidity(); return; }
  act(step ? { action: 'transfer', target, step } : { action: 'transfer', target });
}
$('transfer-now').onclick = () => transfer('');
$('transfer-announce').onclick = () => transfer('announced');
$('transfer-complete').onclick = () => transfer('complete');
$('transfer-cancel').onclick = () => transfer('cancel');
$('forward').onclick = () => {
  const f = $('transfer');
  if (f.target.reportValidity()) act({ action: 'forward', target: f.target.value.trim() });
};
$('private').onsubmit = (e) => {
  e.preventDefault();
  audio();
  const f = e.target;
  act({ action: 'private', target: f.target.value.trim(), emergency: f.emergency.checked, no_floor: f.no_floor.checked,
        to_alias: f.to_alias.checked });
};
// The engine has no emergency call to a functional alias.
for (const form of ['private', 'fta']) {
  const f = $(form);
  f.to_alias.onchange = () => { f.emergency.disabled = f.to_alias.checked; if (f.to_alias.checked) f.emergency.checked = false; };
}
function aliasChange(op) {
  const f = $('fa');
  if (f.alias.reportValidity()) act({ action: f.service.value === 'mcdata' ? 'mcdata-fa' : 'fa', op, aliases: [f.alias.value.trim()] });
}
// A functional alias is not a group.
for (const form of ['sds', 'fd']) {
  const f = $(form);
  f.to_alias.onchange = () => { f.group.disabled = f.to_alias.checked; if (f.to_alias.checked) f.group.checked = false; };
  f.group.onchange = () => { f.to_alias.disabled = f.group.checked; if (f.group.checked) f.to_alias.checked = false; };
}
$('location').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, targets = f.targets.value.trim();
  act(f.to_alias.checked ? { action: 'location-request', alias: targets, refresh: f.refresh.checked }
    : { action: 'location-request', targets: targets.split(/[\s,]+/), refresh: f.refresh.checked });
};
$('location-configure').onclick = () => {
  const f = $('location');
  if (f.targets.reportValidity() && f.periodic.reportValidity())
    act({ action: 'location-configure', targets: f.targets.value.trim().split(/[\s,]+/), periodic: Number(f.periodic.value) });
};
$('regroup').kind.onchange = (e) => {
  const f = $('regroup'), temporary = e.target.value === 'temporary';
  f.preconfigured.disabled = temporary;
  f.chat.disabled = f.protected.disabled = !temporary;
  f.members.placeholder = e.target.value === 'users' ? 'MCPTT IDs, separated by spaces' : 'groups, separated by spaces';
};
$('regroup').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, tgi = f.tgi.value.trim(), members = f.members.value.trim().split(/[\s,]+/);
  if (f.kind.value === 'temporary') {
    formed.add(tgi);
    keepFormed();
    act({ action: 'temporary', op: 'create', tgi, groups: members, chat: f.chat.checked, protected: f.protected.checked });
  } else {
    if (!f.preconfigured.value.trim()) { f.preconfigured.setCustomValidity('a preconfigured group is needed');
      f.preconfigured.reportValidity(); f.preconfigured.setCustomValidity(''); return; }
    act({ action: 'regroup', op: f.kind.value, tgi, preconfigured: f.preconfigured.value.trim(), members });
  }
};
$('store-sync').onclick = () => storeAct({ op: 'sync' });
$('store-folders').onclick = () => storeAct({ op: 'folders' });
$('store-subscribe').onclick = () => storeAct({ op: 'subscribe' });
$('store-unsubscribe').onclick = () => storeAct({ op: 'unsubscribe' });
$('store-only-found').onchange = () => renderStore(state.msgstore || {});
$('store-new-folder').onsubmit = (e) => {
  e.preventDefault();
  const parent = $('store-folder').value;
  storeAct(parent ? { op: 'folder-create', name: e.target.name.value.trim(), parent } : { op: 'folder-create', name: e.target.name.value.trim() });
  e.target.name.value = '';
};
$('store-search').onsubmit = (e) => {
  e.preventDefault();
  storeAct({ op: 'search', criteria: e.target.criteria.value.trim().split(/\s+/) });
};
$('store-upload').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, body = new FormData();
  body.append('file', f.file.files[0]);
  if ($('store-folder').value) body.append('folder', $('store-folder').value);
  post('/api/msgstore/upload', body, false).then(() => { f.file.value = ''; }).catch((err) => log('error: ' + err.message));
};
for (const [id, op] of [['store-enable', 'enable'], ['store-disable', 'disable']])
  $(id).onclick = () => act({ action: 'msgstore-ctrl', op, all_private: $('store-private').checked, all_group: $('store-group').checked });
$('fa-activate').onclick = () => aliasChange('activate');
$('fa').onsubmit = (e) => { e.preventDefault(); aliasChange('activate'); };
$('fa-takeover').onclick = () => aliasChange('takeover');
$('fa-deactivate').onclick = () => aliasChange('deactivate');
$('sds').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  post('/api/command', { action: 'sds', target: f.target.value.trim(), group: f.group.checked, to_alias: f.to_alias.checked,
                         text: f.text.value })
    .then(() => { f.text.value = ''; }).catch((err) => log('error: ' + err.message));
};
$('session').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, kind = f.kind.value, target = f.target.value.trim();
  act(kind === 'adhoc' ? { action: 'sds-session', op: 'open', adhoc: true, targets: target.split(/[\s,]+/) }
    : { action: 'sds-session', op: 'open', target, group: kind === 'group', priority: f.priority.value });
};
// No emergency or imminent peril ad hoc group SDS session; imminent peril is a group communication.
$('session').kind.onchange = (e) => {
  const p = $('session').priority;
  for (const o of p.options) o.disabled = o.value !== 'normal' && (e.target.value === 'adhoc' || (o.value === 'imminent' && e.target.value === 'private'));
  if (p.selectedOptions[0].disabled) p.value = 'normal';
};
$('session').kind.onchange({ target: $('session').kind });
$('session-send').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target;
  post('/api/command', { action: 'sds-session', op: 'send', text: f.text.value, disposition: f.disposition.value })
    .then(() => { f.text.value = ''; }).catch((err) => log('error: ' + err.message));
};
$('session-close').onclick = () => act({ action: 'sds-session', op: 'close' });
$('session-close-all').onclick = () => act({ action: 'sds-session', op: 'close', all: true });
$('status').onsubmit = (e) => { e.preventDefault(); act({ action: 'status-values', group: e.target.group.value.trim() }); };
$('fd').onsubmit = (e) => {
  e.preventDefault();
  const f = e.target, body = new FormData();
  body.append('file', f.file.files[0]);
  body.append('target', f.target.value.trim());
  body.append('group', f.group.checked);
  body.append('to_alias', f.to_alias.checked);
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
