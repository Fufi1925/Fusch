// Fusch Website – minimaler statischer Server (keine Abhängigkeiten)
// Railway setzt PORT automatisch.
const http = require('http');
const fs = require('fs');
const path = require('path');

const PUB = path.join(__dirname, 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.json': 'application/json; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8'
};

http.createServer((req, res) => {
  let p;
  try { p = decodeURIComponent((req.url || '/').split('?')[0]); } catch (e) { p = '/'; }
  if (p === '/') p = '/index.html';
  const file = path.normalize(path.join(PUB, p));
  if (!file.startsWith(PUB)) { res.writeHead(403); return res.end('403'); }

  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('404 – nicht gefunden');
    }
    const ext = path.extname(file).toLowerCase();
    const noCache = (p === '/index.html' || p === '/update.json');
    if (ext === '.apk') {
      res.setHeader('Content-Type', MIME['.apk']);
      res.setHeader('Content-Disposition', 'attachment; filename="' + path.basename(file) + '"');
    } else {
      res.setHeader('Content-Type', MIME[ext] || 'application/octet-stream');
    }
    res.setHeader('Cache-Control', noCache ? 'no-cache' : 'public, max-age=3600');
    fs.createReadStream(file).pipe(res);
  });
}).listen(process.env.PORT || 3000, () => {
  console.log('Fusch website läuft auf Port ' + (process.env.PORT || 3000));
});
