#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Thermostat Scheduler+ API test (Mode 2): parent app HTTP routes.
# Needs the parent app installed with a "test-tsp" program (see test-tsp.sh).
# Leaves the program resumed (no hold). Turns the parent's debug logging on (it
# turns itself off after 30 minutes) and creates the program "test-tsp-new" once,
# through the debug-only POST /programs, to check a new program's defaults.
#
# Usage:
#   bash tests/test-tsp-api.sh            # default hub
#   bash tests/test-tsp-api.sh @hubname   # specific hub
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
PROGRAM_NAME = "test-tsp"
STATUS_LABEL = "test-tsp scheduler"
NEW_PROGRAM = "test-tsp-new"

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

def set_main_bool(app_id, name, want):
    """Save one bool on an app's main page (runs updated()), echoing the page's other bools and text inputs."""
    cfg = fetch(f"/installedapp/configure/json/{app_id}")
    page, current = cfg["configPage"], cfg.get("settings") or {}
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(app_id)),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]")]
    for sec in page.get("sections", []):
        for i in sec.get("input", []):
            n, typ = i["name"], i.get("type", "")
            if typ not in ("bool", "text", "number", "decimal", "enum"):
                continue
            value = want if n == name else current.get(n)
            fields += [(f"{n}.type", typ), (f"{n}.multiple", "false")]
            if value is None or value == "" or isinstance(value, (list, dict)):
                continue
            if typ == "bool":
                fields.append((f"checkbox[{n}]", "on"))
                value = "true" if str(value).lower() == "true" else "false"
            fields.append((f"settings[{n}]", str(value)))
    fields += [("referrer", f"http://{hub_ip}/installedapp/list"),
               ("url", f"http://{hub_ip}/installedapp/configure/{app_id}/mainPage"), ("_cancellable", "false")]
    resp = json.loads(post_form("/installedapp/update/json", fields))
    if resp.get("status") != "success":
        die(f"app {app_id}: save of {name} rejected: {resp}")

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
progs = [a for a in apps if a.get("name") == PROGRAM_NAME and a.get("type") == PARENT_TYPE + " Program"]
if not progs:
    die(f"No '{PROGRAM_NAME}' program on {hub_name}")
prog_id = progs[0]["id"]

api_page = fetch_text(f"/installedapp/configure/json/{parent_id}/apiPage")
m = re.search(r"access_token=([a-f0-9-]+)", api_page)
if not m:
    die("Could not find access_token on the parent's apiPage (is OAuth enabled?)")
token = m.group(1)
base = f"http://{hub_ip}/apps/api/{parent_id}"
info(f"API base: {base}")

def call(method, path, body=None, auth=True, raw_body=None, url=None):
    h = {"Content-Type": "application/json"}
    if auth: h["Authorization"] = f"Bearer {token}"
    data = raw_body if raw_body is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url or (base + path), method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read(), e.code
    try:
        return code, json.loads(raw or b"{}")
    except Exception:
        return code, {"_raw": raw.decode(errors="replace")}

def cmd(body):
    return call("POST", f"/programs/{prog_id}/command", body)

# Program device (read through the hub).
dev_id = None
for d in walk(fetch("/hub2/devicesList").get("devices", [])):
    if d.get("name") == STATUS_LABEL or d.get("label") == STATUS_LABEL:
        dev_id = d.get("id"); break
if dev_id is None:
    info(f"program device '{STATUS_LABEL}' not found; cross-check will fail")

def device_attr(name):
    j = fetch(f"/device/fullJson/{dev_id}")
    cs = j.get("currentStates") or (j.get("device") or {}).get("currentStates") or {}
    if isinstance(cs, dict):
        return (cs.get(name) or {}).get("value")
    for s in cs:
        if s.get("name") == name: return s.get("value")
    return None

try:
    # ── Auth ──────────────────────────────────────────────────────────
    section("Auth")
    s, b = call("GET", "/programs", auth=False); check("no token is refused (401)", s, 401)
    s, b = call("POST", f"/programs/{prog_id}/command", {"command": "resume"}, auth=False)
    check("no token on command is refused (401)", s, 401)

    # ── Read routes ───────────────────────────────────────────────────
    section("Read routes")
    s, b = call("GET", "/programs"); check("list ok", s, 200)
    check("list holds test-tsp", any(p.get("name") == PROGRAM_NAME for p in b.get("programs", [])), True)
    s, b = call("GET", f"/programs/{prog_id}"); check("get ok", s, 200)
    check("get has revision", "revision" in b, True)
    check("get has config profiles", len(b.get("config", {}).get("profiles", [])) > 0, True)
    s, b = call("GET", "/programs/999999"); check("unknown program is 404", s, 404)
    check("unknown program error text", "no program" in b.get("error", ""), True)
    s, b = call("GET", "/programs/abc"); check("non-numeric id is 404", s, 404)

    # ── Commands ──────────────────────────────────────────────────────
    section("Commands")
    s, b = cmd({"command": "holdProfile", "profile": "Sleep", "end": "30"})
    check("hold ok", s, 200)
    check("hold result ok flag", b.get("ok"), True)
    check("hold status", b.get("status"), "hold")
    check("hold profile", b.get("profile"), "Sleep")
    fields = ("schedule", "heatingTarget", "coolingTarget", "holdEnd", "nextTransition",
              "nextProfile", "eco", "ecoOffset", "lastApply")
    check("hold result carries the status fields", [k for k in fields if k not in b], [])
    check("hold reply holdEnd is set", b.get("holdEnd") not in (None, "", "none"), True)
    time.sleep(2)

    if dev_id is not None:
        got = device_attr("status")
        check("command status equals program device attribute", b.get("status"), got)

    s, b = cmd({"command": "resume"})
    check("resume ok", s, 200)
    check("resume status is not hold", b.get("status") != "hold", True)
    check("resume reply holdEnd is none", b.get("holdEnd"), "none")
    time.sleep(2)
    if dev_id is not None:
        check("resume status equals program device attribute", b.get("status"), device_attr("status"))

    s, b = cmd({"command": "explode"})
    check("bad command 400", s, 400)
    check("bad command error", "unknown command" in b.get("error", ""), True)
    s, b = cmd({"command": "holdProfile"})
    check("missing arg 400", s, 400)
    check("missing arg has error", bool(b.get("error")), True)
    s, b = cmd({"command": "holdSetpoints", "heating": 120, "end": "next"})
    check("out-of-range hold setpoint is 400", s, 400)
    check("out-of-range hold setpoint error", "out of range" in b.get("error", ""), True)
    s, b = cmd({"command": "holdProfile", "profile": "Sleep", "end": "2020-01-01T00:00:00Z"})
    check("hold ending in the past is 400", s, 400)
    s, b = cmd({"command": "holdProfile", "profile": "Sleep", "end": "2099-01-01T00:00:00Zjunk"})
    check("hold end with trailing text is 400", s, 400)
    s, b = call("POST", "/programs/999999/command", {"command": "resume"})
    check("command on unknown program is 404", s, 404)

    s, b = call("POST", f"/programs/{prog_id}/command", raw_body=b"{not json")
    info(f"malformed JSON body: HTTP {s} (platform answers before the app runs; not asserted)")

    # ── Configuration PUT ─────────────────────────────────────────────
    section("Configuration PUT")
    s, doc = call("GET", f"/programs/{prog_id}")
    original = doc["config"]
    rev = doc["revision"]
    if any(p.get("name") == "Temp" for p in original["profiles"]):   # left by an interrupted run
        original["profiles"] = [p for p in original["profiles"] if p.get("name") != "Temp"]
        s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": original})
        if s != 200: die(f"could not remove the leftover Temp profile: {s} {b}")
        rev = b["revision"]
        info("removed a leftover Temp profile")
    s, b = call("PUT", f"/programs/{prog_id}", {"config": original}, auth=False); check("put without token is 401", s, 401)
    s, b = call("PUT", "/programs/999999", {"revision": rev, "config": original}); check("put unknown program is 404", s, 404)
    s, b = call("PUT", f"/programs/{prog_id}", {"config": original}); check("put without revision is 400", s, 400)
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev}); check("put without config is 400", s, 400)
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": original})
    check("round trip put is 200", s, 200)
    check("round trip keeps the revision", b.get("revision"), rev)
    bad = json.loads(json.dumps(original)); bad["active"] = "No such schedule"
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": bad})
    check("invalid document is 400", s, 400)
    check("invalid document lists errors", any("No such schedule" in e for e in b.get("errors", [])), True)
    edited = json.loads(json.dumps(original))
    edited["profiles"].append({"name": "Temp", "heat": 17.5})
    edited["eco"] = {"offset": 3, "onOverrides": True}
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": edited})
    check("edited put is 200", s, 200)
    check("edited put adds the profile", any(p.get("name") == "Temp" for p in b.get("config", {}).get("profiles", [])), True)
    check("edited put sets the eco offset", float(b.get("config", {}).get("eco", {}).get("offset", 0)), 3.0)
    check("edited put changes the revision", b.get("revision") != rev, True)
    s, b2 = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": original})
    check("stale revision is 409", s, 409)
    check("409 carries the current revision", b2.get("revision"), b.get("revision"))
    rev = b.get("revision")
    s, h = cmd({"command": "holdProfile", "profile": "Temp", "end": "indefinite"}); check("hold Temp", h.get("profile"), "Temp")
    s, b = call("PUT", f"/programs/{prog_id}", {"revision": rev, "config": original})
    check("put restoring the original is 200", s, 200)
    check("put removing the held profile ends the hold", b.get("status", {}).get("holdEnd"), "none")
    check("original eco offset is back", float(b.get("config", {}).get("eco", {}).get("offset", 0)), float(original["eco"]["offset"]))

    # Two PUTs sent at once with the same revision: one must win, the other must get 409.
    import threading
    races = []
    for rnd in range(10):
        s, doc = call("GET", f"/programs/{prog_id}")
        bodies = []
        for k in (1, 2):
            c = json.loads(json.dumps(doc["config"])); c["profiles"].append({"name": f"Race{k}", "heat": 17.0})
            bodies.append({"revision": doc["revision"], "config": c})
        codes = [None, None]
        def put(i): codes[i] = call("PUT", f"/programs/{prog_id}", bodies[i])[0]
        ts = [threading.Thread(target=put, args=(i,)) for i in (0, 1)]
        for th in ts: th.start()
        for th in ts: th.join()
        races.append(sorted(codes))
        s, doc = call("GET", f"/programs/{prog_id}")
        call("PUT", f"/programs/{prog_id}", {"revision": doc["revision"], "config": original})
    check("concurrent PUTs with one revision: one 200, one 409", races, [[200, 409]] * 10)

    # ── Create route (debug only) ─────────────────────────────────────
    section("Create route")
    if str((fetch(f"/installedapp/configure/json/{parent_id}").get("settings") or {}).get("debugEnable")).lower() == "true":
        set_main_bool(parent_id, "debugEnable", False)
        info("turned the parent's debug logging off")
    s, b = call("POST", "/programs", {"label": NEW_PROGRAM})
    check("create is 404 while debug logging is off", s, 404)
    set_main_bool(parent_id, "debugEnable", True)
    info("turned the parent's debug logging on")
    s, b = call("POST", "/programs", {})
    check("create without label is 400", s, 400)
    s, b = call("POST", "/programs", {"label": NEW_PROGRAM})
    check("create returns 201 or the existing program (200)", s in (200, 201), True)
    new_id = b.get("id")
    s, b = call("GET", f"/programs/{new_id}")
    check("new program reads", s, 200)
    modes = fetch("/modes/json").get("modes", [])
    away = next((m["id"] for m in modes if str(m.get("name", "")).strip().lower() == "away"), None)
    want = [{"modeId": away, "profile": "Away"}] if away is not None else []
    check("new program maps the Away mode to the Away profile", (b.get("config") or {}).get("overrides"), want)

    # ── Cloud ─────────────────────────────────────────────────────────
    section("Cloud access")
    uid = None
    for a in [parent_id] + [x["id"] for x in apps if x.get("type") == "Maker API"]:
        try:
            t = fetch_text(f"/installedapp/configure/json/{a}")
        except Exception:
            continue
        mm = re.search(r"cloud\.hubitat\.com/api/([0-9a-f-]+)", t)
        if mm:
            uid = mm.group(1); break
    if not uid:
        info("hub UID not found in any app config page; cloud case skipped")
    else:
        s, b = call("GET", "", auth=True, url=f"https://cloud.hubitat.com/api/{uid}/apps/{parent_id}/programs")
        check("cloud request is refused (403) with cloud access off", s, 403)
finally:
    # Always leave the program resumed.
    try:
        cmd({"command": "resume"})
    except Exception:
        pass

total = passed + failed
print(f"\n{BOLD}=== Results: {passed}/{total} passed", end="")
if failed: print(f", {RED}{failed} failed{RESET}", end="")
print(f" ==={RESET}")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
