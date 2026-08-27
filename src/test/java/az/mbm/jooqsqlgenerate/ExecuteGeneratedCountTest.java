package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.core.SelectTable;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * {@link JooqQuery#executeGenerated} — COUNT / pagination bayraqlarının davranış testləri.
 *
 * <p>Real in-memory H2 DB istifadə olunur ({@code MockConnection} rowCount qaytara bilmir).
 * Generated / derived-table yolu {@code JooqQuery.from(SelectTable, alias)} ilə işə salınır.
 *
 * <p>Yoxlanılan bayraqlar (v1.1.57 düzəlişi — entity mode {@code execute()} ilə eyni):
 * <ul>
 *   <li>{@code withCount()} — LIMIT/OFFSET yox, amma rowCount dolur</li>
 *   <li>{@code skipCount()} — pagination var, COUNT atlanır → rowCount = -1</li>
 *   <li>{@code onlyCount()} — əsas data icra edilmir (boş), rowCount dolur</li>
 *   <li>GROUP BY + COUNT — xam sətir yox, qrup sayı qaytarılır</li>
 * </ul>
 */
class ExecuteGeneratedCountTest {

    private Connection conn;
    private DSLContext dsl;

    @BeforeEach
    void setUp() throws Exception {
        // H2-ni PostgreSQL uyğunluq rejimində açırıq — kitabxana POSTGRES dialect ilə
        // render edir; DATABASE_TO_LOWER identifikatorları postgres kimi kiçik hərflə saxlayır.
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:egc_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dsl = DSL.using(conn, SQLDialect.POSTGRES);
        dsl.execute("CREATE TABLE warehouse_flow (" +
                "id BIGINT, status VARCHAR(50), fk_class_id BIGINT)");
        // 5 xam sətir; fk_class_id → 3 fərqli qrup (10, 20, 30)
        dsl.execute("INSERT INTO warehouse_flow (id, status, fk_class_id) VALUES " +
                "(1,'A',10),(2,'A',10),(3,'A',20),(4,'B',20),(5,'B',30)");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    /** Generated/derived mode üçün mənbə — sahələri məlum derived table (executeGenerated yolu). */
    private SelectTable flows() {
        return new SelectTable(
                dsl.select(field(name("id"),          Long.class),
                           field(name("status"),      String.class),
                           field(name("fk_class_id"), Long.class))
                   .from(table(name("warehouse_flow"))),
                0);
    }

    /** Əsas data sorğusunun qaytardığı sətir sayı (SelectTable-in daxili sorğusunu icra edir). */
    private int dataRowCount(SelectTable st) {
        return dsl.fetch(st.getSelectTable()).size();
    }

    // ─── Testlər ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("withCount(): LIMIT yoxdur, rowCount ümumi sayı verir")
    void withCountReturnsTotalWithoutPagination() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.id", "t.status")
                .withCount()
                .execute(dsl);

        assertThat(st.getRowCount())
                .as("withCount() rowCount-u doldurmalıdır (əvvəllər 0 qalırdı)")
                .isEqualTo(5);
        assertThat(dataRowCount(st))
                .as("withCount()-da data tam qayıdır (LIMIT yox)")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("page().skipCount(): təmiz pagination COUNT-u atlanır → rowCount = -1")
    void skipCountLeavesRowCountAsSentinel() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.id")
                .page(0, 2)
                .skipCount()
                .execute(dsl);

        assertThat(st.getRowCount())
                .as("skipCount() təmiz pagination COUNT-unu atlamalıdır → -1")
                .isEqualTo(-1);
        assertThat(dataRowCount(st))
                .as("pagination işləyir: LIMIT 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("onlyCount(): əsas data icra edilmir (boş), rowCount dolur")
    void onlyCountReturnsEmptyDataButRowCount() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.id", "t.status")
                .onlyCount()
                .execute(dsl);

        assertThat(st.getRowCount())
                .as("onlyCount() rowCount-u doldurmalıdır")
                .isEqualTo(5);
        assertThat(dataRowCount(st))
                .as("onlyCount() əsas data sorğusunu icra etməməlidir (boş nəticə)")
                .isZero();
    }

    @Test
    @DisplayName("GROUP BY + COUNT: xam sətir yox, qrup sayı qaytarılır")
    void groupByCountReturnsGroupCountNotRawRows() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.fk_class_id")
                .groupBy("t.fk_class_id")
                .page(0, 10)
                .execute(dsl);

        // 5 xam sətir, amma 3 fərqli fk_class_id → COUNT qrup sayını verməlidir
        assertThat(st.getRowCount())
                .as("GROUP BY-da COUNT qruplaşdırılmış nəticəni saymalıdır (5 yox, 3)")
                .isEqualTo(3);
        assertThat(dataRowCount(st))
                .as("3 qrup qayıdır")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("DISTINCT + COUNT: unikal sətir sayı (subquery-də DISTINCT qorunur)")
    void distinctCountReturnsUniqueRowCount() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.status")          // 5 sətir, amma 2 fərqli status (A, B)
                .distinct()
                .page(0, 10)
                .execute(dsl);

        assertThat(st.getRowCount())
                .as("DISTINCT + COUNT unikal sətir sayını verməlidir (2)")
                .isEqualTo(2);
        assertThat(dataRowCount(st))
                .as("2 unikal status qayıdır")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("page(): ümumi COUNT dolur, data səhifə ölçüsü qədər")
    void paginateReturnsTotalCountAndLimitedData() {
        SelectTable st = JooqQuery.from(flows(), "t")
                .select("t.id")
                .page(0, 2)
                .execute(dsl);

        assertThat(st.getRowCount())
                .as("paginate: ümumi say = 5")
                .isEqualTo(5);
        assertThat(dataRowCount(st))
                .as("paginate: səhifə ölçüsü = 2")
                .isEqualTo(2);
    }
}
