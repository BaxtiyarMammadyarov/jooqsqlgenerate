# PROGRESS.md — İş Jurnalı

> Bu fayl müxtəlif kompüterlər (iş + ev) arasında kontekst saxlamaq üçündür.
> Hər iş sessiyasından sonra yenilənir, git ilə sinxronlaşır.
>
> **İstifadə:** Yeni söhbətə başlayanda Claude-a "PROGRESS.md oxu" de — bütün kontekst geri qayıdır.

---

## Proyekt haqqında

**Ad:** `jooq-sql-generate`
**Versiya:** 1.1.5
**Maven coordinate:** `az.mbm:jooq-sql-generate:1.1.5`
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

Versiya 1.1.5 hazırdır, Maven Central-a release gözləyir. `DOCUMENTATION.md` (siniflərin izahı) və `USAGE.md` (istifadə təlimatı) tam doludur.

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
                                     JoinConditionType, SqlFunction
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

- [ ] **Maven Central-a release (1.1.6):**
  - `~/.gradle/gradle.properties` və `~/.gradle/secret.pgp` iş kompüterinə köçürüldü ✓
  - Növbəti addımlar (gələcək sessiyada):
    1. `./gradlew clean publishToMavenLocal` — signing test
    2. `build.gradle.kts`-də versiya artıq `1.1.6`-dır — dəyişmə lazım deyil
    3. `./gradlew clean publishToSonatype closeAndReleaseSonatypeStagingRepository`
    4. `git add -A && git commit -m "release: 1.1.6" && git push && git tag v1.1.6 && git push --tags`
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
