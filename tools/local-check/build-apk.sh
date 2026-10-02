#!/bin/bash
# Assemble un APK de vérification SANS Android Gradle Plugin, avec les outils Debian/Ubuntu :
#   apt-get install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange
# Le build officiel reste ./gradlew :app:assembleDebug (Android Studio / CI).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out/apk"
ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
rm -rf "$OUT"; mkdir -p "$OUT/dex"

echo "== Compilation Kotlin (cœur + application)"
(cd "$HERE" && gradle --no-daemon -q :appcheck:compileKotlin)
CLASSES="$HERE/appcheck/build/classes/kotlin/main"
STDLIB="$(find ~/.gradle/caches/modules-2 -name 'kotlin-stdlib-2.0.21.jar' | head -1)"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib introuvable"; exit 1; }

echo "== Dex"
# dx ne comprend pas les module-info (Java 9+) : on les retire de la stdlib.
mkdir -p "$OUT/stdlib" && (cd "$OUT/stdlib" && unzip -q "$STDLIB" && rm -rf META-INF/versions)
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$CLASSES" "$OUT/stdlib"

echo "== Ressources"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.cheval.game" android:versionCode="1" android:versionName="1.0">\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />|' \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f -M "$OUT/AndroidManifest.xml" -S "$ROOT/app/src/main/res" -I "$ANDROID_JAR" -F "$OUT/cheval-unsigned.apk"
(cd "$OUT/dex" && aapt add "$OUT/cheval-unsigned.apk" classes.dex >/dev/null)

echo "== Alignement et signature (clé de debug locale)"
zipalign -f 4 "$OUT/cheval-unsigned.apk" "$OUT/cheval-aligned.apk"
KS="$HERE/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/cheval-debug.apk" "$OUT/cheval-aligned.apk"
apksigner verify "$OUT/cheval-debug.apk"
ls -la "$OUT/cheval-debug.apk"
