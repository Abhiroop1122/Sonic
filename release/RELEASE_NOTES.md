Sonic. 1.5.0: MP3 from your own folder, and a walk round the room.

## What's new

- **MP3 in your folder.** A new switch in Settings › Flags, off by default. With it on, MP3 albums in your music folder become records too. MP3 is lossy, so its record is pressed black, never hi-res, and the signal path says "MP3 · lossy", never lossless or bit-perfect. If a folder holds the same album in FLAC and MP3, the FLAC is kept. Dig, Soulseek downloads, sharing and Friday Drops stay FLAC only.
- **A tour of the room.** The first time the room opens, and once after this update, it lights one control at a time: start / stop, skip, eject, the crate, the record, the display, two fingers to lay the deck flat, and Settings › Flags. Use the lit control or tap next. Settings › About › how to play the deck plays it again. It replaces the old ? keys and the tilt hint.
- **Storage, shown once.** The first time you open Settings, it shows the two-finger swipe up that opens Storage.
- **What's new waits for the intro.** After an update it appears once the opening has handed over to the room, not on top of it.
- **New installs** start with the intro on launch and vinyl only switched on.
- The Bluetooth codec readout no longer risks a crash on Android 9 to 12, and names extended codecs on Android 15.

Everything else is as in [1.4.2](https://github.com/Abhiroop1122/Sonic/releases/tag/v1.4.2). The full list is in the [changelog](https://github.com/Abhiroop1122/Sonic/blob/main/CHANGELOG.md).

## Install

- Android 9 or newer, on ARM64, ARMv7, x86-64 or x86.
- Download `Sonic-1.5.0.apk` and `Sonic-1.5.0.apk.sha256` into one folder, then check them:

  ```bash
  sha256sum -c Sonic-1.5.0.apk.sha256        # Linux
  shasum -a 256 -c Sonic-1.5.0.apk.sha256    # macOS
  ```

- It updates Sonic 1.0 to 1.4.2 in place (same app, same signing key).
- Also in the [Sonic F-Droid repository](https://somchandra17.github.io/sonic-fdroid/repo) and through Obtainium.

## Known limits

- The APK is signed with the maintainer's development key, not a production key.
- Behind carrier-grade NAT (many home ISPs, and every mobile network) no one can reach you, and no router setting changes that: you can download from, and upload to, people whose port is open, but two people who are both unreachable can't exchange files.

Sonic is MIT licensed. Credits are in the [README](https://github.com/Abhiroop1122/Sonic#licence-and-credits).
