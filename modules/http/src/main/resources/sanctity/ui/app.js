/* Sanctity console: a thin client over the authenticated /v1 workflow API.
   Everything rendered from the API goes through text nodes; no HTML is built from data.
   No inline style attributes: the server sends style-src 'self'. */
'use strict';
(() => {
  // ---------- small utilities ----------
  const store = {
    get(area, key) { try { return window[area].getItem(key); } catch { return null; } },
    set(area, key, value) { try { value == null ? window[area].removeItem(key) : window[area].setItem(key, value); } catch { /* storage unavailable */ } },
  };
  const short = id => (id || '').slice(0, 8);
  const shortDigest = d => (d || '').slice(0, 12);
  const bytes = n => {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    const u = ['KiB', 'MiB', 'GiB', 'TiB']; let v = n / 1024, i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (v < 10 ? v.toFixed(1) : Math.round(v)) + ' ' + u[i];
  };
  const when = (iso, dateOnly) => {
    const d = new Date(iso);
    if (isNaN(d)) return iso || '';
    return d.toLocaleString(undefined, dateOnly ? { month: 'short', day: 'numeric', year: 'numeric' } : { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' });
  };
  const show = v => v === null ? 'null' : typeof v === 'string' ? v : JSON.stringify(v);
  // Check facts nest (timingMetadata.RepetitionTime.value); flatten to dotted names so they can be listed and compared.
  const flatFacts = (o, pre = '', out = {}) => {
    for (const [k, v] of Object.entries(o || {})) {
      const n = pre ? pre + '.' + k : k;
      if (v && typeof v === 'object' && !Array.isArray(v)) flatFacts(v, n, out); else out[n] = v;
    }
    return out;
  };
  const plural = (n, one, many = one + 's') => n + ' ' + (n === 1 ? one : many);
  // BIDS-defined units for common acquisition fields; shown beside the stored value, never folded into it.
  const UNITS = { RepetitionTime: 's', EchoTime: 's', TotalReadoutTime: 's', InversionTime: 's', EffectiveEchoSpacing: 's',
    SliceTiming: 's', DelayTime: 's', AcquisitionDuration: 's', FlipAngle: '°', MagneticFieldStrength: 'T' };
  const fileKind = p => {
    const m = /\.(nii\.gz|nii|json|tsv|bval|bvec|gz|txt)$/.exec(p);
    if (!m) return /(^|\/)(README|CHANGES|LICENSE)(\.md)?$/.test(p) ? 'txt' : '';
    return m[1] === 'nii.gz' ? 'nii' : m[1];
  };
  const TEXT_KINDS = new Set(['json', 'tsv', 'bval', 'bvec', 'txt']);
  const stem = p => p.replace(/\.(nii\.gz|nii|json|tsv|bval|bvec)$/, '');
  const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'k-' + Date.now() + '-' + Math.random().toString(16).slice(2));
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const DECIMAL = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/;
  const narrow = () => matchMedia('(max-width: 980px)').matches;

  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'fk') el.dataset.fk = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected') el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat(Infinity)) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return el;
  }
  // A path that breaks only after "/" and "_" so names never split mid-token.
  const pathText = p => {
    const s = h('span', { class: 'path-text' });
    p.split(/(?<=[/_])/).forEach((part, i) => { if (i) s.append(h('wbr')); s.append(part); });
    return s;
  };
  const svg = (paths, cls = 'icon', vb = '0 0 16 16') => {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', vb); s.setAttribute('class', cls); s.setAttribute('aria-hidden', 'true');
    s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '1.6');
    s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
    for (const d of [].concat(paths)) { const p = document.createElementNS(s.namespaceURI, 'path'); p.setAttribute('d', d); s.append(p); }
    return s;
  };
  const I = {
    mark: () => { const s = svg(['M16 3a13 13 0 1 0 0 26a13 13 0 1 0 0-26', 'M16 9a7 7 0 1 0 0 14a7 7 0 1 0 0-14'], 'mark', '0 0 32 32'); s.setAttribute('stroke-width', '2.4'); return s; },
    seal: () => svg(['M8 1.5l1.6 1.2 2-.1.6 1.9 1.6 1.2-.6 1.9.6 1.9-1.6 1.2-.6 1.9-2-.1L8 14.5l-1.6-1.2-2 .1-.6-1.9-1.6-1.2.6-1.9-.6-1.9 1.6-1.2.6-1.9 2 .1z', 'M5.6 8.1l1.7 1.7 3.2-3.4']),
    stop: () => svg(['M8 1.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13', 'M5.7 5.7l4.6 4.6M10.3 5.7l-4.6 4.6']),
    warn: () => svg(['M8 2l6.5 11.5h-13z', 'M8 6.5v3', 'M8 11.6v.1']),
    err: () => svg(['M8 1.8a6.2 6.2 0 1 0 0 12.4a6.2 6.2 0 1 0 0-12.4', 'M8 4.8v3.8', 'M8 11v.1']),
    down: () => svg(['M8 2.5v8', 'M4.5 7.5L8 11l3.5-3.5', 'M3 13.5h10']),
    edit: () => svg(['M10.5 2.5l3 3L6 13H3v-3z']),
    dir: () => svg(['M1.8 4.2c0-.7.5-1.2 1.2-1.2h3l1.4 1.6H13c.7 0 1.2.5 1.2 1.2v6.2c0 .7-.5 1.2-1.2 1.2H3c-.7 0-1.2-.5-1.2-1.2z']),
    chev: () => svg(['M6 4l4 4-4 4']),
    moon: () => svg(['M13.5 9.5A5.5 5.5 0 0 1 6.5 2.5a5.5 5.5 0 1 0 7 7z']),
    plus: () => svg(['M8 3v10M3 8h10']),
    x: () => svg(['M4 4l8 8M12 4l-8 8']),
    check: () => svg(['M3 8.5l3 3 7-7']),
    out: () => svg(['M6 2.5H3.5v11H6', 'M10 5l3 3-3 3', 'M13 8H6.5']),
    arrow: () => svg(['M2 8h11', 'M9.5 4.5L13 8l-3.5 3.5']),
    link: () => svg(['M6.5 9.5l3-3', 'M7 4.5l1-1a2.5 2.5 0 0 1 3.5 3.5l-1 1', 'M9 11.5l-1 1a2.5 2.5 0 0 1-3.5-3.5l1-1']),
  };

  function announce(msg) { const a = document.getElementById('announce'); if (a) { a.textContent = ''; setTimeout(() => { a.textContent = msg; }, 30); } }
  function toast(msg) {
    document.querySelectorAll('.toast').forEach(t => t.remove());
    const t = h('div', { class: 'toast', role: 'status', text: msg });
    document.body.append(t); setTimeout(() => t.remove(), 2400);
  }
  function hashChip(value, label, full) {
    return h('button', { class: 'hash', type: 'button', title: 'Copy ' + label + ': ' + value, 'aria-label': 'Copy ' + label + ' ' + value,
      onclick: async () => { try { await navigator.clipboard.writeText(value); toast('Copied ' + label); } catch { toast(value); } } },
      full ? value : value.length > 40 ? shortDigest(value) : short(value));
  }
  const unitOf = name => UNITS[name] ? h('span', { class: 'unit' + (UNITS[name] === '°' ? ' tight' : '') }, UNITS[name] === '°' ? '' : h('span', { class: 'sr-only' }, ' '), UNITS[name]) : null;
  const valueNode = (name, v) => h('span', { class: 'value' }, show(v), typeof v === 'number' || Array.isArray(v) && v.length && v.every(x => typeof x === 'number') ? unitOf(name) : null);

  // ---------- API ----------
  class ApiError extends Error {
    constructor(status, code) { super(code); this.status = status; this.code = code; }
  }
  const explain = e => {
    if (!(e instanceof ApiError)) return 'The repository could not be reached. Check that the Sanctity API is running.';
    return ({
      unauthorized: 'This credential was not accepted. It may be revoked or mistyped.',
      not_found: 'Not found, or not visible to this credential. Sanctity does not distinguish the two.',
      conflict: 'The dataset head changed after this was prepared, or this retry key was used for different content. Open the current head and prepare the change there.',
      admission_rejected: 'Admission rejected the candidate. Its findings explain why.',
      invalid_request: 'The repository refused the request as invalid. Check identifiers, actions and JSON syntax.',
      limit_exceeded: 'The request exceeds a repository limit.',
      request_timeout: 'The request timed out. Try again.',
      repository_unavailable: 'The repository is temporarily unavailable.',
      authentication_unavailable: 'Authentication is temporarily unavailable.',
      repository_failure: 'The repository reported an internal failure.',
    })[e.code] || 'Request failed (' + e.code + ').';
  };

  const demo = () => window.SanctityDemo;
  // Forget everything tied to the signed-in principal and invalidate pending loads.
  function resetSession() {
    generation++; memo.clear(); focusKey = null;
    state.principal = null; state.token = null; state.correction = null; state.query = null; state.accessForm = null;
    state.revoking = null; state.revokeBusy = false; state.selectionQueries = null;
    store.set('sessionStorage', 'sanctity.token', null);
  }
  function sessionEnded() {
    resetSession();
    state.notice = 'Your session ended or the credential was revoked. Sign in again.';
    if (location.hash !== '#/') location.hash = '#/'; else render();
  }
  // `json` is a pre-serialised body so decimals keep their exact lexical form.
  async function api(method, path, { json, headers = {}, raw = false } = {}) {
    if (demo()) return demo().request(method, path, { body: json, headers, raw }).catch(e => { throw e.code ? new ApiError(e.status, e.code) : e; });
    const init = { method, headers: { Authorization: 'Bearer ' + state.token, ...headers }, cache: 'no-store' };
    if (json !== undefined) { init.body = json; init.headers['Content-Type'] = 'application/json'; }
    const res = await fetch(path, init);
    if (!res.ok) {
      let code = 'http_' + res.status;
      try { code = (await res.json()).error || code; } catch { /* non-JSON error */ }
      if (res.status === 401 && state.principal) sessionEnded();
      throw new ApiError(res.status, code);
    }
    if (raw) return res;
    return res.status === 204 ? null : res.json();
  }
  const enc = encodeURIComponent;
  const releaseUrl = (d, r) => '/v1/datasets/' + enc(d) + '/releases/' + enc(r);
  const memo = new Map();
  const cached = (key, f) => { if (!memo.has(key)) memo.set(key, f().catch(e => { memo.delete(key); throw e; })); return memo.get(key); };
  const getRelease = (d, r) => cached('rel:' + d + '/' + r, () => api('GET', releaseUrl(d, r)));
  const getMetadata = (d, r, p) => cached('meta:' + d + '/' + r + ':' + p, () => api('GET', releaseUrl(d, r) + '/metadata?path=' + enc(p)));
  const getFileText = (d, r, p) => cached('text:' + d + '/' + r + ':' + p, async () => (await api('GET', releaseUrl(d, r) + '/file?path=' + enc(p), { raw: true })).text());
  const getHead = d => cached('head:' + d, async () => (await api('GET', '/v1/datasets/' + enc(d))).head);
  const describe = (d, r) => cached('desc:' + d + '/' + r, async () => {
    try { return JSON.parse(await getFileText(d, r, 'dataset_description.json')); } catch { return null; }
  });

  // ---------- state & routing ----------
  const state = {
    token: store.get('sessionStorage', 'sanctity.token'),
    principal: null, notice: null,
    correction: null,     // { key, base, path, original, text, reason, preview, receipt }
    query: null, accessForm: null,
    revoking: null,       // grant id awaiting confirmation
    scrollInspector: false,
  };
  const grantsKey = (who = state.principal) => who ? 'sanctity.grants.' + who : null;
  const loadGrants = (who) => { const k = grantsKey(who); try { return k ? JSON.parse(store.get('sessionStorage', k) || '[]') : []; } catch { return []; } };
  const saveGrants = (g, who) => { const k = grantsKey(who); if (k) store.set('sessionStorage', k, JSON.stringify(g)); };
  const recentDatasets = () => { try { return JSON.parse(store.get('localStorage', 'sanctity.recent') || '[]'); } catch { return []; } };
  const remember = d => store.set('localStorage', 'sanctity.recent', JSON.stringify([d, ...recentDatasets().filter(x => x !== d)].slice(0, 5)));

  function route() {
    const raw = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = raw.split('?');
    const q = new URLSearchParams(qs || '');
    const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
    return { parts, q };
  }
  const go = (hash, replace) => {
    if (replace) { history.replaceState(null, '', hash); return render(); }
    location.hash = hash;
  };
  const relHash = (d, r, q) => '#/d/' + enc(d) + '/r/' + enc(r) + (q && Object.keys(q).length ? '?' + new URLSearchParams(q) : '');

  // ---------- focus continuity ----------
  // Controls carry data-fk keys. A re-render restores focus to the control with the same key.
  let focusKey = null;
  const rememberFocus = () => { const a = document.activeElement; if (!focusKey && a && a.dataset && a.dataset.fk) focusKey = a.dataset.fk; };
  function restoreFocus() {
    if (!focusKey) return false;
    const keys = focusKey.split('|');
    const el = keys.map(k => [...document.querySelectorAll('[data-fk]')].find(x => x.dataset.fk === k)).find(Boolean);
    if (el) { el.focus({ preventScroll: true }); focusKey = null; return true; }
    return false;
  }

  // ---------- theme ----------
  function applyTheme(t) { if (t) document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme; }
  applyTheme(store.get('localStorage', 'sanctity.theme'));
  const isDark = () => document.documentElement.dataset.theme ? document.documentElement.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  function toggleTheme(ev) {
    const next = isDark() ? 'light' : 'dark';
    store.set('localStorage', 'sanctity.theme', next); applyTheme(next);
    ev.currentTarget.setAttribute('aria-pressed', String(next === 'dark'));
  }

  // ---------- shell ----------
  const app = document.getElementById('app');
  let generation = 0;

  function topbar(ctx, active) {
    const nav = ctx && ctx.dataset ? h('nav', { class: 'nav', 'aria-label': 'Workspace' },
      [['release', 'Release', ctx.release ? relHash(ctx.dataset, ctx.release) : '#/d/' + enc(ctx.dataset)],
       ['query', 'Query', '#/d/' + enc(ctx.dataset) + '/query' + (ctx.release ? '?release=' + enc(ctx.release) : '')],
       ['access', 'Access', '#/d/' + enc(ctx.dataset) + '/access' + (ctx.release ? '?release=' + enc(ctx.release) : '')]]
        .map(([k, label, href]) => h('a', { href, fk: 'nav:' + k, 'aria-current': active === k ? 'page' : null }, label))) : null;
    const p = state.principal || '';
    return h('header', { class: 'topbar' },
      h('a', { class: 'brand', href: '#/', 'aria-label': 'Sanctity home' }, I.mark(), h('span', { 'aria-hidden': 'true' }, 'Sanctity')),
      nav,
      h('div', { class: 'tools' },
        demo() ? h('span', { class: 'chip warn demo', title: 'Synthetic fixtures; nothing is stored' }, 'Demo data') : null,
        h('button', { class: 'btn ghost icon-btn', type: 'button', fk: 'theme', onclick: toggleTheme, 'aria-label': 'Dark theme', 'aria-pressed': String(isDark()), title: 'Toggle dark theme' }, I.moon()),
        p ? h('span', { class: 'who', title: 'Signed-in principal ' + p },
          h('span', { class: 'avatar', 'aria-hidden': 'true' }, p.slice(0, 2).toUpperCase()),
          h('span', { class: 'mono who-id' }, short(p))) : null,
        p ? h('button', { class: 'btn ghost icon-btn', type: 'button', fk: 'signout', onclick: disconnect, 'aria-label': 'Sign out', title: 'Sign out' }, I.out()) : null));
  }
  function page(ctx, active, ...content) {
    const main = h('main', { id: 'main', tabindex: '-1' }, ...content);
    app.replaceChildren(topbar(ctx, active), main);
    restoreFocus();
    return main;
  }
  function failure(ctx, active, e, retry) {
    page(ctx, active, h('div', { class: 'card pad stack measure' },
      h('h1', { class: 'h2' }, e instanceof ApiError && e.code === 'not_found' ? 'Nothing here' : 'Something went wrong'),
      h('p', { class: 'muted' }, explain(e)),
      h('div', { class: 'row' }, retry ? h('button', { class: 'btn', type: 'button', onclick: retry }, 'Try again') : null,
        h('a', { class: 'btn ghost', href: '#/' }, 'Open another dataset'))));
  }
  // Paint a loading state only if data takes long enough to notice; avoids flashing over cached views.
  const loadingAfter = (my, ctx, active, label) => setTimeout(() => { if (my === generation) page(ctx, active, h('p', { class: 'loading' }, label)); }, 180);
  function disconnect() {
    if (grantsKey()) store.set('sessionStorage', grantsKey(), null);
    resetSession();
    go('#/');
  }

  // ---------- connect ----------
  function connectView(message) {
    const recent = recentDatasets();
    let tokenInput, datasetInput, err;
    const notice = state.notice || message; state.notice = null;
    const submit = async ev => {
      ev.preventDefault();
      const token = tokenInput ? tokenInput.value.trim() : state.token, ds = datasetInput.value.trim();
      err.replaceChildren();
      if (!demo() && !token) { err.append(h('p', { class: 'notice err', role: 'alert' }, 'Enter an access token.')); tokenInput.setAttribute('aria-invalid', 'true'); tokenInput.focus(); return; }
      if (ds && !UUID.test(ds)) { err.append(h('p', { class: 'notice err', role: 'alert' }, 'Dataset identifiers are UUIDs, like 3f2a9c1e-…-…')); datasetInput.setAttribute('aria-invalid', 'true'); datasetInput.focus(); return; }
      state.token = token;
      try {
        const me = await api('GET', '/v1/me');
        state.principal = me.principal;
        store.set('sessionStorage', 'sanctity.token', token);
        if (ds) go('#/d/' + enc(ds)); else render();
      } catch (e) {
        state.token = null;
        err.append(h('p', { class: 'notice err', role: 'alert' }, explain(e)));
        if (tokenInput && e instanceof ApiError && e.status === 401) { tokenInput.setAttribute('aria-invalid', 'true'); tokenInput.focus(); }
      }
    };
    const clearInvalid = ev => ev.target.removeAttribute('aria-invalid');
    const signedIn = !!state.principal;
    const rings = svg(['M260 20a240 240 0 1 0 0 480a240 240 0 1 0 0-480', 'M260 90a170 170 0 1 0 0 340a170 170 0 1 0 0-340', 'M260 160a100 100 0 1 0 0 200a100 100 0 1 0 0-200'], 'rings', '0 0 520 520');
    app.replaceChildren(h('div', { class: 'connect' },
      h('section', { class: 'connect-hero', 'aria-label': 'About Sanctity' },
        h('div', { class: 'brand' }, I.mark(), h('span', {}, 'Sanctity')),
        h('div', { class: 'hero-copy' },
          h('p', { class: 'hero-title' }, 'Exact scientific states, explained.'),
          h('p', { class: 'hero-lead' }, 'A BIDS-native repository. Every release fixes its content, interpretation and admission evidence. Every change is previewed before it is accepted.'),
          h('dl', { class: 'laws' },
            h('div', {}, h('dt', {}, 'Fidelity'), h('dd', {}, 'Exports reproduce the retained tree byte for byte.')),
            h('div', {}, h('dt', {}, 'Explanation'), h('dd', {}, 'Every metadata value names the file it came from.')),
            h('div', {}, h('dt', {}, 'Authority'), h('dd', {}, 'Access is granted explicitly and can be withdrawn.')))),
        rings, h('p', { class: 'hero-foot' }, 'Console for the Sanctity v1 API')),
      h('main', { class: 'connect-form', id: 'main', tabindex: '-1' },
        h('form', { class: 'stack s18', onsubmit: submit, novalidate: true },
          h('div', { class: 'stack s6' },
            h('h1', { class: 'h2' }, signedIn ? 'Open a dataset' : 'Sign in'),
            h('p', { class: 'muted small' }, signedIn ? 'Signed in as principal ' + short(state.principal) + '. Paste a dataset identifier to continue.' :
              'Use a principal credential issued by your repository operator. It stays in this browser tab only.')),
          notice ? h('p', { class: 'notice warn', role: 'status' }, notice) : null,
          signedIn ? null : h('div', { class: 'field' }, h('label', { for: 'token' }, 'Access token'),
            tokenInput = h('input', { class: 'input mono', id: 'token', type: 'password', oninput: clearInvalid, 'aria-describedby': 'connect-errors', autocomplete: 'off', spellcheck: 'false', placeholder: demo() ? 'Demo mode: any value works' : 'Paste your token' })),
          h('div', { class: 'field' }, h('label', { for: 'dataset' }, 'Dataset identifier'),
            datasetInput = h('input', { class: 'input mono', id: 'dataset', oninput: clearInvalid, autocomplete: 'off', spellcheck: 'false', placeholder: '00000000-0000-0000-0000-000000000000', value: recent[0] || (demo() ? demo().dataset : ''), 'aria-describedby': 'dataset-hint' }),
            h('span', { class: 'hint', id: 'dataset-hint' }, 'The API does not list datasets. Use the identifier of one you can read.')),
          err = h('div', { class: 'stack', id: 'connect-errors' }),
          h('button', { class: 'btn primary block', type: 'submit' }, signedIn ? 'Open dataset' : 'Sign in and open'),
          recent.length > 1 ? h('div', { class: 'stack s8' }, h('h2', { class: 'overline' }, 'Recent'),
            h('div', { class: 'recent' }, recent.map(d => h('button', { type: 'button', onclick: () => { datasetInput.value = d; datasetInput.focus(); } },
              h('span', { class: 'mono' }, d), I.chev())))) : null))));
    (signedIn ? datasetInput : tokenInput).focus();
  }

  // ---------- release ----------
  async function lineage(d, r) {
    const chain = [];
    let cur = r;
    for (let i = 0; cur && i < 6; i++) {
      try { const rel = await getRelease(d, cur); chain.push(rel); cur = rel.parent; } catch { break; }
    }
    return { chain: chain.reverse(), more: !!cur };
  }

  function buildTree(entries) {
    const root = { name: '', path: '', dirs: new Map(), files: [] };
    for (const e of entries) {
      const segs = e.path.split('/');
      let node = root;
      const last = e.kind === 'directory' ? segs.length : segs.length - 1;
      for (let i = 0; i < last; i++) {
        const name = segs[i];
        if (!node.dirs.has(name)) node.dirs.set(name, { name, path: segs.slice(0, i + 1).join('/'), dirs: new Map(), files: [] });
        node = node.dirs.get(name);
      }
      if (e.kind === 'file') node.files.push({ name: segs[segs.length - 1], path: e.path, size: e.size });
    }
    return root;
  }

  async function releaseView(d, r, q) {
    const my = ++generation;
    const ctx = { dataset: d, release: r };
    const tab = q.get('tab') || 'files', selected = q.get('path');
    const timer = loadingAfter(my, ctx, 'release', 'Loading release…');
    let rel, lin, desc, headId;
    try { [rel, lin, desc, headId] = await Promise.all([getRelease(d, r), lineage(d, r), describe(d, r), getHead(d).catch(() => null)]); }
    catch (e) { clearTimeout(timer); if (my === generation) failure(ctx, 'release', e, () => render()); return; }
    clearTimeout(timer);
    if (my !== generation) return;
    remember(d);
    const a = rel.assessment;
    const links = rel.relationships ? rel.relationships.links : [];
    const files = rel.entries.filter(e => e.kind === 'file');
    const errors = a.findings.filter(f => f.severity === 'Error').length;
    const isHead = !headId || headId === r;

    const head = h('div', { class: 'page-head' },
      h('div', { class: 'stack s10' },
        h('div', { class: 'crumbs' }, h('span', {}, 'Dataset'), hashChip(d, 'dataset id'), I.chev(), h('span', {}, 'Release'), hashChip(r, 'release id')),
        h('h1', {}, desc && typeof desc.Name === 'string' ? desc.Name : 'Release ' + short(r)),
        h('div', { class: 'row' },
          a.admissible ? h('span', { class: 'seal ok' }, I.seal(), 'Admitted') : h('span', { class: 'seal err' }, I.stop(), 'Not admissible'),
          h('span', { class: 'meta-item', title: rel.acceptedAt }, 'Accepted ', h('b', {}, when(rel.acceptedAt))),
          h('span', { class: 'meta-item', title: 'Interpretation profile' }, 'Profile ', h('b', { class: 'mono' }, rel.profile)),
          desc && desc.BIDSVersion ? h('span', { class: 'meta-item', title: 'Authored BIDS version' }, 'BIDS ', h('b', {}, desc.BIDSVersion)) : null,
          h('span', { class: 'meta-item' }, h('b', {}, files.length), ' files'))),
      h('div', { class: 'row head-actions' },
        h('a', { class: 'btn', href: '#/d/' + enc(d) + '/query?release=' + enc(r) }, 'Query'),
        isHead ? h('a', { class: 'btn primary', href: '#/d/' + enc(d) + '/r/' + enc(r) + '/correct' + (selected && selected.endsWith('.json') ? '?path=' + enc(selected) : '') }, I.edit(), 'Propose correction')
          : h('a', { class: 'btn primary', href: relHash(d, headId, selected ? { path: selected } : null) }, 'Go to current head')));
    const notHead = isHead ? null : h('p', { class: 'notice warn' }, 'This is an earlier release. It never changes, and corrections are prepared against the current dataset head.');

    const lineageNav = h('nav', { class: 'lineage', 'aria-label': 'Release lineage, oldest first' },
      lin.more ? [h('span', { class: 'muted small' }, 'earlier'), h('span', { class: 'link', 'aria-hidden': 'true' })] : null,
      lin.chain.map((x, i) => [i ? h('span', { class: 'link', 'aria-hidden': 'true' }) : null,
        h('a', { class: 'node', href: relHash(d, x.ref.release), 'aria-current': x.ref.release === r ? 'true' : null,
          'aria-label': 'Release ' + short(x.ref.release) + ', accepted ' + when(x.acceptedAt) + (x.ref.release === r ? ' (viewing)' : '') },
          h('span', { class: 'label' }, short(x.ref.release)), h('span', { class: 'when' }, when(x.acceptedAt, true)))]));

    const tabs = [['files', 'Files', files.length], ['assessment', 'Assessment', a.findings.length], ['relationships', 'Relationships', links.length]];
    const goTab = k => go(relHash(d, r, { ...(k !== 'files' ? { tab: k } : {}), ...(selected ? { path: selected } : {}) }), true);
    const tabBar = h('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Release sections' },
      tabs.map(([k, label, n]) => h('button', { role: 'tab', type: 'button', id: 'tab-' + k, fk: 'tab:' + k, 'aria-selected': String(tab === k), 'aria-controls': 'panel',
        tabindex: tab === k ? '0' : '-1',
        onclick: () => goTab(k),
        onkeydown: ev => {
          const i = tabs.findIndex(t => t[0] === k);
          const j = ev.key === 'ArrowRight' ? (i + 1) % tabs.length : ev.key === 'ArrowLeft' ? (i + tabs.length - 1) % tabs.length
            : ev.key === 'Home' ? 0 : ev.key === 'End' ? tabs.length - 1 : -1;
          if (j >= 0) { ev.preventDefault(); focusKey = 'tab:' + tabs[j][0]; goTab(tabs[j][0]); }
        } }, label, h('span', { class: 'count', 'aria-label': n + ' items' }, n),
        k === 'assessment' && errors ? h('span', { class: 'dot err', 'aria-label': errors + ' blocking' }) : null)));

    const panel = h('div', { id: 'panel', role: 'tabpanel', 'aria-labelledby': 'tab-' + tab });
    const openFile = p => { focusKey = 'f:' + p; go(relHash(d, r, { path: p })); };
    if (tab === 'assessment') panel.append(assessmentPanel(a, openFile));
    else if (tab === 'relationships') panel.append(relationshipsPanel(links, openFile));
    const identity = h('div', { class: 'row identity', role: 'group', 'aria-label': 'Release identity' },
      h('span', { class: 'meta-item' }, 'Manifest ', hashChip(rel.manifest, 'manifest digest')),
      h('span', { class: 'meta-item' }, 'Interpretation ', hashChip(rel.interpretation, 'interpretation digest')),
      h('span', { class: 'meta-item' }, 'Assessment ', hashChip(a.id, 'assessment digest')));
    page(ctx, 'release', head, notHead, h('div', { class: 'strip' }, lineageNav, identity), tabBar, panel);
    if (tab === 'files') filesPanel(panel, d, r, rel, selected, my);
  }

  function filesPanel(panel, d, r, rel, selected, my) {
    const tree = buildTree(rel.entries);
    const findingsByPath = new Map();
    rel.assessment.findings.forEach(f => f.path && findingsByPath.set(f.path, (findingsByPath.get(f.path) || 0) + 1));
    let open;
    try { open = new Set(JSON.parse(store.get('sessionStorage', 'sanctity.open') || '[]')); } catch { open = new Set(); }
    if (selected) selected.split('/').slice(0, -1).forEach((_, i, a) => open.add(a.slice(0, i + 1).join('/')));
    let filter = '';
    const list = h('div', { class: 'tree-list' });
    const draw = () => {
      rememberFocus();
      const match = p => !filter || p.toLowerCase().includes(filter);
      const node = n => {
        const items = [];
        for (const dir of [...n.dirs.values()].sort((x, y) => x.name.localeCompare(y.name))) {
          const sub = node(dir);
          if (filter && !sub.length) continue;
          const isOpen = !!filter || open.has(dir.path);
          items.push(h('li', { class: 'dir' },
            h('button', { class: 'item', type: 'button', fk: 'd:' + dir.path, 'aria-expanded': String(isOpen),
              onclick: () => { isOpen ? open.delete(dir.path) : open.add(dir.path); store.set('sessionStorage', 'sanctity.open', JSON.stringify([...open])); draw(); } },
              I.dir(), h('span', { class: 'name' }, dir.name)),
            isOpen ? h('ul', {}, sub) : null));
        }
        for (const f of n.files.sort((x, y) => x.name.localeCompare(y.name))) {
          if (!match(f.path)) continue;
          const k = fileKind(f.name), nf = findingsByPath.get(f.path);
          items.push(h('li', {}, h('button', { class: 'item file', type: 'button', fk: 'f:' + f.path, 'aria-current': f.path === selected ? 'true' : null,
            onclick: () => { state.scrollInspector = true; focusKey = 'f:' + f.path; go(relHash(d, r, { path: f.path })); } },
            h('span', { class: 'kind ' + k, 'aria-hidden': 'true' }, k || 'file'), h('span', { class: 'name' }, pathText(f.name)),
            nf ? h('span', { class: 'flag', title: nf + ' finding' + (nf > 1 ? 's' : '') }, h('span', { class: 'sr-only' }, nf + (nf > 1 ? ' findings' : ' finding')), h('span', { 'aria-hidden': 'true' }, nf)) : null,
            h('span', { class: 'size' }, bytes(f.size)))));
        }
        return items;
      };
      const items = node(tree);
      list.replaceChildren(items.length ? h('ul', {}, items) : h('p', { class: 'empty small' }, 'No files match “' + filter + '”.'));
      restoreFocus();
    };
    // Up/Down move between visible items; the list is otherwise ordinary buttons.
    list.addEventListener('keydown', ev => {
      if (ev.key !== 'ArrowDown' && ev.key !== 'ArrowUp') return;
      const items = [...list.querySelectorAll('.item')];
      const i = items.indexOf(document.activeElement);
      if (i < 0) return;
      ev.preventDefault();
      items[Math.max(0, Math.min(items.length - 1, i + (ev.key === 'ArrowDown' ? 1 : -1)))].focus();
    });
    draw();
    const nav = h('nav', { class: 'card tree', id: 'release-tree', 'aria-label': 'Release files' },
      h('div', { class: 'tree-filter' }, h('input', { class: 'input', type: 'search', id: 'tree-filter', placeholder: 'Filter files', 'aria-label': 'Filter files',
        oninput: ev => { filter = ev.target.value.trim().toLowerCase(); draw(); } })), list);
    // On narrow screens a chosen file comes first; the tree folds into one line that names the selection.
    // CSS decides when the fold applies, so resizing re-renders nothing (focus, filter and disclosures survive).
    const fold = !!selected && narrow();
    const browse = h('span', { class: 'small strong' }, 'Browse');
    const bar = selected ? h('button', { class: 'card tree-fold-bar', type: 'button', fk: 'tree-fold', 'aria-expanded': 'false', 'aria-controls': 'release-tree',
      onclick: () => { const folded = side.classList.toggle('folded'); bar.setAttribute('aria-expanded', String(!folded)); browse.textContent = folded ? 'Browse' : 'Hide'; } },
      I.dir(), h('span', { class: 'stack s2 grow' }, h('span', { class: 'small muted' }, 'Files · ' + rel.entries.filter(e => e.kind === 'file').length),
        h('span', { class: 'mono small' }, pathText(selected))), browse) : null;
    const side = h('div', { class: 'tree-side' + (selected ? ' foldable folded' : '') }, bar, nav);
    // A tree in use stays open, so narrowing the window never hides the focused control.
    nav.addEventListener('focusin', () => { if (bar && side.classList.contains('folded')) { side.classList.remove('folded'); bar.setAttribute('aria-expanded', 'true'); browse.textContent = 'Hide'; } });
    const inspector = h('section', { class: 'card inspector', 'aria-label': 'File inspector', tabindex: '-1', fk: 'inspector' });
    if (fold && focusKey && focusKey.startsWith('f:')) focusKey = 'inspector';   // the chosen file's button is folded away
    panel.append(h('div', { class: 'workspace' }, side, inspector));
    restoreFocus();
    if (!selected) {
      inspector.append(h('div', { class: 'empty stack' }, h('h2', {}, 'Select a file'),
        h('p', {}, 'See its effective metadata, the file each value comes from, and the acquisitions it is linked to.')));
      return;
    }
    if (state.scrollInspector && narrow()) inspector.scrollIntoView({ block: 'start' });
    state.scrollInspector = false;
    inspectFile(inspector, d, r, rel, selected, my);
  }

  async function inspectFile(box, d, r, rel, p, my) {
    const entry = rel.entries.find(e => e.path === p);
    const k = fileKind(p);
    const links = (rel.relationships ? rel.relationships.links : []).filter(l => l.from === p || l.to.includes(p));
    const findings = rel.assessment.findings.filter(f => f.path === p);
    const status = h('span', { class: 'small muted', role: 'status' });
    const download = async () => {
      status.textContent = 'Downloading…';
      try {
        const res = await api('GET', releaseUrl(d, r) + '/file?path=' + enc(p), { raw: true });
        const blob = await res.blob();
        const expected = res.headers.get('X-Sanctity-SHA256');
        let verified = null;
        if (expected && crypto.subtle) {
          const got = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))].map(b => b.toString(16).padStart(2, '0')).join('');
          verified = got === expected;
        }
        if (verified === false) { status.replaceChildren(h('span', { class: 'chip err' }, 'Digest mismatch. The file was not saved.')); return; }
        const url = URL.createObjectURL(blob);
        h('a', { href: url, download: p.split('/').pop() }).click();
        setTimeout(() => URL.revokeObjectURL(url), 2000);
        status.replaceChildren(verified ? h('span', { class: 'chip ok' }, I.check(), 'SHA-256 verified · ', shortDigest(expected))
          : h('span', { class: 'chip warn' }, 'Saved without verification (needs HTTPS or localhost)'));
      } catch (e) { status.textContent = explain(e); }
    };
    box.append(h('header', { class: 'inspector-head' },
      h('div', { class: 'stack s6 grow' }, h('span', { class: 'eyebrow' }, entry ? [k ? k.toUpperCase() + ' · ' : '', h('span', { class: 'nocaps' }, bytes(entry.size))] : 'Not in this release'),
        h('h2', { class: 'path' }, pathText(p)), status),
      h('div', { class: 'row nowrap' },
        (k === 'json' || /_(beh|events)\.tsv$/.test(p)) ? h('a', { class: 'btn sm', href: '#/d/' + enc(d) + '/r/' + enc(r) + '/correct?path=' + enc(p) }, I.edit(), 'Correct') : null,
        k === 'nii' && entry && !rel.entries.some(e => e.path === stem(p) + '.json') ? h('a', { class: 'btn sm', href: '#/d/' + enc(d) + '/r/' + enc(r) + '/correct?path=' + enc(stem(p) + '.json'),
          title: 'Add ' + stem(p).split('/').pop() + '.json to override inherited values for this image' }, I.plus(), 'Add sidecar') : null,
        entry ? h('button', { class: 'btn sm', type: 'button', onclick: download }, I.down(), 'Download') : null)));

    // Fetch first, then append every section below the head at once, so nothing already shown moves.
    const isText = TEXT_KINDS.has(k) && entry && entry.size <= 262144;
    const [meta, text] = await Promise.allSettled([k === 'json' ? Promise.resolve(null) : getMetadata(d, r, p), isText ? getFileText(d, r, p) : Promise.resolve(null)]);
    if (my !== generation) return;
    const section = (title, ...body) => h('section', { class: 'section' }, h('h3', { class: 'overline' }, title), ...body);
    if (findings.length) box.append(section('Findings', findingList(findings, false, null, null, null, keyFacts(rel.assessment.checks))));
    const noMeta = meta.status === 'rejected' && meta.reason instanceof ApiError && meta.reason.code === 'not_found';
    if (k !== 'json' && !(noMeta && k !== 'nii')) {
      const m = meta.value;
      box.append(section('Effective metadata', meta.status === 'rejected'
        ? h('p', { class: 'muted small' }, meta.reason instanceof ApiError && meta.reason.code === 'not_found' ? 'No interpreted metadata for this file.' : explain(meta.reason))
        : [Object.keys(m.fields).length ? h('dl', { class: 'fields' }, Object.keys(m.fields).map(n => fieldRow(n, m.fields[n], p)))
            : h('p', { class: 'muted small' }, 'No metadata applies to this file.'),
          m.sources.length ? h('p', { class: 'origin sources' }, 'Resolved from ', m.sources.map((s, i) => [i ? ', ' : '', h('span', { class: 'mono' }, s)])) : null]));
    }
    const fileChecks = rel.assessment.checks.filter(x => x.target === p);
    if (fileChecks.length) box.append(section('Content checks', h('ul', { class: 'checks inline' }, fileChecks.map(x => checkItem(x, null, x.findings.length > 0 && !narrow())))));
    if (links.length) box.append(section('Linked acquisitions', relRows(links, p, q => go(relHash(d, r, { path: q })))));
    else if (k === 'nii' && /\/fmap\//.test(p)) box.append(section('Linked acquisitions', h('p', { class: 'muted small' }, 'No run selects this fieldmap: no IntendedFor names it and no B0FieldSource matches its B0FieldIdentifier.')));
    if (isText) box.append(section('Content', text.status === 'rejected' ? h('p', { class: 'muted small' }, explain(text.reason))
      : k === 'tsv' ? tsvTable(text.value) : h('pre', { class: 'code', tabindex: '0', 'aria-label': 'Content of ' + p }, text.value)));
  }

  function tsvTable(t) {
    const rows = t.replace(/\n$/, '').split('\n').map(l => l.split('\t'));
    const head = rows.shift() || [];
    const shown = rows.slice(0, 50);
    return h('div', { class: 'scroll-x', tabindex: '0', role: 'region', 'aria-label': 'Table content' },
      h('table', { class: 'data compact' }, h('thead', {}, h('tr', {}, head.map(c => h('th', {}, c)))),
        h('tbody', {}, shown.map(r => h('tr', {}, r.map(c => h('td', { class: DECIMAL.test(c) ? 'num mono' : 'mono' }, c)))))),
      rows.length > shown.length ? h('p', { class: 'small muted pad-sm' }, 'First 50 of ' + rows.length + ' rows.') : null);
  }

  // Where a value comes from, relative to the inspected file.
  function originLabel(origin, path) {
    if (origin === path) return ['Set on this file'];
    if (stem(origin) === stem(path)) return ['This file’s sidecar'];
    return ['Inherited from ', h('span', { class: 'mono' }, pathText(origin))];
  }
  function fieldRow(name, f, path) {
    const overridden = f.history.filter(c => c.source !== f.origin);
    return h('div', { class: 'fieldrow' },
      h('dt', {}, h('span', { class: 'k' }, name), h('span', { class: 'origin' }, originLabel(f.origin, path))),
      h('dd', {}, valueNode(name, f.value),
        overridden.length ? h('ol', { class: 'cascade', 'aria-label': 'Contributions to ' + name + ', in resolution order' },
          f.history.map(c => h('li', { class: 'step ' + (c.source === f.origin ? 'win' : 'over') },
            h('span', { class: 'pip', 'aria-hidden': 'true' }), h('span', { class: 'src' }, c.source), h('span', { class: 'val' }, valueNode(name, c.value)),
            h('span', { class: 'tag' }, c.source === f.origin ? 'effective' : 'overridden')))) : null));
  }

  // Identical findings on several files collapse into one entry listing the files. `tags` maps a finding key to a delta label.
  const findingKey = f => f.code + '|' + f.severity + '|' + (f.path || '') + '|' + f.message;
  // Row/column positions vary per occurrence; the rest of the message is the finding's meaning.
  const messageShape = m => m.replace(/\brow \d+/g, 'row …').replace(/\bcolumn \d+/g, 'column …');
  // The positions a grouped message elides, e.g. "rows 50, 51"; a position shared by every occurrence stays in the message.
  const POSITIONS = [['row', 'rows'], ['column', 'columns']];
  const positionsOf = (fs, word) => [...new Set(fs.map(x => (new RegExp('\\b' + word + ' (\\d+)').exec(x.message) || [])[1]).filter(Boolean))].sort((a, b) => a - b);
  const listPositions = ns => ns.slice(0, 12).join(', ') + (ns.length > 12 ? ' and ' + (ns.length - 12) + ' more' : '');
  function groupMessage(fs) {
    let m = fs[0].message;
    for (const [one, many] of POSITIONS) if (positionsOf(fs, one).length > 1) m = m.replace(new RegExp('\\b' + one + ' \\d+', 'g'), many + ' ' + listPositions(positionsOf(fs, one)));
    return m;
  }
  // Findings whose meaning is a comparison: the recorded facts that hold the compared values.
  const KEY_FACTS = {
    MriRepetitionTimeMismatch: [['Authored RepetitionTime', 'timingMetadata.RepetitionTime.value', 'RepetitionTime'], ['Header volume spacing', 'temporalSpacingSeconds', 'RepetitionTime']],
  };
  // detail(f) -> list of [label, value] for one finding, from the check that reported it.
  const keyFacts = checks => f => {
    const spec = KEY_FACTS[f.code];
    const c = spec && checks.find(k => k.target === f.path && k.findings.some(x => x.code === f.code));
    if (!c) return null;
    const x = flatFacts(c.facts);
    const got = spec.filter(([, k]) => k in x).map(([label, k, unit]) => [label, x[k], unit]);
    return got.length ? got : null;
  };
  function findingList(findings, withPath = true, open, tags, why, detail) {
    const groups = new Map();
    for (const f of findings) { const k = f.code + '|' + f.severity + '|' + messageShape(f.message) + '|' + (tags ? tags(f) : ''); groups.set(k, [...(groups.get(k) || []), f]); }
    return h('ul', { class: 'findings' }, [...groups.values()].map(fs => {
      const f = fs[0], tag = tags ? tags(f) : null;
      const paths = new Map(); fs.forEach(x => x.path && paths.set(x.path, [...(paths.get(x.path) || []), x]));
      const many = paths.size > 1, varying = POSITIONS.filter(([one]) => positionsOf(fs, one).length > 1);
      // Several files: positions vary by file, so each file lists its own.
      const at = xs => varying.length ? varying.map(([one, more]) => (positionsOf(xs, one).length > 1 ? more : one) + ' ' + listPositions(positionsOf(xs, one))).join(' · ')
        : xs.length > 1 ? plural(xs.length, 'occurrence') : null;
      // Compared values: once for the entry when every file agrees, otherwise on each file's row.
      const factsOf = new Map(detail ? [...paths].map(([p, xs]) => [p, xs.length === 1 ? detail(xs[0]) : null]) : []);
      const shared = factsOf.size && [...factsOf.values()].every(v => v && JSON.stringify(v) === JSON.stringify([...factsOf.values()][0])) ? [...factsOf.values()][0] : null;
      const factList = (facts, cls) => h('dl', { class: 'keyfacts' + (cls || '') }, facts.map(([k, v, unit]) => h('div', {}, h('dt', {}, k), h('dd', {}, valueNode(unit, v)))));
      const where = [...paths].map(([p, xs]) => h('span', { class: 'where-row' },
        open ? h('button', { class: 'where linkish', type: 'button', onclick: () => open(p) }, pathText(p)) : h('span', { class: 'where' }, pathText(p)),
        many && at(xs) ? h('span', { class: 'where-n' }, at(xs)) : null,
        !shared && factsOf.get(p) ? factList(factsOf.get(p), ' inline') : null));
      return h('li', { class: 'finding ' + f.severity },
        h('span', { class: 'sev', title: f.severity }, f.severity === 'Error' ? I.err() : I.warn()),
        h('div', { class: 'stack s4' },
          h('div', { class: 'row' }, tag ? h('span', { class: 'tag delta ' + tag.toLowerCase().replace(/\s+/g, '-') }, tag) : null,
            h('span', { class: 'code' }, f.code), fs.length > 1 ? h('span', { class: 'count' }, plural(fs.length, 'occurrence')) : null,
            h('span', { class: 'sev-label' }, f.severity === 'Error' ? 'Blocks acceptance' : 'Recorded warning')),
          h('p', { class: 'msg' }, many ? groupMessage(fs).replace(/\b(rows|columns) \d+(?:, \d+)*(?: and \d+ more)?/g, '$1 listed by file') : groupMessage(fs)),
          shared ? factList(shared) : null,
          why && why(f) ? h('p', { class: 'why' }, why(f)) : null,
          !withPath || !where.length ? null : where.length > 4
            ? h('details', { class: 'where-more' }, h('summary', {}, 'Show ' + plural(where.length, 'file')), h('div', { class: 'stack s2' }, where))
            : h('div', { class: 'stack s2' }, where)));
    }));
  }

  function assessmentPanel(a, open) {
    const errors = a.findings.filter(f => f.severity === 'Error');
    const warnings = a.findings.filter(f => f.severity !== 'Error');
    return h('div', { class: 'split' },
      h('div', { class: 'stack s18' },
        h('section', { class: 'card pad stack' },
          h('div', { class: 'row between' },
            h('h2', {}, a.admissible ? 'Admitted' : 'Blocked by admission'), hashChip(a.id, 'assessment id')),
          h('p', { class: 'small muted' }, 'Admission policy ', h('span', { class: 'mono' }, a.policy)),
          h('div', { class: 'tally' },
            h('div', { class: errors.length ? 'err' : '' }, h('b', {}, errors.length), h('span', {}, errors.length === 1 ? 'error' : 'errors')),
            h('div', { class: warnings.length ? 'warn' : '' }, h('b', {}, warnings.length), h('span', {}, warnings.length === 1 ? 'warning' : 'warnings')),
            h('div', {}, h('b', {}, a.checks.length), h('span', {}, 'content checks')))),
        a.findings.length ? findingList([...errors, ...warnings], true, open, null, null, keyFacts(a.checks)) : h('p', { class: 'card empty' }, 'No findings. Every assessed rule passed.')),
      h('section', { class: 'stack' }, h('h2', { class: 'overline' }, 'Content checks'),
        a.checks.length ? checksList(a.checks, open) :
          h('p', { class: 'card empty' }, 'No content checks recorded.')));
  }
  const checkChip = c => c.findings.length ? h('span', { class: 'chip ' + (c.findings.some(f => f.severity === 'Error') ? 'err' : 'warn') }, plural(c.findings.length, 'finding'))
    : h('span', { class: 'chip ok' }, I.check(), 'Consistent');
  // A check names its inputs; its recorded facts (often a dozen or more) sit behind a disclosure, opened where findings need them.
  function checkItem(c, open, showFacts) {
    const facts = Object.entries(flatFacts(c.facts));
    return h('li', { class: 'check' },
      h('div', { class: 'row between nowrap' },
        open ? h('button', { class: 'linkish mono small', type: 'button', onclick: () => open(c.target) }, pathText(c.target)) : h('span', { class: 'mono small strong' }, c.rule.split('+')[0]),
        checkChip(c)),
      c.findings.length ? h('p', { class: 'small' }, [...new Set(c.findings.map(f => f.code))].join(', ')) : null,
      h('div', { class: 'facts' }, c.inputs.map(i => h('span', { class: 'fact digest', title: i.path + ' · sha256 ' + i.digest },
        h('b', {}, i.role), h('span', { class: 'mono', 'aria-hidden': 'true' }, pathText(i.path.split('/').pop())), h('span', { class: 'sr-only' }, ' ' + i.path + ', digest '), h('span', { class: 'dg' }, shortDigest(i.digest))))),
      h('details', { class: 'facts-more', open: showFacts }, h('summary', {}, plural(facts.length, 'recorded fact'), h('span', { class: 'muted' }, ' · rule and observations')),
        h('p', { class: 'rule' }, c.rule),
        facts.length ? h('dl', { class: 'factlist' }, facts.map(([k, v]) => h('div', {}, h('dt', {}, k), h('dd', {}, show(v))))) : null));
  }
  // One disclosure per rule family, checks with findings first. Families with blocking errors start open; warnings stay folded,
  // since real releases often carry a warning on nearly every check.
  function checksList(checks, open) {
    const families = new Map();
    checks.forEach(c => { const f = c.rule.split('+')[0]; families.set(f, [...(families.get(f) || []), c]); });
    return h('div', { class: 'stack s10' }, [...families].map(([family, cs]) => {
      const flagged = cs.filter(c => c.findings.length), err = flagged.some(c => c.findings.some(f => f.severity === 'Error'));
      return h('details', { class: 'card checks-ok', open: err },
        h('summary', {}, h('span', { class: 'stack s2 grow' }, h('span', { class: 'mono small strong' }, family), h('span', { class: 'small muted' }, plural(cs.length, 'check'))),
          flagged.length ? h('span', { class: 'chip ' + (err ? 'err' : 'warn') }, flagged.length + ' with findings') : h('span', { class: 'chip ok' }, I.check(), 'All consistent')),
        h('ul', { class: 'checks' }, [...flagged, ...cs.filter(c => !c.findings.length)].map(c => checkItem(c, open))));
    }));
  }

  const KIND = {
    // Fieldmap links run from the corrected image to its fieldmap sources (BidsInterpreter fieldmap associations).
    'bids.intended-for': ['Fieldmaps by run', 'Each run and the fieldmaps whose IntendedFor names it'],
    'bids.b0-field-source': ['B0 field sources', 'Each run and the fieldmaps its B0FieldSource selects'],
    'bids.dwi-gradients': ['DWI gradients', 'Diffusion image and its bval/bvec tables'],
    'bids.task-events': ['Task events', 'BOLD run and its events table'],
    'bids.phasediff-magnitudes': ['Phase-difference family', 'Phase difference and magnitude images'],
  };
  const FIELDMAP = new Set(['bids.intended-for', 'bids.b0-field-source']);
  const kindName = k => (KIND[k] || [k])[0];
  const kindHelp = k => (KIND[k] || [k, ''])[1];
  const evidenceText = e => e.kind === 'companion-selection' ? 'companion rule ' + e.rule : e.document + ' · ' + (e.kind === 'table-column' ? 'column ' + e.column : e.field);
  function endpoint(p, focus, open) {
    return p === focus ? h('span', { class: 'endpoint self' }, pathText(p), h('span', { class: 'tag' }, 'this file'))
      : h('button', { class: 'endpoint linkish', type: 'button', onclick: () => open(p) }, pathText(p));
  }
  function relRows(links, focus, open, grouped) {
    return h('ul', { class: 'rels' + (grouped ? ' grouped' : '') }, links.map(l => h('li', { class: 'rel' },
      grouped ? (l.label ? h('div', { class: 'rel-kind' }, h('span', { class: 'mono small' }, l.label)) : null)
        : h('div', { class: 'rel-kind' }, h('b', {}, kindName(l.kind)), l.label ? h('span', { class: 'mono small muted' }, l.label) : null),
      h('div', { class: 'rel-ends' }, endpoint(l.from, focus, open),
        h('span', { class: 'rel-arrow', role: 'img', 'aria-label': FIELDMAP.has(l.kind) ? 'corrected by' : 'links to' }, I.arrow()),
        h('div', { class: 'stack s4' }, l.to.map(t => endpoint(t, focus, open)))),
      l.evidence.length ? h('p', { class: 'rel-evidence' }, I.link(), h('span', {}, 'Declared by ', l.evidence.map((e, i) => [i ? '; ' : '', h('span', { class: 'mono' }, evidenceText(e))]))) : null)));
  }
  function relationshipsPanel(links, open) {
    if (!links.length) return h('p', { class: 'card empty' }, 'No acquisition relationships were recorded for this release.');
    const groups = new Map();
    links.forEach(l => groups.set(l.kind, [...(groups.get(l.kind) || []), l]));
    return h('div', { class: 'stack s28' }, [...groups].map(([k, ls]) => h('section', { class: 'stack s10' },
      h('div', { class: 'row between' }, h('h2', {}, kindName(k), ' ', h('span', { class: 'count' }, ls.length)), h('span', { class: 'small muted' }, kindHelp(k))),
      h('div', { class: 'card' }, relRows(ls, null, open, true)))));
  }

  // ---------- correction ----------
  async function correctionView(d, r, q) {
    const my = ++generation;
    const ctx = { dataset: d, release: r };
    const want = q.get('path') || '';
    let c = state.correction;
    if (!c || c.base !== d + '/' + r || (want && c.path !== want)) {
      const carried = c && !c.receipt ? c.reason : '';
      // Moving to another release for the same file (e.g. "Correct the current head" after a conflict) carries the edit with the
      // content it was written against. The editor restores it only if that file is unchanged here (see restoreDraft).
      const untouched = c && c.draft && c.text === c.draft.text;
      const draft = c && !c.receipt && c.base !== d + '/' + r && c.path === want && c.text
        ? { text: c.text, original: untouched ? c.draft.original : c.original != null ? c.original : '', from: untouched ? c.draft.from : c.base.split('/')[1] } : null;
      c = state.correction = { key: uuid(), base: d + '/' + r, path: want, original: null, text: '', reason: carried, preview: null, receipt: null, draft };
    }
    let rel, headId;
    try { [rel, headId] = await Promise.all([getRelease(d, r), getHead(d).catch(() => null)]); } catch (e) { if (my === generation) failure(ctx, 'release', e, () => render()); return; }
    if (my !== generation) return;
    c.stale = !!headId && headId !== r && !c.receipt;
    c.headId = headId;
    const sidecars = rel.entries.filter(e => e.kind === 'file' && (e.path.endsWith('.json') || /_(beh|events)\.tsv$/.test(e.path))).map(e => e.path);
    const files = rel.entries.filter(e => e.kind === 'file').map(e => e.path);
    // A requested path that is not in the release is a new sidecar.
    if (c.path && !c.preview && !c.receipt && !files.includes(c.path)) { c.newMode = true; c.newPath = c.path; }
    const step = c.receipt ? 3 : c.preview ? 2 : 1;
    const steps = h('ol', { class: 'steps', 'aria-label': 'Correction progress' },
      ['Edit', 'Review preview', 'Accepted'].map((t, i) => h('li', { 'data-state': i + 1 < step ? 'done' : i + 1 === step ? 'current' : 'todo', 'aria-current': i + 1 === step ? 'step' : null },
        h('span', { class: 'n', 'aria-hidden': 'true' }, i + 1 < step ? I.check() : String(i + 1)), h('span', { class: 't' }, t))));
    const head = h('div', { class: 'page-head' }, h('div', { class: 'stack s10' },
      h('div', { class: 'crumbs' }, h('a', { href: relHash(d, r, c.path ? { path: c.path } : null) }, 'Release ', short(r)), I.chev(), h('span', {}, 'Correction')),
      h('h1', {}, c.path ? (c.newMode ? 'Add ' : 'Correct ') + c.path.split('/').pop() : 'Propose a correction'),
      h('p', { class: 'lead' }, 'Prepared against this exact release and assessed like any import. It becomes the dataset head only when someone with accept authority accepts it.')));
    const staleNote = c.stale ? h('div', { class: 'notice warn row between' }, h('span', {}, 'Release ' + short(r) + ' is not the current dataset head, so a correction prepared here could not be accepted.'),
      h('a', { class: 'btn sm', href: '#/d/' + enc(d) + '/r/' + enc(headId) + '/correct' + (c.path ? '?path=' + enc(c.path) : '') }, 'Correct the current head')) : null;
    const body = c.receipt ? receiptCard(d, c) : c.preview ? previewCard(d, r, c, rel) : await editCard(d, r, c, sidecars, files);
    if (my !== generation) return;
    page(ctx, 'release', head, staleNote, steps, body);
  }

  // A correction replaces one JSON sidecar, or adds a new one (e.g. a subject-level override that BIDS inheritance applies).
  const NEW_SIDECAR = '\u0000new';
  const sidecarPathProblem = (p, files) => {
    if (!p) return 'Give the new sidecar a path, such as sub-02/func/sub-02_task-nback_run-1_bold.json.';
    if (!/\.json$/.test(p)) return 'Sidecar paths end in .json.';
    if (/(^|\/)\.[^/]*$/.test(p)) return 'Name the sidecar after the files it applies to, such as sub-02_task-nback_bold.json.';
    if (/^\/|\/\/|(^|\/)\.\.?(\/|$)|\\/.test(p)) return 'Use a path relative to the dataset root, without “.” or “..” segments.';
    if (/\s/.test(p)) return 'BIDS paths do not contain spaces.';
    if (files.includes(p)) return 'That file already exists. Choose it from the list instead.';
    const under = files.find(f => p.startsWith(f + '/'));
    if (under) return under + ' is a file, so it cannot contain other files.';
    return null;
  };
  async function editCard(d, r, c, sidecars, files) {
    let text, reason, err, submitBtn, newPath;
    const draftNote = h('div', { class: 'stack s8' });
    // A carried edit is restored only onto the content it was written against; otherwise it would silently revert
    // whatever changed in between (a lost update). Then the editor shows the current file and the earlier edit as a diff.
    const restoreDraft = current => {
      const dr = c.draft;
      if (!dr || c.text) return;
      if (dr.original === current) {
        c.text = dr.text;
        draftNote.replaceChildren(h('p', { class: 'notice' }, 'Your edit from release ' + short(dr.from) + ' is restored. ' + (current ? 'This file has not changed since, so nothing else is overwritten.' : 'The file still does not exist here.')));
        return;
      }
      const diff = lineDiff(dr.original, dr.text);
      draftNote.replaceChildren(h('div', { class: 'notice warn stack s8' },
        h('p', {}, c.path.split('/').pop() + ' changed after your edit was written against release ' + short(dr.from) + '. The editor shows its current content; re-apply your change to it.'),
        diff ? h('details', { class: 'draft-diff' }, h('summary', {}, 'Your earlier edit'),
          h('pre', { class: 'code linediff', tabindex: '0' }, diff.map(([op, line]) => h('span', { class: 'ln ' + (op === '+' ? 'add' : op === '-' ? 'del' : '') },
            h('span', { class: 'op', 'aria-hidden': 'true' }, op), h('span', { class: 'sr-only' }, op === '+' ? 'added: ' : op === '-' ? 'removed: ' : ''), line, '\n')))) : null));
    };
    const load = async p => {
      c.path = p; c.original = null;
      if (!p) { text.value = ''; return; }
      if (c.newMode) { c.original = ''; restoreDraft(''); text.value = c.text; return; }
      text.value = ''; text.placeholder = 'Loading current content…';
      try { const t = await getFileText(d, r, p); if (c.path !== p) return; c.original = t; restoreDraft(t); text.value = c.text || t; text.placeholder = ''; }
      catch (e) { text.placeholder = explain(e) + ' Paste the complete corrected document.'; }
    };
    const submit = async ev => {
      ev.preventDefault();
      if (submitBtn.getAttribute('aria-busy') === 'true') return;
      err.replaceChildren();
      if (c.newMode) { c.path = newPath.value.trim(); c.original = ''; }
      c.text = text.value; c.reason = reason.value.trim();
      const problems = [], invalid = new Set();
      const pathProblem = c.newMode ? sidecarPathProblem(c.path, files) : !c.path ? 'Choose the document to correct.' : null;
      if (pathProblem) { problems.push(pathProblem); if (c.newMode) invalid.add(newPath); }
      if (c.path.endsWith('.json')) {
        try { JSON.parse(c.text); } catch (x) { problems.push('The ' + (c.newMode ? 'content' : 'replacement') + ' is not valid JSON: ' + x.message); invalid.add(text); }
      } else if (!c.text.trim()) { problems.push('Provide the complete replacement table.'); invalid.add(text); }
      if (!c.reason) { problems.push('Give a reason. It is recorded with the candidate.'); invalid.add(reason); }
      if (c.reason.length > 2000) { problems.push('Keep the reason to 2000 characters or fewer.'); invalid.add(reason); }
      if (!c.newMode && c.original != null && c.text === c.original) { problems.push('The content is unchanged.'); invalid.add(text); }
      invalid.forEach(el => el.setAttribute('aria-invalid', 'true'));
      if (invalid.size) [...invalid][0].focus();
      if (problems.length) { err.append(h('div', { class: 'notice err', role: 'alert' }, h('ul', { class: 'plain' }, problems.map(p => h('li', {}, p))))); return; }
      submitBtn.setAttribute('aria-busy', 'true'); submitBtn.textContent = 'Preparing candidate…';
      try {
        c.preview = await api('POST', releaseUrl(d, r) + '/corrections', { json: JSON.stringify({ path: c.path, text: c.text, reason: c.reason }) });
        announce('Candidate prepared. Review the preview.');
        focusKey = 'preview-head';
        // Keep ?path in step with an edited new-sidecar path, or the view would treat it as a different correction.
        go('#/d/' + enc(d) + '/r/' + enc(r) + '/correct?path=' + enc(c.path), true);
      } catch (e) {
        submitBtn.removeAttribute('aria-busy'); submitBtn.textContent = 'Prepare preview';
        err.append(h('p', { class: 'notice err', role: 'alert' }, explain(e)));
      }
    };
    const card = h('form', { class: 'split', onsubmit: submit, novalidate: true },
      h('div', { class: 'card pad stack s16' },
        h('div', { class: 'field' }, h('label', { for: 'c-path' }, 'Document'),
          h('select', { class: 'input mono', id: 'c-path', fk: 'c-path', onchange: ev => {
            const v = ev.target.value; c.drafts = { ...(c.drafts || {}), [c.newMode ? NEW_SIDECAR : c.path]: c.text };
            c.newMode = v === NEW_SIDECAR; c.path = c.newMode ? (c.newPath || '') : v; c.text = (c.drafts || {})[v] || ''; c.original = null; focusKey = c.newMode ? 'c-new' : 'c-path';
            go('#/d/' + enc(d) + '/r/' + enc(r) + '/correct' + (c.path ? '?path=' + enc(c.path) : ''), true);
          } },
            h('option', { value: '' }, 'Choose a JSON or table file…'), sidecars.map(s => h('option', { value: s, selected: !c.newMode && s === c.path }, s)),
            h('option', { value: NEW_SIDECAR, selected: !!c.newMode }, 'New sidecar…'))),
        c.newMode ? h('div', { class: 'field' }, h('label', { for: 'c-new' }, 'New sidecar path'),
          newPath = h('input', { class: 'input mono', id: 'c-new', fk: 'c-new', value: c.newPath || '', autocomplete: 'off', spellcheck: 'false', placeholder: 'sub-02/func/sub-02_task-nback_run-1_bold.json', 'aria-describedby': 'c-new-hint c-errors',
            oninput: () => { c.newPath = newPath.value; newPath.removeAttribute('aria-invalid'); } }),
          h('span', { class: 'hint', id: 'c-new-hint' }, 'BIDS inheritance applies it to the files it matches, after any dataset- or task-level sidecar.')) : null,
        h('div', { class: 'field' }, h('label', { for: 'c-text' }, c.newMode ? 'Content' : 'Complete replacement content'),
          text = h('textarea', { class: 'input', id: 'c-text', fk: 'c-text', spellcheck: 'false', rows: 12, placeholder: c.newMode ? '{ "RepetitionTime": 2.0 }' : null, 'aria-describedby': 'c-text-hint c-errors', oninput: () => { c.text = text.value; text.removeAttribute('aria-invalid'); } }),
          h('span', { class: 'hint', id: 'c-text-hint' }, c.newMode ? 'Only the fields you set here override inherited values.' : 'The file is replaced as a whole. Values inherited by other files are recomputed in the preview.')),
        h('div', { class: 'field' }, h('label', { for: 'c-reason' }, 'Reason'),
          reason = h('input', { class: 'input', id: 'c-reason', maxlength: '2000', value: c.reason, placeholder: 'e.g. Protocol sheet gives TR = 1.8 s', 'aria-describedby': 'c-errors', oninput: () => { c.reason = reason.value; reason.removeAttribute('aria-invalid'); } })),
        draftNote,
        err = h('div', { class: 'stack', id: 'c-errors' }),
        h('div', { class: 'row end' },
          h('a', { class: 'btn ghost', href: relHash(d, r, c.path && !c.newMode ? { path: c.path } : null) }, 'Cancel'),
          submitBtn = h('button', { class: 'btn primary', type: 'submit', disabled: c.stale }, 'Prepare preview'))),
      h('aside', { class: 'stack s14 explainer' },
        h('h2', { class: 'overline' }, 'What happens next'),
        h('ol', { class: 'numbered' },
          ['Sanctity prepares a candidate from this release plus your edit.', 'It re-resolves metadata inheritance and acquisition relationships, then re-runs admission checks.',
           'You review every changed value and finding before anything is accepted.', 'Acceptance is atomic and safe to retry. This release never changes.']
            .map(t => h('li', {}, t)))));
    if (c.newMode) { c.original = ''; restoreDraft(''); text.value = c.text || ''; }
    else if (c.path) await load(c.path);
    return card;
  }

  // Minimal line diff (LCS) for small sidecars.
  function lineDiff(a, b) {
    const lines = t => t === '' ? [] : t.replace(/\n$/, '').split('\n');
    const x = lines(a), y = lines(b);
    if (x.length * y.length > 250000) return null;
    const m = x.length, n = y.length, t = Array.from({ length: m + 1 }, () => new Uint16Array(n + 1));
    for (let i = m - 1; i >= 0; i--) for (let j = n - 1; j >= 0; j--) t[i][j] = x[i] === y[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
    const out = []; let i = 0, j = 0;
    while (i < m || j < n) {
      if (i < m && j < n && x[i] === y[j]) { out.push([' ', x[i]]); i++; j++; }
      else if (i < m && (j === n || t[i + 1][j] >= t[i][j + 1])) out.push(['-', x[i++]]);
      else out.push(['+', y[j++]]);
    }
    return out;
  }

  function previewCard(d, r, c, base) {
    const p = c.preview, cand = p.candidate, a = cand.assessment;
    const before = new Set((base ? base.assessment.findings : []).map(findingKey));
    const after = new Set(a.findings.map(findingKey));
    const resolved = base ? base.assessment.findings.filter(f => !after.has(findingKey(f))) : [];
    const tags = base ? f => before.has(findingKey(f)) ? 'Unchanged' : 'New' : null;
    // A finding changed because a check it belongs to reads the edited file; name the facts that moved.
    const checkOf = (f, checks) => checks.find(k => k.findings.some(x => findingKey(x) === findingKey(f)));
    const sameCheck = (k, checks) => k && checks.find(x => x.rule === k.rule && x.target === k.target);
    // The same check (rule + target) before and after the edit, found from either side.
    const pairOf = f => {
      const before = base && (checkOf(f, base.assessment.checks) || sameCheck(checkOf(f, a.checks), base.assessment.checks));
      return [before, checkOf(f, a.checks) || sameCheck(before, a.checks)];
    };
    const cause = f => {
      const [was, now] = pairOf(f), k = now || was;
      if (!k || ![was, now].some(x => x && x.inputs.some(i => i.path === c.path))) return null;
      const x = flatFacts(was && was.facts), y = flatFacts(now && now.facts);
      const moved = was && now ? [...new Set([...Object.keys(x), ...Object.keys(y)])].filter(n => JSON.stringify(x[n]) !== JSON.stringify(y[n]))
        .map(n => n + ' ' + (n in x ? show(x[n]) : 'absent') + ' → ' + (n in y ? show(y[n]) : 'absent')) : [];
      return 'Re-checked because ' + c.path + ' is an input of ' + k.rule.split('+')[0] + '.' +
        (moved.length ? ' Changed: ' + moved.slice(0, 4).join('; ') + (moved.length > 4 ? '; and ' + (moved.length - 4) + ' more' : '') + '.' : '');
    };
    // Resolved only when the same check still ran, observed the same facts and no longer reports this code;
    // other findings on that check (e.g. an unrelated transform warning) do not count against it.
    // Findings without a producing content check (admission rules) get no claim beyond "no longer reported".
    const outcome = f => {
      const was = checkOf(f, base.assessment.checks);
      if (!was) return null;
      const now = sameCheck(was, a.checks);
      if (!now) return 'Not evaluated';
      if (now.findings.some(x => x.code === f.code)) return 'Superseded';
      const x = flatFacts(was.facts), y = flatFacts(now.facts);
      if (Object.keys(x).some(k => !(k in y) || y[k] === null)) return 'Not evaluated';
      return 'Resolved';
    };
    const fresh = a.findings.filter(f => tags && tags(f) === 'New').length;
    // What needs a decision comes first: blocking errors, then findings this change introduces; then what it cleared.
    const attention = [...a.findings.filter(f => f.severity === 'Error'), ...a.findings.filter(f => f.severity !== 'Error' && (!tags || tags(f) === 'New'))];
    const newWhy = f => tags && tags(f) === 'New' ? cause(f) : null;
    const findingSections = [
      [!tags ? 'Findings after this change' : attention.some(f => f.severity === 'Error') ? 'Blocking and new findings' : 'New findings', attention, tags, newWhy, a.checks],
      ['No longer reported', resolved.filter(f => outcome(f) !== 'Not evaluated'), outcome, cause, base ? base.assessment.checks : []],
      ['Not evaluated after this change', resolved.filter(f => outcome(f) === 'Not evaluated'), outcome, cause, base ? base.assessment.checks : []],
      ['Unchanged findings', tags ? a.findings.filter(f => !attention.includes(f)) : [], tags, null, a.checks]];
    const blocked = !a.admissible || a.findings.some(f => f.severity === 'Error');
    let err, btn;
    const accept = async () => {
      if (blocked || btn.getAttribute('aria-busy') === 'true') return;
      err.replaceChildren(); btn.setAttribute('aria-busy', 'true'); btn.lastChild.textContent = 'Accepting…';
      try {
        c.receipt = await api('POST', '/v1/candidates/' + enc(cand.id) + '/accept', { headers: { 'Idempotency-Key': c.key } });
        memo.clear(); announce('Correction accepted. A new release is the dataset head.'); focusKey = 'receipt-head'; render();
      } catch (e) {
        btn.removeAttribute('aria-busy'); btn.lastChild.textContent = 'Accept as new release';
        memo.delete('head:' + d);
        const now = e instanceof ApiError && e.code === 'conflict' ? await getHead(d).catch(() => null) : null;
        err.append(h('div', { class: 'notice err row between', role: 'alert' }, h('span', {}, explain(e)),
          now && now !== r ? h('a', { class: 'btn sm', href: '#/d/' + enc(d) + '/r/' + enc(now) + '/correct' + (c.path ? '?path=' + enc(c.path) : '') }, 'Correct the current head') : null));
      }
    };
    const byPath = new Map();
    p.metadataChanges.forEach(m => byPath.set(m.path, [...(byPath.get(m.path) || []), ...m.fields]));
    const inert = !byPath.size && p.contentChanges.some(x => !x.before && x.path.endsWith('.json'));
    const val = (name, f) => f ? h('span', { class: 'stack s2' }, valueNode(name, f.value), h('span', { class: 'origin' }, 'from ', h('span', { class: 'mono' }, pathText(f.origin)))) : h('span', { class: 'none' }, 'absent');
    c.firstAffected = p.metadataChanges.length ? p.metadataChanges[0].path : null;
    const diff = c.original != null ? lineDiff(c.original, c.text) : null;
    let keys = []; try { keys = Object.keys(JSON.parse(c.text)); } catch { /* validated earlier */ }
    // Errors on the edited file or on any file whose effective metadata it changes (e.g. InconsistentMetadata on the images).
    const affected = new Set([c.path, ...p.metadataChanges.map(m => m.path)]);
    const errs = a.findings.filter(f => f.severity === 'Error' && affected.has(f.path));
    const flaggedKeys = keys.filter(k => errs.some(f => f.message.includes(k)));
    const flagged = line => flaggedKeys.some(k => line.includes('"' + k + '"'));
    return h('div', { class: 'split' },
      h('div', { class: 'stack s18' },
        h('section', { class: 'card pad stack s12' },
          h('div', { class: 'row between' }, h('h2', { tabindex: '-1', fk: 'preview-head' }, 'Effective metadata changes'),
            h('span', { class: 'chip' }, byPath.size + (byPath.size === 1 ? ' file affected' : ' files affected'))),
          byPath.size ? h('ul', { class: 'changes' }, [...byPath].map(([path, fs]) => h('li', { class: 'change' },
            h('p', { class: 'change-path mono' }, pathText(path)),
            h('dl', { class: 'change-fields' }, fs.map(f => h('div', { class: 'change-row' },
              h('dt', { class: 'k' }, f.name),
              h('dd', { class: 'before' }, h('span', { class: 'sr-only' }, 'before: '), val(f.name, f.before)),
              h('dd', { class: 'to', 'aria-hidden': 'true' }, I.arrow()),
              h('dd', { class: 'after' }, h('span', { class: 'sr-only' }, 'after: '), val(f.name, f.after),
                f.before && f.after && JSON.stringify(f.before.value) === JSON.stringify(f.after.value) ? h('span', { class: 'tag' }, 'Same value, new source') : null)))))))
            : inert
              ? h('p', { class: 'notice warn' }, 'The new sidecar changes no file’s effective metadata. BIDS inheritance applies it only to files in its directory or below whose entities and suffix it matches.')
              : h('p', { class: 'muted' }, 'No effective metadata changes.'),
          h('p', { class: 'hint' }, 'Only files whose effective values or their sources change are listed. Files that set these fields in their own sidecars keep their values.')),
        diff ? h('section', { class: 'card pad stack s12' },
          h('div', { class: 'row between' }, h('h2', {}, 'Sidecar change'), h('span', { class: 'mono small muted' }, c.path)),
          h('pre', { class: 'code linediff', tabindex: '0', 'aria-label': 'Line changes to ' + c.path },
            diff.map(([op, line]) => { const bad = op === '+' && flagged(line); return h('span', { class: 'ln ' + (op === '+' ? 'add' : op === '-' ? 'del' : '') + (bad ? ' flagged' : '') },
              h('span', { class: 'op', 'aria-hidden': 'true' }, bad ? '!' : op), h('span', { class: 'sr-only' }, op === '+' ? (bad ? 'added, blocked by a finding: ' : 'added: ') : op === '-' ? 'removed: ' : ''), line, '\n'); }))) : null,
        p.relationshipChanges.length ? h('section', { class: 'card pad stack' }, h('h2', {}, 'Relationship changes'),
          p.relationshipChanges.map(rc => h('div', { class: 'stack s6' }, h('p', { class: 'mono small strong' }, rc.path),
            h('p', { class: 'small muted' }, rc.before.length + ' before → ' + rc.after.length + ' after',
              rc.changedDependencies.length ? '; changed dependencies: ' + rc.changedDependencies.join(', ') : '')))) : null,
        p.contentChanges.length ? h('section', { class: 'card pad stack' }, h('h2', {}, 'Content changes'),
          h('table', { class: 'data stackable' }, h('thead', {}, h('tr', {}, h('th', {}, 'Path'), h('th', { class: 'num' }, 'Before'), h('th', { class: 'num' }, 'After'))),
            h('tbody', {}, p.contentChanges.map(x => h('tr', {}, h('td', { class: 'mono', 'data-label': 'Path' }, pathText(x.path)),
              h('td', { class: 'num', 'data-label': 'Before' }, x.before ? bytes(x.before.size) : 'New file'),
              h('td', { class: 'num', 'data-label': 'After' }, !x.after ? 'Removed' : x.before && x.before.size === x.after.size ? 'Same size, new content' : bytes(x.after.size))))))) : null),
      h('aside', { class: 'card pad stack s16 sticky decision' },
        h('h2', {}, 'Candidate'),
        a.admissible ? h('span', { class: 'seal ok' }, I.seal(), 'Admissible') : h('span', { class: 'seal err' }, I.stop(), 'Not admissible'),
        h('dl', { class: 'receipt' }, h('dt', {}, 'Candidate'), h('dd', {}, hashChip(cand.id, 'candidate id', true)),
          h('dt', {}, 'Base release'), h('dd', {}, hashChip(cand.base || r, 'base release')),
          h('dt', {}, 'Generation'), h('dd', {}, String(cand.generation)), h('dt', {}, 'Reason'), h('dd', {}, c.reason),
          h('dt', {}, 'Manifest'), h('dd', {}, hashChip(cand.manifest, 'manifest digest')),
          h('dt', {}, 'Interpretation'), h('dd', {}, hashChip(cand.interpretation, 'interpretation digest')),
          h('dt', {}, 'Assessment'), h('dd', {}, hashChip(a.id, 'assessment digest')),
          h('dt', {}, 'Retry key'), h('dd', {}, hashChip(c.key, 'idempotency key'))),
        h('p', { class: 'finding-summary small' }, a.findings.length || resolved.length
          ? [h('b', {}, String(fresh)), ' new, ', h('b', {}, String(a.findings.length - fresh)), ' unchanged, ', h('b', {}, String(resolved.length)), ' no longer reported. Details below.']
          : 'No findings before or after this change.'),
        inert ? h('p', { class: 'notice warn small' }, 'Accepting adds the file but changes no effective metadata.') : null,
        err = h('div', { class: 'stack' }),
        h('div', { class: 'row' },
          btn = h('button', { class: 'btn ' + (inert ? '' : 'primary'), type: 'button', fk: 'accept', disabled: blocked, onclick: accept }, I.check(), h('span', {}, 'Accept as new release')),
          h('button', { class: 'btn ghost', type: 'button', onclick: () => { c.preview = null; c.key = uuid(); focusKey = 'c-text'; render(); } }, 'Back to edit')),
        h('p', { class: 'small muted' }, blocked ? 'Errors block acceptance. Edit the sidecar and prepare again.' : 'Requires accept authority. Retrying with the same key is safe.'),
        ...findingSections.map(([title, fs, label, why, checks]) => fs.length ? h('div', { class: 'stack s8' }, h('h3', { class: 'overline' }, title), findingList(fs, true, null, label, why, keyFacts(checks))) : null),
        a.findings.length || resolved.length ? null : h('p', { class: 'small muted' }, 'No findings.')));
  }

  function receiptCard(d, c) {
    const rc = c.receipt, cand = c.preview && c.preview.candidate;
    const verify = h('dl', { class: 'receipt verify' }, h('dt', {}, 'Identity'), h('dd', { class: 'muted' }, 'Checking the new release…'));
    if (cand) getRelease(d, rc.ref.release).then(rel => {
      const rows = [['Manifest', rel.manifest, cand.manifest], ['Interpretation', rel.interpretation, cand.interpretation], ['Assessment', rel.assessment.id, cand.assessment.id]];
      verify.replaceChildren(...rows.flatMap(([k, got, want]) => [h('dt', {}, k), h('dd', { class: 'row' }, hashChip(got, k.toLowerCase() + ' digest'),
        got === want ? h('span', { class: 'chip ok' }, I.check(), 'Matches preview') : h('span', { class: 'chip err' }, 'Differs from preview ', shortDigest(want)))]));
      const okAll = rows.every(([, got, want]) => got === want);
      announce(okAll ? 'New release identity matches the preview.' : 'Warning: the new release identity differs from the preview.');
    }).catch(e => verify.replaceChildren(h('dt', {}, 'Identity'), h('dd', { class: 'muted' }, explain(e))));
    return h('section', { class: 'card pad stack s18 measure' },
      h('span', { class: 'seal ok' }, I.seal(), 'Accepted'),
      h('h2', { tabindex: '-1', fk: 'receipt-head' }, 'A new release is now the dataset head'),
      h('dl', { class: 'receipt' },
        h('dt', {}, 'Release'), h('dd', {}, hashChip(rc.ref.release, 'release id', true)),
        h('dt', {}, 'Candidate'), h('dd', {}, hashChip(rc.candidate, 'candidate id', true)),
        h('dt', {}, 'Accepted'), h('dd', {}, when(rc.acceptedAt)),
        h('dt', {}, 'Retry key'), h('dd', {}, hashChip(rc.key, 'idempotency key'))),
      h('h3', { class: 'overline' }, 'New release identity'), verify,
      h('div', { class: 'row' }, h('a', { class: 'btn primary', href: relHash(d, rc.ref.release, c.firstAffected || c.path ? { path: c.firstAffected || c.path } : null), onclick: () => { state.correction = null; } }, 'Open new release')));
  }

  // ---------- query ----------
  const ART_OPS = { suffix: 'Suffix is', entity: 'Entity equals', metadataEquals: 'Metadata equals', metadataBelow: 'Metadata below' };
  const PART_OPS = { fieldEquals: 'Column equals', fieldAtLeast: 'Column at least' };
  const numeric = op => op === 'fieldAtLeast' || op === 'metadataBelow';
  const describeQuery = s => [
    ...s.participants.map(c => 'participant ' + c.name + (c.op === 'fieldAtLeast' ? ' ≥ ' : ' = ') + c.value),
    ...s.artifacts.map(c => c.op === 'suffix' ? 'suffix ' + c.value : c.op === 'entity' ? c.name + '-' + c.value : c.name + (c.op === 'metadataBelow' ? ' < ' : ' = ') + c.value)];
  function queryView(d, q) {
    const ctx = { dataset: d, release: q.get('release') };
    const s = state.query && state.query.dataset === d ? state.query : (state.query = {
      dataset: d, releases: ctx.release ? [ctx.release] : [], participants: [{ op: 'fieldAtLeast', name: 'age', value: '' }], artifacts: [{ op: 'suffix', name: '', value: 'bold' }],
    });
    const redraw = key => { focusKey = key; queryView(d, q); };
    let err;
    const condRows = (list, ops, kind) => h('div', { class: 'stack s8' }, list.map((c, i) => {
      const n = kind + ' condition ' + (i + 1);
      return h('div', { class: 'cond' },
        h('select', { class: 'input', fk: kind + i + ':op', 'aria-label': n + ' operator', onchange: ev => { c.op = ev.target.value; redraw(kind + i + ':op'); } },
          Object.entries(ops).map(([k, l]) => h('option', { value: k, selected: c.op === k }, l))),
        c.op === 'suffix' ? h('span', { class: 'muted small cond-note' }, 'BIDS suffix') :
          h('input', { class: 'input', fk: kind + i + ':name', placeholder: kind === 'Participant' ? 'column' : c.op === 'entity' ? 'entity, e.g. task' : 'field', value: c.name, 'aria-label': n + ' name', oninput: ev => { c.name = ev.target.value; } }),
        h('input', { class: 'input', fk: kind + i + ':value', inputmode: numeric(c.op) ? 'decimal' : null, placeholder: numeric(c.op) ? 'number' : 'value', value: c.value, 'aria-label': n + ' value', oninput: ev => { c.value = ev.target.value; } }),
        h('button', { class: 'btn ghost icon-btn', type: 'button', 'aria-label': 'Remove ' + n.toLowerCase(), onclick: () => { list.splice(i, 1); redraw('add:' + kind); } }, I.x()));
    }));
    // Numbers are spliced in as their exact lexical form; JSON.parse/stringify would round them.
    const NUM = '\u0000num:';
    const encode = (c, kind, i) => {
      const v = c.value.trim();
      const bad = msg => { const e = new Error(msg); e.fk = kind + i + ':value'; if (/name/.test(msg)) e.fk = kind + i + ':name'; return e; };
      if (!v) throw bad(kind + ' conditions need a value.');
      if (c.op !== 'suffix' && !c.name.trim()) throw bad(kind + ' conditions need a name.');
      if (numeric(c.op)) { if (!DECIMAL.test(v) || v.length > 100) throw bad('“' + v + '” is not a plain decimal number, such as 25 or 1.5.'); return { op: c.op, name: c.name.trim(), value: NUM + v }; }
      return c.op === 'suffix' ? { op: 'suffix', value: v } : { op: c.op, name: c.name.trim(), value: v };
    };
    const run = async ev => {
      ev.preventDefault(); err.replaceChildren();
      let json;
      try {
        if (!s.releases.length) throw new Error('Add at least one release.');
        const body = { releases: s.releases.map(r => ({ dataset: d, release: r })) };
        const ps = s.participants.map((c, i) => encode(c, 'Participant', i));
        const as = s.artifacts.map((c, i) => encode(c, 'File', i));
        if (ps.length) body.participants = ps.length === 1 ? ps[0] : { op: 'all', conditions: ps };
        if (as.length) body.artifacts = as.length === 1 ? as[0] : { op: 'all', conditions: as };
        json = JSON.stringify(body).replace(/"\\u0000num:(-?[0-9.]+)"/g, '$1');
      } catch (x) {
        err.append(h('p', { class: 'notice err', role: 'alert', id: 'q-err' }, x.message));
        const el = x.fk && document.querySelector('[data-fk="' + x.fk + '"]');
        if (el) { el.setAttribute('aria-invalid', 'true'); el.setAttribute('aria-describedby', 'q-err'); el.focus(); el.addEventListener('input', () => el.removeAttribute('aria-invalid'), { once: true }); }
        return;
      }
      try {
        const sel = await api('POST', '/v1/selections', { json });
        state.selectionQueries = { ...(state.selectionQueries || {}), [sel.id]: describeQuery(s) };
        go('#/d/' + enc(d) + '/selections/' + sel.id + (ctx.release ? '?release=' + enc(ctx.release) : ''));
      } catch (x) { err.append(h('p', { class: 'notice err', role: 'alert' }, explain(x))); }
    };
    let relInput, relErr;
    const addRelease = () => {
      const v = relInput.value.trim();
      if (UUID.test(v) && !s.releases.includes(v)) { s.releases.push(v); redraw('rel-input'); }
      else { relInput.setAttribute('aria-invalid', 'true'); relErr.textContent = s.releases.includes(v) ? 'Already added.' : 'Release identifiers are UUIDs.'; }
    };
    const form = h('form', { class: 'card pad stack s18', onsubmit: run, novalidate: true },
      h('fieldset', { class: 'stack s8' }, h('legend', { class: 'overline' }, 'Releases'),
        s.releases.map((r, i) => h('div', { class: 'row between nowrap release-pill' }, h('span', { class: 'mono small' }, r),
          h('button', { class: 'btn ghost icon-btn', type: 'button', 'aria-label': 'Remove release ' + r, onclick: () => { s.releases.splice(i, 1); redraw('rel-input'); } }, I.x()))),
        h('div', { class: 'row nowrap' }, relInput = h('input', { class: 'input mono', fk: 'rel-input', placeholder: 'Add a release id', 'aria-label': 'Add release id', 'aria-describedby': 'rel-err',
            onkeydown: ev => { if (ev.key === 'Enter') { ev.preventDefault(); addRelease(); } } }),
          h('button', { class: 'btn', type: 'button', onclick: () => addRelease() }, 'Add')),
        relErr = h('span', { class: 'hint err-text', id: 'rel-err', role: 'status' })),
      h('fieldset', { class: 'stack s8' }, h('legend', { class: 'overline' }, 'Participants where'), condRows(s.participants, PART_OPS, 'Participant'),
        h('button', { class: 'btn ghost sm self-start', type: 'button', fk: 'add:Participant', onclick: () => { s.participants.push({ op: 'fieldEquals', name: '', value: '' }); redraw('Participant' + (s.participants.length - 1) + ':op'); } }, I.plus(), 'Participant condition')),
      h('fieldset', { class: 'stack s8' }, h('legend', { class: 'overline' }, 'Files where'), condRows(s.artifacts, ART_OPS, 'File'),
        h('button', { class: 'btn ghost sm self-start', type: 'button', fk: 'add:File', onclick: () => { s.artifacts.push({ op: 'entity', name: 'task', value: '' }); redraw('File' + (s.artifacts.length - 1) + ':op'); } }, I.plus(), 'File condition')),
      h('p', { class: 'hint' }, 'All conditions must hold for the same participant and file. Numbers are sent exactly as typed and compared as decimals.'),
      err = h('div', { class: 'stack' }),
      h('button', { class: 'btn primary', type: 'submit' }, 'Freeze selection'));
    page(ctx, 'query',
      h('div', { class: 'page-head' }, h('div', { class: 'stack s10' }, h('h1', {}, 'Query'),
        h('p', { class: 'lead' }, 'A selection freezes its inputs and membership. The same selection identifier always returns the same files, even after later releases.'))),
      h('div', { class: 'query' }, form, h('section', { class: 'card empty stack' }, h('h2', {}, 'No selection yet'),
        h('p', {}, 'Describe participants and files, then freeze the selection to list the matching files.'))));
  }

  async function selectionView(d, id, q) {
    const my = ++generation;
    const ctx = { dataset: d, release: q.get('release') };
    const offset = Math.min(1e9, Math.max(0, parseInt(q.get('offset') || '0', 10) || 0)), limit = 25;
    const timer = loadingAfter(my, ctx, 'query', 'Loading selection…');
    let s;
    try { s = await api('GET', '/v1/selections/' + enc(id) + '?offset=' + offset + '&limit=' + limit); }
    catch (e) { clearTimeout(timer); if (my === generation) failure(ctx, 'query', e, () => render()); return; }
    clearTimeout(timer);
    if (my !== generation) return;
    const queryHref = '#/d/' + enc(d) + '/query' + (ctx.release ? '?release=' + enc(ctx.release) : '');
    const nav = o => '#/d/' + enc(d) + '/selections/' + enc(id) + '?' + new URLSearchParams({ ...(ctx.release ? { release: ctx.release } : {}), offset: o });
    const participants = new Set(s.hits.map(x => x.participant));
    const sessions = s.hits.some(x => x.session);   // a session column only when the dataset has sessions
    page(ctx, 'query',
      h('div', { class: 'page-head' }, h('div', { class: 'stack s10' },
        h('div', { class: 'crumbs' }, h('a', { href: queryHref }, 'Query'), I.chev(), h('span', {}, 'Selection')),
        h('h1', {}, s.total + (s.total === 1 ? ' matching file' : ' matching files')),
        h('div', { class: 'row' }, h('span', { class: 'seal ok' }, I.seal(), 'Frozen'), h('span', { class: 'meta-item' }, 'Selection ', hashChip(s.id, 'selection id')),
          s.total <= limit ? h('span', { class: 'meta-item' }, h('b', {}, participants.size), participants.size === 1 ? ' participant' : ' participants') : null),
        (state.selectionQueries || {})[s.id] ? h('p', { class: 'small muted' }, 'Frozen from: ', state.selectionQueries[s.id].map((t, i) => [i ? ' · ' : '', h('span', { class: 'mono' }, t)])) : null),
        h('div', { class: 'row head-actions' }, h('a', { class: 'btn', href: queryHref }, 'New query'))),
      h('section', { class: 'card' },
        s.hits.length ? h('div', { class: 'scroll-x' }, h('table', { class: 'data stackable' },
          h('caption', { class: 'sr-only' }, 'Files in selection ' + short(s.id)),
          h('thead', {}, h('tr', {}, h('th', {}, 'Participant'), sessions ? h('th', {}, 'Session') : null, h('th', {}, 'File'), h('th', {}, 'Release'))),
          h('tbody', {}, s.hits.map(x => h('tr', {},
            h('td', { class: 'mono', 'data-label': 'Participant' }, 'sub-' + x.participant),
            sessions ? h('td', { class: 'mono muted', 'data-label': 'Session' }, x.session ? 'ses-' + x.session : '—') : null,
            h('td', { class: 'wide', 'data-label': 'File' }, h('a', { class: 'mono', href: relHash(x.ref.dataset, x.ref.release, { path: x.path }) }, pathText(x.path))),
            h('td', { 'data-label': 'Release' }, h('span', { class: 'mono small muted' }, short(x.ref.release))))))))
          : offset > 0 && s.total > 0 ? h('div', { class: 'empty stack' }, h('p', {}, 'This page is past the end of the selection.'), h('a', { class: 'btn sm', href: nav(0) }, 'First page'))
          : h('p', { class: 'empty' }, 'No files matched. The selection is still frozen and can be cited.'),
        s.total > limit && offset < s.total ? h('div', { class: 'pager' }, h('span', { class: 'small muted' }, 'Showing ' + (offset + 1) + '–' + Math.min(offset + limit, s.total) + ' of ' + s.total),
          h('div', { class: 'row' }, offset > 0 ? h('a', { class: 'btn sm', fk: 'pager-prev', href: nav(Math.max(0, offset - limit)), onclick: () => { focusKey = 'pager-prev|pager-next'; } }, 'Previous') : null,
            offset + limit < s.total ? h('a', { class: 'btn sm', fk: 'pager-next', href: nav(offset + limit), onclick: () => { focusKey = 'pager-next|pager-prev'; } }, 'Next') : null)) : null));
  }

  // ---------- access ----------
  // Grants cannot delegate (Authority.validate rejects ManageAccess); fixed-release grants are read-only.
  const ACTIONS = [
    ['ReadMetadata', 'Read metadata', 'Inspect releases, files and effective metadata.'],
    ['ReadContent', 'Read content', 'Download file bytes.'],
    ['Propose', 'Propose', 'Prepare corrections and other candidates.'],
    ['Accept', 'Accept', 'Make a prepared candidate the dataset head.'],
  ];
  const READ_ONLY = new Set(['ReadMetadata', 'ReadContent']);
  function accessView(d, q) {
    const ctx = { dataset: d, release: q.get('release') };
    let recipient, err, revokeInput, revokeErr;
    const form = state.accessForm && state.accessForm.dataset === d ? state.accessForm
      : (state.accessForm = { dataset: d, scope: ctx.release ? 'release' : 'dataset', chosen: new Set(['ReadMetadata']), recipient: '' });
    // Without a release in context, the only possible scope is the whole dataset.
    if (!ctx.release) form.scope = 'dataset';
    const actionsBox = h('div', { class: 'actions' });
    const drawActions = () => actionsBox.replaceChildren(...ACTIONS.map(([k, label, help]) => {
      const locked = form.scope === 'release' && !READ_ONLY.has(k);
      if (locked) form.chosen.delete(k);
      return h('label', { class: 'action' + (locked ? ' locked' : '') }, h('input', { type: 'checkbox', id: 'act-' + k, checked: form.chosen.has(k), disabled: locked, onchange: ev => { ev.target.checked ? form.chosen.add(k) : form.chosen.delete(k); if (form.chosen.size) actionsBox.closest('fieldset').removeAttribute('aria-describedby'); } }),
        h('span', { class: 'stack s2' }, h('b', {}, label), h('span', { class: 'hint' }, locked ? 'Release grants permit reading only.' : help)));
    }));
    drawActions();
    const grants = loadGrants().filter(g => g.dataset === d);
    const grant = async ev => {
      ev.preventDefault(); err.replaceChildren();
      const who = recipient.value.trim();
      if (!UUID.test(who)) { err.append(h('p', { class: 'notice err', role: 'alert', id: 'g-err' }, 'The recipient must be a principal UUID.')); recipient.setAttribute('aria-invalid', 'true'); recipient.setAttribute('aria-describedby', 'g-err'); recipient.focus(); return; }
      if (!form.chosen.size) { err.append(h('p', { class: 'notice err', role: 'alert', id: 'g-act-err' }, 'Choose at least one action.')); actionsBox.closest('fieldset').setAttribute('aria-describedby', 'g-act-err'); actionsBox.querySelector('input:not(:disabled)').focus(); return; }
      if (state.granting) return;
      state.granting = true;
      const owner = state.principal, my = generation;
      try {
        const g = await api('POST', '/v1/datasets/' + enc(d) + '/grants', { json: JSON.stringify({ recipient: who, release: form.scope === 'release' ? ctx.release : null, actions: [...form.chosen] }) });
        if (owner !== state.principal) return;          // signed out meanwhile: never file it under someone else
        saveGrants([g, ...loadGrants(owner)], owner); form.recipient = ''; announce('Grant ' + short(g.id) + ' created.');
        if (my === generation) { focusKey = 'grant:' + g.id; accessView(d, q); }
        else if (onAccess(d)) { rememberFocus(); accessView(d, route().q); }   // left and came back meanwhile: show the new row, keep focus where it is
      } catch (x) { if (my === generation) err.append(h('p', { class: 'notice err', role: 'alert' }, explain(x))); }
      finally { state.granting = false; }
    };
    // Revoking takes an explicit second step on a different control; a fast repeat of the first click is ignored.
    const revoke = async (gid, where) => {
      if (state.revoking !== gid) { state.revoking = gid; focusKey = 'cancel:' + gid; accessView(d, q); announce('Confirm revoking grant ' + short(gid) + ', or cancel.'); return; }
      if (state.revokeBusy) return;
      state.revokeBusy = true; onEscape = null;
      const owner = state.principal, my = generation;
      document.querySelectorAll('[data-fk="cancel:' + gid + '"], [data-fk="revoke:' + gid + '"]').forEach(b => { b.disabled = true; });
      try {
        await api('DELETE', '/v1/datasets/' + enc(d) + '/grants/' + enc(gid));
        const at = new Date().toISOString();   // local clock: when this browser saw the revocation succeed
        if (owner === state.principal) {
          const mine = loadGrants(owner), known = mine.some(g => g.id === gid);
          saveGrants(known ? mine.map(g => g.id === gid ? { ...g, revokedAt: at } : g) : [{ id: gid, dataset: d, recipient: null, release: null, actions: [], revokedAt: at }, ...mine], owner);
          announce('Grant ' + short(gid) + ' revoked.'); focusKey = 'grants-head';
        }
      } catch (x) { state.armError = { gid, text: explain(x) }; focusKey = known(gid) ? 'grant:' + gid : 'revoke-id'; }
      state.revoking = null; state.revokeBusy = false;
      if (owner === state.principal && my === generation) accessView(d, q);
      else if (owner === state.principal && onAccess(d)) { focusKey = null; rememberFocus(); accessView(d, route().q); }
      else state.armError = null;
    };
    const known = gid => grants.some(g => g.id === gid);
    const tableErr = h('div', { class: 'stack pad-x' }, state.armError && known(state.armError.gid) ? h('p', { class: 'notice err', role: 'alert' }, 'Grant ' + short(state.armError.gid) + ' was not revoked. ' + state.armError.text) : null);
    function cancelRevoke() { if (state.revokeBusy) return; const gid = state.revoking; state.revokeInput = grants.some(g => g.id === gid) ? state.revokeInput : gid; state.revoking = null; focusKey = grants.some(g => g.id === gid) ? 'grant:' + gid : 'revoke-by-id'; accessView(d, q); announce('Revoke cancelled.'); }
    onEscape = state.revoking ? cancelRevoke : null;
    const pendingById = state.revoking && !grants.some(g => g.id === state.revoking);
    page(ctx, 'access',
      h('div', { class: 'page-head' }, h('div', { class: 'stack s10' }, h('h1', {}, 'Access'),
        h('p', { class: 'lead' }, 'Grants are explicit and separate from scientific identity. A dataset grant covers future releases. A release grant fixes one exact state, for reading only.'))),
      h('div', { class: 'access' },
        h('form', { class: 'card pad stack s18', onsubmit: grant, novalidate: true },
          h('h2', {}, 'Grant access'),
          h('div', { class: 'field' }, h('label', { for: 'g-recipient' }, 'Recipient principal'),
            recipient = h('input', { class: 'input mono', id: 'g-recipient', fk: 'g-recipient', value: form.recipient, placeholder: '00000000-0000-0000-0000-000000000000', autocomplete: 'off', spellcheck: 'false', oninput: ev => { form.recipient = ev.target.value; ev.target.removeAttribute('aria-invalid'); } })),
          h('fieldset', { class: 'field' }, h('legend', { class: 'label' }, 'Scope'),
            h('div', { class: 'segmented' },
              [['release', ctx.release ? 'Release ' + short(ctx.release) : 'One release'], ['dataset', 'Whole dataset']].map(([v, l]) =>
                h('label', {}, h('input', { type: 'radio', name: 'scope', value: v, checked: form.scope === v, disabled: v === 'release' && !ctx.release, onchange: () => { form.scope = v; drawActions(); scopeHint.textContent = scopeText(); } }), l))),
            scopeHint = h('span', { class: 'hint' }, scopeText())),
          h('fieldset', { class: 'field' }, h('legend', { class: 'label' }, 'Actions'), actionsBox,
            h('span', { class: 'hint' }, 'Managing access cannot be delegated.')),
          err = h('div', { class: 'stack' }),
          h('button', { class: 'btn primary', type: 'submit' }, 'Create grant')),
        h('div', { class: 'stack s18' },
          h('section', { class: 'card' }, h('div', { class: 'pad head-pad' }, h('h2', { tabindex: '-1', fk: 'grants-head' }, 'Granted in this session')),
            grants.length ? h('div', { class: 'scroll-x' }, h('table', { class: 'data stackable' }, h('thead', {}, h('tr', {}, h('th', {}, 'Grant'), h('th', {}, 'Recipient'), h('th', {}, 'Scope'), h('th', {}, 'Actions'), h('th', {}, h('span', { class: 'sr-only' }, 'Revoke')))),
              h('tbody', {}, grants.map(g => h('tr', {}, h('td', { 'data-label': 'Grant' }, hashChip(g.id, 'grant id')), h('td', { 'data-label': 'Recipient' }, g.recipient ? hashChip(g.recipient, 'recipient') : h('span', { class: 'muted small' }, 'by id')), h('td', { class: 'small', 'data-label': 'Scope' }, g.release ? 'Release ' + short(g.release) : g.recipient ? 'Dataset' : 'Unknown'),
                h('td', { class: 'small', 'data-label': 'Actions' }, g.revokedAt && !g.actions.length ? '—' : h('ul', { class: 'plain-list' }, [...g.actions].sort((x, y) => ACTIONS.findIndex(a => a[0] === x) - ACTIONS.findIndex(a => a[0] === y)).map(x => h('li', {}, (ACTIONS.find(a => a[0] === x) || [x, x])[1])))),
                h('td', { class: 'num actions-cell wide' }, g.revokedAt ? h('span', { class: 'chip', title: 'Recorded by this browser at ' + g.revokedAt }, 'Revoked ' + when(g.revokedAt))
                  : state.revoking === g.id
                  ? h('div', { class: 'row nowrap end' },
                      h('button', { class: 'btn sm danger confirm', type: 'button', fk: 'revoke:' + g.id, onclick: () => revoke(g.id, tableErr) }, 'Confirm revoke'),
                      h('button', { class: 'btn sm', type: 'button', fk: 'cancel:' + g.id, onclick: cancelRevoke }, 'Cancel'))
                  : h('button', { class: 'btn sm danger', type: 'button', fk: 'grant:' + g.id, 'aria-label': 'Revoke grant ' + short(g.id), onclick: () => revoke(g.id, tableErr) }, 'Revoke')))))))
              : h('p', { class: 'pad muted small' }, 'Grants you create here are listed until you sign out. The API does not list existing grants, so copy a grant id if you may need to revoke it later.'),
            tableErr),
          h('form', { class: 'card pad stack', onsubmit: ev => { ev.preventDefault(); const v = revokeInput.value.trim(); if (UUID.test(v)) revoke(v, revokeErr); else { revokeInput.setAttribute('aria-invalid', 'true'); revokeErr.replaceChildren(h('p', { class: 'notice err', role: 'alert' }, 'Grant identifiers are UUIDs.')); revokeInput.focus(); } }, novalidate: true },
            h('h2', {}, 'Revoke by identifier'),
            h('div', { class: 'row nowrap' }, revokeInput = h('input', { class: 'input mono', id: 'revoke-id', fk: 'revoke-id', value: pendingById ? state.revoking : state.revokeInput || '', oninput: ev => { state.revokeInput = ev.target.value; ev.target.removeAttribute('aria-invalid'); }, placeholder: 'Grant id', 'aria-label': 'Grant id to revoke', 'aria-describedby': 'revoke-err' }),
              h('button', { class: 'btn danger' + (pendingById ? ' confirm' : ''), type: 'submit', fk: pendingById ? 'revoke:' + state.revoking : 'revoke-by-id' }, pendingById ? 'Confirm revoke' : 'Revoke'),
              pendingById ? h('button', { class: 'btn', type: 'button', fk: 'cancel:' + state.revoking, onclick: cancelRevoke }, 'Cancel') : null),
            revokeErr = h('div', { class: 'stack', id: 'revoke-err' }, state.armError && !known(state.armError.gid) ? h('p', { class: 'notice err', role: 'alert' }, 'Grant ' + short(state.armError.gid) + ' was not revoked. ' + state.armError.text) : null)))));
    state.armError = null;
    function scopeText() { return form.scope === 'dataset' ? 'Includes every future release of this dataset.' : 'Only this exact release.'; }
  }
  let scopeHint, onEscape = null;
  // Same Access route as when a request started, even if the user navigated away and back since.
  const onAccess = d => { const { parts } = route(); return parts[0] === 'd' && parts[1] === d && parts[2] === 'access'; };
  document.addEventListener('keydown', ev => { if (ev.key === 'Escape' && onEscape) { ev.preventDefault(); const f = onEscape; onEscape = null; f(); } });

  // ---------- router ----------
  async function datasetHead(d) {
    const my = ++generation;
    const timer = loadingAfter(my, { dataset: d }, 'release', 'Opening dataset…');
    try {
      const ds = await api('GET', '/v1/datasets/' + enc(d));
      clearTimeout(timer);
      if (my !== generation) return;
      if (ds.head) return go(relHash(d, ds.head), true);
      page({ dataset: d }, 'release', h('div', { class: 'card empty stack' }, h('h1', { class: 'h2' }, 'No accepted release yet'),
        h('p', {}, 'This dataset exists but nothing has been accepted. Imports and their acceptance run through the operator CLI.')));
    } catch (e) { clearTimeout(timer); if (my === generation) failure({ dataset: d }, 'release', e, () => render()); }
  }

  async function render() {
    generation++;            // any view still loading for a previous route stops painting
    memo.delete('head:' + (route().parts[1] || ''));  // the dataset head can move between views
    rememberFocus();
    if (!state.principal && (state.token || demo())) {
      const my = generation;
      try { state.principal = (await api('GET', '/v1/me')).principal; } catch { state.token = null; store.set('sessionStorage', 'sanctity.token', null); }
      if (my !== generation) return;   // another navigation started while signing in
    }
    const { parts, q } = route();
    if (!state.principal) return connectView(parts.length ? 'Sign in to continue.' : null);
    if (parts[0] !== 'd' || !parts[1]) return connectView();
    const d = parts[1];
    if (!UUID.test(d)) return connectView('That dataset identifier is not a UUID.');
    if (parts[2] !== 'access') { state.revoking = null; onEscape = null; }
    if (parts[2] === 'r' && parts[3] && parts[4] === 'correct') return correctionView(d, parts[3], q);
    if (parts[2] === 'r' && parts[3]) return releaseView(d, parts[3], q);
    if (parts[2] === 'query') return queryView(d, q);
    if (parts[2] === 'selections' && parts[3]) return selectionView(d, parts[3], q);
    if (parts[2] === 'access') return accessView(d, q);
    return datasetHead(d);
  }

  window.addEventListener('hashchange', async () => {
    const keep = !!focusKey || state.scrollInspector;
    await render();
    focusKey = null;
    if (!keep) window.scrollTo(0, 0);
    if (!document.activeElement || document.activeElement === document.body) document.getElementById('main')?.focus({ preventScroll: true });
  });
  document.addEventListener('click', ev => {
    const skip = ev.target.closest && ev.target.closest('.skip');
    if (skip) { ev.preventDefault(); document.getElementById('main')?.focus(); }
  });

  const start = () => render();
  if (/(^|[?&])demo\b/.test(location.search) && !window.SanctityDemo) {
    const s = document.createElement('script'); s.src = 'demo.js'; s.onload = start; document.head.append(s);
  } else start();
})();
