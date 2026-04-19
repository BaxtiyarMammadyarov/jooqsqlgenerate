package az.mbm.jooqsqlgenerate.spec;

import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * FACTORY — hazır {@link Specification} nümunələri yaradır.
 *
 * <p>Bütün metodlar statikdir; sinif yaratmaq lazım deyil.
 *
 * <pre>{@code
 *   Spec.eq("status", "ACTIVE")
 *       .and(Spec.in("roleId", List.of(1, 2, 3)))
 *       .and(Spec.between("age", 18, 65))
 *       .or(Spec.isNull("deletedAt"))
 * }</pre>
 */
public final class Spec {

    private Spec() {}

    // ─── Bərabərlik ──────────────────────────────────────────────────────

    public static <T> Specification<T> eq(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).eq(DSL.val(value));
    }

    public static <T> Specification<T> notEq(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).ne(DSL.val(value));
    }

    // ─── Müqayisə ────────────────────────────────────────────────────────

    public static <T> Specification<T> gt(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).greaterThan(DSL.val(value));
    }

    public static <T> Specification<T> gte(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).greaterOrEqual(DSL.val(value));
    }

    public static <T> Specification<T> lt(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).lessThan(DSL.val(value));
    }

    public static <T> Specification<T> lte(String field, Object value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).lessOrEqual(DSL.val(value));
    }

    // ─── Null yoxlaması ──────────────────────────────────────────────────

    public static <T> Specification<T> isNull(String field) {
        requireField(field);
        return table -> table.getField(field).isNull();
    }

    public static <T> Specification<T> isNotNull(String field) {
        requireField(field);
        return table -> table.getField(field).isNotNull();
    }

    // ─── LIKE ────────────────────────────────────────────────────────────

    /** WHERE field LIKE '%value%' */
    public static <T> Specification<T> like(String field, String value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).like("%" + value + "%");
    }

    /** WHERE field LIKE 'value%' */
    public static <T> Specification<T> startWith(String field, String value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).like(value + "%");
    }

    /** WHERE field LIKE '%value' */
    public static <T> Specification<T> endWith(String field, String value) {
        requireField(field); requireValue(value);
        return table -> table.getField(field).like("%" + value);
    }

    // ─── IN / NOT IN ─────────────────────────────────────────────────────

    public static <T> Specification<T> in(String field, Collection<?> values) {
        requireField(field);
        if (values == null || values.isEmpty())
            throw new IllegalArgumentException("Spec.in: dəyərlər boş ola bilməz");
        return table -> table.getField(field)
                .in(values.stream().map(DSL::val).collect(Collectors.toList()));
    }

    public static <T> Specification<T> in(String field, Object... values) {
        return in(field, Arrays.asList(values));
    }

    public static <T> Specification<T> notIn(String field, Collection<?> values) {
        requireField(field);
        if (values == null || values.isEmpty())
            throw new IllegalArgumentException("Spec.notIn: dəyərlər boş ola bilməz");
        return table -> table.getField(field)
                .notIn(values.stream().map(DSL::val).collect(Collectors.toList()));
    }

    public static <T> Specification<T> notIn(String field, Object... values) {
        return notIn(field, Arrays.asList(values));
    }

    // ─── BETWEEN ─────────────────────────────────────────────────────────

    public static <T> Specification<T> between(String field, Object from, Object to) {
        requireField(field); requireValue(from); requireValue(to);
        return table -> table.getField(field).between(DSL.val(from), DSL.val(to));
    }

    // ─── REGEXP ──────────────────────────────────────────────────────────

    public static <T> Specification<T> regexp(String field, String pattern) {
        requireField(field); requireValue(pattern);
        return table -> table.getField(field).likeRegex(pattern);
    }

    public static <T> Specification<T> notRegexp(String field, String pattern) {
        requireField(field); requireValue(pattern);
        return table -> DSL.not(table.getField(field).likeRegex(pattern));
    }

    // ─── Validation ──────────────────────────────────────────────────────

    private static void requireField(String field) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("Sahə adı null və ya boş ola bilməz");
    }

    private static void requireValue(Object value) {
        if (value == null)
            throw new IllegalArgumentException("Dəyər null ola bilməz");
    }
}
