#!/usr/bin/env python3
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""
Mode-4 pure unit test for HubDiagnostics device classification.

Mirrors the Groovy logic in:
  - cleanIntegrationName()    (strips trailing app-name noise → display name)
  - INTEGRATION_OVERRIDES     (connection-type EXCEPTIONS only — bridges + AirPlay)
  - BUILTIN_CLOUD_DRIVERS     (built-in standalone cloud device drivers — OpenWeatherMap)
  - lookupIntegration()       (substring match against INTEGRATION_OVERRIDES)
  - getIntegrationOverrides() merge (user File Manager config overlaid on built-ins)
  - classifyDevice() branch 1c (built-in cloud driver table)
  - classifyDevice() branch 2  (parent-app → algorithm-primary + override)
  - classifyDevice() branch 2b (override file matched on driver type name, no parent app)

Model under test (v5.60.0): the override map holds ONLY the connection types the
isNetwork derivation can't infer. Everything else rides on the derivation —
name from cleanIntegrationName, built-in/community from the hub's appInfo.user
flag, conn from device.isNetwork (LAN ⇒ lan_direct, else cloud). Built-in cloud
device drivers with no derivation signal at all (OpenWeatherMap) are enumerated in
BUILTIN_CLOUD_DRIVERS. The File Manager config is the user escape hatch for
additional connection-type exceptions, matched against the parent-app name or —
for a standalone device with no parent app — the driver type name.

Hub-free: no network calls, no fixtures beyond synthetic dicts.
Discoverable by run-tests.sh (tests/test_*.py → python3 -m pytest).
"""

# ── Mirror of Groovy constants ──────────────────────────────────────────────

CONN_ZIGBEE     = "zigbee"
CONN_ZWAVE      = "zwave"
CONN_MATTER     = "matter"
CONN_BLUETOOTH  = "bluetooth"
CONN_HOMEKIT    = "homekit"
CONN_LAN_DIRECT = "lan_direct"
CONN_LAN_BRIDGE = "lan_bridge"
CONN_CLOUD      = "cloud"
CONN_VIRTUAL    = "virtual"
CONN_HUBMESH    = "hubmesh"
CONN_OTHER      = "other"

# Mirror of INTEGRATION_OVERRIDES — built-in parent-managed integrations, ordered longest-first.
# Tuples are (keyword, conn, name). Each carries a name: their devices arrive without a parentAppId
# in the bulk list (so the parent-app derivation can't see them, and the deep-enrich pass never runs),
# and a conn-only override would demote them to standalone. The conn also fixes the connection type the
# isNetwork derivation can't infer (bridges → lan_bridge; AirPlay isNetwork=false but local → lan_direct).
INTEGRATION_OVERRIDES = [
    ("philips hue", CONN_LAN_BRIDGE,  "Philips Hue"),
    ("hue bridge",  CONN_LAN_BRIDGE,  "Philips Hue"),
    ("airplay",     CONN_LAN_DIRECT,  "AirPlay"),
    ("lutron",      CONN_LAN_BRIDGE,  "Lutron"),
    ("bond",        CONN_LAN_BRIDGE,  "Bond"),
    ("homekit",     CONN_HOMEKIT,     "HomeKit"),
]

# Mirror of BUILTIN_CLOUD_DRIVERS — built-in driver type name (lowercased) → integration name.
# Standalone Hubitat cloud pollers with no parent app, no radio, isNetwork=false.
BUILTIN_CLOUD_DRIVERS = {
    "openweathermap",
    "ecobee thermostat",
    "pushover driver",
    "mobile app device",
}

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
    "zigbee", "zwave", "matter", "bluetooth", "homekit",
    "lan_direct", "lan_bridge", "cloud", "virtual", "hubmesh", "other"
}


def merge_overrides(builtin: list, user: dict) -> list:
    """
    Mirror of Groovy getIntegrationOverrides() merge logic.
    builtin: list of (keyword, conn, name) tuples (the INTEGRATION_OVERRIDES constant).
    user: dict of {raw_key: {conn?, name?}} from the user config file.
    Returns a merged list of (keyword, conn, name) tuples with user entries first.
    Keys starting with '_' (documentation / commented-out entries) are skipped.
    Invalid conn values are rejected; name-only entries keep conn None (derived by caller).
    """
    merged_dict: dict = {}  # keyword -> (conn, name), user entries first

    for raw_key, raw_val in user.items():
        if str(raw_key).startswith("_"):  # documentation / commented-out keys
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


def classify_device(
    device: dict,
    app_info: dict | None,
    community_drivers: set | None = None,
    overrides: list | None = None,
) -> tuple[str, str]:
    """
    Mirror of Groovy classifyDevice() — returns (connectionType, integration).
    Covers: radio flags, hub-mesh, virtual, virtual-name heuristic, built-in cloud table,
    parent-app algorithm-primary, driver-name override, isNetwork, fallback.
    app_info: dict with keys 'type', 'label', 'user' (True if community app), or None.
    community_drivers: set of community driver type names; a device.type NOT in it is built-in.
    overrides: merged (keyword, conn, name) list; defaults to the built-in INTEGRATION_OVERRIDES.
    """
    community_drivers = community_drivers or set()
    overrides = overrides if overrides is not None else INTEGRATION_OVERRIDES

    driver_type = str(device.get("type") or "")
    driver_is_builtin = driver_type not in community_drivers
    driver_type_lower = driver_type.lower()

    # 1. Radio flags — the specific radio IS the connection type; a radio device is not an integration.
    if device.get("isZigbee"):
        return (CONN_ZIGBEE, None)
    if device.get("isZwave"):
        return (CONN_ZWAVE, None)
    if device.get("isMatter"):
        return (CONN_MATTER, None)
    if device.get("isBluetooth"):
        return (CONN_BLUETOOTH, None)
    if device.get("isLinked") or device.get("linked"):
        return (CONN_HUBMESH, None)
    if device.get("isVirtual"):
        return (CONN_VIRTUAL, None)

    # 1b. Built-in Virtual* drivers without the isVirtual flag
    if driver_is_builtin and (driver_type_lower.startswith("virtual ") or driver_type_lower == "virtual"):
        return (CONN_VIRTUAL, None)

    # 1c. Built-in cloud device drivers — standalone cloud pollers; set CONN_CLOUD, no integration
    if driver_is_builtin and driver_type_lower in BUILTIN_CLOUD_DRIVERS:
        return (CONN_CLOUD, None)

    # 2. Parent app present — algorithm-primary
    if app_info:
        app_type  = str(app_info.get("type")  or "")
        app_label = str(app_info.get("label") or "")
        raw = app_type or app_label
        ov = lookup_integration_with_map(app_type, overrides) or lookup_integration_with_map(app_label, overrides)
        # Name: override name wins ONLY if present; otherwise cleaned parent-app name.
        integration = ov[1] if (ov and ov[1]) else clean_integration_name(raw)
        # Conn: override conn wins ONLY if present; otherwise LAN signal ⇒ local, else cloud.
        if ov and ov[0] is not None:
            conn = ov[0]
        else:
            conn = CONN_LAN_DIRECT if device.get("isNetwork") else CONN_CLOUD
        return (conn, integration or raw)

    # 2b. Override file matched on driver type name (standalone device, no parent app).
    #     Wins over the isNetwork derivation below. A `name` means "this is an integration" (label it);
    #     no `name` means connection-type-only — the device is standalone, so integration is None and
    #     the caller omits it from the integration breakdown (no fabricated per-driver integration).
    type_ov = lookup_integration_with_map(driver_type, overrides)
    if type_ov and type_ov[0] is not None:
        integration = type_ov[1] if type_ov[1] else None
        return (type_ov[0], integration)

    # 3. LAN flag, no parent app — standalone (integration None); deep pass may enrich it
    if device.get("isNetwork"):
        return (CONN_LAN_DIRECT, None)

    # 4. Fallback — standalone, no integration
    return (CONN_OTHER, None)


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


# --- radio flags: the specific radio IS the connection type; not an integration (None) ---

def test_zigbee_flag():
    assert classify_device({"isZigbee": True}, None) == (CONN_ZIGBEE, None)


def test_zwave_flag():
    assert classify_device({"isZwave": True}, None) == (CONN_ZWAVE, None)


def test_matter_flag():
    assert classify_device({"isMatter": True}, None) == (CONN_MATTER, None)


def test_bluetooth_flag():
    assert classify_device({"isBluetooth": True}, None) == (CONN_BLUETOOTH, None)


def test_hubmesh_isLinked():
    assert classify_device({"isLinked": True}, None) == (CONN_HUBMESH, None)


def test_hubmesh_linked():
    assert classify_device({"linked": True}, None) == (CONN_HUBMESH, None)


def test_virtual_flag():
    assert classify_device({"isVirtual": True}, None) == (CONN_VIRTUAL, None)


# --- derivation with NO override entry (the common case) -----------------------
# These integrations are deliberately absent from INTEGRATION_OVERRIDES. They prove
# the algorithm derives the right (conn, name) from isNetwork + cleanIntegrationName
# alone — the whole reason the override map stays lean.

def test_derive_yolink_cloud():
    """YoLink Device Service, no isNetwork → (cloud, "YoLink")."""
    app = {"type": "YoLink Device Service", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "YoLink"


def test_derive_bhyve_cloud():
    """B-Hyve → (cloud, "B-Hyve") — no stripping needed."""
    app = {"type": "B-Hyve", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "B-Hyve"


def test_derive_sonoff_cloud():
    """Sonoff Wifi Device Manager → (cloud, "Sonoff Wifi")."""
    app = {"type": "Sonoff Wifi Device Manager", "user": True}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "Sonoff Wifi"


def test_derive_ecobee_cloud():
    """Ecobee Integration (built-in) → (cloud, "Ecobee") — no entry needed."""
    app = {"type": "Ecobee Integration", "user": False}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "Ecobee"


def test_derive_kasa_lan_direct():
    """Kasa + isNetwork → (lan_direct, "Kasa") — derived, not in overrides."""
    app = {"type": "Kasa Device Manager", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_DIRECT
    assert name == "Kasa"


def test_derive_shelly_lan_direct():
    """Shelly + isNetwork → (lan_direct, "Shelly")."""
    app = {"type": "Shelly", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_DIRECT
    assert name == "Shelly"


def test_derive_sonos_lan_direct():
    """Sonos + isNetwork → (lan_direct, "Sonos")."""
    app = {"type": "Sonos", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_DIRECT
    assert name == "Sonos"


def test_derive_samsung_smartthings_cloud():
    """Samsung SmartThings, no isNetwork → (cloud, "Samsung SmartThings") — name unchanged by cleaner."""
    app = {"type": "Samsung SmartThings"}
    conn, name = classify_device({}, app)
    assert conn == CONN_CLOUD
    assert name == "Samsung SmartThings"


def test_derive_homekit_connection():
    """HomeKit Controller commissions the accessory into the hub as its HAP controller (enrolled in,
    not merely reached over IP), so its connection is "homekit" — not lan_direct — even though
    isNetwork=true would derive lan_direct. Here a parent app is present, so it's also a real
    integration ("HomeKit", from the cleaned app name); the "homekit" override pins the connection."""
    app = {"type": "HomeKit Controller", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_HOMEKIT
    assert name == "HomeKit"


# --- connection-type exceptions (the only reason the override map exists) -------

def test_bridge_philips_hue():
    """Philips Hue Bridge + isNetwork → lan_bridge (override beats the lan_direct derivation);
    the override now supplies the integration name 'Philips Hue'."""
    app = {"type": "Philips Hue Bridge", "user": False}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Philips Hue"


def test_bridge_hue_bridge_keyword():
    """'Hue Bridge Integration' keyword triggers the lan_bridge override → name 'Philips Hue'."""
    app = {"type": "Hue Bridge Integration"}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Philips Hue"


def test_bridge_lutron():
    """Hypothetical parent-app path: a 'Lutron Integration' app would clean to 'Lutron'. Note the
    real hub app type is 'Lutron Integrator' (cleaner does NOT strip 'integrator'), and bulk
    devices carry no parentAppId anyway — see test_lutron_no_parent_app_groups for the real path."""
    app = {"type": "Lutron Integration"}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Lutron"


def test_lutron_no_parent_app_groups():
    """Regression: the real Lutron case. /hub2/devicesList exposes no parentAppId for any device,
    so Lutron devices (driver types 'Lutron Switch/Pico/Dimmer/Telnet', isNetwork=true, built-in)
    reach branch 2b and match the 'lutron' override. The override's name groups every driver type
    under one 'Lutron' integration; without it they split per driver type (the v5.56.0 regression)."""
    for dtype in ("Lutron Switch", "Lutron Pico", "Lutron Dimmer", "Lutron Telnet"):
        dev = {"type": dtype, "isNetwork": True}
        conn, name = classify_device(dev, None)  # no parent app, built-in driver
        assert conn == CONN_LAN_BRIDGE, f"{dtype}: expected lan_bridge, got {conn}"
        assert name == "Lutron", f"{dtype}: expected 'Lutron', got '{name}' (driver-type split)"


def test_bridge_bond():
    """Bond is a built-in lan_bridge integration (the Bond bridge fronts its devices); name 'Bond'."""
    app = {"type": "BOND Home Integration", "user": True}
    conn, name = classify_device({"isNetwork": True}, app)
    assert conn == CONN_LAN_BRIDGE
    assert name == "Bond"


def test_airplay_lan_direct():
    """AirPlay devices carry MAC-format DNIs with isNetwork=false — without the override they'd
    mis-derive to cloud. The override forces lan_direct."""
    app = {"type": "AirPlay", "user": True}
    conn, name = classify_device({}, app)  # no isNetwork → would derive cloud
    assert conn == CONN_LAN_DIRECT
    assert name == "AirPlay"


# --- no parent app: standalone, no integration ---

def test_no_parent_isnetwork():
    """No parent app + isNetwork → (lan_direct, None) — standalone."""
    assert classify_device({"isNetwork": True}, None) == (CONN_LAN_DIRECT, None)


def test_no_parent_no_signal():
    """Nothing → (other, None) — standalone."""
    assert classify_device({}, None) == (CONN_OTHER, None)


# --- built-in cloud device drivers (branch 1c) --------------------------------
# Standalone Hubitat cloud pollers (OpenWeatherMap, etc.): the table gives them CONN_CLOUD;
# they are NOT an integration (integration is None).

def test_builtin_cloud_openweather():
    """Built-in OpenWeatherMap → cloud connection, standalone (no integration)."""
    assert classify_device({"type": "OpenWeatherMap"}, None, community_drivers=set()) == (CONN_CLOUD, None)


def test_builtin_cloud_guard_community_namesake():
    """A *community* driver named OpenWeatherMap must NOT hit the built-in table; with no other
    signal it falls to (other, None) (proving the driverIsBuiltin guard)."""
    assert classify_device({"type": "OpenWeatherMap"}, None, community_drivers={"OpenWeatherMap"}) == (CONN_OTHER, None)


def test_builtin_cloud_ecobee():
    """Built-in 'Ecobee Thermostat' → cloud, standalone."""
    assert classify_device({"type": "Ecobee Thermostat"}, None, community_drivers=set()) == (CONN_CLOUD, None)


def test_builtin_cloud_pushover():
    """Built-in 'Pushover driver' → cloud, standalone."""
    assert classify_device({"type": "Pushover driver"}, None, community_drivers=set()) == (CONN_CLOUD, None)


def test_builtin_cloud_mobile_app():
    """Built-in 'Mobile App Device' → cloud, standalone."""
    assert classify_device({"type": "Mobile App Device"}, None, community_drivers=set()) == (CONN_CLOUD, None)


def test_community_pushover_not_matched():
    """The community driver named just 'Pushover' is not a table key, so it does not become cloud
    via the table — it falls to (other, None)."""
    assert classify_device({"type": "Pushover"}, None, community_drivers={"Pushover"}) == (CONN_OTHER, None)


# --- standalone community devices via the override file (branch 2b) -----------
# Modeled on the real /device/fullJson for the community "Awair Element" LAN driver: a usr
# driver, no parent app, isNetwork=false. Without an override it stays "Other"; the user
# classifies it by adding its driver type name to the File Manager override file.

def test_awair_no_override_falls_to_other():
    """Community Awair Element, no override → (other, None) — standalone, no integration."""
    dev = {"type": "Awair Element", "isNetwork": False}
    assert classify_device(dev, None, community_drivers={"Awair Element"}) == (CONN_OTHER, None)


def test_awair_type_override_lan_direct():
    """User adds {"awair": {"conn": "lan_direct"}} (no name) → connection-type-only override:
    lan_direct, and integration is None (standalone — not labeled as an 'Awair' integration)."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {"awair": {"conn": "lan_direct"}})
    dev = {"type": "Awair Element", "isNetwork": False}
    conn, name = classify_device(dev, None, community_drivers={"Awair Element"}, overrides=merged)
    assert conn == CONN_LAN_DIRECT
    assert name is None


def test_type_override_with_name():
    """Override may also set the display name."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {"awair": {"conn": "lan_direct", "name": "Awair"}})
    dev = {"type": "Awair Element"}
    conn, name = classify_device(dev, None, community_drivers={"Awair Element"}, overrides=merged)
    assert conn == CONN_LAN_DIRECT
    assert name == "Awair"


def test_type_override_wins_over_isnetwork():
    """A driver-name override beats the isNetwork=true → lan_direct derivation (branch 2b runs
    before branch 3), letting a user force e.g. cloud for a standalone device."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {"weatherthing": {"conn": "cloud"}})
    dev = {"type": "WeatherThing", "isNetwork": True}
    conn, name = classify_device(dev, None, community_drivers={"WeatherThing"}, overrides=merged)
    assert conn == CONN_CLOUD
    assert name is None   # connection-type-only override → standalone, no integration


def test_conn_only_override_is_standalone():
    """Connection-type-only override (no name) classifies a standalone device: the connection type is
    set, but integration is None so analyzeDevices omits it from the integration breakdown."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {"pushover": {"conn": "cloud"}})
    conn, name = classify_device({"type": "Pushover"}, None, community_drivers={"Pushover"}, overrides=merged)
    assert conn == CONN_CLOUD
    assert name is None


# ── Integration-overrides merge tests (user File Manager config) ─────────────
# Proves a user can still add their own connection-type exception via the config
# file, and that the merge precedence / validation rules hold.


def test_merge_adds_new_integration():
    """User adds a new connection-type exception not in built-ins; lookup finds it."""
    user = {"vera": {"conn": "lan_bridge", "name": "Vera"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("Vera Bridge Integration", merged)
    assert result is not None, "expected a match for 'vera'"
    assert result[0] == CONN_LAN_BRIDGE
    assert result[1] == "Vera"


def test_merge_user_overrides_builtin():
    """User forces lutron to cloud; user value wins over built-in lan_bridge."""
    user = {"lutron": {"conn": "cloud", "name": "Lutron Cloud"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("Lutron Connect", merged)
    assert result is not None, "expected a match for 'lutron'"
    assert result[0] == "cloud", f"expected cloud, got {result[0]}"
    assert result[1] == "Lutron Cloud"


def test_merge_invalid_conn_rejected():
    """Invalid conn value is rejected; conn falls back to None (name-only) or entry omitted."""
    user = {"badentry": {"conn": "banana", "name": "Bad"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    result = lookup_integration_with_map("badentry device", merged)
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
    """Empty user dict → merged map equals built-in defaults (6 conn-only exceptions)."""
    merged = merge_overrides(INTEGRATION_OVERRIDES, {})
    assert len(merged) == len(INTEGRATION_OVERRIDES) == 6
    for (kw, conn, name), (bkw, bconn, bname) in zip(merged, INTEGRATION_OVERRIDES):
        assert kw == bkw
        assert conn == bconn
        assert name == bname


def test_merge_readme_key_skipped():
    """_README key in user file is ignored."""
    user = {
        "_README": "documentation string",
        "vera": {"conn": "lan_bridge", "name": "Vera"},
    }
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    keywords = [kw for kw, _, _ in merged]
    assert "_readme" not in keywords, "_README should be skipped"
    assert "vera" in keywords


def test_merge_underscore_key_skipped():
    """Any key starting with '_' (commented-out example) is ignored, not merged."""
    user = {
        "_example my lan bridge": {"conn": "lan_bridge", "name": "My Bridge"},
        "vera": {"conn": "lan_bridge", "name": "Vera"},
    }
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    keywords = [kw for kw, _, _ in merged]
    assert "_example my lan bridge" not in keywords, "underscore-prefixed key should be skipped"
    assert "vera" in keywords
    # The disabled example must not classify anything.
    assert lookup_integration_with_map("my lan bridge device", merged) is None


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


def test_merge_builtins_preserved():
    """Merging a disjoint user config preserves all 5 built-in exceptions."""
    user = {"vera": {"conn": "lan_bridge", "name": "Vera"}}
    merged = merge_overrides(INTEGRATION_OVERRIDES, user)
    keywords = [kw for kw, _, _ in merged]
    for builtin_kw, _, _ in INTEGRATION_OVERRIDES:
        assert builtin_kw in keywords, f"builtin key '{builtin_kw}' missing from merged map"


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
