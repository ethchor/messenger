# Handoff

Where the work stands (26 October 2026), the rules for working on it, and what comes next. Read this first when picking the project up; [ARCHITECTURE.md](ARCHITECTURE.md) explains how things work, and [DEPLOY.md](DEPLOY.md) how to run them.

## What this is

The most secure messenger we can build, for Vimu, his partner and their friends, with a work section later. Every chat is an MLS group on the X-Wing post-quantum ciphersuite, and the server is trusted with nothing: it relays ciphertext and can't link mailboxes together. Apps for iPhone, Mac and Android sit on one Rust core.

## Working rules

- **Commits.** Commit as you go, authored as Vimu Kale (GitHub `ethchor`):

  ```sh
  git -c user.name="Vimu Kale" -c user.email="ethchor@gmail.com" commit -m "…"
  ```

  No `Co-Authored-By`, "Generated with …" or other tool lines in commit messages or pull request descriptions, ever.
- **Branches.** Real feature names that fit the work, such as `feature/wallpapers`. Never branches named after a tool.
- **Pull requests.** New work branches from `main`. A branch that needs work not merged yet starts from that branch instead, and its PR targets it; merge such a stack from the bottom up, retargeting each next PR to `main` (`main` keeps no branch protection, and merged branches aren't deleted, so GitHub doesn't retarget by itself).
- **Testing.** Vimu tries the apps himself: the iPhone app runs from his Xcode on the iPhone 17 Pro simulator (not from a separate simulator panel), the Mac app from Xcode or a local build. Both talk to a local server, `cargo run -q -p messenger-server` in the repo root, whose database and `blobs/` git ignores. This Mac has no Android SDK, so CI is the compile check for Kotlin.
- **Before pushing:**

  ```sh
  cargo fmt --all
  cargo clippy --workspace --all-targets -- -D warnings
  cargo test --workspace
  scripts/bindings.sh            # after any change to crates/ffi; CI runs --check
  scripts/artwork.py             # after changing design/artwork or design/avatars; needs `pip install svgelements`
  ```

- **Style.** Docs, comments and app text are plain and short. Comments say why, not what. App text talks to people ("This file is no longer on the server."), not about systems.

## The first pull requests

All on `github.com/ethchor/messenger` (private), and all merged into `main` on 26 October 2026, from the bottom up with merge commits. Their branches are kept.

| PR | Branch | Based on | What |
|---|---|---|---|
| #1 | `feature/mailbox-server` | `main` | Mailbox server |
| #2 | `feature/client-core` | `feature/mailbox-server` | Client core (MLS, store, outbox, sync) and CLI |
| #3 | `feature/native-apps` | `feature/client-core` | iPhone, Mac and Android apps |
| #4 | `feature/settings` | `feature/native-apps` | Settings: account, privacy, security, app lock |
| #5 | `feature/panic-wipe` | `feature/settings` | Panic wipe |
| #6 | `feature/location-sharing` | `feature/panic-wipe` | Live location sharing |
| #7 | `feature/push-notifications` | `feature/location-sharing` | Push notifications (not yet tried on devices) |
| #8 | `feature/deploy-guide` | `feature/push-notifications` | Deploy guide, signing, release builds |
| #9 | `feature/appearance` | `feature/deploy-guide` | Drawn avatars, inks, chat paper, helper cards, the Q icon |
| #10 | `feature/threads-and-emoji` | `feature/appearance` | Threads, and reactions with any emoji |
| #11 | `feature/media` | `feature/threads-and-emoji` | Photos, videos, voice messages and files |
| #12 | `feature/readme` | `feature/media` | The README rewritten, with Mermaid diagrams of the architecture |

CI (`.github/workflows/ci.yml`) runs on every pull request: Rust (fmt, clippy, tests, the kill test, bindings and artwork checks) and, when the change touches them, an iOS Simulator and macOS build and an Android debug APK. Compiler errors become annotations on the PR (`scripts/ci-annotate.sh`); `gh api repos/ethchor/messenger/check-runs/<id>/annotations` reads them without the log.

## Done

Features, per the README's status list: registration with a recovery key and recovery onto a new device; 1:1 and group chats with replies, threads, reactions with any emoji, edits and delete-for-everyone; photos, videos, voice messages and files; adding, removing and leaving; read receipts and typing, each switchable; live location with a map; notifications that carry nothing readable; deleting the account or removing it from a device; panic wipe; app lock, app-switcher hiding and screenshot blocking; safety numbers with QR codes; crash recovery at every step of a commit.

Design assets, all generated from sources in `design/`:

- **App icon:** the Q, an off-white ring with a coral tail on near-black (`design/artwork/mark.svg`). `scripts/app-icons.py` draws every size: iOS light, dark and tinted, the Mac's squircle on Apple's 824/1024 grid, Android adaptive and monochrome icons and the notification icon.
- **Avatars:** 30 animals in one continuous line each, Signal-style (`design/avatars/*.svg`, in the order of `design/avatars/order.txt`). `scripts/artwork.py` turns them into `Avatars.swift` and `Avatars.kt`; the apps draw them on a pastel of the person's ink, clipped to a circle.
- **Line drawings:** helper-card illustrations and the doodle paper (`design/artwork/*.svg`), generated into `Artwork.swift` and `Artwork.kt` by the same script.
- **Emoji:** `scripts/emoji.py` builds `emoji.tsv` (in the Apple Resources and Android assets) from emojibase-data 17.0.0 (MIT), up to Emoji 15.0, with skin tones.

## Media (`feature/media`, PR #11)

Files are encrypted and kept on the server as blobs (ARCHITECTURE.md, "Files"). In the apps:

- **Attaching:** a paper clip in the chat and thread composers (photos and videos, the camera on phones, any file); what's picked waits above the text box, which becomes the caption. Holding the microphone records a voice message; sliding left cancels.
- **Preparing** strips metadata: photos re-encoded to JPEG (2048 px, no EXIF), videos re-encoded at 720p. Android's videos go through Media3 and then `Mp4Scrubber`, which blanks `udta`, `meta` and `uuid` boxes and creation times in place.
- **Showing:** a grid of photos and videos in the bubble ("+N" after four), voice messages with a waveform and seeking, files with name and size; a full-screen viewer with zoom, video and sharing (and Save on the Mac). Photos and voice messages are fetched as they arrive, videos and files on a tap.
- **Sending** runs in the background: `sendPending` after sending, at launch and when a sync reports unsent files, with background time on iPhone and a WorkManager job on Android.
- On Android, pickers and the camera are registered on `MainActivity` and their answers go to the view model, which keeps each chat's tray: with app lock set to "immediately", the lock screen replaces the chat screen while a picker is open.

## Next, in order

1. **Colourful illustrations.** Vimu's request: the helper and empty-state drawings (`design/artwork/illustration-*.svg`) are one-ink line art, and the welcome screen's tin-can telephone with a heart is too cheesy. Redraw them in colour in the avatars' style (flat fills with a dark outline; the animals can star in them), extend `scripts/artwork.py` to generate coloured artwork as it does the avatars, and draw it in `LineArt`'s place on both platforms. Its own branch from `main`.
2. **Nature doodle wallpapers.** Vimu's request, taking WhatsApp's doodle wallpaper as the model and drawing on the avatars:
   - Each wallpaper is a repeating doodle pattern that tells a little story. There should be about 30, all scenes from nature: forest plants with big leaves, mountains, balanced stones, a valley of flowers, a jungle with animals, elephants, and so on.
   - Plan: draw each as a seamless square tile in `design/wallpapers/*.svg`, in the avatars' continuous-line style. Anything that crosses an edge must reappear on the opposite edge.
   - Extend `scripts/artwork.py` to generate them into Swift and Kotlin, as it does the avatars.
   - Draw them like the existing `Paper` cases (`PaperView` on Apple, `PaperBackground` on Android): tinted to the chat's ink, at low contrast, behind opaque bubbles.
   - Add them to the paper picker in Appearance settings, with previews, for all chats and for one chat. `Paper` is stored by its raw id, so add new cases rather than renumbering.
3. **Offline chats over Bluetooth mesh**, like bitchat: messages relayed phone to phone with no internet, still MLS end to end. Not designed yet; start with a design note in ARCHITECTURE.md.
4. **The first manual run of the release workflow:** Actions → "Release builds" → Run workflow. It builds an APK and a Mac disk image. The APK is signed with Vimu's own key once the four `ANDROID_*` secrets are set, and with a throwaway key until then; see DEPLOY.md.
5. **The rest of the README's "Not done" list:**
   - SQLCipher, with keys held by the Secure Enclave and the Android Keystore;
   - the iPhone notification extension reading the database through an App Group;
   - calls;
   - key transparency, Privacy Pass, an OHTTP relay, and an external audit.
6. **A full check of the work**, and testing on phones, when Vimu asks.

## Things learned the hard way

- `ureq`'s body limit fails when the body is exactly the limit. The core reads with `limit(max + 1)` and checks the length itself.
- `svgelements` drops an element's transform unless its path is taken as `abs(Path(shape))`. `scripts/artwork.py` does this.
- Kotlin: `ExperimentalLayoutApi` is in `androidx.compose.foundation.layout`.
- Nothing compiles Kotlin on Vimu's Mac, so check that an Android library call exists in the version `libs.versions.toml` pins before using it. Media3 is at 1.5.1, and `Presentation.createForShortSide` only arrived in 1.8.
- The client database migrates with `PRAGMA user_version` (6: threads, 7: attachments). The server's schema is `CREATE TABLE IF NOT EXISTS` with no migrations yet, so a changed server table needs a migration added first.
- From a sandbox where GitHub's GraphQL API and log downloads are blocked, `gh api` (REST) still works for creating and editing pull requests, and check-run annotations stand in for logs.
- Unoptimised ML-KEM key generation overflows the 512 KiB stacks of the threads the apps call the core from (an iPhone debug build crashed signing up). Debug builds optimise dependencies (`[profile.dev.package."*"]` in Cargo.toml); keep it.
- macOS 27: a scroll view's `defaultScrollAnchor(.bottom)` aligns short content by adding a top inset, which the toolbar's scroll edge effect then covers, hiding messages. `MessageScroll` makes the list at least as tall as the scroll view instead.
- macOS: a Text with `fixedSize(horizontal: false, vertical: true)` outside a list makes the window's smallest height huge, since it's measured at a tiny width, and the window grows past the screen. A row of a Mac sidebar list doesn't grow for wrapped text either.
- Xcode 27 has no Simulator.app; Device Hub shows the simulators.
