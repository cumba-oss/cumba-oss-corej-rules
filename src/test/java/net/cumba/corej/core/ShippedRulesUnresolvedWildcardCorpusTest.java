package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Fix #156 — the corpus ratchet for {@code RulePackageLoader.validateUnresolvedOperationWildcards}.
 *
 * <p>
 * An Operation that parks the {@code --} wildcard in {@code reference}, {@code ordering} or
 * {@code offset} is silent at runtime: those three fields are copied verbatim by
 * {@code OperationExecutor.resolvePrefixes}, so the literal {@code "--SEQ"} reaches
 * {@code DataTableMeta.getColumnIndex}, misses, and the operation produces nothing (or, for
 * {@code offset}, silently applies {@code 0}). The shipped corpus is held at <b>zero</b>.
 * </p>
 *
 * <p>
 * <b>Measured when this test was written (2026-08-05).</b> In the shipped {@code rules/} folder the
 * three guarded fields carry a {@code --} <b>nowhere</b>: 36 {@code reference=}, 29
 * {@code ordering=} and 30 {@code offset=} uses, every one a bare column name or an integer
 * literal. {@code minuend_match} / {@code minuend_domain} are authored by no shipped rule at all.
 * </p>
 *
 * <p>
 * ⚠ <b>A zero ratchet cannot prove the pass detects anything</b> — the lesson of
 * {@code ShippedRulesDanglingOperandCorpusTest}, whose expected set emptied and took that proof
 * with it. So {@link #theRatchetIsLive_positiveControl()} below drives a synthetic rule through the
 * <em>same</em> {@code RulePackageLoader} entry point and requires it to be caught. If the corpus
 * assertions pass while that one fails, the zero above is an artefact of a dead pass, not a clean
 * corpus. ⚠ A second shipped folder was walked here too, and its agreement with this one was a free
 * cross-check on the walk; with it gone the positive control and the floors in
 * {@link #assertFolder} are the whole of the anti-vacuity argument.
 * </p>
 *
 * <p>
 * The positive control also names the fields that ARE resolved ({@code name}, {@code group},
 * {@code dictionary_parent}, …) and the one deliberately left alone ({@code minuend_match}, whose
 * {@code --SPID} tokens are resolved per side at evaluation time); the unit-level proof for those
 * boundaries lives in {@code UnresolvedOperationWildcardLoadTest} (corej-cdisc-core).
 * </p>
 */
class ShippedRulesUnresolvedWildcardCorpusTest
{

    /** The load-finding fragment {@code validateUnresolvedOperationWildcards} emits. */
    private static final String MARKER = "is not `--`-resolved";

    @Test
    void expressionCorpus_hasNoUnresolvedOperationWildcards() throws IOException
    {
        assertFolder(CorpusPaths.rules());
    }


    /**
     * ⚠ <b>Do not delete: this is what makes the zero above mean something.</b> Neutering
     * {@code validateUnresolvedOperationWildcards} leaves the corpus test green and turns this one
     * red (measured 2026-08-05).
     */
    @Test
    void theRatchetIsLive_positiveControl() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString("""
                {"rules": {"probe": {
                  "Core": {"Id": "PROBE-UOW"},
                  "Operations": [{"id": "$last", "operator": "is_last_in_group",
                                  "group": ["USUBJID"], "ordering": "--SEQ"}],
                  "Check": {"all": [{"name": "$last", "operator": "equal_to", "value": true}]}
                }}}
                """);
        Rule probe = pkg.getRules().values().iterator().next();
        assertNotNull(probe.getLoadError(),
                "the pass that produces the zero above must still detect a planted `--`");
        assertTrue(probe.getLoadError().contains(MARKER), probe.getLoadError());
    }


    private static void assertFolder(Path folder) throws IOException
    {
        List<String> findings = new ArrayList<>();
        int packages = 0;
        for (Path file : listRulePackages(folder))
        {
            packages++;
            RulePackage pkg = RulePackageLoader.load(file);
            for (Rule rule : pkg.getRules().values())
            {
                if (rule == null)
                {
                    continue;
                }
                collect(findings, file, rule, rule.getLoadError());
                collect(findings, file, rule, rule.getLoadWarning());
            }
        }
        // ⚠ The corpus-SIZE floor that stood here was removed when this repository was
        // extracted: it asserted the FULL internal corpus (>= 56 packages), which this
        // subset deliberately is not. The internal repository still enforces the size.
        // What is kept is the non-vacuity half, which is subset-safe: an empty or
        // mis-resolved corpus must red rather than report a clean pass over nothing.
        assertTrue(packages > 0, "no rule packages found in " + folder + "/ —"
                + " the corpus did not generate; a pass over nothing is not a check");
        assertTrue(findings.isEmpty(),
                "shipped rules whose Operation parks a `--` in reference / ordering / offset —"
                        + " resolvePrefixes copies those verbatim, so the operation reads a"
                        + " non-existent column and the rule silently checks nothing ("
                        + findings.size() + "):\n" + String.join("\n", findings));
    }


    private static void collect(List<String> findings, Path file, Rule rule,
            @org.jspecify.annotations.Nullable String finding)
    {
        if (finding != null && finding.contains(MARKER))
        {
            findings.add(file.getFileName() + "/" + ruleId(rule) + ": " + finding);
        }
    }


    private static List<Path> listRulePackages(Path folder) throws IOException
    {
        Path dir = folder;
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p ->
            {
                String n = p.getFileName().toString();
                return n.startsWith("rules-") && n.endsWith(".json") && !n.endsWith("-expr.json");
            }).sorted().toList();
        }
    }


    private static String ruleId(Rule rule)
    {
        if (rule.getCore() != null && rule.getCore().getId() != null)
        {
            return rule.getCore().getId();
        }
        return rule.getId() != null ? rule.getId() : "<unknown>";
    }
}
