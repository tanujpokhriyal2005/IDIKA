#!/bin/bash
# Nightly: database dump + uploaded photos to S3.
set -euo pipefail
cd /opt/fieldproof
. ./.env
TS=$(date -u +%Y%m%dT%H%M%SZ)
docker compose -f docker-compose.prod.yml exec -T db pg_dump -U fieldproof -Fc fieldproof > /tmp/fieldproof-$TS.dump
aws s3 cp /tmp/fieldproof-$TS.dump "s3://$BUCKET/backups/db/fieldproof-$TS.dump" --only-show-errors
rm -f /tmp/fieldproof-$TS.dump
VOL=$(docker volume inspect fieldproof_uploads --format '{{.Mountpoint}}')
aws s3 sync "$VOL" "s3://$BUCKET/backups/uploads/" --only-show-errors
