#!/usr/bin/env bash
#
# Hub Diagnostics API Validation Test
#
# Tests all JSON API endpoints, validates response structure, data integrity,
# cross-endpoint coherence, and OAuth enforcement. Compares API data against
# raw hub APIs (ground truth) and against the SPA-served HTML.
#
# Coverage current as of v5.13.0 (Phases 0–6 complete + snapshot enrichment).
# Tests every new field added since v5.8.5: firmwareUpdate, backups, security
# (incl. cloudController), cpuInfo, loadThreshold, radioHealth, zwaveJs, ntpServer,
# mdns, zipgatewayVersion, zigbeeChannelScan, zwaveTopologyHtml, snapshot.code,
# snapshot diff sections (backupsChanges/securityChanges/codeChanges + extended
# networkChanges), /api/code, /api/live cpuInfo+loadThreshold persistence
# (regression guard for the v5.11.2 chip-disappearing bug), /api/network/test
# (incl. server-side IPv4 validation), and /api/network/zigbee/scan caching.
#
# Usage:
#   bash tests/test-hub-diagnostics-api.sh                    # default hub
#   bash tests/test-hub-diagnostics-api.sh @maison-pro        # specific hub
#   bash tests/test-hub-diagnostics-api.sh @maison-pro 247    # specific hub + instance
#   RUN_SLOW_TESTS=1 bash tests/test-hub-diagnostics-api.sh   # also run the Zigbee scan (~30s)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
COOKIE_JAR="/tmp/hubitat_test_cookies"

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
import json, sys, re, os, urllib.request, urllib.parse, http.cookiejar, time

hub_name_arg = sys.argv[1]
instance_id_arg = sys.argv[2]
config_file = sys.argv[3]
cookie_jar_path = sys.argv[4]

# Colors
GREEN = "\033[32m"
RED = "\033[31m"
YELLOW = "\033[33m"
CYAN = "\033[36m"
BOLD = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"

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

hub_ip = hub["hub_ip"]
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
    except Exception as e:
        return None

def fetch_raw(url, timeout=30):
    try:
        req = urllib.request.Request(url)
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:
        return 0, None

def fetch_text(path, timeout=15):
    url = f"http://{hub_ip}{path}"
    try:
        resp = opener.open(url, timeout=timeout)
        return resp.read().decode()
    except Exception:
        return None

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
                    if d.get("type", "") == "Hub Diagnostics":
                        return d.get("id")
                children = entry.get("children", [])
                if children:
                    result = find_instance(children)
                    if result:
                        return result
            return None
        instance_id = find_instance(apps_list["apps"])

if not instance_id:
    print(f"{RED}Could not find Hub Diagnostics instance on {hub_name}. Is it installed?{RESET}")
    sys.exit(2)

# Get access_token from the app's config page (embedded in href links)
config_page = fetch(f"/installedapp/configure/json/{instance_id}")
access_token = None
if config_page and "configPage" in config_page:
    for s in config_page["configPage"].get("sections", []):
        for item in s.get("body", []):
            desc = item.get("description", "")
            url = item.get("url", "")
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
info(f"Token: {access_token[:8]}...")

def api_get(endpoint, timeout=60):
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

def api_post(endpoint, params="", timeout=60):
    """POST to a JSON API endpoint with auth."""
    sep = "&" if "?" in endpoint else "?"
    url = f"{api_base}/api/{endpoint}{sep}access_token={access_token}"
    if params:
        url += "&" + params
    try:
        req = urllib.request.Request(url, data=b"", method="POST")
        resp = urllib.request.urlopen(req, timeout=timeout)
        return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"_error": e.code}
    except Exception as e:
        return {"_error": str(e)}

# ── Step 4: Ground truth ─────────────────────────────────────────────
section("Ground Truth (raw hub APIs)")

devices_data = fetch("/hub2/devicesList")
gt_devices = 0
gt_disabled = 0
if devices_data and "devices" in devices_data:
    def count_devices(entries):
        global gt_devices, gt_disabled
        for entry in entries:
            dd = entry.get("data", {})
            if dd and isinstance(dd, dict):
                gt_devices += 1
                if dd.get("disabled"):
                    gt_disabled += 1
            children = entry.get("children", [])
            if children:
                count_devices(children)
    count_devices(devices_data["devices"])

apps_data = fetch("/hub2/appsList")
gt_apps = 0
gt_apps_user = 0
gt_apps_system = 0
gt_apps_user_disabled = 0
if apps_data and "apps" in apps_data:
    def count_apps(entries):
        global gt_apps, gt_apps_user, gt_apps_system, gt_apps_user_disabled
        for entry in entries:
            d = entry.get("data", {})
            if d and isinstance(d, dict):
                gt_apps += 1
                if d.get("user"):
                    gt_apps_user += 1
                    if d.get("disabled"):
                        gt_apps_user_disabled += 1
                else:
                    gt_apps_system += 1
            if entry.get("children"):
                count_apps(entry["children"])
    count_apps(apps_data["apps"])

hub_data = fetch("/hub2/hubData")
gt_hub_name = hub_data.get("name", "") if hub_data else ""
gt_firmware = hub_data.get("version", "") if hub_data else ""
gt_model = hub_data.get("model", "") if hub_data else ""

info(f"Hub: {gt_hub_name} | {gt_model} | fw {gt_firmware}")
info(f"Devices: {gt_devices} (disabled: {gt_disabled})")
info(f"Apps: {gt_apps} (user: {gt_apps_user}, system: {gt_apps_system})")

print(f"\n{BOLD}=== Hub Diagnostics API Validation: {gt_hub_name} ({gt_model}) ==={RESET}")

# ── Test 1: OAuth ─────────────────────────────────────────────────────
section("OAuth Security")

status, _ = fetch_raw(f"{api_base}/api/dashboard")
if status == 401:
    ok("Unauthenticated request returns 401")
else:
    fail(f"Unauthenticated request returned {status} (expected 401)")

status, _ = fetch_raw(f"{api_base}/api/dashboard?access_token=bad-token-12345")
if status == 401:
    ok("Invalid token returns 401")
else:
    fail(f"Invalid token returned {status} (expected 401)")

# ── Test 2: GET /api/dashboard ────────────────────────────────────────
section("GET /api/dashboard")

dash = api_get("dashboard")
if "_error" in dash:
    fail(f"Request failed: {dash['_error']}")
else:
    ok("Endpoint responds")

    # Structure
    for key in ["hub", "devices", "apps", "resources"]:
        if key in dash:
            ok(f"Has '{key}' key")
        else:
            fail(f"Missing '{key}' key")

    # Hub info
    h = dash.get("hub", {})
    if h.get("name") == gt_hub_name:
        ok(f"Hub name matches ({h['name']})")
    else:
        fail(f"Hub name '{h.get('name')}' != '{gt_hub_name}'")
    if h.get("firmware") == gt_firmware:
        ok(f"Firmware matches ({h['firmware']})")
    else:
        fail(f"Firmware '{h.get('firmware')}' != '{gt_firmware}'")

    # Device counts
    dv = dash.get("devices", {})
    if dv.get("total") == gt_devices:
        ok(f"Device count matches ({dv['total']})")
    else:
        fail(f"Device count {dv.get('total')} != {gt_devices}")
    if dv.get("disabled") == gt_disabled:
        ok(f"Disabled count matches ({dv['disabled']})")
    else:
        fail(f"Disabled count {dv.get('disabled')} != {gt_disabled}")

    # App count
    ap = dash.get("apps", {})
    if ap.get("total") == gt_apps:
        ok(f"App count matches ({ap['total']})")
    else:
        fail(f"App count {ap.get('total')} != {gt_apps}")

    # Resources
    res = dash.get("resources", {})
    if res.get("freeOSMemory") and res["freeOSMemory"] > 0:
        ok(f"Free OS Memory present ({res['freeOSMemory']} KB)")
    else:
        fail("Missing or zero freeOSMemory")

    # v5.9.0+ firmware-update badge data
    if "firmwareUpdate" in dash:
        ok("Has 'firmwareUpdate' field")
        fu = dash["firmwareUpdate"]
        if fu is None:
            warn("firmwareUpdate is null (cloud check may have failed; benign)")
        else:
            for field in ["currentVersion", "availableVersion", "updateAvailable", "status"]:
                if field in fu:
                    ok(f"firmwareUpdate has '{field}'")
                else:
                    fail(f"firmwareUpdate missing '{field}'")
            if fu.get("currentVersion") and fu["currentVersion"] != gt_firmware:
                fail(f"firmwareUpdate.currentVersion '{fu['currentVersion']}' != hub firmware '{gt_firmware}'")
    else:
        fail("Missing 'firmwareUpdate' field (v5.9.0+)")

# ── Test 3: GET /api/devices ──────────────────────────────────────────
section("GET /api/devices")

devs = api_get("devices")
if "_error" in devs:
    fail(f"Request failed: {devs['_error']}")
else:
    ok("Endpoint responds")

    rows = devs.get("deviceRows", [])
    if len(rows) == gt_devices:
        ok(f"Device row count matches ({len(rows)})")
    else:
        fail(f"Device rows {len(rows)} != {gt_devices}")

    # Spot check structure (v5.13: deviceRows now use connectionType + integration, not protocol)
    if rows:
        r = rows[0]
        for field in ["id", "name", "type", "connectionType", "integration", "status"]:
            if field in r:
                ok(f"Device row has '{field}' field")
            else:
                fail(f"Device row missing '{field}' field")

    # Connection-type counts should sum to total (replaces obsolete byProtocol)
    bc = devs.get("byConnectionType", {})
    conn_sum = sum(bc.values())
    if conn_sum == gt_devices:
        ok(f"Connection-type counts sum to total ({conn_sum})")
    else:
        fail(f"Connection-type counts sum {conn_sum} != {gt_devices}")

    # Integration counts should also sum to total
    bi = devs.get("byIntegration", {})
    integ_sum = sum(bi.values())
    if integ_sum == gt_devices:
        ok(f"Integration counts sum to total ({integ_sum})")
    else:
        fail(f"Integration counts sum {integ_sum} != {gt_devices}")

    # Device type breakdown
    bt = devs.get("byType", {})
    if bt and isinstance(bt, dict):
        ok(f"byType present ({len(bt)} types)")
        type_sum = sum(bt.values())
        if type_sum == gt_devices:
            ok(f"byType counts sum to total ({type_sum})")
        else:
            fail(f"byType counts sum {type_sum} != {gt_devices}")
    else:
        fail("Missing or empty byType")

    it = devs.get("idsByType", {})
    if it and isinstance(it, dict):
        ok(f"idsByType present ({len(it)} types)")
        id_sum = sum(len(v) for v in it.values())
        if id_sum == gt_devices:
            ok(f"idsByType IDs sum to total ({id_sum})")
        else:
            fail(f"idsByType IDs sum {id_sum} != {gt_devices}")
        if set(bt.keys()) == set(it.keys()):
            ok("idsByType keys match byType keys")
        else:
            fail(f"idsByType keys mismatch: {set(bt.keys()) - set(it.keys())} / {set(it.keys()) - set(bt.keys())}")
    else:
        fail("Missing or empty idsByType")

    # Summary
    s = devs.get("summary", {})
    if s.get("totalDevices") == gt_devices:
        ok(f"Summary totalDevices matches ({s['totalDevices']})")
    else:
        fail(f"Summary totalDevices {s.get('totalDevices')} != {gt_devices}")

    # Regression guard for v5.13.1 fix (#6): childIds must contain ONLY child device IDs,
    # not parents+children. Pre-fix, getDevicesData concatenated parentIds + childIds,
    # so the "Child" metric link on the Devices tab navigated to the wrong set.
    parent_ids = set(s.get("parentIds") or [])
    child_ids  = set(s.get("childIds")  or [])
    if len(child_ids) == s.get("childDevices", 0):
        ok(f"childIds count == childDevices ({len(child_ids)}) — no parent IDs leaked in")
    else:
        fail(f"childIds count {len(child_ids)} != childDevices {s.get('childDevices')} (regression of #6)")
    overlap = parent_ids & child_ids
    if not overlap:
        ok("childIds and parentIds are disjoint (regression guard for #6)")
    else:
        fail(f"childIds includes {len(overlap)} parent IDs — regression of v5.13.1 fix #6")

# ── Test 4: GET /api/apps ─────────────────────────────────────────────
section("GET /api/apps")

apps = api_get("apps")
if "_error" in apps:
    fail(f"Request failed: {apps['_error']}")
else:
    ok("Endpoint responds")

    s = apps.get("summary", {})
    if s.get("totalApps") == gt_apps:
        ok(f"Total apps matches ({s['totalApps']})")
    else:
        fail(f"Total apps {s.get('totalApps')} != {gt_apps}")
    if s.get("userApps") == gt_apps_user:
        ok(f"User apps matches ({s['userApps']})")
    else:
        fail(f"User apps {s.get('userApps')} != {gt_apps_user}")
    if s.get("builtInApps") == gt_apps_system:
        ok(f"System apps matches ({s['builtInApps']})")
    else:
        fail(f"System apps {s.get('builtInApps')} != {gt_apps_system}")

    # Regression guard for v5.13.1 fix (#7): the userApps row's `disabled` field is set from
    # `it.disabled ?: false`. Pre-fix, code read `it.state == "disabled"` against a Map that
    # has no `state` key, so `disabled` was ALWAYS false even for actually-disabled apps,
    # and the "Disabled" badge on the Apps tab never appeared.
    user_app_rows = apps.get("userApps") or []
    api_disabled_count = sum(1 for a in user_app_rows if a.get("disabled") is True)
    if api_disabled_count == gt_apps_user_disabled:
        if gt_apps_user_disabled == 0:
            ok("Disabled-app count matches ground truth (0 disabled — both 0; can't fully exercise but no false positive)")
        else:
            ok(f"Disabled-app count matches ground truth ({api_disabled_count}) — fix #7 working")
    else:
        fail(f"Disabled-app count {api_disabled_count} != ground truth {gt_apps_user_disabled} (regression of #7)")

    # Namespace breakdown
    ns = apps.get("byNamespace", {})
    if ns and isinstance(ns, dict):
        ok(f"Namespace breakdown present ({len(ns)} types)")
    else:
        fail("Missing or empty namespace breakdown")

    # Platform apps
    plat = apps.get("platformApps", [])
    if plat and isinstance(plat, list):
        ok(f"Platform apps present ({len(plat)} entries)")
    else:
        warn("No platform apps (may be expected if runtime data unavailable)")

# ── Test 5: GET /api/network ──────────────────────────────────────────
section("GET /api/network")

net = api_get("network")
if "_error" in net:
    fail(f"Request failed: {net['_error']}")
else:
    ok("Endpoint responds")

    for key in ["network", "zwave", "zigbee"]:
        if key in net:
            ok(f"Has '{key}' section")
        else:
            fail(f"Missing '{key}' section")

    # Network config
    nc = net.get("network", {})
    if nc.get("lanAddr") or nc.get("staticIP"):
        addr = nc.get("lanAddr") or nc.get("staticIP")
        ok(f"IP address present ({addr})")
    else:
        fail("Missing IP address")

    # Z-Wave
    zw = net.get("zwave", {})
    if zw.get("mesh") and isinstance(zw["mesh"].get("nodes"), list):
        ok(f"Z-Wave mesh nodes present ({len(zw['mesh']['nodes'])})")
    else:
        warn("No Z-Wave mesh nodes (may be expected if no Z-Wave)")

    # Zigbee
    zb = net.get("zigbee", {})
    if zb.get("raw") or zb.get("mesh"):
        ok("Zigbee data present")
    else:
        warn("No Zigbee data")

    # v5.9.0+ radio health badges
    if "radioHealth" in net:
        ok("Has 'radioHealth' field")
        rh = net["radioHealth"]
        if rh.get("supported"):
            ok(f"radioHealth: zwave={rh.get('zwave')} zigbee={rh.get('zigbee')}")
        else:
            warn(f"radioHealth not supported (firmware below {rh.get('minFirmware', '?')})")
    else:
        fail("Missing 'radioHealth' field (v5.9.0+)")

    # v5.9.0+ Z-Wave JS controller (only when JS stack is detected)
    if "zwaveJs" in net:
        ok("Has 'zwaveJs' field")
        zj = net["zwaveJs"]
        if zj is None:
            info("zwaveJs is null (legacy Z/IP stack or detection failed)")
        else:
            for field in ["firmwareVersion", "homeId", "ownNodeId", "statistics"]:
                if field in zj:
                    ok(f"zwaveJs has '{field}'")
                else:
                    fail(f"zwaveJs missing '{field}'")
    else:
        fail("Missing 'zwaveJs' field (v5.9.0+)")

    # v5.9.0+ NTP server display
    if "ntpServer" in net:
        ok(f"Has 'ntpServer' field (value: {net['ntpServer'] or '(not configured)'})")
    else:
        fail("Missing 'ntpServer' field (v5.9.0+)")

    # v5.9.1+ mDNS discovery
    if "mdns" in net:
        ok("Has 'mdns' field")
        md = net["mdns"]
        if md is None:
            warn("mdns is null (endpoint failed)")
        else:
            for field in ["totalEndpoints", "totalServiceTypes", "endpoints"]:
                if field in md:
                    ok(f"mdns has '{field}'")
                else:
                    fail(f"mdns missing '{field}'")
    else:
        fail("Missing 'mdns' field (v5.9.1+)")

    # v5.11.1+ Zip Gateway version
    if "zipgatewayVersion" in net:
        ok(f"Has 'zipgatewayVersion' field (value: {net.get('zipgatewayVersion') or '(none)'})")
    else:
        fail("Missing 'zipgatewayVersion' field (v5.11.1+)")

    # v5.11.3+ security (was on Health, moved to Network)
    if "security" in net:
        ok("Has 'security' field")
        sec = net["security"] or {}
        if "limitedAccess" in sec:
            ok(f"security.limitedAccess.enabled = {sec['limitedAccess'].get('enabled')}")
        else:
            fail("security missing 'limitedAccess'")
        for field in ["allowedSubnets", "dnsFallback", "cloudController"]:
            if field in sec:
                ok(f"security has '{field}' (value: {sec[field]})")
            else:
                fail(f"security missing '{field}'")
    else:
        fail("Missing 'security' field (v5.11.3+)")

    # v5.12.0+ Zigbee channel scan cache (may be null if never scanned)
    if "zigbeeChannelScan" in net:
        ok("Has 'zigbeeChannelScan' field")
        sc = net["zigbeeChannelScan"]
        if sc is None:
            info("zigbeeChannelScan cache is empty — POST /api/network/zigbee/scan to populate")
        else:
            for field in ["at", "results"]:
                if field in sc:
                    ok(f"zigbeeChannelScan has '{field}'")
                else:
                    fail(f"zigbeeChannelScan missing '{field}'")
    else:
        fail("Missing 'zigbeeChannelScan' field (v5.12.0+)")

    # v5.12.0+ Z-Wave topology HTML embed
    if "zwaveTopologyHtml" in net:
        ok("Has 'zwaveTopologyHtml' field")
        topo = net["zwaveTopologyHtml"]
        if topo and "<table" in topo:
            ok("zwaveTopologyHtml contains <table> markup")
        elif topo is None:
            warn("zwaveTopologyHtml is null (endpoint failed)")
        else:
            fail("zwaveTopologyHtml present but has no <table>")
    else:
        fail("Missing 'zwaveTopologyHtml' field (v5.12.0+)")

# ── Test 6: GET /api/health ───────────────────────────────────────────
section("GET /api/health")

health = api_get("health")
if "_error" in health:
    fail(f"Request failed: {health['_error']}")
else:
    ok("Endpoint responds")

    for key in ["hub", "resources", "alerts"]:
        if key in health:
            ok(f"Has '{key}' key")
        else:
            fail(f"Missing '{key}' key")

    h = health.get("hub", {})
    if h.get("name") == gt_hub_name:
        ok(f"Hub name matches ({h['name']})")
    else:
        fail(f"Hub name '{h.get('name')}' != '{gt_hub_name}'")

    res = health.get("resources", {})
    if res.get("freeOSMemory") and res["freeOSMemory"] > 0:
        ok(f"Free OS Memory: {res['freeOSMemory']} KB")
    else:
        fail("Missing or zero freeOSMemory")

    alerts = health.get("alerts", [])
    ok(f"Alerts: {len(alerts)} active")

    # v5.11.1+ CPU info chips
    if "cpuInfo" in health:
        ok("Has 'cpuInfo' field")
        ci = health["cpuInfo"]
        if ci and "processors" in ci and "loadAverage" in ci:
            ok(f"cpuInfo: processors={ci['processors']} loadAvg={ci['loadAverage']}")
        elif ci is None:
            warn("cpuInfo is null (endpoint failed)")
        else:
            fail(f"cpuInfo missing processors or loadAverage: {ci}")
    else:
        fail("Missing 'cpuInfo' field (v5.11.1+)")

    # v5.9.0+ load threshold
    if "loadThreshold" in health:
        lt = health["loadThreshold"]
        if isinstance(lt, int) and 1 <= lt <= 100:
            ok(f"loadThreshold = {lt}%")
        elif lt is None:
            warn("loadThreshold is null")
        else:
            fail(f"loadThreshold has unexpected value: {lt!r}")
    else:
        fail("Missing 'loadThreshold' field (v5.9.0+)")

    # v5.9.0+ backups card data
    if "backups" in health:
        ok("Has 'backups' field")
        bk = health["backups"] or {}
        if "local" in bk and isinstance(bk["local"], dict):
            ok(f"backups.local: count={bk['local'].get('count')} latest={bk['local'].get('latestCreateTime')}")
            # v5.11.2+ ISO timestamp for client-side date math
            if "latestCreateTimeOrig" in bk["local"]:
                ok("backups.local has 'latestCreateTimeOrig' (ISO format for diff math)")
            else:
                fail("backups.local missing 'latestCreateTimeOrig' (v5.11.2+)")
        else:
            fail("backups missing 'local' object")
        if "cloud" in bk and isinstance(bk["cloud"], dict):
            cl = bk["cloud"]
            ok(f"backups.cloud: thisHubCount={cl.get('thisHubCount')} otherHubCount={cl.get('otherHubCount')}")
            for field in ["hasCloudBackupEntitlements", "hasCloudRestoreEntitlements"]:
                if field in cl:
                    ok(f"backups.cloud has '{field}'")
                else:
                    fail(f"backups.cloud missing '{field}'")
        else:
            fail("backups missing 'cloud' object")
    else:
        fail("Missing 'backups' field (v5.9.0+)")

# ── Test 7: GET /api/health/history ───────────────────────────────────
section("GET /api/health/history")

hist = api_get("health/history")
if "_error" in hist:
    fail(f"Request failed: {hist['_error']}")
else:
    ok("Endpoint responds")
    pts = hist.get("dataPoints", [])
    if pts and isinstance(pts, list):
        ok(f"History data points: {len(pts)}")
        p = pts[-1]
        for field in ["time", "freeOS", "freeJava", "cpuLoad"]:
            if field in p:
                ok(f"Data point has '{field}'")
            else:
                fail(f"Data point missing '{field}'")
            break  # check first point only
    else:
        warn("No history data points (may be expected on fresh install)")

# ── Test 8: GET /api/performance ──────────────────────────────────────
section("GET /api/performance")

perf = api_get("performance")
if "_error" in perf:
    fail(f"Request failed: {perf['_error']}")
else:
    ok("Endpoint responds")

    for key in ["stats", "resources", "checkpointCount"]:
        if key in perf:
            ok(f"Has '{key}' key")
        else:
            fail(f"Missing '{key}' key")

    stats = perf.get("stats", {})
    if stats.get("uptime"):
        ok(f"Uptime: {stats['uptime']}")
    else:
        warn("No uptime in stats")

# ── Test 9: GET /api/snapshots ────────────────────────────────────────
section("GET /api/snapshots")

snaps = api_get("snapshots")
if "_error" in snaps:
    fail(f"Request failed: {snaps['_error']}")
else:
    ok("Endpoint responds")
    sc = snaps.get("snapshotCount", 0)
    ok(f"Snapshot count: {sc}")

    if sc > 0:
        s = snaps["snapshots"][0]
        for field in ["timestamp", "devices", "apps", "hubInfo"]:
            if field in s:
                ok(f"Snapshot has '{field}'")
            else:
                fail(f"Snapshot missing '{field}'")
            break

# ── Test 10: GET /api/snapshot/view ───────────────────────────────────
section("GET /api/snapshot/view")

if snaps.get("snapshotCount", 0) > 0:
    sv = api_get("snapshot/view?index=0")
    if "_error" in sv:
        fail(f"Request failed: {sv['_error']}")
    elif sv.get("error"):
        fail(f"API error: {sv['error']}")
    else:
        ok("Endpoint responds")
        for key in ["timestamp", "hubInfo", "devices", "apps"]:
            if key in sv:
                ok(f"Has '{key}'")
            else:
                fail(f"Missing '{key}'")
        # Check app sub-fields
        ap = sv.get("apps", {})
        if "userAppsList" in ap:
            ok(f"Has userAppsList ({len(ap['userAppsList'])} entries)")
        else:
            fail("Missing userAppsList in snapshot view")
        if "parentChildHierarchy" in ap:
            ok(f"Has parentChildHierarchy ({len(ap['parentChildHierarchy'])} parents)")
        else:
            fail("Missing parentChildHierarchy in snapshot view")

        # v5.13.0+ new top-level snapshot fields
        for field in ["backups", "security", "ntpServer", "loadThreshold", "code"]:
            if field in sv:
                ok(f"Has v5.13.0 field '{field}'")
            else:
                warn(f"Missing v5.13.0 field '{field}' (snapshot may pre-date 5.13.0)")
        # Code subfields
        code = sv.get("code")
        if code:
            for sub in ["bundles", "libraries", "hubVariables"]:
                if sub in code:
                    ok(f"code.{sub}: {len(code[sub])} entries")
                else:
                    fail(f"code missing '{sub}'")
            # Hub variables MUST NOT capture value (only name+type) — privacy/diff-noise rule
            hv = code.get("hubVariables") or []
            if hv:
                if "value" in hv[0]:
                    fail("code.hubVariables captures 'value' — should be name+type only to avoid diff churn")
                else:
                    ok("code.hubVariables omits 'value' (correct — automation churn would generate diff noise)")
else:
    warn("No snapshots to view — skipping")

# ── Test 11: GET /api/snapshot/diff ───────────────────────────────────
section("GET /api/snapshot/diff")

if snaps.get("snapshotCount", 0) >= 2:
    diff = api_get("snapshot/diff?older=1&newer=0")
    if "_error" in diff:
        fail(f"Request failed: {diff['_error']}")
    elif diff.get("error"):
        fail(f"API error: {diff['error']}")
    else:
        ok("Endpoint responds (two snapshots)")
        for key in ["older", "newer", "deviceChanges"]:
            if key in diff:
                ok(f"Has '{key}'")
            else:
                fail(f"Missing '{key}'")

        dc = diff.get("deviceChanges", {})
        for key in ["added", "removed", "changed"]:
            if key in dc:
                ok(f"Has deviceChanges '{key}'")
            else:
                fail(f"Missing deviceChanges '{key}'")

        # v5.13.0+ new diff sections (always present in payload, may be null)
        for key in ["backupsChanges", "securityChanges", "codeChanges"]:
            if key in diff:
                ok(f"Has v5.13.0 diff field '{key}' (value: {'null' if diff[key] is None else 'present'})")
            else:
                fail(f"Missing v5.13.0 diff field '{key}'")

        # Two snapshots taken back-to-back with no real changes should produce no false-positive
        # diffs in any of the v5.13 sections. (Older snapshot may pre-date v5.13.0 — in that
        # case all four sections are guarded to None by the containsKey/null-Map checks.)
        unchanged_sections = sum(1 for k in ["backupsChanges", "securityChanges", "codeChanges"] if diff.get(k) is None)
        nc_v513 = (diff.get("networkChanges") or {})
        v513_net_keys = [k for k in ("ntpServer", "loadThreshold") if k in nc_v513]
        if unchanged_sections == 3 and not v513_net_keys:
            ok("No v5.13 false-positive diffs between back-to-back snapshots (guards working)")
        elif v513_net_keys:
            warn(f"v5.13 networkChanges populated unexpectedly: {v513_net_keys} — may indicate config actually changed, or guard regression")
        else:
            warn(f"v5.13 diff sections populated unexpectedly ({3 - unchanged_sections} non-null) — investigate if no real changes occurred")
elif snaps.get("snapshotCount", 0) == 1:
    # Test the "now" comparison
    diff = api_get("snapshot/diff?older=0&newer=now")
    if "_error" in diff:
        fail(f"Request failed (now compare): {diff['_error']}")
    elif diff.get("error"):
        fail(f"API error (now compare): {diff['error']}")
    else:
        ok("Endpoint responds (compare to now)")
else:
    warn("No snapshots for diff — skipping")

# ── Test 12: GET /api/stats ──────────────────────────────────────────
section("GET /api/stats")

stats_data = api_get("stats")
if "_error" in stats_data:
    fail(f"Request failed: {stats_data['_error']}")
else:
    ok("Endpoint responds")
    timings = stats_data.get("timings", {})
    if timings and isinstance(timings, dict):
        ok(f"Timings present ({len(timings)} endpoints)")
        # After calling dashboard/devices/apps/etc above, we should have data
        for ep in ["dashboard", "devices", "apps"]:
            if ep in timings:
                t = timings[ep]
                if "median" in t and "count" in t:
                    ok(f"{ep}: median={t['median']}ms, count={t['count']}")
                else:
                    fail(f"{ep} timing missing median/count fields")
    else:
        warn("No timings yet (may be expected on first run)")

# ── Test 13: GET /api/reports ─────────────────────────────────────────
section("GET /api/reports")

rpts = api_get("reports")
if "_error" in rpts:
    fail(f"Request failed: {rpts['_error']}")
else:
    ok("Endpoint responds")
    if "reports" in rpts and isinstance(rpts["reports"], list):
        ok(f"Reports list: {len(rpts['reports'])} files")
    else:
        fail("Missing or invalid 'reports' field")
    if "lastReport" in rpts:
        ok(f"lastReport: {rpts['lastReport'] or '(none)'}")
    else:
        fail("Missing 'lastReport' field")

# ── v5.10.x: GET /api/code ────────────────────────────────────────────
section("GET /api/code")

code_resp = api_get("code")
if "_error" in code_resp:
    fail(f"Request failed: {code_resp['_error']}")
else:
    ok("Endpoint responds")
    for field in ["appTypes", "driverTypes", "bundles", "libraries", "hubVariables"]:
        if field in code_resp:
            v = code_resp[field]
            if isinstance(v, list):
                ok(f"code.{field}: {len(v)} entries")
            elif isinstance(v, dict):  # hubVariables is a dict {count, supported, variables}
                ok(f"code.{field}: count={v.get('count')} supported={v.get('supported')}")
            else:
                fail(f"code.{field} has unexpected type {type(v).__name__}")
        else:
            fail(f"Missing 'code.{field}'")

    # appTypes shape
    ats = code_resp.get("appTypes") or []
    if ats:
        a = ats[0]
        for field in ["id", "name", "namespace", "oauthEnabled", "lastModified", "usedByCount"]:
            if field in a:
                ok(f"appType has '{field}'")
            else:
                fail(f"appType missing '{field}'")

    # driverTypes shape
    dts = code_resp.get("driverTypes") or []
    if dts:
        d = dts[0]
        for field in ["id", "name", "namespace", "lastModified", "capabilityCount", "usedByCount"]:
            if field in d:
                ok(f"driverType has '{field}'")
            else:
                fail(f"driverType missing '{field}'")

    # Coherence: code.appTypes count of user-app-types should match /hub2/userAppTypes ground truth
    user_app_types_raw = fetch("/hub2/userAppTypes")
    if isinstance(user_app_types_raw, list):
        if len(ats) == len(user_app_types_raw):
            ok(f"appTypes count matches /hub2/userAppTypes ({len(ats)})")
        else:
            fail(f"appTypes count {len(ats)} != /hub2/userAppTypes {len(user_app_types_raw)}")

# ── v5.11.2: GET /api/live (auto-refresh payload) ─────────────────────
section("GET /api/live")

live = api_get("live")
if "_error" in live:
    fail(f"Request failed: {live['_error']}")
else:
    ok("Endpoint responds")
    # v5.11.2 fix: cpuInfo + loadThreshold MUST be in /api/live to survive auto-refresh overwrite
    if "cpuInfo" in live and live["cpuInfo"]:
        ok(f"live.cpuInfo present (regression guard for v5.11.2 chip-disappearing bug)")
    else:
        fail("live missing cpuInfo — chips will disappear after auto-refresh (regression of v5.11.2 fix)")
    if "loadThreshold" in live:
        ok(f"live.loadThreshold present (value: {live['loadThreshold']})")
    else:
        fail("live missing loadThreshold (regression of v5.11.2 fix)")
    # Standard live fields
    for field in ["freeOSMemory", "cpuAvg5min", "temperature", "databaseSize"]:
        if field in live:
            ok(f"live has '{field}'")
        else:
            fail(f"live missing '{field}'")

# ── v5.9.0: POST /api/network/test (ping-gateway + IPv4 validation) ──
section("POST /api/network/test")

# Happy path: ping the gateway
ping_resp = api_post("network/test", "type=ping-gateway", timeout=45)
if "_error" in ping_resp:
    fail(f"Request failed: {ping_resp['_error']}")
elif ping_resp.get("success"):
    out = ping_resp.get("output", "")
    if "PING" in out and "packets transmitted" in out:
        ok(f"ping-gateway returned valid ping output ({ping_resp.get('elapsedMs')}ms)")
    else:
        fail(f"ping-gateway output missing expected markers: {out[:200]}")
else:
    fail(f"ping-gateway returned success=false: {ping_resp.get('output', '')[:200]}")

# Server-side IPv4 validation: invalid input must be rejected
bad_resp = api_post("network/test", "type=ping-ip&ip=not-an-ip")
if bad_resp.get("success") is False and "Error: invalid IP address" in (bad_resp.get("output") or ""):
    ok("Invalid IPv4 rejected with 'Error: invalid IP address' (server-side regex guard)")
else:
    fail(f"Invalid IPv4 NOT rejected — security regression: {bad_resp}")

# Unknown test type must also be rejected
unk_resp = api_post("network/test", "type=bogus")
if unk_resp.get("success") is False and "unknown test type" in (unk_resp.get("output") or "").lower():
    ok("Unknown test type rejected")
else:
    fail(f"Unknown test type NOT rejected: {unk_resp}")

# Missing type must error out
miss_resp = api_post("network/test", "")
if miss_resp.get("success") is False:
    ok("Missing 'type' parameter rejected")
else:
    fail(f"Missing 'type' parameter NOT rejected: {miss_resp}")

# ── v5.12.0: POST /api/network/zigbee/scan (slow — gated by env var) ─
section("POST /api/network/zigbee/scan (gated by RUN_SLOW_TESTS=1)")

if os.environ.get("RUN_SLOW_TESTS") == "1":
    info("Triggering Zigbee channel scan (~15-30s)…")
    scan_resp = api_post("network/zigbee/scan", "", timeout=120)
    if "_error" in scan_resp:
        fail(f"Scan request failed: {scan_resp['_error']}")
    elif scan_resp.get("success"):
        sc = scan_resp.get("scan") or {}
        results = sc.get("results") or []
        detected = [r for r in results if r.get("panId")]
        ok(f"Scan completed in {scan_resp.get('elapsedMs')}ms — {len(detected)} neighbor PANs detected of {len(results)} channels probed")
        # Verify scan got cached and is now visible in /api/network
        net2 = api_get("network")
        cached = (net2.get("zigbeeChannelScan") or {})
        if cached.get("at") == sc.get("at"):
            ok("Scan result cached in state and visible in subsequent /api/network response")
        else:
            fail("Scan result NOT cached — cache write or readback broken")
    else:
        fail(f"Scan returned success=false: {scan_resp}")
else:
    info("Skipped (set RUN_SLOW_TESTS=1 to run; takes ~30s)")

# ── Test 13: POST actions ─────────────────────────────────────────────
section("POST /api/snapshot/create")

snap_result = api_post("snapshot/create")
if "_error" in snap_result:
    fail(f"Request failed: {snap_result['_error']}")
elif snap_result.get("success"):
    ok(f"Snapshot created (count: {snap_result.get('snapshotCount')})")
else:
    fail(f"Unexpected response: {snap_result}")

section("POST /api/checkpoint/create")

cp_result = api_post("checkpoint/create")
if "_error" in cp_result:
    fail(f"Request failed: {cp_result['_error']}")
elif cp_result.get("success"):
    ok(f"Checkpoint created (count: {cp_result.get('checkpointCount')})")
else:
    fail(f"Unexpected response: {cp_result}")

# ── Test 13b: Performance compare endpoint ────────────────────────────
section("POST /api/performance/compare")

# Missing params → error
comp_missing = api_post("performance/compare")
if "_error" in comp_missing:
    fail(f"Request failed: {comp_missing['_error']}")
elif comp_missing.get("success") == False and comp_missing.get("error"):
    ok(f"Missing params returns error: {comp_missing['error']}")
else:
    fail(f"Expected error for missing params, got: {comp_missing}")

# Invalid checkpoint index → error
comp_bad_idx = api_post("performance/compare", "baseline=startup&checkpoint=9999")
if "_error" in comp_bad_idx:
    fail(f"Request failed: {comp_bad_idx['_error']}")
elif comp_bad_idx.get("success") == False and comp_bad_idx.get("error"):
    ok(f"Invalid checkpoint index returns error: {comp_bad_idx['error']}")
else:
    fail(f"Expected error for invalid index, got: {comp_bad_idx}")

# Valid comparison: startup → now
comp_valid = api_post("performance/compare", "baseline=startup&checkpoint=now")
if "_error" in comp_valid:
    fail(f"Request failed: {comp_valid['_error']}")
elif comp_valid.get("success"):
    ok("startup→now compare returns success=true")
    for field in ["baselineLabel", "checkpointLabel", "baselineStats", "checkpointStats"]:
        if field in comp_valid:
            ok(f"  Has '{field}'")
        else:
            fail(f"  Missing '{field}' in compare response")
    cs = comp_valid.get("checkpointStats", {})
    if cs.get("uptimeSeconds"):
        ok(f"  checkpointStats.uptimeSeconds present ({cs['uptimeSeconds']:.0f}s)")
    else:
        fail("  checkpointStats.uptimeSeconds missing — startup comparison pct will be N/A")
else:
    fail(f"startup→now compare returned success=false: {comp_valid.get('error')}")

# Valid comparison: startup → saved checkpoint (if one exists)
perf2 = api_get("performance")
cp_count = perf2.get("checkpointCount", 0) if "_error" not in perf2 else 0
if cp_count > 0:
    comp_cp = api_post("performance/compare", "baseline=startup&checkpoint=0")
    if "_error" in comp_cp:
        fail(f"startup→cp[0] request failed: {comp_cp['_error']}")
    elif comp_cp.get("success"):
        ok(f"startup→cp[0] compare returns success=true")
        cs2 = comp_cp.get("checkpointStats", {})
        if cs2.get("deviceStats") is not None:
            ok(f"  checkpointStats.deviceStats present ({len(cs2['deviceStats'])} entries)")
        else:
            fail("  checkpointStats.deviceStats missing")
    else:
        fail(f"startup→cp[0] returned error: {comp_cp.get('error')}")
else:
    info("Skipping checkpoint index compare (no checkpoints)")

# ── Test 14: Cross-endpoint coherence ─────────────────────────────────
section("Cross-Endpoint Coherence")

# Dashboard vs Devices
dash_total = dash.get("devices", {}).get("total") if not "_error" in dash else None
dev_total = devs.get("summary", {}).get("totalDevices") if not "_error" in devs else None
if dash_total is not None and dev_total is not None:
    if dash_total == dev_total:
        ok(f"Devices: dashboard ({dash_total}) == devices ({dev_total})")
    else:
        fail(f"Devices: dashboard ({dash_total}) != devices ({dev_total})")

# Dashboard vs Apps
dash_apps = dash.get("apps", {}).get("total") if not "_error" in dash else None
apps_total = apps.get("summary", {}).get("totalApps") if not "_error" in apps else None
if dash_apps is not None and apps_total is not None:
    if dash_apps == apps_total:
        ok(f"Apps: dashboard ({dash_apps}) == apps ({apps_total})")
    else:
        fail(f"Apps: dashboard ({dash_apps}) != apps ({apps_total})")

# v5.10.x: code.appTypes count == apps.summary.userApps install-source ratio
# (every user-installed app instance comes from one of the user app types)
code_atypes = len(code_resp.get("appTypes") or []) if code_resp and "_error" not in code_resp else None
apps_user = apps.get("summary", {}).get("userApps") if not "_error" in apps else None
if code_atypes is not None and apps_user is not None:
    if apps_user == 0:
        info("No user apps installed — code/apps coherence skipped")
    elif apps_user >= 1 and code_atypes >= 1:
        ok(f"User-installed code present (appTypes={code_atypes}, userAppInstances={apps_user})")
    else:
        warn(f"appTypes={code_atypes} but userAppInstances={apps_user} — possible orphan instances")

# Dashboard hub vs Health hub
dash_hub = dash.get("hub", {}).get("name") if not "_error" in dash else None
health_hub = health.get("hub", {}).get("name") if not "_error" in health else None
if dash_hub and health_hub:
    if dash_hub == health_hub:
        ok(f"Hub name: dashboard == health ({dash_hub})")
    else:
        fail(f"Hub name: dashboard ({dash_hub}) != health ({health_hub})")

# Dashboard resources vs Health resources
dash_mem = dash.get("resources", {}).get("freeOSMemory") if not "_error" in dash else None
health_mem = health.get("resources", {}).get("freeOSMemory") if not "_error" in health else None
if dash_mem and health_mem:
    drift = abs(dash_mem - health_mem) / max(dash_mem, 1)
    if drift < 0.20:
        ok(f"Memory: dashboard ~= health (drift {drift:.0%})")
    else:
        warn(f"Memory drift {drift:.0%} between dashboard and health (timing)")

# ── Test 15: Groovy UI vs API parity ──────────────────────────────────
section("Groovy UI vs API Parity")

# Fetch the Groovy dashboard page for comparison
groovy_dash = fetch(f"/installedapp/configure/json/{instance_id}")
if groovy_dash and "configPage" in groovy_dash:
    groovy_text = ""
    for s in groovy_dash["configPage"].get("sections", []):
        for item in s.get("body", []):
            if item.get("element") == "paragraph":
                clean = re.sub(r'<[^>]+>', '', item.get("description", "")).strip()
                groovy_text += clean + "\n"

    groovy_devices = None
    m = re.search(r'Devices:\s*(\d+)\s*total', groovy_text)
    if m:
        groovy_devices = int(m.group(1))

    api_devices = dash.get("devices", {}).get("total") if not "_error" in dash else None

    if groovy_devices is not None and api_devices is not None:
        if groovy_devices == api_devices:
            ok(f"Device count: Groovy UI ({groovy_devices}) == API ({api_devices})")
        else:
            fail(f"Device count: Groovy UI ({groovy_devices}) != API ({api_devices})")

    groovy_apps = None
    m = re.search(r'Applications:\s*(\d+)\s*total', groovy_text)
    if m:
        groovy_apps = int(m.group(1))

    api_apps_total = dash.get("apps", {}).get("total") if not "_error" in dash else None
    if groovy_apps is not None and api_apps_total is not None:
        if groovy_apps == api_apps_total:
            ok(f"App count: Groovy UI ({groovy_apps}) == API ({api_apps_total})")
        else:
            fail(f"App count: Groovy UI ({groovy_apps}) != API ({api_apps_total})")
else:
    warn("Could not fetch Groovy dashboard for parity check")

# ── Test 16: serveUI endpoint ─────────────────────────────────────────
section("GET /ui.html (SPA serving)")

try:
    url = f"{api_base}/ui.html?access_token={access_token}"
    resp = urllib.request.urlopen(url, timeout=15)
    html = resp.read().decode()
    if "hub_diagnostics" in html.lower() or "access_token" in html or "api_base" in html:
        ok("SPA HTML served")
    else:
        fail("HTML served but doesn't look like the SPA")

    if access_token in html:
        ok("Access token injected into HTML")
    else:
        fail("Access token not found in served HTML")

    if f"/apps/api/{instance_id}" in html:
        ok("API base URL injected into HTML")
    else:
        fail("API base URL not found in served HTML")

    if "${access_token}" in html:
        fail("Raw placeholder ${access_token} still in HTML (not replaced)")
    else:
        ok("No raw placeholders remaining")
except Exception as e:
    fail(f"Could not fetch UI: {e}")

# ── Audit: start → poll → data ────────────────────────────────────────
section("POST /api/audit/start")

audit_start = api_post("audit/start", timeout=30)
if "_error" in audit_start:
    fail(f"Request failed: {audit_start['_error']}")
else:
    scan_id = audit_start.get("scanId")
    total_devices = audit_start.get("total", 0)
    if scan_id and scan_id != "null":
        ok(f"Scan started: scanId={scan_id}, total={total_devices}")
    else:
        fail(f"No scanId in response: {audit_start}")
    if total_devices > 0:
        ok(f"Device count > 0 ({total_devices})")
    else:
        fail(f"total={total_devices} (expected > 0)")

    section("GET /api/audit/status (poll until done)")
    final_status = None
    for i in range(60):
        st = api_get(f"audit/status?scanId={scan_id}", timeout=15)
        if "_error" in st:
            fail(f"Status poll failed: {st['_error']}")
            break
        proc = st.get("processed", 0)
        s = st.get("status")
        info(f"[{i+1}] processed={proc}/{total_devices}  status={s}")
        if s in ("done", "error"):
            final_status = s
            break
        time.sleep(2)

    if final_status == "done":
        ok("Scan completed with status=done")
    elif final_status == "error":
        fail(f"Scan errored: {st.get('error')}")
    else:
        fail("Scan did not complete within 120s")

    section("GET /api/audit/data")
    audit_data = api_get("audit/data", timeout=30)
    if "_error" in audit_data:
        fail(f"Request failed: {audit_data['_error']}")
    else:
        ok("Endpoint responds")
        for key in ["deviceCount", "unreferenced", "allDevices", "hubName", "generatedAt"]:
            if key in audit_data:
                ok(f"Has '{key}'")
            else:
                fail(f"Missing '{key}'")
        device_count = audit_data.get("deviceCount", 0)
        if device_count == total_devices:
            ok(f"deviceCount matches scan total ({device_count})")
        else:
            fail(f"deviceCount {device_count} != scan total {total_devices}")
        all_devs = audit_data.get("allDevices") or {}
        if len(all_devs) > 0:
            ok(f"allDevices populated ({len(all_devs)} entries)")
        else:
            fail("allDevices is empty")

# ── Summary ───────────────────────────────────────────────────────────
total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if warnings:
    print(f", {warnings} warnings", end="")
if failed:
    print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")

sys.exit(1 if failed else 0)
PYTHON_SCRIPT
