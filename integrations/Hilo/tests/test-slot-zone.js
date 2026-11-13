// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 unit test: hilo-winter-credit.html turns an event's epoch second into a day and start
// hour in the utility's zone, whatever the viewer's zone is. Extracts the page's code by name.
//
// Run: node integrations/Hilo/tests/test-slot-zone.js   (re-runs itself under three TZ values)

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

if (!process.env.SLOT_TZ_CHILD) {
    let bad = 0;
    for (const tz of ['America/Toronto', 'Europe/Paris', 'Pacific/Auckland']) {
        const r = spawnSync(process.execPath, [__filename], { env: { ...process.env, TZ: tz, SLOT_TZ_CHILD: '1' }, encoding: 'utf8' });
        process.stdout.write(`TZ=${tz}\n${r.stdout}${r.stderr}`);
        if (r.status) bad++;
    }
    process.exit(bad ? 1 : 0);
}

const html = fs.readFileSync(path.join(__dirname, '..', 'hilo-winter-credit.html'), 'utf8');
function extract(name) {
    const start = html.indexOf(`function ${name}(`);
    if (start < 0) throw new Error(`function not found: ${name}`);
    let depth = 0;
    for (let i = html.indexOf('{', start); i < html.length; i++) {
        if (html[i] === '{') depth++;
        else if (html[i] === '}' && --depth === 0) return html.slice(start, i + 1);
    }
    throw new Error(`unbalanced braces: ${name}`);
}
const tzLine = html.split('\n').find(l => l.startsWith('const WC_TZ ='));
if (!tzLine) { console.log('  [FAIL] WC_TZ constant not found'); process.exit(1); }
const S = new Function(tzLine + '\n' + extract('slotOfEpoch') + '\nreturn { slotOfEpoch };')();

let fail = 0;
const check = (name, cond) => { if (!cond) fail++; console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}`); };
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const sec = iso => Date.parse(iso) / 1000;

check('06:00 EST event is day and hour 6', eq(S.slotOfEpoch(sec('2026-12-03T06:00:00-05:00')), { day: '2026-12-03', start: 6 }));
check('16:00 EST event is hour 16', eq(S.slotOfEpoch(sec('2026-12-03T16:00:00-05:00')), { day: '2026-12-03', start: 16 }));
check('late evening keeps the utility day', eq(S.slotOfEpoch(sec('2026-12-03T23:00:00-05:00')), { day: '2026-12-03', start: 23 }));
check('midnight is hour 0, not 24', eq(S.slotOfEpoch(sec('2026-12-04T00:00:00-05:00')), { day: '2026-12-04', start: 0 }));
check('a summer instant uses EDT', eq(S.slotOfEpoch(sec('2026-07-01T06:00:00-04:00')), { day: '2026-07-01', start: 6 }));

process.exit(fail ? 1 : 0);
