package az.mbm.jooqsqlgenerate.spec;

import org.jooq.Condition;
import az.mbm.jooqsqlgenerate.core.EntityTable;

/**
 * SPECIFICATION PATTERN — composable WHERE şərti
 *
 * <p>Hər bir WHERE şərti ayrı bir Specification-dur.
 * {@link #and}, {@link #or}, {@link #not} vasitəsilə birləşdirilə bilər
 * (Composite Pattern).
 *
 * <pre>{@code
 *   Spec.eq("status", "ACTIVE")
 *       .and(Spec.in("roleId", List.of(1, 2, 3)))
 *       .and(Spec.gt("age", 18))
 *       .or(Spec.isNull("deletedAt"))
 * }</pre>
 *
 * @param <T> entity tipi
 * @see Spec — hazır statik factory metodları
 * @see ExistsSpec — EXISTS / NOT EXISTS şərtləri
 */
@FunctionalInterface
public interface Specification<T> {

    /**
     * Spesifikasiyanı jOOQ {@link Condition}-a çevirir.
     *
     * @param table jOOQ EntityTable (cədvəl + sahə xəritəsi)
     * @return jOOQ Condition; {@code null} qaytarılması şərtin yoxluğunu bildirir
     */
    Condition toCondition(EntityTable<T> table);

    // ─── Composite Pattern ────────────────────────────────────────────────

    /** {@code this AND other} */
    default Specification<T> and(Specification<T> other) {
        return table -> {
            Condition left  = this.toCondition(table);
            Condition right = other.toCondition(table);
            if (left == null)  return right;
            if (right == null) return left;
            return left.and(right);
        };
    }

    /** {@code this OR other} */
    default Specification<T> or(Specification<T> other) {
        return table -> {
            Condition left  = this.toCondition(table);
            Condition right = other.toCondition(table);
            if (left == null)  return right;
            if (right == null) return left;
            return left.or(right);
        };
    }

    /** {@code NOT this} */
    default Specification<T> not() {
        return table -> {
            Condition c = this.toCondition(table);
            return c == null ? null : c.not();
        };
    }
}
