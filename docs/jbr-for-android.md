# JBR for Android (Termux): build plan

> This is the working plan and log from the first build, kept as written. Paths refer to the original working folder: `termux-packages/packages/jbr-25/` is now `jbr/termux-package/jbr-25/`, `docker/` is now `jbr/docker/` and `jbr/scripts/`, and builds now run through `jbr/scripts/build.sh` (see [`building-jbr.md`](building-jbr.md)).

## Goal

Run the IntelliJ remote-dev backend natively on the phone in Termux (no VM, no proot), so a frontend can connect over JetBrains' split-mode protocol (Option A).

To do that we need **JetBrains Runtime (JBR) built for Android/bionic**: JBR's source plus Termux's Android patches for OpenJDK 25.

## Why (what we found)

| Try | Result |
|---|---|
| IntelliJ's bundled JBR (`jbr/bin/java`) | Doesn't start. Built for glibc (`LIBC="gnu"`), and Android uses bionic. |
| Termux OpenJDK 25.0.5 (`$PREFIX/lib/jvm/java-25-openjdk`) | Headless IntelliJ starts, loads plugins, indexes. |
| `remote-dev-server.sh` on Termux OpenJDK 25 | Fails in `Main.initRemoteDev`: `JBR version 17.0.6b796 or later is required to run a remote-dev server with lux` |

The check is `com.jetbrains.JBR.isGraphicsUtilsSupported()`. It asks the JVM for a JBR-only service that lux (the remote-dev server's UI layer) relies on, so it can't be bypassed. Only a real JBR passes it.

Two more launcher details:
- `remote-dev-server.sh` only uses the native `bin/remote-dev-server` launcher on glibc. On Termux it falls back to `plugins/remote-dev-server/bin/launcher.sh`, which is fine.
- `launcher.sh` swaps in the bundled JBR through `/lib/ld-linux-aarch64.so.1` unless `REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0` is set. Always set it.

## Target versions

| Item | Value |
|---|---|
| IntelliJ on phone | `idea-IU-263.7307.200` (2026.3), at `~/intellij/idea-IU-263.7307.200` |
| JBR to build | `jbr-release-25.0.4.1b635.79`, commit `787be56cf0774a46defa818051f686b7bb18b0d5` (same as IntelliJ's bundled `JBR-25.0.4.1+1-b635.79`, variant `nomod`) |
| Android patches | Termux `packages/openjdk-25` (OpenJDK `25.0.5`, revision 2): 37 patches plus `tmpdir-path-length.diff` |
| Phone | Samsung SM-S926B, Android 17, arm64-v8a, 11 GB RAM, Termux over SSH at `192.168.0.105:8022`, ADB at `192.168.0.105:5555` |
| Mac | macOS 26.5.2 arm64, Docker 28.5 (18 CPUs, 12 GB), NDK `26.1.10909125` in `~/Library/Android/sdk/ndk` |

## Folder layout

All work happens under `/Users/safeuq/Files/Projects/Java/JBR`:

```
JBR/
├── jbr-for-android-plan.md   this file
├── jbr/                      JetBrainsRuntime, shallow clone at the tag above
│                             branch termux-android = tag + Termux patches (check only, see step 1)
├── termux-packages/          Termux build system (sparse clone, widened in step 2)
│   └── packages/jbr-25/      new recipe: copy of openjdk-25, pointed at JBR (step 2)
├── output/                   built .deb files land here (Termux's default for run-docker)
└── logs/                     build logs, one per attempt
```

## Steps

### 1. Check that Termux's patches apply to JBR ✅ done

- Applied all 37 patches in order with GNU `patch -p1` (`gpatch`), the way Termux does. One commit per patch on branch `termux-android` in `jbr/`.
- Result: **all 37 apply, with no rejected hunks.** (Run one by one against the untouched tree, 10 seemed to fail; they only depend on earlier patches.)
- This branch is only a check. It holds 29 literal `@TERMUX_…@` placeholders, which Termux's build fills in while patching (`scripts/build/termux_step_patch_package.sh`). So the real build lets Termux apply the patches itself (step 2).

### 2. Write the `jbr-25` Termux recipe

Copy `packages/openjdk-25` to `packages/jbr-25` and change:

| Setting | `openjdk-25` | `jbr-25` |
|---|---|---|
| `TERMUX_PKG_SRCURL` | `github.com/openjdk/jdk25u/archive/refs/tags/jdk-25.0.5-ga.tar.gz` | `github.com/JetBrains/JetBrainsRuntime/archive/refs/tags/jbr-release-25.0.4.1b635.79.tar.gz` |
| `TERMUX_PKG_SHA256` | `9a850cee…` | computed from the JBR tarball |
| `TERMUX_PKG_VERSION` | `25.0.5` | `25.0.4.1` (with `b635.79` in the version string, below) |
| Install path | `$PREFIX/lib/jvm/java-25-openjdk` | `$PREFIX/lib/jvm/jbr-25`, so it sits next to `openjdk-25` |
| rpath in `jdk_ldflags` | `…/java-25-openjdk/lib` | `…/jbr-25/lib` |
| `--with-jvm-features` | `link-time-opt` | `shenandoahgc,link-time-opt` (JBR uses Shenandoah) |
| Vendor/version | `--with-vendor-name=Termux`, empty `--with-version-opt` | `--with-vendor-name="JetBrains s.r.o."`, `--with-version-build=1`, `--with-version-opt=b635.79`, so IntelliJ sees `25.0.4.1+1-b635.79` |
| `--enable-cds` | default | `yes` (as JBR does) |
| Subpackages `openjdk-25-x`, `openjdk-25-source`, `openjdk-25.alternatives` | present | removed (no splitting, no `/usr/bin/java` symlinks, no clash with `openjdk-25`) |

Also:
- Patch `0014` and `build.sh` mention `java-25-openjdk`; update them to `jbr-25`.
- Widen the sparse checkout to the whole `termux-packages` repo (the build needs `scripts/`, `ndk-patches/` and the Docker setup), or do a full shallow clone.

### 3. Decide: headless-only or full AWT (checkpoint)

On Linux, JBR's configure needs **X11 + Wayland + speech-dispatcher** unless `--enable-headless-only` is passed (`make/autoconf/libraries.m4`, `LIB_DETERMINE_DEPENDENCIES`).

| Option | Configure | Extra deps | Risk |
|---|---|---|---|
| **3a. Headless-only** (try first) | `--enable-headless-only` | none | lux may need parts of `libawt` that a headless-only build leaves out. If so, the remote-dev server fails again, with a different error. |
| **3b. Full AWT** | Termux's X11 flags plus `--with-wayland=$PREFIX`, `--with-wayland-protocols=$PREFIX/share/wayland-protocols`, speechd | Termux `libwayland`, `wayland-protocols`, X11 libs (exist in Termux's x11 repo); speech-dispatcher (may not exist, so it may need a small configure patch to make it optional) | More deps, more places to break |

Plan: build 3a first, since a build attempt is the slow part. Move to 3b only if step 5 shows lux needs it.

### 4. Build in Docker on the Mac

```sh
cd /Users/safeuq/Files/Projects/Java/JBR/termux-packages
export DOCKER_DEFAULT_PLATFORM=linux/amd64
export TERMUX_DOCKER_RUN_EXTRA_ARGS="--cap-add CAP_SYS_ADMIN --device /dev/fuse"
# first time only: create the container, then install the tar wrapper (below)
./scripts/run-docker.sh true
./scripts/run-docker.sh ./build-package.sh -I -a aarch64 jbr-25 > ../logs/build-N.log 2>&1
```

- `TERMUX_DOCKER_RUN_EXTRA_ARGS`: on macOS `run-docker.sh` leaves out the FUSE device and `CAP_SYS_ADMIN` it passes on Linux, and the toolchain setup (`termux_setup_toolchain_30.sh`) mounts the NDK toolchain with `fuse-overlayfs`. These flags only take effect when the container is created.

- `-I` downloads already-built dependencies from Termux's apt repo instead of building them.
- After one successful build, Termux records `jbr-25@25.0.4.1` as built and skips it (`built - skipping`). Add `-f` to rebuild after changing the recipe. Add `-c` instead to continue a failed build without re-downloading or re-patching (the recipe then keeps the existing OpenJDK configuration).
- `ghcr.io/termux/package-builder` is published for `amd64` only. Without `DOCKER_DEFAULT_PLATFORM=linux/amd64`, Docker on Apple Silicon asks for `arm64` and fails with `no matching manifest for linux/arm64/v8`. Docker Desktop has Rosetta registered for x86-64 containers, so it runs under Rosetta, not QEMU. Expect a few hours for the first build.
- The `.deb` lands in `termux-packages/output/`, not `JBR/output/`.
- Rosetta doesn't implement the `openat2` system call, and GNU tar 1.35 in the image needs it to create nested folders when extracting (`tar: ./data/data: Cannot mkdir: Function not implemented`). `docker/tar-rosetta-wrapper.sh` sends extraction to `bsdtar` and everything else to GNU tar. Install it once per container; it's lost if the `termux-package-builder` container is recreated:
  ```sh
  docker cp docker/tar-rosetta-wrapper.sh termux-package-builder:/tmp/tar-wrapper
  docker exec -u root termux-package-builder install -m 755 /tmp/tar-wrapper /usr/local/bin/tar
  ```
- If compilation fails, fix it on the `termux-android` branch in `jbr/`, turn the fix into an extra patch in `packages/jbr-25/` (numbered `1001-…patch` to keep it apart from Termux's), and run the build again.
- Expected output: `output/jbr-25_25.0.4.1_aarch64.deb`.

### 5. Install on the phone and test

```sh
scp -P 8022 output/jbr-25_*_aarch64.deb 192.168.0.105:
ssh -p 8022 192.168.0.105 'dpkg -i jbr-25_*_aarch64.deb'
```

Checks, in order. Each must pass before the next:

| # | Check | Pass looks like |
|---|---|---|
| 1 | `$PREFIX/lib/jvm/jbr-25/bin/java -version` | `OpenJDK Runtime Environment JBR-25.0.4.1+1-b635.79…` |
| 2 | JBR API: small Java check calling `com.jetbrains.JBR.isGraphicsUtilsSupported()` with IntelliJ's `lib/jbr-api.jar` on the classpath | prints `true` |
| 3 | Remote-dev server: `IDEA_JDK=$PREFIX/lib/jvm/jbr-25 REMOTE_DEV_SERVER_USE_SELF_CONTAINED_LIBS=0 remote-dev-server.sh run ~/intellij/smoke --listenOn 0.0.0.0 --port 5990` | prints a `tcp://192.168.0.105:5990#jt=…` join link |
| 4 | Connect from the Mac with JetBrains Gateway using that link | IDE window opens on `smoke` |
| 5 | In `Main.java`: unused variable greyed out, NPE warning on `greeting.length()`, completion on `System.out.` | all three work |

If check 3 fails with an AWT or native-library error, go back to step 3 and choose 3b.

### 6. Expected follow-up problems (not part of this build)

- **`/data` listing permissions.** Android doesn't let Termux list `/`, `/data` or `/data/data`. IntelliJ's VFS then misses files (`Cannot find virtual file …` warnings). With `/` made listable under `termux-chroot` the warnings dropped to 0, so this is the likely cause. Expect it at check 4/5. The fix belongs in IntelliJ (patch or plugin), not in proot.
- **Licence/login.** The backend is the Ultimate build; remote dev may ask for a JetBrains login.
- **Memory.** Phone has about 2.9 GB free; the backend runs with `-Xmx2048m`.

## Open questions

1. Does lux work on a headless-only JBR (step 3)?
2. Does Termux's build image run well enough under emulation on Apple Silicon, or is building on the phone (`build-package.sh` in Termux directly, 10 cores) faster?
3. The JetBrains Client the frontend uses is also Swing on JBR. That doesn't matter for this test (the client runs on the Mac), but it matters later for a native Android frontend.

## Log

| Date (IST) | Step | Result |
|---|---|---|
| 2026-11-14 | 1 | All 37 Termux patches apply to `jbr-release-25.0.4.1b635.79` in order, 0 rejects |
| 2026-11-14 | 2 | Recipe `termux-packages/packages/jbr-25/build.sh` written: headless-only (3a), JBR tarball SHA-256 `c6522b2282a7f9573b3292735d47d04424c7731a3d54b769e0fc45332fe48e2f` |
| 2026-11-14 | 4 | Build 1 (`logs/build-1.log`): Docker failed, `no matching manifest for linux/arm64/v8`. Fixed with `DOCKER_DEFAULT_PLATFORM=linux/amd64` |
| 2026-11-14 | 4 | Build 2 (`logs/build-2.log`): failed unpacking the first dependency, `tar: Cannot mkdir: Function not implemented`. Cause: Rosetta has no `openat2`. Fixed with `docker/tar-rosetta-wrapper.sh` |
| 2026-11-14 | 4 | Build 3 (`logs/build-3.log`): `bsdtar` unpacked the dependency but failed setting the time on `/` (`Can't restore time: Operation not permitted`). Wrapper now maps `--no-overwrite-dir` to `-m` |
| 2026-11-14 | 4 | Build 4 (`logs/build-4.log`): all dependencies, JBR source and boot JDK downloaded; failed at `fuse-overlayfs: cannot mount` (no `/dev/fuse`). On macOS `run-docker.sh` leaves out `--cap-add CAP_SYS_ADMIN --device /dev/fuse`. Container recreated with `TERMUX_DOCKER_RUN_EXTRA_ARGS`, tar wrapper reinstalled |
| 2026-11-14 | 4 | Build 5 (`logs/build-5.log`): toolchain mounted, all patches applied; configure failed `--with-giflib=system specified, but gif_lib.h not found` (giflib/libpng came via the dropped `openjdk-25-x` subpackage). Recipe switched to `--with-giflib=bundled --with-libpng=bundled` |
| 2026-11-14 | 4 | Build 6 (`logs/build-6.log`): **configure succeeded**: version `25.0.4.1+1-b635.79`, features include `shenandoahgc` and `link-time-opt`, NDK r30 clang 21 for API 24, 18 jobs. Compile failed: `dictionary.hpp:86: member access into incomplete type 'InstanceKlass'`. JBR's DCEVM helper `old_if_redefining()` has an inline body in a header that only forward-declares `InstanceKlass`; it compiles only with precompiled headers, and Termux uses `--disable-precompiled-headers`. Fixed by patch `1001-move-old_if_redefining-out-of-dictionary-header.patch` (commit `c90dfe11b` on `termux-android`) |
| 2026-11-14 | 4 | `make -k images` run directly in the container's configured tree (`logs/make-k-1.log`), to collect all remaining compile errors in one pass instead of one per full build. **No new compile errors.** Only failure: the `libjvm.so` link was killed (`clang++: error: unable to execute command: Killed`), likely out of memory: LTO link with 18 jobs in Docker's 12 GB |
| 2026-11-14 | 4 | `make images JOBS=2` (`logs/make-2.log`, memory in `logs/make-2-memory.log`). Build JDK (x86-64) `libjvm.so` linked at 3.5 GB peak. Android `libjvm.so` LTO link killed again: memory rose to 10.95 GB of 11.67 GB |
| 2026-11-14 | 2 | Recipe: `__jvm_features="shenandoahgc"` (dropped `link-time-opt`, matching JetBrains' own aarch64 builds) |
| 2026-11-14 | 4 | Build 7 (`logs/build-7.log`): full `build-package.sh` run with patch `1001` and no LTO. **Android `libjvm.so` linked (27 MB).** Failed later: `libfontmanager.so`: `unable to find library -lawt_xawt`, from Termux patch `0014`. JBR doesn't need it: it already moved the X11 font code that calls `AWTFreeFont` into `libfontmanager_xawt` (built only with X11, linked to `libawt_xawt`), and plain `libfontmanager` is built with `-DHEADLESS`. Patch `0014` removed from `packages/jbr-25` |
| 2026-11-14 | 2 | Recipe: `termux_step_configure` returns early under `build-package.sh -c` when `spec.gmk` exists, so a continued build keeps the existing OpenJDK configuration |
| 2026-11-14 | 4 | Build 8 (`logs/build-8.log`): `build-package.sh -c` continuing build 7's tree, with `0014`'s change undone by hand in the container's source. **Package built**: `termux-packages/output/jbr-25_25.0.4.1_aarch64.deb` (185 MB); Termux symbol checks passed on 67 ELF files |
| 2026-11-14 | 5 | Installed with `apt install ./jbr-25_25.0.4.1_aarch64.deb` (349 MB). **Check 1 passed:** `OpenJDK Runtime Environment JBR-25.0.4.1+1-635.79-termux (build 25.0.4.1+1-b635.79)` |
| 2026-11-14 | 5 | **Check 2 passed:** `JBR.isAvailable = true`, `JBR.isGraphicsUtilsSupported = true` (Termux OpenJDK 25: both `false`). Test program `~/intellij/jbr-check/JbrCheck.java`, API jar `lib/intellij.libraries.jbr.jar` |
| 2026-11-14 | 5 | Check 3 failed: `AWTError: Failed to initialize the window toolkit: Can't load library: …/jbr-25/lib/libawt_xawt.so` from `Main.initLux` (Main.kt:268). `initLux` sets `java.awt.headless=false`, so the X11 AWT library is required. **Decision at step 3: headless-only (3a) is not enough; go to 3b** |
| 2026-11-14 | 3 | Recipe for 3b: removed `--enable-headless-only`; added Termux's X11 options, `--with-wayland=$PREFIX`, `--with-wayland-protocols=$PREFIX/share/wayland-protocols`, `--with-xkbcommon=$PREFIX`, `--without-speechd` (speechd is only for screen-reader announcements); deps `libx11 libxext libxi libxrender libxtst libwayland libxkbcommon`, build deps `libxrandr libxt xorgproto libwayland-protocols`. `wayland-scanner` is already in the build image. Patch `0014` stays out: JBR's `libfontmanager_xawt` covers it |
| 2026-11-14 | 4 | Build 9 (`logs/build-9.log`): full build with X11 + Wayland. First run was skipped (`built - skipping`); rerun with `-f`. Configure found all X11 headers, Wayland headers, `wayland-scanner`, `wayland-protocols`, xkbcommon. Compile failed in `libawt_wlawt`: `memory_utils.c: call to undeclared function 'shm_open'` / `'shm_unlink'` (bionic has neither) |
| 2026-11-15 | 4 | Patch `1002-wayland-use-memfd_create-on-android.patch` (commit `34a3442c2`): on `__ANDROID__`, `CreateSharedMemoryFile` uses `syscall(__NR_memfd_create, baseName, MFD_CLOEXEC)` (bionic declares `memfd_create` only from API 30). `make -k images` (`logs/make-k-3.log`): no further compile errors, image built. Packaged (build 10, `-c -f`): 72 ELF files (67 headless) |
| 2026-11-15 | 5 | Full-AWT JBR installed (`apt install --reinstall`); `libawt_xawt.so`, `libawt_wlawt.so` present. Checks 1–2 still pass. Check 3 failed further on: `libfontmanager.so: dlopen failed: cannot locate symbol "AccelGlyphCache_RemoveAllCellInfos"` |
| 2026-11-15 | 4 | Wrote `docker/find-unresolved-symbols.sh`: lists undefined symbols no `DT_NEEDED` library provides. Found 3 libraries relying on glibc's runtime lookup: `libfontmanager.so` (1 symbol), `libfontmanager_xawt.so` (17 `AWT*` font functions), `libwlsplashscreen.so` (`libiconv*`). Patch `1003-link-font-manager-and-wayland-splash-screen-libraries.patch` (commit `09b5cc68f`, after reverting `0014` on the branch as `f44861d54`): link both font libraries to `-lawt_xawt` when X11 is built (plus build-order dependency), add `$(ICONV_LIBS)` to `libwlsplashscreen`. Symbol check clean afterwards. Packaged as build 11 |
| 2026-11-15 | 5 | **Check 3 passed:** remote-dev server on JBR printed `Join link: tcp://0.0.0.0:5990#jt=…&p=IU&…&cb=263.7307.200&newUi=true&jb=25.0.4.1b635.79` (`~/intellij/remote-dev-jbr-3.log`). Backend RSS about 2.5 GB |
| 2026-11-15 | 5 | Check 4, first try: Gateway/JetBrains Client reported `Connection timeout`. Client log (`~/Library/Logs/JetBrains/IntelliJIdea2026.3/frontend/2026-11-15_at_00-16-08/idea.log`): every attempt fails in ~2 ms with `java.net.NoRouteToHostException: No route to host`; nothing reached the phone. Cause: macOS Local Network privacy for the newly downloaded client app (Terminal can reach `192.168.0.105:5990`). The two TLS "failures" in the phone's log were `nc`/`openssl` probes; `openssl s_client` completes TLS 1.3 against port 5990, and a plain Java TLS 1.3 server on `jbr-25` works too. Benign server noise: `IOException: can't enumerate devices` from `LinuxPowerService` (Android blocks battery status). Workaround: `ssh -p 8022 -N -L 127.0.0.1:5990:127.0.0.1:5990 192.168.0.105` and connect to `tcp://127.0.0.1:5990#…`; or allow "IntelliJ IDEA" (the client) and "JetBrains Gateway" under System Settings → Privacy & Security → Local Network |
| 2026-11-15 | 5 | **Check 4 passed** through the SSH tunnel: JetBrains Client `263.7307.200` on the Mac opened `smoke` from the phone. Error shown: `JDK "21" is not found on the disk or corrupted`, caused by the chroot paths left in `jdk.table.xml` (`/usr/lib/jvm/java-21-openjdk`). The JDK home field is read-only; added a new JDK through Project Structure → SDKs → + → Add JDK from disk with `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk` → SDK `openjdk-21`, all `jrt://` module roots filled in |
| 2026-11-15 | 5 | **Check 5 (highlighting) passed:** JDK indexed (`Number of scanned files: 28518`); `unused` greyed out, NPE warning on `greeting.length()`, 2 warnings + 1 weak warning, `String`/`System.out.println` resolve, run gutter icons on `Main`/`main`. The `/data` listing problem did not block SDK setup or JDK indexing. Completion on `System.out.` still to be checked by hand |
| 2026-11-15 | 5 | **Completion works** (user). New problem: screen flickers and `Main.java` closes every 1–2 min. Connection is stable (no drops in the client log). Backend log: `Native file watcher is disabled` (`fsnotifier` is glibc); a rescan with indexing runs every 10–15 s (`UnindexedFilesIndexer … [On refresh of files in smoke]`, the flicker); after a rescan the backend logs `Removing editor …/Main.java` and the file reopens with a new VFS id (`42955` → `43740`), i.e. VFS decided it was deleted. Likely cause: refresh lists from `/`, Android refuses listing `/`, `/data`, `/data/data`, and a refused listing reads as empty, so the whole tree is treated as deleted. Not yet proven (VFS doesn't log deletions by default). Next: quick test with sync-on-activation and idle-save turned off, then fix: option A, JBR patch `1004` (refused listing of a traversable folder returns the child on the path to Termux's home) or option B, an IntelliJ plugin |
| 2026-11-15 | 5 | With sync-on-activation off, the project disappeared from the IDE and stayed gone (files on disk intact, including the user's edit). Log: `Invalid (alien?) file: #46540 …`, scan count dropped from 28518 to 28458 (JDK only). **Cause proven in IntelliJ's bytecode:** `NioFiles.list` (`util-8.jar`) catches every `IOException` from `Files.newDirectoryStream` and returns `Collections.emptyList()`; `LocalFileSystemBase.list` passes it on; `RefreshWorker.fullDirRefresh` calls `generateDeleteEvents` for known children missing from the result. So `AccessDeniedException` on `/data` deletes the whole tree from VFS |
| 2026-11-15 | 4 | Patch `1004-list-known-children-of-unlistable-folders.patch` (commit `c83052e88`): in `UnixFileSystemProvider.newDirectoryStream`, on `EACCES` return the existing children on the path to the folders in system property `jdk.nio.fs.unlistableDirectoryHints` (`:`-separated; default `@TERMUX_PREFIX@`). Packaged as build 12 |
| 2026-11-15 | 5 | Patch `1004` verified on the phone (`~/intellij/list-check/ListCheck.java`): `/` → `[data]`, `/data` → `[data]`, `/data/data` → `[com.termux]`; listable folders unchanged (`/system` 19 entries, `/data/data/com.termux/files` → `[rList, home, usr]`). Termux OpenJDK still throws `AccessDeniedException` for the first three. Backend restarted on the patched JBR (`~/intellij/remote-dev-jbr-4.log`) |
| 2026-11-15 | 5 | **Patch `1004` confirmed by the user:** flicker gone, files stay open, project tree stays filled. (Separate: "output path is not specified" came from the hand-written test project missing `<output url="file://$PROJECT_DIR$/out" />` in `.idea/misc.xml`, not from Android.) **Steps 1–5 complete: IntelliJ's remote-dev backend runs natively on Android on our JBR, and JetBrains Client works against it** |
| 2026-11-15 | 4 | `1004` extended for shared storage. On the phone: `/storage` is `drwx--x--x` and fails with `EACCES`; `/storage/emulated` (FUSE) is `dr-xr-x---`, `stat` says directory, but listing fails with `ENOENT`; `EXTERNAL_STORAGE=/sdcard` → `/storage/emulated/0`. Now: `ENOENT` counts as unlistable when `stat` reports a directory; hints = `@TERMUX_PREFIX@` + `user.home` + `$EXTERNAL_STORAGE` (and real path) + `/storage/` mount points from `/proc/self/mounts` + the property (added to the defaults, no longer replacing them). Verified without the property: `/` → `[data, sdcard, storage]`, `/storage` → `[emulated]`, `/storage/emulated` → `[0]`, `/data/data` → `[com.termux]`. Measured why shared storage is still a poor place for code: no symlinks, no executable files, case-insensitive names, 26,720 ms vs 137 ms to write 2,000 small files |
| 2026-11-15 | 4 | Moved into the repository `safeuq/intellij-android`. First clean build with `jbr/scripts/build.sh`: fresh termux-packages fetch at `a755405`, new container `intellij-android-builder`, patch check passed, 16 minutes (12:25–12:41 IST, no LTO), `output/jbr-25_25.0.4.1_aarch64.deb` (186 MB) |
| 2026-11-15 | 5 | That package on the phone: version `JBR-25.0.4.1+1-635.79-termux`, `JBR.isGraphicsUtilsSupported = true`, listings `/` → `[data, sdcard, storage]`, `/storage/emulated` → `[0]`, remote-dev server prints its join link |
| 2026-11-15 | follow-up | `fsnotifier` for Android (`backend/fsnotifier/`): `intellij-community` `1725b9d` compiles unchanged with NDK 26.1 for API 24; patch `1001` uses `1048576` when `max_user_watches` can't be read. On the phone: the folder test reports `CREATE`/`CHANGE` for new files and for files in a new subfolder; the backend on `~/intellij/smoke` starts it from `idea.properties`, no `gave up`; its inotify watches go 130 → 132 → 130 as two nested folders are created and removed |

## Follow-up: native file watcher (`fsnotifier`) for bionic

Done 2026-11-15, see [`fsnotifier.md`](fsnotifier.md).

Correction to what this section first said: the bundled `bin/fsnotifier` is statically linked, not a glibc binary, and it starts on the phone. It gives up at once because Android doesn't let apps read `/proc/sys/fs/inotify/max_user_watches` (backend log: `can't open /proc/sys/fs/inotify/max_user_watches: Permission denied`, then `File watcher gave up to operate`). The `Native file watcher is disabled` line came from the `inspect.sh` run, which runs IntelliJ as a command-line tool, and IntelliJ never starts the watcher there.

Fix: build the same source with the NDK for API 24 with one patch (when the count can't be read, use `1048576`), and point IntelliJ at it with `idea.filewatcher.executable.path` in the config folder's `idea.properties`. It doesn't replace `1004`: full refreshes from `/` still happen (startup, "Reload All from Disk"). Shared storage is a FUSE mount, which `fsnotifier` reports as unwatchable, so projects there still rely on rescans.
