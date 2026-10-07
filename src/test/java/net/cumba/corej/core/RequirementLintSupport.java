package net.cumba.corej.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code Requirements} lints of {@code PLAN-requirements-doctrine} (rows 9, 11, 12) share:
 * the shipped corpus as loaded rules beside their shipped bodies, the leaves of a Check with their
 * polarity, the presence tests, and the requirement entries as written. Register-free, so it
 * travels to the OSS twin with the lints.
 */
final class RequirementLintSupport
{

    private RequirementLintSupport()
    {
    }

    /**
     * A shipped rule as the loader returns it, beside its shipped body.
     *
     * @param rule
     *            the loaded rule (native expression installed)
     * @param body
     *            the shipped JSON body
     */
    record Shipped(Rule rule, JsonNode body)
    {

        String id()
        {
            return Objects.requireNonNull(rule.getCore()).getId();
        }
    }

    /**
     * Every shipped rule, first package wins.
     *
     * @return id → the rule and its body
     * @throws IOException
     *             when the shipped corpus cannot be read
     */
    static Map<String, Shipped> shipped() throws IOException
    {
        Map<String, Shipped> out = new LinkedHashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        List<Path> packages;
        try (Stream<Path> s = Files.list(CorpusPaths.rules()))
        {
            packages = s.filter(p -> p.getFileName().toString().startsWith("rules-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path p : packages)
        {
            Map<String, JsonNode> bodies = new LinkedHashMap<>();
            collect(mapper.readTree(p.toFile()), bodies);
            for (Rule rule : RulePackageLoader.load(p).getRules().values())
            {
                String id = Objects.requireNonNull(rule.getCore()).getId();
                if (!out.containsKey(id) && bodies.containsKey(id))
                {
                    out.put(id, new Shipped(rule, bodies.get(id)));
                }
            }
        }
        return out;
    }


    /** A synthetic rule through the loader (its Check compiled as in the corpus), with its body. */
    static Shipped synthetic(String id, String bodyJson)
    {
        try
        {
            Rule r = RulePackageLoader.loadFromString("{\"rules\":{\"R\":" + bodyJson + "}}")
                    .getRules().get("R");
            if (r == null || r.getLoadError() != null)
            {
                throw new IllegalStateException(
                        id + ": " + (r == null ? "no rule" : r.getLoadError()));
            }
            return new Shipped(r, new ObjectMapper().readTree(bodyJson));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    private static void collect(JsonNode node, Map<String, JsonNode> out)
    {
        if (node.isObject())
        {
            JsonNode id = node.path("Core").path("Id");
            if (id.isTextual())
            {
                out.putIfAbsent(id.asText(), node);
            }
            node.forEach(child -> collect(child, out));
        }
        else if (node.isArray())
        {
            node.forEach(child -> collect(child, out));
        }
    }


    /**
     * The roots of the rule's Check: the compiled per-level or single Check, else (an ADaM template
     * rule, compiled per expansion only) the parsed authored expression.
     */
    static List<Expr> roots(Rule rule)
    {
        List<Expr> roots = new ArrayList<>(AbsentTrueLeafDetector.compiledRoots(rule));
        if (roots.isEmpty() && rule.getCheck() instanceof CheckConditionExpression ce)
        {
            roots.add(ce.expr());
        }
        return roots;
    }


    /** Every leaf of {@code e} with its polarity (an odd number of enclosing {@code not}s). */
    static List<AbsentTrueLeafDetector.Leaf> leaves(Expr e)
    {
        List<AbsentTrueLeafDetector.Leaf> out = new ArrayList<>();
        collectLeaves(e, false, out);
        return out;
    }


    private static void collectLeaves(Expr e, boolean negated,
            List<AbsentTrueLeafDetector.Leaf> out)
    {
        switch (e)
        {
        case Expr.Not n -> collectLeaves(n.inner(), !negated, out);
        case Expr.And a -> a.parts().forEach(p -> collectLeaves(p, negated, out));
        case Expr.Or o -> o.parts().forEach(p -> collectLeaves(p, negated, out));
        default -> out.add(new AbsentTrueLeafDetector.Leaf(e, negated));
        }
    }

    /**
     * The kind of presence test a leaf is, if any, and the name it tests.
     *
     * @param variable
     *            {@code true} for a column test, {@code false} for a dataset test
     * @param name
     *            the tested name, as written
     * @param firesOnAbsence
     *            whether the leaf, after its polarity, is TRUE when the name is absent
     */
    record Presence(boolean variable, String name, boolean firesOnAbsence)
    {
    }

    /**
     * The presence test {@code leaf} is: {@code var_exists} / {@code ds_exists} (fires on absence
     * under an odd number of {@code not}s), {@code var_not_exists} / {@code ds_not_exists} (under
     * an even one), or {@code count(find_vars(T)) == 0} (a template family's absence).
     *
     * @return the test, or {@code null} when the leaf is not one
     */
    static @Nullable Presence presence(AbsentTrueLeafDetector.Leaf leaf)
    {
        if (leaf.expr() instanceof Expr.Call c && !c.args().isEmpty())
        {
            String name = nameOf(c.args().getFirst());
            if (name == null)
            {
                return null;
            }
            return switch (c.name())
            {
            case "var_exists" -> new Presence(true, name, leaf.negated());
            case "ds_exists" -> new Presence(false, name, leaf.negated());
            case "var_not_exists" -> new Presence(true, name, !leaf.negated());
            case "ds_not_exists" -> new Presence(false, name, !leaf.negated());
            default -> null;
            };
        }
        if (leaf.expr() instanceof Expr.Binary b && b.op() == Expr.BinOp.EQ
                && b.left() instanceof Expr.Call count && "count".equals(count.name())
                && count.args().size() == 1 && count.args().getFirst() instanceof Expr.Call find
                && "find_vars".equals(find.name()) && !find.args().isEmpty()
                && b.right() instanceof Expr.Lit zero && zero.value() instanceof Double d
                && d == 0.0)
        {
            String name = nameOf(find.args().getFirst());
            return name == null ? null : new Presence(true, name, !leaf.negated());
        }
        return null;
    }


    /** Whether {@code leaf} is a presence test of any kind (BroadcastFold's own predicate). */
    static boolean isPresenceLeaf(AbsentTrueLeafDetector.Leaf leaf)
    {
        return presence(leaf) != null
                || (leaf.expr() instanceof Expr.Call c && BroadcastFold.isExistsCall(c));
    }


    static @Nullable String nameOf(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit when lit.value() instanceof String s -> s;
        default -> null;
        };
    }


    /**
     * A requirement entry compared as the census compares it: upper-cased, a trailing
     * {@code :N}/{@code :C} type suffix stripped (never from a {@code /regex/}).
     */
    static String key(String entry)
    {
        String e = entry.trim();
        int colon = e.lastIndexOf(':');
        if (colon > 0 && !e.startsWith("/"))
        {
            e = e.substring(0, colon);
        }
        return e.toUpperCase(Locale.ROOT);
    }


    /** The entries of {@code Requirements.Variables.<facet>}, as written, groups flattened. */
    static List<String> entries(JsonNode body, String facet)
    {
        List<String> out = new ArrayList<>();
        for (JsonNode n : body.path("Requirements").path("Variables").path(facet))
        {
            if (n.isArray())
            {
                n.forEach(m -> out.add(m.asText()));
            }
            else
            {
                out.add(n.asText());
            }
        }
        return out;
    }


    /** {@code Requirements.Datasets}, as written. */
    static List<String> datasets(JsonNode body)
    {
        List<String> out = new ArrayList<>();
        body.path("Requirements").path("Datasets").forEach(n -> out.add(n.asText()));
        return out;
    }


    /** The upper-cased keys of a list of entries. */
    static Set<String> keys(List<String> entries)
    {
        Set<String> out = new LinkedHashSet<>();
        entries.forEach(e -> out.add(key(e)));
        return out;
    }
}
