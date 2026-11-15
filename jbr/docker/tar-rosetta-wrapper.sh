#!/bin/bash
# Installed as /usr/local/bin/tar in the termux-package-builder container.
#
# Docker Desktop runs the amd64-only builder image under Rosetta, and Rosetta does not
# implement the openat2 syscall. GNU tar 1.35 needs openat2 to create nested directories
# while extracting, so it fails with "Cannot mkdir: Function not implemented".
# bsdtar does not use openat2, so extraction goes to bsdtar; everything else (creating
# the .deb with --sort=name) stays on GNU tar, which only needs openat2 when extracting.

set -euo pipefail

readonly gnu_tar=/usr/bin/tar

is_extract=false
for argument in "$@"; do
	case "$argument" in
		--extract | --get) is_extract=true ;;
		--*) ;;
		-*x*) is_extract=true ;;
	esac
done
# Old-style first argument without a dash, e.g. "xf" or "xJ"
if [[ $# -gt 0 && "$1" != -* && "$1" == *x* ]]; then
	is_extract=true
fi

if [[ "$is_extract" == false ]]; then
	exec "$gnu_tar" "$@"
fi

bsdtar_arguments=()
first=true
for argument in "$@"; do
	if [[ "$first" == true && "$argument" != -* ]]; then
		# bsdtar accepts the old-style bundled form only with a leading dash
		bsdtar_arguments+=("-$argument")
	else
		case "$argument" in
			--transform=*)
				# GNU: --transform='s#^.$#data#'  ->  bsdtar: -s '#^.$#data#'
				bsdtar_arguments+=("-s" "${argument#--transform=s}")
				;;
			--no-overwrite-dir)
				# GNU tar leaves existing folders such as / alone. bsdtar would try to set their
				# times and fail as a non-root user, so skip restoring times (-m) instead.
				bsdtar_arguments+=("-m")
				;;
			*)
				bsdtar_arguments+=("$argument")
				;;
		esac
	fi
	first=false
done

exec /usr/bin/bsdtar "${bsdtar_arguments[@]}"
