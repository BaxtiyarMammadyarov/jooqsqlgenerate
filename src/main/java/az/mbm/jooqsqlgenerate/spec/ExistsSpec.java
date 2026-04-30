package az.mbm.jooqsqlgenerate.spec;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final List<OrClause>     orClauses   = new ArrayList<>();
    private       boolean            negated     = false;

    private record JoinField(String existsField, String mainTableAlias, String mainField) {}
    private record FilterClause(String field, Op op, Object value) {}
    /** EXISTS daxilindəki OR/AND qrupları — SelectQueryBuilder.OrFilterRow ilə eyni məntiq */
    private record OrClause(String orGroup, String andGroup, String field, Op op, Object value) {}

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

    // ─── EXISTS daxilində OR/AND qruplaması — fluent builder ─────────────

    /**
     * OR/AND qrupu builder-i başladır.
     *
     * <pre>{@code
     *   ExistsSpec.exists(TaskPermissionEntity.class)
     *       .joinField("fkTaskId", "t", "id")
     *       .filter("status", Op.EQUAl, "A")
     *       .orGroup()
     *           .andBranch("branch1")
     *               .add("fkFilterId",    Op.EQUAl, userId)
     *               .add("fkTaskTypeKey", Op.IN,    l)
     *           .end()
     *           .andBranch("branch2")
     *               .add("fkTaskTypeKey", Op.IN,    vl)
     *           .end()
     *       .done()
     * }</pre>
     */
    public ExistsOrGroupBuilder orGroup() {
        return new ExistsOrGroupBuilder(this, "G");
    }

    /**
     * Fluent OR qrupu builder — {@link #orGroup()} ilə başlayır.
     */
    public final class ExistsOrGroupBuilder {
        private final ExistsSpec<T, E> spec;
        private final String           orGroupAlias;
        private       int              orCounter = 0;

        ExistsOrGroupBuilder(ExistsSpec<T, E> spec, String orGroupAlias) {
            this.spec         = spec;
            this.orGroupAlias = orGroupAlias;
        }

        /**
         * Sadə OR branch — field öz ayrı andGroup-unda olur.
         *
         * <pre>{@code .or("fkTaskTypeKey", Op.IN, list) }</pre>
         */
        public ExistsOrGroupBuilder or(String field, Op op, Object value) {
            String uniqueAndGroup = field + "_" + (orCounter++);
            spec.addOrClause(orGroupAlias, uniqueAndGroup, field, op, value);
            return this;
        }

        /**
         * AND alt-qrupu başladır — eyni branchAlias altındakılar AND, branch-lar OR ilə birləşir.
         *
         * <pre>{@code
         *   .andBranch("b1")
         *       .add("fkFilterId",    Op.EQUAl, userId)
         *       .add("fkTaskTypeKey", Op.IN,    list)
         *   .end()
         * }</pre>
         */
        public ExistsAndBranchBuilder andBranch(String branchAlias) {
            return new ExistsAndBranchBuilder(this, branchAlias);
        }

        /** Builder-i tamamlayır, {@link ExistsSpec}-ə qayıdır. */
        public ExistsSpec<T, E> done() {
            return spec;
        }
    }

    /**
     * AND alt-qrupu builder — {@link ExistsOrGroupBuilder#andBranch} ilə başlayır.
     */
    public final class ExistsAndBranchBuilder {
        private final ExistsOrGroupBuilder parent;
        private final String               branchAlias;

        ExistsAndBranchBuilder(ExistsOrGroupBuilder parent, String branchAlias) {
            this.parent      = parent;
            this.branchAlias = branchAlias;
        }

        /**
         * AND alt-qrupuna field əlavə edir.
         *
         * <pre>{@code
         *   .add("fkFilterId",    Op.EQUAl, userId)
         *   .add("fkTaskTypeKey", Op.IN,    list)
         * }</pre>
         *
         * @param value null olduqda şərt tətbiq edilmir
         */
        public ExistsAndBranchBuilder add(String field, Op op, Object value) {
            parent.spec.addOrClause(parent.orGroupAlias, branchAlias, field, op, value);
            return this;
        }

        /** AND alt-qrupunu bağlayır, {@link ExistsOrGroupBuilder}-ə qayıdır. */
        public ExistsOrGroupBuilder end() {
            return parent;
        }
    }

    // ─── Internal helper — orClauses-ə birbaşa yazma ─────────────────────

    private void addOrClause(String orGroup, String andGroup, String field, Op op, Object value) {
        if (field != null && op != null && value != null)
            orClauses.add(new OrClause(orGroup, andGroup, field, op, value));
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

        // OR/AND qrupları — orGroup → andGroup → List<OrClause>
        if (!orClauses.isEmpty()) {
            LinkedHashMap<String, LinkedHashMap<String, List<OrClause>>> grouped = new LinkedHashMap<>();
            for (OrClause oc : orClauses) {
                grouped.computeIfAbsent(oc.orGroup(),  k -> new LinkedHashMap<>())
                       .computeIfAbsent(oc.andGroup(), k -> new ArrayList<>())
                       .add(oc);
            }
            for (LinkedHashMap<String, List<OrClause>> andGroups : grouped.values()) {
                Condition orGroupCond = null;
                for (List<OrClause> andRows : andGroups.values()) {
                    Condition andCond = null;
                    for (OrClause oc : andRows) {
                        Field<Object> f = existsTable.getField(oc.field());
                        if (f == null) continue; // field tapılmadı — atla
                        Condition c = FilterStrategies.get(oc.op()).apply(f, oc.value());
                        andCond = (andCond == null) ? c : andCond.and(c);
                    }
                    orGroupCond = (orGroupCond == null) ? andCond : orGroupCond.or(andCond);
                }
                if (orGroupCond != null)
                    condition = (condition == null) ? orGroupCond : condition.and(orGroupCond);
            }
        }

        var subSelect = DSL.selectOne()
                .from(existsTable.getTable())
                .where(condition != null ? condition : DSL.trueCondition());

        return negated ? DSL.notExists(subSelect) : DSL.exists(subSelect);
    }
}
