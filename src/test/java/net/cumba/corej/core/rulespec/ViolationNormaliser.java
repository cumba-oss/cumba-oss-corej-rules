package net.cumba.corej.core.rulespec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Translates a {@link RuleExecutionResult} into the canonical-form set used for parity diffs.
 *
 * <p>
 * Per §3.5 of the plan:
 * <ul>
 * <li>{@code rule_id} ← {@link RuleExecutionResult#getRuleId()} (which is
 * {@code rule.getCore().getId()}).</li>
 * <li>{@code dataset} ← the primary domain name passed in.</li>
 * <li>{@code row_key} ← values read directly from the primary {@link IDataTable} using the declared
 * row-key columns. Reading from the table (rather than from {@link Violation#getValues()}) makes
 * the row_key independent of whatever the rule chose to expose via
 * {@code Outcome.Output_Variables}.</li>
 * <li>{@code output_variables} ← {@link Violation#getValues()} as-is (the engine has already
 * projected these per the rule's {@code Outcome.Output_Variables} list).</li>
 * <li>{@code status} ← always {@code SUCCESS} per emitted violation. This is the
 * <b>per-violation</b> axis and is a different thing from the rule-level {@code expected_status}:
 * it qualifies one violation entry, not the run. The rule-level status is
 * {@link net.cumba.corej.core.exec.RuleExecutionStatus}, carried on
 * {@link SpecRunner.SpecResult#engineStatus()} and compared against the spec's top-level
 * {@code expected_status} by {@link SpecRunner.SpecResult#statusMatches()}. (Before
 * {@code PLAN-parity-expected-status} this javadoc claimed the top-level field carried result-level
 * status — it was declared but read by nothing on either lane.)</li>
 * </ul>
 *
 * <p>
 * Note {@link #normalise} returns an <b>empty list</b> for any status other than {@code EXECUTED}:
 * on this lane a skipped or errored rule emits no violations by contract, which is precisely why a
 * skip used to be invisible on every axis and why the status channel exists.
 */
public final class ViolationNormaliser
{

    private ViolationNormaliser()
    {
    }


    public static List<CanonicalViolation> normalise(RuleExecutionResult result, IDataTable primary,
            String primaryDomain, List<String> rowKeyColumns)
    {
        List<CanonicalViolation> out = new ArrayList<>();
        if (result == null || result.getViolations() == null)
        {
            return out;
        }
        if (result.getStatus() != RuleExecutionStatus.EXECUTED)
        {
            // SKIPPED / ERROR: no violations emitted by contract.
            return out;
        }
        DataTableMeta meta = primary.getMetaData();
        List<String> safeRowKey = rowKeyColumns != null ? rowKeyColumns : List.of();

        for (Violation v : result.getViolations())
        {
            Map<String, Object> rowKey = readRowKey(primary, meta, safeRowKey, v.getRow());
            Map<String, Object> outputVars = new LinkedHashMap<>();
            if (v.getValues() != null)
            {
                for (Map.Entry<String, String> e : v.getValues().entrySet())
                {
                    // Per canonical contract (§3.5: "Missing values: null"), coerce empty
                    // strings to null. The Java engine emits "" for missing-or-invalid
                    // IDataValue; Python emits null. Without this coercion both would diverge
                    // on every Output_Variable that resolves to a missing cell.
                    String value = e.getValue();
                    if (value == null || value.isEmpty())
                    {
                        outputVars.put(e.getKey(), null);
                        continue;
                    }
                    // Finding #17: $-keyed Operation results like $study_domains come back
                    // stringified via java.util.List.toString() = "[a, b, c]". Python stringifies
                    // its own list with the same shape but different insertion order. Both are
                    // semantically sets; normalise by sorting so canonical output matches
                    // byte-for-byte across engines.
                    outputVars.put(e.getKey(),
                            (e.getKey() != null && e.getKey().startsWith("$"))
                                    ? sortListString(value)
                                    : value);
                }
            }
            out.add(CanonicalViolation.of(result.getRuleId(), primaryDomain, rowKey, outputVars));
        }
        return out;
    }


    private static Map<String, Object> readRowKey(IDataTable table, DataTableMeta meta,
            List<String> rowKeyColumns, long row)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String col : rowKeyColumns)
        {
            int idx = meta.getColumnIndex(col);
            // Out-of-range row covers the dataset-level-on-empty-dataset case: there is no row to
            // read, so the key column resolves to null. Mirrors the Python adapter's
            // _row_key_from_engine_error, which returns {col: None} when row >= len(df).
            if (idx < 0 || row >= table.getRowCount())
            {
                map.put(col, null);
                continue;
            }
            IDataValue dv = table.getDataValue(row, idx);
            map.put(col, normaliseValue(dv));
        }
        return map;
    }


    private static Object normaliseValue(IDataValue dv)
    {
        if (dv == null || dv.isMissingOrInvalid())
        {
            return null;
        }
        Object raw = dv.getValue();
        if (raw == null)
        {
            return null;
        }
        if (raw instanceof Double d)
        {
            if (d.isNaN() || d.isInfinite())
            {
                return null;
            }
            return Math.round(d * 1.0e10) / 1.0e10;
        }
        if (raw instanceof Float f)
        {
            return normaliseValue(net.cumba.datatable.values.DataValueSupport.getAsDataValue(
                    f.doubleValue(), net.cumba.datatable.values.DataValueType.DOUBLE));
        }
        return raw;
    }


    /**
     * When a value looks like Java's {@code List.toString()} output (a single outer {@code [...]}
     * wrapping comma-separated elements with no inner quoting), parse it, sort the elements
     * lexicographically, and re-stringify. Leaves non-matching strings unchanged. Used only for
     * {@code $}-keyed output variables — see Finding #17.
     */
    static String sortListString(String value)
    {
        if (value == null || value.length() < 2 || value.charAt(0) != '['
                || value.charAt(value.length() - 1) != ']')
        {
            return value;
        }
        String inner = value.substring(1, value.length() - 1);
        if (inner.isEmpty())
        {
            return value;
        }
        String[] parts = inner.split(", ", -1);
        List<String> sorted = new ArrayList<>(parts.length);
        for (String p : parts)
        {
            sorted.add(p);
        }
        java.util.Collections.sort(sorted);
        return "[" + String.join(", ", sorted) + "]";
    }


    /**
     * Returns engine-side error messages, if any. M1: result-level status==ERROR yields a single
     * entry from {@link RuleExecutionResult#getStatusMessage()}; otherwise empty.
     *
     * <p>
     * <b>This is prose and is deliberately NOT the assertion channel.</b> It is kept because
     * {@link SpecRunner.SpecResult#formatDiff()} displays it, and because engine wording is exactly
     * the thing an assertion must not depend on. The compared value is
     * {@link #reasonsFrom(RuleExecutionResult, Rule)}.
     */
    public static List<String> errorsFrom(RuleExecutionResult result)
    {
        if (result != null && result.getStatus() == RuleExecutionStatus.ERROR
                && result.getStatusMessage() != null)
        {
            return List.of(result.getStatusMessage());
        }
        return List.of();
    }

    /**
     * Stable classification of an {@code ERROR} result, independent of engine prose
     * (PLAN-parity-expected-errors, decision D1 = Option B / D4).
     *
     * <p>
     * The tokens correspond one-for-one to the places {@link net.cumba.corej.core.exec.RuleRunner}
     * can return {@link RuleExecutionStatus#ERROR}. They are derived <b>here, in the harness</b>,
     * so the engine carries no parity-specific type — D4 explicitly rejected adding a typed reason
     * to {@link RuleExecutionResult}.
     */
    public enum ErrorReason
    {
        /** {@code rule.getLoadError() != null} — a load-time defect tagged by RulePackageLoader. */
        LOAD_ERROR,
        /** A forward RELREC join combined with a key-based Match_Datasets row expansion. */
        RELREC_KEY_EXPANSION,
        /** The Check compiled to no native expression form (the legacy evaluator is retired). */
        NO_NATIVE_CHECK_EXPR,
        /**
         * A {@code Match_Datasets} name / {@code RDOMAIN} value / dotted reference resolved to a
         * split domain whose members cannot be unioned — a column type clash across members
         * ({@code Fix #358}, ruling 1 of {@code PLAN-match-datasets-split-union.md}).
         */
        INVALID_SPLIT_DOMAIN,
        /**
         * A column's shipped type does not match the operand position it is used in, and the rule
         * did not convert it — {@code PLAN-column-type-conformance} Phase 3, rulings R4/R5/R9/R12.
         * A numeric operation on a {@code Char} column must be authored {@code num(X)}; a numeric
         * column read as text has no legal authoring and the rule is simply broken.
         */
        COLUMN_TYPE_MISMATCH,
        /**
         * An armed stage-B bind-gate finding — {@code PLAN-typed-expression-engine} phase 4,
         * D41/D104e. The typed engine decides each (rule × dataset) binding knowing the dataset's
         * columns, and an armed {@code StageBErrorKind} ({@code UNRESOLVED_WILDCARD},
         * {@code FILTER_UNRESOLVABLE}, {@code MATCHED_FLAG_UNRESOLVABLE}) is mapped by
         * {@code RuleRunner.mapStageBReport} to one {@code ERROR} per (rule, dataset) whose message
         * is the accumulated per-binding text behind the fixed prefix
         * {@link #STAGE_B_MESSAGE_PREFIX}.
         *
         * <p>
         * ⚑ <b>The population is ZERO, and this branch exists anyway.</b> Every armed kind is
         * measured at zero over both of D102c's populations, and the three corpus rules that could
         * reach {@code MATCHED_FLAG_UNRESOLVABLE} — the D118 join-flag consumers — each declare
         * their joined dataset in {@code Requirements.Variables}, which makes the absence a SKIP
         * rather than a bind error. So this is a latent channel, exactly as
         * {@link #COLUMN_TYPE_MISMATCH} was when it was added. What makes it more than dead code is
         * {@code StageBErrorReasonReachabilityTest}, which drives the real gate and classifies the
         * message the <em>engine</em> built — no literal is typed on the test side, so a reworded
         * prefix reds there instead of degrading every stage-B ERROR to {@link #OTHER} in silence.
         * </p>
         */
        STAGE_B,
        /**
         * An {@code ERROR} this classifier does not recognise — including a null status message.
         */
        OTHER
    }

    /**
     * Verbatim copy of the RELREC/key-expansion message in {@code RuleRunner}. Matched exactly, not
     * by substring, so a reworded engine message degrades to {@link ErrorReason#OTHER} — a visible
     * divergence — rather than silently mis-classifying. {@code ErrorReasonEngineContractTest} pins
     * this against the engine's real behaviour so the drift is caught at build time.
     */
    static final String RELREC_KEY_EXPANSION_MESSAGE = "forward RELREC join combined with a key-based Match_Datasets row "
            + "expansion is not supported on the same rule";

    /** Verbatim copy of the missing-native-checkExpr message in {@code RuleRunner}. */
    static final String NO_NATIVE_CHECK_EXPR_MESSAGE = "Rule error — the Check has no native expression form; the "
            + "legacy evaluator has been retired";

    /**
     * Verbatim copy of the fixed <b>tail</b> of the split-domain union-failure message built in
     * {@code SplitDomainResolution.unionOf} ({@code Fix #358}). The head is data-dependent (it
     * names the domain, the clashing column and the two members), so — unlike the two constants
     * above — this one is matched by {@code endsWith}. Still engine-fixed text: a reworded tail
     * degrades to {@link ErrorReason#OTHER}, a visible divergence.
     */
    static final String INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX = "; the domain cannot be joined";

    /**
     * Verbatim copy of the fixed prefix every {@code ColumnTypeMismatchException} message opens
     * with ({@code ColumnTypeGate}, three builder sites, pinned there by
     * {@code EngineErrorMessageContractTest}).
     * <p>
     * ⚠ Matched by <b>prefix</b>, not exactly, and that is forced rather than sloppy: the message
     * names the offending variable and both types, so there is no fixed string to compare. Same
     * shape as {@link #INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX}'s suffix match. A reworded prefix
     * degrades to {@link ErrorReason#OTHER} — a visible divergence — rather than a silent
     * misclassification.
     */
    static final String COLUMN_TYPE_MISMATCH_MESSAGE_PREFIX = "column-type mismatch: ";

    /**
     * Verbatim copy of the fixed prefix {@code RuleRunner.mapStageBReport} builds every armed
     * stage-B message with (one builder site, pinned there by {@code EngineErrorMessageContractTest
     * .theStageBPrefixIsStillTheEngineSWording}).
     * <p>
     * ⚠ Matched by <b>prefix</b> for the same forced reason as
     * {@link #COLUMN_TYPE_MISMATCH_MESSAGE_PREFIX}: the tail is the joined {@code toString()} of
     * the armed findings, which name the kind, the binding and the rule-specific text, so there is
     * no fixed string to compare. A reworded prefix degrades to {@link ErrorReason#OTHER} — a
     * visible divergence — rather than a silent misclassification.
     */
    static final String STAGE_B_MESSAGE_PREFIX = "stage B: ";

    /**
     * Classifies an execution result into the closed {@link ErrorReason} vocabulary and returns the
     * token names, sorted and de-duplicated.
     *
     * <p>
     * Contract, and the reason this exists rather than diffing {@link #errorsFrom}:
     * <ul>
     * <li><b>Total over ERROR-ness.</b> The returned list is non-empty <i>if and only if</i> the
     * result's status is {@code ERROR}. {@link #errorsFrom} is not: an {@code ERROR} carrying a
     * {@code null} status message yields an empty message list, so message-cardinality is
     * <i>not</i> a faithful proxy for "did this error". Here that case classifies as
     * {@link ErrorReason#OTHER}.</li>
     * <li><b>Prose-independent.</b> Two of the three concrete reasons are recognised by exact match
     * against the engine's fixed strings; {@link ErrorReason#LOAD_ERROR} is recognised
     * structurally, by the status message being the rule's own {@code loadError}, which is authored
     * text and therefore never matchable as a constant.</li>
     * <li><b>Order-insensitive.</b> Sorted + de-duplicated, so the comparison asserts <i>which</i>
     * reasons occurred, not how many datasets produced each. The Python lane normalises the same
     * way (see {@code engine_adapter._normalise_reasons}).</li>
     * </ul>
     *
     * @param result
     *            the execution result; {@code null} or non-{@code ERROR} yields an empty list
     * @param rule
     *            the rule that produced it, used only to recognise {@code LOAD_ERROR}; may be
     *            {@code null}, in which case a load error degrades to {@link ErrorReason#OTHER}
     */
    public static List<String> reasonsFrom(RuleExecutionResult result, @Nullable Rule rule)
    {
        if (result == null || result.getStatus() != RuleExecutionStatus.ERROR)
        {
            return List.of();
        }
        return List.of(classify(result.getStatusMessage(), rule).name());
    }


    private static ErrorReason classify(@Nullable String statusMessage, @Nullable Rule rule)
    {
        if (statusMessage == null)
        {
            // An ERROR with no message still errored; say so rather than returning nothing.
            return ErrorReason.OTHER;
        }
        if (RELREC_KEY_EXPANSION_MESSAGE.equals(statusMessage))
        {
            return ErrorReason.RELREC_KEY_EXPANSION;
        }
        if (NO_NATIVE_CHECK_EXPR_MESSAGE.equals(statusMessage))
        {
            return ErrorReason.NO_NATIVE_CHECK_EXPR;
        }
        if (statusMessage.endsWith(INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX))
        {
            return ErrorReason.INVALID_SPLIT_DOMAIN;
        }
        if (statusMessage.startsWith(COLUMN_TYPE_MISMATCH_MESSAGE_PREFIX))
        {
            return ErrorReason.COLUMN_TYPE_MISMATCH;
        }
        if (statusMessage.startsWith(STAGE_B_MESSAGE_PREFIX))
        {
            return ErrorReason.STAGE_B;
        }
        // Checked last: a load error's text is authored, so it could in principle collide with a
        // fixed engine message. Preferring the fixed messages keeps the classification stable.
        if (rule != null && statusMessage.equals(rule.getLoadError()))
        {
            return ErrorReason.LOAD_ERROR;
        }
        return ErrorReason.OTHER;
    }
}
