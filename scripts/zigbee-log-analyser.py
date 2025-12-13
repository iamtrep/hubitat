#!/usr/bin/env python3
"""
Zigbee Unsolicited Traffic Analyzer (Hubitat logs: JSON or text)
- Parses ZCL frames (frame control, sequence, command id)
- Detects unsolicited device-originated traffic (server->client)
- Summarizes per device across clusters (not just OTA 0x0019)
"""

import re, json, statistics
from collections import defaultdict, Counter

# ---- Cluster names (extend as needed)
CLUSTER_NAMES = {
    "0000": "Basic",
    "0001": "Power Configuration",
    "0006": "On/Off",
    "0019": "OTA Upgrade",
    "0201": "Thermostat",
    "0400": "Illuminance Measurement",
    "0402": "Temperature Measurement",
    "0403": "Pressure Measurement",
    "0405": "Relative Humidity",
    "0406": "Occupancy Sensing",
    "0500": "IAS Zone",
    "0702": "Simple Metering",
    "0B04": "Electrical Measurement",
    "FF01": "Manufacturer-specific",
    "FFF1": "Manufacturer-specific",
    "8001": "ZDO (Hubitat internal)",
}

# ---- Global ZCL command names (quick map)
GLOBAL_COMMANDS = {
    0x00: "Read Attributes",
    0x01: "Read Attributes Response",
    0x02: "Write Attributes",
    0x04: "Write Attributes Undivided",
    0x05: "Write Attributes Response",
    0x07: "Configure Reporting",
    0x08: "Configure Reporting Response",
    0x0A: "Report Attributes",
    0x0B: "Default Response",
}

# ---- Some cluster-specific maps (optional; add more as you need)
IAS_ZONE_COMMANDS = {
    0x00: "Zone Enroll Response",
    0x01: "Zone Status Change Notification",
    # ... (Hubitat logs often show IAS status via global 0x0A reports too)
}

# ---- Hubitat JSON line parser (compatible with your current OTA script)
def parse_json_line(line: str):
    try:
        d = json.loads(line)
    except Exception:
        return None
    payload = d.get("payload", [])
    if isinstance(payload, list):
        pay = [b.upper() for b in payload]
    elif isinstance(payload, str):
        pay = [x.upper() for x in payload.split()]
    else:
        pay = []
    return {
        "name": d.get("name") or "unknown",
        "network_id": d.get("id"),
        "device_id": d.get("deviceId"),
        "profileId": (d.get("profileId") or "").upper(),
        "clusterId": (d.get("clusterId") or "").upper(),
        "sourceEndpoint": (d.get("sourceEndpoint") or ""),
        "destinationEndpoint": (d.get("destinationEndpoint") or ""),
        "groupId": (d.get("groupId") or ""),
        "sequence": d.get("sequence"),
        "lqi": d.get("lastHopLqi"),
        "rssi": d.get("lastHopRssi"),
        "time": d.get("time"),
        "type": d.get("type"),
        "payload": pay,
    }

# ---- Hubitat TEXT line parser (matches your attached cpz.log)
_text_re = re.compile(
    r"^name\s+(?P<name>.*?)\s+id\s+(?P<id>\d+)\s+"
    r"profileId\s+(?P<profile>[0-9A-Fa-f]{4})\s+clusterId\s+(?P<cluster>[0-9A-Fa-f]{4})\s+"
    r"sourceEndpoint\s+(?P<se>[0-9A-Fa-f]{2})\s+destinationEndpoint\s+(?P<de>[0-9A-Fa-f]{2}|FF)\s+"
    r"groupId\s+(?P<group>[0-9A-Fa-f]{4})\s+sequence\s+(?P<seq>[0-9A-Fa-f]+)\s+"
    r"lastHopLqi\s+(?P<lqi>\d+)\s+lastHopRssi\s+(?P<rssi>-?\d+)\s+"
    r"time\s+(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+type\s+(?P<type>\w+)\s+"
    r"deviceId\s+(?P<device>\d+)\s+payload\s+(?P<payload>(?:[0-9A-Fa-f]{2}(?:\s+[0-9A-Fa-f]{2})*))$"
)

def parse_text_line(line: str):
    m = _text_re.match(line.strip())
    if not m:
        return None
    pay = m.group("payload").strip().split()
    # sequence may be decimal or hex; logs typically show hex
    seq_str = m.group("seq")
    try:
        seq = int(seq_str, 16)
    except ValueError:
        seq = int(seq_str)
    return {
        "name": m.group("name"),
        "network_id": int(m.group("id")),
        "device_id": int(m.group("device")),
        "profileId": m.group("profile").upper(),
        "clusterId": m.group("cluster").upper(),
        "sourceEndpoint": m.group("se"),
        "destinationEndpoint": m.group("de"),
        "groupId": m.group("group"),
        "sequence": seq,
        "lqi": int(m.group("lqi")),
        "rssi": int(m.group("rssi")),
        "time": m.group("time"),
        "type": m.group("type"),
        "payload": [p.upper() for p in pay],
    }

# ---- ZCL frame decoder (handles manufacturer-specific frames correctly)
def parse_zcl(payload_hex):
    """
    ZCL layout:
      [0] Frame Control (bit0-1: frameType, bit2: manufSpecific, bit3: direction, bit4: disableDefaultRsp)
      [1..2] Manufacturer code (little-endian) IFF manufSpecific==1
      [next] Sequence number
      [next] Command ID
    """
    out = {
        "fc": None, "frame_type": None, "manuf_specific": False,
        "direction": None, "disable_default_rsp": False,
        "manufacturer_code": None, "sequence": None, "command_id": None,
    }
    if not payload_hex or len(payload_hex) < 3:
        return out
    fc = int(payload_hex[0], 16)
    out["fc"] = fc
    out["frame_type"] = "cluster" if (fc & 0x03) == 0x01 else "global"
    out["manuf_specific"] = bool(fc & 0x04)
    out["direction"] = "server_to_client" if (fc & 0x08) else "client_to_server"
    out["disable_default_rsp"] = bool(fc & 0x10)
    idx = 1
    if out["manuf_specific"]:
        if len(payload_hex) < idx + 2:  # incomplete
            return out
        # manufacturer code is little-endian
        m_lo = int(payload_hex[idx], 16)
        m_hi = int(payload_hex[idx + 1], 16)
        out["manufacturer_code"] = f"{(m_hi << 8 | m_lo):04X}"
        idx += 2
    if len(payload_hex) < idx + 2:
        return out
    out["sequence"] = int(payload_hex[idx], 16)
    out["command_id"] = int(payload_hex[idx + 1], 16)
    return out

# ---- Main analyzer (unsolicited only unless told otherwise)
def analyze_log(filepath, unsolicited_only=True, cluster_filter=None, name_filter=None):
    devices = defaultdict(lambda: {
        "total": 0, "lqis": [], "rssis": [],
        "per_cluster": Counter(), "per_cmd": Counter(),
        "manufacturer": None, "device_id": None, "network_id": None,
        "first_time": None, "last_time": None,
    })
    total, parsed = 0, 0
    with open(filepath, "r") as f:
        for line in f:
            total += 1
            if not line.strip():
                continue
            rec = parse_text_line(line) or parse_json_line(line)
            if rec is None:
                continue
            parsed += 1
            name = rec["name"]
            cluster = rec["clusterId"]
            if name_filter and name_filter.lower() not in name.lower():
                continue
            if cluster_filter and cluster.upper() not in [c.upper() for c in cluster_filter]:
                continue
            z = parse_zcl(rec["payload"])
            # unsolicited = server->client; keep OTA regardless (so you still see 0x0019 traffic)
            if unsolicited_only and z["direction"] != "server_to_client" and cluster != "0019":
                continue
            d = devices[name]
            d["total"] += 1
            d["per_cluster"][cluster] += 1
            if z["command_id"] is not None:
                d["per_cmd"][z["command_id"]] += 1
            if z["manufacturer_code"]:
                d["manufacturer"] = z["manufacturer_code"]
            d["device_id"] = rec["device_id"]
            d["network_id"] = rec["network_id"]
            if rec["lqi"] is not None:
                d["lqis"].append(rec["lqi"])
            if rec["rssi"] is not None:
                d["rssis"].append(rec["rssi"])
            # time range per device
            if rec["time"]:
                if d["first_time"] is None:
                    d["first_time"] = rec["time"]
                d["last_time"] = rec["time"]
    return devices, {"total_lines": total, "parsed_lines": parsed}

# ---- Console view
def print_summary(devices):
    hdr = f"{'Device':<34} {'Mfr':<6} {'Count':>6} {'Med LQI':>7} {'Med RSSI':>8}  Top clusters (count)"
    print(hdr)
    print("-" * len(hdr))
    for name, info in sorted(devices.items(), key=lambda kv: kv[1]["total"], reverse=True):
        med_lqi = statistics.median(info["lqis"]) if info["lqis"] else 0
        med_rssi = statistics.median(info["rssis"]) if info["rssis"] else 0
        mfr = info["manufacturer"] or ""
        top = info["per_cluster"].most_common(3)
        top_str = ", ".join([f"{CLUSTER_NAMES.get(c, c)}({n})" for c, n in top])
        print(f"{name:<34} {mfr:<6} {info['total']:>6} {med_lqi:>7.0f} {med_rssi:>8.0f}  {top_str}")

if __name__ == "__main__":
    # Example usage: unsolicited only, all clusters
    devices, stats = analyze_log("cpz.log", unsolicited_only=True)
    print_summary(devices)
    print(f"\nLines total: {stats['total_lines']} parsed: {stats['parsed_lines']}")
