// NiiVue receives one bounded, uncompressed scalar volume from the selected release.
import { h } from './elements.js';

export const niftiLimit = 64 * 1024 * 1024;
const bits = new Map([[2, 8], [4, 16], [8, 32], [16, 32], [64, 64], [256, 8], [512, 16], [768, 32]]);
const fail = message => { throw new Error(message); };

async function inflate(bytes, signal) {
  if (bytes[0] !== 31 || bytes[1] !== 139) fail('The file does not have a gzip header.');
  const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();
  const cancel = () => { reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      signal.throwIfAborted();
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > niftiLimit) {
        await reader.cancel();
        fail('The expanded NIfTI exceeds the 64 MiB preview limit.');
      }
      chunks.push(value);
    }
    signal.throwIfAborted();
    return new Uint8Array(await new Blob(chunks).arrayBuffer());
  } finally {
    signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}

function firstVolume(bytes) {
  if (bytes.length < 352 || bytes[344] !== 110 || bytes[345] !== 43 || bytes[346] !== 49 || bytes[347] !== 0)
    fail('Preview supports single-file NIfTI-1 images (.nii and .nii.gz).');
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const le = v.getInt32(0, true) === 348;
  if (v.getInt32(0, le) !== 348) fail('Invalid NIfTI-1 header length.');
  const i16 = offset => v.getInt16(offset, le);
  const f32 = offset => v.getFloat32(offset, le);
  const rank = i16(40), dims = [i16(42), i16(44), i16(46)];
  const spatialVoxels = dims.reduce((a, b) => a * b);
  const frames = rank === 4 ? i16(48) : 1;
  if ((rank !== 3 && rank !== 4) || frames < 1 || dims.some(n => n < 1 || n > 512) || spatialVoxels > 256 ** 3)
    fail('Preview requires a 3-D or 4-D volume with at most 512 voxels per axis and 256³ voxels per frame.');
  if (!bits.has(i16(70)) || bits.get(i16(70)) !== i16(72)) fail('Unsupported NIfTI scalar datatype or bit depth.');
  const frameBytes = spatialVoxels * i16(72) / 8;
  const offset = f32(108);
  if (!Number.isInteger(offset) || offset < 352 || offset + frameBytes * frames > bytes.length)
    fail('The NIfTI voxel payload is incomplete or its offset is invalid.');
  // NiiVue can invent a default affine for uncoded images. Only label anatomical
  // planes when an actual coded transform can be used without that fallback.
  const q = i16(252), s = i16(254), qfac = f32(76);
  if (q < 0 || s < 0 || q > 5 || s > 5 || !(q || s)) fail('Anatomical orientation needs a supported coded qform or sform.');
  if ([80, 84, 88].some(k => !Number.isFinite(f32(k)) || f32(k) <= 0)) fail('Invalid NIfTI spatial spacing.');
  if (q && (![0, 1, -1].includes(qfac) || [256, 260, 264, 268, 272, 276].some(k => !Number.isFinite(f32(k))) ||
      f32(256) ** 2 + f32(260) ** 2 + f32(264) ** 2 > 1))
    fail('The coded NIfTI quaternion cannot establish anatomical orientation.');
  if (s) {
    const m = Array.from({ length: 12 }, (_, i) => f32(280 + i * 4));
    const [a, b, c, , d, e, f, , g, j, k] = m;
    const det = a * (e*k - f*j) - b * (d*k - f*g) + c * (d*j - e*g);
    const scale = Math.hypot(a, d, g) * Math.hypot(b, e, j) * Math.hypot(c, f, k);
    if (m.some(n => !Number.isFinite(n)) || !scale || Math.abs(det) < 1e-8 * scale)
      fail('The coded NIfTI sform cannot establish anatomical orientation.');
    if (q && Math.sign(det) !== (qfac < 0 ? -1 : 1)) fail('The coded qform and sform disagree on handedness.');
  }
  // Strip optional extensions and trailing frames from the renderer input only.
  // Every offset and the complete declared source payload were checked above.
  const first = new Uint8Array(352 + frameBytes);
  first.set(bytes.subarray(0, 348));
  first.set(bytes.subarray(offset, offset + frameBytes), 352);
  const header = new DataView(first.buffer);
  header.setInt16(40, 3, le);
  for (const k of [48, 50, 52, 54]) header.setInt16(k, 1, le);
  header.setFloat32(108, 352, le);
  return { buffer: first.buffer, dims, frames, form: s >= q ? 'sform' : 'qform' };
}

export async function renderNifti({ entry, bytes, signal }) {
  const raw = /\.gz$/i.test(entry.path) ? await inflate(bytes, signal) : bytes;
  signal.throwIfAborted();
  const { buffer, dims, frames, form } = firstVolume(raw);
  const { Niivue } = await import('./niivue-0.69.0.js');
  signal.throwIfAborted();
  const nv = new Niivue({ interactive: false, dragAndDropEnabled: false, isResizeCanvas: false,
    isRadiologicalConvention: false, isNearestInterpolation: true, crosshairWidth: 0, sliceType: 0 });
  const canvas = h('canvas', { width: 512, height: 512, role: 'img' });
  const output = h('output', { class: 'small mono' });
  const planes = ['Axial', 'Coronal', 'Sagittal'];
  const plane = h('select', { 'aria-label': 'Slice plane', onchange: selectPlane },
    planes.map((name, i) => h('option', { value: String(i) }, name)));
  const slice = h('input', { type: 'range', min: 1, step: 1, 'aria-label': 'Slice number', oninput: draw });
  const figure = h('figure', { class: 'file-image nifti' }, h('div', { class: 'nifti-screen' }, canvas),
    h('div', { class: 'row' }, plane, slice, output),
    h('figcaption', { class: 'small muted' }, 'Native voxel planes · Neurological orientation · ' + form +
      (frames > 1 ? ' · Volume 1 of ' + frames : '')));
  function draw() {
    const axis = 2 - Number(plane.value);
    nv.scene.crosshairPos[axis] = (Number(slice.value) - 0.5) / Number(slice.max);
    nv.drawScene();
    const label = planes[Number(plane.value)] + ' slice ' + slice.value + ' of ' + slice.max;
    output.textContent = label;
    slice.setAttribute('aria-valuetext', label);
    canvas.setAttribute('aria-label', label + ' of ' + entry.path);
  }
  function selectPlane() {
    const i = Number(plane.value);
    nv.setSliceType(i);
    slice.max = nv.volumes[0].dimsRAS[3 - i];
    slice.value = Math.min(Number(slice.max), 1 + Math.floor(nv.scene.crosshairPos[2 - i] * slice.max));
    draw();
  }
  const gl = canvas.getContext('webgl2', { antialias: false });
  if (!gl) fail('MRI preview requires WebGL 2 in this browser.');
  function dispose() {
    nv.cleanup();
    gl.getExtension('WEBGL_lose_context')?.loseContext();
  }
  try {
    if (Math.max(...dims) > gl.getParameter(gl.MAX_3D_TEXTURE_SIZE)) fail('This volume exceeds the 3-D texture limit of this browser.');
    await nv.attachToCanvas(canvas, false);
    signal.throwIfAborted();
    await nv.loadFromArrayBuffer(buffer, 'preview.nii');
    signal.throwIfAborted();
    selectPlane();
    signal.addEventListener('abort', dispose, { once: true });
    return figure;
  } catch (error) { dispose(); throw error; }
}
