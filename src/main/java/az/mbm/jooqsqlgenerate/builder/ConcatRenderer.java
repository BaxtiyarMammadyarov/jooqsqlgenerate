package az.mbm.jooqsqlgenerate.builder;

import org.jooq.Field;
import org.jooq.impl.DSL;
import az.mbm.jooqsqlgenerate.core.EntityTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CONCAT ifadəsinin ortaq render məntiqi (v1.1.53).
 *
 * <p>Eyni ifadə həm SELECT sütunu, həm WHERE/HAVING filterləri, həm də EXISTS
 * joinField müqayisələri üçün istifadə olunur — render məntiqi BİR yerdə olmalıdır,
 * əks halda saxlanılmış dəyər (məs. insert edilmiş {@code fkDataId}) ilə müqayisə
 * ifadəsi fərqli düşər.
 */
public final class ConcatRenderer {

    private ConcatRenderer() {}

    /**
     * CONCAT ifadəsini (alias-sız) qurur.
     *
     * @param separator ayırıcı (null/boş → ayırıcısız)
     * @param items     concat elementləri
     * @param mainTable əsas cədvəl — tableMap-də tapılmayan alias-lar bura düşür
     * @param tableMap  alias → EntityTable xəritəsi (boş ola bilər)
     */
    public static Field<?> render(String separator, List<ConcatItem> items,
                                  EntityTable<?> mainTable,
                                  Map<String, EntityTable<?>> tableMap) {
        List<Field<?>> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0 && separator != null && !separator.isEmpty()) {
                parts.add(DSL.inline(separator));
            }
            ConcatItem item = items.get(i);
            if (item instanceof ConcatItem.Literal lit) {
                parts.add(DSL.inline(lit.value()));
            } else if (item instanceof ConcatItem.ColField cf) {
                EntityTable<?> t = tableMap.getOrDefault(aliasPart(cf.aliasAndField()), mainTable);
                Field<?> f = t.getField(fieldPart(cf.aliasAndField()));
                // CAST(... AS VARCHAR) vacibdir: sütun Long/Integer/Date və s. tipindədirsə,
                // COALESCE(numericField, '') Postgres-də "COALESCE types ... cannot be
                // matched" xətası verir — əvvəlcə mətnə çevrilməlidir.
                parts.add(DSL.coalesce(f.cast(String.class), DSL.inline("")));
            } else if (item instanceof ConcatItem.IfItem ii) {
                // CASE WHEN ifadəsi — COALESCE ilə null qorunması (eyni CAST səbəbi yuxarıda)
                parts.add(DSL.coalesce(ii.expr().toField(mainTable, tableMap).cast(String.class), DSL.inline("")));
            } else if (item instanceof ConcatItem.CoalesceItem ci) {
                // COALESCE ifadəsi — özü artıq null-safe-dir
                parts.add(ci.expr().toField(mainTable, tableMap));
            }
        }
        return DSL.concat(parts.toArray(new Field[0]));
    }

    private static String aliasPart(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : "";
    }

    private static String fieldPart(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(dot + 1) : s;
    }
}
