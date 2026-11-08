# Sonic 1.5.0 verification

Verified on 2026-11-08 (Asia/Kolkata), macOS, branch `v1.5.0` from `main` (6a7f26c).

## Build and tests

- JDK from Android Studio, Android SDK 36, Gradle wrapper.
- `:app:testDebugUnitTest -x buildSoulseekNative`: 412 JVM and Robolectric tests, 411 passed,
  1 skipped (public relay check), no failures or errors.
- `:app:lintDebug`: 0 errors, 92 warnings, 2 hints. The NewApi errors in `sound/BluetoothCodecs.kt`
  (there since 1.2.0) were fixed: hidden-before-Android-13 calls now run inside `runCatching`, and
  the extended codec name is read from Android 15, where it exists.
- `:app:assembleRelease -x buildSoulseekNative`: BUILD SUCCESSFUL.
- Instrumentation tests (`connectedDebugAndroidTest`) were not run for this build.

## Native library

`librunout_soulseek.so` for all four ABIs was not rebuilt: nothing under `native/` changed since
1.4.2, and the libraries are the ones built from those sources with Rust 1.91.0 and NDK r27d.

## Artifact checks

- Application ID `app.sonic`, version 1.5.0, version code 1050099, minimum SDK 28, target SDK 36.
- ARM64, ARMv7, x86 and x86-64 libraries present (16 libraries), every ELF LOAD segment aligned to
  16384 bytes. Native dependency notices bundled under `assets/licenses/`.
- SDK `zipalign -c -P 16 4` and `apksigner verify` passed.
- Signing certificate SHA-256: `1d001a76582b5450dc6795285455321aacaced319cf08fc2040471883b5dd8b8`,
  the same as every release since 1.0.0, so it updates them in place.

Artifact SHA-256: `a3ce407e77a49658dc0fb7791a797e7a285f72f31ec83a806040f559709d3d7f` (67616459 bytes).

## Device checks

On the owner's Pixel 6 (Android 17 / API 37, over wireless debugging):

- The debug build (`app.sonic.dev`) installed fresh and was hand-tested by the owner.
- The release APK installed over Sonic 1.4.2 as an update, keeping the library (52 records).
  What's new 1.5.0 appeared after the launch intro, and after "got it" the tour started on the deck
  ("1 / 9 · your record player").

## Limits

- MP3 playback, the full tour walk and the Settings storage demo were checked in Robolectric tests
  (screenshots 44 to 46), and on the phone only as far as above.
- No Bluetooth or USB DAC, TalkBack or public Soulseek account checks for this build.
- The APK is signed with the development key, not a production key.
