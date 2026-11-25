# -*- coding: utf-8 -*-
"""HUC Proxy bot - private MTProto proxy, access by Telegram ID only.

Runs next to mtprotoproxy.py (patched: one device per user).
Settings come from /etc/tgproxy/env (written by install.sh), never from git.
"""
import datetime
import json
import os
import secrets
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

# ======================= SETTINGS (from /etc/tgproxy/env) =======================
BOT_TOKEN = os.environ.get("BOT_TOKEN", "")            # from @BotFather
ADMIN_IDS = {int(x) for x in os.environ.get("ADMIN_IDS", "").replace(" ", "").split(",") if x}
SERVER_ADDR = os.environ.get("SERVER_ADDR", "")        # server IP (or a domain later)
PROXY_PORT = int(os.environ.get("PROXY_PORT", "443"))
TLS_DOMAIN = os.environ.get("TLS_DOMAIN", "www.cloudflare.com")
METRICS_PORT = int(os.environ.get("METRICS_PORT", "9877"))
DATA_DIR = os.environ.get("DATA_DIR", "/var/lib/tgproxy")
MTP_CONFIG = os.environ.get("MTP_CONFIG", "/etc/tgproxy/mtp_config.py")
MTP_SERVICE = os.environ.get("MTP_SERVICE", "tgproxy-mtp")
# ===============================================================================

DB_PATH = os.path.join(DATA_DIR, "data.db")
PAGE = 8
_lock = threading.RLock()
_db = None
admin_state = {}


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


# ------------------------------- database -------------------------------
def db():
    global _db
    if _db is None:
        os.makedirs(DATA_DIR, exist_ok=True)
        _db = sqlite3.connect(DB_PATH, check_same_thread=False)
        _db.row_factory = sqlite3.Row
        _db.executescript("""
        CREATE TABLE IF NOT EXISTS users(
            uid INTEGER PRIMARY KEY, name TEXT DEFAULT '', username TEXT DEFAULT '',
            secret TEXT NOT NULL, max_ips INTEGER DEFAULT 1, expires INTEGER DEFAULT 0,
            active INTEGER DEFAULT 1, created INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS requests(
            uid INTEGER PRIMARY KEY, name TEXT DEFAULT '', username TEXT DEFAULT '', ts INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT);
        """)
        _db.commit()
    return _db


def q(sql, args=(), one=False):
    with _lock:
        cur = db().execute(sql, args)
        rows = cur.fetchall()
        db().commit()
    return (rows[0] if rows else None) if one else rows


def get_set(k, default):
    r = q("SELECT v FROM settings WHERE k=?", (k,), one=True)
    return r["v"] if r else str(default)


def put_set(k, v):
    q("INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, str(v)))


def def_days():
    return int(get_set("def_days", 30))


def def_ips():
    return int(get_set("def_ips", 1))


def requests_on():
    return get_set("requests_on", "1") == "1"


# ------------------------------ telegram api ------------------------------
def api(method, **params):
    url = "https://api.telegram.org/bot%s/%s" % (BOT_TOKEN, method)
    data = json.dumps({k: v for k, v in params.items() if v is not None}).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            return json.loads(r.read().decode()).get("result")
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="ignore")
        if "message is not modified" not in body:
            log("api", method, e.code, body[:200])
    except Exception as e:
        log("api", method, repr(e))
    return None


def send(chat_id, text, kb=None):
    return api("sendMessage", chat_id=chat_id, text=text, reply_markup=kb,
               disable_web_page_preview=True)


def edit(chat_id, msg_id, text, kb=None):
    r = api("editMessageText", chat_id=chat_id, message_id=msg_id, text=text,
            reply_markup=kb, disable_web_page_preview=True)
    return r


def kb(rows):
    return {"inline_keyboard": [[b for b in row if b] for row in rows if row]}


def btn(text, data):
    return {"text": text, "callback_data": data}


# ------------------------------- helpers -------------------------------
def n_devices(n):
    if n <= 0:
        return "بلا حد"
    if n == 1:
        return "جهاز واحد"
    if n == 2:
        return "جهازان"
    if n <= 10:
        return "%d أجهزة" % n
    return "%d جهازًا" % n


def n_days(n):
    if n == 1:
        return "يوم واحد"
    if n == 2:
        return "يومان"
    if 3 <= n <= 10:
        return "%d أيام" % n
    return "%d يومًا" % n


def fmt_date(ts):
    return datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d")


def expiry_text(u):
    if not u["expires"]:
        return "بلا انتهاء"
    left = int(-(-(u["expires"] - time.time()) // 86400))
    if u["expires"] <= time.time():
        return "منتهية (%s)" % fmt_date(u["expires"])
    return "حتى %s (متبقٍ %s)" % (fmt_date(u["expires"]), n_days(max(left, 0)) if left >= 1 else "أقل من يوم")


def is_valid(u):
    return bool(u) and u["active"] == 1 and (not u["expires"] or u["expires"] > time.time())


def link(u):
    sec = "ee" + u["secret"] + TLS_DOMAIN.encode().hex()
    return "https://t.me/proxy?" + urllib.parse.urlencode(
        {"server": SERVER_ADDR, "port": PROXY_PORT, "secret": sec})


def display(u):
    name = (u["name"] or "").strip() or "بدون اسم"
    if u["username"]:
        name += " @" + u["username"]
    return name


def gb(b):
    return "%.1f GB" % (b / 1024 ** 3)


# --------------------------- proxy config / reload ---------------------------
def write_config():
    now = time.time()
    rows = q("SELECT * FROM users WHERE active=1 AND (expires=0 OR expires>?)", (now,))
    users = {"u%d" % r["uid"]: r["secret"] for r in rows}
    max_ips = {"u%d" % r["uid"]: r["max_ips"] for r in rows if r["max_ips"] > 0}
    if not users:
        users = {"_nobody": secrets.token_hex(16)}
    text = (
        "# generated by bot.py - do not edit by hand\n"
        "PORT = %d\n"
        "USERS = %r\n"
        "USER_MAX_IPS = %r\n"
        "MODES = {'classic': False, 'secure': False, 'tls': True}\n"
        "TLS_DOMAIN = %r\n"
        "METRICS_PORT = %d\n"
        "METRICS_LISTEN_ADDR_IPV4 = '127.0.0.1'\n"
        "STATS_PRINT_PERIOD = 86400\n"
    ) % (PROXY_PORT, users, max_ips, TLS_DOMAIN, METRICS_PORT)
    os.makedirs(os.path.dirname(MTP_CONFIG), exist_ok=True)
    tmp = MTP_CONFIG + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, MTP_CONFIG)


def apply_config():
    with _lock:
        write_config()
    try:
        subprocess.run(["systemctl", "kill", "-s", "USR2", MTP_SERVICE],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    except Exception as e:
        log("reload failed", repr(e))


def metrics():
    """{user_key: {'ips': n, 'bytes': n}} or None if the proxy does not answer."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/" % METRICS_PORT, timeout=3) as r:
            text = r.read().decode()
    except Exception:
        return None
    out = {}
    for line in text.splitlines():
        if line.startswith("#") or "{user=" not in line:
            continue
        name, rest = line.split("{user=", 1)
        user = rest.split('"')[1]
        try:
            val = float(line.rsplit(" ", 1)[1])
        except ValueError:
            continue
        d = out.setdefault(user, {"ips": 0, "bytes": 0})
        if name.endswith("user_ips_curr"):
            d["ips"] = int(val)
        elif name.endswith("user_octets"):
            d["bytes"] = val
    return out


def net_tx():
    total = 0
    try:
        with open("/proc/net/dev") as f:
            for line in f.readlines()[2:]:
                iface, data = line.split(":", 1)
                if iface.strip() == "lo":
                    continue
                total += int(data.split()[8])
    except Exception:
        pass
    return total


def traffic_tick():
    month = time.strftime("%Y-%m")
    raw = net_tx()
    last = int(get_set("tr_last", raw))
    acc = int(get_set("tr_acc", 0))
    if get_set("tr_month", month) != month:
        acc = 0
    acc += raw - last if raw >= last else raw
    put_set("tr_month", month)
    put_set("tr_acc", acc)
    put_set("tr_last", raw)


# ------------------------------- screens -------------------------------
def panel():
    total = q("SELECT COUNT(*) c FROM users", one=True)["c"]
    m = metrics()
    online = "—" if m is None else str(sum(1 for v in m.values() if v["ips"] > 0))
    pending = q("SELECT COUNT(*) c FROM requests", one=True)["c"]
    text = ("لوحة التحكم\n\nالمستخدمون: %d\nمتصلون الآن: %s\nالاستهلاك هذا الشهر: %s"
            % (total, online, gb(int(get_set("tr_acc", 0)))))
    rows = [[btn("إضافة مستخدم", "add"), btn("المستخدمون", "ul:0")],
            [btn("الإحصائيات", "st"), btn("الإعدادات", "set")]]
    if pending:
        rows.append([btn("طلبات الوصول (%d)" % pending, "rql")])
    return text, kb(rows)


def user_card(uid):
    u = q("SELECT * FROM users WHERE uid=?", (uid,), one=True)
    if not u:
        return "المستخدم غير موجود.", kb([[btn("رجوع", "ul:0")]])
    m = metrics() or {}
    ips = m.get("u%d" % uid, {}).get("ips", 0)
    state = "نشط" if is_valid(u) else "متوقف"
    text = ("%s\nالمعرّف: %d\nالحالة: %s\nالأجهزة: متصل الآن %d — الحد %s\nالصلاحية: %s"
            % (display(u), uid, state, ips, n_devices(u["max_ips"]), expiry_text(u)))
    return text, kb([
        [btn("− الحد", "lim:%d:-1" % uid), btn("الحد: %s" % (u["max_ips"] or "∞"), "u:%d" % uid),
         btn("+ الحد", "lim:%d:1" % uid)],
        [btn("+7 أيام", "ext:%d:7" % uid), btn("+30 يومًا", "ext:%d:30" % uid),
         btn("بلا انتهاء", "ext:%d:0" % uid)],
        [btn("تغيير الرابط", "rs:%d" % uid), btn("إرسال الرابط له", "snd:%d" % uid)],
        [btn("إيقاف" if u["active"] else "تفعيل", "tog:%d" % uid), btn("حذف", "del:%d" % uid)],
        [btn("رجوع", "ul:0")],
    ])


def users_list(page):
    total = q("SELECT COUNT(*) c FROM users", one=True)["c"]
    rows = q("SELECT * FROM users ORDER BY created DESC LIMIT ? OFFSET ?", (PAGE, page * PAGE))
    m = metrics() or {}
    lines = []
    for u in rows:
        on = m.get("u%d" % u["uid"], {}).get("ips", 0) > 0
        mark = "🟢" if on else ("⚪" if is_valid(u) else "🔴")
        lines.append([btn("%s %s" % (mark, display(u)[:30]), "u:%d" % u["uid"])])
    nav = []
    if page > 0:
        nav.append(btn("السابق", "ul:%d" % (page - 1)))
    if (page + 1) * PAGE < total:
        nav.append(btn("التالي", "ul:%d" % (page + 1)))
    lines.append(nav)
    lines.append([btn("رجوع", "p")])
    text = "المستخدمون (%d)\n🟢 متصل  ⚪ غير متصل  🔴 متوقف" % total if total else "لا يوجد مستخدمون بعد."
    return text, kb(lines)


def stats_screen():
    m = metrics()
    if m is None:
        return "البروكسي لا يستجيب الآن.", kb([[btn("تحديث", "st"), btn("رجوع", "p")]])
    users = {"u%d" % r["uid"]: r for r in q("SELECT * FROM users")}
    online = sorted([(k, v) for k, v in m.items() if v["ips"] > 0 and k in users],
                    key=lambda kv: -kv[1]["bytes"])
    lines = ["الإحصائيات", "", "متصلون الآن: %d" % len(online),
             "الاستهلاك هذا الشهر: %s" % gb(int(get_set("tr_acc", 0))), ""]
    for k, v in online[:20]:
        lines.append("• %s — %s" % (display(users[k])[:25], gb(v["bytes"])))
    return "\n".join(lines), kb([[btn("تحديث", "st"), btn("رجوع", "p")]])


def settings_screen():
    text = ("الإعدادات\n\nمدة المستخدم الجديد: %s\nحد الأجهزة للمستخدم الجديد: %s\nطلبات الوصول: %s"
            % (n_days(def_days()) if def_days() else "بلا انتهاء", n_devices(def_ips()),
               "مفعّلة" if requests_on() else "متوقفة"))
    return text, kb([
        [btn("− المدة", "sd:-5"), btn("+ المدة", "sd:5"), btn("بلا انتهاء", "sd:0")],
        [btn("− الحد", "sl:-1"), btn("+ الحد", "sl:1")],
        [btn("إيقاف الطلبات" if requests_on() else "تفعيل الطلبات", "rq")],
        [btn("رجوع", "p")],
    ])


def requests_screen():
    rows = q("SELECT * FROM requests ORDER BY ts LIMIT 10")
    if not rows:
        return "لا توجد طلبات.", kb([[btn("رجوع", "p")]])
    lines = []
    for r in rows:
        nm = (r["name"] or "بدون اسم") + (" @" + r["username"] if r["username"] else "")
        lines.append([btn("✓ %s" % nm[:28], "acc:%d" % r["uid"]), btn("✕", "rej:%d" % r["uid"])])
    lines.append([btn("رجوع", "p")])
    return "طلبات الوصول (%d)" % len(rows), kb(lines)


def user_home(uid):
    u = q("SELECT * FROM users WHERE uid=?", (uid,), one=True)
    if is_valid(u):
        lk = link(u)
        text = ("تم تفعيل حسابك\n\nالصلاحية: %s\nالحد: %s\n\nهذا الرابط خاص بك. لا تشاركه مع أحد."
                % (expiry_text(u), n_devices(u["max_ips"])))
        return text, kb([[{"text": "اتصال بالبروكسي", "url": lk}],
                         [{"text": "نسخ الرابط", "copy_text": {"text": lk}}],
                         [btn("حسابي", "me")]])
    if u:
        admin = sorted(ADMIN_IDS)[0] if ADMIN_IDS else None
        rows = [[{"text": "تواصل مع الإدارة", "url": "tg://user?id=%d" % admin}]] if admin else []
        return "انتهت صلاحية اشتراكك.\nتواصل مع الإدارة للتجديد.", kb(rows)
    text = "هذا البوت خاص\n\nمعرّفك: %d" % uid
    if requests_on():
        text += "\nأرسل طلب وصول وانتظر موافقة الإدارة."
        return text, kb([[btn("طلب وصول", "req")]])
    return text, None


# ------------------------------- actions -------------------------------
def add_user(uid, name="", username=""):
    days = def_days()
    exp = int(time.time() + days * 86400) if days else 0
    if not name:
        ch = api("getChat", chat_id=uid) or {}
        name = " ".join(x for x in [ch.get("first_name"), ch.get("last_name")] if x)
        username = ch.get("username") or ""
    existing = q("SELECT uid FROM users WHERE uid=?", (uid,), one=True)
    if existing:
        q("UPDATE users SET active=1, expires=? WHERE uid=?", (exp, uid))
    else:
        q("INSERT INTO users(uid,name,username,secret,max_ips,expires,active,created) VALUES(?,?,?,?,?,?,1,?)",
          (uid, name, username, secrets.token_hex(16), def_ips(), exp, int(time.time())))
    q("DELETE FROM requests WHERE uid=?", (uid,))
    apply_config()
    notify_user(uid)


def notify_user(uid):
    text, k = user_home(uid)
    send(uid, text, k)


def expire_job():
    while True:
        try:
            now = int(time.time())
            gone = q("SELECT uid FROM users WHERE active=1 AND expires>0 AND expires<=?", (now,))
            if gone:
                q("UPDATE users SET active=0 WHERE active=1 AND expires>0 AND expires<=?", (now,))
                apply_config()
                for r in gone:
                    send(r["uid"], "انتهت صلاحية اشتراكك.\nتواصل مع الإدارة للتجديد.")
            traffic_tick()
        except Exception:
            traceback.print_exc()
        time.sleep(60)


# ------------------------------- handlers -------------------------------
def on_message(msg):
    chat = msg.get("chat", {})
    if chat.get("type") != "private":
        return
    uid = msg["from"]["id"]
    text = (msg.get("text") or "").strip()
    fr = msg["from"]
    name = " ".join(x for x in [fr.get("first_name"), fr.get("last_name")] if x)
    q("UPDATE users SET name=?, username=? WHERE uid=?", (name, fr.get("username") or "", uid))

    if uid in ADMIN_IDS:
        if admin_state.get(uid) == "add" and not text.startswith("/"):
            admin_state.pop(uid, None)
            target = None
            fo = msg.get("forward_origin") or {}
            if fo.get("type") == "user":
                target = fo["sender_user"]["id"]
            elif text.lstrip("-").isdigit():
                target = int(text)
            if not target:
                send(uid, "المعرّف غير صالح. أرسل رقم المعرّف فقط.", kb([[btn("رجوع", "p")]]))
                return
            add_user(target)
            t, k = user_card(target)
            send(uid, "تمت إضافة المستخدم\n\n" + t, k)
            return
        admin_state.pop(uid, None)
        t, k = panel()
        send(uid, t, k)
        return

    t, k = user_home(uid)
    send(uid, t, k)


def on_callback(cb):
    uid = cb["from"]["id"]
    data = cb.get("data", "")
    msg = cb.get("message") or {}
    chat_id, mid = msg.get("chat", {}).get("id"), msg.get("message_id")
    alert = None

    def show(screen):
        t, k = screen
        edit(chat_id, mid, t, k)

    try:
        if data == "req":
            if q("SELECT uid FROM users WHERE uid=?", (uid,), one=True):
                show(user_home(uid))
            elif not requests_on():
                alert = "الطلبات متوقفة حاليًا."
            elif q("SELECT uid FROM requests WHERE uid=?", (uid,), one=True):
                alert = "طلبك قيد المراجعة."
            else:
                fr = cb["from"]
                name = " ".join(x for x in [fr.get("first_name"), fr.get("last_name")] if x)
                q("INSERT OR REPLACE INTO requests(uid,name,username,ts) VALUES(?,?,?,?)",
                  (uid, name, fr.get("username") or "", int(time.time())))
                edit(chat_id, mid, "تم إرسال طلبك.\nستصلك رسالة عند الموافقة.")
                for a in ADMIN_IDS:
                    send(a, "طلب وصول جديد\n\n%s%s\nالمعرّف: %d"
                         % (name or "بدون اسم", " — @" + fr["username"] if fr.get("username") else "", uid),
                         kb([[btn("قبول", "acc:%d" % uid), btn("رفض", "rej:%d" % uid)]]))
            return
        if data == "me":
            show(user_home(uid))
            return

        if uid not in ADMIN_IDS:
            return
        if data == "p":
            admin_state.pop(uid, None)
            show(panel())
        elif data == "add":
            admin_state[uid] = "add"
            edit(chat_id, mid, "أرسل معرّف المستخدم (ID)\nأو أعد توجيه رسالة منه.", kb([[btn("إلغاء", "p")]]))
        elif data.startswith("ul:"):
            show(users_list(int(data[3:])))
        elif data.startswith("u:"):
            show(user_card(int(data[2:])))
        elif data.startswith("lim:"):
            _, t, d = data.split(":")
            u = q("SELECT max_ips FROM users WHERE uid=?", (int(t),), one=True)
            if u:
                q("UPDATE users SET max_ips=? WHERE uid=?", (max(0, u["max_ips"] + int(d)), int(t)))
                apply_config()
            show(user_card(int(t)))
        elif data.startswith("ext:"):
            _, t, d = data.split(":")
            t, d = int(t), int(d)
            u = q("SELECT * FROM users WHERE uid=?", (t,), one=True)
            if u:
                if d == 0:
                    exp = 0
                else:
                    base = max(time.time(), u["expires"] or 0)
                    exp = int(base + d * 86400)
                was_valid = is_valid(u)
                q("UPDATE users SET expires=?, active=1 WHERE uid=?", (exp, t))
                apply_config()
                if not was_valid:
                    notify_user(t)
                alert = "تم التمديد"
            show(user_card(t))
        elif data.startswith("rs:"):
            t = int(data[3:])
            q("UPDATE users SET secret=? WHERE uid=?", (secrets.token_hex(16), t))
            apply_config()
            notify_user(t)
            alert = "تم تغيير الرابط وإرسال الجديد للمستخدم"
            show(user_card(t))
        elif data.startswith("snd:"):
            t = int(data[4:])
            notify_user(t)
            alert = "تم الإرسال"
        elif data.startswith("tog:"):
            t = int(data[4:])
            q("UPDATE users SET active=1-active WHERE uid=?", (t,))
            apply_config()
            show(user_card(t))
        elif data.startswith("del:"):
            t = int(data[4:])
            edit(chat_id, mid, "حذف المستخدم %d نهائيًا؟" % t,
                 kb([[btn("تأكيد الحذف", "delok:%d" % t), btn("إلغاء", "u:%d" % t)]]))
        elif data.startswith("delok:"):
            t = int(data[6:])
            q("DELETE FROM users WHERE uid=?", (t,))
            apply_config()
            alert = "تم الحذف"
            show(users_list(0))
        elif data == "st":
            show(stats_screen())
        elif data == "set":
            show(settings_screen())
        elif data.startswith("sd:"):
            d = int(data[3:])
            put_set("def_days", 0 if d == 0 else max(5, def_days() + d if def_days() else 30))
            show(settings_screen())
        elif data.startswith("sl:"):
            put_set("def_ips", max(1, def_ips() + int(data[3:])))
            show(settings_screen())
        elif data == "rq":
            put_set("requests_on", "0" if requests_on() else "1")
            show(settings_screen())
        elif data == "rql":
            show(requests_screen())
        elif data.startswith("acc:"):
            t = int(data[4:])
            r = q("SELECT * FROM requests WHERE uid=?", (t,), one=True)
            add_user(t, r["name"] if r else "", r["username"] if r else "")
            alert = "تم القبول"
            show(user_card(t))
        elif data.startswith("rej:"):
            t = int(data[4:])
            q("DELETE FROM requests WHERE uid=?", (t,))
            send(t, "تم رفض طلبك.")
            alert = "تم الرفض"
            show(requests_screen())
    finally:
        api("answerCallbackQuery", callback_query_id=cb["id"], text=alert)


def poll():
    api("deleteWebhook")
    offset = None
    log("bot started, admins:", sorted(ADMIN_IDS))
    while True:
        res = api("getUpdates", offset=offset, timeout=50,
                  allowed_updates=["message", "callback_query"])
        if res is None:
            time.sleep(3)
            continue
        for upd in res:
            offset = upd["update_id"] + 1
            try:
                if "message" in upd:
                    on_message(upd["message"])
                elif "callback_query" in upd:
                    on_callback(upd["callback_query"])
            except Exception:
                traceback.print_exc()


def main():
    if "--write-config" in sys.argv:
        write_config()
        print("config written:", MTP_CONFIG)
        return
    if not BOT_TOKEN or not ADMIN_IDS or not SERVER_ADDR:
        sys.exit("BOT_TOKEN, ADMIN_IDS and SERVER_ADDR must be set in /etc/tgproxy/env")
    write_config()
    threading.Thread(target=expire_job, daemon=True).start()
    poll()


if __name__ == "__main__":
    main()
