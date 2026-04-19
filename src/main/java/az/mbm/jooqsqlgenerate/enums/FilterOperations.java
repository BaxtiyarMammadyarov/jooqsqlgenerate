package az.mbm.jooqsqlgenerate.enums;


public enum FilterOperations {

    EQUAl("equal"),
    NOT_EQUAL("notEqual"),
    IN("in"),
    COMPOSITE_IN("compositeIn"),
    NOT_IN("notIn"),
    OR("or"),
    LIKE("like"),
    IS_EMPTY("isEmpty"),
    IS_NOT_EMPTY("isNotEmpty"),
    LESS_THAN("lessThan"),
    LESS_THAN_OR_EQUAL_TO("lessThanOrEqualTo"),
    GREATER_THAN("greaterThan"),
    GREATER_THAN_OR_EQUAL_TO("greaterThanOrEqualTo"),
    BETWEEN("between"),
    START_WITH("startWith"),
    END_WITH("endWith"),
    NOT_REGEXP("notRegexp"),
    REGEXP("regexp");
    private final String value;

    FilterOperations(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
