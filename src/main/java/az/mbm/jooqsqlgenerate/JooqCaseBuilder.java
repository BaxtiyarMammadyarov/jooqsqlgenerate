package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.enums.Op;

/**
 * Fluent CASE WHEN builder — {@link JooqManager#addCase()} ilə açılır,
 * {@link CaseStep#as(String)} ilə {@link JooqManager}-ə qayıdır.
 *
 * <pre>{@code
 *   manager.addCase()
 *       .when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
 *       .when("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
 *       .else_("Naməlum")
 *       .as("statusLabel")
 *
 *   // Sütun referansı ilə:
 *   manager.addCase()
 *       .when("type", Op.EQUAl, "A").thenField("t.priceA")
 *       .when("type", Op.EQUAl, "B").thenField("t.priceB")
 *       .elseField("t.defaultPrice")
 *       .as("finalPrice")
 * }</pre>
 */
public final class JooqCaseBuilder {

    private JooqCaseBuilder() {}

    // ════════════════════════════════════════════════════════════════════
    //  CaseStep
    // ════════════════════════════════════════════════════════════════════

    /**
     * Fluent CASE WHEN zəncirinin əsas addımı.
     * {@link JooqManager#addCase()} ilə yaradılır.
     */
    public static final class CaseStep {

        private final JooqManager        manager;
        private final CaseBuilder<Object> cb;

        @SuppressWarnings("unchecked")
        CaseStep(JooqManager manager, CaseBuilder<?> cb) {
            this.manager = manager;
            this.cb      = (CaseBuilder<Object>) cb;
        }

        /** WHEN şərti — THEN gözləyir. */
        public CaseThenStep when(String field, Op op, Object whenValue) {
            return new CaseThenStep(this, field, op, whenValue);
        }

        /** ELSE — literal dəyər. */
        public CaseStep else_(Object elseValue) {
            cb.otherwise(elseValue);
            return this;
        }

        /** ELSE — cədvəl sütunu ({@code "alias.fieldName"}). */
        public CaseStep elseField(String aliasAndField) {
            cb.otherwiseField(aliasAndField);
            return this;
        }

        /**
         * Alias təyin edir, CASE-i manager-ə qeydiyyat edir
         * və {@link JooqManager}-ə qayıdır.
         */
        public JooqManager as(String alias) {
            cb.as(alias);
            manager.commitCase(cb);
            return manager;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  CaseThenStep
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHEN-dən sonra THEN gözləyən addım.
     */
    public static final class CaseThenStep {

        private final CaseStep parent;
        private final String   field;
        private final Op       op;
        private final Object   whenVal;

        CaseThenStep(CaseStep parent, String field, Op op, Object whenVal) {
            this.parent  = parent;
            this.field   = field;
            this.op      = op;
            this.whenVal = whenVal;
        }

        /** THEN — literal dəyər. */
        public CaseStep then(Object thenValue) {
            parent.cb.addWhen(field, op, whenVal, thenValue);
            return parent;
        }

        /** THEN — cədvəl sütunu ({@code "alias.fieldName"}). */
        public CaseStep thenField(String aliasAndField) {
            parent.cb.addWhenField(field, op, whenVal, aliasAndField);
            return parent;
        }
    }
}
