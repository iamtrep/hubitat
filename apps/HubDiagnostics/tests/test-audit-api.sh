#!/usr/bin/env bash
# Smoke test for the four Device Usage Audit endpoints on maison-pro.
# Prereqs: jq installed.
# Usage:   ACCESS_TOKEN=<token> ./test-audit-api.sh
#          (Optional overrides: HUB=http://hostname  APP_ID=<installed-app-id>)

set -euo pipefail
HUB="${HUB:-http://192.168.1.86}"
APP_ID="${APP_ID:-247}"
: "${ACCESS_TOKEN:?ACCESS_TOKEN env var required (find it in the dashboard URL once)}"
BASE="${HUB}/apps/api/${APP_ID}/api"

echo "=== POST /api/audit/start ==="
START=$(curl -sf -X POST "${BASE}/audit/start?access_token=${ACCESS_TOKEN}")
echo "$START" | jq .
SCAN_ID=$(echo "$START" | jq -r '.scanId')
TOTAL=$(echo "$START" | jq -r '.total')
[[ -n "$SCAN_ID" && "$SCAN_ID" != "null" ]] || { echo "FAIL: no scanId"; exit 1; }
[[ "$TOTAL" -gt 0 ]] || { echo "FAIL: total=$TOTAL"; exit 1; }

echo
echo "=== Polling /api/audit/status until done ==="
for i in $(seq 1 60); do
    STATUS=$(curl -sf "${BASE}/audit/status?scanId=${SCAN_ID}&access_token=${ACCESS_TOKEN}")
    PROC=$(echo "$STATUS" | jq -r '.processed')
    ST=$(echo "$STATUS"   | jq -r '.status')
    echo "  [$i] processed=$PROC/$TOTAL  status=$ST"
    [[ "$ST" == "done" || "$ST" == "error" ]] && break
    sleep 2
done
[[ "$ST" == "done" ]] || { echo "FAIL: final status=$ST"; exit 1; }

echo
echo "=== GET /api/audit/data ==="
curl -sfo /tmp/audit-test.json "${BASE}/audit/data?access_token=${ACCESS_TOKEN}"
[[ $(wc -c < /tmp/audit-test.json) -gt 1000 ]] || { echo "FAIL: JSON too small"; exit 1; }
jq -e '.deviceCount and .unreferenced and .allDevices' /tmp/audit-test.json > /dev/null \
    || { echo "FAIL: missing expected JSON keys"; exit 1; }
echo "  JSON size: $(wc -c < /tmp/audit-test.json) bytes — OK"

echo
echo "ALL TESTS PASSED"
