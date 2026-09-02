package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The hand-authored verdict side-car for {@code documentation/rule-inventory/rule-equivalence.yaml}
 * (decision <strong>D36</strong>, {@code plans/PLAN-rule-inventory.md} §11.5/§11.6).
 *
 * <p>
 * <strong>Why it exists.</strong> The registry is a pure function of {@code rules-src/checks} and
 * is regenerated with the corpus. {@link RuleEquivalenceInventory#build} used to emit
 * {@code verdict: pending} and {@code sanctioned_layers: []} as <em>literals</em>, so a verdict
 * hand-written into the YAML was destroyed by the next regeneration and a generator changed to emit
 * anything else failed a pinning test. There was therefore <em>no channel</em> through which a
 * triage judgement could survive a regen, which is what made
 * {@code RuleEquivalenceDriftTest.alignedClassesDoNotDivergeOutsideTheirSanctionedLayers} vacuous
 * by construction. This class is that channel.
 *
 * <p>
 * <strong>⚠⚠ The key is the SET of member rule ids — never the {@code EQ-} / {@code REL-}
 * id.</strong> Those ids are <em>positional</em>: {@link RuleEquivalenceInventory#build} numbers
 * classes in {@code TreeMap} order of their canonical L1, so inserting or removing one class
 * renumbers every class after it. One class whose membership did not change at all was
 * {@code EQ-0470}, then {@code EQ-0475}, then {@code EQ-0479} across three consecutive
 * regenerations. A side-car keyed on the class id would silently re-attach every verdict to the
 * <em>wrong</em> class after any regen, and the failure would be invisible because every key still
 * resolves. {@link #key} therefore normalises a member list to a sorted, de-duplicated
 * {@code List<String>}, and {@link #load} <em>rejects</em> a key whose members look like positional
 * ids.
 *
 * <p>
 * <strong>⚑ What happens when a class's membership changes by one rule.</strong> The key stops
 * matching, and the entry's verdict silently reverts to {@code pending}. <strong>That is the
 * intended default</strong> — the judgement was made about a specific set of rules, and a set that
 * gained or lost a member has not been judged — but it must never be <em>silent</em>. Every
 * side-car key that matched nothing is counted and listed in the registry's own
 * {@code verdict_overlay} section, printed by {@link RuleEquivalenceInventory#main}, and asserted
 * to be empty by {@code RuleEquivalenceDriftTest.theCommittedSideCarIsFullyAccountedFor}. An
 * unmatched verdict that nobody prints is how this whole class of problem started.
 *
 * <p>
 * File shape ({@link #DEFAULT_PATH}):
 *
 * <pre>
 * verdicts:
 *   - members: [CDISC-CG0280, CORE-000335, FDA-SD1269, PMDA-SD1269]
 *     verdict: aligned
 *     sanctioned_layers: [L3, L4]
 *     note: prose and OV context follow each family's own sheet (plan §3.7).
 * </pre>
 *
 * <p>
 * ⚠ The example deliberately sanctions <b>L3/L4 only</b>, on a class that really is aligned. It
 * used to read {@code verdict: sanctioned} / {@code sanctioned_layers: [L2]} over
 * {@code [CDISC-CG0114, CORE-000093, FDA-SD0035, PMDA-SD0035]} — a class that diverges at L2 and
 * nowhere else, and is `pending` for exactly that reason — so the one shape the doctrine forbids
 * was the shape the file's own documentation invited an author to copy. See
 * {@link #L2_SANCTION_ALLOWLIST}.
 *
 * <p>
 * {@code note} is free text for the human reader and is not merged into the registry — the registry
 * carries the machine-readable outcome, the side-car carries the reasoning. The one exception is an
 * {@code L2} sanction, where a {@code note} is <em>required</em> (see
 * {@link #L2_SANCTION_ALLOWLIST}).
 *
 * <p>
 * ⭐ <b>Severity divergence lives here and nowhere else.</b> Owner ruling {@code O-A} (2026-08-25)
 * left {@code Severity} <b>out</b> of the fingerprint entirely — the "hundreds" branch of Plan C
 * §4.7's decision rule, on five measurements landing between 278 and 284 differing classes — so
 * there is no {@code L5} layer and no hash will ever flag a twin class whose members grade the same
 * check differently. That is deliberate, and it makes this side-car's {@code note} the
 * <em>only</em> channel carrying the explanation for the §4.8 {@code A4} classes: their members'
 * prose differs because the rules genuinely differ in severity, which is now an authored
 * {@code Severity} field rather than an inference from a {@code Description} opener. A "no
 * fingerprint change" ruling must not silently drop it.
 * </p>
 */
final class RuleVerdictOverlay
{

    /**
     * Where the committed side-car lives, module-relative (surefire and the generators both cwd
     * here).
     */
    static final Path DEFAULT_PATH = Path.of("documentation", "rule-inventory",
            "rule-equivalence-verdicts.yaml");

    /**
     * The closed verdict vocabulary for a class. Mirrors {@code RuleEquivalenceDriftTest}'s copy;
     * validating here as well means a typo is caught when the side-car is <em>read</em>, not one
     * regeneration later when the drift gate sees it in the artifact.
     */
    static final Set<String> CLASS_VERDICTS = Set.of("pending", "aligned", "sanctioned",
            "dissolved");

    /** The closed verdict vocabulary for a relation. A relation has no {@code aligned} state. */
    static final Set<String> RELATION_VERDICTS = Set.of("pending", "sanctioned", "to-align");

    /**
     * The layers a class may sanction. L1 is deliberately absent: a class is <em>keyed</em> on its
     * L1, so an L1 divergence is a relation, not a sanctionable layer. This is a whitelist, not an
     * {@code != "L1"} blacklist — the blacklist it mirrors accepted {@code l1}, {@code L9} and
     * {@code banana}.
     */
    static final List<String> SANCTIONABLE_LAYERS = List.of("L2", "L3", "L4");

    /**
     * <strong>The member sets for which an {@code L2} sanction is legal — and nothing
     * else.</strong>
     *
     * <p>
     * {@code plans/done/PLAN-twin-alignment-completion.md} §13.2 states the doctrine: prose (L4)
     * and OV-context / {@code Sensitivity} / {@code Executability} (L3) are valid family-sheet
     * variants and so are sanctionable, but targeting (L2 = {@code Scope} + {@code Match_Datasets})
     * is <em>behavioural</em> and in scope for realignment — a class still diverging there stays
     * {@code pending} with its reason, and 17 classes were held back at phase 3 rather than
     * promoted on an unruled L2 sanction. Nothing enforced that, so a side-car author could excuse
     * a live behavioural divergence with one word.
     *
     * <p>
     * The owner has ruled <b>two</b> exceptions, both on 2026-08-19. CLF re-rule <b>R-2 = (a)</b>:
     * the ADaM&times;SDTM class below diverges at L2 because its members target two different
     * <em>data models</em>, which no realignment can reconcile. CLF re-rule <b>R-3</b>: the
     * {@code CDISC-SEND-0087} / {@code FDA-SD0036} / {@code PMDA-SD0036} class spells
     * {@code Classes} as {@code [FINDINGS]} vs {@code [FINDINGS, FINDINGS ABOUT]}, which is
     * representation-only under the engine's FINDINGS-ABOUT subsumption while each member follows
     * its own sheet. An L2 sanction is accepted for those two member sets, and nothing else. It is
     * an allowlist keyed on the member set — the same key the rest of this class uses, and for the
     * same reason ({@code EQ-}/{@code REL-} ids renumber) — because a heuristic over the free-text
     * {@code note} would be gameable by the phrase "per the ruling".
     *
     * <p>
     * <strong>Adding one is deliberately a code change</strong>, in two places: here, and the
     * mirror in {@code RuleEquivalenceDriftTest.l2SanctionsAreLimitedToTheRuledAllowlist} that
     * polices the regenerated registry. An entry must also carry a non-blank {@code note} naming
     * the ruling, so the artifact explains itself to the next reader.
     */
    static final Set<List<String>> L2_SANCTION_ALLOWLIST = Set.of(
            key(List.of("CDISC-AD0046", "FDA-SD0038", "PMDA-AD0046", "PMDA-SD0038")),
            // CLF R-3 (owner, 2026-08-19): Classes spelling [FINDINGS] vs [FINDINGS, FINDINGS
            // ABOUT] is representation-only under the engine's FINDINGS-ABOUT subsumption and
            // each member spells its own sheet — sanctioned as ruled.
            key(List.of("CDISC-SEND-0087", "FDA-SD0036", "PMDA-SD0036")));

    /**
     * A member id that is really a positional registry id. The one key shape that must never work.
     */
    private static final Pattern POSITIONAL_ID = Pattern.compile("(?:EQ|REL)-\\d+");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Sorted, de-duplicated member ids → the hand-authored verdict for that exact set. */
    private final Map<List<String>, Verdict> byMembers;

    private final String source;

    /** One hand-authored judgement. {@code sanctionedLayers} is empty for a relation. */
    record Verdict(String verdict, List<String> sanctionedLayers)
    {

        Verdict
        {
            sanctionedLayers = List.copyOf(sanctionedLayers);
        }
    }

    private RuleVerdictOverlay(String source, Map<List<String>, Verdict> byMembers)
    {
        this.source = source;
        this.byMembers = Map.copyOf(byMembers);
    }


    /** The overlay that supplies nothing — every entry keeps the generator's {@code pending}. */
    static RuleVerdictOverlay empty()
    {
        return new RuleVerdictOverlay("(none)", Map.of());
    }


    /**
     * An in-memory overlay, for tests that must not depend on the committed side-car's contents.
     */
    static RuleVerdictOverlay of(Map<List<String>, Verdict> entries)
    {
        Map<List<String>, Verdict> normalised = new LinkedHashMap<>();
        entries.forEach((members, verdict) -> normalised.put(key(members), verdict));
        return new RuleVerdictOverlay("(synthetic)", normalised);
    }


    /**
     * Reads the side-car. An <strong>absent</strong> file yields an empty overlay rather than an
     * error: the channel is optional by design, and the tool must still regenerate the registry in
     * a tree where nobody has authored a verdict yet. Every other malformation is fatal — a
     * side-car that silently drops an entry is worse than no side-car, because the verdict looks
     * authored.
     */
    static RuleVerdictOverlay load(Path file) throws IOException
    {
        if (!Files.isRegularFile(file))
        {
            return new RuleVerdictOverlay(file.toString().replace('\\', '/'), Map.of());
        }
        JsonNode root = YAML.readTree(file.toFile());
        Map<List<String>, Verdict> entries = new LinkedHashMap<>();
        JsonNode verdicts = root == null ? null : root.get("verdicts");
        if (verdicts != null && !verdicts.isNull())
        {
            if (!verdicts.isArray())
            {
                throw new IllegalStateException(file + ": `verdicts` must be a sequence");
            }
            for (JsonNode entry : verdicts)
            {
                List<String> members = readMembers(file, entry);
                if (entries.put(members, readVerdict(file, entry, members)) != null)
                {
                    throw new IllegalStateException(
                            file + ": duplicate member set " + members + " — one set, one verdict");
                }
            }
        }
        return new RuleVerdictOverlay(file.toString().replace('\\', '/'), entries);
    }


    private static List<String> readMembers(Path file, JsonNode entry)
    {
        JsonNode members = entry.get("members");
        if (members == null || !members.isArray() || members.isEmpty())
        {
            throw new IllegalStateException(file + ": every verdict needs a non-empty `members`"
                    + " sequence — the key is the SET of member rule ids, never the positional"
                    + " EQ-/REL- id");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode member : members)
        {
            String id = member.asText();
            if (POSITIONAL_ID.matcher(id).matches())
            {
                throw new IllegalStateException(file + ": `" + id + "` is a positional registry id."
                        + " Those renumber on every regeneration (one unchanged class was EQ-0470,"
                        + " then EQ-0475, then EQ-0479), so a verdict keyed on one silently"
                        + " re-attaches to the wrong entry. Key on the member RULE ids instead.");
            }
            ids.add(id);
        }
        return key(ids);
    }


    private static Verdict readVerdict(Path file, JsonNode entry, List<String> members)
    {
        JsonNode verdict = entry.get("verdict");
        if (verdict == null || !verdict.isTextual())
        {
            throw new IllegalStateException(file + ": " + members + " has no textual `verdict`");
        }
        Set<String> vocabulary = new TreeSet<>(CLASS_VERDICTS);
        vocabulary.addAll(RELATION_VERDICTS);
        if (!vocabulary.contains(verdict.asText()))
        {
            throw new IllegalStateException(file + ": " + members + " has verdict '"
                    + verdict.asText() + "', which is not one of " + vocabulary);
        }
        Set<String> declaredLayers = new LinkedHashSet<>();
        JsonNode declared = entry.get("sanctioned_layers");
        if (declared != null && !declared.isNull())
        {
            if (!declared.isArray())
            {
                throw new IllegalStateException(
                        file + ": " + members + " has a non-sequence `sanctioned_layers`");
            }
            for (JsonNode layer : declared)
            {
                if (!SANCTIONABLE_LAYERS.contains(layer.asText()))
                {
                    throw new IllegalStateException(file + ": " + members + " cannot sanction layer"
                            + " '" + layer.asText() + "' — only " + SANCTIONABLE_LAYERS
                            + " are sanctionable on a class (an L1 divergence is a relation, not a"
                            + " class, and the comparison is case-sensitive on purpose)");
                }
                declaredLayers.add(layer.asText());
            }
        }
        if (declaredLayers.contains("L2"))
        {
            requireRuledL2Sanction(file, entry, members);
        }
        // Canonical order, so the merged registry does not record the order the author happened to
        // type — two side-cars sanctioning the same layers must produce the same artifact.
        return new Verdict(verdict.asText(),
                SANCTIONABLE_LAYERS.stream().filter(declaredLayers::contains).toList());
    }


    /**
     * Enforces {@link #L2_SANCTION_ALLOWLIST}: an {@code L2} sanction is legal only for an
     * allowlisted member set, and only with a non-blank {@code note} to carry the ruling. Both
     * halves are fatal rather than skipped — an excused behavioural divergence that nobody ruled
     * looks exactly like one that was ruled.
     */
    private static void requireRuledL2Sanction(Path file, JsonNode entry, List<String> members)
    {
        if (!L2_SANCTION_ALLOWLIST.contains(members))
        {
            throw new IllegalStateException(file + ": " + members + " sanctions L2, and L2 is not"
                    + " sanctionable by default. Targeting (Scope + Match_Datasets) is behavioural"
                    + " and in scope for realignment (PLAN-twin-alignment-completion.md §13.2), so"
                    + " a class still diverging there is recorded as `pending` WITH ITS REASON in"
                    + " the note. If the owner has ruled this particular divergence unfixable, add"
                    + " the member set to RuleVerdictOverlay.L2_SANCTION_ALLOWLIST and to the"
                    + " mirror in RuleEquivalenceDriftTest — deliberately a reviewed code change,"
                    + " not a side-car edit.");
        }
        JsonNode note = entry.get("note");
        if (note == null || !note.isTextual() || note.asText().isBlank())
        {
            throw new IllegalStateException(file + ": " + members + " is an allowlisted L2 sanction"
                    + " but carries no `note` — the ruling that allowed it must travel with it,"
                    + " because the registry records only the machine-readable outcome");
        }
    }


    /**
     * The canonical key for a set of member rule ids: <strong>sorted and de-duplicated</strong>, so
     * the side-car does not depend on the order the generator happens to emit members in, and so a
     * hand-authored entry may list them in any order.
     */
    static List<String> key(Collection<String> memberIds)
    {
        return List.copyOf(new TreeSet<>(memberIds));
    }


    /** The verdict authored for exactly this member set, if any. */
    Optional<Verdict> find(Collection<String> memberIds)
    {
        return Optional.ofNullable(byMembers.get(key(memberIds)));
    }


    /** Every authored key, in file order. */
    Set<List<String>> keys()
    {
        return new LinkedHashSet<>(byMembers.keySet());
    }


    int size()
    {
        return byMembers.size();
    }


    /**
     * The {@code verdict_overlay} section written into the registry — the visibility half of D36.
     * {@code unmatched} lists every authored key that matched no class and no relation, i.e. every
     * judgement that has silently reverted to {@code pending} because its class's membership moved.
     *
     * @param usedKeys
     *            the keys {@link RuleEquivalenceInventory#build} actually applied
     * @param applications
     *            how many registry entries received a verdict (a key matching two entries counts
     *            twice, so {@code applied != entries - unmatched} is itself a signal)
     */
    Map<String, Object> report(Set<List<String>> usedKeys, int applications)
    {
        List<Map<String, Object>> unmatched = new ArrayList<>();
        byMembers.forEach((members, verdict) ->
        {
            if (!usedKeys.contains(members))
            {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("members", members);
                record.put("verdict", verdict.verdict());
                unmatched.add(record);
            }
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", source);
        report.put("entries", byMembers.size());
        report.put("applied", applications);
        report.put("unmatched", unmatched);
        return report;
    }
}
