#!/usr/bin/env python3
"""
Zigbee Log Analyzer (Hubitat) — Refactor
========================================

Features
- Parses BOTH Hubitat websocket JSON lines and Hubitat text log lines.
- General ZCL parsing: frame control, manufacturer-specific code, direction bit.
- Two-pass analysis: builds a DNI->manufacturer code index (first pass), then aggregates (second pass),
  so devices that rarely include manufacturer codes still get labeled.
- Filters: by manufacturer code, device id, device name (substring), DNI (hex), cluster(s).
- Output: Table or CSV.
- Optional: write filtered lines to an output file.
- Optional: persist/load DNI->manufacturer index JSON cache (to avoid relearning every run).
- Top Talkers + Noise Heatmap: generate PNG charts summarizing talkers and RSSI over time.

Usage
-----
python3 zigbee-log-analyzer.py <logfile> [options]

Examples
--------
python3 zigbee-log-analyzer.py cpz.log --unsolicited-only --csv > unsolicited.csv
python3 zigbee-log-analyzer.py cpz.log --name "Th Salon" --clusters 0402 0B04
python3 zigbee-log-analyzer.py cpz.log --heatmap --top-talkers 12 --heatmap-prefix charts
python3 zigbee-log-analyzer.py cpz.log --index-cache dni_mfr_index.json

Notes
-----
- Manufacturer codes: this script includes a small subset by default. You can supply a full JSON
  mapping via --manufacturer-db manufacturers.json (keys hex strings, values names), or paste your
  full KNOWN_MANUFACTURERS dict into this file.
- Heatmap labels/annotations are in French to match your environment.
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

import matplotlib
matplotlib.use('Agg')  # headless
import matplotlib.pyplot as plt
import numpy as np

# ---------------------------------------------
# Cluster friendly names (extend as needed)
# ---------------------------------------------
CLUSTER_NAMES = {
    '0000': 'Basic',
    '0001': 'Power Configuration',
    '0006': 'On/Off',
    '0019': 'OTA Upgrade',
    '0201': 'Thermostat',
    '0400': 'Illuminance',
    '0402': 'Température',
    '0403': 'Pression',
    '0405': 'Humidité',
    '0406': 'Présence (Occupancy)',
    '0500': 'IAS Zone',
    '0702': 'Mesure d’énergie',
    '0B04': 'Mesure électrique',
    'FF01': 'Spécifique fabricant',
    'FFF1': 'Spécifique fabricant',
    '8001': 'ZDO (interne)',
}

# Minimal default mapping; you can override via --manufacturer-db or paste full dict below
KNOWN_MANUFACTURERS = {
    '119C': 'Sinopé Technologies',
    '10D0': 'Qorvo',
    '10F6': 'Invensys Controls',
    '104E': 'Centralite',
    '1049': 'Silicon Labs',
    # --- Paste full table from your original script here if you prefer in-file mapping ---
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
        'frame_type': None,
        'manuf_specific': False,
        'direction': None,  # 'server_to_client' or 'client_to_server'
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
# Manufacturer DB loading
# ---------------------------------------------

def load_manufacturer_db(path: Optional[str]) -> Dict[str, str]:
    if path:
        try:
            with open(path, 'r') as f:
                data = json.load(f)
            # normalize keys upper
            return {k.upper(): v for k, v in data.items()}
        except Exception as e:
            print(f"[AVERTISSEMENT] Impossible de charger la base fabricants '{path}': {e}", file=sys.stderr)
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
# Second pass: analyze (with filters & unsolicited detection)
# ---------------------------------------------

def analyze_log(filepath: str,
                 mfr_filter: Optional[str],
                 device_id_filter: Optional[int],
                 device_name_filter: Optional[str],
                 dni_filter: Optional[int],
                 cluster_filters: Optional[List[str]],
                 unsolicited_only: bool,
                 output_file: Optional[str],
                 index_cache: Optional[str]) -> Tuple[dict, Tuple[Optional[str], Optional[str]], Dict[int, str]]:
    # Load or build DNI index
    dni_mfr_index: Dict[int, str] = {}
    if index_cache and os.path.exists(index_cache):
        try:
            with open(index_cache, 'r') as f:
                dni_mfr_index = {int(k): v for k, v in json.load(f).items()}
        except Exception as e:
            print(f"[AVERTISSEMENT] Lecture cache échouée: {e}", file=sys.stderr)
    else:
        dni_mfr_index = build_dni_mfr_index(filepath)
        if index_cache:
            try:
                with open(index_cache, 'w') as f:
                    json.dump(dni_mfr_index, f)
            except Exception as e:
                print(f"[AVERTISSEMENT] Écriture cache échouée: {e}", file=sys.stderr)

    devices = defaultdict(lambda: {
        'count': 0,
        'lqis': [],
        'rssis': [],
        'mfr_code': None,
        'device_id': None,
        'network_id': None,
        'times': [],
        'per_cluster': Counter(),
        'per_cmd': Counter(),
    })

    all_times: List[str] = []
    out = open(output_file, 'w') if output_file else None

    with open(filepath, 'r') as f:
        for line in f:
            rec = parse_text_line(line) or parse_json_line(line)
            if not rec:
                continue

            # Filters
            if mfr_filter:
                # use current frame mfr or fallback from index
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

            z = parse_zcl(rec['payload'])
            # unsolicited = server->client; keep OTA (0019) always
            if unsolicited_only and z['direction'] != 'server_to_client' and rec['clusterId'] != '0019':
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
            # choose mfr: frame or index fallback
            mfr_code = z['manufacturer_code'] or dni_mfr_index.get(rec['network_id'])
            dev['mfr_code'] = mfr_code
            dev['device_id'] = rec['device_id']
            dev['network_id'] = rec['network_id']
            dev['times'].append(rec['time'])
            dev['per_cluster'][rec['clusterId']] += 1
            if z['command_id'] is not None:
                dev['per_cmd'][z['command_id']] += 1
            if rec['time']:
                all_times.append(rec['time'])

    if out:
        out.close()

    time_range = (min(all_times), max(all_times)) if all_times else (None, None)
    return devices, time_range, dni_mfr_index

# ---------------------------------------------
# Output helpers
# ---------------------------------------------

def print_table(devices: dict, time_range: Tuple[Optional[str], Optional[str]], filters: dict, manufacturer_db: Dict[str, str]):
    # Header
    print("Analyse Zigbee — Trames non sollicitées")
    print("=" * 110)
    if time_range[0] and time_range[1]:
        try:
            t1 = datetime.strptime(time_range[0], "%Y-%m-%d %H:%M:%S.%f")
            t2 = datetime.strptime(time_range[1], "%Y-%m-%d %H:%M:%S.%f")
            dur = t2 - t1
            h, r = divmod(int(dur.total_seconds()), 3600)
            m, s = divmod(r, 60)
            print(f"Période: {time_range[0]} → {time_range[1]} (durée {h}h {m}m)")
        except ValueError:
            print(f"Période: {time_range[0]} → {time_range[1]}")
    # Filters line
    parts = []
    if filters.get('manufacturer'):
        parts.append(f"fabricant={filters['manufacturer']}")
    if filters.get('dni'):
        parts.append(f"dni=0x{filters['dni']:04X}")
    if filters.get('device_id'):
        parts.append(f"device_id={filters['device_id']}")
    if filters.get('device_name'):
        parts.append(f"nom contient '{filters['device_name']}'")
    if filters.get('clusters'):
        parts.append(f"clusters={','.join(filters['clusters'])}")
    if parts:
        print("Filtres: " + ", ".join(parts))
    print()

    # Table header
    print(f"{'Libellé appareil':<34} {'Mfr':<6} {'Fabricant':<28} {'Count':>6} {'Med LQI':>7} {'Med RSSI':>8}  {'Top clusters (nb)'}")
    print("-" * 110)
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
    print("-" * 110)
    print(f"{'TOTAL':<34} {'':<6} {'':<28} {total:>6}")


def print_csv(devices: dict, manufacturer_db: Dict[str, str]):
    print("Device Label,Mfr Code,Manufacturer,Count,Median LQI,Median RSSI,Network ID,Device ID,TopClusters")
    for name, info in sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True):
        med_lqi = statistics.median(info['lqis']) if info['lqis'] else 0
        med_rssi = statistics.median(info['rssis']) if info['rssis'] else 0
        mfr_code = info['mfr_code'] or ''
        mfr_name = manufacturer_db.get(mfr_code, '')
        top = info['per_cluster'].most_common(3)
        top_str = ";".join([f"{CLUSTER_NAMES.get(c, c)}({n})" for c, n in top])
        network_id_hex = f"0x{info['network_id']:04X}" if info['network_id'] else ''
        print(f'"{name}",{mfr_code},{mfr_name},{info["count"]},{med_lqi:.0f},{med_rssi:.0f},{network_id_hex},{info["device_id"]},"{top_str}"')

# ---------------------------------------------
# Heatmap / Top talkers
# ---------------------------------------------

def build_time_bins(times: List[str], bin_seconds: int = 60) -> Tuple[List[datetime], Dict[str, int]]:
    """Create fixed-size time bins across the log span; return bin edges and a map time_str->bin_index."""
    if not times:
        return [], {}
    dt = [datetime.strptime(t, "%Y-%m-%d %H:%M:%S.%f") for t in times if t]
    start = min(dt)
    end = max(dt)
    # extend end to include last bin
    end = end + timedelta(seconds=bin_seconds)
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


def generate_heatmaps(devices: dict, manufacturer_db: Dict[str, str], top_n: int = 10,
                       prefix: str = 'charts', bin_seconds: int = 60):
    """Generate two PNGs: talkers count heatmap & RSSI heatmap (French labels)."""
    # pick top talkers by count
    ranking = sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True)[:top_n]
    if not ranking:
        print("[INFO] Aucun appareil pour heatmap.")
        return

    # collect all times across top devices for binning
    all_times = []
    for _, info in ranking:
        all_times.extend([t for t in info['times'] if t])
    bins, time_index = build_time_bins(all_times, bin_seconds)
    if not bins:
        print("[INFO] Pas de données temporelles pour heatmap.")
        return

    # matrices: counts and RSSI (median per bin)
    dev_names = [name for name, _ in ranking]
    counts = np.zeros((len(dev_names), len(bins)), dtype=int)
    rssis = np.full((len(dev_names), len(bins)), np.nan, dtype=float)

    # Fill matrices
    for i, (name, info) in enumerate(ranking):
        # Build per-bin RSSI lists
        rssi_bins: Dict[int, List[int]] = defaultdict(list)
        for t, rssi in zip(info['times'], info['rssis'] + [None] * max(0, len(info['times']) - len(info['rssis']))):
            if not t:
                continue
            idx = time_index.get(t)
            if idx is None or idx >= len(bins) or idx < 0:
                continue
            counts[i, idx] += 1
            if rssi is not None:
                rssi_bins[idx].append(rssi)
        # compute medians
        for idx, values in rssi_bins.items():
            if values:
                rssis[i, idx] = statistics.median(values)

    # --- Plot: talkers count heatmap
    plt.figure(figsize=(12, max(4, 0.4 * len(dev_names))))
    plt.imshow(counts, aspect='auto', cmap='viridis')
    plt.colorbar(label='Comptes de messages / bin')
    plt.yticks(range(len(dev_names)), dev_names)
    xlabels = [b.strftime('%H:%M:%S') for b in bins]
    plt.xticks(range(len(bins))[::max(1, len(bins)//10)], xlabels[::max(1, len(bins)//10)], rotation=45)
    plt.title('Heatmap — Principaux émetteurs (comptes par minute)')
    plt.xlabel('Temps')
    plt.ylabel('Appareils')
    os.makedirs(os.path.dirname(prefix) or '.', exist_ok=True)
    out1 = f"{prefix}_talkers.png"
    plt.tight_layout()
    plt.savefig(out1)
    plt.close()
    print(f"[OK] Heatmap talkers: {out1}")

    # --- Plot: RSSI median heatmap
    plt.figure(figsize=(12, max(4, 0.4 * len(dev_names))))
    # replace nan with -100 for colormap bottom, but mask them so they show as blank
    masked = np.ma.masked_invalid(rssis)
    plt.imshow(masked, aspect='auto', cmap='plasma')
    plt.colorbar(label='RSSI médian (dBm)')
    plt.yticks(range(len(dev_names)), dev_names)
    plt.xticks(range(len(bins))[::max(1, len(bins)//10)], xlabels[::max(1, len(bins)//10)], rotation=45)
    plt.title('Heatmap — Bruit RSSI (médiane par minute)')
    plt.xlabel('Temps')
    plt.ylabel('Appareils')
    out2 = f"{prefix}_rssi.png"
    plt.tight_layout()
    plt.savefig(out2)
    plt.close()
    print(f"[OK] Heatmap RSSI: {out2}")

# ---------------------------------------------
# Main
# ---------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='Analyse Zigbee (Hubitat) — trames non sollicitées, top talkers & heatmap')
    parser.add_argument('logfile', help='Chemin du fichier log Zigbee')
    parser.add_argument('--manufacturer', '-m', help='Filtrer par code fabricant (hex, ex: 119C)')
    parser.add_argument('--manufacturer-db', help='Chemin JSON de la base fabricants (code->nom)')
    parser.add_argument('--device-id', '-d', type=int, help='Filtrer par Hubitat device ID (décimal)')
    parser.add_argument('--device-name', '-n', help='Filtrer par nom/étiquette (match partiel)')
    parser.add_argument('--dni', help='Filtrer par DNI (hex, ex: 78E0 ou 0x78E0)')
    parser.add_argument('--clusters', nargs='*', help='Limiter à certaines clusters (hex, ex: 0402 0500)')
    parser.add_argument('--output', '-o', help='Écrire les lignes filtrées dans ce fichier')
    parser.add_argument('--csv', action='store_true', help='Sortie CSV au lieu de tableau')
    parser.add_argument('--unsolicited-only', action='store_true', default=False,
                        help='Ne garder que les trames serveur->client (rapports, notifications).')
    parser.add_argument('--index-cache', help='Chemin JSON pour cache DNI->fabricant (lecture/écriture)')
    parser.add_argument('--heatmap', action='store_true', help='Générer heatmaps Top talkers & RSSI')
    parser.add_argument('--top-talkers', type=int, default=10, help='Nombre d’appareils à afficher dans le heatmap')
    parser.add_argument('--heatmap-prefix', default='charts', help='Préfixe fichier PNG des heatmaps (ex: rapports/charts)')

    args = parser.parse_args()

    # Parse DNI hex
    dni_filter = None
    if args.dni:
        dni_hex = args.dni.upper().replace('0X', '')
        dni_filter = int(dni_hex, 16)

    manufacturer_db = load_manufacturer_db(args.manufacturer_db)

    devices, time_range, dni_index = analyze_log(
        args.logfile,
        mfr_filter=args.manufacturer,
        device_id_filter=args.device_id,
        device_name_filter=args.device_name,
        dni_filter=dni_filter,
        cluster_filters=args.clusters,
        unsolicited_only=args.unsolicited_only,
        output_file=args.output,
        index_cache=args.index_cache,
    )

    if not devices:
        print("Aucune trame correspondant aux critères.", file=sys.stderr)
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
        print_table(devices, time_range, filters, manufacturer_db)

    # Heatmaps
    if args.heatmap:
        generate_heatmaps(devices, manufacturer_db, top_n=args.top_talkers, prefix=args.heatmap_prefix)

if __name__ == '__main__':
    main()
