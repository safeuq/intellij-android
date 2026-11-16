# `fsnotifier` for Android

IntelliJ's Linux file watcher (`intellij-community`, `native/fsNotifier/linux`), built for Android with the NDK. It uses `inotify` to tell IntelliJ about changes as they happen. Without it, IntelliJ finds changes by rescanning on a timer and when its window gets focus.

The `bin/fsnotifier` that ships with IntelliJ is statically linked and starts on the phone, but stops at once:

```
can't open /proc/sys/fs/inotify/max_user_watches: Permission denied
GIVEUP
```

Android doesn't let apps read that file, and `fsnotifier` treats a missing watch count as fatal.

## Build

```sh
backend/fsnotifier/build.sh      # writes output/fsnotifier (aarch64, API 24)
```

It downloads the four source files at `intellij-community` commit `1725b9d80229df4741d8089ba0011a8911e472d2` (the last change to `native/fsNotifier/linux`) into `work/fsnotifier/src`, applies the patches below, and compiles with `aarch64-linux-android24-clang` and the flags from JetBrains' `make.sh`. The script stops if a `.patch` file in `backend/fsnotifier/` isn't named in this file.

## Use

Copy it to the phone and point IntelliJ at it in the `idea.properties` file of IntelliJ's config folder. Both the remote-dev backend and the desktop IntelliJ read that file:

```sh
scp -P 8022 output/fsnotifier <phone-address>:intellij/fsnotifier
# on the phone; intellij-backend setup adds the same line to the right config folder:
echo "idea.filewatcher.executable.path=$HOME/intellij/fsnotifier" >> ~/.config/JetBrains/IntelliJIdea2026.3/idea.properties
```

IntelliJ's log then shows `Starting file watcher: /data/data/com.termux/files/home/intellij/fsnotifier` and `Native file watcher is operational.`, with no `File watcher gave up to operate` after it. (`operational` alone proves nothing: IntelliJ logs it as soon as the process starts, and the bundled build logs it too before giving up.)

Shared storage (`/storage/emulated/…`) is a FUSE mount, which `fsnotifier` reports to IntelliJ as unwatchable on any Linux. Projects there keep the periodic rescans; projects in Termux's home get the watcher.

## Patches

| Patch | Changes | Why |
|---|---|---|
| `1001-android-default-watch-count.patch` | `inotify.c`: on `__ANDROID__`, when `/proc/sys/fs/inotify/max_user_watches` can't be opened, use `1048576` instead of giving up | The count only sizes `fsnotifier`'s own watch table (indexed by watch descriptor modulo its size). `1048576` is the largest default the kernel picks, so two live descriptors rarely share a slot. The kernel still enforces the real limit, and running out is reported to IntelliJ as before (`inotify.watch.limit`) |
