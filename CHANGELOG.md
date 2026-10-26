# Changelog

All notable changes to **Sonic** (formerly Runout) are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## 1.3.0 (2026-10-26)

Dig finds what's out there, friends' shelves open, and sharing works.

- **Dig hears everyone who answers.** A popular search draws thousands of replies through Soulseek's relay. Sonic held each of those connections for minutes and turned most of them away: a Daft Punk search heard 79 people where 2,300 answered. A connection that only brought search results now closes two seconds after them, so Dig hears far more of the network.
- **Searches ask for FLAC.** Every search carries the word "flac", so people send their FLAC files and not the MP3s that used to fill the space. Punctuation is left out, as some clients answer nothing when it's there. Dig keeps up to 15,000 files instead of 5,000.
- **Hidden blocks.** Soulseek silently drops searches for some names (anything with both "frank" and "ocean" reaches no one). When no one answers at all, Dig searches again with one word left out at a time, keeps only folders that match every word, and says so. Frank Ocean's *Blonde* now turns up hundreds of times.
- **Friends' shelves.** Open a friend's chat and tap "their shelf" to see every FLAC folder they share; a folder opens with its tracks and the download key.
- **Sharing:**
  - Sharing a folder no longer stops at a long file name. A name over 120 characters lost its ".flac", and the whole folder was refused ("Only FLAC files may be shared"). Long names are now shortened before the extension; any file that still fails a check is left out, not the folder. Downloads with long names keep their ".flac" too.
  - An upload to someone Sonic couldn't reach held its slot for the rest of the session; two of them stopped uploads altogether. It now lets go after 30 seconds.
  - After your first Soulseek album, Sonic asks once whether to share your download folder (FLAC only, Wi-Fi only). People who share nothing get treated as leechers; nothing is shared without a yes.
  - Settings › Soulseek shows what you've uploaded to others and downloaded, in size and files.
- **Refusals keep their reason.** When someone turns a download down, Sonic keeps their reason. A full queue ("Too many files") is asked again later; a ban or a file they no longer share goes looking for another copy.
- **What's new.** The first time Sonic opens after an update, it shows that version's notes; Settings › About shows them again.
- **No gap above the keyboard.** Android moved the whole window for the keyboard while the page made room for it too, so a chat's message box floated a keyboard's height above the keys. The window now stays put.
- **Friday Drops** draw on the whole Creative Commons FLAC pool from the week of 30 October: everything published before that Friday, about 12,600 albums, picked without regard to popularity. An empty answer from the archive is no longer kept as a week without albums.

---

## 1.2.0 (2026-10-25)

A neon deck for the dark room, the codec your headphones use, and covers that stay.

- **The neon deck.** A fourth RG-1: OLED black, drawn by white neon edges that glow.
  - It's the dark room's deck now: Flags › deck › theme puts it in a dark room (white / teal in a light one). It's for dark rooms only; picked in a light room, white / teal stands in.
  - Its room is unlit: only the edges and the record show, with just enough light for the label. Flags › deck lights turns the studio lights on.
  - In a dark room the intro plays on it.
- **Vinyl only** (Flags › deck). Sideways, everything but the deck goes black and the deck moves to the middle: on an OLED screen those pixels are off. A tap anywhere brings the room back without pressing anything; it goes dark again after ten quiet seconds.
- **The SONIC. wordmark** is printed in the top-right corner of every deck, its full stop an orange dot. The deck's old "RUNOUT" print is gone.
- **Shared record pictures** carry the SONIC. wordmark instead of the sleeve, in ink that suits the light or the dark theme.
- **Bluetooth codec** (Settings › Sound, or the Audio quality panel with headphones on). Off until you turn it on. It asks for nearby devices and, on Android 14 and later, Android's one-time link with your headphones; then the signal path names the codec, as Android reports it: "LDAC · 96 kHz · 32-bit". Still lossy, and it says so.
- **24-bit to USB DACs.** For DACs whose bit-perfect modes take 24- or 32-bit integers, Pure now opens the output as integers and turns each sample back into the file's own value, so 24-bit files reach the DAC bit for bit. It only runs when Android offers such a mode; untried on a DAC that does (the Pixel 6 routes USB through its mixer).
- **Covers:**
  - Albums from Drops and Soulseek keep their art after a reinstall: Sonic reads it back from the album's own folder (`.cover.jpg`, or the sleeve inside `record.vinyl`).
  - An album with no cover shows the SONIC sleeve, cream in light and black in dark, instead of a blank.
  - Covers Sonic fetched or saved no longer show up in Google Photos and other gallery apps: the folders that hold them are marked as not media, older ones included.
  - Each download wrote another copy of `record.vinyl` (as `record.vinyl (1).zip` and on). It's written once now, and the extra copies are tidied away.
- **Downloads with the screen off** checked on a real phone.

---

## 1.1.0 (2026-10-25)

Feedback from the first week of 1.0.0, and updates from inside the app.

- **The track wheel is a picker.** Scroll the songs under the bar in the middle: the one there is inverted, with "tap to play" in orange ("playing" for the song that's on). Only that song plays on a tap; tapping another rolls it under the bar first. A song on the other side turns the record over and plays; it used to turn it over and stop.
- **Updates.** Settings › About checks GitHub for a newer release, and Settings › Flags › updates does it once a day (on by default).
  - The first check asks which builds you want: **stable** (tested releases) or **development** (pre-releases too). Both are the same app and update each other; change it any time in Flags.
  - A newer one shows at the top of Settings, with an orange square on its tab; "get it" opens its page in the browser. Sonic never downloads or installs anything itself.
  - Versions follow Semantic Versioning; development builds are `x.y.z-dev.n`, published as GitHub pre-releases.
- **Audio quality.** The signal chain heads Settings › Sound again. In it and in the Audio quality panel, only the step that changes the sound says why; the others show their format.
- **Storage** opens on your music beside the whole phone: one line ("your music takes … of …, … free") and one bar. The introduction is gone.
- **Shared record pictures** carry the SONIC sleeve, small, in their top-right corner.
- **Downloads:**
  - Pause all also stops downloads Sonic would restart by itself (a retry or a search waiting, or a resume after an interruption), and pending retry timers.
  - Resume all starts every paused download even when one of them can't start.
  - While files move, the phone and its Wi-Fi stay awake, so a dozing phone with the screen off no longer stalls them.
- **Friends:** no gap between the message box and the keyboard.
- **Page heads** lose their numbered label ("05 / settings"); close or back stands alone above the title.
- **Getting ready for F-Droid:** store text and pictures in `fastlane/`, a draft recipe in `fdroid/`, no dependency metadata in the APK, and builds without the update check (`-Psonic.updates=false`). See docs/FDROID.md.

---

## 1.0.0 — Sonic. (2026-10-23)

The first stable release, and the first under the new name. It follows 0.7.0 development.

- **Runout is now Sonic.**
  - New app id `app.sonic` (`app.sonic.dev` for development builds). It installs beside Runout as a separate app with its own private data.
  - The launcher name, wordmark, LED, notification, help and error texts, Settings and About all say Sonic. The wordmark's full stop is a round dot in signal orange.
  - Files Sonic writes carry its name. Record cards are sent as `sonic:record`; cards from Runout still open. The code package, the native library and `.vinyl` keep their old names.
- **New launcher icon:** the SONIC sleeve from the intro, an orange record on its paper.
  - It follows the app's theme: a cream sleeve while Sonic is light, a black one while it's dark. The swap happens when Sonic leaves the screen.
  - Themed icons (Android 13+) get a one-tone sleeve with its grooves, dotted word and arm cut out.
- **Themes:** Settings › Flags › theme picks phone, light or dark.
  - Dark is true black for OLED screens, with white ink and the same orange signal and dot type. The window and bar icons follow.
  - A dark phone no longer flashes white at launch. On Android 13+, a theme pinned in Sonic also sets the next launch screen. Onboarding's step pictures are redrawn on black.
- **Three decks:** the RG-1 comes in white / teal, mono white and dark, picked in Settings › Flags › deck. By default it matches the theme: white / teal in light, dark in dark.
- **Intro:**
  - New art: the SONIC paper sleeve and its record, with a dark sleeve for the dark theme. The record's label is laid over the deck's once it lands, so it looks the same before and after.
  - It plays slower (about 7 s) and shows the deck the room uses, in any colourway.
  - Settings › Flags › intro on launch plays it each time the app opens, then the room rises in around the deck. Onboarding ends the same way, on the room's own deck.
  - While the 3D deck loads, a dotted vinyl turns where the record will be.
- **The room:**
  - Sideways is the listening room only. Turning the phone closes Dig, Drops, Friends or Settings, and the tabs ask for the phone upright (Downloads still works). The room fills the screen with bigger sleeves and nothing printed around the deck.
  - Sleeves carry only the Hi-Res sticker. Title, artist and label are read out by TalkBack instead. A soft shadow sits under the crate.
  - The top bar shows a green play triangle while a record plays, and a download glyph coloured by status (failed red, in queue orange, done green).
  - The "rg-1 · side a" line under the deck is gone (the LED shows it), and so is the "now spinning" line above the tabs.
- **The deck:**
  - A lamp behind the platter puts a glint and a still wedge of light on the turning record. The grooves are a little glossier.
  - Two fingers down lay the deck flat; two fingers up stand it back. It's remembered, TalkBack gets a Flat/Angled action, and a one-time hint shows the gesture after onboarding.
- **Records in hand:**
  - Hold a record over a playing one for 650 ms to swap. The deck shakes with a tick each time (SWAP on the LED), the old record rises out (EJECT), the new one lands and keeps playing.
  - Eject still flies the record back to its sleeve in the crate. Let go anywhere off the platter and the disc glides back into its own sleeve.
  - Swipe the crate with a record out and it slides home first; then the crate catches up with your finger.
  - The record on the platter can't be pulled out again, and a landing never reloads the record already on the deck. Swaps and ejects cut short by a page, Back or a turn of the phone end cleanly.
- **Record details:** a long press on a sleeve or the pulled disc opens them, in place of the old menu.
  - They end with the record's running time and links to put it back, fetch missing tracks or remove it. A share key sends the track list as a picture.
- **Flags:** three new switches in Settings › Flags, all off by default.
  - Auto-flip turns the record over when a side runs out and plays the next one. Auto-play starts a record as soon as it's on the platter.
  - Sleep mode: a dot-matrix moon in the top bar opens a sleep timer (15 to 50 minutes). The music fades, the needle lifts, and the moon shows the minutes left in orange.
- **Settings:**
  - Five rows (sound, flags, library, soulseek, about) under a faint wordmark. Library holds the download folder and the cover lookup; soulseek holds account, downloads, sharing and connection.
  - Pages slide up from below and drop away going back. Inside a section, chat, drop or folder preview, the head reads "← back" and steps back one level. Pages opened from the room or the nav bar keep "close ×".
  - Tapping the open tab again goes back to its first page (Settings' list, the chats, the week's drops, Dig's search) instead of out to the room.
- **Storage:** swipe up with two fingers on Settings to open it, down to go back.
  - It shows what the music and download folders take up, by kind (hi-res FLAC, cd-quality FLAC, other audio, pictures, unfinished downloads), with their records largest first.
  - Sonic's own covers, cache and data are listed too, with a key to clear the cache.
  - A record in its own folder can be deleted, after a warning in the other theme's colours ("keep it" / "delete"). The picked folder, or one holding another record, is never offered.
- **Downloads:**
  - Three segments with counts: failed (red), completed (green), in queue (orange). Remove queued files, retry or remove failed ones, or clear the lot. Saved music is never touched.
  - A failed song fixes itself where it can. A peer that went away is asked again after 1, 5 and 15 minutes. A refused or broken file is fetched from someone else with the identical file.
  - When nobody shares it, the row says so and offers "find another copy" in Dig. Rows name their album and what Sonic is doing ("retrying in 4 min", "now from zoe").
  - A record missing a track wears a red "1 MISSING" tab and can fetch the rest. A completed track whose file is gone from the folder downloads again.
  - Adding to the queue pops a short note with a haptic tick and a link to Downloads.
- **Dig:**
  - A folder preview knows what's on your shelf. Each track shows a tick, its progress or a download key, and the main key fetches only what's missing into the album you already have.
  - The head stays put and only the results scroll. A dotted vinyl spins while searching. The search is cleared ten seconds after you leave.
- **Sharing:** Settings › soulseek › share on Wi-Fi only (on by default) and uploads at once (1, 2, 3 or 5; 2 by default). On mobile data nothing new is offered and a running upload finishes.
- **Friday Drops:**
  - From Friday 30 October everyone gets the same ten albums each week; earlier weeks keep their pick. Drops reloads the week each time it opens, so a Friday that passes with the app open brings the new drop.
  - The 30-second listen streams the archive's MP3, shows buffering, then a stop square, and stops cleanly on errors, timeouts, unplugged headphones or leaving the app.
  - The orange square on the tab marks a drop you haven't opened. Drops sit on plain white tiles in a black outline. Onboarding has a Friday Drops step.
- **Friends:**
  - The tab opens on the chats, on its own page. A line says messages stay on this phone. Record cards open the sender's Soulseek folder.
  - The composer is boxed and sends on enter, with black and orange bubbles. The sign-in prompt now clears once you're signed in.
- **Sound:**
  - Output to 16-bit is rounded, with TPDF dither only when the signal was processed or the source is deeper than the output. Hi-res decodes to float.
  - Bit-perfect is claimed only on an exact format match on a routed USB device; otherwise the signal path says why. A USB DAC fed a rate it doesn't take, or mixed by Android at a fixed rate (48 kHz on the Pixel 6), reads as resampled.
  - Pure keeps Android's Spatial Audio off Sonic's stream. Outside Pure, the signal path says when Spatial Audio is reshaping the sound.
  - Gapless joins and ReplayGain changes no longer click.
  - The scanner skips FLACs the phone can't decode (12- and 20-bit, 32-bit on Android 9, above 192 kHz before Android 12). An album's quality is its weakest track.
- **Library and covers:** changing the music folder replaces the shelf, releases the old folder, ejects a record from it, and asks whether sharing should follow.
  - "find missing covers online" now covers Soulseek downloads and Drops too. Off means no MusicBrainz lookups; turning it on looks straight away.
- **Haptics:** a detent as each record passes the crate's front. The tonearm lifts with a catch, ticks at track gaps while carried and lands with a thud. EQ faders tick per dB, firmer at 0 dB. The sleep timer confirms when it starts and stops.
- **Battery and background:**
  - The Soulseek connection polls once a second when idle instead of ten times. Download progress and the transfer notification update at most once a second.
  - The native client forgets finished transfers, and its TCP keepalive waits 60 s and probes every 10 s, so the mobile radio can rest.
  - The 3D deck and the record in hand stop drawing when nothing moves.
- **Fixes:**
  - The loader could stay over the 3D deck if the deck settled while frames were being skipped.
  - In the dark theme, dragging a record showed a grey box on the deck.
  - Pages left a gap between the keyboard and a text box.
  - Turning the phone sent the crate back to its first sleeve. It now stays on the record you were looking at.
  - The Hi-Res sticker printed blank on sleeves in the dark theme.

---

## 0.7.0 development — the TE room (2026-10-22)

- **The whole app is rebuilt to the TE boards** (`docs/design/runout-te.pen`): black ink on white, zero radius, no shadows or glass, 1 dp hairlines.
  - The dot-matrix type follows the boards' scale: Array at 400 (10/12/16) and its dotted 700 (12 to 132). The stickers on the sleeves use Inter 700/800.
  - Array is embedded in the APK under its licence. It is not committed to the repository; a source build without it falls back to Silkscreen.
- **Home:** a top bar shows the side that's up ("side a · a2 · 2 / 4"), the line out, downloads and the record count.
  - Under the deck sits its band ("rg-1 · side a · 01:12 · 33⅓ rpm").
  - The crate is redrawn: neighbouring sleeves turned hard away, a floor line, and a faint reflection under the front sleeve. Its stickers are an orange HI-RES round and a black label tab, both slightly tilted.
  - Below the crate: the album caption, a side-coded track wheel (the playing song sharp, the rest soft), and the "now spinning" line.
  - First-time "?" helps open help panels. The landscape room mirrors portrait.
- **Pages:** Dig, Drops, Friends, Settings and Downloads are full pages with the TE screen head ("02 / dig", "close ×") under one nav bar.
  - Audio quality opens from a long press on the deck's LED. Crackle moves to Settings › Sound.
  - Dig results are two compact lines without art. Every "?" opens its help panel.
- **Onboarding:** a 3 s intro tells the needle-drop story and types RUNOUT out (tap to skip; skipped when animations are off). Steps show poster-size numbers, and the welcome greets you by your Soulseek ID over the deck.
- **The record in your hand is 3D** from the sleeve to the platter. It now:
  - turns over in 3D when you flip it (about 0.9 s, with FLIP on the LED and a squash on the flat deck);
  - flies home to its sleeve on Eject (about 1.1 s: EJECT, lift, arc, slide in);
  - bounces back off a busy platter (a red ring, a double buzz, and "first, remove the current record from the platter." with an eject key).

## RG-1 v3 (in 0.7.0)

- **A wider deck, controls on the right:** the turntable is redrawn as the RG-1 v3. It is wider and shallower, so it now fills the width of the screen.
  - Its keys sit on the right in TE style: big, flush keys edge to edge for prev, next, START/STOP and eject.
  - The tonearm, platter and record are unchanged.
- **Red LED windows:** a long title display under the platter shows the track. A time display under the keys shows the track code and the time in 7-segment digits, with the unlit segments faintly showing. Both replace the small screen and the line of text under the player. TalkBack still reads them.
- **The record looks the same in your hand and on the platter.** The deck now renders with true colours (linear, calibrated light) instead of a film curve, and the face no longer carries a painted highlight that turned with the platter.
- **The gaps between tracks show on both records.** The record in your hand now draws side A's gaps; it had none. On the 3D record each gap is a smooth, glossier band in the grooves, so it catches the light.
- **No more empty deck at launch:** a flat deck stands in while the phone builds the 3D deck's shaders. That takes seconds on an emulator.

---

## 0.6.0 development — the 3D turntable (2026-10-22)

- **The record player is now 3D:** the Runout RG-1, a white turntable with teal glass and an anodised-orange START/STOP key, drawn live with Filament over the room.
  - The platter and the record turn in 3D. Each record's face (vinyl, the cover as the label, the gaps between tracks) is painted onto a grooved disc.
  - The tonearm swings on its bearing, follows the side, drops into the groove and can be dragged. Tap the record to flip it, hold it to slow it.
  - New control panel: START/STOP, ◀◀ ▶▶ and a new **eject** key, which press down on the model.
  - The deck has its own lights and screen: an orange status lamp while a record plays, an LED strip that fills as the side plays, and a small screen with the track and time.
  - If the phone can't draw 3D, a flat deck stands in.
- The APK is about 22 MB larger: Filament's libraries for the four CPU types.
- **Library:** fixed a race between the quiet refresh that runs at startup and a scan the user starts at the same time. The startup refresh cancelled the newer scan, so the shelf could stay empty — the same symptom as the "albums vanish" bug fixed in 0.5.1. The startup refresh no longer cancels a scan that is already running.

---

## 0.5.1 — fixes from the first phones (2026-10-21)

This is the current release. It is the build published as `v0.5.1`, the same bytes and the same
certificate as the `v0.5.1-dev.1` development pre-release it was promoted from.

- **Library:**
  - Fixed: after a reinstall, albums appeared for a second and then vanished. One unreadable `.flac` made the whole scan roll back to the previous shelf, which was empty. A partial scan now keeps everything it read, merged with what it couldn't see, and saves it.
  - New: Settings › Library › **Repair local albums**. It rebuilds the shelf from the folder alone and lists every file it couldn't read, with the reason (for example an MP3 renamed to `.flac`, or a damaged or cut-off FLAC). Music files are never changed.
- **Audio quality on the home screen:** a small chip such as "24/96 → Phone speaker" opens the signal chain: Track → Runout → Output, each with its bit depth and rate, and a plain verdict ("Bit-perfect", "Lossless, resampled to 48 kHz", "Lossy over Bluetooth"). Runout now knows the output device: speaker, wired, USB DAC or Bluetooth.
- **Pure and Hi-res output** are explained where they're switched: Pure is about processing, Hi-res about the format, and they combine.
- **Friday Drops:** only albums with FLAC files are picked. This week is picked again.
- **Downloads:** "Stream read error: Interrupted system call" was Android pausing the app (Background usage off). It now shows as a pause with the reason, Downloads shows a note with a link to App info, and interrupted downloads continue when Runout is back.
- **Dig:** a round search key beside the field. Its filters sheet, and Friends' New chat sheet, now shade the whole page.
- **Home:** Crackle is a switch. In landscape the tab bar sits at the bottom right, and the status line no longer runs into the sleeves.
- **Sign-in:** with the keyboard up, the record collage fades and the headline folds away instead of sliding under the titles.
- **Code:** `ui/Turntable.kt` holds only the vinyl player, with a map at the top.
- **Native (source only):** download reads retry an interrupted system call. Not in this APK: the native library is reused unchanged from 0.4.6.

### Release archive

Every development build from 0.4.0 to 0.6.0 is kept as a GitHub pre-release, with its APK, its
checksum and its verification report attached, so each step of the work can be inspected and
installed on its own:

| Tag | Build |
| :--- | :--- |
| `v0.6.0-dev.1` | The RG-1 3D turntable |
| `v0.5.0-dev.1` | The redesigned listening room |
| `v0.4.6-dev.1` | The original glass room restored |
| `v0.4.5-dev.1` | The glass room returns |
| `v0.4.4-dev.1` | Download labels and record writing |
| `v0.4.3-dev.1` | The idle vinyl rack |
| `v0.4.2-dev.1` | Data-loss and privacy fixes |
| `v0.4.1-dev.1` | Faster FLAC discovery, reliable sessions |
| `v0.4.0-dev.1` | Soulseek and FLAC |

They are kept under their original `-dev.1` names, because that is what they were: development
builds, and the record should say so.


## 0.5.0 development — UI redesign (2026-10-21)

From a screen-by-screen interview with the owner; the full checklist is in `docs/design/UI-REDESIGN.md`.

- **Deck:**
  - Teenage Engineering details on the glass plinth: keys with real travel, anodised-orange START/STOP and counterweight, engraved labels and ticks, and a finer dot-matrix screen.
  - Drawn in layers, so only the record redraws each frame.
  - The platter turns slower on screen, with a real-strobe effect.
- **Home:**
  - The footer is four icon tabs. The clock and Change folder are gone from the top.
  - Status shows as coloured dots.
  - The caption is title and artist plus a spec tag.
  - A long press opens a record's menu.
  - Disc pull-out and landing are animated, with haptics and a thud.
- **Onboarding:**
  - Full screen, opening with a needle-drop intro.
  - One Soulseek sign-in that always remembers you; an ⓘ explains how IDs are created.
  - Steps move on by themselves, and it ends with a greeting that pops into the room.
- **Dig:**
  - A full page, albums only, from two sources.
  - Tap-to-preview cards, peer marks and a floating filters key.
  - Sleeve-style previews with clean track names, and better ranking.
- **Friday Drops:** a sleeve shelf, claim lights, and a preview with track list and 30-second listens.
- **Friends:** a full page with chats, friends with online dots, a + to start a chat, and records sent as cards.
- **Settings:** a list of pages, short copy with ⓘ, and a TE-style EQ. Downloads controls are icon keys.
- **Pressing Plant:** removed. Records pressed earlier stay playable.

## [0.4.6] - 2026-10-21 (development)

### Changed
- The original glass listening room is back. The deck is the only player.
- 33/45, pitch and power controls are removed. Records play at the recorded speed.
- Search cues a song on the platter with the needle up. START/STOP plays it.

---

## [0.4.5] - 2026-10-20 (development)

### Changed
- Home is the glass listening room again: crate, picture disc, and glass turntable. The inked deck and idle disc rack are not the home screen.
- Soulseek search keeps a result only when the file is FLAC and every search word matches.
- The download list stays in the order downloads were added. Pause all, Resume all, and Turtle mode are on the download screen.
- Pure mode passes the decoder's PCM through without converting it. A saved download is refused if its bytes do not match the source.

---

## [0.4.4] - 2026-10-21 (development)

### Added
- The printed deck is now played by hand: drag the arm to a band, use the cue and motor, swipe a finished record up to turn it over, and drag it down to put it away.
- Settings can show labelled deck controls. They stay off unless enabled.
- Each completed download writes `record.vinyl` beside the music, with cover art, album metadata, and the source download links.

### Changed
- Covers sit in the centre label of a black record. They are no longer printed across the disc like a CD.
- Next and previous stay on the current side. Flip is the only way to the other side.
- Lowering the needle no longer fades the first moments in from silence.
- Internet Archive downloads resume a partial file. Cancel removes unfinished staging, and a corrupt partial is deleted instead of being reused.

---

## [0.4.3] - 2026-10-21 (development)

### Added
- Idle home opens on a full-screen vinyl rack. Scroll up or down to move records, tap the facing disc to unbox it, then drag it onto the platter.
- First inked home shell: paper room, soft pills, Settings, Crate, and a printed transcription deck when a record is in the room.

---

## [0.4.2] - 2026-10-21 (development)

### Fixed
- Retain unavailable records and shelves through failed reads or folder scans, and create a dated backup before every index rewrite.
- Bound `.vinyl` metadata reads, clean abandoned imports, require checksums, strip private provenance, and prevent imported records from inheriting sender ownership.
- Isolate Soulseek event failures, preserve another-client displacement, and stop native downloads without discarding partial files.
- Bind Friday Drops claims to the admitted week.
- Keep onboarding, Pressing drafts, message drafts, and received-record removal from being lost or completed accidentally.
- Make online cover lookup opt-in for new and existing installs, and exclude device-bound setup choices from Android backup.

---

## [0.4.1] - 2026-10-20 (development)

### Fixed
- Keep account identity through network loss and app background pauses; reconnect without asking for credentials again. Distinguish another-client displacement from temporary connection loss.
- Protect newly connected discovery peers long enough to reply, fixing public searches that previously returned zero results under connection churn. Explicit browsing/downloads can take priority over discovery.
- Stream bounded native result batches, cache result identities and path components, avoid repeated folder-wide matching and filename collation, and keep local indexing separate from network updates.
- Preserve sessions during external folder pickers and notification permission prompts, scope sharing consent and folders to each account, and discard stale sharing scans after account/session changes.
- Cancel the previous needle-settling ramp when loading a different record, and play locally searched songs on their correct side.

### Added
- Guided offline/Soulseek onboarding with download folder, optional sharing and explained connection modes.
- Retro Home, Library, Discover and Messages navigation, global transfers/account access and mini player; retain the vinyl home.
- Unified metadata search, song/album views, availability/quality filters, sorting and complete peer folder preview before album download.
- Argent device flows, Android performance checks and an explicit private-credential public-network probe.

---

## [0.4.0] - 2026-10-19 (development)

### Added
- Soulseek FLAC search, track/folder downloads, private messages, contacts, optional selected-folder sharing and stay-online mode.
- Pinned MIT Rust core with Android JNI libraries for four ABIs and local peer interoperability tests.
- Room-backed transfers/conversations and optional Keystore-protected credentials.
- User-selected download destination; completed files survive uninstalling Runout.

### Changed
- All music admission and playback is FLAC-only, with byte validation and non-destructive legacy migration.
- Soulseek sign-in gates both Soulseek and Internet Archive downloads; offline playback remains account-free.
- Nostr online UI and automatic relay publication are replaced; legacy local data are preserved.
- Internet Archive MP3/Ogg fallback is removed; unavailable/failed FLAC downloads consume no claim.

---

## [0.3.0] - 2026-10-19

### Added
- **The Glass Drive RG-1 Turntable:** Complete redesign inspired by classic direct-drive DJ decks. Features a frosted liquid glass plinth with dynamic refraction shaders (Android 13+), 4-row strobe platter (180 dots/row), S-shaped chrome tonearm with counterweight, anti-skate dial, and exact law-of-cosines tracking geometry.
- **Picture Discs:** Albums with cover artwork are now pressed as picture discs with album art rotating underneath grooved vinyl shaders and smooth track gap bands.
- **Dot-Matrix Pixel Screen:** Plinth-integrated 8-bit display driven by the Silkscreen pixel font. Real-time scrolling track marquee, timer, RPM, and cue readouts. Full TalkBack screen reader support.
- **Serverless Nostr Mesh:** User accounts based on local 256-bit private keys (`nsec1...`). Public profiles and shelves published as kind 33760 events on public relays (`relay.damus.io`, `nos.lol`, `relay.primal.net`).
- **Tag Prefix Search:** Fast username search across public relays with 4-character key collision prevention (`@username·XXXX`).
- **SHA-256 Checksummed Packages:** Every audio file in a `.vinyl` bundle is verified byte-for-byte on arrival.
- **Deck Controls:** Start/Stop toggle, 33/45 RPM switches, ±8% tactile pitch fader with center detent, and track skip controls (`◀◀` / `▶▶`).

### Changed
- Replaced system metadata retrievers with native binary tag parsing for FLAC, MP3, and WAV.
- Expanded Robolectric test coverage to 12 test suites and 106 unit tests.

---

## [0.2.0] - 2026-10-12

### Added
- **Custom 32-Bit Float AudioSink:** Dedicated audio processing pipeline in front of ExoPlayer preserving high-resolution audio up to 24-bit/192 kHz.
- **Sound Panel:** 10-band graphic equalizer with ISO center frequencies, automatic headroom pre-attenuation, AutoEq headphone profile import (`ParametricEQ.txt`), ReplayGain, and look-ahead limiter at −0.3 dBFS.
- **Pure Mode:** Bit-perfect passthrough disabling all DSP filters. Android 14+ USB DAC bit-perfect output support.
- **Pressing Plant:** Custom record creator with 6 generative sleeve patterns, 6 vinyl formulations (Black, Solid Colour, Clear, Splatter, Marble, Picture Disc), and up to 40 tracks.
- **Friday Drops:** Deterministic weekly release of 10 Creative Commons netlabel albums from Internet Archive with a 3-album weekly claim quota.
- **Serverless Sharing:** Portable `.vinyl` file distribution via Android system share sheet (Quick Share, Telegram, WhatsApp, Drive).
- **Portrait Layout:** Responsive dual-mode UI supporting vertical stacking (turntable on top, crate below).

### Fixed
- Fixed folder scanning bug where Android's storage provider failed to return files for nested directories.

---

## [0.1.0] - 2026-10-01

### Added
- Initial prototype listening room.
- Storage Access Framework folder selection.
- 3D perspective vinyl crate.
- Basic spinning turntable with tonearm drag-and-drop.
- Real-time synthesised vinyl SFX (needle drop, surface crackle, runout groove).
- MediaSession background audio playback service.
