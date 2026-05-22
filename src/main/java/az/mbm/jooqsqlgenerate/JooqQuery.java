package az.mbm.jooqsqlgenerate;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.builder.AggregateBuilder;
import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.builder.ConcatItem;
import az.mbm.jooqsqlgenerate.builder.ComputedField;
import az.mbm.jooqsqlgenerate.builder.SelectQueryBuilder;
import az.mbm.jooqsqlgenerate.builder.SubQueryIn;
import az.mbm.jooqsqlgenerate.builder.SubSelectBuilder;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.core.SelectFetchJooq;
import az.mbm.jooqsqlgenerate.core.SelectTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;
import az.mbm.jooqsqlgenerate.enums.Agg;
import az.mbm.jooqsqlgenerate.enums.MathOp;
import az.mbm.jooqsqlgenerate.spec.ExistsSpec;
import az.mbm.jooqsqlgenerate.spec.Filter;
import az.mbm.jooqsqlgenerate.spec.Filters;
import az.mbm.jooqsqlgenerate.spec.Specification;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════
 * JOOQ QUERY — Spring-dən asılı olmayan, sorğu başına yeni nümunə
 *
 * <p><b>Nə üçün?</b><br>
 * {@link JooqManager} {@code @Prototype} bean-dir, lakin standart {@code @Autowired}
 * ilə singleton servisə inject edildikdə Spring onu <em>bir dəfə</em> verir.
 * Ardıcıl gələn sorğularda columns / filters / groupBy siyahıları <b>qarışır</b>.
 *
 * <p>{@code JooqQuery} Spring bean deyil — hər sorğu üçün {@code JooqQuery.from(...)}
 * ilə yeni nümunə yaradılır. State yalnız o nümunəyə aiddir, başqası ilə
 * heç bir əlaqəsi yoxdur.
 *
 * <p><b>İstifadə:</b>
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class UserService {
 *
 *     private final DSLContext dsl;   // yeganə inject — thread-safe, singleton olar
 *
 *     public SelectTable searchUsers(String status, String name) {
 *         return JooqQuery.from(User.class, "u")
 *             .select("u.id", "u.name", "u.email", "u.status")
 *             .filter("status", Op.EQUAl, status)
 *             .filter("name",   Op.LIKE,   name)
 *             .orderBy("u.createdAt", "DESC")
 *             .page(0, 20)
 *             .execute(dsl);
 *     }
 * }
 * }</pre>
 *
 * <p>JooqManager ilə yanaşı mövcuddur — köhnə kodu dəyişmək məcburiyyəti yoxdur.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class JooqQuery<T> {

    // ─── Daxili sorğu vəziyyəti ──────────────────────────────────────────
    private final Class<T>             entity;       // null → generated mode
    private final String               alias;
    private final Table<?>             generatedTable; // null → entity mode

    // Generated mode — birbaşa Field<?> siyahıları
    private final List<Field<?>>            rawGroupByFields  = new ArrayList<>();
    private final List<RawJoinRow>          rawJoins          = new ArrayList<>();
    /** alias → original Table (alias tətbiq edilməmiş) — generated mode-da field resolve üçün */
    private final Map<String, Table<?>>     joinTableRegistry = new LinkedHashMap<>();

    private final List<String>              columns          = new ArrayList<>();
    private final List<SelectAsRow>         selectAsRows     = new ArrayList<>();
    private final List<ComputedFieldEntry>  computedFields   = new ArrayList<>();
    private final List<ComputedRow>         computedCols     = new ArrayList<>();
    private final List<CoalesceRow>         coalesceCols     = new ArrayList<>();
    private final List<ConcatRow>           concatCols       = new ArrayList<>();
    private final List<SubSelectBuilder>    subSelectCols    = new ArrayList<>();
    private final List<SubQueryInRow>       subQueryInCols   = new ArrayList<>();
    private final List<Condition>           rawConditions    = new ArrayList<>();
    private final List<Condition>           rawHavings       = new ArrayList<>();
    private final List<OrFilterEntry>       orFilterEntries  = new ArrayList<>();
    private record OrFilterEntry(String orGroup, String andGroup, String aliasAndField, Op op, Object value) {}
    private final List<Field<?>>            rawSelectFields  = new ArrayList<>();
    private final List<SortField<?>>        rawOrderFields   = new ArrayList<>();
    private final List<FilterRow>           filters          = new ArrayList<>();
    private final List<FiltersEntry>   globalFilters       = new ArrayList<>();
    private final List<FieldFilterEntry> fieldFilterEntries = new ArrayList<>();
    private final List<JoinRow>             joins            = new ArrayList<>();
    private final List<ExtJoinRow>          extJoins         = new ArrayList<>();
    private final List<SelectJoinRow>       selectJoins      = new ArrayList<>();
    private final List<ExistsSpec<?, ?>>    existsSpecs      = new ArrayList<>();
    private final List<ExistsSpec<?, ?>>    havingExistsSpecs = new ArrayList<>();
    private final List<String>              groupByFields    = new ArrayList<>();
    private final List<AggRow>              aggRows          = new ArrayList<>();
    private final List<SortRow>             sortRows         = new ArrayList<>();
    private final List<CaseRow>             caseRows         = new ArrayList<>();
    private final List<CaseBuilder<?>>      caseBuilders     = new ArrayList<>();

    private final List<FilterRow> havingFilterRows = new ArrayList<>();

    /** ROUND(field, scale) AS alias — SELECT sütunları + filter registry */
    private final List<RoundedColumnRow>        roundedColumns  = new ArrayList<>();
    private final Map<String, RoundedColumnRow> roundedAliasMap = new LinkedHashMap<>();

    private boolean distinct   = false;
    private boolean paginate   = false;  // yalnız setPage() çağrılanda aktiv olur
    private boolean countOnly  = false;  // pagination olmadan yalnız COUNT
    private boolean skipCount  = false;  // pagination var, amma COUNT işləməsin
    private boolean onlyCount  = false;  // yalnız COUNT işləsin, əsas data sorğusu icra edilməsin
    private int     pageNumber = 0;
    private int     pageSize   = 50;

    // ─── Daxili record-lar ───────────────────────────────────────────────
    private record FilterRow(String field, Op op, Object value) {}
    private record SelectAsRow(String aliasAndField, String outputAlias) {}
    private record JoinRow(String type, Class<?> entity, String alias,
                           String fromField, String toField) {}
    private record AggRow(Agg fn, String field, String alias, Integer round,
                          String orderDir,
                          MathOp mathOp, String mathField, ComputedField expr) {}
    private record SortRow(String field, String dir) {}
    private record CaseRow(String field, Op op, Object when,
                           Object then, Object els, String alias) {}
    private record ComputedRow(String alias,
                               String ta1, String f1, MathOp op,
                               String ta2, String f2) {}
    private record ComputedFieldEntry(ComputedField cf,
                                      Op filterOp,
                                      Object filterValue) {}
    private record CoalesceRow(String alias, Object def, String[] fields) {}
    private record ConcatRow(String alias, String separator, List<ConcatItem> items) {}
    private record SubQueryInRow(List<String> outerFields, SubQueryIn sub) {}
    private record FiltersEntry(String aliasAndField, Op op, Object value) {}
    private record FieldFilterEntry(String leftAliasAndField, Op op, String rightAliasAndField) {}
    private record RawJoinRow(Table<?> table, JoinType type, Condition on) {}
    /** Çoxlu ON field cütü: fromAlias.fromField OP toAlias.toField */
    private record FieldPair(String fromAlias, String fromField, Op op, String toField) {
        /** Geri uyğunluq üçün — op verilmədikdə EQUAl istifadə edilir */
        FieldPair(String fromAlias, String fromField, String toField) {
            this(fromAlias, fromField, Op.EQUAl, toField);
        }
    }
    /** JOIN ON-da əlavə value şərti: toAlias.field OP value */
    private record JoinFilterRow(String field, Op op, Object value) {}
    /** Genişləndirilmiş entity JOIN — çoxlu field cütü + əlavə şərtlər */
    private record ExtJoinRow(String type, Class<?> entity, String alias,
                               List<FieldPair> pairs, List<JoinFilterRow> extras) {}
    /** SelectTable (derived table) JOIN — string field adları ilə */
    private record SelectJoinRow(String type, SelectTable subQuery, String alias,
                                  List<FieldPair> pairs, List<JoinFilterRow> extras) {}
    /**
     * ROUND(field, scale) AS alias — SELECT-də yuvarlama ilə sütun.
     * Filter tətbiq edildikdə WHERE ROUND(field, scale) OP value kimi işlənir.
     */
    private record RoundedColumnRow(String alias, String fieldRef, int scale) {}

    // ─── Konstruktorlar — birbaşa istifadə edilmir ───────────────────────

    /** Entity mode — JPA class + reflection (EntityTable istifadə edir) */
    private JooqQuery(Class<T> entity, String alias) {
        this.entity         = Objects.requireNonNull(entity, "Entity null ola bilməz");
        this.alias          = Objects.requireNonNull(alias,  "Alias null ola bilməz");
        this.generatedTable = null;
    }

    /** Generated mode — jOOQ generated Table<?> (reflection yoxdur) */
    private JooqQuery(Table<?> table, String alias) {
        this.entity         = null;
        this.alias          = Objects.requireNonNull(alias, "Alias null ola bilməz");
        this.generatedTable = Objects.requireNonNull(table, "Table null ola bilməz");
    }

    /** JooqManager-in UPDATE əməliyyatı üçün entity class-ı qaytarır. */
    public Class<T> entityClass() { return entity; }

    /** Generated mode-da işləyib-işləmədiyini yoxlayır. */
    public boolean isGeneratedMode() { return generatedTable != null; }

    // ════════════════════════════════════════════════════════════════════
    //  GİRİŞ NÖQTƏLƏRİ
    // ════════════════════════════════════════════════════════════════════

    /**
     * Entity mode — JPA annotasiyaları ilə (köhnə üsul, geriyə uyğun).
     *
     * <pre>{@code
     *   JooqQuery.from(User.class, "u")
     *       .select("u.id", "u.name")
     *       .filter("status", EQUAl, "ACTIVE")
     *       .execute(dsl);
     * }</pre>
     */
    public static <T> JooqQuery<T> from(Class<T> entity, String alias) {
        return new JooqQuery<>(entity, alias);
    }

    /**
     * Generated mode — jOOQ generated {@link Table} ilə (tövsiyə olunan).
     *
     * <p>Reflection yoxdur, cache lazım deyil, tip-təhlükəlidir.
     * Field adı səhv yazılsa <b>compile xətası</b> verir.
     *
     * <pre>{@code
     *   import static com.example.domain.jooq.Tables.*;
     *
     *   JooqQuery.from(USERS, "u")
     *       .select(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
     *       .filter(USERS.STATUS.eq("ACTIVE"))
     *       .groupBy(USERS.DEPARTMENT)
     *       .orderBy(USERS.CREATED_AT.desc())
     *       .page(0, 20)
     *       .execute(dsl);
     * }</pre>
     */
    public static <R extends Record> JooqQuery<R> from(Table<R> table, String alias) {
        return new JooqQuery<>(table, alias);
    }

    /**
     * Derived table mode — başqa bir {@link SelectTable} sorğusundan yeni sorğu başladır.
     *
     * <p>Bu üsulla {@code FROM (SELECT ...) alias} quruluşu yaranır.
     * Daxili sorğunun sütunlarına {@code alias.field} formatında müraciət etmək olar.
     *
     * <p><b>String adla select/filter/groupBy/orderBy:</b><br>
     * Daxili sorğunun sütun adlarını (select listindəki adlarla) string kimi vermək olar:
     * <pre>{@code
     *   // Addım 1 — daxili sorğu
     *   SelectTable active = JooqQuery.from(USERS, "u")
     *       .select(USERS.ID, USERS.FIRST_NAME.as("name"), USERS.STATUS)
     *       .filter(USERS.STATUS.eq("ACTIVE"))
     *       .noPagination()
     *       .execute(dsl);
     *
     *   // Addım 2 — derived table üzərindən yeni sorğu
     *   Table<?> sub = active.asTable("sub");
     *
     *   JooqQuery.from(active, "sub")
     *       .select("id", "name")                          // sub.id, sub.name
     *       .filter("name", Op.LIKE, "Ali")  // sub.name LIKE '%Ali%'
     *       .leftJoin(ORDERS, "o", sub.field("id", Long.class).eq(ORDERS.USER_ID))
     *       .orderBy("name", "ASC")
     *       .page(0, 20)
     *       .execute(dsl);
     * }</pre>
     *
     * @param subQuery daxili sorğu (derived table kimi istifadə edilir)
     * @param alias    derived table-ın SQL alias adı
     */
    public static <R extends Record> JooqQuery<R> from(SelectTable subQuery, String alias) {
        return new JooqQuery<>(subQuery.asTable(alias), alias);
    }

    // ════════════════════════════════════════════════════════════════════
    //  SELECT
    // ════════════════════════════════════════════════════════════════════

    /**
     * SELECT sütunlar.
     *
     * <ul>
     *   <li><b>Entity mode</b>: {@code "alias.field"} formatında verilir,
     *       {@link az.mbm.jooqsqlgenerate.core.EntityTable} həll edir.</li>
     *   <li><b>Generated mode</b>: sahə adı (camelCase və ya snake_case) avtomatik
     *       {@code generatedTable.field()} vasitəsilə həll olunur.
     *       Prefiks ({@code "u."}) varsa, atılır.</li>
     * </ul>
     */
    public JooqQuery<T> select(String... cols) {
        if (cols == null) return this;
        if (generatedTable != null) {
            for (String col : cols) {
                Field<?> f = resolveFieldByAlias(col);
                if (f != null) rawSelectFields.add(f);
            }
        } else {
            columns.addAll(Arrays.asList(cols));
        }
        return this;
    }

    /** SELECT sütunlar — dinamik {@link List} ilə (hər iki mode dəstəklənir). */
    public JooqQuery<T> select(List<String> cols) {
        if (cols == null) return this;
        if (generatedTable != null) {
            for (String col : cols) {
                Field<?> f = resolveFieldByAlias(col);
                if (f != null) rawSelectFields.add(f);
            }
        } else {
            columns.addAll(cols);
        }
        return this;
    }

    /**
     * SELECT sütunlar — generated {@link Field} varargs ilə.
     *
     * <pre>{@code
     *   .select(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
     * }</pre>
     */
    public JooqQuery<T> select(Field<?>... fields) {
        if (fields != null) rawSelectFields.addAll(Arrays.asList(fields));
        return this;
    }

    /**
     * SELECT sütunlar — dinamik {@code List<Field<?>>} ilə.
     *
     * <pre>{@code
     *   List<Field<?>> cols = List.of(USERS.ID, USERS.NAME, ORDERS.AMOUNT);
     *   JooqQuery.from(USERS, "u").select(cols)...
     * }</pre>
     */
    public JooqQuery<T> selectFields(List<? extends Field<?>> cols) {
        if (cols != null) rawSelectFields.addAll(cols);
        return this;
    }

    /** 2 sahəli riyazi ifadə sütunu: {@code (ta1.f1 OP ta2.f2) AS alias} */
    public JooqQuery<T> computedColumn(String alias,
                                       String ta1, MathOp op, String f1,
                                       String ta2, String f2) {
        if (alias != null && f1 != null && f2 != null && op != null)
            computedCols.add(new ComputedRow(alias, ta1, f1, op, ta2, f2));
        return this;
    }

    /** Çox sahəli riyazi ifadə sütunu ({@link ComputedField} ilə). */
    public JooqQuery<T> computedColumn(ComputedField cf) {
        if (cf != null) computedFields.add(new ComputedFieldEntry(cf, null, null));
        return this;
    }

    /**
     * Çox sahəli riyazi ifadə sütunu + həmin sütunun nəticəsinə HAVING filtri.
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
     * }</pre>
     *
     * @param cf    computed sütun ({@code .as(alias)} mütləq olmalıdır)
     * @param op    filter əməliyyatı
     * @param value filter dəyəri
     */
    public JooqQuery<T> computedColumn(ComputedField cf, Op op, Object value) {
        if (cf != null) computedFields.add(new ComputedFieldEntry(cf, op, value));
        return this;
    }

    /** COALESCE sütunu. */
    public JooqQuery<T> coalesce(String alias, Object defaultValue, String... fields) {
        if (alias != null && fields != null && fields.length > 0)
            coalesceCols.add(new CoalesceRow(alias, defaultValue, fields));
        return this;
    }

    /** CONCAT sütunu — sütun adları ilə (geriyə dönük uyğun). */
    public JooqQuery<T> concat(String alias, String separator, String... fields) {
        if (alias != null && fields != null && fields.length > 0) {
            List<ConcatItem> items = Arrays.stream(fields)
                    .map(ConcatItem::field)
                    .collect(java.util.stream.Collectors.toList());
            concatCols.add(new ConcatRow(alias, separator, items));
        }
        return this;
    }

    /** CONCAT sütunu — List&lt;String&gt; variantı. */
    public JooqQuery<T> concat(String alias, String separator, List<String> fields) {
        if (alias != null && fields != null && !fields.isEmpty()) {
            List<ConcatItem> items = fields.stream()
                    .map(ConcatItem::field)
                    .collect(java.util.stream.Collectors.toList());
            concatCols.add(new ConcatRow(alias, separator, items));
        }
        return this;
    }

    /** CONCAT sütunu — field + literal qarışıq. */
    public JooqQuery<T> concat(String alias, String separator, ConcatItem... items) {
        if (alias != null && items != null && items.length > 0)
            concatCols.add(new ConcatRow(alias, separator, Arrays.asList(items)));
        return this;
    }

    /** SELECT siyahısına scalar subquery sütunu. */
    public JooqQuery<T> subSelect(SubSelectBuilder sub) {
        if (sub != null) subSelectCols.add(sub);
        return this;
    }

    /** SELECT siyahısına birbaşa jOOQ {@link Field}. */
    public JooqQuery<T> rawSelect(Field<?> field) {
        if (field != null) rawSelectFields.add(field);
        return this;
    }

    /**
     * SELECT siyahısına {@code ROUND(field, scale) AS alias} sütunu əlavə edir.
     *
     * <p>Bu sütuna {@link #filter(String, Op, Object)} tətbiq edildikdə
     * backend-də {@code WHERE ROUND(field, scale) OP value} kimi işlənir —
     * sadəcə {@code field OP value} deyil.
     *
     * <p>Həm <b>entity mode</b>, həm <b>generated mode</b> dəstəklənir.
     * JOIN edilmiş cədvəl sahəsi üçün {@code "alias.field"} formatı istifadə edin.
     *
     * <pre>{@code
     *   // Sadə istifadə
     *   JooqQuery.from(Order.class, "o")
     *       .selectRound("o.totalPrice", 2, "roundedTotal")
     *       .filter("roundedTotal", Op.GREATER_THAN, 100)
     *       // → SELECT ROUND(o."total_price", 2) AS "roundedTotal"
     *       //   WHERE ROUND(o."total_price", 2) > 100
     *
     *   // Generated mode
     *   JooqQuery.from(ORDERS, "o")
     *       .selectRound("o.total_price", 2, "roundedTotal")
     *       .filter("roundedTotal", Op.LESS_THAN, 500)
     *       // → WHERE ROUND(o."total_price", 2) < 500
     * }</pre>
     *
     * @param fieldRef sahə: {@code "tableAlias.fieldName"} və ya {@code "fieldName"} formatında
     * @param scale    onluq rəqəm sayı (məs. 2 → 0.00)
     * @param alias    çıxış alias adı (məs. "roundedTotal")
     */
    public JooqQuery<T> selectRound(String fieldRef, int scale, String alias) {
        if (fieldRef == null || alias == null) return this;
        String cleanAlias = fieldPart(alias); // "t.roundedAmount" → "roundedAmount"
        RoundedColumnRow row = new RoundedColumnRow(cleanAlias, fieldRef, scale);
        roundedColumns.add(row);
        roundedAliasMap.put(cleanAlias, row);

        // Generated mode — field dərhal həll edilir
        if (generatedTable != null) {
            Field<?> f = resolveFieldByAlias(fieldRef);
            if (f != null) {
                rawSelectFields.add(
                        DSL.round((Field<? extends Number>) f, scale).as(cleanAlias));
            }
        }
        // Entity mode — execute()-də işlənir (EntityTable lazımdır)
        return this;
    }

    /**
     * SELECT sütununa özəlləşdirilmiş çıxış alias verir — entity mode üçün.
     *
     * <p>Format: {@code "tableAlias.javaFieldName"} → SQL-də {@code col_name AS outputAlias}
     *
     * <pre>{@code
     *   JooqQuery.from(Warehouse.class, "t")
     *       .selectAs("t1.fkProductId", "productId")
     *       .selectAs("t.operationDate", "date")
     *       .leftJoin(Product.class, "t1", "fkProductId", "id")
     *       .execute(dsl);
     * }</pre>
     *
     * @param aliasAndField sütun: {@code "tableAlias.javaFieldName"} formatında
     * @param outputAlias   SQL alias-ı (nəticədə bu ad görünür)
     */
    public JooqQuery<T> selectAs(String aliasAndField, String outputAlias) {
        if (aliasAndField != null && !aliasAndField.isBlank()
                && outputAlias != null && !outputAlias.isBlank())
            selectAsRows.add(new SelectAsRow(aliasAndField, outputAlias));
        return this;
    }

    /** SELECT DISTINCT */
    public JooqQuery<T> distinct() {
        this.distinct = true;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  JOIN
    // ════════════════════════════════════════════════════════════════════

    /** LEFT JOIN — entity mode üçün (string field adları, tək cüt) */
    public JooqQuery<T> leftJoin(Class<?> entity, String alias,
                                 String fromField, String toField) {
        joins.add(new JoinRow("LEFT", entity, alias, fromField, toField));
        return this;
    }

    /** INNER JOIN — entity mode üçün (string field adları, tək cüt) */
    public JooqQuery<T> innerJoin(Class<?> entity, String alias,
                                  String fromField, String toField) {
        joins.add(new JoinRow("INNER", entity, alias, fromField, toField));
        return this;
    }

    /**
     * LEFT JOIN builder — çoxlu ON şərti + əlavə value filterlər.
     *
     * <pre>{@code
     *   JooqQuery.from(WarehouseFlow.class, "t")
     *       .leftJoin(Product.class, "t1")
     *           .on("fkProductId", "id")           // t.fkProductId = t1.id
     *           .on("companyId",   "companyId")     // t.companyId   = t1.companyId
     *           .andOn("status", Op.EQUAl, "A")  // t1.status = 'A'
     *       .done()
     *       .execute(dsl);
     * }</pre>
     */
    public JoinBuilder leftJoin(Class<?> entity, String alias) {
        return new JoinBuilder(this, "LEFT", entity, alias);
    }

    /**
     * INNER JOIN builder — çoxlu ON şərti + əlavə value filterlər.
     */
    public JoinBuilder innerJoin(Class<?> entity, String alias) {
        return new JoinBuilder(this, "INNER", entity, alias);
    }

    /**
     * Fluent JOIN builder — çoxlu field cütü + əlavə ON şərtləri.
     *
     * <p>{@code on(fromField, toField)} — əsas cədvəl sahəsi = join cədvəl sahəsi.
     * {@code onFrom(fromAlias, fromField, toField)} — konkret alias-dan.
     * {@code andOn(field, op, value)} — join cədvəlindəki dəyər şərti.
     * {@code done()} — {@link JooqQuery}-yə qayıdır.
     */
    public final class JoinBuilder {
        private final JooqQuery<T>          parent;
        private final String                type;
        private final Class<?>              entity;
        private final String                joinAlias;
        private final List<FieldPair>       pairs  = new ArrayList<>();
        private final List<JoinFilterRow>   extras = new ArrayList<>();

        JoinBuilder(JooqQuery<T> parent, String type, Class<?> entity, String joinAlias) {
            this.parent    = parent;
            this.type      = type;
            this.entity    = entity;
            this.joinAlias = joinAlias;
        }

        /**
         * ON şərti: ana cədvəl.fromField = join cədvəl.toField
         *
         * @param fromField ana cədvəldəki sahə adı (camelCase)
         * @param toField   join cədvəlindəki sahə adı (camelCase)
         */
        public JoinBuilder on(String fromField, String toField) {
            if (fromField != null && toField != null)
                pairs.add(new FieldPair(alias, fromField, Op.EQUAl, toField));
            return this;
        }

        /**
         * ON şərti: konkret alias.fromField = join cədvəl.toField
         *
         * @param fromAlias  "from" cədvəlin alias-ı
         * @param fromField  həmin cədvəldəki sahə adı
         * @param toField    join cədvəlindəki sahə adı
         */
        public JoinBuilder onFrom(String fromAlias, String fromField, String toField) {
            if (fromAlias != null && fromField != null && toField != null)
                pairs.add(new FieldPair(fromAlias, fromField, Op.EQUAl, toField));
            return this;
        }

        /**
         * ON şərti: konkret alias.fromField OP join cədvəl.toField
         *
         * <pre>{@code .onFrom("t", "fkRequestId", Op.EQUAl, "id") }</pre>
         *
         * @param fromAlias  "from" cədvəlin alias-ı
         * @param fromField  həmin cədvəldəki sahə adı
         * @param op         müqayisə operatoru (EQUAl, NOT_EQUAL, GREATER_THAN, ...)
         * @param toField    join cədvəlindəki sahə adı
         */
        public JoinBuilder onFrom(String fromAlias, String fromField, Op op, String toField) {
            if (fromAlias != null && fromField != null && op != null && toField != null)
                pairs.add(new FieldPair(fromAlias, fromField, op, toField));
            return this;
        }

        /**
         * JOIN ON-a əlavə dəyər şərti: join cədvəli.field OP value
         *
         * <pre>{@code .andOn("status", Op.EQUAl, "A") }</pre>
         *
         * @param field join cədvəlindəki sahə adı
         * @param op    filter əməliyyatı
         * @param value null olduqda atlanır
         */
        public JoinBuilder andOn(String field, Op op, Object value) {
            if (field != null && op != null && value != null)
                extras.add(new JoinFilterRow(field, op, value));
            return this;
        }

        /** Builder-i tamamlayır, {@link JooqQuery}-yə qayıdır. */
        public JooqQuery<T> done() {
            parent.extJoins.add(new ExtJoinRow(type, entity, joinAlias,
                    new ArrayList<>(pairs), new ArrayList<>(extras)));
            return parent;
        }
    }

    /**
     * LEFT JOIN — generated table ilə, tip-təhlükəli ON şərti.
     *
     * <pre>{@code
     *   .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
     * }</pre>
     */
    public JooqQuery<T> leftJoin(Table<?> table, String alias, Condition on) {
        if (alias != null) joinTableRegistry.put(alias, table);
        rawJoins.add(new RawJoinRow(table.as(alias), JoinType.LEFT_OUTER_JOIN, on));
        return this;
    }

    /**
     * INNER JOIN — generated table ilə, tip-təhlükəli ON şərti.
     *
     * <pre>{@code
     *   .innerJoin(ROLES, "r", USERS.ROLE_ID.eq(ROLES.ID))
     * }</pre>
     */
    public JooqQuery<T> innerJoin(Table<?> table, String alias, Condition on) {
        if (alias != null) joinTableRegistry.put(alias, table);
        rawJoins.add(new RawJoinRow(table.as(alias), JoinType.JOIN, on));
        return this;
    }

    /**
     * LEFT JOIN — başqa bir {@link SelectTable} ilə, raw jOOQ ON şərti.
     */
    public JooqQuery<T> leftJoin(SelectTable subQuery, String alias, Condition on) {
        Table<?> tbl = subQuery.asTable(alias);
        joinTableRegistry.put(alias, tbl);
        rawJoins.add(new RawJoinRow(tbl, JoinType.LEFT_OUTER_JOIN, on));
        return this;
    }

    /**
     * INNER JOIN — başqa bir {@link SelectTable} ilə, raw jOOQ ON şərti.
     */
    public JooqQuery<T> innerJoin(SelectTable subQuery, String alias, Condition on) {
        Table<?> tbl = subQuery.asTable(alias);
        joinTableRegistry.put(alias, tbl);
        rawJoins.add(new RawJoinRow(tbl, JoinType.JOIN, on));
        return this;
    }

    /**
     * LEFT JOIN — başqa bir {@link SelectTable} ilə, string field adları ilə.
     *
     * <pre>{@code
     *   .leftJoin(budgetQuery, "b", "f.fkAccountId", "fkAccountId")
     *   // ON f."fkAccountId" = b."fkAccountId"
     * }</pre>
     *
     * @param fromField  ana cədvəlin sahəsi: {@code "alias.field"} və ya {@code "field"}
     * @param toField    join cədvəlinin sahəsi: sadə {@code "field"} adı
     */
    public JooqQuery<T> leftJoin(SelectTable subQuery, String alias,
                                  String fromField, String toField) {
        selectJoins.add(new SelectJoinRow("LEFT", subQuery, alias,
                List.of(new FieldPair(aliasPart(fromField), fieldPart(fromField), toField)),
                List.of()));
        return this;
    }

    /**
     * INNER JOIN — başqa bir {@link SelectTable} ilə, string field adları ilə.
     */
    public JooqQuery<T> innerJoin(SelectTable subQuery, String alias,
                                   String fromField, String toField) {
        selectJoins.add(new SelectJoinRow("INNER", subQuery, alias,
                List.of(new FieldPair(aliasPart(fromField), fieldPart(fromField), toField)),
                List.of()));
        return this;
    }

    /**
     * SelectTable JOIN builder — çoxlu ON field cütü + əlavə ON şərtləri.
     *
     * <pre>{@code
     *   .leftJoin(budgetQuery, "b")
     *       .on("f.fkAccountId", "fkAccountId")
     *       .on("f.fkCurrencyId", "fkCurrencyId")
     *       .andOn("status", Op.EQUAl, "A")
     *       .done()
     * }</pre>
     */
    public SelectJoinBuilder<T> leftJoin(SelectTable subQuery, String alias) {
        return new SelectJoinBuilder<>(this, "LEFT", subQuery, alias);
    }

    public SelectJoinBuilder<T> innerJoin(SelectTable subQuery, String alias) {
        return new SelectJoinBuilder<>(this, "INNER", subQuery, alias);
    }

    public static class SelectJoinBuilder<T> {
        private final JooqQuery<T>      parent;
        private final String             type;
        private final SelectTable        subQuery;
        private final String             alias;
        private final List<FieldPair>    pairs  = new ArrayList<>();
        private final List<JoinFilterRow> extras = new ArrayList<>();

        SelectJoinBuilder(JooqQuery<T> parent, String type,
                          SelectTable subQuery, String alias) {
            this.parent   = parent;
            this.type     = type;
            this.subQuery = subQuery;
            this.alias    = alias;
        }

        /** ON: fromAlias.fromField = joinAlias.toField */
        public SelectJoinBuilder<T> on(String fromField, String toField) {
            pairs.add(new FieldPair(aliasPart(fromField), fieldPart(fromField), toField));
            return this;
        }

        /** ON əlavə şərt: join cədvəlinin field-i OP value */
        public SelectJoinBuilder<T> andOn(String field, Op op, Object value) {
            if (field != null && op != null && value != null)
                extras.add(new JoinFilterRow(field, op, value));
            return this;
        }

        public JooqQuery<T> done() {
            parent.selectJoins.add(new SelectJoinRow(type, subQuery, alias,
                    new ArrayList<>(pairs), new ArrayList<>(extras)));
            return parent;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  WHERE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Dinamik filter — null / boş / boş kolleksiya olduqda <b>atlanır</b>.
     *
     * <ul>
     *   <li><b>Entity mode</b>: sahə adı {@code "alias.field"} formatında, köhnə davranış.</li>
     *   <li><b>Generated mode</b>: sahə adı (camelCase/snake_case, prefiks atılır)
     *       {@code generatedTable.field()} ilə həll edilir, {@link az.mbm.jooqsqlgenerate.strategy.FilterStrategies}
     *       tətbiq olunur — tip-təhlükəli jOOQ {@link Condition} yaranır.</li>
     * </ul>
     *
     * <pre>{@code
     *   .filter("status", EQUAl,  status)   // null → atlanır
     *   .filter("roleId", IN,     roleIds)   // boş list → atlanır
     *   .filter("firstName", LIKE, "Ali")    // generated mode: USERS.FIRST_NAME
     * }</pre>
     */
    public JooqQuery<T> filter(String field, Op op, Object value) {
        if (field == null || field.isBlank() || op == null || value == null) return this;
        if (value instanceof String s && s.isBlank()) return this;

        // BETWEEN: "from,to" formatında hər iki tərəf null/"null"/boş olduqda atlanır
        if (op == Op.BETWEEN && value instanceof String s) {
            String[] parts = s.split(",", 2);
            if (parts.length < 2) return this;
            String from = parts[0].trim();
            String to   = parts[1].trim();
            if (from.isEmpty() || from.equalsIgnoreCase("null") ||
                to.isEmpty()   || to.equalsIgnoreCase("null")) return this;
        }

        // Generated mode — sahəni main və ya join cədvəlindən həll et (alias prefix nəzərə alınır)
        if (generatedTable != null) {
            // Rounded column yoxlaması: alias roundedAliasMap-dədirsə ROUND(field, scale) OP value
            String cleanField = fieldPart(field);
            RoundedColumnRow rounded = roundedAliasMap.get(cleanField);
            if (rounded != null) {
                Field<?> rf = resolveFieldByAlias(rounded.fieldRef());
                if (rf != null) {
                    Field<?> roundedField = DSL.round((Field<? extends Number>) rf, rounded.scale());
                    Condition c = az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                            .get(op).apply((Field<Object>) roundedField, value);
                    return rawCondition(c);
                }
            }

            Field<?> resolved = resolveFieldByAlias(field);
            if (resolved == null) return this; // sahə tapılmadı — atla
            Object cleanValue = value;
            if (value instanceof Collection<?> c) {
                List<?> clean = c.stream().filter(Objects::nonNull).collect(Collectors.toList());
                if (clean.isEmpty()) return this;
                cleanValue = clean;
            }
            Condition c = az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                    .get(op).apply((Field<Object>) resolved, cleanValue);
            return rawCondition(c);
        }

        // Entity mode — list gəldikdə IN istifadə edilir
        if (value instanceof Collection<?> c) {
            List<?> clean = c.stream().filter(Objects::nonNull).collect(Collectors.toList());
            if (clean.isEmpty()) return this;
            Op resolvedOp = switch (op) {
                case EQUAl     -> Op.IN;
                case NOT_EQUAL -> Op.NOT_IN;
                default        -> op;
            };
            filters.add(new FilterRow(field, resolvedOp, clean));
            return this;
        }
        filters.add(new FilterRow(field, op, value));
        return this;
    }

    /**
     * Global filter — {@link Filters} fluent builder ilə.
     *
     * <pre>{@code
     *   .globalFilter(
     *       Filters.of()
     *           .equal("status", "ACTIVE")
     *           .like("name", name)
     *           .greaterThan("o.amount", "100")
     *   )
     * }</pre>
     */
    public JooqQuery<T> globalFilter(Filters gf) {
        if (gf == null) return this;
        // Filters.build() → operation → {field → value} strukturu
        // birbaşa emal edirik, field-first globalFilter(Map)-ə getmir
        for (Map.Entry<String, Map<String, String>> opEntry : gf.build().entrySet()) {
            Op op = JooqManager.parseOperationPublic(opEntry.getKey());
            if (op == null || opEntry.getValue() == null) continue;
            for (Map.Entry<String, String> fe : opEntry.getValue().entrySet()) {
                String field = fe.getKey();
                String raw   = fe.getValue();
                if (field == null || field.isBlank() || raw == null) continue;
                Object value = (op == Op.IN || op == Op.NOT_IN)
                        ? Arrays.asList(raw.split(",")) : raw;
                globalFilters.add(new FiltersEntry(field, op, value));
            }
        }
        return this;
    }


    /**
     * Global filter — tək field üçün bir neçə əməliyyat.
     *
     * <p>Map-in hər entry-si: key = əməliyyat adı (String), value = dəyər.
     * {@code field} və ya {@code filters} null / boş olduqda metod atlanır.
     * Map daxilindəki null key və ya null value avtomatik atlanır.
     *
     * <pre>{@code
     *   .globalFilter("o.amount", Map.of(
     *       "greaterThan", 100,
     *       "lessThan",    500
     *   ))
     *   .globalFilter("u.status", Map.of("equal", "ACTIVE"))
     *   .globalFilter("u.name",   Map.of("like",  name))   // name null → atlanır
     * }</pre>
     *
     * @param field   sahə adı: {@code "alias.field"} və ya {@code "field"} formatında
     * @param filters əməliyyat adı → dəyər cütləri
     */
    /**
     * Global filter — tək field üçün {@code Map<String, String>} ilə.
     *
     * <p>Map-in hər entry-si: key = əməliyyat adı, value = String dəyər.
     * {@code field} və ya {@code filters} null / boş olduqda atlanır.
     * Map daxilindəki null key, null və ya boş value avtomatik atlanır.
     *
     * <pre>{@code
     *   .globalFilter("o.amount", Map.of(
     *       "greaterThan", "100",
     *       "lessThan",    "500"
     *   ))
     *   .globalFilter("u.status", Map.of("equal", "ACTIVE"))
     * }</pre>
     *
     * @param field   sahə adı: {@code "alias.field"} və ya {@code "field"} formatında
     * @param filters əməliyyat adı → String dəyər cütləri
     */
    public JooqQuery<T> globalFilter(String field, Map<String, String> filters) {
        if (field == null || field.isBlank()) return this;
        if (filters == null || filters.isEmpty()) return this;
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
            Op op = JooqManager.parseOperationPublic(e.getKey());
            if (op == null) continue;
            String raw = e.getValue();
            // BETWEEN: hər iki tərəf null/"null"/boş olduqda atlanır
            if (op == Op.BETWEEN) {
                String[] parts = raw.split(",", 2);
                if (parts.length < 2) continue;
                String from = parts[0].trim(), to = parts[1].trim();
                if (from.isEmpty() || from.equalsIgnoreCase("null") ||
                    to.isEmpty()   || to.equalsIgnoreCase("null")) continue;
            }
            Object value = (op == Op.IN || op == Op.NOT_IN)
                    ? Arrays.asList(raw.split(","))
                    : raw;
            globalFilters.add(new FiltersEntry(field, op, value));
        }
        return this;
    }

    /**
     * Global filter — field-first {@code Map<String, Map<String,String>>} strukturu.
     *
     * <p>Struktur: outer key = field adı, inner key = əməliyyat, inner value = dəyər.
     *
     * <pre>{@code
     *   // JSON: {"price": {"like": "10"}, "status": {"equal": "ACTIVE"}}
     *   .globalFilterByField(Map.of(
     *       "price",  Map.of("like",  "10"),
     *       "status", Map.of("equal", "ACTIVE")
     *   ))
     * }</pre>
     */
    public JooqQuery<T> globalFilter(Map<String, Map<String, String>> fieldMap) {
        if (fieldMap == null || fieldMap.isEmpty()) return this;
        for (Map.Entry<String, Map<String, String>> e : fieldMap.entrySet()) {
            globalFilter(e.getKey(), e.getValue());
        }
        return this;
    }

    /**
     * Field-to-field WHERE şərti — iki cədvəl sütununu Op ilə müqayisə edir.
     *
     * <pre>{@code
     *   .fieldFilter("t.fkTaskId",   Op.EQUAl,        "f.fkTaskId")
     *   .fieldFilter("t.totalPrice", Op.GREATER_THAN,  "f.totalPrice")
     *   // → WHERE t."fk_task_id" = f."fk_task_id"
     *   //     AND t."total_price" > f."total_price"
     * }</pre>
     */
    public JooqQuery<T> fieldFilter(String leftAliasAndField, Op op, String rightAliasAndField) {
        if (leftAliasAndField != null && !leftAliasAndField.isBlank()
                && op != null
                && rightAliasAndField != null && !rightAliasAndField.isBlank())
            fieldFilterEntries.add(new FieldFilterEntry(leftAliasAndField, op, rightAliasAndField));
        return this;
    }

    /** WHERE field IN (SELECT ...) subquery. */
    public JooqQuery<T> inSubQuery(String outerField, SubQueryIn sub) {
        if (outerField != null && !outerField.isBlank() && sub != null)
            subQueryInCols.add(new SubQueryInRow(List.of(outerField), sub));
        return this;
    }

    /** WHERE (f1, f2, ...) IN (SELECT ...) composite subquery. */
    public JooqQuery<T> inSubQuery(String[] outerFields, SubQueryIn sub) {
        if (outerFields != null && outerFields.length > 0 && sub != null)
            subQueryInCols.add(new SubQueryInRow(Arrays.asList(outerFields), sub));
        return this;
    }

    /** WHERE EXISTS / NOT EXISTS. */
    public JooqQuery<T> exists(ExistsSpec<?, ?> spec) {
        if (spec != null) existsSpecs.add(spec);
        return this;
    }

    /**
     * Generated field ilə filter — tip-təhlükəli.
     *
     * <pre>{@code
     *   .filter(USERS.STATUS, EQUAl, "ACTIVE")
     *   .filter(USERS.AGE,    GREATER_THAN, 18)
     * }</pre>
     */
    public <V> JooqQuery<T> filter(Field<V> field, Op op, Object value) {
        if (field == null || op == null || value == null) return this;
        Condition c = az.mbm.jooqsqlgenerate.strategy.FilterStrategies
                .get(op).apply((Field<Object>) field, value);
        return rawCondition(c);
    }

    /**
     * Generated field ilə hazır jOOQ şərt — birbaşa WHERE-ə.
     *
     * <pre>{@code
     *   .filter(USERS.STATUS.eq("ACTIVE"))
     *   .filter(USERS.AGE.gt(18).and(USERS.STATUS.ne("BANNED")))
     * }</pre>
     */
    public JooqQuery<T> filter(Condition condition) {
        return rawCondition(condition);
    }

    /** Birbaşa jOOQ {@link Condition} — WHERE-ə. */
    public JooqQuery<T> rawCondition(Condition c) {
        if (c != null) rawConditions.add(c);
        return this;
    }

    /**
     * OR qrupu filter — sadə hal: eyni orGroupAlias-lı şərtlər OR ilə birləşir.
     *
     * <pre>{@code
     *   // WHERE status = 'A' AND (actionType = 'IN' OR actionType = 'OUT')
     *   .filter("t.status", Op.EQUAl, "A")
     *   .orFilter("myOr", "t.actionType", Op.EQUAl, "IN")
     *   .orFilter("myOr", "t.actionType", Op.EQUAl, "OUT")
     * }</pre>
     */
    public JooqQuery<T> orFilter(String orGroupAlias, String aliasAndField, Op op, Object value) {
        if (orGroupAlias != null && !orGroupAlias.isBlank() && aliasAndField != null && value != null)
            orFilterEntries.add(new OrFilterEntry(orGroupAlias, orGroupAlias, aliasAndField, op, value));
        return this;
    }

    /**
     * OR qrupu filter — mürəkkəb hal: (andGroup1 OR andGroup2).
     *
     * <pre>{@code
     *   // WHERE (field1='y' AND field2='z') OR (field3='a' AND field4='b')
     *   .orFilter("myOr", "andGroup1", "t.field1", Op.EQUAl, "y")
     *   .orFilter("myOr", "andGroup1", "t.field2", Op.EQUAl, "z")
     *   .orFilter("myOr", "andGroup2", "t.field3", Op.EQUAl, "a")
     *   .orFilter("myOr", "andGroup2", "t.field4", Op.EQUAl, "b")
     * }</pre>
     */
    public JooqQuery<T> orFilter(String orGroupAlias, String andGroupAlias, String aliasAndField, Op op, Object value) {
        if (orGroupAlias != null && !orGroupAlias.isBlank()
                && andGroupAlias != null && !andGroupAlias.isBlank()
                && aliasAndField != null && value != null)
            orFilterEntries.add(new OrFilterEntry(orGroupAlias, andGroupAlias, aliasAndField, op, value));
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  GROUP BY / AGGREGATE / HAVING
    // ════════════════════════════════════════════════════════════════════

    /**
     * GROUP BY sahələri (hər iki mode dəstəklənir).
     *
     * <ul>
     *   <li><b>Entity mode</b>: {@code "alias.field"} formatında string.</li>
     *   <li><b>Generated mode</b>: camelCase/snake_case adı {@code generatedTable.field()} ilə
     *       həll edilir, birbaşa {@link Field} kimi saxlanılır.</li>
     * </ul>
     */
    public JooqQuery<T> groupBy(String... fields) {
        if (fields == null) return this;
        if (generatedTable != null) {
            for (String f : fields) {
                Field<?> resolved = resolveFieldByAlias(f);
                if (resolved != null) rawGroupByFields.add(resolved);
            }
        } else {
            groupByFields.addAll(Arrays.asList(fields));
        }
        return this;
    }

    /** GROUP BY sahələri — dinamik {@code List<String>} ilə (hər iki mode dəstəklənir). */
    public JooqQuery<T> groupBy(List<String> fields) {
        if (fields == null) return this;
        if (generatedTable != null) {
            for (String f : fields) {
                Field<?> resolved = resolveFieldByAlias(f);
                if (resolved != null) rawGroupByFields.add(resolved);
            }
        } else {
            groupByFields.addAll(fields);
        }
        return this;
    }

    /**
     * GROUP BY — generated {@link Field} varargs ilə.
     *
     * <pre>{@code
     *   .groupBy(USERS.DEPARTMENT, USERS.STATUS)
     * }</pre>
     */
    public JooqQuery<T> groupBy(Field<?>... fields) {
        if (fields != null) rawGroupByFields.addAll(Arrays.asList(fields));
        return this;
    }

    /**
     * GROUP BY — dinamik {@code List<Field<?>>} ilə.
     *
     * <pre>{@code
     *   List<Field<?>> groups = request.getGroupFields();
     *   JooqQuery.from(USERS, "u").groupByFields(groups)...
     * }</pre>
     */
    public JooqQuery<T> groupByFields(List<? extends Field<?>> fields) {
        if (fields != null) rawGroupByFields.addAll(fields);
        return this;
    }

    /**
     * SUM / COUNT / AVG / MIN / MAX aqreqat.
     *
     * <p>HAVING üçün ayrıca {@link #havingFilter(String, Map)} istifadə edin.
     *
     * <pre>{@code
     *   .agg(SUM, "t.totalPrice", "totalPrice", null, "DESC")
     * }</pre>
     */
    public JooqQuery<T> agg(Agg fn, String field, String alias,
                            Integer round, String orderDir) {
        if (fn != null && field != null && alias != null)
            // alias-da "t.totalPrice" kimi prefix gəlsə yalnız "totalPrice" saxlanır
            aggRows.add(new AggRow(fn, field, fieldPart(alias), round,
                                   orderDir, MathOp.NONOPERATION, null, null));
        return this;
    }

    /** SUM / COUNT / AVG / MIN / MAX — yalnız əsas parametrlər. */
    public JooqQuery<T> agg(Agg fn, String field, String alias) {
        return agg(fn, field, alias, null, null);
    }

    /** SUM / COUNT / AVG / MIN / MAX — yuvarlama ilə. */
    public JooqQuery<T> agg(Agg fn, String field, String alias, Integer round) {
        return agg(fn, field, alias, round, null);
    }

    /**
     * Riyazi ifadəli aqreqat: SUM(f1 * f2).
     *
     * <p>HAVING üçün ayrıca {@link #havingFilter(String, Map)} istifadə edin.
     */
    public JooqQuery<T> aggWithMath(Agg fn,
                                    String field, MathOp mathOp, String mathField,
                                    String alias, Integer round, String orderDir) {
        if (fn != null && field != null && alias != null)
            aggRows.add(new AggRow(fn, field, fieldPart(alias), round,
                                   orderDir, mathOp, mathField, null));
        return this;
    }

    /** Riyazi ifadəli aqreqat — yalnız əsas parametrlər. */
    public JooqQuery<T> aggWithMath(Agg fn,
                                    String field, MathOp mathOp, String mathField,
                                    String alias) {
        return aggWithMath(fn, field, mathOp, mathField, alias, null, null);
    }

    /** Riyazi ifadəli aqreqat — yuvarlama ilə. */
    public JooqQuery<T> aggWithMath(Agg fn,
                                    String field, MathOp mathOp, String mathField,
                                    String alias, Integer round) {
        return aggWithMath(fn, field, mathOp, mathField, alias, round, null);
    }

    /**
     * ComputedField üzərindəki aqreqat: SUM((price * qty) - discount).
     *
     * <p>HAVING üçün ayrıca {@link #havingFilter(String, Map)} istifadə edin.
     */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr,
                                      String alias, Integer round, String orderDir) {
        if (fn != null && expr != null && alias != null)
            aggRows.add(new AggRow(fn, null, fieldPart(alias), round,
                                   orderDir, null, null, expr));
        return this;
    }

    /** ComputedField üzərindəki aqreqat — yalnız əsas parametrlər. */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr, String alias) {
        return aggOnComputed(fn, expr, alias, null, null);
    }

    /** ComputedField üzərindəki aqreqat — yuvarlama ilə. */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr,
                                      String alias, Integer round) {
        return aggOnComputed(fn, expr, alias, round, null);
    }

    /** HAVING EXISTS / NOT EXISTS (GROUP BY ilə birlikdə). */
    public JooqQuery<T> havingExists(ExistsSpec<?, ?> spec) {
        if (spec != null) havingExistsSpecs.add(spec);
        return this;
    }

    /** Birbaşa jOOQ {@link Condition} — HAVING-ə. */
    public JooqQuery<T> rawHaving(Condition c) {
        if (c != null) rawHavings.add(c);
        return this;
    }

    /**
     * HAVING filter — GROUP BY sahəsi üçün əməliyyat + dəyər ilə.
     *
     * <p>Aqreqat funksiyasız, GROUP BY-da olan sahəyə birbaşa HAVING şərti tətbiq edir.
     * Null dəyər və ya boş string olduqda atlanır.
     *
     * <pre>{@code
     *   .havingFilter("t.operationType", Op.EQUAl,    "SELL")
     *   .havingFilter("t.status",        Op.NOT_EQUAL, "PASSIVE")
     *   .havingFilter("t.amount",        Op.GREATER_THAN, 100)
     * }</pre>
     *
     * @param field sahə adı: {@code "alias.field"} və ya {@code "field"} formatında
     * @param op    filter əməliyyatı
     * @param value filter dəyəri (null / boş string → atlanır)
     */
    public JooqQuery<T> havingFilter(String field, Op op, Object value) {
        if (field == null || field.isBlank() || op == null || value == null) return this;
        if (value instanceof String s && s.isBlank()) return this;
        @SuppressWarnings("unchecked")
        Field<Object> f = (Field<Object>) DSL.field(DSL.name(fieldPart(field)));
        Condition c = az.mbm.jooqsqlgenerate.strategy.FilterStrategies.get(op).apply(f, value);
        rawHavings.add(c);
        return this;
    }

    /**
     * HAVING filter — aggregat alias üçün {@code Map<String, String>} ilə.
     *
     * <p>field: aggregat alias adı (məs. "totalPrice"),
     * map: əməliyyat adı → dəyər (məs. {"greaterThan": "1000"}).
     * Null, boş map və boş dəyərlər atlanır.
     *
     * <pre>{@code
     *   .havingFilter("totalPrice", Map.of("greaterThan", "1000"))
     *   .havingFilter("totalPrice", Map.of("between",     "100,5000"))
     * }</pre>
     */
    /**
     * HAVING filter — aggregat alias üçün {@code Map<String, String>} ilə.
     *
     * <p><b>Entity mode</b>: alias-a uyğun aqreqat funksiyası ({@code AggRow}) tapılır
     * və {@code AggregateBuilder.step.having(op, val)} vasitəsilə
     * {@code HAVING SUM(total_price) > 1000} kimi düzgün SQL yaranır.<br>
     * <b>Generated mode</b>: alias birbaşa HAVING-ə yazılır —
     * {@code HAVING "totalPrice" > 1000} (PostgreSQL/MySQL-də işləyir).
     *
     * <pre>{@code
     *   .havingFilter("totalPrice", Map.of("greaterThan", "1000"))
     *   .havingFilter("cnt",        Map.of("between",     "5,50"))
     * }</pre>
     */
    public JooqQuery<T> havingFilter(String field, Map<String, String> filters) {
        if (field == null || field.isBlank()) return this;
        if (filters == null || filters.isEmpty()) return this;
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
            Op op = JooqManager.parseOperationPublic(e.getKey());
            if (op == null) continue;
            Object value = (op == Op.IN || op == Op.NOT_IN)
                    ? Arrays.asList(e.getValue().split(","))
                    : e.getValue();

            if (generatedTable != null) {
                // Generated mode — alias referansı ilə HAVING (PostgreSQL/MySQL dəstəkləyir)
                @SuppressWarnings("unchecked")
                Field<Object> f = (Field<Object>) DSL.field(DSL.name(fieldPart(field)));
                rawHavings.add(az.mbm.jooqsqlgenerate.strategy.FilterStrategies.get(op).apply(f, value));
            } else {
                // Entity mode — AggregateBuilder.step.having() vasitəsilə işlər (execute()-də)
                havingFilterRows.add(new FilterRow(fieldPart(field), op, value));
            }
        }
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CASE WHEN
    // ════════════════════════════════════════════════════════════════════

    /** Sadə CASE WHEN ... THEN ... ELSE ... END AS alias. */
    public JooqQuery<T> caseWhen(String field, Op op,
                                 Object when, Object then, Object els, String alias) {
        if (field != null && op != null && when != null && alias != null)
            caseRows.add(new CaseRow(field, op, when, then, els, alias));
        return this;
    }

    /** Mürəkkəb çox şərtli CASE WHEN. */
    public JooqQuery<T> caseWhen(CaseBuilder<?> cb) {
        if (cb != null) caseBuilders.add(cb);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  ORDER BY / PAGE
    // ════════════════════════════════════════════════════════════════════

    /**
     * ORDER BY field ASC/DESC (hər iki mode dəstəklənir).
     *
     * <ul>
     *   <li><b>Entity mode</b>: {@code "alias.field"} formatında string saxlanılır.</li>
     *   <li><b>Generated mode</b>: sahə {@code generatedTable.field()} ilə həll edilir,
     *       birbaşa {@link SortField} kimi saxlanılır.</li>
     * </ul>
     */
    public JooqQuery<T> orderBy(String field, String direction) {
        if (field == null || field.isBlank()) return this;
        if (generatedTable != null) {
            Field<?> resolved = resolveFromTable(generatedTable, fieldPart(field));
            if (resolved != null)
                rawOrderFields.add("DESC".equalsIgnoreCase(direction) ? resolved.desc() : resolved.asc());
        } else {
            sortRows.add(new SortRow(field, direction));
        }
        return this;
    }

    /**
     * ORDER BY — birləşmiş string format: {@code "alias.field dir, alias.field dir, ..."}.
     *
     * <p>REST endpoint-dən gələn {@code sort} parametrini birbaşa ötürmək üçün əlverişlidir.
     * İstiqamət yazılmadıqda ASC qəbul edilir. Boş/null hissələr atlanır.
     *
     * <pre>{@code
     *   .orderBy("t.insertDate desc, f.createdDate")
     *   .orderBy("u.name asc, u.createdAt desc")
     * }</pre>
     */
    public JooqQuery<T> orderBy(String sortExpression) {
        if (sortExpression == null || sortExpression.isBlank()) return this;
        for (String part : sortExpression.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length == 0 || tokens[0].isBlank()) continue;
            String field = tokens[0];
            String dir   = tokens.length >= 2 ? tokens[1] : "ASC";
            orderBy(field, dir);
        }
        return this;
    }

    /**
     * ORDER BY — dinamik {@link Map} ilə: key=field, value=istiqamət.
     *
     * <pre>{@code
     *   Map<String, String> sorts = Map.of(
     *       "u.createdAt", "DESC",
     *       "u.name",      "ASC"
     *   );
     *   JooqQuery.from(User.class, "u").orderBy(sorts)...
     * }</pre>
     */
    public JooqQuery<T> orderBy(Map<String, String> sorts) {
        if (sorts != null)
            sorts.forEach((field, dir) -> orderBy(field, dir));
        return this;
    }

    /**
     * ORDER BY — {@code List<Map<String, String>>} ilə.
     *
     * <p>Hər map-in tək entry-si: key = field adı, value = "ASC" və ya "DESC".
     * Sıralama siyahıdakı ardıcıllığa görə tətbiq olunur.
     *
     * <pre>{@code
     *   .orderBy(List.of(
     *       Map.of("u.createdAt", "DESC"),
     *       Map.of("u.name",      "ASC")
     *   ))
     * }</pre>
     */
    public JooqQuery<T> orderBy(List<Map<String, String>> sorts) {
        if (sorts == null) return this;
        for (Map<String, String> map : sorts)
            if (map != null) map.forEach((field, dir) -> orderBy(field, dir));
        return this;
    }

    /**
     * ORDER BY — generated field ilə, tip-təhlükəli.
     *
     * <pre>{@code
     *   .orderBy(USERS.CREATED_AT.desc(), USERS.NAME.asc())
     * }</pre>
     */
    public JooqQuery<T> orderBy(SortField<?>... fields) {
        if (fields != null) rawOrderFields.addAll(Arrays.asList(fields));
        return this;
    }

    /**
     * ORDER BY — dinamik {@code List<SortField<?>>} ilə.
     *
     * <pre>{@code
     *   List<SortField<?>> sorts = List.of(USERS.CREATED_AT.desc(), USERS.NAME.asc());
     *   .orderByFields(sorts)
     * }</pre>
     */
    public JooqQuery<T> orderByFields(List<? extends SortField<?>> fields) {
        if (fields != null) rawOrderFields.addAll(fields);
        return this;
    }

    /** ORDER BY birbaşa jOOQ {@link SortField}. */
    public JooqQuery<T> rawOrderBy(SortField<?> sf) {
        if (sf != null) rawOrderFields.add(sf);
        return this;
    }

    /** Səhifələmə. page — 0-dan başlayır. */
    public JooqQuery<T> page(int page, int size) {
        this.pageNumber = page;
        this.pageSize   = size;
        this.paginate   = true;
        return this;
    }

    /** Səhifələməni söndürür — bütün nəticəni qaytarır, COUNT işləmir. */
    public JooqQuery<T> noPagination() {
        this.paginate = false;
        return this;
    }

    /** Pagination olmadan yalnız COUNT-u aktiv edir. */
    public JooqQuery<T> withCount() {
        this.paginate = false;  // LIMIT/OFFSET yox
        this.countOnly = true;  // amma COUNT işlər
        return this;
    }

    /** Pagination aktiv olur (LIMIT/OFFSET işləyir), lakin COUNT sorğusu atlanır. */
    public JooqQuery<T> skipCount() {
        this.skipCount = true;
        return this;
    }

    /** Yalnız COUNT sorğusu icra edilir, əsas data sorğusu işləmir. result = boş siyahı. */
    public JooqQuery<T> onlyCount() {
        this.onlyCount = true;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  EXECUTE
    // ════════════════════════════════════════════════════════════════════

    /**
     * SQL sorğusunu icra edir.
     *
     * <pre>{@code
     *   SelectTable result = JooqQuery.from(User.class, "u")
     *       .select("u.id", "u.name")
     *       .filter("status", EQUAl, "ACTIVE")
     *       .page(0, 20)
     *       .execute(dsl);
     * }</pre>
     */
    public SelectTable execute(DSLContext dsl) {
        // Generated mode — EntityTable bypass, birbaşa jOOQ ilə
        if (generatedTable != null) return executeGenerated(dsl);

        SelectQueryBuilder builder = SelectQueryBuilder.from(entity, alias);

        // SELECT
        if (!columns.isEmpty())
            builder.columns(columns.toArray(new String[0]));
        for (ComputedRow cr : computedCols)
            builder.computedColumn(cr.alias(), cr.ta1(), cr.op(), cr.f1(), cr.ta2(), cr.f2());
        for (ComputedFieldEntry entry : computedFields)
            builder.computedColumn(entry.cf(), entry.filterOp(), entry.filterValue());
        for (CoalesceRow cr : coalesceCols)
            builder.coalesce(cr.alias(), cr.def(), cr.fields());
        for (ConcatRow cc : concatCols)
            builder.concat(cc.alias(), cc.separator(), cc.items());
        for (SubSelectBuilder sub : subSelectCols)
            builder.subSelect(sub);
        for (Field<?> rf : rawSelectFields)
            builder.rawSelectField(rf);
        for (SelectAsRow sa : selectAsRows)
            builder.selectAs(sa.aliasAndField(), sa.outputAlias());
        if (distinct) builder.distinct();

        // JOIN — tək field cütü (köhnə üsul)
        for (JoinRow jr : joins) {
            if ("LEFT".equals(jr.type()))
                builder.leftJoin(jr.entity(), jr.alias()).on(jr.fromField()).equalsField(jr.toField());
            else
                builder.innerJoin(jr.entity(), jr.alias()).on(jr.fromField()).equalsField(jr.toField());
        }

        // JOIN — çoxlu field cütü + əlavə ON şərtləri (yeni üsul)
        // entityClassMap: alias → entity class (from + join cədvəlləri üçün)
        Map<String, Class<?>> entityClassMap = new LinkedHashMap<>();
        entityClassMap.put(alias, entity);
        for (JoinRow jr : joins) entityClassMap.put(jr.alias(), jr.entity());

        for (ExtJoinRow jr : extJoins) {
            EntityTable<?> toTable = new EntityTable<>(jr.entity(), jr.alias());
            entityClassMap.put(jr.alias(), jr.entity());

            Condition on = null;

            // Field cütlərindən ON şərti
            for (FieldPair fp : jr.pairs()) {
                Class<?> fromClass = entityClassMap.getOrDefault(fp.fromAlias(), entity);
                EntityTable<?> fromTable = new EntityTable<>(fromClass, fp.fromAlias());
                @SuppressWarnings("unchecked")
                Field<Object> fromField = (Field<Object>) fromTable.getField(fp.fromField());
                @SuppressWarnings("unchecked")
                Field<Object> toField   = (Field<Object>) toTable.getField(fp.toField());
                Condition c = applyFieldOp(fp.op(), fromField, toField);
                on = (on == null) ? c : on.and(c);
            }

            // Əlavə value şərtlər (t1.status = 'A' kimi)
            for (JoinFilterRow extra : jr.extras()) {
                @SuppressWarnings("unchecked")
                Field<Object> f = (Field<Object>) toTable.getField(extra.field());
                Condition c = FilterStrategies.get(extra.op()).apply(f, extra.value());
                on = (on == null) ? c : on.and(c);
            }

            if (on == null) on = DSL.trueCondition();

            if ("LEFT".equals(jr.type()))
                builder.leftJoinWithCondition(jr.entity(), jr.alias(), on);
            else
                builder.innerJoinWithCondition(jr.entity(), jr.alias(), on);
        }

        // WHERE — normal filterlər
        Set<String> aggAliases = new HashSet<>();
        for (AggRow ar : aggRows) aggAliases.add(ar.alias());
        Set<String> computedAliases = new HashSet<>();
        for (ComputedRow cr  : computedCols)   computedAliases.add(cr.alias());
        for (ComputedFieldEntry entry : computedFields) if (entry.cf().getAlias() != null) computedAliases.add(entry.cf().getAlias());
        boolean hasGroupBy = !groupByFields.isEmpty() || !aggRows.isEmpty();

        List<FilterRow> whereFilters          = new ArrayList<>();
        Map<String, FilterRow> havingMap      = new LinkedHashMap<>();
        List<FilterRow> computedWhereFilters  = new ArrayList<>();
        List<FilterRow> computedHavingFilters = new ArrayList<>();
        List<FilterRow> roundedWhereFilters   = new ArrayList<>();  // ROUND(field,scale) filterlər

        // havingFilter() ilə birbaşa əlavə edilən HAVING şərtləri
        // → bunlar AggregateBuilder.step.having(op, val) vasitəsilə işlənir
        //   beləliklə HAVING SUM(field) > val kimi düzgün SQL yaranır
        for (FilterRow fr : havingFilterRows) {
            havingMap.put(fr.field(), fr);
        }

        for (FilterRow fr : filters) {
            // Alias prefix varsa ("t1.status") yalnız sahə adı ilə yoxla
            String fieldKey = fieldPart(fr.field());
            if (roundedAliasMap.containsKey(fieldKey))
                // ROUND(field, scale) filter — ayrıca işlənir
                roundedWhereFilters.add(new FilterRow(fieldKey, fr.op(), fr.value()));
            else if (aggAliases.contains(fieldKey))
                havingMap.put(fieldKey, new FilterRow(fieldKey, fr.op(), fr.value()));
            else if (computedAliases.contains(fieldKey))
                (hasGroupBy ? computedHavingFilters : computedWhereFilters)
                        .add(new FilterRow(fieldKey, fr.op(), fr.value()));
            else
                whereFilters.add(fr); // alias ilə saxlanılır → aşağıda həll edilir
        }

        if (!whereFilters.isEmpty()) {
            Filter filter = Filter.of();
            for (FilterRow fr : whereFilters) {
                if (fr.field().contains(".")) {
                    // Aliased sahə ("t1.status", "t.fkProductId") →
                    // globalWhereFilter alias-ı həll edir (t1 → Product EntityTable)
                    builder.globalWhereFilter(fr.field(), fr.op(), fr.value());
                } else {
                    // Alias yoxdur → main table üzərindən Filter (köhnə davranış)
                    applyFilter(filter, fr);
                }
            }
            Specification spec = filter.build();
            if (spec != null) builder.where(spec);
        }

        for (SubQueryInRow sir : subQueryInCols) builder.inSubQuery(sir.outerFields(), sir.sub());

        // globalFilter(Map) yolu ilə gələn filterlər — aggAlias olduqda HAVING-ə yönləndir,
        // əks halda normalda WHERE-ə göndər.
        // Qeyd: addFilter(String, Op, Object) yolu artıq aggAliases yoxlayır (yuxarıda),
        //       lakin globalFilter(Map) yolu bunu keçirirdi — bu fix bunu düzəldir.
        for (FiltersEntry gf : globalFilters) {
            String fieldKey = fieldPart(gf.aliasAndField());
            if (aggAliases.contains(fieldKey)) {
                // Aqreqat alias → HAVING-ə əlavə et (step.having() ilə bağlanacaq)
                havingMap.put(fieldKey, new FilterRow(fieldKey, gf.op(), gf.value()));
            } else {
                builder.globalWhereFilter(gf.aliasAndField(), gf.op(), gf.value());
            }
        }

        for (FieldFilterEntry ff : fieldFilterEntries) builder.fieldFilter(ff.leftAliasAndField(), ff.op(), ff.rightAliasAndField());
        for (Condition rc : rawConditions) builder.rawCondition(rc);
        for (OrFilterEntry e : orFilterEntries) builder.orFilter(e.orGroup(), e.andGroup(), e.aliasAndField(), e.op(), e.value());
        for (ExistsSpec<?, ?> es : existsSpecs) builder.where((Specification) es);

        // ComputedField alias filter → globalWhereFilter vasitəsilə ifadə genişləndirilir
        // SelectQueryBuilder.buildWhereCondition() alias-ı tanıyır, WHERE-ə expression yazır
        for (FilterRow fr : computedWhereFilters) builder.globalWhereFilter(fr.field(), fr.op(), fr.value());

        // ─── Rounded columns — entity mode ──────────────────────────────────
        // 1) SELECT-ə ROUND(field, scale) AS alias sütunları əlavə et
        // 2) Filter varsa WHERE ROUND(field, scale) OP value kimi raw condition yaz
        if (!roundedColumns.isEmpty() || !roundedWhereFilters.isEmpty()) {
            // Bütün mövcud cədvəllərin (main + join) EntityTable-larını hazırla
            Map<String, EntityTable<?>> roundedTableMap = new LinkedHashMap<>();
            EntityTable<?> mainEt = new EntityTable<>(entity, alias);
            roundedTableMap.put(alias, mainEt);
            for (JoinRow jr : joins)
                roundedTableMap.put(jr.alias(), new EntityTable<>(jr.entity(), jr.alias()));
            for (ExtJoinRow jr : extJoins)
                roundedTableMap.put(jr.alias(), new EntityTable<>(jr.entity(), jr.alias()));

            // SELECT: ROUND(field, scale) AS alias
            for (RoundedColumnRow rc : roundedColumns) {
                String tAlias    = aliasPart(rc.fieldRef());
                String fieldName = fieldPart(rc.fieldRef());
                EntityTable<?> targetEt = tAlias.isBlank()
                        ? mainEt
                        : roundedTableMap.getOrDefault(tAlias, mainEt);
                Field<?> f = targetEt.getField(fieldName);
                if (f != null) {
                    builder.rawSelectField(
                            DSL.round((Field<? extends Number>) f, rc.scale()).as(rc.alias()));
                }
            }

            // WHERE: ROUND(field, scale) OP value
            for (FilterRow fr : roundedWhereFilters) {
                RoundedColumnRow rc = roundedAliasMap.get(fr.field());
                if (rc == null) continue;
                String tAlias    = aliasPart(rc.fieldRef());
                String fieldName = fieldPart(rc.fieldRef());
                EntityTable<?> targetEt = tAlias.isBlank()
                        ? mainEt
                        : roundedTableMap.getOrDefault(tAlias, mainEt);
                Field<?> f = targetEt.getField(fieldName);
                if (f != null) {
                    Field<?> roundedF = DSL.round((Field<? extends Number>) f, rc.scale());
                    Condition c = FilterStrategies.get(fr.op())
                            .apply((Field<Object>) roundedF, fr.value());
                    builder.rawCondition(c);
                }
            }
        }

        // HAVING
        for (FilterRow fr : computedHavingFilters) { Condition c = aliasCondition(fr); if (c != null) builder.having(c); }
        for (ExistsSpec<?, ?> es : havingExistsSpecs) builder.having((Specification) es);
        for (Condition rh : rawHavings) builder.rawHaving(rh);

        // CASE
        for (CaseRow cr : caseRows)
            builder.caseColumn(CaseBuilder.when(cr.field(), cr.op(), cr.when())
                                          .then(cr.then()).otherwise(cr.els()).as(cr.alias()));
        for (CaseBuilder<?> cb : caseBuilders) builder.caseColumn((CaseBuilder) cb);

        // GROUP BY + AGG
        if (!groupByFields.isEmpty() || !aggRows.isEmpty()) {
            AggregateBuilder aggBuilder = AggregateBuilder.groupBy(groupByFields.toArray(new String[0]));
            for (AggRow ar : aggRows) {
                AggregateBuilder.AggStep step;
                if (ar.expr() != null) {
                    step = switch (ar.fn()) {
                        case SUM   -> aggBuilder.sumOf(ar.expr());
                        case COUNT -> aggBuilder.countOf(ar.expr());
                        case AVG   -> aggBuilder.avgOf(ar.expr());
                        case MAX   -> aggBuilder.maxOf(ar.expr());
                        case MIN   -> aggBuilder.minOf(ar.expr());
                    };
                } else {
                    step = switch (ar.fn()) {
                        case SUM   -> aggBuilder.sum(ar.field());
                        case COUNT -> aggBuilder.count(ar.field());
                        case AVG   -> aggBuilder.avg(ar.field());
                        case MAX   -> aggBuilder.max(ar.field());
                        case MIN   -> aggBuilder.min(ar.field());
                    };
                }
                step.as(ar.alias());
                if (ar.round() != null) step.round(ar.round());
                // HAVING — addHavingFilter(alias, Map) ilə verilir
                if (havingMap.containsKey(ar.alias())) {
                    FilterRow hr = havingMap.get(ar.alias());
                    step.having(hr.op(), hr.value());
                }
                if ("DESC".equalsIgnoreCase(ar.orderDir())) step.orderDesc();
                if ("ASC".equalsIgnoreCase(ar.orderDir()))  step.orderAsc();
                step.done();
            }
            builder.aggregate(aggBuilder);
        }

        // ORDER BY
        for (SortRow sr : sortRows) {
            if ("DESC".equalsIgnoreCase(sr.dir())) builder.orderByDesc(sr.field());
            else                                   builder.orderByAsc(sr.field());
        }
        for (SortField<?> sf : rawOrderFields) builder.rawOrderBy(sf);

        // PAGINATION
        if (paginate)        builder.page(pageNumber, pageSize);
        else if (countOnly)  builder.withCount();
        else                 builder.noPagination();
        if (skipCount)       builder.skipCount();
        if (onlyCount)       builder.onlyCount();

        return builder.build(dsl);
    }

    // ─── Fetch yardımcıları ──────────────────────────────────────────────

    /**
     * Execute edib {@code List<Map<String, Object>>} qaytarır.
     *
     * <pre>{@code
     *   List<Map<String, Object>> rows = JooqQuery.from(User.class, "u")
     *       .select("u.id", "u.name")
     *       .filter("status", EQUAl, "ACTIVE")
     *       .fetchMaps(dsl);
     * }</pre>
     */
    // ════════════════════════════════════════════════════════════════════
    //  GENERATED MODE — EntityTable bypass, birbaşa jOOQ DSL
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generated jOOQ {@link Table} ilə sorğu qurur.
     * Reflection yoxdur, EntityTable yoxdur — sırf jOOQ Field-ləri ilə işləyir.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private SelectTable executeGenerated(DSLContext dsl) {

        // FROM — alias ilə
        Table<?> mainTable = generatedTable.as(alias);

        // ─── Bütün JOIN cədvəllərini joinTableRegistry-yə əvvəlcədən yaz ──────
        // filter / groupBy / agg / orderBy metodları bu registry-dən field resolve edir
        for (JoinRow jr : joins) {
            EntityTable<?> et = new EntityTable<>(jr.entity(), jr.alias());
            joinTableRegistry.put(jr.alias(), et.getTable());
        }
        for (ExtJoinRow jr : extJoins) {
            EntityTable<?> et = new EntityTable<>(jr.entity(), jr.alias());
            joinTableRegistry.put(jr.alias(), et.getTable());
        }
        for (SelectJoinRow jr : selectJoins) {
            joinTableRegistry.put(jr.alias(), jr.subQuery().asTable(jr.alias()));
        }

        // ─── SELECT — agg sütunlar + alias→expr xəritəsi (HAVING üçün lazım) ──
        List<SelectFieldOrAsterisk> selectList = new ArrayList<>(rawSelectFields);
        Map<String, Field<?>> aggExprByAlias   = new LinkedHashMap<>();

        for (AggRow ar : aggRows) {
            if (ar.field() == null || ar.fn() == null) continue;

            Field<?> baseField = resolveFieldByAlias(ar.field());
            if (baseField == null) baseField = DSL.field(DSL.name(fieldPart(ar.field())));

            Field<?> operand = baseField;
            if (ar.mathOp() != null && ar.mathOp() != MathOp.NONOPERATION
                    && ar.mathField() != null) {
                Field<?> mathF = resolveFieldByAlias(ar.mathField());
                if (mathF == null) mathF = DSL.field(DSL.name(fieldPart(ar.mathField())));
                Field<? extends Number> numBase = (Field<? extends Number>) baseField;
                Field<? extends Number> numMath = (Field<? extends Number>) mathF;
                operand = switch (ar.mathOp()) {
                    case ADD      -> numBase.add(numMath);
                    case SUBTRACT -> numBase.subtract(numMath);
                    case MULTIPLY -> numBase.mul(numMath);
                    case DIVIDE   -> numBase.div(numMath);
                    default       -> baseField;
                };
            }

            Field<? extends Number> numOp = (Field<? extends Number>) operand;
            Field<?> aggField = switch (ar.fn()) {
                case SUM   -> DSL.sum(numOp);
                case COUNT -> DSL.count(operand);
                case AVG   -> DSL.avg(numOp);
                case MAX   -> DSL.max(operand);
                case MIN   -> DSL.min(operand);
            };
            if (ar.round() != null)
                aggField = DSL.round((Field<? extends Number>) aggField, ar.round());

            aggExprByAlias.put(ar.alias(), aggField);
            selectList.add(DSL.coalesce(aggField, DSL.val(0)).as(ar.alias()));

            if ("DESC".equalsIgnoreCase(ar.orderDir()))
                rawOrderFields.add(DSL.field(DSL.name(ar.alias())).desc());
            else if ("ASC".equalsIgnoreCase(ar.orderDir()))
                rawOrderFields.add(DSL.field(DSL.name(ar.alias())).asc());
        }

        if (selectList.isEmpty()) selectList.add(DSL.asterisk());

        // ─── FROM ────────────────────────────────────────────────────────────
        SelectJoinStep<Record> query = distinct
                ? dsl.selectDistinct(selectList).from(mainTable)
                : dsl.select(selectList).from(mainTable);

        // ─── JOIN — raw (generated table ilə) ───────────────────────────────
        for (RawJoinRow jr : rawJoins) {
            query = switch (jr.type()) {
                case LEFT_OUTER_JOIN  -> query.leftJoin(jr.table()).on(jr.on());
                case RIGHT_OUTER_JOIN -> query.rightJoin(jr.table()).on(jr.on());
                default               -> query.join(jr.table()).on(jr.on());
            };
        }

        // ─── JOIN — entity class, tək field cütü ────────────────────────────
        for (JoinRow jr : joins) {
            EntityTable<?> et = new EntityTable<>(jr.entity(), jr.alias());
            Table<?> jt       = et.getTable();

            String fromAlias     = aliasPart(jr.fromField());
            String fromFieldName = fieldPart(jr.fromField());
            Table<?> fromTbl     = (fromAlias != null && !fromAlias.isBlank())
                    ? joinTableRegistry.getOrDefault(fromAlias, mainTable)
                    : mainTable;

            Field<?> fromF = resolveFromTable(fromTbl, fromFieldName);
            if (fromF == null) fromF = DSL.field(DSL.name(fromAlias, fromFieldName));
            Field<?> toF = et.getField(jr.toField());

            Condition on = ((Field<Object>) fromF).eq((Field<Object>) toF);
            query = "LEFT".equals(jr.type())
                    ? query.leftJoin(jt).on(on)
                    : query.join(jt).on(on);
        }

        // ─── JOIN — SelectTable (derived table), string field adları ilə ──────
        for (SelectJoinRow jr : selectJoins) {
            Table<?> jt = jr.subQuery().asTable(jr.alias());
            joinTableRegistry.put(jr.alias(), jt);

            Condition on = null;
            for (FieldPair fp : jr.pairs()) {
                Table<?> fromTbl = (fp.fromAlias() != null && !fp.fromAlias().isBlank())
                        ? joinTableRegistry.getOrDefault(fp.fromAlias(), mainTable)
                        : mainTable;
                Field<?> fromF = resolveFromTable(fromTbl, fp.fromField());
                if (fromF == null) fromF = DSL.field(DSL.name(fp.fromAlias(), fp.fromField()));
                Field<?> toF   = resolveFromTable(jt, fp.toField());
                if (toF == null) toF = DSL.field(DSL.name(jr.alias(), fp.toField()));
                Condition c = applyFieldOp(fp.op(), (Field<Object>) fromF, (Field<Object>) toF);
                on = (on == null) ? c : on.and(c);
            }
            for (JoinFilterRow extra : jr.extras()) {
                Field<?> f = resolveFromTable(jt, extra.field());
                if (f == null) f = DSL.field(DSL.name(jr.alias(), extra.field()));
                Condition c = FilterStrategies.get(extra.op()).apply((Field<Object>) f, extra.value());
                on = (on == null) ? c : on.and(c);
            }
            if (on == null) on = DSL.trueCondition();

            query = "LEFT".equals(jr.type())
                    ? query.leftJoin(jt).on(on)
                    : query.join(jt).on(on);
        }

        // ─── JOIN — entity class, çoxlu field cütü + əlavə şərtlər ─────────
        for (ExtJoinRow jr : extJoins) {
            EntityTable<?> toEt = new EntityTable<>(jr.entity(), jr.alias());
            Table<?> jt         = toEt.getTable();

            Condition on = null;
            for (FieldPair fp : jr.pairs()) {
                Table<?> fromTbl = joinTableRegistry.getOrDefault(fp.fromAlias(), mainTable);
                Field<?> fromF   = resolveFromTable(fromTbl, fp.fromField());
                if (fromF == null) fromF = DSL.field(DSL.name(fp.fromAlias(), fp.fromField()));
                Field<?> toF = toEt.getField(fp.toField());
                Condition c = applyFieldOp(fp.op(), (Field<Object>) fromF, (Field<Object>) toF);
                on = (on == null) ? c : on.and(c);
            }
            for (JoinFilterRow extra : jr.extras()) {
                Field<Object> f = (Field<Object>) toEt.getField(extra.field());
                Condition c = FilterStrategies.get(extra.op()).apply(f, extra.value());
                on = (on == null) ? c : on.and(c);
            }
            if (on == null) on = DSL.trueCondition();

            query = "LEFT".equals(jr.type())
                    ? query.leftJoin(jt).on(on)
                    : query.join(jt).on(on);
        }

        // ─── WHERE ───────────────────────────────────────────────────────────
        Condition where = null;
        for (Condition c : rawConditions) where = (where == null) ? c : where.and(c);
        SelectConditionStep<Record> conditioned = (where != null)
                ? query.where(where)
                : query.where(DSL.trueCondition());

        // ─── GROUP BY ────────────────────────────────────────────────────────
        SelectHavingStep<Record> grouped;
        if (!rawGroupByFields.isEmpty()) {
            grouped = conditioned.groupBy(rawGroupByFields);
        } else {
            grouped = conditioned;
        }

        // ─── HAVING — raw + havingFilterRows (agg alias → ifadə ilə) ────────
        Condition having = null;
        for (Condition c : rawHavings) having = (having == null) ? c : having.and(c);

        for (FilterRow fr : havingFilterRows) {
            Field<Object> f;
            Field<?> aggExpr = aggExprByAlias.get(fr.field());
            if (aggExpr != null) {
                f = (Field<Object>) aggExpr;              // HAVING SUM(field) > val
            } else {
                f = (Field<Object>) DSL.field(DSL.name(fr.field())); // birbaşa alias ref
            }
            Condition c = FilterStrategies.get(fr.op()).apply(f, fr.value());
            having = (having == null) ? c : having.and(c);
        }

        if (having != null) grouped = (SelectHavingStep<Record>) grouped.having(having);

        // ─── ORDER BY — sortRows ─────────────────────────────────────────────
        for (SortRow sr : sortRows) {
            Field<?> f = resolveFieldByAlias(sr.field());
            if (f == null) f = DSL.field(DSL.name(fieldPart(sr.field())));
            rawOrderFields.add("DESC".equalsIgnoreCase(sr.dir()) ? f.desc() : f.asc());
        }

        SelectSeekStepN<Record> ordered = grouped.orderBy(rawOrderFields);

        // ─── COUNT (pagination üçün) ─────────────────────────────────────────
        int rowCount = 0;
        if (paginate) {
            Record1<Integer> r = dsl.selectCount()
                    .from(mainTable)
                    .where(where != null ? where : DSL.trueCondition())
                    .fetchOne();
            rowCount = (r == null) ? 0 : r.value1();
        }

        // ─── LIMIT / OFFSET ───────────────────────────────────────────────────
        Select<Record> finalQuery = paginate
                ? ordered.limit(pageSize).offset((long) pageNumber * pageSize)
                : ordered;

        return new SelectTable(finalQuery, rowCount);
    }

    // ════════════════════════════════════════════════════════════════════
    //  FETCH YARDIMÇILARI
    // ════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> fetchMaps(DSLContext dsl) {
        return new SelectFetchJooq<>().fetchMaps(execute(dsl)).getList();
    }

    /**
     * Execute edib entity list qaytarır.
     *
     * <pre>{@code
     *   List<User> users = JooqQuery.from(User.class, "u")
     *       .filter("status", EQUAl, "ACTIVE")
     *       .fetchInto(dsl, User.class);
     * }</pre>
     */
    public <E> List<E> fetchInto(DSLContext dsl, Class<E> type) {
        return new SelectFetchJooq<E>().fetchCast(execute(dsl), type).getList();
    }

    // ─── Private yardımcılar ─────────────────────────────────────────────

    private void applyFilter(Filter filter, FilterRow fr) {
        switch (fr.op()) {
            case EQUAl                    -> filter.eq(fr.field(), fr.value());
            case NOT_EQUAL                -> filter.notEq(fr.field(), fr.value());
            case GREATER_THAN             -> filter.gt(fr.field(), fr.value());
            case GREATER_THAN_OR_EQUAL_TO -> filter.gte(fr.field(), fr.value());
            case LESS_THAN                -> filter.lt(fr.field(), fr.value());
            case LESS_THAN_OR_EQUAL_TO    -> filter.lte(fr.field(), fr.value());
            case LIKE                     -> filter.like(fr.field(), fr.value().toString());
            case START_WITH               -> filter.startWith(fr.field(), fr.value().toString());
            case END_WITH                 -> filter.endWith(fr.field(), fr.value().toString());
            case IS_EMPTY                 -> filter.isNull(fr.field());
            case IS_NOT_EMPTY             -> filter.isNotNull(fr.field());
            case IN                       -> filter.in(fr.field(),
                    fr.value() instanceof Collection<?> c ? c : List.of(fr.value()));
            case NOT_IN                   -> filter.notIn(fr.field(),
                    fr.value() instanceof Collection<?> c ? c : List.of(fr.value()));
            case BETWEEN                  -> {
                if (fr.value() instanceof Object[] arr && arr.length == 2)
                    filter.between(fr.field(), arr[0], arr[1]);
            }
            case REGEXP                   -> filter.regexp(fr.field(), fr.value().toString());
            case NOT_REGEXP               -> filter.regexp(fr.field(), fr.value().toString());
            default                       -> filter.eq(fr.field(), fr.value());
        }
    }

    private Condition aliasCondition(FilterRow fr) {
        org.jooq.Field<Object> f = (org.jooq.Field<Object>) DSL.field(DSL.name(fr.field()));
        return switch (fr.op()) {
            case EQUAl                    -> f.eq(DSL.val(fr.value()));
            case NOT_EQUAL                -> f.ne(DSL.val(fr.value()));
            case GREATER_THAN             -> f.greaterThan(DSL.val(fr.value()));
            case GREATER_THAN_OR_EQUAL_TO -> f.greaterOrEqual(DSL.val(fr.value()));
            case LESS_THAN                -> f.lessThan(DSL.val(fr.value()));
            case LESS_THAN_OR_EQUAL_TO    -> f.lessOrEqual(DSL.val(fr.value()));
            default -> f.eq(DSL.val(fr.value()));
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  GENERATED MODE — SAHƏ HƏLL EDİCİLƏR
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code "alias.field"} və ya {@code "field"} formatından sahə adı hissəsini çıxarır.
     *
     * <pre>{@code
     *   fieldPart("u.firstName") → "firstName"
     *   fieldPart("status")      → "status"
     * }</pre>
     */
    private static String fieldPart(String aliasAndField) {
        if (aliasAndField == null) return null;
        int dot = aliasAndField.indexOf('.');
        return dot >= 0 ? aliasAndField.substring(dot + 1) : aliasAndField;
    }

    /**
     * "t.fieldName" → "t" (cədvəl alias hissəsi).
     * Nöqtə yoxdursa boş sətir qaytarılır.
     */
    private static String aliasPart(String aliasAndField) {
        if (aliasAndField == null) return "";
        int dot = aliasAndField.indexOf('.');
        return dot >= 0 ? aliasAndField.substring(0, dot) : "";
    }

    /**
     * Generated cədvəldə string adla {@link Field} tapır.
     *
     * <ol>
     *   <li>Birbaşa axtarış: {@code table.field(name)}</li>
     *   <li>camelCase → snake_case çevrilərək: {@code firstName → first_name}</li>
     *   <li>Bütün sahələrdə case-insensitive müqayisə</li>
     * </ol>
     *
     * @param table     jOOQ generated cədvəl
     * @param fieldName camelCase və ya snake_case sahə adı
     * @return tapılan {@link Field}, tapılmadıqda {@code null}
     */
    /**
     * Generated mode üçün: "alias.field" və ya "field" formatında sətri parse edib
     * düzgün cədvəldən (main və ya join) Field-i tapır.
     *
     * <p>Alias joinTableRegistry-də tapılmadıqda generatedTable istifadə olunur.
     */
    private Field<?> resolveFieldByAlias(String col) {
        if (col == null || col.isBlank()) return null;
        int dot = col.indexOf('.');
        if (dot > 0) {
            String tableAlias = col.substring(0, dot);
            String fieldName  = col.substring(dot + 1);
            Table<?> target   = joinTableRegistry.getOrDefault(tableAlias, generatedTable);
            return resolveFromTable(target, fieldName);
        }
        return resolveFromTable(generatedTable, col);
    }

    private static Field<?> resolveFromTable(Table<?> table, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return null;

        // 1. Birbaşa axtarış
        Field<?> f = table.field(fieldName);
        if (f != null) return f;

        // 2. camelCase → snake_case
        String snake = camelToSnake(fieldName);
        if (!snake.equals(fieldName)) {
            f = table.field(snake);
            if (f != null) return f;
        }

        // 3. Case-insensitive tam fərqlənmə
        for (Field<?> tf : table.fields()) {
            String name = tf.getName();
            if (name.equalsIgnoreCase(fieldName) || name.equalsIgnoreCase(snake)) return tf;
        }

        return null;
    }

    /**
     * camelCase adı snake_case-ə çevirir.
     *
     * <pre>{@code
     *   camelToSnake("firstName")   → "first_name"
     *   camelToSnake("userId")      → "user_id"
     *   camelToSnake("createdAt")   → "created_at"
     *   camelToSnake("status")      → "status"
     * }</pre>
     */
    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * İki field arasında Op-a uyğun Condition yaradır.
     * JOIN ON şərtlərindəki field-to-field müqayisə üçün istifadə edilir.
     *
     * <pre>{@code
     *   applyFieldOp(Op.EQUAl,        fromF, toF)  →  fromF = toF
     *   applyFieldOp(Op.NOT_EQUAL,    fromF, toF)  →  fromF != toF
     *   applyFieldOp(Op.GREATER_THAN, fromF, toF)  →  fromF > toF
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private static Condition applyFieldOp(Op op, Field<Object> from, Field<Object> to) {
        if (op == null) return from.eq(to);
        return switch (op) {
            case NOT_EQUAL                  -> from.ne(to);
            case LESS_THAN                  -> from.lt(to);
            case LESS_THAN_OR_EQUAL_TO      -> from.le(to);
            case GREATER_THAN               -> from.gt(to);
            case GREATER_THAN_OR_EQUAL_TO   -> from.ge(to);
            default                         -> from.eq(to);   // EQUAl + digərləri
        };
    }
}
