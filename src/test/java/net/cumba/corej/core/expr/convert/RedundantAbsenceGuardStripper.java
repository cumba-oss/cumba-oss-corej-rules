package net.cumba.corej.core.expr.convert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * ⚠ <b>One-shot corpus migration</b> — Plan B phase <b>4d′</b>
 * ({@code plans/done/PLAN-scope-requirements-split.md} §5, owner ruling <b>B-Q13</b>, 2026-08-24).
 * Strips the <em>dead</em> absence-tolerant disjunct from every Check that carries it:
 *
 * <pre>
 * not var_exists("X") or empty(X)   →  empty(X)
 * empty(X) or not var_exists("X")   →  empty(X)
 * </pre>
 *
 * <h2>Why the disjunct is dead</h2>
 *
 * <p>
 * Since {@code Fix #330} retired the injector's bare-emptiness arm, <em>absent ≡ all-missing</em>
 * ({@code EC-43}): {@code empty(X)} already answers {@code true} for a column that does not exist,
 * so the {@code not var_exists("X")} disjunct can never change the value of the disjunction. Ruling
 * B-Q13 releases {@code M3-D19}'s corpus-wide hold on authored {@code or not var_exists(X)}
 * disjuncts for <b>exactly this one shape</b>, and for no other.
 * </p>
 *
 * <h2>⚠⚠ What it must never touch, and why the transform is shape-keyed rather than file-keyed</h2>
 *
 * <ul>
 * <li>the <b>S4</b> shape {@code not var_exists("X") or <non-empty predicate on X>} (e.g.
 * {@code … or X != "Y"}) is <b>protected</b> by the same ruling — there the guard is load-bearing,
 * because the surviving predicate does <em>not</em> fold true on an absent column. <b>14 of the 146
 * wipe rules also carry an S4 disjunct</b>, so a file-level sweep would silently change what those
 * rules report. This transform matches the {@code Or} node, never the file, so an S4 disjunct in
 * the same Check is structurally out of reach;</li>
 * <li>the <b>S3</b> spelling {@code not var_exists(REF) or …} (unquoted reference argument, 5
 * rules) does not match: {@link #absenceGuardOf} requires a quoted (string-literal) argument, which
 * is the grounding's key;</li>
 * <li>the AND form {@code var_exists(X) and empty(X)} is explicitly <b>not</b> revised by B-Q13; it
 * is left alone because nothing here matches an {@code And};</li>
 * <li>the <b>ten</b> {@code B-Q10} {@code Any}-closure rules are excluded <em>by ruling</em> — they
 * adopt {@code Requirements.Variables.Any} in phase 4d and their Checks stay byte-identical.
 * Excluding them is the one thing the shape cannot express, which is why this class drives the
 * corpus itself instead of handing a bare transform to
 * {@link RuleSrcExpressionWriter#run(Path, Path, UnaryOperator)}.</li>
 * </ul>
 *
 * <p>
 * Layout, canonical printing and the per-file self-verification are
 * {@link RuleSrcExpressionWriter}'s, not re-implemented here — exactly as
 * {@link UniqueSetFlattener} (Plan A phase 2) arranged it. A re-run over a migrated corpus is a
 * no-op: the stripped shape no longer matches.
 * </p>
 *
 * <pre>{@code
 * cd cumba-corej-rules
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.corej.core.expr.convert.RedundantAbsenceGuardStripper \
 *     -Dflip.outRoot=/abs/path/to/output/checks
 * }</pre>
 */
public final class RedundantAbsenceGuardStripper
{

    /** The presence call whose negation forms the dead disjunct. */
    static final String VAR_EXISTS = "var_exists";

    /** The emptiness call that already covers the absent case (EC-43, {@code Fix #330}). */
    static final String EMPTY = "empty";

    /**
     * ⚑ EMPTY since {@code PLAN-retire-corpus-transforms.md} phase 7 (B2): ruling B-Q13's exclusion
     * of the {@code B-Q10} {@code Any} closure is RELEASED. The exclusion was procedural — it kept
     * those ten Checks byte-identical while they adopted {@code Requirements.Variables.Any} in the
     * same wave — not semantic: under {@code Any}, when X is absent the rule still runs (a sibling
     * is present) and {@code empty(X)} is true (EC-43), so the strip is equivalence-preserving
     * there too. The constant is kept (empty) as the record of the released ruling; the ten former
     * members' 20 occurrences were stripped by re-running this tool.
     */
    static final Set<String> B_Q10_ANY_CLOSURE = Set.of();

    private RedundantAbsenceGuardStripper()
    {
    }


    /**
     * Runs the migration over the corpus. Same configuration contract as the writer:
     * {@code flip.checksRoot} (default {@code rules-src/checks}) and {@code flip.outRoot}
     * (required; the corpus directory itself for the in-place migration).
     *
     * @param args
     *            ignored (configuration is via system properties)
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
        Report report = run(checksRoot, Path.of(outProperty));
        System.out.printf(Locale.ROOT,
                "[4d-prime] %d file(s) in, %d rewritten, %d occurrence(s) stripped,"
                        + " %d excluded by ruling B-Q13 (B-Q10 Any closure)%n",
                report.filesIn(), report.rulesChanged().size(), report.occurrencesStripped(),
                report.excluded().size());
        report.rulesChanged()
                .forEach((id, n) -> System.out.printf(Locale.ROOT, "  %-16s %d%n", id, n));
    }

    /** Per-run tallies: the counts the ledger entry has to reconcile against 146 / 237. */
    record Report(int filesIn, Map<String, Integer> rulesChanged, int occurrencesStripped,
            List<String> excluded)
    {
    }

    /**
     * Rewrites every {@code *.yaml} under {@code checksRoot} into {@code outRoot} (which may equal
     * {@code checksRoot}), stripping the dead disjunct from every rule outside
     * {@link #B_Q10_ANY_CLOSURE}. Files whose expression does not change are written back
     * unchanged, so the output tree is always complete.
     *
     * @param checksRoot
     *            the authored corpus root ({@code rules-src/checks} shaped)
     * @param outRoot
     *            where the rewritten files go
     * @return the run's tallies
     * @throws IOException
     *             if a file cannot be read or written
     */
    static Report run(Path checksRoot, Path outRoot) throws IOException
    {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(checksRoot))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .forEach(files::add);
        }
        Map<String, Integer> changed = new TreeMap<>();
        List<String> excluded = new ArrayList<>();
        int stripped = 0;
        for (Path file : files)
        {
            String id = file.getFileName().toString().replace(".yaml", "");
            Counter counter = new Counter();
            boolean skip = B_Q10_ANY_CLOSURE.contains(id);
            UnaryOperator<Expr> transform = skip ? UnaryOperator.identity()
                    : expr -> strip(expr, counter);
            RuleSrcExpressionWriter.Conversion c = RuleSrcExpressionWriter.convertFile(
                    checksRoot.relativize(file), Files.readString(file, StandardCharsets.UTF_8),
                    transform);
            if (skip && carriesDeadDisjunct(file))
            {
                excluded.add(id);
            }
            if (counter.count > 0)
            {
                changed.put(id, counter.count);
                stripped += counter.count;
            }
            Path target = outRoot.resolve(c.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, c.fileText(), StandardCharsets.UTF_8);
        }
        return new Report(files.size(), changed, stripped, excluded);
    }


    /** Whether an excluded file would have been rewritten — reported, so the −10 reconciles. */
    private static boolean carriesDeadDisjunct(Path file) throws IOException
    {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.contains("var_exists(\"") && text.contains(EMPTY + "(");
    }

    /** Mutable occurrence tally — a lambda cannot close over a mutable local. */
    private static final class Counter
    {

        private int count;
    }

    /**
     * The transform: bottom-up, so a guard nested inside another operator is reached.
     *
     * @param node
     *            the expression to rewrite
     * @param counter
     *            tallies the stripped occurrences
     * @return the rewritten expression, or {@code node} itself when nothing matched
     */
    private static Expr strip(Expr node, Counter counter)
    {
        Expr rewritten = switch (node)
        {
        case Expr.And a -> new Expr.And(a.parts().stream().map(p -> strip(p, counter)).toList());
        case Expr.Or o -> new Expr.Or(o.parts().stream().map(p -> strip(p, counter)).toList());
        case Expr.Not n -> new Expr.Not(strip(n.inner(), counter));
        case Expr.Binary b -> new Expr.Binary(b.op(), strip(b.left(), counter),
                strip(b.right(), counter));
        case Expr.Call c -> new Expr.Call(c.name(),
                c.args().stream().map(a -> strip(a, counter)).toList(),
                c.kwargs().entrySet().stream().collect(java.util.stream.Collectors
                        .toMap(Map.Entry::getKey, e -> strip(e.getValue(), counter))));
        default -> node;
        };
        return rewritten instanceof Expr.Or or ? dropDeadGuards(or, counter) : rewritten;
    }


    /**
     * Drops every dead absence guard from one disjunction: a {@code not var_exists("X")} part is
     * removed whenever some <b>other part of the same {@code Or}</b> is {@code empty(X)}.
     *
     * <p>
     * ⭐ <b>Why sibling-subsumption and not a two-part shape.</b> Since {@code Fix #330},
     * {@code empty(X)} answers {@code true} for an absent column, so {@code not var_exists("X")}
     * <em>implies</em> {@code empty(X)}. A disjunct implied by another disjunct of the same
     * disjunction can never change its value — {@code A or B or C} with {@code A ⇒ B} is
     * {@code B or C} — so the removal is sound at <b>any</b> arity, and restricting it to a
     * two-part {@code Or} would miss 44 of the 237 occurrences ruling B-Q13 names (11 rules
     * entirely, e.g. {@code FDA-SD1449}'s twelve-pair disjunction, plus one occurrence each in
     * {@code FDA-SE2308} and {@code PMDA-AD0159}).
     * </p>
     *
     * <p>
     * ⚠⚠ The <b>S4</b> shape survives this by construction: there is no {@code empty(X)} sibling to
     * subsume the guard, so nothing is dropped. Where an S4-shaped predicate happens to sit in a
     * disjunction that <em>also</em> carries {@code empty(X)} (e.g. {@code FDA-SE1157}'s
     * {@code --RSTIND == "N" or empty(--RSTIND) or not var_exists("--RSTIND")}), the guard is still
     * dead and the S4 predicate itself is left untouched.
     * </p>
     *
     * <h4>⚑ The 146 / 237 figures are a FLOOR for this shape, not a census of dead disjuncts</h4>
     *
     * <p>
     * Both the guard and its subsumer must be a <em>direct part</em> of the same {@code Or}:
     * {@link #absenceGuardOf} and {@link #emptyOperandOf} match a bare {@code Expr.Not}/
     * {@code Expr.Call}, so a guard buried inside an {@code And} is structurally invisible. That
     * leaves a second, coarser dead shape untouched — a whole <em>disjunct</em> subsumed by another
     * disjunct, where the two conjunctions are identical except that one spells {@code empty(X)}
     * where the other spells {@code not var_exists("X")}. Since {@code not var_exists(X)} implies
     * {@code empty(X)} (the same {@code Fix #330} / {@code EC-43} fact this whole transform rests
     * on), the guard-carrying conjunction implies its sibling and can never change the value of the
     * disjunction.
     * </p>
     *
     * <p>
     * Measured over {@code rules-src/checks} on 2026-08-25, <b>six rules keep eight such dead
     * disjuncts</b> — more than the four the plan-B review named:
     * </p>
     * <ul>
     * <li>{@code CDISC-CG0434}, {@code CORE-000122}, {@code FDA-SD2021}, {@code PMDA-SD2021} (one
     * each): {@code not empty(AGEU) and empty(AGE) and empty(AGETXT) or not empty(AGEU) and
     * empty(AGE) and not var_exists("AGETXT")} — the second conjunction implies the first;</li>
     * <li>{@code CDISC-CG0348}, {@code CORE-000200} (two each): the four-way {@code --STAT} /
     * {@code --DRVFL} / {@code --ORRES} disjunction, whose third and fourth conjunctions imply its
     * first and second respectively.</li>
     * </ul>
     *
     * <p>
     * ⭐ <b>Leaving them is safe, and the direction of the error is the safe one.</b> This transform
     * only ever <em>misses</em> a dead disjunct; it can never over-strip, because it removes a part
     * exclusively when a sibling of the same {@code Or} already subsumes it. A missed disjunct is
     * redundant, not wrong: the rule fires on exactly the same rows either way, so no finding
     * moves. Widening the match to whole-disjunct subsumption is therefore a separate change, and a
     * <b>corpus-moving</b> one — it would rewrite six shipped Checks and shift their L1
     * fingerprints — so it needs its own ruling, its own findings-diff and its own byte-exact
     * snapshot comparison. Do not fold it in here.
     * </p>
     */
    private static Expr dropDeadGuards(Expr.Or or, Counter counter)
    {
        Set<String> emptied = new java.util.HashSet<>();
        for (Expr part : or.parts())
        {
            String name = emptyOperandOf(part);
            if (name != null)
            {
                emptied.add(name);
            }
        }
        if (emptied.isEmpty())
        {
            return or;
        }
        List<Expr> kept = new ArrayList<>();
        for (Expr part : or.parts())
        {
            String guarded = absenceGuardOf(part);
            if (guarded != null && emptied.contains(guarded))
            {
                counter.count++;
                continue;
            }
            kept.add(part);
        }
        if (kept.size() == or.parts().size())
        {
            return or;
        }
        // A disjunction cannot lose every part: the empty(X) that subsumed each dropped guard is
        // itself kept, so at least one survives. One survivor collapses to that expression.
        return kept.size() == 1 ? kept.get(0) : new Expr.Or(kept);
    }


    /**
     * The column named by a {@code not var_exists("X")} disjunct, or {@code null}.
     *
     * <p>
     * The argument must be a <b>string literal</b> — that is the grounding's key, and it is what
     * keeps the S3 spelling {@code not var_exists(REF)} (5 rules, a different shape with a
     * different meaning) out of the population.
     * </p>
     */
    private static @Nullable String absenceGuardOf(Expr part)
    {
        if (!(part instanceof Expr.Not not) || !(not.inner() instanceof Expr.Call call))
        {
            return null;
        }
        if (!VAR_EXISTS.equals(call.name()) || call.args().size() != 1 || !call.kwargs().isEmpty())
        {
            return null;
        }
        return call.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                ? (String) lit.value()
                : null;
    }


    /** The column named by an {@code empty(X)} disjunct, or {@code null}. */
    private static @Nullable String emptyOperandOf(Expr part)
    {
        if (!(part instanceof Expr.Call call) || !EMPTY.equals(call.name())
                || call.args().size() != 1 || !call.kwargs().isEmpty())
        {
            return null;
        }
        return call.args().get(0) instanceof Expr.Ref ref ? ref.name() : null;
    }

}
