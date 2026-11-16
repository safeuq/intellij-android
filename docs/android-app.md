# Android app

The split-mode client as an Android app. It compiles the desktop client's shared code (`client/src/main/kotlin`: connection, session, UI; see [`client.md`](client.md)) and adds only the Android pieces: the connect screen (link prompt, backends on this phone, history; Termux commands), fonts and icons from assets, and the soft keyboard hook. How it works and what was tested: [`split-protocol.md`](split-protocol.md), "The Android app (step 5)".

## Build

Needs:
- the Android SDK (`ANDROID_HOME`); AGP downloads platform 37 itself;
- Java 17 or newer to run Gradle; IntelliJ's bundled JBR works;
- the Gradle distribution (9.6.0) on disk. The wrapper's own download failed on the Mac: `Downloading from https://services.gradle.org/distributions/gradle-9.6.0-bin.zip failed: timeout (10000ms)` (the zip is served from github.com, which Java on that Mac doesn't reach), while `curl -L` of the same URL worked. Fetch it with curl and put it where the wrapper looks: `~/.gradle/wrapper/dists/gradle-9.6.0-bin/<hash>/gradle-9.6.0-bin.zip` (the `<hash>` folder is the one the failed run created);
- JetBrains Client **263.7307.200**, the same build as the backend. The build takes the protocol jars and the fonts from it, and every SVG from its `lib/` and `plugins/` jars into one `icons.jar` asset (about 6,100 icons, 4.5 MB compressed): the backend names icons by path, and plugin icons (Kotlin's file and class icons are in `intellij.kotlin.base.resources.jar`) aren't in `intellij.platform.icons.jar`. Before this, Kotlin files and other plugin icons showed no icon. The platform jar goes first, so its icons win when two jars have the same path.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export JETBRAINS_CLIENT_LIB="$HOME/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib"
cd client/android
../gradlew assembleDebug        # build/outputs/apk/debug/intellij-android-app-debug.apk, about 43 MB
adb install -r build/outputs/apk/debug/intellij-android-app-debug.apk
```

The protocol jars are patched for ART on the way (class file version, `sun.misc.Unsafe` in rd's `UnsafeBuffer`); `client/android/rd-patch/` holds the shim.

## Run

The app opens on the connect screen ([`connections.md`](connections.md)):

| Part | What it does |
|---|---|
| Join link | Paste the link the backend printed, optionally an absolute file path on the backend, and tap **Connect**. |
| On this phone | Backends running here: from Termux (`intellij-backend status --json`, once the app may run Termux commands), or any of the ports 127.0.0.1:5990–5999 that accept a connection. A bare port needs its link pasted. |
| History | Connections that worked, newest first. A tap connects again with the stored link and client identity, also after the app was closed. When the backend has restarted, the old link is refused: paste the new one, and the entry is updated. Termux entries ask Termux for the current link, and offer `intellij-backend start <project>` when none is running. |

| Backend | Join link |
|---|---|
| in Termux on the same phone | as printed, `tcp://127.0.0.1:5990#jt=…&fp=…` |
| on a computer, phone or emulator on USB | as printed, after `adb reverse tcp:5990 tcp:5990` |

Termux commands need `allow-external-apps = true` in `~/.termux/termux.properties` (then `termux-reload-settings`); the app asks for Termux's "Run commands in Termux environment" permission when you tap the Termux row.

For scripted runs, the link and file can come as extras; the app then connects at once:

```sh
adb shell "am start -S -n intellijandroid.client.android/.MainActivity --es joinLink 'tcp://127.0.0.1:5990#jt=…&fp=…' --es openFile '/path/on/backend/Main.java'"
```

The client's log (connection steps, errors) goes to logcat under `System.out`: `adb logcat -s System.out`.
