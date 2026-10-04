#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub
# TEST-EXCLUDE: a measurement run (about 10 min, 2 h for the full matrix); run it directly

#
# test-singlethreaded — which entry points singleThreaded: true serializes
#
# For every pair of entry points into one app or driver instance, a long call
# runs and a second call arrives while it is running. The second call either
# starts during the first one or waits for it. Each pair runs on a copy with
# singleThreaded: true and on a control copy without it. The trigger app fires
# both calls from the hub at set times on the hub clock, and every target
# records its own start and end, so network delay doesn't affect the result.
#
# App entry points: endpoint (over loopback and over the hub's LAN IP),
# scheduled handler, async callback, device event, location event, child device
# calling the parent, child app calling the parent, button handler, page
# render, settings save (updated()).
# Driver entry points: command from an app, command over HTTP, scheduled
# handler, async callback, parse(), child device calling the parent.
#
# Each cell interleaves REPS valid repetitions on the singleThreaded copy and
# REPS on the control. A repetition is discarded (and replaced) when the pair
# didn't overlap as planned, or when the second call started more than
# RELEASE_MAX ms after the first ended, which is slow dispatch, not waiting.
#   [PASS]  singleThreaded serialized every repetition, the control none
#   [WARN]  singleThreaded serialized none (a gap, consistently)
#   [FAIL]  mixed results, the control also waited, or the cell couldn't be measured
# With all n repetitions agreeing, a minority rate above 300/n % is ruled out
# at 95% (rule of three); use --reps 30 for a claim to publish.
#
# Not covered: radio parse() (Zigbee/Z-Wave), Maker API commands, cloud endpoint
# ingress, cron schedule(), a parent calling a singleThreaded child app.
# parse() reaches the control through the hub's LAN IP and the singleThreaded
# copy through loopback, because the hub routes it by sender IP.
#
# Probes: apps/tests/SingleThreadedProbe.groovy, SingleThreadedProbeChildApp.groovy,
# SingleThreadedProbeTrigger.groovy, drivers/tests/SingleThreadedProbeDriver.groovy,
# drivers/tests/SingleThreadedProbeChild.groovy. Target app, child app and
# driver are each pushed twice: as-is and as "... ST" with singleThreaded: true.
#
# Usage:
#   RUN_SLOW_TESTS=1 bash apps/tests/test-singlethreaded.sh [@hub] [options]
#     --reps N    valid repetitions per copy per cell (default 10)
#     --only      app | driver: run one domain
#     --kinds a,b limit the matrix to these entry points
#     --lan N     calls per LAN endpoint burst (default 3)
#     --sweep domain:running:arriving   instead of the matrix, sweep one pair over
#                 --gaps (ms between the two calls), --modes (pause,busy,http: how
#                 the first call holds the instance), --runs (its length, ms),
#                 --msA (the second call's length; default the same) and --idle
#                 (s of quiet before each repetition)
#     --raw FILE  append every repetition (spans, issue times, plan) as JSON lines
#     --resume    reuse the valid repetitions already in --raw (same hub and firmware)
#     --control-reps N   control repetitions per cell (default: same as --reps)
#     --no-provision     don't push code or save settings; use the probes on the hub as they are,
#                 so a second run (e.g. --only driver beside --only app) can share them
#     --keep      leave the probes on the hub
#   Without RUN_SLOW_TESTS=1 only the diagonal (each entry point against itself) runs.
#
# Runtime budget: ~2 h for the full matrix at 10 reps, ~10 min for the diagonal.
#
# Per TESTING.md §1.1: single invocation, exit 0/1/2, [PASS]/[FAIL]/[WARN]
# labels, idempotent (re-provisions what it finds), no production mutation
# (dedicated probe apps and devices), no hardcoded IPs.
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
DRIVER_DIR="$(cd "$SCRIPT_DIR/../../drivers/tests" && pwd)"

python3 -u - "$PROJECT_ROOT/.hubitat.json" "$SCRIPT_DIR" "$DRIVER_DIR" "$@" <<'PYTHON_SCRIPT'
import http.cookiejar, json, os, re, sys, threading, time, urllib.error, urllib.parse, urllib.request

config_file, app_dir, driver_dir = sys.argv[1:4]
args = sys.argv[4:]
HUB_NAME = next((a[1:] for a in args if a.startswith("@")), None)
KEEP = "--keep" in args
REPS = int(args[args.index("--reps") + 1]) if "--reps" in args else 10
ONLY = args[args.index("--only") + 1] if "--only" in args else None
KINDS = args[args.index("--kinds") + 1].split(",") if "--kinds" in args else None
def opt(name, default):
    return args[args.index(name) + 1] if name in args else default
SWEEP = opt("--sweep", None)                      # domain:running:arriving
SWEEP_GAPS = [int(x) for x in opt("--gaps", "0,5,20,100,500").split(",")]
SWEEP_MODES = opt("--modes", "pause").split(",")
SWEEP_RUNS = [int(x) for x in opt("--runs", "2000").split(",")]
SWEEP_IDLE = [float(x) for x in opt("--idle", "0").split(",")]   # s of quiet before each repetition
SWEEP_MS_A = opt("--msA", None)                   # arriving call length; default: same as the running call
LAN_CALLS = int(opt("--lan", "3"))                # calls per LAN burst
RAW = opt("--raw", None)                          # append every repetition as JSON lines here
EXTEND_REPS = 20      # singleThreaded repetitions a cell is extended to when its first results disagree
CONTROL_REPS = int(opt("--control-reps", "0")) or None   # control repetitions per cell; default: same as --reps
NO_PROVISION = "--no-provision" in args           # use the probes already on the hub as they are (for a second, parallel run)
RESUME = "--resume" in args                       # count the valid repetitions already in --raw for this hub and firmware
NET_WAIT = 600                                    # s to wait for the hub to answer again after a network drop
FULL = os.environ.get("RUN_SLOW_TESTS") == "1"
MAX_INVALID = 10
LEAD, GAP, MS_RUN, MS_ARRIVE = 600, 500, 2000, 100   # ms; the arriving call is issued GAP after the running one
VALID_MARGIN = 500     # ms the first call must still have left when the second is issued
RELEASE_MAX = 150      # ms after the first call ends within which a waiting call must start
RUNTIME_BUDGET = 7200 if FULL else 600

APP_KINDS = ["endpoint", "endpointLan", "scheduled", "callback", "devEvent", "locEvent", "childDev", "childApp", "button", "page", "updated"]
DRIVER_KINDS = ["cmdApp", "cmdHttp", "scheduled", "callback", "parse", "childDev"]

GREEN, RED, YELLOW, CYAN, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[36m", "\033[2m", "\033[0m"
passed = failed = warnings = 0
start_time = time.time()

def ok(msg):
    global passed; passed += 1; print(f"  {GREEN}[PASS]{RESET} {msg}")
def fail(msg):
    global failed; failed += 1; print(f"  {RED}[FAIL]{RESET} {msg}")
def warn(msg):
    global warnings; warnings += 1; print(f"  {YELLOW}[WARN]{RESET} {msg}")
def info(msg): print(f"  {DIM}[INFO] {msg}{RESET}")
def section(msg): print(f"\n{CYAN}--- {msg} ---{RESET}")
def die(msg, code=2):
    print(f"{RED}{msg}{RESET}"); sys.exit(code)

# ── Hub access ────────────────────────────────────────────────────────
try:
    with open(config_file) as f: config = json.load(f)
except (OSError, ValueError) as e:
    die(f"Cannot read {config_file}: {e}")
hub_name = HUB_NAME or config.get("default_hub", "")
hub_cfg = config.get("hubs", {}).get(hub_name)
if not hub_cfg:
    die(f"Hub '{hub_name}' not found in .hubitat.json")
hub_ip = hub_cfg["hub_ip"]
hub = f"http://{hub_ip}"

cookies = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_a, **_k): return None
no_redirect = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies), NoRedirect())

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

try:
    hub_data = request("GET", "/hub2/hubData") or {}
except Exception as e:
    die(f"Hub unreachable: {e}")
fw = hub_data.get("baseModel", {}).get("buildVersion", "unknown")
info(f"Hub: {hub_name}, firmware {fw}, {'full matrix' if FULL else 'diagonal only (RUN_SLOW_TESTS=1 for the full matrix)'}")

NAMESPACE = "tests"

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

def walk(entries, out):
    for e in entries:
        d = e.get("data") or {}
        if isinstance(d, dict): out.append(d)
        walk(e.get("children") or [], out)
    return out

def find_instance(label):
    apps = walk((request("GET", "/hub2/appsList") or {}).get("apps") or [], [])
    return next((str(a["id"]) for a in apps if a.get("name") == label), None)

def save_settings(iid, label):
    """Done on the main page: sets the label and runs installed()/updated()."""
    cfg = request("GET", f"/installedapp/configure/json/{iid}")
    fields = [("_action_update", "Done"), ("formAction", "update"), ("id", iid),
              ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""), ("appTypeName", ""),
              ("currentPage", "mainPage"), ("pageBreadcrumbs", "[]"), ("label.type", "text"), ("label", label),
              ("referrer", f"{hub}/installedapp/list"), ("url", f"{hub}/installedapp/configure/{iid}/mainPage"),
              ("_cancellable", "false")]
    r = request("POST", "/installedapp/update/json", form=fields)
    if not isinstance(r, dict) or r.get("status") != "success": raise RuntimeError(f"settings save rejected: {r}")

def ensure_instance(type_id, label):
    iid = find_instance(label)
    if iid is None:
        m = re.search(r"/installedapp/configure/(\d+)", redirect_location(f"/installedapp/create/{type_id}"))
        if not m: raise RuntimeError(f"could not create an instance of app type {type_id}")
        iid = m.group(1)
        save_settings(iid, label)   # the first save runs installed(), which drops the label
    save_settings(iid, label)
    return iid

def app_state(iid):
    return {s["name"]: s.get("value") for s in request("GET", f"/installedapp/statusJson/{iid}").get("appState", [])}

# ── Provision ─────────────────────────────────────────────────────────
section("Provision")

def read(path):
    with open(path) as f: return f.read()

P, P_ST = "Single Threaded Probe", "Single Threaded Probe ST"
CA, CA_ST = "Single Threaded Probe Child App", "Single Threaded Probe ST Child App"
D, D_ST = "Single Threaded Probe Driver", "Single Threaded Probe Driver ST"
TRIGGER = "Single Threaded Probe Trigger"

probe_src = read(f"{app_dir}/SingleThreadedProbe.groovy")
child_app_src = read(f"{app_dir}/SingleThreadedProbeChildApp.groovy")
driver_src = read(f"{driver_dir}/SingleThreadedProbeDriver.groovy")
sources = {
    ("driver", "Single Threaded Probe Child"): read(f"{driver_dir}/SingleThreadedProbeChild.groovy"),
    ("driver", D): driver_src,
    ("driver", D_ST): driver_src.replace(f'name: "{D}"', f'name: "{D_ST}"').replace("singleThreaded: false", "singleThreaded: true"),
    ("app", P): probe_src,
    ("app", P_ST): probe_src.replace(f'name: "{P}"', f'name: "{P_ST}"').replace(f'CHILD_APP = "{CA}"', f'CHILD_APP = "{CA_ST}"')
                            .replace("singleThreaded: false", "singleThreaded: true"),
    ("app", CA): child_app_src,
    ("app", CA_ST): child_app_src.replace(f'name: "{CA}"', f'name: "{CA_ST}"').replace(f'parent: "tests:{P}"', f'parent: "tests:{P_ST}"'),
    ("app", TRIGGER): read(f"{app_dir}/SingleThreadedProbeTrigger.groovy"),
}
for (kind, name), src in sources.items():
    if f'name: "{name}"' not in src: die(f"text substitution for {name} failed; check the probe's definition() layout")
try:
    if NO_PROVISION:
        listing = {(k, t["name"]): str(t["id"]) for k, ep in (("app", "userAppTypes"), ("driver", "userDeviceTypes"))
                   for t in request("GET", f"/hub2/{ep}") or [] if t.get("namespace") == NAMESPACE}
        type_ids = {name: listing[(kind, name)] for (kind, name) in sources}
        iids = {k: find_instance(label) for k, label in (("MT", "test-stp-mt"), ("ST", "test-stp-st"), ("TRIG", "test-stp-trigger"))}
        if not all(iids.values()): raise RuntimeError(f"probe instances missing: {iids}")
    else:
        type_ids = {name: put_code(kind, name, src) for (kind, name), src in sources.items()}
        iids = {"MT": ensure_instance(type_ids[P], "test-stp-mt"), "ST": ensure_instance(type_ids[P_ST], "test-stp-st"),
                "TRIG": ensure_instance(type_ids[TRIGGER], "test-stp-trigger")}
    pushed_at = time.time()
except Exception as e:
    die(f"Provisioning failed: {e}")
tokens = {k: app_state(iid).get("accessToken") for k, iid in iids.items()}
if not all(tokens.values()):
    die(f"probe instances have no access token: {tokens}")

class LostRep(Exception):
    """A call that must not be repeated failed in transit; the repetition is dropped."""

def call(iid, token, path, timeout=60, retry=True, **params):
    """Repeatable calls wait out a network drop for up to NET_WAIT s. /run is not
    repeatable (it would fire the pair twice), so its failure only drops that repetition."""
    q = urllib.parse.urlencode({"access_token": token, **params})
    give_up = time.time() + NET_WAIT
    while True:
        try:
            with urllib.request.urlopen(f"{hub}/apps/api/{iid}/{path}?{q}", timeout=timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            if not retry: raise LostRep(f"/{path}: {e}")
            if time.time() > give_up: raise
            info(f"/{path}: {e}; waiting for the hub")
            time.sleep(10)

def target(k, path, **p): return call(iids[k], tokens[k], path, **p)
def trig(path, **p): return call(iids["TRIG"], tokens["TRIG"], path, **p)

app_targets = {}
for k in ("MT", "ST"):
    i = target(k, "info")
    if not all(i.get(x) for x in ("childDevice", "childDevice2", "childApp", "childAppToken")):
        die(f"{k} target is missing a child device or its child app: {i}")
    app_targets[k] = {"domain": "app", "tid": iids[k], "tok": tokens[k], "childDev": i["childDevice"], "childDev2": i["childDevice2"],
                      "childApp": i["childApp"], "childAppTok": i["childAppToken"], "label": i["label"]}
setup = trig("setup", driverMT=D, driverST=D_ST)   # idempotent: creates the driver devices only if missing
# parse() is routed by the sender's IP, so the two copies are reached through
# different ingress: the control on the hub's LAN IP, singleThreaded on loopback.
driver_targets = {k: {"domain": "driver", "tid": setup[k]["id"], "dni": setup[k]["dni"], "childDev": setup[k]["childId"],
                      "childDev2": setup[k]["childId2"], "parseHost": hub_ip if k == "MT" else "127.0.0.1"} for k in ("MT", "ST")}
ok(f"probes provisioned: apps {iids['MT']}/{iids['ST']}, trigger {iids['TRIG']}, driver devices "
   f"{driver_targets['MT']['tid']}/{driver_targets['ST']['tid']}")
ST_TIDS = {iids["ST"], driver_targets["ST"]["tid"]}
SLOW_BASE = f"http://127.0.0.1:8080/apps/api/{iids['TRIG']}/slow?access_token={tokens['TRIG']}"

def raw(rec):
    if RAW:
        with open(RAW, "a") as f: f.write(json.dumps({"hub": hub_name, "fw": fw, "wall": time.time(),
                                                      "sincePush": round(time.time() - pushed_at), **rec}) + "\n")

# ── One running/arriving pair ─────────────────────────────────────────
rep_counter = [0]

def app_key(t): return "ST" if t["tid"] == iids["ST"] else "MT"

def reset(t):
    if t["domain"] == "app": target(app_key(t), "reset")
    else: trig("dspans", dni=t["dni"], reset="1")

def spans(t):
    if t["domain"] == "app": return target(app_key(t), "spans")["spans"]
    return trig("dspans", dni=t["dni"])["spans"]

def set_mode(t, mode):
    if t["domain"] == "app": target(app_key(t), "mode", m=mode, slowBase=SLOW_BASE)
    else: trig("dmode", dni=t["dni"], m=mode, slowBase=SLOW_BASE)

def one_rep(t, running, arriving, gap=GAP, ms_r=MS_RUN, ms_a=MS_ARRIVE, idle=0, extra=None):
    """One pair. Returns (outcome, detail), outcome one of:
    waited  the second call started within RELEASE_MAX ms after the first ended
    during  the second call started while the first was running
    late    the second call started more than RELEASE_MAX ms after the first ended (slow dispatch; not counted)
    invalid the pair didn't overlap as planned: arming ran past t0, the calls started in the opposite
            order from how they were issued, the second was issued before the first started (gap > 0)
            or with under VALID_MARGIN ms left, or a page/button/updated entry ran unarmed
    no data a span or issue time is missing"""
    if idle: time.sleep(idle)
    rep_counter[0] += 1
    rep = f"{int(start_time) % 100000}x{rep_counter[0]}"
    reset(t)
    params = {k: v for k, v in t.items() if v is not None}
    try:
        plan = trig("run", retry=False, rep=rep, running=running, arriving=arriving, lead=LEAD, gap=gap, msR=ms_r, msA=ms_a, **params)
    except LostRep as e:
        time.sleep((LEAD + gap + max(ms_r, ms_a) + ms_a) / 1000 + 2)   # let whatever did fire drain
        return "invalid", str(e)
    if plan.get("error"): return "no data", plan["error"]
    time.sleep((LEAD + gap + max(ms_r, ms_a) + ms_a) / 1000 + 0.3)
    rt, at = f"r{rep}", f"a{rep}"
    got, all_spans = {}, []
    deadline = time.time() + 30
    while time.time() < deadline:
        all_spans = spans(t)
        got = {s["tag"]: s for s in all_spans}
        if rt in got and at in got: break
        time.sleep(0.5)
    else:
        return "no data", f"spans seen: {sorted(got)}"
    issued = trig("issued", rep=rep)
    if rt not in issued or at not in issued: return "no data", f"issue times missing: {issued}"
    for tag in (rt, at): got[tag]["issued"] = issued[tag]
    by_start = sorted((got[rt], got[at]), key=lambda s: s["start"])
    by_issue = sorted((got[rt], got[at]), key=lambda s: s["issued"])
    first, second = by_start
    release = second["start"] - first["end"]
    reason = None
    if plan["armedBy"] > plan["t0"] - 50: reason = f"arming ran {plan['armedBy'] - plan['t0']} ms past t0"
    elif gap > 0 and by_issue[0]["tag"] != first["tag"]: reason = "calls started in the opposite order from their issue"
    elif gap > 0 and second["issued"] < first["start"]: reason = "second call issued before the first started"
    elif second["issued"] > first["end"] - VALID_MARGIN: reason = f"second call issued with {first['end'] - second['issued']} ms left"
    elif any("unarmed" in s["tag"] for s in all_spans): reason = "an entry ran unarmed"
    if reason: outcome, detail = "invalid", reason
    elif release < -10: outcome, detail = "during", f"started {second['start'] - first['start']} ms into the first"
    elif release <= RELEASE_MAX: outcome, detail = "waited", f"started {release} ms after release"
    else: outcome, detail = "late", f"started {release} ms after release"
    raw({"domain": t["domain"], "st": t["tid"] in ST_TIDS, "running": running, "arriving": arriving, "gap": gap,
         "msR": ms_r, "msA": ms_a, "idle": idle, "rep": rep, "outcome": outcome, "detail": detail,
         "plan": plan, "r": got[rt], "a": got[at], **(extra or {})})
    return outcome, detail

DONE = {}
if RESUME and RAW and os.path.exists(RAW):
    with open(RAW) as f:
        for line in f:
            r = json.loads(line)
            if r.get("hub") != hub_name or r.get("fw") != fw or r.get("outcome") not in ("waited", "during"): continue
            key = (r["domain"], r["running"], r["arriving"], r["gap"], r["msR"], r["msA"], r.get("idle", 0), r.get("mode"))
            DONE.setdefault(key, {"MT": [], "ST": []})["ST" if r["st"] else "MT"].append((r["outcome"], r["detail"]))
    info(f"--resume: {sum(len(v['MT']) + len(v['ST']) for v in DONE.values())} valid repetitions from {RAW}")

def cell(tg, running, arriving, reps, **kw):
    """Interleaves singleThreaded and control repetitions until each has `reps` valid ones."""
    key = (tg["MT"]["domain"], running, arriving, kw.get("gap", GAP), kw.get("ms_r", MS_RUN), kw.get("ms_a", MS_ARRIVE),
           kw.get("idle", 0), kw.get("mode"))
    prior = DONE.get(key, {"MT": [], "ST": []})
    want = {"ST": reps, "MT": CONTROL_REPS or reps}
    res = {"MT": prior["MT"][:want["MT"]], "ST": prior["ST"][:want["ST"]]}
    bad = {"MT": 0, "ST": 0}
    mode = kw.pop("mode", None)
    if mode: kw["extra"] = {"mode": mode}
    while True:
        if not any(len(res[k]) < want[k] for k in want):
            outcomes = {o for o, _ in res["ST"]}
            if len(outcomes) > 1 and want["ST"] < EXTEND_REPS:   # mixed: settle it with more
                want["ST"] = EXTEND_REPS
                if len(prior["ST"]) > len(res["ST"]): res["ST"] = prior["ST"][:EXTEND_REPS]
            else: break
        for k in ("ST", "MT"):
            if len(res[k]) >= want[k]: continue
            outcome, detail = one_rep(tg[k], running, arriving, **kw)
            if outcome == "no data": return None, f"{k}: {detail}"
            if outcome in ("invalid", "late"):
                bad[k] += 1
                if bad[k] > MAX_INVALID: return None, f"{k}: {bad[k]} invalid or late repetitions (last: {detail})"
                continue
            res[k].append((outcome, detail))
    return res, f"{bad['ST']}/{bad['MT']} discarded"

def judge(label, res, note):
    if res is None:
        fail(f"{label}: couldn't measure ({note})"); return None
    sw = sum(o == "waited" for o, _ in res["ST"]); cw = sum(o == "waited" for o, _ in res["MT"])
    n, m = len(res["ST"]), len(res["MT"])
    detail = f"singleThreaded waited {sw}/{n}, control {cw}/{m}, {note}"
    if cw:
        fail(f"{label}: the control waited too, so something other than the lock delays it ({detail})")
    elif sw == n:
        ok(f"{label}: serialized ({detail}" + (f"; a miss rate above {300 / n:.0f}% is ruled out at 95%)" if n >= 10 else ")"))
    elif sw == 0:
        warn(f"{label}: NOT serialized ({detail})")
    else:
        fail(f"{label}: inconsistent ({detail}; {[d for _, d in res['ST']]})")
    return sw, n, cw, m

targets = {"app": app_targets, "driver": driver_targets}

# ── Sweep: one pair over gap, hold mode, run length and idle time ─────
if SWEEP:
    sd, s_run, s_arr = SWEEP.split(":")
    tg = targets[sd]
    section(f"sweep {sd}: {s_arr} arriving during {s_run}")
    for mode in SWEEP_MODES:
        for k in ("MT", "ST"): set_mode(tg[k], mode)
        for ms_r in SWEEP_RUNS:
            ms_a = int(SWEEP_MS_A) if SWEEP_MS_A else ms_r
            for idle in SWEEP_IDLE:
                for gap in SWEEP_GAPS:
                    res, note = cell(tg, s_run, s_arr, REPS, gap=gap, ms_r=ms_r, ms_a=ms_a, idle=idle, mode=mode)
                    judge(f"mode={mode} run={ms_r}ms arrive={ms_a}ms idle={idle:g}s gap={gap}ms", res, note)
    for k in ("MT", "ST"): set_mode(tg[k], "pause")
    elapsed = int(time.time() - start_time)
    print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s, firmware {fw})")
    sys.exit(0 if failed == 0 else 1)

# ── Which entry points carry spans at all ─────────────────────────────
section("Entry points")
domains = [d for d in ("app", "driver") if ONLY in (None, d)]
kinds = {"app": APP_KINDS, "driver": DRIVER_KINDS}
for d in domains:
    usable = []
    for k in [k for k in kinds[d] if KINDS is None or k in KINDS]:
        outcome, detail = one_rep(targets[d]["MT"], k, k)
        if outcome == "no data": warn(f"{d} {k}: no span recorded ({detail}); left out of the matrix")
        else: usable.append(k)
    kinds[d] = usable
    info(f"{d} entry points measured: {', '.join(usable)}")

# ── Matrix ────────────────────────────────────────────────────────────
summary = {}
for d in domains:
    section(f"{d}: running call vs arriving call")
    pairs = [(r, a) for r in kinds[d] for a in kinds[d]] if FULL else [(k, k) for k in kinds[d]]
    for running, arriving in pairs:
        res, note = cell(targets[d], running, arriving, REPS)
        summary[(d, running, arriving)] = judge(f"{arriving} arriving during {running}", res, note)

# ── Endpoint bursts from the LAN ──────────────────────────────────────
if "app" in domains and (KINDS is None or "endpoint" in KINDS):
    section(f"app: bursts of {LAN_CALLS} concurrent endpoint calls from the LAN")
    def lan_burst(k):
        target(k, "reset")
        rep_counter[0] += 1
        tags = [f"lan{rep_counter[0]}-{i}" for i in range(LAN_CALLS)]
        sent = {}
        def go(g):
            sent[g] = time.time()
            target(k, "work", retry=False, tag=g, ms=MS_RUN)
        lost = []
        def safe(g):
            try: go(g)
            except LostRep as e: lost.append(str(e))
        ts = [threading.Thread(target=safe, args=(g,)) for g in tags]
        for t_ in ts: t_.start()
        for t_ in ts: t_.join()
        if lost:
            time.sleep(MS_RUN * LAN_CALLS / 1000 + 2)
            return "lost"
        got = {s["tag"]: s for s in target(k, "spans")["spans"]}
        if not all(g in got for g in tags): return None
        sp = sorted((got[g] for g in tags), key=lambda s: s["start"])
        overlaps = sum(1 for i, a in enumerate(sp) for b in sp[i + 1:] if b["start"] < a["end"] - 10)
        raw({"domain": "app", "st": k == "ST", "running": "endpointLanBurst", "calls": LAN_CALLS, "overlaps": overlaps,
             "sendSkewMs": round((max(sent.values()) - min(sent.values())) * 1000), "spans": sp})
        return overlaps
    res = {"ST": [], "MT": []}
    while min(len(res["ST"]), len(res["MT"])) < REPS:
        for k in ("ST", "MT"):
            if len(res[k]) >= REPS: continue
            o = lan_burst(k)
            if o != "lost": res[k].append(o)
    if None in res["ST"] + res["MT"]:
        fail(f"LAN bursts: some spans missing ({res})")
    else:
        sser = sum(o == 0 for o in res["ST"]); cser = sum(o == 0 for o in res["MT"])
        detail = f"singleThreaded fully serialized {sser}/{REPS} bursts (overlapping pairs per burst {res['ST']}), control {cser}/{REPS}"
        if cser: fail(f"LAN bursts: the control serialized too ({detail})")
        elif sser == REPS: ok(f"LAN bursts: serialized ({detail})")
        elif sser == 0: warn(f"LAN bursts: NOT serialized ({detail})")
        else: fail(f"LAN bursts: inconsistent ({detail})")

# ── Summary grid ──────────────────────────────────────────────────────
for d in domains:
    ks = kinds[d]
    if not FULL or not ks: continue
    section(f"{d} grid: singleThreaded waits (rows: running, columns: arriving)")
    w = max(len(k) for k in ks) + 1
    print(" " * w + " ".join(f"{k[:9]:>9}" for k in ks))
    for r in ks:
        cells = []
        for a in ks:
            s = summary.get((d, r, a))
            cells.append(f"{'—' if not s else f'{s[0]}/{s[1]}':>9}")
        print(f"{r:<{w}}" + " ".join(cells))

# ── Cleanup ───────────────────────────────────────────────────────────
section("Cleanup")
if KEEP:
    info("--keep: leaving the probes on the hub")
else:
    time.sleep(5)   # let queued singleThreaded work drain; a busy instance is slow to uninstall
    steps = [f"/installedapp/delete/{iid}" for iid in (iids["MT"], iids["ST"], iids["TRIG"])] \
        + [f"/app/edit/deleteJson/{type_ids[n]}" for n in (CA, CA_ST, P, P_ST, TRIGGER)] \
        + [f"/driver/editor/deleteJson/{type_ids[n]}" for n in (D, D_ST, "Single Threaded Probe Child")]
    left = []
    for step in steps:
        try: request("GET", step, expect="text", timeout=90)
        except Exception as e: left.append(f"{step} ({e})")
    if left: warn(f"cleanup incomplete, re-run or remove by hand: {left}")
    else: info("removed probe instances, child devices, app types and drivers")

elapsed = int(time.time() - start_time)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s, firmware {fw})")
if elapsed > RUNTIME_BUDGET:
    print(f"{YELLOW}NOTE: exceeded declared runtime budget of {RUNTIME_BUDGET}s{RESET}")
sys.exit(0 if failed == 0 else 1)
PYTHON_SCRIPT
