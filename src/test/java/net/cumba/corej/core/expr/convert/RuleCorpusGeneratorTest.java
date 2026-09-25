package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The emission contract of {@link RuleCorpusGenerator}: {@code rules-src/} feeds {@code rules/},
 * with the {@code Executability} park filter applied upstream of the generator.
 *
 * <p>
 * The generator's {@code main} writes to the module's real {@code rules/}, so this test replays its
 * body over a synthetic {@code rules-src}-shaped corpus in a {@code @TempDir} (the
 * {@code RuleStorageAssembler(Path)} constructor exists for exactly that) and emits into a
 * temporary directory. The <em>committed</em> corpus is the concern of
 * {@link NativeCorpusRoundTripTest}.
 * </p>
 *
 * <p>
 * ⚑ <b>Wave 33 removed this class's original load-bearing test.</b> It used to pin an asymmetry —
 * {@code rules/} keeping an authored {@code Scope.Domains} glob while the Python-facing
 * {@code rules-legacy/} got the degraded literal base name — and that asymmetry was produced by a
 * {@code deepCopy()} whose removal made the test red (verified 2026-08-06). With the legacy branch
 * gone there is no second writer, no in-place degradation and therefore no asymmetry to guard: the
 * property is now structural rather than pinned. Nothing was silently dropped — the guard's subject
 * was.
 * </p>
 */
class RuleCorpusGeneratorTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String GLOBBED_ID = "CDISC-ZZ0001";

    private static final String PARKED_ID = "CDISC-ZZ0002";

    /** What {@link RuleCorpusGenerator#main} produced, so the assertions can look at both sides. */
    private record Emitted(Path nativeDir, RulePackageManifest manifest)
    {
    }

    /**
     * Replays {@link RuleCorpusGenerator#main}'s body against an arbitrary source and target.
     *
     * <p>
     * ⚑ <b>Single-branch since wave 33.</b> This helper used to emit twice — a
     * {@code canonical.deepCopy()} through {@code applyLegacyDegradations} into a Python-facing
     * {@code rules-legacy} directory, then the untouched canonical node into {@code rules/} — and
     * the class existed largely to pin that the copy, and the emission order, kept the degraded
     * literals out of the native branch. With the legacy branch deleted there is no second writer
     * and no in-place rewrite, so the hazard is structurally absent rather than guarded, and the
     * asymmetry test that pinned it has gone with it.
     * </p>
     */
    private static Emitted emit(Path srcDir, Path nativeDir) throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler(srcDir);

        Map<String, String> nativeShaped = new LinkedHashMap<>();
        List<RulePackageManifest.Entry> entries = new ArrayList<>();
        for (Triple t : assembler.discoverPackages())
        {
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            ObjectNode canonical = assembler.assemblePackage(t.family(), t.standard(), t.version());
            nativeShaped.put(file, RuleStorageAssembler.serialize(canonical));
            entries.add(new RulePackageManifest.Entry(file, t.family(), t.standard(), t.version(),
                    canonical.get("rules").size(), List.of()));
        }
        RulePackageManifest manifest = new RulePackageManifest("rules-src", entries);
        RuleExprGenerator.generate(nativeShaped, manifest, nativeDir);
        return new Emitted(nativeDir, manifest);
    }


    /**
     * A minimal but complete {@code rules-src/checks} rule. {@code domainEntry} goes into
     * {@code Scope.Domains.Include} verbatim, so a caller can author a glob or a literal.
     */
    private static void writeCheck(Path checksDir, String id, String domainEntry,
            String executability)
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
                      - Name: "ADaMIG"
                        Version: "1.3"
                Scope:
                  Domains:
                    Include:
                      - %s
                Check:
                  all:
                    - name: AVAL
                      operator: empty
                Outcome:
                  Message: "AVAL must not be empty"
                  Output_Variables:
                    - AVAL
                """.formatted(id, executability, domainEntry), UTF_8);
    }


    private static Path corpus(Path root) throws IOException
    {
        Path checks = Files.createDirectories(root.resolve("checks"));
        Files.createDirectories(root.resolve("docs"));
        writeCheck(checks, GLOBBED_ID, "ADTTE*", "Fully Executable");
        writeCheck(checks, PARKED_ID, "ADSL", "Not Executable");
        return root;
    }


    @Test
    void theCorpusIsWrittenAndCarriesTheManifest(@TempDir Path aRoot) throws IOException
    {
        Emitted out = emit(corpus(aRoot.resolve("src")), aRoot.resolve("native"));
        String file = out.manifest().packages().getFirst().file();

        assertTrue(Files.exists(out.nativeDir().resolve(file)), "rules/" + file);
        assertTrue(Files.exists(out.nativeDir().resolve(RulePackageManifest.FILE_NAME)),
                "rules/" + RulePackageManifest.FILE_NAME);
    }


    /**
     * The {@code Executability: "Not Executable"} park filter, in the one place it lives —
     * {@link RuleStorageAssembler}'s loaded checks map, upstream of the generator.
     *
     * <p>
     * ⚠ Renamed from {@code aParkedRuleIsAbsentFromBothCorpora} in wave 33: there is one corpus
     * now. {@code NotExecutableCorpusSkipTest} links this method by name.
     * </p>
     */
    @Test
    void aParkedRuleIsAbsentFromTheCorpus(@TempDir Path aRoot) throws IOException
    {
        Emitted out = emit(corpus(aRoot.resolve("src")), aRoot.resolve("native"));
        Path pkg = out.nativeDir().resolve(out.manifest().packages().getFirst().file());

        JsonNode rules = JSON.readTree(Files.readString(pkg, UTF_8)).path("rules");
        // The control first: if this is absent the fixture is broken, not the skip.
        assertTrue(rules.has(GLOBBED_ID), () -> "the executable control must survive in " + pkg);
        assertFalse(rules.has(PARKED_ID),
                () -> "Executability: \"Not Executable\" must park the rule in " + pkg);
    }

}
