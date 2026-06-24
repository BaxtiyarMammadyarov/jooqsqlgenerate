package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.spec.ExistsSpec;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Inline EXISTS / NOT EXISTS builder — {@link JooqManager} zəncirinə birbaşa qoşulur.
 *
 * <p>{@code ExistsSpec} import etmədən fluent EXISTS yazmaq üçündür.
 * {@link JooqManager#addExists}, {@link JooqManager#addNotExists},
 * {@link JooqManager#addHavingExists}, {@link JooqManager#addHavingNotExists}
 * metodları ilə açılır, {@link #done()} ilə {@link JooqManager}-ə qayıdır.
 *
 * <pre>{@code
 *   jooq.addExists(CashFlowEntity.class)
 *           .joinField("fkCashGroupId", "t", "id")
 *           .equal("status", "A")
 *           .in("typeIds", list)
 *       .done()
 *
 *   // OR qrupu ilə:
 *   jooq.addExists(TaskPermission.class)
 *           .joinField("fkTaskId", "t", "id")
 *           .equal("status", "A")
 *           .orGroup()
 *               .or("fkFilterId", Op.EQUAl, userId)
 *               .andBranch("b1")
 *                   .add("fkTaskTypeKey", Op.IN, list)
 *                   .add("fkRoleId",      Op.EQUAl, roleId)
 *               .end()
 *           .done()
 *       .done()
 * }</pre>
 *
 * @param <E> EXISTS alt-sorğusunun entity tipi
 */
public final class JooqExistsBuilder<E> {

    private final JooqManager         parent;
    @SuppressWarnings("rawtypes")
    final         ExistsSpec           spec;          // package-private: JooqExistsOrGroupBuilder oxuyur
    private final boolean              forHaving;
    private       int                  orGroupCounter = 0;

    @SuppressWarnings("unchecked")
    JooqExistsBuilder(JooqManager parent, ExistsSpec<?, E> spec, boolean forHaving) {
        this.parent    = parent;
        this.spec      = spec;
        this.forHaving = forHaving;
    }

    // ─── JOIN ────────────────────────────────────────────────────────────

    /**
     * EXISTS cədvəlinin sahəsini ana cədvəlin sahəsinə bağlayır.
     *
     * <pre>{@code .joinField("fkCashGroupId", "t", "id") }</pre>
     *
     * @param existsField EXISTS cədvəlindəki sahə adı (camelCase)
     * @param mainAlias   Ana cədvəlin alias-ı ("t", "u", ...)
     * @param mainField   Ana cədvəldəki sahə adı (camelCase)
     */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> joinField(String existsField, String mainAlias, String mainField) {
        spec.joinField(existsField, mainAlias, mainField);
        return this;
    }

    // ─── Filterlər ────────────────────────────────────────────────────────

    /**
     * EXISTS sorğusuna literal dəyər filtri əlavə edir.
     *
     * <pre>{@code .filter("status", Op.EQUAl, "A") }</pre>
     *
     * @param value null olduqda şərt tətbiq edilmir
     */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> filter(String field, Op op, Object value) {
        if (value != null) spec.filter(field, op, value);
        return this;
    }

    /** {@code WHERE field = value} — value null-dursa atlanır */
    public JooqExistsBuilder<E> equal(String field, Object value) {
        return filter(field, Op.EQUAl, value);
    }

    /** {@code WHERE field != value} — value null-dursa atlanır */
    public JooqExistsBuilder<E> notEqual(String field, Object value) {
        return filter(field, Op.NOT_EQUAL, value);
    }

    /** {@code WHERE field IN (...)} — null/boş kolleksiya atlanır */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> in(String field, Collection<?> values) {
        if (values != null && !values.isEmpty()) spec.filter(field, Op.IN, values);
        return this;
    }

    /** {@code WHERE field IN (...)} — varargs */
    public JooqExistsBuilder<E> in(String field, Object... values) {
        return values != null && values.length > 0
                ? in(field, Arrays.asList(values))
                : this;
    }

    /** {@code WHERE field NOT IN (...)} — null/boş kolleksiya atlanır */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> notIn(String field, Collection<?> values) {
        if (values != null && !values.isEmpty()) spec.filter(field, Op.NOT_IN, values);
        return this;
    }

    /** {@code WHERE field NOT IN (...)} — varargs */
    public JooqExistsBuilder<E> notIn(String field, Object... values) {
        return values != null && values.length > 0
                ? notIn(field, Arrays.asList(values))
                : this;
    }

    /** {@code WHERE field LIKE '%value%'} — null/boş atlanır */
    public JooqExistsBuilder<E> like(String field, String value) {
        return filter(field, Op.LIKE, value);
    }

    /** {@code WHERE field IS NULL} */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> isNull(String field) {
        spec.filter(field, Op.IS_EMPTY, "");
        return this;
    }

    /** {@code WHERE field IS NOT NULL} */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> isNotNull(String field) {
        spec.filter(field, Op.IS_NOT_EMPTY, "");
        return this;
    }

    /**
     * EXISTS daxilinə tək field üçün {@code Map<String,String>} (əməliyyat → dəyər) filtri əlavə edir.
     *
     * <p>{@link JooqManager#addFilter(String, Map)} ilə eyni format — outer çağırışdan
     * gələn açar/dəyərlər birbaşa burada da işlənir.
     *
     * <pre>{@code .addFilter("status", Map.of("equal", "A", "notEqual", "D")) }</pre>
     */
    @SuppressWarnings("unchecked")
    public JooqExistsBuilder<E> addFilter(String field, Map<String, String> filters) {
        if (field == null || field.isBlank() || filters == null || filters.isEmpty()) return this;
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (e.getKey() == null) continue;
            Op op = JooqManager.parseOperationPublic(e.getKey());
            if (op == null) continue;
            // IS_EMPTY/IS_NOT_EMPTY (isNull/isNotNull) dəyər tələb etmir — bax:
            // JooqQuery.globalFilter(String, Map) ilə eyni qayda.
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
            spec.filter(field, op, value);
        }
        return this;
    }

    /**
     * EXISTS daxilinə field-first {@code Map<String, Map<String,String>>} strukturu ilə
     * çoxlu filtri bir dəfəyə əlavə edir — {@code dto.getGlobalFilter()} kimi DTO-lardan
     * birbaşa ötürmək üçün. {@link JooqManager#addFilter(Map)} ilə eyni format: outer key
     * = field adı, inner key = əməliyyat, inner value = dəyər.
     *
     * <pre>{@code
     *   jooq.addExists(FlowEntity.class)
     *           .joinField("fkTaskTypeKey", "t", "taskTypeKey")
     *           .equal("status", "A")
     *           .addFilter(dto.getGlobalFilter())
     *       .done()
     * }</pre>
     */
    public JooqExistsBuilder<E> addFilter(Map<String, Map<String, String>> fieldMap) {
        if (fieldMap == null || fieldMap.isEmpty()) return this;
        for (Map.Entry<String, Map<String, String>> e : fieldMap.entrySet()) {
            addFilter(e.getKey(), e.getValue());
        }
        return this;
    }

    // ─── OR qrupu ─────────────────────────────────────────────────────────

    /**
     * EXISTS daxilində OR qrupu açır.
     *
     * <pre>{@code
     *   .orGroup()
     *       .or("fkFilterId", Op.EQUAl, userId)
     *       .andBranch("b1")
     *           .add("fkTaskTypeKey", Op.IN, list)
     *       .end()
     *   .done()
     * }</pre>
     */
    public JooqExistsOrGroupBuilder<E> orGroup() {
        return new JooqExistsOrGroupBuilder<>(this, "G" + (orGroupCounter++));
    }

    // ─── Tamamlama ────────────────────────────────────────────────────────

    /**
     * EXISTS builder-i tamamlayır, {@link JooqManager}-ə qayıdır.
     * WHERE EXISTS və ya HAVING EXISTS olaraq tətbiq olunur.
     */
    @SuppressWarnings("unchecked")
    public JooqManager done() {
        if (forHaving) parent.addHavingExistsFilter(spec);
        else           parent.addExistsFilter(spec);
        return parent;
    }
}
