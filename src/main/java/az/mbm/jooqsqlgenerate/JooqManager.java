package az.mbm.jooqsqlgenerate;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.RecordMapper;
import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.builder.ComputedField;
import az.mbm.jooqsqlgenerate.builder.SubQueryIn;
import az.mbm.jooqsqlgenerate.builder.SubSelectBuilder;
import az.mbm.jooqsqlgenerate.builder.UpdateQueryBuilder;
import az.mbm.jooqsqlgenerate.core.SelectFetchJooq;
import az.mbm.jooqsqlgenerate.core.SelectFetchMapResponse;
import az.mbm.jooqsqlgenerate.core.SelectFetchResponse;
import az.mbm.jooqsqlgenerate.core.SelectTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.enums.Agg;
import az.mbm.jooqsqlgenerate.enums.MathOperation;
import az.mbm.jooqsqlgenerate.spec.ExistsSpec;
import az.mbm.jooqsqlgenerate.spec.Filter;
import az.mbm.jooqsqlgenerate.spec.Filters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════
 * Spring bean deyil — hər sorğu üçün {@link JooqManagerFactory#create()} ilə
 * yeni nümunə yaradın. Bu şəkildə daxili vəziyyət (columns, filters, joins ...)
 * başqa sorğularla qarışmaz.
 *
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class UserService {
 *
 *     private final JooqManagerFactory jooqFactory;
 *
 *     public SelectFetchResponse<UserDto> search(String status) {
 *         return jooqFactory.create()
 *             .setMainTable(User.class, "u")
 *             .addColumns("u.id", "u.name")
 *             .addFilter("status", Op.EQUAl, status)
 *             .fetchInto(UserDto.class);
 *     }
 * }
 * }</pre>
 */
public class JooqManager {

    private final DSLContext dsl;

    /** Cari sorğunun bütün state-i burada — JooqManager özü heç nə saxlamır */
    @SuppressWarnings("rawtypes")
    private JooqQuery current;

    /** UPDATE üçün ayrıca filter siyahısı (JooqQuery SELECT-ə yönəlibdir) */
    private final List<UpdateFilterRow> updateFilters = new ArrayList<>();
    private record UpdateFilterRow(String field, Op op, Object value) {}

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
     *       .addFilter("name", Op.LIKE, "Ali")
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

    /** LEFT JOIN — entity mode (string field adları, tək cüt) */
    public JooqManager addLeftJoin(Class<?> entity, String alias,
                                   String fromField, String toField) {
        q().leftJoin(entity, alias, fromField, toField);
        return this;
    }

    /** INNER JOIN — entity mode (string field adları, tək cüt) */
    public JooqManager addInnerJoin(Class<?> entity, String alias,
                                    String fromField, String toField) {
        q().innerJoin(entity, alias, fromField, toField);
        return this;
    }

    /**
     * LEFT JOIN builder — çoxlu ON field cütü + əlavə ON şərtləri.
     *
     * <pre>{@code
     *   jooq.setMainTable(WarehouseFlow.class, "t")
     *       .addLeftJoin(Product.class, "t1")
     *           .on("fkProductId", "id")          // t.fkProductId = t1.id
     *           .on("companyId",   "companyId")    // t.companyId   = t1.companyId
     *           .andOn("status", Op.EQUAl, "A")  // AND t1.status = 'A'
     *       .done()
     *       .addColumns(...)
     *       .execute();
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public JoinSetup addLeftJoin(Class<?> entity, String alias) {
        return new JoinSetup(this, q().leftJoin(entity, alias));
    }

    /**
     * INNER JOIN builder — çoxlu ON field cütü + əlavə ON şərtləri.
     */
    @SuppressWarnings("unchecked")
    public JoinSetup addInnerJoin(Class<?> entity, String alias) {
        return new JoinSetup(this, q().innerJoin(entity, alias));
    }

    /**
     * JOIN fluent setup — {@link JooqQuery.JoinBuilder}-i {@link JooqManager}-ə bağlayır.
     * {@code done()} çağrıldıqda {@link JooqManager}-ə qayıdır.
     */
    public final class JoinSetup {
        private final JooqManager              manager;
        @SuppressWarnings("rawtypes")
        private final JooqQuery.JoinBuilder    inner;

        @SuppressWarnings("rawtypes")
        JoinSetup(JooqManager manager, JooqQuery.JoinBuilder inner) {
            this.manager = manager;
            this.inner   = inner;
        }

        /** ON şərti: ana cədvəl.fromField = join cədvəl.toField */
        @SuppressWarnings("unchecked")
        public JoinSetup on(String fromField, String toField) {
            inner.on(fromField, toField);
            return this;
        }

        /** ON şərti: konkret fromAlias.fromField = join cədvəl.toField */
        @SuppressWarnings("unchecked")
        public JoinSetup onFrom(String fromAlias, String fromField, String toField) {
            inner.onFrom(fromAlias, fromField, toField);
            return this;
        }

        /**
         * ON şərti: konkret fromAlias.fromField OP join cədvəl.toField
         *
         * <pre>{@code
         *   .addInnerJoin(RequestEntity.class, "r")
         *       .onFrom("t", "fkRequestId", Op.EQUAl, "id")
         *       .andOn("status", Op.EQUAl, "A")
         *       .done()
         * }</pre>
         */
        @SuppressWarnings("unchecked")
        public JoinSetup onFrom(String fromAlias, String fromField, Op op, String toField) {
            inner.onFrom(fromAlias, fromField, op, toField);
            return this;
        }

        /** JOIN ON-a əlavə dəyər şərti: join cədvəli.field OP value */
        @SuppressWarnings("unchecked")
        public JoinSetup andOn(String field, Op op, Object value) {
            inner.andOn(field, op, value);
            return this;
        }

        /** Builder-i tamamlayır, {@link JooqManager}-ə qayıdır. */
        @SuppressWarnings("unchecked")
        public JooqManager done() {
            inner.done();
            return manager;
        }
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

    /**
     * LEFT JOIN — başqa bir {@link SelectTable} ilə, string field adları (entity join kimi).
     *
     * <pre>{@code
     *   .addLeftJoin(budgetQuery, "b", "f.fkAccountId", "fkAccountId")
     *   // ON f."fkAccountId" = b."fkAccountId"
     * }</pre>
     */
    public JooqManager addLeftJoin(SelectTable subQuery, String alias,
                                    String fromField, String toField) {
        q().leftJoin(subQuery, alias, fromField, toField);
        return this;
    }

    /**
     * INNER JOIN — başqa bir {@link SelectTable} ilə, string field adları ilə.
     */
    public JooqManager addInnerJoin(SelectTable subQuery, String alias,
                                     String fromField, String toField) {
        q().innerJoin(subQuery, alias, fromField, toField);
        return this;
    }

    /**
     * LEFT JOIN builder — çoxlu ON field cütü + əlavə şərtlər.
     *
     * <pre>{@code
     *   .addLeftJoin(budgetQuery, "b")
     *       .on("f.fkAccountId", "fkAccountId")
     *       .on("f.fkCurrencyId", "fkCurrencyId")
     *       .andOn("status", Op.EQUAl, "A")
     *       .done()
     * }</pre>
     */
    public SelectJoinSetup addLeftJoin(SelectTable subQuery, String alias) {
        return new SelectJoinSetup(this, q().leftJoin(subQuery, alias));
    }

    public SelectJoinSetup addInnerJoin(SelectTable subQuery, String alias) {
        return new SelectJoinSetup(this, q().innerJoin(subQuery, alias));
    }

    public final class SelectJoinSetup {
        private final JooqManager                       manager;
        private final JooqQuery.SelectJoinBuilder<?>    inner;

        SelectJoinSetup(JooqManager manager, JooqQuery.SelectJoinBuilder<?> inner) {
            this.manager = manager;
            this.inner   = inner;
        }

        public SelectJoinSetup on(String fromField, String toField) {
            inner.on(fromField, toField);
            return this;
        }

        public SelectJoinSetup andOn(String field, Op op, Object value) {
            inner.andOn(field, op, value);
            return this;
        }

        public JooqManager done() {
            inner.done();
            return manager;
        }
    }

    /**
     * LEFT JOIN — başqa bir {@link SelectTable} ilə, raw jOOQ ON şərti.
     */
    public JooqManager addLeftJoin(SelectTable subQuery, String alias, Condition on) {
        q().leftJoin(subQuery, alias, on);
        return this;
    }

    /**
     * INNER JOIN — başqa bir {@link SelectTable} ilə, raw jOOQ ON şərti.
     */
    public JooqManager addInnerJoin(SelectTable subQuery, String alias, Condition on) {
        q().innerJoin(subQuery, alias, on);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  WHERE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Dinamik filter — null / boş / boş kolleksiya olduqda <b>atlanır</b>.
     *
     * <pre>{@code
     *   jooq.addFilter("status",  Op.EQUAl, status);  // null → atlanır
     *   jooq.addFilter("roleId",  Op.IN,    roleIds);  // boş  → atlanır
     * }</pre>
     */
    public JooqManager addFilter(String field, Op op, Object value) {
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
    public <V> JooqManager addFilter(Field<V> field, Op op, Object value) {
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
     * Filter — {@link Filters} fluent builder ilə.
     *
     * <pre>{@code
     *   jooq.addFilter(
     *       Filters.of()
     *           .equal("status", "ACTIVE")
     *           .like("name",    name)
     *           .greaterThan("o.amount", "100")
     *   );
     * }</pre>
     */
    public JooqManager addFilter(Filters dto) {
        q().globalFilter(dto);
        return this;
    }

    /**
     * Filter — tək field üçün {@code Map<String, String>} ilə.
     *
     * <p>{@code field} null / boş, {@code filters} null / boş olduqda atlanır.
     * Map daxilindəki null key, null və ya boş value avtomatik atlanır.
     *
     * <pre>{@code
     *   jooq.addFilter("o.amount", Map.of("greaterThan", "100", "lessThan", "500"));
     *   jooq.addFilter("u.status", Map.of("equal",  "ACTIVE"));
     *   jooq.addFilter("roleId",   Map.of("in",     "1,2,3"));
     * }</pre>
     *
     * @param field   sahə adı: {@code "alias.field"} və ya {@code "field"} formatında
     * @param filters əməliyyat adı → String dəyər cütləri
     */
    public JooqManager addFilter(String field, Map<String, String> filters) {
        q().globalFilter(field, filters);
        return this;
    }

    /**
     * Filter — field-first {@code Map<String, Map<String,String>>} strukturu.
     *
     * <p>Birbaşa JSON request body-dən gələn struktur üçün uygundur:
     * outer key = field adı, inner key = əməliyyat, inner value = dəyər.
     *
     * <pre>{@code
     *   // JSON: {"price": {"like": "10"}, "status": {"equal": "ACTIVE"}}
     *   jooq.addFilter(request.getFilterMap());
     * }</pre>
     */
    public JooqManager addFilter(Map<String, Map<String, String>> fieldMap) {
        q().globalFilter(fieldMap);
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

    /**
     * OR qrupu filter — sadə hal.
     * Eyni orGroupAlias-lı şərtlər OR ilə birləşir, nəticə AND ilə əsas WHERE-ə qoşulur.
     *
     * <pre>{@code
     *   // WHERE status = 'A' AND (actionType = 'IN' OR actionType = 'OUT')
     *   .addFilter("t.status", Op.EQUAl, "A")
     *   .addOrFilter("myOr", "t.actionType", Op.EQUAl, "IN")
     *   .addOrFilter("myOr", "t.actionType", Op.EQUAl, "OUT")
     * }</pre>
     */
    public JooqManager addOrFilter(String orGroupAlias, String aliasAndField, Op op, Object value) {
        q().orFilter(orGroupAlias, aliasAndField, op, value);
        return this;
    }

    /**
     * OR qrupu filter — mürəkkəb hal: (andGroup1 OR andGroup2).
     * Eyni andGroupAlias-lı şərtlər AND, fərqli andGroupAlias-lılar OR ilə birləşir.
     *
     * <pre>{@code
     *   // WHERE (field1='y' AND field2='z') OR (field3='a' AND field4='b')
     *   .addOrFilter("myOr", "andGroup1", "t.field1", Op.EQUAl, "y")
     *   .addOrFilter("myOr", "andGroup1", "t.field2", Op.EQUAl, "z")
     *   .addOrFilter("myOr", "andGroup2", "t.field3", Op.EQUAl, "a")
     *   .addOrFilter("myOr", "andGroup2", "t.field4", Op.EQUAl, "b")
     * }</pre>
     */
    public JooqManager addOrFilter(String orGroupAlias, String andGroupAlias, String aliasAndField, Op op, Object value) {
        q().orFilter(orGroupAlias, andGroupAlias, aliasAndField, op, value);
        return this;
    }

    /**
     * OR qrupu filter — fluent builder ilə çoxlu field əlavə etmək.
     *
     * <p>İlk field ilə builder-i başladır. Sonrakı field-lər {@code .add()} ilə
     * əlavə edilir, {@code .done()} ilə {@link JooqManager}-ə qayıdılır.
     *
     * <p>Eyni {@code conditionAlias} altındakı fərqli field-lər OR,
     * eyni field-in çoxlu op-ları AND ilə birləşir.
     *
     * <pre>{@code
     *   // WHERE (t.taskNo LIKE '%x%' OR t.carrierDescription LIKE '%x%' OR r.requestDescription LIKE '%x%')
     *   .addOrOperation("operation", "t", "taskNo",             operation)
     *   .add            (           "t", "carrierDescription",  operation)
     *   .add            (           "r", "requestDescription",  operation)
     *   .done()
     *
     *   // WHERE (t.amount > 100 OR t.amount IS NULL)
     *   .addOrOperation("ag", "t", "amount", Map.of("greaterThan", "100", "isNull", ""))
     *   .done()
     * }</pre>
     *
     * @param conditionAlias OR qrupunun adı
     * @param tableAlias     cədvəlin alias-ı
     * @param field          sahə adı (camelCase)
     * @param operations     əməliyyat adı → String dəyər cütləri
     */
    public OrOperationBuilder addOrOperation(String conditionAlias,
                                             String tableAlias,
                                             String field,
                                             Map<String, String> operations) {
        return new OrOperationBuilder(this, conditionAlias)
                .add(tableAlias, field, operations);
    }

    /**
     * Fluent OR qrupu builder — {@link #addOrOperation} ilə başlayır, {@link #done} ilə bitir.
     *
     * <pre>{@code
     *   jooq.addOrOperation("op", "t", "taskNo",            op)
     *       .add(            "t", "carrierDescription",      op)
     *       .add(            "r", "requestDescription",      op)
     *       .done()
     *       .addFilter(...)
     * }</pre>
     */
    public final class OrOperationBuilder {
        private final JooqManager manager;
        private final String      conditionAlias;

        OrOperationBuilder(JooqManager manager, String conditionAlias) {
            this.manager        = manager;
            this.conditionAlias = conditionAlias;
        }

        /**
         * OR qrupuna yeni field əlavə edir.
         *
         * @param tableAlias  cədvəlin alias-ı
         * @param field       sahə adı (camelCase)
         * @param operations  əməliyyat adı → String dəyər cütləri
         */
        public OrOperationBuilder add(String tableAlias,
                                      String field,
                                      Map<String, String> operations) {
            // andGroup = "tableAlias.field" → hər field öz OR branch-ında
            manager.applyOrOperation(conditionAlias, tableAlias + "." + field,
                                     tableAlias, field, operations);
            return this;
        }

        /** Builder-i tamamlayır, {@link JooqManager}-ə qayıdır. */
        public JooqManager done() {
            return manager;
        }
    }

    /**
     * Mürəkkəb OR/AND qruplaması üçün fluent builder.
     *
     * <p>Dəstəklənən məntiqlər:
     * <pre>
     *   x AND (y OR z)                   — sadə OR qrupu
     *   x AND (y OR (z AND f))           — OR içində AND alt-qrupu
     *   x AND ((a AND b) OR (c AND d))   — çoxlu AND alt-qrupları
     * </pre>
     *
     * <pre>{@code
     *   // x AND (y OR (z AND f))
     *   jooq.addFilter("t.status", Op.EQUAl, "ACTIVE")          // x
     *       .orGroup("G")
     *           .or("t", "name",   Map.of("like", "ali"))        // y  (öz OR branch-ı)
     *           .andBranch("zf")                                 // (z AND f)
     *               .add("t", "amount",  Map.of("gt",  "100"))   // z
     *               .add("t", "country", Map.of("eq",  "AZ"))    // f
     *           .end()
     *       .done()
     *
     *   // x AND (taskNo LIKE '%x%' OR carrier LIKE '%x%' OR request LIKE '%x%')
     *   jooq.orGroup("operation")
     *           .or("t", "taskNo",            operation)
     *           .or("t", "carrierDescription", operation)
     *           .or("r", "requestDescription", operation)
     *       .done()
     * }</pre>
     *
     * @param conditionAlias OR qrupunun adı — WHERE-ə AND ilə birləşir
     */
    public OrGroupBuilder orGroup(String conditionAlias) {
        return new OrGroupBuilder(this, conditionAlias);
    }

    /**
     * Fluent OR qrupu builder — {@link #orGroup} ilə başlayır.
     *
     * <ul>
     *   <li>{@link #or}         — sadə OR branch (field öz andGroup-unda)</li>
     *   <li>{@link #andBranch}  — AND alt-qrupu başladır → {@link AndBranchBuilder}</li>
     *   <li>{@link #done}       — {@link JooqManager}-ə qayıdır</li>
     * </ul>
     */
    public final class OrGroupBuilder {
        private final JooqManager manager;
        private final String      conditionAlias;
        private       int         orCounter = 0;  // hər .or() çağırışı unikal andGroup alır

        OrGroupBuilder(JooqManager manager, String conditionAlias) {
            this.manager        = manager;
            this.conditionAlias = conditionAlias;
        }

        /**
         * Sadə OR branch — {@code Map} ilə.
         *
         * <pre>{@code
         *   .or("t", "taskNo",             Map.of("like", "x"))
         *   .or("t", "carrierDescription", Map.of("like", "x"))
         *   // → (taskNo LIKE '%x%' OR carrierDescription LIKE '%x%')
         * }</pre>
         */
        public OrGroupBuilder or(String tableAlias, String field,
                                 Map<String, String> operations) {
            manager.applyOrOperation(conditionAlias, tableAlias + "." + field,
                                     tableAlias, field, operations);
            return this;
        }

        /**
         * Sadə OR branch — {@link Op} + dəyər ilə.
         *
         * <pre>{@code
         *   .or("t", "status", Op.EQUAl, "ACTIVE")
         *   .or("t", "amount", Op.GREATER_THAN, 100)
         *   // → (status = 'ACTIVE' OR amount > 100)
         * }</pre>
         *
         * @param value null olduqda şərt tətbiq edilmir
         */
        public OrGroupBuilder or(String tableAlias, String field, Op op, Object value) {
            if (tableAlias == null || field == null || op == null || value == null) return this;
            String aliasAndField = tableAlias + "." + field;
            // Hər .or() çağırışı unikal andGroup alır →
            // eyni field belə olsa OR-lanır (AND deyil)
            String uniqueAndGroup = aliasAndField + "_" + (orCounter++);
            manager.q().orFilter(conditionAlias, uniqueAndGroup, aliasAndField, op, value);
            return this;
        }

        /**
         * AND alt-qrupu başladır — eyni {@code branchAlias} altındakı field-lər AND,
         * bu alt-qrup isə digər OR branch-larla OR ilə birləşir.
         *
         * <pre>{@code
         *   .andBranch("zf")
         *       .add("t", "amount",  Map.of("greaterThan", "100"))
         *       .add("t", "country", Map.of("equal",       "AZ"))
         *   .end()
         *   // → (amount > 100 AND country = 'AZ')  — bu blok OR qrupunun bir branch-ıdır
         * }</pre>
         *
         * @param branchAlias AND alt-qrupunun unikal adı (orGroup içində)
         */
        public AndBranchBuilder andBranch(String branchAlias) {
            return new AndBranchBuilder(this, branchAlias);
        }

        /** Builder-i tamamlayır, {@link JooqManager}-ə qayıdır. */
        public JooqManager done() {
            return manager;
        }
    }

    /**
     * AND alt-qrupu builder — {@link OrGroupBuilder#andBranch} ilə başlayır.
     *
     * <p>Eyni {@code branchAlias} altındakı bütün field-lər AND ilə birləşir.
     * {@link #end()} çağrılanda {@link OrGroupBuilder}-ə qayıdılır.
     */
    public final class AndBranchBuilder {
        private final OrGroupBuilder parent;
        private final String         branchAlias;

        AndBranchBuilder(OrGroupBuilder parent, String branchAlias) {
            this.parent      = parent;
            this.branchAlias = branchAlias;
        }

        /**
         * AND alt-qrupuna field əlavə edir — {@code Map} ilə.
         *
         * <pre>{@code
         *   .add("t", "type", Map.of("equal", "IN"))
         * }</pre>
         */
        public AndBranchBuilder add(String tableAlias, String field,
                                    Map<String, String> operations) {
            parent.manager.applyOrOperation(parent.conditionAlias, branchAlias,
                                            tableAlias, field, operations);
            return this;
        }

        /**
         * AND alt-qrupuna field əlavə edir — {@link Op} + dəyər ilə.
         *
         * <pre>{@code
         *   .add("t", "type",   Op.EQUAl, "IN")
         *   .add("t", "status", Op.EQUAl, "ACTIVE")
         * }</pre>
         *
         * @param tableAlias cədvəlin alias-ı
         * @param field      sahə adı (camelCase)
         * @param op         müqayisə operatoru
         * @param value      null olduqda şərt tətbiq edilmir
         */
        public AndBranchBuilder add(String tableAlias, String field, Op op, Object value) {
            if (tableAlias == null || field == null || op == null || value == null) return this;
            String aliasAndField = tableAlias + "." + field;
            parent.manager.q().orFilter(parent.conditionAlias, branchAlias, aliasAndField, op, value);
            return this;
        }

        /** AND alt-qrupunu bağlayır, {@link OrGroupBuilder}-ə qayıdır. */
        public OrGroupBuilder end() {
            return parent;
        }
    }

    /**
     * OR qrupu şərtlərini {@link JooqQuery}-yə tətbiq edən daxili helper.
     *
     * @param conditionAlias  OR qrupunun adı
     * @param andGroup        AND alt-qrupunun adı (eyni adlılar AND-lənir, fərqlilər OR-lanır)
     * @param tableAlias      cədvəl alias-ı
     * @param field           sahə adı
     * @param operations      əməliyyat adı → String dəyər cütləri
     */
    private void applyOrOperation(String conditionAlias,
                                   String andGroup,
                                   String tableAlias,
                                   String field,
                                   Map<String, String> operations) {
        if (conditionAlias == null || conditionAlias.isBlank()) return;
        if (tableAlias     == null || tableAlias.isBlank())     return;
        if (field          == null || field.isBlank())          return;
        if (operations     == null || operations.isEmpty())     return;

        String aliasAndField = tableAlias + "." + field;
        String effectiveAndGroup = (andGroup != null && !andGroup.isBlank())
                ? andGroup : aliasAndField;

        for (Map.Entry<String, String> e : operations.entrySet()) {
            if (e.getKey() == null) continue;

            Op op = parseOperationPublic(e.getKey());
            if (op == null) continue;

            if (op == Op.IS_EMPTY || op == Op.IS_NOT_EMPTY) {
                q().orFilter(conditionAlias, effectiveAndGroup, aliasAndField, op, "__null_check__");
                continue;
            }

            String raw = e.getValue();
            if (raw == null || raw.isBlank()) continue;

            Object value = parseOrValue(op, raw);
            if (value == null) continue;

            q().orFilter(conditionAlias, effectiveAndGroup, aliasAndField, op, value);
        }
    }

    /**
     * String dəyərini Op-a uyğun struktural formata çevirir.
     *
     * <p>Həqiqi tip dönüşümü ({@code String → Integer, Long, LocalDate, ...})
     * SQL generasiya zamanı {@code FilterStrategies.coerced()} tərəfindən
     * field-in {@code DataType}-ına əsasən avtomatik edilir.
     * Bu metod yalnız struktural çevrilmə edir:
     * <ul>
     *   <li>{@code IN / NOT_IN}  — vergüllə ayrılmış siyahı → {@code List<String>}</li>
     *   <li>{@code BETWEEN}      — {@code "from,to"} → {@code Object[]{"from","to"}}</li>
     *   <li>Digərlər             — raw string (coercion sonradan baş verir)</li>
     * </ul>
     */
    private static Object parseOrValue(Op op, String raw) {
        return switch (op) {
            case IN, NOT_IN -> {
                // "A,B,C" → ["A", "B", "C"] — boş elementlər təmizlənir
                java.util.List<String> items = java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty() && !s.equalsIgnoreCase("null"))
                        .toList();
                yield items.isEmpty() ? null : items;
            }
            case BETWEEN -> {
                // "from,to" → Object[]{"from","to"} — FilterStrategies.BETWEEN anlar
                String[] parts = raw.split(",", 2);
                if (parts.length < 2) yield null;
                String from = parts[0].trim(), to = parts[1].trim();
                if (from.isEmpty() || from.equalsIgnoreCase("null") ||
                    to.isEmpty()   || to.equalsIgnoreCase("null"))   yield null;
                yield new Object[]{from, to};
            }
            // Bütün digər op-lar (EQUAl, LIKE, GT, LT, REGEXP, ...):
            // raw string ötürülür, FilterStrategies.coerced() DB tipinə çevirir
            default -> raw.trim().isEmpty() ? null : raw.trim();
        };
    }

    /**
     * Field-to-field WHERE şərti — iki cədvəl sütununu Op ilə müqayisə edir.
     *
     * <pre>{@code
     *   .addFieldFilter("t.fkTaskId",   Op.EQUAl,        "f.fkTaskId")
     *   .addFieldFilter("t.totalPrice", Op.GREATER_THAN,  "f.totalPrice")
     *   // → WHERE t."fk_task_id" = f."fk_task_id"
     *   //     AND t."total_price" > f."total_price"
     * }</pre>
     *
     * @param leftAliasAndField  sol tərəf: {@code "alias.field"}
     * @param op                 müqayisə operatoru (EQUAl, NOT_EQUAL, GREATER_THAN, ...)
     * @param rightAliasAndField sağ tərəf: {@code "alias.field"}
     */
    public JooqManager addFieldFilter(String leftAliasAndField, Op op, String rightAliasAndField) {
        q().fieldFilter(leftAliasAndField, op, rightAliasAndField);
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

    // ════════════════════════════════════════════════════════════════════
    //  AGREQATlar — SUM / COUNT / AVG / MIN / MAX
    //
    //  HAVING üçün bu metodlarda parametr yoxdur.
    //  Bunun əvəzinə addHavingFilter(alias, Map<String,String>) istifadə edin:
    //
    //    jooq.addAggFunction(SUM, "t.totalPrice", "totalPrice");
    //    jooq.addHavingFilter("totalPrice", Map.of("greaterThan", "1000"));
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sadə aqreqat funksiya.
     *
     * <pre>{@code
     *   jooq.addAggFunction(SUM,   "t.totalPrice", "totalPrice");
     *   jooq.addAggFunction(COUNT, "t.id",         "cnt");
     *   jooq.addAggFunction(AVG,   "t.amount",     "avgAmount");
     * }</pre>
     */
    public JooqManager addAggFunction(Agg fn, String field, String alias) {
        q().agg(fn, field, alias);
        return this;
    }

    /**
     * Aqreqat funksiya — onluq yuvarlama ilə.
     *
     * <pre>{@code
     *   jooq.addAggFunction(SUM, "t.totalPrice", "totalPrice", 2);  // ROUND(SUM(...), 2)
     * }</pre>
     */
    public JooqManager addAggFunction(Agg fn, String field, String alias, Integer round) {
        q().agg(fn, field, alias, round);
        return this;
    }

    /**
     * Aqreqat funksiya — ORDER BY istiqaməti ilə.
     *
     * <pre>{@code
     *   jooq.addAggFunction(SUM, "t.totalPrice", "totalPrice", "DESC");
     * }</pre>
     *
     * @param orderDir "ASC" və ya "DESC"
     */
    public JooqManager addAggFunction(Agg fn, String field, String alias, String orderDir) {
        q().agg(fn, field, alias, null, orderDir);
        return this;
    }

    /**
     * Aqreqat funksiya — yuvarlama + ORDER BY ilə.
     *
     * <pre>{@code
     *   jooq.addAggFunction(SUM, "t.totalPrice", "totalPrice", 2, "DESC");
     * }</pre>
     */
    public JooqManager addAggFunction(Agg fn, String field, String alias,
                                      Integer round, String orderDir) {
        q().agg(fn, field, alias, round, orderDir);
        return this;
    }

    /**
     * Riyazi ifadəli aqreqat: {@code SUM(field * mathField)}.
     *
     * <pre>{@code
     *   jooq.addAggFunctionWithMath(SUM, "t.price", MULTIPLY, "t.qty", "totalAmount");
     * }</pre>
     */
    public JooqManager addAggFunctionWithMath(Agg fn,
                                              String field, MathOperation mathOp, String mathField,
                                              String alias) {
        q().aggWithMath(fn, field, mathOp, mathField, alias);
        return this;
    }

    /**
     * Riyazi ifadəli aqreqat — yuvarlama ilə.
     *
     * <pre>{@code
     *   jooq.addAggFunctionWithMath(SUM, "t.price", MULTIPLY, "t.qty", "totalAmount", 2);
     * }</pre>
     */
    public JooqManager addAggFunctionWithMath(Agg fn,
                                              String field, MathOperation mathOp, String mathField,
                                              String alias, Integer round) {
        q().aggWithMath(fn, field, mathOp, mathField, alias, round);
        return this;
    }

    /**
     * {@link ComputedField} üzərindəki aqreqat: {@code SUM((price * qty) - discount)}.
     *
     * <pre>{@code
     *   ComputedField expr = ComputedField.of("t.price").multiply("t.qty").as("totalAmount");
     *   jooq.addAggFunctionOnComputed(SUM, expr, "totalAmount");
     * }</pre>
     */
    public JooqManager addAggFunctionOnComputed(Agg fn, ComputedField expr, String alias) {
        q().aggOnComputed(fn, expr, alias);
        return this;
    }

    /**
     * {@link ComputedField} üzərindəki aqreqat — yuvarlama ilə.
     *
     * <pre>{@code
     *   jooq.addAggFunctionOnComputed(SUM, expr, "totalAmount", 2);
     * }</pre>
     */
    public JooqManager addAggFunctionOnComputed(Agg fn, ComputedField expr,
                                                String alias, Integer round) {
        q().aggOnComputed(fn, expr, alias, round);
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

    /**
     * HAVING filter — aggregat alias üçün {@code Map<String, String>} ilə.
     *
     * <p>Null, boş map və boş dəyərlər atlanır.
     *
     * <pre>{@code
     *   // globalFilter-dən götürüb set et:
     *   jooq.addHavingFilter("totalPrice", dto.getFilters().remove("totalPrice"));
     *
     *   // Birbaşa:
     *   jooq.addHavingFilter("totalPrice", Map.of("greaterThan", "1000"));
     * }</pre>
     */
    /**
     * HAVING filter — aggregat alias üçün {@code Map<String, String>} ilə.
     *
     * <pre>{@code
     *   jooq.addHavingFilter("totalPrice", Map.of("greaterThan", "1000"));
     *   jooq.addHavingFilter("cnt",        Map.of("between",     "5,50"));
     * }</pre>
     */
    public JooqManager addHavingFilter(String field, Map<String, String> filters) {
        q().havingFilter(field, filters);
        return this;
    }

    /**
     * HAVING filter — GROUP BY sahəsi üçün əməliyyat + dəyər ilə.
     *
     * <p>Aqreqat funksiyasız GROUP BY-da olan sahəyə HAVING şərti tətbiq edir.
     * Null dəyər və ya boş string olduqda atlanır.
     *
     * <pre>{@code
     *   jooq.addHavingFilter("t.operationType", Op.EQUAl,        "SELL");
     *   jooq.addHavingFilter("t.status",        Op.NOT_EQUAL,     "PASSIVE");
     *   jooq.addHavingFilter("t.amount",        Op.GREATER_THAN,  100);
     *   jooq.addHavingFilter("t.category",      Op.IN,            List.of("A","B"));
     * }</pre>
     *
     * @param field sahə adı: {@code "alias.field"} və ya {@code "field"} formatında
     * @param op    filter əməliyyatı
     * @param value filter dəyəri (null / boş string → atlanır)
     */
    public JooqManager addHavingFilter(String field, Op op, Object value) {
        q().havingFilter(field, op, value);
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CASE WHEN
    // ════════════════════════════════════════════════════════════════════

    /** CASE WHEN — tam parametrlər (ELSE ilə). */
    public JooqManager addCaseColumn(String field, Op op,
                                     Object whenVal, Object thenVal,
                                     Object elseVal, String alias) {
        q().caseWhen(field, op, whenVal, thenVal, elseVal, alias);
        return this;
    }

    /** CASE WHEN — ELSE olmadan (null qaytarır). */
    public JooqManager addCaseColumn(String field, Op op,
                                     Object whenVal, Object thenVal,
                                     String alias) {
        q().caseWhen(field, op, whenVal, thenVal, null, alias);
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
     * ORDER BY — {@code List<Map<String, String>>} ilə.
     *
     * <p>Hər map-in tək entry-si: key = field adı, value = "ASC" və ya "DESC".
     * Sıralama siyahıdakı ardıcıllığa görə tətbiq olunur.
     *
     * <pre>{@code
     *   jooq.addOrderBy(List.of(
     *       Map.of("u.createdAt", "DESC"),
     *       Map.of("u.name",      "ASC")
     *   ));
     * }</pre>
     */
    public JooqManager addOrderBy(List<Map<String, String>> sorts) {
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

    /** Səhifələməni söndürür — bütün nəticəni qaytarır, COUNT işləmir. */
    public JooqManager noPagination() {
        q().noPagination();
        return this;
    }

    /**
     * Pagination olmadan yalnız COUNT sorğusunu aktiv edir.
     * LIMIT/OFFSET tətbiq olunmur, amma {@link #getLastRowCount()} dəyər qaytarır.
     */
    public JooqManager withCount() {
        q().withCount();
        return this;
    }

    public JooqManager skipCount() {
        q().skipCount();
        return this;
    }

    /** Yalnız COUNT sorğusu icra edilir, əsas data sorğusu işləmir. {@link #getLastRowCount()} ilə sayı al. */
    public JooqManager onlyCount() {
        q().onlyCount();
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
     *   jooq.addFilter("id", Op.EQUAl, userId);
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
     * Execute edib {@link SelectFetchMapResponse} qaytarır — siyahı + ümumi sətir sayı.
     *
     * <pre>{@code
     *   SelectFetchMapResponse resp = jooq
     *       .setMainTable(User.class, "u")
     *       .addColumns("u.id", "u.name")
     *       .addFilter("status", EQUAl, "ACTIVE")
     *       .setPage(0, 20)
     *       .fetchMaps();
     *
     *   List<Map<String, Object>> list  = resp.getList();
     *   int                       total = resp.getRowCount();
     * }</pre>
     */
    public SelectFetchMapResponse fetchMaps() {
        return new SelectFetchJooq<>().fetchMaps(execute());
    }

    /**
     * Execute edib {@link RecordMapper} ilə çevirərək {@link SelectFetchResponse} qaytarır.
     *
     * <p>Nəticədən həm siyahını, həm də pagination üçün ümumi sətir sayını almaq mümkündür:
     *
     * <pre>{@code
     *   SelectFetchResponse<MyDto> resp = jooq
     *       .setMainTable(WarehouseFlow.class, "t")
     *       .addColumns("t.id", "t1.productName")
     *       .setPage(0, 20)
     *       .fetchMapper(r -> new MyDto(
     *           r.get("id", String.class),
     *           r.get("product_name", String.class)
     *       ));
     *
     *   List<MyDto> list  = resp.getList();
     *   int         total = resp.getRowCount();
     * }</pre>
     */
    public <V> SelectFetchResponse<V> fetchMapper(RecordMapper<Record, V> mapper) {
        return new SelectFetchJooq<V>().fetchMapper(execute(), mapper);
    }

    /**
     * Execute edib {@link SelectTable} vasitəsilə {@link RecordMapper} ilə
     * {@link SelectFetchResponse} qaytarır.
     */
    public <V> SelectFetchResponse<V> fetchMapper(SelectTable selectTable, RecordMapper<Record, V> mapper) {
        return new SelectFetchJooq<V>().fetchMapper(selectTable, mapper);
    }

    /**
     * Execute edib {@code List<Map<String,Object>>} qaytarır.
     * Null dəyərlər {@code ""} ilə əvəzlənir — JSON-da field silinmir.
     *
     * <pre>{@code
     *   SelectFetchMapResponse resp = jooq
     *       .setMainTable(WarehouseFlow.class, "t")
     *       .addColumns("t.id", "t.productName", "t.price")
     *       .setPage(0, 20)
     *       .fetchMapsNullSafe();
     *   // → [{id: "...", productName: "", price: 55.0}, ...]
     *   List<Map<String, Object>> list  = resp.getList();
     *   int                       total = resp.getRowCount();
     * }</pre>
     */
    public SelectFetchMapResponse fetchMapsNullSafe() {
        SelectFetchMapResponse raw = new SelectFetchJooq<>().fetchMaps(execute());
        List<Map<String, Object>> cleaned = raw.getList().stream()
                .filter(Objects::nonNull)
                .map(JooqManager::replaceNulls)
                .toList();
        return new SelectFetchMapResponse(cleaned, raw.getRowCount());
    }

    /** Map-dəki null dəyərləri {@code ""} ilə əvəzləyir */
    private static Map<String, Object> replaceNulls(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(k, v == null ? "" : v));
        return result;
    }

    /**
     * Sorğu nəticəsini {@code Map<String, Object>}-ə birləşdirir.
     *
     * <p>Nəticə sətirləri {@code "key"} və {@code "value"} sütunlarına sahib olmalıdır.
     * Eyni key varsa <b>sonuncu</b> dəyər saxlanılır.
     *
     * <pre>{@code
     *   Map<String, Object> map = jooq
     *       .setMainTable(Config.class, "c")
     *       .addColumns("c.key", "c.value")
     *       .fetchMergedMap();
     *   // → {"theme": "dark", "lang": "az", ...}
     * }</pre>
     */
    public Map<String, Object> fetchMergedMap() {
        List<Map<String, Object>> list = new SelectFetchJooq<>().fetchMaps(execute()).getList();
        return list.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.containsKey("key") && m.containsKey("value"))
                .collect(Collectors.toMap(
                        m -> String.valueOf(m.get("key")),
                        m -> m.get("value"),
                        (v1, v2) -> v2
                ));
    }

    /**
     * Execute edib {@link SelectFetchResponse} qaytarır — entity siyahısı + ümumi sətir sayı.
     *
     * <pre>{@code
     *   SelectFetchResponse<User> resp = jooq
     *       .setMainTable(User.class, "u")
     *       .addFilter("status", EQUAl, "ACTIVE")
     *       .setPage(0, 20)
     *       .fetchInto(User.class);
     *
     *   List<User> list  = resp.getList();
     *   int        total = resp.getRowCount();
     * }</pre>
     */
    public <E> SelectFetchResponse<E> fetchInto(Class<E> type) {
        return new SelectFetchJooq<E>().fetchCast(execute(), type);
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
     * Əməliyyat adını (String) {@link Op}-a çevirir.
     * {@code addFilter} üçün daxili yardımçı.
     */
    public static Op parseOperationPublic(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "equal", "eq", "equals"                             -> Op.EQUAl;
            case "notequal", "ne", "not_equal", "noteq"              -> Op.NOT_EQUAL;
            case "greaterthan", "gt"                                 -> Op.GREATER_THAN;
            case "greaterthanorequal", "gte", "greaterthanorequalto" -> Op.GREATER_THAN_OR_EQUAL_TO;
            case "lessthan", "lt"                                    -> Op.LESS_THAN;
            case "lessthanorequal", "lte", "lessthanorequalto"       -> Op.LESS_THAN_OR_EQUAL_TO;
            case "like"                                              -> Op.LIKE;
            case "startwith", "startswith", "starts"                 -> Op.START_WITH;
            case "endwith", "endswith", "ends"                       -> Op.END_WITH;
            case "in"                                                -> Op.IN;
            case "notin", "not_in"                                   -> Op.NOT_IN;
            case "between"                                           -> Op.BETWEEN;
            case "isnull", "isempty", "is_null", "is_empty"         -> Op.IS_EMPTY;
            case "isnotnull", "isnotempty", "is_not_null",
                 "is_not_empty"                                      -> Op.IS_NOT_EMPTY;
            case "regexp", "regex"                                   -> Op.REGEXP;
            case "notregexp", "notregex", "not_regexp"               -> Op.NOT_REGEXP;

            // ─── ROUND müqayisə əməliyyatları ─────────────────────────────
            // WHERE ROUND(field, scale) OP value
            // Scale 0 — tam ədədə yuvarlama
            case "equalround0",                "eqround0"            -> Op.EQUAL_ROUND_0;
            case "notequalround0",             "neround0"            -> Op.NOT_EQUAL_ROUND_0;
            case "greaterthanround0",          "gtround0"            -> Op.GREATER_THAN_ROUND_0;
            case "greaterthanorequaltoround0", "gteround0"           -> Op.GREATER_THAN_OR_EQUAL_TO_ROUND_0;
            case "lessthanround0",             "ltround0"            -> Op.LESS_THAN_ROUND_0;
            case "lessthanorequaltoround0",    "lteround0"           -> Op.LESS_THAN_OR_EQUAL_TO_ROUND_0;
            // Scale 1
            case "equalround1",                "eqround1"            -> Op.EQUAL_ROUND_1;
            case "notequalround1",             "neround1"            -> Op.NOT_EQUAL_ROUND_1;
            case "greaterthanround1",          "gtround1"            -> Op.GREATER_THAN_ROUND_1;
            case "greaterthanorequaltoround1", "gteround1"           -> Op.GREATER_THAN_OR_EQUAL_TO_ROUND_1;
            case "lessthanround1",             "ltround1"            -> Op.LESS_THAN_ROUND_1;
            case "lessthanorequaltoround1",    "lteround1"           -> Op.LESS_THAN_OR_EQUAL_TO_ROUND_1;
            // Scale 2
            case "equalround2",                "eqround2"            -> Op.EQUAL_ROUND_2;
            case "notequalround2",             "neround2"            -> Op.NOT_EQUAL_ROUND_2;
            case "greaterthanround2",          "gtround2"            -> Op.GREATER_THAN_ROUND_2;
            case "greaterthanorequaltoround2", "gteround2"           -> Op.GREATER_THAN_OR_EQUAL_TO_ROUND_2;
            case "lessthanround2",             "ltround2"            -> Op.LESS_THAN_ROUND_2;
            case "lessthanorequaltoround2",    "lteround2"           -> Op.LESS_THAN_OR_EQUAL_TO_ROUND_2;
            // Scale 3
            case "equalround3",                "eqround3"            -> Op.EQUAL_ROUND_3;
            case "notequalround3",             "neround3"            -> Op.NOT_EQUAL_ROUND_3;
            case "greaterthanround3",          "gtround3"            -> Op.GREATER_THAN_ROUND_3;
            case "greaterthanorequaltoround3", "gteround3"           -> Op.GREATER_THAN_OR_EQUAL_TO_ROUND_3;
            case "lessthanround3",             "ltround3"            -> Op.LESS_THAN_ROUND_3;
            case "lessthanorequaltoround3",    "lteround3"           -> Op.LESS_THAN_OR_EQUAL_TO_ROUND_3;
            // Scale 4
            case "equalround4",                "eqround4"            -> Op.EQUAL_ROUND_4;
            case "notequalround4",             "neround4"            -> Op.NOT_EQUAL_ROUND_4;
            case "greaterthanround4",          "gtround4"            -> Op.GREATER_THAN_ROUND_4;
            case "greaterthanorequaltoround4", "gteround4"           -> Op.GREATER_THAN_OR_EQUAL_TO_ROUND_4;
            case "lessthanround4",             "ltround4"            -> Op.LESS_THAN_ROUND_4;
            case "lessthanorequaltoround4",    "lteround4"           -> Op.LESS_THAN_OR_EQUAL_TO_ROUND_4;

            // ─── Türk əlifbası case-insensitive LIKE ──────────────────────
            case "likeignorecase",       "like_ignore_case",  "ilike" -> Op.LIKE_IGNORE_CASE;
            case "startwithignorecase",  "start_with_ignore_case"     -> Op.START_WITH_IGNORE_CASE;
            case "endwithignorecase",    "end_with_ignore_case"       -> Op.END_WITH_IGNORE_CASE;

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
