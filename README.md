# tgproxy (branch `tgproxy`)

Private Telegram MTProto proxy - only Telegram IDs approved in the bot can connect, one device per user.

- `mtprotoproxy.py` - alexbers/mtprotoproxy (MIT, see LICENSE-mtprotoproxy) + one-device-per-user patch (`USER_MAX_IPS`, newest IP wins) and instant kick on user removal.
- `bot.py` - admin/user Telegram bot (stdlib only, SQLite in /var/lib/tgproxy).
- `deploy.sh` - server pulls this branch every minute, validates, backs up, deploys, auto-rollback.
- `rollback.sh` - `tgproxy-rollback` / `tgproxy-rollback latest` / `tgproxy-rollback NAME`.
- `install.sh` - one-time setup: `bash install.sh BOT_TOKEN ADMIN_ID`.

Secrets live only on the server in `/etc/tgproxy/env`.
