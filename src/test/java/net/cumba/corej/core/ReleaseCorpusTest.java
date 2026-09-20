package net.cumba.corej.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The release boundary: {@code target/rules-release/} is {@code target/rules/} minus the
 * {@link CorpusPaths#UNRELEASED_FAMILY} family, and it is the tree the assembly packs.
 *
 * <h2>Why the exclusion lives here and not in the assembler</h2>
 *
 * <p>
 * DRAFT rules are a <b>live authoring lane</b> — parked candidates to be revisited and promoted,
 * not abandoned ones. They must therefore keep being generated, schema-validated, lint-scanned and
 * executed by their {@code .cdt} scenarios and specs exactly like every other family. Some thirty
 * corpus tests reach the corpus through {@link CorpusPaths#rules()}; dropping DRAFT there would
 * have taken all of them off the drafts in a single edit, with nothing red — the drafts would rot
 * unwatched, which is precisely what keeping them is meant to prevent. So the full corpus stays
 * whole and the exclusion happens once, at the last step before the archive.
 * </p>
 *
 * <h2>⚠ What this class is defending against</h2>
 *
 * <p>
 * Two trees can drift, and an exclusion test can stop excluding anything. Both failure modes are
 * covered deliberately:
 * </p>
 * <ul>
 * <li>{@link #theReleaseTreeIsAByteIdenticalSubsetOfTheCorpus()} — a released package is not merely
 * <em>similar</em> to the validated one, it is the same bytes. The two trees are written from one
 * assembled node in one run, and this is what keeps that true.</li>
 * <li>{@link #theCorpusActuallyCarriesTheUnreleasedFamily()} — the non-vacuity half. If the DRAFT
 * family ever vanished from {@code rules-src/}, every assertion below would pass over nothing and
 * report a clean exclusion of an empty set. That must red instead, so that "we stopped shipping
 * drafts" can never quietly become "we stopped having drafts".</li>
 * </ul>
 */
class ReleaseCorpusTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Floor on the packages the release tree must carry — 43 at the time of writing (2026-09-07):
     * 58 generated packages less the 15 of the DRAFT family. A floor, not an equality, so corpus
     * growth does not red it; it exists so a mis-resolved or half-written release tree cannot pass
     * as a clean one.
     */
    // 40 -> 30 (PLAN-retire-core-family phase 6). The release tree is the full 58 less DRAFT's
    // 15; CORE's 11 were all released, so 43 -> 32. Floor set below the measurement, not on it.
    private static final int MIN_RELEASE_PACKAGES = 30;

    private static final String UNRELEASED_PREFIX = CorpusPaths.UNRELEASED_FAMILY + "-";

    private static List<Path> packagesIn(Path aFolder) throws IOException
    {
        try (Stream<Path> files = Files.list(aFolder))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json");
            }).sorted().toList();
        }
    }


    private static Set<String> namesIn(Path aFolder) throws IOException
    {
        Set<String> names = new TreeSet<>();
        for (Path p : packagesIn(aFolder))
        {
            names.add(p.getFileName().toString());
        }
        return names;
    }


    /** The package files the folder's own {@code packages.json} declares, by family. */
    private static Set<String> manifestFiles(Path aFolder, boolean aUnreleasedOnly)
        throws IOException
    {
        Set<String> files = new TreeSet<>();
        for (RulePackageManifest.Entry e : RulePackageManifest.load(aFolder).packages())
        {
            if (CorpusPaths.UNRELEASED_FAMILY.equals(e.family()) == aUnreleasedOnly)
            {
                files.add(e.file());
            }
        }
        return files;
    }


    /**
     * ⭐ The non-vacuity guard. Every other test here asserts an absence; without a proven presence
     * on the other side they would all pass over an empty set.
     */
    @Test
    void theCorpusActuallyCarriesTheUnreleasedFamily() throws IOException
    {
        Set<String> unreleased = manifestFiles(CorpusPaths.rules(), true);
        assertFalse(unreleased.isEmpty(),
                "the full corpus at " + CorpusPaths.rules() + " declares no "
                        + CorpusPaths.UNRELEASED_FAMILY + " package at all. Either the family was"
                        + " deleted from rules-src/ — in which case say so deliberately and retire"
                        + " this whole lane — or the corpus did not generate. Until then, every"
                        + " exclusion assertion in this class would be checking nothing.");
    }


    /** No package file of the unreleased family may exist in the release tree. */
    @Test
    void theReleaseTreeCarriesNoUnreleasedPackage() throws IOException
    {
        List<String> leaked = new ArrayList<>();
        for (RulePackageManifest.Entry e : RulePackageManifest.load(CorpusPaths.releaseRules())
                .packages())
        {
            if (CorpusPaths.UNRELEASED_FAMILY.equals(e.family()))
            {
                leaked.add(e.file());
            }
        }
        assertTrue(leaked.isEmpty(), "the release corpus declares " + CorpusPaths.UNRELEASED_FAMILY
                + " packages, which must never be released: " + leaked);

        List<String> onDisk = namesIn(CorpusPaths.releaseRules()).stream()
                .filter(n -> n.startsWith("rules-"
                        + CorpusPaths.UNRELEASED_FAMILY.toLowerCase(java.util.Locale.ROOT) + "-"))
                .toList();
        assertTrue(onDisk.isEmpty(),
                "unreleased package files are sitting in " + CorpusPaths.releaseRules()
                        + " even though the manifest does not declare them — a stale tree the"
                        + " pruner did not clean: " + onDisk);
    }


    /**
     * Nor may a rule of the unreleased family reach a released package by any other route — a
     * mis-derived family on an entry, or a draft id authored into another family's folder.
     */
    @Test
    void noUnreleasedRuleIdReachesAReleasedPackage() throws IOException
    {
        List<String> leaked = new ArrayList<>();
        for (Path pkg : packagesIn(CorpusPaths.releaseRules()))
        {
            JsonNode rules = JSON.readTree(Files.readString(pkg, UTF_8)).path("rules");
            for (java.util.Map.Entry<String, JsonNode> rule : rules.properties())
            {
                if (rule.getKey().startsWith(UNRELEASED_PREFIX))
                {
                    leaked.add(pkg.getFileName() + " -> " + rule.getKey());
                }
            }
        }
        assertTrue(leaked.isEmpty(), "unpublished " + CorpusPaths.UNRELEASED_FAMILY
                + " rules reached the release" + " corpus: " + leaked);
    }


    /**
     * The release tree is exactly the corpus minus the unreleased family — neither a package fewer
     * (a released rule silently dropped) nor one more (a stale file the pruner missed).
     */
    @Test
    void theReleaseTreeIsTheCorpusMinusTheUnreleasedFamily() throws IOException
    {
        Set<String> expected = manifestFiles(CorpusPaths.rules(), false);
        assertEquals(expected, namesIn(CorpusPaths.releaseRules()),
                "the release tree must hold every non-" + CorpusPaths.UNRELEASED_FAMILY
                        + " package of the corpus and nothing else");
        assertEquals(expected, manifestFiles(CorpusPaths.releaseRules(), false),
                "the release manifest must declare exactly the files beside it — a manifest naming"
                        + " a package the archive does not carry makes every consumer fail at"
                        + " load, and one omitting a package hides it from rule selection");
        assertTrue(expected.size() >= MIN_RELEASE_PACKAGES,
                "the release corpus holds " + expected.size() + " packages, below the floor of "
                        + MIN_RELEASE_PACKAGES + " — a partial or mis-resolved tree, not a"
                        + " release");
    }


    /**
     * ⭐ Byte identity, not equivalence: what ships is what the corpus tests validated. Both trees
     * are written from the same assembled node in the same generator run, so any difference here
     * means the release path re-derived something it should merely have copied.
     */
    @Test
    void theReleaseTreeIsAByteIdenticalSubsetOfTheCorpus() throws IOException
    {
        List<String> differing = new ArrayList<>();
        for (Path released : packagesIn(CorpusPaths.releaseRules()))
        {
            Path full = CorpusPaths.rules().resolve(released.getFileName().toString());
            if (!Files.isRegularFile(full))
            {
                differing.add(released.getFileName() + " (absent from the full corpus)");
            }
            else if (Files.mismatch(released, full) != -1L)
            {
                differing.add(released.getFileName().toString());
            }
        }
        assertTrue(differing.isEmpty(),
                "released packages differ from the corpus packages the tests validated: "
                        + differing);
    }

}
