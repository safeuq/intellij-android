# intellij-android

Running IntelliJ IDEA's backend natively on Android, with no Linux VM or proot, as the base for a native Android frontend.

## Status

| Part | State |
|---|---|
| `jbr/`: JetBrains Runtime 25 built for Android (bionic), as a Termux package | Works. IntelliJ IDEA 2026.3's remote-dev server runs on it in Termux; JetBrains Client and Gateway on another machine edit, highlight and complete against it |
| `backend/`: running IntelliJ on the phone | `intellij-backend` (setup, start/stop/status, a notification with a Stop button) and IntelliJ's file watcher built for Android |
| Native frontend | Moved to [safeuq/ideate-editor](https://github.com/safeuq/ideate-editor) (the IDEate Android app and a desktop build of the same code), with its docs: the split-mode protocol, the client and the IntelliJ look |

Why a custom JBR: IntelliJ's remote-dev server needs JetBrains Runtime (`com.jetbrains.JBR.isGraphicsUtilsSupported()` must be `true`), and the JBR that ships with IntelliJ is built for glibc, which Android doesn't have.

## Docs

All documentation is in [`docs/`](docs/):

| Doc | What |
|---|---|
| [`running-the-backend.md`](docs/running-the-backend.md) | set up the phone (`intellij-backend setup`), start/stop the backend, connect from JetBrains Gateway over SSH, known limitations |
| [`building-jbr.md`](docs/building-jbr.md) | build the `jbr-25` Termux package |
| [`jbr-patches.md`](docs/jbr-patches.md) | every patch in the JBR recipe: what it does and why |
| [`jbr-for-android.md`](docs/jbr-for-android.md) | plan and dated log of the whole build: every failure and fix |
| [`fsnotifier.md`](docs/fsnotifier.md) | IntelliJ's file watcher built for Android |
| [`remote-dev-server.md`](docs/remote-dev-server.md) | how IntelliJ's remote-dev server behaves: launcher, commands, status files, sessions, Gateway's steps, native parts that need glibc |
| [`desktop-ui-on-the-phone.md`](docs/desktop-ui-on-the-phone.md) | IntelliJ's full window on the phone over VNC with XFCE: what works, VNC commands and behaviour |
| [`connections.md`](docs/connections.md) | the IDEate app's connect screen (link, this-phone discovery, history) and the `intellij-backend` contract it relies on |

## Layout

```
docs/                          all documentation (above)
jbr/
  termux-package/jbr-25/       Termux recipe: build.sh, Termux's patches (00NN), ours (10NN)
  scripts/build.sh             builds the .deb in Termux's Docker build image
  scripts/dev-tree.sh          recreates a patched JBR git tree for writing new patches
  scripts/find-unresolved-symbols.sh
  docker/tar-rosetta-wrapper.sh
backend/checks/                small Java checks used on the phone
backend/fsnotifier/            IntelliJ's file watcher built for Android: build.sh, our patch
backend/termux/intellij-backend  setup/start/stop/status/link for the backend in Termux (called by the app)
```

No upstream source is committed. `jbr/scripts/build.sh` fetches termux-packages at a pinned commit into `work/`, and the recipe downloads the JBR source tarball, checked against its SHA-256. `backend/fsnotifier/build.sh` downloads its source files from `intellij-community` at a pinned commit.
