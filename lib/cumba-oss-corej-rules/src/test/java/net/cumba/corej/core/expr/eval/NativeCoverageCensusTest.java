package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Epic D census + regression gate. Loads every shipped {@code rules/rules-*.json} corpus package
 * through {@link RulePackageLoader#load(Path)} (so each rule's native {@code checkExpr} is
 * installed exactly as the runtime sees it) and reports, per inferred evaluation domain
 * ({@link Domain}), how many rules are native-executable (non-null {@link Rule#getCheckExpr()})
 * versus not.
 *
 * <p>
 * The test makes the residual legacy-dependent set <b>explicit, measured, and regression-gated</b>:
 * </p>
 * <ul>
 * <li>It prints a readable per-domain + total census to build output, so the "shrink to zero"
 * progress of the nativization program is visible.</li>
 * <li>It asserts a <b>floor</b> on native coverage (total and the {@code {ROW}} domain), baked in
 * from the current measured count. These floors may only ever <em>rise</em>; a regression that
 * drops native coverage below them fails the build.</li>
 * <li>For every rule <em>without</em> a {@code checkExpr} it classifies the reason into a KNOWN
 * residual category (mirroring {@code RulePackageLoader.retainNativeExpr}'s decision tree). A rule
 * that is non-native for an <em>unrecognised</em> reason fails the test — that failure is the
 * signal that a new, silent native gap has appeared.</li>
 * </ul>
 *
 * <p>
 * Verdict correctness for the compiled rules is proven separately by the rulespec suite and the
 * ruletest scenario suites; the operator-vs-operand residual split is the subject of
 * {@link NativeCoverageTest}. This test is the census + floor guard. Since the legacy-engine
 * retirement a rule without a {@code checkExpr} yields a per-rule ERROR at runtime, so the only
 * legitimate non-native residual is the wildcard-template population, whose per-dataset expansions
 * install the native {@code checkExpr} at the generation seam ({@code WildcardExpander}).
 * </p>
 */
class NativeCoverageCensusTest
{

    /**
     * Floor on the total number of native-executable rules (non-null {@code checkExpr}) across the
     * whole shipped corpus. Measured on the current tree; this is a floor that may only rise. A
     * build that drops below it has regressed native coverage.
     */
    // Sized on the CORE-free corpus (the smallest shipped distribution): measured 11672.
    private static final int TOTAL_NATIVE_FLOOR = 11500;

    /**
     * Floor on the number of native-executable rules whose inferred domain is {@link Domain#ROW} —
     * the per-record population. Measured on the current tree; floor only, may only rise.
     */
    // Sized on the CORE-free corpus, as above.
    private static final int ROW_DOMAIN_NATIVE_FLOOR = 7800;

    /** Residual classification buckets for a rule that has no native {@code checkExpr}. */
    private enum Residual
    {
        /** Check has no faithful whole-expression surface (mixed / old-style Check). */
        NOT_EXPRESSIBLE,
        /**
         * Expression compiles but references a non-plain-column operand the native operand layer
         * declines at the whole-rule level. Today this bucket is exactly the wildcard-template
         * rules ({@code *}/{@code xx}/{@code zz} capture columns): the template itself carries no
         * {@code checkExpr}, while each per-dataset expansion produced by {@code WildcardExpander}
         * installs and runs one.
         */
        OPERAND_RESIDUAL,
        /**
         * Eligible rule whose expression is native-supported but which
         * {@code RulePackageLoader.retainsExpr} declines to retain (no bit-faithful native route
         * for its shape). Since the legacy-engine retirement such a rule ERRORs at runtime, so this
         * bucket should stay empty for shipped rules.
         */
        ELIGIBLE_NOT_RETAINED,
        /**
         * Expression compiles, every operand is a plain column, yet the native backend still
         * declines it for an <em>unrecognised</em> reason — an operator has not been nativized and
         * no known residual category explains it. This is the FAILURE bucket: it must always be
         * empty. A non-empty bucket is the signal a new, silent native gap appeared.
         */
        OPERATOR_RESIDUAL
    }

    @Test
    void nativeCoverageCensusAndResidualClassification() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();

        Map<String, int[]> perDomain = new TreeMap<>(); // [native, nonNative]
        Map<Residual, Integer> residualCounts = new EnumMap<>(Residual.class);
        List<String> operatorResiduals = new ArrayList<>();
        int totalNative = 0;
        int totalNonNative = 0;
        int rowDomainNative = 0;

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
                    // The loader infers the domain only when it retains a native checkExpr; a
                    // non-native rule has none and is counted under "(no domain)".
                    Domain domain = rule.getEvaluationDomain();
                    int[] counts = perDomain.computeIfAbsent(
                            domain == null ? "(no domain)" : domain.label(), _ -> new int[2]);
                    if (rule.getCheckExpr() != null)
                    {
                        counts[0]++;
                        totalNative++;
                        if (Domain.ROW.equals(domain))
                        {
                            rowDomainNative++;
                        }
                        continue;
                    }
                    counts[1]++;
                    totalNonNative++;
                    Residual residual = classify(rule);
                    residualCounts.merge(residual, 1, Integer::sum);
                    if (residual == Residual.OPERATOR_RESIDUAL)
                    {
                        String id = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                        operatorResiduals
                                .add(file.getFileName() + " / " + id + " : " + describeExpr(rule));
                    }
                }
            }
        }

        printCensus(perDomain, residualCounts, totalNative, totalNonNative, rowDomainNative);

        // (1) Native-coverage floors — may only rise.
        assertTrue(totalNative >= TOTAL_NATIVE_FLOOR,
                "native coverage REGRESSED: total native checkExpr=" + totalNative
                        + " dropped below the floor " + TOTAL_NATIVE_FLOOR
                        + " (floors may only rise — investigate the lost native rules)");
        assertTrue(rowDomainNative >= ROW_DOMAIN_NATIVE_FLOOR,
                "native coverage REGRESSED: {ROW}-domain native checkExpr=" + rowDomainNative
                        + " dropped below the floor " + ROW_DOMAIN_NATIVE_FLOOR
                        + " (floors may only rise)");

        // (2) No silent, unexplained native gap: every non-native rule must land in a KNOWN
        // non-operator residual category. An OPERATOR_RESIDUAL (plain-column operands, unsupported
        // construct) is a new gap and a hard failure.
        assertTrue(operatorResiduals.isEmpty(),
                "UNEXPLAINED native gap — rule(s) with all-plain-column operands that the native "
                        + "backend still declines (an operator must be nativized) ("
                        + operatorResiduals.size() + "):\n"
                        + String.join("\n", operatorResiduals.stream().limit(40).toList()));
    }

    // ------------------------------------------------------------------


    /**
     * Classifies a non-native rule into a known {@link Residual} bucket, mirroring the decision
     * tree in {@code RulePackageLoader.retainNativeExpr}. Returns
     * {@link Residual#OPERATOR_RESIDUAL} only when the expression compiles, every operand is a
     * plain column, and the native backend still declines — i.e. a genuinely unexplained gap.
     */
    private static Residual classify(Rule rule)
    {
        CheckCondition check = rule.getCheck();
        if (check == null)
        {
            // No Check at all — not a native-evaluable rule. Treat as not-expressible residual.
            return Residual.NOT_EXPRESSIBLE;
        }
        Expr expr;
        try
        {
            expr = CheckToExpr.toExpr(check);
        }
        catch (ExpressionException _)
        {
            // Mixed / old-style Check with no faithful expression surface.
            return Residual.NOT_EXPRESSIBLE;
        }

        // The native backend declined this expression. Sub-cases:
        if (!NativeExprEvaluator.isSupported(expr))
        {
            if (hasNonPlainColumnOperand(expr))
            {
                return Residual.OPERAND_RESIDUAL;
            }
            // Plain-column operands but still unsupported => an operator gap.
            return Residual.OPERATOR_RESIDUAL;
        }

        // Expression IS native-supported but the rule still has no checkExpr: the loader declined
        // to retain it (no bit-faithful native route for its shape — such a rule ERRORs at
        // runtime). Since PLAN-leaf-scope-domain-inference there is no type gate: every rule is
        // offered to the backend.
        return Residual.ELIGIBLE_NOT_RETAINED;
    }


    /**
     * Re-raises the rule's Check to its {@link Expr} for diagnostic printing (the rule has no
     * {@code checkExpr} in the residual buckets), returning {@code "<not expressible>"} when it has
     * no faithful expression surface.
     */
    private static String describeExpr(Rule rule)
    {
        CheckCondition check = rule.getCheck();
        if (check == null)
        {
            return "<no check>";
        }
        try
        {
            return String.valueOf(CheckToExpr.toExpr(check));
        }
        catch (ExpressionException _)
        {
            return "<not expressible>";
        }
    }


    private static void printCensus(Map<String, int[]> perDomain,
            Map<Residual, Integer> residualCounts, int totalNative, int totalNonNative,
            int rowDomainNative)
    {
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n=== Native coverage census (Epic D) ===\n");
        sb.append(String.format("%-58s %8s %10s%n", "Domain", "native", "non-native"));
        for (Map.Entry<String, int[]> e : perDomain.entrySet())
        {
            sb.append(String.format("%-58s %8d %10d%n", e.getKey(), e.getValue()[0],
                    e.getValue()[1]));
        }
        sb.append(String.format("%-58s %8d %10d%n", "TOTAL", totalNative, totalNonNative));
        sb.append("{ROW} native = ").append(rowDomainNative).append('\n');
        sb.append("floors: total>=").append(TOTAL_NATIVE_FLOOR).append(", {ROW}>=")
                .append(ROW_DOMAIN_NATIVE_FLOOR).append('\n');
        sb.append("--- residual classification (non-native rules) ---\n");
        for (Residual r : Residual.values())
        {
            sb.append(String.format("%-22s %6d%n", r.name(), residualCounts.getOrDefault(r, 0)));
        }
        sb.append("=======================================\n");
        System.out.print(sb);
    }


    /**
     * Whether the expression references any operand that is not a plain {@link OperandKind#COLUMN}
     * — a wildcard column, a {@code $}-operation reference, a dotted cross-dataset reference, or a
     * built-in. Such an operand is the deferred operand-resolution residual, so a native decline on
     * it is acceptable. Traverses positional and keyword operands. (Kept in sync with the helper of
     * the same name in {@link NativeCoverageTest}.)
     */
    private static boolean hasNonPlainColumnOperand(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> r.kind() != OperandKind.COLUMN;
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield items.stream().anyMatch(NativeCoverageCensusTest::hasNonPlainColumnOperand);
            }
            yield false;
        }
        case Expr.Call c ->
        {
            for (Expr a : c.args())
            {
                if (hasNonPlainColumnOperand(a))
                {
                    yield true;
                }
            }
            for (Expr a : c.kwargs().values())
            {
                if (hasNonPlainColumnOperand(a))
                {
                    yield true;
                }
            }
            yield false;
        }
        case Expr.Binary b -> hasNonPlainColumnOperand(b.left())
                || hasNonPlainColumnOperand(b.right());
        case Expr.And a -> a.parts().stream()
                .anyMatch(NativeCoverageCensusTest::hasNonPlainColumnOperand);
        case Expr.Or o -> o.parts().stream()
                .anyMatch(NativeCoverageCensusTest::hasNonPlainColumnOperand);
        case Expr.Not n -> hasNonPlainColumnOperand(n.inner());
        };
    }

}
