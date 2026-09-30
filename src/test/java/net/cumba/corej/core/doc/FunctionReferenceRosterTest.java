package net.cumba.corej.core.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.cumba.corej.core.CorpusPaths;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The function reference ({@code rule-guide/reference/functions.md}) is held to the engine's
 * {@link FunctionRegistry}: every registered name must have a {@code ### name} section or be named
 * in backticks there ({@code `name`} or {@code `name(}, how an alias or a negated spelling is
 * documented inside its primary's section). The page is maintained by hand, so without this a newly
 * registered function is invisible to authors while every gate stays green —
 * {@code PLAN-dynamic-column-functions} review round 1 (lane C F7) found {@code date}, {@code time}
 * and two metadata accessors undocumented that way.
 *
 * <p>
 * Non-vacuity, checked on every run: the registry and the page each carry a floor of names (a
 * registry or page read as empty cannot pass), a fabricated name is NOT found, and the matcher run
 * over the page with one real function's heading and mentions removed reports that function missing
 * — so the check can fail, and fails on exactly what it claims to check.
 * </p>
 */
class FunctionReferenceRosterTest
{

    /** A floor, not a count: the registry held 164 names on 2026-09-30. */
    private static final int MIN_REGISTERED = 150;

    /** A floor on the page's {@code ###} sections. */
    private static final int MIN_SECTIONS = 150;

    private static Path page()
    {
        return CorpusPaths.moduleDir().resolve("rule-guide/reference/functions.md");
    }


    /** Whether {@code doc} documents {@code name}: a heading, or a backticked mention. */
    static boolean documents(String doc, String name)
    {
        String q = Pattern.quote(name);
        return Pattern.compile("(?m)^### " + q + "\\s*$").matcher(doc).find()
                || Pattern.compile("`" + q + "(`|\\()").matcher(doc).find();
    }


    private static Set<String> undocumented(String doc, List<String> names)
    {
        Set<String> missing = new TreeSet<>();
        for (String name : names)
        {
            if (!documents(doc, name))
            {
                missing.add(name);
            }
        }
        return missing;
    }


    @Test
    void everyRegisteredFunctionIsDocumented() throws IOException
    {
        String doc = Files.readString(page());
        List<String> names = FunctionRegistry.all().stream().map(FunctionDescriptor::name)
                .distinct().sorted().toList();
        assertTrue(names.size() >= MIN_REGISTERED, "the registry lists " + names.size()
                + " functions, below the floor " + MIN_REGISTERED);
        long sections = doc.lines().filter(l -> l.startsWith("### ")).count();
        assertTrue(sections >= MIN_SECTIONS,
                "functions.md has " + sections + " ### sections, below the floor " + MIN_SECTIONS);
        assertEquals(Set.of(), undocumented(doc, names),
                "registered functions with neither a `### name` section nor a backticked mention in "
                        + page() + " — document them (a section, or an alias mention in the"
                        + " primary's section)");
    }


    /** The instrument can fail: a fabricated name, and a real one with its mentions removed. */
    @Test
    void theRosterCheckSeesAMissingFunction() throws IOException
    {
        String doc = Files.readString(page());
        assertEquals(Set.of("zz_no_such_function"),
                undocumented(doc, List.of("zz_no_such_function", "printf")),
                "a fabricated name must be reported, a documented one not");
        String withoutLpad = doc.replaceAll("(?m)^### lpad\\s*$", "### removed")
                .replace("`lpad(", "`removed(").replace("`lpad`", "`removed`");
        assertEquals(Set.of("lpad"), undocumented(withoutLpad, List.of("lpad", "printf")),
                "removing lpad's heading and mentions must make it undocumented");
    }
}
