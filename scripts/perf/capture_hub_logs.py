#!/usr/bin/env python3
"""Capture a Hubitat hub's /logsocket stream to a JSONL file, reconnecting on
disconnect.  Every N seconds injects a JSON line with /hub/advanced/freeOSMemoryLast
stats so analyse_rm_delays.py can correlate event timing with hub memory/CPU pressure.

Usage:
    capture_hub_logs.py [-t] [--mem-interval SECS] [--username U] [--password P]
                        HUB [OUTPUT_FILE]

HUB is either:
    @hubname   — look up IP and credentials in .hubitat.json
    <ip>       — use this IP directly (no config file required)
"""
import argparse
import http.cookiejar
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

import websockets.exceptions
from websockets.sync.client import connect


# ---------------------------------------------------------------------------
# Hub config helpers (standalone — no shared module)
# ---------------------------------------------------------------------------

def _find_hub_config():
    """Walk up from cwd to find .hubitat.json. Returns (cfg, path) or (None, None)."""
    d = os.path.abspath(os.getcwd())
    while True:
        p = os.path.join(d, '.hubitat.json')
        if os.path.isfile(p):
            with open(p) as fh:
                return json.load(fh), p
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return None, None


def _make_opener(hub_ip, username=None, password=None):
    """Build an authenticated urllib opener for the given hub."""
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    if username and password:
        body = urllib.parse.urlencode({'username': username, 'password': password}).encode()
        req = urllib.request.Request(f"http://{hub_ip}/login", data=body)
        try:
            opener.open(req, timeout=10).read()
        except urllib.error.URLError as e:
            sys.exit(f"Login to {hub_ip} failed: {e}")
    return opener


def _hub_entry_for_ip(hub_ip):
    """Return the .hubitat.json hub dict whose hub_ip matches, or None."""
    cfg, _ = _find_hub_config()
    if cfg is None:
        return None
    for h in cfg.get('hubs', {}).values():
        if h.get('hub_ip') == hub_ip:
            return h
    return None


def resolve_hub(hub_arg, username=None, password=None):
    """Resolve HUB arg to (hub_ip, opener).

    @hubname  — look up in .hubitat.json; --username/--password override config creds.
    <ip>      — use directly; also checks .hubitat.json for credentials by IP so that
                secured hubs authenticate without needing explicit flags.
    """
    if hub_arg.startswith('@'):
        name = hub_arg[1:]
        cfg, cfg_path = _find_hub_config()
        if cfg is None:
            sys.exit("Could not find .hubitat.json (required for @hubname syntax).")
        hubs = cfg.get('hubs', {})
        if name not in hubs:
            sys.exit(f"Hub {name!r} not found in {cfg_path}. Known: {', '.join(hubs)}")
        h = hubs[name]
        hub_ip = h['hub_ip']
        u = username or h.get('username')
        p = password or h.get('password')
    else:
        hub_ip = hub_arg
        h = _hub_entry_for_ip(hub_ip) or {}
        u = username or h.get('username')
        p = password or h.get('password')
    return hub_ip, _make_opener(hub_ip, u, p)


# ---------------------------------------------------------------------------
# Hub metadata
# ---------------------------------------------------------------------------

def fetch_hub_info(hub_ip, opener):
    """GET /hub2/hubData and return a dict with name, model, firmware.
    Returns safe fallback values on any failure."""
    try:
        url = f"http://{hub_ip}/hub2/hubData"
        with opener.open(url, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8', errors='replace'))
        return {
            'name':     data.get('name', 'hub'),
            'model':    data.get('model', 'unknown'),
            'firmware': data.get('version', 'unknown'),
            'ip':       hub_ip,
        }
    except Exception as e:
        print(f"Warning: could not fetch hub info from {hub_ip}: {e}", file=sys.stderr)
        return {'name': 'hub', 'model': 'unknown', 'firmware': 'unknown', 'ip': hub_ip}


def infer_filename(info):
    """Build a filename from hub metadata: <name>-<model>-<firmware>-<timestamp>.json"""
    def slug(s):
        return re.sub(r'[^a-z0-9.\-]+', '-', s.lower()).strip('-')
    ts = datetime.now().strftime('%Y%m%d-%H%M%S')
    return f"{slug(info['name'])}-{slug(info['model'])}-{slug(info['firmware'])}-{ts}.json"


# ---------------------------------------------------------------------------
# Memory / CPU stats helpers
# ---------------------------------------------------------------------------

def _parse_csv_value(s):
    s = s.strip()
    if not s:
        return None
    try:
        return int(s)
    except ValueError:
        pass
    try:
        return float(s)
    except ValueError:
        pass
    return s


def _parse_hub_dt(raw_dt):
    now = datetime.now()
    try:
        t = datetime.strptime(f"{now.year}-{raw_dt}", '%Y-%m-%d %H:%M:%S')
        if t > now + timedelta(days=1):
            t = t.replace(year=now.year - 1)
        return t
    except ValueError:
        return now


def fetch_mem_last(opener, hub_ip):
    url = f"http://{hub_ip}/hub/advanced/freeOSMemoryLast"
    with opener.open(url, timeout=10) as resp:
        text = resp.read().decode('utf-8', errors='replace')
    lines = [ln for ln in text.strip().splitlines() if ln.strip()]
    if len(lines) < 2:
        return None
    header = [h.strip() for h in lines[0].split(',')]
    cols   = [c.strip() for c in lines[-1].split(',')]
    if len(cols) != len(header):
        return None
    return dict(zip(header, cols))


def make_hubstat_line(row):
    t = _parse_hub_dt(row.get('Date/time', ''))
    stats = {k: _parse_csv_value(v) for k, v in row.items() if k != 'Date/time'}
    msg = ' '.join(f"{k}={v}" for k, v in stats.items())
    payload = {
        'time':  t.strftime('%Y-%m-%d %H:%M:%S.') + f"{t.microsecond // 1000:03d}",
        'type':  'hubstat',
        'id':    0,
        'name':  'freeOSMemoryLast',
        'msg':   msg,
        'stats': stats,
    }
    return json.dumps(payload, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Thread-safe file writer
# ---------------------------------------------------------------------------

class FileWriter:
    def __init__(self, path, timestamp_prefix=False):
        self.timestamp_prefix = timestamp_prefix
        self.lock = threading.Lock()
        self.fh = open(path, 'a', encoding='utf-8', buffering=1)

    def write_line(self, line, prefix_timestamp=None):
        if prefix_timestamp is None:
            prefix_timestamp = self.timestamp_prefix
        with self.lock:
            if prefix_timestamp:
                self.fh.write(datetime.now().strftime('%Y-%m-%d %H:%M:%S '))
            self.fh.write(line)
            self.fh.write('\n')
            self.fh.flush()

    def close(self):
        with self.lock:
            self.fh.close()


# ---------------------------------------------------------------------------
# Background memory poller
# ---------------------------------------------------------------------------

def mem_poll_loop(stop_event, opener, hub_ip, writer, interval):
    """Append a hubstat JSON line every `interval` seconds.
    Always written without timestamp prefix — the JSON carries its own time field."""
    while not stop_event.is_set():
        try:
            row = fetch_mem_last(opener, hub_ip)
            if row:
                writer.write_line(make_hubstat_line(row), prefix_timestamp=False)
        except Exception as e:
            err = json.dumps({
                'time': datetime.now().strftime('%Y-%m-%d %H:%M:%S.000'),
                'type': 'hubstat', 'id': 0, 'name': 'freeOSMemoryLast',
                'msg':  f"fetch failed: {e}",
            })
            writer.write_line(err, prefix_timestamp=False)
        if stop_event.wait(interval):
            return


# ---------------------------------------------------------------------------
# WebSocket loop
# ---------------------------------------------------------------------------

def ws_loop(ws_url, writer, stop_event):
    while not stop_event.is_set():
        try:
            with connect(ws_url, open_timeout=10, close_timeout=5, ping_interval=None) as ws:
                for msg in ws:
                    if stop_event.is_set():
                        return
                    if isinstance(msg, bytes):
                        msg = msg.decode('utf-8', errors='replace')
                    writer.write_line(msg.rstrip('\r\n'))
        except (OSError, websockets.exceptions.WebSocketException) as e:
            ts = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            print(f"{ts} disconnected ({e}); retrying in 1 second", file=sys.stderr)
        if stop_event.wait(1):
            return


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(
        description="Capture a Hubitat hub's logsocket stream to a JSONL file.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "HUB examples:\n"
            "  @maison-pro          look up in .hubitat.json\n"
            "  192.168.1.86         bare IP (no config file needed)\n\n"
            "Output file is auto-generated if not supplied:\n"
            "  <hub-name>-<model>-<firmware>-<timestamp>.json"
        ),
    )
    ap.add_argument('-t', '--timestamp', action='store_true',
                    help='Prepend each websocket line with a timestamp.')
    ap.add_argument('--username', help='Hub username (overrides .hubitat.json).')
    ap.add_argument('--password', help='Hub password (overrides .hubitat.json).')
    ap.add_argument('--mem-interval', type=int, default=300,
                    help='Seconds between freeOSMemoryLast samples (default 300; <=0 disables).')
    ap.add_argument('hub', metavar='HUB',
                    help='@hubname (config lookup) or bare IP address.')
    ap.add_argument('output_file', metavar='OUTPUT_FILE', nargs='?',
                    help='Output path (auto-generated if omitted).')
    args = ap.parse_args()

    hub_ip, opener = resolve_hub(args.hub, args.username, args.password)
    info = fetch_hub_info(hub_ip, opener)

    output_file = args.output_file or infer_filename(info)
    print(f"Output: {output_file}", file=sys.stderr)

    writer = FileWriter(output_file, timestamp_prefix=args.timestamp)

    # Capture-header line (written once, not timestamp-prefixed)
    header = {
        'type':         'capture-header',
        'time':         datetime.now().strftime('%Y-%m-%d %H:%M:%S.') +
                        f"{datetime.now().microsecond // 1000:03d}",
        'hub_name':     info['name'],
        'hub_model':    info['model'],
        'hub_firmware': info['firmware'],
        'hub_ip':       info['ip'],
    }
    writer.write_line(json.dumps(header, ensure_ascii=False), prefix_timestamp=False)

    stop_event = threading.Event()
    mem_thread = None

    if args.mem_interval > 0:
        mem_thread = threading.Thread(
            target=mem_poll_loop,
            args=(stop_event, opener, hub_ip, writer, args.mem_interval),
            daemon=True,
        )
        mem_thread.start()
        print(f"Polling {hub_ip}/hub/advanced/freeOSMemoryLast every {args.mem_interval}s",
              file=sys.stderr)

    ws_url = f"ws://{hub_ip}/logsocket"
    print(f"Streaming {ws_url} [{info['name']} | {info['model']} | fw {info['firmware']}]",
          file=sys.stderr)

    try:
        ws_loop(ws_url, writer, stop_event)
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        if mem_thread:
            mem_thread.join(timeout=2)
        writer.close()


if __name__ == '__main__':
    main()
