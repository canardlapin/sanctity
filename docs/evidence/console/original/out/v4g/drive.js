// Drives a specimen into its state, then marks <html data-specimen="ready"|"failed:...">.
(() => {
  const spec = window.SPEC || {};
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const until = async (f, what) => { for (let i = 0; i < 100; i++) { const v = f(); if (v) return v; await sleep(50); } throw new Error('timeout ' + what); };
  const $ = s => document.querySelector(s);
  const input = el => el.dispatchEvent(new Event('input', { bubbles: true }));
  (async () => {
    await until(() => $('main, .connect'), 'app');
    for (const st of spec.steps || []) {
      if (st.wait) await until(() => $(st.wait), st.wait);
      if (st.waitValue) await until(() => $(st.waitValue) && $(st.waitValue).value, st.waitValue);
      if (st.fill) { const el = await until(() => $(st.fill[0]), st.fill[0]); el.value = st.fill[1]; input(el); }
      if (st.replace) { const el = $(st.replace[0]); if (!el.value.includes(st.replace[1])) throw new Error('replace source missing'); el.value = el.value.replace(st.replace[1], st.replace[2]); input(el); }
      if (st.check) { const el = $(st.check); el.click(); }
      if (st.click) { $(st.click).click(); }
      await sleep(30);
    }
    await sleep(250);
    if (document.activeElement && document.activeElement !== document.body && !spec.keepFocus) document.activeElement.blur();
    window.scrollTo(0, 0);
    document.documentElement.dataset.specimen = 'ready';
  })().catch(e => { document.documentElement.dataset.specimen = 'failed:' + e.message; console.error(e); });
})();
