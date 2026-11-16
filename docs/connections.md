# Connecting: link prompt, discovery and history

Decided with the user on 2026-11-16. The app's connect screen has three parts:

1. **Link prompt**: paste a join link.
2. **Discovered**: a backend on this phone, found through Termux. No LAN discovery (decided 2026-11-16): other machines are added once by link or SSH, then stay in history.
3. **History**: machines connected before. Tapping one reconnects.

Build order: saved pasted links first, then Termux on this phone, then SSH.

Ownership: the screens and the connection logic belong to the UI session (`client/`). The phone side (the `intellij-backend` command, Termux setup) belongs to the runtime session (`backend/`). The contract between them is below.

## Why history can't just store the link

| Part of the link | Example | Lifetime |
|---|---|---|
| `jt` (join token) | `584c7a19-0df1-4798-be5c-7af786a00846` | New on every backend start. Accepted from **one client id** only: a different client id gets `Declining invalid token` (see `split-protocol.md`) |
| client id | `<SHA3-256 of the client's CA certificate>-<epoch ms>` | Chosen by the client. Kept, it can use the same `jt` again |
| `fp` | 64 hex digits | Server certificate fingerprint; checked by the client |
| host, port | `tcp://127.0.0.1:5990` | `0.0.0.0` or `127.0.0.1` as printed by the backend; the client puts in the address it actually reached |

The backend also keeps a dropped session open for the client to resume, with no time limit (`rdct.connection.rd.reconnection.timeout.seconds = -1`). It drops the session only when more than 100 MB of unsent updates pile up (`rdct.wire.storage.wire.buffer.size.bytes`).

So:
- **Every history entry stores the client identity it connected with** (CA key and certificate, client id), so the app can use the same `jt` again after it restarts.
- The link itself is valid only until that backend restarts. Then a fresh link is needed: from Termux, over SSH, or pasted again.

## History entry

| Field | Example | For |
|---|---|---|
| kind | `link`, `termux` or `ssh` | how to get a fresh link |
| name | `Pixel 8 · smoke` | display |
| address, port | `192.168.0.105`, `5990` | where to connect |
| project | `/data/data/com.termux/files/home/intellij/smoke` | display; `start` for `termux` and `ssh` |
| `fp` | `9205207E…6B6A` | to notice a different server |
| last link | `tcp://…#jt=…` | try first |
| client identity | key, certificate, client id | reuse the `jt` |
| SSH (kind `ssh` only) | user `u0_a404`, port `8022`, key alias | run commands, tunnel the port |
| last connected | `2026-11-16 10:42` | sort order |

What tapping an entry does:

| Kind | Steps |
|---|---|
| `link` | Connect with the last link and the stored client identity. Refused or the server's `fp` changed (backend restarted) → ask for a new link and keep the entry |
| `termux` | Run `intellij-backend link` through Termux. Exit `3` (not running) → offer `intellij-backend start <project>`. Connect to the printed link (address `127.0.0.1`). A new `jt` means a new client identity |
| `ssh` | Over SSH, run `intellij-backend link` (exit `3` → offer `start`). Connect through an SSH local port forward to `127.0.0.1:<port>` on the host. This also avoids opening the backend's port on the LAN |

## Discovery

Only on this phone:

| Check | How |
|---|---|
| Backend running in Termux | `intellij-backend status --json` through Termux (needs the Termux setup below). It reads IntelliJ's own status files in `~/.cache/JetBrains/RemoteDev/active/`, the same ones JetBrains Gateway checks over SSH |
| Termux not set up or not installed | TCP connect to `127.0.0.1:5990`–`5999`: a backend is there, but its link has to be pasted |

There is no LAN discovery: no DNS-SD announcement and no browsing (dropped 2026-11-16). A machine on the LAN is added once, by pasting its link or as an SSH host, and is reached through history after that.

## Runtime contract

The command is `intellij-backend`, at `$PREFIX/bin/intellij-backend` (`/data/data/com.termux/files/usr/bin/intellij-backend`). The UI side calls it through Termux and over SSH, so its output and exit codes are fixed:

| Command | Prints (stdout) | Exit codes |
|---|---|---|
| `start <project> [--lan] [--port N]` | the join link, one line, once the backend is ready (waits up to 180 s). Already running for that project: prints its link | `0` ok, `1` failed (stderr says why), `4` another IntelliJ is using the same settings (desktop IntelliJ, or a backend for another project) |
| `link` | the join link, one line | `0`, `3` not running |
| `status --json` | `{"running":true,"project":"/data/…/smoke","port":5990,"listenOn":"127.0.0.1","joinLink":"tcp://…"}` or `{"running":false}` | `0` |
| `stop` | nothing | `0` (also when not running) |

While a backend runs, Termux:API shows an ongoing notification "IntelliJ backend running" (`termux-notification --id intellij-backend`). Tapping it opens the app (`intellijandroid.client.android/.MainActivity`); its Stop button runs `intellij-backend stop`. Every backend runs under a small parent (`intellij-backend internal-run`) that shows the notification, waits for IntelliJ's launcher, and then removes the notification and releases the wake lock. `start` uses it directly; for Gateway and hand-typed starts, the launcher wrapper that `intellij-backend setup` puts in place of `bin/remote-dev-server.sh` does. So that happens whenever the backend exits for good (stop, crash, killed by Android), and not on IntelliJ's own restarts (exit code 88), which the launcher handles in a loop. So the app doesn't need its own notification for the backend.

Without `--lan` the backend listens on `127.0.0.1` only (reachable from this phone and over SSH). With `--lan` it listens on `0.0.0.0`.

Termux setup the app needs (one time, done by the user):

1. In `~/.termux/termux.properties`: `allow-external-apps = true`, then `termux-reload-settings`.
2. The app declares `com.termux.permission.RUN_COMMAND` and `<queries><package android:name="com.termux" /></queries>`, and asks for the permission at runtime.
3. The app sends `com.termux.RUN_COMMAND` to `com.termux/.app.RunCommandService` with `RUN_COMMAND_PATH` = the path above, `RUN_COMMAND_ARGUMENTS`, `RUN_COMMAND_BACKGROUND = true`, and a `PendingIntent` that receives stdout, stderr and the exit code.

## Open

- Whether a backend can hand out a second token without restarting (for a second device). Until then: one client identity per backend run.
- `intellij-backend` is written (`backend/termux/intellij-backend`, 2026-11-16) and tested on the phone over SSH and through Termux's RUN_COMMAND (the app's Start). It sets `IDEA_JDK`, `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS` and `TMPDIR` itself, because RUN_COMMAND and notification buttons don't read a shell profile. The `active/` status file of a running backend has `joinLink`, so `link` and `status` answer at once.
