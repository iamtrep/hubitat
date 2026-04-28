
import argparse
import html
import json
import os
import random
import re
import sys
import urllib.parse
import urllib.request
import urllib.error
import http.cookiejar
from datetime import datetime, timedelta
import statistics

OUTLIER_THRESHOLD_SEC = 1.0
CONTEXT_BEFORE = 5      # log lines before the Triggered entry
CONTEXT_AFTER = 5       # log lines after the Action entry
MEM_WINDOW_MIN = 30     # show hub-memory samples within +/- this many minutes of the outlier

parser = argparse.ArgumentParser(description='Analyze Hubitat logsocket capture for slow Trigger->Action delays.')
parser.add_argument('logfile', nargs='?', default='maison-logsocket.json')
parser.add_argument('--hub', help='Hub name (looked up in .hubitat.json). If set, fetches /hub/advanced/freeOSMemoryHistory and shows samples around each outlier.')
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


def find_hub_config():
    """Walk up from the script location to find .hubitat.json."""
    d = os.path.dirname(os.path.abspath(__file__))
    while d != os.path.dirname(d):
        p = os.path.join(d, '.hubitat.json')
        if os.path.isfile(p):
            with open(p, 'r') as fh:
                return json.load(fh), p
        d = os.path.dirname(d)
    return None, None


def open_hub_session(hub_name):
    """Resolve hub config and (if hub-security is enabled) log in. Returns (opener, hub_ip)."""
    cfg, cfg_path = find_hub_config()
    if cfg is None:
        sys.exit("Could not find .hubitat.json")
    hubs = cfg.get('hubs', {})
    if hub_name not in hubs:
        sys.exit(f"Hub {hub_name!r} not found in {cfg_path}. Known: {', '.join(hubs)}")
    h = hubs[hub_name]
    hub_ip = h['hub_ip']
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    user, password = h.get('username'), h.get('password')
    if user and password:
        body = urllib.parse.urlencode({'username': user, 'password': password}).encode()
        req = urllib.request.Request(f"http://{hub_ip}/login", data=body)
        try:
            opener.open(req, timeout=10).read()
        except urllib.error.URLError as e:
            sys.exit(f"Login to {hub_name} ({hub_ip}) failed: {e}")
    return opener, hub_ip

# First pass: load every log entry into memory so we can pull surrounding context.
entries = []  # list of (datetime, dict)
with open(args.logfile, 'r') as f:
    for line in f:
        try:
            data = json.loads(line)
            time_str = data.get('time')
            if not time_str:
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

for idx, (current_time, data) in enumerate(entries):
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
print(f"Timespan: {start_time} to {end_time} ({duration})")
print(f"Total log entries: {len(entries)}")
print(f"Trigger->Action samples: {len(diffs)}")
print(f"Unique App IDs: {len(involved_apps)} ({', '.join(map(str, sorted(involved_apps)))})")
print(f"Mean: {statistics.mean(diffs):.4f}s")
print(f"Stdev: {statistics.stdev(diffs):.4f}s" if len(diffs) > 1 else "")
print(f"Median: {statistics.median(diffs):.4f}s")
print(f"Min: {min(diffs):.4f}s")
print(f"Max: {max(diffs):.4f}s")

print("\n--- Histogram ---")
num_bins = 10
mn, mx = min(diffs), max(diffs)
bin_width = (mx - mn) / num_bins
if bin_width == 0:
    print(f"{mn:.4f}s - {mx:.4f}s: {'#' * len(diffs)} ({len(diffs)})")
else:
    bins = [0] * num_bins
    for d in diffs:
        i = min(int((d - mn) / bin_width), num_bins - 1)
        bins[i] += 1
    for i in range(num_bins):
        s = mn + i * bin_width
        e = s + bin_width
        print(f"{s:.4f}s - {e:.4f}s | {'#' * bins[i]:<30} ({bins[i]})")


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

if args.hub and outliers:
    opener, hub_ip = open_hub_session(args.hub)
    display_host = '<hub>' if obf is not None else hub_ip
    print(f"\nFetching memory/CPU history from http://{display_host}/hub/advanced/freeOSMemoryHistory ...")
    mem_samples = fetch_mem_history(opener, hub_ip, start_time.year)
    if mem_samples:
        print(f"  Got {len(mem_samples)} samples ({mem_samples[0][0]} to {mem_samples[-1][0]})")


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
