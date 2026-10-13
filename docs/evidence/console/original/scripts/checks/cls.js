// Initial unexpected layout-shift sum over load + 3 s, not session-window CLS.
// usage: node cls.js "<file>;<file>;..." [widths=390,1440]
const L = require("../lib.js");
(async () => {
  const { files, widths } = L.inputs(process.argv[2], process.argv[3]);
  const b = await L.launch(); let bad = 0;
  try {
    for (const f of files) for (const w of widths) {
      const ctx = await b.newContext({ viewport: { width: w, height: 900 } });
      try {
      await ctx.addInitScript(() => {
        window.__supported = PerformanceObserver.supportedEntryTypes.includes("layout-shift");
        window.__cls = 0; window.__src = [];
        try {
          new PerformanceObserver(l => l.getEntries().forEach(e => {
            if (e.hadRecentInput) return;
            window.__cls += e.value;
            (e.sources || []).slice(0, 1).forEach(s => window.__src.push(s.node ? s.node.nodeName + "." + String(s.node.className || "").slice(0, 30) : "?"));
          })).observe({ type: "layout-shift", buffered: true });
        } catch (e) { window.__supported = false; }
      });
      const p = await ctx.newPage();
      const errors = []; p.on("pageerror", e => errors.push(e.message));
      const response = await p.goto(L.url(f), { timeout: 30000 });
      if (response && !response.ok()) throw new Error("HTTP " + response.status()); await p.waitForTimeout(3000);
      const r = await p.evaluate(() => ({ supported: window.__supported, c: window.__cls, s: window.__src.slice(0, 3) }));
      if (!r.supported) throw new Error("layout-shift observation unsupported");
      if (errors.length) throw new Error("Page errors: " + errors.join("; "));
      const ok = r.c <= 0.05; if (!ok) bad++;
      console.log(`layout shift ${f.split("/").slice(-2).join("/")} @${w}: initial-shift-sum=${r.c.toFixed(4)}${ok ? "" : " " + JSON.stringify(r.s)} -> ${ok ? "PASS" : "FAIL"}`);
      } finally { await ctx.close(); }
    }
  } finally { await b.close(); }
  process.exitCode = bad ? 1 : 0;
})().catch(L.fail);
