# Deploy and test

How to run the server where your devices can reach it, and how to get Messenger onto Android phones, Macs and iPhones. An iPhone needs only a free Apple ID.

## 1. The server

Every device talks to one server. An account belongs to the server it signed up on, so settle on the address before your partner and friends sign up.

| Where the server runs | Good for | Server address in the apps |
|---|---|---|
| Your Mac, same Wi‑Fi | trying it out at home | `http://<your Mac's IP>:8080` |
| Your Mac, over [Tailscale](https://tailscale.com) | phones anywhere, nothing public | `http://<your Mac's Tailscale name>:8080` |
| A small VPS with Docker | everyday use | `https://chat.example.com` |

Messages are end-to-end encrypted in every case. Plain `http` is fine on your own network or a tailnet; on the open internet, use https so the network can't see usernames and timing.

### On your Mac

```sh
cargo run --release -p messenger-server -- --listen 0.0.0.0:8080
```

- The server keeps its data in `messenger-server.db`, in the folder you start it from.
- Your Mac's address on the Wi‑Fi: `ipconfig getifaddr en0`. If macOS asks whether `messenger-server` may accept incoming connections, allow it.
- The Android emulator reaches your Mac at `http://10.0.2.2:8080`, and the iPhone simulator at `http://localhost:8080`.
- With Tailscale on the Mac and the phones, use the Mac's name from the Tailscale menu (or its `100.x.y.z` address). It works away from home too, and Tailscale encrypts the traffic.

### On a VPS

You need a server with Docker and a domain name whose DNS points at it. Then:

```sh
git clone https://github.com/ethchor/messenger.git && cd messenger
DOMAIN=chat.example.com PUSH_CONTACT=mailto:you@example.com \
  docker compose -f infra/docker-compose.yml up -d
```

`PUSH_CONTACT` is passed on to push services, so they can reach whoever runs the server.

Caddy fetches the TLS certificate for you; open ports 80 and 443. To update, `git pull` and run the same command with `--build`. The database lives in the `server-data` volume. It holds usernames, public keys and ciphertext; if you lose it, everyone signs up again.

Notifications need nothing more for Android. For iPhones and Macs, see [Notifications in the README](../README.md#notifications).

## 2. Android

### Get an APK

- **From GitHub:** Actions → **Release builds** → **Run workflow**. When it's done, download `android-apk` from the run's page. Pushing a tag like `v0.1.1` also makes a draft release with the APK and the Mac disk image attached.
- **Build it yourself** (Android Studio installs the SDK; you also need the NDK and [Rust](https://rustup.rs)):

  ```sh
  cd android && ./gradlew :app:assembleRelease
  # → android/app/build/outputs/apk/release/app-release.apk
  ```

### Install it

- **With a cable:** turn on Developer options and USB debugging on the phone, then `adb install -r Messenger.apk`.
- **Without one:** get the APK onto the phone any way you like, open it, and allow installing apps from wherever you opened it. If Play Protect warns about an unknown app, choose to install anyway.

### Your own signing key (do this once)

Android installs an update only if it's signed with the same key as the app already there. Without your own key, every build from GitHub gets a new throwaway key, so each update means uninstalling first, and that erases the messages on the phone.

```sh
keytool -genkeypair -v -keystore messenger-release.jks -alias messenger \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` file and its passwords somewhere safe, like your password manager. If you lose them, every phone has to uninstall and reinstall.

For builds on your Mac, create `android/keystore.properties` (git ignores it):

```properties
storeFile=/Users/you/keys/messenger-release.jks
storePassword=…
keyAlias=messenger
keyPassword=…
```

For builds on GitHub, add the same as secrets:

```sh
base64 -i messenger-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_STORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body messenger
gh secret set ANDROID_KEY_PASSWORD
```

Android Studio signs what it runs on the emulator with your Mac's debug key, so uninstall that copy before installing a release APK on the same device.

### Notifications

Install [ntfy](https://ntfy.sh) from F-Droid or its website. It delivers pushes for Messenger without Google services. Messenger's Settings → Notifications then says **Instant**.

## 3. Mac

### Get a disk image

```sh
scripts/make-dmg.sh
# → build/Messenger-0.1.0.dmg, for Apple silicon and Intel
```

Or download `mac-dmg` from the **Release builds** workflow on GitHub.

### Install it

Open the disk image and drag Messenger onto Applications.

- A copy you built on this Mac opens normally.
- A downloaded copy isn't notarized (that takes a paid Apple developer account), so macOS won't open it at first. Try to open it once, then go to System Settings → Privacy & Security, scroll down and click **Open Anyway**.
- Without a team, each new build is signed differently, so macOS asks for location and notification access again. To stop that, sign with your team: set `MESSENGER_MAC_SIGN_IDENTITY = Apple Development` in your `Signing.local.xcconfig` (see step 2 of the iPhone setup).

## 4. iPhone with a free Apple ID

You need a Mac with Xcode and your Apple ID, and the iPhone on the same Wi‑Fi (or a cable). No paid developer account. These steps follow Xcode 27; older versions call Device Hub "Devices and Simulators".

1. **Add your Apple ID to Xcode.** Xcode → Settings → Apple Accounts → **+**. A team called "*Your Name* (Personal Team)" appears.

2. **Set your team and an app id of your own.** Copy the example settings (git ignores the copy):

   ```sh
   cp apple/Config/Signing.local.xcconfig.example apple/Config/Signing.local.xcconfig
   ```

   In the copy, set `MESSENGER_BUNDLE_ID` to something only you use, like `com.yourname.messenger`; a free team can't register an id another team already has. Set `DEVELOPMENT_TEAM` to your team's 10-character ID. To find it, make a certificate once (Xcode → Settings → Apple Accounts → your Apple ID → **Manage Certificates…** → **+** → Apple Development), then run:

   ```sh
   security find-certificate -c "Apple Development" -p | openssl x509 -noout -subject
   ```

   The ID is the `OU=` value. Close and reopen the project so Xcode reads the file.

   You can pick the team from the Team menu under Signing & Capabilities instead, for both the Messenger and NotificationService targets; Xcode then saves it in the project file, so keep that change out of your commits. Either way, set the bundle id only in `Signing.local.xcconfig`, not in Xcode's Set Up Signing sheet: the notification extension's id is built from it.

3. **Pair the iPhone.** In Xcode, open the run destination menu → **Manage Devices…**, which opens Device Hub.
   - On iOS 27 or later: click **+** → **Pair Nearby Device…** and follow the steps.
   - Otherwise, connect a cable, tap **Trust** on the iPhone, select it in Device Hub, and click **Pair**.

   If the iPhone asks for Developer Mode: Settings → Privacy & Security → Developer Mode → on. It restarts and asks you to confirm. Once paired, the iPhone runs apps from Xcode over Wi‑Fi, without the cable.

4. **Run it.** Choose your iPhone as the run destination and press ▶. The first build takes a few minutes while the Rust core compiles for iOS.

5. **Trust your certificate on the phone.** The first launch stops with "Untrusted Developer". On the iPhone: Settings → General → VPN & Device Management → your Apple ID → **Trust**. Press ▶ again.

6. **Sign up against your server.** Enter your server address (from section 1) on the sign-up screen, and allow local network access when asked.

Your partner's or a friend's iPhone works the same way: pair it with your Mac and press ▶. It's signed with your Apple ID.

**What a free Apple ID can't do:**

- **The app stops opening after 7 days.** Press ▶ in Xcode again to renew it. Your account and messages stay, as long as the bundle id doesn't change.
- **At most 3 apps** from free teams on one phone, and **10 new app ids a week**.
- **No Apple push.** Notifications come while Messenger is open or sharing its location, and when iOS lets it refresh in the background, which it does on its own schedule. The Mac app shows notifications whenever it's running.

A paid Apple developer account removes all three, and adds TestFlight for sending builds to friends.

## 5. A first test

1. Start the server, and sign up on two devices with different usernames. Write each recovery key down.
2. Start a chat by username and send a few messages both ways.
3. Open the chat's info and compare safety numbers in person.
4. Put one app in the background, then send it a message from the other one. A notification should appear.

## 6. When something's off

| You see | Try |
|---|---|
| "Offline. Retrying…" | Check the server address, that the server is running, and that the phone is on the same network or tailnet. On a Mac server, allow incoming connections in System Settings → Network → Firewall. |
| Xcode: "Failed to register bundle identifier" | Another team has that id. Pick a different `MESSENGER_BUNDLE_ID`. |
| iPhone: "Untrusted Developer" | Step 5 of the iPhone setup. |
| iPhone: the app won't open after a week | The free 7-day signature ran out. Press ▶ in Xcode again. |
| Android: "App not installed" when updating | The new APK is signed with a different key. Set up your own key (section 2), or uninstall the old copy first. |
| Mac: "Apple could not verify Messenger" | System Settings → Privacy & Security → **Open Anyway**. |
