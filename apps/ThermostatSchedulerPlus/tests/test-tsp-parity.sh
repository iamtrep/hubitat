#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Thermostat Scheduler+ parity test: the built-in Thermostat Scheduler and a
# Thermostat Scheduler+ program run the same schedule on twin virtual
# thermostats, in real time, and the test compares the two heating setpoints.
#
# Reference (set up by hand once, never changed by this test): a built-in
# "Thermostat Scheduler 2.0" instance labelled "TEST TS EcoMode+Away" with one
# thermostat, an all-days time-period schedule, an Away setting and a
# "disabled by a switch" restriction. Any other built-in scheduler on the same
# thermostat (e.g. "TEST TS Second") is held restricted by its own switch for
# the run.
#
# Provisioned idempotently by this test:
#   - virtual thermostat "test-parity-plus" (same driver as the reference)
#   - Thermostat Scheduler+ program "test-parity" (created through the parent's
#     debug route) with a configuration generated from the reference's schedule,
#     the reference's restriction switch as its pause switch
#   - Maker API "test-parity-maker" (devices and hub mode)
#
# Cases (5 s settle each): apply vs Set Scheduled Temperatures, Away mode in and
# out, restriction on and off, and the intended difference (Away, manual change,
# apply: the built-in keeps the manual value, Thermostat Scheduler+ reapplies).
# Leaves the hub mode, every switch and the reference thermostat as found.
#
# Runtime ~90 s: gated behind RUN_SLOW_TESTS=1 (TESTING.md §1.1).
#
# Usage:
#   RUN_SLOW_TESTS=1 bash tests/test-tsp-parity.sh            # default hub
#   RUN_SLOW_TESTS=1 bash tests/test-tsp-parity.sh @hubname   # specific hub
#
# Exit: 0 all pass (or skipped), 1 failures, 2 setup error.

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

if [[ "${RUN_SLOW_TESTS:-}" != "1" ]]; then
    echo "  [INFO] test-tsp-parity skipped: runs ~90 s; set RUN_SLOW_TESTS=1"
    exit 0
fi

HUB_NAME=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; fi
done

python3 - "$HUB_NAME" "$CONFIG_FILE" <<'PYTHON_SCRIPT'
import json, re, sys, time, urllib.request, urllib.error, urllib.parse, http.cookiejar
from datetime import datetime, timezone

hub_name_arg, config_file = sys.argv[1], sys.argv[2]

BUILTIN_TYPE = "Thermostat Scheduler 2.0"
BUILTIN_LABEL = "TEST TS EcoMode+Away"
PARENT_TYPE = "Thermostat Scheduler+"
PROGRAM_TYPE = "Thermostat Scheduler+ Program"
PROGRAM = "test-parity"
STATUS = "test-parity scheduler"
PLUS_THERM = "test-parity-plus"
MAKER_LABEL = "test-parity-maker"
SETTLE = 5            # seconds after each action
RUN_WINDOW = 150      # seconds the cases need without a period boundary
MANUAL = 17.0         # case 4 manual setpoint
PARK = 15.0           # setpoint no period uses, to prove a write happened

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
        return r.read().decode(), r.geturl()

def walk(entries):
    for e in entries:
        d = e.get("data")
        if isinstance(d, dict):
            yield d
        yield from walk(e.get("children", []))

def apps():
    return list(walk(fetch("/hub2/appsList").get("apps", [])))

def devices():
    return list(walk(fetch("/hub2/devicesList").get("devices", [])))

def app_button(app_id, name):
    post_form("/installedapp/btn", {"id": str(app_id), "name": name,
                                    f"settings[{name}]": "clicked", f"{name}.type": "button"})

def _unset(v):
    return v is None or v == "[]" or v == ""

def _page_with_input(app_id, name):
    main = fetch(f"/installedapp/configure/json/{app_id}")
    def declares(cfg):
        return name in {i["name"] for sec in cfg["configPage"].get("sections", []) for i in sec.get("input", [])}
    if declares(main):
        return main
    for sec in main["configPage"].get("sections", []):
        for b in sec.get("body", []):
            if b.get("element") == "href" and b.get("page") and not b.get("url"):
                sub = fetch(f"/installedapp/configure/json/{app_id}/{b['page']}")
                if "configPage" in sub and declares(sub):
                    return sub
    die(f"app {app_id} has no input '{name}' on its main page or the pages it links to")

def app_settings(app_id, changes, label=None):
    """Save settings (runs updated()), one input at a time and in order, echoing
    every other setting on the input's page. Dies if a value does not land."""
    items = list(changes.items()) or [(None, None)]
    for name, want in items:
        cfg = _page_with_input(app_id, name) if name else fetch(f"/installedapp/configure/json/{app_id}")
        page, current = cfg["configPage"], cfg.get("settings") or {}
        sub_page = page.get("name", "mainPage") != "mainPage"
        fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(app_id)),
                  ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""),
                  ("appTypeName", ""), ("currentPage", page.get("name", "mainPage")),
                  ("pageBreadcrumbs", '["mainPage"]' if sub_page else "[]")]
        for sec in page.get("sections", []):
            for b in sec.get("body", []):
                if b.get("element") == "label":
                    fields += [(f"{b['name']}.type", "text"), (b["name"], label or cfg["app"].get("label") or "")]
        for sec in page.get("sections", []):
            for i in sec.get("input", []):
                n, typ = i["name"], i.get("type", "")
                if typ in ("button", "paragraph", "href"):
                    continue
                fields += [(f"{n}.type", typ), (f"{n}.multiple", "true" if i.get("multiple") else "false")]
                value = want if n == name else current.get(n)
                if isinstance(value, dict):
                    value = list(value.keys())
                if typ.startswith(("capability.", "device.")):
                    ids = [str(v) for v in (value or [])] if not _unset(value) else []
                    if ids:
                        fields.append((f"settings[{n}]", ",".join(ids)))
                    fields += [("deviceList", n), ("", "")]
                    continue
                if _unset(value):
                    continue
                if isinstance(value, list):
                    fields.append((f"settings[{n}]", json.dumps([str(v) for v in value])))
                    continue
                if typ == "bool":
                    fields.append((f"checkbox[{n}]", "on"))
                    value = "true" if str(value).lower() == "true" else "false"
                fields.append((f"settings[{n}]", str(value)))
        fields += [("referrer", f"http://{hub_ip}/installedapp/list"),
                   ("url", f"http://{hub_ip}/installedapp/configure/{app_id}/{page.get('name')}"),
                   ("_cancellable", "false")]
        body, _ = post_form("/installedapp/update/json", fields)
        try:
            resp = json.loads(body)
        except ValueError:
            resp = body[:200]
        if not isinstance(resp, dict) or resp.get("status") != "success":
            die(f"app {app_id}: save of {name!r} rejected: {resp}")
        if name is None:
            continue
        got = (fetch(f"/installedapp/configure/json/{app_id}").get("settings") or {}).get(name)
        if isinstance(want, list):
            got_ids = sorted(map(str, got.keys() if isinstance(got, dict) else (got or [])))
            if got_ids != sorted(map(str, want)):
                die(f"app {app_id}: {name!r} did not land: wanted {want}, hub has {got}")
        elif str(got).lower() != str(want).lower():
            die(f"app {app_id}: {name!r} did not land: wanted {want!r}, hub has {got!r}")

def setting_differs(current, want):
    got = current.get(want[0])
    if isinstance(want[1], list):
        return sorted(map(str, got.keys() if isinstance(got, dict) else (got or []))) != sorted(map(str, want[1]))
    return str(got).lower() != str(want[1]).lower()

# Setup reads the reference and provisions the rig; any unexpected error here is
# a setup failure (exit 2). Nothing outside the rig changes until the guard passes.
try:
    # ── Reference: the built-in scheduler ────────────────────────────────
    section("Reference")
    all_apps = apps()
    refs = [a for a in all_apps if a.get("type") == BUILTIN_TYPE and a.get("name") == BUILTIN_LABEL]
    if len(refs) != 1:
        die(f"Need exactly one '{BUILTIN_TYPE}' instance labelled '{BUILTIN_LABEL}' (found {len(refs)}). "
            f"Set it up by hand: one virtual thermostat, Time Periods, all days in one group, "
            f"an Away setting, restriction 'disabled by a switch'.")
    ref_id = refs[0]["id"]
    ref_cfg = fetch(f"/installedapp/configure/json/{ref_id}")
    ref_settings = ref_cfg.get("settings") or {}
    ref_status = fetch(f"/installedapp/statusJson/{ref_id}")
    ref_state = {s["name"]: s["value"] for s in ref_status.get("appState", [])}

    therm = ref_settings.get("therm") or {}
    restrict = ref_settings.get("disabled") or {}
    if len(therm) != 1 or len(restrict) != 1:
        die(f"'{BUILTIN_LABEL}' needs one thermostat and one restriction switch (has {therm}, {restrict})")
    ref_therm = next(iter(therm))
    restrict_id = next(iter(restrict))
    # disabledOff=false: restricted while the switch is on.
    restrict_on = "off" if str(ref_settings.get("disabledOff")).lower() == "true" else "on"
    turn_off = str(ref_settings.get("turnThermOff")).lower() == "true"
    if ref_settings.get("schedTypeL") != "Time Periods":
        die(f"'{BUILTIN_LABEL}' must schedule by Time Periods")
    groups = ref_state.get("dayGroups") or {}
    if list(groups.values()) != [[True] * 7]:
        die(f"'{BUILTIN_LABEL}' must have one day group covering every day (has {groups})")
    gid = next(iter(groups))
    if ref_state.get("heatAway") is None:
        die(f"'{BUILTIN_LABEL}' has no Away heating setpoint")
    heat_away = float(ref_state["heatAway"])

    periods = []
    for p in ref_state.get("timeSort") or []:
        heat = ref_state.get(f"heat{p}.{gid}")
        kind = ref_settings.get(f"time{p}.{gid}")
        if heat is None or not kind:
            die(f"'{BUILTIN_LABEL}' period {p} has no heating setpoint or start")
        if kind == "Sunrise":
            start = {"kind": "sunrise", "offset": int(ref_settings.get(f"atSunriseOffset{p}.{gid}") or 0)}
        elif kind == "Sunset":
            start = {"kind": "sunset", "offset": int(ref_settings.get(f"atSunsetOffset{p}.{gid}") or 0)}
        else:
            start = {"kind": "time", "at": ref_settings.get(f"atTime{p}.{gid}")}
        periods.append({"name": p, "start": start, "heat": float(heat)})
    info(f"'{BUILTIN_LABEL}': {len(periods)} periods, Away {heat_away}, restricted while switch is "
         f"{restrict_on}, turn off when restricted: {turn_off}")

    # Other built-in schedulers on the same thermostat are held restricted for the run.
    others = []
    for a in all_apps:
        if a.get("type") != BUILTIN_TYPE or a["id"] == ref_id:
            continue
        s = fetch(f"/installedapp/configure/json/{a['id']}").get("settings") or {}
        if ref_therm not in (s.get("therm") or {}):
            continue
        sw = s.get("disabled") or {}
        if len(sw) != 1:
            die(f"Built-in scheduler '{a.get('name')}' also drives the reference thermostat and has no "
                f"restriction switch to hold it off; disable it for the run")
        others.append({"label": a.get("name"), "switch": next(iter(sw)),
                       "on": "off" if str(s.get("disabledOff")).lower() == "true" else "on"})
    for o in others:
        info(f"'{o['label']}' also drives the reference thermostat: held restricted for the run")

    # ── Provisioning ─────────────────────────────────────────────────────
    section("Provisioning")
    devs = devices()
    by_id = {str(d["id"]): d for d in devs}
    ref_dev = fetch(f"/device/fullJson/{ref_therm}")["device"]
    ref_label = ref_dev.get("label") or ref_dev.get("name")
    plus = [d for d in devs if (d.get("label") or d.get("name")) == PLUS_THERM]
    if plus:
        plus_id = str(plus[0]["id"])
        ok(f"thermostat '{PLUS_THERM}' exists")
    else:
        _, final = post_form("/device/save", {"name": PLUS_THERM, "label": PLUS_THERM,
                                              "deviceNetworkId": "TEST-PARITY-PLUS",
                                              "deviceTypeId": str(ref_dev["deviceTypeId"])})
        m = re.search(r"/device/edit/(\d+)", final)
        plus_id = m.group(1) if m else next((str(d["id"]) for d in devices()
                                             if (d.get("label") or d.get("name")) == PLUS_THERM), None)
        if not plus_id:
            die(f"could not create '{PLUS_THERM}'")
        ok(f"created thermostat '{PLUS_THERM}'")

    parents = [a for a in all_apps if a.get("type") == PARENT_TYPE]
    if not parents:
        die(f"No '{PARENT_TYPE}' instance on {hub_name}; install the parent app first")
    parent_id = parents[0]["id"]
    if str((fetch(f"/installedapp/configure/json/{parent_id}").get("settings") or {}).get("debugEnable")).lower() != "true":
        app_settings(parent_id, {"debugEnable": True})
    api_page = fetch_text(f"/installedapp/configure/json/{parent_id}/apiPage")
    m = re.search(r"access_token=([a-f0-9-]+)", api_page)
    if not m:
        die("No access_token on the parent's apiPage (is OAuth enabled?)")
    req = urllib.request.Request(f"http://{hub_ip}/apps/api/{parent_id}/programs", method="POST",
                                 data=json.dumps({"label": PROGRAM}).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {m.group(1)}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        created = json.loads(r.read())
    prog_id = created["id"]
    ok(f"program '{PROGRAM}' {'created' if created.get('created') else 'exists'}")

    # Configuration equivalent to the reference: one profile per distinct setpoint.
    def pname(v): return f"H{v:.1f}"
    values = sorted({p["heat"] for p in periods} | {heat_away})
    modes = fetch("/modes/json")
    away_id = next((m["id"] for m in modes["modes"] if m["name"] == "Away"), None)
    day_id = next((m["id"] for m in modes["modes"] if m["name"] == "Day"), None)
    if away_id is None or day_id is None:
        die("The hub needs modes named Day and Away")
    plus_config = {"v": 1,
        "profiles": [{"name": pname(v), "heat": v} for v in values],
        "schedules": [{"name": "Builtin", "type": "time", "groups": [{"name": "All", "days": [1, 2, 3, 4, 5, 6, 7],
            "periods": [{"name": p["name"], "start": p["start"], "profile": pname(p["heat"])} for p in periods]}]}],
        "active": "Builtin", "overrides": [{"modeId": away_id, "profile": pname(heat_away)}]}
    config_json = json.dumps(plus_config, separators=(",", ":"))

    want = [("debugEnable", True), ("thermostats", [plus_id]), ("pauseSwitch", [restrict_id]),
            ("pauseWhenSwitch", restrict_on), ("whilePaused", "off" if turn_off else "leave"),
            ("onResume", "restore"), ("testConfigJson", config_json)]
    prog_settings = fetch(f"/installedapp/configure/json/{prog_id}").get("settings") or {}
    todo = {k: v for k, v in want if setting_differs(prog_settings, (k, v))}
    if todo:
        app_settings(prog_id, todo)
        info(f"program settings saved: {sorted(todo)}")
    app_button(prog_id, "btnLoadConfig")
    time.sleep(2)
    status_dev = next((str(d["id"]) for d in devices() if (d.get("label") or d.get("name")) == STATUS), None)
    if not status_dev:
        die(f"program device '{STATUS}' not found after provisioning")
    ok(f"program configured ({len(values)} profiles, {len(periods)} periods, Away override)")

    makers = [a for a in apps() if a.get("type") == "Maker API" and a.get("name") == MAKER_LABEL]
    need = sorted({ref_therm, plus_id, restrict_id, status_dev} | {o["switch"] for o in others})
    if makers:
        maker_id = makers[0]["id"]
    else:
        type_id = next((fetch(f"/installedapp/configure/json/{a['id']}")["app"]["appTypeId"]
                        for a in all_apps if a.get("type") == "Maker API"), None)
        if type_id is None:
            die("No Maker API instance to copy the app type from; create 'test-parity-maker' by hand")
        final = opener.open(f"http://{hub_ip}/installedapp/create/{type_id}", timeout=30).geturl()
        mm = re.search(r"/installedapp/configure/(\d+)", final)
        if not mm:
            die(f"could not create the Maker API instance ({final})")
        maker_id = mm.group(1)
        app_settings(maker_id, {}, label=MAKER_LABEL)
        info(f"created Maker API '{MAKER_LABEL}'")
    maker_settings = fetch(f"/installedapp/configure/json/{maker_id}").get("settings") or {}
    todo = {k: v for k, v in [("localAccess", True), ("allowModes", True), ("pickedDevices", need)]
            if setting_differs(maker_settings, (k, v))}
    if todo:
        app_settings(maker_id, todo)
    m = re.search(r"access_token=([a-f0-9-]+)", json.dumps(fetch(f"/installedapp/configure/json/{maker_id}")))
    if not m:
        die(f"No access_token on Maker API '{MAKER_LABEL}' (is OAuth enabled?)")
    maker_token, maker_base = m.group(1), f"http://{hub_ip}/apps/api/{maker_id}"
    ok(f"Maker API '{MAKER_LABEL}' ready")

    def maker(path):
        req = urllib.request.Request(maker_base + path, headers={"Authorization": f"Bearer {maker_token}"})
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
        try:
            return json.loads(raw or b"null")
        except ValueError:
            return None

    def cmd(dev, command, *args):
        path = f"/devices/{dev}/{command}"
        if args:
            path += "/" + ",".join(urllib.parse.quote(str(a), safe="") for a in args)
        maker(path)

    def attr(dev, name):
        d = maker(f"/devices/{dev}")
        for a in d.get("attributes", []):
            if a.get("name") == name:
                return a.get("currentValue")
        return None

    def set_mode(mid):
        maker(f"/modes/{mid}")

    def num(v):
        try: return float(v)
        except (TypeError, ValueError): return None

    def both(name="heatingSetpoint"):
        return attr(ref_therm, name), attr(plus_id, name)

    def check_equal(what, want=None):
        r, p = both()
        if num(r) is not None and num(r) == num(p):
            ok(f"{what}: both {num(r)}")
        else:
            fail(f"{what}: built-in {r}, Thermostat Scheduler+ {p}")
        if want is not None:
            if num(p) == want: ok(f"{what}: value is {want}")
            else: fail(f"{what}: value {p}, want {want}")

    def settle(): time.sleep(SETTLE)

    # ── Starting state ───────────────────────────────────────────────────
    section("Starting state")
    found = {"mode": modes.get("currentModeId"), "ref_mode": attr(ref_therm, "thermostatMode"),
             "ref_heat": attr(ref_therm, "heatingSetpoint"), "restrict": attr(restrict_id, "switch"),
             "others": {o["switch"]: attr(o["switch"], "switch") for o in others}}
    info(f"found: hub mode id {found['mode']}, '{ref_label}' {found['ref_mode']} {found['ref_heat']}, "
         f"restriction switch {found['restrict']}, other restriction switches {found['others']}")

    def boundary_guard():
        nt = attr(status_dev, "nextTransition")
        if not nt or nt == "none":
            return
        at = datetime.strptime(nt, "%Y-%m-%dT%H:%M:%S%z")
        left = (at - datetime.now(timezone.utc)).total_seconds()
        if left < RUN_WINDOW:
            die(f"A period starts at {at.strftime('%H:%M')} ({int(left)} s away), inside the test window. "
                f"Re-run after {at.strftime('%H:%M')}.")
        info(f"next period boundary {at.strftime('%H:%M')}, {int(left)} s away")

    boundary_guard()
except SystemExit:
    raise
except Exception as e:
    die(f"Setup failed: {type(e).__name__}: {e}")

def unrestricted_state(): return "on" if restrict_on == "off" else "off"

try:
    set_mode(day_id)
    for o in others:
        cmd(o["switch"], o["on"])
    cmd(restrict_id, unrestricted_state())
    cmd(ref_therm, "heat"); cmd(plus_id, "heat")
    cmd(status_dev, "on"); cmd(status_dev, "resume")
    settle()

    # ── Case 1 ───────────────────────────────────────────────────────
    section("Case 1: apply now vs Set Scheduled Temperatures")
    cmd(ref_therm, "setHeatingSetpoint", PARK); cmd(plus_id, "setHeatingSetpoint", PARK)
    time.sleep(2)
    app_button(ref_id, "setTemps"); app_button(prog_id, "btnApply")
    settle()
    period_value = num(attr(status_dev, "heatingTarget"))
    info(f"current period: {attr(status_dev, 'profile')} ({period_value})")
    check_equal("apply", period_value)

    # ── Case 2 ───────────────────────────────────────────────────────
    section("Case 2: Away mode in and out")
    set_mode(away_id); settle()
    check_equal("mode Away", heat_away)
    set_mode(day_id); settle()
    check_equal("back to Day", period_value)

    # ── Case 3 ───────────────────────────────────────────────────────
    section("Case 3: restriction on and off")
    cmd(restrict_id, restrict_on); settle()
    r, p = both("thermostatMode")
    want_mode = "off" if turn_off else "heat"
    if r == p == want_mode: ok(f"restricted: both thermostats {want_mode}")
    else: fail(f"restricted: built-in {r}, Thermostat Scheduler+ {p} (want {want_mode})")
    st = attr(status_dev, "status")
    if st == "restricted": ok("restricted: program status restricted")
    else: fail(f"restricted: program status {st}")
    set_mode(away_id); settle()
    check_equal("mode Away while restricted is ignored", period_value)
    set_mode(day_id); time.sleep(2)
    cmd(ref_therm, "setHeatingSetpoint", PARK); cmd(plus_id, "setHeatingSetpoint", PARK)
    time.sleep(2)
    cmd(restrict_id, unrestricted_state()); settle()
    r, p = both("thermostatMode")
    if r == p == "heat": ok("restriction lifted: both thermostats heat")
    else: fail(f"restriction lifted: built-in {r}, Thermostat Scheduler+ {p} (want heat)")
    if turn_off:
        check_equal("restriction lifted reapplies the period", period_value)
    else:
        # Observed: with "turn thermostats off" unset, the built-in only logs that the
        # restriction ended and writes nothing, so a value set while restricted stays.
        r, p = both()
        info(f"built-in writes nothing when the restriction lifts (keeps {r}); "
             f"Thermostat Scheduler+ resumes and applies the schedule")
        if num(r) == PARK: ok(f"built-in keeps the value set while restricted ({PARK})")
        else: fail(f"built-in has {r} after the restriction lifted; observed behavior was {PARK}")
        if num(p) == period_value: ok(f"Thermostat Scheduler+ reapplies the period ({period_value})")
        else: fail(f"Thermostat Scheduler+ has {p} after the restriction lifted, want {period_value}")

    # ── Case 4 ───────────────────────────────────────────────────────
    section("Case 4: Away, manual change, apply (intended difference)")
    set_mode(away_id); settle()
    cmd(ref_therm, "setHeatingSetpoint", MANUAL); cmd(plus_id, "setHeatingSetpoint", MANUAL)
    time.sleep(2)
    app_button(ref_id, "setTemps"); app_button(prog_id, "btnApply")
    settle()
    r, p = both()
    info(f"expected difference: after apply in Away the built-in has {r} (manual value was {MANUAL}), "
         f"Thermostat Scheduler+ has {p} (Away value {heat_away})")
    if num(r) == MANUAL: ok(f"built-in keeps the manual {MANUAL}: it skips the Away value on apply (known built-in bug)")
    else: fail(f"built-in has {r} after apply in Away; the known behavior is to keep the manual {MANUAL}")
    if num(p) == heat_away: ok(f"Thermostat Scheduler+ applies the Away value {heat_away}")
    else: fail(f"Thermostat Scheduler+ gives {p}, want {heat_away}")
except Exception as e:
    fail(f"unexpected error during the cases: {type(e).__name__}: {e}")
finally:
    section("Cleanup")
    try:
        set_mode(found["mode"] or day_id)
        time.sleep(2)
        cmd(restrict_id, found["restrict"] or unrestricted_state())
        for o in others:
            cmd(o["switch"], found["others"][o["switch"]] or ("off" if o["on"] == "on" else "on"))
        time.sleep(3)
        if found["ref_heat"] is not None:
            cmd(ref_therm, "setHeatingSetpoint", found["ref_heat"])
        if found["ref_mode"]:
            cmd(ref_therm, found["ref_mode"] if found["ref_mode"] != "emergency heat" else "emergencyHeat")
        cmd(status_dev, "resume")
        info("hub mode, switches and the reference thermostat restored")
    except Exception as e:
        print(f"  [WARN] cleanup incomplete: {e}")

total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if failed: print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
