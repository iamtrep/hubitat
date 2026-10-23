// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 unit test for the winter-credit target in hilo-history.html. Extracts the page's
// functions by name and runs the cases of test-peak-target.groovy, so the page and Hilo Manager
// compute the same thing.
//
// Run: node integrations/Hilo/tests/test-peak-target.js

const fs = require('fs');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, '..', 'hilo-history.html'), 'utf8');

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

const names = ['ymd', 'addDays', 'isWeekend', 'easterSunday', 'noPeakDays', 'peakTarget', 'curtailed'];
const src = 'const WC_CAP = 40;\n' + names.map(extract).join('\n') + `\nreturn { ${names.join(', ')} };`;
const P = new Function(src)();

let pass = 0, fail = 0;
const check = (name, cond) => { if (cond) { pass++; console.log(`  [PASS] ${name}`); } else { fail++; console.log(`  [FAIL] ${name}`); } };
const near = (a, b) => a != null && Math.abs(a - b) < 0.001;
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// Same fixture as the Groovy test: Dec 15 to Jan 10, day i (0-4 cycling) uses anchorVals[i] in
// hours 1-3 and 12-13 and eventVals[i] in hours 6-9 and 16-19.
function fill(anchorVals, eventVals) {
    const out = {};
    let d = '2025-12-15', i = 0;
    while (d <= '2026-01-10') {
        const h = {};
        [1, 2, 3, 12, 13].forEach(x => h[x] = anchorVals[i % 5]);
        [6, 7, 8, 9, 16, 17, 18, 19].forEach(x => h[x] = eventVals[i % 5]);
        out[d] = h;
        d = P.addDays(d, 1); i++;
    }
    return out;
}

console.log('noPeakDays / easterSunday');
check('Easter 2026 is April 5', P.easterSunday(2026) === '2026-04-05');
const np = P.noPeakDays('2026-01-07');
check('holidays and Easter', ['2025-12-24', '2025-12-25', '2025-12-26', '2025-12-31', '2026-01-01', '2026-01-02', '2026-04-03', '2026-04-06'].every(d => np.has(d)) && np.size === 8);

console.log('reference days');
const hours = fill([1, 2, 3, 4, 100], [5, 6, 7, 8, 50]);
let r = P.peakTarget(hours, '2026-01-07', 6, new Set(['2026-01-05']), 3, null);
check('weekday: holidays and event days skipped', eq(r.refDays, ['2026-01-06', '2025-12-30', '2025-12-29', '2025-12-23', '2025-12-22']));
check('skipped days carry the reason', r.skipped.some(s => eq(s, ['2026-01-05', 'event'])) && r.skipped.some(s => eq(s, ['2026-01-02', 'no peak hours'])));
const we = P.peakTarget(hours, '2026-01-10', 6, new Set(), 3, null);
check('weekend: weekend days only', eq(we.refDays, ['2026-01-04', '2026-01-03', '2025-12-28', '2025-12-27', '2025-12-21']));

console.log('target, morning, cap would not cost');
r = P.peakTarget(hours, '2026-01-07', 6, new Set(), 3, null);
check('anchor reference 9, base 28', near(r.anchorRef, 9) && near(r.base, 28));
check('capped target 52', near(r.cappedTarget, 52));
check('plan: at least 27', r.anchorPlan === 'at least' && near(r.anchorTarget, 27) && near(r.expectedCut, 49));
const r2 = P.peakTarget(hours, '2026-01-07', 6, new Set(), 3, 45);
check('target 76, cut deep to 49', near(r2.target, 76) && near(r2.cut, 49) && r2.advice === 'cut');

console.log('target, morning, cap would cost');
const low = fill([0, 1, 1, 1, 9], [5, 6, 7, 8, 50]);
const r3 = P.peakTarget(low, '2026-01-07', 6, new Set(), 3, null);
check('plan: at most 14.25, curtail 40', r3.anchorPlan === 'at most' && near(r3.anchorTarget, 14.25) && near(r3.expectedCut, 40));
const r4 = P.peakTarget(low, '2026-01-07', 6, new Set(), 3, 30);
check('anchor over plan: keep 24 beats a capped 33', near(r4.target, 64) && r4.advice === 'keep' && near(r4.keepUse, 24) && near(r4.cut, 33));

console.log('target, evening');
const pm = P.peakTarget(hours, '2026-01-07', 16, new Set(), 3, 20);
check('anchor 12-13, factor 2, target 56', eq(pm.anchorHours, [12, 13]) && near(pm.k, 2) && near(pm.target, 56));

console.log('curtailed');
check('under 40: target - used', near(P.curtailed(43, 35, 3), 40));
check('over 40 and over the capped target: capped - used', near(P.curtailed(65.9, 38.3, 4.7), 33.6));
check('over 40 but the adjustment under its cap: no change', near(P.curtailed(56.3, 66.0, 2.5), 53.8));

console.log(`\n=== ${pass}/${pass + fail} passed ===`);
process.exit(fail ? 1 : 0);
