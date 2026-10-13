// Project gate: every specimen reaches its driven state without errors, no horizontal overflow,
// and axe-core finds no serious/critical WCAG 2.x A/AA violations (light + dark).
// usage: node checks/ui.js ROUND
const path = require('node:path'), fs = require('node:fs');
const L = require('../scripts/lib.js');
const axeSource = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
(async () => {
  const round = L.identifier(process.argv[2]);
  const out = path.join(L.outputRoot, round);
  const specs = Object.keys(JSON.parse(fs.readFileSync(path.join(L.H, 'specimens/specs.json'), 'utf8')));
  const b = await L.launch(); let bad = 0, n = 0;
  try {
    for (const name of specs) for (const [w, scheme] of [[1440, 'light'], [390, 'light'], [1440, 'dark']]) {
      n++;
      const ctx = await b.newContext({ viewport: { width: w, height: 900 }, colorScheme: scheme });
      try {
        const p = await ctx.newPage(); const errs = [];
        p.on('pageerror', e => errs.push('pageerror: ' + e.message));
        p.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
        await p.goto(L.url(path.join(out, name + '.html')));
        await p.waitForFunction(() => document.documentElement.dataset.specimen, null, { timeout: 15000 });
        const st = await p.evaluate(() => document.documentElement.dataset.specimen);
        if (st !== 'ready') errs.push('specimen ' + st);
        const overflow = await p.evaluate(() => document.scrollingElement.scrollWidth - innerWidth);
        if (overflow > 1) errs.push('horizontal overflow ' + overflow + 'px');
        const pre = errs.length; let axing = true;
        p.removeAllListeners('console');
        await p.addScriptTag({ content: axeSource });
        const res = await p.evaluate(async () => (await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21aa'], rules: { 'css-orientation-lock': { enabled: false } } })).violations
          .filter(v => v.impact === 'serious' || v.impact === 'critical').map(v => v.id + '×' + v.nodes.length + ' [' + v.nodes.slice(0, 2).map(x => x.target.join(' ')).join(' | ') + ']'));
        res.forEach(r => errs.push('axe ' + r));
        if (errs.length) bad++;
        console.log(`ui ${name} @${w} ${scheme}: ${errs.length ? 'FAIL ' + errs.join('; ') : 'PASS'}`);
      } finally { await ctx.close(); }
    }
  } finally { await b.close(); }
  console.log(`ui summary: ${n - bad}/${n} PASS`);
  process.exitCode = bad ? 1 : 0;
})().catch(L.fail);
