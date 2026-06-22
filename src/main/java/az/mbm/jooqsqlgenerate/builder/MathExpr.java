package az.mbm.jooqsqlgenerate.builder;

import az.mbm.jooqsqlgenerate.enums.NullDefault;

/**
 * FLUENT BUILDER — riyazi SELECT sütunu üçün sadə DSL.
 *
 * <p>{@link ComputedField}-i tamamilə gizlədir. İstifadəçi yalnız bu sinif
 * ilə işləyir.
 *
 * <p>İki istifadə forması var:
 *
 * <pre>{@code
 * // ── Forma 1: computedColumn() ilə standalone ──────────────────────────
 * .computedColumn(
 *     MathExpr.of("wf.total_Price_In")
 *         .subtract("wf.total_Price_Out")
 *         .multiply("wf.rate")
 *         .subtract(MathExpr.group("wf.purchase_Expense").multiply("wf.count"))
 *         .az("profit")                    //  ← ComputedField qaytarır
 * )
 *
 * // ── Forma 2: .compute() ilə birbaşa builder zəncirində ────────────────
 * factory.select(WarehouseFlow.class, "wf")
 *     .compute("wf.total_Price_In")
 *         .subtract("wf.total_Price_Out")
 *         .multiply("wf.rate")
 *         .subtract(MathExpr.group("wf.purchase_Expense").multiply("wf.count"))
 *         .as("profit").done()             //  ← SelectQueryBuilder-ə qayıdır
 *     .build(dsl);
 * }</pre>
 *
 * <p><b>Qeyd:</b> İç içə mötərizəli qrup üçün {@link #group(String)} istifadə edin —
 * {@link #az(String)} çağırılmadan birbaşa operand kimi verilir.
 *
 * <pre>{@code
 *   // (price * qty) + (discount * qty)
 *   MathExpr.of("o.price")
 *       .multiply("o.qty")
 *       .add(MathExpr.group("o.discount").multiply("o.qty"))
 *       .az("lineTotal")
 * }</pre>
 */
public class MathExpr {

    /** Daxili ComputedField — xaricdən görünmür */
    private final ComputedField cf;

    private MathExpr(String tableAliasAndField) {
        this.cf = ComputedField.of(tableAliasAndField);
    }

    /** İç içə qrup üçün — alias olmadan, birbaşa operand kimi istifadə edilir */
    private MathExpr(ComputedField expr) {
        this.cf = expr;
    }

    // ─── Statik giriş nöqtələri ──────────────────────────────────────────

    /**
     * Hesablamanı başladır.
     *
     * <pre>{@code MathExpr.of("wf.total_Price_In").subtract("wf.total_Price_Out").az("diff") }</pre>
     */
    public static MathExpr of(String tableAliasAndField) {
        return new MathExpr(tableAliasAndField);
    }

    /**
     * Mötərizəli alt-qrup — alias olmadan, başqa {@code MathExpr}-ə operand kimi verilir.
     *
     * <pre>{@code
     *   // a - (b * c)
     *   MathExpr.of("t.a")
     *       .subtract(MathExpr.group("t.b").multiply("t.c"))
     *       .az("result")
     * }</pre>
     */
    public static MathExpr group(String tableAliasAndField) {
        return new MathExpr(tableAliasAndField);
    }

    // ─── Riyazi əməliyyatlar — sadə sahə ────────────────────────────────

    /** {@code + field} */
    public MathExpr add(String tableAliasAndField) {
        cf.add(tableAliasAndField);
        return this;
    }

    /** {@code - field} */
    public MathExpr subtract(String tableAliasAndField) {
        cf.subtract(tableAliasAndField);
        return this;
    }

    /** {@code * field} */
    public MathExpr multiply(String tableAliasAndField) {
        cf.multiply(tableAliasAndField);
        return this;
    }

    /** {@code / field} */
    public MathExpr divide(String tableAliasAndField) {
        cf.divide(tableAliasAndField);
        return this;
    }

    // ─── NullDefault — LEFT JOIN null sahələri üçün ──────────────────────

    /**
     * LEFT JOIN-dən gələn null sahələr üçün bütün zəncirə COALESCE strategiyası tətbiq edir.
     *
     * <p>Default davranış {@link NullDefault#NONE}-dir — heç bir sahə null gəlsə
     * bütün ifadə (SQL standart davranışı ilə) null olur. Bunu önləmək üçün:
     *
     * <pre>{@code
     *   .compute("o.price")
     *       .multiply("o.qty")
     *       .withNullDefault(NullDefault.ZERO)   // qty null gəlsə 0 kimi hesablanır
     *       .as("lineTotal")
     * }</pre>
     */
    public MathExpr withNullDefault(NullDefault nd) {
        cf.withNullDefault(nd);
        return this;
    }

    /** {@code + COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
    public MathExpr addNullAs(String tableAliasAndField, Number nullAs) {
        cf.addNullAs(tableAliasAndField, nullAs);
        return this;
    }

    /** {@code - COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
    public MathExpr subtractNullAs(String tableAliasAndField, Number nullAs) {
        cf.subtractNullAs(tableAliasAndField, nullAs);
        return this;
    }

    /** {@code * COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
    public MathExpr multiplyNullAs(String tableAliasAndField, Number nullAs) {
        cf.multiplyNullAs(tableAliasAndField, nullAs);
        return this;
    }

    /** {@code / COALESCE(field, nullAs)} — yalnız bu addım üçün null default. */
    public MathExpr divideNullAs(String tableAliasAndField, Number nullAs) {
        cf.divideNullAs(tableAliasAndField, nullAs);
        return this;
    }

    // ─── Riyazi əməliyyatlar — iç içə mötərizəli qrup ───────────────────

    /**
     * {@code + (group)} — mötərizəli alt-qrup.
     *
     * <pre>{@code .add(MathExpr.group("o.tax").multiply("o.qty")) }</pre>
     */
    public MathExpr add(MathExpr group) {
        cf.add(group.cf);
        return this;
    }

    /**
     * {@code - (group)} — mötərizəli alt-qrup.
     *
     * <pre>{@code .subtract(MathExpr.group("o.discount").multiply("o.qty")) }</pre>
     */
    public MathExpr subtract(MathExpr group) {
        cf.subtract(group.cf);
        return this;
    }

    /**
     * {@code * (group)} — mötərizəli alt-qrup.
     *
     * <pre>{@code .multiply(MathExpr.group("o.price").add("o.tax")) }</pre>
     */
    public MathExpr multiply(MathExpr group) {
        cf.multiply(group.cf);
        return this;
    }

    /**
     * {@code / (group)} — mötərizəli alt-qrup.
     *
     * <pre>{@code .divide(MathExpr.group("o.revenue").add("o.other")) }</pre>
     */
    public MathExpr divide(MathExpr group) {
        cf.divide(group.cf);
        return this;
    }

    // ─── Çıxış nöqtəsi — standalone istifadə üçün ───────────────────────

    /**
     * SELECT alias-ı təyin edir və {@link ComputedField} qaytarır.
     * {@code .computedColumn()} metoduna birbaşa verilə bilər.
     *
     * <pre>{@code
     *   .computedColumn(
     *       MathExpr.of("wf.totalIn")
     *           .subtract("wf.totalOut")
     *           .az("diff")
     *   )
     * }</pre>
     *
     * @param alias SELECT sütun adı
     * @return hazır {@link ComputedField}
     */
    public ComputedField az(String alias) {
        return cf.as(alias);
    }

    // ─── Package-private — MathStep üçün daxili çevirmə ─────────────────

    /**
     * Daxili {@link ComputedField}-ə alias təyin edib qaytarır.
     * Yalnız {@code SelectQueryBuilder.MathStep.done()} tərəfindən istifadə olunur.
     */
    ComputedField buildWithAlias(String alias) {
        return cf.as(alias);
    }
}
