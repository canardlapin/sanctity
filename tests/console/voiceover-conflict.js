'use strict';
// Set up the same synthetic two-writer case as the retained regression, then
// leave all recovery, reading and editing to the VoiceOver tester.
(() => {
  const ds = 'b7e3c1d2-5a4f-4e8b-9c21-6f0a3d9e8b14';
  const release = '9d4b2e61-7a3c-4f18-a5d9-0e6c1b2f8a73';
  location.hash = `#/d/${ds}/r/${release}/correct?path=task-nback_bold.json`;
  const wait = async predicate => {
    for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(r => setTimeout(r, 50)); }
    throw new Error('Conflict fixture did not reach its starting state');
  };
  (async () => {
    await wait(() => document.querySelector('#c-text')?.value);
    const text = document.querySelector('#c-text');
    const original = text.value;
    text.value = original.replace('0.03', '0.031');
    text.dispatchEvent(new Event('input', { bubbles: true }));
    const api = window.SanctityDemo.request;
    const preview = await api('POST', `/v1/datasets/${ds}/releases/${release}/corrections`, {
      body: JSON.stringify({ path: 'task-nback_bold.json', text: original.replace('52', '60'), reason: 'Synthetic colleague edit for VoiceOver exercise' })
    });
    await api('POST', `/v1/candidates/${preview.candidate.id}/accept`, { headers: { 'Idempotency-Key': 'voiceover-colleague' } });
    const reason = document.querySelector('#c-reason');
    reason.value = 'Reapply my EchoTime correction while preserving the colleague’s FlipAngle';
    reason.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('form button[type=submit]').click();
    await wait(() => [...document.querySelectorAll('a')].some(a => a.textContent === 'Correct the current head'));
    document.documentElement.dataset.voiceoverFixture = 'ready';
  })().catch(error => {
    document.documentElement.dataset.voiceoverFixture = 'failed';
    const message = document.createElement('p'); message.setAttribute('role', 'alert'); message.textContent = error.message; document.body.append(message);
  });
})();
