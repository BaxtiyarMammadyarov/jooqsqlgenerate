# jooq-sql-generate — İstifadəçi Təlimatı

## Maven / Gradle asılılığı

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

### 2.2 selectAll — əsas entity-nin bütün sütunları

`selectAll()` əsas entity-nin **bütün sütunlarını** camelCase alias ilə SELECT-ə əlavə edir.
`SELECT *`-dan fərqi: yalnız əsas entity-nin sütunları seçilir, JOIN cədvəllərinin sütunları daxil olmur.

```java
JooqQuery.from(User.class, "u")
    .selectAll()
    .execute(dsl);
// → SELECT u."id"         AS "id",
//          u."first_name" AS "firstName",
//          u."last_name"  AS "lastName",
//          u."email"      AS "email",
//          u."status"     AS "status"
//   FROM users u
```

JOIN olan hallarda əlavə sütunları `.select()` ilə birlikdə istifadə et:

```java
JooqQuery.from(User.class, "u")
    .selectAll()                        // u-nun bütün sütunları
    .select("o.totalAmount", "o.status") // o-dan yalnız bunlar
    .leftJoin(Order.class, "o").on("id").equalsField("userId").done()
    .execute(dsl);
```

> **Qeyd:** `selectAll()` + `select()` birlikdə istifadə edildikdə dublikat sütunlar avtomatik atlanır.

### 2.3 selectAs — özəl alias

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

### 2.4 DISTINCT

```java
JooqQuery.from(Order.class, "o")
    .select("o.customerId", "o.status")
    .distinct()
    .execute(dsl);
// → SELECT DISTINCT o."customer_id", o."status" FROM orders o
```

### 2.5 computedColumn — riyazi ifadə sətunu

İki sahə arasında `+`, `-`, `*`, `/`:
```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.price", "o.qty")
    .computedColumn("lineTotal", "o", MathOp.MULTIPLY, "price", "o", "qty")
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

**JooqManager — fluent zəncir (`addComputedColumn`):**
```java
manager.addComputedColumn("t.price")
       .add("t.tax")
       .subtract("t.discount")
       .multiply("t.qty")
       .as("netTotal")
// → ((price + tax) - discount) * qty AS netTotal
```

### 2.5a NullDefault — LEFT JOIN null idarəetməsi

LEFT JOIN edilmiş cədvəldə uyğun sətir olmadıqda sahə `NULL` olur, riyazi ifadə bütünlüklə `NULL` qaytarır. `NullDefault` ilə bu davranışı idarə etmək olar.

**2-sahəli sadə forma + NullDefault:**
```java
// qty LEFT JOIN-dən null gəldikdə → COALESCE(price,0) * COALESCE(qty,0) = 0
JooqQuery.from(Order.class, "o")
    .leftJoin(OrderDetail.class, "d", "id", "fkOrderId")
    .computedColumn("lineTotal", "o", MathOp.MULTIPLY, "price", "d", "qty",
                    NullDefault.ZERO)
    .execute(dsl);
```

**`ComputedField.withNullDefault` — bütün zəncirə:**
```java
// Bütün sahələr COALESCE(field, 0) ilə bükülür
ComputedField.of("o.price")
    .multiply("o.qty")
    .subtract("o.discount")
    .withNullDefault(NullDefault.ZERO)
    .as("net")
// → COALESCE(price,0) * COALESCE(qty,0) - COALESCE(discount,0)
```

**Per-step `*NullAs` metodları — IF məntiq, dəqiq nəzarət:**
```java
// Hər addıma öz default dəyərini ver
ComputedField.of("o.price")
    .multiplyNullAs("d.qty",      0)  // qty null → 0  (ədəd yoxdur)
    .subtractNullAs("d.discount", 0)  // discount null → 0  (endirim yoxdur)
    .as("net")

// DIVIDE — məxrəc null olduqda NULLIF(COALESCE(field,1), 0) avtomatik:
ComputedField.of("t.revenue")
    .divideNullAs("t.unitCount", 1)   // unitCount null → 1 (sıfıra bölünmə yoxdur)
    .as("avgRevenue")
```

| `NullDefault` | Dəyər | Nə zaman istifadə et |
|---|---|---|
| `ZERO` | 0 | ADD, SUBTRACT, MULTIPLY — yoxluq = 0 |
| `ONE` | 1 | DIVIDE məxrəci — yoxluq = 1 (sıfıra bölünmə yoxdur) |
| `NONE` | tətbiq edilmir | Default davranış, DB null qaytarır |

> **Per-step `*NullAs` metodları `withNullDefault`-dan üstündür** — eyni sahəyə hər ikisi
> tətbiq olunsa per-step qalib gəlir.

### 2.6 compute — mötərizəli qrup ifadəsi

`compute()` / `addComputedColumn()` metodlarında boş `add()`, `subtract()`, `multiply()`, `divide()` çağırışı mötərizəli alt-ifadə (qrup) açır. Qrup `.of("field")` ilə başlayır, `.done()` ilə bağlanır və əsas zəncirə qayıdır.

**JooqQuery ilə (`compute`):**
```java
// (total_Price_In - total_Price_Out) * rate - (purchase_Expense * count)
JooqQuery.from(WarehouseFlow.class, "wf")
    .compute("wf.total_Price_In")
        .subtract("wf.total_Price_Out")          // sadə sahə
        .multiply("wf.rate")                      // sadə sahə
        .subtract().of("wf.purchase_Expense")     // ← mötərizə açılır
            .multiply("wf.count")
            .done()                               // ← mötərizə bağlanır
        .as("profit")                             // commit + SelectQueryBuilder-ə qayıdır
    .execute(dsl);
// → ((total_Price_In - total_Price_Out) * rate) - (purchase_Expense * count) AS profit
```

Bütün dörd əməliyyat eyni şəkildə işləyir:
```java
.compute("o.base")
    .add().of("o.tax").multiply("o.qty").done()           // + (tax * qty)
    .subtract().of("o.discount").multiply("o.qty").done() // - (discount * qty)
    .multiply().of("o.price").add("o.vat").done()         // * (price + vat)
    .divide().of("o.total").subtract("o.refund").done()   // / (total - refund)
    .as("result")
```

**JooqManager ilə (`addComputedColumn`):**
```java
jooq.addComputedColumn("t.total_Price_In")
    .subtract("t.total_Price_Out")
    .multiply("t.rate")
    .subtract().of("t.purchase_Expense").multiply("t.count").done()
    .as("profit")
```

> **Qeyd:** `.subtract("field")` — adi çıxma (sadə sahə); `.subtract()` boş çağırış — mötərizə açır, `GroupStep` qaytarır. `.done()` mötərizəni bağlayır, əsas zəncirə qayıdır. `.as("alias")` isə bütün ifadəni tamamlayır.

### 2.7 COALESCE

```java
JooqQuery.from(User.class, "u")
    .coalesce("displayName", "Naməlum", "u.firstName", "u.lastName")
    .execute(dsl);
// → SELECT COALESCE(u."first_name", u."last_name", 'Naməlum') AS "displayName"
```

**v1.1.50-dən — dinamik siyahı variantları** (JooqManager, JooqQuery və SelectQueryBuilder-də):

```java
// List<String> ilə
manager.addCoalesceColumn("displayName", "Naməlum", List.of("u.nickname", "u.firstName"));

// Collection<ConcatItem> ilə — yalnız ConcatItem.field(...) elementləri;
// sabit dəyər üçün defaultValue istifadə edin (literal dəstəklənmir)
List<ConcatItem> cols = new ArrayList<>();
cols.add(ConcatItem.field("u.nickname"));
cols.add(ConcatItem.field("u.firstName"));
manager.addCoalesceColumn("displayName", "Naməlum", cols);
```

### 2.8 selectRound — yuvarlama sütunu

`selectRound` ilə həmin alias-a `filter()` tətbiq edildikdə avtomatik `WHERE ROUND(field, scale)` yaranır:
```java
JooqQuery.from(Order.class, "o")
    .selectRound("o.totalPrice", 2, "roundedTotal")
    .filter("roundedTotal", Op.GREATER_THAN, 100)
    .execute(dsl);
// → SELECT ROUND(o."total_price", 2) AS "roundedTotal"
//   WHERE  ROUND(o."total_price", 2) > 100
```

### 2.9 subSelect — scalar subquery sütunu

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

### 2.10 CONCAT — sütunları birləşdir

Separator ilə sütunları birləşdirir, `null` dəyərlər boş string kimi işlənir:

```java
// Sadə — sütun adları ilə
manager.addConcatColumn("fullName", " ", "u.firstName", "u.lastName")
// → COALESCE(first_name,'') || ' ' || COALESCE(last_name,'') AS fullName

// List ilə
manager.addConcatColumn("fullName", " ", List.of("u.firstName", "u.lastName"))
```

**`ConcatItem` — literal + sütun qarışıq:**

```java
import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;

// Sabit prefiks + sütun
manager.addConcatColumn("userCode", "-", literal("USR"), field("u.id"))
// → 'USR' || '-' || COALESCE(id,'') AS userCode

// Çox qarışıq
manager.addConcatColumn("label", " ", literal("Ad:"), field("u.firstName"), field("u.lastName"))
// → 'Ad:' || ' ' || COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')
```

**v1.1.50-dən — `Collection<ConcatItem>` ilə dinamik siyahı:**

```java
List<ConcatItem> ad = new ArrayList<>();
ad.add(ConcatItem.field("t.firstName"));
ad.add(ConcatItem.literal("-"));          // sabit dəyər
ad.add(ConcatItem.field("t.lastName"));

manager.addConcatColumn("fkDataId", "", ad);
// → first_name || '-' || last_name AS fkDataId
```

> **Qeyd:** parametr tipi `List` yox, `Collection`-dur — mövcud
> `addConcatColumn(alias, sep, List<String>)` ilə type-erasure toqquşmasının qarşısını alır.
> Çağırış tərəfdə `List<ConcatItem>` olduğu kimi ötürülür.

**`ConcatItem.ifExpr` — CONCAT daxilində şərtli dəyər:**

```java
// Şərtli label CONCAT-a qoşulur
manager.addConcatColumn("statusLabel", " | ",
    field("u.name"),
    ifExpr("o.status", "PAID", "Ödənilib", "Gözlənilir"))
// → COALESCE(name,'') || ' | ' || CASE WHEN status='PAID' THEN 'Ödənilib' ELSE 'Gözlənilir' END
```

**`ConcatItem.coalesce` — CONCAT daxilində COALESCE:**

```java
// nickname → firstName → "Anonim" sırası ilə CONCAT
manager.addConcatColumn("displayName", " ",
    literal("Ad:"),
    coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim")))
// → 'Ad:' || ' ' || COALESCE(nickname, first_name, 'Anonim')
```

---

### 2.11 CAST — Tip Çevrilməsi

Sütunun SQL tipini dəyişdirmək üçün.

#### Sadə SELECT-də cast

```java
JooqQuery.from(User.class, "u")
    .castString("u.age", "ageText")          // CAST(age AS VARCHAR)
    .castInteger("u.score", "scoreInt")       // CAST(score AS INTEGER)
    .castLong("u.code", "codeLong")           // CAST(code AS BIGINT)
    .castBigDecimal("o.price", "priceNum")    // CAST(price AS NUMERIC)
    .execute(dsl);
```

#### Tarix/vaxt formatı — bütün DB-lərlə işləyir

Pattern PostgreSQL/Oracle sintaksisindədir — MySQL və MSSQL üçün avtomatik çevrilir:

```java
.castDateTime("o.createdAt", "YYYY-MM-DD", "createdDate")
// PostgreSQL/Oracle: TO_CHAR(created_at, 'YYYY-MM-DD')
// MySQL/MariaDB:     DATE_FORMAT(created_at, '%Y-%m-%d')
// SQL Server:        FORMAT(created_at, 'yyyy-MM-dd')

.castDateTime("o.orderTime", "YYYY-MM-DD HH24:MI:SS", "orderTimeStr")
.castDateTime("u.birthDate", "DD MON YYYY", "birthFormatted")
```

**Tez-tez istifadə olunan pattern-lər:**

| Pattern | Nümunə çıxış |
|---|---|
| `YYYY-MM-DD` | `2024-03-15` |
| `DD/MM/YYYY` | `15/03/2024` |
| `YYYY-MM-DD HH24:MI:SS` | `2024-03-15 14:30:00` |
| `HH24:MI` | `14:30` |
| `DD MON YYYY` | `15 Mar 2024` |
| `MONTH YYYY` | `March 2024` |

#### ComputedField zəncirinə cast

```java
// Hesablama nəticəsini NUMERIC-ə çevir
.computedColumn(
    ComputedField.of("o.price")
        .subtract("o.discount")
        .castToBigDecimal()
        .as("netPrice")
)

// Sadə sütunu STRING-ə çevir
.computedColumn(
    ComputedField.of("u.age")
        .castToString()
        .as("ageText")
)

// Tarixi formatla
.computedColumn(
    ComputedField.of("o.createdAt")
        .castToDateTime("YYYY-MM-DD")
        .as("createdDate")
)
```

#### Aşağı səviyyəli metod — istənilən DataType

```java
import org.jooq.impl.SQLDataType;

.castColumn("o.price", SQLDataType.NUMERIC.precision(10, 2), "priceFormatted")
```

#### JooqManager ilə

```java
manager.addCastStringColumn("u.age", "ageText");
manager.addCastLongColumn("u.code", "codeLong");
manager.addCastIntegerColumn("u.score", "scoreInt");
manager.addCastBigDecimalColumn("o.price", "priceNum");
manager.addCastDateTimeColumn("o.createdAt", "YYYY-MM-DD", "createdDate");
```

> **Qeyd:** `castDateTime` DB dialektini `DSLContext`-dən avtomatik alır — heç bir əlavə konfiqurasiya lazım deyil.

---

### 2.12 IfExpr — Şərtli dəyər (CASE WHEN)

`CASE WHEN field = val THEN x ELSE y END` ifadəsini **ComputedField**, **AggregateBuilder** və **ConcatItem** daxilindən istifadə etməyə imkan verir.

#### ComputedField — başlanğıc nöqtəsi kimi

```java
// Sadə şərtli sütun
ComputedField.ifExpr("o.status", "PAID", 1, 0)
    .as("isPaid")
// → CASE WHEN status='PAID' THEN 1 ELSE 0 END AS isPaid

// Sütun referansı ilə — PAID olarsa amount, əks halda 0
ComputedField.ifExpr("o.status", "PAID", "o.amount", 0)
    .add("o.tax")
    .as("result")
// → (CASE WHEN status='PAID' THEN amount ELSE 0 END) + tax AS result
```

#### ComputedField — zəncirdə IfExpr operandı

`multiplyIf`, `addIf`, `subtractIf`, `divideIf` — CASE WHEN ifadəsini birbaşa riyazi əməliyyat operandı kimi istifadə edir:

```java
// purchaseExpense * CASE WHEN actionType='medaxil' THEN 1 ELSE 0 END + averageCostIn
ComputedField.of("t.purchaseExpense")
    .multiplyIf("t.actionType", "medaxil", 1, 0)
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

> **Qısa vs uzun sintaksis** — eyni nəticəni verir:
> ```java
> // Qısa (tövsiyə edilir)
> ComputedField.of("t.price").multiplyIf("t.type", "SALE", 1, 0).as("salePrice")
>
> // Uzun (nested ComputedField)
> ComputedField.of("t.price").multiply(ComputedField.ifExpr("t.type", "SALE", 1, 0)).as("salePrice")
> ```

#### AggregateBuilder — şərtli aqreqat funksiyaları

```java
AggregateBuilder.<Order>groupBy("o.customerId")
    // SUM(CASE WHEN status='PAID' THEN amount ELSE 0 END)
    .sumIf("o.status", "PAID", "o.amount", 0).as("paidRevenue").done()
    // SUM(CASE WHEN type='OUT' THEN 1 ELSE 0 END)  ← conditional count
    .sumIf("o.type", "OUT", 1, 0).as("outCount").done()
    // COUNT(CASE WHEN status='PAID' THEN 1 END)
    .countIf("o.status", "PAID").as("paidCount").done()
    // AVG / MAX / MIN şərtli
    .avgIf("o.status", "PAID", "o.amount", 0).as("avgPaid").done()
    .maxIf("o.type",   "SALE", "o.amount", 0).as("maxSale").done()
    .minIf("o.type",   "SALE", "o.amount", 0).as("minSale").done()
```

#### AggStep — IfExpr math operandı

Sadə `.sum()` ilə başlayan zəncirə IfExpr-i operand kimi qoşmaq:

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(purchaseExpense * CASE WHEN actionType='medaxil' THEN 1 ELSE 0 END)
    .sum("t.purchaseExpense")
        .multiplyIf("t.actionType", "medaxil", 1, 0)
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

**IfExpr metod parametrləri:**

| Parametr | Məna |
|---|---|
| `condField` (`"alias.field"`) | Şərt sütunu |
| `equalTo` | Bərabərlik dəyəri |
| `thenVal` | `"alias.field"` → sütun ref; digər → literal |
| `elseVal` | `"alias.field"` → sütun ref; digər → literal |

---

### 2.13 CoalesceExpr — COALESCE ifadəsi

`COALESCE(f1, f2, ..., default)` — ilk null olmayan dəyəri götürür. **ComputedField**, **ConcatItem** və **AggregateBuilder** daxilindən istifadə edilə bilər.

#### ComputedField — başlanğıc nöqtəsi

```java
// Sütunlar arasında COALESCE
ComputedField.coalesce("u.nickname", "u.firstName", "u.email")
    .as("displayName")
// → COALESCE(nickname, first_name, email) AS displayName

// Default dəyər ilə
ComputedField.coalesce("u.nickname", "u.firstName")
    .orElse("Anonim")
    .as("displayName")
// → COALESCE(nickname, first_name, 'Anonim') AS displayName

// Hesablamaya qoşulur
ComputedField.coalesce("o.discount", "o.promoDiscount")
    .orElse("0")
    .subtract("o.fee")
    .as("netDiscount")
// → COALESCE(discount, promo_discount, '0') - fee AS netDiscount
```

#### ConcatItem — CONCAT daxilində COALESCE

```java
import static az.mbm.jooqsqlgenerate.builder.ConcatItem.*;

manager.addConcatColumn("displayName", " ",
    literal("Ad:"),
    coalesce(CoalesceExpr.of("u.nickname", "u.firstName").orElse("Anonim")))
// → 'Ad:' || ' ' || COALESCE(nickname, first_name, 'Anonim')
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

**v1.1.12-dən:** `andOn`-da alias ilə field verilə bilər:
```java
.andOnEqual("p.status", "A")      // AND p.status = 'A'
.andOnNotEqual("p.status", "D")   // AND p.status != 'D'
```

**`andOn` shortcut metodları** (v1.1.50-dən tam `andOn*` ailəsi həm `JooqQuery` join builder-ində, həm `JooqManager.addLeftJoin/addInnerJoin` builder-ində mövcuddur; qısa adlar da işləyir):

| Metod | Qısa ad | SQL |
|---|---|---|
| `andOnEqual(field, value)` | `equal` | `AND field = value` |
| `andOnNotEqual(field, value)` | `notEqual` | `AND field != value` |
| `andOnGreaterThan(field, value)` | `greaterThan` | `AND field > value` |
| `andOnGreaterThanOrEqual(field, value)` | `greaterThanOrEqual` | `AND field >= value` |
| `andOnLessThan(field, value)` | `lessThan` | `AND field < value` |
| `andOnLessThanOrEqual(field, value)` | `lessThanOrEqual` | `AND field <= value` |
| `andOnIsNull(field)` | `isNull` | `AND field IS NULL` |
| `andOnIsNotNull(field)` | `isNotNull` | `AND field IS NOT NULL` |

```java
manager
    .addInnerJoin(EmployeeFlowEntity.class, "T2")
        .on("t.fkTaskId", "fkTaskId")
        .on("t.fkRelatedId", "fkRelatedId")
        .andOnEqual("status", "A")        // AND T2.status = 'A'
        .andOnNotEqual("type", "X")       // AND T2.type != 'X'
    .done();
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

### 3.4 onFrom — Op ilə operatorlu JOIN şərti

`onFrom()` equality (`=`) əvəzinə istənilən müqayisə operatoru ilə işlədilə bilər:

```java
jooq.addInnerJoin(RequestEntity.class, "r")
        .onFrom("t", "fkRequestId", Op.EQUAl,        "id")         // t.fkRequestId = r.id
        .onFrom("t", "amount",      Op.GREATER_THAN,  "minAmount")  // t.amount > r.minAmount
        .andOn("status", Op.EQUAl, "A")
    .done()
// → INNER JOIN request r
//       ON t.fk_request_id = r.id
//      AND t.amount > r.min_amount
//      AND r.status = 'A'
```

**v1.1.12-dən:** `onFrom`-un ikinci parametrində `alias.field` formatı dəstəklənir:

```java
.addInnerJoin(ProfitTaxIndicator.class, "tax")
    .onFrom("i.fkTaxIndicatorId", "tax.id")   // i.fk_tax_indicator_id = tax.id
    .andOnEqual("tax.status", "A")             // AND tax.status = 'A'
    .done()
```

`onFrom()` dəstəklənən operatorlar: `EQUAl`, `NOT_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL_TO`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL_TO`.

### 3.5 Generated table JOIN (tip-təhlükəli)

```java
JooqQuery.from(ORDERS, "o")
    .leftJoin(USERS, "u", ORDERS.USER_ID.eq(USERS.ID))
    .execute(dsl);
```

### 3.6 SelectTable JOIN

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

> **WHERE / HAVING yönləndirmə qaydası (v1.1.50):** `t.field` kimi **cədvəl-alias prefiksli**
> referans həmişə real sütundur → WHERE-ə gedir. **Prefixsiz** yazılış isə output alias-a
> (aqreqat/computed/concat/rounded) uyğun gəlirsə müvafiq ifadəyə (aqreqat üçün HAVING-ə)
> yönləndirilir:
> ```java
> .agg(Agg.SUM, "t.totalPrice", "totalPrice")           // SUM(...) AS totalPrice
> .filter("t.totalPrice", Op.NOT_EQUAL, 0)              // → WHERE  t.total_price != 0
> .globalFilter("totalPrice", Map.of("greaterThan","100")) // → HAVING SUM(total_price) > 100
> ```

### 4.2 Bütün filter əməliyyatları (Op enum)

| Op | SQL | Nümunə |
|---|---|---|
| `EQUAL` | `= value` | `Op.EQUAL, "ACTIVE"` |
| `EQUAl` | `= value` — `EQUAL` ilə eyni | geri uyğunluq yazılışı |
| `NOT_EQUAL` | `!= value` | `Op.NOT_EQUAL, "BANNED"` |
| `LIKE` | String: `LOWER(REPLACE(...)) LIKE '%val%'` / Numeric: `CAST AS varchar LIKE '%val%'` | `Op.LIKE, "ali"` |
| `START_WITH` | String: Türk-aware `LIKE 'val%'` / Numeric: `CAST AS varchar LIKE 'val%'` | `Op.START_WITH, "A"` |
| `END_WITH` | String: Türk-aware `LIKE '%val'` / Numeric: `CAST AS varchar LIKE '%val'` | `Op.END_WITH, ".az"` |
| `LIKE_IGNORE_CASE` | String: Türk-aware `LIKE '%val%'` / Numeric: `CAST AS varchar LIKE '%val%'` | `Op.LIKE_IGNORE_CASE, "İlkin"` |
| `START_WITH_IGNORE_CASE` | String: Türk-aware `LIKE 'val%'` / Numeric: sadə CAST | `Op.START_WITH_IGNORE_CASE, "İ"` |
| `END_WITH_IGNORE_CASE` | String: Türk-aware `LIKE '%val'` / Numeric: sadə CAST | `Op.END_WITH_IGNORE_CASE, "lı"` |
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

> **Qeyd — `EQUAl` → `EQUAL`:** `EQUAl` adında yazı səhvi var idi. `Op.EQUAL` düzgün adlanmış
> qarşılıq kimi əlavə edilib; hər ikisi eyni SQL verir (Collection dəyərində avtomatik `IN`
> çevrilməsi daxil). Köhnə kod dəyişməz işləyir, yeni kodda `EQUAL` istifadə edin.

### 4.3 Türk əlifbası case-insensitive LIKE — tip yoxlaması ilə

`Op.LIKE`, `Op.START_WITH`, `Op.END_WITH` (və `IGNORE_CASE` variantları) field tipinə
görə fərqli SQL yaradır:

**String field (`varchar`, `text`):**
```java
.filter("u.firstName", Op.LIKE, "İlkin")
// → WHERE LOWER(REPLACE(REPLACE(u."first_name",'İ','i'),'I','i'))
//         LIKE '%ilkin%'
// "İlkin", "ilkin", "ILKIN", "iLKİN" — hamısı tapılır
```

**Numeric field (`bigint`, `integer`, `numeric`):**
```java
.filter("o.taskNo", Op.LIKE, "1042")
// → WHERE CAST(o."task_no" AS varchar) LIKE '%1042%'
// Rəqəm sütununda 'İ'/'I' ola bilməz — REPLACE/LOWER tətbiq olunmur
```

> **Niyə bu fərq lazımdır?**
> Numeric field üçün `REPLACE(bigint, 'İ', 'i')` PostgreSQL-də
> `function replace(bigint, unknown, unknown) does not exist` xətası verir.
> Tip yoxlaması (`isStringField`) bu xətanın qarşısını avtomatik alır.

Bu davranış bütün LIKE axınlarında eynidir: `filter()`, `globalFilter()`,
`Filter.of().like()`, HAVING, SubQuery — hamısı `FilterStrategies` üzərindən keçir.

### 4.4 JooqManager — birbaşa filter metodları

`JooqManager` istifadə edərkən `Filters.of()` yaratmadan birbaşa filter əlavə etmək mümkündür:

```java
jooq.setMainTable(Order.class, "o")
    .addColumns("o.id", "o.name", "o.status", "o.createdAt")
    .equal("o.status",    "ACTIVE")              // null/boş → atlanır
    .equal("o.statusId",  1L)                    // Long — null → atlanır
    .notEqual("o.typeId", 5)                     // int (autobox Integer)
    .greaterThan("o.amount",    minAmount)       // BigDecimal — null → atlanır
    .lessThanOrEqual("o.age",   65)              // int
    .like("o.name",       name)                  // null/boş → atlanır
    .between("o.createdAt", startDate, endDate)  // Long/Number — null olarsa partial
    .in("o.roleId",       List.of(1L, 2L, 3L))  // boş list → atlanır
    .notIn("o.status",    Set.of("DELETED"))     // boş set → atlanır
    .isNull("o.deletedAt")
    .execute();
```

**Dəstəklənən rəqəm tipləri** (`Number` overload vasitəsilə — hamısı avtomatik işləyir):
`Long`, `long`, `Integer`, `int`, `Double`, `double`, `BigDecimal`, `BigInteger`, `Float`, `Short`

**`between` — null dəyərlər qismən dəstəklənir:**
```java
// startDate=null, endDate dolu  → WHERE o.createdAt <= endDate
// startDate dolu, endDate=null  → WHERE o.createdAt >= startDate
// hər ikisi dolu                → WHERE o.createdAt BETWEEN start AND end
// hər ikisi null                → şərt əlavə edilmir
.between("o.createdAt", startDate, endDate)   // Long
.between("o.price",     minPrice,  maxPrice)  // BigDecimal
```

**`in` / `notIn` — `List` və `Set` qəbul edir:**
```java
.in("o.roleId",  List.of(1L, 2L, 3L))
.in("o.status",  Set.of("ACTIVE", "PENDING"))
.notIn("o.type", List.of("DELETED", "ARCHIVED"))
```

**Mövcud `addFilter()` istifadəsi davam edir — köhnə kod sınmır:**
```java
jooq.addFilter(Filters.of().equal("status", "ACTIVE").like("name", name));
jooq.addFilter("status", Op.EQUAl, status);
jooq.addFilter(USERS.STATUS.eq("ACTIVE"));
```

### 4.5 globalFilter — Filters builder ilə

`null` və ya boş string (`""`, `"  "`) dəyər gəldikdə şərt **avtomatik atlanır** —
`equal`, `notEqual`, `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`,
`like`, `startWith`, `endWith` metodlarının hamısında bu davranış eynidir.

```java
JooqQuery.from(Order.class, "o")
    .globalFilter(
        Filters.of()
            .equal("o.status",       "ACTIVE")
            .like("o.orderNo",       orderNo)       // null/boş → atlanır
            .greaterThan("o.amount", minAmount)     // null/boş → atlanır
            .between("o.createdAt", "2024-01-01", "2024-12-31")
    )
    .execute(dsl);
```

### 4.6 globalFilter — Map ilə (REST sorğularından gəldikdə)

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

### 4.7 Birbaşa jOOQ Condition

```java
.filter(USERS.STATUS.eq("ACTIVE"))
.filter(USERS.AGE.gt(18).and(USERS.STATUS.ne("BANNED")))
.rawCondition(DSL.condition("u.created_at > NOW() - INTERVAL '30 days'"))
```

### 4.8 fieldFilter — iki sahə arasında WHERE müqayisəsi

JOIN edilmiş iki cədvəlin sahələrini birbaşa bir-biri ilə müqayisə etmək üçün:

```java
// t.fk_task_id = f.fk_task_id AND t.total_price > f.total_price
JooqQuery.from(Task.class, "t")
    .leftJoin(Flow.class, "f", "fkFlowId", "id")
    .fieldFilter("t.fkTaskId",   Op.EQUAl,        "f.fkTaskId")
    .fieldFilter("t.totalPrice", Op.GREATER_THAN,  "f.totalPrice")
    .execute(dsl);
// → WHERE t."fk_task_id" = f."fk_task_id"
//     AND t."total_price" > f."total_price"
```

`JooqManager` ilə:
```java
jooq.addFieldFilter("t.totalPrice", Op.GREATER_THAN_OR_EQUAL_TO, "f.minAmount");
```

`fieldFilter` dəstəklənən operatorlar: `EQUAl`, `NOT_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL_TO`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL_TO`.

### 4.9 OR qrupu filterlər — addOrOperation

Eyni `conditionAlias` altında toplanan filtrlər AND şərti ilə birləşir; fərqli `conditionAlias`-lar isə OR ilə:

```java
// (t.status = 'PAID' AND t.amount > 500) OR (t.status = 'REFUND' AND t.amount < 0)
jooq.addOrOperation("group1", "t", "status",  Map.of("equal", "PAID"))
    .add("t", "amount", Map.of("greaterThan", "500"))
    .done()
    .addOrOperation("group2", "t", "status",  Map.of("equal", "REFUND"))
    .add("t", "amount", Map.of("lessThan",    "0"))
    .done();
// → WHERE (t.status = 'PAID' AND t.amount > 500)
//      OR (t.status = 'REFUND' AND t.amount < 0)
```

Yalnız tək şərt:
```java
jooq.addOrOperation("cond", "t", "actionType", Map.of("equal", "OUT"))
    .done();
```

`Op` enum ilə overload:
```java
jooq.addOrOperation("cond", "t", "actionType", Op.EQUAl, "OUT")
    .done();
```

### 4.10 Mürəkkəb OR/AND qruplaması — orGroup

`x AND (y OR (z AND f))` formatlı qruplaşdırılmış şərtlər üçün fluent builder:

```java
// WHERE t.companyId = 5
//   AND (
//         (t.operationDate BETWEEN '2024-01-01' AND '2024-03-31')
//      OR (t.operationDate BETWEEN '2024-07-01' AND '2024-09-30')
//      OR (t.status = 'ACTIVE' AND t.amount > 1000)
//       )
jooq.addFilter("t.companyId", Op.EQUAl, 5)
    .orGroup("g")
        .or("t", "operationDate", Op.BETWEEN, "2024-01-01,2024-03-31")
        .or("t", "operationDate", Op.BETWEEN, "2024-07-01,2024-09-30")
        .andBranch("branch1")
            .add("t", "status", Op.EQUAl,        "ACTIVE")
            .add("t", "amount", Op.GREATER_THAN,  1000)
        .end()
    .done();
```

**Qaydalar:**
- `.or(...)` hər çağırışı öz ayrı OR branch-ı yaradır — eyni field iki dəfə çağırıldıqda OR-lanır (AND deyil).
- `.andBranch("alias")` — eyni alias altındakı `.add()` çağırışları AND ilə birləşir; fərqli branchAlias-lar OR ilə.
- `.done()` — builder-i bağlayır, ana sorğuya qayıdır.

`JooqQuery` ilə eyni sintaksis:
```java
JooqQuery.from(Task.class, "t")
    .filter("t.companyId", Op.EQUAl, 5)
    .orGroup("g")
        .or("t", "operationDate", Op.BETWEEN, "2024-01-01,2024-03-31")
        .or("t", "operationDate", Op.BETWEEN, "2024-07-01,2024-09-30")
        .andBranch("b1")
            .add("t", "status", Op.EQUAl,       "ACTIVE")
            .add("t", "amount", Op.GREATER_THAN, 1000)
        .end()
    .done()
    .execute(dsl);
```

`Map` overload da mövcuddur:
```java
.orGroup("g")
    .or("t", "status", Map.of("equal", "ACTIVE"))
    .andBranch("b1")
        .add("t", "type", Map.of("equal", "OUT"))
        .add("t", "amount", Map.of("greaterThan", "500"))
    .end()
.done()
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

### 6.1 Sadə EXISTS

```java
ExistsSpec<User, Order> hasOrders = ExistsSpec
    .exists(Order.class)
    .joinField("fkUserId", "u", "id")     // orders.fk_user_id = u.id
    .filter("status", Op.EQUAl, "PAID");

JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName")
    .exists(hasOrders)
    .execute(dsl);
// → WHERE EXISTS (SELECT 1 FROM orders
//                  WHERE orders."fk_user_id" = u."id"
//                    AND orders."status" = 'PAID')
```

### 6.2 NOT EXISTS

```java
ExistsSpec<User, Blacklist> notBlacklisted = ExistsSpec
    .notExists(Blacklist.class)
    .joinField("fkUserId", "u", "id")
    .filter("active", Op.EQUAl, true);

JooqQuery.from(User.class, "u")
    .exists(notBlacklisted)
    .execute(dsl);
// → WHERE NOT EXISTS (SELECT 1 FROM blacklist
//                      WHERE blacklist."fk_user_id" = u."id"
//                        AND blacklist."active" = true)
```

### 6.3 Çoxlu joinField

Birdən çox sahə ilə korrelyasiya qurmaq mümkündür:

```java
ExistsSpec.exists(TaskPermission.class)
    .joinField("fkTaskId",    "t", "id")         // tp.fk_task_id = t.id
    .joinField("fkCompanyId", "t", "companyId")  // tp.fk_company_id = t.company_id
    .filter("active", Op.EQUAl, true)
```

### 6.4 EXISTS daxilində OR/AND qruplaması — orGroup

EXISTS alt-sorğusunun içinə `orGroup().andBranch()` ilə mürəkkəb şərt əlavə edilə bilər:

```java
ExistsSpec.exists(TaskPermission.class)
    .joinField("fkTaskId", "t", "id")          // tp.fk_task_id = t.id
    .filter("status", Op.EQUAl, "A")           // AND tp.status = 'A'
    .orGroup()
        .andBranch("branch1")
            .add("fkFilterId",    Op.EQUAl, userId)
            .add("fkTaskTypeKey", Op.IN,    taskTypeKeys)
        .end()
        .andBranch("branch2")
            .add("fkTaskTypeKey", Op.IN, visibleKeys)
        .end()
    .done()
// → EXISTS (SELECT 1 FROM task_permission
//            WHERE tp.fk_task_id = t.id
//              AND tp.status = 'A'
//              AND (
//                    (tp.fk_filter_id = ? AND tp.fk_task_type_key IN (...))
//                 OR (tp.fk_task_type_key IN (...))
//                  ))
```

Sadə `.or()` ilə eyni field-i iki dəfə OR-lamaq:

```java
ExistsSpec.exists(TaskPermission.class)
    .joinField("fkTaskId", "t", "id")
    .orGroup()
        .or("fkFilterId", Op.EQUAl, userId)
        .or("fkFilterId", Op.IS_EMPTY, "")   // OR fk_filter_id IS NULL
    .done()
```

### 6.5 JooqManager — inline EXISTS builder (`addExists`)

`ExistsSpec` import etmədən birbaşa `JooqManager` zənciri içində EXISTS yazmaq mümkündür.
`addExists(Class)` ilə açılır, `done()` ilə `JooqManager`-ə qayıdır.

**Sadə hal:**
```java
jooq.setMainTable(CashItemGroup.class, "t")
    .equal("t.status", "A")
    .addExists(CashFlowEntity.class)
        .joinField("fkCashGroupId", "t", "id")
        .equal("status", "A")
    .done()
    .addSelectAs("t.id", "key")
    .addSelectAs("t.cashItemGroupName", "value")
    .noPagination()
    .skipCount();
// → WHERE t.status = 'A'
//     AND EXISTS (SELECT 1 FROM cash_flow
//                  WHERE cash_flow.fk_cash_group_id = t.id
//                    AND cash_flow.status = 'A')
```

**Shorthand filter metodları** — `Op` yazmadan:

| Metod | SQL |
|---|---|
| `.equal(field, value)` | `WHERE field = value` |
| `.notEqual(field, value)` | `WHERE field != value` |
| `.in(field, Collection<?>)` | `WHERE field IN (...)` |
| `.in(field, Object...)` | `WHERE field IN (...)` — varargs |
| `.notIn(field, Collection<?>)` | `WHERE field NOT IN (...)` |
| `.like(field, value)` | `WHERE field LIKE '%val%'` |
| `.isNull(field)` | `WHERE field IS NULL` |
| `.isNotNull(field)` | `WHERE field IS NOT NULL` |
| `.filter(field, Op, value)` | Hər hansı `Op` ilə |

Null / boş dəyərlər bütün metodlarda avtomatik atlanır.

**Çoxlu filter:**
```java
jooq.addExists(CashFlowEntity.class)
        .joinField("fkGroupId", "t", "id")
        .equal("status", "A")
        .notEqual("type", "DRAFT")
        .in("typeKey", List.of("K1", "K2", "K3"))
        .isNotNull("approvedBy")
    .done()
```

**NOT EXISTS:**
```java
jooq.addNotExists(BlockedEntity.class)
        .joinField("userId", "u", "id")
        .equal("active", true)
    .done()
```

**HAVING EXISTS (aggregate sorğular üçün):**
```java
jooq.addHavingExists(PaymentEntity.class)
        .joinField("fkTaskId", "t", "id")
        .equal("status", "PAID")
    .done()
```

**OR qrupu ilə:**
```java
jooq.addExists(TaskPermission.class)
        .joinField("fkTaskId", "t", "id")
        .equal("status", "A")
        .orGroup()
            .or("fkFilterId", Op.EQUAl, userId)
            .andBranch("b1")
                .add("fkTaskTypeKey", Op.IN,    keys)
                .add("fkRoleId",      Op.EQUAl, roleId)
            .end()
        .done()
    .done()
```

### 6.6 JooqManager ilə EXISTS — dinamik nümunə

Köhnə `ExistsSpec` ilə dinamik çağırış:

```java
private void addBarMenuExists(JooqManager jooq, Long userId, String status,
                               List<String> keys, List<String> visibleKeys) {
    ExistsSpec<?, TaskPermission> spec = ExistsSpec
        .exists(TaskPermission.class)
        .joinField("fkTaskId", "t", "id")
        .filter("status", Op.EQUAl, status);

    if (userId != null && keys != null && !keys.isEmpty()) {
        spec.orGroup()
            .andBranch("b1")
                .add("fkFilterId",    Op.EQUAl, userId)
                .add("fkTaskTypeKey", Op.IN,    keys)
            .end()
            .andBranch("b2")
                .add("fkTaskTypeKey", Op.IN, visibleKeys)
            .end()
        .done();
    }

    jooq.addExists(spec);
}

// İstifadə:
if (condition1) addBarMenuExists(jooq, userId, "A", type1Keys, visibleKeys1);
if (condition2) addBarMenuExists(jooq, userId, "B", type2Keys, visibleKeys2);
// → AND EXISTS (...) AND EXISTS (...)  ← hər çağırış ayrı EXISTS əlavə edir
```

> **Qeyd:** `addExists()` / `addExistsFilter()` hər çağırışda yeni `AND EXISTS (...)` əlavə edir.

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
.aggWithMath(Agg.SUM, "o.price", MathOp.MULTIPLY, "o.qty", "revenue", 2)
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

### 7.3.1 JooqManager — fluent agg zənciri (`addAggFunction`)

Çox field ilə riyazi əməliyyatı birbaşa zəncir kimi yaza bilərsən:

```java
// Çox field
manager.addAggFunction(Agg.SUM, "t.price")
       .add("t.tax")
       .subtract("t.discount")
       .multiply("t.qty")
       .as("totalRevenue")
// → SUM(((price + tax) - discount) * qty) AS totalRevenue

// Yuvarlama ilə
manager.addAggFunction(Agg.SUM, "t.price")
       .subtract("t.discount")
       .as("netTotal", 2)
// → ROUND(SUM(price - discount), 2) AS netTotal

// Tək field (əvvəlki kimi)
manager.addAggFunction(Agg.SUM, "t.price")
       .as("totalPrice")
// → SUM(price) AS totalPrice

// IfExpr math operandı ilə
manager.addAggFunction(Agg.SUM, "t.purchaseExpense")
       .multiplyIf("t.actionType", "medaxil", 1, 0)
       .as("expense")
// → SUM(purchaseExpense * CASE WHEN actionType='medaxil' THEN 1 ELSE 0 END)
```

### 7.3.2 İki SUM ifadəsini bir-birindən çıxmaq — SUM(exprA) - SUM(exprB)

`SUM` xətti (lineer) əməliyyat olduğu üçün `SUM(a) - SUM(b) = SUM(a - b)` — yəni iki ayrı
aqreqatı çıxmaq əvəzinə, hər tərəfi `ComputedField` ilə qurub tək bir aqreqat daxilində
çıxmaq kifayətdir. Bu, əlavə sorğu qatı yaratmadan, tək SELECT sütununda hesablanır.

**Sadə hal — 2 sahə:**

```java
manager.addAggFunctionOnComputed(
    Agg.SUM,
    ComputedField.of("t.totalIn").subtract("t.totalOut"),
    "netAmount"
)
// → SUM(t.totalIn - t.totalOut) AS netAmount
```

**Mürəkkəb hal — hər tərəfdə bir neçə sahə (toplama + vurma qarışıq):**

Oxunaqlılıq üçün hər tərəfi əvvəlcə ayrı `ComputedField` dəyişəninə çıxarın,
sonra onları `.subtract(...)` ilə birləşdirin:

```java
ComputedField inSide = ComputedField.sumOf(
        ComputedField.expr("t.totalIn"),
        ComputedField.expr("t.expense").multiply("t.actionIn")
);

ComputedField outSide = ComputedField.sumOf(
        ComputedField.expr("t.totalOut"),
        ComputedField.expr("t.expense").multiply("t.actionOut")
);

manager.addAggFunctionOnComputed(
    Agg.SUM,
    inSide.subtract(outSide),
    "netAmount"
)
// → SUM((t.totalIn + t.expense * t.actionIn) - (t.totalOut + t.expense * t.actionOut)) AS netAmount
```

Eyni struktur `JooqQuery` üzərində birbaşa `aggOnComputed(...)` ilə də işləyir:

```java
JooqQuery.from(CashFlow.class, "t")
    .groupBy("t.actionType")
    .aggOnComputed(Agg.SUM, inSide.subtract(outSide), "netAmount")
    .execute(dsl);
```

> **Qeyd:** Bu, iki tam ayrı `SUM(...)` ifadəsini SQL səviyyəsində hərfi mənada çıxmaqdan
> fərqlidir — riyazi olaraq eyni nəticəni verir, amma tək aqreqat funksiyası kimi daha
> səmərəlidir. Bu formanın daha qısa yazılışı üçün bax: **7.3.3 addSumExpr**.

### 7.3.3 addSumExpr / AggExpr — oxunaqlı aqreqat zənciri

7.3.2-dəki "hər tərəfi ayrı `ComputedField`-ə çıxar" yanaşmasının daha səliqəli qarşılığı.
Riyazi ekvivalentlikdən istifadə edir: `(a + b) - (c + d) = a + b - c - d` — yəni iç-içə
`sumOf(...)` qruplarına ehtiyac yoxdur, terminlər düz zəncirlə yazılır:

```java
// SUM( marginalCostOut + purchaseExpense*actionOut
//      - marginalCostIn - purchaseExpense*actionIn ) AS totalPrice
manager.addSumExpr("totalPrice", e -> e
        .plus("t.marginalCostOut")
        .plus("t.totalPurchaseExpense", "t.actionOut")    // + (f1 * f2)
        .minus("t.marginalCostIn")
        .minus("t.totalPurchaseExpense", "t.actionIn"));  // - (f1 * f2)
```

`AggExpr` metodları:

| Metod | Mənası |
|---|---|
| `plus(field)` | `+ field` — zəncir mütləq `plus(...)` ilə başlamalıdır |
| `plus(f1, f2)` | `+ (f1 * f2)` — iki sahənin hasilini əlavə edir |
| `plus(f1, MathOp, f2)` | `+ (f1 OP f2)` — istənilən əməliyyat; `DIVIDE` → `NULLIF` sıfıra bölmə qorunması avtomatik |
| `plus(ComputedField)` | `+ (ifadə)` — mürəkkəb termin (round, cast, iç-içə zəncir) |
| `minus(field)` | `- field` |
| `minus(f1, f2)` | `- (f1 * f2)` |
| `minus(f1, MathOp, f2)` | `- (f1 OP f2)` — istənilən əməliyyat, bölmə daxil |
| `minus(ComputedField)` | `- (ifadə)` |

Bölmə nümunəsi:

```java
// SUM( totalPrice/qty - discount ) AS avgNet
manager.addSumExpr("avgNet", e -> e
        .plus("t.totalPrice", MathOp.DIVIDE, "t.qty")   // + (total_price / NULLIF(qty, 0))
        .minus("t.discount"));
```

Digər formalar:

```java
// İstənilən aqreqat funksiyası ilə:
manager.addAggExpr(Agg.AVG, "avgNet", e -> e.plus("t.income").minus("t.expense"));

// Yuvarlama ilə:
manager.addAggExpr(Agg.SUM, "totalPrice", 2, e -> e.plus("t.price", "t.qty"));

// JooqQuery üzərində birbaşa:
JooqQuery.from(CashFlow.class, "t")
    .groupBy("t.actionType")
    .sumExpr("netAmount", e -> e.plus("t.totalIn").minus("t.totalOut"))
    .execute(dsl);
```

Mürəkkəb termin lazım olduqda `plus(ComputedField)` / `minus(ComputedField)` istifadə edin:

```java
manager.addSumExpr("net", e -> e
        .plus(ComputedField.expr("t.price").multiply("t.qty").round(2))
        .minus("t.discount"));
```

> **Qeyd:** `addSumExpr` daxildə `addAggFunctionOnComputed(Agg.SUM, ...)`-a çevrilir —
> SQL nəticəsi flat `ComputedField` zənciri ilə eynidir. Aqreqat `COALESCE(SUM(...), 0)`
> ilə bükülür (mövcud davranış).

### 7.4 AggregateBuilder — fluent API

`AggregateBuilder` obyektləri `JooqQuery` üzərində DEYİL, `SelectQueryBuilder.aggregate(...)`
üzərindən bağlanır. `SelectQueryBuilder`-in terminal metodu `.execute(dsl)` yox, `.build(dsl)`-dır:

```java
SelectQueryBuilder.from(Order.class, "o")
    .select("o.customerId")
    .aggregate(
        AggregateBuilder.<Order>groupBy("o.customerId")
            .sum("o.totalPrice").round(2).as("totalSum")
                .having(Op.GREATER_THAN, 1000)
            .done()
            .count("o.id").as("cnt").done()
    )
    .build(dsl);
// → HAVING ROUND(SUM(total_price),2) > 1000
```

### 7.4.1 AggregateBuilder — şərtli aqreqat funksiyaları (sumIf / countIf)

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(CASE WHEN status='PAID' THEN amount ELSE 0 END)
    .sumIf("t.status", "PAID", "t.amount", 0)
        .as("paidTotal").done()
    // SUM(CASE WHEN type='OUT' THEN 1 ELSE 0 END) — şərtli count
    .sumIf("t.type", "OUT", 1, 0)
        .as("outCount").done()
    // COUNT(CASE WHEN status='PAID' THEN 1 END)
    .countIf("t.status", "PAID")
        .as("paidCount").done()
    // Qalan şərtli funksiyalar
    .avgIf("t.status", "PAID", "t.amount", 0).as("avgPaid").done()
    .maxIf("t.type",   "SALE", "t.amount", 0).as("maxSale").done()
    .minIf("t.type",   "SALE", "t.amount", 0).as("minSale").done()
```

### 7.4.2 AggStep — IfExpr riyazi operandı

Sadə `.sum()` ilə başlayan zəncirə `CASE WHEN` ifadəsini operand kimi qoşmaq:

```java
AggregateBuilder.<CashFlow>groupBy("t.actionType")
    // SUM(purchaseExpense * CASE WHEN actionType='medaxil' THEN 1 ELSE 0 END)
    .sum("t.purchaseExpense")
        .multiplyIf("t.actionType", "medaxil", 1, 0)
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

### 7.7 AggregateBuilder — EXISTS / NOT EXISTS (HAVING)

`AggregateBuilder` daxilindən `exists()` / `notExists()` ilə HAVING EXISTS şərti əlavə etmək mümkündür.
Zəncir `done()` ilə `AggregateBuilder`-ə qayıdır.

#### Sadə EXISTS

```java
SelectQueryBuilder.from(CashFlow.class, "t")
    .select("t.status")
    .aggregate(
        AggregateBuilder.<CashFlow>groupBy("t.status")
            .count("t.id").as("cnt").done()
            .exists(AllowedStatus.class)
                .joinField("statusCode", "t", "status")
                .equal("isActive", true)
            .done()
    )
    .build(dsl);
// → SELECT t.status, COUNT(t.id) AS cnt
//   FROM cash_flow t
//   GROUP BY t.status
//   HAVING EXISTS (SELECT 1 FROM allowed_status
//                  WHERE status_code = t.status AND is_active = true)
```

#### NOT EXISTS

```java
AggregateBuilder.<Order>groupBy("o.customerId")
    .sum("o.totalPrice").round(2).as("totalSum").done()
    .notExists(BlockedCustomer.class)
        .joinField("customerId", "o", "customerId")
    .done()
```

#### IN / NOT IN / like / isNull ilə

```java
AggregateBuilder.<Task>groupBy("t.fkRequestId")
    .count("t.id").as("taskCount").done()
    .exists(TaskPermission.class)
        .joinField("fkTaskId", "t", "id")
        .in("fkRoleId", allowedRoles)
        .equal("isActive", true)
    .done()
```

#### OR qrupu ilə

```java
AggregateBuilder.<Task>groupBy("t.fkRequestId")
    .count("t.id").as("cnt").done()
    .exists(TaskPermission.class)
        .joinField("fkTaskId", "t", "id")
        .orGroup()
            .or("fkUserId",  Op.EQUAl, userId)
            .andBranch("b1")
                .add("fkRoleId",    Op.EQUAl, roleId)
                .add("fkTypeKey",   Op.IN,    typeList)
            .end()
        .done()
    .done()
// → HAVING EXISTS (SELECT 1 FROM task_permission
//                  WHERE fk_task_id = t.id
//                  AND (fk_user_id = ? OR (fk_role_id = ? AND fk_type_key IN (...))))
```

#### `AggExistsBuilder` metodları

| Metod | SQL qarşılığı |
|---|---|
| `joinField(eField, alias, mField)` | `EXISTS.eField = alias.mField` |
| `filter(field, op, value)` | İstənilən Op |
| `equal(field, value)` | `field = value` |
| `notEqual(field, value)` | `field != value` |
| `in(field, collection)` | `field IN (...)` |
| `in(field, values...)` | `field IN (...)` varargs |
| `notIn(field, collection)` | `field NOT IN (...)` |
| `notIn(field, values...)` | `field NOT IN (...)` varargs |
| `like(field, value)` | `LOWER(field) LIKE '%value%'` |
| `isNull(field)` | `field IS NULL` |
| `isNotNull(field)` | `field IS NOT NULL` |
| `orGroup()` | OR alt-qrupu açır |
| `done()` | `AggregateBuilder`-ə qayıdır |

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

**v1.1.50 dəyişiklikləri:**

- Eyni aqreqat alias-ına **bir neçə HAVING şərti** düşə bilər (məs. `greaterThan` + `lessThan`
  aralığı, və ya `havingFilter` + `globalFilter` birgə) — hamısı AND ilə birləşir. Əvvəl
  sonuncu şərt əvvəlkini üstələyirdi.
- Entity mode-da heç bir aqreqata uyğun gəlməyən `havingFilter` şərtləri əvvəllər **səssiz
  itirdi** — indi bare alias referansı ilə HAVING-ə düşür.
- `AggregateBuilder.AggStep.having(op, value)` bir neçə dəfə çağırıla bilər — şərtlər AND
  ilə birləşir.

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

### 9.3 Case — tam fluent zəncir

`Case` sinifi ilə `when().then().when().then().else_().as()` zənciri:

```java
import az.mbm.jooqsqlgenerate.builder.Case;

// Literal dəyərlər
Case.when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
    .when("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
    .else_("Naməlum")
    .as("statusLabel")

// Rəqəm dəyərləri
Case.when("a", Op.EQUAl, 1).then(0)
    .else_(-1)
    .as("result")

// Sütun referansı (thenField / elseField)
Case.when("type", Op.EQUAl, "A").thenField("t.priceA")
    .when("type", Op.EQUAl, "B").thenField("t.priceB")
    .elseField("t.defaultPrice")
    .as("finalPrice")
// → CASE WHEN type='A' THEN priceA WHEN type='B' THEN priceB ELSE defaultPrice END
```

### 9.4 JooqManager — addCase() fluent zəncir

`addCase()` bitdikdə `JooqManager`-ə qayıdır — sorğu zənciri davam edə bilər:

```java
manager.addCase()
       .when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
       .when("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
       .else_("Naməlum")
       .as("statusLabel")           // ← JooqManager-ə qayıdır
       .addOrderBy("createdAt", "DESC")
       .setPage(1, 20);

// Sütun referansı ilə
manager.addCase()
       .when("type", Op.EQUAl, "A").thenField("t.priceA")
       .when("type", Op.EQUAl, "B").thenField("t.priceB")
       .elseField("t.defaultPrice")
       .as("finalPrice");
```

---

## 10. ORDER BY

```java
// Sadə
.orderBy("u.createdAt", "DESC")
.orderBy("u.firstName", "ASC")

// Birləşmiş string — REST parametrindən birbaşa ötürmək üçün
.orderBy("t.insertDate desc, f.createdDate")
.orderBy("u.name asc, u.createdAt desc, t.amount")
// İstiqamət yazılmadıqda ASC qəbul edilir:
.orderBy("u.name")   // → ORDER BY u."name" ASC

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

**v1.1.12-dən:** entity mode-da join table alias-ları ORDER BY-da düzgün işləyir:
```java
factory.create()
    .setMainTable(IncomeFlowEntity.class, "t")
    .addInnerJoin(ProfitTaxIndicator.class, "tax")
        .onFrom("i.fkTaxIndicatorId", "tax.id")
        .done()
    .addOrderBy("tax.accountNo", "asc")   // ✓ tax alias-ı tanınır
    .fetch();
```

**JooqManager:**
```java
jooq.addOrderBy("t.insertDate desc, f.createdDate")

// REST-dən gələn sort parametri birbaşa:
jooq.addOrderBy(request.getSort())   // "t.insertDate desc,f.createdDate asc"
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

## 15. INSERT — InsertQueryBuilder

`InsertQueryBuilder` bir entity cədvəlinə yeni sətir əlavə edir.

### Sadə INSERT

```java
int rows = new InsertQueryBuilder<>(User.class, dsl)
    .value("firstName", "Əli")
    .value("lastName",  "Əliyev")
    .value("status",    "ACTIVE")
    .value("createdAt", LocalDateTime.now())
    .execute();
// → INSERT INTO users (first_name, last_name, status, created_at)
//   VALUES ('Əli', 'Əliyev', 'ACTIVE', ...)
```

### ID qaytaran INSERT

```java
Long newId = new InsertQueryBuilder<>(User.class, dsl)
    .value("firstName", "Bəhruz")
    .value("status",    "ACTIVE")
    .executeAndReturnId(Long.class);
```

### Null dəyərlərlə INSERT

Default olaraq `null` dəyərlər atlanır. `allowNulls()` ilə null-ları da daxil etmək olar:

```java
new InsertQueryBuilder<>(User.class, dsl)
    .allowNulls()
    .value("firstName", "Cavid")
    .value("deletedAt", null)    // null — allowNulls varsa daxil edilir
    .execute();
```

### Batch INSERT (çoxlu sətir)

`addRow()` cari sıranı tamamlayır, yeni boş sıra açır:

```java
int rows = new InsertQueryBuilder<>(Product.class, dsl)
    .value("name", "Məhsul A").value("price", 10.0).addRow()
    .value("name", "Məhsul B").value("price", 20.0).addRow()
    .value("name", "Məhsul C").value("price", 30.0).addRow()
    .executeBatch();
// → INSERT INTO products (name, price) VALUES (...), (...), (...)
```

### ON CONFLICT / ON DUPLICATE KEY UPDATE

```java
new InsertQueryBuilder<>(User.class, dsl)
    .value("email",  "ali@example.com")
    .value("status", "ACTIVE")
    .onDuplicateKeyUpdate("status", "ACTIVE")
    .execute();
// → INSERT INTO users (email, status) VALUES (...)
//   ON CONFLICT DO UPDATE SET status = 'ACTIVE'
```

### SQL debug (icrасız)

```java
String sql = new InsertQueryBuilder<>(User.class, dsl)
    .value("firstName", "Test")
    .value("status",    "ACTIVE")
    .toSQL();
```

**`QueryFactory` ilə (`@Autowired`):**

```java
@Service
@RequiredArgsConstructor
public class UserService {
    private final QueryFactory qf;

    public void create(String name) {
        qf.insert(User.class)
          .value("firstName", name)
          .value("status", "ACTIVE")
          .execute();
    }
}
```

---

## 16. JooqQuery — birbaşa fetch metodları

`JooqQuery` özündə `execute(dsl)` + ayrıca `SelectFetchJooq` çağırışı əvəzinə fetch
metodlarını birbaşa çağırmaq mümkündür:

### fetchMaps — `List<Map<String,Object>>`

```java
List<Map<String, Object>> rows = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .filter("u.status", Op.EQUAl, "ACTIVE")
    .page(0, 20)
    .fetchMaps(dsl);

for (Map<String, Object> row : rows) {
    Long   id   = (Long)   row.get("id");
    String name = (String) row.get("firstName");
}
```

### fetchInto — auto-mapping ilə DTO

```java
List<UserDto> users = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email")
    .filter("u.status", Op.EQUAl, "ACTIVE")
    .page(0, 20)
    .fetchInto(dsl, UserDto.class);
```

> **Qeyd:** `fetchMaps(dsl)` / `fetchInto(dsl, Class)` COUNT sorğusunu işlətmir —
> yalnız data sətirləri qaytarır. Sətir sayı + siyahı lazım olduqda `execute(dsl)` + `SelectFetchJooq` istifadə et.

---

## 17. Generated mode — jOOQ generated cədvəllər

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

## 18. Tam nümunə — mürəkkəb sorğu

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

## 19. Qısa referans

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
| `computedColumn(..., NullDefault)` | Riyazi ifadə + LEFT JOIN null idarəetməsi |
| `compute(field).add/subtract/multiply/divide(field).as(alias)` | Fluent riyazi zəncir |
| `compute(field).subtract().of(field)...done().as(alias)` | Mötərizəli qrup ifadəsi |
| `ComputedField.of(f).withNullDefault(NullDefault).as(alias)` | Bütün zəncirə null default |
| `ComputedField.of(f).multiplyNullAs(field, n).as(alias)` | `* COALESCE(field, n)` |
| `ComputedField.of(f).addNullAs(field, n).as(alias)` | `+ COALESCE(field, n)` |
| `ComputedField.of(f).subtractNullAs(field, n).as(alias)` | `- COALESCE(field, n)` |
| `ComputedField.of(f).divideNullAs(field, n).as(alias)` | `/ NULLIF(COALESCE(field,n),0)` |
| `ComputedField.ifExpr(cond,eq,then,else).as(alias)` | CASE WHEN sütun kimi |
| `ComputedField.of(f).multiplyIf(cond,eq,t,e).as(alias)` | `f * CASE WHEN ...` |
| `ComputedField.of(f).addIf(cond,eq,t,e).as(alias)` | `f + CASE WHEN ...` |
| `ComputedField.of(f).subtractIf(cond,eq,t,e).as(alias)` | `f - CASE WHEN ...` |
| `ComputedField.of(f).divideIf(cond,eq,t,e).as(alias)` | `f / CASE WHEN ...` |
| `ComputedField.coalesce(fields...).orElse(def).as(alias)` | COALESCE sütun kimi |
| `coalesce(alias, def, fields...)` | COALESCE sütunu |
| `castString(field, alias)` | `CAST(field AS VARCHAR)` |
| `castLong(field, alias)` | `CAST(field AS BIGINT)` |
| `castInteger(field, alias)` | `CAST(field AS INTEGER)` |
| `castBigDecimal(field, alias)` | `CAST(field AS NUMERIC)` |
| `castDateTime(field, pattern, alias)` | Tarix → string, bütün DB-lərlə |
| `castColumn(field, DataType, alias)` | İstənilən SQLDataType ilə cast |
| `subSelect(SubSelectBuilder)` | Scalar subquery sütunu |
| `caseWhen(...)` | CASE WHEN sütunu |
| `leftJoin(entity, alias, from, to)` | Sadə LEFT JOIN |
| `leftJoin(entity, alias).on(...).done()` | Builder LEFT JOIN |
| `leftJoin(entity, alias).onFrom(...).done()` | Zəncir JOIN |
| `leftJoin(Table, alias, Condition)` | Generated LEFT JOIN |
| `leftJoin(SelectTable, alias, ...)` | SelectTable JOIN |
| `filter(field, Op, value)` | WHERE filter (null-safe) |
| `filter(Condition)` | Birbaşa jOOQ şərti |
| `fieldFilter(left, Op, right)` | İki sahə arasında müqayisə (t.a > f.b) |
| `globalFilter(Filters)` | Filters builder ilə |
| `globalFilter(field, Map)` | Map ilə çoxlu əməliyyat |
| `globalFilter(Map<String,Map>)` | Tam field xəritəsi |
| `orGroup(alias).or(...).andBranch(...).add(...).end().done()` | Mürəkkəb OR/AND qruplaması |
| `inSubQuery(field, SubQueryIn)` | IN (SELECT ...) |
| `inSubQuery(fields[], SubQueryIn)` | COMPOSITE IN |
| `selectAll()` | Əsas entity-nin bütün sütunları (camelCase alias ilə, JOIN sütunları daxil deyil) |
| `exists(ExistsSpec)` | EXISTS / NOT EXISTS |
| `groupBy(fields...)` | GROUP BY |
| `agg(Agg, field, alias, round, dir)` | Aqreqat funksiya |
| `aggWithMath(...)` | Riyazi aqreqat SUM(f1*f2) |
| `aggOnComputed(...)` | ComputedField aqreqat |
| `sumExpr(alias, e -> e.plus(...).minus(...))` | Oxunaqlı SUM ifadə zənciri (AggExpr) — bax 7.3.3 |
| `aggExpr(Agg, alias, e -> ...)` | AggExpr — istənilən aqreqat funksiyası ilə |
| `aggregate(AggregateBuilder.groupBy(...).sumIf(...).done())` | Şərtli aqreqat |
| `havingFilter(field, Map)` | HAVING filter |
| `havingFilter(field, Op, value)` | HAVING birbaşa filter |
| `orderBy(field, dir)` | ORDER BY |
| `orderBy(sortExpression)` | ORDER BY string format: `"t.field desc, f.field"` |
| `orderBy(Map)` | ORDER BY map ilə |
| `page(page, size)` | Səhifələmə (0-dan başlayır) |
| `skipCount()` | Pagination var, COUNT işləmir (rowCount = -1) |
| `onlyCount()` | Yalnız COUNT işləyir, əsas data sorğusu icra edilmir |
| `noPagination()` | Bütün nəticə, COUNT olmadan |
| `withCount()` | Bütün data + COUNT |
| `execute(dsl)` | Sorğunu icra et |

### JooqManager əlavə metodları

| Metod | Təsvir |
|---|---|
| `setMainTable(Class, alias)` | Ana cədvəl təyini |
| `addColumns(cols...)` | SELECT sütunları |
| `addColumns(List<String>)` | SELECT — dinamik siyahı |
| `addColumns(Field<?>...)` | SELECT — generated field-lər |
| `addColumnFields(List<Field<?>>)` | SELECT — generated field siyahısı |
| `addSelectAs(field, alias)` | Özəl alias ilə SELECT |
| `addRawSelectField(Field<?>)` | Birbaşa jOOQ Field SELECT-ə |
| `addComputedField(ComputedField)` | Hazır ComputedField obyekti ilə |
| `addCoalesceColumn(alias, default, fields...)` | COALESCE sütunu |
| `addCoalesceColumn(alias, default, List<String>)` | COALESCE — dinamik siyahı |
| `addCoalesceColumn(alias, default, Collection<ConcatItem>)` | COALESCE — `ConcatItem.field(...)` siyahısı |
| `addConcatColumn(alias, sep, fields...)` | CONCAT sütunu |
| `addConcatColumn(alias, sep, List<String>)` | CONCAT — dinamik siyahı |
| `addConcatColumn(alias, sep, Collection<ConcatItem>)` | CONCAT — field + literal qarışıq dinamik siyahı |
| `addSubQueryColumn(SubSelectBuilder)` | Scalar subquery sütunu |
| `setDistinct()` | SELECT DISTINCT |
| `addFilter(field, Op, value)` | WHERE filter (Op ilə) |
| `addFilter(Filters)` | Filters builder ilə |
| `addFilter(Condition)` | Birbaşa jOOQ şərti |
| `equal(field, value)` | `WHERE field = value` — birbaşa |
| `notEqual(field, value)` | `WHERE field != value` — birbaşa |
| `like(field, value)` | `WHERE field LIKE '%val%'` — birbaşa |
| `startWith(field, value)` | `WHERE field LIKE 'val%'` — birbaşa |
| `endWith(field, value)` | `WHERE field LIKE '%val'` — birbaşa |
| `greaterThan(field, value)` | `WHERE field > value` — birbaşa |
| `greaterThanOrEqual(field, value)` | `WHERE field >= value` — birbaşa |
| `lessThan(field, value)` | `WHERE field < value` — birbaşa |
| `lessThanOrEqual(field, value)` | `WHERE field <= value` — birbaşa |
| `isNull(field)` | `WHERE field IS NULL` — birbaşa |
| `isNotNull(field)` | `WHERE field IS NOT NULL` — birbaşa |
| `in(field, Collection<?>)` | `WHERE field IN (...)` — List/Set |
| `notIn(field, Collection<?>)` | `WHERE field NOT IN (...)` — List/Set |
| `between(field, String, String)` | `BETWEEN` — null handling daxil |
| `between(field, Number, Number)` | `BETWEEN` — Long/BigDecimal/Integer |
| `addFieldFilter(left, Op, right)` | İki sahə arasında WHERE müqayisəsi |
| `addComputedColumn(field).add/subtract/multiply/divide(field).as(alias)` | Fluent riyazi zəncir |
| `addComputedColumn(field).subtract().of(field)...done().as(alias)` | Mötərizəli qrup ifadəsi |
| `addComputedColumn(field).multiplyIf(cond,eq,t,e).as(alias)` | `field * CASE WHEN ...` |
| `addComputedColumn(field).addIf(cond,eq,t,e).as(alias)` | `field + CASE WHEN ...` |
| `addAggFunction(Agg, field).add/subtract/multiply/divide(field).as(alias)` | Fluent aqreqat riyazi zənciri — `SUM(f1 op f2 ...)` |
| `addAggFunctionWithMath(Agg, field, MathOp, field, alias)` | `SUM(f1 op f2)` — birbaşa 2 sahə |
| `addAggFunctionOnComputed(Agg, ComputedField, alias)` | Çox sahəli/iç-içə ifadə üzərində aqreqat — `SUM(exprA - exprB)` daxil |
| `addSumExpr(alias, e -> e.plus(...).minus(...))` | Oxunaqlı SUM ifadə zənciri (AggExpr) — bax 7.3.3 |
| `addAggExpr(Agg, alias, [round,] e -> ...)` | AggExpr — istənilən aqreqat funksiyası, istəyə bağlı yuvarlama |
| `addLeftJoin(Class, alias).on(...).done()` | Builder LEFT JOIN |
| `addInnerJoin(Class, alias).onFrom(...).done()` | Builder INNER JOIN |
| `.onFrom(fromAlias, fromField, Op, toField)` | JOIN ON ilə Op operatoru |
| `.andOn(field, Op, value)` | JOIN ON-a əlavə dəyər şərti |
| `.andOnEqual / .andOnNotEqual / .andOnGreaterThan / ...` | `andOn` alias-ları (`equal`/`notEqual`/... ilə eyni) |
| `addOrOperation(alias, tableAlias, field, Map).add(...).done()` | OR qrupu filterlər |
| `orGroup(alias).or(...).andBranch(...).add(...).end().done()` | Mürəkkəb OR/AND qruplaması |
| `addSelectAll()` | Əsas entity-nin bütün sütunları (camelCase alias, JOIN sütunları daxil deyil) |
| `addExists(ExistsSpec)` | EXISTS / NOT EXISTS — `ExistsSpec` ilə |
| `addExists(Class)...done()` | EXISTS — inline builder, `ExistsSpec` import lazım deyil |
| `addNotExists(Class)...done()` | NOT EXISTS — inline builder |
| `addHavingExists(Class)...done()` | HAVING EXISTS — aggregate sorğular üçün |
| `addHavingNotExists(Class)...done()` | HAVING NOT EXISTS — aggregate sorğular üçün |
| `addGroupBy(fields...)` | GROUP BY |
| `addOrderBy(field, dir)` | ORDER BY |
| `addOrderBy(sortExpression)` | ORDER BY string format: `"t.field desc, f.field"` |
| `setPage(page, size)` | Səhifələmə |
| `skipCount()` | COUNT işlətmə (rowCount = -1) |
| `onlyCount()` | Yalnız COUNT |
| `fetchMaps()` | `List<Map<String,Object>>` + sətir sayı qaytarır |
| `fetchMapsNullSafe()` | null-safe `List<Map>` + sətir sayı |
| `fetchMapper(RecordMapper)` | Özel mapper ilə `SelectFetchResponse<V>` |
| `fetchInto(Class<V>)` | jOOQ auto-mapping ilə `SelectFetchResponse<V>` |
| `fetchMergedMap()` | Bütün sətirləri tək `Map<String,Object>`-ə birləşdirir |
| `noPagination()` | LIMIT olmadan bütün nəticə |
| `withCount()` | Bütün data + COUNT |
| `reset()` | State-i əl ilə sıfırla |
| `update(setField, setValue)` | Mövcud filtrlərlə UPDATE icra et |
| `getLastRowCount()` | Son `onlyCount()` nəticəsi |

### InsertQueryBuilder metodları

| Metod | Təsvir |
|---|---|
| `new InsertQueryBuilder<>(Class, dsl)` | INSERT builder yaradır |
| `.value(field, value)` | Sütun dəyərini təyin edir (null → atlanır) |
| `.allowNulls()` | Null dəyərləri daxil etməyə icazə verir |
| `.addRow()` | Cari sıranı tamamlayır, yeni boş sıra açır (batch üçün) |
| `.onDuplicateKeyUpdate(field, value)` | ON CONFLICT DO UPDATE SET |
| `.execute()` | INSERT icra edir, dəyişdirilmiş sətir sayı qaytarır |
| `.executeAndReturnId(Class<K>)` | INSERT icra edir, yeni ID qaytarır |
| `.executeBatch()` | Batch INSERT (addRow ilə yığılan sıraları) |
| `.toSQL()` | SQL sətri qaytarır (icrасız) |
| `qf.insert(Class)` | QueryFactory vasitəsilə builder yaradır |

### UpdateQueryBuilder metodları

| Metod | Təsvir |
|---|---|
| `new UpdateQueryBuilder<>(Class, dsl)` | UPDATE builder yaradır |
| `.set(field, value)` | SET dəyərini təyin edir |
| `.where(Specification)` | WHERE şərti (Spec.eq, Spec.in və s.) |
| `.execute()` | UPDATE icra edir, dəyişdirilmiş sətir sayı qaytarır |
| `.toSQL()` | SQL sətri qaytarır (icrасız) |
| `qf.update(Class)` | QueryFactory vasitəsilə builder yaradır |

### ExistsSpec metodları

| Metod | Təsvir |
|---|---|
| `ExistsSpec.exists(Class)` | EXISTS başlatma |
| `ExistsSpec.notExists(Class)` | NOT EXISTS başlatma |
| `.joinField(existsField, mainAlias, mainField)` | EXISTS ↔ ana cədvəl korrelyasiyası |
| `.filter(field, Op, value)` | EXISTS daxili literal filter |
| `.orGroup()` | OR/AND qruplaması başlatır |
| `  .or(field, Op, value)` | Sadə OR şərt |
| `  .andBranch(alias)` | AND alt-qrupu |
| `    .add(field, Op, value)` | AND qrupuna field əlavə edir |
| `  .end()` | andBranch-ı bağlayır |
| `.done()` | orGroup-u bağlayır, ExistsSpec-ə qayıdır |

### JooqExistsBuilder metodları (inline builder)

`addExists(Class)` / `addNotExists(Class)` / `addHavingExists(Class)` ilə açılır.

| Metod | Təsvir |
|---|---|
| `.joinField(existsField, mainAlias, mainField)` | EXISTS ↔ ana cədvəl korrelyasiyası |
| `.filter(field, Op, value)` | Hər hansı `Op` ilə filter |
| `.equal(field, value)` | `WHERE field = value` |
| `.notEqual(field, value)` | `WHERE field != value` |
| `.in(field, Collection<?>)` | `WHERE field IN (...)` |
| `.in(field, Object...)` | `WHERE field IN (...)` — varargs |
| `.notIn(field, Collection<?>)` | `WHERE field NOT IN (...)` |
| `.like(field, value)` | `WHERE field LIKE '%val%'` |
| `.isNull(field)` | `WHERE field IS NULL` |
| `.isNotNull(field)` | `WHERE field IS NOT NULL` |
| `.orGroup()` | OR/AND qruplaması başlatır |
| `  .or(field, Op, value)` | Sadə OR şərt |
| `  .andBranch(alias)` | AND alt-qrupu |
| `    .add(field, Op, value)` | AND qrupuna field əlavə edir |
| `  .end()` | andBranch-ı bağlayır, orGroup-a qayıdır |
| `  .done()` | orGroup-u bağlayır, `JooqExistsBuilder`-ə qayıdır |
| `.done()` | EXISTS-i tamamlayır, `JooqManager`-ə qayıdır |

> **Qeyd:** Bütün filter metodlarında null / boş dəyər avtomatik atlanır — şərt əlavə edilmir.
