package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code plans/done/PLAN-guard-position-lint.md}</b> &mdash; the guard-position authoring rule,
 * asserted in the build. {@code Fix #237}.
 *
 * <p>
 * {@code STANDING-RULINGS.md} &sect;1 decided on <b>2026-08-07</b> that <em>"a check below an
 * {@code any} is not to be seen as a guard"</em>, and nothing has enforced it since. A guard
 * authored into a disjunct is silently inert, and on the former {@code CORE-000269} /
 * {@code CORE-000270} shape (split 2026-09-08) it is worse than inert:
 * {@code var_not_exists(SV.SVPRESP)} is TRUE on an absent {@code SV}, so the misplaced guard
 * <b>enables</b> the rule and it floods against a vacuous {@code $tv_visit}.
 * {@link GuardPositionAudit} derives where every foreign presence leaf actually sits; this test
 * pins that derivation.
 * </p>
 *
 * <h2>&#9776; Ratchet, not a gate to zero</h2>
 *
 * <p>
 * The corpus has <b>0</b> wrongly-positioned foreign presence leaves and {@link #EXPECTED_DEFECTS}
 * pins the <em>known</em> set so the test fails on anything <b>new</b>, exactly as
 * {@code IntentAbsenceOptOutLintTest} does. Removing a row is as much a change as adding one:
 * re-adjudicate, do not just re-pin &mdash; there are <b>two</b> removals on record.
 * {@code FDA-SD1023}, re-adjudicated under {@code D-TA-2}/{@code D-TA-6a} (2026-08-17) when the
 * rule lost its {@code SV} join entirely; and {@code CDISC-CG0033}, re-adjudicated 2026-09-07 under
 * {@code R-5.25} (sheet issue {@code #176}) &mdash; the defect this ratchet pinned was
 * <em>fixed</em>, not moved. Its {@code any[ SV.SVPRESP not_exists, SV.SVPRESP == "Y" ]} conjunct
 * asserted "with no SVPRESP column every visit is planned"; the rule now requires
 * {@code var_exists(SV.SVPRESP)} at the top level and the absent case moved to the minted sibling
 * {@code CDISC-CG0033-A}, which reads planned-ness from the {@code VISIT} name instead. &#9888;
 * {@code CDISC-CG0031} and the {@code CORE} copies still carry the old shape and are the named
 * follow-ups. &#9888; Since {@code Fix #246} (wave 40) the three remaining CG0031/CG0033-family
 * rows are no longer live defects &mdash; their flood route is closed by a top-level dotted
 * {@code TV} guard and they derive {@link GuardPositionAudit.PresenceLeaf#mitigated()} &mdash; but
 * the leaves themselves still sit below an {@code any}, so they stay in the ratchet with
 * re-adjudicated prose rather than being exempted. &#9888; The {@code TV} guards were hoisted into
 * {@code Requirements.Variables.All} (R-4.12); from 2026-09-24 until 2026-10-15 the mitigation pass
 * read Check leaves only and no leaf derived {@code mitigated == true} (finding T10 of
 * FINDINGS-pmd-test-sources). It now reads qualified {@code Requirements.Variables.All} entries and
 * {@code Requirements.Datasets} too, and all four rows derive it again.
 * </p>
 *
 * <h2>&#9888;&#9888; What this lint deliberately does NOT flag</h2>
 *
 * <ol>
 * <li><b>Bucket (c) &mdash; the {@code domain=}-call-only surface</b> (named
 * {@code Operations}-only until runbook W8 retired the operation carrier; the surface is the same
 * {@code domain=} read, now a binding's registry call). {@code 213} rules reached a foreign dataset
 * only that way ({@code $ta_epoch = distinct(EPOCH, domain="TA")}). They carry no presence leaf at
 * all, so they are structurally outside the population;
 * {@link #theDomainCallOnlySurfaceIsCountedNeverFlagged()} asserts that and reports the count.
 * Flagging them is how a lint gets switched off on its first run.</li>
 * <li><b>Presence leaves that are the rule's own assertion.</b> {@code CDISC-SEND-0400} was
 * {@code any[ TT ds_not_exists, TP ds_not_exists, SJ ds_not_exists ]} &mdash; three presence leaves
 * under a top-level {@code any}, none of them a guard, because the rule exists to report the
 * missing domain. Position alone flagged all three. (The 2026-09-12 {@code R-5.25} wave-3 split
 * made each leaf the whole {@code Check} of its own rule &mdash; parent {@code TT}, {@code -A}
 * {@code TP}, {@code -B} {@code SJ} &mdash; so the corpus no longer holds this exhibit, but the
 * leaves are still the rules' own assertions and still excluded by the reads-elsewhere clause. The
 * exhibit was restored synthetically by
 * {@link #theReadsElsewhereClauseChangesADisjunctPositionVerdict()}, where no corpus edit can take
 * it away again.) See {@link #theNaiveReadingWithoutTheReadsElsewhereClauseOverCounts()}.</li>
 * </ol>
 */
class GuardPositionLintTest
{

    /**
     * The <b>authored</b> view. &#9888; The shipped {@code rules/} view lowers Check to a string.
     */
    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final Path REPORT = Path.of("generated", "derivation", "guard-position.tsv");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /*
     * Bucket <b>(b)</b> &mdash; every foreign presence leaf that is not in guard position, keyed
     * {@code ruleId|dataset}, with the adjudication reached by reading each rule file at source on
     * <b>2026-08-12</b>, re-adjudicated on <b>2026-08-13</b> when {@code Fix #246} landed and again
     * on <b>2026-08-17</b> when {@code D-TA-6a} removed one member. All were adjudicated; none was
     * sampled.
     *
     * <p>
     * <b>3 of 4 are real authoring defects</b>, all one shape &mdash; the
     * {@code any[ SV.SVPRESP not_exists, SV.SVPRESP == "Y" ]} conjunct of the CG0031/CG0033 rule
     * family. &#9888; The origin report named only {@code CORE-000269} / {@code CORE-000270}; the
     * lint found <b>three more</b> ({@code CDISC-CG0031}, {@code CDISC-CG0033},
     * {@code FDA-SD1023}), which is the whole reason for deriving the set rather than listing it.
     * {@code FDA-SD1023} then <b>left the population</b> under {@code D-TA-6a} (2026-08-17): the
     * rule was re-authored onto {@code PMDA-SD1023}'s no-join shape, so the misplaced {@code SV}
     * leaf is gone with the join rather than moved &mdash; a removal by re-adjudication, not a
     * re-pin.
     * </p>
     *
     * <p>
     * <b>{@code Fix #246} (wave 40) closed the flood on all five without moving the misplaced
     * leaf</b>: a top-level {@code TV.VISIT exists} / {@code TV.VISITNUM exists} conjunct &mdash;
     * the dotted-{@code exists} idiom of plan &sect;9a, chosen over the ruled {@code ds_exists}
     * shape because the assembler rejects a directly-authored {@code ds_} leaf under a non-presence
     * {@code Rule_Type} (since {@code Fix #255} the engine itself no longer re-types such a guard
     * &mdash; shape B demotes to Record Data and executes &mdash; but the corpus-side rejection
     * stands). The flood was never "absent {@code SV}" &mdash; that is the Description's documented
     * fallback ({@code Executability: "Partially Executable - Possible
     * Overreporting"}); it was the vacuous {@code $tv_visit} / {@code $tv_visitnum} membership test
     * on an absent {@code TV}, and the guard sits on {@code TV} accordingly. The {@code SV} leaves
     * therefore stay in bucket (b) &mdash; a leaf below an {@code any} still does not guard &mdash;
     * but they now derive {@link GuardPositionAudit.PresenceLeaf#mitigated()}, a structural
     * cross-dataset condition with <b>no exemption list</b> (see
     * {@link #theMitigationDerivationIsStructuralNotAList()}). The guards moved to
     * {@code Requirements.Variables.All} (R-4.12), which the mitigation pass reads since 2026-10-15
     * (finding T10; {@link #aGuardHoistedIntoRequirementsMitigates()}).
     * </p>
     *
     * <p>
     * <b>The sixth, {@code CORE-000709}, is a true positional finding but not a defect</b> &mdash;
     * and it is kept in the ratchet rather than special-cased, because a suppression list is a
     * place for a real defect to hide. Its shape is
     * {@code all[ any[ CE.CETERM == "DEATH", all[ CE.CEDECOD exists, CE.CEDECOD == "DEATH" ] ],
     * DTHFL != "Y" ]}: {@code var_exists(CE.CEDECOD)} is a <b>column</b>-level guard for its own
     * conjunct's sibling, not a dataset-level guard for the rule, and it sits correctly relative to
     * the one comparison it protects. An absent {@code CE} cannot flood the rule either, because
     * every {@code CE.*} comparison in it is an equality, and an equality against an absent column
     * is false on every row (the project's absent-column policy) &mdash; unlike
     * {@code is_not_contained_by}, which is vacuously <em>true</em> against an empty set and is
     * what made the other five dangerous. It has no vacuous {@code $}-membership at all, so it has
     * nothing to mitigate and correctly derives {@code mitigated == false}.
     * </p>
     */
    /**
     * ⚠ <b>Corrected 2026-09-24: no longer empty.</b> It holds four adjudicated entries (the
     * CG0031/CG0033 cascade since 2026-09-18, FDA/PMDA-SD1023 since 2026-09-22), and
     * {@link #theGroundTruthRulesAreClassifiedFromTheCorpusItself} reads them from the corpus. What
     * follows is the history up to then.
     *
     * <p>
     * ⭐ <b>EMPTY, and that was the deliverable.</b> This ratchet was created with five
     * wrongly-positioned foreign presence leaves and ends with none: the CG0031/CG0033 family
     * (four) was split on {@code var_exists(SV.SVPRESP)} so each rule tests presence at the top
     * level, and {@code CORE-000709}'s {@code var_exists(CE.CEDECOD)} was removed as guard-injector
     * residue -- redundant, because {@code X == "literal"} is already false on an absent column
     * (owner rulings 2026-09-07/08).
     * </p>
     *
     * <p>
     * ⚠ <b>An empty pin does not make this lint vacuous, but it does change its job.</b> It is now
     * a REINTRODUCTION ratchet: any newly authored guard below a disjunction reds it, and
     * {@link #aNewlyIntroducedWronglyPositionedGuardRedsTheRatchet} proves the detector still fires
     * by INJECTING that shape rather than relying on a live instance. ⛔ Do not add an entry here to
     * "restore coverage" -- an entry is a defect the corpus is carrying.
     * </p>
     */
    private static final String CASCADE_ADJUDICATION = "PLAN-unplanned-visit-cascade (owner rulings Q-A/D2, 2026-09-18): the canonical cascade's tier-2 arm sits below the or BY DESIGN — the leaf is not a guard but the discriminator routing an SVPRESP-less study to the SVUPDES/name tiers. Unlike the pre-split any[not_exists, == Y] defect, absence does NOT assert planned-ness: the arm still demands a blank (SV.)SVUPDES and a neutral VISIT name. Hoisting is forbidden (plan section 2a); the R-4.11 Requirements form would kill the very arm the cascade adds.";

    // ⭐ RE-ADJUDICATED 2026-09-18 (PLAN-unplanned-visit-cascade). The pin was empty from
    // 2026-09-08 to 2026-09-18; the cascade deliberately re-introduces the below-any presence
    // leaf on FOUR rules, each adjudicated at source with the SAME verdict (the constant above).
    // ⚠⚠ SIX on 2026-09-18, then FOUR again the same day: FDA/PMDA-SD1023 joined the population
    // with the plan's phase 3 and LEFT it again when phase 3 was REVERTED. The join made SD1023
    // flood every row of a domain that carries no VISIT/VISITNUM column (AE: 0 -> 1191 findings
    // on testdata/study), because an unqualified name resolves THROUGH the join and no longer
    // folds the absent evaluated-dataset column to false. A removal by re-adjudication, not a
    // re-pin: SD1023 has no SV surface at all again, exactly as before the cascade.
    // ⭐ TWO KEYS -> FOUR on 2026-09-22 (PLAN-unplanned-visit-sv-match-tier). FDA/PMDA-SD1023
    // gained the SV join and the cascade, so the same below-the-or discriminator now sits on four
    // rules again — and the adjudication above applies to them UNCHANGED: the leaf is the
    // discriminator, not a guard. ⚠ This is the SECOND time SD1023 has entered this set; the first
    // (2026-09-18) was reverted the same day when the join FLOODED. It does not flood now: the
    // cause was the engine resolving an unqualified name THROUGH the join, and
    // BroadcastFold.anyJoinedDatasetHasColumn was deleted 2026-09-21. ⛔ The tier-1 `var_exists`
    // gate these four used to carry is GONE (owner: "this is the same as SV.SVPRESP == \"Y\"");
    // only the tier-2 discriminator remains, which is why `kept` and `naive` did not move.
    // ⛔ FOUR KEYS -> TWO on 2026-09-19 (PLAN-retire-core-family phase 6). This is a SET
    // EQUALITY, not a floor, so headroom could not have saved it: CORE-000269|SV and
    // CORE-000270|SV were the CORE arms of the cascade and went with the family. The two CDISC
    // arms carry the identical adjudication, so the defect CLASS is still pinned — what is lost is
    // two of its four instances. ⚠ Both also sat inside the `21 kept` and `88 naive` counts below
    // and inside LeftJoinPositiveTestCoverageLintTest's carrier floor; all four moved together.
    private static final Map<String, String> EXPECTED_DEFECTS = Map.of("CDISC-CG0031|SV",
            CASCADE_ADJUDICATION, "CDISC-CG0033|SV", CASCADE_ADJUDICATION, "FDA-SD1023|SV",
            CASCADE_ADJUDICATION, "PMDA-SD1023|SV", CASCADE_ADJUDICATION);

    /*
     * Bucket <b>(a)</b> &mdash; foreign presence leaves that really do guard. A floor, not a pin:
     * the number is expected to grow as guards are authored, and it is asserted only so that a
     * predicate that silently stops resolving datasets cannot leave the bucket-(b) ratchet looking
     * green over an empty sweep. Measured 2026-08-12: <b>43</b>.
     */
    /**
     * ⚠⚠ <b>THE LAST BLINDNESS CONTROL, and it must not be lowered casually.</b> While
     * {@link #EXPECTED_DEFECTS} was empty this floor was the ONLY thing separating "the corpus is
     * clean" from "the predicate stopped resolving foreign datasets" — both look identical from the
     * ratchet. (It holds four adjudicated entries again since 2026-09-18/22, which also resolve
     * foreign datasets; the floor still guards the rest of the population.)
     *
     * <p>
     * <b>40 → 37 on 2026-09-08</b>, and the reason is on the record rather than inferred: the
     * `R-4.12` hoist moved <b>22</b> correctly-positioned foreign guards out of the {@code Check}
     * and into {@code Requirements.Variables.All} as qualified entries. They are still guards and
     * still correctly positioned; they are simply no longer expressed as {@code Check} leaves, so
     * this predicate — which reads {@code Check} leaves — can no longer see them.
     * </p>
     *
     * <p>
     * ⭐ <b>That was a weakness in the floor, not in the corpus — and it is FIXED as of
     * 2026-09-10.</b> A floor counting only {@code Check}-side guards drifts downward every time a
     * rule is correctly migrated, so it eroded exactly as the corpus improved: the 2026-09-08 hoist
     * took it 40 → 37, and the 2026-09-10 one (25 more rules) would have taken it to <b>18</b>. The
     * durable fix this javadoc asked for is now implemented — the control counts <b>guards in
     * either expression</b>: correctly-positioned {@code Check} leaves <em>plus</em> qualified
     * {@code Requirements.Variables} entries. A guard that moves between the two no longer moves
     * the total, which is the property a blindness control needs.
     * </p>
     *
     * <p>
     * ⚠ So the floor below is now {@link #GUARD_SURFACE_FLOOR} and the quantity it bounds is the
     * SUM. ⛔ Never lower it to accommodate a migration: a migration cannot lower it any more.
     * Measured 2026-09-10 over the AUTHORED corpus: 18 {@code Check} leaves + 62 qualified
     * requirement entries = <b>80</b>. ⚠ 62, not the 33 a shipped-corpus count returns — this lint
     * reads {@code rules-src}, which includes rules parked by
     * {@code Executability: "Not Executable"} (R-8.5) that never reach a package.
     * </p>
     *
     * <p>
     * ⭐ <b>Re-measured by run 2026-09-19</b>, after the CORE family was retired
     * (PLAN-retire-core-family §13.1, which left this as one of four quantities that could not be
     * settled statically): <b>13 {@code Check} leaves + 49 qualified requirement entries = 62</b>.
     * The floor was therefore <b>NOT moved</b> — 62 ≥ 60 — and must not be: it is a floor, so
     * lowering it would only loosen a control that still passes. ⚠ The retirement took <b>six</b>
     * qualified entries, CORE's whole share of the 55 that existed then, and <b>none</b> of the
     * guard leaves. The plan could only bound the result in {@code [49, 62]} because the bucket-(a)
     * leaves are counted here but never emitted to the TSV, so their family share cannot be read
     * off the file; the answer is the top of that range. ⚑ The live figures are always line 2 of
     * {@code generated/derivation/guard-position.tsv}, which this test writes.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>What a sum can and cannot catch — stated because the first wording of this javadoc
     * overclaimed it</b> (review finding 7, 2026-09-10). It said a fall *"means the predicate has
     * stopped seeing one of the two expressions"*. Only one of those two failures reaches the
     * floor: if the qualified-entry half went blind the surface is 18 &lt; 60 and it reds, but if
     * the <b>Check-leaf</b> half went blind the surface is 62 ≥ 60 and <b>this floor passes</b>. ⭐
     * <b>That asymmetry has since closed, and by accident</b> — on the 2026-09-19 corpus a blind
     * Check-leaf half leaves 49 &lt; 60, so the sum now reds for either half. ⛔ Do not rely on it:
     * it is a consequence of the corpus shrinking towards the floor, not of a design change, and it
     * reverses as soon as the corpus grows again. The Check-leaf half is covered on purpose by two
     * other assertions in this class rather than by this one — {@code assertEquals(17, kept, …)} (⚠
     * 18 when this paragraph was written; 21 until the CORE retirement), which counts
     * {@link GuardPositionAudit#presenceLeaves} directly, and the bucket-(b) ratchet, which reds if
     * the {@code Position} classification regresses. ⭐ The instrument FAMILY is sound; do not read
     * this floor as covering both halves on its own, and do not "fix" it with per-half floors —
     * those are what erode under a migration, which is the whole reason the sum exists.
     * </p>
     */
    private static final int GUARD_SURFACE_FLOOR = 60;

    /*
     * Bucket <b>(c)</b> &mdash; rules whose foreign surface is reachable only through an
     * {@code Operations} {@code domain:}. Measured 2026-08-12: <b>218</b> authored rules;
     * re-derived by run 2026-08-13 after {@code Fix #246}: <b>213</b> &mdash; the five
     * CG0031/CG0033-family rules' dotted {@code TV} guard makes {@code TV} column-addressable, so
     * their {@code TV} rows leave the bucket. Re-derived again by run 2026-08-22 after the DRAFT
     * session deleted {@code DRAFT-110006} and {@code DRAFT-110007} (commit {@code 51dc809e9}),
     * whose only foreign surface was an {@code Operations} {@code domain: "DS"}: <b>213 &rarr;
     * 211</b>. A deletion, not a re-shaping &mdash; neither rule leaves a bucket-(b) residue.
     *
     * <p>
     * &#9888; The origin report said <b>"~40 TIG rules"</b>. That was the shipped
     * {@code rules-core-tig-1-0.json} slice; against the whole authored corpus the number was
     * {@code 218}, of which {@code 25} carry a TIG standard reference. Neither figure is wrong —
     * they are different populations — but the lint's exclusion has to cover the larger one.
     * </p>
     */
    /**
     * <b>211 → 221 on 2026-09-08</b>, and the direction is the point: the `R-4.12` hoist moved 22
     * {@code Check}-side foreign presence guards into {@code Requirements}, so ten rules whose
     * foreign surface was <em>Check leaf + Operation</em> became <em>Operation only</em> and
     * entered bucket (c).
     *
     * <p>
     * ⭐ <b>The metric's own meaning has drifted, and that is worth more than the number.</b> Bucket
     * (c) is documented as an EXPRESSIVENESS GAP — "no column-presence guard is writable for one
     * (exists/var_exists take a COLUMN name)". That is no longer true: a qualified
     * {@code Requirements.Variables.All} entry IS a writable presence guard for exactly these
     * surfaces, and this population grew <em>because ten rules used it</em>. So the count now
     * conflates "the engine cannot express a guard here" with "a guard is expressed, just not as a
     * Check leaf". ⛔ Do not read 221 as the size of the engine gap until the two are separated; see
     * {@code <meta>/plans/PLAN-qualified-requirements-cross-standard.md}, which is where the
     * qualified-entry mechanism is being grounded anyway.
     * </p>
     *
     * <p>
     * <b>221 → 223 on 2026-09-11 (wave D)</b>, the same conflation again: the two minted
     * {@code R-5.25} split siblings {@code FDA-SD1017-A} / {@code PMDA-SD1017-A} inherit their
     * parents' shape verbatim — {@code $tv_visitnum} ({@code distinct} over {@code TV}) plus a
     * qualified {@code Requirements.Variables.All} entry {@code TV.VISITNUM} — so like their
     * parents, which have sat in this bucket all along, each reads {@code TV} through an Operation
     * while the presence guard lives in {@code Requirements} (bucket (a)'s qualified-entry count
     * moved 62 → 64 in the same run). Nothing is unguarded; the finding is only that the
     * guarded-by-qualified-entry population grew by two.
     * </p>
     */
    // 223 -> 217 on 2026-09-18 (PLAN-unplanned-visit-cascade): the six cascade rules' SV surface
    // moved from Operations-only (bucket c) into bucket (b)'s adjudicated below-any presence
    // leaves, and the eight retired -A siblings left the census entirely. Re-measured.
    // 217 -> 220 on 2026-09-19 (PLAN-retire-core-family phase 4, the mints): FDA-SD9741 (DS),
    // FDA-SD9745 (EX) and FDA-SD9748 (EX) read a foreign dataset through a `distinct(...,
    // domain=...)` binding and nowhere else, so each lands here exactly as its CORE source does --
    // CORE-000679, CORE-000885 and CORE-000927 are already bucket-(c) rows and the shape is
    // transcribed, not introduced. ⚑ The other sixteen mints do not enter: SD9743/SD9744 bind TS
    // while SCOPED to TS and SD9749 binds AE while scoped to AE (not foreign at all), SD9742 and
    // SD9747 declare their foreign dataset in Match_Datasets, and the rest read one dataset.
    // ⇒ Phase 6's deletion of the three CORE sources takes this back to 217; the net of phase 4 +
    // phase 6 on this bucket is ZERO, which is what "transcribed" is supposed to mean.
    // Bucket (b) -- the population that is actually flagged -- did NOT move: EXPECTED_DEFECTS is
    // unchanged and the assertion above it passed in the same run.
    // 220 -> 173 on 2026-09-19 (PLAN-retire-core-family phase 6): an EXACT equality over the
    // operation-only RULE population; the CORE family held 47 of the 220. ⚠ Two units live in
    // this file and both moved: rules here (220 -> 173) and PAIRS at the >= 200 floor below
    // (225 -> 180). Re-derived from the run, not adjusted by arithmetic.
    // 173 -> 177 on 2026-09-22 (PLAN-unplanned-visit-sv-match-tier): the four `-B` SV-absent
    // siblings. ⛔ CORRECTED at review: this comment first said they "carry ds_exists(\"SV\") as a
    // bucket-(c) Operations-only foreign surface". Wrong dataset AND wrong surface — the artifact
    // this same change generated says TV, not SV (generated/derivation/guard-position.tsv gained
    // four `c <id> <FAMILY> TV` rows), and ds_exists is a Check leaf, not an Operations surface.
    // The real mechanism is the FDA/PMDA-SD1017-A precedent 20 lines above: each sibling inherits
    // its parent's $tv_visit/$tv_visitnum binding, which ALONE is what bucket (c) counts.
    // ⛔ Corrected again at review: the first correction added "plus a qualified
    // Requirements.Variables entry, which is what bucket (c) counts" — that entry is bucket
    // (a)'s 49 -> 53 movement, not (c)'s. Right figure, wrong bucket, in the fix for a wrong
    // reason. Corroborating: bucket (a)'s qualified count moved 49 -> 53
    // in the same artifact. The NUMBER was right and the reason was invented.
    // 177 -> 174 on 2026-09-29 (runbook W2a phase 5b, PLAN-operation-replacements §2.3):
    // CDISC-SEND-0330, FDA-SE2234 and FDA-SE2244 dropped their supp_qnam_present(domain=SUPP--)
    // bindings — the operation is deleted and the qualifier is read per record through the
    // declared SUPP merge (Supp_Merge), which is neither a binding nor a Check leaf, so the three
    // rules leave the Operations-only foreign surface. Re-derived from the run (174 measured),
    // not adjusted by arithmetic; bucket (b)'s EXPECTED_DEFECTS did not move in the same run.
    // 174 -> 177 on 2026-09-30 (PLAN-rprfdy-offset-tp-join phase 4, D-SJ / C2): CDISC-SEND-0401,
    // 0402 and 0403 dropped their SJ Match_Datasets entry and now reach SJ and TP through
    // bindings only — record_count(domain="SJ", …), min_date(SJSTDTC, domain="SJ", …) and
    // read_value(RPRFDY, domain="TP", group=[DM.RPATHCD, RPHASE], mode="MAX") — with no Check leaf
    // on either foreign dataset, which is exactly bucket (c)'s definition (the artifact gained
    // three `c CDISC-SEND-040x CDISC SJ,TP` rows). Their foreign columns ARE guarded, by the
    // qualified Requirements entries (bucket (a): 55 -> 67 qualified entries, +4 per rule —
    // SJ.USUBJID, DM.RPATHCD, TP.RPATHCD, TP.RPRFDY:N; All_Or_None 220 -> 223, +TP.RPHASE per
    // rule). Re-derived from the run (177 measured); EXPECTED_DEFECTS did not move.
    // 177 -> 179 on 2026-09-30 (PLAN-send-tfdetect-pathology-unpark): CDISC-SEND-0204 and 0205
    // were un-parked (a parked rule is outside the census) and reach CL, PM, MA and EX through
    // bindings only — distinct([USUBJID, --SPID], domain=...) and min_date(EXSTDTC, domain="EX",
    // group=[USUBJID]) — with no Check leaf on those datasets, which is bucket (c)'s definition
    // (the artifact gained two `c CDISC-SEND-020x CDISC CL,EX,MA,PM` rows; DS is joined, so it is
    // not in the row). Their foreign columns ARE guarded: EX.EXSTDTC / EX.USUBJID as qualified
    // Requirements entries (bucket (a): 67 -> 71) and the [X.USUBJID, X.XSPID] All_Or_None groups
    // for CL, PM and MA (223 -> 235 qualified All_Or_None entries). Re-derived from the run (179
    // measured); EXPECTED_DEFECTS did not move.
    // Same day, review L4: the [MA.USUBJID, MA.MASPID] group was DROPPED from both rules (an MA
    // without MASPID links no record, so 0204 finds nothing and 0205 judges by the same formula;
    // the CL / PM groups stay), so the artifact's All_Or_None line fell 235 -> 231 (2 entries x 2
    // rules). The count is not pinned here, and this constant did not move: MA is still read
    // through a binding only, so both rules stay in bucket (c) — 179 re-measured.
    // 179 -> 180 on 2026-10-03 (rule round 2026-10-03, sheet issue #286): CDISC-CG0022 reads
    // RELREC through bindings only - distinct(RDOMAIN | RELID, domain="RELREC", filter=(IDVAR ==
    // concat(RDOMAIN, "LNKGRP" | "LNKID"))) - to accept a --LNKGRP that RELREC relates to a
    // --LNKID of another domain. No Check leaf on RELREC, so bucket (c) (the artifact gained one
    // `c CDISC-CG0022 CDISC RELREC` row). Deliberately NOT guarded by qualified Requirements
    // entries: an absent RELREC (or column) must leave the rule firing as before - the empty
    // list exempts nothing - not skip it. Re-derived from the run (180 measured); EXPECTED_DEFECTS
    // did not move.
    // 180 -> 184 on 2026-10-11 (PLAN-join-key-one-side-absent-drop QK17, owner 2026-10-11): the
    // four R-5.25 split siblings
    // CDISC-SEND-0204-A / 0205-A / 0305-A / 0306-A read EX (and 0204-A / 0205-A CL, PM, MA) through
    // domain= calls only, as their pooled parents do. Re-derived from the run (184 measured).
    private static final int DOMAIN_CALL_ONLY_RULES = 184;

    // ------------------------------------------------------------------
    // Corpus plumbing
    // ------------------------------------------------------------------

    private static List<Path> ruleFiles() throws IOException
    {
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            return walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }


    private static Rule read(Path file)
    {
        try
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator or
            // domain until normalized — the same pass the loader runs.
            RulePackageLoader.materialiseBindings(rule);
            // ⚠ No lowering any more: phase 7 retired the v1 operator-leaf model, and the audit
            // walks the parsed expression through CheckExprUnits. The strict-classification
            // census below is the successor of the "presents an operator-leaf Check" count that
            // caught this sweep going blind on the phase-7 bump.
            return rule;
        }
        catch (IOException _)
        {
            throw new UncheckedIOException(new IOException("cannot read " + file));
        }
    }


    private static Rule parse(String yaml)
    {
        try
        {
            Rule rule = YAML.readValue(yaml, Rule.class);
            RulePackageLoader.materialiseBindings(rule);
            return rule;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /**
     * The rule's qualified {@code Requirements.Variables} entries — a {@code DATASET.VARIABLE} in
     * {@code All}, {@code Any} or {@code None}. The second expression a correctly-positioned
     * foreign guard can take (Fix #124 / {@code R-4.12}), and therefore the second half of this
     * lint's blindness control.
     *
     * <p>
     * ⚠ Those three facets, and the parse is {@link ScopeVariableEntry}'s rather than a local
     * {@code indexOf('.')}: that is the same splitter the engine gates on, so this count cannot
     * start disagreeing with the engine about which entries are qualified. ⛔ {@code All_Or_None} is
     * <b>not</b> in this count (review round 2 of {@code PLAN-join-key-pairing}, C-L2): a group is
     * not a guard — it lets the rule run when both sides are absent — so its qualified entries are
     * counted on their own line by {@link #qualifiedAllOrNoneEntryCount} and kept out of the
     * {@code GUARD_SURFACE_FLOOR} quantity.
     * </p>
     *
     * @param rule
     *            the authored rule
     * @return how many qualified guard entries it declares
     */
    private static int qualifiedRequirementEntryCount(Rule rule)
    {
        VariableRequirement vars = rule.effectiveVariableRequirement();
        if (vars == null)
        {
            return 0;
        }
        int count = 0;
        for (List<String> facet : Arrays.asList(vars.getAll(), vars.anyUnion(), vars.getNone()))
        {
            if (facet == null)
            {
                continue;
            }
            for (String entry : facet)
            {
                if (entry != null && ScopeVariableEntry.parse(entry).isQualified())
                {
                    count++;
                }
            }
        }
        return count;
    }


    /**
     * The rule's qualified {@code All_Or_None} entries — reported on their own line, never added to
     * the guard surface (see {@link #qualifiedRequirementEntryCount}).
     */
    private static int qualifiedAllOrNoneEntryCount(Rule rule)
    {
        VariableRequirement vars = rule.effectiveVariableRequirement();
        if (vars == null)
        {
            return 0;
        }
        int count = 0;
        for (String entry : vars.allOrNoneUnion())
        {
            if (entry != null && ScopeVariableEntry.parse(entry).isQualified())
            {
                count++;
            }
        }
        return count;
    }


    /** The bucket-(b) keys of an arbitrary rule set — the ratchet's subject, factored out. */
    private static Set<String> defectKeys(List<Rule> rules)
    {
        Set<String> keys = new TreeSet<>();
        for (Rule rule : rules)
        {
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    keys.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // The ratchet
    // ------------------------------------------------------------------


    /**
     * The deliverable. Derives the (a)/(b)/(c) partition over the authored corpus, writes
     * {@code generated/derivation/guard-position.tsv}, and pins bucket (b).
     */
    @Test
    void theWronglyPositionedGuardPopulationIsExactlyTheSixKnownLeaves() throws IOException
    {
        List<Path> files = ruleFiles();
        // 3700 -> 3435 (PLAN-retire-core-family phase 6): rules-src/checks 3932 -> 3435.
        // 3435 -> 3400 on 2026-10-07 (PLAN-message-folded-corpus-alignment phase 2): the 42
        // R-4.11c absent-column sisters retired, rules-src/checks 3461 -> 3419.
        assertTrue(files.size() >= 3400, "the authored corpus must actually have been read; found "
                + files.size() + " YAML files under " + CHECKS_DIR.toAbsolutePath());

        Map<String, String> defects = new TreeMap<>();
        List<String> defectRows = new ArrayList<>();
        List<String> domainCallOnlyRows = new ArrayList<>();
        int guardLeaves = 0;
        int qualifiedRequirementEntries = 0;
        int qualifiedAllOrNoneEntries = 0;
        int structuralAll = 0;
        int domainCallOnlyRules = 0;
        for (Path file : files)
        {
            Rule rule = read(file);
            // The strict-classification census: rules whose every unit carries a strict
            // v1-vocabulary classification — the same population the retired strict lowering
            // accepted; the residue is the ~91 native-only expressions, which had no v1 surface
            // before phase 7 either.
            Expr check = CheckExprUnits.checkExpr(rule);
            if (check != null && CheckExprUnits.strictlyClassified(check))
            {
                structuralAll++;
            }
            String org = String.valueOf(file.getParent().getFileName());
            // The OTHER expression a correctly-positioned foreign guard can take since Fix #124: a
            // qualified Requirements.Variables entry. Counted into the same blindness control, so
            // the R-4.12 hoist moves a guard between two counted surfaces instead of off the books.
            qualifiedRequirementEntries += qualifiedRequirementEntryCount(rule);
            qualifiedAllOrNoneEntries += qualifiedAllOrNoneEntryCount(rule);
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.GUARD)
                {
                    guardLeaves++;
                    continue;
                }
                defects.put(leaf.ruleId() + '|' + leaf.dataset(), leaf.path());
                defectRows.add("b\t" + leaf.ruleId() + '\t' + org + '\t' + leaf.dataset() + '\t'
                        + leaf.operator() + '\t' + leaf.path() + '\t' + leaf.negated() + '\t'
                        + leaf.mitigated());
            }
            List<String> domainCallOnly = GuardPositionAudit.operationOnlyDatasets(rule);
            if (!domainCallOnly.isEmpty())
            {
                domainCallOnlyRules++;
                domainCallOnlyRows.add("c\t" + GuardPositionAudit.ruleId(rule) + '\t' + org + '\t'
                        + String.join(",", domainCallOnly) + "\t-\t-\t-\t-");
            }
        }

        // Blindness controls. A model or Jackson regression, or a predicate that stops resolving
        // datasets, returns "no defects" exactly as happily as a clean corpus.
        assertTrue(structuralAll >= 3000,
                "only " + structuralAll + " of " + files.size() + " rules classify strictly into"
                        + " CheckExprUnits' v1-vocabulary units; the sweep is not seeing the"
                        + " corpus. A collapse here means the unit classifier has stopped"
                        + " speaking the corpus's language, not that the corpus changed.");
        int guardSurface = guardLeaves + qualifiedRequirementEntries;
        assertTrue(guardSurface >= GUARD_SURFACE_FLOOR,
                "only " + guardSurface + " correctly-positioned foreign guards found (floor "
                        + GUARD_SURFACE_FLOOR + ") — " + guardLeaves + " as Check leaves and "
                        + qualifiedRequirementEntries + " as qualified Requirements entries. The"
                        + " predicate has stopped seeing one of the two expressions, so the"
                        + " bucket-(b) ratchet below is no longer trustworthy. ⛔ A guard migration"
                        + " cannot lower this sum — do not re-base the floor to make it pass.");

        Files.createDirectories(REPORT.getParent());
        List<String> report = new ArrayList<>();
        report.add("# derived by GuardPositionLintTest (Fix #237; mitigated column Fix #246)"
                + " — do not hand-edit");
        report.add("# bucket a (correctly-positioned foreign guards, counted only): " + guardLeaves
                + " leaves + " + qualifiedRequirementEntries + " qualified Requirements entries = "
                + guardSurface + " (the GUARD_SURFACE_FLOOR quantity)");
        report.add("# qualified All_Or_None entries (a group is not a guard — counted, never"
                + " added to the surface): " + qualifiedAllOrNoneEntries);
        report.add("# bucket b (wrongly-positioned, rows below): " + defects.size() + " leaves");
        report.add("# bucket c (domain=-call-only foreign surface, rows below; NEVER flagged): "
                + domainCallOnlyRules + " rules");
        report.add("# bucket\trule_id\torg\tdataset(s)\toperator\tpath\tnegated\tmitigated");
        report.addAll(defectRows.stream().sorted().toList());
        report.addAll(domainCallOnlyRows.stream().sorted().toList());
        Files.write(REPORT, report, StandardCharsets.UTF_8);

        assertEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), new TreeSet<>(defects.keySet()),
                "the wrongly-positioned-guard population moved. A leaf below an `any` does not"
                        + " guard (STANDING-RULINGS.md §1, 2026-08-07) and on the CG0031 family it"
                        + " actively enables the rule. Adjudicate each new entry at source and"
                        + " record the verdict in EXPECTED_DEFECTS — see " + REPORT);
        assertEquals(DOMAIN_CALL_ONLY_RULES, domainCallOnlyRules,
                "the domain=-call-only foreign surface moved. It is bucket (c) — never flagged, but"
                        + " it is the measured size of the engine gap, so a change is a finding.");
    }


    /**
     * <b>A3.</b> The ~40-rule exclusion, asserted rather than asserted-about: no rule whose foreign
     * surface is {@code domain=}-call-only appears in bucket (b) <b>for that dataset</b>.
     *
     * <p>
     * &#9888; Per {@code (rule, dataset)} pair, not per rule &mdash; {@code CORE-000269} used to be
     * in both lists and correctly so: its {@code TV} surface was {@code Operations}-only (bucket c)
     * while its {@code SV} surface was a join it mis-guarded (bucket b). A per-rule assertion would
     * have had to exempt it and would then have been exempting the ground truth. It was split
     * 2026-09-08 and no longer appears in bucket (b); the pair-not-rule granularity stays, because
     * the next such rule would recreate the overlap.
     * </p>
     */
    @Test
    void theDomainCallOnlySurfaceIsCountedNeverFlagged() throws IOException
    {
        Set<String> domainCallOnlyPairs = new TreeSet<>();
        Set<String> defectPairs = new TreeSet<>();
        for (Path file : ruleFiles())
        {
            Rule rule = read(file);
            String id = GuardPositionAudit.ruleId(rule);
            GuardPositionAudit.operationOnlyDatasets(rule)
                    .forEach(dataset -> domainCallOnlyPairs.add(id + '|' + dataset));
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    defectPairs.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        // 200 -> 170 (PLAN-retire-core-family phase 6): operation-only PAIRS 225 -> 180.
        assertTrue(domainCallOnlyPairs.size() >= 170, "only " + domainCallOnlyPairs.size()
                + " domain=-call-only surfaces found; the exclusion has stopped working");
        Set<String> leaked = new TreeSet<>(domainCallOnlyPairs);
        leaked.retainAll(defectPairs);
        assertEquals(Set.of(), leaked,
                "a domain=-call-only surface is being flagged as a wrongly-positioned guard. No"
                        + " column-presence guard is writable for one (exists/var_exists take a"
                        + " COLUMN name), so this is an expressiveness gap, not an authoring"
                        + " defect, and flagging it is how a lint gets disabled.");
        // ⚠⚠ THE ANTI-VACUITY ANCHOR IS GONE, and the retainAll above is now TRIVIALLY EMPTY.
        // It asserted that some live instance was still flagged, so a broken exclusion that
        // flagged nothing could not pass; the last instance (CORE-000709|CE) was fixed
        // 2026-09-08 and defectPairs is empty by design. What still carries weight here is the
        // >= 200 floor above: the domain=-call-only surface must keep being FOUND. The leak
        // assertion re-arms by itself the moment any rule enters bucket (b) again.
        // 2026-09-18 (PLAN-unplanned-visit-cascade): "empty by design" ended when the owner-ruled
        // cascade re-introduced the below-any presence leaf on four adjudicated rules (six until
        // the plan's phase 3 was reverted the same day, see EXPECTED_DEFECTS); the pair set
        // is now pinned to EXPECTED_DEFECTS' keys, and the leak assertion above is re-armed.
        assertEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), defectPairs,
                "the wrongly-positioned population must be exactly the four adjudicated cascade"
                        + " leaves; anything else is a reintroduced defect — re-adjudicate"
                        + " EXPECTED_DEFECTS, do not extend it casually");
    }

    /**
     * The four live corpus rules whose {@code not var_exists(SV.SVPRESP)} discriminator sits below
     * the cascade's {@code or} &mdash; the ground truth of
     * {@link #theGroundTruthRulesAreClassifiedFromTheCorpusItself()}. Named by FILE, not derived by
     * the sweep, so a rule that is renamed, split or deleted reds this test by name instead of
     * silently leaving the population.
     */
    private static final List<Path> GROUND_TRUTH_FILES = List.of(
            CHECKS_DIR.resolve("CDISC").resolve("CDISC-CG0031.yaml"),
            CHECKS_DIR.resolve("CDISC").resolve("CDISC-CG0033.yaml"),
            CHECKS_DIR.resolve("FDA").resolve("FDA-SD1023.yaml"),
            CHECKS_DIR.resolve("PMDA").resolve("PMDA-SD1023.yaml"));

    /**
     * <b>A2.</b> The ground truth, read from the corpus rather than from a fixture: a fixture can
     * agree with a wrong predicate forever, whereas these rules are real authored instances of the
     * shape the lint exists for &mdash; a foreign presence leaf below a disjunction.
     *
     * <p>
     * <b>History.</b> The original subjects were {@code CORE-000269} / {@code CORE-000270}, then
     * {@code CORE-000709}; all were fixed by 2026-09-08 and the test was retired (owner ruling
     * 2026-09-08) as a tombstone, explicitly <em>not</em> replaced by a fixture, with the
     * instruction to restore it against a live instance if one reappeared. Live instances did
     * reappear: {@code CDISC-CG0031} and {@code CDISC-CG0033} on 2026-09-18
     * ({@code PLAN-unplanned-visit-cascade}) and {@code FDA-SD1023} / {@code PMDA-SD1023} on
     * 2026-09-22 ({@code PLAN-unplanned-visit-sv-match-tier}). Restored against all four 2026-09-24
     * (follow-up of finding T4 in {@code plans/findings/done/FINDINGS-pmd-test-sources.md}).
     * </p>
     *
     * <p>
     * &#9888; What is asserted is the detector's <b>positional</b> verdict &mdash;
     * {@link GuardPositionAudit.Position#DISJUNCT}, i.e. bucket (b) &mdash; not that the rules are
     * defective. Whether each is a defect is adjudicated separately, and all four carry
     * {@link #CASCADE_ADJUDICATION}: the leaf is the cascade's tier-2 discriminator, below the
     * {@code or} by design. That verdict does not change the classification, which is exactly why
     * these rules make honest ground truth: the leaf really is a presence probe on a foreign
     * dataset ({@code SV}) the rule reads elsewhere, really sits below an {@code any}, and so a
     * detector that stopped calling it {@code DISJUNCT} would be broken, not corrected.
     * </p>
     */
    @Test
    void theGroundTruthRulesAreClassifiedFromTheCorpusItself()
    {
        Map<String, GuardPositionAudit.PresenceLeaf> disjuncts = new TreeMap<>();
        for (Path file : GROUND_TRUTH_FILES)
        {
            assertTrue(Files.isRegularFile(file), "ground-truth rule " + file.toAbsolutePath()
                    + " is gone — re-adjudicate the ground truth, do not substitute a fixture");
            List<GuardPositionAudit.PresenceLeaf> perRule = new ArrayList<>();
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit
                    .presenceLeaves(read(file)))
            {
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    perRule.add(leaf);
                    disjuncts.put(leaf.ruleId() + '|' + leaf.dataset(), leaf);
                }
            }
            assertEquals(1, perRule.size(), file + " must carry exactly one wrongly-positioned"
                    + " foreign presence leaf (the SV.SVPRESP discriminator); found " + perRule);
        }

        // Anti-vacuity: the exact ids, so a detector that stops classifying these leaves — or
        // starts resolving them to a different dataset — reds here rather than passing empty.
        assertEquals(
                Set.of("CDISC-CG0031|SV", "CDISC-CG0033|SV", "FDA-SD1023|SV", "PMDA-SD1023|SV"),
                disjuncts.keySet(),
                "the detector no longer classifies the live cascade discriminators as"
                        + " wrongly-positioned foreign presence leaves");
        for (GuardPositionAudit.PresenceLeaf leaf : disjuncts.values())
        {
            assertEquals("var_exists", leaf.operator(), leaf + ": wrong presence operator");
            assertTrue(leaf.negated(), leaf + ": the leaf is authored as `not var_exists(...)`");
            assertTrue(leaf.path().contains("any"),
                    leaf + ": a DISJUNCT verdict must come from a disjunction on the path");
        }
        assertTrue(EXPECTED_DEFECTS.keySet().containsAll(disjuncts.keySet()),
                "every ground-truth instance must also be an adjudicated EXPECTED_DEFECTS row");
        // T10 (restores the tombstone removed 2026-09-24): every one of the four reads TV through
        // a vacuous-on-empty membership and guards TV in Requirements.Variables.All (TV.VISIT /
        // TV.VISITNUM, the R-4.12 hoist), so each must derive mitigated == true. Before T10 the
        // mitigation pass read Check leaves only and all four derived false.
        for (GuardPositionAudit.PresenceLeaf leaf : disjuncts.values())
        {
            assertTrue(leaf.mitigated(), leaf + ": the TV guard in Requirements.Variables.All"
                    + " closes the flood route, so the misplaced SV leaf must derive mitigated");
        }
    }

    // ------------------------------------------------------------------
    // Mitigation — Fix #246
    // ------------------------------------------------------------------

    // ⚑ The corpus-read tombstone removed 2026-09-24 (finding T4) is restored as the mitigation
    // half of theGroundTruthRulesAreClassifiedFromTheCorpusItself (finding T10, 2026-10-15): the
    // four live bucket-(b) rows carry their TV guard in Requirements.Variables.All, which the
    // mitigation pass now reads, so each derives mitigated == true again.


    /**
     * The mitigating condition, clause by clause &mdash; <b>structural, cross-dataset, and with no
     * exemption list</b>. The shape mirrors the real family: the flagged leaf is on the joined
     * {@code DM} while the danger (and therefore the guard) is on {@code TA}, the {@code domain:}
     * of the {@code $}-operation the membership test reads. Neuter any clause of
     * {@code GuardPositionAudit}'s mitigation pass and at least one of these flips.
     */
    @Test
    void theMitigationDerivationIsStructuralNotAList()
    {
        String operations = """
                Bindings:
                - name: "$ta_epoch"
                  expression: 'distinct(EPOCH, domain=\"TA\")'
                """;
        String misplacedGuard = "(var_not_exists(DM.DTHFL) or DM.DTHFL == \"Y\")";
        String membership = "EPOCH not in $ta_epoch";

        // The mitigated shape: a top-level guard on the DANGEROUS dataset (TA), not on the
        // flagged unit's own dataset (DM).
        assertEquals(Map.of("DM", true),
                mitigations(operations + check(
                        "var_exists(TA.EPOCH) and " + misplacedGuard + " and " + membership)),
                "a top-level guard on the vacuous operation's dataset mitigates the disjunct");

        // Drop the guard: same disjunct, same danger, nothing closing it.
        assertEquals(Map.of("DM", false),
                mitigations(operations + check(misplacedGuard + " and " + membership)),
                "without the TA guard the flood route is open — not mitigated");

        // Guard the WRONG dataset: a guard on DM (the unit's own dataset) does not close a
        // vacuous $ta_epoch. Mitigation is about the danger, not about the unit.
        assertEquals(Map.of("DM", false),
                mitigations(operations + check(
                        "var_exists(DM.DTHFL) and " + misplacedGuard + " and " + membership)),
                "a guard on the flagged unit's own dataset leaves the vacuous-set route open");

        // The guard authored into the disjunction — the very defect this lint exists for — must
        // not count as mitigation.
        assertEquals(Map.of("DM", false, "TA", false),
                mitigations(operations + check("(var_exists(TA.EPOCH) or not empty(AETERM)) and "
                        + misplacedGuard + " and " + membership)),
                "a TA guard below a disjunction does not guard, so it cannot mitigate either");

        // No vacuous membership at all (equality instead): nothing to mitigate, so the flag
        // stays false — CORE-000709's shape.
        assertEquals(Map.of("DM", false),
                mitigations(operations + check(
                        "var_exists(TA.EPOCH) and " + misplacedGuard + " and EPOCH == $ta_epoch")),
                "an equality is false against an absent column — no flood, no mitigation");

        // De Morgan: not var_not_exists(TA.EPOCH) in guard position IS a presence assertion.
        assertEquals(Map.of("DM", true),
                mitigations(operations + check("not var_not_exists(TA.EPOCH) and " + misplacedGuard
                        + " and " + membership)),
                "`not var_not_exists` implies presence, so it mitigates exactly as `exists` does");
    }


    /**
     * Finding T10: a guard hoisted into {@code Requirements} mitigates exactly as the {@code Check}
     * leaf it replaced. Same shape as {@link #theMitigationDerivationIsStructuralNotAList()}, with
     * the {@code TA} guard moved out of the {@code Check}; only the facets that imply that one
     * dataset is present may count.
     */
    @Test
    void aGuardHoistedIntoRequirementsMitigates()
    {
        String operations = """
                Bindings:
                - name: "$ta_epoch"
                  expression: 'distinct(EPOCH, domain=\"TA\")'
                """;
        String flooding = check(
                "(var_not_exists(DM.DTHFL) or DM.DTHFL == \"Y\") and EPOCH not in $ta_epoch");

        assertEquals(Map.of("DM", true), mitigations(operations + """
                Requirements:
                  Variables:
                    All:
                    - "TA.EPOCH"
                """ + flooding),
                "a qualified Requirements.Variables.All entry on the dangerous dataset skips the"
                        + " rule when TA.EPOCH is absent — the flood route is closed");
        assertEquals(Map.of("DM", true), mitigations(operations + """
                Requirements:
                  Datasets:
                  - "TA"
                """ + flooding),
                "a Requirements.Datasets entry on the dangerous dataset closes the route too");

        // The facets that do NOT imply the one dataset's presence must not mitigate.
        assertEquals(Map.of("DM", false), mitigations(operations + """
                Requirements:
                  Variables:
                    Any:
                    - "TA.EPOCH"
                    - "EPOCH"
                """ + flooding), "an Any facet is satisfied by a sibling — no guard");
        assertEquals(Map.of("DM", false), mitigations(operations + """
                Requirements:
                  Variables:
                    All_Or_None:
                    - - "EPOCH"
                      - "TA.EPOCH"
                """ + flooding), "a group runs the rule with both sides absent — no guard");
        assertEquals(Map.of("DM", false), mitigations(operations + """
                Requirements:
                  Variables:
                    All:
                    - "DM.DTHFL"
                """ + flooding),
                "a hoisted guard on the flagged unit's own dataset leaves the TA route open");
    }


    /** The {@code dataset -> mitigated} map of one synthetic rule body's DISJUNCT leaves. */
    private static Map<String, Boolean> mitigations(String body)
    {
        Map<String, Boolean> byDataset = new LinkedHashMap<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(parse("""
                Core:
                  Id: "SYNTHETIC-0002"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Match_Datasets:
                - Name: "DM"
                  Keys:
                  - "USUBJID"
                """ + body)))
        {
            if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
            {
                byDataset.put(leaf.dataset(), leaf.mitigated());
            }
        }
        return byDataset;
    }

    // ------------------------------------------------------------------
    // Refuted readings — kept live, not merely described
    // ------------------------------------------------------------------


    /**
     * The <b>reads-elsewhere</b> clause, refuted in the direction it corrects. Dropping it —
     * classifying on position and foreignness alone — turns {@code CDISC-SEND-0400}'s three
     * {@code ds_not_exists} assertions into "wrongly-positioned guards", which they are not: the
     * rule reads none of {@code TT}/{@code TP}/{@code SJ}, so there is nothing there to guard.
     * Measured 2026-08-12: <b>3</b> spurious bucket-(b) leaves and <b>77</b> spurious bucket-(a)
     * ones.
     */
    @Test
    void theNaiveReadingWithoutTheReadsElsewhereClauseOverCounts() throws IOException
    {
        List<Rule> corpus = ruleFiles().stream().map(GuardPositionLintTest::read).toList();
        int kept = 0;
        int naive = 0;
        Set<String> naiveDefects = new TreeSet<>();
        for (Rule rule : corpus)
        {
            kept += GuardPositionAudit.presenceLeaves(rule).size();
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit
                    .presenceLeavesIgnoringReadsElsewhere(rule))
            {
                naive++;
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    naiveDefects.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        // Re-derived by run 2026-08-13 after Fix #246: the five new TV guards are foreign
        // presence leaves in both readings, so 49 → 54 and 129 → 134. Re-derived again by run
        // 2026-08-17 after D-TA-6a re-authored FDA-SD1023 onto PMDA-SD1023's no-join shape: its
        // `not var_exists(SV.SVPRESP)` leaf is gone (its TV guard stays), which is one leaf in
        // BOTH readings, so 54 → 53 and 134 → 133. Re-derived again by run 2026-08-18 after
        // CL-G.1 / EC-82 option (a) (wave TA-4) gave PMDA-SD1023 the same `var_exists(TV.VISIT)`
        // guard, completing the Fix #246 family: one new foreign presence leaf in both readings,
        // so 53 → 54 and 133 → 134. Re-derived by run 2026-08-22: the four `ds_exists("TV")`
        // rules minted in 51dc809e9 (CDISC-CG9671, CORE-009705, FDA-SD9713, PMDA-SD9713) add
        // four leaves and the deleted DRAFT-110005 removes one, so 134 → 137. ⚠ The wave-TA-6
        // note previously credited this to the `var_exists("ECDOSE") and empty(ECDOSE)`
        // re-spelling; that was wrong — an undotted `var_exists` name resolves to no dataset
        // (datasetOf returns null), so those leaves were never counted, and Fix #340's removal
        // of them moved neither reading. `kept` stays 54.
        // Re-derived by run 2026-08-24, PLAN-scope-requirements-split phase 4c: the ten POS
        // `ds_exists` preconditions (CDISC-AD0061, CDISC-AD0365, PMDA-AD0061, PMDA-AD0061A on EX;
        // CDISC-CG0105, CDISC-CG0107, CORE-000291, CORE-000292, PMDA-SD1468, PMDA-SD1469 on EC)
        // moved their guard out of the Check and into `Requirements: {Datasets: [...]}`, so ten
        // foreign presence leaves left the corpus: 137 → 127. ⭐ `kept` does NOT move and stays
        // 54 — the reads-elsewhere clause already excluded all ten (none of those rules reads EX
        // or EC anywhere else), which is exactly the asymmetry this control exists to expose.
        // ⚑ The 4d′ redundant-OR wipe of the same wave moved NEITHER count: all 237 stripped
        // `not var_exists("X")` disjuncts name an UNDOTTED column (measured: 0 of 237 dotted), and
        // an undotted name resolves to no dataset (datasetOf returns null), so those leaves were
        // never counted in either reading.
        // 2026-08-26 (PLAN-retire-corpus-transforms phase 4): 54 → 55. The retired corpus
        // guard pass's `var_exists(ADSL.&VAR&)` on CDISC-AD0591 is now AUTHORED (the loader's
        // R-gate forbids hoisting an expansion token into Requirements.Variables.All), and a
        // dotted authored presence leaf whose rule reads the same foreign column elsewhere is
        // exactly what `kept` counts.
        // 2026-09-07 (R-5.25 split of CDISC-CG0033): 55 → 57. The rule's SV leaf moved from a
        // disjunct to a top-level `var_exists(SV.SVPRESP)`, and the minted sibling
        // CDISC-CG0033-A carries its own — two dotted authored presence leaves whose rules read
        // the same foreign column elsewhere, which is what `kept` counts.
        // 2026-09-08: 63 -> 62. CORE-000709 lost its var_exists(CE.CEDECOD) -- guard-injector
        // residue, redundant because `== "literal"` is already false on an absent column.
        // 2026-09-08 (R-4.12 hoist): 62 -> 37, the 22 leaves that moved into Requirements.
        // 2026-09-10 (R-4.12 hoist, phase 2 of PLAN-qualified-requirements-cross-standard): 37 ->
        // 18. Twenty-five rules hoisted, of which NINETEEN were counted here — and the six that
        // were not are CDISC-AD0640..AD0645, whose `var_exists(AE.*)` was their rule's ONLY
        // reference to AE, so the reads-elsewhere clause never counted them. ⭐ That is the same
        // six-rule asymmetry §8.3 of the plan measured from the other side, which is why the
        // arithmetic 25 - 19 = 6 is a cross-check on the finding rather than a coincidence.
        // 2026-09-17 (PLAN-typed-expression-engine D110f/D114/D118): 18 -> 17. PMDA-AD0258's
        // `var_exists(AE.AESEQ)` left the Check when the rule was re-authored onto the phase-5b-J
        // join match flag (`not AE._matched_`) and its presence test became a DECLARATION,
        // `Requirements.Variables.All: [AE.AESEQ]`. It is the only one of the four rewritten
        // rules that carried a presence leaf at all — the other three spelled a bare `empty(...)`
        // — and it was counted under BOTH readings, because the rule read AE elsewhere in the
        // same Check (`empty(AE.AESEQ)`), which is exactly what the reads-elsewhere clause keeps.
        // 17 -> 25 on 2026-09-18 (PLAN-unplanned-visit-cascade): the six adjudicated cascade
        // rules author new foreign presence leaves below their or (var_exists(SV.SVPRESP), twice
        // per SD1023 via the empty(SV.USUBJID) discriminator arm). Re-measured, not derived.
        // 25 -> 21 the same day, when the plan's phase 3 was REVERTED: FDA/PMDA-SD1023 lose the
        // SV join again (it flooded VISIT-less domains), taking two leaves each with it. The
        // four remaining leaves are the CG0031/CG0033/CORE-000269/000270 cascade. Re-measured.
        // 21 -> 17 (PLAN-retire-core-family phase 6): the four CORE-000269/-000270 cascade
        // leaves sat inside this count. An EXACT equality; see EXPECTED_DEFECTS above.
        // 17 -> 15 on 2026-10-04 (PLAN-dynamic-column-corpus-respelling phase 1): CDISC-AD0706 /
        // AD0707 no longer carry a dotted presence leaf. Their `var_exists(ADSL.P${…}S${*})` is
        // respelled as `count(find_vars($p_s)) == 0` over a bound computed entry (the R-5.31
        // spelling), and find_vars is not a presence call. They were the only dotted presence
        // leaves among the 35 respelled rules (S1 carries none; S4's var_exists("APHASEN") is
        // undotted and was never counted), so both readings lose exactly these two.
        // 15 -> 8 on 2026-10-04 (PLAN-dynamic-column-corpus-respelling §6.2, the S2 round): the
        // seven S2 rules (CDISC-AD0102 / 0103 / 0104 / 0498, PMDA-AD0102 / 0103 / 0104) each
        // carried one dotted presence leaf, `var_exists(ADSL.TRT${APERIOD:%02d}P)` and its
        // siblings; respelled as `var_exists($n)` over a bound computed name, the dataset is no
        // longer statically visible, exactly as for the S3 find_vars leaves above. They are the
        // only rule files the round changed, so both readings lose exactly these seven.
        // 8 -> 10 on 2026-10-05 (PLAN-merged-evaluation-table W4, MQ1): CDISC-CG0043 / FDA-SD1143
        // read their Child parent qualified, var_exists("AE.AESMIE") and AE.AESMIE, so their
        // presence leaf is now a dotted foreign guard that reads AE elsewhere (it was the bare
        // var_exists("AESMIE") of the pre-merged column, never counted).
        assertEquals(10, kept, "the predicate's leaf count moved");
        // 2026-08-26 (PLAN-retire-corpus-transforms phase 4): 127 → 128 — the same authored
        // `var_exists(ADSL.&VAR&)` leaf that moved `kept` 54 → 55 (see above) is a dotted
        // presence leaf under the naive reading too.
        // 2026-09-07 (R-5.25 split of CDISC-CG0033): 128 -> 130, the same two dotted SV presence
        // leaves that moved `kept` 55 -> 57 — they are dotted under the naive reading too.
        // 2026-09-08 (D-3): 130 -> 136, the same six dotted SV leaves that moved `kept` 57 -> 63.
        // 2026-09-08: 136 -> 135, CORE-000709's removed leaf under the naive reading too.
        // 2026-09-08 (R-4.12 hoist): 135 -> 110, the same 22 leaves under the naive reading.
        // 2026-09-10 (R-4.12 hoist, phase 2): 110 -> 85 — exactly 25, one per hoisted rule. The
        // naive reading has no reads-elsewhere clause, so unlike `kept` above it counts the six
        // AD0640..AD0645 leaves too, and 25 is the whole hoist. The two deltas differing by
        // precisely those six is the cross-check.
        // 2026-09-17 (D110f/D114/D118): 85 -> 84, the same single PMDA-AD0258 leaf. It is dotted,
        // so the naive reading counted it too, and the two deltas being equal is the cross-check:
        // unlike the R-4.12 hoists above, this removal has no reads-elsewhere asymmetry to expose.
        // 84 -> 92 on 2026-09-18 (PLAN-unplanned-visit-cascade), with the kept count 17 -> 25
        // above: the same eight new below-any presence leaves, counted by both readings.
        // 92 -> 88 with the phase-3 revert, tracking kept 25 -> 21: the four SD1023 leaves are
        // dotted, so both readings lose exactly the same four. Re-measured.
        // 88 -> 76 (PLAN-retire-core-family phase 6): an EXACT equality over the naive
        // reading's leaf count; the CORE family held 12 of the 88, four of them the
        // CORE-000269/-000270 cascade leaves that also left EXPECTED_DEFECTS and the `21 kept`
        // count above. ⭐ The control still controls: the whole point of this assertion is that
        // the naive reading over-counts against `kept`, and 76 > 17 by the same wide margin
        // 88 > 21 gave.
        // 76 -> 80 on 2026-09-22 (PLAN-unplanned-visit-sv-match-tier): the four `-B` siblings'
        // ds_exists("SV") leaves. ⭐ `kept` (17) did NOT move with them.
        // ⛔ CORRECTED at review: the first version of this comment said that was because each sits
        // in a CONJUNCT rather than a disjunct. That is not the mechanism -- `kept` is
        // presenceLeaves(rule).size() and is POSITION-INDEPENDENT (Position.DISJUNCT is filtered
        // separately, and only for naiveDefects). Moving these guards into a disjunct would not
        // move `kept` by one. The actual reason is the READS-ELSEWHERE clause, the CDISC-SEND-0400
        // case this class's own javadoc describes: a `-B` rule mentions SV NOWHERE except inside
        // ds_exists("SV"), so there is no foreign read for the leaf to be guarding. The 80-vs-17
        // gap is what keeps the refutation non-vacuous.
        // 80 -> 78 on 2026-10-04 (PLAN-dynamic-column-corpus-respelling phase 1), tracking kept
        // 17 -> 15 above: the same two AD0706 / AD0707 leaves, dotted under the naive reading too.
        // 78 -> 71 on 2026-10-04 (§6.2, the S2 round), tracking kept 15 -> 8 above: the same seven
        // S2 leaves, dotted under the naive reading too.
        // 71 -> 73 on 2026-10-05 (W4 of PLAN-merged-evaluation-table), tracking kept 8 -> 10
        // above: the same two respelled AE.AESMIE leaves, dotted under the naive reading too.
        // 73 -> 74 on 2026-10-12 (rule round 2026-10-12, owner JKAG-A): the minted FDA-SD9751's
        // not ds_exists("TI") leaf. `kept` did NOT move: as with the four `-B` siblings above, the
        // rule reads TI nowhere else, so there is no foreign read for the leaf to guard. Measured:
        // with FDA-SD9751 removed the count is 73 again.
        assertEquals(74, naive, "the naive reading's leaf count moved");

        Set<String> spurious = new TreeSet<>(naiveDefects);
        spurious.removeAll(EXPECTED_DEFECTS.keySet());
        // ⚠ 2026-09-12 (top-level-or wave 3, R-5.25 group K): the control set went to EMPTY by a
        // RULED corpus change, not by the sweep going blind — the CDISC-SEND-0400 split moved
        // each of its three ds_not_exists leaves from a DISJUNCT position to being the WHOLE
        // Check of its own rule (parent TT, -A TP, -B SJ), so no foreign presence leaf sits in a
        // disjunct anywhere in the corpus any more. `kept` (18) and `naive` (85) did not move —
        // the three leaves still exist and are still counted by the naive reading, which is what
        // keeps this refutation test non-vacuous: the reads-elsewhere clause is still exercised
        // by the 85-vs-18 gap above. The DISJUNCT-position exhibit the corpus lost is restored
        // synthetically by theReadsElsewhereClauseChangesADisjunctPositionVerdict() — a count gap
        // cannot show the clause changing a VERDICT, which is what the exhibit was for. If a
        // future edit brings a foreign presence leaf back into a disjunct, this set going
        // non-empty is exactly the signal it was built to give.
        assertEquals(Set.of(), spurious,
                "the naive reading's false positives moved. They are a control, not a"
                        + " target: a NEW entry here is a foreign presence leaf authored into a"
                        + " disjunct — read the 2026-09-12 note above before touching this pin.");
    }


    /**
     * The <b>reads-elsewhere</b> clause, exhibited on a {@code DISJUNCT}-position verdict rather
     * than only on the {@code 85}-vs-{@code 18} count gap above.
     *
     * <p>
     * &#9888;&#9888; <b>Why this test exists.</b> Until 2026-09-12 the clause's live exhibit was
     * {@code CDISC-SEND-0400}: three {@code ds_not_exists} leaves under a top-level {@code any}
     * that the naive reading called wrongly-positioned guards and the real predicate correctly did
     * not, because the rule reads none of {@code TT}/{@code TP}/{@code SJ}. The {@code R-5.25}
     * wave-3 split made each leaf the whole {@code Check} of its own rule, so no corpus rule
     * exercises the clause <em>on a disjunct-position verdict</em> any more &mdash; the control set
     * in {@link #theNaiveReadingWithoutTheReadsElsewhereClauseOverCounts()} went legitimately
     * empty, and what it lost was the demonstration, not the ratchet. This restores it
     * synthetically, where no corpus edit can take it away again.
     * </p>
     *
     * <p>
     * The two bodies are the <b>same shape in the same position</b> and differ in one thing only:
     * whether the rule reads the foreign dataset anywhere else. So a verdict difference between
     * them can be attributed to the clause and to nothing else &mdash; which a pair of counts
     * cannot do.
     * </p>
     */
    @Test
    void theReadsElsewhereClauseChangesADisjunctPositionVerdict()
    {
        String ownAssertion = """
                Core:
                  Id: "SYNTHETIC-OWN-ASSERTION"
                Scope:
                  Domains:
                    Include:
                    - "TS"
                Check:
                  expression: '(ds_not_exists("TT") or ds_not_exists("TP")) and not empty(TSVAL)'
                """;

        // The naive reading sees two foreign presence leaves below an `any` and calls both
        // wrongly-positioned guards. This is the over-count the clause corrects, and asserting the
        // POSITION as well as the ids is what keeps the exhibit non-vacuous: a reading that stopped
        // finding the leaves at all would also produce an empty real-predicate set below.
        assertEquals(Set.of("SYNTHETIC-OWN-ASSERTION|TP", "SYNTHETIC-OWN-ASSERTION|TT"),
                naiveDisjuncts(ownAssertion),
                "the naive reading no longer flags a foreign presence leaf authored into a"
                        + " disjunct — the over-count this clause exists to correct is gone, so"
                        + " the refutation below proves nothing");
        assertEquals(Set.of(), realDisjuncts(ownAssertion),
                "the reads-elsewhere clause has stopped excluding a presence leaf that is the"
                        + " rule's OWN assertion: the rule reads neither TT nor TP, so there is"
                        + " nothing there to guard and neither leaf is a misplaced guard");

        // One leaf substantively read elsewhere (`TT.TTVAL`), everything else identical: the SAME
        // leaf in the SAME disjunct position is now a real finding. `TP` stays excluded, so the
        // clause is shown discriminating WITHIN one rule and not merely switching off.
        String readsElsewhere = ownAssertion.replace("not empty(TSVAL)", "not empty(TT.TTVAL)");
        assertEquals(Set.of("SYNTHETIC-OWN-ASSERTION|TP", "SYNTHETIC-OWN-ASSERTION|TT"),
                naiveDisjuncts(readsElsewhere),
                "the naive reading is blind to reads-elsewhere by"
                        + " construction, so its verdict must not move between the two bodies —"
                        + " that invariance is what isolates the clause as the only difference");
        assertEquals(Set.of("SYNTHETIC-OWN-ASSERTION|TT"), realDisjuncts(readsElsewhere),
                "a foreign presence leaf in a disjunct, whose dataset the Check DOES read"
                        + " substantively, must be flagged — it is a guard authored into a position"
                        + " that does not guard, which is the whole deliverable of this lint");
    }


    /** The {@code DISJUNCT}-position keys of one synthetic rule body under the real predicate. */
    private static Set<String> realDisjuncts(String body)
    {
        return defectKeys(List.of(parse(body)));
    }


    /** The same, under the naive reading that drops the reads-elsewhere clause. */
    private static Set<String> naiveDisjuncts(String body)
    {
        Set<String> keys = new TreeSet<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit
                .presenceLeavesIgnoringReadsElsewhere(parse(body)))
        {
            if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
            {
                keys.add(leaf.ruleId() + '|' + leaf.dataset());
            }
        }
        return keys;
    }


    /**
     * <b>The lint can go red.</b> A guard authored into the wrong position in a rule that is
     * <em>not</em> in {@link #EXPECTED_DEFECTS} must break the ratchet. Asserted against the real
     * corpus plus one injected rule, so it exercises the same comparison the deliverable does — a
     * lint that cannot red is the vacuity this project keeps catching.
     */
    @Test
    void aNewlyIntroducedWronglyPositionedGuardRedsTheRatchet() throws IOException
    {
        List<Rule> corpus = new ArrayList<>(
                ruleFiles().stream().map(GuardPositionLintTest::read).toList());
        assertEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), defectKeys(corpus),
                "baseline: the untouched corpus matches the pin");

        corpus.add(parse("""
                Core:
                  Id: "SYNTHETIC-NEW-DEFECT"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Match_Datasets:
                - Name: "DM"
                  Keys:
                  - "USUBJID"
                Check:
                  expression: '(var_not_exists(DM.DTHFL) or DM.DTHFL == "Y") and not empty(AETERM)'
                """));
        Set<String> withDefect = defectKeys(corpus);
        assertTrue(withDefect.contains("SYNTHETIC-NEW-DEFECT|DM"),
                "the injected wrongly-positioned guard was not detected at all");
        assertNotEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), withDefect,
                "the ratchet did not move when a new wrongly-positioned guard was introduced");
    }

    // ------------------------------------------------------------------
    // Synthetic controls — one per clause of the classification
    // ------------------------------------------------------------------


    /**
     * The positional classification, clause by clause. Neuter any arm of
     * {@link GuardPositionAudit}'s walk and at least one of these flips.
     */
    @Test
    void everyPositionalShapeIsClassifiedCorrectly()
    {
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD),
                positions(check("var_exists(DM.DTHFL) and DM.DTHFL == \"Y\"")),
                "a top-level conjunct guards");

        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT),
                positions(
                        check("(var_exists(DM.DTHFL) or not empty(AETERM)) and DM.DTHFL == \"Y\"")),
                "a unit below a disjunction does not guard — the whole point of the lint");

        // ⚑ Conjunction is associative. A "direct child of the root `and`" test false-positives
        // here, which is why the walk carries an implication flag rather than a depth. The
        // nested conjunction is built structurally: the printer/parser pair flattens n-ary
        // `and`, so the nested shape arises from wrappers (e.g. the scaffold's all:[…]) rather
        // than from text — exactly why the arm must not rot.
        Rule nested = parse("""
                Core:
                  Id: "SYNTHETIC-0001"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Check:
                  expression: 'AETERM == "X"'
                """);
        Expr inner = new Expr.And(List.of(CheckExpressionParser.parse("var_exists(DM.DTHFL)"),
                CheckExpressionParser.parse("not empty(AETERM)")));
        Expr root = new Expr.And(List.of(inner, CheckExpressionParser.parse("DM.DTHFL == \"Y\"")));
        nested.setCheck(new CheckConditionExpression(root,
                "(var_exists(DM.DTHFL) and not empty(AETERM)) and DM.DTHFL == \"Y\""));
        Map<String, GuardPositionAudit.Position> nestedPositions = new LinkedHashMap<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(nested))
        {
            nestedPositions.put(leaf.dataset(), leaf.position());
        }
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), nestedPositions,
                "a nested conjunction still guards");
    }


    /**
     * {@code not} is not {@code any}, in both directions. &#9888; The authored corpus has
     * <b>zero</b> {@code not:} nodes, so without these two synthetics the De Morgan arm is dead
     * code that would rot unnoticed and then mis-classify the first rule authored with one.
     */
    @Test
    void theNegationArmFollowsDeMorganInBothDirections()
    {
        // ¬(a ∨ b) ≡ ¬a ∧ ¬b — the unit IS implied by the check, in negated form, so it guards.
        Map<String, GuardPositionAudit.Position> underNotAny = positions(
                check("not (var_exists(DM.DTHFL) or not empty(AETERM)) and DM.DTHFL == \"Y\""));
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), underNotAny,
                "`not (a or b)` is a conjunction — its units guard");

        // ¬(a ∧ b) ≡ ¬a ∨ ¬b — a disjunction, so the unit does not guard.
        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT),
                positions(check(
                        "not (var_exists(DM.DTHFL) and not empty(AETERM)) and DM.DTHFL == \"Y\"")),
                "`not (a and b)` is a disjunction — its units do not guard");
    }


    /**
     * Foreignness and the presence-operator set. Each of these must produce <b>no</b> finding, and
     * each corresponds to one way the predicate could silently widen.
     */
    @Test
    void nonForeignAndNonPresenceLeavesAreNotFindings()
    {
        assertEquals(Map.of(), positions(check("var_exists(AE.AETERM) or not empty(AETERM)")),
                "AE is the rule's own Scope.Domains.Include — not foreign");

        assertEquals(Map.of(),
                positions(
                        check("(not empty(DM.DTHFL) or not empty(AETERM)) and DM.DTHFL == \"Y\"")),
                "non_empty is not a presence operator — absence and emptiness differ");

        assertEquals(Map.of(), positions(check("var_exists(DM.DTHFL) or not empty(AETERM)")),
                "nothing else reads DM, so the unit is the rule's assertion, not a guard");
    }


    /**
     * The {@code $}-id arm: a presence leaf on an operation result takes its dataset from the
     * operation's {@code domain:}, and a domainless operation is local. &#9888; This is the arm the
     * {@code IntentAbsenceOptOut} predicate's own plan omitted, at the cost of finding none of the
     * four rules it claimed to return.
     */
    @Test
    void theOperationIdArmResolvesThroughTheOperationsDomain()
    {
        assertEquals(Map.of("TS", GuardPositionAudit.Position.DISJUNCT),
                positions("""
                        Bindings:
                        - name: "$ts_species"
                          expression: 'read_value(TSVAL, domain=TS, mode=\"FIRST\")'
                        """ + check("(var_exists($ts_species) or not empty(AETERM))"
                        + " and not empty($ts_species)")),
                "a $-id resolves to its binding's domain= (read_value, runbook W2a)");

        assertEquals(Map.of(),
                positions("""
                        Bindings:
                        - name: "$ts_species"
                          expression: 'upper(TSVAL)'
                        """ + check("(var_exists($ts_species) or not empty(AETERM))"
                        + " and not empty($ts_species)")),
                "a binding reading no other dataset is local, so there is no foreign surface");
    }


    /**
     * {@code ds_exists} takes a <b>dataset</b> name, and {@link GuardPositionAudit} must resolve it
     * as one rather than looking for a dot. This is also the evidence behind the note for the
     * owner: a dataset-level guard <em>is</em> writable for a bucket-(c) surface, so "no guard is
     * expressible" overstates the gap — only the column-level form is missing.
     */
    @Test
    void theDatasetPresenceOperatorsResolveTheirNameAsTheDataset()
    {
        assertEquals(Map.of("TA", GuardPositionAudit.Position.GUARD), positions("""
                Bindings:
                - name: "$ta_epoch"
                  expression: 'distinct(EPOCH, domain=\"TA\")'
                """ + check("ds_exists(\"TA\") and EPOCH not in $ta_epoch")),
                "ds_exists names the dataset directly and guards the operation's surface");
    }

    // ------------------------------------------------------------------
    // Raise-side predicate shapes, and partial opacity — successors of the Fix #256 arm
    // ------------------------------------------------------------------


    /**
     * The raise-side regex predicates ({@code is_numeric} et al.) classify as the
     * {@code matches_regex} they were raised from, so a rule authored with one still audits — in
     * both positions. (Successor of the {@code Fix #256} legacy-vs-expression twin test: the legacy
     * operator-leaf form is unauthorable since D121, so the expression halves ARE the contract
     * now.)
     */
    @Test
    void raiseSidePredicateShapesAreAuditedInBothPositions()
    {
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD),
                positions(check("var_exists(DM.DTHFL) and is_numeric(DM.AGE)")),
                "an is_numeric read must classify (as its matches_regex origin) and count as a"
                        + " read, or the audit silently skips the shape");

        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT),
                positions(check(
                        "(var_not_exists(DM.DTHFL) or is_numeric(DM.AGE)) and not empty(DM.AGE)")),
                "a misplaced guard beside a raise-side predicate must still be flagged");
    }


    /**
     * The {@code collectDangerous} / mitigation arm over the same shapes: a vacuous-on-empty
     * membership test and its closing guard derive {@code mitigated == true} with an
     * {@code is_numeric} rider present.
     */
    @Test
    void membershipBesideARaiseSidePredicateDerivesMitigation()
    {
        assertEquals(Map.of("DM", true),
                mitigations("""
                        Bindings:
                        - name: "$ta_epoch"
                          expression: 'distinct(EPOCH, domain=\"TA\")'
                        """ + check("var_exists(TA.EPOCH) and (var_not_exists(DM.DTHFL) or"
                        + " is_numeric(DM.AGE)) and EPOCH not in $ta_epoch")),
                "the TA guard and the vacuous $ta_epoch membership must derive mitigation");
    }


    /**
     * ⭐ A construct with no v1 classification no longer blinds the audit — the direct-{@code Expr}
     * walk emits it as an opaque unit and keeps auditing everything around it. (Successor of the
     * fail-loud {@code Fix #256} arm: "cannot read this Check" is impossible by construction now,
     * so the contract it protected — a native-only shape must not silently leave the population —
     * is met one level stronger, by never leaving the population in the first place.)
     */
    @Test
    void aNativeOnlyConstructDoesNotBlindTheWalk()
    {
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD),
                positions(check(
                        "var_exists(DM.DTHFL) and between(char(DM.AGE), char(\"B\"), char(\"Z\"))"
                                + " and not empty(DM.AGE)")),
                "units beside an unclassifiable call must still be audited — an empty map is"
                        + " the pre-phase-7 silent skip in its modern costume");
    }


    /** Wraps a check expression in the YAML block a synthetic rule body carries. */
    private static String check(String expression)
    {
        return "Check:\n  expression: '" + expression.replace("'", "''") + "'\n";
    }


    /** The {@code dataset -> position} map of one synthetic rule body. */
    private static Map<String, GuardPositionAudit.Position> positions(String body)
    {
        Map<String, GuardPositionAudit.Position> byDataset = new LinkedHashMap<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(parse("""
                Core:
                  Id: "SYNTHETIC-0001"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                """ + body)))
        {
            byDataset.put(leaf.dataset(), leaf.position());
        }
        return byDataset;
    }
}
