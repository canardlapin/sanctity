import { h, pathText } from './elements.js';
import { coverageSection } from './coverage.js';

export async function uploadView(d, base, query, { api, getRelease, current, show, failed, go, relHash, invalidateHead, explain }) {
  const enc = encodeURIComponent;
  const url = id => '/v1/uploads/' + enc(id);
  const hex = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    n => n.toString(16).padStart(2, '0')).join('');
  let entries, operation = null, busy = false;
  try {
    entries = base ? (await getRelease(d, base)).entries : [];
    if (query.has('operation')) {
      operation = await api('GET', url(query.get('operation')));
      if (operation.dataset !== d || operation.base !== base) throw new Error('This upload belongs to another dataset or base release.');
    }
  } catch (e) { if (current()) failed(e); return; }
  if (!current()) return;
  if (operation && operation.receipt) { go(relHash(d, operation.receipt.ref.release), true); return; }
  const existing = new Set(entries.filter(e => e.kind === 'file').map(e => e.path));
  const folder = h('input', { type: 'file', webkitdirectory: true, multiple: true });
  const destination = h('input', { type: 'text', placeholder: 'Dataset root', autocomplete: 'off' });
  const acknowledged = h('input', { type: 'checkbox' });
  const collisions = h('div', { class: 'stack s8' });
  const status = h('p', { role: 'status', class: 'small' });
  const preview = h('div', { class: 'stack' });
  const send = h('button', { class: 'btn primary', type: 'button', disabled: true }, 'Upload and preview');
  const fileRows = () => Array.from(folder.files, file => {
    const relative = file.webkitRelativePath.split('/').slice(1).join('/');
    return { file, path: [destination.value.trim(), relative].filter(Boolean).join('/') };
  });
  function refresh() {
    const replaced = fileRows().filter(row => existing.has(row.path)).map(row => row.path);
    collisions.replaceChildren();
    if (replaced.length) collisions.append(h('label', {}, acknowledged, ' Replace these existing files'),
      h('ul', {}, replaced.map(p => h('li', {}, pathText(p)))));
    send.disabled = busy || !folder.files.length || (replaced.length > 0 && !acknowledged.checked);
  }
  function reset() { acknowledged.checked = false; preview.replaceChildren(); refresh(); }
  folder.onchange = () => {
    const first = folder.files[0];
    const name = first ? first.webkitRelativePath.split('/')[0] : '';
    destination.value = /^(sub-|ses-)/.test(name) ? name : '';
    reset();
  };
  destination.oninput = reset; acknowledged.onchange = () => { preview.replaceChildren(); refresh(); };
  function lock(active) {
    busy = active; folder.disabled = destination.disabled = acknowledged.disabled = active; refresh();
  }
  async function review(op) {
    const p = await api('POST', url(op.id) + '/preview');
    if (!current()) return;
    const accept = h('button', { class: 'btn primary', type: 'button', disabled: !p.candidate.assessment.admissible }, 'Accept release');
    accept.onclick = async () => {
      accept.disabled = true;
      try {
        const receipt = await api('POST', url(op.id) + '/accept');
        if (current()) { invalidateHead(); go(relHash(d, receipt.ref.release)); }
      } catch (e) { if (current()) { status.textContent = explain(e); accept.disabled = false; } }
    };
    const files = p.contentChanges.filter(c => (c.after || c.before).kind === 'file');
    preview.replaceChildren(h('h2', {}, 'Review upload'),
      h('ul', {}, files.map(c => h('li', {}, c.before ? 'Replace ' : 'Add ', pathText(c.path)))),
      p.candidate.levels ? coverageSection(p.candidate.levels) : null,
      h('h3', {}, 'Findings'),
      p.candidate.assessment.findings.length ? h('ul', {}, p.candidate.assessment.findings.map(f =>
        h('li', {}, h('strong', {}, f.severity), ' · ', f.path ? [pathText(f.path), ' — '] : '', f.message))) : h('p', {}, 'No findings.'),
      accept);
    status.textContent = 'Review the proposed release before accepting.';
  }
  send.onclick = async () => {
    if (busy) return;
    lock(true); preview.replaceChildren();
    try {
      const rows = fileRows(), files = [];
      if (rows.length > 10000 || rows.some(({ file }) => file.size > 2 ** 31) ||
          rows.reduce((total, { file }) => total + file.size, 0) > 2 ** 33) {
        throw new Error('Choose up to 10,000 files, at most 2 GiB each and 8 GiB in total.');
      }
      for (const row of rows) {
        if (!current()) return;
        status.textContent = 'Checking ' + row.path;
        files.push({ path: row.path, size: row.file.size, sha256: await hex(await row.file.arrayBuffer()) });
      }
      files.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
      const json = JSON.stringify({ base, replace: files.filter(f => existing.has(f.path)).map(f => f.path), files });
      const key = await hex(new TextEncoder().encode(json));
      if (!current()) return;
      const op = await api('POST', '/v1/datasets/' + enc(d) + '/uploads', { json, headers: { 'Idempotency-Key': key } });
      if (!current()) return;
      history.replaceState(null, '', location.hash.split('?')[0] + '?operation=' + enc(op.id));
      const received = new Set(op.received);
      for (const row of rows) {
        if (!current()) return;
        if (!received.has(row.path)) {
          status.textContent = 'Uploading ' + row.path;
          await api('PUT', url(op.id) + '/file?path=' + enc(row.path), { bytes: row.file });
        }
      }
      if (current()) await review(op);
    } catch (e) { if (current()) status.textContent = e.message + ' You can retry the same folder.'; }
    finally { if (current()) lock(false); }
  };
  show(h('div', { class: 'stack' },
    h('h1', {}, base ? 'Add files to this release' : 'Upload a dataset'),
    h('label', { class: 'stack s8' }, 'Choose a folder', folder),
    h('label', { class: 'stack s8' }, 'Place its contents in', destination),
    h('p', { class: 'small muted' }, 'Leave the path empty for the dataset root, or enter a path such as sub-01/ses-02.'),
    collisions, send, status, preview));
  if (operation && operation.complete) {
    try { await review(operation); } catch (e) { if (current()) status.textContent = e.message; }
  } else if (operation) status.textContent = 'Select the same folder and destination to resume.';
}
