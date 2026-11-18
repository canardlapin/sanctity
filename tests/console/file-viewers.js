// Exercised through a real upload and a release-scoped Chromium inspector.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const { gzipSync } = require('node:zlib');
const anatomy = 'sub-01/ses-01/anat/sub-01_ses-01_T1w.nii.gz';
const questionnaire = 'phenotype/handedness.tsv';

// LPS storage order. Raw low x and low y are anatomical right and anterior.
// The bright patch switches hemispheres between slices 3 and 4.
function asymmetricVolume() {
  const bytes = Buffer.alloc(352 + 32 * 32 * 5);
  bytes.writeInt32LE(348);
  [3,32,32,5,1,1,1,1].forEach((n,i) => bytes.writeInt16LE(n,40+2*i));
  bytes.writeInt16LE(2,70); bytes.writeInt16LE(8,72);
  [1,1,1,3].forEach((n,i) => bytes.writeFloatLE(n,76+4*i));
  bytes.writeFloatLE(352,108); bytes[123] = 2;
  bytes.writeInt16LE(1,254);
  [-1,0,0,31, 0,-1,0,31, 0,0,3,0].forEach((n,i) => bytes.writeFloatLE(n,280+4*i));
  bytes.write('n+1',344);
  for (let z=0; z<5; z++) for (let y=0; y<32; y++) for (let x=0; x<32; x++)
    bytes[352 + x + 32*(y+32*z)] = y<16 && (z<3 ? x<16 : x>=16) ? 224 : 16;
  return bytes;
}

async function writeViewerFixtures(folder, page) {
  const stimuli = path.join(folder, 'stimuli');
  fs.mkdirSync(stimuli);
  const volume = asymmetricVolume();
  fs.mkdirSync(path.dirname(path.join(folder, anatomy)), { recursive: true });
  fs.writeFileSync(path.join(folder, anatomy), gzipSync(volume));
  const uncoded = Buffer.from(volume);
  uncoded.writeInt16LE(0,254);
  fs.writeFileSync(path.join(stimuli, 'uncoded.nii'), uncoded);
  fs.writeFileSync(path.join(stimuli, 'short.nii'), volume.subarray(0,360));
  const fourD = Buffer.concat([volume, Buffer.alloc(volume.length - 352)]);
  // The second frame is black. The first is explicitly displayed using a qform.
  fourD.writeInt16LE(4, 40); fourD.writeInt16LE(2, 48);
  fourD.writeInt16LE(1, 252); fourD.writeInt16LE(0, 254);
  fourD.writeFloatLE(1, 264); // 180-degree rotation around z
  fs.writeFileSync(path.join(stimuli, 'four.nii'), fourD);
  fs.writeFileSync(path.join(stimuli, 'expanded.nii.gz'), gzipSync(Buffer.alloc(64 * 1024 * 1024 + 1)));
  fs.mkdirSync(path.join(folder, 'phenotype'));
  fs.writeFileSync(path.join(folder, questionnaire), 'participant_id\tscore\tchoice\nsub-01\t05\tR\n');
  fs.writeFileSync(path.join(folder, 'phenotype/handedness.json'), JSON.stringify({
    score: { LongName: 'Handedness score', Description: 'Questionnaire total', Units: 'points' },
    choice: { Description: 'Preferred hand', Levels: { R: 'Right hand', L: 'Left hand' } },
    unused: { Description: 'Absent column' },
  }));
  const hand = fs.readFileSync(path.join(__dirname, '../../modules/app/src/test/resources/multimodal-conformance/eeg-matchingpennies/dataset/stimuli/left_hand.png'));
  fs.writeFileSync(path.join(stimuli, 'left_hand.png'), hand);
  const tooWide = Buffer.from(hand);
  tooWide.writeUInt32BE(100000, 16);
  fs.writeFileSync(path.join(stimuli, 'wide.png'), tooWide);
  const jpeg = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 2; canvas.height = 1;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = 'red'; ctx.fillRect(0, 0, 2, 1);
    return canvas.toDataURL('image/jpeg').split(',')[1];
  });
  fs.writeFileSync(path.join(stimuli, 'red.jpg'), Buffer.from(jpeg, 'base64'));
  fs.writeFileSync(path.join(stimuli, 'notes.csv'), '\uFEFFid,note,value\r\n001,"left, ""cue""\nright",89.47720185999999\r\n');
  fs.writeFileSync(path.join(stimuli, 'records.tsv'), Array.from({ length: 53 }, (_, i) =>
    Array.from({ length: 35 }, (_, j) => (i ? 'r' + i : 'Column') + '-' + j).join('\t')).join('\n'));
  fs.writeFileSync(path.join(stimuli, 'raw.json'), '{"value":89.47720185999999,"code":"<script>example</script>"}\n');
  fs.writeFileSync(path.join(stimuli, 'uneven.csv'), 'one,two\nthree\n');
  fs.writeFileSync(path.join(stimuli, 'binary.txt'), Buffer.from([0xff]));
  fs.writeFileSync(path.join(stimuli, 'large.txt'), Buffer.alloc(256 * 1024 + 1, 0x61));
  fs.writeFileSync(path.join(stimuli, 'shape.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
}

async function exerciseViewers(page, releasePage) {
  const requests = [];
  const record = request => {
    const url = new URL(request.url());
    if (url.pathname.endsWith('/file')) requests.push(url.searchParams.get('path'));
  };
  page.on('request', record);
  const preview = page.getByRole('region', { name: 'File preview', exact: true });
  async function inspect(name) {
    const path = 'stimuli/' + name;
    await page.goto(releasePage + '?path=' + encodeURIComponent(path));
    await page.getByRole('heading', { name: path, exact: true }).waitFor();
    await preview.waitFor();
    return path;
  }
  try {
    await page.goto(releasePage + '?path=' + encodeURIComponent(questionnaire));
    await preview.getByText('Column dictionary', { exact: true }).click();
    await preview.getByText('Questionnaire total', { exact: true }).waitFor();
    await preview.getByText('Units: points', { exact: true }).waitFor();
    await preview.getByText('R — Right hand', { exact: true }).waitFor();
    assert.equal(await preview.getByText('phenotype/handedness.json', { exact: true }).count(), 2);
    assert.equal(await preview.getByText('Absent column').count(), 0);
    assert.deepEqual(await preview.locator('td').allTextContents(), ['sub-01', '05', 'R']);
    await page.goto(releasePage + '?path=sub-01/ses-01/beh/sub-01_ses-01_task-choice_beh.tsv');
    await preview.getByText('Column dictionary', { exact: true }).click();
    await preview.getByText('Chosen direction', { exact: true }).waitFor();
    await preview.getByText('task-choice_beh.json', { exact: true }).waitFor();

    await page.goto(releasePage + '?path=' + encodeURIComponent(anatomy));
    await page.evaluate(() => {
      window.viewerViolations = [];
      document.addEventListener('securitypolicyviolation', e => window.viewerViolations.push(e.effectiveDirective));
    });
    const launch = preview.getByRole('button', { name: 'Preview MRI slices' });
    await launch.waitFor();
    assert(!requests.includes(anatomy));
    await launch.click();
    const slice = preview.getByRole('slider', { name: 'Slice number' });
    await slice.waitFor();
    assert.equal(await slice.inputValue(), '3');
    // Sample the actual Chromium screenshot, independently of NiiVue's own transform API.
    async function quadrants() {
      const screenshot = await preview.locator('canvas').screenshot();
      return page.evaluate(async data => {
        const img = new Image(); img.src = 'data:image/png;base64,' + data; await img.decode();
        const raster = document.createElement('canvas');
        raster.width = img.width; raster.height = img.height;
        const ctx = raster.getContext('2d'); ctx.drawImage(img, 0, 0);
        return [[.25,.25], [.75,.25], [.25,.75], [.75,.75]].map(([x,y]) =>
          ctx.getImageData(Math.floor(x*img.width), Math.floor(y*img.height), 1, 1).data[0] > 127);
      }, screenshot.toString('base64'));
    }
    assert.deepEqual(await quadrants(), [false, true, false, false]);
    await slice.press('ArrowRight');
    await preview.getByText('Axial slice 4 of 5', { exact: true }).waitFor();
    assert.deepEqual(await quadrants(), [true, false, false, false]);
    await preview.getByLabel('Slice plane').selectOption('2');
    await preview.getByRole('img', { name: /Sagittal slice .* of 32/ }).waitFor();
    assert.equal(requests.filter(p => p === anatomy).length, 1);
    assert.deepEqual(await page.evaluate(() => window.viewerViolations), []);
    await inspect('four.nii');
    await preview.getByRole('button', { name: 'Preview MRI slices' }).click();
    await preview.getByText(/qform · Volume 1 of 2/).waitFor();
    assert.deepEqual(await quadrants(), [false, true, false, false]);
    for (const [name, cause] of [['uncoded.nii', /coded qform or sform/], ['short.nii', /payload is incomplete/],
      ['expanded.nii.gz', /expanded NIfTI exceeds/]]) {
      await inspect(name);
      await preview.getByRole('button', { name: 'Preview MRI slices' }).click();
      await preview.getByText(cause).waitFor();
      assert.equal(await preview.locator('canvas').count(), 0);
    }
    await inspect('notes.csv');
    await preview.locator('tbody td').first().waitFor();
    assert.deepEqual(await preview.locator('tbody td').allTextContents(),
      ['001', 'left, "cue"\nright', '89.47720185999999']);
    assert.deepEqual(await preview.locator('thead th').allTextContents(), ['id', 'note', 'value']);
    await inspect('records.tsv');
    await preview.getByText('First 50 of 52 data rows.').waitFor();
    await preview.getByText('First 32 of 35 columns.').waitFor();
    assert.equal(await preview.locator('tbody tr').count(), 50);
    assert.equal(await preview.locator('thead th[scope=col]').count(), 32);
    await inspect('raw.json');
    await preview.locator('pre').waitFor();
    assert.equal(await preview.locator('pre').textContent(), '{"value":89.47720185999999,"code":"<script>example</script>"}\n');
    assert.equal(await preview.locator('script').count(), 0);
    await inspect('uneven.csv');
    await preview.getByText('Rows have different column counts. Showing the original text.').waitFor();
    await inspect('binary.txt');
    await preview.getByText('This file does not contain valid UTF-8 text.').waitFor();
    for (const [name, label] of [['left_hand.png', 'PNG · 1398 × 1856 pixels'], ['red.jpg', 'JPEG · 2 × 1 pixels']]) {
      const path = await inspect(name);
      const button = preview.getByRole('button', { name: 'Preview image' });
      await button.waitFor();
      assert(!requests.includes(path));
      await button.press('Enter');
      await preview.getByText(label).waitFor();
      assert.equal(await preview.getByRole('img', { name: path }).count(), 1);
      assert.equal(requests.filter(p => p === path).length, 1);
      if (name === 'red.jpg') {
        const red = await preview.locator('canvas').evaluate(canvas => canvas.getContext('2d').getImageData(0, 0, 1, 1).data[0]);
        assert(red > 245);
      }
    }
    await inspect('wide.png');
    await preview.getByRole('button', { name: 'Preview image' }).click();
    await preview.getByText(/16 megapixels/).waitFor();
    assert.equal(await preview.locator('canvas').count(), 0);
    for (const [name, message] of [['large.txt', /exceeds the 256 KiB/], ['shape.svg', /No viewer/]]) {
      const path = await inspect(name);
      await preview.getByText(message).waitFor();
      assert(!requests.includes(path));
      assert.equal(await page.getByRole('button', { name: 'Download', exact: true }).count(), 1);
    }
  } finally { page.off('request', record); }
}
// Synthetic transport failures supplement the real-server read and grant tests.
// Exercise the same inspector component with controlled response bodies.
async function checkViewerTransport(browser, origin) {
  const page = await browser.newPage();
  try {
    await page.goto(origin + '/elements.js');
    await page.evaluate(async () => {
      const { filePreview } = await import('./viewers.js');
      const payload = new Uint8Array([65]);
      const sha = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', payload)),
        b => b.toString(16).padStart(2, '0')).join('');
      function response(body, digest = sha) {
        return new Response(body, { headers: { 'X-Sanctity-SHA256': digest, 'X-Sanctity-Size': '1' } });
      }
      function mount(id, read, controller = new AbortController()) {
        const view = filePreview({ path: 'example.txt', size: 1 }, { read, signal: controller.signal, formatSize: String });
        view.id = id;
        document.body.append(view);
      }
      mount('truncated', () => response(new Uint8Array()));
      mount('overlong', () => response(new Uint8Array([65, 66])));
      mount('corrupt', () => response(payload, '0'.repeat(64)));
      const controller = new AbortController();
      let deliver;
      mount('discarded', () => new Promise(resolve => { deliver = resolve; }), controller);
      controller.abort();
      deliver(response(new ReadableStream({ cancel() { document.body.dataset.cancelled = 'true'; } })));
    });
    for (const id of ['truncated', 'overlong', 'corrupt']) {
      const section = page.locator('#' + id);
      await section.getByText(/do not match/).waitFor({ timeout: 5000 });
      assert.equal(await section.locator('pre').count(), 0);
    }
    await page.waitForSelector('body[data-cancelled=true]', { timeout: 5000 });
    assert.equal(await page.locator('#discarded pre, #discarded [role=status]').count(), 0);
    return { truncated: true, overlong: true, corrupt: true, discarded: true };
  } finally { await page.close(); }
}
module.exports = { anatomy, questionnaire, writeViewerFixtures, exerciseViewers, checkViewerTransport };
