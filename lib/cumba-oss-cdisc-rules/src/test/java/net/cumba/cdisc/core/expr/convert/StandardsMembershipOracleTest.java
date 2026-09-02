package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.cdisc.core.expr.convert.StandardsMembershipOracle.Finding;
import net.cumba.cdisc.core.expr.convert.StandardsMembershipOracle.Oracle;
import net.cumba.cdisc.core.expr.convert.StandardsMembershipOracle.Sheets;
import net.cumba.cdisc.core.expr.convert.StandardsMembershipOracle.Verdict;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardsMembershipOracleTest
{

    // ---- fixtures ---------------------------------------------------------

    private static void sheet(XSSFWorkbook wb, String name, List<List<String>> rows)
    {
        Sheet s = wb.createSheet(name);
        for (int r = 0; r < rows.size(); r++)
        {
            Row row = s.createRow(r);
            List<String> cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++)
            {
                Cell cell = row.createCell(c);
                cell.setCellValue(cells.get(c));
            }
        }
    }


    private static void book(Path dir, String file, XSSFWorkbook wb) throws IOException
    {
        try (OutputStream os = Files.newOutputStream(dir.resolve(file)))
        {
            wb.write(os);
        }
        wb.close();
    }


    /**
     * Writes all five workbooks in their real layouts (header rows, column labels, one-row-per-rule
     * {@code X} marks vs one-row-per-version cells) with a small population each.
     */
    private static void workbooks(Path dir) throws IOException
    {
        XSSFWorkbook fda = new XSSFWorkbook();
        sheet(fda, "FDA Validator Rules Intro", List.of(List.of("intro")));
        sheet(fda, "FDA Validator Rules v1.6", List.of(List.of("version 1.6"),
                List.of("FDA Validator Rule ID", "Publisher", "Publisher ID", "Domains",
                        "SDTMIG\n3.1.2", "SDTMIG\n3.1.3", "SDTMIG\n3.2", "SDTMIG\n3.3",
                        "SENDIG\n3.0", "SENDIG\n3.1", "SENDIG\n3.1.1", "SENDIG-AR\n1.0",
                        "SENDIG-DART\n1.1"),
                List.of("SD0001", "CDISC", "CG0001", "ALL", "X", "X", "X", "X", "X", "X", "X", "X",
                        "X"),
                List.of("SD0002", "FDA", "FDAB001", "ALL", "", "", "X", "X", "", "", "", "", ""),
                List.of("SD0003", "FDA", "FDAB001", "ALL", "", "", "", "", "", "", "", "", ""),
                List.of("", "", "", "", "", "", "", "", "", "", "", "", "")));
        book(dir, "FDA-validator-rules-v1-6.xlsx", fda);

        XSSFWorkbook pmda = new XSSFWorkbook();
        sheet(pmda, "Change History", List.of(List.of("x")));
        sheet(pmda, "SDTM Rules",
                List.of(List.of(""), List.of("SDTM Rules v6.0"),
                        List.of("RULE ID", "MESSAGE", "DESCRIPTION", "DOMAINS", "PMDA Severity",
                                "3.1.2", "3.1.3", "3.2", "3.3", "3.4", "PMDA NOTES"),
                        List.of("SD0001", "m", "d", "ALL", "Error", "X", "X", "X", "X", "X", ""),
                        List.of("SD0002", "m", "d", "ALL", "Error", "", "", "", "X", "X", "")));
        sheet(pmda, "ADaM Rules",
                List.of(List.of("ADaM Rules v6.0"),
                        List.of("RULE ID", "MESSAGE", "DESCRIPTION", "DOMAINS", "PMDA Severity",
                                "1.0", "1.1", "1.2", "1.3", "PMDA NOTES"),
                        List.of("AD0001", "m", "d", "ADSL", "Error", "X", "", "", "", ""),
                        List.of("AD0001B", "m", "d", "ADSL", "Error", "", "X", "X", "X", "")));
        book(dir, "PMDA-ValidationRules-v6-0.xlsx", pmda);

        XSSFWorkbook send = new XSSFWorkbook();
        sheet(send, "READ ME", List.of(List.of("x")));
        sheet(send, "_GENETOXv1.0_DARTv1.2", List.of(
                List.of("CDISC SEND Rule ID", "Class", "Domain", "Variable", "Cited Document",
                        "SENDIG Version"),
                List.of("SEND1", "ALL", "ALL", "NA", "SENDIG v3.0", "SENDIG v3.0"),
                List.of("SEND1.1", "ALL", "ALL", "NA", "SENDIG v3.1", "SENDIG v3.1"),
                List.of("SEND1.1", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG v3.1.1"),
                List.of("SEND1.1", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG-GENETOX v1.0"),
                List.of("SEND1.1", "ALL", "ALL", "NA", "SENDIG v3.1", "SENDIG-DART v1.1"),
                List.of("SEND1.1", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG-DART v1.2"),
                List.of("SEND2", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG v3.1.1"),
                List.of("SEND2", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG-GENETOX v1.0"),
                List.of("SEND2", "ALL", "ALL", "NA", "SENDIG v3.1.1", "SENDIG-DART v1.2"),
                List.of("SEND3", "ALL", "ALL", "NA", "SENDIG v3.0", "not a version")));
        book(dir, "SEND_Conformance_Rules_v5.0.xlsx", send);

        XSSFWorkbook cg = new XSSFWorkbook();
        sheet(cg, "SDTMIG Conformance Rules v2.0",
                List.of(List.of("Rule ID", "SDTMIG Version", "Rule Version"),
                        List.of("CG0001", "3.2", "1"), List.of("CG0001", "3.3", "1"),
                        List.of("CG0001", "3.4", "1"), List.of("CG0002", "3.2", "1"),
                        List.of("CG0002", "3.3", "1"), List.of("CG0003", "", "1")));
        book(dir, "SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx", cg);

        XSSFWorkbook adam = new XSSFWorkbook();
        sheet(adam, "README", List.of(List.of("x")));
        sheet(adam, "Rules Catalogue",
                List.of(List.of("Identifiers"),
                        List.of("Rule ID", "Rule ID Version", "Related Rule(s)",
                                "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)"),
                        List.of("1", "1", "", "1"), List.of("1", "1", "", "1.1"),
                        List.of("1", "1", "", "1.2"), List.of("1", "1", "", "1.3"),
                        List.of("2", "1", "", "1.3")));
        book(dir, "ADaM Conformance Rules v5.0.xlsx", adam);
    }


    private static Path checkFile(Path checksRoot, String family, String id, String body)
        throws IOException
    {
        Path fam = checksRoot.resolve(family);
        Files.createDirectories(fam);
        Path f = fam.resolve(id + ".yaml");
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    /** One standard entry: {@code (org, name, version)} carrying one {@code (origin, id)} ref. */
    private record Entry(String org, String name, String version, String origin, String id)
    {
    }

    /**
     * A check whose {@code Standards} block is exactly {@code entries}, grouped by organisation.
     */
    private static String check(String ruleId, Entry... entries)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Core:\n  Id: \"").append(ruleId).append("\"\nStandards:\n");
        List<String> orgs = new ArrayList<>();
        for (Entry e : entries)
        {
            if (!orgs.contains(e.org()))
            {
                orgs.add(e.org());
            }
        }
        for (String org : orgs)
        {
            sb.append("- Organization: \"").append(org).append("\"\n  Standards:\n");
            for (Entry e : entries)
            {
                if (!e.org().equals(org))
                {
                    continue;
                }
                sb.append("  - Name: \"").append(e.name()).append("\"\n    Version: \"")
                        .append(e.version()).append("\"\n");
                if (e.origin().isEmpty())
                {
                    continue;
                }
                sb.append("    References:\n    - Origin: \"").append(e.origin())
                        .append("\"\n      Rule_Identifier:\n        Id: \"").append(e.id())
                        .append("\"\n        Version: \"1\"\n      Version: \"1\"\n");
            }
        }
        return sb.toString();
    }


    private static Entry fda(String name, String version, String id)
    {
        return new Entry("FDA", name, version, StandardsMembershipOracle.FDA_ORIGIN, id);
    }


    private static Entry cg(String org, String name, String version, String id)
    {
        return new Entry(org, name, version, StandardsMembershipOracle.CG_ORIGIN, id);
    }


    private static Entry pmda(String name, String version, String id)
    {
        return new Entry("PMDA", name, version, StandardsMembershipOracle.PMDA_ORIGIN, id);
    }


    private static Entry send(String name, String version, String id)
    {
        return new Entry("CDISC", name, version, StandardsMembershipOracle.SEND_ORIGIN, id);
    }


    private static Finding finding(Oracle oracle, String ruleId)
    {
        return oracle.findings().stream().filter(f -> f.ruleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + ruleId));
    }


    private static Set<StandardsMembershipOracle.Key> keys(String... text)
    {
        Set<StandardsMembershipOracle.Key> out = new TreeSet<>();
        for (String t : text)
        {
            out.add(StandardsMembershipOracle.Key.parse(t));
        }
        return out;
    }

    // ---- workbook projection ---------------------------------------------


    @Test
    void workbooksProjectToColumnUniversesAndMarkedKeys(@TempDir Path dir) throws IOException
    {
        workbooks(dir);
        Sheets sheets = StandardsMembershipOracle.loadSheets(dir);

        assertEquals(List.of("FDA", "PMDA-AD", "PMDA-SDTM", "CDISC-SEND", "CDISC-CG", "CDISC-AD"),
                new ArrayList<>(sheets.byBinding().keySet()));
        var fda = sheets.projection("FDA");
        assertEquals(
                keys("SDTMIG 3.1.2", "SDTMIG 3.1.3", "SDTMIG 3.2", "SDTMIG 3.3", "SENDIG 3.0",
                        "SENDIG 3.1", "SENDIG 3.1.1", "SENDIG-AR 1.0", "SENDIG-DART 1.1"),
                fda.columns(),
                "the FDA universe is the nine version columns the v1.6 workbook carries; 3.4 is not one");
        assertEquals(keys("SDTMIG 3.2", "SDTMIG 3.3"), fda.keys("SD0002"));
        assertEquals(Set.of(), fda.keys("SD0003"), "an unmarked row still resolves (to nothing)");
        assertNull(fda.keys("SD9999"));

        var pmdaAd = sheets.projection("PMDA-AD");
        assertEquals(keys("ADaMIG 1.0", "ADaMIG 1.1", "ADaMIG 1.2", "ADaMIG 1.3"),
                pmdaAd.columns());
        assertEquals(keys("ADaMIG 1.1", "ADaMIG 1.2", "ADaMIG 1.3"), pmdaAd.keys("AD0001B"));
        var pmdaSd = sheets.projection("PMDA-SDTM");
        assertEquals(keys("SDTMIG 3.1.2", "SDTMIG 3.1.3", "SDTMIG 3.2", "SDTMIG 3.3", "SDTMIG 3.4"),
                pmdaSd.columns(), "the PMDA SDTM sheet DOES carry 3.4");
        assertEquals(keys("SDTMIG 3.3", "SDTMIG 3.4"), pmdaSd.keys("SD0002"));

        var send = sheets.projection("CDISC-SEND");
        assertEquals(keys("SENDIG 3.0", "SENDIG 3.1", "SENDIG 3.1.1", "SENDIG-DART 1.1",
                "SENDIG-DART 1.2", "SENDIG-GENETOX 1.0"), send.columns());
        assertEquals(keys("SENDIG 3.0"), send.keys("SEND1"));
        assertEquals(keys("SENDIG 3.1", "SENDIG 3.1.1", "SENDIG-DART 1.1", "SENDIG-DART 1.2",
                "SENDIG-GENETOX 1.0"), send.keys("SEND1.1"));
        assertEquals(Set.of(), send.keys("SEND3"), "an unparseable version cell marks nothing");

        var cgp = sheets.projection("CDISC-CG");
        assertEquals(keys("SDTMIG 3.2", "SDTMIG 3.3", "SDTMIG 3.4"), cgp.columns());
        assertEquals(keys("SDTMIG 3.2", "SDTMIG 3.3"), cgp.keys("CG0002"));
        assertEquals(Set.of(), cgp.keys("CG0003"));

        var adam = sheets.projection("CDISC-AD");
        assertEquals(keys("ADaMIG 1.0", "ADaMIG 1.1", "ADaMIG 1.2", "ADaMIG 1.3"), adam.keys("CR1"),
                "the bare 1 is spelled 1.0 and the id carries the CR prefix");
        assertEquals(keys("ADaMIG 1.3"), adam.keys("CR2"));
    }


    @Test
    void columnParsersRejectWhatIsNotAVersionColumn()
    {
        assertEquals(new StandardsMembershipOracle.Key("SENDIG-DART", "1.1"),
                StandardsMembershipOracle.fdaColumn("SENDIG-DART\n1.1"));
        assertNull(StandardsMembershipOracle.fdaColumn("Publisher ID"));
        assertEquals(new StandardsMembershipOracle.Key("SDTMIG", "3.1.2"),
                StandardsMembershipOracle.versionColumn("SDTMIG", "3.1.2"));
        assertNull(StandardsMembershipOracle.versionColumn("SDTMIG", "PMDA NOTES"));
        assertEquals(new StandardsMembershipOracle.Key("SENDIG-GENETOX", "1.0"),
                StandardsMembershipOracle.sendKey("SENDIG-GENETOX v1.0"));
        assertNull(StandardsMembershipOracle.sendKey(""));
        assertThrows(IllegalArgumentException.class,
                () -> StandardsMembershipOracle.Key.parse("SDTMIG"));
        assertEquals("SDTMIG 3.4", StandardsMembershipOracle.Key.parse(" SDTMIG 3.4 ").toString());
    }


    @Test
    void projectionRoundTripsThroughTheCommittedTsv(@TempDir Path dir) throws IOException
    {
        workbooks(dir);
        Sheets loaded = StandardsMembershipOracle.loadSheets(dir);
        Path tsv = dir.resolve("sub").resolve("standards-version-columns.tsv");
        StandardsMembershipOracle.writeBaseline(tsv, loaded);

        Sheets read = StandardsMembershipOracle.readBaseline(tsv);
        assertEquals(loaded.byBinding().keySet(), read.byBinding().keySet());
        for (String binding : loaded.byBinding().keySet())
        {
            assertEquals(loaded.projection(binding).columns(), read.projection(binding).columns(),
                    binding + " universe");
            assertEquals(loaded.projection(binding).byId(), read.projection(binding).byId(),
                    binding + " rows");
        }
        String text = Files.readString(tsv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# Sheet version-column projection"));
        assertTrue(text.contains("FDA\t*\tSDTMIG 3.1.2,"), "the universe row is spelled *");
        assertTrue(text.contains("\nFDA\tSD0003\t\n"), "an unmarked row is kept with no keys");
    }


    @Test
    void malformedBaselineRowsAreRejected(@TempDir Path dir) throws IOException
    {
        Path bad = dir.resolve("bad.tsv");
        Files.writeString(bad, "# c\nFDA\tSD0001\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> StandardsMembershipOracle.readBaseline(bad));
        Path noUniverse = dir.resolve("nou.tsv");
        Files.writeString(noUniverse, "FDA\tSD0001\tSDTMIG 3.2\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> StandardsMembershipOracle.readBaseline(noUniverse));
    }

    // ---- verdicts ---------------------------------------------------------


    private static Sheets fixtureSheets(Path dir) throws IOException
    {
        workbooks(dir);
        return StandardsMembershipOracle.loadSheets(dir);
    }


    @Test
    void matchSuspectBothDirectionsAndUnresolved(@TempDir Path dir) throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        // Exactly the row: MATCH.
        checkFile(checks, "PMDA", "PMDA-SD0002", check("PMDA-SD0002",
                pmda("SDTMIG", "3.3", "SD0002"), pmda("SDTMIG", "3.4", "SD0002")));
        // Declares 3.2 the row does not mark: OVER.
        checkFile(checks, "PMDA", "PMDA-SD0002B",
                check("PMDA-SD0002B", pmda("SDTMIG", "3.2", "SD0002"),
                        pmda("SDTMIG", "3.3", "SD0002"), pmda("SDTMIG", "3.4", "SD0002")));
        // Omits four versions the row marks: UNDER — no backward inheritance, an omission is a
        // defect too. (Its own row, so the split-sibling rule cannot excuse it.)
        checkFile(checks, "PMDA", "PMDA-SD0001",
                check("PMDA-SD0001", pmda("SDTMIG", "3.3", "SD0001")));
        // Both at once: the AD0280/AD0280B displacement shape on the B row.
        checkFile(checks, "PMDA", "PMDA-AD0001B", check("PMDA-AD0001B",
                pmda("ADaMIG", "1.0", "AD0001B"), pmda("ADaMIG", "1.1", "AD0001B")));
        // Cites an id the sheet has not got.
        checkFile(checks, "PMDA", "PMDA-SD9999",
                check("PMDA-SD9999", pmda("SDTMIG", "3.4", "SD9999")));

        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        assertEquals(5, oracle.total());
        assertEquals(Verdict.MATCH, finding(oracle, "PMDA-SD0002").verdict());
        Finding over = finding(oracle, "PMDA-SD0002B");
        assertEquals(Verdict.SUSPECT, over.verdict());
        assertEquals("OVER", over.reason());
        assertEquals(keys("SDTMIG 3.2"), over.extra());
        assertEquals(Set.of(), over.missing());
        Finding under = finding(oracle, "PMDA-SD0001");
        assertEquals(Verdict.SUSPECT, under.verdict(), under.describe());
        assertEquals("UNDER", under.reason());
        assertEquals(keys("SDTMIG 3.1.2", "SDTMIG 3.1.3", "SDTMIG 3.2", "SDTMIG 3.4"),
                under.missing());
        Finding both = finding(oracle, "PMDA-AD0001B");
        assertEquals("BOTH", both.reason());
        assertEquals(keys("ADaMIG 1.0"), both.extra());
        assertEquals(keys("ADaMIG 1.2", "ADaMIG 1.3"), both.missing());
        assertEquals(List.of("PMDA-AD:AD0001B"), both.cited(),
                "an AD#### id binds to the ADaM sheet");
        Finding unresolved = finding(oracle, "PMDA-SD9999");
        assertEquals(Verdict.UNRESOLVED, unresolved.verdict());
        assertEquals("PMDA-SDTM:SD9999", unresolved.reason());
        assertEquals(Map.of("PMDA", 3), oracle.byFamily(Verdict.SUSPECT));
        assertTrue(both.describe().contains("extra=[ADaMIG 1.0] missing=[ADaMIG 1.2, ADaMIG 1.3]"),
                both.describe());
    }


    /**
     * A declared key outside the cited workbook's columns is retained and never judged: the FDA
     * rule's {@code SDTMIG 3.4} (the v1.6 workbook stops at 3.3) and the PMDA SDTM rule's
     * {@code ADaMIG} one-hop entries. The linkage references (CG ids on an FDA rule, the FDA
     * one-hop block on a PMDA rule) are ignored as authority.
     */
    @Test
    void sheetSilentAxesAreRetainedAndLinkageReferencesAreNotAuthority(@TempDir Path dir)
        throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD0002",
                check("FDA-SD0002", fda("SDTMIG", "3.4", "SD0002"), fda("SDTMIG", "3.3", "SD0002"),
                        fda("SDTMIG", "3.2", "SD0002"), cg("CDISC", "SDTMIG", "3.4", "CG0002"),
                        cg("CDISC", "SDTMIG", "3.3", "CG0002"),
                        cg("CDISC", "SDTMIG", "3.2", "CG0002")));
        checkFile(checks, "PMDA", "PMDA-SD0002", check("PMDA-SD0002",
                pmda("SDTMIG", "3.3", "SD0002"), pmda("SDTMIG", "3.4", "SD0002"),
                new Entry("FDA", "ADaMIG", "1.3", StandardsMembershipOracle.FDA_ORIGIN, "SD0002")));

        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        Finding f = finding(oracle, "FDA-SD0002");
        assertEquals(Verdict.MATCH, f.verdict(), f.describe());
        assertEquals(keys("SDTMIG 3.4"), f.retained());
        assertEquals(List.of("FDA:SD0002"), f.cited(),
                "the CG ids are linkage, not authority — CG0002's 3.4-less row must not be compared");
        Finding p = finding(oracle, "PMDA-SD0002");
        assertEquals(Verdict.MATCH, p.verdict(), p.describe());
        assertEquals(keys("ADaMIG 1.3"), p.retained());
        assertEquals(List.of("PMDA-SDTM:SD0002"), p.cited());
        assertTrue(p.describe().contains("retained=[ADaMIG 1.3]"));
    }


    /** Two authority ids on one rule (the SEND base/{@code .1} encoding) compare as a union. */
    @Test
    void severalAuthorityIdsCompareAgainstTheUnionOfTheirRows(@TempDir Path dir) throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "CDISC", "CDISC-SEND-0001", check("CDISC-SEND-0001",
                send("SENDIG", "3.1.1", "SEND1.1"), send("SENDIG", "3.1", "SEND1.1"),
                send("SENDIG", "3.0", "SEND1"), send("SENDIG-GENETOX", "1.0", "SEND1.1"),
                send("SENDIG-DART", "1.1", "SEND1.1"), send("SENDIG-DART", "1.2", "SEND1.1")));
        // The CDISC-SEND-0324 shape: a 3.1.1-born rule that never entered GENETOX/DART 1.2.
        checkFile(checks, "CDISC", "CDISC-SEND-0002A",
                check("CDISC-SEND-0002A", send("SENDIG", "3.1.1", "SEND2")));
        // The CDISC-SEND-0326 shape: declared into versions that predate it, absent from two it
        // owns.
        checkFile(checks, "CDISC", "CDISC-SEND-0002",
                check("CDISC-SEND-0002", send("SENDIG", "3.1.1", "SEND2"),
                        send("SENDIG", "3.1", "SEND2"), send("SENDIG", "3.0", "SEND2")));

        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        Finding full = finding(oracle, "CDISC-SEND-0001");
        assertEquals(Verdict.MATCH, full.verdict(), full.describe());
        assertEquals(List.of("CDISC-SEND:SEND1", "CDISC-SEND:SEND1.1"), full.cited());
        Finding partial = finding(oracle, "CDISC-SEND-0002A");
        assertEquals(Verdict.SUSPECT, partial.verdict(), partial.describe());
        assertEquals(keys("SENDIG-DART 1.2", "SENDIG-GENETOX 1.0"), partial.missing());
        Finding predates = finding(oracle, "CDISC-SEND-0002");
        assertEquals("BOTH", predates.reason());
        assertEquals(keys("SENDIG 3.0", "SENDIG 3.1"), predates.extra());
        assertEquals(keys("SENDIG-DART 1.2", "SENDIG-GENETOX 1.0"), predates.missing());
    }


    /**
     * The {@code -A} partition convention: rules of one family citing the same row may split its
     * versions. Each is judged on its OWN block — a member equal to the row is MATCH, a strict
     * subset that covers what its siblings leave uncovered is EXEMPT, and a member declaring a
     * version the row does not mark is SUSPECT whatever its siblings do.
     */
    @Test
    void splitSiblingsAreExemptOnlyWhileTheGroupStaysInsideAndCoversTheRow(@TempDir Path dir)
        throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        // FDA-SD0095 / -A shape: parent = row, side rule = the sheet-silent 3.4 only.
        checkFile(checks, "FDA", "FDA-SD0001",
                check("FDA-SD0001", fda("SDTMIG", "3.1.2", "SD0001"),
                        fda("SDTMIG", "3.1.3", "SD0001"), fda("SDTMIG", "3.2", "SD0001"),
                        fda("SDTMIG", "3.3", "SD0001"), fda("SENDIG", "3.0", "SD0001"),
                        fda("SENDIG", "3.1", "SD0001"), fda("SENDIG", "3.1.1", "SD0001"),
                        fda("SENDIG-AR", "1.0", "SD0001"), fda("SENDIG-DART", "1.1", "SD0001")));
        checkFile(checks, "FDA", "FDA-SD0001-A",
                check("FDA-SD0001-A", fda("SDTMIG", "3.4", "SD0001")));
        // CORE-000550 / CORE-001079 shape: a disjoint partition of CG0001's three versions.
        checkFile(checks, "CORE", "CORE-000001", check("CORE-000001",
                cg("CDISC", "SDTMIG", "3.2", "CG0001"), cg("CDISC", "SDTMIG", "3.4", "CG0001")));
        checkFile(checks, "CORE", "CORE-000002",
                check("CORE-000002", cg("CDISC", "SDTMIG", "3.3", "CG0001")));
        // A partition with a hole: CG0002's 3.2 is covered by nobody — both are UNDER.
        checkFile(checks, "CORE", "CORE-000003",
                check("CORE-000003", cg("CDISC", "SDTMIG", "3.3", "CG0002")));
        checkFile(checks, "CORE", "CORE-000004",
                check("CORE-000004", cg("CDISC", "SDTMIG", "3.3", "CG0002")));
        // A sibling that steps outside the row is SUSPECT even though the group covers it.
        checkFile(checks, "PMDA", "PMDA-SD0002", check("PMDA-SD0002",
                pmda("SDTMIG", "3.3", "SD0002"), pmda("SDTMIG", "3.4", "SD0002")));
        checkFile(checks, "PMDA", "PMDA-SD0002-A", check("PMDA-SD0002-A",
                pmda("SDTMIG", "3.2", "SD0002"), pmda("SDTMIG", "3.4", "SD0002")));
        // Siblings are found by the shared row, never by id suffix: AD0001B is its own row.
        checkFile(checks, "PMDA", "PMDA-AD0001", check("PMDA-AD0001",
                pmda("ADaMIG", "1.0", "AD0001"), pmda("ADaMIG", "1.1", "AD0001")));
        checkFile(checks, "PMDA", "PMDA-AD0001B", check("PMDA-AD0001B",
                pmda("ADaMIG", "1.2", "AD0001B"), pmda("ADaMIG", "1.3", "AD0001B")));

        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        assertEquals(Verdict.MATCH, finding(oracle, "FDA-SD0001").verdict());
        Finding side = finding(oracle, "FDA-SD0001-A");
        assertEquals(Verdict.EXEMPT, side.verdict(), side.describe());
        assertEquals("SPLIT_SIBLING:FDA-SD0001", side.reason());
        assertEquals(keys("SDTMIG 3.4"), side.retained());
        assertEquals("SPLIT_SIBLING:CORE-000002", finding(oracle, "CORE-000001").reason());
        assertEquals("SPLIT_SIBLING:CORE-000001", finding(oracle, "CORE-000002").reason());
        assertEquals(Verdict.SUSPECT, finding(oracle, "CORE-000003").verdict(),
                "a hole in the partition is nobody's exemption");
        assertEquals(Verdict.SUSPECT, finding(oracle, "CORE-000004").verdict());
        assertEquals(Verdict.MATCH, finding(oracle, "PMDA-SD0002").verdict());
        Finding outside = finding(oracle, "PMDA-SD0002-A");
        assertEquals(Verdict.SUSPECT, outside.verdict(), outside.describe());
        assertEquals(keys("SDTMIG 3.2"), outside.extra());
        Finding displaced = finding(oracle, "PMDA-AD0001");
        assertEquals(Verdict.SUSPECT, displaced.verdict(), "AD0280-shape displacement");
        assertEquals(keys("ADaMIG 1.1"), displaced.extra());
        Finding displacedB = finding(oracle, "PMDA-AD0001B");
        assertEquals(keys("ADaMIG 1.1"), displacedB.missing());
        // The split groups are family-local: a CORE rule and a CDISC rule citing CG0001 are not
        // siblings of each other.
        checkFile(checks, "CDISC", "CDISC-CG0001",
                check("CDISC-CG0001", cg("CDISC", "SDTMIG", "3.2", "CG0001")));
        Finding crossFamily = finding(StandardsMembershipOracle.classify(checks, sheets),
                "CDISC-CG0001");
        assertEquals(Verdict.SUSPECT, crossFamily.verdict(), crossFamily.describe());
    }


    /** The unreachable population is EXEMPT with a reason code, never silently MATCH. */
    @Test
    void unreachableRulesAreExemptWithAReasonCode(@TempDir Path dir) throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9001",
                check("FDA-SD9001", new Entry("FDA", "SDTMIG", "3.4", "", "")));
        checkFile(checks, "CORE", "CORE-000900", check("CORE-000900", new Entry("FDA", "SDTMIG",
                "3.4", StandardsMembershipOracle.FDA_BUSINESS_ORIGIN, "FDAB001")));
        checkFile(checks, "CORE", "CORE-000901",
                check("CORE-000901",
                        new Entry("CDISC", "TIG", "1.0", StandardsMembershipOracle.TIG_ORIGIN,
                                "TIG0001"),
                        new Entry("FDA", "SDTMIG", "3.4",
                                StandardsMembershipOracle.FDA_BUSINESS_ORIGIN, "FDAB001")));
        // A CORE rule citing only an FDA Validator id: not a CORE authority origin.
        checkFile(checks, "CORE", "CORE-000902",
                check("CORE-000902", fda("SDTMIG", "3.3", "SD0002")));
        // No Standards block at all.
        checkFile(checks, "CORE", "CORE-000903", "Core:\n  Id: \"CORE-000903\"\n");

        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        assertEquals(5, oracle.count(Verdict.EXEMPT));
        assertEquals("NO_AUTHORITY_REF", finding(oracle, "FDA-SD9001").reason());
        assertEquals("FDA_BUSINESS_RULES_ONLY", finding(oracle, "CORE-000900").reason());
        assertEquals("TIG_ONLY", finding(oracle, "CORE-000901").reason());
        assertEquals("NO_AUTHORITY_REF:FDA Validator Rules",
                finding(oracle, "CORE-000902").reason());
        assertEquals("NO_AUTHORITY_REF", finding(oracle, "CORE-000903").reason());
        assertEquals(keys("SDTMIG 3.4"), finding(oracle, "FDA-SD9001").retained(),
                "everything an unreachable rule declares is retained");
        assertEquals(0, oracle.count(Verdict.MATCH), "unreachable is not clean");
    }


    /** {@code DRAFT} is classified and reported but carved out of {@link Oracle#shipped}. */
    @Test
    void draftFamilyIsClassifiedButNotShipped(@TempDir Path dir) throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "DRAFT", "DRAFT-900001",
                check("DRAFT-900001", fda("SDTMIG", "3.2", "SD0002")));
        checkFile(checks, "FDA", "FDA-SD0002", check("FDA-SD0002", fda("SDTMIG", "3.2", "SD0002")));
        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        assertEquals(2, oracle.count(Verdict.SUSPECT));
        assertEquals(List.of("FDA-SD0002"),
                oracle.shipped(Verdict.SUSPECT).stream().map(Finding::ruleId).toList());
        assertEquals(Map.of("DRAFT", 1, "FDA", 1), oracle.byFamily(Verdict.SUSPECT));
    }


    @Test
    void reportPrintsTallyAndEveryNonMatch(@TempDir Path dir) throws IOException
    {
        Sheets sheets = fixtureSheets(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD0002", check("FDA-SD0002", fda("SDTMIG", "3.2", "SD0002"),
                fda("SDTMIG", "3.3", "SD0002")));
        checkFile(checks, "FDA", "FDA-SD0003", check("FDA-SD0003", fda("SDTMIG", "3.2", "SD0003")));
        Oracle oracle = StandardsMembershipOracle.classify(checks, sheets);
        var buffer = new java.io.ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try
        {
            StandardsMembershipOracle.report(oracle);
        }
        finally
        {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("[oracle] 2 rules: 1 match, 1 suspect, 0 exempt, 0 unresolved"),
                out);
        assertTrue(out.contains("[oracle] SUSPECT    FDA-SD0003 FDA:SD0003 SUSPECT:OVER"), out);
        assertFalse(out.contains("FDA-SD0002 FDA:SD0002 MATCH"), "matches are not listed");
    }


    @Test
    void mainWritesTheProjectionUnlessDryRun(@TempDir Path dir) throws IOException
    {
        workbooks(dir);
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD0003", check("FDA-SD0003", fda("SDTMIG", "3.2", "SD0003")));
        Path tsv = dir.resolve("out.tsv");
        System.setProperty("cdisc.docs.dir", dir.toString());
        System.setProperty("oracle.checksRoot", checks.toString());
        System.setProperty("oracle.baseline", tsv.toString());
        System.setProperty("oracle.dryRun", "true");
        try
        {
            StandardsMembershipOracle.main(new String[0]);
            assertFalse(Files.exists(tsv), "dry run writes nothing");
            System.clearProperty("oracle.dryRun");
            StandardsMembershipOracle.main(new String[0]);
            assertTrue(Files.exists(tsv));
        }
        finally
        {
            System.clearProperty("cdisc.docs.dir");
            System.clearProperty("oracle.checksRoot");
            System.clearProperty("oracle.baseline");
            System.clearProperty("oracle.dryRun");
        }
        assertEquals(6, StandardsMembershipOracle.readBaseline(tsv).byBinding().size());
    }

    // ---- the shipped corpus ------------------------------------------------


    /**
     * ⚠ Neuter-and-watch. A copy of the shipped {@code CDISC-SEND-0326} — corrected by
     * {@code M2-D17}/{@code M3-D10} to the sheet's three pairs — with its {@code SENDIG 3.0} entry
     * spliced back in must be classified SUSPECT. Without this the "zero SUSPECT" assertion below
     * could be vacuous.
     */
    @Test
    void reinjectingTheCorrectedPredatingVersionIsDetectedAsSuspect(@TempDir Path dir)
        throws IOException
    {
        Path shipped = basedir().resolve("rules-src/checks/CDISC/CDISC-SEND-0326.yaml");
        String text = Files.readString(shipped, StandardCharsets.UTF_8);
        assertFalse(text.contains("Version: \"3.0\""),
                "the shipped rule must be the corrected one for this test to mean anything");
        String spurious = """
                  - Name: "SENDIG"
                    Version: "3.0"
                    References:
                    - Origin: "SEND Conformance Rules"
                      Rule_Identifier:
                        Id: "SEND326"
                        Version: "1"
                      Version: "5.0"
                """;
        String marker = "  - Name: \"SENDIG\"\n    Version: \"3.1.1\"\n";
        assertTrue(text.contains(marker));
        Path checks = dir.resolve("checks");
        checkFile(checks, "CDISC", "CDISC-SEND-0326", text.replace(marker, spurious + marker));

        Oracle oracle = StandardsMembershipOracle.classify(checks, shippedProjection());
        assertEquals(1, oracle.count(Verdict.SUSPECT), "the reinjected 3.0 must red: "
                + oracle.findings().stream().map(Finding::describe).toList());
        Finding f = oracle.of(Verdict.SUSPECT).get(0);
        assertEquals("CDISC-SEND-0326", f.ruleId());
        assertEquals(keys("SENDIG 3.0"), f.extra());
    }


    /**
     * THE CHECK ({@code M2-D17}). Every shipped {@code Standards} block agrees with its sheet
     * row(s) on the axes the workbook carries, or is an authored split-sibling exemption, or is
     * unreachable with a named reason. Both directions fail: an omitted version is silent coverage
     * loss, an added one is a version the authority never granted.
     *
     * <p>
     * ⚠ {@code DRAFT} is carved out of the failing assertion only: it is a staging family under its
     * own lane's churn, so its suspects are reported through {@link Oracle#byFamily} in the message
     * rather than gated here.
     * </p>
     */
    @Test
    void shippedStandardsBlocksAgreeWithTheSheetVersionColumns() throws IOException
    {
        Oracle oracle = StandardsMembershipOracle.classify(basedir().resolve("rules-src/checks"),
                shippedProjection());

        assertEquals(List.of(),
                oracle.shipped(Verdict.SUSPECT).stream().map(Finding::describe).toList(),
                "a Standards block disagrees with its sheet row(s) — re-derive it (M2-D17); "
                        + "suspect by family: " + oracle.byFamily(Verdict.SUSPECT));
        assertEquals(List.of(),
                oracle.shipped(Verdict.UNRESOLVED).stream().map(Finding::describe).toList(),
                "every authority id resolves in its workbook");
        // The authored partitions, named — never silently passed.
        // ⭐ Grew 14 -> 32 on 2026-08-26 (plans/PLAN-source-proposed-block.md ruling I), and the
        // membership rule was RE-DERIVED rather than the list nudged: a rule is EXEMPT exactly when
        // its declared versions are a STRICT SUBSET of its cited row's - an authored partition of
        // that row. Verified against all 35 minted-band rules: every one that declares fewer
        // versions than its row grants is here (FDA-SD9712 declares 1 of 10, PMDA-SD9712 1 of 5,
        // FDA-SE9701/9702 4 of 10), every one that declares the row's full set is not, and
        // FDA-SD9713 is absent because it has NO citation at all - it is a missing-row rule.
        // ⚑ The two assertions above still hold, which is the point: no repaired citation is
        // SUSPECT and none is UNRESOLVED, so the repair claims no version the authority never
        // granted. These 18 are subsets, never supersets.
        assertEquals(List.of("CDISC-AD0039-A", "CDISC-AD0040-A", "CDISC-SEND-9430",
                "CDISC-SEND-9433", "CDISC-SEND-9435", "CORE-000550", "CORE-001079", "FDA-SD0069",
                "FDA-SD0069-A", "FDA-SD0095-A", "FDA-SD1041-A", "FDA-SD1121", "FDA-SD1121-A",
                "FDA-SD9701", "FDA-SD9702", "FDA-SD9703", "FDA-SD9704", "FDA-SD9707", "FDA-SD9708",
                "FDA-SD9709", "FDA-SD9710", "FDA-SD9711", "FDA-SD9712", "FDA-SE9701", "FDA-SE9702",
                "PMDA-AD0252-A", "PMDA-AD0252-C", "PMDA-SD0095", "PMDA-SD0095-A", "PMDA-SD9707",
                "PMDA-SD9708", "PMDA-SD9712"),
                oracle.of(Verdict.EXEMPT).stream()
                        .filter(f -> f.reason().startsWith("SPLIT_SIBLING:")).map(Finding::ruleId)
                        .sorted().toList(),
                "the split-sibling exemptions are a closed, named list");
        // The two unmeasurable CORE slices are EXEMPT with a reason, never MATCH.
        assertTrue(
                oracle.of(Verdict.EXEMPT).stream()
                        .anyMatch(f -> "FDA_BUSINESS_RULES_ONLY".equals(f.reason())),
                "the FDA-Business-only CORE slice is unmeasurable and must say so");
        assertEquals(oracle.total(),
                oracle.count(Verdict.MATCH) + oracle.count(Verdict.EXEMPT)
                        + oracle.byFamily(Verdict.SUSPECT).getOrDefault("DRAFT", 0)
                        + oracle.byFamily(Verdict.UNRESOLVED).getOrDefault("DRAFT", 0),
                "every shipped rule is an exact match or a named exemption");
    }


    private static Path basedir()
    {
        return Path.of(System.getProperty("projectBasedir"));
    }


    private static Sheets shippedProjection() throws IOException
    {
        return StandardsMembershipOracle
                .readBaseline(basedir().resolve(StandardsMembershipOracle.BASELINE));
    }
}
