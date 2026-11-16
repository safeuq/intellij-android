# IntelliJ's remote-dev server: how it behaves

What we found out about IntelliJ's remote development backend while running it in Termux. Checked 2026-11-15/16 with IntelliJ IDEA 2026.3 (build `263.7307.200`) on `jbr-25` (`25.0.4.1b635.79`), a Galaxy S24+ on Android 17, and JetBrains Gateway `263.7307.184` on macOS. How to use it: [`running-the-backend.md`](running-the-backend.md). The connection side (tokens, reconnects, history): [`connections.md`](connections.md) and [`split-protocol.md`](https://github.com/safeuq/ideate-editor/blob/main/docs/split-protocol.md).

## The launcher

`bin/remote-dev-server.sh` is a small wrapper. It picks one of two launchers:

| Check | Linux with glibc | Android (Termux) |
|---|---|---|
| `getconf GNU_LIBC_VERSION`, `ldd --version` not musl, no gcompat stub | passes | fails |
| launcher used | `bin/remote-dev-server` (native, glibc) | `plugins/remote-dev-server/bin/launcher.sh` |

Then it runs that launcher in a loop: exit code `88` (`IDEA_RESTART_VIA_EXIT_CODE`, an IDE restart, e.g. after installing a plugin) starts it again, any other code ends the loop. So:

- the `remote-dev-server.sh` process lives exactly as long as the backend, restarts included;
- the JVM's pid changes on a restart, so anything watching the JVM's pid sees it end early. `intellij-backend` therefore waits on the launcher process, not on the JVM's pid.

`launcher.sh` on Android needs three things from the environment, all set by `intellij-backend` and its launcher wrapper:

| Setting | Without it |
|---|---|
| `IDEA_JDK=$PREFIX/lib/jvm/jbr-25` | uses the bundled JBR, built for glibc: `exec: /lib/ld-linux-aarch64.so.1: not found`, exit `127` |
| `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0` | uses its bundled X11 and other libraries (`plugins/remote-dev-server/selfcontained/`) through glibc's `/lib/ld-linux-aarch64.so.1`, which Android doesn't have |
| `TMPDIR=$PREFIX/tmp` | `mkdir: cannot create directory '/tmp/pid.30443.temp.fontconfig': Permission denied`; Android has no writable `/tmp`. Termux sets `TMPDIR` in its own sessions, not in a bare environment |

Harmless on every run: `grep: /proc/sys/kernel/osrelease: Permission denied` (Android hides that file).

### Commands

From the launcher's usage text (2026.3):

```
./remote-dev-server.sh run /path/to/project [arguments...]
./remote-dev-server.sh status /path/to/project
./remote-dev-server.sh warm-up /path/to/project [arguments...]
./remote-dev-server.sh installPlugins /path/to/project PLUGIN_ID [PLUGIN_ID] ...
./remote-dev-server.sh registerBackendLocationForGateway
```

Not in the usage text but works: `stop /path/to/project` (asks the running backend to save and exit; the backend logs `Unattended host will be closed soon`).

`run` options (from a backend log; `run --help` on macOS crashed in `ClientToolkit`):

| Option | Default | Example |
|---|---|---|
| `-l`, `--listenOn` | `127.0.0.1` | `--listenOn 0.0.0.0` for the LAN |
| `-p`, `--port` | `5990` | `--port 5991` |
| `--no-join-token` | off | link without `jt` |
| `--ssh-link-host`, `--ssh-link-port`, `--ssh-link-user` | none | put into the printed Gateway link |

Environment variables from the usage text: `REMOTE_DEV_SERVER_TRACE`, `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS`, `REMOTE_DEV_LAUNCHER_NAME_FOR_USAGE`, `REMOTE_DEV_TRUST_PROJECTS` (skip the trust prompt; runs build scripts), `REMOTE_DEV_NEW_UI_ENABLED`, `REMOTE_DEV_NON_INTERACTIVE`, `REMOTE_DEV_JDK_DETECTION`, `REMOTE_DEV_LEGACY_PER_PROJECT_CONFIGS`.

`status`, `stop` and `warm-up` on a running backend start a second, short JVM that hands the command to the running one (its log shows `External command line:` and `Processing command with …UnattendedHostStatusStarterLegacy`). `status` takes about 1.4 s on the phone and prints `STATUS:` followed by JSON.

`registerBackendLocationForGateway` adds a symlink to the install under `~/.cache/JetBrains/RemoteDev/userProvidedDist/`, e.g. `_data_data_com.termux_files_home_intellij_idea-IU-263.7307.200 -> /data/data/com.termux/files/home/intellij/idea-IU-263.7307.200`. Gateway offers installs listed there instead of downloading its own. Run again for an install that's already registered, it prints `… is already registered` and exits with an error; `intellij-backend setup` checks the links first.

## Status files

While a backend runs, it keeps a JSON file at `~/.cache/JetBrains/RemoteDev/active/<pid of the JVM>`:

| Field | Example |
|---|---|
| `appPid` | `4423` |
| `appVersion` | `IU-263.7307.200` |
| `runtimeVersion` | `25.0.4.1b635.79` |
| `idePath` | `/data/data/com.termux/files/home/intellij/idea-IU-263.7307.200` |
| `joinLink` | `tcp://127.0.0.1:5990#jt=4fd57122-…&p=IU&fp=7077…&cb=263.7307.200&newUi=true&jb=25.0.4.1b635.79` |
| `httpLink`, `gatewayLink` | `gatewayLink` carries `idePath` and the URL-encoded `projectPath` |
| `projects` | per project: `projectPath`, `controllerConnected`, … |
| also | `ideIdentityString`, `backendUnresponsive`, `modalDialogIsOpened`, `unattendedMode` |

What to know about them:

- **`joinLink` in the file goes stale.** It holds the token from start. Once a client connects with that token, the backend issues a new one, but the file keeps the old one (seen 2026-11-16: file `jt=01cc75ad…`, backend `jt=4b003251…`). The `status` command returns the current token without using it up. `intellij-backend link` uses `status` for that reason.
- A backend killed with `kill -9` leaves its file behind (seen: `active/10518`, `active/11886`). Gateway and `intellij-backend` skip files whose pid isn't running.
- `~/.cache/JetBrains/RemoteDev/recent/` keeps copies for finished backends, named `<pid>` and `<pid>_<number>`.

## Clients and sessions

Details and log lines are in [`connections.md`](connections.md); in short:

- **One controlling client at a time.** A new client with a valid token ends the current one's session (`Active controller connection has been detected. Terminate existing connection.`).
- **A join token works once.** After a client connects, the backend issues a new one.
- **The backend outlives disconnects.** A dropped client's session waits for it to come back (`Suspending wire`, `Wire is suspended`), with no time limit (`rdct.connection.rd.reconnection.timeout.seconds = -1`; Code With Me uses `300`), until more than 100 MB of unsent updates pile up (`rdct.wire.storage.wire.buffer.size.bytes = 104857600`). So a backend started with `intellij-backend` doesn't need a restart when the phone app or a laptop disconnects.

## JetBrains Gateway over SSH

What Gateway does on the phone, from its log (`~/Library/Logs/JetBrains/JetBrainsGateway2026.3/idea.log`) and the phone:

1. Runs `uname -sm` (`Linux aarch64`) and its commands through `/bin/sh -lc …`; Termux's `termux-exec` maps `/bin/sh` to Termux's shell.
2. Uploads its worker, a static Go program (`remote-dev-worker-linux-arm64` from `Gateway.app/Contents/lib/remote-dev-workers/`), to `~/.cache/JetBrains/RemoteDev/remote-dev-worker/remote-dev-worker_<sha256>`. It runs on Android as is.
3. Runs `<worker> port-forwarding-test`.
4. Looks for a running backend: the worker reads `active/` (and checks the pid is alive), and Gateway runs `remote-dev-server.sh status` (`Get host status for idePath=…`). This is the "checking for already running backend" step; there is no mDNS or LAN discovery involved.
5. If none runs, starts one: the worker runs `<idePath>/bin/remote-dev-server.sh run <projectPath>` with no other options (`Starting remote IDE backend`). On our phone that is the `intellij-backend` launcher wrapper, so it gets the notification.
6. Forwards the backend's port inside its SSH connection and opens JetBrains Client. It also starts `Remote Backend Restart Monitoring (retry attempts limit: 13, delay: 5 s)`.

A backend that Gateway started closes itself a few seconds after its client quits normally (2026-11-16 04:51:12 `Shutting down client`, 04:51:15 `Unattended host will be closed soon`, `Unattended host controller session is not found`). A client that's killed instead of quitting leaves the backend waiting for it to reconnect.

On macOS, Local Network privacy blocks both Gateway's SSH and JetBrains Client from the phone's LAN address (`NoRouteToHostException`); see [`running-the-backend.md`](running-the-backend.md) for the workaround.

## Native parts that need glibc

Everything in the IntelliJ install that is a native aarch64 file and asks for glibc symbol versions (`readelf -V … | grep GLIBC_`), checked on the phone 2026-11-16. None of these can load on Android, so the feature each one serves doesn't work there; the remote-dev backend runs without all of them.

| File | Used for |
|---|---|
| `bin/idea`, `bin/remote-dev-server` | native launchers; the shell launchers run instead |
| `plugins/jna-plugin/…/libjnidispatch.so` | JNA (Java calling native code), used by several plugins |
| `plugins/platform-compose-plugin/…/libskiko-linux-arm64.so` | Compose (Skia) inside IntelliJ |
| `plugins/platform-vcs-plugin/…/libsqliteij.so` | VCS log index |
| `plugins/profiler-asyncOne-plugin/…/libasyncProfiler.so` | profiler |
| `plugins/pty4j-plugin/…/libpty.so`, `plugins/terminal/libghostty-vt/…/libghostty-vt.so` | built-in terminal |
| `plugins/remdev-plugin/quiche-native/…/libquiche.so` | QUIC transport for remote dev; our connections use TCP |
| `plugins/webp/…/libwebp_jni.so` | WebP images |
| `plugins/jcef-plugin/jcef/*` | embedded Chromium (JCEF) |
| `plugins/remote-dev-server/selfcontained/*` | X11, GTK and other libraries for the backend's virtual display; skipped with `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0` |

Also failing at startup, from the log: IntelliJ's OS-integration daemon (`DaemonClient - bundled (attempt 1): Failed to launch daemon`).

Static, and so they start: `bin/fsnotifier` (but gives up, see [`fsnotifier.md`](fsnotifier.md)), `bin/restarter`, `lib/ijent/ijent-aarch64-unknown-linux-musl-release`, `plugins/gateway-plugin/lib/remote-dev-workers/remote-dev-worker-linux-arm64`.

IntelliJ's own JBR (`jbr/lib/*.so`, `jbr/lib/server/libjvm.so`) is glibc throughout, which is why we build `jbr-25` ([`jbr-for-android.md`](jbr-for-android.md)).
