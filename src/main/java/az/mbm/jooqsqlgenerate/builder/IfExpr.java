package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;

import java.util.Map;
import java.util.Objects;

/**
 * SQL {@code CASE WHEN field = value THEN x ELSE y END} ifadəsi.
 *
 * <p>Then/else dəyərlər üçün smart həll:
 * <ul>
 *   <li>String {@code "alias.field"} — alias tableMap-də varsa sütun referansı</li>
 *   <li>Hər hansı digər dəyər — SQL literal (inline)</li>
 * </ul>
 *
 * <p>İstifadə nümunələri:
 * <pre>{@code
 *   // ComputedField-də başlanğıc ifadə kimi:
 *   ComputedField.ifExpr("o.status", "PAID", "o.amount", "0")
 *       .add("o.tax")
 *       .as("result")
 *   // → (CASE WHEN o.status='PAID' THEN o.amount ELSE 0 END) + o.tax AS result
 *
 *   // Aqreqat funksiyada:
 *   AggregateBuilder.groupBy("o.customerId")
 *       .sumIf("o.status", "PAID", "o.amount", "0").as("paidRevenue").done()
 *   // → SUM(CASE WHEN o.status='PAID' THEN o.amount ELSE 0 END) AS paidRevenue
 *
 *   // CONCAT-da:
 *   ConcatItem.ifExpr("o.status", "PAID", "Ödənilib", "Gözlənilir")
 *   // → CASE WHEN o.status='PAID' THEN 'Ödənilib' ELSE 'Gözlənilir' END
 * }</pre>
 */
public final class IfExpr {

    private final String condAlias;
    private final String condField;
    private final Object condValue;
    private final Object thenValue;
    private final Object elseValue;

    private IfExpr(String condAlias, String condField,
                   Object condValue, Object thenValue, Object elseValue) {
        this.condAlias  = condAlias;
        this.condField  = condField;
        this.condValue  = condValue;
        this.thenValue  = thenValue;
        this.elseValue  = elseValue;
    }

    // ─── Factory ─────────────────────────────────────────────────────────

    /**
     * {@code CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END}
     *
     * <pre>{@code
     *   // Sütun referansları ilə (alias.field formatında):
     *   IfExpr.of("o.status", "PAID", "o.amount", "o.penalty")
     *   // → CASE WHEN o.status='PAID' THEN o.amount ELSE o.penalty END
     *
     *   // Literal dəyərlərlə:
     *   IfExpr.of("o.status", "PAID", "Ödənilib", "Gözlənilir")
     *   // → CASE WHEN o.status='PAID' THEN 'Ödənilib' ELSE 'Gözlənilir' END
     *
     *   // Qarışıq:
     *   IfExpr.of("o.status", "PAID", "o.amount", "0")
     *   // → CASE WHEN o.status='PAID' THEN o.amount ELSE 0 END
     * }</pre>
     *
     * @param condField  şərt sütunu — {@code "alias.field"} formatında
     * @param equalTo    bərabərlik dəyəri
     * @param thenVal    şərt doğruysa dəyər — sütun adı və ya literal
     * @param elseVal    şərt yanlışdırsa dəyər — sütun adı və ya literal
     */
    public static IfExpr of(String condField, Object equalTo,
                            Object thenVal,   Object elseVal) {
        Objects.requireNonNull(condField, "IfExpr: condField null ola bilməz");
        Objects.requireNonNull(equalTo,   "IfExpr: equalTo null ola bilməz");
        String[] parts = split(condField);
        return new IfExpr(parts[0], parts[1], equalTo, thenVal, elseVal);
    }

    // ─── jOOQ Field-ə çevirmə ────────────────────────────────────────────

    /**
     * jOOQ {@link Field} kimi render edir.
     *
     * @param mainTable  ana entity table
     * @param tableMap   alias → EntityTable xəritəsi
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> toField(EntityTable<?> mainTable,
                            Map<String, EntityTable<?>> tableMap) {
        // Şərt sütunu
        EntityTable<?> ct = tableMap.getOrDefault(condAlias, mainTable);
        Field<Object> condCol = (Field<Object>) ct.getField(condField);

        Condition cond = condCol.eq(DSL.val(condValue));

        @SuppressWarnings("rawtypes")
        Field thenField = resolveValue(thenValue, mainTable, tableMap);
        @SuppressWarnings("rawtypes")
        Field elseField = resolveValue(elseValue, mainTable, tableMap);

        //noinspection unchecked
        return DSL.when(cond, thenField).otherwise(elseField);
    }

    // ─── Yardımcı ─────────────────────────────────────────────────────────

    /**
     * Dəyəri sütun referansı və ya SQL literal kimi həll edir.
     *
     * <p>Həll qaydası:
     * <ol>
     *   <li>String {@code "alias.field"} formatındadırsa VƏ alias tableMap-də varsa → sütun</li>
     *   <li>Əks halda → {@code DSL.inline(value)} (SQL literal)</li>
     * </ol>
     */
    static Field<?> resolveValue(Object value,
                                 EntityTable<?> mainTable,
                                 Map<String, EntityTable<?>> tableMap) {
        if (value instanceof String s) {
            int dot = s.indexOf('.');
            if (dot > 0) {
                String alias     = s.substring(0, dot);
                String fieldName = s.substring(dot + 1);
                if (tableMap.containsKey(alias)) {
                    return tableMap.get(alias).getField(fieldName);
                }
            }
        }
        return DSL.inline(value);
    }

    private static String[] split(String s) {
        int dot = s.indexOf('.');
        if (dot > 0) return new String[]{s.substring(0, dot), s.substring(dot + 1)};
        return new String[]{"", s};
    }
}
