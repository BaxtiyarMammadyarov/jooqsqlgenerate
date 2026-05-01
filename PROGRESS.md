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
- Türk-aware LIKE: `LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i'))` default
- `Op.X_ROUND_N` (5 miqyas × 6 əməliyyat = 30 dəyər)
- HAVING-də alias prefix avtomatik strip olunur

---

## İş Jurnalı

### 2026-05-01 — Cowork sessiyası (ilkin setup)
- PROGRESS.md yaradıldı (cross-device kontekst saxlamaq üçün)
- Proyekt strukturu və mövcud sənədlər oxundu
- Növbəti sessiya üçün hazırlıq

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

- [ ] (boş — yeni iş əlavə et)

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
