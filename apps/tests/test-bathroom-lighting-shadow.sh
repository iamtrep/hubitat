#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-bls-app — Mode 1 behavior test
#
# Generated from apps/tests/spec-bathroom-lighting-shadow.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-bathroom-lighting-shadow.sh                             # default hub
#   bash apps/tests/test-bathroom-lighting-shadow.sh @hubname                    # specific hub
#   bash apps/tests/test-bathroom-lighting-shadow.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~120s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "Bathroom Lighting Shadow"
APP_INSTANCE_LABEL    = "test-bls-app"
MAKER_API_LABEL       = "test-bls-maker"
INPUT_DEVICE_LABELS   = ["test-bls-hue", "test-bls-fp300-pir", "test-bls-fp300-mm", "test-bls-wall", "test-bls-door", "test-bls-hfc"]
OUTPUT_DEVICE_LABELS  = ["test-bls-app hueOnly", "test-bls-app fp300Hybrid", "test-bls-app fp300Mm", "test-bls-app anyMotion", "test-bls-app composite"]
CASES                 = [{'name': 'smoke-rig-boots', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}], 'wait_seconds': 2, 'assert': [{'device': 'test-bls-wall', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'walkin-hue-fires-output', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}], 'actions': [{'device': 'test-bls-hue', 'command': 'active'}], 'wait_seconds': 2, 'assert': [{'device': 'test-bls-app hueOnly', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'hueonly-holds-then-times-out', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}], 'actions': [{'device': 'test-bls-hue', 'command': 'active'}, {'device': 'test-bls-hue', 'command': 'inactive'}], 'command_spacing_seconds': 1.0, 'wait_seconds': 12, 'assert': [{'device': 'test-bls-app hueOnly', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'fp300-mm-only-fires-mm-policy', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}, {'device': 'test-bls-app fp300Hybrid', 'command': 'off'}, {'device': 'test-bls-app fp300Mm', 'command': 'off'}, {'device': 'test-bls-app anyMotion', 'command': 'off'}], 'actions': [{'device': 'test-bls-fp300-mm', 'command': 'active'}], 'wait_seconds': 2, 'assert': [{'device': 'test-bls-app hueOnly', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-bls-app fp300Hybrid', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-bls-app fp300Mm', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-bls-app anyMotion', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'missed-on-no-sensors-fire', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}], 'actions': [{'device': 'test-bls-wall', 'command': 'on'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'missedOn:[1-9]', 'level': 'info'}]}, {'name': 'anticipated-user-shadow-off-while-wall-still-on', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}], 'actions': [{'device': 'test-bls-hue', 'command': 'active'}, {'device': 'test-bls-wall', 'command': 'on'}, {'device': 'test-bls-hue', 'command': 'inactive'}], 'command_spacing_seconds': 1.0, 'wait_seconds': 12, 'assert_logs': [{'pattern': 'classifyOff hueOnly class=correctOff_anticipatedUser', 'level': 'info'}]}, {'name': 'false-on-fp300-glitch-no-wall', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}, {'device': 'test-bls-app fp300Mm', 'command': 'off'}], 'actions': [{'device': 'test-bls-fp300-mm', 'command': 'active'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}], 'command_spacing_seconds': 1.0, 'wait_seconds': 42, 'assert_logs': [{'pattern': 'falseOn classified for policy fp300Mm', 'level': 'info'}]}, {'name': 'composite-holds-through-hfc-active', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}, {'device': 'test-bls-hfc', 'command': 'off'}, {'device': 'test-bls-app hueOnly', 'command': 'off'}, {'device': 'test-bls-app composite', 'command': 'off'}], 'actions': [{'device': 'test-bls-hue', 'command': 'active'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-hfc', 'command': 'on'}], 'command_spacing_seconds': 1.0, 'wait_seconds': 12, 'assert': [{'device': 'test-bls-app hueOnly', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-bls-app composite', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'user-forgot-arm-quiet', 'setup': [{'device': 'test-bls-wall', 'command': 'on'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}], 'actions': [{'device': 'test-bls-hue', 'command': 'active'}, {'device': 'test-bls-hue', 'command': 'inactive'}], 'command_spacing_seconds': 1.0, 'wait_seconds': 22, 'assert': [{'device': 'test-bls-wall', 'attribute': 'switch', 'value': 'on'}]}, {'name': 'user-forgot-trigger-off', 'actions': [{'device': 'test-bls-wall', 'command': 'off'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'userForgotOff: count=', 'level': 'info'}]}, {'name': 'manual-override-arm-quiet', 'setup': [{'device': 'test-bls-wall', 'command': 'off'}, {'device': 'test-bls-hue', 'command': 'inactive'}, {'device': 'test-bls-fp300-pir', 'command': 'inactive'}, {'device': 'test-bls-fp300-mm', 'command': 'inactive'}], 'wait_seconds': 6, 'assert': [{'device': 'test-bls-wall', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'manual-override-fires', 'actions': [{'device': 'test-bls-wall', 'command': 'on'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'manualOverride', 'level': 'info'}]}]
RUNTIME_BUDGET_SECONDS = 120
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
