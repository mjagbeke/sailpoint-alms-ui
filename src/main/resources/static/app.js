'use strict';

// ── State ─────────────────────────────────────────────────────────────────────

let mode = 'sheet';
let operations = [];
const picked = { sheet: null, driver: null };   // paths relative to the work folder
let driverRows = { ok: 0, total: 0 };
let polling = false;
let logLineCount = 0;

// ── API ───────────────────────────────────────────────────────────────────────

// Every POST carries X-ALMS-Request; the server refuses POSTs without it, so other web pages can't start runs
async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers);
  if (options.method && options.method !== 'GET') headers['X-ALMS-Request'] = '1';
  if (options.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(options.json);
  }
  const response = await fetch(path, Object.assign({}, options, { headers }));
  if (response.status === 204) return null;
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || ('HTTP ' + response.status));
  return body;
}

// localStorage can be unavailable (private windows, blocked storage); the page works without it
function remember(key, value) { try { localStorage.setItem('alms.' + key, value); } catch (e) {} }
function recall(key) { try { return localStorage.getItem('alms.' + key); } catch (e) { return null; } }

// ── Start-up ──────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', async () => {
  try {
    operations = await api('/api/operations');
    fillOperations();
    await loadTenants();
  } catch (err) {
    appendLog('ERROR Couldn\'t load the UI: ' + err.message);
  }
  // A run started earlier (e.g. before a page reload) keeps its log
  const current = await api('/api/runs/current').catch(() => null);
  if (current) {
    showRun(current);
    if (current.state === 'RUNNING') poll(current.lines.length);
  }
  updateRunButton();
});

function setMode(next) {
  mode = next;
  document.getElementById('tab-sheet').classList.toggle('active', mode === 'sheet');
  document.getElementById('tab-driver').classList.toggle('active', mode === 'driver');
  document.getElementById('mode-sheet').classList.toggle('hidden', mode !== 'sheet');
  document.getElementById('mode-driver').classList.toggle('hidden', mode !== 'driver');
  updateRunButton();
}

// ── Tenants ───────────────────────────────────────────────────────────────────

async function loadTenants() {
  const info = await api('/api/tenants');
  const select = document.getElementById('tenant');
  const previous = select.value || recall('tenant');
  select.innerHTML = '';

  info.tenants.forEach(t => {
    const option = document.createElement('option');
    option.value = t.name;
    option.textContent = t.error ? t.name + ' — ' + t.error : t.name + ' — ' + t.company + ' (' + t.tenantType + ')';
    option.disabled = !!t.error;
    select.appendChild(option);
  });
  if (previous && info.tenants.some(t => t.name === previous && !t.error)) select.value = previous;

  document.getElementById('tenant-note').textContent = info.tenants.length
    ? 'From ' + info.folder
    : 'No credentials files in ' + info.folder + '. Create one with: --set-credential <tenant>';
  updateRunButton();
}

// ── Operations ────────────────────────────────────────────────────────────────

function fillOperations() {
  const select = document.getElementById('operation');
  select.innerHTML = '';
  const groups = {};

  operations.forEach(op => {
    if (!groups[op.group]) {
      groups[op.group] = document.createElement('optgroup');
      groups[op.group].label = op.group;
      select.appendChild(groups[op.group]);
    }
    const option = document.createElement('option');
    option.value = op.name;
    option.textContent = op.label;
    groups[op.group].appendChild(option);
  });
  if (recall('operation') && operations.some(op => op.name === recall('operation'))) select.value = recall('operation');
  onOperationChange();
}

function currentOperation() {
  return operations.find(op => op.name === document.getElementById('operation').value);
}

function onOperationChange() {
  const op = currentOperation();
  if (!op) return;
  remember('operation', op.name);

  const versions = document.getElementById('apiVersion');
  versions.innerHTML = '';
  op.versions.forEach(v => {
    const option = document.createElement('option');
    option.value = option.textContent = v;
    versions.appendChild(option);
  });

  document.getElementById('hint-columns').innerHTML = op.columns.map(c => '<span class="hint-col">' + escHtml(c) + '</span>').join('');
  document.getElementById('template-link').href = '/api/operations/' + encodeURIComponent(op.name) + '/template';
  document.getElementById('operation-hint').classList.remove('hidden');
  updateRunButton();
}

// ── File browser ──────────────────────────────────────────────────────────────

// Lists the work folder's folders and CSV files. In driver mode only drivers can be chosen.
async function openBrowser(kind, path) {
  const box = document.getElementById('browser-' + kind);
  const start = path !== undefined ? path : (recall('folder.' + kind) || '');
  let listing;

  try {
    listing = await api('/api/files?path=' + encodeURIComponent(start));
  } catch (err) {
    if (path === undefined && start) return openBrowser(kind, '');   // the remembered folder has gone
    box.innerHTML = '<div class="browser-error">' + escHtml(err.message) + '</div>';
    box.classList.remove('hidden');
    return;
  }
  remember('folder.' + kind, listing.path);

  const crumbs = ['<button type="button" class="crumb" data-path="">' + escHtml(baseName(listing.root)) + '</button>'];
  let acc = '';
  listing.path.split('/').filter(Boolean).forEach(part => {
    acc = acc ? acc + '/' + part : part;
    crumbs.push('<button type="button" class="crumb" data-path="' + escHtml(acc) + '">' + escHtml(part) + '</button>');
  });

  const items = [];
  if (listing.parent !== null) items.push('<li class="entry folder" data-folder="' + escHtml(listing.parent) + '">..</li>');
  listing.entries.forEach(e => {
    if (e.folder) {
      items.push('<li class="entry folder" data-folder="' + escHtml(e.path) + '">' + escHtml(e.name) + '</li>');
    } else {
      const choosable = kind === 'sheet' || e.driver;
      items.push('<li class="entry file' + (choosable ? '' : ' disabled') + (e.path === picked[kind] ? ' chosen' : '') +
        '" data-file="' + escHtml(e.path) + '">' + escHtml(e.name) + (e.driver ? '<span class="tag">driver</span>' : '') + '</li>');
    }
  });
  if (!listing.entries.length) items.push('<li class="entry empty">No folders or CSV files here.</li>');

  box.innerHTML =
    '<div class="crumbs">' + crumbs.join('<span class="crumb-sep">/</span>') +
    '<button type="button" class="link-btn browser-close">Close</button></div>' +
    '<ul class="entries">' + items.join('') + '</ul>';
  box.classList.remove('hidden');

  box.querySelectorAll('.crumb').forEach(b => b.addEventListener('click', () => openBrowser(kind, b.dataset.path)));
  box.querySelectorAll('[data-folder]').forEach(li => li.addEventListener('click', () => openBrowser(kind, li.dataset.folder)));
  box.querySelectorAll('.entry.file:not(.disabled)').forEach(li => li.addEventListener('click', () => choose(kind, li.dataset.file)));
  box.querySelector('.browser-close').addEventListener('click', () => box.classList.add('hidden'));
}

function choose(kind, path) {
  picked[kind] = path;
  document.getElementById(kind + '-picked').textContent = path;
  document.getElementById(kind + '-picked').classList.add('set');
  document.getElementById('browser-' + kind).classList.add('hidden');
  if (kind === 'driver') checkDriver();
  updateRunButton();
}

function baseName(path) {
  const parts = path.split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : path;
}

// ── Driver: row check ─────────────────────────────────────────────────────────

async function checkDriver() {
  if (!picked.driver) return;
  const card = document.getElementById('preflight-card');
  const body = document.getElementById('preflight');
  driverRows = { ok: 0, total: 0 };

  try {
    const rows = await api('/api/drivers/check', { method: 'POST', json: { driver: picked.driver } });
    const ok = rows.filter(r => r.runnable).length;
    driverRows = { ok, total: rows.length };
    document.getElementById('preflight-summary').textContent = ok + ' of ' + rows.length + ' will run';

    body.innerHTML = rows.length ? '' : '<p class="file-note">No row has both Execute and pathToInput filled.</p>';
    rows.forEach(row => {
      const div = document.createElement('div');
      div.className = 'preflight-row ' + (row.runnable ? 'ok' : 'skip');
      div.innerHTML =
        '<div class="preflight-head"><span class="preflight-num">' + row.number + '</span>' +
        '<span class="preflight-op">' + escHtml(row.operation || '(no operation)') + '</span>' +
        (row.runnable ? '<span class="preflight-meta">' + escHtml(row.tenant + ' · ' + row.apiVersion) + '</span>' : '') + '</div>' +
        (row.notes ? '<div class="preflight-notes">' + escHtml(row.notes) + '</div>' : '') +
        (row.runnable
          ? '<div class="preflight-sheets">' + row.sheets.map(escHtml).join('<br>') + '</div>'
          : '<div class="preflight-error">Skipped: ' + escHtml(row.error) + '</div>');
      body.appendChild(div);
    });
  } catch (err) {
    body.innerHTML = '<div class="preflight-error">' + escHtml(err.message) + '</div>';
    document.getElementById('preflight-summary').textContent = '';
  }
  card.classList.remove('hidden');
  updateRunButton();
}

// ── Run ───────────────────────────────────────────────────────────────────────

function updateRunButton() {
  const btn = document.getElementById('run-btn');
  const ready = mode === 'sheet'
    ? !!(picked.sheet && document.getElementById('tenant').value && currentOperation())
    : driverRows.ok > 0;
  btn.disabled = polling || !ready;

  const partial = mode === 'driver' && driverRows.ok < driverRows.total;
  document.getElementById('btn-text').textContent = polling ? 'Running…'
    : mode === 'sheet' ? 'Run sheet'
    : partial ? 'Run ' + driverRows.ok + ' of ' + driverRows.total + ' rows' : 'Run driver';
}

async function run() {
  // Skipped rows are often a driver's setup or teardown, so running the rest can fail or leave objects behind
  if (mode === 'driver' && driverRows.ok < driverRows.total &&
      !confirm((driverRows.total - driverRows.ok) + ' of ' + driverRows.total + ' rows will be skipped. ' +
               'If they are setup or teardown rows, the others may fail or leave objects in the tenant. Run the other ' +
               driverRows.ok + ' rows anyway?')) {
    return;
  }

  clearLogs();
  try {
    if (mode === 'sheet') {
      remember('tenant', document.getElementById('tenant').value);
      await api('/api/runs/sheet', { method: 'POST', json: {
        operation: document.getElementById('operation').value,
        apiVersion: document.getElementById('apiVersion').value,
        tenant: document.getElementById('tenant').value,
        sheet: picked.sheet
      }});
    } else {
      await api('/api/runs/driver', { method: 'POST', json: { driver: picked.driver } });
    }
  } catch (err) {
    appendLog('ERROR ' + err.message);
    setBadge('error', 'Not started');
    return;
  }
  poll(0);
}

async function poll(from) {
  polling = true;
  setBadge('running', 'Running');
  updateRunButton();
  document.getElementById('btn-spinner').classList.remove('hidden');

  let next = from;
  while (true) {
    let snapshot;
    try {
      snapshot = await api('/api/runs/current?from=' + next);
    } catch (err) {
      appendLog('ERROR Lost the run: ' + err.message);
      break;
    }
    if (!snapshot) break;
    if (next === 0) document.getElementById('log-title').textContent = snapshot.label;
    snapshot.lines.forEach(line => appendLog(line));
    next = snapshot.from + snapshot.lines.length;
    if (snapshot.state !== 'RUNNING') { finish(snapshot); break; }
    await new Promise(resolve => setTimeout(resolve, 700));
  }

  polling = false;
  document.getElementById('btn-spinner').classList.add('hidden');
  updateRunButton();
}

function showRun(snapshot) {
  clearLogs();
  document.getElementById('log-title').textContent = snapshot.label;
  snapshot.lines.forEach(line => appendLog(line));
  if (snapshot.state !== 'RUNNING') finish(snapshot);
}

function finish(snapshot) {
  if (snapshot.state === 'FAILED' || snapshot.errors > 0) {
    setBadge('error', snapshot.state === 'FAILED' ? 'Stopped' : snapshot.errors + (snapshot.errors === 1 ? ' error' : ' errors'));
  } else if (snapshot.warnings > 0) {
    setBadge('warn', snapshot.warnings + (snapshot.warnings === 1 ? ' warning' : ' warnings'));
  } else {
    setBadge('success', 'Complete');
  }

  const outputs = document.getElementById('outputs');
  outputs.innerHTML = '';
  if (snapshot.outputs.length) {
    outputs.innerHTML = '<span class="outputs-title">Output files</span>';
    snapshot.outputs.forEach(path => {
      const link = document.createElement('a');
      link.href = '/api/runs/' + snapshot.id + '/files?path=' + encodeURIComponent(path);
      link.className = 'output-link';
      link.title = path;
      link.textContent = baseName(path);
      outputs.appendChild(link);
    });
  }
  outputs.classList.toggle('hidden', snapshot.outputs.length === 0);
}

function setBadge(kind, text) {
  const badge = document.getElementById('status-badge');
  badge.className = 'status-badge status-' + kind;
  badge.textContent = text;
}

// ── Log ───────────────────────────────────────────────────────────────────────

function appendLog(line) {
  const output = document.getElementById('log-output');
  const empty = output.querySelector('.log-empty');
  if (empty) empty.remove();

  const level = line.split(' ', 1)[0].toLowerCase();
  const div = document.createElement('div');
  div.className = 'log-line log-' + (['info', 'warn', 'error'].includes(level) ? level : 'default');
  div.textContent = line;
  output.appendChild(div);

  logLineCount++;
  document.getElementById('log-count').textContent = logLineCount + (logLineCount === 1 ? ' line' : ' lines');
  output.scrollTop = output.scrollHeight;
}

function clearLogs() {
  document.getElementById('log-output').innerHTML = '<div class="log-empty">No output yet — run a sheet or a driver to see its log here.</div>';
  document.getElementById('log-title').textContent = 'Log Output';
  document.getElementById('outputs').classList.add('hidden');
  logLineCount = 0;
  document.getElementById('log-count').textContent = '0 lines';
  if (!polling) setBadge('idle', 'Ready');
}

function copyLogs() {
  const lines = Array.from(document.querySelectorAll('#log-output .log-line')).map(el => el.textContent).join('\n');
  navigator.clipboard.writeText(lines).catch(() => {});
}

function escHtml(str) {
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
