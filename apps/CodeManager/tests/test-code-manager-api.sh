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
# TASK-SECTIONS-BELOW

total = passed + failed
print(f"\n=== Results: {passed}/{total} passed, {failed} failed, {warnings} warnings ===")
sys.exit(1 if failed else 0)
PYTHON_SCRIPT
