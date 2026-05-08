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

FILE=$(echo "$STATUS" | jq -r '.filename')
echo "  → report: $FILE"

echo
echo "=== GET /api/audit/list ==="
LIST=$(curl -sf "${BASE}/audit/list?access_token=${ACCESS_TOKEN}")
echo "$LIST" | jq .
COUNT=$(echo "$LIST" | jq '.reports | length')
[[ "$COUNT" -gt 0 ]] || { echo "FAIL: no reports listed"; exit 1; }

echo
echo "=== GET the rendered HTML ==="
curl -sfo /tmp/audit-test.html "${HUB}/local/${FILE}"
[[ $(wc -c < /tmp/audit-test.html) -gt 1000 ]] || { echo "FAIL: HTML too small"; exit 1; }
echo "  HTML size: $(wc -c < /tmp/audit-test.html) bytes — OK"

echo
echo "=== POST /api/audit/delete ==="
DEL=$(curl -sf -X POST "${BASE}/audit/delete?access_token=${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"filename\":\"${FILE}\"}")
echo "$DEL" | jq .
[[ $(echo "$DEL" | jq -r '.deleted') == "true" ]] || { echo "FAIL: delete returned false"; exit 1; }

echo
echo "ALL TESTS PASSED"
