'use strict';
// Local, synthetic-only fixture server for the human VoiceOver exercise.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const ui = path.resolve(__dirname, '../../modules/http/src/main/resources/sanctity/ui');
const assets = new Map(['index.html', 'app.js', 'app.css', 'demo.js'].map(name => [name, path.join(ui, name)]));
assets.set('voiceover-conflict.js', path.join(__dirname, 'voiceover-conflict.js'));
const server = http.createServer((req, res) => {
  const route = new URL(req.url, 'http://127.0.0.1').pathname;
  const file = route === '/' || route === '/conflict.html' ? 'index.html' : route.slice(1);
  if (req.method !== 'GET' || !assets.has(file)) { res.writeHead(404); res.end(); return; }
  let data = fs.readFileSync(assets.get(file));
  if (route === '/conflict.html') data = Buffer.from(data.toString().replace('<script src="app.js"></script>',
    '<script src="demo.js"></script><script src="voiceover-conflict.js"></script><script src="app.js"></script>'));
  res.writeHead(200, {
    'Content-Type': file.endsWith('.js') ? 'text/javascript; charset=utf-8' : file.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/html; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'"
  });
  res.end(data);
});
server.listen(8093, '127.0.0.1', () => {
  console.log('VoiceOver demo: http://127.0.0.1:8093/?demo');
  console.log('Concurrent-edit fixture: http://127.0.0.1:8093/conflict.html');
  for (const [name, file] of assets) console.log(crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'), name);
});
