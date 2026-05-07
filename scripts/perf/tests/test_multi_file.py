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


class TestLoadLogfile:
    def test_basic(self, helpers, tmp_path):
        f = write_jsonl(tmp_path, 'a.json', [
            make_header(),
            make_entry('2026-05-01 10:00:00.000', 10, 'Rule A', 'Triggered: x'),
            make_entry('2026-05-01 10:00:00.200', 10, 'Rule A', 'Action: On: Light'),
        ])
        entries, hdr, mem = helpers['load_logfile'](str(f))
        assert len(entries) == 2
        assert hdr['hub_name'] == 'hub-a'
        assert mem == []

    def test_no_header(self, helpers, tmp_path):
        f = write_jsonl(tmp_path, 'b.json', [
            make_entry('2026-05-01 10:00:00.000', 10, 'Rule A', 'Triggered: x'),
        ])
        _, hdr, _ = helpers['load_logfile'](str(f))
        assert hdr is None

    def test_bad_lines_skipped(self, helpers, tmp_path):
        f = write_jsonl(tmp_path, 'c.json', [
            'not json',
            make_entry('2026-05-01 10:00:00.000', 10, 'Rule A', 'Triggered: x'),
        ])
        entries, _, _ = helpers['load_logfile'](str(f))
        assert len(entries) == 1

    def test_hubstat_goes_to_mem(self, helpers, tmp_path):
        hubstat = json.dumps({
            'time': '2026-05-01 10:00:00.000', 'type': 'hubstat',
            'id': 0, 'name': 'freeOSMemoryLast', 'msg': 'Free OS=500000',
            'stats': {'Free OS': 500000, '5m CPU avg': 1.0},
        })
        f = write_jsonl(tmp_path, 'd.json', [
            hubstat,
            make_entry('2026-05-01 10:00:01.000', 10, 'Rule A', 'Triggered: x'),
        ])
        entries, _, mem = helpers['load_logfile'](str(f))
        assert len(mem) == 1
        assert mem[0][1]['Free OS'] == 500000
        assert len(entries) == 2  # hubstat also in entries (filtered later by main loop)


class TestValidateHeaders:
    def _h(self, name='hub-a', ip='1.2.3.4', fw='2.5.0.100'):
        return {'hub_name': name, 'hub_ip': ip, 'hub_firmware': fw}

    def test_no_headers_ok(self, helpers):
        w, err, rep = helpers['validate_headers']([('a.json', None), ('b.json', None)])
        assert err is None and rep is None

    def test_consistent_ok(self, helpers):
        h = self._h()
        w, err, rep = helpers['validate_headers']([('a.json', h), ('b.json', dict(h))])
        assert err is None and rep is h

    def test_name_mismatch_is_error(self, helpers):
        _, err, _ = helpers['validate_headers']([
            ('a.json', self._h(name='hub-a')),
            ('b.json', self._h(name='hub-b')),
        ])
        assert err is not None and 'hub-b' in err

    def test_ip_mismatch_is_error(self, helpers):
        _, err, _ = helpers['validate_headers']([
            ('a.json', self._h(ip='1.1.1.1')),
            ('b.json', self._h(ip='2.2.2.2')),
        ])
        assert err is not None

    def test_partial_header_warns_not_error(self, helpers):
        w, err, rep = helpers['validate_headers']([
            ('a.json', self._h()), ('b.json', None),
        ])
        assert err is None
        assert any('no capture-header' in x for x in w)


class TestCheckTimeOrdering:
    def _ts(self, s):
        return datetime.strptime(s, '%Y-%m-%d %H:%M:%S')

    def test_clean_sequential_no_warning(self, helpers):
        ranges = [
            ('a.json', self._ts('2026-05-01 10:00:00'), self._ts('2026-05-01 10:30:00')),
            ('b.json', self._ts('2026-05-01 10:30:30'), self._ts('2026-05-01 11:00:00')),
        ]
        assert helpers['check_time_ordering'](ranges) == []

    def test_small_gap_no_warning(self, helpers):
        # 30s gap — below GAP_WARN_SEC (60s)
        ranges = [
            ('a.json', self._ts('2026-05-01 10:00:00'), self._ts('2026-05-01 10:30:00')),
            ('b.json', self._ts('2026-05-01 10:30:30'), self._ts('2026-05-01 11:00:00')),
        ]
        assert helpers['check_time_ordering'](ranges) == []

    def test_large_gap_warns(self, helpers):
        ranges = [
            ('a.json', self._ts('2026-05-01 10:00:00'), self._ts('2026-05-01 10:30:00')),
            ('b.json', self._ts('2026-05-01 12:30:00'), self._ts('2026-05-01 13:00:00')),
        ]
        w = helpers['check_time_ordering'](ranges)
        assert len(w) == 1 and 'gap' in w[0].lower()

    def test_overlap_warns(self, helpers):
        ranges = [
            ('a.json', self._ts('2026-05-01 10:00:00'), self._ts('2026-05-01 11:00:00')),
            ('b.json', self._ts('2026-05-01 10:30:00'), self._ts('2026-05-01 11:30:00')),
        ]
        w = helpers['check_time_ordering'](ranges)
        assert len(w) == 1 and 'overlap' in w[0].lower()


class TestDeduplicateEntries:
    def _e(self, t, app_id, msg):
        ts = datetime.strptime(t, '%Y-%m-%d %H:%M:%S.%f')
        return (ts, {'type': 'app', 'id': app_id, 'msg': msg, 'name': 'R'})

    def test_no_dupes_unchanged(self, helpers):
        e = [self._e('2026-05-01 10:00:00.000', 1, 'Triggered: A'),
             self._e('2026-05-01 10:00:00.200', 1, 'Action: On')]
        assert len(helpers['deduplicate_entries'](e)) == 2

    def test_exact_dupe_removed(self, helpers):
        e = self._e('2026-05-01 10:00:00.000', 1, 'Triggered: A')
        assert len(helpers['deduplicate_entries']([e, e])) == 1

    def test_same_time_diff_msg_kept(self, helpers):
        e = [self._e('2026-05-01 10:00:00.000', 1, 'Triggered: A'),
             self._e('2026-05-01 10:00:00.000', 1, 'Triggered: B')]
        assert len(helpers['deduplicate_entries'](e)) == 2

    def test_same_time_diff_id_kept(self, helpers):
        e = [self._e('2026-05-01 10:00:00.000', 1, 'Triggered: A'),
             self._e('2026-05-01 10:00:00.000', 2, 'Triggered: A')]
        assert len(helpers['deduplicate_entries'](e)) == 2

    def test_preserves_first_occurrence(self, helpers):
        ts = datetime.strptime('2026-05-01 10:00:00.000', '%Y-%m-%d %H:%M:%S.%f')
        d1 = {'type': 'app', 'id': 1, 'msg': 'x', 'name': 'First'}
        d2 = {'type': 'app', 'id': 1, 'msg': 'x', 'name': 'Second'}
        result = helpers['deduplicate_entries']([(ts, d1), (ts, d2)])
        assert len(result) == 1 and result[0][1]['name'] == 'First'


class TestSingleFileBackwardCompat:
    def test_exits_zero(self, tmp_path):
        f = valid_file(tmp_path, 'a.json')
        assert run(str(f)).returncode == 0

    def test_shows_filename(self, tmp_path):
        f = valid_file(tmp_path, 'a.json')
        assert str(f) in run(str(f)).stdout

    def test_shows_hub_info(self, tmp_path):
        f = valid_file(tmp_path, 'a.json', header_kwargs={'hub_name': 'my-hub'})
        assert 'my-hub' in run(str(f)).stdout

    def test_empty_file_exits_nonzero(self, tmp_path):
        f = tmp_path / 'empty.json'
        f.write_text('\n')
        assert run(str(f)).returncode != 0


class TestMultiFileHappyPath:
    def test_two_files_exit_zero(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', '2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200')
        assert run(str(f1), str(f2)).returncode == 0

    def test_both_filenames_in_output(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', '2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200')
        out = run(str(f1), str(f2)).stdout
        assert str(f1) in out and str(f2) in out

    def test_files_sorted_chronologically(self, tmp_path):
        # Supply later file first — output timespan must start from the earlier file
        f_late  = valid_file(tmp_path, 'late.json',  '2026-05-01 12:00:00.000', '2026-05-01 12:00:00.200')
        f_early = valid_file(tmp_path, 'early.json', '2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        out = run(str(f_late), str(f_early)).stdout
        assert '2026-05-01 10:00' in out  # timespan starts at early file

    def test_combined_sample_count(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', '2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200')
        assert 'Trigger->Action samples: 2' in run(str(f1), str(f2)).stdout


class TestHubValidation:
    def test_hub_name_mismatch_exits_nonzero(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', header_kwargs={'hub_name': 'hub-a', 'hub_ip': '1.2.3.4'})
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200',
                        header_kwargs={'hub_name': 'hub-b', 'hub_ip': '1.2.3.4'})
        r = run(str(f1), str(f2))
        assert r.returncode != 0
        assert 'Error' in r.stderr

    def test_no_header_warns(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json')
        f2 = write_jsonl(tmp_path, 'b.json',
                         make_pair('2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200'))
        r = run(str(f1), str(f2))
        assert r.returncode == 0
        assert 'Warning' in r.stderr

    def test_firmware_change_noted(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', header_kwargs={'firmware': '2.5.0.100'})
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200',
                        header_kwargs={'firmware': '2.5.0.131'})
        out = run(str(f1), str(f2)).stdout
        assert '2.5.0.100' in out and '2.5.0.131' in out


class TestGapOverlapDedup:
    def test_large_gap_warns(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json', '2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 12:00:00.000', '2026-05-01 12:00:00.200')
        r = run(str(f1), str(f2))
        assert r.returncode == 0
        assert 'gap' in r.stderr.lower()

    def test_overlap_warns(self, tmp_path):
        lines_a = [make_header()] + \
                  make_pair('2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200') + \
                  make_pair('2026-05-01 10:30:00.000', '2026-05-01 10:30:00.200')
        f1 = write_jsonl(tmp_path, 'a.json', lines_a)
        lines_b = [make_header()] + \
                  make_pair('2026-05-01 10:15:00.000', '2026-05-01 10:15:00.200')
        f2 = write_jsonl(tmp_path, 'b.json', lines_b)
        r = run(str(f1), str(f2))
        assert r.returncode == 0
        assert 'overlap' in r.stderr.lower()

    def test_identical_files_not_double_counted(self, tmp_path):
        lines = [make_header()] + make_pair('2026-05-01 10:00:00.000', '2026-05-01 10:00:00.200')
        f1 = write_jsonl(tmp_path, 'a.json', lines)
        f2 = write_jsonl(tmp_path, 'b.json', lines)
        out = run(str(f1), str(f2)).stdout
        assert 'Trigger->Action samples: 1' in out

    def test_obfuscate_two_files(self, tmp_path):
        f1 = valid_file(tmp_path, 'a.json')
        f2 = valid_file(tmp_path, 'b.json', '2026-05-01 11:00:00.000', '2026-05-01 11:00:00.200')
        r = run(str(f1), str(f2), '--obfuscate')
        assert r.returncode == 0
        assert 'Names obfuscated' in r.stdout
        assert str(f1) not in r.stdout and str(f2) not in r.stdout
