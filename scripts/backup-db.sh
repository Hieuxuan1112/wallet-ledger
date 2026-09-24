#!/usr/bin/env bash
# Nightly logical backup. Cron on the VM: 15 3 * * * ~/wallet-ledger/scripts/backup-db.sh
# Restore: docker compose exec -T db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean < backups/<file>.dump
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
mkdir -p backups
docker compose exec -T db pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" > "backups/wallet-$(date +%F).dump"
find backups -name 'wallet-*.dump' -mtime +14 -delete
