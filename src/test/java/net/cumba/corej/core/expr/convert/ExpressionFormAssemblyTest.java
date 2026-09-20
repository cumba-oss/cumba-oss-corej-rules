package net.cumba.corej.core.expr.convert;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The §5-prep proof of {@code plans/done/PLAN-rules-src-expression-flip.md}: <b>the assembly
 * pipeline accepts an expression-form {@code Check} end-to-end today</b> — the property the flip
 * (§6) stands on, proven rather than read off the code.
 *
 * <p>
 * A synthetic corpus of two expression-form rules runs through the real pipeline:
 * {@link RuleStorageAssembler} (load, the {@code Sensitivity} derive-and-validate gate via
 * {@link RuleClassifier}, Authorities join) into {@link RuleExprGenerator#generate} (carry-through
 * and package emission). Two claims:
 * </p>
 *
 * <ol>
 * <li><b>the schema/pipeline accepts {@code {expression}}</b> — the rules load, bind and assemble
 * without special-casing;</li>
 * <li><b>the carry-through keeps the authored text byte-equal</b>.</li>
 * </ol>
 *
 * <p>
 * The fixtures deliberately author no {@code Sensitivity}, so {@code RuleStorageAssembler.derive}
 * must run — an expression-form rule that broke derivation would abort assembly here, exactly as it
 * would abort a regen.
 * </p>
 *
 * <p>
 * ⚑ Until D121 (phase 7 of {@code PLAN-typed-expression-engine.md}) the corpus paired each
 * expression rule with a byte-identical <em>operator-leaf</em> twin and additionally pinned the
 * raise ({@code CheckToExpr}) and the classifier's representation-independence across the two
 * forms. The leaf form is unauthorable now — a leaf Check is an incorrect rule that fails loudly at
 * load — so those twin arms retired with their subject.
 * </p>
 */
class ExpressionFormAssemblyTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String SECOND_ID = "CDISC-ZZ0101";

    private static final String EXPR_ID = "CDISC-ZZ0102";

    /** The canonical expression, pinned literally so the fixture is self-documenting. */
    private static final String CANONICAL = "not empty(AETERM) and AESEV == \"MILD\"";

    /** The shared rule body; only the id and the {@code Check} block vary between the twins. */
    private static void writeCheck(Path aChecksDir, String aId, String aCheckBlock)
        throws IOException
    {
        Files.writeString(aChecksDir.resolve(aId + ".yaml"), """
                Core:
                  Id: %s
                  Status: Published
                  Version: "1"
                Executability: "Fully Executable"
                Description: "AETERM is populated and AESEV is MILD."
                Standards:
                  - Organization: "CDISC"
                    Standards:
                      - Name: "SDTMIG"
                        Version: "3.4"
                Scope:
                  Domains:
                    Include:
                      - AE
                %s
                Outcome:
                  Message: "AETERM must be populated with AESEV MILD"
                  Output_Variables:
                    - AETERM
                    - AESEV
                """.formatted(aId, aCheckBlock), UTF_8);
    }

    private static final String SECOND_CHECK = """
            Check:
              expression: 'var_exists("AESEV") and empty(AETERM)'""";

    private static final String EXPR_CHECK = """
            Check:
              expression: 'not empty(AETERM) and AESEV == "MILD"'""";

    private static Path corpus(Path aRoot) throws IOException
    {
        Path checks = Files.createDirectories(aRoot.resolve("checks"));
        Files.createDirectories(aRoot.resolve("docs"));
        writeCheck(checks, SECOND_ID, SECOND_CHECK);
        writeCheck(checks, EXPR_ID, EXPR_CHECK);
        return aRoot;
    }


    /** Replays {@link RuleCorpusGenerator#main}'s body against a synthetic source and target. */
    private static Path emit(Path aSrcDir, Path aNativeDir) throws IOException
    {
        RuleStorageAssembler assembler = new RuleStorageAssembler(aSrcDir);
        Map<String, String> nativeShaped = new LinkedHashMap<>();
        List<RulePackageManifest.Entry> entries = new ArrayList<>();
        for (Triple t : assembler.discoverPackages())
        {
            String file = RuleStorageAssembler.packageFileName(t.family(), t.standard(),
                    t.version());
            ObjectNode canonical = assembler.assemblePackage(t.family(), t.standard(), t.version());
            nativeShaped.put(file, RuleStorageAssembler.serialize(canonical));
            entries.add(new RulePackageManifest.Entry(file, t.family(), t.standard(), t.version(),
                    canonical.get("rules").size()));
        }
        assertEquals(1, entries.size(), "both rules name one (family, standard, version)");
        RulePackageManifest manifest = new RulePackageManifest("rules-src", entries);
        RuleExprGenerator.generate(nativeShaped, manifest, aNativeDir);
        return aNativeDir.resolve(entries.getFirst().file());
    }


    /** The derivation gate runs on the expression form: the rule derives a Sensitivity. */
    @Test
    void theClassifierDerivesFromTheExpressionForm(@TempDir Path aRoot) throws IOException
    {
        Path src = corpus(aRoot);
        Rule expr = LENIENT.treeToValue(
                YAML.readTree(
                        Files.readString(src.resolve("checks").resolve(EXPR_ID + ".yaml"), UTF_8)),
                Rule.class);

        net.cumba.corej.core.RulePackageLoader.normalizeOperations(expr);
        RuleClassifier.Derived<Sensitivity> exprSens = RuleClassifier.deriveSensitivity(expr);
        assertNotNull(exprSens.value(), "the expression rule must derive: " + exprSens.rationale());
    }


    /** Claims 1 + 2: both rules assemble, and the authored text is carried byte-equal. */
    @Test
    void theRulesAssembleWithTheAuthoredExpressionCarriedVerbatim(@TempDir Path aRoot)
        throws IOException
    {
        Path pkg = emit(corpus(aRoot.resolve("src")), aRoot.resolve("native"));
        String written = Files.readString(pkg, UTF_8);

        JsonNode rules = new ObjectMapper().readTree(written).path("rules");
        assertTrue(rules.has(SECOND_ID) && rules.has(EXPR_ID),
                "both rules must assemble: " + rules);
        assertEquals(CANONICAL, rules.get(EXPR_ID).get("Check").get("expression").asText(),
                "the expression Check must be kept verbatim");
        assertEquals("var_exists(\"AESEV\") and empty(AETERM)",
                rules.get(SECOND_ID).get("Check").get("expression").asText(),
                "the second rule's Check must be kept verbatim too");
    }

}
