<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat `zigbee` helper — reference

Reverse-engineered reference for the `zigbee` object Hubitat injects into drivers. Hubitat's [own page](https://docs2.hubitat.com/en/developer/zigbee-object) gives signatures and one-line descriptions, and the hub's built-in API reference documents `ZigbeeCluster`, `ZoneStatus`, `SmartShield` and `DataType`. This doc adds what reflection and probes on a running hub show, with the original SmartThings docs for context (Hubitat's helper is descended from SmartThings').

Every fact carries an evidence mark ([EVIDENCE.md](EVIDENCE.md)); the sources below are the ones most marks point to.

**Sources**
- Reflection on the `zigbee` object and related classes, firmware 2.5.0.148, C-8 Pro (marked `reflection dump`)
- SmartThings docs (Wayback): [zigbee-ref @ 2020-05-10](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html), [zigbee-primer @ 2020-10-08](https://web.archive.org/web/20201008180753/https://docs.smartthings.com/en/latest/device-type-developers-guide/zigbee-primer.html), [building-zigbee-DHs @ 2020-09-21](https://web.archive.org/web/20200921015021/https://docs.smartthings.com/en/latest/device-type-developers-guide/building-zigbee-device-handlers.html)
- Code examples (still live): the various `zigbee-*-handler.src/*.groovy` under [SmartThingsCommunity/SmartThingsPublic](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings)


## How to inspect it yourself

[Object introspection](hubitat-platform-notes.md#object-introspection) in the platform notes covers the techniques and their sandbox limits. `zigbee.properties` has only 6 entries (`class`, `cluster`, `device`, `deviceNetworkId`, `endpointId`, `zigbeeId`), because most `zigbee.*` members take arguments and aren't bean properties *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh; probed 2.5.0.148, reflection dump)*. The full method and field lists came from Class reflection, which 2.5.2.135 refuses (`getMethods()` and `getFields()` on any receiver), so the 2.5.0.148 results are the record *(false 2.5.0.148; probed 2.5.2.135)*.

## What the `zigbee` object actually is

`com.hubitat.zigbee.Zigbee` holds a `DeviceWrapper` reference (`getDevice()` / `setDevice(DeviceWrapper)`), so all the convenience builders know which endpoint to address without you passing it *(probed 2.5.0.148, reflection dump; docs: on-hub api-com-hubitat-zigbee-zigbee, 2.5.2.134)*.

## Methods (curated, with semantics)

Signatures below are from the live reflection. Semantics in italics are quoted from the SmartThings docs unless noted *(probed 2.5.0.148, reflection dump; community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*.

### Parse helpers

| Method | Returns | Notes |
|---|---|---|
| `getEvent(String description)` | `Map` | *"Tries to parse ZigBee Clusters into a map whose key/value pairs can be directly handled by `sendEvent()`."* *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))* With synthetic `read attr` descriptions it recognizes `0x0006` (switch), `0x0008` (level, scaled to %), `0x0402` (temperature), and `0x0001` attrs `0x0021` (battery %, value/2) and `0x0020` (`batteryVoltage`, value/10). Returns an empty map (not null) for `0x0405` humidity, `0x0400` illuminance, `0x0406` occupancy, `0x0403` pressure, `zone status` strings, and ST-style `temperature: 21.6` strings. For the two-byte temperature it byte-swaps the `value:` field (`0898` decodes as −266 °C, `9808` as 22 °C), whereas `parseDescriptionAsMap`-based decoders read `value` big-endian; until checked against a live sensor, don't rely on it for temperature *(probed 2.5.2.124, synthetic descriptions)*. |
| `parseDescriptionAsMap(String)` | `Map` | The workhorse. For a `read attr` description it returns `raw`, `dni`, `endpoint`, `cluster`, `size`, `attrId`, `encoding`, `command`, `value`, `clusterInt` and `attrInt`, and no `commandInt` or `isValidForDataType` *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh)*, although SmartThings DTHs read both, and `additionalAttrs` for multi-attribute reports *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings))*. The on-hub reference documents the schema: `catchall:` and `read attr -` input only, `additionalAttrs` entries with `value`, `encoding`, `attrId`, `consumedBytes` and `attrInt`, or error entries with `status` `86`; it lists neither `commandInt` nor `isValidForDataType` *(docs: on-hub api-com-hubitat-zigbee-zigbee, 2.5.2.134)*. A char-string value in `additionalAttrs` already has its length prefix stripped *(probed firmware not recorded, Aqara FF01 frames on a C-7)*. |
| `parseZoneStatus(String description)` | `ZoneStatus` | *"The description should be of the form `'zone status {number}'` where `{number}` is a hex number."* *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))* Returns `hubitat.zigbee.clusters.iaszone.ZoneStatus` — see below *(probed 2.5.0.148, reflection dump)*. `zone status 0x0021 -- extended status 0x00` parses *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. |
| `parseZoneStatusChange(String)` | `Map` | Hubitat-only (not in ST) *(probed 2.5.0.148, reflection dump; spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html) lists no such method)*. Parses the description of a Zone Status Change Notification (cluster `0x0500` command `0x00`) *(spec: ZCL (07-5123 rev 8), 8.2.2.3.1)*. It needs the full form `zone status 0x0021 -- extended status 0x00 - sourceEndpoint:01, zoneId:FF, delay:0000`; without the trailing fields it throws NullPointerException. It returns a LinkedHashMap of `sourceEndpoint`, `extendedStatus`, `zoneId` and `delay` (Strings) and ten Booleans `alarm1Set` … `batteryDefectSet` *(probed 2.5.2.135, C-8 Pro, throwaway driver; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |
| `parse(String)` | `SmartShield` | Low-level catch-all. Returns `hubitat.zigbee.SmartShield`, the raw ZCL frame wrapper (clusterId, command, profileId, source/dest endpoints, data list, manufacturer flags). Most drivers should use `parseDescriptionAsMap` instead *(probed 2.5.0.148, reflection dump; docs: on-hub api-hubitat-zigbee-smartshield, 2.5.2.134)*. |
| `getKnownDescription(String)` | `Map` | Recognizes On/Off reports only: a `read attr` for cluster `0006` attribute `0000`, or an `0006` `catchall`, returns `[name:switch, value:on, type:switch]` (or `value:off`); anything else, including `on/off: 1`, returns `[:]` *(probed 2.5.2.135, C-8 Pro, throwaway driver; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |

### Low-level cluster I/O

These all take an optional trailing `Map additionalParams = [:]` with keys `mfgCode` (Integer) and `destEndpoint` (Integer) — *"intended to support future params without affecting backward compatibility"*, which explains the map rather than positional args *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html); docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. The trailing `int` overloads carry a delay (ms) for batching *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*.

| Method | Notes |
|---|---|
| `command(Integer cluster, Integer command, String... payload)` | *"Each argument should be passed as an ASCII hex string in little-endian format of the appropriate width for the data type. For example, to pass the value 5 for a UINT24 you would pass `050000`."* *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html); docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))* |
| `command(Integer cluster, Integer command, Map params, String... payload)` | Same with mfg/endpoint override *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |
| `readAttribute(Integer cluster, Integer attributeId)` | Returns `List<String>` of `he raw` commands *(probed 2.5.0.148, reflection dump)*: `readAttribute(0x0006, 0x0000)` gives `he raw 0x<dni> 1 0x<ep> 0x0006 {10 00 00 00 00}` (a ZCL Read Attributes frame) and `delay 2000` *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh)*. |
| `readAttribute(Integer cluster, List attributeIds)` | Multi-attribute read in one frame *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |
| `writeAttribute(Integer cluster, Integer attrId, Integer dataType, value)` | ST docs caveat: *"The Integer value to write for data types of boolean, unsigned int, signed int, general data, and enumerations. Other data types are not currently supported but will be added in the future."* *(spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))* Hubitat now overloads with `String` for the value too, so string-class types are reachable *(probed 2.5.0.148, reflection dump)*. |
| `configureReporting(Integer cluster, Integer attrId, Integer dataType, Integer minSec, Integer maxSec[, Integer reportableChange])` | *"reportableChange required for analog data types. Discrete data types should always provide null for this value."* `minSec` = minimum reporting interval, `maxSec` = max if nothing changes (a heartbeat), `reportableChange` = min delta to trigger a report *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html); docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |
| `reportingConfiguration(Integer cluster, Integer attrId[, Map])` | Hubitat-only *(probed 2.5.0.148, reflection dump; spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html) lists no such method)*. Reads the device's CURRENT reporting config (Read Reporting Configuration command, ZCL command `0x08`) — useful for diagnosing why a sensor isn't reporting *(docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object); spec: ZCL (07-5123 rev 8), Table 2-3)*. |
| `refreshData(String, String)` | Builds a read-attribute command: `refreshData("0x0006", "0x0000")` returns `he rattr 0x<dni> 0x<ep> 0x0006 0x0000 {}` and `delay 2000`, so the arguments act as cluster and attribute *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. docs2 calls them two attribute IDs *(docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. |
| `updateFirmware([Map])` | Hubitat-only *(probed 2.5.0.148, reflection dump; spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html) lists no such method)*. Builds an OTA Image Notify: `updateFirmware()` returns one command and no delay line, `he raw 0x<dni> 0x01 0x<ep> 0x0019 {09 01 00 03 64 FFFF FFFF FFFF FFFF}`; a Map argument changes nothing *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. That is frame control `0x09` (cluster-specific, server to client), sequence `01`, command `0x00` Image Notify, payload type `0x03` (query jitter, manufacturer code, image type and file version), jitter `0x64`, and wildcard manufacturer code, image type and file version *(spec: ZCL (07-5123 rev 8), 2.4.1.1 and 11.13.3)*. The on-hub reference calls it an image-query command *(docs: on-hub api-com-hubitat-zigbee-zigbee, 2.5.2.134)*. |

**Prefer these helpers over `he raw`.** The helpers are typed and endian-correct; `he raw` is hand-assembled frame strings (FCF bits, sequence byte, endianness) that are easy to get wrong and noisy to read. Reach for `he raw` only when no helper covers the operation *(inferred: helpers build the frame from typed arguments, `he raw` is a hand-written frame)*.

**ZDO has no helper — `he raw` is the only path.** `zigbee.command(cluster, …)` assembles a *ZCL* frame: FCF byte + transaction-sequence byte + command-ID byte + payload *(docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object); spec: ZCL (07-5123 rev 8), 2.4.1)*. Even for a ZDO cluster it builds a ZCL `he cmd`: `zigbee.command(0x0031, 0x00)` returns `he cmd 0x<dni> 0x<ep> 0x0031 0x00 {}` and `delay 2000` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. ZDO requests carry none of that framing — just a sequence byte followed by the payload *(spec: Zigbee Specification (05-3474-22), 2.4.2.8)* — so `zigbee.command()` produces structurally wrong frames for the ZDO clusters (`0x0031` Mgmt_Lqi_req, `0x0032` Mgmt_Rtg_req, `0x0036` Mgmt_Permit_Joining_req) *(inferred: the output above and the two frame formats)*. Build those with `he raw` directly: `0 0` for source/dest endpoints, a `{00 …}` leading sequence byte *(inferred: from drivers/sinope/Sinope_SW2500ZB.groovy and drivers/zigbee_helpers/ZigbeeMeshProbe.groovy)*. See [ZDO responses](#zdo-responses) for decoding the replies.

**The hub rewrites the sequence byte of `he raw` frames, ZDO and ZCL.** Requests sent with sequence bytes 08, 09, 0A and 0B (Node_Desc, Simple_Desc, Read Attributes, Discover Attributes) went out as 6F, 70, 7B and 7C, and the devices answered correctly, so the rest of each frame arrived intact. Consecutive requests got consecutive numbers, apparently from one counter shared by ZDO and ZCL. Match replies by cluster, plus endpoint for ZCL (ZDO frames use endpoint 00), not by sequence number *(probed 2.5.2.139, C-7, one 3RSP019BZ plug, `/zigbeeLogsocket`)*. With a non-zero sequence byte, a Simple_Desc_req sent 2 to 3 s after another ZDO request sometimes went out with that byte kept, and the device answered NOT_ACTIVE (raw `<seq> 83 54 38 00`); the hub's counter had skipped one number. 3 of 5 such pairs failed and 0 of 3 sent alone. With sequence byte 00, as the hub's own helpers send, 6 of 6 pairs and every later run succeeded. The cause is not known *(probed 2.5.2.139, C-7, one 3RSP019BZ plug)*. One guess fits NOT_ACTIVE: the hub writes its number into a later byte, the endpoint *(inferred: not tested)*.

**A Write Attributes sent with `he raw` is answered with the one-byte all-success Write Attributes Response.** Writing StartUpOnOff (cluster 0x0006, attribute 0x4003, enum8) with the value just read gave that response, and the plugs' drivers ignored it: driver, settings and data were unchanged, and only one driver's `lastRx` moved *(probed 2.5.2.139, C-7, 3RSP019BZ and 3RSP02064Z plugs)*. A Read Reporting Configuration for OnOff returned a configured record (see the [3RSP plugs](hubitat-device-reference.md#thirdreality-3rsp-plugs)) *(probed 2.5.2.139, C-7, 3RSP019BZ and 3RSP02064Z plugs)*.

**`configureReporting` intervals.** `max=0` means *report on change-of-value only* — NOT disabled. There's just no periodic heartbeat; the device still pushes every change. `0xFFFF` is what disables reporting per the ZCL spec *(spec: ZCL (07-5123 rev 8), 2.5.7.1.6)*. For discrete types (bool `0x10`, enum8 `0x30`, bitmap8 `0x18`) omit `reportableChange` — they report on any change *(spec: ZCL (07-5123 rev 8), 2.5.7.1.7; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. Reporting and refresh/polling are **not** redundant: reporting is the device pushing changes autonomously (including buffering across brief hub outages); refresh is the hub pulling current values on demand (boot rehydration, user-clicked Refresh). A Zigbee device acts independently of the hub, so the two cover different scenarios and normally coexist *(inferred: ZCL attribute reporting model)*.

**Reading the Configure Reporting Response.** The response arrives in `parse()` as profileId `0104`, `command 07`. `data[0]` = status (`0x00` success; `0x8D` INVALID_DATA_TYPE when the advertised data type doesn't match what the device actually encodes — e.g. declaring S16 `0x29` for an attribute the device sends as UINT32 `0x23`); `data[2..3]` = the attribute id, little-endian *(spec: ZCL (07-5123 rev 8), 2.5.8, Figure 2-18 and Table 2-12)*. A wrong reporting data type fails **silently** — the frame falls through to the generic unhandled-command path and the unsolicited reports simply never arrive — unless you decode this and warn on non-zero status *(probed firmware not recorded, Stelpro HT402 driver)*.

### Capability convenience builders

Each returns `List<String>` of pre-formatted `he raw`/ZCL commands ready to return from a driver `command`/`refresh` method *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*.

- **Switch:** `on()`, `off()`, `onOffConfig()`, `onOffConfig(min, max)`, `onOffConfig(String attr, min, max)`, `onOffRefresh()` *(probed 2.5.0.148, reflection dump)*
  - `on()`/`off()` emit **only** the `0x0006` On/Off command — they do NOT chain a `readAttribute`: `zigbee.on()` returns `he cmd 0x<dni> 0x<ep> 6 1 {}` and `delay 2000`, and `off()` the same with command `0` *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh; probed 2.5.2.135, C-8 Pro, throwaway driver)*. A retry/verify watchdog must watch the `switch` attribute (the device's own On/Off attribute report), not a ZCL Default Response: the Default Response only says "command received, status 00", not that the relay moved *(spec: ZCL (07-5123 rev 8), 2.5.12)*. A no-op command (device already in the target state) fires no on-change report and no Read Response, so nothing ever confirms it and the platform command-retry watchdog exhausts its retries. Chain `zigbee.readAttribute(0x0006, 0x0000)` after the command to force state confirmation even for no-ops *(probed firmware not recorded, Sinopé SW2500ZB/DM2500ZB drivers)*.
- **Level:** `setLevel(level)`, `setLevel(level, rate)` (level 0–100, rate in tenths of a second, max 100 = 10s), `levelConfig()`, `levelRefresh()` *(probed 2.5.0.148, reflection dump; community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*
- **Color Temperature:** `setColorTemperature(K)` (Kelvin, ST notes "usually > 2700"), `colorTemperatureConfig()`, `colorTemperatureRefresh()` *(probed 2.5.0.148, reflection dump; community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*
- **Full color** (Hubitat-only, beyond what ST documented): `setColor(Map)`, `setColorXY(Map)`, `setHue(Integer)`, `setSaturation(Integer)` *(probed 2.5.0.148, reflection dump; spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html) lists none of them)*
- **Battery / Power:** `batteryConfig()`, `electricMeasurementPowerRefresh()` *(probed 2.5.0.148, reflection dump)*
- **Temperature:** `temperatureConfig()`, `temperatureConfig(min, max)` *(probed 2.5.0.148, reflection dump)*
- **IAS Zone:** `enrollResponse()` (cluster `0x0500` enrollment ack) *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*

### Group commands (Hubitat-only)

Issue a multicast to a Zigbee group:

- `groupOn(Integer groupId)`, `groupOff(Integer groupId)` *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*
- `setGroupLevel(Integer groupId, level[, rate])` *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*
- `setGroupColorTemperature(Integer groupId, value[, rate, duration])` *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*

### Utility

- `convertHexToInt(String)`, `convertToHexString(Integer value, Integer width)` (width is the minimum number of hex digits; `convertToHexString(10, 4) → "000A"`) *(probed 2.5.0.148, reflection dump; community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html); docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*
- `swapOctets(String)` swaps the first two bytes and drops the rest: `swapOctets("1234")` and `swapOctets("12345678")` both return `"3412"` *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. It is not a general endian swap, so it is not a full replacement for ST's `swapEndianHex`, which SmartThings DTHs used on four-digit values *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings))*
- `hex2String(String)` *(probed 2.5.0.148, reflection dump; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*
- `clusterLookup(Object)` → `ZigbeeCluster` enum value. It takes an int or a hex string (`6`, `"0x0006"`); a name such as `"ON_OFF"` throws NumberFormatException *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh; docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*

## Strings the helpers build

The command helpers only build strings; calling them sends nothing, and a driver returns them or hands them to `sendHubCommand`. These are the strings *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.

- **Inputs.** `0x<deviceNetworkId>` is the device's network id and the endpoint is `0x<endpointId>` of the device, which prints as `0xnull` when the device has none. A trailing `[destEndpoint: n]` replaces the endpoint (`2` prints `0x02`, `255` prints `0xFF`, a String `"2"` prints `0x32`, the hex of its character), and `[mfgCode: m]` takes an Integer or a String of hex digits with or without `0x` and in any case (`4571`, `"0x119C"`, `"119C"`, `"0x119c"`) *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. A device whose endpoint was set only as a data value still prints `0xnull`: the helper reads the device's `endpointId`, which the device page's Save sets and a driver code push clears *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Argument types.** The overloads take `Integer`, `Map` and `List` arguments, and `String` for `writeAttribute`'s value. A `Long`, `BigDecimal`, `Boolean` or a `String` where an `Integer` goes is `MissingMethodException` (`readAttribute("0x0006", "0x0000")`, `writeAttribute(..., true)`, `configureReporting` with a Long reportable change, `setLevel("50")`, `convertToHexString` with a Long) *(probed 2.5.2.135, C-8 Pro, throwaway driver)*. Computed calls such as `zigbee."${name}"(...)` throw `SecurityException: Computed method calls are not allowed on com.hubitat.zigbee.Zigbee from app or driver code` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Delays.** Each command is followed by `delay 2000`, or by `delay n` when the call ends in an `int` (`readAttribute(6, 0, [:], 1000)`); `0` or a negative number leaves the delay line out. The commands are GStrings; the delay line is a GString too, except in `writeAttribute`, `command` without a Map, `refreshData`, `onOffConfig`, `levelConfig`, `batteryConfig`, `temperatureConfig` and `colorTemperatureConfig`, where it is a String *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`readAttribute(cluster, attr)`** gives `he raw 0x<dni> 1 0x<ep> 0x<cluster4> {10 00 00 <attrLo> <attrHi>}`. A list of attributes appends each as two bytes (`{10 00 00 00 06 01 06 02 06}` for `[0x0600, 0x0601, 0x0602]`, `{10 00 00}` for an empty list). With `mfgCode` the frame control is `04`, then the code little-endian, then `00 00` and the attributes: `{04 9C 11 00 00 00 00}` for `0x119C` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`reportingConfiguration(cluster, attr)`** gives `he raw 0x<dni> 1 0x<ep> 0x<cluster4> {10 00 08 00 <attrLo><attrHi>}`, and with `mfgCode` `{14 <codeLo><codeHi> 00 08 00 <attrLo><attrHi>}` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`writeAttribute(cluster, attr, type, value)`** gives `he wattr 0x<dni> 0x<ep> 0x<cluster4> 0x<attr4> 0x<type2> {<value>} {<mfg>}`, where the last brace is the manufacturer code as four hex digits (`{119C}`) or empty. An Integer value prints in upper-case hex, padded to the width `DataType.getLength` gives the type (UINT8 2 digits, INT16 4, UINT24 6, UINT48 12), a negative one masked to that width (`-150` as INT16 is `FF6A`, `-1` as UINT8 is `FF`) and a positive one never truncated (`256` as UINT8 prints `100`). A String value is copied as given (`"04"`, `"4"`, `"0x04"`, `"abc"`) *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`configureReporting(cluster, attr, type, min, max[, change[, map[, delay]]])`** gives a bind and the reporting command: `zdo bind 0x<dni> 0x<ep> 0x01 0x<cluster4> {<zigbeeId>} {}`, then `he cr 0x<dni> 0x<ep> <cluster> <attr> <type> <min> <max> {<change>} {<mfg>}` with cluster, attribute, type, min and max in decimal. The reportable change is printed only for analog types (`0x20`–`0x2F`, `0x38`–`0x3A`) and then little-endian in the type's width (`50` as INT16 is `3200`, `-50` is `CEFF`, `10` as UINT48 is `0A0000000000`); null, `0` and every other type print `{}`. A change too wide for the type, `300` for a UINT8, throws `StringIndexOutOfBoundsException` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`command(cluster, cmd, payload...)`** gives `he cmd 0x<dni> 0x<ep> 0x<cluster4> 0x<cmd2> {<payload>}`, the payload strings joined with nothing between. With a Map argument (even an empty one) a manufacturer brace follows: `he cmd ... {} {119C}`; the Map has no effect on the payload text *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Builders without arguments.** `on()` gives `he cmd 0x<dni> 0x<ep> 6 1 {}` and `off()` the same with `0`; `onOffRefresh()` and `levelRefresh()` give `he rattr 0x<dni> 0x<ep> 6 0 {}` and `... 8 0 {}`; `refreshData("0x0201", "0x0012")` gives `he rattr 0x<dni> 0x<ep> 0x0201 0x0012 {}`; `electricMeasurementPowerRefresh()` gives `he rattr 0x<dni> 0x<ep> 0x0B04 0x050B` and `colorTemperatureRefresh()` `... 0x0300 0x0007 {}` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Configuration builders.** `onOffConfig(min, max)` is a bind (`... 0x01 6 {<zigbeeId>} {}`) and `he cr 0x<dni> 0x<ep> 6 0 16 <min> <max> {}`; its String form `onOffConfig("0x0000", 0, 600)` prints the String after `0x` in place of the endpoint (`0x0x0000`); `levelConfig()` binds cluster 8 and sets `0 32 1 3600`; `batteryConfig()` binds cluster 1 and sets `32 32 30 21600 {01}`; `temperatureConfig(min, max)` binds `0x0402` and sets `0x0402 0x0000 0x29 <min> <max> {6400}`, `temperatureConfig()` meaning `30, 3600`; `colorTemperatureConfig()` binds `0x0300` and sets `0x0300 0x0007 0x21 0x0001 0x0E10 {1000}` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`setLevel(level[, rate])`** gives `he cmd ... 0x0008 0x04 {<level> <rate>}`: the level scaled to 0–255 and cut off at both ends (`50` is `7F`, `100` and `101` are `FF`, `0` and `-1` are `00`; a BigDecimal is first made an Integer), the rate in tenths of a second as the two bytes of `swapOctets(convertToHexString(rate, 4))` (`10` is `0A00`, `70000` is `1711`) or `FFFF` without one. `setColorTemperature(kelvin)` sends `{<mireds little-endian> 1000}` with mireds `1000000 / kelvin` (`2700` is `7201`); `setHue(h)` and `setSaturation(s)` send `h * 2.54` and `s * 2.54` as one byte (`50` is `7F`, `100` is `FE`) with `1000`; `groupOn(g)` and `groupOff(g)` give `he grp cmd 0x<group4> 0x01 6 1 {}` and `... 6 0 {}` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`enrollResponse([delay[, map]])`** writes the hub's own Zigbee EUI into the device's CIE Address attribute, bytes reversed, and then answers the enroll request: `he wattr 0x<dni> 0x<ep> 0x0500 0x0010 0xF0 {<EUI reversed>}`, then `he raw 0x<dni> 1 0x<ep> 0x0500 {01 23 00 00 00}`, each followed by `delay <delay>` (default 2000). `[useZigbee3Ack: true]` in the map swaps the second command for `he cmd 0x<dni> 0x<ep> 0x0500 0x00 {00 00}`. The helper adds `useZigbee3Ack: false` to the Map the caller passed when the key is missing. With the hub's Zigbee radio off the call throws `NullPointerException`, there being no EUI to write *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`updateFirmware()`** gives one command and no delay line, `he raw 0x<dni> 0x01 0x<ep> 0x0019 {09 01 00 03 64 FFFF FFFF FFFF FFFF}`; a Map argument changes nothing *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Utilities.** `convertToHexString(v, width)` is `String.format("%0<width>X", v)`: `(10, 4)` is `000A`, `(256, 2)` is `100`, `(-1, 2)` is `FFFFFFFF`, `(0, 0)` is `0`. `convertHexToInt` is `Integer.parseInt(s, 16)`, so `"0x1F"`, `""` and `"FFFFFFFF"` throw `NumberFormatException` and `"-1"` is `-1`. `swapOctets` takes the first four characters (`"123456"` is `3412`) and throws on a shorter string. `hex2String("48656C6C6F")` is `Hello`, one character per byte (`C3A9` gives two Latin-1 characters). `clusterLookup` takes an Integer or a hex String with or without `0x` and returns the `ZigbeeCluster` constant, or null for an id the enum lacks (`0xFF01`); its `clusterLabel` is the name the hub's pages show (`On/Off`, `Thermostat`, `Electrical Measurement`, `Humidity Measurement` for `0x0405`, `Over-the-Air Upgrade` for `0x0019`, `Analog Input (Basic)`) *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **Cluster constants.** Every constant in the table below answers except `BIND_MANAGEMENT_CLUSTER` and `SIMPLE_METERING_CLUSTER`, which are `MissingPropertyException` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`DataType.getLength(id)`** answers for 56 of the 256 ids: the fixed widths `0x08`–`0x0F` as 1–8 bytes, `0x10` 1, `0x18`–`0x1F` 1–8, `0x20`–`0x27` 1–8, `0x28`–`0x2F` 1–8, `0x30` 1, `0x31` 2, `0x38` 2, `0x39` 4, `0x3A` 1, `0xE0`, `0xE1`, `0xE2` and `0xEA` 4, `0xE8` and `0xE9` 2, `0xF0` 8 and `0xF1` 16; `0` for `0x00` and `0xFF`; `-1` for the variable-length `0x41`–`0x44`, `0x48`, `0x4C`, `0x50` and `0x51`; and null for every other id *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.

### `parseDescriptionAsMap` in detail

- **Only two prefixes.** The description must start with `catchall: ` or `read attr - `, with the space; `Catchall:`, a leading space, `zone status ...`, `on/off: 1`, `enroll request ...` and the empty string give an empty map *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`catchall:`** splits the rest on spaces (several spaces are one) into `profileId`, `clusterId`, `sourceEndpoint`, `destinationEndpoint`, `options`, `messageType`, `dni`, `isClusterSpecific` (true when the next field is `01`), `isManufacturerSpecific` (true when the one after is `01`), `manufacturerId`, `command`, `direction` and, when present, `data`, the list of its two-digit chunks (a last odd digit stays one digit); the map starts with `raw` (the whole text) and has `clusterInt` (Integer) after `clusterId`. Fewer than twelve fields throw `ArrayIndexOutOfBoundsException`, a `clusterId` that is not hex throws `NumberFormatException` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`read attr - `** splits the rest on `,`, each piece on `:`, and trims both sides, so `value: ` is an empty value, `value:` or a piece with no colon throws `ArrayIndexOutOfBoundsException`, a second `:` is dropped (`a: b: c` is `a: b`) and a repeated key keeps its first place and its last value. The map is the pieces in order, then `clusterInt` and `attrInt` (Integers; a missing or non-hex `cluster` or `attrId` throws `NumberFormatException`), then `additionalAttrs` when there is one. A missing `encoding` throws `NullPointerException: Value to convert cannot be null.` and a missing `value` a `NullPointerException` on `substring()` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **The `value` field is decoded by `encoding`.** The hub reverses the bytes of a multi-byte number (`value: 9808` for an INT16 becomes `0898`): the types `0x19`–`0x1F`, `0x21`–`0x27`, `0x29`–`0x2F`, `0x31`, `0x38` and `0x39`; single bytes, the data types `0x08`–`0x0F`, `0xE0`–`0xEA`, `0xF0` and `0xF1` stay as written. Only the first `getLength` bytes are kept (`980800` is `0898`) and a value shorter than that throws `StringIndexOutOfBoundsException`. The strings `0x41` and `0x42` take a one-byte length prefix and give the octets as hex (`0x41`) or as text, one Latin-1 character per byte (`0x42`: `0A6C756D692E73656E736F` is `lumi.senso`); `0x43` and `0x44` take a two-byte little-endian length. A declared length one byte longer than the text is tolerated for `0x41` and `0x42` (`0D` before twelve bytes gives all twelve) and two or more throw `StringIndexOutOfBoundsException`; `0x43` and `0x44` tolerate none. An array (`0x48`) stays as written, `0x4C`, `0x50` and `0x51` throw, and an id `getLength` answers null for (`0x01`, `0x32`, `0xFE`, ...) leaves the value as written *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`additionalAttrs`.** After the first value, the `value` text may carry more attribute records, `<attrId little-endian> <type> <value>` with no separators (`98080100210A00` is the INT16 `0898` and then attribute `0001`, type `21`, value `000A`). The list exists when the text runs more than six characters past the first value, and a record is read while at least as many characters remain as the first value ended at, minus one (a first value of two bytes lets a record start with four characters left, one of five bytes needs nine, a ten-byte string needs twenty-one). Each entry is a Map with `value`, `encoding`, `attrId`, `consumedBytes` (attribute, type and value; a string's length byte is left out) and `attrInt`, the value decoded as above; an unknown type throws `NullPointerException: Cannot get property 'typeInt' on null object`. With `command: 01` (Read Attributes Response) a record is the attribute and a status byte: `00` is followed by type and value, any other status makes the entry `[status: "86", attrId:, attrInt:]`. The Aqara door sensor's press frame, a model string followed by the `FF01` record, arrives this way: the `FF01` entry's value is the check-in with its length byte already removed *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`getEvent`.** For `read attr` descriptions it knows On/Off (`switch`), Level (`0008` attribute `0000`: `value * 100 / 255` rounded, `FE` giving 99, `FF` 100), Temperature Measurement (`0402`: the reversed value as a signed 16-bit number, divided by 100 and rounded half up to an Integer, `unit` `C`, `descriptionText` `<device> was <value>C`, so `value: 9808` is 22 and `0898` is -266) and Power Configuration (`0001` attribute `0021` as `value / 2` and `0020` as `value / 10`, BigDecimals, `unknown` for `FF`); everything else, humidity included, returns an empty Map *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`getKnownDescription`** answers an On/Off `read attr` (`01` is `on`, anything else `off`) and an On/Off `catchall` with command `0B`, from the first data byte, as `[name: "switch", value:, type: "switch"]` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.
- **`parseZoneStatus`** reads the first token after `zone status ` as hex with or without `0x` (`zone status 21` is `0x21`) and returns null for any other text. `parseZoneStatusChange` returns the Map described above with `extendedStatus`, `zoneId` and `delay` after the ten Booleans; the short form `zone status 0x0021` throws `ArrayIndexOutOfBoundsException` *(probed 2.5.2.135, C-8 Pro, throwaway driver)*.

## Cluster ID constants

Available as integer constants on the `zigbee` object itself (e.g. `zigbee.ON_OFF_CLUSTER` returns `6`) *(probed 2.5.0.148, reflection dump)*. Hubitat doesn't document them *(absent 2.5.2.134, docs2 Zigbee Object page and the on-hub API reference list only the ZigbeeCluster enum)*; SmartThings only mentioned a few in code examples *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings))*. Full list from reflection — note the inconsistent naming on `SOIL_MOISTURE_MEASUREMENT` (no `_CLUSTER` suffix) and `TEMPERATURE_CONFIGURATION_CLUSTER` (id `2`, which is actually Device Temperature Configuration per the ZCL — easy to mistake for Temperature Measurement at `1026`) *(probed 2.5.0.148, reflection dump; spec: ZCL (07-5123 rev 8), 3.4)*.

| Constant | ID (dec / hex) |
|---|---|
| `BASIC_CLUSTER` | 0 / 0x0000 |
| `POWER_CONFIGURATION_CLUSTER` | 1 / 0x0001 |
| `TEMPERATURE_CONFIGURATION_CLUSTER` | 2 / 0x0002 (= Device Temperature Configuration; NOT Temperature Measurement) |
| `IDENTIFY_CLUSTER` | 3 / 0x0003 |
| `GROUPS_CLUSTER` | 4 / 0x0004 |
| `SCENES_CLUSTER` | 5 / 0x0005 |
| `ON_OFF_CLUSTER` | 6 / 0x0006 |
| `ON_OFF_SWITCH_CONFIGURATION_CLUSTER` | 7 / 0x0007 |
| `LEVEL_CONTROL_CLUSTER` | 8 / 0x0008 |
| `ALARMS_CLUSTER` | 9 / 0x0009 |
| `TIME_CLUSTER` | 10 / 0x000A |
| `RSSI_LOCATION_CLUSTER` | 11 / 0x000B |
| `ANALOG_INPUT_CLUSTER` … `MULTISTATE_VALUE_CLUSTER` | 12–20 / 0x000C–0x0014 |
| `OTA_CLUSTER` | 25 / 0x0019 |
| `POWER_PROFILE_CLUSTER` | 26 / 0x001A |
| `POLL_CONTROL_CLUSTER` | 32 / 0x0020 |
| `SHADE_CONFIGURATION_CLUSTER` | 256 / 0x0100 |
| `DOOR_LOCK_CLUSTER` | 257 / 0x0101 |
| `WINDOW_COVERING_CLUSTER` | 258 / 0x0102 |
| `PUMP_CONFIGURATION_CONTROL_CLUSTER` | 512 / 0x0200 |
| `THERMOSTAT_CLUSTER` | 513 / 0x0201 |
| `FAN_CONTROL_CLUSTER` | 514 / 0x0202 |
| `DEHUMIDIFICATION_CONTROL_CLUSTER` | 515 / 0x0203 |
| `THERMOSTAT_USER_INTERFACE_CONFIGURATION_CLUSTER` | 516 / 0x0204 |
| `COLOR_CONTROL_CLUSTER` | 768 / 0x0300 |
| `BALLAST_CONFIGURATION_CLUSTER` | 769 / 0x0301 |
| `ILLUMINANCE_MEASUREMENT_CLUSTER` | 1024 / 0x0400 |
| `ILLUMINANCE_LEVEL_SENSING_CLUSTER` | 1025 / 0x0401 |
| `TEMPERATURE_MEASUREMENT_CLUSTER` | 1026 / 0x0402 |
| `PRESSURE_MEASUREMENT_CLUSTER` | 1027 / 0x0403 |
| `FLOW_MEASUREMENT_CLUSTER` | 1028 / 0x0404 |
| `RELATIVE_HUMIDITY_MEASUREMENT_CLUSTER` | 1029 / 0x0405 |
| `OCCUPANCY_SENSING_CLUSTER` | 1030 / 0x0406 |
| `SOIL_MOISTURE_MEASUREMENT` | 1032 / 0x0408 |
| `IAS_ZONE_CLUSTER` | 1280 / 0x0500 |
| `IAS_ACE_CLUSTER` | 1281 / 0x0501 |
| `IAS_WD_CLUSTER` | 1282 / 0x0502 |
| `PRICE_CLUSTER` | 1792 / 0x0700 |
| `DEMAND_RESPONSE_CLUSTER` | 1793 / 0x0701 |
| `METERING_CLUSTER` | 1794 / 0x0702 |
| `MESSAGING_CLUSTER` | 1795 / 0x0703 |
| `TUNNELING_CLUSTER` | 1796 / 0x0704 |
| `KEY_ESTABLISHMENT_CLUSTER` | 2048 / 0x0800 |
| `METER_IDENTIFICATION_CLUSTER` | 2817 / 0x0B01 |
| `ELECTRICAL_MEASUREMENT_CLUSTER` | 2820 / 0x0B04 |
| `DIAGNOSTICS_CLUSTER` | 2821 / 0x0B05 |

## `ZigbeeCluster` (the class the docs reference)

The Hubitat docs mention a `ZigbeeCluster` class *(docs: [Zigbee Object](https://docs2.hubitat.com/en/developer/zigbee-object))*. It's actually a **nested enum**: `com.hubitat.zigbee.Zigbee$ZigbeeCluster`. Returned by `zigbee.clusterLookup(int|String)` and the static `getCluster(Integer|String)` methods. Each enum value carries `clusterInt` (Integer), `clusterLabel` (String), and `clusterEnum` (String) accessors. The enum values mirror the integer constants above 1:1 *(probed 2.5.0.148, reflection dump; docs: on-hub api-com-hubitat-zigbee-zigbee-zigbeecluster, 2.5.2.134)*. Useful when you want a typed cluster reference instead of a bare int — but the bare-int constants are what every real driver uses *(unmarked)*.

## `ZoneStatus` (IAS Zone status bitfield)

FQN: `hubitat.zigbee.clusters.iaszone.ZoneStatus` *(probed 2.5.0.148, reflection dump)*. Wraps the 16-bit `ZoneStatus` attribute from cluster `0x0500` *(spec: ZCL (07-5123 rev 8), 8.2, ZoneStatus is map16)*. Returned by `zigbee.parseZoneStatus(String)` *(probed 2.5.0.148, reflection dump)*.

Each property has both a direct-bit getter `getXxx()` returning `0`/`1` and a boolean `isXxxSet()` query *(probed 2.5.0.148, reflection dump)*:

| Bit | Property | `isXxxSet()` true when |
|---|---|---|
| 0 | `alarm1` | opened / alarmed |
| 1 | `alarm2` | opened / alarmed |
| 2 | `tamper` | tampered |
| 3 | `battery` | low battery |
| 4 | `supervisionReports` | sensor reports supervision |
| 5 | `restoreReports` | sensor reports restore |
| 6 | `trouble` | trouble/failure |
| 7 | `ac` | AC/mains fault |
| 8 | `test` | in test mode |
| 9 | `batteryDefect` | defective battery detected |

Bit semantics preserved verbatim from the SmartThings docs *(community: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*.

## ZDO responses

ZDO management responses come back through `parse()` on the mirror-of-request cluster ids (request `0x0031` → response `0x8031`, request `0x0032` → response `0x8032`) *(spec: Zigbee Specification (05-3474-22), Tables 2-80 and 2-126; community: [Gary Milne's Zigbee_Monitor_Driver](https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy))*. A response, like any frame a node sends, reaches the `parse()` of whichever device record holds the node's short address as its DNI, not the record that sent the request. Moving a paired device's DNI to a placeholder and giving that address to a virtual device (no `controllerType`, no `zigbeeId`) sent the node's ZCL read responses, its unsolicited attribute reports and its `0x8031` response to the virtual device's `parse()`, and `/zigbeeLogsocket` tagged them with the virtual device's id. No reboot or radio restart was needed, either way. Restoring the DNI sent the frames back to the paired record, and its state, settings, data values and current attributes were unchanged. While the address is moved, the paired record's own driver misses the node's reports, and its outgoing commands go to the placeholder. Whether a rejoin or Device_annce moves the DNI back was not tested *(probed 2.5.2.139, C-7, one 3RSP019BZ plug, `/zigbeeLogsocket`)*. When the replies reach the target's own driver, they can change that driver's state. Across five full runs of ZDO and ZCL reads, the Generic Zigbee Outlet driver's `deviceState`, settings, data and DNI stayed unchanged *(probed 2.5.2.139, C-7, one 3RSP019BZ plug)*. A 3RSP02064Z plug's driver keeps a receive timestamp in `state.lastRx`: it did not change over 78 s with no reads (four reads 25 s apart) and moved forward during each run, so the replies updated it *(probed 2.5.2.139, C-7, one 3RSP02064Z plug)*. ZDO request cluster IDs overlap ZCL cluster IDs: `0x0006` is both Match_Desc_req (ZDP) and On/Off (ZCL), so route a frame whose source or destination endpoint is `0x00` to a ZDO parser by endpoint, not by cluster ID; ZDO responses use `0x80xx` *(spec: Zigbee Specification (05-3474-22), 2.4.3.1.7, Table 2-44, 2.5.3 and 2.3.1.3; spec: ZCL (07-5123 rev 8), 3.8)*. There's no `zigbee.*` decoder — walk the `data` byte list yourself *(absent 2.5.0.148, no ZDO method in the reflection dump)*. Byte-offset layouts below reviewed against Gary Milne's `Zigbee_Monitor_Driver` (`https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy`) *(community: [Gary Milne's Zigbee_Monitor_Driver](https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy))*.

### Mgmt_Lqi_rsp (`0x8031`) — 22 bytes per neighbor entry
<!-- evidence: spec: Zigbee Specification (05-3474-22), 2.4.4.4.2, Figure 2-98 and Table 2-130; community: [Gary Milne's Zigbee_Monitor_Driver](https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy) -->

The response starts with Status, NeighborTableEntries, StartIndex and NeighborTableListCount, one byte each, then the entries. Offsets below are within one entry. Bits are numbered from 0, least significant, as in the spec (1.2.3).

| Offset | Field | Notes |
|--------|-------|-------|
| 0–7   | Extended PAN ID | little-endian on wire (reverse to read) |
| 8–15  | EUI64 | reversed |
| 16–17 | nwkAddr | reversed |
| 18    | info octet | bits 0–1 deviceType (`0`=Coordinator, `1`=Router, `2`=End Device, `3`=Unknown), bits 2–3 rxOnWhenIdle, bits 4–6 relationship |
| 19    | permit joining | bits 0–1; usually skipped |
| 20    | depth | tree depth; usually skipped |
| 21    | LQI | raw 0–255, linear, no normalization |

### Mgmt_Rtg_rsp (`0x8032`) — 5 bytes per routing-table entry
<!-- evidence: spec: Zigbee Specification (05-3474-22), 2.4.4.4.3, Figure 2-99 and Table 2-132; community: [Gary Milne's Zigbee_Monitor_Driver](https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy) -->

Same four-byte header (Status, RoutingTableEntries, StartIndex, RoutingTableListCount), then the entries.

| Offset | Field | Notes |
|--------|-------|-------|
| 0–1 | dest addr | reversed |
| 2   | status octet | bits 0–2 route status: 0=Active, 1=Discovery Underway, 2=Discovery Failed, 3=Inactive, 4=Validation Underway; bit 3 memoryConstrained, bit 4 manyToOne, bit 5 routeRecordRequired |
| 3–4 | nextHop | reversed |

Many routers (including some smart plugs) reply NOT_SUPPORTED to `Mgmt_Rtg_req` — handle that case *(probed firmware not recorded, ZDO probes of ThirdReality plugs)*; the request is optional per spec, and a NOT_SUPPORTED reply omits every field after Status *(spec: Zigbee Specification (05-3474-22), Table 2-80 and 2.4.4.4.3)*.

### StartIndex pagination + dedup

`listCount` is the number of entries in this response, and the Entries field is the table's total *(spec: Zigbee Specification (05-3474-22), Tables 2-129 and 2-131)*. Re-issue the request with the next StartIndex (formatted `%02X`) and loop while `listCount != 0` *(community: [Gary Milne's Zigbee_Monitor_Driver](https://raw.githubusercontent.com/GaryMilne/Hubitat-Zigbee/main/Zigbee_Monitor_Driver.groovy))*; the walk is complete once StartIndex reaches the total *(spec: Zigbee Specification (05-3474-22), Tables 2-129 and 2-131)*. Dedup across pages with a composite key (e.g. `"${lqi}-${id}"`): pages overlap when the table mutates mid-walk, and you'll double-count without it *(inferred: a page is read at a different moment from the one before it)*.

## `DataType`

The ST docs documented a `physicalgraph.zigbee.zcl.DataType` class with the full ZCL type-code table and `getLength` / `isVariableLength` / `isDiscrete` / `pack` helpers *(spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*. Hubitat has `hubitat.zigbee.zcl.DataType`: in a driver or app the bare name `DataType` holds it with no import ([platform notes](hubitat-platform-notes.md#sandbox-and-groovy)) *(probed 2.5.2.135, throwaway app and driver)*. The on-hub reference documents it with `getLength`, `isDiscrete`, `pack` and the type constants (`UINT8`, `INT16`, `ENUM8`, `STRING_CHAR`, …), and a deprecated `com.hubitat.zigbee.DataType` *(docs: on-hub api-hubitat-zigbee-zcl-datatype, 2.5.2.134; docs: on-hub api-com-hubitat-zigbee-datatype-compat, 2.5.2.134)*.

The ZCL type codes behind the constants: `0x10` BOOLEAN, `0x18` BITMAP8, `0x20` UINT8, `0x21` UINT16, `0x23` UINT32, `0x29` INT16, `0x30` ENUM8, `0x42` STRING_CHAR *(spec: ZCL (07-5123 rev 8), 2.6.2, Table 2-11)*.

## Conceptual notes worth keeping (from the ST primer)

- **Four main message types**: `command`, `readAttribute`, `writeAttribute`, `configureReporting` *(spec: [SmartThings zigbee-primer](https://web.archive.org/web/20201008180753/https://docs.smartthings.com/en/latest/device-type-developers-guide/zigbee-primer.html))*. Everything outside these (the old `raw …` family) is deprecated *(spec: [SmartThings building-zigbee-DHs](https://web.archive.org/web/20200921015021/https://docs.smartthings.com/en/latest/device-type-developers-guide/building-zigbee-device-handlers.html))*.
- **Endpoints** work like ports: different endpoints can support different clusters, and a device can have several *(spec: [SmartThings zigbee-primer](https://web.archive.org/web/20201008180753/https://docs.smartthings.com/en/latest/device-type-developers-guide/zigbee-primer.html))*.
- **Cluster taxonomy**: ZCL-defined / profile-specific (HA, ZSE) / manufacturer-specific *(spec: [SmartThings zigbee-primer](https://web.archive.org/web/20201008180753/https://docs.smartthings.com/en/latest/device-type-developers-guide/zigbee-primer.html))*. The cluster ID alone doesn't tell you which *(unmarked)*.
- **`zbjoin` schema** (from the primer, never re-documented by Hubitat): the `simple` string is space-separated `Endpoint, ProfileID, DeviceID, DeviceVersion, NumInClusters, [InClusters…], NumOutClusters, [OutClusters…]`. `application` = Basic cluster attribute `0x0001`; `manufacturer` = `0x0004`; `model` = `0x0005` *(spec: [SmartThings zigbee-primer](https://web.archive.org/web/20201008180753/https://docs.smartthings.com/en/latest/device-type-developers-guide/zigbee-primer.html); absent 2.5.2.134, no `zbjoin` in a docs2 search or the on-hub API reference)*.
- **Best practice the ST docs flag explicitly**: *"Do not use `sendEvent()` in command methods. Sending Events should be handled in the `parse` method."* *(spec: [SmartThings building-zigbee-DHs](https://web.archive.org/web/20200921015021/https://docs.smartthings.com/en/latest/device-type-developers-guide/building-zigbee-device-handlers.html))* Hubitat inherited this convention *(unmarked)*.

## What Hubitat added beyond SmartThings

Reachable now, not in the ST docs: the group commands, full color control (`setColor(Map)`, `setColorXY(Map)`, `setHue`, `setSaturation`), `reportingConfiguration`, `updateFirmware`, `clusterLookup` and the `ZigbeeCluster` enum, multi-attribute `readAttribute(cluster, List)`, `parseZoneStatusChange`, the trailing `int delay` overloads, `temperatureConfig(min, max)`, `String`-value overloads on `writeAttribute`, and `swapOctets` (a two-byte swap; ST code used `swapEndianHex`, see [Utility](#utility)) *(probed 2.5.0.148, reflection dump; probed 2.5.2.135, C-8 Pro, throwaway driver; spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html) lists none of them)*. ST's note *"Only color temperature can be parsed. Full color control is in the works."* was about `getEvent` parsing, not the color commands *(spec: [SmartThings zigbee-ref](https://web.archive.org/web/20200510195834/https://docs.smartthings.com/en/latest/ref-docs/zigbee-ref.html))*.

## What ST had that Hubitat dropped (or hid)

- `convertToInt(String value, int radix)` — Hubitat has only `convertHexToInt(String)` (always base 16) *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings); absent 2.5.0.148, not in the reflection dump)*
- `addBinding(...)` *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings); absent 2.5.0.148, not in the reflection dump)* There is no `BIND_MANAGEMENT_CLUSTER` constant: reading `zigbee.BIND_MANAGEMENT_CLUSTER` throws MissingPropertyException *(regression 2.5.2.135–2.5.3.102, apps/tests/test-architecture-claims.sh)*. Bind_req is ZDO cluster `0x0021` *(spec: Zigbee Specification (05-3474-22), 2.4.3.2.2)*, so `zigbee.command()` can't build it; drivers send a `zdo bind` command string instead *(inferred: from the `zigbee.command(0x0031, …)` output above and drivers/XfinityContactSensor.groovy)*
- `simpleMeteringPowerConfig()` / `simpleMeteringPowerRefresh()` / `electricMeasurementPowerConfig()` / `hueSaturationRefresh()` *(community: [SmartThingsPublic DTHs](https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/smartthings); absent 2.5.0.148, not in the reflection dump)*

## Related classes

- **`hubitat.zigbee.SmartShield`** — what `zigbee.parse(String)` returns. Raw ZCL frame: `clusterId`, `command`, `profileId`, `sourceEndpoint`, `destinationEndpoint`, `data` (List), `manufacturerId`, `direction`, `messageType`, `options`, `isClusterSpecific`, `isManufacturerSpecific`. Constructed by the platform from a `catchall` description *(probed 2.5.0.148, reflection dump; docs: on-hub api-hubitat-zigbee-smartshield, 2.5.2.134)*.
- **`com.hubitat.app.DeviceWrapper`** — what `zigbee.getDevice()` returns. Same wrapper drivers see as `device`. Of note: `getEndpointId()`, `getZigbeeId()`, `getDriverId()`, `eventsSince(Date)`, `statesSince(String, Date)` *(probed 2.5.0.148, reflection dump)*.
- **`com.hubitat.hub.domain.Hub`** — see [Location, hub and device objects](hubitat-platform-notes.md#location-hub-and-device-objects) *(probed 2.5.0.148, reflection dump)*.
- **`com.hubitat.hub.domain.State`** — what `currentState(attr)` / `latestState(attr)` return (`eventsSince(...)` and `statesSince(...)` return a `List`): `getStringValue`/`getNumberValue`/`getDate` accessors etc. *(probed 2.5.0.148, reflection dump)*
