/**
 *  Zigbee Responder — hub-wide responder for Zigbee cluster reads Hubitat does not
 *  forward to driver parse() (currently Time cluster 0x000A; OTA 0x0019 stub for later).
 *
 *  One virtual-device instance opens a single WebSocket to the hub's /zigbeeLogsocket,
 *  filters inbound frames by cluster + opt-in deviceId, and emits a ZCL response
 *  on behalf of the originating device using `he raw 0x${srcDni}`. This replaces the
 *  per-driver WebSocket pattern that doesn't scale to many devices.
 *
 *  Design reference: kkossev Aqara P1 Motion Sensor v2.1.5 (websocket + payload[1]
 *  seq echo). The Time cluster response byte layout matches that driver's; this one
 *  factors out the cluster routing and per-device addressing into a single instance.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 *  this file except in compliance with the License. You may obtain a copy of the
 *  License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Version  Date          Who   What
 *  0.1.0    2026-06-09    PJ    Initial — Time cluster (0x000A) responder via /zigbeeLogsocket.
 *  0.2.0    2026-06-09    PJ    OTA (0x0019) Query Next Image responder — NO_IMAGE_AVAILABLE replies to quiet retry chatter.
 *  0.2.1    2026-06-09    PJ    Drop per-cluster counter and last-request attributes; only wsStatus remains.
 */

import groovy.transform.Field

static String version()   { '0.2.1' }
static String timeStamp() { '2026/06/09' }

metadata {
    definition(name: 'Zigbee Responder', namespace: 'iamtrep', author: 'PJ',
               importUrl: 'https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/ZigbeeResponder/ZigbeeResponder.groovy',
               singleThreaded: true) {
        capability 'Initialize'
        capability 'Configuration'
        capability 'Refresh'

        attribute 'wsStatus', 'enum', ['disconnected', 'connecting', 'connected', 'failed']

        command 'reconnect', [[name: 'Force a WebSocket reconnect']]
    }

    preferences {
        input name: 'logEnable',  type: 'bool', title: '<b>Enable debug logging</b>',
              description: 'Auto-disables after 30 minutes.', defaultValue: true
        input name: 'txtEnable',  type: 'bool', title: '<b>Enable info logging</b>',
              defaultValue: true

        input name: 'enableTimeResponder', type: 'bool',
              title: '<b>Enable Time cluster (0x000A) responder</b>',
              description: 'When on, responds to Read Attributes on the Time cluster from devices listed below.',
              defaultValue: true

        input name: 'timeResponderDeviceIds', type: 'string',
              title: '<b>Allowed device IDs (Time)</b>',
              description: 'Comma-separated hub device IDs to respond on behalf of. Empty = respond to no one (safe default). Use the integer id from the device page URL.',
              defaultValue: ''

        input name: 'enableOtaResponder', type: 'bool',
              title: '<b>Enable OTA (0x0019) Query-Next-Image responder</b>',
              description: 'When on, replies NO_IMAGE_AVAILABLE (status 0x98) to OTA Query Next Image requests from devices listed below. Quiets the per-second retry chatter from devices that have no Hubitat-side OTA image.',
              defaultValue: false

        input name: 'otaResponderDeviceIds', type: 'string',
              title: '<b>Allowed device IDs (OTA)</b>',
              description: 'Comma-separated hub device IDs. Empty = respond to no one.',
              defaultValue: ''
    }
}

// ══════════════════════════════════════════════════════════════════════════
// LIFECYCLE
// ══════════════════════════════════════════════════════════════════════════

void installed() {
    log.info "${device.displayName} installed v${version()}"
    state.clear()
    initialize()
}

void updated() {
    log.info "${device.displayName} updated"
    if (settings?.logEnable) runIn(1800, 'logsOff', [overwrite: true])
    else                     unschedule('logsOff')
    // Rebuild parsed allowlists so the hot path doesn't re-parse settings each frame
    state.timeAllowedIds = parseAllowedIds(settings?.timeResponderDeviceIds)
    state.otaAllowedIds  = parseAllowedIds(settings?.otaResponderDeviceIds)
    logInfo "Time responder: ${settings?.enableTimeResponder ? 'ENABLED' : 'disabled'}, " +
            "allowedIds=${state.timeAllowedIds}"
    logInfo "OTA responder:  ${settings?.enableOtaResponder ? 'ENABLED' : 'disabled'}, " +
            "allowedIds=${state.otaAllowedIds}"
    runIn(2, 'connectZigbeeLogSocket', [overwrite: true])
}

void initialize() {
    log.info "${device.displayName} initialize v${version()}"
    state.timeAllowedIds = parseAllowedIds(settings?.timeResponderDeviceIds)
    state.otaAllowedIds  = parseAllowedIds(settings?.otaResponderDeviceIds)
    runIn(2, 'connectZigbeeLogSocket', [overwrite: true])
}

void uninstalled() {
    log.info "${device.displayName} uninstalled"
    disconnectZigbeeLogSocket()
}

void configure() {
    log.info "${device.displayName} configure"
    initialize()
}

void refresh() {
    logInfo "ws=${state.wsConnected}, " +
            "time=${settings?.enableTimeResponder ? 'on' : 'off'} allowed=${state.timeAllowedIds}, " +
            "ota=${settings?.enableOtaResponder ? 'on' : 'off'} allowed=${state.otaAllowedIds}"
}

void reconnect() {
    logInfo 'reconnect requested'
    disconnectZigbeeLogSocket()
    runIn(2, 'connectZigbeeLogSocket', [overwrite: true])
}

// ══════════════════════════════════════════════════════════════════════════
// WEBSOCKET LIFECYCLE
// ══════════════════════════════════════════════════════════════════════════

void connectZigbeeLogSocket() {
    try { unschedule('connectZigbeeLogSocket') } catch (ignored) { }
    state.wsReconnectPending = false
    state.wsIntentionalClose = false
    state.wsConnected = false
    sendEvent(name: 'wsStatus', value: 'connecting')
    logDebug 'connecting to ws://127.0.0.1:8080/zigbeeLogsocket'
    try {
        interfaces.webSocket.connect('ws://127.0.0.1:8080/zigbeeLogsocket')
        logInfo 'zigbeeLogsocket connect initiated'
    } catch (Exception e) {
        state.wsConnected = false
        sendEvent(name: 'wsStatus', value: 'failed')
        logWarn "connect failed: ${e.message}"
        scheduleZigbeeLogSocketReconnect()
    }
}

void disconnectZigbeeLogSocket() {
    try { unschedule('connectZigbeeLogSocket') } catch (ignored) { }
    state.wsReconnectPending = false
    state.wsIntentionalClose = true
    state.wsConnected = false
    sendEvent(name: 'wsStatus', value: 'disconnected')
    try {
        interfaces.webSocket.close()
        logDebug 'close requested'
    } catch (Exception e) {
        logDebug "close exception: ${e.message}"
    }
}

private void scheduleZigbeeLogSocketReconnect() {
    if (state.wsIntentionalClose == true) return
    if (state.wsReconnectPending == true) return
    state.wsReconnectPending = true
    logWarn 'reconnect in 10s'
    runIn(10, 'connectZigbeeLogSocket', [overwrite: true])
}

void webSocketStatus(String status) {
    String normalized = status?.trim()?.toLowerCase()
    logDebug "webSocketStatus: ${status}"
    if (normalized in ['open', 'status: open']) {
        state.wsConnected = true
        state.wsReconnectPending = false
        state.wsIntentionalClose = false
        sendEvent(name: 'wsStatus', value: 'connected')
        logInfo 'zigbeeLogsocket connected'
        try { unschedule('connectZigbeeLogSocket') } catch (ignored) { }
        return
    }
    if (normalized in ['closing', 'status: closing']) return

    boolean isClosed  = normalized in ['closed', 'status: closed']
    boolean isFailure = normalized?.startsWith('failure')
    if (isClosed || isFailure) {
        state.wsConnected = false
        sendEvent(name: 'wsStatus', value: 'disconnected')
        if (state.wsIntentionalClose == true) {
            logDebug 'closed intentionally'
            return
        }
        if (isFailure) logWarn "ws failure: ${status}"
        else           logWarn 'ws closed unexpectedly'
        scheduleZigbeeLogSocketReconnect()
    } else {
        logDebug "webSocketStatus: unhandled '${status}'"
    }
}

// ══════════════════════════════════════════════════════════════════════════
// FRAME INGEST & ROUTING
// ══════════════════════════════════════════════════════════════════════════

void parse(String description) {
    // The only thing reaching parse() on this virtual device is the WebSocket
    // text frame (JSON). Anything else is a hub anomaly and gets dropped.
    String trimmed = description?.trim()
    if (!trimmed?.startsWith('{')) {
        logDebug "parse: ignored non-JSON: ${trimmed?.take(80)}"
        return
    }

    Map entry
    try {
        entry = parseJson(trimmed)
    } catch (Exception e) {
        logDebug "parse: JSON error: ${e.message}"
        return
    }

    // Common guards before per-cluster dispatch
    if (entry?.type?.toString() != 'zigbeeRx') return
    if (!entry?.profileId?.toString()?.equalsIgnoreCase('0104')) return
    if (!(entry?.payload instanceof List)) return
    if ((entry.payload as List).size() < 3) return

    String cluster = entry?.clusterId?.toString()?.toUpperCase()
    switch (cluster) {
        case '000A':
            processTimeReadRequest(entry)
            return
        case '0019':
            processOtaQueryNextImageRequest(entry)
            return
        default:
            return
    }
}

// ══════════════════════════════════════════════════════════════════════════
// CLUSTER 0x000A — Time
// ══════════════════════════════════════════════════════════════════════════

private void processTimeReadRequest(Map entry) {
    if (settings?.enableTimeResponder == false) return

    // Opt-in by hub device id. Empty list = respond to nobody (safe default).
    List<String> allowedIds = (state.timeAllowedIds ?: []) as List<String>
    String entryDeviceId = entry?.deviceId?.toString()
    if (!entryDeviceId) return
    if (!allowedIds.contains(entryDeviceId)) {
        logDebug "Time read from non-allowed device ${entryDeviceId} ignored"
        return
    }

    List<String> rawPayload = (entry.payload as List).collect {
        it.toString().trim().toUpperCase().padLeft(2, '0')
    }

    // Frame control + command check. 0x10 = profile-wide client-to-server DDR;
    // some devices may use 0x00 (DDR clear) — accept both. 0x00 = Read Attributes.
    String fc = rawPayload[0]
    if (fc != '10' && fc != '00') {
        logDebug "Time: unexpected FC 0x${fc} from device ${entryDeviceId} - ignoring"
        return
    }
    if (rawPayload[2] != '00') {
        logDebug "Time: unexpected cmd 0x${rawPayload[2]} from device ${entryDeviceId} - ignoring"
        return
    }

    // ZCL transaction sequence — echoed verbatim. The JSON `sequence` field is the
    // websocket log counter, NOT the ZCL seq.
    String zclSeq = rawPayload[1]

    // Dedup: the WS can re-deliver the same frame within a few ms. 2s window keyed
    // by deviceId+seq+payload covers it without rejecting genuine new reads.
    String sig = "${entryDeviceId}|${zclSeq}|${rawPayload.join(',')}"
    Map last = (state.lastRequestByDevice ?: [:]) as Map
    long nowMs = now()
    Map prev = last[entryDeviceId] as Map
    if (prev?.sig == sig && (nowMs - ((prev?.ts ?: 0L) as long)) < 2000L) {
        logDebug "Time: duplicate (dev=${entryDeviceId}, seq=0x${zclSeq}) suppressed"
        return
    }
    last[entryDeviceId] = [sig: sig, ts: nowMs]
    state.lastRequestByDevice = last

    // Parse requested attribute IDs (LE 16-bit pairs starting at byte 3)
    List<String> attrBytes = rawPayload.drop(3)
    if (attrBytes.size() % 2 != 0) {
        logDebug "Time: odd attribute byte count ${attrBytes.size()} - ignoring"
        return
    }
    List<Integer> requestedAttrs = []
    for (int i = 0; i < attrBytes.size(); i += 2) {
        requestedAttrs.add(Integer.parseInt(attrBytes[i + 1] + attrBytes[i], 16))
    }

    // Endpoints from the request — response reverses them
    Integer srcEp = Integer.parseInt(entry.sourceEndpoint?.toString() ?: '01', 16)
    Integer dstEp = Integer.parseInt(entry.destinationEndpoint?.toString() ?: '01', 16)

    // Source DNI — entry.id is the integer 16-bit network address
    Integer srcDniInt = (entry.id as Number)?.intValue()
    if (srcDniInt == null) {
        logWarn "Time: missing source DNI in frame from device ${entryDeviceId}"
        return
    }
    String srcDniHex = String.format('%04X', srcDniInt & 0xFFFF)

    def attrHexList = requestedAttrs.collect { String.format('0x%04X', it) }
    String devLabel = entry?.name?.toString() ?: "id=${entryDeviceId}"
    logInfo "<b>Time read</b> from ${devLabel} (dni=0x${srcDniHex}, seq=0x${zclSeq}, attrs=${attrHexList})"

    sendTimeClusterResponse(srcDniHex, zclSeq, requestedAttrs, srcEp, dstEp)
}

// Build the response payload and send `he raw` to the requesting device's DNI.
// Response is attribute-aware: only the four most likely attrs return success;
// anything else returns UNSUP_ATTRIBUTE (0x86) so the device sees a well-formed reply.
private void sendTimeClusterResponse(String srcDniHex, String zclSeq, List<Integer> requestedAttrs,
                                     Integer srcEp, Integer dstEp) {
    final long ZIGBEE_EPOCH_OFFSET = 946684800L
    long utcSec     = (now() / 1000L).toLong() - ZIGBEE_EPOCH_OFFSET
    int  tzOffsetSec = location.timeZone.rawOffset.intdiv(1000)
    int  dstSec     = location.timeZone.inDaylightTime(new Date())
                          ? location.timeZone.getDSTSavings().intdiv(1000) : 0
    long localSec    = utcSec + tzOffsetSec + dstSec        // attr 0x0007 LocalTime
    long standardSec = utcSec + tzOffsetSec                 // attr 0x0006 StandardTime

    String utcHex      = toLEHex32(utcSec)
    String tzHex       = toLEHex32(tzOffsetSec)
    String dstHex      = toLEHex32(dstSec)
    String localHex    = toLEHex32(localSec)
    String standardHex = toLEHex32(standardSec)

    // Response endpoints reverse the request's
    int responseSrcEp = dstEp
    int responseDstEp = srcEp

    // 0x18 = profile-wide | server-to-client | DDR; cmd 0x01 = Read Attributes Response
    StringBuilder sb = new StringBuilder("18 ${zclSeq} 01")
    requestedAttrs.each { Integer attrId ->
        switch (attrId) {
            case 0x0000:                                        // Time (UTCTime)
                sb.append(" 00 00 00 E2 ${utcHex}"); break
            case 0x0002:                                        // TimeZone
                sb.append(" 02 00 00 2B ${tzHex}"); break
            case 0x0005:                                        // DstShift
                sb.append(" 05 00 00 2B ${dstHex}"); break
            case 0x0006:                                        // StandardTime
                sb.append(" 06 00 00 23 ${standardHex}"); break
            case 0x0007:                                        // LocalTime
                sb.append(" 07 00 00 23 ${localHex}"); break
            default:                                            // status 0x86 UNSUP_ATTRIBUTE
                String le = String.format('%02X %02X', attrId & 0xFF, (attrId >> 8) & 0xFF)
                sb.append(" ${le} 86"); break
        }
    }

    String payload = sb.toString()
    List<String> cmds = ["he raw 0x${srcDniHex} ${responseSrcEp} ${responseDstEp} 0x000A {${payload}} {0x0104}"]
    logInfo "Time response → 0x${srcDniHex}: seq=0x${zclSeq}, UTC=${utcSec}, TZ=${tzOffsetSec}s, DST=${dstSec}s"
    logDebug "Time response payload: ${payload}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// ══════════════════════════════════════════════════════════════════════════
// CLUSTER 0x0019 — OTA Upgrade (Query Next Image only)
// ══════════════════════════════════════════════════════════════════════════
// Devices with no matching Hubitat-side OTA image will retry Query Next Image
// every few seconds indefinitely (proven on ThirdReality plugs + vibration
// sensors here). Replying NO_IMAGE_AVAILABLE (status 0x98) is the polite
// "stop asking" and pushes the device's next attempt out to its long-poll
// interval (typically 24h). We do NOT implement Image Block (0x03) or
// Upgrade End (0x06) — those only fire after a SUCCESS response, which we
// never send.

private void processOtaQueryNextImageRequest(Map entry) {
    if (settings?.enableOtaResponder == false) return

    List<String> allowedIds = (state.otaAllowedIds ?: []) as List<String>
    String entryDeviceId = entry?.deviceId?.toString()
    if (!entryDeviceId) return
    if (!allowedIds.contains(entryDeviceId)) {
        logDebug "OTA from non-allowed device ${entryDeviceId} ignored"
        return
    }

    List<String> rawPayload = (entry.payload as List).collect {
        it.toString().trim().toUpperCase().padLeft(2, '0')
    }
    if (rawPayload.size() < 12) {
        logDebug "OTA: short payload (${rawPayload.size()}) from device ${entryDeviceId} - ignoring"
        return
    }

    // FC bit 0 must be 1 (cluster-specific), bit 3 must be 0 (client→server). DDR bit is don't-care.
    int fcInt
    try { fcInt = Integer.parseInt(rawPayload[0], 16) }
    catch (e) { logDebug "OTA: FC parse error: ${e.message}"; return }
    if ((fcInt & 0x09) != 0x01) {
        logDebug "OTA: unexpected FC 0x${rawPayload[0]} from device ${entryDeviceId} - ignoring"
        return
    }
    String zclSeq = rawPayload[1]
    if (rawPayload[2] != '01') {
        // Only Query Next Image Request (0x01) is handled. Image Block, Upgrade End etc. are ignored.
        logDebug "OTA: cmd 0x${rawPayload[2]} not handled from device ${entryDeviceId}"
        return
    }

    String sig = "0019|${entryDeviceId}|${zclSeq}|${rawPayload.join(',')}"
    Map last = (state.lastOtaByDevice ?: [:]) as Map
    long nowMs = now()
    Map prev = last[entryDeviceId] as Map
    if (prev?.sig == sig && (nowMs - ((prev?.ts ?: 0L) as long)) < 2000L) {
        logDebug "OTA: duplicate (dev=${entryDeviceId}, seq=0x${zclSeq}) suppressed"
        return
    }
    last[entryDeviceId] = [sig: sig, ts: nowMs]
    state.lastOtaByDevice = last

    Integer srcEp = Integer.parseInt(entry.sourceEndpoint?.toString() ?: '01', 16)
    Integer dstEp = Integer.parseInt(entry.destinationEndpoint?.toString() ?: '01', 16)
    Integer srcDniInt = (entry.id as Number)?.intValue()
    if (srcDniInt == null) {
        logWarn "OTA: missing source DNI in frame from device ${entryDeviceId}"
        return
    }
    String srcDniHex = String.format('%04X', srcDniInt & 0xFFFF)

    // Decode request fields for the info log line (LE encodings)
    String mfrCode = "0x${rawPayload[5]}${rawPayload[4]}"
    String imgType = "0x${rawPayload[7]}${rawPayload[6]}"
    String fileVer = "0x${rawPayload[11]}${rawPayload[10]}${rawPayload[9]}${rawPayload[8]}"

    String devLabel = entry?.name?.toString() ?: "id=${entryDeviceId}"
    logInfo "<b>OTA Query Next Image</b> from ${devLabel} (dni=0x${srcDniHex}, seq=0x${zclSeq}, mfr=${mfrCode}, imgType=${imgType}, fileVer=${fileVer})"

    sendOtaNoImageResponse(srcDniHex, zclSeq, srcEp, dstEp)
}

private void sendOtaNoImageResponse(String srcDniHex, String zclSeq, Integer srcEp, Integer dstEp) {
    // FC 0x19 = cluster-specific | server-to-client | disable-default-response
    // cmd 0x02 = Query Next Image Response; status 0x98 = NO_IMAGE_AVAILABLE.
    // Per ZCL spec, on non-SUCCESS status the response body ends at the status byte.
    int responseSrcEp = dstEp
    int responseDstEp = srcEp
    String payload = "19 ${zclSeq} 02 98"
    List<String> cmds = ["he raw 0x${srcDniHex} ${responseSrcEp} ${responseDstEp} 0x0019 {${payload}} {0x0104}"]
    logInfo "OTA response → 0x${srcDniHex}: seq=0x${zclSeq}, status=0x98 NO_IMAGE_AVAILABLE"
    logDebug "OTA response payload: ${payload}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// ══════════════════════════════════════════════════════════════════════════
// HELPERS
// ══════════════════════════════════════════════════════════════════════════

private List<String> parseAllowedIds(String csv) {
    if (!csv) return []
    return csv.split(',')*.trim().findAll { it }*.toString()
}

private String toLEHex32(long value) {
    long v = value & 0xFFFFFFFFL
    return String.format('%02X %02X %02X %02X',
        (v & 0xFF), ((v >> 8) & 0xFF), ((v >> 16) & 0xFF), ((v >> 24) & 0xFF))
}

void logsOff() {
    log.info "${device.displayName} debug logging disabled"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

void logDebug(String msg) { if (settings?.logEnable) log.debug "${device.displayName} ${msg}" }
void logInfo(String msg)  { if (settings?.txtEnable) log.info  "${device.displayName} ${msg}" }
void logWarn(String msg)  { if (settings?.logEnable) log.warn  "${device.displayName} ${msg}" }
