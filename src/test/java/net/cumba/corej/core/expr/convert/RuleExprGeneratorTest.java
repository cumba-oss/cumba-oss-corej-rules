package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link RuleExprGenerator} — since
 * {@code plans/done/PLAN-retire-corpus-transforms.md} phase 1 the package <em>writer</em>: it
 * serialises the assembled packages, writes the manifest and prunes stale packages. The
 * unconvertible-leaf inventory and the derived-field strip it used to carry are retired.
 */
class RuleExprGeneratorTest
{

    /** A package as {@code RuleStorageAssembler} emits it since the flip, {@code id} keyed. */
    private static String assembledPackage(String id)
    {
        return """
                {
                  "rules" : {
                    "%s" : {
                      "Core" : { "Id" : "%s" },
                      "Description" : "d",
                      "Executability" : "Fully Executable",
                      "Check" : { "expression" : "empty(AETERM)" }
                    }
                  }
                }
                """.formatted(id, id);
    }


    private static RulePackageManifest manifestFor(String... files)
    {
        List<RulePackageManifest.Entry> entries = new ArrayList<>();
        for (String file : files)
        {
            entries.add(
                    new RulePackageManifest.Entry(file, "CDISC", "SDTMIG", "3.4", 1, List.of()));
        }
        return new RulePackageManifest("rules-src", entries);
    }


    /**
     * The seam the corpus generator drives: {@code generate} is a pure function of its inputs — no
     * source directory is read — and it performs the whole writer tail (write, manifest, prune)
     * into the directory it is handed.
     */
    @Test
    void generateWritesEveryPackageTheManifestDeclaresPlusTheManifestItself(@TempDir Path aDir)
        throws IOException
    {
        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("rules-a.json", assembledPackage("CDISC-ZZ0001"));
        packages.put("rules-b.json", assembledPackage("CDISC-ZZ0002"));
        // A leftover from a previous corpus shape: the prune must remove it.
        Files.writeString(aDir.resolve("rules-stale.json"), "{}", StandardCharsets.UTF_8);
        // …and a non-package file must survive it.
        Files.writeString(aDir.resolve("notes.txt"), "keep me", StandardCharsets.UTF_8);

        RuleExprGenerator.generate(packages, manifestFor("rules-a.json", "rules-b.json"), aDir);

        assertTrue(Files.exists(aDir.resolve("rules-a.json")));
        assertTrue(Files.exists(aDir.resolve("rules-b.json")));
        assertFalse(Files.exists(aDir.resolve("rules-stale.json")),
                "a package the manifest no longer declares must be pruned");
        assertTrue(Files.exists(aDir.resolve("notes.txt")), "a non-package file must survive");
        assertEquals(2, RulePackageManifest.load(aDir).packages().size(),
                "the authoritative manifest must be emitted alongside the packages");
    }


    @Test
    void generateRetiresTheStaleTextManifest(@TempDir Path aDir) throws IOException
    {
        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("rules-a.json", assembledPackage("CDISC-ZZ0001"));
        Files.writeString(aDir.resolve("packages.txt"), "stale", StandardCharsets.UTF_8);

        RuleExprGenerator.generate(packages, manifestFor("rules-a.json"), aDir);

        assertFalse(Files.exists(aDir.resolve("packages.txt")),
                "the legacy text manifest is deleted; packages.json replaces it");
    }


    @Test
    void generateRefusesToConvertWhenAManifestDeclaredPackageWasNotAssembled(@TempDir Path aDir)
    {
        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("rules-a.json", assembledPackage("CDISC-ZZ0001"));

        IOException thrown = assertThrows(IOException.class, () -> RuleExprGenerator
                .generate(packages, manifestFor("rules-a.json", "rules-b.json"), aDir));
        assertTrue(thrown.getMessage().contains("rules-b.json"), thrown.getMessage());
    }


    @Test
    void generateRefusesAnEmptyManifest(@TempDir Path aDir)
    {
        assertThrows(IOException.class, () -> RuleExprGenerator.generate(Map.of(),
                new RulePackageManifest(List.of()), aDir));
    }

}
