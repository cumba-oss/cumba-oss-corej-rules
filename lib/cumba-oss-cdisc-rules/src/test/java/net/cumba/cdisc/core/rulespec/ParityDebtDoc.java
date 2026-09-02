package net.cumba.cdisc.core.rulespec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code documentation/PARITY-DEBT.md} — the release-relevant view of
 * {@code parity-diff-baseline.json}.
 *
 * <p>
 * <b>Why this exists.</b> The baseline holds every known Java/Python divergence, and before the
 * 2026-08-04 classification sweep every one of them read as an open defect: a release audit
 * grepping {@code fix_ref} scored the whole file as debt. Most entries are recorded decisions that
 * will never close. This document reports only the residue — the entries that survive
 * classification without being accepted, upstream-owned, or a harness limitation. That residue is
 * the real coreJ parity debt list, and it is what an audit should read instead of the raw baseline.
 *
 * <p>
 * <b>How an entry is classified.</b> Purely by the markers the classification sweep appends to
 * {@code fix_ref}. ⚠ The three strings used to be kept in step with {@code DISPOSITION_MARKERS} in
 * {@code scripts/classify-baseline-divergences.py}; <b>that script no longer exists</b> — it lived
 * in the runner's Python tree, deleted with the Python lane in wave 33 — so
 * {@link #ACCEPTED_MARKER}, {@link #UPSTREAM_MARKER} and {@link #HARNESS_MARKER} are now the sole
 * definition. Prose elsewhere that still names that script is history, not a pointer. An entry
 * carrying <b>none</b> of them is debt. That default is deliberate and is the safe direction: an
 * entry a future lane appends without a marker shows up as debt until somebody classifies it,
 * rather than silently vanishing.
 *
 * <p>
 * <b>Drift.</b> A generated file committed to the repo is exactly the shape that rots — nobody
 * regenerates derived output unless something makes them. ⚠ This paragraph used to justify itself
 * with "the {@code rule-inventory/} artifacts on this repo are ~2900 lines adrift"; that claim is
 * <b>stale and was refuted twice</b> (wave-16 lane C re-derived them byte-identically, and wave 21
 * regenerated the whole tree in {@code 0be3dcba1}), so it has been removed rather than restated
 * with a fresher number — the guard below is what makes the argument, not the anecdote.
 * {@link ParityDebtDocDriftTest} regenerates this document on every build and fails if the
 * committed copy differs, the same guard {@code LegacyToExpressionDocDriftTest} once put on
 * {@code legacy-to-expression-map.md} (retired with that doc's freeze at the wave-43 flip). <b>A
 * stale debt list is worse than no debt list</b>, because an audit trusts it.
 *
 * <p>
 * Regenerate with:
 *
 * <pre>
 * mvn -P main -pl lib/corej-cdisc-rules -am test -Dtest=ParityDebtDocDriftTest
 * </pre>
 *
 * and copy the failure's regenerated output, or run {@link #main(String[])} with the target path.
 */
public final class ParityDebtDoc
{

    /** Classes A and B — a recorded decision not to align the lanes. Never closes. */
    public static final String ACCEPTED_MARKER = "java-only-accepted";

    /** Class C — a defect in the vendored fork, owned by neither lane's design. */
    public static final String UPSTREAM_MARKER = "upstream-defect";

    /** Class E — a limitation of the spec harness, not of either engine. */
    public static final String HARNESS_MARKER = "harness-limitation";

    /** Class B adds this qualifier inside the accepted clause: detection agrees, output differs. */
    public static final String REPORTING_QUALIFIER = "java-only-accepted (reporting-surface)";

    private static final List<String> DISPOSITION_MARKERS = List.of(ACCEPTED_MARKER,
            UPSTREAM_MARKER, HARNESS_MARKER);

    /**
     * Whether each debt entry is a <i>capability gap</i> (coreJ cannot do something the fork can)
     * or a <i>wrongness</i> (coreJ does it, differently from what the recorded expectation says).
     *
     * <p>
     * Not derivable from the JSON — it is a reading of the entry, so it is curated here and pinned
     * by the drift test along with everything else. An entry absent from this map is rendered
     * {@code unclassified}, which is the prompt to read it and add a row rather than a failure.
     *
     * <p>
     * ⚠ A row here is dead data once its entry leaves the baseline — {@link #DEBT_KIND} is read
     * only through {@code getOrDefault} over the entries that ARE debt. The {@code CORE-000585} row
     * was removed with its entry by EC-55 (2026-08-12), which re-baselined that spec
     * Java-canonical; keep the two in step.
     */
    private static final Map<String, String> DEBT_KIND = Map
            .of("OPGAP-shares-at-least-one-element-with", "capability gap");

    private ParityDebtDoc()
    {
    }


    /**
     * The module's {@code documentation/} directory, resolved from either the module directory
     * (surefire's working directory) or the repo root (a hand-run {@link #main(String[])}).
     *
     * <p>
     * ⚠ Probed on the <b>baseline file</b>, not on the directory name. {@code lib/corej-cdisc-core}
     * and {@code lib/corej-cdisc-rules} also have a {@code documentation/} directory, so a
     * directory-name probe would resolve to a different module's docs while the baseline lookup
     * correctly fell through — and the two would then disagree.
     */
    private static Path documentationDir()
    {
        Path moduleRelative = Path.of("documentation", "parity-diff-baseline.json");
        return Files.isRegularFile(moduleRelative) ? Path.of("documentation")
                : Path.of("lib", "corej-cdisc-rules", "documentation");
    }


    /** Locate the baseline from either the module directory or the repo root. */
    static Path baselineFile()
    {
        return documentationDir().resolve("parity-diff-baseline.json");
    }


    /** Where the generated document is committed. Always beside the baseline it derives from. */
    static Path documentFile()
    {
        return documentationDir().resolve("PARITY-DEBT.md");
    }


    private static boolean carries(BaselineManifest.KnownDivergence aEntry, String aMarker)
    {
        String ref = aEntry.fixRef();
        return ref != null && ref.contains(aMarker);
    }


    /** True when the entry carries no disposition marker at all — i.e. it is coreJ debt. */
    public static boolean isDebt(BaselineManifest.KnownDivergence aEntry)
    {
        return DISPOSITION_MARKERS.stream().noneMatch(m -> carries(aEntry, m));
    }


    /** The debt residue, spec-sorted. Computed from the file, never from a hard-coded cohort. */
    public static List<BaselineManifest.KnownDivergence> debt(BaselineManifest.Manifest aManifest)
    {
        return aManifest.knownDivergences().stream().filter(ParityDebtDoc::isDebt)
                .sorted(Comparator.comparing(BaselineManifest.KnownDivergence::spec)).toList();
    }


    private static long count(BaselineManifest.Manifest aManifest,
            java.util.function.Predicate<BaselineManifest.KnownDivergence> aPredicate)
    {
        return aManifest.knownDivergences().stream().filter(aPredicate).count();
    }

    /**
     * The five disposition counts the document's census table prints.
     *
     * <p>
     * Exposed so {@link ParityDebtDocDriftTest} can assert what {@link #render} only *displays*:
     * that the five add up to {@code total}, and that the marker classes are mutually exclusive.
     * Neither is free — {@link #reporting} is derived by <b>subtraction</b>
     * ({@code count(ACCEPTED) - count(REPORTING)}), so an entry carrying two markers, or a
     * qualifier spelled without its token, would silently make the table not add up while every
     * other assertion stayed green.
     */
    public record Census(long reporting, long accepted, long upstream, long harness, long debt,
            int total)
    {

        /** What the five class counts sum to; must equal {@link #total}. */
        public long sum()
        {
            return reporting + accepted + upstream + harness + debt;
        }
    }

    /** Count each disposition class. See {@link Census}. */
    public static Census census(BaselineManifest.Manifest aManifest)
    {
        long reporting = count(aManifest, e -> carries(e, REPORTING_QUALIFIER));
        return new Census(reporting, count(aManifest, e -> carries(e, ACCEPTED_MARKER)) - reporting,
                count(aManifest, e -> carries(e, UPSTREAM_MARKER)),
                count(aManifest, e -> carries(e, HARNESS_MARKER)), debt(aManifest).size(),
                aManifest.knownDivergences().size());
    }


    /** Entries carrying more than one disposition marker — always an authoring error. */
    public static List<String> multiMarkerSpecs(BaselineManifest.Manifest aManifest)
    {
        return aManifest.knownDivergences().stream()
                .filter(e -> DISPOSITION_MARKERS.stream().filter(m -> carries(e, m)).count() > 1)
                .map(BaselineManifest.KnownDivergence::spec).toList();
    }


    /** Escape the pipe and newline characters that would break a Markdown table cell. */
    private static String cell(String aText)
    {
        if (aText == null)
        {
            return "";
        }
        return aText.replace("|", "\\|").replace("\r", " ").replace("\n", " ").strip();
    }


    /** Render the whole document. Deterministic: same JSON in, same bytes out. */
    public static String render(BaselineManifest.Manifest aManifest)
    {
        List<BaselineManifest.KnownDivergence> debt = debt(aManifest);
        Census c = census(aManifest);
        long reporting = c.reporting();
        long accepted = c.accepted();
        long upstream = c.upstream();
        long harness = c.harness();
        int total = c.total();

        StringBuilder b = new StringBuilder();
        b.append("# coreJ parity debt\n\n");
        b.append("> **Generated — do not edit by hand.** Produced from\n");
        b.append("> [`parity-diff-baseline.json`](parity-diff-baseline.json) by\n");
        b.append("> `ParityDebtDoc`, and pinned by `ParityDebtDocDriftTest` on every build.\n");
        b.append("> Re-run the test to regenerate.\n\n");
        b.append("The baseline records every known Java/Python divergence, most of which are\n");
        b.append("recorded decisions that will never close. **This document reports only the\n");
        b.append("residue**: entries carrying no disposition marker, which is what a release\n");
        b.append("audit should read instead of the raw baseline.\n\n");
        b.append("> ⛔ **The baseline is FROZEN IN PLACE (`W37-B2`, 2026-08-12).** Every entry\n");
        b.append("> in it is now `lane: python`, and under the removal of the vendored\n");
        b.append(
                "> `cdisc-rules-engine` fork (`PLAN-fork-removal-stage4`; last pinned at commit\n");
        b.append("> `195c7172`, `v0.16.0-hf3-19-g195c7172`) that tag means **historical and no\n");
        b.append(
                "> longer re-derivable** \u2014 not \"live on the other lane\". The Python lane\n");
        b.append("> itself died in wave 33, so already today no entry below can be confirmed,\n");
        b.append("> refuted or retired by measurement. They stay inert by the mechanism that\n");
        b.append(
                "> always made them inert: `RuleExecutionParityTest` takes the identical branch\n");
        b.append("> for a `lane: python` entry and for no entry at all.\n");
        b.append(">\n");
        b.append("> ⚠ Three `upstream-defect` rows (`CDISC-AD0053`, `CDISC-AD0089`,\n");
        b.append("> `CDISC-AD0256`) instruct a reader to *\"revisit on bump\"*. **That becomes\n");
        b.append("> unperformable** \u2014 stage 4 leaves no submodule to bump.\n");
        b.append(">\n");
        b.append("> ⚑ The two `lane: java` entries (`CORE-000581`, `CORE-000585`) were\n");
        b.append(
                "> re-baselined **Java-canonical** and removed by **EC-55** on 2026-08-12; both\n");
        b.append("> specs pass normally now. `CORE-000585` was one of the two rows this debt\n");
        b.append("> list used to carry.\n\n");

        b.append("## Disposition census\n\n");
        b.append("| n | disposition | meaning |\n");
        b.append("|---:|---|---|\n");
        b.append(String
                .format("| %d | `java-only-accepted (reporting-surface)` | both engines agree on"
                        + " **detection**; they differ in which columns decorate the finding"
                        + " record |\n", reporting));
        b.append(String.format(
                "| %d | `java-only-accepted` | a recorded decision **not to align the lanes**;"
                        + " no port plan |\n",
                accepted));
        b.append(String
                .format("| %d | `upstream-defect` | a defect in the vendored `cdisc-rules-engine`,"
                        + " owned by neither lane's design |\n", upstream));
        b.append(String.format(
                "| %d | `harness-limitation` | a limitation of the spec harness, not of either"
                        + " engine |\n",
                harness));
        b.append(String.format("| **%d** | **coreJ debt** | **the table below** |\n", debt.size()));
        b.append(String.format("| %d | | total baseline entries |\n\n", total));

        b.append("⚠ `java-only-accepted` answers *\"do we align the lanes?\"*, not *\"which\n");
        b.append("engine is right?\"*. An accepted entry can still carry an open contract\n");
        b.append("question; where it does, the entry's own `fix_ref` says so.\n\n");

        b.append("## The debt\n\n");
        if (debt.isEmpty())
        {
            b.append("*None — every baseline entry carries a disposition marker.*\n");
            return b.toString();
        }
        b.append("| spec | lane | channels | kind | `fix_ref` |\n");
        b.append("|---|---|---|---|---|\n");
        for (BaselineManifest.KnownDivergence e : debt)
        {
            b.append(String.format("| `%s` | %s | %s | %s | %s |\n", cell(e.spec()),
                    e.laneOrDefault(), String.join(", ", e.effectiveChannels()),
                    DEBT_KIND.getOrDefault(e.spec(), "unclassified"), cell(e.fixRef())));
        }
        b.append('\n');
        b.append(
                "**`kind`** is curated in `ParityDebtDoc.DEBT_KIND`, because it is a reading of\n");
        b.append("the entry rather than something the JSON states. A new debt entry renders as\n");
        b.append("`unclassified` until somebody reads it and adds a row.\n\n");
        b.append("**A new entry with no marker lands here by default.** That is the safe\n");
        b.append("direction: an unclassified divergence is reported as debt rather than\n");
        b.append("silently disappearing from both this list and the audit.\n");
        return b.toString();
    }


    /** Regenerate the document. Optional argument: the output path (default: the committed one). */
    public static void main(String[] aArgs) throws IOException
    {
        Path out = aArgs.length > 0 ? Path.of(aArgs[0]) : documentFile();
        Files.writeString(out, render(BaselineManifest.load(baselineFile())),
                StandardCharsets.UTF_8);
    }
}
