# Pen and Touch Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tiny throwaway Android test app that shows whether the Boox Note Air 6 C reports both pen side buttons to a third-party app, separately, with and without Boox fast-ink mode on, and how finger and palm touches behave while fast-ink is on.

**Architecture:** A standalone Gradle project in `probe/`, kept apart from the real app, which will later live in its own folder. One activity hosts a full-screen `SurfaceView`. A "Fast-ink" toggle switches between plain Android input (every `MotionEvent` and `KeyEvent` is logged) and Onyx `TouchHelper` raw drawing (Onyx callbacks are logged, along with any Android events that still get through). Every event is written as one line to a log file that can be pulled over adb, and is also shown on screen when fast-ink is paused, because the screen doesn't refresh while it's on.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 29, Onyx `onyxsdk-pen:1.5.9` (from the Boox Maven repo), `org.lsposed.hiddenapibypass:hiddenapibypass:4.3`, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md`. This plan implements section 9, "Known risk: verify first". Its result is a hard stop: it goes back to the owner before the main build is planned.

## Global Constraints

- Throwaway code (spec §9). Keep it small and readable, but it is not the real app. Nothing in `probe/` gets reused without the owner's approval.
- Readable code (spec §5): small files, plain names, comments that explain *why*.
- Kotlin, native Android (spec §5).
- Onyx SDK repo is HTTP only: `http://repo.boox.com/repository/maven-public/`, so it needs `isAllowInsecureProtocol = true`. HTTPS doesn't serve the metadata (checked 2026-11-07).
- On API 30 and up, call `HiddenApiBypass.addHiddenApiExemptions("")` in `Application.onCreate`. Onyx's own demos do this, and the SDK reaches hidden APIs.
- Portrait only (spec §6.2).
- Only the owner approves design changes (spec §5). Report the results; don't act on them.

## Review Focus

1. **Buttons pressed while the pen hovers rather than touches.** Many pens report button state only on hover events (`onGenericMotionEvent`), not on touch. The probe must log hover events, not only touch events (Task 2, manual check M2).
2. **A button held before the tip touches the screen.** Real use is "hold the button, then write". The log must show the button state on the very first `ACTION_DOWN` or `BUTTON_PRESS`, not just changes mid-stroke (Task 2, check M3).
3. **Pen eraser end vs side button.** A pen with an eraser end reports `TOOL_TYPE_ERASER`, and Onyx may route a side button into its "raw erasing" callbacks. The log must record the tool type and which Onyx callback family fired (draw or erase) so the two can be told apart (Task 2 test, check M4).
4. **Fast-ink mode swallowing Android events.** With raw drawing on, `MotionEvent`s may stop reaching the view entirely. The probe logs both sources side by side, so the report can say which one carries button information (Task 3, check M5).
5. **The log not being visible in fast-ink mode.** The screen freezes while raw drawing is on. The owner needs a "Show log" button that pauses fast-ink, plus a log file reachable over adb (Task 3, check M1).

---

### Task 1: Toolchain and empty probe app that installs on the tablet

**Files:**
- Create: `probe/settings.gradle.kts`, `probe/build.gradle.kts`, `probe/gradle.properties`, `probe/app/build.gradle.kts`, `probe/app/src/main/AndroidManifest.xml`, `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeApp.kt`, `probe/app/src/main/java/com/fzizzle/epapernotes/probe/MainActivity.kt`, `probe/.gitignore`
- Create (generated): `probe/gradlew`, `probe/gradlew.bat`, `probe/gradle/wrapper/*`

**Interfaces:**
- Produces: package `com.fzizzle.epapernotes.probe`, application class `ProbeApp`, launcher `MainActivity`, and the build commands `./gradlew :app:testDebugUnitTest` and `./gradlew :app:installDebug`, run from `probe/`.

- [ ] **Step 1: Install the host toolchain (CachyOS / Arch).** None of it is installed right now: no java, gradle, adb or SDK. Install `jdk17-openjdk`, `android-tools` and `gradle` with pacman. Download the Android command-line tools into `~/Android/Sdk/cmdline-tools/latest`, then run `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"` and accept the licences. Export `ANDROID_HOME=~/Android/Sdk`. Package installs need the owner's sudo, so ask before running them.

  Verify: `java -version` shows 17, `adb version` prints, and `sdkmanager --list_installed` lists `platforms;android-35`.

- [ ] **Step 2: Create the Gradle project.**
  - `settings.gradle.kts`: plugin repos are `google()`, `mavenCentral()` and `gradlePluginPortal()`. Dependency repos are `google()`, `mavenCentral()`, `maven("https://jitpack.io")` and the Boox repo (HTTP, insecure allowed). Include `:app`.
  - Root `build.gradle.kts`: `com.android.application` 8.7.3 and `org.jetbrains.kotlin.android` 2.0.21, both `apply false`.
  - `gradle.properties`: `android.useAndroidX=true`.
  - App module: namespace and applicationId `com.fzizzle.epapernotes.probe`, minSdk 29, target and compile 35, Java and JVM target 17. Dependencies are `com.onyx.android.sdk:onyxsdk-pen:1.5.9`, `org.lsposed.hiddenapibypass:hiddenapibypass:4.3`, `androidx.appcompat:appcompat:1.7.0`, and `junit:junit:4.13.2` for tests. Add `packaging { resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/ASL2.0") }` and `jniLibs.pickFirsts += "lib/*/libc++_shared.so"`, because Onyx's demo needed both.
  - Then run `gradle wrapper --gradle-version 8.11.1` inside `probe/`.

- [ ] **Step 3: Add `ProbeApp` and the manifest.** `ProbeApp : Application` calls the hidden-API bypass on API 30 and up. The manifest registers `.ProbeApp`. `MainActivity` is the launcher, with `android:screenOrientation="portrait"`. The app label is "Pen Probe". `MainActivity` shows only a "Pen Probe" text view for now.

- [ ] **Step 4: Build.** Run `./gradlew :app:assembleDebug` in `probe/`. Expected: BUILD SUCCESSFUL. If Onyx dependencies fail to resolve, report the missing artifact and the repo it was looked up in. Don't swap SDK versions silently.

- [ ] **Step 5: Install on the tablet (owner's help needed).** Ask the owner to enable Developer options and USB debugging on the Boox, then connect by USB. Run `adb devices` (expect one device), then `./gradlew :app:installDebug`. Expected: "Pen Probe" launches and shows its label. Also record `adb shell getprop ro.build.version.sdk` and `adb shell getprop ro.product.model`, for the results file.

- [ ] **Step 6: Commit.**

```bash
git add probe
git commit -m "probe: empty Android project that installs on the Note Air 6 C"
```

### Task 2: Event model, describer and log file

**Files:**
- Create: `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeEvent.kt`
- Create: `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeLog.kt`
- Test: `probe/app/src/test/java/com/fzizzle/epapernotes/probe/ProbeEventTest.kt`

**Interfaces:**
- Produces:
  - `enum class EventSource { TOUCH, HOVER, KEY, ONYX_DRAW, ONYX_ERASE }`
  - `data class ProbeEvent(val source: EventSource, val fastInk: Boolean, val toolType: Int, val action: String, val buttonState: Int, val actionButton: Int, val keyCode: Int, val x: Float, val y: Float, val pressure: Float)`. The ints are the raw Android values. Use -1 when a field doesn't apply.
  - `fun buttonNames(buttonState: Int): String`, which decodes the `MotionEvent.BUTTON_*` bits into names joined by `+`, or `"none"`.
  - `fun toolName(toolType: Int): String`, which maps `TOOL_TYPE_*` to `FINGER`, `STYLUS`, `ERASER`, `MOUSE` or `UNKNOWN(n)`, and returns `"-"` for -1.
  - `fun ProbeEvent.describe(): String`, a single line.
  - `class ProbeLog(file: java.io.File)` with `fun add(event: ProbeEvent)`, `fun note(text: String)` and `fun recent(n: Int): List<String>`. It appends one line per event to the file with a time prefix (`HH:mm:ss.SSS`), and keeps the last 200 lines in memory. Write the file synchronously, line by line, so nothing is lost if the app is killed mid-test.

The decoder bit values are Android's constants, copied here so the JVM unit test needs no Android runtime: `BUTTON_PRIMARY=1`, `BUTTON_SECONDARY=2`, `BUTTON_TERTIARY=4`, `BUTTON_BACK=8`, `BUTTON_FORWARD=16`, `BUTTON_STYLUS_PRIMARY=32`, `BUTTON_STYLUS_SECONDARY=64`, and `TOOL_TYPE_FINGER=1`, `STYLUS=2`, `MOUSE=3`, `ERASER=4`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
class ProbeEventTest {
    @Test fun noButtons() = assertEquals("none", buttonNames(0))
    @Test fun stylusPrimary() = assertEquals("STYLUS_PRIMARY", buttonNames(32))
    @Test fun bothStylusButtons() = assertEquals("STYLUS_PRIMARY+STYLUS_SECONDARY", buttonNames(96))
    @Test fun unknownBitsShown() = assertEquals("STYLUS_SECONDARY+0x100", buttonNames(64 or 256))
    @Test fun eraserTool() = assertEquals("ERASER", toolName(4))
    @Test fun notApplicableTool() = assertEquals("-", toolName(-1))
    @Test fun describeHoverWithButton() {
        val e = ProbeEvent(EventSource.HOVER, fastInk = false, toolType = 2, action = "HOVER_MOVE",
            buttonState = 64, actionButton = -1, keyCode = -1, x = 10f, y = 20f, pressure = 0f)
        assertEquals("HOVER fastInk=off tool=STYLUS action=HOVER_MOVE buttons=STYLUS_SECONDARY actionButton=- key=- x=10 y=20 p=0.00", e.describe())
    }
    @Test fun describeOnyxErase() {
        val e = ProbeEvent(EventSource.ONYX_ERASE, fastInk = true, toolType = -1, action = "BEGIN",
            buttonState = -1, actionButton = -1, keyCode = -1, x = 5f, y = 6f, pressure = 0.5f)
        assertEquals("ONYX_ERASE fastInk=on tool=- action=BEGIN buttons=- actionButton=- key=- x=5 y=6 p=0.50", e.describe())
    }
}
```

  `describe()` prints `buttons=-` when `buttonState == -1`, and `actionButton` goes through `buttonNames` unless it is -1. Coordinates are rounded to integers, and pressure has 2 decimals.

- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :app:testDebugUnitTest`. Expected: compilation failure, because `buttonNames` and the rest are undefined.

- [ ] **Step 3: Implement `ProbeEvent.kt` and `ProbeLog.kt`** to the interfaces above.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 8 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeEvent.kt probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeLog.kt probe/app/src/test
git commit -m "probe: event model, button decoder and on-device log"
```

### Task 3: Probe screen with both input paths

**Files:**
- Modify: `probe/app/src/main/java/com/fzizzle/epapernotes/probe/MainActivity.kt`
- Create: `probe/app/src/main/java/com/fzizzle/epapernotes/probe/ProbeSurface.kt`
- Create: `probe/app/src/main/java/com/fzizzle/epapernotes/probe/OnyxInput.kt`
- Create: `probe/app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `ProbeEvent`, `EventSource`, `ProbeLog` (Task 2).
- Produces: nothing used later. This is the end of the probe.

Screen layout, portrait:

- **Top row of buttons:** `Fast-ink: OFF/ON` (toggle), `Show log`, `Clear`, `Mark`. `Mark` writes a `note("---- MARK ----")` line, so the owner can split up test sections.
- **Below that:** a big label for the *current* stylus button state, for example "Buttons: STYLUS_SECONDARY".
- **The rest of the screen:** a `ProbeSurface` (a `SurfaceView`) with a log text overlay that shows `recent(25)`.

Behaviour:

- **`ProbeSurface`** overrides `onTouchEvent`, `onGenericMotionEvent` (hover) and `dispatchKeyEvent` (via the activity), and maps each one to a `ProbeEvent` with source `TOUCH`, `HOVER` or `KEY`. Log every event, and log moves too, without throttling. Button transitions matter most, so don't drop `ACTION_BUTTON_PRESS` or `ACTION_BUTTON_RELEASE`. Return `true` from touch handlers so the events keep coming. With fast-ink off, draw a simple black line under the stylus with a plain Canvas, so the owner gets feedback that writing works.
- **`OnyxInput`** wraps `TouchHelper.create(surface, rawInputCallback)`. `setLimitRect` covers the surface's visible rect, excluding the top button row. Call `openRawDrawing()` and `setRawDrawingEnabled(true)` when the toggle turns on, and `setRawDrawingEnabled(false)` and `closeRawDrawing()` when it turns off and in `onPause`. Each callback logs a `ProbeEvent`. `onBeginRawDrawing`/`onEndRawDrawing` log `ONYX_DRAW` `BEGIN`/`END` with the touch point. `onBeginRawErasing`/`onEndRawErasing` log `ONYX_ERASE` the same way. The point-list callbacks log one summary line (`note("ONYX_DRAW list n=<count>")`) rather than every point. The `TouchPoint` fields used are `x`, `y` and `pressure`.
- **While fast-ink is on**, keep logging any Android events that still arrive at `ProbeSurface`. Seeing them, or not, is a result in itself.
- **`Show log`** turns fast-ink off, if it's on, and refreshes the overlay. The toggle's label must show the real state afterwards.
- **The log file** is `getExternalFilesDir(null)/probe-log.txt`. At start, note the device model, SDK level, and the file path.

- [ ] **Step 1: Implement** the layout, `ProbeSurface`, `OnyxInput` and the wiring in `MainActivity`.

- [ ] **Step 2: Build and install.** Run `./gradlew :app:testDebugUnitTest :app:installDebug`. Expected: tests pass and the app installs.

- [ ] **Step 3: Smoke check on the device (with the owner).** Write with fast-ink off: lines appear and the log shows `TOUCH ... tool=STYLUS`. Toggle fast-ink on and write: ink appears with low latency. Tap `Show log`: the log shows `ONYX_DRAW BEGIN`/`END` lines. Pull the file with `adb pull /sdcard/Android/data/com.fzizzle.epapernotes.probe/files/probe-log.txt` and confirm it matches what's on screen.

- [ ] **Step 4: Commit.**

```bash
git add probe
git commit -m "probe: screen logging Android and Onyx fast-ink input side by side"
```

### Task 4: Run the test session with the owner and write up the results

**Files:**
- Create: `probe/TESTING.md` (the owner's checklist, in plain words)
- Create: `docs/superpowers/probe-results.md`

**Interfaces:**
- Consumes: the installed probe (Task 3).
- Produces: `docs/superpowers/probe-results.md`, which the main build plan will be written from.

- [ ] **Step 1: Write `probe/TESTING.md`**, the checklist the owner follows, one line each. Press `Mark` between items. Do every item once with fast-ink OFF and once with it ON.
  - **M1:** Write a few words. Then `Show log`, and confirm lines appear.
  - **M2:** Hover the pen just above the screen without touching. Press and release button 1, then button 2.
  - **M3:** Hold button 1, *then* touch down and draw a line. Release the button after lifting. Repeat with button 2.
  - **M4:** Press button 1 mid-stroke, then release it mid-stroke. Repeat with button 2. If the pen has an eraser end, write with that too.
  - **M5:** Write normally with the palm resting on the screen. Then scroll with one finger, and pinch with two.
  - **M6:** Note anything Boox did by itself, such as a system menu popping up on a button press or ink appearing from the palm.

- [ ] **Step 2: Run the session.** The owner works through `TESTING.md`. Then pull the log with `adb pull` (same path as in Task 3).

- [ ] **Step 3: Write `docs/superpowers/probe-results.md`.** Include the device model and SDK level. Then, in plain words, answer each of these for **fast-ink off** and **fast-ink on**:
  1. Can the app tell button 1 and button 2 apart? Which event carries them (touch, hover, key, Onyx erase callback), and with which values?
  2. Is a button that's held *before* touch-down visible on the first touch?
  3. Do Android events still arrive while fast-ink is on?
  4. Does the palm cause ink or events? Do finger touches arrive while fast-ink is on?
  5. What does this mean for the design (spec §6.3 pen buttons, §6.2 touch)? Can it go ahead as written, or is there a design change the owner needs to decide on?

  Quote the relevant log lines under each answer.

- [ ] **Step 4: Commit and stop.**

```bash
git add probe/TESTING.md docs/superpowers/probe-results.md
git commit -m "probe: owner test checklist and results"
```

Hand the results to the owner. Stop here. The next plan (PDF library choice, then the main app) is written only after the owner has seen these results and approved any design changes they imply.
