# Fusch-Website auf Railway

Die Landingpage zeigt ausschließlich den neuesten separat signierten Fusch-Build
(derzeit **2.9 / Code 22**). Der Server nutzt nur Node.js-Standardmodule,
Leaflet und seine Lizenzdateien liegen lokal; die interaktive Karte lädt
CARTO-Kacheln mit OpenStreetMap-Daten **erst beim Anzeigen des Kartenbereichs**.
Ohne Internet funktioniert die Karte nicht; APK, Screenshots und Website bleiben
als statische Dateien verfügbar.

## Deployment

1. Das Repository [`Fufi1925/Fusch`](https://github.com/Fufi1925/Fusch) in
   [Railway](https://railway.app) als GitHub-Projekt auswählen.
2. Für den Service **Root Directory = `website`** setzen. Startbefehl:
   `node server.js`; Railway stellt `PORT` bereit.
3. Domain `https://fusch.up.railway.app` zuweisen bzw. vorhandene Domain nutzen.
4. Nach dem Deploy prüfen: Hauptseite, `/api/release`,
   `/downloads/Fusch-2.9.apk`, `/update.json`.

Lokal: `npm --prefix website start` (Port 3000, oder `PORT=4173`). Keine
Abhängigkeiten zur Laufzeit.

## Welche Version wird gezeigt?

- `website/public/release.json` enthält Version, Code, Dateigröße und SHA-256
  der **lokalen** 2.9-APK. Der Server prüft beim Start, dass diese Angaben zur
  Datei `downloads/Fusch-2.9.apk` passen. Sie ist immer als Download verfügbar.
- `/api/release` fragt die neuesten veröffentlichten GitHub-Releases des
  **eigenen** Repos ab (Cache: 10 Minuten). Nur ein Release ab v2.9 mit
  entsprechend benannter APK und sicherem GitHub-Download-Link darf die
  angezeigte Version und den Download-Link ersetzen. Für v2.9 müssen Größe
  und, falls von GitHub geliefert, SHA-256 zur lokalen APK passen. Ein Fehler
  führt zum gekennzeichneten lokalen Fallback, nicht zu einem falschen
  „Live“-Status.
- Neue Screenshots liegen unter `public/img/fusch-2.9-*.webp`. Sie wurden aus
  `app/assets/index.html` aufgenommen, dessen Datei **bytegleich in der
  signierten 2.9-APK enthalten ist**. Die Browser-Testumgebung liefert
  Beispieldaten über die native Bridge; es sind keine Geräteaufnahmen und
  kein real laufender Spoof. Keine generierten oder veralteten App-Bilder.
- Auf der interaktiven Online-Karte sind Punkte, Zoom und Stadtwechsel
  bedienbar. Die Browser-Ortung wird nur nach Klick auf „Mein Standort“
  angefragt. Die Website selbst simuliert **keinen** Android-Standort.

## Wichtig: zwei Signatur-Linien

Die bisherige APK unter `public/downloads/Fusch-latest.apk` ist **2.5 / Code 18**.
Die ältere App fragt weiterhin `public/update.json` ab. **Beide Dateien bleiben
unverändert**, damit keine 2.5-Installation ein nicht installierbares „Update“
auf die anders signierte 2.9 angeboten bekommt. Die Website bewirbt nur
`downloads/Fusch-2.9.apk` bzw. das zugehörige GitHub-Release. Wer von 2.5 auf
2.9 wechselt, muss die alte App deinstallieren und verliert deren lokale Daten.
Die separat signierten 2.6–2.8 sind mit 2.9 update-kompatibel.

Ein späterer, mit demselben **neuen** Schlüssel signierter Release braucht einen
höheren `versionCode`, ein neues GitHub-Release samt APK und eine aktualisierte
lokale `release.json` plus verifizierte Website-APK als Offline-Fallback.
`update.json` für die **alte Signatur** nur dann ändern, wenn auch eine damit
kompatible APK mit dem ursprünglichen Schlüssel gebaut wurde.

## Screenshots reproduzieren

Ausgehend vom Repository-Root:

```bash
# Lokale Werkzeuge: Chromium, ImageMagick und Playwright Core (nur zum Aufnehmen)
npm install --prefix website --no-save --no-package-lock playwright-core
node website/tools/capture-screenshots.cjs
```

Chromium liegt standardmäßig unter `/usr/bin/chromium`; alternativ
`CHROME_PATH=/pfad/zu/chromium` setzen. Das Skript startet einen lokalen
Asset-Server, rendert die aktuelle App-UI in mobiler Auflösung, wartet beim
Kartenscreenshot auf echte Kartenkacheln und speichert sechs komprimierte
WebP-Aufnahmen. Die App muss dabei nicht installiert sein. Bitte Fotos vom
Gerät nicht als durch dieses Skript erzeugt bezeichnen.

## Technische Hinweise

- `index.html`, `release.json` und das Bestands-`update.json` werden mit
  `no-cache` ausgeliefert; `/api/release` mit `no-store`.
- APKs werden als Download mit korrektem MIME-Type und `Content-Disposition`
  ausgeliefert.
- Google/andere Dienste, die einen Standort trotz Mock-Modus erkennen,
  können von der Website oder App nicht umgangen werden.
