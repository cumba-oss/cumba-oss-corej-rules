# Two guards retired by the split — and what is no longer checked

Both read a tree that the coreJ monorepo held **beside** this one and that the
split moved into a different repository. Neither can be made to work here, and
neither is worth a silent skip.

## `RulesFixtureSyncTest`

Byte-compared the engine's curated fixture corpus
(`corej-core/src/test/resources/fixtures/rules/`) against this repo's
`rules-src/`, so the engine's tests could not silently pin stale rules.

⚠ **It is inherently two-repo.** One half is in `cumba-corej`, the other here,
and no build checks out both — so unlike the `MetadataProvider` delegation guard
(which split into a matching pair, one per repo) this one has no half that means
anything alone.

**What is no longer checked:** that `cumba-corej`'s rule fixtures still match
this corpus — and since 2026-09-28 there is nothing left to check. The engine's
copy itself was retired by `PLAN-engine-fixture-join-key-drift` (meta repo
`plans/`): the fixture tree and `FixtureCorpusIntegrityTest` were deleted from
both engine twins, the engine tests that only asserted a rule's verdict went with
them, and the engine's mechanism tests now carry hand-written inline rules that are
not copies of corpus rules. The generator that rebuilt the copy,
`scripts/build-core-fixtures.py` (stage 1 of `scripts/regen-rules-corpus.sh`), was
deleted from this repo in the same plan. A rule's verdict is now proven only here,
by its `.cdt` scenarios. ⚑ Until the retirement a drift showed up only as an engine
test pinning a rule this corpus no longer contained — it happened twice
(2026-09-23, 2026-09-25) with nothing red.

## `PlanStatusHeaderGuardTest`

Validated the status headers of the monorepo's `plans/` markdown. `plans/` is not
carried into this repo — it goes to the documentation archive — so the guard had
nothing to read and reported the directory as missing.

**What is no longer checked:** plan-document header hygiene, in the repo that no
longer holds the plan documents.
