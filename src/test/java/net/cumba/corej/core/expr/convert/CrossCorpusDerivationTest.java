package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The cross-corpus derivation gate of {@code plans/done/PLAN-classifier-redesign.md} (&sect;6) —
 * the committed form of the &sect;0 {@code P0Remeasure} probe, whose absence is why the grounding
 * defect survived: Gate B ({@link DerivedFieldEquivalenceTest}) derives from {@code rules-src},
 * where the {@code Operations} declarations are still present, while the corpus the Java engine
 * actually ships ({@code rules/}) is the assembled node serialised as-is (the retired
 * {@code OperationInliner} stage used to fold single-use operations into the Check and delete the
 * declaration, making the two views differ). One classifier, two materially different inputs — so
 * the agreement must be asserted on <em>every</em> form, not assumed from one.
 *
 * <p>
 * The two-forms test pins the representative shapes from the plan's &sect;0 re-measure
 * ({@code record_count(filter=…) == 0}, an inlined zero-arg call beside a declared operation,
 * {@code variable_count(--LNKGRP)} under {@code var_exists}): the same rule as a {@code rules-src}
 * operator-leaf Check and as a {@code rules/} inlined expression must derive the same
 * {@code Sensitivity} and infer the same evaluation domain. That is a <em>form-invariance</em>
 * property — one classifier, two materially different representations of one rule — and it needs no
 * external baseline to be meaningful. ({@code Rule_Type}, which this gate once covered, is no
 * longer a rule field — {@code PLAN-leaf-scope-domain-inference.md} phase 7.)
 * </p>
 *
 * <p>
 * <b>⚠⚠ What this gate deliberately no longer does, and why.</b> Until the {@code rules-legacy}
 * corpus was cut (wave 33) the full-corpus scan compared the derivation against the 14 067
 * {@code Rule_Type} / {@code Sensitivity} values baked into {@code rules-legacy}, and both this
 * test and {@link DerivedFieldEquivalenceTest} called those values <em>"the authored truth"</em>.
 * <b>That label was wrong.</b> Nothing authors them: {@code rules-src} carries {@code Rule_Type} on
 * <b>0</b> of 3 734 rules and {@code Sensitivity} on <b>8</b> (the documented overrides), so every
 * one of the 14 067 legacy values had itself been produced by this same derivation and written down
 * — for the Python fork, which implements neither field. The comparison was therefore <em>"the
 * derivation reproduces what the derivation produced last time"</em>: a snapshot regression test
 * that pins today's behaviour <b>including any defect in it</b>, and that must be regenerated
 * whenever the derivation legitimately improves. With the fork gone, the party it existed for is
 * gone too.
 * </p>
 *
 * <p>
 * It is replaced by three properties that are true independently of any stored output:
 * <b>form-invariance</b> (below), <b>completeness</b> — the derivation abstains on no shipped rule
 * ({@link #derivationCompletesEveryShippedRuleAndTheCorpusLoadsClean}) — and <b>routing</b>
 * ({@link #inferredDomainRoutesAsIntended}), which is where a derivation regression actually does
 * damage now that the fields appear in no output at all.
 * </p>
 */
class CrossCorpusDerivationTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Path NATIVE_DIR = CorpusPaths.rules();

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    /**
     * The documented {@code Sensitivity} override set — the same rules pinned by identity in
     * {@link DerivedFieldEquivalenceTest}. The real stripper keeps their authored
     * {@code Sensitivity}, so the in-memory strip must too (&sect;6): stripping them would
     * manufacture diffs the shipped pipeline can never produce.
     *
     * <p>
     * <b>13 &rarr; 8 on 2026-07-31.</b> {@code CDISC-CG0273/0275/0276/0277/0278} left the set when
     * {@code plans/done/PLAN-split-mixed-granularity-rules.md} split them: each disjoined a
     * dataset-level and a record-level assertion, so <em>neither</em> {@code Sensitivity} was right
     * and the authored value was a structural override rather than a semantic one. After the split
     * the record half (bare id) derives {@code Record} and the new {@code CDISC-CG027xA} absence
     * half derives {@code Dataset}, both with no override. The remaining 8 are held for semantic
     * reasons and stay.
     * </p>
     */
    private static final List<String> SENSITIVITY_OVERRIDES = List.of("CDISC-CG0001",
            "CDISC-CG0077", "CDISC-CG0535", "CDISC-SEND-0153", "CDISC-SEND-0249.1",
            // 2026-10-07 (PLAN-message-folded-corpus-alignment): the 42 R-4.11c absent-column
            // sisters left this list with the sisters (their parents report the absence under
            // Outcome.Message_Folded). CDISC-SEND-9438 is the ONE sister kept — its parent
            // CDISC-SEND-0397 gates FXRESCAT on purpose (the guard-column hazard of a wide P3),
            // so the dataset-level absence is still the sister's, with its authored Sensitivity.
            "CDISC-SEND-9438",
            // 2026-10-12 (rule round 2026-10-12, batch C item 2): CDISC-SEND-9445, the guarded
            // sister of the --EXCLFL gate on CDISC-SEND-0149 / FDA-SE2354. The parents' leaf
            // --EXCLFL != "Y" is no Message_Folded trigger (R-4.11b (e)), so the absent column is
            // the sister's one dataset-level fact; its trigger not empty(--REASEX) is per record,
            // so the derivation would read Record. Authored Dataset is correct.
            "CDISC-SEND-9445",
            // 2026-10-12 (rule round 2026-10-12, batch D item 2): FDA-SE9704, the FDA arm of
            // CDISC-SEND-9445 (the guarded sister of the --EXCLFL gate on FDA-SE2354). Same shape,
            // same reason: authored Dataset is correct, the derivation would read Record.
            "FDA-SE9704",
            // 2026-10-12 (rule round 2026-10-12, batch D item 4a): the nine study-day presence
            // companions. The finding is that the date COLUMN is absent, one dataset-level fact;
            // the trigger not empty(--DY) (or a --CH*DY / --X*DY) is per record, so the
            // derivation would read Record. Authored Dataset is correct.
            "FDA-SE9705", "FDA-SE9706", "FDA-SE9707", "FDA-SE9708", "FDA-SE9709", "FDA-SE9710",
            "PMDA-SD9752", "PMDA-SD9753", "PMDA-SD9754",
            // ⛔ 2026-09-19 (PLAN-retire-core-family phase 6): the five CORE entries went with the
            // family — CORE-000212 / -000365 / -000929 / -009705 / -009707. Kept in step with
            // DerivedFieldEquivalenceTest, the authored-corpus half, where the same five went and
            // the reasoning is recorded. No override is lost: each was the CORE arm of a set
            // whose other arms are all still listed.
            // 8 -> 12 on 2026-08-19 (wave TA-6, review H2): the SV-scoped conditional TV
            // presence mints author `Dataset` because the derivation's `Study` would trip the
            // loader's unrestricted-scope gate; see DerivedFieldEquivalenceTest.
            "CDISC-CG9671", "FDA-SD9713", "PMDA-SD9713"
    // 12 -> 15 on 2026-09-07 (R-4.11b, the --STRESU presence sisters): the finding is that
    // the --STRESU COLUMN is absent — one dataset-level fact — while the existential
    // witness is a per-record leaf, so the derivation reads `Record` and the per-record
    // reading would multiply the finding by the number of numeric results. Kept in step
    // with DerivedFieldEquivalenceTest, which holds the rules-src half of the same list.
    // 15 -> 19 on 2026-09-11 (R-4.11c wave A, the M1/M13 presence sisters): the finding is
    // that the --BODSYS / --TOX COLUMN is absent — one dataset-level fact — while the
    // existential witness is a per-record leaf, so the derivation reads `Record`. The M19
    // pair FDA/PMDA-SD9718 is deliberately absent: all-var_exists Checks derive `Dataset`
    // on their own. Kept in step with DerivedFieldEquivalenceTest, which holds the
    // rules-src half of the same list.
    // 19 -> 25 on 2026-09-11 (R-4.11c wave B, the M3/M11/M11b/M8 presence sisters): the
    // finding is that the end- or start-timepoint COLUMN SET is absent — one
    // dataset-level fact — while every trigger leaf is per-row, so the derivation reads
    // `Record`. Kept in step with DerivedFieldEquivalenceTest, which holds the
    // rules-src half of the same list.
    // 25 -> 31 on 2026-09-11 (R-4.11c wave C1, the M9/M10 presence sisters): the finding
    // is that the ECDOSTXT (M9) or SEUPDES (M10) COLUMN is absent — one dataset-level
    // fact — while every trigger leaf is per-row, so the derivation reads `Record`.
    // Kept in step with DerivedFieldEquivalenceTest, which holds the rules-src half of
    // the same list.
    // 31 -> 36 on 2026-09-11 (R-4.11c wave C2, the M4/M7/M7b/M14/M18 presence sisters):
    // the finding is that the AEENDTC/AEDUR (M14), --STINT/--ENINT (M4), --REASND
    // (M7/M7b) or FXSTRESC/FXSTAT (M18) COLUMN SET is absent — one dataset-level fact —
    // while every trigger leaf is per-row, so the derivation reads `Record`. Kept in
    // step with DerivedFieldEquivalenceTest, which holds the rules-src half of the same
    // list.
    // 36 -> 40 on 2026-09-11 (R-4.11c wave C3, the M16/M17 presence sisters): the finding
    // is that the RDOMAIN (M16) or DSSCAT (M17) COLUMN is absent — one dataset-level
    // fact — while every trigger leaf is per-row, so the derivation reads `Record`.
    // DRAFT-900045 ships only into the draft packages, which this scan walks too. The M15
    // pair FDA/PMDA-SD9727 is deliberately absent: all-var_exists Checks derive `Dataset`
    // on their own. Kept in step with DerivedFieldEquivalenceTest, which holds the
    // rules-src half of the same list.
    // 40 -> 41 on 2026-09-11 (wave D, the M10 SENDIG carry-over): CDISC-SEND-9439,
    // sister of CDISC-SEND-0125.1 — the SEUPDES COLUMN absence is one dataset-level
    // fact while the ETCD == "UNPLAN" trigger leaf is per-row, so the derivation
    // reads `Record`. The four wave-D split siblings (FDA/PMDA-SD1017-A/-SD1018-A)
    // are record-level by design and deliberately absent. Kept in step with
    // DerivedFieldEquivalenceTest, which holds the rules-src half of the same list.
    // +1 on 2026-10-02 (rule round 2026-10-02, owner): CDISC-CG9685, the --BDSYCD presence
    // sister of CDISC-CG0082 - same rationale as the --STRESU set: one dataset-level fact
    // (the column is absent) witnessed by a per-record leaf (not empty(--BODSYS)), so the
    // derivation reads Record and would multiply one finding by the populated rows.
    // Kept in step with the twin roster in the other test class.
    // PMDA arm of CDISC-CG9685, same day and rationale.
    // ⭐ 2026-10-07 (PLAN-message-folded-corpus-alignment): the 43 absent-column sisters of
    // the R-4.11c waves above are RETIRED (their parents carry Message_Folded); the wave
    // notes stay as the record of why the entries existed.
    );

    /**
     * The plan's &sect;0 representative shapes, one per failure class: {@code FDA-SE2201}
     * ({@code record_count(filter=…) == 0} — the ~170-rule FDA/PMDA block), {@code CDISC-SEND-0016}
     * (an inlined zero-arg {@code domain_is_custom()} beside a still-declared operation) and
     * {@code CDISC-CG0022} ({@code variable_count(--LNKGRP)} under {@code var_exists}). All three
     * are expected to classify {@code Dataset}; the first and third infer the {@code {}} domain
     * (their sole leaf is a whole-column verdict), the second {@code {ROW}}.
     *
     * <p>
     * The property is <b>form-invariance</b>: {@code rules-src} carries the operator-leaf Check
     * with every operation still declared, while {@code rules/} carries the inlined native
     * expression. One classifier over two materially different inputs must reach the same answer.
     * The expected values are stated here, in the test, rather than read out of a corpus — so this
     * pins the classification itself, not a copy of its own last output.
     * </p>
     */
    @ParameterizedTest
    @CsvSource(
    {
            "FDA-SE2201, checks/FDA/FDA-SE2201.yaml, rules-fda-sendig-3-1-1.json, {}, Dataset",
            "CDISC-SEND-0016, checks/CDISC/CDISC-SEND-0016.yaml, rules-cdisc-sendig-dart-1-1.json,"
                    + " {ROW}, Dataset",
            "CDISC-CG0022, checks/CDISC/CDISC-CG0022.yaml, rules-cdisc-sdtmig-3-2.json, {},"
                    + " Dataset"
    })
    void theSameRuleDerivesIdenticallyInBothCorpusForms(String id, String srcFile, String pkgFile,
            String expectedDomain, String expectedSensitivity)
        throws IOException
    {
        // Stated here, not read out of a corpus: this pins the classification, not its own output.
        Sensitivity authoredSensitivity = Sensitivity.fromJson(expectedSensitivity);
        assertNotNull(authoredSensitivity,
                "unknown Sensitivity in the CsvSource: " + expectedSensitivity);
        // Form 1 — rules-src operator-leaf Check, operations declared.
        Rule src = YAML.readValue(
                Files.readString(CHECKS_DIR.resolveSibling(srcFile), StandardCharsets.UTF_8),
                Rule.class);
        // 7b: an external binder materialises the Bindings itself — without this the classifier
        // sees no operations at all and a `$`-consuming Check derives RECORD.
        RulePackageLoader.materialiseBindings(src);
        assertDerives(id + " [rules-src]", src, expectedDomain, authoredSensitivity);
        // Form 2 — rules/ native expression, single-use operations inlined into the Check. The
        // derived field is stripped in memory and the package is loaded through
        // RulePackageLoader, so the value on the loaded rule is exactly what the production
        // derivation supplies.
        Rule nativeRule = loadStripped(NATIVE_DIR.resolve(pkgFile), id).getRules().get(id);
        assertNotNull(nativeRule, id + " not found in native " + pkgFile);
        assertNotNull(nativeRule.getEvaluationDomain(), id + " [rules/] no inferred domain");
        assertEquals(expectedDomain, nativeRule.getEvaluationDomain().label(),
                id + " [rules/] loader-inferred domain");
        assertEquals(authoredSensitivity, nativeRule.getSensitivity(),
                id + " [rules/] loader-derived Sensitivity");
    }


    /**
     * &sect;6 acceptance, in its post-{@code rules-legacy} form: every entry of every shipped
     * native package loads clean and the derivation <b>completes</b> on it — a non-null
     * {@code Sensitivity} for all &gt;13 900 entries, and an inferred evaluation domain wherever a
     * native expression was retained, with no abstention.
     *
     * <p>
     * <b>Completeness is the property that replaces the old snapshot diff, and it is strictly
     * stronger in the one direction that matters.</b> The fields are now absent from every shipped
     * artefact, so a derivation that starts abstaining leaves <em>no trace anywhere</em> — the rule
     * simply routes differently (see {@link #inferredDomainRoutesAsIntended}). A count of 14 067
     * matching strings could never have caught an abstention that the strings themselves were
     * regenerated from; a non-null assertion over the live derivation does.
     * </p>
     *
     * <p>
     * This also keeps the load-health half of the original scan, which never depended on
     * {@code rules-legacy} at all: zero load errors, zero load warnings, and no loader-injected
     * precondition gates on the shipped corpus.
     * </p>
     */
    @Test
    void derivationCompletesEveryShippedRuleAndTheCorpusLoadsClean() throws IOException
    {
        List<Path> packages;
        try (Stream<Path> files = Files.list(NATIVE_DIR))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-"))
                    .filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        // 56 -> 45 (PLAN-retire-core-family phase 6): 58 -> 47 packages.
        assertTrue(packages.size() >= 45, "expected the full native corpus, got " + packages);

        List<String> diffs = new ArrayList<>();
        List<String> loadErrors = new ArrayList<>();
        int entries = 0;
        for (Path pkgFile : packages)
        {
            RulePackage pkg = loadStripped(pkgFile, null);
            for (Map.Entry<String, Rule> e : pkg.getRules().entrySet())
            {
                String id = e.getKey();
                Rule rule = e.getValue();
                entries++;
                if (rule.getLoadError() != null)
                {
                    loadErrors.add(pkgFile.getFileName() + " " + id + ": " + rule.getLoadError());
                    continue;
                }
                // ⚠ Fix #159 removed the dangling-operand exclusion that used to sit here. It
                // filtered out the `which no Operations entry defines` clause, on the grounds that
                // ShippedRulesDanglingOperandCorpusTest owned that warning for the two
                // Not Executable T3 rules. Both halves of that premise are gone: Fix #147 made
                // those rules executable, and Fix #159 made the dangling-operand finding an
                // unconditional loadError — so no rule can ever carry that text on the WARNING
                // channel again and the filter had become unreachable. This test now holds the
                // shipped corpus to zero load warnings, full stop.
                String otherWarnings = rule.getLoadWarning() == null ? "" : rule.getLoadWarning();
                if (!otherWarnings.isEmpty())
                {
                    // The frame gate is a WARNING at load (Q2, resolved 2026-07-30) so external
                    // rules keep running — but the shipped corpus is held to zero warnings here.
                    loadErrors
                            .add(pkgFile.getFileName() + " " + id + ": [warning] " + otherWarnings);
                }
                if (rule.getInjectedPreconditionGates() != null)
                {
                    // The loader injects availability gates only into a Check that INLINES a
                    // library/define/dictionary operation call. The shipped corpus inlines none —
                    // and carries zero Preconditions of any kind — so zero injections here is the
                    // expected result, and an injection means the generator started inlining an
                    // availability-dependent call without baking in OperationInliner's gate.
                    //
                    // ⚠ KDICT-F1: this assertion is NOT evidence that the shipped rules are gated.
                    // It used to be commented "the shipped corpus carries OperationInliner's gates
                    // already", which is false: the 98 dictionary rules keep the DECLARED ($-ref)
                    // form, which the injector by design leaves alone, and their no-dictionary SKIP
                    // comes from RuleRunner's eager dictionary arm (Fix #268), not from a
                    // Precondition. Reading a green here as "the SKIP contract holds" is exactly
                    // how the false PASS survived — ShippedDictionaryRuleSkipTest asks that
                    // question directly.
                    loadErrors.add(pkgFile.getFileName() + " " + id + ": [injected gate] "
                            + rule.getInjectedPreconditionGates());
                }
                String where = pkgFile.getFileName() + " " + id;
                if (rule.getCheckExpr() != null && rule.getEvaluationDomain() == null)
                {
                    diffs.add(where + " Domain: inference ABSTAINED");
                }
                if (rule.getSensitivity() == null)
                {
                    diffs.add(where + " Sensitivity: derivation ABSTAINED");
                }
            }
        }
        // 13900 -> 12700 (PLAN-retire-core-family phase 6): fan-out 14903 -> 12818;
        // 12700 -> 12600 (PLAN-message-folded-corpus-alignment, 42 R-4.11c sisters retired):
        // fan-out 12818 -> 12690.
        assertTrue(entries > 12600, "expected the whole shipped corpus, got " + entries);
        assertEquals(List.of(), loadErrors,
                "stripping the derived fields must not break loading, and the shipped corpus"
                        + " must carry zero load warnings");
        assertEquals(List.of(), diffs,
                "the derivation must complete on every shipped rule — an abstention leaves the"
                        + " field null and the engine then routes the rule as an untyped rule,"
                        + " silently and with nothing in any artefact to notice it in (found "
                        + diffs.size() + ")");
    }


    /**
     * ⭐ <b>The routing pin — the protection that actually matters now.</b>
     *
     * <p>
     * The inferred evaluation domain is not decoration: {@code RuleRunner} branches on it
     * ({@code {}} broadcast, {@code {VAR}} per-variable, {@code {ROW}} per-record,
     * {@code {VAR,ROW}} per-(variable,row) framing), as does
     * {@code ValidationReportBuilder.scopeOf}. Before wave 33 a derivation regression would at
     * least have shown up as a diff against the values baked into {@code rules-legacy}. Now the
     * routing key appears in <b>no output at all</b> (the routing census aside), so the only
     * observable consequence of getting one wrong is that the engine <em>runs the rule
     * differently</em> — no artefact changes, no corpus drifts, nothing goes red.
     * </p>
     *
     * <p>
     * So the inference is pinned here at the routing predicates {@code RuleRunner} actually
     * consults, over the whole shipped corpus, with a non-zero floor on each routing class. An
     * inference that collapses — every rule becoming {@code {ROW}}, say — moves these counts
     * immediately, where the old snapshot comparison would have been regenerated along with it.
     * </p>
     */
    @Test
    void inferredDomainRoutesAsIntended() throws IOException
    {
        List<Path> packages;
        try (Stream<Path> files = Files.list(NATIVE_DIR))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-"))
                    .filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }

        Map<String, Domain> byId = new java.util.TreeMap<>();
        for (Path pkgFile : packages)
        {
            for (Map.Entry<String, Rule> e : loadStripped(pkgFile, null).getRules().entrySet())
            {
                if (e.getValue().getLoadError() == null
                        && e.getValue().getEvaluationDomain() != null)
                {
                    byId.putIfAbsent(e.getKey(), e.getValue().getEvaluationDomain());
                }
            }
        }
        // 3500 -> 3100 (PLAN-retire-core-family phase 6). ⚠ This is the ONLY one of these four
        // counting DISTINCT shipped ids rather than fan-out entries, and its measured value is
        // 3132 — lower than the manifest's 3411, because it counts only the ids this derivation
        // routes.
        assertTrue(byId.size() > 3100, "expected the whole shipped corpus, got " + byId.size());

        long dataset = byId.values().stream().filter(Domain.DATASET::equals).count();
        long variable = byId.values().stream().filter(Domain.VARIABLE::equals).count();
        long row = byId.values().stream().filter(Domain.ROW::equals).count();
        long cell = byId.values().stream().filter(Domain.CELL::equals).count();
        // Each routing class must be non-empty. A collapsed inference empties one of these, and
        // the emptied class is exactly the set of rules the engine would then mis-route.
        assertTrue(dataset > 0, "no rule routes as {} — RuleRunner's broadcast branch is now"
                + " unreachable for the whole corpus");
        assertTrue(variable > 0, "no rule routes as {VAR} — RuleRunner's per-variable branch is"
                + " now unreachable for the whole corpus");
        assertTrue(row > 0, "no rule routes as {ROW} — RuleRunner's per-record framing is now"
                + " unreachable for the whole corpus");
        assertTrue(cell > 0, "no rule routes as {VAR,ROW} — RuleRunner's per-(variable,row)"
                + " branch is now unreachable for the whole corpus");
        // ... and none may swallow the corpus. Both directions are needed: an inference that
        // answers {ROW} for everything leaves every class above non-empty except by accident,
        // but drives this one over the line.
        assertTrue(row < byId.size(), "every shipped rule routes as {ROW} — the inference has"
                + " collapsed to a single answer (" + row + " of " + byId.size() + ")");
    }


    /**
     * Phase 3 of {@code PLAN-parity-java-lane-native-corpus}: the shipped {@code rules/} corpus is
     * stripped on disk — no entry carries {@code Rule_Type}, and {@code Sensitivity} survives only
     * on the {@link #SENSITIVITY_OVERRIDES}. This keeps the scan above meaningful: its in-memory
     * strip is a no-op on a stripped corpus, so the scan genuinely measures what the production
     * loader derives. A field creeping back into the generator's output would silently turn the
     * scan back into authored-vs-authored — exactly the blindness that let the original defect
     * ship.
     */
    @Test
    void shippedNativeCorpusCarriesNoDerivableField() throws IOException
    {
        List<Path> packages;
        try (Stream<Path> files = Files.list(NATIVE_DIR))
        {
            packages = files.filter(p -> p.getFileName().toString().startsWith("rules-"))
                    .filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<String> offenders = new ArrayList<>();
        int entries = 0;
        for (Path pkgFile : packages)
        {
            JsonNode rules = JSON.readTree(pkgFile.toFile()).get("rules");
            for (Map.Entry<String, JsonNode> e : rules.properties())
            {
                entries++;
                if (e.getValue().has("Rule_Type"))
                {
                    offenders.add(pkgFile.getFileName() + " " + e.getKey() + ": Rule_Type");
                }
                boolean override = SENSITIVITY_OVERRIDES.contains(e.getKey());
                if (e.getValue().has("Sensitivity") != override)
                {
                    offenders.add(pkgFile.getFileName() + " " + e.getKey() + ": Sensitivity "
                            + (override ? "missing (documented override must keep it)"
                                    : "present (must be derived, not shipped)"));
                }
            }
        }
        // 13900 -> 12700 (PLAN-retire-core-family phase 6): fan-out 14903 -> 12818;
        // 12700 -> 12600 (PLAN-message-folded-corpus-alignment, 42 R-4.11c sisters retired):
        // fan-out 12818 -> 12690.
        assertTrue(entries > 12600, "expected the whole shipped corpus, got " + entries);
        assertEquals(List.of(), offenders,
                "rules/ must ship stripped: Rule_Type never, Sensitivity only on the documented"
                        + " override set");
    }


    /**
     * Infers the domain and derives the Sensitivity directly from {@code rule} and asserts them
     * against the stated expectation.
     */
    private static void assertDerives(String label, Rule rule, String expectedDomain,
            Sensitivity authoredSensitivity)
    {
        assertNotNull(rule.getCheck(), label + " has no Check");
        Domain domain = DomainScan.infer(CheckExprUnits.toExpr(rule.getCheck()),
                BindingDomains.forRule(rule));
        assertEquals(expectedDomain, domain.label(), label + " inferred domain");
        RuleClassifier.Derived<Sensitivity> sensitivity = RuleClassifier.deriveSensitivity(rule);
        assertEquals(authoredSensitivity, sensitivity.value(),
                label + " Sensitivity (" + sensitivity.rationale() + ")");
    }


    /**
     * Loads a native package with {@code Sensitivity} stripped in memory — from one rule
     * ({@code onlyId} non-null) or from every rule. Mirrors the real stripper: the
     * {@link #SENSITIVITY_OVERRIDES} keep their authored {@code Sensitivity}.
     */
    private static RulePackage loadStripped(Path file, String onlyId) throws IOException
    {
        JsonNode root = JSON.readTree(file.toFile());
        ObjectNode rules = (ObjectNode) root.get("rules");
        for (Map.Entry<String, JsonNode> entry : rules.properties())
        {
            if (onlyId != null && !onlyId.equals(entry.getKey()))
            {
                continue;
            }
            ObjectNode rule = (ObjectNode) entry.getValue();
            if (!SENSITIVITY_OVERRIDES.contains(entry.getKey()))
            {
                rule.remove("Sensitivity");
            }
        }
        return RulePackageLoader.loadFromString(JSON.writeValueAsString(root));
    }

}
