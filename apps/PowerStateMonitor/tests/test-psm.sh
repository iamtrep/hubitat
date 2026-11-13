#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-psm — Mode 1 behavior test
#
# Generated from apps/PowerStateMonitor/tests/spec-psm.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/PowerStateMonitor/tests/test-psm.sh                             # default hub
#   bash apps/PowerStateMonitor/tests/test-psm.sh @hubname                    # specific hub
#   bash apps/PowerStateMonitor/tests/test-psm.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~180s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "Power State Monitor"
APP_INSTANCE_LABEL    = "test-psm"
MAKER_API_LABEL       = "test-psm-maker"
INPUT_DEVICE_LABELS   = ["test-psm-meter", "test-psm-relay"]
OUTPUT_DEVICE_LABELS  = ["test-psm State"]
CASES                 = [{'name': 'start-surge-settles-to-normal', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-psm-relay', 'command': 'off'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [0]}, {'await': {'device': 'test-psm State', 'attribute': 'powerState', 'value': 'off', 'timeout_seconds': 15}}], 'command_spacing_seconds': 1, 'actions': [{'device': 'test-psm-meter', 'command': 'setPower', 'args': [59.8]}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [134.4]}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [115.5]}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [47.7]}], 'wait_seconds': 6, 'assert': [{'device': 'test-psm State', 'attribute': 'powerState', 'value': 'normal'}], 'assert_events': [{'attribute': 'powerState', 'value': 'normal', 'source': 'test-psm State'}, {'attribute': 'powerState', 'value': 'turbo', 'source': 'test-psm State', 'negate': True}, {'attribute': 'powerState', 'value': 'unknown', 'source': 'test-psm State', 'negate': True}]}, {'name': 'ramp-to-turbo-from-the-switch', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-psm-relay', 'command': 'off'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [47.7]}, {'await': {'device': 'test-psm State', 'attribute': 'powerState', 'value': 'normal', 'timeout_seconds': 15}}], 'command_spacing_seconds': 1, 'actions': [{'device': 'test-psm-relay', 'command': 'on'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [104.6]}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [89.6]}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [76.3]}], 'wait_seconds': 6, 'assert': [{'device': 'test-psm State', 'attribute': 'powerState', 'value': 'turbo'}, {'device': 'test-psm State', 'attribute': 'stateSource', 'value': 'switch'}], 'assert_events': [{'attribute': 'powerState', 'value': 'unknown', 'source': 'test-psm State', 'negate': True}]}, {'name': 'turbo-after-the-switch-goes-off-is-other', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-psm-relay', 'command': 'on'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [76.3]}, {'await': {'device': 'test-psm State', 'attribute': 'powerState', 'value': 'turbo', 'timeout_seconds': 15}}], 'actions': [{'device': 'test-psm-relay', 'command': 'off'}], 'wait_seconds': 3, 'assert': [{'device': 'test-psm State', 'attribute': 'powerState', 'value': 'turbo'}, {'device': 'test-psm State', 'attribute': 'stateSource', 'value': 'other'}]}, {'name': 'hysteresis-keeps-normal-near-its-edge', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-psm-relay', 'command': 'off'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [47.7]}, {'await': {'device': 'test-psm State', 'attribute': 'powerState', 'value': 'normal', 'timeout_seconds': 15}}], 'actions': [{'device': 'test-psm-meter', 'command': 'setPower', 'args': [63]}], 'wait_seconds': 6, 'assert': [{'device': 'test-psm State', 'attribute': 'powerState', 'value': 'normal'}, {'device': 'test-psm State', 'attribute': 'stateSource', 'value': 'none'}]}, {'name': 'held-surge-sets-unknown', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-psm-relay', 'command': 'off'}, {'device': 'test-psm-meter', 'command': 'setPower', 'args': [47.7]}, {'await': {'device': 'test-psm State', 'attribute': 'powerState', 'value': 'normal', 'timeout_seconds': 15}}], 'actions': [{'device': 'test-psm-meter', 'command': 'setPower', 'args': [130]}], 'wait_seconds': 11, 'assert': [{'device': 'test-psm State', 'attribute': 'powerState', 'value': 'unknown'}], 'allow_log_patterns': ['a reading above 90 W has held for 8 s']}]
RUNTIME_BUDGET_SECONDS = 180
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
