'use strict';

// ── State ─────────────────────────────────────────────────────────────────────

let mode = 'sheet';
let operations = [];
let sheetFiles = [];          // File objects for single-sheet mode
let sheetChoice = null;       // name of the file that is the sheet
let driverWorkspace = null;   // { id, files, drivers } after a folder upload
let driverRunnable = false;
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

// ── Start-up ──────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', async () => {
  setupDropZone();
  try {
    operations = await api('/api/operations');
    fillOperations();
    await loadCredentials();
  } catch (err) {
    appendLog('ERROR Couldn\'t load the UI: ' + err.message);
  }
  // A run started earlier (e.g. before a page reload) keeps its log
  const current = await api('/api/runs/current').catch(() => null);
  if (current) {
    showRun(current);
    if (current.state === 'RUNNING') poll(current.lines.length);
  }
});

function setMode(next) {
  mode = next;
  document.getElementById('tab-sheet').classList.toggle('active', mode === 'sheet');
  document.getElementById('tab-driver').classList.toggle('active', mode === 'driver');
  document.getElementById('mode-sheet').classList.toggle('hidden', mode !== 'sheet');
  document.getElementById('mode-driver').classList.toggle('hidden', mode !== 'driver');
  updateRunButton();
}

// ── Credentials ───────────────────────────────────────────────────────────────

async function loadCredentials() {
  const info = await api('/api/credentials');
  const select = document.getElementById('credentials');
  const previous = select.value;
  select.innerHTML = '';

  info.files.forEach(file => {
    const option = document.createElement('option');
    option.value = file.name;
    option.textContent = file.error ? file.name + ' — ' + file.error : file.name + ' — ' + file.company + ' (' + file.tenantType + ')';
    option.disabled = !!file.error;
    select.appendChild(option);
  });
  if (previous && info.files.some(f => f.name === previous && !f.error)) select.value = previous;

  document.getElementById('credentials-note').textContent = info.files.length
    ? 'From ' + info.folder
    : 'No credentials files in ' + info.folder + '. Create one with: mainClass --set-credential ' + info.folder + '/<tenant>.yaml';
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
  onOperationChange();
}

function currentOperation() {
  return operations.find(op => op.name === document.getElementById('operation').value);
}

function onOperationChange() {
  const op = currentOperation();
  if (!op) return;

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
}

// ── Single sheet: files ───────────────────────────────────────────────────────

function setupDropZone() {
  const zone = document.getElementById('drop-zone');
  zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('drag-over'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag-over'));
  zone.addEventListener('drop', e => {
    e.preventDefault();
    zone.classList.remove('drag-over');
    addFiles(e.dataTransfer.files);
  });
}

function addFiles(list) {
  Array.from(list).forEach(file => {
    sheetFiles = sheetFiles.filter(f => f.name !== file.name);
    sheetFiles.push(file);
  });
  if (!sheetChoice || !sheetFiles.some(f => f.name === sheetChoice)) {
    const firstCsv = sheetFiles.find(f => f.name.toLowerCase().endsWith('.csv'));
    sheetChoice = firstCsv ? firstCsv.name : null;
  }
  renderFiles();
}

function removeFile(name) {
  sheetFiles = sheetFiles.filter(f => f.name !== name);
  if (sheetChoice === name) {
    const firstCsv = sheetFiles.find(f => f.name.toLowerCase().endsWith('.csv'));
    sheetChoice = firstCsv ? firstCsv.name : null;
  }
  renderFiles();
}

function renderFiles() {
  const list = document.getElementById('file-list');
  list.innerHTML = '';

  sheetFiles.forEach(file => {
    const chip = document.createElement('label');
    chip.className = 'file-chip';
    const isCsv = file.name.toLowerCase().endsWith('.csv');
    chip.innerHTML =
      '<input type="radio" name="sheet" ' + (file.name === sheetChoice ? 'checked ' : '') + (isCsv ? '' : 'disabled ') + '>' +
      '<span class="file-name">' + escHtml(file.name) + '</span>' +
      '<span class="file-role">' + (file.name === sheetChoice ? 'sheet' : 'data file') + '</span>' +
      '<button type="button" class="file-remove" title="Remove">×</button>';
    chip.querySelector('input').addEventListener('change', () => { sheetChoice = file.name; renderFiles(); });
    chip.querySelector('button').addEventListener('click', e => { e.preventDefault(); removeFile(file.name); });
    list.appendChild(chip);
  });
  updateRunButton();
}

// ── Driver: folder, preflight ─────────────────────────────────────────────────

async function uploadFolder(list) {
  const files = Array.from(list);
  if (!files.length) return;

  driverWorkspace = null;
  driverRunnable = false;
  document.getElementById('preflight-card').classList.add('hidden');
  document.getElementById('folder-note').textContent = 'Uploading ' + files.length + ' files…';

  try {
    const form = new FormData();
    files.forEach(file => {
      form.append('files', file);
      form.append('paths', file.webkitRelativePath || file.name);
    });
    driverWorkspace = await api('/api/workspaces', { method: 'POST', body: form });
  } catch (err) {
    document.getElementById('folder-note').textContent = err.message;
    updateRunButton();
    return;
  }

  const drivers = driverWorkspace.drivers;
  document.getElementById('folder-note').textContent = files.length + ' files uploaded. ' +
    (drivers.length ? '' : 'None of them is a driver (a CSV with operation and pathToInput columns).');

  const select = document.getElementById('driver');
  select.innerHTML = '';
  drivers.forEach(d => {
    const option = document.createElement('option');
    option.value = option.textContent = d;
    select.appendChild(option);
  });
  document.getElementById('driver-group').classList.toggle('hidden', drivers.length === 0);
  if (drivers.length) await checkDriver();
  updateRunButton();
}

async function checkDriver() {
  const driver = document.getElementById('driver').value;
  const card = document.getElementById('preflight-card');
  const body = document.getElementById('preflight');
  driverRunnable = false;

  try {
    const rows = await api('/api/workspaces/' + driverWorkspace.id + '/check', { method: 'POST', json: { driver } });
    const ok = rows.filter(r => r.runnable).length;
    driverRunnable = ok > 0;
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
    ? !!(sheetChoice && document.getElementById('credentials').value && currentOperation())
    : !!(driverWorkspace && driverRunnable);
  btn.disabled = polling || !ready;
  document.getElementById('btn-text').textContent = polling ? 'Running…' : (mode === 'sheet' ? 'Run sheet' : 'Run driver');
}

async function run() {
  clearLogs();
  try {
    if (mode === 'sheet') {
      const form = new FormData();
      sheetFiles.forEach(file => { form.append('files', file); form.append('paths', file.name); });
      const workspace = await api('/api/workspaces', { method: 'POST', body: form });
      await api('/api/workspaces/' + workspace.id + '/runs/sheet', { method: 'POST', json: {
        operation: document.getElementById('operation').value,
        apiVersion: document.getElementById('apiVersion').value,
        credentials: document.getElementById('credentials').value,
        sheet: sheetChoice
      }});
    } else {
      await api('/api/workspaces/' + driverWorkspace.id + '/runs/driver', { method: 'POST',
        json: { driver: document.getElementById('driver').value } });
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
      link.href = '/api/workspaces/' + snapshot.workspaceId + '/files?path=' + encodeURIComponent(path);
      link.className = 'output-link';
      link.textContent = path;
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
