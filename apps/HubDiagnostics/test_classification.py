#!/usr/bin/env python3
"""
Device classification test harness for HubDiagnostics.

Fetches fullJson for every device on the hub and shows:
  - What the bulk-only classification would be (current approach)
  - What the enhanced classification should be (with parentApp from fullJson)
  - Mismatches and unclassified devices

Usage:
  python3 test_classification.py [hub_ip]
  python3 test_classification.py 192.168.1.86
"""

import json
import sys
import urllib.request
import urllib.error
from collections import Counter, defaultdict

HUB_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.86"

# Mirror of INTEGRATION_TABLE from HubDiagnostics.groovy — longest-first ordering
INTEGRATION_TABLE = [
    ("home connect",  "cloud",      "Home Connect"),
    ("philips hue",   "lan_bridge", "Philips Hue"),
    ("hue bridge",    "lan_bridge", "Philips Hue"),
    ("bluetooth",     "paired",     "Bluetooth"),
    ("icomfort",      "cloud",      "iComfort"),
    ("homekit",       "paired",     "HomeKit"),
    ("samsung",       "cloud",      "SmartThings"),
    ("bthome",        "paired",     "BTHome"),
    ("shelly",        "lan_direct", "Shelly"),
    ("lutron",        "lan_bridge", "Lutron"),
    ("ecobee",        "cloud",      "ecobee"),
    ("google",        "cloud",      "Google Home"),
    ("govee",         "lan_direct", "Govee"),
    ("sonos",         "lan_direct", "Sonos"),
    ("alexa",         "cloud",      "Amazon Alexa"),
    ("kasa",          "lan_direct", "Kasa"),
    ("lifx",          "lan_direct", "LIFX"),
    ("wled",          "lan_direct", "WLED"),
    ("bond",          "lan_bridge", "Bond"),
    ("wiz",           "lan_direct", "WiZ"),
]

def lookup_integration(text):
    if not text:
        return None
    t = text.lower()
    for key, conn, name in INTEGRATION_TABLE:
        if key in t:
            return (conn, name)
    return None

def fetch(path):
    url = f"http://{HUB_IP}{path}"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"  [ERR] GET {path}: {e}", file=sys.stderr)
        return None

def flatten_devices(items):
    result = []
    for item in items:
        if not isinstance(item, dict):
            continue
        d = item.get("data") or item
        if d.get("id"):
            result.append(d)
        result.extend(flatten_devices(item.get("children", [])))
    return result

def classify_bulk(device):
    """Classify using bulk devicesList data only (boolean flags + parentAppId)."""
    if device.get("isZigbee"):   return ("paired",   "Zigbee")
    if device.get("isZwave"):    return ("paired",   "Z-Wave")
    if device.get("isMatter"):   return ("paired",   "Matter")
    if device.get("isBluetooth"):return ("paired",   "Bluetooth")
    if device.get("isLinked") or device.get("linked"): return ("hubmesh", "Hub Mesh")
    if device.get("isVirtual"):  return ("virtual",  "Virtual")

    parent_app_id = device.get("parentAppId")
    # (parentAppId is typically null in the bulk list for most device types)

    if device.get("isNetwork"):
        # Can't determine integration without parent app info from fullJson
        return ("lan_direct", "LAN Device [needs enrichment]")

    return ("other", "Other")

def classify_full(device, full):
    """Classify using fullJson data (enriched approach)."""
    # Start with flag-based — these are definitive
    if device.get("isZigbee"):    return ("paired",   "Zigbee")
    if device.get("isZwave"):     return ("paired",   "Z-Wave")
    if device.get("isMatter"):    return ("paired",   "Matter")
    if device.get("isBluetooth"): return ("paired",   "Bluetooth")
    if device.get("isLinked") or device.get("linked"): return ("hubmesh", "Hub Mesh")
    if device.get("isVirtual"):   return ("virtual",  "Virtual")

    # Parent app from fullJson (not available in bulk list for most devices)
    parent_app = full.get("parentApp") if full else None
    if parent_app:
        app_type_obj = parent_app.get("appType") or {}
        app_type_name = app_type_obj.get("name") or parent_app.get("name") or ""
        app_label     = parent_app.get("label") or parent_app.get("trueLabel") or ""

        match = lookup_integration(app_type_name) or lookup_integration(app_label)
        if match:
            return match

        # Not in table — use app type name as integration, derive conn from flags
        int_name = app_type_name or app_label or "Unknown App"
        conn = "lan_bridge" if device.get("isNetwork") else "other"
        return (conn, int_name)

    # Fallback: use controllerType from fullJson top level
    ct = (full.get("controllerType") or "").upper() if full else ""
    CT_MAP = {
        "ZGB": ("paired",    "Zigbee"),
        "ZWV": ("paired",    "Z-Wave"),
        "MTR": ("paired",    "Matter"),
        "LNK": ("hubmesh",   "Hub Mesh"),
        "NET": ("lan_direct","LAN Device"),
        "CLO": ("cloud",     "Cloud"),
        "VIR": ("virtual",   "Virtual"),
        "BTH": ("paired",    "Bluetooth"),
    }
    if ct in CT_MAP:
        return CT_MAP[ct]

    if device.get("isNetwork"):
        return ("lan_direct", "LAN Device")

    return ("other", "Other")

def main():
    print(f"Fetching devices from {HUB_IP}...", flush=True)
    data = fetch("/hub2/devicesList")
    if not data:
        print("Failed to fetch devices list"); sys.exit(1)

    devices = flatten_devices(data.get("devices", []))
    print(f"Found {len(devices)} devices. Fetching fullJson for each...\n", flush=True)

    rows = []
    controller_types_seen = Counter()
    app_type_names_seen = Counter()

    for i, dev in enumerate(devices):
        dev_id = dev.get("id")
        name   = dev.get("name") or dev.get("label") or f"Device {dev_id}"
        dtype  = dev.get("type") or ""

        sys.stderr.write(f"\r  {i+1}/{len(devices)} {name[:40]:<40}")
        sys.stderr.flush()

        full = fetch(f"/device/fullJson/{dev_id}")

        bulk_class = classify_bulk(dev)
        full_class = classify_full(dev, full)

        # Collect stats
        ct = (full.get("controllerType") or "") if full else ""
        if ct:
            controller_types_seen[ct] += 1

        parent_app = full.get("parentApp") if full else None
        if parent_app:
            atype = (parent_app.get("appType") or {}).get("name") or parent_app.get("name") or ""
            if atype:
                app_type_names_seen[atype] += 1

        flags = [k for k in ["isZigbee","isZwave","isMatter","isNetwork","isBluetooth","isVirtual","isLinked","isOrphan"] if dev.get(k)]

        rows.append({
            "id": dev_id,
            "name": name,
            "type": dtype,
            "flags": flags,
            "controllerType": ct,
            "parentAppName": ((parent_app.get("appType") or {}).get("name") or parent_app.get("name") or "") if parent_app else "",
            "bulk": bulk_class,
            "full": full_class,
            "changed": bulk_class != full_class,
        })

    sys.stderr.write("\n\n")

    # ── Summary ──────────────────────────────────────────────────────────────
    print("=" * 90)
    print("CLASSIFICATION SUMMARY")
    print("=" * 90)

    full_counter = Counter(r["full"][0] for r in rows)
    bulk_counter = Counter(r["bulk"][0] for r in rows)
    print(f"\n{'Connection type':<20} {'Bulk':>6} {'Full':>6}")
    print("-" * 35)
    for conn in ["paired","lan_bridge","lan_direct","cloud","virtual","hubmesh","other"]:
        b = bulk_counter.get(conn, 0)
        f = full_counter.get(conn, 0)
        diff = f"  ← +{f-b}" if f > b else (f"  ← -{b-f}" if b > f else "")
        print(f"  {conn:<18} {b:>6} {f:>6}{diff}")

    print(f"\n{'Integration':<30} {'Full count':>10}")
    print("-" * 42)
    int_counter = Counter(r["full"][1] for r in rows)
    for integ, cnt in int_counter.most_common():
        print(f"  {integ:<30} {cnt:>10}")

    print(f"\ncontrollerType values seen:")
    for ct, cnt in controller_types_seen.most_common():
        print(f"  {ct}: {cnt}")

    print(f"\nParent app type names seen (via fullJson):")
    for name, cnt in app_type_names_seen.most_common():
        print(f"  {name}: {cnt}")

    # ── Changed rows ─────────────────────────────────────────────────────────
    changed = [r for r in rows if r["changed"]]
    print(f"\n\n{'=' * 90}")
    print(f"CLASSIFICATION CHANGES ({len(changed)} devices upgraded by fullJson):")
    print("=" * 90)
    for r in sorted(changed, key=lambda x: x["full"][0]):
        print(f"  [{r['id']:>4}] {r['name'][:35]:<35}  bulk: {r['bulk'][0]+'/'+r['bulk'][1]:<30}  full: {r['full'][0]+'/'+r['full'][1]}")

    # ── Remaining Other ───────────────────────────────────────────────────────
    still_other = [r for r in rows if r["full"][0] == "other"]
    print(f"\n\n{'=' * 90}")
    print(f"STILL UNCLASSIFIED (other) after fullJson enrichment ({len(still_other)} devices):")
    print("=" * 90)
    for r in still_other:
        print(f"  [{r['id']:>4}] {r['name'][:35]:<35}  flags={r['flags']}  ct={r['controllerType']!r}  parentApp={r['parentAppName']!r}  type={r['type'][:30]}")

if __name__ == "__main__":
    main()
