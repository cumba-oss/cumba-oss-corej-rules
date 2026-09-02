package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.cdisc.core.CorpusPaths;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase 4b coverage gate — the running metric for the nativization plan. Over all shipped corpus
 * rule packages, every Record-Data rule whose {@code Expr} the native evaluator declines must be
 * declined for an <b>operand</b> reason (a non-plain-column operand — wildcard
 * {@code --}/{@code *}, a {@code $}-operation reference, a dotted cross-dataset reference, or a
 * built-in), which is the documented Phase-N residual (decision Q3). A decline for an
 * <b>operator</b> reason — every operand is a plain column yet {@code ExprCompiler} still rejects
 * the construct — means an operator has not been nativized and is a <b>failure</b>: the native
 * evaluator must compute every operator on plain-column operands.
 *
 * <p>
 * Value-level parity (native == legacy on a synthetic dataset) for the compiled rules is proven by
 * {@code NativeCorpusParityTest}; this test is purely the compile-coverage census.
 * </p>
 */
class NativeCoverageTest
{

    @Test
    void everyPlainColumnOperatorIsNativelySupported() throws Exception
    {
        Path rulesDir = CorpusPaths.rules();
        List<String> operatorReasonDeclines = new ArrayList<>();
        int compiled = 0;
        int operandReasonDeclines = 0;
        try (Stream<Path> files = Files.list(rulesDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.toString().endsWith(".json")).sorted().toList())
            {
                RulePackage pkg = RulePackageLoader.load(file);
                for (Rule rule : pkg.getRules().values())
                {
                    Expr expr = rule.getCheckExpr();
                    // row-data rules: no VAR cursor (the former Record Data population)
                    if (expr == null || rule.getEvaluationDomain() == null
                            || rule.getEvaluationDomain().varCursor())
                    {
                        continue;
                    }
                    if (NativeExprEvaluator.isSupported(expr))
                    {
                        compiled++;
                    }
                    else if (hasNonPlainColumnOperand(expr))
                    {
                        operandReasonDeclines++;
                    }
                    else
                    {
                        String id = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                        operatorReasonDeclines.add(file.getFileName() + " / " + id + " : " + expr);
                    }
                }
            }
        }
        assertTrue(compiled > 100,
                "expected a substantial native-compiled rule count, got " + compiled);
        assertTrue(operatorReasonDeclines.isEmpty(),
                "native declines for an OPERATOR reason (plain-column operands but unsupported "
                        + "construct) — these operators must be nativized ("
                        + operatorReasonDeclines.size() + "):\n"
                        + String.join("\n", operatorReasonDeclines.stream().limit(40).toList())
                        + "\n[compiled=" + compiled
                        + ", operand-reason declines (Phase-N residual)=" + operandReasonDeclines
                        + "]");
    }

    // ------------------------------------------------------------------


    /**
     * Whether the expression references any operand that is not a plain {@link OperandKind#COLUMN}
     * — a wildcard column, a {@code $}-operation reference, a dotted cross-dataset reference, or a
     * built-in. Such an operand is the deferred Phase-N operand-resolution residual, so a native
     * decline on it is acceptable. Traverses positional and keyword operands.
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
                yield items.stream().anyMatch(NativeCoverageTest::hasNonPlainColumnOperand);
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
                .anyMatch(NativeCoverageTest::hasNonPlainColumnOperand);
        case Expr.Or o -> o.parts().stream().anyMatch(NativeCoverageTest::hasNonPlainColumnOperand);
        case Expr.Not n -> hasNonPlainColumnOperand(n.inner());
        };
    }

}
