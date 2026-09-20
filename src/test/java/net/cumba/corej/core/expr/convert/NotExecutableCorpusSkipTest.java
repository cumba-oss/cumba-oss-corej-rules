package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code Fix #159}, {@code plans/done/PLAN-not-executable-parks-a-rule.md} — the corpus-generation
 * half.
 *
 * <p>
 * A rule declaring {@code Executability: "Not Executable"} must never reach a shipped package.
 * {@link RuleStorageAssembler} loads {@code rules-src/} for stage 0 of
 * {@code scripts/regen-rules-corpus.sh} ({@code RuleCorpusGenerator}), and the skip sits on that
 * loaded {@code checks} map — <em>upstream of the fan-out</em>, so the rule reaches neither the
 * legacy branch nor the native one. That is what parks it in the Python fork as well, since
 * {@code rules-legacy/} is the fork's corpus and the fork has no {@code Executability}-driven load
 * behaviour of its own.
 * </p>
 *
 * <p>
 * ⚠ Until 2026-08-06 the reason was transitivity: {@code rules/} was derived <em>from</em>
 * {@code rules-legacy/}, so omitting a rule from the latter omitted it from the former. The corpora
 * now derive independently; the conclusion survives only because of where the filter sits. Hence
 * {@link RuleCorpusGeneratorTest#aParkedRuleIsAbsentFromTheCorpus(java.nio.file.Path)}, which
 * checks both emitted corpora rather than only the assembled node.
 * </p>
 *
 * <p>
 * ⚠ <b>The fixture corpus is minimal and otherwise valid.</b> The parked rule and the control
 * differ <em>only</em> in their {@code Executability} value and their id; both name the same
 * {@code (Standard, Version)}, so both would land in the same package. If the control did not
 * appear in the output, "the parked rule is absent" would be a statement about the fixture rather
 * than about the skip.
 * </p>
 *
 * <p>
 * The byte-identity of the <em>shipped</em> corpora is the separate concern of
 * {@link NativeCorpusRoundTripTest} and {@code RulesFixtureSyncTest}: zero rules are currently
 * {@code Not Executable}, so those must be unaffected. That is the gate; this is the proof the skip
 * does anything at all.
 * </p>
 */
class NotExecutableCorpusSkipTest
{

    private static final String PARKED_ID = "CDISC-ZZ0001";

    private static final String CONTROL_ID = "CDISC-ZZ0002";

    /**
     * Writes a minimal but complete {@code rules-src/checks} YAML file. Only the id and the
     * {@code Executability} value vary between the two fixtures.
     */
    private static void writeCheck(Path checksDir, String id, String executability)
        throws IOException
    {
        Files.writeString(checksDir.resolve(id + ".yaml"), """
                Core:
                  Id: %s
                  Status: Published
                  Version: "1"
                Executability: "%s"
                Sensitivity: "Record"
                Standards:
                  - Organization: "CDISC"
                    Standards:
                      - Name: "SDTMIG"
                        Version: "3.4"
                Scope:
                  Domains:
                    Include:
                      - AE
                Check:
                  all:
                    - name: AETERM
                      operator: empty
                Outcome:
                  Message: "AETERM must not be empty"
                  Output_Variables:
                    - AETERM
                """.formatted(id, executability), StandardCharsets.UTF_8);
    }


    private static Path corpus(Path root) throws IOException
    {
        Path checks = Files.createDirectories(root.resolve("checks"));
        Files.createDirectories(root.resolve("docs"));
        writeCheck(checks, PARKED_ID, "Not Executable");
        writeCheck(checks, CONTROL_ID, "Fully Executable");
        return root;
    }


    @Test
    void aNotExecutableRuleNeverReachesAnAssembledPackage(@TempDir Path aRoot) throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler(corpus(aRoot));

        List<Triple> packages = assembler.discoverPackages();
        assertEquals(1, packages.size(),
                () -> "both fixtures name the same (family, standard, version): " + packages);
        Triple t = packages.getFirst();
        ObjectNode pkg = assembler.assemblePackage(t.family(), t.standard(), t.version());

        // The control first: if THIS is absent the fixture is broken, not the skip.
        assertTrue(pkg.get("rules").has(CONTROL_ID),
                () -> "the executable control must be assembled: " + pkg.get("rules").fieldNames());
        assertFalse(pkg.get("rules").has(PARKED_ID),
                () -> "the parked rule must be omitted: " + pkg);
        assertEquals(1, pkg.get("rules").size(), () -> "exactly the control survives: " + pkg);
    }


    @Test
    void aWhollyParkedPackageContributesNoTupleAtAll(@TempDir Path aRoot) throws IOException
    {
        // The skip sits on the loaded check tree, before discoverPackages() crosses families with
        // standards — so a (family, standard, version) reached ONLY by parked rules is never
        // emitted at all, rather than emitted empty. A skip applied at assembly time would leave
        // an empty package file and a manifest entry behind.
        Path checks = Files.createDirectories(aRoot.resolve("checks"));
        Files.createDirectories(aRoot.resolve("docs"));
        writeCheck(checks, PARKED_ID, "Not Executable");

        assertEquals(List.of(), new RuleStorageAssembler(aRoot).discoverPackages(),
                "an all-parked tuple produces no package");
    }


    @Test
    void everyOtherExecutabilityValueIsAssembledNormally(@TempDir Path aRoot) throws IOException
    {
        // ⚠ Only the exact "Not Executable" declaration skips. A partially-executable rule still
        // ships.
        Path checks = Files.createDirectories(aRoot.resolve("checks"));
        Files.createDirectories(aRoot.resolve("docs"));
        writeCheck(checks, "CDISC-ZZ0003", "Partially Executable");
        writeCheck(checks, "CDISC-ZZ0004", "Partially Executable - Possible Underreporting");
        writeCheck(checks, "CDISC-ZZ0005", "Partially Executable - Possible Overreporting");
        writeCheck(checks, "CDISC-ZZ0006", "Fully Executable");

        RuleStorageAssembler assembler = new RuleStorageAssembler(aRoot);
        Triple t = assembler.discoverPackages().getFirst();
        ObjectNode pkg = assembler.assemblePackage(t.family(), t.standard(), t.version());

        // All four non-parking values of the enum, so "nothing but Not Executable" is a statement
        // about the whole enum rather than about the three values that happened to be listed.
        assertEquals(4, pkg.get("rules").size(),
                () -> "nothing but \"Not Executable\" is skipped: " + pkg);
    }

}
