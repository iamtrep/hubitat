#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

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
#   bash tests/test-multi-hub-inventory-api.sh @maison-pro        # specific hub
#   bash tests/test-multi-hub-inventory-api.sh @maison-pro 247    # specific hub + instance
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
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
            m = re.search(r'access_token=([a-f0-9-]+)', desc)
            if not m:
                m = re.search(r'access_token=([a-f0-9-]+)', url)
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

def api_get(endpoint, timeout=30):
    """Fetch a JSON API endpoint with auth."""
    sep = "&" if "?" in endpoint else "?"
    url = f"{api_base}/api/{endpoint}{sep}access_token={access_token}"
    try:
        resp = urllib.request.urlopen(url, timeout=timeout)
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

bad_op = api_get("peer?hub=0&op=evil")
if "_error" in bad_op:
    fail(f"Request failed: {bad_op['_error']}")
elif bad_op.get("error") == "invalid op":
    ok("/api/peer rejects non-whitelisted op (op=evil → {\"error\":\"invalid op\"})")
else:
    fail(f"/api/peer op whitelist not enforced — got: {bad_op}")

# ── Test 3: unknown hub index ────────────────────────────────────────
section("/api/peer — unknown hub index")

bad_hub = api_get("peer?hub=99&op=data")
if "_error" in bad_hub:
    fail(f"Request failed: {bad_hub['_error']}")
elif bad_hub.get("error") == "unknown hub":
    ok("/api/peer rejects unknown hub index (hub=99 → {\"error\":\"unknown hub\"})")
else:
    fail(f"/api/peer did not reject unknown hub index — got: {bad_hub}")

# ── Test 4: data passthrough — audit shape or clean error ────────────
section("/api/peer?hub=0&op=data")

data_resp = api_get("peer?hub=0&op=data", timeout=120)
if "_error" in data_resp:
    fail(f"Request failed (HTTP/network): {data_resp['_error']}")
else:
    ok("/api/peer?hub=0&op=data responds")

    if "allDevices" in data_resp:
        ok("/api/peer data response contains 'allDevices' (audit shape)")
        all_devs = data_resp["allDevices"] or {}
        info(f"  allDevices count: {len(all_devs)}")
        if all_devs:
            sample = next(iter(all_devs.values()))
            # Check raw fields the SPA's correlation functions depend on
            for field in ["appsUsingCount", "orphan", "dashboards", "lastActivityTimeMs"]:
                if field in sample:
                    ok(f"  allDevices sample has '{field}'")
                else:
                    warn(f"  allDevices sample missing '{field}' (SPA correlation may be incomplete)")
    elif "error" in data_resp:
        ok(f"/api/peer data response is a clean error (peer not yet scanned or unreachable): {data_resp['error']}")
        warn("Hub 0 returned an error — run a scan first (Rescan fleet in the SPA) for a full passthrough test")
    else:
        # Neither allDevices nor error — unexpected shape but not a stack trace
        body = json.dumps(data_resp)
        if "Exception" in body or "Traceback" in body or "at line" in body:
            fail(f"/api/peer data response looks like a stack trace — proxy error handling missing")
        else:
            warn(f"/api/peer data returned an unexpected shape (neither allDevices nor error): {body[:200]}")

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
