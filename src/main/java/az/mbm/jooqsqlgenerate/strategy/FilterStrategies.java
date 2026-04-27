package az.mbm.jooqsqlgenerate.strategy;

import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.enums.Op;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STRATEGY REGISTRY — {@link Op} → {@link FilterStrategy} xəritəsi
 *
 * <p>Bütün filter əməliyyatları statik blokda qeydiyyatdan keçir.
 * Xarici kod {@link #register} ilə yeni əməliyyat əlavə edə bilər
 * (mövcud kodu dəyişmədən — Open/Closed Principle).
 *
 * <pre>{@code
 *   // Yeni filter növü əlavə etmək:
 *   FilterStrategies.register(Op.MY_OP,
 *       (field, val) -> field.contains(val.toString()));
 * }</pre>
 */
public final class FilterStrategies {

    private static final Map<Op, FilterStrategy> REGISTRY =
            new EnumMap<>(Op.class);

    static {
        // ─── Bərabərlik ──────────────────────────────────────────────────
        register(Op.EQUAl,
                (field, val) -> field.eq(coerced(field, val)));

        register(Op.NOT_EQUAL,
                (field, val) -> field.ne(coerced(field, val)));

        // ─── Müqayisə ────────────────────────────────────────────────────
        register(Op.GREATER_THAN,
                (field, val) -> field.greaterThan(coerced(field, val)));

        register(Op.GREATER_THAN_OR_EQUAL_TO,
                (field, val) -> field.greaterOrEqual(coerced(field, val)));

        register(Op.LESS_THAN,
                (field, val) -> field.lessThan(coerced(field, val)));

        register(Op.LESS_THAN_OR_EQUAL_TO,
                (field, val) -> field.lessOrEqual(coerced(field, val)));

        // ─── Null yoxlaması — tip uyğunlaşması tələb olunmur ─────────────
        register(Op.IS_EMPTY,
                (field, val) -> field.isNull());

        register(Op.IS_NOT_EMPTY,
                (field, val) -> field.isNotNull());

        // ─── LIKE — VARCHAR tipi gözlənilir, çevrilmə yoxdur ────────────
        register(Op.LIKE,
                (field, val) -> field.like("%" + val + "%"));

        register(Op.START_WITH,
                (field, val) -> field.like(val + "%"));

        register(Op.END_WITH,
                (field, val) -> field.like("%" + val));

        // ─── IN / NOT IN — null elementlər çıxarılır, qalan tip uyğunlaşdırılır
        register(Op.IN, (field, val) -> {
            Collection<?> col = filterNulls(toCollection(val));
            if (col.isEmpty()) return DSL.falseCondition(); // heç nə uyğun gəlmir
            return field.in(coercedList(field, col));
        });

        register(Op.NOT_IN, (field, val) -> {
            Collection<?> col = filterNulls(toCollection(val));
            if (col.isEmpty()) return DSL.trueCondition();
            return field.notIn(coercedList(field, col));
        });

        // ─── BETWEEN — hər iki hədd field tipinə uyğunlaşdırılır ─────────
        register(Op.BETWEEN, (field, val) -> {
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
        register(Op.REGEXP,
                (field, val) -> field.likeRegex(val.toString()));

        register(Op.NOT_REGEXP,
                (field, val) -> DSL.not(field.likeRegex(val.toString())));

        // ─── ROUND müqayisə əməliyyatları ─────────────────────────────────
        // ROUND(field, scale) OP value — hesablanmayan sütunlar üçün
        //
        // Nümunə:
        //   FilterStrategies.get(Op.GREATER_THAN_ROUND_2).apply(priceField, "9.50")
        //   → WHERE ROUND(price, 2) > 9.50

        // Scale 0 — tam ədədə yuvarlama
        register(Op.EQUAL_ROUND_0,
                (field, val) -> rounded(field, 0).eq(coerced(field, val)));
        register(Op.NOT_EQUAL_ROUND_0,
                (field, val) -> rounded(field, 0).ne(coerced(field, val)));
        register(Op.GREATER_THAN_ROUND_0,
                (field, val) -> rounded(field, 0).greaterThan(coerced(field, val)));
        register(Op.GREATER_THAN_OR_EQUAL_TO_ROUND_0,
                (field, val) -> rounded(field, 0).greaterOrEqual(coerced(field, val)));
        register(Op.LESS_THAN_ROUND_0,
                (field, val) -> rounded(field, 0).lessThan(coerced(field, val)));
        register(Op.LESS_THAN_OR_EQUAL_TO_ROUND_0,
                (field, val) -> rounded(field, 0).lessOrEqual(coerced(field, val)));

        // Scale 1
        register(Op.EQUAL_ROUND_1,
                (field, val) -> rounded(field, 1).eq(coerced(field, val)));
        register(Op.NOT_EQUAL_ROUND_1,
                (field, val) -> rounded(field, 1).ne(coerced(field, val)));
        register(Op.GREATER_THAN_ROUND_1,
                (field, val) -> rounded(field, 1).greaterThan(coerced(field, val)));
        register(Op.GREATER_THAN_OR_EQUAL_TO_ROUND_1,
                (field, val) -> rounded(field, 1).greaterOrEqual(coerced(field, val)));
        register(Op.LESS_THAN_ROUND_1,
                (field, val) -> rounded(field, 1).lessThan(coerced(field, val)));
        register(Op.LESS_THAN_OR_EQUAL_TO_ROUND_1,
                (field, val) -> rounded(field, 1).lessOrEqual(coerced(field, val)));

        // Scale 2
        register(Op.EQUAL_ROUND_2,
                (field, val) -> rounded(field, 2).eq(coerced(field, val)));
        register(Op.NOT_EQUAL_ROUND_2,
                (field, val) -> rounded(field, 2).ne(coerced(field, val)));
        register(Op.GREATER_THAN_ROUND_2,
                (field, val) -> rounded(field, 2).greaterThan(coerced(field, val)));
        register(Op.GREATER_THAN_OR_EQUAL_TO_ROUND_2,
                (field, val) -> rounded(field, 2).greaterOrEqual(coerced(field, val)));
        register(Op.LESS_THAN_ROUND_2,
                (field, val) -> rounded(field, 2).lessThan(coerced(field, val)));
        register(Op.LESS_THAN_OR_EQUAL_TO_ROUND_2,
                (field, val) -> rounded(field, 2).lessOrEqual(coerced(field, val)));

        // Scale 3
        register(Op.EQUAL_ROUND_3,
                (field, val) -> rounded(field, 3).eq(coerced(field, val)));
        register(Op.NOT_EQUAL_ROUND_3,
                (field, val) -> rounded(field, 3).ne(coerced(field, val)));
        register(Op.GREATER_THAN_ROUND_3,
                (field, val) -> rounded(field, 3).greaterThan(coerced(field, val)));
        register(Op.GREATER_THAN_OR_EQUAL_TO_ROUND_3,
                (field, val) -> rounded(field, 3).greaterOrEqual(coerced(field, val)));
        register(Op.LESS_THAN_ROUND_3,
                (field, val) -> rounded(field, 3).lessThan(coerced(field, val)));
        register(Op.LESS_THAN_OR_EQUAL_TO_ROUND_3,
                (field, val) -> rounded(field, 3).lessOrEqual(coerced(field, val)));

        // Scale 4
        register(Op.EQUAL_ROUND_4,
                (field, val) -> rounded(field, 4).eq(coerced(field, val)));
        register(Op.NOT_EQUAL_ROUND_4,
                (field, val) -> rounded(field, 4).ne(coerced(field, val)));
        register(Op.GREATER_THAN_ROUND_4,
                (field, val) -> rounded(field, 4).greaterThan(coerced(field, val)));
        register(Op.GREATER_THAN_OR_EQUAL_TO_ROUND_4,
                (field, val) -> rounded(field, 4).greaterOrEqual(coerced(field, val)));
        register(Op.LESS_THAN_ROUND_4,
                (field, val) -> rounded(field, 4).lessThan(coerced(field, val)));
        register(Op.LESS_THAN_OR_EQUAL_TO_ROUND_4,
                (field, val) -> rounded(field, 4).lessOrEqual(coerced(field, val)));
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
    public static void register(Op op, FilterStrategy strategy) {
        REGISTRY.put(op, strategy);
    }

    /**
     * @param op filter əməliyyatı
     * @return müvafiq strategiya
     * @throws IllegalArgumentException əgər qeydiyyatda yoxdursa
     */
    public static FilterStrategy get(Op op) {
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
     * <p>Null olan FK-ları axtarmaq üçün {@link Op#IS_EMPTY} istifadə edin.
     */
    private static Collection<?> filterNulls(Collection<?> col) {
        return col.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Field-i {@code ROUND(field, scale)} ifadəsinə çevirir.
     *
     * <p>ROUND əməliyyatları üçün daxili köməkçi metod:
     * {@code DSL.round(numericField, scale)} çağırır, nəticəni {@code Field<Object>} kimi qaytarır.
     *
     * @param field orijinal sahə ({@code Field<Object>})
     * @param scale onluq rəqəm sayı
     * @return {@code ROUND(field, scale)} ifadəsi
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Field<Object> rounded(Field<Object> field, int scale) {
        return (Field<Object>) (Field<?>) DSL.round(
                (Field<? extends Number>) (Field<?>) field, scale);
    }
}
