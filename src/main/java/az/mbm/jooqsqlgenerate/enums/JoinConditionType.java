package az.mbm.jooqsqlgenerate.enums;

public enum JoinConditionType
{
    FIELD("FIELD"),
    VALUE("VALUE");

    public String getValue() {
        return value;
    }

    private final String value;

    JoinConditionType(String value) {
        this.value = value;
    }
}
