package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The universal-absence authoring gate: no shipped rule tests a wildcard template for presence
 * <b>only in negated form</b>.
 *
 * <p>
 * The engine treats a template named in a presence call ({@code var_exists("TRTxxP")}) as an
 * <b>expansion anchor</b>: {@code WildcardExpander.tryExpand} expands the rule once per matching
 * column tuple, and when no column matches it answers {@code NoMatch}, which
 * {@code DatasetRuleResolver} routes to a SKIP. A rule whose every presence template is negated —
 * {@code not var_exists("TRTxxP") and not var_exists("TRTxxA")} — is violated exactly when no such
 * column exists, which is exactly the case the engine skips; whenever a column does exist, the
 * negated test is false. Such a rule can never fire (engine gap G4). {@code CDISC-AD0581} had this
 * shape until {@code PLAN-wildcard-universal-absence} (meta repo) moved its eight templates into
 * {@code Requirements.Variables.None}; {@code PLAN-dynamic-column-functions} (owner Q13,
 * 2026-09-30) moved them on into the Check as {@code count(find_vars("TRTxxP")) == 0} and its
 * siblings.
 * </p>
 *
 * <p>
 * <b>The fix</b> (R-5.31, since 2026-09-30). A negated presence template asks <i>"is no member of
 * the family present?"</i>, and {@code count(find_vars("TRTxxP")) == 0} asks exactly that, in the
 * Check, without making the rule a template: {@code find_vars} returns the matching column names
 * and never expands the rule, so a dataset carrying a member runs and PASSES instead of being
 * skipped. ⚑ The former fix — the template as a {@code Requirements.Variables.None} entry — skipped
 * such a dataset (a conformant dataset read "could not check"). Keep the entry a template
 * ({@code TRTxxP}): like every column-name match it ignores letter case (owner ruling 2026-09-28,
 * register {@code CIT §1}; R-5.31), so it also catches a lower-case {@code trt01p} — a
 * {@code /regex/} buys nothing here and is harder to read. The former {@code None} move was exact
 * only when every template was a top-level conjunct of a single-level Check; under an {@code or}
 * ({@code not var_exists("TRTxxP") or AVAL > 100}) it was not, because {@code None} skips the whole
 * rule — {@code find_vars} composes under {@code or} like any predicate. Naming the family's first
 * instance ({@code TRT01P} for {@code TRTxxP}) is exact only where the IG guarantees the family is
 * contiguous from {@code 01} / {@code 1} in that dataset class — ADSL. A BDS dataset copies ADSL
 * variables selectively, so a period-2 dataset may carry {@code TRT02P} without {@code TRT01P}, and
 * the first-instance form would report it falsely. The rule-guide page
 * {@code authoring/wildcards.md} states the authoring rule this lint enforces.
 * </p>
 *
 * <p>
 * <b>What this lint does NOT check.</b> It flags one shape: every presence template of the rule
 * negated. A single positive presence template clears the rule, and that is weaker than "the
 * zero-match skip loses nothing". Two cases pass it and can still lose a finding: a marker bound
 * <i>only</i> by a negated template while another marker has a positive anchor, and a positive
 * anchor under {@code or} beside a non-template sibling ({@code var_exists("TRTxxP") or X == "Y"}
 * skips on zero matches, and with it the {@code X} branch). Neither is detected here (review round
 * 1 of {@code PLAN-wildcard-universal-absence}; the corpus was not censused for them).
 * </p>
 *
 * <h2>Detection, on the parsed Check</h2>
 * <p>
 * Every declared check level of every rule in the generated corpus ({@link CorpusPaths#rules()},
 * loaded through {@link RulePackageLoader}, so the tree is the one the engine runs) is walked with
 * a <b>polarity</b>: {@code and} / {@code or} keep it, {@code not} flips it, and a negative
 * spelling ({@code var_not_exists}) flips it once more, so
 * {@code not (var_exists(A) or var_exists(B))} negates both. A presence call ({@code var_exists},
 * {@code var_not_exists}, {@code ds_exists}, {@code ds_not_exists} — the name calls the expander
 * collects templates from) whose name operand is a template <b>by the expander's own
 * classification</b> ({@link WildcardExpander#hasRealWildcards}) is a site. A presence call reached
 * through any other node (a comparison, a function argument, a compiled binding) has UNKNOWN
 * polarity: it is never counted as negated, and the number of such sites is pinned, so a new one
 * reds and asks for a decision instead of being guessed at.
 * </p>
 *
 * <h2>Non-vacuity, every run</h2>
 * <ul>
 * <li>the population of rules carrying at least one presence template is pinned EXACTLY;</li>
 * <li>synthetic rules parsed through {@link RulePackageLoader#loadFromString} show the detector
 * flags an all-negated rule, does not flag an anchored one, and carries polarity through
 * {@code not (… or …)} and a negative spelling.</li>
 * </ul>
 */
class WildcardUniversalAbsenceLintTest
{

    /**
     * Distinct shipped rule ids carrying at least one presence template. ⚠ EXACT, not a floor: a
     * new presence-template rule moves it, and the failure asks you to confirm the rule has a
     * positive anchor — which this test then checks. Measured 2026-09-28
     * (PLAN-wildcard-universal-absence): 141 before {@code CDISC-AD0581}'s templates moved out of
     * its Check into {@code Requirements.Variables.None}, 140 after. Re-derived 2026-09-30
     * (PLAN-dynamic-column-functions Q13): still 140 — AD0581's templates came back into its Check
     * as {@code find_vars} entries, which are not presence calls and never anchor an expansion.
     */
    private static final int EXPECTED_PRESENCE_TEMPLATE_RULES = 140;

    /**
     * Presence-template sites whose polarity the walk cannot decide (reached through a comparison,
     * a function argument or a compiled binding). Measured 2026-09-28: none.
     */
    private static final int EXPECTED_UNKNOWN_POLARITY_SITES = 0;

    private static final Set<String> PRESENCE_CALLS = Set.of("var_exists", "var_not_exists",
            "ds_exists", "ds_not_exists");

    private static final Set<String> NEGATIVE_SPELLINGS = Set.of("var_not_exists", "ds_not_exists");

    private static final String STANDARDS = "\"standards\": [{\"id\": \"adam/adamig-1-3\","
            + " \"role\": \"primary\"}]";

    /** The polarity of a presence site. */
    enum Polarity
    {

        POSITIVE, NEGATED, UNKNOWN;

        Polarity flip()
        {
            return switch (this)
            {
            case POSITIVE -> NEGATED;
            case NEGATED -> POSITIVE;
            case UNKNOWN -> UNKNOWN;
            };
        }
    }


    /** One presence call naming a template. */
    record Site(String template, Polarity polarity)
    {
    }


    /** The corpus census: population, undecidable sites, and every flagged rule. */
    static final class Census
    {

        final Set<String> templateRules = new TreeSet<>();

        final Set<String> unknownSites = new TreeSet<>();

        final Map<String, Set<String>> flagged = new TreeMap<>();

        void add(String ruleId, List<Site> sites)
        {
            if (sites.isEmpty())
            {
                return;
            }
            templateRules.add(ruleId);
            sites.stream().filter(s -> s.polarity() == Polarity.UNKNOWN)
                    .forEach(s -> unknownSites.add(ruleId + " " + s.template()));
            if (isFlagged(sites))
            {
                Set<String> templates = flagged.computeIfAbsent(ruleId, _ -> new TreeSet<>());
                sites.forEach(s -> templates.add(s.template()));
            }
        }
    }

    @Test
    void noRuleNegatesEveryPresenceTemplate() throws IOException
    {
        Census census = censusOf(CorpusPaths.rules());
        List<String> lines = new ArrayList<>();
        census.flagged.forEach((id, templates) -> lines.add(id + ": " + templates));
        assertTrue(census.flagged.isEmpty(), census.flagged.size()
                + " rule(s) test every presence template only in negated form. The engine expands"
                + " a template once per matching column and SKIPS the rule when none matches —"
                + " exactly the case a negated presence test is written to catch — so such a rule"
                + " can never fire (engine gap G4). Fix (R-5.31): test the family with"
                + " count(find_vars(\"TRTxxP\")) == 0 in the Check instead of"
                + " not var_exists(\"TRTxxP\") — find_vars matches the template against the"
                + " dataset's columns (ignoring letter case, like every column-name match) and"
                + " answers [] when none is present, so the rule RUNS and fires, or passes when a"
                + " member is present. (The former fix, Requirements.Variables.None, SKIPS a"
                + " dataset carrying a member of the family, where the rule should run and pass.)"
                + " Name the first instance (TRT01P for TRTxxP) only"
                + " where the IG guarantees the family starts at 01/1 in that dataset class (ADSL;"
                + " not BDS, which copies variables selectively). If the rule really tests a member"
                + " per index, add the positive anchor it has"
                + " (var_exists(\"TRTxxAN\") and not var_exists(\"TRTxxA\")). See the rule-guide"
                + " page authoring/wildcards.md.\n  " + String.join("\n  ", lines));
        assertEquals(EXPECTED_PRESENCE_TEMPLATE_RULES, census.templateRules.size(),
                "rules carrying a presence template — a new one? confirm it has a positive"
                        + " anchor (this test checks that), then move the pin");
        assertEquals(EXPECTED_UNKNOWN_POLARITY_SITES, census.unknownSites.size(),
                "presence-template sites of undecidable polarity — decide whether each can"
                        + " be the rule's only anchor, then move the pin: " + census.unknownSites);
    }


    @Test
    void theDetectorSeesEachShape() throws IOException
    {
        // (b) every presence template negated: flagged, naming both templates.
        Census allNegated = censusOfRule("T-NEG",
                "not var_exists(\\\"TRTP\\\") and not var_exists(\\\"TRTxxP\\\")"
                        + " and not var_exists(\\\"TRxxPGy\\\")");
        assertEquals(Map.of("T-NEG", Set.of("TRTxxP", "TRxxPGy")), allNegated.flagged);
        assertEquals(Set.of("T-NEG"), allNegated.templateRules,
                "a literal (TRTP) is not a template");

        // (c) a positive anchor: a template rule, not flagged.
        Census anchored = censusOfRule("T-ANCHORED",
                "var_exists(\\\"TRTxxAN\\\") and not var_exists(\\\"TRTxxA\\\")");
        assertEquals(Set.of("T-ANCHORED"), anchored.templateRules);
        assertTrue(anchored.flagged.isEmpty(), anchored.flagged::toString);

        // (d) polarity through not (… or …): both negated, flagged.
        Census deMorgan = censusOfRule("T-NOT-OR",
                "not (var_exists(\\\"TRTxxP\\\") or var_exists(\\\"TRTxxA\\\"))");
        assertEquals(Map.of("T-NOT-OR", Set.of("TRTxxA", "TRTxxP")), deMorgan.flagged);

        // ... and a double negation is an anchor again.
        Census doubleNot = censusOfRule("T-NOT-NOT",
                "not (not var_exists(\\\"TRTxxP\\\") or not var_exists(\\\"TRTxxA\\\"))");
        assertEquals(Set.of("T-NOT-NOT"), doubleNot.templateRules);
        assertTrue(doubleNot.flagged.isEmpty(), doubleNot.flagged::toString);

        // A negative spelling negates; a negated negative spelling anchors.
        assertEquals(Set.of("T-NOT-EXISTS"),
                censusOfRule("T-NOT-EXISTS", "var_not_exists(\\\"TRTxxP\\\")").flagged.keySet());
        assertTrue(censusOfRule("T-NOT-NOT-EXISTS", "not var_not_exists(\\\"TRTxxP\\\")").flagged
                .isEmpty());

        // A presence call inside a comparison is UNKNOWN: counted, never flagged.
        Census unknown = censusOfRule("T-UNKNOWN",
                "var_exists(\\\"TRTxxP\\\") == false and not var_exists(\\\"TRTxxA\\\")");
        assertEquals(Set.of("T-UNKNOWN TRTxxP"), unknown.unknownSites);
        assertTrue(unknown.flagged.isEmpty(), unknown.flagged::toString);

        // (e) the R-5.31 fix itself (PLAN-dynamic-column-functions §2.8): a family tested through
        // count(find_vars("<template>")) == 0 is no presence template at all — the rule runs
        // whether or not a member is present — so it is neither flagged nor counted.
        Census findVars = censusOfRule("T-FV",
                "not var_exists(\\\"TRTP\\\") and count(find_vars(\\\"TRTxxP\\\")) == 0");
        assertTrue(findVars.flagged.isEmpty(), findVars.flagged::toString);
        assertTrue(findVars.templateRules.isEmpty(), findVars.templateRules::toString);
    }


    /** The census of every {@code rules-*.json} package in {@code aRulesDir}. */
    static Census censusOf(Path aRulesDir) throws IOException
    {
        List<Path> packages;
        try (Stream<Path> files = Files.list(aRulesDir))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(!packages.isEmpty(), "no rule package under " + aRulesDir);
        Census census = new Census();
        for (Path file : packages)
        {
            addPackage(census, RulePackageLoader.load(file));
        }
        return census;
    }


    private static Census censusOfRule(String anId, String anExpression) throws IOException
    {
        String json = "{" + STANDARDS + ", \"rules\": {\"" + anId + "\": {\"Core\": {\"Id\": \""
                + anId + "\", \"Status\": \"Published\", \"Version\": \"1\"},"
                + " \"Check\": {\"expression\": \"" + anExpression + "\"}}}}";
        Census census = new Census();
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        assertEquals(1, pkg.getRules().size(), json);
        // Review round 2: a shape that fails to LOAD has no Check to walk, and an empty census
        // would pass every "not flagged" assertion vacuously — so every fixture must load clean.
        Rule loaded = pkg.getRules().get(anId);
        org.junit.jupiter.api.Assertions.assertNotNull(loaded, json);
        org.junit.jupiter.api.Assertions.assertNull(loaded.getLoadError(),
                () -> anId + " must load clean: " + loaded.getLoadError());
        addPackage(census, pkg);
        return census;
    }


    private static void addPackage(Census census, RulePackage pkg)
    {
        for (Rule rule : pkg.getRules().values())
        {
            if (rule != null)
            {
                census.add(idOf(rule), sitesOf(rule));
            }
        }
    }


    /** Every presence-template site of {@code rule}: every check level and compiled binding. */
    static List<Site> sitesOf(Rule rule)
    {
        List<Site> sites = new ArrayList<>();
        for (CheckCondition level : rule.checkConditions())
        {
            walk(level, Polarity.POSITIVE, sites);
        }
        List<CompiledBinding> bindings = rule.getCompiledBindings();
        if (bindings != null)
        {
            bindings.forEach(b -> walk(b.expression(), Polarity.UNKNOWN, sites));
        }
        return sites;
    }


    /** A rule is flagged when it names a presence template and every one is negated. */
    static boolean isFlagged(List<Site> sites)
    {
        return !sites.isEmpty() && sites.stream().allMatch(s -> s.polarity() == Polarity.NEGATED);
    }


    private static void walk(@Nullable CheckCondition condition, Polarity polarity,
            List<Site> sites)
    {
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().forEach(c -> walk(c, polarity, sites));
        case CheckConditionAny any -> any.getConditions().forEach(c -> walk(c, polarity, sites));
        case CheckConditionNot not -> walk(not.getCondition(), polarity.flip(), sites);
        case CheckConditionExpression expr -> walk(expr.expr(), polarity, sites);
        case null ->
        {
            // no Check on this level
        }
        }
    }


    private static void walk(Expr e, Polarity polarity, List<Site> sites)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> walk(p, polarity, sites));
        case Expr.Or o -> o.parts().forEach(p -> walk(p, polarity, sites));
        case Expr.Not n -> walk(n.inner(), polarity.flip(), sites);
        case Expr.Call c ->
        {
            String template = PRESENCE_CALLS.contains(c.name()) && c.args().size() == 1
                    ? templateName(c.args().get(0))
                    : null;
            if (template != null)
            {
                sites.add(new Site(template,
                        NEGATIVE_SPELLINGS.contains(c.name()) ? polarity.flip() : polarity));
                return;
            }
            c.args().forEach(a -> walk(a, Polarity.UNKNOWN, sites));
            c.kwargs().values().forEach(a -> walk(a, Polarity.UNKNOWN, sites));
        }
        case Expr.Binary b ->
        {
            walk(b.left(), Polarity.UNKNOWN, sites);
            walk(b.right(), Polarity.UNKNOWN, sites);
        }
        case Expr.Lit l ->
        {
            if (l.kind() == Expr.LitKind.LIST && l.value() instanceof List<?> items)
            {
                items.stream().filter(Expr.class::isInstance)
                        .forEach(i -> walk((Expr) i, Polarity.UNKNOWN, sites));
            }
        }
        case Expr.Ref _ ->
        {
            // a bare reference outside a presence call is not a presence site
        }
        }
    }


    /**
     * The name operand of a presence call when it is a template by the EXPANDER's classification,
     * else {@code null}. The operand is wrapped in a one-call Check and handed to
     * {@link WildcardExpander#hasRealWildcards}, so the lint and the engine cannot disagree on what
     * a template is.
     */
    private static @Nullable String templateName(Expr operand)
    {
        String name = switch (operand)
        {
        case Expr.Lit l when l.kind() == Expr.LitKind.STRING -> (String) l.value();
        case Expr.Ref r -> r.name();
        default -> null;
        };
        if (name == null)
        {
            return null;
        }
        Expr probe = new Expr.Call("var_exists", List.of(new Expr.Lit(Expr.LitKind.STRING, name)),
                Map.of());
        return WildcardExpander.hasRealWildcards(new CheckConditionExpression(probe, name)) ? name
                : null;
    }


    private static String idOf(Rule rule)
    {
        return rule.getCore() != null && rule.getCore().getId() != null ? rule.getCore().getId()
                : String.valueOf(rule.getId());
    }
}
