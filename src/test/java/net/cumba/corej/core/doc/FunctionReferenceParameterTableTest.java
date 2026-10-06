package net.cumba.corej.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.authoring.FunctionHelp;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import org.junit.jupiter.api.Test;

/**
 * The function reference's parameter tables are held to the engine's {@link FunctionDescriptor}s
 * ({@code PLAN-rule-authoring-api} phase 4, owner RE-Q7): for every registered function that takes
 * a parameter and has a {@code ### <name>} section, the section's table names exactly the
 * descriptor's parameters — the first (positional) one first, the keyword ones in any order — with
 * the descriptor's required flags. A function with no parameter needs no table. The tables were
 * hand-maintained and had drifted (measured 2026-10-06: 5 of 117 tables, one table missing); from
 * here a drift is red, and {@code FunctionHelp} (the editor's tooltip source) reads the same page.
 *
 * <p>
 * {@link FunctionReferenceRosterTest} holds the page's roster (every registered name has a section
 * or is a listed alias); this test holds the content of the sections that exist.
 * </p>
 */
class FunctionReferenceParameterTableTest
{

    /** A floor so a page that lost its tables cannot pass vacuously. */
    private static final int MIN_TABLES_CHECKED = 100;

    @Test
    void everyParameterTableNamesTheDescriptorsParameters() throws IOException
    {
        FunctionHelp help = FunctionHelp.parse(Files.readString(
                CorpusPaths.moduleDir().resolve("rule-guide/reference/functions.md"),
                StandardCharsets.UTF_8));
        Comparison result = compare(help, FunctionRegistry.all());
        assertTrue(result.checked() >= MIN_TABLES_CHECKED, "tables checked: " + result.checked());
        assertEquals(List.of(), result.problems(), result.problems().size() + " drifted table(s)");
    }


    /**
     * The comparison must SEE a drift, or the test above is a tautology: the same {@link #compare}
     * the main test runs, fed a planted page, reports a name drift and a required-flag drift with
     * their exact messages, and a correct table with none.
     */
    @Test
    void theComparisonSeesADrift()
    {
        FunctionDescriptor upper = FunctionRegistry.descriptor("upper");
        assertTrue(
                upper != null && upper.parameters().size() == 1
                        && "x".equals(upper.parameters().getFirst().name())
                        && upper.parameters().getFirst().required(),
                "premise: upper takes one required parameter x");
        Comparison nameDrift = compare(page("| `value` | any | yes | wrong name |"),
                List.of(upper));
        assertEquals(
                List.of("upper: the first (positional) parameter is x, the table opens with"
                        + " value", "upper: the table names [value], the engine declares [x]"),
                nameDrift.problems());
        Comparison flagDrift = compare(page("| `x` | any | no | wrong flag |"), List.of(upper));
        assertEquals(List.of("upper.x: the table says required=false, the engine says true"),
                flagDrift.problems());
        Comparison noTable = compare(FunctionHelp.parse("### upper\n\n`upper(x)`\n"),
                List.of(upper));
        assertEquals(List.of("upper: no parameter table; the engine declares [x]"),
                noTable.problems());
        Comparison correct = compare(page("| `x` | any | yes | right |"), List.of(upper));
        assertEquals(List.of(), correct.problems());
        assertEquals(1, correct.checked());
    }


    private static FunctionHelp page(String aRow)
    {
        return FunctionHelp.parse("""
                ### upper

                `upper(x)` → **value**

                Upper-cases.

                | parameter | type | required | description |
                |---|---|---|---|
                """ + aRow + "\n");
    }

    /**
     * The tables checked and the drifts found.
     *
     * @param checked
     *            the functions whose section has a parameter table that was compared
     * @param problems
     *            one line per drift
     */
    record Comparison(int checked, List<String> problems)
    {
    }

    /**
     * Hold every descriptor's parameters against its section's table: the first (positional)
     * parameter first, the keyword set equal, the required flags equal. A descriptor with no
     * section (the roster test owns that) or no parameter (nothing to table) is not compared.
     */
    static Comparison compare(FunctionHelp aHelp, Collection<FunctionDescriptor> aDescriptors)
    {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (FunctionDescriptor d : aDescriptors)
        {
            FunctionHelp.Entry entry = aHelp.entry(d.name());
            if (entry == null || d.parameters().isEmpty())
            {
                continue;
            }
            List<String> engine = d.parameters().stream().map(Parameter::name).toList();
            List<String> doc = entry.parameters().stream().map(FunctionHelp.Parameter::name)
                    .toList();
            if (doc.isEmpty())
            {
                problems.add(d.name() + ": no parameter table; the engine declares " + engine);
                continue;
            }
            checked++;
            if (!doc.getFirst().equals(engine.getFirst()))
            {
                problems.add(d.name() + ": the first (positional) parameter is " + engine.getFirst()
                        + ", the table opens with " + doc.getFirst());
            }
            Set<String> docSet = new LinkedHashSet<>(doc);
            Set<String> engineSet = new LinkedHashSet<>(engine);
            if (!docSet.equals(engineSet))
            {
                problems.add(
                        d.name() + ": the table names " + doc + ", the engine declares " + engine);
                continue;
            }
            for (Parameter p : d.parameters())
            {
                FunctionHelp.Parameter row = entry.parameters().stream()
                        .filter(r -> r.name().equals(p.name())).findFirst().orElseThrow();
                if (row.required() != p.required())
                {
                    problems.add(d.name() + "." + p.name() + ": the table says required="
                            + row.required() + ", the engine says " + p.required());
                }
            }
        }
        return new Comparison(checked, List.copyOf(problems));
    }
}
