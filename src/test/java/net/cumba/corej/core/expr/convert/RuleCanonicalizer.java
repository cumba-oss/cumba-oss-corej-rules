package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Offline developer tool — stage 3 of the internal {@code backfill-rules} workflow (an internal
 * developer pipeline that is not distributed with this repository): normalises every
 * {@code rules-src/checks/*.yaml} to a defined top-level field order and a single canonical YAML
 * style (plan {@code plans/done/PLAN-source-block-migration.md}). It is schema-aware of the
 * post-backfill layout: {@code Source} is second-to-last (immediately before {@code Standards}, as
 * {@link OriginalTextBackfill} splices it), the legacy {@code Original_*} keys are gone, and the
 * cross-family linkage lives as {@code References[]} inside {@code Standards} (written by
 * {@link StandardsLinkageBackfill}).
 *
 * <p>
 * Per check file it (1) parses the YAML to a tree — dropping comments, which is the agreed
 * behaviour since the rationale lives in the {@code documentation/} prose; (2) reorders the
 * <strong>top-level</strong> keys into {@link #TOP_ORDER} (any unknown key is placed just before
 * {@code Source}, sorted among the other unknowns, and a drift warning is printed naming it; a
 * straggler {@code Original_*} key is dropped with a warning); (3) preserves every nested object's
 * key order and <em>all</em> array element order exactly; (4) re-emits with the same Jackson YAML
 * writer as {@code RuleStorageSplitter} (deleted in wave 33) /
 * {@link OriginalTextBackfill#YAML_WRITE} (double-quoted, folded, no doc-start marker) and a single
 * trailing newline, which recursively normalises quoting and line breaks.
 * </p>
 *
 * <p>
 * <strong>Round-trip data-safety (hard gate).</strong> Before writing, the parsed original (minus
 * any intentionally-dropped straggler) and the parsed canonical output are deep-compared with all
 * object keys recursively sorted; they must be data-equal. A file that fails is never written and
 * is reported; the run ends non-zero. This guarantees canonicalisation is semantically inert.
 * </p>
 *
 * <p>
 * Default mode rewrites in place. {@code -Dcanonicalize.check=true} (or the shared
 * {@code -Dbackfill.dryRun=true}) is report-only and, under {@code canonicalize.check}, fails the
 * build (a Spotless-style CI gate). The rewrite is idempotent — a second run makes no change.
 * </p>
 *
 * <p>
 * ⚠ <b>What check mode reports is a COUNT, not a list.</b> {@link #main} prints
 * {@code non-canonical=N}; the per-file names it does print are the {@code warnings} (an unknown
 * top-level key, a dropped straggler, a stripped {@code Severity: "Error"}) and the round-trip
 * failures, which are a different thing entirely. To find <em>which</em> files are non-canonical,
 * run the rewrite against a copy and diff it — e.g. {@code git archive HEAD | tar -x} into a temp
 * tree, point {@code -Dbackfill.checksRoot} at its {@code rules-src/checks}, and diff.
 * </p>
 *
 * <p>
 * &#9888;&#9888; <b>The baseline is now ZERO, and the "98" figure below it is retired.</b> Measured
 * 2026-08-25 at {@code d1d21ab08}: <b>{@code files=3804 non-canonical=0}</b> — the whole corpus was
 * canonicalised on the owner's direction, so an acceptance phrased as <em>"expect 0
 * non-canonical"</em> is now <b>correct</b> rather than wrong. <b>Re-measured 2026-08-26</b> at
 * {@code d1d21ab08} <em>plus Plan C phase 5b's nine uncommitted {@code rules-src} files</em> (the
 * level-keyed {@code Check} rewrites): <b>{@code files=3804 non-canonical=0 comments-stripped=8
 * round-trip-failures=0}</b> — so the baseline survives 5b, and the level-map layout below
 * ({@link #renderLevelCheckBlockOrNull}) is what keeps those nine canonical.
 * </p>
 *
 * <p>
 * &#9873; <b>The retired figure, kept because it is easy to re-derive wrongly.</b> This javadoc
 * read <em>"98 corpus files are already non-canonical at pristine HEAD"</em> — true at
 * {@code 793963dec}, and <b>stale twice over</b> by the time it was corrected. Plan C phases 1-3
 * reworded 233 {@code Description} openers without re-wrapping them to the canonical fold width,
 * taking the count <b>98 &#8594; 131</b>; canonicalising all 131 then took it to <b>0</b>. ⇒ A
 * non-canonical count is a property of <em>a commit</em>, never of the tool. State the hash.
 * </p>
 *
 * <p>
 * &#9888; <b>Run it from the repository root, or with an absolute root.</b> {@code SRC_CHECKS} is a
 * <b>relative</b> path: invoked from the wrong directory {@link #main} walks nothing and prints
 * {@code files=0 non-canonical=0}, which reads exactly like a clean gate and is a <b>void run</b>.
 * Always pass {@code -Dbackfill.checksRoot=<absolute>/lib/corej-cdisc-rules/rules-src/checks} and
 * <b>assert {@code files=3804}</b> before believing the count.
 * </p>
 *
 * <p>
 * &#9888; <b>{@code comments-stripped=8} is a FALSE-POSITIVE detection counter, not an action.</b>
 * The corpus contains <b>zero</b> YAML comments; all 8 hits are a {@code #} inside a <em>quoted
 * string value</em> (sheet-issue references such as {@code #127}, {@code SDTM IG #4.1.5.3.2},
 * {@code naming conventions #10}). Verified 2026-08-25: 4 of the 8 are re-wrapped and 4 untouched,
 * and <b>all 8 keep their values byte-intact</b>. Nothing is lost — but the counter misreports.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-rules
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.1:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.corej.core.expr.convert.RuleCanonicalizer
 * }</pre>
 */
public final class RuleCanonicalizer
{

    static final ObjectMapper YAML_READ = new ObjectMapper(new YAMLFactory());

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    /**
     * The canonical top-level key order; {@code Source} is second-to-last, before
     * {@code Standards}.
     *
     * <p>
     * {@code Grouping_Variables} and {@code Grouping} sit adjacent and in that order because they
     * are the flat and block spellings of one field ({@code Fix #180}); a rule declares exactly one
     * of them (declaring both is a load error), so the pair never both appear in a file. Listing
     * {@code Grouping} here is what keeps the block in the position the flat key used to hold —
     * without it the block is an <em>unknown</em> key and every rule carrying one is relocated to
     * just before {@code Source} with a drift warning. {@code Variable_Universe} (the VAR-cursor
     * universe, {@code PLAN-leaf-scope-domain-inference.md} §3.7) follows it as the third
     * cursor-configuring sibling.
     * </p>
     *
     * <p>
     * {@code Requirements} sits immediately after {@code Scope} because the two are read together
     * and in that order — {@code Scope} says which datasets the rule is <em>about</em>,
     * {@code Requirements} what it needs in order to answer at all
     * ({@code plans/done/PLAN-scope-requirements-split.md} §4.1). Omitting it here would not fail
     * any gate: an unlisted key is merely relocated to just before {@code Source} with a warning,
     * at the next {@code backfill-rules} run and nowhere else — which is why
     * {@code CanonicalTopOrderCoverageTest} asserts {@code TOP_ORDER} covers every key the corpus
     * actually carries.
     * </p>
     */
    static final List<String> TOP_ORDER = List.of("Core", "Description", "Severity", "Sensitivity",
            "Executability", "ExecutabilityHint", "Scope", "Requirements", "Match_Datasets",
            "Grouping_Variables", "Grouping", "Variable_Universe", "Operations", "Check", "Outcome",
            // Board finding L4 (filed 2026-08-24, landed 2026-08-25): "Expansion" was modelled
            // (Rule.getExpansion()) and authored by two rules, but absent here — so every run
            // relocated it to the unknown-key slot just before "Source" and warned. It heads the
            // expansion-directive group because the wildcard keys below are its modifiers.
            // ⚑ Slot chosen to be a NO-OP on the corpus: both carriers (CDISC-AD0591, AD0898)
            // carry none of the four wildcard keys, so "after Outcome, before Source" is exactly
            // where the unknown-key relocation already put it.
            "Expansion", "wildcards", "wildcardExclude", "wildcardPairCatalogue",
            // "Source_Proposed" — the row we ask the authority to publish, immediately BEFORE the
            // verbatim "Source" it proposes to replace, so the two read as original-then-proposal
            // (plans/done/PLAN-source-proposed-block.md §2.1, owner ruling 1 of 2026-08-23).
            // ⚑ Deliberately a NO-OP on any file seeded before this entry existed: an unlisted key
            // is relocated to the unknown-key slot, which the loop below emits when it reaches
            // "Source" — i.e. the same position. That equivalence holds while the corpus carries no
            // OTHER unknown top-level key, which CanonicalTopOrderCoverageTest asserts outright.
            "skipIfLibraryDefined", "Source_Proposed", "Source", "Standards");

    static final Set<String> KNOWN = Set.copyOf(TOP_ORDER);

    /** Legacy provenance keys that must not survive; dropped with a warning if seen. */
    static final Set<String> DROP = Set.of("Original_Description", "Original_Condition",
            "Original_Message");

    private RuleCanonicalizer()
    {
    }

    /** Per-run tallies and residual lists. */
    record Result(int files, int changed, int comments, List<String> failures,
            Set<String> unknownKeys)
    {
    }

    /**
     * Canonicalises the corpus in place (or reports, in check mode).
     *
     * @param args
     *            ignored (configuration is via system properties)
     * @throws IOException
     *             if a check file cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        Path checksRoot = Path.of(System.getProperty("backfill.checksRoot", SRC_CHECKS.toString()));
        boolean gate = Boolean.getBoolean("canonicalize.check");
        boolean checkMode = gate || Boolean.getBoolean("backfill.dryRun");

        Result r = run(checksRoot, checkMode);
        System.out.printf(Locale.ROOT,
                "[canonicalize] files=%d %s=%d comments-stripped=%d round-trip-failures=%d%s%n",
                r.files(), checkMode ? "non-canonical" : "changed", r.changed(), r.comments(),
                r.failures().size(), checkMode ? " (check)" : "");
        if (!r.unknownKeys().isEmpty())
        {
            System.out.printf(Locale.ROOT, "[canonicalize] WARNING unknown top-level key(s): %s%n",
                    new TreeSet<>(r.unknownKeys()));
        }
        for (String f : r.failures())
        {
            System.out.printf(Locale.ROOT, "[canonicalize] ROUND-TRIP FAILURE: %s%n", f);
        }
        if (!r.failures().isEmpty())
        {
            throw new IllegalStateException(
                    "round-trip data-safety check failed for " + r.failures().size() + " file(s)");
        }
        if (gate && r.changed() > 0)
        {
            throw new IllegalStateException(r.changed()
                    + " file(s) are not canonical; run RuleCanonicalizer without -Dcanonicalize.check to fix");
        }
    }


    /** Walks {@code checksRoot}, canonicalising (or checking) every {@code *.yaml}. */
    static Result run(Path checksRoot, boolean checkMode) throws IOException
    {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(checksRoot))
        {
            try (Stream<Path> stream = Files.walk(checksRoot))
            {
                stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted()
                        .forEach(files::add);
            }
        }
        int changed = 0;
        int comments = 0;
        List<String> failures = new ArrayList<>();
        Set<String> unknownKeys = new LinkedHashSet<>();
        for (Path file : files)
        {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (hasComment(text))
            {
                comments++;
                // ⚠⚠ NAMED, never only counted. The rewrite below deletes a YAML comment without
                // a word, and on 2026-09-11 CDISC-CG0155's five-line rationale went exactly that
                // way — hidden inside a count of 9 whose other 8 hits were a '#' inside a quoted
                // value and lost nothing. A count cannot tell those apart; a name can, because
                // the caller can diff that one file.
                System.out.printf(Locale.ROOT,
                        "[canonicalize] %s: '#' outside quotes — VERIFY with a diff before"
                                + " rewriting; a real comment is deleted without further notice%n",
                        name(file));
            }
            List<String> warnings = new ArrayList<>();
            String canonical;
            try
            {
                canonical = canonicalize(text, warnings, unknownKeys);
            }
            catch (RoundTripException e)
            {
                failures.add(name(file));
                continue;
            }
            for (String w : warnings)
            {
                System.out.printf(Locale.ROOT, "[canonicalize] %s: %s%n", name(file), w);
            }
            if (!canonical.equals(text))
            {
                changed++;
                if (!checkMode)
                {
                    Files.writeString(file, canonical, StandardCharsets.UTF_8);
                }
            }
        }
        return new Result(files.size(), changed, comments, failures, unknownKeys);
    }

    /** Thrown when the canonical output is not data-equal to the (drop-adjusted) original. */
    private static final class RoundTripException extends RuntimeException
    {

        private static final long serialVersionUID = -2272153512520922839L;

        RoundTripException()
        {
            super();
        }
    }

    /**
     * Returns the canonical YAML for {@code text}: top-level keys reordered per {@link #TOP_ORDER},
     * stragglers dropped, re-emitted with the splitter writer and a single trailing newline. Adds
     * human-readable notes to {@code warnings} and any unknown top-level key names to
     * {@code unknownKeys}. Throws {@link RoundTripException} if the output is not data-equal to the
     * drop-adjusted input (in which case the caller must not write it).
     */
    static String canonicalize(String text, List<String> warnings, Set<String> unknownKeys)
    {
        JsonNode root;
        try
        {
            root = YAML_READ.readTree(text);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        if (root == null || !root.isObject())
        {
            // Not a mapping (should never happen for a check); leave byte-for-byte.
            return text;
        }
        ObjectNode work = ((ObjectNode) root).deepCopy();

        // Drop straggler legacy keys (top-level and Outcome.Original_Message).
        for (String key : DROP)
        {
            if (work.has(key))
            {
                work.remove(key);
                warnings.add("dropped straggler legacy key '" + key + "'");
            }
        }
        JsonNode outcome = work.get("Outcome");
        if (outcome instanceof ObjectNode on && on.has("Original_Message"))
        {
            on.remove("Original_Message");
            warnings.add("dropped straggler legacy key 'Outcome.Original_Message'");
        }

        // Plan C §3.1 obligation 3 — STRIP a redundant `Severity: "Error"`.
        //
        // ⚑ Strip, do NOT reject. An authored `Severity: "Error"` is valid and loads; it is merely
        // non-canonical, exactly as a mis-ordered key is. Rejecting it would make the field's
        // default asymmetric with Sensitivity, which the corpus does carry explicitly in places.
        //
        // ⚠⚠ This is the LOAD-BEARING one of the three obligations. The loader defaulting an
        // absent field and the writer never emitting one still leave the redundant key reachable
        // through the rule-editor REST round-trip — and RuleSrcExpressionWriterCorpusTest's
        // byte-exactness would then be enforcing two different spellings of the same rule.
        // Case-insensitive, because the field is parsed case-insensitively.
        JsonNode severity = work.get("Severity");
        if (severity != null && severity.isTextual()
                && "error".equalsIgnoreCase(severity.asText().strip()))
        {
            work.remove("Severity");
            warnings.add("stripped redundant Severity: \"Error\" (absent means Error)");
        }

        // Order each Operations item: id, operator, then the remaining keys alphabetically. The
        // array element order (and any inner array values) are preserved.
        if (work.get("Operations") instanceof ArrayNode ops)
        {
            work.set("Operations", orderOperations(ops));
        }

        List<String> unknown = new ArrayList<>();
        work.fieldNames().forEachRemaining(k ->
        {
            if (!KNOWN.contains(k))
            {
                unknown.add(k);
            }
        });
        Collections.sort(unknown);
        for (String u : unknown)
        {
            unknownKeys.add(u);
            warnings.add("unknown top-level key '" + u + "' (placed before Source)");
        }

        ObjectNode out = YAML_READ.createObjectNode();
        for (String key : TOP_ORDER)
        {
            if (key.equals("Source"))
            {
                for (String u : unknown)
                {
                    out.set(u, work.get(u));
                }
            }
            if (work.has(key))
            {
                out.set(key, work.get(key));
            }
        }

        String yaml;
        try
        {
            yaml = OriginalTextBackfill.YAML_WRITE.writeValueAsString(out);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        String canonical = yaml.stripTrailing() + "\n";

        // D27: an expression-form Check owns its layout (renderExpressionCheckBlock). Jackson's
        // writer would re-emit it double-quoted at SnakeYAML's wrap width, undoing the folded
        // block the flip wrote — splice the canonical block back in over Jackson's rendering.
        // Plan C §3.3: a LEVEL-KEYED Check owns its layout the same way, one folded scalar per
        // declared level (renderLevelCheckBlockOrNull). Both branches are line-level splices over
        // the same column-0 boundary.
        JsonNode check = out.get("Check");
        String levelled = renderLevelCheckBlockOrNull(check);
        if (levelled != null)
        {
            canonical = spliceExpressionCheckBlock(canonical, levelled);
        }
        else if (check != null && check.isObject() && check.size() == 1
                && check.hasNonNull("expression") && check.get("expression").isTextual())
        {
            canonical = spliceExpressionCheckBlock(canonical,
                    renderExpressionCheckBlock(check.get("expression").asText()));
        }

        // Round-trip data-safety: the re-parsed canonical output must be data-equal to the
        // drop-adjusted original (key order and formatting excluded; comments are not data).
        JsonNode reparsed;
        try
        {
            reparsed = YAML_READ.readTree(canonical);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        if (!dataEqual(work, reparsed))
        {
            throw new RoundTripException();
        }
        return canonical;
    }


    /**
     * A copy of the {@code Operations} array with each item object's keys reordered to {@code id},
     * {@code operator}, then the remaining keys alphabetically. Element order and non-object
     * elements are preserved; inner values (e.g. {@code codelists} lists) are untouched.
     */
    private static ArrayNode orderOperations(ArrayNode ops)
    {
        ArrayNode out = YAML_READ.createArrayNode();
        for (JsonNode item : ops)
        {
            if (!(item instanceof ObjectNode op))
            {
                out.add(item);
                continue;
            }
            ObjectNode ordered = YAML_READ.createObjectNode();
            if (op.has("id"))
            {
                ordered.set("id", op.get("id"));
            }
            if (op.has("operator"))
            {
                ordered.set("operator", op.get("operator"));
            }
            List<String> rest = new ArrayList<>();
            op.fieldNames().forEachRemaining(k ->
            {
                if (!k.equals("id") && !k.equals("operator"))
                {
                    rest.add(k);
                }
            });
            Collections.sort(rest);
            for (String k : rest)
            {
                ordered.set(k, op.get(k));
            }
            out.add(ordered);
        }
        return out;
    }


    /**
     * Replaces the top-level {@code Check:} block of {@code fileText} (as Jackson emitted it) with
     * {@code renderedBlock} — a full {@code Check:} block from either
     * {@link #renderExpressionCheckBlock} (a plain {@code Check:}) or
     * {@link #renderLevelCheckBlockOrNull} (a level-keyed one). The block boundary is the
     * line-level one the flip writer used: from the column-0 {@code Check:} line to the next
     * column-0 key.
     *
     * @param fileText
     *            the whole-file canonical text
     * @param renderedBlock
     *            the D27 {@code Check:} block, with its one trailing newline
     * @return the file text with the block spliced in
     */
    private static String spliceExpressionCheckBlock(String fileText, String renderedBlock)
    {
        List<String> out = new ArrayList<>();
        boolean inCheck = false;
        boolean spliced = false;
        for (String line : fileText.split("\n", -1))
        {
            if (line.equals("Check:"))
            {
                if (spliced)
                {
                    throw new IllegalStateException("two top-level Check blocks");
                }
                inCheck = true;
                spliced = true;
                out.add(renderedBlock.stripTrailing());
                continue;
            }
            if (inCheck && !line.isEmpty() && !line.startsWith(" "))
            {
                inCheck = false;
            }
            if (!inCheck)
            {
                out.add(line);
            }
        }
        if (!spliced)
        {
            throw new IllegalStateException("no top-level Check block to splice");
        }
        String result = String.join("\n", out);
        // A Check block at end-of-file consumes the trailing empty split element with it;
        // re-assert the file's single trailing newline (unreachable today — Outcome: always
        // follows Check in TOP_ORDER — but cheap to keep true by construction).
        return result.endsWith("\n") ? result : result + "\n";
    }

    // ---- D27: expression-form Check block layout --------------------------

    /**
     * D27 wrap column for the expression-form {@code Check} block: a rendered line (indent
     * included) never exceeds this width when a token boundary allows a break. Matches the
     * project's Java line width ({@code eclipse-formatter.xml} {@code lineSplit=100}); the corpus's
     * Jackson-emitted strings wrap at SnakeYAML's ~80, but expressions are code and read best at
     * the code width. ⚠ Owner ruling D27 decides this value and the scalar style below — see
     * {@code WAVE42-C-D27-SAMPLE.md} (wave 42 lane C) for the sample and the alternatives.
     */
    static final int EXPRESSION_WRAP_COLUMN = 100;

    /**
     * Continuation indent of the folded scalar's content lines, <b>relative to the
     * {@code expression:} key</b> — it must be non-empty, or the folded scalar would not be a child
     * of its key. At the top level ({@code Check: / expression:}, key indent 2) that is the
     * absolute 4 spaces the flip wrote; inside a level map (key indent 4) it is 6.
     */
    private static final String EXPRESSION_LINE_INDENT = "  ";

    /**
     * Renders the canonical layout of an expression-form {@code Check:} block — the D27 decision
     * point, owned here because this class owns the corpus's canonical YAML layout. The proposed
     * layout is a <b>folded block scalar</b>:
     *
     * <pre>{@code
     * Check:
     *   expression: >-
     *     TSPARMCD == "RANDQT" and not empty(TSVAL) and not (is_numeric(TSVAL) and
     *     between(TSVAL, 0, 1))
     * }</pre>
     *
     * <p>
     * Why folded ({@code >-}), not double-quoted or literal: the expression text is carried
     * <em>verbatim</em> (no {@code \"} escapes, so the source greps exactly like the expression
     * language), wrapped lines re-fold to the exact single-line canonical text (each break replaces
     * one token-boundary space, and YAML folding restores it), and the loaded string therefore
     * stays a {@code print ∘ parse} fixed point — which a wrapped literal scalar ({@code |-})
     * breaks, because its embedded newlines enter the value. The method self-verifies: the rendered
     * block is re-parsed and must reconstruct {@code expression} exactly, so a wrap that YAML
     * folding would not undo can never be written.
     * </p>
     *
     * @param expression
     *            the canonical single-line expression text (non-blank, no newlines/tabs, no leading
     *            or trailing whitespace)
     * @return the full {@code Check:} block, ending in exactly one trailing newline
     * @throws IllegalArgumentException
     *             if {@code expression} cannot be carried by this layout
     * @throws IllegalStateException
     *             if the rendered block does not re-parse to {@code expression} (never expected;
     *             the self-check exists so a wrapping bug fails loudly instead of shipping)
     */
    static String renderExpressionCheckBlock(String expression)
    {
        String rendered = "Check:\n" + renderExpressionScalar(expression, 2);
        JsonNode reparsed = reparseBlock(rendered);
        if (!expression.equals(reparsed.path("Check").path("expression").asText()))
        {
            throw new IllegalStateException(
                    "folded layout did not reconstruct the expression: <" + expression + ">");
        }
        return rendered;
    }


    /**
     * The canonical layout of a <b>level-keyed</b> {@code Check:} block (Plan C &#167;3.3) — or
     * {@code null} when {@code check} is not a level map every one of whose levels is
     * expression-form, in which case the caller leaves Jackson's rendering alone.
     *
     * <pre>{@code
     * Check:
     *   ERROR:
     *     expression: >-
     *       date(RFSTDTC) > RFENDTC
     *   INFO:
     *     expression: >-
     *       date(latest_possible(RFSTDTC)) > earliest_possible(RFENDTC)
     *     Message: "start may be after end"
     * }</pre>
     *
     * <p>
     * ⭐ Levels are emitted <b>strictest first</b> ({@code REJECT} &rarr; {@code ERROR} &rarr;
     * {@code WARNING} &rarr; {@code INFO}), never in authored order: the ladder <em>is</em> the
     * evaluation order (&#167;3.4 step 3), so a canonical file that showed a different order would
     * be showing something untrue. Note this is the one place canonicalisation reorders keys inside
     * a nested object — everywhere else nested key order is preserved verbatim — and it is safe for
     * the same reason the top-level reorder is: the round-trip gate compares the two documents
     * key-sorted, so a reorder is data-inert by construction.
     * </p>
     *
     * <p>
     * ⚑ A level's optional {@code Message} is emitted by Jackson and re-indented, so its quoting
     * and escaping are the writer's, identical to every other string in the file; the self-check
     * below re-parses the whole block and demands every expression <em>and</em> every message come
     * back byte-exact.
     * </p>
     *
     * @param check
     *            the rule's {@code Check} node, may be {@code null}
     * @return the full {@code Check:} block with one trailing newline, or {@code null} when this
     *         layout does not apply
     */
    static @Nullable String renderLevelCheckBlockOrNull(@Nullable JsonNode check)
    {
        SequencedMap<String, JsonNode> levels = RuleFingerprint.levels(check);
        if (levels == null)
        {
            return null;
        }
        StringBuilder block = new StringBuilder("Check:\n");
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            JsonNode condition = level.getValue();
            if (!condition.isObject() || condition.size() != 1
                    || !condition.hasNonNull("expression")
                    || !condition.get("expression").isTextual())
            {
                // A legacy-shaped or multi-key level has no D27 layout; leave the whole map to
                // Jackson rather than emitting a half-canonical block.
                return null;
            }
            block.append("  ").append(level.getKey()).append(":\n");
            block.append(renderExpressionScalar(condition.get("expression").asText(), 4));
            JsonNode message = check.get(level.getKey()).get(RuleFingerprint.MESSAGE_KEY);
            if (message != null && !message.isNull())
            {
                block.append(renderMessage(message, 4));
            }
        }
        String rendered = block.toString();
        JsonNode reparsed = reparseBlock(rendered).path("Check");
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            JsonNode back = reparsed.path(level.getKey());
            String intended = level.getValue().get("expression").asText();
            if (!intended.equals(back.path("expression").asText()))
            {
                throw new IllegalStateException("folded layout did not reconstruct level "
                        + level.getKey() + ": <" + intended + ">");
            }
            JsonNode message = check.get(level.getKey()).get(RuleFingerprint.MESSAGE_KEY);
            if (message != null && !message.isNull()
                    && !message.asText().equals(back.path(RuleFingerprint.MESSAGE_KEY).asText()))
            {
                throw new IllegalStateException(
                        "layout did not reconstruct the Message of level " + level.getKey());
            }
        }
        return rendered;
    }


    /**
     * One {@code expression: >-} folded scalar, its key at {@code keyIndent} spaces and its content
     * one {@link #EXPRESSION_LINE_INDENT} deeper. Shared by the plain and the level-keyed layouts
     * so the wrap rule is decided once (D27).
     */
    private static String renderExpressionScalar(String expression, int keyIndent)
    {
        if (expression.isBlank() || !expression.strip().equals(expression)
                || expression.indexOf('\n') >= 0 || expression.indexOf('\t') >= 0)
        {
            throw new IllegalArgumentException(
                    "not a canonical single-line expression: <" + expression + ">");
        }
        String key = " ".repeat(keyIndent);
        String content = key + EXPRESSION_LINE_INDENT;
        StringBuilder block = new StringBuilder(key).append("expression: >-\n");
        for (String line : wrapExpression(expression, EXPRESSION_WRAP_COLUMN - content.length()))
        {
            block.append(content).append(line).append('\n');
        }
        return block.toString();
    }


    /** One {@code Message:} entry, written by the shared YAML writer and re-indented. */
    private static String renderMessage(JsonNode message, int keyIndent)
    {
        ObjectNode holder = YAML_READ.createObjectNode();
        holder.set(RuleFingerprint.MESSAGE_KEY, message);
        String written;
        try
        {
            written = OriginalTextBackfill.YAML_WRITE.writeValueAsString(holder);
        }
        catch (JsonProcessingException e)
        {
            throw new UncheckedIOException(e);
        }
        String indent = " ".repeat(keyIndent);
        StringBuilder out = new StringBuilder();
        for (String line : written.strip().split("\n", -1))
        {
            out.append(indent).append(line).append('\n');
        }
        return out.toString();
    }


    /** Re-parses a rendered {@code Check:} block, so a wrapping bug fails loudly here. */
    private static JsonNode reparseBlock(String rendered)
    {
        try
        {
            return YAML_READ.readTree(rendered);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("rendered Check block is not valid YAML: " + rendered,
                    e);
        }
    }


    /**
     * Greedy fill: breaks {@code text} at token-boundary spaces (spaces outside double-quoted
     * strings and backtick-quoted operands) so each returned line is at most {@code width} chars,
     * except where no boundary allows it (a single over-long token is emitted unbroken rather than
     * split mid-token). Breaks never sit beside a second space, so no folded line can start or end
     * with whitespace — the property YAML folding needs to reconstruct the input exactly.
     */
    static List<String> wrapExpression(String text, int width)
    {
        List<Integer> breaks = breakCandidates(text);
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (text.length() - start > width)
        {
            int chosen = -1;
            for (int b : breaks)
            {
                if (b <= start)
                {
                    continue;
                }
                if (b - start > width)
                {
                    break;
                }
                chosen = b;
            }
            if (chosen < 0)
            {
                // No boundary within the width: over-long token. Break at the next boundary
                // beyond it, or give up and emit the remainder as one long line.
                for (int b : breaks)
                {
                    if (b > start)
                    {
                        chosen = b;
                        break;
                    }
                }
                if (chosen < 0)
                {
                    break;
                }
            }
            lines.add(text.substring(start, chosen));
            start = chosen + 1;
        }
        lines.add(text.substring(start));
        return lines;
    }


    /**
     * The indices of every space usable as a wrap point: outside double-quoted strings (with
     * {@code \}-escape handling) and backtick-quoted operands, not adjacent to another space, and
     * not the first or last character. Quote tracking is a readability heuristic only — a
     * misdetected span merely suppresses candidates (the line runs long); value safety comes from
     * {@link #renderExpressionCheckBlock}'s re-parse check, never from this scan.
     */
    private static List<Integer> breakCandidates(String text)
    {
        List<Integer> out = new ArrayList<>();
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (inDouble)
            {
                if (c == '\\')
                {
                    i++;
                }
                else if (c == '"')
                {
                    inDouble = false;
                }
            }
            else if (inBacktick)
            {
                if (c == '`')
                {
                    inBacktick = false;
                }
            }
            else if (c == '"')
            {
                inDouble = true;
            }
            else if (c == '`')
            {
                inBacktick = true;
            }
            else if (c == ' ' && i > 0 && i < text.length() - 1 && text.charAt(i - 1) != ' '
                    && text.charAt(i + 1) != ' ')
            {
                out.add(i);
            }
        }
        return out;
    }

    // ---- data-equality (recursive key-sorted structural compare) ----------


    /** True if {@code a} and {@code b} are structurally equal ignoring object key order. */
    static boolean dataEqual(JsonNode a, JsonNode b)
    {
        return sortedDeep(a).equals(sortedDeep(b));
    }


    /** A deep copy with every object's keys sorted; array element order is preserved. */
    private static JsonNode sortedDeep(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode sorted = YAML_READ.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String k : keys)
            {
                sorted.set(k, sortedDeep(node.get(k)));
            }
            return sorted;
        }
        if (node.isArray())
        {
            ArrayNode arr = YAML_READ.createArrayNode();
            for (JsonNode e : node)
            {
                arr.add(sortedDeep(e));
            }
            return arr;
        }
        return node;
    }

    // ---- comment detection (for the strip count) --------------------------


    /**
     * A best-effort scan for a YAML comment: a {@code #} that is the first non-blank character or
     * is preceded by whitespace, outside single/double-quoted scalars.
     */
    static boolean hasComment(String text)
    {
        for (String line : text.split("\n", -1))
        {
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < line.length(); i++)
            {
                char c = line.charAt(i);
                if (inDouble && c == '\\')
                {
                    i++;
                    continue;
                }
                if (c == '\'' && !inDouble)
                {
                    inSingle = !inSingle;
                }
                else if (c == '"' && !inSingle)
                {
                    inDouble = !inDouble;
                }
                else if (c == '#' && !inSingle && !inDouble
                        && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t'))
                {
                    return true;
                }
            }
        }
        return false;
    }


    private static String name(Path file)
    {
        return file.getFileName().toString().replace(".yaml", "");
    }
}
