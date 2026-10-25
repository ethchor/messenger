# Handoff

Where the work stands (25 October 2026), the rules for working on it, and what comes next. Read this first when picking the project up; [ARCHITECTURE.md](ARCHITECTURE.md) explains how things work, and [DEPLOY.md](DEPLOY.md) how to run them.

## What this is

The most secure messenger we can build, for Vimu, his partner and their friends, with a work section later. Every chat is an MLS group on the X-Wing post-quantum ciphersuite, and the server is trusted with nothing: it relays ciphertext and can't link mailboxes together. Apps for iPhone, Mac and Android sit on one Rust core.

## Working rules

- **Commits.** Commit as you go, authored as Vimu Kale (GitHub `ethchor`):

  ```sh
  git -c user.name="Vimu Kale" -c user.email="ethchor@gmail.com" commit -m "…"
  ```

  No `Co-Authored-By`, "Generated with …" or other tool lines in commit messages or pull request descriptions, ever.
- **Branches.** Real feature names that fit the work, such as `feature/wallpapers`. Never branches named after a tool.
- **Pull requests are stacked.** Each feature branch starts from the one before it, and its PR targets that branch (table below). Merge from the bottom up: #1 into `main`, then retarget #2 to `main`, and so on.
- **Build code; test later.** For now, don't start simulators or emulators. CI is the compile check for Swift and Kotlin; testing on devices comes when Vimu asks for it.
- **Before pushing:**

  ```sh
  cargo fmt --all
  cargo clippy --workspace --all-targets -- -D warnings
  cargo test --workspace
  scripts/bindings.sh            # after any change to crates/ffi; CI runs --check
  scripts/artwork.py             # after changing design/artwork or design/avatars; needs `pip install svgelements`
  ```

- **Style.** Docs, comments and app text are plain and short. Comments say why, not what. App text talks to people ("This file is no longer on the server."), not about systems.

## The pull request stack

All on `github.com/ethchor/messenger` (private). Every PR is open; none is merged yet.

| PR | Branch | Based on | What |
|---|---|---|---|
| #1 | `feature/mailbox-server` | `main` | Mailbox server |
| #2 | `feature/client-core` | `feature/mailbox-server` | Client core (MLS, store, outbox, sync) and CLI |
| #3 | `feature/native-apps` | `feature/client-core` | iPhone, Mac and Android apps |
| #4 | `feature/settings` | `feature/native-apps` | Settings: account, privacy, security, app lock |
| #5 | `feature/panic-wipe` | `feature/settings` | Panic wipe |
| #6 | `feature/location-sharing` | `feature/panic-wipe` | Live location sharing |
| #7 | `feature/push-notifications` | `feature/location-sharing` | Push notifications (draft) |
| #8 | `feature/deploy-guide` | `feature/push-notifications` | Deploy guide, signing, release builds |
| #9 | `feature/appearance` | `feature/deploy-guide` | Drawn avatars, inks, chat paper, helper cards, the Q icon |
| #10 | `feature/threads-and-emoji` | `feature/appearance` | Threads, and reactions with any emoji |
| #11 | `feature/media` | `feature/threads-and-emoji` | Photos, videos, voice notes and files (draft, in progress) |

CI (`.github/workflows/ci.yml`) runs on every pull request: Rust (fmt, clippy, tests, the kill test, bindings and artwork checks) and, when the change touches them, an iOS Simulator and macOS build and an Android debug APK. Compiler errors become annotations on the PR (`scripts/ci-annotate.sh`); `gh api repos/ethchor/messenger/check-runs/<id>/annotations` reads them without the log.

## Done

Features, per the README's status list: registration with a recovery key and recovery onto a new device; 1:1 and group chats with replies, threads, reactions with any emoji, edits and delete-for-everyone; adding, removing and leaving; read receipts and typing, each switchable; live location with a map; notifications that carry nothing readable; deleting the account or removing it from a device; panic wipe; app lock, app-switcher hiding and screenshot blocking; safety numbers with QR codes; crash recovery at every step of a commit.

Design assets, all generated from sources in `design/`:

- **App icon:** the Q, an off-white ring with a coral tail on near-black (`design/artwork/mark.svg`). `scripts/app-icons.py` draws every size: iOS light, dark and tinted, the Mac's squircle on Apple's 824/1024 grid, Android adaptive and monochrome icons and the notification icon.
- **Avatars:** 30 animals in one continuous line each, Signal-style (`design/avatars/*.svg`, in the order of `design/avatars/order.txt`). `scripts/artwork.py` turns them into `Avatars.swift` and `Avatars.kt`; the apps draw them on a pastel of the person's ink, clipped to a circle.
- **Line drawings:** helper-card illustrations and the doodle paper (`design/artwork/*.svg`), generated into `Artwork.swift` and `Artwork.kt` by the same script.
- **Emoji:** `scripts/emoji.py` builds `emoji.tsv` (in the Apple Resources and Android assets) from emojibase-data 17.0.0 (MIT), up to Emoji 15.0, with skin tones.

## In progress: media (`feature/media`, PR #11)

### Done

- **Format and server.** Files are padded with Padmé and sealed in 64 KiB ChaCha20-Poly1305 chunks under a per-file key, with the SHA-256 of the ciphertext checked before decrypting (ARCHITECTURE.md, "Files"). The server has `POST /v1/blobs` (signed; size and digest in, upload id out; checks size and the daily quota), `PUT /v1/blobs/{id}` (the upload, streamed, checked against size and digest) and `GET /v1/blobs/{id}` (no sign-in; the id is the capability). Flags: `--blobs` (folder), `--max-file-mb` (100), `--daily-upload-mb` (2048). Blobs expire with message retention, abandoned uploads after an hour, and deleting an account deletes its blobs.
- **Core** (`crates/core/src/media.rs`):
  - `send_media(conversation, caption, attachments, reply_to, in_thread)` takes files the app prepared, moves them into `media/` and shows the message as sending.
  - `send_pending()` encrypts, uploads and then sends the message. A refused file (400, 413, 429) fails its message; network errors leave it for the next call.
  - `download_attachment(conversation, message, position)` fetches, checks and decrypts a file, and returns its path. It's safe to call from several threads.
  - Client database migration 7 adds the `attachments` table.
  - Views: `MessageView.attachments` (`AttachmentView` with kind, type, name, size, dimensions, duration, thumbnail, waveform, state, path, error) and `SyncReport.unsent_media`. Chat-list previews and notifications say "Photo", "2 photos", "Voice message", or the file's name, for messages without a caption.
- **FFI and bindings.** The FFI exposes the same API, and the Swift and Kotlin bindings are regenerated. The apps don't use it yet.
- **CLI.** `send-file <chat> <files…> [--caption] [--thread]` sends files. `download <chat> <message> [--position] [--to]` fetches one. `show` lists a message's files, and `sync` and `watch` retry uploads.
- **Tests.** Unit tests cover the format (every size round-trips, the largest file fits, tampering fails, file names). End-to-end tests cover files arriving whole, damaged or missing files being refused, files going with their message, and refused files failing their message.

### Left to do

**iPhone and Mac** (iOS 17, macOS 14):

1. **Attaching:**
   - an attach menu in the chat and thread composers: Photos (`PhotosPicker`, up to 10, images and videos), Camera on iPhone (a small `UIImagePickerController` wrapper), and Files (`.fileImporter`, any type);
   - hold-to-record voice notes.
2. **Preparing, which strips metadata:**
   - Photos are re-encoded with `CGImageSourceCreateThumbnailAtIndex` (with the transform, long side at most 2048 px) and then `CGImageDestination` to JPEG at about 0.8 quality, adding no properties, so no EXIF or location. GIFs go as they are.
   - Videos go through `AVAssetExportSession` with the 1280×720 preset to `.mp4`, with `metadata = []` and `metadataItemFilter = .forSharing()`. If the result is over 96 MB, say it's too long to send.
   - Thumbnails are JPEGs of at most 8 KiB: start near 96 px on the long side at quality 0.6, and shrink until one fits.
   - Fill in width, height and duration.
3. **Voice notes:**
   - Record with `AVAudioRecorder`, AAC mono `.m4a` (`audio/mp4`), with metering about every 50 ms, turned into at most 256 waveform steps (0–255).
   - Add `INFOPLIST_KEY_NSMicrophoneUsageDescription` and `INFOPLIST_KEY_NSCameraUsageDescription` to the build settings in `project.pbxproj`, and `com.apple.security.device.audio-input` to `Config/Messenger-macOS.entitlements`.
4. **Sending:**
   - Write the prepared file to a temporary folder, call `sendMedia` (the core moves the file), then `sendPending()` off the main thread.
   - Also call `sendPending()` at launch and after any sync whose report has `unsentMedia > 0`. Use `beginBackgroundTask` so an upload can finish when the app is sent to the background.
5. **Showing:**
   - Photos and videos in a grid in the bubble: one large; two side by side; three or more in two columns, with "+N" after four. The caption goes under it.
   - Voice notes as a row: play/pause, the waveform with progress, and the duration (`AVAudioPlayer`).
   - Files as a row: icon, name, size and state.
   - Until a file is here, show its blurred thumbnail with a download button or progress; a failed file shows its error.
6. **Fetching:** download photos and voice notes as soon as they arrive (in the background, with `downloadAttachment`); videos and files on tap.
7. **Viewer:** photos full screen with zoom, video with AVKit's `VideoPlayer`, files with `.quickLookPreview`, and `ShareLink` to save or share.

**Android** (minSdk 26, compileSdk 35, Kotlin 2.0.21, AGP 8.7.3; pick library versions that still work with Kotlin 2.0, as the notes in `gradle/libs.versions.toml` say):

1. **Attaching:**
   - photos and videos with `PickMultipleVisualMedia(10)`, files with `OpenMultipleDocuments`;
   - the camera through `TakePicture` and `CaptureVideo`, writing to a `FileProvider` URI (add the provider to the manifest);
   - voice notes with `MediaRecorder` (AAC in MPEG-4), which needs the `RECORD_AUDIO` permission asked for at runtime.
2. **Preparing:**
   - Photos: `ImageDecoder` on API 28 and later, and `BitmapFactory` with `ExifInterface` orientation below that. Scale to at most 2048 px and save with `Bitmap.compress(JPEG, 80)`, which writes no EXIF.
   - Videos: Media3 Transformer to H.264 with the short side at most 720 px, and AAC audio. Then blank the metadata in the MP4 itself: rename `udta` and `meta` boxes to `free` and zero their contents, and zero the creation and modification times in `mvhd`, `tkhd` and `mdhd`. Sizes and offsets stay valid, and it works on any MP4, including the original if transcoding fails.
   - Thumbnails and waveforms as on Apple. The waveform comes from `maxAmplitude` about every 50 ms.
3. **Sending and fetching:** the same as Apple, in `AppViewModel`, with WorkManager (already a dependency) running `sendPending` when the app isn't open.
4. **Showing:**
   - Decode images on `Dispatchers.IO` with `inSampleSize`, plus a small `LruCache`; no image library is needed.
   - Video with Media3 ExoPlayer, or `VideoView`; voice notes with `MediaPlayer`.
   - Open and share files through the `FileProvider`, with `ACTION_VIEW` and `ACTION_SEND` and read grants.

**Then:**

- Add the apps' part to ARCHITECTURE.md ("Files": metadata stripping and auto-download) and update the README's status.
- Mark PR #11 ready once CI is green.

## Next, in order

1. **Nature doodle wallpapers.** Vimu's request, taking WhatsApp's doodle wallpaper as the model and drawing on the avatars:
   - Each wallpaper is a repeating doodle pattern that tells a little story. There should be about 30, all scenes from nature: forest plants with big leaves, mountains, balanced stones, a valley of flowers, a jungle with animals, elephants, and so on.
   - Plan: draw each as a seamless square tile in `design/wallpapers/*.svg`, in the avatars' continuous-line style. Anything that crosses an edge must reappear on the opposite edge.
   - Extend `scripts/artwork.py` to generate them into Swift and Kotlin, as it does the avatars.
   - Draw them like the existing `Paper` cases (`PaperView` on Apple, `PaperBackground` on Android): tinted to the chat's ink, at low contrast, behind opaque bubbles.
   - Add them to the paper picker in Appearance settings, with previews, for all chats and for one chat. `Paper` is stored by its raw id, so add new cases rather than renumbering.
2. **Offline chats over Bluetooth mesh**, like bitchat: messages relayed phone to phone with no internet, still MLS end to end. Not designed yet; start with a design note in ARCHITECTURE.md.
3. **The first manual run of the release workflow:** Actions → "Release builds" → Run workflow. It builds an APK and a Mac disk image. The APK is signed with Vimu's own key once the four `ANDROID_*` secrets are set, and with a throwaway key until then; see DEPLOY.md.
4. **The rest of the README's "Not done" list:**
   - SQLCipher, with keys held by the Secure Enclave and the Android Keystore;
   - the iPhone notification extension reading the database through an App Group;
   - calls;
   - key transparency, Privacy Pass, an OHTTP relay, and an external audit.
5. **A full check of the work**, and testing on devices, when Vimu asks.

## Things learned the hard way

- `ureq`'s body limit fails when the body is exactly the limit. The core reads with `limit(max + 1)` and checks the length itself.
- `svgelements` drops an element's transform unless its path is taken as `abs(Path(shape))`. `scripts/artwork.py` does this.
- Kotlin: `ExperimentalLayoutApi` is in `androidx.compose.foundation.layout`.
- The client database migrates with `PRAGMA user_version` (6: threads, 7: attachments). The server's schema is `CREATE TABLE IF NOT EXISTS` with no migrations yet, so a changed server table needs a migration added first.
- From a sandbox where GitHub's GraphQL API and log downloads are blocked, `gh api` (REST) still works for creating and editing pull requests, and check-run annotations stand in for logs.
