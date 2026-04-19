package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.FilterOperations;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * FLUENT BUILDER — SQL {@code CASE WHEN} ifadəsi üçün
 *
 * <p>Köhnə kodda 11 parametrli bir metod:
 * <pre>{@code
 *   manager.setAndCaseValue("alias", "w1", "u", "status",
 *       "ACTIVE", FilterOperations.EQUAl, "Aktiv", false, null,
 *       FilterOperations.EQUAl, "INACTIVE");
 * }</pre>
 *
 * <p>Yeni fluent API:
 * <pre>{@code
 *   CaseBuilder.when("status", FilterOperations.EQUAl, "ACTIVE").then("Aktiv")
 *              .andWhen("status", FilterOperations.EQUAl, "INACTIVE").then("Deaktiv")
 *              .otherwise("Naməlum")
 *              .as("statusLabel")
 * }</pre>
 *
 * <p><b>Qeyd:</b> Giriş nöqtəsi üçün statik {@link #when(String, FilterOperations, Object)},
 * zəncir üçün isə instance {@link #andWhen(String, FilterOperations, Object)} istifadə edin.
 * Java statik və instance metodun eyni erasure-a sahib ola bilməz — buna görə adlar fərqlidir.
 *
 * @param <T> entity tipi
 */
public class CaseBuilder<T> {

    private record WhenClause(String field, FilterOperations op, Object whenVal, Object thenVal) {}

    private final List<WhenClause> whenClauses = new ArrayList<>();
    private       Object           elseValue   = null;
    private       String           alias       = null;

    private CaseBuilder() {}

    // ─── Statik giriş nöqtəsi ────────────────────────────────────────────

    /**
     * CASE WHEN builder-ini başladır.
     *
     * <pre>{@code CaseBuilder.when("status", EQUAl, "ACTIVE").then("Aktiv") }</pre>
     */
    public static <T> WhenStep<T> when(String field, FilterOperations op, Object whenValue) {
        CaseBuilder<T> b = new CaseBuilder<>();
        return new WhenStep<>(b, field, op, whenValue);
    }

    // ─── Zəncir üçün əlavə WHEN ──────────────────────────────────────────

    /**
     * .then() çağrısından sonra əlavə WHEN şərti əlavə edir.
     *
     * <pre>{@code
     *   CaseBuilder.when("status", EQUAl, "ACTIVE").then("Aktiv")
     *              .andWhen("status", EQUAl, "INACTIVE").then("Deaktiv")
     * }</pre>
     *
     * <p><b>Niyə andWhen?</b> Java-da bir sinifdə eyni silinmə (erasure) ilə
     * həm statik həm instance metod ola bilməz. Statik {@code when()} giriş
     * nöqtəsi, {@code andWhen()} isə zəncir metodu rolunu oynayır.
     */
    public WhenStep<T> andWhen(String field, FilterOperations op, Object whenValue) {
        return new WhenStep<>(this, field, op, whenValue);
    }

    // ─── OTHERWISE ───────────────────────────────────────────────────────

    public CaseBuilder<T> otherwise(Object elseValue) {
        this.elseValue = elseValue;
        return this;
    }

    // ─── Alias ───────────────────────────────────────────────────────────

    public CaseBuilder<T> as(String alias) {
        this.alias = Objects.requireNonNull(alias, "CASE alias null ola bilməz");
        return this;
    }

    // ─── THEN addımı (inner builder) ─────────────────────────────────────

    public static class WhenStep<T> {
        private final CaseBuilder<T>   builder;
        private final String           field;
        private final FilterOperations op;
        private final Object           whenVal;

        WhenStep(CaseBuilder<T> builder, String field, FilterOperations op, Object whenVal) {
            this.builder = builder;
            this.field   = field;
            this.op      = op;
            this.whenVal = whenVal;
        }

        /** THEN dəyəri: bu WHEN tamamlanır, CaseBuilder-ə qayıdılır */
        public CaseBuilder<T> then(Object thenValue) {
            builder.whenClauses.add(new WhenClause(field, op, whenVal, thenValue));
            return builder;
        }
    }

    // ─── jOOQ Field-ə çevirmə ────────────────────────────────────────────

    /**
     * Builder-i jOOQ {@link Field}-ə çevirir.
     * {@link SelectQueryBuilder} tərəfindən çağrılır.
     *
     * @param table entity-nin EntityTable nümunəsi
     * @throws IllegalStateException WHEN şərti və ya alias yoxdursa
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> toField(EntityTable<T> table) {
        if (whenClauses.isEmpty())
            throw new IllegalStateException("CaseBuilder: heç bir WHEN şərti yoxdur");
        if (alias == null)
            throw new IllegalStateException("CaseBuilder: .as(alias) tələb olunur");

        org.jooq.CaseConditionStep caseStep = null;

        for (WhenClause wc : whenClauses) {
            Field<Object> f    = (Field<Object>) table.getField(wc.field());
            var           cond = FilterStrategies.get(wc.op()).apply(f, wc.whenVal());
            var           then = DSL.val(wc.thenVal());
            caseStep = (caseStep == null)
                    ? DSL.case_().when(cond, then)
                    : caseStep.when(cond, then);
        }

        var result = (elseValue != null)
                ? caseStep.otherwise(DSL.val(elseValue))
                : caseStep.otherwise(DSL.inline((Object) null));

        return result.as(alias);
    }
}
