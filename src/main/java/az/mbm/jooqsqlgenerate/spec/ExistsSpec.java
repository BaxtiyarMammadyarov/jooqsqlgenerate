package az.mbm.jooqsqlgenerate.spec;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.List;

/**
 * EXISTS / NOT EXISTS spesifikasiyası
 *
 * <p>Köhnə kodda:
 * <pre>{@code
 *   manager.setExistsTable("e1", "u", Order.class);
 *   manager.setExistsFilter("e1", "userId", "u.id", Op.EQUAl, false);
 *   manager.setExistsFilter("e1", "status", "PAID", Op.EQUAl, true);
 * }</pre>
 *
 * <p>Yeni API:
 * <pre>{@code
 *   ExistsSpec.exists(Order.class)
 *       .joinField("userId", "u", "id")         // o.userId = u.id
 *       .filter("status", Op.EQUAl, "PAID")
 * }</pre>
 *
 * @param <T> ana cədvəl entity tipi
 * @param <E> EXISTS alt-sorğusunun entity tipi
 */
public class ExistsSpec<T, E> implements Specification<T> {

    private final Class<E>           existsEntity;
    private final List<JoinField>    joinFields  = new ArrayList<>();
    private final List<FilterClause> filters     = new ArrayList<>();
    private       boolean            negated     = false;

    private record JoinField(String existsField, String mainTableAlias, String mainField) {}
    private record FilterClause(String field, Op op, Object value) {}

    private ExistsSpec(Class<E> existsEntity) {
        this.existsEntity = existsEntity;
    }

    // ─── Static factory ──────────────────────────────────────────────────

    /** WHERE EXISTS (SELECT 1 FROM existsEntity WHERE ...) */
    public static <T, E> ExistsSpec<T, E> exists(Class<E> existsEntity) {
        return new ExistsSpec<>(existsEntity);
    }

    /** WHERE NOT EXISTS (SELECT 1 FROM existsEntity WHERE ...) */
    public static <T, E> ExistsSpec<T, E> notExists(Class<E> existsEntity) {
        ExistsSpec<T, E> spec = new ExistsSpec<>(existsEntity);
        spec.negated = true;
        return spec;
    }

    // ─── JOIN şərti ──────────────────────────────────────────────────────

    /**
     * EXISTS cədvəlinin sahəsini ana cədvəlin sahəsinə bağlayır.
     *
     * <pre>{@code
     *   .joinField("userId", "u", "id")   // existsTable.userId = u.id
     * }</pre>
     *
     * @param existsField  EXISTS cədvəlindəki sahə
     * @param mainAlias    Ana cədvəlin alias-ı
     * @param mainField    Ana cədvəldəki sahə
     */
    public ExistsSpec<T, E> joinField(String existsField, String mainAlias, String mainField) {
        joinFields.add(new JoinField(existsField, mainAlias, mainField));
        return this;
    }

    // ─── EXISTS daxili filtrlər ───────────────────────────────────────────

    /**
     * EXISTS sorğusuna literal dəyər filtri əlavə edir.
     *
     * <pre>{@code
     *   .filter("status", Op.EQUAl, "PAID")
     * }</pre>
     */
    public ExistsSpec<T, E> filter(String field, Op op, Object value) {
        filters.add(new FilterClause(field, op, value));
        return this;
    }

    // ─── Specification → Condition ────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Condition toCondition(EntityTable<T> mainTable) {
        EntityTable<E> existsTable = new EntityTable<>(existsEntity);
        Condition condition = null;

        // JOIN şərtləri: existsTable.field = mainTable.field
        for (JoinField jf : joinFields) {
            EntityTable<T> aliasTable = new EntityTable<>(mainTable.getEntityClass(), jf.mainTableAlias());
            Field<Object>  eField     = existsTable.getField(jf.existsField());
            Field<Object>  mField     = aliasTable.getField(jf.mainField());
            Condition      c          = eField.eq(mField);
            condition = (condition == null) ? c : condition.and(c);
        }

        // Literal filtr şərtləri
        for (FilterClause fc : filters) {
            Field<Object> f = existsTable.getField(fc.field());
            Condition     c = FilterStrategies.get(fc.op()).apply(f, fc.value());
            condition = (condition == null) ? c : condition.and(c);
        }

        var subSelect = DSL.selectOne()
                .from(existsTable.getTable())
                .where(condition != null ? condition : DSL.trueCondition());

        return negated ? DSL.notExists(subSelect) : DSL.exists(subSelect);
    }
}
