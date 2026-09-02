package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.PresenceSpelling;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.Executability;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — <b>the flip writer</b> of
 * {@code plans/done/PLAN-rules-src-expression-flip.md} §5: rewrites each authored
 * {@code rules-src/checks/*.yaml} {@code Check:} block from the legacy leaf form to one canonical
 * expression string. Authored in wave 42 (the pre-flip prep wave); <b>the flip itself (§6) runs it
 * over the corpus in place on its own owner go</b> — until then every run writes to a directory
 * outside the repo.
 *
 * <p>
 * Per §5 the writer:
 * </p>
 * <ol>
 * <li>raises each authored {@code Check} through the production path — type-free since phase 1 of
 * {@code PLAN-leaf-scope-domain-inference.md}: every presence leaf spells its own fact
 * ({@code ds_exists} / {@code var_exists}), the generic {@code exists} being rejected at load;</li>
 * <li><b>canonicalises the negative presence spelling</b> ({@link PresenceSpelling}):
 * {@code ds_not_exists(X)} is written {@code not ds_exists(X)} — the invariant the retired
 * assembler lowering used to produce as a by-product ({@code rules/} carries zero negative-spelling
 * calls), now kept explicitly here and policed by the corpus drift test;</li>
 * <li>normalises via {@code print(parse(print(...)))} and verifies the result is a
 * {@code print ∘ parse} fixed point;</li>
 * <li>routes each Check through the production path
 * ({@link RulePackageConverter#convertCheck(JsonNode, RulePackageConverter.ConversionReport)}); the
 * curated-special registries retired with the conversion machinery
 * ({@code PLAN-retire-corpus-transforms.md} phase 1) — the formerly-curated texts are authored
 * directly and pinned by {@code RuleSrcExpressionWriterCorpusTest};</li>
 * <li>rewrites <b>only the {@code Check:} block</b> — every other byte of the file is preserved
 * exactly, so the flip's diff is reviewable as Check-block-only;</li>
 * <li>leaves every layout decision (scalar style, wrap column, indentation, quoting) to
 * {@link RuleCanonicalizer#renderExpressionCheckBlock}, the canonical-layout owner (D27).</li>
 * </ol>
 *
 * <p>
 * &#9733; <b>Level-keyed Checks (Plan C &#167;3.3).</b> The writer rewrites the {@code Check:}
 * block whichever of the two shapes it carries. A plain {@code Check:} takes the path above
 * unchanged — which is every one of the 3 804 shipped rules, so the flip's own image does not move.
 * A <b>level map</b> is raised, canonicalised and fixed-point-checked <em>per declared level</em>
 * and written by {@link RuleCanonicalizer#renderLevelCheckBlockOrNull}: one folded scalar per
 * level, ladder order, each level's optional {@code Message} passed through verbatim. Anything
 * other than the {@code Check:} block is still preserved byte for byte.
 * </p>
 *
 * <p>
 * Every per-file conversion is <b>self-verifying</b>: the rewritten file must parse as YAML, its
 * re-extracted expression must byte-equal the intended text, and its {@code Check} must bind to a
 * non-null {@link CheckCondition} through the {@code Rule} model's deserializer (the
 * {@code {expression}} branch). A file failing any check aborts the run — nothing is half written.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-rules
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.cdisc.core.expr.convert.RuleSrcExpressionWriter \
 *     -Dflip.outRoot=/abs/path/to/output/checks
 * }</pre>
 *
 * <p>
 * {@code -Dflip.outRoot} is deliberately required (no in-place default): the flip wave passes the
 * corpus directory itself; every other caller a temp directory.
 * </p>
 */
public final class RuleSrcExpressionWriter
{

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /** The authored form that parks a rule, taken from the enum (see RuleStorageAssembler). */
    private static final String NOT_EXECUTABLE = Executability.NOT_EXECUTABLE.getJsonValue();

    private RuleSrcExpressionWriter()
    {
    }

    /**
     * One converted file: identity, texts, and the per-rule flags the run report aggregates.
     *
     * @param id
     *            the rule's {@code Core.Id}
     * @param relativePath
     *            the file's path relative to the checks root
     * @param fileText
     *            the rewritten file
     * @param expression
     *            the <b>strictest declared level's</b> canonical expression — and, for every rule
     *            that authors a plain {@code Check:}, simply that rule's expression. Same meaning
     *            {@code Rule.getCheck()} carries (Plan C §3.3)
     * @param expressions
     *            <em>every</em> declared level's canonical expression, strictest first; a
     *            one-element list holding exactly {@link #expression()} for a plain {@code Check:}.
     *            ⚠ The read surface for any sweep that must see every level — the generic-presence
     *            residue count reads this, not {@link #expression()}
     * @param parked
     *            whether {@code Executability: "Not Executable"} parks the rule
     * @param divergentFromPlainRaise
     *            whether any level's canonical text differs from the plain raise
     */
    record Conversion(String id, Path relativePath, String fileText, String expression,
            List<String> expressions, boolean parked, boolean divergentFromPlainRaise)
    {
    }


    /** Per-run tallies and residual id lists — the §6 dry-run figures, measured not read. */
    record Result(int filesIn, int filesOut, List<String> unraisable, List<String> genericResidue,
            Set<String> parked, Set<String> divergentFromPlainRaise)
    {
    }

    /**
     * Converts the corpus. Configuration via system properties: {@code flip.checksRoot} (default
     * {@code rules-src/checks}) and {@code flip.outRoot} (required; equal to the checks root only
     * at the flip itself).
     *
     * @param args
     *            ignored (configuration is via system properties)
     * @throws IOException
     *             if a check file cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        Path checksRoot = Path.of(System.getProperty("flip.checksRoot", SRC_CHECKS.toString()));
        String outProperty = System.getProperty("flip.outRoot");
        if (outProperty == null || outProperty.isBlank())
        {
            throw new IllegalArgumentException("-Dflip.outRoot is required (the flip wave passes"
                    + " the corpus directory itself; every other caller a temp directory)");
        }
        Result r = run(checksRoot, Path.of(outProperty));
        System.out.printf(Locale.ROOT,
                "[flip-writer] %d in, %d out, %d unraisable %s, %d generic-presence residue %s,"
                        + " parked %s, divergent from the plain raise %s%n",
                r.filesIn(), r.filesOut(), r.unraisable().size(), r.unraisable(),
                r.genericResidue().size(), r.genericResidue(), r.parked(),
                r.divergentFromPlainRaise());
        if (!r.unraisable().isEmpty())
        {
            throw new IllegalStateException(r.unraisable().size() + " file(s) unraisable");
        }
    }


    /**
     * Converts every {@code *.yaml} under {@code checksRoot}, writing each rewritten file to the
     * same relative path under {@code outRoot} (which may equal {@code checksRoot} — the flip's
     * in-place mode). Deterministic: files are processed in sorted order.
     *
     * @param checksRoot
     *            the authored corpus root ({@code rules-src/checks} shaped)
     * @param outRoot
     *            where the rewritten files go
     * @return the run's tallies
     * @throws IOException
     *             if a file cannot be read or written
     */
    static Result run(Path checksRoot, Path outRoot) throws IOException
    {
        return run(checksRoot, outRoot, UnaryOperator.identity());
    }


    /**
     * {@link #run(Path, Path)} with a one-shot AST transform applied to every non-curated
     * expression between the parse and the canonical print — the hook a corpus <em>migration</em>
     * uses (e.g. {@link UniqueSetFlattener}, Plan A Phase 2). The transform runs after
     * {@link PresenceSpelling#canonical} and before the {@code print ∘ parse} fixed-point check, so
     * whatever it produces must print canonically. A curated special whose text the transform would
     * move is a stop-and-report finding (see {@link #convertFile}).
     *
     * @param transform
     *            the Expr → Expr rewrite; {@link UnaryOperator#identity()} for the plain writer
     */
    static Result run(Path checksRoot, Path outRoot, UnaryOperator<Expr> transform)
        throws IOException
    {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(checksRoot))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                    .forEach(files::add);
        }
        List<String> unraisable = new ArrayList<>();
        List<String> genericResidue = new ArrayList<>();
        Set<String> parked = new TreeSet<>();
        Set<String> divergentFromPlainRaise = new TreeSet<>();
        int filesOut = 0;
        for (Path file : files)
        {
            Conversion c;
            try
            {
                c = convertFile(checksRoot.relativize(file),
                        Files.readString(file, StandardCharsets.UTF_8), transform);
            }
            catch (Unraisable u)
            {
                unraisable.add(u.getMessage());
                continue;
            }
            if (c.parked())
            {
                parked.add(c.id());
            }
            if (c.divergentFromPlainRaise())
            {
                divergentFromPlainRaise.add(c.id());
            }
            // ⚑ EVERY level (Plan C §3.3): a generic `exists` sitting in a weaker level is
            // exactly as much of a §5 residue as one in the strictest, and reading expression()
            // alone would count it as clean.
            if (c.expressions().stream().map(CheckExpressionParser::parse)
                    .anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall))
            {
                genericResidue.add(c.id());
            }
            Path target = outRoot.resolve(c.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, c.fileText(), StandardCharsets.UTF_8);
            filesOut++;
        }
        return new Result(files.size(), filesOut, unraisable, genericResidue, parked,
                divergentFromPlainRaise);
    }

    /** A per-file conversion failure; the id-tagged message joins the run's unraisable list. */
    static final class Unraisable extends RuntimeException
    {

        private static final long serialVersionUID = 5754206315471852809L;

        Unraisable(String message)
        {
            super(message);
        }
    }

    /**
     * Converts one authored check file: the §5 raise, the D27 layout, the byte surgery, and the
     * self-verification — everything except the actual write.
     *
     * @param relativePath
     *            the file's path relative to the checks root (carried into the result)
     * @param fileText
     *            the authored file, verbatim
     * @return the conversion, with {@code fileText()} holding the rewritten file
     * @throws Unraisable
     *             when the Check has no full expression surface (never expected — P4b measured 3
     *             781/3 781)
     */
    static Conversion convertFile(Path relativePath, String fileText)
    {
        return convertFile(relativePath, fileText, UnaryOperator.identity());
    }


    /**
     * {@link #convertFile(Path, String)} with a migration transform (see
     * {@link #run(Path, Path, UnaryOperator)}).
     */
    static Conversion convertFile(Path relativePath, String fileText, UnaryOperator<Expr> transform)
    {
        JsonNode root;
        try
        {
            root = YAML.readTree(fileText);
        }
        catch (JsonProcessingException e)
        {
            throw new Unraisable(relativePath + ": not parseable as YAML: " + e.getMessage());
        }
        String id = idOf(root, relativePath);
        JsonNode checkNode = root.get("Check");
        if (checkNode == null || checkNode.isNull())
        {
            throw new Unraisable(id + ": no Check block");
        }
        boolean parked = NOT_EXECUTABLE.equals(textOf(root.get("Executability")));

        SequencedMap<String, JsonNode> levels = RuleFingerprint.levels(checkNode);
        if (levels != null)
        {
            return convertLevelled(relativePath, fileText, id, checkNode, levels, transform,
                    parked);
        }

        // The production path — the same carry-through RuleExprGenerator drives when rules/ is
        // built (an expression Check is carried verbatim; anything else does not raise).
        JsonNode converted = RulePackageConverter.convertCheck(checkNode,
                new RulePackageConverter.ConversionReport());
        if (!(converted != null && converted.isObject() && converted.size() == 1
                && converted.hasNonNull("expression")))
        {
            throw new Unraisable(id + ": Check did not raise to a single expression: " + converted);
        }
        String expression = canonicalise(converted.get("expression").asText(), transform);
        if (!expression.equals(reprint(expression)))
        {
            throw new IllegalStateException(
                    id + ": normalised expression is not a print∘parse fixed point: <" + expression
                            + ">");
        }

        // The plain (special-free) toExpr raise, so a curated special that drifts from what the
        // production raise would produce is counted (specials do not participate).
        CheckCondition check;
        try
        {
            check = YAML.treeToValue(checkNode, CheckCondition.class);
        }
        catch (JsonProcessingException e)
        {
            throw new Unraisable(id + ": Check did not bind: " + e.getMessage());
        }
        String plain = plainRaiseText(check);

        String rewritten = spliceCheckBlock(fileText,
                RuleCanonicalizer.renderExpressionCheckBlock(expression), id);
        verifyRewrittenFile(rewritten, expression, id);
        return new Conversion(id, relativePath, rewritten, expression, List.of(expression), parked,
                plain != null && !plain.equals(expression));
    }


    /**
     * {@link #convertFile}'s <b>level-keyed</b> arm (Plan C &#167;3.3): every declared level is
     * raised, canonicalised and fixed-point-checked exactly as a plain {@code Check:} is, and the
     * block is written by {@link RuleCanonicalizer#renderLevelCheckBlockOrNull} — the same
     * canonical-layout owner (D27), one folded scalar per level, ladder order.
     *
     * <p>
     * &#9873; A level's {@code Message} passes through untouched. It is not part of the condition
     * grammar, so it is lifted off before the raise and re-attached to the rendered node; the
     * rule's own {@code Outcome.Message} is never copied into a level (&#167;3.6).
     * </p>
     *
     */
    private static Conversion convertLevelled(Path relativePath, String fileText, String id,
            JsonNode checkNode, SequencedMap<String, JsonNode> levels,
            UnaryOperator<Expr> transform, boolean parked)
    {
        ObjectNode rendered = YAML.createObjectNode();
        List<String> expressions = new ArrayList<>();
        boolean divergent = false;
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            String where = id + " (level " + level.getKey() + ")";
            JsonNode converted = RulePackageConverter.convertCheck(level.getValue(),
                    new RulePackageConverter.ConversionReport());
            if (!(converted != null && converted.isObject() && converted.size() == 1
                    && converted.hasNonNull("expression")))
            {
                throw new Unraisable(
                        where + ": Check did not raise to a single expression: " + converted);
            }
            String expression = canonicalise(converted.get("expression").asText(), transform);
            if (!expression.equals(reprint(expression)))
            {
                throw new IllegalStateException(where + ": normalised expression is not a"
                        + " print∘parse fixed point: <" + expression + ">");
            }
            expressions.add(expression);
            CheckCondition bound;
            try
            {
                bound = YAML.treeToValue(level.getValue(), CheckCondition.class);
            }
            catch (JsonProcessingException e)
            {
                throw new Unraisable(where + ": Check did not bind: " + e.getMessage());
            }
            String plain = plainRaiseText(bound);
            divergent |= plain != null && !plain.equals(expression);

            ObjectNode out = rendered.putObject(level.getKey());
            out.put("expression", expression);
            JsonNode message = checkNode.get(level.getKey()).get(RuleFingerprint.MESSAGE_KEY);
            if (message != null && !message.isNull())
            {
                out.set(RuleFingerprint.MESSAGE_KEY, message);
            }
        }
        String block = RuleCanonicalizer.renderLevelCheckBlockOrNull(rendered);
        if (block == null)
        {
            throw new IllegalStateException(id + ": rendered level map has no canonical layout");
        }
        String rewritten = spliceCheckBlock(fileText, block, id);
        verifyRewrittenLevels(rewritten, levels.keySet(), expressions, id);
        return new Conversion(id, relativePath, rewritten, expressions.get(0),
                List.copyOf(expressions), parked, divergent);
    }


    /**
     * Replaces the file's single top-level {@code Check:} block with {@code renderedBlock},
     * preserving every byte before and after it. The block starts at the (unique) column-0
     * {@code Check:} line and ends at the next column-0 key line — measured corpus-wide: always
     * {@code Outcome:}, but any top-level key (or EOF) terminates it.
     */
    static String spliceCheckBlock(String fileText, String renderedBlock, String id)
    {
        int start;
        if (fileText.startsWith("Check:\n"))
        {
            start = 0;
        }
        else
        {
            start = fileText.indexOf("\nCheck:\n");
            if (start < 0)
            {
                throw new IllegalStateException(id + ": no top-level Check: block found");
            }
            start++;
        }
        if (fileText.indexOf("\nCheck:\n", start) >= 0)
        {
            throw new IllegalStateException(id + ": multiple top-level Check: keys");
        }
        int contentStart = start + "Check:\n".length();
        int end = fileText.length();
        int p = contentStart;
        while (p < fileText.length())
        {
            char c = fileText.charAt(p);
            if (Character.isLetter(c) || c == '_')
            {
                end = p;
                break;
            }
            int nl = fileText.indexOf('\n', p);
            p = nl < 0 ? fileText.length() : nl + 1;
        }
        return fileText.substring(0, start) + renderedBlock + fileText.substring(end);
    }


    /**
     * The per-file hard gate: the rewritten file parses as YAML, its {@code Check.expression}
     * byte-equals the intended text (so the D27 layout provably carries it), and the block binds to
     * a non-null {@link CheckCondition} through the {@code Rule}-model deserializer.
     */
    static void verifyRewrittenFile(String rewritten, String expression, String id)
    {
        JsonNode root;
        try
        {
            root = YAML.readTree(rewritten);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(id + ": rewritten file is not valid YAML", e);
        }
        String extracted = root.path("Check").path("expression").asText();
        if (!expression.equals(extracted))
        {
            throw new IllegalStateException(id + ": rewritten Check does not carry the intended"
                    + " expression: intended=<" + expression + "> carried=<" + extracted + ">");
        }
        CheckCondition bound;
        try
        {
            bound = YAML.treeToValue(root.get("Check"), CheckCondition.class);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(id + ": rewritten Check does not bind", e);
        }
        if (bound == null)
        {
            throw new IllegalStateException(id + ": rewritten Check bound to null");
        }
    }


    /**
     * The level-keyed counterpart of {@link #verifyRewrittenFile}: the rewritten file parses, its
     * {@code Check} still declares exactly these levels in this order, and the text carried under
     * each level byte-equals the one intended for it.
     *
     * @param rewritten
     *            the rewritten file text
     * @param levels
     *            the declared level names, strictest first
     * @param expressions
     *            the intended canonical text per level, in the same order
     * @param id
     *            the rule id, for the messages
     */
    static void verifyRewrittenLevels(String rewritten, Set<String> levels,
            List<String> expressions, String id)
    {
        JsonNode root;
        try
        {
            root = YAML.readTree(rewritten);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(id + ": rewritten file is not valid YAML", e);
        }
        SequencedMap<String, JsonNode> back = RuleFingerprint.levels(root.get("Check"));
        if (back == null || !List.copyOf(levels).equals(List.copyOf(back.keySet())))
        {
            throw new IllegalStateException(
                    id + ": rewritten Check does not declare the intended" + " levels " + levels
                            + " (got " + (back == null ? "a plain Check" : back.keySet()) + ")");
        }
        int i = 0;
        for (JsonNode condition : back.values())
        {
            String intended = expressions.get(i++);
            if (!intended.equals(condition.path("expression").asText()))
            {
                throw new IllegalStateException(id + ": rewritten level does not carry the intended"
                        + " expression: intended=<" + intended + "> carried=<"
                        + condition.path("expression").asText() + ">");
            }
        }
    }


    /**
     * Whether the raised tree still carries a <em>generic</em> {@code exists}/{@code not_exists}
     * call — the §5 guarantee that a concrete-type raise leaves none (matching the shipped corpus's
     * measured zero). Structural walk, {@code LitKind.LIST} items included (mirroring the retired
     * {@code RuleTypeAwareIdempotenceTest}'s sweep, whose corpus-wide job this writer's own corpus
     * test carries since the flip).
     */
    static boolean carriesGenericPresenceCall(Expr node)
    {
        return switch (node)
        {
        case Expr.Call c -> "exists".equals(c.name()) || "not_exists".equals(c.name())
                || c.args().stream().anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall)
                || c.kwargs().values().stream()
                        .anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall);
        case Expr.And a -> a.parts().stream()
                .anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall);
        case Expr.Or o -> o.parts().stream()
                .anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall);
        case Expr.Not n -> carriesGenericPresenceCall(n.inner());
        case Expr.Binary b -> carriesGenericPresenceCall(b.left())
                || carriesGenericPresenceCall(b.right());
        case Expr.Lit(Expr.LitKind kind, Object value) when kind == Expr.LitKind.LIST ->
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) value;
            yield items.stream().anyMatch(RuleSrcExpressionWriter::carriesGenericPresenceCall);
        }
        case Expr.Lit _,Expr.Ref _ -> false;
        };
    }


    /**
     * The plain (special-free) raise's canonical text, or {@code null} when it throws — so a rule
     * the raise rejects counts as divergent instead of erroring the sweep.
     */
    private static @Nullable String plainRaiseText(CheckCondition check)
    {
        try
        {
            return ExpressionPrinter.print(PresenceSpelling.canonical(CheckToExpr.toExpr(check)));
        }
        catch (ExpressionException _)
        {
            return null;
        }
    }


    private static String reprint(String text)
    {
        return ExpressionPrinter.print(CheckExpressionParser.parse(text));
    }


    /**
     * {@link #reprint} after the {@link PresenceSpelling} rewrite and the caller's migration
     * transform — the canonical text.
     */
    private static String canonicalise(String text, UnaryOperator<Expr> transform)
    {
        return ExpressionPrinter.print(
                transform.apply(PresenceSpelling.canonical(CheckExpressionParser.parse(text))));
    }


    private static @Nullable String textOf(@Nullable JsonNode node)
    {
        return node == null || node.isNull() ? null : node.asText();
    }


    private static String idOf(JsonNode root, Path fallback)
    {
        JsonNode core = root.get("Core");
        if (core != null && core.hasNonNull("Id"))
        {
            return core.get("Id").asText();
        }
        return fallback.getFileName().toString();
    }
}
