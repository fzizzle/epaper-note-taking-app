# Play Store findings (2026-11-16)

What it would take to publish Jot on the Google Play Store, looked into on 2026-11-16. **Nothing here is planned or started.** It is a record to come back to. The rules were read from Google's pages and a few guides that day and change every year: check them again before acting.

## What was checked in the build

From the release APK of acf77cb (`notes/app/build/outputs/apk/release/app-release.apk`, 31.9 MB) and the repository.

| What | Found | Fine for Play? |
|---|---|---|
| Target Android version | `targetSdk = 35`, `compileSdk = 35`, AGP 8.7.3 | **No.** New apps must target Android 16 (API 36) since 31 August 2026 |
| Signing | The release build is signed with this computer's debug key | **No.** Play refuses debug-signed uploads |
| Upload format | Only APKs are built | **No.** Play takes an app bundle (`./gradlew :app:bundleRelease`) |
| Native libraries and 16 KB memory pages | All five are aligned to 16 KB or more: `libmupdf_java.so`, `libc++_shared.so`, `libneopen_jni.so`, `libonyx_pen_touch_reader.so` at 0x4000, `libmmkv.so` at 0x10000 (`readelf -lW`) | Yes |
| Processor types | `arm64-v8a` only | Yes: Play then offers it to 64-bit ARM devices only |
| Permissions the app asks for | `MANAGE_EXTERNAL_STORAGE` | Needs Google's approval (below) |
| Permissions the Onyx SDK adds to the built app | `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `BLUETOOTH`, `DUMP`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `SYSTEM_ALERT_WINDOW`, `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`; and with them the features `android.hardware.bluetooth` and `android.hardware.wifi` (`aapt2 dump permissions`, `dump badging`) | **Remove them** (`tools:node="remove"` in the manifest), then try the pen: "draw over other apps" and `DUMP` invite questions in review, and the app uses none of them |
| Internet | No `INTERNET` permission | Yes: the data form can truthfully say nothing is collected or sent |
| Source | The GitHub repository is private; licence AGPL-3.0 | **No**, once distributed (below) |
| Version | `versionCode = 1`, `versionName = "0.1"` | The code must go up with every upload |

## What must change in the app first

1. **Target API 36.** Raise `compileSdk` and `targetSdk`; AGP 8.7.3 predates API 36, so the Android Gradle plugin most likely has to go up too. Then check on the tablet: an app targeting 36 can no longer opt out of drawing under the system bars, the Back gesture changes (the app overrides `onBackPressed` in both screens), and large screens ignore orientation locks.
2. **A signing key of its own.** An upload key in a keystore kept outside the repository, and Play App Signing. The Play build then can't install over the build on the tablet now (different key): that one has to be uninstalled first, which removes the app's settings, journals, remembered pages and the All files access grant. The PDFs are untouched. Save everything and leave each document before doing it.
3. **The ten extra permissions removed** (table above).
4. **Devices that aren't Boox.** Play would offer the app to every 64-bit Android 11+ tablet and phone.
   - `NotesApp.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` with nothing around it. If that fails on some device or Android version, the app dies at start. Not seen to fail; not tested anywhere but the Boox.
   - `PenInput.on` assumes Onyx's fast-ink. From reading the SDK (its classes taken apart, not run): on other devices no line follows the pen, strokes come out hair-thin (pressure is read on Onyx's 0 to 4095 scale), and the SDK swallows finger moves, so pinch and pan are dead.
   - Either limit the app to Onyx devices in Play Console's device catalogue (not checked: whether Boox tablets, which often aren't Play-certified, appear there at all), or make the app say plainly on other devices that it is made for Boox tablets, or give it a plain-Android pen path. The last is a feature of its own.
5. **`settings.gradle.kts` fetches the Onyx SDK over plain HTTP.** Not a Play rule, but a release build should check what it downloads. The [improvements plan](plans/2026-11-16-improvements.md), Task 3, does this.

## Two risks that could stop it

- **All files access.** Google approves `MANAGE_EXTERNAL_STORAGE` app by app, through a Permissions Declaration Form. "Document management apps" are among the permitted uses, but the form asks why Android's folder picker (the Storage Access Framework) isn't enough. Jot's case: it saves by writing a temporary file beside the original and renaming it over, watches folders for changes, and walks the owner's whole folder tree; the folder picker gives none of the three cleanly. If Google refuses, the only way on is to rebuild file access on the folder picker, which is a large change to `SafeSave`, `FileOps`, `FolderWatch` and every path-keyed store.
- **Licences.** MuPDF is AGPL-3.0, so anyone given the app must be able to get its source: the repository has to be public (or the source offered some other way) from the first release. Harder: the app also ships Onyx's closed-source pen SDK inside the same APK, and the AGPL generally doesn't allow distributing a combined work with a part whose source can't be given. The owner can allow that for their own code but not for MuPDF's, which is Artifex's. This isn't legal advice: ask Artifex (they sell commercial licences and are known to enforce the AGPL) before publishing anywhere, GitHub included. Also read the terms Onyx's SDK comes under for redistribution. None of this matters for private use on one's own tablet.

## The steps in Play Console

1. A developer account: 25 USD once, with identity verification.
2. Create the app. Listing: name (30 characters; "Jot" alone is a common name), short description (80), full description (4,000), a 512 px icon, a 1024 × 500 banner, at least two screenshots; tablet screenshots as well.
3. The forms: a privacy policy link (required even when nothing is collected), data safety, content rating, target audience, ads (none), category (Productivity), and the All files access declaration.
4. Upload the app bundle to a closed test.
5. On a personal account opened after November 2023: 12 testers must stay opted in to the closed test for 14 days running before production access can be asked for. This is from third-party guides, not confirmed on Google's own page; one of them says Google also began checking in 2026 that testers really use the app. An organisation account is exempt but needs a registered business.
6. Ask for production access, then review. About three to four weeks from first upload.

## A cheaper route

For an app that only works on Boox tablets, an APK on GitHub Releases reaches the same people: no tester rule, no permission review, no device catalogue. It still needs the signing key (item 2) and the licence question answered (both risks' second point). F-Droid's main repository won't take an app with a closed-source library in it.

## Sources (read 2026-11-16)

- [Target API level requirements, Android Developers](https://developer.android.com/google/play/requirements/target-sdk)
- [Target API level requirements, Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Use of All files access permission, Play Console Help](https://support.google.com/googleplay/android-developer/answer/10467955)
- [Manage all files on a storage device, Android Developers](https://developer.android.com/training/data-storage/manage-all-files)
- [Google Play's 12-tester rule, Choicely](https://www.choicely.com/blog/google-play-12-tester-rule) and [ProAndroidDev](https://proandroiddev.com/how-to-get-12-testers-for-google-play-closed-testing-and-actually-pass-6a2ab52a0779) (third-party)
