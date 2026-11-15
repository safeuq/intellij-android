#!/bin/bash
# Recreates a JBR git tree with all of the recipe's patches applied, one commit per patch,
# for writing or updating a patch. Not needed for building: build.sh downloads the source.
#
# Patches are applied as stored, so placeholders such as "@TERMUX_PREFIX@" stay in the tree
# and in anything exported from it; Termux's build fills them in. The tree is for editing
# patches, not for compiling: build through jbr/scripts/build.sh.
#
# Usage: jbr/scripts/dev-tree.sh [target-directory]   (default: work/jbr)
#
# New patch:     commit on top, then
#   git -C work/jbr format-patch -1 --stdout > jbr/termux-package/jbr-25/10NN-<name>.patch
# Change a patch: edit, `git commit --fixup <its commit>`, `git rebase -i --autosquash`, then
#   export that commit again over its file the same way (git format-patch -1 --stdout <commit>).

set -euo pipefail

readonly JBR_URL="https://github.com/JetBrains/JetBrainsRuntime.git"
readonly BRANCH_NAME="termux-android"

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly recipe_dir="$repository_root/jbr/termux-package/jbr-25"
readonly target_dir="${1:-$repository_root/work/jbr}"

if [[ -e "$target_dir" ]]; then
	echo "$target_dir already exists; remove it or pass another directory." >&2
	exit 1
fi

# GNU patch, as Termux uses; macOS's own patch handles some hunks differently.
patch_tool="$(command -v gpatch || true)"
if [[ -z "$patch_tool" ]]; then
	if patch --version 2>/dev/null | grep -q "GNU patch"; then
		patch_tool="$(command -v patch)"
	else
		echo "GNU patch is required (macOS: brew install gpatch)" >&2
		exit 1
	fi
fi

# The JBR tag and version come from the recipe, so they can't drift apart.
jbr_version="$(sed -n 's/^TERMUX_PKG_VERSION="\(.*\)"$/\1/p' "$recipe_dir/build.sh")"
jbr_build="$(sed -n 's/^_JBR_BUILD="\(.*\)"$/\1/p' "$recipe_dir/build.sh")"
if [[ -z "$jbr_version" || -z "$jbr_build" ]]; then
	echo "Could not read TERMUX_PKG_VERSION and _JBR_BUILD from $recipe_dir/build.sh" >&2
	exit 1
fi
readonly jbr_tag="jbr-release-${jbr_version}b${jbr_build}"

echo "Cloning $jbr_tag into $target_dir"
git clone -q --depth 1 --branch "$jbr_tag" "$JBR_URL" "$target_dir"
git -C "$target_dir" switch -q -c "$BRANCH_NAME"

for patch_file in "$recipe_dir"/*.patch "$recipe_dir"/tmpdir-path-length.diff; do
	patch_name="$(basename "$patch_file")"
	(cd "$target_dir" && "$patch_tool" -p1 --forward --no-backup-if-mismatch -s) < "$patch_file" || {
		echo "Patch did not apply: $patch_name" >&2
		exit 1
	}
	git -C "$target_dir" add -A
	git -C "$target_dir" commit -q -m "patch: $patch_name"
done

echo "Done: $target_dir, branch $BRANCH_NAME ($(git -C "$target_dir" rev-list --count "$jbr_tag"..HEAD) patch commits)"
