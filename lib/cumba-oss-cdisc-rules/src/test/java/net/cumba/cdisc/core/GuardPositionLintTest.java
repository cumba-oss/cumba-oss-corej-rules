package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code plans/done/PLAN-guard-position-lint.md}</b> &mdash; the guard-position authoring rule,
 * asserted in the build. {@code Fix #237}.
 *
 * <p>
 * {@code STANDING-RULINGS.md} &sect;1 decided on <b>2026-08-07</b> that <em>"a check below an
 * {@code any} is not to be seen as a guard"</em>, and nothing has enforced it since. A guard
 * authored into a disjunct is silently inert, and on {@code CORE-000269} / {@code CORE-000270} it
 * is worse than inert: {@code var_not_exists(SV.SVPRESP)} is TRUE on an absent {@code SV}, so the
 * misplaced guard <b>enables</b> the rule and it floods against a vacuous {@code $tv_visit}.
 * {@link GuardPositionAudit} derives where every foreign presence leaf actually sits; this test
 * pins that derivation.
 * </p>
 *
 * <h2>&#9776; Ratchet, not a gate to zero</h2>
 *
 * <p>
 * The corpus has <b>5</b> wrongly-positioned foreign presence leaves and {@link #EXPECTED_DEFECTS}
 * pins the <em>known</em> set so the test fails on anything <b>new</b>, exactly as
 * {@code IntentAbsenceOptOutLintTest} does. Removing a row is as much a change as adding one:
 * re-adjudicate, do not just re-pin &mdash; the one removal on record is {@code FDA-SD1023},
 * re-adjudicated under {@code D-TA-2}/{@code D-TA-6a} (2026-08-17) when the rule lost its
 * {@code SV} join entirely. &#9888; Since {@code Fix #246} (wave 40) the four remaining
 * CG0031/CG0033-family rows are no longer live defects &mdash; their flood route is closed by a
 * top-level dotted {@code TV} guard and they derive
 * {@link GuardPositionAudit.PresenceLeaf#mitigated()} &mdash; but the leaves themselves still sit
 * below an {@code any}, so they stay in the ratchet with re-adjudicated prose rather than being
 * exempted.
 * </p>
 *
 * <h2>&#9888;&#9888; What this lint deliberately does NOT flag</h2>
 *
 * <ol>
 * <li><b>Bucket (c) &mdash; the {@code Operations}-only surface.</b> {@code 213} rules reach a
 * foreign dataset only through an {@code Operations} entry's {@code domain:}
 * ({@code $ta_epoch = distinct(EPOCH, domain: "TA")}). They carry no presence leaf at all, so they
 * are structurally outside the population; {@link #theOperationOnlySurfaceIsCountedNeverFlagged()}
 * asserts that and reports the count. Flagging them is how a lint gets switched off on its first
 * run.</li>
 * <li><b>Presence leaves that are the rule's own assertion.</b> {@code CDISC-SEND-0400} is
 * {@code any[ TT ds_not_exists, TP ds_not_exists, SJ ds_not_exists ]} &mdash; three presence leaves
 * under a top-level {@code any}, none of them a guard, because the rule exists to report the
 * missing domain. Position alone flags all three. See
 * {@link #theNaiveReadingWithoutTheReadsElsewhereClauseOverCounts()}.</li>
 * </ol>
 */
class GuardPositionLintTest
{

    /**
     * The <b>authored</b> view. &#9888; The shipped {@code rules/} view lowers Check to a string.
     */
    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    private static final Path REPORT = Path.of("documentation", "derivation", "guard-position.tsv");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Bucket <b>(b)</b> &mdash; every foreign presence leaf that is not in guard position, keyed
     * {@code ruleId|dataset}, with the adjudication reached by reading each rule file at source on
     * <b>2026-08-12</b>, re-adjudicated on <b>2026-08-13</b> when {@code Fix #246} landed and again
     * on <b>2026-08-17</b> when {@code D-TA-6a} removed one member. All were adjudicated; none was
     * sampled.
     *
     * <p>
     * <b>4 of 5 are real authoring defects</b>, all one shape &mdash; the
     * {@code any[ SV.SVPRESP not_exists, SV.SVPRESP == "Y" ]} conjunct of the CG0031/CG0033 rule
     * family. &#9888; The origin report named only {@code CORE-000269} / {@code CORE-000270}; the
     * lint found <b>three more</b> ({@code CDISC-CG0031}, {@code CDISC-CG0033},
     * {@code FDA-SD1023}), which is the whole reason for deriving the set rather than listing it.
     * {@code FDA-SD1023} then <b>left the population</b> under {@code D-TA-6a} (2026-08-17): the
     * rule was re-authored onto {@code PMDA-SD1023}'s no-join shape, so the misplaced {@code SV}
     * leaf is gone with the join rather than moved &mdash; a removal by re-adjudication, not a
     * re-pin.
     * </p>
     *
     * <p>
     * <b>{@code Fix #246} (wave 40) closed the flood on all five without moving the misplaced
     * leaf</b>: a top-level {@code TV.VISIT exists} / {@code TV.VISITNUM exists} conjunct &mdash;
     * the dotted-{@code exists} idiom of plan &sect;9a, chosen over the ruled {@code ds_exists}
     * shape because the assembler rejects a directly-authored {@code ds_} leaf under a non-presence
     * {@code Rule_Type} (since {@code Fix #255} the engine itself no longer re-types such a guard
     * &mdash; shape B demotes to Record Data and executes &mdash; but the corpus-side rejection
     * stands). The flood was never "absent {@code SV}" &mdash; that is the Description's documented
     * fallback ({@code Executability: "Partially Executable - Possible
     * Overreporting"}); it was the vacuous {@code $tv_visit} / {@code $tv_visitnum} membership test
     * on an absent {@code TV}, and the guard sits on {@code TV} accordingly. The {@code SV} leaves
     * therefore stay in bucket (b) &mdash; a leaf below an {@code any} still does not guard &mdash;
     * but they now derive {@link GuardPositionAudit.PresenceLeaf#mitigated()}, a structural
     * cross-dataset condition with <b>no exemption list</b> (see
     * {@link #theMitigationDerivationIsStructuralNotAList()}).
     * </p>
     *
     * <p>
     * <b>The sixth, {@code CORE-000709}, is a true positional finding but not a defect</b> &mdash;
     * and it is kept in the ratchet rather than special-cased, because a suppression list is a
     * place for a real defect to hide. Its shape is
     * {@code all[ any[ CE.CETERM == "DEATH", all[ CE.CEDECOD exists, CE.CEDECOD == "DEATH" ] ],
     * DTHFL != "Y" ]}: {@code var_exists(CE.CEDECOD)} is a <b>column</b>-level guard for its own
     * conjunct's sibling, not a dataset-level guard for the rule, and it sits correctly relative to
     * the one comparison it protects. An absent {@code CE} cannot flood the rule either, because
     * every {@code CE.*} comparison in it is an equality, and an equality against an absent column
     * is false on every row (the project's absent-column policy) &mdash; unlike
     * {@code is_not_contained_by}, which is vacuously <em>true</em> against an empty set and is
     * what made the other five dangerous. It has no vacuous {@code $}-membership at all, so it has
     * nothing to mitigate and correctly derives {@code mitigated == false}.
     * </p>
     */
    private static final Map<String, String> EXPECTED_DEFECTS = Map.of("CDISC-CG0031|SV",
            "mitigated (Fix #246) — SV.SVPRESP still sits inside an any (the documented SVPRESP"
                    + " fallback), but the top-level TV.VISIT exists guard closes the vacuous"
                    + " $tv_visit flood",
            "CDISC-CG0033|SV",
            "mitigated (Fix #246) — same family; the top-level TV.VISITNUM exists guard closes"
                    + " the vacuous $tv_visitnum flood",
            "CORE-000269|SV",
            "mitigated (Fix #246) — the origin instance (STANDING-RULINGS.md §1, 2026-08-12); TV.VISIT"
                    + " exists guard added, SV disjunct left as the documented fallback",
            "CORE-000270|SV",
            "mitigated (Fix #246) — the origin instance's VISITNUM twin; TV.VISITNUM exists"
                    + " guard added",
            "CORE-000709|CE",
            "not a defect — a column-level guard correctly placed inside its own conjunct; kept"
                    + " because an exemption list is where a real defect hides");

    /**
     * Bucket <b>(a)</b> &mdash; foreign presence leaves that really do guard. A floor, not a pin:
     * the number is expected to grow as guards are authored, and it is asserted only so that a
     * predicate that silently stops resolving datasets cannot leave the bucket-(b) ratchet looking
     * green over an empty sweep. Measured 2026-08-12: <b>43</b>.
     */
    private static final int GUARD_LEAF_FLOOR = 40;

    /**
     * Bucket <b>(c)</b> &mdash; rules whose foreign surface is reachable only through an
     * {@code Operations} {@code domain:}. Measured 2026-08-12: <b>218</b> authored rules;
     * re-derived by run 2026-08-13 after {@code Fix #246}: <b>213</b> &mdash; the five
     * CG0031/CG0033-family rules' dotted {@code TV} guard makes {@code TV} column-addressable, so
     * their {@code TV} rows leave the bucket. Re-derived again by run 2026-08-22 after the DRAFT
     * session deleted {@code DRAFT-110006} and {@code DRAFT-110007} (commit {@code 51dc809e9}),
     * whose only foreign surface was an {@code Operations} {@code domain: "DS"}: <b>213 &rarr;
     * 211</b>. A deletion, not a re-shaping &mdash; neither rule leaves a bucket-(b) residue.
     *
     * <p>
     * &#9888; The origin report said <b>"~40 TIG rules"</b>. That was the shipped
     * {@code rules-core-tig-1-0.json} slice; against the whole authored corpus the number was
     * {@code 218}, of which {@code 25} carry a TIG standard reference. Neither figure is wrong —
     * they are different populations — but the lint's exclusion has to cover the larger one.
     * </p>
     */
    private static final int OPERATION_ONLY_RULES = 211;

    // ------------------------------------------------------------------
    // Corpus plumbing
    // ------------------------------------------------------------------

    private static List<Path> ruleFiles() throws IOException
    {
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            return walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }


    private static Rule read(Path file)
    {
        try
        {
            Rule rule = YAML.readValue(file.toFile(), Rule.class);
            // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator or
            // domain until normalized — the same pass the loader runs.
            RulePackageLoader.normalizeOperations(rule);
            return rule;
        }
        catch (IOException _)
        {
            throw new UncheckedIOException(new IOException("cannot read " + file));
        }
    }


    private static Rule parse(String yaml)
    {
        try
        {
            Rule rule = YAML.readValue(yaml, Rule.class);
            RulePackageLoader.normalizeOperations(rule);
            return rule;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /** The bucket-(b) keys of an arbitrary rule set — the ratchet's subject, factored out. */
    private static Set<String> defectKeys(List<Rule> rules)
    {
        Set<String> keys = new TreeSet<>();
        for (Rule rule : rules)
        {
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    keys.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // The ratchet
    // ------------------------------------------------------------------


    /**
     * The deliverable. Derives the (a)/(b)/(c) partition over the authored corpus, writes
     * {@code documentation/derivation/guard-position.tsv}, and pins bucket (b).
     */
    @Test
    void theWronglyPositionedGuardPopulationIsExactlyTheSixKnownLeaves() throws IOException
    {
        List<Path> files = ruleFiles();
        assertTrue(files.size() >= 3700, "the authored corpus must actually have been read; found "
                + files.size() + " YAML files under " + CHECKS_DIR.toAbsolutePath());

        Map<String, String> defects = new TreeMap<>();
        List<String> defectRows = new ArrayList<>();
        List<String> operationOnlyRows = new ArrayList<>();
        int guardLeaves = 0;
        int structuralAll = 0;
        int operationOnlyRules = 0;
        for (Path file : files)
        {
            Rule rule = read(file);
            // Any structural root counts since the flip: the writer collapsed singleton `all:`
            // wrappers, so a one-conjunct rule deserialises to a bare leaf/not root.
            if (rule.getCheck() != null && !(rule.getCheck() instanceof CheckConditionExpression))
            {
                structuralAll++;
            }
            String org = String.valueOf(file.getParent().getFileName());
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.GUARD)
                {
                    guardLeaves++;
                    continue;
                }
                defects.put(leaf.ruleId() + '|' + leaf.dataset(), leaf.path());
                defectRows.add("b\t" + leaf.ruleId() + '\t' + org + '\t' + leaf.dataset() + '\t'
                        + leaf.operator() + '\t' + leaf.path() + '\t' + leaf.negated() + '\t'
                        + leaf.mitigated());
            }
            List<String> operationOnly = GuardPositionAudit.operationOnlyDatasets(rule);
            if (!operationOnly.isEmpty())
            {
                operationOnlyRules++;
                operationOnlyRows.add("c\t" + GuardPositionAudit.ruleId(rule) + '\t' + org + '\t'
                        + String.join(",", operationOnly) + "\t-\t-\t-\t-");
            }
        }

        // Blindness controls. A model or Jackson regression, or a predicate that stops resolving
        // datasets, returns "no defects" exactly as happily as a clean corpus.
        assertTrue(structuralAll >= 3000,
                "only " + structuralAll + " of " + files.size() + " rules deserialised to a"
                        + " structural Check; the sweep is not seeing the corpus");
        assertTrue(guardLeaves >= GUARD_LEAF_FLOOR,
                "only " + guardLeaves + " correctly-positioned foreign guards found (floor "
                        + GUARD_LEAF_FLOOR + "). The predicate has stopped resolving foreign"
                        + " datasets, so the bucket-(b) ratchet below is no longer trustworthy.");

        Files.createDirectories(REPORT.getParent());
        List<String> report = new ArrayList<>();
        report.add("# derived by GuardPositionLintTest (Fix #237; mitigated column Fix #246)"
                + " — do not hand-edit");
        report.add("# bucket a (correctly-positioned foreign guards, counted only): " + guardLeaves
                + " leaves");
        report.add("# bucket b (wrongly-positioned, rows below): " + defects.size() + " leaves");
        report.add("# bucket c (Operations-only foreign surface, rows below; NEVER flagged): "
                + operationOnlyRules + " rules");
        report.add("# bucket\trule_id\torg\tdataset(s)\toperator\tpath\tnegated\tmitigated");
        report.addAll(defectRows.stream().sorted().toList());
        report.addAll(operationOnlyRows.stream().sorted().toList());
        Files.write(REPORT, report, StandardCharsets.UTF_8);

        assertEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), new TreeSet<>(defects.keySet()),
                "the wrongly-positioned-guard population moved. A leaf below an `any` does not"
                        + " guard (STANDING-RULINGS.md §1, 2026-08-07) and on the CG0031 family it"
                        + " actively enables the rule. Adjudicate each new entry at source and"
                        + " record the verdict in EXPECTED_DEFECTS — see " + REPORT);
        assertEquals(OPERATION_ONLY_RULES, operationOnlyRules,
                "the Operations-only foreign surface moved. It is bucket (c) — never flagged, but"
                        + " it is the measured size of the engine gap, so a change is a finding.");
    }


    /**
     * <b>A3.</b> The ~40-rule exclusion, asserted rather than asserted-about: no rule whose foreign
     * surface is {@code Operations}-only appears in bucket (b) <b>for that dataset</b>.
     *
     * <p>
     * &#9888; Per {@code (rule, dataset)} pair, not per rule &mdash; {@code CORE-000269} is in both
     * lists and correctly so: its {@code TV} surface is {@code Operations}-only (bucket c) while
     * its {@code SV} surface is a join it mis-guards (bucket b). A per-rule assertion would have to
     * exempt it and would then be exempting the ground truth.
     * </p>
     */
    @Test
    void theOperationOnlySurfaceIsCountedNeverFlagged() throws IOException
    {
        Set<String> operationOnlyPairs = new TreeSet<>();
        Set<String> defectPairs = new TreeSet<>();
        for (Path file : ruleFiles())
        {
            Rule rule = read(file);
            String id = GuardPositionAudit.ruleId(rule);
            GuardPositionAudit.operationOnlyDatasets(rule)
                    .forEach(dataset -> operationOnlyPairs.add(id + '|' + dataset));
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(rule))
            {
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    defectPairs.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        assertTrue(operationOnlyPairs.size() >= 200, "only " + operationOnlyPairs.size()
                + " Operations-only surfaces found; the exclusion has stopped working");
        Set<String> leaked = new TreeSet<>(operationOnlyPairs);
        leaked.retainAll(defectPairs);
        assertEquals(Set.of(), leaked,
                "an Operations-only surface is being flagged as a wrongly-positioned guard. No"
                        + " column-presence guard is writable for one (exists/var_exists take a"
                        + " COLUMN name), so this is an expressiveness gap, not an authoring"
                        + " defect, and flagging it is how a lint gets disabled.");
        assertTrue(defectPairs.contains("CORE-000269|SV"),
                "CORE-000269's SV surface must still be flagged — the exclusion has widened past"
                        + " the Operations-only case and swallowed the ground truth");
    }


    /**
     * <b>A2.</b> The ground truth, read from the corpus rather than from a fixture: a fixture can
     * agree with a wrong predicate forever, whereas these two rules are the coordinator-verified
     * instances the whole lane exists for.
     */
    @Test
    void theGroundTruthRulesAreClassifiedFromTheCorpusItself() throws IOException
    {
        for (String id : List.of("CORE-000269", "CORE-000270"))
        {
            Rule rule = read(CHECKS_DIR.resolve("CORE").resolve(id + ".yaml"));
            List<GuardPositionAudit.PresenceLeaf> svLeaves = GuardPositionAudit.presenceLeaves(rule)
                    .stream().filter(l -> "SV".equals(l.dataset())).toList();
            assertEquals(1, svLeaves.size(), id + " must contribute exactly one SV presence leaf");
            GuardPositionAudit.PresenceLeaf leaf = svLeaves.getFirst();
            assertEquals(GuardPositionAudit.Position.DISJUNCT, leaf.position(),
                    id + " is the verified instance: SV.SVPRESP not_exists sits inside an `any`, so"
                            + " it cannot guard. A detector that calls it a guard is wrong.");
            // Since the flip the authored form is `not var_exists("SV.SVPRESP")` inside the
            // disjunct: the model's strict lowering keeps the structural `not`, so the audit
            // reports a NEGATED var_exists leaf where the legacy corpus authored a flat
            // not_exists. Same predicate, new spelling — the DISJUNCT classification is the
            // load-bearing half and is unchanged.
            assertEquals("var_exists", leaf.operator());
            assertTrue(leaf.negated(), id + " spells the absence as `not var_exists(...)`");
        }
    }

    // ------------------------------------------------------------------
    // Mitigation — Fix #246
    // ------------------------------------------------------------------


    /**
     * The four CG0031/CG0033-family rows are <b>mitigated, from the corpus itself</b>: each carries
     * a top-level dotted {@code TV} guard ({@code Fix #246}) that closes the vacuous
     * {@code $tv_visit} / {@code $tv_visitnum} flood, and the derivation sees it. The fifth row,
     * {@code CORE-000709}, has no vacuous {@code $}-membership at all &mdash; nothing to mitigate
     * &mdash; and must stay {@code false}, or the flag has degenerated into "true for everything in
     * the ratchet".
     *
     * <p>
     * &#9888; {@code FDA-SD1023} was the fifth guarded rule until <b>D-TA-2/6a</b> (2026-08-17)
     * re-authored it onto {@code PMDA-SD1023}'s no-join shape: it has no {@code SV} surface left,
     * so it contributes no disjunct leaf to mitigate. Its {@code Fix #246} {@code TV.VISIT} guard
     * was <b>kept</b> in the converged Check &mdash; the flood route the fix closed is the vacuous
     * {@code $tv_visit_keys} membership, which the new shape still has.
     * </p>
     */
    @Test
    void theFourGuardedRulesDeriveMitigatedFromTheCorpusAndTheFifthDoesNot() throws IOException
    {
        Map<String, String> guarded = Map.of("CORE/CORE-000269", "TV", "CORE/CORE-000270", "TV",
                "CDISC/CDISC-CG0031", "TV", "CDISC/CDISC-CG0033", "TV");
        for (Map.Entry<String, String> entry : guarded.entrySet())
        {
            Rule rule = read(CHECKS_DIR.resolve(entry.getKey() + ".yaml"));
            List<GuardPositionAudit.PresenceLeaf> leaves = GuardPositionAudit.presenceLeaves(rule);
            GuardPositionAudit.PresenceLeaf guard = leaves.stream()
                    .filter(l -> l.position() == GuardPositionAudit.Position.GUARD
                            && entry.getValue().equals(l.dataset()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            entry.getKey() + " must carry its Fix #246 top-level "
                                    + entry.getValue() + " guard — it is what closes the flood"));
            assertEquals("var_exists", guard.operator(),
                    entry.getKey() + " guards via the dotted-exists idiom (var_exists since the"
                            + " flip's disambiguation), not ds_exists (which the assembler rejects"
                            + " under a non-presence Rule_Type — Fix #255)");
            GuardPositionAudit.PresenceLeaf sv = leaves.stream()
                    .filter(l -> l.position() == GuardPositionAudit.Position.DISJUNCT).findFirst()
                    .orElseThrow(() -> new AssertionError(entry.getKey()
                            + " must still carry its SV disjunct leaf — the documented fallback"));
            assertTrue(sv.mitigated(), entry.getKey() + "'s disjunct leaf must derive mitigated:"
                    + " the guard on " + entry.getValue() + " closes its only flood route");
        }

        Rule seventhOhNine = read(CHECKS_DIR.resolve("CORE").resolve("CORE-000709.yaml"));
        GuardPositionAudit.presenceLeaves(seventhOhNine)
                .forEach(leaf -> assertFalse(leaf.mitigated(),
                        "CORE-000709 has no vacuous $-membership, so it has nothing to mitigate;"
                                + " a true here means the flag no longer discriminates"));
    }


    /**
     * The mitigating condition, clause by clause &mdash; <b>structural, cross-dataset, and with no
     * exemption list</b>. The shape mirrors the real family: the flagged leaf is on the joined
     * {@code DM} while the danger (and therefore the guard) is on {@code TA}, the {@code domain:}
     * of the {@code $}-operation the membership test reads. Neuter any clause of
     * {@code GuardPositionAudit}'s mitigation pass and at least one of these flips.
     */
    @Test
    void theMitigationDerivationIsStructuralNotAList()
    {
        String operations = """
                Operations:
                - id: "$ta_epoch"
                  operator: "distinct"
                  domain: "TA"
                  name: "EPOCH"
                """;
        String misplacedGuard = """
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_not_exists"
                    - name: "DM.DTHFL"
                      operator: "equal_to"
                      value: "Y"
                      value_is_literal: true
                """;
        String membership = """
                  - name: "EPOCH"
                    operator: "is_not_contained_by"
                    value: "$ta_epoch"
                """;

        // The mitigated shape: a top-level guard on the DANGEROUS dataset (TA), not on the
        // flagged leaf's own dataset (DM).
        assertEquals(Map.of("DM", true), mitigations(operations + """
                Check:
                  all:
                  - name: "TA.EPOCH"
                    operator: "var_exists"
                """ + misplacedGuard + membership),
                "a top-level guard on the vacuous operation's dataset mitigates the disjunct");

        // Drop the guard: same disjunct, same danger, nothing closing it.
        assertEquals(Map.of("DM", false),
                mitigations(operations + "Check:\n  all:\n" + misplacedGuard + membership),
                "without the TA guard the flood route is open — not mitigated");

        // Guard the WRONG dataset: a guard on DM (the leaf's own dataset) does not close a
        // vacuous $ta_epoch. Mitigation is about the danger, not about the leaf.
        assertEquals(Map.of("DM", false), mitigations(operations + """
                Check:
                  all:
                  - name: "DM.DTHFL"
                    operator: "var_exists"
                """ + misplacedGuard + membership),
                "a guard on the flagged leaf's own dataset leaves the vacuous-set route open");

        // The guard authored into the any — the very defect this lint exists for — must not
        // count as mitigation.
        assertEquals(Map.of("DM", false, "TA", false), mitigations(operations + """
                Check:
                  all:
                  - any:
                    - name: "TA.EPOCH"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                """ + misplacedGuard + membership),
                "a TA guard below an any does not guard, so it cannot mitigate either");

        // No vacuous membership at all (equality instead): nothing to mitigate, so the flag
        // stays false — CORE-000709's shape.
        assertEquals(Map.of("DM", false), mitigations(operations + """
                Check:
                  all:
                  - name: "TA.EPOCH"
                    operator: "var_exists"
                """ + misplacedGuard + """
                  - name: "EPOCH"
                    operator: "equal_to"
                    value: "$ta_epoch"
                """), "an equality is false against an absent column — no flood, no mitigation");

        // De Morgan: not[ TA.EPOCH not_exists ] in guard position IS a presence assertion.
        assertEquals(Map.of("DM", true), mitigations(operations + """
                Check:
                  all:
                  - not:
                      name: "TA.EPOCH"
                      operator: "var_not_exists"
                """ + misplacedGuard + membership),
                "`not[not_exists]` implies presence, so it mitigates exactly as `exists` does");
    }


    /** The {@code dataset -> mitigated} map of one synthetic rule body's DISJUNCT leaves. */
    private static Map<String, Boolean> mitigations(String body)
    {
        Map<String, Boolean> byDataset = new LinkedHashMap<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(parse("""
                Core:
                  Id: "SYNTHETIC-0002"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Match_Datasets:
                - Name: "DM"
                  Keys:
                  - "USUBJID"
                """ + body)))
        {
            if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
            {
                byDataset.put(leaf.dataset(), leaf.mitigated());
            }
        }
        return byDataset;
    }

    // ------------------------------------------------------------------
    // Refuted readings — kept live, not merely described
    // ------------------------------------------------------------------


    /**
     * The <b>reads-elsewhere</b> clause, refuted in the direction it corrects. Dropping it —
     * classifying on position and foreignness alone — turns {@code CDISC-SEND-0400}'s three
     * {@code ds_not_exists} assertions into "wrongly-positioned guards", which they are not: the
     * rule reads none of {@code TT}/{@code TP}/{@code SJ}, so there is nothing there to guard.
     * Measured 2026-08-12: <b>3</b> spurious bucket-(b) leaves and <b>77</b> spurious bucket-(a)
     * ones.
     */
    @Test
    void theNaiveReadingWithoutTheReadsElsewhereClauseOverCounts() throws IOException
    {
        List<Rule> corpus = ruleFiles().stream().map(GuardPositionLintTest::read).toList();
        int kept = 0;
        int naive = 0;
        Set<String> naiveDefects = new TreeSet<>();
        for (Rule rule : corpus)
        {
            kept += GuardPositionAudit.presenceLeaves(rule).size();
            for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit
                    .presenceLeavesIgnoringReadsElsewhere(rule))
            {
                naive++;
                if (leaf.position() == GuardPositionAudit.Position.DISJUNCT)
                {
                    naiveDefects.add(leaf.ruleId() + '|' + leaf.dataset());
                }
            }
        }
        // Re-derived by run 2026-08-13 after Fix #246: the five new TV guards are foreign
        // presence leaves in both readings, so 49 → 54 and 129 → 134. Re-derived again by run
        // 2026-08-17 after D-TA-6a re-authored FDA-SD1023 onto PMDA-SD1023's no-join shape: its
        // `not var_exists(SV.SVPRESP)` leaf is gone (its TV guard stays), which is one leaf in
        // BOTH readings, so 54 → 53 and 134 → 133. Re-derived again by run 2026-08-18 after
        // CL-G.1 / EC-82 option (a) (wave TA-4) gave PMDA-SD1023 the same `var_exists(TV.VISIT)`
        // guard, completing the Fix #246 family: one new foreign presence leaf in both readings,
        // so 53 → 54 and 133 → 134. Re-derived by run 2026-08-22: the four `ds_exists("TV")`
        // rules minted in 51dc809e9 (CDISC-CG9671, CORE-009705, FDA-SD9713, PMDA-SD9713) add
        // four leaves and the deleted DRAFT-110005 removes one, so 134 → 137. ⚠ The wave-TA-6
        // note previously credited this to the `var_exists("ECDOSE") and empty(ECDOSE)`
        // re-spelling; that was wrong — an undotted `var_exists` name resolves to no dataset
        // (datasetOf returns null), so those leaves were never counted, and Fix #340's removal
        // of them moved neither reading. `kept` stays 54.
        // Re-derived by run 2026-08-24, PLAN-scope-requirements-split phase 4c: the ten POS
        // `ds_exists` preconditions (CDISC-AD0061, CDISC-AD0365, PMDA-AD0061, PMDA-AD0061A on EX;
        // CDISC-CG0105, CDISC-CG0107, CORE-000291, CORE-000292, PMDA-SD1468, PMDA-SD1469 on EC)
        // moved their guard out of the Check and into `Requirements: {Datasets: [...]}`, so ten
        // foreign presence leaves left the corpus: 137 → 127. ⭐ `kept` does NOT move and stays
        // 54 — the reads-elsewhere clause already excluded all ten (none of those rules reads EX
        // or EC anywhere else), which is exactly the asymmetry this control exists to expose.
        // ⚑ The 4d′ redundant-OR wipe of the same wave moved NEITHER count: all 237 stripped
        // `not var_exists("X")` disjuncts name an UNDOTTED column (measured: 0 of 237 dotted), and
        // an undotted name resolves to no dataset (datasetOf returns null), so those leaves were
        // never counted in either reading.
        // 2026-08-26 (PLAN-retire-corpus-transforms phase 4): 54 → 55. The retired corpus
        // guard pass's `var_exists(`ADSL.&VAR`)` on CDISC-AD0591 is now AUTHORED (the loader's
        // R-gate forbids hoisting an expansion token into Requirements.Variables.All), and a
        // dotted authored presence leaf whose rule reads the same foreign column elsewhere is
        // exactly what `kept` counts.
        assertEquals(55, kept, "the predicate's leaf count moved");
        // 2026-08-26 (PLAN-retire-corpus-transforms phase 4): 127 → 128 — the same authored
        // `var_exists(`ADSL.&VAR`)` leaf that moved `kept` 54 → 55 (see above) is a dotted
        // presence leaf under the naive reading too.
        assertEquals(128, naive, "the naive reading's leaf count moved");

        Set<String> spurious = new TreeSet<>(naiveDefects);
        spurious.removeAll(EXPECTED_DEFECTS.keySet());
        assertEquals(Set.of("CDISC-SEND-0400|SJ", "CDISC-SEND-0400|TP", "CDISC-SEND-0400|TT"),
                spurious,
                "the naive reading's false positives moved. They are a control, not a"
                        + " target: if they went to zero the sweep has stopped reading rules and"
                        + " the ratchet is no longer trustworthy.");
    }


    /**
     * <b>The lint can go red.</b> A guard authored into the wrong position in a rule that is
     * <em>not</em> in {@link #EXPECTED_DEFECTS} must break the ratchet. Asserted against the real
     * corpus plus one injected rule, so it exercises the same comparison the deliverable does — a
     * lint that cannot red is the vacuity this project keeps catching.
     */
    @Test
    void aNewlyIntroducedWronglyPositionedGuardRedsTheRatchet() throws IOException
    {
        List<Rule> corpus = new ArrayList<>(
                ruleFiles().stream().map(GuardPositionLintTest::read).toList());
        assertEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), defectKeys(corpus),
                "baseline: the untouched corpus matches the pin");

        corpus.add(parse("""
                Core:
                  Id: "SYNTHETIC-NEW-DEFECT"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Match_Datasets:
                - Name: "DM"
                  Keys:
                  - "USUBJID"
                Check:
                  all:
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_not_exists"
                    - name: "DM.DTHFL"
                      operator: "equal_to"
                      value: "Y"
                      value_is_literal: true
                  - name: "AETERM"
                    operator: "non_empty"
                """));
        Set<String> withDefect = defectKeys(corpus);
        assertTrue(withDefect.contains("SYNTHETIC-NEW-DEFECT|DM"),
                "the injected wrongly-positioned guard was not detected at all");
        assertNotEquals(new TreeSet<>(EXPECTED_DEFECTS.keySet()), withDefect,
                "the ratchet did not move when a new wrongly-positioned guard was introduced");
    }

    // ------------------------------------------------------------------
    // Synthetic controls — one per clause of the classification
    // ------------------------------------------------------------------


    /**
     * The positional classification, clause by clause. Neuter any arm of
     * {@link GuardPositionAudit}'s walk and at least one of these flips.
     */
    @Test
    void everyPositionalShapeIsClassifiedCorrectly()
    {
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), positions("""
                Check:
                  all:
                  - name: "DM.DTHFL"
                    operator: "var_exists"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """), "a top-level `all` conjunct guards");

        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT), positions("""
                Check:
                  all:
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """), "a leaf below an `any` does not guard — the whole point of the lint");

        // ⚑ Conjunction is associative. A "direct child of the root `all`" test false-positives
        // here, which is why the walk carries an implication flag rather than a depth.
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), positions("""
                Check:
                  all:
                  - all:
                    - name: "DM.DTHFL"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """), "a nested `all` still guards");
    }


    /**
     * {@code not} is not {@code any}, in both directions. &#9888; The authored corpus has
     * <b>zero</b> {@code not:} nodes, so without these two synthetics the De Morgan arm is dead
     * code that would rot unnoticed and then mis-classify the first rule authored with one.
     */
    @Test
    void theNegationArmFollowsDeMorganInBothDirections()
    {
        // ¬(a ∨ b) ≡ ¬a ∧ ¬b — the leaf IS implied by the check, in negated form, so it guards.
        Map<String, GuardPositionAudit.Position> underNotAny = positions("""
                Check:
                  all:
                  - not:
                      any:
                      - name: "DM.DTHFL"
                        operator: "var_exists"
                      - name: "AETERM"
                        operator: "non_empty"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """);
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), underNotAny,
                "`not[any[...]]` is a conjunction — its leaves guard");

        // ¬(a ∧ b) ≡ ¬a ∨ ¬b — a disjunction, so the leaf does not guard.
        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT), positions("""
                Check:
                  all:
                  - not:
                      all:
                      - name: "DM.DTHFL"
                        operator: "var_exists"
                      - name: "AETERM"
                        operator: "non_empty"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """), "`not[all[...]]` is a disjunction — its leaves do not guard");
    }


    /**
     * Foreignness and the presence-operator set. Each of these must produce <b>no</b> finding, and
     * each corresponds to one way the predicate could silently widen.
     */
    @Test
    void nonForeignAndNonPresenceLeavesAreNotFindings()
    {
        assertEquals(Map.of(), positions("""
                Check:
                  all:
                  - any:
                    - name: "AE.AETERM"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                """), "AE is the rule's own Scope.Domains.Include — not foreign");

        assertEquals(Map.of(), positions("""
                Check:
                  all:
                  - any:
                    - name: "DM.DTHFL"
                      operator: "non_empty"
                    - name: "AETERM"
                      operator: "non_empty"
                  - name: "DM.DTHFL"
                    operator: "equal_to"
                    value: "Y"
                    value_is_literal: true
                """), "non_empty is not a presence operator — absence and emptiness differ");

        assertEquals(Map.of(), positions("""
                Check:
                  all:
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                """), "nothing else reads DM, so the leaf is the rule's assertion, not a guard");
    }


    /**
     * The {@code $}-id arm: a presence leaf on an operation result takes its dataset from the
     * operation's {@code domain:}, and a domainless operation is local. &#9888; This is the arm the
     * {@code IntentAbsenceOptOut} predicate's own plan omitted, at the cost of finding none of the
     * four rules it claimed to return.
     */
    @Test
    void theOperationIdArmResolvesThroughTheOperationsDomain()
    {
        assertEquals(Map.of("TS", GuardPositionAudit.Position.DISJUNCT), positions("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  domain: "TS"
                  name: "TSVAL"
                Check:
                  all:
                  - any:
                    - name: "$ts_species"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                  - name: "$ts_species"
                    operator: "non_empty"
                """), "a $-id resolves to its operation's domain:");

        assertEquals(Map.of(), positions("""
                Operations:
                - id: "$ts_species"
                  operator: "ts_parameter_value"
                  name: "TSVAL"
                Check:
                  all:
                  - any:
                    - name: "$ts_species"
                      operator: "var_exists"
                    - name: "AETERM"
                      operator: "non_empty"
                  - name: "$ts_species"
                    operator: "non_empty"
                """), "an operation with no domain: is local, so there is no foreign surface");
    }


    /**
     * {@code ds_exists} takes a <b>dataset</b> name, and {@link GuardPositionAudit} must resolve it
     * as one rather than looking for a dot. This is also the evidence behind the note for the
     * owner: a dataset-level guard <em>is</em> writable for a bucket-(c) surface, so "no guard is
     * expressible" overstates the gap — only the column-level form is missing.
     */
    @Test
    void theDatasetPresenceOperatorsResolveTheirNameAsTheDataset()
    {
        assertEquals(Map.of("TA", GuardPositionAudit.Position.GUARD), positions("""
                Operations:
                - id: "$ta_epoch"
                  operator: "distinct"
                  domain: "TA"
                  name: "EPOCH"
                Check:
                  all:
                  - name: "TA"
                    operator: "ds_exists"
                  - name: "EPOCH"
                    operator: "is_not_contained_by"
                    value: "$ta_epoch"
                """), "ds_exists names the dataset directly and guards the operation's surface");
    }

    // ------------------------------------------------------------------
    // Expression-form Checks — Fix #256 (pre-flip re-point, PLAN-rules-src-expression-flip §7b)
    // ------------------------------------------------------------------


    /**
     * <b>{@code Fix #256}.</b> An expression-form {@code Check} that the strict load-path lowering
     * declines (here through the raised {@code is_numeric} predicate, which has no v1 lowering)
     * arrives at the audit as a {@code CheckConditionExpression} — and must be audited exactly as
     * its operator-leaf equivalent, in both positions, not silently skipped. Unreachable on today's
     * all-legacy corpus; the arm exists so the {@code rules-src} expression flip cannot make this
     * audit vacuous.
     */
    @Test
    void anExpressionFormCheckIsAuditedIdenticallyToItsLegacyForm()
    {
        // GUARD position. The legacy body is the exact conversion-lowered twin of the expression
        // (is_numeric(X) restores to the matches_regex leaf it was raised from, value_is_literal
        // set by ExprLowering.regexLeaf).
        String legacyGuard = """
                Check:
                  all:
                  - name: "DM.DTHFL"
                    operator: "var_exists"
                  - name: "DM.AGE"
                    operator: "matches_regex"
                    value: "^-?(0|[1-9]\\\\d*)(\\\\.\\\\d+)?$"
                    value_is_literal: true
                """;
        String expressionGuard = """
                Check:
                  expression: 'var_exists(DM.DTHFL) and is_numeric(DM.AGE)'
                """;
        assertEquals(Map.of("DM", GuardPositionAudit.Position.GUARD), positions(expressionGuard),
                "the expression-form Check must be audited at all — an empty map is the"
                        + " pre-Fix-#256 silent skip");
        assertEquals(positions(legacyGuard), positions(expressionGuard),
                "the two forms of the same Check must audit identically");

        // DISJUNCT position — the defect shape the lint exists for, authored as an expression.
        String legacyDisjunct = """
                Check:
                  all:
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_not_exists"
                    - name: "DM.AGE"
                      operator: "matches_regex"
                      value: "^-?(0|[1-9]\\\\d*)(\\\\.\\\\d+)?$"
                      value_is_literal: true
                  - name: "DM.AGE"
                    operator: "non_empty"
                """;
        String expressionDisjunct = """
                Check:
                  expression: '(var_not_exists(DM.DTHFL) or is_numeric(DM.AGE)) and non_empty(DM.AGE)'
                """;
        assertEquals(Map.of("DM", GuardPositionAudit.Position.DISJUNCT),
                positions(expressionDisjunct),
                "a misplaced guard inside an expression-form Check must still be flagged");
        assertEquals(positions(legacyDisjunct), positions(expressionDisjunct),
                "the two forms of the same defect must audit identically");
    }


    /**
     * The {@code collectDangerous} / mitigation arm of the same re-point: a vacuous-on-empty
     * membership test and its closing guard authored in expression form derive
     * {@code mitigated == true} exactly as the legacy form does. The {@code is_numeric} rider only
     * forces the Check onto the {@code CheckConditionExpression} path.
     */
    @Test
    void expressionFormMembershipDerivesMitigationIdenticallyToItsLegacyForm()
    {
        String operations = """
                Operations:
                - id: "$ta_epoch"
                  operator: "distinct"
                  domain: "TA"
                  name: "EPOCH"
                """;
        String legacy = operations + """
                Check:
                  all:
                  - name: "TA.EPOCH"
                    operator: "var_exists"
                  - any:
                    - name: "DM.DTHFL"
                      operator: "var_not_exists"
                    - name: "DM.AGE"
                      operator: "matches_regex"
                      value: "^-?(0|[1-9]\\\\d*)(\\\\.\\\\d+)?$"
                      value_is_literal: true
                  - name: "EPOCH"
                    operator: "is_not_contained_by"
                    value: "$ta_epoch"
                """;
        String expression = operations
                + """
                        Check:
                          expression: 'var_exists(TA.EPOCH) and (var_not_exists(DM.DTHFL) or is_numeric(DM.AGE))
                            and EPOCH not in $ta_epoch'
                        """;
        assertEquals(Map.of("DM", true), mitigations(expression),
                "the TA guard and the vacuous $ta_epoch membership inside the expression must"
                        + " derive mitigation — a miss means collectDangerous skipped the form");
        assertEquals(mitigations(legacy), mitigations(expression),
                "the two forms of the same Check must derive identical mitigation");
    }


    /**
     * The fail-loud half of the {@code Fix #256} arm: a native-only expression — non-canonical
     * {@code between(char(X), char("B"), char("Z"))} bounds have no operator-leaf surface even in
     * conversion mode — must throw rather than be skipped, so the first such authored rule reds
     * this lint instead of silently leaving its population.
     */
    @Test
    void aNativeOnlyExpressionCheckFailsLoudRatherThanBeingSkipped()
    {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> positions("""
                Check:
                  expression: 'var_exists(DM.DTHFL) and between(char(DM.AGE), char("B"), char("Z"))'
                """));
        assertTrue(ex.getMessage().contains("PLAN-rules-src-expression-flip"),
                "the failure must name the flip plan: " + ex.getMessage());
    }


    /** The {@code dataset -> position} map of one synthetic rule body. */
    private static Map<String, GuardPositionAudit.Position> positions(String body)
    {
        Map<String, GuardPositionAudit.Position> byDataset = new LinkedHashMap<>();
        for (GuardPositionAudit.PresenceLeaf leaf : GuardPositionAudit.presenceLeaves(parse("""
                Core:
                  Id: "SYNTHETIC-0001"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                """ + body)))
        {
            byDataset.put(leaf.dataset(), leaf.position());
        }
        return byDataset;
    }
}
