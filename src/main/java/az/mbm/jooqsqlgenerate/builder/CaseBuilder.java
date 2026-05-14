package az.mbm.jooqsqlgenerate.builder;

import az.mbm.jooqsqlgenerate.enums.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * FLUENT BUILDER — SQL {@code CASE WHEN} ifadəsi üçün.
 *
 * <p>Bu sinif yalnız data saxlayır — heç bir jOOQ asılılığı yoxdur.
 * SQL generasiyası {@link SelectQueryBuilder#buildCaseField} tərəfindən həyata keçirilir.
 *
 * <pre>{@code
 *   CaseBuilder.when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
 *              .andWhen("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
 *              .otherwise("Naməlum")
 *              .as("statusLabel")
 *
 *   // Rəqəm dəyərləri:
 *   CaseBuilder.when("a", Op.EQUAl, 1).then(0)
 *              .otherwise(-1)
 *              .as("result")
 * }</pre>
 *
 * <p><b>Qeyd:</b> Giriş nöqtəsi üçün statik {@link #when(String, Op, Object)},
 * zəncir üçün isə instance {@link #andWhen(String, Op, Object)} istifadə edin.
 *
 * @param <T> entity tipi
 */
public class CaseBuilder<T> {

    /**
     * WHEN/THEN cütü — package-private, SelectQueryBuilder tərəfindən oxunur.
     * {@code thenIsField=true} olduqda {@code thenVal} sütun adıdır (alias.field),
     * əks halda literal dəyərdir ({@code DSL.val()} ilə render olunur).
     */
    record WhenClause(String field, Op op, Object whenVal, Object thenVal, boolean thenIsField) {}

    private final List<WhenClause> whenClauses  = new ArrayList<>();
    private       Object           elseValue    = null;
    private       boolean          elseIsField  = false;
    private       String           alias        = null;

    public CaseBuilder() {}

    // ─── Statik giriş nöqtəsi ────────────────────────────────────────────

    /**
     * CASE WHEN builder-ini başladır.
     *
     * <pre>{@code CaseBuilder.when("status", Op.EQUAl, "ACTIVE").then("Aktiv") }</pre>
     */
    public static <T> WhenStep<T> when(String field, Op op, Object whenValue) {
        CaseBuilder<T> b = new CaseBuilder<>();
        return new WhenStep<>(b, field, op, whenValue);
    }

    // ─── Zəncir üçün əlavə WHEN ──────────────────────────────────────────

    /**
     * .then() çağrısından sonra əlavə WHEN şərti əlavə edir.
     *
     * <p><b>Niyə andWhen?</b> Java-da bir sinifdə eyni erasure ilə
     * həm statik həm instance metod ola bilməz. Statik {@code when()} giriş
     * nöqtəsi, {@code andWhen()} isə zəncir metodu rolunu oynayır.
     */
    public WhenStep<T> andWhen(String field, Op op, Object whenValue) {
        return new WhenStep<>(this, field, op, whenValue);
    }

    // ─── OTHERWISE ───────────────────────────────────────────────────────

    /** ELSE — literal dəyər (rəqəm, string və s.). */
    public CaseBuilder<T> otherwise(Object elseValue) {
        this.elseValue   = elseValue;
        this.elseIsField = false;
        return this;
    }

    /** ELSE — cədvəl sütunu ({@code "alias.fieldName"} formatında). */
    public CaseBuilder<T> otherwiseField(String aliasAndField) {
        this.elseValue   = aliasAndField;
        this.elseIsField = true;
        return this;
    }

    // ─── Alias ───────────────────────────────────────────────────────────

    public CaseBuilder<T> as(String alias) {
        Objects.requireNonNull(alias, "CASE alias null ola bilməz");
        int dot = alias.indexOf('.');
        this.alias = dot >= 0 ? alias.substring(dot + 1) : alias;
        return this;
    }

    // ─── THEN addımı (inner builder) ─────────────────────────────────────

    public static class WhenStep<T> {
        private final CaseBuilder<T> builder;
        private final String         field;
        private final Op             op;
        private final Object         whenVal;

        WhenStep(CaseBuilder<T> builder, String field, Op op, Object whenVal) {
            this.builder = builder;
            this.field   = field;
            this.op      = op;
            this.whenVal = whenVal;
        }

        /** THEN — literal dəyər (rəqəm, string və s.). */
        public CaseBuilder<T> then(Object thenValue) {
            builder.whenClauses.add(new WhenClause(field, op, whenVal, thenValue, false));
            return builder;
        }

        /** THEN — cədvəl sütunu ({@code "alias.fieldName"} formatında). */
        public CaseBuilder<T> thenField(String aliasAndField) {
            builder.whenClauses.add(new WhenClause(field, op, whenVal, aliasAndField, true));
            return builder;
        }
    }

    // ─── Package-private accessors (SelectQueryBuilder üçün) ─────────────

    List<WhenClause> whenClauses() { return Collections.unmodifiableList(whenClauses); }
    Object           elseValue()   { return elseValue; }
    boolean          elseIsField() { return elseIsField; }
    String           alias()       { return alias; }

    /** Package-private — Case sinifi üçün. */
    void addWhenClause(WhenClause wc) { whenClauses.add(wc); }

    /** Literal THEN dəyəri ilə WHEN/THEN cütü əlavə edir. */
    public CaseBuilder<T> addWhen(String field, Op op, Object whenVal, Object thenVal) {
        whenClauses.add(new WhenClause(field, op, whenVal, thenVal, false));
        return this;
    }

    /** Sütun referansı THEN ilə WHEN/THEN cütü əlavə edir. */
    public CaseBuilder<T> addWhenField(String field, Op op, Object whenVal, String thenAliasAndField) {
        whenClauses.add(new WhenClause(field, op, whenVal, thenAliasAndField, true));
        return this;
    }
}
