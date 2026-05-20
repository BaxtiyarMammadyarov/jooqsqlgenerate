package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.enums.Op;

/**
 * EXISTS daxilindəki OR qrupu builder-i.
 *
 * <p>{@link JooqExistsBuilder#orGroup()} ilə açılır,
 * {@link #done()} ilə {@link JooqExistsBuilder}-ə qayıdır.
 *
 * <pre>{@code
 *   .orGroup()
 *       .or("fkFilterId", Op.EQUAl, userId)        // sadə OR branch
 *       .andBranch("b1")                            // AND alt-qrupu
 *           .add("fkTaskTypeKey", Op.IN,    list)
 *           .add("fkRoleId",      Op.EQUAl, roleId)
 *       .end()
 *   .done()
 * }</pre>
 *
 * @param <E> EXISTS entity tipi
 */
public final class JooqExistsOrGroupBuilder<E> {

    private final JooqExistsBuilder<E> existsBuilder;
    final         String               orGroupName;    // package-private: JooqExistsAndBranchBuilder oxuyur
    private       int                  branchCounter = 0;

    JooqExistsOrGroupBuilder(JooqExistsBuilder<E> existsBuilder, String orGroupName) {
        this.existsBuilder = existsBuilder;
        this.orGroupName   = orGroupName;
    }

    /**
     * Tək sahə üçün sadə OR branch əlavə edir.
     * Hər {@code or()} çağrışı öz ayrı AND qrupunda olur.
     *
     * <pre>{@code
     *   .or("fkFilterId", Op.EQUAl, userId)
     *   .or("fkRoleId",   Op.EQUAl, adminId)
     *   // → (fkFilterId = userId OR fkRoleId = adminId)
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public JooqExistsOrGroupBuilder<E> or(String field, Op op, Object value) {
        String uniqueAndGroup = field + "_" + (branchCounter++);
        existsBuilder.spec.orFilter(orGroupName, uniqueAndGroup, field, op, value);
        return this;
    }

    /**
     * AND alt-qrupu açır — eyni branchAlias altındakılar AND, branch-lar OR ilə birləşir.
     *
     * <pre>{@code
     *   .andBranch("b1")
     *       .add("fkTaskTypeKey", Op.IN,    list)
     *       .add("fkRoleId",      Op.EQUAl, roleId)
     *   .end()
     *   // → (fkTaskTypeKey IN (...) AND fkRoleId = roleId)
     * }</pre>
     *
     * @param branchAlias AND qrupunun adı — eyni adlılar AND ilə birləşir
     */
    public JooqExistsAndBranchBuilder<E> andBranch(String branchAlias) {
        return new JooqExistsAndBranchBuilder<>(this, branchAlias);
    }

    /**
     * OR qrupu builder-i bağlayır, {@link JooqExistsBuilder}-ə qayıdır.
     */
    public JooqExistsBuilder<E> done() {
        return existsBuilder;
    }
}
