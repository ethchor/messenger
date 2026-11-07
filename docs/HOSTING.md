# Where to host the server

The server is one container with one volume (a SQLite database and a folder of encrypted files), reached through a Cloudflare Tunnel or Caddy. Anywhere that runs Docker can host it, and moving is a backup and a restore. [DEPLOY.md](DEPLOY.md#on-a-vps) has the steps.

## The plan

**Oracle Cloud's Always Free ARM machine, reached through a Cloudflare Tunnel, with the domain's DNS on Cloudflare.**

- Oracle's free Ampere A1 allowance (up to 4 cores and 24 GB of memory, 200 GB of disk) is far more than a circle of friends needs, and costs nothing.
- The tunnel connects out from the machine to Cloudflare, so no port is open to the internet. Cloudflare handles HTTPS, and Oracle's two firewalls (the security list and the image's iptables rules) need no changes.
- The hostname lives in Cloudflare and points at the tunnel, not at a machine. Moving hosts means starting the same compose file somewhere else with the same tunnel token: the apps never notice.

## The options

| | Cost | Good | Watch out for |
|---|---|---|---|
| **Oracle Cloud, Always Free A1 (ARM)** | free | plenty of memory and disk; free outbound traffic up to 10 TB a month | sign-up needs a card and can be refused; free machines in busy regions can be hard to get; Oracle may reclaim a free machine it sees as idle (keep it on a paid "Pay As You Go" account, still free within the limits, to avoid that) |
| **Hetzner Cloud CAX11 (ARM)** | about €4 a month | reliable, simple, EU data centres | not free |
| **Google Cloud e2-micro** | free in some US regions | always free | 1 GB of memory, which is tight for building but fine for running the published image |
| **Fly.io** | a few dollars a month | runs the container directly, volumes, anycast | a different deploy path (fly.toml) |
| **A Mac or a Raspberry Pi at home, through the tunnel** | free | the data stays home | down whenever the machine or the connection is |
| **Cloudflare Workers** | free tier | no machine at all | doesn't fit: the server needs a long-running process with SQLite, long polls and files on disk, so it would have to be rewritten around Durable Objects, D1 and R2 |

## Moving to another host

1. On the old machine: `infra/backup.sh`, then copy the newest `infra/backups/messenger-*.tar.gz` and `infra/.env` off it.
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
