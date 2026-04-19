package az.mbm.jooqsqlgenerate.builder;

import org.jooq.*;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.FilterOperations;
import az.mbm.jooqsqlgenerate.enums.GroupFunction;
import az.mbm.jooqsqlgenerate.enums.MathOperation;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
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
            GroupFunction    function,
            String           tableAlias,
            String           fieldName,
            MathOperation    mathOp,
            String           mathField,
            String           alias,
            Integer          roundScale,
            FilterOperations havingOp,
            Object           havingValue,
            String           orderDirection,
            ComputedField    computedExpr      // null → sadə sahə; non-null → çox sahəli ifadə
    ) {}

    private final List<String>   groupByFields = new ArrayList<>();
    private final List<AggField> aggFields     = new ArrayList<>();

    private AggregateBuilder() {}

    // ─── Static giriş nöqtəsi ────────────────────────────────────────────

    public static <T> AggregateBuilder<T> groupBy(String... fields) {
        AggregateBuilder<T> b = new AggregateBuilder<>();
        for (String f : fields) b.groupByFields.add(f);
        return b;
    }

    // ─── Sadə aqreqat funksiyaları (tək sahə) ───────────────────────────

    public AggStep<T> sum(String tableAliasAndField)   { return new AggStep<>(this, GroupFunction.SUM,   tableAliasAndField); }
    public AggStep<T> count(String tableAliasAndField) { return new AggStep<>(this, GroupFunction.COUNT, tableAliasAndField); }
    public AggStep<T> avg(String tableAliasAndField)   { return new AggStep<>(this, GroupFunction.AVG,   tableAliasAndField); }
    public AggStep<T> max(String tableAliasAndField)   { return new AggStep<>(this, GroupFunction.MAX,   tableAliasAndField); }
    public AggStep<T> min(String tableAliasAndField)   { return new AggStep<>(this, GroupFunction.MIN,   tableAliasAndField); }

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
    public AggStep<T> sumOf(ComputedField expr)   { return new AggStep<>(this, GroupFunction.SUM,   expr); }
    public AggStep<T> countOf(ComputedField expr) { return new AggStep<>(this, GroupFunction.COUNT, expr); }
    public AggStep<T> avgOf(ComputedField expr)   { return new AggStep<>(this, GroupFunction.AVG,   expr); }
    public AggStep<T> maxOf(ComputedField expr)   { return new AggStep<>(this, GroupFunction.MAX,   expr); }
    public AggStep<T> minOf(ComputedField expr)   { return new AggStep<>(this, GroupFunction.MIN,   expr); }

    // ─── Accessor-lar ────────────────────────────────────────────────────

    public List<String>   getGroupByFields() { return groupByFields; }
    public List<AggField> getAggFields()     { return aggFields;     }

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
        Field<Object> operand;

        if (agg.computedExpr() != null) {
            // ─── Çox sahəli ifadə — ComputedField (alias olmadan, aggregate operandı kimi)
            operand = (Field<Object>) agg.computedExpr().buildExpr(table, tableMap);

        } else {
            // ─── Sadə sahə (± riyazi əməliyyat)
            Field<Object> baseField = (Field<Object>) table.getField(agg.fieldName());
            operand = baseField;

            if (agg.mathOp() != null && agg.mathOp() != MathOperation.NONOPERATION
                    && agg.mathField() != null) {
                Field<?> mathF = table.getField(agg.mathField());
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

        return aggField.as(agg.alias());
    }

    /** HAVING Condition-larını yığır */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Condition buildHaving(List<AggField> aggFields, EntityTable<?> table) {
        Condition having = null;
        for (AggField agg : aggFields) {
            if (agg.havingOp() == null) continue;
            Field<Object> f = (Field<Object>) DSL.field(DSL.name(agg.alias()));
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
        private final GroupFunction       function;
        // Sadə sahə
        private final String              tableAlias;
        private final String              fieldName;
        private       MathOperation       mathOp    = MathOperation.NONOPERATION;
        private       String              mathField = null;
        // Çox sahəli ifadə
        private final ComputedField       computedExpr;
        // Ümumi
        private       String              alias     = null;
        private       Integer             round     = null;
        private       FilterOperations    havingOp  = null;
        private       Object              havingVal = null;
        private       String              orderDir  = null;

        /** Sadə sahə konstruktoru */
        AggStep(AggregateBuilder<T> parent, GroupFunction fn, String tableAliasAndField) {
            this.parent       = parent;
            this.function     = fn;
            this.computedExpr = null;
            String[] parts    = tableAliasAndField.split("\\.", 2);
            this.tableAlias   = parts.length == 2 ? parts[0] : "";
            this.fieldName    = parts.length == 2 ? parts[1] : parts[0];
        }

        /** ComputedField konstruktoru */
        AggStep(AggregateBuilder<T> parent, GroupFunction fn, ComputedField expr) {
            this.parent       = parent;
            this.function     = fn;
            this.computedExpr = expr;
            this.tableAlias   = "";
            this.fieldName    = "";
        }

        /** SELECT-dəki alias — mütləq tələb olunur */
        public AggStep<T> as(String alias) {
            this.alias = alias;
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
        public AggStep<T> add(String field)      { return math(MathOperation.ADD,      field); }
        /** {@code SUM(f1 - f2)} */
        public AggStep<T> subtract(String field) { return math(MathOperation.SUBTRACT, field); }
        /** {@code SUM(f1 * f2)} */
        public AggStep<T> multiply(String field) { return math(MathOperation.MULTIPLY, field); }
        /** {@code SUM(f1 / f2)} */
        public AggStep<T> divide(String field)   { return math(MathOperation.DIVIDE,   field); }

        private AggStep<T> math(MathOperation op, String field) {
            this.mathOp    = op;
            this.mathField = field;
            return this;
        }

        // ─── HAVING ─────────────────────────────────────────────────────

        public AggStep<T> having(FilterOperations op, Object value) {
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
                    computedExpr));
        }
    }
}
