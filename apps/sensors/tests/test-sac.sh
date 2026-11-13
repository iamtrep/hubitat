#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-sac-app — Mode 1 behavior test
#
# Generated from apps/sensors/tests/spec-sac.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/sensors/tests/test-sac.sh                             # default hub
#   bash apps/sensors/tests/test-sac.sh @hubname                    # specific hub
#   bash apps/sensors/tests/test-sac.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Sensor Aggregator Child"
APP_INSTANCE_LABEL    = "test-sac-app"
MAKER_API_LABEL       = "test-sac-maker"
INPUT_DEVICE_LABELS   = ["test-sac-in-1", "test-sac-in-2", "test-sac-in-3"]
OUTPUT_DEVICE_LABELS  = ["test-sac-out"]
CASES                 = [{'name': 'all-equal-baseline', 'setup': [{'settings': {'aggregationMethod': 'average'}}, {'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [25.0]}, {'device': 'test-sac-in-2', 'command': 'setTemperature', 'args': [25.0]}, {'device': 'test-sac-in-3', 'command': 'setTemperature', 'args': [25.0]}, {'await': {'device': 'test-sac-out', 'attribute': 'temperature', 'value': '25.0', 'timeout_seconds': 10}}], 'actions': [{'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [20.0]}, {'device': 'test-sac-in-2', 'command': 'setTemperature', 'args': [20.0]}, {'device': 'test-sac-in-3', 'command': 'setTemperature', 'args': [20.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 20.0, 'tolerance': 0.05}], 'assert_events': [{'attribute': 'temperature', 'value': '20.0', 'source': 'test-sac-out'}]}, {'name': 'single-input-raised', 'actions': [{'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [30.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 23.333, 'tolerance': 0.1}]}, {'name': 'idempotent-reset', 'actions': [{'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [20.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 20.0, 'tolerance': 0.05}]}, {'name': 'method-median', 'setup': [{'settings': {'aggregationMethod': 'median'}}, {'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [10.0]}, {'device': 'test-sac-in-3', 'command': 'setTemperature', 'args': [60.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 20.0, 'tolerance': 0.05}]}, {'name': 'method-min', 'setup': [{'settings': {'aggregationMethod': 'min'}}], 'actions': [{'device': 'test-sac-in-1', 'command': 'setTemperature', 'args': [11.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 11.0, 'tolerance': 0.05}]}, {'name': 'method-max', 'setup': [{'settings': {'aggregationMethod': 'max'}}], 'actions': [{'device': 'test-sac-in-3', 'command': 'setTemperature', 'args': [61.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 61.0, 'tolerance': 0.05}]}, {'name': 'method-average-restored', 'setup': [{'settings': {'aggregationMethod': 'average'}}], 'actions': [{'device': 'test-sac-in-3', 'command': 'setTemperature', 'args': [29.0]}], 'wait_seconds': 2, 'assert': [{'device': 'test-sac-out', 'attribute': 'temperature', 'value': 20.0, 'tolerance': 0.05}]}]
RUNTIME_BUDGET_SECONDS = 60
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
