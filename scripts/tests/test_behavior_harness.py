# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""Offline tests for scripts/lib/behavior_harness.py: what makes a case fail.

A fake rig stands in for the hub, and the real LogCapture and EventCapture
filters run over lines put into them directly (no socket is opened).
"""
import io
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import behavior_harness as h
from eventsocket import EventCapture
from logsocket import LogCapture


class FakeRig:
    def __init__(self, send_ok=True, button_ok=True, settings_err=None, mode_err=None, attr=None):
        self.send_ok, self.button_ok = send_ok, button_ok
        self.settings_err, self.mode_err, self.attr = settings_err, mode_err, attr
        self.instance_id = 10
        self.label_to_id = {"test-pump": "7", "test-pump-active": "8"}
        self.all_apps = [{"id": 10, "name": "SUT"}, {"id": 11, "name": "Other"},
                         {"id": 12, "name": "Twin"}, {"id": 13, "name": "Twin"}]
        self.buttons = []

    def maker_send(self, device_id, command, args=None): return self.send_ok
    def app_settings(self, changes): return self.settings_err
    def set_mode(self, name): return self.mode_err
    def current_attribute(self, label, attr): return self.attr
    def app_id_for(self, target): return h.Rig.app_id_for(self, target)

    def app_button(self, name, app_id=None):
        self.buttons.append(app_id)
        return self.button_ok


def tally():
    return h.Tally(out=io.StringIO())


def step(step, rig, clock=None):
    t = tally()
    ticks = iter(clock or [0, 100])
    h.run_step(step, "action", rig, t, sleep=lambda s: None, now=lambda: next(ticks))
    return t


def logs(*lines):
    cap = LogCapture(hub_ip="192.0.2.1")
    for name, level, msg in lines:
        cap._messages.append({"name": name, "level": level, "msg": msg, "id": 1})
    return cap


def events(*evts):
    cap = EventCapture(hub_ip="192.0.2.1")
    for display, dev_id, name, value in evts:
        cap._events.append({"displayName": display, "deviceId": dev_id, "name": name, "value": value})
    return cap


def test_accepted_steps_do_not_fail():
    for s in ({"device": "test-pump", "command": "on"}, {"button": "btnRun"},
              {"settings": {"x": 1}}, {"mode": "Away"}):
        t = step(s, FakeRig())
        assert (t.failed, t.warnings) == (0, 0), s


def test_refused_device_command_fails():
    assert step({"device": "test-pump", "command": "off"}, FakeRig(send_ok=False)).failed == 1


def test_refused_button_settings_and_mode_fail():
    assert step({"button": "btnRun"}, FakeRig(button_ok=False)).failed == 1
    assert step({"settings": {"x": 1}}, FakeRig(settings_err="did not land")).failed == 1
    assert step({"mode": "Away"}, FakeRig(mode_err="hub mode 'Away' not found")).failed == 1


def test_button_target_app_resolves_a_label():
    rig = FakeRig()
    assert step({"button": "b", "target_app": "Other"}, rig).failed == 0
    assert step({"button": "b", "target_app": 99}, rig).failed == 0
    assert step({"button": "b"}, rig).failed == 0
    assert rig.buttons == [11, 99, 10]


def test_button_unknown_or_ambiguous_target_app_fails_without_a_click():
    rig = FakeRig()
    assert step({"button": "b", "target_app": "Nope"}, rig).failed == 1
    assert step({"button": "b", "target_app": "Twin"}, rig).failed == 1
    assert rig.buttons == []


def test_await_timeout_fails_unless_optional():
    a = {"device": "test-pump", "attribute": "switch", "value": "on", "timeout_seconds": 1}
    assert step({"await": a}, FakeRig(attr="off")).failed == 1
    t = step({"await": dict(a, optional=True)}, FakeRig(attr="off"))
    assert (t.failed, t.warnings) == (0, 1)
    assert step({"await": a}, FakeRig(attr="on")).failed == 0


def test_log_guard_sees_a_label_with_regex_characters():
    label = "Mirror Switch (test)"
    t = tally()
    h.check_log_guard({}, logs((label, "error", "boom")), label, t)
    assert t.failed == 1
    t = tally()
    h.check_log_guard({}, logs((label, "info", "fine"), ("Other app", "error", "boom")), label, t)
    assert (t.passed, t.failed) == (1, 0)


def test_log_guard_allows_the_version_notice_and_spec_patterns():
    cap = logs(("App", "warn", "New version: 1.2 (was: 1.1)"), ("App", "warn", "UI sync rejected"))
    t = tally()
    h.check_log_guard({"allow_log_patterns": ["UI sync"]}, cap, "App", t)
    assert t.failed == 0
    t = tally()
    h.check_log_guard({}, cap, "App", t)
    assert t.failed == 1
    t = tally()
    h.check_log_guard({"allow_warnings": True}, cap, "App", t)
    assert (t.passed, t.failed) == (0, 0)


def test_default_log_assertion_source_is_the_literal_label():
    label = "Mirror Switch (test)"
    case = {"assert_logs": [{"pattern": "mirrored", "level": "info"}]}
    t = tally()
    h.check_logs(case, logs((label, "info", "mirrored on")), label, t)
    assert (t.passed, t.failed) == (1, 0)
    case = {"assert_logs": [{"pattern": "mirrored", "negate": True}]}
    t = tally()
    h.check_logs(case, logs((label, "info", "mirrored on")), label, t)
    assert t.failed == 1


def test_log_assertion_source_given_as_regex_is_kept():
    case = {"assert_logs": [{"pattern": "x", "source": "^test-.*-sensor$"}]}
    t = tally()
    h.check_logs(case, logs(("test-a-sensor", "info", "x")), "App", t)
    assert t.passed == 1


def test_event_source_label_does_not_match_a_longer_label():
    rig = FakeRig()
    case = {"assert_events": [{"attribute": "switch", "value": "on", "source": "test-pump"}]}
    t = tally()
    h.check_events(case, events(("test-pump-active", 8, "switch", "on")), rig.label_to_id, t)
    assert (t.passed, t.failed) == (0, 1)
    t = tally()
    h.check_events(case, events(("test-pump", 7, "switch", "on")), rig.label_to_id, t)
    assert (t.passed, t.failed) == (1, 0)


def test_event_source_not_a_known_label_is_a_regex():
    assert h.event_source("test-.*", {"test-pump": "7"}) == "test-.*"
    assert h.event_source(None, {"test-pump": "7"}) is None
    assert h.event_source("test-pump", {"test-pump": "7"}) == 7


def test_capture_closed_early_fails():
    cap, evt = logs(), events()
    t = tally()
    h.check_captures(cap, evt, t)
    assert t.failed == 0
    cap.closed_early = True
    h.check_captures(cap, evt, t)
    assert t.failed == 1


def test_run_case_waits_after_an_empty_setup_when_the_spec_asks():
    waits = []

    class Cap:
        closed_early = False
        events = []
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def find_all(self, *a, **k): return []
        def matches(self, *a, **k): return False

    case = {"name": "c", "setup_wait_seconds": 3, "wait_seconds": 0, "allow_warnings": True}
    h.run_case(case, FakeRig(), tally(), "App", lambda: (Cap(), Cap()), sleep=waits.append)
    assert waits == [3, 0]
    waits.clear()
    h.run_case({"name": "c", "wait_seconds": 0, "allow_warnings": True},
               FakeRig(), tally(), "App", lambda: (Cap(), Cap()), sleep=waits.append)
    assert waits == [0]


if __name__ == "__main__":
    passed = failed = 0
    for name, fn in sorted((n, f) for n, f in globals().items() if n.startswith("test_") and callable(f)):
        try:
            fn()
            print(f"[PASS] {name}")
            passed += 1
        except AssertionError as exc:
            print(f"[FAIL] {name}: {exc}")
            failed += 1
        except Exception as exc:
            print(f"[FAIL] {name}: {type(exc).__name__}: {exc}")
            failed += 1
    print(f"\n{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)
