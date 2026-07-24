package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.core.SelectTable;
import az.mbm.jooqsqlgenerate.enums.MathOp;
import az.mbm.jooqsqlgenerate.enums.NullDefault;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.spec.Specification;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  FLUENT BUILDER — SELECT sorğusu üçün tam implementasiya
 *
 *  Design Patterns:
 *    • Fluent Builder   — zəncir metodlar, mütləq sıra aydındır
 *    • Specification    — WHERE şərtləri composable
 *    • Strategy         — FilterStrategies vasitəsilə
 *    • Composite        — Spec.and() / Spec.or()
 *    • Template Method  — build() → step1..step10
 *    • Factory Method   — QueryFactory.select()
 *
 *  Köhnə ChwJooqManager-in bütün xüsusiyyətlərini əhatə edir:
 *    ✓ SELECT (sütun, computed, CASE WHEN, CONCAT)
 *    ✓ JOIN (entity, subquery) — çoxsəviyyəli
 *    ✓ WHERE (Specification + AND/OR/NOT/EXISTS/field-to-field)
 *    ✓ GROUP BY + aqreqat funksiyalar (SUM, COUNT, AVG, MIN, MAX)
 *    ✓ HAVING
 *    ✓ ORDER BY
 *    ✓ DISTINCT
 *    ✓ Pagination (COUNT + LIMIT/OFFSET)
 *
 *  İstifadə:
 * <pre>{@code
 *   QueryFactory factory = new QueryFactory(dsl);
 *
 *   SelectTable result = factory.select(User.class, "u")
 *       .columns("u.id", "u.name", "u.email")
 *       .leftJoin(Order.class, "o").on("id").equalsField("userId")
 *       .where(Spec.eq("status", "ACTIVE").and(Spec.in("roleId", roles)))
 *       .orderByDesc("u.createdAt")
 *       .page(0, 20)
 *       .build(dsl);
 * }</pre>
 * ═══════════════════════════════════════════════════════════════════════
 *
 * @param <T> ana cədvəl entity tipi
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class SelectQueryBuilder<T> {

    // ─── İmmutable meta ──────────────────────────────────────────────────
    private final Class<T> entityClass;
    private final String   tableAlias;

    // ─── SELECT sahələri ──────────────────────────────────────────────────
    private final List<String>            columns        = new ArrayList<>();
    private final List<SelectAsCol>       selectAsCols   = new ArrayList<>();
    private final List<ComputedCol>       computed       = new ArrayList<>();
    private final List<ComputedField>     computedChain  = new ArrayList<>();
    private final List<CaseBuilder<T>>    caseCols       = new ArrayList<>();
    private final List<ConcatCol>         concatCols     = new ArrayList<>();
    private final List<CoalesceCol>       coalesceCols   = new ArrayList<>();
    private final List<SubSelectBuilder>  subSelectCols  = new ArrayList<>();
    private final List<Field<?>>          rawSelectFields = new ArrayList<>(); // birbaşa jOOQ Field
    private final List<CastCol>           castCols        = new ArrayList<>();

    // ─── JOIN ─────────────────────────────────────────────────────────────
    private final List<JoinConfig>              joins                  = new ArrayList<>();
    private final List<SubQueryJoin>            subQueryJoins          = new ArrayList<>();
    private final List<ConditionedEntityJoin>   conditionedEntityJoins = new ArrayList<>();
    private final List<RawTableJoin>            rawTableJoins          = new ArrayList<>();

    // ─── WHERE ───────────────────────────────────────────────────────────
    private Specification<T> whereSpec = null;

    // ─── IN (SELECT ...) subquery filterlər ──────────────────────────────
    private final List<SubQueryInEntry> inSubQueryEntries = new ArrayList<>();

    private record SubQueryInEntry(List<String> outerFields, SubQueryIn sub) {}

    // ─── Global filter — alias.field ilə join cədvəlinə qədər çatan WHERE filterlər ──
    private final List<FiltersEntry> globalFilterEntries = new ArrayList<>();

    private record FiltersEntry(String aliasAndField,
                                     az.mbm.jooqsqlgenerate.enums.Op op,
                                     Object value) {}

    // ─── HAVING əlavəsi (computed alias filterlər üçün) ───────────────────
    private Condition extraHaving = null;

    // ─── Birbaşa jOOQ raw conditions ──────────────────────────────────────
    private final List<Condition> rawConditions = new ArrayList<>();
    private final List<Condition> rawHavings    = new ArrayList<>();

    // ─── Field-to-field WHERE/HAVING müqayisə filterlər ─────────────────
    // Məs: t.fkTaskId = f.fkTaskId  AND  t.totalPrice > f.totalPrice
    private final List<FieldFilterRow> fieldFilterRows = new ArrayList<>();
    private record FieldFilterRow(String leftAliasAndField, Op op, String rightAliasAndField) {}

    // ─── OR qrupları ─────────────────────────────────────────────────────
    // Struktur: orGroupAlias → (andGroupAlias → List<filterRow>)
    // Nəticə: AND (andGroup1 OR andGroup2 OR ...)
    // andGroup içi: condition1 AND condition2 AND ...
    private final List<OrFilterRow> orFilterRows = new ArrayList<>();
    private record OrFilterRow(String orGroup, String andGroup, String aliasAndField, Op op, Object value) {}

    // ─── GROUP BY + Aqreqat ──────────────────────────────────────────────
    private AggregateBuilder<T> aggregator = null;

    // ─── ORDER BY ────────────────────────────────────────────────────────
    private final List<SortField<?>> orderFields = new ArrayList<>();
    private record PendingOrder(String tableAliasAndField, boolean asc) {}
    private final List<PendingOrder> pendingOrders = new ArrayList<>();

    // ─── Flags ───────────────────────────────────────────────────────────
    private boolean distinct              = false;
    private int     pageNumber            = 0;
    private int     pageSize              = 50;
    private boolean paginate              = false;  // yalnız page() çağrılanda aktiv olur
    private boolean countOnly             = false;  // pagination olmadan yalnız COUNT
    private boolean skipCount             = false;  // pagination var, amma COUNT işləməsin
    private boolean onlyCount             = false;  // yalnız COUNT işləsin, əsas data sorğusu icra edilməsin
    private boolean selectMainEntityFields = false; // əsas entity-nin bütün sütunları explicit SELECT-ə əlavə olunur

    // ════════════════════════════════════════════════════════════════════
    //  Daxili record-lar
    // ════════════════════════════════════════════════════════════════════

    /** SELECT sütununa özelleştirilmiş alias: "t1.fieldName" AS "outputAlias" */
    private record SelectAsCol(String aliasAndField, String outputAlias) {}

    /** Riyazi əməliyyatla hesablanmış SELECT sütunu */
    private record ComputedCol(
            String alias,
            String tableAlias1, String field1,
            MathOp op,
            String tableAlias2, String field2,
            NullDefault nullDefault) {}

    /** CONCAT SELECT sütunu */
    private record ConcatCol(String alias, String separator, List<ConcatItem> items) {}

    /** COALESCE SELECT sütunu — ilk null olmayan sahəni qaytarır */
    private record CoalesceCol(String alias, List<String> fields, Object defaultValue) {}

    /**
     * CAST SELECT sütunu.
     * {@code datePattern != null} olduqda {@code TO_CHAR(field, pattern)} istifadə edilir,
     * əks halda {@code CAST(field AS targetType)}.
     */
    private record CastCol(String tableAliasAndField, DataType<?> targetType,
                           String alias, String datePattern) {
        /** Tipə cast üçün qısa konstruktor */
        CastCol(String tableAliasAndField, DataType<?> targetType, String alias) {
            this(tableAliasAndField, targetType, alias, null);
        }
    }

    /** Entity-to-entity JOIN konfiqurasiyası */
    private record JoinConfig(
            Class<?>         fromClass,
            String           fromAlias,
            Class<?>         toClass,
            String           toAlias,
            JoinType         joinType,
            String           fromField,
            String           toField,
            Op condOp) {}

    /** Subquery JOIN konfiqurasiyası */
    private record SubQueryJoin(
            Select<?> subQuery,
            String    subAlias,
            String    subField,
            String    mainField) {}

    /**
     * Çoxlu field cütü + əlavə value şərtləri olan entity JOIN.
     * Tam Condition kənarda qurulub buraya verilir.
     */
    private record ConditionedEntityJoin(
            Class<?> entity,
            String   alias,
            JoinType joinType,
            Condition on) {}

    /**
     * Raw jOOQ {@link Table} (məs. {@link SelectTable#asTable(String)} ilə alınmış
     * derived table) + tam Condition ilə JOIN. JPA entity-yə bağlı deyil —
     * {@link EntityTable}-in raw-mode konstruktoru ilə {@code tableMap}-ə qeyd olunur,
     * beləliklə bu alias-a aid sahələr ({@code "d2.vatTotalPrice"} kimi) entity mode-da
     * digər bütün resolution nöqtələrində (aqreqat, GROUP BY, CASE, CONCAT, COALESCE və s.)
     * avtomatik tanınır.
     */
    private record RawTableJoin(
            Table<?>  rawTable,
            String    alias,
            JoinType  joinType,
            Condition on) {}

    // ════════════════════════════════════════════════════════════════════
    //  Static factory (giriş nöqtəsi)
    // ════════════════════════════════════════════════════════════════════

    private SelectQueryBuilder(Class<T> entityClass, String tableAlias) {
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class null ola bilməz");
        this.tableAlias  = Objects.requireNonNull(tableAlias,  "Table alias null ola bilməz");
    }

    /**
     * Builder-i yaradır.
     *
     * @param entity     JPA entity class-ı
     * @param alias      SQL alias-ı (məs. "u")
     * @param <T>        entity tipi
     */
    public static <T> SelectQueryBuilder<T> from(Class<T> entity, String alias) {
        return new SelectQueryBuilder<>(entity, alias);
    }

    // ════════════════════════════════════════════════════════════════════
    //  1 — SELECT sütunlar
    // ════════════════════════════════════════════════════════════════════

    /**
     * Seçiləcək sütunlar. Format: "alias.fieldName" və ya "fieldName".
     *
     * <pre>{@code .columns("u.id", "u.name", "o.total") }</pre>
     */
    public SelectQueryBuilder<T> columns(String... fields) {
        columns.addAll(Arrays.asList(fields));
        return this;
    }

    /**
     * Əsas entity-nin bütün sütunlarını SELECT-ə explicit əlavə edir.
     *
     * <p>Fərqi {@code SELECT *}-dən: yalnız əsas entity-nin sütunları seçilir
     * (JOIN cədvəllərinin sütunları daxil olmur), hər sütun camelCase Java adı
     * ilə alias-lanır.
     *
     * <pre>{@code
     *   factory.select(User.class, "u")
     *       .selectAll()                  // u.id, u.name, u.email ...
     *       .leftJoin(Order.class, "o")...// o sütunları SEÇİLMİR
     *       .build(dsl);
     * }</pre>
     *
     * <p>Əlavə sütunlar lazım olduqda {@code .columns()} ilə birlikdə istifadə et:
     * <pre>{@code
     *   .selectAll()
     *   .columns("o.totalAmount", "o.status")
     * }</pre>
     */
    public SelectQueryBuilder<T> selectAll() {
        this.selectMainEntityFields = true;
        return this;
    }

    /**
     * SELECT sütununa özəlləşdirilmiş çıxış alias verir.
     *
     * <p>Format: {@code "tableAlias.fieldName"} → SQL-də {@code tableAlias.column_name AS outputAlias}
     *
     * <pre>{@code
     *   .selectAs("t1.fkProductId", "productId")
     *   .selectAs("t.operationDate", "date")
     *   .selectAs("o.totalAmount",   "amount")
     * }</pre>
     *
     * @param aliasAndField sütun: {@code "tableAlias.javaFieldName"} formatında
     * @param outputAlias   SQL alias-ı (nəticədə bu ad görünür)
     */
    public SelectQueryBuilder<T> selectAs(String aliasAndField, String outputAlias) {
        if (aliasAndField != null && !aliasAndField.isBlank()
                && outputAlias != null && !outputAlias.isBlank())
            selectAsCols.add(new SelectAsCol(aliasAndField, outputAlias));
        return this;
    }

    /**
     * Riyazi əməliyyatla hesablanmış sütun — 2 sahə üçün sadə form.
     *
     * <pre>{@code .computedColumn("net", "o", MathOp.SUBTRACT, "amount", "o", "discount") }</pre>
     */
    public SelectQueryBuilder<T> computedColumn(
            String alias,
            String alias1, MathOp op, String field1,
            String alias2, String field2) {
        computed.add(new ComputedCol(alias, alias1, field1, op, alias2, field2, NullDefault.NONE));
        return this;
    }

    /**
     * Riyazi əməliyyatla hesablanmış sütun — 2 sahə + NULL default strategiyası.
     *
     * <pre>{@code
     *   .computedColumn("lineTotal", "o", MathOp.MULTIPLY, "price", "o", "qty", NullDefault.ZERO)
     *   // → COALESCE(price,0) * COALESCE(qty,0) AS lineTotal
     * }</pre>
     */
    public SelectQueryBuilder<T> computedColumn(
            String alias,
            String alias1, MathOp op, String field1,
            String alias2, String field2,
            NullDefault nullDefault) {
        computed.add(new ComputedCol(alias, alias1, field1, op, alias2, field2,
                nullDefault != null ? nullDefault : NullDefault.NONE));
        return this;
    }

    /**
     * Riyazi əməliyyatla hesablanmış sütun — istənilən sayda sahə üçün.
     *
     * <pre>{@code
     *   .computedColumn(
     *       ComputedField.of("o.price")
     *           .add("o.tax")
     *           .add("o.shipping")
     *           .subtract("o.discount")
     *           .as("totalCost")
     *   )
     * }</pre>
     */
    public SelectQueryBuilder<T> computedColumn(ComputedField cf) {
        if (cf != null) computedChain.add(cf);
        return this;
    }

    /**
     * Riyazi əməliyyatla hesablanmış sütun + həmin sütuna HAVING filtri.
     *
     * <p>Sorğu icra ediləndə nəticə sətirləri yalnız filtri keçənlər qaytarılır.
     * Əsas cədvəl WHERE-i ilə qarışmır — computed alias üzərindən HAVING işləyir.
     *
     * <pre>{@code
     *   // grandTotal > 1000
     *   .computedColumn(
     *       ComputedField.sumOf(
     *           ComputedField.expr("o.price").multiply("o.qty"),
     *           ComputedField.expr("o.tax")
     *       ).as("grandTotal"),
     *       Op.GREATER_THAN, 1000
     *   )
     *
     *   // netAmount BETWEEN 500 AND 2000
     *   .computedColumn(
     *       ComputedField.of("o.amount").subtract("o.discount").as("netAmount"),
     *       Op.BETWEEN, new Object[]{500, 2000}
     *   )
     *
     *   // marginPct >= 15  (çox hissəli ifadə + filter)
     *   .computedColumn(
     *       ComputedField.sumOf(
     *           ComputedField.expr("o.price").multiply("o.qty"),
     *           ComputedField.expr("o.bonus")
     *       ).as("totalRevenue"),
     *       Op.GREATER_THAN_OR_EQUAL_TO, 5000
     *   )
     * }</pre>
     *
     * @param cf    computed sütun ({@code .as(alias)} mütləq olmalıdır)
     * @param op    filter əməliyyatı
     * @param value filter dəyəri
     */
    @SuppressWarnings("unchecked")
    public SelectQueryBuilder<T> computedColumn(ComputedField cf,
                                                Op op,
                                                Object value) {
        if (cf == null) return this;
        computedChain.add(cf);
        if (op != null && value != null) {
            Field<Object> aliasField = (Field<Object>) DSL.field(DSL.name(cf.getAlias()));
            Condition c = FilterStrategies.get(op).apply(aliasField, value);
            rawHavings.add(c);
        }
        return this;
    }

    /**
     * Riyazi hesablanmış sütun — {@link MathExpr} DSL ilə başlanır,
     * {@code .as("alias").done()} ilə tamamlanır.
     *
     * <p>Bu metod {@link ComputedField}-i tamamilə gizlədir — istifadəçi yalnız
     * {@link MathExpr} ilə işləyir.
     *
     * <pre>{@code
     *   // (total_Price_In - total_Price_Out) * rate - purchase_Expense * count
     *   factory.select(WarehouseFlow.class, "wf")
     *       .compute("wf.total_Price_In")
     *           .subtract("wf.total_Price_Out")
     *           .multiply("wf.rate")
     *           .subtract(MathExpr.group("wf.purchase_Expense").multiply("wf.count"))
     *           .as("profit")
     *       .build(dsl);
     *
     *   // Sadə: price - discount
     *   .compute("o.price")
     *       .subtract("o.discount")
     *       .as("netPrice")
     *
     *   // Mötərizəli qrup: price + (tax * qty)
     *   .compute("o.price")
     *       .add().of("o.tax").multiply("o.qty").done()
     *       .as("total")
     * }</pre>
     *
     * @param tableAliasAndField  ilk sahə — {@code "alias.fieldName"} formatında
     * @return {@link MathStep} — riyazi zəncir, {@code .as().done()} ilə tamamlanır
     */
    public MathStep<T> compute(String tableAliasAndField) {
        return new MathStep<>(this, MathExpr.of(tableAliasAndField));
    }

    /**
     * Riyazi hesablanmış sütun zənciri — {@link #compute(String)} tərəfindən başladılır.
     *
     * <p>Zəncir {@code .as("alias").done()} ilə tamamlanır:
     * {@code .done()} hesablanmış sütunu builder-ə əlavə edib {@link SelectQueryBuilder}-ə qayıdır.
     *
     * @param <E> entity tipi
     */
    public static class MathStep<E> {

        private final SelectQueryBuilder<E> parent;
        private final MathExpr              expr;
        private       String                alias;

        MathStep(SelectQueryBuilder<E> parent, MathExpr expr) {
            this.parent = parent;
            this.expr   = expr;
        }

        // ─── Riyazi əməliyyatlar — sadə sahə ────────────────────────────

        /** {@code + field} */
        public MathStep<E> add(String field)      { expr.add(field);      return this; }

        /** {@code - field} */
        public MathStep<E> subtract(String field) { expr.subtract(field); return this; }

        /** {@code * field} */
        public MathStep<E> multiply(String field) { expr.multiply(field); return this; }

        /** {@code / field} */
        public MathStep<E> divide(String field)   { expr.divide(field);   return this; }

        // ─── NullDefault — LEFT JOIN null sahələri üçün ─────────────────

        /**
         * LEFT JOIN-dən gələn null sahələr üçün bütün zəncirə COALESCE strategiyası tətbiq edir.
         *
         * <p>Default davranış {@link NullDefault#NONE}-dir — hesablamadakı hər hansı
         * sahə null gəlsə (məs. LEFT JOIN-də uyğun sətir tapılmadıqda) bütün ifadə
         * null olur (SQL-in standart davranışı). Bunu önləmək üçün:
         *
         * <pre>{@code
         *   .compute("o.price")
         *       .multiply("o.qty")
         *       .withNullDefault(NullDefault.ZERO)   // qty null gəlsə 0 kimi hesablanır
         *       .as("lineTotal")
         * }</pre>
         */
        public MathStep<E> withNullDefault(NullDefault nd) { expr.withNullDefault(nd); return this; }

        /** {@code + COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
        public MathStep<E> addNullAs(String field, Number nullAs)      { expr.addNullAs(field, nullAs);      return this; }

        /** {@code - COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
        public MathStep<E> subtractNullAs(String field, Number nullAs) { expr.subtractNullAs(field, nullAs); return this; }

        /** {@code * COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
        public MathStep<E> multiplyNullAs(String field, Number nullAs) { expr.multiplyNullAs(field, nullAs); return this; }

        /** {@code / COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
        public MathStep<E> divideNullAs(String field, Number nullAs)   { expr.divideNullAs(field, nullAs);   return this; }

        // ─── Mötərizəli qrup açmaq — boş çağırış ────────────────────────

        /**
         * {@code + ( ... )} — yeni mötərizəli qrup başladır.
         *
         * <pre>{@code
         *   .add().of("o.tax").multiply("o.qty").done()
         *   // → + (tax * qty)
         * }</pre>
         */
        public GroupStep<E> add()      { return new GroupStep<>(this, MathOp.ADD);      }

        /**
         * {@code - ( ... )} — yeni mötərizəli qrup başladır.
         *
         * <pre>{@code
         *   .subtract().of("wf.purchase_Expense").multiply("wf.count").done()
         *   // → - (purchase_Expense * count)
         * }</pre>
         */
        public GroupStep<E> subtract() { return new GroupStep<>(this, MathOp.SUBTRACT); }

        /**
         * {@code * ( ... )} — yeni mötərizəli qrup başladır.
         *
         * <pre>{@code
         *   .multiply().of("o.price").add("o.tax").done()
         *   // → * (price + tax)
         * }</pre>
         */
        public GroupStep<E> multiply() { return new GroupStep<>(this, MathOp.MULTIPLY); }

        /**
         * {@code / ( ... )} — yeni mötərizəli qrup başladır.
         *
         * <pre>{@code
         *   .divide().of("o.total").subtract("o.discount").done()
         *   // → / (total - discount)
         * }</pre>
         */
        public GroupStep<E> divide()   { return new GroupStep<>(this, MathOp.DIVIDE);   }

        // ─── Çıxış nöqtəsi ──────────────────────────────────────────────

        /**
         * SELECT alias-ını təyin edib hesablanmış sütunu builder-ə əlavə edir
         * və {@link SelectQueryBuilder}-ə qayıdır.
         *
         * <pre>{@code
         *   .compute("wf.total_Price_In")
         *       .subtract("wf.total_Price_Out")
         *       .multiply("wf.rate")
         *       .subtract().of("wf.purchase_Expense").multiply("wf.count").done()
         *       .as("profit")        // ← commit + qayıdış, ayrıca .done() lazım deyil
         *   .build(dsl);
         * }</pre>
         */
        public SelectQueryBuilder<E> as(String alias) {
            parent.computedColumn(expr.buildWithAlias(alias));
            return parent;
        }
    }

    /**
     * Mötərizəli alt-ifadə builder-i — {@link MathStep#add()}, {@link MathStep#subtract()},
     * {@link MathStep#multiply()}, {@link MathStep#divide()} tərəfindən açılır.
     *
     * <p>Zəncir {@code .of("alias.field")} ilə başlayır, {@code .done()} ilə
     * bağlanır və {@link MathStep}-ə qayıdır.
     *
     * <pre>{@code
     *   // (total_Price_In - total_Price_Out) * rate - (purchase_Expense * count)
     *   factory.select(WarehouseFlow.class, "wf")
     *       .compute("wf.total_Price_In")
     *           .subtract("wf.total_Price_Out")
     *           .multiply("wf.rate")
     *           .subtract().of("wf.purchase_Expense").multiply("wf.count").done()
     *           .as("profit")
     *       .build(dsl);
     *
     *   // price + (tax * qty) - (discount * qty)
     *   .compute("o.price")
     *       .add().of("o.tax").multiply("o.qty").done()
     *       .subtract().of("o.discount").multiply("o.qty").done()
     *       .as("lineTotal")
     * }</pre>
     *
     * @param <E> entity tipi
     */
    public static class GroupStep<E> {

        private final MathStep<E> parent;
        private final MathOp      op;       // valideynə tətbiq olunacaq əməliyyat
        private       MathExpr    group;    // .of() ilə qurulur

        GroupStep(MathStep<E> parent, MathOp op) {
            this.parent = parent;
            this.op     = op;
        }

        // ─── Qrupun ilk sahəsi ────────────────────────────────────────────

        /**
         * Mötərizənin içindəki ilk sahəni təyin edir.
         *
         * <pre>{@code .subtract().of("wf.purchase_Expense") }</pre>
         */
        public GroupStep<E> of(String tableAliasAndField) {
            this.group = MathExpr.of(tableAliasAndField);
            return this;
        }

        // ─── Qrup daxilindəki əməliyyatlar ───────────────────────────────

        /** {@code + field} — qrup daxilində */
        public GroupStep<E> add(String field)      { group.add(field);      return this; }

        /** {@code - field} — qrup daxilində */
        public GroupStep<E> subtract(String field) { group.subtract(field); return this; }

        /** {@code * field} — qrup daxilində */
        public GroupStep<E> multiply(String field) { group.multiply(field); return this; }

        /** {@code / field} — qrup daxilində */
        public GroupStep<E> divide(String field)   { group.divide(field);   return this; }

        // ─── NullDefault — qrup daxilində LEFT JOIN null sahələri üçün ───

        /** Qrupun bütün zəncirinə COALESCE strategiyası tətbiq edir — bax {@link MathExpr#withNullDefault(NullDefault)}. */
        public GroupStep<E> withNullDefault(NullDefault nd) { group.withNullDefault(nd); return this; }

        /** {@code + COALESCE(field, nullAs)} — qrup daxilində, yalnız bu addım üçün. */
        public GroupStep<E> addNullAs(String field, Number nullAs)      { group.addNullAs(field, nullAs);      return this; }

        /** {@code - COALESCE(field, nullAs)} — qrup daxilində, yalnız bu addım üçün. */
        public GroupStep<E> subtractNullAs(String field, Number nullAs) { group.subtractNullAs(field, nullAs); return this; }

        /** {@code * COALESCE(field, nullAs)} — qrup daxilində, yalnız bu addım üçün. */
        public GroupStep<E> multiplyNullAs(String field, Number nullAs) { group.multiplyNullAs(field, nullAs); return this; }

        /** {@code / COALESCE(field, nullAs)} — qrup daxilində, yalnız bu addım üçün. */
        public GroupStep<E> divideNullAs(String field, Number nullAs)   { group.divideNullAs(field, nullAs);   return this; }

        // ─── Mötərizəni bağla, valideynə qayıt ───────────────────────────

        /**
         * Mötərizəni bağlayır — qrupu əsas ifadəyə tətbiq edib {@link MathStep}-ə qayıdır.
         *
         * <pre>{@code .subtract().of("wf.purchase_Expense").multiply("wf.count").done() }</pre>
         *
         * @throws IllegalStateException {@code .of(field)} çağrılmayıbsa
         */
        public MathStep<E> done() {
            if (group == null)
                throw new IllegalStateException("GroupStep: .of(field) tələb olunur");
            switch (op) {
                case ADD      -> parent.expr.add(group);
                case SUBTRACT -> parent.expr.subtract(group);
                case MULTIPLY -> parent.expr.multiply(group);
                case DIVIDE   -> parent.expr.divide(group);
            }
            return parent;
        }
    }

    /**
     * CASE WHEN SELECT sütunu.
     *
     * <pre>{@code
     *   .caseColumn(
     *       CaseBuilder.when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
     *                  .otherwise("Naməlum").as("statusLabel")
     *   )
     * }</pre>
     */
    public SelectQueryBuilder<T> caseColumn(CaseBuilder<T> caseBuilder) {
        caseCols.add(caseBuilder);
        return this;
    }

    /**
     * CONCAT SELECT sütunu — sütun adları ilə (ən sadə hal, heç bir əlavə import lazım deyil).
     *
     * <pre>{@code .concat("fullName", " ", "u.firstName", "u.lastName") }</pre>
     *
     * <p>Literal/CASE/COALESCE qarışdırmaq lazım olduqda {@link #concat(String, String, ConcatItem...)}
     * istifadə edin.
     */
    public SelectQueryBuilder<T> concat(String alias, String separator, String... fields) {
        List<ConcatItem> items = Arrays.stream(fields)
                .map(ConcatItem::field)
                .collect(java.util.stream.Collectors.toList());
        concatCols.add(new ConcatCol(alias, separator, items));
        return this;
    }

    /**
     * CONCAT SELECT sütunu — {@link ConcatItem} qarışıq (field + literal).
     *
     * <pre>{@code
     * import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;
     *
     * .concat("userCode", "-", literal("USR"), field("u.userId"))
     * // SQL: 'USR' || '-' || COALESCE(userId,'')
     * }</pre>
     */
    public SelectQueryBuilder<T> concat(String alias, String separator, ConcatItem... items) {
        concatCols.add(new ConcatCol(alias, separator, Arrays.asList(items)));
        return this;
    }

    /**
     * CONCAT SELECT sütunu — {@link ConcatItem} List variantı.
     */
    public SelectQueryBuilder<T> concat(String alias, String separator, List<ConcatItem> items) {
        concatCols.add(new ConcatCol(alias, separator, new ArrayList<>(items)));
        return this;
    }

    /**
     * CONCAT SELECT sütunu — {@link ConcatItem} kolleksiyası ilə (dinamik siyahı).
     */
    public SelectQueryBuilder<T> concat(String alias, String separator, Collection<ConcatItem> items) {
        if (items != null && !items.isEmpty())
            concatCols.add(new ConcatCol(alias, separator, new ArrayList<>(items)));
        return this;
    }

    /**
     * COALESCE SELECT sütunu — ilk null olmayan sahəni qaytarır.
     *
     * <pre>{@code
     *   // COALESCE(u.nickname, u.firstName, 'Naməlum') AS displayName
     *   .coalesce("displayName", "Naməlum", "u.nickname", "u.firstName")
     * }</pre>
     *
     * @param alias        SELECT alias
     * @param defaultValue hamısı null olduqda qaytarılacaq sabit dəyər
     * @param fields       "alias.field" formatında sahələr (sıra ilə yoxlanır)
     */
    public SelectQueryBuilder<T> coalesce(String alias, Object defaultValue, String... fields) {
        if (alias != null && fields.length > 0)
            coalesceCols.add(new CoalesceCol(alias, Arrays.asList(fields), defaultValue));
        return this;
    }

    /** COALESCE SELECT sütunu — List&lt;String&gt; variantı. Bax: {@link #coalesce(String, Object, String...)}. */
    public SelectQueryBuilder<T> coalesce(String alias, Object defaultValue, List<String> fields) {
        if (fields != null && !fields.isEmpty())
            coalesce(alias, defaultValue, fields.toArray(new String[0]));
        return this;
    }

    /**
     * COALESCE SELECT sütunu — {@link ConcatItem} kolleksiyası ilə (dinamik siyahı).
     * Yalnız {@link ConcatItem#field(String)} elementləri dəstəklənir —
     * sabit dəyər üçün {@code defaultValue} istifadə edin.
     */
    public SelectQueryBuilder<T> coalesce(String alias, Object defaultValue, Collection<ConcatItem> items) {
        if (items != null && !items.isEmpty())
            coalesce(alias, defaultValue, items.stream().map(it -> {
                if (it instanceof ConcatItem.ColField cf) return cf.aliasAndField();
                throw new IllegalStateException(
                        "coalesce yalnız ConcatItem.field(...) qəbul edir — sabit dəyər üçün defaultValue istifadə edin");
            }).toArray(String[]::new));
        return this;
    }

    /**
     * SELECT listdə scalar subquery sütunu.
     *
     * <pre>{@code
     *   .subSelect(
     *       SubSelectBuilder.from(Product.class, "p")
     *           .selectCoalesce("Naməlum", "p.name", "p.code")
     *           .correlateOn("p.id", "o.productId")
     *           .as("productName")
     *   )
     * }</pre>
     */
    public SelectQueryBuilder<T> subSelect(SubSelectBuilder sub) {
        if (sub != null) subSelectCols.add(sub);
        return this;
    }

    /**
     * SELECT listinə birbaşa jOOQ {@link Field} əlavə edir.
     *
     * <pre>{@code
     *   .rawSelectField(DSL.field(DSL.select(...).from(...).where(...)).as("alias"))
     *   .rawSelectField(DSL.coalesce(...).as("displayName"))
     * }</pre>
     */
    /**
     * Sütunu hədəf SQL tipinə cast edərək SELECT-ə əlavə edir.
     *
     * <pre>{@code
     *   // INTEGER → VARCHAR
     *   .castColumn("u.age", SQLDataType.VARCHAR, "ageText")
     *
     *   // VARCHAR → INTEGER
     *   .castColumn("u.code", SQLDataType.INTEGER, "codeNum")
     *
     *   // NUMERIC precision ilə
     *   .castColumn("o.price", SQLDataType.NUMERIC.precision(10, 2), "priceFormatted")
     * }</pre>
     *
     * @param tableAliasAndField  "alias.field" formatında sahə (məs. "u.age")
     * @param targetType          hədəf jOOQ DataType (məs. {@code SQLDataType.VARCHAR})
     * @param alias               SELECT alias-ı
     */
    public SelectQueryBuilder<T> castColumn(String tableAliasAndField,
                                            DataType<?> targetType,
                                            String alias) {
        if (tableAliasAndField != null && targetType != null && alias != null)
            castCols.add(new CastCol(tableAliasAndField, targetType, alias));
        return this;
    }

    /** Sahəni {@code VARCHAR} tipinə cast edir. */
    public SelectQueryBuilder<T> castString(String tableAliasAndField, String alias) {
        return castColumn(tableAliasAndField, org.jooq.impl.SQLDataType.VARCHAR, alias);
    }

    /** Sahəni {@code BIGINT} tipinə cast edir. */
    public SelectQueryBuilder<T> castLong(String tableAliasAndField, String alias) {
        return castColumn(tableAliasAndField, org.jooq.impl.SQLDataType.BIGINT, alias);
    }

    /** Sahəni {@code INTEGER} tipinə cast edir. */
    public SelectQueryBuilder<T> castInteger(String tableAliasAndField, String alias) {
        return castColumn(tableAliasAndField, org.jooq.impl.SQLDataType.INTEGER, alias);
    }

    /** Sahəni {@code NUMERIC} tipinə cast edir. */
    public SelectQueryBuilder<T> castBigDecimal(String tableAliasAndField, String alias) {
        return castColumn(tableAliasAndField, org.jooq.impl.SQLDataType.DECIMAL, alias);
    }

    /**
     * Tarix/vaxt sahəsini format pattern ilə string-ə çevirir.
     *
     * <p>SQL nəticəsi: {@code TO_CHAR(alias.field, 'pattern') AS alias}
     *
     * <pre>{@code
     *   .castDateTime("u.createdAt", "YYYY-MM-DD", "createdDate")
     *   .castDateTime("o.orderTime", "YYYY-MM-DD HH24:MI", "orderTimeStr")
     * }</pre>
     *
     * @param pattern  PostgreSQL TO_CHAR formatı — məs. {@code "YYYY-MM-DD"}
     */
    public SelectQueryBuilder<T> castDateTime(String tableAliasAndField,
                                              String pattern,
                                              String alias) {
        if (tableAliasAndField != null && pattern != null && alias != null)
            castCols.add(new CastCol(tableAliasAndField, null, alias, pattern));
        return this;
    }

    public SelectQueryBuilder<T> rawSelectField(Field<?> field) {
        if (field != null) rawSelectFields.add(field);
        return this;
    }

    /**
     * ORDER BY-a birbaşa jOOQ {@link SortField} əlavə edir.
     *
     * <pre>{@code
     *   .rawOrderBy(DSL.field("created_at").desc())
     *   .rawOrderBy(DSL.field("score").asc().nullsLast())
     * }</pre>
     */
    public SelectQueryBuilder<T> rawOrderBy(SortField<?> sortField) {
        if (sortField != null) orderFields.add(sortField);
        return this;
    }

    /** SELECT DISTINCT */
    public SelectQueryBuilder<T> distinct() {
        this.distinct = true;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  2 — JOIN
    // ════════════════════════════════════════════════════════════════════

    /**
     * INNER JOIN.
     *
     * <pre>{@code .innerJoin(Order.class, "o").on("id").equalsField("userId") }</pre>
     *
     * <p>Bu "u.id = o.userId" deməkdir.
     */
    public <F> JoinOnBuilder<F> innerJoin(Class<F> foreignEntity, String alias) {
        return new JoinOnBuilder<>(this, entityClass, tableAlias, foreignEntity, alias, JoinType.JOIN);
    }

    /** LEFT JOIN */
    public <F> JoinOnBuilder<F> leftJoin(Class<F> foreignEntity, String alias) {
        return new JoinOnBuilder<>(this, entityClass, tableAlias, foreignEntity, alias, JoinType.LEFT_OUTER_JOIN);
    }

    /** RIGHT JOIN */
    public <F> JoinOnBuilder<F> rightJoin(Class<F> foreignEntity, String alias) {
        return new JoinOnBuilder<>(this, entityClass, tableAlias, foreignEntity, alias, JoinType.RIGHT_OUTER_JOIN);
    }

    /**
     * Müəyyən entity-dən çoxsəviyyəli LEFT JOIN.
     *
     * <pre>{@code
     *   // u → o → p (Product)
     *   .leftJoinFrom(Order.class, "o", Product.class, "p").on("productId").equalsField("id")
     * }</pre>
     */
    public <F> JoinOnBuilder<F> leftJoinFrom(Class<?> fromEntity, String fromAlias,
                                              Class<F> toEntity, String toAlias) {
        return new JoinOnBuilder<>(this, fromEntity, fromAlias, toEntity, toAlias, JoinType.LEFT_OUTER_JOIN);
    }

    /** Müəyyən entity-dən çoxsəviyyəli INNER JOIN */
    public <F> JoinOnBuilder<F> innerJoinFrom(Class<?> fromEntity, String fromAlias,
                                               Class<F> toEntity, String toAlias) {
        return new JoinOnBuilder<>(this, fromEntity, fromAlias, toEntity, toAlias, JoinType.JOIN);
    }

    /**
     * Subquery ilə JOIN.
     *
     * <pre>{@code
     *   DSLContext dsl = ...;
     *   Select<?> sub = dsl.select(DSL.field("userId"), DSL.sum(DSL.field("amount")))
     *                      .from("orders").groupBy(DSL.field("userId"));
     *   .joinSubQuery(sub, "orderSums", "userId", "id")
     * }</pre>
     */
    public SelectQueryBuilder<T> joinSubQuery(
            Select<?> subQuery, String subAlias,
            String subField, String mainField) {
        subQueryJoins.add(new SubQueryJoin(subQuery, subAlias, subField, mainField));
        return this;
    }

    /**
     * LEFT JOIN — tam Condition ilə (çoxlu field cütü + əlavə şərtlər üçün).
     * Condition kənarda ({@link az.mbm.jooqsqlgenerate.JooqQuery}) qurulub buraya verilir.
     */
    public SelectQueryBuilder<T> leftJoinWithCondition(Class<?> entity, String alias, Condition on) {
        conditionedEntityJoins.add(new ConditionedEntityJoin(entity, alias, JoinType.LEFT_OUTER_JOIN, on));
        return this;
    }

    /** INNER JOIN — tam Condition ilə. */
    public SelectQueryBuilder<T> innerJoinWithCondition(Class<?> entity, String alias, Condition on) {
        conditionedEntityJoins.add(new ConditionedEntityJoin(entity, alias, JoinType.JOIN, on));
        return this;
    }

    /**
     * LEFT JOIN — raw jOOQ {@link Table} (məs. derived table / {@link SelectTable}) ilə,
     * tam Condition ilə. Condition kənarda ({@link az.mbm.jooqsqlgenerate.JooqQuery}) qurulub buraya verilir.
     * {@code rawTable} artıq {@code alias} ilə aliaslanmış olmalıdır.
     */
    public SelectQueryBuilder<T> leftJoinRawWithCondition(Table<?> rawTable, String alias, Condition on) {
        rawTableJoins.add(new RawTableJoin(rawTable, alias, JoinType.LEFT_OUTER_JOIN, on));
        return this;
    }

    /** INNER JOIN — raw jOOQ {@link Table} ilə, tam Condition ilə. */
    public SelectQueryBuilder<T> innerJoinRawWithCondition(Table<?> rawTable, String alias, Condition on) {
        rawTableJoins.add(new RawTableJoin(rawTable, alias, JoinType.JOIN, on));
        return this;
    }

    // ─── JoinOnBuilder ────────────────────────────────────────────────────

    public static class JoinOnBuilder<F> {
        private final SelectQueryBuilder<?> parent;
        private final Class<?>  fromClass;
        private final String    fromAlias;
        private final Class<F>  toClass;
        private final String    toAlias;
        private final JoinType  joinType;
        private       String    fromField;

        JoinOnBuilder(SelectQueryBuilder<?> p,
                      Class<?> fc, String fa,
                      Class<F> tc, String ta, JoinType jt) {
            parent = p; fromClass = fc; fromAlias = fa;
            toClass = tc; toAlias = ta; joinType = jt;
        }

        /** Ana cədvəlin sahəsini seç */
        public JoinOnBuilder<F> on(String fromField) {
            this.fromField = fromField;
            return this;
        }

        /**
         * Xarici cədvəlin sahəsini seç → JOIN tamamlandı.
         * "fromAlias.fromField = toAlias.toField" şərti yaranır.
         */
        public SelectQueryBuilder<?> equalsField(String toField) {
            ((SelectQueryBuilder) parent).joins.add(new JoinConfig(
                    fromClass, fromAlias, toClass, toAlias,
                    joinType, fromField, toField, Op.EQUAl));
            return parent;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  3 — WHERE
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHERE şərtidir — {@link Specification} ilə.
     * Bir neçə {@code .where()} çağrışı AND ilə birləşdirilir.
     *
     * <pre>{@code
     *   .where(Spec.eq("status", "ACTIVE"))
     *   .where(Spec.in("roleId", roleIds))
     *   // → WHERE status = 'ACTIVE' AND roleId IN (...)
     * }</pre>
     */
    public SelectQueryBuilder<T> where(Specification<T> spec) {
        if (spec == null) return this;  // Filter.build() null qaytara bilər — atlanır
        this.whereSpec = (this.whereSpec == null) ? spec : this.whereSpec.and(spec);
        return this;
    }

    /** Hazır jOOQ {@link Condition}-u birbaşa WHERE-ə əlavə edir */
    public SelectQueryBuilder<T> where(Condition condition) {
        if (condition == null) return this;
        return where(table -> condition);
    }

    /**
     * WHERE outerField IN (SELECT ... FROM entity WHERE ...)
     *
     * <p>Tək sahə üçün:
     * <pre>{@code
     *   .inSubQuery(List.of("userId"),
     *       SubQueryIn.from(Order.class, "o")
     *           .select("o.userId")
     *           .filter("status", Op.EQUAl, "PAID"))
     * }</pre>
     *
     * <p>Composite sahə üçün:
     * <pre>{@code
     *   .inSubQuery(List.of("firstName", "lastName"),
     *       SubQueryIn.from(Blacklist.class, "bl")
     *           .select("bl.firstName", "bl.lastName"))
     * }</pre>
     *
     * @param outerFields əsas sorğudakı sahə adları (camelCase, alias olmadan)
     * @param sub         SubQueryIn builder-i
     */
    public SelectQueryBuilder<T> inSubQuery(List<String> outerFields, SubQueryIn sub) {
        if (outerFields != null && !outerFields.isEmpty() && sub != null)
            inSubQueryEntries.add(new SubQueryInEntry(outerFields, sub));
        return this;
    }

    /**
     * Global filter — həm əsas cədvəl, həm də join cədvəllərinə WHERE filtri.
     *
     * <p>Field adı "alias.field" formatında olduqda tableMap-dən uyğun
     * EntityTable tapılır; alias yoxdursa əsas cədvəl istifadə edilir.
     * Tip uyğunlaşması (String → Integer, və s.) avtomatik edilir.
     *
     * <pre>{@code
     *   .globalWhereFilter("status",    Op.EQUAl,        "ACTIVE")
     *   .globalWhereFilter("o.amount",  Op.GREATER_THAN, "100")  // join cədvəli
     *   .globalWhereFilter("c.country", Op.IN,           "AZ,TR")
     * }</pre>
     */
    public SelectQueryBuilder<T> globalWhereFilter(
            String aliasAndField,
            az.mbm.jooqsqlgenerate.enums.Op op,
            Object value) {
        if (aliasAndField != null && !aliasAndField.isBlank() && op != null && value != null)
            globalFilterEntries.add(new FiltersEntry(aliasAndField, op, value));
        return this;
    }

    /**
     * Field-to-field WHERE şərti — iki cədvəl sütununu Op ilə müqayisə edir.
     *
     * <p>Hər iki tərəf {@code "alias.field"} formatında verilir.
     * Alias tableMap-dən həll edilir; alias olmadıqda əsas cədvəl istifadə edilir.
     *
     * <pre>{@code
     *   .fieldFilter("t.fkTaskId",   Op.EQUAl,        "f.fkTaskId")
     *   .fieldFilter("t.totalPrice", Op.GREATER_THAN,  "f.totalPrice")
     *   // → WHERE t."fk_task_id" = f."fk_task_id"
     *   //     AND t."total_price" > f."total_price"
     * }</pre>
     *
     * @param leftAliasAndField  sol tərəf: {@code "alias.field"}
     * @param op                 müqayisə operatoru
     * @param rightAliasAndField sağ tərəf: {@code "alias.field"}
     */
    public SelectQueryBuilder<T> fieldFilter(String leftAliasAndField, Op op, String rightAliasAndField) {
        if (leftAliasAndField != null && !leftAliasAndField.isBlank()
                && op != null
                && rightAliasAndField != null && !rightAliasAndField.isBlank())
            fieldFilterRows.add(new FieldFilterRow(leftAliasAndField, op, rightAliasAndField));
        return this;
    }

    /**
     * Birbaşa jOOQ {@link Condition}-u WHERE-ə əlavə edir.
     * Bir neçə çağrış AND ilə birləşir.
     *
     * <pre>{@code
     *   .rawCondition(DSL.field("user_id").in(DSL.select(...)))
     *   .rawCondition(DSL.exists(DSL.selectOne().from(...)))
     * }</pre>
     */
    public SelectQueryBuilder<T> rawCondition(Condition condition) {
        if (condition != null) rawConditions.add(condition);
        return this;
    }

    /**
     * OR qrupu filter — sadə hal: bir qrup içindəki şərtlər OR ilə birləşir.
     *
     * <pre>{@code
     *   // WHERE status = 'A' AND (actionType = 'IN' OR actionType = 'OUT')
     *   .filter("t.status", Op.EQUAl, "A")
     *   .orFilter("myOr", "t.actionType", Op.EQUAl, "IN")
     *   .orFilter("myOr", "t.actionType", Op.EQUAl, "OUT")
     * }</pre>
     *
     * @param orGroupAlias  OR qrupunun adı — eyni adlı şərtlər OR ilə birləşir
     * @param aliasAndField "tableAlias.fieldName" formatında sütun
     */
    public SelectQueryBuilder<T> orFilter(String orGroupAlias, String aliasAndField, Op op, Object value) {
        if (orGroupAlias != null && !orGroupAlias.isBlank() && aliasAndField != null && value != null)
            orFilterRows.add(new OrFilterRow(orGroupAlias, orGroupAlias, aliasAndField, op, value));
        return this;
    }

    /**
     * OR qrupu filter — mürəkkəb hal: (andGroup1 OR andGroup2).
     * Eyni andGroupAlias-lı şərtlər AND, fərqli andGroupAlias-lılar OR ilə birləşir.
     *
     * <pre>{@code
     *   // WHERE (field1='y' AND field2='z') OR (field3='a' AND field4='b')
     *   .orFilter("myOr", "andGroup1", "t.field1", Op.EQUAl, "y")
     *   .orFilter("myOr", "andGroup1", "t.field2", Op.EQUAl, "z")
     *   .orFilter("myOr", "andGroup2", "t.field3", Op.EQUAl, "a")
     *   .orFilter("myOr", "andGroup2", "t.field4", Op.EQUAl, "b")
     * }</pre>
     *
     * @param orGroupAlias  OR qrupunun adı
     * @param andGroupAlias AND alt-qrupunun adı
     * @param aliasAndField "tableAlias.fieldName" formatında sütun
     */
    public SelectQueryBuilder<T> orFilter(String orGroupAlias, String andGroupAlias, String aliasAndField, Op op, Object value) {
        if (orGroupAlias != null && !orGroupAlias.isBlank()
                && andGroupAlias != null && !andGroupAlias.isBlank()
                && aliasAndField != null && value != null)
            orFilterRows.add(new OrFilterRow(orGroupAlias, andGroupAlias, aliasAndField, op, value));
        return this;
    }

    /**
     * Birbaşa jOOQ {@link Condition}-u HAVING-ə əlavə edir.
     *
     * <pre>{@code
     *   .rawHaving(DSL.count().greaterThan(5))
     *   .rawHaving(DSL.sum(DSL.field("amount", Double.class)).greaterThan(1000.0))
     * }</pre>
     */
    public SelectQueryBuilder<T> rawHaving(Condition condition) {
        if (condition != null) rawHavings.add(condition);
        return this;
    }

    /**
     * Əlavə HAVING şərti — computed alias filterlər üçün.
     * Bir neçə çağrış AND ilə birləşir.
     */
    public SelectQueryBuilder<T> having(Condition condition) {
        if (condition == null) return this;
        this.extraHaving = (this.extraHaving == null) ? condition : this.extraHaving.and(condition);
        return this;
    }

    /**
     * Specification-u HAVING-ə əlavə edir (EXISTS / NOT EXISTS üçün).
     */
    public SelectQueryBuilder<T> having(Specification<T> spec) {
        if (spec == null) return this;
        EntityTable<T> table = new EntityTable<>(entityClass, tableAlias);
        Condition condition = spec.toCondition(table);
        return having(condition);
    }

    /**
     * Field-to-field müqayisəsi.
     *
     * <pre>{@code .whereFieldEq("u", "managerId", "d", "headId") }</pre>
     * <p>→ {@code u.managerId = d.headId}
     */
    public SelectQueryBuilder<T> whereFieldEq(String t1, String f1, String t2, String f2) {
        return where(mainTable ->
                new EntityTable<>(entityClass, t1).getField(f1)
                        .eq(new EntityTable<>(entityClass, t2).getField(f2))
        );
    }

    // ════════════════════════════════════════════════════════════════════
    //  4 — GROUP BY + Aqreqat + HAVING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Aqreqat funksiyalar + GROUP BY + HAVING.
     *
     * <pre>{@code
     *   .aggregate(
     *       AggregateBuilder.groupBy("u.status")
     *           .sum("o.amount").round(2)
     *               .having(Op.GREATER_THAN, 500)
     *               .orderDesc()
     *               .as("totalAmount").done()
     *           .count("o.id").as("orderCount").done()
     *   )
     * }</pre>
     */
    public SelectQueryBuilder<T> aggregate(AggregateBuilder<T> agg) {
        this.aggregator = agg;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  5 — ORDER BY
    // ════════════════════════════════════════════════════════════════════

    /**
     * ORDER BY field DESC.
     *
     * <pre>{@code .orderByDesc("u.createdAt") }</pre>
     */
    public SelectQueryBuilder<T> orderByDesc(String tableAliasAndField) {
        pendingOrders.add(new PendingOrder(tableAliasAndField, false));
        return this;
    }

    /**
     * ORDER BY field ASC.
     *
     * <pre>{@code .orderByAsc("u.name") }</pre>
     */
    public SelectQueryBuilder<T> orderByAsc(String tableAliasAndField) {
        pendingOrders.add(new PendingOrder(tableAliasAndField, true));
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  6 — PAGİNASİYA
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sayfalama — {@link SelectTable#getRowCount()} cəmi sətir sayını qaytarır.
     *
     * @param pageNumber 0-based səhifə nömrəsi
     * @param pageSize   hər səhifədəki sətir sayı
     */
    public SelectQueryBuilder<T> page(int pageNumber, int pageSize) {
        this.pageNumber = pageNumber;
        this.pageSize   = pageSize;
        this.paginate   = true;
        return this;
    }

    /** Sayfalama olmadan bütün nəticəni gətirir, COUNT işləmir. */
    public SelectQueryBuilder<T> noPagination() {
        this.paginate  = false;
        this.countOnly = false;
        return this;
    }

    /** Pagination olmadan yalnız COUNT-u aktiv edir. */
    public SelectQueryBuilder<T> withCount() {
        this.paginate  = false;
        this.countOnly = true;
        return this;
    }

    /**
     * Pagination aktiv olur (LIMIT/OFFSET işləyir), lakin COUNT sorğusu atlanır.
     * rowCount = -1 qaytarılır. Excel export kimi ssenarilərdə COUNT lazım olmadıqda istifadə et.
     */
    public SelectQueryBuilder<T> skipCount() {
        this.skipCount = true;
        return this;
    }

    /**
     * Yalnız COUNT sorğusu icra edilir, əsas data sorğusu işləmir.
     * Sətir sayını öyrənmək lazım olduqda, data lazım olmadıqda istifadə et.
     * rowCount = ümumi sətir sayı, result = boş siyahı.
     */
    public SelectQueryBuilder<T> onlyCount() {
        this.onlyCount = true;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  BUILD — Template Method Pattern
    //  Hər addım ayrı private metod — test edilə bilər
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builder-dən jOOQ sorğusu qurur.
     *
     * @param dsl jOOQ DSLContext
     * @return {@link SelectTable} — sorğu + sətir sayı
     */
    public SelectTable build(DSLContext dsl) {
        EntityTable<T> mainTable = new EntityTable<>(entityClass, tableAlias);

        Map<String, EntityTable<?>> tableMap = new LinkedHashMap<>();
        tableMap.put(tableAlias, mainTable);

        // JOIN entity-lərini əvvəlcədən tableMap-ə əlavə et —
        // buildSelectFields() JOIN alias-larını tanısın deyə
        for (JoinConfig j : joins) {
            tableMap.computeIfAbsent(j.fromAlias(), a -> new EntityTable<>(j.fromClass(), a));
            tableMap.computeIfAbsent(j.toAlias(),   a -> new EntityTable<>(j.toClass(), a));
        }
        for (ConditionedEntityJoin j : conditionedEntityJoins) {
            tableMap.computeIfAbsent(j.alias(), a -> new EntityTable<>(j.entity(), a));
        }
        for (RawTableJoin j : rawTableJoins) {
            tableMap.computeIfAbsent(j.alias(), a -> new EntityTable<>(j.rawTable(), a));
        }

        // Addım 1 — SELECT sahələri
        SQLDialect dialect = dsl.dialect();
        List<SelectFieldOrAsterisk> selectFields = buildSelectFields(mainTable, tableMap, dialect);

        // Addım 2 — FROM
        SelectJoinStep<Record> query = distinct
                ? dsl.selectDistinct(selectFields).from(mainTable.getTable())
                : dsl.select(selectFields).from(mainTable.getTable());

        // Addım 3 — Entity JOINlər
        query = buildEntityJoins(query, tableMap);

        // Addım 3.5 — Raw table JOINlər (derived table / SelectTable, tam Condition ilə)
        query = buildRawTableJoins(query, tableMap);

        // Addım 4 — Subquery JOINlər
        query = buildSubQueryJoins(query);

        // Addım 5 — WHERE şərti
        Condition whereCondition = buildWhereCondition(mainTable, tableMap);

        // Addım 6 — GROUP BY + HAVING
        SelectHavingStep<Record> afterGroupBy = buildGroupBy(query, mainTable, whereCondition, tableMap, dialect);

        // Addım 7 — ORDER BY sahələri (normal + aqreqat)
        List<SortField<?>> allOrderFields = buildAllOrderFields(tableMap, mainTable);

        // Addım 8 — ORDER BY tətbiqi
        SelectSeekStepN<Record> ordered = afterGroupBy.orderBy(allOrderFields);

        // Addım 9 — COUNT (pagination və ya withCount() üçün)
        // skipCount=true olduqda COUNT sorğusu işləmir, rowCount = -1 qaytarılır
        // onlyCount=true olduqda yalnız COUNT işləyir, əsas sorğu icra edilmir
        int rowCount = 0;
        if (onlyCount) {
            rowCount = buildCount(dsl, mainTable, whereCondition, afterGroupBy, tableMap);
            return new SelectTable(dsl.selectZero().where(DSL.falseCondition()), rowCount);
        } else if ((paginate || countOnly) && !skipCount) {
            rowCount = buildCount(dsl, mainTable, whereCondition, afterGroupBy, tableMap);
        } else if (skipCount) {
            rowCount = -1;
        }

        // Addım 10 — LIMIT / OFFSET
        Select<Record> finalQuery = paginate
                ? ordered.limit(pageSize).offset((long) pageNumber * pageSize)
                : ordered;

        return new SelectTable(finalQuery, rowCount);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Template Method addımları (private)
    // ════════════════════════════════════════════════════════════════════

    private List<SelectFieldOrAsterisk> buildSelectFields(
            EntityTable<T> mainTable,
            Map<String, EntityTable<?>> tableMap,
            SQLDialect dialect) {

        boolean hasCustomFields = selectMainEntityFields
                || !columns.isEmpty() || !selectAsCols.isEmpty()
                || !computed.isEmpty() || !computedChain.isEmpty() || !caseCols.isEmpty()
                || !concatCols.isEmpty() || !coalesceCols.isEmpty() || !subSelectCols.isEmpty()
                || !rawSelectFields.isEmpty() || !castCols.isEmpty()
                || (aggregator != null && !aggregator.getAggFields().isEmpty())
                // v1.1.51: SELECT tam boş olsa belə GROUP BY sahələri custom sayılır —
                // aşağıdakı auto-add bloku onları SELECT-ə əlavə edir. Əvvəllər bu halda
                // "SELECT *" yazılırdı və Postgres "column ... must appear in the GROUP BY
                // clause" xətası verirdi.
                || (aggregator != null && !aggregator.getGroupByFields().isEmpty());

        if (!hasCustomFields) return List.of(DSL.asterisk());

        List<SelectFieldOrAsterisk> fields = new ArrayList<>();

        // selectAsCols-da olan source field-lər — bunlar columns-dan çıxarılacaq
        // eyni field həm columns, həm selectAsCols-da olarsa SELECT-də dublikat olmasın
        Set<String> selectAsSourceKeys = new HashSet<>();
        for (SelectAsCol sa : selectAsCols) {
            selectAsSourceKeys.add(sa.aliasAndField());
        }

        // selectAll() çağrılanda əsas entity-nin bütün sütunları explicit əlavə olunur.
        // SELECT * əvəzinə: yalnız əsas entity sütunları, camelCase alias ilə.
        // columns() ilə birlikdə istifadə olunduqda dublikatlar atlanır.
        if (selectMainEntityFields) {
            Set<String> explicitCols = new HashSet<>(columns);
            for (String javaFieldName : mainTable.getEntityFieldMap().keySet()) {
                // columns()-də artıq varsa atla (dublikat önlənir)
                String qualifiedName = tableAlias + "." + javaFieldName;
                if (explicitCols.contains(javaFieldName) || explicitCols.contains(qualifiedName)) continue;
                // selectAsCols-da da yoxla
                if (selectAsSourceKeys.contains(javaFieldName) || selectAsSourceKeys.contains(qualifiedName)) continue;

                Field<?> f = mainTable.getField(javaFieldName);
                // DB sütun adı (snake_case) ilə java adı (camelCase) fərqlidirsə alias əlavə et
                if (!f.getName().equals(javaFieldName)) {
                    fields.add(f.as(javaFieldName));
                } else {
                    fields.add(f);
                }
            }
        }

        // Sadə sütunlar — user camelCase yazmışsa AS "camelCase" əlavə olunur
        // Məs: "t1.productName" → "t1"."product_name" AS "productName"
        // selectAsCols-da artıq olan field-lər buradan atılır (dublikat önlənir)
        for (String col : columns) {
            if (selectAsSourceKeys.contains(col)) continue;
            EntityTable<?> t         = tableMap.getOrDefault(aliasPart(col), mainTable);
            String         fieldName = fieldPart(col);
            Field<?>       f         = t.getField(fieldName);
            // DB sütun adı ilə user-in yazdığı ad fərqlidirsə alias əlavə et
            if (!f.getName().equals(fieldName)) {
                fields.add(f.as(fieldName));
            } else {
                fields.add(f);
            }
        }

        // Özəl alias verilmiş sütunlar: "t1.fieldName" AS "outputAlias"
        for (SelectAsCol sa : selectAsCols) {
            EntityTable<?> t = tableMap.getOrDefault(aliasPart(sa.aliasAndField()), mainTable);
            fields.add(t.getField(fieldPart(sa.aliasAndField())).as(sa.outputAlias()));
        }

        // GROUP BY sahələri — SELECT-də olmayan GROUP BY field-ləri avtomatik əlavə olunur
        // columns və selectAsCols-da artıq olan field-lər atlanır (dublikat önlənir)
        if (aggregator != null && !aggregator.getGroupByFields().isEmpty()) {
            Set<String> alreadyInSelect = new HashSet<>();
            for (String col : columns)       alreadyInSelect.add(col);
            for (SelectAsCol sa : selectAsCols) alreadyInSelect.add(sa.aliasAndField());

            // Aqreqat sütunların output alias-ları — GROUP BY-dakı raw sahə eyni
            // adla SELECT-ə əlavə olunmasın (əks halda iki eyni adlı sütun yaranır
            // və xarici sorğuda "t"."alias" istinadı ambiqual olur).
            Set<String> aggOutputAliases = new HashSet<>();
            for (AggregateBuilder.AggField af : aggregator.getAggFields())
                if (af.alias() != null) aggOutputAliases.add(af.alias());

            for (String gf : aggregator.getGroupByFields()) {
                if (alreadyInSelect.contains(gf)) continue;
                String fieldName = fieldPart(gf);
                if (aggOutputAliases.contains(fieldName)) {
                    alreadyInSelect.add(gf);
                    continue;
                }
                EntityTable<?> t  = tableMap.getOrDefault(aliasPart(gf), mainTable);
                Field<?>       f  = t.getField(fieldName);
                if (!f.getName().equals(fieldName)) fields.add(f.as(fieldName));
                else                                fields.add(f);
                alreadyInSelect.add(gf);
            }
        }

        // Riyazi əməliyyatla hesablanmış sütunlar — 2 sahəli sadə form
        for (ComputedCol c : computed) {
            EntityTable<?> t1 = tableMap.getOrDefault(c.tableAlias1(), mainTable);
            EntityTable<?> t2 = tableMap.getOrDefault(c.tableAlias2(), mainTable);
            Field<Object> f1 = (Field<Object>) t1.getField(c.field1());
            Field<Object> f2 = (Field<Object>) t2.getField(c.field2());
            fields.add(applyMathOp(f1, c.op(), f2, c.nullDefault()).as(c.alias()));
        }

        // Riyazi əməliyyatla hesablanmış sütunlar — çox sahəli zəncir form
        for (ComputedField cf : computedChain) {
            fields.add(cf.toField(mainTable, tableMap, dialect));
        }

        // CASE WHEN sütunlar
        for (CaseBuilder<T> cb : caseCols) {
            fields.add(buildCaseField(cb, mainTable, tableMap));
        }

        // CONCAT sütunlar
        for (ConcatCol cc : concatCols) {
            fields.add(buildConcatField(cc, mainTable, tableMap));
        }

        // COALESCE sütunlar
        for (CoalesceCol cc : coalesceCols) {
            fields.add(buildCoalesceField(cc, mainTable, tableMap));
        }

        // Subquery sütunlar
        for (SubSelectBuilder sub : subSelectCols) {
            fields.add(sub.toField(tableMap));
        }

        // Cast sütunlar
        for (CastCol cc : castCols) {
            EntityTable<?> t = tableMap.getOrDefault(aliasPart(cc.tableAliasAndField()), mainTable);
            Field<?> src = t.getField(fieldPart(cc.tableAliasAndField()));
            if (cc.datePattern() != null) {
                fields.add(DateFormatHelper.toDialectField(src, cc.datePattern(), dialect).as(cc.alias()));
            } else {
                fields.add(src.cast(cc.targetType()).as(cc.alias()));
            }
        }

        // Birbaşa jOOQ Field-lər
        fields.addAll(rawSelectFields);

        // Aqreqat sütunlar (SUM, COUNT, AVG, ...)
        if (aggregator != null) {
            for (AggregateBuilder.AggField agg : aggregator.getAggFields()) {
                EntityTable<?> t = tableMap.getOrDefault(agg.tableAlias(), mainTable);
                fields.add(AggregateBuilder.toJooqField(agg, t, tableMap));
            }
        }

        return fields;
    }

    private <R extends Record> SelectJoinStep<R> buildEntityJoins(
            SelectJoinStep<R> query,
            Map<String, EntityTable<?>> tableMap) {

        for (JoinConfig j : joins) {
            EntityTable<?> fromTable = tableMap.computeIfAbsent(
                    j.fromAlias(), a -> new EntityTable<>(j.fromClass(), a));
            EntityTable<?> toTable   = new EntityTable<>(j.toClass(), j.toAlias());
            tableMap.put(j.toAlias(), toTable);

            Field<Object> fromField = (Field<Object>) fromTable.getField(j.fromField());
            Field<Object> toField   = (Field<Object>) toTable.getField(j.toField());
            Condition     on        = fromField.eq(toField);

            query = switch (j.joinType()) {
                case LEFT_OUTER_JOIN  -> query.leftJoin(toTable.getTable()).on(on);
                case RIGHT_OUTER_JOIN -> query.rightJoin(toTable.getTable()).on(on);
                default               -> query.join(toTable.getTable()).on(on);
            };
        }
        // ConditionedEntityJoin — tam Condition ilə entity JOIN-lər
        for (ConditionedEntityJoin j : conditionedEntityJoins) {
            EntityTable<?> toTable = new EntityTable<>(j.entity(), j.alias());
            tableMap.put(j.alias(), toTable);
            query = switch (j.joinType()) {
                case LEFT_OUTER_JOIN  -> query.leftJoin(toTable.getTable()).on(j.on());
                case RIGHT_OUTER_JOIN -> query.rightJoin(toTable.getTable()).on(j.on());
                default               -> query.join(toTable.getTable()).on(j.on());
            };
        }

        return query;
    }

    /**
     * Raw table (derived table / {@link SelectTable}) JOIN-lərini emal edir.
     * {@code tableMap}-də artıq {@code build()}-in başında qeydiyyatdan keçmiş
     * {@link EntityTable} nüsxəsindən istifadə edilir (eyni instansiya).
     */
    private <R extends Record> SelectJoinStep<R> buildRawTableJoins(
            SelectJoinStep<R> query,
            Map<String, EntityTable<?>> tableMap) {

        for (RawTableJoin j : rawTableJoins) {
            EntityTable<?> toTable = tableMap.computeIfAbsent(
                    j.alias(), a -> new EntityTable<>(j.rawTable(), a));
            query = switch (j.joinType()) {
                case LEFT_OUTER_JOIN  -> query.leftJoin(toTable.getTable()).on(j.on());
                case RIGHT_OUTER_JOIN -> query.rightJoin(toTable.getTable()).on(j.on());
                default               -> query.join(toTable.getTable()).on(j.on());
            };
        }
        return query;
    }

    private <R extends Record> SelectJoinStep<R> buildSubQueryJoins(SelectJoinStep<R> query) {
        for (SubQueryJoin sj : subQueryJoins) {
            Table<?>       sub  = sj.subQuery().asTable(sj.subAlias());
            EntityTable<T> main = new EntityTable<>(entityClass, tableAlias);
            Field<Object>  mf   = (Field<Object>) main.getField(sj.mainField());
            Field<Object>  sf   = (Field<Object>) DSL.field(DSL.name(sj.subAlias(), sj.subField()));
            query = query.leftJoin(sub).on(mf.eq(sf));
        }
        return query;
    }

    /**
     * OR filter sahəsini həll edir (v1.1.51).
     * Prefixsiz referans computed/concat alias-a uyğun gəlirsə ifadənin özü istifadə olunur
     * (əvvəllər sütun kimi axtarılıb NPE/səhv SQL yaranırdı). Aqreqat alias-ı OR qrupunda
     * (WHERE) mümkün deyil — aydın xəta atılır. Prefiksli referans həmişə real sütundur.
     */
    @SuppressWarnings("unchecked")
    private Field<Object> resolveOrFilterField(String aliasAndField,
                                               EntityTable<T> mainTable,
                                               Map<String, EntityTable<?>> tableMap) {
        int dot = aliasAndField.indexOf('.');
        if (dot < 0) {
            ComputedField cf = computedChain.stream()
                    .filter(c -> aliasAndField.equals(c.getAlias()))
                    .findFirst().orElse(null);
            if (cf != null) return (Field<Object>) cf.buildExpr(mainTable, tableMap);

            ConcatCol cc = concatCols.stream()
                    .filter(c -> aliasAndField.equals(c.alias()))
                    .findFirst().orElse(null);
            if (cc != null) return (Field<Object>) buildConcatExpr(cc, mainTable, tableMap);

            if (aggregator != null && aggregator.getAggFields().stream()
                    .anyMatch(af -> aliasAndField.equals(af.alias())))
                throw new IllegalStateException(
                        "orFilter: \"" + aliasAndField + "\" aqreqat alias-ıdır — OR qrupu WHERE-də "
                        + "işləyir, aqreqat şərti üçün havingFilter istifadə edin");

            return (Field<Object>) mainTable.getField(aliasAndField);
        }
        EntityTable<?> t = tableMap.getOrDefault(aliasAndField.substring(0, dot), mainTable);
        return (Field<Object>) t.getField(aliasAndField.substring(dot + 1));
    }

    private Condition buildWhereCondition(EntityTable<T> mainTable,
                                          Map<String, EntityTable<?>> tableMap) {
        Condition cond = whereSpec == null ? null : whereSpec.toCondition(mainTable);

        // IN (SELECT ...) subquery şərtlər
        for (SubQueryInEntry e : inSubQueryEntries) {
            Condition c = e.sub().toCondition(e.outerFields(), mainTable, tableMap);
            cond = (cond == null) ? c : cond.and(c);
        }

        // Global filter — alias.field → tableMap-dən resolve edilir
        // Xüsusi hal: filter sahəsi bir ComputedField alias-ına uyğun gəlirsə,
        // ifadənin özü WHERE-ə genişləndirilir (HAVING deyil).
        for (FiltersEntry gf : globalFilterEntries) {
            int dot = gf.aliasAndField().indexOf('.');
            String plainName = (dot > 0)
                    ? gf.aliasAndField().substring(dot + 1)
                    : gf.aliasAndField();

            // ComputedField alias uyğunluğunu yoxla.
            // "t.grandTotal" kimi prefiksli referans REAL sütuna aiddir —
            // output alias uyğunluğu yalnız prefixsiz yazılışda yoxlanılır.
            ComputedField matchedCf = (dot > 0) ? null : computedChain.stream()
                    .filter(cf -> plainName.equals(cf.getAlias()))
                    .findFirst()
                    .orElse(null);

            // CONCAT alias uyğunluğunu yoxla (computed deyilsə, prefixsizsə)
            ConcatCol matchedCc = (dot > 0 || matchedCf != null) ? null : concatCols.stream()
                    .filter(cc -> plainName.equals(cc.alias()))
                    .findFirst()
                    .orElse(null);

            Condition c;
            if (matchedCf != null) {
                // Computed ifadəni birbaşa WHERE-ə genişləndir:
                // məs. globalWhereFilter("grandTotal", GT, 1000)
                // → WHERE (price * qty) + tax > 1000
                @SuppressWarnings("unchecked")
                Field<Object> expr = (Field<Object>) matchedCf.buildExpr(mainTable, tableMap);
                c = FilterStrategies.get(gf.op()).apply(expr, gf.value());
            } else if (matchedCc != null) {
                // CONCAT ifadəsini birbaşa WHERE-ə genişləndir:
                // məs. globalWhereFilter("carrierDescription", LIKE, "%abc%")
                // → WHERE CONCAT(...) LIKE '%abc%'
                @SuppressWarnings("unchecked")
                Field<Object> expr = (Field<Object>) buildConcatExpr(matchedCc, mainTable, tableMap);
                c = FilterStrategies.get(gf.op()).apply(expr, gf.value());
            } else {
                // Adi sahə — cədvəldən resolve et
                EntityTable<?> t;
                String fieldName;
                if (dot > 0) {
                    String alias = gf.aliasAndField().substring(0, dot);
                    fieldName    = plainName;
                    t = tableMap.getOrDefault(alias, mainTable);
                } else {
                    fieldName = plainName;
                    t = mainTable;
                }
                @SuppressWarnings("unchecked")
                Field<Object> f = (Field<Object>) t.getField(fieldName);
                c = FilterStrategies.get(gf.op()).apply(f, gf.value());
            }
            cond = (cond == null) ? c : cond.and(c);
        }

        // OR qrupları — x AND (y OR z) və ya x AND ((y AND z) OR (a AND b))
        if (!orFilterRows.isEmpty()) {
            // orGroup → andGroup → List<row>
            LinkedHashMap<String, LinkedHashMap<String, List<OrFilterRow>>> grouped = new LinkedHashMap<>();
            for (OrFilterRow row : orFilterRows) {
                grouped
                    .computeIfAbsent(row.orGroup(),  k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.andGroup(), k -> new ArrayList<>())
                    .add(row);
            }

            for (LinkedHashMap<String, List<OrFilterRow>> andGroups : grouped.values()) {
                Condition orGroupCond = null;
                for (List<OrFilterRow> andRows : andGroups.values()) {
                    Condition andCond = null;
                    for (OrFilterRow row : andRows) {
                        Field<Object> f = resolveOrFilterField(row.aliasAndField(), mainTable, tableMap);
                        Condition c = FilterStrategies.get(row.op()).apply(f, row.value());
                        andCond = (andCond == null) ? c : andCond.and(c);
                    }
                    orGroupCond = (orGroupCond == null) ? andCond : orGroupCond.or(andCond);
                }
                if (orGroupCond != null)
                    cond = (cond == null) ? orGroupCond : cond.and(orGroupCond);
            }
        }

        // Field-to-field müqayisə şərtlər (t.price > f.price kimi)
        for (FieldFilterRow ff : fieldFilterRows) {
            Field<Object> leftF  = resolveAliasField(ff.leftAliasAndField(),  mainTable, tableMap);
            Field<Object> rightF = resolveAliasField(ff.rightAliasAndField(), mainTable, tableMap);
            if (leftF != null && rightF != null) {
                Condition c = applyFieldOp(ff.op(), leftF, rightF);
                cond = (cond == null) ? c : cond.and(c);
            }
        }

        // Birbaşa jOOQ raw condition-lar
        for (Condition rc : rawConditions) {
            cond = (cond == null) ? rc : cond.and(rc);
        }

        return cond;
    }

    /**
     * "alias.field" formatında sahəni tableMap-dən resolve edir.
     * Alias yoxdursa əsas cədvəl istifadə edilir.
     */
    @SuppressWarnings("unchecked")
    private Field<Object> resolveAliasField(String aliasAndField,
                                             EntityTable<T> mainTable,
                                             Map<String, EntityTable<?>> tableMap) {
        int dot = aliasAndField.indexOf('.');
        String alias     = (dot > 0) ? aliasAndField.substring(0, dot) : null;
        String fieldName = (dot > 0) ? aliasAndField.substring(dot + 1) : aliasAndField;
        EntityTable<?> t = (alias != null)
                ? tableMap.getOrDefault(alias, mainTable)
                : mainTable;
        return (Field<Object>) t.getField(fieldName);
    }

    /**
     * İki field arasında Op-a uyğun Condition yaradır.
     * JOIN ON və field-to-field WHERE şərtləri üçün istifadə edilir.
     */
    @SuppressWarnings("unchecked")
    private static Condition applyFieldOp(Op op, Field<Object> from, Field<Object> to) {
        if (op == null) return from.eq(to);
        return switch (op) {
            case NOT_EQUAL                -> from.ne(to);
            case LESS_THAN                -> from.lt(to);
            case LESS_THAN_OR_EQUAL_TO    -> from.le(to);
            case GREATER_THAN             -> from.gt(to);
            case GREATER_THAN_OR_EQUAL_TO -> from.ge(to);
            default                       -> from.eq(to);
        };
    }

    private SelectHavingStep<Record> buildGroupBy(
            SelectJoinStep<Record> query,
            EntityTable<T> mainTable,
            Condition where,
            Map<String, EntityTable<?>> tableMap,
            SQLDialect dialect) {

        SelectConditionStep<Record> conditioned = (where != null)
                ? query.where(where)
                : query.where(DSL.trueCondition());

        if (aggregator == null || aggregator.getGroupByFields().isEmpty()) {
            // v1.1.51: əvvəllər burada HAVING-lər (agg having, rawHaving, extraHaving,
            // havingExists) səssiz itirdi — GROUP BY olmasa belə tətbiq olunur
            // (jOOQ-da SelectConditionStep SelectHavingStep-i extend edir).
            return applyHaving(conditioned, mainTable, tableMap);
        }

        // ── Explicit GROUP BY sahələri ───────────────────────────────────
        // LinkedHashMap: sıra + duplicate yoxlama
        LinkedHashMap<String, Field<?>> groupFieldMap = new LinkedHashMap<>();

        for (String gf : aggregator.getGroupByFields()) {
            EntityTable<?> t = tableMap.getOrDefault(aliasPart(gf), mainTable);
            Field<?> f = t.getField(fieldPart(gf));
            groupFieldMap.put(aliasPart(gf) + "." + f.getName(), f);
        }

        // ── AUTO GROUP BY: SELECT-dəki qeyri-aggregate sütunlar ─────────
        // SQL qaydası: SELECT-dəki hər qeyri-aggregate sütun GROUP BY-da olmalıdır.
        // Developer bunu unutsa da sistem özü əlavə edir.

        // Aggregate alias-ları — bunlar GROUP BY-a getməməlidir
        Set<String> aggAliases = new HashSet<>();
        for (AggregateBuilder.AggField af : aggregator.getAggFields())
            aggAliases.add(af.alias());

        // Sadə sütunlar — GROUP BY-a əlavə et (sonuncu qalsın — put)
        for (String col : columns) {
            String fieldName = fieldPart(col);
            if (!aggAliases.contains(fieldName)) {
                EntityTable<?> t = tableMap.getOrDefault(aliasPart(col), mainTable);
                Field<?> f = t.getField(fieldName);
                groupFieldMap.put(aliasPart(col) + "." + f.getName(), f);
            }
        }

        // selectAsCols sütunları da GROUP BY-a əlavə et — sonuncu qalsın (put)
        for (SelectAsCol sa : selectAsCols) {
            if (!aggAliases.contains(sa.outputAlias())) {
                EntityTable<?> t = tableMap.getOrDefault(aliasPart(sa.aliasAndField()), mainTable);
                Field<?> f = t.getField(fieldPart(sa.aliasAndField()));
                groupFieldMap.put(aliasPart(sa.aliasAndField()) + "." + f.getName(), f);
            }
        }

        // 2 sahəli computed sütunlar — hər iki sahəni GROUP BY-a əlavə et
        for (ComputedCol cc : computed) {
            EntityTable<?> t1 = tableMap.getOrDefault(cc.tableAlias1(), mainTable);
            EntityTable<?> t2 = tableMap.getOrDefault(cc.tableAlias2(), mainTable);
            Field<?> f1 = t1.getField(cc.field1());
            Field<?> f2 = t2.getField(cc.field2());
            groupFieldMap.putIfAbsent(cc.tableAlias1() + "." + f1.getName(), f1);
            groupFieldMap.putIfAbsent(cc.tableAlias2() + "." + f2.getName(), f2);
        }

        // Çox sahəli computed sütunlar (ComputedField) — alias ilə GROUP BY
        // SQL-də computed ifadənin özünü GROUP BY-a vermək lazımdır,
        // alias isə bir çox DB-də GROUP BY-da işləmir.
        // Ən təhlükəsiz yol: ComputedField-in toField() nəticəsini alias-sız vermək.
        for (ComputedField cf : computedChain) {
            // GROUP BY-da alias deyil, ifadənin özü lazımdır
            Field<?> expr = cf.buildExpr(mainTable, tableMap, dialect);
            groupFieldMap.putIfAbsent("__cf_" + cf.getAlias(), expr);
        }

        // Cast sütunlar — ifadənin özü GROUP BY-a verilir
        for (CastCol cc : castCols) {
            if (!aggAliases.contains(cc.alias())) {
                EntityTable<?> t = tableMap.getOrDefault(aliasPart(cc.tableAliasAndField()), mainTable);
                Field<?> src = t.getField(fieldPart(cc.tableAliasAndField()));
                Field<?> expr = cc.datePattern() != null
                        ? DateFormatHelper.toDialectField(src, cc.datePattern(), dialect)
                        : src.cast(cc.targetType());
                groupFieldMap.putIfAbsent("__cast_" + cc.alias(), expr);
            }
        }

        SelectHavingStep<Record> grouped = conditioned.groupBy(new ArrayList<>(groupFieldMap.values()));
        return applyHaving(grouped, mainTable, tableMap);
    }

    /**
     * HAVING = aggregate HAVING + extraHaving + rawHavings + havingExists.
     * GROUP BY olub-olmamasından asılı olmayaraq tətbiq olunur (v1.1.51).
     */
    private SelectHavingStep<Record> applyHaving(SelectHavingStep<Record> grouped,
                                                 EntityTable<T> mainTable,
                                                 Map<String, EntityTable<?>> tableMap) {
        Condition aggHaving = (aggregator != null)
                ? AggregateBuilder.buildHaving(aggregator.getAggFields(), mainTable, tableMap)
                : null;
        Condition allHaving = (aggHaving != null && extraHaving != null) ? aggHaving.and(extraHaving)
                            : (aggHaving  != null) ? aggHaving
                            : extraHaving;

        for (Condition rh : rawHavings) {
            allHaving = (allHaving == null) ? rh : allHaving.and(rh);
        }

        // EXISTS / NOT EXISTS HAVING şərtləri
        if (aggregator != null) {
            for (AggregateBuilder.AggExistsClause ec : aggregator.getExistsClauses()) {
                Condition ecCond = ec.toCondition(mainTable, tableMap);
                allHaving = (allHaving == null) ? ecCond : allHaving.and(ecCond);
            }
        }

        return (allHaving != null) ? (SelectHavingStep<Record>) grouped.having(allHaving) : grouped;
    }

    private List<SortField<?>> buildAllOrderFields(Map<String, EntityTable<?>> tableMap, EntityTable<T> mainTable) {
        List<SortField<?>> all = new ArrayList<>(orderFields);

        // pendingOrders — tableMap ilə düzgün resolve et
        for (PendingOrder po : pendingOrders) {
            String alias = aliasPart(po.tableAliasAndField());
            String field = fieldPart(po.tableAliasAndField());
            EntityTable<?> t = alias != null
                    ? tableMap.getOrDefault(alias, mainTable)
                    : mainTable;
            @SuppressWarnings("unchecked")
            Field<Object> f = (Field<Object>) t.getField(field);
            all.add(po.asc() ? f.asc() : f.desc());
        }

        if (aggregator != null) {
            for (AggregateBuilder.AggField agg : aggregator.getAggFields()) {
                if (agg.orderDirection() != null) {
                    Field<?> f = DSL.field(DSL.name(agg.alias()));
                    all.add("DESC".equalsIgnoreCase(agg.orderDirection()) ? f.desc() : f.asc());
                }
            }
        }
        return all;
    }

    private int buildCount(
            DSLContext dsl,
            EntityTable<T> mainTable,
            Condition where,
            SelectHavingStep<Record> groupedQuery,
            Map<String, EntityTable<?>> tableMap) {

        if (aggregator != null && !aggregator.getGroupByFields().isEmpty()) {
            // GROUP BY varsa: COUNT(1) FROM (subquery) AS _count
            // groupedQuery artıq JOIN-ləri özündə saxlayır
            Record1<Integer> r = dsl.selectCount()
                    .from(groupedQuery.asTable("_count"))
                    .fetchOne();
            return r == null ? 0 : r.value1();
        } else {
            // GROUP BY yoxdursa: COUNT-da da əsas sorğudakı eyni JOIN-lər tətbiq edilməlidir,
            // əks halda INNER JOIN-lər və/və ya JOIN şərtində/WHERE-də join cədvəlinin sahələri
            // istifadə olunan hallarda count səhv çıxır.
            SelectJoinStep<Record1<Integer>> countQuery =
                    dsl.selectCount().from(mainTable.getTable());
            countQuery = buildEntityJoins(countQuery, tableMap);
            countQuery = buildSubQueryJoins(countQuery);

            Record1<Integer> r = countQuery
                    .where(where != null ? where : DSL.trueCondition())
                    .fetchOne();
            return r == null ? 0 : r.value1();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Yardımcı metodlar
    // ════════════════════════════════════════════════════════════════════

    private EntityTable<T> resolveTable(String tableAndField) {
        String alias = aliasPart(tableAndField);
        return alias != null
                ? new EntityTable<>(entityClass, alias)
                : new EntityTable<>(entityClass, tableAlias);
    }

    /** "u.name" → "u"  (nöqtə yoxdursa null) */
    private String aliasPart(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : null;
    }

    /** "u.name" → "name" */
    private String fieldPart(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(dot + 1) : s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> applyMathOp(Field<Object> f1, MathOp op, Field<Object> f2,
                                  NullDefault nd) {
        // NullDefault.NONE → COALESCE tətbiq edilmir
        if (nd == null || nd == NullDefault.NONE) {
            Field<? extends Number> numF1 = (Field<? extends Number>) (Field<?>) f1;
            Field<? extends Number> numF2 = (Field<? extends Number>) (Field<?>) f2;
            return op.apply(numF1, numF2);
        }
        // NONOPERATION → orijinal sahə olduğu kimi (COALESCE bükülmədən) qaytarılır
        if (op == MathOp.NONOPERATION) return f1;
        // NullDefault.ZERO / ONE → hər iki sahəni COALESCE ilə bük
        // Field<Object>→Field<? extends Number>: Field<?> üzərindən double-cast lazımdır
        Field<? extends Number> n1 = (Field<? extends Number>)(Field<?>) DSL.coalesce(f1, DSL.val(nd.numericValue()));
        Field<? extends Number> n2 = (Field<? extends Number>)(Field<?>) DSL.coalesce(f2, DSL.val(nd.numericValue()));
        // DIVIDE → NULLIF ilə sıfıra bölmə qorunması (raw Field cast — tip inference üçün)
        return (op == MathOp.DIVIDE)
                ? n1.div((Field<? extends Number>)(Field<?>) DSL.nullif((Field) n2, 0))
                : op.apply(n1, n2);
    }

    private Field<?> buildCaseField(CaseBuilder<T> cb, EntityTable<T> mainTable,
                                    Map<String, EntityTable<?>> tableMap) {
        return CaseFieldBuilder.build(cb, mainTable, tableMap);
    }

    /**
     * CONCAT ifadəsini (alias-sız) qurur — həm SELECT, həm də WHERE/HAVING üçün ortaq məntiq.
     * v1.1.53-dən render {@link ConcatRenderer}-də mərkəzləşib (EXISTS joinField də oradan istifadə edir).
     */
    private Field<?> buildConcatExpr(ConcatCol cc, EntityTable<T> mainTable,
                                      Map<String, EntityTable<?>> tableMap) {
        return ConcatRenderer.render(cc.separator(), cc.items(), mainTable, tableMap);
    }

    private Field<?> buildConcatField(ConcatCol cc, EntityTable<T> mainTable,
                                       Map<String, EntityTable<?>> tableMap) {
        return buildConcatExpr(cc, mainTable, tableMap).as(cc.alias());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> buildCoalesceField(CoalesceCol cc, EntityTable<T> mainTable,
                                         Map<String, EntityTable<?>> tableMap) {
        if (cc.fields().isEmpty())
            throw new IllegalStateException("COALESCE: ən azı bir sahə lazımdır");

        List<Field<?>> coalesceList = new ArrayList<>();
        boolean stringDefault = cc.defaultValue() instanceof String;
        for (String f : cc.fields()) {
            EntityTable<?> t = tableMap.getOrDefault(aliasPart(f), mainTable);
            Field<?> fld = t.getField(fieldPart(f));
            // Default mətn (String) olduqda sütunlar CAST(... AS VARCHAR) edilir —
            // əks halda qeyri-mətn sütun (Long/Date/...) ilə mətn literalı
            // Postgres-də COALESCE-də "types ... cannot be matched" xətası verir.
            coalesceList.add(stringDefault ? fld.cast(String.class) : fld);
        }
        // Son element — default dəyər
        coalesceList.add(DSL.inline(cc.defaultValue()));

        Field<?> first = coalesceList.get(0);
        Field<?>[] rest = coalesceList.subList(1, coalesceList.size()).toArray(new Field[0]);
        return DSL.coalesce(first, rest).as(cc.alias());
    }
}
