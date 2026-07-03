package az.mbm.jooqsqlgenerate.spec;

import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

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
    public Filter<T> like(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.LIKE, value);
    }

    /**
     * {@code WHERE field LIKE 'value%'} — value null/boş-dursa atlanır.
     *
     * <p>String field → türk normallaşdırması ilə; Numeric field → sadə CAST.
     */
    public Filter<T> startWith(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.START_WITH, value);
    }

    /**
     * {@code WHERE field LIKE '%value'} — value null/boş-dursa atlanır.
     *
     * <p>String field → türk normallaşdırması ilə; Numeric field → sadə CAST.
     */
    public Filter<T> endWith(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.END_WITH, value);
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

    // ─── ROUND müqayisə əməliyyatları (hesablanmayan sütunlar üçün) ──────
    //
    // WHERE ROUND(field, scale) OP value — value null-dursa atlanır
    //   .gtRound2("unitCost", 9.99)   → WHERE ROUND(unit_cost, 2) > 9.99
    //
    // Hamısı eyni şablondur: roundFilter(field, Op.<OP>_ROUND_<scale>, value)

    // ── Scale 0 — tam ədədə yuvarlama ────────────────────────────────────

    /** {@code WHERE ROUND(field, 0) = value} — value null-dursa atlanır */
    public Filter<T> eqRound0(String field, Object value)    { return roundFilter(field, Op.EQUAL_ROUND_0, value); }

    /** {@code WHERE ROUND(field, 0) != value} — value null-dursa atlanır */
    public Filter<T> notEqRound0(String field, Object value) { return roundFilter(field, Op.NOT_EQUAL_ROUND_0, value); }

    /** {@code WHERE ROUND(field, 0) > value} — value null-dursa atlanır */
    public Filter<T> gtRound0(String field, Object value)    { return roundFilter(field, Op.GREATER_THAN_ROUND_0, value); }

    /** {@code WHERE ROUND(field, 0) >= value} — value null-dursa atlanır */
    public Filter<T> gteRound0(String field, Object value)   { return roundFilter(field, Op.GREATER_THAN_OR_EQUAL_TO_ROUND_0, value); }

    /** {@code WHERE ROUND(field, 0) < value} — value null-dursa atlanır */
    public Filter<T> ltRound0(String field, Object value)    { return roundFilter(field, Op.LESS_THAN_ROUND_0, value); }

    /** {@code WHERE ROUND(field, 0) <= value} — value null-dursa atlanır */
    public Filter<T> lteRound0(String field, Object value)   { return roundFilter(field, Op.LESS_THAN_OR_EQUAL_TO_ROUND_0, value); }

    // ── Scale 1 — bir onluq rəqəm ────────────────────────────────────────

    /** {@code WHERE ROUND(field, 1) = value} — value null-dursa atlanır */
    public Filter<T> eqRound1(String field, Object value)    { return roundFilter(field, Op.EQUAL_ROUND_1, value); }

    /** {@code WHERE ROUND(field, 1) != value} — value null-dursa atlanır */
    public Filter<T> notEqRound1(String field, Object value) { return roundFilter(field, Op.NOT_EQUAL_ROUND_1, value); }

    /** {@code WHERE ROUND(field, 1) > value} — value null-dursa atlanır */
    public Filter<T> gtRound1(String field, Object value)    { return roundFilter(field, Op.GREATER_THAN_ROUND_1, value); }

    /** {@code WHERE ROUND(field, 1) >= value} — value null-dursa atlanır */
    public Filter<T> gteRound1(String field, Object value)   { return roundFilter(field, Op.GREATER_THAN_OR_EQUAL_TO_ROUND_1, value); }

    /** {@code WHERE ROUND(field, 1) < value} — value null-dursa atlanır */
    public Filter<T> ltRound1(String field, Object value)    { return roundFilter(field, Op.LESS_THAN_ROUND_1, value); }

    /** {@code WHERE ROUND(field, 1) <= value} — value null-dursa atlanır */
    public Filter<T> lteRound1(String field, Object value)   { return roundFilter(field, Op.LESS_THAN_OR_EQUAL_TO_ROUND_1, value); }

    // ── Scale 2 — iki onluq rəqəm (qiymət/məbləğ üçün ən çox istifadə olunur) ──

    /** {@code WHERE ROUND(field, 2) = value} — value null-dursa atlanır */
    public Filter<T> eqRound2(String field, Object value)    { return roundFilter(field, Op.EQUAL_ROUND_2, value); }

    /** {@code WHERE ROUND(field, 2) != value} — value null-dursa atlanır */
    public Filter<T> notEqRound2(String field, Object value) { return roundFilter(field, Op.NOT_EQUAL_ROUND_2, value); }

    /** {@code WHERE ROUND(field, 2) > value} — value null-dursa atlanır */
    public Filter<T> gtRound2(String field, Object value)    { return roundFilter(field, Op.GREATER_THAN_ROUND_2, value); }

    /** {@code WHERE ROUND(field, 2) >= value} — value null-dursa atlanır */
    public Filter<T> gteRound2(String field, Object value)   { return roundFilter(field, Op.GREATER_THAN_OR_EQUAL_TO_ROUND_2, value); }

    /** {@code WHERE ROUND(field, 2) < value} — value null-dursa atlanır */
    public Filter<T> ltRound2(String field, Object value)    { return roundFilter(field, Op.LESS_THAN_ROUND_2, value); }

    /** {@code WHERE ROUND(field, 2) <= value} — value null-dursa atlanır */
    public Filter<T> lteRound2(String field, Object value)   { return roundFilter(field, Op.LESS_THAN_OR_EQUAL_TO_ROUND_2, value); }

    // ── Scale 3 — üç onluq rəqəm ─────────────────────────────────────────

    /** {@code WHERE ROUND(field, 3) = value} — value null-dursa atlanır */
    public Filter<T> eqRound3(String field, Object value)    { return roundFilter(field, Op.EQUAL_ROUND_3, value); }

    /** {@code WHERE ROUND(field, 3) != value} — value null-dursa atlanır */
    public Filter<T> notEqRound3(String field, Object value) { return roundFilter(field, Op.NOT_EQUAL_ROUND_3, value); }

    /** {@code WHERE ROUND(field, 3) > value} — value null-dursa atlanır */
    public Filter<T> gtRound3(String field, Object value)    { return roundFilter(field, Op.GREATER_THAN_ROUND_3, value); }

    /** {@code WHERE ROUND(field, 3) >= value} — value null-dursa atlanır */
    public Filter<T> gteRound3(String field, Object value)   { return roundFilter(field, Op.GREATER_THAN_OR_EQUAL_TO_ROUND_3, value); }

    /** {@code WHERE ROUND(field, 3) < value} — value null-dursa atlanır */
    public Filter<T> ltRound3(String field, Object value)    { return roundFilter(field, Op.LESS_THAN_ROUND_3, value); }

    /** {@code WHERE ROUND(field, 3) <= value} — value null-dursa atlanır */
    public Filter<T> lteRound3(String field, Object value)   { return roundFilter(field, Op.LESS_THAN_OR_EQUAL_TO_ROUND_3, value); }

    // ── Scale 4 — dörd onluq rəqəm ───────────────────────────────────────

    /** {@code WHERE ROUND(field, 4) = value} — value null-dursa atlanır */
    public Filter<T> eqRound4(String field, Object value)    { return roundFilter(field, Op.EQUAL_ROUND_4, value); }

    /** {@code WHERE ROUND(field, 4) != value} — value null-dursa atlanır */
    public Filter<T> notEqRound4(String field, Object value) { return roundFilter(field, Op.NOT_EQUAL_ROUND_4, value); }

    /** {@code WHERE ROUND(field, 4) > value} — value null-dursa atlanır */
    public Filter<T> gtRound4(String field, Object value)    { return roundFilter(field, Op.GREATER_THAN_ROUND_4, value); }

    /** {@code WHERE ROUND(field, 4) >= value} — value null-dursa atlanır */
    public Filter<T> gteRound4(String field, Object value)   { return roundFilter(field, Op.GREATER_THAN_OR_EQUAL_TO_ROUND_4, value); }

    /** {@code WHERE ROUND(field, 4) < value} — value null-dursa atlanır */
    public Filter<T> ltRound4(String field, Object value)    { return roundFilter(field, Op.LESS_THAN_ROUND_4, value); }

    /** {@code WHERE ROUND(field, 4) <= value} — value null-dursa atlanır */
    public Filter<T> lteRound4(String field, Object value)   { return roundFilter(field, Op.LESS_THAN_OR_EQUAL_TO_ROUND_4, value); }

    // ─── Türk əlifbası case-insensitive LIKE ─────────────────────────────

    /** Türk İ/I-yə uyğun case-insensitive LIKE — value null/boş-dursa atlanır */
    public Filter<T> likeIgnoreCase(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.LIKE_IGNORE_CASE, value);
    }

    /** Türk İ/I-yə uyğun case-insensitive STARTWITH — value null/boş-dursa atlanır */
    public Filter<T> startWithIgnoreCase(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.START_WITH_IGNORE_CASE, value);
    }

    /** Türk İ/I-yə uyğun case-insensitive ENDWITH — value null/boş-dursa atlanır */
    public Filter<T> endWithIgnoreCase(String field, String value) {
        if (isBlank(field) || isBlank(value)) return this;
        return strategy(field, Op.END_WITH_IGNORE_CASE, value);
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

    /**
     * ROUND filter şablonu: field/value yoxlaması + strategy tətbiqi.
     * {@code WHERE ROUND(field, scale) OP value} — value null-dursa atlanır.
     */
    private Filter<T> roundFilter(String field, Op op, Object value) {
        if (isBlank(field) || value == null) return this;
        return strategy(field, op, value);
    }

    /** Şərti {@link FilterStrategies} registry-sindəki strategiya ilə qurur. */
    @SuppressWarnings("unchecked")
    private Filter<T> strategy(String field, Op op, Object value) {
        return add(table -> {
            var f = (Field<Object>) table.getField(field);
            return FilterStrategies.get(op).apply(f, value);
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Field-in tipi ilə dəyəri uyğunlaşdırır.
     * "25" → 25 (Integer), "true" → true (Boolean) və s.
     * Çevrilmə mümkün deyilsə — orijinal dəyər bind edilir.
     */
    @SuppressWarnings("unchecked")
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
    @SuppressWarnings("unchecked")
    private static List<Field<?>> coercedList(Field<Object> field, Collection<?> col) {
        org.jooq.DataType<Object> dt;
        try { dt = (org.jooq.DataType<Object>) field.getDataType(); }
        catch (Exception e) { dt = null; }
        List<Field<?>> result = new ArrayList<>(col.size());
        for (Object item : col) {
            if (item == null) continue;
            if (dt == null) { result.add(DSL.val(item)); continue; }
            try { result.add(DSL.val(dt.convert(item), dt)); }
            catch (Exception ex) { result.add(DSL.val(item)); }
        }
        return result;
    }
}
