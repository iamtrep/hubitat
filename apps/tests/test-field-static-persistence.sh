#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# test-field-static-persistence — verifies @Field static survives code pushes
#
# Asserts the memory claim recorded in
#   memory/hubitat_field_static_persistence.md
# that, as of Hubitat 2.5.0.x, `@Field static` values in apps and drivers
# persist across `/app/ajax/update` (and the equivalent for drivers).
#
# Probe: apps/tests/FieldStaticPersistenceTest.groovy — single app with
#   @Field static AtomicInteger fspCounter
# and three buttons: btnIncrement / btnReset / btnReport. Each emits a
# log line `FSP_COUNTER=<n> after=<op>` so logsocket can read the value.
#
# Sequence:
#   Phase 1 — provision + baseline
#     a. Push source (create the app type if missing; update if present).
#     b. Create the installed instance if missing.
#     c. btnReset → btnIncrement ×3 → btnReport
#        assert log saw counters 0, 1, 2, 3, 3 in order.
#   Phase 2 — the assertion
#     d. Push a marker variant of the source (extra comment) so the hub
#        is forced to recompile, not no-op.
#     e. btnReport → assert log shows counter still = 3.
#        (If @Field static reset on push, this would be 0 → FAIL.)
#     f. btnIncrement → assert counter = 4 (continuity).
#   Cleanup
#     g. Restore the canonical source on the hub by pushing the disk copy.
#
# Usage:
#   bash apps/tests/test-field-static-persistence.sh             # default hub
#   bash apps/tests/test-field-static-persistence.sh @maison-pro # specific hub
#
# Runtime budget: ~20s.
#
# Per TESTING.md §1.1: single invocation, exit 0/1/2, [PASS]/[FAIL]/[WARN]/[INFO]
# labels, idempotent (reset button at start of every run), no production
# mutation (dedicated test instance), no hardcoded IPs.
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
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/hubitat-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    fi
done

SOURCE_FILE="$SCRIPT_DIR/FieldStaticPersistenceTest.groovy"
if [[ ! -f "$SOURCE_FILE" ]]; then
    echo "Missing app source: $SOURCE_FILE" >&2
    exit 2
fi

python3 - "$HUB_NAME" "$CONFIG_FILE" "$COOKIE_JAR" "$PROJECT_ROOT" "$SOURCE_FILE" <<'PYTHON_SCRIPT'
import json, sys, re, time, urllib.request, urllib.parse, urllib.error, http.cookiejar

APP_TYPE_NAME      = "Field Static Persistence Test"
APP_NAMESPACE      = "tests"
INSTANCE_LABEL     = "test-field-static-persistence"
RUNTIME_BUDGET     = 20
BTN_PROPAGATE_S    = 1.2   # time for a button-click log line to traverse the logsocket
PUSH_PROPAGATE_S   = 1.0   # time for /app/ajax/update to settle before we click again

hub_name_arg, config_file, cookie_jar_path, project_root, source_file = sys.argv[1:6]
sys.path.insert(0, f"{project_root}/scripts/lib")
from logsocket import LogCapture

GREEN  = "\033[32m"; RED = "\033[31m"; YELLOW = "\033[33m"
CYAN   = "\033[36m"; DIM = "\033[2m"; RESET  = "\033[0m"

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

# ── Config + hub selection ────────────────────────────────────────────
try:
    with open(config_file) as f:
        config = json.load(f)
except FileNotFoundError:
    die(f"Config not found: {config_file}")

hub_name = hub_name_arg or config.get("default_hub", "")
hub = config.get("hubs", {}).get(hub_name)
if not hub:
    die(f"Hub '{hub_name}' not found in .hubitat.json")

hub_ip   = hub["hub_ip"]
username = hub.get("username")
password = hub.get("password")

cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def hub_get_json(path, timeout=15):
    url = f"http://{hub_ip}{path}"
    try:
        with opener.open(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

def hub_get_raw(path, timeout=15):
    url = f"http://{hub_ip}{path}"
    try:
        with opener.open(url, timeout=timeout) as r:
            return r.status, r.read().decode(errors="replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace"), dict(e.headers)
    except Exception as e:
        return None, str(e), {}

if username and password:
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
    except Exception as e:
        die(f"Hub auth failed: {e}")

info(f"Hub: {hub_name} ({hub_ip})")
info(f"Runtime budget: ~{RUNTIME_BUDGET}s")

# Firmware version is context: the claim is bound to platform >= 2.5.0.x.
hub_data = hub_get_json("/hub2/hubData") or {}
fw = hub_data.get("version") or "unknown"
info(f"Hub firmware: {fw}")
m = re.match(r"^(\d+)\.(\d+)\.(\d+)", str(fw))
if m and (int(m.group(1)), int(m.group(2)), int(m.group(3))) < (2, 5, 0):
    warn(f"firmware {fw} predates 2.5.0; the claim under test only applies from 2.5.0.x")

# ── Phase 0: ensure app type + installed instance exist ──────────────
section("Provision")

with open(source_file) as f:
    canonical_source = f.read()

def find_app_type():
    types = hub_get_json("/hub2/userAppTypes") or []
    for t in types:
        if t.get("name") == APP_TYPE_NAME and t.get("namespace") == APP_NAMESPACE:
            return t
    return None

def create_app_type(source):
    body = json.dumps({"source": source, "version": 1}).encode()
    req = urllib.request.Request(
        f"http://{hub_ip}/app/saveOrUpdateJson",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    try:
        with opener.open(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"_error": str(e)}

def get_app_code(app_type_id):
    return hub_get_json(f"/app/ajax/code?id={app_type_id}")

def push_app_code(app_type_id, version, source):
    body = urllib.parse.urlencode({
        "id": str(app_type_id),
        "version": str(version),
        "source": source,
    }).encode()
    req = urllib.request.Request(
        f"http://{hub_ip}/app/ajax/update",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with opener.open(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"_error": str(e)}

app_type = find_app_type()
if app_type is None:
    info(f"App type '{APP_TYPE_NAME}' not on hub — creating")
    created = create_app_type(canonical_source)
    if not created.get("success") and not created.get("id"):
        die(f"Failed to create app type: {created}")
    app_type_id = created.get("id")
    info(f"Created app type id={app_type_id}")
else:
    app_type_id = app_type.get("id")
    info(f"App type already present id={app_type_id}")
    # Sync the hub copy to the disk copy as a baseline.
    code = get_app_code(app_type_id) or {}
    push_resp = push_app_code(app_type_id, code.get("version", 1), canonical_source)
    if push_resp.get("status") != "success":
        warn(f"baseline push returned: {push_resp}")

# Locate the installed instance via /hub2/appsList by label.
def walk_apps(entries, hits):
    for entry in entries:
        d = entry.get("data") or {}
        if isinstance(d, dict):
            hits.append(d)
        for child in entry.get("children") or []:
            walk_apps([child], hits)
    return hits

def find_instance():
    apps_list = hub_get_json("/hub2/appsList") or {}
    all_apps = walk_apps(apps_list.get("apps") or [], [])
    for a in all_apps:
        # On appsList, the label is in `data.name`; `data.label` is null.
        if a.get("type") == APP_TYPE_NAME and a.get("name") == INSTANCE_LABEL:
            return a.get("id")
    return None

def create_instance(app_type_id):
    # GET /installedapp/create/{appTypeId} → 302 → Location: /installedapp/configure/{newId}
    req = urllib.request.Request(f"http://{hub_ip}/installedapp/create/{app_type_id}")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *_args, **_kwargs): return None
    local_opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cj), NoRedirect()
    )
    try:
        local_opener.open(req, timeout=15)
        return None
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307):
            loc = e.headers.get("Location", "")
            m = re.search(r"/installedapp/configure/(\d+)", loc)
            if m:
                return int(m.group(1))
        return None
    except Exception:
        return None

def set_instance_label(instance_id, label):
    # The label needs to be saved on the configure page; minimal POST that
    # /installedapp/update/json accepts. If this fails we still proceed —
    # subsequent runs will use the (unlabeled) instance by type only.
    body = urllib.parse.urlencode({
        "id": str(instance_id),
        "version": "1",
        "currentPage": "mainPage",
        "formAction": "update",
        "url": f"/installedapp/configure/{instance_id}/mainPage",
        "pageBreadcrumbs": "[]",
        "referrer": f"/installedapp/configure/{instance_id}",
        "_action_update": "Done",
        "_cancellable": "false",
        "label.type": "text",
        "label": label,
        "app.label": label,
    }).encode()
    req = urllib.request.Request(
        f"http://{hub_ip}/installedapp/update/json",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with opener.open(req, timeout=15) as r:
            return r.status == 200
    except Exception:
        return False

instance_id = find_instance()
if instance_id is None:
    # Look for any unlabeled instance of this type before creating a new one.
    apps_list = hub_get_json("/hub2/appsList") or {}
    all_apps = walk_apps(apps_list.get("apps") or [], [])
    for a in all_apps:
        if a.get("type") == APP_TYPE_NAME:
            instance_id = a.get("id")
            info(f"Adopting unlabeled instance id={instance_id}; labeling it")
            set_instance_label(instance_id, INSTANCE_LABEL)
            break

if instance_id is None:
    info(f"No instance — creating one")
    instance_id = create_instance(app_type_id)
    if instance_id is None:
        die("Failed to create app instance (no 302 Location)")
    set_instance_label(instance_id, INSTANCE_LABEL)
    info(f"Created instance id={instance_id}")
else:
    info(f"Instance present id={instance_id} label='{INSTANCE_LABEL}'")

ok(f"Provisioned app type id={app_type_id} + instance id={instance_id}")

# ── Helpers ───────────────────────────────────────────────────────────
def app_button(btn, timeout=15):
    body = urllib.parse.urlencode({
        "id": str(instance_id),
        "name": btn,
        f"settings[{btn}]": "clicked",
        f"{btn}.type": "button",
    }).encode()
    req = urllib.request.Request(
        f"http://{hub_ip}/installedapp/btn",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False

COUNTER_RE = re.compile(r"FSP_COUNTER=(\d+)\s+after=(\w+)")

def captured_counters(cap):
    # All FSP_COUNTER lines from our SUT, in order, as (value:int, after:str).
    out = []
    for m in cap.find_all(r"FSP_COUNTER=", source=str(instance_id)):
        match = COUNTER_RE.search(m.get("msg") or "")
        if match:
            out.append((int(match.group(1)), match.group(2)))
    return out

hub_creds = (username, password)

# ── Phase 1: baseline (reset + 3 increments + report) ────────────────
section("Phase 1 — baseline counter sequence")

with LogCapture(hub_ip=hub_ip, username=hub_creds[0], password=hub_creds[1]) as cap:
    for step in ["btnReset", "btnIncrement", "btnIncrement", "btnIncrement", "btnReport"]:
        if not app_button(step):
            warn(f"button click HTTP non-200: {step}")
        time.sleep(BTN_PROPAGATE_S)
    # extra grace so the last log line definitely reaches us
    time.sleep(0.5)

seq = captured_counters(cap)
expected = [(0, "reset"), (1, "increment"), (2, "increment"), (3, "increment"), (3, "report")]
if seq == expected:
    ok(f"counter sequence: {seq}")
else:
    fail(f"counter sequence: expected {expected}, got {seq}")
    # If we never saw any FSP_COUNTER lines, the rest of the test will
    # be meaningless; bail before phase 2.
    if not seq:
        die("No FSP_COUNTER log lines captured — abandoning phase 2", code=1)

# ── Phase 2: push again + assert counter survives ────────────────────
section("Phase 2 — push code, counter must persist")

code_info = get_app_code(app_type_id) or {}
current_version = code_info.get("version", 1)

marker = f"// fsp-test-marker run={int(time.time())}"
marker_source = canonical_source.rstrip() + "\n" + marker + "\n"

push_resp = push_app_code(app_type_id, current_version, marker_source)
if push_resp.get("status") != "success":
    fail(f"marker push returned {push_resp}; cannot evaluate persistence")
else:
    ok(f"pushed marker source (version {current_version} → {push_resp.get('version')})")
    time.sleep(PUSH_PROPAGATE_S)

    with LogCapture(hub_ip=hub_ip, username=hub_creds[0], password=hub_creds[1]) as cap2:
        for step in ["btnReport", "btnIncrement"]:
            if not app_button(step):
                warn(f"button click HTTP non-200: {step}")
            time.sleep(BTN_PROPAGATE_S)
        time.sleep(0.5)

    seq2 = captured_counters(cap2)
    expected2 = [(3, "report"), (4, "increment")]
    if seq2 == expected2:
        ok(f"counter survived code push: {seq2}")
    elif seq2 and seq2[0][0] == 0:
        fail(f"counter RESET on code push (saw {seq2}) — claim is FALSE on firmware {fw}")
    else:
        fail(f"unexpected sequence after push: expected {expected2}, got {seq2}")

# ── Cleanup: restore canonical source ────────────────────────────────
section("Cleanup")
code_info = get_app_code(app_type_id) or {}
restore_resp = push_app_code(app_type_id, code_info.get("version", 1), canonical_source)
if restore_resp.get("status") == "success":
    info("Restored canonical source on the hub")
else:
    warn(f"restore push returned {restore_resp}")

# ── Trailer ───────────────────────────────────────────────────────────
elapsed = int(time.time() - start_time)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s)")
if elapsed > RUNTIME_BUDGET:
    print(f"{YELLOW}NOTE: exceeded declared runtime budget of {RUNTIME_BUDGET}s{RESET}")

sys.exit(0 if failed == 0 else 1)
PYTHON_SCRIPT
