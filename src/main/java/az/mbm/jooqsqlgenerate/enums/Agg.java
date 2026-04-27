package az.mbm.jooqsqlgenerate.enums;



public enum Agg {
    SUM("SUM"),
    AVG("AVG"),
    COUNT("COUNT"),
    MIN("MIN"),
    MAX("MAX");

    private final String value;

    Agg(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
