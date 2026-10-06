package net.cumba.corej.core.ruletestsuites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.datatable.provider.cdt.CdtDataset;
import net.cumba.datatable.provider.cdt.CdtDocument;
import net.cumba.datatable.provider.cdt.CdtParser;
import net.cumba.datatable.provider.cdt.CdtType;
import net.cumba.datatable.provider.cdt.CdtValues;
import org.junit.jupiter.api.Test;

/**
 * The guard on {@code CdtDocument}'s rewrite path over the real corpus
 * ({@code PLAN-rule-authoring-api} §3.1, pre-go review M4): for <b>every</b> scenario, every
 * dataset block is <em>forced through a rewrite with unchanged values</em> — a no-op splice would
 * exercise nothing — and then {@code CdtParser.parseAll} must yield value-equal tables,
 * <b>missing-value kind included</b>, and every byte outside the rewritten blocks must be
 * unchanged.
 *
 * <p>
 * Hosted here, not in {@code cumba-datatable-provider-cdt}: the 9 700 scenarios are here, and a
 * guard that only ran over hand-written fixtures would be checking something adjacent to what the
 * editor will rewrite.
 * </p>
 */
class CdtDocumentRewriteGuardTest
{

    private static final Path ROOT = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    /** A floor so a walk that matches nothing cannot pass vacuously. */
    private static final int MIN_FILES = 9_000;

    @Test
    void everyScenarioSurvivesAForcedRewriteOfEveryBlock() throws IOException
    {
        List<String> problems = new ArrayList<>();
        int files = 0;
        int blocks = 0;
        try (Stream<Path> walk = Files.walk(ROOT))
        {
            for (Path p : walk.filter(f -> f.toString().endsWith(".cdt")).sorted().toList())
            {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                List<CdtDataset> before;
                try
                {
                    before = CdtParser.parseAll(text, p.toString());
                }
                catch (RuntimeException e)
                {
                    // a fixture the parser itself refuses is not this guard's subject
                    continue;
                }
                files++;
                CdtDocument doc = CdtDocument.parse(text);
                if (!doc.text().equals(text))
                {
                    problems.add(p + ": parse does not keep the text");
                    continue;
                }
                CdtDocument rewritten = doc;
                for (CdtDataset ds : before)
                {
                    rewritten = rewritten.withDataset(ds);
                    blocks++;
                }
                String out = rewritten.text();
                List<CdtDataset> after;
                try
                {
                    after = CdtParser.parseAll(out, p + " (rewritten)");
                }
                catch (RuntimeException e)
                {
                    problems.add(p + ": the rewrite does not parse: " + e.getMessage());
                    continue;
                }
                String mismatch = compare(before, after);
                if (mismatch != null)
                {
                    problems.add(p + ": " + mismatch);
                    continue;
                }
                String outside = outsideBlocksDiffer(text, doc, out, rewritten);
                if (outside != null)
                {
                    problems.add(p + ": " + outside);
                }
            }
        }
        assertTrue(files >= MIN_FILES, "rewrote " + files + " files, floor " + MIN_FILES);
        assertTrue(blocks > files, "rewrote " + blocks + " blocks over " + files + " files");
        assertEquals(List.of(), problems, problems.size() + " problem(s) in " + files + " files");
    }


    /** Value equality, missing-value kind included, over every cell of every dataset. */
    private static String compare(List<CdtDataset> aBefore, List<CdtDataset> aAfter)
    {
        if (aBefore.size() != aAfter.size())
        {
            return "dataset count " + aBefore.size() + " -> " + aAfter.size();
        }
        for (int d = 0; d < aBefore.size(); d++)
        {
            CdtDataset b = aBefore.get(d);
            CdtDataset a = aAfter.get(d);
            if (!b.getName().equals(a.getName()) || !b.getColumns().equals(a.getColumns())
                    || !b.getAttrs().equals(a.getAttrs())
                    || !java.util.Objects.equals(b.getLabel(), a.getLabel()))
            {
                return "dataset " + b.getName() + ": header or columns differ";
            }
            if (b.getDataRows().size() != a.getDataRows().size())
            {
                return "dataset " + b.getName() + ": row count " + b.getDataRows().size() + " -> "
                        + a.getDataRows().size();
            }
            for (int r = 0; r < b.getDataRows().size(); r++)
            {
                for (int c = 0; c < b.getColumns().size(); c++)
                {
                    CdtType type = b.getColumns().get(c).getType();
                    Object vb = CdtValues.parseValue(b.getDataRows().get(r).get(c), type);
                    Object va = CdtValues.parseValue(a.getDataRows().get(r).get(c), type);
                    if (!vb.equals(va) || vb.getClass() != va.getClass())
                    {
                        return "dataset " + b.getName() + " row " + r + " col "
                                + b.getColumns().get(c).getName() + ": " + vb + " ("
                                + vb.getClass().getSimpleName() + ") -> " + va + " ("
                                + va.getClass().getSimpleName() + ")";
                    }
                }
            }
        }
        return null;
    }


    /** The prelude and the text between / after the blocks must be byte-identical. */
    private static String outsideBlocksDiffer(String aText, CdtDocument aDoc, String aOut,
            CdtDocument aRewritten)
    {
        List<String> before = CdtDocument.splitKeepingTerminators(aText);
        List<String> after = CdtDocument.splitKeepingTerminators(aOut);
        List<CdtDocument.DatasetBlock> b = aDoc.datasets();
        List<CdtDocument.DatasetBlock> a = aRewritten.datasets();
        if (b.size() != a.size())
        {
            return "block count " + b.size() + " -> " + a.size();
        }
        int ib = 0;
        int ia = 0;
        for (int k = 0; k <= b.size(); k++)
        {
            int endB = k < b.size() ? b.get(k).headerLine() : before.size();
            int endA = k < a.size() ? a.get(k).headerLine() : after.size();
            if (!before.subList(ib, endB).equals(after.subList(ia, endA)))
            {
                return "text outside block " + k + " differs";
            }
            if (k < b.size())
            {
                // the dataset line and the fences are kept byte for byte
                if (!before.get(b.get(k).headerLine()).equals(after.get(a.get(k).headerLine())))
                {
                    return "dataset line of block " + k + " differs";
                }
                ib = b.get(k).end();
                ia = a.get(k).end();
            }
        }
        return null;
    }
}
