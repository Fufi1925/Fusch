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
  **https://fusch.up.railway.app** ✅

> Wenn `fusch` vergeben sein sollte: erst den Service umbenennen
> (Settings → Service Name = `fusch`), dann Domain generieren.

### 5. Fertig – Update-Flow
- Die App prüft bei jedem Start `https://fusch.up.railway.app/update.json`
- Neue Version veröffentlichen:
  1. `bash app/build.sh`  (APK wird automatisch in `website/public/downloads/` gelegt)
  2. `website/public/update.json` → `version`, `code`, `notes`, `date` anpassen
  3. `git add . && git commit -m "Release 1.x" && git push`
  4. Railway baut neu (~1 Minute) → User bekommen das Update-Pop-up ✅

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
