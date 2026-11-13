#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-hfc-slow — Mode 1 behavior test
#
# Generated from apps/tests/spec-humidity-fan-controller-slow.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-humidity-fan-controller-slow.sh                             # default hub
#   bash apps/tests/test-humidity-fan-controller-slow.sh @hubname                    # specific hub
#   bash apps/tests/test-humidity-fan-controller-slow.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~260s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_INSTANCE_LABEL    = "test-hfc-slow"
MAKER_API_LABEL       = "test-hfcs-maker"
INPUT_DEVICE_LABELS   = ["test-hfcs-bath", "test-hfcs-ref"]
OUTPUT_DEVICE_LABELS  = ["test-hfcs-fan", "test-hfcs-hi-sw"]
CASES                 = [{'name': 'baseline-normal', 'setup': [{'device': 'test-hfcs-bath', 'command': 'setHumidity', 'args': [45]}, {'device': 'test-hfcs-ref', 'command': 'setHumidity', 'args': [50]}, {'await': {'device': 'test-hfcs-hi-sw', 'attribute': 'switch', 'value': 'off', 'timeout_seconds': 8}}, {'device': 'test-hfcs-fan', 'command': 'off'}], 'wait_seconds': 2, 'assert': [{'device': 'test-hfcs-fan', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'physical-run-floor-expires', 'actions': [{'device': 'test-hfcs-fan', 'command': 'physicalOn'}], 'wait_seconds': 65, 'assert': [{'device': 'test-hfcs-fan', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Physical-run floor reached \\(1 min\\); turning off fan', 'level': 'info'}]}, {'name': 'max-fan-run-time-expires', 'actions': [{'device': 'test-hfcs-bath', 'command': 'setHumidity', 'args': [75]}], 'wait_seconds': 66, 'assert': [{'device': 'test-hfcs-fan', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-hfcs-hi-sw', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Max fan run time \\(1 min\\) expired', 'level': 'warn'}], 'allow_log_patterns': ['Max fan run time \\(1 min\\) expired', 'No notification device configured']}, {'name': 'reassert-keeps-the-max-run-window', 'setup': [{'device': 'test-hfcs-bath', 'command': 'setHumidity', 'args': [30]}, {'await': {'device': 'test-hfcs-hi-sw', 'attribute': 'switch', 'value': 'off', 'timeout_seconds': 8}}, {'await': {'device': 'test-hfcs-fan', 'attribute': 'switch', 'value': 'off', 'timeout_seconds': 6}}], 'command_spacing_seconds': 35, 'actions': [{'device': 'test-hfcs-bath', 'command': 'setHumidity', 'args': [75]}, {'device': 'test-hfcs-fan', 'command': 'off'}], 'wait_seconds': 35, 'assert': [{'device': 'test-hfcs-fan', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'turned off by another source while humidity is high', 'level': 'info'}, {'pattern': 'Max fan run time \\(1 min\\) expired', 'level': 'warn'}], 'allow_log_patterns': ['Max fan run time \\(1 min\\) expired', 'No notification device configured']}]
RUNTIME_BUDGET_SECONDS = 260
SLOW                  = True

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
