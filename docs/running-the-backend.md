# Running IntelliJ's backend on the phone

In Termux (aarch64), with the start script:

```sh
apt install ./jbr-25_25.0.4.1_aarch64.deb          # installs to $PREFIX/lib/jvm/jbr-25
# IntelliJ IDEA 2026.3, linux-aarch64 build, unpacked to ~/intellij/idea-IU-263.7307.200
# optional: the Android file watcher (backend/fsnotifier/build.sh, see fsnotifier.md) copied to ~/intellij/fsnotifier
install -m 755 backend/termux/intellij-backend $PREFIX/bin/   # copy it to the phone first
intellij-backend setup     # once, and again after installing another IntelliJ; safe to repeat

intellij-backend start ~/path/to/project      # prints the join link once ready; --lan listens on 0.0.0.0
intellij-backend status                       # or: status --json, link
intellij-backend stop
```

`setup` does everything in Termux:

- installs `jq` and `termux-api` if missing;
- moves IntelliJ's `bin/remote-dev-server.sh` to `bin/remote-dev-server.real.sh` and puts a wrapper in its place. The wrapper sets `IDEA_JDK`, `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0` and `TMPDIR` for every IntelliJ command, and runs `run` under `intellij-backend`, so a backend started any way (the app, `start`, Gateway, by hand) gets the notification;
- points IntelliJ at `~/intellij/fsnotifier` in `idea.properties`, when it's there;
- registers the install with JetBrains Gateway.

It then lists the Android settings it can't change: `allow-external-apps` for the app, battery usage Unrestricted for Termux and Termux:API, and "Disable child process restrictions" in Developer options (Android 14+), so Android doesn't kill the backend. The Termux:API app itself comes from the same source as Termux (F-Droid or GitHub).

`intellij-backend` finds the IntelliJ under `~/intellij/idea-*/` (or `$INTELLIJ_HOME`), sets `IDEA_JDK` and `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0` itself, takes Termux's wake lock while the backend runs, shows an ongoing notification with a Stop button (tapping it opens the Android app), and keeps the backend running after the shell or SSH session that started it ends. Logs: `~/.local/state/intellij-backend/`. Status comes from IntelliJ's own files in `~/.cache/JetBrains/RemoteDev/active/`. It refuses to start (exit `4`) while another IntelliJ runs with the same settings, e.g. the desktop IntelliJ.

The same by hand (after `setup`; without it, first `export IDEA_JDK=$PREFIX/lib/jvm/jbr-25` and `export REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0`, else the launcher uses its bundled glibc JBR):

```sh
~/intellij/idea-IU-263.7307.200/bin/remote-dev-server.sh run ~/path/to/project \
  --listenOn 0.0.0.0 \
  --port 5990
```

It prints a `Join link: tcp://0.0.0.0:5990#jt=…`. Open it in JetBrains Gateway with the phone's address instead of `0.0.0.0`.

On macOS, JetBrains Client may be blocked from the local network (`NoRouteToHostException: No route to host`). Either allow it under System Settings → Privacy & Security → Local Network, or tunnel the port and use `tcp://127.0.0.1:5990#…`:

```sh
ssh -p 8022 -N -L 127.0.0.1:5990:127.0.0.1:5990 <phone-address>
```

## From JetBrains Gateway over SSH

Gateway can start the backend on the phone itself and carry the connection inside its SSH session, so no join link and no port tunnel are needed. Tested 2026-11-16 with Gateway 263.7307.184: the backend ran on `jbr-25`, and JetBrains Client opened the project with full highlighting.

Setup in Termux: `intellij-backend setup` (above). It registers the install with Gateway, so Gateway uses it instead of downloading its own (glibc) IntelliJ, and its launcher wrapper sets the JBR for Gateway's commands, so no shell profile is needed. Tested 2026-11-16: a Gateway start went through the wrapper and showed the notification.

Then open this link on the Mac, with the phone's address and SSH port (Termux's `whoami` is the user):

```
jetbrains-gateway://connect#type=ssh&deploy=false&host=<phone-address>&port=8022&user=u0_a404&idePath=%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome%2Fintellij%2Fidea-IU-263.7307.200&projectPath=%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome%2Fintellij%2Fsmoke
```

Or in Gateway: SSH → New Connection, then pick the IntelliJ under `~/intellij` and the project. Gateway keeps the connection in its recent list for later.

What Gateway does on the phone: `uname -sm` (`Linux aarch64`), uploads its worker (a static Go program, runs on Android) to `~/.cache/JetBrains/RemoteDev/remote-dev-worker/`, runs commands as `/bin/sh -lc …` (Termux's `termux-exec` maps `/bin/sh`), starts `remote-dev-server.sh run` and forwards the backend's port over SSH. Its `chanreq for env … Request failed` warnings only mean the SSH server declines `LANG`; harmless.

On macOS, Gateway's own SSH code is also blocked from the local network (`NoRouteToHostException`). Allow Gateway under System Settings → Privacy & Security → Local Network, or forward the phone's SSH port and connect Gateway to `127.0.0.1:8122` (add the phone's host key for `[127.0.0.1]:8122` to `~/.ssh/known_hosts` first, or accept it in Gateway's prompt within 30 seconds):

```sh
ssh -p 8022 -N -L 127.0.0.1:8122:127.0.0.1:8022 <phone-address>
```

## Known limitations

- IntelliJ's own `fsnotifier` gives up on Android (`File watcher gave up to operate`), so changes are found by periodic rescans. Use the Android build instead (see [`fsnotifier.md`](fsnotifier.md)). Projects in shared storage are rescanned either way: it's a FUSE mount, which `fsnotifier` doesn't watch.
- The backend needs about 2.5 GB of RAM.
- A JDK for projects must be added by its real path, e.g. `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk`.
- Projects in shared storage (`~/storage/shared`, `/storage/emulated/0`) and on SD cards can be opened, but prefer Termux's home (`~/…`) for code: shared storage has no symlinks, no executable files (`./gradlew` won't run), case-insensitive file names, and was about 195× slower writing 2,000 small files in a test. For other folders Android won't let Java list on the way down, add them with `-Djdk.nio.fs.unlistableDirectoryHints=/path/one:/path/two` (see `1004` in [`jbr-patches.md`](jbr-patches.md)).
