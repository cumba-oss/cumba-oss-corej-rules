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
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.convert.RuleStorageAssembler.Triple;
import net.cumba.corej.core.model.CheckCondition;
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
 * A synthetic two-rule corpus in which the rules are byte-identical except for their id and the
 * <em>representation</em> of the same Check — one authored in the legacy leaf form, its twin
 * already as {@code {expression: ...}} — runs through the real pipeline:
 * {@link RuleStorageAssembler} (load, the {@code Sensitivity} derive-and-validate gate via
 * {@link RuleClassifier}, Authorities join) into {@link RuleExprGenerator#generate} (carry-through
 * and package emission). Three claims:
 * </p>
 *
 * <ol>
 * <li><b>the schema/pipeline accepts {@code {expression}}</b> — the twin loads, binds and assembles
 * without special-casing;</li>
 * <li><b>the carry-through keeps the authored text byte-equal</b> — and, since
 * {@code PLAN-retire-corpus-transforms.md} phase 1, the legacy leaf twin is <em>no longer
 * raised</em>: it assembles with its leaf form carried verbatim (the raise itself is still pinned
 * against {@code CheckToExpr}, the live loader raise, in
 * {@link #theLeafTwinRaisesToExactlyTheAuthoredExpression(Path)});</li>
 * <li><b>{@link RuleClassifier} is representation-independent</b> — both twins derive the same
 * {@code Sensitivity} (asserted directly as well, since the assembled package carries the field
 * only where authored and would hide a divergence).</li>
 * </ol>
 *
 * <p>
 * The fixtures deliberately author no {@code Sensitivity}, so {@code RuleStorageAssembler.derive}
 * must run on both representations — an expression-form rule that broke derivation would abort
 * assembly here, exactly as it would abort a regen.
 * </p>
 */
class ExpressionFormAssemblyTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String LEAF_ID = "CDISC-ZZ0101";

    private static final String EXPR_ID = "CDISC-ZZ0102";

    /**
     * The canonical expression both twins must converge on. Pinned literally so the fixture is
     * self-documenting; {@link #theLeafTwinRaisesToExactlyTheAuthoredExpression(Path)} proves it is
     * what the leaf form raises to, so the expression twin authors the true canonical text.
     */
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

    private static final String LEAF_CHECK = """
            Check:
              all:
                - name: AETERM
                  operator: non_empty
                - name: AESEV
                  operator: equal_to
                  value: "MILD"
                  value_is_literal: true""";

    private static final String EXPR_CHECK = """
            Check:
              expression: 'not empty(AETERM) and AESEV == "MILD"'""";

    private static Path corpus(Path aRoot) throws IOException
    {
        Path checks = Files.createDirectories(aRoot.resolve("checks"));
        Files.createDirectories(aRoot.resolve("docs"));
        writeCheck(checks, LEAF_ID, LEAF_CHECK);
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
        assertEquals(1, entries.size(), "both twins name one (family, standard, version)");
        RulePackageManifest manifest = new RulePackageManifest("rules-src", entries);
        RuleExprGenerator.generate(nativeShaped, manifest, aNativeDir);
        return aNativeDir.resolve(entries.getFirst().file());
    }


    /**
     * The fixture pin: the leaf twin's Check, raised through {@code CheckToExpr} (the live loader
     * raise — {@code RulePackageLoader.tryRaiseToExpr} runs the same one on every loaded rule),
     * prints exactly the text the expression twin authors — so the expression twin authors the true
     * canonical form, not an arbitrary spelling.
     */
    @Test
    void theLeafTwinRaisesToExactlyTheAuthoredExpression(@TempDir Path aRoot) throws IOException
    {
        Path src = corpus(aRoot);
        JsonNode rule = YAML.readTree(
                Files.readString(src.resolve("checks").resolve(LEAF_ID + ".yaml"), UTF_8));
        CheckCondition check = YAML.treeToValue(rule.get("Check"), CheckCondition.class);
        assertNotNull(check);
        assertEquals(CANONICAL, ExpressionPrinter.print(CheckToExpr.toExpr(check)));
    }


    /** Claim 3, asserted directly: derivation is representation-independent. */
    @Test
    void theClassifierDerivesIdenticallyFromBothRepresentations(@TempDir Path aRoot)
        throws IOException
    {
        Path src = corpus(aRoot);
        Rule leaf = LENIENT.treeToValue(
                YAML.readTree(
                        Files.readString(src.resolve("checks").resolve(LEAF_ID + ".yaml"), UTF_8)),
                Rule.class);
        Rule expr = LENIENT.treeToValue(
                YAML.readTree(
                        Files.readString(src.resolve("checks").resolve(EXPR_ID + ".yaml"), UTF_8)),
                Rule.class);

        RuleClassifier.Derived<Sensitivity> leafSens = RuleClassifier.deriveSensitivity(leaf);
        RuleClassifier.Derived<Sensitivity> exprSens = RuleClassifier.deriveSensitivity(expr);
        assertNotNull(leafSens.value(), "the leaf twin must derive: " + leafSens.rationale());
        assertEquals(leafSens.value(), exprSens.value(),
                "Sensitivity derivation diverged between representations: leaf="
                        + leafSens.rationale() + " expr=" + exprSens.rationale());
    }


    /**
     * Claims 1 + 2: the expression twin's authored text is carried byte-equal, and the legacy leaf
     * twin — no longer raised since the conversion machinery retired — assembles with its leaf form
     * carried verbatim. (Before {@code PLAN-retire-corpus-transforms.md} phase 1 the two
     * representations assembled byte-identically because the pipeline raised the leaf twin; that
     * transform is the thing this plan removed, so the old byte-identity claim retired with it.)
     */
    @Test
    void theTwinsAssembleWithTheAuthoredRepresentationCarriedVerbatim(@TempDir Path aRoot)
        throws IOException
    {
        Path pkg = emit(corpus(aRoot.resolve("src")), aRoot.resolve("native"));
        String written = Files.readString(pkg, UTF_8);

        JsonNode rules = new ObjectMapper().readTree(written).path("rules");
        assertTrue(rules.has(LEAF_ID) && rules.has(EXPR_ID), "both twins must assemble: " + rules);
        assertEquals(CANONICAL, rules.get(EXPR_ID).get("Check").get("expression").asText(),
                "the expression twin's Check must be kept verbatim");
        assertTrue(rules.get(LEAF_ID).get("Check").has("all"),
                "the leaf twin's Check is carried verbatim — the raise retired with phase 1: "
                        + rules.get(LEAF_ID).get("Check"));
    }

}
