#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Multi-Hub Inventory API Integration Test
#
# Tests the /api/peers and /api/peer proxy endpoints of a deployed
# Multi-Hub Inventory app instance.
#
# PREREQUISITES:
#   - The app must be deployed (MultiHubInventory.groovy pushed, OAuth enabled,
#     multi_hub_inventory_ui.html uploaded to File Manager) and configured with
#     at least one peer hub.
#   - Reads .hubitat.json from the repo root (three levels up from this script).
#     The path resolves correctly once this branch is merged to main.
#
# Usage:
#   bash tests/test-multi-hub-inventory-api.sh                    # default hub
#   bash tests/test-multi-hub-inventory-api.sh @hubname            # specific hub
#   bash tests/test-multi-hub-inventory-api.sh @hubname 247        # specific hub + instance
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# HUBITAT_CONFIG (absolute path) points the test at a different hub config file.
CONFIG_FILE="${HUBITAT_CONFIG:-$PROJECT_ROOT/.hubitat.json}"
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/hubitat-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT

# Parse arguments
HUB_NAME=""
INSTANCE_ID=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    elif [[ "$arg" =~ ^[0-9]+$ ]]; then
        INSTANCE_ID="$arg"
    fi
done

python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "$COOKIE_JAR" <<'PYTHON_SCRIPT'
import json, sys, re, os, urllib.request, urllib.parse, http.cookiejar

hub_name_arg = sys.argv[1]
instance_id_arg = sys.argv[2]
config_file = sys.argv[3]
cookie_jar_path = sys.argv[4]

# Colors
GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
CYAN   = "\033[36m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

passed = 0
failed = 0
warnings = 0

def ok(msg):
    global passed; passed += 1
    print(f"  {GREEN}[PASS]{RESET} {msg}")

def fail(msg):
    global failed; failed += 1
    print(f"  {RED}[FAIL]{RESET} {msg}")

def warn(msg):
    global warnings; warnings += 1
    print(f"  {YELLOW}[WARN]{RESET} {msg}")

def section(msg):
    print(f"\n{CYAN}--- {msg} ---{RESET}")

def info(msg):
    print(f"  {DIM}{msg}{RESET}")

# ── Step 1: Read config ──────────────────────────────────────────────
with open(config_file) as f:
    config = json.load(f)

hub_name = hub_name_arg or config.get("default_hub", "")
hub = config["hubs"].get(hub_name)
if not hub:
    print(f"{RED}Hub '{hub_name}' not found in .hubitat.json{RESET}")
    sys.exit(2)

hub_ip   = hub["hub_ip"]
username = hub.get("username")
password = hub.get("password")

# ── Step 2: Authenticate (hub security) ──────────────────────────────
cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(path, timeout=30):
    url = f"http://{hub_ip}{path}"
    try:
        resp = opener.open(url, timeout=timeout)
        return json.loads(resp.read().decode())
    except Exception:
        return None

def fetch_raw(url, timeout=30):
    try:
        req  = urllib.request.Request(url)
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:
        return 0, None

if username and password:
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
    except Exception as e:
        print(f"{RED}Authentication failed: {e}{RESET}")
        sys.exit(2)

# ── Step 3: Find instance and access token ───────────────────────────
instance_id = int(instance_id_arg) if instance_id_arg else None

if not instance_id:
    apps_list = fetch("/hub2/appsList")
    if apps_list and "apps" in apps_list:
        def find_instance(entries):
            for entry in entries:
                d = entry.get("data", {})
                if d and isinstance(d, dict):
                    if d.get("name", "") == "Multi-Hub Inventory":
                        return d.get("id")
                children = entry.get("children", [])
                if children:
                    result = find_instance(children)
                    if result:
                        return result
            return None
        instance_id = find_instance(apps_list["apps"])

if not instance_id:
    print(f"{RED}Could not find Multi-Hub Inventory instance on {hub_name}. Is it installed?{RESET}")
    sys.exit(2)

# Get access_token from the app's config page (embedded in href links)
config_page  = fetch(f"/installedapp/configure/json/{instance_id}")
access_token = None
if config_page and "configPage" in config_page:
    for s in config_page["configPage"].get("sections", []):
        for item in s.get("body", []):
            desc = item.get("description", "")
            url  = item.get("url", "")
            m = re.search(r'ui\.html\?access_token=([a-f0-9-]+)', desc)
            if not m:
                m = re.search(r'ui\.html\?access_token=([a-f0-9-]+)', url)
            if m:
                access_token = m.group(1)
                break
        if access_token:
            break

if not access_token:
    print(f"{RED}Could not find access_token. Is OAuth enabled and is the dashboard link present?{RESET}")
    sys.exit(2)

api_base = f"http://{hub_ip}/apps/api/{instance_id}"
info(f"API base: {api_base}")
info(f"Token:    {access_token[:8]}...")

AUTH = lambda: {"Authorization": f"Bearer {access_token}"}   # header, not ?access_token= — keeps the token out of access logs (scheme is case-sensitive)

def api_get(endpoint, timeout=30):
    """Fetch a JSON API endpoint with auth."""
    url = f"{api_base}/api/{endpoint}"
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=AUTH()), timeout=timeout)
        return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"_error": e.code}
    except Exception as e:
        return {"_error": str(e)}

def api_get_status(endpoint, timeout=30):
    """(HTTP status, JSON body) for a call that may answer with an error status."""
    url = f"{api_base}/api/{endpoint}"
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=AUTH()), timeout=timeout)
        return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return None, {"_error": str(e)}

def api_post(endpoint, timeout=30):
    """POST a JSON API endpoint with auth."""
    url = f"{api_base}/api/{endpoint}"
    try:
        req = urllib.request.Request(url, data=b"", method="POST", headers=AUTH())
        resp = urllib.request.urlopen(req, timeout=timeout)
        return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"_error": e.code}
    except Exception as e:
        return {"_error": str(e)}

hub_data     = fetch("/hub2/hubData")
gt_hub_name  = hub_data.get("name", "") if hub_data else hub_name
print(f"\n{BOLD}=== Multi-Hub Inventory API Test: {gt_hub_name} ==={RESET}")

# ── Test 1: /api/peers — structure + token leak ──────────────────────
section("/api/peers")

peers_resp = api_get("peers")
if "_error" in peers_resp:
    fail(f"Request failed: {peers_resp['_error']}")
    print(f"\n{RED}Cannot continue without /api/peers.{RESET}")
    sys.exit(1)

ok("/api/peers responds")

if "peers" in peers_resp and isinstance(peers_resp["peers"], list):
    ok("/api/peers returns a 'peers' list")
else:
    fail("/api/peers missing 'peers' list")

blob = json.dumps(peers_resp)
if "access_token" not in blob and "token" not in blob:
    ok("/api/peers omits tokens (no 'token'/'access_token' in response)")
else:
    fail("/api/peers leaks a token — response contains 'token' or 'access_token'")

peers = peers_resp.get("peers", [])
if len(peers) >= 1:
    ok(f"/api/peers has at least one configured peer ({len(peers)} found)")
else:
    fail("No peers configured — add at least one peer hub in the app settings first, then re-run")

# Spot-check peer shape (index + label; no token)
if peers:
    p = peers[0]
    for field in ["index", "label"]:
        if field in p:
            ok(f"peers[0] has '{field}' ({p[field]})")
        else:
            fail(f"peers[0] missing '{field}'")
    if "token" in p or "access_token" in p:
        fail("peers[0] exposes a token field")
    else:
        ok("peers[0] contains no token field")

# ── Test 2: op whitelist — reject unknown op ─────────────────────────
section("/api/peer — op whitelist")

status, bad_op = api_get_status("peer?hub=0&op=evil")
if status == 400 and bad_op.get("error") == "invalid op":
    ok("/api/peer rejects non-whitelisted op (op=evil → 400 {\"error\":\"invalid op\"})")
else:
    fail(f"/api/peer op whitelist not enforced — got HTTP {status}: {bad_op}")

# ── Test 3: unknown hub index ────────────────────────────────────────
section("/api/peer — unknown hub index")

status, bad_hub = api_get_status("peer?hub=99&op=data")
if status == 400 and bad_hub.get("error") == "unknown hub":
    ok("/api/peer rejects unknown hub index (hub=99 → 400 {\"error\":\"unknown hub\"})")
else:
    fail(f"/api/peer did not reject unknown hub index — got HTTP {status}: {bad_hub}")

# ── Test 4: data passthrough — the self-peer must return real data ───
# Peer 0 is this hub's own Hub Inspector. An error reply means the forwarder's
# success path (base URL, Bearer header, index mapping) is broken, so it fails.
section("/api/peer?hub=0&op=data")

LEAK_MARKERS = ["access_token", "Bearer"]

def check_no_leak(label, body):
    blob = json.dumps(body)
    hit = [m for m in LEAK_MARKERS if m in blob]
    if hit:
        fail(f"{label} body leaks {hit}: {blob[:200]}")
    else:
        ok(f"{label} body contains neither 'access_token' nor 'Bearer'")

status, data_resp = api_get_status("peer?hub=0&op=data", timeout=120)
if status != 200:
    fail(f"/api/peer?hub=0&op=data answered HTTP {status}: {json.dumps(data_resp)[:200]} (self-peer must return data; run a scan first if the peer says 'no scan')")
    check_no_leak("/api/peer?hub=0&op=data error", data_resp)
elif "allDevices" in data_resp or "status" in data_resp:
    ok("/api/peer?hub=0&op=data returns real data (HTTP 200)")
    if "allDevices" in data_resp:
        all_devs = data_resp["allDevices"] or {}
        info(f"  allDevices count: {len(all_devs)}")
        if isinstance(all_devs, dict) and len(all_devs) > 0:
            ok(f"allDevices is a non-empty map ({len(all_devs)} devices)")
            sample = next(iter(all_devs.values()))
            # Check raw fields the SPA's correlation functions depend on
            for field in ["appsUsingCount", "orphan", "dashboards", "lastActivityTimeMs"]:
                if field in sample:
                    ok(f"  allDevices sample has '{field}'")
                else:
                    warn(f"  allDevices sample missing '{field}' (SPA correlation may be incomplete)")
        else:
            fail("allDevices present but empty or not a map")
    check_no_leak("/api/peer?hub=0&op=data", data_resp)
else:
    fail(f"/api/peer?hub=0&op=data returned neither allDevices nor status: {json.dumps(data_resp)[:200]}")

# ── Test 5: op=start / op=status shapes and scanId validation ────────
# op=start begins (or joins, idempotently) a real scan on this hub's Hub Inspector.
section("/api/peer?hub=0&op=start|status")

def start_call():
    url = f"{api_base}/api/peer?hub=0&op=start"
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=AUTH()), timeout=60)
        return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return None, {"_error": str(e)}

st_status, st = start_call()
real_sid = None
if st_status != 200 or "error" in st:
    fail(f"op=start answered HTTP {st_status}: {json.dumps(st)[:200]}")
    check_no_leak("op=start error", st)
else:
    real_sid = st.get("scanId")
    if isinstance(real_sid, str) and re.fullmatch(r"[A-Za-z0-9_\-]+", real_sid):
        ok(f"op=start returns a URL-safe scanId ({real_sid})")
    else:
        fail(f"op=start scanId missing or not URL-safe: {json.dumps(st)[:200]}")
    if isinstance(st.get("total"), int) and st["total"] > 0:
        ok(f"op=start total is a positive int ({st['total']})")
    else:
        fail(f"op=start total missing or not a positive int: {json.dumps(st)[:200]}")
    if isinstance(st.get("alreadyRunning"), bool):
        ok(f"op=start alreadyRunning is a bool ({st['alreadyRunning']})")
    else:
        fail(f"op=start alreadyRunning missing or not a bool: {json.dumps(st)[:200]}")
    check_no_leak("op=start", st)

# status without scanId: latest known status
status, ss = api_get_status("peer?hub=0&op=status")
if status == 200 and isinstance(ss.get("status"), str) and isinstance(ss.get("processed"), int) and isinstance(ss.get("total"), int):
    ok(f"op=status shape ok (status={ss['status']}, {ss['processed']}/{ss['total']})")
    if real_sid and ss.get("scanId") == real_sid:
        ok("op=status (no scanId) reports the scan just started")
    elif real_sid:
        fail(f"op=status scanId {ss.get('scanId')} differs from the started scan {real_sid}")
else:
    fail(f"op=status shape wrong (HTTP {status}): {json.dumps(ss)[:200]}")
check_no_leak("op=status", ss)

# status with the real scanId
if real_sid:
    status, ss2 = api_get_status(f"peer?hub=0&op=status&scanId={real_sid}")
    if status == 200 and ss2.get("scanId") == real_sid and ss2.get("status") != "unknown":
        ok(f"op=status with the real scanId returns that scan (status={ss2.get('status')})")
    else:
        fail(f"op=status with the real scanId did not return the scan (HTTP {status}): {json.dumps(ss2)[:200]}")

# a well-formed but unknown scanId is forwarded and answered 'unknown'
status, ss3 = api_get_status("peer?hub=0&op=status&scanId=nosuchscan-123")
if status == 200 and ss3.get("status") == "unknown" and ss3.get("scanId") == "nosuchscan-123":
    ok("op=status with an unknown valid scanId is forwarded (status=unknown)")
else:
    fail(f"op=status with an unknown valid scanId not forwarded (HTTP {status}): {json.dumps(ss3)[:200]}")

# an invalid scanId must be dropped, not forwarded: the reply is the latest status,
# not an 'unknown' echo of the bad value.
bad_sid = urllib.parse.quote("x y;z&evil=1", safe="")
status, ss4 = api_get_status(f"peer?hub=0&op=status&scanId={bad_sid}")
if status == 200 and ss4.get("status") != "unknown" and ss4.get("scanId") != "x y;z&evil=1":
    ok(f"op=status ignores an invalid scanId (answers the latest status, status={ss4.get('status')})")
else:
    fail(f"op=status did not ignore an invalid scanId (HTTP {status}): {json.dumps(ss4)[:200]}")

# ── Test 6: other peers — a reply is data or a clean error body ──────
section("/api/peer — other peers (error body hygiene)")
for p in peers[1:]:
    idx = p.get("index")
    for op in ["status", "data"]:
        status, body = api_get_status(f"peer?hub={idx}&op={op}", timeout=120)
        label = f"peer {idx} ({p.get('label')}) op={op} HTTP {status}"
        blob = json.dumps(body)
        if "Exception" in blob or "Traceback" in blob or "at line" in blob:
            fail(f"{label} looks like a stack trace: {blob[:200]}")
        elif status in (400, 404, 502):
            if isinstance(body.get("error"), str):
                ok(f"{label} is a clean error body ({body['error']})")
            else:
                fail(f"{label} error without an 'error' string: {blob[:200]}")
        elif status == 200:
            ok(f"{label} returned data")
        else:
            fail(f"{label} unexpected: {blob[:200]}")
        check_no_leak(label, body)

# ── Test: /api/version/check ─────────────────────────────────────────
section("/api/version/check")
vc = api_get("version/check")
if "_error" in vc:
    fail(f"Request failed: {vc['_error']}")
elif "error" in vc:
    warn(f"version/check returned an error (GitHub unreachable?): {vc['error']}")
else:
    if vc.get("currentVersion"):
        ok(f"currentVersion present: {vc['currentVersion']}")
    else:
        fail("currentVersion missing")
    if "updateAvailable" in vc and isinstance(vc["updateAvailable"], bool):
        ok(f"updateAvailable is a bool: {vc['updateAvailable']}")
    else:
        fail("updateAvailable missing or not a bool")
    if "latestVersion" in vc:
        ok(f"latestVersion present: {vc['latestVersion']}")
    else:
        fail("latestVersion missing")

# ── Test: /api/ui/sync ───────────────────────────────────────────────
section("/api/ui/sync")
us = api_post("ui/sync")
if "_error" in us:
    fail(f"Request failed: {us['_error']}")
elif "success" not in us:
    fail(f"ui/sync response missing 'success': {json.dumps(us)[:200]}")
else:
    ok(f"ui/sync returned success={us['success']}")
    if us.get("version"):
        ok(f"ui/sync version present: {us['version']}")
    else:
        fail("ui/sync version missing")
    if us["success"] is True:
        ok("ui/sync success=true")
    else:
        fail(f"ui/sync success=false: {json.dumps(us)[:200]}")

# ── Summary ──────────────────────────────────────────────────────────
total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if warnings:
    print(f", {warnings} warnings", end="")
if failed:
    print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")

sys.exit(1 if failed else 0)
PYTHON_SCRIPT
