# Messenger

An end-to-end encrypted messenger for a small circle: you, your partner, your friends, and later a work space. Every conversation is an MLS group (RFC 9420) on the X-Wing hybrid post-quantum ciphersuite. The server only relays ciphertext between mailboxes it can't link together.

One Rust core runs on every platform. Native apps sit on top of it: SwiftUI for iPhone and Mac, Jetpack Compose for Android.

| | |
|---|---|
| `crates/protocol` | Wire formats: HTTP API types, signing payloads, content protobufs, Padmé padding |
| `crates/core` | Client core: MLS (OpenMLS + libcrux), SQLite store, outbox, sync, crash recovery |
| `crates/ffi` | UniFFI bindings, the only API the apps see |
| `crates/server` | Mailbox server: accounts, key package directory, ordered mailboxes |
| `crates/cli` | Command-line client for development and tests |
| `apple/` | Xcode project: one SwiftUI app for iPhone and Mac, plus a notification extension |
| `android/` | Gradle project: Compose app and the core as an Android library |
| `proto/` | Versioned formats that travel inside MLS |
| `docs/` | Architecture, and how to deploy and test |

## Run it

You need Rust from [rustup.rs](https://rustup.rs). To put the server on the internet, build an APK or a Mac disk image, or run on an iPhone with a free Apple ID, see [docs/DEPLOY.md](docs/DEPLOY.md).

```sh
cargo run -p messenger-server -- --listen 0.0.0.0:8080   # the server
cargo test --workspace                                    # unit + end-to-end tests
scripts/kill-test.sh                                      # crash a client mid-commit, check it recovers
```

Two terminals chatting through the CLI:

```sh
cargo run -q -p messenger-cli -- --data /tmp/alice register alice
cargo run -q -p messenger-cli -- --data /tmp/bob register bob
cargo run -q -p messenger-cli -- --data /tmp/alice chat bob
cargo run -q -p messenger-cli -- --data /tmp/alice send bob "hi bob"
cargo run -q -p messenger-cli -- --data /tmp/bob watch
```

### iPhone and Mac

Open `apple/Messenger.xcodeproj`, pick a simulator or My Mac, and press Run. A build phase compiles the Rust core for the selected platform and adds any missing rustup targets. The first build takes several minutes; after that it's incremental.

- The simulator and the Mac app reach a server on the same Mac at `http://localhost:8080`.
- To run on your iPhone, even with a free Apple ID, follow [docs/DEPLOY.md](docs/DEPLOY.md#4-iphone-with-a-free-apple-id). In the app, use `http://<your-mac's-LAN-IP>:8080` as the server.

### Android

Open `android/` in Android Studio and run the `app` configuration. Gradle builds the Rust core with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk); `scripts/android-build-rust.sh` installs the rustup targets and cargo-ndk on first use. You need an NDK (Settings → Android SDK → SDK Tools → NDK). The emulator reaches a server on the host at `http://10.0.2.2:8080`.

Running from Android Studio compiles the core only for the ABI of the device you deploy to. Command-line and CI builds compile both arm64-v8a and x86_64.

### Notifications

Pushes say only "wake up"; each app decrypts and writes its own notifications.

- Android uses UnifiedPush, so it works without Google services. Install a distributor app such as [ntfy](https://ntfy.sh) (F-Droid or its website), then check Settings → Notifications in Messenger. The server needs nothing extra. For a push service on your own network, start the server with `--allow-private-push`.
- iPhone and Mac need Apple's push service, which takes a paid Apple developer account: add the Push Notifications capability under Signing & Capabilities, and start the server with your APNs key:

  ```sh
  cargo run -p messenger-server -- --listen 0.0.0.0:8080 \
    --apns-key AuthKey_KEYID.p8 --apns-key-id KEYID --apns-team-id TEAMID \
    --apns-topic <your app's bundle id>
  ```

  Without it, the Mac shows notifications while Messenger runs, and the iPhone while Messenger is open, while it shares your location, and when iOS lets it refresh in the background.
- `--push-contact mailto:you@example.com` tells push services who runs the server.

### Bindings

The Swift and Kotlin bindings are generated from `crates/ffi` and committed, so the IDEs build without a code-generation step. After changing the FFI API, run `scripts/bindings.sh`. CI runs `scripts/bindings.sh --check`.

## Status

This is Phase 0 plus the v0.1 app shell. Working today:

- registration with a recovery key, recovery onto a new device, and replacing a lost recovery key;
- 1:1 and group chats, with replies, reactions, edits and delete-for-everyone;
- adding and removing members, leaving a group or ending a 1:1 chat, with concurrent changes resolved by the server's ordering rule;
- read receipts and typing indicators, each of which can be turned off (both ways);
- live location sharing with a map, for an hour, the day, or until stopped; end-to-end encrypted like messages;
- notifications from pushes that carry nothing readable: UnifiedPush on Android, Apple's push service on iPhone and Mac with a paid developer account, and local notifications while the app runs;
- deleting the account, or removing it from a device;
- panic wipe: triple-tap “Chats” (⌥⇧⌘⌫ on the Mac) to erase the device at once, with no network;
- app lock with Face ID, Touch ID, fingerprint or the device passcode; hiding the app in the app switcher; blocking screenshots on Android and screen capture on the Mac;
- safety numbers with QR codes;
- crash recovery at every step of a commit.

Not done yet, in roughly this order:

- SQLCipher and keys wrapped by the Secure Enclave / Android Keystore. **Today the local database is plain SQLite with keys inside**, excluded from backups.
- Message text in notifications on an iPhone where Messenger isn't running: the notification extension has to open the database through an App Group. Until then such a push shows "New message".
- Media, voice notes and calls.
- Key transparency, Privacy Pass rate limiting, an OHTTP relay, and an external audit.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works and what the server can and can't see.
