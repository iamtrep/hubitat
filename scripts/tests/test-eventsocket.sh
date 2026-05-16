#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# scripts/lib/eventsocket.py — integration test
#
# Verifies EventCapture against a live hub end-to-end:
#   1. matches / no_matches / count / find_all / wait_for over real
#      /eventsocket messages
#   2. attribute regex filter
#   3. value filter (str + compiled regex)
#   4. source filter (by deviceId int AND by displayName regex)
#   5. descriptionText pattern filter
#   6. clean teardown when used as a context manager
#
# Precondition: the SADC test rig (`apps/sensors/tests/test-sadc.sh`) must
# have been run at least once on the target hub to provision:
#   - test-sadc-maker (Maker API instance, OAuth on)
#   - test-sadc-in-1  (Virtual Contact Sensor)
# The test exits 2 with a clear message if any of these are missing.
#
# Usage:
#   bash scripts/tests/test-eventsocket.sh              # default hub
#   bash scripts/tests/test-eventsocket.sh @maison-pro
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
from eventsocket import EventCapture

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
if not maker: die("test-sadc-maker not found. Run apps/sensors/tests/test-sadc.sh first.")

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
in1_id = int(in1["id"])
ok(f"SADC rig found: contact sensor id={in1_id}, maker id={maker['id']}")

# Quiesce to known baseline before opening capture.
maker_send(in1_id, "close")
time.sleep(1)

# ── Test 1: EventCapture as context manager + wait_for ───────────────
section("EventCapture context manager + wait_for")
sensor_label = "test-sadc-in-1"

with EventCapture(hub_ip=hub_ip, username=username, password=password) as cap:
    # Drive one open then one close so we have at least two distinct
    # events to filter on.
    maker_send(in1_id, "open")
    hit = cap.wait_for(attribute="contact", value="open", source=sensor_label, timeout=5)
    if hit:
        ok(f"wait_for matched contact=open from {hit.get('displayName')!r}")
    else:
        fail("wait_for timed out — no contact=open event from test-sadc-in-1")

    time.sleep(0.5)
    maker_send(in1_id, "close")
    hit = cap.wait_for(attribute="contact", value="closed", source=in1_id, timeout=5)
    if hit:
        ok(f"wait_for matched contact=closed by deviceId={in1_id}")
    else:
        fail(f"wait_for timed out — no contact=closed event from deviceId={in1_id}")

    n_total = len(cap.events)
    if n_total >= 2:
        ok(f"captured {n_total} total events during window")
    else:
        fail(f"capture window produced {n_total} events; expected >=2")

# ── Test 2: assertion primitives over the captured set ───────────────
section("Assertion primitives after context exit")

# matches (positive: by attribute + value + source name)
if cap.matches(attribute="contact", value="open", source=sensor_label):
    ok("matches(): contact=open from test-sadc-in-1 present")
else:
    fail("matches() did not find the expected event")

# matches (positive: by deviceId int)
if cap.matches(attribute="contact", source=in1_id):
    ok(f"matches(): source=<int id={in1_id}> matches by deviceId")
else:
    fail(f"matches() did not match by deviceId={in1_id}")

# matches (negative: bogus value)
if not cap.matches(attribute="contact", value="banana"):
    ok("matches() correctly rejects unknown value 'banana'")
else:
    fail("matches() incorrectly matched value='banana'")

# no_matches
if cap.no_matches(attribute="humidity", source=sensor_label):
    ok("no_matches(): no humidity events from a contact sensor")
else:
    fail("no_matches() returned False where it should be True")

# count >= 1 per direction
n_open = cap.count(attribute="contact", value="open", source=sensor_label)
n_closed = cap.count(attribute="contact", value="closed", source=sensor_label)
if n_open >= 1 and n_closed >= 1:
    ok(f"count() returned {n_open}× open + {n_closed}× closed from test-sadc-in-1")
else:
    fail(f"count() expected >=1 each direction; got open={n_open} closed={n_closed}")

# value filter: compiled regex
val_re = re.compile(r"^(open|closed)$")
if cap.count(attribute="contact", value=val_re, source=sensor_label) == n_open + n_closed:
    ok("value=<regex> matches both open and closed events")
else:
    fail("value=<regex> count disagrees with sum of open+closed")

# source by displayName regex partial match
if cap.matches(attribute="contact", source="sadc-in-1"):
    ok("source='sadc-in-1' (partial displayName regex) matches")
else:
    fail("source='sadc-in-1' did not match by displayName regex")

# source filter rejects unknown device
if not cap.matches(attribute="contact", source="no-such-device-zzz"):
    ok("source filter correctly rejects non-matching displayName")
else:
    fail("source filter incorrectly matched a nonexistent device")

# descriptionText pattern filter
if cap.matches(pattern=r"sadc-in-1.*open", source=sensor_label):
    ok("pattern=<regex> matches against descriptionText")
else:
    fail("pattern=<regex> did not match descriptionText")

# find_all consistent with count
hits = cap.find_all(attribute="contact", source=sensor_label)
expected = cap.count(attribute="contact", source=sensor_label)
if isinstance(hits, list) and len(hits) == expected:
    ok(f"find_all() consistent with count() ({len(hits)} hits)")
else:
    fail(f"find_all() and count() disagree: {len(hits)} vs {expected}")

# Every returned event has the expected shape
sample = hits[0] if hits else {}
for key in ("name", "value", "displayName", "deviceId", "descriptionText"):
    if key not in sample:
        warn(f"captured event missing key {key!r}: {sample}")

# ── Reset and summary ────────────────────────────────────────────────
maker_send(in1_id, "close")

elapsed = int(time.time() - t0)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s)")
sys.exit(0 if failed == 0 else 1)
PY
