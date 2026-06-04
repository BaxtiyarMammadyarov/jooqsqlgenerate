package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * Verilənlər bazasına görə tarix/vaxt formatını string-ə çevirən yardımçı sinif.
 *
 * <p>Pattern PostgreSQL/Oracle sintaksisindədir:
 * <pre>
 *   YYYY   — 4 rəqəmli il
 *   MM     — ay (01-12)
 *   DD     — gün (01-31)
 *   HH24   — saat 24-saatlıq (00-23)
 *   HH12   — saat 12-saatlıq (01-12)
 *   MI     — dəqiqə (00-59)
 *   SS     — saniyə (00-59)
 *   MON    — qısaldılmış ay adı (Jan, Feb ...)
 *   MONTH  — tam ay adı (January ...)
 *   DAY    — tam gün adı (Monday ...)
 *   DY     — qısaldılmış gün adı (Mon ...)
 *   AM/PM  — AM/PM göstəricisi
 * </pre>
 *
 * <p>DB → funksiya xəritəsi:
 * <ul>
 *   <li>PostgreSQL / Oracle / H2 / default → {@code TO_CHAR(field, 'pattern')}
 *   <li>MySQL / MariaDB                    → {@code DATE_FORMAT(field, '%Y-%m-%d ...')}
 *   <li>SQL Server                         → {@code FORMAT(field, 'yyyy-MM-dd ...')}
 * </ul>
 */
final class DateFormatHelper {

    private DateFormatHelper() {}

    /**
     * DB dialektinə uyğun format ifadəsi qaytarır.
     *
     * @param src      formatlanacaq sahə
     * @param pattern  PostgreSQL/Oracle sintaksisində pattern (məs. {@code "YYYY-MM-DD"})
     * @param dialect  jOOQ {@link SQLDialect}
     */
    static Field<String> toDialectField(Field<?> src, String pattern, SQLDialect dialect) {
        String family = dialect.family().name();
        return switch (family) {
            case "MYSQL", "MARIADB" ->
                    DSL.field("DATE_FORMAT({0}, {1})", String.class,
                              src, DSL.inline(toMySqlPattern(pattern)));
            case "SQLSERVER" ->
                    DSL.field("FORMAT({0}, {1})", String.class,
                              src, DSL.inline(toMsSqlPattern(pattern)));
            default ->
                    // PostgreSQL, Oracle, H2, Derby, HSQLDB, SQLite ...
                    DSL.field("TO_CHAR({0}, {1})", String.class,
                              src, DSL.inline(pattern));
        };
    }

    // ─── Pattern çeviriciləri ─────────────────────────────────────────────

    /**
     * PostgreSQL/Oracle pattern-ini MySQL {@code DATE_FORMAT} pattern-inə çevirir.
     *
     * <pre>
     *   YYYY → %Y,  MM → %m,  DD → %d
     *   HH24 → %H,  HH12 → %h,  HH → %H
     *   MI   → %i,  SS   → %s
     *   MONTH → %M, MON → %b, DAY → %W, DY → %a
     *   AM/PM → %p, US → %f
     * </pre>
     */
    static String toMySqlPattern(String p) {
        return p
                .replace("YYYY",  "%Y")
                .replace("YYY",   "%Y")
                .replace("YY",    "%y")
                .replace("MONTH", "%M")
                .replace("MON",   "%b")
                .replace("MM",    "%m")
                .replace("DD",    "%d")
                .replace("HH24",  "%H")
                .replace("HH12",  "%h")
                .replace("HH",    "%H")
                .replace("MI",    "%i")
                .replace("SS",    "%s")
                .replace("DAY",   "%W")
                .replace("DY",    "%a")
                .replace("AM",    "%p")
                .replace("PM",    "%p")
                .replace("US",    "%f");
    }

    /**
     * PostgreSQL/Oracle pattern-ini MSSQL {@code FORMAT} pattern-inə çevirir.
     *
     * <pre>
     *   YYYY → yyyy,  MM → MM (eyni),  DD → dd
     *   HH24 → HH,    HH12 → hh,       HH → HH
     *   MI   → mm,    SS   → ss
     *   MONTH → MMMM, MON → MMM, DAY → dddd, DY → ddd
     *   AM/PM → tt,   US → ffffff
     * </pre>
     */
    static String toMsSqlPattern(String p) {
        return p
                .replace("YYYY",  "yyyy")
                .replace("YYY",   "yyy")
                .replace("YY",    "yy")
                .replace("MONTH", "MMMM")
                .replace("MON",   "MMM")
                // "MM" MSSQL-də eynidir — dəyişmə
                .replace("DD",    "dd")
                .replace("HH24",  "HH")
                .replace("HH12",  "hh")
                .replace("HH",    "HH")
                .replace("MI",    "mm")
                .replace("SS",    "ss")
                .replace("DAY",   "dddd")
                .replace("DY",    "ddd")
                .replace("AM",    "tt")
                .replace("PM",    "tt")
                .replace("US",    "ffffff");
    }
}
