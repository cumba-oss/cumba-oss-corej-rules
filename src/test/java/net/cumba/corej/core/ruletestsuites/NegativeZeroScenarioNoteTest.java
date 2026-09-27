package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The safeguard of owner answer {@code NZL Q3} (a) (PLAN-negative-zero-on-load): the rule-test
 * harness keeps a {@code -0.0} cell RAW — {@code CdtLoader} stores into an
 * {@link OverlayDataTable}, never through a DOUBLE buffer, so it is the one corpus-level instrument
 * that can still put a negative zero into a key column, the shape a computed key produces. Since
 * {@code NZL O1} no product reader can load one, so a raw {@code -0} Num cell in a scenario is a
 * deliberate stand-in for a computed {@code -0.0} and must say so.
 *
 * <p>
 * This walks every {@code .cdt} scenario, loads it with {@link RuleTestCdt#load(Path)} (no engine
 * run), and finds every file with a DOUBLE cell whose raw bits are {@code 0x8000000000000000}. It
 * reads the <b>typed</b> cell, because a text scan is wrong both ways: it hits
 * {@code CDISC-SEND-0221-invalid_char_shipped-TA.cdt}, whose {@code #note} contains {@code -0}, and
 * would miss a {@code -0e0} spelling.
 * </p>
 *
 * <ul>
 * <li>The set of such files <b>equals</b> {@link #ROSTER} — an exact roster, not a count (a count
 * pin is blind to a re-key), so a new raw {@code -0} Num cell must be added here deliberately.</li>
 * <li>Each rostered file's {@code #note} cites {@code PLAN-negative-zero-on-load}.</li>
 * <li>A population floor on the files walked, so a walk that loaded nothing cannot pass.</li>
 * </ul>
 */
class NegativeZeroScenarioNoteTest
{

    private static final Path SCENARIOS = Path
            .of("src/test/resources/net/cumba/corej/core/ruletestsuites");

    /** The scenarios that carry a raw {@code -0.0} Num cell on purpose (NZL Q3 (a)). */
    private static final List<String> ROSTER = List.of("CDISC-CG0562-invalid-VS-4.cdt",
            "FDA-SD1125-invalid_signed_zero-VS.cdt");

    private static final String PLAN = "PLAN-negative-zero-on-load";

    /**
     * Floor on the scenarios walked. ⚠ A FLOOR, not a pin: set below the 8 852 {@code .cdt} files
     * measured on 2026-09-27, because the corpus grows.
     */
    private static final int MIN_SCENARIOS = 8000;

    private static boolean holdsNegativeZero(OverlayDataTable aTable)
    {
        DataTableMeta meta = aTable.getMetaData();
        for (int c = 0; c < meta.getColumnCount(); c++)
        {
            if (meta.getColumn(c).getType() != DataValueType.DOUBLE)
            {
                continue;
            }
            for (long r = 0; r < aTable.getRowCount(); r++)
            {
                if (aTable.getValue(r, c) instanceof Double d
                        && Double.doubleToRawLongBits(d) == Long.MIN_VALUE)
                {
                    return true;
                }
            }
        }
        return false;
    }


    @Test
    void everyRawNegativeZeroCellIsRosteredAndCitesThePlan() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SCENARIOS))
        {
            files = walk.filter(Files::isRegularFile).filter(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cdt"))
                    .sorted().toList();
        }
        assertTrue(files.size() >= MIN_SCENARIOS, "walked only " + files.size()
                + " .cdt scenarios under " + SCENARIOS + " (floor " + MIN_SCENARIOS + ")");

        TreeMap<String, RuleTestScenario> found = new TreeMap<>();
        for (Path file : files)
        {
            RuleTestScenario scenario = RuleTestCdt.load(file);
            for (OverlayDataTable ds : scenario.getDatasets())
            {
                if (holdsNegativeZero(ds))
                {
                    found.put(file.getFileName().toString(), scenario);
                    break;
                }
            }
        }

        assertEquals(ROSTER, new ArrayList<>(found.keySet()),
                "the scenarios holding a raw -0.0 Num cell (the rule-test harness keeps it raw, "
                        + "NZL Q3 (a)); a new one is a stand-in for a computed -0.0 and must be "
                        + "added to the roster and cite " + PLAN + " in its #note");
        for (var e : found.entrySet())
        {
            String note = e.getValue().getNote();
            assertNotNull(note, e.getKey() + ": no #note");
            assertTrue(note.contains(PLAN), e.getKey() + ": the #note must cite " + PLAN);
        }
    }
}
