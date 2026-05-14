package az.mbm.jooqsqlgenerate.builder;

/**
 * CONCAT əməliyyatında iştirak edən element.
 * <p>İki növü var:</p>
 * <ul>
 *   <li>{@link #field(String)} — cədvəl sütunu (alias.fieldName formatında)</li>
 *   <li>{@link #literal(String)} — sabit string dəyər (SQL inline)</li>
 * </ul>
 *
 * <pre>{@code
 * import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;
 *
 * manager.addConcatColumn("userCode", "-", literal("USR"), field("u.userId"))
 * // SQL: 'USR' || '-' || COALESCE(userId,'')
 *
 * manager.addConcatColumn("label", " ", literal("Ad:"), field("u.firstName"), field("u.lastName"))
 * // SQL: 'Ad:' || ' ' || COALESCE(firstName,'') || ' ' || COALESCE(lastName,'')
 * }</pre>
 */
public sealed interface ConcatItem permits ConcatItem.ColField, ConcatItem.Literal {

    /** Cədvəl sütunu — {@code "alias.fieldName"} və ya {@code "fieldName"} formatında. */
    record ColField(String aliasAndField) implements ConcatItem {}

    /** Sabit string dəyər — SQL-də {@code inline('value')} kimi render olunur. */
    record Literal(String value) implements ConcatItem {}

    /** Sütun referansı yaradır. */
    static ConcatItem field(String aliasAndField) {
        return new ColField(aliasAndField);
    }

    /** Sabit string dəyər yaradır. */
    static ConcatItem literal(String value) {
        return new Literal(value);
    }
}
