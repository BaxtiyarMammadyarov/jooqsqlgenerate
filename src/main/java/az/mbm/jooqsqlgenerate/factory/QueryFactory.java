package az.mbm.jooqsqlgenerate.factory;

import org.jooq.DSLContext;
import az.mbm.jooqsqlgenerate.builder.SelectQueryBuilder;
import az.mbm.jooqsqlgenerate.builder.UpdateQueryBuilder;

/**
 * FACTORY METHOD PATTERN — Sorğu builder-larını yaratmaq üçün giriş nöqtəsi
 *
 * <p>Köhnə kodda:
 * <pre>{@code
 *   ChwJooqManagerFactory factory = new ChwJooqManagerFactory(dsl);
 *   ChwJooqManager<UserDto, ?> manager = factory.createInstance();
 *   // Sonra hansı sıradan method çağırmaq lazım? Sənəd yox idi.
 * }</pre>
 *
 * <p>Yeni design — tip-təhlükəsiz, oxunaqlı API:
 * <pre>{@code
 *   QueryFactory factory = new QueryFactory(dsl);
 *
 *   // SELECT
 *   SelectTable result = factory.select(User.class, "u")
 *       .columns("u.id", "u.name")
 *       .where(Spec.eq("status", "ACTIVE"))
 *       .page(0, 20)
 *       .build(dsl);
 *
 *   // UPDATE
 *   int rows = factory.update(User.class)
 *       .set("status", "INACTIVE")
 *       .where(Spec.eq("id", userId))
 *       .execute();
 * }</pre>
 *
 * @see SelectQueryBuilder
 * @see UpdateQueryBuilder
 */
public final class QueryFactory {

    private final DSLContext dsl;

    /**
     * @param dsl jOOQ DSLContext — Spring-dən inject oluna bilər
     */
    public QueryFactory(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ─── Factory metodları ───────────────────────────────────────────────

    /**
     * SELECT builder-i yaradır.
     * Alias avtomatik entity adından götürülür (kiçik hərflə).
     *
     * <pre>{@code factory.select(User.class) // alias = "user" }</pre>
     */
    public <T> SelectQueryBuilder<T> select(Class<T> entity) {
        return SelectQueryBuilder.from(entity, entity.getSimpleName().toLowerCase());
    }

    /**
     * SELECT builder-i özəl alias ilə yaradır.
     *
     * <pre>{@code factory.select(User.class, "u") }</pre>
     */
    public <T> SelectQueryBuilder<T> select(Class<T> entity, String alias) {
        return SelectQueryBuilder.from(entity, alias);
    }

    /**
     * UPDATE builder-i yaradır.
     *
     * <pre>{@code factory.update(User.class) }</pre>
     */
    public <T> UpdateQueryBuilder<T> update(Class<T> entity) {
        return new UpdateQueryBuilder<>(entity, dsl);
    }

    /** DSLContext-i qaytarır (manual sorğular üçün) */
    public DSLContext dsl() {
        return dsl;
    }
}
