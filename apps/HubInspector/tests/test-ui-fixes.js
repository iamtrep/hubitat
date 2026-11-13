#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction test for four fixes in hub_inspector_ui.html. The functions are loaded from the shipped
// file by name or block, so the test stays bound to the shipped code. No hub or browser needed.
//  - B1-4  api(): concurrent calls for one endpoint share one request; a rejection is not kept; a
//          response in flight across drop() is not cached; a stalled request times out.
//  - B1-5  ZCL decoder: a truncated or malformed attribute value does not throw.
//  - B1-6  forum export: with Ethernet and Wi-Fi both active the System & Health table stays whole.
//  - B2-1  computeSnapshotDiff: Hub Mesh peers come from the stored snapshot's `peers` list.
//
// Run: node apps/HubInspector/tests/test-ui-fixes.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');

function extractFn(name, prefix) {
  const s = src.indexOf((prefix || '') + 'function ' + name + '(');
  assert(s >= 0, 'function not found: ' + name);
  const o = src.indexOf('{', s); let d = 0, i = o;
  for (; i < src.length; i++) { if (src[i] === '{') d++; else if (src[i] === '}') { d--; if (d === 0) { i++; break; } } }
  return src.slice(s, i);
}
function load(tag, code, exportsList) {
  const tmp = path.join(os.tmpdir(), 'hd_uifix_' + tag + '_' + process.pid + '.js');
  fs.writeFileSync(tmp, code + '\nmodule.exports = { ' + exportsList.join(', ') + ' };');
  process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
  return require(tmp);
}
let pass = 0, fail = 0;
const queue = [];
function t(n, fn) { queue.push([n, fn]); }

// ---- B1-4 api()
{
  const timeoutConst = (src.match(/const API_TIMEOUT_MS=\d+;/) || [''])[0];
  const apiCode =
    'globalThis.S={fetchCalls:0,fetchImpl:null}; const WORKBENCH=false, MOCK_DATA={}, REPORT=null, B="", AUTH=()=>({});\n' +
    'let cache={}, inflight={};\n' +
    'const fetch=(u,o)=>{S.fetchCalls++;return S.fetchImpl(u,o)};\n' +
    'const CACHE_TTL_MS=120000;\n' +
    // The real timeout is 90 s; a short one keeps the test fast. Only the number is replaced.
    timeoutConst.replace(/\d+/, '40') + '\n' +
    // apiFetch is new with the fix; against older code it is simply absent.
    ['api', 'apiFetch', 'drop'].filter(n => src.includes('function ' + n + '(')).map(n => extractFn(n, n === 'api' || n === 'apiFetch' ? 'async ' : '')).join('\n') +
    '\n';
  const A = load('api', apiCode, ['api', 'drop']);
  const S = globalThis.S;
  const ok = body => () => Promise.resolve({ ok: true, json: () => Promise.resolve(body) });
  const later = (body, ms) => () => new Promise(res => setTimeout(() => res({ ok: true, json: () => Promise.resolve(body) }), ms));

  t('B1-4 two concurrent calls make one request', async () => {
    S.fetchCalls = 0; A.drop(); S.fetchImpl = later({ v: 1 }, 15);
    const [a, b] = await Promise.all([A.api('dashboard'), A.api('dashboard')]);
    assert.strictEqual(S.fetchCalls, 1);
    assert.deepStrictEqual(a, { v: 1 }); assert.deepStrictEqual(b, { v: 1 });
  });
  t('B1-4 a later call is served from the cache', async () => {
    const before = S.fetchCalls; await A.api('dashboard'); assert.strictEqual(S.fetchCalls, before);
  });
  t('B1-4 different endpoints do not share', async () => {
    S.fetchCalls = 0; A.drop(); S.fetchImpl = later({}, 5);
    await Promise.all([A.api('dashboard'), A.api('health')]);
    assert.strictEqual(S.fetchCalls, 2);
  });
  t('B1-4 a failed request is not kept: the next call retries', async () => {
    S.fetchCalls = 0; A.drop();
    S.fetchImpl = () => Promise.resolve({ ok: false, status: 500 });
    await assert.rejects(A.api('health'), /API 500/);
    S.fetchImpl = ok({ good: true });
    assert.deepStrictEqual(await A.api('health'), { good: true });
    assert.strictEqual(S.fetchCalls, 2);
  });
  t('B1-4 a response in flight across drop() is not cached', async () => {
    S.fetchCalls = 0; A.drop(); S.fetchImpl = later({ stale: true }, 20);
    const first = A.api('snapshots');
    A.drop('snapshots');                       // a write happened while the read was in flight
    S.fetchImpl = ok({ stale: false });
    assert.deepStrictEqual(await A.api('snapshots'), { stale: false });
    await first;
    assert.deepStrictEqual(await A.api('snapshots'), { stale: false }, 'the old response must not replace the new one');
  });
  t('B1-4 snapshot/view is never shared or cached', async () => {
    S.fetchCalls = 0; A.drop(); S.fetchImpl = ok({});
    await Promise.all([A.api('snapshot/view?key=1'), A.api('snapshot/view?key=1')]);
    assert.strictEqual(S.fetchCalls, 2);
  });
  t('B1-4 a stalled request times out', async () => {
    A.drop();
    S.fetchImpl = (u, o) => new Promise((res, rej) => { o.signal.addEventListener('abort', () => { const e = new Error('aborted'); e.name = 'AbortError'; rej(e); }); });
    await assert.rejects(A.api('network'), /API timeout/);
  });
}

// ---- B1-5 ZCL decode
{
  const START = 'const ZB_CLUSTERS=', END = '// ===== ZCL DECODE (END) =====';
  const s = src.indexOf(START), e = src.indexOf(END);
  assert(s >= 0 && e > s, 'ZCL block not found');
  const Z = load('zcl', src.slice(s, e), ['rcZbParseZcl', 'rcZbDecodedSummary']);
  const frame = (type, valBytes) => ['18', '5A', '0A', '00', '00', type].concat(valBytes);
  const sum = (payload) => Z.rcZbDecodedSummary(Z.rcZbParseZcl(payload, '0402', '01'));
  t('B1-5 a complete float32 decodes', () => {
    assert.strictEqual(sum(frame('39', ['00', '00', '80', '3F'])).short.endsWith('=1'), true);
  });
  t('B1-5 a float cut short does not throw', () => {
    const r = sum(frame('39', ['01', '02']));
    assert.strictEqual(typeof r.short, 'string');
    assert(r.short.includes('?'), r.short);
  });
  t('B1-5 a double cut short does not throw', () => { sum(frame('3A', ['01', '02', '03', '04', '05'])); });
  t('B1-5 a non-hex byte in an integer value does not throw', () => { sum(frame('21', ['ZZ', '00'])); });
  t('B1-5 an empty payload element does not throw', () => { sum(frame('23', ['', '00', '00', '00'])); });
  t('B1-5 a normal bool attribute still decodes', () => {
    const r = sum(['18', '5A', '0A', '00', '00', '10', '01']);
    assert(r.short.includes('=true'), r.short);
  });
}

// ---- B1-6 forum export table
{
  const HELPERS = ['makeObf', 'assembleForumData', 'buildForumMarkdown', 'forumMarkdownBody', 'composeAlerts', 'flattenRadioDevices',
    'fmem', 'ftemp', 'tScale', 'tSym', 'c2u', 'splitGhostNodes', 'zbWeakNeighbors', 'zbStaleNeighbors', 'zbMeshDetails', 'zwMeshStats', 'noTags', 'isNewer', 'relSummary', 'relLabel'];
  const CD = (src.match(/const CD=\{[^;]*\};/) || [])[0];
  const THRESHOLDS = (src.match(/const ZWAVE_PER_CRIT=[^;]+;/) || [])[0];
  const REL_T = (src.match(/const REL_T=\{[^;]*\};/) || [])[0];
  assert(CD && THRESHOLDS && REL_T, 'forum constants not found');
  const F = load('forum',
    'const CODE_VERSION="6.3.0";\nlet TH={temperatureScale:"C",critMemMb:75,warnMemMb:100,warnCpuLoad:4,critCpuLoad:8,warnTempC:50,critTempC:60,chattyDeviceThreshold:10};\n' +
    THRESHOLDS + '\n' + CD + '\n' + REL_T + '\n' + HELPERS.map(n => extractFn(n)).join('\n') + '\nconst Obf = makeObf();',
    ['assembleForumData', 'buildForumMarkdown']);
  const payload = (net) => ({
    dashboard: { appVersion: '6.3.0', hub: { name: 'Hub', hardware: 'C-8 Pro', firmware: '2.5.3.1' } },
    devices: { summary: { totalDevices: 1, activeDevices: 1, inactiveDevices: 0, disabledDevices: 0 }, byConnectionType: {}, byIntegration: {}, lowBatteryDevices: [], deviceRows: [] },
    apps: { summary: { totalApps: 1, builtInApps: 1, userApps: 0 }, userApps: [], builtInInstances: {} },
    network: { uptimeSeconds: 1000, network: net, zwave: { enabled: false }, zigbee: { enabled: false }, hubMesh: { enabled: false }, matter: { enabled: false } },
    health: { hub: { name: 'Hub', hardware: 'C-8 Pro', firmware: '2.5.3.1' }, resources: { freeOSMemory: 131000, cpuAvg5min: 0.44, freeJavaMemory: 141000, totalJavaMemory: 324000 },
      temperature: 43, databaseSize: 96, stateCompression: { enabled: true }, eventStateLimits: { maxEvents: 11, maxEventAgeDays: 31, maxStateAgeDays: 7 },
      alertSignals: { platformAlerts: [], hubMessages: [] }, firmwareUpdate: null },
    performance: { stats: { uptime: '1m', totalDevicesRuntime: '1s', devicePct: '1%', totalAppsRuntime: '1s', appPct: '1%', deviceStats: [], appStats: [] }, radioStats: { zwave: [], zigbee: [] } },
    settings: { forumObfuscate: false }
  });
  // Lines of the System & Health section that start the table up to the first non-table line.
  function tableBreaks(md) {
    const lines = md.split('\n');
    const i = lines.findIndex(l => l === '### System & Health');
    assert(i >= 0, 'System & Health heading missing');
    let j = i + 1; while (j < lines.length && lines[j].startsWith('|')) j++;
    // Rows after the table ended that still look like table rows.
    const stray = lines.slice(j).filter(l => /^\| (CPU Load|Free OS Memory|Temperature|Database|Max Events)/.test(l));
    return { tableEndsAt: j, stray };
  }
  t('B1-6 Ethernet + Wi-Fi: no table row is left outside the table', () => {
    const md = F.buildForumMarkdown(F.assembleForumData(payload({ hasEthernet: true, hasWiFi: true, lanAddr: '192.0.2.20', usingStaticIP: false })));
    assert(md.includes('Both Ethernet and WiFi are active'), 'warning missing');
    const r = tableBreaks(md);
    assert.deepStrictEqual(r.stray, [], 'rows outside the table: ' + r.stray.join(' / '));
  });
  t('B1-6 the warning comes after the last table row', () => {
    const md = F.buildForumMarkdown(F.assembleForumData(payload({ hasEthernet: true, hasWiFi: true, lanAddr: '192.0.2.20' })));
    assert(md.indexOf('**Warning:**') > md.indexOf('| Max State Age |'));
  });
  t('B1-6 Ethernet only: table unchanged, no warning', () => {
    const md = F.buildForumMarkdown(F.assembleForumData(payload({ hasEthernet: true, hasWiFi: false, lanAddr: '192.0.2.20' })));
    assert(!md.includes('Both Ethernet and WiFi'));
    assert.deepStrictEqual(tableBreaks(md).stray, []);
  });
}

// ---- B2-1 computeSnapshotDiff
{
  const CD = (src.match(/const CD=\{[^;]*\};/) || [])[0];
  const D = load('diff', CD + '\n' + extractFn('computeSnapshotDiff'), ['computeSnapshotDiff']);
  // Snapshot shape as createSnapshot stores it: network.hubMesh = {peers: [{name, ip}]}.
  const snap = (ms, peers) => ({ timestampMs: ms, devices: { allDevices: [] }, apps: {}, network: { hubMesh: { peers } } });
  t('B2-1 a new peer is reported as added', () => {
    const r = D.computeSnapshotDiff(snap(1, [{ name: 'Hub B', ip: '192.0.2.30' }]), snap(2, [{ name: 'Hub B', ip: '192.0.2.30' }, { name: 'Hub C', ip: '192.0.2.31' }]));
    assert.deepStrictEqual(r.networkChanges.hubMeshPeers, { added: ['Hub C'], removed: [] });
  });
  t('B2-1 a dropped peer is reported as removed', () => {
    const r = D.computeSnapshotDiff(snap(1, [{ name: 'Hub B', ip: '192.0.2.30' }]), snap(2, []));
    assert.deepStrictEqual(r.networkChanges.hubMeshPeers, { added: [], removed: ['Hub B'] });
  });
  t('B2-1 an unnamed peer is identified by its address', () => {
    const r = D.computeSnapshotDiff(snap(1, []), snap(2, [{ ip: '192.0.2.40' }]));
    assert.deepStrictEqual(r.networkChanges.hubMeshPeers.added, ['192.0.2.40']);
  });
  t('B2-1 unchanged peers report nothing', () => {
    const r = D.computeSnapshotDiff(snap(1, [{ name: 'Hub B', ip: 'x' }]), snap(2, [{ name: 'Hub B', ip: 'x' }]));
    assert.strictEqual((r.networkChanges || {}).hubMeshPeers, undefined);
  });
}

(async () => {
  for (const [n, fn] of queue) {
    try { await fn(); pass++; console.log('  ok   ' + n); }
    catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + String(e.message).split('\n')[0]); }
  }
  console.log(`${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})();
