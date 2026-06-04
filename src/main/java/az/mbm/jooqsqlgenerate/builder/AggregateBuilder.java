package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.enums.Agg;
import az.mbm.jooqsqlgenerate.enums.MathOp;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FLUENT BUILDER — GROUP BY + Aqreqat funksiyalar + HAVING
 *
 * <p>Sadə istifadə:
 * <pre>{@code
 *   AggregateBuilder.groupBy("u.status")
 *       .sum("o.amount").round(2).as("totalSum").done()
 *       .count("o.id").as("orderCount").done()
 * }</pre>
 *
 * <p>Çox sahəli ifadə ilə:
 * <pre>{@code
 *   AggregateBuilder.groupBy("o.customerId")
 *       .sumOf(
 *           ComputedField.of("o.price")
 *               .multiply("o.quantity")
 *               .subtract("o.discount")
 *       ).round(2).as("netRevenue").done()
 * }</pre>
 *
 * @param <T> entity tipi
 */
public class AggregateBuilder<T> {

    /**
     * Bir aqreqat sahəsinin tam konfiqurasiyası.
     *
     * <p>{@code computedExpr} null deyilsə — o istifadə olunur,
     * {@code tableAlias}/{@code fieldName}/{@code mathOp} nəzərə alınmır.
     */
    public record AggField(
            Agg    function,
            String           tableAlias,
            String           fieldName,
            MathOp    mathOp,
            String           mathField,
            String           alias,
            Integer          roundScale,
            Op havingOp,
            Object           havingValue,
            String           orderDirection,
            ComputedField    computedExpr,     // null → sadə sahə; non-null → çox sahəli ifadə
            IfExpr           ifExpr,           // null → sadə/computed; non-null → CASE WHEN ifadəsi (tam operand)
            IfExpr           mathIfExpr        // null → sadə math; non-null → math operandı CASE WHEN ifadəsidir
    ) {}

    private final List<String>        groupByFields  = new ArrayList<>();
    private final List<AggField>      aggFields      = new ArrayList<>();
    private final List<AggExistsClause> existsClauses = new ArrayList<>();

    private AggregateBuilder() {}

    // ════════════════════════════════════════════════════════════════════
    //  EXISTS / NOT EXISTS daxili data strukturu
    // ════════════════════════════════════════════════════════════════════

    /**
     * AggregateBuilder daxilindəki EXISTS / NOT EXISTS şərtinin data strukturu.
     *
     * <p>Condition build zamanı ({@link SelectQueryBuilder#buildGroupBy}) mainTable
     * və tableMap mövcuddur — ora qədər bu record saxlanılır.
     */
    public record AggExistsClause(
            Class<?>           existsEntity,
            boolean            negated,
            List<JoinFieldRow> joinFields,
            List<FilterRow>    filters,
            List<OrClauseRow>  orClauses
    ) {
        public record JoinFieldRow(String existsField, String mainAlias, String mainField) {}
        public record FilterRow(String field, Op op, Object value) {}
        /** EXISTS daxilindəki OR/AND qrupları */
        public record OrClauseRow(String orGroup, String andGroup, String field, Op op, Object value) {}

        /**
         * EXISTS Condition-unu qurur.
         * {@code mainTable} — ana cədvəlin EntityTable-ı (joinField üçün lazımdır).
         * {@code tableMap}  — bütün alias → EntityTable xəritəsi.
         */
        @SuppressWarnings("unchecked")
        public Condition toCondition(EntityTable<?> mainTable,
                                     Map<String, EntityTable<?>> tableMap) {
            EntityTable<?> existsTable = new EntityTable<>(existsEntity);
            Condition cond = null;

            // JOIN şərtləri: existsTable.field = mainAliasTable.field
            for (JoinFieldRow jf : joinFields) {
                EntityTable<?> aliasTable = tableMap.getOrDefault(
                        jf.mainAlias(), mainTable);
                Field<Object> eField = (Field<Object>) existsTable.getField(jf.existsField());
                Field<Object> mField = (Field<Object>) aliasTable.getField(jf.mainField());
                Condition c = eField.eq(mField);
                cond = (cond == null) ? c : cond.and(c);
            }

            // Literal filtr şərtləri
            for (FilterRow fr : filters) {
                Field<Object> f = (Field<Object>) existsTable.getField(fr.field());
                Condition c = FilterStrategies.get(fr.op()).apply(f, fr.value());
                cond = (cond == null) ? c : cond.and(c);
            }

            // OR/AND qrupları
            if (!orClauses.isEmpty()) {
                LinkedHashMap<String, LinkedHashMap<String, List<OrClauseRow>>> grouped =
                        new LinkedHashMap<>();
                for (OrClauseRow oc : orClauses) {
                    grouped.computeIfAbsent(oc.orGroup(),  k -> new LinkedHashMap<>())
                           .computeIfAbsent(oc.andGroup(), k -> new ArrayList<>())
                           .add(oc);
                }
                for (LinkedHashMap<String, List<OrClauseRow>> andGroups : grouped.values()) {
                    Condition orGroupCond = null;
                    for (List<OrClauseRow> andRows : andGroups.values()) {
                        Condition andCond = null;
                        for (OrClauseRow oc : andRows) {
                            Field<Object> f = (Field<Object>) existsTable.getField(oc.field());
                            Condition c = FilterStrategies.get(oc.op()).apply(f, oc.value());
                            andCond = (andCond == null) ? c : andCond.and(c);
                        }
                        orGroupCond = (orGroupCond == null) ? andCond : orGroupCond.or(andCond);
                    }
                    if (orGroupCond != null)
                        cond = (cond == null) ? orGroupCond : cond.and(orGroupCond);
                }
            }

            var subSelect = DSL.selectOne()
                    .from(existsTable.getTable())
                    .where(cond != null ? cond : DSL.trueCondition());

            return negated ? DSL.notExists(subSelect) : DSL.exists(subSelect);
        }
    }

    // ─── Static giriş nöqtəsi ────────────────────────────────────────────

    public static <T> AggregateBuilder<T> groupBy(String... fields) {
        AggregateBuilder<T> b = new AggregateBuilder<>();
        for (String f : fields) b.groupByFields.add(f);
        return b;
    }

    // ─── Sadə aqreqat funksiyaları (tək sahə) ───────────────────────────

    public AggStep<T> sum(String tableAliasAndField)   { return new AggStep<>(this, Agg.SUM,   tableAliasAndField); }
    public AggStep<T> count(String tableAliasAndField) { return new AggStep<>(this, Agg.COUNT, tableAliasAndField); }
    public AggStep<T> avg(String tableAliasAndField)   { return new AggStep<>(this, Agg.AVG,   tableAliasAndField); }
    public AggStep<T> max(String tableAliasAndField)   { return new AggStep<>(this, Agg.MAX,   tableAliasAndField); }
    public AggStep<T> min(String tableAliasAndField)   { return new AggStep<>(this, Agg.MIN,   tableAliasAndField); }

    // ─── IfExpr ilə şərtli aqreqat funksiyaları ─────────────────────────

    /**
     * {@code SUM(CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END)}
     *
     * <pre>{@code
     *   .sumIf("o.status", "PAID", "o.amount", "0").as("paidRevenue").done()
     *   // → SUM(CASE WHEN o.status='PAID' THEN o.amount ELSE 0 END) AS paidRevenue
     *
     *   // Conditional count (PAID sifariş sayı):
     *   .sumIf("o.status", "PAID", "1", "0").as("paidCount").done()
     *   // → SUM(CASE WHEN o.status='PAID' THEN 1 ELSE 0 END) AS paidCount
     * }</pre>
     */
    public AggStep<T> sumIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
        return new AggStep<>(this, Agg.SUM, IfExpr.of(condField, equalTo, thenVal, elseVal));
    }

    /**
     * {@code COUNT(CASE WHEN condField = equalTo THEN 1 END)}
     *
     * <pre>{@code
     *   .countIf("o.status", "PAID").as("paidCount").done()
     *   // → COUNT(CASE WHEN o.status='PAID' THEN 1 ELSE NULL END) AS paidCount
     * }</pre>
     */
    public AggStep<T> countIf(String condField, Object equalTo) {
        return new AggStep<>(this, Agg.COUNT, IfExpr.of(condField, equalTo, 1, null));
    }

    /**
     * {@code AVG(CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END)}
     *
     * <pre>{@code
     *   .avgIf("o.status", "PAID", "o.amount", "0").as("avgPaidAmount").done()
     * }</pre>
     */
    public AggStep<T> avgIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
        return new AggStep<>(this, Agg.AVG, IfExpr.of(condField, equalTo, thenVal, elseVal));
    }

    /**
     * {@code MAX(CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END)}
     */
    public AggStep<T> maxIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
        return new AggStep<>(this, Agg.MAX, IfExpr.of(condField, equalTo, thenVal, elseVal));
    }

    /**
     * {@code MIN(CASE WHEN condField = equalTo THEN thenVal ELSE elseVal END)}
     */
    public AggStep<T> minIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
        return new AggStep<>(this, Agg.MIN, IfExpr.of(condField, equalTo, thenVal, elseVal));
    }

    // ─── ComputedField ilə aqreqat funksiyaları (çox sahə) ──────────────

    /**
     * {@code SUM(expr)} — çox sahəli ifadə:
     *
     * <pre>{@code
     *   .sumOf(
     *       ComputedField.of("o.price")
     *           .multiply("o.quantity")
     *           .subtract("o.discount")
     *   ).round(2).as("netRevenue").done()
     * }</pre>
     */
    public AggStep<T> sumOf(ComputedField expr)   { return new AggStep<>(this, Agg.SUM,   expr); }
    public AggStep<T> countOf(ComputedField expr) { return new AggStep<>(this, Agg.COUNT, expr); }
    public AggStep<T> avgOf(ComputedField expr)   { return new AggStep<>(this, Agg.AVG,   expr); }
    public AggStep<T> maxOf(ComputedField expr)   { return new AggStep<>(this, Agg.MAX,   expr); }
    public AggStep<T> minOf(ComputedField expr)   { return new AggStep<>(this, Agg.MIN,   expr); }

    // ─── EXISTS / NOT EXISTS giriş nöqtələri ────────────────────────────────

    /**
     * HAVING EXISTS şərti əlavə edir.
     *
     * <pre>{@code
     *   AggregateBuilder.groupBy("t.status")
     *       .count("t.id").as("cnt").done()
     *       .exists(CashFlowEntity.class)
     *           .joinField("fkGroupId", "t", "id")
     *           .equal("status", "A")
     *       .done()
     * }</pre>
     */
    public <E> AggExistsBuilder<T, E> exists(Class<E> entity) {
        return new AggExistsBuilder<>(this, entity, false);
    }

    /**
     * HAVING NOT EXISTS şərti əlavə edir.
     *
     * <pre>{@code
     *   .notExists(BlockedEntity.class)
     *       .joinField("fkUserId", "u", "id")
     *   .done()
     * }</pre>
     */
    public <E> AggExistsBuilder<T, E> notExists(Class<E> entity) {
        return new AggExistsBuilder<>(this, entity, true);
    }

    // ─── Accessor-lar ────────────────────────────────────────────────────

    public List<String>          getGroupByFields()  { return groupByFields; }
    public List<AggField>        getAggFields()      { return aggFields;     }
    public List<AggExistsClause> getExistsClauses()  { return existsClauses; }

    // ─── jOOQ Field-ə çevirmə ────────────────────────────────────────────

    /**
     * {@link AggField}-i jOOQ {@link Field}-ə çevirir.
     *
     * @param agg      konfiqurasiya
     * @param table    əsas entity table
     * @param tableMap alias → EntityTable xəritəsi (JOIN-lər daxil)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Field<?> toJooqField(AggField agg, EntityTable<?> table,
                                       Map<String, EntityTable<?>> tableMap) {
        return buildAggExpr(agg, table, tableMap).as(agg.alias());
    }

    /**
     * Aggregate ifadəsini alias olmadan qurur.
     * Həm SELECT üçün ({@link #toJooqField}), həm HAVING üçün ({@link #buildHaving}) istifadə olunur.
     * PostgreSQL HAVING-də alias referansı dəstəkləmir — ifadənin özü lazımdır.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Field<?> buildAggExpr(AggField agg, EntityTable<?> table,
                                         Map<String, EntityTable<?>> tableMap) {
        Field<Object> operand;

        if (agg.ifExpr() != null) {
            // ─── CASE WHEN ifadəsi — IfExpr
            operand = (Field<Object>) agg.ifExpr().toField(table, tableMap);

        } else if (agg.computedExpr() != null) {
            // ─── Çox sahəli ifadə — ComputedField (alias olmadan, aggregate operandı kimi)
            operand = (Field<Object>) agg.computedExpr().buildExpr(table, tableMap);

        } else {
            // ─── Sadə sahə (± riyazi əməliyyat)
            Field<Object> baseField = (Field<Object>) table.getField(agg.fieldName());
            operand = baseField;

            if (agg.mathOp() != null && agg.mathOp() != MathOp.NONOPERATION) {
                Field<?> mathF = null;
                if (agg.mathIfExpr() != null) {
                    // CASE WHEN ifadəsi math operandı kimi
                    mathF = agg.mathIfExpr().toField(table, tableMap);
                } else if (agg.mathField() != null) {
                    // Sadə sahə math operandı
                    mathF = table.getField(agg.mathField());
                }
                if (mathF != null) {
                    @SuppressWarnings("unchecked")
                    Field<? extends Number> numMathF = (Field<? extends Number>) (Field<?>) mathF;
                    operand = (Field<Object>) switch (agg.mathOp()) {
                        case ADD      -> baseField.add(mathF);
                        case SUBTRACT -> baseField.subtract(mathF);
                        case MULTIPLY -> baseField.mul(numMathF);
                        case DIVIDE   -> baseField.div(numMathF);
                        default       -> baseField;
                    };
                }
            }
        }

        // DSL.sum / DSL.avg → Field<? extends Number> tələb edir
        @SuppressWarnings("unchecked")
        Field<? extends Number> numOperand = (Field<? extends Number>) (Field<?>) operand;

        Field<?> aggField = switch (agg.function()) {
            case SUM   -> DSL.sum(numOperand);
            case COUNT -> DSL.count(operand);
            case AVG   -> DSL.avg(numOperand);
            case MAX   -> DSL.max(operand);
            case MIN   -> DSL.min(operand);
        };

        // ROUND
        if (agg.roundScale() != null) {
            aggField = DSL.round((Field<? extends Number>) aggField, agg.roundScale());
        }

        return aggField;
    }

    /**
     * HAVING Condition-larını yığır.
     * PostgreSQL alias-ı HAVING-də tanımadığından aggregate ifadəsinin özü istifadə olunur:
     * məs. {@code HAVING SUM("totalPrice") > 100} — alias referansı deyil.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Condition buildHaving(List<AggField> aggFields, EntityTable<?> table,
                                        Map<String, EntityTable<?>> tableMap) {
        Condition having = null;
        for (AggField agg : aggFields) {
            if (agg.havingOp() == null) continue;
            Field<Object> f = (Field<Object>) buildAggExpr(agg, table, tableMap);
            Condition c = FilterStrategies.get(agg.havingOp()).apply(f, agg.havingValue());
            having = (having == null) ? c : having.and(c);
        }
        return having;
    }

    // ════════════════════════════════════════════════════════════════════
    //  AggStep — .as() .round() .having() .orderDesc() .done() zənciri
    // ════════════════════════════════════════════════════════════════════

    public static class AggStep<T> {
        private final AggregateBuilder<T> parent;
        private final Agg       function;
        // Sadə sahə
        private final String              tableAlias;
        private final String              fieldName;
        private       MathOp       mathOp    = MathOp.NONOPERATION;
        private       String              mathField = null;
        // Çox sahəli ifadə
        private final ComputedField       computedExpr;
        // CASE WHEN ifadəsi (tam operand)
        private final IfExpr              ifExpr;
        // CASE WHEN ifadəsi (math operandı)
        private       IfExpr              mathIfExpr = null;
        // Ümumi
        private       String              alias     = null;
        private       Integer             round     = null;
        private       Op    havingOp  = null;
        private       Object              havingVal = null;
        private       String              orderDir  = null;

        /** Sadə sahə konstruktoru */
        AggStep(AggregateBuilder<T> parent, Agg fn, String tableAliasAndField) {
            this.parent       = parent;
            this.function     = fn;
            this.computedExpr = null;
            this.ifExpr       = null;
            String[] parts    = tableAliasAndField.split("\\.", 2);
            this.tableAlias   = parts.length == 2 ? parts[0] : "";
            this.fieldName    = parts.length == 2 ? parts[1] : parts[0];
        }

        /** ComputedField konstruktoru */
        AggStep(AggregateBuilder<T> parent, Agg fn, ComputedField expr) {
            this.parent       = parent;
            this.function     = fn;
            this.computedExpr = expr;
            this.ifExpr       = null;
            this.tableAlias   = "";
            this.fieldName    = "";
        }

        /** IfExpr konstruktoru */
        AggStep(AggregateBuilder<T> parent, Agg fn, IfExpr expr) {
            this.parent       = parent;
            this.function     = fn;
            this.computedExpr = null;
            this.ifExpr       = expr;
            this.tableAlias   = "";
            this.fieldName    = "";
        }

        /** SELECT-dəki alias — mütləq tələb olunur.
         *  "t.totalPrice" kimi prefix avtomatik silinir → "totalPrice" saxlanır. */
        public AggStep<T> as(String alias) {
            if (alias != null) {
                int dot = alias.indexOf('.');
                this.alias = dot >= 0 ? alias.substring(dot + 1) : alias;
            } else {
                this.alias = null;
            }
            return this;
        }

        /** {@code ROUND(aggFunc, scale)} */
        public AggStep<T> round(int scale) {
            this.round = scale;
            return this;
        }

        // ─── Sadə riyazi əməliyyatlar (2 sahə) ──────────────────────────
        // Çox sahə lazımdırsa sumOf(ComputedField) istifadə edin

        /** {@code SUM(f1 + f2)} */
        public AggStep<T> add(String field)      { return math(MathOp.ADD,      field); }
        /** {@code SUM(f1 - f2)} */
        public AggStep<T> subtract(String field) { return math(MathOp.SUBTRACT, field); }
        /** {@code SUM(f1 * f2)} */
        public AggStep<T> multiply(String field) { return math(MathOp.MULTIPLY, field); }
        /** {@code SUM(f1 / f2)} */
        public AggStep<T> divide(String field)   { return math(MathOp.DIVIDE,   field); }

        private AggStep<T> math(MathOp op, String field) {
            this.mathOp    = op;
            this.mathField = field;
            return this;
        }

        // ─── IfExpr ilə riyazi əməliyyatlar ─────────────────────────────

        /**
         * {@code SUM(f1 + CASE WHEN cond THEN thenVal ELSE elseVal END)}
         *
         * <pre>{@code
         *   .sum("t.base").addIf("t.type", "BONUS", "t.bonus", 0).as("total").done()
         *   // → SUM(base + CASE WHEN type='BONUS' THEN bonus ELSE 0 END)
         * }</pre>
         */
        public AggStep<T> addIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
            return mathIf(MathOp.ADD, condField, equalTo, thenVal, elseVal);
        }

        /**
         * {@code SUM(f1 - CASE WHEN cond THEN thenVal ELSE elseVal END)}
         *
         * <pre>{@code
         *   .sum("t.revenue").subtractIf("t.type", "REFUND", "t.amount", 0).as("net").done()
         *   // → SUM(revenue - CASE WHEN type='REFUND' THEN amount ELSE 0 END)
         * }</pre>
         */
        public AggStep<T> subtractIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
            return mathIf(MathOp.SUBTRACT, condField, equalTo, thenVal, elseVal);
        }

        /**
         * {@code SUM(f1 * CASE WHEN cond THEN thenVal ELSE elseVal END)}
         *
         * <pre>{@code
         *   .sum("t.purchaseExpense").multiplyIf("t.actionType", "medaxil", 1, 0).as("expense").done()
         *   // → SUM(purchaseExpense * CASE WHEN actionType='medaxil' THEN 1 ELSE 0 END)
         * }</pre>
         */
        public AggStep<T> multiplyIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
            return mathIf(MathOp.MULTIPLY, condField, equalTo, thenVal, elseVal);
        }

        /**
         * {@code SUM(f1 / CASE WHEN cond THEN thenVal ELSE elseVal END)}
         *
         * <pre>{@code
         *   .sum("t.profit").divideIf("t.type", "SALE", "t.saleCount", 1).as("avg").done()
         *   // → SUM(profit / CASE WHEN type='SALE' THEN saleCount ELSE 1 END)
         * }</pre>
         */
        public AggStep<T> divideIf(String condField, Object equalTo, Object thenVal, Object elseVal) {
            return mathIf(MathOp.DIVIDE, condField, equalTo, thenVal, elseVal);
        }

        private AggStep<T> mathIf(MathOp op, String condField, Object equalTo, Object thenVal, Object elseVal) {
            this.mathOp     = op;
            this.mathIfExpr = IfExpr.of(condField, equalTo, thenVal, elseVal);
            return this;
        }

        // ─── HAVING ─────────────────────────────────────────────────────

        public AggStep<T> having(Op op, Object value) {
            this.havingOp  = op;
            this.havingVal = value;
            return this;
        }

        // ─── ORDER BY ───────────────────────────────────────────────────

        public AggStep<T> orderDesc() { this.orderDir = "DESC"; return this; }
        public AggStep<T> orderAsc()  { this.orderDir = "ASC";  return this; }

        // ─── Zəncir davam etdirmə ────────────────────────────────────────

        public AggStep<T> sum(String f)   { commit(); return parent.sum(f);   }
        public AggStep<T> count(String f) { commit(); return parent.count(f); }
        public AggStep<T> avg(String f)   { commit(); return parent.avg(f);   }
        public AggStep<T> max(String f)   { commit(); return parent.max(f);   }
        public AggStep<T> min(String f)   { commit(); return parent.min(f);   }

        public AggStep<T> sumOf(ComputedField e)   { commit(); return parent.sumOf(e);   }
        public AggStep<T> countOf(ComputedField e) { commit(); return parent.countOf(e); }
        public AggStep<T> avgOf(ComputedField e)   { commit(); return parent.avgOf(e);   }
        public AggStep<T> maxOf(ComputedField e)   { commit(); return parent.maxOf(e);   }
        public AggStep<T> minOf(ComputedField e)   { commit(); return parent.minOf(e);   }

        // ─── Yeni aqreqat başlatmaq üçün chain pass-through-lar ─────────────
        public AggStep<T> sumIf(String c, Object eq, Object t, Object e)   { commit(); return parent.sumIf(c, eq, t, e);   }
        public AggStep<T> countIf(String c, Object eq)                     { commit(); return parent.countIf(c, eq);        }
        public AggStep<T> avgIf(String c, Object eq, Object t, Object e)   { commit(); return parent.avgIf(c, eq, t, e);   }
        public AggStep<T> maxIf(String c, Object eq, Object t, Object e)   { commit(); return parent.maxIf(c, eq, t, e);   }
        public AggStep<T> minIf(String c, Object eq, Object t, Object e)   { commit(); return parent.minIf(c, eq, t, e);   }

        /** AggStep-i tamamlayır və AggregateBuilder-ə qayıdır */
        public AggregateBuilder<T> done() {
            commit();
            return parent;
        }

        private void commit() {
            if (alias == null)
                throw new IllegalStateException("AggStep: .as(alias) tələb olunur");
            parent.aggFields.add(new AggField(
                    function, tableAlias, fieldName,
                    mathOp, mathField, alias, round,
                    havingOp, havingVal, orderDir,
                    computedExpr, ifExpr, mathIfExpr));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  AggExistsBuilder
    // ════════════════════════════════════════════════════════════════════

    /**
     * Aggregate EXISTS / NOT EXISTS üçün fluent builder.
     *
     * <p>{@link AggregateBuilder#exists} / {@link AggregateBuilder#notExists} ilə açılır,
     * {@link #done()} ilə {@link AggregateBuilder}-ə qayıdır.
     *
     * <pre>{@code
     *   AggregateBuilder.groupBy("t.status")
     *       .count("t.id").as("cnt").done()
     *       .exists(CashFlowEntity.class)
     *           .joinField("fkGroupId", "t", "id")
     *           .equal("status",  "A")
     *           .in("typeId",     typeIds)
     *       .done()
     * }</pre>
     *
     * @param <T> ana entity tipi
     * @param <E> EXISTS alt-sorğusunun entity tipi
     */
    public static class AggExistsBuilder<T, E> {

        private final AggregateBuilder<T>                parent;
        private final Class<E>                           entity;
        private final boolean                            negated;
        private final List<AggExistsClause.JoinFieldRow> joinFields = new ArrayList<>();
        private final List<AggExistsClause.FilterRow>    filters    = new ArrayList<>();
        private final List<AggExistsClause.OrClauseRow>  orClauses  = new ArrayList<>();
        private       int                                orGroupCounter = 0;

        AggExistsBuilder(AggregateBuilder<T> parent, Class<E> entity, boolean negated) {
            this.parent  = parent;
            this.entity  = entity;
            this.negated = negated;
        }

        // ─── JOIN ────────────────────────────────────────────────────────────

        /**
         * EXISTS cədvəlinin sahəsini ana cədvəlin sahəsinə bağlayır.
         *
         * <pre>{@code .joinField("fkGroupId", "t", "id") }</pre>
         *
         * @param existsField EXISTS cədvəlindəki sahə adı (camelCase)
         * @param mainAlias   Ana cədvəlin alias-ı ("t", "u", ...)
         * @param mainField   Ana cədvəldəki sahə adı (camelCase)
         */
        public AggExistsBuilder<T, E> joinField(String existsField, String mainAlias, String mainField) {
            joinFields.add(new AggExistsClause.JoinFieldRow(existsField, mainAlias, mainField));
            return this;
        }

        // ─── Filterlər ────────────────────────────────────────────────────────

        /** Literal dəyər filtri — value null-dursa atlanır. */
        public AggExistsBuilder<T, E> filter(String field, Op op, Object value) {
            if (value != null) filters.add(new AggExistsClause.FilterRow(field, op, value));
            return this;
        }

        /** {@code WHERE field = value} */
        public AggExistsBuilder<T, E> equal(String field, Object value) {
            return filter(field, Op.EQUAl, value);
        }

        /** {@code WHERE field != value} */
        public AggExistsBuilder<T, E> notEqual(String field, Object value) {
            return filter(field, Op.NOT_EQUAL, value);
        }

        /** {@code WHERE field IN (...)} — null/boş kolleksiya atlanır */
        public AggExistsBuilder<T, E> in(String field, Collection<?> values) {
            if (values != null && !values.isEmpty())
                filters.add(new AggExistsClause.FilterRow(field, Op.IN, values));
            return this;
        }

        /** {@code WHERE field IN (...)} — varargs */
        public AggExistsBuilder<T, E> in(String field, Object... values) {
            return values != null && values.length > 0 ? in(field, Arrays.asList(values)) : this;
        }

        /** {@code WHERE field NOT IN (...)} — null/boş kolleksiya atlanır */
        public AggExistsBuilder<T, E> notIn(String field, Collection<?> values) {
            if (values != null && !values.isEmpty())
                filters.add(new AggExistsClause.FilterRow(field, Op.NOT_IN, values));
            return this;
        }

        /** {@code WHERE field NOT IN (...)} — varargs */
        public AggExistsBuilder<T, E> notIn(String field, Object... values) {
            return values != null && values.length > 0 ? notIn(field, Arrays.asList(values)) : this;
        }

        /** {@code WHERE field LIKE '%value%'} — null/boş atlanır */
        public AggExistsBuilder<T, E> like(String field, String value) {
            return filter(field, Op.LIKE, value);
        }

        /** {@code WHERE field IS NULL} */
        public AggExistsBuilder<T, E> isNull(String field) {
            filters.add(new AggExistsClause.FilterRow(field, Op.IS_EMPTY, ""));
            return this;
        }

        /** {@code WHERE field IS NOT NULL} */
        public AggExistsBuilder<T, E> isNotNull(String field) {
            filters.add(new AggExistsClause.FilterRow(field, Op.IS_NOT_EMPTY, ""));
            return this;
        }

        // ─── OR qrupu ─────────────────────────────────────────────────────────

        /**
         * EXISTS daxilində OR qrupu açır.
         *
         * <pre>{@code
         *   .orGroup()
         *       .or("fkFilterId", Op.EQUAl, filterId)
         *       .andBranch("b1")
         *           .add("fkTypeKey", Op.IN, typeList)
         *           .add("fkRoleId",  Op.EQUAl, roleId)
         *       .end()
         *   .done()
         * }</pre>
         */
        public AggExistsOrGroupBuilder<T, E> orGroup() {
            return new AggExistsOrGroupBuilder<>(this, "G" + (orGroupCounter++));
        }

        // ─── Tamamlama ────────────────────────────────────────────────────────

        /**
         * EXISTS builder-i tamamlayır, {@link AggregateBuilder}-ə qayıdır.
         * Condition HAVING-ə əlavə olunur.
         */
        public AggregateBuilder<T> done() {
            parent.existsClauses.add(
                    new AggExistsClause(entity, negated, joinFields, filters, orClauses));
            return parent;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  AggExistsOrGroupBuilder
    // ════════════════════════════════════════════════════════════════════

    /**
     * EXISTS daxilindəki OR qrupu builder-i.
     *
     * <p>{@link AggExistsBuilder#orGroup()} ilə açılır,
     * {@link #done()} ilə {@link AggExistsBuilder}-ə qayıdır.
     *
     * @param <T> ana entity tipi
     * @param <E> EXISTS alt-sorğusunun entity tipi
     */
    public static class AggExistsOrGroupBuilder<T, E> {

        private final AggExistsBuilder<T, E> existsBuilder;
        private final String                 orGroupName;
        private       int                    branchCounter = 0;

        AggExistsOrGroupBuilder(AggExistsBuilder<T, E> existsBuilder, String orGroupName) {
            this.existsBuilder = existsBuilder;
            this.orGroupName   = orGroupName;
        }

        /**
         * OR qrupuna tək şərt əlavə edir — hər biri ayrı AND branch kimi işlənir.
         *
         * @param value null olduqda şərt tətbiq edilmir
         */
        public AggExistsOrGroupBuilder<T, E> or(String field, Op op, Object value) {
            if (value != null)
                existsBuilder.orClauses.add(new AggExistsClause.OrClauseRow(
                        orGroupName, "_s" + (branchCounter++), field, op, value));
            return this;
        }

        /**
         * AND alt-qrupu açır — eyni branch alias-ı olan şərtlər AND ilə birləşir.
         *
         * <pre>{@code
         *   .andBranch("b1")
         *       .add("fkTypeKey", Op.IN,    typeList)
         *       .add("fkRoleId",  Op.EQUAl, roleId)
         *   .end()
         * }</pre>
         */
        public AggExistsAndBranchBuilder<T, E> andBranch(String alias) {
            return new AggExistsAndBranchBuilder<>(this, alias);
        }

        /** OR qrupunu bağlayır, {@link AggExistsBuilder}-ə qayıdır. */
        public AggExistsBuilder<T, E> done() {
            return existsBuilder;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  AggExistsAndBranchBuilder
    // ════════════════════════════════════════════════════════════════════

    /**
     * EXISTS daxilindəki AND alt-qrupu builder-i.
     *
     * <p>{@link AggExistsOrGroupBuilder#andBranch(String)} ilə açılır,
     * {@link #end()} ilə {@link AggExistsOrGroupBuilder}-ə qayıdır.
     *
     * @param <T> ana entity tipi
     * @param <E> EXISTS alt-sorğusunun entity tipi
     */
    public static class AggExistsAndBranchBuilder<T, E> {

        private final AggExistsOrGroupBuilder<T, E> orGroupBuilder;
        private final String                        branchAlias;

        AggExistsAndBranchBuilder(AggExistsOrGroupBuilder<T, E> orGroupBuilder, String branchAlias) {
            this.orGroupBuilder = orGroupBuilder;
            this.branchAlias    = branchAlias;
        }

        /**
         * AND alt-qrupuna şərt əlavə edir.
         *
         * @param value null olduqda şərt tətbiq edilmir
         */
        public AggExistsAndBranchBuilder<T, E> add(String field, Op op, Object value) {
            if (value != null)
                orGroupBuilder.existsBuilder.orClauses.add(new AggExistsClause.OrClauseRow(
                        orGroupBuilder.orGroupName, branchAlias, field, op, value));
            return this;
        }

        /** AND alt-qrupunu bağlayır, {@link AggExistsOrGroupBuilder}-ə qayıdır. */
        public AggExistsOrGroupBuilder<T, E> end() {
            return orGroupBuilder;
        }
    }
}
