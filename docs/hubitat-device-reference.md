<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat device reference

Per-device / per-vendor protocol facts learned from working with these devices on Hubitat —
cluster maps, attribute encodings, and quirks. Reverse-engineered, not vendor-official; may
change with device firmware. Corroborating sources (Zigbee2MQTT, ZHA, deCONZ) are noted where
they exist.

## Xiaomi / Aqara (lumi FF01)

Xiaomi/Aqara `lumi.*` endpoints report a manufacturer-specific TLV blob in the `0xFF01`
attribute of cluster `0x0000` (Basic). The frames are mfr-specific Report Attributes: frame
control `0x1C`, manufacturer `0x115F` (Lumi), command `0x0A`.

**TLV layout.** The first byte is the payload length; the remainder is a sequence of
`{tag, dataType, value}` records with the value stored little-endian.

**The two-path length-prefix gotcha.** The FF01 check-in reaches a driver's `parse()` by two
paths that differ in how the ZCL char-string length prefix is handled — calibrate on one and
you silently corrupt the other:

- **Standalone check-in** — the frame carries `attrId 0xFF01` only. It arrives as the raw
  `description` string (`attrId: FF01`), and its `value:` field **includes** the length-prefix
  byte (e.g. `1D0121…`). Match it with the `description.contains("attrId: FF01")` string branch
  and skip the prefix.
- **Button / reset press** — the frame carries `attrId 0x0005` (ModelIdentifier) **plus**
  `0xFF01`. Here `0x0005` is primary and the FF01 rides in `descMap.additionalAttrs` (from
  `zigbee.parseDescriptionAsMap`), where the platform has **already stripped** the prefix
  (value `0121…`). Re-add a placeholder byte so this path aligns with the standalone one.

Align both paths on **`strPosition = 2`** (skip a two-hex-char prefix). The trap: a
descMap-derived sample (prefix already stripped, `0121…`) tempts you to set `strPosition = 0`,
which then misparses every standalone check-in (which arrives `1D0121…`, with the prefix).
Keep the parser prefix-skipping and normalize the stripped path instead.

**Frame shapes.**

| Shape | Attributes present | Meaning |
|---|---|---|
| Standalone check-in | `0xFF01` only | Periodic status/heartbeat |
| Button / reset press | `0x0005` + `0xFF01` | Press event; discriminator = `additionalAttrs` contain `FF01` |
| Join announce | `0x0005` + `0x0001` (ApplicationVersion) | Rejoin; update model/appVersion, emit **no** button event |

**Per-model tag table — tag IDs are reused with different meanings per model; do NOT copy a
decoder across models.** Authoritative cross-check: Zigbee2MQTT `zigbee-herdsman-converters`
`src/lib/lumi.ts`, function `numericAttributes2Payload`.

| Tag | Encoding / meaning | Notes |
|---|---|---|
| `0x01` | battery voltage (mV, ÷1000) | |
| `0x03` | device / chip temperature (INT8 °C) | internal, not ambient |
| `0x05` | **power outage count** (cumulative) | community drivers mislabel this "RSSI dB" — it is not radio quality |
| `0x06` | Xiaomi proprietary counter (UINT40) | community drivers mislabel this "LQI"; Z2M calls it `trigger_count` on some models — model-specific, do not hard-label |
| `0x0A` | context-dependent | parent DNI (network id) on some models; `switch_type` on others |
| `0x64` | model-specific | contact (0=closed/1=open) on `lumi.sensor_magnet.aq2`; **temperature** on `lumi.weather` |
| `0x65` | model-specific | humidity on `lumi.weather`; illuminance/battery on others |
| `0x66` | model-specific | pressure on `lumi.weather`; battery/etc. on others |

The reuse of `0x64/0x65/0x66` is the crux: same tag ID, different sensor per model. Check the
per-model mapping in `lumi.ts` before reusing a decoder on a sibling device.

**Community mislabels to unlearn:** `0x05` is a power-outage counter, **not** RSSI; `0x06` is a
proprietary counter, **not** LQI. The original community port introduced both and every
downstream driver copied them.

**No Zigbee keep-alive → re-parenting behavior.** Aqara `lumi` devices implement no Zigbee
keep-alive, so a parent (including the coordinator over a marginal edge link) ages them out of
its child table, causing parent loss and a loss-driven NWK rejoin onto whatever router is
strongest at that moment. A single short press of the reset button is only a network-state
check — it wakes the device and polls its **existing** parent; it does **not** rescan,
re-select, or rejoin. Deliberate re-parenting needs a long (~3 s) press (re-pair). These
devices normally "stick" to their parent and often go **offline** on loss rather than rejoin.
Retention is purely network-layer child-aging; the best mitigation is a strong, tolerant nearby
router that the device can pin to direct-to-hub — there is no short-press or driver lever for
parenting. (Multi-source: Aqara manual/FAQ, deCONZ REST docs, zigbee-herdsman-converters,
Silabs.)

## Sinopé

**Device-temperature cluster differs by product line — mutually exclusive, each line lacks the
other's cluster.** Verified against claudegel/sinope-zha `switch.py` input-cluster lists and Z2M
`sinope.ts`:

- **Water valves (VA4200ZB / VA4201ZB / VA4220ZB / VA4221ZB)** — temperature on Temperature
  Measurement cluster **`0x0402`** (=1026), signed int16 in 1/100 °C. Cluster `0x0002` is
  absent. This is a device-temperature probe, not water temperature. (Z2M's VA4220ZB doesn't
  bind/report it; claudegel exposes `0x0402` as a plain temperature sensor.)
- **Switches / dimmers (SW2500ZB / DM2500ZB)** — temperature on Device Temperature
  Configuration cluster **`0x0002`** (whole-degree), no `0x0402`. This is an internal
  self-heating chip temperature, roughly a dozen °C above ambient — best surfaced as a custom
  `deviceTemperature` attribute rather than the standard `temperature`.

So "read device temp from the same cluster across Sinopé drivers" is wrong: valve = `0x0402`,
switch/dimmer = `0x0002`.

**Thermostat model lines and the G2 variant.** Two distinct hardware platforms; a second-gen
(G2) variant exists on only one of them:

- **TH1123ZB / TH1124ZB** — line-voltage (baseboard/convector). **Has** G2 variants
  (`TH1123ZB-G2`, `TH1124ZB-G2`). G2 hardware uses a `Backlight` enum (`On_demand=0x00`,
  `Always_on=0x01`, `Bedroom=0x02`) instead of the original `Simplebacklight` (`On_demand=0x00`,
  `Always_on=0x01`).
- **TH1300ZB / TH1320ZB(-04)** — floor-heating thermostats with GFCI + relay. **No G2 variant.**
  Always `Simplebacklight`. Confirmed by both Z2M (`sinope.ts`) and claudegel (`thermostat.py`
  TH1300ZB quirk).
- **TH1400ZB / TH1500ZB** — low-voltage / pulse-output. **No G2** either, per current references.

Practical note: a multi-model driver lifted from the TH1123/TH1124 lineage may carry
`isG2Model()` / `constBacklightModesG2` / G2-branching backlight logic. On TH1300/TH1320/TH1400
that is dead code (the fingerprints can never produce a `-G2` model string); on TH1123/TH1124 it
is load-bearing.

## Fujitsu / Ayla (FGLair)

Fujitsu HVAC units on the Ayla cloud use two different temperature representations, and the
empirical scaling below beats the vendor library's:

- **Sensor readings** (`display_temperature`, `outdoor_temperature`) — **hundredths of °F**:
  `fahrenheit = raw / 100`, `celsius = (fahrenheit - 32) * 5 / 9`. E.g. `7000` → 70.0 °F /
  21.1 °C; `5500` → 55.0 °F / 12.8 °C.
- **Setpoint** (`adjust_temperature`) — **tenths of °C**, regardless of the unit's display
  setting: `celsius = raw / 10` (0.5 °C step). E.g. `180` → 18.0 °C.

Home Assistant's `fujitsu_fglair` integration (via `ayla-iot-unofficial`) instead uses a linear
range-map for sensors — raw `[4000, 9500]` → `[-10, +45] °C`. Applying that formula gives values
~8 °C off on the observed unit (raw `5500` → 5 °C per the lib, but the actual temperature was
~13 °C, matching the hundredths-of-°F reading). The lib's constants (`SENSED_TEMP_SUPPORTED`
varies by `ModelType` A/B/F) are evidently for a different model/firmware. When empirical field
data contradicts a reference library, the empirical reading wins for the specific unit on hand.

If a future unit reports sensor values outside the plausible hundredths-of-°F range (roughly raw
3200–11200, covering -18 °C to +49 °C), the lib's range-map or a per-unit override may need
reconsidering.

## Philips Hue

Philips Hue (Signify) motion sensors report their model in `device.data.model` (mfr `Signify
Netherlands B.V.`). The Hubitat driver name is just "Hue Motion Sensor" for all of them and does
**not** distinguish the model, so a driver-name search can't tell them apart — read
`device.data.model`.

- **SML003 = indoor** motion sensor.
- **SML004 = outdoor** motion sensor.

These are **different products, not older/newer revisions of the same model**. (SML001/SML002
are the earlier indoor/outdoor generation.)

## ThirdReality 3RPL01084Z

ThirdReality Presence Sensor R3 / Multi-Function Night Light. Confirmed protocol map:

- **Cluster `0x042E` (=1070, ZCL tvocMeasurement) is a genuine TVOC cluster.** Z2M /
  zigbee-herdsman-converters model it as custom cluster `3r60gRadarSpecialCluster`,
  **manufacturerCode `0x1407`**, which reuses the tvocMeasurement cluster ID and bolts radar
  config on as manufacturer-specific attributes. Attribute map:
  - `0x0000` = totalVolatileOrganicCompounds (TVOC, ppb)
  - `F001` = tvocSensorCalibration (write-only)
  - `F002` = presenceDetectDistanceLevel (1–6)
  - `F003` = tvocAlertThreshold (ppb, 3000–50000)
  - `F004` = motionDetectSensitivity (0–20)
  - `F005` = presenceDetectSensitivity (0–20)
  - `F006` = presenceHoldTimeLevel (1–4)
  - `F007` = tvocAlertEnable (0–1)
- **Attribute `0x0000` is transmitted as an IEEE-754 float (ZCL data type `0x39`), NOT the
  UINT32 that zigbee-herdsman-converters declares.** A driver following the z-h-c type literally
  will misparse; decode as float. Observed values in the hundreds-to-low-thousands ppb range,
  from both standard (`0x18`) and mfg-`0x1407` reports.
- The device auto-reports F001–F007 roughly every 10 s.
- **Legacy Hue/Saturation color works.** `Move to Hue` (single-channel command `0x00`) advances
  CurrentHue (`0x0000`); `Move to Saturation` (command `0x03`) sets CurrentSaturation
  (`0x0001`); CT (`0x0007`) reads back fine. The device is also enhanced-capable
  (EnhancedCurrentHue `0x4000`, enhancedMoveToHue command `0x40`, Z2M models it
  `{color:{modes:["xy"],enhancedHue:true}}`), but legacy 8-bit hue is adequate for Hubitat's
  0–100 hue range.

## ThirdReality vibration sensor

ThirdReality vibration sensors (mfr `1233`) emit a flood of `FFF1:0000/0001/0002/0003` Report
Attributes frames whenever the device is shaking (e.g. a dryer or washer cycle) — on the order
of ~1000+ frames per cycle.

**The report cadence is device-firmware-decided and NOT tunable from the hub.** Corroborated
across alt-stacks: Z2M's `third_reality.ts` converter does no `bind()` or `configureReporting()`
on FFF1 (its `configure()` is a battery read only); ZHA has only an open device-support issue
with no reporting discussion; a deCONZ issue closed stale; ThirdReality firmware gates streaming
on trigger state with no user interval. The most likely outcome of a bind + configureReporting
on FFF1 from Hubitat is a silent ignore.

The only mitigations are hub-side: (a) accept the burst, (b) ensure the sensor's parent router
isn't also a heavy talker so contention stays local, or (c) replace the device. Do **not**
suggest "tune the reporting interval" or "configure Reportable Change" — there's no lever.

**Speculative / untested:** the cluster declares `coolDownTime` (`0xFFF1/0x0004`, UINT16) as
writable in the herdsman schema, but it is unconfirmed whether this is the vendor's throttle
knob (units unknown, adversarial verification declined to confirm). Treat any `coolDownTime`-style
lever as untested speculation, not a fact.
