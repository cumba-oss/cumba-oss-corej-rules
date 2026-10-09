# External medical dictionaries

This directory holds coreJ's **house-format dictionary files**. Each
`<type>.json` reduces every dictionary conformance check the engine
supports to a map lookup.

> ⚠ **The files in *this* directory are the small dummy fixtures the
> test-suite is pinned to — not shippable dictionary data.** They exist
> so the 98 dictionary rules execute and the `.cdt` scenarios discriminate
> without any licensed data present.
>
> **Adding, removing or renaming a file here reddens the build**
> immediately: `DictionaryValidationTest` asserts the exact filename set
> (`.json` only — a `README.md` is filtered out). *Changing the content*
> of a file has a narrower, per-file blast radius — ~30 pinned rule
> verdicts and the **197** `.cdt` fixtures carrying `#dictionaries dummy`,
> of which only the ones exercising that dictionary are affected.
>
> Real dictionary data belongs in a **separate, operator-controlled
> directory** — see [Pointing the engine at real data](#4-pointing-the-engine-at-real-data).

---

## 1. The house format

One JSON document per dictionary type. All top-level keys are optional;
an absent or mis-shaped key degrades to empty **silently**
(`ValueMapDictionary.parse`, `.../metadata/ValueMapDictionary.java:106`).

```jsonc
{
  "type": "medrt",                       // dictionary type; falls back to the filename stem
  "levels": {                            // membership + case checks
    "MEDRT":   { "CYCLOOXYGENASE INHIBITORS": "Cyclooxygenase Inhibitors" },
    "MEDRTCD": { "N0000000160": "N0000000160" }
  },
  "hierarchy": {                         // term -> ALL its ancestors (flattened, not just parent)
    "Headaches NEC": ["Headaches", "Nervous system disorders"]
  },
  "hierarchy_by_level": {                // level -> term -> ancestor level -> ancestor terms (meddra)
    "PT":   { "Headache":  { "HLT": ["Headaches NEC"], "HLGT": ["Headaches"], "SOC": ["Nervous system disorders"] } },
    "PTCD": { "10019211":  { "HLTCD": ["10019231"], "HLGTCD": ["10019233"], "SOCCD": ["10029205"] } },
    "HLT":  { "Headaches NEC": { "HLGT": ["Headaches"], "SOC": ["Nervous system disorders"] } }
  },
  "pairs": {                             // registry -> code -> decode
    "medrt": { "N0000000160": "Cyclooxygenase Inhibitors" }
  },
  "attributes": {                        // attribute -> term -> value
    "neoplasm": { "ADENOMA, BENIGN": "BENIGN" }
  }
}
```

> ⚠ **The shipped `medrt.json` fixture pairs `N0000000181` with
> `"Cyclooxygenase Inhibitors"`. That is wrong.** In real MED-RT
> `N0000000181` is *Angiotensin-converting Enzyme Inhibitors [MoA]*;
> Cyclooxygenase Inhibitors is `N0000000160`. Surfaced here, not fixed —
> the fixture is frozen and correcting it is a separate change. Note also
> that real MED-RT names carry `[MoA]` / `[EPC]` / `[PE]` / `[PK]` type
> suffixes that the fixture strips.

| Section      | Key                  | Value                       | Serves |
|--------------|----------------------|-----------------------------|--------|
| `levels`     | term **upper-cased** | the term in *preferred case* | `valid_external_dictionary_value`, `valid_external_dictionary_code` |
| `hierarchy`  | term, verbatim       | list of ancestor terms, verbatim | `valid_external_dictionary_hierarchy` for every type except MedDRA; a MedDRA file keeps it only for older engine releases sharing the directory |
| `hierarchy_by_level` | level → term, verbatim | ancestor level → list of ancestor terms, verbatim | `valid_external_dictionary_hierarchy` (level-scoped) |
| `pairs`      | registry → code, verbatim | decode, verbatim       | `valid_external_dictionary_code_term_pair`, `dictionary_has_decode` |
| `attributes` | attribute → term, verbatim | value, verbatim        | reached only by the pair/decode fall-through scan |

`levels` keys are upper-cased **at load**; every other section keeps a
verbatim map *and* a case-folded twin (`hierarchy_by_level` builds its twin
once, on the first `case_sensitive=false` lookup). The terms of `levels` and
`hierarchy_by_level` are interned through one map at load, so a name that
recurs (a SOC on every PT row) is held once.

#### `hierarchy` vs `hierarchy_by_level` — the level of the variable read

A MedDRA name can sit at more than one level — a PT name can also be an HLT
and an LLT name — so the bare-name `hierarchy` map, which files every level
under one key, can answer with another level's ancestors (a wrong SOC
passes). `hierarchy_by_level` keys the **primary** path per level: a PT
(name, and its code under `PTCD`) → its one primary HLT, HLGT and SOC; an HLT /
HLGT → the set of HLGTs / SOCs it reaches on primary rows; codes sit at their
own `*CD` levels. **No LLT level is written**: it would be about three
quarters of the section and no shipped rule reads `--LLT` — a hierarchy rule
reading `--LLT` / `--LLTCD` reports SKIPPED. ⚠ When a rule comes to
read `--LLT`, the converter must add the level back (an LLT name and code → its
PT, `llt.asc` field 2): a PT name can also be an LLT name, which the bare-name
map cannot tell apart.
`valid_external_dictionary_hierarchy` reads it at the levels of **both**
columns it names, derived from their names at load (`--DECOD` → `PT`,
`--PTCD` → `PTCD`, `--LLT`, `--HLT`, `--HLGT`, `--SOC` and their `CD` forms);
`--BODSYS` is deliberately **not** mapped (it may carry a secondary SOC).

⭐ **No legacy arm for MedDRA**: the MedDRA hierarchy is read from
`hierarchy_by_level` only — there is no fallback to the bare-name map.

> ⚠ **Upgrading: re-install (re-convert) your MedDRA dictionary after this
> release.** A MedDRA file converted by an earlier release has no
> `hierarchy_by_level`. It still installs — the installer reports a
> **WARNING** for a meddra file missing any of the six levels PT, PTCD, HLT,
> HLTCD, HLGT, HLGTCD in `hierarchy_by_level` and writes it anyway — and it
> still loads: its membership and pair rules keep answering. But it is
> reported once at load (WARNING) and in the run's `Dictionary_Basis`
> (`meddra 27.0 (no hierarchy_by_level — re-convert it to answer the
> hierarchy rules)`), and the hierarchy rules **`CDISC-CG0460` / `CG0461`
> report SKIPPED** with that reason until the dictionary is re-converted.
> `MedDraConverter` writes both sections; the bare-name `hierarchy` stays in
> the file for older engine releases sharing the directory.

A hierarchy rule whose child level the section does not carry (`--LLT` /
`--LLTCD` — no converter writes the LLT level) also reports SKIPPED, with the
reason. A call naming a column that maps to no MedDRA level (`--BODSYS`, a
`$`-binding, a computed operand) fails to load. ⚠ The level-scoped read, that
load error and the SKIP are **MedDRA-only**: every other type (`whodrug`, …)
answers `valid_external_dictionary_hierarchy` from its bare-name `hierarchy`
map at any column, unchanged by this release. The dummy fixture here carries the section.
The registry *name* is a
preference, not a constraint — on a miss both pair operations scan every
`pairs` registry and every `attributes` map of that dictionary.

### 1.1 The preferred-case contract — a generator MUST satisfy this

Enforced by `DictionaryValidationTest.caseContractViolations`
(`.../DictionaryValidationTest.java:627`):

1. **`levels`** — each key is exactly `upper(value)`, **and** a term has
   **one** preferred form *within each level*. Two levels may disagree on
   case (owner ruling, Fix batch A4): WHO writes B3 drug names upper-case
   but ATC texts mixed-case, and the engine only ever consults the level
   named by `dictionary_term_type`, so a cross-level clause had no engine
   backing — and made every real WHODrug distribution uninstallable.
2. **`hierarchy`** — every key *and every ancestor* must resolve to a
   term in some level, written exactly as some level publishes it.
   **`hierarchy_by_level`** is held to its **own** levels: a key must be a
   term of the level it is filed under, an ancestor a term of the level it
   is filed as, each written as that level publishes it
   (`HouseFormatValidator.caseContractViolations`).
3. **`pairs` / `attributes`** — any key or value that *is* a level term
   must be written as some level publishes it. Values that are not terms
   (the NEOPLASM `BENIGN`/`MALIGNANT` classes) are unconstrained.

> ⛔ **This contract is a *consistency* audit, not a completeness one.**
> It has no non-emptiness, cardinality or required-level assertion:
> `"levels": {}` yields zero violations. A structurally empty file still
> makes `isAvailable(type)` return **true**, which bypasses the SKIP
> safety net entirely — 84 membership rules then flood false violations
> and 12 pair/decode rules go vacuously green. Never treat a green
> contract check as proof a dictionary is usable.

### 1.2 ⚠ Case sensitivity — 79 of 98 rules compare case-sensitively

The engine default is **case-sensitive**
(`caseSensitive = !Boolean.FALSE.equals(op.getCaseSensitive())`). Only
**19** rules set `case_sensitive: false`, and all 19 are MedDRA `_value`.
The other 79 — 12 explicit `true` plus 67 with no flag at all — take the
sensitive path, and 17 of those sit at *name* levels (UNII `SRS` ×9,
SNOMED ×3, MED-RT ×3, WHODrug `PT`/`ATC` ×2).

**Every `levels` value must be the vendor's string verbatim. Never
normalise case.** Title-casing the FDA `DISPLAY_NAME` when building
`unii.json` makes nine rules fire on every row.

### 1.3 Term types the shipped corpus requires

Census over `lib/corej-rules/rules/*.json` and
`rules-src/checks/**/*.yaml` — the two populations name the same
**98 rules** (CDISC 27, FDA 41, PMDA 30). A type with no file loaded is
*unavailable*: its rules **SKIP** rather than false-pass.

| `type`     | required `levels` keys                                                   | other sections | rules |
|------------|--------------------------------------------------------------------------|---|-------|
| `meddra`   | `PT` `PTCD` `LLT` `LLTCD` `HLT` `HLTCD` `HLGT` `HLGTCD` `SOC` `SOCCD` | `hierarchy`, keyed at **HLT + HLGT only**, each carrying its SOC; `hierarchy_by_level` (PT / HLT / HLGT names and codes → primary path) — required for the hierarchy rules (`PT` `PTCD` `HLT` `HLTCD` `HLGT` `HLGTCD`; a missing level is an install warning, and the hierarchy rules reading it SKIP) | 51 |
| `unii`     | `UNII` `SRS`                                                             | `pairs.unii` | 24 |
| `snomed`   | `SNOMED` `SNOMEDCD`                                                      | `pairs.snomed` | 8 |
| `medrt`    | `MEDRT` `MEDRTCD`                                                        | `pairs.medrt` | 8 |
| `whodrug`  | `PT` `ATC` `ATCCD`                                                       | `pairs` keyed by **reported drug names** | 4 |
| `loinc`    | `LOINC`                                                                  | `pairs.loinc` (licence-required, see §5) | 2 |
| `neoplasm` | none — emit no `levels`                                                  | `attributes.neoplasm` | 1 |

⚠ **13 of the 98 rules have no `.cdt` fixture at all**, including both
LOINC rules — the converter work is least covered exactly where it is
riskiest. (`CDISC-CG0096` left this list in `PLAN-dictionary-seeder`
Phase 7b, when the dummy `whodrug.json` gained a `pairs.whodrug` registry
and the rule its first fixtures.)

> **CDISC Controlled Terminology is *not* handled here.** CT flows through
> the CDISC Library metadata path and `ExternalCodeList`, behind its own
> operations and its own skip gate. The corpus names exactly seven
> dictionary types and `cdiscct` is not among them, so a `cdiscct.json`
> would load cleanly and answer nothing.

---

## 2. Where to download

### 2.1 Downloaded by the installer — no credentials

| Dictionary | Source | Artefact |
|---|---|---|
| **MED-RT** | `https://evs.nci.nih.gov/ftp1/MED-RT/` | `MEDRT.txt` (flat `name⇥NUI⇥MED-RT`, ~220 KB) or `Core_MEDRT_DTS.zip` → `Core_MEDRT_<date>_DTS.xml` |
| **UNII** | `https://precision.fda.gov/uniisearch/archive` | `UNII_Data.zip` → `UNII_Records_<date>.txt` (TSV; col 0 `UNII`, col 1 `DISPLAY_NAME`; 171 912 rows in the 4 Aug 2026 release) |
| **LOINC** | release API, free account | `Loinc.csv` (or `LoincTable/Loinc.csv`); col 0 `LOINC_NUM`, col 8 `VersionLastChanged` |
| **neoplasm** | `https://evs.nci.nih.gov/ftp1/CDISC/SEND/` | `SEND Terminology.txt`, codelist **`Neoplasm Type` (C88025)** — 310 terms in the **2026-03-27** release (140 `, BENIGN` + 170 `, MALIGNANT`) |

The NCI EVS site is a single-page app; it exposes a listing API a tool can drive:

```bash
curl -sG --data-urlencode 'folder=MED-RT/' https://evs.nci.nih.gov/ftp1/folder
curl -sLO 'https://evs.nci.nih.gov/ftp1/MED-RT/MEDRT.txt'
```

⚠ A 200 response of ~2 873 bytes of HTML means **the file does not exist** —
the SPA shell is served for any unmatched path. ⚠ `HEAD` on
`precision.fda.gov` returns 403; use `GET`.

### 2.2 Free, but account-gated

| Dictionary | Access |
|---|---|
| **LOINC** | Free account at <https://loinc.org>. Release API: `GET https://loinc.regenstrief.org/api/v1/Loinc` → `version`, `downloadUrl`, `downloadMD5Hash`; `GET /Loinc/Download`. HTTP Basic auth. |
| **SNOMED CT** | Free tier is the **Global Patient Set**, registration required at <https://www.snomed.org/gps>. Full editions need a SNOMED International Affiliate licence or an NLM UMLS account (free in Member territories, including the US). |

### 2.3 Commercial — licence holders supply their own files

coreJ ships **no** MedDRA or WHODrug data and never will.

| Dictionary | Licensor | Expected layout |
|---|---|---|
| **MedDRA** | MSSO — <https://www.meddra.org> | Directory of `$`-delimited ASCII: `llt.asc pt.asc hlt.asc hlgt.asc soc.asc hlt_pt.asc hlgt_hlt.asc soc_hlgt.asc` (+ `meddra_release.asc`). Field 0 = code, field 1 = term; `llt.asc[2]` = PT code, `pt.asc[3]` = SOC code. |
| **WHODrug** | UMC — <https://who-umc.org> | B3 fixed-width `DD.txt` / `DDA.txt` / `INA.txt` (+ `version.txt`), or the C3 CSV set. `DD` `[0:6]` = drug record no, `[30:]` = name; `DDA` `[12:19]` = ATC code; `INA` `[0:7]` = ATC code, `[7]` = level, `[8:]` = text. |

⚠ For WHODrug, `ATCCD` must come from **`INA.txt`** (the full ATC index),
not `DDA.txt` (only the codes that licensee's drugs use), and `DD.txt`
must be filtered to preferred records or `--DECOD` accepts trade names.

---

## 3. Conversion

Every source needs converting — no authority publishes the house format,
and no off-the-shelf converter exists. The converters live in the
dictionary installer; see
**[`plans/done/PLAN-dictionary-seeder.md`](../../../plans/done/PLAN-dictionary-seeder.md)**
for the design and delivery state.

```bash
# download + convert every freely available dictionary
CdiscValidate --install-dictionaries --dictionaries-dir ./dictionaries-data

# convert a licensed distribution you already hold
CdiscValidate --install-dictionaries \
              --meddra  /path/to/meddra/MedAscii \
              --whodrug /path/to/whodrug/B3 \
              --dictionaries-dir ./dictionaries-data
```

The installer validates its own output before writing and records the
detected **version** per dictionary (MedDRA `meddra_release.asc` field 0;
WHODrug `version.txt` as `upper(v[-5:-2]) + "_20" + v[-2:]`, e.g.
`SEP_2020`; MED-RT `//namespace/version`; UNII the date token in the file
name; LOINC `max(VersionLastChanged)`).

> Those versions are **reported**, in the six `*_Version` conformance
> fields. They are **not** checked against a define.xml
> `ExternalCodeList/@Version` — no such comparison exists in the engine.

---

## 4. Pointing the engine at real data

Resolution order for the dictionary directory:

1. `--dictionaries-dir <path>` on the CLI
2. `COREJ_DICTIONARIES_DIR` environment variable
3. `-Dcorej.dictionariesDir=<path>` system property
4. `./dictionaries` relative to the process working directory (default)

The CLI flag is carried into the run directly (as
`StudyValidationParams.dictionariesDir()`, the resolver's explicit
argument) — **not** via the system property, which sits *below* the
environment variable. That ordering matters in containers: every Docker
image sets `COREJ_DICTIONARIES_DIR`, and the flag must still win there.
The system property remains the lowest *configured* tier, for operators
and embedding callers that set it themselves.

Within that directory, dictionaries are stored **per type and per version** —
`<dictionaries-dir>/<type>/<version>/` — so several MedDRA or WHODrug
releases can sit side by side.

⛔ **A dictionary is used only when something tells coreJ which version to
use.** Your `--<type>-version` option wins; otherwise the define.xml
`ExternalCodeList/@Version`; otherwise **the rules skip**. There is no
fallback, and *the number of installed versions is irrelevant* — one
installed version behaves exactly like ten. Nothing is ever inferred, so
the version named in the report is always one a human chose.

That is deliberate: installing a second MedDRA release must never silently
change the findings for a study that validated yesterday, and validating
against a version the study was not coded in produces findings that are
simply wrong in both directions — terms retired since coding read as
violations, terms that did not yet exist read as valid.

Every distribution ships the **resolution path and an empty store** (D12:
no dictionary data is ever redistributed). The three dist bundles set
`corej.dictionariesDir = ${env:COREJ_DICTIONARIES_DIR:-${bootstrap.dir}/dictionaries}`
in their `corej-bootstrap.conf` `[properties]` — CWD-independent, with the
env variable still winning — and ship that `dictionaries/` directory (README
only) at the bundle root; the Docker images set `COREJ_DICTIONARIES_DIR` to
a writable, persistable location (CLI image: `/app/dictionaries`; REST
images: `/app/data/dictionaries` on the data volume; editor images:
`/data/dictionaries`). Until an operator installs, a fresh deployment still
**SKIPs all 98 dictionary rules — loudly**, per §4.2.

The CLI's `--meddra` `--whodrug` `--loinc` `--medrt` `--unii` `--snomed`
`--neoplasm` options are raw-distribution *installer inputs* — meaningful
only with `--install-dictionaries`; in a validate run they are accepted
and reported in the "ignoring unsupported options" note (Python-CLI
compatible). The SNOMED trio `--snomed-version` `--snomed-edition`
`--snomed-url` stays accepted-but-ignored compatibility only
(`CdiscValidate`, `compatSink`); SNOMED's real selection option is
`--snomed-version-select`.

### 4.2 What happens when a dictionary is missing

A missing dictionary is never an error and never a false pass — the
affected rules **SKIP, loudly and by name**. Five states, five messages:

| State | Result | You should |
|---|---|---|
| Not installed | SKIP | run the installer for that dictionary |
| Installed but carries no usable terms | SKIP | reinstall — the file is empty, malformed or unreadable (e.g. a truncated download); only that type degrades, its siblings stay loaded |
| No version selected, or the one selected is not installed | SKIP | name a version with `--<type>-version`, or supply a define.xml that declares it |
| MedDRA installed by an earlier release (no `hierarchy_by_level`) | the hierarchy rules `CDISC-CG0460` / `CG0461` SKIP; every other MedDRA rule answers | re-install (re-convert) the MedDRA distribution — see [`hierarchy` vs `hierarchy_by_level`](#hierarchy-vs-hierarchy_by_level--the-level-of-the-variable-read) |
| The *rule* declares no dictionary type | **ERROR at load** | report it — the rule is defective, not your install |

Only the first four are yours to fix. The run-level `Dictionary_Basis`
field names which types loaded, which did not, and how many of the 98
dictionary rules were actually answered — and it appears in the log, the
JSON report, the XLSX, the REST response and the CLI summary. **A run that
validated nothing never reports as a clean run.**

⚠ Version selection never guesses — see §4 above.

### 4.1 Containers

The CLI and REST images auto-fill on start, following the pattern their
entrypoints already use for rule packs:

- **Auto-convert is on by default.** A licensed distribution mounted at
  the conventional path **`/licensed-dictionaries/<type>`** (types:
  `meddra` `whodrug` `loinc` `medrt` `unii` `snomed` `neoplasm`) is
  converted into the house format on start, into the writable store
  (`COREJ_DICTIONARIES_DIR`). No network, deterministic, and idempotent —
  the entrypoints pass `--skip-installed`, so a type/version already in
  the store is left untouched.
- **Auto-download is opt-in** (`COREJ_DICTIONARY_AUTO_INSTALL=1`), off by
  default; it installs the credential-free trio (MED-RT, UNII, neoplasm)
  and records each release's version verbatim in the report. Off by
  default because validation must be reproducible — a container that
  fetched "latest" would give different findings from identical inputs a
  month later — and because these deployments are often air-gapped.
- When no dictionary resolves at start-up, the entrypoint prints a
  **first-run notice naming the exact install command** (D13 surface 5,
  container flavour); the per-run report then repeats the degradation in
  `Dictionary_Basis` and the per-rule SKIP reasons.

The REST images bake the corej-cli bundle in at `/app/cli` as their
installer (`docker exec <container> /app/cli/run.sh --install-dictionaries
--dictionaries-dir /app/data/dictionaries`). The editor images carry the
resolution path and store location (`/data/dictionaries`) but **no
installer** — populate the store with the CLI, or point
`COREJ_DICTIONARIES_DIR` at a store another deployment filled.

⚠ Neither ever writes into the CLI image's `WORKDIR /data`, which is the
caller's bind mount (the CLI image's store is `/app/dictionaries` — mount
a volume there to persist installs). Auto-fill never aborts the container:
on failure it warns and falls back to whatever is baked in.

---

## 5. Licences

> ⛔ **coreJ ships no dictionary data at all.** Pending a legal review of
> redistribution (see
> [`documentation/dictionary-redistribution-review.md`](../documentation/dictionary-redistribution-review.md)),
> every dictionary is downloaded or read from a local distribution **on your
> machine**, by the installer. Nothing below is redistributed by us.

The installer writes each dictionary's licence or terms-of-use document next
to the data it fetches, and shows you the terms at install time. Your
installed store looks like this — **per type and per version**, so the
notice always travels with the release it applies to:

```
<store>/
  selected-versions.json          {"medrt": "2026.07.06", "unii": "4Aug2026"}
  SOURCES.md                      per type: URL · artefact · version ·
                                  retrieval date · SHA-256 · entry counts
  medrt/2026.07.06/
    medrt.json                    (or medrt.json.gz above ~1 MB)
    LICENSES/MEDRT.txt
  unii/4Aug2026/
    unii.json.gz
    LICENSES/UNII.txt
  loinc/2.80/
    loinc.json
    LICENSES/LOINC.txt
    LICENSES/LOINC_short_license.txt    ← the verbatim notice §10 requires
```

The notice files are written from the texts shipped as classpath resources
in `dictionary-licences/`; there is one per dictionary type
(`medrt`, `unii`, `neoplasm`, `loinc`, `snomed`, `meddra`, `whodrug`).

| Dictionary | How you get it | Terms that apply to your copy |
|---|---|---|
| **MED-RT** | installer downloads (no credentials) | Produced by the **US Department of Veterans Affairs / VHA** (NCI EVS only hosts it). **No terms document is published**, and none exists in any of the 8 distribution files. Your copy rests on absence of restriction — 17 U.S.C. §105 and NLM UMLS source-restriction-level **0** — not on an affirmative grant. MED-RT™ and NDF-RT™ are trademarks. |
| **UNII** | installer downloads (no credentials) | FDA. **No UNII-specific terms document exists.** Basis is 17 U.S.C. §105 plus FDA's general open-data posture; the site footer says "All Rights Reserved", which is boilerplate. The converter takes 2 of 25 columns, dropping every third-party identifier column (`NCIT`, `RXCUI`, `INN_ID`, Kew `MPNS`, …) and the WHO disclaimer attached to `INN_ID`. |
| **LOINC** | installer downloads — **free LOINC account required** | Open licence. Your own use is covered by the grant; §10 (with the display-name condition in §10.c) binds you only if you redistribute your converted copy. See below. |
| **neoplasm** (CDISC SEND CT) | installer downloads (no credentials) | Published free of charge by NCI-EVS with **no licence terms attached**. CDISC asserts "Copyright (c) 2014, CDISC All rights reserved." and publishes no redistribution grant, so this is *free of charge*, not *free of restriction*. |
| **SNOMED CT** | you supply a GPS / RF2 package (registration) | GPS is **CC BY-ND 4.0** since ~March 2026 (it was CC BY 4.0 before, irrevocably, so the release version matters). Verbatim redistribution is permitted; we ship nothing and convert locally. |
| **MedDRA / WHODrug** | you supply a local distribution | Commercial subscription. |

> ⚠ **The installer writes no `CC-BY-4.0.txt` for MED-RT or neoplasm.** The NCI
> Creative Commons statement names **only "The NCI Thesaurus™"** and lives
> in `/ftp1/NCI_Thesaurus/`. MED-RT and CDISC CT are different artefacts
> from different producers. Shipping that licence beside them would
> misstate a third party's terms and purport to pass on rights we do not
> hold — worse than shipping no licence file.

### 5.1 LOINC — why display names are included

LOINC §10.c: *"Any information that is extracted from the Licensed
Material must always be associated with the corresponding identifier from
LOINC … **and include the corresponding LOINC display name**."*

A codes-only extract carries the identifier and no name, which on a
literal reading fails that condition. So `loinc.json` emits
`pairs.loinc` mapping code → `LONG_COMMON_NAME` alongside
`levels.LOINC`, even though no rule reads it. The required notice ships
verbatim as `LICENSES/LOINC_short_license.txt`:

> This material contains content from LOINC (http://loinc.org). LOINC is
> copyright © Regenstrief Institute, Inc. and the Logical Observation
> Identifiers Names and Codes (LOINC) Committee and is available at no
> cost under the license at http://loinc.org/license. LOINC® is a
> registered United States trademark of Regenstrief Institute, Inc.

Under §10 the version number is *"strongly encouraged, but not required"*
(the mandatory-version clause is §9, which governs distributing whole
copies). We ship it anyway. Two further §10 obligations bind and are **not**
discharged by a file inside the bundle: the notice must be on the same
page from which the product is downloaded, and available in the terms of
use of any API or UI. ⚠ Adding display names pulls in terms carrying
`EXTERNAL_COPYRIGHT_NOTICE`, whose §10.b handling is an open legal
question.

### 5.2 CDISC CT subsets — allowlist codelists, never filter columns

The CT publications embed third-party instrument copyrights. In SDTM CT
there are 277 such notices, all in the `CDISC Definition` column of four
"Category of…" codelists (EORTC, AJCC, CAPS, CDR, CDRS-R; 50+ rights
holders).

⛔ **Do not rely on "take codes and submission values, drop definitions".**
QRS *Test Name* codelists put instrument content **in the submission
value**: codelist `C101818` carries `EQ5D02-Anxiety/Depression`,
`EQ5D02-Mobility`, `EQ5D02-Self-Care` … — the EQ-5D-5L's dimensions — and
that codelist contains **zero copyright notices** (EuroQol's notice sits on
a different codelist). Column filtering would ship instrument content
notice-free and discard the only encumbrance signal.

**The control is a codelist allowlist.** C88025 is on it: verified to
carry no third-party notice on the codelist or any of its 310 terms. Note
the bundle ships **submission values only** — the house format has no slot
for a concept code in the `neoplasm` type.

### 5.3 AGPL and bundled external data

**coreJ is licensed AGPL-3.0-only. The AGPL does not extend to any
external dictionary data.**

coreJ ships no such data today, so nothing is aggregated with it. Once you
install dictionaries, the files in your dictionary directory — **LOINC**
(`loinc.json`), **MED-RT** (`medrt.json`), **UNII** (`unii.json.gz`) and the
**CDISC SEND CT** neoplasm subset (`neoplasm.json`) — are separate works
that you obtained from their respective authorities. They remain governed by
their own terms in `LICENSES/`, and coreJ's AGPL grant to modify and
redistribute does **not** apply to them. This matters concretely: LOINC's
licence forbids altering field contents, which the AGPL grant would
otherwise appear to permit. If you redistribute your installed tree, you
must comply with each dictionary's own terms.

⚠ AGPL §13 makes network use a distribution event, so a deployed REST
service triggers LOINC's online-resource notice obligation (§5.1).

### 5.4 Relationship to the dependency third-party licence report

The root `pom.xml` configures `license-maven-plugin` (2.7.1) with
`licensesConfigFile=src/license/licensesConfig.xml` and a comment saying
to run `mvn license:add-third-party` to produce `src/license/THIRD-PARTY.txt`.
⚠ **That mechanism has never been run**: `src/license/` does not exist,
`THIRD-PARTY.txt` has never been generated, the plugin sits in
`<pluginManagement>` bound to no execution, and the repo has no `NOTICE`
file.

`LICENSES/` covers **bundled data**, which that plugin does not and cannot
see — it reports Maven *dependency* licences. The two are complementary,
not duplicative. If `THIRD-PARTY.txt` is ever generated, it should
cross-reference `LICENSES/` so a reader finds both halves.
