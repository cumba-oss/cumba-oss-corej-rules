package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.expr.convert.RulePackageConverter.ConversionReport;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;

/**
 * Offline developer tool — <b>stage 0</b> of the internal {@code regen-rules-corpus} workflow, and
 * the single producer of both shipped corpora. That workflow is an internal developer pipeline and
 * is not distributed with this repository, so every {@code regen-rules-corpus} mention below names
 * it rather than a file found here; in <em>this</em> repository the same stage runs unconditionally
 * from the build's {@code exec-maven-plugin} binding at {@code process-test-classes}.
 *
 * <h2>Assemble once, emit one corpus</h2>
 *
 * <pre>
 * rules-src/{checks,docs}
 *      │
 *      └── RuleStorageAssembler                assemble once:
 *            │                                   · Authorities join
 *            │                                   · Sensitivity derive-and-validate
 *            │                                   · Requirements materialisation
 *            │
 *            └── RuleExprGenerator.generate ──&gt; rules/ + packages.json
 * </pre>
 *
 * <p>
 * <b>⚑ Single-branch since wave 33.</b> This driver used to fan out: the same canonical node was
 * emitted twice, once through {@code applyLegacyDegradations} into the Python-facing
 * {@code rules-legacy/} corpus and once through {@link RuleExprGenerator} into {@code rules/}. That
 * branch, its five transforms and the corpus they wrote are gone with the Python parity lane
 * ({@code plans/PLAN-cut-rules-legacy.md}).
 * </p>
 *
 * <p>
 * ⚠ <b>Two safety constructs went with it, and their absence is not a regression.</b> The
 * {@code deepCopy()} guarding the native node existed only because {@code applyLegacyDegradations}
 * rewrote in place; with one branch there is nothing to protect it from, and nothing left that
 * could leak a degraded literal into {@code rules/}. Likewise the ordering constraint of the
 * pre-2026-08-06 {@code rules-src → rules-legacy → rules} chain, where {@link RuleExprGenerator}
 * read the Python-facing packages off disk and every transform applied for the fork reached the
 * Java corpus too. Both hazards are now structurally impossible rather than merely guarded.
 * </p>
 *
 * <p>
 * Test scope, like {@link RuleStorageAssembler}: {@code jackson-dataformat-yaml} is a test-scoped
 * dependency, so YAML never leaks into the engine jar. Test scope already sees main scope, which is
 * why this driver can call the main-scope converter directly.
 * </p>
 *
 * <p>
 * The <em>source</em> paths resolve against the working directory, which must be the module base
 * directory — the build's exec binding sets it, and the {@code regen-rules-corpus} workflow
 * {@code cd}s there. The <em>output</em> directory is a parameter (see {@link #main}); it is
 * {@code target/rules/} under the build, and nothing generated is committed any more
 * ({@code plans/PLAN-rules-corpus-build-integration.md}).
 * </p>
 */
public final class RuleCorpusGenerator
{

    private RuleCorpusGenerator()
    {
    }


    /**
     * Rebuilds the generated corpus from {@code rules-src/}.
     *
     * @param args
     *            optionally the output directory. When absent the destination comes from
     *            {@link CorpusPaths#rules()} — the {@code corpusDir} system property, else
     *            {@code <module>/target/rules}. The build passes it explicitly (see this module's
     *            pom); the {@code regen-rules-corpus} workflow does not, and gets the same default.
     * @throws IOException
     *             if the source corpus cannot be read or the output cannot be written
     */
    public static void main(String[] args) throws IOException
    {
        Path outputDir = args.length > 0 && !args[0].isBlank() ? Path.of(args[0])
                : CorpusPaths.rules();

        RuleStorageAssembler assembler = new RuleStorageAssembler();
        // R9 — the ADaMIG->SDTMIG pairing (and every package's primary product) is DATA read here
        // at packaging time, not a table compiled into the engine.
        PackageStandardsMap standardsMap = PackageStandardsMap.load(Path.of(""));

        Map<String, String> nativeShaped = new LinkedHashMap<>();
        List<RulePackageManifest.Entry> entries = new ArrayList<>();

        for (Triple t : assembler.discoverPackages())
        {
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            ObjectNode canonical = assembler.assemblePackage(t.family(), t.standard(), t.version());
            int ruleCount = canonical.get("rules").size();
            // Read from the STRICT map: the shipped regeneration requires the file, whereas the
            // assembler's own copy is tolerant so synthetic fixture directories still work. Both
            // read the same file, so the package body and the manifest cannot disagree.
            List<net.cumba.corej.core.model.StandardRef> declared = standardsMap
                    .forPackage(t.standard(), t.version());

            nativeShaped.put(file, RuleStorageAssembler.serialize(canonical));

            entries.add(new RulePackageManifest.Entry(file, t.family(), t.standard(), t.version(),
                    ruleCount, declared));
            System.out.printf(Locale.ROOT, "[assemble] %-34s %d rules%n", file, ruleCount);
        }

        entries.sort(Comparator.comparing(RulePackageManifest.Entry::file));
        RulePackageManifest manifest = new RulePackageManifest("rules-src", entries);
        System.out.printf(Locale.ROOT, "[assemble] %d packages%n", entries.size());

        ConversionReport report = RuleExprGenerator.generate(nativeShaped, manifest, outputDir);
        System.out.printf(Locale.ROOT, "[gen] %s%n", report);
    }

}
