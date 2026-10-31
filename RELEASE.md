# Releasing Sonic

Releases are published on this repository's [releases page](https://github.com/Abhiroop1122/Sonic/releases).

| Kind | Version | Tag | GitHub | APK |
| :--- | :--- | :--- | :--- | :--- |
| **Release** | `1.1.0` | `v1.1.0` | release, marked Latest | `Sonic-1.1.0.apk` |
| **Development build** | `1.2.0-dev.1` | `v1.2.0-dev.1` | pre-release | `Sonic-1.2.0-dev.1.apk` |

Versions follow [Semantic Versioning](https://semver.org): a fix bumps the patch (1.1.1), a
feature the minor (1.2.0), a change that breaks what people rely on the major. A development build
carries the version it leads up to, numbered from 1 (`1.2.0-dev.1`, `1.2.0-dev.2`, then `1.2.0`).

Both kinds are the release variant, `app.sonic`, label **Sonic.**, so they update each other in
place. The debug variant (`app.sonic.dev`, **Sonic. Dev**) is for development on your own phone and
is never published.

The version is set in one place, `sonicVersion` in `app/build.gradle.kts`. The version code follows
from it (major × 1,000,000 + minor × 10,000 + patch × 100 + the dev number, or 99 for a release), so
a development build always sorts after the release before it and before its own release:
1.0.0 was 15, 1.1.0 is 1010099, 1.2.0-dev.1 is 1020001, 1.2.0 is 1020099.

Every APK supports Android 9 or newer on arm64-v8a, armeabi-v7a, x86_64 and x86.

## Update channels

The app checks GitHub itself (Settings › About, and once a day when Settings › Flags › check for
updates is on). The first time someone checks, they choose:

- **stable**: GitHub's latest release (`/releases/latest`). Pre-releases are never offered.
- **development**: the newest of all releases, pre-releases included (`/releases`).

So the tag and the pre-release flag are what the app reads. A release must be a full release with
the Latest label; a development build must be a pre-release that does not take Latest. A newer
release opens its page in the browser; the app never downloads or installs anything itself.

The release variant is signed with the debug key of the maintainer's machine, so only the
maintainer builds releases. That keeps the signing certificate the same from one release to the
next, which Android requires for an update to install over the previous version.

## 1. Prepare

1. Start from a reviewed, clean source revision.
2. Set `sonicVersion` in `app/build.gradle.kts` (the version code follows).
3. Update `CHANGELOG.md` and `release/RELEASE_NOTES.md`. The release notes become the GitHub
   release body.

## 2. Build and test

Follow [BUILDING](docs/BUILDING.md) with JDK 21, SDK 36, Rust 1.91.0 and NDK r27d. Build the native
library from this revision first, then run the Gradle checks with the native task skipped:

```bash
bash scripts/build-native.sh
cargo +1.91.0 test --manifest-path native/Cargo.toml --workspace --lib --locked
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease -x buildSoulseekNative
./gradlew :app:connectedDebugAndroidTest -x buildSoulseekNative   # on an emulator or device
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Install it on a device or emulator and
go through the screens touched by this release.

## 3. Check the artifact

```bash
APK=app/build/outputs/apk/release/app-release.apk
BT="$ANDROID_HOME/build-tools/36.1.0"     # any current build-tools version

"$BT/aapt2" dump badging "$APK" | head -1  # package app.sonic, versionCode, versionName
unzip -l "$APK" | grep librunout_soulseek  # one line each: arm64-v8a armeabi-v7a x86_64 x86
unzip -l "$APK" | grep assets/licenses/    # native dependency notices are bundled
"$BT/zipalign" -c -P 16 -v 4 "$APK"
"$BT/apksigner" verify --print-certs "$APK"
```

Check that every native library has 16 KiB-aligned ELF load segments (the last column of each
`LOAD` line must be `0x4000` or larger):

```bash
rm -rf /tmp/sonic-libs && unzip -q "$APK" 'lib/*' -d /tmp/sonic-libs
READELF=$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)
for so in /tmp/sonic-libs/lib/*/*.so; do echo "$so"; "$READELF" -lW "$so" | grep LOAD; done
```

Compare the certificate SHA-256 from `apksigner` with the previous release's verification report.
If it differs, the build will not update existing installs: stop and find out why.

## 4. Checksum and verification report

Copy the checked APK into `release/` and write its checksum from inside that directory, so the
checksum file names the APK without a path:

```bash
cp app/build/outputs/apk/release/app-release.apk release/Sonic-<version>.apk
cd release
shasum -a 256 Sonic-<version>.apk > Sonic-<version>.apk.sha256   # or sha256sum on Linux
shasum -a 256 -c Sonic-<version>.apk.sha256
```

Write `release/Sonic-<version>-VERIFICATION.md`. Record:

- date, host and source commit;
- tool versions, and test and lint counts, including anything skipped and why;
- whether the native library was built from this revision;
- application ID, version name and code, min and target SDK, the four ABIs, 16 KiB alignment,
  `zipalign` and `apksigner` results, and the certificate SHA-256;
- the APK's SHA-256 and size;
- what was checked by hand and on which device, and what was not (physical phones, public
  Soulseek accounts, Bluetooth or USB DACs, TalkBack).

Commit the version bump, changelog, release notes, checksum and report. The APK itself is never
committed; it is a release asset. Checksums and reports from earlier versions stay in `release/`
as history.

## 5. Publish

Push the commit to `Abhiroop1122/Sonic`, then create the tag and release on that exact commit:

```bash
gh release create v<version> \
  release/Sonic-<version>.apk \
  release/Sonic-<version>.apk.sha256 \
  release/Sonic-<version>-VERIFICATION.md \
  --repo Abhiroop1122/Sonic --target <full-commit-sha> \
  --title 'Sonic. <version>' --latest \
  --notes-file release/RELEASE_NOTES.md
```

Download the published APK and check it against the published checksum:

```bash
gh release download v<version> --repo Abhiroop1122/Sonic --pattern 'Sonic-*' --dir /tmp/sonic-check
(cd /tmp/sonic-check && shasum -a 256 -c Sonic-<version>.apk.sha256)
```

Creating a release is separate from opening a pull request.

## 6. Publish to the F-Droid repository

[Somchandra17/sonic-fdroid](https://github.com/Somchandra17/sonic-fdroid) serves the release APKs
to F-Droid, Droid-ify and Neo Store. It picks a new release up within six hours; to publish at once,
run its workflow:

```bash
gh workflow run update.yml --repo Somchandra17/sonic-fdroid
```

Obtainium needs nothing: it installs straight from the GitHub release.

## Development builds

A development build goes through the same steps with `sonicVersion = "<next>-dev.<n>"`: the release
variant, the same checks, `Sonic-<next>-dev.<n>.apk` with its checksum and report. Publish it as a
pre-release that does not take the Latest label, so only the development channel sees it:

```bash
gh release create v<next>-dev.<n> \
  release/Sonic-<next>-dev.<n>.apk \
  release/Sonic-<next>-dev.<n>.apk.sha256 \
  release/Sonic-<next>-dev.<n>-VERIFICATION.md \
  --repo Abhiroop1122/Sonic --target <full-commit-sha> \
  --prerelease --latest=false --title 'Sonic. <next> development <n>' \
  --notes-file release/RELEASE_NOTES.md
```

## F-Droid

Sonic is not in F-Droid's own catalogue, on purpose. F-Droid builds every app from public source,
and Array, the font the interface is set in, may be embedded in the app but not published, so an
F-Droid build would fall back to Silkscreen. Instead:

- **Its own F-Droid repository,** `https://somchandra17.github.io/sonic-fdroid/repo` (fingerprint
  `C482BF3B0848C66DD63E0194711A3DC800487D908D6008A66A124B7E16A91E6B`), serving the exact release
  APKs, so installs from there and from GitHub update each other (step 6).
- **Obtainium,** straight from the GitHub releases.

The store text and pictures in `fastlane/metadata/android/en-US/` feed the repository's listing:
add `changelogs/<versionCode>.txt` (at most 500 characters) for every release. The groundwork for
the catalogue (`-Psonic.updates=false`, the draft recipe in [`fdroid/app.sonic.yml`](fdroid/app.sonic.yml),
[docs/FDROID.md](docs/FDROID.md)) stays in case Sonic ever ships a free font.

## Building on a new machine

Everything is in git except three files, kept out of it on purpose. A copy of them is in the
encrypted `sonic-keys-<date>.tar.enc` backup (its `RESTORE.txt` says where each goes):

- **`~/.android/debug.keystore`, the key every release is signed with** (certificate SHA-256
  `1d001a76582b5450dc6795285455321aacaced319cf08fc2040471883b5dd8b8`). Without it a build can't
  update an installed Sonic; a debug key made on another machine is a different key.
- **`app/src/main/assets/fonts/licensed/Array-*.ttf`.** Without them the build uses Silkscreen.
- **`~/.config/sonic-fdroid/`**, the key that signs the F-Droid repository's index.

Then, on macOS or Linux:

1. Install JDK 21, the Android SDK with NDK `27.3.13750724`, and Rust `1.91.0` through rustup with
   the four Android targets (`aarch64-linux-android`, `armv7-linux-androideabi`,
   `x86_64-linux-android`, `i686-linux-android`).
2. Clone `Abhiroop1122/Sonic`, put the three files back, and build:
   `./gradlew :app:testDebugUnitTest :app:assembleRelease`. The Gradle build compiles the Soulseek
   library for all four ABIs itself.
3. Check the APK's signing certificate (section 3) before publishing anything: it must be the
   SHA-256 above.

## Older installs and what is still missing

Sonic's application IDs are new, so Sonic does not update an older Runout install (`app.runout`
or `app.runout.dev`). The two apps sit side by side with separate private data. Music in the
user's download folder is not affected.

The APK is signed with a debug key, not a production key, and the Soulseek client still reports
the experimental version 177/1. A Play Store release would need production signing, an allocated
Soulseek client identifier and interoperability checks with real accounts on representative
networks.
