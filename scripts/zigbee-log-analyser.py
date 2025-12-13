#!/usr/bin/env python3
"""
Zigbee Log Analyzer (Hubitat) — Plus (Inbound-only OTA sniff)
------------------------------------------------------------

- Parses Hubitat websocket JSON lines and text log lines.
- ZCL frame parsing: frame control, manufacturer-specific code, direction bit.
- Two-pass analysis: builds a DNI->manufacturer code index (first pass), then aggregates (second pass).
- Filters: manufacturer code, device id, device name, DNI (hex), clusters, unsolicited-only.
- ZDO (profile 0x0000): optionally include discovery/management traffic.
- ZCL Global Commands: labels profile-wide commands (Read/Write/Configure/Report/Discover...).
- OTA (0x0019) details: per-device command counts; **inbound-only OTA sniff** for Query/Block/Upgrade End requests.
- Outputs: table or CSV; optional filtered raw log output.
- Heatmaps: Top talkers & RSSI noise (lazy import) with **forward-fill** for continuous bars.

Usage examples:
  python3 zigbee-log-analyzer-plus.py cpz.log --unsolicited-only
  python3 zigbee-log-analyzer-plus.py cpz.log --unsolicited-only --csv > unsolicited.csv
  python3 zigbee-log-analyzer-plus.py cpz.log --manufacturer 119C --clusters 0402 0500 --unsolicited-only
  python3 zigbee-log-analyzer-plus.py cpz.log --index-cache dni_mfr_index.json --unsolicited-only
  python3 zigbee-log-analyzer-plus.py cpz.log --heatmap --top-talkers 12 --heatmap-prefix charts
  python3 zigbee-log-analyzer-plus.py cpz.log --exclude-zdo --no-global
  python3 zigbee-log-analyzer-plus.py cpz.log --ota-details
"""

import argparse
import json
import re
import sys
import statistics
import os
from collections import defaultdict, Counter
from datetime import datetime, timedelta
from typing import Dict, List, Tuple, Optional

# ---------------------------------------------
# Lazy plotting imports (only if --heatmap)
# ---------------------------------------------

def _ensure_plotting_libs():
    """Import plotting libs only when needed; allow analyzer to run w/o matplotlib/numpy."""
    global np, plt, matplotlib
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        import numpy as np
        return plt, np
    except Exception as e:
        print("[WARNING] Matplotlib/Numpy not installed. Skipping heatmaps.\n"
              "Install with: python3 -m pip install matplotlib numpy\nDetail:", e, file=sys.stderr)
        return None, None

# ---------------------------------------------
# Cluster friendly names (English)
# ---------------------------------------------
CLUSTER_NAMES = {
    '0000': 'Basic',
    '0001': 'Power Configuration',
    '0006': 'On/Off',
    '0019': 'OTA Upgrade',
    '0201': 'Thermostat',
    '0400': 'Illuminance Measurement',
    '0402': 'Temperature Measurement',
    '0403': 'Pressure Measurement',
    '0405': 'Relative Humidity',
    '0406': 'Occupancy Sensing',
    '0500': 'IAS Zone',
    '0702': 'Simple Metering',
    '0B04': 'Electrical Measurement',
    'FF01': 'Manufacturer-specific',
    'FFF1': 'Manufacturer-specific',
}

# ZDO cluster labels (requests/responses)
ZDO_NAMES = {
    '0000': 'NWK Address Req',      '8000': 'NWK Address Rsp',
    '0001': 'IEEE Address Req',     '8001': 'IEEE Address Rsp',
    '0002': 'Node Descriptor Req',  '8002': 'Node Descriptor Rsp',
    '0003': 'Power Descriptor Req', '8003': 'Power Descriptor Rsp',
    '0004': 'Simple Descriptor Req','8004': 'Simple Descriptor Rsp',
    '0005': 'Active Endpoints Req', '8005': 'Active Endpoints Rsp',
    '0006': 'Match Descriptors Req','8006': 'Match Descriptors Rsp',
    '0031': 'Mgmt LQI Req',         '8031': 'Mgmt LQI Rsp',
    '0032': 'Mgmt Routing Req',     '8032': 'Mgmt Routing Rsp',
}

# ZCL global commands map
GLOBAL_COMMANDS = {
    0x00: 'Read Attributes',
    0x01: 'Read Attributes Response',
    0x02: 'Write Attributes',
    0x04: 'Write Attributes Response',
    0x06: 'Configure Reporting',
    0x07: 'Configure Reporting Response',
    0x08: 'Read Reporting Configuration',
    0x09: 'Read Reporting Configuration Response',
    0x0A: 'Report Attributes',
    0x0B: 'Default Response',
    0x0C: 'Discover Attributes',
    0x0D: 'Discover Attributes Response',
    0x11: 'Discover Commands Received',
    0x12: 'Discover Commands Received Response',
    0x13: 'Discover Commands Generated',
    0x14: 'Discover Commands Generated Response',
    0x15: 'Discover Attributes Extended',
    0x16: 'Discover Attributes Extended Response',
}

# OTA command names
OTA_COMMANDS = {
    0x00: 'Image Notify',
    0x01: 'Query Next Image Request',
    0x02: 'Query Next Image Response',
    0x03: 'Image Block Request',
    0x04: 'Image Page Request',
    0x05: 'Image Block Response',
    0x06: 'Upgrade End Request',
    0x07: 'Upgrade End Response',
    0x08: 'Query Specific File',
}

# Minimal default manufacturers; load full via --manufacturer-db to improve labeling
KNOWN_MANUFACTURERS = {
    '119C': 'Sinopé Technologies',
    '10D0': 'Qorvo',
    '10F6': 'Invensys Controls',
    '104E': 'Centralite',
    '1049': 'Silicon Labs',
}

# ---------------------------------------------
# Parsers for Hubitat log lines (JSON or TEXT)
# ---------------------------------------------
_text_re = re.compile(
    r"^name\s+(?P<name>.*?)\s+id\s+(?P<id>\d+)\s+"
    r"profileId\s+(?P<profile>[0-9A-Fa-f]{4})\s+clusterId\s+(?P<cluster>[0-9A-Fa-f]{4})\s+"
    r"sourceEndpoint\s+(?P<se>[0-9A-Fa-f]{2})\s+destinationEndpoint\s+(?P<de>[0-9A-Fa-f]{2}|FF)\s+"
    r"groupId\s+(?P<group>[0-9A-Fa-f]{4})\s+sequence\s+(?P<seq>[0-9A-Fa-f]+)\s+"
    r"lastHopLqi\s+(?P<lqi>\d+)\s+lastHopRssi\s+(?P<rssi>-?\d+)\s+"
    r"time\s+(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+type\s+(?P<type>\w+)\s+"
    r"deviceId\s+(?P<device>\d+)\s+payload\s+(?P<payload>(?:[0-9A-Fa-f]{2}(?:\s+[0-9A-Fa-f]{2})*))$"
)

def parse_text_line(line: str) -> Optional[dict]:
    m = _text_re.match(line.strip())
    if not m:
        return None
    seqstr = m.group('seq')
    try:
        seq = int(seqstr, 16)
    except ValueError:
        seq = int(seqstr)
    return {
        'name': m.group('name'),
        'network_id': int(m.group('id')),
        'device_id': int(m.group('device')),
        'profileId': m.group('profile').upper(),
        'clusterId': m.group('cluster').upper(),
        'sourceEndpoint': m.group('se'),
        'destinationEndpoint': m.group('de'),
        'groupId': m.group('group'),
        'sequence': seq,
        'lqi': int(m.group('lqi')),
        'rssi': int(m.group('rssi')),
        'time': m.group('time'),
        'type': m.group('type'),
        'payload': [p.upper() for p in m.group('payload').split()],
    }


def parse_json_line(line: str) -> Optional[dict]:
    try:
        d = json.loads(line)
    except Exception:
        return None
    payload = d.get('payload', [])
    if isinstance(payload, list):
        pay = [x.upper() for x in payload]
    elif isinstance(payload, str):
        pay = [x.upper() for x in payload.split()]
    else:
        pay = []
    return {
        'name': d.get('name') or 'unknown',
        'network_id': d.get('id'),
        'device_id': d.get('deviceId'),
        'profileId': (d.get('profileId') or '').upper(),
        'clusterId': (d.get('clusterId') or '').upper(),
        'sourceEndpoint': (d.get('sourceEndpoint') or ''),
        'destinationEndpoint': (d.get('destinationEndpoint') or ''),
        'groupId': (d.get('groupId') or ''),
        'sequence': d.get('sequence'),
        'lqi': d.get('lastHopLqi'),
        'rssi': d.get('lastHopRssi'),
        'time': d.get('time'),
        'type': d.get('type'),
        'payload': pay,
    }

# ---------------------------------------------
# ZCL frame decoding
# ---------------------------------------------

def parse_zcl(payload_hex: List[str]) -> dict:
    """Decode ZCL frame control and optional manufacturer code.
    Layout: FC, [MFR lo, MFR hi], SEQ, CMD
    """
    res = {
        'fc': None,
        'frame_type': None,  # 'global' or 'cluster'
        'manuf_specific': False,
        'direction': None,   # 'server_to_client' or 'client_to_server'
        'disable_default_rsp': False,
        'manufacturer_code': None,
        'sequence': None,
        'command_id': None,
    }
    if not payload_hex or len(payload_hex) < 3:
        return res
    try:
        fc = int(payload_hex[0], 16)
    except Exception:
        return res
    res['fc'] = fc
    res['frame_type'] = 'cluster' if (fc & 0x03) == 0x01 else 'global'
    res['manuf_specific'] = bool(fc & 0x04)
    res['direction'] = 'server_to_client' if (fc & 0x08) else 'client_to_server'
    res['disable_default_rsp'] = bool(fc & 0x10)
    idx = 1
    if res['manuf_specific']:
        if len(payload_hex) < idx + 2:
            return res
        m_lo = int(payload_hex[idx], 16)
        m_hi = int(payload_hex[idx + 1], 16)
        res['manufacturer_code'] = f"{(m_hi << 8 | m_lo):04X}"
        idx += 2
    if len(payload_hex) < idx + 2:
        return res
    res['sequence'] = int(payload_hex[idx], 16)
    res['command_id'] = int(payload_hex[idx + 1], 16)
    return res

# ---------------------------------------------
# OTA request payload parsing (inbound-only)
# ---------------------------------------------

def _u16(lo_hex: str, hi_hex: str) -> int:
    return (int(hi_hex, 16) << 8) | int(lo_hex, 16)

def _u32(b0: str, b1: str, b2: str, b3: str) -> int:
    return (int(b3, 16) << 24) | (int(b2, 16) << 16) | (int(b1, 16) << 8) | int(b0, 16)


def parse_ota_request_payload(payload_hex: List[str], zcl_header: dict) -> dict | None:
    """Parse OTA client->server request payloads (Query Next Image Req, Image Block Req, Upgrade End Req).
    Returns a dict of extracted fields or None if not parseable.
    Based on OTA Upgrade spec; fields are little-endian in ZCL.
    """
    try:
        start = 1 + (2 if zcl_header.get('manuf_specific') else 0) + 2
        data = payload_hex[start:]
        cmd = zcl_header.get('command_id')
        if cmd is None or len(data) == 0:
            return None
        res: dict = {}
        if cmd == 0x01:  # Query Next Image Request
            if len(data) < 1 + 2 + 2 + 4:
                return None
            field_ctrl = int(data[0], 16)
            res['field_control'] = field_ctrl
            res['manufacturer_id'] = _u16(data[1], data[2])
            res['image_type'] = _u16(data[3], data[4])
            res['file_version'] = _u32(data[5], data[6], data[7], data[8])
            if field_ctrl & 0x01 and len(data) >= 1 + 2 + 2 + 4 + 2:
                res['hardware_version'] = _u16(data[9], data[10])
            return res
        if cmd == 0x03:  # Image Block Request
            if len(data) < 1 + 2 + 2 + 4 + 4 + 1:
                return None
            field_ctrl = int(data[0], 16)
            res['field_control'] = field_ctrl
            res['manufacturer_id'] = _u16(data[1], data[2])
            res['image_type'] = _u16(data[3], data[4])
            res['file_version'] = _u32(data[5], data[6], data[7], data[8])
            res['file_offset'] = _u32(data[9], data[10], data[11], data[12])
            res['max_data_size'] = int(data[13], 16)
            pos = 14
            if field_ctrl & 0x01 and len(data) >= pos + 8:
                eui_bytes = data[pos:pos+8]
                res['request_node_addr'] = ''.join(b for b in eui_bytes)
                pos += 8
            if field_ctrl & 0x02 and len(data) >= pos + 2:
                res['block_request_delay'] = _u16(data[pos], data[pos+1])
            return res
        if cmd == 0x06:  # Upgrade End Request
            if len(data) < 1 + 2 + 2 + 4:
                return None
            res['status'] = int(data[0], 16)
            res['manufacturer_id'] = _u16(data[1], data[2])
            res['image_type'] = _u16(data[3], data[4])
            res['file_version'] = _u32(data[5], data[6], data[7], data[8])
            return res
        return None
    except Exception:
        return None

# ---------------------------------------------
# Manufacturer DB loading
# ---------------------------------------------

def load_manufacturer_db(path: Optional[str]) -> Dict[str, str]:
    if path:
        try:
            with open(path, 'r') as f:
                data = json.load(f)
            return {str(k).upper(): v for k, v in data.items()}
        except Exception as e:
            print(f"[WARNING] Could not load manufacturer DB '{path}': {e}", file=sys.stderr)
    return KNOWN_MANUFACTURERS

# ---------------------------------------------
# First pass: build DNI -> manufacturer code
# ---------------------------------------------

def build_dni_mfr_index(filepath: str) -> Dict[int, str]:
    index: Dict[int, str] = {}
    with open(filepath, 'r') as f:
        for line in f:
            rec = parse_text_line(line) or parse_json_line(line)
            if not rec:
                continue
            z = parse_zcl(rec['payload'])
            if z['manufacturer_code'] and rec['network_id'] is not None:
                index[rec['network_id']] = z['manufacturer_code']
    return index

# ---------------------------------------------
# Second pass: analyze
# ---------------------------------------------

def analyze_log(filepath: str,
                 mfr_filter: Optional[str],
                 device_id_filter: Optional[int],
                 device_name_filter: Optional[str],
                 dni_filter: Optional[int],
                 cluster_filters: Optional[List[str]],
                 unsolicited_only: bool,
                 output_file: Optional[str],
                 index_cache: Optional[str],
                 include_zdo: bool,
                 show_global: bool,
                 ota_details: bool):

    # Load or build DNI index
    dni_mfr_index: Dict[int, str] = {}
    if index_cache and os.path.exists(index_cache):
        try:
            with open(index_cache, 'r') as f:
                dni_mfr_index = {int(k): v for k, v in json.load(f).items()}
        except Exception as e:
            print(f"[WARNING] Could not read cache: {e}", file=sys.stderr)
    else:
        dni_mfr_index = build_dni_mfr_index(filepath)
        if index_cache:
            try:
                with open(index_cache, 'w') as f:
                    json.dump(dni_mfr_index, f)
            except Exception as e:
                print(f"[WARNING] Could not write cache: {e}", file=sys.stderr)

    devices = defaultdict(lambda: {
        'count': 0,
        'lqis': [],
        'rssis': [],
        'mfr_code': None,
        'device_id': None,
        'network_id': None,
        'times': [],
        'per_cluster': Counter(),
        'per_cmd': Counter(),         # cluster-specific command IDs
        'per_global_cmd': Counter(),  # ZCL global commands
        'per_zdo': Counter(),         # ZDO ops per device
        'per_ota_cmd': Counter(),     # OTA command IDs per device
        # OTA inbound-only fields
        'ota_req_mfr': None,
        'ota_req_image_type': None,
        'ota_req_current_version': None,
        'ota_last_offset': None,
        'ota_max_block_size': None,
        'ota_blocks_count': None,
        'ota_completed_download': None,
        'ota_phase': None,
    })

    global_summary = {
        'zdo': Counter(),
        'global_cmds': Counter(),
        'ota_cmds': Counter(),
    }

    all_times: List[str] = []
    out = open(output_file, 'w') if output_file else None

    with open(filepath, 'r') as f:
        for line in f:
            rec = parse_text_line(line) or parse_json_line(line)
            if not rec:
                continue

            # Filters
            if mfr_filter:
                ztmp = parse_zcl(rec['payload'])
                mfr_this = ztmp['manufacturer_code'] or dni_mfr_index.get(rec['network_id'])
                if not mfr_this or mfr_this.upper() != mfr_filter.upper():
                    continue
            if device_id_filter and rec['device_id'] != device_id_filter:
                continue
            if device_name_filter and device_name_filter.lower() not in (rec['name'] or '').lower():
                continue
            if dni_filter and rec['network_id'] != dni_filter:
                continue
            if cluster_filters and rec['clusterId'].upper() not in [c.upper() for c in cluster_filters]:
                continue

            # ZDO classification
            is_zdo = (rec['profileId'] == '0000')
            if is_zdo and not include_zdo:
                continue

            z = parse_zcl(rec['payload'])

            # unsolicited rule: server->client frames, plus always keep OTA cluster
            if unsolicited_only and z['direction'] != 'server_to_client' and rec['clusterId'] != '0019':
                # allow ZDO regardless (these aren't ZCL)
                if not is_zdo:
                    continue

            # write filtered line if requested
            if out:
                out.write(line)

            name = rec['name'] or 'unknown'
            dev = devices[name]
            dev['count'] += 1
            if rec['lqi'] is not None:
                dev['lqis'].append(rec['lqi'])
            if rec['rssi'] is not None:
                dev['rssis'].append(rec['rssi'])
            mfr_code = z['manufacturer_code'] or dni_mfr_index.get(rec['network_id'])
            dev['mfr_code'] = mfr_code
            dev['device_id'] = rec['device_id']
            dev['network_id'] = rec['network_id']
            dev['times'].append(rec['time'])
            if rec['time']:
                all_times.append(rec['time'])

            if is_zdo:
                label = ZDO_NAMES.get(rec['clusterId'], f"ZDO {rec['clusterId']}")
                dev['per_zdo'][label] += 1
                global_summary['zdo'][label] += 1
            else:
                # ZCL path
                dev['per_cluster'][rec['clusterId']] += 1
                if z['frame_type'] == 'global' and show_global and z['command_id'] is not None:
                    gname = GLOBAL_COMMANDS.get(z['command_id'], f"Global 0x{z['command_id']:02X}")
                    dev['per_global_cmd'][gname] += 1
                    global_summary['global_cmds'][gname] += 1
                elif z['command_id'] is not None:
                    dev['per_cmd'][z['command_id']] += 1
                # OTA details
                if ota_details and rec['clusterId'] == '0019' and z['command_id'] is not None:
                    oname = OTA_COMMANDS.get(z['command_id'], f"OTA 0x{z['command_id']:02X}")
                    dev['per_ota_cmd'][oname] += 1
                    global_summary['ota_cmds'][oname] += 1
                    # Inbound-only sniff: parse client->server requests to infer identity & progress
                    if z['direction'] == 'client_to_server':
                        meta = parse_ota_request_payload(rec['payload'], z)
                        if meta:
                            if z['command_id'] == 0x01:  # Query Next Image Request
                                dev['ota_req_mfr'] = f"0x{meta.get('manufacturer_id'):04X}" if meta.get('manufacturer_id') is not None else None
                                dev['ota_req_image_type'] = f"0x{meta.get('image_type'):04X}" if meta.get('image_type') is not None else None
                                fv = meta.get('file_version')
                                dev['ota_req_current_version'] = f"0x{fv:08X}" if fv is not None else None
                                dev['ota_phase'] = 'querying'
                            elif z['command_id'] == 0x03:  # Image Block Request
                                off = meta.get('file_offset')
                                if off is not None:
                                    prev = dev.get('ota_last_offset')
                                    dev['ota_last_offset'] = max(prev or 0, off)
                                blk = meta.get('max_data_size')
                                if blk is not None:
                                    dev['ota_max_block_size'] = blk
                                dev['ota_blocks_count'] = (dev.get('ota_blocks_count') or 0) + 1
                                dev['ota_phase'] = 'downloading'
                            elif z['command_id'] == 0x06:  # Upgrade End Request
                                dev['ota_completed_download'] = True
                                dev['ota_phase'] = 'awaiting_upgrade'

    if out:
        out.close()

    time_range = (min(all_times), max(all_times)) if all_times else (None, None)
    return devices, time_range, dni_mfr_index, global_summary

# ---------------------------------------------
# Output helpers
# ---------------------------------------------

def print_table(devices: dict, time_range: Tuple[Optional[str], Optional[str]], filters: dict, manufacturer_db: Dict[str, str], global_summary: dict):
    print("Zigbee Analysis — Unsolicited frames (and OTA)")
    print("=" * 118)
    if time_range[0] and time_range[1]:
        try:
            t1 = datetime.strptime(time_range[0], "%Y-%m-%d %H:%M:%S.%f")
            t2 = datetime.strptime(time_range[1], "%Y-%m-%d %H:%M:%S.%f")
            dur = t2 - t1
            h, r = divmod(int(dur.total_seconds()), 3600)
            m, s = divmod(r, 60)
            print(f"Time range: {time_range[0]} → {time_range[1]} (duration {h}h {m}m)")
        except ValueError:
            print(f"Time range: {time_range[0]} → {time_range[1]}")
    parts = []
    if filters.get('manufacturer'): parts.append(f"manufacturer={filters['manufacturer']}")
    if filters.get('dni'): parts.append(f"dni=0x{filters['dni']:04X}")
    if filters.get('device_id'): parts.append(f"device_id={filters['device_id']}")
    if filters.get('device_name'): parts.append(f"name contains '{filters['device_name']}'")
    if filters.get('clusters'): parts.append(f"clusters={','.join(filters['clusters'])}")
    if parts:
        print("Filters: " + ", ".join(parts))
    print()

    # Device table
    print(f"{'Device Label':<34} {'Mfr':<6} {'Manufacturer':<28} {'Count':>6} {'Med LQI':>7} {'Med RSSI':>8}  Top clusters (count)")
    print("-" * 118)
    total = 0
    for name, info in sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True):
        med_lqi = statistics.median(info['lqis']) if info['lqis'] else 0
        med_rssi = statistics.median(info['rssis']) if info['rssis'] else 0
        mfr_code = info['mfr_code'] or ''
        mfr_name = manufacturer_db.get(mfr_code, '')
        top = info['per_cluster'].most_common(3)
        top_str = ", ".join([f"{CLUSTER_NAMES.get(c, c)}({n})" for c, n in top])
        print(f"{name:<34} {mfr_code:<6} {mfr_name:<28} {info['count']:>6} {med_lqi:>7.0f} {med_rssi:>8.0f}  {top_str}")
        total += info['count']
    print("-" * 118)
    print(f"{'TOTAL':<34} {'':<6} {'':<28} {total:>6}")

    # Optional sections
    if global_summary['global_cmds']:
        print("\nTop ZCL Global Commands:")
        for cmd, n in global_summary['global_cmds'].most_common(10):
            print(f"  {cmd:<36} {n:>6}")
    if global_summary['zdo']:
        print("\nTop ZDO Operations:")
        for zdo, n in global_summary['zdo'].most_common(10):
            print(f"  {zdo:<36} {n:>6}")
    if global_summary['ota_cmds']:
        print("\nTop OTA Commands:")
        for oc, n in global_summary['ota_cmds'].most_common(10):
            print(f"  {oc:<36} {n:>6}")
    # OTA inbound-only phases summary
    phases = Counter()
    for _, info in devices.items():
        if info.get('ota_phase'):
            phases[info['ota_phase']] += 1
    if phases:
        print("\nOTA (inbound-only) phases:")
        for ph, n in phases.most_common():
            print(f"  {ph:<20} {n:>6}")


def print_csv(devices: dict, manufacturer_db: Dict[str, str]):
    header = (
        "Device Label,Mfr Code,Manufacturer,Count,Median LQI,Median RSSI,Network ID,Device ID,"
        "TopClusters,TopGlobalCmds,TopZDO,TopOTA,"
        "OTA Req Mfr,OTA Req ImageType,OTA Req CurrentVersion,OTA LastOffset,OTA MaxBlock,OTA Blocks,OTA Phase,OTA Completed"
    )
    print(header)
    for name, info in sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True):
        med_lqi = statistics.median(info['lqis']) if info['lqis'] else 0
        med_rssi = statistics.median(info['rssis']) if info['rssis'] else 0
        mfr_code = info['mfr_code'] or ''
        mfr_name = manufacturer_db.get(mfr_code, '')
        top_clusters = ";".join([f"{CLUSTER_NAMES.get(c, c)}({n})" for c, n in info['per_cluster'].most_common(3)])
        top_globals = ";".join([f"{k}({v})" for k, v in info['per_global_cmd'].most_common(3)])
        top_zdo = ";".join([f"{k}({v})" for k, v in info['per_zdo'].most_common(3)])
        top_ota = ";".join([f"{k}({v})" for k, v in info['per_ota_cmd'].most_common(3)])
        network_id_hex = f"0x{info['network_id']:04X}" if info['network_id'] else ''
        row = (
            f'"{name}",{mfr_code},{mfr_name},{info["count"]},{med_lqi:.0f},{med_rssi:.0f},'
            f'{network_id_hex},{info["device_id"]},'
            f'"{top_clusters}","{top_globals}","{top_zdo}","{top_ota}",'
            f'{info.get("ota_req_mfr","")},{info.get("ota_req_image_type","")},{info.get("ota_req_current_version","")},'
            f'{info.get("ota_last_offset","")},{info.get("ota_max_block_size","")},{info.get("ota_blocks_count","")},'
            f'{info.get("ota_phase","")},{info.get("ota_completed_download","")}'
        )
        print(row)

# ---------------------------------------------
# Heatmap / Top talkers (forward-fill for RSSI)
# ---------------------------------------------

def build_time_bins(times: List[str], bin_seconds: int = 60) -> Tuple[List[datetime], Dict[str, int]]:
    if not times:
        return [], {}
    dt = []
    for t in times:
        try:
            dt.append(datetime.strptime(t, "%Y-%m-%d %H:%M:%S.%f"))
        except Exception:
            pass
    if not dt:
        return [], {}
    start = min(dt)
    end = max(dt) + timedelta(seconds=bin_seconds)
    bins = []
    cur = start.replace(microsecond=0)
    while cur <= end:
        bins.append(cur)
        cur += timedelta(seconds=bin_seconds)
    index = {}
    for t in times:
        try:
            tt = datetime.strptime(t, "%Y-%m-%d %H:%M:%S.%f")
            idx = int((tt - bins[0]).total_seconds() // bin_seconds)
            index[t] = idx
        except Exception:
            pass
    return bins, index


def generate_heatmaps(devices: dict, top_n: int = 10, prefix: str = 'charts', bin_seconds: int = 60):
    plt, np = _ensure_plotting_libs()
    if plt is None or np is None:
        return
    # pick top talkers by count
    ranking = sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True)[:top_n]
    if not ranking:
        print("[INFO] No devices to plot heatmaps.")
        return
    # collect times for binning
    all_times = []
    for _, info in ranking:
        all_times.extend([t for t in info['times'] if t])
    bins, time_index = build_time_bins(all_times, bin_seconds)
    if not bins:
        print("[INFO] No temporal data for heatmaps.")
        return
    # matrices
    dev_names = [name for name, _ in ranking]
    counts = np.zeros((len(dev_names), len(bins)), dtype=int)
    rssis = np.full((len(dev_names), len(bins)), np.nan, dtype=float)
    for i, (name, info) in enumerate(ranking):
        rssi_bins: Dict[int, List[int]] = defaultdict(list)
        rssi_list = info['rssis']
        time_list = info['times']
        for t_idx, t in enumerate(time_list):
            if not t:
                continue
            idx = time_index.get(t)
            if idx is None or not (0 <= idx < len(bins)):
                continue
            counts[i, idx] += 1
            if t_idx < len(rssi_list) and rssi_list[t_idx] is not None:
                rssi_bins[idx].append(rssi_list[t_idx])
        for idx, values in rssi_bins.items():
            if values:
                rssis[i, idx] = statistics.median(values)
        # forward-fill continuous bars
        last = None
        for idx in range(len(bins)):
            if not np.isnan(rssis[i, idx]):
                last = rssis[i, idx]
            elif last is not None:
                rssis[i, idx] = last
        # back-fill leading NaNs with first known value
        first_non_nan = None
        for idx in range(len(bins)):
            if not np.isnan(rssis[i, idx]):
                first_non_nan = rssis[i, idx]
                break
        if first_non_nan is not None:
            for idx in range(len(bins)):
                if np.isnan(rssis[i, idx]):
                    rssis[i, idx] = first_non_nan
                else:
                    break
    # talkers heatmap
    plt.figure(figsize=(12, max(4, 0.4 * len(dev_names))))
    plt.imshow(counts, aspect='auto', cmap='viridis')
    plt.colorbar(label='Message count per bin')
    plt.yticks(range(len(dev_names)), dev_names)
    xlabels = [b.strftime('%H:%M:%S') for b in bins]
    step = max(1, len(bins)//10)
    plt.xticks(range(0, len(bins), step), xlabels[::step], rotation=45)
    plt.title('Heatmap — Top talkers (counts per bin)')
    plt.xlabel('Time')
    plt.ylabel('Devices')
    os.makedirs(os.path.dirname(prefix) or '.', exist_ok=True)
    out1 = f"{prefix}_talkers.png"
    plt.tight_layout(); plt.savefig(out1); plt.close()
    print(f"[OK] Talkers heatmap: {out1}")
    # RSSI heatmap (continuous)
    plt.figure(figsize=(12, max(4, 0.4 * len(dev_names))))
    plt.imshow(rssis, aspect='auto', cmap='plasma', interpolation='nearest')
    plt.colorbar(label='Median RSSI (dBm)')
    plt.yticks(range(len(dev_names)), dev_names)
    plt.xticks(range(0, len(bins), step), xlabels[::step], rotation=45)
    plt.title('Heatmap — RSSI noise (median per bin)')
    plt.xlabel('Time')
    plt.ylabel('Devices')
    out2 = f"{prefix}_rssi.png"
    plt.tight_layout(); plt.savefig(out2); plt.close()
    print(f"[OK] RSSI heatmap: {out2}")

# ---------------------------------------------
# Main
# ---------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='Zigbee (Hubitat) Log Analyzer — ZDO, Global cmd, OTA inbound sniff, Heatmaps')
    parser.add_argument('logfile', help='Path to Zigbee log file')
    parser.add_argument('--manufacturer', '-m', help='Filter by manufacturer code (hex, e.g., 119C)')
    parser.add_argument('--manufacturer-db', help='Path to manufacturer DB (JSON {code:name})')
    parser.add_argument('--device-id', '-d', type=int, help='Filter by Hubitat device ID (decimal)')
    parser.add_argument('--device-name', '-n', help='Filter by device name (substring match)')
    parser.add_argument('--dni', help='Filter by device network ID (hex, e.g., 78E0 or 0x78E0)')
    parser.add_argument('--clusters', nargs='*', help='Limit to specific clusters (hex, e.g., 0402 0500)')
    parser.add_argument('--output', '-o', help='Write filtered raw log lines to this file')
    parser.add_argument('--csv', action='store_true', help='Output CSV instead of table')
    parser.add_argument('--unsolicited-only', action='store_true', default=False,
                        help='Keep only server->client frames (reports/notifications); OTA always included.')
    parser.add_argument('--index-cache', help='JSON path for DNI->manufacturer cache (read/write)')
    parser.add_argument('--heatmap', action='store_true', help='Generate Top talkers & RSSI heatmaps (PNG)')
    parser.add_argument('--top-talkers', type=int, default=10, help='Number of devices in heatmaps')
    parser.add_argument('--heatmap-prefix', default='charts', help='Heatmap PNG prefix (e.g., reports/charts)')
    parser.add_argument('--include-zdo', action='store_true', default=True, help='Include ZDO traffic (profile 0x0000)')
    parser.add_argument('--exclude-zdo', action='store_true', help='Exclude ZDO traffic')
    parser.add_argument('--show-global', action='store_true', default=True, help='Include ZCL global commands')
    parser.add_argument('--no-global', action='store_true', help='Exclude ZCL global commands')
    parser.add_argument('--ota-details', action='store_true', help='Show per-device OTA command breakdown & inbound-only metadata')

    args = parser.parse_args()

    dni_filter = None
    if args.dni:
        dni_hex = args.dni.upper().replace('0X', '')
        dni_filter = int(dni_hex, 16)

    include_zdo = True
    if args.exclude_zdo:
        include_zdo = False
    elif not args.include_zdo:
        include_zdo = False

    show_global = True
    if args.no_global:
        show_global = False
    elif not args.show_global:
        show_global = False

    manufacturer_db = load_manufacturer_db(args.manufacturer_db)

    devices, time_range, dni_index, global_summary = analyze_log(
        args.logfile,
        mfr_filter=args.manufacturer,
        device_id_filter=args.device_id,
        device_name_filter=args.device_name,
        dni_filter=dni_filter,
        cluster_filters=args.clusters,
        unsolicited_only=args.unsolicited_only,
        output_file=args.output,
        index_cache=args.index_cache,
        include_zdo=include_zdo,
        show_global=show_global,
        ota_details=args.ota_details,
    )

    if not devices:
        print("No frames matched the criteria.", file=sys.stderr)
        sys.exit(1)

    filters = {
        'manufacturer': args.manufacturer,
        'dni': dni_filter,
        'device_id': args.device_id,
        'device_name': args.device_name,
        'clusters': args.clusters,
    }

    if args.csv:
        print_csv(devices, manufacturer_db)
    else:
        print_table(devices, time_range, filters, manufacturer_db, global_summary)

    if args.heatmap:
        generate_heatmaps(devices, top_n=args.top_talkers, prefix=args.heatmap_prefix)

if __name__ == '__main__':
    main()
