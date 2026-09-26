# Fusch-Website auf Railway deployen (Free Account)

Die Website ist bewusst **komplett statisch + abhängigkeitsfrei** (nur Node.js
Standard-Bibliothek) – läuft auf Railways Free/Standby-Plan problemlos.

## Schritt für Schritt

### 1. Code nach GitHub pushen (privates Repo)
```bash
cd Fusch
git init
git add .
git commit -m "Fusch – App + Website"
git branch -M main
git remote add origin https://github.com/<DEIN-NAME>/<REPO-NAME>.git
git push -u origin main
```
Auf GitHub: Repo-Settings → **Danger Zone → Change visibility → Private**.

### 2. Railway Project anlegen
1. [railway.app](https://railway.app) öffnen → **Login with GitHub**
2. **New Project** → **Deploy from GitHub repo** → dein privates Repo wählen
   (beim ersten Mal: GitHub-App installieren & Repo-Zugriff erlauben)

### 3. Root Directory setzen
- Service anklicken → **Settings** → **Build/Deploy**
- **Root Directory** = `website`  ← wichtig! (sonst findet Railway kein package.json)
- Railway deployed automatisch (Nixpacks, Start: `node server.js`)

### 4. Domain holen
- Service → **Settings → Networking → Generate Domain**
- Als Subdomain **`fusch`** eintragen → Ergebnis:
  **https://fusch.up.railway.app**

> Wenn `fusch` vergeben sein sollte: erst den Service umbenennen
> (Settings → Service Name = `fusch`), dann Domain generieren.

### 5. Fertig – Update-Flow
- Die App prüft bei jedem Start `https://fusch.up.railway.app/update.json`
- Neue Version veröffentlichen:
  1. `app/AndroidManifest.xml` → Version und Versionscode erhöhen.
  2. **Originale** Signaturdateien unter `app/keys/` bereitstellen und `bash app/build.sh`
     ausführen (Test-Builds werden aus gutem Grund nie hierher kopiert).
  3. `website/public/update.json` → `version`, `code`, `notes`, `date` anpassen.
  4. `git add . && git commit -m "Release 2.x" && git push`.
  5. Railway baut neu (~1 Minute) → User bekommen das Update-Pop-up.

> Ohne den ursprünglichen privaten Schlüssel ist keine update-kompatible APK
> für bestehende Website-Installationen möglich. Die neue separat signierte APK
> (2.9 / Code 22) lässt sich mit `FUSCH_STANDALONE_BUILD=1 bash app/build.sh`
> bauen, wird aber **nicht** auf die Website kopiert. `update.json` und der
> Website-Download bleiben bei 2.5 / Code 18. Die neue APK lässt sich wegen der
> anderen Signatur nur nach Deinstallation der alten App installieren (lokale
> App-Daten gehen dabei verloren). `FUSCH_DEV_BUILD=1` ist nur ein Test-Build.

## Lokal testen
```bash
cd website
node server.js          # http://localhost:3000
```

## Hinweise
- `update.json` und `index.html` werden mit `no-cache` ausgeliefert –
  Änderungen sind sofort für alle sichtbar.
- Die `.apk` wird mit `Content-Disposition: attachment` gesendet,
  der Browser startet den Download also direkt.
- Railway Free ("Trial") braucht irgendwann eine Zahlungsmethode im Hub;
  der Ressourcenverbrauch dieser Site ist minimal (paar MB RAM).
