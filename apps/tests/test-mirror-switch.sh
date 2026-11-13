#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# Mirror Switch (test) — Mode 1 behavior test
#
# Generated from apps/tests/spec-mirror-switch.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-mirror-switch.sh                             # default hub
#   bash apps/tests/test-mirror-switch.sh @hubname                    # specific hub
#   bash apps/tests/test-mirror-switch.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Mirror Switch"
APP_INSTANCE_LABEL    = "Mirror Switch (test)"
MAKER_API_LABEL       = "Mirror Switch Test Maker API"
INPUT_DEVICE_LABELS   = ["mirror-test-a", "mirror-test-b", "mirror-test-c"]
OUTPUT_DEVICE_LABELS  = []
CASES                 = [{'name': 'propagate-on', 'setup': [{'device': 'mirror-test-a', 'command': 'off'}, {'device': 'mirror-test-b', 'command': 'off'}, {'device': 'mirror-test-c', 'command': 'off'}], 'actions': [{'device': 'mirror-test-a', 'command': 'on'}], 'wait_seconds': 5, 'assert': [{'device': 'mirror-test-b', 'attribute': 'switch', 'value': 'on'}, {'device': 'mirror-test-c', 'attribute': 'switch', 'value': 'on'}], 'assert_events': [{'attribute': 'switch', 'value': 'on', 'source': 'mirror-test-b'}, {'attribute': 'switch', 'value': 'on', 'source': 'mirror-test-c'}, {'attribute': 'switch', 'value': 'off', 'source': 'mirror-test-a', 'negate': True}]}, {'name': 'propagate-off', 'setup': [{'device': 'mirror-test-a', 'command': 'on'}, {'device': 'mirror-test-b', 'command': 'on'}, {'device': 'mirror-test-c', 'command': 'on'}], 'actions': [{'device': 'mirror-test-b', 'command': 'off'}], 'wait_seconds': 5, 'assert': [{'device': 'mirror-test-a', 'attribute': 'switch', 'value': 'off'}, {'device': 'mirror-test-c', 'attribute': 'switch', 'value': 'off'}], 'assert_events': [{'attribute': 'switch', 'value': 'off', 'source': 'mirror-test-a'}, {'attribute': 'switch', 'value': 'off', 'source': 'mirror-test-c'}, {'attribute': 'switch', 'value': 'on', 'source': 'mirror-test-b', 'negate': True}]}, {'name': 'already-matching-no-churn', 'setup': [{'device': 'mirror-test-a', 'command': 'on'}, {'device': 'mirror-test-b', 'command': 'on'}, {'device': 'mirror-test-c', 'command': 'on'}], 'actions': [{'device': 'mirror-test-a', 'command': 'on'}], 'wait_seconds': 4, 'assert': [{'device': 'mirror-test-b', 'attribute': 'switch', 'value': 'on'}, {'device': 'mirror-test-c', 'attribute': 'switch', 'value': 'on'}], 'assert_events': [{'attribute': 'switch', 'value': 'on', 'source': 'mirror-test-a'}, {'attribute': 'switch', 'source': 'mirror-test-b', 'negate': True}, {'attribute': 'switch', 'source': 'mirror-test-c', 'negate': True}]}]
RUNTIME_BUDGET_SECONDS = 60
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
