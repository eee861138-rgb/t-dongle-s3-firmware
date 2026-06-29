import json
import os
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB_PATH = ROOT / "data.sqlite3"
CONFIG_PATH = ROOT / "config.json"
DOWNLOADS = ROOT / "downloads"


def load_config():
    with CONFIG_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with db() as conn:
        conn.execute(
            """
            create table if not exists devices (
              device_id text primary key,
              app_version_code integer not null,
              app_version_name text not null,
              first_seen integer not null,
              last_seen integer not null,
              user_agent text
            )
            """
        )
        conn.execute(
            """
            create table if not exists events (
              id integer primary key autoincrement,
              device_id text,
              kind text not null,
              created_at integer not null
            )
            """
        )
        conn.execute(
            """
            create table if not exists bot_state (
              key text primary key,
              value text not null
            )
            """
        )
        conn.execute(
            """
            create table if not exists dongles (
              device_id text primary key,
              first_seen integer not null,
              last_seen integer not null,
              last_status text
            )
            """
        )
        conn.execute(
            """
            create table if not exists dongle_commands (
              id integer primary key autoincrement,
              device_id text not null,
              command text not null,
              status text not null,
              created_at integer not null,
              updated_at integer not null
            )
            """
        )


def now():
    return int(time.time())


def stats():
    cutoff = now() - 120
    with db() as conn:
        installs = conn.execute("select count(*) from devices").fetchone()[0]
        online = conn.execute("select count(*) from devices where last_seen >= ?", (cutoff,)).fetchone()[0]
        opens_today = conn.execute(
            "select count(*) from events where kind = 'open' and created_at >= ?",
            (now() - 86400,),
        ).fetchone()[0]
        versions = conn.execute(
            "select app_version_name, count(*) as c from devices group by app_version_name order by c desc"
        ).fetchall()
    return {
        "installs": installs,
        "online": online,
        "opens_today": opens_today,
        "versions": {row["app_version_name"]: row["c"] for row in versions},
    }


def dongle_stats():
    cutoff = now() - 10
    with db() as conn:
        rows = conn.execute(
            "select device_id, first_seen, last_seen, last_status from dongles order by last_seen desc"
        ).fetchall()
    return [
        {
            "device_id": row["device_id"],
            "online": row["last_seen"] >= cutoff,
            "last_seen": row["last_seen"],
            "last_status": row["last_status"] or "",
        }
        for row in rows
    ]


def queue_demo(device_id="default"):
    t = now()
    with db() as conn:
        if device_id == "default":
            row = conn.execute("select device_id from dongles order by last_seen desc limit 1").fetchone()
            device_id = row["device_id"] if row else "default"
        conn.execute(
            "insert into dongle_commands(device_id, command, status, created_at, updated_at) values(?, 'notepad_demo', 'queued', ?, ?)",
            (device_id, t, t),
        )
    return device_id


class Api(BaseHTTPRequestHandler):
    server_version = "MacroControllerAPI/1.0"

    def log_message(self, fmt, *args):
        return

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        body = self.rfile.read(length).decode("utf-8")
        return json.loads(body)

    def send_json(self, code, value):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        config = load_config()
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/version":
            self.send_json(
                200,
                {
                    "version_code": int(config["latest_version_code"]),
                    "version_name": config["latest_version_name"],
                    "apk_url": config["apk_url"],
                    "release_notes": config.get("release_notes", ""),
                },
            )
        elif parsed.path == "/api/stats":
            self.send_json(200, stats())
        elif parsed.path == "/api/dongles":
            self.send_json(200, {"ok": True, "dongles": dongle_stats()})
        elif parsed.path == "/api/dongle/poll":
            self.handle_dongle_poll(parsed)
        elif parsed.path == "/admin":
            self.serve_admin()
        elif parsed.path.startswith("/downloads/"):
            self.serve_download(parsed.path.removeprefix("/downloads/"))
        else:
            self.send_json(200, {"ok": True})

    def serve_admin(self):
        body = b"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Macro Controller Admin</title>
<style>body{font-family:Arial,sans-serif;background:#101214;color:#f2f2f2;margin:0}main{max-width:820px;margin:0 auto;padding:18px}button{width:100%;padding:14px;margin:10px 0;border:0;border-radius:8px;background:#2f8cff;color:white;font-size:17px}pre{background:#171b1f;padding:12px;border-radius:8px;overflow:auto}</style></head>
<body><main><h1>Macro Controller Admin</h1><button id='run'>Run Notepad Demo</button><button id='refresh'>Refresh</button><pre id='out'>Loading...</pre>
<script>
async function refresh(){const r=await fetch('/api/dongles');document.getElementById('out').textContent=JSON.stringify(await r.json(),null,2)}
document.getElementById('run').onclick=async()=>{await fetch('/api/demo/run',{method:'POST'});await refresh()}
document.getElementById('refresh').onclick=refresh;refresh();
</script></main></body></html>"""
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_dongle_poll(self, parsed):
        qs = urllib.parse.parse_qs(parsed.query)
        device_id = (qs.get("device_id", [""])[0] or "unknown")[:80]
        t = now()
        with db() as conn:
            exists = conn.execute("select 1 from dongles where device_id = ?", (device_id,)).fetchone()
            if exists:
                conn.execute("update dongles set last_seen = ? where device_id = ?", (t, device_id))
            else:
                conn.execute(
                    "insert into dongles(device_id, first_seen, last_seen, last_status) values(?, ?, ?, '')",
                    (device_id, t, t),
                )
            cmd = conn.execute(
                """
                select id, command from dongle_commands
                where status = 'queued' and (device_id = ? or device_id = 'default')
                order by id asc limit 1
                """,
                (device_id,),
            ).fetchone()
            if cmd:
                conn.execute("update dongle_commands set status = 'sent', updated_at = ? where id = ?", (t, cmd["id"]))
                self.send_json(200, {"ok": True, "id": cmd["id"], "command": cmd["command"]})
            else:
                self.send_json(200, {"ok": True, "command": ""})

    def serve_download(self, name):
        safe_name = Path(name).name
        path = DOWNLOADS / safe_name
        if not path.exists() or not path.is_file():
            self.send_json(404, {"error": "not found"})
            return
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", f'attachment; filename="{safe_name}"')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/demo/run":
            device_id = queue_demo()
            self.send_json(200, {"ok": True, "message": "notepad_demo queued", "device_id": device_id})
            return
        if parsed.path == "/api/dongle/result":
            payload = self.read_json()
            device_id = str(payload.get("device_id", ""))[:80]
            status = str(payload.get("status", ""))[:80]
            with db() as conn:
                conn.execute("update dongles set last_status = ?, last_seen = ? where device_id = ?", (status, now(), device_id))
            self.send_json(200, {"ok": True})
            return
        if parsed.path not in ("/api/register", "/api/heartbeat"):
            self.send_json(404, {"error": "not found"})
            return
        try:
            payload = self.read_json()
            device_id = str(payload.get("device_id", ""))[:80]
            version_code = int(payload.get("version_code", 0))
            version_name = str(payload.get("version_name", ""))[:40]
            if not device_id:
                self.send_json(400, {"error": "device_id required"})
                return
            t = now()
            with db() as conn:
                exists = conn.execute("select 1 from devices where device_id = ?", (device_id,)).fetchone()
                if exists:
                    conn.execute(
                        """
                        update devices
                        set app_version_code = ?, app_version_name = ?, last_seen = ?, user_agent = ?
                        where device_id = ?
                        """,
                        (version_code, version_name, t, self.headers.get("User-Agent", ""), device_id),
                    )
                else:
                    conn.execute(
                        """
                        insert into devices(device_id, app_version_code, app_version_name, first_seen, last_seen, user_agent)
                        values(?, ?, ?, ?, ?, ?)
                        """,
                        (device_id, version_code, version_name, t, t, self.headers.get("User-Agent", "")),
                    )
                kind = "open" if parsed.path == "/api/register" else "heartbeat"
                conn.execute("insert into events(device_id, kind, created_at) values(?, ?, ?)", (device_id, kind, t))
            self.send_json(200, {"ok": True})
        except Exception as exc:
            self.send_json(500, {"error": str(exc)})


def tg_api(token, method, params=None):
    url = f"https://api.telegram.org/bot{token}/{method}"
    data = None
    if params is not None:
        data = urllib.parse.urlencode(params).encode("utf-8")
    with urllib.request.urlopen(url, data=data, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def bot_loop():
    config = load_config()
    token = config.get("telegram_bot_token", "")
    admins = {int(x) for x in config.get("telegram_admin_ids", [])}
    if not token or not admins:
        return
    offset = 0
    while True:
        try:
            data = tg_api(token, "getUpdates", {"offset": offset, "timeout": 25})
            for upd in data.get("result", []):
                offset = max(offset, upd["update_id"] + 1)
                msg = upd.get("message") or {}
                chat = msg.get("chat") or {}
                chat_id = int(chat.get("id", 0))
                text = (msg.get("text") or "").strip()
                if chat_id not in admins:
                    continue
                if text in ("/start", "/help"):
                    reply = "/stats - current stats\n/version - latest APK info\n/dongles - dongle status\n/run_demo - run fixed cloud demo"
                elif text == "/stats":
                    s = stats()
                    reply = (
                        f"Installs: {s['installs']}\n"
                        f"Online: {s['online']}\n"
                        f"Opens 24h: {s['opens_today']}\n"
                        f"Versions: {s['versions']}"
                    )
                elif text == "/version":
                    c = load_config()
                    reply = f"{c['latest_version_name']} ({c['latest_version_code']})\n{c['apk_url']}"
                elif text == "/dongles":
                    reply = json.dumps(dongle_stats(), ensure_ascii=False, indent=2)
                elif text == "/run_demo":
                    device_id = queue_demo()
                    reply = f"Queued notepad_demo for {device_id}"
                else:
                    reply = "Unknown command"
                tg_api(token, "sendMessage", {"chat_id": chat_id, "text": reply})
        except Exception:
            time.sleep(5)


def main():
    DOWNLOADS.mkdir(exist_ok=True)
    if not CONFIG_PATH.exists():
        raise SystemExit("Create server/config.json first")
    init_db()
    config = load_config()
    threading.Thread(target=bot_loop, daemon=True).start()
    httpd = ThreadingHTTPServer((config.get("host", "0.0.0.0"), int(config.get("port", 8080))), Api)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
