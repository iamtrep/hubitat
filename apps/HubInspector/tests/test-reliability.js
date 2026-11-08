#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for relSummary / relLabel / relLinks (hub_inspector_ui.html), the Reliability Center
// card's derivation (firmware 2.5.3+). Extracted by name from the shipped SPA and run directly.
// Fixtures use invented names and ids.
//
// Run: node apps/HubInspector/tests/test-reliability.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');

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
const cst = n => { const m = src.match(new RegExp('const ' + n + '=\\{[^;]*\\};')); assert(m, n + ' const not found'); return m[0]; };
const REL_T = ['REL_T', 'REL_GO', 'REL_TG'].map(cst).join('\n');
const tmp = path.join(os.tmpdir(), 'hd_rel_' + process.pid + '.js');
fs.writeFileSync(tmp, 'let relQ=[];const Obf={enabled:false,nm:s=>Obf.enabled?"alias-"+String(s).length:s,scrub:s=>s};\n' +
  'const h=s=>String(s??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");const hp=(p,l)=>"[HP "+p+"]";const mc=(l,v)=>"["+l+"="+v+"]";const fdt=x=>"T"+x;\n' + REL_T + '\n' + ['relLabel', 'relLinks', 'relName', 'relTitle', 'relSub', 'relSummary', 'relCard', 'fago'].map(extractFn).join('\n') + '\nmodule.exports = { relSummary, relLabel, relLinks, relName, relTitle, relSub, relCard, Obf, REL_GO, REL_TG };');
const { relSummary, relLabel, relLinks, relName, relTitle, relSub, relCard, Obf, REL_GO, REL_TG } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

// Shape recorded from a 2.5.3.104 hub, with invented names and ids.
const full = {
  generatedAt: '2025-10-09T08:53:20+0000', generatedAtMs: 1760000000000, monitoredDeviceCount: 6,
  findings: [
    { findingType: 'silentDevice', deviceId: 101, deviceName: 'Hall Sensor', protocol: 'Zigbee', severity: 'critical', hoursSilent: 4426.4 },
    { findingType: 'silentDevice', deviceId: 102, deviceName: 'Shed Plug', protocol: 'Zigbee', severity: 'critical', hoursSilent: 2940 }
  ],
  generalFindings: [
    { findingType: 'lowBattery', deviceId: 103, title: 'Gate Camera battery low', subtitle: 'Cam Driver · Cloud', severity: 'critical' },
    { findingType: 'offline', deviceId: 104, title: 'Virtual Probe offline', subtitle: 'Virtual · LAN or local', severity: 'warning' },
    { findingType: 'zigbeeWeakLink', deviceId: 105, title: 'Remote weak link', severity: 'warning' }
  ],
  appFindings: Array.from({ length: 12 }, (_, i) => ({ findingType: 'missingSubscriptions', appId: 200 + i, appName: 'Rule ' + i, title: 'Rule ' + i + ' has no trigger', severity: 'warning' }))
};

t('null and absent give null', () => { assert.strictEqual(relSummary(null), null); assert.strictEqual(relSummary(undefined), null); });
t('unavailable gives {available:false}', () => { assert.deepStrictEqual(relSummary({ unavailable: true }), { available: false }); });
t('empty lists give zero counts and no top entries', () => {
  const s = relSummary({ findings: [], generalFindings: [], appFindings: [], monitoredDeviceCount: 6 });
  assert.strictEqual(s.available, true); assert.strictEqual(s.total, 0); assert.strictEqual(s.critical, 0);
  assert.deepStrictEqual(s.top, []); assert.deepStrictEqual(s.byType, {}); assert.strictEqual(s.monitored, 6);
});
t('payload without any list is an empty summary', () => { const s = relSummary({}); assert.strictEqual(s.total, 0); assert.strictEqual(s.available, true); });
t('full payload: counts, by-type, scan time, monitored', () => {
  const s = relSummary(full);
  assert.strictEqual(s.critical, 3); assert.strictEqual(s.warning, 14); assert.strictEqual(s.total, 17);
  assert.deepStrictEqual(s.byType, { silentDevice: 2, lowBattery: 1, offline: 1, zigbeeWeakLink: 1, missingSubscriptions: 12 });
  assert.strictEqual(s.generatedAt, 1760000000000); assert.strictEqual(s.monitored, 6);
});
t('top list: critical first, capped at 10, source order kept within a severity', () => {
  const s = relSummary(full);
  assert.strictEqual(s.top.length, 10);
  assert.deepStrictEqual(s.top.slice(0, 3).map(f => f.severity), ['critical', 'critical', 'critical']);
  assert.deepStrictEqual(s.top.slice(0, 3).map(f => f.title), ['Hall Sensor', 'Shed Plug', 'Gate Camera battery low']);
  assert.ok(s.top.slice(3).every(f => f.severity === 'warning'));
  assert.strictEqual(s.top[3].title, 'Virtual Probe offline');
});
t('entry fields: title falls back to deviceName, sub to hours silent then protocol, ids kept', () => {
  const s = relSummary(full);
  assert.strictEqual(s.top[0].sub, 'Zigbee'); assert.strictEqual(s.top[0].hrs, 4426.4); assert.strictEqual(s.top[0].deviceId, 101);
  assert.strictEqual(s.top[2].sub, 'Cam Driver · Cloud');
  assert.strictEqual(s.top[9].appId, 204);
});
t('no appFindings and no severity: counts as warning', () => {
  const s = relSummary({ findings: [{ findingType: 'silentDevice', deviceId: 1, deviceName: 'A' }], generalFindings: [{ findingType: 'offline', title: 'B' }] });
  assert.strictEqual(s.warning, 2); assert.strictEqual(s.critical, 0); assert.strictEqual(s.total, 2);
});
t('non-array list is ignored', () => { assert.strictEqual(relSummary({ findings: 'x', appFindings: null }).total, 0); });
t('unknown type passes through with its raw name', () => {
  const s = relSummary({ generalFindings: [{ findingType: 'futureCheck', title: 'Busy', severity: 'critical' }] });
  assert.deepStrictEqual(s.byType, { futureCheck: 1 }); assert.strictEqual(relLabel('futureCheck'), 'futureCheck');
  assert.strictEqual(relSummary({ generalFindings: [{ title: 'No type' }] }).byType.other, 1);
});
t('known types have plain labels', () => {
  assert.strictEqual(relLabel('silentDevice'), 'Silent device'); assert.strictEqual(relLabel('missingSubscriptions'), 'Rule with no trigger');
});
t('relLinks: device, app, none', () => {
  assert.deepStrictEqual(relLinks({ deviceId: 7 }), [{ label: 'Device page', href: '/device/edit/7' }]);
  assert.deepStrictEqual(relLinks({ appId: 'APP-9' }), [{ label: 'App page', href: '/installedapp/configure/9' }, { label: 'App status', href: '/installedapp/status/9' }]);
  assert.deepStrictEqual(relLinks({}), []);
});

const goOf = f => relLinks(f).filter(x => x.go).map(x => [x.label, x.go.tab, x.go.id, x.go.q, x.go.sub]);
t('targets per type', () => {
  assert.deepStrictEqual(goOf({ type: 'lowBattery', deviceId: 1 }), [['Devices', 'devices', 'devTbl', 1, '']]);
  assert.deepStrictEqual(goOf({ type: 'zwaveFailed' }), [['Z-Wave mesh', 'network', 'zwMeshTbl', 1, '']]);
  assert.deepStrictEqual(goOf({ type: 'zigbeeWeakLink' }).map(x => x[0] + '/' + x[1] + '/' + x[4]), ['Radio capture/radio-capture/zigbee', 'Zigbee network/network/']);
  assert.deepStrictEqual(goOf({ type: 'hubLoad' }).map(x => x[1]), ['performance', 'health']);
  assert.deepStrictEqual(goOf({ type: 'hubCloud' }).map(x => x[2]), ['cloudCallsTbl']);
  assert.deepStrictEqual(goOf({ type: 'missingSubscriptions', appId: 3 }), [['App runtime', 'performance', 'appStatsTbl', 1, '']]);
  assert.deepStrictEqual(goOf({ type: 'missingIp' }).map(x => x[2]), ['mdnsTbl']);
  assert.deepStrictEqual(goOf({ type: 'matterSubscription' }).map(x => x[1] + '/' + x[4]), ['network/', 'radio-capture/matter']);
});
t('silent device: radio capture follows the protocol', () => {
  assert.strictEqual(goOf({ type: 'silentDevice', proto: 'Zigbee' })[1][4], 'zigbee');
  assert.strictEqual(goOf({ type: 'silentDevice', proto: 'Cam \u00b7 Z-Wave' })[1][4], 'zwave');
  assert.strictEqual(goOf({ type: 'silentDevice', proto: 'Zigbee' })[0][2], 'devTbl');
});
t('unknown type and hubScheduler: hub-page links only', () => {
  assert.deepStrictEqual(goOf({ type: 'futureCheck', deviceId: 4 }), []);
  assert.deepStrictEqual(goOf({ type: 'hubScheduler' }), []);
  assert.strictEqual(relLinks({ type: 'futureCheck', deviceId: 4 }).length, 1);
});
t('every target key in the type table exists', () => {
  Object.entries(REL_TG).forEach(([ty, ks]) => ks.split(',').forEach(k => assert.ok(k === 'rc' || REL_GO[k], ty + ' -> ' + k)));
  assert.ok(Object.keys(REL_TG).every(ty => REL_T.includes(ty + ':')), 'type missing a label');
});
t('relName: by device id from the Devices rows, else the finding name', () => {
  const rows = [{ id: 103, label: 'Gate Camera' }, { id: 5, name: 'Only Name' }];
  assert.strictEqual(relName({ deviceId: 103, name: 'x' }, rows), 'Gate Camera');
  assert.strictEqual(relName({ deviceId: 5 }, rows), 'Only Name');
  assert.strictEqual(relName({ deviceId: 999, name: 'Fallback' }, rows), 'Fallback');
  assert.strictEqual(relName({ appId: 1, name: 'Rule 1' }, undefined), 'Rule 1');
  assert.strictEqual(relName({}, rows), '');
});
t('summary entries carry name and protocol for the lookup', () => {
  const s = relSummary(full);
  assert.strictEqual(s.top[0].name, 'Hall Sensor'); assert.strictEqual(s.top[0].proto, 'Zigbee');
});

const card = (rel, rows) => relCard(relSummary(rel), rows);
t('silent subtitle: last heard, via fago', () => {
  assert.strictEqual(relSub({ hrs: 4426.4, sub: 'Zigbee' }), 'last heard ' + (Math.round(4426.4 / 24) + 'd ago'));
  assert.strictEqual(relSub({ sub: 'Cam \u00b7 Cloud' }), 'Cam \u00b7 Cloud');
});
t('monitored count: singular and plural, tile and no-findings line', () => {
  assert.ok(/1 device monitored/.test(card({ monitoredDeviceCount: 1 })) && !/1 devices/.test(card({ monitoredDeviceCount: 1 })));
  assert.ok(/6 devices monitored/.test(card({ monitoredDeviceCount: 6 })));
  const one = { monitoredDeviceCount: 1, findings: [{ findingType: 'silentDevice', deviceName: 'A', severity: 'critical', hoursSilent: 30 }] };
  assert.ok(card(one).includes('[Device monitored=1]'));
  assert.ok(card(Object.assign({}, full, { monitoredDeviceCount: 6 })).includes('[Devices monitored=6]'));
});
t('breakdown: "Label: n \u00b7 ..." and omitted for one finding', () => {
  assert.ok(card(full).includes('Silent device: 2 \u00b7 Low battery: 1 \u00b7 Offline: 1'));
  const one = { findings: [{ findingType: 'silentDevice', deviceName: 'A', severity: 'critical' }] };
  const c = card(one);
  assert.ok(!c.includes('Silent device: 1') && c.includes('not counted in Alerts'));
  assert.ok(card({ findings: [one.findings[0], one.findings[0]] }).includes('Silent device: 2'));
});
t('app finding: app name as title, hub title as tooltip only when not obfuscated', () => {
  const rel = { appFindings: [{ findingType: 'missingSubscriptions', appId: 9, appName: 'Rule 9', title: 'Rule 9 has no <trigger>' }] };
  const c = card(rel);
  assert.ok(c.includes('<span title="Rule 9 has no &lt;trigger&gt;">Rule 9</span>'));
  Obf.enabled = true; const co = card(rel); Obf.enabled = false;
  assert.ok(!co.includes('title="Rule') && co.includes('alias-6'));
});
t('hub-level finding: Hub alerts link; unknown non-hub type: none', () => {
  assert.ok(relLinks({ type: 'hubScheduler' }).some(x => x.href === '/alerts' && x.label === 'Hub alerts'));
  assert.ok(relLinks({ type: 'hubLoad' }).some(x => x.href === '/alerts') && relLinks({ type: 'hubLoad' }).some(x => x.go));
  assert.ok(!relLinks({ type: 'futureCheck' }).some(x => x.href === '/alerts'));
});
t('obfuscated device finding: alias of the device name plus key metric; hub title never shown', () => {
  const rows = [{ id: 103, label: 'Gate Camera' }];
  const f = { title: 'Gate Camera battery is 3%', name: '', deviceId: 103, metric: 'Battery: 3%' };
  assert.strictEqual(relTitle(f, rows).text, 'Gate Camera battery is 3%');
  Obf.enabled = true;
  assert.strictEqual(relTitle(f, rows).text, 'alias-11'); assert.strictEqual(relTitle(f, rows).meta, 'Battery: 3%');
  const oc = card({ generalFindings: [{ findingType: 'lowBattery', deviceId: 103, title: f.title, subtitle: 'Cam · Cloud', severity: 'critical', metrics: [{ label: 'Battery', value: '3%' }] }] }, rows);
  assert.ok(oc.includes('(Battery: 3% \u00b7 Cam \u00b7 Cloud)') && !oc.includes('Gate Camera'), 'one parenthesis: metric then subtitle');
  Obf.enabled = false;
  const s = relSummary({ generalFindings: [{ findingType: 'lowBattery', deviceId: 103, title: 'x', metrics: [{ label: 'Warning threshold', value: '20%' }, { label: 'Battery', value: '3%' }] }] });
  assert.strictEqual(s.top[0].metric, 'Battery: 3%');
  assert.strictEqual(relSummary({ generalFindings: [{ findingType: 'lowBattery', title: 'x', metrics: [{ label: 'Warning threshold', value: '20%' }] }] }).top[0].metric, '');
});
t('row links are separated by a middle dot', () => {
  const c = card({ findings: [{ findingType: 'silentDevice', deviceId: 1, deviceName: 'Hall', severity: 'critical', protocol: 'Zigbee' }] });
  assert.ok(/Device page \u2197<\/a> \u00b7 <a [^>]*>Devices<\/a> \u00b7 <a [^>]*>Radio capture<\/a>/.test(c));
});
t('links carry aria-labels naming target and finding', () => {
  const c = card({ findings: [{ findingType: 'silentDevice', deviceId: 1, deviceName: 'Hall "Sensor"', severity: 'critical', protocol: 'Zigbee' }] });
  assert.ok(c.includes('aria-label="Devices: Hall &quot;Sensor&quot;"') && c.includes('aria-label="Device page: Hall &quot;Sensor&quot;"'));
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
