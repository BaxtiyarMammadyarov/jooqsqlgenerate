package az.mbm.jooqsqlgenerate.strategy;

import org.jooq.Condition;
import org.jooq.Field;

/**
 * STRATEGY PATTERN — Filtr əməliyyatı interfeysi
 *
 * <p>Köhnə kodda bütün filter əməliyyatları {@code JooqUtil}-da nəhəng
 * if/switch blokları ilə həll olunurdu (~200 sətir, 17 branch).
 *
 * <p>Yeni dizayn: hər filtr öz {@link Condition}-ını özü bilir.
 * Yeni filtr növü əlavə etmək üçün yalnız
 * {@link FilterStrategies#register(az.mbm.jooqsqlgenerate.enums.FilterOperations, FilterStrategy)}
 * çağırmaq kifayətdir — mövcud kodu dəyişmək lazım deyil (Open/Closed Principle).
 *
 * @see FilterStrategies
 */
@FunctionalInterface
public interface FilterStrategy {

    /**
     * @param field jOOQ sahəsi
     * @param value filtrin dəyəri (String, Set, List, ...)
     * @return jOOQ Condition
     */
    Condition apply(Field<Object> field, Object value);
}
