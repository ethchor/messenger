#!/usr/bin/env bash
# Backs up the server's database and uploaded files into infra/backups, and
# keeps the last 14. Run it from cron on the VPS, for example nightly:
#   15 3 * * * /opt/messenger/infra/backup.sh >> /var/log/messenger-backup.log 2>&1
#
# The database holds usernames, public keys and ciphertext; the files are
# encrypted. Neither can be read without the devices' keys, but keep the
# backups somewhere private anyway.
set -euo pipefail
cd "$(dirname "$0")"
compose=(docker compose -f docker-compose.yml)
stamp="$(date -u +%Y%m%d-%H%M%S)"
mkdir -p backups

# SQLite's online backup copies a consistent database while the server runs.
"${compose[@]}" exec -T server sqlite3 /data/server.db ".backup '/data/backup.db'"
"${compose[@]}" exec -T server tar -C /data -cf - backup.db blobs > "backups/messenger-$stamp.tar"
"${compose[@]}" exec -T server rm -f /data/backup.db
gzip -f "backups/messenger-$stamp.tar"

ls -1t backups/messenger-*.tar.gz | tail -n +15 | xargs -r rm -f
echo "backed up to infra/backups/messenger-$stamp.tar.gz"
