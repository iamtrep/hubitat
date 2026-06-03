// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Zigbee Mesh Probe
 *
 * Sends ZDO Mgmt_Lqi_req (cluster 0x0031) and Mgmt_Rtg_req (cluster 0x0032) to
 * arbitrary nodes on the mesh on demand. Companion tooling reads the matching
 * 0x8031 / 0x8032 responses off the hub's /zigbeeLogsocket and decodes them.
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

@Field static final String DRIVER_VERSION = "0.1.0"

@Field static final String CLUSTER_MGMT_LQI_REQ = "0031"
@Field static final String CLUSTER_MGMT_RTG_REQ = "0032"

metadata {
    definition(
        name: "Zigbee Mesh Probe",
        namespace: "iamtrep",
        author: "pj",
        description: "Issues ZDO neighbor-table and routing-table queries for external mesh-mapping tools",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/ZigbeeMeshProbe/ZigbeeMeshProbe.groovy"
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
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() { }
void updated()   { }
void parse(String description) { }

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

private void sendZdoRequest(String addr, String clusterHex, int startIndex) {
    String frame = buildZdoFrame(addr, clusterHex, startIndex)
    sendHubCommand new hubitat.device.HubMultiAction([frame], hubitat.device.Protocol.ZIGBEE)
    if (txtEnable) log.info "ZDO cluster 0x${clusterHex} → ${addr} (startIndex=${startIndex})"
}
