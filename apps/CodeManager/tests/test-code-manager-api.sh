#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
#
# Code Manager API test (Mode 2). Every /api/* endpoint, structure + ground truth against /hub2/*.
# Idempotent; no production mutation (sweep start is gated by RUN_SLOW_TESTS=1). Upper bound ~60 s.
#
# Usage:
#   bash apps/CodeManager/tests/test-code-manager-api.sh [@hubname] [instance-id]
#   RUN_SLOW_TESTS=1 bash apps/CodeManager/tests/test-code-manager-api.sh   # also runs a sweep
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/cm-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT
HUB_NAME=""; INSTANCE_ID=""
for arg in "$@"; do
  if [[ "$arg" == @* ]]; then HUB_NAME="${arg#@}"; elif [[ "$arg" =~ ^[0-9]+$ ]]; then INSTANCE_ID="$arg"; fi
done
python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "$COOKIE_JAR" "${RUN_SLOW_TESTS:-}" <<'PYTHON_SCRIPT'
import json, sys, re, time, urllib.request, urllib.parse, http.cookiejar
hub_name_arg, instance_id_arg, config_file, cookie_jar_path, run_slow = sys.argv[1:6]
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

def api_get(ep, timeout=60):
    sep = "&" if "?" in ep else "?"
    try: return json.loads(urllib.request.urlopen(f"{api_base}/api/{ep}{sep}access_token={access_token}", timeout=timeout).read().decode())
    except urllib.error.HTTPError as e: return {"_error": e.code}
    except Exception as e: return {"_error": str(e)}
def api_post(ep, params=None, timeout=60):
    q = dict(params or {}); q["access_token"] = access_token
    try:
        req = urllib.request.Request(f"{api_base}/api/{ep}?{urllib.parse.urlencode(q)}", data=b"", method="POST")
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e: return {"_error": e.code}
    except Exception as e: return {"_error": str(e)}
def poll_job(job_id, timeout_s=120):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        j = api_get(f"jobs/{job_id}")
        if j.get("status") in ("done", "error", "unknown"): return j
        time.sleep(2)
    return {"status": "timeout"}

# ── Task 1: scaffold endpoints ────────────────────────────────────────
section("Scaffold")
s = api_get("settings")
if "_error" in s: fail(f"settings: {s}")
else:
    ok("GET /api/settings responds")
    for k in ("txtEnable", "debugEnable", "labelBadge", "appVersion", "uiVersion", "sweepTtlHours"):
        (ok if k in s else fail)(f"settings has {k}")
    if s.get("appVersion") == s.get("uiVersion"): ok(f"app and UI versions in lockstep ({s.get('appVersion')})")
    else: warn(f"app v{s.get('appVersion')} vs UI v{s.get('uiVersion')}")
v = api_get("version/check")
(ok if ("currentVersion" in v or "error" in v) else fail)("GET /api/version/check shape")
st = api_get("stats")
(ok if st.get("version") else fail)("GET /api/stats has version")
j = api_get("jobs/does-not-exist")
(ok if j.get("status") == "unknown" else fail)("GET /api/jobs/{unknown} -> status unknown")
html = None
try: html = urllib.request.urlopen(f"{api_base}/ui.html?access_token={access_token}", timeout=30).read().decode()
except Exception as e: fail(f"GET /ui.html: {e}")
if html:
    (ok if "${access_token}" not in html and access_token in html else fail)("ui.html substitutes access_token")
    (ok if f'const CODE_VERSION = "{s.get("uiVersion")}"' in html else fail)("ui.html CODE_VERSION matches settings.uiVersion")
    (ok if "function tbl(" in html else fail)("ui.html serves the real SPA (has function tbl()")
    (ok if "function makeLimiter(" in html else fail)("ui.html serves the real SPA (has function makeLimiter()")
try:
    urllib.request.urlopen(f"{api_base}/api/settings?access_token=bad", timeout=15); fail("bad token accepted")
except urllib.error.HTTPError as e: (ok if e.code in (401, 403, 500) else fail)(f"bad token rejected ({e.code})")
except Exception as e: warn(f"bad token check: {e}")
# ── Task 4: inventory ─────────────────────────────────────────────────
section("Inventory")
gt_apps = fetch("/hub2/userAppTypes") or []
gt_drvs = fetch("/hub2/userDeviceTypes") or []
gt_libs = fetch("/hub2/userLibraries") or []
gt_bundles = fetch("/bundle/list/json") or []
inv = api_get("inventory", timeout=180)
if "_error" in inv: fail(f"inventory: {inv}")
else:
    ok("GET /api/inventory responds")
    for k in ("generatedAt", "hub", "packages", "components", "summary", "hpm", "sweep"):
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
    (ok if by_kind.get("library", 0) == len(gt_libs) else fail)(f"library count {by_kind.get('library',0)} == hub {len(gt_libs)}")
    required = {"key", "kind", "hubId", "namespace", "name", "origin", "status", "usedByCount", "lastModifiedMs", "ihash"}
    bad = [c for c in comps if not required.issubset(c.keys())]
    (ok if not bad else fail)(f"every component carries {sorted(required)} ({len(bad)} missing)")
    # importUrl presence must match the hub for apps (single/data is the only source of importUrl)
    sample = [a for a in gt_apps][:5]
    mism = 0
    for a in sample:
        sd = (fetch(f"/app/list/single/data/{a['id']}") or [{}])[0]
        c = next((x for x in comps if x["kind"] == "app" and str(x["hubId"]) == str(a["id"])), None)
        if not c or (bool(sd.get("importUrl")) != bool(c.get("location"))): mism += 1
    (ok if mism == 0 else fail)(f"importUrl presence matches hub for {len(sample)} sampled apps")
    # usage index equals usedBy from the bulk lists
    gt_used = {("app", str(a["id"])): len(a.get("usedBy") or []) for a in gt_apps}
    gt_used.update({("driver", str(d["id"])): len(d.get("usedBy") or []) for d in gt_drvs})
    wrong = [c for c in comps if c["kind"] in ("app","driver") and c["hubId"] and gt_used.get((c["kind"], str(c["hubId"]))) != c["usedByCount"]]
    (ok if not wrong else fail)(f"usedByCount matches hub for apps/drivers ({len(wrong)} wrong)")
    # every bundle member is classified as bundle-member and points at its bundle
    members = {("app", str(i)) for b in gt_bundles for i in b.get("appTypeIds", [])} | {("driver", str(i)) for b in gt_bundles for i in b.get("deviceTypeIds", [])}
    wrongm = [c for c in comps if (c["kind"], str(c["hubId"])) in members and c["origin"] != "bundle"]
    (ok if not wrongm else fail)(f"bundle members carry origin=bundle ({len(wrongm)} wrong)")
    # no sources, no secrets in the payload
    leak = [c for c in comps if "source" in c or "oauthClientSecret" in json.dumps(c)]
    (ok if not leak else fail)("inventory payload carries no source text and no OAuth secrets")
    # idempotent: a forced second pass (force=1 bypasses the freshness cache, so this actually
    # re-runs runInventoryPass rather than re-serving the cached registry) does not change counts
    inv2 = api_get("inventory?force=1", timeout=300)
    (ok if len(inv2.get("components", [])) == len(comps) else fail)("second inventory call is stable")
    # reclassification regression guard: a url-origin app survives a forced second pass with its
    # classification and location intact (ghost/regroup logic must not misclassify it as local)
    url_apps = [c for c in comps if c.get("kind") == "app" and c.get("origin") == "url"]
    if not url_apps:
        warn("no url-origin app on this hub to exercise the reclassification regression guard")
    else:
        samp = url_apps[0]
        c2 = next((x for x in inv2.get("components", []) if x.get("kind") == "app" and str(x.get("hubId")) == str(samp.get("hubId"))), None)
        (ok if c2 and c2.get("origin") == "url" and c2.get("location") else fail)("sampled url-origin app keeps origin=url and a location after a forced second pass")
    # manifest documents exist for every package
    files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
    pkgs = inv.get("packages", [])
    missing_docs = [p for p in pkgs if p.get("doc") not in files]
    (ok if not missing_docs else fail)(f"manifest document present for every package ({len(missing_docs)} missing of {len(pkgs)})")
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
    bound_ad = [c for c in comps if c["kind"] in ("app", "driver")]  # files carry a hubId (filename) unconditionally, DESIGN.md 2.1
    (ok if sum(1 for c in bound_ad if c["hubId"]) == exp_bound else fail)(f"bound HPM components {sum(1 for c in bound_ad if c['hubId'])} == {exp_bound}")
    (ok if r.get("dangling") == exp_dangling else fail)(f"dangling heIDs reported: {r.get('dangling')} == {exp_dangling}")
    (ok if all(c.get("managedBy") == "hpm" for c in comps) else fail)("HPM-origin components are managedBy=hpm in observe mode")
    r2 = api_post("hpm/import", timeout=180)
    (ok if r2.get("packages") == r.get("packages") else fail)("re-import is idempotent")
    # a forced inventory pass must not wipe file-kind HPM components' hubId (runInventoryPass only
    # rebinds app/driver/library from `seen`; file/bundle components must survive untouched)
    inv3 = api_get("inventory?force=1", timeout=300)
    hpm_files = [c for c in inv3.get("components", []) if c["origin"] == "hpm" and c["kind"] == "file"]
    if not hpm_files:
        warn("no HPM package has files on this hub; file-hubId-survives-forced-pass assertion skipped")
    else:
        (ok if all(c.get("hubId") for c in hpm_files) else fail)("HPM file components keep hubId after a forced inventory pass")
    files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
    (ok if "cm_hpm_import.json" in files else fail)("cm_hpm_import.json written")
# ── Task 6: sweep ─────────────────────────────────────────────────────
section("Sweep")
sw = inv.get("sweep", {}) if isinstance(inv, dict) else {}
(ok if isinstance(sw, dict) else fail)("inventory.sweep is a map")
if run_slow:
    r = api_post("sweep/start", timeout=60)
    (ok if r.get("jobId") else fail)(f"POST /api/sweep/start -> {r}")
    j = poll_job(r.get("jobId", ""), timeout_s=600)
    (ok if j.get("status") == "done" else fail)(f"sweep job terminal status: {j.get('status')} {j.get('error') or ''}")
    res = j.get("result") or {}
    for k in ("queued", "checked", "notModified", "changed", "broken", "unreachable", "updates"):
        (ok if k in res else fail)(f"sweep result has {k}")
    inv = api_get("inventory", timeout=180)
    comps = inv.get("components", [])
    allowed = {"current", "update-available", "changed-upstream", "modified-locally", "broken-source", "unknown", "private", "missing"}
    badst = [c for c in comps if c["status"] not in allowed]
    (ok if not badst else fail)(f"every status in the allowed set ({len(badst)} bad)")
    url_comps = [c for c in comps if c["origin"] == "url" and c.get("hubId") is not None and c.get("location") and not c.get("isPrivate")]
    unchecked = [c for c in url_comps if not c.get("checkedAtMs") and not c.get("error")]
    (ok if not unchecked else fail)(f"every url component was checked ({len(unchecked)} unchecked of {len(url_comps)})")
    hpm_pkgs = [p for p in inv.get("packages", []) if p.get("origin") == "hpm"]
    unchecked_pkgs = [p for p in hpm_pkgs if p.get("checkedAtMs") is None]
    (ok if not unchecked_pkgs else fail)(f"every hpm package was checked at the package row ({len(unchecked_pkgs)} unchecked of {len(hpm_pkgs)})")
    local = [c for c in comps if c["origin"] == "local"]
    (ok if all(c["status"] in ("unknown", "missing") for c in local) else fail)("local-origin components stay unknown")
    r2 = api_post("sweep/start", timeout=60)
    j2 = poll_job(r2.get("jobId", ""), timeout_s=300)
    (ok if (j2.get("result") or {}).get("queued", 1) == 0 else fail)("second sweep inside the TTL queues nothing")
    st = api_get("inventory", timeout=60).get("sweep", {})
    (ok if st.get("status") == "done" else fail)("state.sweep status done after the job")
    files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
    (ok if "cm_sweep.json" in files else fail)("cm_sweep.json persisted")
else:
    warn("RUN_SLOW_TESTS not set; sweep execution skipped")
# ── Task 7: item source + export ──────────────────────────────────────
section("Item source and export")
comps = (api_get("inventory", timeout=180)).get("components", [])
a = next((c for c in comps if c["kind"] == "app" and c.get("hubId")), None)
if a:
    srcr = api_get(f"item/app/{a['hubId']}/source", timeout=60)
    (ok if srcr.get("source") and srcr.get("lock") is not None else fail)(f"item source for app {a['hubId']}: keys {sorted(srcr.keys())[:6]}")
    (ok if srcr.get("ihash") == a.get("ihash") else fail)("item source ihash equals inventory ihash")
    (ok if srcr.get("modifiedLocally") is False else warn)(f"modifiedLocally={srcr.get('modifiedLocally')} (expected False on an untouched hub)")
bad = api_get("item/app/999999999/source")
(ok if "error" in bad else fail)("unknown item -> error payload")
lib = next((c for c in comps if c["kind"] == "library"), None)
if lib:
    ls = api_get(f"item/library/{lib['hubId']}/source", timeout=60)
    (ok if ls.get("source", "").lstrip().startswith("library") else fail)("library source served from /library/ajax/code")
ex = api_get("export", timeout=120)
for k in ("exportedAt", "registry", "documents", "sweep", "hpm"):
    (ok if k in ex else fail)(f"export has {k}")
(ok if len(ex.get("documents", [])) == len(ex.get("registry", {})) else fail)("one document per registry package in the export")
files = [f["name"] for f in (fetch("/hub/fileManager/json") or {}).get("files", [])]
(ok if "cm_registry_export.json" in files else fail)("cm_registry_export.json written")
# TASK-SECTIONS-BELOW

print(f"\n{passed} passed, {failed} failed, {warnings} warnings")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
