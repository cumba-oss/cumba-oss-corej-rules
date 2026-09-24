package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.CgParse;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.Fallback;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.Result;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.Selection;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.SheetBinding;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.SourceRow;
import net.cumba.corej.core.expr.convert.OriginalTextBackfill.VersionKey;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginalTextBackfillTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // ---- workbook fixtures ------------------------------------------------

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


    private static Path write(Path dir, String file, XSSFWorkbook wb) throws IOException
    {
        Path p = dir.resolve(file);
        try (OutputStream os = Files.newOutputStream(p))
        {
            wb.write(os);
        }
        wb.close();
        return p;
    }


    private static Path cdiscBook(Path dir) throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "SDTMIG Conformance Rules v2.0", List.of(
                List.of("Rule ID", "SDTMIG Version", "Class", "Domain", "Variable", "Condition",
                        "Rule"),
                List.of("CG9001", "3.2", "OLDCLASS", "AE", "OLDVAR", "old cond", "old rule 3.2"),
                List.of("CG9001", "3.4", "ALL", "DM", "DOMAIN", "new cond", "new rule 3.4"),
                List.of("CG9002", "3.4", "ALL", "", "", "", "unconditional rule"),
                List.of("CG0021", "3.4", "ALL", "DM", "", "c", "r21"),
                List.of("CG0040", "3.4", "ALL", "DM", "", "c", "r40"),
                List.of("", "", "", "", "", "", "")));
        return write(dir, "SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx", wb);
    }

    private static final String RULE_SET = "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)";

    private static Path adamBook(Path dir) throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "Rules Catalogue", List.of(List.of("Identifiers", "", "", "", "", "", "", ""),
                List.of("Rule ID", RULE_SET, "Class", "Subclass", "SEND/SDTM Domain",
                        "Variable or Item", "Natural Language Rule (Failure Criteria)",
                        "Rule (Failure Criteria)", "Condition (Failure)"),
                List.of("1", "1.0", "BDS", "sub", "ADSL", "AVAL", "nl1", "failure rule",
                        "fail cond"),
                List.of("2", "1.2", "ADSL", "", "", "", "nat lang 2", "", ""),
                // id 9: v1 row Class = BDS; v1.1 supersedes with the fuller Class value.
                List.of("9", "1", "BDS", "", "", "", "nl9", "r9", "c9"), List.of("9", "1.1",
                        "BDS; MEDICAL DEVICE BDS", "", "", "", "nl9", "r9", "c9 v1.1")));
        return write(dir, "ADaM Conformance Rules v5.0.xlsx", wb);
    }


    /**
     * The SEND fixture. Version cells are spelled exactly as the workbook spells them
     * ({@code SENDIG v3.1.1}), because {@code OWN_PRIMARY_ROW} matches them against the check's
     * {@code Name + " v" + Version}.
     *
     * <ul>
     * <li>{@code SEND1} / {@code SEND2} — one row each (the pre-M3-D24 fixture).</li>
     * <li>{@code SEND3} — the 0138 shape: three version rows, the two later ones blank on
     * {@code Rule}, so the lowest row is the only one carrying it.</li>
     * <li>{@code SEND4} — a rule that does not ship in the lowest row's version.</li>
     * <li>{@code SEND5} — TWO rows sharing one version cell, one of them blank on
     * {@code Condition}: the same-version (b1) donor shape.</li>
     * <li>{@code SEND6} — the future {@code 0138} shape (b1) exists for: the rule's own lowest
     * declared row (v3.1) is blank on {@code Rule}, the undeclared v3.0 row below it carries the
     * cell, and a DART row carries a different one.</li>
     * <li>{@code SEND7} — three SENDIG rows; the v3.1.1 row is blank on {@code Rule} and BOTH lower
     * rows carry one (nearest-first), while the v3.0 row is blank on {@code Condition} and only the
     * HIGHER rows carry it (never borrowed upward).</li>
     * </ul>
     */
    private static Path sendBook(Path dir) throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "_GENETOXv1.0_DARTv1.2", List.of(
                List.of("CDISC SEND Rule ID", "Class", "Domain", "Variable",
                        "Plain Text Explanation of Condition/Rule", "Condition", "Rule",
                        "SENDIG Version"),
                List.of("SEND1", "ALL", "TS", "TSVAL", "plain expl", "send cond", "send rule",
                        "SENDIG v3.1.1"),
                List.of("SEND2", "ALL", "", "", "plain only", "", "", "SENDIG v3.1.1"),
                // SEND3: rows deliberately NOT in version order, as the real workbook has them.
                List.of("SEND3", "FND", "LB", "LBSEQ", "expl dart", "cond dart", "",
                        "SENDIG-DART v1.2"),
                List.of("SEND3", "FND", "LB", "LBSEQ", "expl v30", "cond v30", "seq is unique",
                        "SENDIG v3.0"),
                List.of("SEND3", "FND", "LB", "LBSEQ", "expl v31", "cond v31", "", "SENDIG v3.1"),
                // SEND4: the v3.0 row exists but the rule does not declare SENDIG 3.0.
                List.of("SEND4", "ALL", "DM", "ARM", "expl v30", "c30", "r30", "SENDIG v3.0"),
                List.of("SEND4", "ALL", "DM", "ARM", "expl dart11", "c11", "r11",
                        "SENDIG-DART v1.1"),
                List.of("SEND4", "ALL", "DM", "ARM", "expl dart12", "c12", "r12",
                        "SENDIG-DART v1.2"),
                // SEND5: two rows on ONE version cell; the chosen (lower) row is blank on
                // Condition, the same-version sibling carries it.
                List.of("SEND5", "ALL", "BW", "BWSTRESN", "expl a", "", "rule a", "SENDIG v3.0"),
                List.of("SEND5", "ALL", "BW", "BWSTRESN", "expl b", "cond b", "rule b",
                        "SENDIG v3.0"),
                // SEND6 (rows 12-14): the 0138 shape seen from a rule that declares v3.1 only.
                List.of("SEND6", "FND", "LB", "LBSEQ", "expl v31", "cond v31", "", "SENDIG v3.1"),
                List.of("SEND6", "FND", "LB", "LBSEQ", "expl v30", "cond v30", "rule v30",
                        "SENDIG v3.0"),
                List.of("SEND6", "FND", "LB", "LBSEQ", "expl dart", "cond dart", "rule dart",
                        "SENDIG-DART v1.2"),
                // SEND7 (rows 15-17): nearest lower donor wins; nothing is borrowed upward.
                List.of("SEND7", "FND", "LB", "LBSEQ", "expl v30", "", "rule v30", "SENDIG v3.0"),
                List.of("SEND7", "FND", "LB", "LBSEQ", "expl v31", "cond v31", "rule v31",
                        "SENDIG v3.1"),
                List.of("SEND7", "FND", "LB", "LBSEQ", "expl v311", "cond v311", "",
                        "SENDIG v3.1.1")));
        return write(dir, "SEND_Conformance_Rules_v5.0.xlsx", wb);
    }


    private static Path fdaBook(Path dir) throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "FDA Validator Rules v1.6", List.of(List.of("version 1.6"),
                List.of("FDA Validator Rule ID", "Publisher", "Publisher ID",
                        "FDA Validator Rule Message", "FDA Validator Rule Description", "Domains"),
                List.of("CT9001", "CDISC", "CG0021, 40", "short message", "long description",
                        "ALL"),
                List.of("SD9500", "FDA", "FDAB017", "fda msg", "fda desc", "DM"),
                // blank message -> the key is omitted (no empty string).
                List.of("SD9600", "", "", "", "d2", "ALL")));
        return write(dir, "FDA-validator-rules-v1-6.xlsx", wb);
    }


    private static Path pmdaBook(Path dir) throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        // ⚑ Plan C phase 1: the "PMDA Severity" column is part of the binding, so the fixture
        // must carry it — header detection fails without it. All three live values are used.
        sheet(wb, "SDTM Rules",
                List.of(List.of("banner"),
                        List.of("RULE ID", "MESSAGE", "DESCRIPTION", "DOMAINS", "PMDA Severity"),
                        List.of("SD9500", "sdtm msg", "sdtm desc", "GLOBAL", "Warning"),
                        List.of("CT9001", "ct msg", "ct desc", "ALL", "Error"),
                        List.of("SD9999", "lonely msg", "lonely desc", "DM", "Reject")));
        sheet(wb, "ADaM Rules",
                List.of(List.of("banner"),
                        List.of("RULE ID", "MESSAGE", "DESCRIPTION", "DOMAINS", "PMDA Severity"),
                        List.of("AD9001", "adam msg", "adam desc", "ADSL, BDS", "Error")));
        return write(dir, "PMDA-ValidationRules-v6-0.xlsx", wb);
    }


    private static Map<String, Map<String, List<SourceRow>>> loadAll(Path dir) throws IOException
    {
        cdiscBook(dir);
        adamBook(dir);
        sendBook(dir);
        fdaBook(dir);
        pmdaBook(dir);
        return OriginalTextBackfill.loadAll(dir);
    }

    // ---- loadSheet --------------------------------------------------------


    /** Selects through {@link OriginalTextBackfill#select} with no declared-version set. */
    private static SequencedMap<String, String> pick(String binding, List<SourceRow> rows)
    {
        return OriginalTextBackfill.select(OriginalTextBackfill.binding(binding), rows, Set.of())
                .fields();
    }


    @Test
    void cdiscLoaderPicksHighestVersionAndOmitsBlankCells(@TempDir Path dir) throws IOException
    {
        var src = OriginalTextBackfill.loadSheet(cdiscBook(dir),
                OriginalTextBackfill.binding("CDISC-CG"));
        assertEquals(2, src.get("CG9001").size(), "the loader keeps EVERY row per id");
        var cg9001 = pick("CDISC-CG", src.get("CG9001"));
        assertEquals("new rule 3.4", cg9001.get("Rule"), "highest-version row wins");
        assertEquals("new cond", cg9001.get("Condition"));
        assertEquals("DOMAIN", cg9001.get("Variable"));
        assertEquals("DM", cg9001.get("Domain"));
        // sheet-column order preserved
        assertEquals(List.of("Class", "Domain", "Variable", "Condition", "Rule"),
                new ArrayList<>(cg9001.keySet()));
        var cg9002 = pick("CDISC-CG", src.get("CG9002"));
        assertEquals("unconditional rule", cg9002.get("Rule"));
        assertFalse(cg9002.containsKey("Condition"), "blank cell omits the key");
        assertFalse(cg9002.containsKey("Domain"));
    }


    @Test
    void adamLoaderStripsHeaderBannerAndPicksHighestVersion(@TempDir Path dir) throws IOException
    {
        var src = OriginalTextBackfill.loadSheet(adamBook(dir),
                OriginalTextBackfill.binding("CDISC-AD"));
        assertEquals("failure rule", pick("CDISC-AD", src.get("1")).get("Rule_Failure_Criteria"));
        assertEquals("fail cond", pick("CDISC-AD", src.get("1")).get("Condition_Failure"));
        assertEquals("ADSL", pick("CDISC-AD", src.get("1")).get("SEND_SDTM_Domain"));
        assertEquals("nat lang 2",
                pick("CDISC-AD", src.get("2")).get("Natural_Language_Rule_Failure_Criteria"));
        assertFalse(pick("CDISC-AD", src.get("2")).containsKey("Rule_Failure_Criteria"),
                "blank omitted");
        // highest Rule Set row wins -> the fuller v1.1 Class value
        assertEquals("BDS; MEDICAL DEVICE BDS", pick("CDISC-AD", src.get("9")).get("Class"));
        assertEquals("c9 v1.1", pick("CDISC-AD", src.get("9")).get("Condition_Failure"));
    }


    @Test
    void sendLoaderReadsOwnColumns(@TempDir Path dir) throws IOException
    {
        var src = OriginalTextBackfill.loadSheet(sendBook(dir),
                OriginalTextBackfill.binding("CDISC-SEND"));
        assertEquals("send rule", pick("CDISC-SEND", src.get("SEND1")).get("Rule"));
        assertEquals("send cond", pick("CDISC-SEND", src.get("SEND1")).get("Condition"));
        assertEquals("plain expl",
                pick("CDISC-SEND", src.get("SEND1")).get("Plain_Text_Explanation"));
        assertEquals("plain only",
                pick("CDISC-SEND", src.get("SEND2")).get("Plain_Text_Explanation"));
        assertFalse(pick("CDISC-SEND", src.get("SEND2")).containsKey("Rule"),
                "blank Rule omitted (no same-version sibling carries it)");
        assertFalse(pick("CDISC-SEND", src.get("SEND2")).containsKey("Condition"));
        assertEquals(3, src.get("SEND3").size(), "all three version rows are kept");
        assertEquals(4, src.get("SEND3").getFirst().sheetRow(),
                "1-based sheet row, in sheet order");
        assertEquals("SENDIG-DART v1.2", src.get("SEND3").getFirst().versionCell());
    }


    @Test
    void fdaLoaderReadsOwnColumnsOnlyNoLinkageKeys(@TempDir Path dir) throws IOException
    {
        var src = OriginalTextBackfill.loadSheet(fdaBook(dir), OriginalTextBackfill.binding("FDA"));
        var r = pick("FDA", src.get("SD9500"));
        assertEquals(
                List.of("FDA_Validator_Rule_Message", "FDA_Validator_Rule_Description", "Domains"),
                new ArrayList<>(r.keySet()), "own-sheet fields only, in sheet order");
        assertEquals("fda msg", r.get("FDA_Validator_Rule_Message"));
        assertEquals("fda desc", r.get("FDA_Validator_Rule_Description"));
        assertEquals("DM", r.get("Domains"));
        assertFalse(r.containsKey("Publisher"), "Publisher is linkage-only, not a Source key");
        assertFalse(r.containsKey("Publisher_ID"));
        var blank = pick("FDA", src.get("SD9600"));
        assertFalse(blank.containsKey("FDA_Validator_Rule_Message"), "blank message omitted");
        assertEquals("d2", blank.get("FDA_Validator_Rule_Description"));
    }


    @Test
    void pmdaKeysAreTitleCased(@TempDir Path dir) throws IOException
    {
        Path book = pmdaBook(dir);
        var sdtm = OriginalTextBackfill.loadSheet(book, OriginalTextBackfill.binding("PMDA-SDTM"));
        var adam = OriginalTextBackfill.loadSheet(book, OriginalTextBackfill.binding("PMDA-AD"));
        assertEquals("sdtm desc", pick("PMDA-SDTM", sdtm.get("SD9500")).get("Description"));
        assertEquals("adam msg", pick("PMDA-AD", adam.get("AD9001")).get("Message"));
        assertEquals("ADSL, BDS", pick("PMDA-AD", adam.get("AD9001")).get("Domains"),
                "multi-value cell kept as one scalar");
        assertNull(sdtm.get("AD9001"), "SDTM sheet does not carry the ADaM rule");
        // Plan C phase 1 — the severity column is read off a real sheet, all three values.
        assertEquals("Warning", pick("PMDA-SDTM", sdtm.get("SD9500")).get("Severity"));
        assertEquals("Error", pick("PMDA-SDTM", sdtm.get("CT9001")).get("Severity"));
        assertEquals("Reject", pick("PMDA-SDTM", sdtm.get("SD9999")).get("Severity"));
        assertEquals("Error", pick("PMDA-AD", adam.get("AD9001")).get("Severity"));
        assertEquals(List.of("Message", "Description", "Domains", "Severity"),
                new ArrayList<>(pick("PMDA-SDTM", sdtm.get("SD9500")).keySet()),
                "Source keys in the sheet's own column order");
    }


    @Test
    void loadAllReadsEveryBinding(@TempDir Path dir) throws IOException
    {
        var all = loadAll(dir);
        assertEquals(6, all.size());
        assertTrue(all.get("CDISC-CG").containsKey("CG9001"));
        assertTrue(all.get("CDISC-SEND").containsKey("SEND1"));
        assertTrue(all.get("FDA").containsKey("SD9500"));
        assertTrue(all.get("PMDA-AD").containsKey("AD9001"));
    }

    // ---- parseCgIds (retained; reused by StandardsLinkageBackfill) --------


    @Test
    void unparseablePublisherIdIsFlaggedByParser()
    {
        CgParse parse = OriginalTextBackfill.parseCgIds("see notes");
        assertTrue(parse.ids().isEmpty());
        assertEquals(List.of("see notes"), parse.unparseable());
    }


    @Test
    void parseCgIdsHandlesRangeDecimalBareFullAndUnparseable()
    {
        assertEquals(List.of("CG0021", "CG0040", "CG0049"),
                OriginalTextBackfill.parseCgIds("CG0021, 40, 49").ids());
        assertEquals(List.of("CG0621", "CG0622", "CG0623"),
                OriginalTextBackfill.parseCgIds("CG0621-CG0623").ids());
        assertEquals(List.of("CG0253.1"), OriginalTextBackfill.parseCgIds("253.1").ids());
        assertEquals(List.of("CG0071"), OriginalTextBackfill.parseCgIds("71").ids());
        assertEquals(List.of("CG0010", "CG0011"),
                OriginalTextBackfill.parseCgIds("CG0010 and CG0011").ids());
        assertEquals(List.of("CG0010", "CG0011"),
                OriginalTextBackfill.parseCgIds("CG0010 / CG0011; ").ids());
        CgParse bad = OriginalTextBackfill.parseCgIds("CG0010, FOO");
        assertEquals(List.of("CG0010"), bad.ids());
        assertEquals(List.of("FOO"), bad.unparseable());
    }

    // ---- joinId -----------------------------------------------------------


    private static ObjectNode check(String yaml) throws IOException
    {
        return (ObjectNode) YAML.readTree(yaml);
    }


    @Test
    void joinIdStripsCrPrefixForAdam() throws IOException
    {
        ObjectNode c = check("""
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "ADaMIG"
                    Version: "1.3"
                    References:
                    - Origin: "ADaM Conformance Rules"
                      Rule_Identifier:
                        Id: "CR42"
                """);
        assertEquals("42",
                OriginalTextBackfill.joinId(OriginalTextBackfill.binding("CDISC-AD"), c));
    }


    @Test
    void joinIdMatchesOriginAndIgnoresOthers() throws IOException
    {
        ObjectNode c = check("""
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier: {Id: "CG0001"}
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier: {Id: "CT2001"}
                """);
        assertEquals("CT2001", OriginalTextBackfill.joinId(OriginalTextBackfill.binding("FDA"), c));
    }


    @Test
    void joinIdReturnsNullWhenNoStandardsOrNoMatch() throws IOException
    {
        assertNull(OriginalTextBackfill.joinId(OriginalTextBackfill.binding("CDISC-CG"),
                check("Description: \"x\"\n")));
        ObjectNode c = check("""
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "Other Origin"
                      Rule_Identifier: {Id: "X"}
                """);
        assertNull(OriginalTextBackfill.joinId(OriginalTextBackfill.binding("CDISC-CG"), c));
    }

    // ---- backfill (textual splice) ---------------------------------------


    /** One synthetic sheet row: 1-based row number, version cell, then key/value pairs. */
    private static SourceRow row(int sheetRow, String version, String... kv)
    {
        return new SourceRow(sheetRow, version, VersionKey.parse(version), fields(kv));
    }


    private static SequencedMap<String, String> fields(String... kv)
    {
        SequencedMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- Plan C phase 1: Source.Severity on the two PMDA bindings -------


    /**
     * The {@code PMDA Severity} column is transcribed by the two PMDA bindings and by nobody else.
     *
     * <p>
     * ⚑ Measured 2026-08-25 across all nine workbooks coreJ reads: <b>PMDA is the only publisher
     * that grades its rules</b> — 1 060 severity cells over three tabs, zero blanks, exactly three
     * distinct values. The other eight workbooks carry no severity column at all, so a fourth
     * binding emitting {@code Severity} would be transcribing a column that does not exist.
     * </p>
     */
    @Test
    void onlyTheTwoPmdaBindingsEmitSeverity()
    {
        SequencedMap<String, String> emitters = new LinkedHashMap<>();
        for (SheetBinding b : OriginalTextBackfill.REGISTRY)
        {
            b.fields().stream().filter(f -> "Severity".equals(f.yamlKey())).findFirst()
                    .ifPresent(f -> emitters.put(b.name(), f.sheetColumn()));
        }
        assertEquals(Map.of("PMDA-SDTM", "PMDA Severity", "PMDA-AD", "PMDA Severity"), emitters,
                "exactly the two PMDA bindings must emit Severity, both from the same column");
    }


    /**
     * {@code Severity} is emitted <b>last</b>, which is the sheet's own column order
     * ({@code RULE ID | MESSAGE | DESCRIPTION | DOMAINS | PMDA Severity}) — the registry mirrors
     * the workbook so a reviewer can read the two side by side.
     */
    @Test
    void thePmdaBindingsEmitSeverityLastMirroringTheSheetColumnOrder()
    {
        for (SheetBinding b : OriginalTextBackfill.REGISTRY)
        {
            if (b.name().startsWith("PMDA"))
            {
                assertEquals(List.of("Message", "Description", "Domains", "Severity"),
                        b.fields().stream().map(OriginalTextBackfill.SourceField::yamlKey).toList(),
                        b.name() + " must mirror the sheet's column order");
            }
        }
    }


    /**
     * All three values the {@code PMDA Severity} column actually holds survive the splice verbatim,
     * in position, quoted like every other {@code Source} value.
     */
    @Test
    void theThreeSheetSeverityValuesRoundTripInPosition()
    {
        for (String value : List.of("Reject", "Error", "Warning"))
        {
            List<String> out = OriginalTextBackfill
                    .backfill(List.of("Description: d", "Standards: []"), fields("Message", "m",
                            "Description", "d", "Domains", "ALL", "Severity", value));
            assertEquals(
                    List.of("Description: d", "Source:", "  Message: \"m\"", "  Description: \"d\"",
                            "  Domains: \"ALL\"", "  Severity: \"" + value + "\"", "Standards: []"),
                    out, "severity " + value + " must round-trip in position");
        }
    }


    /**
     * A rule whose id matches no sheet row gets <b>no</b> {@code Source} block at all — the new
     * field must not turn an unjoinable rule into a partially-transcribed one. ⚑ The live
     * population is the twelve {@code PMDA-SD97xx} coreJ mints, which have no workbook row.
     */
    @Test
    void anUnjoinableRuleStillEmitsNoSourceBlockAtAll()
    {
        List<String> out = OriginalTextBackfill.backfill(
                List.of("Description: d", "Source:", "  Severity: \"Error\"", "Standards: []"),
                new LinkedHashMap<>());
        assertEquals(List.of("Description: d", "Standards: []"), out,
                "no fields ⇒ the whole Source block goes, Severity included");
    }


    @Test
    void backfillInsertsSourceBlockImmediatelyBeforeStandards()
    {
        List<String> in = List.of("Description: d", "Scope:", "  Domains:", "    Include:",
                "    - \"ALL\"", "Standards:", "- Organization: \"FDA\"");
        List<String> out = OriginalTextBackfill.backfill(in,
                fields("FDA_Validator_Rule_Message", "m", "Domains", "ALL"));
        assertEquals(List.of("Description: d", "Scope:", "  Domains:", "    Include:",
                "    - \"ALL\"", "Source:", "  FDA_Validator_Rule_Message: \"m\"",
                "  Domains: \"ALL\"", "Standards:", "- Organization: \"FDA\""), out);
    }


    @Test
    void backfillAppendsWhenNoStandardsAnchor()
    {
        List<String> in = List.of("Description: d", "Scope: {}");
        List<String> out = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        assertEquals(List.of("Description: d", "Scope: {}", "Source:", "  Domains: \"ALL\""), out);
    }


    @Test
    void backfillEmptyFieldsJustStrips()
    {
        List<String> in = List.of("Description: d", "Original_Condition: STALE", "Standards: []");
        List<String> out = OriginalTextBackfill.backfill(in, new LinkedHashMap<>());
        assertEquals(List.of("Description: d", "Standards: []"), out);
    }


    @Test
    void backfillStripsLegacyOriginalsAndPriorSourceThenIsIdempotent()
    {
        List<String> in = List.of("Description: d", "Original_Description: STALE",
                "Original_Condition: STALE", "Outcome:", "  Message: m",
                "  Original_Message: STALEMSG", "  Output_Variables:", "  - \"x\"", "Source:",
                "  Domains: \"OLD\"", "Standards: []");
        var fields = fields("Message", "fresh msg", "Domains", "ALL");
        List<String> once = OriginalTextBackfill.backfill(in, fields);
        assertEquals(List.of("Description: d", "Outcome:", "  Message: m", "  Output_Variables:",
                "  - \"x\"", "Source:", "  Message: \"fresh msg\"", "  Domains: \"ALL\"",
                "Standards: []"), once);
        List<String> twice = OriginalTextBackfill.backfill(once, fields);
        assertEquals(once, twice, "second pass byte-identical");
    }


    @Test
    void backfillLeavesHandAuthoredLinesUntouched()
    {
        List<String> in = List.of("Description: line one that folds onto",
                "  a second continuation line", "  and a third continuation line", "Standards: []");
        List<String> out = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        assertEquals("Description: line one that folds onto", out.get(0));
        assertEquals("  a second continuation line", out.get(1));
        assertEquals("  and a third continuation line", out.get(2));
        assertEquals("Source:", out.get(3));
    }


    @Test
    void stripSourceOnSourceBlockDropsAllChildren()
    {
        List<String> in = List.of("Source:", "  Class: \"ALL\"", "  Rule: \"do a thing\"",
                "Standards: []");
        assertEquals(List.of("Standards: []"),
                OriginalTextBackfill.stripSource(new ArrayList<>(in)));
    }


    @Test
    void valueEndStopsAtNextSiblingIndent()
    {
        List<String> lines = List.of("  Message: a", "    continued", "  Output_Variables:");
        assertEquals(2, OriginalTextBackfill.valueEnd(lines, 0));
    }

    // ---- process (end-to-end on a temp corpus) ----------------------------


    @Test
    void processWritesSourceBeforeStandardsAndReportsResiduals(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("CDISC");
        Files.createDirectories(fam);
        Files.writeString(fam.resolve("CDISC-CG9001.yaml"), """
                Core:
                  Id: "CDISC-CG9001"
                Description: "rephrased"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier: {Id: "CG9001"}
                """, StandardCharsets.UTF_8);
        Files.writeString(fam.resolve("CDISC-CG0000.yaml"), """
                Core:
                  Id: "CDISC-CG0000"
                Description: "orphan"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier: {Id: "CG0000"}
                """, StandardCharsets.UTF_8);
        Files.writeString(fam.resolve("CDISC-CG9999.yaml"), """
                Core:
                  Id: "CDISC-CG9999"
                Description: "no origin"
                Standards: []
                """, StandardCharsets.UTF_8);

        var src = Map.of("CG9001",
                List.of(row(2, "3.4", "Condition", "orig cond", "Rule", "orig rule")));
        Result r = OriginalTextBackfill.process(OriginalTextBackfill.binding("CDISC-CG"), src,
                checks, false);

        assertEquals(3, r.files());
        assertEquals(1, r.changed());
        assertEquals(List.of("CDISC-CG9999"), r.noId());
        assertEquals(List.of("CDISC-CG0000"), r.noRow());

        String written = Files.readString(fam.resolve("CDISC-CG9001.yaml"), StandardCharsets.UTF_8);
        assertTrue(written.contains("Source:"), written);
        assertTrue(written.indexOf("Source:") < written.indexOf("Standards:"),
                "Source before Standards");
        assertTrue(written.contains("  Rule: \"orig rule\""), written);
        assertTrue(r.report("CDISC-CG", false).contains("changed=1"));
    }


    @Test
    void processStripsLegacyKeysFromUnlistedDraftRule(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("CDISC");
        Files.createDirectories(fam);
        // A draft/unlisted rule (its sheet id is not in the source map) that still carries legacy
        // provenance from an earlier backfill: the stale keys must be stripped and NO Source block
        // added, even though the rule is reported as a no-row residual.
        Files.writeString(fam.resolve("CDISC-CG8888.yaml"), """
                Core:
                  Id: "CDISC-CG8888"
                Description: "draft"
                Original_Description: "STALE sheet text"
                Original_Condition: "STALE cond"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier: {Id: "CG8888"}
                """, StandardCharsets.UTF_8);

        Result r = OriginalTextBackfill.process(OriginalTextBackfill.binding("CDISC-CG"), Map.of(),
                checks, false);

        assertEquals(List.of("CDISC-CG8888"), r.noRow());
        assertEquals(1, r.changed(), "stale legacy keys are stripped even without a source row");
        String written = Files.readString(fam.resolve("CDISC-CG8888.yaml"), StandardCharsets.UTF_8);
        assertFalse(written.contains("Original_Description"), written);
        assertFalse(written.contains("Original_Condition"), written);
        assertFalse(written.contains("Source:"), "no Source block for a rule with no sheet row");
    }


    @Test
    void processDryRunDoesNotWrite(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("FDA");
        Files.createDirectories(fam);
        String original = """
                Core:
                  Id: "FDA-CT9001"
                Description: "rephrased"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier: {Id: "CT9001"}
                """;
        Files.writeString(fam.resolve("FDA-CT9001.yaml"), original, StandardCharsets.UTF_8);

        var src = Map.of("CT9001", List.of(row(3, "", "FDA_Validator_Rule_Message", "m")));
        Result r = OriginalTextBackfill.process(OriginalTextBackfill.binding("FDA"), src, checks,
                true);

        assertEquals(1, r.changed());
        assertEquals(original,
                Files.readString(fam.resolve("FDA-CT9001.yaml"), StandardCharsets.UTF_8),
                "dry run leaves the file untouched");
    }


    @Test
    void processPreservesTrailingNewlineState(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("FDA");
        Files.createDirectories(fam);
        String noNewline = "Core:\n  Id: \"FDA-CT9001\"\nStandards:\n- Organization: \"FDA\"\n"
                + "  Standards:\n  - Name: \"SDTMIG\"\n    Version: \"3.4\"\n    References:\n"
                + "    - Origin: \"FDA Validator Rules\"\n      Rule_Identifier: {Id: \"CT9001\"}";
        Path noNl = fam.resolve("FDA-CT9001.yaml");
        Files.writeString(noNl, noNewline, StandardCharsets.UTF_8);
        String withNewline = noNewline + "\n";
        Path withNl = fam.resolve("FDA-CT9002.yaml");
        Files.writeString(withNl, withNewline.replace("CT9001", "CT9002"), StandardCharsets.UTF_8);

        var src = Map.of("CT9001", List.of(row(3, "", "Domains", "ALL")), "CT9002",
                List.of(row(4, "", "Domains", "ALL")));
        OriginalTextBackfill.process(OriginalTextBackfill.binding("FDA"), src, checks, false);

        assertFalse(Files.readString(noNl, StandardCharsets.UTF_8).endsWith("\n"),
                "no trailing newline preserved");
        assertTrue(Files.readString(withNl, StandardCharsets.UTF_8).endsWith("\n"),
                "trailing newline preserved");
    }


    @Test
    void processSelectsByIdPatternSoSheetsCoexistInOneFolder(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("CDISC");
        Files.createDirectories(fam);
        Files.writeString(fam.resolve("CDISC-CG9001.yaml"), """
                Core:
                  Id: "CDISC-CG9001"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier: {Id: "CG9001"}
                """, StandardCharsets.UTF_8);
        Files.writeString(fam.resolve("CDISC-AD9001.yaml"), """
                Core:
                  Id: "CDISC-AD9001"
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "ADaMIG"
                    Version: "1.3"
                    References:
                    - Origin: "ADaM Conformance Rules"
                      Rule_Identifier: {Id: "9001"}
                """, StandardCharsets.UTF_8);

        Result cg = OriginalTextBackfill.process(OriginalTextBackfill.binding("CDISC-CG"),
                Map.of("CG9001", List.of(row(2, "3.4", "Rule", "cg rule"))), checks, true);
        Result ad = OriginalTextBackfill.process(OriginalTextBackfill.binding("CDISC-AD"),
                Map.of("9001", List.of(row(3, "1.3", "Rule_Failure_Criteria", "ad rule"))), checks,
                true);

        assertEquals(1, cg.files(), "CG binding only matches the CG file");
        assertEquals(1, cg.changed());
        assertEquals(1, ad.files(), "AD binding only matches the AD file");
        assertEquals(1, ad.changed());
    }


    @Test
    void processMissingDirectoryYieldsEmptyResult(@TempDir Path dir) throws IOException
    {
        Result r = OriginalTextBackfill.process(OriginalTextBackfill.binding("PMDA-AD"), Map.of(),
                dir.resolve("nope"), false);
        assertEquals(0, r.files());
        assertTrue(r.report("PMDA-AD", false).contains("files=0"));
    }

    // ---- stripSplitSuffix + split-id lookup-fallback ----------------------


    @Test
    void stripSplitSuffixOnlyDropsAnUppercaseLetterAnchoredToADigit()
    {
        // The digit arm: a letter directly after a digit.
        assertEquals("AD0252", OriginalTextBackfill.stripSplitSuffix("AD0252A"));
        assertEquals("AD0252", OriginalTextBackfill.stripSplitSuffix("AD0252"));
        assertEquals("AD0252AB", OriginalTextBackfill.stripSplitSuffix("AD0252AB"));
        assertEquals("SEND", OriginalTextBackfill.stripSplitSuffix("SEND"));
        assertEquals("A", OriginalTextBackfill.stripSplitSuffix("A"));
        // The hyphen arm (Fix #248): the coreJ-minted `-<letter>` spelling, still requiring a
        // digit before the hyphen so a genuinely letter-terminated id is never mis-stripped.
        assertEquals("CG0285", OriginalTextBackfill.stripSplitSuffix("CG0285-A"));
        assertEquals("AD0252", OriginalTextBackfill.stripSplitSuffix("AD0252-D"));
        assertEquals("SEND-A", OriginalTextBackfill.stripSplitSuffix("SEND-A"));
        assertEquals("FDA-SE2280A-DRAFT",
                OriginalTextBackfill.stripSplitSuffix("FDA-SE2280A-DRAFT"));
        assertEquals("-A", OriginalTextBackfill.stripSplitSuffix("-A"));
    }


    @Test
    void processSplitIdInheritsBaseRowThenIsIdempotent(@TempDir Path dir) throws IOException
    {
        // ⚠ A deliberately synthetic id (no live rule cites a suffixed Rule_Identifier since
        // Fix #248 rewrote the fabricated AD0252A…D citations), on the hyphenated spelling so
        // the widened fallback arm is wired through `process`, not only unit-tested.
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("PMDA");
        Files.createDirectories(fam);
        Files.writeString(fam.resolve("PMDA-AD9998-A.yaml"), """
                Core:
                  Id: "PMDA-AD9998-A"
                Standards:
                - Organization: "PMDA"
                  Standards:
                  - Name: "ADaMIG"
                    Version: "1.1"
                    References:
                    - Origin: "PMDA Validation Rules"
                      Rule_Identifier: {Id: "AD9998-A"}
                """, StandardCharsets.UTF_8);
        Files.writeString(fam.resolve("PMDA-AD9998.yaml"), """
                Core:
                  Id: "PMDA-AD9998"
                Standards:
                - Organization: "PMDA"
                  Standards:
                  - Name: "ADaMIG"
                    Version: "1.1"
                    References:
                    - Origin: "PMDA Validation Rules"
                      Rule_Identifier: {Id: "AD9998"}
                """, StandardCharsets.UTF_8);

        var src = Map.of("AD9998",
                List.of(row(2, "", "Message", "base msg", "Description", "base desc")));
        Result r = OriginalTextBackfill.process(OriginalTextBackfill.binding("PMDA-AD"), src,
                checks, false);

        assertEquals(2, r.files());
        assertEquals(2, r.changed(), "both the base and the split rule are backfilled");
        assertTrue(r.noRow().isEmpty(), "the split id no longer misses via the base fallback");

        String split = Files.readString(fam.resolve("PMDA-AD9998-A.yaml"), StandardCharsets.UTF_8);
        assertTrue(split.contains("  Description: \"base desc\""),
                "split rule inherits the base row");

        Result again = OriginalTextBackfill.process(OriginalTextBackfill.binding("PMDA-AD"), src,
                checks, false);
        assertEquals(0, again.changed(), "re-running the backfill is a no-op");
    }

    // ---- (b2) the version key --------------------------------------------

    private static final SheetBinding SEND = OriginalTextBackfill.binding("CDISC-SEND");

    private static final SheetBinding CG = OriginalTextBackfill.binding("CDISC-CG");

    private static final SheetBinding AD = OriginalTextBackfill.binding("CDISC-AD");

    private static int cmp(SheetBinding binding, String a, String b)
    {
        return OriginalTextBackfill.compareVersions(binding, VersionKey.parse(a),
                VersionKey.parse(b));
    }


    @Test
    void versionKeyParsesEverySendSpellingTheWorkbookUses()
    {
        assertEquals(new VersionKey("SENDIG", List.of(3, 0)), VersionKey.parse("SENDIG v3.0"));
        assertEquals(new VersionKey("SENDIG", List.of(3, 1)), VersionKey.parse("SENDIG v3.1"));
        assertEquals(new VersionKey("SENDIG", List.of(3, 1, 1)), VersionKey.parse("SENDIG v3.1.1"));
        assertEquals(new VersionKey("SENDIG-DART", List.of(1, 1)),
                VersionKey.parse("SENDIG-DART v1.1"));
        assertEquals(new VersionKey("SENDIG-DART", List.of(1, 2)),
                VersionKey.parse("SENDIG-DART v1.2"));
        assertEquals(new VersionKey("SENDIG-GENETOX", List.of(1, 0)),
                VersionKey.parse("SENDIG-GENETOX v1.0"));
        // ⚑ Defensive only: SENDIG-AR does NOT occur in SEND_Conformance_Rules_v5.0.xlsx
        // (six distinct version cells, measured 2026-08-22). Pinned here, not by corpus data.
        assertEquals(new VersionKey("SENDIG-AR", List.of(1, 0)),
                VersionKey.parse("SENDIG-AR v1.0"));
    }


    @Test
    void aBareVersionMarkerIsNotMistakenForAFamilyHead()
    {
        // The family head must be followed by whitespace, so the leading `v` cannot be swallowed
        // into the family group (the shape the plan's review question 4 asked about).
        assertEquals(new VersionKey("", List.of(1, 2)), VersionKey.parse("v1.2"));
        assertEquals(new VersionKey("", List.of(3, 4)), VersionKey.parse("3.4"));
        assertEquals(new VersionKey("", List.of(1)), VersionKey.parse("1"));
        assertEquals(new VersionKey("SENDIG", List.of(3, 0)), VersionKey.parse("  SENDIG v3.0  "));
    }


    @Test
    void theDartV12MisorderingThatMotivatedM3D24IsGone()
    {
        // The deleted parseVersion split on '.' and mapped every non-numeric part to 0:
        // SENDIG v3.1.1 -> [0, 1, 1] SENDIG-DART v1.1 -> [0, 1]
        // SENDIG-DART v1.2 -> [0, 2] SENDIG-GENETOX v1.0 -> [0, 0]
        // so the whole family head vanished, SENDIG v3.1.1 out-ranked SENDIG-DART v1.1, and the
        // family-wide maximum was always SENDIG-DART v1.2 (285 of the 323 joined SEND rules,
        // re-measured 2026-08-22 — the dossier's 286 of 326 before three rules were retired).
        assertTrue(cmp(SEND, "SENDIG v3.1.1", "SENDIG-DART v1.1") < 0,
                "a SENDIG version is lower than any SENDIG-DART one (old parser: GREATER)");
        assertTrue(cmp(SEND, "SENDIG-GENETOX v1.0", "SENDIG v3.0") > 0,
                "GENETOX ranks after SENDIG (old parser: both [0,0], i.e. EQUAL)");
        assertTrue(cmp(SEND, "SENDIG v3.1.1", "SENDIG-DART v1.2") < 0);
    }


    @Test
    void familyOrderBeatsTheNumbers()
    {
        assertTrue(cmp(SEND, "SENDIG v3.0", "SENDIG-DART v1.1") < 0);
        assertTrue(cmp(SEND, "SENDIG-DART v1.1", "SENDIG-GENETOX v1.0") < 0);
        assertTrue(cmp(SEND, "SENDIG-GENETOX v1.0", "SENDIG-AR v1.0") < 0);
        assertTrue(cmp(SEND, "SENDIG v3.0", "SENDIG v3.1") < 0, "within a family the numbers rule");
        assertEquals(0, cmp(SEND, "SENDIG v3.1", "SENDIG v3.1"));
    }


    @Test
    void bareNumericFamiliesAreUnaffectedByTheFix()
    {
        // CDISC-CG's vocabulary (3.2 / 3.3 / 3.4) and CDISC-AD's (1 / 1.1 / 1.2 / 1.3), measured
        // 2026-08-22: no cell in either column carries a family head, so familyOrder is empty.
        assertTrue(cmp(CG, "3.2", "3.3") < 0);
        assertTrue(cmp(CG, "3.3", "3.4") < 0);
        assertTrue(cmp(CG, "3.1.2", "3.4") < 0, "absent from the workbook, must still order");
        assertTrue(cmp(AD, "1", "1.1") < 0);
        assertTrue(cmp(AD, "1.1", "1.2") < 0);
        assertTrue(cmp(AD, "1.2", "1.3") < 0);
    }


    @Test
    void aMissingNumericPartReadsAsZero()
    {
        assertTrue(cmp(SEND, "SENDIG v3.1", "SENDIG v3.1.1") < 0);
        assertEquals(0, cmp(CG, "3.4", "3.4.0"));
    }


    @Test
    void anUnparseableCellSortsLastAndIsNeverVersionZero()
    {
        assertEquals(new VersionKey("draft", List.of()), VersionKey.parse("draft"));
        assertEquals(new VersionKey("", List.of()), VersionKey.parse(""));
        assertTrue(cmp(SEND, "draft", "SENDIG-AR v1.0") > 0, "an unlisted family ranks last");
        assertTrue(cmp(SEND, "draft", "0") > 0, "an unparseable cell is not version zero");
        assertTrue(cmp(SEND, "draft", "0.0") > 0);
    }

    // ---- (b3) row selection ----------------------------------------------


    private static Selection selectSend(Path dir, String sheetId, String... declared)
        throws IOException
    {
        var src = OriginalTextBackfill.loadSheet(sendBook(dir), SEND);
        return OriginalTextBackfill.select(SEND, src.get(sheetId), Set.of(declared));
    }


    @Test
    void ownPrimaryRowPicksTheLowestVersionTheRuleItselfDeclares(@TempDir Path dir)
        throws IOException
    {
        // SEND3's rows are DART v1.2 (row 4), SENDIG v3.0 (row 5), SENDIG v3.1 (row 6) — NOT in
        // version order, exactly like the real workbook's SEND289.1.
        Selection selection = selectSend(dir, "SEND3", "SENDIG v3.0", "SENDIG v3.1",
                "SENDIG-DART v1.2");
        assertEquals(5, selection.row().sheetRow());
        assertEquals("SENDIG v3.0", selection.row().versionCell());
        assertEquals("expl v30", selection.fields().get("Plain_Text_Explanation"));
        assertFalse(selection.undeclared());
    }


    @Test
    void theSend0138ShapeTakesTheRuleCellFromTheLowestRow(@TempDir Path dir) throws IOException
    {
        // The CDISC-SEND-0138 shape: only the lowest (v3.0) row carries `Rule`; the later rows are
        // blank, which is how the pre-M3-D24 pick lost the cell entirely. Asserted on the emitted
        // YAML lines, not on the map.
        Selection selection = selectSend(dir, "SEND3", "SENDIG v3.0", "SENDIG v3.1",
                "SENDIG-DART v1.2");
        assertEquals(
                List.of("Source:", "  Class: \"FND\"", "  Domain: \"LB\"", "  Variable: \"LBSEQ\"",
                        "  Plain_Text_Explanation: \"expl v30\"", "  Condition: \"cond v30\"",
                        "  Rule: \"seq is unique\""),
                OriginalTextBackfill.sourceBlock(selection.fields()));
        assertTrue(selection.fallbacks().isEmpty(),
                "no fallback is needed — the chosen row carries the cell");
    }


    @Test
    void aRuleThatDoesNotDeclareTheLowestRowGetsItsOwnLowest(@TempDir Path dir) throws IOException
    {
        Selection selection = selectSend(dir, "SEND4", "SENDIG-DART v1.1", "SENDIG-DART v1.2");
        assertEquals("SENDIG-DART v1.1", selection.row().versionCell());
        assertEquals("expl dart11", selection.fields().get("Plain_Text_Explanation"));
        assertFalse(selection.undeclared());
    }


    @Test
    void anEmptyEligibleSetFallsBackToTheLowestRowAndIsReported(@TempDir Path dir)
        throws IOException
    {
        Selection selection = selectSend(dir, "SEND4", "SENDIG v9.9");
        assertTrue(selection.undeclared(), "reported, never fatal");
        assertEquals("SENDIG v3.0", selection.row().versionCell(), "the lowest candidate row");
    }


    @Test
    void aTieOnTheVersionCellIsBrokenByTheLowerSheetRow(@TempDir Path dir) throws IOException
    {
        // SEND5's two rows share `SENDIG v3.0`; the lower sheet row (10) wins.
        Selection selection = selectSend(dir, "SEND5", "SENDIG v3.0");
        assertEquals(10, selection.row().sheetRow());
        assertEquals("expl a", selection.fields().get("Plain_Text_Explanation"));
    }


    @Test
    void aBlankCellIsFilledFromASameVersionSiblingAndReported(@TempDir Path dir) throws IOException
    {
        // (b1). SEND5 row 10 is blank on Condition; row 11 carries it on the SAME version cell.
        Selection selection = selectSend(dir, "SEND5", "SENDIG v3.0");
        assertEquals("cond b", selection.fields().get("Condition"));
        assertEquals(List.of(new Fallback("Condition", 11, "SENDIG v3.0")), selection.fallbacks());
        assertFalse(OriginalTextBackfill.sourceBlock(selection.fields()).toString()
                .contains("Fallback"), "no provenance marker is written into the Source block");
    }


    @Test
    void theBlankCellFallbackNeverCrossesAFamilyBoundary(@TempDir Path dir) throws IOException
    {
        // Owner ruling 2026-08-21 (question 2), family half. SEND3's chosen v3.0 row carries every
        // field, so invert it: choose the DART v1.2 row, whose `Rule` cell is blank while the
        // SENDIG v3.0 row 5 carries one. SENDIG v3.0 orders BELOW DART v1.2 in the family order,
        // so a pure "lower version" test would admit it — the family test is what keeps the key
        // OMITTED. (O-2, 2026-08-22: the version half was widened to same-or-lower, see the next
        // two tests; the family half is unchanged.)
        Selection selection = selectSend(dir, "SEND3", "SENDIG-DART v1.2");
        assertEquals("SENDIG-DART v1.2", selection.row().versionCell());
        assertFalse(selection.fields().containsKey("Rule"),
                "row 5 carries `Rule` but is a different family — not eligible");
        assertTrue(selection.fallbacks().isEmpty());
    }


    @Test
    void aBlankCellOnTheOwnPrimaryRowIsFilledFromTheSameFamilysLowerVersionRow(@TempDir Path dir)
        throws IOException
    {
        // O-2 (2026-08-22): the future 0138 shape. A rule declaring SENDIG v3.1 only anchors on row
        // 12, whose `Rule` is blank; the SENDIG v3.0 row 13 (a version the rule does NOT declare)
        // carries it and is the donor. The DART row 14 also carries one but is another family.
        Selection selection = selectSend(dir, "SEND6", "SENDIG v3.1");
        assertEquals(12, selection.row().sheetRow());
        assertEquals("rule v30", selection.fields().get("Rule"));
        assertEquals(List.of(new Fallback("Rule", 13, "SENDIG v3.0")), selection.fallbacks());
        assertEquals(
                List.of("Source:", "  Class: \"FND\"", "  Domain: \"LB\"", "  Variable: \"LBSEQ\"",
                        "  Plain_Text_Explanation: \"expl v31\"", "  Condition: \"cond v31\"",
                        "  Rule: \"rule v30\""),
                OriginalTextBackfill.sourceBlock(selection.fields()),
                "the borrowed key sits in its declared position and nothing marks it");
    }


    @Test
    void theNearestLowerVersionDonatesFirstAndNothingIsBorrowedUpward(@TempDir Path dir)
        throws IOException
    {
        // SEND7: v3.1.1 (row 17) is blank on `Rule`; both v3.1 (row 16) and v3.0 (row 15) carry
        // one — the NEAREST lower version wins, not the lowest.
        Selection top = selectSend(dir, "SEND7", "SENDIG v3.1.1");
        assertEquals(17, top.row().sheetRow());
        assertEquals("rule v31", top.fields().get("Rule"));
        assertEquals(List.of(new Fallback("Rule", 16, "SENDIG v3.1")), top.fallbacks());

        // v3.0 (row 15) is blank on `Condition`; only the HIGHER rows carry it — omitted.
        Selection bottom = selectSend(dir, "SEND7", "SENDIG v3.0");
        assertEquals(15, bottom.row().sheetRow());
        assertFalse(bottom.fields().containsKey("Condition"),
                "rows 16/17 carry `Condition` but are higher versions — never borrowed upward");
        assertTrue(bottom.fallbacks().isEmpty());

        // The donor order itself, pinned directly: nearest version first, lower sheet row on a tie.
        var rows = OriginalTextBackfill.loadSheet(sendBook(dir), SEND).get("SEND7");
        SourceRow chosen = rows.stream().filter(r -> r.sheetRow() == 17).findFirst().orElseThrow();
        assertEquals(List.of(16, 15), OriginalTextBackfill.donorsNearestFirst(SEND, rows, chosen)
                .stream().map(SourceRow::sheetRow).toList());
    }


    @Test
    void theEmittedKeyOrderIsTheBindingsFieldOrderWhicheverRowSuppliedEachValue(@TempDir Path dir)
        throws IOException
    {
        Selection selection = selectSend(dir, "SEND5", "SENDIG v3.0");
        assertEquals(
                List.of("Class", "Domain", "Variable", "Plain_Text_Explanation", "Condition",
                        "Rule"),
                new ArrayList<>(selection.fields().keySet()),
                "Condition came from the sibling row yet still sits in declared position 5");
    }


    @Test
    void highestVersionIgnoresTheDeclaredSetAndKeepsTheEarlierRowOnATie()
    {
        // CDISC-CG / CDISC-AD keep the pre-M3-D24 pick byte-for-byte, tie behaviour included: the
        // old loader kept a row only on a STRICTLY greater version, so the earlier row survived.
        List<SourceRow> rows = List.of(row(2, "3.4", "Rule", "earlier"),
                row(3, "3.2", "Rule", "lowest"), row(4, "3.4", "Rule", "later"));
        assertEquals("earlier",
                OriginalTextBackfill.select(CG, rows, Set.of()).fields().get("Rule"));
        assertEquals("earlier",
                OriginalTextBackfill.select(CG, rows, Set.of("SDTMIG v3.2")).fields().get("Rule"),
                "the check's declared versions are not read under HIGHEST_VERSION");
        assertFalse(OriginalTextBackfill.select(CG, rows, Set.of()).undeclared());
    }


    @Test
    void aVersionCellOutsideTheBindingsVocabularyIsRejectedAtLoadTime(@TempDir Path dir)
        throws IOException
    {
        // ⚠ The failure this prevents is a SILENT one. An unrankable cell sorts after every known
        // family, which under HIGHEST_VERSION makes it WIN the pick: a future `OCCDS v1.0` in the
        // ADaM Rule Set column (the spelling that column's own header advertises) would out-rank
        // `1.3` and re-anchor an ADaM rule nobody decided to move. The old parseVersion mapped it
        // to [0,0] and it lost; the new key must not let it win by accident.
        // Handed to write(), which closes it once the book is saved.
        @SuppressWarnings("PMD.CloseResource")
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "Rules Catalogue", List.of(List.of("Identifiers", "", "", "", "", "", "", ""),
                List.of("Rule ID", RULE_SET, "Class", "Subclass", "SEND/SDTM Domain",
                        "Variable or Item", "Natural Language Rule (Failure Criteria)",
                        "Rule (Failure Criteria)", "Condition (Failure)"),
                List.of("1", "1.3", "BDS", "", "", "", "nl", "r", "c"),
                List.of("1", "OCCDS v1.0", "BDS", "", "", "", "nl occds", "r occds", "c occds")));
        Path book = write(dir, "ADaM Conformance Rules v5.0.xlsx", wb);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OriginalTextBackfill.loadSheet(book, AD));
        assertTrue(thrown.getMessage().contains("OCCDS v1.0"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("row 4"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("<bare numerics only>"), thrown.getMessage());

        // The mirror case: a bare numeric on SEND, whose familyOrder does NOT contain "".
        // Handed to write(), which closes it once the book is saved.
        @SuppressWarnings("PMD.CloseResource")
        XSSFWorkbook send = new XSSFWorkbook();
        sheet(send, "_GENETOXv1.0_DARTv1.2",
                List.of(List.of("CDISC SEND Rule ID", "Class", "Domain", "Variable",
                        "Plain Text Explanation of Condition/Rule", "Condition", "Rule",
                        "SENDIG Version"),
                        List.of("SEND1", "ALL", "TS", "TSVAL", "e", "c", "r", "3.0")));
        Path sendBook = write(dir, "SEND_Conformance_Rules_v5.0.xlsx", send);
        assertTrue(
                assertThrows(IllegalStateException.class,
                        () -> OriginalTextBackfill.loadSheet(sendBook, SEND)).getMessage()
                                .contains("SENDIG-GENETOX"),
                "the message names the families the binding does accept");
    }


    @Test
    void theBlankCellFallbackRunsUnderOwnPrimaryRowOnly()
    {
        // M3-D24 scoped (b1) to SEND and ruled the other families out of scope, so FIRST_ROW and
        // HIGHEST_VERSION keep the pre-M3-D24 blank-cell semantics BY CONSTRUCTION: a blank cell
        // omits the key even when a same-version sibling row carries one. Measured 2026-08-22:
        // no non-SEND binding would take a fallback today anyway — this pins it for the next
        // workbook revision, which nobody has ruled on.
        List<SourceRow> cg = List.of(row(2, "3.4", "Class", "ALL"),
                row(3, "3.4", "Class", "ALL", "Rule", "sibling rule"));
        Selection highest = OriginalTextBackfill.select(CG, cg, Set.of());
        assertEquals(2, highest.row().sheetRow(), "the earlier row wins the version tie");
        assertFalse(highest.fields().containsKey("Rule"), "no borrowing under HIGHEST_VERSION");
        assertTrue(highest.fallbacks().isEmpty());

        List<SourceRow> fda = List.of(row(3, "", "Domains", "ALL"),
                row(4, "", "Domains", "ALL", "FDA_Validator_Rule_Message", "sibling msg"));
        Selection first = OriginalTextBackfill.select(OriginalTextBackfill.binding("FDA"), fda,
                Set.of());
        assertFalse(first.fields().containsKey("FDA_Validator_Rule_Message"),
                "no borrowing under FIRST_ROW");
        assertTrue(first.fallbacks().isEmpty());
    }


    @Test
    void firstRowPolicyKeepsSheetOrder()
    {
        List<SourceRow> rows = List.of(row(3, "", "Domains", "first"),
                row(4, "", "Domains", "second"));
        assertEquals("first",
                OriginalTextBackfill.select(OriginalTextBackfill.binding("FDA"), rows, Set.of())
                        .fields().get("Domains"));
    }

    // ---- the declared-version set ----------------------------------------


    @Test
    void declaredVersionsSpellsThemAsTheSheetsVersionColumnDoes() throws IOException
    {
        ObjectNode check = check("""
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "SENDIG"
                    Version: "3.1.1"
                  - Name: "SENDIG-DART"
                    Version: "1.2"
                """);
        assertEquals(Set.of("SENDIG v3.1.1", "SENDIG-DART v1.2"),
                OriginalTextBackfill.declaredVersions(check));
        assertTrue(OriginalTextBackfill.declaredVersions(check("Core:\n  Id: \"X\"\n")).isEmpty());
    }

    // ---- line breaks: the one documented fidelity exception --------------


    @Test
    void everyLineBreakRunInsideACellBecomesOneSpace()
    {
        assertEquals("a b", OriginalTextBackfill.transcribe("a\nb"));
        assertEquals("a b", OriginalTextBackfill.transcribe("a\r\nb"), "a CRLF is ONE line break");
        assertEquals("a b", OriginalTextBackfill.transcribe("a\n\n\nb"));
        assertEquals("a  b", OriginalTextBackfill.transcribe("a \nb"),
                "only the break is replaced — surrounding spaces are preserved");
        assertEquals("a\tb", OriginalTextBackfill.transcribe("a\tb"), "a tab is not a line break");
        assertEquals("plain", OriginalTextBackfill.transcribe("plain"));
    }


    @Test
    void theLoaderTranscribesAnEmbeddedLineBreakAsOneSpace(@TempDir Path dir) throws IOException
    {
        // The shape of SEND_Conformance_Rules_v5.0.xlsx row 1476 (CDISC-SEND-0280), the only
        // _x000D_-bearing cell in the six Source-bearing columns.
        // Handed to write(), which closes it once the book is saved.
        @SuppressWarnings("PMD.CloseResource")
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "_GENETOXv1.0_DARTv1.2",
                List.of(List.of("CDISC SEND Rule ID", "Class", "Domain", "Variable",
                        "Plain Text Explanation of Condition/Rule", "Condition", "Rule",
                        "SENDIG Version"),
                        List.of("SEND280", "FND", "LB", "LBSTAT",
                                "reason for not completing the\r\n" + "evaluation as scheduled",
                                "c", "r", "SENDIG v3.0"),
                        List.of("SEND281", "FND", "LB", "LBSTAT", "trailing break\n", "c", "r",
                                "SENDIG v3.0")));
        var src = OriginalTextBackfill.loadSheet(write(dir, "SEND_Conformance_Rules_v5.0.xlsx", wb),
                SEND);
        assertEquals("reason for not completing the evaluation as scheduled",
                src.get("SEND280").getFirst().fields().get("Plain_Text_Explanation"));
        assertEquals("trailing break",
                src.get("SEND281").getFirst().fields().get("Plain_Text_Explanation"),
                "a trailing break is stripped, not turned into a trailing space");
    }

    // ---- process(), end to end -------------------------------------------


    private static void sendCheck(Path fam, String id, String sheetId, String... versions)
        throws IOException
    {
        StringBuilder sb = new StringBuilder("Core:\n  Id: \"").append(id)
                .append("\"\nStandards:\n- Organization: \"CDISC\"\n  Standards:\n");
        for (String version : versions)
        {
            int at = version.indexOf(" v");
            sb.append("  - Name: \"").append(version, 0, at).append("\"\n    Version: \"")
                    .append(version.substring(at + 2))
                    .append("\"\n    References:\n" + "    - Origin: \"SEND Conformance Rules\"\n"
                            + "      Rule_Identifier: {Id: \"")
                    .append(sheetId).append("\"}\n");
        }
        Files.writeString(fam.resolve(id + ".yaml"), sb.toString(), StandardCharsets.UTF_8);
    }


    @Test
    void processAnchorsEachSendRuleOnItsOwnPrimaryRowAndIsIdempotent(@TempDir Path dir)
        throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("CDISC");
        Files.createDirectories(fam);
        sendCheck(fam, "CDISC-SEND-0003", "SEND3", "SENDIG v3.0", "SENDIG v3.1",
                "SENDIG-DART v1.2");
        sendCheck(fam, "CDISC-SEND-0004", "SEND4", "SENDIG-DART v1.1", "SENDIG-DART v1.2");
        // A letter-suffixed split sibling: the sheet holds only the base id (SEND4).
        sendCheck(fam, "CDISC-SEND-0004-A", "SEND4-A", "SENDIG-DART v1.1", "SENDIG-DART v1.2");

        var src = OriginalTextBackfill.loadSheet(sendBook(dir), SEND);
        Result r = OriginalTextBackfill.process(SEND, src, checks, false);

        assertEquals(3, r.files());
        assertEquals(3, r.changed());
        assertTrue(r.noId().isEmpty());
        assertTrue(r.noRow().isEmpty(), "the split id resolves through stripSplitSuffix");
        assertTrue(r.fallbacks().isEmpty());
        assertTrue(r.undeclared().isEmpty());

        String three = Files.readString(fam.resolve("CDISC-SEND-0003.yaml"),
                StandardCharsets.UTF_8);
        assertTrue(three.contains("  Rule: \"seq is unique\""), three);
        assertTrue(three.contains("  Plain_Text_Explanation: \"expl v30\""), three);
        assertTrue(three.indexOf("Source:") < three.indexOf("Standards:"));
        String split = Files.readString(fam.resolve("CDISC-SEND-0004-A.yaml"),
                StandardCharsets.UTF_8);
        assertTrue(split.contains("  Plain_Text_Explanation: \"expl dart11\""),
                "the split sibling inherits the PARENT's primary row");

        assertEquals(0, OriginalTextBackfill.process(SEND, src, checks, false).changed(),
                "re-running the backfill is a no-op");
    }


    @Test
    void theRunReportNamesEveryFallbackAndUndeclaredPrimary(@TempDir Path dir) throws IOException
    {
        Path checks = dir.resolve("checks");
        Path fam = checks.resolve("CDISC");
        Files.createDirectories(fam);
        sendCheck(fam, "CDISC-SEND-0005", "SEND5", "SENDIG v3.0");
        sendCheck(fam, "CDISC-SEND-0004", "SEND4", "SENDIG v9.9");

        var src = OriginalTextBackfill.loadSheet(sendBook(dir), SEND);
        Result r = OriginalTextBackfill.process(SEND, src, checks, true);

        assertEquals(List.of("CDISC-SEND-0005.Condition <- sheet row 11 (SENDIG v3.0)"),
                r.fallbacks());
        assertEquals(List.of("CDISC-SEND-0004"), r.undeclared());
        String report = r.report("CDISC-SEND", true);
        assertTrue(report.contains("fallbacks=1 undeclared=1 (dry-run)"), report);
        assertTrue(report.contains("fallback: CDISC-SEND-0005.Condition <- sheet row 11"), report);
        assertTrue(report.contains("undeclared-primary: [CDISC-SEND-0004]"), report);
    }

    // ================================================================================
    // Source_Proposed non-touch pins (plans/done/PLAN-source-proposed-block.md §4 item 7)
    //
    // ⚠ Before these, NO test covered a Source-sibling root key through the splice, so the plan's
    // §2.1 safety argument was INFERENCE. These make it evidence. The tool gains no functional
    // change; what it gains is the guarantee that it keeps having none.
    //
    // ⚑ Exercised on line lists rather than @TempDir files: backfill/stripSource ARE the splice,
    // and a file round-trip would test Files.readAllLines instead of the property at stake.
    // ================================================================================


    /**
     * A synthetic rule carrying a proposal, in canonical order: proposal, then Source, then
     * Standards.
     */
    private static List<String> withProposal(String... tail)
    {
        List<String> in = new ArrayList<>(List.of("Core:", "  Id: \"FDA-SD9701\"",
                "Description: \"d\"", "Source_Proposed:", "  Sheet_Issue: \"#160\"",
                "  FDA_Validator_Rule_Message: \"proposed message\"", "  Domains: \"AE\""));
        in.addAll(List.of(tail));
        return in;
    }


    /**
     * 1. Proposal present, no Source: the proposal is untouched and Source lands before Standards.
     */
    @Test
    void proposalSurvivesAFirstTimeSourceSplice()
    {
        List<String> in = withProposal("Standards:", "- Organization: \"FDA\"");
        List<String> out = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        assertEquals(in.subList(0, 7), out.subList(0, 7), "the proposal block moved or changed");
        assertEquals(
                List.of("Source:", "  Domains: \"ALL\"", "Standards:", "- Organization: \"FDA\""),
                out.subList(7, out.size()));
    }


    /** 2. Proposal present, Source present and wrong: Source is replaced, the proposal is not. */
    @Test
    void proposalSurvivesAReplacementOfAWrongSource()
    {
        List<String> in = withProposal("Source:", "  Domains: \"STALE\"", "Standards:");
        List<String> out = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        assertEquals(in.subList(0, 7), out.subList(0, 7));
        assertTrue(out.contains("  Domains: \"ALL\""), out.toString());
        assertFalse(out.contains("  Domains: \"STALE\""), out.toString());
        // exactly one root-level Source: line — the strip did not leave the old block behind
        assertEquals(1, out.stream().filter(l -> l.equals("Source:")).count(), out.toString());
        assertEquals(1, out.stream().filter(l -> l.equals("Source_Proposed:")).count(),
                out.toString());
    }


    /**
     * 3. Proposal byte-equal to the fresh Source: the run must NOT collapse them.
     *
     * <p>
     * That equality is the ADOPTION trigger — §2.6's {@code noAdoptedProposalIsStillCarried} exists
     * to see it and report "the sheet adopted this, retire the block". A splice that silently
     * dropped the duplicate would erase the signal before anything could read it.
     * </p>
     */
    @Test
    void anAdoptedLookingProposalIsNotCollapsedIntoTheSource()
    {
        List<String> in = new ArrayList<>(List.of("Core:", "  Id: \"FDA-SD0001\"",
                "Source_Proposed:", "  Domains: \"ALL\"", "Standards:"));
        List<String> out = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        assertEquals(1, out.stream().filter(l -> l.equals("Source_Proposed:")).count());
        assertEquals(1, out.stream().filter(l -> l.equals("Source:")).count());
        assertEquals(2, out.stream().filter(l -> l.equals("  Domains: \"ALL\"")).count(),
                "the adoption trigger was collapsed away: " + out);
    }


    /**
     * 4. ⚠⚠ Citation unresolvable: {@code Source} is DELETED and the proposal is not.
     *
     * <p>
     * {@code process} builds an EMPTY field map when a rule resolves to no source row, and
     * {@code backfill} then returns the stripped lines — so a rule whose {@code Rule_Identifier}
     * stops resolving loses its {@code Source} <b>with no error</b>. That is pre-existing
     * behaviour, pinned here because phase 3b runs the tool over the corpus and depends on it: the
     * proposal must not be collateral damage.
     * </p>
     */
    @Test
    void anUnresolvableCitationDeletesTheSourceButNeverTheProposal()
    {
        List<String> in = withProposal("Source:", "  Domains: \"ALL\"", "Standards:");
        List<String> out = OriginalTextBackfill.backfill(in, new LinkedHashMap<>());
        assertFalse(out.contains("Source:"), "Source survived an empty field map: " + out);
        assertEquals(in.subList(0, 7), out.subList(0, 7), "the proposal was collateral damage");
    }


    /** 5. Idempotence: a second run over the first run's output is a byte-for-byte no-op. */
    @Test
    void theSpliceIsIdempotentInThePresenceOfAProposal()
    {
        List<String> in = withProposal("Standards:", "- Organization: \"FDA\"");
        List<String> once = OriginalTextBackfill.backfill(in, fields("Domains", "ALL"));
        List<String> twice = OriginalTextBackfill.backfill(once, fields("Domains", "ALL"));
        assertEquals(once, twice);
    }


    /**
     * ⛔ The three forbidden key spellings (§2.1): {@code stripSource} drops any indent-0 line
     * starting {@code Original_Description:} / {@code Original_Condition:} / {@code Source:}, and
     * any indent-2 {@code Original_Message:}, <b>regardless of parent</b>. No family key set uses
     * those spellings, so the prohibition costs nothing — but the deletion is silent, so it is
     * pinned rather than trusted.
     *
     * <p>
     * ⭐ The positive half is the load-bearing half: {@code "Source_Proposed:"} does not start with
     * {@code "Source:"}, which is the single character of slack the whole design rests on.
     * </p>
     */
    @Test
    void stripSourceDropsTheForbiddenSpellingsAndKeepsTheProposal()
    {
        List<String> out = OriginalTextBackfill.stripSource(new ArrayList<>(
                List.of("Core:", "  Id: \"X\"", "Source_Proposed:", "  Domains: \"AE\"",
                        "Original_Description: \"legacy\"", "Original_Condition: \"legacy\"",
                        "Source:", "  Domains: \"ALL\"", "Outcome:",
                        "  Original_Message: \"legacy\"", "  Message: \"m\"", "Standards:")));
        assertEquals(List.of("Core:", "  Id: \"X\"", "Source_Proposed:", "  Domains: \"AE\"",
                "Outcome:", "  Message: \"m\"", "Standards:"), out);
    }
}
