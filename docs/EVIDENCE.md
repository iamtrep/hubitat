<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Evidence marks

Every fact in the platform reference set (`docs/hubitat-*.md`) says how it was established. A mark is an italic parenthetical at the end of the claim it covers:

```
`GET /hub/zigbee/healthStatus` returns the text `true` or `false` *(probed 2.5.0.143)*.
```

`scripts/check-doc-evidence.py` checks the marks and runs before each commit. Add a mark with every new fact, and change it whenever the fact's evidence changes.

## Where a fact goes

| File | Holds |
|---|---|
| [`hubitat-internal-apis.md`](hubitat-internal-apis.md) | The hub as a client off the hub sees it: admin HTTP endpoints, websockets, port 8081, payloads, authentication, status codes |
| [`hubitat-platform-notes.md`](hubitat-platform-notes.md) | What Groovy code on the hub can do and see: sandbox, `state`, events, scheduling, outbound HTTP, app pages, platform objects |
| [`hubitat-zigbee-helper.md`](hubitat-zigbee-helper.md) | The `zigbee` object injected into drivers, and ZDO |
| [`hubitat-device-reference.md`](hubitat-device-reference.md) | Per-device and per-vendor protocol facts: cluster maps, encodings, quirks |

A fact that spans two files goes where its subject is and links from the other: an endpoint an app calls over loopback is described in internal APIs, and what the app sees in platform notes. State a fact once.

## The ladder

Ordered from strongest to weakest. When two sources disagree, the higher rung wins if it describes the same or a later firmware; a higher rung on older firmware does not settle a newer one, so record both and say they conflict.

| Kind | Meaning | Required detail |
|---|---|---|
| `regression` | Re-checked by a test in this repo after each firmware update | firmware, test path |
| `probed` | We ran it on a hub: a request, a throwaway app or driver, a capture | firmware, or `firmware not recorded` for an old test whose build is lost; hub model when it matters |
| `source` | Read from code the hub serves (admin UI bundles, app-page templates) without running it | firmware, file |
| `staff` | Hubitat staff, usually on the community forum | handle, link |
| `docs` | Hubitat's documentation: [docs2](https://docs2.hubitat.com/en/developer/overview) or the on-hub API reference | link, or `on-hub <page-id>` |
| `spec` | A protocol standard or a manufacturer's documentation (ZCL, Z-Wave, a datasheet) | link or document and section |
| `reported` | Another user's observation backed by data: an export, a log, a HAR, a screenshot | where it came from; firmware if known |
| `community` | A community statement or code we have not tested: forum posts, community drivers, Zigbee2MQTT or ZHA converters | link |
| `inferred` | Our reasoning from other facts, not observed | what it rests on |

Two kinds sit outside the ladder:

| Kind | Meaning | Required detail |
|---|---|---|
| `absent` | Looked for and not found. A negative result, as strong as the search behind it | firmware, what was searched |
| `false` | Tested on these builds and the statement did **not** hold: the bound of a behavior that changed | firmware |
| `unmarked` | Written before marks existed; how it was established is unknown | none. Never use it for a new fact |

`staff` ranks above `docs` because the documentation usually lags what staff say about a current build. `source` ranks above `staff` for what the hub's own code does (which endpoint a page calls); for why it does it, staff is the better source.

## Syntax

- Kind first, then the detail: `*(probed 2.5.2.135, C-8 Pro, throwaway app)*`, `*(staff: [gopher.ny](https://community.hubitat.com/t/84194/15))*`, `*(docs: on-hub app-subscriptions)*`, `*(source: vue-hub2-zwave-info.min.js, 2.5.2.134)*`.
- Several sources for one claim go in one mark, separated by `;`: `*(probed 2.5.2.129; docs: [Zigbee Details](https://docs2.hubitat.com/en/user-interface/settings/zigbee-details))*`.
- A mark covers the text before it, back to the previous mark or the start of the bullet or paragraph. When one bullet holds claims with different evidence, mark each claim, or split the bullet.
- A nested bullet without a mark takes its parent's.
- A paragraph that ends with `:` introduces what follows and needs no mark.
- A table needs a mark in the paragraph right before or after it, unless each row carries one.
- `<!-- evidence: probed 2.5.2.135, C-8 Pro, throwaway driver -->` on the line after a heading marks the whole section; after the file's title, the whole file. Use it for one probe's output or a measurement table, not to avoid marking separate facts.
- What a test showed, and what it did not test, belongs in the sentence; the mark says how the sentence is known.

## Firmware builds

A fact's firmware builds say where it is known to hold, so a mark keeps every build it was tested on and a re-test never erases one.

- Write the full build (`2.5.2.135`). A fact tested on several builds carries the oldest and newest as a range: `probed 2.5.2.129–2.5.2.135`. A range means "held on both ends"; the builds in between are assumed, not tested.
- A re-test widens the range: a fact marked `probed 2.5.2.129` and confirmed on 2.5.2.135 becomes `probed 2.5.2.129–2.5.2.135`. Never replace the old build. When the kind differs (a one-off probe, then a regression test), keep both parts: `regression 2.5.2.135, apps/tests/test-architecture-claims.sh; probed 2.5.2.129`.
- The oldest build in a mark is the oldest one tested, not when the behavior began. When a behavior changed, add the `false` bound on the other side, so the change is bracketed: `false 2.5.0.153; probed 2.5.0.157–2.5.2.135` says it did not hold on 2.5.0.153 and held from 2.5.0.157. The same bound after the range says when it stopped: `probed 2.5.2.129–2.5.2.134; false 2.5.2.135`.
- When a fact stops holding, keep it, say in the sentence what the newer firmware does instead, and give the new behavior its own mark.

