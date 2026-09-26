#!/usr/bin/env node
/**
 * Reproduzierbare Aufnahmen der in der 2.9-APK enthaltenen App-Oberfläche.
 * Kein Bildgenerator: Chromium rendert app/assets/index.html wie die Android-WebView.
 * Die Native-Bridge stellt nur klar gekennzeichnete Beispieldaten bereit; es wird
 * kein echter Android-Standort verändert und kein Gerät vorgetäuscht.
 *
 * Einmalig lokal: npm --prefix website install --no-save --no-package-lock playwright-core
 *               (Chromium und ImageMagick müssen installiert sein; CHROME_PATH ist optional.)
 * Danach: node website/tools/capture-screenshots.cjs
 */
'use strict';
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { chromium } = require('playwright-core');
const root = path.resolve(__dirname, '../..');
const assets = path.join(root, 'app/assets');
const out = path.join(root, 'website/public/img');
const mime = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png'};
const server = http.createServer((req,res)=>{
  const file=path.resolve(assets,'.'+decodeURIComponent(req.url.split('?')[0]));
  if(!file.startsWith(assets+path.sep)){res.writeHead(403);res.end();return;}
  fs.readFile(file,(e,b)=>{if(e){res.writeHead(404);res.end();return;}
    res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});res.end(b);});
});

(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/usr/bin/chromium',args:['--no-sandbox']});
  try {
    const context=await browser.newContext({viewport:{width:412,height:915},deviceScaleFactor:2,
      isMobile:true,hasTouch:true,locale:'de-DE',timezoneId:'Europe/Berlin',reducedMotion:'reduce'});
    const page=await context.newPage();
    page.on('pageerror',e=>console.error('App-Fehler:',e.message));
    const now=Date.now();
    await page.addInitScript(now=>{
      window.__demoRoute=false;
      const data={v:14,onb:1,bgask:1,
        S:{map_style:'dark',map_labels:true,map_key_tiles:false,fav_sort:'new',keep_alive:true,mock_network:true,jitter:true},
        favs:[
          {name:'Duisburg Innenhafen',lat:51.44388,lng:6.76358,ts:now-86400000},
          {name:'Berlin Hauptbahnhof',lat:52.52508,lng:13.36940,ts:now-172800000},
          {name:'Amsterdam Centraal',lat:52.37911,lng:4.90027,ts:now-259200000},
          {name:'Köln Dom',lat:50.94126,lng:6.95828,ts:now-345600000}
        ],
        hist:[
          {name:'Duisburg Innenhafen',lat:51.44388,lng:6.76358,ts:now-3600000},
          {name:'Berlin Hauptbahnhof',lat:52.52508,lng:13.36940,ts:now-86400000},
          {name:'Amsterdam Centraal',lat:52.37911,lng:4.90027,ts:now-172800000}
        ]};
      window.Android={storeGet:()=>JSON.stringify(data),storeSet:()=>{},setSettings:()=>{},
        geoStatus:()=> 'NONE',geoReverse:()=>JSON.stringify({name:'Duisburg Innenhafen',display_name:'Innenhafen, Duisburg, Nordrhein-Westfalen'}),
        checkUpdate:()=>'',hasLocPerm:()=>true,hasNotifPerm:()=>true,mockAllowed:()=>true,battOpt:()=>true,
        isSpoofing:()=>true,activeStatus:()=>JSON.stringify(window.__demoRoute
          ?{route:true,name:'Route unterwegs',startedAt:now-68000,totalMeters:6400,doneMeters:2100,speedMs:6,loop:false}
          :{route:false,name:'Duisburg Innenhafen',startedAt:now-68000}),
        appVersion:()=> '2.9',appCode:()=>22};
    },now);
    await page.goto(`http://127.0.0.1:${server.address().port}/index.html`,{waitUntil:'load'});
    await page.locator('#splash').waitFor({state:'hidden',timeout:8000});
    await page.evaluate(()=>{
      map.setView([51.44388,6.76358],13,{animate:false});
      setTarget(51.44388,6.76358,{noFly:true});
    });
    // Esri-Kacheln sind echtes Kartenmaterial aus dem Laufzeit-Kartenlayer.
    // Wenn die externe Verbindung fehlt, zeigen wir keinen erfundenen Kartenscreenshot.
    await page.waitForFunction(()=>[...document.querySelectorAll('#map img.leaflet-tile')]
      .some(img=>img.complete&&img.naturalWidth>0),{timeout:14000});
    await page.waitForTimeout(700);
    async function shot(name){
      const png=path.join(out,`fusch-2.9-${name}.png`);
      const webp=path.join(out,`fusch-2.9-${name}.webp`);
      await page.screenshot({path:png,animations:'disabled'});
      execFileSync('magick',[png,'-strip','-quality','88',webp]);
      fs.unlinkSync(png);
      console.log('Aufnahme:',webp);
    }
    await shot('karte');
    await page.evaluate(()=>{openFavs();});
    await shot('favoriten');
    await page.evaluate(()=>{closeFavs();renderHist();document.getElementById('histtab').classList.add('open');});
    await shot('verlauf');
    await page.evaluate(()=>{document.getElementById('histtab').classList.remove('open');openSettings();});
    await shot('einstellungen');
    await page.evaluate(()=>{
      closeSettings();window.onNativeState(true,'',51.44388,6.76358);
    });
    await shot('aktiv');
    await page.evaluate(()=>{window.__demoRoute=true;refreshActiveScreen();});
    await shot('route');
    await context.close();
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>server.close());
