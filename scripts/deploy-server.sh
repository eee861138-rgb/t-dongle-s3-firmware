#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/macro-controller

apt-get update
apt-get install -y python3 nginx

id -u macroctl >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin macroctl
mkdir -p "$APP_DIR"
cp -R server "$APP_DIR/"
mkdir -p "$APP_DIR/server/downloads"

if [ ! -f "$APP_DIR/server/config.json" ]; then
  cp "$APP_DIR/server/config.example.json" "$APP_DIR/server/config.json"
fi

chown -R macroctl:macroctl "$APP_DIR"
cp "$APP_DIR/server/macro-controller.service" /etc/systemd/system/macro-controller.service
cp "$APP_DIR/server/nginx.conf" /etc/nginx/sites-available/macro-controller
ln -sf /etc/nginx/sites-available/macro-controller /etc/nginx/sites-enabled/macro-controller
rm -f /etc/nginx/sites-enabled/default

systemctl daemon-reload
systemctl enable --now macro-controller
nginx -t
systemctl reload nginx
