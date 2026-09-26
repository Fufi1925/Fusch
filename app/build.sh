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
DEV_BUILD=${FUSCH_DEV_BUILD:-0}
STANDALONE_BUILD=${FUSCH_STANDALONE_BUILD:-0}
KEY_DIR=$PROJ/keys
OUT=$OUTDIR/Fusch-latest.apk

if [ "$DEV_BUILD" = 1 ] && [ "$STANDALONE_BUILD" = 1 ]; then
  echo "Entweder Test-Build oder eigenständig signierter Build, nicht beides." >&2
  exit 1
fi
if [ "$DEV_BUILD" = 1 ]; then
  KEY_DIR=$BUILD/dev-keys
  OUT=$OUTDIR/Fusch-dev.apk
elif [ "$STANDALONE_BUILD" = 1 ]; then
  OUT=$OUTDIR/Fusch-standalone.apk
fi
if [ "$DEV_BUILD" != 1 ] && { [ ! -f "$KEY_DIR/key.pkcs8" ] || [ ! -f "$KEY_DIR/cert.pem" ]; }; then
  echo "Signaturschlüssel fehlt in app/keys/ (key.pkcs8 und cert.pem)." >&2
  echo "Für eine temporäre Test-APK: FUSCH_DEV_BUILD=1 bash app/build.sh." >&2
  exit 1
fi

# Only a regular release may replace the website APK. Its signing identity
# MUST match the published APK; a standalone build deliberately skips the
# comparison but is NEVER copied to the website or served as an update.
PUBLISHED=$ROOT/website/public/downloads/Fusch-latest.apk
if [ "$DEV_BUILD" != 1 ] && [ "$STANDALONE_BUILD" != 1 ] && [ -f "$PUBLISHED" ]; then
  expected=$(java -jar "$SDK/lib/apksigner.jar" verify --print-certs "$PUBLISHED" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
  actual=$(openssl x509 -in "$KEY_DIR/cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "Signatur passt nicht zur veröffentlichten APK – Build abgebrochen." >&2
    exit 1
  fi
fi

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
javac -encoding UTF-8 -classpath "$PLAT" -d "$BUILD/classes" @"$BUILD/sources.txt"

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
if [ "$DEV_BUILD" = 1 ]; then
  mkdir -p "$KEY_DIR"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$KEY_DIR/key.pem" -out "$KEY_DIR/cert.pem" \
    -days 365 -subj "/CN=Fusch Development/O=Fufi-IL/C=DE" 2>/dev/null
  openssl pkcs8 -topk8 -inform PEM -outform DER -in "$KEY_DIR/key.pem" \
    -out "$KEY_DIR/key.pkcs8" -nocrypt
fi
java -jar "$SDK/lib/apksigner.jar" sign \
  --key "$KEY_DIR/key.pkcs8" --cert "$KEY_DIR/cert.pem" \
  --out "$OUT" "$BUILD/aligned.apk"

echo "== verify =="
java -jar "$SDK/lib/apksigner.jar" verify --print-certs "$OUT" | head -3

# New/dev signatures must NEVER replace the published APK: existing users
# could not install the result as an update.
if [ "$DEV_BUILD" = 1 ]; then
  echo "== Test-APK: nicht update-kompatibel und NICHT auf die Website kopiert =="
elif [ "$STANDALONE_BUILD" = 1 ]; then
  echo "== Neue Signatur: eigenständige APK, NICHT auf die Website kopiert =="
elif [ -d "$ROOT/website/public/downloads" ]; then
  cp "$OUT" "$PUBLISHED"
  echo "== kopiert nach website/public/downloads/Fusch-latest.apk =="
fi

ls -la "$OUT"
echo "DONE: $OUT"
