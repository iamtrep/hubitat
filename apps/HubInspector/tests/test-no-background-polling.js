#!/usr/bin/env node
// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 (pure-JS) unit test: Hub Inspector is a troubleshooting tool, so the page never polls the
// hub on its own. Only timers the user starts may repeat: the audit progress poll and the Matter log
// recorder. The resource tiles refresh on the Refresh button and show when their data was read.
//
// Checks: every setInterval in hub_inspector_ui.html is on the allowlist; the startup block makes
// one dashboard call and no repeating calls over an hour, visible or hidden; refreshLive is reached
// only from the Refresh button.
//
// Run: node apps/HubInspector/tests/test-no-background-polling.js
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vm = require('vm');

const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');

// 1. Every repeating timer is user-started.
const ALLOWED = ['setInterval(tick, 2000)', 'setInterval(rcMatterPoll, m.intervalMs)'];
const found = src.match(/setInterval\([^;]{0,60}?\)(?=[;\s])/g) || [];
for (const f of found) assert(ALLOWED.includes(f), `unexpected repeating timer: ${f}`);

// 2. The startup block polls nothing.
const end = src.lastIndexOf('  go(curTab);\n}');
const start = src.lastIndexOf('} else {\n', end);
assert(start >= 0 && end > start, 'startup block not found');
const block = src.slice(start + '} else {\n'.length, end + '  go(curTab);\n'.length);
let now = 0, nextId = 1, dashCalls = 0;
const timers = new Map(), listeners = {};
const document = { hidden: false, addEventListener: (t, f) => { (listeners[t] = listeners[t] || []).push(f); } };
const ctx = {
  document, curTab: 'dashboard',
  setInterval: (f, ms) => { const id = nextId++; timers.set(id, { f, ms, at: now + ms }); return id; },
  setTimeout: (f, ms) => { const id = nextId++; timers.set(id, { f, ms: Infinity, at: now + ms }); return id; },
  clearInterval: id => timers.delete(id),
  api: ep => { if (ep === 'dashboard') dashCalls++; return Promise.resolve({}); },
  refreshLive: () => { throw new Error('startup must not call refreshLive'); },
  go: () => {}, setHubInfoHeader: () => {},
};
vm.createContext(ctx);
vm.runInContext(block, ctx);
function advance(ms) {
  const stop = now + ms;
  for (;;) {
    let next = null;
    for (const [id, t] of timers) if (t.at <= stop && (!next || t.at < next[1].at)) next = [id, t];
    if (!next) break;
    now = next[1].at; if (next[1].ms === Infinity) timers.delete(next[0]); else next[1].at += next[1].ms; next[1].f();
  }
  now = stop;
}
assert.strictEqual(dashCalls, 1, 'startup loads the dashboard once for the header');
advance(60 * 60000);
document.hidden = true; (listeners.visibilitychange || []).forEach(f => f());
advance(60 * 60000);
document.hidden = false; (listeners.visibilitychange || []).forEach(f => f());
advance(60 * 60000);
assert.strictEqual(dashCalls, 1, `startup block made ${dashCalls - 1} more dashboard calls over 3 hours`);
assert.strictEqual(timers.size, 0, 'startup block left a timer running');

// 3. refreshLive runs only from the Refresh button.
const callers = (src.match(/(?<!function )refreshLive\(\)/g) || []).length;
assert.strictEqual(callers, 1, `refreshLive() is called from ${callers} places; only the Refresh button handler may call it`);
assert(/closest\('\[data-live-refresh\]'\)\)refreshLive\(\)/.test(src), 'the Refresh button handler must call refreshLive()');
assert((src.match(/data-live-refresh title=/g) || []).length === 2, 'both resource cards carry a Refresh button');

console.log('ok - no background polling; resource tiles refresh on request');
