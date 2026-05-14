package az.mbm.jooqsqlgenerate.builder;

import az.mbm.jooqsqlgenerate.enums.Op;

/**
 * Fluent CASE WHEN builder — tam zəncir sintaksisi ilə.
 *
 * <pre>{@code
 * import az.mbm.jooqsqlgenerate.builder.Case;
 *
 * Case.when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
 *     .when("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
 *     .else_("Naməlum")
 *     .as("statusLabel")
 *
 * // Sütun referansı:
 * Case.when("type", Op.EQUAl, "A").thenField("t.priceA")
 *     .when("type", Op.EQUAl, "B").thenField("t.priceB")
 *     .elseField("t.defaultPrice")
 *     .as("finalPrice")
 *
 * // Rəqəm dəyərləri:
 * Case.when("a", Op.EQUAl, 1).then(0)
 *     .else_(-1)
 *     .as("result")
 * }</pre>
 */
public class Case {

    private Case() {}

    /** Zənciri başladır — ilk WHEN şərti. */
    public static <T> ThenStep<T> when(String field, Op op, Object whenValue) {
        return new ThenStep<>(new CaseBuilder<>(), field, op, whenValue);
    }

    // ─── ThenStep: WHEN-dən sonra THEN gözləyir ───────────────────────────

    public static class ThenStep<T> {
        private final CaseBuilder<T> builder;
        private final String         field;
        private final Op             op;
        private final Object         whenVal;

        ThenStep(CaseBuilder<T> builder, String field, Op op, Object whenVal) {
            this.builder = builder;
            this.field   = field;
            this.op      = op;
            this.whenVal = whenVal;
        }

        /** THEN — literal dəyər (rəqəm, string və s.). */
        public CaseChain<T> then(Object thenValue) {
            builder.addWhenClause(new CaseBuilder.WhenClause(field, op, whenVal, thenValue, false));
            return new CaseChain<>(builder);
        }

        /** THEN — cədvəl sütunu ({@code "alias.fieldName"} formatında). */
        public CaseChain<T> thenField(String aliasAndField) {
            builder.addWhenClause(new CaseBuilder.WhenClause(field, op, whenVal, aliasAndField, true));
            return new CaseChain<>(builder);
        }
    }

    // ─── CaseChain: növbəti WHEN, ELSE və ya sonluq ───────────────────────

    public static class CaseChain<T> {
        private final CaseBuilder<T> builder;

        CaseChain(CaseBuilder<T> builder) {
            this.builder = builder;
        }

        /** Növbəti WHEN şərti. */
        public ThenStep<T> when(String field, Op op, Object whenValue) {
            return new ThenStep<>(builder, field, op, whenValue);
        }

        /** ELSE — literal dəyər. */
        public CaseChain<T> else_(Object elseValue) {
            builder.otherwise(elseValue);
            return this;
        }

        /** ELSE — cədvəl sütunu ({@code "alias.fieldName"} formatında). */
        public CaseChain<T> elseField(String aliasAndField) {
            builder.otherwiseField(aliasAndField);
            return this;
        }

        /** Alias təyin edir və {@link CaseBuilder}-i qaytarır — zənciri bitirir. */
        public CaseBuilder<T> as(String alias) {
            builder.as(alias);
            return builder;
        }
    }
}
