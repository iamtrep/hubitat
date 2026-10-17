#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub with the Android TV Remote Tester installed and its TV selected
#
# Runs the Android TV Remote Tester through its endpoints and prints one line per test.
# A test where the driver didn't act on what the box sent is [FAIL]; one where only the box differs
# from what the driver assumes (a model quirk) is [WARN], as is a skipped test. Exit 1 only on [FAIL].
# Needs Script access turned on in the app.
#
# Usage:
#   bash drivers/androidtv/tests/test-live-tv.sh [@hub] [<instance-id>]            # test 1 only, about 15 s
#   RUN_SLOW_TESTS=1 bash drivers/androidtv/tests/test-live-tv.sh [@hub]           # tests 1-8 and 13, about 3 min
#   RUN_SLOW_TESTS=1 ATV_OPTS="standby=5&cut=true&wrongcode=true" bash ...          # opt-in tests too
# Upper bound: 20 min plus the standby minutes (a full run with every option takes about 7 min plus the standby).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="${HUBITAT_CONFIG:-$PROJECT_ROOT/.hubitat.json}"
HUB_NAME=""; INSTANCE_ID=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; elif [[ "$arg" =~ ^[0-9]+$ ]]; then INSTANCE_ID="$arg"; fi
done
python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "${RUN_SLOW_TESTS:-}" "${ATV_OPTS:-}" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.parse, urllib.request, http.cookiejar
hub_arg, inst_arg, config_file, slow, opts = sys.argv[1:6]
passed = failed = warnings = 0
def ok(m):
    global passed; passed += 1; print(f"  [PASS] {m}")
def fail(m):
    global failed; failed += 1; print(f"  [FAIL] {m}")
def warn(m):
    global warnings; warnings += 1; print(f"  [WARN] {m}")
def info(m): print(f"  [INFO] {m}")
def done(code):
    print(f"\n{passed} passed, {failed} failed, {warnings} warnings"); sys.exit(code)

try:
    config = json.load(open(config_file))
except Exception as e:
    print(f"cannot read {config_file}: {e}"); sys.exit(2)
hub = config["hubs"].get(hub_arg or config.get("default_hub", ""))
if not hub: print("hub not found in config"); sys.exit(2)
ip = hub["hub_ip"]
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def get(path, timeout=30):
    return json.loads(opener.open(f"http://{ip}{path}", timeout=timeout).read().decode())
try:
    if hub.get("username") and hub.get("password"):
        opener.open(f"http://{ip}/login", urllib.parse.urlencode({"username": hub["username"], "password": hub["password"]}).encode(), timeout=10)
    inst = int(inst_arg) if inst_arg else None
    if not inst:
        def find(entries):
            for e in entries:
                d = e.get("data") or {}
                if isinstance(d, dict) and d.get("type") == "Android TV Remote Tester": return d.get("id")
                r = find(e.get("children") or [])
                if r: return r
        inst = find(get("/hub2/appsList").get("apps", []))
    if not inst: print("Android TV Remote Tester is not installed"); sys.exit(2)
    page = json.dumps(get(f"/installedapp/configure/json/{inst}"))
    m = re.search(r"access_token=([a-f0-9-]+)", page)
    if not m: print("no access token: turn on Script access in the app"); sys.exit(2)
except SystemExit:
    raise
except Exception as e:
    print(f"hub unreachable: {e}"); sys.exit(2)

token = m.group(1)
base = f"http://{ip}/apps/api/{inst}"
def api(path, timeout=30):
    req = urllib.request.Request(base + path, headers={"Authorization": f"Bearer {token}"})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or "{}") | {"_status": e.code}

tests = ["1"]
if slow:
    tests = ["1", "2", "3", "4", "5", "6", "7", "8", "13"]
    tests += [n for flag, n in (("standby=", "9"), ("cut=true", "10"), ("wrongcode=true", "11")) if flag in opts]
q = f"/run?tests={','.join(tests)}" + (f"&{opts}" if slow and opts else "")
try:
    r = api(q)
    if r.get("_status") == 409: print("a run is already in progress"); sys.exit(2)
    if r.get("error"): print(f"run not started: {r['error']}"); sys.exit(2)
    deadline = time.time() + 20 * 60 + 60 * int((re.search(r"standby=(\d+)", opts or "") or [0, 0])[1] or 0)
    while time.time() < deadline:
        st = api("/status")
        if st.get("status") != "running": break
        time.sleep(10)
    else:
        api("/abort")   # leave nothing running, so the next invocation can start
        print("run did not finish in time; aborted it"); sys.exit(2)
    rep = api("/report")
except SystemExit:
    raise
except Exception as e:
    print(f"lost contact with the hub: {e}"); sys.exit(2)
info(f"run {rep.get('status')}" + (f": {rep.get('reason')}" if rep.get("reason") else ""))
for t in rep.get("results") or []:
    name = f"{t['test']}. {t['name']}"
    obs = " ".join(f"{k}={v}" for k, v in (t.get("observed") or {}).items())
    drv, box = t.get("driverDiffs") or [], t.get("boxDiffs") or []
    if t.get("skipped"): warn(f"{name}: skipped: {t['skipped']}")
    elif drv: fail(f"{name}: driver differs ({', '.join(drv)})" + (f"; box differs ({', '.join(box)})" if box else "") + f" {obs}")
    elif box: warn(f"{name}: box differs ({', '.join(box)}) {obs}")
    else: ok(name)
if rep.get("status") != "done":
    print(f"\nrun {rep.get('status')}: {rep.get('reason')}"); sys.exit(2)
done(1 if failed else 0)
PYTHON_SCRIPT
