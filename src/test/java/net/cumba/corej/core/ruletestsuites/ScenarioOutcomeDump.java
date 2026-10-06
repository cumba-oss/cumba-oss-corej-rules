package net.cumba.corej.core.ruletestsuites;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import net.cumba.corej.core.exec.Violation;
import org.jspecify.annotations.Nullable;

/**
 * The opt-in per-scenario outcome dump — the equivalence instrument of
 * {@code PLAN-rule-authoring-api} phase 3 (pre-go review H1):
 * {@code -Dcorej.scenarioOutcomeDump=<file>} makes every factory write one line per {@code .cdt} it
 * ran — the scope-gate result, the execution state, the counts, the sorted violation rows and the
 * JUnit state — so the harness lifted into {@code cumba-corej-ruletest} can be shown to judge every
 * scenario <b>byte for byte</b> as the test-tree harness did.
 *
 * <p>
 * Why the findings snapshot is not that instrument: it runs {@code StudyValidationService} over
 * {@code testdata/study} and never touches the {@code .cdt} harness, so it is byte-identical under
 * the switch by construction. This dump reads the harness's own verdict path.
 * </p>
 *
 * <p>
 * Lines are keyed by the scenario's display path and written sorted at JVM exit (one Surefire JVM
 * runs every factory), UTF-8, LF. The content is deliberately free of timings, absolute paths of
 * this tree and anything else that differs between two runs of the same code.
 * </p>
 */
final class ScenarioOutcomeDump
{

    static final String PROPERTY = "corej.scenarioOutcomeDump";

    private static final ConcurrentSkipListMap<String, String> LINES = new ConcurrentSkipListMap<>();

    private static volatile boolean hooked;

    private ScenarioOutcomeDump()
    {
    }


    static boolean enabled()
    {
        String file = System.getProperty(PROPERTY);
        return file != null && !file.isBlank();
    }


    /** Start an entry for a scenario; a no-op recorder when the dump is off. */
    static Entry begin(String aDisplay)
    {
        return new Entry(aDisplay);
    }


    static void record(Entry aEntry)
    {
        if (!enabled())
        {
            return;
        }
        LINES.put(aEntry.display, aEntry.render());
        if (!hooked)
        {
            synchronized (ScenarioOutcomeDump.class)
            {
                if (!hooked)
                {
                    Runtime.getRuntime().addShutdownHook(
                            new Thread(ScenarioOutcomeDump::write, "scenario-outcome-dump"));
                    hooked = true;
                }
            }
        }
    }


    static void write()
    {
        Path file = Path.of(System.getProperty(PROPERTY));
        StringBuilder sb = new StringBuilder(1 << 20);
        for (Map.Entry<String, String> e : LINES.entrySet())
        {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try
        {
            if (file.getParent() != null)
            {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot write the scenario outcome dump " + file, e);
        }
    }

    /** One scenario's record, filled as the factory goes. */
    static final class Entry
    {

        private final String display;

        private String scope = "scope=unjudged";

        private String outcome = "outcome=none";

        private String junit = "junit=none";

        Entry(String aDisplay)
        {
            display = aDisplay;
        }


        /** The scope pre-gate: in scope, or the mismatch that kept the rule from running. */
        void scope(@Nullable String aMismatch)
        {
            scope = aMismatch == null ? "scope=in" : "scope=out:" + oneLine(aMismatch);
        }


        /** The execution outcome, field by field, violation rows sorted. */
        void outcome(boolean aViolated, boolean aExecuted, boolean aErrored,
                List<Violation> aViolations, long aTotalCount, boolean aTruncated,
                @Nullable String aNotExecutedReason)
        {
            List<String> rows = new ArrayList<>(aViolations.size());
            for (Violation v : aViolations)
            {
                rows.add(renderRow(v));
            }
            rows.sort(null);
            outcome = String.format(Locale.ROOT,
                    "executed=%s\terrored=%s\tviolated=%s\ttotal=%d\ttruncated=%s\treason=%s"
                            + "\trows=%d\tviolations=%s",
                    aExecuted, aErrored, aViolated, aTotalCount, aTruncated,
                    aNotExecutedReason == null ? "-" : oneLine(aNotExecutedReason), rows.size(),
                    String.join(" || ", rows));
        }


        /** The JUnit state the dynamic test ended in. */
        void junit(String aState, @Nullable String aMessage)
        {
            junit = "junit=" + aState + (aMessage == null ? "" : ":" + oneLine(aMessage));
        }


        String render()
        {
            return scope + '\t' + outcome + '\t' + junit;
        }


        private static String renderRow(Violation aViolation)
        {
            StringBuilder sb = new StringBuilder(64);
            sb.append(aViolation.getRow()).append(':');
            Map<String, String> values = new TreeMap<>();
            if (aViolation.getValues() != null)
            {
                values.putAll(aViolation.getValues());
            }
            boolean first = true;
            for (Map.Entry<String, String> e : values.entrySet())
            {
                if (!first)
                {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            if (aViolation.getLevel() != null)
            {
                sb.append(" @").append(aViolation.getLevel().name());
            }
            if (aViolation.getNamedColumns() != null && !aViolation.getNamedColumns().isEmpty())
            {
                sb.append(" named=").append(String.join("|", aViolation.getNamedColumns()));
            }
            return sb.toString();
        }


        private static String oneLine(String aText)
        {
            String first = aText.strip();
            int eol = first.indexOf('\n');
            return (eol < 0 ? first : first.substring(0, eol)).replace('\t', ' ');
        }
    }
}
