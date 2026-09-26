const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');

const root = path.resolve(__dirname, '..');
function read(file) { return fs.readFileSync(path.join(root, file), 'utf8'); }

function app(options = {}) {
  const html = read('app/assets/index.html');
  const dom = new JSDOM(html, {url: 'https://app.example/', runScripts: 'outside-only'});
  const {window: w} = dom;
  const state = {loc: false, notif: false, mock: false, battery: false, running: false};
  const calls = [], writes = [];
  w.Android = {
    storeGet: () => JSON.stringify(options.nativeStore || {}),
    storeSet: j => writes.push(JSON.parse(j)), setSettings: () => {},
    geoStatus: () => 'BUILTIN', checkUpdate: () => '',
    hasLocPerm: () => state.loc, hasNotifPerm: () => state.notif,
    mockAllowed: () => state.mock, battOpt: () => state.battery,
    stopSpoof: () => { calls.push('stop'); state.running = false; w.__onState(false, '', 0, 0); },
    requestLocationPermission: () => calls.push('location'),
    requestNotificationPermission: () => calls.push('notification'),
    openDevSettings: () => calls.push('developers'),
    openAppSettings: () => calls.push('app-info'),
    requestBattOpt: () => calls.push('battery'),
    deviceLocation: () => calls.push('device-location')
  };
  if (options.settings) w.localStorage.setItem('sg_settings', JSON.stringify(options.settings));
  if (options.favs) w.localStorage.setItem('sg_favs', JSON.stringify(options.favs));
  if (options.hist) w.localStorage.setItem('sg_hist', JSON.stringify(options.hist));
  w.eval(read('app/assets/icons.js'));
  const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
    .map(m => m[1]).filter(Boolean);
  for (const script of scripts) {
    // jsdom evaluates each script in its own lexical scope; test hooks are
    // appended to that same source, without adding debug APIs to the APK.
    const hooks = script.includes('let spoofBusy=false') ? '\n' +
      'window.__testSetRouteState=()=>{routeRun=true;spoofing=true;routePts=[{lat:52,lng:7},{lat:52.01,lng:7.01}];};' +
      'window.__testSetBusyState=()=>{spoofBusy=true;window._spoofResult="OK";};' +
      'window.__testSettings=()=>Object.assign({},S);' +
      'window.__testData=()=>({favs:favs.slice(),hist:hist.slice()});' : '';
    w.eval(script + hooks);
  }
  w.FuschIcons.render(w.document);
  return {dom, w, state, calls, writes, byId: id => w.document.getElementById(id)};
}

test('Berechtigungen: kein automatischer Dialog; Standort und Meldungen sind getrennt', () => {
  const {dom, w, state, calls, byId} = app();
  try {
    assert.deepEqual(calls, [], 'app startup must not request permissions or battery exemption');
    w.onbShow(2);
    assert.equal(byId('onb_cta').disabled, true);
    byId('onb_allow_loc').click();
    assert.deepEqual(calls, ['location']);
    byId('onb_allow_not').click();
    assert.deepEqual(calls, ['location', 'notification']);
    byId('onb_app_settings').click();
    assert.equal(calls.at(-1), 'app-info');
    state.loc = true;
    w.permsResult();
    assert.equal(byId('onb_cta').disabled, false);
    assert.equal(byId('onb_loc_stat').textContent, 'Erlaubt');
    assert.equal(byId('onb_not_stat').textContent, 'Noch nicht angefragt');
    assert.equal(byId('ploci').textContent, 'aktiv');
    assert.equal(byId('pnoti').textContent, 'fehlt');
  } finally { dom.window.close(); }
});

test('Entwickleroptionen und Akku-Seite: richtige Bridge-Aufrufe, Status nach Rückkehr', () => {
  const {dom, w, state, calls, byId} = app();
  try {
    w.onbShow(3);
    assert.equal(byId('onb_cta').disabled, true);
    byId('onb_dev').click();
    assert.deepEqual(calls, ['developers']);
    state.mock = true;
    w.onSystemSettingsReturn();
    assert.equal(byId('onb_cta').disabled, false);
    assert.equal(byId('onb_mock_ok').classList.contains('show'), true);
    byId('onb_cta').click();
    assert.equal(byId('onb_s4').classList.contains('on'), true);
    byId('onb_allow_batt').click();
    assert.equal(calls.at(-1), 'battery');
    state.battery = true;
    w.onSystemSettingsReturn();
    assert.equal(byId('onb_batt_stat').textContent, 'Ausnahme erlaubt');
    assert.equal(byId('onb_allow_batt').style.display, 'none');
    assert.equal(byId('pbatti').textContent, 'Ausnahme aktiv');
  } finally { dom.window.close(); }
});

test('Einstellungsbuttons fragen jeweils nur ihre eigene Berechtigung an', () => {
  const {dom, w, calls, byId} = app();
  try {
    w.openSettings();
    byId('reqLoc').click();
    byId('reqNotif').click();
    byId('battSettings').click();
    byId('devset').click();
    assert.deepEqual(calls, ['location', 'notification', 'battery', 'developers']);
    byId('loc').click();
    assert.deepEqual(calls, ['location', 'notification', 'battery', 'developers'],
      'location shortcut must not request a permission without another explicit tap');
    assert.match(byId('mbox').textContent, /Genauen Standort erlauben/);
  } finally { dom.window.close(); }
});

test('App und Website rendern dekorative Symbole als monochrome SVGs statt Emoji', () => {
  const a = app();
  try {
    assert.equal(a.w.document.querySelectorAll('[data-icon]').length, 0);
    assert.ok(a.w.document.querySelectorAll('.mono-icon-glyph').length >= 30);
    assert.equal(a.w.getComputedStyle(a.w.document.querySelector('.mono-icon')).color,
      'rgb(154, 154, 154)');
    assert.ok(a.w.FuschIcons.html('star').includes('<svg'));
  } finally { a.dom.window.close(); }
  const dom = new JSDOM(read('website/public/index.html'),
    {url: 'https://website.example/', runScripts: 'outside-only'});
  try {
    dom.window.eval(read('website/public/icons.js'));
    dom.window.FuschIcons.render(dom.window.document);
    assert.equal(dom.window.document.querySelectorAll('[data-icon]').length, 0);
    assert.ok(dom.window.document.querySelectorAll('.mono-icon-glyph').length >= 13);
    assert.equal(dom.window.getComputedStyle(dom.window.document.querySelector('.mono-icon')).color,
      'rgb(154, 154, 154)');
    for (const file of ['app/assets/index.html', 'website/public/index.html'])
      assert.doesNotMatch(read(file), /\p{Emoji_Presentation}|\uFE0F/u);
    // The historical 2.5 update manifest must not be rewritten for the separately signed APK.
    const published = JSON.parse(read('website/public/update.json'));
    assert.equal(published.version, '2.5');
    assert.equal(published.code, 18);
  } finally { dom.window.close(); }
});

test('Einstellungen: kein Dynamic Island und nur echte, sortierte Fusch-Funktionen', () => {
  const {dom, w, state, calls, byId} = app();
  try {
    w.openSettings();
    const text = byId('settab').textContent;
    const sections = [...byId('settab').querySelectorAll('h3.settings-label')]
      .map(x => x.textContent.trim());
    assert.deepEqual(sections,['SPOOFING', 'EINRICHTUNG', 'ROUTE', 'KARTE', 'GEO-API']);
    for (const removed of ['Dynamic Island', 'Schwebende Kapsel', 'Weitere Einstellungen',
      'DEIN FUSCH', 'FEINABSTIMMUNG', 'BENACHRICHTIGUNG', 'Diagnose'])
      assert.ok(!text.includes(removed), removed);
    assert.equal(byId('islandSwitch'), null);
    for (const id of ['devset', 'reqLoc', 'reqNotif', 'battSettings',
      'styleseg', 'profseg', 'apitest', 'ckin', 'settingsGuide']) assert.ok(byId(id), id);
    assert.equal(byId('pmockdot').classList.contains('needs'), true);
    byId('refreshChecks').click();
    assert.deepEqual(calls, [], 'Status prüfen öffnet keinen Berechtigungsdialog');
    state.loc = true; state.notif = true; state.mock = true; state.battery = true;
    w.onSystemSettingsReturn();
    for (const id of ['pmockdot', 'plocdot', 'pnotdot', 'pbattdot'])
      assert.equal(byId(id).classList.contains('is-ok'), true, id);
    byId('reqLoc').click(); byId('reqNotif').click();
    assert.deepEqual(calls, ['app-info', 'app-info']);
  } finally { dom.window.close(); }
});

test('2.8-Upgrade: gespeicherter Insel-Schalter wird gelöscht statt erneut aktiviert', () => {
  const {dom, w, calls, byId} = app({nativeStore:{v:13, onb:1, bgask:1,
    S:{dynamic_island:true,map_style:'dark'}},settings:{dynamic_island:true}});
  try {
    assert.equal('dynamic_island' in w.__testSettings(), false);
    assert.equal('dynamic_island' in JSON.parse(w.localStorage.getItem('sg_settings')), false);
    assert.equal(byId('settab').textContent.includes('Insel'), false);
    assert.deepEqual(calls, []);
  } finally { dom.window.close(); }
});

test('Android: keine Overlay-Klasse, keine Overlay-Berechtigung, Service bleibt aktiv', () => {
  const manifest=read('app/AndroidManifest.xml');
  const main=read('app/src/com/spoofgps/app/MainActivity.java');
  const service=read('app/src/com/spoofgps/app/SpoofService.java');
  assert.match(manifest,/versionCode="22"/);
  assert.match(manifest,/versionName="2.9"/);
  assert.doesNotMatch(manifest,/SYSTEM_ALERT_WINDOW|BIND_ACCESSIBILITY_SERVICE/);
  assert.equal(fs.existsSync(path.join(root,'app/src/com/spoofgps/app/IslandOverlay.java')),false);
  assert.doesNotMatch(main+service,/IslandOverlay|openOverlaySettings|setDynamicIsland/);
  assert.match(main,/Prefs\.remove\(this, "dynamic_island"\)/);
  assert.match(service,/startForeground\(ID, build\(\)\)/);
  assert.match(service,/ACTION_STOP/);
});

test('Aktiver Spoof: nur Status und Stop bedienbar, danach Tabs wieder frei', () => {
  const {dom, w, state, calls, byId} = app();
  try {
    state.running=true;
    w.Android.isSpoofing=()=>state.running;
    w.Android.activeStatus=()=>JSON.stringify({route:false, name:'Essen Innenstadt',
      startedAt:Date.now()-8200, totalMeters:0, doneMeters:0, speedMs:0});
    w.__onState(true,'',51.456,7.013);
    assert.equal(byId('activePanel').classList.contains('hidden'),false);
    assert.equal(byId('settab').hasAttribute('inert'),true,'Hintergrund auch für Tastatur gesperrt');
    assert.equal(w.document.activeElement.id,'activeStop');
    assert.equal(byId('activeTitle').textContent,'Standort aktiv');
    assert.equal(byId('activePlace').textContent,'Essen Innenstadt');
    assert.equal(byId('activeRoute').classList.contains('hidden'),true);
    w.openSettings();w.openFavs();byId('histbtn').click();
    byId('reqLoc').click();byId('battSettings').click();byId('apitest').click();
    assert.deepEqual(calls, [],'andere native Aktionen sind auch direkt gesperrt');
    const keep=byId('settab').querySelector('input[data-k=keep_alive]');
    keep.checked=false;keep.dispatchEvent(new w.Event('change'));
    assert.equal(keep.checked,true,'gesperrter Schalter setzt sich sofort zurück');
    assert.equal(w.__testSettings().keep_alive,true);
    for(const id of ['settab','favtab','histtab'])
      assert.equal(byId(id).classList.contains('open'),false,id);
    assert.equal(w.appBack(),false,'Back darf Fusch verlassen, aber keine neuen Aktionen starten');
    byId('activeStop').click();
    assert.deepEqual(calls,['stop']);
    assert.equal(byId('activePanel').classList.contains('hidden'),true);
    assert.equal(byId('settab').hasAttribute('inert'),false);
    w.openFavs();assert.equal(byId('favtab').classList.contains('open'),true);
  } finally { dom.window.close(); }
});

test('Route: Stoppen aus Benachrichtigung setzt Routenbedienung zurück', () => {
  const {dom, w, byId} = app();
  try {
    w.__testSetRouteState();
    byId('routebar').classList.add('live');
    byId('rliverow').classList.add('show');
    byId('rgo').classList.add('hidden');
    w.__onState(false,'',0,0);
    assert.equal(byId('routebar').classList.contains('live'), false);
    assert.equal(byId('rliverow').classList.contains('show'), false);
    assert.equal(byId('rgo').classList.contains('hidden'), false,
      'die bereits geplante Route soll nach Stopp erneut startbar sein');
  } finally { dom.window.close(); }
});

test('Schnelles Verlassen: verspäteter Erfolg darf gestoppten Spoof nicht wieder anzeigen', () => {
  const {dom, w, byId} = app();
  try {
    w.Android.isSpoofing=()=>false;
    w.__testSetBusyState();
    byId('spovl').classList.remove('hidden');
    w.__onState(false,'',0,0);
    assert.equal(byId('spovl').classList.contains('hidden'), true);
    assert.equal(byId('spoofbtn').classList.contains('stop'), false);
    assert.equal(w._spoofResult, 'ERR:STOPPED');
  } finally { dom.window.close(); }
});

test('Neustart der WebView: aktive Route wird mit Fortschrittsanzeige wiederhergestellt', () => {
  const {dom, w, byId} = app();
  try {
    w.Android.activeRoute=()=>JSON.stringify({
      points:[[52,7],[52.01,7.01]], total:1600,
      speed:24,loop:false,startedAt:Date.now()-8000,
    });
    w.Android.activeStatus=()=>JSON.stringify({route:true,loop:false,
      startedAt:Date.now()-8000,totalMeters:1600,doneMeters:800,speedMs:24/3.6});
    w.__onState(true,'',52.005,7.005);
    assert.equal(byId('activePanel').classList.contains('hidden'), false);
    assert.equal(byId('activePercent').textContent, '50 %');
    assert.equal(byId('activeRoute').classList.contains('hidden'), false);
    assert.equal(byId('activeRoute').querySelector('[role=progressbar]').getAttribute('aria-valuenow'), '50');
    assert.equal(byId('routebar').classList.contains('hidden'), false);
    assert.equal(byId('routebar').classList.contains('live'), true);
    assert.equal(byId('rliverow').classList.contains('show'), true);
    assert.equal(byId('rgo').classList.contains('hidden'), true);
    assert.equal(byId('cardwrap').classList.contains('hidden'), true);
    assert.match(byId('statustxt').textContent,/Route aktiv/);
    byId('rcancel').click();
    assert.equal(byId('routebar').classList.contains('hidden'), false,
      'Abbrechen darf die Stop-Bedienung einer aktiven Route nicht verschwinden lassen');
    w.__onState(false,'',0,0);
    assert.equal(byId('rgo').classList.contains('hidden'), false);
  } finally { dom.window.close(); }
});

test('Verlauf: nach Tagen gruppiert, einzelne Einträge löschbar, alle nur nach Bestätigung', () => {
  const now=Date.now();
  const yesterday=new Date();yesterday.setDate(yesterday.getDate()-1);
  const {dom, w, writes, byId}=app({nativeStore:{v:14,onb:1},hist:[
    {name:'Essen',lat:51.456,lng:7.013,ts:now},
    {name:'Dortmund',lat:51.513,lng:7.465,ts:yesterday.getTime()},
  ]});
  try {
    byId('histbtn').click();
    assert.equal(byId('histlist').querySelectorAll('.library-group').length,2);
    assert.deepEqual([...byId('histlist').querySelectorAll('.library-heading h4')]
      .map(e=>e.textContent),['HEUTE','GESTERN']);
    assert.equal(byId('histlist').querySelectorAll('.library-row').length,2);
    byId('histlist').querySelector('.entry-action').click();
    assert.equal(byId('histcount').textContent,'1');
    assert.equal(writes.at(-1).hist.length,1,'Änderung sofort nativ gesichert');
    byId('histclear').click();
    assert.equal(byId('histcount').textContent,'1','Lösch-Dialog verändert Daten nicht');
    assert.equal(byId('movl').classList.contains('hidden'),false);
    w.doClearHist();
    assert.equal(byId('histcount').textContent,'0');
    assert.equal(byId('histempty').classList.contains('hidden'),false);
    assert.equal(byId('histActions').classList.contains('hidden'),true);
    assert.deepEqual(writes.at(-1).hist,[]);
  } finally { dom.window.close(); }
});

test('Favoriten: neutrale Karten, Suche, Umbenennen und sicheres Löschen', () => {
  const {dom, w, writes, byId}=app({nativeStore:{v:14,onb:1},favs:[
    {name:'Berlin',lat:52.52,lng:13.4,ts:1},
    {name:'Essen',lat:51.456,lng:7.013,ts:2},
    {name:'Köln',lat:50.94,lng:6.96,ts:3},
    {name:'Dortmund',lat:51.51,lng:7.47,ts:4},
  ]});
  try {
    w.openFavs();
    assert.equal(byId('favlist').querySelectorAll('.library-row').length,4);
    assert.equal(byId('favlist').querySelectorAll('.fvav').length,0,
      'keine willkürlich bunt eingefärbten Avatare');
    assert.equal(byId('fav-sort-section').classList.contains('hidden'),false);
    byId('fq').value='Essen';
    byId('fq').dispatchEvent(new w.Event('input',{bubbles:true}));
    assert.equal(byId('favlist').querySelectorAll('.library-row').length,1);
    assert.equal(byId('favcount').textContent,'4');
    byId('fqx').click();
    byId('favsort').querySelector('[data-fs=az]').click();
    assert.equal(byId('favlist').querySelector('.entry-name').textContent,'Berlin');
    byId('favlist').querySelector('.entry-action').click();
    byId('renin').value='Berlin neu';byId('rens').click();
    assert.equal(byId('favlist').querySelector('.entry-name').textContent,'Berlin neu');
    assert.equal(writes.at(-1).favs.some(f=>f.name==='Berlin neu'),true);
    byId('favlist').querySelector('.entry-actions .entry-action:last-child').click();
    assert.equal(byId('favcount').textContent,'3');
    byId('favclear').click();
    assert.equal(byId('favcount').textContent,'3','Lösch-Dialog verändert Daten nicht');
    w.doClearAllFavs();
    assert.equal(byId('favcount').textContent,'0');
    assert.equal(byId('favempty').classList.contains('hidden'),false);
    assert.equal(byId('favActions').classList.contains('hidden'),true);
  } finally { dom.window.close(); }
});

test('Historie wird beim erfolgreichen Start direkt gespeichert, auch bei schnellem App-Wechsel', () => {
  const {dom, w, writes, byId}=app({nativeStore:{v:14,onb:1}});
  try {
    w.setTarget(51.456,7.013);
    w.Android.startSpoof=()=> 'OK';
    byId('spoofbtn').click();
    assert.equal(w.__testData().hist.length,1);
    assert.equal(writes.at(-1).hist.length,1,'kein ungesicherter WebView-Timer');
    assert.equal(byId('spovl').classList.contains('hidden'),false);
  } finally { dom.window.close(); }
});

test('Laufender Spoof sperrt auch bereits geöffnete Verlauf- und Favoriten-Aktionen', () => {
  const now=Date.now();
  const {dom, w, byId, writes}=app({nativeStore:{v:14,onb:1},
    favs:[{name:'Essen',lat:51.456,lng:7.013,ts:now}],
    hist:[{name:'Köln',lat:50.94,lng:6.96,ts:now}]});
  try {
    w.openFavs();
    const favorite=byId('favlist').querySelector('.entry-main');
    w.__onState(true,'',51.456,7.013);
    assert.equal(byId('favtab').classList.contains('open'),false);
    favorite.click();byId('favclear').click();
    byId('histclear').click();w.openSettings();
    assert.equal(byId('settab').classList.contains('open'),false);
    assert.equal(w.__testData().favs.length,1);
    assert.equal(w.__testData().hist.length,1);
    assert.equal(writes.length,0);
  } finally { dom.window.close(); }
});
