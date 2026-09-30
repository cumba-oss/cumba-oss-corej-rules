package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The join-key authoring gate, ARMED: every keyed {@code Match_Datasets} entry of the authored
 * corpus declares its key columns in {@code Requirements.Variables}, so a study missing a key
 * <b>skips</b> the rule instead of flooding (a key absent on one side) or reaching {@code JKM R7}'s
 * all-absent execution error (every key absent on both sides).
 *
 * <p>
 * Source: {@code PLAN-join-key-authoring-gate} (meta repo) — owner rulings §6 Q1 (all keyed
 * entries), Q2 as amended (the PAIRING gate: one {@code All_Or_None} group per key column), Q3 (arm
 * this ratchet at the end), Q5 (expansion templates), Q6 (self-joins take their group like any
 * other entry) and Q9 (the first key is also bare in {@code All}). The owner's original wording,
 * <i>"make it mandatory … for now only on new and edited rules"</i>, described the period before a
 * ratchet existed; the sweep made every entry comply, which is what makes this check non-vacuous.
 * </p>
 *
 * <h2>The shape rules — no named exemption list</h2>
 * <table>
 * <caption>required declaration per key column</caption>
 * <tr>
 * <th>entry shape</th>
 * <th>required</th>
 * </tr>
 * <tr>
 * <td>ordinary ({@code Name: DS}) — single- or multi-key, any {@code Join_Type}, self-joins
 * included</td>
 * <td>an {@code All_Or_None} group holding the bare key and {@code DS.KEY} (a sided key:
 * {@code LEFT} and {@code DS.RIGHT}), whose every member is a side of THAT key; plus the entry's
 * <b>first</b> key bare in {@code All}</td>
 * </tr>
 * <tr>
 * <td>{@code Child: true} (SUPP--, CO, SQ--, RELREC primaries)</td>
 * <td>every key bare in {@code All}; nothing on the parent side, which is resolved per row — and no
 * group naming the key, unless an ordinary entry of the same rule keys on it too</td>
 * </tr>
 * <tr>
 * <td>expansion template ({@code &}-token name or key)</td>
 * <td>the first key bare in {@code All} when it is not a token; token keys and the {@code &DOM.}
 * half are exempt BY CONSTRUCTION — loader gate R6 bars an expansion token from
 * {@code Requirements.Variables}</td>
 * </tr>
 * </table>
 *
 * <p>
 * ⚑ A group may carry the same bare key paired with SEVERAL joined datasets ({@code FDA-SD9749}
 * joins DM and AE on {@code USUBJID}: {@code [USUBJID, DM.USUBJID, AE.USUBJID]}). Two separate
 * groups would repeat {@code USUBJID} across groups, which the loader reports as a WARNING (its
 * ruling D4) and {@code CrossCorpusDerivationTest} holds the shipped corpus to zero warnings; the
 * merged group is presence-equivalent, because all-or-none is transitive through the shared bare
 * member.
 * </p>
 *
 * <p>
 * ⛔ <b>It is a corpus lint, not a loader gate, on purpose.</b> The plan scoped out any engine
 * change, and a loader gate would also reject every external rule set that pre-dates the ruling.
 * The corpus lint is what stops THIS corpus drifting back, which is what the ruling asked for.
 * </p>
 *
 * <p>
 * ⭐ <b>Non-vacuity, twice.</b> The population is pinned by EXACT counts per shape (a check that
 * examined nothing would pass every per-entry assertion), and
 * {@link #eachShapeRedsWhenItsDeclarationIsRemoved()} runs the checker over a well-formed and a
 * sabotaged rule of every shape on every build, so a checker that stopped seeing violations reds
 * itself.
 * </p>
 */
class JoinKeyDeclarationLintTest
{

    /** The authored view — the surface a rule author edits. */
    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    /**
     * Keyed {@code Match_Datasets} entries in the authored corpus, per shape. Measured 2026-09-25
     * (PLAN-join-key-authoring-gate phase 0: 209 keyed entries in 202 rule files). ⚠ EXACT, not a
     * floor: a new keyed rule moves one of these, and the failure asks you to confirm it was
     * authored with its declarations — which this test then checks.
     */
    // ⭐ 197 stays 197 on 2026-09-30 (PLAN-rprfdy-offset-tp-join phase 4) — a count a RE-KEY
    // conserves proves nothing on its own, so the IDENTITIES were re-derived: exactly the three
    // SJ entries of CDISC-SEND-0401/0402/0403 (Keys [USUBJID, RPHASE]) left and exactly their
    // three DM entries (Keys [USUBJID]) arrived (D-SJ / C2).
    private static final int EXPECTED_ORDINARY = 197;

    private static final int EXPECTED_CHILD = 11;

    private static final int EXPECTED_TEMPLATE = 1;

    private static final Pattern TYPE_TAG = Pattern.compile(":(N|C|NUM|CHAR)$",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Per-shape counts and every violation found, for one rule or the whole corpus. */
    static final class Census
    {

        int ordinary;

        int child;

        int template;

        final List<String> violations = new ArrayList<>();

        void add(Census other)
        {
            ordinary += other.ordinary;
            child += other.child;
            template += other.template;
            violations.addAll(other.violations);
        }
    }

    @Test
    void everyKeyedJoinDeclaresItsKey() throws IOException
    {
        Census all = checkTree(CHECKS_DIR);
        assertTrue(all.violations.isEmpty(), all.violations.size()
                + " keyed Match_Datasets declaration(s) missing — every key"
                + " column of a keyed join is declared (PLAN-join-key-authoring-gate; the"
                + " rule-guide page authoring/joins.md, 'Declare the key — required for every"
                + " keyed join'):\n  " + String.join("\n  ", all.violations));
        assertEquals(EXPECTED_ORDINARY, all.ordinary,
                "ordinary keyed entries — a new keyed rule? confirm it declares its key, then move"
                        + " the pin");
        assertEquals(EXPECTED_CHILD, all.child, "Child: true keyed entries");
        assertEquals(EXPECTED_TEMPLATE, all.template, "expansion-template keyed entries");
    }


    @Test
    void eachShapeRedsWhenItsDeclarationIsRemoved()
    {
        String single = """
                Core: {Id: "T-SINGLE"}
                Requirements:
                  Variables:
                    All: ["USUBJID"]
                    All_Or_None: [["USUBJID", "DM.USUBJID"]]
                Match_Datasets:
                - Name: "DM"
                  Keys: ["USUBJID"]
                """;
        assertClean(single);
        assertRedNaming(single.replace("    All: [\"USUBJID\"]\n", ""), "T-SINGLE", "USUBJID",
                "first key");
        assertRedNaming(single.replace("    All_Or_None: [[\"USUBJID\", \"DM.USUBJID\"]]\n", ""),
                "T-SINGLE", "DM.USUBJID", "no All_Or_None group");

        String multi = """
                Core: {Id: "T-MULTI"}
                Requirements:
                  Variables:
                    All: ["VISITNUM:N"]
                    All_Or_None: [["VISITNUM", "TV.VISITNUM"], ["VISIT", "TV.VISIT"], ["VISITDY", "TV.VISITDY"]]
                Match_Datasets:
                - Name: "TV"
                  Keys: ["VISITNUM", "VISIT", "VISITDY"]
                """;
        assertClean(multi);
        assertRedNaming(multi.replace(", [\"VISITDY\", \"TV.VISITDY\"]", ""), "T-MULTI",
                "TV.VISITDY", "no All_Or_None group");
        assertRedNaming(multi.replace("[\"VISIT\", \"TV.VISIT\"]", "[\"VISIT\", \"TV.VISITDY\"]"),
                "T-MULTI", "TV.VISIT", "no All_Or_None group");
        assertRedNaming(
                multi.replace("[\"VISITNUM\", \"TV.VISITNUM\"]",
                        "[\"VISITNUM\", \"TV.VISITNUM\", \"SV.EPOCH\"]"),
                "T-MULTI", "SV.EPOCH", "not a side of key VISITNUM");

        String sided = """
                Core: {Id: "T-SIDED"}
                Requirements:
                  Variables:
                    All: ["VISITDY"]
                    All_Or_None: [["VISITDY", "TV.TVSTRL"]]
                Match_Datasets:
                - Name: "TV"
                  Keys: [{left: "VISITDY", right: "TVSTRL"}]
                """;
        assertClean(sided);
        assertRedNaming(sided.replace("\"TV.TVSTRL\"]]", "\"TV.VISITDY\"]]"), "T-SIDED",
                "TV.TVSTRL", "no All_Or_None group");

        String merged = """
                Core: {Id: "T-TWO-JOINS"}
                Requirements:
                  Variables:
                    All: ["USUBJID"]
                    All_Or_None: [["USUBJID", "DM.USUBJID", "AE.USUBJID"]]
                Match_Datasets:
                - Name: "DM"
                  Keys: ["USUBJID"]
                - Name: "AE"
                  Keys: ["USUBJID"]
                """;
        assertClean(merged);
        assertRedNaming(merged.replace(", \"AE.USUBJID\"", ""), "T-TWO-JOINS", "AE.USUBJID",
                "no All_Or_None group");

        // PLAN-rprfdy-offset-tp-join C1: a QUALIFIED key — TP joins on [DM.RPATHCD, RPHASE], the
        // record side of the first component being the row's bound DM record. Its declaration is
        // the same shape as any key's: DM.RPATHCD bare in All (it is the first key) and one group
        // holding DM.RPATHCD and TP.RPATHCD (MatchDataset.getRightKeys() answers the unqualified
        // name, so the loader's gate and this lint agree without a special arm). No corpus rule
        // carries the shape yet; the fixture is what keeps the two from disagreeing about it.
        String qualified = """
                Core: {Id: "T-QUALIFIED"}
                Requirements:
                  Variables:
                    All: ["USUBJID", "DM.RPATHCD", "RPHASE"]
                    All_Or_None: [["USUBJID", "DM.USUBJID"], ["DM.RPATHCD", "TP.RPATHCD"], ["RPHASE", "TP.RPHASE"]]
                Match_Datasets:
                - Name: "DM"
                  Keys: ["USUBJID"]
                - Name: "TP"
                  Keys: ["DM.RPATHCD", "RPHASE"]
                """;
        assertClean(qualified);
        assertRedNaming(qualified.replace(", [\"DM.RPATHCD\", \"TP.RPATHCD\"]", ""), "T-QUALIFIED",
                "DM.RPATHCD", "no All_Or_None group");
        assertRedNaming(
                qualified.replace("All: [\"USUBJID\", \"DM.RPATHCD\", \"RPHASE\"]",
                        "All: [\"USUBJID\", \"RPHASE\"]"),
                "T-QUALIFIED", "DM.RPATHCD", "first key");
        assertRedNaming(
                qualified.replace("[\"DM.RPATHCD\", \"TP.RPATHCD\"]",
                        "[\"DM.RPATHCD\", \"TP.RPATHCD\", \"SJ.RPATHCD\"]"),
                "T-QUALIFIED", "SJ.RPATHCD", "not a side of key DM.RPATHCD");

        String child = """
                Core: {Id: "T-CHILD"}
                Requirements:
                  Variables:
                    All: ["QNAM", "USUBJID", "IDVAR", "IDVARVAL"]
                Match_Datasets:
                - Name: "SUPP--"
                  Child: true
                  Keys: ["USUBJID", "IDVAR", "IDVARVAL"]
                """;
        assertClean(child);
        assertRedNaming(
                child.replace("All: [\"QNAM\", \"USUBJID\", \"IDVAR\", \"IDVARVAL\"]",
                        "All: [\"QNAM\", \"USUBJID\", \"IDVAR\"]"),
                "T-CHILD", "IDVARVAL", "Child key");
        assertRedNaming(
                child.replace("    All: [\"QNAM\", \"USUBJID\", \"IDVAR\", \"IDVARVAL\"]\n",
                        "    All: [\"QNAM\", \"USUBJID\", \"IDVAR\", \"IDVARVAL\"]\n"
                                + "    All_Or_None: [[\"IDVAR\", \"AE.IDVAR\"]]\n"),
                "T-CHILD", "IDVAR", "named by an All_Or_None group");

        // The exception (review round 2, M1): a Child key that ALSO keys an ordinary entry of the
        // same rule is legitimately named by that entry's group.
        String childAndDm = """
                Core: {Id: "T-CHILD-DM"}
                Requirements:
                  Variables:
                    All: ["QNAM", "USUBJID", "IDVAR", "IDVARVAL"]
                    All_Or_None: [["USUBJID", "DM.USUBJID"]]
                Match_Datasets:
                - Name: "SUPP--"
                  Child: true
                  Keys: ["USUBJID", "IDVAR", "IDVARVAL"]
                - Name: "DM"
                  Keys: ["USUBJID"]
                """;
        assertClean(childAndDm);
        assertRedNaming(childAndDm.replace("- Name: \"DM\"\n  Keys: [\"USUBJID\"]\n", ""),
                "T-CHILD-DM", "USUBJID", "named by an All_Or_None group");

        // ...and an expansion template sharing the key does NOT license the group (review round 3,
        // T1): a template carries no key group, so it is not an ordinary entry.
        String childAndTemplate = """
                Core: {Id: "T-CHILD-TPL"}
                Requirements:
                  Variables:
                    All: ["QNAM", "USUBJID", "IDVAR", "IDVARVAL", "STUDYID"]
                    All_Or_None: [["USUBJID", "XX.USUBJID"]]
                Match_Datasets:
                - Name: "SUPP--"
                  Child: true
                  Keys: ["USUBJID", "IDVAR", "IDVARVAL"]
                - Name: "&DOM"
                  Keys: ["STUDYID", "USUBJID", "&DOMSEQ"]
                """;
        assertRedNaming(childAndTemplate, "T-CHILD-TPL", "USUBJID",
                "named by an All_Or_None group");

        // Round 5, F1: a NAMELESS ordinary entry is exempt and inert
        // (RuleRunner.buildJoinedDatasets
        // skips it), so it licenses nothing — and, F2 a, it is not judged itself (control).
        String childAndInert = """
                Core: {Id: "T-CHILD-INERT"}
                Requirements:
                  Variables:
                    All: ["QNAM", "USUBJID", "IDVAR", "IDVARVAL"]
                    All_Or_None: [["USUBJID", "FOO.BAR"]]
                Match_Datasets:
                - Name: "SUPP--"
                  Child: true
                  Keys: ["USUBJID", "IDVAR", "IDVARVAL"]
                - Keys: ["USUBJID"]
                """;
        assertRedNaming(childAndInert, "T-CHILD-INERT", "USUBJID", "named by an All_Or_None group");
        assertClean(childAndInert.replace("    All_Or_None: [[\"USUBJID\", \"FOO.BAR\"]]\n", ""));

        // Round 5, F2 b (the reviewer's input, agreed with the loader): a token on the RIGHT side
        // makes the entry an expansion template too, so it licenses nothing whatever its name.
        String childAndRightTemplate = """
                Core: {Id: "T-CHILD-RTPL"}
                Requirements:
                  Variables:
                    All: ["QNAM", "USUBJID", "IDVAR", "IDVARVAL"]
                    All_Or_None: [["USUBJID", "DM.USUBJID"]]
                Match_Datasets:
                - Name: "SUPP--"
                  Child: true
                  Keys: ["USUBJID", "IDVAR", "IDVARVAL"]
                - Name: "DM"
                  Keys: [{left: "USUBJID", right: "&K"}]
                """;
        assertRedNaming(childAndRightTemplate, "T-CHILD-RTPL", "USUBJID",
                "named by an All_Or_None group");

        String template = """
                Core: {Id: "T-TEMPLATE"}
                Requirements:
                  Variables:
                    All: ["STUDYID"]
                Match_Datasets:
                - Name: "&DOM"
                  Keys: ["STUDYID", "USUBJID", "&DOMSEQ"]
                """;
        assertClean(template);
        assertRedNaming(template.replace("    All: [\"STUDYID\"]\n", ""), "T-TEMPLATE", "STUDYID",
                "first key");
    }


    @Test
    void aRuleFileTheAssemblerWouldNotReadIsReported(@TempDir Path aDir) throws IOException
    {
        String yaml = """
                Core: {Id: "T-FILE"}
                Requirements:
                  Variables:
                    All: ["USUBJID"]
                    All_Or_None: [["USUBJID", "DM.USUBJID"]]
                Match_Datasets:
                - Name: "DM"
                  Keys: ["USUBJID"]
                """;
        Files.writeString(aDir.resolve("T-FILE.yaml"), yaml);
        Census clean = checkTree(aDir);
        assertTrue(clean.violations.isEmpty(), clean.violations::toString);
        assertEquals(1, clean.ordinary);
        Files.writeString(aDir.resolve("T-STRAY.yml"), yaml.replace("T-FILE", "T-STRAY"));
        Files.writeString(aDir.resolve("T-STRAY2.json"), "{}");
        Census red = checkTree(aDir);
        assertEquals(2, red.violations.size(), red.violations::toString);
        assertTrue(red.violations.stream().anyMatch(v -> v.contains("T-STRAY.yml")),
                red.violations::toString);
        assertTrue(red.violations.stream().anyMatch(v -> v.contains("T-STRAY2.json")),
                red.violations::toString);
    }


    /**
     * Every regular file under {@code aDir}: a {@code .yaml} rule is checked, and ANY other file is
     * a violation. ⚑ The corpus assembler reads {@code .yaml} only ({@code RuleStorageAssembler}),
     * so a rule saved as {@code .yml} or {@code .json} would be neither shipped nor linted —
     * silently; a walk filtered to {@code .yaml} would be blind to exactly that file (review round
     * 1, F5).
     */
    static Census checkTree(Path aDir) throws IOException
    {
        Census all = new Census();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(aDir))
        {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files)
        {
            if (file.getFileName().toString().endsWith(".yaml"))
            {
                all.add(check(read(file)));
            }
            else
            {
                all.violations.add(file + ": not a .yaml file — the corpus assembler reads .yaml"
                        + " only, so this rule would be neither shipped nor checked here");
            }
        }
        return all;
    }


    private static void assertClean(String yaml)
    {
        Census c = check(parse(yaml));
        assertTrue(c.violations.isEmpty(),
                () -> "well-formed fixture flagged: " + c.violations + "\n" + yaml);
        assertTrue(c.ordinary + c.child + c.template > 0,
                () -> "fixture was not recognised as a keyed rule:\n" + yaml);
    }


    private static void assertRedNaming(String yaml, String ruleId, String name, String what)
    {
        Census c = check(parse(yaml));
        assertTrue(
                c.violations.stream()
                        .anyMatch(v -> v.contains(ruleId) && v.contains(name) && v.contains(what)),
                () -> "sabotage was not reported naming " + ruleId + " / " + name + " / '" + what
                        + "': " + c.violations + "\n" + yaml);
    }


    /**
     * The check for one rule. Package-visible so the sabotage test and any later caller share ONE
     * definition of the shape rules.
     */
    static Census check(Rule rule)
    {
        Census c = new Census();
        List<MatchDataset> joins = rule.getMatchDatasets();
        if (joins == null)
        {
            return c;
        }
        String id = rule.getCore() != null && rule.getCore().getId() != null
                ? rule.getCore().getId()
                : String.valueOf(rule.getId());
        VariableRequirement vars = rule.getRequirements() == null ? null
                : rule.getRequirements().getVariables();
        Set<String> all = folded(vars == null ? null : vars.getAll());
        List<Set<String>> groups = new ArrayList<>();
        if (vars != null && vars.getAllOrNoneGroups() != null)
        {
            vars.getAllOrNoneGroups().forEach(g -> groups.add(folded(g)));
        }
        for (MatchDataset md : joins)
        {
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            if (left == null || right == null || left.isEmpty())
            {
                continue;
            }
            String name = String.valueOf(md.getName());
            String where = id + " Match_Datasets '" + name + "'";
            boolean template = name.startsWith("&") || Stream.concat(left.stream(), right.stream())
                    .anyMatch(k -> k.startsWith("&"));
            if (template)
            {
                c.template++;
                String first = left.get(0);
                if (!first.startsWith("&") && !all.contains(fold(first)))
                {
                    c.violations.add(where + ": first key " + first
                            + " is not bare in Requirements.Variables.All (Q5/Q9)");
                }
                continue;
            }
            if (Boolean.TRUE.equals(md.getChild()))
            {
                c.child++;
                for (String k : left)
                {
                    if (!all.contains(fold(k)))
                    {
                        c.violations.add(where + ": Child key " + k
                                + " is not bare in Requirements.Variables.All (Q2 amendment rule 2)");
                    }
                    // A Child entry's parent is resolved per row and has no nameable side, so a
                    // group naming the key pairs it with nothing the ruling allows (Q2 amendment
                    // rule 2). Exempt only when the same bare key also keys an ordinary entry of
                    // this rule, whose group legitimately names it.
                    if (groups.stream().anyMatch(g -> g.contains(fold(k)))
                            && !keysAnOrdinaryEntry(joins, k))
                    {
                        c.violations.add(where + ": Child key " + k
                                + " is named by an All_Or_None group — a Child key is declared bare"
                                + " in All only (Q2 amendment rule 2)");
                    }
                }
                continue;
            }
            if (md.getName() == null)
            {
                // An ORDINARY entry without a name has no NAME.RIGHT side to declare against and
                // is inert at run time (RuleRunner.buildJoinedDatasets skips it) — exempt, as the
                // loader twin exempts it (review round 5, F2 a). A nameless Child entry was judged
                // above: the child pre-merger joins on it.
                continue;
            }
            c.ordinary++;
            if (!all.contains(fold(left.get(0))))
            {
                c.violations.add(where + ": first key " + left.get(0)
                        + " is not bare in Requirements.Variables.All (Q9)");
            }
            for (int i = 0; i < left.size(); i++)
            {
                String bare = fold(left.get(i));
                String joined = fold(name + "." + right.get(i));
                Set<String> sides = sidesOf(joins, left.get(i));
                Set<String> group = groups.stream()
                        .filter(g -> g.contains(bare) && g.contains(joined)).findFirst()
                        .orElse(null);
                if (group == null)
                {
                    c.violations.add(where + ": key " + left.get(i)
                            + " has no All_Or_None group holding both " + left.get(i) + " and "
                            + name + "." + right.get(i));
                    continue;
                }
                for (String member : group)
                {
                    if (!sides.contains(member))
                    {
                        c.violations.add(where + ": the All_Or_None group of key " + left.get(i)
                                + " carries " + member + ", which is not a side of key "
                                + left.get(i));
                    }
                }
            }
        }
        return c;
    }


    /**
     * The legal members of a key's group: the bare key, and {@code NAME.RIGHT} for every ordinary
     * entry of the rule that joins on that bare key.
     */
    private static Set<String> sidesOf(List<MatchDataset> joins, String bareKey)
    {
        Set<String> sides = new LinkedHashSet<>();
        sides.add(fold(bareKey));
        for (MatchDataset md : joins)
        {
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            // ORDINARY entries only, as keysAnOrdinaryEntry and the loader twin
            // (RulePackageLoader.joinSidesOf) read it: neither a Child entry nor an expansion
            // template (an &-token name or key) contributes a `NAME.RIGHT` side — a template's
            // name is a token, not a dataset (PLAN-rule-unknown-keys-gate review round 4, G2).
            if (left == null || right == null || md.getName() == null
                    || Boolean.TRUE.equals(md.getChild())
                    || String.valueOf(md.getName()).startsWith("&")
                    || left.stream().anyMatch(k -> k.startsWith("&"))
                    || right.stream().anyMatch(k -> k.startsWith("&")))
            {
                continue;
            }
            for (int i = 0; i < Math.min(left.size(), right.size()); i++)
            {
                if (fold(left.get(i)).equals(fold(bareKey)))
                {
                    sides.add(fold(md.getName() + "." + right.get(i)));
                }
            }
        }
        return sides;
    }


    private static boolean keysAnOrdinaryEntry(List<MatchDataset> joins, String bareKey)
    {
        for (MatchDataset md : joins)
        {
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            // ORDINARY only (review round 3, T1): neither a Child entry nor an expansion template
            // (an &-token name or key — on EITHER side, the loader's isExpansionTemplateEntry
            // predicate, round 5 F2 b) carries a key group, so neither licenses one; nor does a
            // NAMELESS ordinary entry, which is exempt and inert (round 5, F1).
            if (left == null || md.getName() == null || Boolean.TRUE.equals(md.getChild())
                    || String.valueOf(md.getName()).startsWith("&")
                    || left.stream().anyMatch(k -> k.startsWith("&"))
                    || (right != null && right.stream().anyMatch(k -> k.startsWith("&"))))
            {
                continue;
            }
            if (left.stream().anyMatch(k -> fold(k).equals(fold(bareKey))))
            {
                return true;
            }
        }
        return false;
    }


    private static Set<String> folded(@Nullable List<String> entries)
    {
        Set<String> out = new LinkedHashSet<>();
        if (entries != null)
        {
            entries.forEach(e -> out.add(fold(e)));
        }
        return out;
    }


    private static String fold(String entry)
    {
        return TYPE_TAG.matcher(entry.trim()).replaceFirst("").toUpperCase(Locale.ROOT);
    }


    private static Rule read(Path file)
    {
        try
        {
            return YAML.readValue(file.toFile(), Rule.class);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }


    private static Rule parse(String yaml)
    {
        try
        {
            return YAML.readValue(yaml, Rule.class);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
