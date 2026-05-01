# PROGRESS.md — İş Jurnalı

> Bu fayl müxtəlif kompüterlər (iş + ev) arasında kontekst saxlamaq üçündür.
> Hər iş sessiyasından sonra yenilənir, git ilə sinxronlaşır.
>
> **İstifadə:** Yeni söhbətə başlayanda Claude-a "PROGRESS.md oxu" de — bütün kontekst geri qayıdır.

---

## Proyekt haqqında

**Ad:** `jooq-sql-generate`
**Versiya:** 1.0.8
**Maven coordinate:** `az.mbm:jooq-sql-generate:1.0.8`
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

Versiya 1.0.8 stabildir, Maven Central-da yayımlanır. `DOCUMENTATION.md` (siniflərin izahı) və `USAGE.md` (istifadə təlimatı) tam doludur.

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

<!--
Növbəti sessiya üçün şablon:

### YYYY-MM-DD — qısa başlıq
- Nə edildi: ...
- Hansı fayllar dəyişdirildi: ...
- Qərarlar / qeydlər: ...
- Yarımçıq qalan: ...
-->

---

## Yarımçıq işlər / TODO

<!-- Burada açıq tapşırıqlar, bug-lar, ideyalar -->

- [ ] **Maven Central-a release (1.0.9):**
  - `~/.gradle/gradle.properties` və `~/.gradle/secret.pgp` iş kompüterinə köçürüldü ✓
  - Növbəti addımlar (gələcək sessiyada):
    1. `./gradlew clean publishToMavenLocal` — signing test
    2. `build.gradle.kts`-də iki yerdə (sətr 10, 68) `version = "1.0.8"` → `"1.0.9"`
    3. `./gradlew clean publishToSonatype closeAndReleaseSonatypeStagingRepository`
    4. `git add -A && git commit -m "release: 1.0.9" && git push && git tag v1.0.9 && git push --tags`
- [ ] `JooqQuery.executeGenerated()` daxilində COUNT sorğusu (sətr ~1882) də
      JOIN-ləri tətbiq etmir. Tövsiyə: `dsl.selectCount().from(grouped.asTable("_count"))`
      üzərinə keçmək (entity mode-dakı kimi düzgün count almaq üçün).
- [ ] Yeni unit test əlavə et: INNER JOIN + JOIN ON-da əlavə şərt olduqda
      `rowCount == result.size()` (noPagination ilə) doğrulansın.

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
