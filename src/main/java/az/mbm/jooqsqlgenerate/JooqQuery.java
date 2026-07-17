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
import az.mbm.jooqsqlgenerate.enums.NullDefault;
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

    /**
     * Bu sorğuda artıq qeydiyyatdan keçmiş bütün SQL output alias-ları
     * (computed/agg/coalesce/concat/selectAs). Eyni alias iki dəfə təyin
     * olunarsa (məs. kopyala-yapışdır zamanı köhnə bloku silməyi unutmaq),
     * Postgres-də gizli "ambiguous column" xətası ilə yox, bura, query
     * qurulan zaman aydın mesajla bağlanır. Bax: {@link #registerAlias(String)}.
     */
    private final Set<String>               usedOutputAliases = new HashSet<>();

    /**
     * Yeni output alias-ı qeydə alır; əgər artıq istifadə olunubsa,
     * dərhal aydın xəta atır (runtime-da Postgres-in qarışıq
     * "ambiguous column" xətasını gözləmək əvəzinə).
     */
    private void registerAlias(String alias) {
        if (alias == null || alias.isBlank()) return;
        if (!usedOutputAliases.add(alias)) {
            throw new IllegalStateException(
                "Duplicate output alias: \"" + alias + "\" bu sorğuda artıq istifadə olunub. " +
                "Çox güman ki, kopyalanmış computed/agg/concat/coalesce/selectAs bloku var — " +
                "köhnə tərifi silin və ya başqa alias seçin."
            );
        }
    }

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
    /** Generated mode: alias.field referansları — joinTableRegistry doldurulana qədər (execute zamanı) həll təxirə salınır. */
    private final List<String>              deferredSelectCols = new ArrayList<>();
    /** Generated mode: GROUP BY üçün alias.field referansları — joinTableRegistry doldurulana qədər (execute zamanı) həll təxirə salınır. */
    private final List<String>              deferredGroupByCols = new ArrayList<>();
    private final List<SortField<?>>        rawOrderFields   = new ArrayList<>();
    /**
     * Generated mode: ORDER BY tokenləri — həqiqi çağırış sırasını saxlamaq üçün.
     * {@code orderBy("field","dir")} VƏ aqreqat alias-ının {@code orderDir} parametri
     * eyni siyahıya yazılır ki, son ORDER BY-da çağırış sırası qorunsun
     * (əks halda aqreqat-əsaslı sıralama həmişə sona düşürdü).
     */
    /**
     * {@code seq} — istəyə bağlı açıq sıra nömrəsi (orderSeq). {@code null} olduqda
     * çağırış sırası saxlanılır; əks halda bütün açıq {@code seq} dəyərləri ASC
     * sıralanıb əvvələ qoyulur, sonra {@code seq}-i olmayanlar öz çağırış sırasında
     * davam edir (bax: {@link #buildOrderTokens()}).
     */
    private record OrderToken(boolean isAlias, String fieldOrAlias, String dir, Integer seq) {}
    private final List<OrderToken>          orderTokens      = new ArrayList<>();
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

    /**
     * Generated mode-da .filter(field, op, value) həllini executeGenerated()-ə
     * təxirə salır — çağırış vaxtı joinTableRegistry/aggExprByAlias hələ tam
     * dolu olmaya bilər (Class-based JOIN-lər və concat/coalesce/selectAs
     * alias-ları kimi). Bax: executeGenerated()-də həll loop-u.
     */
    private final List<FilterRow> deferredWhereFilterRows = new ArrayList<>();

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
                          MathOp mathOp, String mathField, ComputedField expr,
                          List<AggregateBuilder.PostAggOp> postOps) {}
    private record SortRow(String field, String dir) {}
    private record CaseRow(String field, Op op, Object when,
                           Object then, Object els, String alias) {}
    private record ComputedRow(String alias,
                               String ta1, String f1, MathOp op,
                               String ta2, String f2,
                               NullDefault nullDefault) {}
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
     *
     * @deprecated Reflection + EntityTable əsaslıdır, sahə adı səhv yazılsa yalnız
     * runtime-da üzə çıxır. Bunun əvəzinə {@link #from(Table, String)} (jOOQ generated
     * Table, tip-təhlükəli) və ya {@link #from(SelectTable, String)} (derived table)
     * istifadə edin.
     */
    @Deprecated
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
            // Həll təxirə salınır — JOIN-lər hələ joinTableRegistry-yə yazılmayıb (bax executeGenerated)
            deferredSelectCols.addAll(Arrays.asList(cols));
        } else {
            columns.addAll(Arrays.asList(cols));
        }
        return this;
    }

    /** SELECT sütunlar — dinamik {@link List} ilə (hər iki mode dəstəklənir). */
    public JooqQuery<T> select(List<String> cols) {
        if (cols == null) return this;
        if (generatedTable != null) {
            // Həll təxirə salınır — JOIN-lər hələ joinTableRegistry-yə yazılmayıb (bax executeGenerated)
            deferredSelectCols.addAll(cols);
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
        if (alias != null && f1 != null && f2 != null && op != null) {
            registerAlias(alias);
            computedCols.add(new ComputedRow(alias, ta1, f1, op, ta2, f2, NullDefault.NONE));
        }
        return this;
    }

    /**
     * 2 sahəli riyazi ifadə sütunu + NULL default strategiyası.
     *
     * <pre>{@code
     *   .computedColumn("lineTotal", "o", MathOp.MULTIPLY, "price", "o", "qty", NullDefault.ZERO)
     *   // → COALESCE(price,0) * COALESCE(qty,0) AS lineTotal
     * }</pre>
     */
    public JooqQuery<T> computedColumn(String alias,
                                       String ta1, MathOp op, String f1,
                                       String ta2, String f2,
                                       NullDefault nullDefault) {
        if (alias != null && f1 != null && f2 != null && op != null) {
            registerAlias(alias);
            computedCols.add(new ComputedRow(alias, ta1, f1, op, ta2, f2,
                    nullDefault != null ? nullDefault : NullDefault.NONE));
        }
        return this;
    }

    /** Çox sahəli riyazi ifadə sütunu ({@link ComputedField} ilə). */
    public JooqQuery<T> computedColumn(ComputedField cf) {
        if (cf != null) {
            registerAlias(cf.getAlias());
            computedFields.add(new ComputedFieldEntry(cf, null, null));
        }
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
        if (cf != null) {
            registerAlias(cf.getAlias());
            computedFields.add(new ComputedFieldEntry(cf, op, value));
        }
        return this;
    }

    /** COALESCE sütunu. */
    public JooqQuery<T> coalesce(String alias, Object defaultValue, String... fields) {
        if (alias != null && fields != null && fields.length > 0) {
            registerAlias(alias);
            coalesceCols.add(new CoalesceRow(alias, defaultValue, fields));
        }
        return this;
    }

    /** COALESCE sütunu — List&lt;String&gt; variantı. Bax: {@link #coalesce(String, Object, String...)}. */
    public JooqQuery<T> coalesce(String alias, Object defaultValue, List<String> fields) {
        if (fields != null && !fields.isEmpty())
            coalesce(alias, defaultValue, fields.toArray(new String[0]));
        return this;
    }

    /**
     * COALESCE sütunu — {@link ConcatItem} kolleksiyası ilə (dinamik siyahı).
     * Yalnız {@link ConcatItem#field(String)} elementləri dəstəklənir —
     * sabit dəyər üçün {@code defaultValue} istifadə edin.
     */
    public JooqQuery<T> coalesce(String alias, Object defaultValue, Collection<ConcatItem> items) {
        if (items != null && !items.isEmpty())
            coalesce(alias, defaultValue, items.stream().map(it -> {
                if (it instanceof ConcatItem.ColField cf) return cf.aliasAndField();
                throw new IllegalStateException(
                        "coalesce yalnız ConcatItem.field(...) qəbul edir — sabit dəyər üçün defaultValue istifadə edin");
            }).toArray(String[]::new));
        return this;
    }

    /**
     * CONCAT sütunu — sadəcə sütunları birləşdirir (ən çox işlənən sadə hal).
     *
     * <p>Literal/CASE/COALESCE qarışdırmaq lazım olduqda {@link #concat(String, String, ConcatItem...)}
     * istifadə edin; bu metod isə adi "sütunları birləşdir" halı üçündür və heç bir
     * əlavə (builder paketindən) import tələb etmir.
     */
    public JooqQuery<T> concat(String alias, String separator, String... fields) {
        if (alias != null && fields != null && fields.length > 0) {
            registerAlias(alias);
            List<ConcatItem> items = Arrays.stream(fields)
                    .map(ConcatItem::field)
                    .collect(java.util.stream.Collectors.toList());
            concatCols.add(new ConcatRow(alias, separator, items));
        }
        return this;
    }

    /**
     * CONCAT sütunu — fluent (join-style) yazı tərzi: əvvəl sahələr, sonra ayırıcı, sonra alias.
     *
     * <pre>{@code
     *   .concatColumn("u.firstName", "u.lastName").sep(" ").as("fullName")
     * }</pre>
     *
     * <p>{@code .as(alias)} builder-i tamamlayır və sütunu əlavə edib {@link JooqQuery}-yə
     * qayıdır (JOIN builder-lərdəki {@code done()} kimi). Nəticə {@link #concat(String, String, String...)}
     * ilə eynidir, sadəcə yazı sırası fərqlidir — heç bir əlavə import tələb etmir.
     */
    public ConcatSetup concatColumn(String... fields) {
        return new ConcatSetup(this, fields);
    }

    /**
     * Fluent CONCAT builder — {@link #concatColumn(String...)} tərəfindən yaradılır.
     */
    public final class ConcatSetup {
        private final JooqQuery<T> parent;
        private final String[]     fields;
        private String             separator = "";

        ConcatSetup(JooqQuery<T> parent, String[] fields) {
            this.parent = parent;
            this.fields = fields;
        }

        /** Sahələr arasına qoyulan ayırıcı (default: boş string). */
        public ConcatSetup sep(String separator) {
            this.separator = separator != null ? separator : "";
            return this;
        }

        /** Builder-i tamamlayır: alias-ı təyin edir, sütunu əlavə edir, {@link JooqQuery}-yə qayıdır. */
        public JooqQuery<T> as(String alias) {
            return parent.concat(alias, separator, fields);
        }
    }

    /**
     * CONCAT sütunu — List&lt;String&gt; variantı. Bax: {@link #concat(String, String, String...)}.
     */
    public JooqQuery<T> concat(String alias, String separator, List<String> fields) {
        if (alias != null && fields != null && !fields.isEmpty()) {
            registerAlias(alias);
            List<ConcatItem> items = fields.stream()
                    .map(ConcatItem::field)
                    .collect(java.util.stream.Collectors.toList());
            concatCols.add(new ConcatRow(alias, separator, items));
        }
        return this;
    }

    /**
     * CONCAT sütunu — {@link ConcatItem} kolleksiyası ilə (dinamik siyahı).
     *
     * <pre>{@code
     *   List<ConcatItem> ad = new ArrayList<>();
     *   ad.add(ConcatItem.field("t.firstName"));
     *   ad.add(ConcatItem.literal("-"));
     *   ad.add(ConcatItem.field("t.lastName"));
     *   .concat("fkDataId", "", ad)
     * }</pre>
     */
    public JooqQuery<T> concat(String alias, String separator, Collection<ConcatItem> items) {
        if (alias != null && items != null && !items.isEmpty()) {
            registerAlias(alias);
            concatCols.add(new ConcatRow(alias, separator, new ArrayList<>(items)));
        }
        return this;
    }

    /** CONCAT sütunu — field + literal qarışıq. */
    public JooqQuery<T> concat(String alias, String separator, ConcatItem... items) {
        if (alias != null && items != null && items.length > 0) {
            registerAlias(alias);
            concatCols.add(new ConcatRow(alias, separator, Arrays.asList(items)));
        }
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
                && outputAlias != null && !outputAlias.isBlank()) {
            registerAlias(outputAlias);
            selectAsRows.add(new SelectAsRow(aliasAndField, outputAlias));
        }
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

    /**
     * LEFT JOIN — entity mode üçün (string field adları, tək cüt).
     *
     * @deprecated Yalnız tək ON cütünə icazə verir. Bunun əvəzinə
     * {@link #leftJoin(Class, String)} (fluent {@code JoinBuilder}) istifadə edin:
     * {@code .leftJoin(Entity.class, alias).on(fromField, toField).done()} —
     * çoxlu ON şərti və əlavə {@code andOn(...)} filterlərini də dəstəkləyir.
     */
    @Deprecated
    public JooqQuery<T> leftJoin(Class<?> entity, String alias,
                                 String fromField, String toField) {
        joins.add(new JoinRow("LEFT", entity, alias, fromField, toField));
        return this;
    }

    /**
     * INNER JOIN — entity mode üçün (string field adları, tək cüt).
     *
     * @deprecated Bax: {@link #leftJoin(Class, String, String, String)}. Bunun
     * əvəzinə {@link #innerJoin(Class, String)} (fluent {@code JoinBuilder}) istifadə edin.
     */
    @Deprecated
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
         * ON şərti: {@code fromField} dot-notation daşıyırsa ("d0.fkCounterAgentId")
         * həmin alias-dan (əvvəlki JOIN-lərdən biri ola bilər), yoxdursa ana cədvəldən
         * götürülür = join cədvəl.toField.
         *
         * <pre>{@code
         *   .on("fkProductId", "id")            // ana cədvəl.fkProductId = join.id
         *   .on("d0.fkCounterAgentId", "id")    // d0.fkCounterAgentId    = join.id
         * }</pre>
         *
         * @param fromField ana cədvəldəki sahə adı (camelCase), və ya {@code "alias.field"}
         * @param toField   join cədvəlindəki sahə adı (camelCase)
         */
        public JoinBuilder on(String fromField, String toField) {
            if (fromField != null && toField != null) {
                String fAlias = aliasPart(fromField);
                String fField = fieldPart(fromField);
                pairs.add(new FieldPair(fAlias.isBlank() ? alias : fAlias, fField, Op.EQUAl, toField));
            }
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
         * ON şərti: dot-notation — {@code "alias.field"} birləşdirilmiş formada.
         *
         * <pre>{@code .onFrom("u.fkCompanyId", "id") }</pre>
         *
         * @param fromAliasAndField {@code "alias.field"} formatında birləşdirilmiş
         * @param toField           join cədvəlindəki sahə adı
         */
        public JoinBuilder onFrom(String fromAliasAndField, String toField) {
            if (fromAliasAndField != null && toField != null) {
                int dot = fromAliasAndField.indexOf('.');
                if (dot > 0) {
                    pairs.add(new FieldPair(
                            fromAliasAndField.substring(0, dot),
                            fromAliasAndField.substring(dot + 1),
                            Op.EQUAl, toField));
                }
            }
            return this;
        }

        /**
         * ON şərti: dot-notation + operator — {@code "alias.field"} birləşdirilmiş formada.
         *
         * <pre>{@code .onFrom("u.fkCompanyId", Op.EQUAl, "id") }</pre>
         *
         * @param fromAliasAndField {@code "alias.field"} formatında birləşdirilmiş
         * @param op                müqayisə operatoru
         * @param toField           join cədvəlindəki sahə adı
         */
        public JoinBuilder onFrom(String fromAliasAndField, Op op, String toField) {
            if (fromAliasAndField != null && op != null && toField != null) {
                int dot = fromAliasAndField.indexOf('.');
                if (dot > 0) {
                    pairs.add(new FieldPair(
                            fromAliasAndField.substring(0, dot),
                            fromAliasAndField.substring(dot + 1),
                            op, toField));
                }
            }
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

        /** Shortcut: {@code AND join.field = value} */
        public JoinBuilder equal(String field, Object value) {
            return andOn(field, Op.EQUAl, value);
        }

        /** Alias: {@code equal} ilə eynidir */
        public JoinBuilder andOnEqual(String field, Object value) {
            return andOn(field, Op.EQUAl, value);
        }

        /** Shortcut: {@code AND join.field != value} */
        public JoinBuilder notEqual(String field, Object value) {
            return andOn(field, Op.NOT_EQUAL, value);
        }

        /** Alias: {@code notEqual} ilə eynidir */
        public JoinBuilder andOnNotEqual(String field, Object value) {
            return andOn(field, Op.NOT_EQUAL, value);
        }

        /** Shortcut: {@code AND join.field > value} */
        public JoinBuilder greaterThan(String field, Object value) {
            return andOn(field, Op.GREATER_THAN, value);
        }

        /** Shortcut: {@code AND join.field >= value} */
        public JoinBuilder greaterThanOrEqual(String field, Object value) {
            return andOn(field, Op.GREATER_THAN_OR_EQUAL_TO, value);
        }

        /** Shortcut: {@code AND join.field < value} */
        public JoinBuilder lessThan(String field, Object value) {
            return andOn(field, Op.LESS_THAN, value);
        }

        /** Shortcut: {@code AND join.field <= value} */
        public JoinBuilder lessThanOrEqual(String field, Object value) {
            return andOn(field, Op.LESS_THAN_OR_EQUAL_TO, value);
        }

        /** Shortcut: {@code AND join.field IS NULL} */
        public JoinBuilder isNull(String field) {
            return andOn(field, Op.IS_EMPTY, "__null_check__");
        }

        /** Shortcut: {@code AND join.field IS NOT NULL} */
        public JoinBuilder isNotNull(String field) {
            return andOn(field, Op.IS_NOT_EMPTY, "__null_check__");
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
     *
     * @deprecated Yalnız tək ON cütünə icazə verir. Bunun əvəzinə
     * {@link #leftJoin(SelectTable, String)} (fluent {@code SelectJoinBuilder}) istifadə edin.
     */
    @Deprecated
    public JooqQuery<T> leftJoin(SelectTable subQuery, String alias,
                                  String fromField, String toField) {
        selectJoins.add(new SelectJoinRow("LEFT", subQuery, alias,
                List.of(new FieldPair(aliasPart(fromField), fieldPart(fromField), toField)),
                List.of()));
        return this;
    }

    /**
     * INNER JOIN — başqa bir {@link SelectTable} ilə, string field adları ilə.
     *
     * @deprecated Bax: {@link #leftJoin(SelectTable, String, String, String)}. Bunun
     * əvəzinə {@link #innerJoin(SelectTable, String)} (fluent) istifadə edin.
     */
    @Deprecated
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

        // Generated mode — həll BURADA YOX, executeGenerated()-də aparılır.
        // Səbəb: joinTableRegistry (Class-based JOIN-lər üçün) və aggExprByAlias
        // (concat/coalesce/selectAs/computed alias-ları üçün) yalnız bütün
        // builder zənciri tamamlandıqdan sonra — executeGenerated() içində —
        // tam dolur. Əvvəllər bura BURADA dərhal (eager) həll edilirdi və
        // tapılmadıqda sükutla atılırdı (return this) — nəticədə join edilmiş
        // cədvəlin sütununa və ya concat/coalesce/selectAs alias-ına filter
        // verildikdə heç bir xəbərdarlıq olmadan WHERE-ə düşmürdü.
        if (generatedTable != null) {
            Object cleanValue = value;
            if (value instanceof Collection<?> c) {
                List<?> clean = c.stream().filter(Objects::nonNull).collect(Collectors.toList());
                if (clean.isEmpty()) return this;
                cleanValue = clean;
            }
            deferredWhereFilterRows.add(new FilterRow(field, op, cleanValue));
            return this;
        }

        // Entity mode — list gəldikdə IN istifadə edilir
        if (value instanceof Collection<?> c) {
            List<?> clean = c.stream().filter(Objects::nonNull).collect(Collectors.toList());
            if (clean.isEmpty()) return this;
            Op resolvedOp = switch (op) {
                case EQUAl, EQUAL -> Op.IN;
                case NOT_EQUAL    -> Op.NOT_IN;
                default           -> op;
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
            if (e.getKey() == null) continue;
            Op op = JooqManager.parseOperationPublic(e.getKey());
            if (op == null) continue;
            // IS_EMPTY/IS_NOT_EMPTY (isNull/isNotNull) dəyər tələb etmir — FilterStrategies
            // dəyəri nəzərə almır (field.isNull()/field.isNotNull()). Digər əməliyyatlar
            // üçün isə boş/null dəyər mənasızdır və atlanır.
            String raw = e.getValue() == null ? "" : e.getValue();
            if (op != Op.IS_EMPTY && op != Op.IS_NOT_EMPTY && raw.isBlank()) continue;
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
            // joinTableRegistry execute() zamanı dolur — həll təxirə salınır
            // (join alias-ları groupBy-dan SONRA əlavə oluna bilər).
            deferredGroupByCols.addAll(Arrays.asList(fields));
        } else {
            groupByFields.addAll(Arrays.asList(fields));
        }
        return this;
    }

    /** GROUP BY sahələri — dinamik {@code List<String>} ilə (hər iki mode dəstəklənir). */
    public JooqQuery<T> groupBy(List<String> fields) {
        if (fields == null) return this;
        if (generatedTable != null) {
            // joinTableRegistry execute() zamanı dolur — həll təxirə salınır
            // (join alias-ları groupBy-dan SONRA əlavə oluna bilər).
            deferredGroupByCols.addAll(fields);
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
        if (fn != null && field != null && alias != null) {
            // alias-da "t.totalPrice" kimi prefix gəlsə yalnız "totalPrice" saxlanır
            registerAlias(fieldPart(alias));
            aggRows.add(new AggRow(fn, field, fieldPart(alias), round,
                                   orderDir, MathOp.NONOPERATION, null, null, List.of()));
            if (generatedTable != null && orderDir != null)
                orderTokens.add(new OrderToken(true, fieldPart(alias), orderDir, null));
        }
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
        if (fn != null && field != null && alias != null) {
            registerAlias(fieldPart(alias));
            aggRows.add(new AggRow(fn, field, fieldPart(alias), round,
                                   orderDir, mathOp, mathField, null, List.of()));
            if (generatedTable != null && orderDir != null)
                orderTokens.add(new OrderToken(true, fieldPart(alias), orderDir, null));
        }
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
        return aggOnComputed(fn, expr, alias, round, orderDir, null);
    }

    /**
     * ComputedField üzərindəki aqreqat — açıq ORDER BY sıra nömrəsi ilə.
     *
     * <p>{@code orderSeq} verildikdə bu sıralama meyarı, çağırış sırasından asılı
     * olmayaraq, {@code orderSeq} dəyərinə görə (ASC) digər açıq {@code orderSeq}
     * meyarları ilə birgə ORDER BY-ın əvvəlinə yerləşdirilir. {@code orderSeq}
     * verilməyən (yəni {@code null}) meyarlar isə öz çağırış sırasında onlardan sonra
     * gəlir.
     *
     * <pre>{@code
     *   .addAggFunction(Agg.MAX, "d1.operationDate").as("operationDate", "DESC", 1)
     *   .addAggFunction(Agg.SUM, "d1.totalPrice").as("totalPrice", 2, "DESC", 2)
     * }</pre>
     */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr,
                                      String alias, Integer round, String orderDir,
                                      Integer orderSeq) {
        return aggOnComputed(fn, expr, alias, round, orderDir, orderSeq, List.of());
    }

    /**
     * ComputedField üzərindəki aqreqat — POST-AGGREGATE əməliyyatlar ilə.
     *
     * <p>{@code postOps} aqreqat funksiyası (və ROUND, COALESCE(...,0)) artıq
     * hesablandıqdan SONRA tətbiq olunur — yəni {@code SUM(expr - field)} YOX,
     * {@code COALESCE(ROUND(SUM(expr),4),0) - COALESCE(field, nullAs)} qurulur.
     * Bu, artıq JOIN-lənmiş/aqreqasiya olunmuş tək-qiymətli sahələri qrupun
     * sətir sayına görə təkrar çıxarmamaq üçün lazımdır. Bax: {@link JooqManager.AggChain#subtractAfterAgg}.
     */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr,
                                      String alias, Integer round, String orderDir,
                                      Integer orderSeq, List<AggregateBuilder.PostAggOp> postOps) {
        if (fn != null && expr != null && alias != null) {
            registerAlias(fieldPart(alias));
            aggRows.add(new AggRow(fn, null, fieldPart(alias), round,
                                   orderDir, null, null, expr,
                                   postOps == null ? List.of() : postOps));
            if (generatedTable != null && orderDir != null)
                orderTokens.add(new OrderToken(true, fieldPart(alias), orderDir, orderSeq));
        }
        return this;
    }

    /** ComputedField üzərindəki aqreqat — yalnız əsas parametrlər. */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr, String alias) {
        return aggOnComputed(fn, expr, alias, null, null, null);
    }

    /** ComputedField üzərindəki aqreqat — yuvarlama ilə. */
    public JooqQuery<T> aggOnComputed(Agg fn, ComputedField expr,
                                      String alias, Integer round) {
        return aggOnComputed(fn, expr, alias, round, null, null);
    }

    /**
     * Oxunaqlı aqreqat ifadəsi — {@link az.mbm.jooqsqlgenerate.builder.AggExpr} zənciri ilə.
     *
     * <pre>{@code
     *   // SUM( a + b*c - d - b*e ) AS totalPrice
     *   .sumExpr("totalPrice", e -> e
     *       .plus("t.marginalCostOut")
     *       .plus("t.totalPurchaseExpense", "t.actionOut")    // + (f1 * f2)
     *       .minus("t.marginalCostIn")
     *       .minus("t.totalPurchaseExpense", "t.actionIn"))   // - (f1 * f2)
     * }</pre>
     */
    public JooqQuery<T> sumExpr(String alias,
                                java.util.function.Consumer<az.mbm.jooqsqlgenerate.builder.AggExpr> chain) {
        return aggExpr(Agg.SUM, alias, chain);
    }

    /** {@link #sumExpr(String, java.util.function.Consumer)} — istənilən aqreqat funksiyası ilə. */
    public JooqQuery<T> aggExpr(Agg fn, String alias,
                                java.util.function.Consumer<az.mbm.jooqsqlgenerate.builder.AggExpr> chain) {
        az.mbm.jooqsqlgenerate.builder.AggExpr e = az.mbm.jooqsqlgenerate.builder.AggExpr.create();
        chain.accept(e);
        return aggOnComputed(fn, e.build(), alias);
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

            // Hər iki rejimdə də həllini sonraya saxlayırıq: executeGenerated()/execute()
            // FilterRow-u alias üzrə aqreqat ifadəsi (aggExprByAlias / havingMap) ilə
            // həll edir. Generated rejimdə birbaşa bare alias referansı ("paymentX")
            // yazmaq TƏHLÜKƏLİDİR — əgər həmin alias adı eyni zamanda JOIN edilmiş
            // törəmə cədvəldə (derived table) real sütun kimi mövcuddursa, Postgres
            // HAVING-i SELECT alias-ına görə deyil, FROM-dakı real sütuna görə oxuyur və
            // "must appear in GROUP BY clause or be used in an aggregate function" xətası verir.
            havingFilterRows.add(new FilterRow(fieldPart(field), op, value));
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
        return orderBy(field, direction, null);
    }

    /**
     * ORDER BY — açıq sıra nömrəsi (orderSeq) ilə. Bax: {@link #aggOnComputed}
     * metodundaki {@code orderSeq} izahı.
     */
    public JooqQuery<T> orderBy(String field, String direction, Integer orderSeq) {
        if (field == null || field.isBlank()) return this;
        if (generatedTable != null) {
            // joinTableRegistry execute() zamanı dolur, aqreqat alias-ları ilə
            // çağırış sırasını qorumaq üçün — həll təxirə salınır.
            orderTokens.add(new OrderToken(false, field, direction, orderSeq));
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
            builder.computedColumn(cr.alias(), cr.ta1(), cr.op(), cr.f1(), cr.ta2(), cr.f2(),
                    cr.nullDefault());
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

                // toField "alias.field" formatında ola bilər (məs: "tax.id")
                String rawToField = fp.toField();
                int toDot = rawToField.indexOf('.');
                EntityTable<?> resolvedToTable;
                String resolvedToFieldName;
                if (toDot > 0) {
                    String toAlias = rawToField.substring(0, toDot);
                    resolvedToFieldName = rawToField.substring(toDot + 1);
                    Class<?> toClass = entityClassMap.getOrDefault(toAlias, jr.entity());
                    resolvedToTable = new EntityTable<>(toClass, toAlias);
                } else {
                    resolvedToFieldName = rawToField;
                    resolvedToTable = toTable;
                }
                @SuppressWarnings("unchecked")
                Field<Object> toField = (Field<Object>) resolvedToTable.getField(resolvedToFieldName);
                Condition c = applyFieldOp(fp.op(), fromField, toField);
                on = (on == null) ? c : on.and(c);
            }

            // Əlavə value şərtlər (t1.status = 'A' və ya tax.status = 'A' kimi)
            for (JoinFilterRow extra : jr.extras()) {
                String rawField = extra.field();
                int dot = rawField.indexOf('.');
                EntityTable<?> filterTable;
                String fieldName;
                if (dot > 0) {
                    String fieldAlias = rawField.substring(0, dot);
                    fieldName = rawField.substring(dot + 1);
                    Class<?> cls = entityClassMap.getOrDefault(fieldAlias, jr.entity());
                    filterTable = new EntityTable<>(cls, fieldAlias);
                } else {
                    fieldName = rawField;
                    filterTable = toTable;
                }
                @SuppressWarnings("unchecked")
                Field<Object> f = (Field<Object>) filterTable.getField(fieldName);
                Condition c = FilterStrategies.get(extra.op()).apply(f, extra.value());
                on = (on == null) ? c : on.and(c);
            }

            if (on == null) on = DSL.trueCondition();

            if ("LEFT".equals(jr.type()))
                builder.leftJoinWithCondition(jr.entity(), jr.alias(), on);
            else
                builder.innerJoinWithCondition(jr.entity(), jr.alias(), on);
        }

        // JOIN — SelectTable (derived table) ilə, çoxlu field cütü + əlavə ON şərtləri.
        // rawAliasMap: artıq join edilmiş SelectTable alias-larını (d2, d3...) saxlayır,
        // ki sonrakı SelectTable JOIN-lər "d2.field" kimi əvvəlki derived table-a istinad edə bilsin.
        Map<String, Table<?>> rawAliasMap = new LinkedHashMap<>();
        for (SelectJoinRow jr : selectJoins) {
            @SuppressWarnings("unchecked")
            Table<?> jt = jr.subQuery().asTable(jr.alias());

            Condition on = null;

            // Field cütlərindən ON şərti
            for (FieldPair fp : jr.pairs()) {
                @SuppressWarnings("unchecked")
                Field<Object> fromField = (Field<Object>) resolveJoinFieldForAlias(
                        fp.fromAlias(), fp.fromField(), entityClassMap, rawAliasMap, entity);

                // toField "alias.field" formatında ola bilər, əks halda join cədvəlinin öz sahəsidir
                String rawToField = fp.toField();
                int toDot = rawToField.indexOf('.');
                Field<Object> toField;
                if (toDot > 0) {
                    String toAlias = rawToField.substring(0, toDot);
                    String toFieldName = rawToField.substring(toDot + 1);
                    @SuppressWarnings("unchecked")
                    Field<Object> resolved = (Field<Object>) resolveJoinFieldForAlias(
                            toAlias, toFieldName, entityClassMap, rawAliasMap, entity);
                    toField = resolved;
                } else {
                    Field<?> rf = jt.field(rawToField);
                    if (rf == null) rf = DSL.field(DSL.name(jr.alias(), rawToField));
                    @SuppressWarnings("unchecked")
                    Field<Object> resolved = (Field<Object>) rf;
                    toField = resolved;
                }

                Condition c = applyFieldOp(fp.op(), fromField, toField);
                on = (on == null) ? c : on.and(c);
            }

            // Əlavə value şərtlər (join cədvəlinin field-i OP value)
            for (JoinFilterRow extra : jr.extras()) {
                String rawField = extra.field();
                int dot = rawField.indexOf('.');
                Field<Object> f;
                if (dot > 0) {
                    String fieldAlias = rawField.substring(0, dot);
                    String fieldName  = rawField.substring(dot + 1);
                    @SuppressWarnings("unchecked")
                    Field<Object> resolved = (Field<Object>) resolveJoinFieldForAlias(
                            fieldAlias, fieldName, entityClassMap, rawAliasMap, entity);
                    f = resolved;
                } else {
                    Field<?> rf = jt.field(rawField);
                    if (rf == null) rf = DSL.field(DSL.name(jr.alias(), rawField));
                    @SuppressWarnings("unchecked")
                    Field<Object> resolved = (Field<Object>) rf;
                    f = resolved;
                }
                Condition c = FilterStrategies.get(extra.op()).apply(f, extra.value());
                on = (on == null) ? c : on.and(c);
            }

            if (on == null) on = DSL.trueCondition();

            if ("LEFT".equals(jr.type()))
                builder.leftJoinRawWithCondition(jt, jr.alias(), on);
            else
                builder.innerJoinRawWithCondition(jt, jr.alias(), on);

            rawAliasMap.put(jr.alias(), jt);
        }

        // WHERE — normal filterlər
        Set<String> aggAliases = new HashSet<>();
        for (AggRow ar : aggRows) aggAliases.add(ar.alias());
        Set<String> computedAliases = new HashSet<>();
        for (ComputedRow cr  : computedCols)   computedAliases.add(cr.alias());
        for (ComputedFieldEntry entry : computedFields) if (entry.cf().getAlias() != null) computedAliases.add(entry.cf().getAlias());
        // CONCAT alias-ları — CONCAT aqreqat olmadığı üçün GROUP BY olsa belə HAVING-ə
        // yox, həmişə WHERE-ə (computedWhereFilters) yönləndirilir.
        Set<String> concatAliases = new HashSet<>();
        for (ConcatRow cc : concatCols) if (cc.alias() != null) concatAliases.add(cc.alias());
        boolean hasGroupBy = !groupByFields.isEmpty() || !aggRows.isEmpty();

        List<FilterRow> whereFilters          = new ArrayList<>();
        // Eyni aqreqat alias-ına bir neçə HAVING şərti düşə bilər (məs. filter + globalFilter,
        // və ya greaterThan + lessThan aralığı) — üstələmə (overwrite) yox, hamısı AND ilə tətbiq olunur.
        Map<String, List<FilterRow>> havingMap = new LinkedHashMap<>();
        List<FilterRow> computedWhereFilters  = new ArrayList<>();
        List<FilterRow> computedHavingFilters = new ArrayList<>();
        List<FilterRow> roundedWhereFilters   = new ArrayList<>();  // ROUND(field,scale) filterlər

        // havingFilter() ilə birbaşa əlavə edilən HAVING şərtləri
        // → bunlar AggregateBuilder.step.having(op, val) vasitəsilə işlənir
        //   beləliklə HAVING SUM(field) > val kimi düzgün SQL yaranır
        for (FilterRow fr : havingFilterRows) {
            havingMap.computeIfAbsent(fr.field(), k -> new ArrayList<>()).add(fr);
        }

        for (FilterRow fr : filters) {
            // Alias prefix varsa ("t1.status") yalnız sahə adı ilə yoxla
            String fieldKey = fieldPart(fr.field());
            // "t.totalPrice" kimi cədvəl-alias prefiksli filter REAL sütuna aiddir —
            // eyni adlı aqreqat alias olsa belə HAVING-ə YOX, WHERE-ə getməlidir.
            // Aqreqat alias-ına filter yalnız prefixsiz ("totalPrice") yazılışla düşür.
            boolean prefixed = fr.field().contains(".");
            if (!prefixed && roundedAliasMap.containsKey(fieldKey))
                // ROUND(field, scale) filter — ayrıca işlənir
                roundedWhereFilters.add(new FilterRow(fieldKey, fr.op(), fr.value()));
            else if (!prefixed && aggAliases.contains(fieldKey))
                havingMap.computeIfAbsent(fieldKey, k -> new ArrayList<>())
                         .add(new FilterRow(fieldKey, fr.op(), fr.value()));
            else if (!prefixed && computedAliases.contains(fieldKey))
                (hasGroupBy ? computedHavingFilters : computedWhereFilters)
                        .add(new FilterRow(fieldKey, fr.op(), fr.value()));
            else if (!prefixed && concatAliases.contains(fieldKey))
                computedWhereFilters.add(new FilterRow(fieldKey, fr.op(), fr.value()));
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
            // Prefiksli ("t.totalPrice") → real sütun, WHERE (yuxarıdakı filters loop-u ilə eyni qayda)
            boolean prefixed = gf.aliasAndField().contains(".");
            if (!prefixed && roundedAliasMap.containsKey(fieldKey)) {
                // ROUND(field, scale) alias → aşağıdaki rounded-bloku WHERE ROUND(...) OP value kimi işləyəcək
                roundedWhereFilters.add(new FilterRow(fieldKey, gf.op(), gf.value()));
            } else if (!prefixed && aggAliases.contains(fieldKey)) {
                // Aqreqat alias → HAVING-ə əlavə et (step.having() ilə bağlanacaq)
                havingMap.computeIfAbsent(fieldKey, k -> new ArrayList<>())
                         .add(new FilterRow(fieldKey, gf.op(), gf.value()));
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

        // Heç bir aqreqat alias-ına uyğun gəlməyən HAVING şərtləri əvvəllər səssiz
        // itirdi — indi bare alias referansı ilə HAVING-ə düşür (generated mode-dakı
        // buildHavingCondition fallback-ının ekvivalenti).
        for (Map.Entry<String, List<FilterRow>> e : havingMap.entrySet()) {
            if (aggAliases.contains(e.getKey())) continue; // aşağıda step.having() ilə işlənir
            for (FilterRow hr : e.getValue()) {
                Condition c = aliasCondition(hr);
                if (c != null) builder.rawHaving(c);
            }
        }

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
                if (ar.postOps() != null && !ar.postOps().isEmpty()) step.withPostOps(ar.postOps());
                // HAVING — addHavingFilter(alias, Map) ilə verilir; bir neçə şərt AND ilə birləşir
                for (FilterRow hr : havingMap.getOrDefault(ar.alias(), List.of())) {
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

        // 1. JOIN cədvəlləri registry-yə + təxirə salınmış select/groupBy həlli
        registerJoinTablesForGenerated();
        resolveDeferredSelectAndGroupBy();
        dropShadowedRawSelectFields();

        // 2. SELECT — agg sütunlar + alias→expr xəritəsi (HAVING/filter üçün lazım)
        List<SelectFieldOrAsterisk> selectList = new ArrayList<>(rawSelectFields);
        Map<String, Field<?>> aggExprByAlias   = new LinkedHashMap<>();
        buildAggregateSelectColumns(mainTable, selectList, aggExprByAlias);

        // 3. ORDER BY tokenləri (çağırış sırası + orderSeq)
        applyOrderTokens();

        // 4. Hesablanan / ifadə sütunları — computed, concat, coalesce, selectAs
        buildComputedColumns(selectList, aggExprByAlias);
        buildComputedFieldColumns(mainTable, selectList, aggExprByAlias);
        buildConcatColumns(mainTable, selectList, aggExprByAlias);
        buildCoalesceColumns(selectList, aggExprByAlias);
        buildSelectAsColumns(selectList, aggExprByAlias);

        // 5. Təxirə salınmış WHERE filterləri — indi bütün alias-lar məlumdur
        resolveDeferredWhereFilters(aggExprByAlias);

        if (selectList.isEmpty()) selectList.add(DSL.asterisk());

        // 6. FROM + bütün JOIN növləri
        SelectJoinStep<Record> query = distinct
                ? dsl.selectDistinct(selectList).from(mainTable)
                : dsl.select(selectList).from(mainTable);
        query = applyGeneratedJoins(query, mainTable);

        // 7. Global filterlər (agg alias → HAVING, qalanı → WHERE)
        applyGlobalFilters(aggExprByAlias);

        // 8. WHERE
        Condition where = null;
        for (Condition c : rawConditions) where = (where == null) ? c : where.and(c);
        SelectConditionStep<Record> conditioned = (where != null)
                ? query.where(where)
                : query.where(DSL.trueCondition());

        // 9. GROUP BY
        SelectHavingStep<Record> grouped = rawGroupByFields.isEmpty()
                ? conditioned
                : conditioned.groupBy(rawGroupByFields);

        // 10. HAVING — raw + havingFilterRows (agg alias → ifadə ilə)
        Condition having = buildHavingCondition(aggExprByAlias);
        if (having != null) grouped = (SelectHavingStep<Record>) grouped.having(having);

        // 11. ORDER BY — sortRows
        for (SortRow sr : sortRows) {
            Field<?> f = resolveFieldByAlias(sr.field());
            if (f == null) f = DSL.field(DSL.name(fieldPart(sr.field())));
            rawOrderFields.add("DESC".equalsIgnoreCase(sr.dir()) ? f.desc() : f.asc());
        }

        SelectSeekStepN<Record> ordered = grouped.orderBy(rawOrderFields);

        // 12. COUNT (pagination üçün)
        // GROUP BY (+ HAVING) varsa COUNT mütləq "qruplaşdırılmış" nəticəni saymalıdır —
        // əks halda qruplaşdırmadan əvvəlki sətir sayı qaytarılır (səhv nəticə: list 1
        // sətir, count isə qruplaşmamış sətirlərin sayını — məs. 4 — göstərir).
        // "conditioned"/"grouped" artıq bütün JOIN-ləri özündə saxlayır.
        int rowCount = 0;
        if (paginate) {
            Select<Record> countSource = rawGroupByFields.isEmpty() ? conditioned : grouped;
            Record1<Integer> r = dsl.selectCount()
                    .from(countSource.asTable("_count"))
                    .fetchOne();
            rowCount = (r == null) ? 0 : r.value1();
        }

        // 13. LIMIT / OFFSET
        Select<Record> finalQuery = paginate
                ? ordered.limit(pageSize).offset((long) pageNumber * pageSize)
                : ordered;

        return new SelectTable(finalQuery, rowCount);
    }

    // ─── executeGenerated() addım metodları ──────────────────────────────

    /**
     * Bütün JOIN cədvəllərini {@code joinTableRegistry}-yə əvvəlcədən yazır —
     * filter / groupBy / agg / orderBy metodları bu registry-dən field resolve edir.
     */
    private void registerJoinTablesForGenerated() {
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
    }

    /** deferredSelectCols / deferredGroupByCols — registry artıq doludur, indi həll edilir. */
    private void resolveDeferredSelectAndGroupBy() {
        for (String col : deferredSelectCols) {
            Field<?> f = resolveFieldByAlias(col);
            if (f != null) rawSelectFields.add(f);
        }
        for (String col : deferredGroupByCols) {
            Field<?> f = resolveFieldByAlias(col);
            if (f != null) rawGroupByFields.add(f);
        }
    }

    /**
     * DUPLICATE ALIAS DEDUP.
     * Raw select sütunu (məs: {@code .select("d2.vatTotalPrice")}) öz native adını
     * ("vatTotalPrice") select list-də saxlayır. Əgər həmin ad sonradan
     * computed/agg/concat/coalesce/selectAs alias-ı kimi DƏ istifadə
     * olunubsa (məs: {@code .addComputedColumn(...).as("vatTotalPrice", 4)}),
     * derived table-da EYNİ adlı İKİ sütun yaranır → Postgres "column
     * reference ... is ambiguous" xətası verir. Explicit alias üstünlük
     * təşkil etməlidir — raw passthrough həmin halda select-dən çıxarılır.
     */
    private void dropShadowedRawSelectFields() {
        Set<String> reservedAliases = new HashSet<>();
        for (AggRow ar : aggRows) if (ar.alias() != null) reservedAliases.add(ar.alias());
        for (ComputedRow cr : computedCols) if (cr.alias() != null) reservedAliases.add(cr.alias());
        for (ComputedFieldEntry entry : computedFields) {
            if (entry.cf() != null && entry.cf().getAlias() != null) reservedAliases.add(entry.cf().getAlias());
        }
        for (ConcatRow cc : concatCols) if (cc.alias() != null) reservedAliases.add(cc.alias());
        for (CoalesceRow cc : coalesceCols) if (cc.alias() != null) reservedAliases.add(cc.alias());
        for (SelectAsRow sa : selectAsRows) if (sa.outputAlias() != null) reservedAliases.add(sa.outputAlias());
        if (!reservedAliases.isEmpty()) {
            rawSelectFields.removeIf(f -> reservedAliases.contains(f.getName()));
        }
    }

    /**
     * Aqreqat sütunları (aggRows) select list-ə əlavə edir və alias→ifadə
     * xəritəsini doldurur (HAVING / filter yönləndirməsi üçün lazımdır).
     */
    private void buildAggregateSelectColumns(Table<?> mainTable,
                                             List<SelectFieldOrAsterisk> selectList,
                                             Map<String, Field<?>> aggExprByAlias) {
        for (AggRow ar : aggRows) {
            if (ar.fn() == null) continue;
            if (ar.field() == null && ar.expr() == null) continue;

            Field<?> operand;

            if (ar.expr() != null) {
                // ─── aggOnComputed / addAggFunction(fn,field).add()...as(alias) ──
                operand = ar.expr().buildExprGenerated(mainTable, joinTableRegistry);
            } else {
                Field<?> baseField = resolveFieldByAlias(ar.field());
                if (baseField == null) baseField = DSL.field(DSL.name(fieldPart(ar.field())));

                operand = baseField;
                if (ar.mathOp() != null && ar.mathOp() != MathOp.NONOPERATION
                        && ar.mathField() != null) {
                    Field<?> mathF = resolveFieldByAlias(ar.mathField());
                    if (mathF == null) mathF = DSL.field(DSL.name(fieldPart(ar.mathField())));
                    Field<? extends Number> numBase = (Field<? extends Number>) baseField;
                    Field<? extends Number> numMath = (Field<? extends Number>) mathF;
                    operand = ar.mathOp().apply(numBase, numMath);
                }
            }

            Field<? extends Number> numOp = (Field<? extends Number>) operand;
            Field<?> aggField = switch (ar.fn()) {
                case SUM   -> DSL.sum(numOp);
                case COUNT -> DSL.count(operand);
                case AVG   -> DSL.avg(numOp);
                case MAX   -> DSL.max(operand);
                case MIN   -> DSL.min(operand);
            };
            // ROUND() yalnız ədədi tiplərə tətbiq edilə bilər — MAX/MIN tarix/string
            // sahə üzərində ola bilər, belə halda round səssizcə ötürülür.
            if (ar.round() != null && Number.class.isAssignableFrom(aggField.getType()))
                aggField = DSL.round((Field<? extends Number>) aggField, ar.round());

            // ─── POST-AGGREGATE əməliyyatlar — aqreqatdan KƏNARDA tətbiq olunur ───
            // Məs: COALESCE(ROUND(SUM(d1.totalPriceIn*d1.rate),4),0) - COALESCE(d3.paymentTotal,0)
            // (SUM(expr - field) YOX — artıq JOIN-lənmiş tək-qiymətli sahə qrupun sətir
            // sayına görə təkrar çıxarılmasın deyə, çıxma/toplama COALESCE-dən SONRA olur).
            Field<?> finalField = DSL.coalesce((Field<? extends Number>) aggField, DSL.val(0));
            for (AggregateBuilder.PostAggOp po : ar.postOps()) {
                Field<?> rawField = resolveFieldByAlias(po.field());
                if (rawField == null) rawField = DSL.field(DSL.name(fieldPart(po.field())));
                Field<?> opnd = po.nullAs() != null
                        ? DSL.coalesce((Field) rawField, DSL.val(po.nullAs()))
                        : rawField;
                Field<? extends Number> numFinal = (Field<? extends Number>) finalField;
                Field<? extends Number> numOpnd  = (Field<? extends Number>) opnd;
                finalField = po.op().apply(numFinal, numOpnd);
            }

            aggExprByAlias.put(ar.alias(), finalField);
            selectList.add(finalField.as(ar.alias()));
        }
    }

    /**
     * orderTokens → rawOrderFields.
     * {@code orderBy("field","dir")} və {@code addAggFunction(...).as(alias,...,orderDir)}
     * bir-birinə nəzərən hansı sırada çağırılıbsa, elə sırada tətbiq olunur —
     * YALNIZ açıq orderSeq verilməyibsə. orderSeq verilmiş meyarlar ASC
     * sıralanıb əvvələ keçir, stable sort sayəsində bərabər/boş seq-lər öz
     * çağırış sırasını saxlayır.
     */
    private void applyOrderTokens() {
        List<OrderToken> orderedTokens = new ArrayList<>(orderTokens);
        orderedTokens.sort((a, b) -> {
            if (a.seq() != null && b.seq() != null) return Integer.compare(a.seq(), b.seq());
            if (a.seq() != null) return -1;
            if (b.seq() != null) return 1;
            return 0;
        });
        for (OrderToken ot : orderedTokens) {
            Field<?> f = ot.isAlias()
                    ? DSL.field(DSL.name(ot.fieldOrAlias()))
                    : resolveFieldByAlias(ot.fieldOrAlias());
            if (f == null) continue;
            rawOrderFields.add("DESC".equalsIgnoreCase(ot.dir()) ? f.desc() : f.asc());
        }
    }

    /** computedCols — sadə 2-sahəli MathOp forma ({@code addComputedColumn(alias,...)}). */
    private void buildComputedColumns(List<SelectFieldOrAsterisk> selectList,
                                      Map<String, Field<?>> aggExprByAlias) {
        for (ComputedRow cr : computedCols) {
            Field<?> f1 = resolveFieldByAlias(cr.ta1() + "." + cr.f1());
            if (f1 == null) f1 = DSL.field(DSL.name(cr.ta1(), cr.f1()));
            Field<?> f2 = resolveFieldByAlias(cr.ta2() + "." + cr.f2());
            if (f2 == null) f2 = DSL.field(DSL.name(cr.ta2(), cr.f2()));

            if (cr.nullDefault() != null && cr.nullDefault() != NullDefault.NONE) {
                f1 = DSL.coalesce((Field) f1, DSL.val(cr.nullDefault().numericValue()));
                f2 = DSL.coalesce((Field) f2, DSL.val(cr.nullDefault().numericValue()));
            }

            Field<? extends Number> n1 = (Field<? extends Number>) f1;
            Field<? extends Number> n2 = (Field<? extends Number>) f2;
            // DIVIDE → NULLIF ilə sıfıra bölmə qorunması, qalanları ortaq MathOp.apply()
            Field<?> expr = (cr.op() == MathOp.DIVIDE)
                    ? n1.div((Field<? extends Number>) (Field<?>) DSL.nullif((Field) n2, 0))
                    : cr.op().apply(n1, n2);

            aggExprByAlias.put(cr.alias(), expr);
            selectList.add(expr.as(cr.alias()));
        }
    }

    /**
     * computedFields — ComputedField zənciri ({@code addComputedColumn(field).add()...as(alias)}).
     *
     * <p>DİQQƏT: map-ə raw (alias-sız) ifadə saxlanılır — bax: {@link #buildConcatColumns}
     * izahı. Əvvəllər cf.toFieldGenerated(...) (artıq .as(alias) tətbiq edilmiş)
     * həm map-ə, həm də inline filter-də DSL.field(DSL.name(alias)) ilə birbaşa
     * alias istinadı üçün istifadə olunurdu — Postgres-də SELECT alias-ları
     * HAVING-də görünmür, "column does not exist" yaranırdı.
     */
    private void buildComputedFieldColumns(Table<?> mainTable,
                                           List<SelectFieldOrAsterisk> selectList,
                                           Map<String, Field<?>> aggExprByAlias) {
        for (ComputedFieldEntry entry : computedFields) {
            ComputedField cf = entry.cf();
            if (cf == null) continue;

            Field<?> expr = cf.buildExprGenerated(mainTable, joinTableRegistry);
            String computedAlias = cf.getAlias();

            aggExprByAlias.put(computedAlias, expr);
            selectList.add(expr.as(computedAlias));

            if (entry.filterOp() != null && entry.filterValue() != null) {
                rawHavings.add(az.mbm.jooqsqlgenerate.strategy.FilterStrategies.get(entry.filterOp())
                        .apply((Field<Object>) expr, entry.filterValue()));
            }
        }
    }

    /**
     * concatCols — CONCAT(field/literal/if/coalesce, ...).
     *
     * <p>DİQQƏT: map-ə alias-sız (raw) ifadə qoyulur — alias-lanmış Field-i
     * saxlasaydıq, bu obyekt sonra HAVING/.filter() şərtində istifadə
     * ediləndə jOOQ onu sadəcə "alias" adı kimi render edir və Postgres
     * "column ... does not exist" xətası verir (SELECT alias-ları
     * WHERE/HAVING-də görünmür).
     */
    private void buildConcatColumns(Table<?> mainTable,
                                    List<SelectFieldOrAsterisk> selectList,
                                    Map<String, Field<?>> aggExprByAlias) {
        for (ConcatRow cc : concatCols) {
            List<Field<?>> parts = new ArrayList<>();
            for (int i = 0; i < cc.items().size(); i++) {
                if (i > 0 && cc.separator() != null && !cc.separator().isEmpty()) {
                    parts.add(DSL.inline(cc.separator()));
                }
                ConcatItem item = cc.items().get(i);
                if (item instanceof ConcatItem.Literal lit) {
                    parts.add(DSL.inline(lit.value()));
                } else if (item instanceof ConcatItem.ColField cf) {
                    Field<?> f = resolveFieldByAlias(cf.aliasAndField());
                    if (f == null) f = DSL.field(DSL.name(fieldPart(cf.aliasAndField())));
                    // CAST(... AS VARCHAR) vacibdir: sütun Long/Integer/Date və s. tipindədirsə,
                    // COALESCE(numericField, '') Postgres-də "COALESCE types ... cannot be
                    // matched" xətası verir — əvvəlcə mətnə çevrilməlidir.
                    parts.add(DSL.coalesce(f.cast(String.class), DSL.inline("")));
                } else if (item instanceof ConcatItem.IfItem ii) {
                    parts.add(DSL.coalesce(ii.expr().toFieldGenerated(mainTable, joinTableRegistry).cast(String.class), DSL.inline("")));
                } else if (item instanceof ConcatItem.CoalesceItem ci) {
                    parts.add(ci.expr().toFieldGenerated(mainTable, joinTableRegistry));
                }
            }
            Field<?> expr = DSL.concat(parts.toArray(new Field[0]));
            aggExprByAlias.put(cc.alias(), expr);
            selectList.add(expr.as(cc.alias()));
        }
    }

    /** coalesceCols — COALESCE(field1, field2, ..., default). Raw ifadə saxlanılır (bax: buildConcatColumns). */
    private void buildCoalesceColumns(List<SelectFieldOrAsterisk> selectList,
                                      Map<String, Field<?>> aggExprByAlias) {
        for (CoalesceRow cc : coalesceCols) {
            List<Field<?>> coalesceList = new ArrayList<>();
            boolean stringDefault = cc.def() instanceof String;
            for (String f : cc.fields()) {
                Field<?> rf = resolveFieldByAlias(f);
                if (rf == null) rf = DSL.field(DSL.name(fieldPart(f)));
                // Default mətn (String) olduqda CAST(... AS VARCHAR) — Postgres COALESCE tip uyğunlaşdırması.
                coalesceList.add(stringDefault ? rf.cast(String.class) : rf);
            }
            coalesceList.add(DSL.inline(cc.def()));
            Field<?> first = coalesceList.get(0);
            Field<?>[] rest = coalesceList.subList(1, coalesceList.size()).toArray(new Field[0]);
            Field<?> expr = DSL.coalesce(first, rest);
            aggExprByAlias.put(cc.alias(), expr);
            selectList.add(expr.as(cc.alias()));
        }
    }

    /** selectAsRows — {@code "alias.field" AS outputAlias}. Raw ifadə saxlanılır (bax: buildConcatColumns). */
    private void buildSelectAsColumns(List<SelectFieldOrAsterisk> selectList,
                                      Map<String, Field<?>> aggExprByAlias) {
        for (SelectAsRow sa : selectAsRows) {
            Field<?> f = resolveFieldByAlias(sa.aliasAndField());
            if (f == null) f = DSL.field(DSL.name(fieldPart(sa.aliasAndField())));
            aggExprByAlias.put(sa.outputAlias(), f);
            selectList.add(f.as(sa.outputAlias()));
        }
    }

    /**
     * deferredWhereFilterRows — {@code .filter(field,op,value)} həlli.
     * Bu mərhələdə joinTableRegistry (bütün JOIN növləri) və aggExprByAlias
     * (aqreqatlar + concat/coalesce/selectAs/computed alias-ları) tam doludur.
     */
    private void resolveDeferredWhereFilters(Map<String, Field<?>> aggExprByAlias) {
        if (deferredWhereFilterRows.isEmpty()) return;

        Set<String> aggregateAliasSet = new HashSet<>();
        for (AggRow ar : aggRows) aggregateAliasSet.add(ar.alias());

        for (FilterRow fr : deferredWhereFilterRows) {
            String key = fieldPart(fr.field());
            // "t.totalPrice" kimi prefiksli referans REAL sütuna aiddir —
            // heç bir output alias (rounded/agg/computed/concat) ilə qarışdırılmır.
            boolean prefixed = fr.field().contains(".");

            // ROUND(field, scale) AS alias yoxlaması (rounded SELECT sütunu)
            RoundedColumnRow rounded = prefixed ? null : roundedAliasMap.get(key);
            if (rounded != null) {
                Field<?> rf = resolveFieldByAlias(rounded.fieldRef());
                if (rf != null) {
                    Field<?> roundedField = DSL.round((Field<? extends Number>) rf, rounded.scale());
                    rawConditions.add(FilterStrategies
                            .get(fr.op()).apply((Field<Object>) roundedField, fr.value()));
                    continue;
                }
            }

            // Aqreqat / concat / coalesce / selectAs / computed alias-ı?
            // Yalnız prefixsiz referanslar alias sayılır.
            Field<?> expr = prefixed ? null : aggExprByAlias.get(key);
            if (expr != null) {
                Condition c = FilterStrategies
                        .get(fr.op()).apply((Field<Object>) expr, fr.value());
                // Əsl aqreqat (SUM/COUNT/AVG/...) WHERE-də işlənə bilməz — HAVING-ə yönəlir.
                // Qalanlar (concat/coalesce/selectAs/computed) sətir-əsaslıdır — WHERE-də qalır.
                if (aggregateAliasSet.contains(key)) rawHavings.add(c);
                else rawConditions.add(c);
                continue;
            }

            // Adi cədvəl sütunu (main və ya JOIN edilmiş) — registry artıq doludur
            Field<?> resolved = resolveFieldByAlias(fr.field());
            if (resolved == null) resolved = DSL.field(DSL.name(key));
            rawConditions.add(FilterStrategies
                    .get(fr.op()).apply((Field<Object>) resolved, fr.value()));
        }
    }

    /** Bütün JOIN növlərini (raw, entity, derived-table, extended) sorğuya tətbiq edir. */
    private SelectJoinStep<Record> applyGeneratedJoins(SelectJoinStep<Record> query,
                                                       Table<?> mainTable) {
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

        return query;
    }

    /**
     * GLOBAL FILTER — generated mode.
     * globalFilter(...) çağırışları ilə əlavə olunan filterlər — alias.field
     * joinTableRegistry-dən və ya əsas cədvəldən resolve edilir.
     * Aqreqat alias-ına uyğun gəlirsə HAVING-ə yönləndirilir, əks halda WHERE-ə.
     */
    private void applyGlobalFilters(Map<String, Field<?>> aggExprByAlias) {
        for (FiltersEntry gf : globalFilters) {
            String fieldKey = fieldPart(gf.aliasAndField());
            // "t.totalPrice" kimi prefiksli filter REAL sütuna aiddir —
            // heç bir output alias (rounded/agg/computed/concat) ilə qarışdırılmır.
            boolean prefixed = gf.aliasAndField().contains(".");

            // ROUND(field, scale) AS alias yoxlaması — direkt .filter() yolu ilə
            // eyni qaydada ROUND(...) ifadəsi WHERE-ə yazılır,
            // alias literal sütun kimi yanlış həll edilmir.
            RoundedColumnRow rounded = prefixed ? null : roundedAliasMap.get(fieldKey);
            if (rounded != null) {
                Field<?> rf = resolveFieldByAlias(rounded.fieldRef());
                if (rf != null) {
                    Field<?> roundedField = DSL.round((Field<? extends Number>) rf, rounded.scale());
                    Condition c = FilterStrategies.get(gf.op())
                            .apply((Field<Object>) roundedField, gf.value());
                    rawConditions.add(c);
                    continue;
                }
            }

            Field<?> aggExpr = prefixed ? null : aggExprByAlias.get(fieldKey);
            if (aggExpr != null) {
                Condition c = FilterStrategies.get(gf.op()).apply((Field<Object>) aggExpr, gf.value());
                rawHavings.add(c);
                continue;
            }
            Field<?> resolved = resolveFieldByAlias(gf.aliasAndField());
            if (resolved == null) resolved = DSL.field(DSL.name(fieldKey));
            Condition c = FilterStrategies.get(gf.op()).apply((Field<Object>) resolved, gf.value());
            rawConditions.add(c);
        }
    }

    /** HAVING şərtini qurur — rawHavings + havingFilterRows (agg alias → ifadə ilə). */
    private Condition buildHavingCondition(Map<String, Field<?>> aggExprByAlias) {
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
        return having;
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

    /**
     * Entity-mode SelectTable JOIN-ləri üçün bir "alias.field" istinadını həll edir.
     *
     * <p>Əvvəlcə {@code rawAliasMap}-ə baxır (artıq join edilmiş derived table alias-ları,
     * məs. "d2", "d3" — {@link EntityTable} reflection-ı keçilərək jOOQ-un öz
     * {@code table.field(name)} axtarışından istifadə olunur). Tapılmasa, {@code entityClassMap}-dəki
     * Class-based alias kimi həll edilir (köhnə davranışla eyni).
     */
    @SuppressWarnings("unchecked")
    private static Field<Object> resolveJoinFieldForAlias(
            String alias, String fieldName,
            Map<String, Class<?>> entityClassMap,
            Map<String, Table<?>> rawAliasMap,
            Class<?> defaultEntity) {

        Table<?> raw = rawAliasMap.get(alias);
        if (raw != null) {
            Field<?> f = raw.field(fieldName);
            if (f == null) f = DSL.field(DSL.name(alias, fieldName));
            return (Field<Object>) f;
        }
        Class<?> cls = entityClassMap.getOrDefault(alias, defaultEntity);
        EntityTable<?> table = new EntityTable<>(cls, alias);
        return (Field<Object>) table.getField(fieldName);
    }
}
