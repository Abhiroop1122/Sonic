# Documentation

Everything about Sonic (called Runout before 1.0), grouped by who is looking for it. The
[README](../README.md) is the front door: what the app does, how to install it, and what changed
in each release.

## Using Sonic

| Document | What it covers |
| :--- | :--- |
| [README](../README.md) | The app, the features, and how to install the current release |
| [Downloads](../release/README.md) | Release files, checksums and how to verify an APK |
| [The handbook](HANDBOOK.md) | Installing, setting up, and the original v0.3 design history |
| [Pressing and Friday Drops](PRESSING-AND-DROPS.md) | Claiming weekly drops and the record-pressing model |
| [Record format](RECORD-FORMAT.md) | The `.vinyl` container, record cards in chat, checksums and import rules |
| [Sound and output](SOUND.md) | Pure, Hi-res, ReplayGain, the EQ and the output chain |
| [The turntable](TURNTABLE.md) | The deck, the platter and the player controls |
| [Soulseek](SOULSEEK.md) | The protocol, storage, credentials, messages and privacy |

## Building and developing

| Document | What it covers |
| :--- | :--- |
| [Building](BUILDING.md) | Toolchain, prerequisites, native builds, ABIs and page alignment |
| [Developer notes](DEVELOPMENT.md) | Where things live, decisions and invariants to keep, testing, known gaps |
| [Architecture](ARCHITECTURE.md) | The layers, the data flow and the boundaries between them |
| [Native bridge](../native/README.md) | The JNI adapter and the vendored `soulseek-rs` changes |
| [Contributing](../CONTRIBUTING.md) | Product boundaries and how to propose a change |
| [Release process](../RELEASE.md) | Building, verifying, tagging and publishing a release |
| [Changelog](../CHANGELOG.md) | Every notable change, per version |
| [Security](../.github/SECURITY.md) | What is sensitive and how to report a vulnerability |
| [Code of conduct](../CODE_OF_CONDUCT.md) | How we expect contributors to treat each other |

## Testing

| Document | What it covers |
| :--- | :--- |
| [Argent flows](../.argent/README.md) | Saved Android flows for the manual UI checks |
| [CI workflow template](../.github/workflows-templates/README.md) | The inactive GitHub Actions workflow and how to enable it |

## Design and history

These are working records from before 1.0, kept as written. They use the old name, Runout.

| Document | What it covers |
| :--- | :--- |
| [TE interview](design/TE-INTERVIEW.md) | The screen-by-screen interview behind the current Teenage Engineering-style design |
| [Design checklist](design/UI-REDESIGN.md) | The earlier redesign interview and the status of every change |
| [RG-1 deck v3](design/deck-v3-CHANGES.md) | The current 3D deck model and its colourways (the neon one is built by `scripts/deck-v3/neon.py`) |
| [RG-1 deck v2](design/deck-v2-CHANGES.md) | The previous deck model |
| [pen.dev prompt](design/PEN-DEV-PROMPT.md) | The brief given for the design enhancement pass |

The design file itself is `design/runout-te.pen`, which opens in pen.dev. Its screens are the `TE · …` frames in the light theme, a `TE · 1.2 · …` row for the neon deck, vinyl only, Flags and the Bluetooth codec, and below them a `TE · Night · …` copy of every screen in the dark theme.

## Repository map

```text
app/      the Android application, its layers and its tests
native/   the Rust JNI bridge and the vendored Soulseek library
docs/     this documentation
scripts/  build and check helpers, and the deck model scripts
release/  release notes, checksums and verification reports
.argent/  saved Android UI flows
.github/  issue and pull request templates, security policy
```

## Conventions

- Documentation is one file per subject, in the present tense, written for the person who will
  read it next.
- Behaviour changes come with a changelog entry and, where they can be observed, a test.
- The [handbook](HANDBOOK.md) and [pressing guide](PRESSING-AND-DROPS.md) began as v0.3 design
  history. Where they mention Nostr, multi-format audio or accounts, the current documentation
  above supersedes them.
