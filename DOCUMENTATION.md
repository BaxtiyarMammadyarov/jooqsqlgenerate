# jooqsqlgenerate — Tam Dokumentasiya

> Bu sənəd kitabxananın hər sinfini, niyə yaradıldığını, içindəki məntiqi və necə istifadə
> ediləcəyini sadə dillə izah edir.

---

## Dəyişikliklər — Versiya Tarixi

### v1.1.31 — Bug fix: CONCAT/COALESCE/`selectAs` sütununa filter qoyanda `column "alias" does not exist`

**Simptom:** generated/derived-table mode-da CONCAT (və ya COALESCE/`selectAs`) sütununa
`.filter()` / `.havingFilter()` / `globalFilter()` ilə şərt qoyulanda Postgres bu xətanı verirdi:

```
ERROR: column "carrierDescription" does not exist
```

**Səbəb:** Kitabxana daxilində CONCAT/COALESCE/`selectAs` ifadələri **artıq `.as(alias)` ilə
alias-lanmış** halda daxili xəritəyə (`aggExprByAlias`) yazılırdı. Bu ifadə sonra HAVING/filter
şərtində təkrar istifadə ediləndə jOOQ onu real ifadə (`CONCAT(...)`) əvəzinə sadəcə `"alias"`
identifikatoru kimi render edirdi — SQL-də isə SELECT alias-ları WHERE/HAVING-də etibarlı deyil,
ona görə Postgres "column does not exist" deyirdi. (Aqreqat (`SUM`/`COUNT`/...) və
`addComputedColumn` sütunları bu səhvə düşmürdü, onlar düzgün qurulmuşdu.)

**Düzəliş:** CONCAT, COALESCE və `selectAs` sütunları üçün də daxili xəritəyə **alias-sız (raw)**
ifadə yazılır, `.as(alias)` yalnız SELECT siyahısında tətbiq olunur — aqreqat sütunlarla eyni
qaydaya salındı. Nəticədə HAVING/filter indi real ifadəni təkrarlayır, alias-a istinad etmir.

**Kimə təsir edir:** `concat`/`concatColumn`/`addConcatColumn`, `coalesceColumn`/
`addCoalesceColumn`, `selectAs`, və `addComputedColumn(field).add()...as(alias)` zənciri
(daxili `.filter()` ilə birgə) — bu sütunlara generated/derived-table mode-da filter qoyan
bütün hallar. İstifadəçi tərəfində heç bir API dəyişikliyi yoxdur — sadəcə düzgün SQL yaranır.

---

### v1.1.30 — Yeni feature: `concatColumn(...).sep(...).as(...)` (fluent, join-style concat)

**Motivasiya:** Mövcud `addConcatColumn(alias, sep, fields...)` sadə və importsuz idi, amma
JOIN builder-lərdəki kimi fluent/sıralı yazı tərzi yox idi — istəyirdik ki birbaşa
`concatColumn(fields...)` ilə başlayıb `.as(alias)` ilə bitirə bilək.

```java
jooq.concatColumn("u.firstName", "u.lastName")
    .sep(" ")
    .as("fullName")
// → COALESCE(firstName,'') || ' ' || COALESCE(lastName,'') AS fullName
```

**Niyə ad `addConcatColumn` deyil:** Yeni overload-u məhz `addConcatColumn(String...)` adı ilə
əlavə etmək mövcud `addConcatColumn(String alias, String separator, String... fields)` ilə
**Java compile-time ambiguity** yaradırdı (2+ String arqumentli çağırışda hər iki metod
variable-arity baxımından uyğun gəlir → "ambiguous method call" compile xətası). Ona görə
fərqli ad seçildi: `concatColumn(String... fields)` — nəticədə `ConcatSetup` adlı kiçik
fluent builder qaytarır (JOIN-lərdəki `addLeftJoin(...)` → `JoinBuilder`/`done()` modelinə bənzər).

**Yeni siniflər/metodlar:**
- `JooqQuery.concatColumn(String...)` → `JooqQuery.ConcatSetup` (`.sep(...)`, `.as(...)`)
- `JooqManager.concatColumn(String...)` → `JooqManager.ConcatSetup` (`.sep(...)`, `.as(...)`)
- `.as(alias)` tamamlayıcı çağırışdır: daxilən mövcud `concat(alias, sep, fields...)` /
  `addConcatColumn(alias, sep, fields...)`-ə delegate edir və parent obyektə (`JooqQuery<T>` /
  `JooqManager`) qayıdır.

Tamamilə əlavədir — mövcud `String...`/`List<String>`/`ConcatItem...` overload-larına
heç bir təsiri yoxdur, sadəcə üçüncü (fluent) yazı tərzi əlavə olundu.

---

### v1.1.28 — CONCAT/COALESCE tip xətası, WHERE filter generated-mode düzəlişi, köhnə metodlar @Deprecated

**1) Bug fix: `COALESCE` tip uyğunsuzluğu (`COALESCE types ... cannot be matched`)**

**Problem:** `concat()` və `coalesce()` daxilində PostgreSQL-ə göndərilən `COALESCE(field, literal)`
ifadəsində `field` mətn olmayan tipdə (Long, Integer, Date və s.) olduqda, literal isə mətn
(`''` və ya String default) olduqda Postgres xəta verirdi:
```
ERROR: COALESCE types bigint and character varying cannot be matched
```
Səbəb: Postgres `||` (concat) operatorunda literalı avtomatik cast edir, amma `COALESCE` daxilində
YOX — hər iki tərəfin tipi dəqiq uyğun olmalıdır.

**Həll:** Mətn literalı ilə qarşılaşan hər sahə əvvəlcə `field.cast(String.class)` ilə cast edilir.
- `concat()` həmişə mətn yaratdığı üçün — sahə **həmişə** cast olunur.
- `coalesce()`-də yalnız default dəyər `String` olduqda cast tətbiq olunur (rəqəm-default +
  rəqəm-sahələr halı pozulmasın deyə).

**Düzəlişin tətbiq olunduğu yerlər (7 yer, 4 fayl):**
| Fayl | Metod |
|---|---|
| `builder/SelectQueryBuilder.java` | `buildConcatField`, `buildCoalesceField` |
| `JooqQuery.java` | generated-mode CONCAT bloku, generated-mode COALESCE bloku |
| `builder/CoalesceExpr.java` | `toField`, `toFieldGenerated` |
| `builder/SubSelectBuilder.java` | `buildSelectExpr` — CONCAT və COALESCE case-ləri |

**2) Bug fix: generated mode-da `.filter()` sahələri sükutla itirdi**

**Problem:** Generated mode-da `filter(field, op, value)` çağırışı sahəni DƏRHALA (eager) resolve
etməyə çalışırdı, amma `joinTableRegistry` (Class-based JOIN-lər üçün) və `aggExprByAlias`
(concat/coalesce/selectAs alias-ları üçün) yalnız `executeGenerated()` işə düşəndə tam dolur —
yəni bütün `.filter()` çağırışlarından SONRA. Nəticədə uyğun sahə tapılmayanda filter heç bir
xəta vermədən sükutla atılırdı, WHERE şərti SQL-ə düşmürdü.

**Həll:** Sahə həll olunması `executeGenerated()`-in sonuna təxirə salındı (`deferredWhereFilterRows`)
— elə vaxta ki bütün registry-lər artıq tam dolu olsun. Əlavə fayda: indi WHERE filterini
concat/coalesce/selectAs kimi hesablanmış alias-lara da tətbiq etmək mümkündür (əvvəl mümkün deyildi).

**3) Köhnə metodların `@Deprecated` edilməsi**

Aşağıdaki metodlar daha sonra yazılmış, daha güclü əvəzedicisi olduğu üçün `@Deprecated`
nişanlandı. **Hələ də işləyirlər**, sadəcə tövsiyə olunmur — geriyə dönük uyğunluq pozulmayıb.
Ümumi məqsədli metodlar (`addFilter`, `addCoalesceColumn`, `addComputedColumn` və s.) toxunulmadı.

| Köhnə (deprecated) | Əvəzedici | Səbəb |
|---|---|---|
| `JooqQuery.from(Class, String)` | `from(Table, String)` / `from(SelectTable, String)` | Entity-reflection əsaslı, generated mode-un tip-təhlükəsizliyi yoxdur |
| `JooqQuery.leftJoin/innerJoin(Class, alias, from, to)` | fluent `leftJoin(Class, alias)` / `innerJoin(Class, alias)` | Yalnız tək ON cütünə icazə verir |
| `JooqQuery.leftJoin/innerJoin(SelectTable, alias, from, to)` | fluent `leftJoin(SelectTable, alias)` / `innerJoin(SelectTable, alias)` | Eyni səbəb |
| `JooqManager.setMainTable(Class, String)` | `setMainTable(Table, String)` / `setMainTable(SelectTable, String)` | Entity-reflection əsaslı |
| `JooqManager.addLeftJoin/addInnerJoin(Class, alias, from, to)` | `addLeftJoin(Class, alias)` / `addInnerJoin(Class, alias)` (fluent `JoinSetup`) | Tək ON cütü ilə məhdud |
| `JooqManager.addLeftJoin/addInnerJoin(SelectTable, alias, from, to)` | `addLeftJoin(SelectTable, alias)` / `addInnerJoin(SelectTable, alias)` (fluent `SelectJoinSetup`) | Eyni səbəb |

> **Qeyd (düzəliş):** `concat(alias, sep, String...)` / `addConcatColumn(alias, sep, String...|List<String>)`
> overload-ları **geri DEPRECATE EDİLMƏDİ**. İlk planda bunlar `ConcatItem...` versiyasına görə
> köhnə sayılmışdı, amma `ConcatItem` `builder` (daxili) paketindədir — istifadəçinin onu
> import etməsini tələb etmək kitabxananın "daxili siniflər JooqManager arxasında qalsın"
> prinsipinə zidd idi. Sadə "sütunları birləşdir" halı (literal qarışdırmadan) ən çox işlənən
> hal olduğu üçün `addFilter` kimi ümumi/əsas metod sayılır və `String...` overload-u
> dəyişdirilmədən qalır. `ConcatItem...` versiyası yalnız literal/CASE/COALESCE qarışdırmaq
> lazım olanda əlavə seçim kimi qalır — biri digərini "əvəz etmir".

---

### v1.1.12 — JOIN alias dəstəyi, orderBy düzəlişi, andOnEqual/andOnNotEqual

**Düzəlişlər:**

- **`addOrderBy("tax.accountNo")`** — entity mode-da join table alias-ı (`tax`, `i`, vs.) ilə verilən ORDER BY sahəsi artıq düzgün table-dan resolve edilir. Əvvəl main table-da axtarıb `Field not found` xətası verirdi.
- **`andOn("tax.status", Op.EQUAL, "A")`** — `andOn`-da alias ilə field verilə bilər. Əvvəl yalnız join table-ın öz sahəsi (alias-sız) dəstəklənirdi.
- **`onFrom("i.fkTaxIndicatorId", "tax.id")`** — `onFrom`-un ikinci parametrində `alias.field` formatı dəstəklənir. Əvvəl ikinci parametr həmişə join table-ın sadə field adı kimi qəbul edilirdi.

**Yeni metodlar (`JoinBuilder`):**
- `andOnEqual(field, value)` — `equal()` ilə eynidir, daha oxunaqlı ad
- `andOnNotEqual(field, value)` — `notEqual()` ilə eynidir, daha oxunaqlı ad

**İstifadə nümunəsi:**
```java
factory.create()
    .setMainTable(IncomeFlowEntity.class, "t")
    .addInnerJoin(IncomeItem.class, "i")
        .onFrom("t.fkIncomeItemId", "i.id")   // ikinci parametrdə alias
        .andOnEqual("i.status", "A")            // alias ilə andOn
        .done()
    .addInnerJoin(ProfitTaxIndicator.class, "tax")
        .onFrom("i.fkTaxIndicatorId", "tax.id")
        .andOnEqual("tax.status", "A")
        .done()
    .addOrderBy("tax.accountNo", "asc")         // join table field-i ilə orderBy
    .fetch();
```

---

### v1.1.11 — NullDefault: LEFT JOIN null idarəetməsi

**Problem:** LEFT JOIN edilmiş cədvəldə uyğun sətir olmadıqda həmin cədvəlin bütün sahələri `NULL` qaytarır. Riyazi ifadədə (`computedColumn`, `ComputedField`) hər hansı operand `NULL` olduqda SQL nəticəsi bütünlüklə `NULL` olur.

**Həll:** Eksplisit `NullDefault` API — framework avtomatik coalesce etmir, istifadəçi özü seçir.

**Yeni enum `NullDefault`:**
- `ZERO` — `COALESCE(field, 0)` — ADD/SUBTRACT üçün tövsiyə edilir
- `ONE`  — `COALESCE(field, 1)` — nadir hallarda
- `NONE` — COALESCE tətbiq edilmir, DB davranışı (default)

**`ComputedField`-ə əlavə edilən metodlar:**
- `.withNullDefault(NullDefault)` — bütün zəncirlərə eyni default
- `.addNullAs(field, nullAs)` — `+ COALESCE(field, nullAs)`
- `.subtractNullAs(field, nullAs)` — `- COALESCE(field, nullAs)`
- `.multiplyNullAs(field, nullAs)` — `* COALESCE(field, nullAs)`
- `.divideNullAs(field, nullAs)` — `/ COALESCE(field, nullAs)` + auto `NULLIF(denom, 0)`

**`computedColumn` — yeni overload:**
- `JooqQuery.computedColumn(alias, ta1, op, f1, ta2, f2, NullDefault)`
- `SelectQueryBuilder.computedColumn(alias, alias1, op, f1, alias2, f2, NullDefault)`

---

### v1.1.10 — IfExpr, CoalesceExpr, şərtli zəncir metodları

**Nə əlavə edildi?**

SQL `CASE WHEN` və `COALESCE` ifadələrini **ComputedField**, **AggregateBuilder** və **ConcatItem** daxilindən istifadə etmək üçün tam dəstək əlavə edildi.

**Yeni siniflər:**
- `IfExpr` — `CASE WHEN field=val THEN x ELSE y END` ifadəsi; standalone istifadə üçün
- `CoalesceExpr` — `COALESCE(f1, f2, ..., default)` ifadəsi; standalone istifadə üçün

**ComputedField-ə əlavə edilən metodlar:**
- `ComputedField.ifExpr(cond, eq, then, else)` — CASE WHEN-dən başlayan zəncir
- `ComputedField.coalesce(fields...)` — COALESCE-dən başlayan zəncir
- `.multiplyIf(cond, eq, then, else)` — `field * CASE WHEN ...`
- `.addIf(cond, eq, then, else)` — `field + CASE WHEN ...`
- `.subtractIf(cond, eq, then, else)` — `field - CASE WHEN ...`
- `.divideIf(cond, eq, then, else)` — `field / CASE WHEN ...`

**AggregateBuilder-ə əlavə edilən metodlar:**
- `sumIf`, `countIf`, `avgIf`, `maxIf`, `minIf` — `SUM/COUNT/AVG/MAX/MIN(CASE WHEN ...)`
- `AggStep.multiplyIf`, `addIf`, `subtractIf`, `divideIf` — `SUM(f * CASE WHEN ...)`

**ConcatItem-ə əlavə edilən tiplər:**
- `ConcatItem.ifExpr(...)` — CONCAT daxilində CASE WHEN ifadəsi
- `ConcatItem.coalesce(CoalesceExpr)` — CONCAT daxilində COALESCE

---

### v1.1.9 — CAST dəstəyi

**Nə əlavə edildi?**

Sütunların SQL tipini dəyişdirmək üçün (INTEGER → VARCHAR, VARCHAR → INTEGER, tarix → string və s.) tam CAST dəstəyi əlavə edildi.

**`SelectQueryBuilder` — sadə select üçün:**
- `castString(field, alias)` — `CAST(field AS VARCHAR)`
- `castLong(field, alias)` — `CAST(field AS BIGINT)`
- `castInteger(field, alias)` — `CAST(field AS INTEGER)`
- `castBigDecimal(field, alias)` — `CAST(field AS NUMERIC)`
- `castDateTime(field, pattern, alias)` — tarix/vaxt formatı, bütün DB-lərlə uyğun
- `castColumn(field, DataType, alias)` — istənilən `SQLDataType` ilə aşağı səviyyəli metod

**`ComputedField` — hesablama zəncirinə cast:**
- `castToString()`, `castToLong()`, `castToInteger()`, `castToBigDecimal()` — tip cast
- `castToDateTime(pattern)` — tarix/vaxt format
- `castTo(DataType)` — ixtiyari tip

**`DateFormatHelper` — yeni yardımçı sinif:**
- `dsl.dialect()` ilə DB-ni avtomatik tanıyır
- PostgreSQL/Oracle → `TO_CHAR(field, 'pattern')`
- MySQL/MariaDB → `DATE_FORMAT(field, '%Y-%m-%d')`
- SQL Server → `FORMAT(field, 'yyyy-MM-dd')`
- Pattern avtomatik çevrilir — bir dəfə yazırsınız, hər DB-də işləyir

### v1.1.8 — REGEXP/NOT_REGEXP düzəlişi
**Problem:** `Op.REGEXP` və `Op.NOT_REGEXP` filterlərinə `List<String>` dəyər verildikdə
Java-nın `List.toString()` onu `[isMusteri]` formatına çevirirdi. PostgreSQL regex-ində
`[isMusteri]` bir **character class** kimi işlənir (i, s, M, u, t, e, r hərflərindən biri),
nəticədə demək olar bütün sətirləri sızdırırdı.

**Həll:** `FilterStrategies.java`-ya `toRegexPattern()` köməkçi metodu əlavə edildi:
- `List["isMusteri"]` → `"isMusteri"` (brackets silinir)
- `List["isMusteri", "isTechizatci"]` → `"isMusteri|isTechizatci"` (regex OR)
- `"isMusteri,isTechizatci"` (vergüllü string) → `"isMusteri|isTechizatci"`

**NOT_REGEXP SQL düzəlişi:** `DSL.not(field.likeRegex(...))` əvəzinə
`field.notLikeRegex(...)` istifadə olunur:
```sql
-- Əvvəl: NOT (("t"."col" ~ 'pattern'))   ← ikiqat mötərizə
-- İndi:  "t"."col" !~ 'pattern'          ← təmiz PostgreSQL sintaksisi
```

### v1.1.7
- Əvvəlki buraxılış

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

---

#### 2.1 Sütun seçimi

**`addColumns`** — SELECT-ə sütun əlavə edir.

```java
// String varargs
jooq.addColumns("t.id", "t.name", "t1.productName");

// List<String>
jooq.addColumns(List.of("t.id", "t.name"));

// jOOQ Field varargs (generated mode)
jooq.addColumns(USERS.ID, USERS.FIRST_NAME);

// List<Field<?>>
jooq.addColumnFields(List.of(USERS.ID, USERS.FIRST_NAME));
```

**`addSelectAs`** — sütuna özəl çıxış alias verir (entity mode).

```java
// "t1.fkProductId" sütununu "productId" adı ilə SELECT-ə əlavə edir
jooq.addSelectAs("t1.fkProductId", "productId")
    .addSelectAs("t.operationDate",  "date");
```

**`addRawSelectField`** — birbaşa jOOQ `Field<?>` əlavə edir.

```java
jooq.addRawSelectField(DSL.val("literal").as("constCol"));
jooq.addRawSelectField(DSL.currentDate().as("today"));
```

**`setDistinct`** — `SELECT DISTINCT` aktivləşdirir.

```java
jooq.setMainTable(Order.class, "o")
    .addColumns("o.status")
    .setDistinct()
    .execute();
// → SELECT DISTINCT o.status FROM orders o
```

---

#### 2.2 Computed sütunlar

**`addComputedColumn` — 3 parametrli (sadə ikitərəfli riyaziyyat)**

```java
// (tableAlias1.field1) OP (tableAlias2.field2) AS alias
jooq.addComputedColumn("netAmount", "t", "price", MathOp.MULTIPLY, "t", "qty");
// → t.price * t.qty AS net_amount
```

**`addComputedField(ComputedField cf)` — hazır `ComputedField` obyekti ilə**

```java
ComputedField expr = ComputedField.of("t.price")
    .multiply("t.qty")
    .subtract("t.discount")
    .as("netAmount");
jooq.addComputedField(expr);
```

**`addComputedColumn(String field)` — fluent zəncir (ən çox istifadə olunan)**

Bir sahədən başlayıb riyazi zəncir qurursan, `.as(alias)` ilə tamamlayırsan:

```java
// Sadə:
jooq.addComputedColumn("t.price")
    .add("t.tax")
    .subtract("t.discount")
    .as("finalPrice");
// → (t.price + t.tax) - t.discount AS final_price

// Multiply ilə:
jooq.addComputedColumn("t.totalPriceIn")
    .add("t.totalPriceOut")
    .as("totalPriceValue");
```

**`ComputedGroupChain` — mötərizəli alt-ifadə**

`.add()`, `.subtract()`, `.multiply()`, `.divide()` metodlarını arqumentsiz çağırsan —
bu yeni bir `ComputedGroupChain` açır. `.of(field)` ilə qrupun içindəki ilk sahəni
təyin edirsən, `.done()` ilə mötərizəni bağlayıb əsas zəncirə qayıdırsan:

```java
jooq.addComputedColumn("t.total_Price_In")
    .subtract("t.total_Price_Out")
    .multiply("t.rate")
    .subtract().of("t.purchase_Expense").multiply("t.count").done()
    .as("profit");
// → ((t.total_price_in - t.total_price_out) * t.rate)
//     - (t.purchase_expense * t.count) AS profit
```

---

#### 2.3 Xüsusi SELECT sütunları

**`addCoalesceColumn`** — `COALESCE(field1, field2, ..., default) AS alias`

```java
// Birinci null olmayan dəyəri götürür, hamısı null-sa default qaytarır
jooq.addCoalesceColumn("displayName", "N/A", "t.fullName", "t.username");
// → COALESCE(t.full_name, t.username, 'N/A') AS display_name
```

**`addConcatColumn`** — `CONCAT` sütunu, ayırıcı ilə birləşdirmə

```java
// String varargs:
jooq.addConcatColumn("fullName", " ", "t.firstName", "t.lastName");
// → CONCAT(t.first_name, ' ', t.last_name) AS full_name

// List<String>:
jooq.addConcatColumn("fullName", " ", List.of("t.firstName", "t.lastName"));

// ConcatItem — field + literal qarışıq:
jooq.addConcatColumn("label", "",
    ConcatItem.field("t.code"),
    ConcatItem.literal(" - "),
    ConcatItem.field("t.name"));
// → CONCAT(t.code, ' - ', t.name) AS label
```

**`addSubQueryColumn`** — SELECT içində scalar subquery sütunu

```java
jooq.addSubQueryColumn(
    SubSelectBuilder.from(OrderLine.class, "ol")
        .select("ol.amount")
        .filter("fkOrderId", Op.EQUAl, "o.id")
        .as("lineAmount")
);
// → (SELECT ol.amount FROM order_lines ol WHERE ol.fk_order_id = o.id) AS line_amount
```

---

#### 2.4 JOIN metodları

**Sadə 1 ON şərti ilə JOIN:**

```java
// LEFT JOIN — entity mode, tək cüt
jooq.addLeftJoin(Product.class, "t1", "fkProductId", "id");
// → LEFT JOIN products t1 ON t.fk_product_id = t1.id

// INNER JOIN
jooq.addInnerJoin(Category.class, "c", "fkCategoryId", "id");
```

**Fluent `JoinSetup` builder — çoxlu ON şərtləri:**

```java
jooq.addLeftJoin(Product.class, "t1")
    .on("fkProductId", "id")           // t.fk_product_id = t1.id
    .on("companyId",   "companyId")    // t.company_id    = t1.company_id
    .andOn("status", Op.EQUAl, "A")   // AND t1.status = 'A'
    .done()
    .addColumns("t1.productName");

// onFrom — başqa cədvəlin alias-ından ON şərti:
jooq.addLeftJoin(Company.class, "c")
    .onFrom("u.fkCompanyId", "id")     // u.fk_company_id = c.id
    .equal("isActive", true)           // AND c.is_active = true
    .done();

// onFrom dot-notation + operator:
jooq.addInnerJoin(RequestEntity.class, "r")
    .onFrom("t", "fkRequestId", Op.EQUAl, "id")
    .andOn("status", Op.EQUAl, "A")
    .done();
```

`JoinSetup`-da mövcud shorthand metodlar: `equal`, `notEqual`, `greaterThan`,
`greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`, `isNull`, `isNotNull`.

**Generated mode JOIN (tip-təhlükəli):**

```java
jooq.addLeftJoin(ORDERS, "o", USERS.ID.eq(ORDERS.USER_ID));
jooq.addInnerJoin(ROLES,  "r", USERS.ROLE_ID.eq(ROLES.ID));
```

**`SelectTable` ilə JOIN (derived table üzərinə):**

```java
// Sadə:
jooq.addLeftJoin(budgetQuery, "b", "f.fkAccountId", "fkAccountId");

// Fluent builder:
jooq.addLeftJoin(budgetQuery, "b")
    .on("f.fkAccountId",   "fkAccountId")
    .on("f.fkCurrencyId",  "fkCurrencyId")
    .andOn("status", Op.EQUAl, "A")
    .done();

// Raw Condition:
jooq.addLeftJoin(subQuery, "sub", DSL.condition("sub.id = t.fk_sub_id"));
```

---

#### 2.5 WHERE filterlər

**Birbaşa filter metodları** — `Filters.of()` yaratmadan istifadə:

```java
jooq.setMainTable(Order.class, "o")
    .equal("o.status",    "ACTIVE")         // WHERE o.status = 'ACTIVE'
    .notEqual("o.type",   "DRAFT")          // AND o.type != 'DRAFT'
    .like("o.name",       searchName)       // AND o.name LIKE '%...%'
    .startWith("o.code",  prefix)           // AND o.code LIKE 'prefix%'
    .endWith("o.ref",     suffix)           // AND o.ref  LIKE '%suffix'
    .greaterThan("o.amount", 100)           // AND o.amount > 100
    .greaterThanOrEqual("o.amount", 100)    // AND o.amount >= 100
    .lessThan("o.amount", 500)              // AND o.amount < 500
    .lessThanOrEqual("o.amount", 500)       // AND o.amount <= 500
    .between("o.createdAt", from, to)       // AND o.created_at BETWEEN ... AND ...
    .in("o.roleId",   List.of(1L, 2L))      // AND o.role_id IN (1, 2)
    .notIn("o.status", List.of("DEL"))      // AND o.status NOT IN ('DEL')
    .isNull("o.deletedAt")                  // AND o.deleted_at IS NULL
    .isNotNull("o.confirmedAt")             // AND o.confirmed_at IS NOT NULL
    .execute();
```

`between` null dəstəyi: yalnız `from` → `>= from`; yalnız `to` → `<= to`; ikisi null → atlanır.

**`addFilter` overload-ları:**

```java
// Op enum ilə (hər cədvəl üçün — alias prefix desteklenir):
jooq.addFilter("t.status", Op.EQUAl, status);        // null → atlanır
jooq.addFilter("t.roleId", Op.IN,    roleIds);        // boş kolleksiya → atlanır

// Generated Field ilə (tip-təhlükəli):
jooq.addFilter(USERS.STATUS, Op.EQUAl, "ACTIVE");

// Birbaşa jOOQ Condition:
jooq.addFilter(USERS.AGE.gt(18).and(USERS.DELETED_AT.isNull()));

// Filters fluent builder:
jooq.addFilter(
    Filters.of()
        .equal("status", "ACTIVE")
        .like("name",    name)
        .greaterThan("o.amount", "100")
);

// Map<String, String> — field + əməliyyatlar:
jooq.addFilter("o.amount", Map.of("greaterThan", "100", "lessThan", "500"));

// Map<String, Map<String,String>> — JSON body-dən:
jooq.addFilter(request.getFilterMap());
// {"price": {"greaterThan": "10"}, "status": {"equal": "ACTIVE"}}
```

**`addFieldFilter`** — iki field arasında müqayisə (sabit deyil, başqa sütunla):

```java
// WHERE t.priceIn > t.priceOut
jooq.addFieldFilter("t.priceIn", Op.GREATER_THAN, "t.priceOut");

// WHERE t.startDate <= t.endDate
jooq.addFieldFilter("t.startDate", Op.LESS_THAN_OR_EQUAL_TO, "t.endDate");
```

**`addRawCondition`** — birbaşa jOOQ `Condition`:

```java
jooq.addRawCondition(DSL.condition("t.amount > t.min_amount"));
```

---

#### 2.6 OR qrupları

**`addOrFilter` — sadə OR qrupu:**

```java
// WHERE t.status = 'A' AND (t.actionType = 'IN' OR t.actionType = 'OUT')
jooq.addFilter("t.status", Op.EQUAl, "A")
    .addOrFilter("myOr", "t.actionType", Op.EQUAl, "IN")
    .addOrFilter("myOr", "t.actionType", Op.EQUAl, "OUT");
```

**`addOrFilter` — AND alt-qrupu olan OR:**

```java
// WHERE (field1='y' AND field2='z') OR (field3='a' AND field4='b')
jooq.addOrFilter("myOr", "andGroup1", "t.field1", Op.EQUAl, "y")
    .addOrFilter("myOr", "andGroup1", "t.field2", Op.EQUAl, "z")
    .addOrFilter("myOr", "andGroup2", "t.field3", Op.EQUAl, "a")
    .addOrFilter("myOr", "andGroup2", "t.field4", Op.EQUAl, "b");
```

**`addOrOperation` + `OrOperationBuilder`** — Map-based OR, çoxlu field:

```java
// WHERE (taskNo LIKE '%x%' OR carrierDescription LIKE '%x%' OR requestDescription LIKE '%x%')
Map<String, String> op = Map.of("like", searchText);

jooq.addOrOperation("operation", "t", "taskNo",            op)
    .add(                        "t", "carrierDescription", op)
    .add(                        "r", "requestDescription", op)
    .done();

// WHERE (amount > 100 OR amount IS NULL)
jooq.addOrOperation("ag", "t", "amount", Map.of("greaterThan", "100", "isNull", ""))
    .done();
```

**`orGroup` + `OrGroupBuilder`** — mürəkkəb OR/AND qruplaması:

```java
// WHERE x AND (y OR z)
jooq.addFilter("t.status", Op.EQUAl, "ACTIVE")      // x
    .orGroup("G")
        .or("t", "name",   Map.of("like", "ali"))    // y
        .or("t", "code",   Map.of("equal", "B01"))   // z
    .done();

// WHERE x AND (y OR (z AND f))
jooq.orGroup("G")
        .or("t", "name",  Map.of("like", "ali"))          // y
        .andBranch("zf")                                   // (z AND f)
            .add("t", "amount",  Map.of("greaterThan", "100"))  // z
            .add("t", "country", Map.of("equal",  "AZ"))        // f
        .end()
    .done();
```

---

#### 2.7 EXISTS filterlər

**`addExists` / `addNotExists`** — WHERE EXISTS inline builder:

```java
// WHERE EXISTS (SELECT 1 FROM cash_flow cf WHERE cf.fk_cash_group_id = t.id AND cf.status = 'A')
jooq.addExists(CashFlowEntity.class)
    .joinField("fkCashGroupId", "t", "id")
    .filter("status", Op.EQUAl, "A")
    .done();

// WHERE NOT EXISTS
jooq.addNotExists(BlockedEntity.class)
    .joinField("userId", "u", "id")
    .filter("active", Op.EQUAl, true)
    .done();
```

**`addHavingExists` / `addHavingNotExists`** — HAVING EXISTS (aggregate sorğular üçün):

```java
jooq.addHavingExists(PaymentEntity.class)
    .joinField("fkTaskId", "t", "id")
    .filter("status", Op.EQUAl, "PAID")
    .done();
```

**`addExistsFilter` / `addNotExistsFilter`** — hazır `ExistsSpec` ilə:

```java
ExistsSpec<Task, Permission> spec = ExistsSpec.exists(Permission.class)
    .joinField("fkTaskId", "t", "id")
    .in("fkRoleId", allowedRoles);
jooq.addExistsFilter(spec);
```

**`addInSubQuery`** — WHERE field IN (SELECT ...):

```java
// WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.status = 'PAID')
jooq.addInSubQuery("u.id",
    SubQueryIn.from(Order.class, "o")
        .select("o.userId")
        .filter("status", Op.EQUAl, "PAID")
);

// Composite (çox sahəli):
// WHERE (u.firstName, u.lastName) IN (SELECT bl.firstName, bl.lastName FROM blacklist bl)
jooq.addInSubQuery(
    new String[]{"u.firstName", "u.lastName"},
    SubQueryIn.from(Blacklist.class, "bl")
        .select("bl.firstName", "bl.lastName")
);
```

---

#### 2.8 GROUP BY

```java
// String varargs
jooq.addGroupBy("t.department", "t.status");

// List<String> — dinamik
jooq.addGroupBy(request.getGroupBy());

// Generated Field varargs
jooq.addGroupBy(USERS.DEPARTMENT, USERS.STATUS);

// List<Field<?>>
jooq.addGroupByFields(List.of(USERS.DEPARTMENT, USERS.STATUS));
```

---

#### 2.9 Aqreqat funksiyalar (SUM / COUNT / AVG / MIN / MAX)

**Sadə aqreqat:**

```java
jooq.addAggFunction(Agg.SUM,   "t.totalPrice", "totalPrice");
jooq.addAggFunction(Agg.COUNT, "t.id",         "cnt");
jooq.addAggFunction(Agg.AVG,   "t.amount",     "avgAmount");
jooq.addAggFunction(Agg.MIN,   "t.price",      "minPrice");
jooq.addAggFunction(Agg.MAX,   "t.price",      "maxPrice");
```

**Yuvarlama ilə:**

```java
jooq.addAggFunction(Agg.SUM, "t.totalPrice", "totalPrice", 2);
// → ROUND(SUM(t.total_price), 2) AS total_price
```

**ORDER BY ilə:**

```java
jooq.addAggFunction(Agg.SUM, "t.totalPrice", "totalPrice", "DESC");
jooq.addAggFunction(Agg.SUM, "t.totalPrice", "totalPrice", 2, "DESC");
```

**Riyazi ifadəli aqreqat:**

```java
// SUM(t.price * t.qty)
jooq.addAggFunctionWithMath(Agg.SUM, "t.price", MathOp.MULTIPLY, "t.qty", "totalAmount");

// Yuvarlama ilə:
jooq.addAggFunctionWithMath(Agg.SUM, "t.price", MathOp.MULTIPLY, "t.qty", "totalAmount", 2);
```

**`ComputedField` üzərindəki aqreqat:**

```java
// SUM((t.price * t.qty) - t.discount)
ComputedField expr = ComputedField.of("t.price").multiply("t.qty").subtract("t.discount");
jooq.addAggFunctionOnComputed(Agg.SUM, expr, "netTotal");
jooq.addAggFunctionOnComputed(Agg.SUM, expr, "netTotal", 2);  // ROUND ilə
```

**`AggExpr` — oxunaqlı aqreqat zənciri (`addSumExpr` / `addAggExpr`):**

Çoxterminli SUM ifadələri üçün `ComputedField` qurmadan, birbaşa lambda zənciri ilə.
`plus(f1, f2)` / `minus(f1, f2)` iki sahənin hasilini (`f1 * f2`) əlavə edir/çıxır:

```java
// SUM( marginalCostOut + purchaseExpense*actionOut
//      - marginalCostIn - purchaseExpense*actionIn ) AS totalPrice
jooq.addSumExpr("totalPrice", e -> e
        .plus("t.marginalCostOut")
        .plus("t.totalPurchaseExpense", "t.actionOut")
        .minus("t.marginalCostIn")
        .minus("t.totalPurchaseExpense", "t.actionIn"));

// İstənilən funksiya və yuvarlama ilə:
jooq.addAggExpr(Agg.AVG, "avgNet", e -> e.plus("t.income").minus("t.expense"));
jooq.addAggExpr(Agg.SUM, "totalPrice", 2, e -> e.plus("t.price", "t.qty"));

// Bölmə (və istənilən MathOp) — NULLIF sıfıra bölmə qorunması avtomatik:
jooq.addSumExpr("avgNet", e -> e
        .plus("t.totalPrice", MathOp.DIVIDE, "t.qty")   // + (total_price / NULLIF(qty, 0))
        .minus("t.discount"));
```

**Fluent `AggChain` builder:**

```java
// SUM(t.price + t.tax - t.discount) AS total_price
jooq.addAggFunction(Agg.SUM, "t.price")
    .add("t.tax")
    .subtract("t.discount")
    .as("totalPrice");

// Yuvarlama ilə:
jooq.addAggFunction(Agg.SUM, "t.price")
    .subtract("t.discount")
    .as("netTotal", 2);
```

**HAVING filterlər:**

```java
// HAVING alias üçün Map<String, String>:
jooq.addHavingFilter("totalPrice", Map.of("greaterThan", "1000"));
jooq.addHavingFilter("cnt",        Map.of("between",     "5,50"));

// GROUP BY sahəsinə birbaşa Op ilə:
jooq.addHavingFilter("t.operationType", Op.EQUAl,       "SELL");
jooq.addHavingFilter("t.amount",        Op.GREATER_THAN, 100);
jooq.addHavingFilter("t.category",      Op.IN,           List.of("A","B"));

// Birbaşa jOOQ Condition:
jooq.addRawHaving(DSL.condition("COUNT(t.id) > 5"));
```

---

#### 2.10 CASE WHEN

**Sadə CASE WHEN — tək şərt:**

```java
// CASE WHEN t.status = 'ACTIVE' THEN 'Aktiv' ELSE 'Deaktiv' END AS status_label
jooq.addCaseColumn("t.status", Op.EQUAl, "ACTIVE", "Aktiv", "Deaktiv", "statusLabel");

// ELSE olmadan (null qaytarır):
jooq.addCaseColumn("t.status", Op.EQUAl, "ACTIVE", "Aktiv", "statusLabel");
```

**Mürəkkəb CASE — `CaseBuilder` ilə:**

```java
jooq.addCaseBuilder(
    CaseBuilder.when("t.status", Op.EQUAl, "ACTIVE")
        .then("Aktiv")
        .when("t.status", Op.EQUAl, "BANNED")
        .then("Qadağalı")
        .otherwise("Bilinməyən")
        .as("statusLabel")
);
```

**Fluent `addCase()` — ən oxunaqlı sintaksis:**

```java
// Dəyər variantları:
jooq.addCase()
    .when("status", Op.EQUAl, "ACTIVE").then("Aktiv")
    .when("status", Op.EQUAl, "INACTIVE").then("Deaktiv")
    .else_("Naməlum")
    .as("statusLabel");

// Sütun referansı (thenField / elseField):
jooq.addCase()
    .when("type", Op.EQUAl, "A").thenField("t.priceA")
    .when("type", Op.EQUAl, "B").thenField("t.priceB")
    .elseField("t.defaultPrice")
    .as("finalPrice");
```

---

#### 2.11 ORDER BY

```java
// Sadə field + istiqamət:
jooq.addOrderBy("t.insertDate", "DESC");
jooq.addOrderBy("u.name",       "ASC");

// Birləşmiş string (REST sort parametrindən birbaşa):
jooq.addOrderBy("t.insertDate desc, f.createdDate");
jooq.addOrderBy(request.getSort());   // "u.name asc, u.createdAt desc"

// Map<String, String>:
jooq.addOrderBy(Map.of("u.createdAt", "DESC", "u.name", "ASC"));

// List<Map<String, String>> — ardıcıllıq mühümdür:
jooq.addOrderBy(List.of(
    Map.of("u.createdAt", "DESC"),
    Map.of("u.name",      "ASC")
));

// Generated SortField varargs:
jooq.addOrderBy(USERS.CREATED_AT.desc(), USERS.NAME.asc());

// List<SortField<?>>:
jooq.addOrderByFields(List.of(USERS.CREATED_AT.desc()));

// Tək jOOQ SortField:
jooq.addRawOrderBy(USERS.CREATED_AT.desc());
```

---

#### 2.12 Pagination

```java
// Səhifə — 0-dan başlayır:
jooq.setPage(0, 20);    // LIMIT 20 OFFSET 0
jooq.setPage(2, 20);    // LIMIT 20 OFFSET 40

// Bütün nəticəni al (LIMIT/OFFSET yox, COUNT da yox):
jooq.noPagination();

// COUNT sorğusunu aktiv et, LIMIT/OFFSET tətbiq etmə:
jooq.withCount();

// COUNT-u söndür (çox böyük cədvəllərdə performans üçün):
jooq.skipCount();

// Yalnız COUNT icra et, data sorğusu işlətmə:
jooq.onlyCount();
int total = jooq.getLastRowCount();
```

---

#### 2.13 Fetch metodları

`execute()` `SelectTable` qaytarır — sorğu hələ icra olunmayıb. Fetch metodları həm
icra edir, həm nəticəni çevirir:

**`fetchMaps()`** — `List<Map<String, Object>>` + sətir sayı:

```java
SelectFetchMapResponse resp = jooq
    .setMainTable(User.class, "u")
    .addColumns("u.id", "u.name")
    .setPage(0, 20)
    .fetchMaps();

List<Map<String, Object>> list  = resp.getList();
int                        total = resp.getRowCount();
```

**`fetchMapsNullSafe()`** — eyni, lakin null dəyərlər `""` ilə əvəzlənir:

```java
// JSON-da field silinmir, "" olaraq görünür
SelectFetchMapResponse resp = jooq...fetchMapsNullSafe();
```

**`fetchMapper(RecordMapper)`** — özel mapper ilə DTO:

```java
SelectFetchResponse<MyDto> resp = jooq
    .setMainTable(WarehouseFlow.class, "t")
    .addColumns("t.id", "t1.productName")
    .setPage(0, 20)
    .fetchMapper(r -> new MyDto(
        r.get("id",           String.class),
        r.get("product_name", String.class)
    ));

List<MyDto> list  = resp.getList();
int         total = resp.getRowCount();
```

**`fetchInto(Class<E>)`** — jOOQ auto-mapping ilə entity:

```java
SelectFetchResponse<UserDto> resp = jooq
    .setMainTable(User.class, "u")
    .addFilter("status", Op.EQUAl, "ACTIVE")
    .setPage(0, 20)
    .fetchInto(UserDto.class);
```

**`fetchMergedMap()`** — `key`/`value` sütunlu nəticəni `Map<String,Object>`-ə çevirir:

```java
// Cədvəl "key" və "value" sütunlarına sahib olmalıdır
Map<String, Object> config = jooq
    .setMainTable(Config.class, "c")
    .addColumns("c.key", "c.value")
    .fetchMergedMap();
// → {"theme": "dark", "lang": "az"}
```

---

#### 2.14 UPDATE

```java
// setMainTable + addFilter + update
jooq.setMainTable(User.class, "u");
jooq.addFilter("id", Op.EQUAl, userId);
int rows = jooq.update("status", "INACTIVE");
// → UPDATE users SET status = 'INACTIVE' WHERE id = ?
```

> **Xəbərdarlıq:** `update()` WHERE filtri olmadan çağrılanda `IllegalStateException` atılır —
> bütün cədvəli silmək qorundur.

---

#### 2.15 `reset()`

State-i əl ilə sıfırlar. `execute()`, `update()` və bütün `fetch*()` metodları onu
avtomatik çağırır, ona görə adətən birbaşa çağırmağa ehtiyac olmur:

```java
jooq.reset();  // current = null, updateFilters.clear()
```

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

**`ComputedField` üzərində aqreqat — çox sahəli/iç-içə ifadələr:**

Sadə `.sum(field)` yalnız tək sütun qəbul edir. `price * qty - discount` kimi çox sahəli
ifadələr üçün `sumOf(ComputedField)` (və qardaşları `countOf`/`avgOf`/`maxOf`/`minOf`)
istifadə olunur — `ComputedField` özü riyazi ifadəni qurur, `AggregateBuilder` onu aqreqat
funksiyasına bükür:

```java
AggregateBuilder.groupBy("o.customerId")
    .sumOf(
        ComputedField.of("o.price").multiply("o.quantity").subtract("o.discount")
    ).round(2).as("netRevenue").done()
// → ROUND(SUM((o.price * o.quantity) - o.discount), 2) AS netRevenue
```

**İki SUM ifadəsini bir-birindən çıxmaq — `SUM(exprA) - SUM(exprB)`:**

`SUM` xətti əməliyyat olduğundan `SUM(a) - SUM(b) = SUM(a - b)` — iki ayrı aqreqat
əvəzinə hər tərəf `ComputedField.sumOf(...)` ilə qurulur, sonra `.subtract(...)` ilə
tək aqreqat daxilində birləşdirilir. Oxunaqlılıq üçün hər tərəf öz adlı dəyişəninə
çıxarılır — kitabxanaya yeni metod əlavə etmədən, mövcud `ComputedField` blokları ilə:

```java
ComputedField inSide = ComputedField.sumOf(
        ComputedField.expr("t.totalIn"),
        ComputedField.expr("t.expense").multiply("t.actionIn")
);
ComputedField outSide = ComputedField.sumOf(
        ComputedField.expr("t.totalOut"),
        ComputedField.expr("t.expense").multiply("t.actionOut")
);

manager.addAggFunctionOnComputed(Agg.SUM, inSide.subtract(outSide), "netAmount");
// → SUM((t.totalIn + t.expense*t.actionIn) - (t.totalOut + t.expense*t.actionOut)) AS netAmount
```

> **Qeyd:** Bu, hər iki tərəfin ayrıca `SUM(...)`-ı hesablanıb sonradan çıxılmasından
> fərqlidir (bax: `AggregateBuilder.PostAggOp` — aqreqatdan SONRA sadə/tək-qiymətli sahə
> çıxmaq üçündür, iki tam aqreqatı çıxmaq üçün deyil). İki tam aqreqatı çıxmaq lazım
> olduqda düzgün yol yuxarıdakı `sumOf(...).subtract(sumOf(...))` nümunəsidir.

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

**REGEXP / NOT_REGEXP — Pattern hazırlama:**

`Op.REGEXP` və `Op.NOT_REGEXP` üçün xüsusi `toRegexPattern()` metodu işlənir.

**Problem (v1.1.7 və öncəsi):** Java-da `List.of("isMusteri").toString()` → `"[isMusteri]"` verir.
PostgreSQL regex-ində `[isMusteri]` bir **character class** kimi işlənir — `{i, s, M, u, t, e, r}`
hərflərindən birini axtarır. Nəticədə demək olar hər sətir filterə tutulurdu.

**Həll (v1.1.8):** `toRegexPattern()` daxili köməkçi metodu:

```java
// Collection → elementlər | ilə birləşdirilir
List.of("isMusteri")                    →  "isMusteri"
List.of("isMusteri", "isTechizatci")    →  "isMusteri|isTechizatci"

// Vergüllü string → | ilə əvəzlənir
"isMusteri,isTechizatci"                →  "isMusteri|isTechizatci"

// Sadə string → dəyişmədən
"isMusteri"                             →  "isMusteri"
```

Yaranan SQL:
```sql
-- REGEXP:
"t"."fk_counter_agent_type_key" ~ 'isMusteri|isTechizatci'

-- NOT_REGEXP (notLikeRegex() ilə — ikiqat mötərizəsiz):
"t"."fk_counter_agent_type_key" !~ 'isMusteri'
```

İstifadə nümunəsi — `fk_counter_agent_type_key` sütunu `"isMusteri,isTechizatci"` kimi
vergüllə ayrılmış dəyərlər saxladıqda:
```java
// isMusteri olanları GÖSTƏRMƏ:
.addFilter("t.fkCounterAgentTypeKey", Op.NOT_REGEXP, List.of("isMusteri"))

// isMusteri VƏ ya isTechizatci olanları GÖSTƏRMƏ:
.addFilter("t.fkCounterAgentTypeKey", Op.NOT_REGEXP, List.of("isMusteri", "isTechizatci"))

// Yalnız isMusteri olanları GÖSTƏR:
.addFilter("t.fkCounterAgentTypeKey", Op.REGEXP, "isMusteri")
```

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

**NullDefault — LEFT JOIN null idarəetməsi (v1.1.11-dən)**

LEFT JOIN edilmiş cədvəldə uyğun sətir olmadıqda sahə `NULL` olur, riyazi ifadə bütünlüklə `NULL` qaytarır. `NullDefault` API ilə bu davranışı idarə etmək olar.

**Global — bütün zəncirə:**

```java
// qty null → 0: COALESCE(price,0) * COALESCE(qty,0)
ComputedField.of("o.price")
    .multiply("o.qty")
    .withNullDefault(NullDefault.ZERO)
    .as("lineTotal")
```

**Per-step (IF məntiq) — dəqiq nəzarət:**

```java
ComputedField.of("o.price")
    .multiplyNullAs("o.qty", 0)       // qty null → 0  → price * 0 = 0
    .subtractNullAs("o.discount", 0)  // discount null → 0  → result - 0 = result
    .as("net")
```

| Metod | SQL nəticəsi |
|---|---|
| `.withNullDefault(NullDefault.ZERO)` | Bütün sadə sahələr `COALESCE(field, 0)` |
| `.withNullDefault(NullDefault.ONE)` | Bütün sadə sahələr `COALESCE(field, 1)` |
| `.addNullAs(field, n)` | `+ COALESCE(field, n)` |
| `.subtractNullAs(field, n)` | `- COALESCE(field, n)` |
| `.multiplyNullAs(field, n)` | `* COALESCE(field, n)` |
| `.divideNullAs(field, n)` | `/ NULLIF(COALESCE(field, n), 0)` |

> **Qeyd:** `withNullDefault` yalnız sadə sahələrə tətbiq olunur — `nested`, `ifExpr`, `coalesce` ilə başlayan addımlara deyil. Per-step `*NullAs` metodları `withNullDefault`-dan üstündür.

---

**Cast metodları (v1.1.9-dan)**

Hesablama nəticəsini SQL tipinə çevirmək üçün:

| Metod | SQL nəticəsi |
|---|---|
| `.castToString()` | `CAST(expr AS VARCHAR)` |
| `.castToLong()` | `CAST(expr AS BIGINT)` |
| `.castToInteger()` | `CAST(expr AS INTEGER)` |
| `.castToBigDecimal()` | `CAST(expr AS NUMERIC)` |
| `.castToDateTime(pattern)` | `TO_CHAR / DATE_FORMAT / FORMAT` — DB-yə görə |
| `.castTo(DataType)` | İstənilən `SQLDataType` |

```java
// Riyazi nəticəni NUMERIC-ə çevir
ComputedField.of("o.price")
    .subtract("o.discount")
    .castToBigDecimal()
    .as("netPrice")

// Sadə sütunu STRING-ə çevir
ComputedField.of("u.age")
    .castToString()
    .as("ageText")

// Tarixi format ilə string-ə çevir
ComputedField.of("o.createdAt")
    .castToDateTime("YYYY-MM-DD")
    .as("createdDate")
```

---

### 14a. `DateFormatHelper` — Çox-DB Tarix Format Yardımçısı

**Fayl:** `builder/DateFormatHelper.java`

**Niyə yaradıldı?**

Hər verilənlər bazasının tarix/vaxt formatlaması üçün fərqli funksiyası var:

| DB | Funksiya |
|---|---|
| PostgreSQL, Oracle, H2 | `TO_CHAR(field, 'YYYY-MM-DD')` |
| MySQL, MariaDB | `DATE_FORMAT(field, '%Y-%m-%d')` |
| SQL Server | `FORMAT(field, 'yyyy-MM-dd')` |

`DateFormatHelper` `dsl.dialect()`-dən DB-ni avtomatik tanıyır və pattern-i uyğun formata çevirir. Developer yalnız PostgreSQL/Oracle sintaksisində pattern yazır — qalanı avtomatikdir.

**Pattern çevrilmə nümunəsi:**

| PostgreSQL pattern | MySQL | MSSQL |
|---|---|---|
| `YYYY-MM-DD` | `%Y-%m-%d` | `yyyy-MM-dd` |
| `HH24:MI:SS` | `%H:%i:%s` | `HH:mm:ss` |
| `YYYY-MM-DD HH24:MI` | `%Y-%m-%d %H:%i` | `yyyy-MM-dd HH:mm` |
| `MON DD, YYYY` | `%b %d, %Y` | `MMM dd, yyyy` |

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
| `REGEXP` / `NOT_REGEXP` `List` dəyəri `[val]` formatına düşür | `toRegexPattern()` — Collection-ı `\|` ilə birləşdirir, Java `List.toString()` brackets-ni silir |
| `NOT_REGEXP` ikiqat mötərizə `NOT ((field ~ p))` | `field.notLikeRegex(p)` — birbaşa `field !~ 'p'` yaradır |
| SELECT-də tip çevrilməsi (INTEGER → VARCHAR, tarix → string) | `castString/castLong/castInteger/castBigDecimal/castDateTime` metodları — `castColumn(DataType)` üzərindən işləyir |
| `castDateTime` hər DB-də fərqli funksiya tələb edir | `DateFormatHelper.toDialectField()` — `dsl.dialect()` ilə PG/Oracle/MySQL/MSSQL fərqləndirir, pattern avtomatik çevrilir |
| `ComputedField` zəncirinə cast əlavə etmək | `castTo(DataType)` + `castToString/Long/Integer/BigDecimal/DateTime` — `buildExpr()` sonunda tətbiq edilir |
