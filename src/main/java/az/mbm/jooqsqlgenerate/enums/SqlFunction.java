package az.mbm.jooqsqlgenerate.enums;

public enum SqlFunction {
//    SUBSTRING("substring"),
    NONE("none")
//    NONE("distinct")
//    CONCAT("concat"),
//    COALESCE("coalesce"),
//    CAST("cast"),
//    CASE("case")
    ;
    private final String value;

    SqlFunction(String value) {
        this.value = value;
    }
}
