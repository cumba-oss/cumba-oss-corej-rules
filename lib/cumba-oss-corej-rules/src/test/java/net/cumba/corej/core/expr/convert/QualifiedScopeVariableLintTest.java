package net.cumba.corej.core.expr.convert;

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
 * <li><b>Unknown qualifier</b> — the qualifier names neither a {@code Match_Datasets.Name} nor an
 * {@code Operations} {@code domain:} of the same rule. Reported so a typo fails CI.</li>
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

    @Test
    void qualifiedScopeVariableEntriesAreWellFormed() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<String> unknownQualifier = new ArrayList<>();
        List<String> operandTemplate = new ArrayList<>();
        int rulesScanned = 0;

        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    if (rule == null)
                    {
                        continue;
                    }
                    rulesScanned++;
                    lintRule(rule, file.getFileName().toString(), unknownQualifier,
                            operandTemplate);
                }
            }
        }

        assertTrue(rulesScanned > 3000, "corpus scan looks broken: only " + rulesScanned
                + " rules loaded — expected the full assembled corpus");

        assertTrue(operandTemplate.isEmpty(),
                "Requirements.Variables entries must not carry a ${…} operand template — requirement matching "
                        + "has no substitution context, so the entry stays literal and never "
                        + "matches (" + operandTemplate.size() + "):\n"
                        + String.join("\n", operandTemplate));

        assertTrue(unknownQualifier.isEmpty(),
                "qualified Requirements.Variables entry whose dataset is named by neither Match_Datasets "
                        + "nor an Operations domain — check for a typo. A resolver-only reference "
                        + "is legitimate (see CDISC-AD0643); if this entry is one, add the "
                        + "dataset to the rule's Match_Datasets or adjust this lint ("
                        + unknownQualifier.size() + "):\n" + String.join("\n", unknownQualifier));
    }


    private static void lintRule(Rule rule, String file, List<String> unknownQualifier,
            List<String> operandTemplate)
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
                if (qualifier != null && !declared.contains(qualifier.toUpperCase(Locale.ROOT)))
                {
                    unknownQualifier.add(file + "|" + id + "|" + raw);
                }
            }
        }
    }


    /**
     * The {@code All}, {@code Any} and {@code None} lists, skipping the {@code null}s.
     *
     * <p>
     * ⚠ All THREE facets: a qualified entry is exactly as unresolvable in {@code Any} as in
     * {@code All}, and a facet left out here is a facet this lint cannot see.
     * </p>
     */
    private static List<List<String>> listsOf(VariableRequirement variables)
    {
        List<List<String>> lists = new ArrayList<>(3);
        if (variables.getAll() != null)
        {
            lists.add(variables.getAll());
        }
        if (variables.getAny() != null)
        {
            lists.add(variables.getAny());
        }
        if (variables.getNone() != null)
        {
            lists.add(variables.getNone());
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
