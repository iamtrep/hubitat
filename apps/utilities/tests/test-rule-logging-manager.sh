#!/usr/bin/env bash
#
# Rule Logging Manager — Endpoint Monitor
#
# Tests the undocumented Hubitat HTTP endpoints used by RuleLoggingManager.groovy
# and detects breaking API changes via structural assertions and schema snapshot diffs.
#
# Usage:
#   bash test-rule-logging-manager.sh                           # default hub
#   bash test-rule-logging-manager.sh @maison-pro               # specific hub
#   bash test-rule-logging-manager.sh @maison-pro --save-snapshots
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
# Unique per run/hub/test so concurrent runs (e.g. parallel agents, pytest -n)
# don't clobber each other's session state. Cleaned up on exit.
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/hubitat-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT
SNAPSHOT_DIR="$SCRIPT_DIR/snapshots"

HUB_NAME=""
SAVE_SNAPSHOTS="false"
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    elif [[ "$arg" == "--save-snapshots" ]]; then
        SAVE_SNAPSHOTS="true"
    fi
done

python3 - "$HUB_NAME" "$SAVE_SNAPSHOTS" "$CONFIG_FILE" "$COOKIE_JAR" "$SNAPSHOT_DIR" <<'PYTHON_SCRIPT'
import json, sys, os, urllib.request, urllib.parse, http.cookiejar

hub_name_arg   = sys.argv[1]
save_snapshots = sys.argv[2] == "true"
config_file    = sys.argv[3]
cookie_jar_path = sys.argv[4]
snapshot_dir   = sys.argv[5]

# ── Colors ───────────────────────────────────────────────────────────────────
GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
CYAN   = "\033[36m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

passed = 0; failed = 0; warnings = 0

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

# ── Config ───────────────────────────────────────────────────────────────────
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

print(f"{BOLD}Rule Logging Manager — Endpoint Monitor{RESET}")
print(f"Hub: {hub_name} ({hub_ip})")
if save_snapshots:
    print(f"{YELLOW}Mode: --save-snapshots (baseline will be updated){RESET}")

# ── HTTP setup ────────────────────────────────────────────────────────────────
cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(path, timeout=30):
    url = f"http://{hub_ip}{path}"
    try:
        resp = opener.open(url, timeout=timeout)
        return json.loads(resp.read().decode())
    except Exception as e:
        fail(f"Request failed for {path}: {e}")
        return None

if username and password:
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
        info("Authenticated (hub security enabled)")
    except Exception as e:
        print(f"{RED}Authentication failed: {e}{RESET}")
        sys.exit(2)

# ── Schema utilities ──────────────────────────────────────────────────────────
def extract_schema(obj, depth=0, max_depth=4):
    if depth >= max_depth:
        return "__truncated__"
    if obj is None:         return "null"
    if isinstance(obj, bool):  return "bool"
    if isinstance(obj, int):   return "int"
    if isinstance(obj, float): return "float"
    if isinstance(obj, str):   return "str"
    if isinstance(obj, list):
        if not obj: return "list[]"
        first = obj[0]
        if isinstance(first, dict):
            return {"__list_element__": extract_schema(first, depth + 1, max_depth)}
        return f"list[{type(first).__name__}]"
    if isinstance(obj, dict):
        return {k: extract_schema(v, depth + 1, max_depth) for k, v in obj.items()}
    return type(obj).__name__

def flatten_schema(schema, prefix=""):
    paths = {}
    if isinstance(schema, dict):
        for k, v in schema.items():
            full = f"{prefix}.{k}" if prefix else k
            if isinstance(v, dict):
                paths.update(flatten_schema(v, full))
            else:
                paths[full] = v
    else:
        paths[prefix] = schema
    return paths

def diff_schemas(saved, live):
    s = set(flatten_schema(saved).keys())
    l = set(flatten_schema(live).keys())
    return l - s, s - l  # added, removed

def check_snapshot(name, live_data):
    live_schema = extract_schema(live_data)
    path = os.path.join(snapshot_dir, f"{name}.json")
    if save_snapshots or not os.path.exists(path):
        os.makedirs(snapshot_dir, exist_ok=True)
        with open(path, "w") as f:
            json.dump(live_schema, f, indent=2)
        if not save_snapshots:
            info(f"Snapshot saved: {name}.json (first run — re-run to compare)")
        else:
            info(f"Snapshot updated: {name}.json")
        return
    with open(path) as f:
        saved_schema = json.load(f)
    added, removed = diff_schemas(saved_schema, live_schema)
    if not added and not removed:
        ok(f"Schema unchanged vs snapshot ({name}.json)")
    else:
        for key in sorted(removed):
            warn(f"Schema key REMOVED: {key}  [{name}.json]")
        for key in sorted(added):
            warn(f"Schema key ADDED:   {key}  [{name}.json]")

# ── Endpoint 1: GET /hub2/appsList ────────────────────────────────────────────
section(f"GET /hub2/appsList")

apps_list = fetch("/hub2/appsList")
test_rule = None

if apps_list is None:
    fail("No response — cannot continue appsList checks")
elif not isinstance(apps_list, dict) or "apps" not in apps_list:
    fail("BREAKING: response missing top-level 'apps' key — getRuleMachineRuleApps() will return empty list")
else:
    ok("Response has top-level 'apps' key")
    apps = apps_list["apps"]

    if not isinstance(apps, list):
        fail("BREAKING: 'apps' is not a list")
    else:
        ok("'apps' is a list")

        first_with_data = next((e for e in apps if isinstance(e.get("data"), dict)), None)
        if not first_with_data:
            warn("No entries with 'data' dict found — cannot validate field structure")
        else:
            data = first_with_data["data"]
            # type, name, id, disabled — required by getRuleMachineRuleApps(); label is optional (defaults to "")
            for field in ["type", "name", "id", "disabled"]:
                if field in data:
                    ok(f"apps[].data.{field} present")
                else:
                    fail(f"BREAKING: apps[].data.{field} missing — getRuleMachineRuleApps() will malfunction")
            if "label" not in data:
                warn("apps[].data.label absent (optional — Groovy defaults to empty string)")

            if "children" in first_with_data:
                ok("apps[].children key present")
            else:
                fail("BREAKING: apps[].children missing — rule discovery will find no rules")

            # Search all entries for a child that has a data dict (first parent may have no RM/BC children)
            first_child = None
            for entry in apps:
                children = entry.get("children") or []
                for child in children:
                    if isinstance(child.get("data"), dict):
                        first_child = child
                        break
                    for grandchild in (child.get("children") or []):
                        if isinstance(grandchild.get("data"), dict):
                            first_child = grandchild
                            break
                if first_child:
                    break

            if first_child:
                child_data = first_child["data"]
                for field in ["id", "name"]:
                    if field in child_data:
                        ok(f"apps[].children[].data.{field} present")
                    else:
                        fail(f"BREAKING: apps[].children[].data.{field} missing — rules will be skipped")
            else:
                warn("No child entries with 'data' found to validate child field structure")

    check_snapshot("appsList", apps_list)

    # Discover a test rule for downstream endpoints
    def find_rm_rule(entries):
        for entry in entries:
            pd = entry.get("data") or {}
            ptype  = str(pd.get("type",  "") or "").lower()
            pname  = str(pd.get("name",  "") or "").lower()
            plabel = str(pd.get("label", "") or "").lower()
            combined = f"{ptype} {pname} {plabel}"
            if "basic button controller" in combined or "basicbuttoncontroller" in combined:
                continue
            if "rule machine" in combined or "button controller" in combined or "buttoncontroller" in combined or "rule" in ptype:
                for child in (entry.get("children") or []):
                    grandchildren = child.get("children") or []
                    nodes = grandchildren if grandchildren else [child]
                    for node in nodes:
                        d = node.get("data") or {}
                        if d.get("id") and d.get("name"):
                            return {"id": str(d["id"]), "name": str(d["name"])}
        return None

    test_rule = find_rm_rule(apps_list.get("apps", []))
    if test_rule:
        info(f"Test subject: '{test_rule['name']}' (id={test_rule['id']})")
    else:
        warn("No RM/BC rule found — statusJson and configure/json tests will be skipped")

# ── Endpoint 2: GET /installedapp/statusJson/{ruleId} ─────────────────────────
if test_rule:
    rule_id = test_rule["id"]
    section(f"GET /installedapp/statusJson/{rule_id}")

    status = fetch(f"/installedapp/statusJson/{rule_id}")

    if status is None:
        fail("No response")
    elif not isinstance(status, dict):
        fail(f"BREAKING: response is not a JSON object (got {type(status).__name__})")
    else:
        ok("Response is a JSON object")

        containers = [k for k in ["appSettings", "settings", "state"] if k in status]
        if containers:
            ok(f"Scanning containers present: {containers}")
        else:
            warn("None of appSettings/settings/state present — detectRuleLogging() will find no candidates")

        if "appState" in status:
            app_state = status["appState"]
            if not isinstance(app_state, list):
                fail("BREAKING: appState is not a list — extractLastRun() iteration will fail")
            else:
                ok(f"appState is a list ({len(app_state)} items)")
                if app_state:
                    item = app_state[0]
                    if "name" in item and "value" in item:
                        ok("appState items have 'name' and 'value' keys")
                    else:
                        fail("BREAKING: appState items missing 'name' or 'value' — extractLastRun() will fail")

                    names_found = {i["name"] for i in app_state if isinstance(i.get("name"), str)}
                    for field in ["lastEvtDate", "lastEvtTime", "timeFormat", "dateFormat"]:
                        if field in names_found:
                            ok(f"appState contains '{field}' entry")
                        else:
                            info(f"appState has no '{field}' entry (optional; Last Run may be blank for this rule)")
        else:
            info("appState not present — Last Run column will be blank for this rule")

        check_snapshot(f"statusJson_{rule_id}", status)

# ── Endpoint 3: GET /installedapp/configure/json/{ruleId} ────────────────────
if test_rule:
    section(f"GET /installedapp/configure/json/{rule_id}")

    config_data = fetch(f"/installedapp/configure/json/{rule_id}")

    if config_data is None:
        fail("No response")
    elif not isinstance(config_data, dict):
        fail(f"BREAKING: response is not a JSON object (got {type(config_data).__name__})")
    else:
        ok("Response is a JSON object")

        # app key
        if "app" not in config_data:
            fail("BREAKING: 'app' key missing — buildPostBody() will use empty version/label")
        else:
            ok("'app' key present")
            app_info = config_data["app"]
            for field in ["version", "label"]:
                if field in app_info:
                    ok(f"app.{field} present")
                else:
                    fail(f"BREAKING: app.{field} missing — POST body will be malformed")

        # configPage key
        if "configPage" not in config_data:
            fail("BREAKING: 'configPage' key missing — toggle operation will fail entirely")
        else:
            ok("'configPage' key present")
            config_page = config_data["configPage"]

            if "name" in config_page:
                ok(f"configPage.name present ('{config_page['name']}')")
            else:
                fail("BREAKING: configPage.name missing — buildPostBody() pageName will be wrong")

            sections = config_page.get("sections")
            if not isinstance(sections, list):
                fail("BREAKING: configPage.sections missing or not a list — no inputs can be read")
            else:
                ok(f"configPage.sections is a list ({len(sections)} sections)")

                # Check first body element and first input element across all sections
                body_checked = False
                input_checked = False
                for sec in sections:
                    if not body_checked:
                        for elem in (sec.get("body") or []):
                            if isinstance(elem, dict):
                                for field in ["element", "name"]:
                                    if field in elem:
                                        ok(f"sections[].body[].{field} present")
                                    else:
                                        warn(f"sections[].body[].{field} absent (label inputs may be missed)")
                                body_checked = True
                                break
                    if not input_checked:
                        for inp in (sec.get("input") or []):
                            if isinstance(inp, dict):
                                for field in ["name", "type", "multiple"]:
                                    if field in inp:
                                        ok(f"sections[].input[].{field} present")
                                    else:
                                        fail(f"BREAKING: sections[].input[].{field} missing — POST body will be malformed")
                                if inp.get("type") == "enum" and "options" not in inp:
                                    fail("BREAKING: enum input missing 'options' — enum toggle will fail")
                                input_checked = True
                                break

        # settings key
        if "settings" in config_data:
            ok("'settings' key present")
        else:
            fail("BREAKING: 'settings' key missing — buildPostBody() will send empty values for all inputs")

        check_snapshot(f"configureJson_{rule_id}", config_data)

# ── Endpoint 4: POST /installedapp/update/json ───────────────────────────────
section("POST /installedapp/update/json (precondition check — no POST issued)")
ok("POST not issued — live rule settings will not be modified")
info("configure/json structural checks above validate all POST preconditions")
info("POST response contract: JSON object with status == \"success\"")

# ── Summary ───────────────────────────────────────────────────────────────────
total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if warnings:
    print(f", {YELLOW}{warnings} warning{'s' if warnings != 1 else ''}{RESET}{BOLD}", end="")
if failed:
    print(f", {RED}{failed} failed{RESET}{BOLD}", end="")
print(f" ==={RESET}")

sys.exit(1 if failed else 0)
PYTHON_SCRIPT
