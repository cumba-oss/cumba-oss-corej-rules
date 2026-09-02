package net.cumba.cdisc.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link UniqueSetFlattener} on the five Plan A §2.3 call-site shapes, the order/no-dedup contract,
 * idempotence, and the end-to-end writer run that the 2026-08-23 corpus migration used.
 */
class UniqueSetFlattenerTest
{

    private static String flatten(String text)
    {
        return ExpressionPrinter
                .print(UniqueSetFlattener.flattenUniqueSet(CheckExpressionParser.parse(text)));
    }


    /** The five authored shapes of §2.3 (the 134 / 17 / 6 / 2 populations + the AD0688 repeat). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value =
    {
            "not is_unique_set(USUBJID, keys=[DOMAIN])|not is_unique_set([USUBJID, DOMAIN])",
            "not is_unique_set(IETESTCD, DOMAIN)|not is_unique_set([IETESTCD, DOMAIN])",
            "not is_unique_set(USUBJID)|not is_unique_set([USUBJID])",
            "not is_unique_set(--REPNUM, keys=[USUBJID, --TESTCD, $TIMING_VARIABLES], regex=\"^(.*)$\")"
                    + "|not is_unique_set([--REPNUM, USUBJID, --TESTCD, $TIMING_VARIABLES], regex=\"^(.*)$\")",
            "not is_unique_set(USUBJID, keys=[USUBJID, SPDEVID])"
                    + "|not is_unique_set([USUBJID, USUBJID, SPDEVID])",
            "is_not_unique_set(A, keys=[B, C])|is_not_unique_set([A, B, C])",
            "not is_unique_set([A, B])|not is_unique_set([A, B])",
    })
    void theFiveShapesFlattenInAuthoredOrderWithoutDedup(String authored, String expected)
    {
        assertEquals(expected, flatten(authored));
        assertEquals(expected, flatten(expected), "the single-list form is a fixed point");
    }


    @Test
    void nestedSitesAreRewrittenAndEverythingElseIsRebuiltUnchanged()
    {
        String authored = "var_exists(USUBJID) and (not is_unique_set(USUBJID, keys=[DOMAIN])"
                + " or not is_unique_set(STUDYID, keys=[$define_key_variables]))"
                + " and not is_inconsistent_across_dataset(--TEST, keys=[--TESTCD])"
                + " and AESEV in [\"MILD\"]";
        AtomicInteger sites = new AtomicInteger();
        Expr out = UniqueSetFlattener.flattenUniqueSet(CheckExpressionParser.parse(authored),
                sites);
        assertEquals("var_exists(USUBJID) and (not is_unique_set([USUBJID, DOMAIN])"
                + " or not is_unique_set([STUDYID, $define_key_variables]))"
                + " and not is_inconsistent_across_dataset(--TEST, keys=[--TESTCD])"
                + " and AESEV in [\"MILD\"]", ExpressionPrinter.print(out));
        assertEquals(2, sites.get(), "only the pair's sites count; the sibling keeps keys=");
    }


    /** The migration run: the writer re-renders only the Check block, in D27 layout. */
    @Test
    void theWriterRunRewritesTheCheckBlockOnly(@TempDir Path dir) throws IOException
    {
        String before = """
                Core:
                  Id: "CDISC-ZZ0688"
                  Status: "Published"
                  Version: "1"
                Description: "Fixture."
                Executability: "Fully Executable"
                Scope:
                  Domains:
                    Include:
                    - "ADDL"
                Check:
                  expression: >-
                    not is_unique_set(USUBJID, keys=[USUBJID, SPDEVID])
                Outcome:
                  Message: "More than one record."
                  Output_Variables:
                  - "SPDEVID"
                """;
        Path checks = Files.createDirectories(dir.resolve("checks").resolve("CDISC"));
        Files.writeString(checks.resolve("CDISC-ZZ0688.yaml"), before, UTF_8);
        Path out = dir.resolve("out");
        AtomicInteger sites = new AtomicInteger();
        RuleSrcExpressionWriter.Result r = RuleSrcExpressionWriter.run(dir.resolve("checks"), out,
                e -> UniqueSetFlattener.flattenUniqueSet(e, sites));
        assertEquals(1, r.filesOut());
        assertTrue(r.unraisable().isEmpty(), r.unraisable().toString());
        assertEquals(1, sites.get());
        String written = Files.readString(out.resolve("CDISC").resolve("CDISC-ZZ0688.yaml"), UTF_8);
        assertEquals(before.replace("not is_unique_set(USUBJID, keys=[USUBJID, SPDEVID])",
                "not is_unique_set([USUBJID, USUBJID, SPDEVID])"), written);
    }
}
