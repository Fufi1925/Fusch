#!/usr/bin/env bash
# ============================================================
#  Fusch – APK Build (aapt2 + javac + d8 + apksigner)
#  Voraussetzungen: JDK 11+, Android Build-Tools 34 + Platform 34
#  unter ~/buildtools/sdk (siehe README.md)
# ============================================================
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"        # Fusch/app
ROOT="$(dirname "$PROJ")"                    # Fusch/

SDK=~/buildtools/sdk/android-14              # build-tools 34
PLAT=~/buildtools/sdk/android-34/android.jar # platform android-34
BUILD=$PROJ/build
OUTDIR=$PROJ/apk
OUT=$OUTDIR/Fusch-latest.apk

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUTDIR"
chmod +x "$SDK/aapt2" "$SDK/zipalign" 2>/dev/null || true

echo "== aapt2 compile =="
"$SDK/aapt2" compile --dir "$PROJ/res" -o "$BUILD/res.zip"

echo "== aapt2 link =="
"$SDK/aapt2" link -o "$BUILD/base.apk" \
  -I "$PLAT" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  "$BUILD/res.zip"

echo "== javac =="
find "$PROJ/src" "$BUILD/gen" -name "*.java" > "$BUILD/sources.txt"
javac -encoding UTF-8 -classpath "$PLAT" -d "$BUILD/classes" @"$BUILD/sources.txt" 2>&1 | grep -v "^Note:" || true

echo "== d8 =="
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
# shellcheck disable=SC2046
java -cp "$SDK/lib/d8.jar" com.android.tools.r8.D8 --release \
  --lib "$PLAT" --min-api 26 \
  --output "$BUILD/dex" $(cat "$BUILD/classes.txt")

echo "== package =="
if unzip -v "$BUILD/base.apk" | grep resources.arsc | grep -qv Stored; then
  echo "  fixing resources.arsc compression"
  mkdir -p "$BUILD/arsc" && (cd "$BUILD/arsc" && unzip -qo "$BUILD/base.apk" resources.arsc)
  zip -q -d "$BUILD/base.apk" resources.arsc
  (cd "$BUILD/arsc" && zip -q -X -0 "$BUILD/base.apk" resources.arsc)
fi
(cd "$BUILD/dex" && zip -q -X "$BUILD/base.apk" classes.dex)

echo "== zipalign =="
"$SDK/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "== sign =="
mkdir -p "$PROJ/keys"
if [ ! -f "$PROJ/keys/cert.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$PROJ/keys/key.pem" -out "$PROJ/keys/cert.pem" \
    -days 10950 -subj "/CN=Fusch Debug/O=Fufi-IL/C=DE" 2>/dev/null
  openssl pkcs8 -topk8 -inform PEM -outform DER -in "$PROJ/keys/key.pem" \
    -out "$PROJ/keys/key.pkcs8" -nocrypt
fi
java -jar "$SDK/lib/apksigner.jar" sign \
  --key "$PROJ/keys/key.pkcs8" --cert "$PROJ/keys/cert.pem" \
  --out "$OUT" "$BUILD/aligned.apk"

echo "== verify =="
java -jar "$SDK/lib/apksigner.jar" verify --print-certs "$OUT" | head -3

# APK automatisch in die Website übernehmen (Download-Link immer aktuell)
if [ -d "$ROOT/website/public/downloads" ]; then
  cp "$OUT" "$ROOT/website/public/downloads/Fusch-latest.apk"
  echo "== kopiert nach website/public/downloads/Fusch-latest.apk =="
fi

ls -la "$OUT"
echo "DONE: $OUT"
