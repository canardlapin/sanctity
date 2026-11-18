// Browser-only mutations against a newly seeded packaged demo. Reads are checked independently.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require('playwright');

const fixtures = path.resolve(__dirname, '../../modules/app/src/test/resources');
const mriFixture = 'mri-conformance/analysis-inputs/dataset';
const eegFixture = 'multimodal-conformance/eeg-matchingpennies/dataset';
const archiveFixture = 'archive-conformance/face-protocol/dataset';
const image = 'sub-01/func/sub-01_task-cue_bold.nii';
const companion = image.replace(/\.nii$/, '.json');
const recording = 'sub-05/eeg/sub-05_task-matchingpennies_eeg.vhdr';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

async function run(home, evidence) {
  const instance = JSON.parse(fs.readFileSync(path.join(home, 'instance.json')));
  const alice = JSON.parse(fs.readFileSync(path.join(home, 'demo-realm.json'))).users.find(u => u.username === 'alice');
  const project = instance.labs[0].project;
  const base = instance.url;
  const mri = fs.mkdtempSync(path.join(os.tmpdir(), 'sanctity-curation-'));
  const browser = await chromium.launch();
  const checks = [];
  const output = { browser: browser.version(), deployment: instance.image, checks };
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const verifier = await browser.newContext({ extraHTTPHeaders: {
      Authorization: 'Bearer ' + instance.people.find(person => person.key === 'alice').token,
    } });
    const read = async route => {
      const response = await verifier.request.get(new URL(route, base).href);
      assert.equal(response.status(), 200, route);
      return response;
    };
    async function postVia(button, suffix, status) {
      const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith(suffix));
      await button.click();
      const result = await response;
      assert.equal(result.status(), status, await result.text());
      return result.json();
    }
    async function upload(name, directory) {
      await page.goto(base + '#/p/' + project.id);
      await page.getByText('New dataset', { exact: true }).click();
      await page.getByLabel('Dataset name', { exact: true }).fill(name);
      await page.getByLabel('Custodian ID', { exact: true }).fill(instance.custodian);
      const { created } = await postVia(page.getByRole('button', { name: 'Create dataset', exact: true }), '/datasets', 201);
      await page.getByRole('link', { name: 'Upload a dataset', exact: true }).click();
      await page.getByLabel('Choose a folder').setInputFiles(directory);
      await page.getByLabel('Place its contents in').fill('');
      const preview = await postVia(page.getByRole('button', { name: 'Upload and preview' }), '/preview', 200);
      assert(preview.candidate.assessment.admissible);
      await page.getByRole('heading', { name: 'Coverage by data type' }).waitFor();
      const { ref } = await postVia(page.getByRole('button', { name: 'Accept release', exact: true }), '/accept', 200);
      assert.equal(ref.dataset, created.dataset);
      await page.getByRole('tab', { name: /^Files/ }).waitFor();
      const route = '/v1/datasets/' + ref.dataset + '/releases/' + ref.release;
      const release = await (await read(route)).json();
      assert.equal(release.manifest, preview.candidate.manifest);
      assert.equal((await (await read('/v1/datasets/' + ref.dataset)).json()).head, ref.release);
      return { ref, route, release };
    }
    async function showCoverage(result, file, level) {
      await page.goto(base + '#/d/' + result.ref.dataset + '/r/' + result.ref.release + '?path=' + encodeURIComponent(file));
      await page.getByRole('group', { name: 'File coverage' }).getByText(level, { exact: true }).waitFor();
      assert.equal(result.release.entries.find(e => e.path === file).level, level.toLowerCase());
    }
    async function verifyBytes(result, directory) {
      const files = fs.readdirSync(directory, { recursive: true })
        .filter(p => fs.statSync(path.join(directory, p)).isFile()).sort();
      assert.deepEqual(result.release.entries.filter(e => e.kind === 'file').map(e => e.path).sort(), files);
      for (const file of files) {
        const stored = await read(result.route + '/file?path=' + encodeURIComponent(file));
        assert.equal(sha(await stored.body()), sha(fs.readFileSync(path.join(directory, file))), file);
      }
      return files.length;
    }

    await page.goto(base + '#/p/' + project.id);
    await page.getByRole('link', { name: 'Sign in with your institution' }).click();
    await page.locator('#username').fill(alice.username);
    await page.locator('#password').fill(alice.credentials[0].value);
    await page.locator('#kc-login').click();
    await page.getByRole('heading', { name: project.name, exact: true }).waitFor();
    assert.equal(await page.evaluate(() => sessionStorage.getItem('sanctity.token')), null);

    fs.mkdirSync(path.dirname(path.join(mri, image)), { recursive: true });
    fs.copyFileSync(path.join(fixtures, mriFixture, 'sub-01/func/sub-01_task-cue_run-01_echo-1_bold.nii'), path.join(mri, image));
    fs.writeFileSync(path.join(mri, 'dataset_description.json'), '{"Name":"Curator MRI","BIDSVersion":"1.11.2","DatasetType":"raw"}\n');
    fs.writeFileSync(path.join(mri, companion), '{"TaskName":"cue","RepetitionTime":1.5}\n');
    const first = await upload('D07 MRI', mri);
    const hasMismatch = release => release.assessment.findings.some(f => f.code === 'MriRepetitionTimeMismatch' && f.path === image);
    assert(hasMismatch(first.release));
    await showCoverage(first, image, 'Assessed');
    await page.getByRole('button', { name: 'Fix: edit ' + companion }).click();
    await page.locator('#c-text').waitFor();
    const fixText = await page.locator('#c-text').inputValue();
    assert.equal(JSON.parse(fixText).RepetitionTime, 2);
    await page.locator('#c-reason').fill('Match repetition time to the synthetic NIfTI header.');
    const corrected = await postVia(page.getByRole('button', { name: 'Prepare preview' }), '/corrections', 201);
    assert(!hasMismatch(corrected.candidate));
    const { ref: fixed } = await postVia(page.getByRole('button', { name: 'Accept as new release' }), '/accept', 200);
    const fixedRoute = '/v1/datasets/' + fixed.dataset + '/releases/' + fixed.release;
    const newRelease = await (await read(fixedRoute)).json();
    assert(!hasMismatch(newRelease));
    assert(hasMismatch(await (await read(first.route)).json()));
    assert.equal(await verifyBytes(first, mri), 3);
    checks.push({ workflow: 'MRI finding and correction', initial: first.ref, corrected: fixed, finding: 'MriRepetitionTimeMismatch' });

    const eeg = await upload('D07 EEG', path.join(fixtures, eegFixture));
    await showCoverage(eeg, recording, 'Described');
    assert.equal(await verifyBytes(eeg, path.join(fixtures, eegFixture)), 54);
    checks.push({ workflow: 'EEG upload', release: eeg.ref, files: 54 });

    const archive = await upload('D07 archive', path.join(fixtures, archiveFixture));
    const archivedFile = archive.release.entries.find(e => e.kind === 'file' && e.level === 'preserved');
    await showCoverage(archive, archivedFile.path, 'Preserved');
    await page.getByRole('heading', { name: 'Face study protocol archive', exact: true }).waitFor();
    assert.equal(archive.release.profile, 'sanctity-archive-v1');
    assert.equal(await verifyBytes(archive, path.join(fixtures, archiveFixture)), 3);
    checks.push({ workflow: 'Archive upload', release: archive.ref, files: 3 });

    assert.deepEqual(errors, []);
    fs.writeFileSync(evidence, JSON.stringify(output, null, 2) + '\n');
    console.log('PASS packaged curator workflow: Keycloak sign-in, MRI finding and fix, EEG and archive uploads, and file coverage');
  } finally {
    await browser.close();
    fs.rmSync(mri, { recursive: true, force: true });
  }
}

run(process.argv[2], process.argv[3]).catch(error => {
  console.error(error.message.replace(/(code|state|session_state)=[^&\s]+/g, '$1=[redacted]'));
  process.exitCode = 1;
});
