package com.github.ykiselev.aggrid.sources.objects.aggregation

import com.github.ykiselev.aggrid.domain.request.AgGridGetRowsRequest
import com.github.ykiselev.aggrid.domain.request.AggFunc
import com.github.ykiselev.aggrid.domain.request.ColumnVO
import com.github.ykiselev.aggrid.sources.Context
import com.github.ykiselev.aggrid.sources.objects.types.*
import spock.lang.Shared
import spock.lang.Specification

import java.util.stream.Stream

/**
 * @author Yuriy Kiselev (uze@yandex.ru).
 */
class AggregationTest extends Specification {

    @Shared
    def typeInfo = new DefaultTypeInfo<Object[]>(
            [
                    new IntAttribute<>('a', { v -> (int) v[0] }),
                    new DoubleAttribute<>('b', { v -> (double) v[1] }),
                    new LongAttribute<>('c', { v -> (long) v[2] }),
                    new ObjectAttribute<>('d', String, { v -> (String) v[3] })
            ].collectEntries {
                [(it.getName()): it]
            }
    )

    def "should support empty input stream"() {
        when:
        def context = Context.create(
                new AgGridGetRowsRequest(
                        startRow: 0,
                        endRow: 100,
                        rowGroupCols: [],
                        valueCols: [],
                        pivotCols: [],
                        pivotMode: false,
                        groupKeys: [],
                        filterModel: [:],
                        sortModel: []
                )
        )
        def result = Aggregation.groupBy(Stream.of(), context, typeInfo).toArray()

        then:
        result.length == 0
    }

    def "should simply convert input items into maps when no grouping and pivoting defined"() {
        when:
        def context = Context.create(
                new AgGridGetRowsRequest(
                        startRow: 0,
                        endRow: 100,
                        rowGroupCols: [],
                        valueCols: [],
                        pivotCols: [],
                        pivotMode: false,
                        groupKeys: [],
                        filterModel: [:],
                        sortModel: []
                )
        )
        def result = Aggregation.groupBy(
                Stream.of(
                        [1, 2d, 3L, null] as Object[],
                        [4, 5d, 6L, null]
                ),
                context,
                typeInfo
        ).toArray()

        then:
        result == [
                [
                        'a': 1,
                        'b': 2d,
                        'c': 3L,
                        'd': null
                ],
                [
                        'a': 4,
                        'b': 5d,
                        'c': 6L,
                        'd': null
                ]
        ] as Object[]
    }

    def "should group input if group columns count > group key count"() {
        when:
        def context = Context.create(
                new AgGridGetRowsRequest(
                        startRow: 0,
                        endRow: 100,
                        rowGroupCols: [
                                new ColumnVO('a', 'a', 'a', null)
                        ],
                        valueCols: [
                                new ColumnVO('b', 'b', 'b', AggFunc.SUM),
                                new ColumnVO('c', 'c', 'c', AggFunc.SUM)
                        ],
                        pivotCols: [],
                        pivotMode: false,
                        groupKeys: [],
                        filterModel: [:],
                        sortModel: []
                )
        )
        def result = Aggregation.groupBy(
                Stream.of(
                        [1, 17d, -5L, null] as Object[],
                        [1, -9d, 16L, null]
                ),
                context,
                typeInfo
        ).toArray()

        then:
        result == [
                [
                        'a': 1,
                        'b': 8d,
                        'c': 11L,
                        'd': null
                ]
        ] as Object[]
    }

    def "should pivot"() {
        when:
        def context = Context.create(
                new AgGridGetRowsRequest(
                        startRow: 0,
                        endRow: 100,
                        rowGroupCols: [],
                        valueCols: [
                                new ColumnVO('b', 'b', 'b', AggFunc.SUM),
                                new ColumnVO('c', 'c', 'c', AggFunc.SUM)
                        ],
                        pivotCols: [
                                new ColumnVO('d', 'd', 'd', null)
                        ],
                        pivotMode: true,
                        groupKeys: [],
                        filterModel: [:],
                        sortModel: []
                )
        )
        def result = Aggregation.groupBy(
                Stream.of(
                        [1, 1d, 4L, 'x'] as Object[],
                        [1, 2d, 5L, 'y'],
                        [1, 3d, 6L, 'z']
                ),
                context,
                typeInfo
        ).toArray()

        then:
        result == [
                [
                        'a'  : 1,
                        'x_b': 1d,
                        'x_c': 4L,
                        'd'  : 'x',
                ],
                [
                        'a'  : 1,
                        'y_b': 2d,
                        'y_c': 5L,
                        'd'  : 'y',
                ],
                [
                        'a'  : 1,
                        'z_b': 3d,
                        'z_c': 6L,
                        'd'  : 'z',
                ]
        ] as Object[]
    }

}
