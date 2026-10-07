package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.EmptyColumnCollapse;
import net.cumba.corej.core.expr.eval.FoldCapability;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.testkit.MockTable;

/**
 * The <b>computed</b> absent-true-leaf (ATL) detector of {@code PLAN-requirements-doctrine} row 8 —
 * the census behind {@code AbsentColumnVerdictsLintTest} (internal corpus only) (R-4.11b, R-4.11e)
 * — built on the engine's <b>public</b> fold API, never on a regex over the Check text.
 *
 * <h2>What it computes</h2> For every compiled Check root of a loaded rule (the per-level roots of
 * a level-keyed Check, else the single compiled Check — never the authored text), every leaf that
 * reads exactly ONE column is folded with that column absent, exactly as the runner folds it
 * ({@link BroadcastFold#fold} on {@link FoldCapability#censusContext}: the gated columns present,
 * every other column absent, the rule's own type expectations — so an absent column reads
 * {@code MissingValue.MIS} where the rule reads it as a number and {@code ""} otherwise, D76). The
 * leaf is <b>absent-true</b> when that verdict, negated once per enclosing {@code not}, is TRUE. A
 * pair is reported when the leaf has a sibling in its root (the fold of the root then reaches the
 * row path, where the absent leaf paints every row) and the column is neither gated
 * ({@code Requirements.Variables.All} / {@code Any}) nor guarded in the Check ({@code var_exists}
 * over it, or a {@code not empty(X)} conjunct — R-5.23's opt-out).
 *
 * <h2>The two kinds of pair</h2> A pair is <b>mf-discharged</b> when its column is a live
 * {@code Message_Folded} trigger ({@link FoldCapability#triggers}: a polarity-live
 * {@code empty}-family leaf over a plain column outside {@code Keep_Row_Findings}) <b>and</b> the
 * rule declares {@code Outcome.Message_Folded} — the absence reports once per dataset; every other
 * pair is <b>flood</b>-shaped — the absence reports once per record.
 *
 * <h2>Reach, and what is out of reach by design</h2>
 * <ul>
 * <li>{@code X != <number>}, {@code not in}, {@code f(X) != "lit"}, a negated function — every
 * single-column leaf the constant makes true, with no operator list (R-5.22: polarity follows from
 * the constant);</li>
 * <li>a <b>dotted</b> {@code DS.X}: the census context does not evaluate a dotted read, so the
 * reference is renamed to a plain absent column of the same expected type (the rule's own reading
 * of the absent read, D76, which answers for a dotted name as for a plain one) and folded the same
 * way;</li>
 * <li>⛔ {@code X != OTHERVAR} is <b>out of reach</b>: a leaf reading two columns does not fold at
 * dataset level (D39a), so it is never single-column here — column-vs-column OPERAND pairs are the
 * AST check of {@code CorejFamilyRequirementsLintTest};</li>
 * <li>a leaf over a {@code $}-binding is {@code ColumnTypedNumericReadLintTest}'s population (5d),
 * not this one: a binding is not a column read;</li>
 * <li>a column-status predicate ({@code var_is_empty}, {@code var_is_all_missing},
 * {@code var_is_single_value}) tests a NAME, not a read — its absent verdict is the predicate's own
 * contract (D76). Decided per carrier 2026-10-07: the five rule files using one read it through a
 * {@code $}-binding ({@code CDISC-SEND-0105} / {@code -0106}, the binding population above) or over
 * the variable cursor {@code varname()} ({@code FDA-SD1078}, {@code PMDA-SD1078},
 * {@code FDA-SD1149}), which names only present columns — none can flood;</li>
 * <li>a glob ({@code *DT}, {@code *}) binds only the columns a dataset has, so it is never
 * absent.</li>
 * </ul>
 */
final class AbsentTrueLeafDetector
{

    /** The synthetic variable wildcard prefix rules are specialised under (the census's). */
    static final String PREFIX = MessageFoldedCoverageLintTest.PREFIX;

    /** The anchor column of the census table (the census's). */
    static final String ANCHOR = MessageFoldedCoverageLintTest.ANCHOR;

    /** The plain column every folded read is renamed to — never on the census table, so absent. */
    static final String STAND_IN = "ZZZABSENT";

    /** Leaf shapes, for the per-shape floors. */
    enum Shape
    {
        /** {@code empty(X)} / {@code is_missing(X)} — the empty family. */
        EMPTY,
        /** {@code X != "lit"}. */
        NEQ_STRING,
        /** {@code X != 5}. */
        NEQ_NUMBER,
        /** {@code X not in [...]}. */
        NOT_IN,
        /** Any other binary leaf ({@code !~}, an order comparison, {@code f(X) != "lit"} …). */
        OTHER_BINARY,
        /**
         * A call that is not an empty-family leaf — a negated function, {@code equalsIgnoreCase} …
         */
        CALL
    }


    /**
     * One absent-true leaf pair.
     *
     * @param rule
     *            the rule id
     * @param column
     *            the column as the authored Check names it ({@code --} restored)
     * @param shape
     *            the leaf's shape
     * @param dotted
     *            whether the column is a dotted {@code DS.X} read
     * @param gated
     *            whether {@code Requirements.Variables.All} / {@code Any} gates it
     * @param datasetGated
     *            whether the rule declares a supplemental dataset in {@code Requirements.Datasets}
     *            (R-4.11b (c): a partial gate for a column that dataset delivers)
     * @param guarded
     *            whether the Check guards it ({@code var_exists} over it, or {@code not empty(X)})
     * @param mfDischarged
     *            whether it is a live {@code Message_Folded} trigger of a rule declaring the key
     */
    record Pair(String rule, String column, Shape shape, boolean dotted, boolean gated,
            boolean datasetGated, boolean guarded, boolean mfDischarged)
    {

        /** The {@code <rule>\t<column>} key of the registers. */
        String key()
        {
            return rule + "\t" + column;
        }


        /** Ungated and unguarded: the pair R-4.11b's completeness check asks about. */
        boolean open()
        {
            return !gated && !guarded;
        }
    }

    private AbsentTrueLeafDetector()
    {
    }

    /**
     * The census over the shipped corpus.
     *
     * @param pairs
     *            every absent-true pair, gated and guarded ones included
     * @param rules
     *            the number of distinct shipped rules visited
     * @param datasetSensitivityRules
     *            the ids of the rules that DECLARE {@code Sensitivity: "Dataset"}
     */
    record Census(List<Pair> pairs, int rules, Set<String> datasetSensitivityRules)
    {
    }

    /**
     * Loads every shipped package with the engine's own loader (native expression installed), and
     * reports every rule's pairs once (first package wins, as the coverage census does).
     *
     * @return the census
     * @throws IOException
     *             when the shipped corpus cannot be read
     */
    static Census census() throws IOException
    {
        List<Path> packages;
        try (Stream<Path> s = Files.list(CorpusPaths.rules()))
        {
            packages = s.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        if (packages.isEmpty())
        {
            throw new IllegalStateException("no shipped package under " + CorpusPaths.rules()
                    + " (target/rules generated?)");
        }
        ObjectMapper mapper = new ObjectMapper();
        Set<String> seen = new HashSet<>();
        Set<String> datasetSensitive = new LinkedHashSet<>();
        List<Pair> out = new ArrayList<>();
        for (Path p : packages)
        {
            Map<String, JsonNode> bodies = new LinkedHashMap<>();
            collectBodies(mapper.readTree(p.toFile()), bodies);
            for (Rule rule : RulePackageLoader.load(p).getRules().values())
            {
                String id = Objects.requireNonNull(rule.getCore()).getId();
                if (!seen.add(id))
                {
                    continue;
                }
                if (rule.getLoadError() != null)
                {
                    throw new IllegalStateException(id + ": " + rule.getLoadError());
                }
                JsonNode body = Objects.requireNonNull(bodies.get(id), id);
                if ("Dataset".equals(body.path("Sensitivity").asText("")))
                {
                    datasetSensitive.add(id);
                }
                out.addAll(pairs(rule, body));
            }
        }
        return new Census(out, seen.size(), datasetSensitive);
    }


    /**
     * Every shipped rule as the engine's loader returns it, keyed by id (first package wins, as the
     * census does).
     *
     * @return id → loaded rule
     * @throws IOException
     *             when the shipped corpus cannot be read
     */
    static Map<String, Rule> shippedRules() throws IOException
    {
        Map<String, Rule> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(CorpusPaths.rules()))
        {
            for (Path p : s.filter(f -> f.getFileName().toString().startsWith("rules-")
                    && f.getFileName().toString().endsWith(".json")).sorted().toList())
            {
                for (Rule rule : RulePackageLoader.load(p).getRules().values())
                {
                    out.putIfAbsent(Objects.requireNonNull(rule.getCore()).getId(), rule);
                }
            }
        }
        return out;
    }


    private static void collectBodies(JsonNode node, Map<String, JsonNode> out)
    {
        if (node.isObject())
        {
            JsonNode id = node.path("Core").path("Id");
            if (id.isTextual())
            {
                out.putIfAbsent(id.asText(), node);
            }
            node.forEach(child -> collectBodies(child, out));
        }
        else if (node.isArray())
        {
            node.forEach(child -> collectBodies(child, out));
        }
    }


    /**
     * The absent-true pairs of one loaded rule, de-duplicated by column (the first shape seen wins;
     * a column is mf-discharged / gated / guarded as a property of the rule, not the leaf).
     *
     * @param rule
     *            the rule as the loader returns it (native expression installed)
     * @param json
     *            the shipped rule body (for the gates and {@code Requirements.Datasets} as written)
     * @return the pairs, in Check order
     */
    static List<Pair> pairs(Rule rule, JsonNode json)
    {
        Probe probe = probe(rule);
        Rule spec = probe.spec();
        EvaluationContext ctx = probe.ctx();
        TypeExpectations expectations = probe.expectations();
        Set<String> triggers = upper(liveTriggers(probe));
        boolean declaresKey = rule.getOutcome() != null
                && rule.getOutcome().getMessageFolded() != null;
        Set<String> gates = upper(writtenGates(json));
        boolean supplementalDataset = declaresSupplementalDataset(json);
        String checkText = json.path("Check").toString();

        Map<String, Pair> out = new LinkedHashMap<>();
        for (Expr root : compiledRoots(spec))
        {
            List<Leaf> leaves = new ArrayList<>();
            collect(root, false, leaves);
            if (leaves.size() < 2)
            {
                continue; // a bare leaf keeps its broadcast verdict: one finding, never a flood
            }
            Set<String> guards = guardedColumns(leaves);
            for (Leaf leaf : leaves)
            {
                Set<String> reads = FoldCapability.readColumnNames(leaf.expr());
                if (reads.size() != 1)
                {
                    continue;
                }
                String x = reads.iterator().next();
                // a glob (`*DT`, `*`) binds only the columns a dataset HAS — a per-variable cursor
                // over present names — so it is never absent and never floods
                if (x.indexOf('*') >= 0 || !absentTrue(leaf, x, ctx, expectations))
                {
                    continue;
                }
                String written = written(x, checkText);
                String upperX = x.toUpperCase(Locale.ROOT);
                boolean dotted = x.indexOf('.') > 0;
                boolean gated = gates.contains(written.toUpperCase(Locale.ROOT));
                out.putIfAbsent(written,
                        new Pair(rule.getCore() == null ? "?" : rule.getCore().getId(), written,
                                shapeOf(leaf.expr()), dotted, gated, supplementalDataset && !gated,
                                guards.contains(upperX),
                                declaresKey && !dotted && triggers.contains(upperX)));
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * A rule specialised under {@link #PREFIX}, on its census context (the gated columns present,
     * every other column absent), with its type expectations.
     *
     * @param spec
     *            the specialised rule
     * @param ctx
     *            the census context
     * @param expectations
     *            the specialised rule's type expectations
     */
    record Probe(Rule spec, EvaluationContext ctx, TypeExpectations expectations)
    {
    }

    /** The census probe of a loaded rule — the context the coverage census builds. */
    static Probe probe(Rule rule)
    {
        Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(rule, null, PREFIX));
        MockTable table = MockTable.of().name(PREFIX).col(ANCHOR, "v");
        for (String gated : FoldCapability.guaranteedPresent(spec, PREFIX))
        {
            if (!gated.equalsIgnoreCase(ANCHOR) && gated.indexOf('.') < 0)
            {
                table.col(gated, "v");
            }
        }
        EvaluationContext ctx = FoldCapability.censusContext(spec, table.build(), PREFIX);
        return new Probe(spec, ctx, TypeExpectations.forRule(spec));
    }


    /**
     * The rule's live {@code Message_Folded} triggers ({@link FoldCapability#triggers}: a
     * polarity-live {@code empty}-family leaf over a plain column outside
     * {@code Keep_Row_Findings}), as the specialised Check names them ({@code --} resolved to
     * {@link #PREFIX}).
     */
    static Set<String> liveTriggers(Probe probe)
    {
        return FoldCapability
                .triggers(probe.spec(), probe.ctx(), EmptyColumnCollapse
                        .resolveKeepRowFindings(probe.spec().getOutcome(), probe.ctx()))
                .blank().keySet();
    }


    /**
     * {@code name} as the specialised Check names it: a leading {@code --} resolved to
     * {@link #PREFIX}, as {@code RuleSpecialiser} resolves it (the inverse of {@link #written}).
     */
    static String specialised(String name)
    {
        String n = name.trim();
        return n.startsWith("--") ? PREFIX + n.substring(2) : n;
    }

    /** A leaf of a compiled root and its polarity. */
    record Leaf(Expr expr, boolean negated)
    {
    }

    private static void collect(Expr e, boolean negated, List<Leaf> out)
    {
        switch (e)
        {
        case Expr.Not not -> collect(not.inner(), !negated, out);
        case Expr.And and -> and.parts().forEach(p -> collect(p, negated, out));
        case Expr.Or or -> or.parts().forEach(p -> collect(p, negated, out));
        default -> out.add(new Leaf(e, negated));
        }
    }


    /**
     * The compiled roots: the per-level expressions of a level-keyed Check, else the single
     * compiled Check (the same choice {@code FoldCapability.compiledRoots} makes; the authored text
     * is never read).
     */
    static List<Expr> compiledRoots(Rule spec)
    {
        List<Expr> roots = new ArrayList<>();
        if (spec.getCheckLevelExprs() != null)
        {
            roots.addAll(spec.getCheckLevelExprs().values());
        }
        else if (spec.getCheckExpr() != null)
        {
            roots.add(spec.getCheckExpr());
        }
        if (roots.isEmpty() && spec.getCheck() instanceof CheckConditionExpression ce)
        {
            // an ADaM template rule is compiled per expansion only (WildcardExpander), so its
            // specialised form carries no compiled Check: the parsed authored expression is the
            // one form there is — FoldCapability's own fallback, for the same rules
            roots.add(ce.expr());
        }
        return roots;
    }


    /**
     * Whether {@code leaf}, with its one column {@code x} absent, is TRUE after its polarity — the
     * runner's own fold ({@link BroadcastFold#fold}, the absent arm on). A dotted read is renamed
     * to a plain absent stand-in that carries the dotted name's own type expectation (D76).
     */
    static boolean absentTrue(Leaf leaf, String x, EvaluationContext ctx,
            TypeExpectations expectations)
    {
        return absentVerdict(leaf, x, ctx, expectations) == BroadcastFold.Verdict.TRUE;
    }


    /**
     * The leaf's verdict with its one column {@code x} absent, after its polarity: TRUE (it fires
     * on every row of an absent column), FALSE (a positive test: silent on one), or UNKNOWN (not
     * decided by the absence alone).
     */
    static BroadcastFold.Verdict absentVerdict(Leaf leaf, String x, EvaluationContext ctx,
            TypeExpectations expectations)
    {
        EvaluationContext c = ctx;
        // ⭐ Every column is folded through the ABSENT stand-in, whatever its kind: a gated column
        // is PRESENT on the census context (so a direct fold would read it and decide nothing —
        // R-4.11e's gated pairs need the absent reading too), a dotted read is not evaluated by the
        // census context at all, and a marker template binds no capture there. The stand-in
        // carries the original name's own type expectation (D76), so a plain ungated column folds
        // exactly as it would under its own name.
        Expr e = rename(leaf.expr(), x);
        if (expectations.numericExpected(x))
        {
            Set<String> numeric = new HashSet<>(ctx.getNumericExpectedColumns());
            numeric.add(STAND_IN);
            c = ctx.toBuilder().numericExpectedColumns(numeric).build();
        }
        BroadcastFold.Verdict v = BroadcastFold.fold(e, c, false);
        if (!leaf.negated() || v == BroadcastFold.Verdict.UNKNOWN)
        {
            return v;
        }
        return v == BroadcastFold.Verdict.TRUE ? BroadcastFold.Verdict.FALSE
                : BroadcastFold.Verdict.TRUE;
    }


    /**
     * {@code e} with every reference to {@code x} — plain, {@code --}-templated, ADaM-marker or
     * dotted — replaced by {@link #STAND_IN}.
     */
    static Expr rename(Expr e, String x)
    {
        return switch (e)
        {
        case Expr.Ref r when (r.kind() == OperandKind.DOTTED_REF
                || r.kind() == OperandKind.WILDCARD_COLUMN || r.kind() == OperandKind.COLUMN)
                && r.name().equals(x) -> new Expr.Ref(STAND_IN, OperandKind.COLUMN);
        case Expr.Call c ->
        {
            List<Expr> args = new ArrayList<>();
            c.args().forEach(a -> args.add(rename(a, x)));
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, rename(v, x)));
            yield new Expr.Call(c.name(), args, kwargs);
        }
        case Expr.Binary b -> new Expr.Binary(b.op(), rename(b.left(), x), rename(b.right(), x));
        case Expr.And a -> new Expr.And(a.parts().stream().map(p -> rename(p, x)).toList());
        case Expr.Or o -> new Expr.Or(o.parts().stream().map(p -> rename(p, x)).toList());
        case Expr.Not n -> new Expr.Not(rename(n.inner(), x));
        default -> e;
        };
    }


    /**
     * The columns the Check guards, upper-cased: every {@code var_exists} / {@code var_not_exists}
     * leaf's name, and every column read by an {@code empty} leaf under an odd number of
     * {@code not}s (R-5.23's {@code not empty(X)} opt-out — FALSE on an absent column).
     */
    private static Set<String> guardedColumns(List<Leaf> leaves)
    {
        Set<String> out = new HashSet<>();
        for (Leaf leaf : leaves)
        {
            if (!(leaf.expr() instanceof Expr.Call c))
            {
                continue;
            }
            if (BroadcastFold.isExistsCall(c) && !c.args().isEmpty())
            {
                String name = switch (c.args().getFirst())
                {
                case Expr.Ref r -> r.name();
                case Expr.Lit lit when lit.value() instanceof String s -> s;
                default -> null;
                };
                if (name != null)
                {
                    out.add(name.toUpperCase(Locale.ROOT));
                }
            }
            else if (leaf.negated() && ("empty".equals(c.name()) || "is_missing".equals(c.name()))
                    && c.args().size() == 1 && c.args().getFirst() instanceof Expr.Ref r)
            {
                out.add(r.name().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }


    static Shape shapeOf(Expr e)
    {
        if (e instanceof Expr.Call c)
        {
            return EmptyColumnCollapse.EMPTY_FAMILY.contains(c.name()) ? Shape.EMPTY : Shape.CALL;
        }
        if (e instanceof Expr.Binary b)
        {
            if (b.op() == Expr.BinOp.NOT_IN)
            {
                return Shape.NOT_IN;
            }
            if (b.op() == Expr.BinOp.NEQ && b.left() instanceof Expr.Ref
                    && b.right() instanceof Expr.Lit lit)
            {
                return lit.kind() == Expr.LitKind.NUMBER ? Shape.NEQ_NUMBER
                        : lit.kind() == Expr.LitKind.STRING ? Shape.NEQ_STRING : Shape.OTHER_BINARY;
            }
            return Shape.OTHER_BINARY;
        }
        return Shape.OTHER_BINARY;
    }


    /**
     * The census name back in the authored spelling: the specialised {@code XX} prefix returns to
     * {@code --} where the shipped Check writes it that way.
     */
    static String written(String x, String checkText)
    {
        if (checkText.contains(x))
        {
            return x;
        }
        String back = x.replace(PREFIX, "--");
        return checkText.contains(back) ? back : x;
    }


    /** {@code Requirements.Variables.All} and every {@code Any} member, as written. */
    static Set<String> writtenGates(JsonNode rule)
    {
        Set<String> gate = new LinkedHashSet<>();
        JsonNode req = rule.path("Requirements").path("Variables");
        for (String key : List.of("All", "Any"))
        {
            for (JsonNode n : req.path(key))
            {
                if (n.isArray())
                {
                    n.forEach(m -> gate.add(stripSuffix(m.asText())));
                }
                else
                {
                    gate.add(stripSuffix(n.asText()));
                }
            }
        }
        return gate;
    }


    private static String stripSuffix(String entry)
    {
        String e = entry.trim();
        int colon = e.lastIndexOf(':');
        return colon > 0 && e.indexOf('/') < 0 ? e.substring(0, colon) : e;
    }


    /** Whether {@code Requirements.Datasets} names a supplemental dataset (R-4.11b (c)). */
    static boolean declaresSupplementalDataset(JsonNode rule)
    {
        for (JsonNode d : rule.path("Requirements").path("Datasets"))
        {
            String name = d.asText().toUpperCase(Locale.ROOT);
            if (name.startsWith("SUPP") || name.startsWith("SQAP"))
            {
                return true;
            }
        }
        return false;
    }


    private static Set<String> upper(Set<String> in)
    {
        Set<String> out = new HashSet<>();
        in.forEach(s -> out.add(s.toUpperCase(Locale.ROOT)));
        return out;
    }
}
