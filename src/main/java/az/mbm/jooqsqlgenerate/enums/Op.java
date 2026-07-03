package az.mbm.jooqsqlgenerate.enums;


public enum Op {

    /**
     * @deprecated Adında yazı səhvi var (EQUAl) — yeni kodda {@link #EQUAL} istifadə edin.
     * Geri uyğunluq üçün saxlanılır və davranışı {@link #EQUAL} ilə eynidir.
     */
    @Deprecated
    EQUAl("equal"),

    /** {@code field = value} — {@link #EQUAl}-ın düzgün adlanmış qarşılığı. */
    EQUAL("equal"),

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
    REGEXP("regexp"),

    // ─── Türk əlifbası case-insensitive LIKE ─────────────────────────────
    // LOWER(REPLACE(REPLACE(field,'İ','i'),'I','i')) LIKE '%value%'
    LIKE_IGNORE_CASE("likeIgnoreCase"),
    START_WITH_IGNORE_CASE("startWithIgnoreCase"),
    END_WITH_IGNORE_CASE("endWithIgnoreCase"),

    // ─── ROUND müqayisə əməliyyatları (WHERE ROUND(field, scale) OP value) ──
    // Scale 0 — tam ədədə yuvarlama (ROUND(0.4,0)=0  ROUND(0.6,0)=1)
    EQUAL_ROUND_0("equalRound0"),
    NOT_EQUAL_ROUND_0("notEqualRound0"),
    GREATER_THAN_ROUND_0("greaterThanRound0"),
    GREATER_THAN_OR_EQUAL_TO_ROUND_0("greaterThanOrEqualToRound0"),
    LESS_THAN_ROUND_0("lessThanRound0"),
    LESS_THAN_OR_EQUAL_TO_ROUND_0("lessThanOrEqualToRound0"),

    // Scale 1 — bir onluq rəqəm
    EQUAL_ROUND_1("equalRound1"),
    NOT_EQUAL_ROUND_1("notEqualRound1"),
    GREATER_THAN_ROUND_1("greaterThanRound1"),
    GREATER_THAN_OR_EQUAL_TO_ROUND_1("greaterThanOrEqualToRound1"),
    LESS_THAN_ROUND_1("lessThanRound1"),
    LESS_THAN_OR_EQUAL_TO_ROUND_1("lessThanOrEqualToRound1"),

    // Scale 2 — iki onluq rəqəm (qiymət, məbləğ üçün ən çox istifadə olunur)
    EQUAL_ROUND_2("equalRound2"),
    NOT_EQUAL_ROUND_2("notEqualRound2"),
    GREATER_THAN_ROUND_2("greaterThanRound2"),
    GREATER_THAN_OR_EQUAL_TO_ROUND_2("greaterThanOrEqualToRound2"),
    LESS_THAN_ROUND_2("lessThanRound2"),
    LESS_THAN_OR_EQUAL_TO_ROUND_2("lessThanOrEqualToRound2"),

    // Scale 3 — üç onluq rəqəm
    EQUAL_ROUND_3("equalRound3"),
    NOT_EQUAL_ROUND_3("notEqualRound3"),
    GREATER_THAN_ROUND_3("greaterThanRound3"),
    GREATER_THAN_OR_EQUAL_TO_ROUND_3("greaterThanOrEqualToRound3"),
    LESS_THAN_ROUND_3("lessThanRound3"),
    LESS_THAN_OR_EQUAL_TO_ROUND_3("lessThanOrEqualToRound3"),

    // Scale 4 — dörd onluq rəqəm
    EQUAL_ROUND_4("equalRound4"),
    NOT_EQUAL_ROUND_4("notEqualRound4"),
    GREATER_THAN_ROUND_4("greaterThanRound4"),
    GREATER_THAN_OR_EQUAL_TO_ROUND_4("greaterThanOrEqualToRound4"),
    LESS_THAN_ROUND_4("lessThanRound4"),
    LESS_THAN_OR_EQUAL_TO_ROUND_4("lessThanOrEqualToRound4");

    private final String value;

    Op(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
