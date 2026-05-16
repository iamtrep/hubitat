# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""Event-assertion helper for Mode 1 behavior tests.

Subscribes to a hub's ``ws://<hub>/eventsocket`` stream in a background
thread, captures every device-state event, and exposes assertion
primitives so tests can verify attribute transitions without depending
on the app under test emitting matching log lines.

Complement to :mod:`scripts.lib.logsocket`. The two streams answer
different questions:

  - ``LogCapture`` (/logsocket): "did the app emit this *log line*?"
    Useful when the test's expected outcome is an explicit
    ``log.info("...")`` / ``log.warn("...")`` from the app under test.

  - ``EventCapture`` (/eventsocket): "did this *attribute* transition,
    and what value did it carry?" Useful when the test's expected
    outcome is a device-state change — especially for apps that drive
    devices without verbose logging, or when the test wants to assert
    on the *value* of a transition (e.g. "humidity became 75").

Usage::

    from eventsocket import EventCapture

    with EventCapture(hub_ip="192.168.1.86") as cap:
        maker_send(fan_id, "on")
        # wait_for blocks until a matching event arrives (or timeout)
        cap.wait_for(attribute="switch", value="on",
                     source="test-hfc-fan", timeout=5)

    # After the context exits, capture is closed and assertions reflect
    # the final list of captured events.
    assert cap.matches(attribute="switch", value="on", source="test-hfc-fan")
    assert cap.count(attribute="humidity", source=273) == 3

Authentication:
    On secured hubs, pass ``username=`` / ``password=`` (or a pre-built
    ``urllib.request.OpenerDirector`` via ``opener=`` whose cookiejar
    holds a valid ``HUBSESSION``). On open hubs (e.g. maison-pro) omit
    both — the hub's /eventsocket is unauthenticated.

Message schema (Hubitat /eventsocket, JSON per line)::

    {"source":"DEVICE",
     "name":"humidity",
     "displayName":"test-hfc-bath",
     "value":"61",
     "type":"null",
     "unit":"null",
     "deviceId":273,
     "hubId":0,
     "installedAppId":0,
     "descriptionText":"test-hfc-bath was set to 61%"}

  - ``source`` is the event origin class (``DEVICE``, ``APP``,
    ``LOCATION``, etc.). The :class:`EventCapture` ``source=`` filter
    matches against ``deviceId`` (when int) or ``displayName`` (when
    str regex) — it does NOT match the ``source`` field directly.
  - ``value`` is always a string in the wire format. The ``value=``
    filter accepts either a plain ``str`` (exact match) or a compiled
    ``re.Pattern`` (``re.search`` against the value string).
  - All numeric attributes (``humidity``, ``illuminance``,
    ``temperature``, ``level``) arrive as numeric strings — compare as
    strings, or pass a regex if you want ranges.

Filter semantics (matches / no_matches / count / find_all / wait_for):
    pattern   : regex matched via re.search against ``descriptionText``.
                ``None`` matches every captured event (use other filters).
    attribute : regex matched via re.search against ``name``. ``None``
                matches any attribute.
    value     : ``str`` → exact match against ``value``. ``re.Pattern``
                → ``re.search`` against ``value``. ``None`` → any value.
    source    : ``int`` → match ``deviceId``. ``str`` → regex on
                ``displayName``. ``None`` → any source.
"""
from __future__ import annotations

import http.cookiejar
import json
import re
import threading
import time
import urllib.parse
import urllib.request
from typing import Iterable, List, Optional, Pattern, Union

import websockets.exceptions
from websockets.sync.client import connect


# Type aliases mirror logsocket.py for consistency.
ValueFilter = Union[str, Pattern, None]
Source = Union[str, int, None]


class EventCapture:
    """Context manager that captures /eventsocket messages in a background thread.

    Thread-safety: the captured-event list is protected by a lock. Assertion
    methods read a snapshot, so they can be called from the main thread while
    the capture is still running.
    """

    def __init__(
        self,
        hub_ip: str,
        opener: Optional[urllib.request.OpenerDirector] = None,
        username: Optional[str] = None,
        password: Optional[str] = None,
        max_events: int = 10000,
        connect_timeout: float = 5.0,
    ):
        self.hub_ip = hub_ip
        self.max_events = max_events
        self.connect_timeout = connect_timeout

        # Auth: caller-supplied opener wins; otherwise build one if creds given.
        if opener is None and username and password:
            opener = self._build_opener(hub_ip, username, password)
        self._opener = opener

        self._events: List[dict] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._open_error: Optional[str] = None
        self._ws = None  # populated by _run, used by stop() to interrupt recv

    # ── Lifecycle ─────────────────────────────────────────────────────

    def __enter__(self) -> "EventCapture":
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.stop()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        if not self._ready.wait(timeout=self.connect_timeout + 1):
            self.stop()
            raise RuntimeError(
                f"EventCapture: connect to ws://{self.hub_ip}/eventsocket timed out"
            )
        if self._open_error:
            err = self._open_error
            self.stop()
            raise RuntimeError(f"EventCapture: {err}")

    def stop(self) -> None:
        self._stop.set()
        # Close the WebSocket from the foreground thread to interrupt the
        # background recv() — same pattern as LogCapture.stop().
        ws = self._ws
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass
        if self._thread:
            self._thread.join(timeout=7)
            self._thread = None

    # ── Capture loop (runs in background thread) ──────────────────────

    def _run(self) -> None:
        url = f"ws://{self.hub_ip}/eventsocket"
        headers = self._cookie_header()
        try:
            with connect(
                url,
                open_timeout=self.connect_timeout,
                close_timeout=1,
                ping_interval=None,
                additional_headers=headers,
            ) as ws:
                self._ws = ws
                self._ready.set()
                # See logsocket.py for the rationale on this timeout value.
                ws.socket.settimeout(5.0)
                while not self._stop.is_set():
                    try:
                        raw = ws.recv()
                    except TimeoutError:
                        continue
                    except websockets.exceptions.ConnectionClosed:
                        break
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", errors="replace")
                    self._ingest(raw)
        except Exception as e:
            self._open_error = str(e)
            self._ready.set()

    def _ingest(self, raw: str) -> None:
        try:
            evt = json.loads(raw)
        except json.JSONDecodeError:
            return  # /eventsocket emits well-formed JSON; skip garbage
        if not isinstance(evt, dict):
            return
        with self._lock:
            self._events.append(evt)
            if len(self._events) > self.max_events:
                # Drop oldest to keep memory bounded.
                self._events = self._events[-self.max_events:]

    # ── Access ────────────────────────────────────────────────────────

    @property
    def events(self) -> List[dict]:
        """Snapshot of captured events (safe to read while running)."""
        with self._lock:
            return list(self._events)

    def matches(self, pattern: Optional[str] = None,
                attribute: Optional[str] = None,
                value: ValueFilter = None,
                source: Source = None) -> bool:
        """True iff at least one captured event matches all filters."""
        return self._first_match(pattern, attribute, value, source) is not None

    def no_matches(self, pattern: Optional[str] = None,
                   attribute: Optional[str] = None,
                   value: ValueFilter = None,
                   source: Source = None) -> bool:
        """True iff NO captured event matches all filters."""
        return self._first_match(pattern, attribute, value, source) is None

    def count(self, pattern: Optional[str] = None,
              attribute: Optional[str] = None,
              value: ValueFilter = None,
              source: Source = None) -> int:
        """Number of captured events matching all filters."""
        return len(self.find_all(pattern, attribute, value, source))

    def find_all(self, pattern: Optional[str] = None,
                 attribute: Optional[str] = None,
                 value: ValueFilter = None,
                 source: Source = None) -> List[dict]:
        """Every captured event matching all filters."""
        desc_re = re.compile(pattern) if pattern else None
        attr_re = re.compile(attribute) if attribute else None
        val_re = self._normalize_value(value)
        src_id, src_re = self._normalize_source(source)
        with self._lock:
            return [e for e in self._events
                    if self._match(e, desc_re, attr_re, val_re, src_id, src_re)]

    def wait_for(self, pattern: Optional[str] = None,
                 attribute: Optional[str] = None,
                 value: ValueFilter = None,
                 source: Source = None,
                 timeout: float = 5.0,
                 poll: float = 0.1) -> Optional[dict]:
        """Block until a matching event arrives, or `timeout` elapses.

        Returns the first matching event (dict) or None on timeout.
        Re-reads the captured-events list each poll cycle, so it sees
        events that arrive while the call is blocked.
        """
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            e = self._first_match(pattern, attribute, value, source)
            if e is not None:
                return e
            time.sleep(poll)
        return None

    # ── Internals ─────────────────────────────────────────────────────

    def _first_match(self, pattern, attribute, value, source):
        desc_re = re.compile(pattern) if pattern else None
        attr_re = re.compile(attribute) if attribute else None
        val_re = self._normalize_value(value)
        src_id, src_re = self._normalize_source(source)
        with self._lock:
            for e in self._events:
                if self._match(e, desc_re, attr_re, val_re, src_id, src_re):
                    return e
        return None

    @staticmethod
    def _match(evt, desc_re, attr_re, val_or_re, src_id, src_re) -> bool:
        if desc_re is not None and not desc_re.search(evt.get("descriptionText") or ""):
            return False
        if attr_re is not None and not attr_re.search(evt.get("name") or ""):
            return False
        if val_or_re is not None:
            v = evt.get("value")
            if v is None:
                return False
            v = str(v)
            if isinstance(val_or_re, re.Pattern):
                if not val_or_re.search(v):
                    return False
            else:
                if v != val_or_re:
                    return False
        if src_id is not None and evt.get("deviceId") != src_id:
            return False
        if src_re is not None and not src_re.search(evt.get("displayName") or ""):
            return False
        return True

    @staticmethod
    def _normalize_value(value: ValueFilter):
        # None / str / compiled regex are passed through as-is for _match.
        return value

    @staticmethod
    def _normalize_source(source: Source):
        if source is None:
            return None, None
        if isinstance(source, int):
            return source, None
        s = str(source)
        if s.isdigit():
            return int(s), None
        return None, re.compile(s)

    @staticmethod
    def _build_opener(hub_ip: str, username: str, password: str):
        cj = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        body = urllib.parse.urlencode({"username": username, "password": password}).encode()
        opener.open(f"http://{hub_ip}/login", data=body, timeout=10)
        return opener

    def _cookie_header(self) -> dict:
        """Build a Cookie header from the auth opener's cookiejar (if any)."""
        if self._opener is None:
            return {}
        for handler in self._opener.handlers:
            if isinstance(handler, urllib.request.HTTPCookieProcessor):
                cj = handler.cookiejar
                cookie_str = "; ".join(f"{c.name}={c.value}" for c in cj
                                        if c.domain in (self.hub_ip, ""))
                if cookie_str:
                    return {"Cookie": cookie_str}
        return {}
