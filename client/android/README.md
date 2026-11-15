# Android app

The split-mode client as an Android app. It compiles the desktop client's shared code (`../src/main/kotlin`: connection, session, UI) and adds only the Android pieces: the activity with a join-link form, fonts and icons from assets, and the soft keyboard hook. How it works and what was tested: [`docs/split-protocol.md`](../../docs/split-protocol.md), "The Android app (step 5)".

## Build

Needs:
- the Android SDK (`ANDROID_HOME`); AGP downloads platform 37 itself;
- Java 17 or newer to run Gradle; IntelliJ's bundled JBR works;
- JetBrains Client **263.7307.200**, the same build as the backend. The build takes the protocol jars, the icons jar and the fonts from it.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export JETBRAINS_CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib"
cd client/android
../gradlew assembleDebug        # build/outputs/apk/debug/intellij-android-app-debug.apk, about 43 MB
adb install -r build/outputs/apk/debug/intellij-android-app-debug.apk
```

The protocol jars are patched for ART on the way (class file version, `sun.misc.Unsafe` in rd's `UnsafeBuffer`); `rd-patch/` holds the shim.

## Run

Open the app, paste the join link the backend printed, optionally an absolute file path on the backend, and tap **Connect**. The link and path are remembered. Each join link works for one connection only: restart the backend before connecting again.

| Backend | Join link |
|---|---|
| in Termux on the same phone | as printed, `tcp://127.0.0.1:5990#jt=…&fp=…` |
| on a computer, phone or emulator on USB | as printed, after `adb reverse tcp:5990 tcp:5990` |

For scripted runs, the link and file can come as extras; the app then connects at once:

```sh
adb shell "am start -S -n intellijandroid.client.android/.MainActivity --es joinLink 'tcp://127.0.0.1:5990#jt=…&fp=…' --es openFile '/path/on/backend/Main.java'"
```

The client's log (connection steps, errors) goes to logcat under `System.out`: `adb logcat -s System.out`.
