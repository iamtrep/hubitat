#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for the SPA's Zigbee live-tail frame decoder (Radio Capture tab).
//
// Focus: ZDO routing. Endpoint 0 is the ZDO endpoint, and request-range ZDO cluster IDs
// (0x0000-0x003F) collide with ZCL application clusters (e.g. 0x0006 = ZDO Match_Desc_req AND
// ZCL On/Off). The decoder must route endpoint-0 frames to the ZDO parser regardless of cluster
// ID, distinguish ZDO requests (no status byte) from responses (status byte), and never surface
// the colliding ZCL cluster name / attribute decode for a ZDO frame.
//
// EXTRACTS the shipped ZCL-decode block from hub_diagnostics_ui.html (ZB_CLUSTERS .. ZCL DECODE
// (END)) so the test stays bound to the shipped code rather than a copy.
//
// Run: node apps/HubDiagnostics/tests/test-zcl-decode.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_diagnostics_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

// Slice the self-contained decode block plus the cluster-label helpers just above it. Starts at the
// ZB_CLUSTERS table (first dependency) and ends at the block's END marker.
const START = 'const ZB_CLUSTERS=';
const END = '// ===== ZCL DECODE (END) =====';
const s = src.indexOf(START), e = src.indexOf(END);
assert(s >= 0 && e > s, 'could not locate ZCL decode block in HTML');
const block = src.slice(s, e) +
  '\nmodule.exports = { rcZbParseZcl, rcZbParseZdo, resolveCmd, zdoName, rcZbEp, rcZbClusterCell, rcZbDecodedSummary };';
const tmp = path.join(os.tmpdir(), 'hd_zcl_' + process.pid + '.js');
fs.writeFileSync(tmp, block);
const D = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function check(name, cond) { if (cond) { pass++; } else { fail++; console.error('  FAIL: ' + name); } }

// Representative frame bytes: a device On/Off Report Attributes on endpoint 01.
const onOffReport = ['18', '5A', '0A', '00', '00', '10', '01'];   // global 0x0A, attr 0000, bool, =01
// Device_annce (ZDO request 0x0013) as seen on endpoint 0: [TSN][NWKaddr(2)][IEEE(8)][capability].
const deviceAnnce = ['A1', '5F', '58', '11', '22', '33', '44', '55', '66', '77', '88', '80'];
// Mgmt_Lqi_rsp (ZDO response 0x8031): [TSN][Status=00][...]
const mgmtLqiRsp = ['42', '00', '00', '00', '00'];

// 1. On/Off report on EP 01 is ZCL, not ZDO, and decodes OnOff=true.
{
  const zcl = D.rcZbParseZcl(onOffReport, '0006', '01');
  check('EP01 0006 is ZCL (not ZDO)', zcl && !zcl.isZdo);
  const sum = D.rcZbDecodedSummary(zcl);
  check('EP01 0006 decodes OnOff=true', /OnOff=true/.test(sum.short));
  check('EP01 0006 cluster cell = On/Off', D.rcZbClusterCell('0006', zcl) === 'On/Off (0x0006)');
}

// 2. Same cluster 0x0006 on EP 00 is ZDO Match_Desc_req — must NOT decode as On/Off.
{
  const zcl = D.rcZbParseZcl(['11', '5F', '58', '04', '01', '06', '00'], '0006', '00');
  check('EP00 0006 routes to ZDO', zcl && zcl.isZdo === true);
  check('EP00 0006 is a request (not response)', zcl.isZdoResponse === false);
  check('EP00 0006 has no bogus status', zcl.status == null);
  check('EP00 0006 command = Match_Desc_req', D.resolveCmd(zcl) === 'Match_Desc_req');
  check('EP00 0006 cluster cell = ZDO (not On/Off)', D.rcZbClusterCell('0006', zcl) === 'ZDO (0x0006)');
  const sum = D.rcZbDecodedSummary(zcl);
  check('EP00 0006 does not decode OnOff attrs', !/OnOff/.test(sum.short));
}

// 3. Device_annce (0x0013) on EP 00 is named, request-shaped, statusless.
{
  const zcl = D.rcZbParseZcl(deviceAnnce, '0013', '00');
  check('EP00 0013 is ZDO', zcl && zcl.isZdo === true);
  check('EP00 0013 command = Device_annce', D.resolveCmd(zcl) === 'Device_annce');
  check('EP00 0013 has no status byte read', zcl.status == null);
}

// 4. ZDO response (0x8031) is routed by cluster-ID fallback even when endpoint is absent, and
//    its status byte IS read.
{
  const zcl = D.rcZbParseZcl(mgmtLqiRsp, '8031', undefined);
  check('8031 routes to ZDO without EP', zcl && zcl.isZdo === true);
  check('8031 is a response', zcl.isZdoResponse === true);
  check('8031 status = SUCCESS byte', zcl.status === '00');
  check('8031 command = Mgmt_Lqi_rsp', D.resolveCmd(zcl) === 'Mgmt_Lqi_rsp');
}

// 5. rcZbEp normalization.
{
  check('rcZbEp("01") = 01', D.rcZbEp('01') === '01');
  check('rcZbEp(2) = 02', D.rcZbEp(2) === '02');
  check('rcZbEp("0x19") = 19', D.rcZbEp('0x19') === '19');
  check('rcZbEp("") = blank', D.rcZbEp('') === '');
  check('rcZbEp(null) = blank', D.rcZbEp(null) === '');
}

console.log(`test-zcl-decode: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
