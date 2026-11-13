#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-ilk-permit — Mode 1 behavior test
#
# Generated from apps/HvacInterlock/tests/spec-interlock-permit.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/HvacInterlock/tests/test-ilk-permit.sh                             # default hub
#   bash apps/HvacInterlock/tests/test-ilk-permit.sh @hubname                    # specific hub
#   bash apps/HvacInterlock/tests/test-ilk-permit.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~210s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
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
APP_TYPE_NAME         = "HVAC Interlock Group"
APP_INSTANCE_LABEL    = "test-ilk-permit"
MAKER_API_LABEL       = "test-ilk-maker"
INPUT_DEVICE_LABELS   = ["test-ilk-window"]
OUTPUT_DEVICE_LABELS  = ["test-ilk-permit status", "test-season Season", "test-ilk-notify", "test-ilk-th"]
CASES                 = [{'name': 'allowed-in-spring', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block'}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['spring', 0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'none'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'blocked-in-summer', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'season'}]}, {'name': 'open-window-blocks', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-ilk-window', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'openings'}, {'device': 'test-ilk-permit status', 'attribute': 'openContacts', 'value': 'test-ilk-window'}, {'device': 'test-ilk-notify', 'attribute': 'lastMessage', 'value': 'test-ilk-permit: test-ilk-window open'}]}, {'name': 'reopen-during-close-delay', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 10}}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}, {'device': 'test-ilk-window', 'command': 'open'}], 'actions': [{'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-ilk-window', 'command': 'open'}], 'wait_seconds': 12, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-ilk-notify', 'attribute': 'lastMessage', 'value': 'test-ilk-permit: test-ilk-window open'}]}, {'name': 'closing-clears-after-delay', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3}}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}, {'device': 'test-ilk-window', 'command': 'open'}], 'actions': [{'device': 'test-ilk-window', 'command': 'close'}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'closed'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'none'}, {'device': 'test-ilk-permit status', 'attribute': 'openContacts', 'value': 'none'}, {'device': 'test-ilk-notify', 'attribute': 'lastMessage', 'value': 'test-ilk-permit: alert cleared'}]}, {'name': 'open-window-in-summer-stays-quiet', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}], 'actions': [{'device': 'test-ilk-window', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'closed'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'season'}]}, {'name': 'open-before-save-raises', 'command_spacing_seconds': 0.5, 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'openDelay': 60}}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-ilk-window', 'command': 'open'}], 'actions': [{'settings': {'openDelay': 0}}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'off'}]}, {'name': 'warn-raises-without-blocking', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'warn'}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-ilk-window', 'command': 'open'}], 'wait_seconds': 3, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'none'}]}, {'name': 'block-restored', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block'}}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['spring', 0]}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'on'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'closed'}]}, {'name': 'failing-notifier-still-blocks-and-forwards', 'setup': [{'settings': {'debugEnable': True}}, {'settings': {'testCloseDelay': 3, 'response': 'block'}}, {'device': 'test-ilk-notify', 'command': 'setFailing', 'args': ['false']}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['summer', 0]}, {'device': 'test-ilk-window', 'command': 'open'}, {'device': 'test-ilk-notify', 'command': 'setFailing', 'args': ['true']}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'wait_seconds': 4, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'blockReason', 'value': 'openings'}, {'device': 'test-ilk-permit status', 'attribute': 'contact', 'value': 'open'}, {'device': 'test-ilk-th', 'attribute': 'thermostatMode', 'value': 'off'}], 'allow_warnings': True}, {'name': 'notifier-restored', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-ilk-notify', 'command': 'setFailing', 'args': ['false']}, {'device': 'test-ilk-window', 'command': 'close'}, {'device': 'test-season Season', 'command': 'setSeason', 'args': ['winter', 0]}], 'actions': [{'device': 'test-season Season', 'command': 'setSeason', 'args': ['spring', 0]}], 'wait_seconds': 8, 'assert': [{'device': 'test-ilk-permit status', 'attribute': 'switch', 'value': 'on'}]}]
RUNTIME_BUDGET_SECONDS = 210
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
