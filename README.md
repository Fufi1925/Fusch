# Fusch

**Dein Standort. Überall.** – Android-Standort-Tool von **Fufi/!L** (Aleks heißt Fusch).

```
Fusch/
├── app/                  ← Android-App (Quellcode + build.sh)
│   ├── AndroidManifest.xml
│   ├── src/…             ← Java-Code
│   ├── res/…             ← Icons & Themes
│   ├── assets/index.html ← App-UI (Karte, Tabs, Popups)
│   ├── assets/icons.js   ← einfarbig graue SVG-Symbole
│   ├── assets/vendor/    ← Leaflet lokal, kein externer Skript-CDN
│   ├── keys/             ← neuer privater Signaturschlüssel (ignoriert; sicher aufbewahren)
│   └── build.sh          ← alter Website-Release nur mit Originalschlüssel; neue APK separat
└── website/              ← Railway-Website (https://fusch.up.railway.app)
    ├── server.js         ← statische Dateien + GitHub-Release-Status (keine Abhängigkeiten)
    ├── tools/            ← reproduzierbare Aufnahmen der App-UI mit Beispieldaten
    ├── package.json
    ├── railway.json
    └── public/
        ├── index.html    ← Landingpage für die neueste separat signierte APK
        ├── release.json  ← geprüfter Fallback-Stand 2.9 / Code 22
        ├── update.json   ← Bestands-Manifest 2.5 / Code 18 (nicht für 2.9 ändern!)
        ├── img/          ← neue Aufnahmen der aktuellen App-Oberfläche
        ├── vendor/       ← Leaflet für die interaktive Online-Karte
        └── downloads/
            ├── Fusch-2.9.apk    ← einziger beworbener Download
            └── Fusch-latest.apk ← Bestands-APK 2.5 für bisherige Update-URLs
```

## App bauen

Voraussetzungen: JDK 11+, Android Build-Tools 34 + Platform 34 unter `~/buildtools/sdk`
(siehe unten „SDK einrichten“).

**Neue separate APK (Version 2.9 / Code 22):** Der dauerhaft verwendbare neue
Signaturschlüssel liegt unter `app/keys/key.pkcs8`, das Zertifikat unter
`app/keys/cert.pem`. Die beiden Dateien sind aus Git ausgeschlossen und müssen
**gemeinsam sicher und dauerhaft gesichert** werden. Besonders `key.pkcs8` ist
privat und unverschlüsselt: niemals hochladen oder in ein Repository committen.
Für alle späteren APKs mit dieser Signatur **dieselben Dateien** verwenden:

```bash
FUSCH_STANDALONE_BUILD=1 bash app/build.sh
```

Ein neuer Build unter `app/apk/Fusch-standalone.apk` wird **nicht automatisch**
auf die Website kopiert. Die geprüfte, separat signierte 2.9-APK liegt unter
[`releases/Fusch-2.9-ohne-Kapsel.apk`](releases/Fusch-2.9-ohne-Kapsel.apk)
und bytegleich als **einziger sichtbarer Website-Download** unter
`website/public/downloads/Fusch-2.9.apk`. Der ältere Pfad
`downloads/Fusch-latest.apk` und `update.json` bleiben ausschließlich für
bestehende 2.5-Installationen unverändert erhalten.
Diese neue APK hat dieselbe Paket-ID, aber **eine andere Signatur**: Sie kann
**nicht** über die alte App installiert werden. Vor Installation muss die alte
App deinstalliert werden; dabei gehen ihre lokal gespeicherten App-Daten verloren.
Die Version 2.9 / Code 22 kann die separat gelieferten Versionen 2.6 / Code 19,
2.7 / Code 20 und 2.8 / Code 21 **direkt aktualisieren**, weil alle denselben
neuen Signaturschlüssel nutzen. Spätere APKs mit diesem Schlüssel können diese Installation aktualisieren,
sofern der `versionCode` jeweils weiter erhöht wird. Eine automatische Umstellung
der bestehenden Website-Nutzer ist damit nicht möglich.

**Alten Website-Release aktualisieren:** Das wäre ausschließlich mit dem
**ursprünglichen** privaten Schlüssel und passenden Zertifikat möglich:

```bash
bash app/build.sh
```

Das Skript prüft die Signatur gegen die veröffentlichte APK und bricht mit dem
**neuen** Schlüssel ab, bevor es den Download überschreiben kann. Nur mit dem
alten Originalschlüssel würde es `app/apk/Fusch-latest.apk` auch nach
`website/public/downloads/Fusch-latest.apk` kopieren.

**Temporäre Test-APK:** Zum lokalen Testen ohne dauerhaften Schlüssel:

```bash
FUSCH_DEV_BUILD=1 bash app/build.sh
```

Diese liegt in `app/apk/Fusch-dev.apk`, bekommt bei jedem Build eine neue
Testsignatur und wird ebenfalls **nie** auf die Website kopiert.

### SDK einrichten (einmalig)
```bash
mkdir -p ~/buildtools && cd ~/buildtools
curl -fsSL -o bt.zip   https://dl.google.com/android/repository/build-tools_r34-linux.zip
curl -fsSL -o plat.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -q bt.zip -d sdk && unzip -q plat.zip -d sdk
```

## Website deployen (Railway, kostenlos)

→ Details: [website/README-RAILWAY.md](website/README-RAILWAY.md)

Kurzform:
1. Dieses Projekt im GitHub-Repo [`Fufi1925/Fusch`](https://github.com/Fufi1925/Fusch) verwenden
2. Auf [railway.app](https://railway.app) mit GitHub einloggen → **New Project → Deploy from GitHub repo**
3. Im Service unter **Settings → Root Directory** = `website` setzen
4. **Settings → Networking → Generate Domain** → Subdomain `fusch` wählen → `https://fusch.up.railway.app`

## Neues Release veröffentlichen

Die Website bewirbt **2.9 / Code 22 mit separater Signatur** als aktuellen
Download. Das alte 2.5-Paket und sein Update-Manifest bleiben unter den
bisherigen URLs erhalten, werden aber nicht mehr auf der Website angeboten.
Diese Version entfernt die schwebende „Dynamic Island“-Kapsel,
ihren Einstellungsschalter und die Overlay-Berechtigung vollständig. Bei
aktivem Standortspoof oder aktiver Route zeigt Fusch nur den internen
Statusbildschirm mit **Stoppen**; Einstellungen, Favoriten, Verlauf und
Kartenaktionen sind bis zum Stop gesperrt. Nach einem App-Neustart wird der
Laufzeitstatus wiederhergestellt. Die notwendige Android-Dauerbenachrichtigung
mit Stop-Aktion bleibt erhalten.

Favoriten und Verlauf sind in gruppierten Karten im Stil der Einstellungen
aufgebaut. Einträge lassen sich suchen (bei längeren Listen), sortieren,
einzeln löschen; Favoriten außerdem umbenennen. Das Löschen aller Einträge
braucht eine Bestätigung. Verlaufseinträge werden erst nach erfolgreichem
Start des Spoofs gespeichert; Listenänderungen werden sofort in der App
persistiert. Der fest eingebettete Geo-API-Schlüssel ist unverändert.

Die Website zeigt die 2.9-APK als **Neuinstallation bzw. Upgrade von den
separat signierten 2.6–2.8** und warnt vor der notwendigen Deinstallation von
2.5 mit lokalem Datenverlust. `website/public/update.json` und
`website/public/downloads/Fusch-latest.apk` **nicht** auf 2.9 umstellen:
Andernfalls würde die alte App ein nicht installierbares Update anbieten.
Die Landingpage fragt `/api/release` ab: Ein passendes GitHub-Release mit APK
wird höchstens alle zehn Minuten neu abgefragt; bei Ausfall bleibt die lokal
per SHA-256 geprüfte 2.9-APK als Download verfügbar. Für ein späteres Release
müssen Website-Kopie und `release.json` bewusst mitgezogen werden, wenn der
Offline-Fallback nicht auf 2.9 bleiben soll.

Für einen weiteren **separaten** Build den `versionCode` in
`app/AndroidManifest.xml` über 22 erhöhen und erneut mit
`FUSCH_STANDALONE_BUILD=1 bash app/build.sh` und denselben beiden Schlüsseldateien
bauen. Einen Release für die **bisherige Website-Nutzerbasis** kann nur jemand
mit dem alten Originalschlüssel erstellen; danach Version und Code im Manifest
und in `website/public/update.json` aufeinander abstimmen. Die Umstellung auf
den neuen Schlüssel wäre eine eigene Migration, kein normales App-Update.

## UI-Tests

```bash
npm ci --prefix tests
npm --prefix tests test
```

Die Tests prüfen Berechtigungsbuttons, Entwickler- und Akku-Links, die
entfernte Overlay-Funktion und die Migration älterer Einstellungen, den
app-internen Aktivbildschirm samt Aktionssperre, Start/Stop/Wiederherstellung
laufender Routen, Listenaktionen einschließlich sofortiger Speicherung sowie
einfarbige SVG-Symbole. Für ein echtes Android-Gerät zusätzlich prüfen:

1. 2.9 über die separat signierte 2.8 installieren, **ohne** lokale Daten zu
   löschen; vorhandene Favoriten und der Verlauf müssen erhalten bleiben.
2. Die Android-Berechtigungen dürfen erst nach Antippen ihrer jeweiligen
   Buttons angefragt werden. Entwickleroptionen- und Akku-Button müssen die
   korrekten Systemseiten öffnen.
3. Standortspoof starten: In Fusch ist nur der Aktivbildschirm samt Stop-Button
   bedienbar. App verlassen und zurückkehren: Spoof und Status bleiben aktiv,
   keine schwebende Kapsel erscheint. Stoppen gibt die App wieder frei.
4. Route starten, App verlassen und neu öffnen: Fortschritt und Restzeit werden
   wiederhergestellt. Stoppen in Fusch oder per Android-Benachrichtigung muss
   die Route wieder startbar machen. Die Dauerbenachrichtigung bleibt während
   eines aktiven Spoofs erhalten.
5. Favoriten und Verlauf einschließlich Suchen, Sortieren, Umbenennen bzw.
   Löschen testen. Beim Löschen aller Einträge muss vorher eine Bestätigung
   erscheinen.
