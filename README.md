# intellij-android

Running IntelliJ IDEA's backend natively on Android, with no Linux VM or proot, as the base for a native Android frontend.

## Status

| Part | State |
|---|---|
| `jbr/`: JetBrains Runtime 25 built for Android (bionic), as a Termux package | Works. IntelliJ IDEA 2026.3's remote-dev server runs on it in Termux; JetBrains Client on another machine edits, highlights and completes against it |
| `backend/`: running IntelliJ on the phone | Manual commands below; start script and a bionic `fsnotifier` to come |
| `client/`: native Android frontend | Not started |

Why a custom JBR: IntelliJ's remote-dev server needs JetBrains Runtime (`com.jetbrains.JBR.isGraphicsUtilsSupported()` must be `true`), and the JBR that ships with IntelliJ is built for glibc, which Android doesn't have.

## Layout

```
docs/jbr-for-android.md        plan and dated log of the whole build: every failure and fix
jbr/
  termux-package/jbr-25/       Termux recipe: build.sh, Termux's patches (00NN), ours (10NN)
  PATCHES.md                   what each patch does and why
  scripts/build.sh             builds the .deb in Termux's Docker build image
  scripts/dev-tree.sh          recreates a patched JBR git tree for writing new patches
  scripts/find-unresolved-symbols.sh
  docker/tar-rosetta-wrapper.sh
backend/checks/                small Java checks used on the phone
```

No upstream source is committed. `jbr/scripts/build.sh` fetches termux-packages at a pinned commit into `work/`, and the recipe downloads the JBR source tarball, checked against its SHA-256.

## Build the JBR package

Needs Docker. On Apple Silicon, the amd64 build image runs under Rosetta; the script handles that.

```sh
jbr/scripts/build.sh          # full build, about 40 minutes
jbr/scripts/build.sh -f       # rebuild after changing the recipe
jbr/scripts/build.sh -c       # continue a failed build without re-downloading or re-patching
```

Result: `output/jbr-25_25.0.4.1_aarch64.deb`, and a log in `logs/`.

## Run IntelliJ's backend on the phone

In Termux (aarch64):

```sh
apt install ./jbr-25_25.0.4.1_aarch64.deb          # installs to $PREFIX/lib/jvm/jbr-25

# IntelliJ IDEA 2026.3, linux-aarch64 build, unpacked to ~/intellij/idea-IU-263.7307.200
export IDEA_JDK=$PREFIX/lib/jvm/jbr-25
export REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0   # else the launcher swaps in the bundled glibc JBR
~/intellij/idea-IU-263.7307.200/bin/remote-dev-server.sh run ~/path/to/project \
  --listenOn 0.0.0.0 \
  --port 5990
```

It prints a `Join link: tcp://0.0.0.0:5990#jt=…`. Open it in JetBrains Gateway with the phone's address instead of `0.0.0.0`.

On macOS, JetBrains Client may be blocked from the local network (`NoRouteToHostException: No route to host`). Either allow it under System Settings → Privacy & Security → Local Network, or tunnel the port and use `tcp://127.0.0.1:5990#…`:

```sh
ssh -p 8022 -N -L 127.0.0.1:5990:127.0.0.1:5990 <phone-address>
```

## Known limitations

- No native file watcher yet (`Native file watcher is disabled`): IntelliJ's `fsnotifier` is a glibc binary, so changes are found by periodic rescans.
- The backend needs about 2.5 GB of RAM.
- A JDK for projects must be added by its real path, e.g. `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk`.
- Projects in shared storage (`~/storage/shared`, `/storage/emulated/0`) and on SD cards can be opened, but prefer Termux's home (`~/…`) for code: shared storage has no symlinks, no executable files (`./gradlew` won't run), case-insensitive file names, and was about 195× slower writing 2,000 small files in a test. For other folders Android won't let Java list on the way down, add them with `-Djdk.nio.fs.unlistableDirectoryHints=/path/one:/path/two` (see `1004` in `jbr/PATCHES.md`).
