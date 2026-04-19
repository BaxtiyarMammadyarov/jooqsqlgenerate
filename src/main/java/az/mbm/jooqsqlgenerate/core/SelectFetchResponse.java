package az.mbm.jooqsqlgenerate.core;

import java.util.List;

/**
 * Tipli nəticə siyahısı + ümumi sətir sayı.
 *
 * @param <V> entity və ya DTO tipi
 */
public class SelectFetchResponse<V> {

    private final List<V> list;
    private final int     rowCount;

    public SelectFetchResponse(List<V> list, int rowCount) {
        this.list     = list;
        this.rowCount = rowCount;
    }

    public List<V> getList() {
        return list;
    }

    /** Pagination üçün ümumi sətir sayı */
    public int getRowCount() {
        return rowCount;
    }
}
