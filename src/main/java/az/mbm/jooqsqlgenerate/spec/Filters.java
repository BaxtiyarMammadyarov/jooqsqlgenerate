package az.mbm.jooqsqlgenerate.spec;

import az.mbm.jooqsqlgenerate.enums.FilterOperationConstants;

import java.util.*;

/**
 * FLUENT BUILDER — {@code Map<String, Map<String,String>>} tipli global filter.
 *
 * <p>Xarici sistemdən (REST, RPC, konfiqurasiya) gələn dinamik filterləri
 * tip-təhlükəsiz şəkildə qurmaq üçündür. Developer nə map strukturu ilə,
 * nə də əməliyyat adı sətirləri ilə məşğul olur.
 *
 * <p>Eyni əməliyyat üçün bir neçə sahə <b>avtomatik birləşdirilir</b>:
 * <pre>{@code
 *   Filters.of()
 *       .equal("status", "ACTIVE")
 *       .equal("type",   "USER")     // eyni əməliyyat → daxili map-ə əlavə olunur
 *       .like("firstName", "Ali")
 *       .greaterThan("o.amount", "100")    // join cədvəli
 *       .in("roleId", "1,2,3")             // vergüllə ayrılmış
 *       .isNull("deletedAt");
 * }</pre>
 *
 * <p>JooqManager ilə istifadə:
 * <pre>{@code
 *   jooq.setMainTable(User.class, "u")
 *       .addColumns("u.id", "u.name")
 *       .addFilter(
 *           Filters.of()
 *               .equal("status", "ACTIVE")
 *               .like("name",   "Ali")
 *       )
 *       .execute();
 * }</pre>
 */
public final class Filters {

    /** Daxili quruluş: operatorAdı → (sahəAdı → dəyər) */
    private final Map<String, Map<String, String>> map = new LinkedHashMap<>();

    private Filters() {}

    /** Yeni, boş Filters builder-i yaradır. */
    public static Filters of() {
        return new Filters();
    }

    // ─── Bərabərlik ──────────────────────────────────────────────────────

    /** {@code WHERE field = value} — null və ya boş dəyər atlanır */
    public Filters equal(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAl, field, value);
    }

    /**
     * {@code WHERE field = value} — rəqəm tipləri üçün
     * ({@code Long}, {@code Integer}, {@code Double}, {@code BigDecimal} və s.).
     * Primitiv {@code int}, {@code long}, {@code double} avtomatik autobox olunur.
     * Null dəyər atlanır.
     */
    public Filters equal(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAl, field, value.toString());
    }

    /** {@code WHERE field != value} — null və ya boş dəyər atlanır */
    public Filters notEqual(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAl, field, value);
    }

    /**
     * {@code WHERE field != value} — rəqəm tipləri üçün
     * ({@code Long}, {@code Integer}, {@code Double}, {@code BigDecimal} və s.).
     * Null dəyər atlanır.
     */
    public Filters notEqual(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAl, field, value.toString());
    }

    // ─── Müqayisə ────────────────────────────────────────────────────────

    /** {@code WHERE field > value} — null və ya boş dəyər atlanır */
    public Filters greaterThan(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN, field, value);
    }

    /**
     * {@code WHERE field > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThan(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN, field, value.toString());
    }

    /** {@code WHERE field >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqual(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO, field, value);
    }

    /**
     * {@code WHERE field >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqual(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO, field, value.toString());
    }

    /** {@code WHERE field < value} — null və ya boş dəyər atlanır */
    public Filters lessThan(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN, field, value);
    }

    /**
     * {@code WHERE field < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThan(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN, field, value.toString());
    }

    /** {@code WHERE field <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqual(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO, field, value);
    }

    /**
     * {@code WHERE field <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqual(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO, field, value.toString());
    }

    // ─── LIKE ────────────────────────────────────────────────────────────

    /** {@code WHERE field LIKE '%value%'} — null və ya boş dəyər atlanır */
    public Filters like(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LIKE, field, value);
    }

    /** {@code WHERE field LIKE 'value%'} — null və ya boş dəyər atlanır */
    public Filters startWith(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.START_WITH, field, value);
    }

    /** {@code WHERE field LIKE '%value'} — null və ya boş dəyər atlanır */
    public Filters endWith(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.END_WITH, field, value);
    }

    // ─── NULL yoxlaması ──────────────────────────────────────────────────

    /** {@code WHERE field IS NULL} */
    public Filters isNull(String field) {
        return put(FilterOperationConstants.IS_EMPTY, field, "");
    }

    /** {@code WHERE field IS NOT NULL} */
    public Filters isNotNull(String field) {
        return put(FilterOperationConstants.IS_NOT_EMPTY, field, "");
    }

    // ─── IN / NOT IN ─────────────────────────────────────────────────────

    /**
     * {@code WHERE field IN (...)}
     *
     * @param values vergüllə ayrılmış dəyərlər: {@code "1,2,3"}
     */
    public Filters in(String field, String values) {
        return put(FilterOperationConstants.IN, field, values);
    }

    /**
     * {@code WHERE field IN (...)} — {@link Collection} variantı ({@link List}, {@link Set}).
     *
     * <p>Null və ya boş kolleksiya atlanır. Kolleksiya elementləri {@code toString()} ilə
     * string-ə çevrilir — {@code Long}, {@code Integer}, {@code String} hamısı işləyir.
     *
     * <pre>{@code
     *   .in("roleId",  List.of(1L, 2L, 3L))
     *   .in("status",  Set.of("ACTIVE", "PENDING"))
     * }</pre>
     */
    public Filters in(String field, Collection<?> values) {
        if (values == null || values.isEmpty()) return this;
        return put(FilterOperationConstants.IN, field,
                values.stream().filter(Objects::nonNull)
                      .map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""));
    }

    /**
     * {@code WHERE field NOT IN (...)}
     *
     * @param values vergüllə ayrılmış dəyərlər: {@code "1,2,3"}
     */
    public Filters notIn(String field, String values) {
        return put(FilterOperationConstants.NOT_IN, field, values);
    }

    /**
     * {@code WHERE field NOT IN (...)} — {@link Collection} variantı ({@link List}, {@link Set}).
     *
     * <p>Null və ya boş kolleksiya atlanır.
     *
     * <pre>{@code
     *   .notIn("status",  List.of("DELETED", "BANNED"))
     *   .notIn("roleId",  Set.of(5L, 6L))
     * }</pre>
     */
    public Filters notIn(String field, Collection<?> values) {
        if (values == null || values.isEmpty()) return this;
        return put(FilterOperationConstants.NOT_IN, field,
                values.stream().filter(Objects::nonNull)
                      .map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""));
    }

    // ─── BETWEEN ─────────────────────────────────────────────────────────

    /**
     * {@code WHERE field BETWEEN from AND to}
     *
     * <p>Null/boş dəyərlər qismən dəstəklənir:
     * <ul>
     *   <li>Hər ikisi dolu → {@code BETWEEN from AND to}</li>
     *   <li>Yalnız from   → {@code >= from}</li>
     *   <li>Yalnız to     → {@code <= to}</li>
     *   <li>Hər ikisi null/boş → şərt əlavə edilmir</li>
     * </ul>
     *
     * <pre>{@code
     *   .between("createdAt", "2024-01-01", "2024-12-31")
     *   .between("age",       "18",         "65")
     * }</pre>
     */
    public Filters between(String field, String from, String to) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo   = to   != null && !to.isBlank();
        if (hasFrom && hasTo) return put(FilterOperationConstants.BETWEEN,                  field, from + "," + to);
        if (hasFrom)          return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO, field, from);
        if (hasTo)            return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO,    field, to);
        return this;
    }

    /**
     * {@code WHERE field BETWEEN from AND to} — {@link Number} tipləri üçün
     * ({@code Long}, {@code BigDecimal}, {@code Integer}, {@code BigInteger} və s.)
     *
     * <p>Null dəyərlər qismən dəstəklənir — yuxarıdakı String variantı ilə eyni məntiq.
     *
     * <pre>{@code
     *   .between("createdAt", 20240101000000L,         20241231235959L)
     *   .between("price",     new BigDecimal("10.00"), new BigDecimal("99.99"))
     *   .between("count",     1,                       100)
     * }</pre>
     */
    public Filters between(String field, Number from, Number to) {
        return between(field,
                from != null ? from.toString() : null,
                to   != null ? to.toString()   : null);
    }

    // ─── REGEXP ──────────────────────────────────────────────────────────

    /** {@code WHERE field REGEXP pattern} */
    public Filters regexp(String field, String pattern) {
        return put(FilterOperationConstants.REGEXP, field, pattern);
    }

    /** {@code WHERE field NOT REGEXP pattern} */
    public Filters notRegexp(String field, String pattern) {
        return put(FilterOperationConstants.NOT_REGEXP, field, pattern);
    }

    // ─── ROUND müqayisə əməliyyatları (hesablanmayan sütunlar üçün) ──────
    //
    // WHERE ROUND(field, scale) OP value
    //   .equalRound2("unitCost", "9.99")        → WHERE ROUND(unit_cost, 2) = 9.99
    //   .greaterThanRound0("totalPrice", 100)    → WHERE ROUND(total_price, 0) > 100

    // ── Scale 0 — tam ədədə yuvarlama ──────────────────────────────────────

    /** {@code WHERE ROUND(field, 0) = value} — null və ya boş dəyər atlanır */
    public Filters equalRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) = value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters equalRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_0, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 0) != value} — null və ya boş dəyər atlanır */
    public Filters notEqualRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) != value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters notEqualRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_0, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 0) > value} — null və ya boş dəyər atlanır */
    public Filters greaterThanRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_0, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 0) >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqualToRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqualToRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_0, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 0) < value} — null və ya boş dəyər atlanır */
    public Filters lessThanRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_0, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 0) <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqualToRound0(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_0, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 0) <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqualToRound0(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_0, field, value.toString());
    }


    // ── Scale 1 — bir onluq rəqəm ──────────────────────────────────────

    /** {@code WHERE ROUND(field, 1) = value} — null və ya boş dəyər atlanır */
    public Filters equalRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) = value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters equalRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_1, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 1) != value} — null və ya boş dəyər atlanır */
    public Filters notEqualRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) != value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters notEqualRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_1, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 1) > value} — null və ya boş dəyər atlanır */
    public Filters greaterThanRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_1, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 1) >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqualToRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqualToRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_1, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 1) < value} — null və ya boş dəyər atlanır */
    public Filters lessThanRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_1, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 1) <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqualToRound1(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_1, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 1) <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqualToRound1(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_1, field, value.toString());
    }


    // ── Scale 2 — iki onluq rəqəm (qiymət/məbləğ üçün ən çox istifadə olunur) ──────────────────────────────────────

    /** {@code WHERE ROUND(field, 2) = value} — null və ya boş dəyər atlanır */
    public Filters equalRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) = value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters equalRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_2, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 2) != value} — null və ya boş dəyər atlanır */
    public Filters notEqualRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) != value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters notEqualRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_2, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 2) > value} — null və ya boş dəyər atlanır */
    public Filters greaterThanRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_2, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 2) >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqualToRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqualToRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_2, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 2) < value} — null və ya boş dəyər atlanır */
    public Filters lessThanRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_2, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 2) <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqualToRound2(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_2, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 2) <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqualToRound2(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_2, field, value.toString());
    }


    // ── Scale 3 — üç onluq rəqəm ──────────────────────────────────────

    /** {@code WHERE ROUND(field, 3) = value} — null və ya boş dəyər atlanır */
    public Filters equalRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) = value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters equalRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_3, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 3) != value} — null və ya boş dəyər atlanır */
    public Filters notEqualRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) != value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters notEqualRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_3, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 3) > value} — null və ya boş dəyər atlanır */
    public Filters greaterThanRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_3, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 3) >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqualToRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqualToRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_3, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 3) < value} — null və ya boş dəyər atlanır */
    public Filters lessThanRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_3, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 3) <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqualToRound3(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_3, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 3) <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqualToRound3(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_3, field, value.toString());
    }


    // ── Scale 4 — dörd onluq rəqəm ──────────────────────────────────────

    /** {@code WHERE ROUND(field, 4) = value} — null və ya boş dəyər atlanır */
    public Filters equalRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) = value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters equalRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.EQUAL_ROUND_4, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 4) != value} — null və ya boş dəyər atlanır */
    public Filters notEqualRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) != value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters notEqualRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.NOT_EQUAL_ROUND_4, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 4) > value} — null və ya boş dəyər atlanır */
    public Filters greaterThanRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) > value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_ROUND_4, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 4) >= value} — null və ya boş dəyər atlanır */
    public Filters greaterThanOrEqualToRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) >= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters greaterThanOrEqualToRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_4, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 4) < value} — null və ya boş dəyər atlanır */
    public Filters lessThanRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) < value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_ROUND_4, field, value.toString());
    }

    /** {@code WHERE ROUND(field, 4) <= value} — null və ya boş dəyər atlanır */
    public Filters lessThanOrEqualToRound4(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_4, field, value);
    }

    /**
     * {@code WHERE ROUND(field, 4) <= value} — rəqəm tipləri üçün.
     * Null dəyər atlanır.
     */
    public Filters lessThanOrEqualToRound4(String field, Number value) {
        if (value == null) return this;
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_4, field, value.toString());
    }


    // ─── Türk əlifbası case-insensitive LIKE ─────────────────────────────

    /** {@code WHERE LOWER(...) LIKE '%value%'} — Türk İ/I-yə uyğun case-insensitive. Null/boş dəyər atlanır */
    public Filters likeIgnoreCase(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.LIKE_IGNORE_CASE, field, value);
    }

    /** {@code WHERE LOWER(...) LIKE 'value%'} — Türk İ/I-yə uyğun case-insensitive. Null/boş dəyər atlanır */
    public Filters startWithIgnoreCase(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.START_WITH_IGNORE_CASE, field, value);
    }

    /** {@code WHERE LOWER(...) LIKE '%value'} — Türk İ/I-yə uyğun case-insensitive. Null/boş dəyər atlanır */
    public Filters endWithIgnoreCase(String field, String value) {
        if (value == null || value.isBlank()) return this;
        return put(FilterOperationConstants.END_WITH_IGNORE_CASE, field, value);
    }

    // ─── Birləşdirmə ─────────────────────────────────────────────────────

    /**
     * Başqa bir {@link Filters}-i bu filter-ə birləşdirir.
     *
     * <p>Eyni əməliyyat + sahə konflikt yaratdıqda — bu filter-in dəyəri üstün gəlir.
     *
     * <pre>{@code
     *   Filters base = Filters.of().equal("tenantId", tenantId);
     *   Filters req  = Filters.of().like("name", name).equal("status", "ACTIVE");
     *   jooq.addFilter(base.merge(req));
     * }</pre>
     */
    public Filters merge(Filters other) {
        if (other == null) return this;
        for (Map.Entry<String, Map<String, String>> e : other.map.entrySet()) {
            map.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
               .putAll(e.getValue());
        }
        return this;
    }

    // ─── Build ───────────────────────────────────────────────────────────

    /**
     * Daxili map-i qaytarır — {@link az.mbm.jooqsqlgenerate.JooqManager#addFilter(Filters)}
     * tərəfindən istifadə olunur.
     *
     * <p>Boş filter üçün boş map qaytarılır — {@code addFilter} bunu nəzərə alır.
     */
    public Map<String, Map<String, String>> build() {
        return Collections.unmodifiableMap(map);
    }

    // ─── Private ─────────────────────────────────────────────────────────

    private Filters put(String op, String field, String value) {
        if (field == null || field.isBlank()) return this;
        if (value == null) return this;
        map.computeIfAbsent(op, k -> new LinkedHashMap<>()).put(field, value);
        return this;
    }
}
