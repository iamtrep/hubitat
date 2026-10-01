#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# test-architecture-claims — re-checks the platform claims behind ARCHITECTURE.md
#
# Each check below backs a statement in ARCHITECTURE.md or
# docs/hubitat-platform-notes.md. Re-run after a firmware update; a FAIL means
# the platform changed and the docs need updating.
#
# Probes: apps/tests/ArchitectureClaimsProbe.groovy (pushed twice: as-is, and
# as "... ST" with singleThreaded: true) and
# drivers/tests/ArchitectureClaimsProbeDriver.groovy (pushed twice: as-is,
# and as "... B" so a device can switch drivers).
#
# Phases: provision, sandbox, push, threading and async callbacks, the
# singleThreaded lock matrix, state commit, scheduling, sendEvent dedup,
# device state and driver switch, CORS.
# With --reboot it also arms scheduled jobs, REBOOTS THE HUB, and checks
# which jobs survived and when they fired (adds ~5 minutes).
#
# Usage:
#   bash apps/tests/test-architecture-claims.sh [@hub] [--reboot] [--keep]
#     --reboot  include the reboot phase (reboots the hub)
#     --keep    leave the probe apps, drivers and device on the hub
#
# Runtime budget: ~280s without --reboot.
#
# Per TESTING.md §1.1: single invocation, exit 0/1/2, [PASS]/[FAIL]/[WARN]
# labels, idempotent (re-provisions what it finds), no production mutation
# (dedicated probe instances and device), no hardcoded IPs.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
while [[ "$PROJECT_ROOT" != "/" && ! -e "$PROJECT_ROOT/.hubitat.json" ]]; do
    PROJECT_ROOT="$(dirname "$PROJECT_ROOT")"
done
if [[ ! -e "$PROJECT_ROOT/.hubitat.json" ]]; then
    echo "Could not find .hubitat.json walking up from $SCRIPT_DIR" >&2
    exit 2
fi
APP_SOURCE="$SCRIPT_DIR/ArchitectureClaimsProbe.groovy"
DRIVER_SOURCE="$(cd "$SCRIPT_DIR/../../drivers/tests" && pwd)/ArchitectureClaimsProbeDriver.groovy"

python3 - "$PROJECT_ROOT/.hubitat.json" "$APP_SOURCE" "$DRIVER_SOURCE" "$@" <<'PYTHON_SCRIPT'
import http.cookiejar, json, re, sys, threading, time, urllib.error, urllib.parse, urllib.request

config_file, app_source, driver_source = sys.argv[1:4]
args = sys.argv[4:]

HUB_NAME = next((a[1:] for a in args if a.startswith("@")), None)
DO_REBOOT = "--reboot" in args
KEEP = "--keep" in args

APP_MT, APP_ST = "Architecture Claims Probe", "Architecture Claims Probe ST"
DRV_A, DRV_B = "Architecture Claims Probe Driver", "Architecture Claims Probe Driver B"
NAMESPACE = "tests"
LABEL_MT, LABEL_MT2 = "test-arch-claims-mt", "test-arch-claims-mt2"
LABEL_ST, LABEL_ST2 = "test-arch-claims-st", "test-arch-claims-st2"
DEVICE_DNI, DEVICE_NAME = "TEST_ARCH_CLAIMS_PROBE", "test-arch-claims-device"
DATE_SAMPLE_EPOCH = 1778036863088   # 2026-05-05T23:07:43.088-0400
DOCUMENTED_ASYNC_POOL = 8            # concurrent async HTTP calls per app, docs/hubitat-platform-notes.md
RUNTIME_BUDGET = 280

GREEN, RED, YELLOW, CYAN, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[36m", "\033[2m", "\033[0m"
passed = failed = warnings = 0
start_time = time.time()

def ok(msg):
    global passed; passed += 1; print(f"  {GREEN}[PASS]{RESET} {msg}")
def fail(msg):
    global failed; failed += 1; print(f"  {RED}[FAIL]{RESET} {msg}")
def warn(msg):
    global warnings; warnings += 1; print(f"  {YELLOW}[WARN]{RESET} {msg}")
def info(msg): print(f"  {DIM}{msg}{RESET}")
def section(msg): print(f"\n{CYAN}--- {msg} ---{RESET}")
def die(msg, code=2):
    print(f"{RED}{msg}{RESET}"); sys.exit(code)
def check(cond, msg_ok, msg_fail):
    ok(msg_ok) if cond else fail(msg_fail)

# ── Hub access ────────────────────────────────────────────────────────
try:
    with open(config_file) as f: config = json.load(f)
except (OSError, ValueError) as e:
    die(f"Cannot read {config_file}: {e}")
hub_name = HUB_NAME or config.get("default_hub", "")
hub_cfg = config.get("hubs", {}).get(hub_name)
if not hub_cfg:
    die(f"Hub '{hub_name}' not found in .hubitat.json")
hub = f"http://{hub_cfg['hub_ip']}"

cookies = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_a, **_k): return None
no_redirect = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies), NoRedirect())

def request(method, path, form=None, json_body=None, expect="json", timeout=30):
    """One admin request. form: dict or list of pairs. expect: json | text | url."""
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

def redirect_location(path):
    try:
        no_redirect.open(hub + path, timeout=15)
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307): return e.headers.get("Location", "")
        raise
    return ""

if hub_cfg.get("username") and hub_cfg.get("password"):
    try:
        request("POST", "/login", form={"username": hub_cfg["username"], "password": hub_cfg["password"]}, expect="text")
    except Exception as e:
        die(f"Hub login failed: {e}")

fw = (request("GET", "/hub2/hubData") or {}).get("baseModel", {}).get("buildVersion", "unknown")
info(f"Hub: {hub_name} ({hub_cfg['hub_ip']}), firmware {fw}")

def put_code(kind, name, source):
    """Create or update an app ("app") or driver ("driver") type; returns its id."""
    listing = request("GET", "/hub2/userAppTypes" if kind == "app" else "/hub2/userDeviceTypes") or []
    found = next((t for t in listing if t.get("name") == name and t.get("namespace") == NAMESPACE), None)
    if found:
        version = request("GET", f"/{kind}/ajax/code?id={found['id']}")["version"]
        r = request("POST", f"/{kind}/ajax/update", form={"id": found["id"], "version": version, "source": source}, timeout=90)
        if r.get("status") != "success": raise RuntimeError(f"{name}: {r.get('errors') or r}")
        return str(found["id"])
    r = request("POST", f"/{kind}/saveOrUpdateJson", json_body={"source": source, "version": 1}, timeout=90)
    if not r.get("success"): raise RuntimeError(f"{name}: {r.get('message') or r}")
    return str(r["id"])

def walk_apps(entries, out):
    for e in entries:
        d = e.get("data") or {}
        if isinstance(d, dict): out.append(d)
        walk_apps(e.get("children") or [], out)
    return out

def find_instance(label):
    apps = walk_apps((request("GET", "/hub2/appsList") or {}).get("apps") or [], [])
    return next((str(a["id"]) for a in apps if a.get("name") == label), None)

def save_settings(iid, label, device_ids=()):
    """Done on the probe's main page: sets the label and device input, runs installed()/updated()."""
    cfg = request("GET", f"/installedapp/configure/json/{iid}")
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", iid),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]"), ("label.type", "text"), ("label", label),
              ("probeDevice.type", "capability.actuator"), ("probeDevice.multiple", "false")]
    if device_ids: fields.append(("settings[probeDevice]", ",".join(device_ids)))
    fields += [("deviceList", "probeDevice"), ("", ""), ("referrer", f"{hub}/installedapp/list"),
               ("url", f"{hub}/installedapp/configure/{iid}/mainPage"), ("_cancellable", "false")]
    r = request("POST", "/installedapp/update/json", form=fields)
    if not isinstance(r, dict) or r.get("status") != "success": raise RuntimeError(f"settings save rejected: {r}")

def ensure_instance(type_id, label, device_ids=()):
    iid = find_instance(label)
    if iid is None:
        m = re.search(r"/installedapp/configure/(\d+)", redirect_location(f"/installedapp/create/{type_id}"))
        if not m: raise RuntimeError(f"could not create an instance of app type {type_id}")
        iid = m.group(1)
    save_settings(iid, label, device_ids)
    return iid

def status_json(iid):
    return request("GET", f"/installedapp/statusJson/{iid}")

def app_state(iid):
    return {s["name"]: s.get("value") for s in status_json(iid).get("appState", [])}

def run_command(device_id, command, value):
    request("POST", "/device/runmethod", json_body={"id": int(device_id), "method": command,
            "args": [{"type": "STRING", "value": value}]}, expect="text")

# ── Provision ─────────────────────────────────────────────────────────
section("Provision")

with open(app_source) as f: APP_SRC = f.read()
with open(driver_source) as f: DRV_SRC = f.read()
ST_SRC = APP_SRC.replace(f'name: "{APP_MT}"', f'name: "{APP_ST}"').replace("singleThreaded: false", "singleThreaded: true")
DRV_B_SRC = DRV_SRC.replace(f'name: "{DRV_A}"', f'name: "{DRV_B}"')

try:
    drv_a = put_code("driver", DRV_A, DRV_SRC)
    drv_b = put_code("driver", DRV_B, DRV_B_SRC)
    type_mt = put_code("app", APP_MT, APP_SRC)
    type_st = put_code("app", APP_ST, ST_SRC)
except Exception as e:
    die(f"Install/push failed: {e}")

devs = walk_apps((request("GET", "/hub2/devicesList") or {}).get("devices") or [], [])   # same tree shape as appsList
dev = next((d for d in devs if d.get("dni") == DEVICE_DNI), None)
if dev:
    device_id = str(dev["id"])
else:
    final = request("POST", "/device/save", form={"name": DEVICE_NAME, "label": DEVICE_NAME,
                    "deviceNetworkId": DEVICE_DNI, "deviceTypeId": drv_a}, expect="url")
    m = re.search(r"/device/edit/(\d+)", final)
    if not m: die(f"could not create the probe device (landed on {final})")
    device_id = m.group(1)

def device_full():
    return request("GET", f"/device/fullJson/{device_id}")

def set_device_driver(type_id):
    d = device_full()["device"]
    request("POST", "/device/update", expect="text", form={
        "id": device_id, "version": d["version"], "name": d["name"], "label": d.get("label") or d["name"],
        "deviceNetworkId": d["deviceNetworkId"], "deviceTypeId": type_id, "zigbeeId": d.get("zigbeeId") or "",
        "maxEvents": d.get("maxEvents", 11), "maxStates": d.get("maxStates", 100),
        "spammyThreshold": d.get("spammyThreshold", 300), "roomId": d.get("roomId") or 0,
        "groupId": d.get("groupId") or 0, "locationId": d.get("locationId"), "hubId": d.get("hubId"),
        "meshEnabled": "false", "retryEnabled": "false", "homeKitEnabled": "false",
        "dashboardIds": "", "tags": "", "defaultIcon": "", "notes": "", "controllerType": d.get("controllerType") or ""})

if str(device_full()["device"]["deviceTypeId"]) != str(drv_a):
    set_device_driver(drv_a)

try:
    iid_mt = ensure_instance(type_mt, LABEL_MT, [device_id])
    iid_mt2 = ensure_instance(type_mt, LABEL_MT2)
    iid_st = ensure_instance(type_st, LABEL_ST)
    iid_st2 = ensure_instance(type_st, LABEL_ST2)
except Exception as e:
    die(f"Instance provisioning failed: {e}")
instances = (iid_mt, iid_mt2, iid_st, iid_st2)
tokens = {iid: app_state(iid).get("accessToken") for iid in instances}
if not all(tokens.values()):
    die(f"Probe instances have no access token: {tokens}")
ok(f"probe apps {type_mt}/{type_st}, drivers {drv_a}/{drv_b}, instances {'/'.join(instances)}, device {device_id}")

def api(iid, path, timeout=90, **params):
    q = urllib.parse.urlencode({"access_token": tokens[iid], **params})
    try:
        with urllib.request.urlopen(f"{hub}/apps/api/{iid}/{path}?{q}", timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, None

def run_cmd(cmd, value):
    run_command(device_id, cmd, value)
    time.sleep(1.2)   # events <1s apart coalesce; see platform notes

# ── Sandbox ───────────────────────────────────────────────────────────
section("Sandbox")

_, sb = api(iid_mt, "sandbox")
check(sb["objectClassName"] == "java.lang.Integer", "getObjectClassName(1) == java.lang.Integer", f"getObjectClassName: {sb['objectClassName']}")
check(sb["topLevel"] is None, "top-level non-@Field variable reads as null inside methods", f"top-level variable: {sb['topLevel']}")
check(sb["dateParse"] == str(DATE_SAMPLE_EPOCH), "Date.parse(ISO with -0400 offset) works in the sandbox", f"Date.parse: {sb['dateParse']}")
check(str(sb["computeIfAbsent"]).startswith("made-"), "ConcurrentHashMap.computeIfAbsent with a closure runs", f"computeIfAbsent: {sb['computeIfAbsent']}")

probe_src = 'definition(name: "Arch Claims getClass Probe", namespace: "tests", author: "PJ", description: "", category: "", iconUrl: "", iconX2Url: "")\npreferences { page(name: "p") { section { } } }\nvoid installed() { log.debug((1).getClass().name) }\n'
resp = request("POST", "/app/saveOrUpdateJson", json_body={"source": probe_src, "version": 1}, timeout=90)
if resp.get("success") or resp.get("id"):
    fail("getClass() compiled; the sandbox no longer rejects it")
    try: request("GET", f"/app/edit/deleteJson/{resp['id']}")
    except Exception: pass
else:
    check("MethodCallExpression" in str(resp), "getClass() is rejected when the code is saved", f"unexpected save response: {resp}")

api(iid_mt, "shared", write="from-mt")
_, peek = api(iid_mt2, "shared")
check(peek["writer"] == f"{iid_mt}:from-mt", "@Field static is shared by instances of the same app type",
      f"second instance saw {peek['writer']!r}")

# ── Push ──────────────────────────────────────────────────────────────
section("Code push")

api(iid_mt, "subscribe", mode="default")
for _ in range(3): api(iid_mt, "info")
_, before = api(iid_mt, "info")
put_code("app", APP_MT, APP_SRC.replace('PUSH_MARK = "base"', 'PUSH_MARK = "pushed"'))
time.sleep(1)
_, after = api(iid_mt, "info")
check(after["pushMark"] == "pushed", "pushed code runs immediately", f"pushMark after push: {after['pushMark']}")
check(after["updatedCount"] == before["updatedCount"] and after["initCount"] == before["initCount"],
      "push does not call updated() or initialize()", f"counts before {before} after {after}")
check(after["fieldCounter"] == 1, f"@Field static reset by push ({before['fieldCounter']} -> {after['fieldCounter']})",
      f"@Field static survived push ({before['fieldCounter']} -> {after['fieldCounter']})")
tag = f"push{int(time.time())}"
run_cmd("emit", tag)
_, post = api(iid_mt, "info")
check(tag in post["evDefault"], "subscriptions survive a push", f"handler did not see {tag}: {post['evDefault']}")
put_code("app", APP_MT, APP_SRC)

# ── Threading and async callbacks ─────────────────────────────────────
section("singleThreaded and async callbacks")

def endpoint_burst(iid, n=3, ms=1000):
    """Fire n concurrent endpoint calls; True when they ran strictly one after another."""
    out = [None] * n
    def worker(i): out[i] = api(iid, "sleep", ms=ms)[1]
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    for t in ts: t.start()
    for t in ts: t.join()
    starts = sorted(o["start"] for o in out)
    return all(b - a >= ms * 0.9 for a, b in zip(starts, starts[1:]))

def max_overlap(spans_a, spans_b=None):
    """Largest number of [in, out] spans running at one moment (across both lists if two are given)."""
    spans = list(spans_a) + list(spans_b or [])
    return max((sum(1 for o in spans if o[0] < s[1] and o[1] > s[0]) for s in spans), default=0)

BURSTS = 6
mt_serial = sum(endpoint_burst(iid_mt) for _ in range(BURSTS))
st_serial = sum(endpoint_burst(iid_st) for _ in range(BURSTS))
check(mt_serial == 0, f"without singleThreaded, concurrent endpoint calls overlap ({mt_serial}/{BURSTS} bursts serialized)",
      f"without singleThreaded, {mt_serial}/{BURSTS} bursts ran serialized")
if st_serial == BURSTS:
    warn(f"singleThreaded serialized every endpoint burst ({BURSTS}/{BURSTS}); the platform notes say it does not reliably")
else:
    ok(f"singleThreaded does not reliably serialize OAuth endpoint calls ({st_serial}/{BURSTS} bursts serialized)")

sleep_path = f"/apps/api/{iid_mt2}/sleep?access_token={tokens[iid_mt2]}&ms=3000"
ONE_HOST, FOUR_HOSTS = "127.0.0.1", "127.0.0.1,127.0.0.2,127.0.0.3,127.0.0.4"

def fan_in(iid, hosts, n=20, wait_s=120):
    api(iid, "asyncFire", n=n, targetPath=sleep_path, hosts=hosts)
    deadline = time.time() + wait_s
    while time.time() < deadline:
        time.sleep(5)
        _, r = api(iid, "asyncRead")
        if r["fanInFinalWrites"] >= 1 and r["plainKeys"] >= n: return r
    return api(iid, "asyncRead")[1]

one = fan_in(iid_mt, ONE_HOST)
info(f"one destination: {one['maxRequestsInFlight']} requests in flight at most")
mt = fan_in(iid_mt, FOUR_HOSTS)
check(mt["plainKeys"] == 20 and mt["fanInStatuses"] == ["200"], "20 async calls: all callbacks ran with HTTP 200 (none lost)",
      f"async callbacks: {mt['plainKeys']}/20, statuses {mt['fanInStatuses']}")
in_flight = mt["maxRequestsInFlight"]
if in_flight == DOCUMENTED_ASYNC_POOL:
    ok(f"async calls in flight at once, spread over 4 destinations: {in_flight}, matches the documented pool")
else:
    warn(f"async calls in flight at once, spread over 4 destinations: {in_flight}, documented pool is {DOCUMENTED_ASYNC_POOL}")
if one["maxRequestsInFlight"] < in_flight:
    ok(f"a single destination caps in-flight calls lower ({one['maxRequestsInFlight']}) than the app pool ({in_flight})")
check(mt["fanIn"] == 20 and mt["fanInFinalWrites"] == 1,
      "@Field static ConcurrentHashMap + AtomicInteger: 20/20 kept, one final state write",
      f"concurrent-map fan-in kept {mt['fanIn']}/20, final writes {mt['fanInFinalWrites']}")
check(mt["plainKeys"] == 20, "separate top-level state keys from callbacks: 20/20 kept", f"separate state keys: {mt['plainKeys']}/20")
info(f"callbacks overlapping at most: {max_overlap(mt['callbackSpans'])}")
if mt["plainMap"] < 20 and mt["atomicMap"] < 20:
    ok(f"shared-map read-modify-write loses entries in both state ({mt['plainMap']}/20) and atomicState ({mt['atomicMap']}/20)")
else:
    warn(f"no read-modify-write loss this run: state {mt['plainMap']}/20, atomicState {mt['atomicMap']}/20 (race is timing-dependent)")

# Two singleThreaded instances of one type fan in at the same time.
results = {}
def st_worker(iid): results[iid] = fan_in(iid, FOUR_HOSTS)
ts = [threading.Thread(target=st_worker, args=(i,)) for i in (iid_st, iid_st2)]
for t in ts: t.start()
for t in ts: t.join()
st, st2 = results[iid_st], results[iid_st2]
check(max_overlap(st["callbackSpans"]) == 1, "singleThreaded runs async callbacks one at a time (no overlap)",
      f"singleThreaded callbacks overlapped: {max_overlap(st['callbackSpans'])} at once")
check(st["plainMap"] == 20 and st["atomicMap"] == 20,
      "singleThreaded: shared-map read-modify-write keeps 20/20 in state and atomicState",
      f"singleThreaded fan-in: state {st['plainMap']}, atomicState {st['atomicMap']}")
across = max_overlap(st["callbackSpans"], st2["callbackSpans"])
if across > 1:
    ok(f"singleThreaded is per instance: callbacks of two instances of one type overlapped ({across} at once)")
else:
    warn("callbacks of two singleThreaded instances never overlapped; serialization may be per app type")

# ── Lock matrix: which handler types wait for each other ──────────────
section("singleThreaded lock: running handler vs arriving handler")

LOCK_EXPECT = {   # (running, arriving) -> what singleThreaded did on 2.5.2.128; the control always runs during
    ("scheduled", "scheduled"): "waited", ("scheduled", "callback"): "waited", ("scheduled", "endpoint"): "waited",
    ("callback", "scheduled"): "waited", ("callback", "callback"): "waited", ("callback", "endpoint"): "waited",
    ("endpoint", "scheduled"): "waited",
    ("endpoint", "callback"): None,   # inconsistent: ran during in some setups, waited in others
}
slow_url = f"http://127.0.0.1:8080/apps/api/{iid_mt2}/sleep?access_token={tokens[iid_mt2]}&ms=2000"

def lock_case(iid, running, arriving):
    """Run one long handler and one arriving handler; return 'during' or 'waited'."""
    if running == "endpoint":
        api(iid, "lockArm", arriving=arriving, slowUrl=slow_url)
        long_span = api(iid, "sleep", ms=6000)[1]
        time.sleep(1)
        arrive = api(iid, "lockRead")[1]["arriving"]
    else:
        api(iid, "lockArm", running=running, arriving=arriving if arriving != "endpoint" else "", slowUrl=slow_url)
        endpoint_span = None
        if arriving == "endpoint":
            time.sleep(1.8)
            endpoint_span = api(iid, "sleep", ms=100)[1]
        time.sleep(8)
        r = api(iid, "lockRead")[1]
        long_span, arrive = r["running"], (endpoint_span or r["arriving"])
    if not long_span.get("start") or not arrive.get("start"):
        return "no data"
    return "during" if long_span["start"] < arrive["start"] < long_span["end"] else "waited"

for (running, arriving), expected in LOCK_EXPECT.items():
    st_got, ctl_got = lock_case(iid_st, running, arriving), lock_case(iid_mt, running, arriving)
    label = f"{arriving} arriving while {'an' if running == 'endpoint' else 'a'} {running} handler runs"
    if expected is None:
        info(f"singleThreaded: {label} -> {st_got} (recorded, not judged: this case is inconsistent)")
    elif ctl_got != "during":
        warn(f"{label}: the control (no singleThreaded) {ctl_got}; this cell can't be judged")
    else:
        check(st_got == expected, f"singleThreaded: {label} -> {st_got}",
              f"singleThreaded: {label} -> {st_got}, expected {expected} (update the platform notes)")

# ── State commit ──────────────────────────────────────────────────────
section("state vs atomicState commit")

_, c = api(iid_mt, "commit")
check(c["otherRequestSaw"].get("atomic") == c["wrote"] and c["otherRequestSaw"].get("plain") != c["wrote"],
      "mid-method, atomicState is visible to another request and state is not",
      f"commit visibility: {c}")
token = f"t{int(time.time())}"
code, _ = api(iid_mt, "throwWrite", token=token)
st_after = app_state(iid_mt)
check(code == 500 and st_after.get("throwAtomic") == token and st_after.get("throwPlain") != token,
      "a handler that throws keeps its atomicState write and loses its state write",
      f"after throw: HTTP {code}, atomic={st_after.get('throwAtomic')!r}, plain={st_after.get('throwPlain')!r}")

# ── Scheduling ────────────────────────────────────────────────────────
section("Scheduling")

api(iid_mt, "runIn")
jobs = [j.get("methodName") or j.get("handler") for j in status_json(iid_mt).get("scheduledJobs", [])]
check(jobs.count("noopDefault") == 1 and jobs.count("noopNoOverwrite") == 2,
      "runIn overwrites a same-name job by default; [overwrite:false] keeps both",
      f"scheduled jobs: {jobs}")

# ── sendEvent dedup ───────────────────────────────────────────────────
section("sendEvent dedup and subscriber filtering")

def stored(value):
    evs = request("GET", f"/device/eventsJson/{device_id}") or []
    evs = evs if isinstance(evs, list) else evs.get("events", [])
    return sum(1 for e in evs if e.get("name") == "probe" and e.get("value") == value)

def emit_series(mode):
    api(iid_mt, "subscribe", mode=mode)
    tag = f"{mode}{int(time.time())}"
    for _ in range(3): run_cmd("emit", tag)
    run_cmd("emitForced", tag)
    time.sleep(1)
    _, i = api(iid_mt, "info")
    return stored(tag), i["evDefault"].count(tag), i["evUnfiltered"].count(tag)

s, d, u = emit_series("default")
check(s == 2 and d == 2, "default subscribers only: 3 same-value emits + 1 forced -> 2 stored, 2 delivered",
      f"default only: stored {s}, delivered {d}")
s, d, u = emit_series("both")
check(s == 4 and u == 4 and d == 2,
      "with a filterEvents:false subscriber: all 4 stored and delivered to it; default subscriber still gets 2",
      f"with unfiltered subscriber: stored {s}, unfiltered {u}, default {d}")

# ── Device state and driver switch ────────────────────────────────────
section("Device state, data values and driver switch")

_, sd = api(iid_mt, "storeDevice")
_, rd = api(iid_mt, "readDevice")
check(sd["sameCall"] == DEVICE_NAME and rd["storedClass"] == "java.util.HashMap" and str(rd["nextCall"]).startswith("exception"),
      "a DeviceWrapper in state works in the same call and comes back as a plain HashMap",
      f"DeviceWrapper in state: same call {sd}, next call {rd}")

marker = f"m{int(time.time())}"
run_cmd("markState", marker)
run_cmd("markData", marker)
full = device_full()
check(full.get("deviceState", {}).get("probeState") == marker,
      "driver state is in /device/fullJson deviceState (what the device page's State Variables card shows)",
      f"deviceState: {full.get('deviceState')}")
set_device_driver(drv_b)
time.sleep(2)
full = device_full()
data = full["device"].get("data") or {}
check(str(full["device"]["deviceTypeId"]) == str(drv_b), "POST /device/update switched the driver", f"driver still {full['device']['deviceTypeId']}")
check(data.get("probeData") == marker, "data values survive a driver switch", f"data after switch: {data}")
check(not full.get("deviceState"), "a driver switch clears device state", f"state after switch: {full.get('deviceState')}")
check("probeTypeUpdated" in data, "deviceTypeUpdated() runs on a driver switch", f"data after switch: {data}")
set_device_driver(drv_a)
time.sleep(2)
back = device_full()
check(not back.get("deviceState"), "state stays cleared after switching back to the original driver",
      f"state after switching back: {back.get('deviceState')}")

# ── CORS ──────────────────────────────────────────────────────────────
section("CORS on the local OAuth API")

url = f"{hub}/apps/api/{iid_mt}/info?access_token={tokens[iid_mt]}"
with urllib.request.urlopen(urllib.request.Request(url, headers={"Origin": "http://example.test"}), timeout=15) as r:
    acao = r.headers.get("Access-Control-Allow-Origin")
    check(r.status == 200 and acao is None, "cross-origin GET returns 200 with no Access-Control-Allow-Origin", f"GET {r.status}, ACAO {acao!r}")
try:
    urllib.request.urlopen(urllib.request.Request(url, method="OPTIONS", headers={"Origin": "http://example.test",
                           "Access-Control-Request-Method": "GET"}), timeout=15)
    fail("OPTIONS preflight was accepted")
except urllib.error.HTTPError as e:
    check(e.code == 405, "OPTIONS preflight returns 405", f"OPTIONS returned {e.code}")

# ── Reboot (opt-in) ───────────────────────────────────────────────────
if DO_REBOOT:
    section("Scheduled jobs across a reboot (rebooting the hub)")
    api(iid_mt, "rebootArm", dueSecs=120)
    armed = app_state(iid_mt)
    jobs_before = sorted(j.get("methodName") or "" for j in status_json(iid_mt).get("scheduledJobs", []))
    info(f"armed: one-shot due in 120 s, far job in 1 h, every-minute cron; jobs {jobs_before}")
    request("POST", "/hub/reboot", expect="text")
    reboot_at = time.time()
    time.sleep(30)
    while True:
        try:
            with urllib.request.urlopen(f"{hub}/hub2/hubData", timeout=5) as r:
                if r.status == 200: break
        except Exception:
            pass
        if time.time() - reboot_at > 600: die("hub did not come back within 10 minutes", 1)
        time.sleep(5)
    back_at = time.time()
    info(f"hub answered again after {int(back_at - reboot_at)} s; waiting for overdue jobs to settle")
    time.sleep(150)
    _, rb = api(iid_mt, "rebootRead")
    fired = rb["fired"]
    jobs_after = sorted(j.get("methodName") or "" for j in status_json(iid_mt).get("scheduledJobs", []))
    rel = [(f["what"], round((f["at"] - rb["armedAt"]) / 1000), f["uptime"]) for f in fired]
    info(f"fired (handler, s after arming, hub uptime s): {rel}")
    info(f"jobs after reboot: {jobs_after}")
    check("rebootFar" in jobs_after, "a far-future runIn job survives the reboot", f"rebootFar missing after reboot: {jobs_after}")
    check(any(f["what"] == "oneShot" for f in fired), "a one-shot runIn that came due during the reboot still runs",
          "the overdue one-shot never ran")
    check(sum(1 for f in fired if f["what"] == "oneShot") == 1, "the overdue one-shot runs once", "the overdue one-shot ran more than once")
    check(any(f["what"] == "everyMinute" and f["uptime"] < 600 for f in fired), "the cron schedule resumes after the reboot",
          "the cron schedule did not fire after the reboot")
    check(any(f["what"] == "systemStart" for f in fired), "the systemStart subscription fires", "no systemStart event seen")
    api(iid_mt, "runIn")   # replaces the reboot jobs with harmless ones

# ── Cleanup ───────────────────────────────────────────────────────────
section("Cleanup")
if KEEP:
    info("--keep: leaving probe apps, drivers and device on the hub")
else:
    time.sleep(5)   # let queued singleThreaded callbacks drain; a busy instance is slow to uninstall
    steps = [f"/installedapp/delete/{iid}" for iid in instances] + [f"/device/forceDelete/{device_id}/json"] \
        + [f"/app/edit/deleteJson/{tid}" for tid in (type_mt, type_st)] \
        + [f"/driver/editor/deleteJson/{tid}" for tid in (drv_a, drv_b)]
    left = []
    for step in steps:
        try: request("GET", step, expect="text", timeout=90)
        except Exception as e: left.append(f"{step} ({e})")
    if left: warn(f"cleanup incomplete, re-run or remove by hand: {left}")
    else: info("removed probe instances, app types, device and drivers")

elapsed = int(time.time() - start_time)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s, firmware {fw})")
if elapsed > RUNTIME_BUDGET and not DO_REBOOT:
    print(f"{YELLOW}NOTE: exceeded declared runtime budget of {RUNTIME_BUDGET}s{RESET}")
sys.exit(0 if failed == 0 else 1)
PYTHON_SCRIPT
