package az.mbm.jooqsqlgenerate.core;

import java.util.List;
import java.util.Map;

/**
 * {@code Map<String,Object>} siyahısı + ümumi sətir sayı.
 *
 * <p>Dinamik sorğu nəticələri üçün — entity class-ı bilinmədikdə.
 */
public class SelectFetchMapResponse {

    private final List<Map<String, Object>> list;
    private final int                       rowCount;

    public SelectFetchMapResponse(List<Map<String, Object>> list, int rowCount) {
        this.list     = list;
        this.rowCount = rowCount;
    }

    public List<Map<String, Object>> getList() {
        return list;
    }

    public int getRowCount() {
        return rowCount;
    }
}
