#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
#
# Code Manager API test (Mode 2). Every /api/* endpoint, structure + ground truth against /hub2/*.
# Idempotent; no code is written to the hub. The Drift section runs last: it brings facts up to date and fetches upstream.
#
# Usage:
#   bash apps/CodeManager/tests/test-code-manager-api.sh [@hubname] [instance-id]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="${HUBITAT_CONFIG:-$PROJECT_ROOT/.hubitat.json}"
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/cm-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT
HUB_NAME=""; INSTANCE_ID=""
for arg in "$@"; do
  if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; elif [[ "$arg" =~ ^[0-9]+$ ]]; then INSTANCE_ID="$arg"; fi
done
python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "$COOKIE_JAR" "${RUN_SLOW_TESTS:-}" "$SCRIPT_DIR" <<'PYTHON_SCRIPT'
import json, sys, re, time, os, hashlib, urllib.request, urllib.parse, http.cookiejar
hub_name_arg, instance_id_arg, config_file, cookie_jar_path, run_slow, script_dir = sys.argv[1:7]
passed = failed = warnings = 0
def ok(m):   global passed;   passed += 1;   print(f"  [PASS] {m}")
def fail(m): global failed;   failed += 1;   print(f"  [FAIL] {m}")
def warn(m): global warnings; warnings += 1; print(f"  [WARN] {m}")
def info(m): print(f"  [INFO] {m}")
def section(m): print(f"\n--- {m} ---")

with open(config_file) as f: config = json.load(f)
hub_name = hub_name_arg or config.get("default_hub", "")
hub = config["hubs"].get(hub_name)
if not hub: print(f"Hub '{hub_name}' not found in .hubitat.json"); sys.exit(2)
hub_ip = hub["hub_ip"]
cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
if hub.get("username"):
    data = urllib.parse.urlencode({"username": hub["username"], "password": hub.get("password",""), "submit": "Login"}).encode()
    opener.open(f"http://{hub_ip}/login", data=data, timeout=15)

def fetch(path, timeout=30):
    try: return json.loads(opener.open(f"http://{hub_ip}{path}", timeout=timeout).read().decode())
    except Exception as e: info(f"fetch {path} failed: {e}"); return None

# Instance self-discovery by app type name
instance_id = int(instance_id_arg) if instance_id_arg else None
if not instance_id:
    apps = fetch("/hub2/appsList") or {}
    def find(entries):
        for e in entries:
            d = e.get("data") or {}
            if d.get("type") == "Code Manager": return d.get("id")
            r = find(e.get("children") or []);
            if r: return r
    instance_id = find(apps.get("apps", []))
if not instance_id: print("Could not find a Code Manager instance. Is it installed?"); sys.exit(2)
cfg = fetch(f"/installedapp/configure/json/{instance_id}") or {}
access_token = None
for s in (cfg.get("configPage", {}) or {}).get("sections", []):
    for b in s.get("body", []):
        for field in ("description", "url"):
            m = re.search(r"access_token=([a-f0-9-]+)", str(b.get(field, "")))
            if m: access_token = m.group(1)
if not access_token: print("Could not find access_token; open the app and click Done once."); sys.exit(2)
api_base = f"http://{hub_ip}/apps/api/{instance_id}"
info(f"API base: {api_base}")

AUTH = lambda: {"Authorization": f"Bearer {access_token}"}   # header, not ?access_token= — keeps the token out of access logs (scheme is case-sensitive)

def api_get(ep, timeout=60):
    try: return json.loads(urllib.request.urlopen(urllib.request.Request(f"{api_base}/api/{ep}", headers=AUTH()), timeout=timeout).read().decode())
    except urllib.error.HTTPError as e: return {"_error": e.code}
    except Exception as e: return {"_error": str(e)}
def api_post(ep, params=None, timeout=60):
    q = dict(params or {})
    sep = "?" if q else ""
    try:
        req = urllib.request.Request(f"{api_base}/api/{ep}{sep}{urllib.parse.urlencode(q)}", data=b"", method="POST", headers=AUTH())
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e: return {"_error": e.code}
    except Exception as e: return {"_error": str(e)}
def poll_job(job_id, timeout_s=120):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        j = api_get(f"jobs/{job_id}")
        if j.get("status") in ("done", "error", "not-found"): return j
        time.sleep(2)
    return {"status": "timeout"}

# ── Task 1: scaffold endpoints ────────────────────────────────────────
section("Scaffold")
s = api_get("settings")
if "_error" in s: fail(f"settings: {s}")
else:
    ok("GET /api/settings responds")
    for k in ("txtEnable", "debugEnable", "labelBadge", "appVersion", "uiVersion", "driftMaxInflight"):
        (ok if k in s else fail)(f"settings has {k}")
    if s.get("appVersion") == s.get("uiVersion"): ok(f"app and UI versions in lockstep ({s.get('appVersion')})")
    else: warn(f"app v{s.get('appVersion')} vs UI v{s.get('uiVersion')}")
v = api_get("version/check")
(ok if ("currentVersion" in v or "error" in v) else fail)("GET /api/version/check shape")
st = api_get("stats")
(ok if st.get("version") else fail)("GET /api/stats has version")
j = api_get("jobs/does-not-exist")
(ok if j.get("status") == "not-found" else fail)("GET /api/jobs/{unknown id} -> status not-found")
html = None
try: html = urllib.request.urlopen(f"{api_base}/ui.html?access_token={access_token}", timeout=30).read().decode()
except Exception as e: fail(f"GET /ui.html: {e}")
if html:
    (ok if "${access_token}" not in html and access_token in html else fail)("ui.html substitutes access_token")
    (ok if f'const CODE_VERSION = "{s.get("uiVersion")}"' in html else fail)("ui.html CODE_VERSION matches settings.uiVersion")
    (ok if "function tbl(" in html else fail)("ui.html serves the real SPA (has function tbl()")
    (ok if "function makeLimiter(" in html else fail)("ui.html serves the real SPA (has function makeLimiter()")
# Lockstep by content (DESIGN.md §10): the UI file the hub holds is byte-identical to this checkout's, not only the same CODE_VERSION.
try:
    served = opener.open(f"http://{hub_ip}/local/code_manager_ui.html", timeout=30).read()
    with open(os.path.join(script_dir, "..", "code_manager_ui.html"), "rb") as f: local = f.read()
    hs, hl = hashlib.sha256(served).hexdigest(), hashlib.sha256(local).hexdigest()
    (ok if hs == hl else fail)(f"the hub's code_manager_ui.html matches this checkout (sha256 {hs[:12]} vs {hl[:12]})")
except Exception as e: fail(f"read /local/code_manager_ui.html: {e}")
try:
    urllib.request.urlopen(f"{api_base}/api/settings?access_token=bad", timeout=15); fail("bad query-param token accepted")
except urllib.error.HTTPError as e: (ok if e.code in (401, 403, 500) else fail)(f"bad query-param token rejected ({e.code})")
except Exception as e: warn(f"bad query-param token check: {e}")
try:
    urllib.request.urlopen(urllib.request.Request(f"{api_base}/api/settings", headers={"Authorization": "Bearer bad"}), timeout=15); fail("bad Bearer token accepted")
except urllib.error.HTTPError as e: (ok if e.code in (401, 403, 500) else fail)(f"bad Bearer token rejected ({e.code})")
except Exception as e: warn(f"bad Bearer token check: {e}")
# ── Task 2: shared bulk reads ─────────────────────────────────────────
section("Bulk reads")
import threading
def bulk_fetches(): return (api_get("stats").get("bulkFetches") or {})
b0 = bulk_fetches()
res = [None, None]
def _inv(i): res[i] = api_get("inventory", timeout=300)
ths = [threading.Thread(target=_inv, args=(i,)) for i in (0, 1)]
[t.start() for t in ths]; [t.join() for t in ths]
b1 = bulk_fetches()
delta = b1.get("apps", 0) - b0.get("apps", 0)
(ok if delta <= 1 else fail)(f"two concurrent inventory calls cost at most one apps read (delta {delta})")
inv2 = res[0] if "_error" not in (res[0] or {"_error": 1}) else res[1]
if not inv2 or "_error" in inv2: fail(f"inventory under concurrency: {inv2}")
else:
    (ok if isinstance(inv2.get("asOf"), int) else fail)("inventory has asOf (int ms)")
    libs_meta = inv2.get("libraries")
    (ok if isinstance(libs_meta, dict) and (libs_meta.get("asOf") is None or isinstance(libs_meta.get("asOf"), int)) else fail)("inventory has libraries.asOf (int or null)")
lib_asof0 = (api_get("inventory").get("libraries") or {}).get("asOf")
r = api_post("libraries/refresh")
(ok if r.get("jobId") else fail)(f"POST libraries/refresh returns a jobId ({r})")
jr = poll_job(r.get("jobId", ""), timeout_s=180)
(ok if jr.get("status") == "done" else fail)(f"library refresh job ends done ({jr.get('status')})")
lib_asof1 = (api_get("inventory").get("libraries") or {}).get("asOf")
(ok if lib_asof1 is not None and (lib_asof0 is None or lib_asof1 > lib_asof0) else fail)(f"libraries.asOf increased ({lib_asof0} -> {lib_asof1})")
# failure path: the last good list stays. The hook answers only while debug logging is on.
dbg_before = api_get("settings").get("debugEnable") is True
def set_debug(on): api_post("settings", {"data": json.dumps({"debugEnable": on})})
try:
    set_debug(True)
    before = {c["id"] for c in api_get("inventory").get("components", []) if c.get("kind") == "library"}
    sim = api_post("test/simulateLibFail", {"on": "1"})
    (ok if sim.get("simulateLibFail") is True else fail)(f"test/simulateLibFail on ({sim})")
    r2 = api_post("libraries/refresh")
    j2 = poll_job(r2.get("jobId", ""), timeout_s=120)
    (ok if j2.get("status") == "done" and (j2.get("result") or {}).get("error") else fail)(f"failed refresh ends done with result.error ({j2.get('status')}, {(j2.get('result') or {}).get('error')})")
    inv3 = api_get("inventory?force=1", timeout=300)
    after = {c["id"] for c in inv3.get("components", []) if c.get("kind") == "library"}
    (ok if before and before == after else fail)(f"inventory still lists every library after a failed read ({len(before)} -> {len(after)})")
    (ok if (inv3.get("libraries") or {}).get("asOf") == lib_asof1 else fail)("libraries.asOf unchanged by the failed read")
finally:
    api_post("test/simulateLibFail", {"on": "0"})
    set_debug(False)
off = api_post("test/simulateLibFail", {"on": "0"})
(ok if off.get("_error") == 404 else fail)(f"test hook answers 404 with debug logging off ({off})")
set_debug(dbg_before)
st2 = api_get("stats")
(ok if st2.get("perItemReadsInRequest") == 0 else fail)(f"perItemReadsInRequest is 0 ({st2.get('perItemReadsInRequest')})")
(ok if set(st2.get("bulkFetches", {})) >= {"apps", "drivers", "libraries"} else fail)("stats.bulkFetches has the per-key counters")

# ── Task 4: inventory ─────────────────────────────────────────────────
section("Inventory")
gt_libs = fetch("/hub2/userLibraries") or []
gt_bundles = fetch("/bundle/list/json") or []
# force=1 so this pass is fresh, then re-read app/driver ground truth immediately after it — the
# prior version read ground truth before an inventory that could be up to an hour old, so the
# usedByCount/by-kind comparisons below were comparing against two different points in time (I9).
inv = api_get("inventory?force=1", timeout=300)
gt_apps = fetch("/hub2/userAppTypes") or []
gt_drvs = fetch("/hub2/userDeviceTypes") or []
if "_error" in inv: fail(f"inventory: {inv}")
else:
    ok("GET /api/inventory responds")
    for k in ("generatedAt", "hub", "packages", "components", "summary", "hpm", "drift"):
        (ok if k in inv else fail)(f"inventory has {k}")
    comps = inv.get("components", [])
    by_kind = {}
    for c in comps: by_kind[c.get("kind")] = by_kind.get(c.get("kind"), 0) + 1
    # HPM can add app/driver/bundle components with no live hub match (optional never installed,
    # a dangling heID, or an unresolved bundle id — DESIGN.md 2.1); compare the *bound* subset
    # (non-null hubId) to the hub's live count for those three kinds. HPM never adds library
    # components, so library compares its raw total.
    bound_by_kind = {}
    for c in comps:
        if c.get("hubId"): bound_by_kind[c.get("kind")] = bound_by_kind.get(c.get("kind"), 0) + 1
    for kind, n in {"app": len(gt_apps), "driver": len(gt_drvs), "bundle": len(gt_bundles)}.items():
        (ok if bound_by_kind.get(kind, 0) == n else fail)(f"{kind} bound count {bound_by_kind.get(kind,0)} == hub {n}")
    if (inv.get("libraries") or {}).get("asOf") is not None:
        (ok if by_kind.get("library", 0) == len(gt_libs) else fail)(f"library count {by_kind.get('library',0)} == hub {len(gt_libs)}")
    else:
        warn("library list not read yet; library count not compared")
    required = {"key", "kind", "hubId", "namespace", "name", "origin", "status", "usedByCount", "lastModifiedMs", "ihash", "required", "basis", "factsReadAtMs", "childOf", "asOf"}
    bad = [c for c in comps if not required.issubset(c.keys())]
    (ok if not bad else fail)(f"every component carries {sorted(required)} ({len(bad)} missing)")
    # facts carry their basis: only valid or provisional facts are shown; an unusable fact never reads as a location or a hash
    BASES = {"valid", "provisional", "stale", "unread"}
    items = [c for c in comps if c["kind"] in ("app", "driver", "library") and c.get("hubId") is not None]
    badb = [c for c in items if c.get("basis") not in BASES]
    (ok if not badb else fail)(f"every installed app/driver/library has a basis ({len(badb)} without)")
    leakf = [c for c in items if c.get("basis") in ("stale", "unread") and (c.get("ihash") or (c.get("origin") != "hpm" and (c.get("location") or c.get("iver"))))]
    (ok if not leakf else fail)(f"stale or unread facts are not shown ({len(leakf)} shown)")
    unread_o = [c for c in comps if c.get("origin") == "unread"]
    (ok if all(c.get("basis") in ("stale", "unread") for c in unread_o) else fail)("origin unread only where the basis is stale or unread")
    wrongcur = [c for c in items if c.get("basis") in ("stale", "unread") and c.get("origin") != "hpm" and c["status"] == "current"]
    (ok if not wrongcur else fail)(f"an unread component never reads current ({len(wrongcur)} wrong)")
    fc = inv.get("facts") or {}
    (ok if fc.get("total") == len(items) and fc.get("valid", 0) + fc.get("stale", 0) + fc.get("unread", 0) == fc.get("total") else fail)(f"facts counts add up to the installed items ({fc})")
    for k in ("valid", "stale", "unread", "failing", "large"): (ok if k in fc else fail)(f"facts has {k}")
    (ok if isinstance(inv.get("asOf"), int) else fail)("inventory asOf is an int (ms)")
    (ok if all(c.get("asOf") == inv.get("asOf") for c in comps) else fail)("each component asOf equals the payload asOf")
    # childOf points at instances that exist (app instances in /hub2/appsList, parent devices in /hub2/devicesList)
    def _walk_ids(nodes, out):
        for n in nodes or []:
            out.add(str((n.get("data") or {}).get("id"))); _walk_ids(n.get("children"), out)
        return out
    gt_app_ids = _walk_ids((fetch("/hub2/appsList") or {}).get("apps"), set())
    gt_dev_ids = _walk_ids((fetch("/hub2/devicesList") or {}).get("devices"), set())
    links = [(c, l) for c in comps for l in (c.get("childOf") or [])]
    badl = [l for c, l in links if str(l.get("id")) not in (gt_app_ids if l.get("kind") == "app" else gt_dev_ids) or l.get("kind") not in ("app", "device")]
    (ok if not badl else fail)(f"every childOf link names an existing instance ({len(badl)} of {len(links)} wrong)")
    if not links: warn("no child instances on this hub to exercise childOf")
    # importUrl presence must match the hub for apps and drivers whose facts are usable (single/data is the only source of importUrl)
    usable_ids = {(c["kind"], str(c["hubId"])) for c in items if c.get("basis") in ("valid", "provisional")}
    sample_apps = [a for a in gt_apps if ("app", str(a["id"])) in usable_ids][:5]
    sample_drvs = [d for d in gt_drvs if ("driver", str(d["id"])) in usable_ids][:5]
    mism = 0
    for a in sample_apps:
        sd = (fetch(f"/app/list/single/data/{a['id']}") or [{}])[0]
        c = next((x for x in comps if x["kind"] == "app" and str(x["hubId"]) == str(a["id"])), None)
        if not c or (bool(sd.get("importUrl")) != bool(c.get("location"))): mism += 1
    for d in sample_drvs:
        sd = (fetch(f"/driver/list/single/data/{d['id']}") or [{}])[0]
        c = next((x for x in comps if x["kind"] == "driver" and str(x["hubId"]) == str(d["id"])), None)
        if not c or (bool(sd.get("importUrl")) != bool(c.get("location"))): mism += 1
    sample_n = len(sample_apps) + len(sample_drvs)
    (ok if mism == 0 else fail)(f"importUrl presence matches hub for {sample_n} sampled apps/drivers with usable facts")
    if sample_n == 0: warn("no component has usable facts yet (the fact reader has not run); importUrl presence not compared")
    # usage index equals usedBy from the bulk lists
    gt_used = {("app", str(a["id"])): len(a.get("usedBy") or []) for a in gt_apps}
    gt_used.update({("driver", str(d["id"])): len(d.get("usedBy") or []) for d in gt_drvs})
    wrong = [c for c in comps if c["kind"] in ("app","driver") and c["hubId"] and gt_used.get((c["kind"], str(c["hubId"]))) != c["usedByCount"]]
    (ok if not wrong else fail)(f"usedByCount matches hub for apps/drivers ({len(wrong)} wrong)")
    # every bundle member points at its bundle. Its origin is bundle, or hpm when an HPM package
    # installed that bundle and the members were folded into the package.
    members = {("app", str(i)) for b in gt_bundles for i in b.get("appTypeIds", [])} | {("driver", str(i)) for b in gt_bundles for i in b.get("deviceTypeIds", [])}
    wrongm = [c for c in comps if (c["kind"], str(c["hubId"])) in members and (c["origin"] not in ("bundle", "hpm") or not c.get("bundleOf"))]
    (ok if not wrongm else fail)(f"bundle members carry their bundle and a bundle/hpm origin ({len(wrongm)} wrong)")
    # one item, one package: the whole point of the package model
    owners = {}
    for c in comps:
        if c.get("hubId") is not None: owners.setdefault((c["kind"], str(c["hubId"])), set()).add(c["key"])
    dupes = {k: v for k, v in owners.items() if len(v) > 1}
    (ok if not dupes else fail)(f"no component belongs to two packages ({len(dupes)} do)")
    # an installed bundle is bound wherever it is declared — never reported missing while it exists
    bundle_urls = {b.get("sourceUrl"): str(b["id"]) for b in gt_bundles if b.get("sourceUrl")}
    unbound = [c for c in comps if c["kind"] == "bundle" and c.get("location") in bundle_urls and c.get("hubId") is None]
    (ok if not unbound else fail)(f"every declared bundle that is installed is bound to it ({len(unbound)} unbound)")
    # file components resolve against File Manager, and nothing else claims to
    fm_names = {f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])}
    badf = [c for c in comps if c["kind"] == "file" and ((c["hubId"] is not None) != (c["name"] in fm_names))]
    (ok if not badf else fail)(f"file components are bound exactly when the file exists ({len(badf)} wrong)")
    # status vocabulary is closed: the SPA has a label and a legend line for each of these
    VOCAB = {"current", "update-available", "changed-upstream", "modified-locally", "broken-source",
             "missing", "not-installed", "private", "unmanaged", "not-checked"}
    stray = sorted({c["status"] for c in comps} - VOCAB)
    (ok if not stray else fail)(f"component statuses stay in the documented vocabulary (stray: {stray})")
    PKG_VOCAB = VOCAB | {"incomplete"}
    strayp = sorted({p["status"] for p in inv.get("packages", [])} - PKG_VOCAB)
    (ok if not strayp else fail)(f"package statuses stay in the documented vocabulary (stray: {strayp})")
    # a component with no import URL is never judged against an upstream it does not have
    wrongu = [c for c in comps if c.get("origin") == "local" and c["status"] not in ("unmanaged", "private")]
    (ok if not wrongu else fail)(f"hub-local code reads as unmanaged, never as checked ({len(wrongu)} wrong)")
    # optional-and-absent is not an error, required-and-absent is
    wrongm2 = [c for c in comps if c.get("hubId") is None and c["status"] != ("missing" if c.get("required") else "not-installed")]
    (ok if not wrongm2 else fail)(f"absent components report missing or not-installed by their required flag ({len(wrongm2)} wrong)")
    # single-component packages carry their kind so the SPA can list them without a second lookup
    solo_nokind = [p for p in inv.get("packages", []) if p.get("componentCount") == 1 and not p.get("kind")]
    (ok if not solo_nokind else fail)(f"single-component packages carry their kind ({len(solo_nokind)} missing)")
    # no sources, no secrets in the payload
    leak = [c for c in comps if "source" in c or "oauthClientSecret" in json.dumps(c)]
    (ok if not leak else fail)("inventory payload carries no source text and no OAuth secrets")
    # idempotent: a forced second pass (force=1 bypasses the freshness cache, so this actually
    # re-reconciles the registry rather than re-serving a cached payload) does not change counts
    inv2 = api_get("inventory?force=1", timeout=300)
    (ok if len(inv2.get("components", [])) == len(comps) else fail)("second inventory call is stable")
    # reclassification regression guard: a url-origin app survives a forced second pass with its
    # classification and location intact (ghost/regroup logic must not misclassify it as local)
    url_apps = [c for c in comps if c.get("kind") == "app" and c.get("origin") == "url"]
    if not url_apps:
        warn("no url-origin app on this hub (facts unread) to exercise the reclassification regression guard")
    else:
        samp = url_apps[0]
        c2 = next((x for x in inv2.get("components", []) if x.get("kind") == "app" and str(x.get("hubId")) == str(samp.get("hubId"))), None)
        (ok if c2 and c2.get("origin") == "url" and c2.get("location") else fail)("sampled url-origin app keeps origin=url and a location after a forced second pass")
    # manifest documents exist for every package
    files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
    pkgs = inv.get("packages", [])
    # a package of unread items has no manifest yet (doc is null)
    docd = [p for p in pkgs if p.get("origin") != "unread"]
    missing_docs = [p for p in docd if p.get("doc") not in files]
    (ok if not missing_docs else fail)(f"manifest document present for every package with a manifest ({len(missing_docs)} missing of {len(docd)})")
    (ok if all(p.get("doc") is None for p in pkgs if p.get("origin") == "unread") else fail)("unread packages carry no document")
# ── Task 5: HPM import ────────────────────────────────────────────────
section("HPM import (observe)")
hpm_type = next((a for a in gt_apps if a.get("namespace") == "dcm.hpm"), None)
hs = api_get("hpm/status")
if "_error" in hs: fail(f"hpm/status: {hs}")
elif not hpm_type:
    (ok if hs.get("mode") == "absent" else fail)("hpm/status mode=absent when HPM is not installed")
    warn("HPM not installed on this hub; import assertions skipped")
else:
    inst = (hpm_type.get("usedBy") or [{}])[0].get("id")
    (ok if hs.get("mode") in ("observe", "absent") and str(hs.get("instanceId")) == str(inst) else fail)(f"hpm/status finds instance {inst} (mode={hs.get('mode')})")
    (ok if "disabled" in hs and "autoUpdateMode" in hs else fail)("hpm/status carries live disabled + autoUpdateMode")
    r = api_post("hpm/import", timeout=180)
    (ok if r.get("success") else fail)(f"POST /api/hpm/import: {r}")
    st = fetch(f"/installedapp/statusJson/{inst}") or {}
    manifests = next((e["value"] for e in st.get("appState", []) if e["name"] == "manifests"), {}) or {}
    (ok if r.get("packages") == len(manifests) else fail)(f"imported {r.get('packages')} packages == HPM manifests {len(manifests)}")
    inv = api_get("inventory", timeout=180)
    hpm_pkgs = [p for p in inv.get("packages", []) if p["origin"] == "hpm"]
    (ok if {p["key"] for p in hpm_pkgs} == set(manifests.keys()) else fail)("package keys equal HPM manifest URLs")
    live_ids = {("app", str(a["id"])) for a in gt_apps} | {("driver", str(d["id"])) for d in gt_drvs}
    exp_bound = sum(1 for m in manifests.values() for kind, arr in (("app","apps"),("driver","drivers")) for c in (m.get(arr) or []) if c.get("heID") and (kind, str(c["heID"])) in live_ids)
    exp_dangling = sum(1 for m in manifests.values() for kind, arr in (("app","apps"),("driver","drivers")) for c in (m.get(arr) or []) if c.get("heID") and (kind, str(c["heID"])) not in live_ids)
    comps = [c for c in inv.get("components", []) if c["origin"] == "hpm"]
    # manifest-declared apps/drivers only: members of a bundle the package installed are folded into
    # the package by the inventory pass and are not part of HPM's own component records.
    bound_ad = [c for c in comps if c["kind"] in ("app", "driver") and not c.get("bundleOf")]
    (ok if sum(1 for c in bound_ad if c["hubId"]) == exp_bound else fail)(f"bound HPM components {sum(1 for c in bound_ad if c['hubId'])} == {exp_bound}")
    (ok if r.get("dangling") == exp_dangling else fail)(f"dangling heIDs reported: {r.get('dangling')} == {exp_dangling}")
    (ok if all(c.get("managedBy") == "hpm" for c in comps) else fail)("HPM-origin components are managedBy=hpm in observe mode")
    r2 = api_post("hpm/import", timeout=180)
    (ok if r2.get("packages") == r.get("packages") else fail)("re-import is idempotent")
    # a forced inventory pass must not wipe file-kind HPM components' hubId (the registry reconcile only
    # rebinds app/driver/library from `seen`; file/bundle components must survive untouched)
    inv3 = api_get("inventory?force=1", timeout=300)
    hpm_files = [c for c in inv3.get("components", []) if c["origin"] == "hpm" and c["kind"] == "file"]
    if not hpm_files:
        warn("no HPM package has files on this hub; file-hubId-survives-forced-pass assertion skipped")
    else:
        (ok if all(c.get("hubId") for c in hpm_files) else fail)("HPM file components keep hubId after a forced inventory pass")
    files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
    (ok if "cm_hpm_import.json" in files else fail)("cm_hpm_import.json written")
# ── Task 7: item source + export ──────────────────────────────────────
section("Item source and export")
comps = (api_get("inventory", timeout=180)).get("components", [])
a = next((c for c in comps if c["kind"] == "app" and c.get("hubId")), None)
if a:
    srcr = api_get(f"item/app/{a['hubId']}/source", timeout=60)
    (ok if srcr.get("source") and srcr.get("lock") is not None else fail)(f"item source for app {a['hubId']}: keys {sorted(srcr.keys())[:6]}")
    if a.get("ihash"): (ok if srcr.get("ihash") == a.get("ihash") else fail)("item source ihash equals inventory ihash")
    else: warn("inventory ihash not read yet; item source ihash not compared")
    (ok if srcr.get("modifiedLocally") is False else warn)(f"modifiedLocally={srcr.get('modifiedLocally')} (expected False on an untouched hub)")
bad = api_get("item/app/999999999/source")
(ok if "error" in bad else fail)("unknown item -> error payload")
lib = next((c for c in comps if c["kind"] == "library"), None)
if lib:
    ls = api_get(f"item/library/{lib['hubId']}/source", timeout=60)
    (ok if ls.get("source", "").lstrip().startswith("library") else fail)("library source served from /library/ajax/code")
ex = api_post("export", timeout=120)
for k in ("exportedAt", "registry", "documents", "drift", "hpm"):
    (ok if k in ex else fail)(f"export has {k}")
(ok if all(d["doc"] is not None for d in ex.get("documents", [])) else fail)("every registered package has a readable document")
files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
(ok if "cm_registry_export.json" in files else fail)("cm_registry_export.json written")
# ── Task 4: background fact reader ────────────────────────────────────
section("Fact reader")
def facts_wait(timeout_s=600):
    t0 = time.time(); st = api_get("facts/status")
    while time.time() - t0 < timeout_s and st.get("status") == "running":
        time.sleep(5); st = api_get("facts/status")
    return st
inv = api_get("inventory", timeout=300)               # starts the reader when facts are missing; schedules only
fc0 = inv.get("facts", {})
pending0 = fc0.get("unread", 0) + fc0.get("stale", 0)
info(f"facts at start: {fc0}")
time.sleep(3)
st = api_get("facts/status")
if pending0 > 0:
    (ok if st.get("jobId") and st.get("status") in ("running", "done") else fail)(f"facts/status shows a reader job ({st.get('status')})")
    ts = api_get("stats")
    # interrupt and resume: re-initialising while the reader runs strands its job; the next start must not re-read valid items
    if st.get("status") == "running":
        api_post("reinit")
        lost = api_get(f"jobs/{st.get('jobId')}")
        (ok if lost.get("status") == "error" and "lost" in (lost.get("error") or "") else fail)(f"reinit while the reader runs leaves its job error/lost ({lost.get('status')}: {lost.get('error')})")
        inv = api_get("inventory", timeout=300)       # unread items remain, so this restarts the reader
        time.sleep(3)
        st = api_get("facts/status")
        mid = inv.get("facts", {})
        pend_mid = mid.get("unread", 0) + mid.get("stale", 0)
        (ok if st.get("jobId") != lost.get("id") and (st.get("total") or 0) <= max(pend_mid, 1) + 1 else fail)(f"restarted reader queues only unread items (total {st.get('total')} vs {pend_mid} unread)")
    st = facts_wait(600)
    (ok if st.get("status") == "done" else fail)(f"reader finished within 600 s ({st.get('status')}, processed {st.get('processed')}/{st.get('total')})")
else:
    info("every fact is already valid; the first-run and interrupt/resume checks need a fresh registry")
time.sleep(5)                                         # a provisional fact is re-read once by a following job
st = facts_wait(300)
inv = api_get("inventory", timeout=300)
fc = inv.get("facts", {})
(ok if fc.get("unread", 0) + fc.get("stale", 0) <= fc.get("failing", 0) + fc.get("large", 0) else fail)(f"nothing is left unread except failing or large items ({fc})")
read_comps = [c for c in inv.get("components", []) if c.get("basis") in ("valid", "provisional") and c.get("kind") in ("app", "driver", "library")]
(ok if read_comps and all(c.get("ihash") for c in read_comps) else fail)(f"every read component has ihash ({len(read_comps)} read)")
unread_c = [c for c in inv.get("components", []) if c.get("basis") in ("unread", "stale")]
(ok if all(c.get("origin") == "unread" or c.get("origin") in ("hpm", "bundle") for c in unread_c) else fail)("an unread or stale component is never classified url or local")
ts = api_get("stats")
(ok if ts.get("perItemReadsInRequest") == 0 else fail)(f"no per-item read happened inside an endpoint handler ({ts.get('perItemReadsInRequest')})")
one = next((c for c in inv.get("components", []) if c.get("kind") == "app" and c.get("hubId") and not c.get("isPrivate")), None)
if one:
    reads0 = ts.get("perItemReadsInRequest")
    r = api_post("facts/read", {"kind": "app", "id": one["hubId"]}, timeout=120)
    (ok if r.get("read") is True and r.get("basis") in ("valid", "provisional") and r.get("hash") else fail)(f"POST facts/read reads one item ({ {k: r.get(k) for k in ('read', 'basis', 'error')} })")
    (ok if api_get("stats").get("perItemReadsInRequest") == reads0 + 1 else fail)("the user-requested read counts as the one request read")
bad = api_post("facts/read", {"kind": "app", "id": "999999999"})
(ok if bad.get("error") else fail)("facts/read of an unknown item -> error payload")
# ── Task 5: drift checks on request ───────────────────────────────────
section("Drift")
def ck(c): return f"{c['kind']}:{c['hubId']}"
inv = api_get("inventory", timeout=300)
(ok if isinstance(inv.get("drift", {}), dict) else fail)("inventory.drift is a map")
reads_before = api_get("stats").get("perItemReadsInRequest")
bad = api_post("drift/check", {"key": "app:999999999"})
(ok if bad.get("error") else fail)("drift/check of an unknown item -> error payload")
bad = api_post("drift/check", {"key": "pkg:whatever"})
(ok if bad.get("error") else fail)("drift/check refuses a package key")
bad = api_post("drift/start", {"scope": "nonsense"})
(ok if bad.get("error") else fail)("drift/start with an unknown scope -> error payload")
bad = api_post("drift/start", {"scope": "package", "key": "no-such-package"})
(ok if bad.get("error") else fail)("drift/start of an unknown package -> error payload")
comps = inv.get("components", [])
one = next((c for c in comps if c.get("origin") == "url" and c.get("hubId") and c.get("location", "") and c["location"].startswith("https://")
            and c.get("kind") in ("app", "driver", "library") and not c.get("isPrivate") and c.get("basis") in ("valid", "provisional")), None)
if one:
    r = api_post("drift/check", {"key": ck(one)}, timeout=120)
    (ok if r.get("checked") is True and r.get("status") not in (None, "not-checked") else fail)(f"drift/check on a url-origin component -> {({k: r.get(k) for k in ('status', 'checked', 'outcome', 'error', 'httpStatus')})}")
    after = next((c for c in api_get("inventory", timeout=180).get("components", []) if ck(c) == ck(one)), {})
    (ok if after.get("status") == r.get("status") and after.get("checkedAtMs") else fail)(f"the inventory reports the checked status ({after.get('status')})")
    r2 = api_post("drift/check", {"key": ck(one)}, timeout=120)       # second check: sends If-None-Match, a 304 when upstream sent an ETag
    (ok if r2.get("checked") is True and not r2.get("error") and r2.get("status") == r.get("status") else fail)(f"a second drift/check answers without error and keeps its status ({({k: r2.get(k) for k in ('status', 'outcome', 'httpStatus', 'error')})})")
else:
    warn("no url-origin component with valid facts and an https location; single-item drift check skipped")
# a package-scoped job
pk = next((p for p in inv.get("packages", []) if p.get("origin") == "url"), None)
if pk:
    r = api_post("drift/start", {"scope": "package", "key": pk["key"]}, timeout=60)
    (ok if r.get("jobId") else fail)(f"POST drift/start package -> {r}")
    j = poll_job(r.get("jobId", ""), timeout_s=600)
    (ok if j.get("status") == "done" else fail)(f"package drift job terminal status: {j.get('status')} {j.get('error') or ''}")
else:
    warn("no url-origin package; package-scoped drift job skipped")
# everything
r = api_post("drift/start", {"scope": "all"}, timeout=60)
(ok if r.get("jobId") else fail)(f"POST drift/start all -> {r}")
j = poll_job(r.get("jobId", ""), timeout_s=900)
(ok if j.get("status") == "done" else fail)(f"drift job terminal status: {j.get('status')} {j.get('error') or ''}")
res = j.get("result") or {}
for k in ("checked", "skippedLarge", "failed"):
    (ok if isinstance(res.get(k), int) else fail)(f"drift result has an integer {k} ({res.get(k)})")
info(f"drift result: {res}")
inv = api_get("inventory", timeout=180)
comps = inv.get("components", [])
allowed = {"current", "update-available", "changed-upstream", "modified-locally", "broken-source", "not-checked", "unmanaged", "private", "missing", "not-installed"}
badst = [c for c in comps if c["status"] not in allowed]
(ok if not badst else fail)(f"every status in the allowed set, none unknown ({len(badst)} bad)")
(ok if not any(p["status"] == "unknown" for p in inv.get("packages", [])) else fail)("no package reads unknown")
url_comps = [c for c in comps if c["origin"] == "url" and c.get("hubId") is not None and c.get("location") and not c.get("isPrivate") and c.get("basis") in ("valid", "provisional")]
unchecked = [c for c in url_comps if not c.get("checkedAtMs") and not c.get("error")]
(ok if not unchecked else fail)(f"every url component with valid facts was checked ({len(unchecked)} unchecked of {len(url_comps)})")
hpm_pkgs = [p for p in inv.get("packages", []) if p.get("origin") == "hpm"]
unchecked_pkgs = [p for p in hpm_pkgs if p.get("checkedAtMs") is None]
(ok if not unchecked_pkgs else fail)(f"every hpm package was checked at the package row ({len(unchecked_pkgs)} unchecked of {len(hpm_pkgs)})")
local = [c for c in comps if c["origin"] == "local"]
(ok if all(c["status"] in ("unmanaged", "private") for c in local) else fail)("hub-local code is never drift-checked, so it stays unmanaged")
hpm_files = [c for c in comps if c["origin"] == "hpm" and c["kind"] == "file" and c.get("hubId")]
(ok if all(c["status"] == "not-checked" for c in hpm_files) else fail)("an HPM file never inherits its package's status")
(ok if (inv.get("drift") or {}).get("status") == "done" else fail)("inventory.drift status done after the job")
files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
(ok if "cm_drift.json" in files else fail)("cm_drift.json persisted")
reads_after = api_get("stats").get("perItemReadsInRequest")
(ok if reads_after - reads_before <= 1 else fail)(f"perItemReadsInRequest rose by at most 1 for the whole section ({reads_before} -> {reads_after})")

section("Remove check")
bad = api_post("remove-check/start", {"kind": "library", "id": "1"})
(ok if bad.get("error") else fail)("remove-check/start refuses a library")
bad = api_post("remove-check/start", {"kind": "driver", "id": "999999999"})
(ok if bad.get("error") else fail)("remove-check/start of an unknown driver -> error payload")
reads_before = api_get("stats").get("perItemReadsInRequest")
unused = next((c for c in api_get("inventory", timeout=180).get("components", [])
               if c.get("kind") == "driver" and c.get("hubId") is not None and (c.get("usedByCount") or 0) == 0), None)
if unused:
    r = api_post("remove-check/start", {"kind": "driver", "id": str(unused["hubId"])}, timeout=60)
    (ok if r.get("jobId") else fail)(f"POST remove-check/start on an unused driver -> {({k: r.get(k) for k in ('jobId', 'total', 'error')})}")
    j = poll_job(r.get("jobId", ""), timeout_s=600)
    (ok if j.get("status") == "done" else fail)(f"remove-check job terminal status: {j.get('status')} {j.get('error') or ''}")
    res = j.get("result") or {}
    (ok if res.get("verdict") in ("no-creator", "may-be-created", "incomplete") else fail)(f"verdict in the closed set ({res.get('verdict')})")
    (ok if isinstance(res.get("creators"), list) and isinstance(res.get("unread"), list) else fail)("result lists creators and unread items")
    (ok if (res.get("verdict") == "may-be-created") == bool(res.get("creators")) else fail)("may-be-created exactly when a creator was found")
    info(f"remove check of '{unused.get('name')}': {res.get('verdict')}, {len(res.get('creators') or [])} creators, {len(res.get('unread') or [])} unread")
else:
    warn("no unused driver type; remove-check job skipped")
reads_after = api_get("stats").get("perItemReadsInRequest")
(ok if reads_after == reads_before else fail)(f"the remove check reads nothing inside a request ({reads_before} -> {reads_after})")
# TASK-SECTIONS-BELOW

print(f"\n{passed} passed, {failed} failed, {warnings} warnings")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
