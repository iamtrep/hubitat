#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-bcl-app — Mode 1 behavior test
#
# Generated from apps/tests/spec-battery-change-logger.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-battery-change-logger.sh                             # default hub
#   bash apps/tests/test-battery-change-logger.sh @hubname                    # specific hub
#   bash apps/tests/test-battery-change-logger.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Battery Change Logger"
APP_INSTANCE_LABEL    = "test-bcl-app"
MAKER_API_LABEL       = "test-bcl-maker"
INPUT_DEVICE_LABELS   = ["test-bcl-battery"]
OUTPUT_DEVICE_LABELS  = []
CASES                 = [{'name': 'seed-then-replace-detected', 'command_spacing_seconds': 0.5, 'setup': [{'device': 'test-bcl-battery', 'command': 'sendBatteryEvent', 'args': [50]}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-bcl-battery', 'command': 'sendBatteryEvent', 'args': [80]}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'Battery replacement detected.*50.*80', 'level': 'info'}]}, {'name': 'below-threshold-no-replacement', 'actions': [{'device': 'test-bcl-battery', 'command': 'sendBatteryEvent', 'args': [85]}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'Battery replacement detected', 'level': 'info', 'negate': True}]}, {'name': 'decrease-no-replacement', 'actions': [{'device': 'test-bcl-battery', 'command': 'sendBatteryEvent', 'args': [50]}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'Battery replacement detected', 'level': 'info', 'negate': True}]}]
RUNTIME_BUDGET_SECONDS = 30
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
