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

## 3. İki İstifadə Üsulu

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
            .filter("status", FilterOperations.EQUAl, status)
            .filter("firstName", FilterOperations.LIKE, name)
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
        jooq.addFilter("status", FilterOperations.EQUAl, status);
        jooq.addFilter("firstName", FilterOperations.LIKE, name);
        jooq.addOrderByDesc("u.createdAt");
        jooq.setPage(0, 20);
        return jooq.execute();
    }
}
```

---

## 4. Entity Sinifini Hazırla

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

## 5. Nəticəni Almaq

`execute()` `SelectTable` qaytarır. Onu `SelectFetchJooq` ilə istənilən formata çevir:

```java
SelectTable result = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .filter("status", FilterOperations.EQUAl, "ACTIVE")
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
    .filter("status", FilterOperations.EQUAl, "ACTIVE")
    .fetchInto(dsl, UserDto.class);

List<Map<String, Object>> rows = JooqQuery.from(User.class, "u")
    .filter("status", FilterOperations.EQUAl, "ACTIVE")
    .fetchMaps(dsl);
```

---

## 6. Filter Növləri

```java
JooqQuery.from(User.class, "u")

    // Bərabərlik
    .filter("status",    FilterOperations.EQUAl,                 "ACTIVE")
    .filter("status",    FilterOperations.NOT_EQUAL,              "BANNED")

    // Müqayisə
    .filter("age",       FilterOperations.GREATER_THAN,           18)
    .filter("age",       FilterOperations.GREATER_THAN_OR_EQUAL_TO, 18)
    .filter("salary",    FilterOperations.LESS_THAN,              5000)
    .filter("salary",    FilterOperations.LESS_THAN_OR_EQUAL_TO,  5000)

    // Mətn axtarışı
    .filter("firstName", FilterOperations.LIKE,                   "Ali")    // %Ali%
    .filter("firstName", FilterOperations.START_WITH,             "Ali")    // Ali%
    .filter("firstName", FilterOperations.END_WITH,               "li")     // %li

    // Siyahı
    .filter("roleId",    FilterOperations.IN,      List.of(1L, 2L, 3L))
    .filter("status",    FilterOperations.NOT_IN,  List.of("DELETED", "BANNED"))

    // Aralıq
    .filter("createdAt", FilterOperations.BETWEEN,
            new Object[]{LocalDate.of(2024,1,1), LocalDate.of(2024,12,31)})

    // Boş yoxlaması
    .filter("deletedAt", FilterOperations.IS_EMPTY,     null)   // IS NULL
    .filter("deletedAt", FilterOperations.IS_NOT_EMPTY, null)   // IS NOT NULL

    // Regex
    .filter("phone",     FilterOperations.REGEXP,     "^\\+994")
    .filter("phone",     FilterOperations.NOT_REGEXP, "^\\+994")

    .execute(dsl);
```

> **Null qaydası:** `value` null və ya boş sətirdirsə həmin filter **avtomatik atlanır** —
> əlavə `if` yazmağa ehtiyac yoxdur.

---

## 7. JOIN

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "o.amount", "o.createdAt")

    // LEFT JOIN
    .leftJoin(Order.class, "o", "u.id", "o.userId")

    // INNER JOIN
    .innerJoin(Role.class, "r", "u.roleId", "r.id")

    .filter("u.status", FilterOperations.EQUAl, "ACTIVE")
    .filter("o.amount", FilterOperations.GREATER_THAN, 100)
    .execute(dsl);
```

---

## 8. GROUP BY və Aqreqat Funksiyalar

```java
JooqQuery.from(User.class, "u")
    .leftJoin(Order.class, "o", "u.id", "o.userId")
    .groupBy("u.department", "u.status")
    .agg(GroupFunction.SUM,   "o.amount", "totalAmount", 2,   null, null, "DESC")
    .agg(GroupFunction.COUNT, "o.id",     "orderCount",  0,   null, null, null)
    .agg(GroupFunction.AVG,   "o.amount", "avgAmount",   2,   null, null, null)
    // HAVING COUNT(o.id) > 5:
    .agg(GroupFunction.COUNT, "o.id", "orderCount", 0,
         FilterOperations.GREATER_THAN, 5, null)
    .execute(dsl);
```

---

## 9. Dinamik Sıralama

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

## 10. Pagination

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

## 11. GlobalFilter — Xarici Filterlər

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

## 12. EXISTS Subquery

```java
// WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .exists(
        ExistsSpec.exists(Order.class)
            .joinField("userId", "u", "id")
            .filter("status", FilterOperations.EQUAl, "PAID")
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

## 13. IN Subquery

```java
// WHERE u.id IN (SELECT o.userId FROM orders o WHERE o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .inSubQuery("u.id",
        SubQueryIn.from(Order.class, "o")
            .select("o.userId")
            .filter("status", FilterOperations.EQUAl, "PAID")
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

## 14. CASE WHEN

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .caseWhen(
        CaseBuilder.when("u.status", FilterOperations.EQUAl, "ACTIVE")
            .then("Aktiv")
            .when("u.status", FilterOperations.EQUAl, "BANNED")
            .then("Qadağalı")
            .otherwise("Bilinməyən")
            .as("statusLabel")
    )
    .execute(dsl);
```

---

## 15. Hesablama Sütunları

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

---

## 16. Derived Table — Sorğu Üzərindən Sorğu

Bir sorğunun nəticəsini başqa sorğunun cədvəli kimi istifadə et:

```java
// Addım 1 — daxili sorğu (mütləq noPagination() olmalıdır)
SelectTable activeUsers = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.department")
    .filter("status", FilterOperations.EQUAl, "ACTIVE")
    .noPagination()
    .execute(dsl);

// Addım 2 — daxili sorğu üzərindən yeni sorğu
SelectTable result = JooqQuery.from(activeUsers, "sub")
    .select("id", "firstName", "department")
    .filter("firstName", FilterOperations.LIKE, searchName)
    .filter("department", FilterOperations.EQUAl, dept)
    .orderBy("firstName", "ASC")
    .page(pageNumber, pageSize)
    .execute(dsl);
```

---

## 17. jOOQ Code Generation ilə (Generated Mode)

Layihəndə `generateJooq` task-ı konfiqurasiya edilibsə verilənlər bazası sxemindən
`Tables.USERS`, `USERS.ID`, `USERS.FIRST_NAME` kimi siniflər yaranır. Bu rejimdə
tip-təhlükəli sorğular yazmaq mümkündür:

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
    .filter("firstName", FilterOperations.LIKE, searchName)
    .groupBy("department")
    .orderBy("createdAt", "DESC")
    .page(0, 20)
    .execute(dsl);
```

---

## 18. Tam Nümunə — REST Endpoint

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
            .filter("u.status",     FilterOperations.EQUAl, status)      // null → atlanır
            .filter("u.firstName",  FilterOperations.LIKE,  name)         // null → atlanır
            .filter("u.department", FilterOperations.EQUAl, department)   // null → atlanır
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

| `FilterOperations` | SQL qarşılığı | Nümunə dəyər |
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
