# jooqsqlgenerate — Tam Dokumentasiya

> Bu sənəd kitabxananın hər sinfini, niyə yaradıldığını, içindəki məntiqi və necə istifadə
> ediləcəyini sadə dillə izah edir.

---

## Ümumi Mənzərə — Bu Kitabxana Nə Edir?

Adətən Java-da verilənlər bazasına sorğu yazmaq üçün ya SQL sətiri yazırıq
(`"SELECT * FROM users WHERE status = ?"`) ya da Spring Data JPA kimi bir ORM işlədirik.

Bu kitabxana fərqli bir yol seçir: **jOOQ** adlı kitabxananın üzərində, **dinamik sorğular**
qurmaq üçün bir çərçivə (framework) yaradır. Məqsəd — REST endpoint-dən gələn
parametrlərdən asılı olaraq WHERE, JOIN, GROUP BY, ORDER BY hissələri dəyişən sorğular
yazmağı asanlaşdırmaq.

```
[Developer kodu]
     ↓
JooqQuery / JooqManager   ← bu kitabxana
     ↓
SelectQueryBuilder         ← sorğunu addım-addım qurur
     ↓
EntityTable                ← Java class-ını SQL cədvəlinə çevirir
     ↓
jOOQ DSL                   ← tip-təhlükəli SQL yaradır
     ↓
[PostgreSQL / MySQL / H2]
```

---

## Siniflərin Xəritəsi

```
az.mbm.jooqsqlgenerate
│
├── JooqManager.java          ← Spring @Component — köhnə API
├── JooqManager.java          ← Spring bean facade — fluent API giriş nöqtəsi
├── JooqManagerFactory.java   ← JooqManager factory (Spring bean)
├── JooqQuery.java            ← Spring-dən asılı olmayan aşağı səviyyəli API
├── JooqExistsBuilder.java        ← Inline EXISTS builder (addExists...done())
├── JooqExistsOrGroupBuilder.java ← EXISTS daxili OR qrupu builder
├── JooqExistsAndBranchBuilder.java ← EXISTS daxili AND alt-qrupu builder
├── JooqCaseBuilder.java          ← Inline CASE WHEN builder (addCase...as())
│
├── core/
│   ├── EntityTable.java      ← JPA annotasiyalarını SQL-ə çevirir
│   ├── SelectTable.java      ← Sorğu nəticəsi + sətir sayı
│   ├── SelectFetchJooq.java  ← Nəticəni Java obyektinə çevirir
│   ├── SelectFetchResponse.java
│   └── SelectFetchMapResponse.java
│
├── builder/
│   ├── SelectQueryBuilder.java   ← SELECT sorğusunu addım-addım qurur
│   ├── AggregateBuilder.java     ← GROUP BY + SUM/COUNT/AVG/MIN/MAX + HAVING EXISTS
│   ├── CaseBuilder.java          ← CASE WHEN ... THEN ... END
│   ├── ComputedField.java        ← (price * qty) - discount kimi ifadələr
│   ├── SubQueryIn.java           ← WHERE id IN (SELECT ...)
│   ├── SubSelectBuilder.java     ← SELECT içində scalar subquery
│   └── UpdateQueryBuilder.java   ← UPDATE sorğusu
│
├── spec/
│   ├── Filter.java           ← Dinamik WHERE builder (null-ları atlar)
│   ├── GlobalFilter.java     ← Xarici sistemdən gələn filterlər üçün builder
│   ├── Specification.java    ← WHERE şərti interfeysi
│   ├── Spec.java             ← Specification-ların fabrikası
│   └── ExistsSpec.java       ← WHERE EXISTS / NOT EXISTS
│
├── strategy/
│   ├── FilterStrategy.java   ← Bir filter əməliyyatının interfeysi
│   └── FilterStrategies.java ← Bütün filterlərin qeydiyyatı (Strategy Pattern)
│
└── enums/
    ├── Op.java                    ← EQUAl, LIKE, IN, BETWEEN, ROUND variantları...
    ├── FilterOperationConstants.java  ← String sabitlər (Map / globalFilter üçün)
    ├── GroupFunction.java             ← SUM, COUNT, AVG, MIN, MAX
    ├── MathOperation.java             ← PLUS, MINUS, MULTIPLY, DIVIDE
    └── ...
```

---

## Siniflərin Ətraflı İzahı

---

### 1. `JooqQuery<T>` — Əsas Giriş Nöqtəsi

**Fayl:** `JooqQuery.java`

**Niyə yaradıldı?**

Əvvəllər `JooqManager` Spring `@Prototype` bean idi — yəni hər dəfə yenisi verilməli idi.
Lakin bir singleton servis içinə `@Autowired` ediləndə Spring onu **bir dəfə** verirdi.
Ardıcıl gələn iki sorğu üçün `columns`, `filters`, `groupBy` siyahıları **qarışırdı** —
birinci sorğunun filterləri ikinci sorğuya da tətbiq olunurdu.

`JooqQuery` bu problemi həll edir: **Spring bean deyil**, `static factory` metodu ilə
hər sorğu üçün yeni nümunə yaradılır. Bütün state (sütunlar, filterlər, join-lər)
yalnız həmin nümunəyə aiddir.

**Üç rejim var:**

```
Entity Mode          →  JooqQuery.from(User.class, "u")
Generated Mode       →  JooqQuery.from(Tables.USERS, "u")
Derived Table Mode   →  JooqQuery.from(selectTable, "sub")
```

**Entity mode** — JPA `@Table`, `@Column` annotasiyalarını oxuyur, `EntityTable` sinfi
vasitəsilə Java field adlarını SQL sütun adlarına çevirir. Reflection işlənir.

**Generated mode** — jOOQ-un `generateJooq` task-ı verilənlər bazası sxemindən avtomatik
`Tables.USERS`, `USERS.ID`, `USERS.FIRST_NAME` kimi siniflər yaradır. Bu rejimdə
reflection yoxdur, hər şey tip-təhlükəlidir — field adı səhv yazılsa kod **kompilyasiya
xətası** verir.

**Derived table mode** — başqa bir `SelectTable` sorğusunu `FROM (SELECT ...) alias`
kimi istifadə edir. Yəni bir sorğunun nəticəsini növbəti sorğunun cədvəli kimi işlədirsən.

**Daxili məntiq:**

`JooqQuery` içindəki bütün `select()`, `filter()`, `groupBy()`, `orderBy()` metodları
**iki modlu** işləyir:

```java
public JooqQuery<T> select(String... cols) {
    if (generatedTable != null) {
        // Generated mode: "firstName" → generatedTable.field("first_name") tapır
        for (String col : cols) {
            Field<?> f = resolveFromTable(generatedTable, fieldPart(col));
            if (f != null) rawSelectFields.add(f);
        }
    } else {
        // Entity mode: "u.firstName" sətri olaraq saxlanılır
        columns.addAll(Arrays.asList(cols));
    }
    return this;
}
```

`execute(dsl)` çağrılanda:
- Entity mode → `SelectQueryBuilder` + `EntityTable` ilə sorğu qurulur
- Generated mode → `executeGenerated()` ilə birbaşa jOOQ DSL işlənir

**Entity mode-da `filter()` — alias həlli:**

`filter("alias.field", op, value)` çağrılanda field adı içindəki nöqtəyə görə
otomatik yönləndirilir:

```
"t1.status"   →  alias="t1" → tableMap["t1"] (Product EntityTable) → status sütunu
"t.fkUnitId"  →  alias="t"  → tableMap["t"]  (main EntityTable)    → fk_unit_id sütunu
"status"      →  alias yox  → main EntityTable                      → status sütunu
```

Bu qaydalar həm **WHERE**, həm də **HAVING** üçün eyni cür işləyir — computed sütun
filterlərində alias prefix yazılsa belə (`"t.averageCost"`), yalnız field hissəsi
(`"averageCost"`) HAVING yoxlamasında istifadə olunur.

---

### 1.1. `selectRound()` — ROUND ilə Sütun Seçimi

`selectRound()` metodu SELECT-ə `ROUND(field, scale) AS alias` sütunu əlavə edir
**VƏ** həmin alias-a tətbiq edilən hər filterin WHERE-də də ROUND ilə işlənməsini
avtomatik təmin edir.

```java
public JooqQuery<T> selectRound(String fieldRef, int scale, String alias)
```

**Necə işləyir?**

1. `selectRound("o.amount", 2, "roundedAmount")` çağrılanda:
   - SELECT-ə `ROUND(o.amount, 2) AS rounded_amount` əlavə olunur
   - Daxili `roundedAliasMap`-ə `"roundedAmount" → {fieldRef, scale}` yazılır

2. Sonradan `.filter("roundedAmount", Op.GREATER_THAN, 100)` çağrılanda:
   - Sistem `roundedAliasMap`-dən həmin alias-ı tapır
   - WHERE-ə `ROUND(o.amount, 2) > 100` yazır — `rounded_amount > 100` DEYİL

**Nümunə:**

```java
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.status")
    .selectRound("o.amount", 2, "roundedAmount")   // ROUND(o.amount, 2) AS rounded_amount
    .filter("roundedAmount", Op.GREATER_THAN, 100)  // WHERE ROUND(o.amount, 2) > 100
    .execute(dsl);
```

Yaranan SQL:
```sql
SELECT o.id, o.status, ROUND(o.amount, 2) AS rounded_amount
FROM orders o
WHERE ROUND(o.amount, 2) > 100
```

**Xəbərdarlıq — ikiqat ROUND:**

`selectRound()` ilə seçilmiş bir sütuna `Op.EQUAL_ROUND_2` kimi ROUND op tətbiq etmə —
bu `ROUND(ROUND(field, 2), 2)` yaradır. `selectRound` ilə birlikdə sadə `Op.EQUAl`,
`Op.GREATER_THAN` kimi adi op-lar istifadə et:

```java
// DÜZGÜN:
.selectRound("o.amount", 2, "roundedAmount")
.filter("roundedAmount", Op.GREATER_THAN, 100)   // → ROUND(o.amount,2) > 100

// YANLIŞ:
.selectRound("o.amount", 2, "roundedAmount")
.filter("roundedAmount", Op.GREATER_THAN_ROUND_2, 100)  // → ROUND(ROUND(o.amount,2),2) > 100
```

---

### 2. `JooqManager` — Spring Wrapper

**Fayl:** `JooqManager.java`

**Niyə yaradıldı?**

Köhnə kodu dəyişməmək üçün saxlanılmışdır. `addColumns()`, `addFilter()`, `setMainTable()`
kimi tanış metodları var. Daxilən hər şeyi `JooqQuery`-yə ötürür — özü heç bir state
saxlamır. `execute()` çağrılanda `JooqQuery.execute(dsl)` çağrılır, sonra `current = null`
— növbəti sorğu üçün cədvəl təzədən qurulmalıdır.

```java
// JooqManager içindəki əsas fikir:
private JooqQuery current;   // bütün state burada

public JooqManager setMainTable(Class<?> entity, String alias) {
    current = JooqQuery.from(entity, alias);  // hər setMainTable-da yeni JooqQuery
    return this;
}

public SelectTable execute() {
    SelectTable result = current.execute(dsl);
    current = null;   // sıfırla — növbəti sorğu üçün təmiz başlayır
    return result;
}
```

**Birbaşa filter metodları — istifadəçi `Filters` sinifini bilməli deyil:**

`Filters` kitabxananın daxili sinifidir. İstifadəçi yalnız `JooqManager` ilə işləyir.
Hər metod daxilən `addFilter(Filters.of().xxx())` çağırır — duplikasiya yoxdur:

```java
jooq.setMainTable(Order.class, "o")
    .addColumns("o.id", "o.name")
    .equal("o.status",    "ACTIVE")
    .like("o.name",       searchName)
    .between("o.createdAt", startDate, endDate)   // Long, null → partial dəstək
    .in("o.roleId",       List.of(1L, 2L, 3L))   // Collection<?>
    .isNull("o.deletedAt")
    .execute();
```

Mövcud `addFilter()` overload-ları tam saxlanılır — geriyə dönük uyğunluq var.

---

### 3. `EntityTable<T>` — JPA Annotasiyalarını SQL-ə Çevirir

**Fayl:** `core/EntityTable.java`

**Niyə yaradıldı?**

Entity mode-da developer `User.class` verir. Amma SQL üçün cədvəl adı, sxema adı,
hər field-in sütun adı lazımdır. `EntityTable` bu məlumatları JPA annotasiyalarından
oxuyur:

```java
@Table(name = "users", schema = "public")
public class User {
    @Column(name = "user_id")
    private Long id;

    @Column(name = "first_name")
    private String firstName;

    private String status;   // → "status" (annotasiya yoxsa field adı istifadə olunur)
}
```

Nəticə: `EntityTable` `"id"` field adından `"user_id"` SQL adını, `"firstName"`-dən
`"first_name"` adını tapır.

**Reflection bahalıdır — cache sistemi:**

Reflection bir dəfə işlənir, nəticə `WeakHashMap`-də saxlanılır. İkinci dəfə eyni
class üçün `EntityTable` yaradılanda annotasiyalar yenidən oxunmur, cache-dən götürülür.

`WeakHashMap` seçilməsinin səbəbi: Spring DevTools hot reload zamanı köhnə sinif
`ClassLoader`-ı dəyişdirir. `WeakHashMap` zəif referansla saxladığından köhnə `Class<?>` obyekti
GC tərəfindən silindikdə cache-dəki entry də avtomatik silinir — **yaddaş sızması olmur**.

**Thread safety:** `ReentrantReadWriteLock` istifadə olunur. Oxuma kilidləri paralel işləyir,
yazma kilidi müstəsnadır. Double-check locking pattern tətbiq edilib:

```
1. Read lock al → cache-ə bax
2. Tapılırsa → qaytar (çox thread paralel bu yolu keçə bilər)
3. Tapılmırsa → Read lock burax, Write lock al
4. Write lock altında yenidən yoxla (başqa thread bizdən əvvəl yaza bilərdi)
5. Yenə yoxdursa → reflection ilə qur, cache-ə yaz
```

---

### 4. `SelectTable` — Sorğu Nəticəsinin Bağlayıcısı

**Fayl:** `core/SelectTable.java`

**Niyə yaradıldı?**

`execute()` iki şey qaytarmalı idi: hazır jOOQ `Select<?>` sorğusu və pagination üçün
ümumi sətir sayı. Java metodları bir şey qaytara bilər, ona görə bu ikisi bir sinifdə
birləşdirildi.

```java
public class SelectTable {
    private final Select<?> query;    // SQL sorğusu (hələ icra olunmayıb)
    private final int       rowCount; // COUNT(*) nəticəsi (pagination üçün)
}
```

**Derived table xüsusiyyəti:**

`asTable(String alias)` metodu bu sorğunu başqa bir sorğunun `FROM` hissəsinə yerləşdirir:

```java
SelectTable activeUsers = JooqQuery.from(USERS, "u")
    .filter(USERS.STATUS.eq("ACTIVE"))
    .noPagination()
    .execute(dsl);

// activeUsers sorğusu indi bir cədvəl kimi işlənir:
Table<?> sub = activeUsers.asTable("sub");
// SQL: FROM (SELECT * FROM users u WHERE u.status = 'ACTIVE') sub
```

---

### 5. `SelectFetchJooq<V>` — Nəticəni Java Obyektinə Çevirir

**Fayl:** `core/SelectFetchJooq.java`

**Niyə yaradıldı?**

`SelectTable` içindəki sorğu hələ icra olunmayıb — yalnız SQL quruluşudur. `SelectFetchJooq`
bu sorğunu icra edir və nəticəni istənilən formata çevirir:

- `fetchCast(sel, User.class)` → `List<User>` (jOOQ auto-mapping)
- `fetchMapper(sel, mapper)` → özel `RecordMapper` ilə
- `fetchMaps(sel)` → `List<Map<String, Object>>` (DTO olmadan sütun adı → dəyər)

---

### 6. `SelectQueryBuilder<T>` — Sorğunu Addım-addım Quran Motor

**Fayl:** `builder/SelectQueryBuilder.java`

**Niyə yaradıldı?**

Entity mode-da `JooqQuery`-nin `execute()` metodu işin əsl çətin hissəsini bu sinfə
ötürür. `SelectQueryBuilder` bütün SELECT, JOIN, WHERE, GROUP BY, ORDER BY, LIMIT
hissələrini götürüb tək bir jOOQ sorğusuna çevirir.

Fluent Builder pattern ilə işləyir — hər metod `this` qaytarır, zəncir yazılır:

```java
SelectQueryBuilder.from(User.class, "u")
    .columns("u.id", "u.name")
    .leftJoin(Order.class, "o").on("id").equalsField("userId")
    .where(Spec.eq("status", "ACTIVE"))
    .orderByDesc("u.createdAt")
    .page(0, 20)
    .build(dsl);
```

`build(dsl)` çağrılanda ardıcıl olaraq:
1. EntityTable yaradılır (cədvəl + sütunlar)
2. SELECT siyahısı toplanır
3. JOIN-lər əlavə olunur
4. WHERE şərtləri birləşdirilir (AND ilə)
5. GROUP BY + aqreqatlar tətbiq edilir
6. HAVING şərtləri əlavə olunur
7. ORDER BY tətbiq edilir
8. COUNT sorğusu icra olunur (pagination üçün)
9. LIMIT / OFFSET əlavə olunur
10. `SelectTable` qaytarılır

---

### 7. `AggregateBuilder` — GROUP BY və Aqreqat Funksiyalar

**Fayl:** `builder/AggregateBuilder.java`

**Niyə yaradıldı?**

GROUP BY + SUM/COUNT/AVG/MIN/MAX kombinasiyaları mürəkkəb olduğundan ayrıca sinifə
çıxarıldı. HAVING şərtlərini də özü idarə edir:

```java
AggregateBuilder.groupBy("u.status", "u.department")
    .sum("o.amount").round(2).as("totalAmount").done()
    .count("o.id").as("orderCount").having(GREATER_THAN, 5).done()
    .avg("o.amount").round(0).as("avgAmount").orderDesc().done()
```

Yaranan SQL:
```sql
SELECT u.status, u.department,
       ROUND(SUM(o.amount), 2)  AS totalAmount,
       COUNT(o.id)               AS orderCount,
       ROUND(AVG(o.amount), 0)  AS avgAmount
FROM users u
JOIN orders o ON ...
GROUP BY u.status, u.department
HAVING COUNT(o.id) > 5
ORDER BY SUM(o.amount) DESC
```

> **Qeyd:** `AggregateBuilder`-dəki `.round(scale)` aqreqat funksiyaya (SUM, AVG...) tətbiq
> olunur. Bu, `selectRound()` metodundan fərqlidir — `selectRound()` adi sətri yuvarlayır.

**EXISTS / NOT EXISTS — HAVING-də:**

`AggregateBuilder` eyni fluent zəncir daxilindən EXISTS şərtlərini HAVING-ə əlavə edə bilir.
Üç daxili sinif bu işi görür — hamısı `AggregateBuilder.java`-nın içindədir:

- **`AggExistsBuilder<T,E>`** — `exists(Class)` / `notExists(Class)` ilə açılır;
  `joinField`, `filter`, shorthand filterlər (`equal`, `in`, `like`, `isNull`…), `orGroup()`, `done()` metodları var.
- **`AggExistsOrGroupBuilder<T,E>`** — `orGroup()` ilə açılır; `or()`, `andBranch()`, `done()` var.
- **`AggExistsAndBranchBuilder<T,E>`** — `andBranch(alias)` ilə açılır; `add()`, `end()` var.

`done()` çağırıldıqda `AggExistsClause` record-u `existsClauses` siyahısına əlavə olunur.
`SelectQueryBuilder.buildGroupBy()` bu siyahıdakı hər clause-u `toCondition()` ilə jOOQ `Condition`-a çevirib HAVING-ə AND ilə birləşdirir.

```java
AggregateBuilder.<Task>groupBy("t.fkRequestId")
    .count("t.id").as("taskCount").done()
    .exists(TaskPermission.class)
        .joinField("fkTaskId", "t", "id")
        .in("fkRoleId", allowedRoles)
        .equal("isActive", true)
    .done()
// → HAVING EXISTS (SELECT 1 FROM task_permission
//                  WHERE fk_task_id = t.id
//                  AND fk_role_id IN (...)
//                  AND is_active = true)
```

---

### 8. `Filter<T>` — Dinamik WHERE Builder

**Fayl:** `spec/Filter.java`

**Niyə yaradıldı?**

REST endpoint-dən gələn parametrlər çox vaxt nullable olur — istifadəçi bəzən `status`
verir, bəzən vermır. `Filter` sinfi null/boş dəyərləri **avtomatik atlayır** — sən
hər parametri ayrıca `if` ilə yoxlamağa məcbur deyilsən:

```java
// status=null, name="Ali" gəldi — yalnız name filter işləyəcək
Specification filter = Filter.of()
    .eq("status", status)    // null → bu sətir tamamilə atlanır
    .like("name", name)      // "Ali" → WHERE LOWER(REPLACE(...)) LIKE '%ali%'
    .in("roleId", roleIds)   // boş list → atlanır
    .build();
```

**`like()`, `startWith()`, `endWith()` — `FilterStrategies` üzərindən işləyir:**

Bu metodlar `FilterStrategies.get(Op.LIKE/START_WITH/END_WITH).apply(field, value)` çağırır.
Buna görə field tipinə görə eyni avtomatik davranış tətbiq olunur:
- String field (`varchar`, `text`) → `LOWER(REPLACE(REPLACE(...))) LIKE '%val%'`
- Numeric field (`bigint`, `integer`) → `CAST(field AS varchar) LIKE '%val%'`

> **Qeyd:** `Filter` sinfi yalnız main table üçündür. JOIN cədvəlinin sahələrini filtreləmək
> üçün `filter("alias.field", op, value)` metodunu birbaşa `JooqQuery` / `JooqManager`
> üzərindən çağır — alias həlli avtomatik aparılır.

---

### 9. `GlobalFilter` — Xarici Sistemdən Gələn Filterlər

**Fayl:** `spec/GlobalFilter.java`

**Niyə yaradıldı?**

Bəzi hallarda filterlər `Map<String, Map<String, String>>` strukturunda gəlir
(REST body, konfiqurasiya faylı, başqa mikroservis). Bu xam map ilə işləmək
çatışmaz, `GlobalFilter` onu fluent builder-ə çevirir:

```java
// Əvvəl belə yazılırdı (çirkin):
Map<String, Map<String, String>> filter = new HashMap<>();
filter.put("equal", Map.of("status", "ACTIVE"));
filter.put("like", Map.of("firstName", "Ali"));

// İndi belə yazılır (oxunaqlı):
GlobalFilter.of()
    .equal("status", "ACTIVE")
    .like("firstName", "Ali")
    .greaterThan("o.amount", "100")
    .between("createdAt", "2024-01-01", "2024-12-31")
    .in("roleId", "1", "2", "3")
    .isNull("deletedAt")
```

Birləşdirmə dəstəyi:
```java
// Bazis filter (hər sorğuda eyni)
GlobalFilter base = GlobalFilter.of().equal("tenantId", tenantId);

// İstifadəçidən gələn filter
GlobalFilter user = GlobalFilter.of().like("name", name).equal("status", status);

// İkisini birləşdir — base-in dəyərləri üstün gəlir
jooq.addGlobalFilter(base.merge(user));
```

---

### 10. `FilterOperationConstants` — String Sabitlər

**Fayl:** `enums/FilterOperationConstants.java`

**Niyə yaradıldı?**

`GlobalFilter` daxilən `Map<String, Map<String, String>>` işlədir, burada açar kimi
`"equal"`, `"like"`, `"greaterThan"` kimi sətirlər istifadə olunur. Bu sətirləri
hər dəfə əllə yazmaq əvəzinə sabit olaraq saxlanılmışdır.

**Əsas sabitlər:**

```java
import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;

// map.put("equal", ...)   → typo riski: "equall" yazsan, runtime xətası
// map.put(EQUAl, ...)     → compile zamanı yoxlanılır
```

**ROUND sabitlər (həndəsə dəqiqliyi üçün):**

Xam `Map` ilə global filter istifadə edərkən ROUND əməliyyatları üçün sabitlər:

```java
// Scale 0 — tam ədədə yuvarlama
FilterOperationConstants.EQUAL_ROUND_0
FilterOperationConstants.GREATER_THAN_ROUND_0
FilterOperationConstants.LESS_THAN_ROUND_0
// ...

// Scale 2 — qiymət / məbləğ üçün (ən çox istifadə olunur)
FilterOperationConstants.EQUAL_ROUND_2
FilterOperationConstants.NOT_EQUAL_ROUND_2
FilterOperationConstants.GREATER_THAN_ROUND_2
FilterOperationConstants.GREATER_THAN_OR_EQUAL_TO_ROUND_2
FilterOperationConstants.LESS_THAN_ROUND_2
FilterOperationConstants.LESS_THAN_OR_EQUAL_TO_ROUND_2
// ... Scale 1, 3, 4 üçün də eyni pattern
```

Cəmi **30 ROUND sabiti** mövcuddur: 6 əməliyyat × 5 miqyas (0–4).

---

### 11. `Op` — Filter Əməliyyatlarının Enum-u

**Fayl:** `enums/Op.java`

**Niyə yaradıldı?**

Bütün filter növlərini (bərabərlik, müqayisə, LIKE, IN, BETWEEN, IS NULL, ROUND...)
tip-təhlükəli şəkildə ifadə etmək üçün. `"equal"` sətri əvəzinə `Op.EQUAl` yazırsan —
yanlış dəyər mümkün deyil.

```java
.filter("status", Op.EQUAl, "ACTIVE")
.filter("age",    Op.GREATER_THAN, 18)
.filter("name",   Op.LIKE, "Ali")
.filter("roleId", Op.IN, List.of(1L, 2L, 3L))
```

**ROUND Op-lar (WHERE ROUND(field, scale) OP value):**

Sütunu `selectRound()` ilə seçmədən birbaşa WHERE-də ROUND tətbiq etmək üçün
(məsələn, hesablanmayan sütunlar üçün global filterdə):

```java
// WHERE ROUND(price, 2) > 9.99
.filter("price", Op.GREATER_THAN_ROUND_2, "9.99")

// WHERE ROUND(score, 0) = 5
.filter("score", Op.EQUAL_ROUND_0, 5)
```

Mövcud ROUND Op-lar (5 miqyas × 6 əməliyyat = 30 dəyər):

| Op | SQL qarşılığı |
|---|---|
| `EQUAL_ROUND_N` | `ROUND(field, N) = value` |
| `NOT_EQUAL_ROUND_N` | `ROUND(field, N) != value` |
| `GREATER_THAN_ROUND_N` | `ROUND(field, N) > value` |
| `GREATER_THAN_OR_EQUAL_TO_ROUND_N` | `ROUND(field, N) >= value` |
| `LESS_THAN_ROUND_N` | `ROUND(field, N) < value` |
| `LESS_THAN_OR_EQUAL_TO_ROUND_N` | `ROUND(field, N) <= value` |

`N` = 0, 1, 2, 3 və ya 4. Nümunə: `Op.GREATER_THAN_ROUND_2`, `Op.EQUAL_ROUND_0`.

**`selectRound()` ilə fərqi:**

| | `selectRound()` | `Op.ROUND_N` |
|---|---|---|
| SELECT-ə təsir | Bəli — `ROUND(field, N) AS alias` əlavə edir | Xeyr |
| WHERE-ə təsir | Alias-a filter tətbiq edildikdə | Birbaşa, həmişə |
| Nə vaxt istifadə et | Yuvarlama nəticəsini göstərmək + filter lazımdırsa | Yalnız WHERE-də ROUND lazımdırsa |

---

### 12. `FilterStrategies` — Hər Filter Növünün İcrasının Qeydi

**Fayl:** `strategy/FilterStrategies.java`

**Niyə yaradıldı?**

**Strategy Pattern** tətbiqi. Hər `Op` dəyərinə müvafiq `FilterStrategy`
(bir funksiyadır: `(Field, value) → Condition`) bağlıdır. Yeni filter növü əlavə etmək
üçün mövcud kodu dəyişmək lazım deyil — sadəcə qeydiyyata əlavə edirsən (Open/Closed
Principle):

```java
// Daxili qeydiyyat (sinif ilk dəfə yüklənəndə bir dəfə işlənir):
register(Op.EQUAl,    (field, val) -> field.eq(coerced(field, val)));
register(Op.LIKE,     (field, val) -> likeReadyField(field).like("%" + likeReadyVal(field, val) + "%"));
register(Op.IN,       (field, val) -> field.in(coercedList(field, val)));
register(Op.BETWEEN,  (field, val) -> field.between(...));

// ROUND variantları — ROUND(field, scale) OP value:
register(Op.GREATER_THAN_ROUND_2,
    (field, val) -> DSL.round((Field<? extends Number>) field, 2).greaterThan(coerced(field, val)));
// ... skala 0, 1, 3, 4 üçün eyni

// Xarici kod yeni əməliyyat əlavə edə bilər:
FilterStrategies.register(Op.MY_CUSTOM_OP,
    (field, val) -> field.contains(val.toString()));
```

**Tip uyğunlaşdırması (coercion):** Əgər `Field` INTEGER tipindədir amma `value` `"25"` sətri
kimi gəlirsə, `coerced()` metodu `"25"` → `25` çevirir. Bu REST-dən gələn string
parametrlər üçün çox vacibdir.

**LIKE üçün field tipinə görə davranış:**

Rəqəm sütunlarında (`bigint`, `integer`, `numeric`) Türk simvolu ('İ', 'I') ola bilməz.
Ona görə LIKE əməliyyatında field tipinə görə fərqli SQL yaranır:

| Field tipi | Yaranan SQL |
|---|---|
| `varchar`, `text` | `LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%val%'` |
| `bigint`, `integer`, `numeric` | `CAST(field AS varchar) LIKE '%val%'` |

Bu məntiqi üç daxili köməkçi metod idarə edir:
- `isStringField(field)` — field `CharSequence` alt-tipi olub olmadığını yoxlayır
- `likeReadyField(field)` — string → `turkishLower(field)`; numeric → `field.cast(String.class)`
- `likeReadyVal(field, val)` — string → `turkishNormalize(val)`; numeric → `val.toString()`

Bu strategiya bütün LIKE axınlarına (WHERE, HAVING, OR filter, SubQuery, SubSelect) tətbiq olunur,
çünki hamısı `FilterStrategies.get(op).apply(field, val)` üzərindən keçir.

---

### 13. `CaseBuilder<T>` — CASE WHEN ... THEN ... END

**Fayl:** `builder/CaseBuilder.java`

**Niyə yaradıldı?**

SQL-də `CASE WHEN status = 'ACTIVE' THEN 'Aktiv' ELSE 'Deaktiv' END AS statusLabel`
kimi ifadələr lazım olduqda:

```java
JooqQuery.from(USERS, "u")
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

### 14. `ComputedField` — Riyazi İfadə Sütunları

**Fayl:** `builder/ComputedField.java`

**Niyə yaradıldı?**

`(price * quantity) - discount` kimi riyazi ifadələri SELECT siyahısına əlavə etmək üçün:

```java
ComputedField.of("o.price")
    .multiply("o.quantity")
    .subtract("o.discount")
    .as("netAmount")
```

Yaranan SQL: `(o.price * o.quantity) - o.discount AS netAmount`

---

### 15. `SubQueryIn` — WHERE field IN (SELECT ...)

**Fayl:** `builder/SubQueryIn.java`

**Niyə yaradıldı?**

Subquery ilə IN filtri üçün:

```java
// WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .inSubQuery("u.id",
        SubQueryIn.from(Order.class, "o")
            .select("o.userId")
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);
```

Composite (çox sahəli) versiyası da var:
```java
// WHERE (u.firstName, u.lastName) IN (SELECT bl.firstName, bl.lastName FROM blacklist bl)
.inSubQuery(new String[]{"u.firstName", "u.lastName"},
    SubQueryIn.from(Blacklist.class, "bl")
        .select("bl.firstName", "bl.lastName")
)
```

---

### 16. `ExistsSpec<T, E>` — WHERE EXISTS (SELECT ...)

**Fayl:** `spec/ExistsSpec.java`

**Niyə yaradıldı?**

`EXISTS` alt-sorğuları üçün — çox vaxt bir entity ilə əlaqəli başqa entity mövcuddursa
filtirləmək lazım olur:

```java
// WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.status = 'PAID')
JooqQuery.from(User.class, "u")
    .exists(
        ExistsSpec.exists(Order.class)
            .joinField("userId", "u", "id")   // o.user_id = u.id
            .filter("status", Op.EQUAl, "PAID")
    )
    .execute(dsl);
```

---

### 17. `Specification<T>` + `Spec` — WHERE Şərtlərinin İnterfeysi

**Fayl:** `spec/Specification.java`, `spec/Spec.java`

**Niyə yaradıldı?**

`Specification` bir WHERE şərtinin interfeysidir — hər şərt `toCondition(EntityTable)`
metodunu implement edir. `Spec` sinfi isə tez-tez lazım olan şərtlər üçün statik factory
metodları verir:

```java
Spec.eq("status", "ACTIVE")          // WHERE status = 'ACTIVE'
    .and(Spec.like("name", "Ali"))    // AND name LIKE '%Ali%'
    .or(Spec.in("roleId", roles))     // OR roleId IN (...)
    .not()                            // NOT (...)
```

Bu, Composite Pattern-dir — şərtlər bir-birinə AND/OR/NOT ilə zəncirlənir.

---

## Tam İstifadə Nümunələri

### Entity mode ilə sadə sorğu

```java
SelectTable result = JooqQuery.from(User.class, "u")
    .select("u.id", "u.firstName", "u.email", "u.status")
    .filter("status", Op.EQUAl, status)       // null-sa atlanır
    .filter("firstName", Op.LIKE, name)       // null-sa atlanır
    .orderBy("u.createdAt", "DESC")
    .page(0, 20)
    .execute(dsl);

List<UserDto> users = new SelectFetchJooq<UserDto>()
    .fetchCast(result, UserDto.class)
    .getList();
```

### Generated mode ilə tip-təhlükəli sorğu

```java
import static com.example.jooq.Tables.*;

SelectTable result = JooqQuery.from(USERS, "u")
    .select(USERS.ID, USERS.FIRST_NAME, USERS.EMAIL)
    .leftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID))
    .select(ORDERS.AMOUNT, ORDERS.CREATED_AT)
    .filter(USERS.STATUS.eq("ACTIVE"))
    .filter(ORDERS.AMOUNT.greaterThan(BigDecimal.valueOf(100)))
    .groupBy(USERS.ID, USERS.FIRST_NAME)
    .orderBy(ORDERS.AMOUNT.sum().desc())
    .page(0, 20)
    .execute(dsl);
```

### Generated mode — string adlarla (dinamik sorğular üçün)

```java
// String adlar da işləyir: "firstName" → USERS.FIRST_NAME avtomatik tapılır
JooqQuery.from(USERS, "u")
    .select("id", "firstName", "status")        // camelCase → snake_case çevrilir
    .filter("firstName", Op.LIKE, searchName)
    .groupBy("department")
    .orderBy("createdAt", "DESC")
    .page(0, 20)
    .execute(dsl);
```

### Derived table — sorğu üzərindən sorğu

```java
// Addım 1: Aktiv istifadəçilər (pagination olmadan)
SelectTable activeUsers = JooqQuery.from(USERS, "u")
    .select(USERS.ID, USERS.FIRST_NAME.as("name"), USERS.DEPARTMENT)
    .filter(USERS.STATUS.eq("ACTIVE"))
    .noPagination()
    .execute(dsl);

// Addım 2: O nəticəni cədvəl kimi istifadə et, JOIN əlavə et, yenidən filter et
Table<?> sub = activeUsers.asTable("sub");

SelectTable finalResult = JooqQuery.from(activeUsers, "sub")
    .select("id", "name", "department")
    .select(ORDERS.AMOUNT)
    .leftJoin(ORDERS, "o", sub.field("id", Long.class).eq(ORDERS.USER_ID))
    .filter("name", Op.LIKE, searchName)
    .filter(ORDERS.AMOUNT.greaterThan(BigDecimal.valueOf(500)))
    .orderBy("name", "ASC")
    .page(pageNumber, pageSize)
    .execute(dsl);
```

Yaranan SQL:
```sql
SELECT sub.id, sub.name, sub.department, o.amount
FROM (
    SELECT u.id, u.first_name AS name, u.department
    FROM users u
    WHERE u.status = 'ACTIVE'
) sub
LEFT JOIN orders o ON sub.id = o.user_id
WHERE sub.name LIKE '%Ali%'
  AND o.amount > 500
ORDER BY sub.name ASC
LIMIT 20 OFFSET 0
```

### GROUP BY + Aqreqat

```java
JooqQuery.from(User.class, "u")
    .leftJoin(Order.class, "o", "id", "userId")
    .groupBy("u.department", "u.status")
    .agg(GroupFunction.SUM,   "o.amount", "totalAmount", 2, null, null, "DESC")
    .agg(GroupFunction.COUNT, "o.id",     "orderCount",  0,
         Op.GREATER_THAN, 5, null)   // HAVING COUNT > 5
    .execute(dsl);
```

### selectRound — Yuvarlama ilə SELECT + Filter

```java
// Entity mode
JooqQuery.from(Order.class, "o")
    .select("o.id", "o.status")
    .selectRound("o.unitCost",  2, "cost")     // ROUND(o.unit_cost, 2) AS cost
    .selectRound("o.quantity",  0, "qty")      // ROUND(o.quantity, 0)  AS qty
    .filter("cost", Op.GREATER_THAN, 9.99)     // WHERE ROUND(o.unit_cost, 2) > 9.99
    .filter("qty",  Op.EQUAl, 10)             // WHERE ROUND(o.quantity, 0) = 10
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

### Global filter ilə ROUND Op

```java
import static az.mbm.jooqsqlgenerate.enums.FilterOperationConstants.*;

// Xam Map ilə (REST body-dən gəlibsə)
Map<String, Map<String, String>> globalFilter = Map.of(
    GREATER_THAN_ROUND_2, Map.of("price", "9.50"),   // WHERE ROUND(price,2) > 9.50
    EQUAL_ROUND_0,        Map.of("score", "5")        // WHERE ROUND(score,0) = 5
);

JooqQuery.from(Product.class, "p")
    .globalFilter(globalFilter)
    .execute(dsl);
```

---

## Arxitektura Qərarlarının Xülasəsi

| Problem | Həll |
|---|---|
| Ardıcıl sorğularda state qarışması | `JooqQuery` — hər sorğu üçün yeni nümunə |
| Reflection hər sorğuda işlənir | `WeakHashMap` + `ReentrantReadWriteLock` cache |
| Hot reload zamanı ClassLoader leak | `WeakHashMap` zəif referansla GC-ə imkan verir |
| Yeni filter növü əlavə etmək | `FilterStrategies.register()` — mövcud kodu dəyişmədən |
| Null parametrlər üçün if yığını | `Filter` sinfi null/boş dəyərləri avtomatik atlar |
| `Filters.of()` wrapping-i artıqdır | `JooqManager` birbaşa `equal`, `between`, `in` metodları — `Filters` gizli qalır |
| `between` null from/to | Yalnız from → `>=`, yalnız to → `<=`, ikisi null → atlanır |
| `in`/`notIn` tip çevrilməsi | `Collection<?>` overload — `List<Long>`, `Set<String>` hamısı `coerced()` ilə DB tipinə uyğunlaşır |
| String `"equal"` yazı xətası riski | `FilterOperationConstants` + `Op` enum |
| Sorğu üzərindən sorğu | `SelectTable.asTable()` + `JooqQuery.from(SelectTable, alias)` |
| Tip-təhlükəsiz sorğular | Generated mode — `USERS.FIRST_NAME`, compile zamanı yoxlanılır |
| JOIN cədvəlinə filter yazmaq | `filter("t1.field", op, value)` — alias avtomatik həll edilir |
| HAVING-də alias prefix | `filter("t.computedAlias", op, value)` — prefix stripped, HAVING-ə düşür |
| SELECT-də ROUND + filter uyğunluğu | `selectRound(field, scale, alias)` — filter-də ROUND avtomatik tətbiq olunur |
| WHERE-də ROUND, SELECT-siz | `Op.GREATER_THAN_ROUND_2` kimi ROUND Op-lar — 30 variant (skala 0–4) |
| LIKE numeric field-də (bigint xətası) | `isStringField()` → `likeReadyField()` + `likeReadyVal()` — tip yoxlaması ilə CAST/REPLACE seçimi |
| `Filter.java` LIKE bypass | `like()` / `startWith()` / `endWith()` `FilterStrategies` üzərindən işləyir — tip yoxlaması daxildir |
