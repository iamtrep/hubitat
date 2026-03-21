# ThirdReality Presence Sensor R3 — Hubitat Driver Plan

## Context

The ThirdReality Presence Sensor R3 (`3RPL01084Z`) is a new Zigbee 3.0 mmWave presence sensor with integrated RGB night light, illuminance sensor, and TVOC air quality sensor. No Hubitat driver exists yet. There is strong community interest (Hubitat community thread exists). Both Zigbee2MQTT and ZHA have full support, providing excellent reference implementations.

---

## Device Technical Reference

### Identity
| Field | Value |
|-------|-------|
| Model | `3RPL01084Z` |
| Manufacturer | `Third Reality, Inc` |
| Manufacturer Code | `0x1407` |
| Protocol | Zigbee 3.0 (profile `0x0104`) |
| Device Type | `0x0102` (Color Dimmable Light) |
| Power | USB-C (5V/1A), mains-powered |
| Zigbee Role | Router (repeater) |
| Radar | 60 GHz mmWave, up to 6m range |
| EU variant model | `P1MSR3A1-EU` |

### Endpoint 1 — Cluster Map

**Input Clusters:**

| Cluster | Name | Purpose |
|---------|------|---------|
| `0x0000` | Basic | Device info |
| `0x0003` | Identify | Identify |
| `0x0004` | Groups | Group membership |
| `0x0005` | Scenes | Scene support |
| `0x0006` | OnOff | RGB light on/off |
| `0x0008` | Level Control | RGB light brightness |
| `0x0300` | Color Control | RGB light color (XY mode, enhanced hue) |
| `0x0400` | Illuminance Measurement | Ambient light (lux) |
| `0x0406` | Occupancy Sensing | Presence via 60GHz radar |
| `0x1000` | Lightlink | Touchlink commissioning |
| `0x042E` | Custom: 3r60gRadarSpecialCluster | TVOC + radar config (mfr code `0x1407`) |

**Output Clusters:**

| Cluster | Name |
|---------|------|
| `0x0019` | OTA Upgrade |

### Custom Cluster `0x042E` — Manufacturer-Specific Attributes

| Attr ID | Name | Data Type | Access | Range | Description |
|---------|------|-----------|--------|-------|-------------|
| `0x0000` | TVOC | UINT32 (ZHA uses Single/float) | Read | 0–0xFFFFFFFF | Volatile organic compounds in ppb |
| `0xF001` | TVOC Calibration Reset | UINT8 | Read/Write | 1 (reset) | Write `1` to reset TVOC calibration |
| `0xF002` | Presence Sensitivity | UINT8 | Read/Write | 1–6 | Radar detection sensitivity |
| `0xF003` | Air Quality Threshold | UINT16 | Read/Write | 3000–50000 | Air quality threshold in ppb |

### Standard Cluster Details

**Occupancy Sensing (`0x0406`):**
- Attribute `0x0000` (Occupancy): BITMAP8 — bit 0 = occupied
- Standard Zigbee reporting

**Illuminance Measurement (`0x0400`):**
- Attribute `0x0000` (MeasuredValue): UINT16 — raw value, convert to lux via `10^((raw - 1) / 10000)`

**OnOff (`0x0006`):**
- Attribute `0x0000`: BOOLEAN — light on/off
- Power-on behavior supported (off/on/toggle/previous)

**Level Control (`0x0008`):**
- Attribute `0x0000` (CurrentLevel): UINT8 — 0–254

**Color Control (`0x0300`):**
- XY color mode with enhanced hue support
- Attributes: `0x0003` (CurrentX), `0x0004` (CurrentY), `0x0000` (CurrentHue), `0x0001` (CurrentSaturation)

### Capabilities NOT Exposed
- No distance/range attribute — sensitivity is the only detection tunable
- No detection zone configuration over Zigbee
- No PIR — purely mmWave

---

## Reference Implementations

### Zigbee2MQTT (primary reference)
- **Repo:** `zigbee-herdsman-converters`
- **File:** `src/devices/third_reality.ts`
- **PR:** https://github.com/Koenkk/zigbee-herdsman-converters/pull/11191
- **Device page:** https://www.zigbee2mqtt.io/devices/3RPL01084Z.html
- Uses: `m.occupancy()`, `m.illuminance()`, `m.light({color: {modes: ["xy"], enhancedHue: true}})`, custom cluster `0x042E`

### ZHA (secondary reference)
- **Repo:** `zha-device-handlers` (zigpy)
- **File:** `zhaquirks/thirdreality/60g_radar.py`
- Uses QuirkBuilder v2: `QuirkBuilder("Third Reality, Inc", "3RPL01084Z")`
- Replaces cluster `0x042E` with `ThirdRealityRadarCluster`
- VOC attribute `0x0000`, type `t.Single` (float)

### SmartThings
- Uses generic ThirdReality "Zigbee Switch" driver from ThirdReality's channel
- No dedicated R3 fingerprint in public repos
- Limited reference value

### Hubitat Community
- **Thread:** https://community.hubitat.com/t/thirdreality-smart-presence-sensor-r3/162357
- No driver exists yet
- Interest from users; no existing work from known driver authors

### Product Page
- https://thirdreality.com/product/smart-presence-sensor-r3/

---

## Patterns from `/Users/trep/Documents/GitHub/iamtrep/hubitat`

The target repo uses these conventions:

- **Namespace:** `iamtrep`, author: `pj`
- **Naming:** `Manufacturer_Model.groovy` or descriptive name
- **Logging:** `logTrace()`, `logDebug()`, `logInfo()`, `logWarn()`, `logError()` helpers; `debugEnable`/`traceEnable`/`infoEnable` preferences
- **Parse pattern:** `zigbee.parseDescriptionAsMap(description)` → switch on cluster → `parseAttributeReport()` helper
- **Mfr-specific attributes:** `zigbee.readAttribute(cluster, attr, [mfgCode: "0xNNNN"])` and `zigbee.writeAttribute(cluster, attr, DataType, value, [mfgCode: "0xNNNN"])`
- **Commands sent via:** `sendZigbeeCommands(List cmds)` using `HubMultiAction`
- **Preferences:** `input name:, type:, title:, defaultValue:, range:` pattern
- **Events:** `sendEvent(name:, value:, unit:, descriptionText:)`
- **Lifecycle:** `installed()`, `updated()`, `initialize()`, `configure()`, `refresh()`
- **Key reference drivers:**
  - `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/sinope/Sinope_TH1300ZB.groovy` — complex Zigbee with mfr-specific cluster
  - `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/sinope/Sinope_DM2500ZB.groovy` — OnOff + Level + mfr-specific LED control
  - `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/XfinityContactSensor.groovy` — IAS Zone sensor with battery/temp
  - `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/AwairElement.groovy` — VOC/AQ attributes (HTTP, not Zigbee, but good for attribute naming)

---

## Implementation Plan

### File Location
`/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/ThirdReality_3RPL01084Z.groovy`

### Decisions
- **No device in hand** — building from Z2M/ZHA documentation; will need real device testing later
- **Full scope** — sensors + RGB light control in one driver from the start
- **File location** — `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/ThirdReality_3RPL01084Z.groovy`

### Single Driver, All Features

All capabilities in one file — single endpoint device, no parent/child needed.

**Fingerprint:**
```groovy
fingerprint profileId: "0104", endpointId: "01",
    inClusters: "0000,0003,0004,0005,0006,0008,0300,0400,0406,042E,1000",
    outClusters: "0019",
    manufacturer: "Third Reality, Inc", model: "3RPL01084Z",
    deviceJoinName: "ThirdReality Presence Sensor R3"
```

**Capabilities:**
- `PresenceSensor` — occupancy cluster `0x0406` → `present`/`not present`
- `IlluminanceMeasurement` — cluster `0x0400`, report in lux
- `Switch` — light on/off via cluster `0x0006`
- `SwitchLevel` — light brightness via cluster `0x0008`
- `ColorControl` — light color via cluster `0x0300` (XY mode with enhanced hue)
- `Configuration`
- `Refresh`

**Custom attributes:**
- `tvoc` (number) — TVOC in ppb from cluster `0x042E` attr `0x0000`
- `airQuality` (enum: good/moderate/unhealthy) — derived from TVOC vs threshold

**Custom commands:**
- `resetTVOCCalibration()` — writes `1` to cluster `0x042E` attr `0xF001`

**Preferences:**
- `presenceSensitivity` — enum 1–6 (writes to `0x042E` attr `0xF002`)
- `airQualityThreshold` — number 3000–50000 (writes to `0x042E` attr `0xF003`)
- `debugEnable`, `traceEnable`, `infoEnable` — standard logging toggles
- `powerOnBehavior` — enum: off/on/toggle/previous (writes to `0x0006` power-on attribute)

### Parse Routing

```
parse(description)
  → zigbee.parseDescriptionAsMap()
  → switch on clusterInt:
      0x0406 → parseOccupancy()     — attr 0x0000, bit 0 → presence event
      0x0400 → parseIlluminance()   — attr 0x0000, raw → lux conversion
      0x042E → parseRadarCluster()  — attr 0x0000 → TVOC; 0xF002 → sensitivity readback
      0x0006 → parseOnOff()         — attr 0x0000 → switch event
      0x0008 → parseLevel()         — attr 0x0000 → level event
      0x0300 → parseColorControl()  — attrs 0x0003/0x0004 → color events (XY→HSB)
```

All mfr-specific reads/writes on `0x042E` use `[mfgCode: "0x1407"]`.

### Configure Method

```groovy
configure():
  // Bind standard clusters
  bind 0x0406 (Occupancy)
  bind 0x0400 (Illuminance)
  bind 0x0006 (OnOff)
  bind 0x0008 (Level)
  bind 0x0300 (Color)
  bind 0x042E (Custom radar)

  // Configure reporting
  configureReporting 0x0406, 0x0000, BITMAP8, min=0, max=3600, change=1
  configureReporting 0x0400, 0x0000, UINT16, min=10, max=3600, change=100
  configureReporting 0x0006, 0x0000, BOOLEAN, min=0, max=3600
  configureReporting 0x0008, 0x0000, UINT8, min=1, max=3600, change=1
  // 0x042E reporting — attempt configureReporting; may need polling fallback

  // Write preferences
  writeAttribute 0x042E, 0xF002, UINT8, sensitivity, [mfgCode: 0x1407]
  writeAttribute 0x042E, 0xF003, UINT16, threshold, [mfgCode: 0x1407]

  // Initial read
  refresh()
```

### Light Control Commands

- `on()` → `zigbee.on()`
- `off()` → `zigbee.off()`
- `setLevel(level, duration)` → `zigbee.setLevel(level, duration)`
- `setColor(colorMap)` → convert HSB to XY, write to cluster `0x0300` attrs `0x0003`/`0x0004`
  - Hubitat's `zigbee.setColor()` may handle this; otherwise raw `moveToColor` command
- `setHue(hue)` → `setColor([hue: hue, saturation: device.currentSaturation ?: 100])`
- `setSaturation(sat)` → `setColor([hue: device.currentHue ?: 0, saturation: sat])`

### Code Structure (following repo conventions)

```
metadata { definition / preferences }

// Lifecycle
installed(), updated(), initialize(), configure(), refresh()

// Parse
parse(description) → route to cluster handlers
parseOccupancy(descMap)
parseIlluminance(descMap)
parseRadarCluster(descMap)
parseOnOff(descMap)
parseLevel(descMap)
parseColorControl(descMap)

// Commands
on(), off(), setLevel(), setColor(), setHue(), setSaturation()
resetTVOCCalibration()

// Helpers
sendZigbeeCommands(cmds)
logTrace(), logDebug(), logInfo(), logWarn(), logError()
```

### Key Reference Files to Study During Implementation
- `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/sinope/Sinope_DM2500ZB.groovy` — OnOff + Level + mfr-specific cluster pattern
- `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/sinope/Sinope_TH1300ZB.groovy` — complex parse routing, mfr-specific reads/writes
- `/Users/trep/Documents/GitHub/iamtrep/hubitat/drivers/XfinityContactSensor.groovy` — sensor with reporting config
- Z2M `src/devices/third_reality.ts` — cluster `0x042E` attribute definitions
- ZHA `zhaquirks/thirdreality/60g_radar.py` — TVOC type handling

---

## Blind Spots & Missing Information

### Things I Don't Know Yet

1. **Exact fingerprint cluster list** — I've reconstructed it from Z2M and ZHA, but the actual device's `inClusters`/`outClusters` strings from Hubitat's Zigbee info page may differ. We'll need to pair the device and check its Zigbee details in Hubitat.

2. **TVOC data type ambiguity** — Z2M says UINT32, ZHA quirk uses `t.Single` (float/IEEE754). Need to see actual attribute reports to know which parsing approach is correct for this device.

3. **TVOC reporting configuration** — Does cluster `0x042E` support standard `configureReporting`? Manufacturer-specific clusters sometimes don't. May need to poll instead.

4. **Occupancy reporting behavior** — Does it auto-report both occupied→unoccupied and unoccupied→occupied transitions? What's the default reporting interval? Is there an occupancy timeout, or does the radar handle that internally?

5. **Color Control specifics** — The Z2M converter uses XY mode with `enhancedHue: true`. Need to confirm Hubitat's built-in `zigbee.setColor()` helpers work, or if we need raw XY writes.

6. **No RGB light driver in the repo** — The `iamtrep/hubitat` repo has no existing color light driver to reference. The Sinope dimmer handles Level but not Color. We may need to reference Hubitat's built-in `Generic Zigbee RGBW Light` driver patterns or community examples.

7. **Device data response** — Need to confirm how the device responds to `zigbee.readAttribute()` calls on the custom cluster — does it require the `mfgCode` parameter or not?

### Risks

- **No device for testing** — Building entirely from Z2M/ZHA references. The fingerprint, attribute data types, and reporting behavior may need adjustment once paired with a real Hubitat hub.
- **Untested custom cluster** — The `0x042E` cluster behavior is derived from Z2M/ZHA code, not from Hubitat testing.
- **Color conversion** — HSB↔XY conversion can be tricky. Hubitat's built-in `zigbee.setColor()` may or may not handle this correctly for this device. May need raw ZCL commands.
- **TVOC polling vs reporting** — If `configureReporting` doesn't work on the mfr-specific cluster, we'll need a scheduled polling fallback.

---

## Verification Plan

1. **Pair the device** to Hubitat and capture the Zigbee fingerprint from the device details page
2. **Test with Generic Zigbee Motion Sensor** first to confirm basic occupancy works on cluster `0x0406`
3. **Install the MVP driver** (Phase 1) and verify presence + illuminance events in device events log
4. **Enable debug logging** and send `refresh()` to read custom cluster attributes — verify TVOC value parsing
5. **Test sensitivity writes** — change preference, verify via `readAttribute` that the device accepted the value
6. **Test RGB light** — on/off, level, color commands — verify via device events and visual confirmation
7. **Long-running test** — leave paired for 24h, verify occupancy transitions, TVOC reporting intervals, and no missed events
