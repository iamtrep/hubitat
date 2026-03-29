#!/usr/bin/env bash
#
# Hub Diagnostics API Validation Test
#
# Tests all JSON API endpoints of the headless architecture, validates
# response structure, data integrity, and cross-endpoint coherence.
# Also compares API data against raw hub APIs (ground truth).
#
# Usage:
#   bash scripts/test-hub-diagnostics-api.sh                    # default hub
#   bash scripts/test-hub-diagnostics-api.sh @maison            # specific hub
#   bash scripts/test-hub-diagnostics-api.sh @maison 243        # specific hub + instance ID
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
if apps_data and "apps" in apps_data:
    def count_apps(entries):
        global gt_apps, gt_apps_user, gt_apps_system
        for entry in entries:
            d = entry.get("data", {})
            if d and isinstance(d, dict):
                gt_apps += 1
                if d.get("user"):
                    gt_apps_user += 1
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

    # Spot check structure
    if rows:
        r = rows[0]
        for field in ["id", "name", "type", "protocol", "status"]:
            if field in r:
                ok(f"Device row has '{field}' field")
            else:
                fail(f"Device row missing '{field}' field")
            break  # Only check first row for all fields

    # Protocol counts should sum to total
    bp = devs.get("byProtocol", {})
    proto_sum = sum(bp.values())
    if proto_sum == gt_devices:
        ok(f"Protocol counts sum to total ({proto_sum})")
    else:
        fail(f"Protocol counts sum {proto_sum} != {gt_devices}")

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
