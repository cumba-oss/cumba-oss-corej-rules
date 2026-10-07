package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.cumba.corej.core.CheckExprUnits;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>Gate B</b> of {@code PLAN-derive-rule-type-sensitivity} — that {@code Sensitivity} is
 * genuinely derivable, so the corpus need not carry it. ({@code Rule_Type}, the other field this
 * gate once covered, is no longer a rule field — {@code PLAN-leaf-scope-domain-inference.md} phase
 * 7 — and a rule carrying it fails to load.)
 *
 * <p>
 * <b>⚠⚠ Historical note — the corpus this test used to compare against was not a second source of
 * truth.</b> Until wave 33 the gate asserted, rule by rule, that the derivation reproduced the
 * {@code Rule_Type} / {@code Sensitivity} baked into {@code rules-legacy}, and described those
 * values as authored. <b>They were not.</b> {@code rules-src} carries {@code Rule_Type} on <b>0</b>
 * of 3 734 rules and {@code Sensitivity} on <b>8</b>; every legacy value had therefore been
 * produced by this very derivation and written down, for the Python fork, which implements neither
 * field. The gate was a snapshot regression test — it pinned today's derivation <em>including any
 * defect in it</em> — and it is gone with the corpus and the fork it served.
 * </p>
 *
 * <p>
 * What replaces it here is a test <b>against the specification</b>: the documented contract of
 * {@link RulePackageLoader#deriveOmittedFields(Rule)}, plus the documented override set.
 * Corpus-wide completeness and the routing consequences live in {@link CrossCorpusDerivationTest}.
 * </p>
 *
 * <p>
 * A small override set is exempt: rules where the derivation is documented to disagree and the
 * authored value wins ({@code rules-src} keeps the field for exactly these). They are listed
 * explicitly so the set cannot grow unnoticed.
 * </p>
 */
class DerivedFieldEquivalenceTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    /**
     * The documented {@code Sensitivity} override set (plan §5b): the derivation says
     * {@code Record}, the authored {@code Dataset} is correct, and {@code rules-src} keeps the
     * field. Every one is a rule whose Check fires all-or-none, or whose finding is a single
     * dataset-level fact that the per-record reading would multiply.
     *
     * <p>
     * <b>13 &rarr; 8 on 2026-07-31</b>, when
     * {@code plans/done/PLAN-split-mixed-granularity-rules.md} split
     * {@code CDISC-CG0273/0275/0276/0277/0278}. Those five were the only members held for a
     * <em>structural</em> reason — they disjoined a dataset-level and a record-level assertion, so
     * neither {@code Sensitivity} was correct. Both halves now derive their value with no override.
     * </p>
     */
    // ⛔ 2026-09-19 (PLAN-retire-core-family phase 6): FIVE CORE entries removed with the family
    // — "CORE-000212", "CORE-000365", "CORE-000929" from the original eight, "CORE-009705" from
    // the TA-6 four and "CORE-009707" from the wave-B six. ⭐ NO OVERRIDE IS LOST: every one was
    // the CORE arm of a set whose CDISC/FDA/PMDA arms are all still listed — CDISC-CG0535 /
    // CDISC-CG0077 / CDISC-CG0001 carry the first three's shapes, CDISC-CG9671 + FDA/PMDA-SD9713
    // the fourth's, and FDA-SD9720 + FDA-SD9721 the fifth's. The rationale comments are left
    // exactly as they were, minus the retired ids: they argue about a SHAPE, not about a family.
    private static final List<String> SENSITIVITY_OVERRIDES = List.of("CDISC-CG0001",
            "CDISC-CG0077", "CDISC-CG0535", "CDISC-SEND-0153", "CDISC-SEND-0249.1",
            // 8 -> 12 on 2026-08-19 (twin-alignment wave TA-6, review H2): the four minted
            // conditional TV presence rules are SCOPED to SV (that is the "visits are in use"
            // condition), so the derivation's certain `Study` verdict would trip the loader's
            // study-sensitivity-needs-unrestricted-scope gate — exactly the case the loader's own
            // message directs to "declare a dataset-level Sensitivity instead". The finding (TV
            // absent while SV is present) attaches to SV, so the authored `Dataset` is correct
            // and the derivation is documented-wrong for this shape.
            "CDISC-CG9671", "FDA-SD9713", "PMDA-SD9713",
            // 12 -> 15 on 2026-09-07 (R-4.11b, the --STRESU presence sisters): the finding is that
            // the --STRESU COLUMN is not in the dataset — one dataset-level fact. The derivation
            // reads `Record` because the existential witness is a per-record leaf
            // (`--STRESC =~ /numeric/`), and there is no dataset-level predicate for "some row is
            // numeric": `var_is_empty` only answers "the whole column is null". So the per-record
            // reading would multiply one column-absence finding by the number of numeric results,
            // which is the flood these rules exist to replace. Authored `Dataset` is correct and
            // the derivation is documented-wrong for this shape.
            "CDISC-CG9672", "FDA-SD9715", "PMDA-SD9715",
            // 15 -> 19 on 2026-09-11 (R-4.11c wave A, the M1/M13 presence sisters): same rationale
            // as the --STRESU set — the finding is that the --BODSYS / --TOX COLUMN is absent, one
            // dataset-level fact, while the existential witness is a per-record leaf
            // (`not empty(--BDSYCD)` / `--TOXGR =~ /[1-9]/`), so the derivation reads `Record` and
            // would multiply one column-absence finding by the number of qualifying rows — the
            // flood these rules replace. ⚠ The M19 pair FDA/PMDA-SD9718 is deliberately NOT here:
            // its Check is nothing but var_exists leaves, so the derivation itself returns
            // `Dataset` and an authored value would fail the disagree clause below. Kept in step
            // with CrossCorpusDerivationTest, which holds the shipped-corpus half of this list.
            "FDA-SD9716", "PMDA-SD9716", "FDA-SD9717", "PMDA-SD9717",
            // 19 -> 25 on 2026-09-11 (R-4.11c wave B, the M3/M11/M11b/M8 presence sisters): same
            // rationale again — the finding is that the end-timepoint set (--ENDTC/--ENRF/
            // --ENRTPT/--DUR, SD9719) or the start-timepoint set (--STDTC/--STRF/--STRTPT,
            // SD9720/SD9721) is ABSENT from the dataset, one dataset-level fact,
            // while every trigger leaf is per-row (--OCCUR != "N", empty(--STAT),
            // not empty(--ENDTC) …), so the derivation reads `Record` and would multiply one
            // column-absence finding by the number of qualifying rows — the flood these rules
            // replace. Kept in step with CrossCorpusDerivationTest, the shipped-corpus half.
            "FDA-SD9719", "PMDA-SD9719", "FDA-SD9720", "PMDA-SD9720", "FDA-SD9721",
            // 25 -> 31 on 2026-09-11 (R-4.11c wave C1, the M9/M10 presence sisters): same
            // rationale again — the finding is that the ECDOSTXT (M9) or SEUPDES (M10) COLUMN
            // is absent, one dataset-level fact, while every trigger leaf is per-row
            // (empty(ECDOSE), ECOCCUR != "N", ECSTAT != "NOT DONE" / empty(ECSTAT),
            // ETCD == "UNPLAN"), so the derivation reads `Record` and would multiply one
            // column-absence finding by the number of qualifying rows — the flood these rules
            // replace. Kept in step with CrossCorpusDerivationTest, the shipped-corpus half.
            "CDISC-CG9673", "FDA-SD9722", "PMDA-SD9722", "CDISC-CG9674", "FDA-SD9723",
            "PMDA-SD9723",
            // 31 -> 36 on 2026-09-11 (R-4.11c wave C2, the M4/M7/M7b/M14/M18 presence sisters):
            // same rationale again — the finding is that the AEENDTC/AEDUR (M14), --STINT/--ENINT
            // (M4), --REASND (M7/M7b) or FXSTRESC/FXSTAT (M18) COLUMN SET is absent, one
            // dataset-level fact, while every trigger leaf is per-row (AEOUT == "RECOVERED/
            // RESOLVED", --TEST =~ /.+ to .+/, --STAT == "NOT DONE", empty(FXRESCAT)), so the
            // derivation reads `Record` and would multiply one column-absence finding by the
            // number of qualifying rows — the flood these rules replace. Kept in step with
            // CrossCorpusDerivationTest, the shipped-corpus half.
            "FDA-SD9724", "PMDA-SD9724", "FDA-SD9725", "FDA-SD9726", "CDISC-SEND-9437",
            "CDISC-SEND-9438",
            // 36 -> 40 on 2026-09-11 (R-4.11c wave C3, the M16/M17 presence sisters): same
            // rationale again — the finding is that the RDOMAIN (M16) or DSSCAT (M17) COLUMN is
            // absent, one dataset-level fact, while every trigger leaf is per-row
            // (not empty(IDVAR), not empty(IDVARVAL), not is_unique_set([DSCAT, USUBJID, EPOCH]),
            // DSCAT == "DISPOSITION EVENT"), so the derivation reads `Record` and would multiply
            // one column-absence finding by the number of qualifying rows — the flood these rules
            // replace. DRAFT-900045 is the corpus's first DRAFT-family member of this list; its
            // model CDISC-CG0535 has sat in the base eight since the list was written, so the
            // shape is proven (its CORE twin CORE-000212 sat beside it until the 2026-09 family
            // retirement). ⚠ The M15 pair FDA/PMDA-SD9727 is deliberately NOT
            // here: like FDA/PMDA-SD9718 its Check is nothing but var_exists leaves, so the
            // derivation itself returns `Dataset` and an authored value would fail the disagree
            // clause below. Kept in step with CrossCorpusDerivationTest, the shipped-corpus half.
            "CDISC-CG9675", "FDA-SD9728", "PMDA-SD9728", "DRAFT-900045",
            // 40 -> 41 on 2026-09-11 (wave D, the M10 SENDIG carry-over): CDISC-SEND-9439,
            // sister of CDISC-SEND-0125.1 — the finding is that the SEUPDES COLUMN is absent,
            // one dataset-level fact, while the trigger leaf ETCD == "UNPLAN" is per-row, so
            // the derivation reads `Record` (verified: CDISC-CG9674, the same shape, sits
            // above). ⚠ The four wave-D R-5.25 split siblings FDA/PMDA-SD1017-A and
            // FDA/PMDA-SD1018-A are deliberately NOT here: a split sibling is RECORD-level —
            // it re-derives its parent's own per-row check by another route rather than
            // reporting the absence once — so authored == derived would red the disagree
            // clause below (CDISC-CG0031-A/CG0033-A precedent, routing Record/{ROW}). Kept
            // in step with CrossCorpusDerivationTest, the shipped-corpus half.
            "CDISC-SEND-9439",
            // +1 on 2026-10-02 (rule round 2026-10-02, owner): CDISC-CG9685, the --BDSYCD presence
            // sister of CDISC-CG0082 - same rationale as the --STRESU set: one dataset-level fact
            // (the column is absent) witnessed by a per-record leaf (not empty(--BODSYS)), so the
            // derivation reads Record and would multiply one finding by the populated rows.
            // Kept in step with the twin roster in the other test class.
            "CDISC-CG9685",
            // PMDA arm of CDISC-CG9685, same day and rationale.
            "PMDA-SD9750");

    /**
     * One {@code rules-src} fixture per evaluation domain ({@code {}}, {@code {VAR}},
     * {@code {ROW}}, {@code {VAR,ROW}} — the routing classes since the leaf-scope plan) the corpus
     * actually reaches, chosen from the corpus rather than hand-listed so the set cannot go stale:
     * the first rule (in sorted path order) whose Check infers each domain. <b>This is what widens
     * {@code R2} from a single fixture</b> — the clauses below are then asserted once per routing
     * class instead of once for {@code CDISC-CG0022}.
     *
     * @return one fixture per reached domain, by domain label
     * @throws IOException
     *             if {@code rules-src/checks} cannot be walked
     */
    static Stream<Arguments> oneFixturePerRoutingClass() throws IOException
    {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        Map<String, Path> firstOf = new TreeMap<>();
        for (Path file : files)
        {
            Rule rule = YAML.readValue(Files.readString(file, StandardCharsets.UTF_8), Rule.class);
            // 7b: materialise the Bindings before deriving (normalizeOperations javadoc).
            RulePackageLoader.materialiseBindings(rule);
            if (rule.getSensitivity() != null || rule.getCheck() == null)
            {
                // The documented override set: not a clause-1 fixture, it is clause 2's.
                continue;
            }
            String domain;
            try
            {
                domain = DomainScan
                        .infer(CheckExprUnits.toExpr(rule.getCheck()), BindingDomains.forRule(rule))
                        .label();
            }
            catch (net.cumba.corej.core.expr.ExpressionException _)
            {
                continue;
            }
            firstOf.putIfAbsent(domain, file);
        }
        assertEquals(4, firstOf.size(),
                "expected the corpus to reach every routing class, got " + firstOf.keySet());
        return firstOf.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }


    /**
     * <b>Clauses 1, 4 and 5 of the derivation contract of
     * {@link RulePackageLoader#deriveOmittedFields(Rule)}</b>, asserted as a specification rather
     * than against a stored copy of its own output — and now over
     * {@linkplain #oneFixturePerRoutingClass one fixture per routing class} rather than the single
     * {@code CDISC-CG0022} sample the wave-33 review flagged as {@code R2}.
     *
     * <ol>
     * <li>a rule carrying no {@code Sensitivity} gets it derived;</li>
     * <li><i>(clause 2 — an authored value wins — is
     * {@link #anAuthoredValueSurvivesTheDerivation})</i>;</li>
     * <li><i>(clause 3 — the seeding order — is
     * {@link #theSensitivityDerivationIsTypeFreeBecauseTheGenericPresenceOperatorIsRetired})</i>;</li>
     * <li>an applied derivation records a <b>rationale</b>;</li>
     * <li>it is <b>idempotent</b>, and a no-op on a rule that failed to load.</li>
     * </ol>
     *
     * @param domain
     *            the routing class (inferred evaluation domain) this fixture was selected for
     * @param sample
     *            the {@code rules-src} fixture
     * @throws IOException
     *             if the fixture cannot be read
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("oneFixturePerRoutingClass")
    void deriveOmittedFieldsFollowsItsDocumentedContract(String domain, Path sample)
        throws IOException
    {
        String yaml = Files.readString(sample, StandardCharsets.UTF_8);

        // (1) not authored -> derived.
        Rule rule = YAML.readValue(yaml, Rule.class);
        assertNull(rule.getSensitivity(), "fixture must start with no authored Sensitivity");
        assertNotNull(rule.getCheck(), "fixture must carry a Check");
        assertEquals(domain,
                DomainScan
                        .infer(CheckExprUnits.toExpr(rule.getCheck()), BindingDomains.forRule(rule))
                        .label(),
                "the fixture really is the routing class it was selected for");
        RulePackageLoader.deriveOmittedFields(rule);
        assertNotNull(rule.getSensitivity(),
                "clause 1: Sensitivity must be derived (domain " + domain + ")");

        // (4) an applied derivation is explained.
        assertNotNull(rule.getDerivationRationale(), "clause 4: a rationale must be recorded");
        assertTrue(rule.getDerivationRationale().containsKey("Sensitivity"),
                "clause 4: Sensitivity rationale");

        // (5) idempotent.
        Sensitivity onceSensitivity = rule.getSensitivity();
        RulePackageLoader.deriveOmittedFields(rule);
        assertEquals(onceSensitivity, rule.getSensitivity(), "clause 5: idempotent on Sensitivity");

        // (5b) no-op on a rule that failed to load.
        Rule broken = YAML.readValue(yaml, Rule.class);
        broken.setLoadError("synthetic");
        RulePackageLoader.deriveOmittedFields(broken);
        assertNull(broken.getSensitivity(), "clause 5: no derivation on a rule with a loadError");
    }


    /**
     * <b>Clause 2 — an authored value wins.</b> Grounded in the corpus rather than only in a
     * synthetic override: the {@link #SENSITIVITY_OVERRIDES} are the rules that really do carry an
     * authored {@code Sensitivity} in {@code rules-src}, and
     * {@link #everySensitivityOverrideStillDisagreesWithTheDerivation} proves the derivation
     * disagrees with each — so surviving the derivation is an observable property, not a tautology.
     *
     * @param id
     *            the overriding rule's {@code Core.Id}
     * @throws IOException
     *             if the fixture cannot be read
     */
    @ParameterizedTest
    @MethodSource("sensitivityOverrides")
    void anAuthoredValueSurvivesTheDerivation(String id) throws IOException
    {
        Path file = locate(id);
        assertNotNull(file, "override rule missing from rules-src: " + id);
        String yaml = Files.readString(file, StandardCharsets.UTF_8);

        Rule authored = YAML.readValue(yaml, Rule.class);
        Sensitivity authoredSensitivity = authored.getSensitivity();
        assertNotNull(authoredSensitivity, id + " must carry an authored Sensitivity");
        RulePackageLoader.deriveOmittedFields(authored);
        assertEquals(authoredSensitivity, authored.getSensitivity(),
                "clause 2: an authored Sensitivity must survive the derivation");
        assertTrue(
                authored.getDerivationRationale() == null
                        || !authored.getDerivationRationale().containsKey("Sensitivity"),
                "clause 2: an authored field must not record a derivation rationale");
    }


    static Stream<String> sensitivityOverrides()
    {
        return SENSITIVITY_OVERRIDES.stream();
    }


    /**
     * <b>Clause 3, re-grounded by phase 1 of {@code PLAN-leaf-scope-domain-inference.md}.</b> The
     * seeded {@code Rule_Type} used to reach {@code deriveSensitivity} through exactly one boolean
     * ({@code ruleType == DOMAIN_PRESENCE_CHECK}), which re-read a generic
     * {@code exists}/{@code not_exists} leaf as dataset presence; the wave-34 lane-B witness was a
     * synthetic {@code ds_not_exists} beside a generic {@code not_exists} — the only shape on which
     * the seeding was observable (no corpus rule had it: 0 of 3 734 then, 0 of 3 802 now).
     *
     * <p>
     * The generic operator is retired (owner ruling 1), so the parameter was a <em>dead</em>
     * parameter and was removed rather than re-justified. This pins the removal as a fact, not an
     * assumption: the witness now fails to load with the migration message, and the two explicit
     * spellings of the same conjunct carry the information the type used to — {@code ds_not_exists}
     * derives {@code Study} (nothing reads the dataset under evaluation), {@code var_not_exists}
     * derives {@code Dataset} — with no type in sight.
     * </p>
     *
     * <p>
     * ⚠ The witness is written as an {@code expression:} since phase 7d of
     * {@code PLAN-typed-expression-engine.md} (D121) retired the operator-leaf {@code Check} form
     * outright: an {@code operator:} node is now refused by {@code CheckConditionDeserializer}
     * before any rule-level validation runs, which would have masked the load error this test is
     * about behind a Jackson {@code MismatchedInputException}. Both subjects are unchanged —
     * {@code genericPresenceCall} walks the {@code Expr}, and {@code deriveSensitivity} classifies
     * the raised conjuncts.
     * </p>
     */
    @Test
    void theSensitivityDerivationIsTypeFreeBecauseTheGenericPresenceOperatorIsRetired()
        throws IOException
    {
        String witness = """
                Core:
                  Id: "LANE-B-CLAUSE3-SYNTHETIC"
                Description: "Synthetic clause-3 witness: no corpus rule has this shape."
                Check:
                  expression: 'ds_not_exists("AE") and not_exists("SUPPAE")'
                Outcome:
                  Message: "synthetic"
                """;
        Rule loaded = RulePackageLoader
                .loadFromString("{\"rules\":{\"LANE-B-CLAUSE3-SYNTHETIC\":"
                        + new ObjectMapper().writeValueAsString(YAML.readTree(witness)) + "}}")
                .getRules().get("LANE-B-CLAUSE3-SYNTHETIC");
        assertNotNull(loaded);
        assertNotNull(loaded.getLoadError(), "the generic not_exists must be rejected at load");
        assertTrue(loaded.getLoadError().contains("retired generic presence operator 'not_exists'"),
                loaded.getLoadError());
        assertTrue(loaded.getLoadError().contains("ds_not_exists(X)"), loaded.getLoadError());

        Rule dataset = YAML.readValue(witness.replace("and not_exists(", "and ds_not_exists("),
                Rule.class);
        assertEquals(Sensitivity.STUDY, RuleClassifier.deriveSensitivity(dataset).value(),
                "two dataset-presence leaves read nothing from the dataset under evaluation");
        Rule column = YAML.readValue(witness.replace("and not_exists(", "and var_not_exists("),
                Rule.class);
        assertEquals(Sensitivity.DATASET, RuleClassifier.deriveSensitivity(column).value(),
                "a column-presence leaf reads the dataset under evaluation");
    }


    /** The first {@code rules-src} file whose name is {@code id}.yaml, or {@code null}. */
    private static @Nullable Path locate(String id) throws IOException
    {
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            return walk.filter(p -> p.getFileName().toString().equals(id + ".yaml")).findFirst()
                    .orElse(null);
        }
    }


    /**
     * ⭐ <b>The override set polices itself.</b> Each of the {@link #SENSITIVITY_OVERRIDES} exists
     * for exactly one reason: the derivation is documented to get that rule wrong, so the authored
     * value wins. That justification is checkable — and if it stops holding (the classifier
     * improves, or the rule is rewritten), the override becomes dead weight that silently
     * suppresses a now-correct derivation.
     *
     * <p>
     * So: every override must still <em>disagree</em> with the derivation. This is the assertion
     * the old {@code rules-legacy} comparison could never make — it {@code continue}d past the
     * override set precisely to avoid looking at it.
     * </p>
     */
    @Test
    void everySensitivityOverrideStillDisagreesWithTheDerivation() throws IOException
    {
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : SENSITIVITY_OVERRIDES)
        {
            List<Path> found;
            try (Stream<Path> walk = Files.walk(CHECKS_DIR))
            {
                found = walk.filter(p -> p.getFileName().toString().equals(id + ".yaml")).toList();
            }
            if (found.isEmpty())
            {
                missing.add(id);
                continue;
            }
            Rule authoredRule = YAML
                    .readValue(Files.readString(found.get(0), StandardCharsets.UTF_8), Rule.class);
            RulePackageLoader.materialiseBindings(authoredRule);
            Sensitivity authored = authoredRule.getSensitivity();
            if (authored == null)
            {
                missing.add(id + " (no authored Sensitivity in rules-src)");
                continue;
            }
            Sensitivity derived = RuleClassifier.deriveSensitivity(authoredRule).value();
            if (authored == derived)
            {
                stale.add(id + ": authored=" + authored + " now equals derived=" + derived);
            }
        }
        assertEquals(List.of(), missing,
                "every documented Sensitivity override must exist in rules-src and carry the field");
        assertEquals(List.of(), stale,
                "these overrides no longer disagree with the derivation — the derivation caught up,"
                        + " so the authored value is now redundant and must be removed from both"
                        + " rules-src and SENSITIVITY_OVERRIDES rather than left to mask a future"
                        + " regression");
    }


    /**
     * The complement of Gate B: {@code rules-src} must have <em>shed</em> both fields everywhere
     * except the documented override set. A field that creeps back would silently win over the
     * derivation, which is precisely the duplication phase 5 removed.
     */
    @Test
    void rulesSrcCarriesTheFieldsOnlyForTheDocumentedOverrides() throws IOException
    {
        List<String> unexpectedType = new ArrayList<>();
        List<String> unexpectedSensitivity = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files)
        {
            JsonNode node = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String id = node.path("Core").path("Id").asText("<unknown>");
            if (node.hasNonNull("Rule_Type"))
            {
                unexpectedType.add(id);
            }
            if (node.hasNonNull("Sensitivity") && !SENSITIVITY_OVERRIDES.contains(id))
            {
                unexpectedSensitivity.add(id);
            }
        }
        assertEquals(List.of(), unexpectedType,
                "Rule_Type is no longer a rule field and must not appear in rules-src");
        assertEquals(List.of(), unexpectedSensitivity,
                "Sensitivity may only be authored for the documented override set");
    }
}
