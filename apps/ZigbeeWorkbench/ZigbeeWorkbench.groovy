// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Zigbee Workbench
 *
 * Inspects any Zigbee device without changing its driver: sends ZDO and ZCL requests through the
 * Zigbee Workbench Radio child device, matches the replies the child reads off the hub's Zigbee
 * log socket, decodes them and shows the results on the app pages.
 */
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String APP_NAME = "Zigbee Workbench"
@Field static final String CODE_VERSION = "0.1.0"

@Field static final String HUB_BASE = "http://127.0.0.1:8080"
@Field static final String RADIO_DNI = "zbworkbench-radio"
@Field static final String RADIO_DRIVER = "Zigbee Workbench Radio"
@Field static final String MONITOR_DRIVER = "Zigbee Workbench Monitor"
@Field static final long REPLY_TIMEOUT_MS = 10000L
@Field static final long HUB_CACHE_MS = 60000L
@Field static final int MAX_PAGES = 20

// Hub data fetched over loopback (zigbeeDetails, child and route tables), keyed by
// "<app id>:<name>" -> [time: ms, data: Map]. In memory only: lost on a push or reboot, which
// costs one refetch. Expires after HUB_CACHE_MS; the Hub tables button clears it.
@Field static final ConcurrentHashMap<String, Map> hubCache = new ConcurrentHashMap<>()

definition(
    name: "Zigbee Workbench",
    namespace: "iamtrep",
    author: "pj",
    description: "Inspect and change any Zigbee device without changing its driver: ZDO tables and descriptors, attributes, reporting, commands, binding",
    category: "Utility",
    singleInstance: true,
    singleThreaded: true,
    iconUrl: "",
    iconX2Url: "",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/ZigbeeWorkbench",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ZigbeeWorkbench/ZigbeeWorkbench.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "devicePage")
    page(name: "hubPage")
    page(name: "confirmPage")
}

// ==== Codec begin ====
// Pure frame builders and decoders. No app context (log, state, settings, device): the unit test
// extracts this region and runs it under plain groovy. Hex strings are upper case, addresses 4 hex
// chars, IEEE addresses 16 hex chars, response payloads are lists of 2-hex bytes as the socket sends them.

// Zigbee spec 05-3474-22, ZDO status Table 2-138.
@Field static final Map<Integer, String> ZDO_STATUS = [
    0x00: 'SUCCESS', 0x80: 'INV_REQUESTTYPE', 0x81: 'DEVICE_NOT_FOUND', 0x82: 'INVALID_EP', 0x83: 'NOT_ACTIVE',
    0x84: 'NOT_SUPPORTED', 0x85: 'TIMEOUT', 0x86: 'NO_MATCH', 0x88: 'NO_ENTRY', 0x89: 'NO_DESCRIPTOR',
    0x8A: 'INSUFFICIENT_SPACE', 0x8B: 'NOT_PERMITTED', 0x8C: 'TABLE_FULL', 0x8D: 'NOT_AUTHORIZED',
    0x8E: 'DEVICE_BINDING_TABLE_FULL'
]

// ZCL revision 8, status enumerations Table 2-12.
@Field static final Map<Integer, String> ZCL_STATUS = [
    0x00: 'SUCCESS', 0x01: 'FAILURE', 0x7E: 'NOT_AUTHORIZED', 0x7F: 'RESERVED_FIELD_NOT_ZERO',
    0x80: 'MALFORMED_COMMAND', 0x81: 'UNSUP_CLUSTER_COMMAND', 0x82: 'UNSUP_GENERAL_COMMAND',
    0x83: 'UNSUP_MANUF_CLUSTER_COMMAND', 0x84: 'UNSUP_MANUF_GENERAL_COMMAND', 0x85: 'INVALID_FIELD',
    0x86: 'UNSUPPORTED_ATTRIBUTE', 0x87: 'INVALID_VALUE', 0x88: 'READ_ONLY', 0x89: 'INSUFFICIENT_SPACE',
    0x8A: 'DUPLICATE_EXISTS', 0x8B: 'NOT_FOUND', 0x8C: 'UNREPORTABLE_ATTRIBUTE', 0x8D: 'INVALID_DATA_TYPE',
    0x8E: 'INVALID_SELECTOR', 0x8F: 'WRITE_ONLY', 0x90: 'INCONSISTENT_STARTUP_STATE', 0x91: 'DEFINED_OUT_OF_BAND',
    0x92: 'INCONSISTENT', 0x93: 'ACTION_DENIED', 0x94: 'TIMEOUT', 0x95: 'ABORT', 0x96: 'INVALID_IMAGE',
    0x97: 'WAIT_FOR_DATA', 0x98: 'NO_IMAGE_AVAILABLE', 0x99: 'REQUIRE_MORE_IMAGE', 0x9A: 'NOTIFICATION_PENDING',
    0xC0: 'HARDWARE_FAILURE', 0xC1: 'SOFTWARE_FAILURE', 0xC2: 'CALIBRATION_ERROR', 0xC3: 'UNSUPPORTED_CLUSTER'
]

@Field static final List<String> ROUTE_STATUS_NAMES = ['Active', 'Discovery Underway', 'Discovery Failed', 'Inactive', 'Validation Underway', 'Reserved']

// ---- byte helpers ----

@CompileStatic
static String hex2(int v) {
    return String.format('%02X', v & 0xFF)
}

// "1037" -> "37 10"; works for any even-length hex string (IEEE addresses too).
@CompileStatic
static String littleEndian(String hex) {
    String h = hex.toUpperCase()
    if (h.length() % 2 == 1) h = '0' + h
    List<String> out = []
    for (int i = h.length() - 2; i >= 0; i -= 2) out << h.substring(i, i + 2)
    return out.join(' ')
}

// Integer -> n little-endian bytes as "XX XX".
@CompileStatic
static String leInt(long v, int bytes) {
    List<String> out = []
    for (int i = 0; i < bytes; i++) out << hex2((int) ((v >> (8 * i)) & 0xFF))
    return out.join(' ')
}

@CompileStatic
static int u8(List<String> p, int i) {
    return Integer.parseInt(p[i], 16)
}

@CompileStatic
static int u16(List<String> p, int i) {
    return u8(p, i) | (u8(p, i + 1) << 8)
}

// Little-endian bytes at i..i+n-1 as a big-endian hex string ("6F 5E" -> "5E6F").
@CompileStatic
static String hexAt(List<String> p, int i, int n) {
    StringBuilder sb = new StringBuilder()
    for (int k = i + n - 1; k >= i; k--) sb.append(p[k].toUpperCase())
    return sb.toString()
}

@CompileStatic
static String zdoStatusName(int s) {
    String n = ZDO_STATUS.get(s)
    return n != null ? n : '0x' + hex2(s)
}

@CompileStatic
static String zclStatusName(int s) {
    String n = ZCL_STATUS.get(s)
    return n != null ? n : '0x' + hex2(s)
}

// ---- request builders ----

@CompileStatic
static String zdoFrame(String addr, String reqCluster, int seq, String payloadHex) {
    String body = hex2(seq) + (payloadHex ? ' ' + payloadHex : '')
    return "he raw 0x${addr} 0 0 0x${reqCluster} {${body}} {0x0000}".toString()
}

// opts: clusterSpecific, toClient, disableDefaultRsp (Boolean, default false), mfgCode (String or null)
@CompileStatic
static String zclFrame(String addr, String ep, String cluster, int seq, int cmd, String payloadHex, Map opts) {
    Map o = opts == null ? [:] : opts
    int fc = 0
    if (o.get('clusterSpecific') == true) fc |= 0x01
    String mfg = o.get('mfgCode') as String
    if (mfg) fc |= 0x04
    if (o.get('toClient') == true) fc |= 0x08
    if (o.get('disableDefaultRsp') == true) fc |= 0x10
    String body = hex2(fc) + (mfg ? ' ' + littleEndian(mfg) : '') + ' ' + hex2(seq) + ' ' + hex2(cmd) + (payloadHex ? ' ' + payloadHex : '')
    return "he raw 0x${addr} 1 0x${ep} 0x${cluster} {${body}}".toString()
}

// OTA Image Notify (cluster 0x0019, command 0x00, payload type 0x03, jitter 0x64, wildcard
// manufacturer, image type and file version), which makes a device query for firmware.
@CompileStatic
static String imageNotifyFrame(String addr, String ep, int seq) {
    return "he raw 0x${addr} 1 0x${ep} 0x0019 {09 ${hex2(seq)} 00 03 64 FFFF FFFF FFFF FFFF}".toString()
}

// Node (0x0002), Power (0x0003) and Active Endpoints (0x0005) requests.
@CompileStatic
static String nwkPayload(String addr) {
    return littleEndian(addr)
}

@CompileStatic
static String simpleDescPayload(String addr, String ep) {
    return littleEndian(addr) + ' ' + ep.toUpperCase()
}

// Mgmt_Lqi_req, Mgmt_Rtg_req, Mgmt_Bind_req start index.
@CompileStatic
static String indexPayload(int start) {
    return hex2(start)
}

// Mgmt_Leave_req: device address, then flags (bit 7 rejoin, bit 6 remove children).
@CompileStatic
static String leavePayload(String ieee, boolean rejoin) {
    return littleEndian(ieee) + ' ' + hex2(rejoin ? 0x80 : 0x00)
}

// Bind_req (0x0021) and Unbind_req (0x0022), unicast destination (address mode 0x03).
@CompileStatic
static String bindPayload(String srcIeee, String srcEp, String cluster, String dstIeee, String dstEp) {
    return littleEndian(srcIeee) + ' ' + srcEp.toUpperCase() + ' ' + littleEndian(cluster) + ' 03 ' + littleEndian(dstIeee) + ' ' + dstEp.toUpperCase()
}

// Same, group destination (address mode 0x01).
@CompileStatic
static String bindGroupPayload(String srcIeee, String srcEp, String cluster, String group) {
    return littleEndian(srcIeee) + ' ' + srcEp.toUpperCase() + ' ' + littleEndian(cluster) + ' 01 ' + littleEndian(group)
}

@CompileStatic
static String readAttrPayload(List<Integer> ids) {
    List<String> out = []
    for (Integer id : ids) out << leInt(id.longValue(), 2)
    return out.join(' ')
}

@CompileStatic
static String discoverAttrPayload(int startId, int max) {
    return leInt(startId, 2) + ' ' + hex2(max)
}

// valueHexLE is already in wire (little-endian) byte order.
@CompileStatic
static String writeAttrPayload(int id, int type, String valueHexLE) {
    return leInt(id, 2) + ' ' + hex2(type) + (valueHexLE ? ' ' + valueHexLE.trim().toUpperCase() : '')
}

// Configure Reporting for one attribute, direction 0 (reports sent by the server). changeHexLE is
// the reportable change for analog types in wire order; empty for discrete types.
@CompileStatic
static String configReportingPayload(int id, int type, int min, int max, String changeHexLE) {
    return '00 ' + leInt(id, 2) + ' ' + hex2(type) + ' ' + leInt(min, 2) + ' ' + leInt(max, 2) +
        (changeHexLE ? ' ' + changeHexLE.trim().toUpperCase() : '')
}

@CompileStatic
static String readReportingPayload(int id) {
    return '00 ' + leInt(id, 2)
}

// ---- ZDO decoders ----

@CompileStatic
static Map decodeZdo(String rspCluster, List<String> p) {
    Map<String, Object> r = [:]
    if (p.size() < 2) {
        r.put('seq', p.size() > 0 ? u8(p, 0) : -1)
        r.put('status', -1)
        r.put('statusName', 'TRUNCATED')
        return r
    }
    int status = u8(p, 1)
    r.put('seq', u8(p, 0))
    r.put('status', status)
    r.put('statusName', zdoStatusName(status))
    if (status != 0) return r
    switch (rspCluster.toUpperCase()) {
        case '8031': decodeTable(r, p, 22, 'lqi'); break
        case '8032': decodeTable(r, p, 5, 'rtg'); break
        case '8033': decodeTable(r, p, 0, 'bind'); break
        case '8002': decodeNodeDesc(r, p); break
        case '8003': decodePowerDesc(r, p); break
        case '8005': decodeActiveEp(r, p); break
        case '8004': decodeSimpleDesc(r, p); break
        default: break
    }
    return r
}

// Mgmt_Lqi/Rtg/Bind_rsp: seq, status, total, start index, count, entries.
@CompileStatic
private static void decodeTable(Map<String, Object> r, List<String> p, int entrySize, String kind) {
    List<Map> entries = []
    r.put('total', p.size() > 2 ? u8(p, 2) : 0)
    r.put('start', p.size() > 3 ? u8(p, 3) : 0)
    r.put('entries', entries)
    int count = p.size() > 4 ? u8(p, 4) : 0
    int off = 5
    for (int i = 0; i < count; i++) {
        Map e = null
        int used = entrySize
        if (kind == 'bind') {
            Object[] res = decodeBindEntry(p, off)
            if (res == null) break
            e = (Map) res[0]
            used = (int) res[1]
        } else {
            if (off + entrySize > p.size()) break
            e = kind == 'lqi' ? decodeLqiEntry(p, off) : decodeRouteEntry(p, off)
        }
        entries << e
        off += used
    }
}

@CompileStatic
private static Map decodeLqiEntry(List<String> p, int o) {
    int o1 = u8(p, o + 18)
    int o2 = u8(p, o + 19)
    int type = o1 & 0x03
    int rxOn = (o1 >> 2) & 0x03
    int rel = (o1 >> 4) & 0x07
    int permit = o2 & 0x03
    List<String> relNames = ['Parent', 'Child', 'Sibling', 'None', 'Previous child']
    Map<String, Object> e = [:]
    e.put('extPan', hexAt(p, o, 8))
    e.put('ieee', hexAt(p, o + 8, 8))
    e.put('nwk', hexAt(p, o + 16, 2))
    e.put('deviceType', type == 0 ? 'Coordinator' : type == 1 ? 'Router' : type == 2 ? 'End Device' : 'Unknown')
    e.put('rxOnWhenIdle', rxOn == 0 ? 'Off' : rxOn == 1 ? 'On' : 'Unknown')
    e.put('relationship', rel < relNames.size() ? relNames[rel] : 'Unknown')
    e.put('permitJoin', permit == 0 ? 'No' : permit == 1 ? 'Yes' : 'Unknown')
    e.put('depth', u8(p, o + 20))
    e.put('lqi', u8(p, o + 21))
    return e
}

@CompileStatic
private static Map decodeRouteEntry(List<String> p, int o) {
    int f = u8(p, o + 2)
    int st = f & 0x07
    Map<String, Object> e = [:]
    e.put('dest', hexAt(p, o, 2))
    e.put('routeStatus', st < ROUTE_STATUS_NAMES.size() ? ROUTE_STATUS_NAMES[st] : 'Reserved')
    e.put('memoryConstrained', (f & 0x08) != 0)
    e.put('manyToOne', (f & 0x10) != 0)
    e.put('routeRecordRequired', (f & 0x20) != 0)
    e.put('nextHop', hexAt(p, o + 3, 2))
    return e
}

// Variable-length binding table entry; returns [entry map, bytes used] or null when truncated or
// the address mode is unknown.
@CompileStatic
private static Object[] decodeBindEntry(List<String> p, int o) {
    if (o + 12 > p.size()) return null
    int mode = u8(p, o + 11)
    Map<String, Object> e = [:]
    e.put('srcIeee', hexAt(p, o, 8))
    e.put('srcEp', p[o + 8].toUpperCase())
    e.put('cluster', hexAt(p, o + 9, 2))
    if (mode == 0x01) {
        if (o + 14 > p.size()) return null
        e.put('dstGroup', hexAt(p, o + 12, 2))
        return [e, 14] as Object[]
    }
    if (mode == 0x03) {
        if (o + 21 > p.size()) return null
        e.put('dstIeee', hexAt(p, o + 12, 8))
        e.put('dstEp', p[o + 20].toUpperCase())
        return [e, 21] as Object[]
    }
    return null
}

@CompileStatic
private static void decodeNodeDesc(Map<String, Object> r, List<String> p) {
    if (p.size() < 4) return
    r.put('nwk', hexAt(p, 2, 2))
    if (p.size() < 4 + 11) return
    int lt = u8(p, 4) & 0x07
    r.put('logicalType', lt == 0 ? 'Coordinator' : lt == 1 ? 'Router' : lt == 2 ? 'End Device' : 'Unknown')
    r.put('macCapability', u8(p, 6))
    r.put('mfgCode', hexAt(p, 7, 2))
    r.put('maxBufferSize', u8(p, 9))
    r.put('serverMask', u16(p, 12))
}

@CompileStatic
private static void decodePowerDesc(Map<String, Object> r, List<String> p) {
    if (p.size() < 4) return
    r.put('nwk', hexAt(p, 2, 2))
    if (p.size() < 6) return
    int b0 = u8(p, 4)
    int b1 = u8(p, 5)
    r.put('currentMode', b0 & 0x0F)
    r.put('availableSources', (b0 >> 4) & 0x0F)
    r.put('currentSource', b1 & 0x0F)
    r.put('level', (b1 >> 4) & 0x0F)
}

@CompileStatic
private static void decodeActiveEp(Map<String, Object> r, List<String> p) {
    if (p.size() < 4) return
    r.put('nwk', hexAt(p, 2, 2))
    List<String> eps = []
    int count = p.size() > 4 ? u8(p, 4) : 0
    for (int i = 0; i < count && 5 + i < p.size(); i++) eps << p[5 + i].toUpperCase()
    r.put('endpoints', eps)
}

@CompileStatic
private static void decodeSimpleDesc(Map<String, Object> r, List<String> p) {
    if (p.size() < 4) return
    r.put('nwk', hexAt(p, 2, 2))
    if (p.size() < 5 + 8) return
    int o = 5
    r.put('ep', p[o].toUpperCase())
    r.put('profileId', hexAt(p, o + 1, 2))
    r.put('deviceId', hexAt(p, o + 3, 2))
    r.put('version', u8(p, o + 5) & 0x0F)
    int inCount = u8(p, o + 6)
    o += 7
    List<String> ins = []
    for (int i = 0; i < inCount && o + 2 <= p.size(); i++) {
        ins << hexAt(p, o, 2)
        o += 2
    }
    List<String> outs = []
    int outCount = o < p.size() ? u8(p, o) : 0
    o++
    for (int i = 0; i < outCount && o + 2 <= p.size(); i++) {
        outs << hexAt(p, o, 2)
        o += 2
    }
    r.put('inClusters', ins)
    r.put('outClusters', outs)
}

// ---- ZCL decoders ----

@CompileStatic
static Map parseZclHeader(List<String> p) {
    int fc = u8(p, 0)
    boolean mfg = (fc & 0x04) != 0
    int o = 1
    String mfgCode = null
    if (mfg) {
        mfgCode = hexAt(p, 1, 2)
        o = 3
    }
    Map<String, Object> h = [:]
    h.put('frameType', fc & 0x03)
    h.put('mfgSpecific', mfg)
    h.put('toClient', (fc & 0x08) != 0)
    h.put('disableDefaultRsp', (fc & 0x10) != 0)
    h.put('mfgCode', mfgCode)
    h.put('seq', u8(p, o))
    h.put('cmd', u8(p, o + 1))
    h.put('offset', o + 2)
    return h
}

// Analog types take a reportable change in Configure Reporting: integers, floats, time types.
@CompileStatic
private static boolean isAnalogType(int type) {
    return (type >= 0x20 && type <= 0x2F) || (type >= 0x38 && type <= 0x3A) || (type >= 0xE0 && type <= 0xE2)
}

@CompileStatic
static Map decodeZcl(Map header, List<String> p) {
    Map<String, Object> r = [:]
    int off = (int) header.get('offset')
    int cmd = (int) header.get('cmd')
    r.put('seq', header.get('seq'))
    if ((int) header.get('frameType') != 0) {
        r.put('clusterSpecific', true)
        r.put('command', cmd)
        r.put('payload', off <= p.size() ? new ArrayList<String>(p.subList(off, p.size())) : new ArrayList<String>())
        return r
    }
    List<Map> records = []
    switch (cmd) {
        case 0x01:   // Read Attributes Response
            while (off + 3 <= p.size()) {
                Map<String, Object> rec = [:]
                int st = u8(p, off + 2)
                rec.put('attr', u16(p, off))
                rec.put('status', st)
                rec.put('statusName', zclStatusName(st))
                off += 3
                if (st != 0) { records << rec; continue }
                if (off >= p.size()) { records << rec; break }
                int type = u8(p, off)
                List v = decodeValueCore(type, p, off + 1)
                rec.put('type', type)
                rec.put('value', v[0])
                records << rec
                if (v[2] == true) break
                off += 1 + (int) v[1]
            }
            r.put('records', records)
            break
        case 0x0D:   // Discover Attributes Response
            r.put('complete', off < p.size() && u8(p, off) != 0)
            List<Map> attrs = []
            for (int o = off + 1; o + 3 <= p.size(); o += 3) {
                Map<String, Object> a = [:]
                a.put('id', u16(p, o))
                a.put('type', u8(p, o + 2))
                attrs << a
            }
            r.put('attrs', attrs)
            break
        case 0x04:   // Write Attributes Response: status, attr
        case 0x07:   // Configure Reporting Response: status, direction, attr
            boolean cfg = cmd == 0x07
            int recLen = cfg ? 4 : 3
            if (p.size() - off == 1 && u8(p, off) == 0) {
                Map<String, Object> ok = [:]
                ok.put('status', 0)
                ok.put('statusName', zclStatusName(0))
                records << ok
            } else {
                for (int o = off; o + recLen <= p.size(); o += recLen) {
                    Map<String, Object> rec = [:]
                    int st = u8(p, o)
                    rec.put('status', st)
                    rec.put('statusName', zclStatusName(st))
                    if (cfg) rec.put('direction', u8(p, o + 1))
                    rec.put('attr', u16(p, o + recLen - 2))
                    records << rec
                }
            }
            r.put('records', records)
            break
        case 0x09:   // Read Reporting Configuration Response
            while (off + 4 <= p.size()) {
                Map<String, Object> rec = [:]
                int st = u8(p, off)
                int dir = u8(p, off + 1)
                rec.put('direction', dir)
                rec.put('attr', u16(p, off + 2))
                rec.put('status', st)
                rec.put('statusName', zclStatusName(st))
                off += 4
                if (st == 0) {
                    if (dir == 0) {
                        if (off >= p.size()) { records << rec; break }
                        int type = u8(p, off)
                        rec.put('type', type)
                        off++
                        if (off + 4 > p.size()) { records << rec; break }
                        rec.put('min', u16(p, off))
                        rec.put('max', u16(p, off + 2))
                        off += 4
                        if (isAnalogType(type)) {
                            List v = decodeValueCore(type, p, off)
                            if (v[2] == true) { records << rec; break }
                            rec.put('change', v[0])
                            off += (int) v[1]
                        }
                    } else {
                        if (off + 2 > p.size()) { records << rec; break }
                        rec.put('timeout', u16(p, off))
                        off += 2
                    }
                }
                records << rec
            }
            r.put('records', records)
            break
        case 0x0B:   // Default Response
            if (off + 2 <= p.size()) {
                int st = u8(p, off + 1)
                r.put('cmd', u8(p, off))
                r.put('status', st)
                r.put('statusName', zclStatusName(st))
            }
            break
        default:
            r.put('payload', off <= p.size() ? new ArrayList<String>(p.subList(off, p.size())) : new ArrayList<String>())
    }
    return r
}

// Returns [value, byteLength] for the attribute value at p[offset]. Unsupported types (including
// 0x48 array, 0x4C struct, 0x50 set, 0x51 bag) and truncated values give ["raw:<hex of the rest>",
// remaining], which callers treat as the end of the record list.
@CompileStatic
static List decodeZclValue(int type, List<String> p, int offset) {
    List v = decodeValueCore(type, p, offset)
    return [v[0], v[1]]
}

// As decodeZclValue, plus a third element: true when the value could not be decoded.
@CompileStatic
private static List decodeValueCore(int type, List<String> p, int offset) {
    int remaining = p.size() - offset
    if (remaining < 0) remaining = 0
    String rawRest = offset < p.size() ? p.subList(offset, p.size()).join('').toUpperCase() : ''
    List rawResult = ['raw:' + rawRest, remaining, true]
    int n = 0           // fixed byte length, 0 when length-prefixed
    int prefix = 0      // length-prefix bytes
    if (type == 0x10) n = 1
    else if (type >= 0x08 && type <= 0x0F) n = type - 0x08 + 1
    else if (type >= 0x18 && type <= 0x1F) n = type - 0x18 + 1
    else if (type >= 0x20 && type <= 0x27) n = type - 0x20 + 1
    else if (type >= 0x28 && type <= 0x2F) n = type - 0x28 + 1
    else if (type == 0x30) n = 1
    else if (type == 0x31) n = 2
    else if (type == 0x38) n = 2
    else if (type == 0x39) n = 4
    else if (type == 0x3A) n = 8
    else if (type == 0x41 || type == 0x42) prefix = 1
    else if (type == 0x43 || type == 0x44) prefix = 2
    else if (type == 0xE0 || type == 0xE1 || type == 0xE2) n = 4
    else if (type == 0xE8 || type == 0xE9) n = 2
    else if (type == 0xEA) n = 4
    else if (type == 0xF0) n = 8
    else if (type == 0xF1) n = 16
    else return rawResult

    if (prefix > 0) {
        if (remaining < prefix) return rawResult
        int len = prefix == 1 ? u8(p, offset) : u16(p, offset)
        boolean invalid = (prefix == 1 && len == 0xFF) || (prefix == 2 && len == 0xFFFF)
        if (invalid) return [null, prefix, false]
        if (remaining < prefix + len) return rawResult
        int s = offset + prefix
        if (type == 0x41 || type == 0x43) {
            return [p.subList(s, s + len).join('').toUpperCase(), prefix + len, false]
        }
        byte[] bytes = new byte[len]
        for (int i = 0; i < len; i++) bytes[i] = (byte) u8(p, s + i)
        String str = new String(bytes, 'UTF-8').replace('\u0000', '')
        return [str, prefix + len, false]
    }

    if (remaining < n) return rawResult
    Object val
    if (type == 0x10) {
        int b = u8(p, offset)
        val = b == 0xFF ? null : (Object) (b != 0)
    } else if ((type >= 0x18 && type <= 0x27) || type == 0x30 || type == 0x31 || type == 0xE2) {
        val = normalizeInt(leBig(p, offset, n, false))
    } else if (type >= 0x28 && type <= 0x2F) {
        val = normalizeInt(leBig(p, offset, n, true))
    } else if (type == 0x38) {
        int bits = u16(p, offset)
        int exp = (bits >> 10) & 0x1F
        int frac = bits & 0x3FF
        double mag
        if (exp == 0) mag = frac * Math.pow(2.0d, -24.0d)
        else if (exp == 31) mag = frac == 0 ? Double.POSITIVE_INFINITY : Double.NaN
        else mag = (1.0d + frac / 1024.0d) * Math.pow(2.0d, (double) (exp - 15))
        val = (bits & 0x8000) != 0 ? -mag : mag
    } else if (type == 0x39) {
        float f = Float.intBitsToFloat(leBig(p, offset, 4, false).intValue())
        val = Double.valueOf(Float.toString(f))
    } else if (type == 0x3A) {
        val = Double.longBitsToDouble(leBig(p, offset, 8, false).longValue())
    } else if (type == 0xE0) {   // time of day: hours, minutes, seconds, hundredths; 0xFF marks a field not used
        List<String> f = new ArrayList<String>()
        for (int i = 0; i < 4; i++) {
            int b = u8(p, offset + i)
            f.add(b == 0xFF ? '--' : String.format('%02d', b))
        }
        val = f.count('--') == 4 ? null : (Object) (f[0] + ':' + f[1] + ':' + f[2] + '.' + f[3])
    } else if (type == 0xE1) {   // date: year-1900, month, day of month, day of week; 0xFF marks a field not used
        int y = u8(p, offset), mo = u8(p, offset + 1), dd = u8(p, offset + 2)
        val = (y == 0xFF && mo == 0xFF && dd == 0xFF) ? null : (Object) ((y == 0xFF ? '----' : String.format('%04d', 1900 + y)) + '-' +
            (mo == 0xFF ? '--' : String.format('%02d', mo)) + '-' + (dd == 0xFF ? '--' : String.format('%02d', dd)))
    } else {                     // 0x08-0x0F data, 0xE8 0xE9 0xEA 0xF0 0xF1 identifiers and keys: shown big-endian
        val = hexAt(p, offset, n)
    }
    return [val, n, false]
}

@CompileStatic
private static BigInteger leBig(List<String> p, int off, int n, boolean signed) {
    BigInteger v = BigInteger.ZERO
    for (int i = n - 1; i >= 0; i--) v = v.shiftLeft(8).or(BigInteger.valueOf(u8(p, off + i)))
    if (signed && v.testBit(n * 8 - 1)) v = v.subtract(BigInteger.ONE.shiftLeft(n * 8))
    return v
}

// Smallest of Integer, Long, BigInteger that holds the value.
@CompileStatic
private static Object normalizeInt(BigInteger v) {
    if (v.bitLength() < 32) return v.intValue()
    if (v.bitLength() < 64) return v.longValue()
    return v
}

// ---- names for the interview report ----

// Cluster and attribute names, written from ZCL rev 8 (Metering from the Smart Energy Metering
// cluster). Ids not listed show as hex.
@Field static final Map<Integer, Map> ZCL_ATTR_NAMES = [
    0x0000: [name: 'Basic', attrs: [0x0000: 'ZCLVersion', 0x0001: 'ApplicationVersion', 0x0002: 'StackVersion', 0x0003: 'HWVersion',
        0x0004: 'ManufacturerName', 0x0005: 'ModelIdentifier', 0x0006: 'DateCode', 0x0007: 'PowerSource', 0x0008: 'GenericDevice-Class',
        0x0009: 'GenericDevice-Type', 0x000A: 'ProductCode', 0x000B: 'ProductURL', 0x000C: 'ManufacturerVersionDetails',
        0x000D: 'SerialNumber', 0x000E: 'ProductLabel', 0x0010: 'LocationDescription', 0x0011: 'PhysicalEnvironment',
        0x0012: 'DeviceEnabled', 0x0013: 'AlarmMask', 0x0014: 'DisableLocalConfig', 0x4000: 'SWBuildID']],
    0x0001: [name: 'Power Configuration', attrs: [0x0000: 'MainsVoltage', 0x0001: 'MainsFrequency', 0x0010: 'MainsAlarmMask',
        0x0011: 'MainsVoltageMinThreshold', 0x0012: 'MainsVoltageMaxThreshold', 0x0013: 'MainsVoltageDwellTripPoint',
        0x0020: 'BatteryVoltage', 0x0021: 'BatteryPercentageRemaining', 0x0030: 'BatteryManufacturer', 0x0031: 'BatterySize',
        0x0032: 'BatteryAHrRating', 0x0033: 'BatteryQuantity', 0x0034: 'BatteryRatedVoltage', 0x0035: 'BatteryAlarmMask',
        0x0036: 'BatteryVoltageMinThreshold', 0x0037: 'BatteryVoltageThreshold1', 0x0038: 'BatteryVoltageThreshold2',
        0x0039: 'BatteryVoltageThreshold3', 0x003A: 'BatteryPercentageMinThreshold', 0x003B: 'BatteryPercentageThreshold1',
        0x003C: 'BatteryPercentageThreshold2', 0x003D: 'BatteryPercentageThreshold3', 0x003E: 'BatteryAlarmState']],
    0x0003: [name: 'Identify', attrs: [0x0000: 'IdentifyTime']],
    0x0004: [name: 'Groups', attrs: [0x0000: 'NameSupport']],
    0x0005: [name: 'Scenes', attrs: [0x0000: 'SceneCount', 0x0001: 'CurrentScene', 0x0002: 'CurrentGroup', 0x0003: 'SceneValid',
        0x0004: 'NameSupport', 0x0005: 'LastConfiguredBy']],
    0x0006: [name: 'On/Off', attrs: [0x0000: 'OnOff', 0x4000: 'GlobalSceneControl', 0x4001: 'OnTime', 0x4002: 'OffWaitTime',
        0x4003: 'StartUpOnOff']],
    0x0008: [name: 'Level Control', attrs: [0x0000: 'CurrentLevel', 0x0001: 'RemainingTime', 0x0002: 'MinLevel', 0x0003: 'MaxLevel',
        0x0004: 'CurrentFrequency', 0x0005: 'MinFrequency', 0x0006: 'MaxFrequency', 0x000F: 'Options', 0x0010: 'OnOffTransitionTime',
        0x0011: 'OnLevel', 0x0012: 'OnTransitionTime', 0x0013: 'OffTransitionTime', 0x0014: 'DefaultMoveRate',
        0x4000: 'StartUpCurrentLevel']],
    0x0019: [name: 'OTA Upgrade', attrs: [0x0000: 'UpgradeServerID', 0x0001: 'FileOffset', 0x0002: 'CurrentFileVersion',
        0x0003: 'CurrentZigBeeStackVersion', 0x0004: 'DownloadedFileVersion', 0x0005: 'DownloadedZigBeeStackVersion',
        0x0006: 'ImageUpgradeStatus', 0x0007: 'ManufacturerID', 0x0008: 'ImageTypeID', 0x0009: 'MinimumBlockPeriod',
        0x000A: 'ImageStamp', 0x000B: 'UpgradeActivationPolicy', 0x000C: 'UpgradeTimeoutPolicy']],
    0x0201: [name: 'Thermostat', attrs: [0x0000: 'LocalTemperature', 0x0001: 'OutdoorTemperature', 0x0002: 'Occupancy',
        0x0003: 'AbsMinHeatSetpointLimit', 0x0004: 'AbsMaxHeatSetpointLimit', 0x0005: 'AbsMinCoolSetpointLimit',
        0x0006: 'AbsMaxCoolSetpointLimit', 0x0007: 'PICoolingDemand', 0x0008: 'PIHeatingDemand',
        0x0009: 'HVACSystemTypeConfiguration', 0x0010: 'LocalTemperatureCalibration', 0x0011: 'OccupiedCoolingSetpoint',
        0x0012: 'OccupiedHeatingSetpoint', 0x0013: 'UnoccupiedCoolingSetpoint', 0x0014: 'UnoccupiedHeatingSetpoint',
        0x0015: 'MinHeatSetpointLimit', 0x0016: 'MaxHeatSetpointLimit', 0x0017: 'MinCoolSetpointLimit',
        0x0018: 'MaxCoolSetpointLimit', 0x0019: 'MinSetpointDeadBand', 0x001A: 'RemoteSensing',
        0x001B: 'ControlSequenceOfOperation', 0x001C: 'SystemMode', 0x001D: 'AlarmMask', 0x001E: 'ThermostatRunningMode',
        0x0020: 'StartOfWeek', 0x0021: 'NumberOfWeeklyTransitions', 0x0022: 'NumberOfDailyTransitions',
        0x0023: 'TemperatureSetpointHold', 0x0024: 'TemperatureSetpointHoldDuration',
        0x0025: 'ThermostatProgrammingOperationMode', 0x0029: 'ThermostatRunningState', 0x0030: 'SetpointChangeSource',
        0x0031: 'SetpointChangeAmount', 0x0032: 'SetpointChangeSourceTimestamp', 0x0034: 'OccupiedSetback',
        0x0035: 'OccupiedSetbackMin', 0x0036: 'OccupiedSetbackMax', 0x0037: 'UnoccupiedSetback', 0x0038: 'UnoccupiedSetbackMin',
        0x0039: 'UnoccupiedSetbackMax', 0x003A: 'EmergencyHeatDelta', 0x0040: 'ACType', 0x0041: 'ACCapacity',
        0x0042: 'ACRefrigerantType', 0x0043: 'ACCompressorType', 0x0044: 'ACErrorCode', 0x0045: 'ACLouverPosition',
        0x0046: 'ACCoilTemperature', 0x0047: 'ACCapacityFormat']],
    0x0300: [name: 'Color Control', attrs: [0x0000: 'CurrentHue', 0x0001: 'CurrentSaturation', 0x0002: 'RemainingTime',
        0x0003: 'CurrentX', 0x0004: 'CurrentY', 0x0005: 'DriftCompensation', 0x0006: 'CompensationText',
        0x0007: 'ColorTemperatureMireds', 0x0008: 'ColorMode', 0x000F: 'Options', 0x0010: 'NumberOfPrimaries',
        0x0011: 'Primary1X', 0x0012: 'Primary1Y', 0x0013: 'Primary1Intensity', 0x0015: 'Primary2X', 0x0016: 'Primary2Y',
        0x0017: 'Primary2Intensity', 0x0019: 'Primary3X', 0x001A: 'Primary3Y', 0x001B: 'Primary3Intensity',
        0x0030: 'WhitePointX', 0x0031: 'WhitePointY', 0x0032: 'ColorPointRX', 0x0033: 'ColorPointRY',
        0x0034: 'ColorPointRIntensity', 0x0036: 'ColorPointGX', 0x0037: 'ColorPointGY', 0x0038: 'ColorPointGIntensity',
        0x003A: 'ColorPointBX', 0x003B: 'ColorPointBY', 0x003C: 'ColorPointBIntensity', 0x4000: 'EnhancedCurrentHue',
        0x4001: 'EnhancedColorMode', 0x4002: 'ColorLoopActive', 0x4003: 'ColorLoopDirection', 0x4004: 'ColorLoopTime',
        0x4005: 'ColorLoopStartEnhancedHue', 0x4006: 'ColorLoopStoredEnhancedHue', 0x400A: 'ColorCapabilities',
        0x400B: 'ColorTempPhysicalMinMireds', 0x400C: 'ColorTempPhysicalMaxMireds',
        0x400D: 'CoupleColorTempToLevelMinMireds', 0x4010: 'StartUpColorTemperatureMireds']],
    0x0402: [name: 'Temperature Measurement', attrs: [0x0000: 'MeasuredValue', 0x0001: 'MinMeasuredValue',
        0x0002: 'MaxMeasuredValue', 0x0003: 'Tolerance']],
    0x0405: [name: 'Relative Humidity Measurement', attrs: [0x0000: 'MeasuredValue', 0x0001: 'MinMeasuredValue',
        0x0002: 'MaxMeasuredValue', 0x0003: 'Tolerance']],
    0x0406: [name: 'Occupancy Sensing', attrs: [0x0000: 'Occupancy', 0x0001: 'OccupancySensorType',
        0x0002: 'OccupancySensorTypeBitmap', 0x0010: 'PIROccupiedToUnoccupiedDelay', 0x0011: 'PIRUnoccupiedToOccupiedDelay',
        0x0012: 'PIRUnoccupiedToOccupiedThreshold', 0x0020: 'UltrasonicOccupiedToUnoccupiedDelay',
        0x0021: 'UltrasonicUnoccupiedToOccupiedDelay', 0x0022: 'UltrasonicUnoccupiedToOccupiedThreshold',
        0x0030: 'PhysicalContactOccupiedToUnoccupiedDelay', 0x0031: 'PhysicalContactUnoccupiedToOccupiedDelay',
        0x0032: 'PhysicalContactUnoccupiedToOccupiedThreshold']],
    0x0500: [name: 'IAS Zone', attrs: [0x0000: 'ZoneState', 0x0001: 'ZoneType', 0x0002: 'ZoneStatus', 0x0010: 'IAS_CIE_Address',
        0x0011: 'ZoneID', 0x0012: 'NumberOfZoneSensitivityLevelsSupported', 0x0013: 'CurrentZoneSensitivityLevel']],
    0x0702: [name: 'Metering', attrs: [0x0000: 'CurrentSummationDelivered', 0x0001: 'CurrentSummationReceived',
        0x0002: 'CurrentMaxDemandDelivered', 0x0003: 'CurrentMaxDemandReceived', 0x0004: 'DFTSummation',
        0x0005: 'DailyFreezeTime', 0x0006: 'PowerFactor', 0x0007: 'ReadingSnapShotTime',
        0x0008: 'CurrentMaxDemandDeliveredTime', 0x0009: 'CurrentMaxDemandReceivedTime', 0x000A: 'DefaultUpdatePeriod',
        0x0014: 'SupplyStatus', 0x0200: 'Status', 0x0300: 'UnitofMeasure', 0x0301: 'Multiplier', 0x0302: 'Divisor',
        0x0303: 'SummationFormatting', 0x0304: 'DemandFormatting', 0x0305: 'HistoricalConsumptionFormatting',
        0x0306: 'MeteringDeviceType', 0x0400: 'InstantaneousDemand']],
    0x0B04: [name: 'Electrical Measurement', attrs: [0x0000: 'MeasurementType', 0x0100: 'DCVoltage', 0x0103: 'DCCurrent',
        0x0106: 'DCPower', 0x0300: 'ACFrequency', 0x0304: 'TotalActivePower', 0x0305: 'TotalReactivePower',
        0x0306: 'TotalApparentPower', 0x0400: 'ACFrequencyMultiplier', 0x0401: 'ACFrequencyDivisor', 0x0402: 'PowerMultiplier',
        0x0403: 'PowerDivisor', 0x0404: 'HarmonicCurrentMultiplier', 0x0405: 'PhaseHarmonicCurrentMultiplier', 0x0505: 'RMSVoltage', 0x0508: 'RMSCurrent', 0x050B: 'ActivePower',
        0x050E: 'ReactivePower', 0x050F: 'ApparentPower', 0x0510: 'PowerFactor', 0x0600: 'ACVoltageMultiplier',
        0x0601: 'ACVoltageDivisor', 0x0602: 'ACCurrentMultiplier', 0x0603: 'ACCurrentDivisor', 0x0604: 'ACPowerMultiplier',
        0x0605: 'ACPowerDivisor', 0x0800: 'ACAlarmsMask']]
]

// Global attributes, in every cluster (ZCL rev 8, 2.6).
@Field static final Map<Integer, String> ZCL_GLOBAL_ATTRS = [0xFFFD: 'ClusterRevision', 0xFFFE: 'AttributeReportingStatus']

// Short data type names (ZCL rev 8, Table 2-10).
@Field static final Map<Integer, String> ZCL_TYPE_NAMES = [
    0x08: 'data8', 0x09: 'data16', 0x0A: 'data24', 0x0B: 'data32', 0x0C: 'data40', 0x0D: 'data48', 0x0E: 'data56', 0x0F: 'data64',
    0x10: 'bool', 0x18: 'map8', 0x19: 'map16', 0x1A: 'map24', 0x1B: 'map32', 0x1C: 'map40', 0x1D: 'map48', 0x1E: 'map56', 0x1F: 'map64',
    0x20: 'uint8', 0x21: 'uint16', 0x22: 'uint24', 0x23: 'uint32', 0x24: 'uint40', 0x25: 'uint48', 0x26: 'uint56', 0x27: 'uint64',
    0x28: 'int8', 0x29: 'int16', 0x2A: 'int24', 0x2B: 'int32', 0x2C: 'int40', 0x2D: 'int48', 0x2E: 'int56', 0x2F: 'int64',
    0x30: 'enum8', 0x31: 'enum16', 0x38: 'semi', 0x39: 'single', 0x3A: 'double', 0x41: 'octstr', 0x42: 'string',
    0x43: 'octstr16', 0x44: 'string16', 0x48: 'array', 0x4C: 'struct', 0x50: 'set', 0x51: 'bag', 0xE0: 'ToD', 0xE1: 'date',
    0xE2: 'UTC', 0xE8: 'clusterId', 0xE9: 'attribId', 0xEA: 'bacOID', 0xF0: 'EUI64', 0xF1: 'key128', 0xFF: 'unk'
]

@CompileStatic
static String clusterLabel(String cluster, Map names) {
    Map c = (Map) names?.get(Integer.parseInt(cluster, 16))
    return '0x' + cluster.toUpperCase() + (c ? ' ' + c.name : '')
}

// The interview as plain text: device, node type, endpoints, then per server cluster its
// attributes with name, type and value. `iv` holds node, endpoints, simple[ep], disc["EP:CL"]
// ([id, type] maps), vals["EP:CL"]["AAAA"] (read records) and failed[step key] (reason).
@CompileStatic
static String renderReport(Map iv, Map names) {
    Map simple = (Map) (iv.simple ?: [:])
    Map disc = (Map) (iv.disc ?: [:])
    Map vals = (Map) (iv.vals ?: [:])
    Map failed = (Map) (iv.failed ?: [:])
    Map node = (Map) iv.node
    List<String> eps = (List<String>) (iv.endpoints ?: [])
    Closure<String> basic = { String attr ->
        for (String ep in eps) {
            Map rec = (Map) ((Map) vals[ep + ':0000'])?.get(attr)
            if (rec != null && rec.status == 0) return rec.value?.toString()
        }
        return 'not read'
    }
    List<String> out = []
    out << "Manufacturer: ${basic('0004')}".toString()
    out << "Model: ${basic('0005')}".toString()
    if (node) out << "Node type: ${node.logicalType}, manufacturer code 0x${node.mfgCode}".toString()
    else out << "Node type: not read${failed.node ? ' (' + failed.node + ')' : ''}".toString()
    if (failed.endpoints) out << "Endpoints: not read (${failed.endpoints})".toString()
    else out << "Endpoints: ${eps ? eps.join(', ') : 'none'}".toString()
    for (String ep in eps) {
        Map sd = (Map) simple[ep]
        if (sd == null) {
            out << "Endpoint ${ep}: simple descriptor not read${failed['simple:' + ep] ? ' (' + failed['simple:' + ep] + ')' : ''}".toString()
            continue
        }
        out << "Endpoint ${ep}: profile 0x${sd.profileId}, device 0x${sd.deviceId}".toString()
        List<String> outs = (List<String>) (sd.outClusters ?: [])
        if (outs) out << "  Client clusters: ${outs.collect { clusterLabel(it, names) }.join(', ')}".toString()
        for (String cl in (List<String>) (sd.inClusters ?: [])) {
            String key = "${ep}:${cl}".toString()
            out << "  ${clusterLabel(cl, names)}".toString()
            String why = failed['discover:' + key]
            if (why) { out << "    attributes not discovered (${why})".toString(); continue }
            Map attrNames = (Map) ((Map) names?.get(Integer.parseInt(cl, 16)))?.get('attrs') ?: [:]
            Map got = (Map) (vals[key] ?: [:])
            for (Map a in ((List<Map>) (disc[key] ?: [])).sort(false) { Map x -> (int) (x.get('id') as int) }) {
                int id = a.id as int
                String hex = String.format('%04X', id)
                String name = (String) (attrNames[id] ?: ZCL_GLOBAL_ATTRS[id])
                int type = a.type as int
                String tn = ZCL_TYPE_NAMES[type] ?: String.format('0x%02X', type)
                String head = "    0x${hex}${name ? ' ' + name : ''} (${tn})".toString()
                Map rec = (Map) got[hex]
                if (rec == null) out << "${head}: not read".toString()
                else if ((rec.status as int) != 0) out << "${head}: ${rec.statusName}".toString()
                else out << "${head} = ${rec.value}".toString()
            }
            failed.findAll { k, v -> k.toString().startsWith('read:' + key + ':') }.each { k, v ->
                out << "    read from 0x${k.toString().substring(('read:' + key + ':').length())} failed (${v})".toString()
            }
        }
    }
    return out.join('\n')
}
// ==== Codec end ====


// ==== Engine begin ====
// Pure request-engine helpers: no app context, extracted and run by tests/test-request-engine.groovy
// together with the codec region. A pending request is a Map with at least addr, cluster (the reply
// cluster: req | 0x8000 for ZDO, the request cluster for ZCL), zdo, sent, deadline (null until
// sent); ZDO table requests carry start, simple descriptor requests ep, ZCL requests expectCmds
// (the general commands that answer them), cmd, and attrs (read) or start (discover).
//
// The hub replaces the transaction sequence number of every `he raw` frame, ZDO and ZCL, with its
// own, so a reply cannot be matched by sequence number. It is matched by address, reply cluster
// and its content instead, and only one request per address is on the air at a time
// (see pickSendable).

// ZDO request clusters per action; paged actions walk a table by start index.
@Field static final Map<String, Map> ZDO_ACTIONS = [
    neighbors: [req: '0031', paged: true], routes: [req: '0032', paged: true], bindings: [req: '0033', paged: true],
    node: [req: '0002'], power: [req: '0003'], endpoints: [req: '0005'], simple: [req: '0004']
]

@Field static final int DISCOVER_PAGE_SIZE = 16
@Field static final String NO_REPLY = "No reply within 10 seconds."
@Field static final String NO_REPLY_SLEEPY = "No reply within 10 seconds. This device turns its receiver off when idle: it answers only when awake."
@Field static final String NO_REPLY_UNKNOWN = "No reply within 10 seconds. A battery device answers only when awake."
@Field static final String NOT_ZIGBEE = "Not a Zigbee device"
@Field static final String LOST_LINK = "Lost the Zigbee log connection"
@Field static final String NO_REPLY_DDR = "No reply. With the default response disabled, a command that succeeds may send none."
@Field static final String NO_QUERY = "No query came. The device may not take firmware updates this way, or may ask later."
@Field static final String RAW_REFUSED = "A raw command must be a he command for this device."
// Warnings on the confirmation page, for frames that do more than their form suggests.
@Field static final String WARN_RESET = "This is Reset to Factory Defaults (Basic cluster command 00): the device loses its settings and may leave the network."
@Field static final String WARN_RESET_MAYBE = "This Basic cluster frame cannot be read: it may be Reset to Factory Defaults."
@Field static final String WARN_LEAVE = "Cluster 0x0034 is Mgmt_Leave: the device may leave the network."
@Field static final String WARN_OTA = "Cluster 0x0019 is OTA Upgrade: the device may start a firmware update."
@Field static final String RAW_ZDO_MGMT = "Raw commands may not send ZDO requests that change the network (0x0036, 0x0038)."
@Field static final String RAW_WAIT_PENDING = "Wait: a request to this device is still waiting for its reply."
@Field static final String ZCL_EP_RANGE = "ZCL endpoints are 01-F0"
// How long a confirmation stays valid.
@Field static final long CONFIRM_TTL_MS = 120000L
// General commands that have a response command (ZCL rev 8, Table 2-3).
@Field static final Map<Integer, Integer> GENERAL_RSP = [0x00: 0x01, 0x02: 0x04, 0x03: 0x04, 0x06: 0x07, 0x08: 0x09, 0x0C: 0x0D,
                                                        0x11: 0x12, 0x13: 0x14, 0x15: 0x16]
@Field static final List<String> RAW_VERBS = ['raw', 'cmd', 'rattr', 'wattr', 'cr']
// Requests on the air at once, all devices together: one per device and a total well under the
// radio's unicast slots (10 by default in the Silicon Labs stack), which the hub's drivers share.
// ZHA allows 8 across the network, Zigbee2MQTT 16; neither adds a delay between requests.
@Field static final int MAX_IN_FLIGHT = 4

// Local request number, 1-255. It keeps pending keys unique; frames carry 00 (buildRequestFrame).
@CompileStatic
static int nextSeq(int last) {
    return (last < 1 || last >= 255) ? 1 : last + 1
}

@CompileStatic
static String pendingKey(String addr, String cluster, int seq) {
    return addr.toUpperCase() + ':' + cluster.toUpperCase() + ':' + String.format('%02X', seq & 0xFF)
}

// ZDO reply cluster for a request cluster ("0031" -> "8031").
@CompileStatic
static String zdoReplyCluster(String req) {
    return String.format('%04X', Integer.parseInt(req, 16) | 0x8000)
}

// Finds the pending request a frame answers, removes it from `pending` and returns
// [key, request, header] (header: the parsed ZCL header, null for ZDO), or null. A frame matches a
// sent, unexpired request with the same address and cluster, of the same kind (ZDO when the source
// endpoint is 00), whose content it fits (replyFits). When several fit, the oldest wins.
static Map matchPending(Map pending, Map frame, long now) {
    if (!pending || frame == null) return null
    List<String> p = (frame.payload instanceof List) ? ((List) frame.payload).collect { it.toString().toUpperCase() } : []
    String addr = frame.addr?.toString()?.toUpperCase()
    String cluster = frame.clusterId?.toString()?.toUpperCase()
    if (!addr || !cluster) return null
    boolean zdo = frame.srcEp?.toString() in ['00', '0']
    Map header = null
    if (zdo) {
        if (p.size() < 2) return null
    } else {
        if (p.size() < 3) return null
        try {
            header = parseZclHeader(p)
        } catch (Exception ignored) {
            return null
        }
        if ((header.offset as int) > p.size()) return null
    }
    Map.Entry best = null
    for (Object o : pending.entrySet()) {
        Map.Entry e = (Map.Entry) o
        if (!(e.value instanceof Map)) continue
        Map req = (Map) e.value
        if (req.sent == false) continue
        if (req.addr?.toString()?.toUpperCase() != addr || req.cluster?.toString()?.toUpperCase() != cluster) continue
        if ((req.zdo as boolean) != zdo) continue
        if (req.deadline != null && now > (req.deadline as long)) continue
        // The Query Next Image Request answering a firmware notify can come from the device's OTA
        // client endpoint, which need not be the endpoint the notify went to.
        if (!zdo && req.ep != null && req.action != 'ota' && !sameEp(req.ep, frame.srcEp)) continue
        if (!replyFits(req, p, header)) continue
        if (best == null || ((req.sentAt ?: 0) as long) < ((((Map) best.value).sentAt ?: 0) as long)) best = e
    }
    if (best == null) return null
    pending.remove(best.key)
    return [key: best.key.toString(), request: best.value, header: header]
}

// Whether a ZCL reply's source endpoint is the endpoint the request went to (hex, any width). An
// endpoint that does not parse is not held against the frame.
static boolean sameEp(Object want, Object got) {
    try {
        return Integer.parseInt(want.toString(), 16) == Integer.parseInt(got.toString(), 16)
    } catch (Exception ignored) {
        return true
    }
}

// Whether a reply payload can be the answer to `req` (beyond address and cluster).
static boolean replyFits(Map req, List<String> p, Map header) {
    if (req.zdo) {
        int status = Integer.parseInt(p[1], 16)
        if (status != 0) return true
        String c = req.cluster.toString().toUpperCase()
        if (c in ['8031', '8032', '8033']) {
            return p.size() > 3 && Integer.parseInt(p[3], 16) == ((req.start ?: 0) as int)
        }
        if (c in ['8002', '8003', '8004', '8005'] && p.size() >= 4) {
            if (hexAt(p, 2, 2) != req.addr.toString().toUpperCase()) return false
        }
        if (c == '8004' && p.size() > 5 && req.ep) {
            return p[5] == req.ep.toString().toUpperCase()
        }
        return true
    }
    int cmd = header.cmd as int
    int off = header.offset as int
    boolean general = (header.frameType as int) == 0
    // A Default Response ends a firmware notify only when it reports a failure: on success the
    // answer still to come is the device's Query Next Image Request.
    if (general && cmd == 0x0B && req.action == 'ota') {
        return p.size() > off + 1 && req.cmd != null && Integer.parseInt(p[off], 16) == (req.cmd as int) && Integer.parseInt(p[off + 1], 16) != 0
    }
    if (general && cmd == 0x0B && req.action == 'cmd') {
        return p.size() > off && req.cmd != null && Integer.parseInt(p[off], 16) == (req.cmd as int)
    }
    if (req.action == 'cmd') {
        if ((req.frameType as int) == 0) return general && GENERAL_RSP.get(req.cmdId as int) == cmd
        return !general && (header.toClient as boolean) != (req.toClient as boolean)
    }
    if (req.action == 'ota') return !general && cmd == 0x01 && !(header.toClient as boolean)
    if (!general) return false
    if (req.expectCmds && !((List) req.expectCmds).collect { it as int }.contains(cmd)) return false
    if ((cmd == 0x04 || cmd == 0x07 || cmd == 0x09) && req.attr) {
        int want = Integer.parseInt(req.attr.toString(), 16)
        List recs = (List) (decodeZcl(header, p).records ?: [])
        if (recs.isEmpty()) return false
        // an all-success Write or Configure Reporting response is one status byte, with no attribute
        return recs.every { Map r -> r.attr == null ? (r.status as int) == 0 : (r.attr as int) == want }
    }
    if (cmd == 0x0B) {
        return p.size() > off && req.cmd != null && Integer.parseInt(p[off], 16) == (req.cmd as int)
    }
    if (cmd == 0x09 && req.attrs) {
        List<Integer> asked = ((List) req.attrs).collect { Integer.parseInt(it.toString(), 16) }
        List recs = (List) (decodeZcl(header, p).records ?: [])
        return !recs.isEmpty() && recs.every { asked.contains(((Map) it).attr as int) }
    }
    if (cmd == 0x01 && req.attrs) {
        List<Integer> asked = ((List) req.attrs).collect { Integer.parseInt(it.toString(), 16) }
        Map d = decodeZcl(header, p)
        List recs = (List) (d.records ?: [])
        return !recs.isEmpty() && recs.every { asked.contains(((Map) it).attr as int) }
    }
    if (cmd == 0x0D && req.start != null) {
        Map d = decodeZcl(header, p)
        List attrs = (List) (d.attrs ?: [])
        return attrs.isEmpty() || ((((Map) attrs[0]).id as int) >= (req.start as int))
    }
    return true
}

// Keys whose deadline has passed. Does not remove them.
static List<String> expirePending(Map pending, long now) {
    if (!pending) return []
    return pending.findAll { k, v -> v instanceof Map && v.deadline != null && now > (v.deadline as long) }
        .collect { it.key.toString() }
}

// Start index of the next table page, or null when this page was the last one or empty.
static Integer nextPageStart(Map decoded) {
    List entries = decoded?.entries instanceof List ? (List) decoded.entries : []
    if (entries.isEmpty()) return null
    int next = ((decoded.start ?: 0) as int) + entries.size()
    return next >= ((decoded.total ?: 0) as int) ? null : next
}

// Current short address of a device, found by IEEE in /hub/zigbeeDetails/json.
// Returns [ieee, nwk, name, ep] or null when the IEEE is not on the network. The details map holds
// no endpoint, so ep is '01'; the app replaces it with the device's own endpointId.
static Map resolveTarget(String ieee, Map zigbeeDetails) {
    if (!ieee || zigbeeDetails == null) return null
    List devs = zigbeeDetails.devices instanceof List ? (List) zigbeeDetails.devices : []
    Map d = (Map) devs.find { it instanceof Map && it.zigbeeId?.toString()?.equalsIgnoreCase(ieee) }
    if (d == null || !d.shortZigbeeId) return null
    return [ieee: ieee.toUpperCase(), nwk: d.shortZigbeeId.toString().toUpperCase().padLeft(4, '0'),
            name: d.name?.toString(), ep: '01']
}

// The `he raw` frame for a request map (action, addr, ep, reqCluster or cluster, start, attrs). The
// sequence byte is 00, as in the hub's own zigbee helpers: the hub puts its own sequence number
// there. With a non-zero byte, a Simple_Desc_req sent a few seconds after another ZDO request
// sometimes reached the device with that byte kept and drew NOT_ACTIVE; with 00 it never did.
static String buildRequestFrame(Map r) {
    String addr = r.addr as String
    int seq = 0
    switch (r.action as String) {
        case 'neighbors':
        case 'routes':
        case 'bindings':
            return zdoFrame(addr, r.reqCluster as String, seq, indexPayload((r.start ?: 0) as int))
        case 'simple':
            return zdoFrame(addr, r.reqCluster as String, seq, simpleDescPayload(addr, r.ep as String))
        case 'node':
        case 'power':
        case 'endpoints':
            return zdoFrame(addr, r.reqCluster as String, seq, nwkPayload(addr))
        case 'read':
            List<Integer> ids = ((List) r.attrs).collect { Integer.parseInt(it.toString(), 16) }
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x00, readAttrPayload(ids), [:])
        case 'discover':
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x0C,
                            discoverAttrPayload((r.start ?: 0) as int, DISCOVER_PAGE_SIZE), [:])
        case 'write':
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x02,
                            writeAttrPayload(Integer.parseInt(r.attr as String, 16), r.type as int, r.value as String), [mfgCode: r.mfg])
        case 'configReport':
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x06,
                            configReportingPayload(Integer.parseInt(r.attr as String, 16), r.type as int, r.min as int, r.max as int, r.change as String),
                            [mfgCode: r.mfg])
        case 'reportcfg':
            String body = ((List) r.attrs).collect { readReportingPayload(Integer.parseInt(it.toString(), 16)) }.join(' ')
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x08, body, [:])
        case 'readReport':
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, 0x08,
                            readReportingPayload(Integer.parseInt(r.attr as String, 16)), [mfgCode: r.mfg])
        case 'cmd':
            return zclFrame(addr, r.ep as String, r.cluster as String, seq, r.cmdId as int, r.payload as String,
                            [clusterSpecific: (r.frameType as int) == 1, toClient: r.toClient == true, disableDefaultRsp: r.ddr == true, mfgCode: r.mfg])
        case 'bind':
        case 'unbind':
            String body = r.group ? bindGroupPayload(r.ieee as String, r.srcEp as String, r.bindCluster as String, r.group as String)
                                  : bindPayload(r.ieee as String, r.srcEp as String, r.bindCluster as String, r.dstIeee as String, r.dstEp as String)
            return zdoFrame(addr, r.reqCluster as String, seq, body)
        case 'leave':
            return zdoFrame(addr, '0034', seq, leavePayload(r.ieee as String, r.rejoin == true))
        case 'ota':
            return imageNotifyFrame(addr, r.ep as String, seq)
        default:
            return null
    }
}
// A failed result: the status (timeout, refused, lost, error), the request frame that was sent, the
// raw reply payload if one came, the device's status name for refused, and what a paged request had
// collected before it failed.
static Map failureEntry(Map req, String status, String error, List<String> raw, String statusName, long now) {
    Map e = [time: now, status: status, error: error, frame: req.frame]
    if (raw) e.raw = raw
    if (statusName) e.statusName = statusName
    if (req.acc) e.partial = req.acc
    return e
}

static Map timeoutEntry(Map req, long now) {
    return failureEntry(req, 'timeout', noReplyText(req), null, null, now)
}

// Why a request may have had no reply: a cluster command sent with the default response disabled
// gets none when it succeeds; a firmware notify is answered by a query the device may send later; a
// device that turns its receiver off when idle answers only when awake. req.sleepy comes from the
// node descriptor, null when it has not been read.
static String noReplyText(Map req) {
    if (req.action == 'cmd' && req.ddr == true) return NO_REPLY_DDR
    if (req.action == 'ota') return NO_QUERY
    if (req.sleepy == null) return NO_REPLY_UNKNOWN
    return req.sleepy ? NO_REPLY_SLEEPY : NO_REPLY
}

// Whether a device turns its receiver off when idle (MAC capability bit 3 clear), from its node
// descriptor; null when it has not been read.
@CompileStatic
static Boolean sleepyFrom(Map node) {
    Object mac = node?.get('macCapability')
    return mac == null ? null : (Boolean) (((mac as int) & 0x08) == 0)
}

// Empties `pending` and returns [ieee, kind, entry] for each request it held, as lost. A second call
// finds nothing and returns [].
static List<Map> failAll(Map pending, String reason, long now) {
    if (!pending) return []
    List<Map> out = pending.values().collect { r -> [ieee: r.ieee, kind: r.kind, entry: failureEntry((Map) r, 'lost', reason, null, null, now)] }
    pending.clear()
    return out
}

// Addresses the radio should watch: those of pending requests, sorted. The radio's socket stays
// open; the list only filters the frames it passes on.
static List<String> watchFor(Map pending) {
    return ((pending ?: [:]).values().collect { it.addr.toString().toUpperCase() } as List<String>).unique().sort()
}

// What flushRadio does with requests ready to send: "send" while the radio's socket (always kept
// open) is connected, otherwise "fail": the requests are reported lost rather than sent with nobody
// listening for the reply.
@CompileStatic
static String flushDecision(String wsStatus) {
    return wsStatus == "connected" ? "send" : "fail"
}

// A picked device: [zigbee: true, ieee, ep] for a Zigbee device (ep falls back to 01), or
// [zigbee: false, note: NOT_ZIGBEE].
static Map classifyPick(Object zigbeeId, Object endpointId) {
    String ieee = zigbeeId?.toString()
    if (!ieee || !(ieee ==~ /(?i)[0-9a-f]{16}/)) return [zigbee: false, note: NOT_ZIGBEE]
    String ep = endpointId?.toString()
    return [zigbee: true, ieee: ieee.toUpperCase(),
            ep: (ep && ep ==~ /(?i)[0-9a-f]{1,2}/) ? ep.toUpperCase().padLeft(2, '0') : '01']
}

// Which unsent requests to send now, in queue order: none to a device with a request on the air,
// and at most MAX_IN_FLIGHT on the air in all. Replies (or timeouts) pace the queue; there is no
// fixed delay. Returns [keys].
static Map pickSendable(Map pending) {
    List sent = (pending ?: [:]).values().findAll { it.sent }
    Set<String> busy = sent.collect { it.addr.toString().toUpperCase() } as Set<String>
    int room = MAX_IN_FLIGHT - sent.size()
    List<String> keys = []
    List queued = (pending ?: [:]).findAll { k, v -> !v.sent }.collect { it }.sort { (it.value.queuedAt ?: 0) as long }
    for (Object o : queued) {
        if (keys.size() >= room) break
        Map.Entry e = (Map.Entry) o
        String addr = ((Map) e.value).addr.toString().toUpperCase()
        if (busy.contains(addr)) continue
        busy << addr
        keys << e.key.toString()
    }
    return [keys: keys]
}
// ---- confirmation tokens ----

// Adds a confirmation token for an action that changes a device. Only one is outstanding at a time:
// issuing revokes every earlier token, used or not.
static Map tokenIssue(Map tokens, String token, String action, String ieee, Map args, long now) {
    tokens.clear()
    tokens[token] = [action: action, ieee: ieee, args: args ?: [:], issued: now]
    return tokens
}

// The token's [action, ieee, args, issued] once: it is removed whether it is used or expired.
// Null for an unknown, used or expired token.
static Map tokenTake(Map tokens, String token, long now) {
    if (tokens == null || token == null) return null
    Object e = tokens.remove(token)
    if (!(e instanceof Map)) return null
    return now - (((Map) e).issued as long) > CONFIRM_TTL_MS ? null : (Map) e
}

// ---- raw commands ----

// Null when `cmd` is one `he` command addressed to the device at `nwk`, otherwise why not. Fails
// closed: the endpoint and cluster tokens must be 0x plus 1-4 hex digits, or a single digit (the
// same value read as decimal or hex); anything else is refused. The ZDO requests that change the
// whole network, 0x0036 (Mgmt_Permit_Joining) and 0x0038 (Mgmt_NWK_Update), are refused whatever
// the endpoint: the hub runs the network. A `he cmd` command token must be one byte.
static String rawCommandError(String cmd, String nwk) {
    if (cmd == null || nwk == null || cmd.trim().isEmpty() || cmd ==~ /(?s).*[^\x20-\x7E].*/) return RAW_REFUSED
    List<String> t = cmd.trim().split(/ +/) as List<String>
    if (t.size() < 3 || t[0] != 'he' || !(t[1].toLowerCase() in RAW_VERBS)) return RAW_REFUSED
    if (!t[2].equalsIgnoreCase('0x' + nwk)) return RAW_REFUSED
    boolean raw = t[1].toLowerCase() == 'raw'
    // he raw 0x<addr> <srcEp> <dstEp> <cluster> {...}; he cmd|rattr|wattr|cr 0x<addr> <ep> <cluster> ...
    List<Integer> idx = raw ? [3, 4, 5] : [3, 4]
    if (t.size() <= idx.last()) return RAW_REFUSED
    List<Integer> vals = idx.collect { int i -> rawNumber(t[i]) }
    if (vals.contains(null)) return RAW_REFUSED
    int cluster = vals.last()
    if (cluster == 0x0036 || cluster == 0x0038) return RAW_ZDO_MGMT
    if (t[1].toLowerCase() == 'cmd') {
        // he cmd 0x<addr> <ep> <cluster> <cmd> {...}: a ZCL command id is one byte; a hub keeping only
        // the low byte of 0x0100 would send 00, so anything wider is refused.
        Integer c = t.size() > 5 ? rawNumber(t[5]) : null
        if (c == null || c > 0xFF) return RAW_REFUSED
    }
    return null
}

// What the confirmation page warns of for a raw command that rawCommandError accepts, or null:
// a leave, an OTA command, or a factory reset (or a Basic cluster frame that cannot be read).
static String rawWarning(String cmd) {
    List<String> t = cmd.trim().split(/ +/) as List<String>
    boolean raw = t[1].toLowerCase() == 'raw'
    int cluster = rawNumber(t[raw ? 5 : 4])
    if (cluster == 0x0034) return WARN_LEAVE
    if (cluster == 0x0019) return WARN_OTA
    if (cluster != 0x0000) return null
    if (raw && rawNumber(t[4]) != 0) {
        // he raw 0x<addr> <srcEp> <dstEp> 0x0000 {<ZCL frame>}: read its header
        Map h = rawZclHeader(t.subList(6, t.size()).join(' '))
        if (h == null) return WARN_RESET_MAYBE
        return isFactoryReset('0000', h.frameType as int, h.cmd as int, h.toClient as boolean) ? WARN_RESET : null
    }
    // he cmd 0x<addr> <ep> 0x0000 <cmd> {...}: cluster-specific, client to server
    return t[1].toLowerCase() == 'cmd' && rawNumber(t[5]) == 0x00 ? WARN_RESET : null
}

// What the confirmation page warns of for a form request (zclFormRequest's fields), or null.
static String requestWarning(String action, Map r) {
    if (action == 'cmd' && isFactoryReset(r.cluster as String, r.frameType as int, r.cmdId as int, r.toClient == true)) return WARN_RESET
    if (action in ['write', 'configReport', 'cmd'] && r.cluster == '0019') return WARN_OTA
    return null
}

// Basic cluster (0x0000) Reset to Factory Defaults: cluster-specific command 00, client to server.
static boolean isFactoryReset(String cluster, int frameType, int cmd, boolean toClient) {
    return Integer.parseInt(cluster, 16) == 0x0000 && frameType == 1 && cmd == 0x00 && !toClient
}

// [frameType, toClient, cmd] from the `{...}` payload of a `he raw` ZCL frame, or null when it
// cannot be read.
private static Map rawZclHeader(String rest) {
    def m = rest =~ /^\{([0-9A-Fa-f ]*)\}/
    if (!m.find()) return null
    String hex = m.group(1).replaceAll(' ', '')
    if (hex.length() % 2 != 0 || hex.length() < 6) return null
    List<String> p = (0..<(hex.length() / 2)).collect { int i -> hex.substring(2 * i, 2 * i + 2) }
    int fc = Integer.parseInt(p[0], 16)
    int ci = (fc & 0x04) != 0 ? 4 : 2
    if (p.size() <= ci) return null
    return [frameType: fc & 0x03, toClient: (fc & 0x08) != 0, cmd: Integer.parseInt(p[ci], 16)]
}

// A raw endpoint or cluster token: 0x plus 1-4 hex digits, or one decimal digit; null otherwise.
private static Integer rawNumber(String s) {
    if (s ==~ /(?i)0x[0-9a-f]{1,4}/) return Integer.parseInt(s.substring(2), 16)
    if (s ==~ /[0-9]/) return Integer.parseInt(s)
    return null
}

// Why a raw command cannot go now, or null. A raw command's reply is never matched, so it waits
// until no request to the device is pending (its reply could be taken for theirs).
static String rawSendBlock(Map pending, String nwk) {
    String a = nwk?.toUpperCase()
    if ((pending ?: [:]).values().any { it instanceof Map && it.addr?.toString()?.toUpperCase() == a }) return RAW_WAIT_PENDING
    return null
}

// ---- forms ----

// Byte length of a fixed-length ZCL type, -1 or -2 for a 1- or 2-byte length-prefixed string,
// null for a type the forms do not encode.
static Integer zclTypeLength(int type) {
    if (type == 0x10) return 1
    if (type >= 0x08 && type <= 0x0F) return type - 0x08 + 1
    if (type >= 0x18 && type <= 0x1F) return type - 0x18 + 1
    if (type >= 0x20 && type <= 0x27) return type - 0x20 + 1
    if (type >= 0x28 && type <= 0x2F) return type - 0x28 + 1
    if (type == 0x30) return 1
    if (type == 0x31 || type == 0x38 || type == 0xE8 || type == 0xE9) return 2
    if (type == 0x39 || type == 0xE0 || type == 0xE1 || type == 0xE2 || type == 0xEA) return 4
    if (type == 0x3A || type == 0xF0) return 8
    if (type == 0x41 || type == 0x42) return -1
    if (type == 0x43 || type == 0x44) return -2
    return null
}

// A value typed in hex, most significant byte first (strings: the bytes in order), in wire order
// for `type`; null when it does not fit or the type is not supported.
static String encodeZclValue(int type, String hex) {
    String h = hex?.replaceAll(/\s+/, '')?.replaceFirst(/^0[xX]/, '')?.toUpperCase()
    if (!h || !(h ==~ /[0-9A-F]+/)) return null
    Integer n = zclTypeLength(type)
    if (n == null) return null
    if (n > 0) {
        if (h.length() > 2 * n) return null
        return littleEndian(h.padLeft(2 * n, '0'))
    }
    if (h.length() % 2 != 0) return null
    List<String> bytes = (0..<(h.length() / 2)).collect { int i -> h.substring(2 * i, 2 * i + 2) }
    if (n == -1 && bytes.size() > 254) return null
    return ([leInt(bytes.size(), -n)] + bytes).join(' ')
}

private static String hexField(Object v, int digits) {
    String s = v?.toString()?.trim()?.replaceFirst(/^0[xX]/, '')
    return (s && s ==~ /(?i)[0-9a-f]{1,${digits}}/) ? s.toUpperCase().padLeft(digits, '0') : null
}

// Null when `ep` (1-2 hex digits) is a ZCL endpoint, 01-F0, else why not. Every ZCL request
// checks it: endpoint 00 is the ZDO, where a cluster id can be a ZDO request.
static String zclEpError(String ep) {
    String e = hexField(ep, 2)
    if (e == null) return "Endpoint must be 2 hex digits, not '${ep}'".toString()
    return zclEpOk(e) ? null : "${ZCL_EP_RANGE}, not '${e}'".toString()
}

private static boolean zclEpOk(String ep) {
    int e = Integer.parseInt(ep, 16)
    return e >= 0x01 && e <= 0xF0
}

private static Integer seconds(Object v) {
    String s = v?.toString()?.trim()
    if (!(s ==~ /\d{1,5}/)) return null
    int i = Integer.parseInt(s)
    return i <= 65535 ? i : null
}

// The request fields for a ZCL form (write, configReport, readReport, cmd), or [error: text].
// f: ep, cluster, mfg, attr, type, value, min, max, change, cmd, payload, frameType ('cluster' or
// 'general'), toClient, ddr. An empty endpoint is the device's own.
static Map zclFormRequest(String action, Map f, String defaultEp) {
    String ep = hexField(f.ep ?: defaultEp, 2)
    if (!ep) return [error: "Endpoint must be 2 hex digits, not '${f.ep}'".toString()]
    String cluster = hexField(f.cluster, 4)
    if (!cluster) return [error: "Cluster must be 4 hex digits, not '${f.cluster}'".toString()]
    if (!zclEpOk(ep)) return [error: "${ZCL_EP_RANGE}, not '${ep}'".toString()]
    String mfg = null
    if (f.mfg?.toString()?.trim()) {
        mfg = hexField(f.mfg, 4)
        if (!mfg) return [error: "Manufacturer code must be 4 hex digits, not '${f.mfg}'".toString()]
    }
    Map r = [ep: ep, cluster: cluster]
    if (action == 'cmd') {
        String c = hexField(f.cmd, 2)
        if (!c) return [error: "Command must be 2 hex digits, not '${f.cmd}'".toString()]
        String raw = (f.payload ?: '').toString().replaceAll(/\s+/, '').toUpperCase()
        if (raw && (!(raw ==~ /[0-9A-F]+/) || raw.length() % 2 != 0)) return [error: "Payload must be whole hex bytes, not '${f.payload}'".toString()]
        String payload = raw ? (0..<(raw.length() / 2)).collect { int i -> raw.substring(2 * i, 2 * i + 2) }.join(' ') : ''
        int id = Integer.parseInt(c, 16)
        r += [cmdId: id, payload: payload, frameType: f.frameType == 'general' ? 0 : 1, toClient: f.toClient == true || f.toClient == 'true',
              ddr: f.ddr == true || f.ddr == 'true', cmd: id]
        if (mfg) r.mfg = mfg
        r.kind = "cmd:${ep}:${cluster}".toString()
        return r
    }
    String attr = hexField(f.attr, 4)
    if (!attr) return [error: "Attribute must be 4 hex digits, not '${f.attr}'".toString()]
    r.attr = attr
    if (action == 'readReport') {
        r += [cmd: 0x08, expectCmds: [0x09, 0x0B]]
        if (mfg) r.mfg = mfg
        r.kind = "report:${ep}:${cluster}".toString()
        return r
    }
    String t = hexField(f.type, 2)
    if (!t) return [error: "Type must be 2 hex digits, not '${f.type}'".toString()]
    int type = Integer.parseInt(t, 16)
    r.type = type
    if (action == 'write') {
        String v = encodeZclValue(type, f.value?.toString())
        if (v == null) return [error: "The value '${f.value}' does not fit type 0x${t}".toString()]
        r += [value: v, cmd: 0x02, expectCmds: [0x04, 0x0B]]
        if (mfg) r.mfg = mfg
        r.kind = "write:${ep}:${cluster}".toString()
        return r
    }
    // configReport
    Integer min = seconds(f.min)
    if (min == null) return [error: "Minimum interval must be 0-65535 seconds, not '${f.min}'".toString()]
    Integer max = seconds(f.max)
    if (max == null) return [error: "Maximum interval must be 0-65535 seconds, not '${f.max}'".toString()]
    // A maximum of 0 (no periodic reports) or 65535 (no reports) is not an interval to compare with.
    if (max != 0 && max != 0xFFFF && min > max) return [error: "The minimum interval (${min} s) is longer than the maximum (${max} s)".toString()]
    String change = null
    if (isAnalogType(type)) {
        if (!f.change?.toString()?.trim()) return [error: "Type 0x${t} needs a reportable change (hex)".toString()]
        change = encodeZclValue(type, f.change.toString())
        if (change == null) return [error: "The change '${f.change}' does not fit type 0x${t}".toString()]
    }
    r += [min: min, max: max, change: change, cmd: 0x06, expectCmds: [0x07, 0x0B]]
    if (mfg) r.mfg = mfg
    r.kind = "report:${ep}:${cluster}".toString()
    return r
}

// The request fields for Bind_req or Unbind_req, or [error: text]: to a group when one is given,
// else to the hub's IEEE address, endpoint dstEp (default 01). f: srcEp, cluster, group, dstEp.
static Map bindFormRequest(String action, Map f, String ieee, String hubIeee, String defaultEp) {
    String srcEp = hexField(f.srcEp ?: defaultEp, 2)
    if (!srcEp) return [error: "Endpoint must be 2 hex digits, not '${f.srcEp}'".toString()]
    if (!zclEpOk(srcEp)) return [error: "${ZCL_EP_RANGE}, not '${srcEp}'".toString()]
    String cluster = hexField(f.cluster, 4)
    if (!cluster) return [error: "Cluster must be 4 hex digits, not '${f.cluster}'".toString()]
    String group = null
    String dstIeee = null
    String dstEp = null
    if (f.group?.toString()?.trim()) {
        group = hexField(f.group, 4)
        if (!group) return [error: "Group must be 4 hex digits, not '${f.group}'".toString()]
    } else {
        if (!(hubIeee ==~ /(?i)[0-9a-f]{16}/)) return [error: "The hub's Zigbee address is not known; give a group instead."]
        dstIeee = hubIeee.toUpperCase()
        dstEp = hexField(f.dstEp ?: '01', 2)
        if (!dstEp) return [error: "Destination endpoint must be 2 hex digits, not '${f.dstEp}'".toString()]
    }
    return [srcEp: srcEp, bindCluster: cluster, dstIeee: dstIeee, dstEp: dstEp, group: group,
            reqCluster: action == 'unbind' ? '0022' : '0021', kind: 'bind']
}

// ---- results ----

// Query Next Image Request payload: field control, manufacturer code, image type, file version.
static Map decodeImageQuery(List<String> p) {
    if (p == null || p.size() < 9) return [:]
    return [mfgCode: hexAt(p, 1, 2), imageType: hexAt(p, 3, 2), fileVersion: hexAt(p, 5, 4)]
}

// What a ZCL reply to a write, configReport, readReport, cmd or ota request means: [status: 'ok',
// data] or [status: 'refused', statusName, error]. A record status in a read of the reporting
// configuration (none configured, say) is part of the answer, not a refusal.
static Map zclResult(Map req, Map header, Map d) {
    String action = req.action as String
    int cmd = header.cmd as int
    if ((header.frameType as int) == 0 && cmd == 0x0B) {
        int st = (d.status ?: 0) as int
        if (st != 0) return [status: 'refused', statusName: d.statusName, error: "The device answered ${d.statusName}.".toString()]
        if (action == 'cmd') return [status: 'ok', data: [ep: req.ep, cluster: req.cluster, cmd: req.cmdId, defaultResponse: d.statusName]]
        return [status: 'ok', data: [ep: req.ep, cluster: req.cluster, defaultResponse: d.statusName]]
    }
    if (action == 'cmd') {
        // A cluster-specific frame is matched by cluster and direction only; a general one is the
        // response command of the general command sent.
        return [status: 'ok', data: [ep: req.ep, cluster: req.cluster, cmd: req.cmdId,
                                     reply: [clusterSpecific: d.clusterSpecific == true, cmd: d.command != null ? d.command : cmd, payload: d.payload,
                                             matchedBy: d.clusterSpecific == true ? 'cluster and direction' : 'response command']]]
    }
    if (action == 'ota') {
        return [status: 'ok', data: [query: decodeImageQuery((List<String>) d.payload)]]
    }
    List records = (List) (d.records ?: [])
    if (action == 'readReport') {
        return [status: 'ok', data: [op: 'read', ep: req.ep, cluster: req.cluster, attr: req.attr, records: records]]
    }
    Map bad = (Map) records.find { (it.status as int) != 0 }
    if (bad) return [status: 'refused', statusName: bad.statusName, error: "The device answered ${bad.statusName}.".toString()]
    if (action == 'write') {
        return [status: 'ok', data: [ep: req.ep, cluster: req.cluster, attr: req.attr, type: req.type, value: req.value, records: records]]
    }
    return [status: 'ok', data: [op: 'configure', ep: req.ep, cluster: req.cluster, attr: req.attr, type: req.type, min: req.min, max: req.max,
                                 change: req.change, records: records]]
}
// ---- monitor devices ----

@Field static final String MONITOR_PREFIX = "zbworkbench-mon-"
@Field static final List<String> POLL_MINUTES = ['15', '30', '60', '180']

static String monitorDni(String ieee) {
    return MONITOR_PREFIX + ieee.toUpperCase()
}

// The IEEE address in a monitor DNI, or null when the DNI is not a monitor's.
static String monitorIeee(String dni) {
    if (dni == null || !dni.startsWith(MONITOR_PREFIX)) return null
    String ieee = dni.substring(MONITOR_PREFIX.length())
    return ieee ==~ /[0-9A-F]{16}/ ? ieee : null
}

// IEEE addresses of monitor devices whose device is no longer picked, sorted. They are listed for
// the user to remove; they are never deleted on their own.
static List<String> orphanMonitors(List<String> dnis, Collection<String> picked) {
    return (dnis ?: []).collect { monitorIeee(it) }.findAll { it != null && !(it in (picked ?: [])) }.unique().sort()
}

// The monitor attributes a neighbour table (and a routing table) gives: neighborCount (the
// table's total), childCount (entries whose relationship is Child), parent (the Parent entry's
// name, "none" without one), lowestLqi ("<lqi> <name>"), routeCount (Active routes). Keys it cannot
// fill are left out. names: [byNwk: [nwk: name], byIeee: [ieee: name]].
static Map monitorValues(Map neighbors, Map routes, Map names) {
    Map out = [:]
    Closure nameOf = { Map e ->
        String n = e.ieee ? names?.byIeee?.get(e.ieee.toString().toUpperCase()) : null
        if (!n && e.nwk) n = names?.byNwk?.get(e.nwk.toString().toUpperCase())
        return n ?: (e.nwk ? e.nwk.toString().toUpperCase() : 'unknown')
    }
    if (neighbors != null) {
        List<Map> entries = (List<Map>) (neighbors.entries ?: [])
        out.neighborCount = neighbors.total != null ? (neighbors.total as int) : entries.size()
        out.childCount = entries.count { it.relationship == 'Child' } as int
        Map par = entries.find { it.relationship == 'Parent' }
        out.parent = par ? nameOf(par) : 'none'
        Map low = entries.findAll { it.lqi != null }.min { it.lqi as int }
        if (low) out.lowestLqi = "${low.lqi} ${nameOf(low)}".toString()
    }
    if (routes != null) {
        out.routeCount = ((List<Map>) (routes.entries ?: [])).count { it.routeStatus == 'Active' } as int
    }
    return out
}

// online when the hub heard from the device within inactiveMinutes, offline after that, unknown
// when it has no time (never heard since the hub started) or the time cannot be read. The hub
// writes lastMessage as 2026-01-02T03:04:05+0000.
static String healthFrom(String lastMessageIso, int inactiveMinutes, long now) {
    Long t = isoMillis(lastMessageIso)
    if (t == null) return 'unknown'
    return now - t > inactiveMinutes * 60000L ? 'offline' : 'online'
}

// Epoch ms of an ISO 8601 time with seconds and a zone (+0000, +00:00 or Z), or null. (The hub
// sandbox refuses java.text.ParsePosition, so the whole string is checked by pattern first.)
// Health of the device with this IEEE from a zigbeeDetails map: unknown when the map has no device
// list (the read failed), offline when the hub no longer lists the device (it left the network),
// otherwise healthFrom its lastMessage (unknown when the hub has no time for it).
static String healthFor(Map details, String ieee, int inactiveMinutes, long now) {
    if (!(details?.devices instanceof List)) return 'unknown'
    Map rec = (Map) ((List) details.devices).find { it instanceof Map && it.zigbeeId?.toString()?.toUpperCase() == ieee?.toUpperCase() }
    if (rec == null) return 'offline'
    return healthFrom(rec.lastMessage?.toString(), inactiveMinutes, now)
}

// The kinds a scheduled poll sends that are not already pending for this device: a poll never
// cancels a request the user started (a new request of the same kind replaces the pending one).
static List<String> pollKinds(List<String> wanted, Map pending, String ieee) {
    Set<String> busy = ((pending ?: [:]).values().findAll { it instanceof Map && it.ieee == ieee }.collect { it.kind?.toString() }) as Set<String>
    return wanted.findAll { !(it in busy) }
}

static Long isoMillis(String iso) {
    def m = iso == null ? null : (iso =~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?([+-]\d{4}|[+-]\d{2}:\d{2}|Z)$/)
    if (m == null || !m.find()) return null
    String zone = m.group(2)
    String f = "yyyy-MM-dd'T'HH:mm:ss" + (m.group(1) ? '.SSS' : '') + (zone.length() == 5 ? 'Z' : 'XXX')
    try {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(f)
        sdf.setLenient(false)
        return sdf.parse(iso).time
    } catch (Exception ignored) {
        return null
    }
}

// Minutes without a message before a device counts as offline, when the user set none: 720 for a
// battery device, 60 for a mains one. From the node descriptor: its MAC capability says whether the
// device is mains powered; without it an End Device counts as battery.
static int defaultInactiveMinutes(Map node) {
    if (node?.macCapability != null) return ((node.macCapability as int) & 0x04) != 0 ? 60 : 720
    return node?.logicalType == 'End Device' ? 720 : 60
}

// One poll per interval at a random second and minute offset (ARCHITECTURE: random jitter on
// recurring schedules). minutes: 15, 30, 60 or 180.
static String pollCron(int minutes, int second, int minute) {
    int s = Math.abs(second) % 60
    if (minutes >= 180) return "${s} ${Math.abs(minute) % 60} ${Math.abs(minute) % 3}/3 ? * *".toString()
    if (minutes >= 60) return "${s} ${Math.abs(minute) % 60} * ? * *".toString()
    int m = minutes >= 30 ? 30 : 15
    return "${s} ${Math.abs(minute) % m}/${m} * ? * *".toString()
}

// ---- interview ----

// A Read Attributes Response must fit one frame. Each record takes 4 bytes plus its value; a
// string is counted at 32 characters, and a type the codec does not size takes a whole read.
@Field static final int READ_BUDGET = 60
@Field static final int READ_MAX_ATTRS = 8

// The discovered attributes ([id, type] maps) split into reads whose replies fit READ_BUDGET.
@CompileStatic
static List<List<Integer>> readChunks(List<Map> attrs) {
    List<List<Integer>> chunks = []
    List<Integer> cur = []
    int used = 0
    for (Map a in attrs) {
        Integer n = zclTypeLength(a.type as int)
        int size = n == null ? READ_BUDGET : 4 + (n > 0 ? n : 32 - n)
        if (cur && (used + size > READ_BUDGET || cur.size() >= READ_MAX_ATTRS)) {
            chunks << cur
            cur = []
            used = 0
        }
        cur << (a.id as int)
        used += size
    }
    if (cur) chunks << cur
    return chunks
}

// Every step the interview has learned it needs so far, in order: node descriptor, active
// endpoints, a simple descriptor per endpoint, then per server cluster of a ZCL endpoint
// (01-F0) attribute discovery and the reads of what was discovered. Each step is [key, action,
// args, kind] (and attrs for a read); its key is unique within the interview.
@CompileStatic
static List<Map> interviewPlan(Map iv) {
    List<Map> steps = new ArrayList<Map>()
    steps << ([key: 'node', action: 'node', args: [], kind: 'node'] as Map)
    steps << ([key: 'endpoints', action: 'endpoints', args: [], kind: 'endpoints'] as Map)
    Map simple = (Map) (iv.simple ?: [:])
    Map disc = (Map) (iv.disc ?: [:])
    List<String> eps = (List<String>) (iv.endpoints ?: [])
    for (String ep in eps) steps << [key: "simple:${ep}".toString(), action: 'simple', args: [ep], kind: "simple:${ep}".toString()]
    for (String ep in eps) {
        int e = Integer.parseInt(ep, 16)
        Map sd = (Map) simple[ep]
        if (sd == null || e < 0x01 || e > 0xF0) continue
        for (String cl in (List<String>) (sd.inClusters ?: [])) {
            String k = "${ep}:${cl}".toString()
            steps << [key: "discover:${k}".toString(), action: 'discover', args: [ep, cl], kind: "discover:${k}".toString()]
            List<Map> attrs = (List<Map>) disc[k]
            if (!attrs) continue
            for (List<Integer> c in readChunks(attrs)) {
                List<String> ids = c.collect { String.format('%04X', it) }
                steps << [key: "read:${k}:${ids[0]}".toString(), action: 'read', args: [ep, cl, ids.join(',')],
                          kind: "read:${k}".toString(), attrs: ids]
            }
        }
    }
    if (iv.report) steps.addAll(reportSteps(iv))
    return steps
}

// The first planned step not yet tried, or null when the interview is done.
@CompileStatic
static Map interviewNext(Map iv) {
    List tried = (List) (iv.tried ?: [])
    return interviewPlan(iv).find { !(it.key in tried) }
}

// Records a finished step's result in the interview: the data on success, else the reason.
@CompileStatic
static void interviewRecord(Map iv, Map step, Map entry) {
    String key = (String) step.key
    List args = (List) step.args
    if (entry.status != 'ok') {
        if (iv.failed == null) iv.failed = [:]
        ((Map) iv.failed)[key] = "${entry.status}${entry.error ? ': ' + entry.error : ''}".toString()
        return
    }
    Map d = (Map) (entry.data ?: [:])
    String action = (String) step.action
    if (action == 'node') iv.node = d
    else if (action == 'endpoints') iv.endpoints = (List) (d.endpoints ?: [])
    else if (action == 'simple') {
        if (iv.simple == null) iv.simple = [:]
        ((Map) iv.simple)[(String) args[0]] = d
    } else if (action == 'discover') {
        if (iv.disc == null) iv.disc = [:]
        ((Map) iv.disc)["${args[0]}:${args[1]}".toString()] = ((List<Map>) (d.attrs ?: [])).collect { Map a -> [id: a.id, type: a.type] }
    } else if (action == 'read') {
        String k = "${args[0]}:${args[1]}".toString()
        if (iv.vals == null) iv.vals = [:]
        Map got = (Map) (((Map) iv.vals)[k] ?: [:])
        List<String> want = (List<String>) step.attrs
        for (Map r in (List<Map>) (d.records ?: [])) {
            String hex = String.format('%04X', r.attr as int)
            if (!(hex in want)) continue
            Map<String, Object> rec = new LinkedHashMap<String, Object>()
            for (String f in ['status', 'statusName', 'type', 'value']) rec.put(f, r.get(f))
            got.put(hex, rec)
        }
        ((Map) iv.vals)[k] = got
    } else if (action == 'reportcfg') {
        String k = "${args[0]}:${args[1]}".toString()
        if (iv.rep == null) iv.rep = [:]
        Map got = (Map) (((Map) iv.rep)[k] ?: [:])
        for (Map r in (List<Map>) (d.records ?: [])) {
            if (r.get('direction') != null && (r.get('direction') as int) != 0) continue
            Map<String, Object> rec = new LinkedHashMap<String, Object>()
            for (String f in ['status', 'statusName', 'min', 'max', 'change']) rec.put(f, r.get(f))
            got.put(String.format('%04X', r.attr as int), rec)
        }
        ((Map) iv.rep)[k] = got
    }
}

// ---- shareable report ----

@Field static final int FORUM_MAX_CHARS = 30000
@Field static final int REPORTS_KEPT = 3
// A result younger than this is reused by a report run instead of being asked for again.
@Field static final long REPORT_FRESH_MS = 600000L
// Read Reporting Configuration skips the clusters whose attributes devices do not report.
@Field static final List<String> REPORTCFG_SKIP = ['0000', '0003', '0004', '0005', '0019']
// A reply record is up to 9 bytes plus the reportable change; 4 attributes fit one frame.
@Field static final int REPORTCFG_MAX_ATTRS = 4
@Field static final Map<Integer, String> POWER_SOURCES = [0: 'Unknown', 1: 'Mains (single phase)', 2: 'Mains (3 phase)', 3: 'Battery',
                                                         4: 'DC source', 5: 'Emergency mains, constant', 6: 'Emergency mains, transfer switch']

// The steps a report adds after the interview: the network tables not read within
// REPORT_FRESH_MS (iv.fresh; routes only for a device that is not an end device) and the
// reporting configuration of each server cluster's discovered attributes.
@CompileStatic
static List<Map> reportSteps(Map iv) {
    List<Map> steps = []
    List fresh = (List) (iv.fresh ?: [])
    List<String> tables = ['neighbors', 'bindings']
    if (((Map) iv.node)?.logicalType != 'End Device') tables.add(1, 'routes')
    for (String t in tables) if (!(t in fresh)) steps << [key: t, action: t, args: [], kind: t]
    Map disc = (Map) (iv.disc ?: [:])
    for (Object k0 in disc.keySet().sort()) {
        String k = k0.toString()
        List<String> kp = k.split(':') as List<String>
        if (kp[1] in REPORTCFG_SKIP) continue
        List<String> ids = ((List<Map>) disc[k]).collect { (it.id as int) }.findAll { it < 0xFFFD }.collect { String.format('%04X', it) }
        for (int i = 0; i < ids.size(); i += REPORTCFG_MAX_ATTRS) {
            List<String> c = ids.subList(i, Math.min(ids.size(), i + REPORTCFG_MAX_ATTRS))
            steps << [key: "reportcfg:${k}:${c[0]}".toString(), action: 'reportcfg', args: [kp[0], kp[1], c.join(',')],
                      kind: "reportcfg:${k}".toString(), attrs: new ArrayList<String>(c)]
        }
    }
    return steps
}

// Device-name aliases, a port of makeObf in Hub Inspector's UI so a hub's names get the same
// alias in both reports: FNV-1a over UTF-16 code units, the first free adjective-noun pair from
// the hash, names primed longest first then in String order.
@Field static final List<String> ALIAS_ADJECTIVES = ['amber', 'azure', 'brave', 'brisk', 'bronze', 'calm', 'clever', 'coral', 'crimson', 'dapper',
    'eager', 'fierce', 'frosty', 'gentle', 'glossy', 'golden', 'grand', 'hardy', 'hidden', 'humble', 'icy', 'indigo', 'jolly', 'keen', 'lively',
    'lucky', 'merry', 'misty', 'mossy', 'noble', 'olive', 'plucky', 'quiet', 'rapid', 'rosy', 'rustic', 'sandy', 'silent', 'silver', 'sleek',
    'smooth', 'snowy', 'solid', 'spry', 'stormy', 'sturdy', 'sunny', 'swift', 'tame', 'tidy', 'vivid', 'warm', 'wild', 'witty', 'zesty']
@Field static final List<String> ALIAS_NOUNS = ['acorn', 'badger', 'beacon', 'beetle', 'bramble', 'canyon', 'cedar', 'cliff', 'comet', 'cricket',
    'dahlia', 'delta', 'ember', 'falcon', 'ferret', 'finch', 'garnet', 'geode', 'glade', 'harbor', 'heron', 'iris', 'jade', 'kestrel', 'lagoon',
    'lichen', 'lotus', 'marble', 'meadow', 'mesa', 'moss', 'newt', 'onyx', 'opal', 'otter', 'pebble', 'plume', 'quartz', 'quill', 'raven', 'reef',
    'river', 'sage', 'shoal', 'silt', 'slate', 'spark', 'spruce', 'sumac', 'thistle', 'tundra', 'vale', 'vine', 'weave', 'willow', 'yarrow']

@CompileStatic
static long aliasHash(String s) {
    int hv = (int) 2166136261L
    for (int i = 0; i < s.length(); i++) {
        hv = hv ^ (int) s.charAt(i)
        hv = hv * 16777619
    }
    return ((long) hv) & 0xFFFFFFFFL
}

@CompileStatic
static Map<String, String> makeAliases(List<String> names) {
    List<String> adj = ALIAS_ADJECTIVES
    List<String> nouns = ALIAS_NOUNS
    List<String> reg = new ArrayList<String>()
    for (String n in names) if (n != null && n != '' && !reg.contains(n)) reg.add(n)
    reg.sort { String a, String b -> b.length() != a.length() ? b.length() - a.length() : a.compareTo(b) }
    Map<String, String> map = new LinkedHashMap<String, String>()
    Set<String> used = new HashSet<String>()
    for (String key in reg) {
        long hv = aliasHash(key)
        int ai = (int) (hv % adj.size())
        int ni = (int) ((hv >>> 8) % nouns.size())
        String a = null
        for (int tries = 0; tries < adj.size() * nouns.size(); tries++) {
            a = adj[ai] + '-' + nouns[ni]
            if (!used.contains(a)) break
            ni++
            if (ni >= nouns.size()) { ni = 0; ai = (ai + 1) % adj.size() }
        }
        if (used.contains(a)) a = a + '-' + map.size()
        used.add(a)
        map.put(key, a)
    }
    return map
}

// An IEEE address keeps its first 3 bytes (the manufacturer's OUI): 282C02XXXXXXXXXX.
@CompileStatic
static String maskIeee(Object ieee) {
    String s = ieee?.toString()?.toUpperCase()
    if (!s || !(s ==~ /[0-9A-F]{16}/)) return s
    return s.substring(0, 6) + 'XXXXXXXXXX'
}

// Masks each IEEE address in `ids` inside a frame or payload written as hex bytes, in either
// byte order, keeping its first 3 bytes; `full` ids (extended PAN IDs) lose all 8.
@CompileStatic
static String maskHex(String text, List ids, List full) {
    if (!text) return text
    String out = text
    // hex digits and spaces are literal in a regex
    Closure<String> sub = { String src, String find, String put -> src.replaceAll('(?i)' + find, put) }
    for (Object id0 in ((ids ?: []) + (full ?: []))) {
        String id = id0?.toString()?.toUpperCase()
        if (!id || !(id ==~ /[0-9A-F]{16}/)) continue
        boolean all = (full ?: []).any { it?.toString()?.equalsIgnoreCase(id) }
        List<String> be = (0..7).collect { id.substring(2 * it, 2 * it + 2) }
        List<String> le = be.reverse()
        List<String> beM = all ? (0..7).collect { 'XX' } : be.take(3) + (3..7).collect { 'XX' }
        List<String> leM = beM.reverse()
        out = sub(sub(out, be.join(''), beM.join('')), le.join(''), leM.join(''))
        out = sub(sub(out, be.join(' '), beM.join(' ')), le.join(' '), leM.join(' '))
    }
    return out
}

// File Manager name prefix for a model: characters outside A-Z a-z 0-9 _ - become _.
@CompileStatic
static String reportFilePrefix(String model) {
    String m = (model ?: 'unknown').replaceAll(/[^A-Za-z0-9_-]/, '_')
    return 'zigbee-workbench-' + m + '-'
}

// The saved reports beyond the newest `keep` with this prefix, oldest first: what to delete.
@CompileStatic
static List<String> reportsToDelete(List<Map> files, String prefix, int keep) {
    // exactly <prefix><yyyyMMdd-HHmm>.md: a model whose name goes on after a dash is another model
    List<String> mine = new ArrayList<String>()
    for (Map f in files) {
        String n = f.get('name')?.toString()
        if (n != null && n.startsWith(prefix) && n.substring(prefix.length()) ==~ /\d{8}-\d{4}\.md/) mine.add(n)
    }
    Collections.sort(mine)
    return mine.size() > keep ? new ArrayList<String>(mine.subList(0, mine.size() - keep)) : new ArrayList<String>()
}

// The report's content: only these fields. `inp` carries the device (ieee, nwk, name), the
// interview data (iv), its results, the hub's record of it, its driver, the hub, the names by
// IEEE and short address, the run start, the app version and the time.
static Map reportData(Map inp) {
    Map iv = (Map) (inp.iv ?: [:])
    Map res = (Map) (inp.results ?: [:])
    Map node = (Map) iv.node
    Map vals = (Map) (iv.vals ?: [:])
    Map rep = (Map) (iv.rep ?: [:])
    Map names = (Map) (inp.names ?: [:])
    Closure nameOf = { Object nwk, Object ieee ->
        String n = ieee ? names.byIeee?.get(ieee.toString().toUpperCase()) : null
        if (!n && nwk) n = names.byNwk?.get(nwk.toString().toUpperCase())
        return n
    }
    List<String> eps = (List<String>) (iv.endpoints ?: [])
    Closure basic = { String attr ->
        for (String ep in eps) {
            Map r = (Map) ((Map) vals[ep + ':0000'])?.get(attr)
            if (r != null && r.status == 0) return r.value
        }
        return null
    }
    Object ps = basic('0007')
    Map drv = (Map) (inp.driver ?: [:])
    Map drvData = (Map) (drv.data ?: [:])
    Map device = [name: inp.name, ieee: inp.ieee, nwk: inp.nwk, manufacturer: basic('0004'), model: basic('0005'),
                  applicationVersion: basic('0001'), stackVersion: basic('0002'), firmwareMT: drvData.firmwareMT,
                  powerSource: ps == null ? null : "${POWER_SOURCES[(ps as int) & 0x7F] ?: 'Reserved'}${((ps as int) & 0x80) ? ', battery backup' : ''}".toString(),
                  logicalType: node?.logicalType, manufacturerCode: node?.mfgCode]
    Map driver = [name: drv.name, namespace: drv.namespace, version: drvData.version]
    List endpoints = eps.collect { String ep ->
        Map sd = (Map) ((Map) (iv.simple ?: [:]))[ep] ?: [:]
        [ep: ep, profileId: sd.profileId, deviceId: sd.deviceId, inClusters: sd.inClusters ?: [], outClusters: sd.outClusters ?: []]
    }
    List attributes = []
    ((Map) (iv.disc ?: [:])).keySet().sort().each { Object k0 ->
        String k = k0.toString()
        List<String> kp = k.split(':') as List<String>
        Map cn = (Map) ZCL_ATTR_NAMES[Integer.parseInt(kp[1], 16)]
        Map got = (Map) (vals[k] ?: [:])
        Map rc = (Map) (rep[k] ?: [:])
        List attrs = ((List<Map>) iv.disc[k]).sort(false) { it.id as int }.collect { Map a ->
            String hex = String.format('%04X', a.id as int)
            Map r = (Map) got[hex]
            Map c = (Map) rc[hex]
            boolean cfg = c != null && (c.status as int) == 0
            [id: hex, name: (cn?.attrs?.get(a.id as int) ?: ZCL_GLOBAL_ATTRS[a.id as int]), type: ZCL_TYPE_NAMES[a.type as int] ?: String.format('0x%02X', a.type as int),
             value: r != null && (r.status as int) == 0 ? r.value?.toString() : null, status: r == null ? 'not read' : ((r.status as int) == 0 ? null : r.statusName),
             min: cfg ? c.min : null, max: cfg ? c.max : null, change: cfg ? c.change?.toString() : null]
        }
        attributes << [ep: kp[0], cluster: kp[1], name: cn?.name, attrs: attrs]
    }
    Map bres = (Map) res.bindings
    List bindings = bres?.status == 'ok' ? ((List<Map>) (bres.data?.entries ?: [])).collect {
        [srcEp: it.srcEp, cluster: it.cluster, dstName: it.dstGroup ? null : nameOf(null, it.dstIeee), dstIeee: it.dstIeee, dstEp: it.dstEp, dstGroup: it.dstGroup]
    } : null
    Map nres = (Map) res.neighbors
    List<Map> nb = nres?.status == 'ok' ? (List<Map>) (nres.data?.entries ?: []) : null
    Map rres = (Map) res.routes
    Map par = nb?.find { it.relationship == 'Parent' }
    Map rec = (Map) (inp.record ?: [:])
    Long last = isoMillis(rec.lastMessage?.toString())
    Map lastHop = [res.neighbors, res.routes, res.bindings, res.node, res.endpoints].findAll { it instanceof Map && it.lqi != null }.max { (it.time ?: 0) as long }
    Map mesh = [parent: par ? (nameOf(par.nwk, par.ieee) ?: par.nwk) : (nb != null ? 'none' : null),
                neighbors: nb?.collect { [name: nameOf(it.nwk, it.ieee), nwk: it.nwk, ieee: it.ieee, relationship: it.relationship, deviceType: it.deviceType, lqi: it.lqi] },
                routes: rres?.status == 'ok' ? ((List<Map>) (rres.data?.entries ?: [])).collect {
                    [dest: nameOf(it.dest, null), destNwk: it.dest, nextHop: nameOf(it.nextHop, null), nextHopNwk: it.nextHop, status: it.routeStatus] } : null,
                lastHopLqi: lastHop?.lqi, lastHopRssi: lastHop?.rssi, messageCount: rec.messageCount,
                minutesSinceMessage: last == null ? null : (int) (((inp.now as long) - last).intdiv(60000L))]
    Map hub = (Map) (inp.hub ?: [:])
    long started = (inp.started ?: 0) as long
    Closure fail = { String kind, Map v -> [kind: kind, status: v.status, error: v.error, frame: v.frame, raw: v.raw instanceof List ? ((List) v.raw).join(' ') : v.raw] }
    List failures = res.findAll { k, v -> v instanceof Map && !(v.status in ['ok', 'sent', 'running']) && ((v.time ?: 0) as long) >= started && k != 'report' }
        .collect { k, v -> fail(k.toString(), (Map) v) }
    // the interview's failed steps, also when the report reused an earlier interview
    ((Map) (iv.failed ?: [:])).each { k, why ->
        List<String> kp = k.toString().split(':') as List<String>
        String kind = (kp[0] in ['read', 'reportcfg'] && kp.size() == 4) ? kp.take(3).join(':') : k.toString()
        if (failures.any { it.kind == kind }) return
        Map v = (Map) res[kind]
        if (v != null && v.status != 'ok') { failures << fail(kind, v); return }
        List<String> w = why.toString().split(': ', 2) as List<String>
        failures << [kind: kind, status: w[0], error: w.size() > 1 ? w[1] : null, frame: null, raw: null]
    }
    return [device: device, driver: driver, endpoints: endpoints, attributes: attributes, bindings: bindings, mesh: mesh,
            hub: [model: hub.model, firmware: hub.firmware, channel: hub.channel, powerLevel: hub.powerLevel],
            run: [appVersion: inp.version, time: inp.time], failures: failures]
}

// The report as Markdown for a forum post: at most opts.maxChars (FORUM_MAX_CHARS) characters,
// attribute values cut first and then the attribute list, each cut said in the text.
static String renderForum(Map data, Map opts) {
    int max = (opts?.maxChars ?: FORUM_MAX_CHARS) as int
    String full = renderMd(data, opts, 0)
    if (full.length() <= max) return full
    String noValues = renderMd(data, opts, 1)
    if (noValues.length() <= max) return noValues
    String noAttrs = renderMd(data, opts, 2)
    if (noAttrs.length() <= max) return noAttrs
    String cut = '\n\n*Cut to fit a post. The full report is in the attached file.*'
    return noAttrs.substring(0, max - cut.length()) + cut
}

// The same Markdown with nothing cut, for the File Manager copy.
static String renderFull(Map data, Map opts) {
    return renderMd(data, opts, 0)
}

@CompileStatic
private static String mdCell(Object v) {
    return v == null ? '–' : v.toString().replace('|', '\\|').replace('\n', ' ')
}

// level 0: everything; 1: attribute values cut; 2: attribute list cut.
static String renderMd(Map data, Map opts, int level) {
    boolean useAliases = opts?.aliases != false
    Map aliasMap = (Map) (opts?.aliasMap ?: [:])
    Closure nm = { Object n ->
        if (n == null) return null
        if (!useAliases || n == 'Hub') return n.toString()
        return aliasMap[n.toString()] ?: 'unlisted device'
    }
    List ieees = [data.device?.ieee] + ((List) (data.mesh?.neighbors ?: [])).collect { it.ieee } + ((List) (data.bindings ?: [])).collect { it.dstIeee }
    Closure ie = { Object v -> maskIeee(v) }
    Closure mx = { Object v -> v == null ? null : maskHex(v.toString(), ieees.findAll { it }, (List) (opts?.extPans ?: [])) }
    Map d = (Map) data.device ?: [:]
    Map drv = (Map) data.driver ?: [:]
    Map hub = (Map) data.hub ?: [:]
    Map mesh = (Map) data.mesh ?: [:]
    List<String> L = []
    L << "## Zigbee Workbench report: ${mdCell(nm(d.name))}".toString()
    L << ''
    L << '| Field | Value |'
    L << '|---|---|'
    [['Manufacturer', d.manufacturer], ['Model', d.model], ['Application version', d.applicationVersion], ['Stack version', d.stackVersion],
     ['Firmware (firmwareMT)', d.firmwareMT], ['Power source', d.powerSource], ['Node type', d.logicalType],
     ['Manufacturer code', d.manufacturerCode ? '0x' + d.manufacturerCode : null], ['IEEE', ie(d.ieee)], ['Short address', d.nwk],
     ['Driver', drv.name ? "${drv.name} (${drv.namespace ?: '?'})${drv.version ? ' ' + drv.version : ''}" : null],
     ['Messages', mesh.messageCount], ['Minutes since last message', mesh.minutesSinceMessage],
     ['Parent', mesh.parent == 'none' ? 'none' : nm(mesh.parent)], ['Last-hop LQI / RSSI', mesh.lastHopLqi != null ? "${mesh.lastHopLqi} / ${mesh.lastHopRssi}" : null],
     ['Hub', [hub.model, hub.firmware ? 'firmware ' + hub.firmware : null, hub.channel != null ? 'channel ' + hub.channel : null,
              hub.powerLevel != null ? 'power level ' + hub.powerLevel : null].findAll { it }.join(', ') ?: null],
     ['Report', "Zigbee Workbench ${data.run?.appVersion}, ${data.run?.time}"]].each { L << "| ${it[0]} | ${mdCell(it[1])} |".toString() }
    L << ''
    L << '[details="Endpoints and bindings"]'
    ((List) (data.endpoints ?: [])).each { Map e ->
        L << "Endpoint ${e.ep}: profile 0x${e.profileId}, device 0x${e.deviceId}".toString()
        L << "- Server clusters: ${((List) e.inClusters).collect { clusterLabel(it.toString(), ZCL_ATTR_NAMES) }.join(', ') ?: 'none'}".toString()
        L << "- Client clusters: ${((List) e.outClusters).collect { clusterLabel(it.toString(), ZCL_ATTR_NAMES) }.join(', ') ?: 'none'}".toString()
        L << ''
    }
    if (data.bindings == null) L << 'Bindings: not read'
    else {
        L << '| Source endpoint | Cluster | Destination | Destination endpoint |'
        L << '|---|---|---|---|'
        ((List) data.bindings).each { Map b ->
            String dst = b.dstGroup ? "group ${b.dstGroup}" : "${nm(b.dstName) ?: 'unlisted device'} (${ie(b.dstIeee)})"
            L << "| ${b.srcEp} | ${b.cluster} | ${mdCell(dst)} | ${mdCell(b.dstEp)} |".toString()
        }
    }
    L << '[/details]'
    L << ''
    List attrsAll = (List) (data.attributes ?: [])
    int count = attrsAll.sum(0) { ((List) it.attrs).size() } as int
    if (level >= 2) {
        L << "*${count} attributes cut to fit a post. Full attribute list in the attached report.*"
    } else {
        L << "[details=\"Attributes (${count})\"]".toString()
        if (level == 1) L << '*Attribute values cut to fit a post. Full attribute list in the attached report.*'
        attrsAll.each { Map c ->
            L << ''
            L << "**Endpoint ${c.ep} · 0x${c.cluster}${c.name ? ' ' + c.name : ''}**".toString()
            if (level == 1) {
                L << ((List) c.attrs).collect { "0x${it.id}${it.name ? ' ' + it.name : ''} (${it.type})" }.join(', ')
                return
            }
            L << ''
            L << '| Attribute | Type | Value | Reporting min / max / change |'
            L << '|---|---|---|---|'
            ((List) c.attrs).each { Map a ->
                String v = a.status ? a.status : a.value
                String r = a.min != null ? "${a.min} s / ${a.max} s / ${a.change ?: '–'}" : null
                L << "| 0x${a.id}${a.name ? ' ' + a.name : ''} | ${a.type} | ${mdCell(v)} | ${mdCell(r)} |".toString()
            }
        }
        L << '[/details]'
    }
    L << ''
    L << '[details="Mesh"]'
    if (mesh.neighbors == null) L << 'Neighbours: not read'
    else {
        L << "Neighbours (${((List) mesh.neighbors).size()}):".toString()
        L << ''
        L << '| Device | Address | IEEE | Type | Relationship | LQI |'
        L << '|---|---|---|---|---|---|'
        ((List) mesh.neighbors).each { Map n ->
            L << "| ${mdCell(nm(n.name) ?: 'unlisted device')} | ${n.nwk} | ${ie(n.ieee)} | ${mdCell(n.deviceType)} | ${mdCell(n.relationship)} | ${mdCell(n.lqi)} |".toString()
        }
    }
    if (mesh.routes != null) {
        L << ''
        L << "Routes (${((List) mesh.routes).size()}):".toString()
        L << ''
        L << '| Destination | Next hop | Status |'
        L << '|---|---|---|'
        ((List) mesh.routes).each { Map r ->
            L << "| ${mdCell(r.dest ? nm(r.dest) : null)} (${r.destNwk}) | ${mdCell(r.nextHop ? nm(r.nextHop) : null)} (${r.nextHopNwk}) | ${mdCell(r.status)} |".toString()
        }
    }
    L << '[/details]'
    List fails = (List) (data.failures ?: [])
    L << ''
    L << "[details=\"Failures (${fails.size()})\"]".toString()
    if (!fails) L << 'None.'
    fails.each { Map f ->
        L << "- `${f.kind}`: ${f.status}${f.error ? ' — ' + f.error : ''}".toString()
        if (f.frame) L << "  - request `${mx(f.frame)}`".toString()
        if (f.raw) L << "  - reply `${mx(f.raw)}`".toString()
    }
    L << '[/details]'
    return L.join('\n')
}
// ==== Engine end ====

// LIFECYCLE

void installed() {
    logCfg "installed v${CODE_VERSION}"
    initialize()
}

void updated() {
    logCfg "updated"
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    checkVersion(false)
    if (debugEnable) runIn(1800, "logsOff")
    ensureRadio()
    ensureRadioSocket()
    syncTargets()
    subscribe(location, "systemStart", "systemStartHandler")
    scheduleMonitors()
    state.watchSent = null
    if (state.pending) flushRadio()
}

void uninstalled() {
    hubCache.keySet().removeAll { it.startsWith("${app.id}:") }
}

void systemStartHandler(evt) {
    checkVersion()
    failAllPending("The hub restarted")
    state.watchSent = null
    flushRadio()
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    logWarn "debug logging disabled"
}

private void ensureRadio() {
    if (getChildDevice(RADIO_DNI)) return
    try {
        addChildDevice("iamtrep", RADIO_DRIVER, RADIO_DNI, [name: RADIO_DRIVER, label: RADIO_DRIVER, isComponent: true])
        logCfg "created the ${RADIO_DRIVER} device"
    } catch (Exception e) {
        logError "could not create the ${RADIO_DRIVER} device (is its driver installed?): ${e.message}"
    }
}

// The radio keeps its log socket open from its own initialize(); after a code push or a radio
// created before that rule, nothing has opened it yet. radio.initialize() only schedules the
// connect, so the radio never reports back to the app from inside this call.
private void ensureRadioSocket() {
    def radio = getChildDevice(RADIO_DNI)
    if (radio == null) return
    String known = radio.socketKnownStatus()?.toString()
    String ws = (known ?: radio.currentValue("wsStatus", true))?.toString()
    if (ws == null || ws == "disconnected") {
        logCfg "opening the ${RADIO_DRIVER} log socket (was ${ws})"
        radio.initialize()
    } else if (ws == "failed" || (known == null && ws == "connecting")) {
        // after a radio code push only the attribute is left, and it can be stale: close and reopen
        logCfg "reopening the ${RADIO_DRIVER} log socket (was ${ws}${known == null ? ', from the attribute' : ''})"
        radio.reopen()
    }
}

// Rebuilds state.targets ([ieee: [deviceId, name, ep]]) from the picked devices, and drops results
// for devices no longer picked. Never stores the DeviceWrapper.
private void syncTargets() {
    Map<String, Map> targets = [:]
    List<Map> other = []
    for (d in (settings.zbDevices ?: [])) {
        Object zid = null
        Object eid = null
        try {
            zid = d.zigbeeId
            eid = d.endpointId
        } catch (Exception ignored) { }
        Map c = classifyPick(zid, eid)
        if (c.zigbee) {
            targets[(String) c.ieee] = [deviceId: d.id.toString(), name: d.displayName?.toString(), ep: c.ep]
        } else {
            other << [deviceId: d.id.toString(), name: d.displayName?.toString()]
        }
    }
    state.targets = targets
    state.nonZigbee = other
    markOrphansUnknown()
    if (state.results) state.results = state.results.findAll { k, v -> targets.containsKey(k) }
    if (state.interviews) state.interviews = state.interviews.findAll { k, v -> targets.containsKey(k) }
}

// HUB DATA (loopback, cached HUB_CACHE_MS per app)

Map fetchZigbeeDetails(boolean fresh = false) {
    return cachedHubGet("details", "/hub/zigbeeDetails/json", "Zigbee details", fresh)
}

// The coordinator's own tables. Right after a reboot the first read can come back empty while
// topologyRefreshing is true; such a read is not cached.
Map fetchChildAndRouteInfo(boolean fresh = false) {
    return cachedHubGet("routes", "/hub/zigbee/getChildAndRouteInfoJson", "Zigbee child and route tables", fresh)
}

private Map cachedHubGet(String name, String path, String label, boolean fresh) {
    String key = "${app.id}:${name}"
    Map hit = hubCache.get(key)
    if (!fresh && hit != null && now() - (hit.time as long) < HUB_CACHE_MS) return (Map) hit.data
    Map data = hubGet(path, label)
    if (data == null) return hit != null ? (Map) hit.data : [:]
    boolean incomplete = data.topologyRefreshing == true && !data.neighbors && !data.routes
    if (!incomplete) hubCache.put(key, [time: now(), data: data])
    return data
}

private Map hubGet(String path, String label) {
    Map result = null
    long start = now()
    try {
        httpGet([uri: HUB_BASE, path: path, contentType: "application/json", timeout: 15]) { resp ->
            if (resp.success && resp.data instanceof Map) result = (Map) resp.data
        }
        logNet "fetched ${label} in ${now() - start} ms"
    } catch (Exception e) {
        logWarn "could not read ${label}: ${e.message}"
    }
    return result
}

// BUTTONS

void appButtonHandler(String btn) {
    checkVersion()
    List<String> parts = btn?.split(':') as List<String>
    if (!parts || parts[0] != 'zbw' || parts.size() < 2) return
    String action = parts[1]
    if (action == 'hubTables') {
        refreshHubTables(0)
        return
    }
    if (parts.size() < 3) return
    expireOverdue()
    if (action == 'confirm') {
        confirmAction(parts[2])
        return
    }
    if (action == 'cancel') {
        consumeConfirmToken(parts[2])
        if (state.confirmView?.token == parts[2]) state.confirmView = state.confirmView + [cancelled: true]
        return
    }
    String ieee = parts[2].toUpperCase()
    List<String> args = parts.size() > 3 ? parts.subList(3, parts.size()) : []
    if (action == 'unmonitor') {
        prepareUnmonitor(ieee)
        return
    }
    if (action in CHANGE_ACTIONS) {
        prepareChange(ieee, action, args)
        return
    }
    if (action == 'monitor') {
        createMonitor(ieee)
        return
    }
    if (action == 'poll') {
        pollDevice(ieee)
        return
    }
    if (action == 'interview') {
        startInterview(ieee)
        return
    }
    if (action == 'report') {
        startReport(ieee)
        return
    }
    if (!(action in ZDO_ACTIONS.keySet()) && !(action in REQUEST_ACTIONS)) {
        logWarn "unknown action ${action}"
        return
    }
    startRequest(ieee, action, args)
}

@Field static final List<String> REQUEST_ACTIONS = ['read', 'discover', 'readReport']
// The buttons that change a device. Each goes through the confirmation page.
@Field static final List<String> CHANGE_ACTIONS = ['write', 'configReport', 'cmd', 'bind', 'unbind', 'raw', 'leave', 'ota']

// CONFIRMATION (every action that changes a device, and monitor removal)
//
// Those buttons only prepare: they check the form, build the frame for the device's current
// address, issue a single-use token valid for CONFIRM_TTL_MS and point state.confirmView at it.
// The confirmation page shows that frame; it is sent only when the page's Confirm button posts
// the token, and only if the device still has that address.

String issueConfirmToken(String action, String ieee, Map args = null) {
    String token = UUID.randomUUID().toString()
    Map tokens = (Map) (state.confirmTokens ?: [:])
    tokenIssue(tokens, token, action, ieee, args, now())
    state.confirmTokens = tokens
    return token
}

boolean consumeConfirmToken(String token) {
    return takeConfirmToken(token) != null
}

private Map takeConfirmToken(String token) {
    Map tokens = (Map) (state.confirmTokens ?: [:])
    Map e = tokenTake(tokens, token, now())
    state.confirmTokens = tokens
    return e
}

private void prepareUnmonitor(String ieee) {
    def mon = getChildDevice(monitorDni(ieee))
    if (mon == null) {
        logWarn "${ieee} has no monitor device"
        return
    }
    String token = issueConfirmToken('unmonitor', ieee, [:])
    state.confirmView = [token: token, action: 'unmonitor', ieee: ieee, args: [:], issued: now()]
    logCfg "${mon.displayName}: removal waits for confirmation"
}

private void prepareChange(String ieee, String action, List<String> args) {
    Map tgt = (Map) state.targets?.get(ieee)
    if (tgt == null) {
        logWarn "${ieee} is not one of the picked devices"
        return
    }
    String cmd = settings.w_raw?.toString()?.trim()
    Map req = action == 'raw' ? [ieee: ieee, action: 'raw', kind: 'raw'] : buildRequest(ieee, action, args)
    if (req == null) return
    Map t = currentTarget(ieee, true)
    if (t == null) {
        storeResult(ieee, (String) req.kind, [status: 'error', error: 'The device is not on the Zigbee network.'])
        return
    }
    String frame
    String warning
    if (action == 'raw') {
        String err = rawCommandError(cmd, (String) t.nwk)
        if (err) {
            inputError(err)
            return
        }
        frame = cmd
        warning = rawWarning(cmd)
    } else {
        frame = buildRequestFrame(req + [addr: t.nwk])
        warning = requestWarning(action, req)
    }
    state.remove("inputError")
    Map a = [req: req, nwk: t.nwk, frame: frame, warning: warning]
    String token = issueConfirmToken(action, ieee, a)
    state.confirmView = [token: token, action: action, ieee: ieee, args: a, issued: now()]
    logCfg "${tgt.name}: ${action} waits for confirmation"
}

private void confirmAction(String token) {
    // A raw command waits for the device's pending requests; the token stays valid meanwhile.
    Map peek = (Map) state.confirmTokens?.get(token)
    String wait = peek?.action == 'raw' ? rawSendBlock((Map) state.pending, (String) peek.args?.nwk) : null
    if (wait) {
        if (state.confirmView?.token == token) state.confirmView = state.confirmView + [wait: wait]
        return
    }
    Map e = takeConfirmToken(token)
    if (e == null) {
        logWarn "confirmation refused: unknown, used or expired"
        confirmFailed(token, "This confirmation expired or was already used.")
        return
    }
    String ieee = (String) e.ieee
    if (e.action == 'unmonitor') {
        confirmDone(token)
        removeMonitor(ieee)
        return
    }
    Map t = currentTarget(ieee, true)
    if (t == null) {
        confirmFailed(token, "The device is no longer on the Zigbee network. Nothing was sent.")
        return
    }
    if (t.nwk != e.args?.nwk) {
        confirmFailed(token, "The device's address changed to ${t.nwk} since the frame was built. Nothing was sent.".toString())
        return
    }
    confirmDone(token)
    if (e.action == 'raw') {
        sendRawFrame(ieee, (String) e.args.frame)
    } else {
        sendRequest((Map) e.args.req, t)
    }
}

private void confirmDone(String token) {
    if (state.confirmView?.token == token) state.confirmView = state.confirmView + [done: true]
}

private void confirmFailed(String token, String msg) {
    if (state.confirmView?.token == token) state.confirmView = state.confirmView + [error: msg]
}

// MONITOR DEVICES
//
// One optional child device per watched device (DNI zbworkbench-mon-<IEEE>), output only: the app
// pushes values with updateValues(); its refresh() calls monitorRefresh() from its own execution.
// A monitor whose device was dropped from the picker is listed as orphaned and only removed when
// the user confirms; removal always goes through the confirmation page.

private List monitorDevices() {
    return getChildDevices().findAll { monitorIeee(it.deviceNetworkId?.toString()) != null }
}

private void createMonitor(String ieee) {
    Map tgt = (Map) state.targets?.get(ieee)
    if (tgt == null) {
        logWarn "${ieee} is not one of the picked devices"
        return
    }
    if (getChildDevice(monitorDni(ieee))) return
    try {
        addChildDevice("iamtrep", MONITOR_DRIVER, monitorDni(ieee), [name: MONITOR_DRIVER, label: "${tgt.name} monitor", isComponent: false])
        state.remove("inputError")
        logCfg "${tgt.name}: created its monitor device"
    } catch (Exception e) {
        inputError("Could not create the monitor device (is the ${MONITOR_DRIVER} driver installed?): ${e.message}")
        return
    }
    scheduleMonitors()
    pollDevice(ieee)
}

private void removeMonitor(String ieee) {
    def mon = getChildDevice(monitorDni(ieee))
    if (mon == null) return
    String label = mon.displayName
    try {
        deleteChildDevice(monitorDni(ieee))
    } catch (Exception e) {
        logError "could not delete ${label}: ${e.message}"
    }
    if (getChildDevice(monitorDni(ieee))) {
        logWarn "${label} is still there after the delete"
    } else {
        logCfg "removed the monitor device ${label}"
        Map polls = (Map) (state.monitorPolls ?: [:])
        polls.remove(ieee)
        state.monitorPolls = polls
    }
    scheduleMonitors()
}

// One schedule for all monitors, at a random offset; none without monitors.
private void scheduleMonitors() {
    if (!monitorDevices()) {
        unschedule("pollMonitors")
        return
    }
    int minutes = ((settings.pollMinutes ?: '60') as String).toInteger()
    Random rng = new Random()
    schedule(pollCron(minutes, rng.nextInt(60), rng.nextInt(60)), "pollMonitors")
}

void pollMonitors() {
    checkVersion()
    expireOverdue()
    Map targets = (Map) (state.targets ?: [:])
    Map details = null
    monitorDevices().each { mon ->
        String ieee = monitorIeee(mon.deviceNetworkId?.toString())
        if (targets.containsKey(ieee)) {
            if (details == null) details = fetchZigbeeDetails(true)   // once per poll, shared by every device
            pollDevice(ieee, details, false)
        }
    }
    flushRadio()   // once for every device's requests
    markOrphansUnknown()
}

// An orphaned monitor is no longer polled, so its health is unknown rather than its last value.
private void markOrphansUnknown() {
    Map targets = (Map) (state.targets ?: [:])
    monitorDevices().each { mon ->
        if (!targets.containsKey(monitorIeee(mon.deviceNetworkId?.toString()))) {
            try { mon.updateValues([healthStatus: 'unknown']) } catch (Exception e) { logWarn "could not update ${mon.displayName}: ${e.message}" }
        }
    }
}

// Called by a monitor device's refresh(), in that device's own execution.
void monitorRefresh(String dni) {
    checkVersion()
    String ieee = monitorIeee(dni)
    if (ieee == null || !state.targets?.containsKey(ieee)) {
        logWarn "refresh from ${dni}: its device is no longer picked"
        return
    }
    expireOverdue()
    pollDevice(ieee)
}

// Health from the hub's last message time, then, for routers and devices of unknown type, the
// neighbour and routing tables (whose results update the monitor when they arrive). End devices
// get health only.
// lastPoll is the time of the poll attempt; the table values follow when the replies arrive. The
// single-device paths (button, refresh) fetch their own details; pollMonitors passes one read.
private void pollDevice(String ieee, Map details = null, boolean flush = true) {
    if (!state.targets?.containsKey(ieee)) return
    long t = now()
    Map polls = (Map) (state.monitorPolls ?: [:])
    polls[ieee] = t
    state.monitorPolls = polls
    if (details == null) details = fetchZigbeeDetails(true)
    pushMonitor(ieee, [healthStatus: healthOf(ieee, details), lastPoll: isoTime(t)])
    Map res = (Map) (state.results?.get(ieee) ?: [:])
    String lt = res.node?.status == 'ok' ? res.node.data?.logicalType : null
    if (lt == 'End Device') return
    List<String> wanted = []
    if (lt == null) wanted << 'node'
    wanted << 'neighbors'
    // a router that refused the routing table as NOT_SUPPORTED is not asked again
    if (!(res.routes?.status == 'refused' && res.routes?.statusName == 'NOT_SUPPORTED')) wanted << 'routes'
    pollKinds(wanted, (Map) state.pending, ieee).each { String k -> startRequest(ieee, k, [], false) }
    if (flush) flushRadio()
}

private String healthOf(String ieee, Map details) {
    return healthFor(details, ieee, inactiveFor(ieee), now())
}

private Map hubRecord(String ieee, Map details) {
    return (Map) ((List) (details?.devices ?: [])).find { it.zigbeeId?.toString()?.toUpperCase() == ieee }
}

private int inactiveFor(String ieee) {
    Object v = settings["monInactive_${ieee}"]
    if (v != null && v.toString().isInteger() && v.toString().toInteger() > 0) return v.toString().toInteger()
    Map node = (Map) state.results?.get(ieee)?.get('node')
    return defaultInactiveMinutes(node?.status == 'ok' ? (Map) node.data : null)
}

// Sends the monitor what the stored tables give, plus `extra`; the device keeps only changes.
private void pushMonitor(String ieee, Map extra = [:]) {
    def mon = getChildDevice(monitorDni(ieee))
    if (mon == null) return
    Map res = (Map) (state.results?.get(ieee) ?: [:])
    Map v = monitorValues(res.neighbors?.status == 'ok' ? (Map) res.neighbors.data : null,
                          res.routes?.status == 'ok' ? (Map) res.routes.data : null, nameMaps(fetchZigbeeDetails()))
    try {
        mon.updateValues(v + extra)
    } catch (Exception e) {
        logWarn "could not update ${mon.displayName}: ${e.message}"
    }
}

private String isoTime(long t) {
    return new Date(t).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getTimeZone("UTC"))
}

// RAW COMMANDS: sent as typed, after rawCommandError and the confirmation; no reply is matched.

private void sendRawFrame(String ieee, String cmd) {
    Map tgt = (Map) state.targets?.get(ieee)
    def radio = getChildDevice(RADIO_DNI)
    if (radio == null) {
        storeResult(ieee, 'raw', [status: 'error', error: "The ${RADIO_DRIVER} device is missing.", frame: cmd])
        return
    }
    radio.sendFrames([cmd])
    storeResult(ieee, 'raw', [status: 'sent', frame: cmd])
    logCmd "${tgt?.name ?: ieee}: raw command sent"
}

// REQUEST ENGINE

private void startRequest(String ieee, String action, List<String> args, boolean flush = true) {
    Map req = buildRequest(ieee, action, args)
    if (req == null) return
    Map t = currentTarget(ieee)
    if (t == null) {
        storeResult(ieee, (String) req.kind, [status: 'error', error: 'The device is not on the Zigbee network.'])
        return
    }
    sendRequest(req, t, flush)
}

// The request for a button, without its address, or null after showing why not.
private Map buildRequest(String ieee, String action, List<String> args) {
    Map tgt = (Map) state.targets?.get(ieee)
    if (tgt == null) {
        logWarn "${ieee} is not one of the picked devices"
        return null
    }
    Map node = (Map) state.results?.get(ieee)?.get('node')
    Map req = [ieee: ieee, action: action, ep: tgt.ep, pages: 1, sleepy: node?.status == 'ok' ? sleepyFrom((Map) node.data) : null]
    String kind = action
    if (action in ['write', 'configReport', 'readReport', 'cmd']) {
        Map form = zclFormRequest(action, zclFormFields(action, args), (String) tgt.ep)
        if (form.error) { inputError((String) form.error); return null }
        req.putAll(form)
        req.zdo = false
        kind = (String) form.kind
    } else if (action in ['bind', 'unbind']) {
        Map f = [srcEp: settings.w_bEp, cluster: settings.w_bCluster ?: '0006', group: settings.w_bGroup, dstEp: settings.w_bDstEp]
        Map form = bindFormRequest(action, f, ieee, hubIeee(), (String) tgt.ep)
        if (form.error) { inputError((String) form.error); return null }
        req.putAll(form)
        req.zdo = true
        req.cluster = zdoReplyCluster((String) form.reqCluster)
        kind = 'bind'
    } else if (action == 'leave') {
        req += [zdo: true, reqCluster: '0034', cluster: '8034', rejoin: settings.w_rejoin == true]
    } else if (action == 'ota') {
        req += [zdo: false, cluster: '0019', ep: tgt.ep, cmd: 0x00]
        String epErr = zclEpError((String) req.ep)
        if (epErr) { inputError(epErr); return null }
    } else if (action == 'reportcfg') {
        // from a report run: ep, cluster, attribute ids (comma-separated)
        req += [zdo: false, ep: args[0], cluster: args[1], attrs: args[2].split(',') as List, cmd: 0x08, expectCmds: [0x09, 0x0B]]
        kind = "reportcfg:${args[0]}:${args[1]}"
    } else if (ZDO_ACTIONS.containsKey(action)) {
        Map spec = ZDO_ACTIONS[action]
        req.zdo = true
        req.reqCluster = spec.req
        req.cluster = zdoReplyCluster((String) spec.req)
        req.paged = spec.paged == true
        req.start = 0
        if (action == 'simple') {
            String ep = args ? args[0] : (String) tgt.ep
            if (!isHex(ep, 2)) { inputError("Endpoint must be 2 hex digits, not '${ep}'"); return null }
            req.ep = ep.toUpperCase()
            kind = "simple:${req.ep}"
        }
    } else {
        String ep = args.size() > 0 ? args[0] : (settings.zbwEp ?: tgt.ep)?.toString()?.trim()
        String cluster = args.size() > 1 ? args[1] : (settings.zbwCluster ?: '0000').toString().trim()
        if (!isHex(ep, 2)) { inputError("Endpoint must be 2 hex digits, not '${ep}'"); return null }
        if (!isHex(cluster, 4)) { inputError("Cluster must be 4 hex digits, not '${cluster}'"); return null }
        String epErr = zclEpError(ep)
        if (epErr) { inputError(epErr); return null }
        req.zdo = false
        req.ep = ep.toUpperCase().padLeft(2, '0')
        req.cluster = cluster.toUpperCase().padLeft(4, '0')
        if (action == 'read') {
            String list = args.size() > 2 ? args[2] : (settings.zbwAttrs ?: '0004,0005').toString()
            List<String> attrs = list.split(/[,\s]+/).findAll { it }.collect { it.trim() }
            if (!attrs || attrs.any { !isHex(it, 4) }) { inputError("Attributes must be 4-hex-digit ids separated by commas, not '${list}'"); return null }
            req.attrs = attrs.collect { it.toUpperCase().padLeft(4, '0') }
            req.cmd = 0x00
            req.expectCmds = [0x01, 0x0B]
            kind = "read:${req.ep}:${req.cluster}"
        } else {
            req.start = 0
            req.cmd = 0x0C
            req.expectCmds = [0x0D, 0x0B]
            kind = "discover:${req.ep}:${req.cluster}"
        }
    }
    req.kind = kind
    state.remove("inputError")
    return req
}

// The device's place on the network ([ieee, nwk, name, ep]), read again once if the cached
// details lack it (or always, when `fresh`); null when the hub does not list it.
private Map currentTarget(String ieee, boolean fresh = false) {
    Map t = fresh ? null : resolveTarget(ieee, fetchZigbeeDetails())
    return t ?: resolveTarget(ieee, fetchZigbeeDetails(true))
}

private void sendRequest(Map req, Map t, boolean flush = true) {
    String ieee = (String) req.ieee
    Map r = req + [addr: t.nwk]
    cancelPending(ieee, (String) r.kind)
    issueRequest(r)
    logCmd "${state.targets?.get(ieee)?.name ?: ieee}: ${r.kind} request queued for ${t.nwk}"
    if (flush) flushRadio()
}

// The form fields for a ZCL request: from the button's arguments when it has them (write:
// ep, cluster, attr, type, value[, mfg]; readReport: ep, cluster, attr[, mfg]), else from the
// page inputs.
private Map zclFormFields(String action, List<String> args) {
    if (args && action == 'write') return [ep: args[0], cluster: args[1], attr: args[2], type: args[3], value: args[4], mfg: args[5]]
    if (args && action == 'readReport') return [ep: args[0], cluster: args[1], attr: args[2], mfg: args[3]]
    return [ep: settings.w_ep, cluster: settings.w_cluster ?: '0006', mfg: settings.w_mfg, attr: settings.w_attr, type: settings.w_type,
            value: settings.w_value, min: settings.w_min, max: settings.w_max, change: settings.w_change, cmd: settings.w_cmd,
            payload: settings.w_payload, frameType: settings.w_frameType ?: 'cluster', toClient: settings.w_toClient == true,
            ddr: settings.w_ddr == true]
}

// The hub's own IEEE address (null when its Zigbee radio is off). /hub/zigbeeDetails/json does
// not carry it.
private String hubIeee() {
    String id = location.hub?.zigbeeEui?.toString() ?: location.hub?.zigbeeId?.toString()
    return id ? id.toUpperCase() : null
}

private void inputError(String msg) {
    state.inputError = msg
    logWarn msg
}

@CompileStatic
private static boolean isHex(String s, int maxLen) {
    return s != null && s ==~ /(?i)[0-9a-f]{1,${maxLen}}/
}

private void cancelPending(String ieee, String kind) {
    Map pending = state.pending
    if (!pending) return
    pending.findAll { k, v -> v.ieee == ieee && v.kind == kind }.collect { it.key }.each { pending.remove(it) }
}

// Allocates a seq, builds the frame and queues the request in state.pending, unsent. flushRadio
// sends it and starts its deadline.
private void issueRequest(Map req) {
    int seq = nextSeq((state.seq ?: 0) as int)
    state.seq = seq
    req.seq = seq
    req.frame = buildRequestFrame(req)
    req.sent = false
    req.deadline = null
    req.queuedAt = now()
    if (state.pending == null) state.pending = [:]
    state.pending[pendingKey((String) req.addr, (String) req.cluster, seq)] = req
}

// Points the radio's watch list at the pending addresses and sends unsent frames while its socket is
// connected (else they are lost), as pickSendable allows: one per device and
// MAX_IN_FLIGHT in all. Calls into the radio child, so it runs only in the
// app's own executions, never inside radioFrame or radioDown, where the child is waiting on the app.
void flushRadio() {
    checkVersion()
    def radio = getChildDevice(RADIO_DNI)
    Map pending = state.pending ?: [:]
    if (radio == null) {
        failAllPending("The ${RADIO_DRIVER} device is missing.")
        return
    }
    List<String> watch = watchFor(pending)
    if (state.watchSent == null || watch != state.watchSent) {
        radio.setWatch(watch)
        state.watchSent = watch
    }
    long t = now()
    Map pick = pickSendable(pending)
    List<String> unsent = (List<String>) pick.keys
    if (unsent) {
        // the radio's in-memory status: its wsStatus events can be stored out of order
        String ws = (radio.socketStatus() ?: radio.currentValue("wsStatus", true))?.toString()
        if (flushDecision(ws) == "fail") {
            logWarn "the Zigbee log socket is down (${ws}); ${unsent.size()} request(s) lost"
            ensureRadioSocket()
            for (String k in unsent) {
                Map r = (Map) pending.remove(k)
                putResult((String) r.ieee, (String) r.kind, failureEntry(r, 'lost', LOST_LINK, null, null, t))
            }
            List<String> left = watchFor(pending)
            if (left != state.watchSent) {
                radio.setWatch(left)
                state.watchSent = left
            }
            if (pending) runInMillis(500, "flushRadio")
            return
        }
        List<String> cmds = []
        for (String k in unsent) {
            Map r = (Map) pending[k]
            cmds << (String) r.frame
            r.sent = true
            r.sentAt = t
            r.deadline = t + REPLY_TIMEOUT_MS
        }
        radio.sendFrames(cmds)
        logDebug "sent ${cmds}"
    }
    if (pending) scheduleSweep()
}

// Schedules one sweep for the earliest reply deadline (5 s ahead when nothing is on the air). A
// sweep already scheduled is left alone: runIn replaces a job of the same name, so rescheduling on
// every flush or page refresh would keep pushing it out while replies or refreshes keep coming.
// A sweep due more than a second ago is taken as lost (a reboot) and scheduled again.
private void scheduleSweep() {
    long t = now()
    Long at = state.sweepAt as Long
    if (at != null && at > t - 1000L) return
    List<Long> deadlines = ((Map) (state.pending ?: [:])).values().findAll { it.deadline != null }.collect { it.deadline as long }
    long due = deadlines ? (long) deadlines.min() + 200L : t + 5000L
    long delay = Math.max(500L, due - t)
    runInMillis(delay, "sweepPending")
    state.sweepAt = t + delay
}

// Called by the radio child for each frame from a watched address.
void radioFrame(Map frame) {
    checkVersion()
    Map pending = state.pending
    if (!pending) return
    Map m = matchPending(pending, frame, now())
    if (m == null) return
    try {
        handleReply((Map) m.request, frame, (Map) m.header)
    } catch (Exception e) {
        logError "could not decode the ${m.request.kind} reply: ${e.message}"
        storeFailure((Map) m.request, 'error', "Could not decode the reply: ${e.message}", payloadOf(frame), null)
    }
    runInMillis(20, "flushRadio")
}

// Called by the radio child when its log socket drops while it watches addresses (requests are
// waiting); it reconnects on its own. The flush clears its watch list.
void radioDown(String reason) {
    checkVersion()
    failAllPending(reason)
    state.watchSent = null
    runInMillis(20, "flushRadio")
}

// Fails every pending request as lost. Idempotent: a second call finds nothing pending.
void failAllPending(String reason) {
    Map pending = state.pending
    if (!pending) return
    logWarn "${pending.size()} request(s) lost: ${reason}"
    failAll(pending, reason, now()).each { Map f -> putResult((String) f.ieee, (String) f.kind, (Map) f.entry) }
    state.pending = [:]
}

void sweepPending() {
    checkVersion()
    state.remove("sweepAt")
    expireOverdue()
    interviewSweep()
    flushRadio()
}

// Records a timeout for every sent request past its deadline. Runs from sweepPending and also from
// every button press and device page render, so a sweep lost to a reboot or a failed run does not
// leave a request pending for good.
private void expireOverdue() {
    Map pending = state.pending
    if (!pending) return
    for (String k in expirePending(pending, now())) {
        Map r = (Map) pending.remove(k)
        logWarn "${r.kind} for ${targetName((String) r.ieee)}: no reply"
        putResult((String) r.ieee, (String) r.kind, timeoutEntry(r, now()))
    }
}

private static List<String> payloadOf(Map frame) {
    return (frame?.payload instanceof List) ? ((List) frame.payload).collect { it.toString().toUpperCase() } : []
}

private void handleReply(Map req, Map frame, Map header) {
    List<String> p = payloadOf(frame)
    // sentAt: when the request (the last page, for a paged table) went out; time is when it was answered
    Map meta = [lqi: frame.lqi, rssi: frame.rssi, sentAt: req.sentAt]
    if (req.zdo) {
        Map d = decodeZdo((String) req.cluster, p)
        if ((d.status as int) != 0) {
            storeFailure(req, 'refused', "The device answered ${d.statusName}.", p, d.statusName as String)
            return
        }
        if (req.paged) {
            List acc = ((List) (req.acc ?: [])) + ((List) (d.entries ?: []))
            Integer next = nextPageStart(d)
            if (next != null && ((req.pages ?: 1) as int) < MAX_PAGES) {
                issueNextPage(req, next, acc)
                return
            }
            storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [total: d.total, entries: acc]] + meta)
        } else if (req.action in ['bind', 'unbind']) {
            storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [op: req.action, ep: req.srcEp, cluster: req.bindCluster,
                                                                                    dstIeee: req.dstIeee, dstEp: req.dstEp, group: req.group]] + meta)
        } else if (req.action == 'leave') {
            storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [rejoin: req.rejoin == true]] + meta)
        } else {
            storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: d.findAll { k, v -> !(k in ['seq', 'status', 'statusName']) }] + meta)
        }
        return
    }
    Map d = decodeZcl(header, p)
    if (req.action in ['write', 'configReport', 'readReport', 'cmd', 'ota']) {
        Map out = zclResult(req, header, d)
        if (out.data?.reply instanceof Map) out.data.reply += [srcEp: frame.srcEp?.toString(), receivedAt: now()]
        if (out.status != 'ok') {
            storeFailure(req, (String) out.status, (String) out.error, p, (String) out.statusName)
        } else {
            storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: out.data] + meta)
        }
        return
    }
    if ((header.cmd as int) == 0x0B) {
        storeFailure(req, 'refused', "The device answered ${d.statusName ?: 'a default response'}.", p, d.statusName as String)
        return
    }
    if (req.action == 'reportcfg') {
        storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [ep: req.ep, cluster: req.cluster, records: d.records ?: []]] + meta)
        return
    }
    if (req.action == 'read') {
        Map prev = (Map) state.results?.get(req.ieee)?.get(req.kind)
        Map<String, Map> byAttr = [:]
        if (prev?.status == 'ok') ((List) prev.data?.records ?: []).each { byAttr[it.attr.toString()] = (Map) it }
        ((List) d.records ?: []).each { byAttr[it.attr.toString()] = (Map) it }
        List records = byAttr.values().sort { (it.attr as int) }
        storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [ep: req.ep, cluster: req.cluster, records: records]] + meta)
        return
    }
    // discover
    List attrs = ((List) (req.acc ?: [])) + ((List) (d.attrs ?: []))
    boolean complete = d.complete == true
    int last = d.attrs ? (((List) d.attrs).last().id as int) : -1
    // 0xFFFF is the last attribute id: a page ending there has nothing after it
    if (!complete && d.attrs && last < 0xFFFF && ((req.pages ?: 1) as int) < MAX_PAGES) {
        issueNextPage(req, last + 1, attrs)
        return
    }
    storeResult((String) req.ieee, (String) req.kind, [status: 'ok', data: [ep: req.ep, cluster: req.cluster, attrs: attrs, complete: complete]] + meta)
}

private void issueNextPage(Map req, int start, List acc) {
    Map nr = new LinkedHashMap(req)
    ['seq', 'frame', 'sent', 'sentAt', 'deadline', 'queuedAt'].each { nr.remove(it) }
    nr.start = start
    nr.acc = acc
    nr.pages = ((req.pages ?: 1) as int) + 1
    issueRequest(nr)
}

// RESULT STORE: state.results[ieee][kind] = [time, status, data | error, ...]

private void storeResult(String ieee, String kind, Map entry) {
    putResult(ieee, kind, [time: now()] + entry)
    if (kind in ['neighbors', 'routes'] && entry.status == 'ok') pushMonitor(ieee)
}

private void putResult(String ieee, String kind, Map entry) {
    if (state.results == null) state.results = [:]
    Map forDev = (Map) (state.results[ieee] ?: [:])
    forDev[kind] = entry
    state.results[ieee] = forDev
    Map iv = (Map) state.interviews?.get(ieee)
    if (iv != null && ((Map) iv.step)?.kind == kind) interviewAdvance(ieee, iv, entry)
}

// A running interview always has its step pending. One without (its request was dropped, say by a
// reboot with nothing else pending) would wait for good: stop it.
private void interviewSweep() {
    Map ivs = (Map) state.interviews
    if (!ivs) return
    Collection pend = ((Map) (state.pending ?: [:])).values()
    for (String ieee in new ArrayList<String>(ivs.keySet())) {
        Map iv = (Map) ivs[ieee]
        if (!pend.any { it.ieee == ieee && it.kind == ((Map) iv.step)?.kind }) interviewFinish(ieee, iv, 'Interrupted: its request was dropped.')
    }
}

// INTERVIEW: one request at a time through the engine; each step's result (reply, failure or
// timeout, whoever sent the request) records it and queues the next. It only queues: putResult
// runs inside radioFrame, where the radio child cannot be called, and the flush that follows
// every reply, sweep and button press sends it.

private void startInterview(String ieee) {
    if (state.targets?.get(ieee) == null) {
        logWarn "${ieee} is not one of the picked devices"
        return
    }
    if (state.interviews == null) state.interviews = [:]
    Map iv = [started: now(), tried: []]
    state.interviews[ieee] = iv
    logCmd "${targetName(ieee)}: interview started"
    interviewAdvance(ieee, iv, null)
    flushRadio()
}

private void interviewAdvance(String ieee, Map iv, Map entry) {
    if (entry != null) {
        interviewRecord(iv, (Map) iv.step, entry)
        if (entry.status == 'lost') { interviewFinish(ieee, iv, (String) entry.error); return }
    }
    while (true) {
        Map step = interviewNext(iv)
        if (step == null) { interviewFinish(ieee, iv, null); return }
        ((List) iv.tried) << step.key
        iv.step = step
        Map req = buildRequest(ieee, (String) step.action, (List<String>) step.args)
        if (req == null) {
            interviewRecord(iv, step, [status: 'error', error: state.inputError ?: 'could not build the request'])
            continue
        }
        Map t = currentTarget(ieee)
        if (t == null) { interviewFinish(ieee, iv, 'The device is not on the Zigbee network.'); return }
        cancelPending(ieee, (String) req.kind)
        issueRequest(req + [addr: t.nwk])
        List plan = interviewPlan(iv)
        if (state.results == null) state.results = [:]
        String pk = iv.report ? 'report' : 'interview'
        state.results[ieee] = ((Map) state.results[ieee] ?: [:]) +
            [(pk): [time: now(), status: 'running', data: [done: ((List) iv.tried).size() - 1, total: plan.size()]]]
        state.interviews[ieee] = iv
        return
    }
}

// The result keeps the report text and what the interview found (a report run reuses it); the
// run's working state is dropped. A report run then builds its report in a run of its own: that
// reads hub data and writes a file, too long for the radio's callback this may run in.
private void interviewFinish(String ieee, Map iv, String error) {
    int total = ((List) iv.tried).size()
    Map ivData = iv.subMap(['node', 'endpoints', 'simple', 'disc', 'vals', 'rep', 'failed'])
    Map entry = [time: now(), status: error ? 'error' : 'ok', data: [done: total, total: total, report: renderReport(iv, ZCL_ATTR_NAMES), iv: ivData]]
    if (error) entry.error = error
    if (state.results == null) state.results = [:]
    Map add = [interview: entry]
    if (iv.report) add.report = [time: now(), status: 'running', data: [done: total, total: total]]
    state.results[ieee] = ((Map) state.results[ieee] ?: [:]) + add
    state.interviews.remove(ieee)
    if (iv.report) runInMillis(100, "reportBuild", [data: [ieee: ieee, started: iv.started, error: error]])
    logCmd "${targetName(ieee)}: interview ${error ? 'stopped: ' + error : 'done'} after ${total} steps"
}

private void storeFailure(Map req, String status, String error, List<String> raw, String statusName) {
    putResult((String) req.ieee, (String) req.kind, failureEntry(req, status, error, raw, statusName, now()))
}

// SHAREABLE REPORT

// Starts a report run: the interview unless one finished cleanly within REPORT_FRESH_MS, the
// network tables not read within it, and the reporting configuration of each server cluster.
private void startReport(String ieee) {
    if (state.targets?.get(ieee) == null) {
        logWarn "${ieee} is not one of the picked devices"
        return
    }
    Map res = (Map) (state.results?.get(ieee) ?: [:])
    long t = now()
    Map iv = [started: t, tried: [], report: true]
    Map last = (Map) res.interview
    Map seed = last?.status == 'ok' && t - ((last.time ?: 0) as long) < REPORT_FRESH_MS ? (Map) last.data?.iv : null
    // A step that failed is not asked again: a device that ignores a cluster ignores it every time.
    // Its failure goes into the report.
    if (seed) {
        iv.putAll(seed.subMap(['node', 'endpoints', 'simple', 'disc', 'vals', 'failed']))
        iv.tried = interviewPlan(seed).collect { it.key }
    }
    iv.fresh = ['neighbors', 'routes', 'bindings'].findAll { String k ->
        Map r = (Map) res[k]
        r?.status == 'ok' && t - ((r.time ?: 0) as long) < REPORT_FRESH_MS
    }
    if (state.interviews == null) state.interviews = [:]
    state.interviews[ieee] = iv
    logCmd "${targetName(ieee)}: report started"
    interviewAdvance(ieee, iv, null)
    flushRadio()
}

void reportBuild(Map data) {
    checkVersion()
    String ieee = data?.ieee?.toString()
    Map tgt = (Map) state.targets?.get(ieee)
    if (tgt == null) return
    Map res = (Map) (state.results?.get(ieee) ?: [:])
    Map details = fetchZigbeeDetails(true)
    Map devList = hubGet("/hub2/devicesList", "devices list") ?: [:]
    Map apps = hubGet("/hub2/appsList", "apps list") ?: [:]
    Map fj = hubGet("/device/fullJson/${tgt.deviceId}", "device details") ?: [:]
    Map hd = hubGet("/hub2/hubData", "hub data") ?: [:]
    Map byId = [:]
    flatEntries((List) devList.devices).each { Map e -> if (e.data instanceof Map) byId[e.data.id?.toString()] = (e.data.name ?: 'Unknown').toString() }
    Map names = [byNwk: ['0000': 'Hub'], byIeee: [:]]
    ((List) (details?.devices ?: [])).each { Map d ->
        String n = byId[d.id?.toString()] ?: d.name?.toString()?.trim()
        if (d.shortZigbeeId) names.byNwk[d.shortZigbeeId.toString().toUpperCase()] = n
        if (d.zigbeeId) names.byIeee[d.zigbeeId.toString().toUpperCase()] = n
    }
    String hub = hubIeee()
    if (hub) names.byIeee[hub] = 'Hub'
    Map r = resolveTarget(ieee, details)
    Map dev = (Map) (fj.device ?: [:])
    Map inp = [ieee: ieee, nwk: r?.nwk, name: byId[tgt.deviceId?.toString()] ?: tgt.name, iv: (Map) (res.interview?.data?.iv ?: [:]),
               results: res, record: hubRecord(ieee, details) ?: [:], names: names,
               driver: [name: dev.deviceTypeName, namespace: dev.deviceTypeNamespace, data: dev.data instanceof Map ? dev.data : [:]],
               hub: [model: hd.model, firmware: location.hub?.firmwareVersionString, channel: details?.channel, powerLevel: details?.powerLevel],
               started: (data.started ?: 0) as long, version: CODE_VERSION, now: now(),
               time: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getDefault())]
    Map rd = reportData(inp)
    List extPans = (((List) (res.neighbors?.data?.entries ?: [])).collect { it.extPan } + [details?.extendedPanId]).findAll { it }.collect { it.toString() }.unique()
    // only the aliases of names the report shows, from the whole hub's list so they match Hub Inspector's
    Map all = makeAliases(aliasRegistry(devList, apps))
    List shown = [rd.device.name] + ((List) (rd.mesh.neighbors ?: [])).collect { it.name } +
        ((List) (rd.mesh.routes ?: [])).collect { [it.dest, it.nextHop] }.flatten() + ((List) (rd.bindings ?: [])).collect { it.dstName } + [rd.mesh.parent]
    Map aliasMap = all.subMap(shown.findAll { it != null }.collect { it.toString() }.unique().findAll { all.containsKey(it) })
    boolean useAliases = settings.reportAliases != false
    Map opts = [aliases: useAliases, aliasMap: aliasMap, extPans: extPans]
    Map save = saveReport(rd.device.model?.toString(), renderFull(rd, opts))
    Map entry = [time: now(), status: 'ok', data: [report: rd, aliasMap: aliasMap, extPans: extPans, aliases: useAliases, file: save.file, fileError: save.error]]
    if (data.error) entry.error = "The run stopped early: ${data.error}".toString()
    state.results[ieee] = ((Map) state.results[ieee] ?: [:]) + [report: entry]
    logCmd "${targetName(ieee)}: report ready${save.file ? ', saved as ' + save.file : ''}"
}

// The devices list as one flat list of its entries, children included.
@CompileStatic
private static List<Map> flatEntries(List entries) {
    List<Map> out = []
    (entries ?: []).each { Object e ->
        if (!(e instanceof Map)) return
        out << (Map) e
        out.addAll(flatEntries((List) ((Map) e).children))
    }
    return out
}

// The names Hub Inspector registers for its aliases: every device's name in /hub2/devicesList and
// every user app's label in /hub2/appsList.
static List<String> aliasRegistry(Map devList, Map apps) {
    List<String> reg = []
    flatEntries((List) devList?.devices).each { Map e -> if (e.data instanceof Map) reg << (e.data.name ?: 'Unknown').toString() }
    Closure walk
    walk = { List entries ->
        (entries ?: []).each { Object e ->
            if (!(e instanceof Map)) return
            Map a = (Map) ((Map) e).data
            if (a instanceof Map && a.user) reg << (a.name ?: a.type ?: 'Unknown App').toString()
            walk((List) ((Map) e).children)
        }
    }
    walk((List) apps?.apps)
    return reg
}

// Writes the report to File Manager and keeps the newest REPORTS_KEPT for the model: [file] or [error].
private Map saveReport(String model, String markdown) {
    String prefix = reportFilePrefix(model)
    String name = prefix + new Date().format('yyyyMMdd-HHmm', location.timeZone ?: TimeZone.getDefault()) + '.md'
    try {
        uploadHubFile(name, markdown.getBytes('UTF-8'))
    } catch (Exception e) {
        logWarn "could not save the report: ${e.message}"
        return [error: "Could not save the report to File Manager: ${e.message}".toString()]
    }
    try {
        List<Map> files = ((List) (getHubFiles() ?: [])).collect { [name: it.name?.toString()] }
        reportsToDelete(files, prefix, REPORTS_KEPT).each { String old -> deleteHubFile(old) }
    } catch (Exception e) {
        logWarn "could not remove older reports: ${e.message}"
    }
    return [file: name]
}

// HUB TABLES

void hubTablesRetry(Map data) {
    checkVersion()
    refreshHubTables((data?.tries ?: 1) as int)
}

private void refreshHubTables(int tries) {
    Map info = fetchChildAndRouteInfo(true)
    Map details = fetchZigbeeDetails(tries == 0)
    // Right after the tables were last read long ago, the hub answers with empty tables while it
    // rebuilds them, which took more than 15 s on a larger mesh: ask again every 3 s for 45 s.
    boolean rebuilding = info.topologyRefreshing == true && !info.neighbors && !info.routes
    if (rebuilding && tries < 15) {
        state.hubTables = [time: now(), status: 'refreshing']
        runIn(3, "hubTablesRetry", [data: [tries: tries + 1]])
        return
    }
    if (rebuilding) {
        state.hubTables = [time: now(), status: 'error', error: 'The hub is still rebuilding its Zigbee tables. Try again in a minute.']
        return
    }
    if (!info) {
        state.hubTables = [time: now(), status: 'error', error: 'Could not read the hub Zigbee tables.']
        return
    }
    state.hubTables = [time: now(), status: 'ok', data: summarizeHubTables(info, details)]
    logCmd "hub tables: ${state.hubTables.data.neighbors.size()} neighbours, ${state.hubTables.data.routes.size()} routes"
}

private Map summarizeHubTables(Map info, Map details) {
    List<Map> neighbors = ((List) (info.neighbors ?: [])).collect {
        [nwk: it.id?.toString(), lqi: it.lqi, inCost: it.inCost, outCost: it.outCost, age: it.age]
    }
    List<Map> children = ((List) (info.children ?: [])).collect { it instanceof Map ? new LinkedHashMap((Map) it) : [value: it.toString()] }
    List<Map> routes = ((List) (info.routes ?: [])).findAll { it.used != false && it.id }.collect {
        [dest: it.id?.toString(), nextHop: it.nextHopId?.toString(), status: it.status, age: it.age,
         routeRecordState: it.routeRecordState, concentratorType: it.concentratorType]
    }
    List lqis = neighbors.collect { it.lqi }.findAll { it != null }
    return [neighbors: neighbors, children: children, routes: routes,
            lowestLqi: lqis ? lqis.min() : null,
            channel: details.channel, panId: details.panId, extendedPanId: details.extendedPanId,
            powerLevel: details.powerLevel, networkState: details.networkState]
}

// PAGES

Map mainPage(Map params = null) {
    syncTargets()
    Map details = fetchZigbeeDetails()
    Map names = nameMaps(details)
    return dynamicPage(name: "mainPage", title: APP_NAME, install: true, uninstall: true) {
        section("Devices") {
            input "zbDevices", "capability.*", title: "Devices to inspect", multiple: true, required: false, submitOnChange: true
            paragraph "Picking a device changes nothing on it: its driver, settings and state stay as they are. Its driver also receives the replies, as it does every frame from the device."
        }
        Map targets = state.targets ?: [:]
        if (targets || state.nonZigbee) {
            section("Inspect") {
                targets.each { String ieee, Map t ->
                    Map r = resolveTarget(ieee, details)
                    Map node = (Map) state.results?.get(ieee)?.get('node')
                    String type = node?.status == 'ok' ? node.data?.logicalType : 'type not read yet'
                    Long last = lastResultTime(ieee)
                    String desc = "IEEE ${ieee} · address ${r?.nwk ?: 'not on the network'} · ${type} · " +
                        (last ? "last result ${fmtTime(last)}" : 'no results yet')
                    href "dev_${ieee}", page: "devicePage", params: [ieee: ieee], title: esc(t.name), description: desc
                }
                (state.nonZigbee ?: []).each { Map o ->
                    paragraph "${esc(o.name)}: ${NOT_ZIGBEE}"
                }
            }
        }
        section("Monitor devices") {
            input "pollMinutes", "enum", title: "Poll monitored devices every (minutes)", options: POLL_MINUTES, defaultValue: "60", required: false, width: 4
            List mons = monitorDevices()
            List<String> orphans = orphanMonitors(mons.collect { it.deviceNetworkId.toString() }, targets.keySet() as List<String>)
            paragraph "<small>A monitor device publishes a device's health, neighbour and route counts, parent and lowest LQI for dashboards and rules. Turn it on from the device's page. ${mons.size()} monitor device${mons.size() == 1 ? '' : 's'}.</small>"
            orphans.each { String oi ->
                def mon = getChildDevice(monitorDni(oi))
                paragraph "<b>${esc(mon?.displayName)}</b> (IEEE ${esc(oi)}): its device is no longer picked. It is kept until you remove it."
                input "zbw:unmonitor:${oi}", "button", title: "Remove ${esc(mon?.displayName)}…", width: 4
            }
            Map cv = (Map) state.confirmView
            if (cv?.action == 'unmonitor' && cv.ieee in orphans && confirmOpen(cv)) {
                href "zbwConfirmOrphan", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cv.action]}", description: CONFIRM_LINK
            }
        }
        section("Hub") {
            href "hubLink", page: "hubPage", title: "Hub Zigbee tables", description: "The coordinator's neighbours, children and routes; channel, PAN and power"
        }
        section("Logging", hideable: true, hidden: true) {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
        section("") {
            paragraph "<small>${APP_NAME} v${CODE_VERSION}</small>"
        }
    }
}

Map devicePage(Map params = null) {
    if (params?.ieee) state.viewIeee = params.ieee.toString().toUpperCase()
    String ieee = state.viewIeee
    if (!ieee || !state.targets?.containsKey(ieee)) ieee = ((Map) (state.targets ?: [:])).keySet().find { true }
    Map tgt = ieee ? (Map) state.targets?.get(ieee) : null
    if (tgt == null) {
        return dynamicPage(name: "devicePage", title: "Device") {
            section("") { paragraph "Pick the device on the main page first." }
        }
    }
    int before = ((Map) (state.pending ?: [:])).size()
    expireOverdue()
    interviewSweep()
    // a timeout recorded here can queue an interview's next step: send it from a run of its own
    if (state.pending) {
        if (((Map) state.pending).values().any { it.sent != true } || ((Map) state.pending).size() != before) runInMillis(20, "flushRadio")
        scheduleSweep()
    }
    Map details = fetchZigbeeDetails()
    Map names = nameMaps(details)
    Map res = (Map) (state.results?.get(ieee) ?: [:])
    List pend = ((Map) (state.pending ?: [:])).values().findAll { it.ieee == ieee }
    Map r = resolveTarget(ieee, details)
    return dynamicPage(name: "devicePage", title: esc(tgt.name), refreshInterval: pend ? 2 : 0) {
        section("") {
            String line = "IEEE <b>${esc(ieee)}</b> · address <b>${esc(r?.nwk ?: 'not on the network')}</b> · endpoint <b>${esc(tgt.ep)}</b>"
            if (pend) line += "<br><i>Waiting for ${pend.size()} repl${pend.size() == 1 ? 'y' : 'ies'}…</i>"
            if (state.inputError) line += "<br><span style='color:var(--hubitat-validation-color, #d32f2f)'>${esc(state.inputError)}</span>"
            paragraph line
        }
        section("Health and monitor device") {
            Map hr = hubRecord(ieee, details)
            int inactive = inactiveFor(ieee)
            Map mv = monitorValues(res.neighbors?.status == 'ok' ? (Map) res.neighbors.data : null, res.routes?.status == 'ok' ? (Map) res.routes.data : null, names)
            Long polled = (Long) state.monitorPolls?.get(ieee)
            String line = "Health <b>${healthFor(details, ieee, inactive, now())}</b> (offline after ${inactive} minutes without a message) · " +
                "last message <b>${esc(hr == null ? 'the hub no longer lists this device' : (hr.lastMessage ?: 'none since the hub started'))}</b> · messages <b>${esc(hr?.messageCount ?: '?')}</b>"
            if (mv) line += "<br>Neighbours <b>${mv.neighborCount}</b> · children <b>${mv.childCount}</b> · parent <b>${esc(mv.parent)}</b> · lowest LQI <b>${esc(mv.lowestLqi ?: '–')}</b>" +
                (mv.routeCount != null ? " · active routes <b>${mv.routeCount}</b>" : '')
            if (polled) line += "<br>Last poll ${fmtTime(polled)}"
            paragraph line
            def mon = getChildDevice(monitorDni(ieee))
            if (mon) {
                paragraph "Monitor device <a href='/device/edit/${mon.id}' target='_blank'>${esc(mon.displayName)}</a> publishes these values. Polled every ${esc(settings.pollMinutes ?: '60')} minutes; routers also get their neighbour and routing tables read."
                // no defaultValue: a saved default would stop following the node descriptor once it is read
                input "monInactive_${ieee}", "number", title: "Offline after (minutes without a message; empty: ${defaultInactiveMinutes(res.node?.status == 'ok' ? (Map) res.node.data : null)})", width: 4, required: false, submitOnChange: true
                input "zbw:poll:${ieee}", "button", title: "Poll now", width: 3
                input "zbw:unmonitor:${ieee}", "button", title: "Stop monitoring…", width: 3
                Map cv = (Map) state.confirmView
                if (cv?.ieee == ieee && cv.action == 'unmonitor' && confirmOpen(cv)) {
                    href "zbwConfirmMon", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cv.action]}", description: CONFIRM_LINK
                }
            } else {
                input "zbw:monitor:${ieee}", "button", title: "Monitor this device", width: 4
            }
        }
        section("Interview") {
            paragraph "<small>Reads the node descriptor, the endpoints, each endpoint's clusters, and every attribute the device lists for its server clusters, one request at a time. Nothing is written.</small>"
            input "zbw:interview:${ieee}", "button", title: "Interview", width: 4
            Map iv = (Map) res.interview
            if (iv) {
                StringBuilder sb = new StringBuilder("<div class='mb-3'><b>Interview</b> <small class='text-color-secondary'>${fmtTime(iv.time as Long)}</small><br>")
                if (iv.status == 'running') sb.append("<i>Step ${(iv.data.done as int) + 1} of ${iv.data.total} (more as the device answers)…</i>".toString())
                if (iv.status == 'error') sb.append("<div class='p-message p-message-warn p-2'>Stopped: ${esc(iv.error)}</div>".toString())
                if (iv.data?.report) sb.append("<small>Select the text to copy it.</small><pre style='white-space:pre-wrap;user-select:all;font-size:85%;max-height:40em;overflow:auto'>${esc(iv.data.report)}</pre>".toString())
                paragraph rawHtml: true, sb.append("</div>").toString()
            }
        }
        section("Shareable report") {
            paragraph "<small>Runs what the report needs that was not read in the last 10 minutes (interview, network tables, reporting configuration), then shows a version that fits a forum post and saves the full one to File Manager. IEEE addresses keep their first 3 bytes; with aliases on, device names become the same aliases Hub Inspector uses.</small>"
            input "reportAliases", "bool", title: "Replace device names with aliases", width: 6, required: false, submitOnChange: true, defaultValue: true
            input "zbw:report:${ieee}", "button", title: "Create report", width: 4
            Map rp = (Map) res.report
            if (rp?.status == 'running') {
                paragraph "<i>Step ${((rp.data?.done ?: 0) as int) + 1} of ${rp.data?.total ?: '?'} (more as the device answers)…</i>"
            } else if (rp?.status == 'ok' && rp.data?.report) {
                Map opts = [aliases: settings.reportAliases != false, aliasMap: rp.data.aliasMap, extPans: rp.data.extPans]
                String forum = renderForum((Map) rp.data.report, opts)
                StringBuilder sb = new StringBuilder("<div class='mb-3'><b>Report</b> <small class='text-color-secondary'>${fmtTime(rp.time as Long)} · ${forum.length()} characters</small>")
                if (rp.error) sb.append("<div class='p-message p-message-warn p-2'>${esc(rp.error)}</div>".toString())
                sb.append("<textarea id='zbwReport' readonly style='width:100%;height:24em;font-family:monospace;font-size:85%'>${esc(forum)}</textarea>".toString())
                sb.append("<button type='button' class='btn btn-default' onclick='var t=document.getElementById(\"zbwReport\");if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t.value)}else{t.select();document.execCommand(\"copy\")}'>Copy</button>")
                if (rp.data.file) sb.append(" <small>Full report: <a href='/local/${esc(rp.data.file)}' target='_blank'>${esc(rp.data.file)}</a> (saved with aliases ${rp.data.aliases ? 'on' : 'off'})</small>".toString())
                if (rp.data.fileError) sb.append("<br><span style='color:var(--hubitat-validation-color, #d32f2f)'>${esc(rp.data.fileError)}</span>".toString())
                paragraph rawHtml: true, sb.append("</div>").toString()
            }
        }
        section("Network tables") {
            input "zbw:neighbors:${ieee}", "button", title: "Neighbours", width: 4
            input "zbw:routes:${ieee}", "button", title: "Routes", width: 4
            input "zbw:bindings:${ieee}", "button", title: "Bindings", width: 4
            paragraph rawHtml: true, resultHtml("Neighbours", res.neighbors, pend, 'neighbors', names)
            paragraph rawHtml: true, resultHtml("Routes", res.routes, pend, 'routes', names)
            paragraph rawHtml: true, resultHtml("Bindings", res.bindings, pend, 'bindings', names)
        }
        section("Descriptors") {
            input "zbw:node:${ieee}", "button", title: "Node", width: 4
            input "zbw:power:${ieee}", "button", title: "Power", width: 4
            input "zbw:endpoints:${ieee}", "button", title: "Active endpoints", width: 4
            List<String> eps = res.endpoints?.status == 'ok' ? (List<String>) res.endpoints.data?.endpoints : [(String) tgt.ep]
            eps.each { String ep -> input "zbw:simple:${ieee}:${ep}", "button", title: "Simple descriptor ${ep}", width: 4 }
            paragraph rawHtml: true, resultHtml("Node", res.node, pend, 'node', names)
            paragraph rawHtml: true, resultHtml("Power", res.power, pend, 'power', names)
            paragraph rawHtml: true, resultHtml("Active endpoints", res.endpoints, pend, 'endpoints', names)
            res.keySet().findAll { it.startsWith('simple:') }.sort().each { String k ->
                paragraph rawHtml: true, resultHtml("Simple descriptor ${k.substring(7)}", res[k], pend, k, names)
            }
        }
        section("Attributes") {
            input "zbwEp", "text", title: "Endpoint (hex)", width: 2, required: false, submitOnChange: true, defaultValue: tgt.ep
            input "zbwCluster", "text", title: "Cluster (hex)", width: 3, required: false, submitOnChange: true, defaultValue: "0000"
            input "zbwAttrs", "text", title: "Attributes to read (hex, comma-separated)", width: 7, required: false, submitOnChange: true, defaultValue: "0004,0005"
            input "zbw:read:${ieee}", "button", title: "Read attributes", width: 4
            input "zbw:discover:${ieee}", "button", title: "Discover attributes", width: 4
            res.keySet().findAll { it.startsWith('read:') || it.startsWith('discover:') }.sort().each { String k ->
                List<String> kp = k.split(':') as List<String>
                String title = (kp[0] == 'read' ? 'Read' : 'Discover') + " endpoint ${kp[1]} cluster ${kp[2]}"
                paragraph rawHtml: true, resultHtml(title, res[k], pend, k, names)
            }
        }
        section("Write and reporting (ZCL)") {
            paragraph "<small>Writing and configuring reporting change the device: each shows the frame on a confirmation page before anything is sent. The endpoint, cluster and manufacturer code apply to the writes, reporting and cluster commands below. Values are hex, most significant byte first.</small>"
            input "w_ep", "text", title: "Endpoint (hex)", width: 2, required: false, submitOnChange: true, defaultValue: tgt.ep
            input "w_cluster", "text", title: "Cluster (hex)", width: 2, required: false, submitOnChange: true, defaultValue: "0006"
            input "w_mfg", "text", title: "Manufacturer code (hex, optional)", width: 3, required: false, submitOnChange: true
            input "w_attr", "text", title: "Attribute (hex)", width: 2, required: false, submitOnChange: true
            input "w_type", "text", title: "Type (hex: 10 bool, 20 uint8, 21 uint16, 29 int16, 30 enum8, 42 string)", width: 3, required: false, submitOnChange: true
            input "w_value", "text", title: "Value to write (hex)", width: 3, required: false, submitOnChange: true
            input "zbw:write:${ieee}", "button", title: "Write attribute…", width: 3
            input "w_min", "text", title: "Minimum report interval (s)", width: 3, required: false, submitOnChange: true
            input "w_max", "text", title: "Maximum report interval (s)", width: 3, required: false, submitOnChange: true
            input "w_change", "text", title: "Reportable change (hex, numeric types only)", width: 3, required: false, submitOnChange: true
            input "zbw:configReport:${ieee}", "button", title: "Configure reporting…", width: 4
            input "zbw:readReport:${ieee}", "button", title: "Read reporting configuration", width: 4
            Map cvWrite = confirmFor(ieee, ['write', 'configReport'])
            if (cvWrite) href "zbwConfirmWrite", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cvWrite.action]}", description: CONFIRM_LINK
            res.keySet().findAll { it.startsWith('write:') || it.startsWith('report:') }.sort().each { String k ->
                List<String> kp = k.split(':') as List<String>
                String title = (kp[0] == 'write' ? 'Write' : 'Reporting') + " endpoint ${kp[1]} cluster ${kp[2]}"
                paragraph rawHtml: true, resultHtml(title, res[k], pend, k, names)
            }
        }
        section("Cluster command") {
            input "w_cmd", "text", title: "Command (hex)", width: 2, required: false, submitOnChange: true
            input "w_payload", "text", title: "Payload (hex bytes, as sent)", width: 4, required: false, submitOnChange: true
            input "w_frameType", "enum", title: "Command type", width: 3, required: false, submitOnChange: true,
                options: [cluster: "Cluster-specific", general: "General (profile-wide)"], defaultValue: "cluster"
            input "w_toClient", "bool", title: "Server to client", width: 3, required: false, submitOnChange: true, defaultValue: false
            input "w_ddr", "bool", title: "Disable default response", width: 3, required: false, submitOnChange: true, defaultValue: false
            input "zbw:cmd:${ieee}", "button", title: "Send command…", width: 3
            Map cvCmd = confirmFor(ieee, ['cmd'])
            if (cvCmd) href "zbwConfirmCmd", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cvCmd.action]}", description: CONFIRM_LINK
            res.keySet().findAll { it.startsWith('cmd:') }.sort().each { String k ->
                List<String> kp = k.split(':') as List<String>
                paragraph rawHtml: true, resultHtml("Command endpoint ${kp[1]} cluster ${kp[2]}", res[k], pend, k, names)
            }
        }
        section("Raw command") {
            input "w_raw", "text", title: "One he command addressed to 0x${esc(r?.nwk ?: '????')}, sent as typed", width: 9, required: false, submitOnChange: true
            input "zbw:raw:${ieee}", "button", title: "Send…", width: 3
            Map cvRaw = confirmFor(ieee, ['raw'])
            if (cvRaw) href "zbwConfirmRaw", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cvRaw.action]}", description: CONFIRM_LINK
            paragraph rawHtml: true, resultHtml("Raw command", res.raw, pend, 'raw', names)
        }
        section("Binding") {
            String hub = hubIeee()
            paragraph "<small>Binds a cluster of the device to the hub (IEEE ${esc(hub ?: 'unknown')}) or, when a group is given, to that group.</small>"
            input "w_bEp", "text", title: "Source endpoint (hex)", width: 2, required: false, submitOnChange: true, defaultValue: tgt.ep
            input "w_bCluster", "text", title: "Cluster (hex)", width: 2, required: false, submitOnChange: true, defaultValue: "0006"
            input "w_bGroup", "text", title: "Group (hex, optional)", width: 3, required: false, submitOnChange: true
            input "w_bDstEp", "text", title: "Hub endpoint (hex)", width: 2, required: false, submitOnChange: true, defaultValue: "01"
            input "zbw:bind:${ieee}", "button", title: "Bind…", width: 3
            input "zbw:unbind:${ieee}", "button", title: "Unbind…", width: 3
            Map cvBind = confirmFor(ieee, ['bind', 'unbind'])
            if (cvBind) href "zbwConfirmBind", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cvBind.action]}", description: CONFIRM_LINK
            paragraph rawHtml: true, resultHtml("Bind or unbind", res.bind, pend, 'bind', names)
        }
        section("Leave and firmware") {
            input "w_rejoin", "bool", title: "Rejoin after leaving", width: 4, required: false, submitOnChange: true, defaultValue: false
            input "zbw:leave:${ieee}", "button", title: "Leave the network…", width: 4
            input "zbw:ota:${ieee}", "button", title: "Firmware update notify…", width: 4
            Map cvLeave = confirmFor(ieee, ['leave', 'ota'])
            if (cvLeave) href "zbwConfirmLeave", page: "confirmPage", title: "Confirm: ${CONFIRM_TITLES[cvLeave.action]}", description: CONFIRM_LINK
            paragraph rawHtml: true, resultHtml("Leave", res.leave, pend, 'leave', names)
            paragraph rawHtml: true, resultHtml("Firmware update notify", res.ota, pend, 'ota', names)
        }
    }
}

@Field static final Map<String, String> CONFIRM_TITLES = [write: "write an attribute", configReport: "configure reporting", cmd: "send a cluster command",
                                                          raw: "send a raw command", bind: "bind a cluster", unbind: "unbind a cluster",
                                                          leave: "leave the network", ota: "firmware update notify", unmonitor: "remove the monitor device"]
@Field static final String CONFIRM_LINK = "Opens the confirmation page (valid for 2 minutes)"

private boolean confirmOpen(Map cv) {
    return cv != null && !cv.done && !cv.cancelled && !cv.error && now() - ((cv.issued ?: 0) as long) <= CONFIRM_TTL_MS
}

// The open confirmation for one of `actions` on this device, or null.
private Map confirmFor(String ieee, List<String> actions) {
    Map cv = (Map) state.confirmView
    return cv?.ieee == ieee && cv.action in actions && confirmOpen(cv) ? cv : null
}

// Shows the device, its address, what will happen and the exact frame, as prepared by a button
// that changes a device (or the device to remove, for a monitor device). Confirm posts the
// single-use token; Cancel drops it. Done is hidden so those are the only exits.
Map confirmPage(Map params = null) {
    Map cv = (Map) state.confirmView
    Map tgt = cv ? (Map) state.targets?.get(cv.ieee) : null
    def mon = cv?.action == 'unmonitor' ? getChildDevice(monitorDni((String) cv.ieee)) : null
    if (cv?.action == 'unmonitor' && tgt == null) tgt = [name: mon?.displayName ?: cv.ieee]
    Map r = cv ? resolveTarget((String) cv.ieee, fetchZigbeeDetails()) : null
    return dynamicPage(name: "confirmPage", title: "Confirm", install: false, uninstall: false) {
        section("") {
            paragraph rawHtml: true, "<style>#formApp:has(.zbw-confirm) #fieldsetAppButtons button[value='Done'] { display:none !important; }</style><span class='zbw-confirm'></span>"
            if (cv == null || tgt == null) {
                paragraph "Nothing to confirm. Use a button that changes a device on its device page."
            } else if (cv.done && cv.action == 'unmonitor') {
                paragraph mon ? "The monitor device ${esc(mon.displayName)} could not be removed; see the logs." : "Removed the monitor device of ${esc(tgt.name)}."
            } else if (cv.done) {
                paragraph "Sent: ${esc(CONFIRM_TITLES[cv.action])} to ${esc(tgt.name)}. The result shows on the device page."
            } else if (cv.cancelled) {
                paragraph "Cancelled. Nothing was sent."
            } else if (cv.error || now() - ((cv.issued ?: 0) as long) > CONFIRM_TTL_MS) {
                paragraph "${esc(cv.error ?: 'This confirmation expired.')} Press the button on the device page again."
            } else {
                Map req = (Map) cv.args?.req
                String what = cv.action == 'leave'
                    ? "The device leaves the Zigbee network${req?.rejoin ? ' and then tries to rejoin' : ''}. To use it again you may have to pair it again."
                    : cv.action == 'unmonitor'
                    ? "The monitor device ${mon?.displayName ?: ''} is deleted. Dashboards, rules and apps that use it lose it. The Zigbee device itself is not changed."
                    : cv.action == 'ota'
                    ? "The device is told to look for a firmware update from the hub now. If the hub has one, the update starts."
                    : cv.action == 'raw'
                    ? "The command below is sent as typed. Its reply is not matched; it reaches the device's driver and the Zigbee logs."
                    : "The frame below is sent to the device; the hub puts its own sequence number in place of the 00."
                String frame = cv.args?.frame ? "<br>Frame <code>${esc(cv.args.frame)}</code>" : ''
                String warning = cv.args?.warning ? "<br><b>${esc(cv.args.warning)}</b>" : ''
                String wait = cv.wait ? "<br><i>${esc(cv.wait)}</i>" : ''
                paragraph rawHtml: true, "<div class='p-message p-message-warn p-2'><b>${esc(CONFIRM_TITLES[cv.action])}</b><br>" +
                    "Device <b>${esc(tgt.name)}</b><br>IEEE <b>${esc(cv.ieee)}</b> · address <b>${esc(r?.nwk ?: 'not on the network')}</b><br>${esc(what)}" +
                    "${frame}${warning}${wait}</div>"
                input "zbw:confirm:${cv.token}", "button", title: "Confirm", width: 3
                input "zbw:cancel:${cv.token}", "button", title: "Cancel", width: 3
            }
            if (cv?.ieee && state.targets?.containsKey(cv.ieee)) {
                href "zbwBack", page: "devicePage", params: [ieee: cv.ieee], title: "Back to the device", description: ""
            } else {
                href "zbwBackMain", page: "mainPage", title: "Back", description: ""
            }
        }
    }
}

Map hubPage(Map params = null) {
    Map info = fetchChildAndRouteInfo()
    Map details = fetchZigbeeDetails()
    Map names = nameMaps(details)
    boolean refreshing = info.topologyRefreshing == true && !info.neighbors && !info.routes
    Map s = summarizeHubTables(info, details)
    return dynamicPage(name: "hubPage", title: "Hub Zigbee tables", refreshInterval: refreshing ? 3 : 0) {
        section("") {
            String line = "Channel <b>${esc(s.channel ?: '?')}</b> · PAN <b>${esc(s.panId ?: '?')}</b> · extended PAN <b>${esc(s.extendedPanId ?: '?')}</b> · " +
                "power level <b>${esc(s.powerLevel ?: '?')}</b> · network <b>${esc(s.networkState?.toString() ?: '?')}</b><br>" +
                "${s.neighbors.size()} neighbours · ${s.children.size()} children · ${s.routes.size()} routes · lowest neighbour LQI <b>${esc(s.lowestLqi ?: '–')}</b>"
            if (refreshing) line += "<br><i>The hub is refreshing its tables…</i>"
            paragraph line
            input "zbw:hubTables", "button", title: "Refresh", width: 3
        }
        section("Neighbours") {
            paragraph rawHtml: true, htmlTable(["Device", "Address", "LQI", "In cost", "Out cost", "Age"],
                s.neighbors.sort { it.lqi ?: 0 }.collect { [nameOf(names, it.nwk, null), it.nwk, it.lqi, it.inCost, it.outCost, it.age] })
        }
        section("Children") {
            paragraph rawHtml: true, s.children ? htmlTable(["Child"], s.children.collect { [it.toString()] }) : "<i>No children</i>"
        }
        section("Routes") {
            paragraph rawHtml: true, htmlTable(["Destination", "Address", "Next hop", "Status", "Age"],
                s.routes.collect { [nameOf(names, it.dest, null), it.dest, "${nameOf(names, it.nextHop, null)} (${it.nextHop})", it.status, it.age] })
        }
    }
}

// RENDERING

private String resultHtml(String title, Map res, List pend, String kind, Map names) {
    boolean waiting = pend.any { it.kind == kind }
    StringBuilder sb = new StringBuilder("<div class='mb-3'><b>${esc(title)}</b>")
    if (res) sb.append(" <small class='text-color-secondary'>${fmtTime(res.time as Long)}</small>".toString())
    if (waiting) sb.append(" <i>waiting for a reply…</i>")
    sb.append("<br>")
    if (!res) return sb.append(waiting ? "" : "<i class='text-color-secondary'>No result yet.</i>").append("</div>").toString()
    if (res.status == 'sent') {
        return sb.append("Sent <code>${esc(res.frame)}</code><br><small>A raw command's reply is not matched; it reaches the device's driver and the Zigbee logs.</small></div>".toString()).toString()
    }
    if (res.status != 'ok') {
        sb.append("<div class='p-message p-message-warn p-2'>${esc(res.status)}: ${esc(res.error)}".toString())
        if (res.frame) sb.append("<br><small>request <code>${esc(res.frame)}</code></small>".toString())
        if (res.raw) sb.append("<br><small>reply <code>${esc(((List) res.raw).join(' '))}</code></small>".toString())
        if (res.partial) sb.append("<br><small>${((List) res.partial).size()} entries read before the failure</small>".toString())
        return sb.append("</div></div>").toString()
    }
    Map d = (Map) res.data ?: [:]
    String base = kind.split(':')[0]
    switch (base) {
        case 'neighbors':
            sb.append("${d.total} entries<br>".toString())
            sb.append(htmlTable(["Device", "Address", "IEEE", "Type", "Relationship", "Rx on idle", "Depth", "LQI"],
                ((List) d.entries).collect { [nameOf(names, it.nwk, it.ieee), it.nwk, it.ieee, it.deviceType, it.relationship, it.rxOnWhenIdle, it.depth, it.lqi] }))
            break
        case 'routes':
            sb.append("${d.total} entries<br>".toString())
            sb.append(htmlTable(["Destination", "Address", "Next hop", "Status", "Flags"],
                ((List) d.entries).collect { [nameOf(names, it.dest, null), it.dest, "${nameOf(names, it.nextHop, null)} (${it.nextHop})", it.routeStatus,
                    [it.manyToOne ? 'many-to-one' : null, it.routeRecordRequired ? 'route record' : null, it.memoryConstrained ? 'memory constrained' : null].findAll { it }.join(', ')] }))
            break
        case 'bindings':
            sb.append("${d.total} entries<br>".toString())
            sb.append(htmlTable(["Source endpoint", "Cluster", "Destination", "Destination endpoint"],
                ((List) d.entries).collect { [it.srcEp, it.cluster, it.dstGroup ? "group ${it.dstGroup}" : "${nameOf(names, null, it.dstIeee)} (${it.dstIeee})", it.dstEp ?: ''] }))
            break
        case 'endpoints':
            sb.append(esc(((List) d.endpoints ?: []).join(', ') ?: 'none'))
            break
        case 'simple':
            sb.append(htmlTable(["Field", "Value"], [["Profile", d.profileId], ["Device id", d.deviceId], ["Version", d.version],
                ["In clusters", ((List) d.inClusters ?: []).join(' ')], ["Out clusters", ((List) d.outClusters ?: []).join(' ')]]))
            break
        case 'read':
            sb.append(htmlTable(["Attribute", "Type", "Status", "Value"],
                ((List) d.records ?: []).collect { [hex4(it.attr), it.type != null ? "0x${hex2(it.type as int)}" : '', it.statusName, it.value] }))
            break
        case 'discover':
            sb.append(htmlTable(["Attribute", "Type"], ((List) d.attrs ?: []).collect { [hex4(it.id), "0x${hex2(it.type as int)}"] }))
            if (!d.complete) sb.append("<small>The list may be incomplete.</small>")
            break
        case 'write':
            sb.append(htmlTable(["Attribute", "Type", "Value sent (wire order)", "Status"],
                [[d.attr, d.type != null ? "0x${hex2(d.type as int)}" : '', d.value, d.defaultResponse ?: ((List) d.records ?: []).collect { it.statusName }.join(', ')]]))
            break
        case 'report':
            if (d.op == 'read') {
                sb.append(htmlTable(["Attribute", "Status", "Type", "Min interval (s)", "Max interval (s)", "Reportable change", "Timeout (s)"],
                    ((List) d.records ?: []).collect { [hex4(it.attr), it.statusName, it.type != null ? "0x${hex2(it.type as int)}" : '', it.min, it.max, it.change, it.timeout] }))
            } else {
                sb.append(htmlTable(["Attribute", "Type", "Min interval (s)", "Max interval (s)", "Reportable change (wire order)", "Status"],
                    [[d.attr, d.type != null ? "0x${hex2(d.type as int)}" : '', d.min, d.max, d.change ?: '', d.defaultResponse ?: ((List) d.records ?: []).collect { it.statusName }.join(', ')]]))
            }
            break
        case 'cmd':
            Map rp = (Map) d.reply
            sb.append(htmlTable(["Command", "Answer", "From endpoint", "Received"], [["0x${hex2(d.cmd as int)}", d.defaultResponse ? "default response ${d.defaultResponse}" :
                "${rp?.clusterSpecific ? 'cluster' : 'general'} command 0x${hex2((rp?.cmd ?: 0) as int)} ${((List) rp?.payload ?: []).join(' ')}",
                rp?.srcEp ?: '', rp?.receivedAt ? fmtTime(rp.receivedAt as Long) : '']]))
            if (rp?.matchedBy == 'cluster and direction') sb.append("<small>Frame matched by cluster and direction; it may not be the reply.</small><br>")
            break
        case 'bind':
            sb.append(htmlTable(["Operation", "Endpoint", "Cluster", "Destination"],
                [[d.op, d.ep, d.cluster, d.group ? "group ${d.group}" : "${nameOf(names, '0000', d.dstIeee)} (${d.dstIeee}) endpoint ${d.dstEp}"]]))
            break
        case 'leave':
            sb.append(esc("The device accepted the leave request${d.rejoin ? ' and will try to rejoin' : ''}."))
            break
        case 'ota':
            Map q = (Map) d.query ?: [:]
            sb.append(htmlTable(["Field", "Value"], [["Manufacturer code", q.mfgCode], ["Image type", q.imageType], ["Current file version", q.fileVersion]]))
            break
        default:   // node, power
            sb.append(htmlTable(["Field", "Value"], d.findAll { k, v -> k != 'nwk' }.collect { k, v -> [k, k == 'macCapability' || k == 'serverMask' ? "0x${Integer.toHexString(v as int).toUpperCase()}" : v] }))
    }
    if (res.lqi != null) sb.append("<small class='text-color-secondary'>reply LQI ${esc(res.lqi)}, RSSI ${esc(res.rssi)}</small>".toString())
    return sb.append("</div>").toString()
}

@CompileStatic
private static String hex4(Object v) {
    return v == null ? '' : String.format('%04X', (v as int))
}

private String htmlTable(List<String> headers, List<List> rows) {
    if (!rows) return "<i class='text-color-secondary'>Empty</i>"
    StringBuilder sb = new StringBuilder("<table style='border-collapse:collapse;font-size:90%'><tr>")
    headers.each { sb.append("<th style='text-align:left;padding:2px 8px;border-bottom:1px solid #ccc'>${esc(it)}</th>".toString()) }
    sb.append("</tr>")
    rows.each { List row ->
        sb.append("<tr>")
        row.each { sb.append("<td style='padding:2px 8px'>${esc(it == null ? '' : it.toString())}</td>".toString()) }
        sb.append("</tr>")
    }
    return sb.append("</table>").toString()
}

// [byNwk: [nwk: name], byIeee: [ieee: name]] from the zigbeeDetails map.
private Map nameMaps(Map details) {
    Map byNwk = ['0000': 'Hub']
    Map byIeee = [:]
    ((List) (details?.devices ?: [])).each { d ->
        if (d.shortZigbeeId) byNwk[d.shortZigbeeId.toString().toUpperCase()] = d.name
        if (d.zigbeeId) byIeee[d.zigbeeId.toString().toUpperCase()] = d.name
    }
    return [byNwk: byNwk, byIeee: byIeee]
}

private String nameOf(Map names, Object nwk, Object ieee) {
    String n = ieee ? names.byIeee[ieee.toString().toUpperCase()] : null
    if (!n && nwk) n = names.byNwk[nwk.toString().toUpperCase()]
    return n ?: 'unknown'
}

private String targetName(String ieee) {
    return state.targets?.get(ieee)?.name ?: ieee
}

private Long lastResultTime(String ieee) {
    List times = ((Map) (state.results?.get(ieee) ?: [:])).values().collect { it.time }.findAll { it != null }
    return times ? (times.max() as Long) : null
}

private String fmtTime(Long t) {
    return t ? new Date(t).format("yyyy-MM-dd HH:mm:ss", location.timeZone) : ''
}

@CompileStatic
private static String esc(Object s) {
    if (s == null) return ''
    return s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;')
}

// LOGGING
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  🏷️ Ver  ·  ⚠️ Warn  🛑 Error
private String logp(String e) { "${e} ${app.getLabel()}: " }
void logEvt(String m)   { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd(String m)   { if (txtEnable != false) log.info logp('⬆️') + m }
void logCfg(String m)   { if (txtEnable != false) log.info logp('🔧') + m }
void logNet(String m)   { if (debugEnable) log.debug logp('🌐') + m }
void logVer(String m)   { log.warn logp('🏷️') + m }
void logWarn(String m)  { log.warn logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logInfo(String m)  { if (txtEnable != false) log.info "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }
