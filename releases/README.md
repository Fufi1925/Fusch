# Separat signierte Fusch-APK

`Fusch-2.9-ohne-Kapsel.apk` ist Fusch **2.9 / VersionCode 22**, signiert mit dem
separaten, wiederverwendbaren Release-Zertifikat. Die Paket-ID lautet
`com.spoofgps.app`.

Diese APK aktualisiert nur die mit **demselben separaten Schlüssel** signierten
Versionen 2.6, 2.7 und 2.8. Sie kann die ursprünglich über die Website
veröffentlichte APK (2.5 / Code 18) **nicht** direkt aktualisieren: Zuerst müsste
diese deinstalliert werden, wodurch ihre lokalen App-Daten verloren gehen.

Die Website bietet diese APK bytegleich als `website/public/downloads/Fusch-2.9.apk`
als einzigen beworbenen Download an; das GitHub-Release `v2.9` erhält ebenfalls
dieses Artefakt. Für die alten Installationen bleiben der ältere URL-Pfad
`website/public/downloads/Fusch-latest.apk` und das Bestands-Update-Manifest
`website/public/update.json` **unverändert bei 2.5 / Code 18**. Sie dürfen nicht
auf die anders signierte 2.9 zeigen. Der private Signaturschlüssel gehört
**nicht** ins GitHub-Repo; `app/keys/` ist ignoriert und muss separat sicher
aufbewahrt werden.
