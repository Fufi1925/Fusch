// Fusch Website: statische Dateien + aktuelle, unabhängig geprüfte GitHub-Release-Daten.
// Keine Laufzeit-Abhängigkeiten; Railway setzt PORT automatisch.
'use strict';
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PUB = path.join(__dirname, 'public');
const REPO = 'Fufi1925/Fusch';
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const fallback = Object.freeze(JSON.parse(fs.readFileSync(path.join(PUB, 'release.json'), 'utf8')));
const localApk = path.resolve(PUB, '.' + fallback.download);
if (!localApk.startsWith(path.join(PUB, 'downloads') + path.sep))
  throw new Error('Download-Pfad außerhalb des Download-Ordners');
const apk = fs.readFileSync(localApk);
if (apk.length !== fallback.apkBytes ||
    crypto.createHash('sha256').update(apk).digest('hex') !== fallback.sha256)
  throw new Error('Die hinterlegte Fusch-APK stimmt nicht mit release.json überein');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.webp': 'image/webp', '.json': 'application/json; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive', '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8'
};
let latest = null;
let expires = 0;
let pending = null;

function versionParts(s) {
  return /^\d+\.\d+(?:\.\d+)?$/.test(s) ? s.split('.').map(Number) : null;
}
function atLeast(a, b) {
  const x = versionParts(a), y = versionParts(b);
  if (!x || !y) return false;
  for (let i=0; i<3; i++) {
    if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  }
  return true;
}

// Nur ein veröffentlichtes Release dieses Repos mit einer passenden APK darf
// den Website-Download ersetzen. Keine fremden URLs, Vorabversionen oder
// Versionen unter dem lokal hinterlegten Stand akzeptieren.
function parseGithubRelease(release) {
  if (!release || release.draft || release.prerelease ||
      typeof release.tag_name !== 'string' || !/^v\d+\.\d+(?:\.\d+)?$/.test(release.tag_name)) return null;
  const version = release.tag_name.slice(1);
  if (!atLeast(version, fallback.version) || !Array.isArray(release.assets)) return null;
  const prefix = `https://github.com/${REPO}/releases/download/${release.tag_name}/`;
  const expectedName = new RegExp(`^Fusch-${version.replace(/\./g, '\\.')}(?:-[A-Za-z0-9_.-]+)?\\.apk$`);
  const asset = release.assets.find(item => item &&
    expectedName.test(item.name) &&
    item.browser_download_url === prefix + encodeURIComponent(item.name));
  if (!asset || !Number.isSafeInteger(asset.size) || asset.size < 10000) return null;
  // Bei demselben Tag muss das auf GitHub hochgeladene Artefakt genau der
  // lokalen, bereits per SHA-256 geprüften 2.9-APK entsprechen.
  if (version === fallback.version &&
      (asset.size !== fallback.apkBytes ||
       (asset.digest && asset.digest !== `sha256:${fallback.sha256}`))) return null;
  const published = typeof release.published_at === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(release.published_at)
      ? release.published_at.slice(0,10) : fallback.date;
  return {
    version, code: version === fallback.version ? fallback.code : null,
    tag: release.tag_name, date: published, download: asset.browser_download_url,
    apkBytes: asset.size,
    sha256: version === fallback.version ? fallback.sha256
      : /^sha256:[a-f0-9]{64}$/.test(asset.digest || '') ? asset.digest.slice(7) : null,
    releaseUrl: `https://github.com/${REPO}/releases/tag/${release.tag_name}`,
    source: 'github', checkedAt: new Date().toISOString()
  };
}

function githubJson() {
  return new Promise((resolve, reject) => {
    const req = https.get(RELEASE_API, {
      headers: { 'User-Agent': 'FuschWebsite/2.9 (+https://github.com/Fufi1925/Fusch)',
        Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' }
    }, response => {
      if (response.statusCode !== 200) {
        response.resume(); reject(new Error(`GitHub HTTP ${response.statusCode}`)); return;
      }
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 512000) response.destroy(new Error('GitHub-Antwort zu groß'));
      });
      response.on('end', () => {
        try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
      });
      response.on('error', reject);
    });
    req.setTimeout(5000, () => req.destroy(new Error('GitHub timeout')));
    req.on('error', reject);
  });
}

async function releaseData() {
  if (Date.now() < expires) return latest || { ...fallback, source: 'local', checkedAt: null };
  if (pending) return pending;
  pending = githubJson().then(data => {
    latest = parseGithubRelease(data);
    expires = Date.now() + 10 * 60 * 1000;
    return latest || { ...fallback, source: 'local', checkedAt: null };
  }).catch(() => {
    expires = Date.now() + 2 * 60 * 1000;
    return latest ? { ...latest, source: 'cache' } : { ...fallback, source: 'local', checkedAt: null };
  }).finally(() => { pending = null; });
  return pending;
}

function createServer() {
  return http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    let p;
    try { p = decodeURIComponent(new URL(req.url || '/', 'http://localhost').pathname); }
    catch (_) { res.writeHead(400); res.end('Ungültige URL'); return; }
    if (p === '/api/release') {
      if (req.method !== 'GET') { res.writeHead(405, {Allow:'GET'}); res.end(); return; }
      const data = await releaseData();
      res.writeHead(200, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(data)); return;
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, {Allow:'GET, HEAD'}); res.end(); return;
    }
    if (p === '/') p = '/index.html';
    const file = path.resolve(PUB, '.' + p);
    if (!file.startsWith(PUB + path.sep)) { res.writeHead(403); res.end('403'); return; }
    fs.realpath(file, (error, actual) => {
      if (error || !actual.startsWith(PUB + path.sep)) {
        res.writeHead(404); res.end('404'); return;
      }
      fs.stat(actual, (e, stat) => {
        if (e || !stat.isFile()) {
          res.writeHead(404, { 'Content-Type':'text/plain; charset=utf-8' });
          res.end('404 – nicht gefunden'); return;
        }
        const ext = path.extname(actual).toLowerCase();
        res.setHeader('Content-Type', MIME[ext] || 'application/octet-stream');
        const noCache = p === '/index.html' || p === '/update.json' || p === '/release.json';
        res.setHeader('Cache-Control', noCache ? 'no-cache' : 'public, max-age=3600');
        if (ext === '.apk') {
          res.setHeader('Content-Disposition', `attachment; filename="${path.basename(actual)}"`);
        }
        res.setHeader('Content-Length', stat.size);
        res.writeHead(200);
        if (req.method === 'HEAD') res.end(); else fs.createReadStream(actual).pipe(res);
      });
    });
  });
}

if (require.main === module) {
  const port = process.env.PORT || 3000;
  createServer().listen(port, '0.0.0.0', () => console.log(`Fusch website läuft auf Port ${port}`));
}
module.exports = {createServer, parseGithubRelease, atLeast};
