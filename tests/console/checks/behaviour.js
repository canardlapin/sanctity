// Regression probes for confirmed v0 defects, using real keyboard input.
// usage: node checks/behaviour.js ROUND
const path = require('node:path');
const L = require('../scripts/lib.js');
const DS = 'b7e3c1d2-5a4f-4e8b-9c21-6f0a3d9e8b14', R3 = '9d4b2e61-7a3c-4f18-a5d9-0e6c1b2f8a73';
(async () => {
  const round = L.identifier(process.argv[2]);
  const base = L.url(path.join(L.outputRoot, round, 'index.html')) + '?demo';
  const b = await L.launch(); let bad = 0;
  const result = (name, ok, detail) => { if (!ok) bad++; console.log(`behaviour ${name}: ${ok ? 'PASS' : 'FAIL'}${detail ? ' ' + detail : ''}`); };
  const open = async (hash, vp = { width: 1440, height: 900 }) => {
    const ctx = await b.newContext({ viewport: vp }); const p = await ctx.newPage();
    await p.goto(base + hash); await p.waitForSelector('main, .connect'); await p.waitForTimeout(400); return [ctx, p];
  };
  const active = p => p.evaluate(() => { const a = document.activeElement; return a ? (a.dataset.fk || a.tagName + '.' + a.className) : 'none'; });
  try {
    // 1. No inline style attributes (server CSP is style-src 'self').
    for (const hash of [`#/d/${DS}/r/${R3}?path=sub-02%2Ffunc%2Fsub-02_task-nback_run-1_bold.nii.gz`, `#/d/${DS}/r/${R3}?tab=relationships`, `#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`, `#/d/${DS}/access?release=${R3}`, '#/']) {
      const [ctx, p] = await open(hash); await p.waitForTimeout(500);
      const n = await p.evaluate(() => document.querySelectorAll('[style]').length);
      result('no-inline-style ' + hash.split('?')[0].slice(-24), n === 0, n + ' elements'); await ctx.close();
    }
    // 2. Keyboard: opening a folder and selecting a file keeps focus on the activated item.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}`);
      await p.waitForSelector('.tree .item');
      const dir = p.locator('.tree .item', { hasText: 'sub-01' }).first(); await dir.focus(); await p.keyboard.press('Enter'); await p.waitForTimeout(300);
      const a1 = await active(p); result('focus-after-folder', /sub-01/.test(a1), a1);
      const file = p.locator('.tree .item', { hasText: 'participants.tsv' }).first();
      await file.focus(); await p.keyboard.press('Enter'); await p.waitForTimeout(600);
      const a2 = await active(p); result('focus-after-file', /participants\.tsv/.test(a2), a2); await ctx.close(); }
    // 3. Tabs: ArrowRight moves selection and focus; selected file survives.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=README`);
      await p.waitForSelector('[role=tab]'); await p.locator('[role=tab][aria-selected=true]').focus(); await p.keyboard.press('ArrowRight'); await p.waitForTimeout(500);
      const a = await p.evaluate(() => document.activeElement && document.activeElement.getAttribute('aria-selected') === 'true' && document.activeElement.textContent);
      result('tab-arrow-focus', !!a && /Assessment/.test(a), String(a));
      await p.keyboard.press('ArrowLeft'); await p.waitForTimeout(500);
      result('tab-keeps-file', /path=README/.test(await p.evaluate(() => location.hash)), await p.evaluate(() => location.hash)); await ctx.close(); }
    // 4. A preview for one file must not appear on another file's correction route.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('.diff, .linediff', { timeout: 5000 }).catch(() => {});
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/r/${R3}/correct?path=task-rest_bold.json`); await p.waitForTimeout(800);
      const stale = await p.evaluate(() => !!document.querySelector('.diff, .linediff') && !document.querySelector('#c-text'));
      result('no-stale-preview', !stale); await ctx.close(); }
    // 5. Access never offers delegation (API rejects ManageAccess).
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      const has = await p.evaluate(() => /Manage access/.test(document.querySelector('main').textContent));
      result('no-delegation-offer', !has); await ctx.close(); }
    // 6. Query numbers are sent as exact decimals.
    { const [ctx, p] = await open(`#/d/${DS}/query?release=${R3}`);
      await p.waitForSelector('.cond');
      await p.fill("input[aria-label='Participant condition 1 value']", '123456789012345678901234.5');
      let sent = '';
      await p.exposeFunction('__sent', s => { sent = s; });
      await p.evaluate(() => { const d = window.SanctityDemo; const r = d.request; d.request = (m, u, o) => { if (u === '/v1/selections') window.__sent(typeof o.body === 'string' ? o.body : JSON.stringify(o.body)); return r(m, u, o); }; });
      await p.click('form button[type=submit]'); await p.waitForTimeout(600);
      result('exact-decimal', sent.includes('123456789012345678901234.5'), sent.slice(0, 160)); await ctx.close(); }
    // 7. Mobile: selecting a file brings the inspector into view.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}`, { width: 390, height: 844 });
      await p.locator('.tree .item', { hasText: 'README' }).first().tap?.().catch(() => {});
      await p.locator('.tree .item', { hasText: 'README' }).first().click(); await p.waitForTimeout(900);
      const top = await p.evaluate(() => { const i = document.querySelector('.inspector'); return i ? Math.round(i.getBoundingClientRect().top) : 9999; });
      result('mobile-inspector-visible', top < 600, 'inspector top ' + top); await ctx.close(); }

    // 8. A double-click or double Enter on Revoke must not revoke.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      let deletes = 0;
      await p.evaluate(() => { const d = window.SanctityDemo; const r = d.request; d.request = (m, u, o) => { if (m === 'DELETE') window.__del = (window.__del || 0) + 1; return r(m, u, o); }; });
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.click('form button[type=submit]');
      await p.waitForSelector('table.data tbody tr button.danger');
      await p.locator('table.data tbody tr button.danger').first().dblclick(); await p.waitForTimeout(500);
      deletes = await p.evaluate(() => window.__del || 0);
      result('no-double-click-revoke', deletes === 0, 'deletes ' + deletes);
      await p.keyboard.press('Escape'); await p.waitForTimeout(200);
      const armed = await p.evaluate(() => /Confirm revoke/.test(document.querySelector('main').textContent));
      result('escape-cancels-revoke', !armed); await ctx.close(); }
    // 9. Choosing another sidecar in the dropdown keeps the prepared candidate.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.selectOption('#c-path', 'task-rest_bold.json');
      await p.waitForFunction(() => document.querySelector('#c-text').value.includes('"rest"'));
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.6')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForTimeout(900);
      const ok = await p.evaluate(() => !!document.querySelector('[data-fk="preview-head"]') && /task-rest_bold\.json/.test(document.querySelector('main').textContent));
      result('dropdown-switch-keeps-preview', ok); await ctx.close(); }
    // 10. Access without a release in context never shows a release scope as selected.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/access`); await p.waitForTimeout(500);
      const bad = await p.evaluate(() => { const r = document.querySelector('input[name=scope][value=release]'); return !!r && r.checked; });
      result('access-no-release-scope', !bad); await ctx.close(); }
    // 11. Content-check digest chips stay compact (no form-input sizing).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=assessment`);
      await p.waitForSelector('.check', { state: 'attached' }); await p.evaluate(() => document.querySelectorAll('details').forEach(d => { d.open = true; }));
      const w = await p.evaluate(() => Math.max(...[...document.querySelectorAll('.check .facts > *')].map(e => e.getBoundingClientRect().height)));
      result('compact-digest-chips', w < 30, 'max chip height ' + Math.round(w)); await ctx.close(); }
    // 12. Correction preview at 390: each changed file path is readable (wraps at most twice).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`, { width: 390, height: 844 });
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForTimeout(900);
      const lines = await p.evaluate(() => { const el = [...document.querySelectorAll('main .path-text')].find(e => /sub-01\/func/.test(e.textContent)); if (!el) return 99; const lh = parseFloat(getComputedStyle(el).lineHeight) || 18; return Math.round(el.getBoundingClientRect().height / lh); });
      result('mobile-diff-readable', lines <= 3, 'path lines ' + lines); await ctx.close(); }
    // 13. Back to edit keeps the draft and focuses the editor.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      await p.getByRole('button', { name: 'Back to edit' }).focus(); await p.keyboard.press('Enter'); await p.waitForTimeout(500);
      const st = await p.evaluate(() => ({ id: document.activeElement && document.activeElement.id, kept: (document.querySelector('#c-text') || {}).value || '' }));
      result('back-to-edit-focus', st.id === 'c-text' && st.kept.includes('1.8'), JSON.stringify({ id: st.id, kept: st.kept.includes('1.8') })); await ctx.close(); }
    // 14. Correcting a release that is not the head says so before any preparation.
    { const [ctx, p] = await open(`#/d/${DS}/r/5e2f8a13-9c4b-4d7a-b6e0-2a1f3c8d9e57/correct?path=task-nback_bold.json`);
      await p.waitForTimeout(600);
      const told = await p.evaluate(() => /not the (current )?(dataset )?head/i.test(document.querySelector('main').textContent));
      result('non-head-correction-warned', told); await ctx.close(); }

    // 15. Signing out while a release loads leaves the sign-in page intact.
    { const [ctx, p] = await open('#/');
      await p.evaluate(() => { window.SanctityDemo.latency = 1500; });
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/r/${R3}`); await p.waitForTimeout(300);
      await p.evaluate(() => { location.hash = '#/'; }); await p.waitForTimeout(250);
      const btn = await p.$('[data-fk="signout"]'); if (btn) await btn.click();
      await p.waitForTimeout(1900);
      const leaked = await p.evaluate(() => !!document.querySelector('.tree') );
      result('no-route-race', !leaked); await ctx.close(); }
    // 16. Confirming revoke twice sends one DELETE; armed Cancel sits where Revoke was.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      await p.evaluate(() => { const d = window.SanctityDemo; const r = d.request; d.request = (m, u, o) => { if (m === 'DELETE') window.__del = (window.__del || 0) + 1; return r(m, u, o); }; });
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.click('form button[type=submit]');
      await p.waitForSelector('table.data tbody tr button.danger');
      await p.locator('table.data tbody tr button.danger').first().click(); await p.waitForTimeout(800);
      await p.evaluate(() => { window.SanctityDemo.latency = 800; });
      await p.locator('button.confirm').first().dblclick(); await p.waitForTimeout(1400);
      const n = await p.evaluate(() => window.__del || 0);
      result('single-revoke-request', n === 1, 'deletes ' + n);
      const kept = await p.evaluate(() => /Revoked/.test(document.querySelector('main').textContent));
      result('revoked-grant-recorded', kept); await ctx.close(); }
    // 17. Enter in "Add release id" adds the release instead of freezing.
    { const [ctx, p] = await open(`#/d/${DS}/query?release=${R3}`);
      await p.fill("input[aria-label='Add release id']", '5e2f8a13-9c4b-4d7a-b6e0-2a1f3c8d9e57'); await p.press("input[aria-label='Add release id']", 'Enter'); await p.waitForTimeout(500);
      const st = await p.evaluate(() => ({ h: location.hash, added: /5e2f8a13-9c4b/.test(document.querySelector('main').textContent) }));
      result('enter-adds-release', st.added && /query/.test(st.h), JSON.stringify(st)); await ctx.close(); }
    // 18. Pager keeps keyboard focus on a pager control (two releases, no conditions: 48 hits).
    { const [ctx, p] = await open(`#/d/${DS}/query?release=${R3}`);
      await p.fill("input[aria-label='Add release id']", '5e2f8a13-9c4b-4d7a-b6e0-2a1f3c8d9e57'); await p.getByRole('button', { name: 'Add', exact: true }).click(); await p.waitForTimeout(200);
      for (const n of ['Remove participant condition 1', 'Remove file condition 1']) { await p.getByRole('button', { name: n }).click(); await p.waitForTimeout(150); }
      await p.click('form button[type=submit]'); await p.waitForTimeout(800);
      const hasNext = await p.$('[data-fk="pager-next"]');
      if (!hasNext) { result('pager-focus', false, 'no pager'); }
      else { await p.focus('[data-fk="pager-next"]'); await p.keyboard.press('Enter'); await p.waitForTimeout(700);
        const a = await p.evaluate(() => document.activeElement && document.activeElement.dataset.fk || document.activeElement.tagName);
        result('pager-focus', /pager/.test(a), a); }
      await ctx.close(); }
    // 19. Assessment at 390 stays scannable: page height under 3000 px.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=assessment`, { width: 390, height: 844 });
      await p.waitForSelector('.finding'); const ht = await p.evaluate(() => document.documentElement.scrollHeight);
      result('assessment-compact-390', ht < 3000, 'height ' + ht); await ctx.close(); }
    // 20. Receipt verifies the new release's identity against the preview on screen.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="accept"]');
      await p.click('[data-fk="accept"]'); await p.waitForTimeout(1200);
      const ok = await p.evaluate(() => (document.querySelector('main').textContent.match(/Matches preview/g) || []).length);
      result('receipt-identity-verified', ok >= 3, 'matches ' + ok); await ctx.close(); }
    // 21. A blocked candidate marks the offending sidecar line.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '0')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const flagged = await p.evaluate(() => !!document.querySelector('.linediff .flagged'));
      result('blocked-line-flagged', flagged); await ctx.close(); }
    // 22. Resolved/new findings say which edited input caused re-evaluation.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const why = await p.evaluate(() => [...document.querySelectorAll('.finding .why')].length);
      result('finding-cause-shown', why >= 2, 'why lines ' + why); await ctx.close(); }
    // 23. The BOLD inspector shows the header check that corroborates RepetitionTime.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-02%2Ffunc%2Fsub-02_task-nback_run-1_bold.nii.gz`);
      await p.waitForSelector('.fieldrow'); await p.waitForTimeout(300);
      const has = await p.evaluate(() => /temporalSpacingSeconds/.test(document.querySelector('.inspector').textContent));
      result('inspector-header-check', has); await ctx.close(); }

    // 24. A check that can no longer evaluate never shows its old finding as Resolved.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '0')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const bad = await p.evaluate(() => [...document.querySelectorAll('.finding')].some(f => /EventsOutsideWindow/.test(f.textContent) && /Resolved/i.test(f.textContent)));
      result('no-false-resolved', !bad); await ctx.close(); }
    // 25. A grant whose request finishes after sign-out is not shown to the next principal.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64');
      await p.evaluate(() => { window.SanctityDemo.latency = 900; });
      await p.click('form button[type=submit]'); await p.waitForTimeout(150);
      await p.click('[data-fk="signout"]'); await p.waitForTimeout(100);
      await p.evaluate(() => { window.SanctityDemo.principal = '0d3c5b7a-2e4f-4a61-8b9c-7e1f2a3b4c5d'; window.SanctityDemo.latency = 60; });
      await p.waitForTimeout(1200);
      await p.click('form button[type=submit]').catch(() => {}); await p.waitForTimeout(400);
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/access?release=${R3}`); await p.waitForTimeout(600);
      const st = await p.evaluate(() => ({ rows: document.querySelectorAll('table.data tbody tr').length, nullKey: sessionStorage.getItem('sanctity.grants.null') }));
      result('no-grant-leak', st.rows === 0 && !st.nullKey, JSON.stringify(st)); await ctx.close(); }
    // 26. Check inputs name their file visibly (not only in a tooltip).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=assessment`);
      await p.waitForSelector('.check', { state: 'attached' }); await p.evaluate(() => document.querySelectorAll('details').forEach(d => { d.open = true; }));
      const ok = await p.evaluate(() => /task-nback_bold\.json/.test([...document.querySelectorAll('.check .fact.digest')].map(e => e.innerText).join(' ')));
      result('check-inputs-named', ok); await ctx.close(); }
    // 27. Adding a sidecar that fixes a TR mismatch shows it Resolved, even though the same check still
    //     reports an unrelated transform warning (live backend: v3c showed "Superseded").
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-04%2Ffunc%2Fsub-04_task-rest_bold.nii.gz`);
      await p.waitForSelector('.inspector-head');
      const add = await p.locator('.inspector-head a', { hasText: 'Add sidecar' }).count();
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/r/${R3}/correct?path=${encodeURIComponent('sub-04/func/sub-04_task-rest_bold.json')}`);
      await p.waitForSelector('#c-text'); await p.waitForTimeout(200);
      await p.fill('#c-text', '{ "RepetitionTime": 2.0 }\n'); await p.fill('#c-reason', 'probe: header spacing is 2.0 s');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]', { timeout: 5000 }).catch(() => {});
      const tag = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /MriRepetitionTimeMismatch/.test(x.textContent) && /sub-04_task-rest/.test(x.textContent)); return f ? (f.querySelector('.tag.delta') || {}).textContent || 'no tag' : 'absent'; });
      result('add-sidecar-resolves', add === 1 && /resolved/i.test(tag), 'add link ' + add + ', tag ' + tag); await ctx.close(); }
    // 28. A NEW finding names the facts that moved, not only the input that changed.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const why = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /\bNew\b/i.test((x.querySelector('.tag.delta') || {}).textContent || '')); return f ? (f.querySelector('.why') || {}).textContent || '' : 'no new finding'; });
      result('new-finding-moved-facts', /Changed: .*RepetitionTime.*1\.5 → 1\.8/.test(why), why.slice(0, 160)); await ctx.close(); }
    // 29. Per-row findings with one meaning collapse into one entry (live: EventsOutsideWindow per row).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=assessment`);
      await p.waitForSelector('.finding');
      const n = await p.evaluate(() => [...document.querySelectorAll('.finding')].filter(f => /EventsOutsideWindow/.test(f.textContent)).length);
      result('row-findings-grouped', n === 1, n + ' entries'); await ctx.close(); }
    // 30. Fieldmap links read run → fieldmap, the backend's direction.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=relationships`);
      await p.waitForSelector('.rel');
      const st = await p.evaluate(() => { const sec = [...document.querySelectorAll('section')].find(s => /Fieldmaps by run/.test(s.textContent)); const r = sec && sec.querySelector('.rel');
        return r ? [...r.querySelectorAll('.endpoint')].map(e => e.textContent).join(' => ') : 'missing'; });
      result('fieldmap-direction', /\/func\/.* => .*\/fmap\//.test(st), st); await ctx.close(); }
    // 31. A grant that lands after Access → Query → Access appears without a reload.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64');
      await p.evaluate(() => { window.SanctityDemo.latency = 1200; });
      await p.click('form button[type=submit]'); await p.waitForTimeout(100);
      await p.evaluate(h => { location.hash = h; }, `#/d/${DS}/query?release=${R3}`); await p.waitForTimeout(200);
      await p.goBack(); await p.waitForTimeout(1600);
      const rows = await p.evaluate(() => document.querySelectorAll('table.data tbody tr').length);
      result('access-round-trip-fresh', rows === 1, rows + ' rows'); await ctx.close(); }
    // 32. At 390 a selected file's inspector starts near the top; the tree folds to one line.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-02%2Ffunc%2Fsub-02_task-nback_run-1_bold.nii.gz`, { width: 390, height: 844 });
      await p.waitForSelector('.inspector .fieldrow');
      const st = await p.evaluate(() => ({ fold: (e => !!e && e.getClientRects().length > 0)(document.querySelector('.tree-fold-bar')) && !(e => !!e && e.getClientRects().length > 0)(document.querySelector('#release-tree')), gap: Math.round(document.querySelector('.inspector').getBoundingClientRect().top - document.querySelector('.tabs').getBoundingClientRect().bottom + scrollY * 0) }));
      result('mobile-tree-folds', st.fold && st.gap < 140, JSON.stringify(st)); await ctx.close(); }
    // 33. A new sidecar path that already exists, or escapes the dataset, is refused before any request.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct`);
      await p.waitForSelector('#c-path'); await p.selectOption('#c-path', '\u0000new'); await p.waitForSelector('#c-new');
      await p.fill('#c-new', '../escape.json'); await p.fill('#c-text', '{}'); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForTimeout(300);
      const msg = await p.evaluate(() => (document.querySelector('#c-errors') || {}).textContent || '');
      const inv = await p.evaluate(() => document.querySelector('#c-new').getAttribute('aria-invalid'));
      result('new-sidecar-path-validated', /relative to the dataset root/.test(msg) && inv === 'true', msg.slice(0, 90)); await ctx.close(); }
    // 34. Editing the prefilled new-sidecar path keeps the draft through to the preview (v4d critic: silently reset).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=${encodeURIComponent('sub-04/func/sub-04_task-rest_bold.json')}`);
      await p.waitForSelector('#c-new');
      await p.fill('#c-new', 'sub-04/sub-04_task-rest_bold.json'); await p.fill('#c-text', '{ "RepetitionTime": 2.0 }\n'); await p.fill('#c-reason', 'probe: subject level');
      await p.click('form button[type=submit]'); const shown = await p.waitForSelector('[data-fk="preview-head"]', { timeout: 5000 }).then(() => true, () => false);
      const st = await p.evaluate(() => ({ hash: decodeURIComponent(location.hash), h1: (document.querySelector('h1') || {}).textContent }));
      result('edited-new-path-kept', shown && /path=sub-04\/sub-04_task-rest_bold\.json$/.test(st.hash), JSON.stringify(st)); await ctx.close(); }
    // v4f: fixes from the v4d fresh reviews.
    const R2 = '5e2f8a13-9c4b-4d7a-b6e0-2a1f3c8d9e57';
    const newSidecar = async (p, path, text) => {
      await p.waitForSelector('#c-path'); await p.selectOption('#c-path', '\u0000new'); await p.waitForSelector('#c-new');
      await p.fill('#c-new', path); await p.fill('#c-text', text); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]');
    };
    // 35. Grouped row findings name the rows they stand for.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?tab=assessment`);
      await p.waitForSelector('.finding');
      const msg = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /EventsOutsideWindow/.test(x.textContent)); return f ? f.innerText : 'absent'; });
      result('grouped-rows-listed', /rows \d+, \d+/.test(msg) && !/row …/.test(msg), msg.replace(/\s+/g, ' ').slice(0, 160)); await ctx.close(); }
    // 36. A TR mismatch shows the two compared values next to the finding.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-04%2Ffunc%2Fsub-04_task-rest_bold.nii.gz`);
      await p.waitForSelector('.inspector .finding');
      const t = await p.evaluate(() => (document.querySelector('.inspector .finding .keyfacts') || {}).innerText || 'none');
      result('tr-mismatch-key-facts', /Authored RepetitionTime/.test(t) && /Header volume spacing/.test(t), t.replace(/\s+/g, ' ')); await ctx.close(); }
    // 37. A subject-level sidecar applies through BIDS inheritance in the demo, as on the backend.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct`);
      await newSidecar(p, 'sub-04/sub-04_task-rest_bold.json', '{ "RepetitionTime": 2.0 }\n');
      await p.waitForSelector('[data-fk="preview-head"]', { timeout: 5000 }).catch(() => {});
      const st = await p.evaluate(() => ({ files: [...document.querySelectorAll('.change-path')].map(e => e.textContent), tag: (() => { const f = [...document.querySelectorAll('.finding')].find(x => /MriRepetitionTimeMismatch/.test(x.textContent) && /sub-04_task-rest/.test(x.textContent)); return f ? (f.querySelector('.tag.delta') || {}).textContent : 'absent'; })() }));
      result('subject-sidecar-inherits', st.files.some(f => /sub-04_task-rest_bold\.nii/.test(f)) && /resolved/i.test(st.tag), JSON.stringify(st)); await ctx.close(); }
    // 38. A new sidecar that changes nothing says so.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct`);
      await newSidecar(p, 'sub-04/sub-04_task-other_bold.json', '{ "RepetitionTime": 2.0 }\n');
      await p.waitForSelector('[data-fk="preview-head"]', { timeout: 5000 }).catch(() => {});
      const t = await p.evaluate(() => (document.querySelector('.notice.warn') || {}).textContent || 'none');
      result('inert-sidecar-warns', /changes no file/.test(t), t.slice(0, 90)); await ctx.close(); }
    // 39. A path that is only ".json" is refused before any request.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct`);
      await newSidecar(p, 'sub-04/.json', '{}'); await p.waitForTimeout(300);
      const st = await p.evaluate(() => ({ msg: (document.querySelector('#c-errors') || {}).textContent, inv: document.querySelector('#c-new')?.getAttribute('aria-invalid'), head: !!document.querySelector('[data-fk="preview-head"]') }));
      result('dot-json-refused', /Name the sidecar/.test(st.msg) && st.inv === 'true' && !st.head, JSON.stringify(st).slice(0, 120)); await ctx.close(); }
    // 40. At 390 a grant row's Revoke is on screen.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`, { width: 390, height: 844 });
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.click('form button[type=submit]');
      await p.waitForSelector('table.data tbody tr');
      const st = await p.evaluate(() => { const b = document.querySelector('table.data tbody button.danger'); const r = b.getBoundingClientRect(); return { left: Math.round(r.left), right: Math.round(r.right), vw: innerWidth, label: b.getAttribute('aria-label'), overflow: document.documentElement.scrollWidth - innerWidth }; });
      result('grant-revoke-visible-mobile', st.right <= st.vw - 8 && st.left >= 8 && /Revoke grant/.test(st.label) && st.overflow === 0, JSON.stringify(st)); await ctx.close(); }
    // 41. At 390 selection rows keep paths readable and drop an all-empty Session column.
    { const [ctx, p] = await open(`#/d/${DS}/query?release=${R3}`, { width: 390, height: 844 });
      await p.fill('[data-fk="Participant0:value"]', '30'); await p.click('button:has-text("Freeze selection")');
      await p.waitForSelector('table.data tbody tr');
      const st = await p.evaluate(() => ({ session: [...document.querySelectorAll('table.data th')].some(t => /Session/.test(t.textContent)), tallest: Math.max(...[...document.querySelectorAll('table.data tbody a')].map(a => Math.round(a.getBoundingClientRect().height))), overflow: document.documentElement.scrollWidth - innerWidth }));
      result('selection-readable-mobile', !st.session && st.tallest <= 60 && st.overflow === 0, JSON.stringify(st)); await ctx.close(); }
    // 42. The tree fold follows the viewport without navigating.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-02%2Ffunc%2Fsub-02_task-nback_run-1_bold.nii.gz`, { width: 390, height: 844 });
      await p.waitForSelector('.inspector .fieldrow');
      const folded = await p.locator('.tree-fold-bar').isVisible() && !(await p.locator('#release-tree').isVisible()) ? 1 : 0;
      await p.setViewportSize({ width: 1440, height: 900 }); await p.waitForTimeout(500);
      const wide = await p.locator('.tree-fold-bar').isVisible() || !(await p.locator('#release-tree').isVisible()) ? 1 : 0;
      result('fold-follows-viewport', folded === 1 && wide === 0, folded + ' → ' + wide); await ctx.close(); }
    // 43. "Choose at least one action" is tied to the Actions group and moves focus there.
    { const [ctx, p] = await open(`#/d/${DS}/access?release=${R3}`);
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.uncheck('#act-ReadMetadata'); await p.click('form button[type=submit]'); await p.waitForTimeout(200);
      const st = await p.evaluate(() => ({ desc: [...document.querySelectorAll('fieldset')].map(f => f.getAttribute('aria-describedby')).filter(Boolean), focus: document.activeElement.id }));
      result('actions-error-described', st.desc.includes('g-act-err') && /^act-/.test(st.focus), JSON.stringify(st)); await ctx.close(); }
    // 44. Moving a draft to the current head keeps the edited content and says where it came from.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R2}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      const edited = (await p.inputValue('#c-text')).replace('1.5', '1.7'); await p.fill('#c-text', edited);
      await p.click('a:has-text("Correct the current head")'); await p.waitForTimeout(600);
      const st = await p.evaluate(() => ({ text: document.querySelector('#c-text').value, note: /is restored/.test(document.body.innerText) }));
      result('head-keeps-draft', st.text === edited && st.note, JSON.stringify({ note: st.note, kept: st.text === edited })); await ctx.close(); }
    // v4g: fixes from the v4f fresh reviews.
    // 45. A carried draft never reverts a change accepted on the head meanwhile (lost update).
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      const mine = (await p.inputValue('#c-text')).replace('0.03', '0.031'); await p.fill('#c-text', mine);
      // A colleague accepts FlipAngle 60 on the same file first.
      await p.evaluate(async ([ds, r]) => { const api = window.SanctityDemo.request;
        const t = await api('GET', `/v1/datasets/${ds}/releases/${r}/file?path=task-nback_bold.json`, { raw: true }).then(x => x.text());
        const pv = await api('POST', `/v1/datasets/${ds}/releases/${r}/corrections`, { body: JSON.stringify({ path: 'task-nback_bold.json', text: t.replace('52', '60'), reason: 'colleague' }) });
        await api('POST', `/v1/candidates/${pv.candidate.id}/accept`, { headers: { 'Idempotency-Key': 'colleague' } }); }, [DS, R3]);
      await p.fill('#c-reason', 'probe'); await p.click('form button[type=submit]'); await p.waitForTimeout(500);
      await p.click('a:has-text("Correct the current head")'); await p.waitForTimeout(800);
      const st = await p.evaluate(() => ({ text: document.querySelector('#c-text').value, note: /changed after your edit/.test(document.body.innerText), earlier: !!document.querySelector('details.draft-diff') }));
      result('carried-draft-no-lost-update', /60/.test(st.text) && !/0\.031/.test(st.text) && st.note && st.earlier, JSON.stringify({ ...st, text: st.text.replace(/\s+/g, ' ').slice(0, 80) })); await ctx.close(); }
    // 46. A TR mismatch on several files still shows its compared values.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.8')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const t = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /MriRepetitionTimeMismatch/.test(x.textContent) && /occurrences/.test(x.textContent)); return f ? [...f.querySelectorAll('.keyfacts')].map(e => e.innerText).join(' | ') : 'no group'; });
      result('grouped-mismatch-key-facts', /Authored RepetitionTime/.test(t) && /1\.8/.test(t), t.replace(/\s+/g, ' ').slice(0, 140)); await ctx.close(); }
    // 47. A blocked preview leads with what blocks it.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '-1')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const st = await p.evaluate(() => { const hs = [...document.querySelectorAll('.decision h3.overline')].map(e => e.textContent); const first = document.querySelector('.decision .finding'); return { hs, first: first ? first.querySelector('.sev-label').textContent : null }; });
      result('blocked-preview-error-first', /^Blocking/.test(st.hs[0] || '') && st.first === 'Blocks acceptance', JSON.stringify(st)); await ctx.close(); }
    // 48. Several files' row lists keep the message readable.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`);
      await p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('1.5', '1.4')); await p.fill('#c-reason', 'probe');
      await p.click('form button[type=submit]'); await p.waitForSelector('[data-fk="preview-head"]');
      const m = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /EventsOutsideWindow/.test(x.textContent) && /listed by file/.test(x.textContent)); return f ? f.querySelector('.msg').textContent : 'none'; });
      result('row-lists-readable', /listed by file column/.test(m), m.slice(-60)); await ctx.close(); }
    // 49. New-sidecar paths through an existing file, or with spaces, are refused.
    for (const bad of ['task-nback_bold.json/x.json', 'sub-03/ .json']) {
      const [ctx, p] = await open(`#/d/${DS}/r/${R3}/correct`);
      await newSidecar(p, bad, '{}'); await p.waitForTimeout(300);
      const st = await p.evaluate(() => ({ inv: document.querySelector('#c-new')?.getAttribute('aria-invalid'), head: !!document.querySelector('[data-fk="preview-head"]') }));
      result('odd-path-refused ' + bad, st.inv === 'true' && !st.head, JSON.stringify(st)); await ctx.close(); }
    // 50. Crossing the fold width keeps focus, the filter text and the scroll position's subject.
    { const [ctx, p] = await open(`#/d/${DS}/r/${R3}?path=sub-02%2Ffunc%2Fsub-02_task-nback_run-1_bold.nii.gz`);
      await p.waitForSelector('.inspector .fieldrow'); await p.focus('#tree-filter'); await p.keyboard.type('nback');
      await p.setViewportSize({ width: 390, height: 844 }); await p.waitForTimeout(300); await p.setViewportSize({ width: 1440, height: 900 }); await p.waitForTimeout(300);
      const st = await p.evaluate(() => ({ focus: document.activeElement.id, value: document.querySelector('#tree-filter').value }));
      result('resize-keeps-focus', st.focus === 'tree-filter' && st.value === 'nback', JSON.stringify(st)); await ctx.close(); }

    // Post-v4g: the backend corrects event/behavioural tables and DWI gradients, and access changes can end
    // with an unconfirmed policy outcome. Each probe records exactly one result, even when an older build throws.
    const guarded = async (name, body) => {
      const opened = [];
      const o = async (...a) => { const r = await open(...a); opened.push(r[0]); return r; };
      let recorded = false;
      const done = (ok, detail) => { recorded = true; result(name, ok, detail); };
      try { await body(o, done); } catch (e) { if (!recorded) done(false, 'threw: ' + String(e.message).split('\n')[0].slice(0, 120)); }
      finally { for (const c of opened) await c.close().catch(() => {}); }
      if (!recorded) result(name, false, 'no result');
    };
    const EV3 = 'sub-03/func/sub-03_task-nback_run-1_events.tsv', BEH = 'sub-01/beh/sub-01_task-recall_beh.tsv', BVAL = 'sub-02/dwi/sub-02_dwi.bval';
    const correctAt = (r, file) => `#/d/${DS}/r/${r}/correct?path=${encodeURIComponent(file)}`;
    const loaded = p => p.waitForFunction(() => document.querySelector('#c-text') && document.querySelector('#c-text').value, null, { timeout: 5000 });
    // Rewrite file lines (1-based, header = row 1) in the editor, as a curator would.
    const editRows = async (p, edits) => {
      const lines = (await p.inputValue('#c-text')).split('\n');
      for (const [row, f] of Object.entries(edits)) lines[row - 1] = f(lines[row - 1]);
      await p.fill('#c-text', lines.join('\n'));
    };
    const prepare = async (p, reason = 'probe') => {
      await p.fill('#c-reason', reason); await p.click('form button[type=submit]');
      await p.waitForSelector('[data-fk="preview-head"]', { timeout: 5000 });
    };
    // 51. Tables and gradients are offered for correction, grouped by kind.
    await guarded('companion-correction-offered', async (o, done) => {
      const [, p] = await o(`#/d/${DS}/r/${R3}?path=${encodeURIComponent(EV3)}`);
      await p.waitForSelector('.inspector-head', { timeout: 5000 });
      const link = await p.locator('.inspector-head a', { hasText: 'Correct' }).count();
      await p.click('.inspector-head a:has-text("Correct")'); await loaded(p);
      const st = await p.evaluate(() => ({ groups: [...document.querySelectorAll('#c-path optgroup')].map(g => g.label), selected: document.querySelector('#c-path').value }));
      done(link === 1 && st.selected.endsWith('_events.tsv') && ['Sidecars', 'Event and behavioural tables', 'DWI gradients'].every(g => st.groups.includes(g)), JSON.stringify({ link, ...st }));
    });
    // 52. In a table, Tab types a column separator; Escape then Tab leaves (no keyboard trap).
    await guarded('table-tab-separator', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3)); await loaded(p);
      await p.focus('#c-text'); await p.evaluate(() => { const t = document.querySelector('#c-text'); t.setSelectionRange(t.value.length, t.value.length); }); await p.keyboard.type('412.0'); await p.keyboard.press('Tab');
      const typed = await p.evaluate(() => ({ tail: document.querySelector('#c-text').value.slice(-6), focus: document.activeElement.id }));
      await p.keyboard.press('Escape'); await p.keyboard.press('Tab');
      const left = await p.evaluate(() => document.activeElement.id);
      done(typed.tail === '412.0\t' && typed.focus === 'c-text' && left !== 'c-text', JSON.stringify({ ...typed, left }));
    });
    // 53. A row whose columns were separated by spaces is refused before any request, naming the row.
    await guarded('table-width-refused', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3)); await loaded(p);
      let posts = 0; await p.exposeFunction('__post', () => { posts++; });
      await p.evaluate(() => { const d = window.SanctityDemo, r = d.request; d.request = (m, u, x) => { if (m === 'POST') window.__post(); return r(m, u, x); }; });
      await editRows(p, { 7: l => l.replace(/\t/g, '   ') });
      await p.fill('#c-reason', 'probe'); await p.click('form button[type=submit]'); await p.waitForTimeout(300);
      const st = await p.evaluate(() => ({ msg: (document.querySelector('#c-errors') || {}).textContent, inv: document.querySelector('#c-text').getAttribute('aria-invalid') }));
      done(/Row 7 has 1 column/.test(st.msg) && st.inv === 'true' && posts === 0, JSON.stringify({ ...st, posts }).slice(0, 160));
    });
    // 54. A table correction leads with its rows, resolves the timing warning, and advances through acceptance.
    await guarded('table-correction-accepted', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3)); await loaded(p);
      await editRows(p, { 50: l => l.replace('405.75', '399.0'), 51: l => l.replace('406.5', '400.0') });
      await prepare(p, 'probe: log sheet gives the last two onsets');
      const st = await p.evaluate(() => {
        const first = document.querySelector('.split > .stack h2'), focus = document.activeElement;
        const rows = [...document.querySelectorAll('.tdiff tbody tr:not(.gap)')].map(r => r.querySelector('th').textContent);
        const tag = (() => { const f = [...document.querySelectorAll('.finding')].find(x => /EventsOutsideWindow/.test(x.textContent) && /sub-03/.test(x.textContent)); return f ? (f.querySelector('.tag.delta') || {}).textContent : 'absent'; })();
        return { first: first && first.textContent, focused: focus && focus.dataset.fk, rows, chg: document.querySelectorAll('.tdiff td.chg').length, spoken: [...document.querySelectorAll('.tdiff td.chg')].every(td => / changed$/.test(td.textContent)),
          gap: !!document.querySelector('.tdiff tr.gap'), meta: /Effective metadata changes/.test(document.querySelector('main').textContent), tag,
          accept: !document.querySelector('[data-fk="accept"]').disabled };
      });
      await p.click('[data-fk="accept"]'); await p.waitForSelector('[data-fk="receipt-head"]', { timeout: 5000 }); await p.waitForTimeout(400);
      const end = await p.evaluate(() => ({ matches: (document.querySelector('main').textContent.match(/Matches preview/g) || []).length, step: (document.querySelector('.steps [aria-current="step"]') || {}).textContent }));
      done(st.first === 'Table change' && st.focused === 'preview-head' && st.rows.join() === '49,50,50,51,51' && st.chg === 4 && st.spoken && st.gap && !st.meta && /resolved/i.test(st.tag) && st.accept && end.matches >= 3 && /Accepted/.test(end.step),
        JSON.stringify({ ...st, ...end }));
    });
    // 55. A blocking table error names its row, and the row is marked in the change.
    await guarded('table-blocked-row-marked', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3)); await loaded(p);
      await editRows(p, { 10: l => l.replace(/^[^\t]+/, 'abc') }); await prepare(p);
      const st = await p.evaluate(() => ({ seal: (document.querySelector('.seal') || {}).textContent, flagged: [...document.querySelectorAll('.tdiff tr.flagged th')].map(e => e.textContent),
        sr: (document.querySelector('.tdiff tr.flagged .sr-only') || {}).textContent, first: (document.querySelector('.decision .finding .code') || {}).textContent,
        accept: document.querySelector('[data-fk="accept"]').disabled, hint: /Edit the table/.test(document.querySelector('.decision').textContent),
        why: (document.querySelector('.decision .finding') || {}).textContent || '' }));
      // Facts a blocked table no longer yields are grouped, not each listed as "→ absent".
      done(/Not admissible/.test(st.seal) && st.flagged.join() === '10' && /blocked by a finding/.test(st.sr) && /EventsNumber/.test(st.first) && st.accept && st.hint
        && /No longer recorded: comparisonEndSeconds/.test(st.why) && !/absent/.test(st.why), JSON.stringify({ ...st, why: st.why.slice(0, 200) }));
    });
    // 56. A behavioural table's response times are re-checked.
    await guarded('beh-correction-checked', async (o, done) => {
      const [, p] = await o(correctAt(R3, BEH)); await loaded(p);
      await editRows(p, { 3: l => l.replace(/[^\t]+$/, 'fast') }); await prepare(p);
      const st = await p.evaluate(() => ({ first: (document.querySelector('.decision .finding .code') || {}).textContent, flagged: [...document.querySelectorAll('.tdiff tr.flagged th')].map(e => e.textContent) }));
      done(/BehaviouralResponseTime/.test(st.first) && st.flagged.join() === '3', JSON.stringify(st));
    });
    // 57. A gradient correction is shown as a gradient change and its count is re-checked.
    await guarded('gradient-correction-checked', async (o, done) => {
      const [, p] = await o(correctAt(R3, BVAL)); await loaded(p);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace(/ 1000\n$/, '\n')); await prepare(p);
      const st = await p.evaluate(() => ({ head: (document.querySelector('[data-fk="preview-head"]') || {}).textContent, first: (document.querySelector('.decision .finding .code') || {}).textContent,
        msg: (document.querySelector('.decision .finding .msg') || {}).textContent }));
      done(st.head === 'Gradient change' && /DwiBvalCount/.test(st.first) && /64 b-values for 65/.test(st.msg), JSON.stringify(st));
    });
    // 58. A carried table edit never reverts a row a colleague changed meanwhile (lost update).
    await guarded('table-carried-no-lost-update', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3)); await loaded(p);
      await editRows(p, { 51: l => l.replace('406.5', '400.0') });
      await p.evaluate(async ([ds, r, f]) => { const api = window.SanctityDemo.request;
        const t = await api('GET', `/v1/datasets/${ds}/releases/${r}/file?path=${encodeURIComponent(f)}`, { raw: true }).then(x => x.text());
        const pv = await api('POST', `/v1/datasets/${ds}/releases/${r}/corrections`, { body: JSON.stringify({ path: f, text: t.replace('405.75', '399.0'), reason: 'colleague' }) });
        await api('POST', `/v1/candidates/${pv.candidate.id}/accept`, { headers: { 'Idempotency-Key': 'colleague-table' } }); }, [DS, R3, EV3]);
      await prepare(p); await p.click('[data-fk="accept"]');
      await p.click('a:has-text("Correct the current head")', { timeout: 5000 }); await p.waitForTimeout(800);
      const st = await p.evaluate(() => ({ text: document.querySelector('#c-text').value, note: /changed after your edit/.test(document.body.innerText), earlier: !!document.querySelector('details.draft-diff') }));
      done(/\n399\.0\t/.test(st.text) && /\n406\.5\t/.test(st.text) && st.note && st.earlier, JSON.stringify({ note: st.note, earlier: st.earlier, colleague: /\n399\.0\t/.test(st.text), mine: /\n400\.0\t/.test(st.text) }));
    });
    // 59. At 390 a table change scrolls inside its card, never the page; it reads before the decision, in focus order, and its focused heading is visible.
    await guarded('table-preview-mobile', async (o, done) => {
      const [, p] = await o(correctAt(R3, EV3), { width: 390, height: 844 }); await loaded(p);
      await editRows(p, { 50: l => l.replace('405.75', '399.0') }); await prepare(p);
      const st = await p.evaluate(() => { const y = s => document.querySelector(s).getBoundingClientRect().top, f = document.activeElement;
        return { overflow: document.documentElement.scrollWidth - innerWidth, table: !!document.querySelector('.tdiff'), focused: f.dataset.fk,
          changeFirst: y('.split > .stack') < y('.decision'), seen: f.getBoundingClientRect().top >= document.querySelector('.topbar').getBoundingClientRect().bottom && f.getBoundingClientRect().bottom <= innerHeight }; });
      done(st.table && st.overflow === 0 && st.focused === 'preview-head' && st.changeFirst && st.seen, JSON.stringify(st));
    });
    // 60. A revocation with an unknown policy outcome is never reported as "not revoked" or as revoked.
    const OP = '4f6c2a1e-8b3d-4e7a-9c05-2d1e7f9a3b68';
    await guarded('policy-unknown-revoke', async (o, done) => {
      const [, p] = await o(`#/d/${DS}/access?release=${R3}`);
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.click('form button[type=submit]');
      await p.waitForSelector('table.data tbody tr button.danger', { timeout: 5000 });
      await p.evaluate(op => { const d = window.SanctityDemo, r = d.request; d.request = (m, u, x) => m === 'DELETE' ? Promise.reject({ status: 503, code: 'policy_outcome_unknown:' + op }) : r(m, u, x); }, OP);
      await p.locator('table.data tbody tr button.danger').first().click(); await p.waitForTimeout(300);
      await p.locator('button.confirm').first().click(); await p.waitForTimeout(500);
      const st = await p.evaluate(() => { const row = document.querySelector('table.data tbody tr');
        return { alert: (document.querySelector('main [role=alert]') || {}).textContent || '', table: row ? row.innerText.replace(/\s+/g, ' ') : 'none',
          open: !!(row && row.querySelector('button.danger')) && !/Revoked/.test(row.textContent) }; });
      done(/revocation not confirmed/.test(st.alert) && !/was not revoked/.test(st.alert) && st.alert.includes(OP) && /Outcome unknown/.test(st.alert) && st.open, JSON.stringify({ ...st, alert: st.alert.slice(0, 90) }));
    });
    // 61. A grant whose policy acknowledgement is pending says so, names the operation, and lists no grant.
    await guarded('policy-pending-grant', async (o, done) => {
      const [, p] = await o(`#/d/${DS}/access?release=${R3}`);
      await p.evaluate(op => { const d = window.SanctityDemo, r = d.request; d.request = (m, u, x) => m === 'POST' && /grants$/.test(u) ? Promise.reject({ status: 503, code: 'policy_pending:' + op }) : r(m, u, x); }, OP);
      await p.fill('#g-recipient', '6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64'); await p.click('form button[type=submit]'); await p.waitForTimeout(500);
      const st = await p.evaluate(() => ({ alert: (document.querySelector('main [role=alert]') || {}).textContent || '', rows: document.querySelectorAll('table.data tbody tr').length }));
      done(/Not confirmed/.test(st.alert) && st.alert.includes(OP) && /Do not repeat/.test(st.alert) && st.rows === 0, JSON.stringify({ ...st, alert: st.alert.slice(0, 120) }));
    });
    // 62. Fix is offered where one correctable document can address a finding, and nowhere else.
    const assessAt = `#/d/${DS}/r/${R3}?tab=assessment`;
    await guarded('fix-offered-where-editable', async (o, done) => {
      const [, p] = await o(assessAt); await p.waitForSelector('.finding', { timeout: 5000 });
      const st = await p.evaluate(() => ({ fixes: [...document.querySelectorAll('button.fix')].map(b => b.getAttribute('aria-label')),
        transform: [...document.querySelectorAll('.finding')].filter(f => /MriTransformUnavailable/.test(f.textContent)).map(f => !!f.querySelector('button.fix')) }));
      done(st.fixes.length === 2 && st.fixes.some(x => /edit sub-03\/func\/sub-03_task-nback_run-1_events\.tsv$/.test(x)) && st.fixes.some(x => /add sub-04\/func\/sub-04_task-rest_bold\.json$/.test(x))
        && st.transform.length > 0 && st.transform.every(x => !x), JSON.stringify(st));
    });
    // 63. A TR mismatch fix adds the run's sidecar with the header spacing, states the authored value, and resolves the finding.
    await guarded('fix-tr-suggests-sidecar', async (o, done) => {
      const [, p] = await o(assessAt); await p.waitForSelector('button.fix', { timeout: 5000 });
      await p.click('button.fix[aria-label$="sub-04_task-rest_bold.json"]'); await loaded(p);
      const ed = await p.evaluate(() => ({ h1: document.querySelector('h1').textContent, path: (document.querySelector('#c-new') || {}).value, text: document.querySelector('#c-text').value,
        note: (document.querySelector('.fix-note') || {}).textContent || '', reason: document.querySelector('#c-reason').value, focus: document.activeElement.id,
        cancel: (document.querySelector('form a.btn.ghost') || {}).getAttribute('href') }));
      const okEdit = /^Add sub-04_task-rest_bold\.json$/.test(ed.h1) && ed.path === 'sub-04/func/sub-04_task-rest_bold.json' && JSON.parse(ed.text).RepetitionTime === 2
        && /1\.5/.test(ed.note) && /header volume spacing/.test(ed.note) && ed.reason === '' && ed.focus === 'c-text' && /tab=assessment$/.test(ed.cancel);
      await prepare(p, 'probe: scanner protocol gives TR 2 s');
      const tag = await p.evaluate(() => { const f = [...document.querySelectorAll('.finding')].find(x => /MriRepetitionTimeMismatch/.test(x.textContent) && /sub-04_task-rest/.test(x.textContent)); return f ? (f.querySelector('.tag.delta') || {}).textContent : 'absent'; });
      const accept = await p.evaluate(() => !document.querySelector('[data-fk="accept"]').disabled);
      done(okEdit && /resolved/i.test(tag) && accept, JSON.stringify({ ...ed, note: ed.note.slice(0, 90), tag, accept }));
    });
    // 64. A table fix opens the named table with its first flagged row selected.
    await guarded('fix-events-selects-row', async (o, done) => {
      const [, p] = await o(assessAt); await p.waitForSelector('button.fix', { timeout: 5000 });
      await p.click('button.fix[aria-label$="events.tsv"]'); await loaded(p); await p.waitForTimeout(100);
      const st = await p.evaluate(() => { const t = document.querySelector('#c-text');
        return { sel: t.value.slice(t.selectionStart, t.selectionEnd), line: t.value.slice(0, t.selectionStart).split('\n').length, scrolled: t.scrollTop > 0,
          note: (document.querySelector('.fix-note') || {}).textContent || '', focus: document.activeElement.id }; });
      done(st.line === 50 && /^405\.75\t/.test(st.sel) && st.scrolled && /Rows 50, 51/.test(st.note) && st.focus === 'c-text', JSON.stringify(st));
    });
    // 65. The inspector offers the same Fix; an earlier release offers none and points to the head.
    await guarded('fix-in-inspector-head-only', async (o, done) => {
      const img = 'sub-04/func/sub-04_task-rest_bold.nii.gz';
      const [, p] = await o(`#/d/${DS}/r/${R3}?path=${encodeURIComponent(img)}`); await p.waitForSelector('.inspector .finding', { timeout: 5000 });
      const head = await p.$$eval('.inspector button.fix', bs => bs.map(b => b.getAttribute('aria-label')));
      const earlier = await p.evaluate(() => (document.querySelector('.lineage a.node:not([aria-current])') || {}).getAttribute('href'));
      await p.evaluate(h => { location.hash = h.replace(/\?.*$/, '') + '?path=' + encodeURIComponent('sub-04/func/sub-04_task-rest_bold.nii.gz'); }, earlier);
      await p.waitForFunction(() => /This is an earlier release/.test(document.body.textContent) && document.querySelector('.inspector .finding'), null, { timeout: 5000 });
      const old = await p.$$eval('.inspector button.fix', bs => bs.length);
      done(head.length === 1 && /add sub-04\/func\/sub-04_task-rest_bold\.json$/.test(head[0]) && old === 0, JSON.stringify({ head, earlier, old }));
    });
    // 66. Identical effective-metadata changes across files read as one change with the files listed under it.
    await guarded('metadata-changes-grouped', async (o, done) => {
      const [, p] = await o(`#/d/${DS}/r/${R3}/correct?path=task-nback_bold.json`); await loaded(p);
      await p.fill('#c-text', (await p.inputValue('#c-text')).replace('"FlipAngle": 52', '"FlipAngle": 60')); await prepare(p);
      const st = await p.evaluate(() => ({ entries: document.querySelectorAll('ul.changes > li.change').length, summary: (document.querySelector('.change-files summary') || {}).textContent || '',
        files: [...document.querySelectorAll('.change-files .change-path')].map(e => e.textContent), rows: document.querySelectorAll('.change-row').length,
        chip: (document.querySelector('[data-fk="preview-head"]').closest('section').querySelector('.chip') || {}).textContent }));
      done(st.entries === 1 && /^4 files with the same change/.test(st.summary) && st.files.length === 4 && st.files.every(f => /_task-nback_run-1_bold\.nii\.gz$/.test(f)) && st.rows === 1 && st.chip === '4 files affected',
        JSON.stringify({ ...st, files: st.files.length }));
    });
  } finally { await b.close(); }
  console.log(`behaviour failures: ${bad}`);
  process.exitCode = bad ? 1 : 0;
})().catch(L.fail);
