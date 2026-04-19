package az.mbm.jooqsqlgenerate.enums;


public enum MathOperation {
    ADD("ADD"),
    SUBTRACT("SUBTRACT"),
    MULTIPLY("MULTIPLY"),
    DIVIDE("DIVIDE"),
    NONOPERATION("NONOPERATION");
    private final String value;
    MathOperation(String value) {
        this.value = value;
    }
}


