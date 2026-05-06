"""Tests for multi-file input support in analyse_rm_delays.py.

Run with:
    cd /Users/trep/Documents/GitHub/iamtrep/hubitat/scripts/perf
    python3 -m pytest tests/test_multi_file.py -v
"""
import importlib.util
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parent.parent / 'analyse_rm_delays.py'
PYTHON = sys.executable


# ---------------------------------------------------------------------------
# JSONL fixture helpers
# ---------------------------------------------------------------------------

def make_header(hub_name='hub-a', hub_ip='1.2.3.4', firmware='2.5.0.100',
                model='C-7', time='2026-05-01 00:00:00.000'):
    return json.dumps({'type': 'capture-header', 'hub_name': hub_name,
                       'hub_model': model, 'hub_firmware': firmware,
                       'hub_ip': hub_ip, 'time': time})


def make_entry(time, app_id, name, msg, entry_type='app'):
    return json.dumps({'time': time, 'type': entry_type,
                       'id': app_id, 'name': name, 'msg': msg})


def make_pair(t_trigger, t_action, app_id=100, name='Rule A'):
    return [
        make_entry(t_trigger, app_id, name, 'Triggered: Periodic Schedule'),
        make_entry(t_action,  app_id, name, 'Action: On: Some Light'),
    ]


def write_jsonl(tmp_path, filename, lines):
    p = tmp_path / filename
    p.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return p


def valid_file(tmp_path, filename,
               t_trigger='2026-05-01 10:00:00.000',
               t_action='2026-05-01 10:00:00.200',
               header_kwargs=None):
    hkw = header_kwargs or {}
    lines = [make_header(**hkw)] + make_pair(t_trigger, t_action)
    return write_jsonl(tmp_path, filename, lines)


# ---------------------------------------------------------------------------
# Import helper — exec only the pure functions defined before argparse
# ---------------------------------------------------------------------------

@pytest.fixture(scope='module')
def helpers():
    src = SCRIPT.read_text()
    stop_marker = 'parser = argparse.ArgumentParser'
    lines = src.splitlines()
    stop_idx = next(i for i, l in enumerate(lines) if stop_marker in l)
    ns = {}
    exec(compile('\n'.join(lines[:stop_idx]), str(SCRIPT), 'exec'), ns)
    return ns


# ---------------------------------------------------------------------------
# Subprocess runner
# ---------------------------------------------------------------------------

def run(*args):
    return subprocess.run([PYTHON, str(SCRIPT)] + list(args),
                          capture_output=True, text=True)
