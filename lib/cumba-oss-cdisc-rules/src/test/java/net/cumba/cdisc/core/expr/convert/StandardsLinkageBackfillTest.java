package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.AdamFinding;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.AdamOracle;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.AdamSheet;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.AdamVerdict;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.Ref;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.Result;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.Sheets;
import net.cumba.cdisc.core.expr.convert.StandardsLinkageBackfill.StdKey;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardsLinkageBackfillTest
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


    private static void book(Path dir, String file, XSSFWorkbook wb) throws IOException
    {
        try (OutputStream os = Files.newOutputStream(dir.resolve(file)))
        {
            wb.write(os);
        }
        wb.close();
    }

    private static final List<String> DEFINED_FDAB = List.of("FDAB017", "FDAB040", "FDAB065");

    /**
     * Writes the FDA validator + CG conformance + FDA business-rules workbooks into {@code dir} and
     * loads the linkage sheets, with the default {@link #DEFINED_FDAB} business-rule id set. FDA
     * rows: {@code [id, publisher, publisherId]}. CG rows: {@code [ruleId, ruleVersion,
     * sdtmigVersion]}.
     */
    private static Sheets sheets(Path dir, List<List<String>> fda, List<List<String>> cg)
        throws IOException
    {
        return sheets(dir, fda, cg, DEFINED_FDAB);
    }


    /**
     * As {@link #sheets(Path, List, List)} but with an explicit set of defined {@code FDAB} ids.
     */
    private static Sheets sheets(Path dir, List<List<String>> fda, List<List<String>> cg,
            List<String> fdabDefined)
        throws IOException
    {
        XSSFWorkbook fdaWb = new XSSFWorkbook();
        List<List<String>> fdaRows = new ArrayList<>();
        fdaRows.add(List.of("FDA Validator Rule ID", "Publisher", "Publisher ID"));
        fdaRows.addAll(fda);
        sheet(fdaWb, "FDA Validator Rules v1.6", fdaRows);
        book(dir, "FDA-validator-rules-v1-6.xlsx", fdaWb);

        XSSFWorkbook cgWb = new XSSFWorkbook();
        List<List<String>> cgRows = new ArrayList<>();
        cgRows.add(List.of("Rule ID", "Rule Version", "SDTMIG Version"));
        cgRows.addAll(cg);
        sheet(cgWb, "SDTMIG Conformance Rules v2.0", cgRows);
        book(dir, "SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx", cgWb);

        XSSFWorkbook fbWb = new XSSFWorkbook();
        List<List<String>> fbRows = new ArrayList<>();
        fbRows.add(List.of("FDA Business Rule ID", "FDA Business Rule"));
        for (String id : fdabDefined)
        {
            fbRows.add(List.of(id, "text for " + id));
        }
        sheet(fbWb, "FDA Business Rules v1.5", fbRows);
        book(dir, "FDA-business-rules-v1-5.xlsx", fbWb);

        return StandardsLinkageBackfill.loadSheets(dir);
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


    private static JsonNode parse(Path f) throws IOException
    {
        return YAML.readTree(Files.readString(f, StandardCharsets.UTF_8));
    }

    // ---- kind 1: CDISC block generation ----------------------------------


    @Test
    void cdiscBlockMirrorsSdtmigEntriesWithPerVersionCgRefsFromSheet(@TempDir Path dir)
        throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021, 40")),
                List.of(List.of("CG0021", "2", "3.4"), List.of("CG0021", "1", "3.2"),
                        List.of("CG0040", "1", "3.4")));
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9001", """
                Core:
                  Id: "FDA-SD9001"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                  - Name: "SDTMIG"
                    Version: "3.3"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                  - Name: "SENDIG"
                    Version: "3.0"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                """);

        List<String> gaps = new ArrayList<>();
        Result r = StandardsLinkageBackfill.process(sh, checks, false, gaps);
        assertEquals(1, r.cdiscBlocks());

        JsonNode root = parse(checks.resolve("FDA/FDA-SD9001.yaml"));
        var cdisc = StandardsLinkageBackfill.existingBlock(root, "CDISC");
        // Mirrors the two SDTMIG entries, NOT the SENDIG one.
        assertEquals(2, cdisc.size());
        assertTrue(cdisc.containsKey(new StdKey("SDTMIG", null, "3.4")));
        assertTrue(cdisc.containsKey(new StdKey("SDTMIG", null, "3.3")));
        assertFalse(cdisc.containsKey(new StdKey("SENDIG", null, "3.0")));
        // Per SDTMIG entry: CG0021 highest-version Rule Version "2", CG0040 "1", refVersion "2.0".
        List<Ref> refs = cdisc.get(new StdKey("SDTMIG", null, "3.4"));
        assertEquals(List.of(new Ref("SDTM and SDTMIG Conformance Rules", "CG0021", "2", "2.0"),
                new Ref("SDTM and SDTMIG Conformance Rules", "CG0040", "1", "2.0")), refs);
        // Identity FDA validator ref untouched.
        var fda = StandardsLinkageBackfill.existingBlock(root, "FDA");
        assertEquals("FDA Validator Rules",
                fda.get(new StdKey("SDTMIG", null, "3.4")).get(0).origin());
    }


    @Test
    void cdiscBlockOverwriteReplacesStaleAndPreservesIdentity(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021")),
                List.of(List.of("CG0021", "1", "3.4")));
        Path checks = dir.resolve("checks");
        // A stale CDISC block citing the wrong id (CG9998) must be replaced by CG0021.
        checkFile(checks, "FDA", "FDA-SD9001", """
                Core:
                  Id: "FDA-SD9001"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier:
                        Id: "CG9998"
                        Version: "1"
                      Version: "2.0"
                """);

        StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9001.yaml"));
        var cdisc = StandardsLinkageBackfill.existingBlock(root, "CDISC");
        assertEquals("CG0021", cdisc.get(new StdKey("SDTMIG", null, "3.4")).get(0).id(),
                "stale CG9998 replaced");
        // Identity FDA block preserved, and there is still exactly one FDA validator ref.
        var fda = StandardsLinkageBackfill.existingBlock(root, "FDA");
        assertEquals(1, fda.get(new StdKey("SDTMIG", null, "3.4")).size());
        assertEquals("SD9001", fda.get(new StdKey("SDTMIG", null, "3.4")).get(0).id());
    }


    @Test
    void danglingCgIdInjectedAsAssertedAndGapLogged(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021, 9999")),
                List.of(List.of("CG0021", "1", "3.4")));
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9001", identityFda("SD9001"));

        List<String> gaps = new ArrayList<>();
        StandardsLinkageBackfill.process(sh, checks, false, gaps);
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9001.yaml"));
        List<Ref> refs = StandardsLinkageBackfill.existingBlock(root, "CDISC")
                .get(new StdKey("SDTMIG", null, "3.4"));
        assertEquals(List.of("CG0021", "CG9999"), refs.stream().map(Ref::id).toList());
        assertEquals("1", refs.get(1).riVersion(), "dangling id defaults to version 1");
        assertTrue(gaps.stream().anyMatch(g -> g.contains("CG9999")), "dangling id gap-logged");
    }

    // ---- kind 2: FDA Business insert -------------------------------------


    @Test
    void fdaBusinessRefsAddedAsSecondOriginPreservingIdentity(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9002", "FDA", "FDAB017, FDAB065")), List.of());
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9002", identityFda("SD9002"));

        Result r = StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        assertEquals(1, r.fbBlocks());
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9002.yaml"));
        List<Ref> refs = StandardsLinkageBackfill.existingBlock(root, "FDA")
                .get(new StdKey("SDTMIG", null, "3.4"));
        // Identity validator ref first, then the two FDA Business refs (verbatim FDAB### ids).
        assertEquals(3, refs.size());
        assertEquals("FDA Validator Rules", refs.get(0).origin());
        assertEquals(new Ref("FDA Business Rules", "FDAB017", "1", "1.5"), refs.get(1));
        assertEquals(new Ref("FDA Business Rules", "FDAB065", "1", "1.5"), refs.get(2));
    }


    @Test
    void undefinedFdabIdIsStillInjectedButGapLogged(@TempDir Path dir) throws IOException
    {
        // FDAB999 is not in the business-rules sheet -> injected as-asserted + gap-logged.
        Sheets sh = sheets(dir, List.of(List.of("SD9005", "FDA", "FDAB017, FDAB999")), List.of(),
                List.of("FDAB017"));
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9005", identityFda("SD9005"));

        List<String> gaps = new ArrayList<>();
        StandardsLinkageBackfill.process(sh, checks, false, gaps);
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9005.yaml"));
        List<Ref> refs = StandardsLinkageBackfill.existingBlock(root, "FDA")
                .get(new StdKey("SDTMIG", null, "3.4"));
        assertEquals(List.of("SD9005", "FDAB017", "FDAB999"), refs.stream().map(Ref::id).toList());
        assertTrue(gaps.stream().anyMatch(g -> g.contains("FDAB999") && g.contains("not defined")),
                "undefined FDAB id gap-logged: " + gaps);
    }


    @Test
    void tcgAndBlankPublisherYieldNoRefAndAreLogged(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9003", "FDA", "TCG 4.1.4.1")), List.of());
        Path checks = dir.resolve("checks");
        checkFile(checks, "FDA", "FDA-SD9003", identityFda("SD9003"));

        List<String> gaps = new ArrayList<>();
        Result r = StandardsLinkageBackfill.process(sh, checks, false, gaps);
        assertEquals(0, r.fbBlocks(), "TCG token yields no FDA Business ref");
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9003.yaml"));
        assertEquals(1,
                StandardsLinkageBackfill.existingBlock(root, "FDA")
                        .get(new StdKey("SDTMIG", null, "3.4")).size(),
                "only the identity ref remains");
        assertTrue(gaps.stream().anyMatch(g -> g.contains("SD9003") && g.contains("FDAB")),
                "TCG-only publisher id gap-logged: " + gaps);
    }


    @Test
    void fdaBusinessInsertHandlesVersionAfterReferencesKeyOrder(@TempDir Path dir)
        throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9004", "FDA", "FDAB040")), List.of());
        Path checks = dir.resolve("checks");
        // Some unquoted FDA files order the standard-entry Version AFTER the References list; the
        // FB ref must land inside the list (before Version:), not after it (which is invalid YAML).
        checkFile(checks, "FDA", "FDA-SD9004", """
                Core:
                  Id: "FDA-SD9004"
                Standards:
                - Organization: FDA
                  Standards:
                  - Name: SDTMIG
                    References:
                    - Origin: FDA Validator Rules
                      Rule_Identifier:
                        Id: SD9004
                        Version: '1'
                      Version: '1.6'
                    Version: '3.4'
                """);

        StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        // The written file must still be valid YAML with both refs and the standard Version.
        JsonNode root = parse(checks.resolve("FDA/FDA-SD9004.yaml"));
        List<Ref> refs = StandardsLinkageBackfill.existingBlock(root, "FDA")
                .get(new StdKey("SDTMIG", null, "3.4"));
        assertEquals(2, refs.size(), "Version-after-References entry parsed and Version preserved");
        assertEquals("FDA Validator Rules", refs.get(0).origin());
        assertEquals(new Ref("FDA Business Rules", "FDAB040", "1", "1.5"), refs.get(1));
    }


    @Test
    void extractFdabIdsKeepsVerbatimAndIgnoresNonFdabTokens()
    {
        assertEquals(List.of("FDAB017", "FDAB065"),
                StandardsLinkageBackfill.extractFdabIds("FDAB017, FDAB065 and TCG 4.1.4.1"));
        assertTrue(StandardsLinkageBackfill.extractFdabIds("TCG 4.1.4.1; eCTD 2.3").isEmpty());
    }

    // ---- kind 3: PMDA one-hop --------------------------------------------


    @Test
    void pmdaOneHopBuildsFdaBlockWhenNativeIdInFdaSheet(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021")),
                List.of(List.of("CG0021", "1", "3.4")));
        Path checks = dir.resolve("checks");
        // PMDA rule whose native id SD9001 matches the FDA sheet -> one-hop FDA block.
        checkFile(checks, "PMDA", "PMDA-SD9001", """
                Core:
                  Id: "PMDA-SD9001"
                Standards:
                - Organization: PMDA
                  Standards:
                  - Name: SDTMIG
                    Version: '3.2'
                    References:
                    - Origin: PMDA Validation Rules
                      Rule_Identifier:
                        Id: SD9001
                        Version: '1'
                      Version: '6.0'
                """);
        // A PMDA rule with an AD native id absent from the FDA sheet -> nothing.
        checkFile(checks, "PMDA", "PMDA-AD9001", """
                Core:
                  Id: "PMDA-AD9001"
                Standards:
                - Organization: PMDA
                  Standards:
                  - Name: ADaMIG
                    Version: '1.1'
                    References:
                    - Origin: PMDA Validation Rules
                      Rule_Identifier:
                        Id: AD9001
                        Version: '1'
                      Version: '6.0'
                """);

        Result r = StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        assertEquals(1, r.pmdaHops());

        JsonNode hop = parse(checks.resolve("PMDA/PMDA-SD9001.yaml"));
        List<Ref> fda = StandardsLinkageBackfill.existingBlock(hop, "FDA")
                .get(new StdKey("SDTMIG", null, "3.2"));
        assertEquals(new Ref("FDA Validator Rules", "SD9001", "1", "1.6"), fda.get(0));
        // Identity PMDA block preserved (unquoted lines untouched).
        assertTrue(Files.readString(checks.resolve("PMDA/PMDA-SD9001.yaml"))
                .contains("- Organization: PMDA"), "identity PMDA block left byte-for-byte");

        JsonNode none = parse(checks.resolve("PMDA/PMDA-AD9001.yaml"));
        assertTrue(StandardsLinkageBackfill.existingBlock(none, "FDA").isEmpty(),
                "AD rule with no FDA match gets no block");
    }

    // ---- idempotency + round-trip ----------------------------------------


    @Test
    void secondRunIsByteIdenticalNoOp(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir,
                List.of(List.of("SD9001", "CDISC", "CG0021"), List.of("SD9002", "FDA", "FDAB017")),
                List.of(List.of("CG0021", "2", "3.4")));
        Path checks = dir.resolve("checks");
        Path cdisc = checkFile(checks, "FDA", "FDA-SD9001", identityFda("SD9001"));
        Path fb = checkFile(checks, "FDA", "FDA-SD9002", identityFda("SD9002"));
        checkFile(checks, "PMDA", "PMDA-SD9001", """
                Core:
                  Id: "PMDA-SD9001"
                Standards:
                - Organization: PMDA
                  Standards:
                  - Name: SDTMIG
                    Version: '3.2'
                    References:
                    - Origin: PMDA Validation Rules
                      Rule_Identifier:
                        Id: SD9001
                        Version: '1'
                      Version: '6.0'
                """);

        StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        String cdisc1 = Files.readString(cdisc, StandardCharsets.UTF_8);
        String fb1 = Files.readString(fb, StandardCharsets.UTF_8);

        Result again = StandardsLinkageBackfill.process(sh, checks, false, new ArrayList<>());
        assertEquals(0, again.cdiscBlocks(), "second CDISC pass is a no-op");
        assertEquals(0, again.fbBlocks(), "second FB pass is a no-op");
        assertEquals(0, again.pmdaHops(), "second PMDA pass is a no-op");
        assertEquals(cdisc1, Files.readString(cdisc, StandardCharsets.UTF_8));
        assertEquals(fb1, Files.readString(fb, StandardCharsets.UTF_8));

        // Round-trip: the written CDISC refs re-parse to exactly the computed set.
        var block = StandardsLinkageBackfill.existingBlock(parse(cdisc), "CDISC");
        assertEquals(new Ref("SDTM and SDTMIG Conformance Rules", "CG0021", "2", "2.0"),
                block.get(new StdKey("SDTMIG", null, "3.4")).get(0));
    }


    @Test
    void dryRunReportsOracleButWritesNothing(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021")),
                List.of(List.of("CG0021", "1", "3.4")));
        Path checks = dir.resolve("checks");
        // Existing correct CDISC block -> oracle match, no write.
        String body = """
                Core:
                  Id: "FDA-SD9001"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier:
                        Id: "CG0021"
                        Version: "1"
                      Version: "2.0"
                """;
        Path f = checkFile(checks, "FDA", "FDA-SD9001", body);

        Result r = StandardsLinkageBackfill.process(sh, checks, true, new ArrayList<>());
        assertEquals(1, r.oracleTotal());
        assertEquals(1, r.oracleMatch());
        assertEquals(0, r.oracleSuspect());
        assertEquals(body, Files.readString(f, StandardCharsets.UTF_8), "dry run writes nothing");
    }


    @Test
    void oracleClassifiesStaleSupersetAsMonotonicNotSuspect(@TempDir Path dir) throws IOException
    {
        Sheets sh = sheets(dir, List.of(List.of("SD9001", "CDISC", "CG0021, 40")),
                List.of(List.of("CG0021", "1", "3.4"), List.of("CG0040", "1", "3.4")));
        Path checks = dir.resolve("checks");
        // Stale corpus: existing block has only CG0021; the sheet now also asserts CG0040.
        checkFile(checks, "FDA", "FDA-SD9001", """
                Core:
                  Id: "FDA-SD9001"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "SD9001"
                        Version: "1"
                      Version: "1.6"
                - Organization: "CDISC"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "SDTM and SDTMIG Conformance Rules"
                      Rule_Identifier:
                        Id: "CG0021"
                        Version: "1"
                      Version: "2.0"
                """);

        Result r = StandardsLinkageBackfill.process(sh, checks, true, new ArrayList<>());
        assertEquals(1, r.oracleTotal());
        assertEquals(0, r.oracleMatch(), "not an exact match (sheet has more)");
        assertEquals(0, r.oracleSuspect(), "monotonic superset is stale, not a suspect");
    }

    // ---- ADaM Standards oracle -------------------------------------------


    /**
     * A CDISC ADaM check with one {@code ADaMIG} standard entry per {@code versions} element, each
     * citing {@code crId}. This is the exact shape every {@code CDISC-AD*} rule ships.
     */
    private static String adamCheck(String ruleId, String crId, String... versions)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Core:\n  Id: \"").append(ruleId).append("\"\nStandards:\n")
                .append("- Organization: \"CDISC\"\n  Standards:\n");
        for (String v : versions)
        {
            sb.append("  - Name: \"ADaMIG\"\n    Version: \"").append(v).append("\"\n")
                    .append("    References:\n    - Origin: \"ADaM Conformance Rules\"\n")
                    .append("      Rule_Identifier:\n        Id: \"").append(crId)
                    .append("\"\n        Version: \"1\"\n      Version: \"5.0\"\n");
        }
        return sb.toString();
    }


    /** The projection the oracle compares against, written in the committed TSV format. */
    private static Path adamBaseline(Path dir, String... rows) throws IOException
    {
        Path tsv = dir.resolve("adam-rule-sets.tsv");
        StringBuilder sb = new StringBuilder("# fixture\n");
        for (String row : rows)
        {
            sb.append(row).append('\n');
        }
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
        return tsv;
    }


    private static AdamFinding finding(AdamOracle oracle, String ruleId)
    {
        return oracle.findings().stream().filter(f -> f.ruleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + ruleId));
    }


    /**
     * All four live verdicts on one corpus, each in its own vocabulary: an exact match, the
     * {@code +1.0} shape that {@code CR714} shipped (SUSPECT), the {@code -1.3} shape that
     * {@code CR591} shipped (MONOTONIC), and the {@code Fix #194} side rule whose omission of
     * {@code 1.0} is the point of the split (EXEMPT).
     */
    @Test
    void adamOracleClassifiesMatchSuspectStalenessAndTheSideRuleExemption(@TempDir Path dir)
        throws IOException
    {
        Path checks = dir.resolve("checks");
        checkFile(checks, "CDISC", "CDISC-AD0001", adamCheck("CDISC-AD0001", "CR1", "1.0", "1.1"));
        checkFile(checks, "CDISC", "CDISC-AD0714",
                adamCheck("CDISC-AD0714", "CR714", "1.0", "1.1", "1.2", "1.3"));
        checkFile(checks, "CDISC", "CDISC-AD0591", adamCheck("CDISC-AD0591", "CR591", "1.2"));
        checkFile(checks, "CDISC", "CDISC-AD0039",
                adamCheck("CDISC-AD0039", "CR39", "1.0", "1.1", "1.2", "1.3"));
        checkFile(checks, "CDISC", "CDISC-AD0039-A",
                adamCheck("CDISC-AD0039-A", "CR39", "1.1", "1.2", "1.3"));

        AdamSheet sheet = StandardsLinkageBackfill.readAdamBaseline(adamBaseline(dir,
                "CR1\t1.0,1.1", "CR714\t1.1,1.2,1.3", "CR591\t1.2,1.3", "CR39\t1.0,1.1,1.2,1.3"));
        AdamOracle oracle = StandardsLinkageBackfill.adamOracle(checks, sheet);

        assertEquals(5, oracle.total());
        assertEquals(AdamVerdict.MATCH, finding(oracle, "CDISC-AD0001").verdict());
        assertEquals(AdamVerdict.MATCH, finding(oracle, "CDISC-AD0039").verdict());
        assertEquals(AdamVerdict.SUSPECT, finding(oracle, "CDISC-AD0714").verdict(),
                "the corpus asserts 1.0, which the catalogue does not");
        assertEquals(AdamVerdict.MONOTONIC, finding(oracle, "CDISC-AD0591").verdict(),
                "the catalogue asserts 1.3, which the corpus omits");
        AdamFinding side = finding(oracle, "CDISC-AD0039-A");
        assertEquals(AdamVerdict.EXEMPT, side.verdict(),
                "a side rule whose parent carries the catalogue's full range is exempt, not stale");
        assertEquals("CDISC-AD0039", side.splitBase(),
                "the exemption names the parent it rests on");
        assertTrue(side.describe().contains("side rule of CDISC-AD0039"),
                "an exempted rule is visible in the report, not invisible: " + side.describe());
    }


    /**
     * ⚠ The exemption is not "any letter-suffixed id". It rests on the parent carrying the
     * catalogue's full range — that is what makes the sibling's narrower range an authored split
     * rather than two rules that are both stale.
     */
    @Test
    void adamOracleExemptionRequiresTheParentToCarryTheFullCatalogueRange(@TempDir Path dir)
        throws IOException
    {
        Path checks = dir.resolve("checks");
        // Parent is itself short of the catalogue -> the sibling's omission is NOT authored.
        checkFile(checks, "CDISC", "CDISC-AD0039",
                adamCheck("CDISC-AD0039", "CR39", "1.1", "1.2", "1.3"));
        checkFile(checks, "CDISC", "CDISC-AD0039-A",
                adamCheck("CDISC-AD0039-A", "CR39", "1.1", "1.2", "1.3"));
        // A letter-suffixed id whose base is in ANOTHER family is not a split group at all.
        checkFile(checks, "PMDA", "PMDA-AD0039A",
                adamCheck("PMDA-AD0039A", "CR39", "1.1", "1.2", "1.3"));

        AdamSheet sheet = StandardsLinkageBackfill
                .readAdamBaseline(adamBaseline(dir, "CR39\t1.0,1.1,1.2,1.3"));
        AdamOracle oracle = StandardsLinkageBackfill.adamOracle(checks, sheet);

        assertEquals(AdamVerdict.MONOTONIC, finding(oracle, "CDISC-AD0039-A").verdict(),
                "parent does not carry the full range, so nothing authorises the omission");
        assertEquals(AdamVerdict.MONOTONIC, finding(oracle, "PMDA-AD0039A").verdict(),
                "the split-group lookup is family-local: CDISC-AD0039 cannot ground a PMDA id");
        assertEquals(0, oracle.count(AdamVerdict.EXEMPT));
    }


    @Test
    void adamOracleReportsAnUnresolvableCitationRatherThanPassingIt(@TempDir Path dir)
        throws IOException
    {
        Path checks = dir.resolve("checks");
        checkFile(checks, "CDISC", "CDISC-AD9999", adamCheck("CDISC-AD9999", "CR9999", "1.3"));
        AdamSheet sheet = StandardsLinkageBackfill.readAdamBaseline(adamBaseline(dir, "CR1\t1.0"));
        AdamOracle oracle = StandardsLinkageBackfill.adamOracle(checks, sheet);
        assertEquals(AdamVerdict.UNRESOLVED, finding(oracle, "CDISC-AD9999").verdict());
        assertEquals(0, oracle.count(AdamVerdict.MATCH), "an unresolvable id is not a pass");
    }


    @Test
    void adamCatalogueProjectionRoundTripsThroughTheCommittedTsv(@TempDir Path dir)
        throws IOException
    {
        XSSFWorkbook wb = new XSSFWorkbook();
        sheet(wb, "Rules Catalogue",
                List.of(List.of("Rule ID",
                        "Rule Set (Generally IG Version, OCCDS v1.0, ADNCA v1.0)"),
                        List.of("152", "1"), List.of("152", "1.1"), List.of("152", "1.2"),
                        List.of("714", "1.1"), List.of("714", "1.3"), List.of("", "1.3")));
        book(dir, "ADaM Conformance Rules v5.0.xlsx", wb);

        AdamSheet loaded = StandardsLinkageBackfill.loadAdamSheet(dir);
        // The catalogue's bare `1` is normalised to the corpus spelling `1.0`; a blank id is
        // skipped rather than folded into a phantom rule.
        assertEquals(Set.of("1.0", "1.1", "1.2"), loaded.versions("CR152"));
        assertEquals(Set.of("1.1", "1.3"), loaded.versions("CR714"));
        assertEquals(2, loaded.versionsById().size());

        Path tsv = dir.resolve("out.tsv");
        StandardsLinkageBackfill.writeAdamBaseline(tsv, loaded);
        assertEquals(List.of("CR152\t1.0,1.1,1.2", "CR714\t1.1,1.3"),
                Files.readAllLines(tsv, StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.startsWith("#")).toList(),
                "rows are ordered by numeric CR id");
        assertEquals(loaded.versionsById(),
                StandardsLinkageBackfill.readAdamBaseline(tsv).versionsById());
    }


    /**
     * ⚠ Neuter-and-watch. A copy of the shipped {@code CDISC-AD0714} — corrected by this wave —
     * with its {@code 1.0} entry spliced back in must be classified SUSPECT. Without this the "zero
     * SUSPECT" assertion below could be vacuous.
     */
    @Test
    void reinjectingTheCorrectedSpuriousVersionIsDetectedAsSuspect(@TempDir Path dir)
        throws IOException
    {
        Path shipped = basedir().resolve("rules-src/checks/CDISC/CDISC-AD0714.yaml");
        String text = Files.readString(shipped, StandardCharsets.UTF_8);
        assertFalse(text.contains("Version: \"1.0\""),
                "the shipped rule must be the corrected one for this test to mean anything");
        String spurious = """
                  - Name: "ADaMIG"
                    Version: "1.0"
                    References:
                    - Origin: "ADaM Conformance Rules"
                      Rule_Identifier:
                        Id: "CR714"
                        Version: "1"
                      Version: "5.0"
                """;
        Path checks = dir.resolve("checks");
        checkFile(checks, "CDISC", "CDISC-AD0714",
                text.replace("  - Name: \"ADaMIG\"\n    Version: \"1.1\"\n",
                        spurious + "  - Name: \"ADaMIG\"\n    Version: \"1.1\"\n"));

        AdamOracle oracle = StandardsLinkageBackfill.adamOracle(checks, shippedCatalogue());
        assertEquals(1, oracle.count(AdamVerdict.SUSPECT),
                "the reinjected 1.0 must red: " + oracle.findings());
        assertEquals("CDISC-AD0714", oracle.of(AdamVerdict.SUSPECT).get(0).ruleId());
    }


    /**
     * THE CHECK. Every shipped ADaM {@code Standards} block agrees with the catalogue, or is an
     * authored side-rule exemption. ⚠ Only SUSPECT fails: a monotonic difference means the sheet
     * has moved ahead of the corpus, which is staleness rather than a defect, so it is reported.
     */
    @Test
    void shippedAdamStandardsCarryNoSuspectAgainstTheCatalogue() throws IOException
    {
        AdamOracle oracle = StandardsLinkageBackfill
                .adamOracle(basedir().resolve("rules-src/checks"), shippedCatalogue());

        assertEquals(List.of(),
                oracle.of(AdamVerdict.SUSPECT).stream().map(AdamFinding::describe).toList(),
                "a corpus version the catalogue does not assert manufactures coverage");
        assertEquals(List.of(),
                oracle.of(AdamVerdict.UNRESOLVED).stream().map(AdamFinding::describe).toList(),
                "every cited CR id resolves in the catalogue");
        // Reported, not failed — but pinned, so a NEW staleness has to be looked at.
        assertEquals(List.of(),
                oracle.of(AdamVerdict.MONOTONIC).stream().map(AdamFinding::describe).toList(),
                "no rule omits a version the catalogue asserts");
        // The Fix #194 side rules: EXEMPT and named, never silently passed.
        assertEquals(List.of("CDISC-AD0039-A", "CDISC-AD0040-A"),
                oracle.of(AdamVerdict.EXEMPT).stream().map(AdamFinding::ruleId).sorted().toList(),
                "the abbreviated-suffix side rules omit 1.0 by design (illegal in ADaMIG 1.0)");
        assertEquals(oracle.total(),
                oracle.count(AdamVerdict.MATCH) + oracle.count(AdamVerdict.EXEMPT),
                "every compared rule is either an exact match or an authored exemption");
    }


    private static Path basedir()
    {
        return Path.of(System.getProperty("projectBasedir"));
    }


    private static AdamSheet shippedCatalogue() throws IOException
    {
        return StandardsLinkageBackfill.readAdamBaseline(
                basedir().resolve("documentation/rule-review-results/adam-rule-sets.tsv"));
    }

    // ---- helpers ----------------------------------------------------------


    /** A minimal identity FDA check with one SDTMIG 3.4 standard entry citing {@code nativeId}. */
    private static String identityFda(String nativeId)
    {
        return """
                Core:
                  Id: "FDA-%s"
                Standards:
                - Organization: "FDA"
                  Standards:
                  - Name: "SDTMIG"
                    Version: "3.4"
                    References:
                    - Origin: "FDA Validator Rules"
                      Rule_Identifier:
                        Id: "%s"
                        Version: "1"
                      Version: "1.6"
                """.formatted(nativeId, nativeId);
    }
}
