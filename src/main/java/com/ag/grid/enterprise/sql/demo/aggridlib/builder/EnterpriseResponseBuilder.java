package com.ag.grid.enterprise.sql.demo.aggridlib.builder;

import com.ag.grid.enterprise.sql.demo.aggridlib.request.ColumnVO;
import com.ag.grid.enterprise.sql.demo.aggridlib.request.EnterpriseGetRowsRequest;
import com.ag.grid.enterprise.sql.demo.aggridlib.response.EnterpriseGetRowsResponse;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.*;

public class EnterpriseResponseBuilder {

    public static EnterpriseGetRowsResponse createResponse(
            EnterpriseGetRowsRequest request,
            List<Map<String, Object>> rows,
            Map<String, List<String>> pivotValues) {

        int currentLastRow = request.getStartRow() + rows.size();
        int lastRow = currentLastRow <= request.getEndRow() ? currentLastRow : -1;

        List<ColumnVO> valueColumns = request.getValueCols();

        return new EnterpriseGetRowsResponse(rows, lastRow, getSecondaryColumns(pivotValues, valueColumns));
    }

    private static List<String> getSecondaryColumns(Map<String, List<String>> pivotValues, List<ColumnVO> valueColumns) {

        List<Set<Pair<String, String>>> pairList = pivotValues.entrySet().stream()
                .map(e -> e.getValue().stream()
                        .map(pivotValue -> Pair.of(e.getKey(), pivotValue))
                        .collect(toSet()))
                .collect(toList());

        return Sets.cartesianProduct(pairList)
                .stream()
                .flatMap(pairs -> {
                    String pivotColStr = pairs.stream()
                            .map(Pair::getRight)
                            .collect(joining("_"));

                    return valueColumns.stream()
                            .map(valueCol -> pivotColStr + "_" + valueCol.getField());

                }).collect(toList());
    }

}