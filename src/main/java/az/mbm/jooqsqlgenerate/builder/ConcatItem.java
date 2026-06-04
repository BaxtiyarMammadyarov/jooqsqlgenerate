package az.mbm.jooqsqlgenerate.builder;

/**
 * CONCAT əməliyyatında iştirak edən element.
 *
 * <p>Dörd növü var:
 * <ul>
 *   <li>{@link #field(String)} — cədvəl sütunu</li>
 *   <li>{@link #literal(String)} — sabit string dəyər</li>
 *   <li>{@link #ifExpr(String, Object, Object, Object)} — CASE WHEN şərtli dəyər</li>
 *   <li>{@link #coalesce(CoalesceExpr)} — COALESCE ifadəsi</li>
 * </ul>
 *
 * <pre>{@code
 * import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;
 *
 * // Sadə sütun + literal
 * addConcatColumn("userCode", "-", literal("USR"), field("u.id"))
 * // → 'USR' || '-' || COALESCE(id,'')
 *
 * // Şərtli dəyər
 * addConcatColumn("statusLabel", " | ", field("u.name"),
 *     ifExpr("o.status", "PAID", "Ödənilib", "Gözlənilir"))
 * // → COALESCE(name,'') || ' | ' || CASE WHEN status='PAID' THEN 'Ödənilib' ELSE 'Gözlənilir' END
 *
 * // COALESCE ilə
 * addConcatColumn("displayName", " ", literal("Ad:"),
 *     coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim")))
 * // → 'Ad:' || ' ' || COALESCE(nickname, first_name, 'Anonim')
 * }</pre>
 */
public sealed interface ConcatItem
        permits ConcatItem.ColField, ConcatItem.Literal,
                ConcatItem.IfItem,   ConcatItem.CoalesceItem {

    /** Cədvəl sütunu — {@code "alias.fieldName"} formatında. */
    record ColField(String aliasAndField) implements ConcatItem {}

    /** Sabit string dəyər — SQL-də {@code inline('value')} kimi render olunur. */
    record Literal(String value) implements ConcatItem {}

    /** CASE WHEN ifadəsi — {@link IfExpr} əsasında. */
    record IfItem(IfExpr expr) implements ConcatItem {}

    /** COALESCE ifadəsi — {@link CoalesceExpr} əsasında. */
    record CoalesceItem(CoalesceExpr expr) implements ConcatItem {}

    // ─── Factory metodları ───────────────────────────────────────────────

    /** Sütun referansı yaradır. */
    static ConcatItem field(String aliasAndField) {
        return new ColField(aliasAndField);
    }

    /** Sabit string dəyər yaradır. */
    static ConcatItem literal(String value) {
        return new Literal(value);
    }

    /**
     * {@code CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END} ifadəsi.
     *
     * <pre>{@code
     *   // Status adını göstər
     *   ConcatItem.ifExpr("o.status", "PAID", "Ödənilib", "Gözlənilir")
     *   // → CASE WHEN status='PAID' THEN 'Ödənilib' ELSE 'Gözlənilir' END
     *
     *   // Sütun referansı ilə — SALE olarsa amount, əks halda refundAmount
     *   ConcatItem.ifExpr("o.type", "SALE", "o.amount", "o.refundAmount")
     * }</pre>
     *
     * @param condField  şərt sütunu — {@code "alias.field"}
     * @param equalTo    bərabərlik dəyəri
     * @param thenVal    doğru isə — sütun adı ({@code "alias.field"}) və ya literal
     * @param elseVal    yanlış isə — sütun adı ({@code "alias.field"}) və ya literal
     */
    static ConcatItem ifExpr(String condField, Object equalTo,
                             Object thenVal,   Object elseVal) {
        return new IfItem(IfExpr.of(condField, equalTo, thenVal, elseVal));
    }

    /**
     * {@code COALESCE(f1, f2, ...)} ifadəsi — ilk null olmayan dəyəri götürür.
     *
     * <pre>{@code
     *   // nickname → firstName → "Anonim"
     *   ConcatItem.coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim"))
     *   // → COALESCE(nickname, first_name, 'Anonim')
     * }</pre>
     */
    static ConcatItem coalesce(CoalesceExpr expr) {
        return new CoalesceItem(expr);
    }
}
