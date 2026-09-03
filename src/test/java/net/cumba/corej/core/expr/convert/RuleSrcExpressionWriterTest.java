package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.core.expr.convert.RuleSrcExpressionWriter.Conversion;
import net.cumba.corej.core.expr.convert.RuleSrcExpressionWriter.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused unit tests for {@link RuleSrcExpressionWriter} on synthetic fixtures: the byte-surgical
 * property (only the {@code Check:} block moves), the retired generic-presence residue and the
 * negative-presence canonicalisation ({@link net.cumba.corej.core.expr.PresenceSpelling}), and the
 * parked-rule handling. Since {@code PLAN-retire-corpus-transforms.md} phase 1 the writer accepts
 * only the expression-form corpus the flip produced — a legacy operator-leaf {@code Check} no
 * longer raises and is reported as unraisable. The whole-corpus figures live in
 * {@link RuleSrcExpressionWriterCorpusTest}.
 */
class RuleSrcExpressionWriterTest
{

    /**
     * A synthetic authored file in the corpus's canonical shape. The Description is deliberately a
     * multi-line folded double-quoted scalar and the Outcome carries distinctive spacing so byte
     * preservation is asserted against non-trivial surroundings.
     */
    private static final String PREFIX = """
            Core:
              Id: "CDISC-ZZ0201"
              Status: "Published"
              Version: "1"
            Description: "AETERM is populated and AESEV is MILD — a fixture Description long\\
              \\ enough to carry the corpus's folded double-quoted style."
            Executability: "Fully Executable"
            Scope:
              Domains:
                Include:
                - "AE"
            """;

    /**
     * An expression Check in a deliberately non-canonical spelling (plain scalar, negative presence
     * call), so the writer's canonicalising rewrite has real work to do.
     */
    private static final String AUTHORED_CHECK = """
            Check:
              expression: 'var_not_exists("AESTDTC") or not empty(AETERM) and AESEV == "MILD"'
            """;

    private static final String SUFFIX = """
            Outcome:
              Message: "AETERM must be populated with AESEV MILD"
              Output_Variables:
              - "AETERM"
              - "AESEV"
            Standards:
            - Organization: "CDISC"
              Standards:
              - Name: "SDTMIG"
                Version: "3.4"
            """;

    private static final String FIXTURE = PREFIX + AUTHORED_CHECK + SUFFIX;

    private static final String CANONICAL_EXPRESSION = "not var_exists(\"AESTDTC\")"
            + " or not empty(AETERM) and AESEV == \"MILD\"";

    // ---- the byte-surgical property ---------------------------------------

    @Test
    void onlyTheCheckBlockChangesEveryOtherByteIsPreserved()
    {
        Conversion c = RuleSrcExpressionWriter.convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"),
                FIXTURE);
        assertEquals(CANONICAL_EXPRESSION, c.expression());
        assertTrue(c.fileText().startsWith(PREFIX), "prefix bytes must be preserved exactly");
        assertTrue(c.fileText().endsWith(SUFFIX), "suffix bytes must be preserved exactly");
        assertEquals(PREFIX + RuleCanonicalizer.renderExpressionCheckBlock(c.expression()) + SUFFIX,
                c.fileText(), "the rewrite must be exactly prefix + rendered block + suffix");
        assertFalse(c.parked());
    }


    @Test
    void spliceReplacesOnlyTheCheckBlockAndRejectsMalformedFiles()
    {
        String block = "Check:\n  expression: >-\n    X\n";
        assertEquals(PREFIX + block + SUFFIX,
                RuleSrcExpressionWriter.spliceCheckBlock(FIXTURE, block, "id"));

        // A Check block at the very start of the file (no preceding byte) still splices.
        assertEquals(block + SUFFIX,
                RuleSrcExpressionWriter.spliceCheckBlock(AUTHORED_CHECK + SUFFIX, block, "id"));

        // A Check block terminated by EOF (no following top-level key) still splices.
        assertEquals(PREFIX + block,
                RuleSrcExpressionWriter.spliceCheckBlock(PREFIX + AUTHORED_CHECK, block, "id"));

        assertThrows(IllegalStateException.class,
                () -> RuleSrcExpressionWriter.spliceCheckBlock(PREFIX + SUFFIX, block, "id"),
                "a file without a top-level Check block must be rejected");
        assertThrows(
                IllegalStateException.class, () -> RuleSrcExpressionWriter
                        .spliceCheckBlock(FIXTURE + AUTHORED_CHECK, block, "id"),
                "a file with two top-level Check keys must be rejected");
    }

    // ---- the retired generic presence operator -----------------------------


    /**
     * The generic {@code exists} is retired (owner ruling 1 of
     * {@code PLAN-leaf-scope-domain-inference.md}): an authored generic call is written verbatim
     * and counted as residue — the loader rejects such a rule, and the corpus test holds the
     * residue at zero.
     */
    @Test
    void anAuthoredGenericExistsIsWrittenVerbatimAndCountedAsResidue()
    {
        String genericCheck = """
                Check:
                  expression: >-
                    exists("AETERM") and not empty(AETERM)
                """;
        Conversion c = RuleSrcExpressionWriter.convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"),
                PREFIX + genericCheck + SUFFIX);
        assertEquals("exists(\"AETERM\") and not empty(AETERM)", c.expression());
        assertTrue(RuleSrcExpressionWriter.carriesGenericPresenceCall(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(c.expression())));
        assertFalse(RuleSrcExpressionWriter
                .carriesGenericPresenceCall(net.cumba.corej.core.expr.CheckExpressionParser
                        .parse("var_exists(\"AETERM\") and not empty(AETERM)")));
    }

    // ---- the negative presence spelling (§5 correction, kept explicit) --------


    /**
     * An authored negative presence call must write the <em>shipped</em> spelling
     * {@code not ds_exists(...)} / {@code not var_exists(...)} — the shipped corpus carries zero
     * {@code ds_not_exists(}/{@code var_not_exists(} calls. This is
     * {@link net.cumba.corej.core.expr.PresenceSpelling} applied in the writer's canonical
     * rendering.
     */
    @Test
    void anAuthoredNegativePresenceSpellingWritesTheCanonicalForm()
    {
        String expressionFixture = PREFIX + """
                Check:
                  expression: >-
                    var_not_exists("AETERM") or ds_not_exists("ADSL") and not empty(AETERM)
                """ + SUFFIX;
        Conversion expression = RuleSrcExpressionWriter
                .convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"), expressionFixture);
        assertEquals("not var_exists(\"AETERM\") or not ds_exists(\"ADSL\") and not empty(AETERM)",
                expression.expression());
        assertFalse(expression.divergentFromPlainRaise(),
                "the plain raise is canonicalised the same way, so nothing diverges");
    }

    // ---- parked-rule handling ---------------------------------------------


    /** A parked rule converts in place like any other and is flagged, never skipped. */
    @Test
    void aParkedRuleConvertsInPlaceAndIsFlagged(@TempDir Path dir) throws IOException
    {
        String parked = FIXTURE.replace("Executability: \"Fully Executable\"",
                "Executability: \"Not Executable\"");
        Path checks = Files.createDirectories(dir.resolve("checks").resolve("CDISC"));
        Files.writeString(checks.resolve("CDISC-ZZ0201.yaml"), parked, UTF_8);
        Path out = dir.resolve("out");

        Result r = RuleSrcExpressionWriter.run(dir.resolve("checks"), out);
        assertEquals(1, r.filesIn());
        assertEquals(1, r.filesOut());
        assertEquals(java.util.Set.of("CDISC-ZZ0201"), r.parked());
        assertTrue(r.unraisable().isEmpty());
        String written = Files.readString(out.resolve("CDISC").resolve("CDISC-ZZ0201.yaml"), UTF_8);
        assertTrue(written.contains("Executability: \"Not Executable\""),
                "the parked marker must survive the rewrite");
        assertTrue(written.contains("expression: >-"), "the parked rule must still convert");
    }


    /**
     * A legacy operator-leaf Check no longer raises — the conversion machinery retired with
     * {@code PLAN-retire-corpus-transforms.md} phase 1, so any non-expression {@code Check} is
     * reported as unraisable and nothing is written.
     */
    @Test
    void aLegacyLeafCheckIsReportedUnraisableAndNothingIsWritten(@TempDir Path dir)
        throws IOException
    {
        String legacy = PREFIX + """
                Check:
                  all:
                  - name: "AETERM"
                    operator: "non_empty"
                """ + SUFFIX;
        Path checks = Files.createDirectories(dir.resolve("checks").resolve("CDISC"));
        Files.writeString(checks.resolve("CDISC-ZZ0201.yaml"), legacy, UTF_8);
        Path out = dir.resolve("out");

        Result r = RuleSrcExpressionWriter.run(dir.resolve("checks"), out);
        assertEquals(1, r.filesIn());
        assertEquals(0, r.filesOut());
        assertEquals(1, r.unraisable().size());
        assertTrue(r.unraisable().get(0).contains("CDISC-ZZ0201"), r.unraisable().toString());
        assertFalse(Files.exists(out.resolve("CDISC").resolve("CDISC-ZZ0201.yaml")));
    }

    // ---- the rewritten file's self-verification ---------------------------


    @Test
    void theRewrittenFileParsesBindsAndRoundTripsItsExpression()
    {
        Conversion c = RuleSrcExpressionWriter.convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"),
                FIXTURE);
        // verifyRewrittenFile ran inside convertFile; re-run it explicitly against a mutation to
        // prove it is not vacuous: a file whose Check carries a different expression must fail.
        assertNotNull(c.fileText());
        RuleSrcExpressionWriter.verifyRewrittenFile(c.fileText(), c.expression(), "CDISC-ZZ0201");
        assertThrows(IllegalStateException.class,
                () -> RuleSrcExpressionWriter.verifyRewrittenFile(c.fileText(),
                        c.expression() + " and empty(AESEV)", "CDISC-ZZ0201"));
        assertThrows(IllegalStateException.class, () -> RuleSrcExpressionWriter
                .verifyRewrittenFile("Check: [not, a, mapping", c.expression(), "CDISC-ZZ0201"));
    }

    // ---- Plan C §3.3: a level-keyed Check --------------------------------

    private static final String LEVELLED_CHECK = """
            Check:
              INFO:
                expression: 'empty(AETERM)'
                Message: "AETERM may be missing"
              ERROR:
                expression: 'empty(AESEV)'
            """;

    @Test
    void aLevelKeyedCheckIsRewrittenLevelByLevelAndNothingElseMoves()
    {
        Conversion c = RuleSrcExpressionWriter.convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"),
                PREFIX + LEVELLED_CHECK + SUFFIX);
        assertTrue(c.fileText().startsWith(PREFIX), "prefix bytes must be preserved exactly");
        assertTrue(c.fileText().endsWith(SUFFIX), "suffix bytes must be preserved exactly");
        assertEquals(2, c.expressions().size(), "both levels were raised");
        assertEquals("empty(AESEV)", c.expression(),
                "expression() is the STRICTEST level, matching Rule.getCheck()");
        assertEquals(java.util.List.of("empty(AESEV)", "empty(AETERM)"), c.expressions(),
                "strictest first, whatever order the levels were authored in");
        assertTrue(c.fileText().contains("""
                Check:
                  ERROR:
                    expression: >-
                      empty(AESEV)
                  INFO:
                    expression: >-
                      empty(AETERM)
                    Message: "AETERM may be missing"
                """), "rewritten file was:\n" + c.fileText());
    }


    @Test
    void aRewrittenLevelMapVerifiesEveryLevelNotOnlyTheStrictest()
    {
        Conversion c = RuleSrcExpressionWriter.convertFile(Path.of("CDISC", "CDISC-ZZ0201.yaml"),
                PREFIX + LEVELLED_CHECK + SUFFIX);
        RuleSrcExpressionWriter.verifyRewrittenLevels(c.fileText(),
                new java.util.LinkedHashSet<>(java.util.List.of("ERROR", "INFO")), c.expressions(),
                "CDISC-ZZ0201");
        assertThrows(IllegalStateException.class,
                () -> RuleSrcExpressionWriter.verifyRewrittenLevels(c.fileText(),
                        new java.util.LinkedHashSet<>(java.util.List.of("ERROR")),
                        java.util.List.of("empty(AESEV)"), "CDISC-ZZ0201"),
                "a level the file declares but the caller does not is a hard failure");
    }
}
