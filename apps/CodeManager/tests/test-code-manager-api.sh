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
            if d.get("appTypeName") == "Code Manager" or d.get("name") == "Code Manager": return d.get("id")
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
    exp = {"app": len(gt_apps), "driver": len(gt_drvs), "library": len(gt_libs), "bundle": len(gt_bundles)}
    for kind, n in exp.items():
        (ok if by_kind.get(kind, 0) == n else fail)(f"{kind} count {by_kind.get(kind,0)} == hub {n}")
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
    wrong = [c for c in comps if c["kind"] in ("app","driver") and gt_used.get((c["kind"], str(c["hubId"]))) != c["usedByCount"]]
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
# TASK-SECTIONS-BELOW

print(f"\n{passed} passed, {failed} failed, {warnings} warnings")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
