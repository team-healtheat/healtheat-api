#!/usr/bin/env bash
set -euo pipefail

target="${HEALTHEAT_DEPLOY_DIR:-/home/ec2-user/healtheat}"
test -f "$target/deploy/.env" || { echo 'Server configuration is missing.' >&2; exit 1; }
umask 077
mkdir -p "$target/backups"
temporary="$(mktemp "$target/backups/.backup.XXXXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
cd "$target"
sudo -n docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T postgres \
  pg_dump -U healtheat --format=custom healtheat > "$temporary"
destination="$target/backups/healtheat-$(date -u +%Y%m%dT%H%M%S)-${RANDOM}.dump"
mv -- "$temporary" "$destination"
echo "Database backup created: $destination"
