#!/usr/bin/env bash
#
# Regenerates documentation/routing-census.tsv — one row per shipped Core.Id
# carrying that rule's DERIVED Sensitivity and the evaluation Domain DomainScan
# infers from its Check (Rule_Type is no longer a rule field). One-shot tool; NOT part of the
# default build.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/gen-routing-census.sh
#
# WHY IT EXISTS. Neither column is authored — RuleClassifier derives Sensitivity
# and DomainScan infers the Domain at load — and the Domain decides which
# RuleRunner branch a rule executes under and the FindingScope of every finding
# it emits. Wave-34 lane B measured that the 4 724
# .cdt scenarios do NOT cover that routing: re-routing all 75 Domain Presence
# Check rules to Record Data produced ZERO red scenarios out of 5 284 (Fix #231).
# RoutingCensusDriftTest fails the build whenever the committed artifact stops
# matching this generator's output, and reports the difference as a per-rule
# diff — so a re-routing names every rule it moved instead of naming one test.
#
# ⚠ THIS IS STEP 6 OF SIX in the corpus regeneration sequence:
#   1. regen-rules-corpus.sh   2. regen-rule-equivalence.sh
#   3. documentation/rule-inventory/generate-inventory.py
#   4. documentation/rule-inventory/build-overview.py
#   5. gen-rule-doc-census.sh  6. gen-routing-census.sh  <-- this script
# It censuses rules/, so it must run AFTER step 1 rebuilds it. A regen that stops
# short leaves this artifact stale and the build red.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${MODULE_DIR}"

# THE GENERATOR MUST RUN AGAINST THIS TREE'S DERIVATION, NOT AN INSTALLED JAR.
#
# The census IS a transcript of RuleClassifier.deriveSensitivity and
# DomainScan.infer. A stale corej-cdisc-core on the classpath yields a census of
# an engine nobody is shipping, and RoutingCensusDriftTest then pins the wrong
# routing — which is strictly worse than no pin, because the next reader trusts
# it. scripts/lib/corpus-gen-env.sh builds the reactor, resolves the classpath
# from this tree's target/classes and refuses to start if any net.cumba.corej
# dependency still resolves from ~/.m2.
#
# Do not reintroduce an `exec:java` invocation here: CorpusGenScriptGuardTest
# fails the build if any script under scripts/ carries that shape.
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE the generator runs — an abort here leaves the artifact untouched.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "test-scope classpath"

# RoutingCensus is test-scope (it is a documentation tool, not shipped code), so
# it needs the test classpath — the same scope RuleDocCensus and
# RuleCorpusGenerator run in. It reads rules/ through RulePackageLoader, i.e. the
# corpus the engine executes, completed by the production derivation pass.
cgen_run_main_test net.cumba.cdisc.core.doc.RoutingCensus \
    "${MODULE_DIR}/documentation/routing-census.tsv"
