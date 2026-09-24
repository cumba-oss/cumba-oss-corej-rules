package net.cumba.corej.core.ruletestsuites.adam;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>Fix #227 — the acceptance gate for the ADaM template suites' declare channel.</b>
 *
 * <h2>What it pins</h2>
 *
 * <p>
 * {@code lib/corej-rules/scripts/adam-classes-to-structures-instrument-holds.tsv} <b>used to record
 * 15</b> rules under {@code instrument = adam-template-suite}: rules that carried
 * {@code Scope.Classes} and could not migrate to the semantically correct
 * {@code Scope.Data_Structures}, because the migrated form went SKIPPED in the template suites and
 * turned their {@code _invalid} assertion red. The recorded cause was <i>"the template-expanded
 * ADLBC table carries no BDS indicator column"</i> — i.e. the fixture is synthesised from the
 * rule's own leaf columns and so detects {@code ADAM OTHER}.
 * </p>
 *
 * <p>
 * ✅ <b>Those 15 migrated in {@code Fix #230} (wave 34) and left the TSV; the bucket is now
 * empty.</b> This probe consequently changed from <i>authorising</i> that migration to
 * <i>pinning</i> it — see the wave-34 note below, which is the part worth reading.
 * </p>
 *
 * <p>
 * ⚑ The remedy is <b>not</b> to add indicator columns to the fixture (make-the-data-true, which
 * wave-31 lane F deleted 16 instances of). The suites already <em>declare</em> their structure; Fix
 * #227 routes that declaration to {@code RuleRunner} via
 * {@link AbstractAdamTemplateTest#declaringLibrary}. This probe measures the declaration, not the
 * data.
 * </p>
 *
 * <h2>⚠⚠ WAVE 34 — RE-POINTED, AND THE LESSON IS WORTH MORE THAN THE FIX</h2>
 *
 * <p>
 * {@code Fix #230} migrated all 15 rules to {@code Scope.Data_Structures} — <b>which is exactly
 * what this probe existed to authorise.</b> The probe then failed, all 5 test methods at once, on
 * its own anti-vacuity guard: the population filter dropped every rule that had <em>already</em>
 * migrated, so a completed migration emptied the population and the guard correctly reported
 * "measuring nothing".
 * </p>
 *
 * <p>
 * ⚑⚑ <b>The general shape, stated so the next instrument of this kind is not written the same
 * way:</b> a test that <em>simulates a pending change</em> is guaranteed to lose its population the
 * moment the change lands. Its success and its vacuity are the same event, and no message can tell
 * them apart from inside. <b>Prefer asserting on the shipped state over simulating a transition</b>
 * — and where a simulation is genuinely needed, make the empty-population diagnosis name both
 * causes, as {@link #emptyPopulationDiagnosis()} now does.
 * </p>
 *
 * <p>
 * The three arms are unchanged in what they prove; only their subject moved from a synthesised
 * migrated copy to the shipped rule. ⚠ The <b>control inverted</b> rather than disappearing: it now
 * <em>de</em>-migrates ({@code Data_Structures} → {@code Classes}) via {@link #preMigrationScope},
 * because a control that shares arm 2's dependency on the declaration is not a control.
 * </p>
 *
 * <h2>Three arms, and why all three are needed</h2>
 *
 * <ol>
 * <li><b>control</b> — the rule in its <em>pre-migration</em> {@code Scope.Classes} shape fires on
 * the synthesised invalid fixture, with no declaration. Without this arm a green in arm 2 could
 * mean "the fixture cannot make this rule fire at all", and the probe would be vacuous.</li>
 * <li><b>shipped + declared</b> — the rule as shipped ({@code Scope.Data_Structures}) fires once
 * the structure is declared. This is the claim {@code Fix #227} made and {@code Fix #230}
 * shipped.</li>
 * <li><b>shipped, declaration withheld</b> — the same rule and the same fixture, executed against
 * the <em>undecorated</em> library mock, is SKIPPED. ⚑ This is the neuter, pinned as a test: it is
 * what makes arm 2's green evidence rather than decoration. Wave 31 measured that a
 * {@code noViolation}-shaped assertion is structurally blind to this gate, so
 * {@code templateTests_valid} can never provide it. ⚠⚠ <b>This arm <em>IS</em> template arm
 * {@code B3}</b> — {@code PLAN-adam-template-declare-channel.md:306} records the identity, and the
 * older <i>"do not retire it and {@code B3} together"</i> note wrongly read them as two guards. ⛔
 * <b>Measured 2026-08-12 and KEPT: not redundant</b> — see this arm's own javadoc for the two
 * neuters that settle it.</li>
 * </ol>
 *
 * <p>
 * ⚠ The rules are mutated in place and restored in a {@code finally}: {@code WildcardExpander
 * .expandScope} returns the <em>template's own</em> {@link Scope} object when the rule declares no
 * {@code Requirements.Variables}, and {@code DatasetRuleResolver} may pass a non-template rule
 * through by reference, so an un-restored {@code setScope} would leak into {@code rulesByCoreId} —
 * which every other ADaM suite in this JVM shares.
 * </p>
 */
class AdamTemplateDeclaredStructureProbeTest extends AbstractAdamTemplateTest
{ // NOSONAR S3577 — Test-suffixed probe name is the suite convention.

    /**
     * The three template suites, as {@code (domain, declared structure)} — mirroring
     * {@code AdamTemplatesBdsRuleTest:23,25}, {@code AdamTemplatesOccdsRuleTest:22,24} and
     * {@code AdamTemplatesAdslRuleTest:23,25}. Q-J4 asked <i>"three owners or one?"</i>: three
     * suites declare, one place executes.
     */
    private record Suite(String domain, String structure)
    {
    }

    private static final List<Suite> SUITES = List.of(new Suite("ADLBC", "BASIC DATA STRUCTURE"),
            new Suite("ADAE", "OCCURRENCE DATA STRUCTURE"),
            new Suite("ADSL", "SUBJECT LEVEL ANALYSIS DATASET"));

    /**
     * The rules the {@code adam-template-suite} instrument used to hold — and, since {@code Fix
     * #230} released them, the population this probe asserts on in its shipped form.
     *
     * <p>
     * ⚠ <b>15, not 7.</b> The "7" carried in {@code WORK-STATUS-overview.md} (retired 2026-08-31)
     * and in Q-J4's own wording is the count as at wave 27; wave 29 added a second block of 8
     * ({@code CDISC-AD0346} … {@code CDISC-AD0414}). Counted from the TSV on 2026-08-11.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>These 15 are no longer in the holds TSV, and there is no next tranche to re-base
     * onto.</b> Measured 2026-08-12: the TSV's remaining <b>78</b> holds are {@code parity-spec}
     * (54), {@code java-probe} (21) and {@code java-probe-vacuous} (3) — the
     * {@code adam-template-suite} bucket is <b>empty</b>. A rule held by
     * {@code RuleExecutionSpecTest} (named {@code RuleExecutionParityTest} until the wave-41
     * rename) is held for a reason these three template suites cannot observe, so pointing
     * {@code HELD} at one would produce a green that is not evidence about its hold. <b>If this
     * list ever needs to change, re-derive the bucket from the TSV first.</b>
     * </p>
     */
    private static final List<String> HELD = List.of("CDISC-AD0346", "CDISC-AD0349", "CDISC-AD0352",
            "CDISC-AD0369", "CDISC-AD0411", "CDISC-AD0412", "CDISC-AD0413", "CDISC-AD0414",
            "CDISC-AD0445", "CDISC-AD0446", "CDISC-AD0526", "CDISC-AD0547", "CDISC-AD0730",
            "CDISC-AD0731", "CDISC-AD0792");

    /**
     * The 21 {@code <expanded rule id>@<domain>} pairs the three arms measure, sorted — the
     * per-rule evidence behind "these 15 families may leave {@code INSTRUMENT-HELD}". All 15 are
     * reached through {@code ADLBC}/BDS; {@code CDISC-AD0369} is additionally reached through
     * {@code ADAE}/OCCDS (which is what the TSV's AD0369 row already says), and no held family
     * reaches the {@code ADSL} suite at all.
     * <p>
     * ⚠ Sorted, not in encounter order: {@code ownedTemplateRules()} walks a
     * {@link java.util.HashMap HashMap}'s values, so encounter order is an implementation detail of
     * the JDK's hashing and would make this ratchet flap.
     * </p>
     */
    private static final List<String> EXPECTED_CASES = List.of("CDISC-AD0346-R2A1LO@ADLBC",
            "CDISC-AD0349-R2A1HI@ADLBC", "CDISC-AD0352-A1IND@ADLBC", "CDISC-AD0369-ADTF@ADLBC",
            "CDISC-AD0369-AENDTF@ADAE", "CDISC-AD0369-ASTDTF@ADAE",
            // AXDTF joined when the M4 fixture work (terminal review of
            // PLAN-typed-expression-engine, 2026-09-17) added the AXDTM/AXTM ISO columns to the
            // ADLBC fixture for the CDISC-AD0044 *DTM/*TM template pair — AD0369's *DTF family
            // derives an imputation-flag case from the new *DTM column. A real population
            // growth, not drift.
            "CDISC-AD0369-AXDTF@ADLBC", "CDISC-AD0369-TRTEDTF@ADAE", "CDISC-AD0369-TRTEDTF@ADLBC",
            "CDISC-AD0369-TRTSDTF@ADAE", "CDISC-AD0369-TRTSDTF@ADLBC", "CDISC-AD0411-ANL01FN@ADLBC",
            "CDISC-AD0412-ANL01FL@ADLBC", "CDISC-AD0413-ANL01FN@ADLBC",
            "CDISC-AD0414-ANL01FL@ADLBC", "CDISC-AD0445-PARCAT1@ADLBC",
            "CDISC-AD0446-PARCAT1N@ADLBC", "CDISC-AD0526-ANL01FN@ADLBC",
            "CDISC-AD0547-PARCAT1N@ADLBC", "CDISC-AD0730-PARCAT1@ADLBC",
            "CDISC-AD0731-PARCAT1N@ADLBC", "CDISC-AD0792-TRTSDT@ADLBC");

    /**
     * Every expanded rule of a held family that the template harness can build an invalid fixture
     * for, across all three suites. Keyed by the expanded rule so each arm sees the same objects.
     */
    private record Case(Suite suite, Rule rule, String ruleId, OverlayDataTable table)
    {
    }

    private List<Case> cases()
    {
        List<Case> out = new ArrayList<>();
        for (Suite suite : SUITES)
        {
            for (Rule rule : expandRules(suite.domain(), suite.structure(),
                    adamDomains.get(suite.domain())))
            {
                String ruleId = rule.getCore() != null ? rule.getCore().getId() : null;
                if (ruleId == null || !isHeldFamily(ruleId) || !isMigrated(rule))
                {
                    continue;
                }
                OverlayDataTable table = buildInvalidTable(rule, suite.domain());
                if (table != null)
                {
                    out.add(new Case(suite, rule, ruleId, table));
                }
            }
        }
        assertFalse(out.isEmpty(), emptyPopulationDiagnosis());
        return out;
    }


    /**
     * ⚑⚑ <b>The anti-vacuity guard's message, and why it is computed rather than a constant.</b>
     *
     * <p>
     * An empty population has two completely different causes and the old message named only one of
     * them. This distinguishes them, because the difference decides what the reader should do:
     * </p>
     * <ul>
     * <li><b>a MIGRATION REGRESSION</b> — a rule of {@link #HELD} is back on {@code Scope.Classes}.
     * Something reverted {@code Fix #230}; fix the corpus, not this test.</li>
     * <li><b>the tranche is EXHAUSTED</b> — every held family is migrated but none expands in any
     * suite any more, so the {@code HELD} list, the corpus or the expansion has moved.</li>
     * </ul>
     */
    private String emptyPopulationDiagnosis()
    {
        List<String> unmigrated = new ArrayList<>();
        for (Suite suite : SUITES)
        {
            for (Rule rule : expandRules(suite.domain(), suite.structure(),
                    adamDomains.get(suite.domain())))
            {
                String ruleId = rule.getCore() != null ? rule.getCore().getId() : null;
                if (ruleId != null && isHeldFamily(ruleId) && !isMigrated(rule))
                {
                    unmigrated.add(ruleId);
                }
            }
        }
        if (!unmigrated.isEmpty())
        {
            return "MIGRATION REGRESSION: " + unmigrated.size()
                    + " held-family rule(s) are back on " + "Scope.Classes ("
                    + unmigrated.stream().distinct().sorted().toList()
                    + "). Fix #230 migrated all 15; fix the corpus, not this probe";
        }
        return "the HELD tranche is migrated but no expansion of it reaches any template suite any "
                + "more — the HELD list, the corpus or the expansion has moved, so this probe is "
                + "measuring nothing. Re-point HELD at the rules the declare channel now covers, or "
                + "retire the probe deliberately (see the class javadoc)";
    }


    /** Whether {@code ruleId} is a held family, or an expansion of one ({@code <id>-<COLUMN>}). */
    private static boolean isHeldFamily(String ruleId)
    {
        for (String held : HELD)
        {
            if (ruleId.equals(held) || ruleId.startsWith(held + "-"))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether {@code rule} is in the post-migration shape this probe now asserts on — it carries
     * {@code Scope.Data_Structures} and no {@code Scope.Classes}.
     *
     * <p>
     * ⚑ Before wave 34 this method was {@code migratedScope}, which <em>synthesised</em> the
     * migrated shape and returned {@code null} once there was nothing left to migrate. That made
     * the population empty by construction the moment the migration landed — see the class javadoc.
     * </p>
     */
    private static boolean isMigrated(Rule rule)
    {
        Scope scope = rule.getScope();
        return scope != null && scope.getDataStructures() != null && scope.getClasses() == null;
    }


    /**
     * The rule's scope with {@code Data_Structures} moved back to {@code Classes} — the shape these
     * rules shipped in <em>before</em> wave 34. Used only by the control arm, to show the fixture
     * can make the rule fire without the declare channel at all. Returns {@code null} when there is
     * nothing to undo.
     *
     * <p>
     * ⚠ This is the inverse of the old {@code migratedScope} and it exists for one reason: the
     * control arm's whole job is to be independent of the thing arms 2 and 3 measure. Executing the
     * shipped rule would couple it to the declaration and the control would stop being a control.
     * </p>
     */
    private static Scope preMigrationScope(Rule rule)
    {
        Scope scope = rule.getScope();
        DataStructureScope structures = scope != null ? scope.getDataStructures() : null;
        if (scope == null || structures == null)
        {
            return null;
        }
        ClassScope classes = new ClassScope();
        classes.setInclude(structures.getInclude());
        classes.setExclude(structures.getExclude());
        Scope pre = new Scope();
        pre.setDomains(scope.getDomains());
        pre.setDatasets(scope.getDatasets());
        pre.setUseCase(scope.getUseCase());
        pre.setSubclasses(scope.getSubclasses());
        pre.setClasses(classes);
        // ⚠ No Variables line: the variable requirement no longer lives under Scope
        // (PLAN-scope-requirements-split phase 5), so it is untouched by this de-migration and
        // stays on the rule. Scope.Datasets IS copied — it is an axis, and omitting it here would
        // silently widen the control arm.
        return pre;
    }


    /**
     * Arm 1 — <b>the control, and it must stay independent of the declare channel.</b> Executed
     * with the rule's <em>pre-migration</em> {@code Scope.Classes} shape and an undecorated library
     * mock, the rule fires on its own synthesised invalid fixture.
     *
     * <p>
     * ⚑ Without this arm, a green in arm 2 could mean <i>"the fixture cannot make this rule fire at
     * all"</i> and the probe would be vacuous. That is why it de-migrates rather than executing the
     * rule as shipped: the shipped rule now needs the declaration, which is precisely what arms 2
     * and 3 are measuring.
     * </p>
     */
    // A @TestFactory: the DynamicTests it returns carry the assertions.
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
    @TestFactory
    Stream<DynamicTest> control_preMigrationClassScope_firesOnInvalidData()
    {
        return cases().stream()
                .map(c -> DynamicTest.dynamicTest(c.ruleId() + "@" + c.suite().domain(), () ->
                {
                    RuleExecutionResult result = executeWithScope(c, preMigrationScope(c.rule()),
                            getLibraryMock());
                    // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so
                    // hasViolations()
                    // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
                    // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same
                    // discipline as
                    // ScenarioDeclaredScopeProviderTest, which already guarded this way.
                    assertFalse(result.isError(),
                            "ERRORED rather than firing: " + result.getStatusMessage());
                    assertTrue(result.hasViolations(),
                            c.ruleId() + " does not fire on its own invalid fixture even with the "
                                    + "pre-migration Scope.Classes — arms 2 and 3 would be vacuous "
                                    + "for it");
                }));
    }


    /**
     * Arm 2 — <b>the shipped claim.</b> The rule <em>as shipped</em> (on
     * {@code Scope.Data_Structures} since {@code Fix #230}) fires, because the suite's declaration
     * reaches {@code RuleRunner}'s structure gate.
     */
    // A @TestFactory: the DynamicTests it returns carry the assertions.
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
    @TestFactory
    Stream<DynamicTest> shippedDataStructureScope_firesWhenStructureDeclared()
    {
        return cases().stream()
                .map(c -> DynamicTest.dynamicTest(c.ruleId() + "@" + c.suite().domain(), () ->
                {
                    RuleExecutionResult result = execute(c,
                            declaringLibrary(c.table(), c.suite().structure()));
                    assertFalse(result.isSkipped(),
                            c.ruleId() + " SKIPPED with the structure declared as '"
                                    + c.suite().structure() + "': " + result.getStatusMessage());
                    // ⚠ An ERROR carries RuleRunner's "__error__" sentinel Violation, so
                    // hasViolations()
                    // is TRUE for a rule that errored and the assertion below would pass VACUOUSLY.
                    // Reachable since PLAN-column-type-conformance's ColumnTypeGate. Same
                    // discipline as
                    // ScenarioDeclaredScopeProviderTest, which already guarded this way.
                    assertFalse(result.isError(),
                            "ERRORED rather than firing: " + result.getStatusMessage());
                    assertTrue(result.hasViolations(), c.ruleId()
                            + " does not fire on Scope.Data_Structures, even with the structure "
                            + "declared");
                }));
    }


    /**
     * Arm 3 — <b>the neuter, pinned, and the reason this probe survives its own success.</b> Same
     * shipped rule, same fixture, declaration withheld: SKIPPED. ⚑ A green here is what makes arm 2
     * evidence. If this arm ever goes red the declare channel has stopped being load-bearing —
     * either it was removed, or something started shaping the fixture so the column heuristic
     * answers on its own.
     *
     * <h4>⛔⛔ MEASURED 2026-08-12 (wave 35 lane C) — <b>KEEP THIS ARM.</b> {@code W32-B1} is
     * answered, and its retirement condition turns out not to imply redundancy</h4>
     *
     * <p>
     * {@code W32-B1} made retiring template arm {@code B3} — which <em>is</em> this arm, see
     * {@code PLAN-adam-template-declare-channel.md:306} — conditional on two things. Both are now
     * met: the 15-rule migration landed ({@code Fix #230}), and
     * {@link AbstractAdamTemplateTest#templateTests_invalid} <b>does</b> observe the gate.
     * Withholding {@code declaringLibrary} at the {@code templateTests_invalid} call site reds
     * <b>21</b> cases — 17 in {@code AdamTemplatesBdsRuleTest}, 4 in
     * {@code AdamTemplatesOccdsRuleTest}, 0 in {@code AdamTemplatesAdslRuleTest} — <b>set-equal to
     * {@link #EXPECTED_CASES}</b>. ⚠ That measurement had to be redone: the sweep recorded in the
     * plan's §6.4 found <b>0 failures</b> at this same site, but it predates the migration.
     * </p>
     *
     * <p>
     * ⚑⚑ <b>And "all 21 redden" does NOT imply "redundant" — that inference is the trap.</b> The
     * two assertions have <b>opposite polarity</b>: {@code templateTests_invalid} asserts <i>fires
     * WITH the declaration</i>; this arm asserts <i>SKIPPED WITHOUT it</i>. Neuter-equivalence is
     * not guard-equivalence. Measured, second neuter — make the structure gate itself permissive
     * ({@code ScopeMatcher.describeDataStructureMismatch} stops reporting a mismatch):
     * </p>
     *
     * <pre>
     * AdamTemplatesBdsRuleTest    154 tests, 0 failures   ← blind
     * AdamTemplatesOccdsRuleTest  150 tests, 0 failures   ← blind
     * AdamTemplatesAdslRuleTest   180 tests, 0 failures   ← blind
     * AdamTemplateDeclaredStructureProbeTest  103 tests, 30 failures — 29 of them THIS ARM
     * </pre>
     *
     * <p>
     * ⇒ <b>a loosened structure gate is caught by this arm and by nothing else.</b> The same
     * asymmetry covers the other regression the paragraph above names: if the fixture ever started
     * carrying an indicator column so the heuristic answered on its own — the "make-the-data-true"
     * drift wave-31 lane F deleted 16 instances of — {@code templateTests_invalid} would stay green
     * and only this arm would move. <b>Retiring it would delete the only detector of both.</b>
     * </p>
     */
    // A @TestFactory: the DynamicTests it returns carry the assertions.
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
    @TestFactory
    Stream<DynamicTest> shippedDataStructureScope_isSkippedWhenStructureUndeclared()
    {
        return cases().stream()
                .map(c -> DynamicTest.dynamicTest(c.ruleId() + "@" + c.suite().domain(), () ->
                {
                    RuleExecutionResult result = execute(c, getLibraryMock());
                    assertTrue(result.isSkipped(),
                            c.ruleId() + " was NOT skipped without a structure declaration — the "
                                    + "declare channel is pinned by nothing for this rule "
                                    + "(status: " + result.getStatus() + ")");
                }));
    }


    /** All distinct held families the three suites actually reach — the B5 deliverable list. */
    // A @TestFactory: the DynamicTests it returns carry the assertions.
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
    @TestFactory
    Stream<DynamicTest> heldFamiliesReachedByTheTemplateSuites()
    {
        Set<String> reached = new LinkedHashSet<>();
        for (Case c : cases())
        {
            for (String held : HELD)
            {
                if (c.ruleId().equals(held) || c.ruleId().startsWith(held + "-"))
                {
                    reached.add(held);
                }
            }
        }
        return HELD.stream().map(held -> DynamicTest.dynamicTest(held, () -> assertTrue(
                reached.contains(held),
                held + " is recorded INSTRUMENT-HELD by the adam-template-suite instrument but no "
                        + "expansion of it reaches any of the three template suites — its hold "
                        + "cannot be dissolved by this lane, and the reason must be recorded "
                        + "per-rule")));
    }


    /**
     * The exact population the three arms measure — {@code <expanded rule id>@<domain>}, sorted.
     * Pinned so the arms cannot quietly stop covering a held family: a green arm over a shrunken
     * population is the vacuity this whole probe exists to rule out.
     */
    @Test
    void caseInventory_matchesTheRecordedList()
    {
        assertEquals(EXPECTED_CASES,
                cases().stream().map(c -> c.ruleId() + "@" + c.suite().domain()).sorted().toList());
    }


    private RuleExecutionResult execute(Case c, MetadataProvider provider)
    {
        return RuleRunner.execute(c.rule(), c.table(), resolverWith(c.suite().domain(), c.table()),
                c.suite().domain(), provider, null);
    }


    private RuleExecutionResult executeWithScope(Case c, Scope scope, MetadataProvider provider)
    {
        Scope original = c.rule().getScope();
        try
        {
            c.rule().setScope(scope);
            return execute(c, provider);
        }
        finally
        {
            c.rule().setScope(original);
        }
    }

}
