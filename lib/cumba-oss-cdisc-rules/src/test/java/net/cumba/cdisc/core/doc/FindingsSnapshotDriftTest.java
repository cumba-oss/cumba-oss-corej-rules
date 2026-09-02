package net.cumba.cdisc.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The behaviour pin behind {@link FindingsSnapshot}: a fresh study-level run over
 * {@code testdata/study} must reproduce {@code documentation/findings-snapshot.tsv} row for row —
 * every finding occurrence, every row number, every rule × dataset status.
 *
 * <p>
 * <b>Opt-in</b> ({@code -Dcorej.findingsSnapshot=true}): the run takes minutes, needs the main
 * tree's untracked {@code testdata/study} and both library caches ({@code CDISC_PICKLE_CACHE_DIR},
 * {@code CDISC_API_CACHE}). It is the gate every finding-moving phase of
 * {@code PLAN-leaf-scope-domain-inference.md} (§5 phases 4–6) runs explicitly, not a default-build
 * test. A failure names every rule whose rows moved and the first few rows each way.
 * </p>
 */
class FindingsSnapshotDriftTest
{

    private static final int MAX_REPORTED = 200;

    @Test
    @EnabledIfSystemProperty(named = "corej.findingsSnapshot", matches = "true")
    void committedSnapshotMatchesAFreshRun() throws IOException
    {
        FindingsSnapshot.Snapshot fresh = FindingsSnapshot.snapshot();
        String committed = Files.readString(FindingsSnapshot.artifactFile());
        List<String> actual = FindingsSnapshot.parse(FindingsSnapshot.render(fresh));
        List<String> diff = FindingsSnapshot.diff(FindingsSnapshot.parse(committed), actual);
        assertTrue(diff.isEmpty(), () -> diff.size()
                + " rule(s) produce different findings / statuses over testdata/study than"
                + " documentation/findings-snapshot.tsv records. If the movement was intended,"
                + " list every rule below in the ledger entry and regenerate with"
                + " lib/corej-cdisc-rules/scripts/gen-findings-snapshot.sh.\n" + report(diff));
        assertEquals(FindingsSnapshot.render(fresh), committed,
                "documentation/findings-snapshot.tsv has the same rows as a fresh run but differs"
                        + " byte-for-byte — the banner or the counts drifted. Regenerate with"
                        + " lib/corej-cdisc-rules/scripts/gen-findings-snapshot.sh.");
    }


    private static String report(List<String> diff)
    {
        StringBuilder b = new StringBuilder();
        for (String line : diff.subList(0, Math.min(MAX_REPORTED, diff.size())))
        {
            b.append("  ").append(line).append('\n');
        }
        if (diff.size() > MAX_REPORTED)
        {
            b.append("  … and ").append(diff.size() - MAX_REPORTED).append(" more\n");
        }
        return b.toString();
    }
}
