#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-tsp-retry — Mode 1 behavior test
#
# Generated from apps/HvacScheduler/tests/spec-tsp-retry.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/HvacScheduler/tests/test-tsp-retry.sh                             # default hub
#   bash apps/HvacScheduler/tests/test-tsp-retry.sh @hubname                    # specific hub
#   bash apps/HvacScheduler/tests/test-tsp-retry.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~160s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "HVAC Scheduler Program"
APP_INSTANCE_LABEL    = "test-tsp-retry"
MAKER_API_LABEL       = "test-tsp-maker"
INPUT_DEVICE_LABELS   = ["test-tsp-stubborn", "test-tsp-normal"]
OUTPUT_DEVICE_LABELS  = ["test-tsp-retry scheduler"]
CASES                 = [{'name': 'all-confirmed', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-tsp-retry scheduler', 'command': 'resume'}, {'device': 'test-tsp-retry scheduler', 'command': 'setEco', 'args': ['off']}, {'device': 'test-tsp-stubborn', 'command': 'setIgnore', 'args': [0]}, {'device': 'test-tsp-stubborn', 'command': 'heat'}, {'device': 'test-tsp-normal', 'command': 'heat'}, {'mode': 'Day'}, {'settings': {'testConfigJson': '{"v":1,"profiles":[{"name":"Home","heat":21.0,"cool":24.0},{"name":"Out","heat":19.0,"cool":27.0},{"name":"Sleep","heat":18.0,"cool":26.0},{"name":"Away","heat":16.0,"cool":29.0}],"schedules":[{"name":"Normal","type":"time","groups":[{"name":"All","days":[1,2,3,4,5,6,7],"periods":[{"name":"Wake","start":{"kind":"time","at":"06:30"},"profile":"Home"},{"name":"Leave","start":{"kind":"time","at":"08:15"},"profile":"Out"},{"name":"Night","start":{"kind":"time","at":"22:00"},"profile":"Sleep"}]}]},{"name":"Vacation","type":"time","groups":[{"name":"All","days":[1,2,3,4,5,6,7],"periods":[{"name":"Day","start":{"kind":"time","at":"00:00"},"profile":"Away"}]}]}],"active":"Normal","overrides":[{"modeId":4,"profile":"Away"}]}'}}, {'button': 'btnLoadConfig'}, {'settings': {'testClock': '2026-10-05 09:00'}}], 'actions': [{'button': 'btnEvaluate'}], 'wait_seconds': 33, 'assert': [{'device': 'test-tsp-stubborn', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp-normal', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp-retry scheduler', 'attribute': 'lastApply', 'value': 'ok'}]}, {'name': 'ignored-write-is-reported-not-resent', 'allow_warnings': True, 'setup': [{'device': 'test-tsp-stubborn', 'command': 'setIgnore', 'args': [1]}], 'actions': [{'settings': {'testClock': '2026-10-05 22:00'}}, {'button': 'btnEvaluate'}], 'wait_seconds': 33, 'assert': [{'device': 'test-tsp-stubborn', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp-stubborn', 'attribute': 'ignored', 'value': 1, 'tolerance': 0.1}, {'device': 'test-tsp-normal', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}, {'device': 'test-tsp-retry scheduler', 'attribute': 'lastApply', 'value': 'partial'}]}, {'name': 'second-batch-keeps-first-batch-check', 'allow_warnings': True, 'setup': [{'device': 'test-tsp-normal', 'command': 'setCoolingSetpoint', 'args': [30]}, {'device': 'test-tsp-stubborn', 'command': 'setIgnore', 'args': [1]}], 'actions': [{'settings': {'testClock': '2026-10-06 06:30'}}, {'device': 'test-tsp-normal', 'command': 'cool'}], 'wait_seconds': 33, 'assert': [{'device': 'test-tsp-stubborn', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp-normal', 'attribute': 'coolingSetpoint', 'value': 24.0, 'tolerance': 0.05}, {'device': 'test-tsp-retry scheduler', 'attribute': 'lastApply', 'value': 'partial'}]}]
RUNTIME_BUDGET_SECONDS = 160
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
