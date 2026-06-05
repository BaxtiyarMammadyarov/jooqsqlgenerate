package az.mbm.jooqsqlgenerate.enums;

/**
 * LEFT JOIN null sahəsi üçün riyazi əməliyyatlarda istifadə ediləcək COALESCE default strategiyası.
 *
 * <p>Seçim qaydası:
 * <ul>
 *   <li>{@link #ZERO} — ADD / SUBTRACT üçün tövsiyə edilir:
 *       yoxluq = 0, nəticəyə təsir etmir.</li>
 *   <li>{@link #ONE}  — MULTIPLY / DIVIDE üçün istifadə edilə bilər:
 *       yoxluq = 1, dəyəri dəyişdirmir. Lakin domain semantikasına diqqət edin —
 *       çox hallarda {@link #ZERO} daha doğrudur.</li>
 *   <li>{@link #NONE} — COALESCE tətbiq edilmir, DB davranışı qorunur (default).</li>
 * </ul>
 *
 * <pre>{@code
 *   // Bütün zəncirə eyni default:
 *   ComputedField.of("o.price")
 *       .multiply("o.qty")
 *       .withNullDefault(NullDefault.ZERO)
 *       .as("lineTotal")
 *   // → COALESCE(price,0) * COALESCE(qty,0) AS lineTotal
 *
 *   // Per-step dəqiq nəzarət:
 *   ComputedField.of("o.price")
 *       .multiplyNullAs("o.qty", 0)   // qty null → 0
 *       .as("lineTotal")
 *
 *   // 2-sahəli sadə forma:
 *   .computedColumn("net", "o", MathOp.SUBTRACT, "amount", "o", "discount", NullDefault.ZERO)
 * }</pre>
 */
public enum NullDefault {

    /** NULL sahə 0 kimi hesablanır */
    ZERO,

    /** NULL sahə 1 kimi hesablanır */
    ONE,

    /** COALESCE tətbiq edilmir — DB davranışı (default) */
    NONE;

    /** Enum-a uyğun numeric dəyər. {@code NONE} üçün {@code null} qaytarır. */
    public Number numericValue() {
        return switch (this) {
            case ZERO -> 0;
            case ONE  -> 1;
            case NONE -> null;
        };
    }
}
