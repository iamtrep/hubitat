#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for assembleForumData (hub_diagnostics_ui.html).
//
// The forum export is now built entirely client-side: btnForum batch-fetches the tab endpoints
// and assembleForumData reshapes them into the `d` object buildForumMarkdown consumes — the same
// shape the hub used to ship from /api/forum/data (apiForumData, now deleted). Same source of
// truth: the tab endpoints and the old aggregator both derive from analyzeDevices/analyzeApps/
// analyzeNetwork on the hub. This test pins the remap field-by-field so a tab-endpoint shape change
// can't silently corrupt the export. assembleForumData is dependency-free, so it's extracted by
// name (brace-matched) and run directly — bound to the shipped code, not a copy.
//
// Run: node apps/HubDiagnostics/tests/test-forum-export.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_diagnostics_ui.html'), 'utf8');

function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start);
  let depth = 0, i = open;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'hd_forum_' + process.pid + '.js');
fs.writeFileSync(tmp, extractFn('assembleForumData') + '\nmodule.exports = { assembleForumData };');
const { assembleForumData } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

// Representative tab-endpoint payloads (shapes mirror getDashboardData/getDevicesData/getAppsData/
// getNetworkData/getHealthData/getPerformanceData/apiGetSettings in HubDiagnostics.groovy).
const r = {
  dashboard: { appVersion: '5.57.0', hub: { name: 'Hub', hardware: 'C-8 Pro', firmware: '2.5.0' } },
  devices: {
    summary: { totalDevices: 50, activeDevices: 45, inactiveDevices: 3, disabledDevices: 2 },
    byConnectionType: { zwave: 20, zigbee: 25, lan: 5 },
    byIntegration: { 'Hubitat': 30, 'Philips Hue': 20 },
    lowBatteryDevices: [{ id: 1, name: 'Front Door', type: 'Contact Sensor', battery: 15 }],
    deviceRows: [
      { id: 2, name: 'Old Switch', type: 'Z-Wave Switch', status: 'Inactive' },
      { id: 3, name: 'Active Light', type: 'Bulb', status: 'Active' }
    ]
  },
  apps: {
    summary: { totalApps: 12, builtInApps: 8, userApps: 4 },
    userApps: [
      { id: 10, label: 'Morning Lights', type: 'Rule Machine', parentId: null, disabled: false },
      { id: 11, label: 'Away Mode', type: 'Rule Machine' }
    ],
    builtInInstances: { 'Groups and Scenes': 3, 'Notifier': 2 }
  },
  network: {
    uptimeSeconds: 90000,
    network: { hasEthernet: true, hasWiFi: false, lanAddr: '192.168.1.5' },
    zwave: { enabled: true, healthy: true, region: 'US', zwaveJS: false, nodeCount: 20, version: '7.18',
             mesh: { avgPer: 0.5, totalRouteChanges: 4, nodes: [{ nodeId: 2, name: 'N2', state: 'OK', per: 0 }] },
             ghostNodes: [{ id: 9, name: 'Ghost', kind: 'ghost' }] },
    zigbee: { enabled: true, healthy: true, networkState: 'ONLINE', channel: 20, powerLevel: 8, deviceCount: 25,
              mesh: { neighbors: 10, avgLqi: 200, minLqi: 150, maxLqi: 255,
                      neighborDetails: [{ shortId: 'A1', lqi: 200, age: 1 }, { shortId: 'B2', lqi: 100, age: 9 }] } },
    hubMesh: { enabled: true, peers: [{ name: 'Maison', ip: '192.168.1.213', offline: false, deviceCount: 20, varCount: 2 }] },
    matter: { enabled: true, installed: true, devices: [{}, {}], networkState: 'Online', fabricId: 'A98634F7' }
  },
  health: {
    hub: { name: 'Hub', hardware: 'C-8 Pro', firmware: '2.5.0' },
    resources: { freeOSMemory: 500000, cpuAvg5min: 1.2, freeJavaMemory: 100000, totalJavaMemory: 512000 },
    temperature: 41, databaseSize: 120,
    stateCompression: { enabled: true }, eventStateLimits: { maxEvents: 1000, maxStateAgeDays: 7 },
    alertSignals: { platformAlerts: [] }, firmwareUpdate: null
  },
  performance: { stats: { uptime: '25h 0m 0s', totalDevicesRuntime: '1h 28m 5s', devicePct: '1.2%',
                          totalAppsRuntime: '3h 26m 2s', appPct: '2.9%',
                          deviceStats: [{ name: 'local', pctTotal: 0.365, count: 137104, average: 11.3 }],
                          appStats: [{ name: 'Hub Diagnostics', type: 'app', pctTotal: 0.728, count: 13808, average: 223.7 }] },
                 radioStats: { zwave: [{ name: 'N2', deviceId: 2, msgCount: 100 }], zigbee: [{ name: 'Z6', id: 6, msgCount: 50 }] } },
  settings: { obfuscateForumExport: true }
};

const d = assembleForumData(r);

t('hub identity from health.hub', () => {
  assert.strictEqual(d.hubInfo.hardware, 'C-8 Pro');
  assert.strictEqual(d.hubInfo.firmware, '2.5.0');
});
t('system/health fields (resources, temp, db, stateCompression, eventStateLimits)', () => {
  assert.strictEqual(d.resources.freeOSMemory, 500000);
  assert.strictEqual(d.temperature, 41);
  assert.strictEqual(d.databaseSize, 120);
  assert.strictEqual(d.stateCompression.enabled, true);
  assert.strictEqual(d.eventStateLimits.maxEvents, 1000);
});
t('deviceStats: summary unnested + by-maps + low battery keeps type (obfuscation)', () => {
  assert.strictEqual(d.deviceStats.totalDevices, 50);
  assert.strictEqual(d.deviceStats.disabledDevices, 2);
  assert.strictEqual(d.deviceStats.byIntegration['Philips Hue'], 20);
  assert.strictEqual(d.deviceStats.lowBatteryDevices[0].type, 'Contact Sensor'); // needed for obfuscate mode
  assert.strictEqual(d.deviceStats.allDevices.find(x => x.status === 'Inactive').name, 'Old Switch');
});
t('appStats: userAppsList grouped-by app type; builtInInstances passed through', () => {
  assert.strictEqual(d.appStats.totalApps, 12);
  assert.deepStrictEqual(d.appStats.userAppsList.map(a => a.name), ['Rule Machine', 'Rule Machine']);
  assert.strictEqual(d.appStats.builtInInstances['Notifier'], 2);
});
t('networkData carries raw config + zwave/zigbee with counts for the builder tweaks', () => {
  assert.strictEqual(d.networkData.network.lanAddr, '192.168.1.5');
  assert.strictEqual(d.networkData.zwave.nodeCount, 20);      // buildForumMarkdown reads nodeCount
  assert.strictEqual(d.networkData.zigbee.deviceCount, 25);   // buildForumMarkdown reads deviceCount
  assert.strictEqual(d.networkData.zwave.region, 'US');
});
t('zwaveMesh / ghostNodes / zwaveVersion lifted from network.zwave', () => {
  assert.strictEqual(d.zwaveMesh.avgPer, 0.5);
  assert.strictEqual(d.zwaveMesh.nodes[0].nodeId, 2);
  assert.strictEqual(d.ghostNodes[0].kind, 'ghost');
  assert.strictEqual(d.zwaveVersion, '7.18');
});
t('zigbeeMesh: neighbors remapped to the neighborDetails array (builder expects an array)', () => {
  assert(Array.isArray(d.zigbeeMesh.neighbors), 'neighbors must be the array');
  assert.strictEqual(d.zigbeeMesh.neighbors.length, 2);
  assert.strictEqual(d.zigbeeMesh.neighbors[0].shortId, 'A1');
  assert.strictEqual(d.zigbeeMesh.avgLqi, 200);
});
t('radioStats passthrough + uptimeMin derived + obfuscate from settings', () => {
  assert.strictEqual(d.radioStats.zwave[0].msgCount, 100);
  assert.strictEqual(d.uptimeMin, 1500);   // 90000s / 60
  assert.strictEqual(d.obfuscate, true);
});
t('Matter / Hub Mesh / Performance sections carried through for the builder', () => {
  // Matter is a raw passthrough; builder reads networkData.matter.{installed,networkState,fabricId}.
  assert.strictEqual(d.networkData.matter.fabricId, 'A98634F7');
  assert.strictEqual(d.networkData.matter.networkState, 'Online');
  // Hub Mesh: builder reads networkData.hubMesh.peers (reshaped), not the raw hubList.
  assert(Array.isArray(d.networkData.hubMesh.peers), 'hubMesh.peers must be present');
  assert.strictEqual(d.networkData.hubMesh.peers[0].deviceCount, 20);
  assert.strictEqual(d.networkData.hubMesh.peers[0].ip, '192.168.1.213');
  // Performance: stats passthrough keeps the runtime + CPU-by-type fields the builder renders.
  assert.strictEqual(d.stats.totalDevicesRuntime, '1h 28m 5s');
  assert.strictEqual(d.stats.deviceStats[0].name, 'local');
  assert.strictEqual(d.stats.appStats[0].pctTotal, 0.728);
});
t('degrades safely on empty input (no throw)', () => {
  const e = assembleForumData({});
  assert.deepStrictEqual(e.hubInfo, {});
  assert.strictEqual(e.zwaveMesh, null);
  assert.strictEqual(e.obfuscate, false);
  assert.deepStrictEqual(e.ghostNodes, []);
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
