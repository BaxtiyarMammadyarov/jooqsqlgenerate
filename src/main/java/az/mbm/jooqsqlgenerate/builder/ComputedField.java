package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.enums.MathOperation;

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
    private record Step(MathOperation op, String tableAlias, String fieldName,
                        ComputedField nested) {
        /** Sadə sahə addımı */
        static Step of(MathOperation op, String tableAlias, String fieldName) {
            return new Step(op, tableAlias, fieldName, null);
        }
        /** İç içə ifadə addımı */
        static Step nested(MathOperation op, ComputedField nested) {
            return new Step(op, null, null, nested);
        }
        boolean isNested() { return nested != null; }
    }

    private final String        firstTableAlias;
    private final String        firstFieldName;
    private final List<Step>    steps = new ArrayList<>();
    private       String        alias = null;

    private ComputedField(String tableAlias, String fieldName) {
        this.firstTableAlias = tableAlias;
        this.firstFieldName  = fieldName;
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

    // ─── Əməliyyat zənciri — sadə sahə ──────────────────────────────────

    /** {@code + field} */
    public ComputedField add(String tableAliasAndField) {
        return fieldStep(MathOperation.ADD, tableAliasAndField);
    }

    /** {@code - field} */
    public ComputedField subtract(String tableAliasAndField) {
        return fieldStep(MathOperation.SUBTRACT, tableAliasAndField);
    }

    /** {@code * field} */
    public ComputedField multiply(String tableAliasAndField) {
        return fieldStep(MathOperation.MULTIPLY, tableAliasAndField);
    }

    /** {@code / field} */
    public ComputedField divide(String tableAliasAndField) {
        return fieldStep(MathOperation.DIVIDE, tableAliasAndField);
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
        steps.add(Step.nested(MathOperation.ADD, nested));
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
        steps.add(Step.nested(MathOperation.SUBTRACT, nested));
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
        steps.add(Step.nested(MathOperation.MULTIPLY, nested));
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
        steps.add(Step.nested(MathOperation.DIVIDE, nested));
        return this;
    }

    private ComputedField fieldStep(MathOperation op, String tableAliasAndField) {
        String[] parts = split(tableAliasAndField);
        steps.add(Step.of(op, parts[0], parts[1]));
        return this;
    }

    // ─── Alias ───────────────────────────────────────────────────────────

    /**
     * SELECT alias — mütləq tələb olunur.
     *
     * <pre>{@code .as("totalCost") }</pre>
     */
    public ComputedField as(String alias) {
        this.alias = Objects.requireNonNull(alias, "ComputedField alias null ola bilməz");
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
        // Birinci sahə
        EntityTable<?> t0 = resolve(firstTableAlias, mainTable, tableMap);
        Field<Object> result = (Field<Object>) t0.getField(firstFieldName);

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
