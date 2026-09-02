package net.cumba.cdisc.core.expr.convert;

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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.cdisc.core.exec.RuleClassifier;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;

/**
 * Offline developer tool — strips {@code Rule_Type} / {@code Sensitivity} from
 * {@code rules-src/checks/*.yaml} wherever {@link RuleClassifier} reproduces the authored value
 * ({@code PLAN-derive-rule-type-sensitivity} phase 5, step 2).
 *
 * <p>
 * The fields stay in {@code rules-legacy}, which the Python reference engine loads and which needs
 * them explicitly; {@link RuleStorageAssembler} re-inserts the derived value on the way out. What
 * this tool removes is the <em>authored duplication</em> in the hand-edited source.
 * </p>
 *
 * <p>
 * A rule whose derivation disagrees with the authored value keeps both fields — that is the
 * documented override set. Nothing is guessed: disagreement means the author knew something the
 * derivation does not, so the explicit value must survive and win.
 * </p>
 *
 * <p>
 * <b>Textual splice, not a re-serialize.</b> Whole lines are deleted from the YAML so every other
 * byte — quoting, folding, key order, comments — is preserved exactly, the same discipline
 * {@code OriginalTextBackfill} uses. Re-emitting 3713 files through a YAML writer would rewrite
 * formatting wholesale and bury the real change.
 * </p>
 *
 * <pre>{@code
 * cd lib/corej-cdisc-rules
 * mvn -P main -q org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.cumba.cdisc.core.expr.convert.DerivedFieldStripper
 * }</pre>
 */
public final class DerivedFieldStripper
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Path CHECKS_DIR = Path.of("rules-src", "checks");

    /** Matches a whole top-level {@code Rule_Type:} / {@code Sensitivity:} line. */
    private static final Pattern FIELD_LINE = Pattern.compile("^(Rule_Type|Sensitivity): .*$",
            Pattern.MULTILINE);

    private DerivedFieldStripper()
    {
    }


    /**
     * Strips the derivable fields across the corpus and prints a summary.
     *
     * @param args
     *            {@code --dry-run} to report without writing
     * @throws IOException
     *             if the corpus cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        boolean dryRun = args.length > 0 && "--dry-run".equals(args[0]);
        List<String> overrides = new ArrayList<>();
        int stripped = 0;
        int sensitivityKept = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CHECKS_DIR))
        {
            files = walk.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files)
        {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode node = YAML.readTree(text);
            Rule rule = YAML.treeToValue(node, Rule.class);
            String coreId = node.path("Core").path("Id").asText("<unknown>");

            RuleClassifier.Derived<Sensitivity> sensitivity = RuleClassifier
                    .deriveSensitivity(rule);
            // Rule_Type is no longer a rule field (PLAN-leaf-scope-domain-inference phase 7):
            // any surviving line is dropped unconditionally.
            boolean dropType = true;
            boolean dropSensitivity = matches(sensitivity, rule.getSensitivity());

            if (!dropSensitivity)
            {
                sensitivityKept++;
                overrides.add(coreId + "\tSensitivity\t" + text(rule.getSensitivity()) + "\t"
                        + describe(sensitivity));
            }
            if (!dropType && !dropSensitivity)
            {
                continue;
            }
            String updated = strip(text, dropType, dropSensitivity);
            if (!updated.equals(text))
            {
                stripped++;
                if (!dryRun)
                {
                    Files.writeString(file, updated, StandardCharsets.UTF_8);
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "[strip] %d files%s; %d rules edited; kept Sensitivity on %d%n", files.size(),
                dryRun ? " (dry run)" : "", stripped, sensitivityKept);
        overrides.forEach(line -> System.out.println("[override] " + line));
    }


    /** Whether the derivation reproduces the authored value with enough confidence to drop it. */
    private static boolean matches(RuleClassifier.Derived<?> derived, Object authored)
    {
        return derived.confidence() != RuleClassifier.Confidence.NONE && derived.value() != null
                && derived.value().equals(authored);
    }


    /**
     * Deletes the whole {@code Rule_Type} / {@code Sensitivity} lines, preserving every other byte.
     */
    private static String strip(String text, boolean dropType, boolean dropSensitivity)
    {
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = FIELD_LINE.matcher(text);
        int cursor = 0;
        while (matcher.find())
        {
            boolean drop = "Rule_Type".equals(matcher.group(1)) ? dropType : dropSensitivity;
            if (!drop)
            {
                continue;
            }
            out.append(text, cursor, matcher.start());
            // swallow the line terminator too, so no blank line is left behind
            cursor = matcher.end() < text.length() && text.charAt(matcher.end()) == '\n'
                    ? matcher.end() + 1
                    : matcher.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }


    private static String describe(RuleClassifier.Derived<?> derived)
    {
        return text(derived.value()) + " (" + derived.confidence() + ": " + derived.rationale()
                + ")";
    }


    private static String text(Object value)
    {
        return switch (value)
        {
        case null -> "<absent>";
        case Sensitivity sensitivity -> sensitivity.getJsonValue();
        default -> value.toString();
        };
    }
}
