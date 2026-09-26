package net.cumba.corej.core.rulespec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;

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
 * words ({@code registers/derivation/loader-bypassing-tests.tsv}), "a hand-maintained
 * REIMPLEMENTATION of the load pipeline", and it is deliberately partial. Verified at source
 * 2026-08-13 (wave 41) against {@code RulePackageLoader.finishLoad}
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
 * diagnostic anywhere. That half is covered by {@link #rejectDroppedKeys(SpecSchema, Rule)}, which
 * since {@code PLAN-rule-unknown-keys-gate} calls the engine's own composite
 * {@code RulePackageLoader.unknownKeyErrors} — every unknown or stray key at every level of the
 * rule (the retired spellings, R1, R2's unknown-key arm, {@code Match_Datasets}, the {@code Check}
 * grammar's stray keys and every nested block's collector), in the loader's own wording — instead
 * of a hand-copied walk of five collectors that had to be mirrored by hand. So the blind spot no
 * longer depends on a reviewer noticing. The other five omissions remain uncovered.</li>
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
            RulePackageLoader.normalizeJoinTypes(rule);
        }
        // The scaffold bypasses RulePackageLoader, so a built rule carries no native checkExpr and
        // would dispatch on the legacy engine. Install it here through the SAME single retention
        // decision the loader uses, mirroring the per-dataset DatasetRuleResolver seam for
        // generated
        // rules that likewise bypass the loader (DatasetRuleResolver:
        // RulePackageLoader.installNativeExpr). This makes a parity-harness rule dispatch natively
        // exactly as a loader-loaded production rule does; installNativeExpr is a no-op when a
        // checkExpr is already present.
        if (rule != null && rule.getCheckExpr() == null)
        {
            RulePackageLoader.installNativeExpr(rule);
        }
        // EC-37: the SAME effective-Output_Variables derivation a production load performs,
        // after installNativeExpr exactly like the loader/generator seams. Without this the
        // harness would assert pre-derivation behaviour that no production run exhibits.
        RulePackageLoader.deriveOutputVariables(rule);
        return rule;
    }


    /**
     * The scaffold's drop-net — the engine's judgement, not a copy of it. This lane binds with its
     * own mapper and never runs {@code finishLoad}, so it asks the loader's composite
     * {@code RulePackageLoader.unknownKeyErrors} (PLAN-rule-unknown-keys-gate &#167;5.5) for every
     * unknown or stray key of the bound rule: a retired spelling, {@code Scope.Variables} (R1), an
     * unknown key under {@code Scope} / {@code Requirements} (R2), a {@code Match_Datasets} typo, a
     * key beside {@code expression:} in {@code Check} (which makes the binding DROP the condition,
     * so a scaffold that ignored {@code Rule.rawCheckLevels} would run the spec with no
     * {@code Check} at all) and every nested block's collector. Each is a <em>deleted</em>
     * requirement or a silently changed rule rather than a skipped one: the spec then executes on
     * something its author did not write, and on this lane that reads as an engine result.
     *
     * <p>
     * ⚑ {@code Source} / {@code Source_Proposed} are no longer allow-listed (T1-1 a): they are
     * authoring provenance the corpus build strips from every package, so a spec's inline
     * {@code rule:} carries none — the five pinned ADaM specs that did were cleaned.
     * </p>
     *
     * @param spec
     *            the spec being scaffolded, named in the failure message
     * @param rule
     *            the bound rule
     * @throws IllegalStateException
     *             if the loader reports any unknown or stray key
     */
    private static void rejectDroppedKeys(SpecSchema spec, Rule rule)
    {
        List<String> errors = RulePackageLoader.unknownKeyErrors(rule);
        if (!errors.isEmpty())
        {
            throw new IllegalStateException("Spec '" + spec.name() + "': rule key(s) bound to"
                    + " nothing and would be silently dropped — " + String.join("; ", errors)
                    + ". This lane does not run the loader's validation passes, so nothing else"
                    + " would report it — check for a retired `_`-prefixed spelling"
                    + " (PLAN-underscore-field-retirement.md §3 G), a `Scope: {Variables: …}`"
                    + " block that should now be `Requirements: {Variables: {All: …}}`"
                    + " (PLAN-scope-requirements-split.md), an inline `Source:` block (authoring"
                    + " provenance, not engine format) or a typo.");
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
