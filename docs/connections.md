# Connecting: link prompt, discovery and history

Decided with the user on 2026-11-16. The app's connect screen has three parts:

1. **Link prompt**: paste a join link.
2. **Discovered**: backends found on this phone and on the LAN.
3. **History**: machines connected before. Tapping one reconnects.

Build order: saved pasted links first, then Termux on this phone, then SSH.

Ownership: the screens and the connection logic belong to the UI session (`client/`). The phone side (the `intellij-backend` command, the DNS-SD announcement, Termux setup) belongs to the runtime session (`backend/`). The contract between them is below.

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

| Where | How |
|---|---|
| This phone | TCP connect to `127.0.0.1:5990`–`5999`. And if Termux is installed and the permission is granted: `intellij-backend status --json` through Termux |
| LAN | Android `NsdManager`: browse `_intellij-backend._tcp` (announced by `intellij-backend` while the backend runs) and `_ssh._tcp` (macOS announces it when Remote Login is on) |

Announcements never contain the join token. Anyone who has the token can join, and so run code on the machine.

## Runtime contract

The command is `intellij-backend`, at `$PREFIX/bin/intellij-backend` (`/data/data/com.termux/files/usr/bin/intellij-backend`). The UI side calls it through Termux and over SSH, so its output and exit codes are fixed:

| Command | Prints (stdout) | Exit codes |
|---|---|---|
| `start <project> [--lan] [--port N]` | the join link, one line, once the backend is ready (waits up to 180 s). Already running for that project: prints its link | `0` ok, `1` failed (stderr says why), `4` another IntelliJ is using the same settings (desktop IntelliJ, or a backend for another project) |
| `link` | the join link, one line | `0`, `3` not running |
| `status --json` | `{"running":true,"project":"/data/…/smoke","port":5990,"listenOn":"127.0.0.1","joinLink":"tcp://…"}` or `{"running":false}` | `0` |
| `stop` | nothing | `0` (also when not running) |

Without `--lan` the backend listens on `127.0.0.1` only (reachable from this phone and over SSH). With `--lan` it listens on `0.0.0.0`.

DNS-SD announcement while the backend runs:

| Item | Value |
|---|---|
| type | `_intellij-backend._tcp` |
| instance name | `<device name> · <project folder name>`, e.g. `Pixel 8 · smoke` |
| port | the backend port, e.g. `5990` |
| TXT `v` | `1` |
| TXT `project` | `smoke` |
| TXT `direct` | `1` when started with `--lan` (connect straight to the port), `0` when only reachable over SSH |
| TXT `fp` | server certificate fingerprint, 64 hex digits |
| TXT `ssh`, `user` | SSH port and user, e.g. `8022`, `u0_a404` |

Termux setup the app needs (one time, done by the user):

1. In `~/.termux/termux.properties`: `allow-external-apps = true`, then `termux-reload-settings`.
2. The app declares `com.termux.permission.RUN_COMMAND` and `<queries><package android:name="com.termux" /></queries>`, and asks for the permission at runtime.
3. The app sends `com.termux.RUN_COMMAND` to `com.termux/.app.RunCommandService` with `RUN_COMMAND_PATH` = the path above, `RUN_COMMAND_ARGUMENTS`, `RUN_COMMAND_BACKGROUND = true`, and a `PendingIntent` that receives stdout, stderr and the exit code.

## Open

- Whether a backend can hand out a second token without restarting (for a second device). Until then: one client identity per backend run.
- Whether Termux can announce DNS-SD at all (multicast without the Android multicast lock). If it can't, LAN discovery of a phone backend falls back to history and manual entry.
- `intellij-backend` itself is not written yet (runtime session, next).
