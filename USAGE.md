# jooq-sql-generate — İstifadəçi Təlimatı

## Maven / Gradle asılılığı

```xml
<!-- Maven -->
<dependency>
    <groupId>az.mbm</groupId>
    <artifactId>jooq-sql-generate</artifactId>
    <version>1.0.3</version>
</dependency>
```

```kotlin
// Gradle
implementation("az.mbm:jooq-sql-generate:1.0.3")
```

---

## 1. Giriş — JooqQuery

`JooqQuery` hər sorğu üçün yeni nümunə yaradılan, Spring-dən asılı olmayan sorğu builderidir.
Servisdə yalnız `DSLContext` inject edilir — singleton, thread-safe.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final DSLContext dsl;

    public SelectTable getOrders(String status) {
        return JooqQuery.from(Order.class, "o")
            .select("o.id", "o.orderNo", "o.status", "o.totalPrice")
            .filter("o.status", Op.EQUAl, status)
            .orderBy("o.createdAt", "DESC")
            .page(0, 20)
            .execute(dsl);
    }
}
```

### 1.1 Giriş nöqtələri

**Entity mode** — JPA `@Entity` sinfi ilə (reflection + cache):
```java
JooqQuery.from(User.class, "u")
```

**Generated mode** — jOOQ generated `Table<?>` ilə (tip-təhlükəli, compile xətası):
```java
import static com.example.jooq.Tables.*;

JooqQuery.from(USERS, "u")
```

**Derived table mode** — başqa bir `SelectTable` nəticəsindən:
```java
SelectTable sub = JooqQuery.from(Order.class, "o")
    .select("o.id", "o.userId", "o.totalPrice")
    .filter("o.status", Op.EQUAl, "ACTIVE")
    .noPagination()
    .execute(dsl);

JooqQuery.from(sub, "s")
    .select("s.id", "s.totalPrice")
    .filter("s.totalPrice", Op.GREATER_THAN, 1000)
    .execute(dsl);
// → SELECT s.id, s.total_price
//   FROM (SELECT o.id, o.user_id, o.total_price FROM orders o WHERE ...) s
//   WHERE s.total_price > 1000
```

---

## 2. SELECT

### 2.1 Sadə sütunlar

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email", "u.status")
    .execute(dsl);
// → SELECT u."id", u."first_name", u."email", u."status" FROM users u
```

String siyahı ilə:
```java
List<String> cols = List.of("u.id", "u.firstName", "u.email");
JooqQuery.from(User.class, "u").select(cols).execute(dsl);
```

### 2.2 selectAs — özəl alias

Eyni sütun adına sahib join cədvəllərini fərqləndirmək üçün:
```java
JooqQuery.from(Task.class, "t")
    .selectAs("propertyValue.propertyValue",     "propertyValue")
    .selectAs("propertyValueType.propertyValue", "propertyValueType")
    .selectAs("fkCashItemTypeKey.propertyValue", "cashItemTypeValue")
    .execute(dsl);
```

> **Qeyd:** Eyni source field həm `select(...)`, həm `selectAs(...)` ilə əlavə edildikdə
> SELECT-də dublikat sütun yaranmır — `selectAs` versiyası (aliasla olan) qalır, `select`-dəki atlanır.

### 2.3 DISTINCT

```java
JooqQuery.from(Order.class, "o")
    .select("o.customerId", "o.status")
    .distinct()
    .execute(dsl);
// → SELECT DISTINCT o."customer_id", o."status" FROM orders o
```

### 2.4 computedColumn — riyazi ifadə sütunu

İki sahə arasında `+`, `-`, `*`, `/`:
```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.price", "o.qty")
    .computedColumn("lineTotal", "o", MathOperation.MULTIPLY, "price", "o", "qty")
    .execute(dsl);
// → SELECT ..., (o."price" * o."qty") AS "lineTotal"
```

Mürəkkəb çox sahəli ifadə (`ComputedField`):
```java
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.of("o.price")
            .multiply("o.qty")
            .subtract("o.discount")
            .as("netAmount")
    )
    .execute(dsl);
// → SELECT ((o."price" * o."qty") - o."discount") AS "netAmount"
```

Filter ilə birlikdə (HAVING-ə çevrilir):
```java
.computedColumn(
    ComputedField.of("o.price").multiply("o.qty").as("lineTotal"),
    Op.GREATER_THAN, 500
)
// → HAVING ((price * qty)) > 500
```

### 2.5 COALESCE

```java
JooqQuery.from(User.class, "u")
    .coalesce("displayName", "Naməlum", "u.firstName", "u.lastName")
    .execute(dsl);
// → SELECT COALESCE(u."first_name", u."last_name", 'Naməlum') AS "displayName"
```

### 2.6 selectRound — yuvarlama sütunu

`selectRound` ilə həmin alias-a `filter()` tətbiq edildikdə avtomatik `WHERE ROUND(field, scale)` yaranır:
```java
JooqQuery.from(Order.class, "o")
    .selectRound("o.totalPrice", 2, "roundedTotal")
    .filter("roundedTotal", Op.GREATER_THAN, 100)
    .execute(dsl);
// → SELECT ROUND(o."total_price", 2) AS "roundedTotal"
//   WHERE  ROUND(o."total_price", 2) > 100
```

### 2.7 subSelect — scalar subquery sütunu

```java
SubSelectBuilder sub = SubSelectBuilder
    .from(Order.class, "o")
    .correlateOn("u.id", "userId")
    .selectField("totalPrice")
    .as("lastOrderTotal");

JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .subSelect(sub)
    .execute(dsl);
// → SELECT u.id, u.first_name,
//          (SELECT o.total_price FROM orders o WHERE o.user_id = u.id) AS last_order_total
```

---

## 3. JOIN

### 3.1 Sadə LEFT / INNER JOIN (tək field cütü)

```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.orderNo", "u.firstName", "u.email")
    .leftJoin(User.class, "u", "fkUserId", "id")
    .execute(dsl);
// → LEFT JOIN users u ON o."fk_user_id" = u."id"
```

### 3.2 Builder JOIN — çoxlu ON şərti

```java
JooqQuery.from(WarehouseFlow.class, "t")
    .leftJoin(Product.class, "p")
        .on("fkProductId", "id")          // t.fk_product_id = p.id
        .on("companyId",   "companyId")    // t.company_id    = p.company_id
        .andOn("status", Op.EQUAl, "A")   // AND p.status = 'A'
    .done()
    .execute(dsl);
```

### 3.3 onFrom — zəncir JOIN (ikinci cədvəl üçüncüyə)

Birinci → ikinci cədvəl normal leftJoin, ikinci → üçüncü `onFrom()` ilə:
```java
JooqQuery.from(Task.class, "t")
    // 1-ci → 2-ci: normal leftJoin
    .leftJoin(TaskType.class, "taskType", "fkTaskTypeKey", "taskTypeKey")

    // 2-ci → 3-cü: onFrom ilə taskType-dan
    .leftJoin(TaskTypeDetail.class, "taskTypeDetail")
        .onFrom("taskType", "taskTypeKey", "fkTaskTypeKey")
    .done()
    .execute(dsl);
// → LEFT JOIN task_types taskType ON t.fk_task_type_key = taskType.task_type_key
//   LEFT JOIN task_type_details taskTypeDetail ON taskType.task_type_key = taskTypeDetail.fk_task_type_key
```

### 3.4 Generated table JOIN (tip-təhlükəli)

```java
JooqQuery.from(ORDERS, "o")
    .leftJoin(USERS, "u", ORDERS.USER_ID.eq(USERS.ID))
    .execute(dsl);
```

### 3.5 SelectTable JOIN

Başqa bir `SelectTable` nəticəsini join etmək:
```java
SelectTable budgetQuery = JooqQuery.from(Budget.class, "b")
    .select("b.fkAccountId", "b.fkCurrencyId", "b.budgetAmount")
    .noPagination()
    .execute(dsl);

JooqQuery.from(Flow.class, "f")
    .select("f.id", "f.amount")
    .leftJoin(budgetQuery, "b", "f.fkAccountId", "fkAccountId")
    .execute(dsl);
// → LEFT JOIN (SELECT ...) b ON f."fk_account_id" = b."fk_account_id"
```

Çoxlu ON ilə:
```java
.leftJoin(budgetQuery, "b")
    .on("f.fkAccountId",  "fkAccountId")
    .on("f.fkCurrencyId", "fkCurrencyId")
    .andOn("status", Op.EQUAl, "ACTIVE")
.done()
```

---

## 4. WHERE — filter

### 4.1 Sadə filter

`null`, boş string, boş kolleksiya olduqda **avtomatik atlanır**:
```java
JooqQuery.from(User.class, "u")
    .filter("u.status",    Op.EQUAl,       status)    // null → atlanır
    .filter("u.firstName", Op.LIKE,         name)      // boş → atlanır
    .filter("u.roleId",    Op.IN,           roleIds)   // boş list → atlanır
    .filter("u.age",       Op.GREATER_THAN, 18)
    .filter("u.createdAt", Op.BETWEEN,      "2024-01-01,2024-12-31")
    .execute(dsl);
```

### 4.2 Bütün filter əməliyyatları (Op enum)

| Op | SQL | Nümunə |
|---|---|---|
| `EQUAl` | `= value` | `Op.EQUAl, "ACTIVE"` |
| `NOT_EQUAL` | `!= value` | `Op.NOT_EQUAL, "BANNED"` |
| `LIKE` | `LOWER(REPLACE(...)) LIKE '%val%'` | `Op.LIKE, "ali"` |
| `START_WITH` | Türk-aware `LIKE 'val%'` | `Op.START_WITH, "A"` |
| `END_WITH` | Türk-aware `LIKE '%val'` | `Op.END_WITH, ".az"` |
| `LIKE_IGNORE_CASE` | Türk-aware `LIKE '%val%'` | `Op.LIKE_IGNORE_CASE, "İlkin"` |
| `START_WITH_IGNORE_CASE` | Türk-aware `LIKE 'val%'` | `Op.START_WITH_IGNORE_CASE, "İ"` |
| `END_WITH_IGNORE_CASE` | Türk-aware `LIKE '%val'` | `Op.END_WITH_IGNORE_CASE, "lı"` |
| `IN` | `IN (...)` | `Op.IN, List.of(1,2,3)` |
| `NOT_IN` | `NOT IN (...)` | `Op.NOT_IN, List.of(4,5)` |
| `BETWEEN` | `BETWEEN a AND b` | `Op.BETWEEN, "100,500"` |
| `GREATER_THAN` | `> value` | `Op.GREATER_THAN, 100` |
| `GREATER_THAN_OR_EQUAL_TO` | `>= value` | `Op.GREATER_THAN_OR_EQUAL_TO, 0` |
| `LESS_THAN` | `< value` | `Op.LESS_THAN, 1000` |
| `LESS_THAN_OR_EQUAL_TO` | `<= value` | `Op.LESS_THAN_OR_EQUAL_TO, 100` |
| `IS_EMPTY` | `IS NULL` | `Op.IS_EMPTY, ""` |
| `IS_NOT_EMPTY` | `IS NOT NULL` | `Op.IS_NOT_EMPTY, ""` |
| `REGEXP` | `REGEXP pattern` | `Op.REGEXP, "^A.*"` |
| `NOT_REGEXP` | `NOT REGEXP pattern` | `Op.NOT_REGEXP, "^B"` |

### 4.3 Türk əlifbası case-insensitive LIKE

`Op.LIKE`, `Op.START_WITH`, `Op.END_WITH` default olaraq Türk-aware məntiqilə işləyir:
```java
.filter("u.firstName", Op.LIKE, "İlkin")
// → WHERE LOWER(REPLACE(REPLACE(u."first_name",'İ','i'),'I','i'))
//         LIKE '%ilkin%'
// "İlkin", "ilkin", "ILKIN", "iLKİN" — hamısı tapılır
```

### 4.4 globalFilter — Filters builder ilə

```java
JooqQuery.from(Order.class, "o")
    .globalFilter(
        Filters.of()
            .equal("o.status",    "ACTIVE")
            .like("o.orderNo",    orderNo)
            .greaterThan("o.amount", "100")
            .between("o.createdAt", "2024-01-01,2024-12-31")
    )
    .execute(dsl);
```

### 4.5 globalFilter — Map ilə (REST sorğularından gəldikdə)

```java
// Tək field üçün çoxlu əməliyyat
.globalFilter("o.amount", Map.of(
    "greaterThan", "100",
    "lessThan",    "500"
))

// Bütün field xəritəsi
.globalFilter(Map.of(
    "o.status",  Map.of("equal",       "ACTIVE"),
    "o.orderNo", Map.of("like",        "ORD-2024"),
    "u.name",    Map.of("likeIgnoreCase", "İlkin")
))
```

### 4.6 Birbaşa jOOQ Condition

```java
.filter(USERS.STATUS.eq("ACTIVE"))
.filter(USERS.AGE.gt(18).and(USERS.STATUS.ne("BANNED")))
.rawCondition(DSL.condition("u.created_at > NOW() - INTERVAL '30 days'"))
```

---

## 5. IN (SELECT ...) — SubQueryIn

### 5.1 Tək sahə

```java
SubQueryIn activeSub = SubQueryIn.from(Order.class, "o")
    .select("o.userId")
    .filter("status",  Op.EQUAl,       "PAID")
    .filter("amount",  Op.GREATER_THAN, 1000);

JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .inSubQuery("u.id", activeSub)
    .execute(dsl);
// → WHERE u."id" IN (SELECT o."user_id" FROM orders o
//                     WHERE o."status" = 'PAID' AND o."amount" > 1000)
```

### 5.2 NOT IN

```java
SubQueryIn blacklist = SubQueryIn.notFrom(Blacklist.class, "bl")
    .select("bl.userId");

JooqQuery.from(User.class, "u")
    .inSubQuery("u.id", blacklist)
    .execute(dsl);
// → WHERE u."id" NOT IN (SELECT bl."user_id" FROM blacklist bl)
```

### 5.3 Composite IN (çoxlu sahə)

```java
SubQueryIn sub = SubQueryIn.from(Blacklist.class, "bl")
    .select("bl.firstName", "bl.lastName");

JooqQuery.from(User.class, "u")
    .inSubQuery(new String[]{"u.firstName", "u.lastName"}, sub)
    .execute(dsl);
// → WHERE (u."first_name", u."last_name")
//         IN (SELECT bl."first_name", bl."last_name" FROM blacklist bl)
```

---

## 6. EXISTS / NOT EXISTS

```java
ExistsSpec<Order, ?> hasOrders = ExistsSpec
    .exists(Order.class, "o")
    .correlate("u.id", "fkUserId")      // WHERE o.fk_user_id = u.id
    .filter("status", Op.EQUAl, "PAID");

JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .exists(hasOrders)
    .execute(dsl);
// → WHERE EXISTS (SELECT 1 FROM orders o
//                  WHERE o."fk_user_id" = u."id" AND o."status" = 'PAID')
```

---

## 7. GROUP BY + Aqreqat

### 7.1 Sadə agg

```java
JooqQuery.from(Order.class, "o")
    .select("o.customerId")
    .groupBy("o.customerId")
    .agg(Agg.SUM,   "o.totalPrice", "totalSum",   2,    "DESC")
    .agg(Agg.COUNT, "o.id",         "orderCount", null, null)
    .agg(Agg.AVG,   "o.totalPrice", "avgPrice",   2,    null)
    .execute(dsl);
// → SELECT o."customer_id",
//          ROUND(SUM(o."total_price"),2)  AS "totalSum",
//          COUNT(o."id")                  AS "orderCount",
//          ROUND(AVG(o."total_price"),2)  AS "avgPrice"
//   FROM orders o
//   GROUP BY o."customer_id"
//   ORDER BY ROUND(SUM(o."total_price"),2) DESC
```

### 7.2 Riyazi ifadəli agg — SUM(price * qty)

```java
.aggWithMath(Agg.SUM, "o.price", MathOperation.MULTIPLY, "o.qty", "revenue", 2)
// → ROUND(SUM(o."price" * o."qty"), 2) AS "revenue"
```

### 7.3 ComputedField üzərindəki agg — SUM((price * qty) - discount)

```java
.aggOnComputed(
    Agg.SUM,
    ComputedField.of("o.price").multiply("o.qty").subtract("o.discount"),
    "netRevenue", 2
)
// → ROUND(SUM((o."price" * o."qty") - o."discount"), 2) AS "netRevenue"
```

### 7.4 AggregateBuilder — fluent API

```java
JooqQuery.from(Order.class, "o")
    .select("o.customerId")
    .aggregate(
        AggregateBuilder.<Order>groupBy("o.customerId")
            .sum("o.totalPrice").round(2).as("totalSum")
                .having(Op.GREATER_THAN, 1000)
            .done()
            .count("o.id").as("cnt").done()
    )
    .execute(dsl);
// → HAVING ROUND(SUM(total_price),2) > 1000
```

### 7.5 Set ilə GROUP BY

GROUP BY-da duplikat alias problemi yoxdur — actual sütun ifadəsi istifadə olunur:
```java
Set<String> cols = new HashSet<>();
cols.add("t.fkRequestId");
cols.add("t.operationDate");
cols.add("taskType.taskTypeName");
// ... digər sütunlar

JooqQuery.from(Task.class, "t")
    .select(new ArrayList<>(cols))
    .groupBy(new ArrayList<>(cols))
    .execute(dsl);
```

### 7.6 selectAs + groupBy — avtomatik SELECT

`groupBy` verildiyi halda, `selectAs` ilə **yalnız bəzi sütunlara** özəl alias vermək mümkündür.
Qalan GROUP BY sütunları SELECT-ə **avtomatik əlavə** olunur:

```java
Set<String> groupByCols = new HashSet<>();
groupByCols.add("t.operationDate");
groupByCols.add("t.actionType");
groupByCols.add("cashItemType.propertyValue");   // Properties-dən ilk alias
groupByCols.add("propertyValueType.propertyValue"); // Properties-dən ikinci alias

JooqQuery.from(CashFlow.class, "t")
    .addLeftJoin(PropertiesEntity.class, "cashItemType")
        .onFrom("t", "fkCashItemTypeKey", "propertyKey")
        .andOn("propertyCode", Op.EQUAl, "madde_novu").done()
    .addLeftJoin(PropertiesEntity.class, "propertyValueType")
        .onFrom("t", "actionType", "propertyKey")
        .andOn("propertyCode", Op.EQUAl, "pul_axisi").done()
    // Yalnız alias fərqli olan sütunlara selectAs ver:
    .selectAs("cashItemType.propertyValue",     "cashItemTypeName")
    .selectAs("propertyValueType.propertyValue","propertyValueType")
    // Qalan groupBy sütunları (t.operationDate, t.actionType) avtomatik SELECT-ə gəlir
    .groupBy(new ArrayList<>(groupByCols))
    .agg(Agg.SUM, "t.totalPrice", "totalPrice")
    .execute(dsl);
// → SELECT "cashItemType"."property_value"     AS "cashItemTypeName",
//          "propertyValueType"."property_value" AS "propertyValueType",
//          "t"."operation_date",
//          "t"."action_type",
//          SUM("t"."total_price") AS "totalPrice"
//   FROM ... GROUP BY ...
```

> **Eyni adlı sütunlar (çoxlu Properties join):** Eyni entity fərqli aliaslarla join edildikdə
> (məs. `cashItemType`, `propertyValueType`, `propertyValue` — hamısı `PropertiesEntity`),
> GROUP BY hər birini ayrı sütun kimi emal edir. Alias.fieldName açarı istifadə olunur,
> buna görə `cashItemType.property_value` ilə `propertyValueType.property_value` toqquşmur.

---

## 8. HAVING

```java
JooqQuery.from(Order.class, "o")
    .groupBy("o.customerId")
    .agg(Agg.SUM,   "o.totalPrice", "totalSum", 2, null)
    .agg(Agg.COUNT, "o.id",         "cnt",      null, null)
    // agg alias-ı ilə HAVING
    .havingFilter("totalSum", Map.of("greaterThan", "1000"))
    .havingFilter("cnt",      Map.of("between",     "5,50"))
    // birbaşa field ilə HAVING
    .havingFilter("o.status", Op.NOT_EQUAL, "CANCELLED")
    .execute(dsl);
```

---

## 9. CASE WHEN

### 9.1 Sadə caseWhen

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .caseWhen("u.status", Op.EQUAl, "ACTIVE", "Aktiv", "Deaktiv", "statusLabel")
    .execute(dsl);
// → CASE WHEN u."status" = 'ACTIVE' THEN 'Aktiv' ELSE 'Deaktiv' END AS "statusLabel"
```

### 9.2 CaseBuilder — çox şərtli

```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.totalPrice")
    .caseWhen(
        CaseBuilder.<Order>when("status", Op.EQUAl, "PAID")     .then("Ödənilib")
                   .andWhen("status", Op.EQUAl, "PENDING")  .then("Gözləyir")
                   .andWhen("status", Op.EQUAl, "CANCELLED").then("Ləğv edilib")
                   .otherwise("Naməlum")
                   .as("statusLabel")
    )
    .execute(dsl);
```

---

## 10. ORDER BY

```java
// Sadə
.orderBy("u.createdAt", "DESC")
.orderBy("u.firstName", "ASC")

// Map ilə
.orderBy(Map.of(
    "u.createdAt", "DESC",
    "u.firstName", "ASC"
))

// List<Map> ilə (sıra qorunur)
.orderBy(List.of(
    Map.of("u.createdAt", "DESC"),
    Map.of("u.firstName", "ASC")
))

// Generated field ilə
.orderBy(USERS.CREATED_AT.desc(), USERS.FIRST_NAME.asc())
```

---

## 11. Səhifələmə (Pagination)

```java
.page(0, 20)      // İlk 20 sətir
.page(1, 20)      // 21–40-cı sətirlar
.noPagination()   // Bütün nəticəni gətir, COUNT olmadan
.withCount()      // Yalnız COUNT — siyahı olmadan
.page(0, 50000).skipCount()  // Pagination var, COUNT sorğusu işləmir (rowCount = -1)
.onlyCount()      // Yalnız COUNT işləyir, əsas data sorğusu icra edilmir (result = boş)
```

### skipCount — COUNT olmadan pagination

Excel export kimi ssenarilərdə ümumi sətir sayı lazım deyil, amma LIMIT/OFFSET istifadə etmək lazımdır:

```java
var result = JooqQuery.from(CashFlow.class, "t")
    .select("t.id", "t.operationDate")
    .page(0, 50000)
    .skipCount()       // COUNT sorğusu işləmir — performans üstünlüyü
    .execute(dsl);

// result.getTotalCount() → -1 (hesablanmayıb)
// result.getResult()     → sətirlərin siyahısı (LIMIT tətbiq olunub)
```

> **Nə zaman istifadə et:** COUNT-un lazım olmadığı, amma böyük dataseti hissə-hissə çəkməyin gərəkdiyi hallarda (Excel export, batch emal və s.).

### onlyCount — data olmadan yalnız sətir sayı

Əsas SELECT sorğusu **heç icra edilmir**, yalnız COUNT işləyir. Sətir sayını əvvəlcədən bilmək lazım olduqda istifadə et:

```java
// JooqQuery ilə:
SelectTable result = JooqQuery.from(CashFlow.class, "t")
    .filter("t.status", Op.EQUAl, "A")
    .filter("t.operationDate", Op.BETWEEN, "2024-01-01,2024-12-31")
    .onlyCount()
    .execute(dsl);

int rowCount = result.getRowCount();  // ← sətir sayı
// result.getSelectTable() → boş sorğu (fetch edilməməlidir)
```

```java
// JooqManager ilə:
jooqManager.setMainTable(CashFlowEntity.class, "t")
    .addFilter("t.status", Op.EQUAl, "A")
    .addFilter("t.operationDate", Op.BETWEEN, "2024-01-01,2024-12-31")
    .onlyCount()
    .fetchMapsNullSafe();  // boş siyahı qaytarır

int rowCount = jooqManager.getLastRowCount();  // ← sətir sayı buradadır
```

> **Nə zaman istifadə et:** Yalnız sətir sayını bilmək lazım olduqda (UI-da göstərmək, limitə yoxlamaq və s.) — əsas data sorğusu işlətmədən.

| Metod | Data | COUNT | rowCount |
|---|---|---|---|
| `page(p, s)` | ✓ LIMIT/OFFSET | ✓ | ümumi say |
| `noPagination()` | ✓ hamısı | ✗ | 0 |
| `withCount()` | ✓ hamısı | ✓ | ümumi say |
| `page(p,s).skipCount()` | ✓ LIMIT/OFFSET | ✗ | -1 |
| `onlyCount()` | ✗ boş | ✓ | ümumi say |

`SelectTable`-dan nəticə oxumaq:
```java
SelectTable result = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .page(0, 20)
    .execute(dsl);

long totalCount              = result.getTotalCount();   // bütün sətirlərin sayı
int  pageCount               = result.getPageCount();    // səhifə sayı
List<Map<String,Object>> rows = result.getResult();      // cari səhifənin sətirləri

for (Map<String,Object> row : rows) {
    Long   id   = (Long)   row.get("id");
    String name = (String) row.get("firstName");
}
```

---

## 12. ROUND filter əməliyyatları

`selectRound()` olmadan birbaşa `WHERE ROUND(field, scale)` yazmaq üçün:
```java
.filter("o.totalPrice", Op.EQUAL_ROUND_2,               "99.99")
.filter("o.totalPrice", Op.GREATER_THAN_ROUND_2,         "100")
.filter("o.totalPrice", Op.LESS_THAN_OR_EQUAL_TO_ROUND_2, "500")
// → WHERE ROUND(o."total_price", 2) = 99.99
// → WHERE ROUND(o."total_price", 2) > 100
// → WHERE ROUND(o."total_price", 2) <= 500
```

Mövcud scale-lər: `_ROUND_0` (tam ədəd), `_ROUND_1`, `_ROUND_2`, `_ROUND_3`, `_ROUND_4`.
Hər scale üçün: `EQUAL`, `NOT_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL_TO`, `LESS_THAN`, `LESS_THAN_OR_EQUAL_TO`.

---

## 13. FilterOperationConstants — String sabitlər

`globalFilter(Map)` istifadə edərkən əməliyyat adlarını hardcode etmək əvəzinə sabitlər:
```java
import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;

.globalFilter("o.amount",  Map.of(GREATER_THAN, "100", LESS_THAN, "500"))
.globalFilter("u.status",  Map.of(EQUAl, "ACTIVE"))
.globalFilter("u.name",    Map.of(LIKE, name))
.globalFilter("u.name",    Map.of(LIKE_IGNORE_CASE, "İlkin"))
.globalFilter("o.price",   Map.of(EQUAL_ROUND_2, "9.99"))
```

---

## 14. UPDATE

```java
int updated = new UpdateQueryBuilder<>(User.class, dsl)
    .set("status",    "INACTIVE")
    .set("updatedAt", LocalDateTime.now())
    .where(Spec.eq("id", userId))
    .execute();
```

Çox şərtli WHERE:
```java
new UpdateQueryBuilder<>(Order.class, dsl)
    .set("status", "CANCELLED")
    .where(Spec.eq("status", "PENDING")
               .and(Spec.lt("createdAt", LocalDateTime.now().minusDays(30))))
    .execute();
```

SQL debug (icrasız):
```java
String sql = new UpdateQueryBuilder<>(User.class, dsl)
    .set("status", "INACTIVE")
    .where(Spec.eq("id", 1L))
    .toSQL();
```

WHERE olmadan UPDATE qadağandır — `IllegalStateException` atır.

---

## 15. Generated mode — jOOQ generated cədvəllər

```java
import static com.example.jooq.Tables.*;

SelectTable result = JooqQuery.from(USERS, "u")
    .select(USERS.ID, USERS.FIRST_NAME, USERS.STATUS)
    .filter(USERS.STATUS.eq("ACTIVE"))
    .filter(USERS.AGE, Op.GREATER_THAN, 18)
    .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
    .groupBy(USERS.DEPARTMENT)
    .orderBy(USERS.CREATED_AT.desc())
    .page(0, 20)
    .execute(dsl);
```

---

## 16. Tam nümunə — mürəkkəb sorğu

```java
public SelectTable getTaskReport(TaskFilterRequest req) {
    return JooqQuery.from(Task.class, "t")

        // SELECT
        .select("t.fkRequestId", "t.taskNo", "t.operationDate", "t.actionType")
        .selectAs("taskType.taskTypeName",       "taskTypeName")
        .selectAs("employee.firstName",           "employeeFirstName")
        .selectAs("employee.surname",             "employeeSurname")
        .selectAs("propertyValue.propertyValue",  "propertyValue")
        .selectAs("propertyValueType.propertyValue", "propertyValueType")
        .selectRound("t.rate", 4, "roundedRate")

        // JOIN — birinci → ikinci
        .leftJoin(TaskType.class,     "taskType",     "fkTaskTypeKey",         "taskTypeKey")
        .leftJoin(Employee.class,     "employee",     "fkCashierId",           "id")
        .leftJoin(PropertyValue.class,"propertyValue","fkPaymentFeatureKey",   "propertyKey")
        .leftJoin(PropertyValue.class,"propertyValueType","fkPaymentRootKey",  "propertyKey")

        // JOIN — ikinci → üçüncü (taskType üzərindən)
        .leftJoin(TaskTypeDetail.class, "taskTypeDetail")
            .onFrom("taskType", "taskTypeKey", "fkTaskTypeKey")
        .done()

        // WHERE
        .filter("t.operationDate", Op.BETWEEN,    req.getDateRange())
        .filter("t.actionType",    Op.EQUAl,      req.getActionType())
        .filter("t.fkCashierId",   Op.IN,         req.getCashierIds())
        .filter("taskType.taskTypeName", Op.LIKE,  req.getTaskTypeName())

        // globalFilter (REST-dən gəlir)
        .globalFilter(req.getFilters())

        // GROUP BY
        .groupBy("t.fkRequestId", "t.taskNo", "t.operationDate", "t.actionType",
                 "taskType.taskTypeName", "employee.firstName", "employee.surname",
                 "propertyValue.propertyValue", "propertyValueType.propertyValue")
        .agg(Agg.SUM, "t.rate", "totalRate", 4, "DESC")

        // HAVING
        .havingFilter("totalRate", Map.of("greaterThan", "0"))

        // ORDER BY + Pagination
        .orderBy("t.operationDate", "DESC")
        .page(req.getPage(), req.getSize())

        .execute(dsl);
}
```

---

## 17. Qısa referans

### JooqQuery metodları

| Metod | Təsvir |
|---|---|
| `from(Class, alias)` | Entity mode başlatma |
| `from(Table, alias)` | Generated mode başlatma |
| `from(SelectTable, alias)` | Derived table mode |
| `select(cols...)` | SELECT sütunları |
| `select(List)` | SELECT — dinamik siyahı |
| `selectAs(field, alias)` | Özəl alias ilə SELECT |
| `selectRound(field, scale, alias)` | ROUND SELECT + filter |
| `distinct()` | SELECT DISTINCT |
| `computedColumn(...)` | Riyazi ifadə sütunu |
| `coalesce(alias, def, fields...)` | COALESCE sütunu |
| `subSelect(SubSelectBuilder)` | Scalar subquery sütunu |
| `caseWhen(...)` | CASE WHEN sütunu |
| `leftJoin(entity, alias, from, to)` | Sadə LEFT JOIN |
| `leftJoin(entity, alias).on(...).done()` | Builder LEFT JOIN |
| `leftJoin(entity, alias).onFrom(...).done()` | Zəncir JOIN |
| `leftJoin(Table, alias, Condition)` | Generated LEFT JOIN |
| `leftJoin(SelectTable, alias, ...)` | SelectTable JOIN |
| `filter(field, Op, value)` | WHERE filter (null-safe) |
| `filter(Condition)` | Birbaşa jOOQ şərti |
| `globalFilter(Filters)` | Filters builder ilə |
| `globalFilter(field, Map)` | Map ilə çoxlu əməliyyat |
| `globalFilter(Map<String,Map>)` | Tam field xəritəsi |
| `inSubQuery(field, SubQueryIn)` | IN (SELECT ...) |
| `inSubQuery(fields[], SubQueryIn)` | COMPOSITE IN |
| `exists(ExistsSpec)` | EXISTS / NOT EXISTS |
| `groupBy(fields...)` | GROUP BY |
| `agg(Agg, field, alias, round, dir)` | Aqreqat funksiya |
| `aggWithMath(...)` | Riyazi aqreqat SUM(f1*f2) |
| `aggOnComputed(...)` | ComputedField aqreqat |
| `havingFilter(field, Map)` | HAVING filter |
| `havingFilter(field, Op, value)` | HAVING birbaşa filter |
| `orderBy(field, dir)` | ORDER BY |
| `orderBy(Map)` | ORDER BY map ilə |
| `page(page, size)` | Səhifələmə (0-dan başlayır) |
| `skipCount()` | Pagination var, COUNT işləmir (rowCount = -1) |
| `onlyCount()` | Yalnız COUNT işləyir, əsas data sorğusu icra edilmir |
| `noPagination()` | Bütün nəticə, COUNT olmadan |
| `withCount()` | Bütün data + COUNT |
| `execute(dsl)` | Sorğunu icra et |
