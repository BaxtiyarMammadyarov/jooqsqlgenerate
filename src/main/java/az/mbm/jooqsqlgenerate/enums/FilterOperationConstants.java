package az.mbm.jooqsqlgenerate.enums;

/**
 * {@link JooqManager#addGlobalFilter} metodunda istifadə üçün filter əməliyyat adları.
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
 *   jooq.addGlobalFilter(globalFilter);
 * }</pre>
 *
 * <p>Hər bir constant {@code parseOperation()} tərəfindən {@link FilterOperations}-a çevrilir.
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
}
