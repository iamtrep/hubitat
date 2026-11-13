#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-stuck-sensor-reset — Mode 1 behavior test
#
# Generated from apps/utilities/tests/spec-stuck-sensor-reset.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/utilities/tests/test-stuck-sensor-reset.sh                             # default hub
#   bash apps/utilities/tests/test-stuck-sensor-reset.sh @hubname                    # specific hub
#   bash apps/utilities/tests/test-stuck-sensor-reset.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~60s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
#

set -euo pipefail

# Walk up to the repo root: the directory that holds the harness.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
while [[ "$ROOT" != "/" && ! -f "$ROOT/scripts/lib/behavior_harness.py" ]]; do
    ROOT="$(dirname "$ROOT")"
done
if [[ ! -f "$ROOT/scripts/lib/behavior_harness.py" ]]; then
    echo "Could not find scripts/lib/behavior_harness.py above ${BASH_SOURCE[0]}" >&2
    exit 2
fi

PYTHONPATH="$ROOT/scripts/lib${PYTHONPATH:+:$PYTHONPATH}" python3 - "$0" "$ROOT" "$@" <<'PYTHON_SCRIPT'
import sys
from behavior_harness import run

# ── Spec data (rendered from the spec) ───────────────────
APP_TYPE_NAME         = "Stuck Sensor Reset"
APP_INSTANCE_LABEL    = "test-stuck-sensor-reset"
MAKER_API_LABEL       = "test-ssr-maker"
INPUT_DEVICE_LABELS   = ["test-ssr-motion", "test-ssr-contact", "test-ssr-offline", "test-ssr-trigger"]
OUTPUT_DEVICE_LABELS  = ["test-ssr-notify"]
CASES                 = [{'name': 'sensors still reporting are left alone', 'setup': [{'settings': {'staleHours': 1}}, {'device': 'test-ssr-offline', 'command': 'setHealthStatus', 'args': ['online']}, {'device': 'test-ssr-motion', 'command': 'active'}, {'device': 'test-ssr-contact', 'command': 'open'}, {'device': 'test-ssr-offline', 'command': 'open'}], 'actions': [{'button': 'btnReset'}], 'wait_seconds': 2, 'assert': [{'device': 'test-ssr-motion', 'attribute': 'motion', 'value': 'active'}, {'device': 'test-ssr-contact', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ssr-offline', 'attribute': 'contact', 'value': 'open'}], 'assert_logs': [{'pattern': 'reset 0 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'lastMessage', 'source': 'test-ssr-notify', 'negate': True}]}, {'name': 'trigger switch resets stuck sensors and turns itself off', 'setup': [{'settings': {'staleHours': 0}}, {'device': 'test-ssr-trigger', 'command': 'off'}, {'device': 'test-ssr-motion', 'command': 'active'}, {'device': 'test-ssr-contact', 'command': 'open'}, {'device': 'test-ssr-offline', 'command': 'close'}], 'actions': [{'device': 'test-ssr-trigger', 'command': 'on'}], 'wait_seconds': 4, 'assert': [{'device': 'test-ssr-motion', 'attribute': 'motion', 'value': 'inactive'}, {'device': 'test-ssr-contact', 'attribute': 'contact', 'value': 'closed'}, {'device': 'test-ssr-trigger', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'reset 2 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'motion', 'value': 'inactive', 'source': 'test-ssr-motion', 'pattern': 'reset to inactive by'}, {'attribute': 'contact', 'value': 'closed', 'source': 'test-ssr-contact', 'pattern': 'reset to closed by'}, {'attribute': 'lastMessage', 'source': 'test-ssr-notify'}]}, {'name': 'button resets stuck sensors', 'setup': [{'settings': {'staleHours': 0}}, {'device': 'test-ssr-motion', 'command': 'active'}, {'device': 'test-ssr-contact', 'command': 'open'}, {'device': 'test-ssr-offline', 'command': 'close'}], 'actions': [{'button': 'btnReset'}], 'wait_seconds': 2, 'assert': [{'device': 'test-ssr-motion', 'attribute': 'motion', 'value': 'inactive'}, {'device': 'test-ssr-contact', 'attribute': 'contact', 'value': 'closed'}], 'assert_logs': [{'pattern': 'reset 2 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'lastMessage', 'source': 'test-ssr-notify'}]}, {'name': 'nothing stuck sends no notification', 'setup': [{'settings': {'staleHours': 0}}, {'device': 'test-ssr-motion', 'command': 'inactive'}, {'device': 'test-ssr-contact', 'command': 'close'}, {'device': 'test-ssr-offline', 'command': 'close'}], 'actions': [{'button': 'btnReset'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'reset 0 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'lastMessage', 'source': 'test-ssr-notify', 'negate': True}]}, {'name': 'second reset right after the first finds nothing', 'setup': [{'settings': {'staleHours': 0}}, {'device': 'test-ssr-motion', 'command': 'active'}, {'device': 'test-ssr-contact', 'command': 'open'}, {'device': 'test-ssr-offline', 'command': 'close'}, {'button': 'btnReset'}], 'actions': [{'button': 'btnReset'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'reset 0 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'lastMessage', 'source': 'test-ssr-notify', 'negate': True}]}, {'name': 'offline sensor is reset even when recently heard from', 'setup': [{'settings': {'staleHours': 1}}, {'device': 'test-ssr-motion', 'command': 'inactive'}, {'device': 'test-ssr-contact', 'command': 'close'}, {'device': 'test-ssr-offline', 'command': 'open'}, {'device': 'test-ssr-offline', 'command': 'setHealthStatus', 'args': ['offline']}], 'actions': [{'button': 'btnReset'}], 'wait_seconds': 2, 'assert': [{'device': 'test-ssr-offline', 'attribute': 'contact', 'value': 'closed'}], 'assert_logs': [{'pattern': 'reset 1 sensor\\(s\\)', 'level': 'info'}], 'assert_events': [{'attribute': 'contact', 'value': 'closed', 'source': 'test-ssr-offline', 'pattern': 'reset to closed by'}, {'attribute': 'lastMessage', 'source': 'test-ssr-notify'}]}]
RUNTIME_BUDGET_SECONDS = 60
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
