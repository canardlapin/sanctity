// Built-in release file viewers. Each declares its formats, byte budget,
// loading policy and render({ entry, kind, bytes, signal }) -> Node (or Promise<Node>).
// The host supplies the authorized bytes. Viewers never need a URL or credential.
import { h } from './elements.js';
import { niftiLimit, renderNifti } from './nifti-viewer.js';

const textPreviewLimit = 256 * 1024;
const imageLimit = 8 * 1024 * 1024;
const maxRows = 50;
const maxColumns = 32;
export function fileKind(path) {
  const name = path.split('/').pop().toLowerCase();
  if (name.endsWith('.nii.gz')) return 'nii';
  if (['readme', 'changes', 'license'].includes(name)) return 'txt';
  return /\.([a-z0-9]{1,5})$/.exec(name)?.[1] || '';
}

const hint = message => h('p', { class: 'small muted' }, message);

function utf8(bytes) {
  let source;
  try { source = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
  catch { throw new Error('This file does not contain valid UTF-8 text.'); }
  if (source.includes('\0')) throw new Error('This file contains binary data.');
  return source;
}

function sourceView(entry, source) {
  return h('pre', { class: 'code', tabindex: '0', 'aria-label': 'Content of ' + entry.path }, source);
}

// Read one bounded file, retaining only the visible rectangle. CSV and TSV
// both permit doubled quotes, quoted delimiters and line breaks inside fields.
async function tableView({ entry, bytes, kind, dictionary }) {
  const source = utf8(bytes);
  if (!source.length) return hint('This table is empty.');
  const delimiter = kind === 'csv' ? ',' : '\t';
  const rows = [];
  let count = 0, headerWidth, uneven = false, i = 0;
  while (i < source.length) {
    const row = [];
    let width = 0;
    while (true) {
      let value;
      if (source[i] === '"') {
        const start = ++i;
        let end = source.indexOf('"', i);
        while (end >= 0 && source[end + 1] === '"') end = source.indexOf('"', end + 2);
        if (end < 0) return tableSource('Unclosed quoted field');
        value = source.slice(start, end).replaceAll('""', '"');
        i = end + 1;
      } else {
        const start = i;
        while (i < source.length && source[i] !== delimiter && source[i] !== '\n' && source[i] !== '\r') {
          if (source[i] === '"') return tableSource('Unexpected quote');
          i++;
        }
        value = source.slice(start, i);
      }
      if (count <= maxRows && width < maxColumns) row.push(value);
      width++;
      if (source[i] !== delimiter) break;
      i++;
    }
    if (i < source.length) {
      if (source[i] === '\r' && source[i + 1] === '\n') i += 2;
      else if (source[i] === '\n' || source[i] === '\r') i++;
      else return tableSource('Unexpected text after a quoted field');
    }
    if (count === 0) headerWidth = width;
    if (width !== headerWidth) uneven = true;
    if (count <= maxRows) rows.push(row);
    count++;
  }
  if (uneven) return tableSource('Rows have different column counts');
  const [header, ...data] = rows;
  const grid = h('div', { class: 'scroll-x', tabindex: '0', role: 'region', 'aria-label': 'Table content' },
    h('table', { class: 'data compact preview-table', 'aria-label': entry.path },
      h('thead', {}, h('tr', {}, header.map((c, index) => h('th', { scope: 'col' }, c || 'Column ' + (index + 1))))),
      h('tbody', {}, data.map(row => h('tr', {}, row.map(c => h('td', { class: 'mono' }, c)))))),
    count - 1 > maxRows ? hint('First ' + maxRows + ' of ' + (count - 1) + ' data rows.') : null,
    headerWidth > maxColumns ? hint('First ' + maxColumns + ' of ' + headerWidth + ' columns.') : null);

  return h('div', {}, grid, await dictionary(header));

  function tableSource(reason) {
    return h('div', {}, hint(reason + '. Showing the original text.'), sourceView(entry, source));
  }
}

// The interpretation is authoritative for inherited BIDS fields. A preserved
// questionnaire or archive table can instead carry a same-stem JSON dictionary.
async function tableDictionary(columns, metadata, companion, read, signal) {
  let fields = metadata?.fields || {};
  if (!metadata?.sources?.length && companion) {
    if (companion.size > textPreviewLimit) return hint('The companion dictionary exceeds the 256 KiB preview limit.');
    try {
      const source = utf8(await readBytes(s => read(s, companion.path), companion, signal));
      const data = JSON.parse(source);
      if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error();
      fields = Object.fromEntries(Object.entries(data).map(([name, value]) => [name, { value, origin: companion.path }]));
    } catch {
      signal.throwIfAborted();
      return hint('The companion dictionary could not be read. The table is shown above.');
    }
  }
  const definitions = [];
  for (const name of new Set(columns)) {
    if (!Object.hasOwn(fields, name)) continue;
    const { value, origin } = fields[name];
    if (!value || typeof value !== 'object') continue;
    const text = key => typeof value[key] === 'string' ? value[key] : '';
    const levels = value.Levels && typeof value.Levels === 'object' && !Array.isArray(value.Levels)
      ? Object.entries(value.Levels).filter(([, label]) => typeof label === 'string') : [];
    if (!text('LongName') && !text('Description') && !text('Units') && !levels.length) continue;
    definitions.push(h('dt', {}, name), h('dd', {},
      text('LongName') ? h('strong', {}, text('LongName')) : null,
      text('Description') ? h('div', {}, text('Description')) : null,
      text('Units') ? h('div', {}, 'Units: ', text('Units')) : null,
      levels.length ? h('ul', {}, levels.slice(0, 50).map(([code, label]) => h('li', {}, h('code', {}, code), ' — ', label))) : null,
      levels.length > 50 ? hint('First 50 of ' + levels.length + ' levels.') : null,
      h('div', { class: 'small muted' }, 'Source: ', h('code', {}, origin))));
  }
  return definitions.length ? h('details', { class: 'file-dictionary' },
    h('summary', {}, 'Column dictionary'), h('dl', {}, definitions)) : null;
}

function imageDimensions(bytes, png) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (png) {
    if (bytes.length >= 33 && view.getUint32(0) === 0x89504e47 && view.getUint32(4) === 0x0d0a1a0a &&
        view.getUint32(8) === 13 && view.getUint32(12) === 0x49484452) {
      return [view.getUint32(16), view.getUint32(20)];
    }
  } else if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    // A JPEG frame header precedes the first scan. Skip length-delimited
    // segments such as EXIF without interpreting their contents.
    let i = 2;
    while (i < bytes.length && bytes[i] === 0xff) {
      while (bytes[i] === 0xff) i++;
      const marker = bytes[i++];
      if (marker === 0xda || marker === 0xd9 || i + 2 > bytes.length) break;
      const length = view.getUint16(i);
      if (length < 2 || i + length > bytes.length) break;
      if ([0xc0, 0xc1, 0xc2].includes(marker) && length >= 8) {
        return [view.getUint16(i + 5), view.getUint16(i + 3)];
      }
      i += length;
    }
  }
  throw new Error('The file has no supported ' + (png ? 'PNG' : 'JPEG') + ' image header.');
}

async function imageView({ entry, bytes, signal, kind }) {
  const png = kind === 'png';
  const [w, hgt] = imageDimensions(bytes, png);
  if (!w || !hgt || Math.max(w, hgt) > 8192 || w * hgt > 16000000)
    throw new Error('Image previews support up to 8,192 pixels per side and 16 megapixels.');
  let bitmap;
  try { bitmap = await createImageBitmap(new Blob([bytes], { type: png ? 'image/png' : 'image/jpeg' })); }
  catch { throw new Error('The browser could not decode this image.'); }
  try {
    signal.throwIfAborted();
    const { width, height } = bitmap;
    // EXIF orientation may interchange the two dimensions.
    if (!((width === w && height === hgt) || (width === hgt && height === w)))
      throw new Error('The decoded dimensions differ from the image header.');
    const canvas = h('canvas', { width, height, role: 'img', 'aria-label': entry.path });
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    return h('figure', { class: 'file-image' }, canvas,
      h('figcaption', { class: 'small muted' }, (png ? 'PNG' : 'JPEG') + ' · ' + width + ' × ' + height + ' pixels'));
  } finally { bitmap.close(); }
}

const viewers = [
  { formats: ['csv', 'tsv'], limit: textPreviewLimit, render: tableView },
  { formats: ['nii'], limit: niftiLimit, button: 'Preview MRI slices', render: renderNifti },
  { formats: ['png', 'jpg', 'jpeg'], limit: imageLimit, button: 'Preview image', render: imageView },
  { formats: ['json', 'txt', 'md', 'bval', 'bvec'], limit: textPreviewLimit,
    render: ({ entry, bytes }) => sourceView(entry, utf8(bytes)) },
];

async function readBytes(read, entry, signal) {
  const response = await read(signal);
  if (signal.aborted) {
    await response.body?.cancel();
    signal.throwIfAborted();
  }
  const expected = response.headers.get('X-Sanctity-SHA256');
  const mismatch = () => new Error('The received bytes do not match this release file.');
  if (!response.body || response.headers.get('X-Sanctity-Size') !== String(entry.size) || !/^[0-9a-f]{64}$/.test(expected || '')) {
    await response.body?.cancel();
    throw mismatch();
  }
  // Stop as soon as a stream exceeds its release entry, even without Content-Length.
  const reader = response.body.getReader();
  const bytes = new Uint8Array(entry.size);
  let offset = 0;
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      if (chunk.value.length > bytes.length - offset) { await reader.cancel(); throw mismatch(); }
      bytes.set(chunk.value, offset);
      offset += chunk.value.length;
    }
  } finally { reader.releaseLock(); }
  if (offset !== entry.size) throw mismatch();
  if (crypto.subtle) {
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    if (Array.from(hash, b => b.toString(16).padStart(2, '0')).join('') !== expected) throw mismatch();
  }
  return bytes;
}

export function filePreview(entry, { read, signal, formatSize, metadata, companion }) {
  const kind = fileKind(entry.path);
  const viewer = viewers.find(v => v.formats.includes(kind));
  const content = h('div', {});
  const section = h('section', { class: 'section', 'aria-label': 'File preview' }, h('h3', { class: 'overline' }, 'Preview'), content);
  if (!viewer) content.append(hint('No viewer for this file type. Use Download to open the original.'));
  else if (!Number.isSafeInteger(entry.size) || entry.size < 0 || entry.size > viewer.limit)
    content.append(hint('This file exceeds the ' + formatSize(viewer.limit) + ' preview limit. Use Download to open the original.'));
  else {
    async function load() {
      if (signal.aborted) return;
      content.replaceChildren(h('span', { class: 'small muted', role: 'status' }, 'Loading preview…'));
      try {
        const bytes = await readBytes(read, entry, signal);
        signal.throwIfAborted();
        const dictionary = columns => tableDictionary(columns, metadata, companion, read, signal);
        const node = await viewer.render({ entry, kind, bytes, signal, dictionary });
        if (!signal.aborted) content.replaceChildren(node);
      } catch (error) {
        if (!signal.aborted) content.replaceChildren(h('p', { class: 'small muted', role: 'status' }, error.message));
      }
    }
    if (viewer.button) content.append(h('button', { type: 'button', class: 'btn sm', onclick: load }, viewer.button));
    else load();
    signal.addEventListener('abort', () => content.replaceChildren(), { once: true });
  }
  return section;
}
