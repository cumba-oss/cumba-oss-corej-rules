package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableRequirement;
import org.junit.jupiter.api.Test;

/**
 * Fix #124 — corpus lint for qualified {@code Requirements.Variables} entries.
 * <p>
 * The engine deliberately accepts <b>any</b> dataset as a qualifier: {@code CDISC-AD0643}
 * references {@code AE.AEDUR} with no {@code Match_Datasets} entry at all, so requiring the
 * qualifier to be declared would reject a legitimate resolver-only reference. A typo would then
 * pass load-time validation and silently skip the rule on every dataset, which is why the check
 * lives here as a corpus lint rather than as a {@code RulePackageLoader} error.
 * </p>
 * <p>
 * {@code RulePackageLoader} has no non-fatal diagnostic channel — its only output is a hard
 * {@code loadError} — so this module's test suite is the right home, alongside
 * {@code SilencingGuardHoistGateTest}.
 * </p>
 * <p>
 * Two things are linted:
 * </p>
 * <ol>
 * <li><b>Unknown qualifier</b> — the qualifier is a dataset name <b>no rule anywhere in the corpus
 * declares</b>. Reported so a typo fails CI.
 * <p>
 * ⭐ <b>Widened 2026-09-10 from per-rule to corpus-wide</b> (owner ruling, disposition (a) of
 * {@code plans/PLAN-qualified-requirements-cross-standard.md} §8.4). It used to require the
 * qualifier to be declared by the <em>same</em> rule's {@code Match_Datasets} or {@code Operations}
 * {@code domain:}, which made a legitimate <b>resolver-only</b> reference a finding — and its own
 * suggested remedy (<i>"add the dataset to the rule's Match_Datasets"</i>) was actively harmful:
 * declaring the join is what keeps a rule in the cross-standard collapse population (§8.3 of that
 * plan), so taking the advice would have converted six sound rules into the shape whose control arm
 * floods. {@code R-4.12} already sanctions the resolver-only form, citing {@code CDISC-AD0643}'s
 * {@code AE.AEDUR} with neither declaration.
 * </p>
 * <p>
 * The typo net is kept by the <em>corpus-wide</em> vocabulary instead: a misspelled dataset has to
 * coincide with a name some other rule declares, and {@code DM}/{@code AE} are declared by
 * hundreds. ⚠ That makes the vocabulary load-bearing, so
 * {@link #qualifiedScopeVariableEntriesAreWellFormed} asserts it is non-trivially large — a
 * vocabulary that silently collapsed to a handful would turn this lint into a pass-everything
 * no-op, which is the failure mode this corpus has already shipped seven times.
 * </p>
 * </li>
 * <li><b>{@code ${…}} operand template</b> — variable-requirement matching has no per-row
 * substitution context, so a template in a scope entry stays literal and can never match. Dotted
 * {@code ${…}} names are legitimate in a {@code Check} (33 rule files use them); in a scope entry
 * they are always a mistake.</li>
 * </ol>
 * <p>
 * ⚠ <b>The view is the shipped {@code rules/} corpus, and that narrows
 * {@link #declaredDatasets}.</b> The expression corpus inlines its operations, so no
 * {@code Operations} entry carries a {@code domain:} — the declared set is effectively
 * {@code Match_Datasets} alone, which makes this lint <em>stricter</em> than it was against an
 * operator-leaf view. Measured 2026-08-11 (under the then-current {@code Scope.Variables}
 * spelling): 6 qualified entries and <b>0</b> unknown qualifiers, identical either way, so nothing
 * changed then. A future rule that qualifies a scope entry with a dataset named only by an
 * operation's {@code domain:} would now be reported here; that is a real finding under the
 * resolver, not a false positive to be silenced by widening the set back.
 * </p>
 */
class QualifiedScopeVariableLintTest
{

    /**
     * ⭐ <b>The resolver-only ratchet</b> — every qualified entry whose dataset the rule does not
     * itself declare, as {@code <rule id>|<entry>}. Added 2026-09-10 by review finding 6.
     *
     * <p>
     * ⚠⚠ <b>It exists because widening the unknown-qualifier check from per-rule to corpus-wide
     * gave up the likeliest typo in this family.</b> The corpus-wide check catches a qualifier
     * naming <em>no</em> dataset; it cannot catch one naming a <b>real but wrong</b> dataset — and
     * {@code AE.AGE} mistyped on {@code CDISC-AD0204} (whose join is {@code DM}) is exactly the
     * copy-paste the 2026-09-10 hoist made easy, since it touched the {@code DM.*} and {@code AE.*}
     * families in one change. Such an entry is in the vocabulary, so it lands here instead — and an
     * exact-equality set makes the build red until someone states that the new reference is
     * deliberate.
     * </p>
     *
     * <p>
     * Pairs, not package-qualified keys: the same six rules ship in four {@code adamig} packages,
     * so a package-keyed set would move 24 → 30 the day {@code adamig 1-4} is added, for no reason
     * related to what this ratchet is about. ⛔ Adding a line here is a decision, not a chore: it
     * asserts the qualifier is the dataset the author meant, which nothing else in the repo checks.
     * </p>
     */
    private static final Set<String> EXPECTED_RESOLVER_ONLY = Set.of("CDISC-AD0640|AE.AETRTEM",
            "CDISC-AD0641|AE.AESTDY", "CDISC-AD0642|AE.AEENDY", "CDISC-AD0643|AE.AEDUR",
            "CDISC-AD0644|AE.AESEV", "CDISC-AD0645|AE.AETOXGR");

    @Test
    void qualifiedScopeVariableEntriesAreWellFormed() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<String> unknownQualifier = new ArrayList<>();
        List<String> operandTemplate = new ArrayList<>();
        List<String> resolverOnly = new ArrayList<>();
        Set<String> resolverOnlyPairs = new java.util.TreeSet<>();
        int rulesScanned = 0;

        List<Path> packages;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList();
        }

        // Pass 1 — the corpus-wide dataset vocabulary every qualifier is checked against. Built
        // from the same declaredDatasets() the per-rule check used, so the two cannot drift.
        Set<String> vocabulary = new LinkedHashSet<>();
        for (Path file : packages)
        {
            for (Rule rule : RulePackageLoader.load(file).getRules().values())
            {
                if (rule != null)
                {
                    vocabulary.addAll(declaredDatasets(rule));
                }
            }
        }

        // Pass 2 — the lint proper.
        for (Path file : packages)
        {
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null)
                {
                    continue;
                }
                rulesScanned++;
                lintRule(rule, file.getFileName().toString(), vocabulary, unknownQualifier,
                        operandTemplate, resolverOnly, resolverOnlyPairs);
            }
        }
        System.out.println("### dataset vocabulary: " + vocabulary.size() + " name(s)");
        System.out.println(
                "### resolver-only qualified entries (legitimate, R-4.12): " + resolverOnly.size());
        resolverOnly.forEach(e -> System.out.println("###   " + e));

        assertTrue(rulesScanned > 3000, "corpus scan looks broken: only " + rulesScanned
                + " rules loaded — expected the full assembled corpus");

        // ⛔ Non-vacuity of the instrument itself. The vocabulary is what makes a corpus-wide
        // qualifier check a check at all: if it collapsed — a model rename, a getter returning
        // null, a package glob that stopped matching — every qualifier would pass and this lint
        // would report green while checking nothing. MEASURED 2026-09-10: 39 names.
        assertTrue(vocabulary.size() >= 25,
                "the dataset vocabulary collapsed to " + vocabulary.size()
                        + " name(s), so the unknown-qualifier assertion below can no longer fail — "
                        + "fix the derivation, never this floor");

        assertTrue(operandTemplate.isEmpty(),
                "Requirements.Variables entries must not carry a ${…} operand template — requirement matching "
                        + "has no substitution context, so the entry stays literal and never "
                        + "matches (" + operandTemplate.size() + "):\n"
                        + String.join("\n", operandTemplate));

        assertEquals(EXPECTED_RESOLVER_ONLY, resolverOnlyPairs,
                "the set of qualified entries whose dataset the rule does not declare has changed. A"
                        + " resolver-only reference is LEGAL (R-4.12, CDISC-AD0643) — but this is"
                        + " also where a qualifier naming a real-but-wrong dataset lands, and the"
                        + " corpus-wide check above cannot see that one. Confirm the qualifier names"
                        + " the dataset the author meant, then add it to EXPECTED_RESOLVER_ONLY.");

        assertTrue(unknownQualifier.isEmpty(),
                "qualified Requirements.Variables entry whose dataset NO rule in the corpus declares "
                        + "— almost certainly a typo in the qualifier. ⛔ Do not fix it by adding the "
                        + "dataset to this rule's Match_Datasets: declaring a join changes which "
                        + "population the rule belongs to (PLAN-qualified-requirements-cross-standard "
                        + "§8.3). A resolver-only reference is legitimate per R-4.12 (CDISC-AD0643) "
                        + "and is NOT reported here — this list is spelling only ("
                        + unknownQualifier.size() + "):\n" + String.join("\n", unknownQualifier));
    }


    private static void lintRule(Rule rule, String file, Set<String> vocabulary,
            List<String> unknownQualifier, List<String> operandTemplate, List<String> resolverOnly,
            Set<String> resolverOnlyPairs)
    {
        VariableRequirement variables = rule.effectiveVariableRequirement();
        if (variables == null)
        {
            return;
        }
        Set<String> declared = declaredDatasets(rule);
        String id = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
        for (List<String> list : listsOf(variables))
        {
            for (String raw : list)
            {
                if (raw == null)
                {
                    continue;
                }
                if (raw.contains("${"))
                {
                    operandTemplate.add(file + "|" + id + "|" + raw);
                    continue;
                }
                ScopeVariableEntry entry = ScopeVariableEntry.parse(raw);
                String qualifier = entry.qualifier();
                if (qualifier == null)
                {
                    continue;
                }
                String name = qualifier.toUpperCase(Locale.ROOT);
                if (!vocabulary.contains(name))
                {
                    unknownQualifier.add(file + "|" + id + "|" + raw);
                }
                else if (!declared.contains(name))
                {
                    // A resolver-only reference (R-4.12) — legitimate per se, but it is also where
                    // a qualifier naming a real-but-wrong dataset lands, so the SET is ratcheted
                    // below rather than merely printed.
                    resolverOnly.add(file + "|" + id + "|" + raw);
                    resolverOnlyPairs.add(id + "|" + raw);
                }
            }
        }
    }


    /**
     * The {@code All}, {@code Any}, {@code None} and {@code All_Or_None} lists, skipping the
     * {@code null}s.
     *
     * <p>
     * ⚠ All FOUR facets: a qualified entry is exactly as unresolvable in {@code Any} or in an
     * {@code All_Or_None} group as in {@code All}, and a facet left out here is a facet this lint
     * cannot see. ({@code All_Or_None} joined 2026-09-25, PLAN-join-key-pairing — its qualified
     * side is the whole point of the facet, so a typo there is the likeliest of all.)
     * </p>
     */
    private static List<List<String>> listsOf(VariableRequirement variables)
    {
        List<List<String>> lists = new ArrayList<>(4);
        if (variables.getAll() != null)
        {
            lists.add(variables.getAll());
        }
        if (!variables.anyUnion().isEmpty())
        {
            lists.add(variables.anyUnion());
        }
        if (variables.getNone() != null)
        {
            lists.add(variables.getNone());
        }
        if (!variables.allOrNoneUnion().isEmpty())
        {
            lists.add(variables.allOrNoneUnion());
        }
        return lists;
    }


    /**
     * Upper-cased dataset names the rule itself declares — every {@code Match_Datasets.Name} plus
     * every {@code Operations} {@code domain:}.
     */
    private static Set<String> declaredDatasets(Rule rule)
    {
        Set<String> names = new LinkedHashSet<>();
        if (rule.getMatchDatasets() != null)
        {
            for (MatchDataset md : rule.getMatchDatasets())
            {
                if (md != null && md.getName() != null)
                {
                    names.add(md.getName().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (rule.getOperations() != null)
        {
            for (Operation op : rule.getOperations())
            {
                if (op != null && op.getDomain() != null && !op.getDomain().isEmpty())
                {
                    names.add(op.getDomain().toUpperCase(Locale.ROOT));
                }
                if (op != null && op.getMinuendDomain() != null && !op.getMinuendDomain().isEmpty())
                {
                    names.add(op.getMinuendDomain().toUpperCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

}
