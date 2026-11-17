"""ExaltDS's on-device C compiler: LLVM/Clang 21 built for Android by Termux, laid out for the APK.

ExaltDS's setup recompiles the player's own game into native code on the device (3dsrecomp's C,
compiled and linked there), so the APK carries a compiler:

- lib/arm64-v8a/: clang (libexalt-clang.so), lld (libexalt-ld-.so: lld splits its file name at
  dashes and takes the part "ld" as GNU ld) and the libraries they load. Android installs only lib*.so files from the APK and runs
  programs only from there, so the programs get those names, and the libraries Termux names with a
  version (libz.so.1, libzstd.so.1, libxml2.so.16) are renamed, in every file that names them,
  to a name of the same length.
- assets/clang-kit.zip: clang's own headers and builtins (lib/clang/21) and the Android NDK's
  sysroot for API 30 (C headers, crt objects, libc/libm/libdl stubs, libunwind.a), which setup
  unpacks into the app's files.

The packages are pinned with their SHA-256 and kept in a cache (Termux's pool drops old versions).
LLVM is Apache-2.0 with LLVM exceptions; its license goes in with the app (assets/licenses).

Usage: python clang-kit.py <android NDK> <out dir> [<cache dir>]
"""
import hashlib
import io
import os
import shutil
import sys
import tarfile
import urllib.request
import zipfile

POOL = 'https://packages.termux.dev/apt/termux-main/pool/main/'
PACKAGES = [
    ('c/clang/clang_21.1.8-3_aarch64.deb', '9fb565013b96ce10abd8f8f217aaebb48f72873f53acbf8886329bcea841a21c'),
    ('l/lld/lld_21.1.8-3_aarch64.deb', '6299c8fdff59dcff972f6b444805bcdb211c2bd797a64d2ddb3875e699f30630'),
    ('libl/libllvm/libllvm_21.1.8-3_aarch64.deb', '5056a73f2645fc9c0759e6f94a1bd6edcf36305acaef58dc685211ced9cc2a02'),
    ('libc/libc++/libc++_30_aarch64.deb', '53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca'),
    ('libx/libxml2/libxml2_2.15.4-1_aarch64.deb', 'd315861bbcf716cfb088f53cddab9fb6a96430cc66769f79774cc69c2f53bcff'),
    ('z/zlib/zlib_1.4_aarch64.deb', 'e3027565ae50bcd8ba5113c988b589754ae192c9cc3e6afbe3e4ed7c06b2598e'),
    ('libf/libffi/libffi_3.8.1_aarch64.deb', '9e13f3b5c40625c384101992bb669a278e07d9e2baa37d286d7ed164288fab1a'),
    ('z/zstd/zstd_1.5.7-1_aarch64.deb', 'e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3'),
    ('liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb', '2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826'),
    ('libi/libiconv/libiconv_1.19.1_aarch64.deb', 'a1c6323eff4f660e16ae6f5832356444b3269c19a4f61146355a49e7c4be5ed1'),
    ('libc/libcompiler-rt/libcompiler-rt_21.1.8-3_aarch64.deb', '89de02f642e34fa6494ad16105bd08510bd1dbdbe4fdb2b297aaa4f4e8e534b6'),
]
USR = 'data/data/com.termux/files/usr/'
# installed name: (file in the packages, real name)
PROGRAMS = {'libexalt-clang.so': 'bin/clang-21', 'libexalt-ld-.so': 'bin/lld'}
LIBRARIES = ['libLLVM.so', 'libclang-cpp.so', 'libc++_shared.so', 'libffi.so', 'libiconv.so', 'libandroid-glob.so',
             'libcharset.so', 'libz.so.1', 'libzstd.so.1', 'libxml2.so.16']
RENAMES = {b'libz.so.1': b'libz_1.so', b'libzstd.so.1': b'libzstd_1.so', b'libxml2.so.16': b'libxml2_16.so'}
API = '30'


def fetch(cache):
    os.makedirs(cache, exist_ok=True)
    for path, sha in PACKAGES:
        file = os.path.join(cache, os.path.basename(path))
        if not os.path.isfile(file) or hashlib.sha256(open(file, 'rb').read()).hexdigest() != sha:
            print('fetching', path)
            data = urllib.request.urlopen(POOL + path, timeout=120).read()
            if hashlib.sha256(data).hexdigest() != sha:
                sys.exit(f'{path}: SHA-256 does not match the pinned one')
            open(file, 'wb').write(data)
        yield file


def unpack(debs):
    """every file of the packages' data, by its path under usr/ (symbolic links followed)"""
    files, links = {}, {}
    for deb in debs:
        data = open(deb, 'rb').read()
        assert data[:8] == b'!<arch>\n', deb
        at = 8
        while at < len(data):
            name, size = data[at:at + 16].decode().strip(), int(data[at + 48:at + 58])
            body = data[at + 60:at + 60 + size]
            at += 60 + size + (size & 1)
            if not name.startswith('data.tar'):
                continue
            with tarfile.open(fileobj=io.BytesIO(body)) as tar:
                for member in tar.getmembers():
                    if not member.name.lstrip('./').startswith(USR):
                        continue
                    path = member.name.lstrip('./')[len(USR):]
                    if member.issym():
                        links[path] = os.path.normpath(os.path.join(os.path.dirname(path), member.linkname)).replace('\\', '/')
                    elif member.isfile():
                        files[path] = tar.extractfile(member).read()
    for path, target in links.items():
        while target in links:
            target = links[target]
        if target in files:
            files[path] = files[target]
    return files


def renamed(data):
    for old, new in RENAMES.items():
        assert len(old) == len(new)
        data = data.replace(b'\0' + old + b'\0', b'\0' + new + b'\0')
    return data


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    ndk, out = sys.argv[1], sys.argv[2]
    cache = sys.argv[3] if len(sys.argv) == 4 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../build/clang-kit-cache')
    files = unpack(list(fetch(cache)))

    libs = os.path.join(out, 'lib', 'arm64-v8a')
    os.makedirs(libs, exist_ok=True)
    for name, path in PROGRAMS.items():
        open(os.path.join(libs, name), 'wb').write(renamed(files[path]))
    for name in LIBRARIES:
        target = RENAMES.get(name.encode(), name.encode()).decode()
        open(os.path.join(libs, target), 'wb').write(renamed(files['lib/' + name]))

    # the NDK's prebuilt folder is named for the machine it runs on: windows-x86_64, linux-x86_64, darwin-x86_64
    prebuilt = os.path.join(ndk, 'toolchains', 'llvm', 'prebuilt')
    hosts = sorted(os.listdir(prebuilt))
    host = 'windows-x86_64' if 'windows-x86_64' in hosts else hosts[0]
    sysroot = os.path.join(prebuilt, host, 'sysroot')
    clang_lib = os.path.join(prebuilt, host, 'lib', 'clang')
    os.makedirs(os.path.join(out, 'assets'), exist_ok=True)
    with zipfile.ZipFile(os.path.join(out, 'assets', 'clang-kit.zip'), 'w', zipfile.ZIP_DEFLATED) as kit:
        for path, data in sorted(files.items()):
            if path.startswith('lib/clang/21/include/') or path == 'lib/clang/21/lib/linux/libclang_rt.builtins-aarch64-android.a':
                kit.writestr(path, data)
        include = os.path.join(sysroot, 'usr', 'include')
        others = {'arm-linux-androideabi', 'i686-linux-android', 'x86_64-linux-android', 'riscv64-linux-android', 'c++'}
        for root, dirs, names in os.walk(include):
            dirs[:] = [d for d in dirs if not (root == include and d in others)]
            for name in names:
                full = os.path.join(root, name)
                kit.write(full, 'sysroot/usr/include/' + os.path.relpath(full, include).replace('\\', '/'))
        lib = os.path.join(sysroot, 'usr', 'lib', 'aarch64-linux-android')
        for name in ['crtbegin_so.o', 'crtend_so.o', 'libc.so', 'libm.so', 'libdl.so']:
            kit.write(os.path.join(lib, API, name), f'sysroot/usr/lib/aarch64-linux-android/{API}/{name}')
        unwind = [os.path.join(clang_lib, v, 'lib', 'linux', 'aarch64', 'libunwind.a') for v in os.listdir(clang_lib)]
        kit.write(next(u for u in unwind if os.path.isfile(u)), 'sysroot/usr/lib/aarch64-linux-android/libunwind.a')
    licenses = os.path.join(out, 'assets', 'licenses')
    os.makedirs(licenses, exist_ok=True)
    license = next((data for path, data in files.items() if path.startswith('share/doc/') and 'llvm' in path.lower() and path.endswith('LICENSE.TXT')), None)
    if license is None:
        license = next(data for path, data in files.items() if path.startswith('share/doc/') and path.endswith(('LICENSE', 'LICENSE.TXT', 'copyright')) and 'clang' in path)
    open(os.path.join(licenses, 'LLVM.txt'), 'wb').write(license)
    size = sum(os.path.getsize(os.path.join(libs, n)) for n in os.listdir(libs))
    print(f'clang kit: {size / 1048576:.0f} MB of programs and libraries, assets/clang-kit.zip '
          f'{os.path.getsize(os.path.join(out, "assets", "clang-kit.zip")) / 1048576:.0f} MB')


if __name__ == '__main__':
    main()
