package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #147 — the two shipped {@code Expansion:} templates, exercised against the <b>real</b> corpus
 * rather than a hand-built stand-in.
 *
 * <p>
 * The load-bearing assertion is {@link #ad0898ExpansionBindsTheTokensToTheAeSeqShape}: the
 * declared-token expansion of {@code CDISC-AD0898} over {@code AESEQ} must produce exactly the
 * {@code Check} the template authors with its tokens bound, and exactly the {@code Match_Datasets}
 * that {@code PMDA-AD0258} authors by hand for a pinned {@code AE}. That is the claim the whole
 * design rests on — expansion emits an <em>already-shipping</em> shape, so it needs no new
 * operator, syntax or join — and it is compared against the shipped corpus so a drift in either
 * rule breaks it.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>The Check half of that comparison was a cross-rule equality until 2026-09-17, and it is not
 * one any more — deliberately, and it is an OPEN ITEM, not a closed one.</b> D110f / D114 / D118 of
 * {@code plans/PLAN-typed-expression-engine.md} re-authored the four hand-written
 * {@code empty()}-proxy carriers ({@code CDISC-AD0053}, {@code CDISC-AD0256}, {@code PMDA-AD0258},
 * {@code PMDA-AD0053}) onto the phase-5b-J join match flag, so {@code PMDA-AD0258} now reads
 * {@code not AE._matched_} while {@code CDISC-AD0898} still templates
 * {@code var_exists(`&DOM.&DOMSEQ`) and empty(`&DOM.&DOMSEQ`)}. <b>CDISC-AD0898 is therefore the
 * FIFTH carrier of the retired proxy</b>, found by this very assertion going red — which is the
 * test doing its job. It was NOT rewritten with the other four: D118 closes the consumer rewrite at
 * four named rule ids, and D114's own precedent is that a further carrier is <em>flagged, not taken
 * under the standing go</em>. ⚑ Two things a follow-up must establish before it can be: whether an
 * expansion token is substituted inside a {@code MATCHED_FLAG} reference (spelled
 * {@code not `&DOM`._matched_}), and whether {@code Requirements.Variables.All} — which is what
 * makes the flag form skip cleanly on an absent parent — may carry a token at all (the loader's
 * R-gate forbids hoisting an expansion token into it, see {@code GuardPositionLintTest}'s
 * 2026-08-26 note). Until then the join shape stays the cross-rule pin and the Check is pinned
 * against the template's own text.
 * </p>
 */
class ShippedExpansionTemplateTest
{

    private static Rule shipped(String pkg, String coreId) throws IOException
    {
        RulePackage loaded = RulePackageLoader.load(CorpusPaths.rules(pkg));
        Rule rule = loaded.getRules().get(coreId);
        assertNotNull(rule, coreId + " must be present in " + pkg);
        assertNull(rule.getLoadError(), coreId + " must load without a load error");
        return rule;
    }


    private static IDataTable table(String name, String... columns)
    {
        MockTable t = MockTable.of().name(name);
        for (String column : columns)
        {
            t.col(column, "");
        }
        return t.build();
    }


    private static TokenExpander.Context ctx(IDataTable primary, Map<String, IDataTable> others,
            @Nullable List<String> standardDomains)
    {
        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : others.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return others.keySet();
            }
        };
        MetadataProvider provider = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.lenient().when(provider.getStandardDatasetNames())
                .thenReturn(standardDomains);
        return new TokenExpander.Context(ScopeVariableSource.of(inv, primary), provider,
                primary.getMetaData().getName());
    }


    private static Map<String, IDataTable> map(Object... pairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
        }
        return m;
    }


    private static List<Rule> expanded(WildcardExpander.ExpansionResult result)
    {
        return assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class, result).rules();
    }


    private static String reason(WildcardExpander.ExpansionResult result)
    {
        return assertInstanceOf(WildcardExpander.ExpansionResult.NoMatch.class, result).reason();
    }


    @Test
    void ad0898ExpansionBindsTheTokensToTheAeSeqShape() throws IOException
    {
        Rule template = shipped("rules-cdisc-adamig-1-2.json", "CDISC-AD0898");
        Rule handAuthored = shipped("rules-pmda-adamig-1-0.json", "PMDA-AD0258");

        IDataTable adae = table("ADAE", "STUDYID", "USUBJID", "AESEQ", "AETERM");
        IDataTable ae = table("AE", "STUDYID", "USUBJID", "AESEQ");

        List<Rule> rules = expanded(WildcardExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map("AE", ae), List.of("AE", "CM", "MH", "DM"))));

        assertEquals(1, rules.size(), "exactly one --SEQ column carries a known parent domain");
        Rule concrete = rules.get(0);
        assertEquals("CDISC-AD0898-AESEQ", concrete.effectiveId());
        // The expansion must bind &DOM/&DOMSEQ and change nothing else, so the expanded Check is
        // the template's own text with the tokens substituted. ⚠ This used to be an equality
        // against PMDA-AD0258's Check; see the class javadoc — that rule moved to the join match
        // flag under D118 and CDISC-AD0898 is the fifth, un-rewritten proxy carrier. Comparing the
        // parsed source keeps the assertion on what expansion actually owes.
        assertEquals(
                net.cumba.corej.core.expr.CheckExpressionParser
                        .parse("var_exists(AE.AESEQ) and empty(AE.AESEQ)"),
                assertInstanceOf(net.cumba.corej.core.model.CheckConditionExpression.class,
                        concrete.getCheck()).expr(),
                "the expansion must bind the tokens and leave the authored shape alone");
        // The JOIN half is still a cross-rule equality: D118 changed the Check of PMDA-AD0258, not
        // its Match_Datasets, so "expansion emits an already-shipping join" is unweakened.
        assertEquals(matchShape(handAuthored), matchShape(concrete));
    }


    /** {@code Match_Datasets} reduced to the fields the join actually reads. */
    private static List<String> matchShape(Rule rule)
    {
        List<MatchDataset> matched = rule.getMatchDatasets();
        assertNotNull(matched);
        return matched.stream().map(m -> m.getName() + "|" + m.getKeys() + "|" + m.getJoinType())
                .toList();
    }


    @Test
    void ad0898SkipsWithAReasonWhenTheParentDomainIsNotLoaded() throws IOException
    {
        Rule template = shipped("rules-cdisc-adamig-1-2.json", "CDISC-AD0898");
        IDataTable adae = table("ADAE", "STUDYID", "USUBJID", "AESEQ");

        String reason = reason(WildcardExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map(), List.of("AE"))));

        assertTrue(reason.contains("not among the loaded datasets"), reason);
        assertFalse(reason.isBlank(), "a skip must always carry a reason");
    }


    @Test
    void ad0898DoesNotExpandAdamsOwnSeqVariables() throws IOException
    {
        Rule template = shipped("rules-cdisc-adamig-1-2.json", "CDISC-AD0898");
        IDataTable adae = table("ADAE", "STUDYID", "USUBJID", "ASEQ", "SRCSEQ", "RECSEQ");
        // Datasets named A / SRC / REC are loaded deliberately: otherwise the "parent not loaded"
        // check would drop these three regardless and the test would pin nothing.
        Map<String, IDataTable> loaded = map("A", table("A", "ASEQ"), "SRC", table("SRC", "SRCSEQ"),
                "REC", table("REC", "RECSEQ"));

        String reason = reason(WildcardExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, loaded, List.of("AE", "CM", "MH", "DM"))));

        assertTrue(reason.contains("filtered out"), reason);
    }


    @Test
    void ad0591ExpandsOverEverySharedVariableButNotTheMergeKeys() throws IOException
    {
        Rule template = shipped("rules-cdisc-adamig-1-2.json", "CDISC-AD0591");
        IDataTable adae = table("ADAE", "STUDYID", "USUBJID", "AGE", "SEX", "AESEQ");
        IDataTable adsl = table("ADSL", "STUDYID", "USUBJID", "AGE", "SEX", "TRT01P");

        List<Rule> rules = expanded(WildcardExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map("ADSL", adsl), List.of())));

        assertEquals(List.of("CDISC-AD0591-AGE", "CDISC-AD0591-SEX"),
                rules.stream().map(Rule::effectiveId).toList());
        assertEquals(List.of("AGE", "ADSL.AGE"), rules.get(0).getOutcome().getOutputVariables());
        for (Rule concrete : rules)
        {
            assertNull(concrete.getExpansion(), "an expansion is concrete, never a template again");
            assertNull(concrete.getLoadError());
        }
    }


    @Test
    void ad0591SkipsWithAReasonWhenAdslIsNotLoaded() throws IOException
    {
        Rule template = shipped("rules-cdisc-adamig-1-2.json", "CDISC-AD0591");
        IDataTable adae = table("ADAE", "STUDYID", "USUBJID", "AGE");

        String reason = reason(WildcardExpander.tryExpand(template, adae.getMetaData(),
                ctx(adae, map(), List.of())));

        assertTrue(reason.contains("'ADSL' is not among the loaded datasets"), reason);
    }


    @Test
    void bothTemplatesDeclareAnExpansionBlockAndNoDanglingOperationReference() throws IOException
    {
        for (String coreId : List.of("CDISC-AD0591", "CDISC-AD0898"))
        {
            Rule template = shipped("rules-cdisc-adamig-1-2.json", coreId);
            List<net.cumba.corej.core.model.ExpansionDirective> directives = template
                    .getExpansion();
            assertNotNull(directives, coreId + " must carry an Expansion block");
            assertEquals(1, directives.size());
            assertNotNull(directives.get(0).getOver(),
                    coreId + " must name a recognised expansion source");
            // The pre-Fix-#146 shape referenced $adsl_value / $adam_seq with no Operations block:
            // the operands never resolved and the rule shipped as a silent no-op.
            assertNull(template.getOperations(), coreId + " needs no Operations block");
        }
    }

}
