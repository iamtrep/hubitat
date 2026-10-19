# Code Manager — status

Where the app stands against the release plan in [DESIGN.md](DESIGN.md) §14. Update this file at the end of every release, not during one.

## Now: 0.1.1, read-only inventory (superseded by 0.2)

0.1 shipped the whole inventory release: the four bulk passes and per-item detail fetch, package classification over all four origins, the usage index and safe-to-remove list, HPM import in `observe` mode, the bounded sweep with hash and version freshness, broken-source strikes, the label badge and notifications, the SPA over `/api/*`, and the export. Nothing outside the app's own catalog is written. Everything in DESIGN §14's 0.1 line is present except the child-type source scan in `safeToRemove`, which is parked below.

0.1.1 is a correctness pass over what 0.1 reported, driven by reading the shipped UI against a real hub. A bundle installed by an HPM package is now owned by that package and its members are folded into it, instead of the bundle standing as a second package under its own name; bundle components bind by `sourceUrl` and File Manager files by name, both re-checked every pass. The `required` flag HPM already stored is now honoured, so a component a package declares but never installed reads `not-installed` rather than `missing` — HPM manifests declare every component a repository ships and mark most optional, so the old reading called most of a healthy hub broken. `unmanaged` (no upstream to compare against) is now distinct from `unknown` (an upstream exists, not yet fetched), and no component can read `current` without a comparison having happened. The SPA separates real packages from one-file packages, links File Manager entries to `/local/<name>`, and carries a legend defining every status under each table.

## Verified

Mode 2 API test (`tests/test-code-manager-api.sh`) covers every endpoint against `/hub2/*` ground truth, and asserts the model's invariants: one component never belongs to two packages, an installed bundle is bound wherever it is declared, a file component is bound exactly when the file exists, absent components report by their `required` flag, and both status vocabularies stay closed. Mode 4 extraction tests cover the version heuristics (`test-version-compare.groovy`), source normalization and hashing (`test-source-hash.groovy`), and the status rules (`test-status.groovy`). Mode 3 extraction tests cover the SPA's pure helpers — classification, legends, links, removability, escaping, the limiter, and app/UI version lockstep.

## Parked

- **Registry size.** `state.pkgs` sits near 80 KB on a large hub against the 60 KB DESIGN §2.4 first estimated. It fits, but the registry moves to a File Manager document in 0.2 rather than growing further in `state`.
- **Child-type check.** `safeToRemove` does not check whether a parent's source could create a component as a child; 0.2 does this at remove time (DESIGN 3.3).
- **HPM panel error shape.** A transient failure to read HPM's status renders as a bare mode line rather than an explicit "could not read HPM" state.
- **Housekeeping.** A `cm_pkg_` document whose delete fails is never retried, and the sweep and job caches are never pruned.
- **Mock data.** The workbench mock still carries `usedBy` names, where the live payload carries ids only.

## Next: 0.2, reliable inventory under a hub budget

0.1's inventory pass reads each new or changed item's source inside the `/api/inventory` request, with no limit per pass and no back-off for an item that keeps failing. A first pass on a large hub reads every source in turn, and one such pass on a 212-item hub took about 11 minutes and tied up the hub's code endpoints. Its daily sweep also reports freshness that can be a day old as if it were current. 0.2 replaces both (DESIGN 3, 5, 14):

- Inventory and dependency graph from bulk reads only, rebuilt per request, nothing cached in `state`.
- Every shown fact states its basis: live, read at a `lastModified`, or checked at a time. A cached per-item fact is shown only while the item's `lastModified` is unchanged.
- Per-item facts (`importUrl`, version, hash) filled by a background reader under the hub budget, with progress shown; unread items say so and are never guessed as `local`.
- Drift checks only on request (one item, one package, or all). The daily sweep, the update badge and notifications are removed until scheduled checks are designed.
- The check that a parent could create a type runs when the user asks to remove it, not as a scan of every source.
- Measured hub cost in the README.

`lastModified` is confirmed to move on every successful `ajax/update` and `saveOrUpdateJson` save of an app, driver or library, and never on a rejected one. Bundle re-import moves every member's `lastModified`, and the editor's Save uses `saveOrUpdateJson`, so every write path is covered.

## Later: 0.3, apply for `url` and `local` origins

Preview, diff, snapshot, write, verify, rollback; install from URL. HPM packages stay read-only until 0.4. Prerequisite platform probes before any write code: (1) the real cap on an app's `httpPost`/`asynchttpPost` wait versus the requested `timeout`; (2) for a multi-hundred-KB source, whether `saveOrUpdateJson` and `ajax/update` answer at all, their compile and response times, and whether they differ; (3) whether a small save during an outstanding large compile stalls, times out or lands. The write semantics they feed are in DESIGN 6.2, 6.3 and 6.6 (three outcomes, resolve by read-back, never retry).
