# CDISC rule corpus

The rule packages coreJ executes, as released.

## What is in here

| File | What it is |
|---|---|
| `packages.json` | the manifest: one entry per package, naming its family, standard, version and rule count |
| `rules-<family>-<standard>-<version>.json` | one rule package |

Point a coreJ run at this directory:

```bash
CdiscValidate --rules-dir <this directory> ...
```

or set `COREJ_RULES_DIR` to it.

The Define-XML conformance corpus ships as a **separate** archive
(`cumba-oss-cdisc-rules-define-<version>.zip`), because a consumer may want one without
the other.

## ⚠ Which rules am I actually running?

The archive's version is the *corpus* version — it answers "can this be reproduced", not
"which SDTMIG rules are in it". Those are different questions, and the second is the one
that matters when you are reading a validation report.

**`packages.json` is the answer.** It lists every constituent standard and version —
sdtmig 3-4, adamig 1-3, sendig 3-1-1, define 2-1 and the rest — with the number of rules
each contributes. Read it, and record it beside any result you intend to defend.

## Verify this archive

Each archive is published with a detached OpenPGP signature (`.asc`) made with the same
key as the project's Maven Central artifacts, so there is one trust root rather than two:

```bash
curl -sLO <asset-url>
curl -sLO <asset-url>.asc
gpg --verify <asset>.asc <asset>
```

## ⚠ Release assets are mutable

Unlike a Maven Central artifact, a release asset can be replaced in place by anyone with
write access to the repository. **The reproducibility guarantee therefore lives in your
pinned copy and its hash, not in the release.** The signature proves the archive is
authentic; your recorded hash proves *which* authentic archive you adopted. Keep both.
If a run's result matters, keep the archive you ran; do not assume that re-downloading
the same tag returns the same bytes.

## Provenance

Every package here is generated from the authored source in `rules-src/` during the build
— it is not a committed artifact that can fall behind its source. The authored source
carries more than the released packages do: full citation text, the nested authority tree
and the originating `Source` block are kept in the repository and deliberately stripped
from the release, which is what makes this archive far smaller than the authored corpus.
Nothing the validation report prints is affected; the published rule ids (`CG0176`,
`TIG0405`, the FDA ids) are preserved exactly.

⛔ The **CORE rule family is not redistributed** in this archive. It is externally sourced
and may not be redistributed as a set.
