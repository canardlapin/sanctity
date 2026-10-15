'use strict';
// Original assertions remain in checks/. This runner adds portable builds,
// source-bound results and a fail-closed comparison with the recovered v4f build.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawnSync, execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const harness = __dirname;
const ui = path.join(root, 'modules/http/src/main/resources/sanctity/ui');
fs.mkdirSync(path.join(root, 'target'), { recursive: true });
const output = fs.mkdtempSync(path.join(root, 'target/console-check.'));
const env = { ...process.env, HARNESS: harness, CONSOLE_OUTPUT_ROOT: output };
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const run = (name, command, args) => {
  const result = spawnSync(command, args, { cwd: root, env, encoding: 'utf8', timeout: 300000, maxBuffer: 8 * 1024 * 1024 });
  const log = (result.stdout || '') + (result.stderr || '') + (result.error ? '\n' + result.error.message : '');
  fs.writeFileSync(path.join(output, name + '.log'), log);
  process.stdout.write(log);
  return { exitCode: result.status, error: result.error?.message, log: name + '.log', sha256: sha(path.join(output, name + '.log')), text: log };
};
const build = path.join(output, 'current');
fs.mkdirSync(build);
for (const file of ['index.html', 'app.css', 'app.js', 'demo.js']) fs.copyFileSync(path.join(ui, file), path.join(build, file));
fs.copyFileSync(path.join(harness, 'specimens/drive.js'), path.join(build, 'drive.js'));
const specs = JSON.parse(fs.readFileSync(path.join(harness, 'specimens/specs.json')));
const index = fs.readFileSync(path.join(build, 'index.html'), 'utf8');
if (!index.includes('<script src="app.js"></script>')) throw new Error('Console script marker missing');
for (const [name, spec] of Object.entries(specs)) {
  fs.writeFileSync(path.join(build, `spec-${name}.js`), `window.SPEC = ${JSON.stringify(spec)};\ntry { localStorage.clear(); sessionStorage.clear(); } catch (e) {}\nlocation.hash = window.SPEC.hash;\n`);
  fs.writeFileSync(path.join(build, name + '.html'), index.replace('<script src="app.js"></script>',
    `<script src="spec-${name}.js"></script><script src="demo.js"></script><script src="app.js"></script><script src="drive.js"></script>`));
}
fs.cpSync(path.join(root, 'docs/evidence/console/original/out/v4f'), path.join(output, 'v4f'), { recursive: true });
const sources = Object.fromEntries(['index.html', 'app.css', 'app.js', 'demo.js'].map(f => [f, sha(path.join(build, f))]));
const harnessSources = {};
const recordHarness = dir => {
  for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
    if (item.name === 'node_modules') continue;
    const file = path.join(dir, item.name);
    if (item.isDirectory()) recordHarness(file);
    else harnessSources[path.relative(root, file)] = sha(file);
  }
};
recordHarness(harness);
harnessSources['scripts/check-console.sh'] = sha(path.join(root, 'scripts/check-console.sh'));
const expectedFailures = [
  'carried-draft-no-lost-update', 'grouped-mismatch-key-facts', 'blocked-preview-error-first',
  'row-lists-readable', 'odd-path-refused task-nback_bold.json/x.json', 'odd-path-refused sub-03/ .json', 'resize-keeps-focus'
].sort();
// These pre-existing assertions were rewritten in v4g for its new DOM/wording.
// They cannot measure v4f behavior unchanged; preserve their raw outcomes but
// distinguish them from the seven newly added defect reproductions.
const versionSpecificAssertions = {
  'mobile-tree-folds': 'v4g tests .tree-fold-bar / #release-tree; v4f used a details/summary fold',
  'fold-follows-viewport': 'v4g tests the CSS fold structure introduced after v4f',
  'head-keeps-draft': 'v4g expects the new “is restored” notice; v4f used “carried over”'
};
// Probes for behaviour added after v4g (table/gradient corrections, unconfirmed policy outcomes).
// v4f never had these features, so their v4f outcomes are recorded but not required either way.
const postV4gAssertions = [
  'companion-correction-offered', 'table-tab-separator', 'table-width-refused', 'table-correction-accepted', 'table-blocked-row-marked',
  'beh-correction-checked', 'gradient-correction-checked', 'table-carried-no-lost-update', 'table-preview-mobile',
  'policy-unknown-revoke', 'policy-pending-grant'
];
const assertionCount = 59 + postV4gAssertions.length;
const checks = {};
checks.current = run('current', 'bash', [path.join(harness, 'checks/run.sh'), 'current']);
checks.v4f = run('v4f', process.execPath, [path.join(harness, 'checks/behaviour.js'), 'v4f']);
const assertions = text => [...text.matchAll(/^behaviour (.+?): (PASS|FAIL)(?: |$)/gm)].map(m => ({ name: m[1], result: m[2] }));
checks.current.assertions = assertions(checks.current.text);
checks.v4f.assertions = assertions(checks.v4f.text);
const actualFailures = checks.v4f.assertions.filter(a => a.result === 'FAIL').map(a => a.name).sort();
const extraFailures = actualFailures.filter(name => !expectedFailures.includes(name));
const differentialPassed = checks.v4f.exitCode === 1 && checks.v4f.assertions.length === assertionCount &&
  expectedFailures.every(name => actualFailures.includes(name)) &&
  extraFailures.every(name => Object.hasOwn(versionSpecificAssertions, name) || postV4gAssertions.includes(name));
const currentPassed = checks.current.exitCode === 0 && checks.current.assertions.length === assertionCount &&
  checks.current.assertions.every(a => a.result === 'PASS') && /ui summary: 27\/27 PASS/.test(checks.current.text);
for (const value of Object.values(checks)) delete value.text;
(async () => {
  const browser = await require('./scripts/lib').launch();
  const browserVersion = browser.version(); await browser.close();
  const receipt = {
    date: new Date().toISOString(), baseRevision: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    sourceTreeDirty: !!execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }).trim(),
    sourceSha256: sources, harnessSha256: harnessSources, platform: `${os.platform()} ${os.release()} ${os.arch()}`, node: process.version,
    playwright: require('./node_modules/playwright/package.json').version, axe: require('./node_modules/axe-core/package.json').version,
    browser: { engine: 'chromium', version: browserVersion },
    uiViewports: [{ width: 1440, height: 900, scheme: 'light' }, { width: 390, height: 900, scheme: 'light' }, { width: 1440, height: 900, scheme: 'dark' }],
    behaviourViewports: '1440x900 default; explicit 390x844 and resize cases in checks/behaviour.js',
    checks, differential: { expectedFailures, actualFailures, versionSpecificAssertions, postV4gAssertions, extraFailures, passed: differentialPassed },
    passed: currentPassed && differentialPassed,
    limits: ['Synthetic demo transport; PostgreSQL contracts are qualified separately.', 'axe/keyboard/layout checks are not a real VoiceOver test.']
  };
  fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
  console.log(`Console evidence: ${output}`);
  console.log(`Current gate: ${currentPassed ? 'PASS' : 'FAIL'}; seven v4f regressions: ${differentialPassed ? 'PASS' : 'FAIL'}`);
  process.exitCode = receipt.passed ? 0 : 1;
})().catch(error => { console.error(error); process.exitCode = 1; });
