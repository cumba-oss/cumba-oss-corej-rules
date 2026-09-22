# Self-review

<!-- Distilled from the authoring guidelines' R-x.y conventions and the reviewer's manual, and
     from the traps each chapter of this guide records. Every item here is a mistake that is
     SILENT — it produces no error and no warning, so nothing but a reader will catch it. -->

Run this before proposing a rule. Every item is a mistake that **fails silently** — no error, no
warning, nothing in the output that looks wrong. That is the whole reason the list exists; the
loud mistakes catch themselves.

## The rule says what it does

- [ ] **The `Check` is written in violation polarity.** It describes the *problem*, not the
      requirement. If the rule reports nothing on data you know is wrong, this is the first thing
      to look at.
- [ ] **The `Description` matches the `Check`, conjunct for conjunct** — including the condition,
      not just the test. Where they disagree the check is what runs, and the description is what
      people trust.
- [ ] **The `Message` names the fault and what would be right**, in one sentence, to someone who
      has never seen the rule.
- [ ] **`Output_Variables` lists only columns the check actually read.** Reporting one it never
      touched invites the reader to conclude something the rule never checked.
- [ ] **`Executability` is honest**, and where it is not `Fully Executable` the
      `ExecutabilityHint` says *why* — under- and overreporting are different answers and a
      reviewer responds to them differently.

## It runs on the right things

- [ ] **`Scope` uses the most general axis that expresses the requirement.** A rule scoped to a
      class keeps applying when the standard adds a domain to it; one that lists domains silently
      stops covering, with nothing reporting the gap.
- [ ] **One facet per axis** — an `Include` already excludes everything it does not name, and an
      `Exclude` already includes everything it does not name.
- [ ] ⛔ **No requirement names the column whose absence the rule reports.** This is the most
      expensive mistake in the list: it skips the rule on exactly the datasets it exists for, and
      a skip looks like a clean result.
- [ ] **Every required column is one the check cannot work without** — not one it merely mentions.
- [ ] **A type demand is a `:N` / `:C` suffix, not an assumption.** If the check only makes sense
      on one column kind, say so and get a skip; otherwise a wrong type is an ERROR.

## It behaves on the awkward data

- [ ] **A blank cell.** `""` is a present value: `len("") = 0`, and only the emptiness predicates
      treat it as empty. Does the check mean to judge blank rows?
- [ ] **A missing value.** `!=` against a literal **fires** on a missing value, and so does
      `not in`. Did you mean *"where a value is recorded and it is not X"*?
- [ ] **An absent column.** Most checks report nothing — but `empty()` / `is_missing()` fire on
      **every row**, so a completeness rule reports the whole dataset when its column is gone.
- [ ] **A zero-row dataset.** A row-reading rule is already inert; a metadata rule still fires.
      Neither needs a row-count guard, and neither should re-report the empty table.
- [ ] **A partial date.** Comparisons quantify over the whole range, so `==`, `!=`, `<` and `>=`
      can *all* be false on valid data. `not (A < B)` is not `A >= B`.
- [ ] **A date compared without `date()`.** An untagged comparison over ISO strings compiles to
      the numeric-only plain comparison and fires on the empty set.
- [ ] **An operation with nothing to answer.** An absent `domain=`, a filter no row satisfies —
      the answer is a value, not a skip. `record_count` answers `0`, so `$n == 0` **fires**.

## It is written the way the corpus is

- [ ] **`Bindings:`, never `Operations:`;** a `Check` is an expression, never `operator:` /
      `name:` / `value:`. Both alternatives fail to load.
- [ ] **No defensive `var_exists(X) and …`.** An absent column folds to a constant, so the guard
      buys nothing. A presence test belongs in a rule only when the source guidance states it.
- [ ] **Positive names under `not`** — `not empty(x)`, not `non_empty(x)`.
- [ ] **A regex is anchored** if it means the whole value. `=~` is a *find*.
- [ ] **A list target and its `tuple(…)` are in the same order.** Reversed, the membership would
      always be false — since 2026-09-21 the loader rejects that (a permutation of the same names,
      or a length mismatch, is a load error), so the rule fails to load rather than running dead.
      ⚠ *Differently named* columns of the same length stay legal and unchecked: that is the normal
      cross-dataset pairing, and only you can say whether the two lists line up.

## It is grounded and it is proven

- [ ] **`Source` is transcribed, not tidied.** It is what the check is compared against.
- [ ] **Where the source is wrong, `Source_Proposed` says so** — with `Sheet_Issue` stating what
      is wrong, so the divergence is deliberate rather than looking like an authoring error.
- [ ] **`Cited_Guidance` is quoted, not paraphrased**, for **each** standard version listed — the
      wording changes between versions, and sometimes materially.
- [ ] **`Standards` lists only versions you checked the guidance for.** Listing one it does not
      apply to ships findings a sponsor should never see.
- [ ] **[Scenarios](scenarios.md) exist in both directions**, and the `noViolation` one contains
      the **near-miss** — data the rule nearly catches.
- [ ] **A `skipped` scenario exists for each requirement** you added, proving it is real.

## The question to end on

> **If this rule reports nothing on a study, what are the possible reasons?**

Answer it out loud. If *"the rule is broken"* or *"the column was not there"* are on the list
and nothing in the output would distinguish them from *"the data is fine"*, the rule needs a
requirement, a scenario, or both.

---

**Next:** back to [Anatomy of a rule](../getting-started/anatomy-of-a-rule.md).
