#!/usr/bin/env bash
#
# Regenerates the shipped rule corpus from the per-rule YAML source (rules-src/).
#
#   0. RuleCorpusGenerator (test scope) — assembles each family-scoped package ONCE from
#      rules-src/checks + rules-src/docs (membership derived from each rule's Standards
#      block, Core.Id-keyed), then hands it straight to the native generator:
#
#        rules-src/{checks,docs}
#             |
#             +-- RuleExprGenerator.generate --> rules/ + packages.json
#                 (expression Checks and Form-B operations carried verbatim)
#
#      ⚑ SINGLE BRANCH SINCE WAVE 33 (plans/PLAN-cut-rules-legacy.md). This stage used to
#      fan out: the same canonical node was emitted a second time through
#      RuleStorageAssembler.applyLegacyDegradations into rules-legacy/, the Python fork's
#      production corpus. That branch, its FIVE transforms
#      (LegacyScopePatternDegrader, LegacyDatePartOperatorDegrader, LegacyGroupingDegrader,
#      LegacyScopeVariablesGuardEmitter, LegacyColumnVsLiteralGuardEmitter), the corpus and
#      the Python parity lane are all gone. Two of the five were documented as LOSSY —
#      the date-part conflation (EC-58/EC-59) and the silent keep_missings drop — so the
#      cut removed two known silent-loss paths as well as a maintenance burden.
#
#      ⚠ THIS COMMENT IS NO LONGER A THREE-WAY REGISTER. It used to be kept in step with
#      the <ol> in applyLegacyDegradations' javadoc and that method's body; all three are
#      deleted. There is nothing left to keep in step here — if you are looking for the
#      list of legacy-only transforms because something still references one, it does not
#      exist any more.
#
#      Fix #159: a rule declaring Executability: "Not Executable" is SKIPPED here and prints
#      a "[assemble] N rule(s) parked" line. The skip is deliberately in ONE place —
#      RuleStorageAssembler's loaded checks map — so the parked rule never reaches the
#      generator. RulePackageLoader has its own skip for the paths that bypass this script
#      (rule editor, CDISC-Library API, hand-authored packages); the two are NOT redundant —
#      see RuleStorageAssembler.dropParkedRules.
#   1. build-core-fixtures.py — refreshes the engine's curated fixture corpus.
#
#      ⚑ EVERY TRANSFORM BETWEEN rules-src AND rules/ EXCEPT ASSEMBLY IS RETIRED
#      (PLAN-retire-corpus-transforms, 2026-08-26). The former stages 1 and 2
#      (OperationInliner, OperationFormRewriter) are deleted; each transform's effect
#      moved into the authored source:
#        · Form A inlining — opt-in-only since D31, never what shipped; gone with the class.
#        · the variable_exists lowering (#13) — the operation ships DECLARED
#          (OperationType.VARIABLE_EXISTS is its reporting carriage; RulePackageLoader
#          performs the identical lowering at load).
#        · the split_by lowering (#14) — the three carriers author split_by(...) inline in
#          the Check; SplitByOperationCorpusGateTest rejects any new declaration.
#        · the §9.D concrete-domain cross_dataset_variable_metadata -> var_*(dataset=)
#          accessor rewrite (#15) — the operations ship declared; OperationExecutor's
#          dispatch resolves a concrete domain (measured equivalent, plan phase 0b).
#        · the J3/J4 absent-column guard baking (#12) — the guards' effect is AUTHORED:
#          Requirements.Variables.All where the SKIP conversion is ruled and compensated
#          (Q2 / Fix #330 — a presence rule reports the absence), a branch-scoped
#          var_exists guard in the Check where it is not (fire-on-absent branches with no
#          compensation, join-sourced columns, expansion-token templates — see
#          CORE-RULES-GUARD-WHITELIST.tsv); RulePackageLoader.installNativeExpr
#          still applies NonEmptyGuardInliner at load as the foreign-rule safety net.
#        · the Form A -> Form B operation rendering (#18) — rules-src authors Form B
#          directly (phase 8), so there is nothing left to render.
#
# All stages operate relative to the module root and are idempotent, so re-running is safe.
#
# ⚑⚑ STAGE 0 IS NO LONGER THE ONLY WAY THE CORPUS GETS BUILT, AND ITS GATE HAS CHANGED
# (plans/PLAN-rules-corpus-build-integration.md). Since 2026-09-02 the corej-cdisc-rules build
# runs RuleCorpusGenerator itself, at process-test-classes, writing target/rules/ — so every
# `mvn test` already has a corpus current with rules-src/, and the output is NOT committed.
#
# The old gate here was BYTE IDENTITY against the committed corpus: "a regeneration that changes
# no source must leave `git status -uall -- rules` empty". That gate is gone with the committed
# copy, and it is not missed — an artifact that cannot fall behind its source cannot go stale.
# NativeCorpusRoundTripTest still asserts the round-trip contract from the build.
#
# WHAT THIS SCRIPT IS STILL FOR: stage 1. build-core-fixtures.py refreshes the engine's COMMITTED
# fixture corpus in corej-cdisc-core, which the build does not and should not touch (it writes
# into another module's source tree). Run this after changing the converter, the operation
# printer/parser, or the rule source, and commit whatever stage 1 rewrites.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/regen-rules-corpus.sh
#
# Runs from the module root so rules/ resolves.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/../.." && pwd)"

cd "${MODULE_DIR}"

# THE STAGES MUST RUN AGAINST THIS TREE'S ENGINE, NOT AN INSTALLED JAR.
#
# The generators write as they go, so a stale engine rewrites part of the corpus and then dies —
# or, worse, produces merely *different* output, writes all of it, and leaves a diff that reads as
# ordinary regen churn. Measured 2026-08-04, regenerating after Fix #145 landed: NoSuchMethodError
# Operation.getMissingValues() after 54 files had already been rewritten (+32,463 / -11,380 lines).
#
# scripts/lib/corpus-gen-env.sh is where that is dealt with. It builds the reactor and resolves the
# stage classpaths so all EIGHT net.cumba.corej dependencies come from this tree's target/classes,
# then refuses to start a stage if any of them still resolves from ~/.m2. Its header records why
# the two obvious repairs do not work — in particular that adding `-am` to a bare `exec:java`
# fires the goal on corej-parent, the first reactor module. Read it before changing anything here,
# and do not inline a fresh `exec:java` stage below: CorpusGenScriptGuardTest fails the build if
# any script under scripts/ reintroduces that shape.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE the first generator runs — an abort here writes zero files.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "stage-0 (test-scope) classpath"

# Stage 0 — assemble from rules-src/ and emit the shipped corpus.
# Test scope, not main: the split-storage tools (RuleStorageAssembler, RuleCorpusGenerator) live
# in src/test because jackson-dataformat-yaml is a test-scoped dependency (YAML must not leak into
# the engine jar).
cgen_run_main_test net.cumba.corej.core.expr.convert.RuleCorpusGenerator
# Stage 1 — refresh the engine's curated fixture corpus (PLAN-engine-rules-decoupling §4):
# if a regenerated rule is pinned by an engine fixture, the fixtures must follow, or the
# engine's FixtureCorpusIntegrityTest / this module's RulesFixtureSyncTest will fail.
python3 "${MODULE_DIR}/scripts/build-core-fixtures.py"
