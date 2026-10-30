#!/usr/bin/env python3
"""l14-status.py: live status of the L14 for the Dune launcher on Martin's phone.

    l14-status.py            print the checks as text (what Dune would show)
    l14-status.py --json     print them as JSON
    l14-status.py serve      answer Dune on TCP port 8714 (run by l14-status.service)
    l14-status.py token      print the token Dune needs (creates it on first use)

Checks: backups (USB / Drive / mirror logs, failures, USB drive present), services (Drive mount,
hydrobot, NRG ship watcher, failed units, Claude Remote Control), machine (disk, power, updates,
restart needed) and NRG jobs (last ship, report PDF, Water Watch, dashboard publish). It also
passes on the FAIL/WARN lines of the daily l14-health report for things too slow to check live
(restore drill, unpushed git, rebuild kit).

Protocol (deliberately not HTTP, so Android's cleartext rules don't apply; Tailscale encrypts the
link anyway): the client connects, sends the token and a newline, gets one JSON document, and the
server closes. Wrong token, or a client outside localhost / LAN / Tailscale ranges, gets nothing.

Read-only: changes nothing on the machine except creating its own token file.
Stdlib only. Version 1.0.0, 2026-10-30.
"""
import datetime as dt
import glob
import hmac
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import socketserver
import subprocess
import sys
import threading
import time

HOME = os.path.expanduser("~")
CONF = os.path.join(HOME, ".config", "l14-status")
TOKEN_FILE = os.path.join(CONF, "token")
PORT = int(os.environ.get("L14_STATUS_PORT", "8714"))
LOGDIR = os.path.join(HOME, ".local", "state", "l14-backup")
HEALTH = os.path.join(HOME, "Documents", "l14-health", "latest.txt")
NRG = os.environ.get("NRG_REPO_DIR", os.path.join(HOME, "projects", "NRG"))
USB_REPO = "/mnt/backup/restic"
ALLOWED = [ipaddress.ip_network(n) for n in (
    "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    "100.64.0.0/10", "fd7a:115c:a1e0::/48")]
CACHE_SECS = 60
SLOW_CACHE_SECS = 1800

OK, INFO, WARN, FAIL = "ok", "info", "warn", "fail"


def sh(*cmd, timeout=10):
    """Run a command; return (exit code, stdout). Never raises."""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout.strip()
    except Exception:
        return 127, ""


def ago(t):
    s = time.time() - t
    if s < 7200:
        return f"{int(s // 60)} min ago"
    if s < 172800:
        return f"{int(s // 3600)} h ago"
    return f"{int(s // 86400)} days ago"


def when(t):
    return dt.datetime.fromtimestamp(t).strftime("%a %d %b %H:%M")


class Checks:
    def __init__(self):
        self.items = []

    def add(self, group, name, level, detail):
        self.items.append({"group": group, "name": name, "level": level, "detail": detail})


# ------------------------------------------------------------------------------ backups
def backup_log_lines():
    lines = []
    for f in sorted(glob.glob(os.path.join(LOGDIR, "*.log")))[-3:]:  # last three months is plenty
        try:
            with open(f, errors="replace") as fh:
                lines += [l.rstrip("\r\n") for l in fh if l.startswith("[")]
        except OSError:
            pass
    return lines


def stamp_of(line):
    m = re.match(r"\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\]", line)
    return dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").timestamp() if m else 0


def last_match(lines, pattern):
    for l in reversed(lines):
        if re.search(pattern, l):
            return stamp_of(l), l
    return 0, ""


def path_exists_quick(path, wait=5):
    """os.path.exists in a thread, so a sleeping USB drive can't hang the check."""
    box = []
    t = threading.Thread(target=lambda: box.append(os.path.exists(path)), daemon=True)
    t.start()
    t.join(wait)
    return box[0] if box else None


def check_backups(c):
    g = "Backups"
    lines = backup_log_lines()
    now = time.time()
    for name, pat, w, f in (("USB backup (nightly)", r"USB backup OK", 2, 4),
                            ("Drive backup (weekly)", r"Drive backup OK", 8, 15),
                            ("Drive copy on USB", r"Drive mirror OK", 3, 7)):
        t, _ = last_match(lines, pat)
        if not t:
            c.add(g, name, FAIL, "no successful run in the log")
        elif now - t > f * 86400:
            c.add(g, name, FAIL, f"last OK {when(t)} ({ago(t)})")
        elif now - t > w * 86400:
            c.add(g, name, WARN, f"last OK {when(t)} ({ago(t)})")
        else:
            c.add(g, name, OK, f"last OK {ago(t)}")
    t, l = last_match(lines, r"FAILED")
    ok_after = max(last_match(lines, r"backup OK")[0], last_match(lines, r"mirror OK")[0])
    if t and now - t < 3 * 86400 and t > ok_after:
        c.add(g, "Backup log", FAIL, re.sub(r"^\[[^]]*\]\s*", "", l) + f" ({ago(t)})")
    present = path_exists_quick(USB_REPO)
    if present:
        du = shutil.disk_usage("/mnt/backup")
        pct = 100 * du.used // du.total
        c.add(g, "USB backup drive", WARN if pct > 90 else OK, f"plugged in, {pct}% full")
    elif present is None:
        c.add(g, "USB backup drive", WARN, "not answering (asleep or unplugging?)")
    else:
        c.add(g, "USB backup drive", WARN, "not plugged in: tonight's USB backup will be skipped")
    for u in ("l14-backup-usb", "l14-backup-drive"):
        rc, out = sh("systemctl", "--user", "is-failed", f"{u}.service")
        if out == "failed":
            c.add(g, f"{u}.service", FAIL, "in a failed state")
        rc, out = sh("systemctl", "--user", "is-enabled", f"{u}.timer")
        if out != "enabled":
            c.add(g, f"{u}.timer", FAIL, f"timer not enabled ({out or 'unknown'})")


# ------------------------------------------------------------------------------ services
def check_services(c):
    g = "Services"
    drive = os.path.join(HOME, "Drive")
    c.add(g, "Google Drive mount", OK if os.path.ismount(drive) else FAIL,
          "mounted at ~/Drive" if os.path.ismount(drive) else "~/Drive is not mounted")
    rc, out = sh("systemctl", "is-active", "hydrobot")
    c.add(g, "Telegram bot (hydrobot)", OK if out == "active" else FAIL,
          "running" if out == "active" else f"not running ({out or 'unknown'})")
    rc, out = sh("systemctl", "--user", "is-active", "nrg-ship-watcher")
    c.add(g, "NRG ship watcher", OK if out == "active" else WARN,
          "running" if out == "active" else f"not running ({out or 'unknown'})")
    rc, out = sh("pgrep", "-af", "claude")
    rcl = [l for l in out.splitlines() if "remote-control" in l]
    c.add(g, "Claude Remote Control", OK if rcl else INFO,
          "session running" if rcl else "no session running")
    skip = ("l14-backup", "l14-status")
    for scope, label in ((["--user"], "User services"), ([], "System services")):
        rc, out = sh("systemctl", *scope, "--failed", "--no-legend", "--plain")
        bad = [l.split()[0] for l in out.splitlines() if l.strip() and not l.startswith(skip)]
        if bad:
            c.add(g, label, WARN, "failed: " + " ".join(bad))
    if os.path.exists(HEALTH):
        t = os.path.getmtime(HEALTH)
        if time.time() - t > 2 * 86400:
            c.add(g, "Daily health check", WARN, f"last ran {ago(t)}")


# ------------------------------------------------------------------------------ machine
_slow = {"t": 0, "updates": None}


def updates():
    if time.time() - _slow["t"] > SLOW_CACHE_SECS or _slow["updates"] is None:
        rc, out = sh("apt", "list", "--upgradable", timeout=30)
        lines = [l for l in out.splitlines() if "upgradable" in l]
        _slow["updates"] = (len(lines), sum("-security" in l for l in lines))
        _slow["t"] = time.time()
    return _slow["updates"]


def check_machine(c):
    g = "Machine"
    du = shutil.disk_usage("/")
    free = du.free // 2**30
    c.add(g, "Disk space", FAIL if free < 20 else WARN if free < 50 else OK, f"{free} GB free")
    ac = None
    for p in glob.glob("/sys/class/power_supply/*/online"):
        try:
            ac = (ac or False) or open(p).read().strip() == "1"
        except OSError:
            pass
    cap = None
    for b in glob.glob("/sys/class/power_supply/BAT*/capacity"):
        try:
            cap = int(open(b).read().strip())
        except (OSError, ValueError):
            pass
    if ac is False:
        lvl = FAIL if cap is not None and cap < 20 else WARN
        c.add(g, "Power", lvl, f"on battery ({cap}%), unplugged?" if cap is not None else "on battery")
    else:
        c.add(g, "Power", OK, "plugged in" + (f", battery {cap}%" if cap is not None else ""))
    n, sec = updates()
    if sec:
        c.add(g, "Updates", WARN, f"{n} waiting, {sec} security")
    elif n > 30:
        c.add(g, "Updates", WARN, f"{n} waiting")
    else:
        c.add(g, "Updates", OK, f"{n} waiting")
    if os.path.exists("/var/run/reboot-required"):
        c.add(g, "Restart", WARN, "a restart is needed to finish updates")
    try:
        up = float(open("/proc/uptime").read().split()[0])
        c.add(g, "Uptime", INFO, f"up {int(up // 86400)} days {int(up % 86400 // 3600)} h")
    except OSError:
        pass


# ------------------------------------------------------------------------------ NRG jobs
def newest_mtime(paths):
    ts = [os.path.getmtime(p) for p in paths if os.path.exists(p)]
    return max(ts) if ts else 0


def check_nrg(c):
    g = "NRG jobs"
    now = time.time()
    st = os.path.join(NRG, "working", ".ship_status.json")
    try:
        s = json.load(open(st))
        fin = s.get("finished") or s.get("started") or ""
        try:
            ft = dt.datetime.fromisoformat(fin.replace("Z", "+00:00")).timestamp()
        except ValueError:
            ft = 0
        if s.get("state") not in ("done", "failed", "interrupted", None):
            c.add(g, "Ship", INFO, f"{s.get('id', '?')} {s.get('state')}")
        elif s.get("verdict") != "OK":
            c.add(g, "Ship", FAIL if now - ft < 7 * 86400 else WARN,
                  f"last ship {s.get('id', '?')}: {s.get('verdict') or s.get('state')}" + (f" ({ago(ft)})" if ft else ""))
        else:
            c.add(g, "Ship", OK, "last ship OK" + (f" ({ago(ft)})" if ft else ""))
    except (OSError, ValueError):
        c.add(g, "Ship", INFO, "no ship status file")
    req = os.path.join(NRG, "working", ".ship_request")
    if os.path.exists(req) and now - os.path.getmtime(req) > 1800:
        c.add(g, "Ship request", WARN, f"waiting since {ago(os.path.getmtime(req))}, not picked up")
    pdf = os.path.join(NRG, "docs", "report", "report.pdf")
    odt = os.path.join(NRG, "report_edits", "odt")
    srcs = glob.glob(os.path.join(odt, "report*.odt")) + glob.glob(os.path.join(odt, "report.odm"))
    if os.path.exists(pdf):
        pt, st_ = os.path.getmtime(pdf), newest_mtime(srcs)
        if st_ > pt + 60:
            c.add(g, "Report PDF", WARN, f"older than its sources (built {ago(pt)}): /run report")
        else:
            c.add(g, "Report PDF", OK, f"up to date (built {ago(pt)})")
    else:
        c.add(g, "Report PDF", WARN, "docs/report/report.pdf missing")
    outs = [p for p in glob.glob(os.path.join(NRG, "living", "output", "*", "*", "*")) if os.path.isfile(p)]
    wt = newest_mtime(outs)
    if not wt:
        c.add(g, "Water Watch", WARN, "no output found")
    elif now - wt > 70 * 86400:
        c.add(g, "Water Watch", FAIL, f"last run {ago(wt)}")
    elif now - wt > 40 * 86400:
        c.add(g, "Water Watch", WARN, f"last run {ago(wt)}: this month's round due")
    else:
        c.add(g, "Water Watch", OK, f"last run {ago(wt)}")
    plog = os.path.join(NRG, "working", "dashboard", "publish.log")
    try:
        last = [l for l in open(plog, errors="replace").read().splitlines() if l.strip()][-1]
        pt = os.path.getmtime(plog)
        if " FAIL" in last:
            c.add(g, "Dashboard publish", WARN, "last publish failed")
        elif now - pt > 2 * 86400:
            c.add(g, "Dashboard publish", WARN, f"last ran {ago(pt)}")
        else:
            c.add(g, "Dashboard publish", OK, f"published {ago(pt)}")
    except (OSError, IndexError):
        pass


# ------------------------------------------------------------------------------ daily health report
def check_health_report(c):
    """Pass on the slow findings (restore drill, git, rebuild kit) from the 09:30 health check."""
    try:
        text = open(HEALTH, errors="replace").read()
    except OSError:
        return
    section = ""
    for l in text.splitlines():
        if l.startswith("## "):
            section = l[3:].strip()
        elif section in ("Restore drill", "Projects (git)", "Rebuild kit") and l[:4] in ("FAIL", "WARN"):
            name, _, detail = l[6:].partition(": ")
            c.add("Daily check", name.strip(), FAIL if l.startswith("FAIL") else WARN, detail.strip())


def run_checks():
    c = Checks()
    for fn in (check_backups, check_services, check_machine, check_nrg, check_health_report):
        try:
            fn(c)
        except Exception as e:  # one broken check must not hide the others
            c.add("Status service", fn.__name__, WARN, f"check crashed: {e}")
    n_fail = sum(i["level"] == FAIL for i in c.items)
    n_warn = sum(i["level"] == WARN for i in c.items)
    return {
        "host": socket.gethostname(),
        "generated": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "fail": n_fail, "warn": n_warn,
        "items": c.items,
    }


# ------------------------------------------------------------------------------ server
def token():
    os.makedirs(CONF, exist_ok=True)
    if not os.path.exists(TOKEN_FILE):
        old = os.umask(0o077)
        with open(TOKEN_FILE, "w") as f:
            f.write(secrets.token_urlsafe(24) + "\n")
        os.umask(old)
    return open(TOKEN_FILE).read().strip()


_cache = {"t": 0, "data": b""}
_lock = threading.Lock()


def cached_json():
    with _lock:
        if time.time() - _cache["t"] > CACHE_SECS:
            _cache["data"] = json.dumps(run_checks()).encode()
            _cache["t"] = time.time()
        return _cache["data"]


class Handler(socketserver.StreamRequestHandler):
    timeout = 5

    def handle(self):
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
            if ip.version == 6 and ip.ipv4_mapped:
                ip = ip.ipv4_mapped
            if not any(ip in n for n in ALLOWED):
                return
            sent = self.rfile.readline(200).decode("ascii", "replace").strip()
            if not hmac.compare_digest(sent.encode(), self.server.tok.encode()):
                return
            self.wfile.write(cached_json() + b"\n")
        except Exception:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)  # IPv4 and IPv6
        super().server_bind()


class Server4(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve():
    try:
        srv = Server(("::", PORT), Handler)       # IPv4 + IPv6
    except OSError:
        srv = Server4(("0.0.0.0", PORT), Handler)  # machine without IPv6
    srv.tok = token()
    print(f"l14-status listening on port {PORT}", flush=True)
    srv.serve_forever()


def as_text(d):
    mark = {OK: "  ok ", INFO: "   · ", WARN: "  !  ", FAIL: "  ✕  "}
    out = [f"L14 status {d['generated']}: {d['fail']} problem(s), {d['warn']} warning(s)"]
    group = None
    for i in d["items"]:
        if i["group"] != group:
            group = i["group"]
            out.append(f"\n{group}")
        out.append(f"{mark[i['level']]}{i['name']}: {i['detail']}")
    return "\n".join(out)


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "serve":
        serve()
    elif arg == "token":
        print(token())
    elif arg == "--json":
        print(json.dumps(run_checks(), indent=1))
    else:
        print(as_text(run_checks()))
