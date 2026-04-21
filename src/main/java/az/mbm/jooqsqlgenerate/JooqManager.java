package az.mbm.jooqsqlgenerate;

import org.jooq.*;
import org.jooq.Record;
import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.builder.ComputedField;
import az.mbm.jooqsqlgenerate.builder.SubQueryIn;
import az.mbm.jooqsqlgenerate.builder.SubSelectBuilder;
import az.mbm.jooqsqlgenerate.builder.UpdateQueryBuilder;
import az.mbm.jooqsqlgenerate.core.SelectFetchJooq;
import az.mbm.jooqsqlgenerate.core.SelectTable;
import az.mbm.jooqsqlgenerate.enums.FilterOperations;
import az.mbm.jooqsqlgenerate.enums.GroupFunction;
import az.mbm.jooqsqlgenerate.enums.MathOperation;
import az.mbm.jooqsqlgenerate.spec.ExistsSpec;
import az.mbm.jooqsqlgenerate.spec.Filter;
import az.mbm.jooqsqlgenerate.spec.GlobalFilter;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════
 * JOOQ MANAGER — {@link JooqQuery}-nin Spring wrapper-i.
 *
 * <p>Bütün icra məntiqi {@link JooqQuery}-dədir. JooqManager yalnız
 * Spring-ə uyğun {@code addXxx()} interfeysini saxlayır və daxilindəki
 * {@code JooqQuery} nümunəsinə delegate edir.
 *
 * <p>Hər {@code execute()} çağrışından sonra daxili {@code JooqQuery}
 * sıfırlanır — növbəti sorğu tamamilə təmiz başlayır.
 *
 * <pre>{@code
 * @Service
 * public class UserService {
 *
 *     @Autowired
 *     private JooqManager jooq;
 *
 *     public SelectTable search(String status, String name) {
 *         jooq.setMainTable(User.class, "u");
 *         jooq.addColumns("u.id", "u.name", "u.email");
 *         jooq.addFilter("status", FilterOperations.EQUAl, status);
 *         jooq.addFilter("name",   FilterOperations.LIKE,   name);
 *         return jooq.execute();
 *     }
 * }
 * }</pre>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class JooqManager {

    private final DSLContext dsl;

    /** Cari sorğunun bütün state-i burada — JooqManager özü heç nə saxlamır */
    @SuppressWarnings("rawtypes")
    private JooqQuery current;

    /** UPDATE üçün ayrıca filter siyahısı (JooqQuery SELECT-ə yönəlibdir) */
    private final List<UpdateFilterRow> updateFilters = new ArrayList<>();
    private record UpdateFilterRow(String field, FilterOperations op, Object value) {}

    public JooqManager(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "DSLContext null ola bilməz");
    }

    // ════════════════════════════════════════════════════════════════════
    //  ANA CƏDVƏL
    // ════════════════════════════════════════════════════════════════════

    /**
     * Ana cədvəl — entity mode (JPA annotasiyaları ilə, köhnə üsul).
     *
     * @param entity JPA entity class
     * @param alias  SQL alias ("u", "o", ...)
     */
    @SuppressWarnings("unchecked")
    public JooqManager setMainTable(Class<?> entity, String alias) {
        current = JooqQuery.from(
                (Class<Object>) Objects.requireNonNull(entity, "Entity null ola bilməz"),
                Objects.requireNonNull(alias, "Alias null ola bilməz")
        );
        updateFilters.clear();
        return this;
    }

    /**
     * Ana cədvəl — generated mode (jOOQ generated {@link Table} ilə, tövsiyə olunan).
     *
     * <p>Reflection yoxdur, EntityTable yoxdur. Field adı səhv yazılsa
     * <b>compile xətası</b> verir.
     *
     * <pre>{@code
     *   import static com.example.domain.jooq.Tables.*;
     *
     *   jooq.setMainTable(USERS, "u")
     *       .addColumns(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
     *       .addFilter(USERS.STATUS.eq("ACTIVE"))
     *       .addLeftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
     *       .addGroupBy(USERS.DEPARTMENT)
     *       .addOrderBy(USERS.CREATED_AT.desc())
     *       .execute();
     * }</pre>
     */
    public <R extends Record> JooqManager setMainTable(Table<R> table, String alias) {
        current = JooqQuery.from(
                Objects.requireNonNull(table, "Table null ola bilməz"),
                Objects.requireNonNull(alias, "Alias null ola bilməz")
        );
        updateFilters.clear();
        return this;
    }

    /**
     * Derived table mode — başqa bir {@link SelectTable} sorğusunu ana cədvəl kimi istifadə edir.
     *
     * <p>{@code FROM (SELECT ...) alias} quruluşu yaranır. Daxili sorğunun
     * sütunlarına string adları ilə müraciət etmək olur.
     *
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
     *   jooq.setMainTable(active, "sub")
     *       .addColumns("id", "name")
     *       .addFilter("name", FilterOperations.LIKE, "Ali")
     *       .addLeftJoin(ORDERS, "o", sub.field("id", Long.class).eq(ORDERS.USER_ID))
     *       .addOrderBy("name", "ASC")
     *       .execute();
     * }</pre>
     *
     * @param subQuery daxili sorğu (derived table kimi istifadə edilir)
     * @param alias    derived table-ın SQL alias adı
     */
    public JooqManager setMainTable(SelectTable subQuery, String alias) {
        current = JooqQuery.from(
                Objects.requireNonNull(subQuery, "SubQuery null ola bilməz"),
                Objects.requireNonNull(alias, "Alias null ola bilməz")
        );
        updateFilters.clear();
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SELECT
    // ════════════════════════════════════════════════════════════════════

    /** SELECT sütunlar. Format: "alias.field" */
    public JooqManager addColumns(String... cols) {
        q().select(cols);
        return this;
    }

    /**
     * SELECT sütunlar — dinamik {@link List<String>} ilə.
     *
     * <pre>{@code
     *   List<String> cols = request.getColumns(); // ["u.id", "u.name", "u.status"]
     *   jooq.addColumns(cols);
     * }</pre>
     */
    public JooqManager addColumns(List<String> cols) {
        q().select(cols);
        return this;
    }

    /**
     * SELECT sütunlar — generated {@link Field} varargs ilə.
     *
     * <pre>{@code
     *   jooq.addColumns(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
     * }</pre>
     */
    public JooqManager addColumns(Field<?>... fields) {
        q().select(fields);
        return this;
    }

    /**
     * SELECT sütunlar — dinamik {@code List<Field<?>>} ilə.
     *
     * <pre>{@code
     *   List<Field<?>> cols = List.of(USERS.ID, USERS.NAME, ORDERS.AMOUNT);
     *   jooq.addColumns(cols);
     * }</pre>
     */
    public JooqManager addColumnFields(List<? extends Field<?>> fields) {
        q().selectFields(fields);
        return this;
    }

    /** 2 sahəli riyazi ifadə: {@code (ta1.f1 OP ta2.f2) AS alias} */
    public JooqManager addComputedColumn(String alias,
                                         String tableAlias1, String field1,
                                         MathOperation op,
                                         String tableAlias2, String field2) {
        q().computedColumn(alias, tableAlias1, op, field1, tableAlias2, field2);
        return this;
    }

    /** Çox sahəli riyazi ifadə sütunu ({@link ComputedField} ilə). */
    public JooqManager addComputedField(ComputedField cf) {
        q().computedColumn(cf);
        return this;
    }

    /** COALESCE SELECT sütunu. */
    public JooqManager addCoalesceColumn(String alias, Object defaultValue, String... fields) {
        q().coalesce(alias, defaultValue, fields);
        return this;
    }

    /** SELECT-də scalar subquery sütunu. */
    public JooqManager addSubQueryColumn(SubSelectBuilder sub) {
        q().subSelect(sub);
        return this;
    }

    /** SELECT-ə birbaşa jOOQ {@link Field}. */
    public JooqManager addRawSelectField(Field<?> field) {
        q().rawSelect(field);
        return this;
    }

    /**
     * SELECT sütununa özəlləşdirilmiş çıxış alias verir — entity mode üçün.
     *
     * <pre>{@code
     *   jooq.setMainTable(Warehouse.class, "t")
     *       .addSelectAs("t1.fkProductId", "productId")
     *       .addSelectAs("t.operationDate", "date")
     *       .addLeftJoin(Product.class, "t1", "fkProductId", "id")
     *       .execute();
     * }</pre>
     *
     * @param aliasAndField sütun: {@code "tableAlias.javaFieldName"} formatında
     * @param outputAlias   SQL alias-ı (nəticədə bu ad görünür)
     */
    public JooqManager addSelectAs(String aliasAndField, String outputAlias) {
        q().selectAs(aliasAndField, outputAlias);
        return this;
    }

    /** SELECT DISTINCT */
    public JooqManager setDistinct() {
        q().distinct();
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  JOIN
    // ════════════════════════════════════════════════════════════════════

    /** LEFT JOIN — entity mode (string field adları) */
    public JooqManager addLeftJoin(Class<?> entity, String alias,
                                   String fromField, String toField) {
        q().leftJoin(entity, alias, fromField, toField);
        return this;
    }

    /** INNER JOIN — entity mode (string field adları) */
    public JooqManager addInnerJoin(Class<?> entity, String alias,
                                    String fromField, String toField) {
        q().innerJoin(entity, alias, fromField, toField);
        return this;
    }

    /**
     * LEFT JOIN — generated mode, tip-təhlükəli ON şərti.
     *
     * <pre>{@code
     *   jooq.addLeftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
     * }</pre>
     */
    public JooqManager addLeftJoin(Table<?> table, String alias, Condition on) {
        q().leftJoin(table, alias, on);
        return this;
    }

    /**
     * INNER JOIN — generated mode, tip-təhlükəli ON şərti.
     *
     * <pre>{@code
     *   jooq.addInnerJoin(ROLES, "r", USERS.ROLE_ID.eq(ROLES.ID))
     * }</pre>
     */
    public JooqManager addInnerJoin(Table<?> table, String alias, Condition on) {
        q().innerJoin(table, alias, on);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  WHERE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Dinamik filter — null / boş / boş kolleksiya olduqda <b>atlanır</b>.
     *
     * <pre>{@code
     *   jooq.addFilter("status",  FilterOperations.EQUAl, status);  // null → atlanır
     *   jooq.addFilter("roleId",  FilterOperations.IN,    roleIds);  // boş  → atlanır
     * }</pre>
     */
    public JooqManager addFilter(String field, FilterOperations op, Object value) {
        q().filter(field, op, value);
        updateFilters.add(new UpdateFilterRow(field, op, value)); // UPDATE üçün saxlanır
        return this;
    }

    /**
     * Generated field ilə filter — tip-təhlükəli.
     *
     * <pre>{@code
     *   jooq.addFilter(USERS.STATUS, EQUAl, "ACTIVE")
     *   jooq.addFilter(USERS.AGE,    GREATER_THAN, 18)
     * }</pre>
     */
    public <V> JooqManager addFilter(Field<V> field, FilterOperations op, Object value) {
        q().filter(field, op, value);
        return this;
    }

    /**
     * Birbaşa jOOQ {@link Condition} ilə filter — generated mode üçün ideal.
     *
     * <pre>{@code
     *   jooq.addFilter(USERS.STATUS.eq("ACTIVE"))
     *   jooq.addFilter(USERS.AGE.gt(18).and(USERS.DELETED_AT.isNull()))
     * }</pre>
     */
    public JooqManager addFilter(Condition condition) {
        q().filter(condition);
        return this;
    }

    /**
     * Xarici {@link GlobalFilter} builder-dən gələn filterlər.
     *
     * <pre>{@code
     *   jooq.addGlobalFilter(
     *       GlobalFilter.of()
     *           .equal("status", "ACTIVE")
     *           .like("name", name)
     *   );
     * }</pre>
     */
    public JooqManager addGlobalFilter(GlobalFilter globalFilter) {
        q().globalFilter(globalFilter);
        return this;
    }

    /** Xam {@code Map<String, Map<String,String>>} formatında global filter. */
    public JooqManager addGlobalFilter(Map<String, Map<String, String>> map) {
        q().globalFilter(map);
        return this;
    }

    /** WHERE field IN (SELECT ...) subquery. */
    public JooqManager addInSubQuery(String outerField, SubQueryIn sub) {
        q().inSubQuery(outerField, sub);
        return this;
    }

    /** WHERE (f1, f2, ...) IN (SELECT ...) composite subquery. */
    public JooqManager addInSubQuery(String[] outerFields, SubQueryIn sub) {
        q().inSubQuery(outerFields, sub);
        return this;
    }

    /** WHERE EXISTS / NOT EXISTS. */
    public JooqManager addExistsFilter(ExistsSpec<?, ?> spec) {
        q().exists(spec);
        return this;
    }

    /** WHERE NOT EXISTS — oxunaqlılıq üçün alias. */
    public JooqManager addNotExistsFilter(ExistsSpec<?, ?> spec) {
        return addExistsFilter(spec);
    }

    /** Birbaşa jOOQ {@link Condition} — WHERE-ə. */
    public JooqManager addRawCondition(Condition condition) {
        q().rawCondition(condition);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  GROUP BY / AGGREGATE / HAVING
    // ════════════════════════════════════════════════════════════════════

    /** GROUP BY sahələri. Format: "alias.field" */
    public JooqManager addGroupBy(String... fields) {
        q().groupBy(fields);
        return this;
    }

    /**
     * GROUP BY sahələri — dinamik {@code List<String>} ilə.
     *
     * <pre>{@code
     *   List<String> groups = request.getGroupBy(); // ["u.department", "u.status"]
     *   jooq.addGroupBy(groups);
     * }</pre>
     */
    public JooqManager addGroupBy(List<String> fields) {
        q().groupBy(fields);
        return this;
    }

    /**
     * GROUP BY — generated {@link Field} varargs ilə.
     *
     * <pre>{@code
     *   jooq.addGroupBy(USERS.DEPARTMENT, USERS.STATUS)
     * }</pre>
     */
    public JooqManager addGroupBy(Field<?>... fields) {
        q().groupBy(fields);
        return this;
    }

    /**
     * GROUP BY — dinamik {@code List<Field<?>>} ilə.
     *
     * <pre>{@code
     *   List<Field<?>> groups = List.of(USERS.DEPARTMENT, USERS.STATUS);
     *   jooq.addGroupByFields(groups);
     * }</pre>
     */
    public JooqManager addGroupByFields(List<? extends Field<?>> fields) {
        q().groupByFields(fields);
        return this;
    }

    /** Aqreqat funksiya (SUM, COUNT, AVG, MIN, MAX). */
    public JooqManager addAggFunction(GroupFunction fn, String field, String alias,
                                      Integer round,
                                      FilterOperations havingOp, Object havingVal,
                                      String orderDir) {
        q().agg(fn, field, alias, round, havingOp, havingVal, orderDir);
        return this;
    }

    /** Riyazi əməliyyatlı aqreqat: SUM(f1 * f2). */
    public JooqManager addAggFunctionWithMath(GroupFunction fn,
                                              String field, MathOperation mathOp, String mathField,
                                              String alias, Integer round,
                                              FilterOperations havingOp, Object havingVal,
                                              String orderDir) {
        q().aggWithMath(fn, field, mathOp, mathField, alias, round, havingOp, havingVal, orderDir);
        return this;
    }

    /** ComputedField üzərindəki aqreqat: SUM((price*qty) - discount). */
    public JooqManager addAggFunctionOnComputed(GroupFunction fn,
                                                ComputedField expr,
                                                String alias, Integer round,
                                                FilterOperations havingOp, Object havingVal,
                                                String orderDir) {
        q().aggOnComputed(fn, expr, alias, round, havingOp, havingVal, orderDir);
        return this;
    }

    /** HAVING EXISTS / NOT EXISTS. */
    public JooqManager addHavingExistsFilter(ExistsSpec<?, ?> spec) {
        q().havingExists(spec);
        return this;
    }

    /** HAVING NOT EXISTS — oxunaqlılıq üçün alias. */
    public JooqManager addHavingNotExistsFilter(ExistsSpec<?, ?> spec) {
        return addHavingExistsFilter(spec);
    }

    /** Birbaşa jOOQ {@link Condition} — HAVING-ə. */
    public JooqManager addRawHaving(Condition condition) {
        q().rawHaving(condition);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CASE WHEN
    // ════════════════════════════════════════════════════════════════════

    /** Sadə CASE WHEN ... THEN ... ELSE ... END AS alias. */
    public JooqManager addCaseColumn(String field, FilterOperations op,
                                     Object whenVal, Object thenVal,
                                     Object elseVal, String alias) {
        q().caseWhen(field, op, whenVal, thenVal, elseVal, alias);
        return this;
    }

    /** Mürəkkəb çox şərtli CASE WHEN. */
    public JooqManager addCaseBuilder(CaseBuilder<?> cb) {
        q().caseWhen(cb);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  ORDER BY / PAGE
    // ════════════════════════════════════════════════════════════════════

    /** ORDER BY field ASC/DESC. */
    public JooqManager addOrderBy(String field, String direction) {
        q().orderBy(field, direction);
        return this;
    }

    /**
     * ORDER BY — dinamik {@link Map} ilə: key=field, value=istiqamət.
     *
     * <pre>{@code
     *   Map<String, String> sorts = request.getSorts();
     *   // {"u.createdAt": "DESC", "u.name": "ASC"}
     *   jooq.addOrderBy(sorts);
     * }</pre>
     */
    public JooqManager addOrderBy(Map<String, String> sorts) {
        q().orderBy(sorts);
        return this;
    }

    /**
     * ORDER BY — generated {@link SortField} varargs ilə.
     *
     * <pre>{@code
     *   jooq.addOrderBy(USERS.CREATED_AT.desc(), USERS.NAME.asc())
     * }</pre>
     */
    public JooqManager addOrderBy(SortField<?>... fields) {
        q().orderBy(fields);
        return this;
    }

    /**
     * ORDER BY — dinamik {@code List<SortField<?>>} ilə.
     *
     * <pre>{@code
     *   List<SortField<?>> sorts = List.of(USERS.CREATED_AT.desc());
     *   jooq.addOrderByFields(sorts);
     * }</pre>
     */
    public JooqManager addOrderByFields(List<? extends SortField<?>> fields) {
        q().orderByFields(fields);
        return this;
    }

    /** ORDER BY birbaşa jOOQ {@link SortField} — tək element. */
    public JooqManager addRawOrderBy(SortField<?> sortField) {
        q().rawOrderBy(sortField);
        return this;
    }

    /** Səhifələmə. page — 0-dan başlayır. */
    public JooqManager setPage(int page, int size) {
        q().page(page, size);
        return this;
    }

    /** Səhifələməni söndürür — bütün nəticəni qaytarır. */
    public JooqManager noPagination() {
        q().noPagination();
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  EXECUTE
    // ════════════════════════════════════════════════════════════════════

    /**
     * SQL sorğusunu icra edir və {@link SelectTable} qaytarır.
     * Çağrışdan sonra state avtomatik sıfırlanır.
     */
    public SelectTable execute() {
        JooqQuery<?> q = q();
        try {
            return q.execute(dsl);
        } finally {
            reset();
        }
    }

    /**
     * UPDATE icra edir.
     *
     * <pre>{@code
     *   jooq.setMainTable(User.class, "u");
     *   jooq.addFilter("id", FilterOperations.EQUAl, userId);
     *   int rows = jooq.update("status", "INACTIVE");
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public int update(String setField, Object setValue) {
        if (current == null)
            throw new IllegalStateException("JooqManager: setMainTable() çağrılmayıb");
        if (setField == null || setValue == null)
            throw new IllegalArgumentException("SET sahəsi və dəyəri null ola bilməz");
        if (updateFilters.isEmpty())
            throw new IllegalStateException("JooqManager: WHERE olmadan UPDATE qadağandır");

        try {
            UpdateQueryBuilder ub = new UpdateQueryBuilder(current.entityClass(), dsl);
            ub.set(setField, setValue);

            Filter filter = Filter.of();
            for (UpdateFilterRow fr : updateFilters) applyFilter(filter, fr);
            ub.where(filter.build());

            return ub.execute();
        } finally {
            reset();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  FETCH YARDIMÇILARI
    // ════════════════════════════════════════════════════════════════════

    /**
     * Execute edib {@code List<Map<String, Object>>} qaytarır.
     *
     * <pre>{@code
     *   List<Map<String, Object>> rows = jooq
     *       .setMainTable(User.class, "u")
     *       .addColumns("u.id", "u.name")
     *       .addFilter("status", EQUAl, "ACTIVE")
     *       .fetchMaps();
     * }</pre>
     */
    public List<Map<String, Object>> fetchMaps() {
        return new SelectFetchJooq<>().fetchMaps(execute()).getList();
    }

    /**
     * Execute edib entity list qaytarır.
     *
     * <pre>{@code
     *   List<User> users = jooq
     *       .setMainTable(User.class, "u")
     *       .addFilter("status", EQUAl, "ACTIVE")
     *       .fetchInto(User.class);
     * }</pre>
     */
    public <E> List<E> fetchInto(Class<E> type) {
        return new SelectFetchJooq<E>().fetchCast(execute(), type).getList();
    }

    // ════════════════════════════════════════════════════════════════════
    //  RESET
    // ════════════════════════════════════════════════════════════════════

    /** State-i sıfırla — execute() avtomatik çağırır. */
    public JooqManager reset() {
        current = null;
        updateFilters.clear();
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  STATIC UTIL — JooqQuery tərəfindən də istifadə olunur
    // ════════════════════════════════════════════════════════════════════

    /**
     * Əməliyyat adını (String) {@link FilterOperations}-a çevirir.
     * {@code addGlobalFilter} üçün daxili yardımçı.
     */
    public static FilterOperations parseOperationPublic(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "equal", "eq", "equals"                             -> FilterOperations.EQUAl;
            case "notequal", "ne", "not_equal", "noteq"              -> FilterOperations.NOT_EQUAL;
            case "greaterthan", "gt"                                 -> FilterOperations.GREATER_THAN;
            case "greaterthanorequal", "gte", "greaterthanorequalto" -> FilterOperations.GREATER_THAN_OR_EQUAL_TO;
            case "lessthan", "lt"                                    -> FilterOperations.LESS_THAN;
            case "lessthanorequal", "lte", "lessthanorequalto"       -> FilterOperations.LESS_THAN_OR_EQUAL_TO;
            case "like"                                              -> FilterOperations.LIKE;
            case "startwith", "startswith", "starts"                 -> FilterOperations.START_WITH;
            case "endwith", "endswith", "ends"                       -> FilterOperations.END_WITH;
            case "in"                                                -> FilterOperations.IN;
            case "notin", "not_in"                                   -> FilterOperations.NOT_IN;
            case "between"                                           -> FilterOperations.BETWEEN;
            case "isnull", "isempty", "is_null", "is_empty"         -> FilterOperations.IS_EMPTY;
            case "isnotnull", "isnotempty", "is_not_null",
                 "is_not_empty"                                      -> FilterOperations.IS_NOT_EMPTY;
            case "regexp", "regex"                                   -> FilterOperations.REGEXP;
            case "notregexp", "notregex", "not_regexp"               -> FilterOperations.NOT_REGEXP;
            default -> null;
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  PRIVATE YARDIMÇILAR
    // ════════════════════════════════════════════════════════════════════

    @SuppressWarnings("rawtypes")
    private JooqQuery q() {
        if (current == null)
            throw new IllegalStateException("JooqManager: setMainTable() çağrılmayıb");
        return current;
    }

    @SuppressWarnings("unchecked")
    private void applyFilter(Filter filter, UpdateFilterRow fr) {
        if (fr.value() == null) return;
        switch (fr.op()) {
            case EQUAl                    -> filter.eq(fr.field(), fr.value());
            case NOT_EQUAL                -> filter.notEq(fr.field(), fr.value());
            case GREATER_THAN             -> filter.gt(fr.field(), fr.value());
            case GREATER_THAN_OR_EQUAL_TO -> filter.gte(fr.field(), fr.value());
            case LESS_THAN                -> filter.lt(fr.field(), fr.value());
            case LESS_THAN_OR_EQUAL_TO    -> filter.lte(fr.field(), fr.value());
            case LIKE                     -> filter.like(fr.field(), fr.value().toString());
            case IS_EMPTY                 -> filter.isNull(fr.field());
            case IS_NOT_EMPTY             -> filter.isNotNull(fr.field());
            case IN                       -> filter.in(fr.field(),
                    fr.value() instanceof Collection<?> c ? c : List.of(fr.value()));
            case NOT_IN                   -> filter.notIn(fr.field(),
                    fr.value() instanceof Collection<?> c ? c : List.of(fr.value()));
            default                       -> filter.eq(fr.field(), fr.value());
        }
    }
}
