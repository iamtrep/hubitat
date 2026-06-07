#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// End-to-end render test for the client-side forum export. Extracts the real buildForumMarkdown +
// assembleForumData + their dependency functions from hub_diagnostics_ui.html, feeds synthetic tab
// payloads modeled on real hub baselines (a C-7 with Z-Wave nodes, Zigbee, Matter, Hub Mesh, and
// Performance data), renders the markdown, and asserts every section the server-built export
// produced is present and correct. This is the end-to-end guard that the client rebuild (delete
// apiForumData) reproduces the old /api/forum/data → buildForumMarkdown output.
//
// Run: node apps/HubDiagnostics/tests/test-forum-render.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_diagnostics_ui.html'), 'utf8');

function extractFn(name) {
  const s = src.indexOf('function ' + name + '(');
  assert(s >= 0, 'function not found: ' + name);
  const o = src.indexOf('{', s); let d = 0, i = o;
  for (; i < src.length; i++) { if (src[i] === '{') d++; else if (src[i] === '}') { d--; if (d === 0) { i++; break; } } }
  return src.slice(s, i);
}
const HELPERS = ['makeObf', 'assembleForumData', 'buildForumMarkdown', 'composeAlerts', 'flattenRadioDevices',
  'fmem', 'ftemp', 'tScale', 'tSym', 'c2u', 'splitGhostNodes', 'zbWeakNeighbors', 'zbStaleNeighbors', 'isNewer'];
const CD = (src.match(/const CD=\{[^;]*\};/) || [])[0];
assert(CD, 'CD const not found');
const THRESHOLDS = (src.match(/const ZWAVE_PER_CRIT=[^;]+;/) || [])[0];
assert(THRESHOLDS, 'threshold consts not found');
const harness =
  'const CODE_VERSION="5.58.0";\n' +
  'let TH={temperatureScale:"C",critMemMb:75,warnMemMb:100,warnCpuLoad:4,critCpuLoad:8,warnTempC:50,critTempC:60,chattyDeviceThreshold:10};\n' +
  THRESHOLDS + '\n' + CD + '\n' + HELPERS.map(extractFn).join('\n') +
  '\nconst Obf = makeObf();' +
  '\nmodule.exports = { assembleForumData, buildForumMarkdown, Obf };';
const tmp = path.join(os.tmpdir(), 'hd_render_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const { assembleForumData, buildForumMarkdown, Obf } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

// Synthetic tab payloads modeled on a real C-7 export (Z-Wave nodes, Zigbee, Matter, Hub Mesh, Perf).
const r = {
  dashboard: { appVersion: '5.58.0', hub: { name: 'Maison', hardware: 'C-7', firmware: '2.5.0.143' } },
  devices: {
    summary: { totalDevices: 284, activeDevices: 215, inactiveDevices: 65, disabledDevices: 4 },
    byConnectionType: { paired: 121, virtual: 79, other: 33 },
    byIntegration: { 'Zigbee': 111, 'Virtual': 79, 'Z-Wave': 5 },
    lowBatteryDevices: [{ id: 1, name: 'Fuite Piano', type: 'Water Sensor', battery: 0 }],
    deviceRows: [{ id: 2, name: 'Apple TV Salon', type: 'AppleTV', status: 'Inactive' },
                 { id: 3, name: 'Active Thing', type: 'X', status: 'Active' }]
  },
  apps: {
    summary: { totalApps: 339, builtInApps: 282, userApps: 57 },
    userApps: [{ id: 10, label: 'Hub Diagnostics', type: 'Hub Diagnostics' }],
    builtInInstances: { 'Rule-5.1': 160, 'Notifier': 17 }
  },
  network: {
    uptimeSeconds: 332601, // 3d 20h 23m 21s
    network: { hasEthernet: true, hasWiFi: false, lanAddr: '192.168.1.213' },
    zwave: {
      enabled: true, healthy: true, region: 'US', zwaveJS: false, nodeCount: 5, version: '7.15 (Protocol 7.18)',
      mesh: {
        avgPer: 0.0, avgRssi: 12, totalRouteChanges: 0,
        nodes: [
          { nodeId: 17, name: 'Serrure Avant', security: 'S2 Access Control', rtt: null, per: 0, rssiStr: '12dB', route: '01 -> 11 40kbps', msgCount: 0, driverType: 'sys', state: 'OK', neighbors: 3 },
          { nodeId: 19, name: 'Switch Ventilateur Elliot', security: 'None', per: 0, rssiStr: '15dB', route: '01 -> 13 100kbps', msgCount: 0, driverType: 'usr', state: 'OK', neighbors: 2 },
          // chalet-style node: real RTT (1ms), absent RSSI (—), multi-hop route
          { nodeId: 21, name: 'Z-Wave Range Extender', security: 'S2 Authenticated', rtt: 1, per: 0, rssiStr: null, route: '01 -> 10 -> 0F 40kbps', msgCount: 0, driverType: 'usr', state: 'OK', neighbors: 1 }
        ]
      },
      ghostNodes: []
    },
    zigbee: {
      enabled: true, healthy: true, networkState: 'ONLINE', channel: 11, powerLevel: 8, deviceCount: 111,
      mesh: { neighbors: 16, avgLqi: 251, minLqi: 239, maxLqi: 255, neighborDetails: [{ shortId: 'A1', lqi: 251, age: 1 }] }
    },
    hubMesh: { enabled: true, peers: [{ name: 'Maison-pro', ip: '192.168.1.86', offline: false, deviceCount: 5, varCount: 1 }] },
    matter: { enabled: true, installed: true, devices: [{}, {}, {}, {}, {}], networkState: 'Online', fabricId: '12FC7CD609B9D214' }
  },
  health: {
    hub: { name: 'Maison', hardware: 'C-7', firmware: '2.5.0.143' },
    resources: { freeOSMemory: 131000, cpuAvg5min: 0.44, freeJavaMemory: 141000, totalJavaMemory: 324000 },
    temperature: 43, databaseSize: 96,
    stateCompression: { enabled: true }, eventStateLimits: { maxEvents: 11, maxEventAgeDays: 31, maxStateAgeDays: 7 },
    alertSignals: { platformAlerts: [], hubMessages: [] }, firmwareUpdate: null
  },
  performance: {
    stats: {
      uptime: '3d 20h 23m 21s', totalDevicesRuntime: '4h 40m 56s', devicePct: '5.1%',
      totalAppsRuntime: '6h 39m 27s', appPct: '7.2%',
      deviceStats: [{ name: 'Ping Ecobee via LAN', pctTotal: 1.198, count: 14828, average: 268.6 }],
      appStats: [{ name: 'Hub Diagnostics', type: 'app', pctTotal: 1.132, count: 3799, average: 990.3 }]
    },
    radioStats: { zwave: [], zigbee: [{ name: 'Prise Frigo', id: 6, msgCount: 15801 }] }
  },
  settings: { obfuscateForumExport: false }
};

const md = buildForumMarkdown(assembleForumData(r));

let pass = 0, fail = 0;
function has(needle, label) {
  if (md.includes(needle)) { pass++; console.log('  ok   ' + (label || needle)); }
  else { fail++; console.log('  FAIL ' + (label || needle) + '\n         missing: ' + JSON.stringify(needle)); }
}

console.log('forum export — full render');
// System & Health
has('### System & Health');
has('| Model | C-7 |');
has('| Firmware | 2.5.0.143 |');
has('| Uptime | 3d 20h 23m 21s |');
has('| Connection | Ethernet (DHCP) |');
has('| State Compression | Enabled |');
has('No active alerts.', 'no-alerts line (composeAlerts ran clean)');
// Devices
has('### Devices');
has('| Total | 284 |');
has('| Zigbee | 111 |', 'by-integration row');
has('**Low Battery:** Fuite Piano (0%)');
has('Apple TV Salon', 'inactive device listed');
// Apps
has('- **Total:** 339 (Built-in: 282, User: 57)');
has('- Rule-5.1 (×160)', 'built-in instance count');
has('- Hub Diagnostics', 'user app listed');
// Z-Wave — the node table path (the second baseline exercised this; the first had 0 nodes)
has('### Z-Wave');
has('**Nodes:** 5');
has('**Avg RSSI:** 12 dBm');
has('| 17 | Serrure Avant | S2 Access Control |', 'z-wave node row');
has('01 -> 11 40kbps', 'node route');
has('| Built-in |', 'driverType sys -> Built-in');
has('| User |', 'driverType usr -> User');
has('| 1ms | 0% | — |', 'node RTT present (1ms) + null RSSI fallback (—)');
has('01 -> 10 -> 0F 40kbps', 'multi-hop route');
// Zigbee
has('### Zigbee');
has('**Devices:** 111');
has('**LQI:** avg 251, min 239, max 255');
// Matter
has('### Matter');
has('**Fabric:** 12FC7CD609B9D214');
// Hub Mesh — the bug the baseline caught (reshaped peers vs raw hubList)
has('### Hub Mesh');
has('| Maison-pro | 192.168.1.86 | Online | 5 | 1 |', 'hub mesh peer row');
// Performance
has('### Performance');
has('**Device Runtime:** 4h 40m 56s (5.1% busy)');
has('**Top Device Types by CPU:**');
has('Ping Ecobee via LAN');
has('**Top App Types by CPU:**');
has('**Top Talkers:**');
has('Prise Frigo', 'top talker');
has('15801', 'top talker total msgs');
// Footer
has('*Generated by Hub Diagnostics v5.58.0*');

// ── Scenario 2: alerts present, stale Zigbee neighbors, NO Hub Mesh (modeled on a C-7 w/o mesh) ──
// Exercises the conditional paths scenario 1 didn't: composeAlerts emitting real entries, the
// Stale Neighbors line, and the Hub Mesh section being omitted when there are no peers.
const r2 = {
  dashboard: { appVersion: '5.58.0', hub: { name: 'Andree', hardware: 'C-7', firmware: '2.5.0.136' } },
  devices: { summary: { totalDevices: 85, activeDevices: 53, inactiveDevices: 31, disabledDevices: 1 },
             byConnectionType: { paired: 26 }, byIntegration: { 'Zigbee': 22 }, lowBatteryDevices: [], deviceRows: [] },
  apps: { summary: { totalApps: 130, builtInApps: 110, userApps: 20 }, userApps: [], builtInInstances: {} },
  network: {
    uptimeSeconds: 1238828,
    network: { hasEthernet: true, hasWiFi: false, lanAddr: '192.168.78.112' },
    zwave: { enabled: true, healthy: true, region: 'US', nodeCount: 3, version: '7.18 (Protocol 7.18)',
             mesh: { avgPer: 0, avgRssi: 28, nodes: [] }, ghostNodes: [] },
    zigbee: { enabled: true, healthy: true, networkState: 'ONLINE', channel: 15, powerLevel: 8, deviceCount: 22,
              mesh: { neighbors: 4, avgLqi: 245, minLqi: 176, maxLqi: 254,
                      neighborDetails: [{ shortId: '39E4', lqi: 200, age: 10 }, { shortId: 'B44B', lqi: 180, age: 12 },
                                        { shortId: 'C1', lqi: 245, age: 1 }, { shortId: 'C2', lqi: 244, age: 2 }] } },
    hubMesh: null,  // no Hub Mesh peers — section must be omitted
    matter: { enabled: true, installed: true, devices: [{}], networkState: 'Online', fabricId: '9F93BB6F02A43E79' }
  },
  health: {
    hub: { name: 'Andree', hardware: 'C-7', firmware: '2.5.0.136' },
    resources: { freeOSMemory: 123000, cpuAvg5min: 0.28 }, temperature: 43, databaseSize: 24,
    stateCompression: { enabled: false }, eventStateLimits: { maxEvents: 11 },
    // Platform update flagged via platformAlerts + a hub message; firmwareUpdate null so neither is deduped.
    alertSignals: { platformAlerts: [{ key: 'platformUpdateAvailable', name: 'Platform Update Available', severity: 'warning' }],
                    hubMessages: ['Platform update 2.5.0.146 available (dismiss)'] },
    firmwareUpdate: null
  },
  performance: { stats: { uptime: '14d 8h 7m 8s', totalDevicesRuntime: '8h 46m 59s', devicePct: '2.6%', deviceStats: [], appStats: [] },
                 radioStats: { zwave: [], zigbee: [] } },
  settings: { obfuscateForumExport: false }
};
const md2 = buildForumMarkdown(assembleForumData(r2));
function has2(needle, label) {
  if (md2.includes(needle)) { pass++; console.log('  ok   [s2] ' + (label || needle)); }
  else { fail++; console.log('  FAIL [s2] ' + (label || needle) + '\n         missing: ' + JSON.stringify(needle)); }
}
function hasNot2(needle, label) {
  if (!md2.includes(needle)) { pass++; console.log('  ok   [s2] ' + label); }
  else { fail++; console.log('  FAIL [s2] ' + label + '\n         should be absent: ' + JSON.stringify(needle)); }
}
console.log('forum export — scenario 2 (alerts / stale neighbors / no hub mesh)');
has2('### Alerts', 'Alerts section present');
has2('- **warning**: Platform Update Available', 'platform-update warning alert');
has2('Platform update 2.5.0.146 available', 'platform-update hub message (info)');
hasNot2('No active alerts.', 'no "No active alerts" line when alerts exist');
has2('(2 stale)', 'neighbor line notes stale count');
has2('**Stale Neighbors:** 39E4, B44B', 'stale neighbor short IDs');
hasNot2('### Hub Mesh', 'Hub Mesh section omitted (no peers)');
has2('**Fabric:** 9F93BB6F02A43E79', 'Matter still renders');

// --- obfuscation: names become aliases; prose shows alias ALONE (no appended type) ---
{
  const rObf = JSON.parse(JSON.stringify(r));
  rObf.settings = { obfuscateForumExport: true };
  const mdObf = buildForumMarkdown(assembleForumData(rObf));
  assert.ok(!/Fuite Piano/.test(mdObf), 'real device name leaked into obfuscated export');
  assert.ok(!/Apple TV Salon/.test(mdObf), 'real device name leaked into obfuscated export');
  // Old behavior substituted the driver TYPE for the name in prose; the agreed design
  // aliases the name and shows the alias ALONE — so the type must NOT appear in prose.
  assert.ok(!/Water Sensor/.test(mdObf), 'low-battery prose still shows the driver type (must be alias alone)');
  // buildForumMarkdown left Obf enabled + the name registered/primed, so this is the exact alias it emitted:
  const fpAlias = Obf.nm('Fuite Piano');
  assert.ok(/^[a-z]+-[a-z]+$/.test(fpAlias), 'unexpected alias format: ' + fpAlias);
  assert.ok(mdObf.includes(fpAlias), 'expected alias "' + fpAlias + '" not found in obfuscated export');
  console.log('  ok   obfuscated export aliases names (alias-alone prose, no types)');
}

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
