#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-switchmon-app — Mode 1 behavior test
#
# Generated from apps/tests/spec-switch-monitor.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/tests/test-switch-monitor.sh                             # default hub
#   bash apps/tests/test-switch-monitor.sh @hubname                    # specific hub
#   bash apps/tests/test-switch-monitor.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Switch Monitor"
APP_INSTANCE_LABEL    = "test-switchmon-app"
MAKER_API_LABEL       = "test-switchmon-maker"
INPUT_DEVICE_LABELS   = ["test-switchmon-1"]
OUTPUT_DEVICE_LABELS  = []
CASES                 = [{'name': 'correct-deviated-switch', 'setup': [{'device': 'test-switchmon-1', 'command': 'on'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-switchmon-1', 'command': 'off'}], 'wait_seconds': 5, 'assert': [{'device': 'test-switchmon-1', 'attribute': 'switch', 'value': 'on'}], 'allow_log_patterns': ['turned back on']}]
RUNTIME_BUDGET_SECONDS = 30
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
