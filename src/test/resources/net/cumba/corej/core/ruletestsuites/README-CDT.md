# `.cdt` — CDISC Data Table Test Format

A compact, human-readable text format for defining test input tables used in
CDISC rule tests. Designed to be self-contained (metadata + data in one file),
diff-friendly, and minimal (only required fields need to be specified).

## Purpose

Before this format, test data was defined in Java code via `TestDataTable`
with `virtualTable(...)` + `setValue(row, col, value)` calls scattered
across dozens of lines. This made tests hard to review: you had to trace
each cell assignment individually to understand the data shape.

`.cdt` files put the test data where a reviewer can see it all at once — one
row per line, columns aligned, metadata at the top.

### Before

```java
@Test
void CDISC_AD0117_invalid() {
    var table = virtualTable("ADLBC", 3, "PARAMCD", "ATPT", "ATPTN");
    table.setValue(0, "PARAMCD", "HEIGHT");
    table.setValue(0, "ATPTN", "1");
    table.setValue(0, "ATPT", "Baseline");
    table.setValue(1, "PARAMCD", "HEIGHT");
    table.setValue(1, "ATPTN", "1");
    table.setValue(1, "ATPT", "Different Label");
    table.setValue(2, "PARAMCD", "HEIGHT");
    table.setValue(2, "ATPTN", "2");
    table.setValue(2, "ATPT", "Week 2");
    assertViolation("CDISC-AD0117", table, "ADLBC");
}
```

### After

Java:

```java
@Test
void CDISC_AD0117_invalid() {
    assertViolationCdt("CDISC-AD0117", "CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt");
}
```

`CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt`:

```
# CDISC-AD0117 invalid: within HEIGHT, ATPTN=1 maps to two different ATPT values.
dataset ADLBC class=BDS

col PARAMCD type=Char label="Parameter Code"
col ATPTN   type=Char label="Planned Time Point Number"
col ATPT    type=Char label="Planned Time Point Name"
---
HEIGHT | 1 | Baseline
HEIGHT | 1 | Different Label
HEIGHT | 2 | Week 2
```

## Format

```
# Comments start with '#' and may appear anywhere. Blank lines are allowed.

# 1. Dataset header — required, first non-comment non-blank line
dataset NAME [key=value ...]

# 2. Column definitions — one per line, in declared order
col NAME [key=value ...]
col NAME [key=value ...]
...

# 3. Section separator — required between metadata and data
---

# 4. Data rows — pipe-delimited, columns in declaration order
VALUE | VALUE | VALUE
VALUE | VALUE | VALUE
```

### Dataset header

Required first non-comment line. Grammar:

```
dataset <name> [class=<class>] [structure=<structure>] [subclass=<subclass>] [label="<label>"] [uri=<uri>] [<other-key>=<value>]
```

- **`<name>`** (required): the dataset name (e.g. `ADLBC`, `ADSL`, `DM`)
- **`class=<class>`** (optional): ADaM class, supports these shortcuts:

  | Shortcut | Expands to |
  |----------|-----------|
  | `ADSL` | `SUBJECT LEVEL ANALYSIS DATASET` |
  | `BDS` | `BASIC DATA STRUCTURE` |
  | `OCCDS` | `OCCURRENCE DATA STRUCTURE` |
  | `MD-BDS` | `MEDICAL DEVICE BASIC DATA STRUCTURE` |
  | `MD-OCCDS` | `MEDICAL DEVICE OCCURRENCE DATA STRUCTURE` |

  Full class names are also accepted. Use quotes for values containing spaces:
  `class="DEVICE LEVEL ANALYSIS DATASET"`.
- **`structure=<structure>`** / **`subclass=<subclass>`** (optional): the two ADaM scope
  axes, declared explicitly. See [ADaM structure / subclass](#adam-structure--subclass)
  below.
- **`label=<label>`** (optional): dataset label; use quotes if it contains spaces
- **`uri=<uri>`** (optional): the dataset's **source location**. See
  [Source URI](#source-uri-uri) below.
- **Other keys**: silently stored as table-level metadata (forward compat)

#### Source URI (`uri=`)

`extract_metadata("dataset_location")` / `("filename")` do **not** read the
table-metadata map — they read the table's URI
(`ScalarMetadataFunctions.extractMetadata` → `fileNameFromUri(meta.getTableURI())`,
last path segment, percent-decoded, original casing). A `.cdt` table is built by
`OverlayDataTable.empty(...)`, which sets name / label / row count and nothing
else, so without `uri=` that accessor is `null` on every scenario and a file-name
rule (`CDISC-SEND-0277`) can never fire. `uri=` supplies it:

```
dataset BW class=FINDINGS label="Body Weight" uri=file:///study/send/BW.xpt
```

- the value must be a syntactically valid URI — a malformed one is a parse error
  naming the dataset, never a silently dropped attribute;
- it is **also** kept in the table-metadata map, so a scenario round-trips
  through `CdtWriter` unchanged;
- `dataset_size` is a *different* channel: that one **is** an ordinary
  table-metadata key (`dataset_size=6000000000`), read through the `default` arm.

Worked examples: `cdisc/CDISC-SEND-0277/*` (an uppercase basename fires, a
lowercase one does not, and a scenario with no `uri=` at all pins the
absent-URI silence).

#### ADaM structure / subclass

`Scope.Data_Structures` and `Scope.Subclasses` are normally **detected from the
fixture's columns** (`AdamDataStructureDetector` / `AdamSubclassDetector`): `PARAMCD`
/ `AVAL` say BASIC DATA STRUCTURE, a column ending in `TERM` / `TRT` / `DECOD` says
OCCURRENCE DATA STRUCTURE, `AETERM` / `AEDECOD` on an OCCDS dataset says ADVERSE
EVENT, and so on. A fixture that carries none of them is `ADAM OTHER` with no
subclass, and a structure- or subclass-scoped rule is simply out of scope for it —
detection is the default precisely so a fixture cannot satisfy such a rule by omission.

`structure=` / `subclass=` add the **declared** tier: the scenario's stand-in for a
Define-XML `def:Class` / `def:SubClass` declaration. Both carry shortcuts:

| `structure=` | Expands to |
|--------------|------------|
| `ADSL` | `SUBJECT LEVEL ANALYSIS DATASET` |
| `BDS` | `BASIC DATA STRUCTURE` |
| `OCCDS` | `OCCURRENCE DATA STRUCTURE` |
| `OTHER` | `ADAM OTHER` |

| `subclass=` | Expands to |
|-------------|------------|
| `AE` | `ADVERSE EVENT` |
| `TTE` | `TIME-TO-EVENT` |
| `MD-TTE` | `MEDICAL DEVICE TIME-TO-EVENT` |
| `NCA` | `NON-COMPARTMENTAL ANALYSIS` |
| `POPPK` | `POPULATION PHARMACOKINETIC ANALYSIS` |

Full token names are accepted too (quote values containing spaces:
`structure="OCCURRENCE DATA STRUCTURE"`).

**Precedence — the declaration WINS (Fix #154).** This is the engine's own rule
(`AdamDataStructureDetector.detect(name, columns, declaredClass, defineFirst)` and
`AdamSubclassDetector.resolve(...)`), and the scenario harness applies it verbatim at
both layers:

- by default (`corej.defineFirst=true` since Fix #154) a **recognised declaration
  overrides the columns**. So `structure=OCCDS` puts the rule in scope whether or not
  the fixture carries an OCCDS column — including on a fixture carrying `PARAMCD`,
  where the declaration beats the confident BDS detection, exactly as a contradicting
  `def:Class` now would;
- with **`-Dcorej.defineFirst=false`** the preference flips back to the pre-#154 order:
  the column heuristic decides and the declaration fills in only when the heuristic
  yields `ADAM OTHER` (structure) or no subclass;
- a value that is **not** one of the tokens above is not a data structure / subclass at
  all: it folds to nothing and the heuristic decides alone. It does not "declare a bogus
  structure" that the gate then rejects;
- when there is **no** declaration and the columns are silent, the subclass gate has one
  last, Java-only tier: a BDS dataset named `ADPPK*`/`ADPPT*`/`ADPOPPK*` is popPK and one
  named `ADNCA*` is NCA (`AdamSubclassDetector.detectByName`, Fix #154). It is strictly
  below the columns, so an `ADPPK`-named fixture carrying the NCA relative-time columns
  still detects NCA.

The declaration is honoured **end to end**: the scenario pre-gate
(`ScenarioScopeGate`, run by the rule-test harness's `ScenarioRunner`) and the
runtime gate inside `RuleRunner` (Fix
#117/#118/#119) read the same declared value, the latter through
`ScenarioDeclaredScopeProvider`, which the engine sees as an ordinary
`MetadataProvider`. Two consequences worth knowing:

- **a carrier block is mandatory.** The provider is installed in whichever slot
  `RuleRunner` consults for the declared tier — the `#define` / `#define-xml` provider
  when the scenario has one, otherwise the `#library` / `#library-ref` slot. A scenario
  that declares `structure=` / `subclass=` **and has neither block** is rejected with an
  authoring error, because there is nothing to decorate: every availability gate in the
  engine is a bare `provider != null` test (`library_available()` reads
  `ctx.getLibraryProvider() != null`, and the operand / function skips test the same
  reference), so occupying an empty slot would silently switch that level's self-skips
  off and a library-dependent rule would execute instead of reporting "no Library
  access". Add `#define standard=<std> version=<ver>` — the right carrier for a pure
  declaration, since the declared tier *is* the Define-XML `def:Class` / `def:SubClass`
  channel and the define slot is the one `RuleRunner` consults first — or a `#library …`
  block when the rule needs Library metadata anyway;
- the declaration applies to the scenario's **primary dataset only**; other datasets in
  the file keep whatever the wrapped provider says.

Worked examples: `cdisc/CDISC-AD0261/*-declared-scope.cdt`,
`*-undeclared-scope.cdt` and `*-declaration-wins-over-columns.cdt`, plus
`cdisc/CDISC-AD0887/*-declared-scope-no-control-column.cdt` (the circularity
case: a rule that tests for the absence of the column the heuristic keys on),
plus
`ScenarioDeclaredScopeProviderTest` for the `corej.defineFirst` half.

### Column definitions

One `col` line per column, in the order the columns appear in data rows:

```
col <name> [type=<type>] [label="<label>"] [length=<int>] [format=<sasfmt>] [codelist=<name>]
```

- **`<name>`** (required): column name
- **`type=...`** (optional, default `Char`):

  | Type | Storage | Data format |
  |------|---------|-------------|
  | `Char` | String | Any text |
  | `Num` | Double | Integer or decimal: `3.5`, `100`, `0` |
  | `Date` | Double (SAS days since 1960-01-01) | ISO `YYYY-MM-DD` |
  | `Time` | Double (seconds since midnight) | ISO `HH:MM:SS` |
  | `DateTime` | Double (SAS seconds since 1960-01-01) | ISO `YYYY-MM-DDTHH:MM:SS` |

- **`label="..."`** (optional): variable label (used by rules that check `variable_label`)
- **`length=N`** (optional): declared length (some rules check it)
- **`format=<fmt>`** (optional): SAS format spec (`DATE9.`, `TIME5.`, `BEST12.`, etc.) used by rules that check `variable_format`. Auto-derived for `Date`/`Time`/`DateTime` types unless overridden.
- **`codelist=<name>`** (optional): controlled terminology reference
- **Other keys**: silently ignored (forward compat)

### Data rows

Each data row is one line of pipe-delimited values, in the order columns
were declared:

```
VALUE | VALUE | VALUE
```

Fields are trimmed of leading/trailing whitespace. Use quotes only if needed
to preserve spaces (rare for test data).

**Missing values** (the loader never stores `null`; full grammar: the
`.cdt` provider's README, *Missing values*):
- A blank field → `""` in a `Char` column, `MissingValue.MIS` in a
  `Num` / `Date` / `Time` / `DateTime` column
- An **unquoted** `.`, `._` or `.A` … `.Z` → that SAS missing value, in
  **every** column type, `Char` included — the only way to write a missing
  character value (since 2026-09-17). Nothing else is a sentinel: `NA` is a
  value
- A quoted sentinel (`"."`) is the literal string
- A single `.` on a line by itself → the all-missing row, one `.` per column
  (useful for single-column tables or "data-insensitive" metadata-check rules)

**Date/time formats in data:**

| Declared type | Data value format | Example |
|--------------|-------------------|---------|
| `Date` | ISO 8601 date | `2020-01-15` |
| `Time` | ISO 8601 time | `14:30:00` |
| `DateTime` | ISO 8601 datetime | `2020-01-15T14:30:00` |

These are parsed by the loader and stored as SAS numeric values at read time.

## File naming convention

One `.cdt` file per test variant. Files are organized per-rule:

```
src/test/resources/net/cumba/corej/core/ruletestsuites/
├── cdisc/                  # CDISC family (CDISC-AD*, CDISC-CG*, CDISC-SEND-*)
│   ├── CDISC-AD0117/
│   │   ├── CDISC-AD0117-valid-ADLBC.cdt
│   │   └── CDISC-AD0117-invalid-ADLBC.cdt
│   └── ...
├── core/                   # CORE family (CORE-*)
│   └── ...
├── draft/                  # DRAFT family (DRAFT-*, unpublished; see draft/README.md)
│   └── ...
├── fda/                    # FDA family (FDA-SD*, SE*, CT*)
│   └── ...
└── pmda/                   # PMDA family (PMDA-AD*, SD*, CT*)
    └── ...
```

The subtree name is simultaneously the **assembled-package prefix**: a
`<family>/` scenario resolves its rule out of
`lib/corej-rules/rules/rules-<family>-*.json` and nothing else. That is
what routes the CDISC-SEND rules — they are `CDISC-*` ids assembled into
`rules-cdisc-sendig-*.json`, so they live under `cdisc/` and declare
`#library standard=sendig version=…` (or `sendig-dart` / `sendig-genetox` /
`sendig-ar`); there is no separate `send/` family. It is equally what keeps
the unpublished `DRAFT-*` rules out of every production package.

Three families require an explicit `#library standard=… version=…` /
`#library-ref` declaration on every scenario — `fda`, `pmda` and `draft`
(`requireDeclaredStandard()`); `cdisc` and `core` make it optional only to
keep their migrated scenarios working. Where a declaration *is* present the
membership assertion runs in every family.

Naming pattern: `{RULE_ID}-{valid|invalid}-{DOMAIN}.cdt`

For tests with multiple valid/invalid variants beyond the basic pair, use a
descriptive suffix: `CDISC-AD0278-invalid_multiple-ADAE.cdt`.

## Using `.cdt` files in Java tests

The base class `AbstractAdamRuleTest` provides assertion helpers that load
`.cdt` files and run the rule:

```java
// Simple case — the rule operates on the loaded table alone
@Test
void CDISC_AD0117_valid() {
    assertNoViolationCdt("CDISC-AD0117", "CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt");
}

@Test
void CDISC_AD0117_invalid() {
    assertViolationCdt("CDISC-AD0117", "CDISC-AD0117/CDISC-AD0117-invalid-ADLBC.cdt");
}
```

For rules whose bindings look up the current dataset by name (e.g.,
baseline-lookup rules like CDISC-AD0181), use the self-resolver variant so
the loaded table is registered under its own domain name:

```java
@Test
void CDISC_AD0181_invalid() {
    assertViolationCdtSelfResolver("CDISC-AD0181", "CDISC-AD0181/CDISC-AD0181-invalid-ADLBC.cdt");
}
```

For cross-dataset tests that need multiple test tables:

```java
@Test
void CDISC_AD0204_invalid() {
    assertViolationCdtMulti("CDISC-AD0204",
            "CDISC-AD0204/CDISC-AD0204-invalid-ADSL.cdt",
            "CDISC-AD0204/CDISC-AD0204-invalid-DM.cdt");
}
```

All loaded tables are registered in the resolver under their dataset names,
and the rule is executed against the primary (first) table.

## RuleTest scenario format (`#!RuleTest`)

A `.cdt` file may also be a **self-contained rule-test scenario**: the rule
under test, the expected outcome, and the input data all live in one file.
These are discovered and run automatically by the per-family `@TestFactory`
classes (`RuleTestSuitesCdiscFactoryTest`,
`…DraftFactoryTest`, `…FdaFactoryTest`, `…PmdaFactoryTest`) — no Java glue per
test. A scenario file starts with the `#!RuleTest` shebang followed
by a short directive prelude, then one or more ordinary `dataset` blocks.

```
#!RuleTest
#test CDISC-CG0219 expect=noViolation domain=LB
#note "at least one timing variable present -> no violation"
#library standard=sdtmig version=3-4
#library model-variables LB --DTC:Timing --DY:Timing
dataset LB
col USUBJID type=Char
col LBDTC   type=Char
col DOMAIN  type=Char
---
 |  | LB
```

### Prelude directives

| Directive | Required | Meaning |
|-----------|----------|---------|
| `#!RuleTest` | yes (first non-blank line) | marks the file as a scenario |
| `#test <coreId> expect=violation\|noViolation\|skipped\|executionError domain=<NAME>` | yes | rule to run (by `Core.Id`), expected verdict, and the *primary* dataset (matched case-insensitively to a `dataset` name in the file) |
| `#note "<text>"` | no | human-readable description |
| `#expect-execution-error "<substring>"` | no | assert the engine's error message contains `<substring>`; requires `expect=executionError` (see *The `executionError` verdict*) |
| `#expectViolationCount <N>` | no | assert the rule fires exactly `N` times (see *Asserting where a rule fires*) |
| `#expectViolationAt [row=<N>] [severity=<LEVEL>] [nameOnly=<COL> ...] [COL=value ...]` | no | assert one fired location, optionally the check level it fired at, and the columns it lists by name without a value; repeatable, exact-set match |
| `#library <kind> ...` | no | inline CDISC-Library metadata (see below) |
| `#library-include <file.yaml>` | no | pull Library metadata from a YAML sidecar |
| `#library-ref standard=<id> version=<id> [ct=<pkg> ...] [substandard=<id>]` | no | run against **real** CDISC Library metadata, as held in our unified metadata store (see below); mutually exclusive with `#library` / `#library-include` |
| `#define <kind> ...` | no | inline synthetic Define-XML metadata — same grammar and merge order as `#library` (see *Real Define-XML sidecar* below) |
| `#define-include <file.yaml>` | no | pull synthetic Define-XML metadata from a YAML sidecar |
| `#define-xml <file.xml>` | no | run against a **real** Define-XML sidecar (same directory); serves `define_*` AND `define_vlm_*` accessors; mutually exclusive with `#define` / `#define-include` |
| `#ct-packages <pkg> [<pkg> ...]` | no | the run's **CT Packages** field, for a run-level CT-selection scenario; absent means a blank field. Ignored by the per-rule runner |
| `#ct-available none\|<pkg> [<pkg> ...]` | no | the CT packages the run's metadata store holds (run-level scenarios); absent means every referenced package is seeded |
| `#expect-abort "<substring>"` | no | the run must **abort** before any rule executes, with a message containing `<substring>`; requires `expect=skipped` |
| `#expect-ct-mismatch "<substring>"` | no | the completed run carries a `CT_Declaration_Mismatch` note containing `<substring>`; rejected together with `#expect-abort` |
| `#dictionaries dummy` | no | run with the checked-in dummy external-dictionary bundle (see below) |
| `#runLevel <LEVEL>` | no | the **run severity threshold** for this scenario — the weakest check level it evaluates (see *Check levels and the run threshold*) |

The verdict asserts both the rule's **execution state** and its violations:

| `expect=` | the rule must… | and… |
|---|---|---|
| `violation` | have **executed** | fired at least once |
| `noViolation` | have **executed** | not fired |
| `skipped` | **not** have executed | — |
| `executionError` | have **failed** — the engine reported `RuleExecutionStatus.ERROR` | — |

`skipped` is the deliberate *unmet-requirement* contract: a rule that cannot answer on this
dataset must skip, and the scenario exists to prove it. Before this verdict existed such
scenarios had to borrow `noViolation` — which asserted nothing, because a skipped rule reports
zero violations and is indistinguishable from one that ran and found nothing. That is how a
fixture rots: tighten a rule's requirements, or trim away the column one of them needs, and the
scenario keeps passing while exercising nothing. `violation` / `noViolation` now fail with the
engine's own skip reason when the rule does not run, and with its **error message** when the rule
errored.

#### The `executionError` verdict

A rule that **errors** is the same hole one step further along: it reports no real violations
either, so before this verdict existed an error scenario had to borrow `noViolation` — and passed
while exercising nothing, exactly as a skip scenario did. `executionError` is the positive form:
*this rule is supposed to fail on this fixture, and here is why.*

```
#!RuleTest
#test CDISC-CG0040 expect=executionError domain=AE
#expect-execution-error "use num(AGE)"
#note "AGE is Char in this submission, so `AGE > 5` is a rule defect (R3) and must ERROR"
dataset AE
col STUDYID type=Char
col AGE    type=Char
---
CDISC01 | 25
---
```

- **The rule must have been reached.** A rule that merely *skipped* does not satisfy the verdict —
  the failure names the skip reason and tells you to write `expect=skipped` instead.
- **`#expect-execution-error` is optional but strongly recommended.** Without it the scenario
  asserts only *that* the rule failed, so an unrelated breakage — a typo in the fixture, a
  `Match_Datasets` resolution failure, an NPE — keeps it green while the failure it was written to
  pin has silently gone away. The payload is matched case-sensitively with `String.contains`,
  because an error message is a sentence the engine may reword at the edges: pinning it whole makes
  every wording improvement a corpus-wide edit, pinning nothing tells two different failures apart
  not at all. A substring is the smallest thing that distinguishes them.
- The directive is **rejected on any other verdict** — dead text that reads like an assertion is
  worse than no assertion.
- **Ruling R7** (owner, 2026-09-13): *a rule that can error needs a `.cdt` scenario proving it
  errors.* See [`plans/done/PLAN-column-type-conformance.md`](../../../../../../../../../plans/done/PLAN-column-type-conformance.md).

##### ⛔⛔ `executionError` is NOT `severity=ERROR` — the names collide, the meanings are opposite

`ERROR` already occurs twice in this format and means a **check severity level** both times:

| spelling | what it is |
|---|---|
| `#expectViolationAt severity=ERROR` | the level a violation **fired at** — the rule *ran and found something* |
| `#runLevel ERROR` | the run's severity threshold — which levels are evaluated at all |
| `expect=executionError` | the rule **could not run**: it was reached and it failed |

The verdict is deliberately spelled with the longer `executionError` rather than `error` for this
reason. `#expectViolationAt severity=ERROR` and `#test … expect=…` can sit two lines apart, and a
verdict spelled `expect=error` beside them is the classic silent-disarming shape: the author writes
the word they mean, lands on the wrong concept, and gets a green test asserting something else.
`expect=error` is therefore **not accepted at all** — the parser rejects it and names
`executionError` in the failure, rather than guessing which one was meant.

The requirements a `skipped` scenario can be built on
(spec [Field: `Requirements`](../../../../../../../../../corej-rules/documentation/CORE-RULES-SPECIFICATION.md#field-requirements)):

| requirement | the scenario withholds | the rule skips because |
|---|---|---|
| `Requirements.Variables.All` | one listed column | **every** entry must be present |
| `Requirements.Variables.Any` | **all** listed columns — withholding one is not enough | the leg is unmet only when *every* entry is absent |
| `Requirements.Variables.None` | nothing — it **adds** a listed column | no listed entry may be present |
| `Requirements.Variables.All_Or_None` | **one** entry of a group, keeping the others | a group is unmet only when partly present — withholding the whole group is the all-absent case, which runs |
| `Requirements.Datasets` | the whole `dataset` block for that name | the run must carry it |
| a qualified `DATASET.VARIABLE` entry | the foreign `dataset` block, or that column in it | the foreign metadata decides the entry |

⚑⚑ **Field-name note, 2026-08-24.** A variable requirement used to be spelled
`Scope.Variables.Include` / `.Exclude`; it is now
`Requirements.Variables.All` / `.None`, with the new `.Any` facet
(`plans/done/PLAN-scope-requirements-split.md`, owner requirement #6). ✅ **The corpus has moved, and the
`#note` lines moved with it.** Measured 2026-08-24: `rules-src` and all 56 shipped packages carry
**zero** `Scope.Variables` blocks, the model no longer binds the field, and a corpus rule carrying
it fails to load (gate R1). The **425 `#note` lines, in 425 scenario files**, that said
*"`Scope.Variables.Include` gates the rule to SKIPPED"* were re-spelled to
`Requirements.Variables.All` in the same wave — one mechanical sweep, so the suite never described
a field that does not exist. ⚠ A `#note` is prose — nothing parses it — so no scenario's verdict
turns on which spelling it uses, and no verdict changed either when the corpus moved or when the
notes were swept.

⚠ **`Any` needs the whole set withheld.** A two-entry `Any` whose *first* entry is missing is
still satisfied by the second, so a scenario that removes only one column proves nothing about
the disjunction — it cannot tell a correct implementation from one that answers on entry 1.

A file may contain several `dataset` blocks (for cross-dataset rules); each is
registered in the resolver under its own name and the `domain=` one is the primary
table.

### Asserting where a rule fires

By default a scenario only checks *whether* the rule fires. The optional
`#expectViolationCount` / `#expectViolationAt` directives additionally pin
*where* it fires. They are purely additive — a scenario without them behaves
exactly as before. When `#expectViolationAt` lines are present the match is an
**exact set**: the fired locations must equal the declared ones (no missing, no
extra). Numeric values compare numerically, so `AESEQ=1` matches a `Num` cell
stored as `1.0`.

```
#!RuleTest
#test CDISC-CG0387 expect=violation domain=AE
#expectViolationCount 1
#expectViolationAt row=4 AESER=Maybe
dataset AE
col USUBJID type=Char
col AESEQ   type=Num
col AESER   type=Char
---
001 | 1 | Y
001 | 2 | N
002 | 1 |
003 | 1 | Maybe
```

Rows 1–2 are fine, row 3 is empty and is not flagged, row 4 fires.
⚠ **Never write those notes as `# …` on a data row.** `CdtLoader` recognises a
comment only when `#` is the *first* character of the line, so a trailing
`# row 4 — fires` becomes part of the last cell (`AESER` = `Maybe    # row 4 —
fires`) and the pin silently stops matching. Put the commentary outside the
block, or on its own `#`-leading line.

- **`#expectViolationCount <N>`** — the rule must fire exactly `N` times. Use it
  alone when you only care about the count, or alongside `#expectViolationAt`
  (then `N` must equal the number of `#expectViolationAt` lines).
- **`#expectViolationAt [row=<N>] [severity=<LEVEL>] [nameOnly=<COL> ...] [COL=value ...]`** —
  one fired location, repeatable. `row=N` is the **1-based** data-row number (the *N*th line
  under `---`); `severity=<LEVEL>` pins the check level the violation fired at (see
  *Check levels and the run threshold*); `nameOnly=<COL>` (repeatable, one column per token) pins
  that the violation **lists `COL` by name without a value** (see *Name-only columns* below).
  All three keys are **reserved** and are never column constraints. Each `COL=value` is matched
  against the violation's projected output variables first, then the primary table's column
  `COL` at the fired row — **unless the violation lists `COL` by name, in which case a value pin
  fails and the table is never read.**
  ⚠ That fallback means a pin **cannot see an exclusion**: when a rule's
  `Output_Variables` carries `"!COL"` (R-9.12, `Fix #354`) the projection has no
  `COL` key, so the checker quietly reads the primary table's `COL` cell
  instead — a physical-column pin still passes, and a pin on an excluded
  *engine virtual* (`variable_name`, `$id`, `DS.COL`, …) fails because there is
  no table column to fall back to; and on a **per-domain** rule it compares the
  wrong cell outright, because the row it indexes by is a column index (see the
  rule-class list below), not a data row. A green scenario is therefore not
  evidence that an exclusion took effect; assert it in a Java test on
  `violation.getValues().containsKey(...)` (pinned by
  `ExcludedOutputVariablePinFallbackTest` in `corej-ruletest`).

**Pick the addressing by rule class:**

- **Record-level (value-based) rules** — address by `row=` (optionally with
  identity pins like `USUBJID=…` / `<DOMAIN>SEQ=…`). The engine reports a real
  data row for these, and its raw columns carry their values.
- **Variable-metadata / dataset-level (per-domain) rules** — the engine's row is
  a column index or `0`, so **do not** use `row=`. Pin by the projected output
  variable instead, e.g. `#expectViolationAt variable_name=AEFOO`; a raw column such a
  finding projects is **name-only** — pin it `nameOnly=COL`, never `COL=value`.
- **Wildcard / template rules** (e.g. `*FL`, `ANLzzFN`) — a row can fire under
  several concrete expansions, producing multiple violations on the same row;
  an exact-set scenario must enumerate each. Prefer adopting location
  assertions on non-wildcard rules first.

Validation: `#expectViolationAt` and `#expectViolationCount ≥ 1` require
`expect=violation`; `#expectViolationCount 0` requires `expect=noViolation`.

#### Name-only columns

**A raw column's value is printed only on a record-level finding** (owner, 2026-10-04;
`<meta>/plans/PLAN-finding-values-record-level-only.md` V1–V8). A dataset verdict (a
presence-class rule such as `var_exists("AETERM") and not var_exists("AEDECOD")`, a
`record_count()` comparison, a Study rule), a Dataset-sensitivity rule evaluated row by row
(one finding at its first firing row) and a column-level per-variable finding all **list** their
raw columns — bare, `--`-resolved, SUPP-delivered or `DS.COL` — **by name without a value**
(`Violation.getNamedColumns()`): the finding is about the dataset or the variable, so row 0's
cell was never a fact about it. The finding's own values still print — a dataset-scope
`$binding`, `record_count`, `dataset_name`, the `variable_*` / `library_variable_*` / `define_*`
metadata. A **per-row** binding (e.g. `$end: AEENDTC`) is listed by name like a raw column —
pin it `nameOnly=$end` (owner Q-C, 2026-10-05). A Group finding is unchanged: its raw columns
report the group's distinct set (D94c).

```
#!RuleTest
#test CDISC-AD0366 expect=violation domain=ADSL
#expectViolationCount 1
#expectViolationAt nameOnly=RANDFL "$RANDFL_Y_COUNT"=1
```

- `nameOnly=COL` asserts the column is listed; a `COL=value` pin (the empty `COL=` included) on a
  listed column **fails** — the checker never falls back to the table cell for it, because that
  fallback is exactly what would have kept a `RANDFL=Y` pin green against row 1's cell after the
  engine stopped printing it. The failure message names the columns and the spelling to use.
- An **absent** column is in neither channel (it is omitted, as before), so neither `nameOnly=`
  nor `COL=value` can pin it.
- On a violation that lists anything by name, **no** `COL=value` pin is read from the table cell
  at its row — that row is no record of a non-record finding, so such a pin passed on the
  fixture's row, not on the finding (CDISC-AD0661's `DOSEDURU=HOURS`, re-spelled as `#note`).
  Only the row anchors `USUBJID` / `--SEQ` may still locate the firing row; say anything else in
  the scenario's `#note`.
- `-Dbackfill.locations=true` emits `nameOnly=` pins from the engine's list, so a back-fill
  cannot erase them (the same self-deleting shape the `$` and `severity=` pins guard against).

Most existing scenarios were back-filled with these directives by running the
factories with `-Dbackfill.locations=true` (which captures the locations the
engine currently produces; `-Dbackfill.dryRun=true` reports without writing).

### Check levels and the run threshold

A rule may state a different check **per severity level** — a `Check:` whose keys are `REJECT` /
`ERROR` / `WARNING` / `INFO` rather than a single condition (spec:
[Level-keyed `Check`](../../../../../../../../../corej-rules/documentation/CORE-RULES-SPECIFICATION.md#level-keyed-check)).
A rule with a plain `Check:` declares exactly one level, at its `Severity`, which is every rule in
the corpus today. Two directives address that:

- **`#runLevel <LEVEL>`** — the scenario's **run threshold**: the weakest level it evaluates.
  `Reject` / `Error` / `Warning` / `Info`, case-insensitively; at most one per file. It is the
  `.cdt` face of the CLI's `--severity-level` and the REST `CheckRunRequest.severityThreshold` —
  all three set the same value.
- **`#expectViolationAt … severity=<LEVEL>`** — pins the level a violation fired *at*. `severity`
  is a **reserved key**, like `row`: it is not a column constraint, and `severity=ERROR` never
  means "the column named `severity` holds ERROR". A pin counts as a pin, so
  `#expectViolationAt severity=ERROR` on its own is a complete directive.

⚠⚠ **A scenario that declares no `#runLevel` runs at the engine default, `Warning`** — so
`REJECT` + `ERROR` + `WARNING` evaluate and **`INFO` does not**. Every scenario in this suite is
therefore a default-threshold scenario, and a scenario that pins an `INFO` violation without
`#runLevel Info` pins a level that never ran. Levels below the threshold are not evaluated at all,
and a rule whose only level is below it is **skipped with a stated reason** — `expect=skipped`,
not `expect=noViolation`.

```
#!RuleTest
#test CDISC-CG0999 expect=violation domain=DM
#runLevel Info
#expectViolationCount 2
#expectViolationAt row=1 severity=ERROR
#expectViolationAt row=2 severity=INFO
```

⚑ Levels are evaluated strictest first and **first claim wins**: a row claimed by a stricter level
is never re-reported by a weaker one, so the two pins above are two *different* rows. Expecting the
same row at two levels is a scenario that can never pass.

⛔ **The threshold is a run option and nothing else.** No rule and no rule package may carry one —
the loader rejects every spelling of it. `#runLevel` configures *this scenario's run*, never the
rule.

### Real Define-XML sidecar (`#define-xml`)

The synthetic `#define` / `#define-include` channel serves the `define_*`
operand level from hand-authored key/value metadata — but it cannot serve the
**value-level** (`define_vlm_*`) accessors, which need a real where-clause
model. For those (rule type `Value Check against Define XML VLM`), and for any
define scenario that prefers production-faithful wiring, point at a small real
Define-XML file in the same directory:

```
#define-xml FDA-SD1229-define.xml
```

The runner parses the sidecar (`DefineXmlParser`) and wires **both** the
define-metadata provider (`DefineXmlMetadataProvider`) and the per-record VLM
resolver (`VlmResolver.from`) from the same parsed model — exactly the
production Define-XML path. Mutually exclusive with `#define` /
`#define-include` (both would compete for the define-provider slot); combines
freely with any library channel. The sidecar is not a scenario (no shebang),
so the factories never pick it up directly. See
`fda/FDA-SD1229/FDA-SD1229-define.xml` for a minimal ValueListDef fixture.

Note for the synthetic channel: `define_variable_name` (the usual "variable is
declared in the define" guard) reads the `name` key, so declare it explicitly:
`#define variable-metadata DM AGE name=AGE label="Age"`.

### Sidecars are confined (`SidecarResolver`)

`#library-include`, `#define-include` and `#define-xml` name a sidecar
**relative to the scenario's directory**, and the runner resolves it through
`SidecarResolver` (the rule-test harness), confined to a tree the host
chooses — the corpus passes its suites tree, the rule editor its checkout. A
payload is refused (a red scenario, `SIDECAR` / `PARSE`) when it:

- is empty, absolute or not a valid path;
- climbs out of the tree with `..` (climbing *inside* it is fine);
- passes through a symbolic link, a junction or another redirection on **any**
  component — even one that points back into the tree;
- differs in case from the file on disk, or is not a regular file.

A tree reached through a link of its own (a checkout under a linked
directory) still resolves its sidecars: both the tree and the scenario's
directory are compared as real paths. Messages name the payload and paths
relative to the tree, never an absolute path or file content. Every corpus
sidecar is a plain file name beside its scenario, so confinement changes
nothing for the corpus. `RuleTestCdt.load(Path)` without a resolver confines
`#library-include` / `#define-include` to the scenario's own directory.

### External dictionaries (`#dictionaries`)

Rules using the `valid_external_dictionary_*` operators need an external
dictionary (MedDRA, WHODrug, UNII, SNOMED, MedRT, Neoplasm, LOINC, ...). A
scenario opts in with:

```
#dictionaries dummy
```

`dummy` — the only supported value — loads the dummy bundle the host hands
the runner (`RunEnvironment.dictionariesDir`; the corpus passes its checked-in
`src/test/resources/dictionaries/*.json`, all types above) via
`RuntimeDictionaryProvider.loadDirectory` and hands it to the engine.
Scenario data must use terms/codes from those dummy files. Without the
directive no provider is supplied, so a dictionary-dependent rule **SKIPs**
via its `dictionary_available` gate — which makes an undirectived
`expect=noViolation` scenario pass vacuously. Always pair a `noViolation`
dictionary scenario with an `expect=violation` twin so a missing bundle is
caught (the twin would stop firing).

### Supplying CDISC-Library metadata

Rules that read CDISC-Library metadata (required/expected variables, column
order, codelists, variable metadata, dataset class, …) get it from an
in-memory provider built from `#library` directives — **no live Library or
define.xml is contacted**. Values are synthetic: a label-comparison rule can
assert `"My Label"` against `"Other Label"`.

```
#library standard=<id> version=<id>          # e.g. standard=sdtmig version=3-4
#library required-variables    DOMAIN VAR VAR ...
#library expected-variables    DOMAIN VAR VAR ...
#library column-order          DOMAIN VAR VAR ...
#library model-column-order    DOMAIN VAR VAR ...
#library custom-domain         DOMAIN [DOMAIN ...]
#library codelist-terms        CODELIST TERM TERM ...
#library published-ct-packages PKG [PKG ...]
#library standard-domains      DOMAIN [DOMAIN ...]
#library dataset-class         DOMAIN CLASSNAME
#library domain-variables      DOMAIN NAME:ROLE NAME:ROLE ...
#library model-variables       DOMAIN NAME:ROLE NAME:ROLE ...
#library model-class-variables CLASS NAME:ROLE NAME:ROLE ...
#library codelist-extensible    CODELIST true|false
#library codelist-term-mappings CODELIST SUBMITTED=Decoded SUBMITTED=Decoded ...
#library variable-metadata      DOMAIN VAR key=value key=value ...
#library dataset-metadata       DOMAIN key=value key=value ...
#library codelist-codes         DOMAIN VARIABLE TERM=Cxxxxxx TERM=Cxxxxxx ...
#library codelist-term-ccodes   CODELIST TERM=Cxxxxxx TERM=Cxxxxxx ...
#library codelist-meta          CODELIST ccode=Cxxxxxx pref="NCI Preferred Term"
#library codelist-attributes    PKG "ATTRIBUTE" VALUE VALUE ...
```

The last four keyword-payload kinds (`codelist-extensible`,
`codelist-term-mappings`, `variable-metadata`, `dataset-metadata`) plus the
2026-09 codelist channels (`codelist-term-ccodes`, `codelist-meta`) are the
most recent additions.

- **`standard-domains`** declares the library's canonical dataset (domain)
  names — what `MetadataProvider.getStandardDatasetNames()` serves. A rule
  whose `Expansion:` directive sets `known_domain_only: true` reads exactly
  this list to decide whether a captured prefix is a real parent domain
  (`AESEQ` → `AE` yes, `ASEQ` → `A` no). **Declaring the kind at all is what
  makes the filter decidable**: with no `standard-domains` line the provider
  serves `null`, the filter cannot be applied, and the rule is *skipped with
  a reason* rather than expanded over ADaM's own `ASEQ` / `SRCSEQ` / `RECSEQ`.
  An explicitly empty declaration means "the library attests no domains",
  which is a different statement from "unknown".

- **`variable-metadata`** keys are passed through verbatim; the engine reads
  these canonical keys: `label`, `simpleDatatype` (note: *not* `datatype`),
  `core`, `role`, `ordinal`, `codelist`. Quote values with spaces:
  `label="Reported Term"`.
- **`dataset-metadata`** stores arbitrary `key=value` pairs for a domain;
  `dataset-class DOMAIN X` is sugar for `dataset-metadata DOMAIN className=X`.
- **`codelist-extensible`** defaults to `true` for any codelist not mentioned,
  so you only need it to assert the non-extensible (`false`) case.

- **`codelist-codes`** declares the codelist bound to *one variable* as a
  **submission value → NCI concept id** map — what
  `MetadataProvider.getCodelistCodeMap(domain, variable)` serves, and the only
  input of the `library_variable_code_pair_matches` accessor (`FDA-CT2003` /
  `PMDA-CT2003`). Paired `--TESTCD` / `--TEST` codelist terms share their concept
  id in the CDISC CT, which is exactly what the rule compares, so a code/decode
  scenario declares **both sides**:

  ```
  #library codelist-codes LB LBTESTCD ALB=C64431 BILI=C64433
  #library codelist-codes LB LBTEST   Albumin=C64431 Bilirubin=C64433
  ```

  Term keys are matched against the cell **verbatim** (case-sensitively, decodes
  included); the domain and variable keys fold to upper case like every other
  `#library` kind. Declaring the same (domain, variable) twice replaces the map.

  ⚠ **Both sides are required and the fire is symmetric.** With only the code
  side declared, the partner's map is empty, the accessor returns *no decision*
  and the rule is silent — a fixture that declares one side is vacuous. With both
  declared, a mismatching row fires **twice**: once with `variable_name=LBTESTCD`
  (partner found by stripping the `CD` suffix) and once with `variable_name=LBTEST`
  (partner found by the unique-concept-set fallback in
  `ExprCompiler.resolvePartner`). Pin both, or the exact-set location check fails.

  An undeclared variable has an empty `codelist-codes` map, so CT2003 never
  fires — that is also why an `expect=skipped` is *not* reachable for it: its
  skip gate is a **null Library provider**, and every `fda` / `pmda` scenario
  must declare `#library standard=…`.

- **`codelist-term-ccodes`** declares one codelist's **term submission value →
  NCI concept id ("C-code")** map — the term-conceptId channel of
  `MetadataProvider.getCodelist`, which is what
  `codelist_terms(level="term", returntype="code")` resolves through
  (`CDISC-CG0001`). Same shape as `codelist-term-mappings`:
  codelist key folds to upper case, term keys are verbatim.

  ⚠⚠ **Not `codelist-codes`**, despite the near-identical name: that kind is
  *(domain, variable)*-scoped and feeds only `getCodelistCodeMap` / CT2003.
  The two are not interchangeable and must not be conflated.

  ⚠ **Route each representation down its own channel.** `codelist-terms`
  carries real submission values (`AE`, `LB`), `codelist-term-ccodes` carries
  the C-codes. Feeding C-codes through `codelist-terms` is the self-disarming
  fixture shape that kept CDISC-CG0001 green while production was
  wrong (PLAN-codelist-terms-returntype.md §2.3) — the suites now discriminate.

  A codelist declared on none of the codelist channels has **no**
  `getCodelist` view: every `codelist_terms` shape other than
  `(level="term", returntype="value")` then degrades to an unusable
  answer and the rule SKIPs (the engine's honest-degradation contract). A codelist declared with terms but no ccodes yields an **empty**
  `(term, code)` projection — also a SKIP — exactly like a real library that
  does not model the field.

- **`codelist-meta`** declares the codelist's **own** attributes: `ccode=` its
  NCI concept id, `pref=` its NCI preferred term. The codelist's own submission
  value is the directive key itself. These feed the
  `codelist_terms(level="codelist", ...)` shapes.

- **`codelist-attributes`** declares one **CT package**'s values of one
  attribute — what `MetadataProvider.getCodelistAttribute(pkg, attribute)`
  serves, and the only input of the `get_codelist_attributes` function
  (`CDISC-CG0288`, PLAN-binding-expressions). The package id is the one the
  function derives per row from the target and version values
  (`TSVCDREF = "CDISC CT"`, `TSVCDVER = 2024-09-27` → `sdtmct-2024-09-27`
  under an SDTM standard); quote an attribute with a space:

  ```
  #library codelist-attributes sdtmct-2024-09-27 "Term CCODE" C49488 C66731
  ```

  ⚠ An undeclared package or attribute answers an **empty** list, and the
  function refuses an empty answer: the rule is `skipped`, never silently
  passed. So — unlike `codelist-codes` — `expect=skipped` IS reachable here
  with a Library present, and `CDISC-CG0288` pins both SKIP arms: no `#library`
  line at all (no Library provider) and a `#library` that lacks the package the
  rows name.

- **`model-class-variables`** (EC-85) declares the SDTM model's variable table
  for *one general observation **class*** — what
  `MetadataProvider.getModelVariablesForClass(class)` serves, and the input of a
  `get_model_filtered_variables(…, model_class=…)` call. The map
  is **class-keyed** (`EVENTS`, `INTERVENTIONS`, `FINDINGS`, `FINDINGS ABOUT`,
  case-folded) where `model-variables` is **domain-keyed**; the two never read
  each other. Names are declared **unsubstituted** (`--TERM`) exactly like
  `model-variables`, and the function substitutes the *scenario dataset's*
  prefix — so an `EVENTS` table asked from `BW` answers `BWTERM`. A
  `FINDINGS ABOUT` table must be declared in full (the production resolver's
  FINDINGS splice is not replayed by the harness). Declaring a class twice
  replaces its list. A class the scenario does **not** declare is served empty,
  which the function turns into *library not available* — the rule is
  **skipped**, never evaluated against an empty set — so a class-selecting rule
  with no `model-class-variables` line pins `expect=skipped`:

  ```
  #library model-column-order      BW STUDYID DOMAIN USUBJID BWSEQ BWTESTCD BWORRES BWDTC
  #library model-class-variables   EVENTS        --TERM:Topic "--DECOD:Synonym Qualifier"
  #library model-class-variables   INTERVENTIONS --TRT:Topic "--DOSE:Record Qualifier"
  ```

A directive value that contains whitespace, `=`, or `"` must be quoted, e.g.
`#library variable-metadata LB LBORRES label="Lab Result" core=Exp` or
`"--DECOD:Synonym Qualifier"`.

### YAML sidecar (`#library-include`)

For larger metadata payloads, point at a YAML file in the **same directory**
as the `.cdt` (resolved as a classpath sibling when loaded from resources):

```
#library-include CDISC-CG0219-library.yaml
```

The sidecar mirrors the directive grammar one-to-one (every node optional):

```yaml
standard: sdtmig
version: "3-4"                 # quote so it stays a string
published-ct-packages: [ sdtmct-2023-12-13 ]
custom-domains: [ ZZ ]
domains:
  LB:
    required-variables: [ STUDYID, DOMAIN, USUBJID, LBSEQ ]
    expected-variables: [ LBTEST ]
    column-order:       [ STUDYID, DOMAIN, USUBJID ]
    model-column-order: [ STUDYID, DOMAIN ]
    dataset-class: FINDINGS
    dataset-metadata: { structure: "One record per lab test per visit" }
    variables:        [ { name: LBORRES, role: Result, label: "My Label" } ]
    model-variables:  [ { name: "--DTC", role: Timing } ]
    variable-metadata:
      LBORRES: { label: "My Label", simpleDatatype: Char, core: Exp }
codelists:
  C66742:
    terms: [ Y, N, U ]
    extensible: false
    term-mappings: { Y: "Yes", N: "No" }
    term-ccodes:   { Y: C49488, N: C49487 }   # mirrors #library codelist-term-ccodes
    ccode: C66742                             # mirrors #library codelist-meta ccode=
    pref: "No Yes Response"                   # mirrors #library codelist-meta pref=
```

Unknown keys are rejected (typos fail loudly). When a scenario has both a
sidecar and inline `#library` lines, the **sidecar is applied first and inline
directives override it per key, regardless of line order**. `#library-include`
is input-only: writing a scenario back out (`RuleTestCdt.toString`) flattens it
to inline `#library` directives.

### Running against real CDISC Library metadata (`#library-ref`)

The two modes above build a *synthetic* in-memory provider. Alternatively, a
scenario can run against the **real CDISC Library metadata, as held in our
unified metadata store**, for a given standard and version — useful for checking a rule against actual published metadata rather
than hand-curated values.

```
#!RuleTest
#test CDISC-AD0200 expect=violation domain=ADSL
#note "AGE declared Char but real ADaM-IG 1-3 Library has Num -> violation"
#library-ref standard=adamig version=1-3
dataset ADSL
col STUDYID type=Char
col USUBJID type=Char
col AGE     type=Char label="Age"
---
CDISCPILOT01 | 01-701-1015 | 56
```

Grammar (may span several `#library-ref` lines; `ct=` repeats and accumulates):

```
#library-ref standard=<id> version=<id> [ct=<pkgId> ...] [substandard=<id>]
```

- `standard` and `version` are **required**; `standard`, `version` and `ct` are
  the only inputs that reach the Library.
- `substandard` is accepted but currently inert (forward-compat); the rule is
  selected by `coreId`, so it has no effect.
- `useCase` / `defineVersion` are **not** accepted — neither is a Library input.
- **Mutually exclusive** with `#library` / `#library-include` (mixing is a parse
  error).
- The study side is empty, so the verdict is judged against the real Library
  products (IG / Model / pinned CT) plus the scenario's own declared columns and
  data — no define.xml enrichment.

**Configuration comes from the environment, never the file** (no secrets or
machine-local paths are committed):

| Variable | System property | Purpose | Default |
|----------|-----------------|---------|---------|
| `CDISC_METADATA_STORE` | `cdisc.metadata.store` | our unified metadata store (one zip); used only when it names an existing file | the store `MetadataStoreFixture` seeds for the build (`target/metadata-store/store.zip`, from the pickle corpus `CDISC_PICKLE_CACHE_DIR` or `-Dcdisc.pickle.cache.dir` names) |

No API key is involved: the resolver (`ScenarioLibraryResolver`) builds the provider from the
store, which is what the product evaluates against. `ScenarioLibrarySupport` picks the store
**once per JVM** — the configured one when it names an existing file, else the fixture store — and
hands it to `ScenarioLibraryResolver.resolve(LibraryRef, StoreMetadataProviderFactory)`
explicitly; nothing sets a system property. ⚠ Until 2026-09-28 the corpus build set neither
variable, so both `#library-ref` scenarios skipped on every run
(`PLAN-cdisc-library-access-cleanup` D1).

**Skip behaviour** — a `#library-ref` scenario is *skipped* (not failed) at one of two gates:

1. **Before the rule runs** (`ScenarioLibrarySupport.resolveOrSkip`): there is no store (none
   configured, and the fixture cannot seed one because neither `CDISC_PICKLE_CACHE_DIR` nor
   `-Dcdisc.pickle.cache.dir` names the pickle corpus); or the
   store cannot be opened; or it does not hold the referenced product (IG / declared ADaM
   product) — `resolve()` answers empty. The skip message names which. The product is checked
   **before** the `ct=` pins below, so a store without the product skips rather than fails.
2. **After the rule ran** (`ScenarioLibrarySupport.skipRefIfNotExecuted`): the provider was built
   but the rule reported `SKIPPED`/`ERROR` (not applied to an `expect=skipped` scenario, whose
   skip is the contract under test).

A **missing CT package is neither**: the store factory substitutes an empty package for it (a
`WARNING` in the log; an ADaM ref's `sdtmct` fallback is dropped instead) and the rule runs
against no terms of that package. The harness therefore
**fails** a scenario whose `ct=` pins a package the store does not hold, rather than letting it
pass against empty CT. A store of another FORMAT version also fails the scenario instead of
skipping it. And `LibraryRefScenariosExecuteTest` fails the build if any `#library-ref` scenario
skips while the fixture store exists.

In CI, which seeds the fixture store, both scenarios therefore RUN. Any CT-dependent scenario
must pin a specific `ct=` package id for reproducibility.

Round-trips: writing a `#library-ref` scenario back out
(`RuleTestCdt.toString`) re-emits the `#library-ref` line verbatim.

### Example: a Library variable-label check

`adam/CDISC-AD0709/CDISC-AD0709-invalid-ADSL.cdt` — `AGE` carries the wrong label
versus the Library's `"Age"`, so the rule fires:

```
#!RuleTest
#test CDISC-AD0709 expect=violation domain=ADSL
#note "AGE label 'Wrong Label' != Library label 'Age' -> violation"
#library standard=adamig version=1-3
#library variable-metadata ADSL AGE label="Age" simpleDatatype=Num
dataset ADSL
col STUDYID type=Char label="Study Identifier"
col USUBJID type=Char label="Unique Subject Identifier"
col AGE     type=Num  label="Wrong Label"
---
CDISCPILOT01 | 01-701-1015 | 56
```

## Full example: multi-type, with labels and formats

```
# CDISC-AD0361 invalid: ASTDT > AENDT should fire
dataset ADLBC class=BDS label="Lab Chemistry"

col STUDYID  type=Char  length=15  label="Study Identifier"
col USUBJID  type=Char  length=20  label="Unique Subject Identifier"
col PARAMCD  type=Char  length=8   label="Parameter Code"
col ASTDT    type=Date              label="Analysis Start Date"
col AENDT    type=Date              label="Analysis End Date"
---
S01 | 01-001 | ALB | 2020-03-15 | 2020-02-01
S01 | 01-001 | ALB | 2020-01-10 | 2020-01-20
```

At a glance the reviewer sees:
- Row 1: start 2020-03-15 > end 2020-02-01 → should fire
- Row 2: start 2020-01-10 < end 2020-01-20 → should not fire

## Forward compatibility

Unknown attributes on `dataset` or `col` lines are silently ignored.
This lets you add new attributes to `.cdt` files without breaking the
existing parser:

```
col RACE type=Char origin=Assigned mandatory=true codelist=C74457
```

Today, only `type`, `label`, `length`, `format`, and `codelist` are
interpreted. `origin` and `mandatory` are ignored but preserved in the file
as documentation.

## Error messages

Parse errors include file path and line number:

```
net/cumba/corej/core/ruletestsuites/cdisc/CDISC-AD0117/invalid-ADLBC.cdt:5:
expected 'col ...' or '---' separator, got: colPARAMCD type=Char
```

## Loader API

See `net.cumba.corej.ruletest.cdt.CdtLoader`:

```java
// From classpath
OverlayDataTable table = CdtLoader.loadResource(
    "net/cumba/corej/core/ruletestsuites/cdisc/CDISC-AD0117/CDISC-AD0117-valid-ADLBC.cdt");

// From a String (useful for inline test cases)
OverlayDataTable table = CdtLoader.parse(content, "sourceName");
```

The loader returns `net.cumba.datatable.impl.support.OverlayDataTable` (from `cumba-datatable-impl`) and
needs nothing beyond that.
