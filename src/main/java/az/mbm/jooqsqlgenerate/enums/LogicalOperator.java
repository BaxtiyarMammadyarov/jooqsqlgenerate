package az.mbm.jooqsqlgenerate.enums;




public enum LogicalOperator {
    OR("or"),
    AND("and"),
    NON("non");
    private final String value;

    LogicalOperator(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
