package net.cumba.cdisc.core.rulespec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Committed list of specs that are expected to fail the Java ASSERT lane because they exercise a
 * known, documented Java/Python engine divergence (typically one of the Fix #N entries in
 * {@code core-engine-update-plan.md}).
 *
 * <p>
 * Per plan §9.4: the baseline is the regression net's "known issues" list. It lets us port rules
 * tied to pending engine fixes without blocking the whole parity suite. When a fix lands and the
 * spec starts passing, the test surfaces the unexpected-pass as a failure so the author knows to
 * remove the baseline entry in the same PR.
 *
 * <p>
 * Loaded from {@code rulespec/parity-diff-baseline.json} at test-discovery time. Kept minimal —
 * spec name + Fix #/finding ref + optional notes. No payload-shape checks in M1 of the baseline; we
 * treat the spec as opaquely "known-divergent" and verify only that a divergence IS still present.
 * Tightening to "the divergence matches the recorded shape" is a follow-up if we start hitting
 * drift.
 */
public final class BaselineManifest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The assertion channels a spec can be baselined on. {@code violations} is the original
     * expected-violations diff; {@code status} is the rule-execution-status channel added by
     * {@code PLAN-parity-expected-status}; {@code errors} is the classified-failure-reason channel
     * added by {@code PLAN-parity-expected-errors}. Mirror of {@code baseline.CHANNELS} on the
     * Python lane.
     */
    public static final String VIOLATIONS_CHANNEL = "violations";

    public static final String STATUS_CHANNEL = "status";

    public static final String ERRORS_CHANNEL = "errors";

    public static final List<String> CHANNELS = List.of(VIOLATIONS_CHANNEL, STATUS_CHANNEL,
            ERRORS_CHANNEL);

    /**
     * An entry naming no channels was filed before the status channel existed, and every one of
     * those was about the violation diff.
     */
    private static final List<String> DEFAULT_CHANNELS = List.of(VIOLATIONS_CHANNEL);

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KnownDivergence(String spec,
            /* Which ASSERT lane this spec is expected to fail on. One of
             *  {@code "java"}, {@code "python"}, {@code "both"}. Defaults to
             *  {@code "java"} when absent (back-compat with entries filed before
             *  Python-side baseline support). */
            String lane,
            /* Which channels this spec is expected to DIVERGE on. Absent ⇒
             *  {@code ["violations"]}. A channel that is NOT listed is asserted
             *  normally, so a status-only entry keeps its violation coverage. */
            List<String> channels, String fixRef, String description, String notes)
    {

        /**
         * Effective lane — normalises to lower case and defaults to {@code "java"} when unset.
         * Callers should use this in preference to {@link #lane()}.
         */
        public String laneOrDefault()
        {
            return lane == null || lane.isBlank() ? "java" : lane.toLowerCase(Locale.ROOT);
        }


        public boolean appliesToJavaLane()
        {
            String l = laneOrDefault();
            return "java".equals(l) || "both".equals(l);
        }


        public boolean appliesToPythonLane()
        {
            String l = laneOrDefault();
            return "python".equals(l) || "both".equals(l);
        }


        /**
         * Normalised channel set; absent or empty ⇒ {@code ["violations"]}.
         *
         * <p>
         * Semantics, identical on both lanes: a channel LISTED here is expected to diverge; a
         * channel NOT listed must match normally. Defaulting to {@code ["violations"]} rather than
         * to both is what keeps the entries filed before this field existed subject to the status
         * assertion instead of permanently exempt from it.
         */
        public List<String> effectiveChannels()
        {
            if (channels == null || channels.isEmpty())
            {
                return DEFAULT_CHANNELS;
            }
            return channels.stream().map(c -> c.strip().toLowerCase(Locale.ROOT)).toList();
        }


        /** Whether this entry expects {@code aChannel} to diverge. */
        public boolean divergesOn(String aChannel)
        {
            return effectiveChannels().contains(aChannel);
        }


        /** Channel names outside the vocabulary — a manifest authoring error. */
        public List<String> unknownChannels()
        {
            return effectiveChannels().stream().filter(c -> !CHANNELS.contains(c)).toList();
        }


        /**
         * Channels that diverge but which this entry does <b>not</b> claim.
         *
         * <p>
         * Non-empty means the baseline is under-describing the spec: something broke on an axis the
         * entry says nothing about, and suppressing it would be an accident.
         */
        public List<String> unclaimedDivergences(Map<String, Boolean> aDiverged)
        {
            return aDiverged.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getValue()) && !divergesOn(e.getKey()))
                    .map(Map.Entry::getKey).toList();
        }


        /**
         * Channels this entry claims should diverge but which now <b>match</b>. Non-empty means the
         * claim has rotted — the underlying fix has landed for that channel.
         *
         * <p>
         * Per-channel by design (D3 of {@code PLAN-parity-expected-errors}). The rule this replaces
         * asked only that <i>at least one</i> claimed channel still diverge, which with three
         * channels lets an entry claiming all three stay green while two of them heal, hiding the
         * landed fixes indefinitely. Mirrored in {@code baseline.py}'s {@code healed_channels}.
         */
        public List<String> healedChannels(Map<String, Boolean> aDiverged)
        {
            return effectiveChannels().stream()
                    .filter(c -> aDiverged.containsKey(c) && !Boolean.TRUE.equals(aDiverged.get(c)))
                    .toList();
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Manifest(String baselineDate, List<KnownDivergence> knownDivergences)
    {

        public Map<String, KnownDivergence> bySpec()
        {
            if (knownDivergences == null)
            {
                return Map.of();
            }
            return knownDivergences.stream().collect(java.util.stream.Collectors.toMap(
                    KnownDivergence::spec, d -> d, (a, _) -> a, java.util.LinkedHashMap::new));
        }


        public boolean contains(String specName)
        {
            return lookup(specName).isPresent();
        }


        /**
         * First entry for {@code specName}, or empty.
         *
         * <p>
         * FIRST-MATCH, and deliberately kept that way to mirror the Python lane. A second entry for
         * the same spec is therefore a silent no-op — edit the existing entry rather than appending
         * another. {@link #duplicateSpecs()} exists so a test can make that loud.
         */
        public Optional<KnownDivergence> lookup(String specName)
        {
            if (knownDivergences == null)
            {
                return Optional.empty();
            }
            return knownDivergences.stream().filter(d -> specName.equals(d.spec())).findFirst();
        }


        /**
         * Spec names carrying more than one entry — always an authoring error, since
         * {@link #lookup(String)} is first-match and every later entry is dead.
         */
        public List<String> duplicateSpecs()
        {
            if (knownDivergences == null)
            {
                return List.of();
            }
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            java.util.Set<String> dupes = new java.util.LinkedHashSet<>();
            for (KnownDivergence d : knownDivergences)
            {
                if (!seen.add(d.spec()))
                {
                    dupes.add(d.spec());
                }
            }
            return List.copyOf(dupes);
        }


        /** Entries naming a channel outside the vocabulary, as {@code spec -> bad names}. */
        public Map<String, List<String>> entriesWithUnknownChannels()
        {
            if (knownDivergences == null)
            {
                return Map.of();
            }
            Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            for (KnownDivergence d : knownDivergences)
            {
                List<String> bad = d.unknownChannels();
                if (!bad.isEmpty())
                {
                    out.put(d.spec(), bad);
                }
            }
            return out;
        }
    }

    private BaselineManifest()
    {
    }


    public static Manifest load(Path manifestFile) throws IOException
    {
        if (!Files.isRegularFile(manifestFile))
        {
            return new Manifest(null, List.of());
        }
        try (var in = Files.newInputStream(manifestFile))
        {
            return MAPPER.readValue(in, Manifest.class);
        }
    }


    /**
     * Convenience: resolve the baseline file from either the module-relative or repo-root working
     * directory. Mirrors {@code RuleExecutionSpecTest.specsRoot()}.
     */
    public static Manifest loadDefault() throws IOException
    {
        Path[] candidates =
        {
                Path.of("rulespec/parity-diff-baseline.json").toAbsolutePath(),
                Path.of("lib/cumba-oss-cdisc-rules/rulespec/parity-diff-baseline.json")
                        .toAbsolutePath()
        };
        for (Path p : candidates)
        {
            if (Files.isRegularFile(p))
            {
                return load(p);
            }
        }
        // ⚠⚠ An ABSENT baseline must never degrade to an empty one. This method used to return
        // Manifest(null, List.of()) here, and the caller only guards against an IOException — so a
        // missing file was silent. That is the worst shape available: every baselined spec stops
        // being an expected divergence and reds as a fresh failure, and the cause is invisible.
        // Measured 2026-09-02: 145 of this repository's 535 specs are baselined, so absence turns
        // a green suite into 145 failures that look like engine regressions.
        throw new java.nio.file.NoSuchFileException(candidates[0].toString(), null,
                "parity-diff baseline not found (also tried " + candidates[1] + "). "
                        + "Without it every baselined spec reports as a fresh failure.");
    }
}
