package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;

import java.util.*;

/**
 * FLUENT BUILDER — INSERT sorğusu üçün
 *
 * <p>Eyni strukturda {@link UpdateQueryBuilder} kimi işləyir.
 * Caller-in {@code @Transactional} kontekstinə avtomatik qoşulur —
 * Spring-in {@code DSLContext}-i aktiv tranzaksiyadan Connection götürür.
 *
 * <h3>Tək sətir insert:</h3>
 * <pre>{@code
 *   Long newId = new InsertQueryBuilder<>(User.class, dsl)
 *       .value("name",   "Əli")
 *       .value("status", "ACTIVE")
 *       .value("age",    25)
 *       .executeAndReturnId(Long.class);
 * }</pre>
 *
 * <h3>Çox sətir insert (batch):</h3>
 * <pre>{@code
 *   int count = new InsertQueryBuilder<>(User.class, dsl)
 *       .value("name",   "Əli")
 *       .value("status", "ACTIVE")
 *       .addRow()                    // ← birinci sətri bağla
 *       .value("name",   "Vüsal")
 *       .value("status", "INACTIVE")
 *       .executeBatch();
 * }</pre>
 *
 * <h3>ON DUPLICATE KEY UPDATE (upsert):</h3>
 * <pre>{@code
 *   new InsertQueryBuilder<>(User.class, dsl)
 *       .value("id",     42L)
 *       .value("status", "ACTIVE")
 *       .onDuplicateKeyUpdate("status", "ACTIVE")
 *       .execute();
 * }</pre>
 *
 * @param <T> entity tipi
 */
public class InsertQueryBuilder<T> {

    private final Class<T>    entityClass;
    private final DSLContext  dsl;

    // Cari sətrin dəyərləri
    private final Map<String, Object> currentRow = new LinkedHashMap<>();

    // Batch: bütün sətirlər
    private final List<Map<String, Object>> rows = new ArrayList<>();

    // ON DUPLICATE KEY UPDATE sahələri
    private final Map<String, Object> onDuplicateUpdate = new LinkedHashMap<>();

    // Boş dəyər saxlamaqdan imtina — null-lara icazə ver
    private boolean allowNulls = false;

    public InsertQueryBuilder(Class<T> entityClass, DSLContext dsl) {
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class null ola bilməz");
        this.dsl         = Objects.requireNonNull(dsl,         "DSLContext null ola bilməz");
    }

    // ─── Dəyər əlavə etmə ────────────────────────────────────────────────

    /**
     * Cari sətirə bir sahə dəyəri əlavə edir.
     *
     * @param field Java field adı (camelCase), məs: {@code "userId"}
     * @param value daxil ediləcək dəyər
     */
    public InsertQueryBuilder<T> value(String field, Object value) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("Sahə adı boş ola bilməz");
        if (value == null && !allowNulls)
            throw new IllegalArgumentException(
                    "Null dəyər qadağandır: '" + field + "'. " +
                    "Null daxil etmək üçün .allowNulls() çağırın.");
        currentRow.put(field, value);
        return this;
    }

    /**
     * Null dəyərlərə icazə verir.
     * Nullable sütunlara {@code NULL} yazmaq lazım olduqda istifadə edin.
     */
    public InsertQueryBuilder<T> allowNulls() {
        this.allowNulls = true;
        return this;
    }

    // ─── Batch dəstəyi ────────────────────────────────────────────────────

    /**
     * Cari sətri tamamlayır və növbəti sətir üçün hazırlanır.
     * Batch insert üçün istifadə edilir.
     *
     * <pre>{@code
     *   builder
     *       .value("name", "Əli").value("age", 25).addRow()
     *       .value("name", "Vüsal").value("age", 30)
     *       .executeBatch();
     * }</pre>
     */
    public InsertQueryBuilder<T> addRow() {
        if (currentRow.isEmpty())
            throw new IllegalStateException("addRow() çağırılmadan əvvəl ən azı bir .value() lazımdır");
        rows.add(new LinkedHashMap<>(currentRow));
        currentRow.clear();
        return this;
    }

    // ─── ON DUPLICATE KEY UPDATE ──────────────────────────────────────────

    /**
     * Upsert: eyni primary key varsa bu sahəni yenilə.
     *
     * <pre>{@code
     *   .onDuplicateKeyUpdate("status", "ACTIVE")
     *   .onDuplicateKeyUpdate("updatedAt", LocalDateTime.now())
     * }</pre>
     */
    public InsertQueryBuilder<T> onDuplicateKeyUpdate(String field, Object value) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("onDuplicateKeyUpdate: sahə adı boş ola bilməz");
        onDuplicateUpdate.put(field, value);
        return this;
    }

    // ─── Execute metodları ────────────────────────────────────────────────

    /**
     * INSERT icra edir. Daxil edilmiş sətirlərin sayını qaytarır.
     *
     * @return daxil edilmiş sətir sayı
     */
    public int execute() {
        flushCurrentRow();
        validateRows();

        EntityTable<T> table = new EntityTable<>(entityClass);

        if (rows.size() == 1) {
            return buildSingleInsert(table, rows.get(0)).execute();
        }
        return executeBatchInternal(table);
    }

    /**
     * INSERT icra edir və generasiya olunmuş primary key-i qaytarır.
     * Tək sətir insert üçün nəzərdə tutulub.
     *
     * <pre>{@code
     *   Long id = builder.value("name", "Əli").executeAndReturnId(Long.class);
     * }</pre>
     *
     * @param idType generasiya olunan key-in tipi (məs: {@code Long.class})
     * @return yeni sətrin primary key dəyəri; generasiya olunmayıbsa {@code null}
     */
    public <K> K executeAndReturnId(Class<K> idType) {
        flushCurrentRow();
        if (rows.size() != 1)
            throw new IllegalStateException(
                    "executeAndReturnId() yalnız tək sətir insert üçündür. " +
                    "Çox sətir üçün .execute() istifadə edin.");

        EntityTable<T> table = new EntityTable<>(entityClass);
        InsertResultStep<?> step = buildSingleInsert(table, rows.get(0)).returningResult();
        Record result = step.fetchOne();
        if (result == null) return null;

        Object val = result.getValue(0);
        return idType.cast(val);
    }

    /**
     * Batch INSERT icra edir. Çox sətir üçün daha effektivdir.
     *
     * @return daxil edilmiş sətirlərin ümumi sayı
     */
    public int executeBatch() {
        flushCurrentRow();
        validateRows();
        EntityTable<T> table = new EntityTable<>(entityClass);
        return executeBatchInternal(table);
    }

    /**
     * INSERT SQL-ini qaytarır (debug üçün, icra etmir).
     */
    public String toSQL() {
        flushCurrentRow();
        validateRows();
        EntityTable<T> table = new EntityTable<>(entityClass);
        return buildSingleInsert(table, rows.get(0)).getSQL();
    }

    // ─── Daxili yardımcılar ───────────────────────────────────────────────

    /**
     * Cari sətri (currentRow) əgər dolubsa rows siyahısına əlavə edir.
     * execute() çağırılmadan əvvəl addRow() çağırılmamış ola bilər.
     */
    private void flushCurrentRow() {
        if (!currentRow.isEmpty()) {
            rows.add(new LinkedHashMap<>(currentRow));
            currentRow.clear();
        }
    }

    private void validateRows() {
        if (rows.isEmpty())
            throw new IllegalStateException(
                    "InsertQueryBuilder: heç bir dəyər verilməyib. " +
                    "Ən azı bir .value() çağırın.");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private InsertOnDuplicateSetMoreStep<?> buildSingleInsert(
            EntityTable<T> table,
            Map<String, Object> rowValues) {

        List<Field<?>> fields = new ArrayList<>();
        List<Object>   values = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rowValues.entrySet()) {
            Field<Object> f = table.getField(entry.getKey());
            fields.add(f);
            values.add(coerce(f, entry.getValue()));
        }

        InsertValuesStepN<?> step = dsl
                .insertInto(table.getTable())
                .columns(fields)
                .values(values);

        // ON DUPLICATE KEY UPDATE
        if (!onDuplicateUpdate.isEmpty()) {
            InsertOnDuplicateSetStep<?> dupStep = step.onDuplicateKeyUpdate();
            InsertOnDuplicateSetMoreStep<?> moreStep = null;
            for (Map.Entry<String, Object> dup : onDuplicateUpdate.entrySet()) {
                Field<Object> f = table.getField(dup.getKey());
                UpdateSetStep rawStep = moreStep != null
                        ? (UpdateSetStep) moreStep
                        : (UpdateSetStep) dupStep;
                moreStep = (InsertOnDuplicateSetMoreStep<?>) rawStep.set(f, coerce(f, dup.getValue()));
            }
            return moreStep;
        }

        // ON DUPLICATE KEY IGNORE — duplikat varsa səssizce keç
        return step.onDuplicateKeyIgnore();
    }

    private int executeBatchInternal(EntityTable<T> table) {
        // Bütün sətirlərin field adları ilk sətirdən götürülür
        Map<String, Object> firstRow = rows.get(0);

        List<Field<?>> fields = new ArrayList<>();
        for (String fieldName : firstRow.keySet()) {
            fields.add(table.getField(fieldName));
        }

        InsertValuesStepN<?> step = dsl
                .insertInto(table.getTable())
                .columns(fields);

        for (Map.Entry<Integer, Map<String, Object>> indexed :
                indexedRows().entrySet()) {
            List<Object> rowVals = new ArrayList<>();
            for (String fieldName : firstRow.keySet()) {
                Field<Object> f = table.getField(fieldName);
                rowVals.add(coerce(f, indexed.getValue().get(fieldName)));
            }
            step = step.values(rowVals);
        }

        return step.execute();
    }

    private Map<Integer, Map<String, Object>> indexedRows() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) result.put(i, rows.get(i));
        return result;
    }

    /**
     * Field tipinə görə dəyəri uyğunlaşdırır.
     *
     * <p>Əsas ssenarilər:
     * <ul>
     *   <li>Field {@code VARCHAR}, val {@code UUID}  → {@code uuid.toString()}</li>
     *   <li>Field {@code INTEGER}, val {@code "25"}  → {@code 25}</li>
     *   <li>Field {@code BOOLEAN}, val {@code "true"} → {@code true}</li>
     * </ul>
     *
     * <p>Çevrilmə alınmadıqda orijinal dəyər göndərilir (JDBC driver-ə buraxılır).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Field<Object> field, Object val) {
        if (val == null) return null;
        try {
            org.jooq.DataType<Object> dt = (org.jooq.DataType<Object>) field.getDataType();
            // String field-ə UUID göndərilibsə — toString() ilə çevir
            if (String.class.equals(dt.getType()) && val instanceof java.util.UUID) {
                return val.toString();
            }
            return dt.convert(val);
        } catch (Exception e) {
            return val; // fallback — driver özü həll etsin
        }
    }
}
