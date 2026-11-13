#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-hfc-dew — Mode 1 behavior test
#
# Generated from apps/tests/spec-humidity-fan-controller-dewpoint.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-humidity-fan-controller-dewpoint.sh                             # default hub
#   bash apps/tests/test-humidity-fan-controller-dewpoint.sh @hubname                    # specific hub
#   bash apps/tests/test-humidity-fan-controller-dewpoint.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Humidity-Based Fan Controller"
APP_INSTANCE_LABEL    = "test-hfc-dew"
MAKER_API_LABEL       = "test-hfcd-maker"
INPUT_DEVICE_LABELS   = ["test-hfcd-bath", "test-hfcd-ref"]
OUTPUT_DEVICE_LABELS  = ["test-hfcd-fan"]
CASES                 = [{'name': 'baseline-normal', 'setup': [{'device': 'test-hfcd-fan', 'command': 'off'}, {'device': 'test-hfcd-ref', 'command': 'setTemperature', 'args': [22]}, {'device': 'test-hfcd-bath', 'command': 'setTemperature', 'args': [22]}, {'device': 'test-hfcd-ref', 'command': 'setRelativeHumidity', 'args': [45]}, {'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [44]}, {'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [45]}], 'command_spacing_seconds': 1.0, 'wait_seconds': 4, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'small-rise-waits-for-the-delay', 'actions': [{'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [60]}], 'wait_seconds': 1, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Excessive humidity change', 'negate': True}, {'pattern': 'above activation threshold.*°C.*starting 5s delay', 'level': 'info'}]}, {'name': 'activation-delay-completes', 'wait_seconds': 6, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'back-to-normal', 'actions': [{'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [45]}], 'wait_seconds': 5, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'excessive-rise-skips-the-delay', 'setup': [{'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [44]}], 'actions': [{'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [70]}], 'wait_seconds': 1.5, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Excessive humidity change.*Δ=26', 'level': 'info'}, {'pattern': 'bypassing activation delay', 'level': 'info'}]}, {'name': 'cleanup', 'actions': [{'device': 'test-hfcd-bath', 'command': 'setRelativeHumidity', 'args': [45]}], 'wait_seconds': 5, 'assert': [{'device': 'test-hfcd-fan', 'attribute': 'switch', 'value': 'off'}]}]
RUNTIME_BUDGET_SECONDS = 90
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
