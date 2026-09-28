package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import org.junit.jupiter.api.Test;

/**
 * Wave 0 ({@code PLAN-binding-expressions} §5.1b, rows C4 / C5): the corpus audits that resolve a
 * {@code $}-probe to the dataset it reads must answer for a <b>compiled</b> binding by walking its
 * expression. Before the widening both resolved a {@code $}-id against {@code getOperations()}
 * alone, so a compiled binding reading a foreign dataset classified as local — the negative control
 * of each test below. The shipped corpus carries one compiled binding ({@code CDISC-CG0288}), which
 * reads no foreign dataset, so these synthetic rules are the only population that exercises the arm
 * until a later wave ports a callable that does.
 */
class CompiledBindingCorpusReadersTest
{

    private static Rule rule(String check, CompiledBinding... bindings)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("W0-CORPUS-READERS");
        rule.setCore(core);
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(check), check));
        rule.setCompiledBindings(List.of(bindings));
        return rule;
    }


    private static CompiledBinding binding(String name, String expression)
    {
        return new CompiledBinding(name, CheckExpressionParser.parse(expression), List.of(), null);
    }


    @Test
    void c4_aCompiledBindingReadingAForeignDatasetClassifiesForeign()
    {
        // `empty(AESEV)` is the local probe; `empty($d)` reads DM through the binding.
        Rule rule = rule("empty(AESEV) and empty($d)", binding("$d", "DM.RFSTDTC"));
        IntentAbsenceOptOut.Match match = IntentAbsenceOptOut.classify(rule);
        assertNotNull(match, "the $-probe resolves through the binding to a foreign dataset");
        assertEquals(List.of("DM"), match.foreignDatasets());
    }


    @Test
    void c4_aCompiledEmptinessFlagIsItsProbe()
    {
        // `$flag == true` over `$flag: empty(DM.RFSTDTC)` is the emptiness probe of DM.RFSTDTC.
        Rule rule = rule("empty(AESEV) and $flag == true", binding("$flag", "empty(DM.RFSTDTC)"));
        IntentAbsenceOptOut.Match match = IntentAbsenceOptOut.classify(rule);
        assertNotNull(match);
        assertEquals(List.of("DM"), match.foreignDatasets());
    }


    @Test
    void c5_aCompiledBindingIsAPresenceProbeOfTheDatasetItReads()
    {
        Rule rule = rule("var_not_exists($d) or AESEV == \"X\"", binding("$d", "DM.RFSTDTC"));
        List<GuardPositionAudit.PresenceLeaf> leaves = GuardPositionAudit
                .presenceLeavesIgnoringReadsElsewhere(rule);
        assertEquals(1, leaves.size(), "the $-name resolves through the binding: " + leaves);
        assertEquals("DM", leaves.get(0).dataset());
    }

}
