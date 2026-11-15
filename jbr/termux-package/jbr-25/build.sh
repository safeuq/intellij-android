TERMUX_PKG_HOMEPAGE=https://github.com/JetBrains/JetBrainsRuntime
TERMUX_PKG_DESCRIPTION="JetBrains Runtime 25 with Termux's openjdk-25 Android patches"
TERMUX_PKG_LICENSE="GPL-2.0"
TERMUX_PKG_MAINTAINER="@termux"
# JBR tag jbr-release-25.0.4.1b635.79, the runtime bundled with IntelliJ IDEA 2026.3 (IU-263.7307.200)
TERMUX_PKG_VERSION="25.0.4.1"
_JBR_BUILD_NUMBER=1
_JBR_BUILD="635.79"
TERMUX_PKG_SRCURL="https://github.com/JetBrains/JetBrainsRuntime/archive/refs/tags/jbr-release-${TERMUX_PKG_VERSION}b${_JBR_BUILD}.tar.gz"
TERMUX_PKG_SHA256=c6522b2282a7f9573b3292735d47d04424c7731a3d54b769e0fc45332fe48e2f
TERMUX_PKG_AUTO_UPDATE=false
# Not headless-only: IntelliJ's remote-dev server (Main.initLux) sets java.awt.headless=false,
# so java.awt.Toolkit loads libawt_xawt.so even though lux draws through its own toolkit.
# A full Linux JBR build also requires Wayland and xkbcommon.
TERMUX_PKG_DEPENDS="ca-certificates-java, freetype, libandroid-shmem, libandroid-spawn, libiconv, libjpeg-turbo, zlib, littlecms, alsa-plugins, libx11, libxext, libxi, libxrender, libxtst, libwayland, libxkbcommon"
TERMUX_PKG_BUILD_DEPENDS="cups, fontconfig, freetype, alsa-lib, libxrandr, libxt, xorgproto, libwayland-protocols"
TERMUX_PKG_RECOMMENDS="resolv-conf"
TERMUX_PKG_EXCLUDED_ARCHES="arm, i686, x86_64"
TERMUX_PKG_BUILD_IN_SRC=true
TERMUX_PKG_HAS_DEBUG=false
TERMUX_PKG_HOSTBUILD=true
# Same as JetBrains' aarch64 JBR builds. Termux's openjdk-25 also enables link-time-opt, but the
# LTO link of libjvm.so needs more than the 12 GB Docker has on the Mac and gets killed.
__jvm_features="shenandoahgc"
_JBR_HOME_RELATIVE="lib/jvm/jbr-25"

termux_step_host_build() {
	# precompiled JDK release for GNU/Linux to use as host JDK for bootstrapping
	JDK_BINURL="https://download.java.net/java/GA/jdk25/bd75d5f9689641da8e1daabeccb5528b/36/GPL/openjdk-25_linux-x64_bin.tar.gz"
	JDK_BINARCHIVE="${TERMUX_PKG_CACHEDIR}/openjdk-25_linux-x64_bin.tar.gz"
	JDK_BINSHA256=59cdcaf255add4721de38eb411d4ecfe779356b61fb671aee63c7dec78054c2b
	termux_download "$JDK_BINURL" "$JDK_BINARCHIVE" "$JDK_BINSHA256"
	tar -xf "$JDK_BINARCHIVE" --strip-components=1 -C "$TERMUX_PKG_HOSTBUILD_DIR"
}

termux_step_pre_configure() {
	unset JAVA_HOME

	local patch="$TERMUX_PKG_BUILDER_DIR/tmpdir-path-length.diff"
	local tmpdir_path="$TERMUX_PREFIX/tmp"
	echo "Applying patch: $(basename "$patch")"
	test -f "$patch" && sed \
		-e "s%\@TERMUX_PREFIX\@%${TERMUX_PREFIX}%g" \
		-e "s%\@TERMUX_TMPDIR_PATH_LENGTH\@%${#tmpdir_path}%g" \
		"$patch" | patch --silent -p1
}

termux_step_configure() {
	# build-package.sh -c still runs this step. Re-running OpenJDK's configure would rewrite
	# spec.gmk and could force a large rebuild, so keep the existing configuration.
	if [[ "$TERMUX_CONTINUE_BUILD" == "true" && -f "build/linux-${TERMUX_ARCH}-server-release/spec.gmk" ]]; then
		echo "Continuing build: keeping existing OpenJDK configuration"
		return
	fi

	local jdk_ldflags="-L${TERMUX_PREFIX}/lib \
		-Wl,-rpath=$TERMUX_PREFIX/$_JBR_HOME_RELATIVE/lib \
		-Wl,-rpath=${TERMUX_PREFIX}/lib -Wl,--enable-new-dtags"
	bash ./configure \
		--with-boot-jdk="$TERMUX_PKG_HOSTBUILD_DIR" \
		--disable-precompiled-headers \
		--disable-warnings-as-errors \
		--enable-option-checking=fatal \
		--enable-cds=yes \
		--with-vendor-name="JetBrains s.r.o." \
		--with-vendor-version-string="JBR-${TERMUX_PKG_VERSION}+${_JBR_BUILD_NUMBER}-${_JBR_BUILD}-termux" \
		--with-version-pre="" \
		--with-version-build="${_JBR_BUILD_NUMBER}" \
		--with-version-opt="b${_JBR_BUILD}" \
		--with-jvm-variants=server \
		--with-jvm-features="${__jvm_features}" \
		--with-debug-level=release \
		--openjdk-target=$TERMUX_HOST_PLATFORM \
		--with-toolchain-type=clang \
		--with-extra-cflags="$CFLAGS $CPPFLAGS -DLE_STANDALONE -D__ANDROID__=1 -D__TERMUX__=1" \
		--with-extra-cxxflags="$CXXFLAGS $CPPFLAGS -DLE_STANDALONE -D__ANDROID__=1 -D__TERMUX__=1" \
		--with-extra-ldflags="${jdk_ldflags} -Wl,--as-needed -landroid-shmem -landroid-spawn" \
		--with-cups-include="$TERMUX_PREFIX/include" \
		--with-fontconfig-include="$TERMUX_PREFIX/include" \
		--with-freetype-include="$TERMUX_PREFIX/include/freetype2" \
		--with-freetype-lib="$TERMUX_PREFIX/lib" \
		--with-alsa="$TERMUX_PREFIX" \
		--with-alsa-include="$TERMUX_PREFIX/include/alsa" \
		--with-alsa-lib="$TERMUX_PREFIX/lib" \
		--with-x="$TERMUX_PREFIX/include/X11" \
		--x-includes="$TERMUX_PREFIX/include/X11" \
		--x-libraries="$TERMUX_PREFIX/lib" \
		--with-wayland="$TERMUX_PREFIX" \
		--with-wayland-protocols="$TERMUX_PREFIX/share/wayland-protocols" \
		--with-xkbcommon="$TERMUX_PREFIX" \
		--without-speechd \
		--with-giflib=bundled \
		--with-lcms=system \
		--with-libjpeg=system \
		--with-libpng=bundled \
		--with-zlib=system \
		AR="$AR" \
		NM="$NM" \
		OBJCOPY="$OBJCOPY" \
		OBJDUMP="$OBJDUMP" \
		STRIP="$STRIP" \
		CXXFILT="llvm-cxxfilt" \
		BUILD_CC="$TERMUX_HOST_LLVM_BASE_DIR/bin/clang" \
		BUILD_CXX="$TERMUX_HOST_LLVM_BASE_DIR/bin/clang++" \
		BUILD_NM="$TERMUX_HOST_LLVM_BASE_DIR/bin/llvm-nm" \
		BUILD_AR="$TERMUX_HOST_LLVM_BASE_DIR/bin/llvm-ar" \
		BUILD_OBJCOPY="$TERMUX_HOST_LLVM_BASE_DIR/bin/llvm-objcopy" \
		BUILD_STRIP="$TERMUX_HOST_LLVM_BASE_DIR/bin/llvm-strip" \
		--with-jobs=$TERMUX_PKG_MAKE_PROCESSES
}

termux_step_make() {
	cd build/linux-${TERMUX_ARCH}-server-release
	make images
}

termux_step_make_install() {
	rm -rf $TERMUX_PREFIX/$_JBR_HOME_RELATIVE
	mkdir -p $TERMUX_PREFIX/$_JBR_HOME_RELATIVE
	cp -r build/linux-${TERMUX_ARCH}-server-release/images/jdk/* \
		$TERMUX_PREFIX/$_JBR_HOME_RELATIVE/
	find $TERMUX_PREFIX/$_JBR_HOME_RELATIVE/ -name "*.debuginfo" -delete
}

termux_step_post_make_install() {
	# Use system CA certificates, as openjdk-25 does
	local jbr_cacerts="$TERMUX_PREFIX/$_JBR_HOME_RELATIVE/lib/security/cacerts"
	local ca_certificates_cacerts="$TERMUX_PREFIX/etc/tls/certs/java/cacerts"
	rm "$jbr_cacerts"
	ln -s "$ca_certificates_cacerts" "$jbr_cacerts"
}
