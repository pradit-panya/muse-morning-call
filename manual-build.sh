#!/usr/bin/env bash
# Manual APK build (no Gradle): kotlinc -> aapt2 -> d8 -> zipalign -> apksigner.
# Adapted from voice-alarm-poc/manual-build.sh; this project is pure Android
# framework (no AndroidX / library jars), so only kotlin-stdlib is bundled.
set -euo pipefail
PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
ANDROID_JAR="${ANDROID_PLATFORM_JAR:-$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}"
OUT="$PROJ/manual-build"
GUH="${GRADLE_CACHE_DIR:-$HOME/.gradle/caches/modules-2/files-2.1}"

rm -rf "$OUT"
mkdir -p "$OUT/dex"

KOTLINC_JAR="$(find "$GUH" -name 'kotlin-compiler-embeddable-2.0.20.jar' | head -1)"
STDLIB_JAR="$(find "$GUH" -name 'kotlin-stdlib-2.0.20.jar' | head -1)"
TROVE_JAR="$(find "$GUH" -name 'trove4j-*.jar' | head -1)"
COROUTINES_JAR="$(find "$GUH" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)"
ANNOT_JAR="$(find "$GUH" -path '*org.jetbrains/annotations*' -name 'annotations-*.jar' | head -1)"
echo "kotlinc jar: $KOTLINC_JAR"
echo "stdlib jar: $STDLIB_JAR"

echo "== 1. Kotlin compile =="
java -Xmx512m -cp "$KOTLINC_JAR:$STDLIB_JAR:$TROVE_JAR:$COROUTINES_JAR:$ANNOT_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$ANDROID_JAR:$STDLIB_JAR" \
  -Xskip-metadata-version-check \
  -no-reflect \
  -d "$OUT/app-classes.jar" \
  $(find "$PROJ/app/src/main/java" -name '*.kt')

echo "== 2. aapt2 compile res =="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/res.zip"

echo "== 3. aapt2 link =="
ASSETS_ARG=()
if [ -d "$PROJ/app/src/main/assets" ]; then
  ASSETS_ARG=(-A "$PROJ/app/src/main/assets")
fi
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  "${ASSETS_ARG[@]}" \
  "$OUT/res.zip"

echo "== 4. d8 =="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
  "$OUT/app-classes.jar" "$STDLIB_JAR"

echo "== 5. add dex files =="
python3 - "$OUT/app-unsigned.apk" "$OUT/dex" <<'PY'
import sys, zipfile, glob, os
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(os.path.join(dexdir, "*.dex"))):
        z.write(dex, os.path.basename(dex))
        print("added", os.path.basename(dex))
PY

echo "== 6. zipalign =="
"$BT/zipalign" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 7. sign =="
# Keystore lives OUTSIDE $OUT (wiped each build) so the signature stays stable
# across builds; a regenerated key breaks `install -r` updates.
KS="$PROJ/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/FakeCall.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$OUT/FakeCall.apk"
ls -la "$OUT/FakeCall.apk"
echo "BUILD_OK"
