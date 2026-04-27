# jooqsqlgenerate — İstifadə Qaydası

> Bu sənəd kitabxananı öz layihənə əlavə edib ilk sorğunu necə yazacağını izah edir.

---

## 1. Quraşdırma

### Maven (~/.m2) üzərindən

Kitabxananı lokal Maven repo-ya yayımla:

```bash
./gradlew publishToMavenLocal
```

Sonra öz layihənin `build.gradle.kts`-inə əlavə et:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("az.mbm:jooq-sql-generate:1.0.0")
}
```

---

## 2. Ön Şərtlər

Öz layihəndə bunlar olmalıdır:

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq")
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
}
```

Spring Boot `DSLContext`-i avtomatik yaradır — əlavə konfiqurasiya lazım deyil.

---

## 3. jOOQ Kod Generasiyası (Generated Mode üçün)

Generated mode istifadə edəcəksənsə verilənlər bazası sxemindən avtomatik sinif yaratmaq
lazımdır. Bunun üçün `generateJooq` Gradle task-ı konfiqurasiya edilir.

> **Qeyd:** Entity mode işlədirsənsə bu bölməni keçə bilərsən.

### 3.1. `build.gradle`-ə task əlavə et

```groovy
// build.gradle (Groovy DSL)
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.*

task generateJooq {
    doLast {
        // Verilənlər bazası məlumatları mühit dəyişənlərindən oxunur
        String dbUrl       = System.getenv("DB_URL")       // jdbc:postgresql://host:5432/mydb
        String dbDriver    = System.getenv("DB_DRIVER")    // org.postgresql.Driver
        String dbUsername  = System.getenv("DB_USERNAME")
        String dbPassword  = System.getenv("DB_PASSWORD")

        Configuration configuration = GenerationTool.load(
            new File('src/main/resources/jooq-config.xml')
        )
        configuration.withJdbc(
            new Jdbc()
                .withProperties(new Property().withKey("dialect").withValue("POSTGRES"))
                .withDriver(dbDriver)
                .withUrl(dbUrl)
                .withUser(dbUsername)
                .withPassword(dbPassword)
        )
        GenerationTool.generate(configuration)
    }
}
```

### 3.2. `jooq-config.xml` yarat

`src/main/resources/jooq-config.xml` faylını aşağıdakı kimi konfiqurasiya et:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<configuration>
    <generator>
        <database>
            <!-- PostgreSQL üçün -->
            <name>org.jooq.meta.postgres.PostgresDatabase</name>

            <!-- Bir və ya bir neçə sxema -->
            <schemata>
                <schema>
                    <inputSchema>public</inputSchema>
                </schema>
                <schema>
                    <inputSchema>backlog</inputSchema>   <!-- əlavə sxema varsa -->
                </schema>
            </schemata>
        </database>

        <generate>
            <pojos>true</pojos>     <!-- POJO sinifləri (DTO kimi istifadə oluna bilər) -->
            <daos>true</daos>       <!-- DAO sinifləri -->
            <records>true</records> <!-- Record sinifləri (UsersRecord, OrdersRecord...) -->
        </generate>

        <target>
            <!-- Yaradılan siniflərin yerləşəcəyi paket -->
            <packageName>com.example.myapp.domain.jooq</packageName>
            <!-- Yaradılan siniflərin yazılacağı qovluq -->
            <directory>src/main/java</directory>
        </target>
    </generator>
</configuration>
```

### 3.3. Kodu yenilə

Verilənlər bazasına qoşulub sinifləri yarat:

```bash
export DB_URL="jdbc:postgresql://localhost:5432/mydb"
export DB_DRIVER="org.postgresql.Driver"
export DB_USERNAME="postgres"
export DB_PASSWORD="secret"

./gradlew generateJooq
```

### 3.4. Yaranan struktur

```
src/main/java/com/example/myapp/domain/jooq/
│
├── tables/
│   ├── Users.java          ← Tables.USERS
│   ├── Orders.java         ← Tables.ORDERS
│   └── ...
│
├── tables/records/
│   ├── UsersRecord.java    ← INSERT/UPDATE üçün
│   └── ...
│
├── tables/pojos/
│   ├── Users.java          ← DTO kimi istifadə oluna bilər
│   └── ...
│
└── Tables.java             ← import static Tables.* üçün
```

### 3.5. Nə vaxt yenidən işlətmək lazımdır?

`generateJooq` task-ını aşağıdakı hallarda yenidən işlət: yeni cədvəl əlavə ediləndə,
mövcud cədvələ yeni sütun əlavə ediləndə, sütun adı dəyişdirilndə, sütun tipi dəyişdirilndə.

---

## 4. İki İstifadə Üsulu

Kitabxananın iki fərqli giriş nöqtəsi var. Hansını seçəcəyin layihənin quruluşundan asılıdır.

### Üsul A — `JooqQuery` (Tövsiyə olunan)

Spring bean deyil, hər sorğu üçün yeni nümunə yaradılır. Servisə yalnız `DSLContext` inject edilir.

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final DSLContext dsl;

    public SelectTable searchUsers(String status, String name) {
        return JooqQuery.from(User.class, "u")
            .select("u.id", "u.firstName", "u.email")
            .filter("status", Op.EQUAl, status)
            .filter("firstName", Op.LIKE, name)
            .orderBy("u.createdAt", "DESC")
            .page(0, 20)
            .execute(dsl);
    }
}
```

### Üsul B — `JooqManager` (Köhnə layihələr üçün)

Spring `@Prototype` bean-dir. Singleton servisə inject etmə — hər zaman `ApplicationContext`-dən götür.

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final DSLContext            dsl;
    private final ApplicationContext    ctx;

    public SelectTable searchUsers(String status, String name) {
        JooqManager jooq = ctx.getBean(JooqManager.class); // hər çağrışda yeni nümunə
        jooq.setMainTable(User.class, "u");
        jooq.addColumns("u.id", "u.firstName", "u.email");
        jooq.addFilter("status", Op.EQUAl, status);
        jooq.addFilter("firstName", Op.LIKE, name);
        jooq.addOrderByDesc("u.createdAt");
        jooq.setPage(0, 20);
        return jooq.execute();
    }
}
```

---

## 5. Entity Sinifini Hazırla

Entity mode istifadə edirsənsə JPA annotasiyaları lazımdır:

```java
@Table(name = "users", schema = "public")
public class User {

    @Column(name = "user_id")
    private Long id;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;    // annotasiya yoxsa sütun adı = "email"

    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

> **Qeyd:** `@Entity` annotasiyası məcburi deyil — yalnız `@Table` və `@Column` oxunur.

---

## 6. Nəticəni Almaq

`execute()` `SelectTable` qaytarır. Onu `SelectFetchJooq` ilə istənilən formata çevir:

```java
SelectTable result = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .filter("status", Op.EQUAl, "ACTIVE")
    .page(0, 20)
    .execute(dsl);

// DTO / Entity siyahısı kimi
List<UserDto> users = new SelectFetchJooq<UserDto>()
    .fetchCast(result, UserDto.class)
    .getList();

// Ümumi sətir sayı (pagination üçün)
int totalCount = result.getRowCount();

// Map siyahısı kimi (DTO olmadan)
List<Map<String, Object>> rows = new SelectFetchJooq<>()
    .fetchMaps(result)
    .getList();
```

Və ya daha qısa şəkildə birbaşa `JooqQuery`-dən:

```java
List<UserDto> users = JooqQuery.from(User.class, "u")
    .filter("status", Op.EQUAl, "ACTIVE")
    .fetchInto(dsl, UserDto.class);

List<Map<String, Object>> rows = JooqQuery.from(User.class, "u")
    .filter("status", Op.EQUAl, "ACTIVE")
    .fetchMaps(dsl);
```

---

## 7. Filter Növləri

```java
import az.mbm.jooqsqlgenerate.enums.Op;

JooqQuery.from(User.class, "u")

    // Bərabərlik
    .filter("status",    Op.EQUAl,                 "ACTIVE")
    .filter("status",    Op.NOT_EQUAL,              "BANNED")

    // Müqayisə
    .filter("age",       Op.GREATER_THAN,           18)
    .filter("age",       Op.GREATER_THAN_OR_EQUAL_TO, 18)
    .filter("salary",    Op.LESS_THAN,              5000)
    .filter("salary",    Op.LESS_THAN_OR_EQUAL_TO,  5000)

    // Mətn axtarışı
    .filter("firstName", Op.LIKE,       "Ali")    // %Ali%
    .filter("firstName", Op.START_WITH, "Ali")    // Ali%
    .filter("firstName", Op.END_WITH,   "li")     // %li

    // Siyahı
    .filter("roleId",    Op.IN,      List.of(1L, 2L, 3L))
    .filter("status",    Op.NOT_IN,  List.of("DELETED", "BANNED"))

    // Aralıq
    .filter("createdAt", Op.BETWEEN,
            new Object[]{LocalDate.of(2024,1,1), LocalDate.of(2024,12,31)})

    // Boş yoxlaması
    .filter("deletedAt", Op.IS_EMPTY,     null)   // IS NULL
    .filter("deletedAt", Op.IS_NOT_EMPTY, null)   // IS NOT NULL

    // Regex
    .filter("phone",     Op.REGEXP,     "^\\+994")
    .filter("phone",     Op.NOT_REGEXP, "^\\+994")

    .execute(dsl);
```

> **Null qaydası:** `value` null və ya boş sətirdirsə həmin filter **avtomatik atlanır** —
> əlavə `if` yazmağa ehtiyac yoxdur.

### Alias prefix ilə filter

`filter()` metodu sahə adının əvvəlindəki `alias.` prefiksini **avtomatik həll edir**:

```java
JooqQuery.from(WarehouseFlow.class, "t")
    .leftJoin(Product.class, "t1", "fkProductId", "id")

    // Main cədvəl — alias ilə və ya onsuz, eyni nəticə:
    .filter("status",        Op.EQUAl, "A")
    .filter("t.status",      Op.EQUAl, "A")   // eynidir

    // JOIN cədvəli — "t1" alias-ı Product EntityTable-ına yönləndirilir:
    .filter("t1.fkProductCategoryId", Op.IN,   dto.getFkProductCategoryId())
    .filter("t1.productName",         Op.LIKE,  dto.getProductName())

    .execute(dsl);
```

Bu qaydalar **HAVING** üçün də eynidir — computed sütun filterlərini alias prefix ilə
yaza bilərsən, prefix stripped olunub yalnız alias adı HAVING yoxlamasında istifadə edilir:

```java
JooqQuery.from(Order.class, "o")
    .computedColumn(ComputedField.of("o.price").multiply("o.quantity").as("totalAmount"))

    // Hər ikisi HAVING-ə düşür:
    .filter("totalAmount",   Op.GREATER_THAN, 1000)
    .filter("o.totalAmount", Op.GREATER_THAN, 1000)  // eynidir

    .execute(dsl);
```

---

## 8. JOIN

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "o.amount", "o.createdAt")

    // LEFT JOIN
    .leftJoin(Order.class, "o", "u.id", "o.userId")

    // INNER JOIN
    .innerJoin(Role.class, "r", "u.roleId", "r.id")

    // Hər cədvəlin filterlərini birbaşa alias ilə yaz — avtomatik həll olunur:
    .filter("u.status",  Op.EQUAl,       "ACTIVE")
    .filter("o.amount",  Op.GREATER_THAN, 100)
    .filter("r.roleKey", Op.IN,           List.of("ADMIN", "MANAGER"))
    .execute(dsl);
```

---

## 9. GROUP BY və Aqreqat Funksiyalar

```java
JooqQuery.from(User.class, "u")
    .leftJoin(Order.class, "o", "u.id", "o.userId")
    .groupBy("u.department", "u.status")
    .agg(GroupFunction.SUM,   "o.amount", "totalAmount", 2,   null, null, "DESC")
    .agg(GroupFunction.COUNT, "o.id",     "orderCount",  0,   null, null, null)
    .agg(GroupFunction.AVG,   "o.amount", "avgAmount",   2,   null, null, null)
    // HAVING COUNT(o.id) > 5:
    .agg(GroupFunction.COUNT, "o.id", "orderCount", 0,
         Op.GREATER_THAN, 5, null)
    .execute(dsl);
```

---

## 10. Dinamik Sıralama

```java
// Tək sahə
JooqQuery.from(User.class, "u")
    .orderBy("u.createdAt", "DESC")
    .execute(dsl);

// Çox sahə — Map ilə
Map<String, String> sorts = new LinkedHashMap<>();
sorts.put("u.lastName",  "ASC");
sorts.put("u.firstName", "ASC");
sorts.put("u.createdAt", "DESC");

JooqQuery.from(User.class, "u")
    .orderBy(sorts)
    .execute(dsl);
```

---

## 11. Pagination

```java
// Səhifə 0, hər səhifədə 20 sətir
JooqQuery.from(User.class, "u")
    .page(0, 20)
    .execute(dsl);

// Bütün nəticəni qaytar (pagination olmadan)
JooqQuery.from(User.class, "u")
    .noPagination()
    .execute(dsl);

// Ümumi sətir sayını al
SelectTable result = JooqQuery.from(User.class, "u").page(0, 20).execute(dsl);
int total = result.getRowCount();   // COUNT(*) nəticəsi
```

---

## 12. GlobalFilter — Xarici Filterlər

REST body-dən və ya konfiqurasiyadan `Map<String, Map<String, String>>` formatında gələn
filterlər üçün:

```java
// Fluent builder ilə qur
GlobalFilter filter = GlobalFilter.of()
    .equal("status", "ACTIVE")
    .like("firstName", searchName)
    .greaterThan("salary", "3000")
    .between("createdAt", "2024-01-01", "2024-12-31")
    .in("roleId", "1", "2", "3")
    .isNull("deletedAt");

JooqQuery.from(User.class, "u")
    .globalFilter(filter)
    .execute(dsl);

// Xam Map ilə (başqa sistemdən gəlibsə)
Map<String, Map<String, String>> rawFilter = externalService.getFilters();

JooqQuery.from(User.class, "u")
    .globalFilter(rawFilter)
    .execute(dsl);
```

Bazis filteri bütün sorğulara tətbiq etmək üçün `merge()` istifadə et:

```java
GlobalFilter base = GlobalFilter.of().equal("tenantId", currentTenantId);
GlobalFilter user = GlobalFilter.of().like("name", searchName);

JooqQuery.from(User.class, "u")
    .globalFilter(base.merge(user))
    .execute(dsl);
```

---

## 13. ROUND ilə Filter

İki fərqli yol var — hansını seçəcəyin nəticəni SELECT-də göstərməyə ehtiyacın olub-olmadığından asılıdır.

### 13.1. `selectRound()` — SELECT + WHERE birlikdə

Yuvarlama nəticəsini həm SELECT-də göstərmək, həm də filterlədikdə istifadə et:

```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.status")
    .selectRound("o.unitCost", 2, "cost")     // SELECT ROUND(o.unit_cost, 2) AS cost
    .selectRound("o.quantity", 0, "qty")      // SELECT ROUND(o.quantity, 0)  AS qty
    .filter("cost", Op.GREATER_THAN, 9.99)    // WHERE ROUND(o.unit_cost, 2) > 9.99
    .filter("qty",  Op.EQUAl, 10)            // WHERE ROUND(o.quantity, 0) = 10
    .page(0, 20)
    .execute(dsl);
```

Yaranan SQL:
```sql
SELECT o.id, o.status,
       ROUND(o.unit_cost, 2) AS cost,
       ROUND(o.quantity, 0)  AS qty
FROM orders o
WHERE ROUND(o.unit_cost, 2) > 9.99
  AND ROUND(o.quantity, 0) = 10
LIMIT 20 OFFSET 0
```

`selectRound()` ilə birlikdə **adi** `Op` dəyərləri işlənir:

```java
.filter("cost", Op.EQUAl,                 9.99)
.filter("cost", Op.NOT_EQUAL,             9.99)
.filter("cost", Op.GREATER_THAN,          9.99)
.filter("cost", Op.GREATER_THAN_OR_EQUAL_TO, 9.99)
.filter("cost", Op.LESS_THAN,             9.99)
.filter("cost", Op.LESS_THAN_OR_EQUAL_TO, 9.99)
.filter("cost", Op.BETWEEN, new Object[]{5.00, 15.00})
```

> **Xəbərdarlıq — ikiqat ROUND:** `selectRound()` ilə seçilmiş alias-a `Op.EQUAL_ROUND_2`
> kimi ROUND Op tətbiq etmə — bu `ROUND(ROUND(field,2), 2)` yaradır. `selectRound` ilə
> adi `Op.EQUAl`, `Op.GREATER_THAN` kimi op-lar işlət.

### 13.2. `Op.ROUND_N` — Yalnız WHERE-də ROUND

SELECT-ə ROUND sütunu əlavə etmədən birbaşa WHERE-də ROUND tətbiq etmək üçün:

```java
JooqQuery.from(Product.class, "p")
    .select("p.id", "p.name", "p.price")   // orijinal sütun göstərilir
    .filter("price", Op.GREATER_THAN_ROUND_2, "9.50")  // WHERE ROUND(p.price, 2) > 9.50
    .filter("score", Op.EQUAL_ROUND_0,        5)        // WHERE ROUND(p.score, 0) = 5
    .execute(dsl);
```

Yaranan SQL:
```sql
SELECT p.id, p.name, p.price
FROM products p
WHERE ROUND(p.price, 2) > 9.50
  AND ROUND(p.score, 0) = 5
```

### 13.3. Global Filter ilə ROUND Op

`Map<String, Map<String, String>>` formatında gələn global filterdə ROUND Op sabitlərini
`FilterOperationConstants`-dan al:

```java
import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;

Map<String, Map<String, String>> globalFilter = new LinkedHashMap<>();
globalFilter.put(GREATER_THAN_ROUND_2, Map.of("price",    "9.50"));
globalFilter.put(EQUAL_ROUND_0,        Map.of("score",    "5"));
globalFilter.put(LESS_THAN_ROUND_1,    Map.of("discount", "0.5"));

JooqQuery.from(Product.class, "p")
    .globalFilter(globalFilter)
    .execute(dsl);
```

---

## 14. ROUND Filter Növlərinin Tam Siyahısı

Cəmi **30 ROUND Op** mövcuddur (6 əməliyyat × 5 miqyas):

| `Op` | `FilterOperationConstants` | SQL | Miqyas |
|---|---|---|---|
| `EQUAL_ROUND_0` | `EQUAL_ROUND_0` | `ROUND(field,0) = val` | tam ədəd |
| `NOT_EQUAL_ROUND_0` | `NOT_EQUAL_ROUND_0` | `ROUND(field,0) != val` | tam ədəd |
| `GREATER_THAN_ROUND_0` | `GREATER_THAN_ROUND_0` | `ROUND(field,0) > val` | tam ədəd |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_0` | `GREATER_THAN_OR_EQUAL_TO_ROUND_0` | `ROUND(field,0) >= val` | tam ədəd |
| `LESS_THAN_ROUND_0` | `LESS_THAN_ROUND_0` | `ROUND(field,0) < val` | tam ədəd |
| `LESS_THAN_OR_EQUAL_TO_ROUND_0` | `LESS_THAN_OR_EQUAL_TO_ROUND_0` | `ROUND(field,0) <= val` | tam ədəd |
| `EQUAL_ROUND_1` | `EQUAL_ROUND_1` | `ROUND(field,1) = val` | 1 onluq |
| `NOT_EQUAL_ROUND_1` | `NOT_EQUAL_ROUND_1` | `ROUND(field,1) != val` | 1 onluq |
| `GREATER_THAN_ROUND_1` | `GREATER_THAN_ROUND_1` | `ROUND(field,1) > val` | 1 onluq |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_1` | `GREATER_THAN_OR_EQUAL_TO_ROUND_1` | `ROUND(field,1) >= val` | 1 onluq |
| `LESS_THAN_ROUND_1` | `LESS_THAN_ROUND_1` | `ROUND(field,1) < val` | 1 onluq |
| `LESS_THAN_OR_EQUAL_TO_ROUND_1` | `LESS_THAN_OR_EQUAL_TO_ROUND_1` | `ROUND(field,1) <= val` | 1 onluq |
| `EQUAL_ROUND_2` | `EQUAL_ROUND_2` | `ROUND(field,2) = val` | 2 onluq (qiymət) |
| `NOT_EQUAL_ROUND_2` | `NOT_EQUAL_ROUND_2` | `ROUND(field,2) != val` | 2 onluq (qiymət) |
| `GREATER_THAN_ROUND_2` | `GREATER_THAN_ROUND_2` | `ROUND(field,2) > val` | 2 onluq (qiymət) |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_2` | `GREATER_THAN_OR_EQUAL_TO_ROUND_2` | `ROUND(field,2) >= val` | 2 onluq (qiymət) |
| `LESS_THAN_ROUND_2` | `LESS_THAN_ROUND_2` | `ROUND(field,2) < val` | 2 onluq (qiymət) |
| `LESS_THAN_OR_EQUAL_TO_ROUND_2` | `LESS_THAN_OR_EQUAL_TO_ROUND_2` | `ROUND(field,2) <= val` | 2 onluq (qiymət) |
| `EQUAL_ROUND_3` | `EQUAL_ROUND_3` | `ROUND(field,3) = val` | 3 onluq |
| `NOT_EQUAL_ROUND_3` | `NOT_EQUAL_ROUND_3` | `ROUND(field,3) != val` | 3 onluq |
| `GREATER_THAN_ROUND_3` | `GREATER_THAN_ROUND_3` | `ROUND(field,3) > val` | 3 onluq |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_3` | `GREATER_THAN_OR_EQUAL_TO_ROUND_3` | `ROUND(field,3) >= val` | 3 onluq |
| `LESS_THAN_ROUND_3` | `LESS_THAN_ROUND_3` | `ROUND(field,3) < val` | 3 onluq |
| `LESS_THAN_OR_EQUAL_TO_ROUND_3` | `LESS_THAN_OR_EQUAL_TO_ROUND_3` | `ROUND(field,3) <= val` | 3 onluq |
| `EQUAL_ROUND_4` | `EQUAL_ROUND_4` | `ROUND(field,4) = val` | 4 onluq |
| `NOT_EQUAL_ROUND_4` | `NOT_EQUAL_ROUND_4` | `ROUND(field,4) != val` | 4 onluq |
| `GREATER_THAN_ROUND_4` | `GREATER_THAN_ROUND_4` | `ROUND(field,4) > val` | 4 onluq |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_4` | `GREATER_THAN_OR_EQUAL_TO_ROUND_4` | `ROUND(field,4) >= val` | 4 onluq |
| `LESS_THAN_ROUND_4` | `LESS_THAN_ROUND_4` | `ROUND(field,4) < val` | 4 onluq |
| `LESS_THAN_OR_EQUAL_TO_ROUND_4` | `LESS_THAN_OR_EQUAL_TO_ROUND_4` | `ROUND(field,4) <= val` | 4 onluq |

---

## 15. EXISTS Subquery

```java
// WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .exists(
        ExistsSpec.exists(Order.class)
            .joinField("userId", "u", "id")
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);

// NOT EXISTS
JooqQuery.from(User.class, "u")
    .exists(
        ExistsSpec.notExists(Order.class)
            .joinField("userId", "u", "id")
    )
    .execute(dsl);
```

---

## 16. IN Subquery

```java
// WHERE u.id IN (SELECT o.userId FROM orders o WHERE o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .inSubQuery("u.id",
        SubQueryIn.from(Order.class, "o")
            .select("o.userId")
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);

// WHERE (u.firstName, u.lastName) IN (SELECT bl.firstName, bl.lastName FROM blacklist)
JooqQuery.from(User.class, "u")
    .inSubQuery(
        new String[]{"u.firstName", "u.lastName"},
        SubQueryIn.from(Blacklist.class, "bl")
            .select("bl.firstName", "bl.lastName")
    )
    .execute(dsl);
```

---

## 17. CASE WHEN

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .caseWhen(
        CaseBuilder.when("u.status", Op.EQUAl, "ACTIVE")
            .then("Aktiv")
            .when("u.status", Op.EQUAl, "BANNED")
            .then("Qadağalı")
            .otherwise("Bilinməyən")
            .as("statusLabel")
    )
    .execute(dsl);
```

---

## 18. Hesablama Sütunları

```java
// (price * quantity) - discount AS netAmount
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.of("o.price")
            .multiply("o.quantity")
            .subtract("o.discount")
            .as("netAmount")
    )
    .execute(dsl);
```

### 18.1. Bir Neçə Hissəni Toplama — `sumOf()`

```java
// (price * qty) + tax + (shipping - discount) AS grandTotal
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.sumOf(
            ComputedField.expr("o.price").multiply("o.qty"),
            ComputedField.expr("o.tax"),
            ComputedField.expr("o.shipping").subtract("o.discount")
        ).as("grandTotal")
    )
    .execute(dsl);
```

### 18.2. Computed Sütunun Nəticəsinə Filter

Hesablanmış sütunun nəticəsini filterlə — `computedColumn()` üçüncü arqument kimi:

```java
// grandTotal > 1000  →  HAVING (ifadə) > 1000
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.sumOf(
            ComputedField.expr("o.price").multiply("o.qty"),
            ComputedField.expr("o.tax"),
            ComputedField.expr("o.shipping").subtract("o.discount")
        ).as("grandTotal"),
        Op.GREATER_THAN, 1000
    )
    .execute(dsl);
```

### 18.3. `filter()` ilə Computed Alias Filter (WHERE)

`filter()` metodu computed sütunun alias-ını aldıqda `WHERE`-ə ifadənin özünü yazır:

```java
// WHERE (o.price * o.qty) + o.tax > 1000
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.sumOf(
            ComputedField.expr("o.price").multiply("o.qty"),
            ComputedField.expr("o.tax")
        ).as("grandTotal")
    )
    .filter("grandTotal", Op.GREATER_THAN, 1000)
    .execute(dsl);
```

> **Fərq:** `computedColumn(..., op, value)` → `HAVING`; `.filter("alias", op, value)` → `WHERE`.

---

## 19. Derived Table — Sorğu Üzərindən Sorğu

```java
// Addım 1 — daxili sorğu (mütləq noPagination() olmalıdır)
SelectTable activeUsers = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.department")
    .filter("status", Op.EQUAl, "ACTIVE")
    .noPagination()
    .execute(dsl);

// Addım 2 — daxili sorğu üzərindən yeni sorğu
SelectTable result = JooqQuery.from(activeUsers, "sub")
    .select("id", "firstName", "department")
    .filter("firstName", Op.LIKE, searchName)
    .filter("department", Op.EQUAl, dept)
    .orderBy("firstName", "ASC")
    .page(pageNumber, pageSize)
    .execute(dsl);
```

---

## 20. Generated Mode ilə (Type-safe)

```java
import static com.example.jooq.Tables.*;

// Field-ləri birbaşa ver
JooqQuery.from(USERS, "u")
    .select(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
    .filter(USERS.STATUS.eq("ACTIVE"))
    .filter(USERS.AGE.greaterThan(18))
    .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
    .select(ORDERS.AMOUNT)
    .orderBy(USERS.CREATED_AT.desc())
    .page(0, 20)
    .execute(dsl);

// Və ya string adlar da işləyir (dinamik sorğular üçün)
JooqQuery.from(USERS, "u")
    .select("id", "firstName", "status")     // → USERS.ID, USERS.FIRST_NAME, USERS.STATUS
    .filter("firstName", Op.LIKE, searchName)
    .groupBy("department")
    .orderBy("createdAt", "DESC")
    .page(0, 20)
    .execute(dsl);
```

---

## 21. Tam Nümunə — REST Endpoint

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final DSLContext dsl;

    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        SelectTable result = JooqQuery.from(User.class, "u")
            .select("u.id", "u.firstName", "u.lastName", "u.email", "u.department")
            .filter("u.status",     Op.EQUAl, status)     // null → atlanır
            .filter("u.firstName",  Op.LIKE,  name)        // null → atlanır
            .filter("u.department", Op.EQUAl, department)  // null → atlanır
            .orderBy("u.createdAt", "DESC")
            .page(page, size)
            .execute(dsl);

        List<UserDto> users = new SelectFetchJooq<UserDto>()
            .fetchCast(result, UserDto.class)
            .getList();

        return ResponseEntity.ok(Map.of(
            "data",  users,
            "total", result.getRowCount(),
            "page",  page,
            "size",  size
        ));
    }
}
```

---

## Filter Növlərinin Sürətli İstinadı

### Əsas filterlər

| `Op` | SQL qarşılığı | Nümunə dəyər |
|---|---|---|
| `EQUAl` | `= value` | `"ACTIVE"` |
| `NOT_EQUAL` | `!= value` | `"BANNED"` |
| `GREATER_THAN` | `> value` | `18` |
| `GREATER_THAN_OR_EQUAL_TO` | `>= value` | `18` |
| `LESS_THAN` | `< value` | `5000` |
| `LESS_THAN_OR_EQUAL_TO` | `<= value` | `5000` |
| `LIKE` | `LIKE '%value%'` | `"Ali"` |
| `START_WITH` | `LIKE 'value%'` | `"Ali"` |
| `END_WITH` | `LIKE '%value'` | `"li"` |
| `IN` | `IN (...)` | `List.of(1L, 2L)` |
| `NOT_IN` | `NOT IN (...)` | `List.of("X","Y")` |
| `BETWEEN` | `BETWEEN a AND b` | `new Object[]{1, 100}` |
| `IS_EMPTY` | `IS NULL` | `null` |
| `IS_NOT_EMPTY` | `IS NOT NULL` | `null` |
| `REGEXP` | `REGEXP pattern` | `"^\\+994"` |
| `NOT_REGEXP` | `NOT REGEXP pattern` | `"^\\+994"` |

### ROUND filterlər (qısa)

| Nümunə `Op` | SQL | Miqyas |
|---|---|---|
| `EQUAL_ROUND_0` | `ROUND(field,0) = val` | tam ədəd |
| `NOT_EQUAL_ROUND_0` | `ROUND(field,0) != val` | tam ədəd |
| `GREATER_THAN_ROUND_2` | `ROUND(field,2) > val` | 2 onluq |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_2` | `ROUND(field,2) >= val` | 2 onluq |
| `LESS_THAN_ROUND_2` | `ROUND(field,2) < val` | 2 onluq |
| `LESS_THAN_OR_EQUAL_TO_ROUND_2` | `ROUND(field,2) <= val` | 2 onluq |
| `EQUAL_ROUND_4` | `ROUND(field,4) = val` | 4 onluq |

Tam siyahı üçün bölmə 14-ə bax. Mövcud miqyaslar: **0, 1, 2, 3, 4**.

### `selectRound()` vs `Op.ROUND_N` — hansını seçim?

| Sual | Cavab |
|---|---|
| Yuvarlama nəticəsini **göstərməli** + filterlə? | `selectRound(field, scale, alias)` + adi `Op` |
| Yalnız WHERE-də ROUND lazımdır, SELECT-ə gərək yox? | `Op.EQUAL_ROUND_2` (və s.) birbaşa `filter()`-ə ver |
| Global Map filterdə ROUND lazımdır? | `FilterOperationConstants.GREATER_THAN_ROUND_2` açarı ilə |
