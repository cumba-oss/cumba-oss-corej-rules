package net.cumba.cdisc.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.model.Requirements;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.VariableRequirement;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which columns {@link ScenarioTrimmer#collectReferencedColumns(Rule, String)} protects from
 * trimming. If a referenced column is not in the keep set, trimming deletes it and the scenario
 * silently stops exercising the rule.
 *
 * <h2>Dotted references (Fix #124)</h2> A qualified {@code Requirements.Variables} entry
 * ({@code DM.ARM}) names a column of the <b>foreign</b> dataset, so it must land on that dataset's
 * bucket rather than the primary's. No trimmer change was needed for this — {@code addRef} has
 * always routed dotted references to the qualifier (and resolved a {@code SUPP--} dataset
 * wildcard), because {@code Check} leaves have always used the dotted form. These tests pin it so a
 * refactor cannot silently break qualified-scope fixtures.
 *
 * <h2>Pattern references (Fix C)</h2> A glob ({@code *DTC}), a {@code /…/} regex, or a wildcard
 * marker ({@code TRTxxP}) matches a <em>set</em> of columns, and the trimmer must keep all of them.
 * It previously kept none: {@code *DTC} was dropped by the identifier filter and {@code TRTxxP} was
 * uppercased to {@code TRTXXP}, a name no column can have. Both bugs applied equally to
 * <em>unqualified</em> entries, so this is not specific to Fix #124.
 */
class ScenarioTrimmerQualifiedScopeTest
{

    /**
     * A rule whose {@code Requirements.Variables} carries {@code all} and {@code none}. The
     * parameters keep the {@code Include}/{@code Exclude} roles the retired {@code Scope.Variables}
     * spelling gave them — {@code Include} → {@code All}, {@code Exclude} → {@code None}.
     */
    private static Rule scopedRule(@Nullable List<String> all, @Nullable List<String> none)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(all);
        vr.setNone(none);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    /** A rule whose {@code Requirements.Variables} carries only the {@code Any} facet. */
    private static Rule anyScopedRule(List<String> any)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAny(any);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    @Test
    void qualifiedIncludeEntryLandsOnTheQualifierBucket()
    {
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("DM.ARM"), null), "AE");
        assertTrue(buckets.keeps("DM", "ARM"), "DM bucket keeps ARM: " + buckets.buckets());
        assertFalse(buckets.keeps("AE", "ARM"), "ARM must NOT be retained on the primary");
    }


    @Test
    void unqualifiedEntryStaysOnThePrimaryBucket()
    {
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("AESTDTC"), null), "AE");
        assertTrue(buckets.keeps("AE", "AESTDTC"),
                "unqualified entries anchor on the primary, got " + buckets.buckets());
        assertFalse(buckets.buckets().contains("AESTDTC"), "the entry is a column, not a dataset");
    }


    @Test
    void qualifierDatasetWildcardResolvesAgainstThePrimary()
    {
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("SUPP--.QVAL"), null), "AE");
        assertTrue(buckets.keeps("SUPPAE", "QVAL"),
                "SUPP-- must resolve to SUPPAE, got " + buckets.buckets());
    }


    @Test
    void qualifiedNoneEntryIsNotRetained()
    {
        // All and Any are collected; None is not. A None entry names a column that must be ABSENT,
        // so retaining it would defeat the scenario. Pinned because the asymmetry is easy to "fix"
        // by mistake.
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(null, List.of("DM.ARM")), "AE");
        assertFalse(buckets.keeps("DM", "ARM"),
                "a None entry must not pull the column into the fixture: " + buckets.buckets());
    }


    /**
     * ⚠ {@code Any} <b>is</b> collected — every entry of it. An {@code Any} list is unmet only when
     * EVERY entry is absent, so trimming the whole list away leaves the rule inapplicable exactly
     * as trimming an {@code All} entry would. The facet is new with {@code Requirements}
     * ({@code plans/PLAN-scope-requirements-split.md}); nothing pinned it before.
     */
    @Test
    void everyAnyEntryIsRetainedOnThePrimary()
    {
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(anyScopedRule(List.of("TEENRL", "TEDUR")), "TE");
        assertTrue(buckets.keeps("TE", "TEENRL"), "Any entry TEENRL dropped: " + buckets.buckets());
        assertTrue(buckets.keeps("TE", "TEDUR"), "Any entry TEDUR dropped: " + buckets.buckets());
    }

    // ------------------------------------------------------------------
    // Fix C — pattern and marker references keep every column they match
    // ------------------------------------------------------------------


    @Test
    void globEntryKeepsEveryMatchingColumn()
    {
        // `*DTC` used to be dropped outright by addRef's identifier filter, so a scenario's
        // date columns looked unreferenced and were trimmed away — silently gating the rule off.
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("*DTC"), null), "AE");
        assertTrue(keep.keeps("AE", "AESTDTC"), "glob keeps AESTDTC");
        assertTrue(keep.keeps("AE", "AEENDTC"), "glob keeps AEENDTC");
        assertFalse(keep.keeps("AE", "AESEQ"), "glob does not keep an unrelated column");
    }


    @Test
    void qualifiedGlobEntryKeepsMatchingColumnsOnTheQualifierOnly()
    {
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("DM.*DTC"), null), "AE");
        assertTrue(keep.keeps("DM", "RFSTDTC"), "kept on the qualifier's bucket");
        assertFalse(keep.keeps("AE", "AESTDTC"), "not kept on the primary");
    }


    @Test
    void wildcardMarkerEntryKeepsEveryConcreteColumn()
    {
        // `TRTxxP` used to be uppercased to `TRTXXP` — a name no column can ever have — so every
        // TRTnnP column was considered unreferenced.
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("TRTxxP"), null), "ADAE");
        assertTrue(keep.keeps("ADAE", "TRT01P"), "marker keeps TRT01P");
        assertTrue(keep.keeps("ADAE", "TRT02P"), "marker keeps TRT02P");
        assertFalse(keep.keeps("ADAE", "TRT1P"), "xx is exactly two digits");
        assertFalse(keep.keeps("ADAE", "USUBJID"), "marker does not keep unrelated columns");
    }


    @Test
    void qualifiedMarkerEntryKeepsConcreteColumnsOnTheQualifier()
    {
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("ADSL.TRTxxPN"), null), "ADAE");
        assertTrue(keep.keeps("ADSL", "TRT01PN"), "kept on ADSL");
        assertFalse(keep.keeps("ADAE", "TRT01PN"), "not kept on the primary");
    }


    @Test
    void regexEntryKeepsMatchingColumns()
    {
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("/^AE(ST|EN)DTC$/"), null), "AE");
        assertTrue(keep.keeps("AE", "AESTDTC"));
        assertTrue(keep.keeps("AE", "AEENDTC"));
        assertFalse(keep.keeps("AE", "AEDECOD"));
    }


    @Test
    void plainNamesAreStillMatchedCaseInsensitivelyAsLiterals()
    {
        // The literal path is unchanged: names are stored upper-cased and compared that way.
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("AESTDTC"), null), "AE");
        assertEquals(Set.of("AESTDTC"), keep.literals("AE"));
        assertTrue(keep.keeps("AE", "aestdtc"), "literal comparison is case-insensitive");
    }


    @Test
    void expandedTupleEntryLandsOnTheQualifierBucket()
    {
        // What a WildcardExpander-expanded ADSL.TRTxxPN template looks like by the time the
        // trimmer sees it (expansion happens first, so the entry is already concrete).
        ScenarioTrimmer.ColumnKeepSets buckets = ScenarioTrimmer
                .collectReferencedColumns(scopedRule(List.of("ADSL.TRT01PN"), null), "ADAE");
        assertEquals(Set.of("TRT01PN"), buckets.literals("ADSL"), buckets.buckets().toString());
    }


    /**
     * {@code Fix #354}: an {@code !X} exclusion token in {@code Output_Variables} only withholds X
     * from the FINDING; the rule still reads X, so the column stays in the keep-set under its bare
     * name (a trimmed-away column would silently stop exercising the rule). {@code !$op} is still
     * an operation reference and contributes nothing; a bare {@code !} is ignored.
     */
    @Test
    void outputVariableExclusionTokenKeepsTheBareColumn()
    {
        Rule rule = new Rule();
        net.cumba.cdisc.core.model.Outcome outcome = new net.cumba.cdisc.core.model.Outcome();
        outcome.setOutputVariables(List.of("AESEV", "!AEDECOD", "!DM.ARM", "!$op", "!"));
        rule.setOutcome(outcome);
        ScenarioTrimmer.ColumnKeepSets keep = ScenarioTrimmer.collectReferencedColumns(rule, "AE");
        // Unqualified Output_Variables entries land on the "*" (any-dataset) bucket.
        assertTrue(keep.keeps("*", "AESEV"), keep.buckets().toString());
        assertTrue(keep.keeps("*", "AEDECOD"),
                "excluded-from-finding is still read: " + keep.buckets());
        assertTrue(keep.keeps("DM", "ARM"),
                "dotted exclusion routes to its qualifier: " + keep.buckets());
        assertFalse(keep.literals("*").contains("!AEDECOD"), "the token itself is never a column");
        assertFalse(keep.keeps("*", "$op"));
        assertFalse(keep.keeps("*", ""));
    }
}
