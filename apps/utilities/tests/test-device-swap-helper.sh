#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Device Swap Helper swap test (Mode 2): drives the app's pages over HTTP through a
# capability block, a per-input swap and its undo, and a Swap Apps Device swap and its undo.
#
# The app is single-instance and keeps an undo record, so the test installs the local source
# as its own type ("Device Swap Helper Test", writing its own audit file) and never touches
# the hub's instance. It creates three virtual devices and a probe app named test-dsh-*, and
# removes all of them, the test type and its audit file before exiting. Leftovers from an
# interrupted run are removed at the start.
#
# Usage:
#   bash apps/utilities/tests/test-device-swap-helper.sh            # default hub
#   bash apps/utilities/tests/test-device-swap-helper.sh @hubname   # specific hub
#
# Runtime budget: ~90s.
# Exit: 0 all pass, 1 failures, 2 setup error.

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
SOURCE="$SCRIPT_DIR/../DeviceSwapHelper.groovy"

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; fi
done

python3 - "$HUB_NAME" "$PROJECT_ROOT/.hubitat.json" "$SOURCE" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.request, urllib.error, urllib.parse, http.cookiejar

hub_name_arg, config_file, source_file = sys.argv[1:4]
SUT_TYPE = "Device Swap Helper Test"
AUDIT = "device_swap_audit_test.txt"
PROBE_TYPE = "test-dsh Probe"
SRC, SW, CONTACT = "test-dsh-src", "test-dsh-switch", "test-dsh-contact"
PROBE_SOURCE = '''
definition(name: "test-dsh Probe", namespace: "iamtrep", author: "PJ",
    description: "Device Swap Helper test fixture", category: "Utility", iconUrl: "", iconX2Url: "")
preferences {
    page(name: "mainPage", title: "", install: true, uninstall: true) {
        section { input "sw", "capability.switch", title: "Switches", multiple: true, required: false }
    }
}
void installed() { initialize() }
void updated() { unsubscribe(); initialize() }
void initialize() { if (sw) subscribe(sw, "switch", "handler") }
void handler(evt) { }
'''

GREEN, RED, DIM, CYAN, RESET = "\033[32m", "\033[31m", "\033[2m", "\033[36m", "\033[0m"
passed = failed = 0

def ok(msg):
    global passed; passed += 1
    print(f"  {GREEN}[PASS]{RESET} {msg}")

def fail(msg):
    global failed; failed += 1
    print(f"  {RED}[FAIL]{RESET} {msg}")

def info(msg):
    print(f"  {DIM}[INFO] {msg}{RESET}")

def section(msg):
    print(f"\n{CYAN}--- {msg} ---{RESET}")

def check(name, cond, detail=""):
    ok(name) if cond else fail(f"{name}{f' ({detail})' if detail else ''}")

class SetupError(Exception):
    pass

with open(config_file) as f:
    config = json.load(f)
hub_name = hub_name_arg or config.get("default_hub", "")
hub = config["hubs"].get(hub_name)
if not hub:
    print(f"{RED}Hub '{hub_name}' not found in .hubitat.json{RESET}"); sys.exit(2)
BASE = f"http://{hub['hub_ip']}"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
no_redirect = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)
try:
    if hub.get("username") and hub.get("password"):
        opener.open(f"{BASE}/login", urllib.parse.urlencode(
            {"username": hub["username"], "password": hub["password"]}).encode(), timeout=10)
    opener.open(f"{BASE}/hub2/hubData", timeout=10).read()
except Exception as e:
    print(f"{RED}Hub {hub_name} unreachable or login failed: {e}{RESET}"); sys.exit(2)

def http(method, path, data=None, ctype=None, timeout=60):
    req = urllib.request.Request(f"{BASE}{path}", data=data, method=method)
    if ctype: req.add_header("Content-Type", ctype)
    with opener.open(req, timeout=timeout) as r:
        return r.geturl(), r.read()

def get_json(path, timeout=60):
    return json.loads(http("GET", path, timeout=timeout)[1])

def post_form(path, fields, timeout=60):
    _, raw = http("POST", path, urllib.parse.urlencode(fields).encode(),
                  "application/x-www-form-urlencoded; charset=UTF-8", timeout)
    try: return json.loads(raw)
    except ValueError: return {}

def location_of(path):
    try:
        r = no_redirect.open(f"{BASE}{path}", timeout=30)
        return r.headers.get("Location") or r.geturl()
    except urllib.error.HTTPError as e:
        return e.headers.get("Location") or ""

def walk(entries):
    for e in entries:
        if isinstance(e.get("data"), dict): yield e["data"]
        yield from walk(e.get("children", []))

def instances(type_name):
    return [a for a in walk(get_json("/hub2/appsList").get("apps", [])) if a.get("type") == type_name]

def page(aid, name="mainPage"):
    cp = get_json(f"/installedapp/configure/json/{aid}/{name}").get("configPage") or {}
    return cp

def page_html(cp):
    return " ".join(f"{b.get('title') or ''} {b.get('description') or ''}"
                    for sec in cp.get("sections", []) for b in sec.get("body", []))

def page_text(cp):
    titles = " ".join(str(s.get("title") or "") for s in cp.get("sections", []))
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", titles + " " + page_html(cp)))

def app_state(aid):
    return {x["name"]: x.get("value") for x in get_json(f"/installedapp/statusJson/{aid}").get("appState") or []}

def device_ids(setting):
    if isinstance(setting, dict): return sorted(int(k) for k in setting)
    return sorted(int(v) for v in (setting or []))

def save_devices(aid, inputs, multiple):
    """Saves device inputs on an app's main page with Done; omitted settings are kept."""
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(aid)), ("version", "1"),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]")]
    for name, (typ, ids) in inputs.items():
        fields += [(f"{name}.type", typ), (f"{name}.multiple", "true" if multiple else "false"),
                   (f"settings[{name}]", ",".join(map(str, ids))), ("deviceList", name), ("", "")]
    fields += [("referrer", f"{BASE}/installedapp/list"),
               ("url", f"{BASE}/installedapp/configure/{aid}/mainPage"), ("_cancellable", "false")]
    return post_form("/installedapp/update/json", fields)

def press(aid, button, current="mainPage"):
    post_form("/installedapp/btn", {"id": str(aid), "name": button, f"settings[{button}]": "clicked",
                                    f"{button}.type": "button", "currentPage": current})

def click_href(aid, src, dst, token):
    """Follows an href the way the UI does, carrying the link's token param."""
    fields = [("id", str(aid)), ("version", "1"), ("formAction", "update"), ("currentPage", src),
              ("pageBreadcrumbs", '["mainPage"]'), (f"_action_href_name|{dst}|0", ""),
              (f"params_for_action_href_name|{dst}|0", json.dumps({"token": token}))]
    return post_form("/installedapp/update/json", fields).get("configPage") or {}

def href_token(cp, dst):
    for sec in cp.get("sections", []):
        for b in sec.get("body", []):
            if b.get("element") == "href" and b.get("page") == dst and b.get("params"):
                return b["params"].get("token")
    return None

def wait_for_scan(aid, budget=60):
    t0 = time.time()
    while time.time() - t0 < budget:
        cp = page(aid, "previewPage")
        if "Scan in progress" not in page_html(cp):
            return cp
        time.sleep(2)
    raise SetupError("the scan did not finish")

def device(did):
    return (get_json(f"/device/fullJson/{did}").get("device") or {})

def audit_lines():
    try:
        return http("GET", f"/local/{AUDIT}")[1].decode().splitlines()
    except urllib.error.HTTPError:
        return []

def cleanup():
    for a in instances(SUT_TYPE):
        native = (app_state(a["id"]).get("native") or {})
        if native.get("aid"):
            location_of(f"/installedapp/delete/{native['aid']}")
        location_of(f"/installedapp/delete/{a['id']}")
    for a in instances(PROBE_TYPE):
        location_of(f"/installedapp/delete/{a['id']}")
    for t in get_json("/hub2/userAppTypes"):
        if t.get("name") in (SUT_TYPE, PROBE_TYPE):
            get_json(f"/app/edit/deleteJson/{t['id']}")
    for d in walk(get_json("/hub2/devicesList").get("devices", [])):
        if str(d.get("label") or d.get("name") or "").startswith("test-dsh-"):
            get_json(f"/device/forceDelete/{d['id']}/json")
    if any(f.get("name") == AUDIT for f in get_json("/hub/fileManager/json").get("files", [])):
        http("POST", "/hub/fileManager/delete", json.dumps({"name": AUDIT, "type": "file"}).encode(),
             "application/json")

def install(source, type_name):
    resp = json.loads(http("POST", "/app/saveOrUpdateJson", json.dumps({"source": source, "version": 1}).encode(),
                           "application/json", timeout=120)[1])
    if not resp.get("success"):
        raise SetupError(f"{type_name} did not compile: {resp.get('message')}")
    m = re.search(r"/installedapp/configure/(\d+)", location_of(f"/installedapp/create/{resp['id']}"))
    if not m:
        raise SetupError(f"could not create a {type_name} instance")
    return int(m.group(1))

def make_device(label, driver_id):
    url, _ = http("POST", "/device/save", urllib.parse.urlencode(
        {"name": label, "label": label, "deviceNetworkId": label.upper(), "deviceTypeId": driver_id}).encode(),
        "application/x-www-form-urlencoded")
    m = re.search(r"/device/edit/(\d+)", url)
    if not m:
        raise SetupError(f"could not create {label}")
    return int(m.group(1))

def run_native(aid, page_name, label):
    """Does what the framed swap page and its script do: render, click, render, delete."""
    cp = page(aid, page_name)
    n = app_state(aid).get("native") or {}
    if not n.get("aid"):
        fail(f"{label}: the app opened no swap page ({n.get('reason') or page_text(cp)[:200]})")
        return None
    swap_id = n["aid"]
    page(swap_id)
    press(swap_id, "doSwap")
    page(swap_id)
    location_of(f"/installedapp/delete/{swap_id}")
    return page_text(page(aid, "nativeCheckPage"))

setup_error = False
try:
    section("Setup")
    cleanup()
    listed = get_json("/device/drivers")
    drivers = {d["name"]: d["id"] for d in (listed.get("drivers", []) if isinstance(listed, dict) else listed)
               if d.get("type") == "sys"}
    src = make_device(SRC, drivers["Virtual Switch"])
    sw = make_device(SW, drivers["Virtual Switch"])
    contact = make_device(CONTACT, drivers["Virtual Contact Sensor"])
    source = open(source_file).read()
    source = source.replace('name: "Device Swap Helper",', f'name: "{SUT_TYPE}",', 1)
    source = re.sub(r'AUDIT_FILE = "[^"]+"', f'AUDIT_FILE = "{AUDIT}"', source, count=1)
    if SUT_TYPE not in source or AUDIT not in source:
        raise SetupError("could not rename the app type or its audit file in the source")
    sut = install(source, SUT_TYPE)
    probe = install(PROBE_SOURCE, PROBE_TYPE)
    save_devices(probe, {"sw": ("capability.switch", [src])}, True)
    page(sut)
    info(f"devices {src}/{sw}/{contact}, app {sut}, probe {probe}")
    key = f"{probe}:sw"

    def probe_ids():
        return device_ids(get_json(f"/installedapp/configure/json/{probe}").get("settings", {}).get("sw"))

    check("the probe holds the source", probe_ids() == [src], probe_ids())

    def probe_subscribed_to(did):
        subs = get_json(f"/installedapp/statusJson/{probe}").get("eventSubscriptions") or []
        return any(int(s.get("typeId", -1)) == did and s.get("name") == "switch" for s in subs)

    # ── Capability block ─────────────────────────────────────────────
    section("Capability block: a contact sensor into a switch input")
    save_devices(sut, {"sourceDevice": ("capability.*", [src]), "targetDevice": ("capability.*", [contact])}, False)
    page(sut)
    cp = wait_for_scan(sut)
    st = app_state(sut)
    row = next((r for r in st.get("pendingScan") or [] if r.get("inputName") == "sw"), None)
    check("the probe's input is listed", row is not None, page_text(cp)[:300])
    check("the row is blocked", bool(row and "doesn't have capability.switch" in str(row.get("capBlock"))),
          row and row.get("capBlock"))
    check("the row starts unselected", (st.get("swapSelections") or {}).get(key) == "off")
    check("the row has no checkbox", f"btnSwapSel:{key}" not in page_html(cp))
    check("the preview says why", "Can't swap" in page_text(cp))
    check("Swap Apps Device is not offered", "can't do this swap" in page_text(cp), page_text(cp)[:300])
    if (app_state(sut).get("swapSelections") or {}).get(key) == "off":
        press(sut, f"btnSwapSel:{key}", "previewPage")
    check("a forced selection still swaps nothing", "Nothing to swap" in page_text(page(sut, "confirmSwapPage")))
    check("the probe still holds the source", probe_ids() == [src])

    # ── Per-input swap ───────────────────────────────────────────────
    section("Per-input swap and undo")
    save_devices(sut, {"targetDevice": ("capability.*", [sw])}, False)
    page(sut)
    press(sut, "showPerInput", "previewPage")
    cp = wait_for_scan(sut)
    check("the row has a checkbox", f"btnSwapSel:{key}" in page_html(cp), page_text(cp)[:300])
    check("the row starts selected", (app_state(sut).get("swapSelections") or {}).get(key) != "off")
    token = href_token(page(sut, "confirmSwapPage"), "resultsPage")
    check("the confirm page offers the swap", token is not None)
    result = page_text(click_href(sut, "confirmSwapPage", "resultsPage", token))
    check("the result reports the swap verified", "Verified: input now" in result, result[:300])
    check("the probe now holds the replacement", probe_ids() == [sw], probe_ids())
    check("the probe subscribes to the replacement", probe_subscribed_to(sw))
    swaps = lambda: [l for l in audit_lines() if f"{SRC} ({src}) -> {SW} ({sw})" in l and "| swap |" in l]
    check("the swap is in the audit log", len(swaps()) == 1 and "| ok:" in swaps()[0], audit_lines()[-1:])
    again = page_text(click_href(sut, "confirmSwapPage", "resultsPage", token))
    check("reusing the confirm link shows the results without swapping again",
          "Verified: input now" in again and probe_ids() == [sw] and len(swaps()) == 1, again[:200])
    press(sut, "undoLastSwap")
    check("undo puts the source back", probe_ids() == [src], probe_ids())
    check("the probe subscribes to the source again", probe_subscribed_to(src))
    check("the undo is in the audit log", any("| undo |" in l and "| ok:" in l for l in audit_lines()), audit_lines()[-1:])

    # ── Swap Apps Device ─────────────────────────────────────────────
    section("Swap Apps Device swap and undo")
    dni_src, dni_sw = device(src).get("deviceNetworkId"), device(sw).get("deviceNetworkId")
    save_devices(sut, {"sourceDevice": ("capability.*", [src]), "targetDevice": ("capability.*", [sw])}, False)
    page(sut)
    cp = wait_for_scan(sut)
    check("the preview offers Swap Apps Device", "Swap everywhere with Swap Apps Device" in page_text(cp), page_text(cp)[:300])
    text = run_native(sut, "nativeSwapPage", "swap")
    if text is not None:
        check("the check page reports the swap", "Swapped:" in text, text[:300])
        check("the network ids moved between the two ids",
              device(src).get("deviceNetworkId") == dni_sw and device(sw).get("deviceNetworkId") == dni_src)
        check("the probe keeps device id", probe_ids() == [src])
        check("the swap is in the audit log", any("Swap Apps Device swap" in l and "ok:" in l for l in audit_lines()))
        main = page_text(page(sut))
        check("the main page offers the undo", "Undo: swap them back" in main, main[:300])
        text = run_native(sut, "nativeUndoPage", "undo")
        if text is not None:
            check("the check page reports the undo", "Swapped back" in text, text[:300])
            check("the network ids are back",
                  device(src).get("deviceNetworkId") == dni_src and device(sw).get("deviceNetworkId") == dni_sw)
            check("the undo is in the audit log", any("Swap Apps Device undo" in l and "ok:" in l for l in audit_lines()))
except SetupError as e:
    print(f"{RED}Setup error: {e}{RESET}")
    setup_error = True
except Exception as e:
    fail(f"unexpected error: {e!r}")
finally:
    try:
        cleanup()
        info("removed the test type, instances, devices and audit file")
    except Exception as e:
        fail(f"cleanup failed: {e!r}")

if setup_error:
    sys.exit(2)
print(f"\n{passed} passed, {failed} failed, 0 warnings")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
