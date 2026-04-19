package az.mbm.jooqsqlgenerate;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.builder.AggregateBuilder;
import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.builder.ComputedField;
import az.mbm.jooqsqlgenerate.builder.SelectQueryBuilder;
import az.mbm.jooqsqlgenerate.builder.SubQueryIn;
import az.mbm.jooqsqlgenerate.builder.SubSelectBuilder;
import az.mbm.jooqsqlgenerate.core.SelectFetchJooq;
import az.mbm.jooqsqlgenerate.core.SelectTable;
import az.mbm.jooqsqlgenerate.enums.FilterOperations;
import az.mbm.jooqsqlgenerate.enums.GroupFunction;
import az.mbm.jooqsqlgenerate.enums.MathOperation;
import az.mbm.jooqsqlgenerate.spec.ExistsSpec;
import az.mbm.jooqsqlgenerate.spec.Filter;
import az.mbm.jooqsqlgenerate.spec.GlobalFilter;
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
 *             .filter("status", FilterOperations.EQUAl, status)
 *             .filter("name",   FilterOperations.LIKE,   name)
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
    private final List<Field<?>>            rawGroupByFields = new ArrayList<>();
    private final List<RawJoinRow>          rawJoins         = new ArrayList<>();

    private final List<String>              columns          = new ArrayList<>();
    private final List<ComputedField>       computedFields   = new ArrayList<>();
    private final List<ComputedRow>         computedCols     = new ArrayList<>();
    private final List<CoalesceRow>         coalesceCols     = new ArrayList<>();
    private final List<SubSelectBuilder>    subSelectCols    = new ArrayList<>();
    private final List<SubQueryInRow>       subQueryInCols   = new ArrayList<>();
    private final List<Condition>           rawConditions    = new ArrayList<>();
    private final List<Condition>           rawHavings       = new ArrayList<>();
    private final List<Field<?>>            rawSelectFields  = new ArrayList<>();
    private final List<SortField<?>>        rawOrderFields   = new ArrayList<>();
    private final List<FilterRow>           filters          = new ArrayList<>();
    private final List<GlobalFilterEntry>   globalFilters    = new ArrayList<>();
    private final List<JoinRow>             joins            = new ArrayList<>();
    private final List<ExistsSpec<?, ?>>    existsSpecs      = new ArrayList<>();
    private final List<ExistsSpec<?, ?>>    havingExistsSpecs = new ArrayList<>();
    private final List<String>              groupByFields    = new ArrayList<>();
    private final List<AggRow>              aggRows          = new ArrayList<>();
    private final List<SortRow>             sortRows         = new ArrayList<>();
    private final List<CaseRow>             caseRows         = new ArrayList<>();
    private final List<CaseBuilder<?>>      caseBuilders     = new ArrayList<>();

    private boolean distinct   = false;
    private boolean paginate   = true;
    private int     pageNumber = 0;
    private int     pageSize   = 50;

    // ─── Daxili record-lar ───────────────────────────────────────────────
    private record FilterRow(String field, FilterOperations op, Object value) {}
    private record JoinRow(String type, Class<?> entity, String alias,
                           String fromField, String toField) {}
    private record AggRow(GroupFunction fn, String field, String alias, Integer round,
                          FilterOperations havingOp, Object havingVal, String orderDir,
                          MathOperation mathOp, String mathField, ComputedField expr) {}
    private record SortRow(String field, String dir) {}
    private record CaseRow(String field, FilterOperations op, Object when,
                           Object then, Object els, String alias) {}
    private record ComputedRow(String alias,
                               String ta1, String f1, MathOperation op,
                               String ta2, String f2) {}
    private record CoalesceRow(String alias, Object def, String[] fields) {}
    private record SubQueryInRow(List<String> outerFields, SubQueryIn sub) {}
    private record GlobalFilterEntry(String aliasAndField, FilterOperations op, Object value) {}
    private record RawJoinRow(Table<?> table, JoinType type, Condition on) {}

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
     *       .filter("name", FilterOperations.LIKE, "Ali")  // sub.name LIKE '%Ali%'
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
                Field<?> f = resolveFromTable(generatedTable, fieldPart(col));
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
                Field<?> f = resolveFromTable(generatedTable, fieldPart(col));
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
                                       String ta1, MathOperation op, String f1,
                                       String ta2, String f2) {
        if (alias != null && f1 != null && f2 != null && op != null)
            computedCols.add(new ComputedRow(alias, ta1, f1, op, ta2, f2));
        return this;
    }

    /** Çox sahəli riyazi ifadə sütunu ({@link ComputedField} ilə). */
    public JooqQuery<T> computedColumn(ComputedField cf) {
        if (cf != null) computedFields.add(cf);
        return this;
    }

    /** COALESCE sütunu. */
    public JooqQuery<T> coalesce(String alias, Object defaultValue, String... fields) {
        if (alias != null && fields != null && fields.length > 0)
            coalesceCols.add(new CoalesceRow(alias, defaultValue, fields));
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

    /** SELECT DISTINCT */
    public JooqQuery<T> distinct() {
        this.distinct = true;
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  JOIN
    // ════════════════════════════════════════════════════════════════════

    /** LEFT JOIN — entity mode üçün (string field adları) */
    public JooqQuery<T> leftJoin(Class<?> entity, String alias,
                                 String fromField, String toField) {
        joins.add(new JoinRow("LEFT", entity, alias, fromField, toField));
        return this;
    }

    /** INNER JOIN — entity mode üçün (string field adları) */
    public JooqQuery<T> innerJoin(Class<?> entity, String alias,
                                  String fromField, String toField) {
        joins.add(new JoinRow("INNER", entity, alias, fromField, toField));
        return this;
    }

    /**
     * LEFT JOIN — generated table ilə, tip-təhlükəli ON şərti.
     *
     * <pre>{@code
     *   .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
     * }</pre>
     */
    public JooqQuery<T> leftJoin(Table<?> table, String alias, Condition on) {
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
        rawJoins.add(new RawJoinRow(table.as(alias), JoinType.JOIN, on));
        return this;
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
    public JooqQuery<T> filter(String field, FilterOperations op, Object value) {
        if (field == null || field.isBlank() || op == null || value == null) return this;
        if (value instanceof String s && s.isBlank()) return this;

        // Generated mode — sahəni generatedTable üzərindən həll et, FilterStrategies ilə Condition qur
        if (generatedTable != null) {
            Field<?> resolved = resolveFromTable(generatedTable, fieldPart(field));
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

        // Entity mode — köhnə davranış
        if (value instanceof Collection<?> c) {
            List<?> clean = c.stream().filter(Objects::nonNull).collect(Collectors.toList());
            if (clean.isEmpty()) return this;
            filters.add(new FilterRow(field, op, clean));
            return this;
        }
        filters.add(new FilterRow(field, op, value));
        return this;
    }

    /**
     * Global filter — {@link GlobalFilter} fluent builder ilə.
     *
     * <pre>{@code
     *   .globalFilter(
     *       GlobalFilter.of()
     *           .equal("status", "ACTIVE")
     *           .like("name", name)
     *           .greaterThan("o.amount", "100")
     *   )
     * }</pre>
     */
    public JooqQuery<T> globalFilter(GlobalFilter gf) {
        if (gf == null) return this;
        return globalFilter(gf.build());
    }

    /** Global filter — xam {@code Map<String, Map<String,String>>} ilə. */
    public JooqQuery<T> globalFilter(Map<String, Map<String, String>> map) {
        if (map == null || map.isEmpty()) return this;
        for (Map.Entry<String, Map<String, String>> opEntry : map.entrySet()) {
            FilterOperations op = JooqManager.parseOperationPublic(opEntry.getKey());
            if (op == null || opEntry.getValue() == null) continue;
            for (Map.Entry<String, String> fe : opEntry.getValue().entrySet()) {
                String field = fe.getKey();
                String raw   = fe.getValue();
                if (field == null || field.isBlank() || raw == null || raw.isBlank()) continue;
                Object value = (op == FilterOperations.IN || op == FilterOperations.NOT_IN)
                        ? Arrays.asList(raw.split(",")) : raw;
                if (op == FilterOperations.IS_EMPTY || op == FilterOperations.IS_NOT_EMPTY)
                    value = "__null_check__";
                globalFilters.add(new GlobalFilterEntry(field, op, value));
            }
        }
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
    public <V> JooqQuery<T> filter(Field<V> field, FilterOperations op, Object value) {
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
                Field<?> resolved = resolveFromTable(generatedTable, fieldPart(f));
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
                Field<?> resolved = resolveFromTable(generatedTable, fieldPart(f));
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

    /** SUM / COUNT / AVG / MIN / MAX aqreqat. */
    public JooqQuery<T> agg(GroupFunction fn, String field, String alias,
                            Integer round, FilterOperations havingOp, Object havingVal,
                            String orderDir) {
        if (fn != null && field != null && alias != null)
            aggRows.add(new AggRow(fn, field, alias, round,
                                   havingOp, havingVal, orderDir,
                                   MathOperation.NONOPERATION, null, null));
        return this;
    }

    /** Riyazi ifadəli aqreqat: SUM(f1 * f2). */
    public JooqQuery<T> aggWithMath(GroupFunction fn,
                                    String field, MathOperation mathOp, String mathField,
                                    String alias, Integer round,
                                    FilterOperations havingOp, Object havingVal,
                                    String orderDir) {
        if (fn != null && field != null && alias != null)
            aggRows.add(new AggRow(fn, field, alias, round,
                                   havingOp, havingVal, orderDir,
                                   mathOp, mathField, null));
        return this;
    }

    /** ComputedField üzərindəki aqreqat: SUM((price * qty) - discount). */
    public JooqQuery<T> aggOnComputed(GroupFunction fn, ComputedField expr,
                                      String alias, Integer round,
                                      FilterOperations havingOp, Object havingVal,
                                      String orderDir) {
        if (fn != null && expr != null && alias != null)
            aggRows.add(new AggRow(fn, null, alias, round,
                                   havingOp, havingVal, orderDir,
                                   null, null, expr));
        return this;
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

    // ════════════════════════════════════════════════════════════════════
    //  CASE WHEN
    // ════════════════════════════════════════════════════════════════════

    /** Sadə CASE WHEN ... THEN ... ELSE ... END AS alias. */
    public JooqQuery<T> caseWhen(String field, FilterOperations op,
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

    /** Səhifələməni söndürür — bütün nəticəni qaytarır. */
    public JooqQuery<T> noPagination() {
        this.paginate = false;
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
        for (ComputedField cf : computedFields)
            builder.computedColumn(cf);
        for (CoalesceRow cr : coalesceCols)
            builder.coalesce(cr.alias(), cr.def(), cr.fields());
        for (SubSelectBuilder sub : subSelectCols)
            builder.subSelect(sub);
        for (Field<?> rf : rawSelectFields)
            builder.rawSelectField(rf);
        if (distinct) builder.distinct();

        // JOIN
        for (JoinRow jr : joins) {
            if ("LEFT".equals(jr.type()))
                builder.leftJoin(jr.entity(), jr.alias()).on(jr.fromField()).equalsField(jr.toField());
            else
                builder.innerJoin(jr.entity(), jr.alias()).on(jr.fromField()).equalsField(jr.toField());
        }

        // WHERE — normal filterlər
        Set<String> aggAliases = new HashSet<>();
        for (AggRow ar : aggRows) aggAliases.add(ar.alias());
        Set<String> computedAliases = new HashSet<>();
        for (ComputedRow cr  : computedCols)   computedAliases.add(cr.alias());
        for (ComputedField cf : computedFields) if (cf.getAlias() != null) computedAliases.add(cf.getAlias());
        boolean hasGroupBy = !groupByFields.isEmpty() || !aggRows.isEmpty();

        List<FilterRow> whereFilters          = new ArrayList<>();
        Map<String, FilterRow> havingMap      = new LinkedHashMap<>();
        List<FilterRow> computedWhereFilters  = new ArrayList<>();
        List<FilterRow> computedHavingFilters = new ArrayList<>();

        for (FilterRow fr : filters) {
            if (aggAliases.contains(fr.field()))
                havingMap.put(fr.field(), fr);
            else if (computedAliases.contains(fr.field()))
                (hasGroupBy ? computedHavingFilters : computedWhereFilters).add(fr);
            else
                whereFilters.add(fr);
        }

        if (!whereFilters.isEmpty()) {
            Filter filter = Filter.of();
            for (FilterRow fr : whereFilters) applyFilter(filter, fr);
            Specification spec = filter.build();
            if (spec != null) builder.where(spec);
        }

        for (SubQueryInRow sir : subQueryInCols) builder.inSubQuery(sir.outerFields(), sir.sub());
        for (GlobalFilterEntry gf : globalFilters) builder.globalWhereFilter(gf.aliasAndField(), gf.op(), gf.value());
        for (Condition rc : rawConditions) builder.rawCondition(rc);
        for (ExistsSpec<?, ?> es : existsSpecs) builder.where((Specification) es);

        for (FilterRow fr : computedWhereFilters) { Condition c = aliasCondition(fr); if (c != null) builder.where(c); }

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
                if (ar.havingOp() != null) step.having(ar.havingOp(), ar.havingVal());
                else if (havingMap.containsKey(ar.alias())) {
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
        if (paginate) builder.page(pageNumber, pageSize);
        else          builder.noPagination();

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
    private SelectTable executeGenerated(DSLContext dsl) {

        // FROM — alias ilə
        Table<?> mainTable = generatedTable.as(alias);

        // SELECT
        List<SelectFieldOrAsterisk> selectList = new ArrayList<>(rawSelectFields);
        if (selectList.isEmpty()) selectList.add(DSL.asterisk());

        // FROM
        SelectJoinStep<Record> query = distinct
                ? dsl.selectDistinct(selectList).from(mainTable)
                : dsl.select(selectList).from(mainTable);

        // JOIN-lər
        for (RawJoinRow jr : rawJoins) {
            query = switch (jr.type()) {
                case LEFT_OUTER_JOIN  -> query.leftJoin(jr.table()).on(jr.on());
                case RIGHT_OUTER_JOIN -> query.rightJoin(jr.table()).on(jr.on());
                default               -> query.join(jr.table()).on(jr.on());
            };
        }

        // WHERE
        Condition where = null;
        for (Condition c : rawConditions) where = (where == null) ? c : where.and(c);
        SelectConditionStep<Record> conditioned = (where != null)
                ? query.where(where)
                : query.where(DSL.trueCondition());

        // GROUP BY
        SelectHavingStep<Record> grouped;
        if (!rawGroupByFields.isEmpty()) {
            grouped = conditioned.groupBy(rawGroupByFields);
        } else {
            grouped = conditioned;
        }

        // HAVING
        Condition having = null;
        for (Condition c : rawHavings) having = (having == null) ? c : having.and(c);
        if (having != null) grouped = (SelectHavingStep<Record>) grouped.having(having);

        // ORDER BY
        SelectSeekStepN<Record> ordered = grouped.orderBy(rawOrderFields);

        // COUNT (pagination üçün)
        int rowCount = 0;
        if (paginate) {
            Record1<Integer> r = dsl.selectCount()
                    .from(mainTable)
                    .where(where != null ? where : DSL.trueCondition())
                    .fetchOne();
            rowCount = (r == null) ? 0 : r.value1();
        }

        // LIMIT / OFFSET
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
}
