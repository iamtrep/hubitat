#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-mfc-app — Mode 1 behavior test
#
# Generated from apps/sensors/tests/spec-motion-fusion-child.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/sensors/tests/test-motion-fusion-child.sh                             # default hub
#   bash apps/sensors/tests/test-motion-fusion-child.sh @hubname                    # specific hub
#   bash apps/sensors/tests/test-motion-fusion-child.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~25s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "Motion Fusion Child"
APP_INSTANCE_LABEL    = "test-mfc-app"
MAKER_API_LABEL       = "test-mfc-maker"
INPUT_DEVICE_LABELS   = ["test-mfc-source"]
OUTPUT_DEVICE_LABELS  = ["test-mfc-out"]
CASES                 = [{'name': 'baseline-inactive', 'setup': [{'device': 'test-mfc-source', 'command': 'setPirDetection', 'args': ['inactive']}, {'device': 'test-mfc-source', 'command': 'setRoomState', 'args': ['unoccupied']}, {'device': 'test-mfc-out', 'command': 'inactive'}], 'wait_seconds': 3, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'inactive'}]}, {'name': 'pir-triggers-active', 'actions': [{'device': 'test-mfc-source', 'command': 'setPirDetection', 'args': ['active']}], 'wait_seconds': 1, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'active'}], 'assert_events': [{'attribute': 'motion', 'value': 'active', 'source': 'test-mfc-out'}]}, {'name': 'mmwave-sustains-after-pir-released', 'actions': [{'device': 'test-mfc-source', 'command': 'setRoomState', 'args': ['occupied']}, {'device': 'test-mfc-source', 'command': 'setPirDetection', 'args': ['inactive']}], 'command_spacing_seconds': 1.0, 'wait_seconds': 3, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'active'}], 'assert_events': [{'attribute': 'motion', 'value': 'inactive', 'source': 'test-mfc-out', 'negate': True}]}, {'name': 'mmwave-released-starts-cooldown', 'actions': [{'device': 'test-mfc-source', 'command': 'setRoomState', 'args': ['unoccupied']}], 'wait_seconds': 3, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'inactive'}], 'assert_events': [{'attribute': 'motion', 'value': 'inactive', 'source': 'test-mfc-out'}]}, {'name': 'mmwave-alone-doesnt-trigger', 'actions': [{'device': 'test-mfc-source', 'command': 'setRoomState', 'args': ['occupied']}], 'wait_seconds': 2, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'inactive'}], 'assert_events': [{'attribute': 'motion', 'value': 'active', 'source': 'test-mfc-out', 'negate': True}]}, {'name': 'cleanup-to-baseline', 'actions': [{'device': 'test-mfc-source', 'command': 'setRoomState', 'args': ['unoccupied']}], 'wait_seconds': 1, 'assert': [{'device': 'test-mfc-out', 'attribute': 'motion', 'value': 'inactive'}]}]
RUNTIME_BUDGET_SECONDS = 25
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
