package az.mbm.jooqsqlgenerate;

import az.mbm.jooqsqlgenerate.builder.CaseBuilder;
import az.mbm.jooqsqlgenerate.builder.SubSelectBuilder;
import az.mbm.jooqsqlgenerate.enums.Op;

import java.util.Arrays;
import java.util.Collection;

/**
 * Inline scalar subquery builder — {@link JooqManager} zəncirinə birbaşa qoşulur.
 *
 * <p>{@code SubSelectBuilder} import etmədən SELECT-də scalar subquery yazmaq üçündür.
 * {@link JooqManager#addSubQueryColumn(Class, String)} ilə açılır,
 * {@link #done()} ilə {@link JooqManager}-ə qayıdır.
 *
 * <pre>{@code
 *   // SELECT (SELECT p.name FROM products p WHERE p.id = o.product_id) AS productName
 *   jooq.addSubQueryColumn(Product.class, "p")
 *           .select("p.name")
 *           .correlateOn("p.id", "o.productId")
 *           .filter("active", Op.EQUAl, true)
 *           .as("productName")
 *       .done()
 * }</pre>
 */
public final class JooqSubSelectBuilder {

    private final JooqManager      parent;
    private final SubSelectBuilder sub;

    JooqSubSelectBuilder(JooqManager parent, Class<?> entity, String alias) {
        this.parent = parent;
        this.sub    = SubSelectBuilder.from(entity, alias);
    }

    // ─── SELECT ifadəsi ───────────────────────────────────────────────────

    /** Sadə sahə: {@code .select("p.name")} */
    public JooqSubSelectBuilder select(String tableAliasAndField) {
        sub.select(tableAliasAndField);
        return this;
    }

    /** {@code CONCAT(...)}: {@code .selectConcat(" ", "u.firstName", "u.lastName")} */
    public JooqSubSelectBuilder selectConcat(String separator, String... fields) {
        sub.selectConcat(separator, fields);
        return this;
    }

    /** {@code COALESCE(...)}: {@code .selectCoalesce("Naməlum", "u.nickname", "u.firstName")} */
    public JooqSubSelectBuilder selectCoalesce(Object defaultValue, String... fields) {
        sub.selectCoalesce(defaultValue, fields);
        return this;
    }

    /** {@code CASE WHEN ...}: {@code .selectCase(CaseBuilder.when(...).then(...).otherwise(...))} */
    public JooqSubSelectBuilder selectCase(CaseBuilder<?> cb) {
        sub.selectCase(cb);
        return this;
    }

    // ─── Korrelyasiya ─────────────────────────────────────────────────────

    /** Outer sorğu ilə əlaqə: {@code .correlateOn("p.id", "o.productId")} */
    public JooqSubSelectBuilder correlateOn(String innerField, String outerField) {
        sub.correlateOn(innerField, outerField);
        return this;
    }

    // ─── Əlavə WHERE filterlər ────────────────────────────────────────────

    /** Subquery-yə WHERE şərti: {@code .filter("active", Op.EQUAl, true)} — value null-dursa atlanır */
    public JooqSubSelectBuilder filter(String field, Op op, Object value) {
        sub.addFilter(field, op, value);
        return this;
    }

    /** {@code WHERE field = value} — value null-dursa atlanır */
    public JooqSubSelectBuilder equal(String field, Object value) {
        return filter(field, Op.EQUAl, value);
    }

    /** {@code WHERE field != value} — value null-dursa atlanır */
    public JooqSubSelectBuilder notEqual(String field, Object value) {
        return filter(field, Op.NOT_EQUAL, value);
    }

    /** {@code WHERE field IN (...)} — null/boş kolleksiya atlanır */
    public JooqSubSelectBuilder in(String field, Collection<?> values) {
        if (values != null && !values.isEmpty()) filter(field, Op.IN, values);
        return this;
    }

    /** {@code WHERE field IN (...)} — varargs */
    public JooqSubSelectBuilder in(String field, Object... values) {
        return values != null && values.length > 0
                ? in(field, Arrays.asList(values))
                : this;
    }

    /** {@code WHERE field NOT IN (...)} — null/boş kolleksiya atlanır */
    public JooqSubSelectBuilder notIn(String field, Collection<?> values) {
        if (values != null && !values.isEmpty()) filter(field, Op.NOT_IN, values);
        return this;
    }

    /** {@code WHERE field NOT IN (...)} — varargs */
    public JooqSubSelectBuilder notIn(String field, Object... values) {
        return values != null && values.length > 0
                ? notIn(field, Arrays.asList(values))
                : this;
    }

    /** {@code WHERE field LIKE '%value%'} — null/boş atlanır */
    public JooqSubSelectBuilder like(String field, String value) {
        return value != null && !value.isBlank()
                ? filter(field, Op.LIKE, value)
                : this;
    }

    /** {@code WHERE field IS NULL} */
    public JooqSubSelectBuilder isNull(String field) {
        return filter(field, Op.IS_EMPTY, "");
    }

    /** {@code WHERE field IS NOT NULL} */
    public JooqSubSelectBuilder isNotNull(String field) {
        return filter(field, Op.IS_NOT_EMPTY, "");
    }

    // ─── LIMIT ────────────────────────────────────────────────────────────

    /**
     * {@code LIMIT n}. Default limit yoxdur — subquery çox sətir qaytarsa DB xəta atır.
     * Unique olmayan korrelyasiyada sığorta: {@code .limit(1)}
     */
    public JooqSubSelectBuilder limit(int rows) {
        sub.limit(rows);
        return this;
    }

    // ─── Alias ────────────────────────────────────────────────────────────

    /** SELECT-dəki nəticə alias-ı — mütləq tələb olunur. */
    public JooqSubSelectBuilder as(String alias) {
        sub.as(alias);
        return this;
    }

    // ─── Tamamlama ────────────────────────────────────────────────────────

    /** Builder-i tamamlayır, sütunu SELECT-ə əlavə edib {@link JooqManager}-ə qayıdır. */
    public JooqManager done() {
        if (sub.getAlias() == null)
            throw new IllegalStateException("JooqSubSelectBuilder: .as(alias) tələb olunur");
        return parent.addSubQueryColumn(sub);
    }
}
