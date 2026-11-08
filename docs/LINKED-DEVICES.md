# Linked devices: design

Status: **proposal, for approval before building.**

## What you get

- Use one account on your phone **and** your Mac (or a second phone or a tablet): up to **5 devices**.
- Adding a device takes your approval on a device you already have: the new one shows a QR code, you scan it and tap **Link**.
- Your chats, history and settings come across, encrypted end to end. A switch lets you start fresh instead.
- From then on every message, read mark and setting (pins, archive, mute, lock, blocks) reaches all your devices.
- **Settings → Linked devices** lists them, with when each was last active, and removes any of them, including a lost one.
- Your contacts' safety number for you doesn't change when you add or remove a device.
- The recovery key stays the way back in if you lose every device.

## How it works today

An account is one device. The server keeps one identity key, one invitation inbox, one set of key packages and one push target per account, and every chat (an MLS group) has one member per account. "Recover" with the recovery key replaces that one device. History isn't moved, and contacts see a new safety number.

## The design

### Two kinds of key

- **Account key:** a long-term Ed25519 key that is the account's identity. Safety numbers are made from account keys. Every linked device holds a copy, as in Signal.
- **Device key:** one per device, made on that device and never shared. It signs that device's requests and its messages in MLS. The account key signs each device key, so contacts can check that a device belongs to the account.

Today's single device key becomes the account key, and that device's key as well. Nobody's safety number changes on upgrade.

### Every device is a member of every chat

In each MLS group, an account has one member (a leaf) per device. A member's credential gains a device id and the account key's signature over the device key. Apps check that signature, and show and count *people*, not leaves. "A 1:1 chat has two members" becomes "two accounts".

- **Sending:** a message is encrypted once for the group, so it reaches every device of everyone in the chat. Your own other devices see it as your message.
- **Starting a chat with someone:** you claim one key package for each of their devices and add them all.
- **Read marks:** opening a chat on one device sends a read mark that your other devices also apply.

### A private group of your own devices

All your devices share one extra MLS group, never shown as a chat. It carries what isn't a chat message:
- pins, archive, mute, lock and blocks;
- the chat paper and your profile;
- vanish-mode clean-ups;
- a new device's arrival.

It's end to end encrypted like any chat. The server sees only one more mailbox.

### Linking a device, with approval

1. **New device:** choose "Link to an existing account". It makes its device key and a one-time key pair (X-Wing, the same post-quantum hybrid the chats use). It shows a QR code holding:
   - the server's address;
   - a random link id;
   - the one-time public key.

   Then it waits at the server under the link id.
2. **Existing device:** Settings → Linked devices → **Link a device**, then scan the code. It asks "Link 'Vimu's MacBook'?" and needs Face ID, a fingerprint or the passcode.
3. **Existing device:** encrypts a link bundle to the one-time key and leaves it at the server under the link id. The bundle holds:
   - account id, username, account key;
   - the new device's signed certificate;
   - profiles, settings, blocks;
   - the list of chats;
   - with history: the key and address of an encrypted history archive.

   The server keeps the bundle for at most 10 minutes and deletes it once it has been fetched.
4. **New device:** opens the bundle, registers its device key with the server (signed by the account key), uploads key packages, and joins the private device group.
5. **Existing device:** adds the new device to every chat, one commit per chat. Those commits are posted in the background and retried like any other.

**Why this is safe:**
- The QR code travels over the camera, so the server only ever relays a sealed bundle it can't open, and can't put its own key in the code.
- Without your approval on a device you already hold, nothing is sent.
- The link id expires after 10 minutes.

### History

- **Building the archive:** the existing device writes its messages, reactions, read marks and attachment keys into an archive and encrypts it with a fresh key. It uploads the archive like a file: to R2, through a signed link.
- **Size:** the archive carries attachment keys and file ids, not the files. The new device downloads photos and files when they're opened, as long as they haven't expired (30 days).
- **Without history:** the new device starts from the moment it's linked. Older chats are listed, but empty.

### Removing a device

From any of your devices: Settings → Linked devices → Remove. That device:
- removes the other device from every chat and from the device group;
- asks the server to delete its key, key packages and push target.

The removed device gets nothing new from then on and can't post. If you think a device was stolen, **Reset identity** also replaces the account key: everything is re-linked from the device you're holding, and contacts see a new safety number, just as with recovery.

### The server

| Today | With linked devices |
|---|---|
| `accounts.identity_key` | the **account key**; new `devices` table: device id, device key, certificate, name, added and last seen |
| requests signed by the one key | signed by a device key, with its device id in a header; the server checks the certificate |
| key packages per account | per device; claiming returns one for each device |
| one intro inbox per account | one per device, so each device gets its own invitations |
| one push target and wake token per account | per device: each device is woken |
| `POST /v1/accounts/recover` replaces the device | also clears the device list, then registers the device it's on |
| (new) | `PUT/GET /v1/links/{id}`: leave and fetch the sealed link bundle, at most 10 minutes, deleted on fetch |
| (new) | `GET/DELETE /v1/devices`: list and remove devices, signed by another of your devices |

**What the server learns:** how many devices an account has, when each was added and when it was last active. It sees nothing of what's in the bundle, the archive, the device group or the chats.

## Limits and choices

| Choice | Proposal |
|---|---|
| Devices per account | 5 (one phone plus four, or any mix) |
| History by default | **On**, with a switch to start fresh |
| Approval | A device you already have, with Face ID, fingerprint or passcode |
| Contacts told about a new device | No message in their chats (as WhatsApp and Signal); the safety number stays the same |
| Lost every device | The recovery key, as today: a new account key, and a new safety number for contacts |
| Phone required | No: any device can link or remove any other |

## Compatibility

Old apps can't handle more than one member per person in a chat. So the server and all three apps update together:
1. The server accepts both the old and the new format while the apps update.
2. Linking a device is offered only once everyone in a chat has the new app; a chat with someone still on an old app keeps one device per account for them until they update.

With a handful of people this is simply "everyone updates the same week".

## Building it, in order

| Step | What | Rough size |
|---|---|---|
| 1 | Server: devices, device-signed requests, key packages, inboxes and push per device; migrate existing accounts | 1–2 days |
| 2 | Core: account and device keys; certificates in credentials; several members per person; own messages from other devices | 2–3 days |
| 3 | Core and server: link bundles, QR payload, joining the chats; the private device group | 2 days |
| 4 | Apps: Link a device (QR on Mac, scanner on iPhone and Android); approval; Linked devices list; Remove | 2 days |
| 5 | History archive: export, upload, import | 1–2 days |
| 6 | Settings and read marks over the device group; Reset identity | 1 day |

Each step gets its own PR with end-to-end tests: two devices on one account, a contact, linking with and without history, removal, and recovery.

**Scanning on Android:** CameraX with ZXing, rather than Google's ML Kit, so it works on phones without Google services.

## Questions for you

1. History on by default?
2. Five devices?
3. Should a chat show "Vimu linked a new device"? (I'd say no.)
4. Should **Reset identity** be in the first version, or later?
