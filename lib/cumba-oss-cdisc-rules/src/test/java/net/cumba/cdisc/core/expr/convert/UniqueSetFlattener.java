package net.cumba.cdisc.core.expr.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.cdisc.core.expr.ast.Expr;

/**
 * ⚠ <b>One-shot corpus migration, already run</b> — Plan A Phase 2
 * ({@code plans/PLAN-authoring-grammar-unique-set-and-output-exclusion.md} §3.9(f), 2026-08-23).
 * Rewrites every {@code is_unique_set} / {@code is_not_unique_set} call site from the retired
 * privileged-first-operand spellings to the single-list form the engine accepts since
 * {@code Fix #352} / {@code #353}:
 *
 * <pre>
 * f(A, keys=[B, C])   →  f([A, B, C])
 * f(A, B)             →  f([A, B])
 * f(A)                →  f([A])
 * </pre>
 *
 * <b>Order preserved, no dedup, every other kwarg ({@code regex=}, …) kept</b> — a migration that
 * also "tidies" is one whose findings-diff can no longer be reasoned about; {@code CDISC-AD0688}'s
 * repeated member therefore comes out as {@code [USUBJID, USUBJID, SPDEVID]} and is filed, not
 * fixed. A site already in the single-list form is left untouched (idempotent), so a re-run over a
 * migrated corpus is a no-op.
 *
 * <p>
 * It is <b>not</b> on the production writer path: {@link RuleSrcExpressionWriter} takes it as an
 * explicit transform
 * ({@link RuleSrcExpressionWriter#run(Path, Path, java.util.function.UnaryOperator)}), so the
 * canonical layout (D27, {@link RuleCanonicalizer#renderExpressionCheckBlock}) and the per-file
 * self-verification are the writer's, not re-implemented here. Kept as a documented utility rather
 * than deleted because the transform is unit-tested on the five §2.3 shapes
 * ({@code UniqueSetFlattenerTest}) and because the retired spelling is now a load error — a rule
 * authored in the old form anywhere else is migrated with this, not by hand.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-rules
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.cdisc.core.expr.convert.UniqueSetFlattener \
 *     -Dflip.outRoot=/abs/path/to/output/checks
 * }</pre>
 */
public final class UniqueSetFlattener
{

    /** The pair whose first operand used to be privileged by the grammar. */
    static final Set<String> UNIQUE_SET_OPERATORS = Set.of("is_unique_set", "is_not_unique_set");

    private UniqueSetFlattener()
    {
    }


    /**
     * Runs {@link RuleSrcExpressionWriter} over the corpus with {@link #flattenUniqueSet}
     * installed. Same configuration contract as the writer: {@code flip.checksRoot} (default
     * {@code rules-src/checks}) and {@code flip.outRoot} (required; the corpus directory itself for
     * the in-place migration).
     *
     * @param args
     *            ignored
     * @throws IOException
     *             if a check file cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        Path checksRoot = Path.of(
                System.getProperty("flip.checksRoot", Path.of("rules-src", "checks").toString()));
        String outProperty = System.getProperty("flip.outRoot");
        if (outProperty == null || outProperty.isBlank())
        {
            throw new IllegalArgumentException("-Dflip.outRoot is required");
        }
        AtomicInteger sites = new AtomicInteger();
        RuleSrcExpressionWriter.Result r = RuleSrcExpressionWriter.run(checksRoot,
                Path.of(outProperty), e -> flattenUniqueSet(e, sites));
        System.out.printf(Locale.ROOT,
                "[unique-set-flattener] %d in, %d out, %d call site(s) flattened, %d unraisable %s,"
                        + " divergent from the plain raise %s%n",
                r.filesIn(), r.filesOut(), sites.get(), r.unraisable().size(), r.unraisable(),
                r.divergentFromPlainRaise());
        if (!r.unraisable().isEmpty())
        {
            throw new IllegalStateException(r.unraisable().size() + " file(s) unraisable");
        }
    }


    /**
     * The §3.9(f) transform: every {@code is_(not_)unique_set} call not already in the single-list
     * form becomes {@code f([positionals…, keys…], otherKwargs…)}; every other node is rebuilt
     * unchanged.
     *
     * @param e
     *            the tree
     * @return the rewritten tree (the same instance shape when nothing matched)
     */
    public static Expr flattenUniqueSet(Expr e)
    {
        return flattenUniqueSet(e, new AtomicInteger());
    }


    static Expr flattenUniqueSet(Expr e, AtomicInteger sites)
    {
        return switch (e)
        {
        case Expr.Call c ->
        {
            List<Expr> args = mapAll(c.args(), sites);
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, flattenUniqueSet(v, sites)));
            if (UNIQUE_SET_OPERATORS.contains(c.name())
                    && !(args.size() == 1 && isList(args.get(0))))
            {
                List<Expr> members = new ArrayList<>(args); // arg0 + trailing positionals
                Expr keys = kwargs.remove("keys");
                if (keys instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
                {
                    @SuppressWarnings("unchecked")
                    List<Expr> items = (List<Expr>) lit.value();
                    members.addAll(items); // in authored order, no dedup
                }
                else if (keys != null)
                {
                    throw new IllegalStateException(
                            c.name() + ": keys= is not a list literal: " + keys);
                }
                sites.incrementAndGet();
                yield new Expr.Call(c.name(), List.of(new Expr.Lit(Expr.LitKind.LIST, members)),
                        kwargs);
            }
            yield new Expr.Call(c.name(), args, kwargs);
        }
        case Expr.And a -> new Expr.And(mapAll(a.parts(), sites));
        case Expr.Or o -> new Expr.Or(mapAll(o.parts(), sites));
        case Expr.Not n -> new Expr.Not(flattenUniqueSet(n.inner(), sites));
        case Expr.Binary b -> new Expr.Binary(b.op(), flattenUniqueSet(b.left(), sites),
                flattenUniqueSet(b.right(), sites));
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield new Expr.Lit(Expr.LitKind.LIST, mapAll(items, sites));
            }
            yield lit;
        }
        case Expr.Ref r -> r;
        };
    }


    private static boolean isList(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST;
    }


    private static List<Expr> mapAll(List<Expr> parts, AtomicInteger sites)
    {
        List<Expr> out = new ArrayList<>(parts.size());
        for (Expr p : parts)
        {
            out.add(flattenUniqueSet(p, sites));
        }
        return out;
    }
}
