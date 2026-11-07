# Where to host the server

The server is one container with one volume (a SQLite database, and the encrypted files unless they're in a bucket), reached through a Cloudflare Tunnel or Caddy. Anywhere that runs Docker can host it, and moving is a backup and a restore. [DEPLOY.md](DEPLOY.md#on-a-vps) has the steps.

## The plan

**A small Indian VPS (VPSWala VPS 4 or Hostinger KVM 1), reached through a Cloudflare Tunnel, with the files in Cloudflare R2 and the domain's DNS on Cloudflare.**

- The VPS only runs the server and its database: 2 vCPU and 4 GB is comfortable, 1 vCPU and 2 GB is enough. Servers in India keep it close to the people using it.
- The tunnel connects out from the machine to Cloudflare, so no port is open to the internet and Cloudflare handles HTTPS.
- Photos, videos, voice notes and files go straight between the phones and R2, with links the server signs. They never pass through the VPS, so its data allowance and disk don't limit them, and R2 doesn't charge for downloads. They're encrypted on the phone before upload, so R2 holds only ciphertext.
- The hostname lives in Cloudflare and points at the tunnel, not at a machine. Moving hosts means starting the same compose file somewhere else with the same tunnel token and `.env`, and restoring the database: the apps never notice, and the files stay where they are.
- Nightly backups of the database are encrypted with an age key kept off the VPS and uploaded to a second R2 bucket.

Oracle Cloud's free machine was the first plan, but its sign-up refused the card.

## Files: in the volume or in a bucket

| | Folder (the default) | S3-compatible bucket (`MESSENGER_S3_*` in `.env`) |
|---|---|---|
| Where the bytes go | through the server, into its volume | straight between the phones and the bucket |
| What limits them | the VPS's disk and monthly data allowance | the bucket's price: R2 has 10 GB free, then about $0.015 per GB a month, and no download fees |
| What the server does | stores, checks and serves them | signs an upload link for one file of an exact size and SHA-256, checks the bucket has it, and redirects downloads to a link that works for 10 minutes |
| Moving hosts | copy the files with the database | nothing to copy |

Any S3-compatible service works: Cloudflare R2, Backblaze B2, Wasabi, AWS S3, or one you run yourself. Changing service is a change to `.env`, but files already uploaded stay where they were, so switch before people start sending files, or copy the bucket across. Files expire after `MESSENGER_RETENTION_DAYS` either way; the server deletes them.

What the storage sees: encrypted files of padded sizes, and the IP addresses that upload and fetch them. Through the tunnel Cloudflare sees those addresses already.

## The options

| | Cost | Good | Watch out for |
|---|---|---|---|
| **Oracle Cloud, Always Free A1 (ARM)** | free | plenty of memory and disk; free outbound traffic up to 10 TB a month | sign-up needs a card and can be refused; free machines in busy regions can be hard to get; Oracle may reclaim a free machine it sees as idle (keep it on a paid "Pay As You Go" account, still free within the limits, to avoid that) |
| **Hetzner Cloud CAX11 (ARM)** | about €4 a month | reliable, simple, EU data centres | not free |
| **Google Cloud e2-micro** | free in some US regions | always free | 1 GB of memory, which is tight for building but fine for running the published image |
| **Fly.io** | a few dollars a month | runs the container directly, volumes, anycast | a different deploy path (fly.toml) |
| **A Mac or a Raspberry Pi at home, through the tunnel** | free | the data stays home | down whenever the machine or the connection is |
| **Cloudflare Workers** | free tier | no machine at all | doesn't fit: the server needs a long-running process with SQLite, long polls and files on disk, so it would have to be rewritten around Durable Objects, D1 and R2 |

## Backups

`infra/backup.sh` runs nightly (the setup script schedules it). It takes a consistent copy of the live database with SQLite's online backup, checks it, adds the files if they're kept in the volume, and:

- with `BACKUP_AGE_RECIPIENT` set in `.env`, encrypts it with [age](https://age-encryption.org) to that public key;
- with `BACKUP_S3_BUCKET` set too, uploads it to that bucket. Use a bucket of its own with a write-only token and a lifecycle rule that deletes backups after 30 days.

The last 14 also stay in `infra/backups/` on the VPS.

**Keep somewhere safe, off the VPS** (a password manager is fine): the age key file (`age-keygen -o messenger-backup.key` makes it; the VPS gets only the public `age1…` line), `infra/.env` (the tunnel token, R2 tokens and settings), and the APNs key if you use one. With those and a backup, the server comes back anywhere. The people using it keep their own keys on their devices, and their recovery keys move their accounts; the server has no secret that unlocks their messages.

To open a backup: `age -d -i messenger-backup.key messenger-<time>.tar.gz.age > messenger-<time>.tar.gz`.

## Moving to another host

1. On the old machine: `infra/backup.sh`, then copy the newest backup (decrypted as above) and `infra/.env` off it.
2. On the new one: clone the repository to `/opt/messenger`, put `.env` back in `infra/`, and run `infra/setup-vps.sh` (it keeps an existing `.env`).
3. Restore the data into the volume, then start the server:

   ```sh
   cd /opt/messenger/infra
   docker compose stop server
   docker compose run --rm --no-deps -v "$PWD/backups:/backups" --entrypoint sh server \
     -c 'cd /data && tar -xzf /backups/messenger-<time>.tar.gz && mv backup.db server.db'
   docker compose up -d
   ```

4. With the tunnel, that's all: the hostname follows the token. With Caddy, point the DNS record at the new machine.

Accounts belong to the hostname, not the machine, so as long as the hostname stays, nobody signs up again.
