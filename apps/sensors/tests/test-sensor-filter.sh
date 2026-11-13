#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-filter-app — Mode 1 behavior test
#
# Generated from apps/sensors/tests/spec-sensor-filter.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/sensors/tests/test-sensor-filter.sh                             # default hub
#   bash apps/sensors/tests/test-sensor-filter.sh @hubname                    # specific hub
#   bash apps/sensors/tests/test-sensor-filter.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~30s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "Sensor Filter Child"
APP_INSTANCE_LABEL    = "test-filter-app"
MAKER_API_LABEL       = "test-filter-maker"
INPUT_DEVICE_LABELS   = ["test-filter-in"]
OUTPUT_DEVICE_LABELS  = ["test-filter-out"]
CASES                 = [{'name': 'average-of-10-12-14', 'command_spacing_seconds': 0.5, 'setup': [{'device': 'test-filter-in', 'command': 'setTemperature', 'args': [10]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [12]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [14]}], 'wait_seconds': 5, 'assert': [{'device': 'test-filter-out', 'attribute': 'temperature', 'value': 12, 'tolerance': 0.5}]}, {'name': 'window-slides-to-20-22-24', 'command_spacing_seconds': 0.5, 'actions': [{'device': 'test-filter-in', 'command': 'setTemperature', 'args': [20]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [22]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [24]}], 'wait_seconds': 5, 'assert': [{'device': 'test-filter-out', 'attribute': 'temperature', 'value': 22, 'tolerance': 0.5}]}, {'name': 'idempotent-reset-to-12', 'command_spacing_seconds': 0.5, 'actions': [{'device': 'test-filter-in', 'command': 'setTemperature', 'args': [10]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [12]}, {'device': 'test-filter-in', 'command': 'setTemperature', 'args': [14]}], 'wait_seconds': 5, 'assert': [{'device': 'test-filter-out', 'attribute': 'temperature', 'value': 12, 'tolerance': 0.5}]}]
RUNTIME_BUDGET_SECONDS = 30
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
