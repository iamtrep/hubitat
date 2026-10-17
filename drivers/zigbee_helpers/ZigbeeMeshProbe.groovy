// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Zigbee Mesh Probe
 *
 * Sends ZDO Mgmt_Lqi_req (cluster 0x0031) and Mgmt_Rtg_req (cluster 0x0032) to
 * arbitrary nodes on the mesh on demand. Companion tooling reads the matching
 * 0x8031 / 0x8032 responses off the hub's /zigbeeLogsocket and decodes them.
 *
 * Also sends ZCL Read Attributes and Discover Attributes to any node, so a device's
 * attributes can be probed without changing its driver. The replies go to the
 * device's own driver and show on /zigbeeLogsocket.
 *
 * No parse() — the platform does not route ZDO responses addressed to the
 * coordinator into user drivers, so capturing them here is impossible. The
 * websocket path is the only working egress.
 *
 * Architectural concept (thin Hubitat driver emits ZDO requests; external
 * client decodes the responses off /zigbeeLogsocket) is from Dan Danache's
 * Zigbee Map app for Hubitat — https://codeberg.org/dan-danache/hubitat. This
 * driver is an independent re-implementation; see tools/zigbee-mesh-probe/.
 */
import groovy.transform.CompileStatic
import groovy.transform.Field
import hubitat.helper.HexUtils

@Field static final String CODE_VERSION = "0.2.0"

@Field static final String CLUSTER_MGMT_LQI_REQ = "0031"
@Field static final String CLUSTER_MGMT_RTG_REQ = "0032"

metadata {
    definition(
        name: "Zigbee Mesh Probe",
        namespace: "iamtrep",
        author: "pj",
        description: "Issues ZDO neighbor-table and routing-table queries, and ZCL attribute reads and discovery, for external tools",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/zigbee_helpers/ZigbeeMeshProbe.groovy"
    ) {
        capability "Actuator"

        command "requestNeighborTable", [
            [name: "addr",       type: "STRING", description: "Target 16-bit network address as 4 hex chars (0000 = coordinator)"],
            [name: "startIndex", type: "NUMBER", description: "Paging start index (default 0)"]
        ]
        command "requestRoutingTable", [
            [name: "addr",       type: "STRING", description: "Target 16-bit network address as 4 hex chars"],
            [name: "startIndex", type: "NUMBER", description: "Paging start index (default 0)"]
        ]
        command "readAttributes", [
            [name: "addr",     type: "STRING", description: "Target 16-bit network address as 4 hex chars"],
            [name: "endpoint", type: "STRING", description: "Target endpoint, 2 hex chars (default 01)"],
            [name: "cluster",  type: "STRING", description: "Cluster id, 4 hex chars"],
            [name: "attrs",    type: "STRING", description: "Attribute ids, 4 hex chars each, comma-separated"],
            [name: "mfgCode",  type: "STRING", description: "Manufacturer code, 4 hex chars, for manufacturer-specific attributes (optional)"]
        ]
        command "discoverAttributes", [
            [name: "addr",      type: "STRING", description: "Target 16-bit network address as 4 hex chars"],
            [name: "endpoint",  type: "STRING", description: "Target endpoint, 2 hex chars (default 01)"],
            [name: "cluster",   type: "STRING", description: "Cluster id, 4 hex chars"],
            [name: "startAttr", type: "STRING", description: "First attribute id to list, 4 hex chars (default 0000)"],
            [name: "mfgCode",   type: "STRING", description: "Manufacturer code, 4 hex chars, for manufacturer-specific attributes (optional)"]
        ]
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() {
    updated()
}
void updated() {
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}
void parse(String description) { }

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void requestNeighborTable(String addr, BigDecimal startIndex = 0) {
    sendZdoRequest(addr, CLUSTER_MGMT_LQI_REQ, startIndex == null ? 0 : startIndex.intValue())
}

void requestRoutingTable(String addr, BigDecimal startIndex = 0) {
    sendZdoRequest(addr, CLUSTER_MGMT_RTG_REQ, startIndex == null ? 0 : startIndex.intValue())
}

// The platform's zigbee.* helpers are ZCL-only; ZDO requests have no FCF/command-ID
// header, so zigbee.command(cluster, …) produces the wrong frame shape. `he raw`
// is the only available path — same precedent as the Sinope/Stelpro drivers.
@CompileStatic
private String buildZdoFrame(String addr, String clusterHex, int startIndex) {
    return "he raw 0x${addr} 0 0 0x${clusterHex} {00 ${HexUtils.integerToHexString(startIndex, 1)}} {0x0000}"
}

void readAttributes(String addr, String endpoint, String cluster, String attrs, String mfgCode = null) {
    String ids = attrs.split(",").collect { String a -> littleEndian(a.trim()) }.join(" ")
    sendZclGlobal(addr, endpoint, cluster, "00", ids, mfgCode)
}

void discoverAttributes(String addr, String endpoint, String cluster, String startAttr = "0000", String mfgCode = null) {
    sendZclGlobal(addr, endpoint, cluster, "0C", "${littleEndian(startAttr ?: '0000')} FF", mfgCode)
}

// ZCL global command frame: frame control (0x04 adds the manufacturer code), sequence 00,
// command id, payload. `he raw <addr> <srcEp> <dstEp> <cluster> {frame}`.
private void sendZclGlobal(String addr, String endpoint, String cluster, String cmdId, String payload, String mfgCode) {
    checkVersion()
    String ep = endpoint ?: "01"
    String header = mfgCode ? "04 ${littleEndian(mfgCode)} 00" : "00 00"
    String frame = "he raw 0x${addr} 1 0x${ep} 0x${cluster} {${header} ${cmdId} ${payload}}"
    sendHubCommand new hubitat.device.HubMultiAction([frame], hubitat.device.Protocol.ZIGBEE)
    logCmd "ZCL 0x${cmdId} cluster 0x${cluster} → ${addr}/${ep}: ${frame}"
}

@CompileStatic
private static String littleEndian(String hex4) {
    String h = hex4.padLeft(4, "0")
    return "${h.substring(2, 4)} ${h.substring(0, 2)}"
}

// No parse() traffic and no device configuration: commands are the only entry
// point, and a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

private void sendZdoRequest(String addr, String clusterHex, int startIndex) {
    checkVersion()
    String frame = buildZdoFrame(addr, clusterHex, startIndex)
    sendHubCommand new hubitat.device.HubMultiAction([frame], hubitat.device.Protocol.ZIGBEE)
    logCmd "ZDO cluster 0x${clusterHex} → ${addr} (startIndex=${startIndex})"
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }
