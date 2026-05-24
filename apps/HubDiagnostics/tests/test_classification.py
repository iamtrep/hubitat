#!/usr/bin/env python3
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""
Mode-4 pure unit test for HubDiagnostics device classification.

Mirrors the Groovy logic in:
  - cleanIntegrationName()    (strips trailing app-name noise)
  - INTEGRATION_OVERRIDES     (small map: bridges + aliases)
  - lookupIntegration()       (substring match against INTEGRATION_OVERRIDES)
  - classifyDevice() branch 2 (parent-app → algorithm-primary + override)

Hub-free: no network calls, no fixtures beyond synthetic dicts.
Discoverable by run-tests.sh (tests/test_*.py → python3 -m pytest).
"""

# ── Mirror of Groovy constants ──────────────────────────────────────────────

CONN_PAIRED     = "paired"
CONN_LAN_DIRECT = "lan_direct"
CONN_LAN_BRIDGE = "lan_bridge"
CONN_CLOUD      = "cloud"
CONN_VIRTUAL    = "virtual"
CONN_HUBMESH    = "hubmesh"
CONN_OTHER      = "other"

# Mirror of INTEGRATION_OVERRIDES — ordered longest-first
# Sole purpose: force lan_bridge for LAN-bridge hubs (Hue/Lutron/Bond) that the
# isNetwork algorithm would misclassify as lan_direct.  Integration names and
# cloud/lan_direct distinction are always derived algorithmically.
INTEGRATION_OVERRIDES = [
    ("philips hue", CONN_LAN_BRIDGE, "Philips Hue"),
    ("hue bridge",  CONN_LAN_BRIDGE, "Philips Hue"),
    ("lutron",      CONN_LAN_BRIDGE, "Lutron"),
    ("bond",        CONN_LAN_BRIDGE, "Bond"),
]

# Mirror of cleanIntegrationName() suffix list — longest-first
_CLEAN_SUFFIXES = [
    "(connect)", "connect", "device manager", "device service", "devices", "device",
    "integration", "manager", "service", "controller", "account",
]


def clean_integration_name(raw: str) -> str:
    """Mirror of Groovy cleanIntegrationName(String)."""
    if not raw:
        return raw
    s = raw.strip()
    changed = True
    while changed:
        changed = False
        lower = s.lower()
        for suf in _CLEAN_SUFFIXES:
            if lower.endswith(suf):
                candidate = s[: len(s) - len(suf)].strip()
                if candidate:
                    s = candidate
                    changed = True
                    break
    return s or raw


VALID_CONN = {
    "paired", "lan_direct", "lan_bridge", "cloud", "virtual", "hubmesh", "other"
}


def merge_overrides(builtin: list, user: dict) -> list:
    """
    Mirror of Groovy getIntegrationOverrides() merge logic.
    builtin: list of (keyword, conn, name) tuples (the INTEGRATION_OVERRIDES constant).
    user: dict of {raw_key: {conn?, name?}} from the user config file.
    Returns a merged list of (keyword, conn, name) tuples with user entries first.
    Invalid conn values are rejected; name-only entries keep the builtin conn if present.
    """
    merged_dict: dict = {}  # keyword -> (conn, name), user entries first

    for raw_key, raw_val in user.items():
        if raw_key == "_README":
            continue
        key = raw_key.lower().strip()
        if not key:
            continue
        entry_conn = None
        entry_name = None
        if isinstance(raw_val, dict):
            conn_candidate = (raw_val.get("conn") or "").strip()
            if conn_candidate in VALID_CONN:
                entry_conn = conn_candidate
            name_candidate = (raw_val.get("name") or "").strip()
            if name_candidate:
                entry_name = name_candidate
        if entry_conn is not None or entry_name is not None:
            merged_dict[key] = (entry_conn, entry_name)

    # Append built-in entries not already overridden
    for kw, conn, name in builtin:
        if kw not in merged_dict:
            merged_dict[kw] = (conn, name)

    return [(kw, cv[0], cv[1]) for kw, cv in merged_dict.items()]


def lookup_integration_with_map(text: str, overrides: list):
    """lookup_integration() operating on an arbitrary overrides list."""
    if not text:
        return None
    lower = text.lower()
    for keyword, conn, name in overrides:
        if keyword in lower:
            return (conn, name)
    return None


def lookup_integration(text: str):
    """Mirror of Groovy lookupIntegration(String). Returns (conn, name) or None."""
    return lookup_integration_with_map(text, INTEGRATION_OVERRIDES)


def classify_device(device: dict, app_info: dict | None) -> tuple[str, str]:
    """
    Mirror of Groovy classifyDevice() — returns (connectionType, integration).
    Covers: radio flags, hub-mesh, virtual, parent-app algorithm-primary, isNetwork, fallback.
    app_info: dict with keys 'type', 'label', 'user' (True if community app), or None.
    """
    # 1. Radio / protocol flags
    if device.get("isZigbee"):
        return (CONN_PAIRED, "Zigbee")
    if device.get("isZwave"):
        return (CONN_PAIRED, "Z-Wave")
    if device.get("isMatter"):
        return (CONN_PAIRED, "Matter")
    if device.get("isBluetooth"):
        return (CONN_PAIRED, "Bluetooth")
    if device.get("isLinked") or device.get("linked"):
        return (CONN_HUBMESH, "Hub Mesh")
    if device.get("isVirtual"):
        return (CONN_VIRTUAL, "Virtual")

    # 2. Parent app present — algorithm-primary
    if app_info:
        app_type  = str(app_info.get("type")  or "")
        app_label = str(app_info.get("label") or "")
        raw = app_type or app_label
        ov = lookup_integration(app_type) or lookup_integration(app_label)
        integration = ov[1] if ov else clean_integration_name(raw)
        if ov and ov[0] is not None:
            conn = ov[0]
        else:
            conn = CONN_LAN_DIRECT if device.get("isNetwork") else CONN_CLOUD
        return (conn, integration or raw)

    # 3. LAN flag, no parent app
    if device.get("isNetwork"):
        return (CONN_LAN_DIRECT, "LAN Device")

    # 4. Fallback
    return (CONN_OTHER, "Other")


# ── Tests ───────────────────────────────────────────────────────────────────


# --- cleanIntegrationName unit cases ---

def test_clean_device_service():
    assert clean_integration_name("YoLink Device Service") == "YoLink"


def test_clean_device_manager():
    assert clean_integration_name("Sonoff Wifi Device Manager") == "Sonoff Wifi"


def test_clean_integration_suffix():
    assert clean_integration_name("Ecobee Integration") == "Ecobee"


def test_clean_manager_suffix():
    assert clean_integration_name("Mobile App Manager") == "Mobile App"


def test_clean_device_service_multi_word():
    """'Device Service' is stripped as a two-word phrase before 'Device' alone."""
    assert clean_integration_name("MQTT Device Service") == "MQTT"


def test_clean_noop():
    """Names that need no stripping are returned verbatim."""
    assert clean_integration_name("B-Hyve") == "B-Hyve"


def test_clean_noop_kasa():
    assert clean_integration_name("Kasa") == "Kasa"


def test_clean_noop_shelly():
    assert clean_integration_name("Shelly") == "Shelly"


def test_clean_never_returns_empty():
    """If stripping would empty the string, keep the original."""
    # "Service" stripped from "Service" → "" → return original
    assert clean_integration_name("Service") == "Service"


def test_clean_connect_parens():
    assert clean_integration_name("Philips Hue (Connect)") == "Philips Hue"


def test_clean_connect_plain():
    assert clean_integration_name("Lutron Connect") == "Lutron"


def test_clean_devices_suffix():
    assert clean_integration_name("LIFX Devices") == "LIFX"


def test_clean_controller_suffix():
    assert clean_integration_name("Kasa Controller") == "Kasa"


def test_clean_account_suffix():
    assert clean_integration_name("Ecobee Account") == "Ecobee"


# --- radio / protocol flags ---

def test_zigbee_flag():
    conn, name = classify_device({"isZigbee": True}, None)
    assert conn == CONN_PAIRED
    assert name == "Zigbee"


def test_zwave_flag():
    conn, name = classify_device({"isZwave": True}, None)
    assert conn == CONN_PAIRED
    assert name == "Z-Wave"


def test_matter_flag():
    conn, name = classify_device({"isMatter": True}, None)
    assert conn == CONN_PAIRED
    assert name == "Matter"


def test_bluetooth_flag():
    conn, name = classify_device({"isBluetooth": True}, None)
    assert conn == CONN_PAIRED
    assert name == "Bluetooth"


def test_hubmesh_isLinked():
    conn, name = classify_device({"isLinked": True}, None)
    assert conn == CONN_HUBMESH
    assert name == "Hub Mesh"


def test_hubmesh_linked():
    conn, name = classify_device({"linked": True}, None)
    assert conn == CONN_HUBMESH
    assert name == "Hub Mesh"


def test_virtual_flag():
    conn, name = classify_device({"isVirtual": True}, None)
    assert conn == CONN_VIRTUAL
    assert name == "Virtual"


# --- community integration auto-detect (no override, derive from parent app) ---

def test_community_yolink_cloud():
    """YoLink Device Service → (cloud, "YoLink") — no isNetwork."""
    app = {"type": "YoLink Device Service", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "YoLink"


def test_community_bhyve_cloud():
    """B-Hyve → (cloud, "B-Hyve") — no stripping needed."""
    app = {"type": "B-Hyve", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "B-Hyve"


def test_community_sonoff_cloud():
    """Sonoff Wifi Device Manager → (cloud, "Sonoff Wifi")."""
    app = {"type": "Sonoff Wifi Device Manager", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "Sonoff Wifi"


def test_community_ecobee_cloud():
    """Ecobee Integration → (cloud, "Ecobee")."""
    app = {"type": "Ecobee Integration", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "Ecobee"


# --- LAN-direct derive (isNetwork + parent app, no override) ---

def test_lan_direct_shelly():
    """Shelly + isNetwork → (lan_direct, "Shelly") — not in overrides."""
    app = {"type": "Shelly", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_DIRECT
    assert name == "Shelly"


def test_lan_direct_sonos():
    """Sonos + isNetwork → (lan_direct, "Sonos")."""
    app = {"type": "Sonos", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_DIRECT
    assert name == "Sonos"


# --- bridge overrides ---

def test_bridge_philips_hue():
    """Philips Hue Bridge + isNetwork → (lan_bridge, "Philips Hue") via override."""
    app = {"type": "Philips Hue Bridge", "user": False}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Philips Hue"


def test_bridge_hue_bridge_keyword():
    """'Hue Bridge Integration' keyword triggers override."""
    app = {"type": "Hue Bridge Integration"}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Philips Hue"


def test_bridge_lutron():
    """Lutron → lan_bridge via override regardless of isNetwork."""
    app = {"type": "Lutron Connect"}
    conn, name = classify_device({}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Lutron"


def test_bridge_bond():
    """Bond → lan_bridge via override."""
    app = {"type": "Bond Home"}
    conn, name = classify_device({}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Bond"


# --- samsung derives algorithmically (no override needed) ---

def test_samsung_smartthings_cloud():
    """Samsung SmartThings → name cleaned algorithmically; conn derived (cloud, no isNetwork)."""
    app = {"type": "Samsung SmartThings"}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    # cleanIntegrationName strips nothing from "Samsung SmartThings" → name is the raw type
    assert name == "Samsung SmartThings"


# --- no parent app ---

def test_no_parent_isnetwork():
    """No parent app + isNetwork → (lan_direct, "LAN Device")."""
    conn, name = classify_device({"isNetwork": True}, None)
    assert conn == CONN_LAN_DIRECT
    assert name == "LAN Device"


def test_no_parent_no_signal():
    """Nothing → (other, "Other")."""
    conn, name = classify_device({}, None)
    assert conn == CONN_OTHER
    assert name == "Other"


# ── Integration-overrides merge tests ───────────────────────────────────────


def test_merge_adds_new_integration():
    """User adds a new entry not in built-ins; lookup finds it."""
    user = {"yolink": {"conn": "cloud", "name": "YoLink"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("YoLink Device Service", merged)
    assert result is not None, "expected a match for 'yolink'"
    assert result[0] == "cloud"
    assert result[1] == "YoLink"


def test_merge_user_overrides_builtin():
    """User forces lutron to cloud; user value wins over built-in lan_bridge."""
    user = {"lutron": {"conn": "cloud", "name": "Lutron Cloud"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("Lutron Connect", merged)
    assert result is not None, "expected a match for 'lutron'"
    assert result[0] == "cloud", f"expected cloud, got {result[0]}"
    assert result[1] == "Lutron Cloud"


def test_merge_invalid_conn_rejected():
    """Invalid conn value is rejected; entry is omitted from merged map."""
    user = {"badentry": {"conn": "banana", "name": "Bad"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("badentry device", merged)
    # conn is None because it was rejected; name-only means entry IS added (name present)
    # Per spec: if only name is invalid too and conn invalid, entry has no fields → omitted.
    # Here name IS valid so entry is present but conn is None.
    assert result is None or result[0] is None, (
        f"expected conn=None (rejected), got {result}"
    )


def test_merge_invalid_conn_name_only_accepted():
    """Entry with invalid conn but valid name: name applies, conn stays None."""
    user = {"mycloud": {"conn": "banana", "name": "My Cloud"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("mycloud service", merged)
    assert result is not None, "expected a match (valid name kept entry)"
    assert result[0] is None, f"conn should be None (rejected), got {result[0]}"
    assert result[1] == "My Cloud"


def test_merge_name_only_entry():
    """Name-only entry (no conn): name applied, conn left None (derived by caller)."""
    user = {"shelly": {"name": "Shelly Plus"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("Shelly Device Service", merged)
    assert result is not None
    assert result[0] is None,  f"conn should be None (not set), got {result[0]}"
    assert result[1] == "Shelly Plus"


def test_merge_empty_user_config():
    """Empty user dict → merged map equals built-in defaults."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {})
    assert len(merged) == len(INTEGRATION_OVERRIDES)
    for (kw, conn, name), (bkw, bconn, bname) in zip(merged, INTEGRATION_OVERRIDES):
        assert kw == bkw
        assert conn == bconn
        assert name == bname


def test_merge_readme_key_skipped():
    """_README key in user file is ignored."""
    user = {
        "_README": "documentation string",
        "yolink": {"conn": "cloud", "name": "YoLink"},
    }
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    keywords = [kw for kw, _, _ in merged]
    assert "_readme" not in keywords, "_README should be skipped"
    assert "yolink" in keywords


def test_merge_user_wins_on_precedence():
    """User entry appears before built-ins in iteration order (user wins on first match)."""
    # Built-in has "philips hue" and "hue bridge". User overrides "hue bridge" to cloud.
    user = {"hue bridge": {"conn": "cloud", "name": "My Hue"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    # "Hue Bridge Device" should match user entry first
    result = lookup_integration_with_map("hue bridge device", merged)
    assert result is not None
    assert result[0] == "cloud"
    assert result[1] == "My Hue"


# ── Standalone runner ────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys
    import types
    import traceback

    module = sys.modules[__name__]
    tests = [
        (name, obj)
        for name, obj in vars(module).items()
        if name.startswith("test_") and callable(obj)
    ]
    tests.sort(key=lambda t: t[0])

    passed = 0
    failed = 0
    for name, fn in tests:
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
