#!/bin/bash
# One-time installer. Usage: bash install.sh BOT_TOKEN ADMIN_ID
set -e
TOKEN="${1:-}"
ADMIN="${2:-}"
if [ -z "$TOKEN" ] || [ -z "$ADMIN" ]; then
  echo "Usage: bash install.sh BOT_TOKEN ADMIN_ID"; exit 1
fi

echo "[1/5] Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq git python3 python3-cryptography >/dev/null
apt-get install -y -qq python3-uvloop >/dev/null 2>&1 || true

echo "[2/5] Settings"
IP=$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' || true)
[ -n "$IP" ] || IP=$(hostname -I | awk '{print $1}')
mkdir -p /etc/tgproxy /var/lib/tgproxy /opt/tgproxy/app /opt/tgproxy/releases
cat > /etc/tgproxy/env <<EOF
BOT_TOKEN=$TOKEN
ADMIN_IDS=$ADMIN
SERVER_ADDR=$IP
PROXY_PORT=443
TLS_DOMAIN=www.cloudflare.com
METRICS_PORT=9877
EOF
chmod 600 /etc/tgproxy/env

if ss -ltnH 'sport = :443' | grep -q .; then
  echo "WARNING: something already uses port 443:"; ss -ltnp 'sport = :443'
fi

echo "[3/5] Services"
cat > /etc/systemd/system/tgproxy-mtp.service <<'EOF'
[Unit]
Description=tgproxy MTProto proxy
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/tgproxy/env
ExecStartPre=/usr/bin/python3 /opt/tgproxy/app/bot.py --write-config
ExecStart=/usr/bin/python3 /opt/tgproxy/app/mtprotoproxy.py /etc/tgproxy/mtp_config.py
Restart=always
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/tgproxy-bot.service <<'EOF'
[Unit]
Description=tgproxy admin bot
After=network-online.target tgproxy-mtp.service
Wants=network-online.target

[Service]
EnvironmentFile=/etc/tgproxy/env
ExecStart=/usr/bin/python3 -u /opt/tgproxy/app/bot.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/tgproxy-deploy.service <<'EOF'
[Unit]
Description=tgproxy pull from GitHub and deploy

[Service]
Type=oneshot
ExecStart=/usr/local/bin/tgproxy-deploy
EOF

cat > /etc/systemd/system/tgproxy-deploy.timer <<'EOF'
[Unit]
Description=tgproxy deploy every minute

[Timer]
OnBootSec=1min
OnUnitActiveSec=1min

[Install]
WantedBy=timers.target
EOF

install -m755 /opt/tgproxy/repo/deploy.sh /usr/local/bin/tgproxy-deploy
install -m755 /opt/tgproxy/repo/rollback.sh /usr/local/bin/tgproxy-rollback
systemctl daemon-reload
systemctl enable -q tgproxy-mtp tgproxy-bot tgproxy-deploy.timer

echo "[4/5] Firewall"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 22/tcp >/dev/null; ufw allow 443/tcp >/dev/null
fi

echo "[5/5] Start"
/usr/local/bin/tgproxy-deploy --force || true
systemctl start tgproxy-deploy.timer
sleep 2
echo
echo "proxy: $(systemctl is-active tgproxy-mtp)   bot: $(systemctl is-active tgproxy-bot)"
echo "Server IP: $IP   Port: 443"
echo "DONE - open your bot in Telegram and send /start"
