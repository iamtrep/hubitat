#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 (pure-JS) unit test for the Z-Wave Topology card's data model: the route parser
// (zwParseRoute), the matrix + route join (zwTopoModel), the route warnings (zwTopoFlags) and
// the LWR RSSI text (zwRssiTxt, dB on Z/IP, dBm on Z-Wave JS). EXTRACTS the functions from
// hub_inspector_ui.html by name (brace-matched), so the test stays bound to the shipped code.
//
// Run: node apps/HubInspector/tests/test-zwave-topology.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_inspector_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found in HTML: ' + name);
  const braceOpen = src.indexOf('{', start);
  let depth = 0, i = braceOpen;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}

const FNS = ['zwParseRoute', 'zwTopoModel', 'zwTopoFlags', 'zwRssiTxt'];
const harness = FNS.map(extractFn).join('\n') + '\nmodule.exports = { ' + FNS.join(', ') + ' };';
const tmp = path.join(os.tmpdir(), 'hd_zwtopo_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const { zwParseRoute, zwTopoModel, zwTopoFlags, zwRssiTxt } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g === w) { pass++; console.log('  [PASS] ' + name); }
  else { fail++; console.log('  [FAIL] ' + name + '  got=' + g + ' want=' + w); }
}

console.log('zwParseRoute (hub route strings)');
t('direct',                  zwParseRoute('01 -> 0C 100kbps'), { path: [1, 12], rate: 100 });
t('three repeaters',         zwParseRoute('01 -> 0C -> 11 -> 10 -> 0D 100kbps'), { path: [1, 12, 17, 16, 13], rate: 100 });
t('9.6 kbps',                zwParseRoute('01 -> 10 -> 13 9.6kbps'), { path: [1, 16, 19], rate: 9.6 });
t('no rate',                 zwParseRoute('01 -> 0E'), { path: [1, 14], rate: null });
t('"No route" is null',      zwParseRoute('No route'), null);
t('empty is null',           zwParseRoute(''), null);

console.log('\nzwTopoModel (matrix joined with routes)');
// Hub, a repeater (0C), and a battery sensor (0D) behind it that only 0C lists.
const m = zwTopoModel({ ids: [1, 12, 13], rows: ['-10', '1-1', '01-'] }, [
  { nodeId: 12, name: 'Outlet', route: '01 -> 0C 100kbps', rssiStr: '21dB', per: 0 },
  { nodeId: 13, name: 'Sensor', route: '01 -> 0C -> 0D 40kbps', rssiStr: '-80db', per: 1, battery: true },
]);
t('hub row is named Hub',    m.N[0].name, 'Hub');
t('neighbours from the row', m.N.map(x => x.nb), [1, 2, 1]);
t('relay links: both links of the 2-hop route', Object.keys(m.hops).sort(), ['12|13', '1|12']);
t('relay link names its route', m.hops['1|12'], ['Sensor']);
t('direct route is not a relay link', zwTopoModel({ ids: [1, 12], rows: ['-1', '1-'] }, [{ nodeId: 12, route: '01 -> 0C 100kbps' }]).hops, {});
t('repeater carries sensor', m.carries[12], ['Sensor']);
t('one-sided pair counted',  m.oneSided, 0);
t('summary',                 [m.summary.direct, m.summary.routed, m.summary.deepest, m.summary.slow],
                             [1, 2, { name: 'Sensor', repeaters: 1 }, 0]);
const asym = zwTopoModel({ ids: [1, 12, 13], rows: ['-11', '1-0', '11-'] }, []);
t('one side only',           asym.oneSided, 1);
t('unnamed node',            asym.N[1].name, 'Node 0C');
// A direct route between nodes that neither neighbour list has (seen on a Z/IP hub).
const stale = zwTopoModel({ ids: [1, 17, 21], rows: ['-01', '0-1', '11-'] }, [
  { nodeId: 17, name: 'Lock', route: '01 -> 11 40kbps' },
  { nodeId: 21, name: 'Plug', route: '01 -> 15 100kbps' },
]);
t('direct link, unlisted',   [stale.unlisted, !!stale.links['1|17'], !!stale.hops['1|17']], [1, true, false]);
t('listed hop not counted',  zwTopoModel({ ids: [1, 21], rows: ['-1', '1-'] }, [{ nodeId: 21, route: '01 -> 15 100kbps' }]).unlisted, 0);

console.log('\nzwTopoFlags (route warnings)');
const deep = { path: [1, 2, 3, 4], rate: 9.6, per: 2, nb: 1 };
t('big mesh: all four',      zwTopoFlags(deep, 20), ['2 repeaters', '9.6 kbps fallback', 'packet errors', 'few neighbours']);
t('small mesh: no nb flag',  zwTopoFlags(deep, 4), ['2 repeaters', '9.6 kbps fallback', 'packet errors']);
t('clean direct route',      zwTopoFlags({ path: [1, 2], rate: 40, per: 0, nb: 5 }, 20), []);
t('no route, no flags',      zwTopoFlags({ path: null }, 20), []);

console.log('\nzwRssiTxt (LWR RSSI)');
t('Z/IP dB',                 zwRssiTxt('31dB'), '31 dB');
t('Z-Wave JS dBm',           zwRssiTxt('-94db'), '-94 dBm');
t('empty',                   zwRssiTxt(''), '–');

console.log('\n=== ' + pass + '/' + (pass + fail) + ' passed ===');
process.exit(fail === 0 ? 0 : 1);
