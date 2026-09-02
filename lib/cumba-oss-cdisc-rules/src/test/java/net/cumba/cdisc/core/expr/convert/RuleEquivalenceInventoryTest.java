package net.cumba.cdisc.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.cumba.cdisc.core.expr.convert.RuleEquivalenceInventory.Pair;
import net.cumba.cdisc.core.expr.convert.RuleEquivalenceInventory.Ref;
import net.cumba.cdisc.core.expr.convert.RuleEquivalenceInventory.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Java tool and the Python generator
 * ({@code documentation/rule-inventory/generate-inventory.py}) are independent implementations of
 * the same specification. This test asserts <strong>semantic agreement</strong> — same pairs, same
 * buckets — against the committed {@code pairs-index.tsv}, rather than byte-identical output, which
 * would force one implementation to copy the other's incidental formatting.
 */
class RuleEquivalenceInventoryTest
{

    /** Source family → the origins that make a pair belong to that leg. */
    private static final Map<String, Set<String>> LEG_ORIGINS = Map.of("PMDA-FDA",
            Set.of("FDA Validator Rules"), "FDA-CDISC", Set.of("SDTM and SDTMIG Conformance Rules"),
            "CORE-CDISC", Set.of("SDTM and SDTMIG Conformance Rules", "ADaM Conformance Rules",
                    "SEND Conformance Rules"));

    private static final Map<String, String> LEG_SOURCE_FAMILY = Map.of("PMDA-FDA", "PMDA",
            "FDA-CDISC", "FDA", "CORE-CDISC", "CORE");

    private static Path basedir;

    private static List<Rule> rules;

    private static List<Pair> pairs;

    @BeforeAll
    static void loadCorpus() throws IOException
    {
        basedir = Path.of(System.getProperty("projectBasedir"));
        rules = RuleEquivalenceInventory.load(basedir.resolve("rules-src").resolve("checks"));
        Map<Ref, List<Rule>> owner = RuleEquivalenceInventory.ownerIndex(rules);
        pairs = RuleEquivalenceInventory.pairs(rules, owner);
    }


    /** Which leg a pair belongs to, or {@code null} if it is not one of the three. */
    private static String legOf(Pair pair)
    {
        for (Map.Entry<String, Set<String>> entry : LEG_ORIGINS.entrySet())
        {
            if (LEG_SOURCE_FAMILY.get(entry.getKey()).equals(pair.source().family())
                    && entry.getValue().contains(pair.via().origin()))
            {
                return entry.getKey();
            }
        }
        return null;
    }


    /** leg → bucket → count, as committed by the Python generator. */
    private static Map<String, Map<String, Integer>> expectedFromTsv() throws IOException
    {
        Path tsv = basedir.resolve("documentation").resolve("rule-inventory")
                .resolve("pairs-index.tsv");
        Map<String, Map<String, Integer>> expected = new TreeMap<>();
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split("\t"));
        int legColumn = header.indexOf("leg");
        int bucketColumn = header.indexOf("bucket");
        for (String line : lines.subList(1, lines.size()))
        {
            String[] cells = line.split("\t", -1);
            expected.computeIfAbsent(cells[legColumn], _ -> new TreeMap<>())
                    .merge(cells[bucketColumn], 1, Integer::sum);
        }
        return expected;
    }


    @Test
    void bucketCountsAgreeWithTheCommittedInventory() throws IOException
    {
        Map<String, Map<String, Integer>> actual = new TreeMap<>();
        for (Pair pair : pairs)
        {
            String leg = legOf(pair);
            if (leg == null)
            {
                continue;
            }
            actual.computeIfAbsent(leg, _ -> new TreeMap<>()).merge(
                    RuleEquivalenceInventory.bucket(pair.source(), pair.target()), 1, Integer::sum);
        }
        assertEquals(expectedFromTsv(), actual,
                "Java bucketing must agree with the committed pairs-index.tsv");
    }


    /** The committed TSV names the exact source/target of every pair; the graph must match. */
    @Test
    void everyCommittedPairIsProducedByTheJavaGraph() throws IOException
    {
        Path tsv = basedir.resolve("documentation").resolve("rule-inventory")
                .resolve("pairs-index.tsv");
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split("\t"));
        int sourceColumn = header.indexOf("source_rule");
        int targetColumn = header.indexOf("target_rule");

        Map<String, Integer> committed = new TreeMap<>();
        for (String line : lines.subList(1, lines.size()))
        {
            String[] cells = line.split("\t", -1);
            committed.merge(cells[sourceColumn] + "->" + cells[targetColumn], 1, Integer::sum);
        }

        Map<String, Integer> produced = new TreeMap<>();
        for (Pair pair : pairs)
        {
            if (legOf(pair) != null)
            {
                produced.merge(pair.source().id() + "->" + pair.target().id(), 1, Integer::sum);
            }
        }
        assertEquals(committed, produced);
    }


    /** D7: a rule citing several targets is compared against every one of them, never split. */
    @Test
    void multiReferenceRulesArePairedAgainstEveryTarget()
    {
        List<Pair> fdaSd0072 = pairs.stream().filter(p -> "FDA-SD0072".equals(p.source().id()))
                .toList();
        assertTrue(fdaSd0072.size() > 1, "FDA-SD0072 cites several CG rules");
        assertTrue(fdaSd0072.stream().allMatch(p -> p.sourceRefCount() > 1));
    }


    /** A multi-owner node yields a pair per owner — never just the one that already agrees. */
    @Test
    void multiOwnerNodesYieldAPairPerOwner()
    {
        Map<Ref, List<Rule>> owner = RuleEquivalenceInventory.ownerIndex(rules);
        List<Ref> multiOwner = owner.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey).toList();
        assertFalse(multiOwner.isEmpty(), "CG0014 and SEND12 are owned by several rules");
        for (Ref ref : multiOwner)
        {
            List<Pair> viaRef = pairs.stream().filter(p -> p.via().equals(ref)).toList();
            for (Pair pair : viaRef)
            {
                assertEquals(owner.get(ref).size(), pair.ownerCount());
            }
        }
    }


    /** CORE never identifies itself, so nothing can point at a CORE rule. */
    @Test
    void coreHasNoSelfOrigin()
    {
        for (Rule rule : rules)
        {
            if ("CORE".equals(rule.family()))
            {
                assertTrue(RuleEquivalenceInventory.selfReferences(rule).isEmpty());
            }
        }
    }


    /** CDISC is the root of the provenance graph. */
    @Test
    void cdiscRulesHaveNoForeignReferences()
    {
        for (Rule rule : rules)
        {
            if ("CDISC".equals(rule.family()))
            {
                assertTrue(RuleEquivalenceInventory.foreignReferences(rule).isEmpty(),
                        rule.id() + " should be a provenance root");
            }
        }
    }

    // ------------------------------------------------------------------ registry


    @Test
    void classesShareAnL1AndRelationsDoNot()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        Map<String, Rule> byId = new LinkedHashMap<>();
        rules.forEach(r -> byId.put(r.id(), r));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) registry.get("classes");
        assertFalse(classes.isEmpty());
        for (Map<String, Object> record : classes)
        {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) record.get("members");
            List<String> prints = new ArrayList<>();
            members.forEach(id -> prints.add(RuleFingerprint.l1(byId.get(id).node(), false)));
            assertEquals(1, prints.stream().distinct().count(),
                    record.get("id") + ": a class must share one L1");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) registry.get("relations");
        for (Map<String, Object> record : relations)
        {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) record.get("members");
            String first = RuleFingerprint.l1(byId.get(members.get(0)).node(), false);
            String second = RuleFingerprint.l1(byId.get(members.get(1)).node(), false);
            assertFalse(first.equals(second),
                    record.get("id") + ": a relation must NOT share an L1");
        }
    }


    /**
     * PMDA-SD0007 and FDA-SD0007 each faithfully implement their own sheet (different grouping
     * keys), so they are a sanctioned <em>relation</em>, never a class. Recording them as a class
     * would key them on an L1 they do not share and the drift gate would fire on a legitimate
     * divergence.
     */
    @Test
    void aSanctionedLogicDivergenceIsARelationNotAClass()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) registry.get("relations");
        boolean found = relations.stream().anyMatch(r ->
        {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) r.get("members");
            return members.contains("PMDA-SD0007") && members.contains("FDA-SD0007");
        });
        assertTrue(found, "PMDA-SD0007 <-> FDA-SD0007 must be a relation");
    }


    /** The discovered links the reference graph never recorded outnumber the ones it did. */
    @Test
    void discoveredClassesAreRecordedDistinctlyFromReferencedOnes()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) registry.get("classes");
        long discovered = classes.stream().filter(c -> "discovered".equals(c.get("origin")))
                .count();
        long referenced = classes.stream().filter(c -> "reference".equals(c.get("origin"))).count();
        assertTrue(discovered > 0, "the graph misses cross-family L1 twins");
        assertTrue(referenced > 0);
    }


    /**
     * Phase 6a. The reference graph is blind to the whole {@code PMDA-AD} family — all 370 of those
     * rules carry zero foreign references — yet their {@code CDISC-AD} twins exist and share a
     * byte-identical canonical L1. Those classes can only be found by L1 identity, so they must be
     * recorded with {@code origin: discovered}, never silently merged with referenced ones.
     */
    @Test
    void pmdaAdTwinsAreFoundOnlyByDiscovery()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) registry.get("classes");
        List<Map<String, Object>> withPmdaAd = classes.stream().filter(c ->
        {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) c.get("members");
            return members.stream().anyMatch(m -> m.startsWith("PMDA-AD"));
        }).toList();
        assertFalse(withPmdaAd.isEmpty());
        long discovered = withPmdaAd.stream().filter(c -> "discovered".equals(c.get("origin")))
                .count();
        assertTrue(discovered > withPmdaAd.size() / 2,
                "most PMDA-AD equivalences are invisible to the reference graph");
    }


    /**
     * <strong>D36 — the replacement for
     * {@code everyClassStartsPendingWithNoSanctionedLayers}.</strong>
     *
     * <p>
     * The old assertion pinned {@code verdict: "pending"} and {@code sanctioned_layers: []} as
     * literals, which is precisely why a hand-authored verdict could not survive a regeneration. It
     * is <em>relaxed</em>, not deleted: an emptied ratchet proves nothing, and this is still the
     * only thing that would catch a verdict accidentally hard-coded into the generator. The
     * contract it now pins is <strong>"pending unless the side-car supplies a verdict"</strong>,
     * and it takes both halves of that:
     *
     * <ol>
     * <li>with an <em>empty</em> overlay every class is {@code pending} / {@code []} and every
     * relation is {@code pending} — so a hard-coded {@code aligned} still fails here;</li>
     * <li>with an overlay pointed at one real class, <em>that class and only that class</em> moves
     * — so deleting the merge (reverting to the literals) also fails here.</li>
     * </ol>
     *
     * <p>
     * ⚠ Half (2) is the neuter-and-watch half. <strong>Measured 2026-08-08</strong>: replacing both
     * overlay lookups in {@code RuleEquivalenceInventory.build} with the old {@code null} literal
     * fails <em>this test and only this test</em> — {@code Tests run: 45, Failures: 1}, "the
     * side-car's verdict must reach the registry ==&gt; expected: &lt;sanctioned&gt; but was:
     * &lt;pending&gt;". In particular
     * {@link #theSideCarKeyIsTheMemberSetSoAMembershipChangeRevertsTheVerdict()} stays green under
     * the neuter, because a neutered merge and a genuinely unmatched key produce the same
     * {@code applied=0 / unmatched=1} report. That is why the two tests are separate and why this
     * one, not that one, is the ratchet against a hard-coded verdict.
     */
    @Test
    void everyClassIsPendingUnlessTheSideCarSuppliesAVerdict()
    {
        Map<String, Object> generated = RuleEquivalenceInventory.build(rules,
                RuleVerdictOverlay.empty());
        for (Map<String, Object> record : section(generated, "classes"))
        {
            assertEquals("pending", record.get("verdict"),
                    record.get("id") + " must start pending");
            assertEquals(List.of(), record.get("sanctioned_layers"),
                    String.valueOf(record.get("id")));
        }
        for (Map<String, Object> record : section(generated, "relations"))
        {
            assertEquals("pending", record.get("verdict"),
                    record.get("id") + " must start pending");
        }

        // ...unless the side-car says otherwise, for exactly one member set.
        @SuppressWarnings("unchecked")
        List<String> target = (List<String>) section(generated, "classes").get(0).get("members");
        RuleVerdictOverlay overlay = RuleVerdictOverlay.of(Map.of(RuleVerdictOverlay.key(target),
                new RuleVerdictOverlay.Verdict("sanctioned", List.of("L3"))));
        Map<String, Object> merged = RuleEquivalenceInventory.build(rules, overlay);

        List<String> promoted = new ArrayList<>();
        for (Map<String, Object> record : section(merged, "classes"))
        {
            if (record.get("members").equals(target))
            {
                promoted.add(String.valueOf(record.get("id")));
                assertEquals("sanctioned", record.get("verdict"),
                        "the side-car's verdict must reach the registry");
                assertEquals(List.of("L3"), record.get("sanctioned_layers"));
            }
            else
            {
                assertEquals("pending", record.get("verdict"),
                        record.get("id") + " was not named by the side-car");
                assertEquals(List.of(), record.get("sanctioned_layers"));
            }
        }
        assertEquals(1, promoted.size(), "exactly one class carries the member set " + target);

        Map<String, Object> report = overlayReport(merged);
        assertEquals(1, report.get("entries"));
        assertEquals(1, report.get("applied"));
        assertEquals(List.of(), report.get("unmatched"),
                "the key matched, so nothing is unmatched");
    }


    /**
     * ⚑ <strong>The decision D36 has to state out loud.</strong> The side-car is keyed on the
     * <em>member rule-id set</em>, so a class that gains or loses one rule no longer matches its
     * authored verdict and <strong>silently reverts to {@code pending}</strong>.
     *
     * <p>
     * That is the correct default — the judgement was made about a specific set of rules, and a set
     * that moved has not been judged — but "silently" is exactly the failure mode that produced the
     * vacuous assertions in the first place. So the revert is <em>visible</em>: the key is counted
     * as <strong>unmatched</strong>, listed in the registry's own {@code verdict_overlay} section
     * with the verdict it was carrying, printed by the generator, and asserted to be zero over the
     * committed side-car by {@code RuleEquivalenceDriftTest}.
     *
     * <p>
     * Deliberately built by <em>dropping a member from a real class's key</em> rather than by
     * inventing rule ids: an entirely fictional key would also be unmatched, but it would not prove
     * that a near-miss — the shape this will actually take when a corpus lane splits a rule — is
     * caught rather than fuzzily re-attached to the class it nearly names.
     */
    @Test
    void theSideCarKeyIsTheMemberSetSoAMembershipChangeRevertsTheVerdict()
    {
        Map<String, Object> generated = RuleEquivalenceInventory.build(rules,
                RuleVerdictOverlay.empty());
        Map<String, Object> biggest = section(generated, "classes").stream()
                .max(Comparator.comparingInt(c -> ((List<?>) c.get("members")).size()))
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) biggest.get("members");
        assertTrue(members.size() >= 3, "need a class with a member to spare: " + members);

        // The judgement was authored for the class as it was; the corpus has since lost a member.
        List<String> staleKey = RuleVerdictOverlay.key(members.subList(0, members.size() - 1));
        RuleVerdictOverlay overlay = RuleVerdictOverlay.of(
                Map.of(staleKey, new RuleVerdictOverlay.Verdict("aligned", List.of("L2", "L4"))));
        Map<String, Object> merged = RuleEquivalenceInventory.build(rules, overlay);

        for (Map<String, Object> record : section(merged, "classes"))
        {
            assertEquals("pending", record.get("verdict"),
                    record.get("id") + ": a near-miss key must not re-attach to a neighbouring"
                            + " class — it must revert to pending");
        }

        Map<String, Object> report = overlayReport(merged);
        assertEquals(1, report.get("entries"));
        assertEquals(0, report.get("applied"));
        assertEquals(
                List.of(new LinkedHashMap<>(Map.of("members", staleKey, "verdict", "aligned"))),
                report.get("unmatched"),
                "an authored verdict that matched nothing must be reported, never dropped");
    }


    /**
     * A relation takes a verdict through the same channel, from its own (shorter) vocabulary and
     * with no {@code sanctioned_layers} — it shares no L1, so there is nothing to sanction against.
     * Keyed on the member set exactly like a class: a relation's {@code members} are emitted
     * source-first, so the key normalisation is what makes the side-car independent of that order.
     */
    @Test
    void aRelationTakesAVerdictFromItsOwnVocabulary()
    {
        Map<String, Object> generated = RuleEquivalenceInventory.build(rules,
                RuleVerdictOverlay.empty());
        @SuppressWarnings("unchecked")
        List<String> target = (List<String>) section(generated, "relations").get(0).get("members");
        // Deliberately reversed: the file may list the members in any order.
        List<String> reversed = new ArrayList<>(target);
        Collections.reverse(reversed);
        RuleVerdictOverlay overlay = RuleVerdictOverlay.of(Map.of(RuleVerdictOverlay.key(reversed),
                new RuleVerdictOverlay.Verdict("to-align", List.of())));
        Map<String, Object> merged = RuleEquivalenceInventory.build(rules, overlay);

        long promoted = section(merged, "relations").stream()
                .filter(r -> "to-align".equals(r.get("verdict"))).count();
        assertEquals(1, promoted, "exactly the named relation moves");
        assertEquals("to-align", section(merged, "relations").get(0).get("verdict"));
        assertTrue(
                section(merged, "relations").stream()
                        .noneMatch(r -> r.containsKey("sanctioned_layers")),
                "a relation never gains a sanctioned_layers key");
        assertEquals(List.of(), overlayReport(merged).get("unmatched"));
    }


    /**
     * The two verdict vocabularies are not interchangeable, and the loader cannot tell them apart —
     * it does not know whether a member set will land on a class or a relation. The merge does, so
     * the misapplication is caught there, naming the entry.
     *
     * <p>
     * ⚠ Without this, {@code verdict: aligned} on a relation would reach the artifact and only then
     * be caught, one regeneration later, by
     * {@code RuleEquivalenceDriftTest.everyEntryCarriesAVerdictFromTheClosedVocabulary} — and
     * {@code sanctioned_layers} on a relation would be caught by that test's second arm. Failing at
     * the merge means the bad registry is never written at all.
     */
    @Test
    void aVerdictFromTheWrongVocabularyIsRejectedAtTheMerge()
    {
        Map<String, Object> generated = RuleEquivalenceInventory.build(rules,
                RuleVerdictOverlay.empty());
        @SuppressWarnings("unchecked")
        List<String> classMembers = (List<String>) section(generated, "classes").get(0)
                .get("members");
        @SuppressWarnings("unchecked")
        List<String> relationMembers = (List<String>) section(generated, "relations").get(0)
                .get("members");

        // `to-align` is a RELATION verdict; a class may not carry it.
        RuleVerdictOverlay onAClass = RuleVerdictOverlay
                .of(Map.of(RuleVerdictOverlay.key(classMembers),
                        new RuleVerdictOverlay.Verdict("to-align", List.of())));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> RuleEquivalenceInventory.build(rules, onAClass)).getMessage()
                        .contains("RELATION verdict"));

        // `aligned` is a CLASS verdict; a relation has no aligned state.
        RuleVerdictOverlay onARelation = RuleVerdictOverlay
                .of(Map.of(RuleVerdictOverlay.key(relationMembers),
                        new RuleVerdictOverlay.Verdict("aligned", List.of())));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> RuleEquivalenceInventory.build(rules, onARelation)).getMessage()
                        .contains("CLASS verdict"));

        // ...and a relation shares no L1, so it can sanction nothing.
        RuleVerdictOverlay sanctioningARelation = RuleVerdictOverlay
                .of(Map.of(RuleVerdictOverlay.key(relationMembers),
                        new RuleVerdictOverlay.Verdict("sanctioned", List.of("L2"))));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> RuleEquivalenceInventory.build(rules, sanctioningARelation)).getMessage()
                        .contains("no sanctioned_layers"));
    }


    /**
     * The committed side-car exists and is fully consumed. This is the production path — no
     * synthetic overlay — so it also pins that {@code build(rules)} really does read
     * {@link RuleVerdictOverlay#DEFAULT_PATH} rather than an empty overlay.
     */
    @Test
    void theCommittedSideCarIsPresentAndFullyConsumed() throws IOException
    {
        Path sidecar = basedir.resolve(RuleVerdictOverlay.DEFAULT_PATH);
        assertTrue(Files.isRegularFile(sidecar),
                "the D36 verdict channel must not be deleted: " + sidecar);
        Map<String, Object> report = overlayReport(RuleEquivalenceInventory.build(rules));
        assertEquals(RuleVerdictOverlay.load(sidecar).size(), report.get("entries"),
                "build() must read the committed side-car");
        assertEquals(List.of(), report.get("unmatched"),
                "a hand-authored verdict names a member set the corpus no longer has — re-key it");
    }


    /**
     * ⚠ The side-car key space is flat: one member set addresses at most one registry entry. That
     * holds by DATA, not by construction — a class is keyed on its L1 so class member sets are
     * disjoint, but two relations could in principle share a {source, target} pair reached through
     * two different reference nodes. Measured: zero collisions across all classes and relations. If
     * this ever fires, {@code applied} will exceed {@code entries} and one authored verdict will be
     * silently applied twice; split the key space before relaxing it.
     */
    @Test
    void oneMemberSetAddressesAtMostOneRegistryEntry()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules,
                RuleVerdictOverlay.empty());
        Map<List<String>, List<String>> byKey = new LinkedHashMap<>();
        for (String name : List.of("classes", "relations"))
        {
            for (Map<String, Object> record : section(registry, name))
            {
                @SuppressWarnings("unchecked")
                List<String> members = (List<String>) record.get("members");
                byKey.computeIfAbsent(RuleVerdictOverlay.key(members), _ -> new ArrayList<>())
                        .add(String.valueOf(record.get("id")));
            }
        }
        List<String> collisions = byKey.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " -> " + e.getValue()).toList();
        assertEquals(List.of(), collisions, "member sets must address one entry each");
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> registry, String name)
    {
        return (List<Map<String, Object>>) registry.get(name);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> overlayReport(Map<String, Object> registry)
    {
        return (Map<String, Object>) registry.get("verdict_overlay");
    }


    /**
     * The three causes of an unresolved reference are materially different and must never be
     * conflated: a reference into a corpus we do not carry is not a defect, a self-echo carries no
     * provenance, and only the remainder is a genuinely dangling target.
     */
    @Test
    void anomaliesDistinguishTheThreeCausesOfAnUnresolvedReference()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) registry.get("anomalies");
        Map<String, Long> byKind = new TreeMap<>();
        anomalies.forEach(a -> byKind.merge(String.valueOf(a.get("kind")), 1L, Long::sum));

        // ⚠ getOrDefault, not get: byKind is merged over the anomalies that are PRESENT, so at zero
        // the key is absent entirely and get() would return null rather than 0L.
        assertEquals(0L, byKind.getOrDefault("multi-owner-node", 0L),
                "33 nodes are owned by more than one rule (the count is pinned in"
                        + " ownershipByASplitGroupIsNotAnAnomalyButStillPairs), and ALL of them are"
                        + " owned by a split-group base plus its letter siblings, which is expected"
                        + " and not an anomaly — e.g. CG0014/CG0014-B; the six CG granularity"
                        + " splits CG0273/0273-A, CG0275/0275-A … CG0279/0279-A; the FDA+PMDA"
                        + " twins SD0095/SD0095-A, SD1073/SD1073-A, SD1041/SD1041-A on both"
                        + " sides; the FDA SEND splits SD0069/SD0069-A and SD1121/SD1121-A; and"
                        + " SEND12, owned by CDISC-SEND-0012 + CDISC-SEND-0012-A (minted-sibling"
                        + " spellings per Fix #248). The three CORE absence rules contribute none:"
                        + " SELF_ORIGINS maps CORE to the empty set, so a CORE rule never owns the"
                        + " id it cites.");
        assertTrue(byKind.get("origin-not-owned-by-any-family") > 0,
                "FDA Business Rules and TIG Conformance Rules are not owned by any family here");
        assertTrue(byKind.get("dangling-reference") > 0, "FDA cites CG ids no CDISC rule owns");
    }


    /**
     * D5 removed the 219 legacy self-echo references — each named the rule's own {@code Core.Id}
     * under the {@code SEND Conformance Rules} origin and therefore carried no provenance at all.
     * Nothing re-injects them: {@link StandardsLinkageBackfill} iterates only the {@code FDA/} and
     * {@code PMDA/} directories, and {@link OriginalTextBackfill}'s registry has no CORE binding.
     * This asserts the removal stays removed.
     */
    @Test
    void noSelfEchoReferenceSurvives()
    {
        for (Rule rule : rules)
        {
            for (Ref ref : RuleEquivalenceInventory.foreignReferences(rule))
            {
                assertFalse(
                        "SEND Conformance Rules".equals(ref.origin()) && rule.id().equals(ref.id()),
                        rule.id() + " still carries a self-echo reference (D5)");
            }
        }
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) registry.get("anomalies");
        assertTrue(anomalies.stream().noneMatch(a -> "self-echo-reference".equals(a.get("kind"))),
                "no self-echo anomaly may remain after D5");
    }


    /** D6: every Check root is a composite — a bare-leaf root is not a valid upstream Boolean. */
    @Test
    void everyCheckRootIsAComposite()
    {
        for (Rule rule : rules)
        {
            var check = rule.node().get("Check");
            if (check == null || check.isNull())
            {
                continue;
            }
            // Since the flip the D6 root wrapper is gone: the writer collapsed singleton `all:`
            // wrappers, so a one-conjunct rule's dual-form view is a bare leaf root, and a
            // conversion-unlowerable rule keeps its {expression} node. A level-keyed Check
            // (Plan C §3.3, first authored by the phase-5b date-ordering tranche) is a map of
            // per-level conditions; each level's condition is held to the same shape gate. What
            // must still hold is that every root is one of exactly those shapes — a shape outside
            // them means the dual-form lowering emitted something no walker here understands.
            var levels = RuleFingerprint.levels(check);
            java.util.Collection<com.fasterxml.jackson.databind.JsonNode> roots = levels == null
                    ? List.of(check)
                    : levels.values();
            for (var root : roots)
            {
                assertTrue(
                        root.has("all") || root.has("any") || root.has("not")
                                || root.has("operator") || root.has("expression"),
                        rule.id() + ": Check root must be a composite, a leaf, or an expression");
            }
        }
    }


    /**
     * D6: no redundant single-child wrapper survives <em>below</em> the Check root. The root itself
     * keeps its `all: [x]` shape in 917 rules — that is the house form and the only one the
     * upstream schema accepts — so this must not simply compare against
     * {@link RuleFingerprint#simplify}, which collapses the root too.
     */
    @Test
    void noNestedRedundantWrapperSurvives()
    {
        for (Rule rule : rules)
        {
            JsonNode check = rule.node().get("Check");
            if (check == null || check.isNull())
            {
                continue;
            }
            for (String key : List.of("all", "any", "not"))
            {
                JsonNode child = check.get(key);
                if (child != null)
                {
                    assertNoRedundantWrapper(child, rule.id());
                }
            }
        }
    }


    private static void assertNoRedundantWrapper(JsonNode node, String ruleId)
    {
        if (node.isArray())
        {
            node.forEach(child -> assertNoRedundantWrapper(child, ruleId));
            return;
        }
        if (!node.isObject())
        {
            return;
        }
        for (String key : List.of("all", "any"))
        {
            JsonNode child = node.get(key);
            if (node.size() == 1 && child != null && child.isArray() && child.size() == 1)
            {
                throw new AssertionError(ruleId + ": redundant single-child `" + key
                        + "` wrapper survives below the Check root (D6)");
            }
        }
        node.forEach(child -> assertNoRedundantWrapper(child, ruleId));
    }


    /** An origin no family owns is informational, never a dangling target. */
    @Test
    void unownedOriginsAreNotReportedAsDanglingTargets()
    {
        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) registry.get("anomalies");
        anomalies.stream().filter(a -> "dangling-reference".equals(a.get("kind")))
                .forEach(a -> assertFalse(
                        Set.of("FDA Business Rules", "TIG Conformance Rules")
                                .contains(String.valueOf(a.get("origin"))),
                        a.get("rule") + " -> " + a.get("origin")));
    }

    // -------------------------------------------------------------- split groups


    /**
     * The shape of the split-group convention, re-measured rather than assumed. Mirrors the numbers
     * {@code build_split_bases} pins on the Python side; the two implementations of one convention
     * must not drift apart.
     *
     * <p>
     * ⚠ <strong>Never assume a pair.</strong> A group is the base plus <em>all</em> its letter
     * siblings and runs to five parts. Pairs are 92 of 97 groups — dominant, which is exactly what
     * makes the assumption tempting and still wrong.
     * </p>
     */
    @Test
    void aSplitGroupIsTheBasePlusEveryLetterSibling()
    {
        // ⭐ 2026-08-12: the filter accepts BOTH spellings of the one convention — `…0273A`
        // (authority-published) and `…0285-A` (coreJ-minted, the target convention). It used to
        // require "digit + capital letter", which silently excluded every `-A` sibling: the test
        // stayed GREEN while its own name — "every letter sibling" — became false. ⇒ this site was
        // not red and would not have been found by fixing the predicate alone.
        List<String> letterSuffixed = rules
                .stream().map(
                        Rule::id)
                .filter(id -> id.length() >= 3 && id.charAt(id.length() - 1) >= 'A'
                        && id.charAt(id.length() - 1) <= 'Z'
                        && (Character.isDigit(id.charAt(id.length() - 2))
                                || id.charAt(id.length() - 2) == '-'))
                .sorted().toList();
        Map<String, Long> byLetter = new TreeMap<>();
        letterSuffixed.forEach(
                id -> byLetter.merge(String.valueOf(id.charAt(id.length() - 1)), 1L, Long::sum));
        // ⚠ NOTE ON SPELLINGS: Fix #248 (wave 40) migrated every coreJ-MINTED letter sibling to
        // the hyphenated `-<letter>` spelling; the history notes below give each id in TODAY'S
        // spelling. The counts did not move at the migration itself, because the filter above
        // already accepted both spellings.
        // ⚠ These figures moved 108→111 / 106→109 / 97→99 when `Fix #150` and `Fix #151` renamed
        // three ids INTO the digit-anchored shape in the same wave: CDISC-SEND-0012-B → today's
        // CDISC-SEND-0012-A, and FDA-SE2280{A,B}-DRAFT → today's FDA-SE2280-A/-B. The old
        // spellings ended in a letter-adjacent hyphenated letter or in `T`, so none of them
        // satisfied "digit + capital letter" and none was ever counted here.
        // ⚠ They moved again, 111→116 / 109→114 / 99→104, when the wave-9 corpus lane split the
        // five `prefix: 2` rules: CDISC-CG0413-A, CORE-000598-A, FDA-SD0004-A, PMDA-SD0004-A and
        // CDISC-SEND-0001-A each scope `AP--` with `prefix: 4`, because an AP dataset's DOMAIN is
        // four characters. All five are new letter siblings of an existing same-family base, so
        // each adds one id, one base and one group of size 1 — which is why `siblingCounts` moved
        // only in its 1-key (93→98). Re-derived from the corpus, not adjusted by arithmetic.
        // ⚠ 116→118 / 114→116 / 104→106 in wave 22, when the two ADaM date/time imputation-flag
        // CT rules were split: CDISC-AD0039-A (`^.+DF$`) and CDISC-AD0040-A (`^.+[^D]TF$`) carry
        // the IG's abbreviated DTF→DF / TMF→TF fragments (ADaMIG Table 3.1.5.1), which exist only
        // from v1.1, so each side rule ships to adamig-1-{1,2,3} and not 1-0. Both are new letter
        // siblings of an existing same-family base, so each adds one id, one base and one group of
        // size 1 — which is why `siblingCounts` moved only in its 1-key (98→100).
        // ⚠ 118→121 / 116→119 / 106→109 in wave 34, and it is the SAME SHAPE as wave 22 above:
        // the TOXGGRyN split (PLAN-adam-index-suffix-shortening §10.11) authored CDISC-AD0537-A,
        // CDISC-AD0785-A and CDISC-AD0786-A, each carrying the shortened `TOXGGyN` template at
        // y=[10-99] while its base keeps [1-9] — the 8-character cap is LEAF-scoped while the index
        // range is RULE-scoped, so no single range can be right for a rule holding both. Each is a
        // new letter sibling of an existing same-family base and inherits that base's `Standards`
        // block, so each adds one id, one base, one group of size 1 and one co-owned provenance
        // node (CR537 / CR785 / CR786) — which is why `siblingCounts` moves only in its 1-key
        // (100→103) and `multiOwner` moves 22→25.
        // ⭐ 121→130 in wave 37, and it is a NEW shape, not another split: the nine minted
        // completeness siblings (`NEW-H`, Fix #239) are the corpus's first ids using the ruled
        // `-<letter>` spelling, so they enter this census only because the filter above was widened
        // to accept it. ⚠⚠ Before that widening this assertion stayed GREEN at 121 while nine
        // letter siblings existed and went uncounted — the test's own name says "every letter
        // sibling", and it was quietly false. A green test is not evidence of coverage.
        // Re-derived from the corpus, not adjusted by arithmetic.
        // ⭐ 130→132 in the twin-alignment wave TA-1 (D-TA-5b, 2026-08-17): the two minted TI
        // literal-variable siblings CDISC-CG0027-A and CORE-000237-A (the FDA-SD1041-A template —
        // the `--` wildcard cannot resolve TI's IECAT/IESCAT, and the owner ruled the sheet's
        // "ALL" DOES reach TI). Same shape as waves 22/34: each is a new letter sibling of an
        // existing same-family base, so each adds one id, one base and one group of size 1.
        // Re-derived from the corpus, not adjusted by arithmetic.
        // ⭐ 132→136 in the twin-alignment wave TA-6 (review H3, 2026-08-19): the four minted
        // all-dose-columns-absent sisters CDISC-CG0100-A / CORE-000137-A / FDA-SD1247-A /
        // PMDA-SD1247-A — the wave-22/34/TA-1 mechanism again, one new `-A` sibling of an
        // existing same-family base each. Re-derived from the corpus, not adjusted by arithmetic.
        // ⭐ 136→139 under Fix #331 (M2-D24 bucket 3, owner ruling 2026-08-22): the three minted
        // ADaM presence sisters CDISC-AD0810-A / CDISC-AD0815-A / CDISC-AD0820-A ("if USUBJID /
        // SUBJID / SITEID is present, its numeric companion must exist"), one new `-A` sibling of
        // an existing same-family base each. Re-derived from the corpus, not adjusted by
        // arithmetic.
        assertEquals(143, letterSuffixed.size(),
                "ids ending in a capital letter, preceded by a digit (published, `…0273A`) or by a"
                        + " hyphen (coreJ-minted, `…0285-A`)");
        // A 62→65 under Fix #331 (three `-A` sisters); 65→67 on 2026-08-27 —
        // PMDA-AD0647-A / PMDA-AD0649-A split their parents' published row into its
        // presence and value clauses. Re-derived by run.
        assertEquals(Map.of("A", 69L, "B", 58L, "C", 15L, "D", 1L), byLetter);

        Map<String, String> bases = RuleEquivalenceInventory.splitBases(rules);
        // 134→137 under Fix #331; 137→139 on 2026-08-27 — each sister has its
        // same-family base (PMDA-AD0647 / PMDA-AD0649). Re-derived by run.
        // ⚠⚠ THIS POPULATION IS PINNED **FIVE** TIMES IN THIS ONE METHOD. Adding a single
        // `-A` sibling moves every one of them:
        // 1. letterSuffixed.size() — total letter-suffixed ids
        // 2. byLetter — per-letter map ("A" bucket)
        // 3. bases.size() — ids with a same-family base
        // 4. perRoot.size() — split groups
        // 5. siblingCounts — group-size histogram (the 1L bucket)
        // ⛔ Updating them one at a time costs one full gate cycle each: adding two siblings
        // on 2026-08-27 took FIVE red gates, one per pin, because nothing said they were the
        // same population. Change all five together, or the next author repeats it.
        assertEquals(141, bases.size(), "letter-suffixed ids with a same-family base");

        Map<String, Long> perRoot = new TreeMap<>();
        bases.keySet().forEach(id -> perRoot
                .merge(RuleEquivalenceInventory.splitGroupRoot(id, bases), 1L, Long::sum));
        // 120 → 124 in wave TA-6 (review H3, 2026-08-19): the four minted sisters each open a
        // new group of size 1 with their base. Re-derived by run.
        // 124 → 127 under Fix #331 (2026-08-22): the three minted ADaM presence sisters each
        // open a new group of size 1 with their base. Re-derived by run.
        // 127 → 129 on 2026-08-27: PMDA-AD0647-A / PMDA-AD0649-A each open a group of
        // size 1 with their base. Re-derived by run.
        assertEquals(131, perRoot.size(), "split groups");
        Map<Long, Long> siblingCounts = new TreeMap<>();
        perRoot.values().forEach(n -> siblingCounts.merge(n, 1L, Long::sum));
        // ⭐ Wave 37: the 1-key moves 103→112 and NOTHING else moves — the signature of nine new
        // siblings, each of a distinct base, each forming its own group of size 1. Same shape as
        // waves 9, 22 and 34 above. Re-derived from the corpus, not adjusted by arithmetic.
        // ⭐ Wave TA-1 (D-TA-5b): the 1-key moves 112→114 and NOTHING else moves — the signature
        // of two new siblings, each of a distinct base, each forming its own group of size 1.
        // ⭐ Wave TA-6 (review H3, 2026-08-19): the 1-key moves 114→118 and NOTHING else moves
        // — the four minted sisters, each of a distinct base, each its own group of size 1.
        // ⭐ Fix #331 (M2-D24 bucket 3, 2026-08-22): the 1-key moves 118→121 and NOTHING else
        // moves — the three minted sisters, each of a distinct base, each its own group of size 1.
        assertEquals(Map.of(1L, 125L, 2L, 3L, 3L, 2L, 4L, 1L), siblingCounts,
                "a group runs to five parts (base + 4 siblings); never assume a pair");
    }


    /**
     * ⚠⚠ The negative test the whole predicate hangs on. {@code PMDA-AD0282A} has <em>no</em>
     * same-family base, and a fallback that matched the numeric part across families would ground
     * it against {@code CDISC-AD0282} — a different family's rule, i.e. exactly the false grounding
     * this inventory exists to prevent.
     *
     * <p>
     * The two base-less ids are <strong>not</strong> interchangeable as evidence:
     * {@code CDISC-AD0282} exists, so only {@code AD0282A} demonstrates the trap live, while
     * {@code AD1012} appears in no other family and would make the fallback look safe. This test
     * therefore asserts the tempting cross-family match is <em>available</em> before asserting it
     * is not taken — a bare "resolves to no base" would pass just as well against a corpus in which
     * the trap could not spring.
     * </p>
     */
    @Test
    void theSplitPredicateNeverFallsBackToTheNumericPartAcrossFamilies()
    {
        Set<String> ids = rules.stream().map(Rule::id).collect(Collectors.toSet());
        Map<String, String> bases = RuleEquivalenceInventory.splitBases(rules);

        // Positive control: the cross-family match a numeric fallback would make really is there.
        assertTrue(ids.contains("PMDA-AD0282A"), "PMDA-AD0282A must be in the corpus");
        assertTrue(ids.contains("CDISC-AD0282"),
                "CDISC-AD0282 must exist, or this test cannot spring the trap it guards");
        assertFalse(ids.contains("PMDA-AD0282"), "PMDA-AD0282A has no same-family base");

        // ...and it is not taken: no base, and the group root is the rule itself.
        assertFalse(bases.containsKey("PMDA-AD0282A"),
                "PMDA-AD0282A must resolve to no base — never to CDISC-AD0282");
        assertEquals("PMDA-AD0282A", RuleEquivalenceInventory.splitGroupRoot("PMDA-AD0282A", bases),
                "a base-less letter-suffixed id is its own group; that is not an error state");

        // The second base-less id. AD1012 exists in no other family, which is why it cannot stand
        // in for AD0282A above.
        assertTrue(ids.contains("PMDA-AD1012A"));
        assertFalse(bases.containsKey("PMDA-AD1012A"));
        assertEquals(List.of("PMDA-AD1012A"),
                ids.stream().filter(id -> id.endsWith("AD1012") || id.endsWith("AD1012A")).sorted()
                        .toList(),
                "AD1012 appears in no other family, so it cannot demonstrate the trap");

        // No base ever crosses a family boundary, for any sibling.
        Map<String, String> byId = new LinkedHashMap<>();
        rules.forEach(r -> byId.put(r.id(), r.family()));
        bases.forEach((id, base) -> assertEquals(byId.get(id), byId.get(base),
                id + " -> " + base + " crosses a family boundary"));
    }


    /**
     * The Python generator ({@code generate-inventory.py}) stopped reporting a node owned by a
     * split base plus its siblings; this asserts the Java tool agrees. Two implementations of one
     * convention disagreeing is the class of defect this inventory exists to catch, so the
     * agreement is the point, not the count.
     *
     * <p>
     * ⚠ Pairing is deliberately unchanged — {@link RuleEquivalenceInventory#pairs} still emits one
     * pair per owner, because each sibling is a separate alignment target.
     * </p>
     */
    @Test
    void ownershipByASplitGroupIsNotAnAnomalyButStillPairs()
    {
        Map<Ref, List<Rule>> owner = RuleEquivalenceInventory.ownerIndex(rules);
        List<Ref> multiOwner = owner.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey).toList();
        // (ids below are given in TODAY'S spelling — the coreJ-minted siblings migrated to the
        // hyphenated `-<letter>` form in wave 40, Fix #248.)
        // 16 → 20 in wave 9: four of the five new `prefix: 4` AP siblings inherit their
        // base's Standards block verbatim (the split-group convention — a sibling
        // inherits its grounding), so each now co-owns its base's provenance node.
        // CDISC-CG0413-A, FDA-SD0004-A, PMDA-SD0004-A and CDISC-SEND-0001-A are the four;
        // CORE-000598-A adds none, because CORE has no SELF_ORIGINS and therefore owns
        // no node at all.
        // 20 → 22 in wave 22: CDISC-AD0039-A and CDISC-AD0040-A each inherit their base's
        // `Standards` block verbatim (the split-group convention — a sibling inherits its
        // grounding), so each co-owns its base's provenance node:
        // ("ADaM Conformance Rules", "CR39") → [CDISC-AD0039, CDISC-AD0039-A] and
        // ("ADaM Conformance Rules", "CR40") → [CDISC-AD0040, CDISC-AD0040-A].
        // 22 → 25 in wave 34, same mechanism: CDISC-AD0537-A / AD0785-A / AD0786-A each inherit
        // their base's `Standards` block verbatim, so each co-owns its base's provenance node —
        // ("ADaM Conformance Rules", "CR537"/"CR785"/"CR786") → [<base>, <base>-A].
        // 25 → 32 in wave 37 (lane A, Fix #239), same mechanism a third time: the seven non-CORE
        // minted completeness siblings (the `-A` suffix, set 4) each copy their parent's
        // `Standards` block verbatim, so each co-owns its parent's provenance node. SELF_ORIGINS
        // maps CORE to the empty set, which is why only the seven non-CORE siblings count.
        // Re-derived by run and reported by the lane; applied by the coordinator, and the measured
        // value matched the lane's prediction exactly.
        // 32 → 33 in wave 40 (lane A, Fix #248): the four PMDA-AD0252 splits' fabricated
        // `AD0252A`…`D` citations — ids no sheet publishes — were rewritten to the parent's real
        // row per the minted-sibling convention (§3 of PLAN-minted-sibling-id-suffix.md), so all
        // four now co-own ("PMDA Validation Rules", "AD0252") with their base: one new
        // five-owner node. Re-derived by run.
        // 33 → 34 in the twin-alignment wave TA-1 (D-TA-5b, 2026-08-17), the wave-22/34 mechanism
        // again: the minted TI sibling CDISC-CG0027-A inherits its parent's `Standards` block
        // verbatim (the minted-sibling citation convention), so it co-owns
        // ("SDTM and SDTMIG Conformance Rules", "CG0027") with CDISC-CG0027. Its CORE twin
        // CORE-000237-A adds none — SELF_ORIGINS maps CORE to the empty set. Re-derived by run.
        // 34 → 37 in the twin-alignment wave TA-6 (review H3, 2026-08-19), the same mechanism:
        // the minted sisters inherit their parents' Standards blocks verbatim, so CDISC-CG0100-A
        // co-owns ("SDTM and SDTMIG Conformance Rules", "CG0100"), FDA-SD1247-A co-owns the
        // ("FDA Validator Rules", "SD1247") node and PMDA-SD1247-A the ("PMDA Validation Rules",
        // "SD1247") node with their bases. CORE-000137-A's citations are already co-owned CDISC
        // rows. Re-derived by run.
        // 37 → 40 under Fix #331 (M2-D24 bucket 3, 2026-08-22), the same mechanism: the minted
        // presence sisters CDISC-AD0810-A / -0815-A / -0820-A inherit their parents' Standards
        // blocks verbatim, so each co-owns its ("ADaM Conformance Rules", "CR810" / "CR815" /
        // "CR820") node with its base. Re-derived by run.
        // ⭐ 40 → 43 on 2026-08-26 (plans/PLAN-source-proposed-block.md ruling I), by a THIRD
        // mechanism — co-ownership by CITATION rather than by name. The 31 minted-band rules that
        // operationalise a generic published row had their Rule_Identifier repaired to cite it, so
        // ("FDA Validator Rules", "SD0002"), ("PMDA Validation Rules", "SD0002") and
        // ("SDTM and SDTMIG Conformance Rules", "CG0014") each became multi-owned. ⚑ +3, not +4:
        // ("SEND Conformance Rules", "SEND12") was ALREADY multi-owned by CDISC-SEND-0012 and
        // -0012-A, so the six CDISC-SEND-943x rules joined a node that was already counted.
        // Re-derived by run.
        // ⚑ 43 -> 45, 2026-08-27: PMDA-AD0647-A / PMDA-AD0649-A are minted SIBLINGS splitting their
        // parents' published row into its two clauses (presence + value), so each adds one
        // legitimately co-owned node. A split group owning a node is not an anomaly (this
        // test's own name), but the pin must move with the population.
        // 45 -> 47 on 2026-08-27: CDISC-SEND-0352-A / FDA-SE2251-A each co-own their
        // parent's nodes. ⚠ This is a SIXTH pin on the split population, in a
        // DIFFERENT method from the five listed in aSplitGroupIsTheBasePlusEveryLetterSibling.
        // Search the whole file, not one method, when the split population moves.
        assertEquals(47, multiOwner.size(), "47 nodes are owned by more than one rule");

        Map<String, Object> registry = RuleEquivalenceInventory.build(rules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) registry.get("anomalies");
        List<String> reported = anomalies.stream()
                .filter(a -> "multi-owner-node".equals(a.get("kind")))
                .map(a -> String.valueOf(a.get("id"))).sorted().toList();
        assertEquals(List.of(), reported,
                "no multi-owner node survives suppression: every one is a split-group base plus its"
                        + " letter siblings, in EITHER spelling — `…0273A` (authority-published) or"
                        + " `…0285-A` (coreJ-minted, the target convention since 2026-08-12; every"
                        + " minted sibling spells `-A` since Fix #248)."
                        + " ⚠⚠ THIS MESSAGE PREVIOUSLY SAID the pin was \"0 by DATA, not by"
                        + " a widened predicate\" and that a newly-authored hyphenated id \"must go"
                        + " back to 1, not be relaxed\". That instruction EXPIRED: it was written"
                        + " when a hyphenated letter meant a transcription artefact (Fix #150"
                        + " removed the 2026-era CDISC-SEND-0012-A/-B pair), and the owner has"
                        + " since ruled the hyphenated form the CORRECT one — today's"
                        + " CDISC-SEND-0012-A is the DELIBERATE re-mint of that spelling (see"
                        + " documentation/HISTORICAL-IDS.md). The predicate was widened"
                        + " deliberately, on that ruling — do NOT re-narrow it, and do NOT"
                        + " re-baseline this pin to hide a sibling.");

        // Suppression must not have touched pairing.
        for (Ref ref : multiOwner)
        {
            List<Pair> viaRef = pairs.stream().filter(p -> p.via().equals(ref)).toList();
            viaRef.forEach(p -> assertEquals(owner.get(ref).size(), p.ownerCount()));
        }
        Ref cg0273 = multiOwner.stream().filter(r -> "CG0273".equals(r.id())).findFirst()
                .orElseThrow();
        assertEquals(2, owner.get(cg0273).size(), "CG0273 is owned by CDISC-CG0273 + CG0273A");
        assertTrue(pairs.stream().anyMatch(p -> p.via().equals(cg0273)),
                "a suppressed anomaly still yields its pairs");
    }


    @Test
    void guardOnlyRequiresTargetingToAgreeToo()
    {
        Map<String, Rule> byId = new LinkedHashMap<>();
        rules.forEach(r -> byId.put(r.id(), r));
        Rule pmda = byId.get("PMDA-SD1085");
        Rule fda = byId.get("FDA-SD1085");
        assertTrue(RuleEquivalenceInventory.guardEquivalent(pmda, fda));
        assertFalse(RuleEquivalenceInventory.guardOnly(pmda, fda),
                "SD1085's Scope differs (PMDA gates on Classes + Exclude DO, FDA on Domains ALL),"
                        + " so guards are not the only gap. ⚠ Until D-TA-2 (2026-08-17) the stated"
                        + " reason was the Match_Datasets Join_Type; PMDA's `left` is gone and the"
                        + " assertion now rests on the Scope half of l2 alone");
    }
}
