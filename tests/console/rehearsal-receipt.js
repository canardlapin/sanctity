'use strict';
// Bind browser and independent PostgreSQL results to source files and artifacts.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const output = path.resolve(process.argv[2]);
const read = file => JSON.parse(fs.readFileSync(path.join(output, file), 'utf8'));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const browser = read('browser.json'), persistence = read('persistence.json');
const expected = ['authentication', 'directory-navigation', 'metadata-and-relationships', 'subject-sidecar', 'frozen-query',
  'concurrent-correction', 'table-correction', 'fixed-release-grant', 'revocation', 'transport'];
assert(browser.passed && persistence.passed);
assert.deepEqual(browser.results.map(r => r.name), expected);
assert(browser.results.every(r => r.passed));
assert.equal(browser.persisted.head, persistence.head);
const sourceSha256 = {};
const record = relative => { sourceSha256[relative] = sha(path.join(root, relative)); };
const walk = directory => {
  for (const entry of fs.readdirSync(path.join(root, directory), { withFileTypes: true })) {
    const relative = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(relative);
    else if (entry.isFile()) record(relative);
  }
};
for (const module of fs.readdirSync(path.join(root, 'modules'))) {
  const main = `modules/${module}/src/main`;
  if (fs.existsSync(path.join(root, main))) walk(main);
}
for (const file of ['build.sbt', 'project/build.properties', 'project/bids4s-source.properties',
  'vendor/bids4s-source.zip', 'scripts/check-console-backend.sh', '.github/workflows/scientific-core.yml',
  'tests/console/package-lock.json', 'tests/console/rehearsal.js', 'tests/console/rehearsal-receipt.js',
  'modules/integration/src/test/scala/sanctity/postgres/ConsoleRehearsal.scala',
  'modules/integration/src/test/scala/sanctity/postgres/ConsoleSuite.scala',
  'modules/integration/src/test/scala/sanctity/postgres/PostgresFixture.scala']) record(file);
const artifactSha256 = {};
for (const file of fs.readdirSync(output).sort()) {
  if (/\.(png|json|log|txt)$/.test(file) && file !== 'receipt.json') artifactSha256[file] = sha(path.join(output, file));
}
const receipt = {
  schema: 'sanctity.console-backend-rehearsal.v1', date: new Date().toISOString(), passed: true,
  baseRevision: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  sourceTreeDirty: !!execFileSync('git', ['status', '--porcelain', '--', 'modules', 'scripts', 'tests', 'build.sbt', 'project', '.github'], { cwd: root, encoding: 'utf8' }).trim(),
  sourceSha256, artifactSha256, node: process.version, playwright: require('playwright/package.json').version,
  browser: browser.browser, platform: `${os.platform()} ${os.release()} ${os.arch()}`,
  postgresVersion: fs.readFileSync(path.join(output, 'postgres-version.txt'), 'utf8').trim(),
  postgresImage: fs.readFileSync(path.join(output, 'postgres-image.txt'), 'utf8').trim(),
  command: 'bash scripts/check-console-backend.sh', scenarios: browser.results, persistence,
  limits: ['Invented fixture; no representative-data or scientific qualification.',
    'Browser automation does not complete the VoiceOver gate.',
    'A separate existing gate covers database crash/recovery; this rehearsal checks committed rows after browser/server shutdown.']
};
fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
console.log(`Real-backend console rehearsal: PASS (${expected.length} scenarios plus PostgreSQL persistence). Evidence: ${output}`);
