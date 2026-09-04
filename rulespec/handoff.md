# Spec-runner — Milestones 1–3 + Waves 1–5 Handoff

> **This file is the de facto spec-schema document.** `SpecSchema.java`'s javadoc points at
> `core-engine-test-frw-plan.md` §3 for the schema definition, but **that file no longer exists
> anywhere in the repo** (three dangling references remain). Until someone reinstates it, the
> schema is documented here.

Historical status below is **as of 2026-04-20**; the numbers in it are of their time. Current
state, 2026-08-01: **1031 specs**, both lanes (Java parity module 1179 tests; Python lane 3281 =
1031 x 3 spec-parametrized suites + 188 harness self-tests), with the baseline manifest at **232**
entries, **15** of which claim the `errors` channel.

## Assertion channels (current)

A spec is judged on **three independent channels**, and a baseline entry says which of them it is
allowed to diverge on:

| Channel | Spec field | What it asserts |
|---|---|---|
| `violations` | `expected_violations` | which violations the rule produced |
| `status` | `expected_status` | **whether the rule ran at all** |
| `errors` | `expected_errors` | **why it failed**, as a classified reason token |

`expected_status` is `EXECUTED` \| `SKIPPED` \| `ERROR`, **uppercase only**, and describes the
**Java** lane — Java-canonical, exactly like `expected_violations`. Omitting the key means
`EXECUTED`. A lowercase value (the vendored fork's own vocabulary is lowercase) fails at load on
both lanes rather than silently never matching, and a mistyped *key* is caught by
`SpecTopLevelKeysTest`, which derives its allow-list from `SpecSchema`'s record components by
reflection.

All three channels are **always** asserted. There is deliberately **no toggle** — both fields spent
all of M1 declared-but-never-read, and a switchable assertion is one that dies again. See
`PLAN-parity-expected-status.md` and `PLAN-parity-expected-errors.md`.

**CAPTURE is status-blind AND errors-blind:** it writes `expected_violations` and nothing else.
Both other fields are hand-authored only. Capture exists only on the Python lane
(`SpecRunner.run` throws for any mode ≠ ASSERT), so it could only ever observe the *Python* value —
maintaining a Java-canonical field from it would delete correct pins wherever the lanes disagree.
`expected_status` was never written; `expected_errors` **was**, until D2 of
`PLAN-parity-expected-errors` stopped it. Pinned by
`test_capture_preserves_a_hand_written_expected_status` and
`test_capture_preserves_a_hand_written_expected_errors`.

### The `errors` channel: tokens, not prose (2026-07-31)

`expected_errors` holds **classified reason tokens**, never engine messages, and is Java-canonical
like every other expectation. Both sides are sorted and de-duplicated before comparison, so it
asserts *which* reasons occurred, not how many datasets produced each.

- **Java** — `ViolationNormaliser.reasonsFrom` maps a result to one of
  `LOAD_ERROR` \| `RELREC_KEY_EXPANSION` \| `NO_NATIVE_CHECK_EXPR` \| `OTHER`, derived in the
  *harness* from the status message plus `rule.getLoadError()`. The engine carries no
  parity-specific type. `ExpectedErrorsTest$EngineContract` pins the two fixed messages against
  `RuleRunner`'s source and asserts the engine still has exactly 4 `ERROR`-producing sites, so a
  reword or a new site fails the build instead of silently degrading to `OTHER`.
- **Python** — `engine_adapter` harvests the fork's own `FailedValidationEntity.error` class from
  `entry["errors"][i]["error"]`. Measured across the corpus: `Rule format error` (9 specs),
  `An unknown exception has occurred` (3), `Error occurred during operation execution` (2).

The two vocabularies do **not** map onto each other 1:1 and are not forced to; a Python token that
differs from the Java-canonical expectation is a baselined lane divergence like any other.

**The corpus's one Java-lane exercise of this channel** is
`OPGAP-shares-at-least-one-element-with` — the first `expected_status: ERROR` pin and the first
non-empty `expected_errors` anywhere in the corpus. It pins a real operator-support gap: the fork
implements `shares_at_least_one_element_with`, the Java engine has no such operator, so the Check
raises no native expression and `RuleRunner` returns `ERROR` / `NO_NATIVE_CHECK_EXPR` rather than
evaluating on the retired legacy engine. Every *other* entry claiming `errors` runs the opposite
way (Python produces a reason, Java none), which is why `test_expected_errors.py` keeps two
separate tables — `MEASURED_PYTHON_REASONS` and `JAVA_CANONICAL_ERROR_SPECS` — and asserts a spec
is in exactly one.

**Why not just diff the engine's error message?** Because it carries almost no signal. The fork's
container message takes only two values corpus-wide (`rule execution error` ×13,
`rule evaluation error - operation failed` ×2) and merges unrelated failures; the reason class one
level down separates them three ways. And the *presence* of an error is already implied by the
status channel — `engine_errors != []` ⟺ folded status is `ERROR`, by construction — so a channel
asserting only "did it error" would add nothing.

> **The reason list is harvested only inside the execution-error branch.** A `skipped` entry also
> carries an `errors` list with a real `error` key (`Outside scope`, on `CDISC-AD0500` and
> `CDISC-AD0897`). Harvesting those would attach reasons to a SKIPPED rule and break the
> biconditional above, which is what lets the channel mean something the status channel does not.

**Where the lanes legitimately disagree on status** (re-measured 2026-07-30 across all 995 specs):
Java-only skips come from `Scope.Variables` (incl. qualified `DATASET.VAR`) and a folded-FALSE
`Precondition`; Python-only ones from `Scope.Classes`, `Scope.Entities`, `Use_Case` under
`standard=='tig'`, an empty dataset, and the degrade paths (`KeyError` →
`COLUMN_NOT_FOUND_IN_DATA`, missing metadata, `DomainNotFound`). Both lanes skip on a `Domains`
mismatch, `Data_Structures`, `Subclasses` and provider absence. The fork's *reason* for a skip is
not on the result entry — it is one level down, in `entry["errors"][i]["error"]`.

## The `define_xml` channel (2026-07-31)

`define_xml` supplies a spec's Define-XML overlay, keyed by **dataset name**. It has **two value
shapes**, discriminated on array-vs-object:

```yaml
define_xml:
  VS:                                   # SHAPE A — variable-level: an ARRAY of variable records
    - {define_variable_name: VSPOS, define_variable_ccode: C99999}
  XY:                                   # SHAPE B — dataset-level: an OBJECT of dataset attributes
    define_dataset_class: TRIAL DESIGN
    define_dataset_label: Custom Dataset
```

Shape A is the original variable-level channel and is **domain-keyed** (legacy behaviour,
unchanged). Shape B is dataset-level and is **dataset-name-keyed** on both lanes, so a `SUPPAE` /
`APDM` / split-dataset key works even though its name differs from its domain.

### What the key set means

**Every key of the block — in either shape — is a dataset the Define declares.** The key set feeds
`getDatasetNames()` on the Java lane and the stub reader's `read()` on the Python lane, which is
what the `define_dataset_names` Operation reads.

⚠ The key set must **never** reach the *study* inventory. `$study_datasets` comes from the
`datasets:` map alone (Java `inventoryResolver` → `tables.keySet()`; Python
`data_service.get_datasets()`). Folding define keys in would put a Define-only dataset into
`$study_datasets` and silently break `FDA-SD0061`'s `not_contains_all`.

A key with **no `datasets:` entry** is legal and meaningful: it is a **Define-only dataset**, one
the study does not ship. `FDA-SD0061` exists to catch exactly that. The converse — a `datasets:`
entry with no `define_xml` key — means the Define does not declare it: the operand is **absent** on
Java and a **`None` cell** on the fork, and `non_empty(...)` is false on both.

### Shape-B attributes

The allow-list is the fork's eleven `ItemGroupDef` columns, in the fork's order:

`define_dataset_name`, `define_dataset_label`, `define_dataset_location`, `define_dataset_domain`,
`define_dataset_class`, `define_dataset_structure`, `define_dataset_is_non_standard`,
`define_dataset_variables`, `define_dataset_variable_order`, `define_dataset_key_sequence`,
`define_dataset_has_no_data`.

**Only three have a Java sink**, and one is spelled differently from the operand:

| spec key | `getDatasetMetadata` map key |
|---|---|
| `define_dataset_name` | `name` |
| `define_dataset_label` | `label` |
| `define_dataset_class` | **`className`** |

`RuleRunner.injectDatasetLevel` reads exactly those three. **The other eight are Python-lane-only**
— a Check over one of them yields *opposite verdicts* across the lanes (measured: `equal_to` on
`define_dataset_structure` → Java 0, Python 1). **Declare only attributes the rule under spec
actually reads.**

### Defaults, and why you must not restate them

`define_dataset_name` defaults to the key; `define_dataset_location` defaults to
`<key.lower()>.xpt`. Both defaults apply to **every** key, in **both** shapes, on **both** lanes —
with one asymmetry worth knowing: the **location** default is served as data only on the Python
lane. Java has no location operand and no sink for it, and computes the default solely as R6's
reference value.

**Do not restate them.** Doing so removes the derivation from the execution path (Python's fill is
`if record.get(...) is None`; Java re-assigns the identical value), so a spec that looks like it is
testing the defaults is in fact testing nothing. R6 rejects any value *other* than the derived
default anyway — see below for why.

The location default is load-bearing on the Python lane: the fork merges the Define frame onto the
study frame on (`dataset_name`, `dataset_location`), and `DummyDataset.filename` is
`f"{domain.lower()}.xpt"`. A mismatch reads as **not declared** and the rule silently no-fires.

### Rejections

Both lanes reject these at spec load, with a message naming the spec and the key:

| | |
|---|---|
| **R1** | `define_xml` present with zero keys (`{}`) |
| **R2** | a key whose value is neither an array nor an object |
| **R3** | a key whose value is an empty array or empty object |
| **R4** | a shape-B object carrying a sub-key outside the allow-list |
| **R5** | a shape-A array element that is not a mapping |
| **R6** | a declared `define_dataset_name` ≠ the key, or `define_dataset_location` ≠ `<key.lower()>.xpt` |
| **R7** | a null-valued shape-B sub-key (a bare YAML key) |
| **R8** | a non-textual `define_dataset_name` / `_label` / `_class` |
| **R9** | a non-string block key (Python lane only — Jackson cannot produce one) |

R1 and R3 exist because an empty block would otherwise reach the engine with a **non-null** Define
provider and an **empty** dataset-name list — and `DEFINE_DATASET_NAMES` has no
empty ⇒ `LIBRARY_NOT_AVAILABLE` demotion (unlike `DEFINE_KEY_VARIABLES`), so
`FDA-SD1063`'s `ds_name("DATA") not in []` would fire on **every** dataset. A silently wrong
verdict, not a skip.

### Traps worth knowing before you author

* **The Java status channel is blind to a dead Define channel.** Killing the channel yields
  `EXECUTED` with zero findings, **not** `SKIPPED`, because the define skip gate keys on the
  provider merely being non-null and `putIfNonNull` then omits the operand. Only
  `expected_violations` catches it — so **a 0/0 result means your fixture is wrong**, not that the
  rule is clean. Every spec asserting a non-empty result must first be shown to fire on Java.
* **An undeclared `define_dataset_*` in `Output_Variables`** renders as an absent key on Java and
  `null` on Python. Declare everything the rule outputs.
* **`library_mocks.domain_custom` is consumed on the Java lane only** — the fork has no consumer
  for it at all. For a `domain_is_custom`-gated rule, what makes both lanes agree is using a
  **non-standard dataset name**, not the mock. A spec using a real domain name here would silently
  diverge.
* **No Define block at all** ⇒ Java SKIPs a define-dependent Operation while the fork reports an
  *execution error* (EC-42, baselined on `channels: ["status"]`).

Reference specs: `FDA-SD1325` (control), `FDA-SD1325-multi` (liveness anchor),
`FDA-SD1325-{define-only,sibling}-leak` and `FDA-SD9999-define-only-leak` (EC-41 / Fix #132
regression guards), `FDA-SD0061` (the key-set / D3 pin), `CDISC-SEND-0300` (shape-A name
derivation).

## Historical status (2026-04-20)

The framework defined in `core-engine-test-frw-plan.md` is wired through ASSERT and CAPTURE
modes on both engines, with library-metadata mocks honored end-to-end. **Baseline manifest
(plan §9.4) is live** — specs tied to known Java/Python divergences XFAIL cleanly instead
of blocking the suite.

**32 specs on the test suite at the time:** 22 real CORE-rule ports from `rules-sdtmig-3-4.json`
(Waves 1–3), plus 5 test-derived ports from `tests/unit/test_rules_engine.py` (Wave 4),
plus 5 hand-authored M1/M2/M3 demonstration pilots. **3 XFAIL** (CORE-000012,
Wave4_EqualLength, Wave4_DomainPrefixWildcard) baselined against known engine gaps.
**Wave 5 yielded 0 portable specs** — all 32 `test_validate.py` tests are CLI-wrapper
integration tests, not rule execution. **Filed Fix #15** in `core-engine-update-plan.md`
for the Output_Variables-inference Java engine gap.

## Test results

| Suite | Lane | Tests | Result |
|---|---|---|---|
| `CanonicalViolationTest` | Java (Maven) | 4 | green |
| `SpecLoaderTest` | Java (Maven) | 4 | green |
| `BaselineManifestTest` | Java (Maven) | 4 | green |
| `RuleExecutionParityTest` | Java (Maven) | **32 specs** (3 XFAIL) | green |
| `test_canonical_byte_stability.py` | Python | 6 | green |
| `test_loader.py` | Python | 4 | green |
| `test_specgen.py` | Python | 6 | green |
| `test_capture.py` | Python (CAPTURE byte-stability + round-trip) | 65 | green |
| `test_specs_against_python.py` | Python | **32 specs** | green |
| `cdisc-rules-engine/` SHA | git status | — | unchanged |

Run commands:

```bash
# Java lane
mvn -T6 -pl lib/net.cumba.corej.core test \
    -Dtest='CanonicalViolationTest,SpecLoaderTest,RuleExecutionParityTest'

# Python lane (engine venv — Python 3.12 required by cdisc-rules-engine)
cd lib/net.cumba.corej.core/spec-runner/py
"$ENGINE_VENV/bin/python" -m pytest tests/test_specs_against_python.py -v

# Python meta tests (work on any Python 3.12+)
"$SPEC_RUNNER_VENV/bin/python" -m pytest \
    tests/test_canonical_byte_stability.py tests/test_loader.py tests/test_specgen.py
```

The two venvs (`spec-runner` for meta tests, `engine` for the engine adapter) are throwaway —
the user can recreate them via `uv` and the requirements files in `spec-runner/py/`.

## Pilot specs (both engines agree on the violation set for each)

| Spec | Shape exercised |
|---|---|
| `op_equal_to_string.yaml` | `kind: check_operator` micro-spec, synthetic-rule scaffold |
| `rule_simple_dm_age_below_18.yaml` | `kind: rule`, single-domain Record-Data rule |
| `rule_cross_dataset_dm_ae_usubjid.yaml` | `kind: rule`, Operations + cross-dataset `is_not_contained_by` |
| `rule_with_operation_record_count.yaml` | `kind: rule`, dataset-level violation (Sensitivity: Dataset) |
| `rule_library_mocks_valid_ct_dates.yaml` | `kind: rule` + `library_mocks: published_ct_packages`, exercises `valid_codelist_dates` Operation on both engines |
| `CORE-000351.yaml` | **Wave 1 port** — simple shape. `is_not_unique_set` over (USUBJID, DOMAIN) on DM. |
| `CORE-000252.yaml` | **Wave 1 port** — cross-dataset shape. DM↔DS join on USUBJID, dual-leaf check. |
| `CORE-000761.yaml` | **Wave 1 port** — library-mocks shape. `valid_codelist_dates` op + TSVCDREF/TSVCDVER check. |
| `CORE-000087.yaml` | **Wave 2 port** — AESER must be Y/N. `non_empty + is_not_contained_by`. |
| `CORE-000131.yaml` | **Wave 2 port** — AECONTRT must be Y/N. Mirror of CORE-000087. |
| `CORE-000017.yaml` | **Wave 2 port** — null-logic. CO.RDOMAIN must exist when IDVARVAL is populated. |
| `CORE-000713.yaml` | **Wave 2 port** — date comparison. DM.RFICDTC ≤ RFSTDTC. |
| `CORE-000714.yaml` | **Wave 2 port** — date comparison. DM.RFXSTDTC ≤ RFXENDTC. |
| `CORE-000027.yaml` | **Wave 2 port** — any-of presence. TE must have either TEDUR or TEENRL. |
| `CORE-000086.yaml` | **Wave 2 port** — cross-dataset. DV.DVSTDTC ≥ DM.RFICDTC. |
| `CORE-000236.yaml` | **Wave 2 port** — cross-dataset. MH.MHSTDTC ≤ DM.RFSTDTC. |
| `CORE-000913.yaml` | **Wave 2 port** — cross-dataset. DS.DSSTDTC = DM.DTHDTC for DEATH records. |
| `CORE-000679.yaml` | **Wave 2 port** — operation. DM.DTHFL=Y → DS DEATH record (`distinct` on DS.DSDECOD). |
| `CORE-000228.yaml` | **Wave 2 port** — operation. IE.IETESTCD must be in TI.IETESTCD (`distinct` on TI). |
| `CORE-000012.yaml` | **Wave 3 port — BASELINED as known divergence.** Variable Metadata Check semantics (Java row-level vs Python metadata-level). First baseline entry; XFAILs cleanly. |
| `CORE-000791.yaml` | **Wave 3 port** — relationship constraint. `is_not_unique_relationship` over (ACTARMCD, ACTARM). |
| `CORE-000841.yaml` | **Wave 3 port** — cross-dataset. AE.AEOUT='FATAL' ⇒ AEENDTC matches DM.DTHDTC. |
| `CORE-000008.yaml` | **Wave 3 port** — DM primary joined to SS. SS.SSSTRESC='DEAD' ⇒ DM.DTHFL='Y'. |
| `CORE-000259.yaml` | **Wave 3 port** — cross-dataset date. SS.SSDTC not earlier than DM.DTHDTC for DEAD records. |
| `CORE-000358.yaml` | **Wave 3 port — regression spec for Fix #1.** `variable_count --LNKGRP` across study. Confirms per-dataset template re-resolution is live. |
| `CORE-000224.yaml` | **Wave 3 port** — DM: ACTARM or ARMNRS must be populated. Dual-leaf `empty` check. |
| `CORE-000374.yaml` | **Wave 3 port** — DS disposition-event uniqueness. `not_exists` + `is_not_unique_set`. |
| `Wave4_SuffixMatch.yaml` | **Wave 4 port** — adapted from `test_validate_rule_invalid_suffix`. `suffix_matches_regex`. |
| `Wave4_PrefixMatch.yaml` | **Wave 4 port** — adapted from `test_validate_rule_invalid_prefix`. `prefix_matches_regex`. |
| `Wave4_NotEqualLength.yaml` | **Wave 4 port** — adapted from `test_validate_rule_not_equal_length`. `has_not_equal_length`. |
| `Wave4_EqualLength.yaml` | **Wave 4 port — BASELINED.** `has_equal_length` not implemented in Java (Finding #13). |
| `Wave4_DomainPrefixWildcard.yaml` | **Wave 4 port — BASELINED.** `--` wildcard unresolved in `Output_Variables` on Java (Finding #14). |

## What landed

### Foundation (M1)

**Java (`src/test/java/net/cumba/corej/core/parity/`):**
- `CanonicalViolation` — record + sorted-map serialisation
- `CanonicalJson` — single-source ObjectMapper config (snake_case via per-record annotations)
- `SpecSchema` + nested records — Jackson-deserialisable spec contract
- `SpecLoader` — YAML reader, recursive `$ref` resolver, DM row-key default + validation
- `RuleScaffold` — synthesises a synthetic rule from `kind: check_operator` specs;
  passes through explicit rules unchanged for `kind: rule`
- `FixtureLoader` — builds `IDataTable` from inline rows via `TestDataTable`; type
  inference; `__row_number__` virtual column support
- `MockLibraryProvider` — `LibraryMetadataProvider` backed by `SpecMocks` (used by
  the library-provider `RuleRunner.execute` overload). Java-side wiring is complete.
- `ViolationNormaliser` — `RuleExecutionResult` → canonical violations; row_key extracted
  directly from the primary `IDataTable` rather than from the engine's emitted values
- `SpecRunner` — orchestration (loader + scaffold + fixtures + mocks + RuleRunner +
  normaliser + diff). ASSERT mode only.
- `RuleExecutionParityTest` — `@ParameterizedTest @MethodSource` walking
  `spec-runner/specs/`. One JUnit case per YAML spec.

**Python (`spec-runner/py/`):**
- `canonical.py` — byte-identical violation serialisation to Java
- `schema.py` — pydantic-2 mirror of `SpecSchema.java`
- `loader.py` — YAML reader, `$ref` resolver, DM row-key default + validation
- `engine_adapter.py` — sys.path injection (engine dir never modified); rule-dict scaffold
  byte-identical to Java's; `DummyDataset`/`DummyDataService` wiring; result normalisation
- `spec_runner.py` — CLI + `run_spec()` entry point
- `tests/test_canonical_byte_stability.py` — locks the cross-language JSON contract
- `tests/test_loader.py` — mirrors `SpecLoaderTest.java`
- `tests/test_specs_against_python.py` — parametric pytest, mirror of
  `RuleExecutionParityTest.java`

**Maven (`pom.xml`):**
- `jackson-dataformat-yaml` 2.21.0 (test scope) — pulls SnakeYAML transitively
- `<testResource>` for `spec-runner/specs/` so spec files are also discoverable via
  classpath if a future test needs that

### M2 deliverables

- 6 templates under `spec-runner/specs/_templates/` (check_operator, operation,
  rule_simple, rule_cross_dataset, rule_supp_pivot, rule_relrec)
- `specgen.py` CLI — string-substitution scaffolder; unfilled `{{placeholder}}` tokens
  surface in stderr so the author knows what to fill in
- 2 new pilot specs added (cross-dataset + operation), bringing the total to 4 — together
  exercising the `kind: check_operator`, single-domain `kind: rule`, cross-dataset
  Match_Datasets join, and dataset-level violation shapes

### M3 deliverables

- `capture.py` — ruamel.yaml round-trip CAPTURE: load → run engine → replace
  `expected_violations` + `expected_errors` → write back. Comments and unrelated keys
  preserved.
- `--mode capture` added to `spec_runner.py` CLI (`python spec_runner.py --mode capture
  <spec.yaml>`).
- `tests/test_capture.py` — 9 tests covering CAPTURE byte-stability (§15.3, run twice,
  output identical), CAPTURE→ASSERT round-trip (§15.4, run on every pilot spec), and
  comment-preservation across CAPTURE.
- Authoring loop: write 10–15 lines of YAML, run CAPTURE, review the git diff, commit.
  Demonstrated end-to-end on a scratch spec — 2 violations captured cleanly.

### Wave 1 deliverables (3 ports from rules-sdtmig-3-4.json)

Specs ported to `spec-runner/specs/CORE-000xxx.yaml` with rule bodies extracted into
`spec-runner/specs/fixtures/CORE-000xxx/rule.json`. Inline datasets exercise both
violating and non-violating rows. Both engines emit identical canonical violations.

- **CORE-000351** — DM USUBJID uniqueness. Single domain, no Operations, no
  Match_Datasets. Required `library_mocks.classes: {special-purpose: [DM]}` so the Python
  engine's `Scope.Classes.Include` filter passes.
- **CORE-000252** — DM/DS join: subject with DSDECOD='DEATH' must have DM.DTHFL='Y'.
  `Match_Datasets: [{Name: DS, Keys: [USUBJID]}]`. Required `library_mocks.classes` for DM
  and DS. **Found a Java engine gap** (see findings below) — fixture rule was enhanced
  with explicit `Output_Variables: [DSDECOD, DTHFL]` to work around it.
- **CORE-000761** — TS published-CT-date validation via `valid_codelist_dates` Operation.
  `library_mocks.published_ct_packages` provides the date list; `library_mocks.classes:
  {trial design: [TS]}` for scope.

### Findings landed during Wave 1

These are real engine differences the parity framework surfaced. None are showstoppers
for further porting, but the user should know about them.

1. **Java's `Outcome.Output_Variables` must be explicit.** Java's `extractOutputValues`
   only emits keys listed in `Outcome.Output_Variables`. Python infers the columns
   referenced by the Check leaves when the field is absent. Real CORE rules
   (e.g. CORE-000252) sometimes omit `Output_Variables` and rely on the Python
   behaviour. Without explicit listing, Java emits `output_variables: {}` for every
   violation. **Workaround applied to CORE-000252:** the local fixture rule.json adds
   the field; the original `rules-sdtmig-3-4.json` is untouched. **Suggested follow-up:**
   file as a Java engine bug — Java should mirror Python's leaf-derived inference when
   the field is absent.
2. **Empty-string vs. null in canonical output.** Java's `Violation.values` map stores
   `""` for missing/invalid IDataValue results; Python pandas surfaces these as None.
   Per canonical contract (§3.5: "Missing values: null"), `ViolationNormaliser.java`
   now coerces `""` → null before emitting CanonicalViolation entries. Without this both
   engines diverge on every rule whose Output_Variables include a missing field.
3. **List-typed output_variables stringification.** Operations like
   `valid_codelist_dates` produce a Python `list` value that the engine stringifies into
   `Violation.values`. Java's `List.toString()` form is `[a, b, c]` (no inner quotes);
   Python's `str(list)` form is `['a', 'b', 'c']`. The Python adapter
   (`engine_adapter._engine_value_to_str`) now emits Java's form so canonical strings
   match byte-for-byte.
4. **Class-name mock convention.** Python's `convert_library_class_name_to_ct_class`
   only special-cases `"special-purpose"` → `"SPECIAL PURPOSE"`. For any other class,
   the engine compares `class_name.upper()` against `Scope.Classes.Include`. Mock keys
   in `library_mocks.classes` therefore have to match the upper-cased Pascal-with-space
   form the rule uses (e.g. `trial design` for `TRIAL DESIGN`). Documented inline in
   CORE-000761; will be a recurring footgun for future ports until the adapter learns
   to normalize automatically.
5. **`get_dataset_class` requires a non-None container.** Python's
   `BaseDataService.get_dataset_class` dereferences `library_metadata.standard_metadata`
   unconditionally and crashes on None. The adapter now always passes a
   `LibraryMetadataContainer` (empty when no mocks are declared) — the engine's
   "no library data" code path then runs cleanly via `_handle_custom_domains`.
6. **Engine ExecutionStatus surface.** The Python engine emits five
   `executionStatus` values: `"success"`, `"skipped"`, `"execution error"`,
   `"issue reported"`, `"unknown status"`. Earlier versions of the adapter only checked
   for `"skipped"` and `"error"` (lowercase) — anything else fell through and the
   engine's diagnostic `errors` payload got mistakenly parsed as violations. Now mapped
   correctly: `success` → no violations, `execution error`/`unknown status` → ERROR with
   message captured in the `engine_errors` list, `skipped` → SKIPPED, `issue reported`
   → process per-row violations.

   **Updated 2026-07-30 (PLAN-parity-expected-status D6).** The rule-level status is no longer
   accumulated per result entry — that was last-writer-wins, so a trailing `"skipped"` could
   overwrite an earlier `"execution error"`, and the adapter could report `SKIPPED` for a call
   that had emitted violations (a state the Java lane cannot produce). It is now folded **once,
   after the loop**, by `engine_adapter._fold_execution_status`, using the fork's own precedence
   from `utils.get_execution_status`: `unknown > execution error > issue reported > success >
   skipped`. An **empty** result list yields the internal `NOT_RUN` token — deliberately outside
   the spec vocabulary so it satisfies no `expected_status` and cannot masquerade as a clean
   `EXECUTED`. Previously `rule_status` was *initialised* to `"EXECUTED"` and an empty
   `primary_results` never entered the loop, so "the engine produced no result at all" was
   indistinguishable from "the rule ran and found nothing".
7. **Python data_service holds its own library_metadata reference.** Both
   `RulesEngine(library_metadata=...)` AND `DummyDataService.get_instance(...,
   library_metadata=...)` need the container — different code paths read from each.

### Wave 2 deliverables (10 ports across shapes)

All 10 ports run green on both engines. Mix of single-domain rules (5), cross-dataset
joins (3), and Operation-bearing rules (2). Each spec includes both violating and
non-violating fixture rows so the rule's logic is actually exercised, not just
short-circuited at scope-mismatch.

- **Simple shape (5):** CORE-000087, 000131 (AE Y/N flags); CORE-000017 (CO null
  pointer); CORE-000713, 000714 (DM date comparisons); CORE-000027 (TE any-of presence).
- **Cross-dataset shape (3):** CORE-000086 (DV vs DM dates); CORE-000236 (MH vs DM
  dates); CORE-000913 (DS DEATH date matches DM.DTHDTC).
- **Operation shape (2):** CORE-000679 (`distinct` on joined DS, `does_not_contain`);
  CORE-000228 (`distinct` on TI, `is_not_contained_by`).

### Findings landed during Wave 2

The framework caught additional engine differences during Wave 2. Each was either
fixed in the adapter or worked around in the spec.

8. **YAML 1.1 vs 1.2 ambiguity for `Yes`/`No` strings.** ruamel.yaml dumps unquoted
   `Yes` if it judges the value safe under YAML 1.2; jackson-dataformat-yaml on the
   Java side parses it as the boolean `true` per YAML 1.1 semantics. Same input file,
   two different in-memory values. Surfaced when CORE-000131 used `AECONTRT: "Yes"` as
   the invalid test value. **Workaround applied:** test data uses obviously-invalid
   strings (`"Z"` in CORE-000131) that don't trigger the bool-keyword conversion.
   **Suggested follow-up:** in `capture.py`, force-quote any string scalar that matches
   YAML 1.1 reserved keywords (`yes/no/y/n/true/false/on/off`) so round-tripped specs
   parse identically across engines. Or pin both loaders to YAML 1.2.

9. **Variable Metadata Check dataset-projection divergence (CORE-000012).**
   **Re-triaged 2026-04-21** — original characterization ("row-level vs metadata-level")
   was wrong. Both engines implement `exists` identically: a column-presence check
   broadcast to all rows (Java `allSet(rowCount)` / Python all-True Series). The actual
   divergence is that **Python swaps the dataset for a `VariablesMetadataDatasetBuilder`
   projection** before evaluating the Check. That projection's columns are meta-field
   names (`variable_name`, `variable_order_number`, `variable_label`, ...) — NOT the
   original dataset's columns. `exists("AEOCCUR")` against this projection finds no
   column named `AEOCCUR` → 0 violations. Java doesn't do this swap; `exists` runs
   against the original dataset's column metadata and fires. **Java's output matches
   the rule author's intent** ("raise an error when AEOCCUR exists in AE dataset").
   **Action:** likely a Python engine bug or rule-authoring mismatch; we don't own
   Python, so not actionable on our side. Spec stays baselined on the Java side
   (harness only supports "XFAIL on Java" direction today). Follow-ups: (a) add
   symmetric Python-side baseline so "Python-buggy" specs can XFAIL the Python lane;
   (b) report upstream to CDISC / cdisc-rules-engine. See
   `documentation/spec-runner-findings.md` Finding #9 for the full trace
   *(that document now lives at `lib/corej-cdisc-rules/documentation/spec-runner-findings.md`)*.

### Wave 3 deliverables (baseline manifest + 8 ports + 1 baselined divergence)

**Baseline manifest (plan §9.4) — landed:**

- `documentation/parity-diff-baseline.json` — committed, starts with the CORE-000012
  entry (Variable Metadata Check semantics).
- `src/test/java/.../parity/BaselineManifest.java` + 4-test unit test. Snake_case JSON
  with per-entry `spec`, `fix_ref`, `description`, `notes` fields. `@JsonIgnoreProperties
  (ignoreUnknown = true)` everywhere so future fields don't break loading.
- `RuleExecutionParityTest` XFAIL wiring: baselined specs are EXPECTED to fail Java
  ASSERT. If one passes unexpectedly → loud failure with "Fix may have landed — remove
  baseline entry" message. Non-baselined specs behave as today.

**Port batch (8 green + 1 baselined):**

- **Simple + relationship shapes:** CORE-000791 (is_not_unique_relationship); CORE-000224
  (dual-empty DM); CORE-000374 (DS disposition uniqueness, exercises the `not_exists`
  + `is_not_unique_set` combo).
- **Cross-dataset shapes:** CORE-000841 (AE FATAL ⇔ DM.DTHDTC); CORE-000008 (DM ⇔ SS
  reverse direction); CORE-000259 (SS date ⇔ DM.DTHDTC).
- **Regression spec for landed fix:** CORE-000358 confirms Fix #1 (variable_count
  `--template` per-dataset re-resolution) produces identical output on both engines
  when AE and CM each have a `*LNKGRP` variable.
- **Baselined divergence:** CORE-000012 — the first-ever baseline entry, demonstrating
  the XFAIL mechanism on a real known-divergent rule.

**Ports attempted and deferred:**

- **CORE-000744** (FA RELREC.**DECOD) and **CORE-000206** (Child:true SUPP) — both
  require the production `LocalDataService`'s RELREC / Child pre-merge that
  `DummyDataService` doesn't perform. Python emits the literal sentinel "Not in dataset"
  for cross-reference columns instead of resolving them; Java's `RelrecLookup` /
  `ChildMatchPreMerger` handle these correctly. Not an engine divergence — an adapter
  gap. Tracked as Wave 4 work; will need a production-like data service or the Python
  adapter to replicate the pre-merge logic.

### Findings landed during Wave 3

10. **"Not in dataset" sentinel normalisation.** Python's COREActions inserts the
    literal string `"Not in dataset"` into an error's `value` map for Output_Variables
    that don't resolve against the dataset (e.g. columns targeted by `not_exists`).
    Java's `extractOutputValues` silently omits the key. Fixed in the Python adapter
    (`engine_adapter.run_spec_against_python`) — drop keys whose stringified value is
    exactly `"Not in dataset"` so canonical output stays symmetric across engines.
    Without this fix, every rule using `not_exists` or cross-ref columns without
    pre-merge would surface as a divergence.

11. **Class-name convention for "FINDINGS ABOUT".** Python's
    `convert_library_class_name_to_ct_class` uppercases the mock key; for FA (class
    "FINDINGS ABOUT") the mock key must be literally `findings about` (with space).
    Getting this wrong silently skips the rule with "Rule skipped - doesn't apply to
    class." Documented inline in CORE-000744. Suggestion for future: improve the adapter
    to accept either the lower-cased or exact-case class name so specs are more
    forgiving.

12. **RELREC / Child pre-merge requires production data service (follow-up).** The
    `DummyDataService` the adapter uses doesn't replicate the production
    `LocalDataService.merge_relrec_datasets` / `_merge_rdomain_dataset` logic. Rules
    tied to Fixes #5/#6 need either (a) a production-like data service wired into the
    adapter, or (b) the adapter replicating the pre-merge. Tracked as Wave 4 blocker.

13. **Dot-qualified Output_Variables for unresolvable joins (Fix #126 / EC-32a,
    2026-07-30, final form after the P8 review).** Since Fix #126 an unresolvable
    Match_Dataset leaves the dot-qualified columns ABSENT from the fork's merged frame
    (the `empty` operator fires on the absent dotted name per coreJ's
    FIRES_ON_ABSENT_COLUMN), so the fork naturally omits those keys from error values.
    Java's `extractOutputValues` OMITS the entry when no `JoinLookup` was registered
    (absent dataset, or absent RIGHT-side join-key column) but RENDERS it for a
    registered lookup — including the LEFT-side-missing-key case, where every row
    resolves null and the entry renders "" → canonical null (empirically pinned by
    FDA-SD1012). The adapter mirrors the registration conditions
    (`_unregistered_join_datasets`) and ADDS the null entry for a declared dot-qualified
    Output_Variable of a registered-but-unmaterialised join, so both lanes' canonical
    output matches; merged-but-value-missing entries (CORE-000177's `DM.RFENDTC`) flow
    through untouched.

### Wave 4 deliverables (5 test_rules_engine.py ports + 2 baselined)

Pivoted approach for plan Wave 4: Python's fixtures in `tests/unit/test_rules_engine.py`
use the engine's internal (snake_case) rule dict format. Translated each chosen test's
fixture into the equivalent CORE-format rule JSON and authored minimal inline datasets
that mirror the Python tests' in-memory DataFrames.

**Green (3):**
- `Wave4_SuffixMatch` — exercises `suffix_matches_regex`.
- `Wave4_PrefixMatch` — exercises `prefix_matches_regex`.
- `Wave4_NotEqualLength` — exercises `has_not_equal_length`.

**Baselined (2):**
- `Wave4_EqualLength` — **Finding #13: `has_equal_length` not in Java's `CheckOperator`
  enum.** Java has only `HAS_NOT_EQUAL_LENGTH`. The Python-side inverse exists as
  `has_equal_length`, used by real rules. Filed as a real Java engine gap.
- `Wave4_DomainPrefixWildcard` — **Finding #14: `--` wildcard unresolved in
  `Outcome.Output_Variables`.** Java's `extractOutputValues` doesn't apply
  domain-prefix replacement when looking up `--STDY`, so the violation payload is empty
  even though the CHECK leaf (which goes through `CheckConditionTransformer`) resolves
  correctly. Same row_key fires on both engines; only output_variables differs. Same
  family as Fix #15 (Output_Variables handling).

### Wave 5 deliverables (0 ports — out of scope)

Plan Wave 5 targets `tests/QARegressionTests/test_core/test_validate.py` (32 tests).
Survey result: **all 32 tests are CLI-wrapper integration tests**. They use
`run_command` → subprocess → `python core.py validate ...` and inspect stdout/stderr,
check generated Excel/JSON files, verify log-level flag handling. None exercise
`validate_single_rule`/`validate_single_dataset`. Marking Wave 5 as completed with
zero portable specs — its target suite tests CLI behaviour, not rule execution.

Net Wave-4/5 contribution: **+5 specs on the test suite** (3 green + 2 baselined).
Total port count: 32 specs (29 green + 3 baselined).

### Findings landed during Wave 4

13. **`has_equal_length` operator missing in Java** (Wave4_EqualLength baselined).
    Python's check_operators registers both `has_equal_length` and
    `has_not_equal_length`; Java's `CheckOperator` / `OperatorRegistry` only has
    `HAS_NOT_EQUAL_LENGTH`. Real rule rules-sdtmig-3-4.json uses this operator; a
    grep surfaces its use cases. Action: straightforward to add — copy the existing
    length-check helper with inverted comparison.

14. **`--` wildcard unresolved in `Output_Variables`** (Wave4_DomainPrefixWildcard
    baselined). `RuleRunner.extractOutputValues` iterates the declared variable names
    as-is. Python's `_extract_targets_from_output_variables` applies
    `_replace_variable_wildcards` before the lookup. Add a pre-pass to
    `extractOutputValues` that runs domain-prefix replacement on each `outputVars`
    entry. Low risk — mirrors what `CheckConditionTransformer.resolvePrefixes` already
    does for Check leaves.

### M3.5 deliverables (closes the M2 library-mocks gap)

- `engine_adapter._build_library_metadata` — translates `SpecSchema.SpecMocks` into a
  `LibraryMetadataContainer` and passes it to `RulesEngine` via the `library_metadata`
  kwarg.
- New pilot `rule_library_mocks_valid_ct_dates.yaml` exercises `valid_codelist_dates` on
  both engines with `published_ct_packages` mocked. Both engines emit identical canonical
  violations.
- The library_metadata adapter currently maps `published_ct_packages` and
  `dataset_metadata` keys (the only ones the new pilot exercises). Other mock fields
  (`domain_variables`, `model_variables`, `codelist_terms`, etc.) are still pass-through
  no-ops; they get wired per-rule as port waves require them, so the adapter stays scoped
  to operations actually exercised by green specs.

## Pinned specs — the D1/D4 sync contract (2026-07-30)

Five ADaM `y`-wildcard specs — `CDISC-AD0353`, `CDISC-AD0354`, `CDISC-AD0702`,
`CDISC-AD0703`, `CDISC-AD0790` (the filter-on-max family) — carry a **fully inline
pinned rule** instead of `rule: {coreId, override}`. Neither engine expands
variable-name wildcards at runtime, so each spec pins `y=1` (A1IND/B1IND); the pin
was derived once from the rules-legacy entry plus the historical override and
**deliberately does not track the corpus** (that independence is what makes the
spec corpus-form-proof — see `plans/done/PLAN-parity-java-lane-native-corpus.md` D1).

The sync duty is enforced, not documented-only (D4): each pinned spec records
`pinned_from: {core_id, rules_src_sha256}`, and `PinnedSpecSyncGuardTest` fails the
Java lane whenever the fingerprinted `rules-src/checks` file changes. To re-sync:
re-derive the `rule:` block (legacy entry − Authorities/Standards, deep-merge the
y=1 pin), re-capture expectations if behaviour legitimately moved, and update the
recorded sha256.

## Decisions worth knowing

1. **Value resolution: bareword = column, quoted = literal.** Historical note: the Java
   side once carried a `literalFallback` flag so that an unresolvable value-position token
   fell back to a literal string, matching Python's permissive default. That flag was
   removed with the legacy engine (`plans/done/PLAN-legacy-engine-removal.md`) — it had become
   unreadable by any live code path. Specs must declare literals explicitly
   (`value_is_literal: true` / quoted in expression form); a bareword that resolves to no
   column yields `null` and the comparison simply does not fire.
2. **YAML loader: `jackson-dataformat-yaml`, not raw SnakeYAML** — keeps Jackson as the
   single (de)serialisation stack and matches the existing project idiom. SnakeYAML still
   ships transitively. The plan suggested SnakeYAML; this swap costs nothing and is
   easier to maintain.
3. **`py/` is not a package** — neither `py/__init__.py` nor `py/tests/__init__.py`. With
   pytest, having `py/` as a package collides with the pre-existing global `py` library;
   dropping the inits sidesteps that and the runtime sys.path injection in
   `engine_adapter.py` handles imports cleanly.
4. **Two Python venvs** — `engine` (3.12, full requirements) for the engine adapter;
   `spec-runner` (any 3.12+) for the meta tests. Cheap separation; the meta tests don't
   need numpy/pandas.
5. **Inline rows only in `FixtureLoader` for now** — `$ref` to CSV/JSON/XPT throws
   `UnsupportedOperationException`. Adding it pulls in provider-module test
   dependencies; defer until an actual spec needs file-backed fixtures.
6. **Row_key type coercion** — Java reads native types from `IDataTable`; Python falls
   back to `error.value` when `row` is None (dataset-level violations). Both produce the
   same canonical JSON for the same logical row.

## Known harness debt (2026-07-31)

Surfaced by `PLAN-parity-define-dataset-channel`, deliberately **not** fixed there and **not** filed
as EC entries — none of these is an engine divergence, so the EC register is the wrong home. Recorded
here so they survive that plan being archived.

1. **Three declared-but-dead spec keys.** `scope_override`, `operation` and `dictionaries` are
   record components of `SpecSchema` (and mirrored in `schema.py`), so `SpecTopLevelKeysTest` accepts
   them — but they are used by **zero** specs, `dictionaries` has no reader on either lane, and
   `kind: operation` throws on both (`RuleScaffold.java:48`, `engine_adapter.py:156`). The only
   remaining producer is `spec-runner/specs/_templates/operation.yaml.tmpl`, which still emits
   `operation:`. **Either implement or retire — a declared-but-dead channel is a trap**: an author
   who uses one gets silence, not an error.

2. **Dead code in the adapter.** `primary_meta` (`engine_adapter.py:1079`) is computed and never
   read; `metas` and `_build_dataset_metadata` exist only to compute it, and the comment above it
   describes a row-key-reconstruction consumer that no longer exists (row keys come from
   `primary_df`). Safe to delete, but verify the comment's claim first.

3. **`FDA-SD1063`'s case asymmetry — a semantics gap, NOT a parity divergence.** Both engines
   uppercase `$define_datasets` (`OperationExecutor.upperCaseAll`; `define_dataset_names.py`), but
   the Check's left operand `dataset_name` lowers to `ds_name("DATA")`, which is
   `Normalization.RAW`, under the case-**sensitive** `is_not_contained_by`. A lower- or mixed-case
   submitted dataset name therefore fires spuriously — **identically on both lanes**, which is why
   no baseline entry exists and why a parity spec cannot express it. It belongs to rule authoring or
   the engine's operator semantics, not to this harness.

Two further gaps have their own follow-up plans rather than a bullet here: the harness cannot set a
submitted dataset's **label** (so an `FDA-SD1325`-style control cannot distinguish `dataset_label`
from `dataset_name`), and it cannot observe **study-level attribution** — see
`plans/PLAN-parity-harness-observability.md`.

## What's deferred

### Java-side CAPTURE
Python is the oracle (OD-5), so Python-side CAPTURE is the primary authoring path —
that's what landed. Java-side CAPTURE is documented in the plan §7.4 but not implemented.
When it does come, it needs SnakeYAML 2.x's comment-preserving `LoadSettings` (or a
similar pattern) since Jackson's YAML adapter strips comments on round-trip.

### DIFF mode (Milestone 5)
Python orchestrator shells out to Java. Not started.

### `$ref` to non-object content
Only object refs are merged today. Array/scalar refs would need parent-replacement
plumbing in `SpecLoader.resolveRefs`; defer until a spec needs it.

### CLI for `SpecRunnerCli` and uberjar profile
Plan §5.3 / §5.4 mention an exec-maven CLI and a `*-spec-runner.jar` for DIFF mode. Not
needed for M1/M2; the JUnit harness covers Java parity verification.

### Baseline manifest (`parity-diff-baseline.json`)
Plan §9.4 needs this for the DIFF-lane regression net. Not relevant until DIFF lands.

## Suggested next session

Wave 3 done. 22 real CORE rules ported (21 green + 1 baselined). Sensible next steps
in priority order:

- **Wire a production-like data service into the Python adapter** so RELREC and
  Child:true Match_Datasets rules (Fixes #5 / #6) can be ported. Without it, any rule
  with cross-domain pre-merge semantics either skips silently or emits "Not in
  dataset" placeholders. Two options:
    1. Replace `DummyDataService` with the real `LocalDataService` pointed at fixtures
       on disk. More faithful but requires dataset materialisation.
    2. Extend the Python adapter to replicate
       `LocalDataService.merge_relrec_datasets` / `_merge_rdomain_dataset` using the
       inline DataFrames. More code but keeps the adapter self-contained.
- **Wave 4 (15–25 more ports + divergence-finding ports)** — with the data service
  upgrade, port rules tied to Fixes #5 / #6 (CORE-000744, CORE-000206 etc.) as
  regression specs. Each becomes green when the fix is verified OR baselined if
  Java still diverges.
- **Implement Fix #15 in the Java engine** (filed in `core-engine-update-plan.md`).
  Once landed, drop the `Output_Variables` workaround from CORE-000252's fixture rule
  — the engine will infer from leaves automatically.
- **Force-quote YAML 1.1 reserved keywords in `capture.py`** (finding #8 in this
  document). One-line change in `_yaml_scalar` — wraps strings matching
  `yes/no/y/n/true/false/on/off/null` with `DoubleQuotedScalarString` so reloaded
  specs parse identically across SnakeYAML 1.1 and ruamel 1.2.
- **Port 5–10 more straightforward rules** in parallel with the adapter work — there
  are still ~100 untouched single-domain "simple" rules in the package, and each one
  adds regression coverage for free.
- **DIFF mode + CI lanes (Milestone 5)** — once the spec library hits ~40+ specs,
  running both lanes by hand gets cumbersome.

The library_metadata adapter maps `published_ct_packages`, `dataset_metadata`, and
`classes`. Extend per-rule as port waves require them, rather than pre-mapping
everything.
