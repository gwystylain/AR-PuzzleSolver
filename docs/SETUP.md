# Setup

The project builds clean on this machine already: 31/31 core tests pass and
`:app:assembleDebug` produces a debug APK with no compiler warnings. This document
records the working configuration and how to reproduce it.

**Verified configuration**

| Component | Version | Notes |
| --- | --- | --- |
| JDK | Temurin 17.0.20 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot` |
| Gradle | 8.13 | via the checked-in wrapper |
| AGP | 8.9.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.1.20 | |
| compileSdk | 35 | AGP auto-downloaded the platform and build-tools 35.0.0 |
| Android Studio | 2026.1.3 | its bundled JBR is OpenJDK 25, which Gradle 8.13 does *not* support |

The last row is the one that matters: **do not build with Android Studio's bundled
JBR 25.** Gradle 8.13 and AGP 8.9 target JDK 17, so point the build at Temurin 17 —
either by setting `JAVA_HOME`, or in Studio via *Settings → Build, Execution, Deployment
→ Build Tools → Gradle → Gradle JDK*.

## 1. Install the toolchain

Two pieces:

1. **Temurin JDK 17** — this is what Gradle runs on. Android Studio's bundled JBR is
   too new (see the table above), so a standalone 17 is not optional.

   ```powershell
   winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
   ```

2. **Android Studio** — for the SDK manager, the emulator, `adb`, and the version
   catalog resolution that is a two-click fix in the IDE and a guessing game outside it.

   ```powershell
   winget install --id Google.AndroidStudio -e --accept-package-agreements --accept-source-agreements
   ```

Run Studio's setup wizard and accept the default SDK path. You do **not** need to hunt
down API 35 in the SDK Manager by hand: AGP downloads a missing compile platform and
its build-tools automatically on first build, which is how android-35 and build-tools
35.0.0 arrived here alongside the 37/36 that Studio installed by default.

If you would rather stay entirely on the command line, install the Android
**command-line tools** and run:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

## 2. Point the project at the SDK

`local.properties` is already present and gitignored. It uses forward slashes on
purpose — this is a Java properties file, where `\` is an escape character, so a
Windows path would otherwise need every separator doubled:

```properties
sdk.dir=C:/Users/Edward/AppData/Local/Android/Sdk
```

Android Studio rewrites this file when you open the project, which is fine.

## 3. Gradle wrapper

Already generated and checked in — `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar` are all present. Nothing to do.

To regenerate it against a different Gradle version you need Gradle itself once; note
that the winget package id `Gradle.Gradle` does **not** exist. Either install Gradle
manually, or reuse the distribution the wrapper already downloaded under
`~/.gradle/wrapper/dists/`.

## 4. Run the tests first

The `:core` module is pure JVM with no Android dependencies, so this needs only the JDK
and is the fastest way to find out whether the geometry and solvers actually compile and
behave:

```bash
./gradlew :core:test
```

All 31 pass. If you break one, treat an assertion failure as information about the
implementation rather than about the test — the maths in `WallSurfaceTest` and
`SudokuSolverTest` is derived from first principles and stated in the comments, so a
failure there is a real defect worth chasing.

On Windows the wrapper needs a JDK it can find. Set it once:

```powershell
$jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object Name -like 'jdk-17*' | Select-Object -First 1).FullName; setx JAVA_HOME $jdk; $env:JAVA_HOME = $jdk
```

## 5. Build and install

```bash
./gradlew :app:installDebug
```

## 6. Device requirements

- **ARCore support.** The manifest marks `android.hardware.camera.ar` as required, so
  the Play Store will not offer the app to unsupported hardware. Check yours against
  Google's ARCore supported-devices list.
- **Depth helps but is not required.** `WallFitter` works from ARCore feature points
  when the depth API is unavailable; convergence is slower and wants more panning.
- **OpenGL ES 3.0**, for `gl_FragDepth`, PBO readback and fence sync. Any ARCore device
  qualifies.

## 7. First run

1. Grant camera permission.
2. Point at the wall and pan slowly. The HUD reports the wall fit — expect it to say
   "flat wall" first and settle to a radius as you sweep more arc.
3. Once the wall is locked, coverage starts accumulating and the grid detector engages.
4. The HUD then reports the puzzle type, cells read, and the outcome.

If the wall never locks, the usual causes are a featureless surface (ARCore has nothing
to track), too-fast panning, or low light. The debug panel shows the fit's inlier count
and RMS, which distinguishes these.

## 8. Recording a scan to debug it later

Tap **Record**, do the scan, tap **Stop recording**. The dataset lands in the app's
external files directory:

```bash
adb shell ls /sdcard/Android/data/com.puzzlesolver.app/files/sessions
adb pull /sdcard/Android/data/com.puzzlesolver.app/files/sessions ./recordings
```

**Replay last** re-runs the most recent one through the identical pipeline, with real
ARCore poses replayed from the recording. Attach a debugger and iterate without standing
in front of the wall.

## 9. Debugging notes

Useful log tags:

```bash
adb logcat -s ScanPipeline WallTracker ArCoreFrameSource GlUtil
```

`WallTracker` logs each refit with inlier count, RMS and observed span — the fastest way
to see whether the geometry or the detection is at fault.

`GlUtil.checkError` is called after each GL phase. It logs rather than throws, so a
shader problem shows up as a stream of errors instead of a crash; grep for `GL error`.

Turn on the **Debug** panel in the app for per-frame and per-solve timings, coverage
percentage, and buttons to force a puzzle type — useful when identification is picking
the wrong adapter and you want to isolate whether the reader or the identifier is wrong.
