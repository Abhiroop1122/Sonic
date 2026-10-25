<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/sonic-sleeve-dark.jpg" />
    <img src="docs/images/sonic-sleeve.jpg" width="220" alt="The Sonic sleeve: a big orange circle with dotted grooves and SONIC in dot-matrix letters, on a worn paper card" />
  </picture>
</p>

<h1 align="center">Sonic.</h1>

<p align="center">
  <b>Your FLAC albums, kept like records.</b><br />
  A crate of sleeves and a turntable to play them on, for Android.
</p>

<p align="center">
  <a href="https://github.com/Abhiroop1122/Sonic/releases/latest"><img src="https://img.shields.io/github/v/release/Abhiroop1122/Sonic?label=release&color=F05A24" alt="Latest release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Abhiroop1122/Sonic?label=licence&color=111111" alt="MIT licence" /></a>
  <img src="https://img.shields.io/badge/Android-9%2B-111111?logo=android&logoColor=white" alt="Android 9 or newer" />
  <img src="https://img.shields.io/badge/FLAC-only-111111" alt="FLAC only" />
</p>

<p align="center">
  <a href="https://github.com/Abhiroop1122/Sonic/releases/latest"><b>Download Sonic 1.1.0</b></a>
  &nbsp;·&nbsp; <a href="#what-it-does">What it does</a>
  &nbsp;·&nbsp; <a href="#build-it">Build it</a>
  &nbsp;·&nbsp; <a href="CHANGELOG.md">Changelog</a>
</p>

## Our Vision.

*Music is one search away now. Type a name and the song plays, then the next one, then the next. It’s effortless, which is exactly why it never feels like a collection. When everything is instant, you lose the weight of an album-the effort it took to track it down, the choice to commit to the highest audio quality, and the feeling of actually owning the music.*

*It used to take intention. Records piled up next to the turntable, and you flipped through them until one fit the evening. You slid the disc out of its sleeve, set the needle down, and got up to turn it over when Side A ended. You knew your records because you had handled every single one.*

*Sonic brings that ritual back to your phone. If you want any song in two seconds, a streaming app will serve you better. Sonic is built for downloading, keeping, and truly listening to FLAC albums only.*

*Each album in Sonic lives as a record in your crate, with its sides and resolution printed right on the sleeve. Pull it out, drop it on the platter, play Side A, and flip it for Side B. Under the hood, it delivers the exact pipeline an audiophile actually needs with nothing bloated on top: lossless decoding, hi-res playback at native sample rates, a Pure Mode that leaves samples untouched, and a transparent signal path that exposes when your OS resamples or Bluetooth (LDAC/AAC) compresses versus wired USB-C DAC playback.*

*To grow your crate, Sonic searches Soulseek for full releases, delivers ten curated Creative Commons albums from the Internet Archive every Friday, and lets friends hand you a record as an interactive card in chat.*

<p align="center">
  <img src="docs/images/screens/room-light.png" width="260" alt="The room in light: a white and teal turntable playing Paper Moons above a crate of sleeves, the track list below" />
  &nbsp;
  <img src="docs/images/screens/room-mono.png" width="260" alt="The same room with the mono white turntable" />
  &nbsp;
  <img src="docs/images/screens/room-dark.png" width="260" alt="The room in dark: a black turntable on a true-black screen, the same crate and sleeves" />
</p>

<p align="center">
  <img src="docs/images/screens/intro-light.gif" width="240" alt="The intro in light: the cream SONIC sleeve slides in, its record flies onto the turntable and SONIC. types out" />
  &nbsp;&nbsp;
  <img src="docs/images/screens/intro-dark.gif" width="240" alt="The intro in dark: the black SONIC sleeve, the dark turntable and SONIC. on true black" />
</p>

## What it does

### The room

The home screen is a listening room. The crate sits under the deck, and the deck is the player.

- **Pull and drop.** Tap a sleeve and its record slides out. Drag it onto the platter; it lands
  with a thud you can feel.
- **Play.** START/STOP lowers and lifts the needle. Skip within a side, drag the tonearm to drop
  the needle anywhere, hold the platter to wind it down.
- **Swap.** Hold a new record over the one that's playing. The deck shakes, the old record lifts
  off, the new one lands and the music carries on.
- **Eject** flies the record back into its own sleeve.
- **Look closer.** Two fingers down lay the deck flat, two fingers up stand it back. Long-press a
  sleeve for its details: sides, running times, the whole record's length, and a picture you can
  share.

Turn the phone sideways and the room fills the screen. Sideways is just for listening; the other
pages wait until you turn it back.

<p align="center">
  <img src="docs/images/screens/land-light.png" width="49%" alt="Sideways in light: the white and teal deck on the left, the crate and track list on the right" />
  <img src="docs/images/screens/land-dark.png" width="49%" alt="Sideways in dark: the dark deck on the left, the crate on the right, all on black" />
</p>

### Collect

- **Dig** searches Soulseek for whole album folders, FLAC only, and ranks them by how well they
  match. Open a folder to see its track list before you take anything. If you already own part of
  an album, Dig fetches only what's missing.
- **Friday Drops** brings ten Creative Commons albums from the Internet Archive every week, the
  same ten for everyone. Listen to thirty seconds of any track and claim three a week.
- **Friends** is chat with other Soulseek listeners. Send a record as a card; a tap on it opens
  the sender's folder in Dig.
- **Downloads** mend themselves where they can. A song that failed is asked for again, or fetched
  from someone else who has the identical file. A record missing a track wears a red tab until
  it's whole.

<p align="center">
  <img src="docs/images/screens/drops-light.png" width="260" alt="Friday Drops: this week's Creative Commons albums on white tiles, with three claim lights" />
  &nbsp;
  <img src="docs/images/screens/details-light.png" width="260" alt="Record details for Paper Moons: sides A and B with each track's time and the total, 21:20" />
  &nbsp;
  <img src="docs/images/screens/dig-light.png" width="260" alt="Dig: a search box, on this device or Soulseek, and a dotted question mark while it's empty" />
</p>

### Make it yours

- **Light or dark.** Dark is true black, kind to an OLED screen. The launcher icon follows: a
  cream sleeve in light, a black one in dark.
- **Four decks.** The RG-1 turntable comes in white and teal, mono white, dark, and neon: OLED
  black drawn by glowing white edges, in an unlit room. By default it matches the theme: white and
  teal in light, neon in dark.
- **Vinyl only.** Sideways, everything but the deck goes black; a tap brings the room back.
- **Flags** for the small habits: turn the record over by itself, start playing as soon as it
  lands, a sleep timer that fades the music and lifts the needle, and the intro every time the
  app opens.

<p align="center">
  <img src="docs/images/screens/flags-light.png" width="260" alt="Flags in light: switches for auto-flip, auto-play, sleep mode and intro on launch, then theme and deck pickers" />
  &nbsp;
  <img src="docs/images/screens/flags-dark.png" width="260" alt="The same Flags page in dark, with dark picked" />
  &nbsp;
  <img src="docs/images/screens/settings-dark.png" width="260" alt="Settings in dark: sound, flags, library, soulseek and about, and a hint to swipe up with two fingers" />
</p>

### Your shelf

A collection takes space, so Sonic tells you how much. On Settings, swipe up with two fingers.
Storage shows the phone, your music folder and your download folder, split into hi-res FLAC,
CD-quality FLAC, pictures and unfinished downloads, with your records listed largest first.

You can delete a record you no longer want, folder and files. The warning flips to the other
theme's colours so it can't be missed.

<p align="center">
  <img src="docs/images/screens/storage-dark.png" width="260" alt="Storage: the phone's space, the music folder at 10 MB in 38 files, a bar split by kind, and records largest first with delete keys" />
  &nbsp;
  <img src="docs/images/screens/delete-dark.png" width="260" alt="A white warning sheet over the dark page: delete record, Blue Static, your album will be deleted, keep it or delete" />
</p>

### Sound

- **Pure** turns off everything that changes the sound (EQ, ReplayGain, limiter, crackle), so the
  decoded samples pass through untouched. Bit-perfect to a USB DAC on phones that allow it.
- **Hi-res output** plays each file at its own sample rate in 32-bit float instead of cutting it
  to 16-bit. The two work together.
- **The signal path:** long-press the deck's LED to see the whole chain, track to Sonic to
  output, with the bit depth and rate at each step, and an honest word when Android resamples.
  Allow it, and it names your headphones' Bluetooth codec too (LDAC, aptX, AAC…).
- A 10-band EQ with presets, AutoEq headphone profiles, ReplayGain, a limiter and, if you want
  it, a little vinyl crackle.

### FLAC only

FLAC is the rule everywhere: scanning, downloads, sharing and playback. An MP3 renamed to `.flac`
doesn't get in. Your music folder is only read, never changed, and downloads land in a folder you
choose, where they stay if you uninstall Sonic.

## Get it

Download `Sonic-1.1.0.apk` from the [latest release](https://github.com/Abhiroop1122/Sonic/releases/latest)
and check it against its checksum:

```bash
sha256sum -c Sonic-1.1.0.apk.sha256        # Linux
shasum -a 256 -c Sonic-1.1.0.apk.sha256    # macOS
```

- Android 9 or newer, on ARM64, ARMv7, x86-64 or x86.
- Sonic installs as its own app (`app.sonic`). If you used Runout, it stays where it is; Sonic
  doesn't replace it or read its data.
- No account needed to listen. Skip sign-in and Sonic plays the folder you give it.

### Soulseek, in plain words

Soulseek has no sign-up page: the first time you sign in with a new name, that name becomes your
account, typos included. It has no password reset. Signing in elsewhere with the same name signs
you out here. Its protocol doesn't encrypt passwords or private messages.

Sonic has no server of its own. Your Soulseek password is encrypted with an Android Keystore key
and kept out of backups. Cover lookups are off until you turn them on. Only the one folder you
choose is shared, only its FLAC files, and by default only on Wi-Fi. Chats stay on your phone.

## Build it

Kotlin, Jetpack Compose and Media3 for the app; [Filament](https://github.com/google/filament)
for the 3D deck; a pinned Rust library for Soulseek behind a small JNI bridge, built for four ABIs
with 16 KiB page alignment.

**You need** JDK 21, Android SDK 36, Rust 1.91.0 and Android NDK r27d or newer. The
[build guide](docs/BUILDING.md) has the details.

```bash
git clone https://github.com/Abhiroop1122/Sonic.git
cd Sonic

./gradlew testDebugUnitTest assembleDebug
cargo +1.91.0 test --manifest-path native/Cargo.toml --workspace --lib --locked
```

A debug build installs as **Sonic. Dev** (`app.sonic.dev`) next to the release. Tests run on
local fixtures and test servers; the integration test talks to two local Soulseek peers and never
creates a public account. The screenshots above come from an emulator with a made-up library.

```text
app/src/main/java/app/runout/   the app (the package keeps its old name)
  library/   scanning, the shelf, covers, storage
  playback/  the deck and the playback service
  soulseek/  the bridge, downloads, sign-in
  sound/     DSP, output, the signal path
  ui/        the room, the crate, the deck, every page
native/      the Rust JNI bridge and the vendored soulseek-rs library
docs/        one file per subject, plus design notes
release/     release notes, checksums, verification reports
```

| To learn about | Read |
| :--- | :--- |
| Using the app | [Handbook](docs/HANDBOOK.md) |
| Building and testing | [Building](docs/BUILDING.md) · [Developer notes](docs/DEVELOPMENT.md) |
| How the code fits together | [Architecture](docs/ARCHITECTURE.md) |
| Soulseek, storage and privacy | [Soulseek](docs/SOULSEEK.md) |
| Audio | [Sound](docs/SOUND.md) |
| The turntable | [Turntable](docs/TURNTABLE.md) |
| Records and Friday Drops | [Pressing and Drops](docs/PRESSING-AND-DROPS.md) · [Record format](docs/RECORD-FORMAT.md) |
| Releasing | [Release process](RELEASE.md) · [Changelog](CHANGELOG.md) |
| Everything | [Documentation index](docs/README.md) |

## Releases

| Version | | |
| :--- | :--- | :--- |
| **[1.2.0](https://github.com/Abhiroop1122/Sonic/releases/tag/v1.2.0)** | **Current** | The neon deck, vinyl only, the Bluetooth codec, covers that stay. |
| [1.1.0](https://github.com/Abhiroop1122/Sonic/releases/tag/v1.1.0) | Release | The track wheel picker, update checks with stable and development builds, a clearer signal chain. |
| [1.0.0](https://github.com/Abhiroop1122/Sonic/releases/tag/v1.0.0) | Release | Sonic. Themes, three decks, Storage, swaps by hand. |
| [0.6.0](https://github.com/Abhiroop1122/Sonic/releases/tag/v0.6.0-dev.1) | Development | The RG-1 3D turntable, rendered with Filament. |
| [0.5.1](https://github.com/Abhiroop1122/Sonic/releases/tag/v0.5.1) | Release | Soulseek and FLAC, the redesigned room. |
| [0.4.0 – 0.5.0](https://github.com/Abhiroop1122/Sonic/releases) | Development | Each step kept as a pre-release. |
| [0.3.0](https://github.com/Abhiroop1122/Sonic/releases/tag/v0.3.0) | Release | The Glass Turntable, as Runout. |

## Contributing

Issues and pull requests are welcome. Start with the [contributor guide](CONTRIBUTING.md) and the
[architecture map](docs/ARCHITECTURE.md). Everyone taking part follows the
[Code of Conduct](CODE_OF_CONDUCT.md). Security problems go through the
[security policy](.github/SECURITY.md), not public issues.

## Design

Sonic's look borrows from two companies we admire: the bare, playful hardware of
[Teenage Engineering](https://teenage.engineering) (hard keys, engraved labels, one loud orange)
and the dot-matrix type and black-and-white calm of [Nothing](https://nothing.tech). Sonic isn't
affiliated with or endorsed by either. The RG-1 decks were modelled in Blender for Sonic, and the
sleeve and onboarding art was made for it too.

## Licence and credits

Sonic is [MIT licensed](LICENSE). Settings › about names the licences of what's bundled, and
their full texts ship inside the APK.

- **Soulseek:** [soulseek-rs-lib v19.1.0](https://github.com/michel/soulseek-rs) (MIT), vendored
  with documented changes in [native/](native/README.md).
  [Seeker](https://github.com/jackBonadies/SeekerAndroid),
  [Soulseek.NET](https://github.com/jpdillingham/Soulseek.NET) and the
  [Nicotine+ protocol notes](https://nicotine-plus.org/doc/SLSKPROTOCOL.html) helped us understand
  the protocol; none of their GPL code is in Sonic.
- **Libraries:** [Filament](https://github.com/google/filament) (Apache 2.0), AndroidX, Jetpack
  Compose, Media3 and Room (Apache 2.0), [OkHttp](https://github.com/square/okhttp) (Apache 2.0),
  [Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (Apache 2.0).
- **Type and icons:** [Inter](https://rsms.me/inter/) and
  [Silkscreen](https://kottke.org/plus/type/silkscreen/) (SIL Open Font License),
  [Array](https://www.fontshare.com/fonts/array) by Indian Type Foundry (ITF Free Font License;
  in release builds only, not in this repository, so a source build falls back to Silkscreen), and
  icons drawn after [Lucide](https://lucide.dev) (ISC).
- **Music data:** [MusicBrainz](https://musicbrainz.org), the
  [Cover Art Archive](https://coverartarchive.org) and the
  [Internet Archive](https://archive.org), only when you turn cover lookup on or open Friday Drops.
  Drops keep their Creative Commons licences and credits. Music you download from Soulseek isn't
  marked as openly licensed.

Sonic was called Runout until 1.0.0.
