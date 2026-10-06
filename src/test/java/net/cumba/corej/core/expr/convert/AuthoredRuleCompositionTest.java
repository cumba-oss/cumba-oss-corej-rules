package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.authoring.AssembledRule;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.PackageStandardsMap;
import net.cumba.corej.authoring.RuleFamily;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/**
 * The editor's composition, proven against the gate's corpus ({@code PLAN-rule-authoring-api} phase
 * 1 gate (b), pre-go review M1): for <b>every</b> authored rule and <b>every</b> package it appears
 * in, {@link AuthoredRuleAssembler#assemble} over the rule's own two files and the package's
 * declared standards yields exactly the node the generated {@code target/rules/<package>.json}
 * holds under {@code rules.<id>}, and the rule it loads carries the same {@code loadError} as the
 * gate's loaded rule.
 *
 * <p>
 * Why this is not redundant with the byte-identical regeneration: that gate runs the corpus loop
 * ({@code RuleStorageAssembler}); the editor never does. It calls {@code assemble} on one rule with
 * the files and the standards it resolved itself, and this test is what proves that single-rule
 * path composes the same bytes — the "one implementation, two consumers" claim measured on all
 * consumers.
 * </p>
 */
class AuthoredRuleCompositionTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A floor so a walk that matches nothing cannot pass vacuously. */
    private static final int MIN_RULES = 3_000;

    @Test
    void everyRuleComposesToItsPackagedNodeInEveryPackage() throws IOException
    {
        Path srcDir = CorpusPaths.moduleDir().resolve("rules-src");
        Map<String, Path> checks = files(srcDir.resolve("checks"));
        Map<String, Path> docs = files(srcDir.resolve("docs"));
        PackageStandardsMap standards = PackageStandardsMap.load(CorpusPaths.moduleDir());
        RulePackageManifest manifest = RulePackageManifest.load(CorpusPaths.rules());

        int compared = 0;
        List<String> mismatches = new ArrayList<>();
        for (RulePackageManifest.Entry e : manifest.packages())
        {
            JsonNode packaged = JSON.readTree(
                    Files.readString(CorpusPaths.rules(e.file()), StandardCharsets.UTF_8));
            Map<String, Rule> loaded = RulePackageLoader.load(CorpusPaths.rules(e.file()))
                    .getRules();
            List<StandardRef> declared = standards.forPackage(e.standard(), e.version());
            for (Map.Entry<String, JsonNode> entry : packaged.get("rules").properties())
            {
                String id = entry.getKey();
                Path check = checks.get(id);
                assertNotNull(check, id + " is packaged in " + e.file() + " but has no check file");
                assertEquals(e.family(), RuleFamily.of(id), "family of " + id);
                Path doc = docs.get(id);
                AssembledRule r = AuthoredRuleAssembler.assemble(
                        Files.readString(check, StandardCharsets.UTF_8),
                        doc == null ? null : Files.readString(doc, StandardCharsets.UTF_8),
                        declared);
                if (!entry.getValue().equals(r.packagedNode()))
                {
                    mismatches.add(e.file() + " " + id + ": packaged node differs");
                }
                Rule gate = loaded.get(id);
                Rule editor = r.rule();
                if (gate == null || editor == null)
                {
                    mismatches.add(e.file() + " " + id + ": loaded on one side only (gate="
                            + (gate != null) + ", editor=" + (editor != null) + ")");
                }
                else if (!String.valueOf(gate.getLoadError())
                        .equals(String.valueOf(editor.getLoadError())))
                {
                    mismatches.add(e.file() + " " + id + ": loadError differs\n  gate:   "
                            + gate.getLoadError() + "\n  editor: " + editor.getLoadError());
                }
                compared++;
            }
        }
        assertTrue(compared >= MIN_RULES,
                "compared " + compared + " rule×package entries, floor " + MIN_RULES);
        assertEquals(List.of(), mismatches, mismatches.size() + " mismatch(es) of " + compared);
    }


    /**
     * Sensitivity of the comparison itself: a check text mutated in one field must NOT compose to
     * the packaged node. (A comparison that cannot go red proves nothing.)
     */
    @Test
    void aMutatedCheckDoesNotCompose() throws IOException
    {
        Path srcDir = CorpusPaths.moduleDir().resolve("rules-src");
        Path check = files(srcDir.resolve("checks")).get("CDISC-CG0006");
        assertNotNull(check);
        String text = Files.readString(check, StandardCharsets.UTF_8);
        String mutated = text.replace("Executability: \"Fully Executable\"",
                "Executability: \"Partially Executable - Possible Overreporting\"");
        assertFalse(mutated.equals(text), "the mutation must land");
        RulePackageManifest manifest = RulePackageManifest.load(CorpusPaths.rules());
        RulePackageManifest.Entry pkg = manifest
                .packages().stream().filter(e -> "CDISC".equals(e.family())
                        && "SDTMIG".equals(e.standard()) && "3.4".equals(e.version()))
                .findFirst().orElseThrow();
        JsonNode packaged = JSON
                .readTree(Files.readString(CorpusPaths.rules(pkg.file()), StandardCharsets.UTF_8))
                .get("rules").get("CDISC-CG0006");
        AssembledRule r = AuthoredRuleAssembler.assemble(mutated, null, List.of());
        assertFalse(packaged.equals(r.packagedNode()), "a mutated Executability must differ");
    }


    private static Map<String, Path> files(Path aDir) throws IOException
    {
        Map<String, Path> out = new HashMap<>();
        if (!Files.isDirectory(aDir))
        {
            return out;
        }
        try (Stream<Path> walk = Files.walk(aDir))
        {
            for (Path p : walk.filter(p -> p.toString().endsWith(".yaml")).toList())
            {
                String name = p.getFileName().toString();
                out.put(name.substring(0, name.length() - ".yaml".length()), p);
            }
        }
        return out;
    }
}
