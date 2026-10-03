#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# test-tsp — Mode 1 behavior test
#
# Generated from apps/ThermostatSchedulerPlus/tests/spec-tsp.yaml.
# Edit the spec and regenerate this file. Manual edits
# will be overwritten.
#
# Usage:
#   bash apps/ThermostatSchedulerPlus/tests/test-tsp.sh                             # default hub
#   bash apps/ThermostatSchedulerPlus/tests/test-tsp.sh @hubname                    # specific hub
#   bash apps/ThermostatSchedulerPlus/tests/test-tsp.sh @hubname 247                # specific hub + explicit instance id
#
# Runtime budget: ~370s. Tests exceeding this must opt into RUN_SLOW_TESTS=1 per TESTING.md §1.1.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Walk up from SCRIPT_DIR to find the repo root (the dir containing
# .hubitat.json). Lets tests live at any depth — apps/sensors/tests/,
# apps/tests/, scripts/tests/, etc.
PROJECT_ROOT="$SCRIPT_DIR"
while [[ "$PROJECT_ROOT" != "/" && ! -f "$PROJECT_ROOT/.hubitat.json" ]]; do
    PROJECT_ROOT="$(dirname "$PROJECT_ROOT")"
done
if [[ ! -f "$PROJECT_ROOT/.hubitat.json" ]]; then
    echo "Could not find .hubitat.json walking up from $SCRIPT_DIR" >&2
    exit 2
fi
CONFIG_FILE="$PROJECT_ROOT/.hubitat.json"
# Unique per run/hub/test so concurrent runs (e.g. parallel agents, pytest -n)
# don't clobber each other's session state. Cleaned up on exit.
COOKIE_JAR="$(mktemp -u "${TMPDIR:-/tmp}/hubitat-test-cookies.XXXXXX")"
trap 'rm -f "$COOKIE_JAR"' EXIT

HUB_NAME=""
INSTANCE_ID=""
for arg in "$@"; do
    if [[ "$arg" == @* ]]; then
        HUB_NAME="${arg#@}"
    elif [[ "$arg" =~ ^[0-9]+$ ]]; then
        INSTANCE_ID="$arg"
    fi
done

python3 - "$HUB_NAME" "$INSTANCE_ID" "$CONFIG_FILE" "$COOKIE_JAR" "$PROJECT_ROOT" <<'PYTHON_SCRIPT'
import json, sys, re, time, urllib.request, urllib.parse, urllib.error, http.cookiejar

# ── Embedded spec data (rendered from the spec) ──────────
APP_TYPE_NAME         = "Thermostat Scheduler+ Program"
APP_INSTANCE_LABEL    = "test-tsp"
MAKER_API_LABEL       = "test-tsp-maker"
INPUT_DEVICE_LABELS   = ["test-tsp-th1", "test-tsp-th2", "test-tsp-pause"]
OUTPUT_DEVICE_LABELS  = ["test-tsp scheduler"]
CASES                 = [{'name': 'load-config-and-apply', 'setup': [{'settings': {'debugEnable': True}}, {'device': 'test-tsp scheduler', 'command': 'resume'}, {'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['off']}, {'device': 'test-tsp scheduler', 'command': 'setEcoOffset', 'args': [2]}, {'device': 'test-tsp-th1', 'command': 'heat'}, {'device': 'test-tsp-th2', 'command': 'heat'}, {'device': 'test-tsp-pause', 'command': 'on'}, {'mode': 'Day'}, {'settings': {'testConfigJson': '{"v":1,"profiles":[{"name":"Home","heat":21.0,"cool":24.0},{"name":"Out","heat":19.0,"cool":27.0},{"name":"Sleep","heat":18.0,"cool":26.0},{"name":"Away","heat":16.0,"cool":29.0}],"schedules":[{"name":"Normal","type":"time","groups":[{"name":"All","days":[1,2,3,4,5,6,7],"periods":[{"name":"Wake","start":{"kind":"time","at":"06:30"},"profile":"Home"},{"name":"Leave","start":{"kind":"time","at":"08:15"},"profile":"Out"},{"name":"Night","start":{"kind":"time","at":"22:00"},"profile":"Sleep"}]}]},{"name":"Vacation","type":"time","groups":[{"name":"All","days":[1,2,3,4,5,6,7],"periods":[{"name":"Day","start":{"kind":"time","at":"00:00"},"profile":"Away"}]}]}],"active":"Normal","overrides":[{"modeId":4,"profile":"Away"}]}'}}, {'button': 'btnLoadConfig'}, {'settings': {'testClock': '2026-10-05 09:00'}}], 'actions': [{'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp-th2', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Out'}, {'device': 'test-tsp scheduler', 'attribute': 'nextProfile', 'value': 'Sleep'}, {'device': 'test-tsp scheduler', 'attribute': 'lastApply', 'value': 'ok'}]}, {'name': 'writes-verified', 'actions': [{'device': 'test-tsp scheduler', 'command': 'applyNow'}], 'wait_seconds': 33, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'lastApply', 'value': 'ok'}]}, {'name': 'transition-on-test-clock', 'actions': [{'settings': {'testClock': '2026-10-05 22:00'}}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Sleep'}]}, {'name': 'hold-profile-next', 'actions': [{'device': 'test-tsp scheduler', 'command': 'holdProfile', 'args': ['Home', 'next']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'next'}]}, {'name': 'next-hold-ends-at-transition', 'actions': [{'settings': {'testClock': '2026-10-06 06:30'}}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'none'}]}, {'name': 'hold-indefinite-and-resume', 'actions': [{'device': 'test-tsp scheduler', 'command': 'holdProfile', 'args': ['Sleep', 'indefinite']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'indefinite'}]}, {'name': 'resume', 'actions': [{'device': 'test-tsp scheduler', 'command': 'resume'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}]}, {'name': 'advance', 'actions': [{'device': 'test-tsp scheduler', 'command': 'advance'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Out'}]}, {'name': 'set-schedule', 'actions': [{'device': 'test-tsp scheduler', 'command': 'resume'}, {'device': 'test-tsp scheduler', 'command': 'setSchedule', 'args': ['Vacation']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'schedule', 'value': 'Vacation'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'bad-command-logs-warning', 'allow_warnings': True, 'actions': [{'device': 'test-tsp scheduler', 'command': 'setSchedule', 'args': ['Nope']}], 'wait_seconds': 2, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'schedule', 'value': 'Vacation'}], 'assert_logs': [{'pattern': 'unknown schedule Nope', 'level': 'warn'}]}, {'name': 'eco-offset-zero', 'actions': [{'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['on']}, {'device': 'test-tsp scheduler', 'command': 'setEcoOffset', 'args': [0]}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'eco', 'value': 'on'}, {'device': 'test-tsp scheduler', 'attribute': 'heatingTarget', 'value': 16.0, 'tolerance': 0.05}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'manual-change-survives-evaluate', 'actions': [{'device': 'test-tsp-th1', 'command': 'setHeatingSetpoint', 'args': [22.5]}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'manual'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 22.5, 'tolerance': 0.05}]}, {'name': 'override-in-and-out', 'setup': [{'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['off']}, {'device': 'test-tsp scheduler', 'command': 'setEcoOffset', 'args': [2]}, {'device': 'test-tsp scheduler', 'command': 'setSchedule', 'args': ['Normal']}, {'device': 'test-tsp scheduler', 'command': 'resume'}, {'settings': {'testClock': '2026-10-05 09:00'}}, {'button': 'btnEvaluate'}], 'actions': [{'mode': 'Away'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'mode'}]}, {'name': 'override-ends-next-hold', 'setup': [{'mode': 'Day'}, {'device': 'test-tsp scheduler', 'command': 'holdProfile', 'args': ['Home', 'next']}], 'actions': [{'mode': 'Away'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'mode'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'none'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'eco-on-override-and-off-again', 'actions': [{'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['on']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 14.0, 'tolerance': 0.05}]}, {'name': 'eco-off-while-override-keeps-override', 'actions': [{'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['off']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'mode'}]}, {'name': 'hold-during-override', 'actions': [{'device': 'test-tsp scheduler', 'command': 'holdProfile', 'args': ['Home', 'next']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'next'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}]}, {'name': 'override-end-ends-next-hold', 'actions': [{'mode': 'Day'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'none'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}]}, {'name': 'eco-offset-change-does-not-stack', 'setup': [{'mode': 'Day'}, {'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['on']}], 'actions': [{'device': 'test-tsp scheduler', 'command': 'setEcoOffset', 'args': [3]}, {'device': 'test-tsp scheduler', 'command': 'setEcoOffset', 'args': [2]}], 'command_spacing_seconds': 2, 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 17.0, 'tolerance': 0.05}]}, {'name': 'manual-change-sets-manual', 'setup': [{'device': 'test-tsp scheduler', 'command': 'setEco', 'args': ['off']}], 'actions': [{'device': 'test-tsp-th1', 'command': 'setHeatingSetpoint', 'args': [23]}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'manual'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 23.0, 'tolerance': 0.05}]}, {'name': 'manual-survives-other-events', 'setup': [{'device': 'test-tsp-th2', 'command': 'setCoolingSetpoint', 'args': [30]}], 'actions': [{'device': 'test-tsp-th2', 'command': 'cool'}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 23.0, 'tolerance': 0.05}, {'device': 'test-tsp-th2', 'attribute': 'coolingSetpoint', 'value': 27.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'manual'}]}, {'name': 'cooling-only-change-keeps-manual', 'actions': [{'device': 'test-tsp scheduler', 'command': 'holdSetpoints', 'args': [23, 28, 'next']}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th2', 'attribute': 'coolingSetpoint', 'value': 28.0, 'tolerance': 0.05}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 23.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'manual'}]}, {'name': 'next-write-clears-manual', 'actions': [{'settings': {'testClock': '2026-10-05 22:00'}}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}]}, {'name': 'mode-off-writes-nothing', 'setup': [{'device': 'test-tsp-th2', 'command': 'off'}, {'device': 'test-tsp-th2', 'command': 'setHeatingSetpoint', 'args': [10]}], 'actions': [{'device': 'test-tsp scheduler', 'command': 'applyNow'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th2', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-tsp-th2', 'attribute': 'heatingSetpoint', 'value': 10.0, 'tolerance': 0.05}]}, {'name': 'off-thermostat-heat-gets-setpoint', 'actions': [{'device': 'test-tsp-th2', 'command': 'heat'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th2', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}]}, {'name': 'pause-switch-turns-off-and-restores', 'actions': [{'device': 'test-tsp-pause', 'command': 'off'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'restricted'}]}, {'name': 'resume-restores-mode-and-applies', 'actions': [{'device': 'test-tsp-th1', 'command': 'setHeatingSetpoint', 'args': [12]}, {'device': 'test-tsp-pause', 'command': 'on'}], 'command_spacing_seconds': 2, 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'thermostatMode', 'value': 'heat'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}]}, {'name': 'resume-leave-off-writes-nothing', 'setup': [{'settings': {'onResume': 'leaveOff'}}, {'device': 'test-tsp-pause', 'command': 'off'}, {'device': 'test-tsp-th1', 'command': 'setHeatingSetpoint', 'args': [12]}], 'actions': [{'device': 'test-tsp-pause', 'command': 'on'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'thermostatMode', 'value': 'off'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 12.0, 'tolerance': 0.05}]}, {'name': 'status-switch-pauses', 'setup': [{'settings': {'onResume': 'restore'}}, {'device': 'test-tsp-th1', 'command': 'heat'}, {'device': 'test-tsp-th2', 'command': 'heat'}, {'button': 'btnEvaluate'}], 'actions': [{'device': 'test-tsp scheduler', 'command': 'off'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'switch', 'value': 'off'}, {'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'paused'}, {'device': 'test-tsp-th1', 'attribute': 'thermostatMode', 'value': 'off'}]}, {'name': 'status-switch-resumes', 'actions': [{'device': 'test-tsp scheduler', 'command': 'on'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp-th1', 'attribute': 'thermostatMode', 'value': 'heat'}]}, {'name': 'hub-start-applies', 'setup': [{'device': 'test-tsp-th1', 'command': 'setHeatingSetpoint', 'args': [12]}], 'actions': [{'button': 'btnStart'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}]}, {'name': 'edit-profile-through-ui', 'setup': [{'device': 'test-tsp scheduler', 'command': 'setSchedule', 'args': ['Normal']}, {'button': 'epf~2'}], 'actions': [{'settings': {'edHeat': 17.5}}, {'button': 'btnProfileSave'}], 'wait_seconds': 3, 'assert_events': [{'attribute': 'heatingSetpoint', 'value': '17.5', 'source': 'test-tsp-th1'}]}, {'name': 'delete-in-use-refused', 'allow_warnings': True, 'actions': [{'button': 'epf~2'}, {'button': 'btnProfileDelete'}], 'wait_seconds': 2, 'assert_logs': [{'pattern': 'Sleep is used by', 'level': 'warn'}]}, {'name': 'rename-profile-updates-references', 'setup': [{'button': 'epf~2'}], 'actions': [{'settings': {'edName': 'Bedtime'}}, {'button': 'btnProfileSave'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Bedtime'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 17.5, 'tolerance': 0.05}]}, {'name': 'edit-period-through-ui', 'setup': [{'button': 'esc~0'}, {'button': 'epd~0~2'}], 'actions': [{'settings': {'edProfile': 'Out'}}, {'button': 'btnPeriodSave'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Out'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}]}, {'name': 'split-day-into-own-group', 'setup': [{'button': 'edg~new~1'}, {'button': 'epd~1~2'}], 'actions': [{'settings': {'edProfile': 'Home'}}, {'button': 'btnPeriodSave'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}]}, {'name': 'other-days-keep-their-group', 'actions': [{'settings': {'testClock': '2026-10-06 22:00'}}, {'button': 'btnEvaluate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 19.0, 'tolerance': 0.05}]}, {'name': 'edit-override-through-ui', 'setup': [{'button': 'eov~0'}], 'actions': [{'settings': {'edProfile': 'Home'}}, {'button': 'btnOverrideSave'}, {'mode': 'Away'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'mode'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}]}, {'name': 'mode-schedule-through-ui', 'setup': [{'mode': 'Day'}, {'button': 'btnScheduleNew'}], 'actions': [{'settings': {'edSchedName': 'Modes', 'edSchedType': 'mode'}}, {'button': 'btnScheduleSave'}, {'button': 'btnRowAdd'}, {'settings': {'edModes': ['1'], 'edProfile': 'Away'}}, {'button': 'btnRowSave'}, {'button': 'btnScheduleActivate'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'schedule', 'value': 'Modes'}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Away'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'delete-active-schedule-refused', 'allow_warnings': True, 'actions': [{'button': 'btnScheduleDelete'}], 'wait_seconds': 2, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'schedule', 'value': 'Modes'}], 'assert_logs': [{'pattern': 'Modes is the active schedule', 'level': 'warn'}]}, {'name': 'hold-through-main-page', 'actions': [{'settings': {'holdSel': 'Home', 'holdUntil': 'indefinite'}}, {'button': 'btnHold'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'holdEnd', 'value': 'indefinite'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 21.0, 'tolerance': 0.05}]}, {'name': 'end-hold-through-main-page', 'actions': [{'button': 'btnResume'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'held-profile-rename-follows-hold', 'setup': [{'button': 'btnProfileAdd'}, {'settings': {'edName': 'Guest', 'edHeat': 20}}, {'button': 'btnProfileSave'}, {'device': 'test-tsp scheduler', 'command': 'holdProfile', 'args': ['Guest', 'indefinite']}], 'actions': [{'button': 'epf~4'}, {'settings': {'edName': 'Visit'}}, {'button': 'btnProfileSave'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Visit'}, {'device': 'test-tsp scheduler', 'attribute': 'heatingTarget', 'value': 20.0, 'tolerance': 0.05}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 20.0, 'tolerance': 0.05}]}, {'name': 'held-profile-delete-refused', 'allow_warnings': True, 'actions': [{'button': 'epf~4'}, {'button': 'btnProfileDelete'}], 'wait_seconds': 2, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'hold'}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Visit'}], 'assert_logs': [{'pattern': 'Visit is used by the hold now in effect', 'level': 'warn'}]}, {'name': 'resume-after-held-profile', 'actions': [{'device': 'test-tsp scheduler', 'command': 'resume'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'status', 'value': 'schedule'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 16.0, 'tolerance': 0.05}]}, {'name': 'reload-test-config', 'actions': [{'button': 'btnLoadConfig'}], 'wait_seconds': 3, 'assert': [{'device': 'test-tsp scheduler', 'attribute': 'schedule', 'value': 'Normal'}, {'device': 'test-tsp scheduler', 'attribute': 'profile', 'value': 'Sleep'}, {'device': 'test-tsp-th1', 'attribute': 'heatingSetpoint', 'value': 18.0, 'tolerance': 0.05}]}]
RUNTIME_BUDGET_SECONDS = 370

# ── Stdin args ────────────────────────────────────────────────────────
hub_name_arg, instance_id_arg, config_file, cookie_jar_path, project_root = sys.argv[1:6]

# scripts/lib/logsocket.py provides LogCapture (log lines).
# scripts/lib/eventsocket.py provides EventCapture (device events).
sys.path.insert(0, f"{project_root}/scripts/lib")
from logsocket import LogCapture
from eventsocket import EventCapture

GREEN = "\033[32m"; RED = "\033[31m"; YELLOW = "\033[33m"
CYAN = "\033[36m"; DIM = "\033[2m"; RESET = "\033[0m"

passed = failed = warnings = 0
start_time = time.time()

def ok(msg):
    global passed; passed += 1
    print(f"  {GREEN}[PASS]{RESET} {msg}")

def fail(msg):
    global failed; failed += 1
    print(f"  {RED}[FAIL]{RESET} {msg}")

def warn(msg):
    global warnings; warnings += 1
    print(f"  {YELLOW}[WARN]{RESET} {msg}")

def info(msg):
    print(f"  {DIM}{msg}{RESET}")

def section(msg):
    print(f"\n{CYAN}--- {msg} ---{RESET}")

def die(msg, code=2):
    print(f"{RED}{msg}{RESET}")
    sys.exit(code)

# ── Step 1: Load config and select hub ───────────────────────────────
try:
    with open(config_file) as f:
        config = json.load(f)
except FileNotFoundError:
    die(f"Config not found: {config_file}")

hub_name = hub_name_arg or config.get("default_hub", "")
hub = config.get("hubs", {}).get(hub_name)
if not hub:
    die(f"Hub '{hub_name}' not found in .hubitat.json")

hub_ip   = hub["hub_ip"]
username = hub.get("username")
password = hub.get("password")

cj = http.cookiejar.MozillaCookieJar(cookie_jar_path)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(path, timeout=15):
    url = f"http://{hub_ip}{path}"
    try:
        with opener.open(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

if username and password:
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        opener.open(f"http://{hub_ip}/login", data, timeout=10)
    except Exception as e:
        die(f"Hub auth failed: {e}")

info(f"Hub: {hub_name} ({hub_ip})")
info(f"Runtime budget: ~{RUNTIME_BUDGET_SECONDS}s")

# ── Step 2: Discover app-under-test instance ─────────────────────────
section("Rig discovery")

instance_id = int(instance_id_arg) if instance_id_arg else None

apps_list = fetch("/hub2/appsList")
if not apps_list or "apps" not in apps_list:
    die("Could not fetch /hub2/appsList")

def walk_apps(entries, hits):
    for entry in entries:
        d = entry.get("data") or {}
        if isinstance(d, dict):
            hits.append(d)
        for child in entry.get("children") or []:
            walk_apps([child], hits)
    return hits

all_apps = walk_apps(apps_list["apps"], [])
candidates = [a for a in all_apps if a.get("type") == APP_TYPE_NAME]

# /hub2/appsList exposes the app's label as `data.name` (data.label is always null).
def app_label(a): return a.get("name")

if instance_id is None:
    matching = [a for a in candidates if app_label(a) == APP_INSTANCE_LABEL]
    if len(matching) == 1:
        instance_id = matching[0].get("id")
    elif len(matching) == 0:
        die(f"No '{APP_TYPE_NAME}' instance with label '{APP_INSTANCE_LABEL}' on {hub_name}. "
            f"Provision the test rig the spec describes, then re-run.")
    else:
        die(f"Multiple '{APP_TYPE_NAME}' instances labelled '{APP_INSTANCE_LABEL}'; "
            f"pass an explicit ID as the second positional argument.")
else:
    # Explicit ID — must correspond to an existing instance of the right type.
    if instance_id not in [a.get("id") for a in candidates]:
        die(f"Instance id={instance_id} is not a '{APP_TYPE_NAME}' on {hub_name}.")

if instance_id is None:
    die(f"Could not resolve {APP_TYPE_NAME} instance.")

ok(f"App-under-test: {APP_TYPE_NAME} '{APP_INSTANCE_LABEL}' id={instance_id}")

# ── Step 3: Discover Maker API instance and access token ─────────────
maker_apps = [a for a in all_apps if app_label(a) == MAKER_API_LABEL]
if not maker_apps:
    die(f"Maker API instance '{MAKER_API_LABEL}' not found. Provision the test rig the spec describes.")
maker_id = maker_apps[0]["id"]

cfg = fetch(f"/installedapp/configure/json/{maker_id}")
access_token = None
if cfg and "configPage" in cfg:
    for s in cfg["configPage"].get("sections") or []:
        for item in s.get("body") or []:
            for field in ("description", "url"):
                m = re.search(r'access_token=([a-f0-9-]+)', item.get(field, ""))
                if m:
                    access_token = m.group(1)
                    break
            if access_token: break
        if access_token: break

if not access_token:
    die(f"No access_token on Maker API '{MAKER_API_LABEL}'. Is OAuth enabled?")

api_base = f"http://{hub_ip}/apps/api/{maker_id}"
ok(f"Maker API: '{MAKER_API_LABEL}' id={maker_id}")

def maker_req(url):
    """Build a request that carries the token in the Authorization header rather
    than in the URL, keeping it out of hub and proxy access logs. The platform
    matches the scheme case-sensitively: it must be exactly "Bearer"."""
    return urllib.request.Request(url, headers={"Authorization": f"Bearer {access_token}"})

# ── Step 4: Resolve device labels to IDs ─────────────────────────────
def maker_get(path, timeout=15):
    url = f"{api_base}{path}"
    try:
        with urllib.request.urlopen(maker_req(url), timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"_error": e.code}
    except Exception as e:
        return {"_error": str(e)}

def maker_send(device_id, command, args=None, timeout=15):
    """Drive a device via Maker API. Returns True on HTTP 200.
    `args` is an optional list of command arguments, sent as one secondary
    path segment joined by commas (e.g. /devices/{id}/setLevel/50 or
    /devices/{id}/holdProfile/Home,next). Maker API answers 404 when the
    arguments are separate path segments."""
    path = f"/devices/{device_id}/{command}"
    if args:
        path += "/" + ",".join(urllib.parse.quote(str(a), safe='') for a in args)
    url = f"{api_base}{path}"
    try:
        with urllib.request.urlopen(maker_req(url), timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False

def app_button(button_name, app_id=None, timeout=15):
    """Click a button-type preference on an installed app. Invokes the
    app's appButtonHandler(String btn). Defaults to the app under test."""
    target = app_id if app_id is not None else instance_id
    body = urllib.parse.urlencode({
        "id": str(target),
        "name": button_name,
        f"settings[{button_name}]": "clicked",
        f"{button_name}.type": "button",
    }).encode()
    req = urllib.request.Request(f"http://{hub_ip}/installedapp/btn", data=body)
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False

def _unset(v):
    return v is None or v == "[]" or v == ""

def _page_with_input(target, name):
    """Config JSON of the page that declares input `name`: the main page, or a
    page an href on the main page links to. Returns (cfg, error)."""
    main = fetch(f"/installedapp/configure/json/{target}")
    if not main or "configPage" not in main:
        return None, f"could not read app {target} config"
    def declares(cfg):
        return name in {i["name"] for sec in cfg["configPage"].get("sections", []) for i in sec.get("input", [])}
    if declares(main):
        return main, None
    for sec in main["configPage"].get("sections", []):
        for b in sec.get("body", []):
            if b.get("element") == "href" and b.get("page") and not b.get("url"):
                sub = fetch(f"/installedapp/configure/json/{target}/{b['page']}")
                if sub and "configPage" in sub and declares(sub):
                    return sub, None
    return None, f"app {target} has no input '{name}' on its main page or the pages it links to"

def app_settings(changes, app_id=None, timeout=15):
    """Save settings on an installed app's page (runs updated()). The input may
    live on the main page or on a page the main page links to. Applies
    `changes` one at a time, in order, so an input that only renders once an
    earlier one is set (e.g. trace under debug) can follow it. Every other
    setting on that page is echoed unchanged. Returns an error string, or None
    on success."""
    target = app_id if app_id is not None else instance_id
    for name, want in changes.items():
        cfg, err = _page_with_input(target, name)
        if err:
            return err
        page, current = cfg["configPage"], cfg.get("settings") or {}
        inputs = [i for sec in page.get("sections", []) for i in sec.get("input", [])]
        sub_page = page.get("name", "mainPage") != "mainPage"
        fields = [("_action_update", "Done"), ("formAction", "update"), ("id", str(target)),
                  ("version", str(cfg["app"].get("version", 1))), ("appTypeId", ""),
                  ("appTypeName", ""), ("currentPage", page.get("name", "mainPage")),
                  ("pageBreadcrumbs", '["mainPage"]' if sub_page else "[]")]
        for sec in page.get("sections", []):
            for b in sec.get("body", []):
                if b.get("element") == "label":
                    fields += [(f"{b['name']}.type", "text"), (b["name"], cfg["app"].get("label") or "")]
        for i in inputs:
            n, typ = i["name"], i.get("type", "")
            if typ in ("button", "paragraph", "href"):
                continue
            fields += [(f"{n}.type", typ), (f"{n}.multiple", "true" if i.get("multiple") else "false")]
            value = want if n == name else current.get(n)
            if isinstance(value, dict):
                value = list(value.keys())
            if typ.startswith(("capability.", "device.")):
                ids = [str(v) for v in (value or [])] if not _unset(value) else []
                if ids:
                    fields.append((f"settings[{n}]", ",".join(ids)))
                fields += [("deviceList", n), ("", "")]
                continue
            if _unset(value):
                continue
            if isinstance(value, list):                 # enum multiple: JSON array string
                fields.append((f"settings[{n}]", json.dumps([str(v) for v in value])))
                continue
            if typ == "bool":
                fields.append((f"checkbox[{n}]", "on"))
                value = "true" if str(value).lower() == "true" else "false"
            fields.append((f"settings[{n}]", str(value)))
        fields += [("referrer", f"http://{hub_ip}/installedapp/list"),
                   ("url", f"http://{hub_ip}/installedapp/configure/{target}/{page.get('name')}"),
                   ("_cancellable", "false")]
        req = urllib.request.Request(f"http://{hub_ip}/installedapp/update/json",
                                     data=urllib.parse.urlencode(fields).encode())
        req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try:
            with opener.open(req, timeout=timeout) as r:
                resp = json.loads(r.read().decode())
        except Exception as e:
            return f"save of '{name}' failed: {e}"
        if not isinstance(resp, dict) or resp.get("status") != "success":
            return f"save of '{name}' rejected: {resp}"
        after = (fetch(f"/installedapp/configure/json/{target}") or {}).get("settings") or {}
        got = after.get(name)
        if isinstance(want, list):
            if isinstance(got, str):
                try: got = json.loads(got)
                except ValueError: pass
            if sorted(map(str, got or [])) != sorted(map(str, want)):
                return f"'{name}' did not land: wanted {want}, hub has {got}"
        elif str(got).lower() != str(want).lower():
            return f"'{name}' did not land: wanted {want}, hub has {after.get(name)}"
    return None

devices_resp = maker_get("/devices")
if not isinstance(devices_resp, list):
    die(f"Maker API /devices returned unexpected payload: {devices_resp}")

label_to_id = {}
for d in devices_resp:
    label = d.get("label") or d.get("name")
    if label:
        label_to_id[label] = str(d.get("id"))

required_labels = set(INPUT_DEVICE_LABELS + OUTPUT_DEVICE_LABELS)
missing = required_labels - set(label_to_id.keys())
if missing:
    die(f"Maker API '{MAKER_API_LABEL}' is missing devices: {sorted(missing)}. "
        f"Wire the test devices as the spec describes, then re-run.")

ok(f"Resolved {len(required_labels)} test devices via Maker API")

# ── Step 5: Helper — read current attribute ──────────────────────────
def current_attribute(device_label, attr_name):
    dev_id = label_to_id[device_label]
    d = maker_get(f"/devices/{dev_id}")
    if not isinstance(d, dict):
        return None
    # Maker API returns attributes either as a list or a dict
    attrs = d.get("attributes")
    if isinstance(attrs, list):
        for a in attrs:
            if a.get("name") == attr_name:
                return a.get("currentValue")
    elif isinstance(attrs, dict):
        return attrs.get(attr_name)
    return None

# ── Step 6: Run cases ─────────────────────────────────────────────────
# Each case opens a LogCapture window around its actions + assertions.
# After the window closes, an implicit guard fails the case if the app
# under test (APP_INSTANCE_LABEL) emitted any warn/error log that the
# spec didn't explicitly allow. Setup runs *outside* the window — only
# the "interesting" hub activity is checked.
hub_creds = (hub.get("username"), hub.get("password"))
LOG_GUARD_LEVELS = ["warn", "error"]

for case in CASES:
    name = case.get("name", "<unnamed>")
    section(f"Case: {name}")

    # `command_spacing_seconds`: pause between consecutive Maker API
    # commands within the same case. Hubitat coalesces same-device events
    # fired <1s apart, so apps that rely on each event being seen (e.g.
    # moving-average filters, debouncers) need explicit spacing. Default
    # 0 keeps fast tests fast.
    spacing = float(case.get("command_spacing_seconds", 0))

    def run_step(step, kind):
        # Four step shapes:
        #   - device command: { device: <label>, command: <name>, args: [...] }
        #   - app button:     { button: <name> [, target_app: <label or id>] }
        #   - app settings:   { settings: { <input>: <value>, ... } }  (saved in order)
        #   - hub mode:       { mode: <mode name> }  (via Maker API)
        # target_app defaults to the app under test.
        if "settings" in step:
            err = app_settings(step["settings"])
            if err:
                warn(f"{kind}: <app> settings {step['settings']} ({err})")
            else:
                info(f"{kind}: <app> settings {step['settings']}")
            return
        if "button" in step:
            btn = step["button"]
            target = step.get("target_app")  # default → app under test
            target_id = target if (target is None or isinstance(target, int)) else None
            # If target is a string, the caller can resolve before calling;
            # for the common case (button on the SUT), leave it None.
            ok_call = app_button(btn, app_id=target_id)
            arrow = f"button({btn})"
            label = "<app>" if target is None else f"<app:{target}>"
            if not ok_call:
                warn(f"{kind}: {label} ← {arrow} (HTTP non-200)")
            else:
                info(f"{kind}: {label} ← {arrow}")
            return
        if "mode" in step:
            want = step["mode"]
            modes = maker_get("/modes")
            if not isinstance(modes, list):
                warn(f"{kind}: could not list hub modes ({modes})")
                return
            mid = next((str(m.get("id")) for m in modes if m.get("name") == want), None)
            if mid is None:
                warn(f"{kind}: hub mode {want!r} not found")
            else:
                res = maker_get(f"/modes/{mid}")
                if isinstance(res, dict) and "_error" in res:
                    warn(f"{kind}: hub mode ← {want} failed ({res['_error']})")
                else:
                    info(f"{kind}: hub mode ← {want}")
            return
        # Otherwise: device command
        label = step["device"]
        cmd = step["command"]
        args = step.get("args") or []
        ok_call = maker_send(label_to_id[label], cmd, args=args)
        argstr = ('(' + ','.join(str(x) for x in args) + ')') if args else ''
        if not ok_call:
            warn(f"{kind}: {label} ← {cmd}{argstr} (Maker API non-200)")
        else:
            info(f"{kind}: {label} ← {cmd}{argstr}")

    # 6a. setup (idempotent reset; not checked against the log guard)
    setup_steps = case.get("setup") or []
    for i, step in enumerate(setup_steps):
        run_step(step, "setup")
        if spacing and i < len(setup_steps) - 1:
            time.sleep(spacing)
    if setup_steps:
        # Let setup commands propagate before the test action.
        time.sleep(min(case.get("setup_wait_seconds", 1), 5))

    # 6b. Open the log AND event capture windows around actions + assertions.
    # LogCapture observes app log lines (info/warn/error/debug); EventCapture
    # observes device-state events (attribute transitions + values). Specs
    # use whichever is the least-fragile observation for each case:
    # `assert_logs` for log-emitting apps, `assert_events` for state-change
    # apps (the latter avoids breakage when log wording changes).
    with LogCapture(hub_ip=hub_ip, username=hub_creds[0], password=hub_creds[1]) as cap, \
         EventCapture(hub_ip=hub_ip, username=hub_creds[0], password=hub_creds[1]) as evt_cap:
        # actions (test trigger)
        action_steps = case.get("actions") or []
        for i, step in enumerate(action_steps):
            run_step(step, "action")
            if spacing and i < len(action_steps) - 1:
                time.sleep(spacing)

        # wait for the app to react
        wait_s = case.get("wait_seconds", 2)
        time.sleep(wait_s)

        # attribute assertions (read device state via Maker API; capture still open)
        for a in case.get("assert") or []:
            label = a["device"]
            attr = a["attribute"]
            want = a["value"]
            tol = a.get("tolerance")
            got = current_attribute(label, attr)
            if tol is None:
                # Default: exact string match (works for discrete attributes)
                if str(got) == str(want):
                    ok(f"{label}.{attr} = {got}")
                else:
                    fail(f"{label}.{attr} expected {want!r}, got {got!r}")
            else:
                # Numeric compare with tolerance — use for floats/aggregates
                try:
                    got_f = float(got); want_f = float(want)
                except (TypeError, ValueError):
                    fail(f"{label}.{attr} expected {want} ±{tol}, got non-numeric {got!r}")
                    continue
                if abs(got_f - want_f) <= float(tol):
                    ok(f"{label}.{attr} = {got} (≈ {want} ±{tol})")
                else:
                    fail(f"{label}.{attr} expected {want} ±{tol}, got {got_f}")

        # log assertions (against the captured /logsocket stream — still open).
        # Use these for apps whose primary observable is a log line, not an
        # attribute change (e.g. event loggers, anomaly detectors).
        for la in case.get("assert_logs") or []:
            pattern = la.get("pattern")
            level   = la.get("level")
            source  = la.get("source", APP_INSTANCE_LABEL)
            negate  = bool(la.get("negate", False))
            matched = cap.matches(pattern, level=level, source=source)
            level_str  = f" level={level}" if level else ""
            source_str = f" source={source!r}"
            if (matched and not negate) or (not matched and negate):
                kind = "no-match" if negate else "match"
                ok(f"log {kind}: {pattern!r}{level_str}{source_str}")
            else:
                kind = "no-match" if negate else "match"
                # On miss, show up to 3 captured lines from the same source for diagnosis.
                near = cap.find_all(None, source=source)[:3]
                near_str = "; ".join(f"[{m.get('level')}] {(m.get('msg') or '')[:80]}" for m in near)
                fail(f"log {kind} expected for {pattern!r}{level_str}{source_str}"
                     f"; recent from {source!r}: [{near_str}]")

        # event assertions (against the captured /eventsocket stream — still open).
        # Prefer these over log assertions when the observable is a device-state
        # transition: events are tied to capability attributes, which are far
        # more stable than log wording across refactors.
        for ea in case.get("assert_events") or []:
            attribute = ea.get("attribute")
            value     = ea.get("value")
            source    = ea.get("source")
            pattern   = ea.get("pattern")
            negate    = bool(ea.get("negate", False))
            matched = evt_cap.matches(pattern=pattern, attribute=attribute,
                                       value=value, source=source)
            attr_str = f" attribute={attribute!r}" if attribute else ""
            val_str  = f" value={value!r}" if value is not None else ""
            src_str  = f" source={source!r}" if source is not None else ""
            pat_str  = f" pattern={pattern!r}" if pattern else ""
            sig = f"{attr_str}{val_str}{src_str}{pat_str}"
            if (matched and not negate) or (not matched and negate):
                kind = "no-match" if negate else "match"
                ok(f"event {kind}:{sig}")
            else:
                kind = "no-match" if negate else "match"
                # On miss, show up to 3 recent events from the same source for diagnosis.
                near = evt_cap.find_all(source=source)[:3] if source is not None else evt_cap.events[:3]
                near_str = "; ".join(
                    f"{m.get('displayName')!r}.{m.get('name')}={m.get('value')!r}"
                    for m in near)
                fail(f"event {kind} expected for{sig}; recent: [{near_str}]")

    # 6c. Implicit log guard — fails on unexpected warn/error from the SUT.
    # Spec opt-outs:
    #   allow_warnings: true        → disable the guard for this case
    #   allow_log_patterns: [regex] → whitelist matching lines (substring re.search)
    if case.get("allow_warnings"):
        info("log guard skipped (allow_warnings: true)")
    else:
        allow_patterns = [re.compile(p) for p in (case.get("allow_log_patterns") or [])]
        suspect = cap.find_all(pattern=None, level=LOG_GUARD_LEVELS, source=APP_INSTANCE_LABEL)
        unexpected = [m for m in suspect
                      if not any(r.search(m.get("msg") or "") for r in allow_patterns)]
        if unexpected:
            for m in unexpected[:3]:
                fail(f"unexpected {m.get('level')} from {m.get('name')}: {m.get('msg')!r}")
            if len(unexpected) > 3:
                info(f"... and {len(unexpected) - 3} more unexpected log line(s)")
        else:
            ok(f"no unexpected warn/error from {APP_INSTANCE_LABEL}")

# ── Trailer ───────────────────────────────────────────────────────────
elapsed = int(time.time() - start_time)
print(f"\n{passed} passed, {failed} failed, {warnings} warnings (in {elapsed}s)")
if elapsed > RUNTIME_BUDGET_SECONDS:
    print(f"{YELLOW}NOTE: exceeded declared runtime budget of {RUNTIME_BUDGET_SECONDS}s{RESET}")

sys.exit(0 if failed == 0 else 1)
PYTHON_SCRIPT
