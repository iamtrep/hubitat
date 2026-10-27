#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# HVAC Scheduler occupancy (Mode 1). A program with profiles Base (17) and
# Comfort (18), one period on Base that uses the occupied profile, Comfort as
# the occupied profile, and a virtual motion sensor with both delays at 1 min.
# Cases: vacant at start; active shorter than the delay writes nothing; active
# past it raises the setpoint; a break shorter than the vacant delay keeps it;
# eco on top; a next hold kept when the zone goes vacant; a period not using
# the occupied profile; a pause while occupied and the resume; Done while
# occupied writes nothing; removing the sensor.
#
# Rig (set up once): virtual thermostat "test-tsp-occ-th" and virtual motion
# sensor "test-tsp-occ-motion" (Auto Inactive: Disabled) in the Maker API
# "test-tsp-maker". Each run
# creates the program "test-tsp-occ" and deletes it before exiting.
#
# Runtime ~7 min (real-time delays): gated behind RUN_SLOW_TESTS=1 (TESTING.md §1.1).
#
# Usage:
#   RUN_SLOW_TESTS=1 bash tests/test-tsp-occupancy.sh            # default hub
#   RUN_SLOW_TESTS=1 bash tests/test-tsp-occupancy.sh @hubname   # specific hub
#
# Exit: 0 all pass, 1 failures, 2 setup error.

set -euo pipefail

if [[ "${RUN_SLOW_TESTS:-}" != "1" ]]; then
    echo "  [INFO] test-tsp-occupancy skipped: runs ~7 min; set RUN_SLOW_TESTS=1"
    exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# HUBITAT_CONFIG (absolute path) points the test at a different hub config file.
CONFIG_FILE="${HUBITAT_CONFIG:-$PROJECT_ROOT/.hubitat.json}"

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; fi
done

python3 - "$HUB_NAME" "$CONFIG_FILE" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.request, urllib.error, urllib.parse, http.cookiejar

hub_name_arg, config_file = sys.argv[1], sys.argv[2]
PARENT_TYPE = "HVAC Scheduler"
MAKER_LABEL = "test-tsp-maker"
PROG_LABEL = "test-tsp-occ"
THERM = "test-tsp-occ-th"
MOTION = "test-tsp-occ-motion"
DELAY_S = 60          # both delays are 1 minute
SLACK_S = 30          # scheduling and write latency allowed on top of a delay

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
makers = [a for a in apps if a.get("name") == MAKER_LABEL]
if not makers:
    die(f"Maker API '{MAKER_LABEL}' not found")
maker_id = makers[0]["id"]
maker_token = token_of(maker_id)
api_token = token_of(parent_id, "/apiPage")
if not maker_token or not api_token:
    die("Could not find the Maker API or HVAC Scheduler access token (OAuth enabled?)")

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
missing = [n for n in (THERM, MOTION) if n not in by_label]
if missing:
    die(f"Maker API '{MAKER_LABEL}' is missing {missing}")
therm_id, motion_id = (str(by_label[n]) for n in (THERM, MOTION))

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

def delete_program(pid):
    try:
        fetch(f"/installedapp/delete/{pid}")
    except Exception as e:
        info(f"could not delete program {pid}: {e}")

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
                elif n in fields_in: fields.append((f"settings[{n}]", ""))   # an empty value clears the input
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

auto = [x.get("value") for x in fetch(f"/device/fullJson/{motion_id}").get("settings", []) if x.get("name") == "autoInactive"]
if auto and str(auto[0]) != "0":
    die(f"'{MOTION}' turns itself inactive after {auto[0]} s: set its Auto Inactive to Disabled")

# Leftovers of an earlier run.
s, b = call("GET", "/programs")
if s != 200:
    die(f"GET /programs answered {s}")
for p in b.get("programs", []):
    if p.get("name") == PROG_LABEL:
        delete_program(p["id"]); info(f"deleted leftover program {p['id']}")

# Known starting state: no motion, thermostat in heat at 15.
maker(motion_id, "inactive")
maker(therm_id, "setThermostatMode", "heat")
maker(therm_id, "setHeatingSetpoint", 15)

def base_config(doc, ticked=True):
    cfg = doc["config"]
    period = {"name": "Day", "profile": "Base", "start": {"kind": "time", "at": "00:00"}}
    if ticked: period["useOccupied"] = True
    cfg["profiles"] = [{"name": "Base", "heat": 17.0, "cool": 26.0}, {"name": "Comfort", "heat": 18.0, "cool": 25.0}]
    cfg["schedules"] = [{"name": "Normal", "type": "time", "groups": [
        {"name": "All", "days": [1, 2, 3, 4, 5, 6, 7], "periods": [period]}]}]
    cfg["active"] = "Normal"
    cfg["overrides"] = []
    cfg["occupancy"] = {"profile": "Comfort"}
    cfg["eco"] = {"offset": 2.0, "onOverrides": True}
    cfg["options"].update({"whilePaused": "off", "onResume": "restore"})
    return cfg

def setpoint_events():
    return len([e for e in fetch(f"/device/eventsJson/{therm_id}") if e.get("name") == "heatingSetpoint"])

prog_id = None
try:
    save_settings(parent_id, page_of(parent_id, "debugEnable"), {"debugEnable": True})
    s, b = call("POST", "/programs", {"label": PROG_LABEL})
    if s not in (200, 201):
        die(f"POST /programs answered {s} {b}")
    prog_id = b["id"]
    save_settings(prog_id, "", {"thermostats": [therm_id]})
    s, doc = call("GET", f"/programs/{prog_id}")
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": doc["revision"], "config": base_config(doc)})
    if s != 200:
        die(f"PUT configuration answered {s} {b}")
    opt_page = page_of(prog_id, "occupancySensor")
    save_settings(prog_id, opt_page, {"occupancySensor": [motion_id]})
    save_settings(prog_id, opt_page, {"occupiedAfter": 1, "vacantAfter": 1})
    devs = [d["data"] for d in fetch("/hub2/devicesList")["devices"]]
    pdev = [str(d["id"]) for d in devs if str(d.get("name", "")).startswith(PROG_LABEL) and d.get("type") == "HVAC Scheduler Program Device"]
    if len(pdev) != 1:
        die(f"Expected one program device for {PROG_LABEL}, found {pdev}")
    prog_dev = pdev[0]
    ok(f"program {prog_id} (device {prog_dev}) on {THERM}, occupancy from {MOTION}")

    def status():
        return call("GET", f"/programs/{prog_id}")[1].get("status", {})

    # ── Vacant at start ──────────────────────────────────────────────
    section("Vacant at start")
    run_method(prog_dev, "on")
    run_method(prog_dev, "applyNow")
    check("period setpoint applied", wait_for(therm_id, "heatingSetpoint", 17.0), 17.0)
    check("program device reports vacant", wait_for(prog_dev, "occupancy", "vacant"), "vacant")

    # ── Becoming occupied ────────────────────────────────────────────
    section("Becoming occupied")
    maker(motion_id, "active")
    time.sleep(DELAY_S - 20)
    check("active shorter than the delay writes nothing", num(attr(therm_id, "heatingSetpoint")), 17.0)
    check("occupied after the delay: occupied profile's heat", wait_for(therm_id, "heatingSetpoint", 18.0, 20 + SLACK_S), 18.0)
    check("program device reports occupied", wait_for(prog_dev, "occupancy", "occupied"), "occupied")
    st = status()
    check("profile is the occupied profile", st.get("profile"), "Comfort")
    check("status stays schedule", st.get("status"), "schedule")
    check("heating target includes it", num(st.get("heatingTarget")), 18.0)

    # ── A short break keeps the zone occupied ───────────────────────
    section("A short break keeps the zone occupied")
    maker(motion_id, "inactive")
    time.sleep(20)
    maker(motion_id, "active")
    time.sleep(DELAY_S + 10)
    check("still occupied", attr(prog_dev, "occupancy"), "occupied")
    check("setpoint kept", num(attr(therm_id, "heatingSetpoint")), 18.0)

    # ── Eco on top ───────────────────────────────────────────────────
    section("Eco on top")
    run_method(prog_dev, "setEco", "on")
    check("eco lowers the occupied setpoint", wait_for(therm_id, "heatingSetpoint", 16.0), 16.0)
    run_method(prog_dev, "setEco", "off")
    check("eco off restores it", wait_for(therm_id, "heatingSetpoint", 18.0), 18.0)

    # ── A hold outlasts a change of occupancy ────────────────────────
    section("A hold outlasts a change of occupancy")
    run_method(prog_dev, "holdProfile", "Base", "next")
    check("hold on Base", wait_for(therm_id, "heatingSetpoint", 17.0), 17.0)
    maker(motion_id, "inactive")
    check("zone goes vacant", wait_for(prog_dev, "occupancy", "vacant", DELAY_S + SLACK_S), "vacant")
    st = status()
    check("hold still in effect", (st.get("status"), st.get("holdEnd")), ("hold", "next"))
    run_method(prog_dev, "resume")
    time.sleep(3)
    check("resume while vacant: period setpoint", num(attr(therm_id, "heatingSetpoint")), 17.0)
    check("back on the schedule", status().get("status"), "schedule")

    # ── A period that doesn't use the occupied profile ───────────────
    section("A period that doesn't use the occupied profile")
    s, doc = call("GET", f"/programs/{prog_id}")
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": doc["revision"], "config": base_config(doc, ticked=False)})
    check("PUT without the tick", s, 200)
    maker(motion_id, "active")
    check("zone occupied", wait_for(prog_dev, "occupancy", "occupied", DELAY_S + SLACK_S), "occupied")
    time.sleep(3)
    check("setpoint stays the period's", num(attr(therm_id, "heatingSetpoint")), 17.0)
    s, doc = call("GET", f"/programs/{prog_id}")
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": doc["revision"], "config": base_config(doc)})
    check("PUT with the tick", s, 200)
    check("ticked again while occupied: occupied profile", wait_for(therm_id, "heatingSetpoint", 18.0), 18.0)

    # ── Pause while occupied ─────────────────────────────────────────
    section("Pause while occupied")
    run_method(prog_dev, "off")
    check("paused: thermostat off", wait_for(therm_id, "thermostatMode", "off"), "off")
    run_method(prog_dev, "on")
    check("resumed: mode restored", wait_for(therm_id, "thermostatMode", "heat"), "heat")
    check("resumed: occupied profile applied", wait_for(therm_id, "heatingSetpoint", 18.0), 18.0)

    # ── Done while occupied ──────────────────────────────────────────
    section("Done while occupied")
    before_n = setpoint_events()
    save_settings(prog_id, opt_page, {})
    time.sleep(10)
    check("still occupied after Done", attr(prog_dev, "occupancy"), "occupied")
    check("Done wrote no setpoint", setpoint_events(), before_n)
    check("setpoint kept", num(attr(therm_id, "heatingSetpoint")), 18.0)

    # ── Removing the sensor ──────────────────────────────────────────
    section("Removing the sensor")
    save_settings(prog_id, opt_page, {"occupancySensor": []})
    check("program device reports none", wait_for(prog_dev, "occupancy", "none"), "none")
    check("period setpoint applied", wait_for(therm_id, "heatingSetpoint", 17.0), 17.0)
finally:
    maker(motion_id, "inactive")
    if prog_id: delete_program(prog_id)

total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if failed: print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
