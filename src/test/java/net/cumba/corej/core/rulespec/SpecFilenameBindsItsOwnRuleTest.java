package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.rulespec.SpecCorpusCensus.Row;
import net.cumba.corej.core.rulespec.SpecCorpusCensus.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ A rulespec's <b>filename</b> must agree with the rule it <b>binds</b>.
 *
 * <p>
 * ⛔⛔ <b>Why this exists: a name/key disagreement in a rulespec was detected by NO instrument, and
 * one shipped.</b> {@code PLAN-retire-core-family} phase 1 re-keyed 416 specs onto surviving twins
 * and landed one — {@code CORE-000852}'s — as {@code CDISC-CG0330.yaml} while it bound
 * {@code CDISC-CG0664}, because the new filename had been computed before the spec was retargeted.
 * <b>Nothing reddened.</b> {@link RuleExecutionSpecTest} resolves the <em>binding</em> and never
 * looks at the name; the internal twin's {@code SpecCorpusPopulationRatchetTest} roster records
 * <em>both</em> and is satisfied by any pair it is told to expect, so committing the wrong pair
 * makes it green (that roster is internal-only; this guard is not). It was caught by a hand audit,
 * which is not an instrument. Fixed in {@code 13a46906}; this test is what makes the next one
 * impossible.
 * </p>
 *
 * <p>
 * The rule is a <em>prefix</em> test, not equality, because a rule legitimately carries several
 * specs distinguished by a suffix ({@code CDISC-CG0148-offset.yaml},
 * {@code FDA-SD1325-empty.yaml}). A prefix is enough: the failure class is a stem naming a
 * <em>different rule</em>, and no qualifier suffix can make {@code CDISC-CG0330} a prefix of
 * {@code CDISC-CG0664}.
 * </p>
 *
 * <p>
 * ⛔ <b>The exceptions are pinned as an EXACT SET, never as a count and never as a prefix
 * allowlist.</b> An allowlist keyed on {@code EC*}/{@code rule_*} would let any future spec adopt
 * one of those prefixes and leave the guard silently — a blind allowlist is this tree's dominant
 * defect class. Pinning path → bound id exactly means an added exception, a removed one,
 * <em>and</em> an existing one whose binding moves are all red. Measured 2026-09-19 over THIS
 * repository's 971 specs: <b>16</b> — fifteen deliberate ones plus one that is not.
 * </p>
 *
 * <p>
 * The fifteen are the engine-case fixtures, which are named for the <em>engine behaviour</em> they
 * probe rather than for the corpus rule they happen to drive, and the six {@code rule_*} authoring
 * pilots. ⚑ One of them arrived with the CORE-family retirement:
 * {@code EC-variable-count-split-family-dedup.yaml} was renamed off its old {@code CORE-*} stem
 * (its {@code $ref} body probes engine behaviour and its id is in no corpus family), so its name
 * now deliberately says nothing about the id it binds.
 * </p>
 *
 * <p>
 * ⛔⛔ <b>The sixteenth, {@code AD0180-srcdom-companion-domains.yaml} → {@code CDISC-AD0180}, is NOT
 * a deliberate exception — it is the shortfall this guard is FOR, and it is pinned only so that the
 * guard could be armed at all.</b> It is an ordinary rule spec whose stem simply drops the family
 * prefix, and it is <b>OSS-only</b>: its internal counterpart does not exist, while every one of
 * its OSS-only siblings ({@code CDISC-AD0152}, {@code CDISC-AD0165}, {@code CDISC-AD0168},
 * {@code PMDA-AD0165}, {@code PMDA-AD0168}) carries the prefix. ⇒ <b>The repair is to RENAME the
 * file</b> to {@code CDISC-AD0180-srcdom-companion-domains.yaml}, which also needs its
 * {@code oss-only} row in the meta repo's {@code oss-identity-exclusions.tsv} re-pointed — a
 * two-repo edit, and the spec belongs to {@code PLAN-typed-engine-oss-port.md}, so it is SURFACED
 * here rather than done in passing. The exact-set equality makes that self-closing: the day it is
 * renamed this entry reds and must go.
 * </p>
 *
 * <p>
 * ⭐ <b>PORTED from the internal twin 2026-09-19</b>, with its exception set RE-DERIVED against this
 * corpus rather than copied — 16 here against 19 there, and the difference is measured, not nudged:
 * the four {@code EC46-*} specs are internal-only (their OSS port is owed by
 * {@code PLAN-typed-engine-oss-port.md}) and {@code AD0180-srcdom-companion-domains.yaml} is
 * OSS-only, so 19 − 4 + 1 = 16. ⛔ Never copy the internal list: the two corpora are different
 * populations and a set copied rather than measured is an allowlist wearing a set's clothes.
 * </p>
 */
class SpecFilenameBindsItsOwnRuleTest
{

    /**
     * ⛔ Path (relative to {@code rulespec/specs}) → the rule it binds, for every spec whose stem
     * deliberately does not start with its bound rule id. Exact set; see the class javadoc.
     */
    private static final Map<String, String> EXPECTED_EXCEPTIONS = Map.ofEntries(
            Map.entry("AD0180-srcdom-companion-domains.yaml", "CDISC-AD0180"),
            Map.entry("EC-variable-count-split-family-dedup.yaml",
                    "EC-VARIABLE-COUNT-SPLIT-FAMILY-DEDUP"),
            Map.entry("EC18-date-diff-mode3-sided.yaml", "EC18-DATEDIFF-MODE3"),
            Map.entry("EC21-date-diff-target-from-evaluation-domain.yaml", "EC21-DATEDIFF-TARGET"),
            Map.entry("EC43-not-equal-absent.yaml", "AUTO-EC43-not-equal-absent"),
            Map.entry("EC51-CG0147-indeterminate-extreme.yaml", "CDISC-CG0147"),
            Map.entry("EC51-SE2276-indeterminate-extreme.yaml", "FDA-SE2276"),
            Map.entry("EC51-SEND-0306-indeterminate-extreme.yaml", "CDISC-SEND-0306"),
            Map.entry("EC67-dataset-domain-fork-cannot-resolve.yaml", "CDISC-CG0308"),
            Map.entry("Q16-partial-date-comparison-hull.yaml", "Q16-DATE-HULL"),
            Map.entry("rule_cross_dataset_dm_ae_usubjid.yaml", "PILOT-DM-AE-USUBJID"),
            Map.entry("rule_library_mocks_valid_ct_dates.yaml", "PILOT-VALID-CT-DATES"),
            Map.entry("rule_simple_dm_age_below_18.yaml", "PILOT-AGE-LT-18"),
            Map.entry("rule_standard_domains_srcdom.yaml", "EC14-STANDARD-DOMAINS"),
            Map.entry("rule_variable_names_supp_qnam.yaml", "EC13-VARIABLE-NAMES"),
            Map.entry("rule_with_operation_record_count.yaml", "PILOT-AE-RECORD-COUNT"));

    /**
     * Floor on the population this guard actually inspected, so a walk that saw almost nothing
     * cannot pass vacuously. <b>947 rule-bound specs measured here on 2026-09-19</b>, of 971
     * walked. It is a floor and not a pin — the exact population is the internal twin's
     * {@code SpecCorpusPopulationRatchetTest}'s job, and this repository has no roster artifact for
     * it to read ({@code documentation/} does not exist here).
     */
    private static final int MIN_SPECS_NAMING_A_RULE = 800;

    private static Snapshot snapshot;

    @BeforeAll
    static void take() throws IOException
    {
        snapshot = SpecCorpusCensus.snapshot();
    }


    /** Every spec's filename stem starts with the rule id the spec binds — 16 exceptions apart. */
    @Test
    void everySpecFilenameStartsWithTheRuleItBinds()
    {
        List<String> inspected = new ArrayList<>();
        List<String> disagreeing = new ArrayList<>();
        for (Map.Entry<String, Row> e : snapshot.specs().entrySet())
        {
            String path = e.getKey();
            String rule = e.getValue().rule();
            if (SpecCorpusCensus.NO_RULE.equals(rule))
            {
                continue;
            }
            inspected.add(path);
            if (!stemBindsItsRule(path, rule))
            {
                disagreeing.add(path + " binds " + rule);
            }
        }

        assertTrue(inspected.size() >= MIN_SPECS_NAMING_A_RULE,
                "only " + inspected.size() + " rule-bound specs were inspected, below the floor of "
                        + MIN_SPECS_NAMING_A_RULE + " — the walk is not seeing the tree, so a pass"
                        + " here would mean nothing.");

        List<String> expected = new ArrayList<>();
        EXPECTED_EXCEPTIONS.forEach((p, r) -> expected.add(p + " binds " + r));
        expected.sort(null);
        disagreeing.sort(null);

        assertEquals(expected, disagreeing,
                () -> "a rulespec's filename stem must start with the rule id it BINDS, and the"
                        + " exceptions are pinned as an exact set. A line present here but not in"
                        + " EXPECTED_EXCEPTIONS is a spec whose name and key disagree — almost"
                        + " always a re-key whose filename was computed before the retarget, which"
                        + " RuleExecutionSpecTest cannot see because it resolves the binding. A"
                        + " line missing is an exception that no longer holds: delete its"
                        + " EXPECTED_EXCEPTIONS entry rather than restoring the old name.");
    }


    /**
     * The invariant itself, as one predicate: the filename stem of {@code aPath} starts with the
     * rule id {@code aRule} that the spec binds. Package-private and named so the corpus walk above
     * and the sensitivity arm below run the SAME code — a control that probes a re-implementation
     * proves nothing about the instrument.
     *
     * @param aPath
     *            the spec's path relative to {@code rulespec/specs}
     * @param aRule
     *            the rule id the spec binds
     * @return {@code true} when the name agrees with the binding
     */
    static boolean stemBindsItsRule(String aPath, String aRule)
    {
        String stem = aPath.substring(aPath.lastIndexOf('/') + 1);
        if (stem.endsWith(".yaml"))
        {
            stem = stem.substring(0, stem.length() - ".yaml".length());
        }
        return stem.startsWith(aRule);
    }


    /**
     * ⛔ Sensitivity arm — it runs on EVERY invocation, mirroring
     * {@code ScenarioFilenameBindsItsOwnRuleTest.theDetectorCatchesEveryShapeItClaimsTo}, which
     * does the same for the {@code .cdt} half.
     *
     * <p>
     * ⚠⚠ <b>Why the assertion above is not self-guarding.</b> It is an equality against a 16-entry
     * expected list, so a BLINDED detector — one that reported nothing — reds today, because 16
     * lines would go missing. That protection is an accident of the exception list being non-empty.
     * The 16 are engine-case fixtures, authoring pilots and one un-prefixed name; retiring or
     * renaming any of them is an ordinary, legitimate tidy-up, and the day the list reaches zero a
     * detector that has silently stopped detecting passes an equality of {@code [] == []} for ever.
     * The floor does not help either: {@code MIN_SPECS_NAMING_A_RULE} counts what was WALKED, not
     * what was JUDGED.
     * </p>
     *
     * <p>
     * ⚑ The prefix rule deliberately accepts a suffixed sibling spec ({@code CDISC-CG0148-offset}
     * binding {@code CDISC-CG0148}); the last two cases pin that this looseness is one-directional,
     * so nobody "tightens" it into an equality and reds the ~150 legitimately suffixed specs.
     * </p>
     */
    @Test
    void theDetectorCatchesEveryShapeItClaimsTo()
    {
        // conformant — must be silent
        assertTrue(stemBindsItsRule("CDISC-CG0664.yaml", "CDISC-CG0664"),
                "SENSITIVITY FAILED: a spec named for the rule it binds must be accepted");
        assertTrue(stemBindsItsRule("CDISC-CG0148-offset.yaml", "CDISC-CG0148"),
                "SENSITIVITY FAILED: a qualifier suffix is legitimate and must be accepted");
        assertTrue(stemBindsItsRule("CDISC-SEND-0324-A.yaml", "CDISC-SEND-0324-A"),
                "SENSITIVITY FAILED: a three-segment id with a split suffix must be accepted");

        // ⭐⭐ THE DEFECT THIS GUARD EXISTS FOR, in its shipped shape: phase 1 re-keyed
        // CORE-000852's spec onto CDISC-CG0664 but computed the filename before the retarget, so
        // CDISC-CG0330.yaml bound CDISC-CG0664 and NOTHING reddened (13a46906).
        assertFalse(stemBindsItsRule("CDISC-CG0330.yaml", "CDISC-CG0664"),
                "SENSITIVITY FAILED: a spec whose stem names a DIFFERENT rule than it binds is the"
                        + " one defect no other instrument can see — RuleExecutionSpecTest resolves"
                        + " the binding and never reads the name");
        // the stale-stem direction of the same defect: the name kept, the binding moved on
        assertFalse(stemBindsItsRule("CORE-000852.yaml", "CDISC-CG0664"),
                "SENSITIVITY FAILED: a retired-family stem over a live binding must be reported");
        // a near-miss that a substring test rather than a prefix test would wave through
        assertFalse(
                stemBindsItsRule("EC67-dataset-domain-fork-cannot-resolve.yaml", "CDISC-CG0308"),
                "SENSITIVITY FAILED: the check must be a PREFIX test — this very path is one of the"
                        + " 16 pinned exceptions and must reach the disagreeing list to be pinned");
        // ⚑ one-directional: the stem may be LONGER than the binding, never shorter
        assertFalse(stemBindsItsRule("CDISC-CG0033.yaml", "CDISC-CG0033-A"),
                "SENSITIVITY FAILED: a parent-named spec binding a split SIBLING must be reported");
        assertTrue(stemBindsItsRule("CDISC-CG0033-A.yaml", "CDISC-CG0033"),
                "SENSITIVITY FAILED: the looseness is one-directional by design; tightening it to"
                        + " an equality would red every legitimately suffixed spec");
    }
}
