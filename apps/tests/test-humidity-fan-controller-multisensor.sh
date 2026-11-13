#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-hfc-multi — Mode 1 behavior test
#
# Generated from apps/tests/spec-humidity-fan-controller-multisensor.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-humidity-fan-controller-multisensor.sh                             # default hub
#   bash apps/tests/test-humidity-fan-controller-multisensor.sh @hubname                    # specific hub
#   bash apps/tests/test-humidity-fan-controller-multisensor.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~40s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "Humidity-Based Fan Controller"
APP_INSTANCE_LABEL    = "test-hfc-multi"
MAKER_API_LABEL       = "test-hfcm-maker"
INPUT_DEVICE_LABELS   = ["test-hfcm-bath1", "test-hfcm-bath2", "test-hfcm-ref"]
OUTPUT_DEVICE_LABELS  = ["test-hfcm-fan"]
CASES                 = [{'name': 'baseline-normal', 'setup': [{'device': 'test-hfcm-ref', 'command': 'setHumidity', 'args': [50]}, {'device': 'test-hfcm-bath1', 'command': 'setHumidity', 'args': [45]}, {'device': 'test-hfcm-bath2', 'command': 'setHumidity', 'args': [45]}], 'command_spacing_seconds': 1.0, 'wait_seconds': 4, 'assert': [{'device': 'test-hfcm-fan', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'change-is-measured-on-the-median', 'setup': [{'device': 'test-hfcm-bath2', 'command': 'setHumidity', 'args': [90]}, {'device': 'test-hfcm-bath1', 'command': 'setHumidity', 'args': [20]}], 'command_spacing_seconds': 1.0, 'actions': [{'device': 'test-hfcm-bath1', 'command': 'setHumidity', 'args': [60]}], 'wait_seconds': 0.5, 'assert': [{'device': 'test-hfcm-fan', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Excessive humidity change', 'negate': True}, {'pattern': 'above activation threshold.*starting 2s delay', 'level': 'info'}]}, {'name': 'activation-delay-completes', 'wait_seconds': 3, 'assert': [{'device': 'test-hfcm-fan', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'cleanup', 'command_spacing_seconds': 1.0, 'actions': [{'device': 'test-hfcm-bath1', 'command': 'setHumidity', 'args': [45]}, {'device': 'test-hfcm-bath2', 'command': 'setHumidity', 'args': [45]}], 'wait_seconds': 5, 'assert': [{'device': 'test-hfcm-fan', 'attribute': 'switch', 'value': 'off'}]}]
RUNTIME_BUDGET_SECONDS = 40
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
