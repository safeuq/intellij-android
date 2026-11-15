#!/bin/bash
# Lists, for every shared library in a JDK image, the undefined dynamic symbols that none of
# its DT_NEEDED libraries (searched transitively) export.
#
# On glibc such symbols can still resolve at runtime from libraries loaded earlier. Android's
# loader does not resolve them from libraries Java loaded, so each one is a likely
# "dlopen failed: cannot locate symbol" on the phone.
#
# Usage: find-unresolved-symbols.sh <jdk-image-lib-dir> <termux-prefix-lib-dir> <ndk-sysroot-lib-dir>
# Prints nothing when every symbol is provided.
#
# Run it inside the builder container after a build (needs bash 4 and the NDK's llvm tools):
#   docker cp jbr/scripts/find-unresolved-symbols.sh intellij-android-builder:/tmp/
#   docker exec intellij-android-builder bash -c '
#     toolchain=~/.termux-build/_cache/android-r30-api-24-v0
#     NM_TOOL=$toolchain/bin/llvm-nm READELF_TOOL=$toolchain/bin/llvm-readelf \
#       bash /tmp/find-unresolved-symbols.sh \
#         ~/.termux-build/jbr-25/src/build/linux-aarch64-server-release/images/jdk/lib \
#         /data/data/com.termux/files/usr/lib \
#         $toolchain/sysroot/usr/lib/aarch64-linux-android/24'

set -euo pipefail

if [[ $# -ne 3 ]]; then
	echo "Usage: $0 <jdk-image-lib-dir> <termux-prefix-lib-dir> <ndk-sysroot-lib-dir>" >&2
	exit 2
fi

readonly jdk_lib_dir="$1"
readonly termux_lib_dir="$2"
readonly ndk_lib_dir="$3"
readonly nm_tool="${NM_TOOL:-llvm-nm}"
readonly readelf_tool="${READELF_TOOL:-llvm-readelf}"

find_library() {
	local library_name="$1"
	local directory
	for directory in "$jdk_lib_dir" "$jdk_lib_dir/server" "$termux_lib_dir" "$ndk_lib_dir"; do
		if [[ -f "$directory/$library_name" ]]; then
			echo "$directory/$library_name"
			return
		fi
	done
}

needed_libraries() {
	"$readelf_tool" --dynamic "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
}

# Prints the exported symbols of a library and of everything it needs, transitively.
exported_symbols_transitive() {
	local -A visited=()
	local -a queue=("$1")
	while [[ ${#queue[@]} -gt 0 ]]; do
		local current="${queue[0]}"
		queue=("${queue[@]:1}")
		[[ -n "${visited[$current]:-}" ]] && continue
		visited[$current]=1
		"$nm_tool" -D --defined-only "$current" 2>/dev/null | awk '{print $NF}' | sed 's/@.*//'
		local needed
		while read -r needed; do
			[[ -z "$needed" ]] && continue
			local path
			path="$(find_library "$needed")"
			if [[ -z "$path" ]]; then
				echo "MISSING-LIBRARY:$needed" >&2
				continue
			fi
			queue+=("$path")
		done < <(needed_libraries "$current")
	done
}

for library in "$jdk_lib_dir"/*.so "$jdk_lib_dir"/server/*.so; do
	[[ -f "$library" ]] || continue
	provided="$(
		{
			# The library itself, everything it needs, and libjvm, which Java always loads first
			# into the same namespace and every JDK library may call into.
			exported_symbols_transitive "$library"
			"$nm_tool" -D --defined-only "$jdk_lib_dir/server/libjvm.so" | awk '{print $NF}'
		} 2>/dev/null | sort -u
	)"
	unresolved="$(
		"$nm_tool" -D --undefined-only "$library" 2>/dev/null \
			| awk '$1 == "U" {print $2}' | sed 's/@.*//' | sort -u \
			| comm -23 - <(echo "$provided")
	)"
	if [[ -n "$unresolved" ]]; then
		echo "== $(basename "$library")  needs: $(needed_libraries "$library" | tr '\n' ' ')"
		echo "$unresolved" | sed 's/^/   /'
	fi
done
