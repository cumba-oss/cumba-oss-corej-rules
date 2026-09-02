package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The completeness gate of {@code documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md}
 * ({@code plans/done/PLAN-expression-docs-restructure.md} §4). Successor of the retired
 * {@code ExpressionDocCoverageTest} (which gated the absorbed
 * {@code expression-language-reference.md}), widened from functions/operators/accessors to the full
 * six-registry surface, and made bidirectional:
 *
 * <ul>
 * <li><b>Region pin</b> — the committed generated region equals
 * {@link ExpressionCheckSpecCensus#render()}, so a new registry entry fails the build until the
 * document is regenerated.</li>
 * <li><b>Direction 1 — completeness</b>: every roster token has a documented entry in the
 * hand-written half (the callable categories as {@code `token(}, the concept categories as
 * {@code `token`}).</li>
 * <li><b>Direction 2 — no phantom syntax</b>: every marker-bearing inventory row documents a token
 * that resolves to a registry entry, so a typo or a not-yet-shipped proposal cannot masquerade as
 * syntax.</li>
 * <li><b>Marker well-formedness</b>: every inventory row carries exactly one of
 * {@code authorable | internal | machine-emitted}, and every {@code machine-emitted} row carries
 * the "do not author" warning (owner ruling Q2: review agents consume this specification and must
 * not propose authoring loader-emitted shapes).</li>
 * </ul>
 */
class ExpressionCheckSpecDriftTest
{

    private static String document;

    private static String handWritten;

    private static Map<String, Map<String, String>> inventory;

    /** Every roster token across all categories (the direction-2 resolution universe). */
    private static Set<String> allTokens;

    @BeforeAll
    static void read() throws IOException
    {
        document = Files.readString(ExpressionCheckSpecCensus.documentFile());
        handWritten = ExpressionCheckSpecCensus.withoutRegion(document).replace("\\", "");
        inventory = ExpressionCheckSpecCensus.inventory();
        allTokens = new HashSet<>();
        inventory.values().forEach(m -> allTokens.addAll(m.keySet()));
    }


    @Test
    void committedRegionMatchesGeneratorOutput()
    {
        assertEquals(ExpressionCheckSpecCensus.render(), ExpressionCheckSpecCensus.region(document),
                "the expression-syntax-rosters region is stale — regenerate with"
                        + " lib/corej-cdisc-rules/scripts/gen-expression-check-spec.sh");
    }


    @Test
    void committedTsvMatchesGeneratorOutput() throws IOException
    {
        assertEquals(ExpressionCheckSpecCensus.renderTsv(),
                Files.readString(ExpressionCheckSpecCensus.tsvFile()),
                "expression-syntax-inventory.tsv is stale — regenerate with"
                        + " lib/corej-cdisc-rules/scripts/gen-expression-check-spec.sh");
    }


    /**
     * The callable categories are documented as {@code `name(} — same probe the predecessor used.
     */
    @Test
    void everyCallableTokenIsDocumented()
    {
        List<String> missing = new ArrayList<>();
        for (String category : List.of(ExpressionCheckSpecCensus.CAT_FUNCTION,
                ExpressionCheckSpecCensus.CAT_BOOLEAN_CALL, ExpressionCheckSpecCensus.CAT_OPERATION,
                ExpressionCheckSpecCensus.CAT_ACCESSOR, ExpressionCheckSpecCensus.CAT_MARKER,
                ExpressionCheckSpecCensus.CAT_COMPILER_CALL))
        {
            for (String token : inventory.get(category).keySet())
            {
                if (!handWritten.contains("`" + token + "("))
                {
                    missing.add(category + ": " + token);
                }
            }
        }
        assertTrue(missing.isEmpty(), "undocumented callable tokens (no `token(` entry in the"
                + " hand-written half): " + missing);
    }


    /** The concept categories are documented as a plain {@code `token`}. */
    @Test
    void everyConceptTokenIsDocumented()
    {
        List<String> missing = new ArrayList<>();
        for (String category : List.of(ExpressionCheckSpecCensus.CAT_BUILTIN_OPERAND,
                ExpressionCheckSpecCensus.CAT_BINARY_OPERATOR,
                ExpressionCheckSpecCensus.CAT_LITERAL_KIND,
                ExpressionCheckSpecCensus.CAT_OPERAND_KIND))
        {
            for (String token : inventory.get(category).keySet())
            {
                if (!handWritten.contains("`" + token + "`"))
                {
                    missing.add(category + ": " + token);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "undocumented concept tokens (no `token` mention in the hand-written half): "
                        + missing);
    }

    /** A marker cell, as the specification's inventory tables carry it. */
    private static final Pattern MARKER = Pattern
            .compile("\\b(authorable|internal|machine-emitted)\\b");

    /** The first backticked token of a table row. */
    private static final Pattern ROW_TOKEN = Pattern.compile("^\\|\\s*`([^`]+)`");

    /**
     * Scans every hand-written table row that carries a marker: its token must resolve to a
     * registry entry (direction 2), it must carry exactly one marker, and a machine-emitted row
     * must carry the do-not-author warning.
     */
    @Test
    void everyMarkerRowResolvesAndIsWellFormed()
    {
        List<String> problems = new ArrayList<>();
        int markerRows = 0;
        for (String line : handWritten.split("\n", -1))
        {
            Matcher marker = MARKER.matcher(line);
            if (!line.startsWith("|") || !marker.find())
            {
                continue;
            }
            markerRows++;
            List<String> markers = new ArrayList<>();
            markers.add(marker.group(1));
            while (marker.find())
            {
                markers.add(marker.group(1));
            }
            if (markers.size() != 1)
            {
                problems.add("multiple markers " + markers + " in: " + line);
            }
            if ("machine-emitted".equals(markers.get(0)) && !line.contains("do not author"))
            {
                problems.add("machine-emitted row without the \"do not author\" warning: " + line);
            }
            Matcher token = ROW_TOKEN.matcher(line);
            if (!token.find())
            {
                problems.add("marker row without a leading `token` cell: " + line);
                continue;
            }
            String name = token.group(1);
            int paren = name.indexOf('(');
            if (paren >= 0)
            {
                name = name.substring(0, paren);
            }
            if (!allTokens.contains(name.trim()))
            {
                problems.add(
                        "phantom syntax — marker row token not in any registry: " + name.trim());
            }
        }
        assertTrue(problems.isEmpty(), "inventory-row problems: " + problems);
        assertFalse(markerRows == 0,
                "no marker-bearing inventory rows found — the specification's hand-written"
                        + " chapters must carry the authorable/internal/machine-emitted tables");
    }

}
