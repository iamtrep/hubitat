#!/usr/bin/env bash
#
# scripts/lib/logsocket.py — integration test
#
# Verifies LogCapture against a live hub end-to-end:
#   1. matches / no_matches / count / wait_for over real /logsocket messages
#   2. regex pattern matching
#   3. level filter
#   4. source filter (by name)
#   5. clean teardown when used as a context manager
#
# Precondition: the SADC test rig (`apps/sensors/tests/test-sadc.sh`) must
# have been run at least once on the target hub to provision:
#   - test-sadc-maker (Maker API instance, OAuth on)
#   - test-sadc-in-1  (Virtual Contact Sensor)
#   - test-sadc-app   (Sensor Aggregator Discrete Child)
# The test exits 2 with a clear message if any of these are missing.
#
# Usage:
#   bash scripts/tests/test-logsocket.sh              # default hub
#   bash scripts/tests/test-logsocket.sh @maison-pro
#
# Runtime budget: ~10s.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    fi
done

python3 - "$HUB_NAME" "$CONFIG_FILE" "$PROJECT_ROOT" <<'PY'
import json, sys, re, time, urllib.request, urllib.parse, http.cookiejar

hub_name_arg, config_file, project_root = sys.argv[1:4]

sys.path.insert(0, f"{project_root}/scripts/lib")
from logsocket import LogCapture

G = "\033[32m"; R = "\033[31m"; Y = "\033[33m"; C = "\033[36m"; D = "\033[2m"; X = "\033[0m"
passed = failed = warnings = 0
def ok(m):    global passed; passed += 1; print(f"  {G}[PASS]{X} {m}")
def fail(m):  global failed; failed += 1; print(f"  {R}[FAIL]{X} {m}")
def warn(m):  global warnings; warnings += 1; print(f"  {Y}[WARN]{X} {m}")
def info(m):  print(f"  {D}{m}{X}")
def section(m): print(f"\n{C}--- {m} ---{X}")
def die(m, code=2):
    print(f"{R}{m}{X}"); sys.exit(code)

t0 = time.time()

# ── Config / auth ────────────────────────────────────────────────────
with open(config_file) as f:
    cfg = json.load(f)
hub_name = hub_name_arg or cfg.get("default_hub", "")
hub = cfg.get("hubs", {}).get(hub_name)
if not hub:
    die(f"Hub '{hub_name}' not found in .hubitat.json")
hub_ip = hub["hub_ip"]; username = hub.get("username"); password = hub.get("password")

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
if username and password:
    body = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try: opener.open(f"http://{hub_ip}/login", body, timeout=10)
    except Exception as e: die(f"Hub auth failed: {e}")

info(f"Hub: {hub_name} ({hub_ip})")

# ── Discover SADC rig ────────────────────────────────────────────────
section("Precondition: SADC rig present")
apps_list = json.loads(opener.open(f"http://{hub_ip}/hub2/appsList", timeout=10).read().decode())
def walk(entries, hits):
    for e in entries:
        d = e.get("data") or {}
        if isinstance(d, dict): hits.append(d)
        for c in e.get("children") or []: walk([c], hits)
    return hits
all_apps = walk(apps_list.get("apps", []), [])

maker = next((a for a in all_apps if a.get("name") == "test-sadc-maker"), None)
sadc  = next((a for a in all_apps if a.get("name") == "test-sadc-app"), None)
if not maker: die("test-sadc-maker not found. Run apps/sensors/tests/test-sadc.sh first.")
if not sadc:  die("test-sadc-app not found. Run apps/sensors/tests/test-sadc.sh first.")

# Maker API access token
cfg_page = json.loads(opener.open(f"http://{hub_ip}/installedapp/configure/json/{maker['id']}", timeout=10).read().decode())
token = None
for s in cfg_page["configPage"].get("sections") or []:
    for item in s.get("body") or []:
        for field in ("description", "url"):
            m = re.search(r'access_token=([a-f0-9-]+)', item.get(field, ""))
            if m: token = m.group(1); break
        if token: break
    if token: break
if not token: die("No access_token on test-sadc-maker.")

api = f"http://{hub_ip}/apps/api/{maker['id']}"
def maker_get(path):
    sep = "&" if "?" in path else "?"
    with urllib.request.urlopen(f"{api}{path}{sep}access_token={token}", timeout=10) as r:
        return json.loads(r.read().decode())
def maker_send(dev_id, cmd):
    with urllib.request.urlopen(f"{api}/devices/{dev_id}/{cmd}?access_token={token}", timeout=10) as r:
        return r.status == 200

devs = maker_get("/devices")
in1 = next((d for d in devs if (d.get("label") or d.get("name")) == "test-sadc-in-1"), None)
if not in1: die("test-sadc-in-1 not in Maker API. Re-run SADC test.")
ok(f"SADC rig found: app id={sadc['id']}, sensor id={in1['id']}, maker id={maker['id']}")

# Quiesce to known baseline before opening capture window.
maker_send(in1["id"], "close")
time.sleep(1)

# ── Test 1: LogCapture as context manager + wait_for ─────────────────
section("LogCapture context manager + wait_for")
sadc_label = "test-sadc-app"

with LogCapture(hub_ip=hub_ip, username=username, password=password) as cap:
    maker_send(in1["id"], "open")
    hit = cap.wait_for(r"contact.*open", level="info", source=sadc_label, timeout=5)
    if hit:
        ok(f"wait_for matched: {hit.get('msg')!r} from {hit.get('name')!r}")
    else:
        fail("wait_for timed out — no info-level 'contact*open' line from test-sadc-app")

    # Make sure we have *some* messages — proves the WS plumbing works
    n_total = len(cap.messages)
    if n_total >= 1: ok(f"captured {n_total} total log messages during window")
    else: fail("capture window produced zero messages")

# ── Test 2: assertion primitives over the captured set ───────────────
section("Assertion primitives after context exit")

# matches (positive)
if cap.matches(r"contact.*open", level="info", source=sadc_label):
    ok("matches(): info-level 'contact*open' from test-sadc-app present")
else:
    fail("matches() did not find the expected line")

# matches (negative — bogus pattern)
if not cap.matches(r"this-string-cannot-appear-anywhere-zzz"):
    ok("matches() correctly returns False for unmatched pattern")
else:
    fail("matches() returned True for a string that shouldn't exist")

# no_matches
if cap.no_matches(r"this-string-cannot-appear-anywhere-zzz"):
    ok("no_matches() correctly returns True for unmatched pattern")
else:
    fail("no_matches() returned False for absent pattern")

# count >= 1
n = cap.count(r"contact", source=sadc_label)
if n >= 1: ok(f"count() returned {n} for 'contact' from test-sadc-app")
else:      fail(f"count() returned {n}; expected >=1")

# level filter rejects non-matching level
if not cap.matches(r"contact.*open", level="error"):
    ok("level=error filter correctly excludes info-level matches")
else:
    fail("level=error filter incorrectly matched an info-level line")

# level list accepts either
if cap.matches(r"contact.*open", level=["warn", "info"]):
    ok("level=[warn,info] accepts info-level matches")
else:
    fail("level=[warn,info] did not match an info-level line")

# source filter by id (numeric)
if cap.matches(r"contact", source=sadc["id"]):
    ok(f"source=<int id={sadc['id']}> matches by app id")
else:
    fail(f"source=<int id={sadc['id']}> did not match an entry from the SADC app")

# source filter rejects wrong source
if not cap.matches(r"contact", source="some-app-that-does-not-exist"):
    ok("source filter correctly rejects non-matching name")
else:
    fail("source filter incorrectly matched the wrong app name")

# find_all returns a list and is consistent with count
hits = cap.find_all(r"contact", source=sadc_label)
if isinstance(hits, list) and len(hits) == cap.count(r"contact", source=sadc_label):
    ok(f"find_all() consistent with count() ({len(hits)} hits)")
else:
    fail("find_all() and count() disagree")

# ── Reset and summary ────────────────────────────────────────────────
maker_send(in1["id"], "close")  # idempotent teardown

elapsed = int(time.time() - t0)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s)")
sys.exit(0 if failed == 0 else 1)
PY
