package az.mbm.jooqsqlgenerate.builder;

import az.mbm.jooqsqlgenerate.enums.MathOp;

/**
 * AQREQAT İFADƏSİ ÜÇÜN OXUNAQLI BUILDER — {@code plus / minus}.
 *
 * <p>{@code addAggFunctionOnComputed(Agg.SUM, ComputedField.sumOf(...).subtract(...), alias)}
 * formasının daha səliqəli qarşılığı. Riyazi ekvivalentlikdən istifadə edir:
 * {@code (a + b) - (c + d) = a + b - c - d} — yəni iç-içə {@code sumOf(...)}
 * qrupları düz zəncirlə ifadə oluna bilər.
 *
 * <p><b>İstifadə</b> ({@link az.mbm.jooqsqlgenerate.JooqManager#addSumExpr} ilə):
 * <pre>{@code
 *   // SUM( marginalCostOut + purchaseExpense*actionOut
 *   //      - marginalCostIn - purchaseExpense*actionIn )  AS totalPrice
 *   jooq.addSumExpr("totalPrice", e -> e
 *       .plus("t.marginalCostOut")
 *       .plus("t.totalPurchaseExpense", "t.actionOut")    // + (f1 * f2)
 *       .minus("t.marginalCostIn")
 *       .minus("t.totalPurchaseExpense", "t.actionIn"));  // - (f1 * f2)
 * }</pre>
 *
 * <p>Mürəkkəb hallar üçün hər terminə hazır {@link ComputedField} də vermək olar:
 * <pre>{@code
 *   jooq.addSumExpr("net", e -> e
 *       .plus(ComputedField.expr("t.price").multiply("t.qty").round(2))
 *       .minus("t.discount"));
 * }</pre>
 *
 * <p>Zəncir mütləq {@code plus(...)} ilə başlamalıdır — ilk termin mənfi ola bilməz
 * (SQL-də unar minus əvəzinə {@code 0 - x} yazmaq lazım gələrdi; aydınlıq üçün qadağandır).
 */
public final class AggExpr {

    private ComputedField expr;

    private AggExpr() {}

    /** Boş builder yaradır — adətən birbaşa yox, {@code addSumExpr(alias, e -> ...)} vasitəsilə istifadə olunur. */
    public static AggExpr create() {
        return new AggExpr();
    }

    // ─── Terminlər ───────────────────────────────────────────────────────

    /** {@code + alias.field} — ilk termin üçün də istifadə olunur. */
    public AggExpr plus(String tableAliasAndField) {
        expr = (expr == null)
                ? ComputedField.expr(tableAliasAndField)
                : expr.add(tableAliasAndField);
        return this;
    }

    /** {@code + (ifadə)} — hazır {@link ComputedField} termini. */
    public AggExpr plus(ComputedField term) {
        expr = (expr == null) ? term : expr.add(term);
        return this;
    }

    /** {@code - alias.field} */
    public AggExpr minus(String tableAliasAndField) {
        requireFirstTerm("minus");
        expr = expr.subtract(tableAliasAndField);
        return this;
    }

    /** {@code - (ifadə)} */
    public AggExpr minus(ComputedField term) {
        requireFirstTerm("minus");
        expr = expr.subtract(term);
        return this;
    }

    // ─── Hasil qısayolları — ən çox rast gəlinən "f1 * f2" termini ────────

    /**
     * {@code + (f1 * f2)} — iki sahənin hasilini əlavə edir.
     *
     * <pre>{@code
     *   .plus("t.totalPurchaseExpense", "t.actionOut")
     *   // → + (total_purchase_expense * action_out)
     * }</pre>
     */
    public AggExpr plus(String field1, String field2) {
        return plus(ComputedField.expr(field1).multiply(field2));
    }

    /**
     * {@code - (f1 * f2)} — iki sahənin hasilini çıxır.
     *
     * <pre>{@code
     *   .minus("t.totalPurchaseExpense", "t.actionIn")
     *   // → - (total_purchase_expense * action_in)
     * }</pre>
     */
    public AggExpr minus(String field1, String field2) {
        return minus(ComputedField.expr(field1).multiply(field2));
    }

    // ─── İstənilən əməliyyatlı termin — bölmə daxil ───────────────────────

    /**
     * {@code + (f1 OP f2)} — termin daxilində istənilən riyazi əməliyyat.
     *
     * <pre>{@code
     *   .plus("t.totalPrice", MathOp.DIVIDE, "t.qty")
     *   // → + (total_price / NULLIF(qty, 0))     ← sıfıra bölmə qorunması avtomatik
     * }</pre>
     */
    public AggExpr plus(String field1, MathOp op, String field2) {
        return plus(term(field1, op, field2));
    }

    /**
     * {@code - (f1 OP f2)} — termin daxilində istənilən riyazi əməliyyat.
     *
     * <pre>{@code
     *   .minus("t.discount", MathOp.DIVIDE, "t.rate")
     *   // → - (discount / NULLIF(rate, 0))
     * }</pre>
     */
    public AggExpr minus(String field1, MathOp op, String field2) {
        return minus(term(field1, op, field2));
    }

    /** {@code f1 OP f2} terminini ComputedField kimi qurur. DIVIDE → NULLIF qorunması ComputedField-dədir. */
    private static ComputedField term(String field1, MathOp op, String field2) {
        ComputedField base = ComputedField.expr(field1);
        if (op == null) return base.multiply(field2); // default — köhnə 2-arg davranışı
        return switch (op) {
            case ADD      -> base.add(field2);
            case SUBTRACT -> base.subtract(field2);
            case MULTIPLY -> base.multiply(field2);
            case DIVIDE   -> base.divide(field2);
            default       -> base;
        };
    }

    // ─── Nəticə ──────────────────────────────────────────────────────────

    /**
     * Yığılmış ifadəni {@link ComputedField} kimi qaytarır.
     *
     * @throws IllegalStateException heç bir termin əlavə edilməyibsə
     */
    public ComputedField build() {
        if (expr == null) {
            throw new IllegalStateException(
                    "AggExpr: heç bir termin əlavə edilməyib — ən azı bir plus(...) çağırın.");
        }
        return expr;
    }

    private void requireFirstTerm(String method) {
        if (expr == null) {
            throw new IllegalStateException(
                    "AggExpr: zəncir plus(...) ilə başlamalıdır — ." + method + "(...) ilk termin ola bilməz.");
        }
    }
}
