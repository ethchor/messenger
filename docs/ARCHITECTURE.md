# Architecture

## Pieces

```
 iPhone / Mac app (SwiftUI)        Android app (Compose)          CLI
            \                            |                         /
             +---------- messenger-ffi (UniFFI) ------------------+
                                     |
                              messenger-core
           OpenMLS (X-Wing suite) · SQLite store · outbox · sync
                                     |  HTTPS, JSON
                              messenger-server
  accounts · key package directory · ordered mailboxes · encrypted files
```

The core owns all client state, including what the screens show (chat list, timelines). The apps render read models from the core and keep no state of their own, so iPhone, Mac and Android behave the same.

## Identity

- An account is a random 128-bit id, a username, and an Ed25519 device key that is also the device's MLS signature key. There are no passwords: authenticated calls are signed by the device key, with a timestamp window and a replay cache.
- At sign-up the app generates a 64-character recovery key and registers an Ed25519 key derived from it. The recovery key can later move the account to a new device. Contacts then see a new safety number, and old history is not restored.
- A lost recovery key can be replaced from Settings. The request is signed by the device key, and by the new recovery key to prove the device holds it. The old key stops working at once.
- Each MLS credential carries `{account_id, username}`. Key packages are checked on upload: the leaf key must be the registered device key, and the credential must name the account. They are checked again by whoever adds the person.

## Conversations are MLS groups

Every conversation is an MLS group, and a 1:1 chat is a two-member group. Settings:

- Ciphersuite `0x004D`: X-Wing (ML-KEM-768 + X25519), ChaCha20-Poly1305, SHA-256, Ed25519, via OpenMLS on libcrux.
- Commits and messages are always encrypted (`PURE_CIPHERTEXT`), so the server can't see who joins or leaves.
- No past-epoch secrets are kept (see ordering below), which gives the best forward secrecy MLS allows.
- Content is protobuf (`proto/messenger/v1`), framed and padded with Padmé before MLS encrypts it.
- Group kind and title live in a private-use group context extension, agreed on through the key schedule.

## Mailboxes and ordering

Each epoch of a group has its own mailbox: a 32-byte id derived from the MLS exporter. Only members can compute it, and it changes with every commit, so the server can't link a group's epochs together. Invitations (Welcomes) go to the invitee's intro inbox.

The server orders messages with one rule: **a post is accepted only if its `prev_commit_seq` names the latest commit already in that mailbox.** So:

- At most one commit wins each epoch. A member whose commit loses gets `409`, discards it, catches up, and retries the add or remove in the next epoch.
- Nothing is accepted from a member who hasn't seen the latest commit. A late application message is refused, then re-encrypted in the new epoch and sent again. No member ever needs old epoch keys.
- An invalid commit is skipped by every honest member. Later posts name it in `prev_commit_seq`, so the group moves on.

Posts are idempotent per client message id, so a retry after a lost response returns the original sequence number.

Every member reads the same mailbox in the same order, so messages are shown in server order `(epoch, seq)`. Everyone sees the same timeline, and unsent messages sit at the end.

## Leaving, and removing an account

- Leaving posts an MLS Remove proposal for ourselves; a member can't commit their own removal. The next member to see it commits it, and everyone shows "alice left". Until then the chat shows as leaving and takes no new messages from us. A 1:1 chat ends when either person leaves.
- **Delete account** leaves every chat, then deletes the account on the server: its key packages, its intro inbox, the files it sent, and the username, which becomes free. **Remove from this device** leaves every chat and withdraws the device's key packages, but keeps the account for the recovery key.
- Both then erase the local database. The server side goes first, so if the server can't be reached nothing changes; if the app stops halfway, the wipe finishes on the next start.
- **Panic wipe** only erases the local database, at once: nothing is sent, so it works with no connection, and neither the server nor contacts notice. The recovery key brings the account back, without the old messages. Until the database is encrypted with a key held by the Keychain or Keystore, erasing relies on the platform's file encryption to make the deleted files unreadable.

## Receipts and typing

Read receipts and typing indicators are application messages, encrypted like any other. Receivers keep typing state in memory only, and it expires after 6 seconds. A typing message that loses a race with a commit is dropped rather than re-sent. Each setting works both ways: with read receipts off, we neither send them nor show other people's.

## Threads and reactions

- A reply in a thread is a text message that names the thread's first message, with a flag saying it belongs in the thread. A client that doesn't know threads shows it as an ordinary reply. Replying to a reply goes under the same first message, so threads never nest.
- Threads keep their replies out of the chat. The message they hang off shows how many replies there are, how many are new, and who wrote the latest ones; opening the thread reads them and sends their read receipts. Opening the chat alone doesn't.
- In a group, a reply in a thread is for the people following it: whoever wrote the first message or has replied. Only they count it as unread, get a notification and are woken by it. In a 1:1 chat both people follow every thread.
- A reaction is one emoji of any kind, flags, skin tones and joined sequences included, at most 64 bytes. Clients drop reactions that are words. The apps' picker offers Unicode's emoji up to version 15.0, which iOS 17 and Android 14 can draw, and remembers the ones used lately and a skin tone, on the device only.

## Files

Photos, videos, voice notes and other files travel outside MLS, as encrypted blobs on the server; the message that carries them holds what's needed to fetch and open them.

- **Format.** The sender pads the file with Padmé, splits it into 64 KiB chunks and seals each with ChaCha20-Poly1305 under a fresh random key per file. Each nonce is the chunk's number plus a flag on the last chunk, so chunks can't be reordered, dropped or cut short without failing to open. The SHA-256 of the whole encrypted file is checked before anything is decrypted.
- **Sending.** The app hands the core a file it prepared. The core moves it into its own folder and shows the message at once, as sending. In the background it encrypts the file, asks the server for an upload id with a signed request naming the size and digest, uploads, and only then sends the message, carrying the blob id, key, digest, size, type, name, dimensions or length, a small thumbnail (at most 8 KiB) and a voice note's waveform. A file the server refuses (too big, or the day's quota is used up) fails its message; network errors leave it for the next try.
- **Receiving.** Thumbnails and waveforms arrive inside the message, so they show before the file does. Fetching needs only the blob id, without signing in: the 256-bit id is the capability, and the server doesn't learn which account fetched it. A file that doesn't match its digest, or has expired, shows as unavailable.
- **On the server.** Files are kept as long as messages (30 days by default) and are then deleted, whether fetched or not. An upload has an hour to arrive after its id is given out. Each account can send files of up to 100 MB, and 2 GB a day; both are settings. Deleting a message for everyone deletes it and its files on every device, but its blob stays on the server, unreadable without the key, until it expires.

## Crash safety

OpenMLS writes group state through a storage provider. The core builds that provider on the connection of an open SQLite transaction, so group state, outbox rows, messages and cursors commit together or not at all. A commit moves through three durable steps:

1. **Created.** The pending commit and its outbox row are saved, with any Welcomes waiting behind it.
2. **Accepted.** The server has given it a sequence number. Waiting Welcomes are released.
3. **Merged.** The client has read the commit back in order and merged it. Only now does it move to the next epoch's mailbox.

A client killed between any two steps resumes from disk. `cargo test` covers this in process, and `scripts/kill-test.sh` covers it with real processes exiting abruptly.

## Location sharing

- A position is an MLS application message in the chat it's shared with: latitude, longitude, accuracy, time of the fix, and optionally speed, direction and battery. The server relays it like any message and learns nothing about where anyone is.
- Sharing only starts on the device of the person being located, and runs for a set time or until they stop. "Stop" is a message too: receivers forget the position at once, as they do when the time runs out or someone leaves the chat.
- Receivers keep only the latest position per person and conversation. There's no history, and positions never appear in the chat. An update that hasn't gone out yet is replaced by a newer one, so a phone coming back online sends only where it is now.
- With the app open, phones send a position when they've moved, plus a heartbeat every 30 s while still, so people can see the position is current. With the app closed they switch to Wi-Fi and cell positions (GPS mostly off), send at most every 30 s while moving, and send a heartbeat every 5 minutes. A Mac sends whenever macOS refreshes its Wi-Fi position, about every 5 minutes while it stays put.
- On iPhone, background sharing uses Core Location's background updates. With "Always" access, iOS may pause them while the phone stands still, and a significant move resumes them. Without it they aren't allowed to pause, since nothing would resume them. On Android, a foreground service with a visible notification and a Stop button keeps sharing going.
- Maps: Apple Maps on iPhone and Mac; MapLibre with OpenStreetMap tiles (OpenFreeMap) on Android. Map tiles are fetched from the map provider, which can see which area is being viewed, though not whose position is shown there. Self-hosted tiles would remove that.

## Notifications

- A push carries nothing. It only wakes the device: the app fetches from its mailboxes, decrypts, and writes the notification itself, so names and text never leave the device. Each message gets one notification, and none once it has been read.
- Who can wake whom: registering a push target gets an account a random wake token from the server. Members share their token inside each chat as an MLS message (again whenever someone joins), and send the others' tokens along with each text message; the server wakes the accounts whose tokens a message names. A reply in a group thread names only the people following the thread. Receipts, typing and location updates name nobody, so they never wake anyone. An invitation to a new chat wakes the person invited.
- The server spaces pushes to one device at least 2 s apart, folding a burst into one push, and stops at 300 an hour. Targets the push service reports gone are deleted.
- Android: UnifiedPush, so no Google services. A distributor app (ntfy, for one) keeps one connection open for all apps and gives Messenger a Web Push endpoint. The server encrypts each push to the device's key (RFC 8291) and signs it with VAPID (RFC 8292). Endpoints have to be https on public addresses, so nobody can point the server at its own network.
- iPhone and Mac: Apple's push service, when the server has an APNs key and the app is signed for push; both take a paid Apple developer account. The alert says only "New message", and the app replaces it with the real notification once it has synced. Without push, the Mac shows notifications while Messenger runs (also with its window closed), and the iPhone while Messenger is open or sharing its location, and whenever iOS lets it refresh in the background.
- Settings can hide names and messages, or turn notifications off, which takes the device's push target and wake token off the server.

## What the server learns

| It sees | It doesn't see |
|---|---|
| usernames, account ids, device and recovery public keys | message content, edits, reactions |
| key packages (public) | who is in which group (unless notifications are on, below), and group names |
| ciphertext sizes (Padmé-padded), timing, and the IP addresses of connections | which mailboxes belong to the same group across epochs |
| how many messages each mailbox holds | which account reads or posts to a mailbox, unless linked by IP and timing |
| which account uploaded a file, when, and its padded size | what the file is, which chat it went to, and which account fetched it |

Fetches are batched over one connection, so the server can link the mailboxes a device polls together. An OHTTP relay and per-mailbox fetching are planned, and so is Privacy Pass, so the server can rate-limit without identifying anyone.

Receipts, typing indicators and shared locations are encrypted, but their timing still shows the server when a mailbox is active; location sharing in particular sends regular updates while it's on.

Files are fetched by id alone, but whoever fetches one does so from an IP address, usually soon after it was sent. Timing and addresses can link a file's sender to the people who fetched it, as they can for messages.

With notifications on, the server also learns which accounts each message wakes, and so who is in a chat with whom, much as Signal's server knows the recipients of every message. The push service (a UnifiedPush distributor's server, or Apple's) sees when a device gets a push, but not who it's from. Turning notifications off takes both away.

## Known gaps

- Local database: plain SQLite for now. SQLCipher with a raw key from the Keychain/Keystore is next.
- A malicious member can stall a group by posting garbage commits. Removing them needs a commit they can race. This is acceptable for friends; harder for open groups.
- The server could show different members different orders (a fork). Members would detect it by comparing MLS epoch authenticators, which is planned for the safety-number screen.
- Messages wait 30 days on the server. A device offline longer than that misses commits and has to be re-added.
- Two people starting a 1:1 chat with each other at the same moment get two chats.
