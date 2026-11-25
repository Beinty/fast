#!/bin/bash
# tgproxy-rollback            -> list saved versions
# tgproxy-rollback latest     -> go back to the newest saved version
# tgproxy-rollback NAME       -> go back to that version
REL=/opt/tgproxy/releases
APP=/opt/tgproxy/app
if [ -z "${1:-}" ]; then
  echo "Running: $(cut -c1-7 "$APP/.rev" 2>/dev/null)"
  echo "Saved versions (newest first):"
  ls -1t "$REL" 2>/dev/null
  exit 0
fi
if [ "$1" = latest ]; then
  T=$(ls -1t "$REL" | head -1)
else
  T="$1"
fi
[ -n "$T" ] && [ -d "$REL/$T" ] || { echo "Not found: $1"; exit 1; }
KEEP=$(cat "$APP/.rev" 2>/dev/null)
cp -a "$REL/$T/." "$APP/"
echo "$KEEP" > "$APP/.rev"
systemctl restart tgproxy-mtp tgproxy-bot
sleep 3
systemctl is-active tgproxy-mtp tgproxy-bot
echo "Now running: $T"
