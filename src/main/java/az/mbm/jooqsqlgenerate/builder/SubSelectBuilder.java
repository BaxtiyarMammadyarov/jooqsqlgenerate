package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.spec.Specification;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.*;

/**
 * FLUENT BUILDER — SELECT listdə scalar subquery.
 *
 * <p>Hər bir instansiya bir subquery sütununu təmsil edir.
 * İçindəki SELECT ifadəsi: sadə sahə, CONCAT, COALESCE, və ya CASE WHEN ola bilər.
 *
 * <pre>{@code
 *   // SELECT (SELECT p.name FROM products p WHERE p.id = o.product_id) AS productName
 *   SubSelectBuilder.from(Product.class, "p")
 *       .select("p.name")
 *       .correlateOn("p.id", "o.productId")
 *       .as("productName")
 *
 *   // SELECT (SELECT COALESCE(u.nickname, u.firstName, 'Naməlum')
 *   //         FROM users u WHERE u.id = o.userId) AS buyerName
 *   SubSelectBuilder.from(User.class, "u2")
 *       .selectCoalesce("Unknown", "u2.nickname", "u2.firstName")
 *       .correlateOn("u2.id", "o.userId")
 *       .as("buyerName")
 *
 *   // SELECT (SELECT CONCAT(u.firstName, ' ', u.lastName)
 *   //         FROM users u WHERE u.id = o.userId) AS buyerFullName
 *   SubSelectBuilder.from(User.class, "u2")
 *       .selectConcat(" ", "u2.firstName", "u2.lastName")
 *       .correlateOn("u2.id", "o.userId")
 *       .as("buyerFullName")
 *
 *   // SELECT (SELECT CASE WHEN u.role = 'ADMIN' THEN 'İdarəçi' ELSE 'İstifadəçi' END
 *   //         FROM users u WHERE u.id = o.userId) AS buyerRole
 *   SubSelectBuilder.from(User.class, "u2")
 *       .selectCase(
 *           CaseBuilder.when("role", Op.EQUAl, "ADMIN").then("İdarəçi")
 *                      .otherwise("İstifadəçi")
 *       )
 *       .correlateOn("u2.id", "o.userId")
 *       .as("buyerRole")
 * }</pre>
 */
public class SubSelectBuilder {

    // ─── İfadə növü ──────────────────────────────────────────────────────
    private enum ExprType { FIELD, CONCAT, COALESCE, CASE }

    // ─── Subquery cədvəli ────────────────────────────────────────────────
    private final Class<?>  entityClass;
    private final String    tableAlias;

    // ─── SELECT ifadəsi ──────────────────────────────────────────────────
    private ExprType   exprType    = ExprType.FIELD;
    private String     selectField = null;           // FIELD üçün "alias.field"

    private String        concatSeparator = "";      // CONCAT üçün
    private List<String>  concatFields    = new ArrayList<>();

    private List<String>  coalesceFields  = new ArrayList<>();  // COALESCE üçün
    private Object        coalesceDefault = null;

    private CaseBuilder<?> caseBuilder = null;       // CASE WHEN üçün

    // ─── Korrelyasiya ────────────────────────────────────────────────────
    // (subquery_alias.field = outer_alias.field) şərti
    private final List<CorrelateRow> correlations = new ArrayList<>();

    private record CorrelateRow(String innerField, String outerField) {}

    // ─── Əlavə WHERE filterlər ───────────────────────────────────────────
    private final List<FilterRow> filterRows = new ArrayList<>();

    private record FilterRow(String field, Op op, Object value) {}

    // ─── Nəticə alias ────────────────────────────────────────────────────
    private String alias = null;

    // ─── Konstruktor ─────────────────────────────────────────────────────

    private SubSelectBuilder(Class<?> entityClass, String tableAlias) {
        this.entityClass = Objects.requireNonNull(entityClass, "Entity null ola bilməz");
        this.tableAlias  = Objects.requireNonNull(tableAlias,  "Alias null ola bilməz");
    }

    /** Subquery üçün entity cədvəli */
    public static SubSelectBuilder from(Class<?> entity, String alias) {
        return new SubSelectBuilder(entity, alias);
    }

    // ════════════════════════════════════════════════════════════════════
    //  SELECT ifadəsi seçimi
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sadə sahə seç.
     *
     * <pre>{@code .select("p.name") }</pre>
     */
    public SubSelectBuilder select(String tableAliasAndField) {
        this.exprType    = ExprType.FIELD;
        this.selectField = tableAliasAndField;
        return this;
    }

    /**
     * {@code CONCAT(field1, separator, field2, ...)} ifadəsi.
     *
     * <pre>{@code .selectConcat(" ", "u.firstName", "u.lastName") }</pre>
     *
     * @param separator sahələr arasına qoyulan sabit mətn
     * @param fields    "alias.field" formatında sahələr
     */
    public SubSelectBuilder selectConcat(String separator, String... fields) {
        this.exprType         = ExprType.CONCAT;
        this.concatSeparator  = separator != null ? separator : "";
        this.concatFields     = Arrays.asList(fields);
        return this;
    }

    /**
     * {@code COALESCE(field1, field2, ..., default)} ifadəsi.
     * İlk null olmayan dəyəri qaytarır; hamısı null-dursa {@code defaultValue}.
     *
     * <pre>{@code .selectCoalesce("Naməlum", "u.nickname", "u.firstName") }</pre>
     *
     * @param defaultValue hamısı null olduqda qaytarılacaq sabit dəyər
     * @param fields       "alias.field" formatında sahələr (sıra ilə yoxlanır)
     */
    public SubSelectBuilder selectCoalesce(Object defaultValue, String... fields) {
        this.exprType        = ExprType.COALESCE;
        this.coalesceFields  = Arrays.asList(fields);
        this.coalesceDefault = defaultValue;
        return this;
    }

    /**
     * {@code CASE WHEN ... THEN ... ELSE ... END} ifadəsi.
     *
     * <pre>{@code
     *   .selectCase(
     *       CaseBuilder.when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
     *                  .andWhen("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
     *                  .otherwise("Naməlum")
     *   )
     * }</pre>
     */
    public SubSelectBuilder selectCase(CaseBuilder<?> cb) {
        this.exprType    = ExprType.CASE;
        this.caseBuilder = Objects.requireNonNull(cb, "CaseBuilder null ola bilməz");
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Korrelyasiya (outer query ilə əlaqə)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Subquery-i əsas sorğuya bağlayan şərt.
     *
     * <pre>{@code
     *   // WHERE p.id = o.product_id
     *   .correlateOn("p.id", "o.productId")
     * }</pre>
     *
     * @param innerField subquery-nin sahəsi ("alias.field")
     * @param outerField əsas sorğunun sahəsi ("alias.field")
     */
    public SubSelectBuilder correlateOn(String innerField, String outerField) {
        correlations.add(new CorrelateRow(innerField, outerField));
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Əlavə WHERE filterlər
    // ════════════════════════════════════════════════════════════════════

    /**
     * Subquery-yə əlavə WHERE şərti.
     *
     * <pre>{@code .addFilter("p.active", Op.EQUAl, true) }</pre>
     */
    public SubSelectBuilder addFilter(String field, Op op, Object value) {
        if (field != null && !field.isBlank() && op != null && value != null)
            filterRows.add(new FilterRow(field, op, value));
        return this;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Alias
    // ════════════════════════════════════════════════════════════════════

    /**
     * SELECT-dəki alias — mütləq tələb olunur.
     *
     * <pre>{@code .as("productName") }</pre>
     */
    public SubSelectBuilder as(String alias) {
        Objects.requireNonNull(alias, "SubSelectBuilder alias null ola bilməz");
        int dot = alias.indexOf('.');
        this.alias = dot >= 0 ? alias.substring(dot + 1) : alias;
        return this;
    }

    public String getAlias() { return alias; }

    // ════════════════════════════════════════════════════════════════════
    //  jOOQ Field-ə çevirmə
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builder-i jOOQ scalar subquery {@link Field}-ə çevirir.
     *
     * @param outerTableMap əsas sorğunun alias → EntityTable xəritəsi
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> toField(Map<String, EntityTable<?>> outerTableMap) {
        if (alias == null)
            throw new IllegalStateException("SubSelectBuilder: .as(alias) tələb olunur");

        EntityTable<?> innerTable = new EntityTable<>(entityClass, tableAlias);

        // ─── SELECT ifadəsini qur ─────────────────────────────────────
        Field<?> selectExpr = buildSelectExpr(innerTable);

        // ─── WHERE şərdi: korrelyasiya + əlavə filterlər ─────────────
        Condition whereCondition = buildWhereCondition(innerTable, outerTableMap);

        // ─── Subquery: SELECT expr FROM table WHERE ... ───────────────
        SelectConditionStep<?> subQuery = DSL
                .select(selectExpr)
                .from(innerTable.getTable())
                .where(whereCondition != null ? whereCondition : DSL.trueCondition());

        return DSL.field((Name) subQuery).as(alias);
    }

    // ─── SELECT ifadəsi ──────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> buildSelectExpr(EntityTable<?> innerTable) {
        return switch (exprType) {

            case FIELD -> {
                if (selectField == null)
                    throw new IllegalStateException("SubSelectBuilder: .select() çağrılmayıb");
                yield resolveField(selectField, innerTable);
            }

            case CONCAT -> {
                if (concatFields.isEmpty())
                    throw new IllegalStateException("SubSelectBuilder: .selectConcat() üçün sahə lazımdır");
                List<Field<?>> parts = new ArrayList<>();
                for (int i = 0; i < concatFields.size(); i++) {
                    if (i > 0 && !concatSeparator.isEmpty())
                        parts.add(DSL.inline(concatSeparator));
                    Field<?> f = resolveField(concatFields.get(i), innerTable);
                    parts.add(DSL.coalesce(f, DSL.inline("")));
                }
                yield DSL.concat(parts.toArray(new Field[0]));
            }

            case COALESCE -> {
                if (coalesceFields.isEmpty())
                    throw new IllegalStateException("SubSelectBuilder: .selectCoalesce() üçün sahə lazımdır");
                List<Field<?>> coalesceList = new ArrayList<>();
                for (String cf : coalesceFields)
                    coalesceList.add(resolveField(cf, innerTable));
                coalesceList.add(DSL.inline(coalesceDefault));
                yield DSL.coalesce(coalesceList.get(0),
                        Optional.of(coalesceList.subList(1, coalesceList.size()).toArray(new Field[0])));
            }

            case CASE -> {
                if (caseBuilder == null)
                    throw new IllegalStateException("SubSelectBuilder: .selectCase() çağrılmayıb");
                // CaseBuilder-in alias-ı subquery içindəki sütun adı kimi işlədilir;
                // xarici .as() subquery field-in alias-ını müəyyən edir.
                yield caseBuilder.toField((EntityTable) innerTable);
            }
        };
    }

    // ─── WHERE şərti ─────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition buildWhereCondition(EntityTable<?> innerTable,
                                          Map<String, EntityTable<?>> outerTableMap) {
        Condition cond = null;

        // Korrelyasiya şərtləri: innerField = outerField
        for (CorrelateRow cr : correlations) {
            Field<Object> innerF = (Field<Object>) resolveField(cr.innerField(), innerTable);
            Field<Object> outerF = (Field<Object>) resolveOuterField(cr.outerField(), outerTableMap);
            Condition c = innerF.eq(outerF);
            cond = (cond == null) ? c : cond.and(c);
        }

        // Əlavə WHERE filterlər
        for (FilterRow fr : filterRows) {
            Field<Object> f   = (Field<Object>) resolveField(fr.field(), innerTable);
            Condition     c   = FilterStrategies.get(fr.op()).apply(f, fr.value());
            cond = (cond == null) ? c : cond.and(c);
        }

        return cond;
    }

    // ─── Sahə resolver-lər ───────────────────────────────────────────────

    private Field<?> resolveField(String tableAliasAndField, EntityTable<?> defaultTable) {
        int dot = tableAliasAndField.indexOf('.');
        if (dot > 0) {
            String tAlias = tableAliasAndField.substring(0, dot);
            String fName  = tableAliasAndField.substring(dot + 1);
            // Subquery-nin öz alias-ı
            if (tAlias.equals(tableAlias)) return defaultTable.getField(fName);
            // Başqa EntityTable (JOİN-lər) — bu subquery içindədir, sadəcə DSL.field
            return DSL.field(DSL.name(tAlias, fName));
        }
        return defaultTable.getField(tableAliasAndField);
    }

    private Field<?> resolveOuterField(String tableAliasAndField,
                                       Map<String, EntityTable<?>> outerTableMap) {
        int dot = tableAliasAndField.indexOf('.');
        if (dot > 0) {
            String tAlias = tableAliasAndField.substring(0, dot);
            String fName  = tableAliasAndField.substring(dot + 1);
            EntityTable<?> t = outerTableMap.get(tAlias);
            if (t != null) return t.getField(fName);
        }
        // Əgər tapılmasa — raw DSL.field ilə qaytar
        return DSL.field(tableAliasAndField);
    }
}
