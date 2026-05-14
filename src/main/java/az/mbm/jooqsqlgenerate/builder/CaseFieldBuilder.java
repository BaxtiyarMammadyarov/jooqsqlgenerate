package az.mbm.jooqsqlgenerate.builder;

import az.mbm.jooqsqlgenerate.core.EntityTable;
import az.mbm.jooqsqlgenerate.strategy.FilterStrategies;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Map;

/**
 * Package-private utility — {@link CaseBuilder}-i jOOQ {@link Field}-ə çevirir.
 * {@link SelectQueryBuilder} və {@link SubSelectBuilder} tərəfindən istifadə edilir.
 */
final class CaseFieldBuilder {

    private CaseFieldBuilder() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Field<?> build(CaseBuilder<?> cb,
                          EntityTable<?> mainTable,
                          Map<String, EntityTable<?>> tableMap) {
        if (cb.whenClauses().isEmpty())
            throw new IllegalStateException("CaseBuilder: heç bir WHEN şərti yoxdur");
        if (cb.alias() == null)
            throw new IllegalStateException("CaseBuilder: .as(alias) tələb olunur");

        org.jooq.CaseConditionStep caseStep = null;

        for (CaseBuilder.WhenClause wc : cb.whenClauses()) {
            String        rawField  = wc.field();
            EntityTable<?> t        = resolveTable(rawField, mainTable, tableMap);
            String        fieldName = fieldPart(rawField);

            Field<Object> f    = (Field<Object>) t.getField(fieldName);
            var           cond = FilterStrategies.get(wc.op()).apply(f, wc.whenVal());

            Field<?> then = wc.thenIsField()
                    ? resolveFieldRef((String) wc.thenVal(), mainTable, tableMap)
                    : DSL.val(wc.thenVal());

            caseStep = (caseStep == null)
                    ? DSL.case_().when(cond, then)
                    : caseStep.when(cond, then);
        }

        Field<?> elseField = cb.elseIsField()
                ? resolveFieldRef((String) cb.elseValue(), mainTable, tableMap)
                : (cb.elseValue() != null ? DSL.val(cb.elseValue()) : DSL.inline((Object) null));

        return caseStep.otherwise(elseField).as(cb.alias());
    }

    // ─── köməkçi metodlar ─────────────────────────────────────────────────

    private static EntityTable<?> resolveTable(String aliasAndField,
                                               EntityTable<?> mainTable,
                                               Map<String, EntityTable<?>> tableMap) {
        if (tableMap == null) return mainTable;
        String alias = aliasPart(aliasAndField);
        return alias.isEmpty() ? mainTable : tableMap.getOrDefault(alias, mainTable);
    }

    private static Field<?> resolveFieldRef(String aliasAndField,
                                            EntityTable<?> mainTable,
                                            Map<String, EntityTable<?>> tableMap) {
        EntityTable<?> t = resolveTable(aliasAndField, mainTable, tableMap);
        return t.getField(fieldPart(aliasAndField));
    }

    private static String aliasPart(String aliasAndField) {
        int dot = aliasAndField == null ? -1 : aliasAndField.indexOf('.');
        return dot >= 0 ? aliasAndField.substring(0, dot) : "";
    }

    private static String fieldPart(String aliasAndField) {
        int dot = aliasAndField == null ? -1 : aliasAndField.indexOf('.');
        return dot >= 0 ? aliasAndField.substring(dot + 1) : aliasAndField;
    }
}
