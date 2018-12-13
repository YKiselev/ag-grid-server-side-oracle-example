package com.ag.grid.enterprise.oracle.demo.data.objectsource;

import com.ag.grid.enterprise.oracle.demo.data.RequestFilters;
import com.ag.grid.enterprise.oracle.demo.data.types.TypeInfo;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * @author Yuriy Kiselev (uze@yandex.ru).
 */
@Deprecated
public final class MapUtils {


    public static Function<Map<String, Object>, String> extractValue(String key) {
        return (Map<String, Object> m) -> Objects.toString(m.get(key));
    }

    public static Predicate<Map<String, Object>> predicate(RequestFilters filters, TypeInfo info) {
        return filters.getNames()
                .stream()
                .map(col -> ColumnFilters.predicate(col, filters.getColumnFilter(col), info))
                .reduce(Predicate::and)
                .orElse(v -> true);
    }
}
