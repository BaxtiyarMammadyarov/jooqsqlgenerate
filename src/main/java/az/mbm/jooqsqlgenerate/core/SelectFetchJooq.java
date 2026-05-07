package az.mbm.jooqsqlgenerate.core;

import org.jooq.Record;
import org.jooq.RecordMapper;

import java.util.function.Function;

/**
 * {@link SelectTable} nəticəsini Java obyektlərinə çevirən yardımçı sinif.
 *
 * <p>Dörd fetch strategiyası:
 * <ul>
 *   <li>{@link #fetchCast(SelectTable, Class)} — jOOQ-un auto-mapping-i</li>
 *   <li>{@link #fetchMapper(SelectTable, RecordMapper)} — tipli RecordMapper</li>
 *   <li>{@link #fetchFunction(SelectTable, Function)} — lambda mapper</li>
 *   <li>{@link #fetchMaps(SelectTable)} — {@code Map<String,Object>} siyahısı</li>
 * </ul>
 *
 * @param <V> hədəf tipi
 */
public class SelectFetchJooq<V> {

    /**
     * jOOQ-un daxili auto-mapping mexanizmi ilə nəticəni hədəf class-a çevirir.
     *
     * @param sel   sorğu nəticəsi
     * @param clazz hədəf DTO / entity class-ı
     */
    public SelectFetchResponse<V> fetchCast(SelectTable sel, Class<V> clazz) {
        var select = sel.getSelectTable();
        var list   = select.fetchInto(clazz);
        int count  = sel.getRowCount() == 0 ? list.size() : sel.getRowCount();
        return new SelectFetchResponse<>(list, count);
    }

    /**
     * jOOQ RecordMapper ilə nəticəni çevirir.
     */
    public SelectFetchResponse<V> fetchMapper(SelectTable sel, RecordMapper<Record, V> mapper) {
        var select = sel.getSelectTable();
        var list   = select.fetch(mapper);
        int count  = sel.getRowCount() == 0 ? list.size() : sel.getRowCount();
        return new SelectFetchResponse<>(list, count);
    }

    /**
     * Lambda funksiyası ilə nəticəni çevirir.
     */
    public SelectFetchResponse<V> fetchFunction(SelectTable sel, Function<Record, V> fn) {
        var select = sel.getSelectTable();
        var list   = select.stream().map(fn).toList();
        int count  = sel.getRowCount() == 0 ? list.size() : sel.getRowCount();
        return new SelectFetchResponse<>(list, count);
    }

    /**
     * Dinamik sorğular üçün — nəticəni {@code Map<String,Object>} siyahısı kimi qaytarır.
     * Field adları DB-nin native formatındadır (snake_case).
     */
    public SelectFetchMapResponse fetchMaps(SelectTable sel) {
        var list  = sel.getSelectTable().fetchMaps();
        int count = sel.getRowCount() == 0 ? list.size() : sel.getRowCount();
        return new SelectFetchMapResponse(list, count);
    }
}
