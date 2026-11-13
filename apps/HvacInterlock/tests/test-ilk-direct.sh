#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-ilk-direct — Mode 1 behavior test
#
# Generated from apps/HvacInterlock/tests/spec-interlock-direct.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/HvacInterlock/tests/test-ilk-direct.sh                             # default hub
#   bash apps/HvacInterlock/tests/test-ilk-direct.sh @hubname                    # specific hub
#   bash apps/HvacInterlock/tests/test-ilk-direct.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~150s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "HVAC Interlock Group"
APP_INSTANCE_LABEL    = "test-ilk-direct"
MAKER_API_LABEL       = "test-ilk-maker"
INPUT_DEVICE_LABELS   = ["test-ilk-th", "test-ilk-window2"]
OUTPUT_DEVICE_LABELS  = ["test-ilk-direct status", "test-season Season"]
CASES                 = [{'name': 'spring-heats', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block', 'testQuietSeconds': 100000}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['spring', 0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-ilk-direct status', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'summer-cools', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['spring', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'cool'}]}, {'name': 'winter-turns-off', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-ilk-direct status', 'attribute': 'blockReason', 'value': 'season'}]}, {'name': 'open-window-turns-off', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'actions': [{'device': 'test-ilk-window2', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-ilk-direct status', 'attribute': 'blockReason', 'value': 'openings'}]}, {'name': 'closing-restores-mode', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3}}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}, {'device': 'test-ilk-window2', 'command': 'open'}], 'actions': [{'device': 'test-ilk-window2', 'command': 'close'}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'cool'}]}, {'name': 'no-reassert-when-unchanged', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'warn'}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}, {'device': 'test-ilk-th', 'command': 'heat'}], 'actions': [{'device': 'test-ilk-window2', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-ilk-direct status', 'attribute': 'contact', 'value': 'open'}]}, {'name': 'restart-corrects-mode', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block'}}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}, {'device': 'test-ilk-th', 'command': 'heat'}], 'actions': [{'button': 'btnRestart'}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'cool'}]}, {'name': 'quiet-open-window-stops-blocking', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block', 'testQuietSeconds': 15}}, {'button': 'btnForgetGaps'}, {'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'command_spacing_seconds': 20, 'actions': [{'device': 'test-ilk-window2', 'command': 'open'}, {'button': 'btnCheck'}], 'wait_seconds': 3, 'allow_log_patterns': ['has not reported for'], 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'cool'}, {'device': 'test-ilk-direct status', 'attribute': 'blockReason', 'value': 'none'}, {'device': 'test-ilk-direct status', 'attribute': 'openContacts', 'value': 'none'}]}, {'name': 'quiet-window-counts-again', 'command_spacing_seconds': 2, 'actions': [{'device': 'test-ilk-window2', 'command': 'close'}, {'device': 'test-ilk-window2', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-ilk-direct status', 'attribute': 'blockReason', 'value': 'openings'}, {'device': 'test-ilk-direct status', 'attribute': 'openContacts', 'value': 'test-ilk-window2'}]}, {'name': 'quiet-limit-off-again', 'setup': [{'settings': {'testQuietSeconds': 100000}}], 'actions': [{'device': 'test-ilk-window2', 'command': 'close'}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'cool'}]}]
RUNTIME_BUDGET_SECONDS = 210
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
