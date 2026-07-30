'use strict';

// ── Operations registry ──────────────────────────────────────────────────────

const OPERATIONS = [
  {
    group: 'Governance Groups',
    ops: [
      { value: 'generategovernancegroups', label: 'Generate / Update',
        columns: ['Governance Group Name', 'Owner', 'Description', 'Approvers (;-separated)'] },
      { value: 'deletegovernancegroups', label: 'Delete',
        columns: ['Governance Group Name'] },
      { value: 'outputgovernancegroups', label: 'Export All to CSV',
        columns: ['Output Path'] },
    ]
  },
  {
    group: 'Access Profiles',
    ops: [
      { value: 'generateaccessprofiles', label: 'Generate / Update',
        columns: ['Access Profile by Name', 'Access Profile by ID', 'Description', 'Source by Name',
          'Source by ID', 'Entitlements by Name', 'Entitlements by Id',
          'Access Profile Owner by Name', 'Access Profile Owner by ID',
          'Required User Request Comments', 'Required User Request Denied Comments',
          'Request Approval Schemes by Name', 'Request Approval Schemes by ID',
          'Required User Revoke Comments', 'Required User Revoke Denied Comments',
          'Revoke Approval Schemes by Name', 'Revoke Approval Schemes by ID',
          'Access Profile Enabled', 'Access Profile Requestable'] },
      { value: 'deleteaccessprofiles', label: 'Delete',
        columns: ['Access Profile by Name', 'Access Profile by ID'] },
      { value: 'outputaccessprofiles', label: 'Export All to CSV',
        columns: ['Output Path'] },
    ]
  },
  {
    group: 'Sources',
    ops: [
      { value: 'generatesources', label: 'Generate / Update',
        columns: ['Name', 'ID', 'Description', 'Owner By Name', 'Owner By ID', 'Cluster',
          'Type', 'Connector', 'Connector Class', 'Connector Attributes',
          'Management Workgroup', 'Category', '… (see full template)'] },
      { value: 'deletesources', label: 'Delete',
        columns: ['Name', 'ID'] },
      { value: 'importentitlements', label: 'Import Entitlements',
        columns: ['Source', 'Reset Type', 'Path to Entitlements'] },
      { value: 'importaccounts', label: 'Import Accounts',
        columns: ['Source', 'Reset Type', 'Path to Accounts'] },
      { value: 'outputsources', label: 'Export All to CSV',
        columns: ['Output Path'] },
    ]
  },
  {
    group: 'Applications',
    ops: [
      { value: 'generateapplications', label: 'Generate / Update', columns: ['See application template'] },
      { value: 'applicationoutput',    label: 'Export Output',      columns: ['See application template'] },
      { value: 'applicationdelete',    label: 'Delete',             columns: ['See application template'] },
    ]
  },
  {
    group: 'SP Config',
    ops: [
      { value: 'importspconfig', label: 'Import SP Config',
        columns: ['Exclude Types', 'Include Types', 'Object Options', 'Description',
          'Replacement Value Paths', 'Replacement Values'] },
    ]
  },
  {
    group: 'SP Config Scenarios',
    ops: [
      { value: 'reassignowner', label: 'Reassign Owner',
        columns: ['Object Type', 'Object Names (;-separated)', 'New Owner Name'] },
      { value: 'swapcorrelationconfig', label: 'Swap Correlation Config',
        columns: ['Source Name', 'Correlation Config Name', 'Correlation Config ID',
          'Manager Correlation Rule Name', 'Manager Correlation Rule ID'] },
      { value: 'swapmanagementworkgroup', label: 'Swap Management Workgroup',
        columns: ['Object Type', 'Object Names (;-separated)', 'New Workgroup Name'] },
      { value: 'swapcluster', label: 'Swap Cluster',
        columns: ['Source Name', 'New Cluster Name', 'New Cluster ID'] },
      { value: 'migratesodpolicies', label: 'Migrate SOD Policies',
        columns: ['Policy Name (blank = all)', 'New Owner Name'] },
      { value: 'promoteroles', label: 'Promote Roles',
        columns: ['Role Name (blank = all)', 'New Owner Name'] },
    ]
  },
  {
    group: 'Access Requests',
    ops: [
      { value: 'generateaccessrequest', label: 'Generate',
        columns: ['requestedFor', 'requestType', 'comments', 'requestedItems'] },
      { value: 'cancelaccessrequest', label: 'Cancel',
        columns: ['requestedFor', 'access', 'Comments'] },
    ]
  },
];

// ── State ────────────────────────────────────────────────────────────────────

let selectedFiles = [];
let logLineCount  = 0;
let isRunning     = false;

const PERSIST_FIELDS = ['company', 'tenantType', 'authType', 'apiVersion', 'clientId', 'operation'];
const STORAGE_PREFIX  = 'alms_';

// ── Init ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  populateOperations();
  loadFromStorage();
  onOperationChange();
  setupDragDrop();

  document.getElementById('run-form').addEventListener('submit', onSubmit);
});

// ── Operations dropdown ───────────────────────────────────────────────────────

function populateOperations() {
  const select = document.getElementById('operation');
  OPERATIONS.forEach(group => {
    const optgroup = document.createElement('optgroup');
    optgroup.label = group.group;
    group.ops.forEach(op => {
      const option = document.createElement('option');
      option.value = op.value;
      option.textContent = op.label;
      optgroup.appendChild(option);
    });
    select.appendChild(optgroup);
  });
}

function onOperationChange() {
  const value   = document.getElementById('operation').value;
  const hint    = document.getElementById('operation-hint');
  const cols    = document.getElementById('hint-columns');
  const opData  = OPERATIONS.flatMap(g => g.ops).find(o => o.value === value);

  if (opData && opData.columns.length > 0) {
    cols.innerHTML = opData.columns.map(c => `<span class="hint-col">${escHtml(c)}</span>`).join('');
    hint.classList.remove('hidden');
  } else {
    hint.classList.add('hidden');
  }

  if (document.getElementById('save-creds').checked) saveToStorage();
}

// ── localStorage ─────────────────────────────────────────────────────────────

function saveToStorage() {
  PERSIST_FIELDS.forEach(id => {
    const el = document.getElementById(id);
    if (el) localStorage.setItem(STORAGE_PREFIX + id, el.value);
  });
  localStorage.setItem(STORAGE_PREFIX + 'save', '1');
}

function loadFromStorage() {
  const saved = localStorage.getItem(STORAGE_PREFIX + 'save') === '1';
  document.getElementById('save-creds').checked = saved;
  if (!saved) return;

  PERSIST_FIELDS.forEach(id => {
    const val = localStorage.getItem(STORAGE_PREFIX + id);
    const el  = document.getElementById(id);
    if (el && val !== null) el.value = val;
  });

  // Defaults for empty fields
  const tenantType = document.getElementById('tenantType');
  if (!tenantType.value) tenantType.value = 'regular';
  const apiVersion = document.getElementById('apiVersion');
  if (!apiVersion.value) apiVersion.value = 'v3';
}

function onSaveCredsChange() {
  if (document.getElementById('save-creds').checked) {
    saveToStorage();
  } else {
    PERSIST_FIELDS.forEach(id => localStorage.removeItem(STORAGE_PREFIX + id));
    localStorage.removeItem(STORAGE_PREFIX + 'save');
  }
}

// ── Secret toggle ─────────────────────────────────────────────────────────────

function toggleSecret() {
  const input = document.getElementById('clientSecret');
  const btn   = document.getElementById('toggle-secret-btn');
  if (input.type === 'password') {
    input.type = 'text';
    btn.textContent = 'Hide';
  } else {
    input.type = 'password';
    btn.textContent = 'Show';
  }
}

// ── Card collapse ─────────────────────────────────────────────────────────────

function toggleCard(cardId) {
  document.getElementById(cardId).classList.toggle('collapsed');
}

// ── File handling ─────────────────────────────────────────────────────────────

function setupDragDrop() {
  const zone = document.getElementById('drop-zone');

  zone.addEventListener('dragover', e => {
    e.preventDefault();
    zone.classList.add('drag-over');
  });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag-over'));
  zone.addEventListener('drop', e => {
    e.preventDefault();
    zone.classList.remove('drag-over');
    addFiles(Array.from(e.dataTransfer.files));
  });
}

function onFileInputChange(e) {
  addFiles(Array.from(e.target.files));
  e.target.value = ''; // allow re-selecting same file
}

function addFiles(files) {
  files.forEach(f => {
    if (f.name.toLowerCase().endsWith('.csv') && !selectedFiles.find(sf => sf.name === f.name)) {
      selectedFiles.push(f);
    }
  });
  renderFileList();
}

function removeFile(index) {
  selectedFiles.splice(index, 1);
  renderFileList();
}

function renderFileList() {
  const list = document.getElementById('file-list');
  list.innerHTML = selectedFiles.map((f, i) => `
    <div class="file-chip">
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" style="flex-shrink:0">
        <path d="M4 2h6l4 4v8a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1z" stroke="#6b7280" stroke-width="1.5" fill="none"/>
        <path d="M10 2v4h4" stroke="#6b7280" stroke-width="1.5" stroke-linejoin="round" fill="none"/>
      </svg>
      <span class="file-name">${escHtml(f.name)}</span>
      <button type="button" class="file-remove" onclick="removeFile(${i})" title="Remove">×</button>
    </div>
  `).join('');
}

// ── Form submission ───────────────────────────────────────────────────────────

async function onSubmit(e) {
  e.preventDefault();
  if (isRunning) return;

  const errors = validate();
  if (errors.length > 0) {
    appendLog('[ERROR] Validation failed: ' + errors.join('; '), 'error');
    return;
  }

  if (document.getElementById('save-creds').checked) saveToStorage();

  setRunning(true);
  clearLogs();

  const formData = buildFormData();

  try {
    const response = await fetch('/api/run', { method: 'POST', body: formData });
    await streamResponse(response);
  } catch (err) {
    appendLog('[ERROR] Request failed: ' + err.message, 'error');
  } finally {
    setRunning(false);
  }
}

function validate() {
  const errors = [];
  ['company', 'clientId', 'clientSecret'].forEach(id => {
    const el = document.getElementById(id);
    if (!el.value.trim()) {
      el.classList.add('invalid');
      errors.push(id + ' is required');
    } else {
      el.classList.remove('invalid');
    }
  });
  return errors;
}

function buildFormData() {
  const fd = new FormData();
  const get = id => document.getElementById(id).value.trim();

  fd.append('company',      get('company'));
  fd.append('tenantType',   get('tenantType') || 'regular');
  fd.append('authType',     get('authType'));
  fd.append('clientId',     get('clientId'));
  fd.append('clientSecret', get('clientSecret'));
  fd.append('apiVersion',   get('apiVersion') || 'v3');
  fd.append('operation',    get('operation'));

  selectedFiles.forEach(f => fd.append('files', f));

  return fd;
}

async function streamResponse(response) {
  if (!response.ok) {
    appendLog('[ERROR] Server error: HTTP ' + response.status, 'error');
    return;
  }

  const reader  = response.body.getReader();
  const decoder = new TextDecoder();
  let   buffer  = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop(); // keep partial last line

      lines.forEach(line => { if (line) appendLog(line); });
    }
    if (buffer.trim()) appendLog(buffer);
  } catch (err) {
    appendLog('[ERROR] Stream interrupted: ' + err.message, 'error');
  }
}

// ── Running state ─────────────────────────────────────────────────────────────

function setRunning(running) {
  isRunning = running;
  const btn     = document.getElementById('run-btn');
  const spinner = document.getElementById('btn-spinner');
  const text    = document.getElementById('btn-text');
  const badge   = document.getElementById('status-badge');

  btn.disabled = running;
  spinner.classList.toggle('hidden', !running);
  text.textContent = running ? 'Running…' : 'Run Operation';

  badge.className = 'status-badge ' + (running ? 'status-running' : 'status-idle');
  badge.textContent = running ? 'Running' : 'Ready';
}

function setFinished(success) {
  const badge = document.getElementById('status-badge');
  badge.className = 'status-badge ' + (success ? 'status-success' : 'status-error');
  badge.textContent = success ? 'Complete' : 'Error';
}

// ── Logging ───────────────────────────────────────────────────────────────────

function appendLog(line, forcedLevel) {
  const output = document.getElementById('log-output');
  const empty  = output.querySelector('.log-empty');
  if (empty) empty.remove();

  const level = forcedLevel || detectLevel(line);
  const div   = document.createElement('div');
  div.className = 'log-line log-' + level;
  div.textContent = line;
  output.appendChild(div);

  logLineCount++;
  document.getElementById('log-count').textContent =
    logLineCount + (logLineCount === 1 ? ' line' : ' lines');

  output.scrollTop = output.scrollHeight;

  // Update status badge based on log content
  if (level === 'error') setFinished(false);
  else if (line.includes('Complete') || line.includes('complete')) setFinished(true);
}

function detectLevel(line) {
  if (line.includes('[ERROR]')) return 'error';
  if (line.includes('[WARN]'))  return 'warn';
  if (line.includes('[DEBUG]')) return 'debug';
  if (line.includes('[INFO]'))  return 'info';
  if (line.includes('Complete') || line.includes('complete')) return 'complete';
  return 'default';
}

function clearLogs() {
  const output = document.getElementById('log-output');
  output.innerHTML = '<div class="log-empty">No output yet — run an operation to see logs here.</div>';
  logLineCount = 0;
  document.getElementById('log-count').textContent = '0 lines';

  const badge = document.getElementById('status-badge');
  if (!isRunning) {
    badge.className = 'status-badge status-idle';
    badge.textContent = 'Ready';
  }
}

function copyLogs() {
  const lines = Array.from(document.querySelectorAll('#log-output .log-line'))
    .map(el => el.textContent)
    .join('\n');
  navigator.clipboard.writeText(lines).catch(() => {
    // Fallback for non-HTTPS or older browsers
    const ta = document.createElement('textarea');
    ta.value = lines;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
  });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function escHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
