#!/usr/bin/env bash
# Backs up the server's database (and its files, when they're kept on the
# VPS rather than in a bucket), encrypts the backup, and copies it off the
# VPS. Run it from cron on the VPS, for example nightly:
#   15 3 * * * /opt/messenger/infra/backup.sh >> /var/log/messenger-backup.log 2>&1
#
# - SQLite's online backup copies a consistent database while the server runs.
# - With BACKUP_AGE_RECIPIENT set, the backup is encrypted to that age key;
#   only whoever holds the matching key file (kept off the VPS) can open it.
# - With BACKUP_S3_BUCKET set too, it's uploaded to that bucket on the same
#   S3-compatible service as the files (MESSENGER_S3_ENDPOINT).
# - The last 14 stay in infra/backups either way.
#
# The database holds usernames, public keys and ciphertext; the files are
# encrypted on the phones. Neither can be read without the devices' keys.
set -euo pipefail
cd "$(dirname "$0")"
compose=(docker compose -f docker-compose.yml)
stamp="$(date -u +%Y%m%d-%H%M%S)"
mkdir -p backups
if [ -f .env ]; then set -a; . ./.env; set +a; fi

parts=(backup.db)
# Files in a bucket aren't on this VPS; the bucket keeps them.
[ -z "${MESSENGER_S3_ENDPOINT:-}" ] && parts+=(blobs)

"${compose[@]}" exec -T server sqlite3 /data/server.db ".backup '/data/backup.db'"
"${compose[@]}" exec -T server sqlite3 /data/backup.db "PRAGMA integrity_check" | grep -qx ok \
  || { echo "the backup failed its integrity check" >&2; exit 1; }
"${compose[@]}" exec -T server tar -C /data -cf - "${parts[@]}" | gzip > "backups/messenger-$stamp.tar.gz"
"${compose[@]}" exec -T server rm -f /data/backup.db
file="backups/messenger-$stamp.tar.gz"

if [ -n "${BACKUP_AGE_RECIPIENT:-}" ]; then
  age -r "$BACKUP_AGE_RECIPIENT" -o "$file.age" "$file"
  rm -f "$file"
  file="$file.age"
else
  echo "warning: BACKUP_AGE_RECIPIENT isn't set, so the backup isn't encrypted or copied off the VPS" >&2
fi

if [ -n "${BACKUP_S3_BUCKET:-}" ] && [ "${file%.age}" != "$file" ]; then
  endpoint="${MESSENGER_S3_ENDPOINT:?BACKUP_S3_BUCKET needs MESSENGER_S3_ENDPOINT}"
  curl -fsS --aws-sigv4 "aws:amz:${MESSENGER_S3_REGION:-auto}:s3" \
    --user "${BACKUP_S3_ACCESS_KEY:-$MESSENGER_S3_ACCESS_KEY}:${BACKUP_S3_SECRET_KEY:-$MESSENGER_S3_SECRET_KEY}" \
    -T "$file" "${endpoint%/}/$BACKUP_S3_BUCKET/$(basename "$file")"
  echo "uploaded to $BACKUP_S3_BUCKET/$(basename "$file")"
fi

ls -1t backups/messenger-*.tar.gz* | tail -n +15 | xargs -r rm -f
echo "backed up to infra/$file"
