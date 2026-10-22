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

**`lumi.weather` truncates pressure MeasuredValue to whole hPa; ScaledValue carries 0.1 hPa.** One cluster
`0x0403` report carries MeasuredValue (`0x0000`, INT16), Scale (`0x0014`, INT8) and ScaledValue (`0x0010`, INT16).
Raw `…0403 1C 0000 29 D103 1400 28 FF 1000 29 3326` gives MeasuredValue 977 (hPa), Scale `FF` (−1) and ScaledValue
9779, which is 977.9 hPa as hPa = ScaledValue × 10^Scale; the FF01 check-in tag `0x66` sent 97811 Pa just before.
A driver that reads MeasuredValue alone reports 97.8 kPa from the check-in, then 97.7 kPa from the cluster report
*(probed 2.5.2.139, C-7, one WSDCGQ11LM, driver logs and device events)*. zigbee2mqtt computes
hPa = ScaledValue / 10^Scale / 100 for this model, which gives the same value at Scale −1
*(community: [zigbee-herdsman-converters `fz.pressure`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/converters/fromZigbee.ts))*.
Scale is two hex characters, not four, so it can't go through a 4-character byte flip *(inferred: from the INT8 encoding above)*.

## Sinopé

**A DM2500ZB dimmer turned on reports level 0 first, then the real level.** After `on` from off
(at a stored 30 %), the frames were an on/off report (on), a level report of 0x00, and about 1.5 s later a level
report of the real value (0x4C). `off` sent one on/off report and no level change. So the first level report
after `on` is a transient 0, and logic that treats the first report after a command as the command's result
picks the 0 *(probed 2.5.2.134, one DM2500ZB, `/zigbeeLogsocket`)*.

**A DM2500ZB dimmer reports levels while it fades, and its last level after an off depends on how it was turned off.**
At a stored 60 %, an `off` command was followed within about 1 s by a level of 56–59 % and about 4.5 s later
by the stored 60 %. A short Down press reported 59 %, then off, then 0 % about 4 s later. A short Up press
from off reported on, 12 %, then 60 % about 5 s later. The button report arrives before the first level report
*(probed 2.5.2.139, three DM2500ZB, device event history over two days)*.

**On a DM2500ZB, `0xFF01`/`0x0090` does not follow the load.** While on at 60 %, it rose by exactly 1800 every
30 minutes on three dimmers driving different lights, and it stayed flat while off
*(probed 2.5.2.139, three DM2500ZB, logged energy events over two days)*.
The lights and dimmers have no power-measurement cluster: their attribute table lists `0x0090` as
currentSummationDelivered in Wh and a writable ConnectedLoad (`0xFF01`/`0x0119`, uint16, watts, 0 = none)
*(community: [claudegel/sinope-zha README](https://github.com/claudegel/sinope-zha) at `7902729`, lights and dimmer table)*.
**`0x0090` counts seconds on, not Wh, and ConnectedLoad does not change it.** After ConnectedLoad was written to
100 W (Write Attributes status 0, read back as `0x0064`), the counter still rose one unit per second while on:
517 s on gave +519, the same rate as before the write, when the driver had never set ConnectedLoad *(probed 2.5.2.139, one DM2500ZB, trace logs)*.
**The standard Metering cluster (`0x0702`) holds a separate energy counter.** Discover Attributes lists `0x0000`
CurrentSummationDelivered (uint48), `0x0200` Status, `0x0300` UnitOfMeasure, `0x0301` Multiplier, `0x0302` Divisor,
`0x0303` SummationFormatting, `0x0306` MeteringDeviceType and `0xFFFD`; there is no `0x0400` InstantaneousDemand
(a read answers status `0x86`). UnitOfMeasure is 0 (kWh), Multiplier 1 and Divisor 1000, so the counter is in Wh.
It read 45 with ConnectedLoad at 100 W, and the device sends it in an unsolicited Report Attributes
*(probed 2.5.2.139, one DM2500ZB, ZCL reads from another device and `/zigbeeLogsocket`)*.
With ConnectedLoad at 100 W it rose 6 in 300 s on at 60 %, and three devices with no ConnectedLoad reported 0
*(probed 2.5.2.139, one DM2500ZB at 100 W and three devices at 0, `/zigbeeLogsocket`)*.
**The estimate scales with the dim level: Wh = ConnectedLoad × time on × level.** At 100 W, 630 s at 100 % added 18 Wh
(17.5 expected) and 635 s at 30 % added 5 Wh (5.3 expected; full load would give 17.6), within the counter's 1 Wh step
*(probed 2.5.2.139, one DM2500ZB, device events)*.
**Configure Reporting on `0x0702`/`0x0000` needs a reportable change.** Without one, the DM2500ZB and SW2500ZB answer
status `0x85` (INVALID_FIELD); with min 60 s, max 1800 s and change 10 they answer `0x00`
*(probed 2.5.2.139, one DM2500ZB and one SW2500ZB, `/zigbeeLogsocket`)*.
Sinopé's own app shows a light switch or dimmer in its energy graphs only when it has a load reader or a
connected load entered in its settings, and its DM2500ZB page offers a usage graph in minutes *(docs: Sinopé support,
[device not in consumption graphs](https://support.sinopetech.com/en/neviweb/2.2.1.1/),
[configure your DM2500ZB](https://support.sinopetech.com/en/neviweb/2.1.2.1.1/))*.
The Neviweb integration for Home Assistant reads a user-set wattage (`loadWattOutput1`) for the DM2500ZB (model 2131)
and a measured `wattageInstant` only for the DM2550ZB (model 2132) *(community: [claudegel/sinope-130](https://github.com/claudegel/sinope-130) at `0d0d67c`, `light.py`)*.

**The SW2500ZB and DM2500ZB paddle report (`0xFF01`/`0x0054`) sends a press-down code before the gesture code.**
Up: 1 press-down, 2 single tap, 3 hold, 4 double tap; Down: 17, 18, 19, 20 (hex `11`–`14`)
*(community: [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/cbc26f0/src/devices/sinope.ts), `actionReport` lookup)*.
One short Down press on a SW2500ZB sent `11` then `12`, 120 ms apart, so a driver that maps the press-down
code to `pushed` also fires it ahead of every hold and double tap *(probed 2.5.2.139, one SW2500ZB, device event history)*.
Whether a hold ends with a further code is untested.

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

**Valve battery: the percentage attribute reads 0; derive it from voltage.** A read of Power Configuration `0x0001`/`0x0021` (battery percentage remaining) returns status success, uint8 `0x00`. In the same refresh, `0x0020` (battery voltage, 1/10 V) returned `0x3E` (6.2 V) *(probed 2.5.2.139, valve firmware `119C-0026-00010203`, Zigbee log capture of a `refresh()`)*. The pack is 4× AAA. The `Sinope_VA422xZB` driver maps voltage linearly from 5.6 V to 7.2 V (0–100 %), which is 4× the 1.4–1.8 V lithium range *(spec: [Energizer L92 datasheet](https://data.energizer.com/pdfs/l92.pdf))*. Fresh alkaline cells sit near 1.5–1.6 V, so a new alkaline pack reads well short of 100 % on that scale *(inferred: alkaline voltage against the lithium mapping)*. One user with fresh alkaline cells saw 25 % *(reported: [forum topic 166638](https://community.hubitat.com/t/166638/3))*. Zigbee2MQTT's VA4220ZB also ignores the percentage and maps voltage linearly from 5.4 V to 6.8 V; ZHA shows `0x0021` as-is *(community: [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sinope.ts), [claudegel/sinope-zha `switch.py`](https://github.com/claudegel/sinope-zha))*.

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

**The G2 backlight values are 0 on demand, 1 always on, 2 adaptive.** sacua's driver used 2 for off and 0 for adaptive until its fix of 2026-09-20; claudegel's `Backlight` enum and the fixed sacua map agree, and Z2M's `backlight_auto_dim` names the same values `on_demand`, `off` (no dimming) and `sensing` *(community: [sacua/SinopeDriverHubitat `2589409`](https://github.com/sacua/SinopeDriverHubitat/commit/2589409ecd5c78140fb2196239ff03529cd1ff83), [claudegel/sinope-zha](https://github.com/claudegel/sinope-zha) at `7902729`, [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/72398c3/src/devices/sinope.ts))*.

**Read the electrical scale from the device; it differs by model.** Electrical Measurement `0x0600`–`0x0605` (voltage, current and power multiplier and divisor) read 1/1 for voltage on an RM3250ZB and an RM3500ZB, and 1/10 on a TH1123ZB-G2 and a TH1124ZB; current is 1/1000 (mA) and power 1/1 (W) on all four *(probed 2.5.2.139, C-7: RM3250ZB `119C-000C-01030202`, RM3500ZB `119C-002F-00020300`, TH1123ZB-G2 `119C-0034-00010400`, TH1124ZB application `40`)*. A fixed divisor of 10 shows these load controllers at a tenth of their voltage *(inferred: from the divisor read)*.

**What each device accepts in Configure Reporting** (the devices above and one SW2500ZB):

- Device Temperature `0x0002`/`0x0000`: an RM3250ZB and an SW2500ZB answer `0x8C` (UNREPORTABLE_ATTRIBUTE), so the value changes only when read. An RM3500ZB accepts it, and with minimum interval 0 and no reportable change it then reported every 0.5 s, the same value each time; minimum 60 s with change 1 stopped it. *(probed 2.5.2.139, C-7, `/zigbeeLogsocket`)*
- Thermostat UI `0x0204` (display mode, keypad lockout): a TH1124ZB answers `0x8C`; a TH1123ZB-G2 accepts both. *(probed 2.5.2.139, C-7, `/zigbeeLogsocket`)*
- RMS voltage `0x0B04`/`0x0505`: a TH1123ZB-G2 answers `0x8D` (INVALID_DATA_TYPE) to type `0x29` (int16); the attribute is uint16 (`0x21`). *(probed 2.5.2.139, C-7, `/zigbeeLogsocket`)*
- A TH1123ZB-G2 with seven entries configured answered `0x89` (INSUFFICIENT_SPACE) to an eighth *(probed 2.5.2.139, C-7, `/zigbeeLogsocket`)*. Z2M notes the G2 holds at most 8 reporting entries *(community: [zigbee-herdsman-converters `sinope.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/72398c3/src/devices/sinope.ts), TH1123ZB-G2 `configure`)*. That device also sent active-power reports that none of the drivers it ran configure, so entries set earlier stay on the device and count against the limit *(inferred: from the unsolicited `0x050B` reports)*.
- A TH1123ZB-G2 answers `0x86` (UNSUPPORTED_ATTRIBUTE) to a read of ConnectedLoad `0xFF01`/`0x0119`; a TH1124ZB returns it. *(probed 2.5.2.139, C-7, `/zigbeeLogsocket`)*

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

## ThirdReality 3RSP plugs

Read Reporting Configuration of OnOff (0x0006:0x0000) returned a record of type 0x10 with minimum interval 0 s on both plugs. The maximum interval was 60 s on a 3RSP019BZ and 600 s on a 3RSP02064Z *(probed 2.5.2.139, C-7, one plug of each model)*.

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
  without the user pasting a certificate *(probed 2.5.2.138, C-7, throwaway driver)*. From 2.5.3.104 the driver can instead read it with
  `rawSocket.getPeerCertificate()` on the TLS pairing socket, which gives it under TLS 1.3 too
  *(probed 2.5.3.104, C-8 Pro, Android TV Remote driver against a mock TV, and a throwaway driver against a TLS 1.3 server)*.
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
- On the same box, the link `ca.ebox.tv.platform://` (the package name used as a scheme) opens the
  operator app; `ebox://`, `eboxtv://`, `ebox-tv://` and `ca.ebox.tv://` are refused with `remote_error` 7.
  `content://android.media.tv/channel` links open the stock `com.android.tv` app, and `https://tv.ebox.ca`
  is accepted but changes no foreground app *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.0)*.
- The package-name scheme is particular to that app, not a general launch form: on the same box
  `com.google.android.youtube.tv://`, `com.android.tv://` and `com.google.android.tvlauncher://` are refused
  with `remote_error` 7 although each app is installed, while `ca.ebox.tv.platform:` with any number of
  slashes opens the EBOX app *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.0)*.
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
- Text is typed with `remote_ime_batch_edit` (message 21) as an insert edit. androidtvremote2 echoes the
  IME and field counters of the box's last message 21, and some fields add the text to theirs while others
  replace it *(community: [androidtvremote2 #25](https://github.com/tronikos/androidtvremote2/pull/25); community: [androidtvremote2 #32](https://github.com/tronikos/androidtvremote2/issues/32))*.
- On the `uiw4020ebx` box (remote service `7.00.999235495`), SEARCH opens the Assistant overlay
  (`com.google.android.katniss`, "Ask me something" with suggestion pills). It listens for speech and closes
  by itself 9–11 s after it opens, and it has no text field *(probed 2.5.2.139, C-7, Android TV Remote Tester 0.5.0 and driver 0.3.2)*.
  BACK on the home screen after it closed once brought an empty message 24, a type the reference proto doesn't list *(probed 2.5.2.139, C-7, Android TV Remote Tester 0.5.0)*.
  The overlay's keyboard icon, selected with the physical remote, opens a text field with the keyboard up *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.2 and 0.3.3)*.
- On that box, a text field taking focus brings a message 20 with the text field status in field 2
  (`counter_field`, value, cursor start and end, label), then a message 21 `{{1:1 2:1}}`. BACK closing the
  keyboard brings a message 21 `{{1:0 2:0}}`; each cursor move brings a message 23 with the new cursor start and
  end and no counter, and each applied edit brings a message 22 with the field status *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.2 and 0.3.3)*.
- On that box, an edit applies with the keyboard up or down when its counters are 0/0, or when its field
  counter is the box's last `counter_field` or a few steps behind it (2 cursor moves behind applied). The
  counters 1/1 of the box's message 21, a field counter 75 behind, and one far ahead of the box's (999) were dropped
  with no reply and no `remote_error`. With 0/0 the box types the text one character at a time (a message 22
  per character); with its own counter it inserts the text at the cursor in one edit *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.2 and 0.3.3)*.
  Accented text (UTF-8) reached these fields intact, and the cursor in the echo counts characters, not bytes. The key DEL (code 67) deletes one character per press and brings a message 22 each time *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.3)*.
- On that box, the EBOX app's own search field (`ca.ebox.tv.platform`) reported itself in message 20 field 2
  with no message 21, and took text sent with its field counter, echoed one character at a time *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.3)*.
- On that box, YouTube's search screen (`com.google.android.youtube.tv`) reported no text field. Text sent
  with counters 0/0 brought no message 22 and no `remote_error`, yet its ASCII letters appeared in the search
  box and its accented letters were dropped (`météo` showed as `mto`) *(probed 2.5.2.139, C-7, Android TV Remote driver 0.3.3)*.
- After a factory reset with no Google account signed in, the same EBOX hid its on-screen keyboard while a
  remote session with the IME feature (4) was connected, showing "use your mobile device's keyboard" in its
  place; with the session closed the keyboard came back. Before the reset, with an account signed in, the
  keyboard showed with the session connected *(probed 2.5.2.139, C-7, Android TV Remote driver 0.4.0)*.
  Home Assistant's `enable_ime` option exists for this case *(community: [HA strings.json](https://github.com/home-assistant/core/blob/dev/homeassistant/components/androidtv_remote/strings.json))*.
- On an NVIDIA SHIELD (Android 11, remote service `7.00.999235495`), text sent while the search box's
  on-screen keyboard was up did not appear. After one BACK closed the keyboard and left the search box on
  screen, the text appeared in the box and the search could be run. The box sent no message 21, and the
  search screen (`com.google.android.katniss`) closed by itself 9 s after it opened *(reported: [forum topic 166822](https://community.hubitat.com/t/166822/37), posts 37–38, driver 0.3.1, hub 2.5.3.103)*.

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
