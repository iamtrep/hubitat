#!/usr/bin/env bash
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Thermostat Scheduler+ importer test (Mode 2): POST /import builds a paused
# program from a built-in Thermostat Scheduler.
# Reference (set up by hand once, shared with test-tsp-parity.sh): a built-in
# "Thermostat Scheduler 2.0" instance labelled "TEST TS EcoMode+Away" with one
# thermostat, an all-days time-period schedule, an Away setting and a
# restriction switch.
# Creates programs labelled "test-tsp-import" and after the reference's label,
# and deletes them before exiting. The reference thermostat must not change.
#
# Usage:
#   bash tests/test-tsp-import.sh            # default hub
#   bash tests/test-tsp-import.sh @hubname   # specific hub
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
REF_LABEL = "TEST TS EcoMode+Away"
IMPORT_LABEL = "test-tsp-import"
BUILTIN_TYPE = "Thermostat Scheduler 2.0"

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
    if got == want: ok(f"{name}")
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

def walk(entries):
    for e in entries:
        d = e.get("data")
        if isinstance(d, dict):
            yield d
        yield from walk(e.get("children", []))

# ── Setup ────────────────────────────────────────────────────────────
section("Setup")
apps = list(walk(fetch("/hub2/appsList").get("apps", [])))
parents = [a for a in apps if a.get("type") == PARENT_TYPE]
if not parents:
    die(f"No '{PARENT_TYPE}' instance on {hub_name}")
parent_id = parents[0]["id"]
refs = [a for a in apps if a.get("type") == BUILTIN_TYPE and a.get("name") == REF_LABEL]
if len(refs) != 1:
    die(f"Need exactly one '{BUILTIN_TYPE}' labelled '{REF_LABEL}' (see test-tsp-parity.sh)")
ref_id = refs[0]["id"]
ref_set = fetch(f"/installedapp/configure/json/{ref_id}").get("settings") or {}
ref_st = {x["name"]: x["value"] for x in fetch(f"/installedapp/statusJson/{ref_id}").get("appState", [])}
ref_therm = next(iter(ref_set["therm"]))
ref_switch = next(iter(ref_set["disabled"]))

api_page = fetch_text(f"/installedapp/configure/json/{parent_id}/apiPage")
m = re.search(r"access_token=([a-f0-9-]+)", api_page)
if not m:
    die("Could not find access_token on the parent's apiPage (is OAuth enabled?)")
token = m.group(1)
base = f"http://{hub_ip}/apps/api/{parent_id}"

def call(method, path, body=None, auth=True):
    h = {"Content-Type": "application/json"}
    if auth: h["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw, code = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read(), e.code
    try:
        return code, json.loads(raw or b"{}")
    except Exception:
        return code, {"_raw": raw.decode(errors="replace")}

DEV_ID = ref_therm
def device_attr(name):
    j = fetch(f"/device/fullJson/{DEV_ID}")
    cs = j.get("currentStates") or (j.get("device") or {}).get("currentStates") or {}
    if isinstance(cs, dict):
        return (cs.get(name) or {}).get("value")
    for s in cs:
        if s.get("name") == name: return s.get("value")
    return None


def save_done(app_id, therm_ids):
    """Done on a program's main page, echoing its thermostats input so the save cannot clear it."""
    cfg = fetch(f"/installedapp/configure/json/{app_id}")
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(app_id)),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]"),
              ("thermostats.type", "capability.thermostat"), ("thermostats.multiple", "true"),
              ("settings[thermostats]", ",".join(str(i) for i in therm_ids)),
              ("referrer", f"http://{hub_ip}/installedapp/list"),
              ("url", f"http://{hub_ip}/installedapp/configure/{app_id}/mainPage"), ("_cancellable", "false")]
    resp = json.loads(post_form("/installedapp/update/json", fields))
    if resp.get("status") != "success":
        die(f"app {app_id}: Done rejected: {resp}")

def app_log(app_id):
    """Recent hub log lines from one app (the hub keeps the last few thousand)."""
    out = []
    for r in fetch("/logs/past/json"):
        s = r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)
        if f"app|{app_id}|" in s: out.append(s)
    return out

def delete_program(pid):
    try:
        fetch(f"/installedapp/delete/{pid}")
    except Exception as e:
        info(f"could not delete program {pid}: {e}")

# Leftovers of an earlier run. An imported program stays out of /hub2/appsList until it is
# saved with Done, so list programs through the API (the parent sees all its children).
s, b = call("GET", "/programs")
if s != 200:
    die(f"GET /programs answered {s}")
for p in b.get("programs", []):
    n = str(p.get("name", ""))
    if n.startswith(IMPORT_LABEL) or n.startswith(REF_LABEL):
        delete_program(p["id"])
        info(f"deleted leftover program {n}")

created = []
try:
    # ── Errors ────────────────────────────────────────────────────────
    section("Errors")
    s, b = call("POST", "/import", {}); check("missing from is 400", s, 400)
    s, b = call("POST", "/import", {"from": 999999}); check("unknown scheduler is 404", s, 404)
    s, b = call("POST", "/import", {"from": parent_id}); check("an app that is not a built-in scheduler is 404", s, 404)
    s, b = call("POST", "/import", {"from": ref_id}, auth=False); check("no token is 401", s, 401)

    # ── Import ────────────────────────────────────────────────────────
    section("Import")
    heat_before = device_attr("heatingSetpoint")
    mode_before = device_attr("thermostatMode")
    s, b = call("POST", "/import", {"from": ref_id, "label": IMPORT_LABEL})
    check("import is 201", s, 201)
    if s != 201:
        fail(f"import answered {s} {b}; the remaining cases need the program")
        raise SystemExit(1)
    new_id = b.get("id")
    created.append(new_id)
    check("import uses the given label", b.get("name"), IMPORT_LABEL)
    check("import reports warnings as a list", isinstance(b.get("warnings"), list), True)
    s, doc = call("GET", f"/programs/{new_id}")
    cfg = doc.get("config", {})
    check("imported program is paused", doc.get("status", {}).get("status"), "paused")
    check("imported thermostat", [str(t["id"]) for t in doc.get("thermostats", [])], [str(ref_therm)])
    prog_set = fetch(f"/installedapp/configure/json/{new_id}").get("settings") or {}
    sw = prog_set.get("pauseSwitch")
    check("imported pause switch", sorted(map(str, sw.keys())) if isinstance(sw, dict) else sw, [str(ref_switch)])
    want_when = "off" if str(ref_set.get("disabledOff")).lower() == "true" else "on"
    check("imported pause polarity", prog_set.get("pauseWhenSwitch"), want_when)
    gid = next(iter(ref_st["dayGroups"]))
    periods = cfg["schedules"][0]["groups"][0]["periods"]
    profiles = {p["name"]: p for p in cfg["profiles"]}
    want = [(p, float(ref_st[f"heat{p}.{gid}"])) for p in ref_st["timeSort"] if ref_st.get(f"heat{p}.{gid}") is not None]
    got = [(p["name"], float(profiles[p["profile"]].get("heat"))) for p in periods]
    check("every live period imported with its heating setpoint", got, want)
    check("Away profile holds the Away setpoint", float(profiles["Away"]["heat"]), float(ref_st["heatAway"]))
    away_mode = next(m["id"] for m in fetch("/modes/json")["modes"] if m["name"].lower() == "away")
    check("Away override", cfg.get("overrides"), [{"modeId": away_mode, "profile": "Away"}])
    check("eco offset", float(cfg["eco"]["offset"]), float(ref_st["ecoSet"]))

    # ── Done writes nothing ───────────────────────────────────────────
    section("Done writes nothing")
    # "Turn thermostats off while paused" is the setting that would act on Done if the pause were not
    # marked as already applied (a common built-in setting).
    s, doc = call("GET", f"/programs/{new_id}")
    off_cfg = json.loads(json.dumps(doc["config"])); off_cfg["options"]["whilePaused"] = "off"
    s, b = call("PUT", f"/programs/{new_id}", {"revision": doc["revision"], "config": off_cfg})
    check("set while paused to turn thermostats off", [s, b.get("config", {}).get("options", {}).get("whilePaused")], [200, "off"])
    save_done(new_id, [ref_therm])
    time.sleep(5)
    check("done on imported program writes nothing", [device_attr("heatingSetpoint"), device_attr("thermostatMode")], [heat_before, mode_before])
    s, doc = call("GET", f"/programs/{new_id}")
    check("Done kept the thermostat", [str(t["id"]) for t in doc.get("thermostats", [])], [str(ref_therm)])
    check("still paused after Done", doc.get("status", {}).get("status"), "paused")
    # A program that applies its pause logs "paused" (or "paused: thermostats off"). An imported one
    # starts with the pause already applied, so neither the import nor Done may apply it again.
    applied = [l for l in app_log(new_id) if ": paused" in l]
    check("import and Done never apply the pause", applied, [])

    # ── Default label ─────────────────────────────────────────────────
    section("Default label")
    s, b = call("POST", "/import", {"from": ref_id})
    check("second import is 201", s, 201)
    created.append(b.get("id"))
    check("default label comes from the built-in", str(b.get("name", "")).startswith(REF_LABEL), True)
finally:
    for pid in created:
        if pid:
            delete_program(pid)

total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if failed: print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
