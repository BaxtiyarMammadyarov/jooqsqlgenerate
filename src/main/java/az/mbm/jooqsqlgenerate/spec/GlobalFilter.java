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
 *   GlobalFilter.of()
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
 *       .addGlobalFilter(
 *           GlobalFilter.of()
 *               .equal("status", "ACTIVE")
 *               .like("name",   "Ali")
 *       )
 *       .execute();
 * }</pre>
 */
public final class GlobalFilter {

    /** Daxili quruluş: operatorAdı → (sahəAdı → dəyər) */
    private final Map<String, Map<String, String>> map = new LinkedHashMap<>();

    private GlobalFilter() {}

    /** Yeni, boş GlobalFilter builder-i yaradır. */
    public static GlobalFilter of() {
        return new GlobalFilter();
    }

    // ─── Bərabərlik ──────────────────────────────────────────────────────

    /** {@code WHERE field = value} */
    public GlobalFilter equal(String field, String value) {
        return put(FilterOperationConstants.EQUAl, field, value);
    }

    /** {@code WHERE field != value} */
    public GlobalFilter notEqual(String field, String value) {
        return put(FilterOperationConstants.NOT_EQUAl, field, value);
    }

    // ─── Müqayisə ────────────────────────────────────────────────────────

    /** {@code WHERE field > value} */
    public GlobalFilter greaterThan(String field, String value) {
        return put(FilterOperationConstants.GREATER_THAN, field, value);
    }

    /** {@code WHERE field >= value} */
    public GlobalFilter greaterThanOrEqual(String field, String value) {
        return put(FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO, field, value);
    }

    /** {@code WHERE field < value} */
    public GlobalFilter lessThan(String field, String value) {
        return put(FilterOperationConstants.LESS_THAN, field, value);
    }

    /** {@code WHERE field <= value} */
    public GlobalFilter lessThanOrEqual(String field, String value) {
        return put(FilterOperationConstants.LESS_THAN_OR_EQUAL_TO, field, value);
    }

    // ─── LIKE ────────────────────────────────────────────────────────────

    /** {@code WHERE field LIKE '%value%'} */
    public GlobalFilter like(String field, String value) {
        return put(FilterOperationConstants.LIKE, field, value);
    }

    /** {@code WHERE field LIKE 'value%'} */
    public GlobalFilter startWith(String field, String value) {
        return put(FilterOperationConstants.START_WITH, field, value);
    }

    /** {@code WHERE field LIKE '%value'} */
    public GlobalFilter endWith(String field, String value) {
        return put(FilterOperationConstants.END_WITH, field, value);
    }

    // ─── NULL yoxlaması ──────────────────────────────────────────────────

    /** {@code WHERE field IS NULL} */
    public GlobalFilter isNull(String field) {
        return put(FilterOperationConstants.IS_EMPTY, field, "");
    }

    /** {@code WHERE field IS NOT NULL} */
    public GlobalFilter isNotNull(String field) {
        return put(FilterOperationConstants.IS_NOT_EMPTY, field, "");
    }

    // ─── IN / NOT IN ─────────────────────────────────────────────────────

    /**
     * {@code WHERE field IN (...)}
     *
     * @param values vergüllə ayrılmış dəyərlər: {@code "1,2,3"}
     */
    public GlobalFilter in(String field, String values) {
        return put(FilterOperationConstants.IN, field, values);
    }

    /**
     * {@code WHERE field IN (...)} — varargs variantı.
     *
     * <pre>{@code .in("roleId", "1", "2", "3") }</pre>
     */
    public GlobalFilter in(String field, String... values) {
        return put(FilterOperationConstants.IN, field, String.join(",", values));
    }

    /**
     * {@code WHERE field NOT IN (...)}
     *
     * @param values vergüllə ayrılmış dəyərlər: {@code "1,2,3"}
     */
    public GlobalFilter notIn(String field, String values) {
        return put(FilterOperationConstants.NOT_IN, field, values);
    }

    /**
     * {@code WHERE field NOT IN (...)} — varargs variantı.
     *
     * <pre>{@code .notIn("status", "DELETED", "BANNED") }</pre>
     */
    public GlobalFilter notIn(String field, String... values) {
        return put(FilterOperationConstants.NOT_IN, field, String.join(",", values));
    }

    // ─── BETWEEN ─────────────────────────────────────────────────────────

    /**
     * {@code WHERE field BETWEEN from AND to}
     *
     * <pre>{@code
     *   .between("createdAt", "2024-01-01", "2024-12-31")
     *   .between("age",       "18",         "65")
     * }</pre>
     */
    public GlobalFilter between(String field, String from, String to) {
        return put(FilterOperationConstants.BETWEEN, field, from + "," + to);
    }

    // ─── REGEXP ──────────────────────────────────────────────────────────

    /** {@code WHERE field REGEXP pattern} */
    public GlobalFilter regexp(String field, String pattern) {
        return put(FilterOperationConstants.REGEXP, field, pattern);
    }

    /** {@code WHERE field NOT REGEXP pattern} */
    public GlobalFilter notRegexp(String field, String pattern) {
        return put(FilterOperationConstants.NOT_REGEXP, field, pattern);
    }

    // ─── Birləşdirmə ─────────────────────────────────────────────────────

    /**
     * Başqa bir {@link GlobalFilter}-i bu filter-ə birləşdirir.
     *
     * <p>Eyni əməliyyat + sahə konflikt yaratdıqda — bu filter-in dəyəri üstün gəlir.
     *
     * <pre>{@code
     *   GlobalFilter base = GlobalFilter.of().equal("tenantId", tenantId);
     *   GlobalFilter req  = GlobalFilter.of().like("name", name).equal("status", "ACTIVE");
     *   jooq.addGlobalFilter(base.merge(req));
     * }</pre>
     */
    public GlobalFilter merge(GlobalFilter other) {
        if (other == null) return this;
        for (Map.Entry<String, Map<String, String>> e : other.map.entrySet()) {
            map.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
               .putAll(e.getValue());
        }
        return this;
    }

    // ─── Build ───────────────────────────────────────────────────────────

    /**
     * Daxili map-i qaytarır — {@link az.mbm.jooqsqlgenerate.JooqManager#addGlobalFilter}
     * tərəfindən istifadə olunur.
     *
     * <p>Boş filter üçün boş map qaytarılır — {@code addGlobalFilter} bunu nəzərə alır.
     */
    public Map<String, Map<String, String>> build() {
        return Collections.unmodifiableMap(map);
    }

    // ─── Private ─────────────────────────────────────────────────────────

    private GlobalFilter put(String op, String field, String value) {
        if (field == null || field.isBlank()) return this;
        if (value == null) return this;
        map.computeIfAbsent(op, k -> new LinkedHashMap<>()).put(field, value);
        return this;
    }
}
