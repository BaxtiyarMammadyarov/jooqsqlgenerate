package az.mbm.jooqsqlgenerate.spec;

import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DİNAMİK FİLTER BUILDER — null/boş dəyərləri avtomatik atlayır.
 *
 * <p>Köhnə {@code ChwJooqManager}-in dinamik filter davranışını tam təkrarlayır:
 * dəyər {@code null}, boş sətir, boş kolleksiya olduqda həmin şərt sadəcə
 * <b>nəzərə alınmır</b> — xəta atılmır.
 *
 * <p>İstifadə nümunəsi (REST endpoint-dən gələn nullable parametrlər):
 * <pre>{@code
 *   // status=null, name="Ali" gəldikdə yalnız name filtri işləyir
 *   Specification<User> filter = Filter.<User>of()
 *       .eq("status", status)          // null → atlanır
 *       .like("name", name)            // "Ali" → WHERE name LIKE '%Ali%'
 *       .in("roleId", roleIds)         // boş list → atlanır
 *       .between("age", minAge, maxAge)// hər ikisi null → atlanır
 *       .build();
 *
 *   factory.select(User.class, "u")
 *       .where(filter)
 *       .build(dsl);
 * }</pre>
 *
 * @param <T> entity tipi
 */
public final class Filter<T> {

    private Specification<T> accumulated = null;

    private Filter() {}

    /** Yeni dinamik filter builder yaradır. */
    public static <T> Filter<T> of() {
        return new Filter<>();
    }

    // ─── Bərabərlik ──────────────────────────────────────────────────────

    /** {@code WHERE field = value} — value null-dursa atlanır */
    public Filter<T> eq(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.eq(coerced(f, value)); });
    }

    /** {@code WHERE field != value} — value null-dursa atlanır */
    public Filter<T> notEq(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.ne(coerced(f, value)); });
    }

    // ─── Müqayisə ────────────────────────────────────────────────────────

    /** {@code WHERE field > value} — value null-dursa atlanır */
    public Filter<T> gt(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.greaterThan(coerced(f, value)); });
    }

    /** {@code WHERE field >= value} — value null-dursa atlanır */
    public Filter<T> gte(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.greaterOrEqual(coerced(f, value)); });
    }

    /** {@code WHERE field < value} — value null-dursa atlanır */
    public Filter<T> lt(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.lessThan(coerced(f, value)); });
    }

    /** {@code WHERE field <= value} — value null-dursa atlanır */
    public Filter<T> lte(String field, Object value) {
        if (isBlank(field) || value == null) return this;
        return add(table -> { var f = table.getField(field); return f.lessOrEqual(coerced(f, value)); });
    }

    // ─── Null yoxlaması ──────────────────────────────────────────────────

    /** {@code WHERE field IS NULL} — həmişə əlavə edilir */
    public Filter<T> isNull(String field) {
        if (isBlank(field)) return this;
        return add(table -> table.getField(field).isNull());
    }

    /** {@code WHERE field IS NOT NULL} — həmişə əlavə edilir */
    public Filter<T> isNotNull(String field) {
        if (isBlank(field)) return this;
        return add(table -> table.getField(field).isNotNull());
    }

    // ─── LIKE ────────────────────────────────────────────────────────────

    /**
     * {@code WHERE field LIKE '%value%'} — value null/boş-dursa atlanır.
     *
     * <p>String field → {@code LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%val%'}
     * <br>Numeric field → {@code CAST(field AS varchar) LIKE '%val%'}
     */
    @SuppressWarnings("unchecked")
    public Filter<T> like(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return add(table -> {
            var f = (org.jooq.Field<Object>) table.getField(field);
            return az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                    .get(az.mbm.jooqsqlgenerate.enums.Op.LIKE).apply(f, value);
        });
    }

    /**
     * {@code WHERE field LIKE 'value%'} — value null/boş-dursa atlanır.
     *
     * <p>String field → türk normallaşdırması ilə; Numeric field → sadə CAST.
     */
    @SuppressWarnings("unchecked")
    public Filter<T> startWith(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return add(table -> {
            var f = (org.jooq.Field<Object>) table.getField(field);
            return az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                    .get(az.mbm.jooqsqlgenerate.enums.Op.START_WITH).apply(f, value);
        });
    }

    /**
     * {@code WHERE field LIKE '%value'} — value null/boş-dursa atlanır.
     *
     * <p>String field → türk normallaşdırması ilə; Numeric field → sadə CAST.
     */
    @SuppressWarnings("unchecked")
    public Filter<T> endWith(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return add(table -> {
            var f = (org.jooq.Field<Object>) table.getField(field);
            return az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                    .get(az.mbm.jooqsqlgenerate.enums.Op.END_WITH).apply(f, value);
        });
    }

    // ─── IN / NOT IN ─────────────────────────────────────────────────────

    /** {@code WHERE field IN (...)} — kolleksiya null/boş-dursa atlanır */
    public Filter<T> in(String field, Collection<?> values) {
        if (isBlank(field) || values == null || values.isEmpty()) return this;
        return add(table -> { var f = table.getField(field); return f.in(coercedList(f, values)); });
    }

    /** {@code WHERE field IN (...)} — varargs — heç bir element yoxdursa atlanır */
    public Filter<T> in(String field, Object... values) {
        return in(field, values == null ? null : Arrays.asList(values));
    }

    /** {@code WHERE field NOT IN (...)} — kolleksiya null/boş-dursa atlanır */
    public Filter<T> notIn(String field, Collection<?> values) {
        if (isBlank(field) || values == null || values.isEmpty()) return this;
        return add(table -> { var f = table.getField(field); return f.notIn(coercedList(f, values)); });
    }

    /** {@code WHERE field NOT IN (...)} — varargs */
    public Filter<T> notIn(String field, Object... values) {
        return notIn(field, values == null ? null : Arrays.asList(values));
    }

    // ─── BETWEEN ─────────────────────────────────────────────────────────

    /**
     * {@code WHERE field BETWEEN from AND to}
     * — from VƏ ya to null-dursa atlanır.
     * Yalnız from verilsə → {@code >= from}.
     * Yalnız to verilsə  → {@code <= to}.
     */
    public Filter<T> between(String field, Object from, Object to) {
        if (isBlank(field)) return this;
        if (from != null && to != null)
            return add(table -> { var f = table.getField(field); return f.between(coerced(f, from), coerced(f, to)); });
        if (from != null)
            return add(table -> { var f = table.getField(field); return f.greaterOrEqual(coerced(f, from)); });
        if (to != null)
            return add(table -> { var f = table.getField(field); return f.lessOrEqual(coerced(f, to)); });
        return this; // ikisi də null → atlanır
    }

    // ─── REGEXP ──────────────────────────────────────────────────────────

    /** {@code WHERE field REGEXP pattern} — pattern null/boş-dursa atlanır */
    public Filter<T> regexp(String field, String pattern) {
        if (isBlank(field) || isBlank(pattern)) return this;
        return add(table -> table.getField(field).likeRegex(pattern));
    }

    // ─── OR bloku ────────────────────────────────────────────────────────

    /**
     * Hazırlanmış bir {@link Specification}-i OR ilə əlavə edir.
     *
     * <pre>{@code
     *   Filter.<User>of()
     *       .eq("status", "ACTIVE")
     *       .or(Spec.eq("status", "PENDING"))
     * }</pre>
     */
    public Filter<T> or(Specification<T> spec) {
        if (spec == null) return this;
        accumulated = (accumulated == null) ? spec : accumulated.or(spec);
        return this;
    }

    /**
     * Başqa bir {@link Filter}-i OR ilə birləşdirir.
     *
     * <pre>{@code
     *   Filter.<User>of()
     *       .eq("status", status)
     *       .orFilter(
     *           Filter.<User>of().eq("adminRole", true)
     *       )
     * }</pre>
     */
    public Filter<T> orFilter(Filter<T> other) {
        if (other == null) return this;
        Specification<T> otherSpec = other.build();
        if (otherSpec == null) return this;
        return or(otherSpec);
    }

    // ─── BUILD ───────────────────────────────────────────────────────────

    /**
     * Toplanan bütün şərtləri bir {@link Specification}-ə çevirir.
     *
     * <p>Heç bir şərt əlavə edilməyibsə {@code null} qaytarır —
     * {@link az.mbm.jooqsqlgenerate.builder.SelectQueryBuilder#where} null-u
     * qəbul etmir, ona görə {@link #buildOrTrue()} istifadə edin.
     */
    public Specification<T> build() {
        return accumulated;
    }

    /**
     * Heç bir şərt yoxdursa {@code 1=1} (hər şeyi seçir) qaytarır.
     * WHERE bloku mütləq olduqda istifadə edin.
     *
     * <pre>{@code
     *   .where(Filter.<User>of().eq("status", status).buildOrTrue())
     * }</pre>
     */
    public Specification<T> buildOrTrue() {
        return accumulated != null ? accumulated : table -> DSL.trueCondition();
    }

    // ─── Yardımcılar ─────────────────────────────────────────────────────

    private Filter<T> add(Specification<T> spec) {
        accumulated = (accumulated == null) ? spec : accumulated.and(spec);
        return this;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Field-in tipi ilə dəyəri uyğunlaşdırır.
     * "25" → 25 (Integer), "true" → true (Boolean) və s.
     * Çevrilmə mümkün deyilsə — orijinal dəyər bind edilir.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Field<?> coerced(Field<Object> field, Object val) {
        try {
            var dt = (org.jooq.DataType<Object>) field.getDataType();
            return DSL.val(dt.convert(val), dt);
        } catch (Exception e) {
            return DSL.val(val);
        }
    }

    /**
     * Kolleksiyadakı null-ları çıxarır, qalan elementləri field tipinə uyğunlaşdırır.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.List<Field<?>> coercedList(Field<Object> field, Collection<?> col) {
        org.jooq.DataType<Object> dt;
        try { dt = (org.jooq.DataType<Object>) field.getDataType(); }
        catch (Exception e) { dt = null; }
        final var finalDt = dt;
        return col.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    if (finalDt == null) return (Field<?>) DSL.val(item);
                    try { return (Field<?>) DSL.val(finalDt.convert(item), finalDt); }
                    catch (Exception ex) { return (Field<?>) DSL.val(item); }
                }).collect(Collectors.toList());
    }
}
