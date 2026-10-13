package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.MetadataAttribute;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.eval.NameSlots;
import org.junit.jupiter.api.Test;

/**
 * D5 of {@code PLAN-metadata-accessor-default-level} (owner, 2026-10-05: <i>"lets clean up the
 * corpus in the plan. So we have a clean corpus afterwards."</i>), table 2 row 11, runbook W3
 * MA-r2: the corpus build fails on a written {@code "DATA"} level that the short form can express.
 * An accessor call with no level means {@code DATA} (D1, filled once at load by
 * {@code MetadataOperandMapping.canonicalizeMetadataOperands}), so a written {@code "DATA"} is the
 * default spelled out — legal in the language (explicit levels stay legal, P8), but no longer the
 * corpus's spelling.
 *
 * <h2>What is judged (P7, as amended by O15)</h2>
 * <ul>
 * <li><b>Flagged</b> — every call to a {@link MetadataAttribute} that supports
 * {@link MetadataLevel#DATA} whose last positional argument is the level literal {@code "DATA"}
 * (any case, as the engine reads it): {@code ds_x("DATA")} → {@code ds_x()}, {@code var_x("DATA")}
 * → {@code var_x()}, {@code var_x("AETERM", "DATA")} → {@code var_x("AETERM")},
 * {@code var_x(variable_name, "DATA")} / {@code var_x(varname(), "DATA")} → {@code var_x()} (O15: a
 * variable name is a string; in the one-argument form a string is the name unless it is a level
 * literal).</li>
 * <li><b>Exempt</b> — {@code ds_x(<name>, "DATA")}: a dataset name always needs its level (D3, P7);
 * and {@code var_x(<level literal>, "DATA")}: a variable literally named {@code "DATA"} /
 * {@code "DEFINE"} / {@code "LIBRARY"} has no short form, because the one-argument form reads a
 * level literal as the level ({@code NameSlots.isLevelLiteral}). Both exemptions are asserted by
 * their own control case below; their corpus population is 0 today.</li>
 * </ul>
 *
 * <h2>Through what</h2>
 * <p>
 * The <b>authored</b> corpus, {@code rules-src/checks/**}{@code /*.yaml} — the source of every
 * shipped package — read as YAML (so multi-line folded scalars are whole expressions) and every
 * expression of a rule parsed with {@link CheckExpressionParser#parse} <b>raw</b>: never through
 * the loader, whose canonicalisation fills the default back in and would make every call look
 * written. The expressions walked are the plain {@code Check} and every level of a level-keyed
 * {@code Check}, every {@code Bindings} expression and every {@code Match_Datasets} {@code Filter}
 * (an authored {@code Precondition} does not load — it is engine-internal, rule-check schema).
 * Offenders are reported as a <b>roster</b> of {@code (rule, where, call)} rows, one per
 * occurrence, never a count.
 * </p>
 *
 * <h2>Population, sensitivity</h2>
 * <p>
 * Floors on files, expressions and accessor calls read, so a run that read nothing cannot pass.
 * {@link #sensitivityControl} proves on every run that the classifier flags each P7 shape, leaves
 * the short form and the other levels alone, and exempts the two shapes above.
 * </p>
 */
class WrittenDataLevelLintTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The sections of an authored rule that carry expressions. */
    private static final List<String> SECTIONS = List.of("Check", "Bindings", "Match_Datasets");

    /** The keys whose text is an expression inside those sections. */
    private static final Set<String> EXPRESSION_KEYS = Set.of("expression", "Filter");

    /**
     * Floors, measured 2026-10-09 at runbook W3 (both corpora alike): 3 419 authored files, 4 497
     * expressions, 326 metadata accessor calls (113 of them written {@code "DATA"} before MA-r2).
     */
    private static final int MIN_FILES = 3300;

    private static final int MIN_EXPRESSIONS = 4300;

    private static final int MIN_ACCESSOR_CALLS = 300;

    @Test
    void noAuthoredExpressionWritesTheDefaultDataLevel() throws IOException
    {
        sensitivityControl();

        Path checks = CorpusPaths.moduleDir().resolve("rules-src").resolve("checks");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(checks))
        {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .toList();
        }
        Census census = new Census();
        for (Path file : files)
        {
            JsonNode rule = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String id = rule.path("Core").path("Id").asText(file.getFileName().toString());
            for (String section : SECTIONS)
            {
                collect(rule.get(section), section, id, census);
            }
        }
        assertTrue(files.size() >= MIN_FILES, "only " + files.size() + " authored files read");
        assertTrue(census.expressions >= MIN_EXPRESSIONS,
                "only " + census.expressions + " authored expressions parsed");
        assertTrue(census.accessorCalls >= MIN_ACCESSOR_CALLS,
                "only " + census.accessorCalls + " metadata accessor calls seen");

        List<String> shown = census.offenders.stream().sorted().toList();
        assertEquals(List.of(), shown.size() > 25 ? shown.subList(0, 25) : shown,
                census.offenders.size() + " authored metadata accessor calls write the default"
                        + " \"DATA\" level (D5). Drop the level: ds_name(\"DATA\") -> ds_name(),"
                        + " var_label(\"DATA\") -> var_label(), var_label(\"AETERM\", \"DATA\") ->"
                        + " var_label(\"AETERM\"). Exempt: ds_*(<name>, \"DATA\") (D3).");
    }


    /**
     * The classifier must flag every P7 shape, leave the short form and the explicit non-default
     * levels alone, and exempt {@code ds_*(<name>, "DATA")} and a level-literal variable name —
     * else the empty roster above proves nothing.
     */
    private static void sensitivityControl()
    {
        Map<String, Integer> flagged = Map.of("var_label(\"DATA\") == \"x\"", 1,
                "ds_name(\"DATA\") =~ /^AE/ and len(ds_label(\"data\")) > 40", 2,
                "var_label(\"AETERM\", \"DATA\") != \"\"", 1,
                "var_type(variable_name, \"DATA\") != var_type(varname(), \"DATA\")", 2,
                "not empty(var_format($v, \"DATA\", dataset=\"AE\"))", 1);
        for (Map.Entry<String, Integer> c : flagged.entrySet())
        {
            Census census = new Census();
            scan("CTRL", "Check", c.getKey(), census);
            assertEquals(c.getValue(), census.offenders.size(), "control: " + c.getKey()
                    + " must be flagged " + c.getValue() + "x, got " + census.offenders);
            assertEquals(0, census.exempt.size(), "control: nothing exempt in " + c.getKey());
        }
        for (String clean : List.of("ds_name() =~ /^AE/", "var_label() != var_label(\"DEFINE\")",
                "var_label(\"AETERM\") == var_label(\"AETERM\", \"LIBRARY\")",
                "ds_structure(\"DEFINE\") == \"x\"", "label_template(\"LIBRARY\") == \"x\""))
        {
            Census census = new Census();
            scan("CTRL", "Check", clean, census);
            assertEquals(List.of(), census.offenders, "control: " + clean + " is the short form");
            assertTrue(census.accessorCalls > 0, "control: " + clean + " reads an accessor");
        }
        Census exempt = new Census();
        scan("CTRL", "Check", "ds_name(\"DM\", \"DATA\") == \"DM\"", exempt);
        assertEquals(List.of(), exempt.offenders,
                "control: ds_*(<name>, \"DATA\") is exempt — a dataset name needs its level (D3)");
        assertEquals(1, exempt.exempt.size(), "control: the ds_* exemption is seen, not missed");
        Census levelNamed = new Census();
        scan("CTRL", "Check", "var_label(\"DEFINE\", \"DATA\") == \"x\"", levelNamed);
        assertEquals(List.of(), levelNamed.offenders,
                "control: a variable named like a level has no one-argument form");
        assertEquals(1, levelNamed.exempt.size(), "control: the level-name exemption is seen");
    }


    /** Every expression text under {@code node} (one rule section), scanned. */
    private static void collect(JsonNode node, String where, String id, Census census)
    {
        if (node == null)
        {
            return;
        }
        if (node.isTextual() && "Check".equals(where))
        {
            scan(id, where, node.asText(), census);
        }
        else if (node.isObject())
        {
            for (Map.Entry<String, JsonNode> e : node.properties())
            {
                if (EXPRESSION_KEYS.contains(e.getKey()) && e.getValue().isTextual())
                {
                    scan(id, where, e.getValue().asText(), census);
                }
                else
                {
                    collect(e.getValue(), where + "." + e.getKey(), id, census);
                }
            }
        }
        else if (node.isArray())
        {
            for (int i = 0; i < node.size(); i++)
            {
                collect(node.get(i), where + "[" + i + "]", id, census);
            }
        }
    }


    private static void scan(String id, String where, String text, Census census)
    {
        census.expressions++;
        walk(CheckExpressionParser.parse(text), id, where, census);
    }


    private static void walk(Expr e, String id, String where, Census census)
    {
        switch (e)
        {
        case Expr.Call c ->
        {
            judge(c, id, where, census);
            c.args().forEach(a -> walk(a, id, where, census));
            c.kwargs().values().forEach(a -> walk(a, id, where, census));
        }
        case Expr.Binary b ->
        {
            walk(b.left(), id, where, census);
            walk(b.right(), id, where, census);
        }
        case Expr.And a -> a.parts().forEach(p -> walk(p, id, where, census));
        case Expr.Or o -> o.parts().forEach(p -> walk(p, id, where, census));
        case Expr.Not n -> walk(n.inner(), id, where, census);
        case Expr.Lit l when l.kind() == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) l.value();
            items.forEach(i -> walk(i, id, where, census));
        }
        case Expr.Lit l when l.kind() == Expr.LitKind.SIDED ->
        {
            Expr.SidedKey k = (Expr.SidedKey) l.value();
            walk(k.left(), id, where, census);
            walk(k.right(), id, where, census);
        }
        case Expr.Lit _,Expr.Ref _ ->
        {
            // a scalar literal or a reference holds no call
        }
        }
    }


    /** P7: one accessor call — flagged, exempt, or not a written DATA level at all. */
    private static void judge(Expr.Call c, String id, String where, Census census)
    {
        MetadataAttribute attr = MetadataAttribute.fromFunction(c.name());
        if (attr == null)
        {
            return;
        }
        census.accessorCalls++;
        List<Expr> args = c.args();
        if (!attr.supports(MetadataLevel.DATA) || args.isEmpty()
                || !(args.getLast() instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING
                || MetadataLevel.tryParse((String) lit.value()) != MetadataLevel.DATA)
        {
            return;
        }
        String row = id + "\t" + where + "\t" + ExpressionPrinter.print(c);
        boolean named = args.size() == 2;
        boolean exempt = named && (attr.scope() == MetadataAttribute.Scope.DATASET
                || NameSlots.isLevelLiteral(args.getFirst()));
        (exempt ? census.exempt : census.offenders).add(row);
    }

    /** One scan's tallies and rosters. */
    private static final class Census
    {

        int expressions;

        int accessorCalls;

        /** One row per occurrence (a call written twice in one expression is two rows). */
        final List<String> offenders = new ArrayList<>();

        final List<String> exempt = new ArrayList<>();
    }
}
