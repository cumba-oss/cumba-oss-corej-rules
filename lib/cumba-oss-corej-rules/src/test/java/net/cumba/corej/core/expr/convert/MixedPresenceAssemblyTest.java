package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The corpus-side <b>mixed-granularity prohibition is gone</b> ({@code PLAN-leaf-scope-domain-
 * inference.md} §2.4 items 1 and 3, phase 1): {@code RuleStorageAssembler} no longer lowers the
 * explicit presence calls to the retired generic {@code exists} and therefore no longer rejects a
 * {@code ds_} presence call beside a row read ("cannot lower … Re-author the rule instead"), nor a
 * {@code ds_exists} and a {@code var_exists} in one rule. This test pins the new contract on the
 * same one-rule synthetic corpus the retired {@code Fix #256} guard used: every one of those shapes
 * assembles, in expression form and in leaf form, and the assembled Check is the authored text
 * verbatim — the engine executes them (post-{@code Fix #255}), so the corpus tooling must carry
 * them.
 */
class MixedPresenceAssemblyTest
{

    /**
     * One-rule synthetic corpus. {@code Sensitivity} is authored explicitly so the assembler's
     * derivation step is skipped and the test stays focused on the presence-lowering pass.
     */
    private static Path corpus(Path aRoot, String aId, String aCheckBlock) throws IOException
    {
        Path checks = Files.createDirectories(aRoot.resolve("checks"));
        Files.createDirectories(aRoot.resolve("docs"));
        Files.writeString(checks.resolve(aId + ".yaml"), """
                Core:
                  Id: %s
                  Status: Published
                  Version: "1"
                Executability: "Fully Executable"
                Description: "Synthetic fixture for the mixed-presence assembly contract."
                Standards:
                  - Organization: "CDISC"
                    Standards:
                      - Name: "SDTMIG"
                        Version: "3.4"
                Scope:
                  Domains:
                    Include:
                      - AE
                Sensitivity: "Record"
                %s
                Outcome:
                  Message: "synthetic"
                  Output_Variables:
                    - AETERM
                """.formatted(aId, aCheckBlock), UTF_8);
        return aRoot;
    }


    /** Assembles every discovered package of the synthetic corpus, returning the last node. */
    private static ObjectNode assembleAll(Path aSrcDir) throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler(aSrcDir);
        List<Triple> triples = assembler.discoverPackages();
        assertEquals(1, triples.size(),
                "the one-rule corpus names one (family, standard, version)");
        Triple t = triples.getFirst();
        return assembler.assemblePackage(t.family(), t.standard(), t.version());
    }


    @Test
    void aDsPresenceGuardBesideARowReadAssemblesVerbatim(@TempDir Path aRoot) throws IOException
    {
        // §3.5 example 1 — the shape the assembler used to reject with "Re-author the rule
        // instead"; the engine has executed it since Fix #255 (Record Data, {ROW}).
        Path src = corpus(aRoot, "CDISC-ZZ9001", """
                Check:
                  expression: 'ds_exists("TV") and non_empty(AETERM)'""");
        ObjectNode canonical = assembleAll(src);
        assertEquals("ds_exists(\"TV\") and non_empty(AETERM)",
                canonical.get("rules").get("CDISC-ZZ9001").get("Check").get("expression").asText(),
                "the authored Check must assemble unchanged: " + canonical);
    }


    @Test
    void datasetAndColumnPresenceInOneRuleAssemble(@TempDir Path aRoot) throws IOException
    {
        // §3.5 example 3 / §2.4 item 3 — two presence directions in one Check, formerly an
        // assembly failure because the lowering could lower only one direction per rule.
        Path src = corpus(aRoot, "CDISC-ZZ9005", """
                Check:
                  expression: 'ds_exists("SUPPAE") and var_exists("AE.AESEQ")'""");
        ObjectNode canonical = assembleAll(src);
        assertTrue(canonical.get("rules").has("CDISC-ZZ9005"), canonical.toString());
    }


    @Test
    void aRightDirectionPresenceCallInWriterCanonicalShapeAssembles(@TempDir Path aRoot)
        throws IOException
    {
        Path src = corpus(aRoot, "CDISC-ZZ9004", """
                Check:
                  expression: 'not var_exists("AESTDTC") and non_empty(AETERM)'""");
        ObjectNode canonical = assembleAll(src);
        assertTrue(canonical.get("rules").has("CDISC-ZZ9004"),
                "the writer-canonical presence shape must assemble: " + canonical);
    }


    @Test
    void aNegativeSpellingNestedInsideACompositeAssemblesVerbatim(@TempDir Path aRoot)
        throws IOException
    {
        // The assembler no longer polices the negative spelling: the canonical `not var_exists`
        // form is the WRITER's job (PresenceSpelling, policed by the corpus drift test), and the
        // engine compiles either spelling to the same fact.
        Path src = corpus(aRoot, "CDISC-ZZ9002", """
                Check:
                  all:
                    - expression: 'var_not_exists(AESTDTC)'
                    - name: AETERM
                      operator: non_empty""");
        ObjectNode canonical = assembleAll(src);
        assertTrue(canonical.get("rules").has("CDISC-ZZ9002"), canonical.toString());
    }


    @Test
    void anExpressionCheckWithoutPresenceCallsStillAssembles(@TempDir Path aRoot) throws IOException
    {
        Path src = corpus(aRoot, "CDISC-ZZ9003", """
                Check:
                  expression: 'non_empty(AETERM) and AETERM != "ds_exists"'""");
        ObjectNode canonical = assembleAll(src);
        assertTrue(canonical.get("rules").has("CDISC-ZZ9003"),
                "a presence-free expression Check must assemble unchanged: " + canonical);
    }
}
