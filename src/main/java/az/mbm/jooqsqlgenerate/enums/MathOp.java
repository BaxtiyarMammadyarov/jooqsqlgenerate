package az.mbm.jooqsqlgenerate.enums;


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
}


