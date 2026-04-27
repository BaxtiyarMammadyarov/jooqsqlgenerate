package az.mbm.jooqsqlgenerate.enums;

/**
 * {@link JooqManager#addFilters} metodunda istifadə üçün filter əməliyyat adları.
 *
 * <p>Bu konstantlar {@code Map<String, Map<String, String>>} strukturunun <em>açar</em>
 * (operation key) hissəsində istifadə olunur:
 *
 * <pre>{@code
 *   import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;
 *
 *   Map<String, Map<String, String>> globalFilter = Map.of(
 *       EQUAl,              Map.of("status",    "ACTIVE"),
 *       LIKE,               Map.of("firstName", "Ali"),
 *       GREATER_THAN,       Map.of("o.amount",  "100"),    // join cədvəli
 *       IN,                 Map.of("roleId",    "1,2,3"),  // vergüllə ayrılmış
 *       IS_EMPTY,           Map.of("deletedAt", ""),       // IS NULL
 *       BETWEEN,            Map.of("createdAt", "2024-01-01,2024-12-31")
 *   );
 *
 *   jooq.addFilters(globalFilter);
 * }</pre>
 *
 * <p>Hər bir constant {@code parseOperation()} tərəfindən {@link Op}-a çevrilir.
 */
public final class FilterOperationConstants {

    private FilterOperationConstants() {}

    // ─── Bərabərlik ──────────────────────────────────────────────────────

    /** {@code WHERE field = value} */
    public static final String EQUAl                    = "equal";

    /** {@code WHERE field != value} */
    public static final String NOT_EQUAl                = "notEqual";

    // ─── Müqayisə ────────────────────────────────────────────────────────

    /** {@code WHERE field > value} */
    public static final String GREATER_THAN             = "greaterThan";

    /** {@code WHERE field >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO = "greaterThanOrEqualTo";

    /** {@code WHERE field < value} */
    public static final String LESS_THAN                = "lessThan";

    /** {@code WHERE field <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO    = "lessThanOrEqualTo";

    // ─── LIKE ────────────────────────────────────────────────────────────

    /** {@code WHERE field LIKE '%value%'} */
    public static final String LIKE                     = "like";

    /** {@code WHERE field LIKE 'value%'} */
    public static final String START_WITH               = "startWith";

    /** {@code WHERE field LIKE '%value'} */
    public static final String END_WITH                 = "endWith";

    // ─── NULL yoxlaması ──────────────────────────────────────────────────

    /** {@code WHERE field IS NULL} */
    public static final String IS_EMPTY                 = "isEmpty";

    /** {@code WHERE field IS NOT NULL} */
    public static final String IS_NOT_EMPTY             = "isNotEmpty";

    // ─── IN / NOT IN ─────────────────────────────────────────────────────

    /**
     * {@code WHERE field IN (...)}
     * <p>Dəyər vergüllə ayrılmış sətir kimi verilir: {@code "1,2,3"}
     */
    public static final String IN                       = "in";

    /**
     * {@code WHERE field NOT IN (...)}
     * <p>Dəyər vergüllə ayrılmış sətir kimi verilir: {@code "1,2,3"}
     */
    public static final String NOT_IN                   = "notIn";

    // ─── BETWEEN ─────────────────────────────────────────────────────────

    /**
     * {@code WHERE field BETWEEN from AND to}
     * <p>Dəyər {@code "from,to"} formatında verilir: {@code "2024-01-01,2024-12-31"}
     */
    public static final String BETWEEN                  = "between";

    // ─── REGEXP ──────────────────────────────────────────────────────────

    /** {@code WHERE field REGEXP pattern} */
    public static final String REGEXP                   = "regexp";

    /** {@code WHERE field NOT REGEXP pattern} */
    public static final String NOT_REGEXP               = "notRegexp";

    // ─── ROUND müqayisə əməliyyatları ────────────────────────────────────
    //
    // Hesablanmayan sütunlara ROUND(field, N) tətbiq edərək müqayisə edir.
    // Global filter map-lərdə açar kimi istifadə olunur:
    //
    //   .globalFilter("totalPrice", Map.of(GREATER_THAN_ROUND_2, "100"))
    //   // → WHERE ROUND(total_price, 2) > 100
    //
    //   .globalFilter("unitCost", Map.of(EQUAL_ROUND_2, "9.99"))
    //   // → WHERE ROUND(unit_cost, 2) = 9.99

    // ── Scale 0 — tam ədədə yuvarlama ────────────────────────────────────
    // ROUND(0.4, 0) = 0  |  ROUND(0.6, 0) = 1  |  ROUND(1.5, 0) = 2

    /** {@code WHERE ROUND(field, 0) = value} */
    public static final String EQUAL_ROUND_0                    = "equalRound0";
    /** {@code WHERE ROUND(field, 0) != value} */
    public static final String NOT_EQUAL_ROUND_0                = "notEqualRound0";
    /** {@code WHERE ROUND(field, 0) > value} */
    public static final String GREATER_THAN_ROUND_0             = "greaterThanRound0";
    /** {@code WHERE ROUND(field, 0) >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO_ROUND_0 = "greaterThanOrEqualToRound0";
    /** {@code WHERE ROUND(field, 0) < value} */
    public static final String LESS_THAN_ROUND_0                = "lessThanRound0";
    /** {@code WHERE ROUND(field, 0) <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO_ROUND_0    = "lessThanOrEqualToRound0";

    // ── Scale 1 ──────────────────────────────────────────────────────────

    /** {@code WHERE ROUND(field, 1) = value} */
    public static final String EQUAL_ROUND_1                    = "equalRound1";
    /** {@code WHERE ROUND(field, 1) != value} */
    public static final String NOT_EQUAL_ROUND_1                = "notEqualRound1";
    /** {@code WHERE ROUND(field, 1) > value} */
    public static final String GREATER_THAN_ROUND_1             = "greaterThanRound1";
    /** {@code WHERE ROUND(field, 1) >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO_ROUND_1 = "greaterThanOrEqualToRound1";
    /** {@code WHERE ROUND(field, 1) < value} */
    public static final String LESS_THAN_ROUND_1                = "lessThanRound1";
    /** {@code WHERE ROUND(field, 1) <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO_ROUND_1    = "lessThanOrEqualToRound1";

    // ── Scale 2 (qiymət / məbləğ üçün ən çox istifadə olunur) ───────────

    /** {@code WHERE ROUND(field, 2) = value} */
    public static final String EQUAL_ROUND_2                    = "equalRound2";
    /** {@code WHERE ROUND(field, 2) != value} */
    public static final String NOT_EQUAL_ROUND_2                = "notEqualRound2";
    /** {@code WHERE ROUND(field, 2) > value} */
    public static final String GREATER_THAN_ROUND_2             = "greaterThanRound2";
    /** {@code WHERE ROUND(field, 2) >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO_ROUND_2 = "greaterThanOrEqualToRound2";
    /** {@code WHERE ROUND(field, 2) < value} */
    public static final String LESS_THAN_ROUND_2                = "lessThanRound2";
    /** {@code WHERE ROUND(field, 2) <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO_ROUND_2    = "lessThanOrEqualToRound2";

    // ── Scale 3 ──────────────────────────────────────────────────────────

    /** {@code WHERE ROUND(field, 3) = value} */
    public static final String EQUAL_ROUND_3                    = "equalRound3";
    /** {@code WHERE ROUND(field, 3) != value} */
    public static final String NOT_EQUAL_ROUND_3                = "notEqualRound3";
    /** {@code WHERE ROUND(field, 3) > value} */
    public static final String GREATER_THAN_ROUND_3             = "greaterThanRound3";
    /** {@code WHERE ROUND(field, 3) >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO_ROUND_3 = "greaterThanOrEqualToRound3";
    /** {@code WHERE ROUND(field, 3) < value} */
    public static final String LESS_THAN_ROUND_3                = "lessThanRound3";
    /** {@code WHERE ROUND(field, 3) <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO_ROUND_3    = "lessThanOrEqualToRound3";

    // ── Scale 4 ──────────────────────────────────────────────────────────

    /** {@code WHERE ROUND(field, 4) = value} */
    public static final String EQUAL_ROUND_4                    = "equalRound4";
    /** {@code WHERE ROUND(field, 4) != value} */
    public static final String NOT_EQUAL_ROUND_4                = "notEqualRound4";
    /** {@code WHERE ROUND(field, 4) > value} */
    public static final String GREATER_THAN_ROUND_4             = "greaterThanRound4";
    /** {@code WHERE ROUND(field, 4) >= value} */
    public static final String GREATER_THAN_OR_EQUAL_TO_ROUND_4 = "greaterThanOrEqualToRound4";
    /** {@code WHERE ROUND(field, 4) < value} */
    public static final String LESS_THAN_ROUND_4                = "lessThanRound4";
    /** {@code WHERE ROUND(field, 4) <= value} */
    public static final String LESS_THAN_OR_EQUAL_TO_ROUND_4    = "lessThanOrEqualToRound4";

    // ─── Türk əlifbası case-insensitive LIKE ─────────────────────────────
    //
    // LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%val%'
    // Həm sahə, həm filter dəyəri normallaşdırılır.
    // Türk dilindəki İ/I hərflərini standart LOWER() düzgün idarə etmir,
    // bu sabitlər DB locale-dan asılı olmayan case-insensitive axtarış üçündür.
    //
    // Nümunə:
    //   .globalFilter("firstName", Map.of(LIKE_IGNORE_CASE, "İlkin"))
    //   // "ilkin", "İlkin", "ILKIN" — hamısı tapılır

    /** {@code WHERE LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%value%'} */
    public static final String LIKE_IGNORE_CASE          = "likeIgnoreCase";

    /** {@code WHERE LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE 'value%'} */
    public static final String START_WITH_IGNORE_CASE    = "startWithIgnoreCase";

    /** {@code WHERE LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%value'} */
    public static final String END_WITH_IGNORE_CASE      = "endWithIgnoreCase";
}
