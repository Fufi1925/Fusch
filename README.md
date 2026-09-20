# Fusch

**Dein Standort. Überall.** – Android-Standort-Tool von **Fufi/!L** (Aleks heißt Fusch).

```
Fusch/
├── app/                  ← Android-App (Quellcode + build.sh)
│   ├── AndroidManifest.xml
│   ├── src/…             ← Java-Code
│   ├── res/…             ← Icons & Themes
│   ├── assets/index.html ← App-UI (Karte, Tabs, Popups)
│   └── build.sh          ← baut apk/Fusch-latest.apk und legt sie
│                            automatisch in die Website (downloads/)
└── website/              ← Railway-Website (https://fusch.up.railway.app)
    ├── server.js         ← statischer Server (keine Abhängigkeiten)
    ├── package.json
    ├── railway.json
    └── public/
        ├── index.html    ← Landingpage
        ├── update.json   ← Versions-Manifest (App prüft diese Datei!)
        └── downloads/
            └── Fusch-latest.apk   ← immer der aktuelle Build
```

## App bauen

Voraussetzungen: JDK 11+, Android Build-Tools 34 + Platform 34 unter `~/buildtools/sdk`
(Siehe unten "SDK einrichten").

```bash
bash app/build.sh
```

Die APK landet in `app/apk/Fusch-latest.apk` **und** wird automatisch nach
`website/public/downloads/Fusch-latest.apk` kopiert.

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
1. Diesen **gesamten Ordner** in ein **privates** GitHub-Repo pushen
2. Auf [railway.app](https://railway.app) mit GitHub einloggen → **New Project → Deploy from GitHub repo**
3. Im Service unter **Settings → Root Directory** = `website` setzen
4. **Settings → Networking → Generate Domain** → Subdomain `fusch` wählen → `https://fusch.up.railway.app`

## Neues Release veröffentlichen

1. `app/build.sh` ausführen (baut + kopiert die APK in die Website)
2. `website/public/update.json` anpassen:
   ```json
   {
     "app": "Fusch",
     "version": "1.4",
     "code": 6,
     "url": "https://fusch.up.railway.app/downloads/Fusch-latest.apk",
     "notes": "• Was ist neu\n• …",
     "date": "2026-09-27"
   }
   ```
3. Committen & pushen → Railway deployt neu → alle installierten Apps
   bekommen beim nächsten Start das Update-Pop-up mit Direktdownload.
