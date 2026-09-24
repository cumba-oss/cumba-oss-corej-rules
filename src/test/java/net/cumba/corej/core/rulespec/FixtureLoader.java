package net.cumba.corej.core.rulespec;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;

/**
 * Builds {@link IDataTable} instances from {@link SpecSchema.SpecDataset} declarations.
 *
 * <p>
 * M1 scope: inline {@code rows:} only. {@code $ref} to CSV/JSON/XPT is deferred until an M2 spec
 * actually needs it (the dependencies — provider modules — are not on the classpath yet, so adding
 * them eagerly would bloat the test surface for no current consumer).
 *
 * <p>
 * Type inference: when {@code column_types} is omitted, the first non-null value in each column
 * drives the type pick (Long → LONG, Double → DOUBLE, Boolean → BOOLEAN, otherwise STRING).
 * Explicit hints always win.
 *
 * <p>
 * Row-key conventions:
 * <ul>
 * <li>{@code __row_number__} in {@code row_key} ⇒ a virtual zero-based LONG column is added to the
 * table at load time.</li>
 * <li>Other row-key columns must already exist in the inline rows; this is not enforced here —
 * failures surface later as missing-column errors.</li>
 * </ul>
 */
public final class FixtureLoader
{

    public static final String VIRTUAL_ROW_NUMBER_COLUMN = "__row_number__";

    private FixtureLoader()
    {
    }


    public static Map<String, IDataTable> build(Map<String, SpecSchema.SpecDataset> datasets)
    {
        if (datasets == null || datasets.isEmpty())
        {
            return Map.of();
        }
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        for (Map.Entry<String, SpecSchema.SpecDataset> e : datasets.entrySet())
        {
            tables.put(e.getKey(), buildOne(e.getKey(), e.getValue()));
        }
        return tables;
    }


    private static IDataTable buildOne(String domain, SpecSchema.SpecDataset spec)
    {
        if (spec.ref() != null)
        {
            throw new UnsupportedOperationException(
                    "Dataset $ref loading not implemented yet (M1 inline-only): " + spec.ref());
        }
        List<Map<String, Object>> rows = spec.rows() != null ? spec.rows() : List.of();
        List<String> rowKey = spec.rowKey() != null ? spec.rowKey() : List.of();

        // Column order: union of (a) keys appearing in inline rows in declaration order,
        // (b) any row-key column not yet seen (so __row_number__ can be appended).
        LinkedHashSet<String> columnOrder = new LinkedHashSet<>();
        for (Map<String, Object> row : rows)
        {
            columnOrder.addAll(row.keySet());
        }
        for (String key : rowKey)
        {
            if (!key.equals(VIRTUAL_ROW_NUMBER_COLUMN))
            {
                columnOrder.add(key);
            }
        }
        boolean wantRowNumber = rowKey.contains(VIRTUAL_ROW_NUMBER_COLUMN);
        if (wantRowNumber)
        {
            columnOrder.add(VIRTUAL_ROW_NUMBER_COLUMN);
        }

        OverlayDataTable table = OverlayDataTable.empty(domain, domain, rows.size());
        Map<String, String> typeHints = spec.columnTypes() != null ? spec.columnTypes() : Map.of();
        for (String colName : columnOrder)
        {
            DataValueType type = pickType(colName, rows, typeHints);
            table.addColumn(colName, type, colName);
        }
        // Populate cells from inline rows.
        for (int r = 0; r < rows.size(); r++)
        {
            Map<String, Object> row = rows.get(r);
            for (Map.Entry<String, Object> cell : row.entrySet())
            {
                table.setValue(r, cell.getKey(), cell.getValue());
            }
            if (wantRowNumber)
            {
                table.setValue(r, VIRTUAL_ROW_NUMBER_COLUMN, (long) r);
            }
        }
        return table;
    }


    private static DataValueType pickType(String column, List<Map<String, Object>> rows,
            Map<String, String> hints)
    {
        String hint = hints.get(column);
        if (hint != null)
        {
            return DataValueType.valueOf(hint.toUpperCase(Locale.ROOT));
        }
        if (column.equals(VIRTUAL_ROW_NUMBER_COLUMN))
        {
            return DataValueType.LONG;
        }
        for (Map<String, Object> row : rows)
        {
            Object v = row.get(column);
            if (v != null)
            {
                // The first non-null value decides the column's type.
                return typeOfValue(v);
            }
        }
        return DataValueType.STRING;
    }


    private static DataValueType typeOfValue(Object v)
    {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte)
        {
            return DataValueType.LONG;
        }
        if (v instanceof Double || v instanceof Float)
        {
            return DataValueType.DOUBLE;
        }
        if (v instanceof Boolean)
        {
            return DataValueType.BOOLEAN;
        }
        return DataValueType.STRING;
    }

    // ---- Convenience: derive a primary domain ---------------------------------------


    /**
     * Picks the primary domain for a rule from its scope. Prefers the first
     * {@code Scope.Domains.Include} entry that exists in the loaded {@code datasets}; if no scope
     * is set, returns the first declared dataset.
     */
    public static String pickPrimaryDomain(Map<String, IDataTable> tables,
            net.cumba.corej.core.model.Rule rule)
    {
        if (rule != null && rule.getScope() != null && rule.getScope().getDomains() != null)
        {
            List<String> include = rule.getScope().getDomains().getInclude();
            if (include != null)
            {
                for (String d : include)
                {
                    if (tables.containsKey(d))
                    {
                        return d;
                    }
                }
            }
        }
        if (tables.isEmpty())
        {
            throw new IllegalArgumentException("No datasets declared in spec");
        }
        return tables.keySet().iterator().next();
    }


    /** Set of all dataset names — for {@code DatasetResolver.WithInventory}. */
    public static Set<String> domainNames(Map<String, IDataTable> tables)
    {
        return new LinkedHashSet<>(tables.keySet());
    }
}
