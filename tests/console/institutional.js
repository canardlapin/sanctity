// Real Keycloak -> packaged service -> PostgreSQL. All four accounts in the demo are fictional.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

async function run(home) {
  const demo = JSON.parse(fs.readFileSync(path.join(home, 'instance.json')));
  const users = JSON.parse(fs.readFileSync(path.join(home, 'demo-realm.json'))).users;
  const base = new URL(demo.url).origin;
  const owner = demo.people.find(p => p.key === 'alice');
  const recipient = demo.people.find(p => p.key === 'dave');
  const study = demo.labs[0].datasets.find(d => d.label === 'Matching Pennies EEG');
  const dataset = '/v1/datasets/' + study.id;
  const release = dataset + '/releases/' + study.head;
  const browser = await chromium.launch({ headless: true });
  const request = (page, route, method = 'GET') => page.evaluate(async ({ route, method }) => {
    const response = await fetch(route, { method, headers: method === 'GET' ? {} : { 'X-Sanctity-CSRF': '1' } });
    return { status: response.status, body: await response.json().catch(() => null) };
  }, { route, method });
  async function login(who, fragment) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(base + '/' + fragment);
    await page.getByRole('link', { name: 'Sign in with your institution' }).click();
    const user = users.find(u => u.username === who.key);
    await page.locator('#username').fill(user.username);
    await page.locator('#password').fill(user.credentials[0].value);
    await page.locator('#kc-login').click();
    await page.getByRole('button', { name: 'Sign out' }).waitFor();
    assert.equal(new URL(page.url()).hash, fragment);
    assert.equal((await request(page, '/v1/me')).body.principal, who.principal);
    const session = (await context.cookies()).find(c => c.domain === new URL(base).hostname && c.name === 'sanctity-local-' + new URL(base).port + '-session');
    assert.ok(session && session.httpOnly && session.secure && session.sameSite === 'Lax');
    assert.equal(await page.evaluate(() => sessionStorage.getItem('sanctity.token')), null);
    return page;
  }
  let curator, grant;
  try {
    const anonymous = await browser.newContext();
    const incomplete = await anonymous.request.get(base + '/auth/callback');
    assert.equal(incomplete.status(), 401);
    assert.equal(incomplete.headers()['cache-control'], 'no-store');
    assert.equal((await anonymous.request.get(base + '/v1/me')).status(), 401);
    await anonymous.close();
    const visitor = await login(recipient, '#/');
    assert.equal((await request(visitor, release)).status, 404);
    assert.equal((await request(visitor, dataset + '/recipients?q=Alice')).status, 404);
    curator = await login(owner, '#/d/' + study.id + '/access?release=' + study.head);
    await curator.getByLabel('Find a recipient by name').fill('Dave');
    await curator.getByRole('button', { name: new RegExp(recipient.name) }).click();
    assert.equal(await curator.locator('#g-recipient').inputValue(), recipient.principal);
    const issued = curator.waitForResponse(r => r.url() === base + dataset + '/grants' && r.request().method() === 'POST');
    await curator.getByRole('button', { name: 'Create grant' }).click();
    const created = await issued;
    assert.equal(created.status(), 201);
    grant = (await created.json()).id;
    assert.equal((await request(visitor, release)).status, 200);
    assert.equal((await request(visitor, dataset)).status, 404);
    assert.equal((await request(curator, dataset + '/grants/' + grant, 'DELETE')).status, 204);
    grant = null;
    assert.equal((await request(visitor, release)).status, 404);
    assert.equal(await visitor.evaluate(async () => (await fetch('/auth/logout', { method: 'POST' })).status), 403);
    await visitor.getByRole('button', { name: 'Sign out' }).click();
    await visitor.getByRole('link', { name: 'Sign in with your institution' }).waitFor();
    assert.equal((await request(visitor, '/v1/me')).status, 401);
    console.log('PASS two provider logins, protected session cookie, deep link, by-name release share, revocation and logout');
  } finally {
    try { if (grant && curator) await request(curator, dataset + '/grants/' + grant, 'DELETE'); }
    finally { await browser.close(); }
  }
}
run(process.argv[2]).catch(error => { console.error(error.message.replace(/(code|state|session_state)=[^&\s]+/g, '$1=[redacted]')); process.exitCode = 1; });
