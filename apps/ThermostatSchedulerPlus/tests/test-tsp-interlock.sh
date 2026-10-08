#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Thermostat Scheduler+ with HVAC Interlock and hub variables (Mode 1).
# Cases: a profile setpoint from a hub variable follows the variable; eco on
# and off through program device commands; an HVAC Interlock permit group
# pausing the program through its status switch (thermostat off, then mode
# restored and current setpoints applied); the program paused by the group
# and by its own switch at once, lifted in both orders; the program paused by
# the group (pause while off) and by a second switch (pause while on).
#
# Rig (set up once): virtual thermostat "test-tsp-ilk-th", virtual switch
# "test-tsp-ilk-sw", virtual contact "test-tsp-ilk-window", HVAC Interlock group "test-ilk-tsp" (permit, that
# contact as its only opening, response block, open delay 0) under a parent
# whose Season device allows the group in the current season; the
# thermostat, the switch, the contact and the group's status device in the Maker API
# "test-tsp-maker". Needs hub firmware 2.5.2.133 or later (hub variables
# admin API).
# Each run creates the program "test-tsp-ilk" and the hub variable
# "zz_tsp_ilk_heat", and deletes both before exiting.
#
# Usage:
#   bash tests/test-tsp-interlock.sh            # default hub
#   bash tests/test-tsp-interlock.sh @hubname   # specific hub
#
# Exit: 0 all pass, 1 failures, 2 setup error.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; fi
done

python3 - "$HUB_NAME" "$CONFIG_FILE" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.request, urllib.error, urllib.parse, http.cookiejar

hub_name_arg, config_file = sys.argv[1], sys.argv[2]
PARENT_TYPE = "Thermostat Scheduler+"
GROUP_TYPE = "HVAC Interlock Group"
GROUP_LABEL = "test-ilk-tsp"
MAKER_LABEL = "test-tsp-maker"
PROG_LABEL = "test-tsp-ilk"
THERM = "test-tsp-ilk-th"
PEAK = "test-tsp-ilk-sw"
WINDOW = "test-tsp-ilk-window"
STATUS = f"{GROUP_LABEL} status"
VAR = "zz_tsp_ilk_heat"
CLOSE_DELAY_S = 3

GREEN, RED, DIM, CYAN, BOLD, RESET = "\033[32m", "\033[31m", "\033[2m", "\033[36m", "\033[1m", "\033[0m"
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

def check(name, got, want):
    if got == want: ok(name)
    else: fail(f"{name} (got {got!r}, want {want!r})")

def die(msg):
    print(f"{RED}{msg}{RESET}")
    sys.exit(2)

with open(config_file) as f:
    config = json.load(f)
hub_name = hub_name_arg or config.get("default_hub", "")
hub = config["hubs"].get(hub_name)
if not hub:
    die(f"Hub '{hub_name}' not found in .hubitat.json")
hub_ip = hub["hub_ip"]

opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
if hub.get("username") and hub.get("password"):
    data = urllib.parse.urlencode({"username": hub["username"], "password": hub["password"]}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
    except Exception as e:
        die(f"Authentication failed: {e}")

def fetch_text(path, timeout=30):
    return opener.open(f"http://{hub_ip}{path}", timeout=timeout).read().decode()

def fetch(path, timeout=30):
    return json.loads(fetch_text(path, timeout))

def post_form(path, fields, timeout=30):
    req = urllib.request.Request(f"http://{hub_ip}{path}", data=urllib.parse.urlencode(fields).encode())
    req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    with opener.open(req, timeout=timeout) as r:
        return r.read().decode()

def post_json(path, body, timeout=30):
    """Admin JSON POST. Returns (status, text)."""
    req = urllib.request.Request(f"http://{hub_ip}{path}", data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")

def walk(entries):
    for e in entries:
        d = e.get("data")
        if isinstance(d, dict):
            yield d
        yield from walk(e.get("children", []))

def token_of(app_id, page=""):
    text = fetch_text(f"/installedapp/configure/json/{app_id}{page}")
    m = re.search(r"access_token=([a-f0-9-]+)", text)
    return m.group(1) if m else None

# ── Setup ────────────────────────────────────────────────────────────
section("Setup")
apps = list(walk(fetch("/hub2/appsList").get("apps", [])))
parents = [a for a in apps if a.get("type") == PARENT_TYPE]
if not parents:
    die(f"No '{PARENT_TYPE}' instance on {hub_name}")
parent_id = parents[0]["id"]
groups = [a for a in apps if a.get("type") == GROUP_TYPE and a.get("name") == GROUP_LABEL]
if len(groups) != 1:
    die(f"Need exactly one '{GROUP_TYPE}' labelled '{GROUP_LABEL}' (see the header)")
group_id = groups[0]["id"]
makers = [a for a in apps if a.get("name") == MAKER_LABEL]
if not makers:
    die(f"Maker API '{MAKER_LABEL}' not found")
maker_id = makers[0]["id"]
maker_token = token_of(maker_id)
api_token = token_of(parent_id, "/apiPage")
if not maker_token or not api_token:
    die("Could not find the Maker API or Thermostat Scheduler+ access token (OAuth enabled?)")

def http_json(method, url, token, body=None):
    h = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    req = urllib.request.Request(url, method=method, headers=h,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw, code = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read(), e.code
    try:
        return code, json.loads(raw or b"{}")
    except Exception:
        return code, {"_raw": raw.decode(errors="replace")}

def call(method, path, body=None):
    return http_json(method, f"http://{hub_ip}/apps/api/{parent_id}{path}", api_token, body)

def maker(device_id, command, *args):
    path = f"/devices/{device_id}/{command}"
    if args:
        path += "/" + ",".join(urllib.parse.quote(str(a), safe="") for a in args)
    code, _ = http_json("GET", f"http://{hub_ip}/apps/api/{maker_id}{path}", maker_token)
    if code != 200:
        die(f"Maker API {command} on {device_id} answered {code}")

code, devs = http_json("GET", f"http://{hub_ip}/apps/api/{maker_id}/devices", maker_token)
by_label = {d.get("label") or d.get("name"): d["id"] for d in (devs if isinstance(devs, list) else [])}
missing = [n for n in (THERM, PEAK, WINDOW, STATUS) if n not in by_label]
if missing:
    die(f"Maker API '{MAKER_LABEL}' is missing {missing}")
therm_id, peak_id, window_id, status_id = (str(by_label[n]) for n in (THERM, PEAK, WINDOW, STATUS))

def attr(dev_id, name):
    j = fetch(f"/device/fullJson/{dev_id}")
    cs = j.get("currentStates") or (j.get("device") or {}).get("currentStates") or {}
    if isinstance(cs, dict):
        return (cs.get(name) or {}).get("value")
    for s in cs:
        if s.get("name") == name: return s.get("value")
    return None

def num(v):
    try: return float(v)
    except (TypeError, ValueError): return v

def wait_for(dev_id, name, want, timeout=20):
    """Poll until the attribute equals `want` (numbers compared as floats); returns the last value."""
    end = time.time() + timeout
    while True:
        got = attr(dev_id, name)
        same = num(got) == num(want) if isinstance(want, (int, float)) else got == want
        if same or time.time() > end:
            return num(got) if isinstance(want, (int, float)) else got
        time.sleep(1)

def run_method(dev_id, method, *args):
    """Device command over the admin channel, the same device command an RM custom action sends."""
    body = {"id": int(dev_id), "method": method, "args": [{"type": "STRING", "value": a} for a in args]}
    code, text = post_json("/device/runmethod", body)
    if code != 200:
        die(f"runmethod {method} on {dev_id} answered {code}: {text}")

def var_value():
    for v in fetch("/hub2/variables")["variables"]:
        if v["name"] == VAR: return v["value"]
    return None

def set_var(value):
    code, text = post_json("/hub2/variables/update", {"name": VAR, "type": "bigdecimal", "value": str(value),
                                                      "expectedValue": var_value()})
    if code not in (200, 204):
        die(f"hub variable update answered {code}: {text}")

def delete_program(pid):
    try:
        fetch(f"/installedapp/delete/{pid}")
    except Exception as e:
        info(f"could not delete program {pid}: {e}")

def remove_var():
    if var_value() is None: return
    for _ in range(10):
        code, text = post_json("/hub2/variables/remove", {"name": VAR})
        if code in (200, 204): return
        time.sleep(1)
    info(f"could not remove hub variable {VAR}: {text}")

def save_settings(app_id, page, fields_in):
    """Save some inputs of one app page, echoing the page's other inputs."""
    cfg = fetch(f"/installedapp/configure/json/{app_id}{'/' + page if page else ''}")
    pg, current = cfg["configPage"], cfg.get("settings") or {}
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(app_id)),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", pg.get("name", "mainPage")),
              ("pageBreadcrumbs", '["mainPage"]' if page else "[]")]
    for sec in pg.get("sections", []):
        for i in sec.get("input", []):
            n, typ = i["name"], i.get("type", "")
            if typ in ("button", "paragraph", "href"): continue
            v = fields_in.get(n, current.get(n))
            if isinstance(v, dict): v = list(v.keys())
            fields += [(f"{n}.type", typ), (f"{n}.multiple", "true" if i.get("multiple") else "false")]
            if typ.startswith(("capability.", "device.")):
                if v: fields.append((f"settings[{n}]", ",".join(str(x) for x in v)))
                fields += [("deviceList", n), ("", "")]
                continue
            if v is None or v == "" or v == "[]": continue
            if typ == "bool":
                fields.append((f"checkbox[{n}]", "on"))
                v = "true" if str(v).lower() == "true" else "false"
            fields.append((f"settings[{n}]", str(v)))
    fields += [("referrer", f"http://{hub_ip}/installedapp/list"),
               ("url", f"http://{hub_ip}/installedapp/configure/{app_id}/{pg.get('name')}"), ("_cancellable", "false")]
    resp = json.loads(post_form("/installedapp/update/json", fields))
    if resp.get("status") != "success":
        die(f"app {app_id} page {page or 'main'}: save rejected: {resp}")

def page_of(app_id, name):
    """Name of the page (main or one linked from it) that declares input `name`."""
    main = fetch(f"/installedapp/configure/json/{app_id}")
    def declares(c): return name in {i["name"] for s in c["configPage"].get("sections", []) for i in s.get("input", [])}
    if declares(main): return ""
    for s in main["configPage"].get("sections", []):
        for b in s.get("body", []):
            if b.get("element") == "href" and b.get("page") and not b.get("url"):
                if declares(fetch(f"/installedapp/configure/json/{app_id}/{b['page']}")): return b["page"]
    die(f"app {app_id} has no input {name}")

# Leftovers of an earlier run.
s, b = call("GET", "/programs")
if s != 200:
    die(f"GET /programs answered {s}")
for p in b.get("programs", []):
    if p.get("name") == PROG_LABEL:
        delete_program(p["id"]); info(f"deleted leftover program {p['id']}")
remove_var()

# Known starting state: window closed, thermostat in heat at 15.
maker(window_id, "close")
maker(peak_id, "off")
maker(therm_id, "setThermostatMode", "heat")
maker(therm_id, "setHeatingSetpoint", 15)
save_settings(group_id, "", {"debugEnable": True})
save_settings(group_id, "", {"testCloseDelay": CLOSE_DELAY_S})
if wait_for(status_id, "switch", "on", 15) != "on":
    die(f"'{STATUS}' is not on with the window closed: is the group allowed this season?")
ok(f"group {group_id} allows the equipment, window closed")

code, text = post_json("/hub2/variables/create", {"name": VAR, "type": "bigdecimal", "value": "20.0"})
if code not in (200, 204):
    die(f"creating hub variable {VAR} answered {code} (firmware before 2.5.2.133?): {text}")

prog_id = None
try:
    save_settings(parent_id, page_of(parent_id, "debugEnable"), {"debugEnable": True})
    s, b = call("POST", "/programs", {"label": PROG_LABEL})
    if s not in (200, 201):
        die(f"POST /programs answered {s} {b}")
    prog_id = b["id"]
    save_settings(prog_id, "", {"thermostats": [therm_id]})
    s, doc = call("GET", f"/programs/{prog_id}")
    cfg = doc["config"]
    cfg["profiles"] = [{"name": "Day", "heatVar": VAR, "cool": 26.0}]
    cfg["schedules"] = [{"name": "Normal", "type": "time", "groups": [
        {"name": "All", "days": [1, 2, 3, 4, 5, 6, 7],
         "periods": [{"name": "Day", "profile": "Day", "start": {"kind": "time", "at": "00:00"}}]}]}]
    cfg["active"] = "Normal"
    cfg["overrides"] = []
    cfg["eco"] = {"offset": 2.0, "onOverrides": True}
    cfg["options"].update({"whilePaused": "off", "onResume": "restore"})
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": doc["revision"], "config": cfg})
    if s != 200:
        die(f"PUT configuration answered {s} {b}")
    opt_page = page_of(prog_id, "pauseWhenOff")
    save_settings(prog_id, opt_page, {"pauseWhenOff": [status_id], "pauseWhenOn": [peak_id]})
    devs = [d["data"] for d in fetch("/hub2/devicesList")["devices"]]
    pdev = [str(d["id"]) for d in devs if str(d.get("name", "")).startswith(PROG_LABEL) and d.get("type") == "Thermostat Scheduler+ Program Device"]
    if len(pdev) != 1:
        die(f"Expected one program device for {PROG_LABEL}, found {pdev}")
    prog_dev = pdev[0]
    ok(f"program {prog_id} (device {prog_dev}) on {THERM}, pausing while '{STATUS}' is off or '{PEAK}' is on")

    def prog_status():
        return call("GET", f"/programs/{prog_id}")[1].get("status", {}).get("status")

    # ── Hub variable setpoint ────────────────────────────────────────
    section("Hub variable setpoint")
    run_method(prog_dev, "on")
    run_method(prog_dev, "applyNow")
    check("applied setpoint comes from the variable", wait_for(therm_id, "heatingSetpoint", 20.0), 20.0)
    check("program follows its schedule", prog_status(), "schedule")
    set_var("21.5")
    check("variable change is written to the thermostat", wait_for(therm_id, "heatingSetpoint", 21.5), 21.5)
    check("program device reports the new target", wait_for(prog_dev, "heatingTarget", 21.5), 21.5)

    # ── Eco through device commands ──────────────────────────────────
    section("Eco through device commands")
    run_method(prog_dev, "setEco", "on")
    check("eco on lowers the setpoint by the offset", wait_for(therm_id, "heatingSetpoint", 19.5), 19.5)
    check("program device eco on", wait_for(prog_dev, "eco", "on"), "on")
    run_method(prog_dev, "setEco", "off")
    check("eco off restores the schedule setpoint", wait_for(therm_id, "heatingSetpoint", 21.5), 21.5)

    # ── Interlock pause ──────────────────────────────────────────────
    section("Interlock pause")
    maker(window_id, "open")
    check("open window turns the status switch off", wait_for(status_id, "switch", "off"), "off")
    check("thermostat turned off", wait_for(therm_id, "thermostatMode", "off"), "off")
    check("program reports restricted", prog_status(), "restricted")
    set_var("22.0")
    time.sleep(5)
    check("variable change while restricted writes nothing", num(attr(therm_id, "heatingSetpoint")), 21.5)
    maker(therm_id, "setHeatingSetpoint", 15)
    wait_for(therm_id, "heatingSetpoint", 15)
    maker(window_id, "close")
    check("closed window turns the status switch on", wait_for(status_id, "switch", "on", CLOSE_DELAY_S + 20), "on")
    check("thermostat mode restored", wait_for(therm_id, "thermostatMode", "heat"), "heat")
    check("current setpoint applied on lift", wait_for(therm_id, "heatingSetpoint", 22.0), 22.0)
    # Read after the thermostat's events have settled: a late event the program did not send would
    # leave it in manual until its next write.
    time.sleep(10)
    st = prog_status()
    check("program back on its schedule", st, "schedule")
    if st != "schedule":
        evs = fetch(f"/device/events/{therm_id}/dataTablesJson?max=12")
        for e in (evs.get("data") or [])[:12]:
            info(f"thermostat event: {e}")

    # ── Two pause sources, group lifted first ─────────────────────────
    section("Two pause sources, group lifted first")
    maker(window_id, "open")
    wait_for(therm_id, "thermostatMode", "off")
    run_method(prog_dev, "off")
    time.sleep(3)
    info(f"status with both sources: {prog_status()}")
    maker(window_id, "close")
    check("status switch back on", wait_for(status_id, "switch", "on", CLOSE_DELAY_S + 20), "on")
    time.sleep(5)
    check("still off while the program switch holds", attr(therm_id, "thermostatMode"), "off")
    check("program reports paused", prog_status(), "paused")
    run_method(prog_dev, "on")
    check("program on restores the mode", wait_for(therm_id, "thermostatMode", "heat"), "heat")
    check("and applies the setpoint", wait_for(therm_id, "heatingSetpoint", 22.0), 22.0)

    # ── Two pause sources, program switch lifted first ────────────────
    section("Two pause sources, program switch lifted first")
    run_method(prog_dev, "off")
    wait_for(therm_id, "thermostatMode", "off")
    maker(window_id, "open")
    wait_for(status_id, "switch", "off")
    run_method(prog_dev, "on")
    time.sleep(5)
    check("still off while the group holds", attr(therm_id, "thermostatMode"), "off")
    check("program reports restricted", prog_status(), "restricted")
    maker(window_id, "close")
    check("status switch back on", wait_for(status_id, "switch", "on", CLOSE_DELAY_S + 20), "on")
    check("group lift restores the mode", wait_for(therm_id, "thermostatMode", "heat"), "heat")
    check("and applies the setpoint", wait_for(therm_id, "heatingSetpoint", 22.0), 22.0)
    # ── Pause lists: any on, any off ──────────────────────────────────
    section("Pause lists: any on, any off")
    maker(peak_id, "on")
    check("a switch in the on list pauses", wait_for(therm_id, "thermostatMode", "off"), "off")
    check("program reports restricted", prog_status(), "restricted")
    maker(window_id, "open")
    check("status switch off as well", wait_for(status_id, "switch", "off"), "off")
    maker(peak_id, "off")
    time.sleep(5)
    check("still off while the off list holds", attr(therm_id, "thermostatMode"), "off")
    maker(peak_id, "on")
    maker(window_id, "close")
    check("status switch back on", wait_for(status_id, "switch", "on", CLOSE_DELAY_S + 20), "on")
    time.sleep(5)
    check("still off while the on list holds", attr(therm_id, "thermostatMode"), "off")
    maker(peak_id, "off")
    check("both lists clear: mode restored", wait_for(therm_id, "thermostatMode", "heat"), "heat")
    check("and setpoint applied", wait_for(therm_id, "heatingSetpoint", 22.0), 22.0)
finally:
    maker(window_id, "close")
    maker(peak_id, "off")
    if prog_id: delete_program(prog_id)
    remove_var()

total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if failed: print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
