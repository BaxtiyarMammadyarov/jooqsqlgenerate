package az.mbm.jooqsqlgenerate.enums;



public enum GroupFunction {
    SUM("SUM"),
    AVG("AVG"),
    COUNT("COUNT"),
    MIN("MIN"),
    MAX("MAX");

    private final String value;

    GroupFunction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
