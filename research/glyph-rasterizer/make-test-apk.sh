#!/usr/bin/env bash
# Adds out/classes3.dex to an APK that Morphe already patched, then re-signs it with a debug key.
#
# The Morphe output already has classes.dex AND classes2.dex (the extension), so the new dex
# must be classes3.dex. Overwriting classes2.dex crashes the app with NoClassDefFoundError X.0D7.
#
# Usage: UBER_APK_SIGNER_JAR=... ./make-test-apk.sh morphe-patched.apk
#        Output: out/test-aligned-debugSigned.apk
set -euo pipefail
cd "$(dirname "$0")"
: "${UBER_APK_SIGNER_JAR:?set UBER_APK_SIGNER_JAR}"
input="$1"

cp "$input" out/test.apk
zip -q -d out/test.apk 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.MF' || true
(cd out && zip -q test.apk classes3.dex)
java -jar "$UBER_APK_SIGNER_JAR" -a out/test.apk -o out >/dev/null
echo "Built out/test-aligned-debugSigned.apk (debug key: uninstall the Morphe-signed app first)"
