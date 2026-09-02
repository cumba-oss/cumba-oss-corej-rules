package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Gate over the <b>machine-readable contract</b> of
 * {@code documentation/CORE-RULES-GUARD-MATRIX.md} §3 — the operator index that
 * {@code documentation/rule-inventory/lint-rules.py} parses at runtime rather than transcribes.
 *
 * <p>
 * <b>Why this exists.</b> The class cell is markdown prose, and on 2026-07-31
 * ({@code plans/PLAN-absent-column-guard-backlog.md} P2a) three cells were re-spelled from a bare
 * {@code D} to {@code **D-target** / **D-key**}. {@code lint-rules.py}'s row regex accepted only a
 * bare {@code [PEDV]}, so {@code is_unique_set}, {@code is_not_unique_set} and
 * {@code is_inconsistent_across_dataset} fell out of the parsed matrix entirely; the lint's
 * {@code if operator not in matrix: continue} then made every class-D verdict unreachable and the
 * lint emitted <b>zero</b> class-D findings for the next week, silently. Nothing failed — a lint
 * that reports nothing looks exactly like a clean corpus.
 * </p>
 *
 * <p>
 * The document is the source of truth and must stay free to say more than a letter, so this gate
 * does not freeze the prose. It freezes the <b>token grammar</b> the parser depends on, mirroring
 * {@code lint-rules.py}'s {@code parse_class_cell}: a class cell states its class either as a bare
 * {@code V} or as one or more bolded {@code **P**} / {@code **E**} / {@code **D-target**} /
 * {@code **D-key**} tokens, anything else around them being free prose. Re-spell a cell out of that
 * grammar and this test fails here instead of the lint going quiet there.
 * </p>
 *
 * <p>
 * It also cross-checks the two independent statements of the same fact: the matrix's class-D set
 * and {@link SilencingGuardScan#PYTHON_GUARD_REQUIRED_OPERATORS}. Both name the operators whose
 * Python implementation drops an absent grouping column and over-fires; if one moves without the
 * other, the Java gate and the Python lint disagree about which rules are at risk.
 * </p>
 */
class GuardMatrixClassParseGateTest
{

    private static final Path MATRIX = Path.of("documentation", "CORE-RULES-GUARD-MATRIX.md");

    /** A §3 index row: a backticked operator, then class / Req-Exp guard / Permissible guard. */
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([a-z_0-9]+)`\\s*\\|(.*)$");

    /** Mirrors {@code lint-rules.py}'s {@code CLASS_TOKEN_BOLD}. */
    private static final Pattern BOLD_TOKEN = Pattern
            .compile("\\*\\*([PEDV])(-target|-key)?\\*\\*");

    /** Mirrors {@code lint-rules.py}'s {@code CLASS_TOKEN_BARE}. */
    private static final Pattern BARE_TOKEN = Pattern.compile("^([PEDV])$");

    /** operator -> the class tokens its cell states, in document order. */
    private static Map<String, List<String>> parseIndex() throws IOException
    {
        Map<String, List<String>> index = new LinkedHashMap<>();
        List<String> unparseable = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(MATRIX))
        {
            number++;
            Matcher row = ROW.matcher(line);
            if (!row.matches())
            {
                continue;
            }
            String[] cells = row.group(2).split("\\|", -1);
            if (cells.length < 3)
            {
                continue; // not the §3 operator index
            }
            String operator = row.group(1);
            String cell = cells[0].strip();
            List<String> tokens = new ArrayList<>();
            Matcher bold = BOLD_TOKEN.matcher(cell);
            while (bold.find())
            {
                String token = bold.group(1) + (bold.group(2) == null ? "" : bold.group(2));
                if (!tokens.contains(token))
                {
                    tokens.add(token);
                }
            }
            if (tokens.isEmpty() && BARE_TOKEN.matcher(cell).matches())
            {
                tokens.add(cell);
            }
            if (tokens.isEmpty())
            {
                unparseable.add(MATRIX + ":" + number + ": `" + operator + "` -> \"" + cell + "\"");
            }
            else
            {
                index.put(operator, tokens);
            }
        }
        assertTrue(unparseable.isEmpty(),
                "CORE-RULES-GUARD-MATRIX.md §3 has operator rows whose class cell states no"
                        + " parseable class token. documentation/rule-inventory/lint-rules.py"
                        + " parses this table at runtime and SILENTLY SKIPS any row it cannot"
                        + " read, which switches that operator's guard lint off without any"
                        + " visible failure. Spell the class as a bare `V` or as bolded"
                        + " `**D-key**`-style tokens:\n  " + String.join("\n  ", unparseable));
        return index;
    }


    @Test
    void everyOperatorRowStatesAParseableClass() throws IOException
    {
        Map<String, List<String>> index = parseIndex();
        assertTrue(index.size() > 90,
                "guard matrix §3 shrank unexpectedly — parsed " + index.size() + " operators");
    }


    @Test
    void classDSetMatchesThePythonGuardRequiredOperators() throws IOException
    {
        Set<String> classD = new LinkedHashSet<>();
        parseIndex().forEach((operator, tokens) ->
        {
            if (tokens.getFirst().startsWith("D"))
            {
                classD.add(operator);
            }
        });
        assertEquals(SilencingGuardScan.PYTHON_GUARD_REQUIRED_OPERATORS, classD,
                "the guard matrix's class-D operators and"
                        + " SilencingGuardScan.PYTHON_GUARD_REQUIRED_OPERATORS state the same"
                        + " fact — the operators whose Python implementation drops an absent"
                        + " grouping column and over-fires — and have drifted apart");
    }


    @Test
    void theClassDRowsStateTheirPositionContract() throws IOException
    {
        // Plan A D-8 (ruling 3, 2026-08-23): the uniqueness pair collapsed to a single `D` —
        // the engine builds one key tuple and the `name` position is a member like any other —
        // while is_inconsistent_across_dataset keeps BOTH halves of the 2026-07-31 split (its
        // target is the checked value, not a key). The parsed token set must be NON-EMPTY and
        // carry the collapsed `D`, never just "the lint ran".
        Map<String, List<String>> index = parseIndex();
        for (String operator : SilencingGuardScan.PYTHON_GUARD_REQUIRED_OPERATORS)
        {
            List<String> tokens = index.get(operator);
            assertTrue(tokens != null && !tokens.isEmpty(),
                    "`" + operator + "` must parse to at least one class token");
            if (SilencingGuardScan.POSITION_BLIND_OPERATORS.contains(operator))
            {
                assertEquals(List.of("D"), tokens,
                        "`" + operator + "`'s class cell is the collapsed single `D` since"
                                + " 2026-08-23 (Plan A D-8): one key tuple, no privileged member");
            }
            else
            {
                assertEquals(List.of("D-target", "D-key"), tokens,
                        "`" + operator + "`'s class cell must keep naming BOTH halves of the"
                                + " 2026-07-31 split: the target position owes no guard, the"
                                + " grouping-key position owes one in the Check (R-4.11a)");
            }
        }
        assertEquals(Set.of("is_unique_set", "is_not_unique_set"),
                SilencingGuardScan.POSITION_BLIND_OPERATORS,
                "the collapse is scoped to the uniqueness pair only");
    }
}
