package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.Op;
import az.mbm.jooqsqlgenerate.enums.MathOp;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * FLUENT BUILDER — istənilən sayda sahə ilə riyazi SELECT sütunu.
 *
 * <p>Əməliyyatlar <b>soldan sağa</b> tətbiq edilir — riyazi ardıcıllığa nəzarət etmək üçün
 * iç içə {@code ComputedField} istifadə edin:
 *
 * <pre>{@code
 *   // Sadə zəncir (sol-dan sağa): (price + tax + shipping) - discount
 *   ComputedField.of("o.price")
 *       .add("o.tax")
 *       .add("o.shipping")
 *       .subtract("o.discount")
 *       .as("totalCost")
 *
 *   // Mötərizəli qrup: price + (tax * quantity)
 *   // .add( ComputedField.expr("o.tax").multiply("o.quantity") )
 *   ComputedField.of("o.price")
 *       .add(ComputedField.expr("o.tax").multiply("o.quantity"))
 *       .as("netPrice")
 *
 *   // ((price - cost) / price) * 100 AS marginPct
 *   ComputedField.of("o.price")
 *       .subtract("o.cost")
 *       .divide("o.price")         // bu qrup mötərizəyə alınır
 *       .multiply("o.hundred")
 *       .as("marginPct")
 *
 *   // (width * height * depth) AS volume
 *   ComputedField.of("p.width")
 *       .multiply("p.height")
 *       .multiply("p.depth")
 *       .as("volume")
 *
 *   // price + (qty * unitPrice) - (discount * qty)
 *   ComputedField.of("o.base")
 *       .add(ComputedField.expr("o.qty").multiply("o.unitPrice"))
 *       .subtract(ComputedField.expr("o.discount").multiply("o.qty"))
 *       .as("lineTotal")
 * }</pre>
 */
public class ComputedField {

    /** Bir addım: əməliyyat + ya sahə adı, ya da iç içə ComputedField ifadəsi */
    private record Step(MathOp op, String tableAlias, String fieldName,
                        ComputedField nested) {
        /** Sadə sahə addımı */
        static Step of(MathOp op, String tableAlias, String fieldName) {
            return new Step(op, tableAlias, fieldName, null);
        }
        /** İç içə ifadə addımı */
        static Step nested(MathOp op, ComputedField nested) {
            return new Step(op, null, null, nested);
        }
        boolean isNested() { return nested != null; }
    }

    /**
     * WHERE filter şərti — {@code CASE WHEN condition THEN expr ELSE NULL END} üçün.
     * Bir neçə {@code .where()} AND ilə birləşir.
     */
    private record FilterClause(String tableAlias, String fieldName,
                                Op op, Object value) {}

    private final String             firstTableAlias;
    private final String             firstFieldName;
    /** {@code sumOf()} üçün: ilk hissə başqa bir ComputedField ifadəsidir */
    private final ComputedField      firstNested;
    private final List<Step>         steps         = new ArrayList<>();
    private final List<FilterClause> filterClauses = new ArrayList<>();
    private       String             alias         = null;

    /** Sadə sahə konstruktoru */
    private ComputedField(String tableAlias, String fieldName) {
        this.firstTableAlias = tableAlias;
        this.firstFieldName  = fieldName;
        this.firstNested     = null;
    }

    /** sumOf() üçün: ilk element ComputedField ifadəsidir */
    private ComputedField(ComputedField firstNested) {
        this.firstTableAlias = null;
        this.firstFieldName  = null;
        this.firstNested     = firstNested;
    }

    // ─── Giriş nöqtəsi ───────────────────────────────────────────────────

    /**
     * İlk sahəni təyin edir — SELECT alias ilə bitirilməli.
     *
     * <pre>{@code ComputedField.of("o.price").add("o.tax").as("total") }</pre>
     */
    public static ComputedField of(String tableAliasAndField) {
        String[] parts = split(tableAliasAndField);
        return new ComputedField(parts[0], parts[1]);
    }

    /**
     * Mötərizəli alt-ifadə üçün — alias olmadan, başqa {@code ComputedField}-ə operand kimi istifadə edilir.
     *
     * <pre>{@code
     *   // price + (tax * qty)
     *   ComputedField.of("o.price")
     *       .add( ComputedField.expr("o.tax").multiply("o.qty") )
     *       .as("total")
     * }</pre>
     */
    public static ComputedField expr(String tableAliasAndField) {
        return of(tableAliasAndField);   // alias olmadan da işləyir — toField() yoxlayır
    }

    /**
     * Bir neçə hissəni toplayır — hər hissənin öz riyazi əməliyyatı ola bilər.
     *
     * <p>Hər part üçün {@link #expr(String)} istifadə edin. Nəticə:
     * {@code part1 + part2 + part3 + ...}
     *
     * <pre>{@code
     *   // (price * qty) + tax + (shipping - discount) AS grandTotal
     *   ComputedField.sumOf(
     *       ComputedField.expr("o.price").multiply("o.qty"),
     *       ComputedField.expr("o.tax"),
     *       ComputedField.expr("o.shipping").subtract("o.discount")
     *   ).as("grandTotal")
     *
     *   // (width * height) + (depth * height) + baseArea AS totalSurface
     *   ComputedField.sumOf(
     *       ComputedField.expr("p.width").multiply("p.height"),
     *       ComputedField.expr("p.depth").multiply("p.height"),
     *       ComputedField.expr("p.baseArea")
     *   ).as("totalSurface")
     *
     *   // WHERE filter ilə birlikdə
     *   ComputedField.sumOf(
     *       ComputedField.expr("o.price").multiply("o.qty"),
     *       ComputedField.expr("o.tax")
     *   )
     *   .where("o.status", Op.EQUAl, "ACTIVE")
     *   .as("activeTotal")
     * }</pre>
     *
     * @param first   birinci hissə (ən azı bir tələb olunur)
     * @param rest    qalan hissələr (sıfır və ya daha çox)
     */
    public static ComputedField sumOf(ComputedField first, ComputedField... rest) {
        Objects.requireNonNull(first, "sumOf: birinci hissə null ola bilməz");
        ComputedField cf = new ComputedField(first);
        for (ComputedField part : rest) {
            Objects.requireNonNull(part, "sumOf: hissə null ola bilməz");
            cf.steps.add(Step.nested(MathOp.ADD, part));
        }
        return cf;
    }

    // ─── Əməliyyat zənciri — sadə sahə ──────────────────────────────────

    /** {@code + field} */
    public ComputedField add(String tableAliasAndField) {
        return fieldStep(MathOp.ADD, tableAliasAndField);
    }

    /** {@code - field} */
    public ComputedField subtract(String tableAliasAndField) {
        return fieldStep(MathOp.SUBTRACT, tableAliasAndField);
    }

    /** {@code * field} */
    public ComputedField multiply(String tableAliasAndField) {
        return fieldStep(MathOp.MULTIPLY, tableAliasAndField);
    }

    /** {@code / field} */
    public ComputedField divide(String tableAliasAndField) {
        return fieldStep(MathOp.DIVIDE, tableAliasAndField);
    }

    // ─── Əməliyyat zənciri — iç içə ifadə (mötərizəli qrup) ─────────────

    /**
     * {@code + (expr)} — mötərizəli alt-ifadə.
     *
     * <pre>{@code
     *   // price + (tax * qty)
     *   ComputedField.of("o.price")
     *       .add(ComputedField.expr("o.tax").multiply("o.qty"))
     *       .as("total")
     * }</pre>
     */
    public ComputedField add(ComputedField nested) {
        steps.add(Step.nested(MathOp.ADD, nested));
        return this;
    }

    /**
     * {@code - (expr)} — mötərizəli alt-ifadə.
     *
     * <pre>{@code
     *   // total - (discount * qty)
     *   .subtract(ComputedField.expr("o.discount").multiply("o.qty"))
     * }</pre>
     */
    public ComputedField subtract(ComputedField nested) {
        steps.add(Step.nested(MathOp.SUBTRACT, nested));
        return this;
    }

    /**
     * {@code * (expr)} — mötərizəli alt-ifadə.
     *
     * <pre>{@code
     *   // base * (1 + taxRate)  → addRawSelectField ilə birlikdə
     * }</pre>
     */
    public ComputedField multiply(ComputedField nested) {
        steps.add(Step.nested(MathOp.MULTIPLY, nested));
        return this;
    }

    /**
     * {@code / (expr)} — mötərizəli alt-ifadə.
     *
     * <pre>{@code
     *   // profit / (revenue + other)
     *   .divide(ComputedField.expr("o.revenue").add("o.other"))
     * }</pre>
     */
    public ComputedField divide(ComputedField nested) {
        steps.add(Step.nested(MathOp.DIVIDE, nested));
        return this;
    }

    private ComputedField fieldStep(MathOp op, String tableAliasAndField) {
        String[] parts = split(tableAliasAndField);
        steps.add(Step.of(op, parts[0], parts[1]));
        return this;
    }

    // ─── WHERE filter (group funksiyasız CASE WHEN) ──────────────────────

    /**
     * Riyazi ifadəyə şərt əlavə edir — group funksiyası olmadan.
     *
     * <p>Nəticə SQL-də belə görünür:
     * <pre>{@code CASE WHEN condition THEN (expr) ELSE NULL END AS alias }</pre>
     *
     * <p>Bir neçə {@code .where()} AND ilə birləşir:
     * <pre>{@code
     *   // CASE WHEN o.status = 'PAID' AND o.type = 'SALE'
     *   //      THEN (o.price - o.discount)
     *   //      ELSE NULL END AS paidNet
     *   ComputedField.of("o.price")
     *       .subtract("o.discount")
     *       .where("o.status", Op.EQUAl, "PAID")
     *       .where("o.type",   Op.EQUAl, "SALE")
     *       .as("paidNet")
     * }</pre>
     *
     * @param tableAliasAndField  "alias.field" formatında sahə
     * @param op                  müqayisə əməliyyatı
     * @param value               filter dəyəri
     */
    public ComputedField where(String tableAliasAndField,
                               Op op,
                               Object value) {
        String[] parts = split(tableAliasAndField);
        filterClauses.add(new FilterClause(parts[0], parts[1], op, value));
        return this;
    }

    // ─── Alias ───────────────────────────────────────────────────────────

    /**
     * SELECT alias — mütləq tələb olunur.
     *
     * <pre>{@code .as("totalCost") }</pre>
     */
    public ComputedField as(String alias) {
        Objects.requireNonNull(alias, "ComputedField alias null ola bilməz");
        int dot = alias.indexOf('.');
        this.alias = dot >= 0 ? alias.substring(dot + 1) : alias;
        return this;
    }

    // ─── jOOQ Field-ə çevirmə ────────────────────────────────────────────

    /**
     * Builder-i jOOQ {@link Field}-ə çevirir.
     * {@link SelectQueryBuilder} tərəfindən çağrılır.
     *
     * @param mainTable  ana entity table
     * @param tableMap   alias → EntityTable xəritəsi (JOIN-lər daxil)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> toField(EntityTable<?> mainTable,
                            java.util.Map<String, EntityTable<?>> tableMap) {
        if (alias == null)
            throw new IllegalStateException("ComputedField: .as(alias) tələb olunur");
        return buildExpr(mainTable, tableMap).as(alias);
    }

    /**
     * Alias olmadan ifadə qurur — iç içə {@code expr()} üçün istifadə edilir.
     * Nəticə avtomatik mötərizəyə alınır ki, xarici əməliyyatla düzgün birləşsin.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Field<Object> buildExpr(EntityTable<?> mainTable,
                            java.util.Map<String, EntityTable<?>> tableMap) {
        // Birinci element: ya sadə sahə, ya da sumOf()-dan gələn nested ifadə
        Field<Object> result;
        if (firstNested != null) {
            result = firstNested.buildExpr(mainTable, tableMap);
        } else {
            EntityTable<?> t0 = resolve(firstTableAlias, mainTable, tableMap);
            result = (Field<Object>) t0.getField(firstFieldName);
        }

        // Zəncir əməliyyatlar
        for (Step s : steps) {
            Field<?> operand;

            if (s.isNested()) {
                // İç içə ifadə — mötərizəyə alınmış alt-ifadə
                operand = s.nested().buildExpr(mainTable, tableMap);
            } else {
                // Sadə sahə
                EntityTable<?> t = resolve(s.tableAlias(), mainTable, tableMap);
                operand = t.getField(s.fieldName());
            }

            Field<? extends Number> numOperand = (Field<? extends Number>) (Field<?>) operand;

            result = (Field<Object>) switch (s.op()) {
                case ADD      -> result.add(operand);
                case SUBTRACT -> result.subtract(operand);
                case MULTIPLY -> result.mul(numOperand);
                case DIVIDE   -> result.div(numOperand);
                default       -> result;
            };
        }

        // ─── WHERE filter varsa → CASE WHEN cond THEN result ELSE NULL END ──
        if (!filterClauses.isEmpty()) {
            Condition cond = null;
            for (FilterClause fc : filterClauses) {
                EntityTable<?> ft = resolve(fc.tableAlias(), mainTable, tableMap);
                @SuppressWarnings("unchecked")
                Field<Object> ff = (Field<Object>) ft.getField(fc.fieldName());
                Condition c = FilterStrategies.get(fc.op()).apply(ff, fc.value());
                cond = (cond == null) ? c : cond.and(c);
            }
            result = (Field<Object>) DSL.when(cond, result).otherwise(DSL.castNull(Object.class));
        }

        return result;
    }

    // ─── Accessor-lar (JooqManager üçün) ─────────────────────────────────

    public String getAlias() { return alias; }

    // ─── Yardımcı ─────────────────────────────────────────────────────────

    private static String[] split(String tableAliasAndField) {
        if (tableAliasAndField == null || tableAliasAndField.isBlank())
            throw new IllegalArgumentException("Sahə adı boş ola bilməz");
        int dot = tableAliasAndField.indexOf('.');
        if (dot > 0)
            return new String[]{
                tableAliasAndField.substring(0, dot),
                tableAliasAndField.substring(dot + 1)
            };
        return new String[]{"", tableAliasAndField};
    }

    private static EntityTable<?> resolve(String tableAlias,
                                          EntityTable<?> mainTable,
                                          java.util.Map<String, EntityTable<?>> tableMap) {
        if (tableAlias == null || tableAlias.isBlank()) return mainTable;
        return tableMap.getOrDefault(tableAlias, mainTable);
    }
}
