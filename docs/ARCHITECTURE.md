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
        accounts · key package directory · ordered mailboxes (SQLite)
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
- **Delete account** leaves every chat, then deletes the account on the server: its key packages, its intro inbox, and the username, which becomes free. **Remove from this device** leaves every chat and withdraws the device's key packages, but keeps the account for the recovery key.
- Both then erase the local database. The server side goes first, so if the server can't be reached nothing changes; if the app stops halfway, the wipe finishes on the next start.
- **Panic wipe** only erases the local database, at once: nothing is sent, so it works with no connection, and neither the server nor contacts notice. The recovery key brings the account back, without the old messages. Until the database is encrypted with a key held by the Keychain or Keystore, erasing relies on the platform's file encryption to make the deleted files unreadable.

## Receipts and typing

Read receipts and typing indicators are application messages, encrypted like any other. Receivers keep typing state in memory only, and it expires after 6 seconds. A typing message that loses a race with a commit is dropped rather than re-sent. Each setting works both ways: with read receipts off, we neither send them nor show other people's.

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
- Phones send a position when they've moved, plus a heartbeat every 30 s while still (sparser in the background). On iPhone, background sharing uses Core Location's background updates; on Android, a foreground service with a visible notification and a Stop button.
- Maps: Apple Maps on iPhone and Mac; MapLibre with OpenStreetMap tiles (OpenFreeMap) on Android. Map tiles are fetched from the map provider, which can see which area is being viewed, though not whose position is shown there. Self-hosted tiles would remove that.

## What the server learns

| It sees | It doesn't see |
|---|---|
| usernames, account ids, device and recovery public keys | message content, edits, reactions |
| key packages (public) | who is in which group, and group names |
| ciphertext sizes (Padmé-padded), timing, and the IP addresses of connections | which mailboxes belong to the same group across epochs |
| how many messages each mailbox holds | which account reads or posts to a mailbox, unless linked by IP and timing |

Fetches are batched over one connection, so the server can link the mailboxes a device polls together. An OHTTP relay and per-mailbox fetching are planned, and so is Privacy Pass, so the server can rate-limit without identifying anyone.

Receipts, typing indicators and shared locations are encrypted, but their timing still shows the server when a mailbox is active; location sharing in particular sends regular updates while it's on.

## Known gaps

- Local database: plain SQLite for now. SQLCipher with a raw key from the Keychain/Keystore is next.
- A malicious member can stall a group by posting garbage commits. Removing them needs a commit they can race. This is acceptable for friends; harder for open groups.
- The server could show different members different orders (a fork). Members would detect it by comparing MLS epoch authenticators, which is planned for the safety-number screen.
- Messages wait 30 days on the server. A device offline longer than that misses commits and has to be re-added.
- Two people starting a 1:1 chat with each other at the same moment get two chats.
