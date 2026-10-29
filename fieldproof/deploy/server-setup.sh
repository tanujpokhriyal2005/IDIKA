#!/bin/bash
# EC2 user-data: installs Docker, fetches the release from S3 and starts the stack.
set -euxo pipefail
exec > /var/log/fieldproof-setup.log 2>&1
BUCKET=__BUCKET__
SITE_HOST=__SITE_HOST__
dnf install -y docker cronie tar gzip
systemctl enable --now docker crond
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
mkdir -p /opt/fieldproof && cd /opt/fieldproof
aws s3 cp "s3://$BUCKET/release/fieldproof.tgz" /tmp/fieldproof.tgz
tar -xzf /tmp/fieldproof.tgz -C /opt/fieldproof
aws s3 cp "s3://$BUCKET/release/env" /opt/fieldproof/.env
chmod 600 /opt/fieldproof/.env
grep -q '^SITE_HOST=' .env || echo "SITE_HOST=$SITE_HOST" >> .env
grep -q '^BUCKET=' .env || echo "BUCKET=$BUCKET" >> .env
docker compose -f docker-compose.prod.yml up -d --build
chmod +x backup.sh
echo "30 21 * * * root /opt/fieldproof/backup.sh >> /var/log/fieldproof-backup.log 2>&1" > /etc/cron.d/fieldproof-backup
echo SETUP_DONE
