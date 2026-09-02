package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Offline developer tool — builds the cross-family rule-equivalence registry described by
 * {@code plans/PLAN-rule-inventory.md} and writes it to
 * {@code documentation/rule-inventory/rule-equivalence.yaml}.
 *
 * <p>
 * The central distinction, and the one that is easy to get wrong:
 * </p>
 *
 * <ul>
 * <li>A <strong>class</strong> is a set of rules that <em>share an L1</em> — the same executable
 * check. Members may still differ in the layers the class declares as sanctioned.</li>
 * <li>A <strong>relation</strong> links rules whose L1 <em>differs</em>. A reference is only a link
 * to a <em>related</em> rule in another family (D7); the target may be equal, similar or different.
 * A sanctioned logic divergence — PMDA-SD0007 and FDA-SD0007 each faithfully implement their own
 * sheet — is a relation, never a class. Modelling it as a class would key it on an L1 the members
 * do not share, and the drift gate would fire on a divergence the sheet authorises.</li>
 * </ul>
 *
 * <p>
 * Provenance lives in {@code Standards[].References[]} ({@code Origin} +
 * {@code Rule_Identifier.Id}, injected by {@link StandardsLinkageBackfill}), never in
 * {@code Source}, whose keys are sheet-faithful and therefore differ per family. The graph is noded
 * on {@code (Origin, Id)} — the six origins do <em>not</em> map one-to-one onto the four families,
 * and the mapping from a node to its owning rule is <strong>not injective</strong> ({@code CG0014},
 * {@code SEND12}).
 * </p>
 *
 * <p>
 * Rules are never split (D7). A rule citing several targets is compared against <em>each</em> of
 * them; one may be exactly equal while the others are merely related.
 * </p>
 *
 * <p>
 * &#9733; <b>Level-keyed Checks (Plan C &#167;3.3).</b> A class is keyed on L1, and L1 is computed
 * <b>per declared level</b> ({@link RuleFingerprint#l1}) — two rules are the same rule iff they
 * declare the same levels and each level's condition matches. {@link #load} lowers <em>every</em>
 * level through {@link DualFormCheckNode#lowerCheckInPlace}, so a multi-level rule reaches the
 * leaf-keyed comparison on the same basis as a single-level one. &#9873; {@code Severity} is
 * <b>not</b> a fingerprint input at any layer (owner ruling {@code O-A}); a severity-divergent twin
 * class is explained by its {@link RuleVerdictOverlay} note, never flagged by a hash.
 * </p>
 */
final class RuleEquivalenceInventory
{

    private static final Path SRC_CHECKS = Path.of("rules-src", "checks");

    private static final Path REGISTRY = Path.of("documentation", "rule-inventory",
            "rule-equivalence.yaml");

    private static final List<String> FAMILIES = List.of("CDISC", "CORE", "FDA", "PMDA");

    /**
     * Origins each family may cite as <em>its own</em> provenance. {@code CORE} has none: it never
     * identifies itself in {@code Standards[]}, it only points outward, so no rule can point at it.
     */
    private static final Map<String, Set<String>> SELF_ORIGINS = Map.of("CDISC",
            Set.of("SDTM and SDTMIG Conformance Rules", "ADaM Conformance Rules",
                    "SEND Conformance Rules"),
            "FDA", Set.of("FDA Validator Rules"), "PMDA", Set.of("PMDA Validation Rules"), "CORE",
            Set.of());

    private static final ObjectMapper YAML_READ = new ObjectMapper(new YAMLFactory());

    private static final ObjectMapper YAML_WRITE = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private RuleEquivalenceInventory()
    {
    }

    /** One rule file: its {@code Core.Id}, its family directory, and its parsed tree. */
    record Rule(String id, String family, JsonNode node)
    {
    }


    /** A node of the provenance graph. */
    record Ref(String origin, String id)
    {
    }


    /** One rule compared against one rule it cites. */
    record Pair(Rule source, Rule target, Ref via, int sourceRefCount, int ownerCount)
    {
    }

    // ------------------------------------------------------------------- loading

    static List<Rule> load(Path checksRoot) throws IOException
    {
        List<Rule> rules = new ArrayList<>();
        List<String> unlowerable = new ArrayList<>();
        for (String family : FAMILIES)
        {
            Path dir = checksRoot.resolve(family);
            if (!Files.isDirectory(dir))
            {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir))
            {
                List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted().toList();
                for (Path file : files)
                {
                    JsonNode node = YAML_READ
                            .readTree(Files.readString(file, StandardCharsets.UTF_8));
                    String id = node.path("Core").path("Id").asText();
                    // Dual-form: an expression Check is lowered to the legacy shape the
                    // fingerprints key on; a conversion-unlowerable Check stays in expression
                    // form (no leaves — L1 canonicalises on the expression node itself).
                    if (!DualFormCheckNode.lowerCheckInPlace(node))
                    {
                        unlowerable.add(id);
                    }
                    rules.add(new Rule(id, family, node));
                }
            }
        }
        if (!unlowerable.isEmpty())
        {
            System.err.println("[unlowerable] " + unlowerable.size()
                    + " rule(s) kept in expression form (no legacy Check view): " + unlowerable);
        }
        return rules;
    }


    static Set<Ref> references(JsonNode rule)
    {
        Set<Ref> refs = new LinkedHashSet<>();
        for (JsonNode organisation : rule.path("Standards"))
        {
            for (JsonNode standard : organisation.path("Standards"))
            {
                for (JsonNode reference : standard.path("References"))
                {
                    refs.add(new Ref(reference.path("Origin").asText(),
                            reference.path("Rule_Identifier").path("Id").asText()));
                }
            }
        }
        return refs;
    }


    static Set<Ref> selfReferences(Rule rule)
    {
        Set<Ref> own = new LinkedHashSet<>();
        for (Ref ref : references(rule.node()))
        {
            if (SELF_ORIGINS.get(rule.family()).contains(ref.origin()))
            {
                own.add(ref);
            }
        }
        return own;
    }


    static Set<Ref> foreignReferences(Rule rule)
    {
        Set<Ref> foreign = new LinkedHashSet<>();
        for (Ref ref : references(rule.node()))
        {
            if (!SELF_ORIGINS.get(rule.family()).contains(ref.origin()))
            {
                foreign.add(ref);
            }
        }
        return foreign;
    }


    /**
     * {@code (Origin, Id)} → the rules that own it. Not injective: {@code CG0014} is owned by both
     * {@code CDISC-CG0014} and {@code CDISC-CG0014-B}, {@code SEND12} by three rules.
     */
    static Map<Ref, List<Rule>> ownerIndex(List<Rule> rules)
    {
        Map<Ref, List<Rule>> owner = new LinkedHashMap<>();
        for (Rule rule : rules)
        {
            for (Ref ref : selfReferences(rule))
            {
                owner.computeIfAbsent(ref, _ -> new ArrayList<>()).add(rule);
            }
        }
        return owner;
    }

    // ---------------------------------------------------------------- split groups


    /**
     * {@code id → the id of its split-group base}, for every letter-suffixed sibling whose base
     * exists <strong>in the same family</strong>.
     *
     * <p>
     * Within ONE family, {@code <BASE>} and {@code <BASE><L>} ({@code L} a single capital
     * {@code A}–{@code Z}) are one <strong>split group</strong>: the sibling was authored by
     * splitting the base's sheet row, so it shares the base's owned provenance node. A node owned
     * by a base plus its siblings is therefore <em>expected</em>, not an anomaly.
     * </p>
     *
     * <p>
     * This mirrors {@code build_split_bases} in
     * {@code documentation/rule-inventory/generate-inventory.py} exactly — the two implementations
     * of one convention must not disagree, which is the class of defect this inventory exists to
     * catch. Measured on {@code rules-src/checks} (CDISC/CORE/FDA/PMDA, 3703 ids):
     * </p>
     *
     * <ul>
     * <li><strong>108</strong> ids end in a digit followed by a single capital letter —
     * {@code A}×35, {@code B}×57, {@code C}×15, {@code D}×1.</li>
     * <li><strong>106</strong> of those have a same-family base, forming <strong>97</strong>
     * groups. Sibling counts per base are 1×92, 2×2, 3×2, 4×1 — so a group runs to
     * <strong>five</strong> parts. <strong>Never assume a pair</strong>, even though pairs are 92
     * of 97 groups; that dominance is exactly what makes the assumption tempting and wrong.</li>
     * <li>⚠⚠ <strong>2 have no same-family base</strong> — {@code PMDA-AD0282A} and
     * {@code PMDA-AD1012A}. They are each their own group; that is <em>not</em> an error
     * state.</li>
     * <li>⚠⚠ The lookup is deliberately <strong>family-local and id-literal</strong>. It never
     * falls back to matching the numeric part across families: {@code CDISC-AD0282} exists, so such
     * a fallback would ground {@code PMDA-AD0282A} against a <em>different family's</em> rule — the
     * exact false grounding this inventory exists to prevent. {@code AD1012} appears in no other
     * family, so only {@code AD0282A} demonstrates the trap live; a test using {@code AD1012} alone
     * would make the fallback look safe.</li>
     * </ul>
     *
     * <p>
     * The predicate is {@code base + one letter}, optionally separated by a hyphen, with <em>no
     * digit anchor</em>, and the base must be a real id of the same family.
     * </p>
     *
     * <p>
     * ⭐ <strong>The hyphenated form was ADDED 2026-08-12, on the owner's ruling that
     * {@code -<letter>} is the target convention for a coreJ-minted sibling</strong> ({@code A}
     * alone stays reserved for a suffix the authority itself publishes, e.g. {@code PMDA-SD2247A}).
     * This paragraph previously said the hyphenated spelling was deliberately excluded and that
     * <em>"extending the predicate to them is a decision for whoever owns the id convention"</em>.
     * ⇒ <strong>That decision has now been taken, and this is it.</strong>
     * </p>
     *
     * <p>
     * ⚠⚠ <strong>The old exclusion was correct when written, which is why it must not simply be
     * deleted.</strong> Its two named near-misses were {@code CDISC-SEND-0012-A} / {@code -B} and
     * {@code FDA-SE2280A-DRAFT} / {@code B-DRAFT} — <em>transcription artefacts</em> that
     * {@code Fix #150} and {@code Fix #151} renamed away. Excluding them was right precisely
     * <em>because</em> a hyphenated letter meant "someone spelled this wrong". The ruling inverts
     * that premise: a hyphenated letter now means "this is a minted sibling, correctly spelled". ⇒
     * the predicate widened because <strong>the world changed, not because it was buggy</strong>.
     * (&#9888; Today's {@code CDISC-SEND-0012-A} is {@code Fix #248}'s deliberate re-mint of that
     * spelling for a <em>different</em> rule — see {@code documentation/HISTORICAL-IDS.md}.)
     * </p>
     *
     * <p>
     * ⚑ {@code FDA-SE2280A-DRAFT}-shaped ids stay out regardless, and by the same guard as before:
     * the last character is {@code T}, so the base would be {@code FDA-SE2280A-DRAF}, which is not
     * an id. <strong>The base-must-exist check is what does the work</strong> — the suffix rule
     * only decides how many characters to try removing.
     * </p>
     */
    static Map<String, String> splitBases(List<Rule> rules)
    {
        Map<String, Set<String>> idsByFamily = new LinkedHashMap<>();
        for (Rule rule : rules)
        {
            idsByFamily.computeIfAbsent(rule.family(), _ -> new LinkedHashSet<>()).add(rule.id());
        }
        Map<String, String> bases = new LinkedHashMap<>();
        for (Rule rule : rules)
        {
            String id = rule.id();
            if (id.length() < 2)
            {
                continue;
            }
            char last = id.charAt(id.length() - 1);
            if (last < 'A' || last > 'Z')
            {
                continue;
            }
            // Two spellings, one convention: `CG0273A` (published by the authority) and
            // `CG0285-A` (coreJ-minted, the target convention since 2026-08-12). Drop the
            // hyphen with the letter so both resolve to the same base.
            int suffixLength = id.length() >= 3 && id.charAt(id.length() - 2) == '-' ? 2 : 1;
            String base = id.substring(0, id.length() - suffixLength);
            if (idsByFamily.getOrDefault(rule.family(), Set.of()).contains(base))
            {
                bases.put(id, base);
            }
        }
        return bases;
    }

    /** Lowest numeric tail that marks a coreJ-minted id rather than a published one. */
    private static final int MINTED_BAND_FLOOR = 9000;

    /**
     * ⭐ The second way a rule can be a split: <b>by citation rather than by name</b>.
     *
     * <p>
     * {@link #splitBases} above reads the id — {@code CG0014-B} is a sibling of {@code CG0014}
     * because it is spelled that way. But {@code plans/PLAN-source-proposed-block.md} ruling I
     * (2026-08-26) established a population of splits that carry <b>no letter suffix at all</b>:
     * <b>31</b> minted-band rules that operationalise a <em>generic</em> published row so it can be
     * checked without the CDISC Library — {@code CDISC-CG9670} splits {@code CG0014}, the six
     * {@code CDISC-SEND-943x} split {@code SEND12}, and the FDA/PMDA {@code 97xx} rules split
     * {@code SD0002}. They own their cited node exactly as a letter sibling does, and a node owned
     * by a base plus its splits is <em>expected</em>, not an anomaly — so the id-literal rule alone
     * reported 4 false anomalies and a multi-owner count 3 too high.
     * </p>
     *
     * <p>
     * <b>Derived, never enumerated.</b> A rule qualifies when its numeric tail reaches
     * {@link #MINTED_BAND_FLOOR} <em>and</em> its own-family citation resolves to an id that
     * <em>another</em> rule owns as its own. Both halves are load-bearing:
     * </p>
     *
     * <ul>
     * <li>⛔ <b>The band restriction is what keeps this from over-widening.</b> Without it, every
     * published rule citing another published rule's row would collapse into that rule's split
     * group and genuine multi-owner anomalies would be masked — the "narrow the population until
     * the red goes away" shape. Only coreJ mints in the {@code 9###} band, so only coreJ's own
     * splits can qualify.</li>
     * <li>⛔ <b>"Another rule owns it" excludes the three published {@code 9999}s.</b>
     * {@code FDA-SD9999} cites {@code SD9999} — its <em>own</em> row — so it owns the node rather
     * than joining someone else's group, and it stays outside this map.</li>
     * </ul>
     *
     * <p>
     * ⚠ <b>Composed with {@link #splitBases} at the anomaly call site, never merged into it</b> -
     * {@code splitBases} is pinned in lockstep with the Python {@code build_split_bases} and must
     * stay id-literal.
     *
     * <p>
     * ⛔⛔ <b>IT DOES HAVE A PYTHON COUNTERPART, and this paragraph used to deny it.</b> It read
     * <i>"the Python side does not build an owner index, so there is no second implementation of
     * this convention to disagree with"</i> — <b>false</b>. {@code generate-inventory.py} builds
     * {@code build_owner_index} and applies the identical suppression over {@code split_bases}. For
     * one review cycle the Java half suppressed citation-splits and the Python half did not:
     * {@code anomalies.tsv} carried <b>4</b> {@code multi-owner-node} rows ({@code CG0014},
     * {@code SEND12}, {@code SD0002} twice) that {@code rule-equivalence.yaml} did not — the exact
     * two-implementations-disagree defect this inventory exists to catch, manufactured by a comment
     * asserting it could not happen. ⇒ the mirror is {@code build_citation_split_bases} in
     * {@code generate-inventory.py}; <b>keep them in lockstep exactly as {@code build_split_bases}
     * and {@link #splitBases} are</b>, and note that NO TEST compares the two artifacts — the
     * lockstep is held by discipline alone.
     * </p>
     */
    static Map<String, String> citationSplitBases(List<Rule> rules)
    {
        Map<Ref, List<Rule>> owner = ownerIndex(rules);
        Map<String, String> bases = new LinkedHashMap<>();
        for (Rule rule : rules)
        {
            if (numericTail(rule.id()) < MINTED_BAND_FLOOR)
            {
                continue;
            }
            for (Ref ref : selfReferences(rule))
            {
                for (Rule candidate : owner.getOrDefault(ref, List.of()))
                {
                    if (!candidate.id().equals(rule.id())
                            && candidate.family().equals(rule.family())
                            && numericTail(candidate.id()) < MINTED_BAND_FLOOR)
                    {
                        bases.put(rule.id(), candidate.id());
                    }
                }
            }
        }
        return bases;
    }


    /**
     * The id's trailing number, reading past a letter or {@code -L} suffix; -1 when it has none.
     */
    static int numericTail(String id)
    {
        int end = id.length();
        if (end >= 3 && id.charAt(end - 2) == '-' && id.charAt(end - 1) >= 'A'
                && id.charAt(end - 1) <= 'Z')
        {
            end -= 2;
        }
        if (end > 0 && id.charAt(end - 1) >= 'A' && id.charAt(end - 1) <= 'Z')
        {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(id.charAt(start - 1)))
        {
            start--;
        }
        return start == end ? -1 : Integer.parseInt(id.substring(start, end));
    }


    /**
     * The base of {@code id}'s split group, following the chain to its root. A chain longer than
     * one hop does not occur today (no {@code XAB} beside {@code XA} beside {@code X}), but
     * resolving iteratively — with a seen-set, so a pathological id cycle cannot hang the tool —
     * keeps every caller agreeing on one root per group.
     */
    static String splitGroupRoot(String id, Map<String, String> splitBases)
    {
        Set<String> seen = new LinkedHashSet<>();
        String current = id;
        while (splitBases.containsKey(current) && seen.add(current))
        {
            current = splitBases.get(current);
        }
        return current;
    }


    /**
     * Every (rule, cited target) pair. A multi-reference rule yields one pair per target — it is
     * never split (D7) — and a multi-owner node yields one pair per owner, because picking the
     * owner whose L1 already matches would bias the counts toward "aligned".
     *
     * <p>
     * Deliberately <strong>not</strong> filtered by {@link #splitBases}: each sibling is a separate
     * alignment target even though the shared ownership is unsurprising. Only the anomaly report
     * uses the split predicate.
     * </p>
     */
    static List<Pair> pairs(List<Rule> rules, Map<Ref, List<Rule>> owner)
    {
        List<Pair> pairs = new ArrayList<>();
        for (Rule rule : rules)
        {
            List<Ref> foreign = new ArrayList<>(foreignReferences(rule));
            for (Ref ref : foreign)
            {
                List<Rule> owners = owner.get(ref);
                if (owners == null)
                {
                    continue;
                }
                for (Rule target : owners)
                {
                    pairs.add(new Pair(rule, target, ref, foreign.size(), owners.size()));
                }
            }
        }
        return pairs;
    }

    // ------------------------------------------------------------------ bucketing


    /** Which layer first separates two rules. Mirrors the plan's bucket names exactly. */
    static String bucket(Rule a, Rule b)
    {
        boolean sameL1 = RuleFingerprint.l1(a.node(), false)
                .equals(RuleFingerprint.l1(b.node(), false));
        boolean sameL2 = RuleFingerprint.l2(a.node()).equals(RuleFingerprint.l2(b.node()));
        boolean sameL3 = RuleFingerprint.l3(a.node()).equals(RuleFingerprint.l3(b.node()));
        boolean sameL4 = RuleFingerprint.l4(a.node()).equals(RuleFingerprint.l4(b.node()));
        if (sameL1 && sameL2 && sameL3 && sameL4)
        {
            return "0-identical";
        }
        if (sameL1 && sameL2 && sameL3)
        {
            return "A1-description";
        }
        if (sameL1 && sameL2)
        {
            return "A2-reporting";
        }
        if (sameL1)
        {
            return "B-targeting";
        }
        return "C-logic";
    }


    /** L1 becomes equal once inert {@code exists} guards are stripped. */
    static boolean guardEquivalent(Rule a, Rule b)
    {
        return RuleFingerprint.l1(a.node(), true).equals(RuleFingerprint.l1(b.node(), true));
    }


    /** Guard-equivalent <em>and</em> nothing about targeting differs — guards are the only gap. */
    static boolean guardOnly(Rule a, Rule b)
    {
        return "C-logic".equals(bucket(a, b)) && guardEquivalent(a, b)
                && RuleFingerprint.l2(a.node()).equals(RuleFingerprint.l2(b.node()));
    }

    // ------------------------------------------------------------------- registry


    /**
     * The registry, with hand-authored verdicts taken from the committed side-car
     * ({@link RuleVerdictOverlay#DEFAULT_PATH}). This is the production shape: {@link #main} and
     * every drift assertion go through it.
     *
     * <p>
     * ⚠ {@link IOException} is wrapped rather than declared so that the dozen existing callers keep
     * their signatures — the side-car is read, never written, and an unreadable one is a hard stop
     * either way.
     */
    static Map<String, Object> build(List<Rule> rules)
    {
        try
        {
            return build(rules, RuleVerdictOverlay.load(RuleVerdictOverlay.DEFAULT_PATH));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /**
     * The registry, with {@code overlay} supplying the hand-authored verdicts.
     *
     * <p>
     * <strong>D36.</strong> {@code verdict} and {@code sanctioned_layers} used to be literals here,
     * which is why a hand-authored verdict could not survive a regeneration and why the alignment
     * drift assertions were vacuous by construction. They are now defaults: {@code pending} /
     * {@code []} <em>unless</em> the side-car supplies a verdict for that entry's exact member set.
     * See {@link RuleVerdictOverlay} for why the key is the member set and never the positional
     * {@code EQ-}/{@code REL-} id.
     */
    static Map<String, Object> build(List<Rule> rules, RuleVerdictOverlay overlay)
    {
        Map<Ref, List<Rule>> owner = ownerIndex(rules);
        List<Pair> pairs = pairs(rules, owner);

        // Classes: rules that SHARE an L1 across families. Discovered links are the ones the
        // reference graph never recorded; there are more of those than referenced ones.
        Map<String, List<Rule>> byL1 = new TreeMap<>();
        for (Rule rule : rules)
        {
            byL1.computeIfAbsent(RuleFingerprint.l1(rule.node(), false), _ -> new ArrayList<>())
                    .add(rule);
        }

        Set<String> referencedPairs = new LinkedHashSet<>();
        for (Pair pair : pairs)
        {
            referencedPairs.add(unorderedKey(pair.source().id(), pair.target().id()));
        }

        // D36: which side-car keys were consumed, so the ones that matched NOTHING can be reported.
        Set<List<String>> usedKeys = new LinkedHashSet<>();
        int applications = 0;

        List<Map<String, Object>> classes = new ArrayList<>();
        int classCounter = 0;
        for (Map.Entry<String, List<Rule>> entry : byL1.entrySet())
        {
            List<Rule> members = entry.getValue();
            if (members.size() < 2)
            {
                continue;
            }
            long families = members.stream().map(Rule::family).distinct().count();
            if (families < 2)
            {
                continue;
            }
            boolean anyReferenced = false;
            for (int i = 0; i < members.size() && !anyReferenced; i++)
            {
                for (int j = i + 1; j < members.size(); j++)
                {
                    if (referencedPairs
                            .contains(unorderedKey(members.get(i).id(), members.get(j).id())))
                    {
                        anyReferenced = true;
                        break;
                    }
                }
            }
            List<String> memberIds = members.stream().map(Rule::id).sorted().toList();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", String.format("EQ-%04d", ++classCounter));
            record.put("kind", "cross-family");
            record.put("origin", anyReferenced ? "reference" : "discovered");
            record.put("members", memberIds);
            record.put("divergence", divergence(members));
            RuleVerdictOverlay.Verdict authored = overlay.find(memberIds).orElse(null);
            if (authored == null)
            {
                record.put("verdict", "pending");
                record.put("sanctioned_layers", List.of());
            }
            else
            {
                if (!RuleVerdictOverlay.CLASS_VERDICTS.contains(authored.verdict()))
                {
                    throw new IllegalStateException(
                            record.get("id") + " " + memberIds + ": verdict '" + authored.verdict()
                                    + "' is a RELATION verdict; a" + " class may only be "
                                    + new TreeSet<>(RuleVerdictOverlay.CLASS_VERDICTS));
                }
                record.put("verdict", authored.verdict());
                record.put("sanctioned_layers", authored.sanctionedLayers());
                usedKeys.add(RuleVerdictOverlay.key(memberIds));
                applications++;
            }
            classes.add(record);
        }

        // Relations: reference-linked pairs whose L1 differs. NOT classes — they share no L1.
        List<Map<String, Object>> relations = new ArrayList<>();
        int relationCounter = 0;
        for (Pair pair : pairs)
        {
            if (!"C-logic".equals(bucket(pair.source(), pair.target())))
            {
                continue;
            }
            List<String> memberIds = List.of(pair.source().id(), pair.target().id());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", String.format("REL-%04d", ++relationCounter));
            record.put("members", memberIds);
            record.put("origin", "reference");
            record.put("relation", pair.sourceRefCount() > 1 ? "one-to-many" : "one-to-one");
            record.put("guard_equivalent", guardEquivalent(pair.source(), pair.target()));
            RuleVerdictOverlay.Verdict authored = overlay.find(memberIds).orElse(null);
            if (authored == null)
            {
                record.put("verdict", "pending");
            }
            else
            {
                if (!RuleVerdictOverlay.RELATION_VERDICTS.contains(authored.verdict()))
                {
                    throw new IllegalStateException(
                            record.get("id") + " " + memberIds + ": verdict '" + authored.verdict()
                                    + "' is a CLASS verdict; a" + " relation may only be "
                                    + new TreeSet<>(RuleVerdictOverlay.RELATION_VERDICTS));
                }
                if (!authored.sanctionedLayers().isEmpty())
                {
                    throw new IllegalStateException(record.get("id") + " " + memberIds
                            + ": a relation has no sanctioned_layers — it shares no L1, so there is"
                            + " nothing to sanction against");
                }
                record.put("verdict", authored.verdict());
                usedKeys.add(RuleVerdictOverlay.key(memberIds));
                applications++;
            }
            relations.add(record);
        }

        List<String> unlinked = rules.stream().filter(r -> foreignReferences(r).isEmpty())
                .map(Rule::id).sorted().toList();

        List<Map<String, Object>> anomalies = new ArrayList<>();
        // ⛔ The two split conventions are COMPOSED HERE, never merged inside splitBases().
        // splitBases() must stay id-literal because its javadoc pins it in lockstep with
        // build_split_bases in documentation/rule-inventory/generate-inventory.py, and
        // citationSplitBases() has no Python counterpart. Merging them made splitBases() report 168
        // where its own test expects 137 letter-suffixed ids - i.e. it manufactured exactly the
        // two-implementations-disagree defect this inventory exists to catch.
        Map<String, String> splitBases = new LinkedHashMap<>(splitBases(rules));
        splitBases.putAll(citationSplitBases(rules));
        for (Map.Entry<Ref, List<Rule>> entry : owner.entrySet())
        {
            if (entry.getValue().size() <= 1)
            {
                continue;
            }
            // A node owned by a base plus its letter siblings is EXPECTED, not an anomaly: the
            // split group shares one sheet row and therefore one cited id (see splitBases). One
            // shared root implies one family, because splitBases only ever links within a family.
            Set<String> roots = entry.getValue().stream()
                    .map(r -> splitGroupRoot(r.id(), splitBases))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (roots.size() == 1)
            {
                continue;
            }
            anomalies.add(new LinkedHashMap<>(
                    Map.of("kind", "multi-owner-node", "id", entry.getKey().id(), "owners",
                            entry.getValue().stream().map(Rule::id).sorted().toList())));
        }
        for (Rule rule : rules)
        {
            for (Ref ref : foreignReferences(rule))
            {
                if (owner.containsKey(ref))
                {
                    continue;
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("kind", anomalyKind(rule, ref));
                record.put("rule", rule.id());
                record.put("origin", ref.origin());
                record.put("target_id", ref.id());
                anomalies.add(record);
            }
        }
        anomalies.sort(Comparator.comparing(m -> String.valueOf(m.get("kind"))
                + String.valueOf(m.getOrDefault("rule", m.get("id")))));

        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("classes", classes);
        registry.put("relations", relations);
        registry.put("unlinked", unlinked);
        registry.put("anomalies", anomalies);
        // ⚑ D36 visibility. `unmatched` names every hand-authored verdict whose member set no
        // longer exists — i.e. every judgement that has just reverted to `pending` because a class
        // gained or lost a rule. Reverting is the CORRECT default (the judgement was about a
        // specific set) but it must never be silent, so it lands in the committed artifact, in
        // main()'s stdout line, and in a drift assertion.
        registry.put("verdict_overlay", overlay.report(usedKeys, applications));
        return registry;
    }

    /**
     * Origins that no family in this corpus owns. A reference to one of them is not a defect — it
     * points into a rule set we do not carry — so it must not be reported as a dangling target.
     */
    private static final Set<String> UNOWNED_ORIGINS = Set.of("FDA Business Rules",
            "TIG Conformance Rules");

    /**
     * Why a reference resolves to nothing. The three causes are materially different and must not
     * be conflated:
     *
     * <ul>
     * <li>{@code origin-not-owned-by-any-family} — the origin's rule set is not part of this corpus
     * ({@code FDA Business Rules}, {@code TIG Conformance Rules}). Informational.</li>
     * <li>{@code self-echo-reference} — a legacy import wrote a rule's own {@code Core.Id} as a
     * {@code SEND Conformance Rules} reference. It carries no provenance (D5 removes them).</li>
     * <li>{@code dangling-reference} — the origin is owned, but the cited id is not. These are the
     * FDA→CG citations injected as-asserted by {@link StandardsLinkageBackfill}.</li>
     * </ul>
     */
    private static String anomalyKind(Rule rule, Ref ref)
    {
        if (UNOWNED_ORIGINS.contains(ref.origin()))
        {
            return "origin-not-owned-by-any-family";
        }
        if ("SEND Conformance Rules".equals(ref.origin()) && ref.id().equals(rule.id()))
        {
            return "self-echo-reference";
        }
        return "dangling-reference";
    }


    /** Which layers differ among a class's members. L1 is equal by construction. */
    private static Map<String, Boolean> divergence(List<Rule> members)
    {
        Map<String, Boolean> divergence = new LinkedHashMap<>();
        divergence.put("L2",
                members.stream().map(r -> RuleFingerprint.l2(r.node())).distinct().count() > 1);
        divergence.put("L3",
                members.stream().map(r -> RuleFingerprint.l3(r.node())).distinct().count() > 1);
        divergence.put("L4",
                members.stream().map(r -> RuleFingerprint.l4(r.node())).distinct().count() > 1);
        return divergence;
    }


    private static String unorderedKey(String a, String b)
    {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }


    public static void main(String[] args) throws IOException
    {
        Path checksRoot = Path
                .of(System.getProperty("inventory.checksRoot", SRC_CHECKS.toString()));
        Path out = Path.of(System.getProperty("inventory.out", REGISTRY.toString()));
        Path sidecar = Path.of(System.getProperty("inventory.verdicts",
                RuleVerdictOverlay.DEFAULT_PATH.toString()));
        List<Rule> rules = load(checksRoot);
        Map<String, Object> registry = build(rules, RuleVerdictOverlay.load(sidecar));
        Files.createDirectories(out.getParent());
        Files.writeString(out, YAML_WRITE.writeValueAsString(registry), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> overlay = (Map<String, Object>) registry.get("verdict_overlay");
        System.out.printf("rules=%d classes=%d relations=%d unlinked=%d anomalies=%d -> %s%n",
                rules.size(), ((List<?>) registry.get("classes")).size(),
                ((List<?>) registry.get("relations")).size(),
                ((List<?>) registry.get("unlinked")).size(),
                ((List<?>) registry.get("anomalies")).size(), out);
        // ⚑ Never let an unmatched verdict pass unremarked: it means a class's membership moved and
        // a hand-authored judgement has just reverted to `pending`.
        System.out.printf("verdicts: %s entries=%s applied=%s UNMATCHED=%s %s%n",
                overlay.get("source"), overlay.get("entries"), overlay.get("applied"),
                ((List<?>) overlay.get("unmatched")).size(), overlay.get("unmatched"));
    }
}
