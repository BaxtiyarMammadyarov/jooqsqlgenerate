package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.enums.Op;

/**
 * EXISTS daxilindəki AND alt-qrupu builder-i.
 *
 * <p>{@link JooqExistsOrGroupBuilder#andBranch(String)} ilə açılır,
 * {@link #end()} ilə {@link JooqExistsOrGroupBuilder}-ə qayıdır.
 *
 * <pre>{@code
 *   .andBranch("b1")
 *       .add("fkTaskTypeKey", Op.IN,    taskTypeList)
 *       .add("fkRoleId",      Op.EQUAl, roleId)
 *   .end()
 *   // → (fkTaskTypeKey IN (...) AND fkRoleId = roleId)
 * }</pre>
 *
 * @param <E> EXISTS entity tipi
 */
public final class JooqExistsAndBranchBuilder<E> {

    private final JooqExistsOrGroupBuilder<E> orGroupBuilder;
    private final String                      branchAlias;

    JooqExistsAndBranchBuilder(JooqExistsOrGroupBuilder<E> orGroupBuilder, String branchAlias) {
        this.orGroupBuilder = orGroupBuilder;
        this.branchAlias    = branchAlias;
    }

    /**
     * AND alt-qrupuna şərt əlavə edir.
     *
     * <pre>{@code
     *   .add("fkTaskTypeKey", Op.IN,    taskTypeList)
     *   .add("fkRoleId",      Op.EQUAl, roleId)
     * }</pre>
     *
     * @param value null olduqda şərt tətbiq edilmir
     */
    @SuppressWarnings("unchecked")
    public JooqExistsAndBranchBuilder<E> add(String field, Op op, Object value) {
        if (value != null)
            orGroupBuilder.existsBuilder.spec.orFilter(
                    orGroupBuilder.orGroupName, branchAlias, field, op, value);
        return this;
    }

    /**
     * AND alt-qrupunu bağlayır, {@link JooqExistsOrGroupBuilder}-ə qayıdır.
     */
    public JooqExistsOrGroupBuilder<E> end() {
        return orGroupBuilder;
    }
}
