#!/usr/bin/env bash
#
# Hub Diagnostics Validation Test
#
# Navigates all 8 pages of the Hub Diagnostics app and validates that
# displayed data is coherent across pages and matches raw hub API data.
#
# Usage:
#   bash scripts/test-hub-diagnostics.sh                    # default hub
#   bash scripts/test-hub-diagnostics.sh @maison            # specific hub
#   bash scripts/test-hub-diagnostics.sh @maison 1053       # specific hub + instance ID
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
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
import json, sys, re, os, subprocess, urllib.request, urllib.parse, http.cookiejar

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
RESET = "\033[0m"

passed = 0
failed = 0
warnings = 0

def ok(msg):
    global passed
    passed += 1
    print(f"  {GREEN}[PASS]{RESET} {msg}")

def fail(msg):
    global failed
    failed += 1
    print(f"  {RED}[FAIL]{RESET} {msg}")

def warn(msg):
    global warnings
    warnings += 1
    print(f"  {YELLOW}[WARN]{RESET} {msg}")

def section(msg):
    print(f"\n{CYAN}--- {msg} ---{RESET}")

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

# ── Step 2: Authenticate ─────────────────────────────────────────────
cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(path, timeout=30):
    url = f"http://{hub_ip}{path}"
    try:
        resp = opener.open(url, timeout=timeout)
        return json.loads(resp.read().decode())
    except Exception as e:
        return None

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

# ── Step 3: Find instance ID ─────────────────────────────────────────
instance_id = int(instance_id_arg) if instance_id_arg else None

if not instance_id:
    apps_list = fetch("/hub2/appsList")
    if apps_list and "apps" in apps_list:
        def find_instance(entries):
            for entry in entries:
                d = entry.get("data", {})
                if d and isinstance(d, dict):
                    t = d.get("type", "")
                    if t == "Hub Diagnostics":
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

# ── Step 4: Fetch ground truth ────────────────────────────────────────
section("Ground Truth (raw API data)")

# Devices
devices_data = fetch("/hub2/devicesList")
gt_devices = 0
gt_disabled = 0
if devices_data and "devices" in devices_data:
    for d in devices_data["devices"]:
        dd = d.get("data", {})
        if dd and isinstance(dd, dict):
            gt_devices += 1
            if dd.get("disabled"):
                gt_disabled += 1

# Apps (recursive count from appsList)
apps_data = fetch("/hub2/appsList")
gt_apps_api = 0
gt_apps_user = 0
gt_apps_system = 0
if apps_data and "apps" in apps_data:
    def count_apps(entries):
        global gt_apps_api, gt_apps_user, gt_apps_system
        for entry in entries:
            d = entry.get("data", {})
            if d and isinstance(d, dict):
                gt_apps_api += 1
                if d.get("user"):
                    gt_apps_user += 1
                else:
                    gt_apps_system += 1
            children = entry.get("children", [])
            if children:
                count_apps(children)
    count_apps(apps_data["apps"])

# Runtime stats
runtime_data = fetch("/logs/json")
gt_apps_runtime = len(runtime_data.get("appStats", [])) if runtime_data else 0

# Hub data
hub_data = fetch("/hub2/hubData")
gt_hub_name = hub_data.get("name", "") if hub_data else ""
gt_firmware = hub_data.get("version", "") if hub_data else ""
gt_model = hub_data.get("model", "") if hub_data else ""

# Memory
mem_text = fetch_text("/hub/advanced/freeOSMemoryLast")
gt_free_mem_kb = 0
if mem_text:
    lines = mem_text.strip().split("\n")
    if len(lines) > 1:
        vals = lines[1].split(",")
        if len(vals) >= 2:
            try:
                gt_free_mem_kb = int(vals[1].strip())
            except ValueError:
                pass

print(f"  Hub: {gt_hub_name} | Model: {gt_model} | Firmware: {gt_firmware}")
print(f"  Devices (API): {gt_devices}  |  Disabled: {gt_disabled}")
print(f"  Apps (API recursive): {gt_apps_api}  |  User: {gt_apps_user}  |  System: {gt_apps_system}")
print(f"  Apps (runtime): {gt_apps_runtime}")
print(f"  Free Memory: {gt_free_mem_kb} KB ({gt_free_mem_kb / 1024:.1f} MB)")

# ── Step 5: Fetch all pages ───────────────────────────────────────────
pages = {}
page_names = ["dashboardPage", "devicesPage", "appsPage", "networkPage",
              "performancePage", "systemHealthPage", "snapshotsPage", "settingsPage"]

for pname in page_names:
    path = f"/installedapp/configure/json/{instance_id}"
    if pname != "dashboardPage":
        path += f"/{pname}"
    data = fetch(path, timeout=60)
    if data and "configPage" in data:
        pages[pname] = data["configPage"]
    else:
        pages[pname] = None

# ── Helpers ───────────────────────────────────────────────────────────
def get_sections(page_name):
    p = pages.get(page_name)
    if not p:
        return []
    return p.get("sections", [])

def get_all_text(page_name):
    """Extract all paragraph text from a page, stripping HTML tags."""
    texts = []
    for s in get_sections(page_name):
        for item in s.get("body", []):
            if item.get("element") == "paragraph":
                desc = item.get("description", "")
                clean = re.sub(r'<[^>]+>', '', desc).strip()
                texts.append(clean)
    return "\n".join(texts)

def extract_metric(text, pattern):
    """Extract a numeric value from text using a regex with one capture group."""
    m = re.search(pattern, text)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            return None
    return None

def extract_text_value(text, pattern):
    """Extract a string value from text using a regex with one capture group."""
    m = re.search(pattern, text)
    return m.group(1).strip() if m else None

def count_elements(page_name, element_type):
    count = 0
    for s in get_sections(page_name):
        for item in s.get("body", []):
            if item.get("element") == element_type:
                count += 1
    return count

def extract_table_metric(text, metric_pattern):
    """Extract a value from an HTML table row by metric name pattern (regex)."""
    pattern = metric_pattern + r'.*?<td[^>]*>\s*(?:<[^>]*>)*\s*(\d+)'
    m = re.search(pattern, text, re.DOTALL)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            return None
    return None

def get_raw_html(page_name):
    """Get all paragraph descriptions as raw HTML."""
    htmls = []
    for s in get_sections(page_name):
        for item in s.get("body", []):
            if item.get("element") == "paragraph":
                htmls.append(item.get("description", ""))
    return "\n".join(htmls)

# ── Step 6: Validate each page ────────────────────────────────────────

print(f"\n{BOLD}=== Hub Diagnostics Validation: {gt_hub_name} ({gt_model}) ==={RESET}")

# ── Dashboard ──
section("dashboardPage")
secs = get_sections("dashboardPage")
if not pages.get("dashboardPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 3:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 3 sections, got {len(secs)}")

    text = get_all_text("dashboardPage")
    hrefs = count_elements("dashboardPage", "href")
    if hrefs == 7:
        ok(f"Navigation links: {hrefs}")
    else:
        fail(f"Expected 7 navigation hrefs, got {hrefs}")

    if "Hub Diagnostics Summary" in text:
        ok("Contains summary header")
    else:
        fail("Missing 'Hub Diagnostics Summary' header")

    # Extract dashboard values
    dash_devices = extract_metric(text, r'Devices:\s*(\d+)\s*total')
    dash_hub_name = extract_text_value(text, r'Hub:\s*([^|]+)')
    dash_firmware = extract_text_value(text, r'Firmware:\s*([^|]+)')
    dash_hardware = extract_text_value(text, r'Hardware:\s*(\S+)')
    dash_health = extract_metric(text, r'Health Score:\s*(\d+)/100')
    dash_apps = extract_metric(text, r'Applications:\s*(\d+)\s*total')

    # Ground truth checks
    if dash_devices is not None:
        if dash_devices == gt_devices:
            ok(f"Device count matches API ({dash_devices})")
        else:
            fail(f"Device count {dash_devices} != API {gt_devices}")
    else:
        fail("Could not extract device count")

    if dash_hub_name == gt_hub_name:
        ok(f"Hub name matches ({dash_hub_name})")
    else:
        fail(f"Hub name '{dash_hub_name}' != API '{gt_hub_name}'")

    if dash_firmware == gt_firmware:
        ok(f"Firmware matches ({dash_firmware})")
    else:
        fail(f"Firmware '{dash_firmware}' != API '{gt_firmware}'")

    if dash_hardware == gt_model:
        ok(f"Hardware model matches ({dash_hardware})")
    else:
        fail(f"Hardware model '{dash_hardware}' != API '{gt_model}'")

# ── Devices ──
section("devicesPage")
secs = get_sections("devicesPage")
if not pages.get("devicesPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 4:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 4 sections, got {len(secs)}")

    raw = get_raw_html("devicesPage")
    dev_total = extract_table_metric(raw, "Total Devices")
    dev_disabled = extract_table_metric(raw, "Disabled Devices")

    if dev_total is not None:
        if dev_total == gt_devices:
            ok(f"Total devices matches API ({dev_total})")
        else:
            fail(f"Total devices {dev_total} != API {gt_devices}")
    else:
        fail("Could not extract Total Devices from metrics table")

    if dev_disabled is not None:
        if dev_disabled == gt_disabled:
            ok(f"Disabled devices matches API ({dev_disabled})")
        else:
            fail(f"Disabled devices {dev_disabled} != API {gt_disabled}")
    else:
        fail("Could not extract Disabled Devices from metrics table")

# ── Apps ──
section("appsPage")
secs = get_sections("appsPage")
if not pages.get("appsPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 3:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 3 sections, got {len(secs)}")

    raw = get_raw_html("appsPage")
    app_api = extract_table_metric(raw, r"App Instances.*from API")
    app_runtime = extract_table_metric(raw, r"Total Apps.*incl.*platform")
    app_user = extract_table_metric(raw, "User Apps")
    app_system = extract_table_metric(raw, "System Apps")

    if app_api is not None:
        if app_api == gt_apps_api:
            ok(f"App API count matches ({app_api})")
        else:
            fail(f"App API count {app_api} != ground truth {gt_apps_api}")
    else:
        fail("Could not extract 'App Instances (from API)' from metrics table")

    if app_runtime is not None:
        if app_runtime == gt_apps_runtime:
            ok(f"App runtime count matches ({app_runtime})")
        else:
            fail(f"App runtime count {app_runtime} != ground truth {gt_apps_runtime}")
    elif gt_apps_runtime > gt_apps_api:
        fail("Missing 'Total Apps (incl. platform)' row — expected because runtime > API")
    else:
        ok("No runtime total row (runtime == API count, row correctly omitted)")

    if app_user is not None and app_system is not None and app_api is not None:
        if app_user + app_system == app_api:
            ok(f"User ({app_user}) + System ({app_system}) = API total ({app_api})")
        else:
            fail(f"User ({app_user}) + System ({app_system}) = {app_user + app_system} != API total ({app_api})")

# ── Network ──
section("networkPage")
secs = get_sections("networkPage")
if not pages.get("networkPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 3:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 3 sections, got {len(secs)}")

    text = get_all_text("networkPage")
    if re.search(r'\d+\.\d+\.\d+\.\d+', text):
        ok("Contains IP address data")
    else:
        warn("No IP address found in network page")

# ── Performance ──
section("performancePage")
secs = get_sections("performancePage")
if not pages.get("performancePage"):
    fail("Page failed to load")
else:
    if len(secs) >= 2:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 2 sections, got {len(secs)}")

# ── System Health ──
section("systemHealthPage")
secs = get_sections("systemHealthPage")
if not pages.get("systemHealthPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 5:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 5 sections, got {len(secs)}")

    text = get_all_text("systemHealthPage")
    sh_hub_name = extract_text_value(text, r'Hub Name:\s*(.+)')
    sh_firmware = extract_text_value(text, r'Firmware Version:\s*(.+)')
    sh_hardware = extract_text_value(text, r'Hardware Model:\s*(.+)')
    sh_health = extract_metric(text, r'^(\d+)$')  # standalone number is the score

    # Try to find the health score — it's a standalone number in its own paragraph
    for s in get_sections("systemHealthPage"):
        for item in s.get("body", []):
            if item.get("element") == "paragraph":
                desc_clean = re.sub(r'<[^>]+>', '', item.get("description", "")).strip()
                if re.match(r'^\d+$', desc_clean):
                    sh_health = int(desc_clean)

    if sh_hub_name == gt_hub_name:
        ok(f"Hub name matches ({sh_hub_name})")
    else:
        fail(f"Hub name '{sh_hub_name}' != API '{gt_hub_name}'")

    if sh_firmware == gt_firmware:
        ok(f"Firmware matches ({sh_firmware})")
    else:
        fail(f"Firmware '{sh_firmware}' != API '{gt_firmware}'")

    if sh_hardware == gt_model:
        ok(f"Hardware model matches ({sh_hardware})")
    else:
        fail(f"Hardware model '{sh_hardware}' != API '{gt_model}' (may show 'PHYSICAL' if using location.hubs[0].type)")

    # Memory sanity check
    mem_match = re.search(r'Free OS Memory:\s*([\d.]+)\s*MB', text)
    if mem_match:
        sh_mem_mb = float(mem_match.group(1))
        gt_mem_mb = gt_free_mem_kb / 1024.0
        # Allow 20% drift since memory changes between fetches
        if gt_mem_mb > 0 and abs(sh_mem_mb - gt_mem_mb) / gt_mem_mb < 0.20:
            ok(f"Free memory within 20% ({sh_mem_mb:.1f} MB vs {gt_mem_mb:.1f} MB)")
        elif gt_mem_mb > 0:
            warn(f"Free memory drift > 20% ({sh_mem_mb:.1f} MB vs {gt_mem_mb:.1f} MB)")
        else:
            warn("Could not compare memory (ground truth is 0)")
    else:
        fail("Could not extract Free OS Memory")

# ── Snapshots ──
section("snapshotsPage")
secs = get_sections("snapshotsPage")
if not pages.get("snapshotsPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 2:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 2 sections, got {len(secs)}")

# ── Settings ──
section("settingsPage")
secs = get_sections("settingsPage")
if not pages.get("settingsPage"):
    fail("Page failed to load")
else:
    if len(secs) >= 4:
        ok(f"Page loaded ({len(secs)} sections)")
    else:
        fail(f"Expected >= 4 sections, got {len(secs)}")

    inputs = count_elements("settingsPage", "input")
    if inputs >= 5:
        ok(f"Has {inputs} setting inputs")
    else:
        fail(f"Expected >= 5 inputs, got {inputs}")

# ── Step 7: Cross-page coherence ──────────────────────────────────────
section("Cross-Page Coherence")

# Device count: dashboard vs devicesPage
if dash_devices is not None and dev_total is not None:
    if dash_devices == dev_total:
        ok(f"Device count: dashboard ({dash_devices}) == devicesPage ({dev_total})")
    else:
        fail(f"Device count: dashboard ({dash_devices}) != devicesPage ({dev_total})")

# App count: dashboard vs appsPage
if dash_apps is not None and app_api is not None:
    if dash_apps == app_api:
        ok(f"App count: dashboard ({dash_apps}) == appsPage API ({app_api})")
    else:
        fail(f"App count: dashboard ({dash_apps}) != appsPage API ({app_api})")

# Hub name: dashboard vs systemHealthPage
if dash_hub_name and sh_hub_name:
    if dash_hub_name == sh_hub_name:
        ok(f"Hub name: dashboard == systemHealthPage ({dash_hub_name})")
    else:
        fail(f"Hub name: dashboard ({dash_hub_name}) != systemHealthPage ({sh_hub_name})")

# Firmware: dashboard vs systemHealthPage
if dash_firmware and sh_firmware:
    if dash_firmware == sh_firmware:
        ok(f"Firmware: dashboard == systemHealthPage ({dash_firmware})")
    else:
        fail(f"Firmware: dashboard ({dash_firmware}) != systemHealthPage ({sh_firmware})")

# Hardware: dashboard vs systemHealthPage
if dash_hardware and sh_hardware:
    if dash_hardware == sh_hardware:
        ok(f"Hardware: dashboard == systemHealthPage ({dash_hardware})")
    else:
        fail(f"Hardware: dashboard ({dash_hardware}) != systemHealthPage ({sh_hardware})")

# Health score: dashboard vs systemHealthPage
if dash_health is not None and sh_health is not None:
    if dash_health == sh_health:
        ok(f"Health score: dashboard == systemHealthPage ({dash_health})")
    else:
        fail(f"Health score: dashboard ({dash_health}) != systemHealthPage ({sh_health})")

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
