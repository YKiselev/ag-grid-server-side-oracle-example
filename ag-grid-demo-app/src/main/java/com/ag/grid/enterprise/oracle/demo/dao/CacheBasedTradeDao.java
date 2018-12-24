package com.ag.grid.enterprise.oracle.demo.dao;

import com.ag.grid.enterprise.TradeDumpLoader;
import com.ag.grid.enterprise.oracle.demo.builder.CohFilters;
import com.ag.grid.enterprise.oracle.demo.domain.Portfolio;
import com.ag.grid.enterprise.oracle.demo.domain.Trade;
import com.github.ykiselev.ag.grid.api.filter.ColumnFilter;
import com.github.ykiselev.ag.grid.api.request.AgGridGetRowsRequest;
import com.github.ykiselev.ag.grid.api.response.AgGridGetRowsResponse;
import com.github.ykiselev.ag.grid.data.AgGridRowSource;
import com.github.ykiselev.ag.grid.data.Context;
import com.github.ykiselev.ag.grid.data.ObjectSource;
import com.github.ykiselev.ag.grid.data.ObjectSourceBasedAgGridRowSource;
import com.github.ykiselev.ag.grid.data.RequestFilters;
import com.github.ykiselev.ag.grid.data.types.ReflectedTypeInfo;
import com.github.ykiselev.ag.grid.data.types.TypeInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.tangosol.net.CacheFactory;
import com.tangosol.net.NamedCache;
import com.tangosol.util.Filter;
import com.tangosol.util.extractor.KeyExtractor;
import com.tangosol.util.extractor.ReflectionExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Repository("cacheBasedTradeDao")
public class CacheBasedTradeDao implements TradeDao {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final NamedCache<Long, Trade> trades;

    private final NamedCache<String, Portfolio> portfolios;

    private final TypeInfo<Trade> typeInfo = ReflectedTypeInfo.of(Trade.class);

    private final ThreadLocal<Stats> stats = ThreadLocal.withInitial(Stats::new);

    private Stream<Trade> streamFromCache(ColumnFilter keyFilter, Stats stats) {
        final List<Long> keys = portfolios.entrySet(CohFilters.toFilter(keyFilter, new KeyExtractor()))
                .stream()
                .map(Map.Entry::getValue)
                .map(Portfolio::getTradeKeys)
                .flatMap(Collection::stream)
                .peek(stats::peekTradeId)
                .collect(Collectors.toList());

        return StreamSupport.stream(Iterables.partition(keys, 1000).spliterator(), false)
                .flatMap(k -> trades.getAll(k).entrySet().stream())
                .map(Map.Entry::getValue)
                .peek(stats::peekTrade);
    }

    private final AgGridRowSource rowSource = new ObjectSourceBasedAgGridRowSource<>(
            new ObjectSource<Trade>() {
                @Override
                public Set<String> getFilteredNames() {
                    return Collections.emptySet();
                }

                @Override
                public Stream<Trade> getAll(RequestFilters filters, Context context) {
                    final Stats stats = CacheBasedTradeDao.this.stats.get();
                    stats.before();
                    ColumnFilter keyFilter = filters.getColumnFilter("portfolio");
                    if (keyFilter != null && filters.getNames().size() < 2) {
                        return streamFromCache(keyFilter, stats);
                    }
                    logger.info("Falling back to filtering in cache...");
                    Filter filter = CohFilters.filter(filters);
                    return trades.entrySet(filter)
                            .stream()
                            .map(Map.Entry::getValue)
                            .peek(stats::peekTrade);
                }

                @Override
                public TypeInfo<Trade> getTypeInfo() {
                    return typeInfo;
                }
            }
    );

    public CacheBasedTradeDao() {
        this.trades = CacheFactory.getCache("Trades");
        this.portfolios = CacheFactory.getCache("Portfolios");
    }

    @PostConstruct
    private void init() {
        // The ordered argument specifies whether the index structure is sorted.
        // Sorted indexes are useful for range queries, including "select all entries that fall between two dates" and
        // "select all employees whose family name begins with 'S'". For "equality" queries, an unordered index may be
        // used, which may have better efficiency in terms of space and time.
        trades.addIndex(new ReflectionExtractor<>("getProduct"), false, null);
        trades.addIndex(new ReflectionExtractor<>("getPortfolio"), false, null);
        trades.addIndex(new ReflectionExtractor<>("getBook"), false, null);

        if (trades.isEmpty()) {
            logger.info("Loading data...");
            final Map<String, Map<Long, Trade>> map = TradeDumpLoader.load();

            logger.info("Putting trades...");
            for (Map<Long, Trade> tradeMap : map.values()) {
                trades.putAll(tradeMap);
            }

            logger.info("Putting portfolios...");
            portfolios.putAll(
                    map.entrySet()
                            .stream()
                            .map(e -> new Portfolio(e.getKey(), new HashSet<>(e.getValue().keySet())))
                            .collect(Collectors.toMap(
                                    Portfolio::getName,
                                    Function.identity()
                            ))
            );
            logger.info("Data loaded.");
        }
    }

    private AgGridGetRowsResponse doGetData(AgGridGetRowsRequest request) {
        return rowSource.getRows(request);        /*
        final CacheQueryBuilder builder = new CacheQueryBuilder(request);
        final Filter filter = builder.filter();
        Object result;
        if (builder.isGrouping()) {
            result = trades.aggregate(filter, builder.groupAggregator());
        } else {
            result = trades.entrySet(filter);
        }
        final List<?> rows = builder.parseResult(result);
        final int currentLastRow = request.getStartRow() + rows.size();
        final int lastRow = currentLastRow <= request.getEndRow() ? currentLastRow : -1;
        return new AgGridGetRowsResponse<>(rows, lastRow, new ArrayList<>(builder.getSecondaryColumns()));*/
    }

    @Override
    public AgGridGetRowsResponse getData(AgGridGetRowsRequest request) {
        try {
            return doGetData(request);
        } finally {
            stats.get().print();
        }
    }

    private final class Stats {

        private long idCounter;

        private long tradeCounter;

        void before() {
            idCounter = tradeCounter = 0;
        }

        void peekTradeId(Long id) {
            idCounter++;
        }

        void peekTrade(Trade trade) {
            tradeCounter++;
        }

        void print() {
            logger.info("Loaded {} id(s) and {} trade(s)", idCounter, tradeCounter);
        }
    }

    private class Data {

        private final Object lock = new Object();

        private final List<Long> keys;

        private final Iterable<List<Long>> keyBatches;

        private final List<Collection<Trade>> cached = new ArrayList<>();

        Data(Collection<Long> allKeys, int batchSize) {
            this.keys = ImmutableList.copyOf(allKeys);
            this.keyBatches = Iterables.partition(keys, batchSize);
        }

        Stream<Trade> stream() {
            final Iterator<Collection<Trade>> it = new Iterator<Collection<Trade>>() {

                final Iterator<List<Long>> keyIt = keyBatches.iterator();

                int index;

                @Override
                public boolean hasNext() {
                    return keyIt.hasNext();
                }

                @Override
                public Collection<Trade> next() {
                    if (hasNext()) {
                        final List<Long> batch = keyIt.next();
                        final Collection<Trade> result;
                        synchronized (lock) {
                            if (index < cached.size()) {
                                result = cached.get(index++);
                            } else {
                                result = trades.getAll(batch).values();
                                cached.add(result);
                            }
                        }
                        return result;
                    }
                    throw new NoSuchElementException();
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(it, 0), false
            ).flatMap(Collection::stream);
        }
    }
}