<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat device reference

Per-device / per-vendor protocol facts learned from working with these devices on Hubitat —
cluster maps, attribute encodings, and quirks. Reverse-engineered, not vendor-official; may
change with device firmware. Corroborating sources (Zigbee2MQTT, ZHA, deCONZ) are noted where
they exist. How the hub itself handles these protocols is in the [`zigbee` helper
reference](hubitat-zigbee-helper.md) and the [platform notes](hubitat-platform-notes.md).
Every fact carries an evidence mark ([EVIDENCE.md](EVIDENCE.md)).

## Xiaomi / Aqara (lumi FF01)

Xiaomi/Aqara `lumi.*` endpoints report a manufacturer-specific TLV blob in the `0xFF01`
attribute of cluster `0x0000` (Basic) *(community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*. The frames are mfr-specific Report Attributes: frame
control `0x1C`, manufacturer `0x115F` (Lumi), command `0x0A` *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026; community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*.

**TLV layout.** The first byte is the payload length; the remainder is a sequence of
`{tag, dataType, value}` records with the value stored little-endian *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026; community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*.

**The two-path length-prefix gotcha.** The FF01 check-in reaches a driver's `parse()` by two
paths that differ in how the ZCL char-string length prefix is handled — calibrate on one and
you silently corrupt the other *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026)*:

- **Standalone check-in** — the frame carries `attrId 0xFF01` only. It arrives as the raw
  `description` string (`attrId: FF01`), and its `value:` field **includes** the length-prefix
  byte (e.g. `1D0121…`). Match it with the `description.contains("attrId: FF01")` string branch
  and skip the prefix *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026)*.
- **Button / reset press** — the frame carries `attrId 0x0005` (ModelIdentifier) **plus**
  `0xFF01`. Here `0x0005` is primary and the FF01 rides in `descMap.additionalAttrs` (from
  `zigbee.parseDescriptionAsMap`), where the platform has **already stripped** the prefix
  ([helper reference](hubitat-zigbee-helper.md))
  (value `0121…`). Re-add a placeholder byte so this path aligns with the standalone one *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026)*.

Align both paths on **`strPosition = 2`** (skip a two-hex-char prefix). The trap: a
descMap-derived sample (prefix already stripped, `0121…`) tempts you to set `strPosition = 0`,
which then misparses every standalone check-in (which arrives `1D0121…`, with the prefix).
Keep the parser prefix-skipping and normalize the stripped path instead *(inferred: from the two paths above)*.

**Frame shapes**:

| Shape | Attributes present | Meaning |
|---|---|---|
| Standalone check-in | `0xFF01` only | Periodic status/heartbeat |
| Button / reset press | `0x0005` + `0xFF01` | Press event; discriminator = `additionalAttrs` contain `FF01` |
| Join announce | `0x0005` + `0x0001` (ApplicationVersion) | Rejoin; update model/appVersion, emit **no** button event |

The shapes are those seen from MCCGQ11LM: a short press sends the press shape, a long press the join shape *(probed firmware not recorded, C-7, MCCGQ11LM captures, July 2026)*.

**Per-model tag table — tag IDs are reused with different meanings per model; do NOT copy a
decoder across models.** Authoritative cross-check: Zigbee2MQTT `zigbee-herdsman-converters`
`src/lib/lumi.ts`, function `numericAttributes2Payload`:

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

The table follows `lumi.ts` *(community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*.

The reuse of `0x64/0x65/0x66` is the crux: same tag ID, different sensor per model. Check the
per-model mapping in `lumi.ts` before reusing a decoder on a sibling device *(community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*.

**Community mislabels to unlearn:** `0x05` is a power-outage counter, **not** RSSI; `0x06` is a
proprietary counter, **not** LQI *(community: [zigbee-herdsman-converters `lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts))*. The original community port introduced both and every
downstream driver copied them *(community: [veeceeoh/xiaomi-hubitat](https://github.com/veeceeoh/xiaomi-hubitat))*.

**No Zigbee keep-alive, so re-parenting is out of the driver's hands.** Aqara `lumi` devices implement no Zigbee
keep-alive, so a strict parent ages them out of its child table, and they then rejoin onto whatever router is
strongest at that moment, or go **offline**. Retention is network-layer child-aging and depends on the parent's
tolerance; there is no driver lever for parenting *(inferred: research summary of the Aqara manual and FAQ, deCONZ REST docs, zigbee-herdsman-converters and Silicon Labs material, no capture)*.
A short press of the reset button was seen to move one MCCGQ11LM to another parent without a re-pair, by a mechanism not identified *(probed firmware not recorded, C-7, `/zigbeeLogsocket` capture, July 2026)*.

## Sinopé

**A DM2500ZB dimmer turned on reports level 0 first, then the real level.** After `on` from off
(at a stored 30 %), the frames were an on/off report (on), a level report of 0x00, and about 1.5 s later a level
report of the real value (0x4C). `off` sent one on/off report and no level change. So the first level report
after `on` is a transient 0, and logic that treats the first report after a command as the command's result
picks the 0 *(probed 2.5.2.134, one DM2500ZB, `/zigbeeLogsocket`)*.

**Device-temperature cluster differs by product line — mutually exclusive, each line lacks the
other's cluster.** Verified against claudegel/sinope-zha `switch.py` input-cluster lists and Z2M
`sinope.ts` *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*:

- **Water valves (VA4200ZB / VA4201ZB / VA4220ZB / VA4221ZB)** — temperature on Temperature
  Measurement cluster **`0x0402`** (=1026), signed int16 in 1/100 °C. Cluster `0x0002` is
  absent. This is a device-temperature probe, not water temperature. (Z2M's VA4220ZB doesn't
  bind/report it; claudegel exposes `0x0402` as a plain temperature sensor.) *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*
- **Switches / dimmers (SW2500ZB / DM2500ZB)** — temperature on Device Temperature
  Configuration cluster **`0x0002`** (whole-degree), no `0x0402` *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*. This is an internal
  self-heating chip temperature, roughly a dozen °C above ambient *(unmarked)* — best surfaced as a custom
  `deviceTemperature` attribute rather than the standard `temperature` *(inferred: the reading is internal, not ambient)*.

So "read device temp from the same cluster across Sinopé drivers" is wrong: valve = `0x0402`,
switch/dimmer = `0x0002` *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*.

**Valve flow rate (FS4220/FS4221 sensor) is the average since the previous rate report, and drops to 0 within seconds of a stop.** With the custom `Sinope_VA422xZB` driver's reporting config (`0x0702`/`0x0400`, mL/h, min 5 s, max 300 s), 30 days of logged events (to 2026-10-05; hub 2.5.2.129, valve firmware `119C-0026-00010203`) show: reports every 5–7 s while water flows; a 0 report 5–7 s after the last nonzero one at all 657 stops, valve open, none later; a 0 every 300 s while idle. During flow that window is the 5–6 s report spacing. The one exception is the first report of a draw: it arrives a median 56 s after the last idle 0 yet reads about 73% of the next report, so it covers only the last few seconds, not the idle gap. Holding each reading until the next report integrates to within 2% of the cumulative volume counter (`0x0702`/`0x0000`, mL, uint48), which the device does report *(probed 2.5.2.129, valve firmware `119C-0026-00010203`, 30 days of logged events)*. The system driver behaved differently in January 2023 ([forum topic 96701](https://community.hubitat.com/t/96701), posts 52–66): no zero followed a stop, and the last value stayed until the valve closed, `configure()` ran, water flowed again, or more than about 12 hours passed *(probed firmware not recorded, our screenshots in [forum topic 96701](https://community.hubitat.com/t/96701), posts 52–66)*. Why the system driver gets no zero is unverified (its reporting config was not inspected). The driver's `rateFromVolume` attribute is the volume-difference-over-time cross-check from that investigation; it is development-only and resets to 0 on a zero `rate` report (0.0.10) *(inferred: from `drivers/sinope/Sinope_VA422xZB.groovy` 0.0.10)*.

**Thermostat model lines and the G2 variant.** Two distinct hardware platforms; a second-gen
(G2) variant exists on only one of them:

- **TH1123ZB / TH1124ZB** — line-voltage (baseboard/convector). **Has** G2 variants
  (`TH1123ZB-G2`, `TH1124ZB-G2`). G2 hardware uses a `Backlight` enum (`On_demand=0x00`,
  `Always_on=0x01`, `Bedroom=0x02`) instead of the original `Simplebacklight` (`On_demand=0x00`,
  `Always_on=0x01`) *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*.
- **TH1300ZB / TH1320ZB(-04)** — floor-heating thermostats with GFCI + relay. **No G2 variant.**
  Always `Simplebacklight`. Confirmed by both Z2M (`sinope.ts`) and claudegel (`thermostat.py`
  TH1300ZB quirk) *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*.
- **TH1400ZB / TH1500ZB** — low-voltage / pulse-output. **No G2** either, per current references *(community: [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha), [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts))*.

Practical note: G2-specific backlight logic is dead code on TH1300/TH1320/TH1400 (no `-G2` model string exists there) and needed on TH1123/TH1124 *(inferred: no `-G2` model exists for those lines in the references above)*.

## Stelpro Allia (HT402)

The Allia (model `HT402`, manufacturer string `Stello`) keeps its outdoor temperature, power and
energy on the standard Thermostat cluster 0x0201 at attribute ids 0x4001 (int16, hundredths of
°C), 0x4008 (uint32, W) and 0x4009 (uint32, Wh). They are not manufacturer-specific: read without
`mfgCode`, each answers in a plain Read Attributes Response (frame control 0x08, no manufacturer
bit) with status 0x00 and the types above, and a 0x4001 value the hub wrote reads back unchanged,
so the driver's reads, writes and reporting setup need no manufacturer code. Whether
`configureReporting` on 0x4008/0x4009 is accepted was not checked; the device reported 0 W at the
time, so no report was expected *(probed 2.5.2.134, one HT402, frames from `/zigbeeLogsocket` during `refresh()`)*.

## IKEA window blinds

A PRAKTLYSING cellular blind (firmware `24040013`) lists only 0x0000, 0x0007, 0x0008, 0x0010–0x0013,
0x0017 and 0xFFFD on the Window Covering cluster 0x0102 in a Discover Attributes response, with no
manufacturer-specific ones. It reads WindowCoveringType 0x00 (roller shade), ConfigStatus 0x03,
InstalledOpenLimitLift 0x0000, InstalledClosedLimitLift 0xFFFF, the tilt limits the same, and Mode
0x00. A write to either installed lift limit answers 0x88 READ_ONLY, so the travel range cannot be
narrowed over Zigbee in normal mode; Mode accepts a write (0x00 written back, status 0x00). Whether
the limits become writable with Mode's calibration bit set was not tested
*(probed 2.5.2.139, C-7, one blind, temporary driver commands)*.

With lift reporting configured at a 2 s minimum interval and a change of 1, the blind reports about
every 2 s while it moves, in steps of about 6%, so it travels about 3% a second. A Go To Lift
Percentage sent while the blind runs on an Up/Open command from a bound remote replaces that move's
target: sent on reports at lift 17 and 20 it stopped two blinds at lift 2, while sent on reports at
lift 8 and 9 one blind stopped at 2 and the other reached 0 first *(probed 2.5.2.139, C-7, two PRAKTLYSING blinds)*.

## Fujitsu / Ayla (FGLair)

Fujitsu HVAC units on the Ayla cloud use two different temperature representations, and the
empirical scaling below beats the vendor library's:

- **Sensor readings** (`display_temperature`, `outdoor_temperature`) — **hundredths of °F**:
  `fahrenheit = raw / 100`, `celsius = (fahrenheit - 32) * 5 / 9`. E.g. `7000` → 70.0 °F /
  21.1 °C; `5500` → 55.0 °F / 12.8 °C *(reported: raw cloud values against the actual temperature, one unit, 2026-05-18)*.
- **Setpoint** (`adjust_temperature`) — **tenths of °C**, regardless of the unit's display
  setting: `celsius = raw / 10` (0.5 °C step). E.g. `180` → 18.0 °C *(reported: raw cloud values against the unit's setpoint, one unit, 2026-05-18)*.

Home Assistant's `fujitsu_fglair` integration (via `ayla-iot-unofficial`) instead maps sensor raw `[4000, 9500]` linearly to `[-10, +45] °C` *(community: [ayla-iot-unofficial `fujitsu_consts.py`](https://github.com/rewardone/ayla-iot-unofficial/blob/main/src/ayla_iot_unofficial/fujitsu_consts.py))*. On the observed unit that gives values ~8 °C off (raw `5500` → 5 °C by the map, but the actual temperature was ~13 °C, matching the hundredths-of-°F reading); the library's constants are evidently for a different model or firmware *(reported: raw cloud values against the actual temperature, one unit, 2026-05-18)*.

A sensor raw value outside roughly 3200–11200 (-18 °C to +49 °C) would suggest a different unit or firmware *(inferred: plausible indoor and outdoor temperatures in hundredths of °F)*.

## Philips Hue

Philips Hue (Signify) motion sensors report their model in `device.data.model` (mfr `Signify
Netherlands B.V.`). The Hubitat driver name is just "Hue Motion Sensor" for all of them and does
**not** distinguish the model, so a driver-name search can't tell them apart — read
`device.data.model` *(probed firmware not recorded, device records read 2026-07-13)*.

- **SML003 = indoor** motion sensor *(community: [zigbee-herdsman-converters `philips.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/philips.ts))*.
- **SML004 = outdoor** motion sensor *(community: [zigbee-herdsman-converters `philips.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/philips.ts))*.

These are **different products, not older/newer revisions of the same model**. (SML001/SML002
are the earlier indoor/outdoor generation.) *(community: [zigbee-herdsman-converters `philips.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/philips.ts))*

## ThirdReality 3RPL01084Z

ThirdReality Presence Sensor R3 / Multi-Function Night Light. Confirmed protocol map:

- **Cluster `0x042E` (=1070, ZCL tvocMeasurement) is a genuine TVOC cluster.** Z2M /
  zigbee-herdsman-converters model it as custom cluster `3r60gRadarSpecialCluster`,
  **manufacturerCode `0x1407`**, which reuses the tvocMeasurement cluster ID and bolts radar
  config on as manufacturer-specific attributes *(community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts))*. Attribute map:
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
  from both standard (`0x18`) and mfg-`0x1407` reports *(probed firmware not recorded, C-7, one 3RPL01084Z captures, July 2026; community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts))*.
- The device auto-reports F001–F007 roughly every 10 s *(probed firmware not recorded, C-7, one 3RPL01084Z captures, July 2026)*.
- **Legacy Hue/Saturation color works.** `Move to Hue` (single-channel command `0x00`) advances
  CurrentHue (`0x0000`); `Move to Saturation` (command `0x03`) sets CurrentSaturation
  (`0x0001`); CT (`0x0007`) reads back fine *(probed firmware not recorded, C-7, one 3RPL01084Z captures, July 2026)*. The device is also enhanced-hue capable (EnhancedCurrentHue `0x4000`) *(community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts))*, but legacy 8-bit hue is adequate for Hubitat's
  0–100 hue range *(inferred: Hubitat's 0–100 hue scale needs less than 8 bits)*.

## ThirdReality vibration sensor

ThirdReality vibration sensors (mfr `1233`) emit a flood of `FFF1:0000/0001/0002/0003` Report
Attributes frames whenever the device is shaking (e.g. a dryer or washer cycle) — on the order
of ~1000+ frames per cycle *(reported: [forum topic 160289](https://community.hubitat.com/t/third-reality-vibration-sensor-affecting-zigbee-devices/160289/19), posts 19 and 23, Zigbee log counts; community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts))*.

**The report cadence is device-firmware-decided and NOT tunable from the hub.** Zigbee2MQTT's converter does no `bind()` or `configureReporting()` on FFF1, and the firmware gates streaming on trigger state with no user interval *(community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts); staff: [mike.maxwell](https://community.hubitat.com/t/third-reality-vibration-sensor-affecting-zigbee-devices/160289/41))*. From Hubitat the
device refuses both: an FFF1 unbind answers `NO_ENTRY`, and configureReporting answers
`UNSUPPORTED_ATTRIBUTE` with the manufacturer code and `NOT_SUPPORTED` without it *(probed firmware not recorded, our [forum post](https://community.hubitat.com/t/160289/29))*.

The only mitigations are hub-side: (a) accept the burst, (b) ensure the sensor's parent router
isn't also a heavy talker so contention stays local, or (c) replace the device. Do **not**
suggest "tune the reporting interval" or "configure Reportable Change" — there's no lever *(staff: [mike.maxwell](https://community.hubitat.com/t/third-reality-vibration-sensor-affecting-zigbee-devices/160289/41); inferred: from the section above)*.

**Untested:** the herdsman schema lists a writable `coolDownTime` (`0xFFF1/0x0004`, UINT16, up to 7200 s in Zigbee2MQTT). It is unconfirmed whether it throttles the stream; we have not written it *(community: [zigbee-herdsman-converters `third_reality.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/third_reality.ts))*.

## Android TV / Google TV (Remote protocol v2)

The protocol Google's TV remote app and Home Assistant's `androidtv_remote` integration use: TLS on
TCP 6467 for pairing and 6466 for the remote session, a self-signed client certificate, and
protobuf messages each prefixed by a varint length *(community: [androidtvremote2](https://github.com/tronikos/androidtvremote2))*.
The pairing secret is SHA-256 over the client's and then the TV's RSA modulus and exponent and the
last four hex digits of the code shown on screen, so the client needs the TV's public key before
it pairs. Leading zero bytes of each number are dropped before hashing *(spec: [AOSP google-tv-pairing-protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/refs/heads/main), `PoloChallengeResponse.java`; community: [androidtvremote2 `pairing.py`](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/pairing.py))*. The pairing side is Google's open-source "Polo" protocol; the remote-session messages on 6466 have no public source and were reverse-engineered *(community: [androidtvremote2 credits](https://github.com/tronikos/androidtvremote2#credits))*.

- One Android TV box (model `uiw4020ebx`) serves the same self-signed certificate on 6466 and
  6467: 2048-bit RSA, exponent 65537, CN `atvremote/<MAC>`. It negotiates TLS 1.3 by default and
  also accepts a client that offers only TLS 1.2. The TV's own firmware build was not read *(probed firmware not recorded, openssl s_client from the LAN)*.
- A hand-built TLS 1.2 ClientHello, sent from a Hubitat driver over a plain `rawSocket` with
  `byteInterface: true`, got back ServerHello, Certificate, ServerKeyExchange, CertificateRequest
  and ServerHelloDone in the clear. The certificate parsed with `CertificateFactory`, and its
  modulus matched the one openssl saw. A driver can therefore read the TV's public key
  without the user pasting a certificate *(probed 2.5.2.138, C-7, throwaway driver)*.
- Over TLS on 6467 with a client certificate the hub generated, `pairing_request`
  (`08 02 10 C8 01 52 ...`: protocol version 2, status 200, service `atvremote`) was answered with
  `08 02 10 C8 01 5A 00`, a `pairing_request_ack` with status 200 *(probed 2.5.2.138, C-7, throwaway driver)*.
- An unpaired client certificate on 6466 is refused during the handshake (see the `rawSocket`
  notes in `hubitat-platform-notes.md`) *(probed 2.5.2.138, C-7, throwaway driver)*.
- On the `uiw4020ebx` box (Technicolor "EBOX TV", Android 11, remote service `7.00.956317615`), a
  session opens with `remote_configure` (features 639), `remote_set_active`, then volume, power
  (`remote_start`) and current app (`remote_ime_key_inject`, package name in field 12) within about
  1.4 s of connecting. Pings arrive every 5 s, and kept arriving through 83 minutes of standby
  without the session dropping. POWER is reported back within 0.6 s *(probed 2.5.2.138, C-7,
  Android TV Remote Tester and prototype driver)*.
- The same box never reports the TV's volume: volume and mute keys bring no
  `remote_set_volume_level`, as it passes them to the TV over HDMI-CEC. It sends one only around a
  power change, with range 15 and level 0 (muted) or 15 *(probed 2.5.2.138, C-7, Android TV Remote Tester)*.
- An app-link launch with an `https://` URL opens the app (`https://www.youtube.com` opens
  `com.google.android.youtube.tv`). A bare package name and `market://launch?id=<package>` (the form
  androidtvremote2 sends for a package) are both answered with `remote_error` 7 echoing the request *(probed 2.5.2.138–2.5.2.139, C-7, Android TV Remote Tester and driver)*.
  After every one of 6 such errors the box went quiet or closed the session within 45 s *(probed 2.5.2.138, C-7, Android TV Remote Tester and prototype driver)*.
- On the same box a link in a scheme an installed app registers opens that app (`vnd.youtube://www.youtube.com`
  opens YouTube), while `intent:#Intent;package=<package>;end`, the same with the `MAIN` action and
  `LAUNCHER` category, and `android-app://<package>` are answered with `remote_error` 7 within 40 ms. No
  link form that names only a package opened an app *(probed 2.5.2.139, C-7, Android TV Remote driver 0.1.2)*.
  The box seems to open a link only when an app handles it, so error 7 would mean no app does *(inferred: from the accepted and refused schemes above)*.
- The same box's remote has a button that opens its operator app (`ca.ebox.tv.platform`). Sent from the home
  screen as `remote_key_inject`, none of key codes 131–142, 170, 172, 183–186, 188–203, 229 and 284 opened
  that app or drew any message back *(absent: probed 2.5.2.139, C-7, Android TV Remote driver 0.1.4)*.
- A Google TV Streamer (Android 14, remote service 7.00.956317615) also answers
  `market://launch?id=<package>` with `remote_error` 7 *(reported: [forum report](https://community.hubitat.com/t/beta-android-tv-remote-google-tv-and-android-tv-local-control/166822/12), firmware 2.5.2.139)*.
- Launching by package name is unreliable beyond these boxes: since a Google Play Store change, many
  apps no longer open from a `market://launch?id=` link, and the app's deep link is the form to use *(community: [Android TV Remote integration docs](https://www.home-assistant.io/integrations/androidtv_remote/); community: [androidtvremote2 #46](https://github.com/tronikos/androidtvremote2/issues/46))*.
- A wrong pairing secret is answered with status 402; the client's earlier pairing still works
  afterwards *(probed 2.5.2.138, C-7, Android TV Remote Tester)*.
- Cutting the box's power ended the session in two ways on different runs: no message at all
  (noticed only by the driver's 20 s idle timer) and a `receive error: Connection reset` about 29 s
  after the cut. The box powers on by itself when power returns, is back on 6466 about 70–75 s
  later, and keeps the pairing *(probed 2.5.2.138, C-7, Android TV Remote Tester and prototype driver)*.
- An app link sent in the first 100 ms of a session, before the box's `remote_configure`, is answered with
  `remote_error` 2 echoing the request, where the same link sent later opens the app *(probed 2.5.2.139, C-7, Android TV Remote driver 0.2.0)*.
  A key sent at the same point, 40 ms after the connection opened, made the box close the session: the client's
  `remote_configure` answer then failed with `send error: Connection or outbound has closed` *(probed 2.5.2.139, C-7, Android TV Remote driver 0.2.0)*.
  The same key and link held until the box's first `remote_start` (message 40), then sent, both worked *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.0 with held commands)*.
- A second connection from the same client while a session was open got the box's `remote_configure`, and the
  client's answer failed with `send error: Connection or outbound has closed` *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.0)*.
- Text is typed with `remote_ime_batch_edit` (message 21): the client echoes the IME and field counters of
  the box's last message 21 and sends the text as an insert edit. Apps take it only while their on-screen
  keyboard is hidden, and some fields add it to their text while others replace it *(community: [androidtvremote2 #25](https://github.com/tronikos/androidtvremote2/pull/25); community: [androidtvremote2 #32](https://github.com/tronikos/androidtvremote2/issues/32))*.
- On the `uiw4020ebx` box (remote service `7.00.999235495`), neither the Assistant search screen
  (`com.google.android.katniss`) nor YouTube's search page brought a message 21, and every
  `remote_ime_key_inject` carried only the app (field 1), no text field status (field 2) *(absent: probed 2.5.2.139, C-7, Android TV Remote driver 0.2.0 with sendText)*.
  A message 21 with both counters 0 sent to the search screen drew neither a reply nor `remote_error`; whether the text reached the screen was not seen *(probed 2.5.2.139, C-7, Android TV Remote driver 0.2.0 with sendText and Tester 0.5.0)*.
  The search screen closed by itself 9–11 s after it opened, whether or not text was sent, and BACK on the
  home screen after it closed once brought an empty message 24, a type the reference proto doesn't list *(probed 2.5.2.139, C-7, Android TV Remote Tester 0.5.0)*.

## Samsung Tizen TVs

- A 2020 TU7000 (UN50TU7000FXZC, firmware `T-KTSU2AKUC-2743.0`) announces itself over mDNS only as
  an AirPlay receiver, `_airplay._tcp` on port 47941, with the TV's name as the service name (`Andraye`
  for a TV whose REST name is `[TV] Andraye`) and host `Samsung.local`. Its TXT record has
  `manufacturer=Samsung`, `model=UTU7000`, `fv` (the firmware), `serialNumber`, `deviceid` and `pi`
  (both MAC-shaped), and `pk`, `gid`, `psi`, `features`, `flags`, `srcvers`, `protovers`, `rsf`,
  `gcgl` and `acl` *(probed 2.5.2.139, C-7, throwaway driver and avahi-browse from the LAN)*.
- Neither `deviceid` nor `pi` is the TV's MAC: both differ from the `wifiMac` that
  `GET :8001/api/v2/` reports and from the MAC the TV answers ARP with, and no TXT value matches the
  REST `id` (`uuid:...`). `deviceid` equals the device network ID the hub's built-in AirPlay
  integration gave the TV *(probed 2.5.2.139, C-7, avahi-browse and REST from the LAN, the hub's AirPlay device)*.
  Home Assistant's `samsungtv` integration discovers TVs on `_airplay._tcp` with
  `manufacturer=samsung*`, skips names containing `Soundbar`, and stores `deviceid` as the TV's MAC *(community: [HA core `samsungtv` manifest and `config_flow.py`](https://github.com/home-assistant/core/tree/dev/homeassistant/components/samsungtv))*,
  which on this TV is not the address Wake-on-LAN needs *(inferred: from the MAC comparison above)*.
- In standby the same TV leaves the network: no AirPlay record, no reply to ping, SSDP or port
  8001, and its ARP entry goes stale. The AirPlay record was back within about 10 s of a
  Wake-on-LAN power-on and gone within about 15 s of power-off *(probed 2.5.2.139, C-7, power by the Samsung TV Remote driver, avahi-browse, ping and SSDP from the LAN)*.
- Going to standby, the same TV withdraws its AirPlay record with an mDNS goodbye: a running
  `avahi-browse` reported the removal 19 s after the POWER key, far sooner than a record would
  expire. After 4 minutes in standby, `getMDNSServiceResults` listed the TV again under the same
  name 18 s after a Wake-on-LAN power-on, so standby doesn't trigger the hub's same-name issue
  (see `getMDNSServiceResults` in `hubitat-platform-notes.md`) *(probed 2.5.2.139, C-7, Samsung TV Remote driver and avahi-browse from the LAN)*.
  A power cut sends no goodbye, so it would *(inferred: from the platform note, not tested with this TV)*.

## Hubitat hubs

Per Hubitat staff ([forum, 2024-01-27](https://community.hubitat.com/t/what-is-c8-pro-soc/132597/6)) *(staff: [bravenel](https://community.hubitat.com/t/what-is-c8-pro-soc/132597/6))*:

| Model | SoC | CPU | RAM | Z-Wave | Zigbee |
|---|---|---|---|---|---|
| C-5 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 500 series, single US frequency (other regions need a dongle) | 1.2 |
| C-7 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 700 series, all regions via settings | 1.2 |
| C-8 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 800 series, all regions, external antenna | 3.0 |
| C-8 Pro | Amlogic A113X2 | Cortex-A55, 2.016 GHz | 2 GB | 800 series, all regions, external antenna | 3.0 |

The C-8 Pro uses a faster CPU than the other models *(staff: [bravenel](https://community.hubitat.com/t/what-is-c8-pro-soc/132597/6))*. A user in the same thread reports the C-8 Pro Ethernet still links at 100 Mb/s *(community: [forum](https://community.hubitat.com/t/what-is-c8-pro-soc/132597/18))*.
