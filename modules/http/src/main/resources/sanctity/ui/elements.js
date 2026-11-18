export function h(tag, props, ...kids) {
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
export const pathText = p => {
  const s = h('span', { class: 'path-text' });
  p.split(/(?<=[/_])/).forEach((part, i) => { if (i) s.append(h('wbr')); s.append(part); });
  return s;
};
export const svg = (paths, cls = 'icon', vb = '0 0 16 16') => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', vb); s.setAttribute('class', cls); s.setAttribute('aria-hidden', 'true');
  s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '1.6');
  s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
  for (const d of [].concat(paths)) { const p = document.createElementNS(s.namespaceURI, 'path'); p.setAttribute('d', d); s.append(p); }
  return s;
};
export const I = {
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
