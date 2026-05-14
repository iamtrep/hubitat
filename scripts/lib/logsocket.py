"""Log-assertion helper for Mode 1 behavior tests.

Subscribes to a hub's `ws://<hub>/logsocket` stream in a background thread,
captures emitted log lines, and exposes assertion primitives so tests can
assert on the warnings/errors the app under test emits — not just on
attribute changes.

Per TESTING.md §2.4. Closes the "behavior tests can't see logs" gap.

Usage:

    from logsocket import LogCapture

    with LogCapture(hub_ip="192.168.1.86") as cap:
        # drive the app under test
        maker_send(device_id, "open")
        # cap.wait_for blocks until a matching line arrives (or timeout)
        cap.wait_for(r"contact .*open", level="info", timeout=5)

    # After the context exits, capture is closed and assertions reflect
    # the final list of captured messages.
    assert cap.matches(r"contact .*open", level="info")
    assert cap.no_matches(r"ERROR|exception", level="error")
    assert cap.count(r"sensor.*contact") >= 1

Authentication:
    On secured hubs, pass `username=` / `password=` (or pass a pre-built
    `urllib.request.OpenerDirector` via `opener=` whose cookiejar already
    holds a valid HUBSESSION). On open hubs (e.g. maison-pro) omit both.

Message schema (Hubitat logsocket, JSON per line):
    {"time":"YYYY-MM-DD HH:MM:SS.mmm",
     "type":"app"|"dev",
     "id": <int>,
     "name": "<app or device label>",
     "msg":  "<the log line>",
     "level":"info"|"warn"|"error"|"debug"|"trace"}

Filter semantics (matches/no_matches/count/find_all/wait_for):
    pattern : regex matched via re.search against the `msg` field.
              `None` matches every captured line (useful with level filtering).
    level   : exact case-insensitive match against `level`. Pass a list to
              accept multiple (e.g. ["warn","error"]).
    source  : if int or numeric str → match `id`; otherwise regex on `name`.
"""
from __future__ import annotations

import http.cookiejar
import json
import re
import threading
import time
import urllib.parse
import urllib.request
from typing import Iterable, List, Optional, Union

import websockets.exceptions
from websockets.sync.client import connect


Level = Union[str, Iterable[str], None]
Source = Union[str, int, None]


class LogCapture:
    """Context manager that captures /logsocket messages in a background thread.

    Thread-safety: the captured-message list is protected by a lock; the
    assertion methods read a snapshot, so they can be called from the main
    thread while the capture is still running.
    """

    def __init__(
        self,
        hub_ip: str,
        opener: Optional[urllib.request.OpenerDirector] = None,
        username: Optional[str] = None,
        password: Optional[str] = None,
        max_messages: int = 10000,
        connect_timeout: float = 5.0,
    ):
        self.hub_ip = hub_ip
        self.max_messages = max_messages
        self.connect_timeout = connect_timeout

        # Auth: caller-supplied opener wins; otherwise build one if creds given.
        if opener is None and username and password:
            opener = self._build_opener(hub_ip, username, password)
        self._opener = opener

        self._messages: List[dict] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._open_error: Optional[str] = None

    # ── Lifecycle ─────────────────────────────────────────────────────

    def __enter__(self) -> "LogCapture":
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.stop()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        # Wait until the WS handshake is done so the caller knows the
        # capture window is actually open before they trigger anything.
        if not self._ready.wait(timeout=self.connect_timeout + 1):
            self.stop()
            raise RuntimeError(
                f"LogCapture: connect to ws://{self.hub_ip}/logsocket timed out"
            )
        if self._open_error:
            err = self._open_error
            self.stop()
            raise RuntimeError(f"LogCapture: {err}")

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
            self._thread = None

    # ── Capture loop (runs in background thread) ──────────────────────

    def _run(self) -> None:
        url = f"ws://{self.hub_ip}/logsocket"
        headers = self._cookie_header()
        try:
            with connect(
                url,
                open_timeout=self.connect_timeout,
                close_timeout=1,
                ping_interval=None,
                additional_headers=headers,
            ) as ws:
                self._ready.set()
                # The synchronous client doesn't expose a non-blocking recv,
                # so we use a short socket timeout to allow the stop signal
                # to break the loop quickly.
                ws.socket.settimeout(0.5)
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
            msg = json.loads(raw)
        except json.JSONDecodeError:
            msg = {"msg": raw, "level": None, "name": None, "id": None, "type": None}
        if not isinstance(msg, dict):
            return
        with self._lock:
            self._messages.append(msg)
            if len(self._messages) > self.max_messages:
                # Drop oldest to keep memory bounded.
                self._messages = self._messages[-self.max_messages:]

    # ── Access ────────────────────────────────────────────────────────

    @property
    def messages(self) -> List[dict]:
        """Snapshot of captured messages (safe to read while running)."""
        with self._lock:
            return list(self._messages)

    # ── Assertion primitives ──────────────────────────────────────────

    def matches(self, pattern: Optional[str], level: Level = None,
                source: Source = None) -> bool:
        """True iff at least one captured message matches all filters."""
        return self._first_match(pattern, level, source) is not None

    def no_matches(self, pattern: Optional[str], level: Level = None,
                   source: Source = None) -> bool:
        """True iff NO captured message matches all filters."""
        return self._first_match(pattern, level, source) is None

    def count(self, pattern: Optional[str], level: Level = None,
              source: Source = None) -> int:
        """Number of captured messages matching all filters."""
        return len(self.find_all(pattern, level, source))

    def find_all(self, pattern: Optional[str], level: Level = None,
                 source: Source = None) -> List[dict]:
        """Every captured message matching all filters."""
        regex = re.compile(pattern) if pattern else None
        level_set = self._normalize_level(level)
        src_id, src_re = self._normalize_source(source)
        with self._lock:
            return [m for m in self._messages
                    if self._match(m, regex, level_set, src_id, src_re)]

    def wait_for(self, pattern: Optional[str], level: Level = None,
                 source: Source = None, timeout: float = 5.0,
                 poll: float = 0.1) -> Optional[dict]:
        """Block until a matching message arrives, or `timeout` elapses.

        Returns the first matching message (dict) or None on timeout.
        Useful when the test needs the assertion *before* exiting the
        capture context (e.g. to gate a follow-up action).
        """
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            m = self._first_match(pattern, level, source)
            if m is not None:
                return m
            time.sleep(poll)
        return None

    # ── Internals ─────────────────────────────────────────────────────

    def _first_match(self, pattern, level, source):
        regex = re.compile(pattern) if pattern else None
        level_set = self._normalize_level(level)
        src_id, src_re = self._normalize_source(source)
        with self._lock:
            for m in self._messages:
                if self._match(m, regex, level_set, src_id, src_re):
                    return m
        return None

    @staticmethod
    def _match(msg, regex, level_set, src_id, src_re) -> bool:
        if regex is not None and not regex.search(msg.get("msg") or ""):
            return False
        if level_set is not None:
            lvl = (msg.get("level") or "").lower()
            if lvl not in level_set:
                return False
        if src_id is not None and msg.get("id") != src_id:
            return False
        if src_re is not None and not src_re.search(msg.get("name") or ""):
            return False
        return True

    @staticmethod
    def _normalize_level(level: Level):
        if level is None:
            return None
        if isinstance(level, str):
            return {level.lower()}
        return {lv.lower() for lv in level}

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

    def _cookie_header(self) -> Optional[dict]:
        if self._opener is None:
            return None
        for handler in self._opener.handlers:
            if isinstance(handler, urllib.request.HTTPCookieProcessor):
                pieces = [f"{c.name}={c.value}" for c in handler.cookiejar
                          if self.hub_ip in (c.domain or "") or c.domain in ("", None)]
                if pieces:
                    return {"Cookie": "; ".join(pieces)}
        return None
