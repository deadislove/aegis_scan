# Build & Linking

## C++ core

```bash
cd core
make lib    # core/build/libaegis_core.{dylib,so}  (or cross-compile for .dll)
make jni    # core/build/libaegis_jni.{dylib,so}    (needs JAVA_HOME)
make test   # GoogleTest checkout required: GTEST_DIR (default /tmp/aegis_gtest)
make asan   # AddressSanitizer + UndefinedBehaviorSanitizer
make tsan   # ThreadSanitizer (concurrency suites)
```

No CMake — `core/Makefile` is the single source of truth for every native
artifact every other platform links against. `make lib`/`make jni` are
idempotent (re-run them any time another platform's build complains a
`.dylib`/`.so` is missing).

## Linking the native core per platform

| Platform | How | Where it ends up |
|---|---|---|
| .NET 8 | `[LibraryImport("aegis_core")]` resolves `aegis_core.dll` / `libaegis_core.so` / `libaegis_core.dylib` from the app directory | ship as `runtimes/<rid>/native/<file>` in a NuGet package; for MAUI add a `NativeReference` (iOS) / `AndroidNativeLibrary` (Android), or use `NativeLibrary.SetDllImportResolver` for a custom location |
| Java (FFM) | `new FintechCoreInterop(Path)` with an absolute path, or `null` to resolve via `java.library.path` | wherever you deploy the `.so`/`.dylib`/`.dll` next to your JAR |
| Java/Android (JNI) | `FintechCoreJni.load("/abs/path/libaegis_jni.so")`, or `loadLibrary()` with it on `java.library.path`/`jniLibs/` | `libaegis_jni` statically contains the whole core, so it's the only file you ship for this path |
| Android (NDK) | `$NDK/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang++ -std=c++20 -O2 -fPIC -shared -fvisibility=hidden -DAEGIS_CORE_BUILD -Icore/include core/src/fintech_core_c.cpp core/bindings/jni/aegis_jni.cpp -o libaegis_jni.so` (repeat per ABI) | `jniLibs/<abi>/libaegis_jni.so` |
| iOS | Built automatically by SwiftPM (`CAegisCore` target in the root `Package.swift`) | static, linked straight into the app |

## Per-platform test/build commands

```bash
# Android (needs the Android SDK; see below if ./gradlew can't find a JDK/SDK)
cd android && ./gradlew :aegisscan-core:test :aegisscan-camera:testDebugUnitTest

# Web
cd web && npm install && npm test

# Java (JDK 21: FFM is preview there, final in 22+)
javac --release 21 --enable-preview -cp junit-console.jar -d out $(find bindings/java/src -name '*.java')
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -Daegis.core.lib=core/build/libaegis_core.dylib -Daegis.jni.lib=core/build/libaegis_jni.dylib \
     -jar junit-console.jar execute -cp out --select-class com.aegisscan.interop.InteropTest

# .NET
cd bindings/dotnet && dotnet test AegisScan.Interop.Tests
```

## Sample projects

Each sample under `sample/` is independently buildable; see its own
`README.md` for exact commands. Quick reference:

```bash
sample/csharp/AegisScan.Sample.Console  -> dotnet run
sample/java                              -> make run
sample/web                               -> node serve.mjs  (after building web/)
sample/ios/CoreDemo                      -> swift run
sample/android (via android/gradlew)     -> ./gradlew :sample-android:assembleDebug
```

## Android toolchain notes

This repo's `android/` Gradle project needs a Gradle Wrapper, a JDK 17
toolchain, and an Android SDK — none of which ship with the source tree.
If you're setting this up fresh:

1. **Gradle Wrapper**: `android/gradlew` + `android/gradle/wrapper/` must
   exist (version matched to the Android Gradle Plugin in
   `android/build.gradle.kts` — AGP 8.7.3 wants Gradle 8.9+). If missing,
   generate it with any local Gradle install:
   `gradle wrapper --gradle-version 8.11.1` from `android/`.
2. **JDK 17 toolchain**: both `aegisscan-core` and `aegisscan-camera`
   request `jvmToolchain(17)` / `JavaVersion.VERSION_17`. If your machine
   only has a newer JDK, add the Foojay auto-provisioning plugin to
   `android/settings.gradle.kts` so Gradle downloads a matching JDK itself
   instead of failing with "Cannot find a Java installation...":
   ```kotlin
   plugins {
       id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
   }
   ```
3. **Android SDK**: install at minimum `platform-tools`,
   `platforms;android-35`, `build-tools;35.0.0` (e.g. via the
   `android-commandlinetools` Homebrew cask, then `sdkmanager --licenses`),
   and point `android/local.properties` at it: `sdk.dir=/path/to/sdk`.
4. **`android/gradle.properties`**: needs `android.useAndroidX=true` (the
   camera module depends on AndroidX) — AGP fails dependency resolution
   without it, with a message that looks unrelated to AndroidX at first
   glance.

With all four in place, `./gradlew :aegisscan-core:test
:aegisscan-camera:testDebugUnitTest :sample-android:assembleDebug` builds
and tests everything, including the sample APK.
