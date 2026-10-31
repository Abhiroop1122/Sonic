# Getting Sonic onto F-Droid

Sonic isn't going into F-Droid's own catalogue: an F-Droid build can't carry the Array font, so it
would look different. It ships through its own F-Droid repository and Obtainium instead (see
RELEASE.md, "F-Droid"). What follows is the groundwork, kept in case Sonic ever uses a free font.

Guides: F-Droid's [Submitting to F-Droid Quick Start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
and [Publishing an app on F-Droid](https://randombits.dev/articles/android/fdroid).

## Ready

- **Free software only.** MIT, with Apache 2.0 (AndroidX, Media3, Filament, OkHttp, Backdrop), MIT
  (soulseek-rs) and OFL (Inter, Silkscreen) dependencies. No Google Play services or Firebase.
  The Array font isn't in the repository; a source build uses Silkscreen instead.
- **Store listing** in `fastlane/metadata/android/en-US/`: `title.txt`, `short_description.txt`
  (under 80 characters, no full stop), `full_description.txt`, `images/icon.png` (512 × 512),
  `images/featureGraphic.png` (1024 × 500), `images/phoneScreenshots/`, and
  `changelogs/<versionCode>.txt` for each release (500 characters at most).
- **Tags.** Every release commit is tagged `v<version>` (Semantic Versioning). Development builds
  are tagged `v<version>-dev.<n>`; the recipe's `UpdateCheckMode` only follows plain release tags.
- **Version codes** come from the version name (see RELEASE.md), so they always increase.
- **No self-updates in F-Droid builds.** The recipe passes `sonic.updates=false`, which turns off
  the in-app GitHub check; About says updates come through F-Droid.
- **Reproducible-build friendly:** `dependenciesInfo` is off, so no dependency blob is signed in.
- **A draft recipe**, [`fdroid/app.sonic.yml`](../fdroid/app.sonic.yml): JDK 21, NDK r27d, Rust 1.91.0
  through the rustup srclib for the Soulseek library, then Gradle.

## Before the merge request

1. **Build it the F-Droid way.** Run `fdroid build -v -l app.sonic` with fdroidserver against a
   local fdroiddata checkout holding the recipe, on Linux. Fix whatever the native build or the
   scanner reports (for example, `scripts/build-native.sh` needs Bash 4 and `CARGO` pointing at
   rustup's cargo if it isn't on the path).
2. **Signing.** Today's APKs are signed with the maintainer's debug key. For reproducible builds,
   make a proper release key first, publish its certificate SHA-256, then add
   `Binaries: https://github.com/Abhiroop1122/Sonic/releases/download/v%v/Sonic-%v.apk` and
   `AllowedAPKSigningKeys: <sha256>` to the recipe. Without that, F-Droid signs with its own key,
   and the F-Droid app and the GitHub app can't update each other.
3. **Anti-features.** The recipe declares `NonFreeNet` because Dig and Friends use the Soulseek
   network. Check with the reviewers whether anything else applies.
4. **Open the merge request.** Fork https://gitlab.com/fdroid/fdroiddata, add
   `metadata/app.sonic.yml`, run `fdroid lint app.sonic` and `fdroid rewritemeta app.sonic`, push,
   and open the merge request with the "App inclusion" template.

## On every release after that

Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, tag the commit `v<version>`,
and F-Droid's checker picks it up on its own.
