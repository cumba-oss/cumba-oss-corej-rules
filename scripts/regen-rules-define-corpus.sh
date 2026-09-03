#!/usr/bin/env bash
#
# Regenerates the shipped Define-XML rule corpus from the per-rule YAML source.
#
#   rules-define-src/checks/{CDISC,PMDA}/<Rule_Id>.yaml
#        |
#        +-- DefineRuleCorpusGenerator --> rules-define/ + packages.json
#
# A rule is published once per version it declares, so a rule applying to both 2.0 and 2.1
# appears in BOTH of its family's packages. 409 authored rules -> 701 placements across four
# packages. That mirrors the CDISC data corpus and is why exactly one version may be loaded
# at a time: two would collide on Rule_Id.
#
# ⚑⚑ LARGELY REDUNDANT SINCE 2026-09-02 (plans/PLAN-rules-corpus-build-integration.md). The
# corej-cdisc-rules build runs DefineRuleCorpusGenerator itself, at process-test-classes, into
# target/rules-define/ — which is not committed. `mvn -P main -pl lib/corej-cdisc-rules -am test`
# does everything this script does, with the reactor's classpath instead of a hand-resolved one.
#
# The old BYTE-IDENTITY gate ("a regeneration that changes no source must leave
# `git status -uall -- rules-define` empty") went with the committed corpus. There is nothing
# left for it to compare, and nothing left to go stale. DefineRuleCorpusRoundTripTest still
# asserts the round-trip contract from the build.
#
# Kept as an entry point for regenerating outside a full build.
#
# Usage (from anywhere):
#   lib/corej-cdisc-rules/scripts/regen-rules-define-corpus.sh
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${MODULE_DIR}"

# THE STAGE MUST RUN AGAINST THIS TREE'S CLASSES, NOT AN INSTALLED JAR.
#
# Same hazard as the CDISC corpus: the generator writes as it goes, so a stale
# corej-define-conformance would rewrite part of the corpus and then die -- or, worse, merely
# produce DIFFERENT output, write all of it, and leave a diff that reads as ordinary regen
# churn. corpus-gen-env.sh resolves every net.cumba.corej dependency to this tree's
# target/classes and refuses to start if any still resolves from ~/.m2. Its guard is generic:
# it rejects ANY jar under /net/cumba/corej/, so it covers corej-define-conformance without
# needing to know about it.
#
# shellcheck source=lib/corpus-gen-env.sh
source "${MODULE_DIR}/scripts/lib/corpus-gen-env.sh"

cgen_resolve_classpaths

# Fail fast BEFORE anything is written.
cgen_assert_fresh_engine "${CGEN_CP_TEST}" "define-gen (test-scope) classpath"

# Test scope, not main: this module has no src/main at all, and the Define-XML model arrives
# as a test-scoped dependency (corej-define-conformance) so it never leaks into a shipped jar.
cgen_run_main_test net.cumba.corej.define.conformance.gen.DefineRuleCorpusGenerator
