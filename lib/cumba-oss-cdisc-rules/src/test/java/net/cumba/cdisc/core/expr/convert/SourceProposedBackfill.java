package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Applies {@code documentation/rule-review-results/sheet-proposals.yaml} to the corpus: writes each
 * rule's root-level {@code Source_Proposed} block from the side-car record that proposes it.
 *
 * <h2>The side-car is the source of truth; the corpus block is generated</h2>
 * <p>
 * ⚠ Where the two disagree <b>the side-car is right and the corpus is regenerated — never the
 * reverse</b> ({@code plans/PLAN-source-proposed-block.md} §2.3). That is why this tool exists at
 * all: a hand-edited {@code Source_Proposed} would have no reviewable home, and
 * {@code SourceProposedGuardTest} asserts the two agree key for key.
 * </p>
 *
 * <h2>A line splice, deliberately — not a whole-node rewrite</h2>
 * <p>
 * Modelled directly on {@link OriginalTextBackfill#backfill}: strip any prior indent-0
 * {@code Source_Proposed:} block, splice the fresh one at the first of {@code Source} /
 * {@code Standards} found by {@link OriginalTextBackfill#indexOfTopKey}, leave every other line
 * byte-for-byte alone. ⛔ A whole-node Jackson round-trip was considered and <b>rejected</b>:
 * {@link RuleCanonicalizer} counts comments in its result, so corpus files may carry them and a
 * naive round-trip would drop them. A line splice cannot.
 * </p>
 *
 * <p>
 * ⭐ <b>Run order is load-bearing: {@code apply-sheet-proposals.sh} → {@code backfill-rules.sh}
 * (stages 1–3), never the reverse.</b> Stage 3 ({@code RuleCanonicalizer}) reserialises every file
 * from the parsed node, so style, key order and fold points are normalised <em>after</em> this
 * splice — which is why this tool need not be a fancy writer. Reversed, the canonical pass would
 * run before the block it is meant to normalise existed.
 * </p>
 *
 * <p>
 * ⚑ {@code OriginalTextBackfill} neither reads nor writes {@code Source_Proposed}: its
 * {@code stripSource} predicate matches {@code "Source:"} at indent 0, which
 * {@code "Source_Proposed:"} does not start with. The two tools are safe in either order for that
 * reason, and {@code OriginalTextBackfillTest}'s non-touch pins hold it.
 * </p>
 *
 * <p>
 * Usage — {@code -Dproposals.dryRun=true} (or {@code --check}) reports drift without writing,
 * mirroring the {@code -Dbackfill.dryRun} idiom:
 * </p>
 *
 * <pre>
 * lib / corej - cdisc - rules / scripts / apply - sheet - proposals.sh[--dry - run]
 * </pre>
 */
public final class SourceProposedBackfill
{

    /** The one hand-authored home of a proposed row. */
    static final Path SIDECAR = Path.of("documentation", "rule-review-results",
            "sheet-proposals.yaml");

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    private static final String KEY = "Source_Proposed";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private SourceProposedBackfill()
    {
    }

    /** One side-car record, flattened to the block this tool writes. */
    record Proposal(String rule, SequencedMap<String, String> fields)
    {
    }


    /** What a run did, for the report and for the tests. */
    record Result(int proposals, int changed, List<String> missingRules,
            List<String> alreadyCurrent)
    {

        String report(boolean dryRun)
        {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                    "[proposals] records=%d changed=%d up-to-date=%d missing-rule=%d%s%n",
                    proposals, changed, alreadyCurrent.size(), missingRules.size(),
                    dryRun ? " (dry-run)" : ""));
            if (!missingRules.isEmpty())
            {
                sb.append(String.format(Locale.ROOT, "            missing-rule : %s%n",
                        missingRules));
            }
            return sb.toString();
        }
    }

    /**
     * Reads the side-car into one {@link Proposal} per record.
     *
     * <p>
     * {@code Sheet_Issue} is emitted <b>first</b> in the block — it is the one key {@code Source}
     * does not have, and it names the entry making the ask — followed by the {@code Row} cells in
     * the order the side-car carries them, which is the family's own registry order.
     * </p>
     */
    static List<Proposal> readSidecar(Path sidecar) throws IOException
    {
        JsonNode root = YAML.readTree(sidecar.toFile());
        List<Proposal> out = new ArrayList<>();
        for (JsonNode rec : root.path("proposals"))
        {
            SequencedMap<String, String> fields = new LinkedHashMap<>();
            String issue = rec.path("Sheet_Issue").asText("");
            if (!issue.isEmpty())
            {
                fields.put("Sheet_Issue", issue);
            }
            JsonNode row = rec.path("Row");
            row.fieldNames().forEachRemaining(k -> fields.put(k, row.path(k).asText("")));
            out.add(new Proposal(rec.path("Rule").asText(), fields));
        }
        return out;
    }


    /**
     * Strips any prior root-level {@code Source_Proposed} block and splices a fresh one immediately
     * before {@code Source} — or before {@code Standards} when the rule has no {@code Source} yet,
     * which is where the canonical order puts it either way.
     *
     * @param input
     *            the file's lines (no terminators)
     * @param fields
     *            the ordered block contents; empty removes the block
     * @return the rewritten lines, equal to {@code input} when nothing changed
     */
    static List<String> apply(List<String> input, SequencedMap<String, String> fields)
    {
        List<String> lines = strip(new ArrayList<>(input));
        if (fields.isEmpty())
        {
            return lines;
        }
        List<String> block = OriginalTextBackfill.block(KEY, fields);
        int anchor = OriginalTextBackfill.indexOfTopKey(lines, "Source");
        if (anchor < 0)
        {
            anchor = OriginalTextBackfill.indexOfTopKey(lines, "Standards");
        }
        if (anchor < 0)
        {
            lines.addAll(block);
        }
        else
        {
            lines.addAll(anchor, block);
        }
        return lines;
    }


    /**
     * Drops the root-level {@code Source_Proposed} block, so the splice is idempotent.
     *
     * <p>
     * ⚠ Matches {@code "Source_Proposed:"} at indent 0 <b>exactly</b>, never a {@code startsWith}
     * on {@code "Source"} — the whole design rests on {@code Source} and {@code Source_Proposed}
     * being independently addressable, and a loose prefix here would delete the verbatim
     * transcription this block exists to sit beside.
     * </p>
     */
    static List<String> strip(List<String> lines)
    {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size())
        {
            String line = lines.get(i);
            if (OriginalTextBackfill.indentOf(line) == 0 && line.strip().equals(KEY + ":"))
            {
                i = OriginalTextBackfill.valueEnd(lines, i);
            }
            else
            {
                out.add(line);
                i++;
            }
        }
        return out;
    }


    /** Applies every proposal under {@code checksRoot}; writes nothing when {@code dryRun}. */
    static Result process(List<Proposal> proposals, Path checksRoot, boolean dryRun)
        throws IOException
    {
        Map<String, Path> byId = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(checksRoot))
        {
            walk.filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(p ->
            {
                String stem = p.getFileName().toString();
                byId.put(stem.substring(0, stem.length() - ".yaml".length()), p);
            });
        }
        int changed = 0;
        List<String> missing = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (Proposal p : proposals)
        {
            Path file = byId.get(p.rule());
            if (file == null)
            {
                missing.add(p.rule());
                continue;
            }
            List<String> in = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = apply(in, p.fields());
            if (out.equals(in))
            {
                current.add(p.rule());
                continue;
            }
            changed++;
            if (!dryRun)
            {
                Files.writeString(file, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
            }
        }
        return new Result(proposals.size(), changed, missing, current);
    }


    public static void main(String[] args)
    {
        boolean dryRun = Boolean.getBoolean("proposals.dryRun");
        Path checksRoot = Path
                .of(System.getProperty("proposals.checksRoot", SRC_CHECKS.toString()));
        Path sidecar = Path.of(System.getProperty("proposals.sidecar", SIDECAR.toString()));
        try
        {
            Result result = process(readSidecar(sidecar), checksRoot, dryRun);
            System.out.print(result.report(dryRun));
            if (!result.missingRules().isEmpty())
            {
                throw new IllegalStateException("side-car names rules that are not in the corpus: "
                        + result.missingRules());
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
