# PROGRESS.md — İş Jurnalı

> Bu fayl müxtəlif kompüterlər (iş + ev) arasında kontekst saxlamaq üçündür.
> Hər iş sessiyasından sonra yenilənir, git ilə sinxronlaşır.
>
> **İstifadə:** Yeni söhbətə başlayanda Claude-a "PROGRESS.md oxu" de — bütün kontekst geri qayıdır.

---

## Proyekt haqqında

**Ad:** `jooq-sql-generate`
**Versiya:** 1.1.53 (SubSelectBuilder + INSERT ON DUPLICATE cast fix-ləri, GROUP BY→SELECT auto-add)
**Maven coordinate:** `az.mbm:jooq-sql-generate:1.1.53`
**Repo:** https://github.com/BaxtiyarMammadyarov/jooqsqlgenerate
**Java:** 17
**Asılılıqlar:** jOOQ 3.18.6, Spring Boot 3.2.5 (compileOnly), Jakarta Persistence 3.1.0

**Nə edir:** jOOQ üzərində dinamik SQL sorğu generatoru. REST endpoint-dən gələn parametrlərə əsasən WHERE, JOIN, GROUP BY, ORDER BY hissələri dəyişən sorğular qurmaq üçün fluent builder kitabxanası.

**Üç giriş rejimi:**
- Entity mode — JPA `@Entity` ilə (reflection + cache)
- Generated mode — jOOQ generated `Tables.X` ilə (compile-time tip yoxlaması)
- Derived table mode — `SelectTable.asTable("alias")` ilə sub-query

---

## Cari vəziyyət

Versiya 1.1.53 hazırlanır — v1.1.50 (filter routing, andOn*, Collection<ConcatItem>),
v1.1.51 (audit düzəlişləri), v1.1.52–53 (SubSelectBuilder + INSERT ON DUPLICATE cast
fix-ləri, GROUP BY→SELECT auto-add) dəyişikliklərini əhatə edir. Sənədlər yenilənib.

**Əsas sinif strukturu:**
```
az.mbm.jooqsqlgenerate
├── JooqQuery, JooqManager        — giriş nöqtələri
├── core/                          — EntityTable, SelectTable, SelectFetchJooq
├── builder/                       — SelectQueryBuilder, AggregateBuilder,
│                                    CaseBuilder, ComputedField, SubQueryIn,
│                                    SubSelectBuilder, UpdateQueryBuilder
├── spec/                          — Filter, Filters, Spec, Specification,
│                                    ExistsSpec
├── strategy/                      — FilterStrategy, FilterStrategies
└── enums/                         — Op, Agg, FilterOperationConstants,
                                     LogicalOperator, MathOperation,
                                     JoinConditionType, SqlFunction,
                                     NullDefault
```

**Yadda saxlanmalı arxitektura qərarları:**
- `JooqQuery` Spring bean DEYİL — hər sorğu üçün `static factory` ilə yeni nümunə (state qarışmasının qarşısı)
- `EntityTable` cache `WeakHashMap` + `ReentrantReadWriteLock` (hot reload zamanı GC-ə imkan)
- Filter strategiyaları `FilterStrategies.register()` ilə açıq (Open/Closed Principle)
- Türk-aware LIKE **yalnız string fieldlər üçün**: `LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i'))`
  - Numeric field (bigint/integer/numeric) gəldikdə: `CAST(field AS varchar) LIKE '%val%'` — REPLACE/LOWER yoxdur
  - Tip yoxlaması: `isStringField()` → `likeReadyField()` + `likeReadyVal()` (FilterStrategies-də)
  - Bütün LIKE-axınlar (WHERE, HAVING, OR, SubQuery, SubSelect, `Filter.java`) eyni strategiyadən keçir
- `Op.X_ROUND_N` (5 miqyas × 6 əməliyyat = 30 dəyər)
- HAVING-də alias prefix avtomatik strip olunur

---

## İş Jurnalı

### 2026-07-20 — v1.1.52: SubSelectBuilder ClassCastException + GROUP BY→SELECT auto-add

**(1)** `SubSelectBuilder.toField`: `DSL.field((Name) subQuery)` səhv cast-ı hər scalar
subquery istifadəsində `SelectImpl cannot be cast to Name` atırdı → `DSL.field(Select)` ilə
əvəz olundu. Eyni sinif üzrə audit: `InsertQueryBuilder`-də `(UpdateSetStep) dupStep` cast-ı
da tapıldı və düzəldildi — `ON DUPLICATE KEY UPDATE` istifadəsində CCE atırdı. **(2)** SELECT tam boş + GROUP BY olduqda `SELECT *` əvəzinə GROUP BY sütunları
SELECT-ə avtomatik düşür (mövcud auto-add bloku `hasCustomFields`-ə groupBy əlavə etməklə
aktivləşdirildi; generated mode-da `rawGroupByFields` selectList-ə əlavə olunur). Real hal:
`residualClose` (baglamaqaliq) sorğusunda SELECT dolduran metod çağırılmadığından
`select * ... group by` yaranır və Postgres "t.id must appear in GROUP BY" verirdi.

---

### 2026-07-17 — v1.1.51: Audit düzəlişləri (arxitektor/tester baxışı)

Geriyə uyğun 5 düzəliş: **(1)** GROUP BY-sız HAVING itməsi — `buildGroupBy` erkən çıxışında
bütün HAVING-lər atılırdı, yeni `applyHaving` helper hər halda tətbiq edir; **(2)** `aliasCondition`
IN/BETWEEN/LIKE və s. üçün `default → eq` əvəzinə `FilterStrategies`; **(3)** OR qrupunda
computed/concat alias ifadə kimi genişlənir (`resolveOrFilterField`), agg alias → aydın xəta;
**(4)** `Filters.entries()` — duplicate (op, field) şərtlər itmir, `build()` dəyişməz;
**(5)** entity mode `addOrderBy` output alias-ı tanıyır (`ORDER BY "alias"`).

Açıq qalan (qəsdən): routing məntiqinin 5 nöqtədən tək helper-ə çıxarılması (refactor riski),
SQL-render testlərinin yazılması. Hər ikisi ayrıca sessiya üçün TODO.

---

### 2026-07-17 — v1.1.50: Filter routing düzəlişləri, andOn* alias-ları, Collection<ConcatItem>

**1. Bug fix — prefiksli filter HAVING-ə düşürdü.** Sütun adı ilə aqreqat alias-ı eyni olduqda
(`totalPrice` sütunu + `SUM(...) AS totalPrice`), `addFilter("t.totalPrice", ...)` prefiks
silinərək HAVING-ə gedirdi. Yeni qayda: prefiksli (`t.field`) → həmişə real sütun/WHERE;
prefixsiz → output alias (agg → HAVING, computed/concat → ifadə). 4 yerdə tətbiq olundu:
entity-mode filter/globalFilter loop-ları, generated-mode `resolveDeferredWhereFilters` /
`applyGlobalFilters`, `SelectQueryBuilder.buildWhereCondition`.

**2. Bug fix — havingMap overwrite.** Eyni agg alias-a ikinci HAVING şərti əvvəlkini silirdi
(filter + globalFilter birgə; greaterThan+lessThan aralığı). İndi `Map<String, List<FilterRow>>`,
şərtlər AND ilə birləşir. `AggregateBuilder.AggField`: `havingOp`/`havingValue` →
`List<HavingRow> havings` (breaking — yalnız record-u birbaşa quranlara təsir edir).

**3. Bug fix — entity mode-da aqreqata uyğun gəlməyən `havingFilter` səssiz itirdi.**
İndi bare alias referansı ilə `builder.rawHaving`-ə düşür (generated-mode fallback ekvivalenti).

**4. JOIN `andOn*` alias ailəsi** — `JooqManager.JoinSetup` + `JooqQuery.JoinBuilder`:
`andOnEqual/NotEqual/GreaterThan/GreaterThanOrEqual/LessThan/LessThanOrEqual/IsNull/IsNotNull`.
Qısa adlar (`equal`, `notEqual`, ...) qalır.

**5. `Collection<ConcatItem>` overload-ları** — `addConcatColumn(alias, sep, Collection<ConcatItem>)`,
`addCoalesceColumn(alias, default, List<String> | Collection<ConcatItem>)` — üç səviyyədə
(JooqManager, JooqQuery, SelectQueryBuilder). `Collection` tipi seçilib — `List<String>` ilə
type-erasure toqquşmasına görə. (Əvvəl `ConcatField` builder class-ı yaradılmışdı, sonra
mövcud `ConcatItem` ilə əvəz olunub silindi.)

**6. `Op.EQUAl` üzərindən `@deprecated` javadoc qeydi silindi.**

**Sənədlər:** USAGE.md, USAGE_EN.md, DOCUMENTATION.md (v1.1.50 changelog + bölmələr) yeniləndi.
**Qeyd:** Sandbox-da JDK17/dependency yoxdur — compile IDE-də yoxlanmalıdır.

---

### 2026-07-03 — Refactoring + Op.EQUAL + AggExpr + smoke testlər

**Optimizasiya / oxunaqlılıq (geri uyğunluq tam saxlanılıb):**
- `FilterStrategies` — 30 ROUND qeydiyyatı `registerRoundOps(scale, ...)` helper-inə yığıldı; `coercedList` sadələşdi.
- `Filter.java` 688→434 sətir — 30 ROUND metodu və LIKE ailəsi `roundFilter()`/`strategy()` private helper-lərinə yönləndirildi (bütün public imzalar dəyişməz).
- `MathOp.apply(left, right)` — yeni ortaq helper; `JooqQuery`/`AggregateBuilder`/`ComputedField`/`SelectQueryBuilder`-dəki 9 təkrar `switch(MathOp)` bloku əvəzləndi. DIVIDE-dakı NULLIF qorunması olduğu kimi saxlanıldı.
- `EntityTable` — yeni `fieldsByJavaName` map-i: `getField()` artıq hər çağırışda annotation oxumur (hot path).
- `JooqQuery.executeGenerated()` ~500 sətirdən 13 addımlıq ~90 sətirlik orkestratora bölündü; kod 14 adlı private metoda köçdü (`buildAggregateSelectColumns`, `applyGeneratedJoins`, `resolveDeferredWhereFilters` və s.) — sətir-sətir köçürmə, davranış dəyişməyib.

**Yeni API:**
- `Op.EQUAL` — `EQUAl` typo-sunun düzgün adlanmış qarşılığı; `EQUAl` `@Deprecated` amma tam işlək (Collection→IN çevrilməsi daxil hər ikisi eyni davranır).
- `AggExpr` (builder paketi) + `JooqManager.addSumExpr/addAggExpr` + `JooqQuery.sumExpr/aggExpr` — oxunaqlı aqreqat zənciri: `plus(f)`, `plus(f1,f2)` (= `+ f1*f2`), `plus(ComputedField)`, eyni üçlük `minus`. `(a+b)-(c+d)=a+b-c-d` ekvivalentliyinə əsaslanır, daxildə `addAggFunctionOnComputed`-ə çevrilir.

**Testlər:** `SqlRenderSmokeTest` (8 test) — jOOQ MockConnection ilə DB-siz SQL render yoxlanışı: EQUAL≡EQUAl, Collection→IN, türk LIKE, ROUND strategiyaları, MathOp.apply, JOIN+GROUP+SUM smoke, addSumExpr≡köhnə API, generated-mode. `build.gradle.kts`-ə `testImplementation` jooq+jakarta əlavə edildi.

**Sənədlər:** USAGE.md (Op cədvəli, yeni 7.3.3 addSumExpr, arayış cədvəlləri), DOCUMENTATION.md (AggExpr bölməsi), USAGE_EN.md (Op cədvəlindəki köhnə/yanlış adlar — NOT_LIKE, LIKE_START, IS_NULL — real enum adlarına düzəldildi; yeni 7.3.2).

**Yoxlama:** sandbox-da Maven Central bloklu olduğundan kompilyasiya edilməyib — lokal `./gradlew test` işlədilməlidir.

### 2026-06-22 — v1.1.31: Bug fix — CONCAT/COALESCE/selectAs sütununa filter/HAVING qoyanda `column "alias" does not exist`

**Simptom (real production xətası):** generated-mode sorğuda CONCAT sütununa (`carrierDescription`)
LIKE-axınlı filter qoyulanda Postgres xəta verirdi:
```
ERROR: column "carrierDescription" does not exist
```
HAVING klozunda `lower(replace(replace("carrierDescription", 'İ', 'i'), 'I', 'i')) like ?` kimi
sadəcə alias adı görünürdü — real CONCAT ifadəsi yox.

**Səbəb:** `executeGenerated()` daxilində `aggExprByAlias` map-i (HAVING/`.filter()`/`.havingFilter()`
şərtlərini qurmaq üçün istifadə olunur) **CONCAT/COALESCE/selectAs** sütunları üçün `.as(alias)`
ilə **artıq alias-lanmış** `Field` saxlayırdı (`DSL.concat(...).as(cc.alias())`). Bu obyekt sonra
filter şərtində təkrar istifadə ediləndə jOOQ onu "eyni SELECT-list elementi" kimi tanıyıb sadəcə
`"alias"` identifikatoru kimi render edir — amma SQL-də SELECT alias-ları WHERE/HAVING-də görünmür,
ona görə Postgres "column does not exist" deyir. `aggRows`/`computedCols` blokları bu səhvə düşmürdü,
çünki onlar map-ə **raw** (alias-sız) ifadə qoyub, `.as(alias)`-ı yalnız `selectList.add(...)`-də
ayrıca tətbiq edirdi.

**Düzəliş (`JooqQuery.java`, `executeGenerated()`):** CONCAT, COALESCE və `selectAs` blokları
`aggRows`/`computedCols` ilə eyni qaydaya salındı — `aggExprByAlias.put(alias, rawExpr)` (alias-sız),
`selectList.add(rawExpr.as(alias))` (alias yalnız SELECT-də). Nəticədə HAVING/`.filter()`/
`.havingFilter()` indi real ifadəni təkrarlayır (`HAVING lower(replace(replace(CONCAT(...), ...)))`),
alias-a istinad etmir.

**Təsirlənən sütun növləri:** `concat`/`concatColumn`/`addConcatColumn`, `coalesceColumn`/
`addCoalesceColumn`, `selectAs` — generated/derived-table mode-da bu üç növ sütuna filter
(`filter()`, `havingFilter()`, `globalFilter()`) qoyulan bütün hallar düzəldi.

**Əlavə tapıntı (eyni kod bloku daxilində):** `computedFields` (`addComputedColumn(field).add()
...as(alias)` zənciri) də eyni iki səhvə düşürdü — (1) `cf.toFieldGenerated(...)` artıq
`.as(alias)` tətbiq edilmiş halda map-ə yazılırdı, (2) zəncirin daxili `.filter(op,val)`-i
birbaşa `DSL.field(DSL.name(alias))` ilə HAVING-ə alias istinadı qurudu. Hər ikisi
`cf.buildExprGenerated(...)` (raw, alias-sız) istifadəsinə keçirildi.

---

### 2026-06-22 — v1.1.30: Yeni feature — `concatColumn(...).sep(...).as(...)` (fluent, join-style concat)

**Motivasiya:** İstifadəçi mövcud sadə `addConcatColumn(alias, sep, fields...)` yazı tərzini
saxlamaq istəyirdi (heç bir builder-paket importu lazım olmadan), amma JOIN-lərdəki kimi
fluent/sıralı yazı da istəyirdi: əvvəl sahələr, sonra ayırıcı, sonra `.as(alias)` ilə tamamlanan
zəncir. İlk versiya (`ConcatBuilder.of(fields...).sep(...).as(...)`, ayrıca obyekt yaradıb
`addConcatColumn(cb)`-ə ötürmək) bunu tam vermirdi — istifadəçi birbaşa `addConcatColumn(fields...)`
ilə başlayıb `.as(alias)` ilə bitirmək istədi. **`ConcatBuilder.java` silindi**, əvəzinə
JOIN builder-lərdəki (`JoinBuilder`/`JoinSetup`, `done()`) modelinə bənzər həll quruldu.

**Həll — niyə ad `addConcatColumn` ola bilmədi:** `addConcatColumn(String... fields)` adı ilə
yeni overload əlavə etsəydik, mövcud `addConcatColumn(String alias, String separator, String... fields)`
ilə **Java compile-time ambiguity** yaranırdı (2+ arqumentli çağırışlarda hər iki metod
variable-arity uyğun gəlir, derleyici "ambiguous method call" xətası verir). Ona görə yeni
giriş nöqtəsi fərqli adla: **`concatColumn(String... fields)`** — JOIN-lərdə `addLeftJoin(...)`-in
ayrıca `JoinBuilder` qaytarması kimi, bu da `ConcatSetup` qaytarır.

```java
jooq.concatColumn("u.firstName", "u.lastName")
    .sep(" ")
    .as("fullName")     // ← builder tamamlanır, JooqManager-ə/JooqQuery-yə qayıdır
```

**Yeni siniflər/metodlar:**
- `JooqQuery.concatColumn(String...)` → `JooqQuery.ConcatSetup` (daxili sinif, `JoinBuilder` kimi)
- `JooqManager.concatColumn(String...)` → `JooqManager.ConcatSetup`
- Hər ikisinin `.sep(separator)` və `.as(alias)` metodları var; `.as(alias)` tamamlayıcıdır —
  daxilən mövcud `concat(alias, sep, fields...)` / `addConcatColumn(alias, sep, fields...)`-ə
  delegate edir, sonra parent-ə (`JooqQuery<T>`/`JooqManager`) qayıdır.

Tamamilə əlavədir (additive) — mövcud `String...`/`List<String>`/`ConcatItem...` overload-larına
heç bir təsiri yoxdur, sadəcə üçüncü yazı tərzi əlavə olundu.

---

### 2026-06-22 — v1.1.28: COALESCE tip xətası, WHERE filter generated-mode düzəlişi, @Deprecated

**1. Bug fix — `COALESCE types ... cannot be matched` (Postgres):**

`concat()`/`coalesce()` daxilində mətn olmayan sahə (Long, Date, ...) mətn literalı (`''`,
String default) ilə `COALESCE`-də qarışanda Postgres xəta verirdi. Postgres `||`-də literalı
avtomatik cast edir, `COALESCE`-də YOX. Düzəliş: sahə `field.cast(String.class)` ilə cast edilir
(concat-da həmişə, coalesce-də yalnız default `String` olduqda). 7 yerdə tətbiq edildi:
`SelectQueryBuilder.buildConcatField/buildCoalesceField`, `JooqQuery`-nin generated-mode
CONCAT/COALESCE blokları, `CoalesceExpr.toField/toFieldGenerated`, `SubSelectBuilder.buildSelectExpr`
(CONCAT + COALESCE case-ləri).

**2. Bug fix — generated mode-da `.filter()` sükutla itirdi:**

`filter(field, op, value)` sahəni dərhal (eager) resolve etməyə çalışırdı, amma
`joinTableRegistry`/`aggExprByAlias` yalnız `executeGenerated()`-də tam dolur — bütün `.filter()`
çağırışlarından sonra. Tapılmayan sahə xətasız sükutla atılırdı. Həll: `deferredWhereFilterRows`
siyahısına yığılır, həll `executeGenerated()`-in sonunda olur (registry-lər artıq dolu olanda).
Yan fayda: indi WHERE-i concat/coalesce/selectAs alias-larına da tətbiq etmək mümkündür.

**3. Köhnə metodlar `@Deprecated` edildi** (istəyə görə: "addFilter kimi ümumi olanlar qalsın,
sonradan daha yaxşısını yazdığımız köhnələr deprecate olsun"):

| Köhnə | Əvəzedici |
|---|---|
| `JooqQuery.from(Class, String)` | `from(Table, String)` / `from(SelectTable, String)` |
| `JooqQuery.leftJoin/innerJoin(Class, alias, from, to)` | fluent `leftJoin/innerJoin(Class, alias)` |
| `JooqQuery.leftJoin/innerJoin(SelectTable, alias, from, to)` | fluent `leftJoin/innerJoin(SelectTable, alias)` |
| `JooqManager.setMainTable(Class, String)` | `setMainTable(Table\|SelectTable, String)` |
| `JooqManager.addLeftJoin/addInnerJoin(Class, alias, from, to)` | `addLeftJoin/addInnerJoin(Class, alias)` (`JoinSetup`) |
| `JooqManager.addLeftJoin/addInnerJoin(SelectTable, alias, from, to)` | `addLeftJoin/addInnerJoin(SelectTable, alias)` (`SelectJoinSetup`) |

**Düzəliş (sonradan geri alındı):** `concat(alias, sep, String...)` / `addConcatColumn(alias, sep, String...|List<String>)`
ilkin planda deprecate edilmişdi, amma geri alındı — `ConcatItem...` versiyası `builder`
(daxili) paketindən import tələb edir, bu "daxili siniflər JooqManager arxasında qalsın"
prinsipinə zidddir. Sadə sütun-birləşdirmə `addFilter` kimi ümumi hal sayılır, dəyişməz qalır.
`ConcatItem...` yalnız literal/CASE/COALESCE qarışdırmaq üçün əlavə seçimdir, əvəzedici deyil.

Toxunulmayanlar (ümumi məqsədli, qəsdən saxlanıldı): `addFilter`, `addCoalesceColumn`,
`addComputedColumn`/`addComputedField`, `addAggFunction*`, `addCaseColumn`/`addCaseBuilder`,
`equal`/`notEqual`/`greaterThan`/və s. qısa filter metodları.

Ətraflı izah + nümunələr: `DOCUMENTATION.md` → "v1.1.27" bölümü.

**Versiya:** 1.1.23 → 1.1.27 (build.gradle.kts iki yerdə sinxronlaşdırıldı).

**Yarımçıq qalan:** `subSelectCols` generated-mode dəstəyi yoxdur — `SubSelectBuilder.toField()`
yalnız entity-mode (`EntityTable`); `toFieldGenerated(Table<?>, Map<String,Table<?>>)` yazılmalıdır.

---

### 2026-06-05 — v1.1.11: NullDefault — LEFT JOIN null idarəetməsi

**Problem:** LEFT JOIN edilmiş cədvəldə uyğun sətir olmadıqda sahə `NULL` olur,
riyazi ifadənin (`computedColumn`, `ComputedField`, `aggWithMath`) nəticəsi bütünlüklə
`NULL` qaytarır. Framework əvvəlki hardcoded COALESCE cəhdi düzgün deyildi (memarlıq baxımından).

**Həll — eksplisit `NullDefault` API:**

**Yeni enum `enums/NullDefault.java`:** `ZERO`, `ONE`, `NONE`

**`ComputedField` dəyişiklikləri:**
- `Step` record-a `nullAs` (per-step null default) əlavə edildi
- `nullDefault` field + `withNullDefault(NullDefault)` — bütün zəncirə global default
- `addNullAs`, `subtractNullAs`, `multiplyNullAs`, `divideNullAs` — per-step IF məntiq
- `applyNullDefault()` private yardımçı metod
- `buildExpr()` — per-step `nullAs` > global `nullDefault` > heç nə (prioritet sırası)
- DIVIDE-da `NULLIF(denom, 0)` — sıfıra bölünmə qoruması

**`SelectQueryBuilder` dəyişiklikləri:**
- `ComputedCol` record-a `NullDefault nullDefault` əlavə edildi
- `computedColumn(..., NullDefault)` yeni overload
- `applyMathOp(f1, op, f2, NullDefault)` — NullDefault.NONE olduqda coalesce yoxdur

**`JooqQuery` dəyişiklikləri:**
- `ComputedRow` record-a `NullDefault nullDefault` əlavə edildi
- `computedColumn(..., NullDefault)` yeni overload
- `aggWithMath` — hardcoded coalesce geri alındı (SUM/AVG SQL özü NULL sətirləri ignore edir)

**İstifadə:**
```java
// Global — bütün zəncirə
ComputedField.of("o.price").multiply("o.qty")
    .withNullDefault(NullDefault.ZERO).as("lineTotal")

// Per-step dəqiq nəzarət
ComputedField.of("o.price")
    .multiplyNullAs("d.qty", 0)
    .subtractNullAs("d.discount", 0)
    .as("net")

// 2-sahəli sadə forma
.computedColumn("net", "o", MathOp.SUBTRACT, "amount", "d", "discount", NullDefault.ZERO)
```

**Versiya:** 1.1.11 → Maven Central-a release edildi.

---

### 2026-05-01 — Cowork sessiyası (ilkin setup)
- PROGRESS.md yaradıldı (cross-device kontekst saxlamaq üçün)
- Proyekt strukturu və mövcud sənədlər oxundu
- Növbəti sessiya üçün hazırlıq

### 2026-05-01 — Bug fix: LIKE numeric field-də tam düzəliş (WHERE + HAVING + Filter.java)

**Problem (tam):** `Op.LIKE` / `START_WITH` / `END_WITH` / `LIKE_IGNORE_CASE` /
`START_WITH_IGNORE_CASE` / `END_WITH_IGNORE_CASE` rəqəm field-də (bigint, integer,
numeric) işlədilərkən iki ayrı problem var idi:

1. **FilterStrategies** — `turkishLower()` SQL-də CAST olmadan birbaşa REPLACE çağırırdı:
   PostgreSQL `function replace(bigint, unknown, unknown) does not exist` xətası.

2. **Filter.java** — `like()`, `startWith()`, `endWith()` metodları `FilterStrategies`-i
   tamamilə yan keçirdi: raw `.like("%" + value + "%")` çağırırdı. Nə tip yoxlaması,
   nə türk normallaşdırması tətbiq olunurdu.

**Kök səbəb:** Rəqəm sütunlarında 'İ'/'I' simvolu ola bilməz — REPLACE/LOWER mənasızdır.
Düzgün davranış: `CAST(field AS varchar) LIKE '%val%'`.

**Düzəliş arxitekturası (`FilterStrategies.java`):**
- `isStringField(field)` — `CharSequence` alt-tipi olub olmadığını yoxlayır
- `likeReadyField(field)` — string → `turkishLower(field)`; numeric → `field.cast(String.class)`
- `likeReadyVal(field, val)` — string → `turkishNormalize(val)`; numeric → `val.toString()`
- `turkishLower()` sadələşdi — artıq yalnız string field üçün çağırılır, içindəki CAST məntiqi silindi
- Bütün 6 LIKE strategiyası `likeReadyField` + `likeReadyVal` istifadə edir

**Düzəliş (`Filter.java`):**
- `like()`, `startWith()`, `endWith()` artıq raw `.like()` çağırmır
- `FilterStrategies.get(Op.LIKE/START_WITH/END_WITH).apply(f, value)` üzərindən işləyir
- Tip yoxlaması + türk normallaşdırması avtomatik tətbiq olunur

**Əhatə — bütün LIKE axınları:**
| Kontekst | Fayl | Düzəldi? |
|---|---|---|
| WHERE filter (`addFilter`) | SelectQueryBuilder.java:1100 | ✅ FilterStrategies vasitəsilə |
| WHERE OR filter | SelectQueryBuilder.java:1133 | ✅ FilterStrategies vasitəsilə |
| HAVING (computed column) | SelectQueryBuilder.java:309 | ✅ FilterStrategies vasitəsilə |
| HAVING (aggregate) | AggregateBuilder.java:185 | ✅ FilterStrategies vasitəsilə |
| SubQuery WHERE | SubQueryIn.java:178 | ✅ FilterStrategies vasitəsilə |
| SubSelect WHERE | SubSelectBuilder.java:308 | ✅ FilterStrategies vasitəsilə |
| `Filter<T>.like()` | Filter.java:109 | ✅ Birbaşa düzəldildi |
| JOIN ON | JoinOnBuilder | — yalnız `Op.EQUAl` işlənir, LIKE yoxdur |

**SQL nəticəsi:**
```
varchar/text field: LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%val%'
bigint/integer:     CAST(field AS varchar)                         LIKE '%val%'
```

### 2026-05-01 — Build: signing yalnız key olduqda
**Problem:** `./gradlew publishToMavenLocal` `signMavenJavaPublication` task-ında
"no configured signatory" xətası verirdi (local-da PGP key yoxdur).

**Dəyişdirilmiş fayl:** `build.gradle.kts`
- `signing { ... }` bloku `if (signingKeyFile.exists())` ilə əhatələndi.
- Local publish-də signing avtomatik atlanır.
- Maven Central üçün `~/.gradle/secret.pgp` qoyulduqda yenidən aktivləşir.

İndi sadəcə `./gradlew clean publishToMavenLocal` işləyir, `-x signMavenJavaPublication`
artıq lazım deyil.

### 2026-05-01 — Bug fix: COUNT sorğusunda JOIN-lər itir
**Problem:** `SelectQueryBuilder.buildCount()` GROUP BY olmadığı halda yalnız
`mainTable.getTable()`-i FROM kimi istifadə edirdi, JOIN-ləri tətbiq etmirdi.
Nəticə: INNER JOIN olduqda və ya JOIN ON şərtində/WHERE-də join cədvəlinin
sahələri olduqda `rowCount` SƏHV çıxırdı (əsas SQL-dəki sətirlərdən fərqli).

**Nümunə (entity mode, INNER JOIN + JOIN ON-da əlavə şərt):**
```
Əsas SQL: ... FROM "task" t JOIN "request" r ON (t.fk_request_id = r.id AND r.status='A')
COUNT (səhv): SELECT count(*) FROM "task" t WHERE ...   ← JOIN itib
```

**Dəyişdirilmiş fayllar:**
- `src/main/java/az/mbm/jooqsqlgenerate/builder/SelectQueryBuilder.java`
  - `buildEntityJoins(...)` → generic `<R extends Record>` edildi
  - `buildSubQueryJoins(...)` → generic `<R extends Record>` edildi
  - `buildCount(...)` imzasına `Map<String, EntityTable<?>> tableMap` parametri
    əlavə olundu; GROUP BY-sız halda `selectCount().from(mainTable.getTable())`-dan
    sonra `buildEntityJoins` və `buildSubQueryJoins` çağırılır.
  - `build()` daxilindəki iki `buildCount(...)` çağırışına `tableMap` ötürüldü.

**Yoxlanmamış / yarımçıq:**
- Compile/test sandbox-da internetsizliyə görə icra olunmadı.
  İş kompüterində `./gradlew test` ilə yoxlanmalıdır.
- `JooqQuery.java` (generated mode, sətr ~1882) eyni xətanı ehtiva edir:
  `dsl.selectCount().from(mainTable).where(where)` — JOIN-lər tətbiq olunmur.
  Burada 4 fərqli JOIN növü inline qurulur (rawJoins, joins, selectJoins, extJoins),
  düzəliş daha mürəkkəbdir. Tövsiyə olunan həll: `dsl.selectCount().from(grouped.asTable("_count"))`
  — bütün JOIN+WHERE+GROUP BY-ı dərived table kimi istifadə edir. Sonrakı sessiyada
  düzəldiləcək (istifadəçi təsdiqindən sonra).

### 2026-05-12 — concat metodu JooqManager + JooqQuery-yə əlavə edildi

**Problem:** `SelectQueryBuilder.concat()` mövcud idi, lakin `JooqManager` və `JooqQuery` üzərindən əlçatan deyildi.

**Dəyişdirilmiş fayllar:**

- `JooqQuery.java`:
  - `private final List<ConcatRow> concatCols` əlavə edildi
  - `private record ConcatRow(String alias, String separator, String[] fields)` əlavə edildi
  - `concat(String alias, String separator, String... fields)` metodu əlavə edildi
  - Build fazasında `for (ConcatRow cc : concatCols) builder.concat(...)` lopu əlavə edildi

- `JooqManager.java`:
  - `addConcatColumn(String alias, String separator, String... fields)` metodu əlavə edildi

**İstifadə nümunəsi:**
```java
manager.addConcatColumn("fullName", " ", "u.firstName", "u.lastName")
// SQL: COALESCE(firstName,'') || ' ' || COALESCE(lastName,'') AS fullName
```

---

### 2026-05-18 — Yeni feature: Filters + JooqManager — Number overloadları

**Motivasiya:** `equal`, `notEqual`, `greaterThan` və s. metodlar yalnız `String value`
qəbul edirdi. `Long`, `Integer`, `Double`, `BigDecimal` üçün ayrıca overload yox idi —
istifadəçi `.toString()` çağırmaq məcburiyyətində idi.

**Həll:** `Number` tipli overload əlavə edildi — tək overload bütün rəqəm tiplərini əhatə edir
(`Long`, `Integer`, `Double`, `BigDecimal`, `BigInteger`, `Float` + primitiv `int`/`long`/`double` autobox).

**Dəyişdirilmiş fayllar:**

- `spec/Filters.java` — `Number` overload əlavə edildi: `equal`, `notEqual`,
  `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`
  - Daxilən `value.toString()` çağırıb String variantına yönləndirir
  - `null` yoxlaması var, `isBlank()` yoxdur (Number-də mənasızdır)

- `JooqManager.java` — eyni metodlara `Number` overload əlavə edildi
  - `Filters`-ə delegate edir — logika `Filters`-dədir

**İstifadə:**
```java
.equal("o.statusId", 1L)
.notEqual("u.roleId", roleId)           // Long
.greaterThan("o.amount", minAmount)     // BigDecimal
.lessThanOrEqual("u.age", 65)           // int → autobox Integer
```

---

### 2026-05-15 — Yeni feature: ORDER BY birləşmiş string format

**Motivasiya:** REST endpoint-dən `"t.insertDate desc,f.createdDate"` kimi
birləşmiş `sort` parametri gəlir; əvvəlcə `addOrderBy(Map)` ilə parse etmək lazım idi.

**Dəyişdirilmiş fayllar:**

- `JooqQuery.java` — `orderBy(String sortExpression)` metodu əlavə edildi:
  - Vergüllə böl → hər hissəni boşluqla böl → `field` + `direction` (default ASC)
  - Boş/null hissələr atlanır
  - Daxilən mövcud `orderBy(field, dir)` metodunu çağırır

- `JooqManager.java` — `addOrderBy(String sortExpression)` metodu əlavə edildi:
  - `q().orderBy(sortExpression)` üzərindən delegate edir

**İstifadə:**
```java
jooq.addOrderBy("t.insertDate desc, f.createdDate")
// → ORDER BY t."insertDate" DESC, f."createdDate" ASC

jooq.addOrderBy(request.getSort())  // REST parametri birbaşa
```

---

### 2026-05-15 — Bug fix: Filters null/boş dəyər keçikdirmə

**Problem:** `equal("")`, `like("")`, `greaterThan("")` və digər string filter metodları
boş string-i (`""`) etibarlı dəyər kimi qəbul edirdi → `WHERE field = ''` kimi SQL generasiya olunurdu.
Düzgün davranış: null və ya boş (`isBlank()`) dəyər gəldikdə şərt əlavə edilməməlidir.

**Düzəliş (`Filters.java`):**
Aşağıdakı metodların hər birinə `if (value == null || value.isBlank()) return this;` əlavə edildi:
`equal`, `notEqual`, `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`,
`like`, `startWith`, `endWith`

`isNull`/`isNotNull` dəyişdirilmədi — onlar `""` dəyərini qəsdən ötürür.
`put()` metodu dəyişdirilmədi — `""` yoxlaması `put()`-da deyil, hər metodda fərdi edildi.

`JooqManager`-dəki eyni metodlar `Filters`-ə delegate etdiyi üçün avtomatik düzəldi;
yalnız Javadoc şərhləri "null/boş dəyər atlanır" olaraq yeniləndi.

---

### 2026-05-12 — JooqManager birbaşa filter metodları + Filters təkmilləşdirmələri

**Motivasiya:** İstifadəçi `Filters.of()` yaradıb `addFilter()` ilə set etmək əvəzinə
`JooqManager` üzərindən birbaşa filter əlavə etmək istədi. `Filters` daxili sinifdir,
istifadəçi yalnız `JooqManager` ilə işləməlidir.

**`Filters.java` — dəyişikliklər:**

1. `between(String, String, String)` — null/boş handling əlavə edildi:
   - Hər ikisi dolu → `BETWEEN from AND to`
   - Yalnız from → `>= from`
   - Yalnız to → `<= to`
   - İkisi null/boş → şərt əlavə edilmir

2. `between(String, Number, Number)` — yeni overload:
   - `Long`, `BigDecimal`, `Integer`, `BigInteger` hamısını əhatə edir
   - Daxilən String variantına yönləndirilir, null handling eynidir

3. `in(String, Collection<?>)` — yeni overload (`List`, `Set`)
4. `notIn(String, Collection<?>)` — yeni overload

**`JooqManager.java` — yeni birbaşa filter metodları (hamısı daxilən `Filters`-ə yönlənir):**
- `equal`, `notEqual`
- `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`
- `like`, `startWith`, `endWith`
- `isNull`, `isNotNull`
- `in(String, Collection<?>)`, `notIn(String, Collection<?>)`
- `between(String, String, String)`, `between(String, Number, Number)`

**Geriyə dönük uyğunluq:** Tam qorunur.

---

## Yarımçıq işlər / TODO

<!-- Burada açıq tapşırıqlar, bug-lar, ideyalar -->
- [ ] `JooqQuery.executeGenerated()` daxilində COUNT sorğusu (sətr ~1882) də
      JOIN-ləri tətbiq etmir. Tövsiyə: `dsl.selectCount().from(grouped.asTable("_count"))`
      üzərinə keçmək (entity mode-dakı kimi düzgün count almaq üçün).
- [ ] Yeni unit test əlavə et: INNER JOIN + JOIN ON-da əlavə şərt olduqda
      `rowCount == result.size()` (noPagination ilə) doğrulansın.

---

## Kodlaşdırma qaydaları (checklist — hər yeni feature üçün)

> Bu qaydalar keçmiş buglardan öyrənildi. Yeni metod/feature yazarkən aşağıdakıları yoxla.

### 1. String dəyər alan filter metodları — null/blank guard
**Qayda:** `String value` parametri alan hər filter metodu `null` **və** `isBlank()` yoxlamalıdır.

```java
// ✅ Düzgün
public Filters equal(String field, String value) {
    if (value == null || value.isBlank()) return this;
    return put(..., field, value);
}

// ❌ Səhv — boş string WHERE field = '' generasiya edir
public Filters equal(String field, String value) {
    return put(..., field, value);
}
```

**Tətbiq edilməli metodlar:** `equal`, `notEqual`, `greaterThan`, `greaterThanOrEqual`,
`lessThan`, `lessThanOrEqual`, `like`, `startWith`, `endWith`, `regexp`, `notRegexp`

**İstisnalar:** `isNull`, `isNotNull` — `""` dəyərini qəsdən ötürür; bu metodlarda guard olmaz.

**Collection variantları:** `in(Collection)`, `notIn(Collection)` — `isEmpty()` yoxlaması kifayətdir.

---

### 2. Yeni filter metodu əlavə edərkən — Number overload
**Qayda:** `String value` alan hər filter metodunun `Number value` overloadu da olmalıdır.
Tək `Number` overload bütün rəqəm tiplərini (`Long`, `Integer`, `Double`, `BigDecimal` + primitiv) əhatə edir.

```java
// ✅ Düzgün
public Filters equal(String field, Number value) {
    if (value == null) return this;
    return put(..., field, value.toString());
}
// NB: isBlank() yoxlaması lazım deyil — Number.toString() heç vaxt blank qaytarmır
```

**İstisnalar:** `like`, `startWith`, `endWith` — bunlar semantik olaraq yalnız string üçündür,
Number overload lazım deyil (numeric field-də LIKE ayrıca `CAST AS varchar` məntiqi ilə işləyir).

---

### 2. Numeric field-də LIKE/REPLACE xətası
**Qayda:** LIKE strategiyaları numeric field (bigint, integer, numeric) üçün
`REPLACE`/`LOWER` tətbiq etməməlidir — `CAST(field AS varchar)` istifadə edilməlidir.

```java
// FilterStrategies-də isStringField() yoxlaması mütləqdir
Field<?> ready = isStringField(f) ? turkishLower(f) : f.cast(String.class);
```

**Yoxlanmalı axınlar:** WHERE, HAVING, SubQuery, SubSelect, OR filter — hamısı eyni strategiyadən keçməlidir.

---

### 3. COUNT sorğusunda JOIN-lər
**Qayda:** `buildCount()` JOIN-ləri də tətbiq etməlidir, yalnız `mainTable`-dan `selectCount()` kifayət etmir.

```java
// ✅ Düzgün — JOIN-lər tətbiq edilir
selectCount().from(mainTable).join(...).where(...)

// ❌ Səhv — JOIN-lər itirilir, rowCount yanlış çıxır
selectCount().from(mainTable).where(...)
```

**Qeyd:** `JooqQuery.java` (~1882 sətr) hələ düzəldilməyib — TODO-da var.

---

### 4. Yeni public metod əlavə edərkən
- `JooqManager` metodları → `Filters`-ə delegate et, logika `Filters`-də olsun
- `SelectQueryBuilder` metodları → `JooqQuery` və `JooqManager` üzərindən də əlçatan et
- Builder metodları (CaseBuilder, ConcatItem, ComputedField) jOOQ import etməməlidir — DSL logikası ayrıca `*FieldBuilder` siniflərdə olmalıdır
- Yeni enum dəyər əlavə edərkən `FilterStrategies`-ə müvafiq strategiya da əlavə et

---

### 6. ORDER BY string parse məntiqi
Yeni string-based sort metodu əlavə edərkən parse məntiqi **mövcud `orderBy(field, dir)`-ə delegate** etməlidir — duplikasiya olmaz.

```java
// ✅ Düzgün — parse + delegate
for (String part : expr.split(",")) {
    String[] tokens = part.trim().split("\\s+");
    orderBy(tokens[0], tokens.length >= 2 ? tokens[1] : "ASC");
}
// ❌ Səhv — birbaşa SortField yaratma (tableMap/alias resolution itir)
```

---

### 5. Versiya idarəetməsi
`build.gradle.kts`-də versiya **iki yerdə** yazılır — hər ikisi eyni vaxtda dəyişdirilməlidir:
```kotlin
version = "X.Y.Z"          // ← layihə versiyası (yuxarı)
version = "X.Y.Z"          // ← MavenPublication içində (aşağı)
```

---

## Vacib qərarlar / qeydlər

<!--
Memorable bir qərar verdikdə bura yaz:
- Niyə bu yolu seçdik
- Hansı alternativləri rədd etdik
- Gələcəkdə nəzərə alınmalı detallar
-->

---

## İş axını (cross-device sinxronizasiya)

İş kompüterində iş bitdikdən sonra:
```bash
git add PROGRESS.md
git commit -m "progress: <qısa başlıq>"
git push
```

Ev kompüterində başlamazdan əvvəl:
```bash
git pull
```

Sonra Claude-a: **"PROGRESS.md oxu və davam et"**
