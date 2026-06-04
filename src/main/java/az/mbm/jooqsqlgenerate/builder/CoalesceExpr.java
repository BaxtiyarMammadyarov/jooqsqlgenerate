package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQL {@code COALESCE(f1, f2, ..., default)} ifadəsi — istənilən kontekstdə istifadə üçün.
 *
 * <p>Mövcud {@code JooqQuery.coalesce()} SELECT sütunu kimi işləyirdi.
 * Bu sinif isə {@link ComputedField}, {@link AggregateBuilder} və
 * {@link ConcatItem} daxilindən çağrıla bilər.
 *
 * <p>Dəyər həll qaydası — {@link IfExpr} ilə eynidir:
 * <ul>
 *   <li>String {@code "alias.field"} — alias tableMap-də varsa sütun referansı</li>
 *   <li>Hər hansı digər dəyər — SQL literal (inline)</li>
 * </ul>
 *
 * <pre>{@code
 *   // Sütunlar arasında COALESCE:
 *   CoalesceExpr.of("u.nickname", "u.firstName", "u.email")
 *   // → COALESCE(nickname, first_name, email)
 *
 *   // Literal default ilə:
 *   CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim")
 *   // → COALESCE(nickname, first_name, 'Anonim')
 *
 *   // ComputedField-də:
 *   ComputedField.coalesce("u.nickname", "u.firstName").orElse("N/A").as("displayName")
 *
 *   // ConcatItem-də:
 *   ConcatItem.coalesce("u.firstName", "u.email")
 * }</pre>
 */
public final class CoalesceExpr {

    private final List<Object> values;
    private       Object       defaultValue = null;

    private CoalesceExpr(List<Object> values) {
        this.values = new ArrayList<>(values);
    }

    // ─── Factory ─────────────────────────────────────────────────────────

    /**
     * COALESCE üçün sütun/dəyər siyahısı.
     *
     * <pre>{@code
     *   CoalesceExpr.of("u.nickname", "u.firstName", "u.email")
     *   CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim")
     * }</pre>
     *
     * @param fieldsOrValues  sütun adları ({@code "alias.field"}) və ya literal dəyərlər
     */
    public static CoalesceExpr of(Object... fieldsOrValues) {
        if (fieldsOrValues == null || fieldsOrValues.length == 0)
            throw new IllegalArgumentException("CoalesceExpr: ən azı bir dəyər lazımdır");
        return new CoalesceExpr(Arrays.asList(fieldsOrValues));
    }

    /**
     * Siyahının sonuna literal default dəyər əlavə edir.
     *
     * <pre>{@code
     *   CoalesceExpr.of("u.firstName", "u.lastName").orElse("N/A")
     *   // → COALESCE(first_name, last_name, 'N/A')
     * }</pre>
     */
    public CoalesceExpr orElse(Object defaultValue) {
        this.defaultValue = Objects.requireNonNull(defaultValue,
                "CoalesceExpr.orElse: defaultValue null ola bilməz");
        return this;
    }

    // ─── jOOQ Field-ə çevirmə ────────────────────────────────────────────

    /**
     * jOOQ {@link Field} kimi render edir.
     *
     * @param mainTable  ana entity table
     * @param tableMap   alias → EntityTable xəritəsi
     */
    @SuppressWarnings({"rawtypes"})
    public Field<?> toField(EntityTable<?> mainTable,
                            Map<String, EntityTable<?>> tableMap) {
        List<Field<?>> parts = new ArrayList<>();

        for (Object v : values) {
            parts.add(IfExpr.resolveValue(v, mainTable, tableMap));
        }

        if (defaultValue != null) {
            parts.add(DSL.inline(defaultValue));
        }

        if (parts.isEmpty())
            throw new IllegalStateException("CoalesceExpr: siyahı boşdur");

        Field<?> first = parts.get(0);
        Field<?>[] rest = parts.subList(1, parts.size()).toArray(new Field[0]);
        return DSL.coalesce(first, rest);
    }
}
