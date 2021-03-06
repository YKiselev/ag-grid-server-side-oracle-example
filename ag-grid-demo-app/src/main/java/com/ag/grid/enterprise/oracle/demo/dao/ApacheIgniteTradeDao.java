package com.ag.grid.enterprise.oracle.demo.dao;

import com.ag.grid.enterprise.oracle.demo.builder.OracleSqlQueryBuilder;
import com.github.ykiselev.ag.grid.api.request.AgGridGetRowsRequest;
import com.github.ykiselev.ag.grid.api.request.ColumnVO;
import com.github.ykiselev.ag.grid.api.response.AgGridGetRowsResponse;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.ag.grid.enterprise.oracle.demo.builder.EnterpriseResponseBuilder.createResponse;
import static java.lang.String.format;
import static java.util.stream.Collectors.toMap;

@Repository("igniteTradeDao")
@Lazy
public class ApacheIgniteTradeDao implements TradeDao {

    private static final int PIVOT_VALUES_GLOBAL_LIMIT = 100;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private JdbcTemplate template;

    private OracleSqlQueryBuilder queryBuilder;

    private final Map<String, String> fixMap = ImmutableMap.<String, String>builder()
            .put("PRODUCT", "product")
            .put("PORTFOLIO", "portfolio")
            .put("BOOK", "book")
            .put("TRADEID", "tradeId")
            .put("SUBMITTERID", "submitterId")
            .put("SUBMITTERDEALID", "submitterDealId")
            .put("DEALTYPE", "dealType")
            .put("BIDTYPE", "bidType")
            .put("CURRENTVALUE", "currentValue")
            .put("PREVIOUSVALUE", "previousValue")
            .put("PL1", "pl1")
            .put("PL2", "pl2")
            .put("GAINDX", "gainDx")
            .put("SXPX", "sxPx")
            .put("X99OUT", "x99Out")
            .put("BATCH", "batch")
            .build();

    @Autowired
    public ApacheIgniteTradeDao(@Qualifier("igniteJdbcTemplate") JdbcTemplate template) {
        this.template = template;
        queryBuilder = new OracleSqlQueryBuilder();

        template.setFetchSize(200);
    }

    @PostConstruct
    private void init() throws SQLException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final DataSource dataSource = template.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("No data source!");
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into trade(product,portfolio,book,tradeId," +
                            "submitterId,submitterDealId,dealType,bidType," +
                            "currentValue,previousValue,pl1,pl2," +
                            "gainDx,sxPx,x99Out,batch)" +
                            "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )) {
                for (int i = 0; i < 1_000_000; i++) {
                    ps.clearParameters();
                    ps.setString(1, "product#" + (i % 100));
                    ps.setString(2, "portfolio#" + (i % 1500));
                    ps.setString(3, "book#" + (i % 5000));
                    ps.setInt(4, i);
                    ps.setInt(5, i % 3000);
                    ps.setInt(6, i);
                    ps.setString(7, "dealType#" + (i % 35));
                    ps.setString(8, rnd.nextBoolean() ? "Buy" : "Sell");
                    ps.setDouble(9, rnd.nextDouble(0, 100_000));
                    ps.setDouble(10, rnd.nextDouble(0, 100_000));
                    ps.setDouble(11, rnd.nextDouble());
                    ps.setDouble(12, rnd.nextDouble());
                    ps.setDouble(13, rnd.nextDouble());
                    ps.setDouble(14, rnd.nextDouble());
                    ps.setDouble(15, rnd.nextDouble());
                    ps.setInt(16, i % 15000);
                    ps.addBatch();
                    if (i % 5_0000 == 0) {
                        ps.executeBatch();
                    }
                }
            }
        }
    }

    @Override
    public AgGridGetRowsResponse getData(AgGridGetRowsRequest request) {
        logger.trace("Request: {}", request);

        final String tableName = "trade"; // could be supplied in request as a lookup key?

        // first obtain the pivot values from the DB for the requested pivot columns
        final Map<String, List<String>> pivotValues = request.isPivotMode()
                ? getPivotValues(tableName, request.getPivotCols())
                : Collections.emptyMap();

        // generate sql
        final String sql = queryBuilder.createSql(request, tableName, pivotValues);

        // query db for rows
        List<Map<String, Object>> rows = template.queryForList(sql);
        List<Map<String, Object>> fixed = rows.stream()
                .map(m ->
                        m.entrySet()
                                .stream()
                                .collect(toMap(
                                        e -> fixMap.getOrDefault(e.getKey(), e.getKey()),
                                        Map.Entry::getValue
                                ))
                ).collect(Collectors.toList());

        // create response with our results
        return createResponse(request, fixed, pivotValues);
    }

    private Map<String, List<String>> getPivotValues(String tableName, List<ColumnVO> pivotCols) {
        final int columnLimit;
        if (pivotCols.isEmpty()) {
            columnLimit = 0;
        } else if (pivotCols.size() == 1) {
            columnLimit = PIVOT_VALUES_GLOBAL_LIMIT;
        } else {
            columnLimit = (int) Math.floor(Math.pow(PIVOT_VALUES_GLOBAL_LIMIT, 1.0 / pivotCols.size()));
        }
        return pivotCols.stream()
                .map(ColumnVO::getField)
                .collect(toMap(
                        pivotCol -> pivotCol,
                        pivotCol -> getPivotValues(tableName, pivotCol, columnLimit),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private List<String> getPivotValues(String tableName, String pivotColumn, int limit) {
        List<String> strings = template.queryForList(
                format("SELECT DISTINCT %s FROM %s FETCH FIRST %d ROWS ONLY", pivotColumn, tableName, limit + 1),
                String.class
        );
        if (strings.size() > limit) {
            logger.warn("Column \"{}\" has {} distinct values, only first {} will be used!", pivotColumn, strings.size(), limit);
            strings = strings.subList(0, limit);
        }
        return strings;
    }

}