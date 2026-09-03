package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;

/**
 * Synthesises a {@link Rule} for a parity spec:
 *
 * <ul>
 * <li>{@link SpecSchema.Kind#RULE} — deserialise the spec's {@code rule} JSON tree directly.</li>
 * <li>{@link SpecSchema.Kind#CHECK_OPERATOR} — wrap the spec's {@code check} leaf in a synthetic
 * {@code Record Data} rule scoped to the first declared dataset (per §3.4 of the test-framework
 * plan).</li>
 * <li>{@link SpecSchema.Kind#OPERATION} — deferred until needed (M1 covers RULE + CHECK_OPERATOR;
 * OPERATION is a Milestone-3+ concern, so we throw to make the gap loud rather than silently
 * mis-scaffold).</li>
 * </ul>
 *
 * <p>
 * The synthetic rule's {@code Output_Variables} is set to the row-key columns of the primary
 * dataset, so {@link ViolationNormaliser} can reconstruct each violation's {@code row_key}
 * uniformly.
 *
 * <p>
 * ⚠⚠ <b>Known divergence from a production load</b> — this class is, in the wave-34 census's own
 * words ({@code lib/corej-cdisc-rules/documentation/derivation/loader-bypassing-tests.tsv}), "a
 * hand-maintained REIMPLEMENTATION of the load pipeline", and it is deliberately partial. Verified
 * at source 2026-08-13 (wave 41) against {@code RulePackageLoader.finishLoad}
 * ({@code RulePackageLoader.java:160-212}): {@code finishLoad} runs <b>11</b> passes; this scaffold
 * replays <b>5</b> — {@code normalizeJoinTypes}, {@code installNativeExpr} (the single-rule
 * analogue of {@code retainNativeExpr}, minus the inliners), {@code deriveOutputVariables}, and,
 * inside {@link #buildExplicit} for {@code kind: rule} only, {@code normalizeOperations} and
 * {@code deriveOmittedFields} — and <b>omits 6</b>: {@code removeParkedRules},
 * {@code validateOperandSubstitution}, {@code validateEnumFields},
 * {@code validateOperationReferences}, {@code validateUnresolvedOperationWildcards} and
 * {@code injectInlineOperationGates}. A {@code kind: check_operator} spec gets only <b>3</b>
 * passes, because the two {@code buildExplicit} passes never run for it. (The census named three
 * omissions and never asserted a total; any "omits-three" summary elsewhere is a lossy paraphrase.)
 *
 * <p>
 * What each omission costs a spec's fidelity to production, sharpest first:
 * <ul>
 * <li>{@code removeParkedRules} — a spec resolving an {@code Executability: "Not Executable"} rule
 * RUNS it here, while a production load parks (drops) the rule before any other pass
 * ({@code RulePackageLoader.java:171,225-239}). Such a spec asserts behaviour of a rule production
 * never executes.</li>
 * <li>{@code injectInlineOperationGates} — a rule whose Check depends on an injected
 * inline-operation gate executes here without it, on a Check shape a production run never
 * evaluates.</li>
 * <li>the four {@code validate*} load gates — a rule production would REJECT at load (bad enum
 * field, dangling operation reference, unresolved {@code --} wildcard, invalid operand
 * substitution) scaffolds and executes here without complaint.</li>
 * <li>{@code validateOperandSubstitution} also hosts the retired-underscore guard
 * ({@code RulePackageLoader.validateRetiredUnderscoreKeys},
 * {@code plans/done/PLAN-underscore-field-retirement.md} §3 G), so a spec left on a retired
 * {@code _wildcards:} spelling would bind to nothing and run <b>unfiltered</b> — silently, with no
 * diagnostic anywhere. That half is now covered locally by
 * {@link #rejectDroppedKeys(SpecSchema, Rule)}: any key the model did not bind fails the scaffold —
 * top-level, and (since this lane is a third reader of rule JSON that never runs loader gates R1
 * and R2) also under {@code Scope}, {@code Scope.Datasets}, {@code Requirements} and
 * {@code Requirements.Variables}, whose own collectors the {@link Rule}-level one never sees. So
 * the blind spot no longer depends on a reviewer noticing. The other five omissions remain
 * uncovered.</li>
 * </ul>
 *
 * <p>
 * Two standing cautions: {@code finishLoad:204-209} requires {@code deriveOutputVariables} to
 * observe post-inlining state — {@link #build} preserves that ordering, but nothing in the build
 * enforces it; and every pass added to {@code finishLoad} has to be mirrored here by hand or this
 * suite silently drifts from the production pipeline (the census's own warning).
 */
public final class RuleScaffold
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Top-level rule keys the core {@link Rule} model deliberately leaves unbound. {@code Source}
     * is authoring provenance carried by every corpus rule (11 995 occurrences across
     * {@code rules/}) and read by tooling, never by the engine — it is dropped by a production load
     * too, so dropping it here is not a divergence.
     *
     * <p>
     * {@code Source_Proposed} is the same kind of key and joins it for the same reason
     * ({@code plans/PLAN-source-proposed-block.md} §4 item 3): the row we ask the authority to
     * publish, unmodelled by the engine and dropped at load. Without the entry
     * {@link #rejectDroppedKeys} would throw the day a pinned spec's rule gains a proposal — the
     * five pinned specs are the only rules carrying an inline {@code Source:} inside their
     * {@code rule:} object, which is why this allowlist exists at all.
     * </p>
     */
    private static final java.util.Set<String> UNMODELLED_BUT_LEGAL = java.util.Set.of("Source",
            "Source_Proposed");

    private RuleScaffold()
    {
    }


    public static Rule build(SpecSchema spec, String primaryDomain) throws IOException
    {
        Rule rule = switch (spec.kind())
        {
        case RULE -> buildExplicit(spec);
        case CHECK_OPERATOR -> buildCheckOperator(spec, primaryDomain);
        case OPERATION -> throw new UnsupportedOperationException(
                "kind: operation scaffold not implemented yet (M1 scope is RULE + CHECK_OPERATOR)");
        };
        // Apply the SAME Join_Type normalisation a production load performs (inner-on-absent),
        // mirroring Python's parse_datasets default — the scaffold otherwise bypasses
        // RulePackageLoader. This lets fixtures drop explicit Join_Type and match rules-legacy
        // directly; rules that need a left join carry an explicit Join_Type: left in the corpus.
        if (rule != null)
        {
            // The scaffold's own retired-underscore net (see the class javadoc): this lane never
            // runs validateOperandSubstitution, so a spec on a retired `_wildcards:` spelling
            // would bind to nothing and expand unfiltered with no diagnostic at all. Fail here
            // instead, on ANY key the model did not bind.
            rejectDroppedKeys(spec, rule);
            net.cumba.corej.core.RulePackageLoader.normalizeJoinTypes(rule);
        }
        // The scaffold bypasses RulePackageLoader, so a built rule carries no native checkExpr and
        // would dispatch on the legacy engine. Install it here through the SAME single retention
        // decision the loader uses, mirroring the per-dataset RuleGenerator seam for generated
        // rules that likewise bypass the loader (RuleGenerator:
        // RulePackageLoader.installNativeExpr). This makes a parity-harness rule dispatch natively
        // exactly as a loader-loaded production rule does; installNativeExpr is a no-op when a
        // checkExpr is already present.
        if (rule != null && rule.getCheckExpr() == null)
        {
            net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        }
        // EC-37: the SAME effective-Output_Variables derivation a production load performs,
        // after installNativeExpr exactly like the loader/generator seams. Without this the
        // harness would assert pre-derivation behaviour that no production run exhibits.
        net.cumba.corej.core.RulePackageLoader.deriveOutputVariables(rule);
        return rule;
    }


    /**
     * Fails the scaffold when a spec's rule carries a key the model did not bind — at the top level
     * <b>or</b> in one of the nested blocks that carry their own collector. The mapper is lenient
     * by design (a production load is too), but this lane replays none of the loader's validation
     * passes, so an unbound key here is silent in a way it is nowhere else — the exact shape
     * {@code PLAN-underscore-field-retirement.md} §8 names as "the one place where 'the guard will
     * catch it' is false".
     *
     * <p>
     * Four blocks below the top level own a {@code @JsonAnySetter} collector, and the
     * {@link Rule}-level one never sees their keys: {@link Scope} (a {@code Variables:} block
     * surviving the {@code Requirements} migration —
     * {@code plans/PLAN-scope-requirements-split.md}), {@link DatasetScope}, {@link Requirements}
     * (a misspelled block name) and {@link VariableRequirement} (a misspelled facet, e.g.
     * {@code AnyOf:} for {@code Any:}). Together with {@link Rule} that is every collector the
     * model has, i.e. the same reach as loader gates R1 and R2
     * ({@code RulePackageLoader.validateRetiredScopeVariables} /
     * {@code validateRequirementsShape}). Each is a <em>deleted</em> requirement rather than a
     * skipped rule: the spec then executes unrestricted, and on this lane that reads as a parity
     * result — several parity baseline entries exist precisely because a variable requirement makes
     * Java SKIP where Python executes. The loader's gates R1/R2 read exactly these collectors; this
     * lane runs neither, so the net has to read them itself.
     *
     * @param spec
     *            the spec being scaffolded, named in the failure message
     * @param rule
     *            the bound rule
     * @throws IllegalStateException
     *             if any unbound key other than {@link #UNMODELLED_BUT_LEGAL} was seen
     */
    private static void rejectDroppedKeys(SpecSchema spec, Rule rule)
    {
        List<String> dropped = new ArrayList<>(rule.getUnknownKeys().stream()
                .filter(k -> !UNMODELLED_BUT_LEGAL.contains(k)).toList());
        Scope scope = rule.getScope();
        if (scope != null)
        {
            collectNested(dropped, "Scope", scope.getUnknownKeys());
            DatasetScope datasets = scope.getDatasets();
            if (datasets != null)
            {
                collectNested(dropped, "Scope.Datasets", datasets.getUnknownKeys());
            }
        }
        Requirements requirements = rule.getRequirements();
        if (requirements != null)
        {
            collectNested(dropped, "Requirements", requirements.getUnknownKeys());
            VariableRequirement variables = requirements.getVariables();
            if (variables != null)
            {
                collectNested(dropped, "Requirements.Variables", variables.getUnknownKeys());
            }
        }
        if (!dropped.isEmpty())
        {
            throw new IllegalStateException("Spec '" + spec.name() + "': rule key(s) " + dropped
                    + " bound to nothing and were silently dropped. This lane does not run the"
                    + " loader's validation passes, so nothing else would report it — check for a"
                    + " retired `_`-prefixed spelling (PLAN-underscore-field-retirement.md §3 G),"
                    + " a `Scope: {Variables: …}` block that should now be"
                    + " `Requirements: {Variables: {All: …}}` (PLAN-scope-requirements-split.md)"
                    + " or a typo.");
        }
    }


    /**
     * Adds a nested block's unbound keys to the drop list, qualified by the block name so the
     * failure message says where the key was found ({@code Scope.Variables},
     * {@code Requirements.Variabels}, {@code Requirements.Variables.AnyOf}).
     *
     * @param sink
     *            the drop list being built
     * @param block
     *            dotted path of the block that collected the keys
     * @param keys
     *            that block's collector contents; may be empty
     */
    private static void collectNested(List<String> sink, String block, SequencedSet<String> keys)
    {
        for (String key : keys)
        {
            sink.add(block + "." + key);
        }
    }


    private static Rule buildExplicit(SpecSchema spec) throws IOException
    {
        if (spec.rule() == null)
        {
            throw new IllegalArgumentException(
                    "Spec '" + spec.name() + "' kind=rule must declare a 'rule:' object");
        }
        Rule rule = MAPPER.treeToValue(spec.rule(), Rule.class);
        // The same operation normalisation a production load performs: the native rules/ corpus
        // (which coreId: resolution now serves) declares its residual Operations in expression
        // form (Form B), and this lane binds the rule directly rather than going through
        // RulePackageLoader. Without this pass every such operation looks operator-less and
        // silently resolves null — the rule fires nothing and the miss would be attributed to
        // the engine rather than to the loading path.
        RulePackageLoader.normalizeOperations(rule);
        // The loader also derives Sensitivity when a spec's rule omits it (the shipped native
        // corpus is stripped since the Phase-3 regen, so this runs for every coreId-resolved
        // spec; the documented overrides keep their authored Sensitivity). Without this the Java
        // engine would route the rule on a null Sensitivity, and every difference would be
        // attributed to the engines rather than to the loading path.
        RulePackageLoader.deriveOmittedFields(rule);
        return rule;
    }


    private static Rule buildCheckOperator(SpecSchema spec, String primaryDomain) throws IOException
    {
        if (spec.check() == null)
        {
            throw new IllegalArgumentException(
                    "Spec '" + spec.name() + "' kind=check_operator must declare a 'check:' leaf");
        }
        SpecSchema.SpecDataset primary = spec.datasets().get(primaryDomain);
        if (primary == null)
        {
            throw new IllegalArgumentException("Primary domain '" + primaryDomain
                    + "' not in datasets for spec '" + spec.name() + "'");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "auto-" + spec.name());
        ObjectNode core = MAPPER.createObjectNode().put("Id", "AUTO-" + spec.name());
        root.set("Core", core);
        root.put("Description", spec.description() != null ? spec.description()
                : "Auto-generated for " + spec.name());
        root.put("Sensitivity", "Record");
        root.put("Executability", "Fully Executable");

        // Scope: applyScopeOverride first; default to first dataset's domain.
        ObjectNode scope = MAPPER.createObjectNode();
        ObjectNode domains = MAPPER.createObjectNode();
        ArrayNode include = MAPPER.createArrayNode();
        if (spec.scopeOverride() != null && spec.scopeOverride().domainsInclude() != null
                && !spec.scopeOverride().domainsInclude().isEmpty())
        {
            spec.scopeOverride().domainsInclude().forEach(include::add);
        }
        else
        {
            include.add(primaryDomain);
        }
        domains.set("Include", include);
        scope.set("Domains", domains);
        root.set("Scope", scope);

        // Check: wrap the leaf in {all: [leaf]}
        ObjectNode check = MAPPER.createObjectNode();
        ArrayNode all = MAPPER.createArrayNode();
        all.add(spec.check());
        check.set("all", all);
        root.set("Check", check);

        // Outcome
        ObjectNode outcome = MAPPER.createObjectNode();
        outcome.put("Message", "auto");
        ArrayNode outVars = MAPPER.createArrayNode();
        List<String> rowKey = primary.rowKey() != null ? primary.rowKey() : List.of();
        for (String k : rowKey)
        {
            outVars.add(k);
        }
        outcome.set("Output_Variables", outVars);
        root.set("Outcome", outcome);

        return MAPPER.treeToValue(root, Rule.class);
    }


    /** Returns the rule's primary domain, used by {@link ViolationNormaliser}. */
    public static String primaryDomainOf(Rule rule)
    {
        if (rule == null || rule.getScope() == null || rule.getScope().getDomains() == null)
        {
            return null;
        }
        List<String> include = rule.getScope().getDomains().getInclude();
        return (include == null || include.isEmpty()) ? null : include.get(0);
    }


    /**
     * Exposes the synthesised rule's JSON form for debugging or {@code RulePackageLoader}-style
     * round-trips. Not part of the runtime path.
     */
    public static JsonNode toJsonNode(Rule rule)
    {
        return MAPPER.valueToTree(rule);
    }
}
