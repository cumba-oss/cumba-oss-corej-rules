package net.cumba.corej.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.BindingScope;
import net.cumba.corej.core.exec.OutputEntryKind;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.VariableUniverse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The hand-over list and the sensitivity of {@link FindingValuesNullGuard}
 * ({@code PLAN-output-variable-null-key-provenance} §2.1 / §6.2 H2 / T2).
 *
 * <h2>The hand-over list is a derivation, asserted exactly</h2>
 * <p>
 * {@code nullkey/present-column-omitted.tsv} lists every {@code (rule, entry)} of the deferred
 * present-column / no-cell state: a rule whose Check takes a <b>column-level</b> per-variable path
 * ({@link BindingScope#PER_VARIABLE} on any check level, or the Define variable universe) and an
 * effective {@code Output_Variables} entry of kind {@link OutputEntryKind#COLUMN} (a raw column
 * name or {@code variable_value}) — the entries the engine <b>omits</b> when the column is present
 * and the finding has no row (T2: never a {@code null} key). The list allows nothing: it is the
 * population {@code PLAN-finding-values-record-level-only} (V8) gives a name-only form. The
 * committed file must equal the derivation over the generated corpus in both directions: a row the
 * corpus no longer produces is a dead entry, a candidate the file lacks is an omission that plan
 * would not see. {@code -D}{@value #REGENERATE}{@code =true} rewrites the file and aborts (a
 * regeneration is never a green).
 * </p>
 *
 * <h2>Sensitivity</h2>
 * <p>
 * The guard's judgement is proven on every run: a synthetic violation with null values is reported
 * key by key — a hand-over entry included, since the list allows nothing; a {@code ""} and a
 * {@code .} are values and never reported; the reach counter counts every call.
 * </p>
 */
class FindingValuesNullGuardTest
{

    /** {@code -Dcorej.nullkey.regenerate=true} rewrites the roster instead of comparing. */
    static final String REGENERATE = "corej.nullkey.regenerate";

    /** Floor on the rules read, so a derivation over nothing cannot pass (policy, not a count). */
    private static final int MIN_SHIPPED_RULES = 3300;

    @Test
    void theRosterIsExactlyTheStaticDerivation() throws IOException
    {
        Map<String, String> derived = derive();
        Path file = FindingValuesNullGuard.rosterPath();
        if (Boolean.getBoolean(REGENERATE))
        {
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(derived), UTF_8);
            Assumptions.abort("regenerated " + derived.size() + " rows into " + file
                    + " — review the diff and commit it; a regeneration is never a green");
        }
        Set<String> committed = FindingValuesNullGuard.read(file);
        Set<String> dead = new TreeSet<>(committed);
        dead.removeAll(derived.keySet());
        Set<String> missing = new TreeSet<>(derived.keySet());
        missing.removeAll(committed);
        assertEquals(Set.of(), dead, "hand-over rows the corpus no longer derives (dead entries)");
        assertEquals(Set.of(), missing, "present-column / no-cell candidates the hand-over list"
                + " lacks — regenerate with -D" + REGENERATE + "=true and review the diff");
        for (String row : committed)
        {
            String entry = row.substring(row.indexOf('\t') + 1);
            assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of(entry),
                    "a roster row names a column entry: " + row);
        }
    }


    @Test
    void theGuardReportsEveryNullAndAcceptsValues()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("variable_name", "AETERM");
        values.put("variable_label", "");
        values.put("variable_length", ".");
        values.put("DOMAIN", null);
        values.put("variable_value", null);
        List<Violation> violations = List
                .of(new Violation(0, values, null, null, Map.of(), null, null));

        List<String> offenders = FindingValuesNullGuard.offenders("CDISC-CG0349", violations);

        // DOMAIN of CDISC-CG0349 is on the hand-over list — and still an offender: the list
        // allows nothing, the engine omits those entries.
        assertTrue(FindingValuesNullGuard.roster().contains("CDISC-CG0349\tDOMAIN"),
                "precondition: the list names CG0349/DOMAIN — " + FindingValuesNullGuard.roster());
        assertEquals(2, offenders.size(), offenders.toString());
        assertTrue(offenders.get(0).startsWith("CDISC-CG0349/DOMAIN "), offenders.toString());
        assertTrue(offenders.get(1).startsWith("CDISC-CG0349/variable_value "),
                offenders.toString());
        assertTrue(FindingValuesNullGuard.offenders("R-1", null).isEmpty());
        assertTrue(FindingValuesNullGuard.offenders("R-1", List.of()).isEmpty());

        long before = FindingValuesNullGuard.reach("sensitivity");
        AssertionError red = assertThrows(AssertionError.class, () -> FindingValuesNullGuard
                .requireNullFree("sensitivity", "plant", rule("CDISC-CG0349"), violations));
        assertTrue(red.getMessage().contains("CDISC-CG0349/DOMAIN"), red.getMessage());
        Map<String, String> clean = new LinkedHashMap<>(values);
        clean.remove("DOMAIN");
        clean.remove("variable_value");
        FindingValuesNullGuard.requireNullFree("sensitivity", "clean", rule("CDISC-CG0349"),
                List.of(new Violation(0, clean, null, null, Map.of(), null, null)));
        assertEquals(before + 2, FindingValuesNullGuard.reach("sensitivity"),
                "every call is counted, a red one too");
        assertThrows(AssertionError.class, () -> FindingValuesNullGuard.requireReach("sensitivity",
                before, 3, "the sensitivity control"));
        FindingValuesNullGuard.requireReach("sensitivity", before, 2, "the sensitivity control");
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }


    @Test
    void theRosterFileIsRead()
    {
        Set<String> committed = FindingValuesNullGuard.roster();
        assertFalse(committed.isEmpty(), "the committed roster is empty");
        assertTrue(committed.stream().allMatch(r -> r.indexOf('\t') > 0), committed.toString());
    }

    // ------------------------------------------------------------------ derivation


    /** {@code coreId<TAB>entry → path} over every shipped rule of the generated corpus. */
    static Map<String, String> derive() throws IOException
    {
        Map<String, String> out = new TreeMap<>();
        Set<String> shipped = new TreeSet<>();
        for (Path pkg : OvSweepCensus.packages(CorpusPaths.rules()))
        {
            RulePackage loaded = RulePackageLoader.load(pkg);
            for (Rule rule : loaded.getRules().values())
            {
                String coreId = FindingValuesNullGuard.coreId(rule);
                shipped.add(coreId);
                if (rule.getLoadError() != null)
                {
                    continue;
                }
                String path = columnLevelPath(rule);
                if (path == null)
                {
                    continue;
                }
                for (String entry : rule.effectiveOutputVariablesOrAuthored())
                {
                    if (OutputEntryKind.of(entry) == OutputEntryKind.COLUMN)
                    {
                        out.putIfAbsent(coreId + "\t" + entry, path);
                    }
                }
            }
        }
        assertTrue(shipped.size() >= MIN_SHIPPED_RULES,
                "only " + shipped.size() + " shipped rules read — a derivation over nothing");
        return out;
    }


    /**
     * The column-level per-variable path {@code rule} takes, or {@code null} for every other rule:
     * {@code define-universe} ({@code RuleRunner.buildDefineVariableViolation}) or
     * {@code per-variable} ({@code buildVariableViolation}, any check level routed to
     * {@link BindingScope#PER_VARIABLE} — decided on the cached evaluation domain exactly as
     * {@link OvSweepClassifier#runtimePerVariable}). The per-(variable, row) path
     * ({@link BindingScope#PER_VARIABLE_ROW}) reads a raw column's cell at the row and is not a
     * candidate.
     */
    static @org.jspecify.annotations.Nullable String columnLevelPath(Rule rule)
    {
        List<Expr> exprs = new ArrayList<>();
        if (rule.getCheckLevelExprs() != null && !rule.getCheckLevelExprs().isEmpty())
        {
            exprs.addAll(rule.getCheckLevelExprs().values());
        }
        else if (rule.getCheckExpr() != null)
        {
            exprs.add(rule.getCheckExpr());
        }
        if (exprs.isEmpty())
        {
            return null;
        }
        Domain domain = rule.getEvaluationDomain();
        if (domain == null)
        {
            throw new IllegalStateException(FindingValuesNullGuard.coreId(rule)
                    + ": compiled Check without a cached evaluation domain");
        }
        boolean perVariable = false;
        for (Expr expr : exprs)
        {
            perVariable |= BindingScope.of(domain, expr) == BindingScope.PER_VARIABLE;
        }
        if (!perVariable)
        {
            return null;
        }
        return rule.getVariableUniverse() == VariableUniverse.DEFINE ? "define-universe"
                : "per-variable";
    }


    private static String render(Map<String, String> derived)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "# present-column-omitted.tsv — PLAN-output-variable-null-key-provenance §6.2 H2 / T2:")
                .append(" the present-column / no-cell entries the engine OMITS from a finding (a raw")
                .append(" column name or variable_value on a column-level per-variable finding, where")
                .append(" the column is present and the finding has no row) — never a null key. Allows")
                .append(" nothing: the hand-over list for PLAN-finding-values-record-level-only (V8")
                .append(" gives them a name-only form). coreId<TAB>entry<TAB>path. Derived by")
                .append(" FindingValuesNullGuardTest from the generated corpus and asserted exactly")
                .append(" both ways.\n");
        for (Map.Entry<String, String> e : derived.entrySet())
        {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
