package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.builder.ComputedField;
import az.mbm.jooqsqlgenerate.enums.Agg;
import az.mbm.jooqsqlgenerate.enums.MathOp;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import jakarta.persistence.Column;
import jakarta.persistence.Table;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMOKE TESTLƏR — SQL render müqayisəsi.
 *
 * <p>DB tələb olunmur: jOOQ {@code MockConnection} istifadə olunur, sorğular
 * yalnız qurulur və render edilmiş SQL mətni yoxlanılır. Məqsəd refactoring
 * zamanı davranışın dəyişmədiyini tez aşkarlamaqdır.
 */
@SuppressWarnings("deprecation") // JooqQuery.from(Class, alias) entity mode qəsdən test olunur
class SqlRenderSmokeTest {

    // ─── Test entity-ləri ────────────────────────────────────────────────

    @Table(name = "warehouse_flow", schema = "test")
    static class FlowEntity {
        @Column(name = "id")                           private Long       id;
        @Column(name = "status")                       private String     status;
        @Column(name = "operation_year")               private Integer    operationYear;
        @Column(name = "fk_product_classification_id") private Long       fkProductClassificationId;
        @Column(name = "marginal_cost_out")            private BigDecimal marginalCostOut;
        @Column(name = "marginal_cost_in")             private BigDecimal marginalCostIn;
        @Column(name = "total_purchase_expense")       private BigDecimal totalPurchaseExpense;
        @Column(name = "action_out")                   private BigDecimal actionOut;
        @Column(name = "action_in")                    private BigDecimal actionIn;
    }

    @Table(name = "product_classification", schema = "test")
    static class ClassificationEntity {
        @Column(name = "id")             private Long   id;
        @Column(name = "classification") private String classification;
    }

    // ─── Mock DSLContext — DB yoxdur, yalnız render ──────────────────────

    private static DSLContext mockDsl() {
        MockDataProvider provider = ctx -> new MockResult[]{
                new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())
        };
        return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    }

    private static String render(SelectTableSupplier s) {
        return s.get().getSelectTable().getSQL(ParamType.INLINED).toLowerCase();
    }

    @FunctionalInterface
    private interface SelectTableSupplier {
        az.mbm.jooqsqlgenerate.core.SelectTable get();
    }

    // ─── Testlər ─────────────────────────────────────────────────────────

    /** Op.EQUAL (yeni) və Op.EQUAl (köhnə typo) EYNİ SQL verməlidir. */
    @Test
    void equalAliasProducesSameSqlAsTypo() {
        String withTypo = render(() -> JooqQuery.from(FlowEntity.class, "t")
                .select("t.id", "t.status")
                .filter("status", Op.EQUAl, "A")
                .execute(mockDsl()));

        String withFixed = render(() -> JooqQuery.from(FlowEntity.class, "t")
                .select("t.id", "t.status")
                .filter("status", Op.EQUAL, "A")
                .execute(mockDsl()));

        assertEquals(withTypo, withFixed);
        assertTrue(withFixed.contains("status"), "WHERE status şərti itib: " + withFixed);
        assertTrue(withFixed.contains("'a'") || withFixed.contains("= a"),
                "Filter dəyəri SQL-də yoxdur: " + withFixed);
    }

    /** Op.EQUAL + Collection dəyər → avtomatik IN çevrilməsi (EQUAl ilə eyni). */
    @Test
    void equalWithCollectionBecomesIn() {
        String sql = render(() -> JooqQuery.from(FlowEntity.class, "t")
                .select("t.id")
                .filter("status", Op.EQUAL, List.of("A", "B"))
                .execute(mockDsl()));

        assertTrue(sql.contains(" in "), "Collection EQUAL → IN çevrilməyib: " + sql);
    }

    /** LIKE — türk İ/I normallaşdırması string sahədə tətbiq olunmalıdır. */
    @Test
    void likeAppliesTurkishNormalization() {
        String sql = render(() -> JooqQuery.from(FlowEntity.class, "t")
                .select("t.id")
                .filter("status", Op.LIKE, "İSTANBUL")
                .execute(mockDsl()));

        assertTrue(sql.contains("replace"), "REPLACE(İ→i) yoxdur: " + sql);
        assertTrue(sql.contains("lower"),   "LOWER(...) yoxdur: " + sql);
        assertTrue(sql.contains("%istanbul%"), "Normallaşdırılmış dəyər yoxdur: " + sql);
    }

    /** ROUND strategiyaları: WHERE ROUND(field, 2) > 9.99. */
    @Test
    void roundStrategyRendersRoundComparison() {
        @SuppressWarnings("unchecked")
        Field<Object> price = (Field<Object>) (Field<?>)
                DSL.field(DSL.name("t", "price"), BigDecimal.class);

        Condition c = FilterStrategies.get(Op.GREATER_THAN_ROUND_2).apply(price, "9.99");
        String sql = DSL.using(SQLDialect.POSTGRES).renderInlined(c).toLowerCase();

        assertTrue(sql.contains("round"), "ROUND(...) yoxdur: " + sql);
        assertTrue(sql.contains("9.99"),  "Müqayisə dəyəri yoxdur: " + sql);
        assertTrue(sql.contains(">"),     "> operatoru yoxdur: " + sql);
    }

    /** MathOp.apply() — düzgün operator render olunur, NONOPERATION left-i qaytarır. */
    @Test
    void mathOpApplyRendersOperators() {
        Field<BigDecimal> a = DSL.field("a", BigDecimal.class);
        Field<BigDecimal> b = DSL.field("b", BigDecimal.class);
        DSLContext render = DSL.using(SQLDialect.POSTGRES);

        assertTrue(render.render(MathOp.ADD.apply(a, b)).contains("+"));
        assertTrue(render.render(MathOp.SUBTRACT.apply(a, b)).contains("-"));
        assertTrue(render.render(MathOp.MULTIPLY.apply(a, b)).contains("*"));
        assertTrue(render.render(MathOp.DIVIDE.apply(a, b)).contains("/"));
        assertSame(a, MathOp.NONOPERATION.apply(a, b),
                "NONOPERATION sol operandı olduğu kimi qaytarmalıdır");
    }

    /** İstifadəçi ssenarisi: JOIN + GROUP BY + selectAs + SUM(ifadə) — addSumExpr ilə. */
    @Test
    void managerJoinGroupSumExprSmoke() {
        String sql = new JooqManager(mockDsl())
                .setMainTable(FlowEntity.class, "t")
                .addInnerJoin(ClassificationEntity.class, "t1", "fkProductClassificationId", "id")
                .isNotNull("t.status")
                .equal("t.status", "A")
                .in("t.status", List.of("satisicra", "satisiade"))
                .between("t.operationYear", 2024, 2025)
                .addGroupBy("t.fkProductClassificationId", "t1.classification")
                .addSelectAs("t.fkProductClassificationId", "id")
                .addSelectAs("t1.classification", "name")
                .addSumExpr("totalPrice", e -> e
                        .plus("t.marginalCostOut")
                        .plus("t.totalPurchaseExpense", "t.actionOut")
                        .minus("t.marginalCostIn")
                        .minus("t.totalPurchaseExpense", "t.actionIn"))
                .noPagination()
                .skipCount()
                .execute()
                .getSelectTable()
                .getSQL(ParamType.INLINED)
                .toLowerCase();

        assertTrue(sql.contains("join"),     "JOIN yoxdur: " + sql);
        assertTrue(sql.contains("group by"), "GROUP BY yoxdur: " + sql);
        assertTrue(sql.contains("sum("),     "SUM( yoxdur: " + sql);
        assertTrue(sql.contains("*"),        "Hasil (f1*f2) yoxdur: " + sql);
        assertTrue(sql.contains("between"),  "BETWEEN yoxdur: " + sql);
        assertTrue(sql.contains(" in "),     "IN yoxdur: " + sql);
    }

    /** addSumExpr köhnə addAggFunctionOnComputed(flat zəncir) ilə EYNİ SQL verməlidir. */
    @Test
    void sumExprMatchesEquivalentComputedFieldChain() {
        ComputedField flat = ComputedField.expr("t.marginalCostOut")
                .add(ComputedField.expr("t.totalPurchaseExpense").multiply("t.actionOut"))
                .subtract("t.marginalCostIn")
                .subtract(ComputedField.expr("t.totalPurchaseExpense").multiply("t.actionIn"));

        String oldApi = new JooqManager(mockDsl())
                .setMainTable(FlowEntity.class, "t")
                .addGroupBy("t.fkProductClassificationId")
                .addSelectAs("t.fkProductClassificationId", "id")
                .addAggFunctionOnComputed(Agg.SUM, flat, "totalPrice")
                .noPagination().skipCount()
                .execute().getSelectTable().getSQL(ParamType.INLINED);

        String newApi = new JooqManager(mockDsl())
                .setMainTable(FlowEntity.class, "t")
                .addGroupBy("t.fkProductClassificationId")
                .addSelectAs("t.fkProductClassificationId", "id")
                .addSumExpr("totalPrice", e -> e
                        .plus("t.marginalCostOut")
                        .plus("t.totalPurchaseExpense", "t.actionOut")
                        .minus("t.marginalCostIn")
                        .minus("t.totalPurchaseExpense", "t.actionIn"))
                .noPagination().skipCount()
                .execute().getSelectTable().getSQL(ParamType.INLINED);

        assertEquals(oldApi, newApi,
                "addSumExpr köhnə flat ComputedField zənciri ilə eyni SQL verməlidir");
    }

    // ─── Generated / derived-table mode (executeGenerated yolu) ──────────
    // Qeyd: DSL.table(name) sahə metadatası daşımır — sahə həlli üçün
    // sahələri məlum olan derived table (SELECT ... FROM ...) istifadə olunur.

    private static az.mbm.jooqsqlgenerate.core.SelectTable derivedFlows() {
        return new az.mbm.jooqsqlgenerate.core.SelectTable(
                DSL.using(SQLDialect.POSTGRES)
                        .select(DSL.field(DSL.name("id"), Long.class),
                                DSL.field(DSL.name("status"), String.class),
                                DSL.field(DSL.name("marginal_cost_out"), BigDecimal.class),
                                DSL.field(DSL.name("marginal_cost_in"), BigDecimal.class))
                        .from(DSL.table(DSL.name("test", "warehouse_flow"))),
                0);
    }

    /** AggExpr — MathOp-lu termin: bölmə NULLIF qorunması ilə render olunmalıdır. */
    @Test
    void sumExprDivideTermRendersNullifGuard() {
        String sql = new JooqManager(mockDsl())
                .setMainTable(FlowEntity.class, "t")
                .addGroupBy("t.status")
                .addSelectAs("t.status", "status")
                .addSumExpr("ratio", e -> e
                        .plus("t.marginalCostOut", MathOp.DIVIDE, "t.actionOut")
                        .minus("t.marginalCostIn"))
                .noPagination().skipCount()
                .execute().getSelectTable().getSQL(ParamType.INLINED).toLowerCase();

        assertTrue(sql.contains("/"),      "Bölmə operatoru yoxdur: " + sql);
        assertTrue(sql.contains("nullif"), "NULLIF sıfıra bölmə qorunması yoxdur: " + sql);
        assertTrue(sql.contains("sum("),   "SUM( yoxdur: " + sql);
    }

    /** Generated mode (executeGenerated) — filter + orderBy split-dən sonra işləyir. */
    @Test
    void generatedModeFilterAndOrderBySmoke() {
        String sql = JooqQuery.from(derivedFlows(), "u")
                .select("id", "status")
                .filter("u.status", Op.EQUAL, "ACTIVE")
                .orderBy("u.id", "DESC")
                .execute(mockDsl())
                .getSelectTable()
                .getSQL(ParamType.INLINED)
                .toLowerCase();

        assertTrue(sql.contains("where"),    "WHERE yoxdur: " + sql);
        assertTrue(sql.contains("status"),   "status şərti yoxdur: " + sql);
        assertTrue(sql.contains("'active'"), "Filter dəyəri yoxdur: " + sql);
        assertTrue(sql.contains("order by"), "ORDER BY yoxdur: " + sql);
        assertTrue(sql.contains("desc"),     "DESC yoxdur: " + sql);
    }

    /** Generated mode — SUM aqreqatı COALESCE(...,0) ilə bükülməlidir (mövcud davranış). */
    @Test
    void generatedModeAggregateSmoke() {
        String sql = JooqQuery.from(derivedFlows(), "t")
                .groupBy("t.status")
                .select("t.status")
                .sumExpr("total", e -> e
                        .plus("t.marginal_cost_out")
                        .minus("t.marginal_cost_in"))
                .execute(mockDsl())
                .getSelectTable()
                .getSQL(ParamType.INLINED)
                .toLowerCase();

        assertTrue(sql.contains("sum("),     "SUM( yoxdur: " + sql);
        assertTrue(sql.contains("coalesce"), "COALESCE bükümü yoxdur: " + sql);
        assertTrue(sql.contains("group by"), "GROUP BY yoxdur: " + sql);
    }
}
