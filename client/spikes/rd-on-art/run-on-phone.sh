#!/usr/bin/env bash
# Checks that JetBrains' rd library and the generated split-mode protocol models run on Android ART.
# See docs/split-protocol.md, section "Running rd and the models on Android".
#
# Builds, on the Mac:
#   rd-framework-art.jar   rd-framework with UnsafeBuffer's sun.misc.Unsafe uses routed to ArtUnsafeShim
#   *-v61.jar              the generated model jars with class file version lowered from 69 to 61 for D8
#   checks-dex.jar         all of the above plus the check programs, dexed, with the marshaller resource files
# then runs each check on the phone with app_process, as the ADB shell user in /data/local/tmp (Termux is not touched).
#
# Environment:
#   CLIENT_LIB     JetBrains Client 263.7307.200 Contents/lib directory (rd, models, ASM, coroutines)
#   KOTLIN_STDLIB  a kotlin-stdlib 2.x jar
#   JAVA_HOME      JDK 17+
#   ADB_SERIAL     the phone, e.g. 192.168.0.105:5555
#   ANDROID_SDK    default ~/Library/Android/sdk; needs build-tools/35.0.0 and platforms/android-35
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
: "${CLIENT_LIB:?set CLIENT_LIB to the JetBrains Client Contents/lib directory}"
: "${KOTLIN_STDLIB:?set KOTLIN_STDLIB to a kotlin-stdlib jar}"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17+}"
: "${ADB_SERIAL:?set ADB_SERIAL, e.g. 192.168.0.105:5555}"
: "${ANDROID_SDK:=$HOME/Library/Android/sdk}"

rd_core="$CLIENT_LIB/intellij.libraries.rd.core.jar"
rd_framework="$CLIENT_LIB/intellij.libraries.rd.framework.jar"
rd_text="$CLIENT_LIB/intellij.libraries.rd.text.jar"
coroutines="$CLIENT_LIB/intellij.libraries.kotlinx.coroutines.core.jar"
asm="$CLIENT_LIB/intellij.libraries.asm.jar"
split_protocol="$CLIENT_LIB/intellij.platform.split.protocol.jar"
model_generated="$CLIENT_LIB/intellij.rd.ide.model.generated.jar"
d8="$ANDROID_SDK/build-tools/35.0.0/d8"
android_jar="$ANDROID_SDK/platforms/android-35/android.jar"
for required in "$rd_core" "$rd_framework" "$rd_text" "$coroutines" "$asm" "$split_protocol" "$model_generated" \
                "$KOTLIN_STDLIB" "$d8" "$android_jar"; do
  [[ -f "$required" ]] || { echo "missing: $required" >&2; exit 1; }
done

build="$here/build"
rm -rf "$build"
mkdir -p "$build/classes" "$build/patch" "$build/dex" "$build/resources"

# 1. Lower the model jars' class file version (69 = Java 25) to 61 (Java 17): D8 35.0.0 rejects 69.
python3 -I "$here/downgrade-class-version.py" "$split_protocol" "$build/split-protocol-v61.jar"
python3 -I "$here/downgrade-class-version.py" "$model_generated" "$build/model-generated-v61.jar"

# 2. Compile the shim, the rewriter, the checks and the probes.
classpath="$asm:$rd_core:$rd_framework:$coroutines:$KOTLIN_STDLIB:$build/split-protocol-v61.jar:$build/model-generated-v61.jar"
find "$here/src" -name '*.java' > "$build/sources.txt"
"$JAVA_HOME/bin/javac" --release 17 -nowarn -XDignore.symbol.file -d "$build/classes" -cp "$classpath" @"$build/sources.txt" 2>&1 \
  | grep -v -e '^Note' -e '^import ' -e 'internal proprietary API' -e '^ ' -e 'warnings\?$' -e '^\S*\.java:[0-9]*: warning' || true

# 3. Patch UnsafeBuffer inside a copy of rd-framework and add the shim.
cp "$rd_framework" "$build/rd-framework-art.jar"
(cd "$build/patch" && unzip -oq "$rd_framework" 'com/jetbrains/rd/framework/UnsafeBuffer*.class')
for class_file in "$build"/patch/com/jetbrains/rd/framework/UnsafeBuffer*.class; do
  "$JAVA_HOME/bin/java" -cp "$build/classes:$asm" RewriteUnsafeCalls "$class_file" "$class_file"
done
cp "$build/classes/com/jetbrains/rd/framework/ArtUnsafeShim.class" "$build/patch/com/jetbrains/rd/framework/"
(cd "$build/patch" && zip -q "$build/rd-framework-art.jar" com/jetbrains/rd/framework/*.class)

# 4. Dex everything into one jar and add the marshaller lists the models load as resources.
(cd "$build/classes" && "$JAVA_HOME/bin/jar" cf "$build/checks.jar" \
  RdOnAndroidCheck.class ModelsOnAndroidCheck.class CryptoProbe.class UnsafeProbe.class)
JAVA_HOME="$JAVA_HOME" "$d8" --release --min-api 34 --lib "$android_jar" --output "$build/dex" \
  "$build/checks.jar" "$build/rd-framework-art.jar" "$rd_core" "$rd_text" "$coroutines" "$KOTLIN_STDLIB" \
  "$build/split-protocol-v61.jar" "$build/model-generated-v61.jar" >"$build/d8.log" 2>&1 \
  || { echo "d8 failed, see $build/d8.log" >&2; exit 1; }
(cd "$build/resources" \
  && unzip -oq "$split_protocol" 'intellij.platform.split.protocol.marshallers/*' \
  && unzip -oq "$model_generated" 'marshallers/*')
(cd "$build/dex" && zip -q "$build/checks-dex.jar" classes*.dex)
(cd "$build/resources" && zip -qr "$build/checks-dex.jar" .)

# 5. Run on the phone.
adb -s "$ADB_SERIAL" push "$build/checks-dex.jar" /data/local/tmp/checks-dex.jar >/dev/null
for program in UnsafeProbe CryptoProbe RdOnAndroidCheck ModelsOnAndroidCheck; do
  echo "== $program"
  adb -s "$ADB_SERIAL" shell "CLASSPATH=/data/local/tmp/checks-dex.jar timeout 60 app_process /data/local/tmp $program 2>&1 | grep -v -e '^Debug' -e '^Info '"
done
adb -s "$ADB_SERIAL" shell rm -f /data/local/tmp/checks-dex.jar
