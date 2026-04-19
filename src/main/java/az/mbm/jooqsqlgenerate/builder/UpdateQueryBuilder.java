package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.spec.Specification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * FLUENT BUILDER — UPDATE sorğusu üçün
 *
 * <p>Köhnə {@code JooqUpdateManager}-in problemləri:
 * <ol>
 *   <li>Xarici asılılıqlar (SessionManager, QDate) — bu library-yə aid deyildi</li>
 *   <li>Null yoxlaması {@code &&} ilə — {@link NullPointerException} riski</li>
 *   <li>{@code setIn/setNotIn}-da sonsuz rekursiya</li>
 *   <li>Mutable state — {@code reset()} çağırmağı unutmaq mümkün idi</li>
 *   <li>WHERE olmadan UPDATE icra ola bilirdi — bütün cədvəli dəyişirdi</li>
 * </ol>
 *
 * <p>Yeni design — bütün problemlər aradan qaldırılmışdır:
 * <pre>{@code
 *   new UpdateQueryBuilder<>(User.class, dsl)
 *       .set("status", "INACTIVE")
 *       .set("email",  "new@example.com")
 *       .where(Spec.eq("id", userId))
 *       .execute();
 * }</pre>
 *
 * @param <T> entity tipi
 */
public class UpdateQueryBuilder<T> {

    private final Class<T>              entityClass;
    private final DSLContext            dsl;
    private final Map<String, Object>   setValues = new LinkedHashMap<>();
    private       Specification<T>      whereSpec = null;

    public UpdateQueryBuilder(Class<T> entityClass, DSLContext dsl) {
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class null ola bilməz");
        this.dsl         = Objects.requireNonNull(dsl, "DSLContext null ola bilməz");
    }

    // ─── SET dəyərləri ───────────────────────────────────────────────────

    /**
     * SET field = value əlavə edir.
     *
     * @param field Java field adı (camelCase)
     * @param value yeni dəyər
     */
    public UpdateQueryBuilder<T> set(String field, Object value) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("Sahə adı boş ola bilməz");
        Objects.requireNonNull(value, "Dəyər null ola bilməz: " + field);
        setValues.put(field, value);
        return this;
    }

    // ─── WHERE ───────────────────────────────────────────────────────────

    /**
     * WHERE şərtidir — mütləq tələb olunur.
     * Bir neçə {@code .where()} çağrışı AND ilə birləşdirilir.
     *
     * @throws IllegalStateException {@code execute()} zamanı WHERE yoxdursa
     */
    public UpdateQueryBuilder<T> where(Specification<T> spec) {
        this.whereSpec = (this.whereSpec == null) ? spec : this.whereSpec.and(spec);
        return this;
    }

    // ─── Execute ─────────────────────────────────────────────────────────

    /**
     * UPDATE icra edir.
     *
     * @return dəyişdirilmiş sətirlərin sayı
     * @throws IllegalStateException SET yoxdursa və ya WHERE yoxdursa
     */
    public int execute() {
        if (setValues.isEmpty())
            throw new IllegalStateException("UpdateQueryBuilder: heç bir SET dəyəri verilməyib");
        if (whereSpec == null)
            throw new IllegalStateException(
                    "UpdateQueryBuilder: WHERE şərti olmadan UPDATE icra etmək qadağandır. " +
                    ".where(Spec.eq(...)) əlavə edin.");

        EntityTable<T> table     = new EntityTable<>(entityClass);
        Condition      condition = whereSpec.toCondition(table);
        if (condition == null)
            throw new IllegalStateException("WHERE şərti null Condition qaytardı");

        UpdateSetFirstStep<?> step1 = dsl.update(table.getTable());
        UpdateSetMoreStep<?>  step2 = null;

        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            Field<Object> field = table.getField(entry.getKey());
            // DSL.val() istifadə etmirik: Val<Object> həm Object həm Field<Object> olduğundan
            // jOOQ-un set(Field<T>,T) vs set(Field<T>,Field<T>) overload-ları arasında
            // ambiguity yaranır. Dəyəri birbaşa vermək bu problemi həll edir.
            step2 = applySet(step1, step2, field, entry.getValue());
        }

        return step2.where(condition).execute();
    }

    /**
     * Sorğunu icra etmədən SQL stringini qaytarır (debug üçün).
     */
    public String toSQL() {
        if (setValues.isEmpty() || whereSpec == null)
            throw new IllegalStateException("toSQL() üçün SET və WHERE tələb olunur");

        EntityTable<T> table     = new EntityTable<>(entityClass);
        Condition      condition = whereSpec.toCondition(table);

        UpdateSetFirstStep<?> step1 = dsl.update(table.getTable());
        UpdateSetMoreStep<?>  step2 = null;

        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            Field<Object> field = table.getField(entry.getKey());
            step2 = applySet(step1, step2, field, entry.getValue());
        }

        return step2.where(condition).getSQL();
    }

    // ─── Yardımcı: SET ambiguity-ni aradan qaldırır ───────────────────────

    /**
     * Raw tip vasitəsilə jOOQ-un overload ambiguity-sini aradan qaldırır.
     * set(Field<T>, T) vs set(Field<T>, Field<T>) arasında qarışıqlıq olmur.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static UpdateSetMoreStep<?> applySet(
            UpdateSetFirstStep<?> step1,
            UpdateSetMoreStep<?>  step2,
            Field<Object>         field,
            Object                value) {
        // Raw UpdateSetStep istifadə edərək overload seçimini kompilyatora buraxmırıq
        UpdateSetStep rawStep = step2 != null ? (UpdateSetStep) step2 : (UpdateSetStep) step1;
        return (UpdateSetMoreStep<?>) rawStep.set(field, value);
    }
}
