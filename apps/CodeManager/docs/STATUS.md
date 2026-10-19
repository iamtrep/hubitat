# Code Manager — status

Where the app stands against the release plan in [DESIGN.md](DESIGN.md) §14. Update this file at the end of every release, not during one.

## Now: 0.2.0, reliable inventory under a hub budget

0.2 replaces 0.1's request-time inventory pass and daily sweep (DESIGN 3, 5, 14). The inventory and the usage graph come from the hub's bulk lists only, shared and single-flight; the slow library list is read in the background and shown with its age. Per-item facts (import URL, version, hash, size, child calls) are filled by a background reader under a fixed budget (one read in flight, spaced, backed off, large sources skipped) and every fact on a row states its basis. Drift is checked only on request, for one item, one package or everything, and a result is valid only for the code it was computed against. The check that some code could create a type runs when the user asks about removing it. The SPA shows each row's basis, sorts statuses by severity, counts installed items only, and gains a dark theme, keyboard access and phone layout fixes.

0.1.1 (superseded) was a correctness pass over what 0.1 reported: HPM-installed bundles folded into their package, the `required` flag honoured, `unmanaged` split from unchecked, and no `current` without a comparison.

## Verified

Mode 2 API test (`tests/test-code-manager-api.sh`) covers every endpoint against `/hub2/*` ground truth, and asserts the model's invariants: one component never belongs to two packages, an installed bundle is bound wherever it is declared, a file component is bound exactly when the file exists, absent components report by their `required` flag, and both status vocabularies stay closed. Mode 4 extraction tests cover the version heuristics (`test-version-compare.groovy`), source normalization and hashing (`test-source-hash.groovy`), the status rules over every input combination (`test-status.groovy`), the fact basis, queue, back-off and reader steps (`test-facts.groovy`), drift results (`test-drift.groovy`) and the remove check (`test-remove-check.groovy`). Mode 3 extraction tests cover the SPA's pure helpers — classification, basis labels, severity order, dates, version order, legends, links, remove state, escaping, the limiter, and app/UI version lockstep; Mode 2 also checks that the UI file on the hub is byte-identical to the checkout.

`lastModified` is confirmed to move on every successful `ajax/update` and `saveOrUpdateJson` save of an app, driver or library, and never on a rejected one. Bundle re-import moves every member's `lastModified`, and the editor's Save uses `saveOrUpdateJson`, so every write path is covered.

## Parked

- **Registry size.** `state.pkgs` sits near 80 KB on a large hub against the 60 KB DESIGN §2.4 first estimated. It fits; 0.2 batches the fact reader's writes to it (every 10 reads) instead of moving it to a File Manager document.
- **Package status of absent and bundle packages.** An HPM package with nothing installed is still listed on Packages, and a bundle's status rolls up its members' drift checks, so it reads `Not checked` until they are checked.
- **Housekeeping.** A `cm_pkg_` document whose delete fails is never retried, and the sweep and job caches are never pruned.
- **Mock data.** The workbench mock still carries `usedBy` names, where the live payload carries ids only.

## Next: 0.3, apply for `url` and `local` origins

Preview, diff, snapshot, write, verify, rollback; install from URL. HPM packages stay read-only until 0.4. Prerequisite platform probes before any write code: (1) the real cap on an app's `httpPost`/`asynchttpPost` wait versus the requested `timeout`; (2) for a multi-hundred-KB source, whether `saveOrUpdateJson` and `ajax/update` answer at all, their compile and response times, and whether they differ; (3) whether a small save during an outstanding large compile stalls, times out or lands. The write semantics they feed are in DESIGN 6.2, 6.3 and 6.6 (three outcomes, resolve by read-back, never retry).
