'use strict';
// All writes go through the visible console controls. Independent HTTP reads
// verify results; no demo transport, request interception or injected app state.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { setTimeout: delay } = require('node:timers/promises');
const { chromium, request } = require('playwright');
const config = JSON.parse(fs.readFileSync(0, 'utf8'));
const output = path.resolve(process.argv[2]);
const secrets = [config.ownerToken, config.colleagueToken, config.recipientToken];
const redact = text => secrets.reduce((s, secret) => s.split(secret).join('[credential]'), String(text));
const results = [], requests = [], pageErrors = [], contexts = [], clients = [];
const persisted = {};
const servedUiSha256 = {};
let browser, currentPage, stage = 'launch';
const runPath = 'sub-02/func/sub-02_task-nback_run-1_bold.nii';
const subjectPath = 'sub-02/sub-02_task-nback_bold.json';
const rootPath = 'task-nback_bold.json';
const releaseUrl = release => `/v1/datasets/${config.dataset}/releases/${release}`;
const releaseHash = (release, file) => `#/d/${config.dataset}/r/${release}` + (file ? '?path=' + encodeURIComponent(file) : '');
const correctionHash = release => `#/d/${config.dataset}/r/${release}/correct?path=${rootPath}`;
const read = async (api, endpoint, status = 200) => {
  const response = await api.get(endpoint);
  assert.equal(response.status(), status, endpoint);
  return response;
};
const json = async (api, endpoint) => (await read(api, endpoint)).json();
const sidecar = async (api, release) => JSON.parse(await (await read(api, releaseUrl(release) + '/file?path=' + rootPath)).text());
const metadata = (api, release) => json(api, releaseUrl(release) + '/metadata?path=' + encodeURIComponent(runPath));
const check = (name, detail) => { results.push({ name, passed: true, detail }); console.log('PASS ' + name); };
const until = async (predicate, message) => {
  for (let i = 0; i < 100; i++) { if (await predicate()) return; await delay(50); }
  throw new Error(message);
};
const capture = async (page, name) => {
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(output, name + '.png'), fullPage: true });
};
const apiClient = async token => {
  const api = await request.newContext({ baseURL: config.baseUrl, extraHTTPHeaders: { Authorization: 'Bearer ' + token } });
  clients.push(api); return api;
};
const newPage = async (width = 1440) => {
  const context = await browser.newContext({ viewport: { width, height: width === 390 ? 844 : 900 }, colorScheme: 'light' });
  contexts.push(context);
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', error => pageErrors.push(redact(error.message)));
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/v1/') || url.pathname.endsWith('/demo.js')) {
      requests.push({ method: response.request().method(), path: url.pathname, status: response.status() });
    }
  });
  return page;
};
const signIn = async (page, token, dataset = config.dataset) => {
  await page.goto(config.baseUrl + '/');
  await page.locator('#token').fill(token);
  await page.locator('#dataset').fill(dataset);
  await page.getByRole('button', { name: 'Sign in and open', exact: true }).press('Enter');
  if (dataset) await page.getByRole('tab', { name: /^Files/ }).waitFor();
  else await page.getByRole('heading', { name: 'Open a dataset', exact: true }).waitFor();
  assert.equal(await page.evaluate(() => typeof window.SanctityDemo), 'undefined');
};
const editor = async (page, release) => {
  await page.goto(config.baseUrl + '/' + correctionHash(release));
  await page.locator('#c-text').waitFor();
  await until(async () => (await page.locator('#c-text').inputValue()).includes('FlipAngle'), 'Editor did not load the real sidecar');
  return JSON.parse(await page.locator('#c-text').inputValue());
};
const prepare = async (page, text, reason) => {
  await page.locator('#c-text').fill(JSON.stringify(text, null, 2) + '\n');
  await page.locator('#c-reason').fill(reason);
  const responsePromise = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/corrections'));
  await page.getByRole('button', { name: 'Prepare preview', exact: true }).press('Enter');
  const response = await responsePromise;
  assert.equal(response.status(), 201);
  const preview = await response.json();
  await page.locator('[data-fk="accept"]').waitFor();
  return preview;
};
const accept = async (page, status = 200) => {
  const pending = page.waitForResponse(r => r.request().method() === 'POST' && /\/candidates\/[^/]+\/accept$/.test(new URL(r.url()).pathname));
  await page.locator('[data-fk="accept"]').press('Enter');
  const response = await pending;
  assert.equal(response.status(), status);
  if (status === 200) {
    await page.locator('[data-fk="receipt-head"]').waitFor();
    await until(async () => await page.getByText('Matches preview', { exact: true }).count() === 3, 'Accepted release identity was not verified');
  }
  return response.json();
};

(async () => {
  const startedAt = new Date().toISOString();
  let failure;
  try {
    browser = await chromium.launch({ headless: true });
    const owner = await newPage(), colleague = await newPage(), recipient = await newPage(390);
    currentPage = owner;
    const ownerApi = await apiClient(config.ownerToken), recipientApi = await apiClient(config.recipientToken);
    for (const name of ['index.html', 'app.js', 'app.css']) {
      const served = await read(ownerApi, name === 'index.html' ? '/' : '/' + name);
      const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
      servedUiSha256[name] = sha(await served.body());
      assert.equal(servedUiSha256[name], sha(fs.readFileSync(path.join(__dirname, '../../modules/http/src/main/resources/sanctity/ui', name))));
    }
    stage = 'authentication';
    const shell = await owner.goto(config.baseUrl + '/');
    assert(shell.headers()['content-security-policy'].includes("script-src 'self'"));
    await owner.getByRole('button', { name: 'Sign in and open', exact: true }).press('Enter');
    await owner.getByRole('alert').filter({ hasText: 'Enter an access token.' }).waitFor();
    assert.equal(await owner.locator('#token').getAttribute('aria-invalid'), 'true');
    await owner.locator('#token').fill('invalid-rehearsal-credential');
    const denied = owner.waitForResponse(r => new URL(r.url()).pathname === '/v1/me');
    await owner.getByRole('button', { name: 'Sign in and open', exact: true }).press('Enter');
    assert.equal((await denied).status(), 401);
    await signIn(owner, config.ownerToken);
    await signIn(colleague, config.colleagueToken);
    await read(recipientApi, releaseUrl(config.initialRelease), 404);
    check(stage, 'Empty and invalid credentials rejected; two independent curators authenticate; ungranted recipient cannot read.');

    stage = 'metadata-and-relationships';
    await owner.getByLabel('Filter files', { exact: true }).fill(runPath.split('/').pop());
    await owner.locator('.tree .item').filter({ hasText: runPath.split('/').pop() }).click();
    await owner.locator('.inspector .fieldrow').first().waitFor();
    assert.match(await owner.locator('.inspector').innerText(), /Authored RepetitionTime\s+1.5\s+s\s+Header volume spacing\s+2\s+s/);
    const initial = await json(ownerApi, releaseUrl(config.initialRelease));
    assert(initial.relationships.links.some(l => l.kind === 'bids.intended-for' && l.from === runPath && l.to.includes('sub-02/fmap/sub-02_dir-AP_epi.nii')));
    await capture(owner, 'metadata-desktop');
    check(stage, 'Real imported NIfTI header reports 2 s against authored TR 1.5 s, with recorded fieldmap relationships.');

    stage = 'subject-sidecar';
    // Fix on the real TR finding proposes the run's own sidecar from the backend's recorded header spacing.
    const runSidecar = runPath.replace(/\.nii(\.gz)?$/, '.json');
    await owner.locator('.inspector button.fix').click();
    await until(async () => (await owner.locator('#c-text').inputValue()).includes('RepetitionTime'), 'Fix did not prepare the run sidecar');
    assert.equal(await owner.locator('#c-new').inputValue(), runSidecar);
    assert.equal(JSON.parse(await owner.locator('#c-text').inputValue()).RepetitionTime, 2);
    assert.match(await owner.locator('.fix-note').innerText(), /header volume spacing; the authored value is 1\.5\s*s/);
    assert.equal(await owner.locator('#c-reason').inputValue(), '');
    await owner.getByRole('link', { name: 'Cancel', exact: true }).click();
    await owner.locator('.inspector .fieldrow').first().waitFor();
    // This stage then corrects the whole subject instead, with a subject-level sidecar that BIDS inheritance applies.
    await owner.getByRole('link', { name: 'Add sidecar', exact: true }).click();
    await owner.locator('#c-new').fill(subjectPath);
    await owner.locator('#c-text').fill('{"RepetitionTime":2.0}');
    await owner.getByRole('button', { name: 'Prepare preview', exact: true }).press('Enter');
    await owner.locator('#c-errors').getByRole('alert').waitFor();
    assert.equal(await owner.evaluate(() => document.activeElement.id), 'c-reason');
    const preview = await prepare(owner, { RepetitionTime: 2.0 }, 'Use the header volume spacing for sub-02 nback');
    assert.deepEqual(preview.metadataChanges.map(m => m.path), [runPath]);
    assert(!preview.candidate.assessment.findings.some(f => f.path === runPath && f.code === 'MriRepetitionTimeMismatch'));
    assert.match(await owner.locator('main').innerText(), /RESOLVED\s+MriRepetitionTimeMismatch/);
    await capture(owner, 'subject-sidecar-preview');
    const accepted = await accept(owner);
    const subjectRelease = accepted.ref.release;
    await owner.getByRole('link', { name: 'Open new release', exact: true }).click();
    await owner.reload();
    await owner.locator('.inspector .fieldrow').first().waitFor();
    const fixed = await metadata(ownerApi, subjectRelease);
    assert.equal(fixed.fields.RepetitionTime.value, 2);
    assert.equal(fixed.fields.RepetitionTime.origin, subjectPath);
    assert.equal((await metadata(ownerApi, config.initialRelease)).fields.RepetitionTime.value, 1.5);
    check(stage, 'Browser accepts the subject-level sidecar; reload and independent HTTP reads confirm TR 2 s and immutable initial TR 1.5 s.');

    stage = 'frozen-query';
    await owner.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Query', exact: true }).click();
    await owner.getByLabel('Participant condition 1 value', { exact: true }).fill('30');
    const selected = owner.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/v1/selections');
    await owner.getByRole('button', { name: 'Freeze selection', exact: true }).press('Enter');
    const selectionResponse = await selected;
    assert.equal(selectionResponse.status(), 201);
    const selection = (await selectionResponse.json()).id;
    await owner.locator('table.data tbody tr').first().waitFor();
    assert.equal(await owner.locator('table.data tbody tr').count(), 2);
    const frozen = await json(ownerApi, '/v1/selections/' + selection);
    assert.equal(frozen.total, 2);
    assert(frozen.hits.every(hit => hit.participant === 'sub-02'));
    await capture(owner, 'query-desktop');
    persisted.selection = selection;
    check(stage, 'Age ≥30 and BOLD selection contains the two sub-02 runs at the accepted release.');

    stage = 'concurrent-correction';
    const draft = await editor(owner, subjectRelease);
    draft.EchoTime = 0.031;
    const stale = await prepare(owner, draft, 'Correct EchoTime to 0.031 seconds');
    const colleagueDraft = await editor(colleague, subjectRelease);
    colleagueDraft.FlipAngle = 60;
    await prepare(colleague, colleagueDraft, 'Colleague independently corrects FlipAngle to 60');
    const colleagueAccepted = await accept(colleague);
    const conflict = await accept(owner, 409);
    assert.equal(conflict.error, 'conflict');
    assert.equal((await json(ownerApi, '/v1/datasets/' + config.dataset)).head, colleagueAccepted.ref.release);
    await owner.setViewportSize({ width: 390, height: 844 });
    await owner.getByRole('link', { name: 'Correct the current head', exact: true }).click();
    await owner.getByText('Your earlier edit', { exact: true }).waitFor();
    const carried = JSON.parse(await owner.locator('#c-text').inputValue());
    assert.equal(carried.FlipAngle, 60); assert.equal(carried.EchoTime, 0.03);
    await owner.getByText('Your earlier edit', { exact: true }).click();
    await capture(owner, 'conflict-recovery-390');
    carried.EchoTime = 0.031;
    await prepare(owner, carried, 'Reapply EchoTime while preserving the accepted FlipAngle');
    const recovered = await accept(owner);
    persisted.head = recovered.ref.release;
    persisted.staleCandidate = stale.candidate.id;
    const saved = await sidecar(ownerApi, persisted.head);
    assert.equal(saved.FlipAngle, 60); assert.equal(saved.EchoTime, 0.031);
    assert.equal((await sidecar(ownerApi, subjectRelease)).FlipAngle, 52);
    assert.equal((await sidecar(ownerApi, colleagueAccepted.ref.release)).EchoTime, 0.03);
    assert.deepEqual(await json(ownerApi, '/v1/selections/' + selection), frozen);
    await owner.getByRole('link', { name: 'Open new release', exact: true }).click();
    await owner.reload();
    await owner.locator('.inspector .fieldrow').first().waitFor();
    assert.match(await owner.locator('.inspector').innerText(), /60°/);
    await capture(owner, 'conflict-accepted-390');
    check(stage, 'Two authenticated sessions produce a real 409. Reapplication preserves FlipAngle 60 and EchoTime 0.031; historical sidecars and frozen selection stay unchanged.');

    stage = 'table-correction';
    // Sub-01's events run past the 18 s window (12 volumes at 1.5 s): the real backend re-checks a corrected table.
    const eventsPath = 'sub-01/func/sub-01_task-nback_run-1_events.tsv';
    await owner.setViewportSize({ width: 1440, height: 900 });
    // Start where a curator sees the problem: the Assessment tab's Fix on the real finding.
    await owner.goto(config.baseUrl + '/' + `#/d/${config.dataset}/r/${persisted.head}?tab=assessment`);
    await owner.locator(`button.fix[aria-label="Fix: edit ${eventsPath}"]`).click();
    await until(async () => (await owner.locator('#c-text').inputValue()).startsWith('onset\t'), 'Editor did not load the real events table');
    const table = await owner.locator('#c-text').inputValue();
    const flagged = await owner.evaluate(() => { const t = document.querySelector('#c-text'); return { line: t.value.slice(0, t.selectionStart).split('\n').length, focus: document.activeElement.id }; });
    const flaggedRows = (await owner.locator('.fix-note').innerText()).match(/Rows? ([\d, ]+) (?:are|is) flagged/);
    assert(flaggedRows, 'Fix did not name the flagged rows');
    assert.equal(flagged.line, Number(flaggedRows[1].split(',')[0]));
    assert.equal(flagged.focus, 'c-text');
    const prepareTable = async (text, reason) => {
      await owner.locator('#c-text').fill(text); await owner.locator('#c-reason').fill(reason);
      const pending = owner.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/corrections'));
      await owner.getByRole('button', { name: 'Prepare preview', exact: true }).press('Enter');
      const response = await pending; assert.equal(response.status(), 201);
      await owner.locator('[data-fk="accept"]').waitFor();
      return response.json();
    };
    const blocked = await prepareTable(table.replace('\n6.0\t', '\nabc\t'), 'Probe: a malformed onset must block');
    assert(!blocked.candidate.assessment.admissible);
    assert(blocked.candidate.assessment.findings.some(f => f.code === 'EventsNumber' && f.path === eventsPath && /row 2 column 1/.test(f.message)));
    assert.equal(await owner.locator('[data-fk="preview-head"]').innerText(), 'Table change');
    assert.deepEqual(await owner.locator('.tdiff tr.flagged th').allInnerTexts(), ['2']);
    assert(await owner.locator('[data-fk="accept"]').isDisabled());
    await capture(owner, 'table-blocked-desktop');
    await owner.getByRole('button', { name: 'Back to edit', exact: true }).press('Enter');
    const fixedTable = table.replace('\n30.0\t', '\n14.0\t').replace('\n33.0\t', '\n16.0\t');
    assert.notEqual(fixedTable, table);
    const tablePreview = await prepareTable(fixedTable, 'Log sheet gives onsets 14 and 16 s for the last two trials');
    assert(tablePreview.candidate.assessment.admissible);
    assert(!tablePreview.candidate.assessment.findings.some(f => f.code === 'EventsOutsideWindow' && f.path === eventsPath));
    assert.deepEqual(await owner.locator('.tdiff tbody tr:not(.gap) th').allInnerTexts(), ['4', '5', '5', '6', '6']);
    assert.match(await owner.locator('.finding', { hasText: 'EventsOutsideWindow' }).first().innerText(), /resolved/i);
    await capture(owner, 'table-preview-desktop');
    const tableAccepted = await accept(owner);
    assert.match(await owner.locator('.steps [aria-current="step"]').innerText(), /Accepted/);
    persisted.head = tableAccepted.ref.release;
    assert.equal(await (await read(ownerApi, releaseUrl(persisted.head) + '/file?path=' + encodeURIComponent(eventsPath))).text(), fixedTable);
    assert(!(await json(ownerApi, releaseUrl(persisted.head))).assessment.findings.some(f => f.code === 'EventsOutsideWindow' && f.path === eventsPath));
    assert.equal((await sidecar(ownerApi, persisted.head)).FlipAngle, 60);
    await owner.getByRole('link', { name: 'Open new release', exact: true }).click();
    await until(async () => decodeURIComponent(await owner.evaluate(() => location.hash)).includes(persisted.head + '?path=' + eventsPath), 'Open new release did not show the corrected table');
    await owner.locator('.inspector-head').waitFor();
    check(stage, 'A malformed onset is blocked with its row marked; corrected onsets clear the window warning, are accepted and become the head without disturbing the sidecar.');

    stage = 'fixed-release-grant';
    await owner.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Access', exact: true }).click();
    await owner.locator('#g-recipient').fill(config.recipient);
    await owner.locator('#act-ReadContent').check();
    const grantPending = owner.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/grants'));
    await owner.getByRole('button', { name: 'Create grant', exact: true }).press('Enter');
    const grantResponse = await grantPending;
    assert.equal(grantResponse.status(), 201);
    const grant = await grantResponse.json(); persisted.grant = grant.id;
    assert.equal(grant.release, persisted.head);
    assert.deepEqual([...grant.actions].sort(), ['ReadContent', 'ReadMetadata']);
    await owner.locator('table.data tbody tr button.danger').waitFor();
    assert.equal(await owner.evaluate(() => document.activeElement.textContent), 'Revoke');
    await owner.locator('table.data tbody tr button.danger').press('Enter');
    await owner.locator('button.confirm').waitFor();
    await owner.keyboard.press('Escape');
    assert.equal(await owner.locator('button.confirm').count(), 0);
    await signIn(recipient, config.recipientToken, '');
    await recipient.goto(config.baseUrl + '/' + releaseHash(persisted.head, runPath));
    await recipient.locator('.inspector .fieldrow').first().waitFor();
    await read(recipientApi, releaseUrl(persisted.head));
    await read(recipientApi, '/v1/datasets/' + config.dataset, 404);
    await read(recipientApi, releaseUrl(config.initialRelease), 404);
    const delivered = await read(recipientApi, releaseUrl(persisted.head) + '/file?path=' + rootPath);
    assert.equal(JSON.parse(await delivered.text()).FlipAngle, 60);
    await capture(recipient, 'recipient-fixed-release-390');
    check(stage, 'Recipient reads granted release and bytes after Escape cancels revocation; dataset head and ungranted historical release remain hidden.');

    stage = 'revocation';
    await owner.locator('table.data tbody tr button.danger').press('Enter');
    await owner.locator('button.confirm').click();
    await owner.getByText(/^Revoked /).waitFor();
    await read(recipientApi, releaseUrl(persisted.head), 404);
    await read(recipientApi, releaseUrl(persisted.head) + '/file?path=' + rootPath, 404);
    const lost = recipient.waitForResponse(r => new URL(r.url()).pathname === releaseUrl(persisted.head));
    await recipient.reload();
    assert.equal((await lost).status(), 404);
    assert.equal(await recipient.locator('.inspector .fieldrow').count(), 0);
    await capture(owner, 'grant-revoked-390');
    check(stage, 'Confirmed revocation denies fresh metadata/file requests and removes the recipient view after reload.');

    stage = 'transport';
    assert.equal(pageErrors.length, 0, pageErrors.join('\n'));
    assert(!requests.some(r => r.path.endsWith('/demo.js')));
    assert(requests.some(r => r.status === 409));
    check(stage, 'Real same-origin API traffic under production CSP; no demo transport or browser exceptions.');
  } catch (error) {
    failure = { stage, message: redact(error.stack || error) };
    if (currentPage && !currentPage.isClosed()) await capture(currentPage, 'failure').catch(() => {});
    console.error(JSON.stringify(failure, null, 2));
  } finally {
    const receipt = { schema: 'sanctity.console-backend-browser.v1', startedAt, finishedAt: new Date().toISOString(),
      browser: browser?.version(), headless: true, fixture: 'invented BIDS data with real NIfTI headers; ordinary import and acceptance',
      interactions: { curatorAndColleague: '1440x900; curator switches to 390x844 for conflict recovery and access', recipient: '390x844' },
      results, persisted, servedUiSha256, requests, pageErrors, ...(failure ? { failure } : {}), passed: !failure,
      limits: ['Not a VoiceOver or representative scientific-data qualification.', 'No crash/restart test; independent SQL verification follows browser/server shutdown.'] };
    fs.writeFileSync(path.join(output, 'browser.json'), JSON.stringify(receipt, null, 2) + '\n');
    for (const client of clients) await client.dispose();
    for (const context of contexts) await context.close();
    await browser?.close();
    process.exitCode = failure ? 1 : 0;
  }
})().catch(error => { console.error(redact(error.stack || error)); process.exitCode = 1; });
