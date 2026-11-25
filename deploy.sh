#!/bin/bash
# tgproxy auto-deploy: runs every minute (tgproxy-deploy.timer).
# Pulls branch "tgproxy", validates, backs up the running version, deploys,
# and rolls back automatically if a service does not come up.
set -u
REPO=/opt/tgproxy/repo
APP=/opt/tgproxy/app
REL=/opt/tgproxy/releases
export GIT_SSH_COMMAND="ssh -i /root/.ssh/tgproxy_deploy -o StrictHostKeyChecking=accept-new -o BatchMode=yes"

exec 9>/run/tgproxy-deploy.lock
flock -n 9 || exit 0

cd "$REPO" || exit 1
git fetch -q origin tgproxy || exit 0
NEW=$(git rev-parse origin/tgproxy)
CUR=$(cat "$APP/.rev" 2>/dev/null || echo none)
[ "$NEW" = "$CUR" ] && [ "${1:-}" != "--force" ] && exit 0

git reset -q --hard "$NEW"
for f in bot.py mtprotoproxy.py; do
  python3 -c "import ast,sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())" "$REPO/$f" \
    || { echo "INVALID $f - not deployed"; echo "$NEW" > "$APP/.rev"; exit 1; }
done

TS=$(date +%Y%m%d-%H%M%S)
mkdir -p "$REL" "$APP"
HAD_OLD=0
if [ -f "$APP/bot.py" ]; then
  cp -a "$APP" "$REL/$TS-${CUR:0:7}"
  HAD_OLD=1
fi
MTP_CHANGED=1
cmp -s "$REPO/mtprotoproxy.py" "$APP/mtprotoproxy.py" && MTP_CHANGED=0

cp "$REPO"/*.py "$REPO"/*.sh "$APP"/
echo "$NEW" > "$APP/.rev"
install -m755 "$REPO/deploy.sh" /usr/local/bin/tgproxy-deploy
install -m755 "$REPO/rollback.sh" /usr/local/bin/tgproxy-rollback

systemctl restart tgproxy-bot
[ "$MTP_CHANGED" = 1 ] && systemctl restart tgproxy-mtp
sleep 6

if systemctl is-active -q tgproxy-bot && systemctl is-active -q tgproxy-mtp; then
  echo "DEPLOYED ${NEW:0:7}"
  ls -1dt "$REL"/* 2>/dev/null | tail -n +11 | xargs -r rm -rf
  exit 0
fi

echo "FAILED ${NEW:0:7} - rolling back"
if [ "$HAD_OLD" = 1 ]; then
  cp -a "$REL/$TS-${CUR:0:7}/." "$APP/"
  echo "$NEW" > "$APP/.rev"
  systemctl restart tgproxy-mtp tgproxy-bot
  echo "ROLLED BACK to ${CUR:0:7}"
fi
exit 1
