#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub with a Zigbee router device

#
# test-zigbee-workbench — live test of the Zigbee Workbench request engine
#
# Reads a real Zigbee device through the app and checks the decoded results in
# the app's state. Sent to the device: ZDO table and descriptor requests, Read
# Attributes, Discover Attributes, Read Reporting Configuration of OnOff, and one
# write: StartUpOnOff (0006/4003) is read and the value just read is written back
# through the confirmation page, which must show the write's frame (skipped with a
# warning when the device lacks it; a device without On/Off skips its cases and
# discovers Basic instead). The Leave button is pressed but never
# confirmed: the test checks that nothing is sent. A monitor device is
# created for the device, polled (neighbour and routing tables) and checked, then
# removed through its confirmation page; none is left behind. The test then checks
# that the device's driver, DNI, driver state, settings and data did not change
# (a receive timestamp the device's own driver keeps may move: it sees the replies).
#
# Setup (idempotent): installs the app type, the radio and monitor drivers if missing,
# creates the single app instance if missing (it creates its radio child on
# save), and adds the device to the app's device input. Nothing is removed.
#
# Usage:
#   bash apps/ZigbeeWorkbench/tests/test-zigbee-workbench.sh [@hub] [<instance-id>] --device "<label>"
#     --device  label of the Zigbee router to read (required), e.g. --device "My Zigbee Plug"
#   ZIGBEE_WORKBENCH_DEVICE="<label>" bash apps/ZigbeeWorkbench/tests/test-zigbee-workbench.sh [@hub]   # label from the environment, for a runner that passes only the hub
#   RUN_SLOW_TESTS=1 bash apps/ZigbeeWorkbench/tests/test-zigbee-workbench.sh [@hub] --device "<label>"   # all cases
#
# Runtime budget: ~60 s by default (endpoints, simple descriptor, attribute read and discover,
# StartUpOnOff read/write/read, reporting configuration, 5 s leave guard, monitor device with one
# poll of the paged tables, unchanged-device checks); RUN_SLOW_TESTS=1 adds the neighbour and routing tables (paged, one
# request per page), node and power descriptors, bindings, a concurrent
# burst, an interview and the hub tables. Upper bound: 30 s per request case, 120 s per paged table,
# 180 s for the interview and 60 s for the hub tables. A device that never answers makes the default run take about 2.5 minutes.
#
# Per TESTING.md §1.1: exit 0 all passed, 1 a check failed, 2 could not run;
# [PASS]/[FAIL]/[WARN]/[INFO] labels; no hardcoded IPs.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
while [[ "$PROJECT_ROOT" != "/" && ! -e "$PROJECT_ROOT/.hubitat.json" ]]; do
    PROJECT_ROOT="$(dirname "$PROJECT_ROOT")"
done
if [[ -z "${HUBITAT_CONFIG:-}" && ! -e "$PROJECT_ROOT/.hubitat.json" ]]; then
    echo "Could not find .hubitat.json walking up from $SCRIPT_DIR" >&2
    exit 2
fi
# HUBITAT_CONFIG (absolute path) points the test at a different hub config file.
CONFIG_FILE="${HUBITAT_CONFIG:-$PROJECT_ROOT/.hubitat.json}"
APP_SOURCE="$SCRIPT_DIR/../ZigbeeWorkbench.groovy"
DRIVER_SOURCE="$SCRIPT_DIR/../ZigbeeWorkbenchRadio.groovy"
MONITOR_SOURCE="$SCRIPT_DIR/../ZigbeeWorkbenchMonitor.groovy"
LIB_DIR="$(cd "$SCRIPT_DIR/../../../scripts/lib" && pwd)"

python3 - "$CONFIG_FILE" "$APP_SOURCE" "$DRIVER_SOURCE" "$MONITOR_SOURCE" "$LIB_DIR" "$@" <<'PYTHON_SCRIPT'
import datetime, http.cookiejar, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request

config_file, app_source, driver_source, monitor_source, lib_dir = sys.argv[1:6]
args = sys.argv[6:]
# scripts/lib/logsocket.py provides LogCapture (log lines); it needs the websockets package.
sys.path.insert(0, lib_dir)
from logsocket import LogCapture

USAGE = ('usage: bash apps/ZigbeeWorkbench/tests/test-zigbee-workbench.sh [@hub] [<instance-id>] --device "<label>"\n'
         '  --device  label of a Zigbee router to read, e.g. --device "My Zigbee Plug" (required;\n'
         '            or set ZIGBEE_WORKBENCH_DEVICE, for a test runner that passes only the hub)')
DEVICE_LABEL = None
if "--device" in args:
    i = args.index("--device")
    if i + 1 < len(args) and args[i + 1].strip():
        DEVICE_LABEL = args[i + 1]
        args = args[:i] + args[i + 2:]
# The test runner passes only @hub, so it supplies the label through the environment.
DEVICE_LABEL = DEVICE_LABEL or os.environ.get("ZIGBEE_WORKBENCH_DEVICE", "").strip() or None
if not DEVICE_LABEL:
    print(USAGE); sys.exit(2)
HUB_NAME = next((a[1:] for a in args if a.startswith("@")), None)
INSTANCE_ID = next((a for a in args if a.isdigit()), None)

APP_TYPE, DRIVER_TYPE, MONITOR_TYPE, NAMESPACE = "Zigbee Workbench", "Zigbee Workbench Radio", "Zigbee Workbench Monitor", "iamtrep"
WAIT_S = 30
TABLE_WAIT_S = 120   # a paged table: one request per page
SLOW = os.environ.get("RUN_SLOW_TESTS") == "1"

GREEN, RED, YELLOW, CYAN, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[36m", "\033[2m", "\033[0m"
passed = failed = warnings = 0

def ok(msg):
    global passed; passed += 1; print(f"  {GREEN}[PASS]{RESET} {msg}")
def fail(msg):
    global failed; failed += 1; print(f"  {RED}[FAIL]{RESET} {msg}")
def warn(msg):
    global warnings; warnings += 1; print(f"  {YELLOW}[WARN]{RESET} {msg}")
def info(msg): print(f"  {DIM}[INFO] {msg}{RESET}")
def section(msg): print(f"\n{CYAN}--- {msg} ---{RESET}")
def die(msg):
    print(f"{RED}{msg}{RESET}"); sys.exit(2)
def check(cond, msg_ok, msg_fail):
    ok(msg_ok) if cond else fail(msg_fail)

# ── Hub access ────────────────────────────────────────────────────────
try:
    with open(config_file) as f: config = json.load(f)
except (OSError, ValueError) as e:
    die(f"Cannot read the hub config: {e}")
hub_name = HUB_NAME or config.get("default_hub", "")
hub_cfg = config.get("hubs", {}).get(hub_name)
if not hub_cfg:
    die(f"Hub '{hub_name}' not found in .hubitat.json")
hub = f"http://{hub_cfg['hub_ip']}"
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(method, path, form=None, json_body=None, expect="json", timeout=30):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
    elif json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(hub + path, data=data, method=method, headers=headers)
    with opener.open(req, timeout=timeout) as r:
        body = r.read().decode(errors="replace")
        if expect == "url": return r.geturl()
        if expect == "text": return body
        return json.loads(body) if body.strip() else None

try:
    if hub_cfg.get("username") and hub_cfg.get("password"):
        request("POST", "/login", form={"username": hub_cfg["username"], "password": hub_cfg["password"]}, expect="text")
    fw = (request("GET", "/hub2/hubData") or {}).get("baseModel", {}).get("buildVersion", "unknown")
except Exception as e:
    die(f"Hub unreachable or login failed: {e}")
info(f"Hub: {hub_name}, firmware {fw}")

def walk(entries, out):
    for e in entries:
        d = e.get("data") or {}
        if isinstance(d, dict): out.append(d)
        walk(e.get("children") or [], out)
    return out

def ensure_code(kind, name, source_path):
    listing = request("GET", "/hub2/userAppTypes" if kind == "app" else "/hub2/userDeviceTypes") or []
    found = next((t for t in listing if t.get("name") == name and t.get("namespace") == NAMESPACE), None)
    if found:
        return str(found["id"]), False
    with open(source_path) as f: src = f.read()
    r = request("POST", f"/{kind}/saveOrUpdateJson", json_body={"source": src, "version": 1}, timeout=90)
    if not r or not r.get("success"):
        raise RuntimeError(f"{name}: {(r or {}).get('message') or r}")
    return str(r["id"]), True

def status_json(iid):
    return request("GET", f"/installedapp/statusJson/{iid}")

def app_state(iid):
    out = {}
    for s in status_json(iid).get("appState") or []:
        v = s.get("value")
        if isinstance(v, str):
            try: v = json.loads(v)
            except ValueError: pass
        out[s["name"]] = v
    return out

def press(iid, button):
    request("POST", "/installedapp/btn", form={"id": iid, "name": button, f"settings[{button}]": "clicked",
                                              f"{button}.type": "button"}, expect="text")

def save_main(iid, device_ids):
    """Done on mainPage with the device input set, every other main-page input echoed."""
    cfg = request("GET", f"/installedapp/configure/json/{iid}")
    current = cfg.get("settings") or {}
    inputs = [i for s in cfg["configPage"].get("sections", []) for i in s.get("input", [])]
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", iid),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]"), ("label", cfg["app"].get("label") or APP_TYPE)]
    for i in inputs:
        name, typ = i["name"], i.get("type", "")
        if typ in ("button", "paragraph", "href"): continue
        fields += [(f"{name}.type", typ), (f"{name}.multiple", "true" if i.get("multiple") else "false")]
        if typ.startswith("capability.") or typ.startswith("device."):
            ids = device_ids if name == "zbDevices" else list((current.get(name) or {}).keys())
            # an empty value clears the input; leaving the field out keeps what was picked
            if ids or name == "zbDevices": fields.append((f"settings[{name}]", ",".join(ids)))
            fields += [("deviceList", name), ("", "")]
            continue
        v = current.get(name)
        if v is None or v == "": continue
        if typ == "bool":
            fields.append((f"checkbox[{name}]", "on"))
            v = "true" if str(v).lower() == "true" else "false"
        fields.append((f"settings[{name}]", str(v)))
    fields += [("referrer", f"{hub}/installedapp/list"), ("url", f"{hub}/installedapp/configure/{iid}/mainPage"),
               ("_cancellable", "false")]
    r = request("POST", "/installedapp/update/json", form=fields)
    if not isinstance(r, dict) or r.get("status") != "success":
        raise RuntimeError(f"settings save rejected: {r}")

# ── Setup ─────────────────────────────────────────────────────────────
section("Setup")
try:
    drv_id, drv_new = ensure_code("driver", DRIVER_TYPE, driver_source)
    mon_drv_id, mon_new = ensure_code("driver", MONITOR_TYPE, monitor_source)
    app_type_id, app_new = ensure_code("app", APP_TYPE, app_source)
except Exception as e:
    die(f"Install failed: {e}")
info(f"radio driver type {drv_id}{' (installed now)' if drv_new else ''}, monitor driver type {mon_drv_id}{' (installed now)' if mon_new else ''}, "
     f"app type {app_type_id}{' (installed now)' if app_new else ''}")

devices = walk((request("GET", "/hub2/devicesList") or {}).get("devices") or [], [])
target = next((d for d in devices if (d.get("name") == DEVICE_LABEL or d.get("label") == DEVICE_LABEL)), None)
if not target:
    die(f"No device labelled '{DEVICE_LABEL}'")
dev_id = str(target["id"])

def full():
    return request("GET", f"/device/fullJson/{dev_id}")

def snapshot(fj):
    d = fj.get("device") or {}
    return {"driver": (d.get("deviceTypeId"), d.get("deviceTypeName"), d.get("deviceTypeNamespace")),
            "dni": d.get("deviceNetworkId"), "deviceState": fj.get("deviceState"),
            "settings": {s.get("name"): s.get("value") for s in fj.get("settings") or []},
            "data": d.get("data")}

before_full = full()
before = snapshot(before_full)
ieee = ((before_full.get("device") or {}).get("zigbeeId") or "").upper()
if not ieee:
    die(f"'{DEVICE_LABEL}' is not a Zigbee device")
info(f"device {dev_id}, IEEE {ieee}, DNI {before['dni']}")

iid = INSTANCE_ID
if not iid:
    apps = walk((request("GET", "/hub2/appsList") or {}).get("apps") or [], [])
    inst = next((a for a in apps if a.get("type") == APP_TYPE), None)
    iid = str(inst["id"]) if inst else None
if not iid:
    url = request("GET", f"/installedapp/create/{app_type_id}", expect="url")
    m = re.search(r"/installedapp/configure/(\d+)", url)
    if not m: die(f"could not create the app instance (landed on {url})")
    iid = m.group(1)
    info(f"created app instance {iid}")
else:
    info(f"app instance {iid}")

try:
    cfg = request("GET", f"/installedapp/configure/json/{iid}")
    picked = list(((cfg.get("settings") or {}).get("zbDevices") or {}).keys())
    if dev_id not in picked: picked.append(dev_id)
    save_main(iid, picked)
except Exception as e:
    die(f"could not select the device in the app: {e}")

st = {}
for _ in range(10):
    st = app_state(iid)
    if ieee in (st.get("targets") or {}): break
    time.sleep(1)
tgt = (st.get("targets") or {}).get(ieee)
check(tgt is not None, f"app tracks the device by IEEE (endpoint {tgt and tgt.get('ep')})",
      f"app state.targets lacks {ieee}: {st.get('targets')}")
if tgt is None:
    print(f"\n{passed} passed, {failed} failed, {warnings} warnings"); sys.exit(1)

children = (status_json(iid).get("childDevices") or [])
radio = next((c for c in children if (c.get("deviceNetworkId") or c.get("dni")) == "zbworkbench-radio" or c.get("name") == DRIVER_TYPE), None)
check(radio is not None or any(DRIVER_TYPE in json.dumps(c) for c in children),
      "radio child device exists", f"no radio child under app {iid}: {children}")

# The radio keeps its log socket open; right after a save or reboot it may still be connecting.
def radio_ws():
    if radio is None: return None
    cs = ((request("GET", f"/device/fullJson/{radio.get('id')}") or {}).get("device") or {}).get("currentStates") or {}
    return (cs.get("wsStatus") or {}).get("value")
ws = None
for _ in range(30):
    ws = radio_ws()
    if ws == "connected": break
    time.sleep(1)
check(ws == "connected", "the radio's log socket is open", f"radio wsStatus {ws!r} after 30 s")

# ── Cases ─────────────────────────────────────────────────────────────
capture = LogCapture(hub_ip=hub_cfg["hub_ip"], opener=opener)
capture.__enter__()
def result(kind):
    return ((app_state(iid).get("results") or {}).get(ieee) or {}).get(kind)

def run_case(button, kind, wait_s=WAIT_S):
    prev = result(kind)
    prev_t = prev.get("time") if prev else None
    t0 = time.time()
    press(iid, button)
    while time.time() - t0 < wait_s:
        time.sleep(0.5)
        r = result(kind)
        if r and r.get("time") != prev_t and r.get("status") != "pending":
            info(f"{kind}: {r.get('status')} after {time.time() - t0:.1f} s")
            return r
    return None

def confirm_view(button, action):
    """Presses a button that changes the device; returns the token once the confirmation page shows its frame."""
    press(iid, button)
    view = app_state(iid).get("confirmView") or {}
    token, frame = view.get("token"), (view.get("args") or {}).get("frame")
    if not (view.get("action") == action and view.get("ieee") == ieee and token and frame):
        fail(f"{action}: no confirmation prepared ({view})"); return None, None
    page = request("GET", f"/installedapp/configure/json/{iid}/confirmPage")
    names = [i.get("name") for sec in (page.get("configPage") or {}).get("sections", []) for i in sec.get("input", [])]
    if f"zbw:confirm:{token}" not in names or frame not in json.dumps(page):
        fail(f"{action}: the confirmation page lacks Confirm or the frame {frame!r} (inputs {names})"); return None, None
    return token, frame

def ok_result(r, kind):
    if r is None:
        fail(f"{kind}: no result within {WAIT_S} s"); return False
    if r.get("status") != "ok":
        fail(f"{kind}: {r.get('status')} {r.get('statusName') or ''} {r.get('error')} frame {r.get('frame')}"); return False
    return True

section("Active endpoints and simple descriptor")
r = run_case(f"zbw:endpoints:{ieee}", "endpoints")
if ok_result(r, "endpoints"):
    eps = r["data"].get("endpoints") or []
    check("01" in eps, f"endpoints {eps} include 01", f"endpoints {eps} lack 01")
# A router without On/Off (a presence sensor, say) skips the On/Off cases and
# discovers Basic instead.
has_onoff = True
r = run_case(f"zbw:simple:{ieee}:01", "simple:01")
if ok_result(r, "simple:01"):
    ins = r["data"].get("inClusters") or []
    has_onoff = "0006" in ins
    if has_onoff:
        ok(f"endpoint 01 in-clusters include 0006 ({' '.join(ins)})")
    else:
        warn(f"in-clusters {ins} lack 0006 On/Off: its cases are skipped")

section("Read Attributes 0000/0004,0005")
r = run_case(f"zbw:read:{ieee}:01:0000:0004,0005", "read:01:0000")
if ok_result(r, "read:01:0000"):
    recs = {rec.get("attr"): rec for rec in r["data"].get("records") or []}
    data = before.get("data") or {}
    for attr, key in ((4, "manufacturer"), (5, "model")):
        got = (recs.get(attr) or {}).get("value")
        check(got == data.get(key), f"attribute 0x000{attr} = {got!r} matches the device's {key}",
              f"attribute 0x000{attr} = {got!r}, device data {key} = {data.get(key)!r}")

if has_onoff:
    section("Discover Attributes 0006")
    r = run_case(f"zbw:discover:{ieee}:01:0006", "discover:01:0006")
    if ok_result(r, "discover:01:0006"):
        attrs = r["data"].get("attrs") or []
        check({"id": 0, "type": 0x10} in attrs, f"attributes include OnOff 0x0000 boolean ({len(attrs)} found)",
              f"attributes {attrs} lack id 0 type 0x10")
else:
    section("Discover Attributes 0000")
    r = run_case(f"zbw:discover:{ieee}:01:0000", "discover:01:0000")
    if ok_result(r, "discover:01:0000"):
        attrs = r["data"].get("attrs") or []
        check({"id": 0, "type": 0x20} in attrs, f"attributes include ZCLVersion 0x0000 uint8 ({len(attrs)} found)",
              f"attributes {attrs} lack id 0 type 0x20")

section("StartUpOnOff 0006/4003: read, write the value read, read again")
def read_4003():
    r = run_case(f"zbw:read:{ieee}:01:0006:4003", "read:01:0006")
    if not ok_result(r, "read:01:0006"): return None, None
    return r, next((x for x in r["data"].get("records") or [] if x.get("attr") == 0x4003), None)
r, rec = read_4003() if has_onoff else (None, None)
if not has_onoff:
    info("no On/Off cluster: skipped")
elif r is not None:
    if rec is None:
        fail(f"read 0006/4003: no record for 0x4003 in {r['data'].get('records')}")
    elif rec.get("status") != 0:
        warn(f"StartUpOnOff: the device answered {rec.get('statusName')}; write skipped")
    elif not isinstance(rec.get("value"), int) or isinstance(rec.get("value"), bool) or rec.get("type") is None:
        warn(f"StartUpOnOff value {rec.get('value')!r} type {rec.get('type')} is not an integer; write skipped")
    else:
        value, typ = rec["value"], rec["type"]
        width = {0x20: 1, 0x21: 2, 0x30: 1, 0x18: 1}.get(typ, 1)
        hexv = format(value, f"0{2 * width}X")
        info(f"StartUpOnOff = 0x{hexv} (type 0x{typ:02X}); writing the same value")
        token, frame = confirm_view(f"zbw:write:{ieee}:01:0006:4003:{typ:02X}:{hexv}", "write")
        w = None
        if token:
            queued = [v for v in (app_state(iid).get("pending") or {}).values() if v.get("kind") == "write:01:0006"]
            check(not queued, f"the write waits for Confirm; the confirmation page shows {frame}", f"queued before Confirm: {queued}")
            w = run_case(f"zbw:confirm:{token}", "write:01:0006")
        if token and ok_result(w, "write:01:0006"):
            sts = [x.get("statusName") for x in w["data"].get("records") or []]
            check(sts == ["SUCCESS"], f"write status {sts}", f"write status {sts}, expected ['SUCCESS']")
            r2, rec2 = read_4003()
            if r2 is not None:
                got = (rec2 or {}).get("value")
                check(got == value, f"read back 0x{hexv}: unchanged", f"read back {got!r}, wrote {value!r}")

section("Read Reporting Configuration 0006/0000")
r = run_case(f"zbw:readReport:{ieee}:01:0006:0000", "report:01:0006")
if r is None:
    fail("reporting configuration: no result (timeout)")
elif r.get("status") == "ok":
    recs = r["data"].get("records") or []
    rec = recs[0] if recs else {}
    if rec.get("status") == 0:
        ok(f"reporting configuration of 0x0000: type 0x{(rec.get('type') or 0):02X}, min {rec.get('min')} s, max {rec.get('max')} s")
    elif recs:
        ok(f"reporting configuration of 0x0000: status {rec.get('statusName')}")
    else:
        fail(f"reporting configuration: no records in {r['data']}")
elif r.get("status") == "refused":
    ok(f"reporting configuration: the device answered {r.get('statusName')}")
else:
    fail(f"reporting configuration: {r.get('status')} {r.get('error')} frame {r.get('frame')}")

section("Read on endpoint 00 is refused")
# Endpoint 00 is the ZDO; a ZCL frame there could line up as a ZDO request. Nothing may be queued.
press(iid, f"zbw:read:{ieee}:00:0038:0000")
st = app_state(iid)
check(st.get("inputError") == "ZCL endpoints are 01-F0, not '00'" and not (st.get("pending") or {})
      and not ((st.get("results") or {}).get(ieee) or {}).get("read:00:0038"),
      "refused with the endpoint message, nothing queued", f"inputError {st.get('inputError')!r}, pending {st.get('pending')}")

section("Leave without confirming")
# The Leave button only prepares a confirmation. Nothing may go out unless the confirm page's
# Confirm button posts the token, which this test never does.
prev_leave = result("leave")
mark = len(capture.messages)
earlier_sends = [m for m in capture.messages[:mark] if m.get("name") == DRIVER_TYPE and "sending" in (m.get("msg") or "")]
if earlier_sends:
    info(f"the radio logged {len(earlier_sends)} earlier sends, so a send would show")
else:
    warn("the radio logged none of the earlier sends (info logging off?): the check below sees only 0x0034 text")
press(iid, f"zbw:leave:{ieee}")
t_press = time.time()
st = app_state(iid)
view = st.get("confirmView") or {}
tokens = st.get("confirmTokens") or {}
token = view.get("token")
check(view.get("action") == "leave" and view.get("ieee") == ieee and token in tokens,
      "the Leave button issued a confirmation token and sent nothing yet", f"confirm view {view}, tokens {list(tokens)}")
page = request("GET", f"/installedapp/configure/json/{iid}/confirmPage")
page_text = json.dumps(page)
names = [i.get("name") for sec in (page.get("configPage") or {}).get("sections", []) for i in sec.get("input", [])]
leave_frame = (view.get("args") or {}).get("frame") or ""
check(f"zbw:confirm:{token}" in names and f"zbw:cancel:{token}" in names and ieee in page_text
      and " 0x0034 " in leave_frame and leave_frame in page_text,
      f"the confirm page shows the device, the frame {leave_frame} and Confirm and Cancel", f"confirm page inputs {names}, frame {leave_frame!r}")
press(iid, "zbw:confirm:00000000-0000-0000-0000-000000000000")   # an unknown token: refused
time.sleep(max(0.0, 5 - (time.time() - t_press)))
check(result("leave") == prev_leave, "no leave result stored within 5 s", f"leave result {result('leave')}")
sent = [m for m in capture.messages[mark:] if "0x0034" in (m.get("msg") or "")
        or (m.get("name") == DRIVER_TYPE and "sending" in (m.get("msg") or ""))]
check(not sent, "no 0x0034 frame and no send by the radio within 5 s", f"logged: {[m.get('msg') for m in sent][:3]}")
press(iid, f"zbw:cancel:{token}")
check(token not in (app_state(iid).get("confirmTokens") or {}), "Cancel drops the token", "the token survived Cancel")

section("Monitor device")
# Monitoring on: the app creates the monitor child and polls; a poll press then reads the tables
# again. The monitor's values are compared with the app's stored neighbour table. Monitoring off
# goes through the confirmation page, and the device must be gone afterwards, whatever failed.
MON_DNI = f"zbworkbench-mon-{ieee}"
def monitor_child():
    # statusJson lists children by id and label only; the DNI comes from each child's record
    for c in status_json(iid).get("childDevices") or []:
        d = (request("GET", f"/device/fullJson/{c.get('id')}") or {}).get("device") or {}
        if d.get("deviceNetworkId") == MON_DNI: return c
    return None
def monitor_states(mid):
    cs = ((request("GET", f"/device/fullJson/{mid}") or {}).get("device") or {}).get("currentStates") or {}
    return {k: v.get("value") for k, v in cs.items()}
def remove_monitor():
    press(iid, f"zbw:unmonitor:{ieee}")
    st = app_state(iid)
    view = st.get("confirmView") or {}
    token = view.get("token")
    if not (view.get("action") == "unmonitor" and view.get("ieee") == ieee and token in (st.get("confirmTokens") or {})):
        return False, f"no unmonitor confirmation: {view}"
    if monitor_child() is None:
        return False, "the monitor was removed before Confirm"
    page = request("GET", f"/installedapp/configure/json/{iid}/confirmPage")
    names = [i.get("name") for sec in (page.get("configPage") or {}).get("sections", []) for i in sec.get("input", [])]
    if f"zbw:confirm:{token}" not in names:
        return False, f"confirm page inputs {names}"
    press(iid, f"zbw:confirm:{token}")
    for _ in range(10):
        if monitor_child() is None: return True, "removed after Confirm"
        time.sleep(0.5)
    return False, "the monitor device is still there after Confirm"

if monitor_child() is not None:
    info("a monitor device is already there (left by an earlier run?)")
monitor_label = None
try:
    press(iid, f"zbw:monitor:{ieee}")
    mon = None
    for _ in range(10):
        mon = monitor_child()
        if mon: break
        time.sleep(0.5)
    check(mon is not None, "Monitor this device created the monitor device", f"no child with DNI {MON_DNI}: {app_state(iid).get('inputError')}")
    if mon is not None:
        mid = str(mon.get("id"))
        mdev = (request("GET", f"/device/fullJson/{mid}") or {}).get("device") or {}
        monitor_label = mdev.get("label") or mdev.get("name")
        check(mdev.get("isComponent") is False and mdev.get("deviceTypeName") == MONITOR_TYPE,
              f"monitor uses {MONITOR_TYPE} and is not a component (dashboards and rules can pick it)",
              f"monitor type {mdev.get('deviceTypeName')}, isComponent {mdev.get('isComponent')}")
        prev_n = (result("neighbors") or {}).get("time")
        t_poll = time.time()
        press(iid, f"zbw:poll:{ieee}")
        nb = None
        while time.time() - t_poll < TABLE_WAIT_S:
            time.sleep(1)
            nb = result("neighbors")
            if nb and nb.get("time") != prev_n and not (app_state(iid).get("pending") or {}):
                break
        if not ok_result(nb, "neighbors (poll)"):
            pass
        else:
            info(f"poll done after {time.time() - t_poll:.1f} s")
            ms = monitor_states(mid)
            total = nb["data"].get("total")
            entries = nb["data"].get("entries") or []
            check(str(ms.get("neighborCount")) == str(total), f"neighborCount {ms.get('neighborCount')} equals the stored neighbour total",
                  f"neighborCount {ms.get('neighborCount')}, stored total {total}")
            kids = sum(1 for e in entries if e.get("relationship") == "Child")
            check(str(ms.get("childCount")) == str(kids), f"childCount {ms.get('childCount')} matches the table",
                  f"childCount {ms.get('childCount')}, table has {kids}")
            low = min((e.get("lqi") for e in entries if e.get("lqi") is not None), default=None)
            check(low is None or str(ms.get("lowestLqi") or "").split(" ")[0] == str(low), f"lowestLqi '{ms.get('lowestLqi')}'",
                  f"lowestLqi {ms.get('lowestLqi')!r}, table minimum {low}")
            check(ms.get("healthStatus") == "online", "healthStatus online", f"healthStatus {ms.get('healthStatus')!r}")
            lp = ms.get("lastPoll")
            try:
                age = time.time() - datetime.datetime.fromisoformat(lp).timestamp()
            except (TypeError, ValueError):
                age = None
            check(age is not None and abs(age) <= 60, f"lastPoll {lp} ({age:.0f} s ago)" if age is not None else "lastPoll",
                  f"lastPoll {lp!r}")
            routes = result("routes") or {}
            info(f"routeCount {ms.get('routeCount')!r} (routes result: {routes.get('status')} {routes.get('statusName') or ''}), parent {ms.get('parent')!r}")
        # One UI: the device page shows what the monitor publishes, with Poll now and Stop monitoring
        page = request("GET", f"/installedapp/configure/json/{iid}/devicePage")
        names = [i.get("name") for sec in (page.get("configPage") or {}).get("sections", []) for i in sec.get("input", [])]
        if f"zbw:neighbors:{ieee}" not in names:
            info("the device page shows another picked device; page check skipped")
        else:
            text = json.dumps(page)
            check(f"zbw:poll:{ieee}" in names and f"zbw:unmonitor:{ieee}" in names and "Health" in text,
                  "device page shows health with Poll now and Stop monitoring", f"device page inputs {names}")
        # Dropped from the picker: the monitor stays, listed as orphaned with a Remove button
        others = [x for x in picked if x != dev_id]
        save_main(iid, others)
        try:
            page = request("GET", f"/installedapp/configure/json/{iid}/mainPage")
            names = [i.get("name") for sec in (page.get("configPage") or {}).get("sections", []) for i in sec.get("input", [])]
            check(monitor_child() is not None and f"zbw:unmonitor:{ieee}" in names,
                  "a monitor whose device is no longer picked stays, listed as orphaned with a Remove button",
                  f"orphan: monitor present {monitor_child() is not None}, main page inputs {names}")
        finally:
            save_main(iid, picked)
finally:
    if monitor_child() is not None:
        removed, why = remove_monitor()
        check(removed, f"Stop monitoring: confirmation page, then the monitor device is gone ({why})", f"Stop monitoring: {why}")
    else:
        fail("no monitor device to remove")
check(monitor_child() is None and not any((d.get("deviceNetworkId") or d.get("dni")) == MON_DNI
                                          for d in walk((request("GET", "/hub2/devicesList") or {}).get("devices") or [], [])),
      "no monitor device left on the hub", f"a device with DNI {MON_DNI} is still on the hub")
jobs = [j.get("handler") for j in (status_json(iid).get("scheduledJobs") or [])]
check("pollMonitors" not in jobs, "no monitor poll left scheduled", f"scheduled jobs after cleanup: {jobs}")
ws = radio_ws()
check(ws == "connected", "the radio's log socket stays open between requests", f"radio wsStatus {ws!r}")

if SLOW:
    section("Neighbours (Mgmt_Lqi)")
    r = run_case(f"zbw:neighbors:{ieee}", "neighbors", TABLE_WAIT_S)
    if ok_result(r, "neighbors"):
        d = r["data"]
        entries = d.get("entries") or []
        check(len(entries) == d.get("total"), f"{len(entries)} entries, total {d.get('total')}",
              f"entries {len(entries)} != total {d.get('total')}")
        lqis = [e.get("lqi") for e in entries]
        check(all(isinstance(q, int) and 0 <= q <= 255 for q in lqis), f"every LQI in 0–255 ({lqis})", f"bad LQI values {lqis}")

    section("Routes (Mgmt_Rtg)")
    r = run_case(f"zbw:routes:{ieee}", "routes", TABLE_WAIT_S)
    if r and r.get("status") == "refused" and r.get("statusName") == "NOT_SUPPORTED":
        warn("routes: the device answered NOT_SUPPORTED")
    elif ok_result(r, "routes"):
        d = r["data"]
        n = len(d.get("entries") or [])
        check(n == d.get("total"), f"routes: {n} entries, total {d.get('total')}", f"routes entries {n} != total {d.get('total')}")

    section("Node and power descriptors, bindings")
    r = run_case(f"zbw:node:{ieee}", "node")
    if ok_result(r, "node"):
        lt = r["data"].get("logicalType")
        check(lt == "Router", f"node descriptor: {lt}, manufacturer code {r['data'].get('mfgCode')}", f"node descriptor logical type {lt}, expected Router")
    r = run_case(f"zbw:power:{ieee}", "power")
    if ok_result(r, "power"):
        ok(f"power descriptor: current source {r['data'].get('currentSource')}, level {r['data'].get('level')}")
    r = run_case(f"zbw:bindings:{ieee}", "bindings", TABLE_WAIT_S)
    if r and r.get("status") == "refused" and r.get("statusName") == "NOT_SUPPORTED":
        warn("bindings: the device answered NOT_SUPPORTED")
    elif ok_result(r, "bindings"):
        d = r["data"]
        check(len(d.get("entries") or []) == d.get("total"), f"bindings: {len(d.get('entries') or [])} entries, total {d.get('total')}",
              f"bindings entries {len(d.get('entries') or [])} != total {d.get('total')}")

    section("Concurrent requests")
    # Four presses at once: replies arrive while the app is calling into the radio device. With both
    # single-threaded this used to hang the app until the platform's 120 s gate timeout.
    import threading
    kinds = ["node", "power", "endpoints", "read:01:0000"]
    buttons = [f"zbw:node:{ieee}", f"zbw:power:{ieee}", f"zbw:endpoints:{ieee}", f"zbw:read:{ieee}:01:0000:0004,0005"]
    prev = {k: (result(k) or {}).get("time") for k in kinds}
    errors = []
    def press_bg(b):
        try: press(iid, b)
        except Exception as e: errors.append(f"{b}: {e}")
    t0 = time.time()
    threads = [threading.Thread(target=press_bg, args=(b,)) for b in buttons]
    for th in threads: th.start()
    for th in threads: th.join()
    check(not errors, f"four presses accepted in {time.time() - t0:.1f} s", f"presses failed: {errors}")
    done = {}
    while time.time() - t0 < WAIT_S and len(done) < len(kinds):
        time.sleep(1)
        res = ((app_state(iid).get("results") or {}).get(ieee) or {})
        done = {k: res[k] for k in kinds if res.get(k) and res[k].get("time") != prev[k]}
    statuses = {k: v.get("status") for k, v in done.items()}
    check(len(done) == len(kinds) and all(s == "ok" for s in statuses.values()),
          f"all four answered in {time.time() - t0:.1f} s", f"after {time.time() - t0:.1f} s: {statuses}")
    # One request in flight per device: each of the four went out only after the one before it was answered.
    spans = sorted((v.get("sentAt"), v.get("time"), k) for k, v in done.items() if v.get("sentAt"))
    overlaps = [(a[2], b[2]) for a, b in zip(spans, spans[1:]) if b[0] < a[1]]
    check(len(spans) == len(kinds) and not overlaps,
          "the four went out one at a time, each after the previous reply: " + ", ".join(f"{k} {e - s} ms" for s, e, k in spans),
          f"requests overlapped: {overlaps} (spans {spans})")

    section("Interview")
    prev_t = (result("interview") or {}).get("time")
    t0 = time.time()
    press(iid, f"zbw:interview:{ieee}")
    iv = None
    while time.time() - t0 < 180:
        time.sleep(2)
        r = result("interview")
        if r and r.get("time") != prev_t and r.get("status") in ("ok", "error"):
            iv = r; break
    if iv is None:
        fail(f"interview: not finished after 180 s (last: {(result('interview') or {}).get('data')})")
    else:
        report = (iv.get("data") or {}).get("report") or ""
        check(iv.get("status") == "ok", f"interview finished: {iv['data'].get('done')} steps in {time.time() - t0:.0f} s",
              f"interview stopped: {iv.get('error')}")
        check(("0x0006 On/Off" in report or not has_onoff) and "0x0000 Basic" in report, "the report names the On/Off and Basic clusters",
              f"report lacks On/Off or Basic:\n{report}")
        check(re.search(r"^Manufacturer: (?!not read)", report, re.M) is not None and re.search(r"^Model: (?!not read)", report, re.M) is not None,
              "the report gives the manufacturer and model", f"report lacks manufacturer or model:\n{report}")
        unread = [l.strip() for l in report.splitlines() if l.endswith(": not read") or " failed (" in l or "not discovered" in l]
        if unread: warn(f"interview: {len(unread)} item(s) not read: {unread[:5]}")
        info("report:\n" + "\n".join("      " + l for l in report.splitlines()))

    section("Shareable report")
    def run_report():
        prev_t = (result("report") or {}).get("time")
        t0 = time.time()
        press(iid, f"zbw:report:{ieee}")
        while time.time() - t0 < 180:
            time.sleep(2)
            r = result("report")
            if r and r.get("time") != prev_t and r.get("status") in ("ok", "error"):
                info(f"report: {r.get('status')} after {time.time() - t0:.0f} s")
                return r
        return None
    def hub_files():
        return [f.get("name") for f in (request("GET", "/hub/fileManager/json") or {}).get("files", []) if f.get("type") == "file"]
    def upload(name, text):
        b = "----zbwtest" + str(int(time.time() * 1000))
        body = (f"--{b}\r\nContent-Disposition: form-data; name=\"uploadFile\"; filename=\"{name}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n{text}\r\n--{b}--\r\n").encode()
        req = urllib.request.Request(hub + "/hub/fileManager/upload", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={b}"})
        with opener.open(req, timeout=30) as r: r.read()
    def delete(name):
        request("POST", "/hub/fileManager/delete", json_body={"name": name, "type": "file"}, expect="text")
    rp = run_report()
    if rp is None or rp.get("status") != "ok" or not (rp.get("data") or {}).get("file"):
        fail(f"report: no saved report within 180 s ({rp and rp.get('status')}, {rp and (rp.get('data') or {}).get('fileError')})")
    else:
        fname = rp["data"]["file"]
        prefix = fname[:-len("yyyyMMdd-HHmm.md")]
        check(re.fullmatch(r"zigbee-workbench-[A-Za-z0-9_-]+-\d{8}-\d{4}\.md", fname) is not None and DEVICE_LABEL not in fname,
              f"saved as {fname}", f"file name {fname!r}")
        text = request("GET", f"/local/{fname}", expect="text") or ""
        masked = ieee[:6] + "X" * 10
        check(masked in text and ieee not in text, f"the saved report shows the IEEE as {masked}", "the saved report lacks the masked IEEE or shows the full one")
        check(DEVICE_LABEL not in text, "the saved report does not name the device (aliases on)", "the device label is in the saved report")
        check("[details=\"Attributes" in text and ("0x0006 On/Off" in text or "0x0000 Basic" in text), "the saved report lists the attributes",
              "the saved report lacks the attribute list")
        page = json.dumps(request("GET", f"/installedapp/configure/json/{iid}/devicePage"))
        m = re.search(r"<textarea id='zbwReport'[^>]*>(.*?)</textarea>", page, re.S)
        forum = m.group(1) if m else ""
        check(m is not None and len(forum) <= 30000 and DEVICE_LABEL not in forum and ieee not in forum,
              f"the device page shows the forum version ({len(forum)} characters), masked", "the device page lacks the forum version or it is not masked")
        # Retention: three older reports for the model, then one more run: the newest 3 stay.
        dummies = [f"{prefix}20000101-000{i}.md" for i in range(3)]
        for d in dummies: upload(d, "test file\n")
        rp2 = run_report()
        mine = sorted(n for n in hub_files() if n.startswith(prefix) and n.endswith(".md"))
        newest = (rp2 or {}).get("data", {}).get("file")
        check(rp2 is not None and len(mine) == 3 and newest in mine and dummies[0] not in mine,
              f"after another run {len(mine)} reports remain for the model, the newest kept", f"reports for the model: {mine}, newest {newest}")
        for d in dummies:
            if d in hub_files(): delete(d)
        check(not any(d in hub_files() for d in dummies), "the test's placeholder files are gone", "placeholder files left in File Manager")

    section("Hub tables")
    st0 = (app_state(iid).get("hubTables") or {}).get("time")
    press(iid, "zbw:hubTables")
    ht = None
    t0 = time.time()
    while time.time() - t0 < 60:
        time.sleep(1)
        ht = app_state(iid).get("hubTables") or {}
        if ht.get("time") != st0 and ht.get("status") in ("ok", "error"): break
    if ht and ht.get("status") == "ok":
        n = len((ht.get("data") or {}).get("neighbors") or [])
        check(n > 0, f"coordinator has {n} neighbours (lowest LQI {ht['data'].get('lowestLqi')})", "coordinator neighbour table is empty")
    else:
        fail(f"hub tables: {ht}")

else:
    info("neighbours, routes, node, power, bindings, concurrent requests, interview and hub tables skipped; set RUN_SLOW_TESTS=1")

capture.__exit__(None, None, None)
section("Logs")
errs = [m for m in capture.find_all(None, level="error") if re.search(r"Zigbee Workbench", m.get("name") or "")
        or (monitor_label and (m.get("name") or "") == monitor_label)]
check(not errs, "no errors logged by the app, its radio device or the monitor device", f"errors logged: {[m.get('msg') for m in errs][:3]}")

section("Target device unchanged")
after = snapshot(full())
def is_epoch_ms(v):
    return isinstance(v, int) and not isinstance(v, bool) and v > 10**12

# The replies reach the device's own driver too, as every frame from the device does. A driver that
# stamps each received frame (a lastRx-style key) moves that timestamp forward; only such a forward
# move is tolerated, and reported as a warning.
for k in ("driver", "dni", "settings", "data"):
    check(after[k] == before[k], f"{k} unchanged", f"{k} changed: {before[k]} -> {after[k]}")
b_st, a_st = before["deviceState"] or {}, after["deviceState"] or {}
if a_st == b_st:
    ok("deviceState unchanged")
else:
    moved = sorted(k for k in set(a_st) | set(b_st) if a_st.get(k) != b_st.get(k))
    stamps = [k for k in moved if is_epoch_ms(b_st.get(k)) and is_epoch_ms(a_st.get(k)) and a_st[k] > b_st[k]]
    if stamps == moved:
        warn(f"deviceState: the driver's receive timestamp(s) {stamps} moved forward (it saw the replies); nothing else changed")
    else:
        fail(f"deviceState changed: {[(k, b_st.get(k), a_st.get(k)) for k in moved]}")

pend = app_state(iid).get("pending") or {}
check(not pend, "no requests left pending", f"{len(pend)} requests still pending")

print(f"\n{passed} passed, {failed} failed, {warnings} warnings")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
