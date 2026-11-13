#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-sadc-app — Mode 1 behavior test
#
# Generated from apps/sensors/tests/spec-sadc.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/sensors/tests/test-sadc.sh                             # default hub
#   bash apps/sensors/tests/test-sadc.sh @hubname                    # specific hub
#   bash apps/sensors/tests/test-sadc.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Sensor Aggregator Discrete Child"
APP_INSTANCE_LABEL    = "test-sadc-app"
MAKER_API_LABEL       = "test-sadc-maker"
INPUT_DEVICE_LABELS   = ["test-sadc-in-1", "test-sadc-in-2", "test-sadc-in-3"]
OUTPUT_DEVICE_LABELS  = ["test-sadc-out"]
CASES                 = [{'name': 'all-closed-baseline', 'setup': [{'settings': {'aggregationMethod': 'any', 'defaultStaysSeconds': 0}}, {'device': 'test-sadc-in-1', 'command': 'close'}, {'device': 'test-sadc-in-2', 'command': 'close'}, {'device': 'test-sadc-in-3', 'command': 'close'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'single-open-propagation', 'actions': [{'device': 'test-sadc-in-1', 'command': 'open'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'open'}]}, {'name': 'idempotent-reset', 'actions': [{'device': 'test-sadc-in-1', 'command': 'close'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'majority-one-of-three', 'setup': [{'settings': {'aggregationMethod': 'majority'}}], 'actions': [{'device': 'test-sadc-in-1', 'command': 'open'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'majority-two-of-three', 'actions': [{'device': 'test-sadc-in-2', 'command': 'open'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'open'}]}, {'name': 'all-two-of-three', 'setup': [{'settings': {'aggregationMethod': 'all'}}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'all-three-of-three', 'actions': [{'device': 'test-sadc-in-3', 'command': 'open'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'open'}]}, {'name': 'stays-held', 'setup': [{'settings': {'aggregationMethod': 'any', 'defaultStaysSeconds': 3}}, {'device': 'test-sadc-in-1', 'command': 'close'}, {'device': 'test-sadc-in-2', 'command': 'close'}, {'device': 'test-sadc-in-3', 'command': 'close'}], 'setup_wait_seconds': 5, 'actions': [{'device': 'test-sadc-in-1', 'command': 'open'}], 'wait_seconds': 1, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'stays-committed', 'wait_seconds': 4, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'open'}]}, {'name': 'stays-reset', 'setup': [{'settings': {'defaultStaysSeconds': 0}}], 'actions': [{'device': 'test-sadc-in-1', 'command': 'close'}], 'wait_seconds': 2, 'assert': [{'device': 'test-sadc-out', 'attribute': 'contact', 'value': 'closed'}]}]
RUNTIME_BUDGET_SECONDS = 60
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
