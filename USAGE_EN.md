# jooq-sql-generate — User Guide

## Maven / Gradle Dependency

```xml
<!-- Maven -->
<dependency>
    <groupId>az.mbm</groupId>
    <artifactId>jooq-sql-generate</artifactId>
    <version>1.1.12</version>
</dependency>
```

```kotlin
// Gradle
implementation("az.mbm:jooq-sql-generate:1.1.12")
```

---

## 1. Introduction — JooqQuery

`JooqQuery` is a stateless query builder — create a new instance for every query.
Only `DSLContext` needs to be injected in your service (singleton, thread-safe).

```java
@Service
public class UserService {
    private final DSLContext dsl;

    public UserService(DSLContext dsl) { this.dsl = dsl; }

    public SelectTable getUsers() {
        return JooqQuery.from(User.class, "u")
            .select("u.id", "u.firstName", "u.email")
            .filter("u.status", Op.EQUAl, "ACTIVE")
            .page(0, 20)
            .execute(dsl);
    }
}
```

### 1.1 Entry Points

**Entity mode** — JPA `@Entity` class (reflection + cache):
```java
JooqQuery.from(User.class, "u")
```

**Generated mode** — jOOQ generated `Table<?>` (type-safe, compile-time errors):
```java
import static com.example.jooq.Tables.*;

JooqQuery.from(USERS, "u")
```

**Derived table mode** — query over another `SelectTable` result:
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

### 2.1 Simple columns

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email", "u.status")
    .execute(dsl);
// → SELECT u."id", u."first_name", u."email", u."status" FROM users u
```

With a list:
```java
List<String> cols = List.of("u.id", "u.firstName", "u.email");
JooqQuery.from(User.class, "u").select(cols).execute(dsl);
```

### 2.2 selectAll — all columns of the main entity

`selectAll()` adds all columns of the main entity with camelCase aliases.
Unlike `SELECT *`: only main entity columns are included, JOIN table columns are excluded.

```java
JooqQuery.from(User.class, "u").selectAll().execute(dsl);
// → SELECT u."id", u."first_name" AS "firstName", u."email", ... FROM users u
```

With JOIN — only main entity columns are listed explicitly, joined columns must be added separately:
```java
JooqQuery.from(User.class, "u")
    .selectAll()
    .select("o.orderId", "o.totalPrice")
    .leftJoin(Order.class, "o", "id", "userId")
    .execute(dsl);
```

### 2.3 selectAs — custom alias

```java
JooqQuery.from(User.class, "u")
    .selectAs("u.firstName", "name")
    .selectAs("u.email",     "mail")
    .execute(dsl);
// → SELECT u."first_name" AS "name", u."email" AS "mail" FROM users u
```

### 2.4 DISTINCT

```java
JooqQuery.from(User.class, "u")
    .select("u.status")
    .distinct()
    .execute(dsl);
// → SELECT DISTINCT u."status" FROM users u
```

### 2.5 computedColumn — arithmetic expression column

```java
JooqQuery.from(Order.class, "o")
    .computedColumn(
        ComputedField.of("o.price")
            .multiply("o.quantity")
            .subtract("o.discount")
            .as("netAmount")
    )
    .execute(dsl);
// → SELECT (o.price * o.quantity) - o.discount AS netAmount FROM orders o
```

**Multi-part sum:**
```java
.computedColumn(
    ComputedField.sumOf(
        ComputedField.expr("o.price").multiply("o.qty"),
        ComputedField.expr("o.tax"),
        ComputedField.expr("o.shipping").subtract("o.discount")
    ).as("grandTotal")
)
```

**Conditional expression (CASE WHEN inside computed):**
```java
.computedColumn(
    ComputedField.of("o.price")
        .subtract("o.discount")
        .where("o.status", Op.EQUAl, "PAID")
        .as("paidNet")
)
// → CASE WHEN o.status = 'PAID' THEN (o.price - o.discount) ELSE NULL END AS paidNet
```

### 2.6 compute — fluent inline expression

```java
JooqQuery.from(Order.class, "o")
    .compute("o.price").subtract("o.discount").as("netPrice")
    .execute(dsl);
```

**Parenthesized group:**
```java
JooqQuery.from(Order.class, "o")
    .compute("o.price")
        .subtract()
        .of("o.cost").divide("o.price").done()
    .multiply("o.hundred")
    .as("marginPct")
    .execute(dsl);
// → (o.price - (o.cost / o.price)) * o.hundred AS marginPct
```

### 2.7 COALESCE

```java
JooqQuery.from(User.class, "u")
    .coalesce("displayName", "N/A", "u.firstName", "u.lastName", "u.email")
    .execute(dsl);
// → COALESCE(first_name, last_name, email, 'N/A') AS displayName
```

### 2.8 selectRound — rounded column

```java
JooqQuery.from(Order.class, "o")
    .selectRound("o.totalPrice", 2, "price")
    .execute(dsl);
// → ROUND(o."total_price", 2) AS "price"
```

Filters applied to this alias automatically use `ROUND` too:
```java
.selectRound("o.totalPrice", 2, "price")
.filter("price", Op.GREATER_THAN, 100)
// → WHERE ROUND(o."total_price", 2) > 100
```

### 2.9 subSelect — scalar subquery column

```java
JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .subSelect(
        SubSelectBuilder.from(Order.class, "o")
            .select("o.totalPrice")
            .where("o.userId", "u.id")
            .orderByDesc("o.createdAt")
            .limit(1)
            .as("lastOrderAmount")
    )
    .execute(dsl);
// → SELECT u.id, u.first_name,
//          (SELECT o.total_price FROM orders o
//           WHERE o.user_id = u.id ORDER BY o.created_at DESC LIMIT 1) AS lastOrderAmount
//   FROM users u
```

### 2.10 CONCAT — concatenate columns

```java
// Simple
manager.addConcatColumn("fullName", " ", "u.firstName", "u.lastName")
// → COALESCE(first_name,'') || ' ' || COALESCE(last_name,'') AS fullName

// With ConcatItem (literal + field mix)
import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;

manager.addConcatColumn("userCode", "-", literal("USR"), field("u.id"))
// → 'USR' || '-' || COALESCE(id,'') AS userCode
```

**`ConcatItem.ifExpr` — conditional value inside CONCAT:**

```java
manager.addConcatColumn("statusLabel", " | ",
    field("u.name"),
    ifExpr("o.status", "PAID", "Paid", "Pending"))
// → COALESCE(name,'') || ' | ' || CASE WHEN status='PAID' THEN 'Paid' ELSE 'Pending' END
```

**`ConcatItem.coalesce` — COALESCE inside CONCAT:**

```java
manager.addConcatColumn("displayName", " ",
    literal("Name:"),
    coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonymous")))
// → 'Name:' || ' ' || COALESCE(nickname, first_name, 'Anonymous')
```

---

### 2.11 CAST — Type Conversion

Convert a column's SQL type (e.g. INTEGER → VARCHAR, VARCHAR → INTEGER, date → string).

#### Simple typed cast methods

```java
JooqQuery.from(User.class, "u")
    .castString("u.age",    "ageText")       // CAST(age AS VARCHAR)
    .castInteger("u.score", "scoreInt")       // CAST(score AS INTEGER)
    .castLong("u.code",     "codeLong")       // CAST(code AS BIGINT)
    .castBigDecimal("o.price", "priceNum")    // CAST(price AS NUMERIC)
    .execute(dsl);
```

#### Date/time formatting — works across all databases

The pattern uses PostgreSQL/Oracle syntax. MySQL and SQL Server patterns are converted automatically based on `dsl.dialect()` — no extra configuration needed.

```java
.castDateTime("o.createdAt", "YYYY-MM-DD",          "createdDate")
.castDateTime("o.orderTime", "YYYY-MM-DD HH24:MI:SS","orderTimeStr")
.castDateTime("u.birthDate", "DD MON YYYY",          "birthFormatted")
```

**What gets generated per database:**

| Database | Generated SQL |
|---|---|
| PostgreSQL / Oracle | `TO_CHAR(created_at, 'YYYY-MM-DD')` |
| MySQL / MariaDB | `DATE_FORMAT(created_at, '%Y-%m-%d')` |
| SQL Server | `FORMAT(created_at, 'yyyy-MM-dd')` |

**Common patterns:**

| Pattern | Example output |
|---|---|
| `YYYY-MM-DD` | `2024-03-15` |
| `DD/MM/YYYY` | `15/03/2024` |
| `YYYY-MM-DD HH24:MI:SS` | `2024-03-15 14:30:00` |
| `HH24:MI` | `14:30` |
| `DD MON YYYY` | `15 Mar 2024` |
| `MONTH YYYY` | `March 2024` |

#### Cast inside a ComputedField chain

```java
// Cast the result of a calculation to NUMERIC
.computedColumn(
    ComputedField.of("o.price")
        .subtract("o.discount")
        .castToBigDecimal()
        .as("netPrice")
)

// Cast a plain column to STRING
.computedColumn(
    ComputedField.of("u.age")
        .castToString()
        .as("ageText")
)

// Format a date column
.computedColumn(
    ComputedField.of("o.createdAt")
        .castToDateTime("YYYY-MM-DD")
        .as("createdDate")
)
```

**ComputedField cast methods:**

| Method | SQL result |
|---|---|
| `.castToString()` | `CAST(expr AS VARCHAR)` |
| `.castToLong()` | `CAST(expr AS BIGINT)` |
| `.castToInteger()` | `CAST(expr AS INTEGER)` |
| `.castToBigDecimal()` | `CAST(expr AS NUMERIC)` |
| `.castToDateTime(pattern)` | `TO_CHAR / DATE_FORMAT / FORMAT` — by dialect |
| `.castTo(DataType)` | Any `SQLDataType` |

#### Low-level method — any DataType

```java
import org.jooq.impl.SQLDataType;

.castColumn("o.price", SQLDataType.NUMERIC.precision(10, 2), "priceFormatted")
```

---

### 2.12 IfExpr — Conditional expressions (CASE WHEN)

`CASE WHEN field = val THEN x ELSE y END` — usable in **ComputedField**, **AggregateBuilder**, and **ConcatItem**.

#### ComputedField — as a starting point

```java
// Simple conditional column
ComputedField.ifExpr("o.status", "PAID", 1, 0)
    .as("isPaid")
// → CASE WHEN status='PAID' THEN 1 ELSE 0 END AS isPaid

// With column references — amount if PAID, otherwise 0
ComputedField.ifExpr("o.status", "PAID", "o.amount", 0)
    .add("o.tax")
    .as("result")
// → (CASE WHEN status='PAID' THEN amount ELSE 0 END) + tax AS result
```

#### ComputedField — IfExpr as a chained operand

`multiplyIf`, `addIf`, `subtractIf`, `divideIf` use `CASE WHEN` directly as a math operand:

```java
// purchaseExpense * CASE WHEN actionType='in' THEN 1 ELSE 0 END + averageCostIn
ComputedField.of("t.purchaseExpense")
    .multiplyIf("t.actionType", "in", 1, 0)
    .add("t.averageCostIn")
    .as("averageCostIn")

// base + CASE WHEN type='BONUS' THEN bonusAmount ELSE 0 END
ComputedField.of("t.base")
    .addIf("t.type", "BONUS", "t.bonusAmount", 0)
    .as("total")

// revenue - CASE WHEN type='REFUND' THEN amount ELSE 0 END
ComputedField.of("t.revenue")
    .subtractIf("t.type", "REFUND", "t.amount", 0)
    .as("netRevenue")
```

> **Short vs long form** — both produce identical SQL:
> ```java
> // Short (recommended)
> ComputedField.of("t.price").multiplyIf("t.type", "SALE", 1, 0).as("salePrice")
>
> // Long (nested ComputedField)
> ComputedField.of("t.price").multiply(ComputedField.ifExpr("t.type", "SALE", 1, 0)).as("salePrice")
> ```

#### AggregateBuilder — conditional aggregate functions

```java
AggregateBuilder.<Order>groupBy("o.customerId")
    // SUM(CASE WHEN status='PAID' THEN amount ELSE 0 END)
    .sumIf("o.status", "PAID", "o.amount", 0).as("paidRevenue").done()
    // Conditional count via SUM
    .sumIf("o.type", "OUT", 1, 0).as("outCount").done()
    // COUNT(CASE WHEN status='PAID' THEN 1 END)
    .countIf("o.status", "PAID").as("paidCount").done()
    .avgIf("o.status", "PAID", "o.amount", 0).as("avgPaid").done()
    .maxIf("o.type",   "SALE", "o.amount", 0).as("maxSale").done()
    .minIf("o.type",   "SALE", "o.amount", 0).as("minSale").done()
```

#### AggStep — IfExpr as math operand

Combine a simple `.sum()` field with a `CASE WHEN` operand:

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(purchaseExpense * CASE WHEN actionType='in' THEN 1 ELSE 0 END)
    .sum("t.purchaseExpense")
        .multiplyIf("t.actionType", "in", 1, 0)
        .as("expense").done()
    // SUM(revenue - CASE WHEN type='REFUND' THEN amount ELSE 0 END)
    .sum("t.revenue")
        .subtractIf("t.type", "REFUND", "t.amount", 0)
        .as("netRevenue").done()
    // SUM(base + CASE WHEN type='BONUS' THEN bonusAmount ELSE 0 END)
    .sum("t.base")
        .addIf("t.type", "BONUS", "t.bonusAmount", 0)
        .as("total").done()
```

**IfExpr parameter reference:**

| Parameter | Meaning |
|---|---|
| `condField` (`"alias.field"`) | The condition column |
| `equalTo` | The equality value |
| `thenVal` | `"alias.field"` → column ref; anything else → SQL literal |
| `elseVal` | `"alias.field"` → column ref; anything else → SQL literal |

---

### 2.13 CoalesceExpr — Reusable COALESCE

`COALESCE(f1, f2, ..., default)` — returns the first non-null value. Usable in **ComputedField**, **ConcatItem**, and **AggregateBuilder** chains.

#### ComputedField — as a starting point

```java
// COALESCE across columns
ComputedField.coalesce("u.nickname", "u.firstName", "u.email")
    .as("displayName")
// → COALESCE(nickname, first_name, email) AS displayName

// With a default value
ComputedField.coalesce("u.nickname", "u.firstName")
    .orElse("Anonymous")
    .as("displayName")
// → COALESCE(nickname, first_name, 'Anonymous') AS displayName

// Chained with arithmetic
ComputedField.coalesce("o.discount", "o.promoDiscount")
    .orElse("0")
    .subtract("o.fee")
    .as("netDiscount")
// → COALESCE(discount, promo_discount, '0') - fee AS netDiscount
```

#### ConcatItem — COALESCE inside CONCAT

```java
import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;

manager.addConcatColumn("displayName", " ",
    literal("Name:"),
    coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonymous")))
// → 'Name:' || ' ' || COALESCE(nickname, first_name, 'Anonymous')
```

---

## 3. JOIN

### 3.1 Simple LEFT / INNER JOIN (single field pair)

```java
JooqQuery.from(Order.class, "o")
    .leftJoin(User.class,    "u", "userId", "id")
    .leftJoin(Product.class, "p", "productId", "id")
    .select("o.id", "u.firstName", "p.productName")
    .execute(dsl);
```

### 3.2 Builder JOIN — multiple ON conditions

```java
JooqQuery.from(WarehouseFlow.class, "t")
    .leftJoin(Product.class, "p")
        .on("fkProductId", "id")
        .on("companyId",   "companyId")
        .andOn("status", Op.EQUAl, "A")
    .done()
    .execute(dsl);
```

### 3.3 onFrom — chained JOIN (second table to third)

```java
JooqQuery.from(Task.class, "t")
    .leftJoin(TaskType.class, "taskType", "fkTaskTypeKey", "taskTypeKey")
    .leftJoin(TaskTypeDetail.class, "taskTypeDetail")
        .onFrom("taskType", "taskTypeKey", "fkTaskTypeKey")
    .done()
    .execute(dsl);
// → LEFT JOIN task_types taskType ON t.fk_task_type_key = taskType.task_type_key
//   LEFT JOIN task_type_details taskTypeDetail ON taskType.task_type_key = taskTypeDetail.fk_task_type_key
```

### 3.4 onFrom — with comparison operator

```java
jooq.addInnerJoin(RequestEntity.class, "r")
        .onFrom("t", "fkRequestId", Op.EQUAl,       "id")
        .onFrom("t", "amount",      Op.GREATER_THAN, "minAmount")
        .andOn("status", Op.EQUAl, "A")
    .done()
```

Supported operators in `onFrom`: `EQUAl`, `NOT_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL_TO`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL_TO`.

### 3.5 Generated table JOIN (type-safe)

```java
JooqQuery.from(ORDERS, "o")
    .leftJoin(USERS, "u", ORDERS.USER_ID.eq(USERS.ID))
    .execute(dsl);
```

### 3.6 SelectTable JOIN

```java
SelectTable budgetQuery = JooqQuery.from(Budget.class, "b")
    .select("b.fkAccountId", "b.budgetAmount")
    .noPagination()
    .execute(dsl);

JooqQuery.from(Flow.class, "f")
    .select("f.id", "f.amount")
    .leftJoin(budgetQuery, "b", "f.fkAccountId", "fkAccountId")
    .execute(dsl);
// → LEFT JOIN (SELECT ...) b ON f."fk_account_id" = b."fk_account_id"
```

---

## 4. WHERE — Filtering

### 4.1 Simple filter

```java
JooqQuery.from(User.class, "u")
    .filter("u.status", Op.EQUAl, "ACTIVE")
    .filter("u.age",    Op.GREATER_THAN, 18)
    .execute(dsl);
// → WHERE u.status = 'ACTIVE' AND u.age > 18
```

Null/blank values are automatically skipped — no extra null checks needed.

### 4.2 All filter operations (Op enum)

| Op | SQL | Op | SQL |
|---|---|---|---|
| `EQUAL` | `=` | `NOT_EQUAL` | `!=` |
| `LIKE` | Turkish-aware `LIKE '%val%'` | | |
| `START_WITH` | `LIKE 'val%'` | `END_WITH` | `LIKE '%val'` |
| `LIKE_IGNORE_CASE` | Turkish-aware `LIKE '%val%'` | | |
| `START_WITH_IGNORE_CASE` | `LIKE 'val%'` | `END_WITH_IGNORE_CASE` | `LIKE '%val'` |
| `GREATER_THAN` | `>` | `LESS_THAN` | `<` |
| `GREATER_THAN_OR_EQUAL_TO` | `>=` | `LESS_THAN_OR_EQUAL_TO` | `<=` |
| `IN` | `IN (...)` | `NOT_IN` | `NOT IN (...)` |
| `BETWEEN` | `BETWEEN a AND b` | | |
| `IS_EMPTY` | `IS NULL` | `IS_NOT_EMPTY` | `IS NOT NULL` |
| `REGEXP` | `~ 'pattern'` | `NOT_REGEXP` | `!~ 'pattern'` |

> **Note — `EQUAl` → `EQUAL`:** the original constant `EQUAl` contains a typo. `Op.EQUAL`
> was added as the correctly-named equivalent; both behave identically (including the
> automatic `IN` conversion for Collection values). Old code keeps working — prefer
> `EQUAL` in new code. `ROUND` comparison operations (`EQUAL_ROUND_0..4` etc.) are
> listed in section 12.

### 4.3 Direct jOOQ Condition

```java
.filter(DSL.field("u.age").gt(18).and(DSL.field("u.status").eq("ACTIVE")))
```

### 4.4 fieldFilter — field-to-field comparison

```java
JooqQuery.from(Task.class, "t")
    .leftJoin(Flow.class, "f", "fkTaskId", "fkTaskId")
    .fieldFilter("t.fkTaskId",   Op.EQUAl,        "f.fkTaskId")
    .fieldFilter("t.totalPrice", Op.GREATER_THAN,  "f.totalPrice")
    .execute(dsl);
// → WHERE t.fk_task_id = f.fk_task_id AND t.total_price > f.total_price
```

### 4.5 globalFilter — with Filters builder

```java
Filters filters = Filters.of("u.status",    Op.EQUAl,        "ACTIVE")
                         .and("u.age",       Op.GREATER_THAN, 18)
                         .and("u.firstName", Op.LIKE,         "Ali");

JooqQuery.from(User.class, "u").globalFilter(filters).execute(dsl);
```

### 4.6 globalFilter — with Map (from REST requests)

```java
Map<String, Object> params = Map.of(
    "equal",        "ACTIVE",
    "greaterThan",  18
);
JooqQuery.from(User.class, "u")
    .globalFilter("u.status", params)
    .execute(dsl);
```

### 4.7 OR group filters

```java
JooqQuery.from(User.class, "u")
    .orGroup("g1")
        .or("u.status", Op.EQUAl, "ACTIVE")
        .or("u.status", Op.EQUAl, "PENDING")
    .endGroup()
    .execute(dsl);
// → WHERE (u.status = 'ACTIVE' OR u.status = 'PENDING')
```

**Complex OR/AND grouping:**
```java
JooqQuery.from(User.class, "u")
    .orGroup("g1")
        .or("u.type", Op.EQUAl, "ADMIN")
        .andBranch("b1")
            .add("u.type",   Op.EQUAl, "USER")
            .add("u.status", Op.EQUAl, "ACTIVE")
        .end()
    .endGroup()
    .execute(dsl);
// → WHERE (u.type = 'ADMIN' OR (u.type = 'USER' AND u.status = 'ACTIVE'))
```

---

## 5. IN (SELECT ...) — SubQueryIn

### 5.1 Single field

```java
JooqQuery.from(User.class, "u")
    .inSubQuery("u.id",
        SubQueryIn.from(Order.class, "o")
            .select("o.userId")
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);
// → WHERE u.id IN (SELECT o.user_id FROM orders o WHERE status = 'PAID')
```

### 5.2 NOT IN

```java
.notInSubQuery("u.id", SubQueryIn.from(Blacklist.class, "bl").select("bl.userId"))
```

### 5.3 Composite IN (multiple fields)

```java
.inSubQuery(new String[]{"u.firstName", "u.lastName"},
    SubQueryIn.from(Blacklist.class, "bl")
        .select("bl.firstName", "bl.lastName")
)
// → WHERE (u.first_name, u.last_name) IN (SELECT bl.first_name, bl.last_name FROM blacklist bl)
```

---

## 6. EXISTS / NOT EXISTS

### 6.1 Simple EXISTS

```java
JooqQuery.from(User.class, "u")
    .exists(
        ExistsSpec.of(Order.class, "o")
            .joinField("userId", "u", "id")
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);
// → WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.status = 'PAID')
```

### 6.2 NOT EXISTS

```java
.notExists(ExistsSpec.of(Order.class, "o").joinField("userId", "u", "id"))
```

### 6.3 EXISTS with OR groups

```java
ExistsSpec.of(Order.class, "o")
    .joinField("userId", "u", "id")
    .orGroup("g1")
        .or("status", Op.EQUAl, "PAID")
        .or("status", Op.EQUAl, "SHIPPED")
    .endGroup()
```

---

## 7. GROUP BY + Aggregates

### 7.1 Simple aggregate

```java
JooqQuery.from(Order.class, "o")
    .select("o.customerId")
    .groupBy("o.customerId")
    .agg(Agg.SUM,   "o.totalPrice", "totalSum",   2,    "DESC")
    .agg(Agg.COUNT, "o.id",         "orderCount", null, null)
    .agg(Agg.AVG,   "o.totalPrice", "avgPrice",   2,    null)
    .execute(dsl);
// → SELECT o.customer_id,
//          ROUND(SUM(o.total_price),2) AS totalSum,
//          COUNT(o.id)                 AS orderCount,
//          ROUND(AVG(o.total_price),2) AS avgPrice
//   FROM orders o GROUP BY o.customer_id
//   ORDER BY ROUND(SUM(o.total_price),2) DESC
```

### 7.2 Arithmetic aggregate — SUM(price * qty)

```java
JooqQuery.from(Order.class, "o")
    .groupBy("o.customerId")
    .aggWithMath(Agg.SUM, "o.price", MathOp.MULTIPLY, "o.quantity", "revenue", 2)
    .execute(dsl);
// → ROUND(SUM(o.price * o.quantity), 2) AS revenue
```

### 7.3 ComputedField aggregate — SUM((price * qty) - discount)

```java
JooqQuery.from(Order.class, "o")
    .groupBy("o.customerId")
    .aggOnComputed(
        Agg.SUM,
        ComputedField.of("o.price").multiply("o.quantity").subtract("o.discount"),
        "netRevenue", 2
    )
    .execute(dsl);
// → ROUND(SUM((o.price * o.quantity) - o.discount), 2) AS netRevenue
```

### 7.3.1 Subtracting two SUM expressions — SUM(exprA) - SUM(exprB)

`SUM` is a linear operation: `SUM(a) - SUM(b) = SUM(a - b)`. Instead of running two
separate aggregates and subtracting them afterwards, build each side as a `ComputedField`
and subtract inside a single aggregate call — one SELECT column, no extra query layer:

```java
ComputedField inSide = ComputedField.sumOf(
        ComputedField.expr("t.totalIn"),
        ComputedField.expr("t.expense").multiply("t.actionIn")
);

ComputedField outSide = ComputedField.sumOf(
        ComputedField.expr("t.totalOut"),
        ComputedField.expr("t.expense").multiply("t.actionOut")
);

JooqQuery.from(CashFlow.class, "t")
    .groupBy("t.actionType")
    .aggOnComputed(Agg.SUM, inSide.subtract(outSide), "netAmount")
    .execute(dsl);
// → SUM((t.totalIn + t.expense * t.actionIn) - (t.totalOut + t.expense * t.actionOut)) AS netAmount
```

> Extracting each side into a named `ComputedField` variable (`inSide` / `outSide`) keeps
> the call site readable. For a shorter form see 7.3.2 below.

### 7.3.2 addSumExpr / AggExpr — readable aggregate chain

A cleaner equivalent of the pattern above. Since `(a + b) - (c + d) = a + b - c - d`,
nested `sumOf(...)` groups are unnecessary — write the terms as a flat chain:

```java
// SUM( totalIn + expense*actionIn - totalOut - expense*actionOut ) AS netAmount
manager.addSumExpr("netAmount", e -> e
        .plus("t.totalIn")
        .plus("t.expense", "t.actionIn")     // + (f1 * f2)
        .minus("t.totalOut")
        .minus("t.expense", "t.actionOut")); // - (f1 * f2)
```

`AggExpr` methods: `plus(field)`, `plus(f1, f2)` (adds the product `f1 * f2`),
`plus(f1, MathOp, f2)` (any operation — `DIVIDE` gets automatic `NULLIF` zero-division
protection), `plus(ComputedField)` for complex terms, and the same four overloads for
`minus`. The chain must start with `plus(...)`.

```java
// SUM( totalPrice/qty - discount ) AS avgNet
manager.addSumExpr("avgNet", e -> e
        .plus("t.totalPrice", MathOp.DIVIDE, "t.qty")   // + (total_price / NULLIF(qty, 0))
        .minus("t.discount"));
```

Other forms:

```java
manager.addAggExpr(Agg.AVG, "avgNet", e -> e.plus("t.income").minus("t.expense"));
manager.addAggExpr(Agg.SUM, "totalPrice", 2, e -> e.plus("t.price", "t.qty")); // with ROUND

// Directly on JooqQuery:
JooqQuery.from(CashFlow.class, "t")
    .groupBy("t.actionType")
    .sumExpr("netAmount", e -> e.plus("t.totalIn").minus("t.totalOut"))
    .execute(dsl);
```

> `addSumExpr` delegates to `addAggFunctionOnComputed(Agg.SUM, ...)` internally —
> the generated SQL is identical to an equivalent flat `ComputedField` chain.

### 7.4 AggregateBuilder — fluent API

`AggregateBuilder` is attached through `SelectQueryBuilder.aggregate(...)`, not through
`JooqQuery`. Note that `SelectQueryBuilder`'s terminal method is `.build(dsl)`, not
`.execute(dsl)`:

```java
SelectQueryBuilder.from(Order.class, "o")
    .select("o.customerId")
    .aggregate(
        AggregateBuilder.<Order>groupBy("o.status", "o.customerId")
            .sum("o.totalPrice").as("totalRevenue").done()
            .count("o.id").as("orderCount").done()
            .avg("o.totalPrice").as("avgOrder").done()
            .min("o.createdAt").as("firstOrder").done()
            .max("o.createdAt").as("lastOrder").orderDesc().done()
    )
    .build(dsl);
```

### 7.4.1 AggregateBuilder — conditional aggregate functions (sumIf / countIf)

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(CASE WHEN status='PAID' THEN amount ELSE 0 END)
    .sumIf("t.status", "PAID", "t.amount", 0).as("paidTotal").done()
    // Conditional count via SUM
    .sumIf("t.type", "OUT", 1, 0).as("outCount").done()
    // COUNT(CASE WHEN status='PAID' THEN 1 END)
    .countIf("t.status", "PAID").as("paidCount").done()
    .avgIf("t.status", "PAID", "t.amount", 0).as("avgPaid").done()
    .maxIf("t.type",   "SALE", "t.amount", 0).as("maxSale").done()
    .minIf("t.type",   "SALE", "t.amount", 0).as("minSale").done()
```

### 7.4.2 AggStep — IfExpr as math operand

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(purchaseExpense * CASE WHEN actionType='in' THEN 1 ELSE 0 END)
    .sum("t.purchaseExpense")
        .multiplyIf("t.actionType", "in", 1, 0)
        .as("expense").done()
    // SUM(revenue - CASE WHEN type='REFUND' THEN amount ELSE 0 END)
    .sum("t.revenue")
        .subtractIf("t.type", "REFUND", "t.amount", 0)
        .as("netRevenue").done()
    // SUM(base + CASE WHEN type='BONUS' THEN bonusAmount ELSE 0 END)
    .sum("t.base")
        .addIf("t.type", "BONUS", "t.bonusAmount", 0)
        .as("total").done()
```

### 7.5 HAVING inside AggregateBuilder

```java
SelectQueryBuilder.from(Order.class, "o")
    .aggregate(
        AggregateBuilder.<Order>groupBy("o.customerId")
            .sum("o.totalPrice").round(2).as("totalRevenue")
                .having(Op.GREATER_THAN, 10000)
            .done()
    )
    .build(dsl);
// → HAVING ROUND(SUM(o.total_price),2) > 10000
```

---

## 8. HAVING

```java
JooqQuery.from(Order.class, "o")
    .groupBy("o.customerId")
    .agg(Agg.SUM,   "o.totalPrice", "totalSum", 2, null)
    .agg(Agg.COUNT, "o.id",         "cnt",      null, null)
    // HAVING via the agg alias
    .havingFilter("totalSum", Map.of("greaterThan", "1000"))
    .havingFilter("cnt",      Map.of("between",     "5,50"))
    // HAVING via a direct field
    .havingFilter("o.status", Op.NOT_EQUAL, "CANCELLED")
    .execute(dsl);
```

---

## 9. CASE WHEN

### 9.1 Simple caseWhen

```java
JooqQuery.from(User.class, "u")
    .caseWhen("u.status", "ACTIVE", "Active User", "Inactive User", "statusLabel")
    .execute(dsl);
// → CASE WHEN u.status = 'ACTIVE' THEN 'Active User' ELSE 'Inactive User' END AS statusLabel
```

### 9.2 CaseBuilder — multiple conditions

```java
JooqQuery.from(Order.class, "o")
    .caseColumn(
        CaseBuilder.of(Order.class, "o")
            .when("status", "PAID",      "Completed")
            .when("status", "PENDING",   "Awaiting Payment")
            .when("status", "CANCELLED", "Cancelled")
            .orElse("Unknown")
            .as("statusLabel")
    )
    .execute(dsl);
```

---

## 10. ORDER BY

```java
JooqQuery.from(User.class, "u")
    .orderBy("u.createdAt", "desc")
    .orderBy("u.firstName", "asc")
    .execute(dsl);
```

**String expression:**
```java
.orderBy("u.createdAt desc, u.firstName asc")
```

**Map:**
```java
.orderBy(Map.of("u.createdAt", "desc", "u.totalPrice", "asc"))
```

---

## 11. Pagination

```java
SelectTable result = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .page(0, 20)       // page number (0-based), page size
    .execute(dsl);

long totalCount              = result.getTotalCount();
int  pageCount               = result.getPageCount();
List<Map<String,Object>> rows = result.getResult();
```

| Method | Data | COUNT | rowCount |
|---|---|---|---|
| `page(p, s)` | ✓ LIMIT/OFFSET | ✓ | total rows |
| `noPagination()` | ✓ all rows | ✗ | 0 |
| `withCount()` | ✓ all rows | ✓ | total rows |
| `page(p,s).skipCount()` | ✓ LIMIT/OFFSET | ✗ | -1 |
| `onlyCount()` | ✗ empty | ✓ | total rows |

---

## 12. ROUND filter operations

Apply `ROUND` in `WHERE` without using `selectRound()`:

```java
.filter("o.totalPrice", Op.EQUAL_ROUND_2,                "99.99")
.filter("o.totalPrice", Op.GREATER_THAN_ROUND_2,          "100")
.filter("o.totalPrice", Op.LESS_THAN_OR_EQUAL_TO_ROUND_2, "500")
// → WHERE ROUND(o."total_price", 2) = 99.99
// → WHERE ROUND(o."total_price", 2) > 100
// → WHERE ROUND(o."total_price", 2) <= 500
```

Available scales: `_ROUND_0`, `_ROUND_1`, `_ROUND_2`, `_ROUND_3`, `_ROUND_4`.

---

## 13. FilterOperationConstants — String constants

Use instead of hardcoded strings in `globalFilter(Map)`:

```java
import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;

.globalFilter("o.amount",  Map.of(GREATER_THAN, "100", LESS_THAN, "500"))
.globalFilter("u.status",  Map.of(EQUAl, "ACTIVE"))
.globalFilter("u.name",    Map.of(LIKE, name))
.globalFilter("u.name",    Map.of(LIKE_IGNORE_CASE, "Alice"))
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

Multiple WHERE conditions:
```java
new UpdateQueryBuilder<>(Order.class, dsl)
    .set("status", "CANCELLED")
    .where(Spec.eq("status", "PENDING")
               .and(Spec.lt("createdAt", LocalDateTime.now().minusDays(30))))
    .execute();
```

SQL debug (without execution):
```java
String sql = new UpdateQueryBuilder<>(User.class, dsl)
    .set("status", "INACTIVE")
    .where(Spec.eq("id", 1L))
    .toSQL();
```

> UPDATE without WHERE throws `IllegalStateException`.

---

## 15. INSERT — InsertQueryBuilder

### Simple INSERT

```java
int rows = new InsertQueryBuilder<>(User.class, dsl)
    .value("firstName", "Alice")
    .value("email",     "alice@example.com")
    .value("status",    "ACTIVE")
    .execute();
```

### INSERT returning ID

```java
Long newId = new InsertQueryBuilder<>(User.class, dsl)
    .value("firstName", "Alice")
    .value("email",     "alice@example.com")
    .returning("id", Long.class);
```

### Batch INSERT (multiple rows)

```java
new InsertQueryBuilder<>(User.class, dsl)
    .row(Map.of("firstName", "Alice", "email", "alice@example.com"))
    .row(Map.of("firstName", "Bob",   "email", "bob@example.com"))
    .executeBatch();
```

### ON CONFLICT / ON DUPLICATE KEY UPDATE

```java
new InsertQueryBuilder<>(User.class, dsl)
    .value("email",  "alice@example.com")
    .value("status", "ACTIVE")
    .onConflict("email")
    .doUpdate("status", "ACTIVE")
    .execute();
// → INSERT INTO users ... ON CONFLICT (email) DO UPDATE SET status = 'ACTIVE'
```

---

## 16. JooqQuery — fetch methods

### fetchMaps — `List<Map<String,Object>>`

```java
List<Map<String, Object>> rows = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .filter("u.status", Op.EQUAl, "ACTIVE")
    .noPagination()
    .execute(dsl)
    .getResult();
```

### fetchInto — auto-mapped DTO

```java
List<UserDto> users = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .noPagination()
    .execute(dsl)
    .into(UserDto.class);
```

---

## 17. Generated mode — jOOQ generated tables

Type-safe queries using jOOQ code-generated table classes:

```java
import static com.example.jooq.Tables.*;
import static org.jooq.impl.DSL.*;

JooqQuery.from(USERS, "u")
    .select(USERS.ID.as("id"), USERS.FIRST_NAME.as("firstName"))
    .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
    .filter(USERS.STATUS.eq("ACTIVE"))
    .execute(dsl);
```

---

## 18. Full example — complex query

```java
SelectTable result = JooqQuery.from(Order.class, "o")
    .select("o.id", "o.status")
    .selectAs("o.createdAt", "orderDate")
    .castDateTime("o.createdAt", "YYYY-MM-DD", "createdDateStr")
    .castBigDecimal("o.totalPrice", "priceNum")
    .computedColumn(
        ComputedField.of("o.totalPrice")
            .subtract("o.discount")
            .castToBigDecimal()
            .as("netAmount")
    )
    .leftJoin(User.class, "u", "userId", "id")
    .select("u.firstName", "u.email")
    .leftJoin(Product.class, "p", "productId", "id")
    .select("p.productName")
    .filter("o.status",      Op.IN,           List.of("PAID", "SHIPPED"))
    .filter("o.totalPrice",  Op.GREATER_THAN, 100)
    .filter("u.status",      Op.EQUAl,        "ACTIVE")
    .exists(
        ExistsSpec.of(Payment.class, "pay")
            .joinField("orderId", "o", "id")
            .filter("confirmed", Op.EQUAl, true)
    )
    .orderBy("o.createdAt desc, o.totalPrice asc")
    .page(0, 20)
    .execute(dsl);
```

---

## 19. Quick Reference

### JooqQuery methods

| Method | Description |
|---|---|
| `from(Class, alias)` | Start entity mode |
| `from(Table, alias)` | Start generated mode |
| `from(SelectTable, alias)` | Start derived table mode |
| `select(cols...)` | SELECT columns |
| `select(List)` | SELECT — dynamic list |
| `selectAs(field, alias)` | SELECT with custom alias |
| `selectAll()` | All main entity columns (camelCase) |
| `selectRound(field, scale, alias)` | ROUND column + auto filter |
| `distinct()` | SELECT DISTINCT |
| `computedColumn(ComputedField)` | Arithmetic expression column |
| `compute(field).add/subtract/multiply/divide(field).as(alias)` | Inline fluent expression |
| `ComputedField.ifExpr(cond,eq,then,else).as(alias)` | `CASE WHEN` as a column |
| `ComputedField.of(f).multiplyIf(cond,eq,t,e).as(alias)` | `f * CASE WHEN ...` |
| `ComputedField.of(f).addIf(cond,eq,t,e).as(alias)` | `f + CASE WHEN ...` |
| `ComputedField.of(f).subtractIf(cond,eq,t,e).as(alias)` | `f - CASE WHEN ...` |
| `ComputedField.of(f).divideIf(cond,eq,t,e).as(alias)` | `f / CASE WHEN ...` |
| `ComputedField.coalesce(fields...).orElse(def).as(alias)` | `COALESCE` as a column |
| `coalesce(alias, def, fields...)` | COALESCE column |
| `castString(field, alias)` | `CAST(field AS VARCHAR)` |
| `castLong(field, alias)` | `CAST(field AS BIGINT)` |
| `castInteger(field, alias)` | `CAST(field AS INTEGER)` |
| `castBigDecimal(field, alias)` | `CAST(field AS NUMERIC)` |
| `castDateTime(field, pattern, alias)` | Date format — all databases |
| `castColumn(field, DataType, alias)` | Cast with any SQLDataType |
| `subSelect(SubSelectBuilder)` | Scalar subquery column |
| `caseWhen(...)` | CASE WHEN column |
| `leftJoin(entity, alias, from, to)` | Simple LEFT JOIN |
| `leftJoin(entity, alias).on(...).done()` | Builder LEFT JOIN |
| `filter(field, Op, value)` | WHERE filter (null-safe) |
| `filter(Condition)` | Raw jOOQ condition |
| `fieldFilter(left, Op, right)` | Field-to-field comparison |
| `globalFilter(Filters)` | Filters builder |
| `globalFilter(field, Map)` | Multi-op map filter |
| `orGroup(alias).or(...).andBranch(...).end().done()` | Complex OR/AND grouping |
| `inSubQuery(field, SubQueryIn)` | WHERE field IN (SELECT ...) |
| `notInSubQuery(field, SubQueryIn)` | WHERE field NOT IN (SELECT ...) |
| `exists(ExistsSpec)` | WHERE EXISTS |
| `notExists(ExistsSpec)` | WHERE NOT EXISTS |
| `groupBy(fields...)` | GROUP BY |
| `agg(Agg, field, alias, round, dir)` | Simple aggregate function |
| `aggWithMath(Agg, field, MathOp, field, alias, round)` | `SUM(f1 op f2)` — 2-field arithmetic aggregate |
| `aggOnComputed(Agg, ComputedField, alias, round)` | Aggregate over a multi-field/nested expression — includes `SUM(exprA - exprB)` |
| `sumExpr(alias, e -> e.plus(...).minus(...))` | Readable SUM expression chain (AggExpr) — see 7.3.2 |
| `aggExpr(Agg, alias, e -> ...)` | AggExpr with any aggregate function |
| `havingFilter(field, Op, value)` | HAVING filter — direct field |
| `havingFilter(field, Map)` | HAVING filter — multi-op map |
| `orderBy(field, dir)` | ORDER BY |
| `orderBy(expression)` | ORDER BY string: `"t.field desc"` |
| `page(page, size)` | Pagination (0-based) |
| `noPagination()` | All rows, no COUNT |
| `withCount()` | All rows + COUNT |
| `skipCount()` | Pagination without COUNT |
| `onlyCount()` | COUNT only, no data |
| `execute(dsl)` | Execute and return SelectTable |

### ComputedField methods

| Method | Description |
|---|---|
| `ComputedField.of(field)` | Start with a column |
| `ComputedField.expr(field)` | Parenthesized sub-expression |
| `ComputedField.sumOf(parts...)` | Sum of multiple expressions |
| `ComputedField.ifExpr(cond,eq,then,else)` | Start with `CASE WHEN` |
| `ComputedField.coalesce(fields...)` | Start with `COALESCE` |
| `.add(field)` / `.add(expr)` | `+ field` / `+ (expr)` |
| `.subtract(field)` | `- field` |
| `.multiply(field)` | `* field` |
| `.divide(field)` | `/ field` |
| `.addIf(cond, eq, then, else)` | `+ CASE WHEN ...` |
| `.subtractIf(cond, eq, then, else)` | `- CASE WHEN ...` |
| `.multiplyIf(cond, eq, then, else)` | `* CASE WHEN ...` |
| `.divideIf(cond, eq, then, else)` | `/ CASE WHEN ...` |
| `.where(field, Op, value)` | Wrap in `CASE WHEN ... THEN expr ELSE NULL` |
| `.castToString()` | `CAST(expr AS VARCHAR)` |
| `.castToLong()` | `CAST(expr AS BIGINT)` |
| `.castToInteger()` | `CAST(expr AS INTEGER)` |
| `.castToBigDecimal()` | `CAST(expr AS NUMERIC)` |
| `.castToDateTime(pattern)` | Date format — all databases |
| `.castTo(DataType)` | Any SQLDataType |
| `.as(alias)` | Required — set output alias |

### AggregateBuilder / AggStep methods (IfExpr)

> **Entry point:** a full `AggregateBuilder` chain (`.sum(f).as(alias).having(...).done()...`)
> is attached via `SelectQueryBuilder.from(...).aggregate(AggregateBuilder...).build(dsl)` —
> not through `JooqQuery`. `SelectQueryBuilder` ends with `.build(dsl)`, `JooqQuery` ends
> with `.execute(dsl)`. See §7.4/§7.5.

| Method | Generated SQL |
|---|---|
| `.sumIf(cond, eq, then, else)` | `SUM(CASE WHEN cond=eq THEN then ELSE else END)` |
| `.countIf(cond, eq)` | `COUNT(CASE WHEN cond=eq THEN 1 END)` |
| `.avgIf(cond, eq, then, else)` | `AVG(CASE WHEN ...)` |
| `.maxIf(cond, eq, then, else)` | `MAX(CASE WHEN ...)` |
| `.minIf(cond, eq, then, else)` | `MIN(CASE WHEN ...)` |
| `.sum(f).multiplyIf(cond,eq,t,e)` | `SUM(f * CASE WHEN ...)` |
| `.sum(f).addIf(cond,eq,t,e)` | `SUM(f + CASE WHEN ...)` |
| `.sum(f).subtractIf(cond,eq,t,e)` | `SUM(f - CASE WHEN ...)` |
| `.sum(f).divideIf(cond,eq,t,e)` | `SUM(f / CASE WHEN ...)` |

### castDateTime pattern reference

| Element | PostgreSQL/Oracle | MySQL output | MSSQL output |
|---|---|---|---|
| 4-digit year | `YYYY` | `%Y` | `yyyy` |
| 2-digit year | `YY` | `%y` | `yy` |
| Month (01-12) | `MM` | `%m` | `MM` |
| Day (01-31) | `DD` | `%d` | `dd` |
| Hour 24h | `HH24` | `%H` | `HH` |
| Hour 12h | `HH12` | `%h` | `hh` |
| Minute | `MI` | `%i` | `mm` |
| Second | `SS` | `%s` | `ss` |
| Short month name | `MON` | `%b` | `MMM` |
| Full month name | `MONTH` | `%M` | `MMMM` |
| Full day name | `DAY` | `%W` | `dddd` |
| AM/PM | `AM` | `%p` | `tt` |

> **Note:** Always write your pattern in PostgreSQL/Oracle syntax. The library converts it automatically for MySQL and SQL Server based on `dsl.dialect()`.

### UpdateQueryBuilder methods

| Method | Description |
|---|---|
| `set(field, value)` | Set a column value |
| `where(Specification)` | WHERE condition (required) |
| `execute()` | Execute UPDATE, returns affected rows |
| `toSQL()` | Return SQL string without executing |

### InsertQueryBuilder methods

| Method | Description |
|---|---|
| `value(field, value)` | Set a column value |
| `row(Map<String,Object>)` | Add a row for batch insert |
| `onConflict(field...)` | ON CONFLICT clause |
| `doUpdate(field, value)` | DO UPDATE SET on conflict |
| `execute()` | Execute INSERT |
| `returning(field, type)` | Execute and return generated key |
| `executeBatch()` | Execute batch insert |
