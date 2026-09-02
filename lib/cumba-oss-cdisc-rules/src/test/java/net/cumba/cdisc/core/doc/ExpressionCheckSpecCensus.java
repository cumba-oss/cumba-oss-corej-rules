package net.cumba.cdisc.core.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.cumba.cdisc.core.expr.BuiltinRegistry;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.eval.ExprCompiler;
import net.cumba.cdisc.core.expr.eval.FunctionDescriptor;
import net.cumba.cdisc.core.expr.eval.FunctionRegistry;
import net.cumba.cdisc.core.expr.eval.MetadataAttribute;
import net.cumba.cdisc.core.model.OperationType;

/**
 * Generates the {@code expression-syntax-rosters} region of
 * {@code documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md} and the machine-readable
 * {@code documentation/expression-syntax-inventory.tsv} — the derived half of the expression-check
 * specification ({@code plans/done/PLAN-expression-docs-restructure.md} §4).
 *
 * <p>
 * <b>Why this exists.</b> The expression surface is defined by six code registries (functions,
 * compiler-level boolean calls, operations, metadata accessors, builtin operand names, and the
 * sealed {@code Expr} AST's operator/literal/operand kinds). Hand-curating that inventory is
 * exactly how documentation rots: the surface grew {@code _matched_}-style proposals,
 * {@code keep_missings}, date-bound selection and arithmetic within weeks. This census transcribes
 * the registries; {@link ExpressionCheckSpecDriftTest} fails the build whenever the committed
 * region stops matching, and separately asserts every roster token carries a documented,
 * marker-bearing entry in the hand-written half of the specification. Same guard, same reasoning as
 * {@link RuleDocCensus} / {@code RuleDocCensusDriftTest} on the rules specification.
 * </p>
 *
 * <h2>Regenerating</h2>
 * <p>
 * {@code lib/corej-cdisc-rules/scripts/gen-expression-check-spec.sh}, or {@code main(args)} with
 * the document path. The TSV is rewritten next to the document on every run.
 * </p>
 */
public final class ExpressionCheckSpecCensus
{

    private ExpressionCheckSpecCensus()
    {
    }

    /** Region begin marker (the drift test and the generator must agree on it). */
    public static final String BEGIN_MARKER = "<!-- BEGIN GENERATED: expression-syntax-rosters -->";

    /** Region end marker. */
    public static final String END_MARKER = "<!-- END GENERATED: expression-syntax-rosters -->";

    /** Inventory categories, in render order. Keys double as the TSV {@code category} column. */
    public static final String CAT_FUNCTION = "function";

    /** Compiler-dispatched boolean calls ({@link ExprCompiler#HARDCODED_BOOLEAN_CALLS}). */
    public static final String CAT_BOOLEAN_CALL = "boolean-call";

    /** Declarable / inlinable operations ({@link OperationType}). */
    public static final String CAT_OPERATION = "operation";

    /** {@code var_*} / {@code ds_*} metadata accessor functions ({@link MetadataAttribute}). */
    public static final String CAT_ACCESSOR = "metadata-accessor";

    /** Builtin operand reference names ({@link BuiltinRegistry}). */
    public static final String CAT_BUILTIN_OPERAND = "builtin-operand";

    /** Binary operator surface tokens ({@link Expr.BinOp}). */
    public static final String CAT_BINARY_OPERATOR = "binary-operator";

    /** Literal kinds ({@link Expr.LitKind}). */
    public static final String CAT_LITERAL_KIND = "literal-kind";

    /** Operand kinds ({@link OperandKind}). */
    public static final String CAT_OPERAND_KIND = "operand-kind";

    /**
     * The compiler-structural comparison markers. Recognised structurally by {@code ExprCompiler}'s
     * binary-comparison handling (no runtime {@code EvalFunction}, no registry) — mirrored here
     * exactly as the retired {@code ExpressionDocCoverageTest} hard-coded them; a new marker must
     * be added to this set and to the specification together.
     */
    public static final String CAT_MARKER = "comparison-marker";

    private static final List<String> COMPARISON_MARKERS = List.of("date", "date_part", "time_part",
            "num", "str");

    /**
     * Compiler-dispatched calls that live in neither {@code FunctionRegistry} nor
     * {@link ExprCompiler#HARDCODED_BOOLEAN_CALLS}: the ten {@code vlm_*} value-level-metadata
     * accessors (string-switch dispatch in {@code ExprCompiler}'s VLM branch), the two per-record
     * pairing predicates, the {@code max_value_length} value call, and the two positive-form
     * surfaces authored under {@code not}. <b>Must mirror {@code ExprCompiler}'s dispatch</b> — the
     * same stance as {@code HARDCODED_BOOLEAN_CALLS}' "must mirror compileBoolCall" contract.
     */
    public static final String CAT_COMPILER_CALL = "compiler-call";

    private static final List<String> COMPILER_CALLS = List.of("vlm_data_type", "vlm_length",
            "vlm_mandatory", "vlm_codelist_coded_values", "vlm_codelist_coded_codes",
            "vlm_type_conforms", "vlm_codelist_extensible", "vlm_value_length", "vlm_has_codelist",
            "vlm_decode_matches", "library_variable_code_pair_matches",
            "define_variable_decode_matches", "max_value_length", "is_sorted_by",
            "has_next_corresponding_record");

    /**
     * The surface token of a binary operator. An exhaustive switch on purpose: a new
     * {@link Expr.BinOp} constant fails compilation here, so it cannot ship undocumented.
     *
     * @param op
     *            the AST operator
     * @return the token as authored in expression text
     */
    public static String binOpToken(Expr.BinOp op)
    {
        return switch (op)
        {
        case EQ -> "==";
        case NEQ -> "!=";
        case LT -> "<";
        case GT -> ">";
        case LE -> "<=";
        case GE -> ">=";
        case MATCH -> "=~";
        case NMATCH -> "!~";
        case IN -> "in";
        case NOT_IN -> "not in";
        case ADD -> "+";
        case SUB -> "-";
        case MUL -> "*";
        case DIV -> "/";
        };
    }


    /**
     * The full inventory: category → (token → detail). Categories iterate in render order, tokens
     * in sorted order, so the render — and therefore the drift pin — is deterministic.
     *
     * @return the inventory, freshly computed from the registries
     */
    public static Map<String, Map<String, String>> inventory()
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();

        Map<String, String> functions = new TreeMap<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            functions.merge(d.name(), "arity " + d.arity(),
                    (a, b) -> a.contains(b) ? a : a + ", " + b);
        }
        out.put(CAT_FUNCTION, functions);

        Map<String, String> booleanCalls = new TreeMap<>();
        for (String name : ExprCompiler.HARDCODED_BOOLEAN_CALLS)
        {
            booleanCalls.put(name, "compiler-dispatched boolean call");
        }
        out.put(CAT_BOOLEAN_CALL, booleanCalls);

        Map<String, String> operations = new TreeMap<>();
        for (OperationType t : OperationType.values())
        {
            operations.put(t.getJsonValue(), "OperationType." + t.name());
        }
        out.put(CAT_OPERATION, operations);

        Map<String, String> accessors = new TreeMap<>();
        for (MetadataAttribute a : MetadataAttribute.values())
        {
            accessors.merge(a.functionName(), "MetadataAttribute." + a.name(),
                    (x, y) -> x + ", " + y);
        }
        out.put(CAT_ACCESSOR, accessors);

        Map<String, String> operands = new TreeMap<>();
        for (String name : BuiltinRegistry.names())
        {
            operands.put(name, "builtin operand reference");
        }
        out.put(CAT_BUILTIN_OPERAND, operands);

        Map<String, String> binOps = new TreeMap<>();
        for (Expr.BinOp op : Expr.BinOp.values())
        {
            binOps.put(binOpToken(op), "Expr.BinOp." + op.name());
        }
        out.put(CAT_BINARY_OPERATOR, binOps);

        Map<String, String> litKinds = new TreeMap<>();
        for (Expr.LitKind k : Expr.LitKind.values())
        {
            litKinds.put(k.name(), "Expr.LitKind");
        }
        out.put(CAT_LITERAL_KIND, litKinds);

        Map<String, String> operandKinds = new TreeMap<>();
        for (OperandKind k : OperandKind.values())
        {
            operandKinds.put(k.name(), "OperandKind");
        }
        out.put(CAT_OPERAND_KIND, operandKinds);

        Map<String, String> markers = new TreeMap<>();
        for (String m : COMPARISON_MARKERS)
        {
            markers.put(m, "compiler-structural comparison marker");
        }
        out.put(CAT_MARKER, markers);

        Map<String, String> compilerCalls = new TreeMap<>();
        for (String c : COMPILER_CALLS)
        {
            compilerCalls.put(c, "ExprCompiler dispatch (mirrored)");
        }
        out.put(CAT_COMPILER_CALL, compilerCalls);

        return out;
    }


    /** Human-readable heading per category, in the roster render. */
    private static String heading(String category)
    {
        return switch (category)
        {
        case CAT_FUNCTION -> "Registered functions (`FunctionRegistry`)";
        case CAT_BOOLEAN_CALL -> "Compiler-dispatched boolean calls"
                + " (`ExprCompiler.HARDCODED_BOOLEAN_CALLS`)";
        case CAT_OPERATION -> "Operations (`OperationType`)";
        case CAT_ACCESSOR -> "Metadata accessor functions (`MetadataAttribute`)";
        case CAT_BUILTIN_OPERAND -> "Builtin operand references (`BuiltinRegistry`)";
        case CAT_BINARY_OPERATOR -> "Binary operators (`Expr.BinOp`)";
        case CAT_LITERAL_KIND -> "Literal kinds (`Expr.LitKind`)";
        case CAT_OPERAND_KIND -> "Operand kinds (`OperandKind`)";
        case CAT_MARKER -> "Comparison markers (compiler-structural)";
        case CAT_COMPILER_CALL -> "Compiler-dispatched value / predicate calls (mirrored)";
        default -> throw new IllegalArgumentException("unknown category " + category);
        };
    }


    /**
     * Renders the generated region — one compact roster table per category. Editorial content
     * (syntax, examples, the {@code authorable | internal | machine-emitted} markers) lives in the
     * hand-written chapters, never here, so regeneration can never lose it.
     *
     * @return the full region text including both markers, ending with a newline
     */
    public static String render()
    {
        StringBuilder b = new StringBuilder(BEGIN_MARKER).append('\n');
        b.append("\n> Machine-derived rosters — regenerate with"
                + " `lib/corej-cdisc-rules/scripts/gen-expression-check-spec.sh`. The tokens"
                + " below are the completeness contract: every one has a documented, marked entry"
                + " in the chapters of this specification"
                + " (`ExpressionCheckSpecDriftTest`).\n");
        Map<String, Map<String, String>> inv = inventory();
        for (Map.Entry<String, Map<String, String>> category : inv.entrySet())
        {
            b.append("\n### Roster: ").append(heading(category.getKey())).append(" — ")
                    .append(category.getValue().size()).append(" entries\n\n");
            b.append("| token | source |\n|---|---|\n");
            for (Map.Entry<String, String> e : category.getValue().entrySet())
            {
                b.append("| `").append(e.getKey()).append("` | ").append(e.getValue())
                        .append(" |\n");
            }
        }
        b.append('\n').append(END_MARKER);
        return b.toString();
    }


    /** The TSV form of {@link #inventory()} ({@code token<TAB>category<TAB>detail} + header). */
    public static String renderTsv()
    {
        StringBuilder b = new StringBuilder("token\tcategory\tdetail\n");
        for (Map.Entry<String, Map<String, String>> category : inventory().entrySet())
        {
            for (Map.Entry<String, String> e : category.getValue().entrySet())
            {
                b.append(e.getKey()).append('\t').append(category.getKey()).append('\t')
                        .append(e.getValue()).append('\n');
            }
        }
        return b.toString();
    }


    /** The specification document, resolved against {@code projectBasedir}. */
    public static Path documentFile()
    {
        return Path.of(System.getProperty("projectBasedir", "."))
                .resolve("documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md");
    }


    /** The TSV, resolved next to the document. */
    public static Path tsvFile()
    {
        return Path.of(System.getProperty("projectBasedir", "."))
                .resolve("documentation/expression-syntax-inventory.tsv");
    }


    /**
     * The committed generated region of {@code doc}, markers included.
     *
     * @param doc
     *            the document text
     * @return the region substring
     * @throws IllegalArgumentException
     *             when the markers are missing, duplicated, or inverted
     */
    public static String region(String doc)
    {
        int begin = doc.indexOf(BEGIN_MARKER);
        int end = doc.indexOf(END_MARKER);
        if (begin < 0 || end < 0)
        {
            throw new IllegalArgumentException("the document is missing the generated-region"
                    + " markers " + BEGIN_MARKER + " / " + END_MARKER);
        }
        if (doc.indexOf(BEGIN_MARKER, begin + 1) >= 0 || doc.indexOf(END_MARKER, end + 1) >= 0)
        {
            throw new IllegalArgumentException("duplicated generated-region markers");
        }
        if (end < begin)
        {
            throw new IllegalArgumentException("the generated-region END marker precedes BEGIN");
        }
        return doc.substring(begin, end + END_MARKER.length());
    }


    /** {@code doc} with the generated region removed (for hand-written-half scans). */
    public static String withoutRegion(String doc)
    {
        return doc.replace(region(doc), "");
    }


    /**
     * Regenerates the region of the named document in place and rewrites the TSV.
     *
     * @param args
     *            {@code args[0]} — the document path (defaults to {@link #documentFile()})
     * @throws IOException
     *             on any read/write failure
     */
    public static void main(String[] args) throws IOException
    {
        Path doc = args.length > 0 ? Path.of(args[0]) : documentFile();
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        String updated = text.replace(region(text), render());
        Files.writeString(doc, updated, StandardCharsets.UTF_8);
        Path tsv = args.length > 0 ? doc.getParent().resolve("expression-syntax-inventory.tsv")
                : tsvFile();
        Files.writeString(tsv, renderTsv(), StandardCharsets.UTF_8);
        List<String> counts = new ArrayList<>();
        inventory().forEach((c, m) -> counts.add(c + "=" + m.size()));
        System.out.println("[expression-check-spec] regenerated " + doc + " region + " + tsv + " ("
                + String.join(", ", counts) + ")");
    }

}
