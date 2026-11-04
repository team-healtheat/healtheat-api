#!/usr/bin/env bash
set -euo pipefail

workspace="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
target="${HEALTHEAT_DEPLOY_DIR:-/home/ec2-user/healtheat}"
test -f "$target/deploy/.env" || { echo 'Server configuration is missing.' >&2; exit 1; }
test -f "$workspace/build/libs/healtheat-api-0.0.1-SNAPSHOT.jar" || { echo 'Application artifact is missing.' >&2; exit 1; }

install -d "$target/build/libs" "$target/deploy"
install -m 644 "$workspace/build/libs/healtheat-api-0.0.1-SNAPSHOT.jar" "$target/build/libs/healtheat-api-0.0.1-SNAPSHOT.jar"
install -m 644 "$workspace/deploy/Dockerfile" "$workspace/deploy/Caddyfile" "$workspace/deploy/compose.yaml" "$workspace/deploy/backup.sh" "$target/deploy/"
install -m 644 "$workspace/.dockerignore" "$target/.dockerignore"

cd "$target"
sudo -n docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
sudo -n docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build

domain="$(sed -n 's/^DOMAIN=//p' deploy/.env | head -n 1)"
test -n "$domain" || { echo 'DOMAIN is missing.' >&2; exit 1; }
for attempt in $(seq 1 36); do
  if curl --fail --silent --show-error --noproxy '*' --max-time 5 \
       --resolve "$domain:443:127.0.0.1" "https://$domain/v3/api-docs" -o /dev/null; then
    echo 'Deployment is responding over HTTPS.'
    exit 0
  fi
  sleep 5
done
echo 'Deployment did not become ready; inspect server status.' >&2
sudo -n docker compose --env-file deploy/.env -f deploy/compose.yaml ps
exit 1
