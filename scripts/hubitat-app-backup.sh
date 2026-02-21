#!/usr/bin/env bash
#
# hubitat-app-backup.sh — Back up installed Hubitat app configurations and state
#
# Fetches the full JSON for selected installed apps via:
#   /installedapp/statusJson/{id}  — app state, all settings, subscriptions, scheduled jobs
#   /installedapp/configure/json/{id} — page inputs, UI config, mode map
#
# Selection modes (pick one):
#   --all                   Back up every installed app
#   --id <id>[,<id>...]     Back up specific app IDs (comma-separated)
#   --type <name>           Back up all instances of an app type (substring match)
#   --user-only             Back up only user-installed apps (skip built-in)
#   --list                  List all installed apps and exit (no backup)
#   --list-types            List distinct app types and exit
#
# Options:
#   --hub <host>            Hub address (default: from .hubitat.json or 192.168.1.86)
#   --dir <path>            Output directory (default: ./backups/apps)
#   --no-timestamp          Don't append timestamp to filenames
#   --dry-run               Show what would be backed up without fetching
#   --quiet                 Suppress progress output
#   -h, --help              Show this help
#
# Examples:
#   hubitat-app-backup.sh --list
#   hubitat-app-backup.sh --list-types
#   hubitat-app-backup.sh --all
#   hubitat-app-backup.sh --id 73,211
#   hubitat-app-backup.sh --type "Rule Machine"
#   hubitat-app-backup.sh --type "Room Lighting" --dir /tmp/hubitat-backups
#   hubitat-app-backup.sh --user-only
#

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────
HUB=""
BACKUP_DIR="./backups/apps"
MODE=""
SELECTED_IDS=""
TYPE_FILTER=""
USE_TIMESTAMP=true
DRY_RUN=false
QUIET=false

# ── Parse arguments ──────────────────────────────────────────────────
usage() {
    sed -n '/^#/!q; s/^# \{0,1\}//p' "$0" | tail -n +2
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)        MODE="all"; shift ;;
        --id)         MODE="id"; SELECTED_IDS="$2"; shift 2 ;;
        --type)       MODE="type"; TYPE_FILTER="$2"; shift 2 ;;
        --user-only)  MODE="user"; shift ;;
        --list)       MODE="list"; shift ;;
        --list-types) MODE="list-types"; shift ;;
        --hub)        HUB="$2"; shift 2 ;;
        --dir)        BACKUP_DIR="$2"; shift 2 ;;
        --no-timestamp) USE_TIMESTAMP=false; shift ;;
        --dry-run)    DRY_RUN=true; shift ;;
        --quiet)      QUIET=true; shift ;;
        -h|--help)    usage ;;
        *)            echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Resolve hub address ─────────────────────────────────────────────
if [[ -z "$HUB" ]]; then
    for candidate in "$(dirname "$0")/../.hubitat.json" "./.hubitat.json"; do
        if [[ -f "$candidate" ]]; then
            HUB=$(python3 -c "import json; print(json.load(open('$candidate'))['hub'])" 2>/dev/null || true)
            [[ -n "$HUB" ]] && break
        fi
    done
    HUB="${HUB:-192.168.1.86}"
fi

BASE="http://${HUB}"

log() { $QUIET || printf '%s\n' "$*" >&2; }

# ── Fetch apps list ─────────────────────────────────────────────────
log "Fetching apps list from ${HUB}..."
APPS_JSON=$(curl -sf "${BASE}/hub2/appsList") || {
    echo "Error: failed to fetch apps list from ${BASE}" >&2
    exit 1
}

# ── Build flat app table ─────────────────────────────────────────────
# Python outputs: id|appTypeId|name|type|user|parent|child
APP_TABLE=$(printf '%s' "$APPS_JSON" | python3 -c "
import json, sys

data = json.load(sys.stdin)
apps = data.get('apps', [])

def flatten(app_list):
    for app in app_list:
        d = app['data']
        # Use tab-safe pipe delimiter; names may contain special chars
        print(f\"{d['id']}|{d['appTypeId']}|{d['name']}|{d['type']}|{d.get('user', False)}|{app.get('parent', False)}|{app.get('child', False)}\")
        if app.get('children'):
            flatten(app['children'])

flatten(apps)
")

# ── List mode ────────────────────────────────────────────────────────
if [[ "$MODE" == "list" ]]; then
    printf "%-6s %-6s %-5s %-40s %s\n" "ID" "TypeID" "User" "Type" "Name"
    printf "%-6s %-6s %-5s %-40s %s\n" "------" "------" "-----" "$(printf '%0.s-' {1..40})" "----"
    echo "$APP_TABLE" | while IFS='|' read -r id typeId name type user parent child; do
        indent=""
        [[ "$child" == "True" ]] && indent="  "
        printf "%-6s %-6s %-5s %-40s %s%s\n" "$id" "$typeId" "$user" "$type" "$indent" "$name"
    done
    exit 0
fi

if [[ "$MODE" == "list-types" ]]; then
    printf "%-40s %-6s %s\n" "App Type" "Count" "User"
    printf "%-40s %-6s %s\n" "$(printf '%0.s-' {1..40})" "------" "----"
    echo "$APP_TABLE" | python3 -c "
import sys
from collections import Counter

types = Counter()
user_flag = {}
for line in sys.stdin:
    parts = line.strip().split('|')
    t = parts[3]
    types[t] += 1
    user_flag[t] = parts[4]

for t, count in sorted(types.items()):
    print(f'{t:<40s} {count:<6d} {user_flag[t]}')
"
    exit 0
fi

# ── Require a mode ───────────────────────────────────────────────────
if [[ -z "$MODE" ]]; then
    echo "Error: specify a selection mode (--all, --id, --type, --user-only, --list, --list-types)" >&2
    echo "Run with --help for usage." >&2
    exit 1
fi

# ── Select app IDs to back up ────────────────────────────────────────
BACKUP_IDS=$(echo "$APP_TABLE" | python3 -c "
import sys

mode = sys.argv[1]
type_filter = sys.argv[2].lower()
selected_ids = set(sys.argv[3].split(',')) if sys.argv[3] else set()

for line in sys.stdin:
    parts = line.strip().split('|')
    app_id, type_id, name, app_type, user, parent, child = parts

    if mode == 'all':
        print(app_id)
    elif mode == 'id':
        if app_id in selected_ids:
            print(app_id)
    elif mode == 'type':
        if type_filter in app_type.lower():
            print(app_id)
    elif mode == 'user':
        if user == 'True':
            print(app_id)
" "$MODE" "$TYPE_FILTER" "$SELECTED_IDS")

if [[ -z "$BACKUP_IDS" ]]; then
    echo "No apps matched the selection criteria." >&2
    exit 1
fi

COUNT=$(echo "$BACKUP_IDS" | wc -l | tr -d ' ')
log "Selected ${COUNT} app(s) for backup."

# ── Dry run ──────────────────────────────────────────────────────────
if $DRY_RUN; then
    echo "Dry run — would back up these apps:"
    for id in $BACKUP_IDS; do
        info=$(echo "$APP_TABLE" | grep "^${id}|" | head -1)
        name=$(echo "$info" | cut -d'|' -f3)
        type=$(echo "$info" | cut -d'|' -f4)
        echo "  ID ${id}: ${name} (${type})"
    done
    exit 0
fi

# ── Create backup directory ──────────────────────────────────────────
mkdir -p "$BACKUP_DIR"

TIMESTAMP=""
if $USE_TIMESTAMP; then
    TIMESTAMP="_$(date +%Y%m%d_%H%M%S)"
fi

# ── Back up each app ─────────────────────────────────────────────────
SUCCESS=0
FAIL=0

for id in $BACKUP_IDS; do
    info=$(echo "$APP_TABLE" | grep "^${id}|" | head -1)
    name=$(echo "$info" | cut -d'|' -f3)
    type=$(echo "$info" | cut -d'|' -f4)

    # Sanitize name for filename
    safe_name=$(echo "${name}" | sed 's/<[^>]*>//g' | tr ' /' '_-' | tr -cd '[:alnum:]_-')
    filename="app_${id}_${safe_name}${TIMESTAMP}.json"
    filepath="${BACKUP_DIR}/${filename}"

    log "  Backing up ID ${id}: ${name} (${type})..."

    # Primary: statusJson has complete state, settings, subscriptions, scheduled jobs
    status_json=$(curl -sf "${BASE}/installedapp/statusJson/${id}" 2>/dev/null || true)

    # Secondary: configure/json has page inputs, UI structure, mode map
    config_json=$(curl -sf "${BASE}/installedapp/configure/json/${id}" 2>/dev/null || true)

    # Validate we got at least statusJson
    if [[ -z "$status_json" ]] || echo "$status_json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    if 'installedApp' not in d: sys.exit(1)
except: sys.exit(1)
" 2>/dev/null; status=$?; [[ $status -ne 0 ]]; then
        echo "  WARN: failed to fetch statusJson for app ${id} (${name})" >&2
        FAIL=$((FAIL + 1))
        continue
    fi

    # Assemble backup envelope via Python (avoids shell quoting issues)
    python3 -c "
import json, sys
from datetime import datetime

status = json.loads(sys.argv[1])

backup = {
    '_backup': {
        'timestamp': datetime.now().isoformat(),
        'hub': sys.argv[2],
        'installedAppId': int(sys.argv[3]),
        'appName': sys.argv[4],
        'appType': sys.argv[5],
    },
    'statusJson': status,
}

config_raw = sys.argv[6]
if config_raw:
    try:
        backup['configureJson'] = json.loads(config_raw)
    except json.JSONDecodeError:
        pass

print(json.dumps(backup, indent=2))
" "$status_json" "$HUB" "$id" "$name" "$type" "${config_json:-}" > "$filepath"

    log "    -> ${filepath}"
    SUCCESS=$((SUCCESS + 1))
done

log ""
log "Backup complete: ${SUCCESS} succeeded, ${FAIL} failed."
log "Output directory: $(cd "$BACKUP_DIR" && pwd)"
