package net.cumba.cdisc.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Assertion of the Define-Item-Metadata specs (CORE-001081, CORE-000929). Exercises the harness's
 * {@code define_xml} mock support end to end: {@link SpecRunner} builds a define-level provider
 * from the spec's {@code define_xml} block, iterates the define ItemDefs, and fires where the
 * define role differs from the library role — producing the canonical finding pinned in the spec's
 * {@code expected_violations} (originally captured from the since-removed Python lane's
 * {@code spec_runner.py --mode assert}; the pins are Java-canonical now).
 */
class DefineItemMetadataSpecTest
{

    private static Path spec(String name)
    {
        return Path.of(System.getProperty("projectBasedir"), "rulespec", "specs", name);
    }


    @Test
    void core1081_definedRoleMismatch_matchesExpected() throws IOException
    {
        Path specFile = spec("CORE-001081.yaml");
        assertTrue(Files.exists(specFile), "CORE-001081 parity spec must exist: " + specFile);

        SpecRunner.SpecResult result = SpecRunner.run(specFile, SpecRunner.Mode.ASSERT);

        assertTrue(result.passed(),
                () -> "Java lane must match expected_violations:\n" + result.formatDiff());
        // Sensitivity is Record (PLAN-derive-rule-type-sensitivity, Group B), so EVERY offending
        // ItemDef is reported: the fixture declares a role mismatch on both LBORRES and LBTESTCD.
        // Under the former Dataset sensitivity only the first survived, which is the collapse the
        // Group-B change removed — asserting 2 here is what keeps that regression visible.
        assertEquals(2, result.observed().size(),
                "one define-item finding per offending ItemDef (LBORRES, LBTESTCD)");
    }


    @Test
    void core0929_unpublishedDomainCode_matchesExpected() throws IOException
    {
        Path specFile = spec("CORE-000929.yaml");
        assertTrue(Files.exists(specFile), "CORE-000929 parity spec must exist: " + specFile);

        SpecRunner.SpecResult result = SpecRunner.run(specFile, SpecRunner.Mode.ASSERT);

        assertTrue(result.passed(),
                () -> "Java lane must match expected_violations:\n" + result.formatDiff());
        assertEquals(1, result.observed().size(), "exactly one define-item finding (DOMAIN)");
    }
}
