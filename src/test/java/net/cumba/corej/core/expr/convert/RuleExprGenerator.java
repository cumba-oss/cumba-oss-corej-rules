package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.authoring.ReleaseShapeTrimmer;
import net.cumba.corej.authoring.RulePackageConverter;
import net.cumba.corej.authoring.RulePackageConverter.ConversionReport;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageManifest;

/**
 * The package <em>writer</em> of the corpus regeneration flow: serialises the already-assembled
 * packages into the shipped {@code rules/} corpus, writes the authoritative {@code packages.json}
 * manifest, retires the stale legacy {@code packages.txt}, and prunes packages no longer in the
 * corpus ({@link RulePackagePruner}).
 *
 * <p>
 * Until {@code plans/done/PLAN-retire-corpus-transforms.md} phase 1 this class also drove the
 * legacy-to-expression {@code Check} conversion, stripped the derived {@code Rule_Type} /
 * {@code Sensitivity} fields, and rendered a markdown inventory of unconvertible leaves. All three
 * are retired: the authored corpus has been expression-form since the wave-43 flip (so the
 * conversion pass carries every {@code Check} through verbatim — see
 * {@link RulePackageConverter#convertCheck}), the assembler no longer writes a derived value into
 * the node (it validates the derivation instead — see {@code RuleStorageAssembler}), and with zero
 * unconvertible leaves the inventory had nothing to inventory.
 * </p>
 *
 * <h2>⚑ It reads no directory, and it has no {@code main}</h2>
 *
 * <p>
 * Until 2026-08-06 this class had both: it listed {@code rules-legacy/} off disk and re-read
 * {@code rules-src/checks}, which made {@code rules/} a <em>derivative of the Python-facing
 * corpus</em> and made "never run {@code RuleExprGenerator} standalone" a documented trap.
 * {@code RuleCorpusGenerator} — stage 0 of {@code scripts/regen-rules-corpus.sh} — now assembles
 * once from {@code rules-src/} and hands this class the canonical nodes directly, so
 * {@link #generate} is a pure function of its inputs and the standalone entry point no longer
 * exists to be misused.
 * </p>
 *
 * <p>
 * The output directory is no longer a constant: since
 * {@code plans/done/PLAN-rules-corpus-build-integration.md} the corpus is generated into
 * {@code target/rules/} by the build rather than committed under the module directory, so
 * {@link RuleCorpusGenerator} passes the destination in and
 * {@link #generate(Map, RulePackageManifest, Path)} is the only entry point that writes.
 * {@link CorpusPaths#rules()} is where the default comes from.
 * </p>
 */
public final class RuleExprGenerator
{

    private RuleExprGenerator()
    {
    }


    /**
     * Writes the already-assembled packages as the shipped corpus, into the generated-corpus
     * directory {@link CorpusPaths#rules()} resolves.
     *
     * @param packages
     *            file name &rarr; serialised package JSON, in emission order
     * @param manifest
     *            the authoritative manifest to write beside the packages
     * @return the carry-through tally accumulated over every package
     * @throws IOException
     *             if a package cannot be processed or the output cannot be written
     */
    public static ConversionReport generate(Map<String, String> packages,
            RulePackageManifest manifest)
        throws IOException
    {
        return generate(packages, manifest, CorpusPaths.rules());
    }


    /**
     * Writes the already-assembled packages as the shipped corpus. <b>Pure function of its
     * inputs</b> — no source directory is read, which is what lets the assembly stage hand this
     * writer its canonical nodes directly instead of routing them through a corpus on disk.
     *
     * <p>
     * The {@code exprDir} overload exists for {@code NativeCorpusRoundTripTest}, which regenerates
     * the whole native corpus into a {@code @TempDir} and compares it byte for byte with the one
     * the build generated.
     * </p>
     *
     * @param packages
     *            file name &rarr; serialised package JSON, in emission order
     * @param manifest
     *            the authoritative manifest to write into {@code exprDir}
     * @param exprDir
     *            the output directory for the shipped packages
     * @return the carry-through tally accumulated over every package
     * @throws IOException
     *             if a package cannot be processed or the output cannot be written
     */
    public static ConversionReport generate(Map<String, String> packages,
            RulePackageManifest manifest, Path exprDir)
        throws IOException
    {
        // Completeness gate: every package the manifest declares must actually have been assembled
        // (replaces the old "expect at least 8" magic floor). A subset check — not an exact count —
        // so an unrelated non-package entry never trips it; a declared-but-missing package means
        // assembly and conversion disagree on the corpus.
        if (manifest.packages().isEmpty())
        {
            throw new IOException("the rule-package manifest is empty; assembly produced no"
                    + " packages, so there is nothing to convert");
        }
        for (RulePackageManifest.Entry e : manifest.packages())
        {
            if (!packages.containsKey(e.file()))
            {
                throw new IOException("manifest-declared package " + e.file()
                        + " was not assembled; assembly and conversion disagree on the corpus");
            }
        }
        Files.createDirectories(exprDir);

        ConversionReport report = new ConversionReport();
        for (Map.Entry<String, String> pkg : packages.entrySet())
        {
            String name = pkg.getKey();
            // Convert, then shape for RELEASE. The two are deliberately separate passes:
            // conversion is a content-preserving carry-through that several tests replay, while
            // ReleaseShapeTrimmer removes fields on purpose (PLAN-rules-corpus-build-integration
            // §10). Folding the strip into the converter would silently change what every one of
            // those tests thinks it is asserting.
            JsonNode converted = RulePackageConverter
                    .convertPackageNode(RulePackageConverter.readPackage(pkg.getValue()), report);
            String released = RulePackageConverter
                    .writePackage(ReleaseShapeTrimmer.trim(converted));
            Files.writeString(exprDir.resolve(name), released, StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT, "[gen] %-36s -> %s/%s%n", name, exprDir, name);
        }

        // The shipped corpus carries its own packages.json, so readers resolve
        // family/standard/version from the manifest and never by parsing file names.
        // RulePackageManifest.writeTo is the canonical serialisation.
        manifest.writeTo(exprDir);
        // Retire the stale legacy text manifest (D7): the typed packages.json replaces it.
        Files.deleteIfExists(exprDir.resolve("packages.txt"));
        // Prune any package no longer in the corpus (e.g. the pre-split
        // rules-<standard>-<version>.json names), so the directory holds exactly the manifest set.
        Set<String> keep = new LinkedHashSet<>();
        manifest.packages().forEach(e -> keep.add(e.file()));
        RulePackagePruner.pruneStalePackages(exprDir, keep);

        return report;
    }

}
