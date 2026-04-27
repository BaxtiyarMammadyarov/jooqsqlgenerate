package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.*;

/**
 * FLUENT BUILDER — {@code IN (SELECT ...)} və {@code NOT IN (SELECT ...)} subquery filtri.
 *
 * <p>Həm tək sahə, həm composite (çox sahə) dəstəklənir:
 *
 * <pre>{@code
 *   // Tək sahə: WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.status = 'PAID')
 *   SubQueryIn.from(Order.class, "o")
 *       .select("o.userId")
 *       .filter("status", Op.EQUAl, "PAID")
 *
 *   // Composite: WHERE (u.firstName, u.lastName) IN (SELECT bl.firstName, bl.lastName FROM blacklist bl)
 *   SubQueryIn.from(Blacklist.class, "bl")
 *       .select("bl.firstName", "bl.lastName")
 *
 *   // Nested filter: çox şərtli subquery
 *   SubQueryIn.from(Order.class, "o")
 *       .select("o.customerId")
 *       .filter("status",   Op.EQUAl,        "ACTIVE")
 *       .filter("amount",   Op.GREATER_THAN,  1000)
 *       .filter("regionId", Op.IN,            List.of(1, 2, 3))
 * }</pre>
 */
public class SubQueryIn {

    private final Class<?>      entityClass;
    private final String        tableAlias;
    private final List<String>  selectFields = new ArrayList<>();
    private final List<FilterRow> filters    = new ArrayList<>();
    private       boolean       negated      = false;

    private record FilterRow(String field, Op op, Object value) {}

    private SubQueryIn(Class<?> entityClass, String tableAlias) {
        this.entityClass = Objects.requireNonNull(entityClass, "Entity null ola bilməz");
        this.tableAlias  = Objects.requireNonNull(tableAlias,  "Alias null ola bilməz");
    }

    // ─── Giriş nöqtəsi ───────────────────────────────────────────────────

    /** {@code IN (SELECT ...)} */
    public static SubQueryIn from(Class<?> entity, String alias) {
        return new SubQueryIn(entity, alias);
    }

    /** {@code NOT IN (SELECT ...)} */
    public static SubQueryIn notFrom(Class<?> entity, String alias) {
        SubQueryIn s = new SubQueryIn(entity, alias);
        s.negated = true;
        return s;
    }

    // ─── SELECT sahələri ─────────────────────────────────────────────────

    /**
     * Subquery-nin SELECT sahələri.
     *
     * <p>Tək sahə üçün: {@code .select("o.userId")}<br>
     * Composite üçün:  {@code .select("bl.firstName", "bl.lastName")}
     *
     * @param tableAliasAndFields "alias.field" formatında bir və ya çox sahə
     */
    public SubQueryIn select(String... tableAliasAndFields) {
        selectFields.addAll(Arrays.asList(tableAliasAndFields));
        return this;
    }

    // ─── Subquery filterlər ───────────────────────────────────────────────

    /**
     * Subquery içinə WHERE şərti əlavə edir.
     *
     * <pre>{@code
     *   .filter("status",   Op.EQUAl,       "ACTIVE")
     *   .filter("amount",   Op.GREATER_THAN, 100)
     *   .filter("regionId", Op.IN,           List.of(1, 2, 3))
     * }</pre>
     */
    public SubQueryIn filter(String field, Op op, Object value) {
        if (field != null && !field.isBlank() && op != null && value != null)
            filters.add(new FilterRow(field, op, value));
        return this;
    }

    // ─── Accessor ────────────────────────────────────────────────────────

    public boolean isNegated()           { return negated;      }
    public List<String> getSelectFields(){ return selectFields; }

    // ─── jOOQ Condition-a çevirmə ────────────────────────────────────────

    /**
     * {@code outerFields IN (SELECT subFields FROM table WHERE ...)} şərtini qaytarır.
     *
     * @param outerFields  əsas sorğudakı sahə adları (camelCase)
     * @param outerTable   əsas sorğunun EntityTable-i
     * @param outerTableMap alias → EntityTable xəritəsi
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Condition toCondition(List<String> outerFields,
                                 EntityTable<?> outerTable,
                                 Map<String, EntityTable<?>> outerTableMap) {
        if (selectFields.isEmpty())
            throw new IllegalStateException("SubQueryIn: .select() çağrılmayıb");
        if (outerFields.isEmpty())
            throw new IllegalStateException("SubQueryIn: outer sahə verilməyib");

        EntityTable<?> innerTable = new EntityTable<>(entityClass, tableAlias);

        // ── Subquery SELECT sahələri ────────────────────────────────────
        List<SelectFieldOrAsterisk> subSelectFields = new ArrayList<>();
        for (String sf : selectFields) {
            subSelectFields.add(resolveInnerField(sf, innerTable));
        }

        // ── Subquery WHERE şərti ────────────────────────────────────────
        Condition subWhere = buildSubWhere(innerTable);

        // ── Subquery ────────────────────────────────────────────────────
        SelectConditionStep<?> subQuery = DSL
                .select(subSelectFields)
                .from(innerTable.getTable())
                .where(subWhere != null ? subWhere : DSL.trueCondition());

        // ── Outer sahələr ───────────────────────────────────────────────
        // Tək sahə: field.in(subQuery)
        // Composite: DSL.row(f1, f2).in(subQuery)

        Condition condition;

        if (outerFields.size() == 1) {
            // Tək sahə
            String outerFieldName = outerFields.get(0);
            Field<Object> outerField = resolveOuterField(outerFieldName, outerTable, outerTableMap);
            condition = negated
                    ? outerField.notIn(subQuery)
                    : outerField.in(subQuery);

        } else {
            // Composite — DSL.row(f1, f2, ...) IN (SELECT ...)
            // PostgreSQL, MySQL, Oracle, H2 destekleyir.
            // SQL Server desteklemez — ora EXISTS ile yazin.
            Field<?>[] outerFieldArr = outerFields.stream()
                    .map(fn -> resolveOuterField(fn, outerTable, outerTableMap))
                    .toArray(Field[]::new);

            RowN outerRow = (RowN) DSL.row(outerFieldArr);
            condition = negated
                    ? outerRow.notIn(subQuery)
                    : outerRow.in(subQuery);
        }

        return condition;
    }

    // ─── Private yardımcılar ─────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition buildSubWhere(EntityTable<?> innerTable) {
        Condition cond = null;
        for (FilterRow fr : filters) {
            Field<Object> f = (Field<Object>) innerTable.getField(fieldPart(fr.field()));
            Condition     c;
            if (fr.op() == Op.IN || fr.op() == Op.NOT_IN) {
                // IN üçün Collection lazımdır
                Object val = fr.value();
                if (!(val instanceof Collection)) val = List.of(val);
                c = FilterStrategies.get(fr.op()).apply(f, val);
            } else {
                c = FilterStrategies.get(fr.op()).apply(f, fr.value());
            }
            cond = (cond == null) ? c : cond.and(c);
        }
        return cond;
    }

    private Field<?> resolveInnerField(String tableAliasAndField, EntityTable<?> innerTable) {
        return innerTable.getField(fieldPart(tableAliasAndField));
    }

    @SuppressWarnings("unchecked")
    private Field<Object> resolveOuterField(String fieldName,
                                             EntityTable<?> outerTable,
                                             Map<String, EntityTable<?>> outerTableMap) {
        // "alias.field" formatı
        int dot = fieldName.indexOf('.');
        if (dot > 0) {
            String alias = fieldName.substring(0, dot);
            String fname = fieldName.substring(dot + 1);
            EntityTable<?> t = outerTableMap.getOrDefault(alias, outerTable);
            return (Field<Object>) t.getField(fname);
        }
        return (Field<Object>) outerTable.getField(fieldName);
    }

    private static String fieldPart(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(dot + 1) : s;
    }
}
