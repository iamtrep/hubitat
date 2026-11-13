# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""Harness for Mode 1 behaviour tests (TESTING.md section 2.4).

A generated `tests/test-<app>.sh` holds only the data rendered from its spec
and calls `run()`. Everything that talks to the hub and judges a case is here,
so a fix reaches every test at once.

A case passes only when every step it sent was accepted by the hub: a refused
command, button, settings save or mode change is a failure, and so is an
`await` that times out (unless the step says `optional: true`). A capture
socket that closes before the case ends is a failure too, since assertions
that expect no line or no event would pass on an empty capture.
"""
from __future__ import annotations

import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GREEN = "\033[32m"; RED = "\033[31m"; YELLOW = "\033[33m"
CYAN = "\033[36m"; DIM = "\033[2m"; RESET = "\033[0m"

LOG_GUARD_LEVELS = ["warn", "error"]
# logVer announces a new code version at warn level, once after each push; not a fault.
VERSION_NOTICE = r"New version: \S+ \(was: "


class Tally:
    """Counts and prints results. The exit code comes from `failed`."""

    def __init__(self, out=None):
        self.passed = self.failed = self.warnings = 0
        self._out = out or sys.stdout

    def _p(self, text):
        print(text, file=self._out)

    def ok(self, msg):
        self.passed += 1
        self._p(f"  {GREEN}[PASS]{RESET} {msg}")

    def fail(self, msg):
        self.failed += 1
        self._p(f"  {RED}[FAIL]{RESET} {msg}")

    def warn(self, msg):
        self.warnings += 1
        self._p(f"  {YELLOW}[WARN]{RESET} {msg}")

    def info(self, msg):
        self._p(f"  {DIM}{msg}{RESET}")

    def section(self, msg):
        self._p(f"\n{CYAN}--- {msg} ---{RESET}")


def die(msg, code=2):
    print(f"{RED}{msg}{RESET}")
    sys.exit(code)


# ── Pure helpers (unit-tested in scripts/tests/test_behavior_harness.py) ──

def label_source(label):
    """Source filter matching an app or device label literally. The capture
    classes treat a non-numeric source as a regex, so a label such as
    "Mirror Switch (test)" would otherwise match nothing."""
    return re.escape(label)


def log_source(source, app_label):
    """Source for a log assertion: the app under test by default. A spec may
    give a regex on the log name, or a numeric id."""
    if source is None or source == app_label:
        return label_source(app_label)
    return source


def event_source(source, label_to_id):
    """Source for an event assertion. A known device label becomes its id, so
    `test-pump` does not also match `test-pump-active`. Anything else is passed
    on as given (a regex on the display name, or a numeric id)."""
    if source is not None and str(source) in label_to_id:
        return int(label_to_id[str(source)])
    return source


def unexpected_logs(lines, allow_patterns):
    """Lines of the app under test at warn or error that no pattern allows."""
    allowed = [re.compile(p) for p in [VERSION_NOTICE] + list(allow_patterns or [])]
    return [m for m in lines if not any(r.search(m.get("msg") or "") for r in allowed)]


def walk_apps(entries, hits):
    for entry in entries:
        d = entry.get("data") or {}
        if isinstance(d, dict):
            hits.append(d)
        for child in entry.get("children") or []:
            walk_apps([child], hits)
    return hits


# /hub2/appsList exposes the app's label as `data.name` (data.label is always null).
def app_label(a):
    return a.get("name")


def _unset(v):
    return v is None or v == "[]" or v == ""


# ── Hub access ────────────────────────────────────────────────────────────

class Rig:
    """The test rig on one hub: the app under test, its Maker API instance and
    the test devices. Every method that sends something returns whether the hub
    accepted it; `run_step` turns a refusal into a failure."""

    def __init__(self, hub_ip, opener, instance_id, all_apps):
        self.hub_ip = hub_ip
        self.opener = opener
        self.instance_id = instance_id
        self.all_apps = all_apps
        self.api_base = None
        self.access_token = None
        self.label_to_id = {}

    def fetch(self, path, timeout=15):
        try:
            with self.opener.open(f"http://{self.hub_ip}{path}", timeout=timeout) as r:
                return json.loads(r.read().decode())
        except Exception:
            return None

    def maker_req(self, url):
        """The token travels in the Authorization header, not the URL, which
        keeps it out of hub and proxy access logs. The platform accepts the
        scheme in any case from 2.5.2.120 (exactly "Bearer" before)."""
        return urllib.request.Request(url, headers={"Authorization": f"Bearer {self.access_token}"})

    def maker_get(self, path, timeout=15):
        try:
            with urllib.request.urlopen(self.maker_req(f"{self.api_base}{path}"), timeout=timeout) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return {"_error": e.code}
        except Exception as e:
            return {"_error": str(e)}

    def maker_send(self, device_id, command, args=None, timeout=15):
        """Drive a device via Maker API. Returns True on HTTP 200. Arguments go
        in one path segment joined by commas (/devices/{id}/setLevel/50,
        /devices/{id}/holdProfile/Home,next); Maker API answers 404 when they
        are separate segments."""
        path = f"/devices/{device_id}/{command}"
        if args:
            path += "/" + ",".join(urllib.parse.quote(str(a), safe='') for a in args)
        try:
            with urllib.request.urlopen(self.maker_req(f"{self.api_base}{path}"), timeout=timeout) as r:
                return r.status == 200
        except Exception:
            return False

    def current_attribute(self, device_label, attr_name):
        d = self.maker_get(f"/devices/{self.label_to_id[device_label]}")
        if not isinstance(d, dict):
            return None
        attrs = d.get("attributes")      # a list or a dict, depending on the route
        if isinstance(attrs, list):
            for a in attrs:
                if a.get("name") == attr_name:
                    return a.get("currentValue")
        elif isinstance(attrs, dict):
            return attrs.get(attr_name)
        return None

    def set_mode(self, name):
        """Returns an error string, or None once the hub took the mode."""
        modes = self.maker_get("/modes")
        if not isinstance(modes, list):
            return f"could not list hub modes ({modes})"
        mid = next((str(m.get("id")) for m in modes if m.get("name") == name), None)
        if mid is None:
            return f"hub mode {name!r} not found"
        res = self.maker_get(f"/modes/{mid}")
        if isinstance(res, dict) and "_error" in res:
            return f"hub mode {name} refused ({res['_error']})"
        return None

    def app_id_for(self, target):
        """Installed-app id for a `target_app` value: None is the app under
        test, an int is an id, a string is an app label. Returns None when a
        label matches no app or several."""
        if target is None:
            return self.instance_id
        if isinstance(target, int):
            return target
        ids = [a.get("id") for a in self.all_apps if app_label(a) == target]
        return ids[0] if len(ids) == 1 else None

    def app_button(self, button_name, app_id=None, timeout=15):
        """Click a button-type preference on an installed app, which invokes
        its appButtonHandler(String btn)."""
        target = app_id if app_id is not None else self.instance_id
        body = urllib.parse.urlencode({
            "id": str(target),
            "name": button_name,
            f"settings[{button_name}]": "clicked",
            f"{button_name}.type": "button",
        }).encode()
        req = urllib.request.Request(f"http://{self.hub_ip}/installedapp/btn", data=body)
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with self.opener.open(req, timeout=timeout) as r:
                return r.status == 200
        except Exception:
            return False

    def _page_with_input(self, target, name):
        """Config JSON of the page that declares input `name`: the main page, or
        a page an href on the main page links to. Returns (cfg, error)."""
        main = self.fetch(f"/installedapp/configure/json/{target}")
        if not main or "configPage" not in main:
            return None, f"could not read app {target} config"

        def declares(cfg):
            return name in {i["name"] for sec in cfg["configPage"].get("sections", [])
                            for i in sec.get("input", [])}
        if declares(main):
            return main, None
        for sec in main["configPage"].get("sections", []):
            for b in sec.get("body", []):
                if b.get("element") == "href" and b.get("page") and not b.get("url"):
                    sub = self.fetch(f"/installedapp/configure/json/{target}/{b['page']}")
                    if sub and "configPage" in sub and declares(sub):
                        return sub, None
        return None, f"app {target} has no input '{name}' on its main page or the pages it links to"

    def app_settings(self, changes, app_id=None, timeout=15):
        """Save settings on an installed app's page (runs updated()). Applies
        `changes` one at a time, in order, so an input that only renders once an
        earlier one is set can follow it. Every other setting on that page is
        echoed unchanged. Returns an error string, or None on success."""
        target = app_id if app_id is not None else self.instance_id
        hub_ip = self.hub_ip
        for name, want in changes.items():
            cfg, err = self._page_with_input(target, name)
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
                with self.opener.open(req, timeout=timeout) as r:
                    resp = json.loads(r.read().decode())
            except Exception as e:
                return f"save of '{name}' failed: {e}"
            if not isinstance(resp, dict) or resp.get("status") != "success":
                return f"save of '{name}' rejected: {resp}"
            after = (self.fetch(f"/installedapp/configure/json/{target}") or {}).get("settings") or {}
            got = after.get(name)
            if isinstance(want, list):
                if isinstance(got, str):
                    try:
                        got = json.loads(got)
                    except ValueError:
                        pass
                if sorted(map(str, got or [])) != sorted(map(str, want)):
                    return f"'{name}' did not land: wanted {want}, hub has {got}"
            elif str(got).lower() != str(want).lower():
                return f"'{name}' did not land: wanted {want}, hub has {after.get(name)}"
        return None


# ── Steps and assertions ──────────────────────────────────────────────────

def run_step(step, kind, rig, t, sleep=time.sleep, now=time.time):
    """Run one setup or action step. Five shapes:
      - device command: { device: <label>, command: <name>, args: [...] }
      - app button:     { button: <name> [, target_app: <label or id>] }
      - app settings:   { settings: { <input>: <value>, ... } }  (saved in order)
      - hub mode:       { mode: <mode name> }  (via Maker API)
      - await state:    { await: { device, attribute, value, timeout_seconds: 10 [, optional: true] } }
    A step the hub refuses is a failure: the case would otherwise go on to
    assert against a state the test never produced."""
    if "await" in step:
        a = step["await"]
        label, attr, want = a["device"], a["attribute"], str(a["value"])
        timeout = float(a.get("timeout_seconds", 10))
        deadline = now() + timeout
        got = rig.current_attribute(label, attr)
        while str(got) != want and now() < deadline:
            sleep(0.25)
            got = rig.current_attribute(label, attr)
        if str(got) == want:
            t.info(f"{kind}: {label}.{attr} = {got}")
        else:
            report = t.warn if a.get("optional") else t.fail
            report(f"{kind}: {label}.{attr} still {got!r}, not {want!r}, after {timeout:g}s")
        return
    if "settings" in step:
        err = rig.app_settings(step["settings"])
        if err:
            t.fail(f"{kind}: <app> settings {step['settings']} ({err})")
        else:
            t.info(f"{kind}: <app> settings {step['settings']}")
        return
    if "button" in step:
        btn, target = step["button"], step.get("target_app")
        label = "<app>" if target is None else f"<app:{target}>"
        target_id = rig.app_id_for(target)
        if target_id is None:
            t.fail(f"{kind}: {label} ← button({btn}): no single app with that label")
        elif not rig.app_button(btn, app_id=target_id):
            t.fail(f"{kind}: {label} ← button({btn}) (HTTP non-200)")
        else:
            t.info(f"{kind}: {label} ← button({btn})")
        return
    if "mode" in step:
        err = rig.set_mode(step["mode"])
        if err:
            t.fail(f"{kind}: {err}")
        else:
            t.info(f"{kind}: hub mode ← {step['mode']}")
        return
    label, cmd, args = step["device"], step["command"], step.get("args") or []
    argstr = ('(' + ','.join(str(x) for x in args) + ')') if args else ''
    if rig.maker_send(rig.label_to_id[label], cmd, args=args):
        t.info(f"{kind}: {label} ← {cmd}{argstr}")
    else:
        t.fail(f"{kind}: {label} ← {cmd}{argstr} (Maker API non-200)")


def check_attributes(case, rig, t):
    for a in case.get("assert") or []:
        label, attr, want, tol = a["device"], a["attribute"], a["value"], a.get("tolerance")
        got = rig.current_attribute(label, attr)
        if tol is None:
            if str(got) == str(want):
                t.ok(f"{label}.{attr} = {got}")
            else:
                t.fail(f"{label}.{attr} expected {want!r}, got {got!r}")
            continue
        try:
            got_f = float(got); want_f = float(want)
        except (TypeError, ValueError):
            t.fail(f"{label}.{attr} expected {want} ±{tol}, got non-numeric {got!r}")
            continue
        if abs(got_f - want_f) <= float(tol):
            t.ok(f"{label}.{attr} = {got} (≈ {want} ±{tol})")
        else:
            t.fail(f"{label}.{attr} expected {want} ±{tol}, got {got_f}")


def check_logs(case, cap, app_instance_label, t):
    for la in case.get("assert_logs") or []:
        pattern, level = la.get("pattern"), la.get("level")
        shown = la.get("source", app_instance_label)
        source = log_source(la.get("source"), app_instance_label)
        negate = bool(la.get("negate", False))
        matched = cap.matches(pattern, level=level, source=source)
        level_str = f" level={level}" if level else ""
        word = "no-match" if negate else "match"
        if matched != negate:
            t.ok(f"log {word}: {pattern!r}{level_str} source={shown!r}")
        else:
            near = cap.find_all(None, source=source)[:3]
            near_str = "; ".join(f"[{m.get('level')}] {(m.get('msg') or '')[:80]}" for m in near)
            t.fail(f"log {word} expected for {pattern!r}{level_str} source={shown!r}"
                   f"; recent from {shown!r}: [{near_str}]")


def check_events(case, evt_cap, label_to_id, t):
    for ea in case.get("assert_events") or []:
        attribute, value, pattern = ea.get("attribute"), ea.get("value"), ea.get("pattern")
        shown = ea.get("source")
        source = event_source(shown, label_to_id)
        negate = bool(ea.get("negate", False))
        matched = evt_cap.matches(pattern=pattern, attribute=attribute, value=value, source=source)
        sig = ((f" attribute={attribute!r}" if attribute else "")
               + (f" value={value!r}" if value is not None else "")
               + (f" source={shown!r}" if shown is not None else "")
               + (f" pattern={pattern!r}" if pattern else ""))
        word = "no-match" if negate else "match"
        if matched != negate:
            t.ok(f"event {word}:{sig}")
        else:
            near = evt_cap.find_all(source=source)[:3] if source is not None else evt_cap.events[:3]
            near_str = "; ".join(f"{m.get('displayName')!r}.{m.get('name')}={m.get('value')!r}" for m in near)
            t.fail(f"event {word} expected for{sig}; recent: [{near_str}]")


def check_log_guard(case, cap, app_instance_label, t):
    """Fails the case on a warn or error from the app under test that the spec
    did not allow. Opt-outs: `allow_warnings: true` (no guard for this case),
    `allow_log_patterns: [regex]` (re.search on the message)."""
    if case.get("allow_warnings"):
        t.info("log guard skipped (allow_warnings: true)")
        return
    suspect = cap.find_all(pattern=None, level=LOG_GUARD_LEVELS, source=label_source(app_instance_label))
    unexpected = unexpected_logs(suspect, case.get("allow_log_patterns"))
    if not unexpected:
        t.ok(f"no unexpected warn/error from {app_instance_label}")
        return
    for m in unexpected[:3]:
        t.fail(f"unexpected {m.get('level')} from {m.get('name')}: {m.get('msg')!r}")
    if len(unexpected) > 3:
        t.info(f"... and {len(unexpected) - 3} more unexpected log line(s)")


def check_captures(cap, evt_cap, t):
    """A socket that closed before the window ended saw only part of the case."""
    for what, c in (("log", cap), ("event", evt_cap)):
        if getattr(c, "closed_early", False):
            t.fail(f"{what} capture closed before the case ended; lines after that were not seen")


def run_case(case, rig, t, app_instance_label, open_captures, sleep=time.sleep):
    t.section(f"Case: {case.get('name', '<unnamed>')}")
    # Hubitat coalesces same-device events fired under a second apart, so apps
    # that need every event seen (filters, debouncers) ask for spacing.
    spacing = float(case.get("command_spacing_seconds", 0))

    def run_steps(steps, kind):
        for i, step in enumerate(steps):
            run_step(step, kind, rig, t)
            if spacing and i < len(steps) - 1:
                sleep(spacing)

    # Setup runs outside the capture window: only the actions are judged by the guard.
    setup_steps = case.get("setup") or []
    run_steps(setup_steps, "setup")
    if setup_steps or "setup_wait_seconds" in case:
        sleep(min(case.get("setup_wait_seconds", 1), 5))

    cap, evt_cap = open_captures()
    with cap, evt_cap:
        run_steps(case.get("actions") or [], "action")
        sleep(case.get("wait_seconds", 2))
        check_attributes(case, rig, t)
        check_logs(case, cap, app_instance_label, t)
        check_events(case, evt_cap, rig.label_to_id, t)
    check_captures(cap, evt_cap, t)
    check_log_guard(case, cap, app_instance_label, t)


# ── Entry point ───────────────────────────────────────────────────────────

def run(argv, *, app_type_name, app_instance_label, maker_api_label,
        input_device_labels, output_device_labels, cases, runtime_budget_seconds, slow=False):
    """Run a generated test. `argv` is [script path, repo root, *script args];
    the script args are an optional `@hub` and an optional instance id."""
    script, project_root, args = argv[0], argv[1], argv[2:]
    if slow and os.environ.get("RUN_SLOW_TESTS") != "1":
        name = os.path.basename(script).rsplit(".", 1)[0]
        print(f"  [INFO] {name} skipped: runs ~{runtime_budget_seconds}s; set RUN_SLOW_TESTS=1")
        sys.exit(0)

    hub_name_arg = next((a[1:] for a in args if a.startswith("@")), "")
    instance_id = next((int(a) for a in args if a.isdigit()), None)
    # HUBITAT_CONFIG (absolute path) points the test at a different hub config file.
    config_file = os.environ.get("HUBITAT_CONFIG") or os.path.join(project_root, ".hubitat.json")

    # The capture classes come from the repo that holds this harness.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from logsocket import LogCapture
    from eventsocket import EventCapture

    t = Tally()
    start_time = time.time()

    try:
        with open(config_file) as f:
            config = json.load(f)
    except FileNotFoundError:
        die(f"Config not found: {config_file}")
    hub_name = hub_name_arg or config.get("default_hub", "")
    hub = config.get("hubs", {}).get(hub_name)
    if not hub:
        die(f"Hub '{hub_name}' not found in the hub config")
    hub_ip, username, password = hub["hub_ip"], hub.get("username"), hub.get("password")

    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    if username and password:
        data = urllib.parse.urlencode({"username": username, "password": password}).encode()
        try:
            opener.open(f"http://{hub_ip}/login", data, timeout=10)
        except Exception as e:
            die(f"Hub auth failed: {e}")

    t.info(f"Hub: {hub_name} ({hub_ip})")
    t.info(f"Runtime budget: ~{runtime_budget_seconds}s")

    t.section("Rig discovery")
    rig = Rig(hub_ip, opener, instance_id, [])
    apps_list = rig.fetch("/hub2/appsList")
    if not apps_list or "apps" not in apps_list:
        die("Could not fetch /hub2/appsList")
    rig.all_apps = walk_apps(apps_list["apps"], [])
    candidates = [a for a in rig.all_apps if a.get("type") == app_type_name]

    if instance_id is None:
        matching = [a for a in candidates if app_label(a) == app_instance_label]
        if len(matching) == 0:
            die(f"No '{app_type_name}' instance with label '{app_instance_label}' on {hub_name}. "
                f"Provision the test rig the spec describes, then re-run.")
        if len(matching) > 1:
            die(f"Multiple '{app_type_name}' instances labelled '{app_instance_label}'; "
                f"pass an explicit ID as the second positional argument.")
        instance_id = matching[0].get("id")
    elif instance_id not in [a.get("id") for a in candidates]:
        die(f"Instance id={instance_id} is not a '{app_type_name}' on {hub_name}.")
    if instance_id is None:
        die(f"Could not resolve {app_type_name} instance.")
    rig.instance_id = instance_id
    t.ok(f"App-under-test: {app_type_name} '{app_instance_label}' id={instance_id}")

    maker_apps = [a for a in rig.all_apps if app_label(a) == maker_api_label]
    if not maker_apps:
        die(f"Maker API instance '{maker_api_label}' not found. Provision the test rig the spec describes.")
    maker_id = maker_apps[0]["id"]
    cfg = rig.fetch(f"/installedapp/configure/json/{maker_id}")
    for s in ((cfg or {}).get("configPage") or {}).get("sections") or []:
        for item in s.get("body") or []:
            for field in ("description", "url"):
                m = re.search(r'access_token=([a-f0-9-]+)', item.get(field, ""))
                if m and not rig.access_token:
                    rig.access_token = m.group(1)
    if not rig.access_token:
        die(f"No access_token on Maker API '{maker_api_label}'. Is OAuth enabled?")
    rig.api_base = f"http://{hub_ip}/apps/api/{maker_id}"
    t.ok(f"Maker API: '{maker_api_label}' id={maker_id}")

    devices_resp = rig.maker_get("/devices")
    if not isinstance(devices_resp, list):
        die(f"Maker API /devices returned unexpected payload: {devices_resp}")
    for d in devices_resp:
        label = d.get("label") or d.get("name")
        if label:
            rig.label_to_id[label] = str(d.get("id"))
    required_labels = set(input_device_labels + output_device_labels)
    missing = required_labels - set(rig.label_to_id)
    if missing:
        die(f"Maker API '{maker_api_label}' is missing devices: {sorted(missing)}. "
            f"Wire the test devices as the spec describes, then re-run.")
    t.ok(f"Resolved {len(required_labels)} test devices via Maker API")

    def open_captures():
        return (LogCapture(hub_ip=hub_ip, username=username, password=password),
                EventCapture(hub_ip=hub_ip, username=username, password=password))

    for case in cases:
        run_case(case, rig, t, app_instance_label, open_captures)

    elapsed = int(time.time() - start_time)
    print(f"\n{t.passed} passed, {t.failed} failed, {t.warnings} warnings (in {elapsed}s)")
    if elapsed > runtime_budget_seconds:
        print(f"{YELLOW}NOTE: exceeded declared runtime budget of {runtime_budget_seconds}s{RESET}")
    sys.exit(0 if t.failed == 0 else 1)
