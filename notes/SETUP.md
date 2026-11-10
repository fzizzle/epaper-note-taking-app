# Setting up another computer to build and install the app

What the build needs, how it was installed on the first computer, and the one thing that can wipe the app's data on the tablet if skipped (step 4).

## What is needed

| | Version | Why |
|---|---|---|
| Java (JDK) | 17 | The build is set to Java 17. |
| Android SDK: `platforms;android-35` | 35 | The app is compiled against Android 15's SDK. |
| Android SDK: `build-tools;35.0.0` | 35.0.0 | Packs the app. |
| Android SDK: `platform-tools` | any recent | Gives `adb`, which talks to the tablet. |
| Gradle | 8.11.1 | Not installed by hand: `notes/gradlew` downloads it the first time. |
| Internet on first build | | Gradle fetches MuPDF from `maven.ghostscript.com` and the Onyx pen SDK from `repo.boox.com` (plain HTTP; the build file allows it). |

No root or admin rights are needed. Everything can live in the home folder.

## 1. Java 17

Either install it from the system's package manager (for example `sudo pacman -S jdk17-openjdk`, `sudo apt install openjdk-17-jdk`, or `brew install openjdk@17`), or, without admin rights, download a JDK 17 archive from <https://adoptium.net/temurin/releases/?version=17>, unpack it under `~/.local/opt/`, and point `JAVA_HOME` at it (step 3).

Check: `java -version` says 17.

## 2. Android SDK

The simple way is to install Android Studio and let it install the SDK. Without it:

1. Download "Command line tools only" from <https://developer.android.com/studio#command-line-tools-only>.
2. Unpack it so the tools end up in `~/Android/Sdk/cmdline-tools/latest/` (the archive holds a folder called `cmdline-tools`; its contents go into `latest`).
3. Install the three packages and accept the licences:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```

## 3. A file that loads the tools

On the first computer this is `~/.local/opt/android-env.sh`, and the plans and checklists in this repository all start with `source ~/.local/opt/android-env.sh`. Make the same file, with the paths that are true on this computer:

```sh
# ~/.local/opt/android-env.sh
export JAVA_HOME="$HOME/.local/opt/jdk-17.0.21+9"   # wherever JDK 17 is; leave out if the system's java is 17
                                                     # on a Mac, add /Contents/Home to the unpacked folder
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Gradle finds the SDK through `ANDROID_HOME`. (Android Studio writes `notes/local.properties` instead; that file is not kept in git.)

Check:

```sh
source ~/.local/opt/android-env.sh
cd notes
./gradlew :app:testDebugUnitTest     # first run downloads Gradle and the libraries; a few minutes
./gradlew :app:assembleDebug
```

Both should end in `BUILD SUCCESSFUL`.

## 4. Before installing on the tablet: the signing key

**Read this before the first `installDebug` from a new computer.**

A debug build is signed with a key that each computer makes for itself, in `~/.android/debug.keystore`. The tablet refuses to install a build over one signed with a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The only way past that refusal is to uninstall the app first, and uninstalling **deletes the app's own data on the tablet**: remembered pages, the library folder setting, the All files access grant, and any journal holding writing that has not yet been saved into its PDF.

So do one of these:

- **Best: copy the key from the first computer.** Copy `~/.android/debug.keystore` from it to the same place on this one (over a USB stick or `scp`; never into the repository). Builds from both computers then install over each other.
- **Or accept a clean install.** First open the app on the tablet and leave any open document with ← so everything is saved, and check no message about unsaved writing appears. Then `adb uninstall com.fzizzle.epapernotes`, install, open the app, grant file access again and choose the library folder again. The PDFs themselves are never touched by an uninstall.

## 5. The tablet

1. On the tablet: Settings → About → tap the build number seven times, then turn on USB debugging in Developer options.
2. Connect by USB and accept the "Allow USB debugging?" question on the tablet. `adb devices` should list one device.
3. Install and run:

```sh
source ~/.local/opt/android-env.sh
cd notes
./gradlew :app:installDebug                       # the app
./gradlew :app:installDebugAndroidTest            # its tablet tests
adb shell am instrument -w com.fzizzle.epapernotes.test/androidx.test.runner.AndroidJUnitRunner
```

Things that have gone wrong before:

- **Don't use `./gradlew :app:connectedDebugAndroidTest`.** It uninstalls the app when it finishes, which wipes its data as in step 4. Use the three commands above.
- **Check the tablet is idle before installing.** Installing closes the app, even in the middle of writing. `adb shell dumpsys activity activities | grep topResumedActivity` shows what is on screen, and `adb logcat -d -s Notes | tail` shows when the app last did anything.
- **If file access is lost** after a reinstall: `adb shell appops set --uid com.fzizzle.epapernotes MANAGE_EXTERNAL_STORAGE allow`.
- **Opening a file from the computer** (debug builds): the whole command goes in double quotes, or a path with spaces is cut short.
  `adb shell "am start -n com.fzizzle.epapernotes/.ui.LibraryActivity --es path '/sdcard/Documents/E-paper Notes test/PWT test copy.pdf'"`
- **The app's log:** `adb logcat -s Notes`. The speed check's: `adb logcat -s SpeedCheck`.
- **The tablet sleeps quickly** and then ignores commands: `adb shell input keyevent WAKEUP` first.
- **Onyx's "side button erases" setting must be off** on the tablet for pen button 1 to reach the app as lasso.

## 6. Carrying on with the speed work

The branch is `review-speed`. What is done and what is left is in [docs/superpowers/review-speed-results.md](../docs/superpowers/review-speed-results.md), section "Still to do".
