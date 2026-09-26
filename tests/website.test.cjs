const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const {createServer, parseGithubRelease, atLeast} = require('../website/server.js');

const root = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(root,p));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const release = JSON.parse(read('website/public/release.json'));

test('2.9-Website bewirbt nur die geprüfte neue APK, nicht das alte Update', () => {
  const current = read('website/public/downloads/Fusch-2.9.apk');
  const fromRepo = read('releases/Fusch-2.9-ohne-Kapsel.apk');
  assert.deepEqual(current, fromRepo);
  assert.equal(sha(current), release.sha256);
  assert.equal(current.length, release.apkBytes);
  assert.equal(release.version, '2.9');
  assert.equal(release.code, 22);
  const published = JSON.parse(read('website/public/update.json'));
  assert.equal(published.version, '2.5');
  assert.equal(published.code, 18);
  assert.notEqual(sha(current), sha(read('website/public/downloads/Fusch-latest.apk')));
  const dom = new JSDOM(read('website/public/index.html').toString('utf8'));
  const document = dom.window.document;
  try {
    assert.ok(document.querySelectorAll('[data-download]').length >= 3);
    for (const link of document.querySelectorAll('[data-download]'))
      assert.equal(link.getAttribute('href'), '/downloads/Fusch-2.9.apk');
    assert.match(document.querySelector('#download').textContent, /andere Signatur/);
    assert.match(document.querySelector('#download').textContent, /deinstallieren/);
    assert.doesNotMatch(document.querySelector('main').innerHTML, /shot-(?:map|spoof|favs|splash|settings)\.png|downloads\/Fusch-latest\.apk/);
  } finally { dom.window.close(); }
});

test('Alle sechs Galerie-Bilder sind lokale, neue und echte WebP-Aufnahmen', () => {
  const dom = new JSDOM(read('website/public/index.html').toString('utf8'));
  try {
    const shots = [...dom.window.document.querySelectorAll('.shot img')];
    assert.equal(shots.length,6);
    for (const img of shots) {
      assert.ok(img.alt.length > 18,'sinnvoller Alternativtext');
      const name=img.getAttribute('src');
      assert.match(name,/^img\/fusch-2\.9-[a-z]+\.webp$/);
      const bytes=read('website/public/'+name);
      assert.equal(bytes.subarray(0,4).toString(),'RIFF');
      assert.equal(bytes.subarray(8,12).toString(),'WEBP');
    }
    assert.match(dom.window.document.querySelector('#live').textContent,/Website ändert keinen Gerätestandort/);
    assert.match(dom.window.document.querySelector('#einblicke').textContent,/Beispieldaten/);
  } finally { dom.window.close(); }
});

test('GitHub-Daten werden nur für stabile, passende, nicht ältere Releases übernommen', () => {
  const good={tag_name:'v2.9',published_at:'2026-09-26T12:00:00Z',draft:false,prerelease:false,
    assets:[{name:'Fusch-2.9-ohne-Kapsel.apk',size:release.apkBytes,
      digest:`sha256:${release.sha256}`,
      browser_download_url:'https://github.com/Fufi1925/Fusch/releases/download/v2.9/Fusch-2.9-ohne-Kapsel.apk'}]};
  const clone=()=>structuredClone(good);
  assert.equal(parseGithubRelease(good).download,good.assets[0].browser_download_url);
  assert.equal(parseGithubRelease({...good,draft:true}),null);
  assert.equal(parseGithubRelease({...good,prerelease:true}),null);
  assert.equal(parseGithubRelease({...good,tag_name:'v2.8'}),null);
  let invalid=clone();invalid.assets[0].size++;assert.equal(parseGithubRelease(invalid),null);
  invalid=clone();invalid.assets[0].digest='sha256:'+'a'.repeat(64);
  assert.equal(parseGithubRelease(invalid),null);
  invalid=clone();invalid.assets[0].browser_download_url='https://example.com/app.apk';
  assert.equal(parseGithubRelease(invalid),null);
  invalid=clone();invalid.assets[0].name='Fusch-dev.apk';assert.equal(parseGithubRelease(invalid),null);
  assert.equal(atLeast('2.10','2.9'),true);
  assert.equal(atLeast('2.8.10','2.9'),false);
  assert.equal(atLeast('3.0','2.9'),true);
  invalid={tag_name:'v2.10',draft:false,prerelease:false,
    assets:[{name:'Fusch-2.10.apk',size:140000,digest:'sha256:'+'a'.repeat(64),
      browser_download_url:'https://github.com/Fufi1925/Fusch/releases/download/v2.10/Fusch-2.10.apk'}]};
  assert.equal(parseGithubRelease(invalid).version,'2.10');
});

test('Server liefert die neue APK korrekt aus und schützt das alte Manifest', async t => {
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base=`http://127.0.0.1:${server.address().port}`;
  const apk=await fetch(base+'/downloads/Fusch-2.9.apk');
  assert.equal(apk.status,200);
  assert.equal(apk.headers.get('content-type'),'application/vnd.android.package-archive');
  assert.match(apk.headers.get('content-disposition'),/attachment; filename="Fusch-2\.9\.apk"/);
  assert.equal(sha(Buffer.from(await apk.arrayBuffer())),release.sha256);
  const old=await fetch(base+'/update.json');
  assert.equal((await old.json()).code,18);
  const page=await fetch(base+'/');assert.equal(page.status,200);
  assert.match(await page.text(),/Dein Standort/);
  const screenshot=await fetch(base+'/img/fusch-2.9-karte.webp');
  assert.equal(screenshot.headers.get('content-type'),'image/webp');
  const denied=await fetch(base+'/..%2f..%2fREADME.md');
  assert.notEqual(denied.status,200);
  const current=await fetch(base+'/api/release');
  const live=await current.json();
  assert.equal(current.status,200);
  assert.equal(current.headers.get('cache-control'),'no-store');
  assert.equal(atLeast(live.version,release.version),true);
  assert.match(live.download,/^(\/downloads\/Fusch-2\.9\.apk|https:\/\/github\.com\/Fufi1925\/Fusch\/releases\/download\/)/);
});
