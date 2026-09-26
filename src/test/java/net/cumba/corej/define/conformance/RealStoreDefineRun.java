package net.cumba.corej.define.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.core.run.DefineStoreBinding;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.Requires;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;

/**
 * Shared plumbing of the two real-store Define-XML tests ({@link CtRulesRealCtTest},
 * {@link LibraryRulesRealStoreTest}, PLAN-define-ct-evaluation §6 N3 / N3L): evaluate the authored
 * corpus's {@code Requires: ct} or {@code Requires: library} rules against a document bound through
 * {@link DefineStoreBinding} over the pickle-seeded store, render each finding as one comparable
 * line, and write the raw findings where {@code scripts/define-real-store-oracle.py --engine} reads
 * them for adjudication.
 *
 * <p>
 * A finding line is {@code "<rule id> <location> = <value>"}: the location is the finding's xpath
 * below the {@code MetaDataVersion} (the document prefix carries nothing a reader needs), and the
 * value is the {@code ${value}} the rule's Message template was rendered with — recovered from the
 * message, so the pin names the offending value rather than the whole sentence. A template with no
 * {@code ${value}} (DEFINE-XML-0132, PMDA-DD0055-C) yields the whole message instead.
 * </p>
 */
final class RealStoreDefineRun
{

    private static final String VALUE_TOKEN = "${value}";

    private static final String MDV = "MetaDataVersion[";

    private RealStoreDefineRun()
    {
    }


    /**
     * A fixture under {@code src/test/resources/fixtures/} as a file path (the binding reads it).
     */
    static Path fixture(String aName)
    {
        URL url = Objects.requireNonNull(RealStoreDefineRun.class.getResource("/fixtures/" + aName),
                aName);
        try
        {
            return Path.of(url.toURI());
        }
        catch (URISyntaxException e)
        {
            throw new IllegalStateException(aName, e);
        }
    }


    /** A variant of a fixture, written to {@code aDir}, with each {@code old -> new} applied. */
    static Path variant(String aName, Path aDir, String... aOldNewPairs)
    {
        try
        {
            String text = Files.readString(fixture(aName), StandardCharsets.UTF_8);
            for (int i = 0; i < aOldNewPairs.length; i += 2)
            {
                String replaced = text.replace(aOldNewPairs[i], aOldNewPairs[i + 1]);
                if (replaced.equals(text))
                {
                    throw new IllegalStateException(
                            "variant of " + aName + ": '" + aOldNewPairs[i] + "' matched nothing");
                }
                text = replaced;
            }
            Path out = aDir.resolve(aName);
            Files.writeString(out, text, StandardCharsets.UTF_8);
            return out;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /**
     * The corpus rules of one {@link Requires} kind applicable at {@code aVersion}, by id — the
     * roster the tests compare against their own constant, so a rule added to or parked out of the
     * corpus reds the roster instead of silently widening or narrowing the run.
     */
    static Map<String, ConformanceRule> corpusRules(Requires aRequires, String aVersion)
    {
        Map<String, ConformanceRule> rules = new TreeMap<>();
        Stream.concat(TestRules.load(TestRules.CDISC).stream(),
                TestRules.load(TestRules.PMDA).stream())
                .filter(r -> r.requires() == aRequires && r.applicableVersions().contains(aVersion))
                .forEach(r -> rules.put(r.ruleId(), r));
        return rules;
    }


    /** Evaluates {@code aRules} against the document with the binding's CT and library halves. */
    static Map<String, RuleResult> evaluate(Path aDefine, String aVersion,
            DefineStoreBinding aBinding, Map<String, ConformanceRule> aRules)
    {
        DocumentContext context;
        try (InputStream in = Files.newInputStream(aDefine))
        {
            context = new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), aVersion,
                    aBinding.ctProvider().orElse(null), null, List.of(),
                    aBinding.libraryProvider().orElse(null));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load " + aDefine, e);
        }
        RuleEvaluator evaluator = new RuleEvaluator();
        Map<String, RuleResult> results = new TreeMap<>();
        aRules.forEach((id, rule) -> results.put(id, evaluator.evaluate(rule, context)));
        return results;
    }


    /** Every rule of the run EXECUTED — a skip would make its (empty) findings vacuous. */
    static void assertAllExecuted(Map<String, RuleResult> aResults)
    {
        Set<String> notExecuted = new TreeSet<>();
        aResults.forEach((id, result) ->
        {
            if (result.status() != ExecutionStatus.EXECUTED)
            {
                notExecuted.add(id + "=" + result.status());
            }
        });
        assertEquals(Set.of(), notExecuted, "rules that did not execute");
    }


    /** Sorted finding lines, {@code "<rule id> <location> = <value>"}. */
    static List<String> findingLines(Map<String, RuleResult> aResults)
    {
        List<String> lines = new ArrayList<>();
        for (RuleResult result : aResults.values())
        {
            for (ConformanceFinding finding : result.findings())
            {
                lines.add(result.rule().ruleId() + " " + location(finding.getXpath()) + " = "
                        + value(result.rule().message(), finding.getMessage()));
            }
        }
        lines.sort(null);
        return lines;
    }


    /**
     * Writes {@code target/define-real-store/<case>.tsv} — rule, xpath, message — for
     * {@code scripts/define-real-store-oracle.py --engine}. Written before any assertion, so a red
     * run leaves its findings behind for adjudication.
     */
    static void dump(String aCase, Map<String, RuleResult> aResults)
    {
        StringBuilder tsv = new StringBuilder("rule\txpath\tmessage\n");
        for (RuleResult result : aResults.values())
        {
            for (ConformanceFinding finding : result.findings())
            {
                tsv.append(result.rule().ruleId()).append('\t').append(finding.getXpath())
                        .append('\t').append(finding.getMessage()).append('\n');
            }
        }
        Path dir = Path.of(System.getProperty("projectBasedir", "."), "target",
                "define-real-store");
        try
        {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(aCase + ".tsv"), tsv, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    private static String location(String aXpath)
    {
        int mdv = aXpath.indexOf(MDV);
        if (mdv < 0)
        {
            return aXpath;
        }
        int close = aXpath.indexOf("']", mdv);
        return close < 0 ? aXpath : aXpath.substring(close + 2);
    }


    /** The {@code ${value}} a message was rendered with, or the whole message if it has none. */
    private static String value(String aTemplate, String aMessage)
    {
        int at = aTemplate.indexOf(VALUE_TOKEN);
        if (at < 0)
        {
            return aMessage;
        }
        String prefix = aTemplate.substring(0, at);
        String suffix = aTemplate.substring(at + VALUE_TOKEN.length());
        if (!aMessage.startsWith(prefix) || !aMessage.endsWith(suffix)
                || aMessage.length() < prefix.length() + suffix.length())
        {
            return aMessage;
        }
        return aMessage.substring(prefix.length(), aMessage.length() - suffix.length());
    }
}
