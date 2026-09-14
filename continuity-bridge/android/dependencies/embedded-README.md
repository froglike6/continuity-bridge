# Embedded ADB dependencies

`embedded-fetch.sh DEST` prepares the pinned Java/native inputs for the manual
`javac` + `d8` build. Use a clean destination dedicated to this bundle.
`CONTINUITY_EMBEDDED_CACHE` overrides the default `.embedded-cache` directory.
The current application uses only the embedded inputs below. The legacy
`build/shizuku` directory name does not imply a Shizuku SDK/runtime dependency;
`fetch.sh`, `shizuku.lock`, and their `.cache` are not current build inputs.

The lock is parsed as data, never sourced/evaluated. Every cached artifact,
including source archives and POMs, is SHA-256 checked on every invocation. A
corrupt cache entry is rejected without silently downloading a replacement.
All downloads and extraction finish in a temporary stage before output files
are copied. Cached AARs/JARs remain immutable.

| Artifact | Build use | Runtime output |
| --- | --- | --- |
| libadb-android 3.1.1 | javac + D8 input | `jars/libadb-android-3.1.1.jar` |
| spake2-android 2.2.1 | javac + D8 input | `jars/spake2-android-2.2.1.jar`, both `libspake2.so` ABIs |
| bcprov-jdk15to18 1.81 | javac + D8 input | `jars/bcprov-jdk15to18-1.81.jar`, JAR resources |
| conscrypt-android 2.7.0 | javac + D8 input | `jars/conscrypt-android-2.7.0.jar`, both `libconscrypt_jni.so` ABIs, JAR resource |
| AndroidX annotation 1.3.0 | javac / D8 `--classpath` only | No annotation bytecode in APK |

`runtime-jars.txt` and `compile-only-jars.txt` contain relative paths for the
exact input sets. The published libadb/SPAKE2 POMs request annotation 1.9.1;
this Java-only build deliberately uses 1.3.0 for compile-only annotations.
Every annotation imported by those sources exists in 1.3.0. Recompiling all
upstream libadb 3.1.1 Java sources against this bundle verified compatibility;
no Kotlin runtime is added. Conscrypt 2.7.0 provides the `newProvider()` and
`exportKeyingMaterial(SSLSocket, String, byte[], int)` APIs used by libadb.

## Local PairingConnectionCtx replacement

If `patches/PairingConnectionCtx.java` exists beside this fetch script, the
staged libadb JAR has only
`io/github/muntashirakon/adb/PairingConnectionCtx*.class` removed. The script
requires the base class to exist and checks no matching class remains.
Compile the replacement source with the application sources. A copy is
included in `sources/`; the original cached AAR is unmodified. The patch
records its upstream revision and Apache-2.0 selection. It adds bounded,
cancellable I/O, partial-initialization cleanup, and omits peer identity logs.

## Current manual build integration

`../build.sh` is the actual build entry point. It uses `BUILD/dependencies`,
compiles application, generated AIDL/resource, and patch Java sources, and
passes the four runtime JARs to D8. Its key dependency commands are:

```sh
"$ANDROID_DIR/dependencies/embedded-fetch.sh" "$BUILD/dependencies"
DEPENDENCY_CP=$(find "$BUILD/dependencies/jars" -name '*.jar' -print | LC_ALL=C sort | paste -sd ':' -)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM_JAR:$TOOLS_DIR/core-lambda-stubs.jar" \
  -classpath "$DEPENDENCY_CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$TOOLS_DIR/d8" --lib "$PLATFORM_JAR" \
  --classpath "$BUILD/dependencies/jars/annotation-1.3.0.jar" \
  --min-api 29 --output "$BUILD/dex" "$BUILD/classes.zip" \
  "$BUILD/dependencies/jars/libadb-android-3.1.1.jar" \
  "$BUILD/dependencies/jars/spake2-android-2.2.1.jar" \
  "$BUILD/dependencies/jars/bcprov-jdk15to18-1.81.jar" \
  "$BUILD/dependencies/jars/conscrypt-android-2.7.0.jar"
```

D8 does not copy JAR resources, JNI libraries, or license assets into an APK.
The actual build adds every `classes*.dex`, `lib/`, `assets/`, and `resources/`
explicitly, then runs `zipalign -P 16` and signs with a local debug key.
Its ZIP command compresses native libraries and the manifest sets
`android:extractNativeLibs="true"`; Android extracts them at installation.
The ZIP alignment check does not by itself prove native runtime compatibility.

The runtime resource set includes Conscrypt version/BoringSSL provenance,
BC provider service metadata, BC certificate-review messages and BC Picnic
parameter data. Original JAR manifests and cryptographic signature metadata
are excluded from the APK resource tree. No external Shizuku SDK/provider or
runtime JAR is passed to this build.

The checked-in build script has hardcoded macOS Android SDK/JBR/keystore
paths. The source handoff kit documents the variables and verification-script
paths that a recipient must edit in their own copy. The kit is not a claim
of an unchanged portable build or of byte-for-byte reproducible APK signing.

## Native compatibility evidence

Only arm64-v8a and x86_64 are staged. These binaries need Android system
`libc`, `libm`, `libdl`, and (Conscrypt only) `liblog`; no additional shared
C++ runtime was declared by their dynamic sections.

`embedded-native-alignment.txt` preserves the initial static inspection:
exact native hashes, LOAD alignment, and GNU_RELRO end alignment. All four
pinned binaries have LOAD alignment `2**14`. Conscrypt 2.7.0 also passes the
RELRO end check. SPAKE2 2.2.1 ends RELRO at `0xb000` (arm64) / `0xd000`
(x86_64), which does not pass that additional 16 KB arithmetic check.
That remains a compatibility caveat, not an observed failure on every device.

On 2026-09-13, the actual application ran on an API 37 arm64 emulator where
`getconf PAGE_SIZE` returned `16384`. The original pinned SPAKE2 and Conscrypt
native libraries loaded, wireless ADB SPAKE2 pairing succeeded, and the app
ran a shell command and its shell-UID clipboard helper successfully. Text and
image clipboard transfer and reboot recovery were also exercised; see
[EMBEDDED_HELPER.md](../EMBEDDED_HELPER.md) for the full observed scope. This
runtime result supersedes the static report's earlier "pending runtime"
status for that arm64 environment. No SPAKE2 native rebuild was needed there.

x86_64 native runtime and the source kit's native replacement build recipe
remain untested. These results do not establish all
16 KB devices, physical Galaxy behavior, or Android 11/12 behavior. Preserve
the static RELRO caveat and repeat native loading/pairing tests for each new
target or replacement binary. See [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).

## Source and license distribution

`assets/licenses/embedded/NOTICE.txt` gives the selected licenses and
copyright notices. `SOURCES.txt` records immutable upstream revisions and
actual source URLs. Full Apache, LGPL-3.0, GPL-3.0 and component notices are
included. `sources/` contains pinned libadb, SPAKE2-Java, SPAKE2-C and
Conscrypt snapshots plus BC/annotation source JARs. BoringSSL's exact source
revision is identified separately from the Conscrypt binary's property file.

A GitHub source archive does not populate submodules. To reconstruct the
SPAKE2 tree, extract the Java archive, then extract the spake2-c archive into
`android/src/main/cpp/spake2-c` with `--strip-components=1`. Preserve the
original archives when making changes. Its Gradle/CMake build files and JNI
wrapper are included in those snapshots.

`outputs/continuity-bridge-android-embedded-source.zip` is the companion
source/relink kit for the embedded APK. Its root `README.md` documents the
corresponding APK hash, actual app/helper/AIDL/resource and build-script
inventory, editable tool paths, replacing the SPAKE2 Java/native inputs,
and signing and installing a rebuild with one's own debug key. Its
`SHA256SUMS` identifies the included files. No project signing key, runtime
configuration, local ADB identity, or production credential is included.
The canonical CA is a public certificate only. Unmodified upstream source
archives retain upstream test data, including public Conscrypt test-key
fixtures; these are identified in the kit's inspection record.

For redistributing the combined APK using LGPL-3.0 section 4(d)(0), provide
its corresponding application/helper code, dependency sources, build scripts,
and required installation/relinking information. Keep the LGPL/GPL texts and
copyright notices with modified distributions and identify library changes.
An APK license asset and upstream links alone are not the complete source kit.
The dependency documentation does not grant a new application license or
assert legal sufficiency for a distribution whose application licensing has
not been specified. The bundled license texts state their actual terms.

## Verification performed

On 2026-09-13 in an isolated research directory: fresh downloads succeeded;
cache reuse succeeded; a deliberately corrupted AAR was rejected before
jars/native outputs were staged. With the patch present, only the intended
PairingConnectionCtx classes were removed. The original libadb AAR retained
its locked hash. The full upstream Java compilation succeeded with 11
upstream lint warnings, and D8 succeeded without warnings, producing one
5,212,928-byte dex. Those isolated dependency checks did not use a device.
The later application/runtime validation is described separately above and
in `EMBEDDED_HELPER.md`.
