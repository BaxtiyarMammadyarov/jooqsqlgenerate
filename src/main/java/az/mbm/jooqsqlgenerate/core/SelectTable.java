package az.mbm.jooqsqlgenerate.core;

import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;

/**
 * SELECT sorğusunun nəticəsi + sətir sayı.
 *
 * <p>{@link az.mbm.jooqsqlgenerate.builder.SelectQueryBuilder#build} tərəfindən
 * qaytarılır. {@link SelectFetchJooq} vasitəsilə obyektə, Map-ə və ya
 * Record-a çevrilə bilər.
 *
 * <h3>Derived table (subquery-dən cədvəl)</h3>
 * <p>{@link #asTable(String)} metodu sorğunu başqa bir sorğunun {@code FROM} hissəsinə
 * yerləşdirilə bilən derived table-a çevirir:
 *
 * <pre>{@code
 *   SelectTable inner = JooqQuery.from(USERS, "u")
 *       .select("id", "firstName", "status")
 *       .filter("status", EQUAl, "ACTIVE")
 *       .execute(dsl);
 *
 *   // inner-i derived table kimi istifadə et
 *   JooqQuery.from(inner, "sub")
 *       .select("id", "firstName")
 *       .filter("firstName", LIKE, "Ali")
 *       .leftJoin(ORDERS, "o", inner.asTable("sub").field("id", Long.class).eq(ORDERS.USER_ID))
 *       .execute(dsl);
 * }</pre>
 */
public class SelectTable {

    private final Select<?> query;
    private final int       rowCount;

    public SelectTable(Select<?> query, int rowCount) {
        this.query    = query;
        this.rowCount = rowCount;
    }

    /** jOOQ SELECT sorğusu (fetch üçün) */
    public Select<?> getSelectTable() {
        return query;
    }

    /** Pagination üçün ümumi sətir sayı (0 = sayılmayıb) */
    public Integer getRowCount() {
        return rowCount;
    }

    /**
     * Bu sorğunu {@code FROM (SELECT ...) alias} kimi işlənə bilən
     * derived table-a çevirir.
     *
     * <p>Nəticəni {@link az.mbm.jooqsqlgenerate.JooqQuery#from(SelectTable, String)}
     * və ya birbaşa jOOQ-da {@code .from(table)} kimi istifadə et.
     *
     * <pre>{@code
     *   Table<?> sub = activeUsers.asTable("sub");
     *
     *   // sub.field("id") → derived table-ın id sütunu
     *   // sub.field("firstName") → derived table-ın firstName sütunu
     *
     *   dsl.select(sub.asterisk(), ORDERS.AMOUNT)
     *      .from(sub)
     *      .leftJoin(ORDERS).on(sub.field("id", Long.class).eq(ORDERS.USER_ID))
     *      .fetch();
     * }</pre>
     *
     * @param alias SQL-də derived table-ın alias adı
     * @return jOOQ {@link Table} — {@code FROM}, {@code JOIN}, {@code field()} üçün istifadə olunur
     */
    @SuppressWarnings("unchecked")
    public Table<Record> asTable(String alias) {
        return ((Select<Record>) query).asTable(alias);
    }
}
