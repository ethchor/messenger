#!/usr/bin/env bash
# Prepares a fresh Ubuntu 24.04 VPS and starts the server. Run it on the VPS
# as root, from the cloned repository:
#   git clone https://github.com/ethchor/messenger.git /opt/messenger
#   cd /opt/messenger && sudo infra/setup-vps.sh
#
# It installs Docker, opens only SSH, HTTP and HTTPS, turns on security
# updates, writes infra/.env if there isn't one, and starts the server.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$(id -u)" != 0 ]; then
  echo "Run this as root (sudo)." >&2
  exit 1
fi

echo "== Docker"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi

echo "== Firewall: SSH, HTTP, HTTPS"
apt-get update -q
apt-get install -y -q ufw unattended-upgrades
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo "== Security updates"
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== Settings"
if [ ! -f .env ]; then
  read -rp "Server hostname (its DNS must point here): " domain
  read -rp "Contact for push services (mailto: or https:): " contact
  sed -e "s|^DOMAIN=.*|DOMAIN=$domain|" -e "s|^MESSENGER_PUSH_CONTACT=.*|MESSENGER_PUSH_CONTACT=$contact|" .env.example > .env
  echo "Wrote infra/.env; edit it for APNs or limits."
fi
mkdir -p secrets backups
chmod 700 secrets backups

echo "== Start"
# The image is private while the repository is: log in to ghcr.io first
# (docker login ghcr.io -u ethchor, with a token that can read packages),
# or build here with --build.
if docker compose -f docker-compose.yml pull server 2>/dev/null; then
  docker compose -f docker-compose.yml up -d
else
  echo "Couldn't pull the image; building it here (this takes a while)."
  docker compose -f docker-compose.yml up -d --build
fi

echo "== Nightly backups"
line="15 3 * * * $(pwd)/backup.sh >> /var/log/messenger-backup.log 2>&1"
( crontab -l 2>/dev/null | grep -v "messenger.*backup.sh" ; echo "$line" ) | crontab -

docker compose -f docker-compose.yml ps
echo "Done. In the apps, use https://$(grep '^DOMAIN=' .env | cut -d= -f2) as the server."
