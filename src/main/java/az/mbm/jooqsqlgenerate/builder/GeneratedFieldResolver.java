package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import org.jooq.Table;

import java.util.Map;

/**
 * Generated/derived-table mode üçün sahə həlli — {@code EntityTable} əvəzinə
 * birbaşa jOOQ {@link Table} üzərindən işləyir.
 *
 * <p>{@link ComputedField}, {@link IfExpr} və {@link CoalesceExpr} bunu istifadə edir ki,
 * generated mode-da (derived table JOIN-ləri, {@code JooqQuery.executeGenerated()}) sahələr
 * {@code EntityTable} reflection-suz (entity class tələb etmədən) həll oluna bilsin.
 *
 * <p>Həll qaydası {@code JooqQuery.resolveFromTable} ilə eynidir:
 * birbaşa axtarış → camelCase→snake_case → case-insensitive uyğunlaşma.
 */
public final class GeneratedFieldResolver {

    private GeneratedFieldResolver() {}

    /** Alias-a uyğun {@link Table}-i tapır; tapılmasa ana cədvələ düşür. */
    public static Table<?> resolveTable(String alias, Table<?> mainTable, Map<String, Table<?>> tableMap) {
        if (alias == null || alias.isBlank()) return mainTable;
        return tableMap.getOrDefault(alias, mainTable);
    }

    /** Sahəni cədvəldən tapır — tapılmasa {@code null}. */
    public static Field<?> resolveField(Table<?> table, String fieldName) {
        if (table == null || fieldName == null || fieldName.isBlank()) return null;

        Field<?> f = table.field(fieldName);
        if (f != null) return f;

        String snake = camelToSnake(fieldName);
        if (!snake.equals(fieldName)) {
            f = table.field(snake);
            if (f != null) return f;
        }

        for (Field<?> tf : table.fields()) {
            String name = tf.getName();
            if (name.equalsIgnoreCase(fieldName) || name.equalsIgnoreCase(snake)) return tf;
        }

        return null;
    }

    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
