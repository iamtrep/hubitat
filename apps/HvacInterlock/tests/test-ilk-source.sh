#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-ilk-source — Mode 1 behavior test
#
# Generated from apps/HvacInterlock/tests/spec-interlock-source.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/HvacInterlock/tests/test-ilk-source.sh                             # default hub
#   bash apps/HvacInterlock/tests/test-ilk-source.sh @hubname                    # specific hub
#   bash apps/HvacInterlock/tests/test-ilk-source.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~90s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_INSTANCE_LABEL    = "test-ilk-source"
MAKER_API_LABEL       = "test-ilk-maker"
INPUT_DEVICE_LABELS   = ["test-ilk-src-th", "test-ilk-src-lead", "test-ilk-src-block"]
OUTPUT_DEVICE_LABELS  = ["test-ilk-source status"]
CASES                 = [{'name': 'lead-off-heats', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-src-block', 'command': 'off'}, {'device': 'test-ilk-src-lead', 'command': 'heat'}], 'actions': [{'device': 'test-ilk-src-lead', 'command': 'off'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-src-th', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-ilk-source status', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-ilk-source status', 'attribute': 'source', 'value': 'ok'}]}, {'name': 'lead-heat-turns-off', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-src-block', 'command': 'off'}, {'device': 'test-ilk-src-lead', 'command': 'off'}], 'actions': [{'device': 'test-ilk-src-lead', 'command': 'heat'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-src-th', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-ilk-source status', 'attribute': 'blockReason', 'value': 'source'}]}, {'name': 'blank-mapping-changes-nothing', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-src-block', 'command': 'off'}, {'device': 'test-ilk-src-lead', 'command': 'off'}], 'actions': [{'device': 'test-ilk-src-lead', 'command': 'cool'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-src-th', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-ilk-source status', 'attribute': 'source', 'value': 'ok'}]}, {'name': 'block-switch-turns-off', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-src-block', 'command': 'off'}, {'device': 'test-ilk-src-lead', 'command': 'off'}], 'actions': [{'device': 'test-ilk-src-block', 'command': 'on'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-src-th', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-ilk-source status', 'attribute': 'blockReason', 'value': 'switches'}]}, {'name': 'block-clear-restores', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-src-lead', 'command': 'off'}, {'device': 'test-ilk-src-block', 'command': 'on'}], 'actions': [{'device': 'test-ilk-src-block', 'command': 'off'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-src-th', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-ilk-source status', 'attribute': 'blockReason', 'value': 'none'}]}]
RUNTIME_BUDGET_SECONDS = 90
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
