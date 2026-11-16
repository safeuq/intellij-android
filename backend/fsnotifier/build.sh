#!/bin/bash
# Builds IntelliJ's Linux file watcher (fsnotifier) for Android (aarch64, API 24) with the Android NDK.
#
# Downloads the pinned source files from intellij-community into work/fsnotifier/src, applies our
# patches (backend/fsnotifier/*.patch, each named in docs/fsnotifier.md) and writes
# output/fsnotifier.
#
# Needs the Android NDK: ANDROID_NDK_HOME, or the newest NDK under ~/Library/Android/sdk/ndk
# (macOS) or ~/Android/Sdk/ndk (Linux).

set -euo pipefail

readonly INTELLIJ_COMMUNITY_COMMIT="1725b9d80229df4741d8089ba0011a8911e472d2"
readonly SOURCE_URL="https://raw.githubusercontent.com/JetBrains/intellij-community/$INTELLIJ_COMMUNITY_COMMIT/native/fsNotifier/linux"
readonly SOURCE_FILES=(main.c inotify.c util.c fsnotifier.h)
readonly ANDROID_API=24

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly recipe_dir="$repository_root/backend/fsnotifier"
readonly source_dir="$repository_root/work/fsnotifier/src"
readonly output_dir="$repository_root/output"

find_ndk() {
	if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
		echo "$ANDROID_NDK_HOME"
		return
	fi
	local candidate
	for candidate in "$HOME/Library/Android/sdk/ndk" "$HOME/Android/Sdk/ndk"; do
		if [[ -d "$candidate" ]]; then
			# newest version last
			ls -d "$candidate"/*/ 2>/dev/null | sort -V | tail -n 1 | sed 's#/$##'
			return
		fi
	done
}

readonly ndk_dir="$(find_ndk)"
if [[ -z "$ndk_dir" ]]; then
	echo "Android NDK not found: set ANDROID_NDK_HOME" >&2
	exit 1
fi
readonly toolchain_bin="$(ls -d "$ndk_dir"/toolchains/llvm/prebuilt/*/bin | head -n 1)"
readonly compiler="$toolchain_bin/aarch64-linux-android$ANDROID_API-clang"
if [[ ! -x "$compiler" ]]; then
	echo "Compiler not found: $compiler" >&2
	exit 1
fi

# Every patch must be named in docs/fsnotifier.md, so no change goes unrecorded.
for patch_file in "$recipe_dir"/*.patch; do
	if ! grep -qF "$(basename "$patch_file")" "$repository_root/docs/fsnotifier.md"; then
		echo "$(basename "$patch_file") is not named in docs/fsnotifier.md" >&2
		exit 1
	fi
done

rm -rf "$source_dir"
mkdir -p "$source_dir" "$output_dir"
for file_name in "${SOURCE_FILES[@]}"; do
	curl -fsSL -o "$source_dir/$file_name" "$SOURCE_URL/$file_name"
done
for patch_file in "$recipe_dir"/*.patch; do
	echo "Applying $(basename "$patch_file")"
	patch -p1 -s -d "$source_dir" <"$patch_file"
done

# Same flags as JetBrains' make.sh, minus -static: Termux runs ordinary bionic executables.
"$compiler" -O2 -Wall -Wextra -Wpedantic -std=c11 \
	-D "VERSION=\"${INTELLIJ_COMMUNITY_COMMIT:0:10}-android\"" \
	"$source_dir"/main.c "$source_dir"/inotify.c "$source_dir"/util.c \
	-o "$output_dir/fsnotifier"
"$toolchain_bin/llvm-strip" "$output_dir/fsnotifier"
ls -la "$output_dir/fsnotifier"
