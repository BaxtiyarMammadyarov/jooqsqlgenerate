package az.mbm.jooqsqlgenerate.core;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.jooq.Record;
import org.jooq.Schema;
import org.jooq.impl.DSL;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JPA entity class-ını jOOQ Table + Field xəritəsinə çevirir.
 *
 * <h3>Cache strategiyası</h3>
 * <p>Reflection bahalıdır — lakin entity-nin annotasiya məlumatları
 * (cədvəl adı, schema, field→sütun xəritəsi) runtime-da <b>heç vaxt dəyişmir</b>.
 * Bu məlumatlar {@link #META_CACHE}-də bir dəfə oxunur, sonrakı bütün
 * sorğularda cache-dən istifadə olunur.
 *
 * <p>Alias hər sorğuda fərqli ola bilər — ona görə {@code fieldsMap}
 * (alias baked-in jOOQ Field-ləri) cache-lənmir, amma yaradılması artıq
 * yalnız sürətli {@code DSL.field(DSL.name(alias, col), type)} çağrışlarıdır.
 *
 * <h3>Thread-safety</h3>
 * <p>{@code ConcurrentHashMap} istifadəsi sayəsində parallel sorğularda
 * race condition yoxdur.
 */
public class EntityTable<T> {

    // ─── Statik annotasiya cache-i ───────────────────────────────────────

    /**
     * Class → EntityMeta cache-i.
     *
     * <p><b>Niyə WeakHashMap?</b><br>
     * Spring DevTools hot reload zamanı köhnə entity sinifləri yeni ClassLoader
     * tərəfindən əvəz edilir. {@code WeakHashMap} zəif açar referansı saxladığından,
     * köhnə {@code Class<?>} obyektinə başqa güclü referans qalmadıqda GC onu
     * cache-dən <b>avtomatik silir</b> — ClassLoader leak yaranmır.
     *
     * <p><b>Thread safety:</b> {@code WeakHashMap} özü thread-safe deyil —
     * {@link ReentrantReadWriteLock} ilə qorunur. Oxuma əməliyyatları paralel,
     * yazma müstəsna kilidlə işləyir.
     */
    private static final WeakHashMap<Class<?>, EntityMeta> META_CACHE = new WeakHashMap<>();
    private static final ReentrantReadWriteLock CACHE_LOCK = new ReentrantReadWriteLock();

    /**
     * Bir entity class-ı üçün dəyişməz annotasiya məlumatları.
     *
     * @param schemaName    @Table(schema=...)
     * @param tableName     @Table(name=...)
     * @param fieldToColumn camelCase Java adı → SQL sütun adı
     * @param fieldTypes    camelCase Java adı → Java tipi (jOOQ DataType üçün)
     */
    private record EntityMeta(
            String schemaName,
            String tableName,
            Map<String, String>   fieldToColumn,   // "userId"   → "user_id"
            Map<String, Class<?>> fieldTypes        // "userId"   → Long.class
    ) {}

    // ─── Nümunə vəziyyəti (alias-a bağlıdır, cache-lənmir) ─────────────

    private final Schema                              schema;
    private final org.jooq.Table<Record>              table;
    private final Class<T>                            entityClass;
    private final Map<String, Field>                  entityFieldMap;   // reflection Field ref
    private final Map<String, org.jooq.Field<Object>> fieldsMap;        // alias ilə jOOQ Field
    private final boolean                              rawMode;          // true = derived table (JPA reflection yoxdur)

    // ─── Konstruktorlar ──────────────────────────────────────────────────

    /** Avtomatik unikal alias ilə yaradılır */
    public EntityTable(Class<T> entityClass) {
        this(entityClass,
             resolveTableName(entityClass) + "_" +
             UUID.randomUUID().toString().replace("-", "").substring(0, 5));
    }

    /** Özəl alias ilə yaradılır */
    public EntityTable(Class<T> entityClass, String tableAlias) {
        this.entityClass = entityClass;
        this.rawMode     = false;

        // 1. Annotasiya məlumatları — cache-dən və ya reflection ilə
        EntityMeta meta = getOrBuildMeta(entityClass);

        // 2. Schema + Table (alias hər nümunədə fərqlidir → cache-lənmir)
        this.schema = DSL.schema(meta.schemaName());
        this.table  = DSL.table(DSL.name(meta.schemaName(), meta.tableName())).as(tableAlias);

        // 3. entityFieldMap — reflection Field referansları (meta-dan köçürülür)
        this.entityFieldMap = new HashMap<>();
        buildEntityFieldMap(entityClass);

        // 4. fieldsMap — alias baked-in jOOQ Field-lər (alias bilinir, tez yaranır)
        this.fieldsMap = new HashMap<>(meta.fieldToColumn().size() * 2);
        for (Map.Entry<String, String> e : meta.fieldToColumn().entrySet()) {
            String javaName = e.getKey();
            String colName  = e.getValue();
            Class<?> type   = meta.fieldTypes().get(javaName);
            fieldsMap.put(colName,
                    (org.jooq.Field<Object>) DSL.field(DSL.name(tableAlias, colName), type));
        }
    }

    /**
     * Derived table (məs. {@link SelectTable#asTable(String)}) üçün — JPA reflection
     * olmadan, artıq alias bağlanmış raw jOOQ {@link org.jooq.Table} ilə yaradılır.
     *
     * <p>Bu konstruktor SelectTable-əsaslı JOIN-lərin (alias-ları, məs. "d2", "d3")
     * entity mode-da {@code tableMap}-ə qeydiyyatdan keçməsi üçün istifadə olunur.
     * {@link #getField(String)} bu halda jOOQ-un öz {@code table.field(name)}
     * axtarışından istifadə edir (heç bir camelCase→snake_case çevrilməsi olmadan,
     * çünki derived table-ın sütunları artıq layihələndirilmiş alias adları ilədir).
     *
     * @param rawTable  artıq {@code .as(alias)} ilə aliaslanmış derived table
     * @param tableAlias bu cədvəlin alias adı (referans üçün saxlanılır)
     */
    @SuppressWarnings("unchecked")
    public EntityTable(org.jooq.Table<?> rawTable, String tableAlias) {
        this.rawMode        = true;
        this.entityClass    = null;
        this.schema          = null;
        this.table           = (org.jooq.Table<Record>) rawTable;
        this.entityFieldMap = null;
        this.fieldsMap       = null;
    }

    // ─── Cache oxuma / yazma ─────────────────────────────────────────────

    private static EntityMeta getOrBuildMeta(Class<?> clazz) {
        // Əvvəlcə read lock ilə bax — çox thread paralel oxuya bilər
        CACHE_LOCK.readLock().lock();
        try {
            EntityMeta cached = META_CACHE.get(clazz);
            if (cached != null) return cached;
        } finally {
            CACHE_LOCK.readLock().unlock();
        }

        // Cache miss — write lock ilə yaz
        CACHE_LOCK.writeLock().lock();
        try {
            // Double-check: başqa thread bizdən əvvəl yazmış ola bilər
            EntityMeta cached = META_CACHE.get(clazz);
            if (cached != null) return cached;

            EntityMeta meta = buildMeta(clazz);
            META_CACHE.put(clazz, meta);
            return meta;
        } finally {
            CACHE_LOCK.writeLock().unlock();
        }
    }

    // ─── Cache inşaatı (bir dəfə, lazım olduqda) ────────────────────────

    private static EntityMeta buildMeta(Class<?> clazz) {
        String schemaName = resolveSchema(clazz);
        String tableName  = resolveTableName(clazz);

        Map<String, String>   fieldToColumn = new LinkedHashMap<>();
        Map<String, Class<?>> fieldTypes    = new LinkedHashMap<>();

        // Ana sinif + üst sinif sahələri
        collectFields(clazz, fieldToColumn, fieldTypes);
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            collectFields(clazz.getSuperclass(), fieldToColumn, fieldTypes);
        }

        return new EntityMeta(schemaName, tableName,
                              Collections.unmodifiableMap(fieldToColumn),
                              Collections.unmodifiableMap(fieldTypes));
    }

    private static void collectFields(Class<?> clazz,
                                      Map<String, String>   fieldToColumn,
                                      Map<String, Class<?>> fieldTypes) {
        for (Field f : clazz.getDeclaredFields()) {
            String colName = resolveColumnName(f);
            fieldToColumn.put(f.getName(), colName);
            fieldTypes.put(f.getName(), f.getType());
        }
    }

    /** entityFieldMap — reflection Field ref-ləri (getField() üçün lazım) */
    private void buildEntityFieldMap(Class<T> clazz) {
        for (Field f : clazz.getDeclaredFields())         entityFieldMap.put(f.getName(), f);
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class)
            for (Field f : clazz.getSuperclass().getDeclaredFields()) entityFieldMap.put(f.getName(), f);
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Java sahəsinin adı ilə jOOQ Field-i qaytarır.
     *
     * @param fieldName Java field adı (camelCase), məs: "userId"
     * @throws IllegalArgumentException sahə tapılmadıqda
     */
    @SuppressWarnings("unchecked")
    public org.jooq.Field<Object> getField(String fieldName) {
        if (rawMode) {
            org.jooq.Field<?> f = table.field(fieldName);
            if (f == null) {
                throw new IllegalArgumentException(
                        "Field '" + fieldName + "' not found in derived table '" + table.getName() + "'");
            }
            return (org.jooq.Field<Object>) f;
        }
        Field rf = entityFieldMap.get(fieldName);
        if (rf == null) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' not found in " + entityClass.getSimpleName());
        }
        return fieldsMap.get(resolveColumnName(rf));
    }

    /** Tip parametri ilə jOOQ Field-i qaytarır. */
    public <E> org.jooq.Field<E> getField(String fieldName, Class<E> type) {
        return getField(fieldName).cast(type);
    }

    public org.jooq.SelectField<?> getSelectField(String fieldName) {
        return getField(fieldName);
    }

    public Schema getSchema()                                    { return schema; }
    public org.jooq.Table<Record> getTable()                    { return table; }
    public Class<T> getEntityClass()                            { return entityClass; }
    public Map<String, Field> getEntityFieldMap()               { return entityFieldMap; }
    public Map<String, org.jooq.Field<Object>> getFieldsMap()  { return fieldsMap; }

    // ─── Statik yardımcılar ──────────────────────────────────────────────

    private static String resolveColumnName(Field field) {
        return Optional.ofNullable(field.getAnnotation(Column.class))
                .map(Column::name)
                .filter(name -> !name.isBlank())
                .orElse(field.getName().toLowerCase());
    }

    private static String resolveTableName(Class<?> clazz) {
        return Optional.ofNullable(clazz.getAnnotation(Table.class))
                .map(Table::name)
                .filter(name -> !name.isBlank())
                .orElse(clazz.getSimpleName().toLowerCase());
    }

    private static String resolveSchema(Class<?> clazz) {
        return Optional.ofNullable(clazz.getAnnotation(Table.class))
                .map(Table::schema)
                .orElse("");
    }
}
