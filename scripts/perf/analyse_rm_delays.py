#!/usr/bin/env python3
"""Analyze a Hubitat logsocket JSONL capture for slow Trigger->Action delays.

Reads a file produced by capture_hub_logs.py (or any JSONL logsocket capture)
and reports timing statistics, a histogram, and detailed context around outliers.

Usage:
    analyse_rm_delays.py LOGFILE [--hub NAME|IP] [--username U] [--password P]
                         [--threshold SECS] [--mem-window MINS]
                         [--obfuscate [--obfuscate-map FILE]]


--hub fetches /hub/advanced/freeOSMemoryHistory to correlate memory/CPU pressure
with outliers.  Accepts a hub name (from .hubitat.json) or a bare IP address.
--username / --password are only needed for secured hubs addressed by bare IP.

If the log file contains a capture-header line (written by capture_hub_logs.py),
hub name, model, and firmware are shown in the output header automatically.
"""

import argparse
import html
import http.cookiejar
import json
import os
import random
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
import statistics


# ---------------------------------------------------------------------------
# Hub config helpers (standalone — no shared module)
# ---------------------------------------------------------------------------

def _find_hub_config():
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


def _hub_entry_for_ip(hub_ip):
    """Return the .hubitat.json hub dict whose hub_ip matches, or None."""
    cfg, _ = _find_hub_config()
    if cfg is None:
        return None
    for h in cfg.get('hubs', {}).values():
        if h.get('hub_ip') == hub_ip:
            return h
    return None


def open_hub_session(hub_arg, username=None, password=None):
    """Resolve hub_arg to (opener, hub_ip).

    hub_arg is either:
      - a hub name (no '.' or ':') — looks up .hubitat.json
      - a bare IP/hostname           — uses directly; also checks .hubitat.json for
                                       credentials by IP so secured hubs authenticate
                                       without needing explicit --username/--password.
    """
    is_ip = '.' in hub_arg or ':' in hub_arg
    if is_ip:
        hub_ip = hub_arg
        h = _hub_entry_for_ip(hub_ip) or {}
        u = username or h.get('username')
        p = password or h.get('password')
    else:
        cfg, cfg_path = _find_hub_config()
        if cfg is None:
            sys.exit("Could not find .hubitat.json (required for --hub hubname).")
        hubs = cfg.get('hubs', {})
        if hub_arg not in hubs:
            sys.exit(f"Hub {hub_arg!r} not found in {cfg_path}. Known: {', '.join(hubs)}")
        h = hubs[hub_arg]
        hub_ip = h['hub_ip']
        u = username or h.get('username')
        p = password or h.get('password')
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    if u and p:
        body = urllib.parse.urlencode({'username': u, 'password': p}).encode()
        req = urllib.request.Request(f"http://{hub_ip}/login", data=body)
        try:
            opener.open(req, timeout=10).read()
        except urllib.error.URLError as e:
            sys.exit(f"Login to {hub_ip} failed: {e}")
    return opener, hub_ip

OUTLIER_THRESHOLD_SEC = 0.3
CONTEXT_BEFORE = 5      # log lines before the Triggered entry
CONTEXT_AFTER = 5       # log lines after the Action entry
MEM_WINDOW_MIN = 30     # show hub-memory samples within +/- this many minutes of the outlier

parser = argparse.ArgumentParser(description='Analyze Hubitat logsocket capture for slow Trigger->Action delays.')
parser.add_argument('logfile')
parser.add_argument('--hub', help='Hub name (from .hubitat.json) or bare IP. If set, fetches /hub/advanced/freeOSMemoryHistory and shows samples around each outlier.')
parser.add_argument('--username', help='Hub username (for bare-IP --hub on secured hubs).')
parser.add_argument('--password', help='Hub password (for bare-IP --hub on secured hubs).')
parser.add_argument('--threshold', type=float, default=OUTLIER_THRESHOLD_SEC, help=f'Outlier threshold in seconds (default {OUTLIER_THRESHOLD_SEC})')
parser.add_argument('--mem-window', type=int, default=MEM_WINDOW_MIN, help=f'Memory-history window in minutes (default {MEM_WINDOW_MIN})')
parser.add_argument('--obfuscate', action='store_true', help='Replace device/app names with random adjective-noun aliases (deterministic per-run) for safe sharing on public forums.')
parser.add_argument('--obfuscate-map', help='If set with --obfuscate, write the original->alias mapping to this file (do NOT share alongside the report).')
args = parser.parse_args()


# --- Name obfuscation ----------------------------------------------------
ADJECTIVES = ['amber','azure','brave','brisk','bronze','calm','clever','coral','crimson','dapper','eager',
              'fierce','frosty','gentle','glossy','golden','grand','hardy','hidden','humble','icy','indigo',
              'jolly','keen','lively','lucky','merry','misty','mossy','noble','olive','plucky','quiet',
              'rapid','rosy','rustic','sandy','silent','silver','sleek','smooth','snowy','solid','spry',
              'stormy','sturdy','sunny','swift','tame','tidy','vivid','warm','wild','witty','zesty']
NOUNS = ['acorn','badger','beacon','beetle','bramble','canyon','cedar','cliff','comet','cricket','dahlia',
         'delta','ember','falcon','ferret','finch','garnet','geode','glade','harbor','heron','iris','jade',
         'kestrel','lagoon','lichen','lotus','marble','meadow','mesa','moss','newt','onyx','opal','otter',
         'pebble','plume','quartz','quill','raven','reef','river','sage','shoal','silt','slate','spark',
         'spruce','sumac','thistle','tundra','vale','vine','weave','willow','yarrow']

class Obfuscator:
    def __init__(self):
        self.map = {}
        self._used = set()
        self._rng = random.Random(0xCAFEBABE)
        self._sorted_keys = []  # cached, sorted by length desc, rebuilt on additions

    def alias(self, name):
        if not name:
            return name
        if name not in self.map:
            for _ in range(10000):
                fake = f"{self._rng.choice(ADJECTIVES)}-{self._rng.choice(NOUNS)}"
                if fake not in self._used:
                    break
            self._used.add(fake)
            self.map[name] = fake
            self._sorted_keys = sorted(self.map.keys(), key=len, reverse=True)
        return self.map[name]

    def scrub(self, text):
        if not text:
            return text
        for orig in self._sorted_keys:
            if orig in text:
                text = text.replace(orig, self.map[orig])
        return text

obf = Obfuscator() if args.obfuscate else None

# Hubitat log messages often reference devices that never fire their own log
# entry in the capture window — only by name inside another app's summary.
# These regexes harvest those names so they can be aliased too.
_TRIGGER_LIST_RE = re.compile(r'Triggered:[^()]*? of (.+?)\(')
# Only match simple device-target action verbs — anything with a colon in the
# body (like "Wait for event: --> elapsed time: 0:02:00") would otherwise be
# misread as a device name.
_ACTION_TARGET_RE = re.compile(
    r'^Action: (?:On|Off|Toggle|Capture|Restore|Lock|Unlock|Open|Close|Push|Refresh):\s+(.+?)(?:\s*\(.*)?$'
)

def harvest_referenced_names(msg):
    found = []
    if not msg:
        return found
    m = _TRIGGER_LIST_RE.search(msg)
    if m:
        for n in m.group(1).split(','):
            n = n.strip()
            if 1 < len(n) < 80:
                found.append(n)
    m = _ACTION_TARGET_RE.match(msg)
    if m:
        n = m.group(1).strip()
        if 1 < len(n) < 80:
            found.append(n)
    return found


# First pass: load every log entry into memory so we can pull surrounding context.
entries = []  # list of (datetime, dict)
capture_header = None
with open(args.logfile, 'r') as f:
    for line in f:
        try:
            data = json.loads(line)
            time_str = data.get('time')
            if not time_str:
                continue
            if data.get('type') == 'capture-header':
                capture_header = data
                continue
            entries.append((datetime.strptime(time_str, '%Y-%m-%d %H:%M:%S.%f'), data))
        except (json.JSONDecodeError, ValueError):
            continue

if not entries:
    print("No log entries parsed.")
    sys.exit(1)

# Pre-register every distinct name so cross-entry msg substitution works
# (a device's name often appears verbatim in other entries' messages).
if obf is not None:
    for _, d in entries:
        obf.alias(d.get('name'))
        for n in harvest_referenced_names(d.get('msg', '')):
            obf.alias(n)

trigger_state = {}  # app_id -> (datetime, entry_index)
diffs = []
outliers = []  # (diff, app_id, app_name, trigger_idx, action_idx)
involved_apps = set()
inband_mem_samples = []  # samples written by ws_to_file.py via /hub/advanced/freeOSMemoryLast

for idx, (current_time, data) in enumerate(entries):
    if data.get('type') == 'hubstat':
        stats = data.get('stats') or {}
        if stats:
            row = {'Date/time': current_time.strftime('%m-%d %H:%M:%S')}
            row.update({k: ('' if v is None else v) for k, v in stats.items()})
            inband_mem_samples.append((current_time, row))
        continue
    if data.get('type') != 'app':
        continue
    app_id = data.get('id')
    msg = data.get('msg', '')
    if 'Triggered' in msg:
        trigger_state[app_id] = (current_time, idx)
    elif 'Action:' in msg and app_id in trigger_state:
        t_trigger, trigger_idx = trigger_state.pop(app_id)
        diff = (current_time - t_trigger).total_seconds()
        diffs.append(diff)
        involved_apps.add(app_id)
        if diff > args.threshold:
            outliers.append((diff, app_id, data.get('name'), trigger_idx, idx))

if not diffs:
    print("No matching patterns found.")
    sys.exit(0)

start_time = entries[0][0]
end_time = entries[-1][0]
duration = end_time - start_time

if obf is not None:
    print("[ Names obfuscated for sharing — IDs preserved. ]")
else:
    print(f"File: {args.logfile}")
if capture_header:
    print(f"Hub:  {capture_header.get('hub_name', '?')} | "
          f"{capture_header.get('hub_model', '?')} | "
          f"fw {capture_header.get('hub_firmware', '?')}")
print(f"Timespan: {start_time} to {end_time} ({duration})")
print(f"Total log entries: {len(entries)}")
print(f"Trigger->Action samples: {len(diffs)}")
print(f"Unique App IDs: {len(involved_apps)} ({', '.join(map(str, sorted(involved_apps)))})")
print(f"Mean: {statistics.mean(diffs):.4f}s")
print(f"Stdev: {statistics.stdev(diffs):.4f}s" if len(diffs) > 1 else "")
print(f"Median: {statistics.median(diffs):.4f}s")
print(f"Min: {min(diffs):.4f}s")
print(f"Max: {max(diffs):.4f}s")

print("\n--- Histogram (log-scale, decade-thirds) ---")
# Boundaries in seconds. Below 30 ms is collapsed since it's not actionable.
# Upper end auto-extends if max exceeds 30 s.
boundaries = [0.0, 0.030, 0.100, 0.300, 1.0, 3.0, 10.0, 30.0]
while boundaries[-1] < max(diffs):
    boundaries.append(boundaries[-1] * (10 / 3) if boundaries[-1] >= 1 else boundaries[-1] * 3)

def fmt_bound(s):
    if s < 1:
        return f"{int(round(s * 1000))}ms"
    return f"{s:g}s"

bins = [0] * (len(boundaries) - 1)
for d in diffs:
    for i in range(len(bins)):
        if boundaries[i] <= d < boundaries[i + 1]:
            bins[i] += 1
            break
    else:
        bins[-1] += 1  # >= last boundary

total = len(diffs)
max_count = max(bins) if bins else 1
for i, c in enumerate(bins):
    lo, hi = boundaries[i], boundaries[i + 1]
    label = f"{fmt_bound(lo)} - {fmt_bound(hi)}"
    flag = '!' if lo >= args.threshold else ' '
    bar = '#' * int(round(30 * c / max_count)) if max_count else ''
    pct = 100.0 * c / total if total else 0
    print(f"{flag} {label:>16} | {bar:<30} {c:>5} ({pct:5.1f}%)")


# --- Optional: fetch hub memory/CPU history -------------------------------
mem_samples = []  # list of (datetime, dict-of-cols)

def fetch_mem_history(opener, hub_ip, log_year):
    url = f"http://{hub_ip}/hub/advanced/freeOSMemoryHistory"
    try:
        with opener.open(url, timeout=10) as resp:
            text = resp.read().decode('utf-8', errors='replace')
    except (urllib.error.URLError, OSError) as e:
        print(f"  ! Failed to fetch {url}: {e}")
        return []
    lines = text.strip().splitlines()
    if not lines:
        return []
    header = [h.strip() for h in lines[0].split(',')]
    samples = []
    # The CSV uses MM-DD HH:MM:SS without a year. Stamp with the log's year, and
    # roll back one year if a sample's MM-DD is in the future relative to the log
    # end (handles year-boundary captures).
    for raw in lines[1:]:
        cols = [c.strip() for c in raw.split(',')]
        if len(cols) != len(header):
            continue
        try:
            t = datetime.strptime(f"{log_year}-{cols[0]}", '%Y-%m-%d %H:%M:%S')
        except ValueError:
            continue
        if t > end_time + timedelta(days=1):
            t = t.replace(year=log_year - 1)
        samples.append((t, dict(zip(header, cols))))
    return samples

boots = []  # list of datetime — probable boot completion times derived from mem-history gaps

def detect_boots(samples, gap_threshold_min=7):
    """Memory history is normally sampled every ~5 min. A gap larger than the
    threshold implies the hub was off (boot/restart). Return the timestamps
    when sampling resumed (= boot finished)."""
    found = []
    for i in range(1, len(samples)):
        gap_min = (samples[i][0] - samples[i - 1][0]).total_seconds() / 60.0
        if gap_min > gap_threshold_min:
            found.append(samples[i][0])
    return found

if args.hub:
    opener, hub_ip = open_hub_session(args.hub, args.username, args.password)
    display_host = '<hub>' if obf is not None else hub_ip
    print(f"\nFetching memory/CPU history from http://{display_host}/hub/advanced/freeOSMemoryHistory ...")
    mem_samples = fetch_mem_history(opener, hub_ip, start_time.year)
    if mem_samples:
        print(f"  Got {len(mem_samples)} samples ({mem_samples[0][0]} to {mem_samples[-1][0]})")

# In-band samples (written by ws_to_file.py) merge in: they're authoritative for
# the capture window since they were recorded live, while the API gives wider
# context. Dedupe on timestamp, preferring in-band where both exist.
if inband_mem_samples:
    print(f"In-band hubstat samples in logfile: {len(inband_mem_samples)}")
    by_t = {t: row for t, row in mem_samples}
    by_t.update({t: row for t, row in inband_mem_samples})
    mem_samples = sorted(by_t.items())

if mem_samples:
    boots = detect_boots(mem_samples)
    boots = [b for b in boots if start_time <= b <= end_time]
    print(f"  Detected boots within log timespan: {len(boots)}"
          + (f" ({', '.join(b.strftime('%Y-%m-%d %H:%M') for b in boots)})" if boots else ''))


# --- Temporal distribution of outliers -----------------------------------
def auto_bin_minutes(span_min, target_rows=60):
    for m in [15, 30, 60, 120, 180, 360, 720, 1440]:
        if span_min / m <= target_rows:
            return m
    return 1440

if outliers:
    print(f"\n--- Outlier timeline (events > {args.threshold:.2f}s, binned) ---")
    span_min = (end_time - start_time).total_seconds() / 60.0
    bin_min = auto_bin_minutes(span_min)
    bin_td = timedelta(minutes=bin_min)
    # Snap start to bin boundary
    bin_start = start_time.replace(minute=(start_time.minute // bin_min) * bin_min if bin_min < 60 else 0,
                                   second=0, microsecond=0)
    if bin_min >= 60:
        bin_start = bin_start.replace(hour=(start_time.hour // (bin_min // 60)) * (bin_min // 60))

    counts = {}
    for diff, _, _, t_idx, _ in outliers:
        t = entries[t_idx][0]
        bucket = bin_start + timedelta(minutes=((t - bin_start).total_seconds() // 60) // bin_min * bin_min)
        counts[bucket] = counts.get(bucket, 0) + 1

    boots_set = set(b.replace(second=0, microsecond=0) for b in boots)
    max_count = max(counts.values()) if counts else 1

    print(f"    Bin width: {bin_min} min   Total outliers: {len(outliers)}")
    cur = bin_start
    while cur < end_time:
        nxt = cur + bin_td
        c = counts.get(cur, 0)
        bar = '#' * int(round(30 * c / max_count)) if c else ''
        boot_in_bin = any(cur <= b < nxt for b in boots)
        marker = ' BOOT' if boot_in_bin else '     '
        print(f"  {cur.strftime('%Y-%m-%d %H:%M')}  | {bar:<30} {c:>3}{marker}")
        cur = nxt

    # Boot-relative summary
    if boots:
        for window_min in (10, 30, 60):
            n = 0
            for diff, _, _, t_idx, _ in outliers:
                t = entries[t_idx][0]
                if any(0 <= (t - b).total_seconds() / 60.0 <= window_min for b in boots):
                    n += 1
            pct = 100.0 * n / len(outliers)
            print(f"    Outliers within {window_min:>2} min after a boot: {n}/{len(outliers)} ({pct:.0f}%)")


def fmt_entry(idx, entry, marker=' ', delta=None):
    t, d = entry
    delta_str = '          ' if delta is None else f"+{delta:>7.3f}s "
    name = obf.alias(d.get('name')) if obf else d.get('name')
    name = html.unescape(name) if name else name
    raw_msg = d.get('msg', '')
    msg = obf.scrub(raw_msg) if obf else raw_msg
    msg = html.unescape(msg)
    return (f"{marker} [{idx:>6}] {t.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]} {delta_str}"
            f"type={d.get('type')!s:<3} id={d.get('id')!s:<5} "
            f"name={name!r} msg={msg!r}")


def print_mem_window(t_event, samples, window_min):
    if not samples:
        return
    lo = t_event - timedelta(minutes=window_min)
    hi = t_event + timedelta(minutes=window_min)
    nearby = [(t, row) for t, row in samples if lo <= t <= hi]
    if not nearby:
        # Fall back to the closest sample on either side so we still have *some*
        # signal even if the history hasn't caught up to the outlier yet.
        before = [s for s in samples if s[0] <= t_event]
        after  = [s for s in samples if s[0] >  t_event]
        nearby = []
        if before: nearby.append(before[-1])
        if after:  nearby.append(after[0])
    if not nearby:
        print("    (no memory samples found)")
        return
    print(f"    Memory/CPU samples within +/- {window_min} min of outlier:")
    cols = list(nearby[0][1].keys())
    data_cols = [c for c in cols if c != 'Date/time']
    header = "      " + "  ".join([f"{'Δ vs event':>11}", "Date/time         "] + [f"{c:>13}" for c in data_cols])
    print(header)
    for t, row in nearby:
        delta_min = (t - t_event).total_seconds() / 60.0
        marker = '*' if abs(delta_min) <= 5 else ' '
        cells = [f"{c:>13}" for c in (row.get(k, '') for k in data_cols)]
        print(f"    {marker} {delta_min:>+10.1f}m  {t.strftime('%Y-%m-%d %H:%M:%S')}  " + "  ".join(cells))


print(f"\n--- Outliers (delay > {args.threshold:.2f}s) ---")
print(f"Found: {len(outliers)}")

for n, (diff, app_id, app_name, trigger_idx, action_idx) in enumerate(outliers, 1):
    t_trigger = entries[trigger_idx][0]
    t_action = entries[action_idx][0]
    display_name = obf.alias(app_name) if obf else app_name
    print(f"\n=== Outlier #{n}: app id={app_id} name={display_name!r} delay={diff:.4f}s ===")
    print(f"    Trigger at {t_trigger}  Action at {t_action}")

    ctx_start = max(0, trigger_idx - CONTEXT_BEFORE)
    ctx_end = min(len(entries), action_idx + CONTEXT_AFTER + 1)

    prev_t = None
    for idx in range(ctx_start, ctx_end):
        if idx == trigger_idx:
            marker = '>'
        elif idx == action_idx:
            marker = '<'
        elif trigger_idx < idx < action_idx:
            marker = '*'
        else:
            marker = ' '
        delta = None if prev_t is None else (entries[idx][0] - prev_t).total_seconds()
        print(fmt_entry(idx, entries[idx], marker, delta))
        prev_t = entries[idx][0]

    in_gap = action_idx - trigger_idx - 1
    print(f"    Log entries inside the delay window: {in_gap}")

    if mem_samples:
        print_mem_window(t_trigger, mem_samples, args.mem_window)

if obf is not None and args.obfuscate_map:
    with open(args.obfuscate_map, 'w') as fh:
        json.dump(obf.map, fh, indent=2, ensure_ascii=False)
    print(f"\nObfuscation map written to {args.obfuscate_map} (keep this private)")
