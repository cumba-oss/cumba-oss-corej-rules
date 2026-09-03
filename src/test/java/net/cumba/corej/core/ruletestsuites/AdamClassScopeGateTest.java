package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #206</b> — {@link AbstractRuleTestSuitesFactory#describeAdamClassMismatch}: the ADaM arm
 * of the harness's {@code Scope.Classes} pre-gate.
 *
 * <p>
 * Before Fix #206 the class axis was judged <em>only</em> when a {@code .cdt} fixture declared
 * {@code class=}, so an ADaM class-scoped rule on an undeclared fixture was never gated — wave 27's
 * {@code R-d}: the fixture passed because the gate was off, not because the rule was in scope.
 * Production ({@code RuleGenerator} → {@code ScopeMatcher.describeClassMismatch}) has no such hole.
 * The tests below pin both halves: the gate now fires (cases 2/4), and the three deliberate
 * refusals still leave the pre-Fix-#206 behaviour in place (cases 5–8).
 * </p>
 */
class AdamClassScopeGateTest
{

    private static final String BDS = AdamDataStructureDetector.BDS;

    private static final String MDBDS = AdamDataStructureDetector.MEDICAL_DEVICE_BDS;

    private static final String OTHER = AdamDataStructureDetector.OTHER;

    /** 1 — the detected structure is the scoped one: in scope, exactly as before. */
    @Test
    void adamClassInclude_matchingStructure_inScope()
    {
        assertNull(AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of(BDS), null), List.of(BDS)));
    }


    /**
     * 2 — ⚑ <b>the behaviour Fix #206 adds.</b> A structure-less fixture no longer satisfies a
     * BDS-scoped rule by omission.
     */
    @Test
    void adamClassInclude_structurelessDataset_outOfScope()
    {
        String mismatch = AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of(BDS), null), List.of(OTHER));
        assertNotNull(mismatch);
        assertTrue(mismatch.contains(OTHER) && mismatch.contains("Scope.Classes.Include"),
                "the reason must name the detected structure and the responsible facet: "
                        + mismatch);
    }


    /**
     * 3 — the is-a set is honoured the way {@code describeDataStructureMismatch} honours it: a
     * device BDS dataset satisfies a rule scoped to plain BDS via the supertype in the set.
     */
    @Test
    void adamClassInclude_deviceStructureMatchesItsSupertype_inScope()
    {
        assertNull(AbstractRuleTestSuitesFactory.describeAdamClassMismatch(
                classScoped(List.of(BDS), null), AdamDataStructureDetector.structureSet(MDBDS)));
        // …and the mismatch message names the MOST SPECIFIC token plus the whole set.
        String mismatch = AbstractRuleTestSuitesFactory.describeAdamClassMismatch(
                classScoped(List.of(AdamDataStructureDetector.ADSL), null),
                AdamDataStructureDetector.structureSet(MDBDS));
        assertNotNull(mismatch);
        assertTrue(
                mismatch.startsWith("data structure " + MDBDS) && mismatch.contains("(detected "),
                mismatch);
    }


    /** 4 — the converse: a plain BDS dataset does not satisfy a device-BDS-scoped rule. */
    @Test
    void adamClassInclude_plainStructureDoesNotMatchTheSpecialisation_outOfScope()
    {
        assertNotNull(AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of(MDBDS), null), List.of(BDS)));
    }


    /**
     * 5 — <b>refusal: SDTM.</b> An SDTM observation class is genuinely undetermined without a CDISC
     * Library, so the gate stays off for it rather than resolving it wrongly.
     */
    @Test
    void sdtmClassScope_isNotJudged()
    {
        assertNull(AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of("EVENTS"), null), List.of(OTHER)));
    }


    /**
     * 6 — <b>refusal: Exclude.</b> Lifting an Exclude list over the structure set is not
     * expressible through {@code describeClassMismatch} per token, and the corpus has zero ADaM
     * {@code Classes.Exclude} entries.
     */
    @Test
    void adamClassScopeWithExclude_isNotJudged()
    {
        assertNull(AbstractRuleTestSuitesFactory.describeAdamClassMismatch(
                classScoped(List.of(BDS), List.of(OTHER)), List.of(OTHER)));
    }


    /** 7 — <b>refusal: nothing to judge.</b> No Include list, or no Classes block at all. */
    @Test
    void emptyOrAbsentClassScope_isNotJudged()
    {
        assertNull(AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of(), null), List.of(OTHER)));
        assertNull(AbstractRuleTestSuitesFactory.describeAdamClassMismatch(classScoped(null, null),
                List.of(OTHER)));
        Rule noScope = new Rule();
        assertNull(
                AbstractRuleTestSuitesFactory.describeAdamClassMismatch(noScope, List.of(OTHER)));
        Rule emptyScope = new Rule();
        emptyScope.setScope(new Scope());
        assertNull(AbstractRuleTestSuitesFactory.describeAdamClassMismatch(emptyScope,
                List.of(OTHER)));
    }


    /** 8 — the {@code ALL} wildcard is not an ADaM structure token, so the gate stays off. */
    @Test
    void allWildcardClassScope_isNotJudged()
    {
        assertNull(AbstractRuleTestSuitesFactory
                .describeAdamClassMismatch(classScoped(List.of("ALL"), null), List.of(OTHER)));
    }

    // -----------------------------------------------------------------------
    // The WIRING. ⚠⚠ The cases above pin describeAdamClassMismatch in isolation; they would all
    // stay green if the call to it were deleted from describeScopeMismatch, and so would every
    // .cdt scenario (Fix #206 changes 0 verdicts across the 494 it newly judges). These three run
    // the real pre-gate.
    // -----------------------------------------------------------------------


    /** W1 — the pre-gate rejects a structure-less ADaM fixture that declares no {@code class=}. */
    @Test
    void scopeMismatch_adamClassScopeOnStructurelessFixture_isOutOfScope()
    {
        // ADLB passes the AD*/AX* name gate; USUBJID/PARAMN carry no BDS indicator
        // (PARAMCD/PARAM/AVAL/AVALC) and no OCCDS suffix (TERM/TRT/DECOD) ⇒ ADAM OTHER.
        String mismatch = AbstractRuleTestSuitesFactory.describeScopeMismatch(
                classScoped(List.of(BDS), null), table("ADLB", null, "USUBJID", "PARAMN"));
        assertNotNull(mismatch, "before Fix #206 the class axis was not judged at all here");
        assertTrue(mismatch.contains("Scope.Classes.Include"), mismatch);
    }


    /** W2 — the same fixture with a BDS indicator column is in scope. */
    @Test
    void scopeMismatch_adamClassScopeOnBdsFixture_isInScope()
    {
        assertNull(
                AbstractRuleTestSuitesFactory.describeScopeMismatch(classScoped(List.of(BDS), null),
                        table("ADLB", null, "USUBJID", "PARAMCD", "AVAL")));
    }


    /**
     * W3 — a declared {@code class=} still takes the pre-Fix-#206 path: the declaration wins over
     * the column heuristic, so the structure-less fixture is in scope after all.
     */
    @Test
    void scopeMismatch_declaredClassStillWins()
    {
        assertNull(AbstractRuleTestSuitesFactory.describeScopeMismatch(
                classScoped(List.of(BDS), null), table("ADLB", BDS, "USUBJID", "PARAMN")));
    }


    private static OverlayDataTable table(String aName, @Nullable String aDeclaredClass,
            String... aColumns)
    {
        OverlayDataTable table = OverlayDataTable.empty(aName, aName, 1);
        for (String column : aColumns)
        {
            table.addColumn(column);
        }
        if (aDeclaredClass != null)
        {
            table.setTableMetaData("dataset_class", aDeclaredClass);
        }
        return table;
    }


    private static Rule classScoped(@Nullable List<String> aInclude,
            @Nullable List<String> aExclude)
    {
        ClassScope classes = new ClassScope();
        classes.setInclude(aInclude);
        classes.setExclude(aExclude);
        Scope scope = new Scope();
        scope.setClasses(classes);
        Rule rule = new Rule();
        rule.setScope(scope);
        return rule;
    }

}
