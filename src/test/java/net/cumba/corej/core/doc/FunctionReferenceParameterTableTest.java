package net.cumba.corej.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            FunctionHelp.Entry entry = help.entry(d.name());
            if (entry == null || d.parameters().isEmpty())
            {
                continue; // the roster test owns "has a section"; a nullary function has no table
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
        assertTrue(checked >= MIN_TABLES_CHECKED, "tables checked: " + checked);
        assertEquals(List.of(), problems, problems.size() + " drifted table(s)");
    }


    /** The comparison must SEE a drift, or the test above is a tautology. */
    @Test
    void theComparisonSeesADrift()
    {
        FunctionHelp help = FunctionHelp.parse("""
                ### upper

                `upper(x)` → **value**

                Upper-cases.

                | parameter | type | required | description |
                |---|---|---|---|
                | `value` | any | no | wrong name, wrong flag |
                """);
        FunctionHelp.Entry entry = help.entry("upper");
        assertTrue(entry != null && entry.parameters().size() == 1);
        FunctionDescriptor d = FunctionRegistry.descriptor("upper");
        assertTrue(d != null && !d.parameters().isEmpty(), "premise: upper takes a parameter");
        List<String> engine = d.parameters().stream().map(Parameter::name).toList();
        List<String> doc = entry.parameters().stream().map(FunctionHelp.Parameter::name).toList();
        assertTrue(!doc.equals(engine), "the planted drift is seen: " + doc + " vs " + engine);
    }
}
