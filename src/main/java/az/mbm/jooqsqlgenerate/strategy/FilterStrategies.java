package az.mbm.jooqsqlgenerate.strategy;

import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.enums.FilterOperations;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STRATEGY REGISTRY — {@link FilterOperations} → {@link FilterStrategy} xəritəsi
 *
 * <p>Bütün filter əməliyyatları statik blokda qeydiyyatdan keçir.
 * Xarici kod {@link #register} ilə yeni əməliyyat əlavə edə bilər
 * (mövcud kodu dəyişmədən — Open/Closed Principle).
 *
 * <pre>{@code
 *   // Yeni filter növü əlavə etmək:
 *   FilterStrategies.register(FilterOperations.MY_OP,
 *       (field, val) -> field.contains(val.toString()));
 * }</pre>
 */
public final class FilterStrategies {

    private static final Map<FilterOperations, FilterStrategy> REGISTRY =
            new EnumMap<>(FilterOperations.class);

    static {
        // ─── Bərabərlik ──────────────────────────────────────────────────
        register(FilterOperations.EQUAl,
                (field, val) -> field.eq(coerced(field, val)));

        register(FilterOperations.NOT_EQUAL,
                (field, val) -> field.ne(coerced(field, val)));

        // ─── Müqayisə ────────────────────────────────────────────────────
        register(FilterOperations.GREATER_THAN,
                (field, val) -> field.greaterThan(coerced(field, val)));

        register(FilterOperations.GREATER_THAN_OR_EQUAL_TO,
                (field, val) -> field.greaterOrEqual(coerced(field, val)));

        register(FilterOperations.LESS_THAN,
                (field, val) -> field.lessThan(coerced(field, val)));

        register(FilterOperations.LESS_THAN_OR_EQUAL_TO,
                (field, val) -> field.lessOrEqual(coerced(field, val)));

        // ─── Null yoxlaması — tip uyğunlaşması tələb olunmur ─────────────
        register(FilterOperations.IS_EMPTY,
                (field, val) -> field.isNull());

        register(FilterOperations.IS_NOT_EMPTY,
                (field, val) -> field.isNotNull());

        // ─── LIKE — VARCHAR tipi gözlənilir, çevrilmə yoxdur ────────────
        register(FilterOperations.LIKE,
                (field, val) -> field.like("%" + val + "%"));

        register(FilterOperations.START_WITH,
                (field, val) -> field.like(val + "%"));

        register(FilterOperations.END_WITH,
                (field, val) -> field.like("%" + val));

        // ─── IN / NOT IN — null elementlər çıxarılır, qalan tip uyğunlaşdırılır
        register(FilterOperations.IN, (field, val) -> {
            Collection<?> col = filterNulls(toCollection(val));
            if (col.isEmpty()) return DSL.falseCondition(); // heç nə uyğun gəlmir
            return field.in(coercedList(field, col));
        });

        register(FilterOperations.NOT_IN, (field, val) -> {
            Collection<?> col = filterNulls(toCollection(val));
            if (col.isEmpty()) return DSL.trueCondition();
            return field.notIn(coercedList(field, col));
        });

        // ─── BETWEEN — hər iki hədd field tipinə uyğunlaşdırılır ─────────
        register(FilterOperations.BETWEEN, (field, val) -> {
            if (val instanceof Object[] arr && arr.length == 2) {
                // Object[]{from, to} formatı
                return field.between(coerced(field, arr[0]), coerced(field, arr[1]));
            }
            // "from,to" string formatı
            String[] parts = val.toString().split(",", 2);
            if (parts.length != 2)
                throw new IllegalArgumentException(
                        "BETWEEN üçün Object[]{from,to} və ya 'from,to' string lazımdır: " + val);
            return field.between(coerced(field, parts[0].trim()), coerced(field, parts[1].trim()));
        });

        // ─── REGEXP — string pattern ──────────────────────────────────────
        register(FilterOperations.REGEXP,
                (field, val) -> field.likeRegex(val.toString()));

        register(FilterOperations.NOT_REGEXP,
                (field, val) -> DSL.not(field.likeRegex(val.toString())));
    }

    private FilterStrategies() {}

    // ════════════════════════════════════════════════════════════════════
    //  TİP UYĞUNLAŞDIRMA
    // ════════════════════════════════════════════════════════════════════

    /**
     * Field-in tipi ilə gələn dəyəri uyğunlaşdırır.
     *
     * <p>Nümunələr:
     * <ul>
     *   <li>Field {@code INTEGER}, val {@code "25"}  → {@code 25}  (Integer)</li>
     *   <li>Field {@code BIGINT},  val {@code "100"} → {@code 100L} (Long)</li>
     *   <li>Field {@code BOOLEAN}, val {@code "true"} → {@code true} (Boolean)</li>
     *   <li>Field {@code DATE},    val {@code "2024-01-15"} → {@code LocalDate}</li>
     *   <li>Field {@code VARCHAR}, val {@code 42}   → {@code "42"} (String)</li>
     * </ul>
     *
     * <p>Çevrilmə mümkün deyilsə — orijinal dəyər olduğu kimi bind edilir (fallback).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Field<?> coerced(Field<Object> field, Object val) {
        try {
            DataType<Object> dt = (DataType<Object>) field.getDataType();
            Object converted = dt.convert(val);
            return DSL.val(converted, dt);
        } catch (Exception e) {
            // Tip çevrilməsi bacarılmadı — orijinal dəyəri bind et
            return DSL.val(val);
        }
    }

    /**
     * Kolleksiyanın hər elementini field tipinə uyğunlaşdırır.
     *
     * <pre>{@code
     *   // Field INTEGER, val = ["1", "2", "3"]  →  [1, 2, 3]
     *   // Field VARCHAR, val = [1, 2, 3]         →  ["1", "2", "3"]
     * }</pre>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static java.util.List<Field<?>> coercedList(Field<Object> field, Collection<?> col) {
        DataType<Object> dt;
        try {
            dt = (DataType<Object>) field.getDataType();
        } catch (Exception e) {
            dt = null;
        }
        final DataType<Object> finalDt = dt;
        return col.stream().map(item -> {
            if (finalDt == null) return DSL.val(item);
            try {
                return (Field<?>) DSL.val(finalDt.convert(item), finalDt);
            } catch (Exception ex) {
                return (Field<?>) DSL.val(item);
            }
        }).collect(Collectors.toList());
    }

    /**
     * Yeni filter strategiyasını qeydiyyatdan keçirir.
     * Mövcud strategiyanın üstünə yazır.
     */
    public static void register(FilterOperations op, FilterStrategy strategy) {
        REGISTRY.put(op, strategy);
    }

    /**
     * @param op filter əməliyyatı
     * @return müvafiq strategiya
     * @throws IllegalArgumentException əgər qeydiyyatda yoxdursa
     */
    public static FilterStrategy get(FilterOperations op) {
        FilterStrategy strategy = REGISTRY.get(op);
        if (strategy == null)
            throw new IllegalArgumentException(
                    "Bilinməyən filter əməliyyatı: " + op +
                    ". FilterStrategies.register() ilə qeyd edin.");
        return strategy;
    }

    private static Collection<?> toCollection(Object val) {
        if (val instanceof Collection<?> c) return c;
        if (val instanceof Object[] arr)    return Arrays.asList(arr);
        if (val instanceof String s)        return Arrays.asList(s.split(","));
        return Arrays.asList(val);
    }

    /**
     * Kolleksiyadan null elementləri çıxarır.
     *
     * <p>FK sütunları nullable olduqda developer List içinə null göndərə bilər.
     * SQL-də {@code IN (1, NULL, 3)} yazılanda NULL heç nəyə uyğun gəlmir —
     * FK-sı NULL olan sətirləri gətirmir. Bu metod null elementləri siyahıdan çıxarır.
     *
     * <p>Null olan FK-ları axtarmaq üçün {@link FilterOperations#IS_EMPTY} istifadə edin.
     */
    private static Collection<?> filterNulls(Collection<?> col) {
        return col.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }
}
