package az.mbm.jooqsqlgenerate.enums;

import org.jooq.Field;

public enum MathOp {
    ADD("ADD"),
    SUBTRACT("SUBTRACT"),
    MULTIPLY("MULTIPLY"),
    DIVIDE("DIVIDE"),
    NONOPERATION("NONOPERATION");

    private final String value;

    MathOp(String value) {
        this.value = value;
    }

    /**
     * İki jOOQ sahəsinə bu riyazi əməliyyatı tətbiq edir:
     * {@code left OP right}.
     *
     * <p>{@link #NONOPERATION} (və digər tanınmayan hallar) üçün {@code left}
     * olduğu kimi qaytarılır — kitabxana daxilindəki bütün
     * {@code switch(MathOp)} bloklarının ortaq davranışı.
     *
     * <p>Qeyd: {@code DIVIDE} burada sadə bölmədir. Sıfıra bölmə qorunması
     * (NULLIF) lazım olan yerlərdə çağıran tərəf {@code DIVIDE} halını
     * özü idarə etməlidir.
     */
    public Field<? extends Number> apply(Field<? extends Number> left,
                                         Field<? extends Number> right) {
        return switch (this) {
            case ADD      -> left.add(right);
            case SUBTRACT -> left.subtract(right);
            case MULTIPLY -> left.mul(right);
            case DIVIDE   -> left.div(right);
            default       -> left;
        };
    }
}
