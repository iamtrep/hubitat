#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# test-well-monitor-drivers — driver-contract smoke test
#
# Exercises the two test drivers used by the WellMonitor behavior test:
#   - drivers/tests/VirtualWellPumpSwitch.groovy
#   - drivers/tests/VirtualFlowMeter.groovy
#
# Verifies each command emits the expected attribute event. This isolates
# driver-fixture regressions from app behavior bugs — when the WellMonitor
# Mode 1 test fails, this script answers "is the fault in the app or in
# the driver fixture?"
#
# Reuses the WellMonitor test rig (test-well-monitor-maker, test-wm-pump,
# test-wm-meter). Run /hubitat-behavior-test apps/WellMonitor/tests/spec-
# well-monitor.yaml @<hub> first to provision the rig.
#
# Usage:
#   bash drivers/tests/test-well-monitor-drivers.sh                   # default hub
#   bash drivers/tests/test-well-monitor-drivers.sh @hubname          # specific hub
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
while [[ "$PROJECT_ROOT" != "/" && ! -f "$PROJECT_ROOT/.hubitat.json" ]]; do
    PROJECT_ROOT="$(dirname "$PROJECT_ROOT")"
done
if [[ ! -f "$PROJECT_ROOT/.hubitat.json" ]]; then
    echo "Could not find .hubitat.json walking up from $SCRIPT_DIR" >&2
    exit 2
fi
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/hubitat-driver-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    fi
done

python3 - "$HUB_NAME" "$CONFIG_FILE" "$COOKIE_JAR" "$PROJECT_ROOT" <<'PYTHON_SCRIPT'
import json, sys, re, time, urllib.request, urllib.parse, urllib.error, http.cookiejar

MAKER_API_LABEL  = "test-well-monitor-maker"
PUMP_LABEL       = "test-wm-pump"
METER_LABEL      = "test-wm-meter"
RUNTIME_BUDGET_SECONDS = 20

hub_name_arg, config_file, cookie_jar_path, project_root = sys.argv[1:5]

sys.path.insert(0, f"{project_root}/scripts/lib")
from eventsocket import EventCapture

GREEN = "\033[32m"; RED = "\033[31m"; YELLOW = "\033[33m"
CYAN  = "\033[36m"; DIM = "\033[2m"; RESET = "\033[0m"

passed = failed = warnings = 0
start_time = time.time()

def ok(msg):
    global passed; passed += 1
    print(f"  {GREEN}[PASS]{RESET} {msg}")

def fail(msg):
    global failed; failed += 1
    print(f"  {RED}[FAIL]{RESET} {msg}")

def warn(msg):
    global warnings; warnings += 1
    print(f"  {YELLOW}[WARN]{RESET} {msg}")

def info(msg):
    print(f"  {DIM}{msg}{RESET}")

def section(msg):
    print(f"\n{CYAN}--- {msg} ---{RESET}")

def die(msg, code=2):
    print(f"{RED}{msg}{RESET}")
    sys.exit(code)

# ── Load config & auth ────────────────────────────────────────────────
with open(config_file) as f:
    config = json.load(f)

hub_name = hub_name_arg or config.get("default_hub", "")
hub = config.get("hubs", {}).get(hub_name)
if not hub:
    die(f"Hub '{hub_name}' not found in .hubitat.json")

hub_ip   = hub["hub_ip"]
username = hub.get("username")
password = hub.get("password")

cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(path, timeout=15):
    url = f"http://{hub_ip}{path}"
    try:
        with opener.open(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

if username and password:
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
    except Exception as e:
        die(f"Hub auth failed: {e}")

info(f"Hub: {hub_name} ({hub_ip})")
info(f"Runtime budget: ~{RUNTIME_BUDGET_SECONDS}s")

# ── Discover Maker API + devices (reuses the WellMonitor rig) ─────────
section("Rig discovery")

apps_list = fetch("/hub2/appsList")
if not apps_list or "apps" not in apps_list:
    die("Could not fetch /hub2/appsList")

def walk_apps(entries, hits):
    for entry in entries:
        d = entry.get("data") or {}
        if isinstance(d, dict):
            hits.append(d)
        for child in entry.get("children") or []:
            walk_apps([child], hits)
    return hits

all_apps = walk_apps(apps_list["apps"], [])
maker_apps = [a for a in all_apps if a.get("name") == MAKER_API_LABEL]
if not maker_apps:
    die(f"Maker API '{MAKER_API_LABEL}' not found on {hub_name}. "
        f"Run: /hubitat-behavior-test apps/WellMonitor/tests/spec-well-monitor.yaml @{hub_name}")
maker_id = maker_apps[0]["id"]

cfg = fetch(f"/installedapp/configure/json/{maker_id}")
access_token = None
if cfg and "configPage" in cfg:
    for s in cfg["configPage"].get("sections") or []:
        for item in s.get("body") or []:
            for field in ("description", "url"):
                m = re.search(r'access_token=([a-f0-9-]+)', item.get(field, ""))
                if m:
                    access_token = m.group(1); break
            if access_token: break
        if access_token: break

if not access_token:
    die(f"No access_token on '{MAKER_API_LABEL}'. Is OAuth enabled?")

api_base = f"http://{hub_ip}/apps/api/{maker_id}"
ok(f"Maker API: '{MAKER_API_LABEL}' id={maker_id}")

def maker_get(path, timeout=15):
    sep = "&" if "?" in path else "?"
    url = f"{api_base}{path}{sep}access_token={access_token}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

def maker_send(device_id, command, args=None, timeout=15):
    path = f"/devices/{device_id}/{command}"
    if args:
        for a in args:
            path += f"/{urllib.parse.quote(str(a), safe='')}"
    url = f"{api_base}{path}?access_token={access_token}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False

devices_resp = maker_get("/devices")
if not isinstance(devices_resp, list):
    die("Maker API /devices returned unexpected payload")

label_to_id = {}
for d in devices_resp:
    label = d.get("label") or d.get("name")
    if label:
        label_to_id[label] = int(d.get("id"))

required = {PUMP_LABEL, METER_LABEL}
missing = required - set(label_to_id.keys())
if missing:
    die(f"Maker API '{MAKER_API_LABEL}' is missing devices: {sorted(missing)}. "
        f"Re-run /hubitat-behavior-test to wire the test rig.")

pump_id  = label_to_id[PUMP_LABEL]
meter_id = label_to_id[METER_LABEL]
ok(f"Devices: {PUMP_LABEL} id={pump_id}, {METER_LABEL} id={meter_id}")

# ── Driver-contract assertions ────────────────────────────────────────
# Each step:
#   1. Open EventCapture window
#   2. Send command via Maker API
#   3. wait_for matching event (attribute + value + source)
#   4. PASS/FAIL on whether the event arrived within the timeout

hub_user = hub.get("username"); hub_pw = hub.get("password")

def assert_event(case_name, device_id, command, args, expected):
    """expected = list of (attribute, value) tuples that must all arrive."""
    section(f"Case: {case_name}")
    with EventCapture(hub_ip=hub_ip, username=hub_user, password=hub_pw) as cap:
        if not maker_send(device_id, command, args=args):
            argstr = ('(' + ','.join(str(x) for x in args) + ')') if args else ''
            fail(f"Maker API call {command}{argstr} on dev={device_id} returned non-200")
            return
        argstr = ('(' + ','.join(str(x) for x in args) + ')') if args else ''
        info(f"sent: dev={device_id} ← {command}{argstr}")
        for (attr, value) in expected:
            evt = cap.wait_for(attribute=f"^{attr}$",
                               value=str(value),
                               source=device_id,
                               timeout=3.0)
            if evt is None:
                fail(f"no event {attr}={value} from dev={device_id} after {command}{argstr}")
            else:
                ok(f"event {attr}={value} fired")

# ── Pump driver ───────────────────────────────────────────────────────
# Note: virtual-driver events use isStateChange:true, so even a repeat
# value (e.g. setPower(0) when power is already 0) emits a fresh event.

assert_event("pump.setPower-on", pump_id, "setPower", [250],
             [("power", "250")])

assert_event("pump.setPower-off", pump_id, "setPower", [0],
             [("power", "0")])

assert_event("pump.on", pump_id, "on", None,
             [("switch", "on")])

# off() emits BOTH a switch=off AND a power=0 event — the latter is what
# trips WellMonitor's handlePumpStopped after an emergency shutoff.
assert_event("pump.off", pump_id, "off", None,
             [("switch", "off"), ("power", "0")])

# ── Flow meter driver ─────────────────────────────────────────────────
assert_event("meter.setRate-start", meter_id, "setRate", [7],
             [("rate", "7")])

assert_event("meter.setRate-stop", meter_id, "setRate", [0],
             [("rate", "0")])

assert_event("meter.setVolume", meter_id, "setVolume", [1234.5],
             [("volume", "1234.5")])

# ── Summary ───────────────────────────────────────────────────────────
elapsed = time.time() - start_time
section("Summary")
print(f"  {passed} passed, {failed} failed, {warnings} warnings  ({elapsed:.1f}s)")
if elapsed > RUNTIME_BUDGET_SECONDS:
    warn(f"runtime budget {RUNTIME_BUDGET_SECONDS}s exceeded ({elapsed:.1f}s)")

if failed > 0:
    sys.exit(1)
sys.exit(0)
PYTHON_SCRIPT
