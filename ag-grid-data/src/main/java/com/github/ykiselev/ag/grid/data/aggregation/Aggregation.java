package com.github.ykiselev.ag.grid.data.aggregation;

import com.github.ykiselev.ag.grid.data.Context;
import com.github.ykiselev.ag.grid.data.types.TypeInfo;
import com.google.common.collect.Lists;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * @author Yuriy Kiselev (uze@yandex.ru).
 */
public final class Aggregation {

    private final Context context;

    private Aggregation(Context context) {
        this.context = requireNonNull(context);
    }

    private static <V> Collector<V, ?, Map> create(Context context, TypeInfo<V> typeInfo) {
        final Function<String, Function<V, ?>> getterFactory =
                col -> typeInfo.getAttribute(col).getObjectGetter();

        final Stream<String> classifierColumns;
        if (context.isPivot()) {
            classifierColumns =
                    Stream.concat(
                            context.getGroupByColumns().stream(),
                            context.getPivotColumns().stream()
                    );
        } else {
            classifierColumns =
                    context.getGroupByColumns()
                            .stream();
        }
        // We need to compose collectors from last to first, so that outer one would be the first grouping column
        final List<Function<V, ?>> classifiers = Lists.reverse(
                classifierColumns.map(getterFactory)
                        .collect(Collectors.toList())
        );

        Collector grouping = Collectors.groupingBy(
                classifiers.get(0),
                ObjectMerge.createCollector(context.indexAggregationFunctions(), typeInfo)
        );
        for (int i = 1; i < classifiers.size(); i++) {
            grouping = Collectors.groupingBy(classifiers.get(i), grouping);
        }
        return grouping;
    }

    /**
     * Method to aggregate stream of values of type {@code V} and convert aggregated objects into maps.
     *
     * @param input    the stream of items
     * @param context  the current context
     * @param typeInfo the type info for {@code V}
     * @param <V>      the type parameter
     * @return the stream of aggregated objects transformed into maps
     */
    public static <V> Stream<Map<String, Object>> groupBy(Stream<V> input, Context context, TypeInfo<V> typeInfo) {
        return new Aggregation(context)
                .parse(input.collect(create(context, typeInfo)));
    }

    private Stream<Map<String, Object>> parse(Map<?, ?> rawResult) {
        if (rawResult == null) {
            throw new NullPointerException("result");
        }
        return expandGroup(0, rawResult)
                .flatMap(this::addSecondaryColumns);
    }

    /**
     * Recursively expands aggregation result map entries.
     * For example if we have two group columns A=("a1","a2") and B=("b1","b2") and single value column V then for input map at level 0:
     * <pre>
     *     { a1={
     *            b1={ V=v1 },
     *            b2={ V=v2 }
     *          },
     *       a2={
     *            b1={ V=v3 },
     *            b2={ V=v4 }
     *          }
     *     }
     * </pre>
     * result will be stream of 4 maps:
     * <pre>
     *     { A=a1, B=b1, V=v1},
     *     { A=a1, B=b2, V=v2},
     *     { A=a2, B=b1, V=v3},
     *     { A=a2, B=b2, V=v4}
     * </pre>
     *
     * @param index the group column index
     * @param map   the result map
     * @return the stream of expanded groups
     */
    private Stream<Map<?, ?>> expandGroup(int index, Map<?, ?> map) {
        final List<String> groupByColumns = context.getGroupByColumns();
        if (index < groupByColumns.size()) {
            return map.entrySet()
                    .stream()
                    .flatMap(e -> expandGroup(index + 1, (Map<?, ?>) e.getValue()));
        }
        return Stream.of(map);
    }

    @SuppressWarnings("unchecked")
    private Stream<Map<String, Object>> addSecondaryColumns(Map<?, ?> map) {
        if (!context.isPivot()) {
            return Stream.of((Map<String, Object>) map);
        }
        return map.entrySet()
                .stream()
                .map(this::treeToRows);
    }

    /**
     * Converts (transposes) a single tree formed by values grouped by pivot column values.<p/>
     * For example if we have two pivot columns A=("a1") and B=("b1","b2") and 3 value columns X,Y,Z then input entry will be a tree of maps:
     * <pre>
     *  a1
     *  |__b1
     *  |  |__x1
     *  |  |__y1
     *  |  |__z1
     *  |
     *  |__b2
     *     |__x2
     *     |__y2
     *     |__z2
     * </pre>
     * Then after conversion we'll have a single row (map):
     * <pre>
     *   { a1_b1_x=x1, a1_b1_y=y1, a1_b1_z=z1, a1_b2_x=x2, a1_b2_y=y2, a1_b2_z=z2
     * </pre>
     * Please note that general grouping is processed before that step.
     *
     * @param row the map entry containing the tree to convert to rows, key is the first pivot column value, and value
     * @return the stream of maps containing final columns with values
     * @see Aggregation#expandGroup(int, java.util.Map)
     */
    private Map<String, Object> treeToRows(Map.Entry<?, ?> row) {
        final Map<String, Object> result = new HashMap<>();
        append(0, new Node(null, row.getKey()), row.getValue(), result);
        return result;
    }

    private void append(int index, Node node, Object value, Map<String, Object> target) {
        final int idx = index + 1;
        if (idx < context.getPivotColumns().size()) {
            ((Map<?, ?>) value).forEach((k, v) ->
                    append(idx, new Node(node, k), v, target));
        } else {
            append(node, value, target);
        }
    }

    /**
     * Note: this method fills in set of secondary column names!
     *
     * @param parent the parent node
     * @param value  the map of values
     * @param target the target map to copy values to
     * @see Context#addSecondaryColumns(java.util.Collection)
     */
    @SuppressWarnings("unchecked")
    private void append(Node parent, Object value, Map<String, Object> target) {
        final Map<String, String> column2secondary = context.getValueColumns()
                .stream()
                .collect(Collectors.toMap(
                        col -> col,
                        col -> Node.name(parent.getPath(), col)
                ));
        context.addSecondaryColumns(column2secondary.values());
        ((Map<String, Object>) value)
                .forEach((k, v) ->
                        target.put(column2secondary.getOrDefault(k, k), v));
    }

    private static final class Node {

        private final String path;

        String getPath() {
            return path;
        }

        Node(Node parent, Object key) {
            final String p = Objects.toString(key);
            this.path = parent != null ? name(parent.getPath(), p) : p;
        }

        static String name(String parentName, String name) {
            if (parentName != null) {
                return parentName + "_" + name;
            }
            return name;
        }
    }
}