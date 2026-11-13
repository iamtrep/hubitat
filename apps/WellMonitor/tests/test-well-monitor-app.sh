#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-LIVE: needs a reachable hub

#
# test-well-monitor-app — Mode 1 behavior test
#
# Generated from apps/WellMonitor/tests/spec-well-monitor.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten. The harness is scripts/lib/behavior_harness.py.
#
# Usage:
#   bash apps/WellMonitor/tests/test-well-monitor-app.sh                             # default hub
#   bash apps/WellMonitor/tests/test-well-monitor-app.sh @hubname                    # specific hub
#   bash apps/WellMonitor/tests/test-well-monitor-app.sh @hubname 247                # specific hub + explicit instance id
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
APP_TYPE_NAME         = "Well Monitor"
APP_INSTANCE_LABEL    = "test-well-monitor-app"
MAKER_API_LABEL       = "test-well-monitor-maker"
INPUT_DEVICE_LABELS   = ["test-wm-pump", "test-wm-meter"]
OUTPUT_DEVICE_LABELS  = ["test-wm-pump-active", "test-wm-leak", "test-wm-flow-ind"]
CASES                 = [{'name': 'pump-start-turns-on-active-switch', 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-meter', 'command': 'setVolume', 'args': [1000]}, {'device': 'test-wm-pump-active', 'command': 'off'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Pump started', 'level': 'info'}]}, {'name': 'pump-stop-records-cycle', 'setup': [{'device': 'test-wm-meter', 'command': 'setVolume', 'args': [1007.5]}], 'setup_wait_seconds': 1, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Pump stopped', 'level': 'info'}, {'pattern': 'coincident flow 7\\.5', 'level': 'info'}]}, {'name': 'pump-start-after-ramp-reading', 'command_spacing_seconds': 1, 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-pump-active', 'command': 'off'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [93]}, {'device': 'test-wm-pump', 'command': 'setPower', 'args': [2099]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Pump started', 'level': 'info'}]}, {'name': 'pump-stop-after-ramp-reading', 'command_spacing_seconds': 1, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [50]}, {'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Pump stopped', 'level': 'info'}]}, {'name': 'flow-start-turns-on-flow-indicator', 'setup': [{'device': 'test-wm-meter', 'command': 'setRate', 'args': [0]}, {'device': 'test-wm-flow-ind', 'command': 'off'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-meter', 'command': 'setRate', 'args': [5]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-flow-ind', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Water flow started', 'level': 'info'}]}, {'name': 'flow-stop-records-event', 'setup': [{'device': 'test-wm-meter', 'command': 'setVolume', 'args': [1010.5]}], 'setup_wait_seconds': 1, 'actions': [{'device': 'test-wm-meter', 'command': 'setRate', 'args': [0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-flow-ind', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Water flow stopped', 'level': 'info'}]}, {'name': 'emergency-shutoff-fires-after-max-runtime', 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-pump', 'command': 'on'}, {'device': 'test-wm-leak', 'command': 'dry'}, {'device': 'test-wm-pump-active', 'command': 'off'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}], 'wait_seconds': 14, 'assert': [{'device': 'test-wm-pump', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-wm-leak', 'attribute': 'water', 'value': 'wet'}, {'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'off'}], 'assert_events': [{'attribute': 'water', 'value': 'wet', 'source': 'test-wm-leak'}, {'attribute': 'switch', 'value': 'off', 'source': 'test-wm-pump-active'}], 'allow_log_patterns': ['No notification device configured', 'EMERGENCY SHUTOFF']}, {'name': 'resave-in-running-band-keeps-emergency-timer', 'command_spacing_seconds': 1, 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-pump', 'command': 'on'}, {'device': 'test-wm-leak', 'command': 'dry'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}, {'device': 'test-wm-pump', 'command': 'setPower', 'args': [80]}, {'settings': {'debugEnable': True}}], 'wait_seconds': 16, 'assert': [{'device': 'test-wm-pump', 'attribute': 'switch', 'value': 'off'}], 'allow_log_patterns': ['No notification device configured', 'EMERGENCY SHUTOFF', 'Dashboard UI', 'UI sync rejected']}, {'name': 'recovery-monitors-a-start-it-did-not-see', 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-pump-active', 'command': 'off'}, {'settings': {'powerOnThreshold': 5000}}, {'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}], 'setup_wait_seconds': 2, 'actions': [{'settings': {'powerOnThreshold': 100}}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Pump started', 'level': 'info'}], 'allow_log_patterns': ['its start was not seen', 'Dashboard UI', 'UI sync rejected']}, {'name': 'stop-with-rejected-csv-name', 'command_spacing_seconds': 1, 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'device': 'test-wm-leak', 'command': 'dry'}, {'settings': {'enableCsvLogging': True}}, {'settings': {'csvFileName': '.wm-test.csv'}}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}, {'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Pump stopped', 'level': 'info'}], 'allow_warnings': True}, {'name': 'next-cycle-is-not-timed-from-the-failed-stop', 'setup': [{'device': 'test-wm-leak', 'command': 'dry'}], 'setup_wait_seconds': 12, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-leak', 'attribute': 'water', 'value': 'dry'}, {'device': 'test-wm-pump-active', 'attribute': 'switch', 'value': 'on'}], 'assert_logs': [{'pattern': 'Pump started', 'level': 'info'}], 'assert_events': [{'attribute': 'water', 'value': 'wet', 'source': 'test-wm-leak', 'negate': True}]}, {'name': 'shutoff-is-sent-again-until-the-pump-is-off', 'command_spacing_seconds': 14, 'setup': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [0]}, {'settings': {'enableCsvLogging': False}}, {'device': 'test-wm-leak', 'command': 'dry'}, {'device': 'test-wm-pump', 'command': 'on'}, {'device': 'test-wm-pump', 'command': 'setStuck', 'args': ['true']}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-pump', 'command': 'setPower', 'args': [200]}, {'device': 'test-wm-pump', 'command': 'setStuck', 'args': ['false']}], 'wait_seconds': 65, 'assert': [{'device': 'test-wm-pump', 'attribute': 'switch', 'value': 'off'}], 'allow_log_patterns': ['No notification device configured', 'EMERGENCY SHUTOFF', 'Sending off again']}, {'name': 'unwatched-flow-end-clears-the-indicator', 'command_spacing_seconds': 1, 'setup': [{'device': 'test-wm-pump', 'command': 'setStuck', 'args': ['false']}, {'device': 'test-wm-meter', 'command': 'setRate', 'args': [0]}, {'device': 'test-wm-flow-ind', 'command': 'off'}], 'setup_wait_seconds': 2, 'actions': [{'device': 'test-wm-meter', 'command': 'setRate', 'args': [5]}, {'settings': {'enableFlowTracking': False}}, {'device': 'test-wm-meter', 'command': 'setRate', 'args': [0]}, {'settings': {'enableFlowTracking': True}}], 'wait_seconds': 3, 'assert': [{'device': 'test-wm-flow-ind', 'attribute': 'switch', 'value': 'off'}], 'assert_logs': [{'pattern': 'Water flow stopped', 'level': 'info', 'negate': True}], 'allow_log_patterns': ['ended while it was not watched', 'Dashboard UI', 'UI sync rejected']}]
RUNTIME_BUDGET_SECONDS = 260
SLOW                  = False

run(sys.argv[1:],
    app_type_name=APP_TYPE_NAME, app_instance_label=APP_INSTANCE_LABEL,
    maker_api_label=MAKER_API_LABEL,
    input_device_labels=INPUT_DEVICE_LABELS, output_device_labels=OUTPUT_DEVICE_LABELS,
    cases=CASES, runtime_budget_seconds=RUNTIME_BUDGET_SECONDS, slow=SLOW)
PYTHON_SCRIPT
