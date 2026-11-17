#!/usr/bin/env bash
# ExaltDS's APK on a Linux machine (GitHub Actions' ubuntu runner), the same steps as build-windows.ps1 -Flavor exalt.
#
# VARIANT=hd (the default here) builds "ExaltDS HD", package io.github.exaltds.hd: it installs beside the released
# ExaltDS (whose key only its author has) instead of over it, with its own data. VARIANT=main keeps io.github.exaltds.
# The APK is signed with KEYSTORE (password KEYSTORE_PASSWORD, alias KEY_ALIAS), android/exalt/signing/debug.p12 by
# default: a debug key kept in the fork so each build updates the last one. It is public, so don't use it for releases.
#
# Needs: ANDROID_HOME (build-tools and platform below), ANDROID_NDK (r27c), rustup, cmake, ninja, python3, JDK 17.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
variant=${VARIANT:-hd}
build_tools=${BUILD_TOOLS:-35.0.0}
platform=${PLATFORM:-android-35}
keystore=${KEYSTORE:-$repo/android/exalt/signing/debug.p12}
export KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD:-android}
key_alias=${KEY_ALIAS:-exaltds-debug}
out=${OUT:-$repo/build/android-ci}
tools="$ANDROID_HOME/build-tools/$build_tools"
jar_android="$ANDROID_HOME/platforms/$platform/android.jar"
ndk_bin="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
for f in "$tools/aapt2" "$tools/d8" "$tools/zipalign" "$tools/apksigner" "$jar_android" "$ndk_bin/aarch64-linux-android30-clang" "$keystore"; do
    [[ -e "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-$repo/target}
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android30-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$out" "$work/classes" "$work/dex" "$work/lib/arm64-v8a" "$work/assets/licenses"

cmake_android=(-G Ninja "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384)
cmake -S "$repo/android/third_party/libadrenotools" -B "$CARGO_TARGET_DIR/adrenotools" "${cmake_android[@]}" -DBUILD_SHARED_LIBS=ON
cmake --build "$CARGO_TARGET_DIR/adrenotools" --parallel
for library in libadrenotools.so libhook_impl.so libmain_hook.so libfile_redirect_hook.so libgsl_alloc_hook.so; do
    mapfile -t found < <(find "$CARGO_TARGET_DIR/adrenotools" -type f -name "$library")
    ((${#found[@]} == 1)) || { echo "driver support library $library: ${#found[@]} found" >&2; exit 1; }
    "$ndk_bin/llvm-strip" -o "$work/lib/arm64-v8a/$library" "${found[0]}"
done
cmake -S "$repo/android/third_party/lsfg" -B "$CARGO_TARGET_DIR/lsfg-bridge" "${cmake_android[@]}"
cmake --build "$CARGO_TARGET_DIR/lsfg-bridge" --parallel
"$ndk_bin/llvm-strip" -o "$work/lib/arm64-v8a/liblsfg_bridge.so" "$CARGO_TARGET_DIR/lsfg-bridge/liblsfg_bridge.so"

rustup target add --toolchain 1.95.0 aarch64-linux-android
(cd "$repo" && cargo +1.95.0 build --release --locked --target aarch64-linux-android -p zakuro-android --lib --features zakuro-gpu/vulkan)
"$ndk_bin/llvm-strip" -o "$work/lib/arm64-v8a/libzakuro_android.so" "$CARGO_TARGET_DIR/aarch64-linux-android/release/libzakuro_android.so"

manifest="$repo/android/exalt/AndroidManifest.xml"
package=io.github.exaltds
if [[ "$variant" == hd ]]; then
    package=io.github.exaltds.hd
    sed -e 's/package="io.github.exaltds"/package="io.github.exaltds.hd"/' \
        -e 's/android:authorities="io.github.exaltds.saves"/android:authorities="io.github.exaltds.hd.saves"/' \
        -e 's/android:label="ExaltDS"/android:label="ExaltDS HD"/' "$manifest" > "$work/AndroidManifest.xml"
    manifest="$work/AndroidManifest.xml"
fi
"$tools/aapt2" compile --dir "$repo/android/exalt/res" -o "$work/resources.zip"
"$tools/aapt2" link -o "$work/unsigned.apk" --manifest "$manifest" -I "$jar_android" "$work/resources.zip"
mapfile -t sources < <(find "$repo/android/app/src/main/java" -name '*.java' | LC_ALL=C sort)
javac -nowarn -encoding UTF-8 --release 11 -classpath "$jar_android" -d "$work/classes" "${sources[@]}"
jar --create --file "$work/classes.jar" -C "$work/classes" .
"$tools/d8" --min-api 30 --lib "$jar_android" --output "$work/dex" "$work/classes.jar"
for license in ADRENOTOOLS LINKERNSBYPASS LSFG ZAKURO; do
    cp "$repo/android/app/src/main/assets/licenses/$license.txt" "$work/assets/licenses/"
done
# ExaltDS's on-device compiler (setup builds the game's code on the device)
python3 -I "$repo/android/exalt/clang-kit.py" "$ANDROID_NDK" "$work/kit" "${CLANG_KIT_CACHE:-$repo/build/clang-kit-cache}"
cp "$work/kit/assets/clang-kit.zip" "$work/assets/clang-kit.zip"
cp "$work/kit/assets/licenses/LLVM.txt" "$work/assets/licenses/LLVM.txt"
cp "$repo/crates/exalt-mods/NOTICE.md" "$work/assets/licenses/EXALT-MODS.txt"
jar --update --file "$work/unsigned.apk" --no-manifest --no-compress -C "$work/dex" classes.dex -C "$work" lib -C "$work" assets
jar --update --file "$work/unsigned.apk" --no-manifest -C "$work/kit" lib
"$tools/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
name=exaltds-$variant-$(date -u +%Y%m%d-%H%M%S)-$(cd "$repo" && git rev-parse --short HEAD 2>/dev/null || echo local).apk
"$tools/apksigner" sign --ks "$keystore" --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "$key_alias" --out "$out/$name" "$work/aligned.apk"
"$tools/apksigner" verify --print-certs "$out/$name"
"$tools/zipalign" -c -P 16 4 "$out/$name"
"$tools/aapt2" dump badging "$out/$name" | grep -q "package: name='$package'" || { echo "APK is not $package" >&2; exit 1; }
unzip -Z1 "$out/$name" | grep -qx 'lib/arm64-v8a/libzakuro_android.so'
(cd "$out" && sha256sum "$name" > "$name.sha256")
echo "APK: $out/$name"
