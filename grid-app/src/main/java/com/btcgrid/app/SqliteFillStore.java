package com.btcgrid.app;

import com.btcgrid.common.model.ClientOrderIds;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.strategy.model.ProfitSizingState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.MathContext;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SqliteFillStore implements FillStore {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String jdbcUrl;

    public SqliteFillStore(Path dbPath) {
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create fill database directory: " + dbPath, e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        initialize();
    }

    @Override
    public synchronized void save(FillRecord record) {
        String sql = """
                insert or ignore into fills (
                  execution_id, fill_time_millis, symbol, side, qty, price,
                  value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                  net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                  total_pnl_at_fill, client_order_id, config_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId(record));
            statement.setLong(2, toMillis(record.time()));
            statement.setString(3, record.symbol());
            statement.setString(4, record.side());
            statement.setString(5, text(record.qty()));
            statement.setString(6, text(record.price()));
            statement.setString(7, text(record.value()));
            statement.setString(8, text(record.fee()));
            statement.setString(9, record.feeCurrency() == null || record.feeCurrency().isBlank() ? "USDT" : record.feeCurrency());
            statement.setString(10, text(record.cumulativeQty()));
            statement.setString(11, text(record.cumulativeValue()));
            statement.setString(12, text(record.cumulativeFee()));
            statement.setString(13, text(record.netQty()));
            statement.setString(14, text(record.avgEntryPrice()));
            statement.setString(15, text(record.realizedPnl()));
            statement.setString(16, text(record.floatingPnlAtFill()));
            statement.setString(17, text(record.totalPnlAtFill()));
            statement.setString(18, record.clientOrderId());
            statement.setString(19, record.configJson());
            statement.executeUpdate();
            fillMissingConfigJson(connection, executionId(record), record.configJson());
            backfillDerivedColumns(connection, record.symbol());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save fill record", e);
        }
    }

    private void fillMissingConfigJson(Connection connection, String executionId, String configJson) throws SQLException {
        if (executionId == null || executionId.isBlank() || configJson == null || configJson.isBlank()) {
            return;
        }
        String sql = """
                update fills
                   set config_json = ?
                 where execution_id = ?
                   and (config_json is null or config_json = '')
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, configJson);
            statement.setString(2, executionId);
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized List<FillRecord> recent(int limit) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 order by fill_time_millis desc, rowid desc
                 limit ?
                """;
        List<FillRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(record(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read fill records", e);
        }
        return records;
    }

    @Override
    public synchronized List<FillRecord> recent(String symbol, int limit) {
        return recent(symbol, limit, 0);
    }

    @Override
    public synchronized List<FillRecord> recent(String symbol, int limit, int offset) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                 from fills
                 where symbol = ?
                 order by fill_time_millis desc, rowid desc
                 limit ?
                offset ?
                """;
        List<FillRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol.trim().toUpperCase());
            statement.setInt(2, Math.max(1, limit));
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(record(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read fill records", e);
        }
        return records;
    }

    @Override
    public synchronized List<FillRecord> recentTrading(String symbol, int limit, int offset) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where symbol = ?
                   and side in ('BUY', 'SELL', 'FUNDING_FEE')
                 order by fill_time_millis desc, rowid desc
                 limit ?
                offset ?
                """;
        List<FillRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol.trim().toUpperCase());
            statement.setInt(2, Math.max(1, limit));
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(record(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read trading fill records", e);
        }
        return records;
    }

    @Override
    public synchronized List<FillRecord> recentCycles(int limit) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side = 'CYCLE_END'
                 order by fill_time_millis desc, rowid desc
                 limit ?
                """;
        return query(sql, Math.max(1, limit));
    }

    @Override
    public synchronized List<FillRecord> recentCycles(String symbol, int limit) {
        return recentCycles(symbol, limit, 0);
    }

    @Override
    public synchronized List<FillRecord> recentCycles(String symbol, int limit, int offset) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side = 'CYCLE_END'
                   and symbol = ?
                 order by fill_time_millis desc, rowid desc
                 limit ?
                offset ?
                """;
        List<FillRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol.trim().toUpperCase());
            statement.setInt(2, Math.max(1, limit));
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(record(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cycle records", e);
        }
        return records;
    }

    @Override
    public synchronized List<FillRecord> cycleFills(String cycleExecutionId) {
        CycleFillPage page = cycleFillsPage(cycleExecutionId, Integer.MAX_VALUE, 0);
        return page.items();
    }

    @Override
    public synchronized CycleFillPage cycleFillsPage(String cycleExecutionId, int limit, int offset) {
        int normalizedLimit = Math.max(1, limit);
        int normalizedOffset = Math.max(0, offset);
        if (cycleExecutionId == null || cycleExecutionId.isBlank()) {
            return CycleFillPage.empty(normalizedLimit, normalizedOffset);
        }
        FillRecord cycleEnd = cycleEnd(cycleExecutionId.trim());
        if (cycleEnd == null) {
            return CycleFillPage.empty(normalizedLimit, normalizedOffset);
        }
        String pageSql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                   and fill_time_millis < ?
                 order by fill_time_millis asc, rowid asc
                 limit ? offset ?
                """;
        try (Connection connection = connect()) {
            CycleWindow window = cycleWindow(connection, cycleEnd);
            long total = cycleFillCount(connection, cycleEnd.symbol(), cycleEnd.time(), window);
            if (normalizedOffset >= total) {
                return new CycleFillPage(List.of(), normalizedLimit, normalizedOffset, false, total, cycleTurnoverBefore(
                        connection, cycleEnd.symbol(), cycleEnd.time(), window, normalizedOffset
                ));
            }
            List<FillRecord> fills = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                bindCycleWindow(statement, cycleEnd.symbol(), cycleEnd.time(), window);
                statement.setInt(6, normalizedLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : normalizedLimit + 1);
                statement.setInt(7, normalizedOffset);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        fills.add(record(rs));
                    }
                }
            }
            boolean hasMore = fills.size() > normalizedLimit;
            List<FillRecord> items = hasMore ? List.copyOf(fills.subList(0, normalizedLimit)) : List.copyOf(fills);
            return new CycleFillPage(
                    items,
                    normalizedLimit,
                    normalizedOffset,
                    hasMore,
                    total,
                    cycleTurnoverBefore(connection, cycleEnd.symbol(), cycleEnd.time(), window, normalizedOffset)
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cycle fills", e);
        }
    }

    private CycleWindow cycleWindow(Connection connection, FillRecord cycleEnd) throws SQLException {
        String sql = """
                select rowid, fill_time_millis, client_order_id, config_json
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and fill_time_millis < ?
                 order by fill_time_millis desc, rowid desc
                """;
        String initialClientOrderId = null;
        long startTime = 0;
        long startRowId = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cycleEnd.symbol());
            statement.setLong(2, toMillis(cycleEnd.time()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clientOrderId = rs.getString("client_order_id");
                    if (initialClientOrderId != null && !initialClientOrderId.equals(clientOrderId)) {
                        break;
                    }
                    if (initialClientOrderId == null && isCycleInitial(
                            clientOrderId,
                            rs.getString("config_json")
                    )) {
                        initialClientOrderId = clientOrderId;
                    }
                    if (initialClientOrderId != null) {
                        startTime = rs.getLong("fill_time_millis");
                        startRowId = rs.getLong("rowid");
                    }
                }
            }
        }
        return new CycleWindow(startTime, startRowId);
    }

    private long cycleFillCount(Connection connection, String symbol, Instant endTime, CycleWindow window) throws SQLException {
        String sql = """
                select count(*)
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                   and fill_time_millis < ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCycleWindow(statement, symbol, endTime, window);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private BigDecimal cycleTurnoverBefore(
            Connection connection,
            String symbol,
            Instant endTime,
            CycleWindow window,
            int offset
    ) throws SQLException {
        if (offset <= 0) {
            return BigDecimal.ZERO;
        }
        String sql = """
                select side, value
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                   and fill_time_millis < ?
                 order by fill_time_millis asc, rowid asc
                 limit ?
                """;
        BigDecimal turnover = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCycleWindow(statement, symbol, endTime, window);
            statement.setInt(6, offset);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if ("BUY".equals(rs.getString("side")) || "SELL".equals(rs.getString("side"))) {
                        turnover = turnover.add(decimal(rs.getString("value")).abs());
                    }
                }
            }
        }
        return turnover;
    }

    private void bindCycleWindow(
            PreparedStatement statement,
            String symbol,
            Instant endTime,
            CycleWindow window
    ) throws SQLException {
        statement.setString(1, symbol);
        statement.setLong(2, window.startTimeMillis());
        statement.setLong(3, window.startTimeMillis());
        statement.setLong(4, window.startRowId());
        statement.setLong(5, toMillis(endTime));
    }

    private record CycleWindow(long startTimeMillis, long startRowId) {
    }

    @Override
    public synchronized List<FillRecord> currentCycleFills(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and fill_time_millis > coalesce((
                         select max(fill_time_millis)
                           from fills
                          where symbol = ?
                            and side = 'CYCLE_END'
                       ), 0)
                 order by fill_time_millis desc, rowid desc
                """;
        List<FillRecord> fills = new ArrayList<>();
        String initialClientOrderId = null;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String normalizedSymbol = symbol.trim().toUpperCase();
            statement.setString(1, normalizedSymbol);
            statement.setString(2, normalizedSymbol);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    FillRecord record = record(rs);
                    if (initialClientOrderId != null && !initialClientOrderId.equals(record.clientOrderId())) {
                        break;
                    }
                    fills.add(record);
                    if (initialClientOrderId == null && isCycleInitial(record)) {
                        initialClientOrderId = record.clientOrderId();
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read current cycle fills", e);
        }
        if (fills.isEmpty() || !isCycleInitial(fills.get(fills.size() - 1))) {
            return List.of();
        }
        Collections.reverse(fills);
        return fills;
    }

    @Override
    public synchronized FundingFeePage currentCycleFundingFeesPage(String symbol, int limit, int offset) {
        int normalizedLimit = Math.max(1, limit);
        int normalizedOffset = Math.max(0, offset);
        if (symbol == null || symbol.isBlank()) {
            return FundingFeePage.empty(normalizedLimit, normalizedOffset);
        }
        String normalizedSymbol = symbol.trim().toUpperCase();
        try (Connection connection = connect()) {
            CycleWindow window = currentCycleWindow(connection, normalizedSymbol);
            if (window.startTimeMillis() <= 0 || window.startRowId() <= 0) {
                return FundingFeePage.empty(normalizedLimit, normalizedOffset);
            }
            String countSql = """
                    select count(*)
                      from fills
                     where side = 'FUNDING_FEE'
                       and symbol = ?
                       and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                    """;
            long total;
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                bindCurrentCycleWindow(statement, normalizedSymbol, window);
                try (ResultSet rs = statement.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            if (normalizedOffset >= total) {
                return new FundingFeePage(
                        List.of(),
                        normalizedLimit,
                        normalizedOffset,
                        false,
                        total
                );
            }
            String pageSql = """
                    select rowid as fill_rowid,
                           execution_id, fill_time_millis, symbol, side, qty, price,
                           value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                           net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                           total_pnl_at_fill, client_order_id, config_json
                      from fills
                     where side = 'FUNDING_FEE'
                       and symbol = ?
                       and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                     order by fill_time_millis desc, rowid desc
                     limit ? offset ?
                    """;
            List<PageRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                int next = bindCurrentCycleWindow(statement, normalizedSymbol, window);
                statement.setInt(next++, normalizedLimit);
                statement.setInt(next, normalizedOffset);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PageRow(rs.getLong("fill_rowid"), record(rs)));
                    }
                }
            }
            BigDecimal cumulative = fundingFeeTotalBefore(
                    connection,
                    normalizedSymbol,
                    window,
                    rows.get(rows.size() - 1)
            );
            Map<Long, BigDecimal> cumulativeByRowId = new HashMap<>();
            for (int index = rows.size() - 1; index >= 0; index--) {
                PageRow row = rows.get(index);
                cumulative = cumulative.add(
                        row.fill().fee() == null ? BigDecimal.ZERO : row.fill().fee(),
                        MATH_CONTEXT
                );
                cumulativeByRowId.put(row.rowId(), cumulative);
            }
            List<FundingFeeItem> items = rows.stream()
                    .map(row -> new FundingFeeItem(
                            row.fill(),
                            cumulativeByRowId.getOrDefault(row.rowId(), BigDecimal.ZERO)
                    ))
                    .toList();
            return new FundingFeePage(
                    items,
                    normalizedLimit,
                    normalizedOffset,
                    normalizedOffset + items.size() < total,
                    total
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to page current cycle funding fees", e);
        }
    }

    private BigDecimal fundingFeeTotalBefore(
            Connection connection,
            String symbol,
            CycleWindow window,
            PageRow oldestPageRow
    ) throws SQLException {
        String sql = """
                select fee
                  from fills
                 where side = 'FUNDING_FEE'
                   and symbol = ?
                   and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                   and (fill_time_millis < ? or (fill_time_millis = ? and rowid < ?))
                 order by fill_time_millis asc, rowid asc
                """;
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = bindCurrentCycleWindow(statement, symbol, window);
            long oldestTime = oldestPageRow.fill().time().toEpochMilli();
            statement.setLong(next++, oldestTime);
            statement.setLong(next++, oldestTime);
            statement.setLong(next, oldestPageRow.rowId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    total = total.add(decimal(rs.getString("fee")), MATH_CONTEXT);
                }
            }
        }
        return total;
    }

    @Override
    public synchronized CurrentCycleFillPage currentCycleFillsPage(
            String symbol,
            int limit,
            int offset,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        return currentCycleFillsPage(symbol, limit, offset, minPrice, maxPrice, false);
    }

    @Override
    public synchronized CurrentCycleFillPage currentCycleFillsPage(
            String symbol,
            int limit,
            int offset,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean maxPriceExclusive
    ) {
        int normalizedLimit = Math.max(1, limit);
        int normalizedOffset = Math.max(0, offset);
        if (symbol == null || symbol.isBlank()) {
            return CurrentCycleFillPage.empty(normalizedLimit, normalizedOffset);
        }
        String normalizedSymbol = symbol.trim().toUpperCase();
        try (Connection connection = connect()) {
            CycleWindow window = currentCycleWindow(connection, normalizedSymbol);
            if (window.startTimeMillis() <= 0 || window.startRowId() <= 0) {
                return CurrentCycleFillPage.empty(normalizedLimit, normalizedOffset);
            }

            String filterSql = priceFilterSql(minPrice, maxPrice, maxPriceExclusive);
            String countSql = """
                    select count(*),
                           sum(case when side in ('BUY', 'SELL') then 1 else 0 end),
                           sum(case when side = 'FUNDING_FEE' then 1 else 0 end)
                      from fills
                     where side in ('BUY', 'SELL', 'FUNDING_FEE')
                       and symbol = ?
                       and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                    """ + filterSql;
            long total;
            long tradingCount;
            long fundingCount;
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                int next = bindCurrentCycleWindow(statement, normalizedSymbol, window);
                bindPriceFilter(statement, next, minPrice, maxPrice);
                try (ResultSet rs = statement.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                    tradingCount = rs.getLong(2);
                    fundingCount = rs.getLong(3);
                }
            }
            if (normalizedOffset >= total) {
                return new CurrentCycleFillPage(
                        List.of(),
                        normalizedLimit,
                        normalizedOffset,
                        false,
                        total,
                        tradingCount,
                        fundingCount
                );
            }

            String pageSql = """
                    select rowid as fill_rowid,
                           execution_id, fill_time_millis, symbol, side, qty, price,
                           value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                           net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                           total_pnl_at_fill, client_order_id, config_json
                      from fills
                     where side in ('BUY', 'SELL', 'FUNDING_FEE')
                       and symbol = ?
                       and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                    """ + filterSql + """
                     order by fill_time_millis desc, rowid desc
                     limit ? offset ?
                    """;
            List<PageRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                int next = bindCurrentCycleWindow(statement, normalizedSymbol, window);
                next = bindPriceFilter(statement, next, minPrice, maxPrice);
                statement.setInt(next++, normalizedLimit);
                statement.setInt(next, normalizedOffset);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PageRow(rs.getLong("fill_rowid"), record(rs)));
                    }
                }
            }

            Map<Long, BigDecimal> turnoverByRowId = new HashMap<>();
            Map<Long, BigDecimal> previousPriceByRowId = new HashMap<>();
            Set<Long> requestedRowIds = rows.stream().map(PageRow::rowId).collect(java.util.stream.Collectors.toSet());
            String metricsSql = """
                    select rowid as fill_rowid, side, price, value
                      from fills
                     where side in ('BUY', 'SELL', 'FUNDING_FEE')
                       and symbol = ?
                       and (fill_time_millis > ? or (fill_time_millis = ? and rowid >= ?))
                     order by fill_time_millis asc, rowid asc
                    """;
            BigDecimal turnover = BigDecimal.ZERO;
            BigDecimal previousTradingPrice = null;
            try (PreparedStatement statement = connection.prepareStatement(metricsSql)) {
                bindCurrentCycleWindow(statement, normalizedSymbol, window);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        long rowId = rs.getLong("fill_rowid");
                        String side = rs.getString("side");
                        if ("BUY".equals(side) || "SELL".equals(side)) {
                            if (requestedRowIds.contains(rowId) && previousTradingPrice != null) {
                                previousPriceByRowId.put(rowId, previousTradingPrice);
                            }
                            turnover = turnover.add(decimal(rs.getString("value")).abs());
                            previousTradingPrice = decimal(rs.getString("price"));
                        }
                        if (requestedRowIds.contains(rowId)) {
                            turnoverByRowId.put(rowId, turnover);
                        }
                    }
                }
            }

            List<CurrentCycleFillItem> items = rows.stream()
                    .map(row -> new CurrentCycleFillItem(
                            row.fill(),
                            turnoverByRowId.getOrDefault(row.rowId(), BigDecimal.ZERO),
                            previousPriceByRowId.get(row.rowId())
                    ))
                    .toList();
            return new CurrentCycleFillPage(
                    items,
                    normalizedLimit,
                    normalizedOffset,
                    normalizedOffset + items.size() < total,
                    total,
                    tradingCount,
                    fundingCount
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to page current cycle fills", e);
        }
    }

    private CycleWindow currentCycleWindow(Connection connection, String symbol) throws SQLException {
        String sql = """
                select rowid, fill_time_millis, client_order_id, config_json
                  from fills
                 where side in ('BUY', 'SELL', 'FUNDING_FEE')
                   and symbol = ?
                   and fill_time_millis > coalesce((
                         select max(fill_time_millis)
                           from fills
                          where symbol = ?
                            and side = 'CYCLE_END'
                       ), 0)
                 order by fill_time_millis desc, rowid desc
                """;
        String initialClientOrderId = null;
        long startTime = 0;
        long startRowId = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setString(2, symbol);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clientOrderId = rs.getString("client_order_id");
                    if (initialClientOrderId != null && !initialClientOrderId.equals(clientOrderId)) {
                        break;
                    }
                    if (initialClientOrderId == null && isCycleInitial(
                            clientOrderId,
                            rs.getString("config_json")
                    )) {
                        initialClientOrderId = clientOrderId;
                    }
                    if (initialClientOrderId != null) {
                        startTime = rs.getLong("fill_time_millis");
                        startRowId = rs.getLong("rowid");
                    }
                }
            }
        }
        return new CycleWindow(startTime, startRowId);
    }

    private int bindCurrentCycleWindow(
            PreparedStatement statement,
            String symbol,
            CycleWindow window
    ) throws SQLException {
        statement.setString(1, symbol);
        statement.setLong(2, window.startTimeMillis());
        statement.setLong(3, window.startTimeMillis());
        statement.setLong(4, window.startRowId());
        return 5;
    }

    private String priceFilterSql(
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean maxPriceExclusive
    ) {
        StringBuilder sql = new StringBuilder();
        if (minPrice != null) {
            sql.append(" and cast(price as real) >= ?");
        }
        if (maxPrice != null) {
            sql.append(maxPriceExclusive
                    ? " and cast(price as real) < ?"
                    : " and cast(price as real) <= ?");
        }
        return sql.toString();
    }

    private int bindPriceFilter(
            PreparedStatement statement,
            int next,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) throws SQLException {
        if (minPrice != null) {
            statement.setBigDecimal(next++, minPrice);
        }
        if (maxPrice != null) {
            statement.setBigDecimal(next++, maxPrice);
        }
        return next;
    }

    private record PageRow(long rowId, FillRecord fill) {
    }

    @Override
    public synchronized CurrentCycleSummary currentCycle(String symbol, Instant now, BigDecimal markPrice) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        List<FillRecord> cycleFills = currentCycleFills(symbol);
        if (cycleFills.isEmpty()) {
            return null;
        }
        return summarizeCycle(cycleFills, now == null ? Instant.now() : now, markPrice);
    }

    @Override
    public synchronized ProfitSizingState profitSizingState(String symbol) {
        List<FillRecord> fills = currentCycleFills(symbol);
        BigDecimal cumulativeExtraReduce = BigDecimal.ZERO;
        BigDecimal cumulativeExtraAdd = BigDecimal.ZERO;
        BigDecimal cumulativePermanentReduce = BigDecimal.ZERO;
        BigDecimal reduceCarry = BigDecimal.ZERO;
        BigDecimal addCarry = BigDecimal.ZERO;
        for (FillRecord fill : fills) {
            cumulativeExtraReduce = cumulativeExtraReduce.add(
                    valueOrZero(metadataDecimal(fill, "profitReduceBoostExtraQty")),
                    MATH_CONTEXT
            );
            cumulativeExtraAdd = cumulativeExtraAdd.add(
                    valueOrZero(metadataDecimal(fill, "lossAddBoostExtraQty")),
                    MATH_CONTEXT
            );
            cumulativePermanentReduce = cumulativePermanentReduce.add(
                    valueOrZero(metadataDecimal(fill, "permanentProfitReduceExtraQty")),
                    MATH_CONTEXT
            );
            BigDecimal savedReduceCarry = metadataDecimal(fill, "profitReduceCarryQty");
            if (savedReduceCarry != null) {
                reduceCarry = savedReduceCarry;
            }
            BigDecimal savedAddCarry = metadataDecimal(fill, "lossAddCarryQty");
            if (savedAddCarry != null) {
                addCarry = savedAddCarry;
            }
        }
        return new ProfitSizingState(
                reduceCarry,
                cumulativeExtraReduce,
                addCarry,
                cumulativeExtraAdd,
                cumulativePermanentReduce,
                BigDecimal.ZERO
        );
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    public synchronized CurrentCycleSummary latestOpenCycle(Instant now, BigDecimal markPrice) {
        String sql = """
                select symbol
                  from fills
                 where side in ('BUY', 'SELL')
                 group by symbol
                 order by max(fill_time_millis) desc
                """;
        List<String> symbols = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                symbols.add(rs.getString("symbol"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read current cycle symbols", e);
        }
        for (String symbol : symbols) {
            CurrentCycleSummary summary = currentCycle(symbol, now, markPrice);
            if (summary != null && summary.cumulativeQty() != null && summary.cumulativeQty().signum() != 0) {
                return summary;
            }
        }
        return null;
    }

    private FillRecord cycleEnd(String cycleExecutionId) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side = 'CYCLE_END'
                   and (execution_id = ? or client_order_id = ?)
                 order by fill_time_millis desc, rowid desc
                 limit 1
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cycleExecutionId);
            statement.setString(2, cycleExecutionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? record(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cycle end", e);
        }
    }

    @Override
    public synchronized FillRecord latestTradingFill(String symbol) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id, config_json
                  from fills
                 where side in ('BUY', 'SELL')
                   and symbol = ?
                 order by fill_time_millis desc, rowid desc
                 limit 1
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? record(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest fill record", e);
        }
    }

    @Override
    public synchronized Set<String> filledClientOrderIds(String symbol, Collection<String> clientOrderIds) {
        if (symbol == null || symbol.isBlank() || clientOrderIds == null || clientOrderIds.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = new HashSet<>();
        for (String clientOrderId : clientOrderIds) {
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                candidates.add(clientOrderId);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        String sql = """
                select distinct client_order_id
                  from fills
                 where symbol = ?
                   and side in ('BUY', 'SELL')
                """;
        Set<String> filled = new HashSet<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol.trim().toUpperCase());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String clientOrderId = rs.getString("client_order_id");
                    if (candidates.contains(clientOrderId)) {
                        filled.add(clientOrderId);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read filled client order ids", e);
        }
        return filled;
    }

    private CurrentCycleSummary summarizeCycle(List<FillRecord> fills, Instant now, BigDecimal markPrice) {
        BigDecimal cumulativeQty = BigDecimal.ZERO;
        BigDecimal cumulativeValue = BigDecimal.ZERO;
        BigDecimal cumulativeTradingFee = BigDecimal.ZERO;
        BigDecimal cumulativeFundingFee = BigDecimal.ZERO;
        BigDecimal profitableReduceQty = BigDecimal.ZERO;
        BigDecimal profitReduceBoostExtraQty = BigDecimal.ZERO;
        BigDecimal profitReduceBoostValue = BigDecimal.ZERO;
        BigDecimal permanentProfitReduceQty = BigDecimal.ZERO;
        BigDecimal lossAddBoostExtraQty = BigDecimal.ZERO;
        BigDecimal lossAddBoostValue = BigDecimal.ZERO;
        BigDecimal profitReduceCarryQty = BigDecimal.ZERO;
        BigDecimal lossAddCarryQty = BigDecimal.ZERO;
        BigDecimal buyQty = BigDecimal.ZERO;
        BigDecimal sellQty = BigDecimal.ZERO;
        int buyCount = 0;
        int sellCount = 0;
        FillRecord latest = null;
        for (FillRecord fill : fills) {
            latest = fill;
            BigDecimal qty = fill.qty() == null ? BigDecimal.ZERO : fill.qty();
            BigDecimal value = fill.value() == null || fill.value().signum() == 0
                    ? qty.multiply(fill.price(), MATH_CONTEXT)
                    : fill.value();
            BigDecimal fee = fill.fee() == null ? BigDecimal.ZERO : fill.fee();
            BigDecimal beforeQty = cumulativeQty;
            BigDecimal netQtyDelta = netQtyDelta(fill.symbol(), fill.side(), qty, fee, fill.feeCurrency());
            if ("BUY".equals(fill.side())) {
                buyCount++;
                buyQty = buyQty.add(qty, MATH_CONTEXT);
                cumulativeQty = cumulativeQty.add(netQtyDelta, MATH_CONTEXT);
                cumulativeValue = cumulativeValue.add(value, MATH_CONTEXT);
                cumulativeTradingFee = cumulativeTradingFee.add(fee, MATH_CONTEXT);
                if (beforeQty.signum() < 0 && isPositive(fill.totalPnlAtFill())) {
                    profitableReduceQty = profitableReduceQty.add(qty, MATH_CONTEXT);
                }
            } else if ("SELL".equals(fill.side())) {
                sellCount++;
                sellQty = sellQty.add(qty, MATH_CONTEXT);
                cumulativeQty = cumulativeQty.add(netQtyDelta, MATH_CONTEXT);
                cumulativeValue = cumulativeValue.subtract(value, MATH_CONTEXT);
                cumulativeTradingFee = cumulativeTradingFee.add(fee, MATH_CONTEXT);
                if (beforeQty.signum() > 0 && isPositive(fill.totalPnlAtFill())) {
                    profitableReduceQty = profitableReduceQty.add(qty, MATH_CONTEXT);
                }
            } else if ("FUNDING_FEE".equals(fill.side())) {
                cumulativeFundingFee = cumulativeFundingFee.add(fee, MATH_CONTEXT);
            }
            BigDecimal boostExtraQty = metadataDecimal(fill, "profitReduceBoostExtraQty");
            if (boostExtraQty != null && boostExtraQty.signum() > 0) {
                profitReduceBoostExtraQty = profitReduceBoostExtraQty.add(boostExtraQty, MATH_CONTEXT);
                profitReduceBoostValue = profitReduceBoostValue.add(
                        boostExtraQty.multiply(fill.price(), MATH_CONTEXT),
                        MATH_CONTEXT
                );
            }
            BigDecimal addExtraQty = metadataDecimal(fill, "lossAddBoostExtraQty");
            if (addExtraQty != null && addExtraQty.signum() > 0) {
                lossAddBoostExtraQty = lossAddBoostExtraQty.add(addExtraQty, MATH_CONTEXT);
                lossAddBoostValue = lossAddBoostValue.add(
                        addExtraQty.multiply(fill.price(), MATH_CONTEXT),
                        MATH_CONTEXT
                );
            }
            BigDecimal permanentReduceQty = metadataDecimal(fill, "permanentProfitReduceExtraQty");
            if (permanentReduceQty != null && permanentReduceQty.signum() > 0) {
                permanentProfitReduceQty = permanentProfitReduceQty.add(
                        permanentReduceQty,
                        MATH_CONTEXT
                );
            }
            BigDecimal savedReduceCarry = metadataDecimal(fill, "profitReduceCarryQty");
            if (savedReduceCarry != null) {
                profitReduceCarryQty = savedReduceCarry;
            }
            BigDecimal savedAddCarry = metadataDecimal(fill, "lossAddCarryQty");
            if (savedAddCarry != null) {
                lossAddCarryQty = savedAddCarry;
            }
        }
        if (latest == null) {
            return null;
        }
        BigDecimal cumulativeFee = cumulativeTradingFee.subtract(cumulativeFundingFee, MATH_CONTEXT);
        BigDecimal latestFillPrice = latest.price();
        BigDecimal fillPricePnl = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, latestFillPrice);
        BigDecimal marketPrice = markPrice == null || markPrice.signum() == 0 ? latestFillPrice : markPrice;
        BigDecimal markPricePnl = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, marketPrice);
        String entryDirection = "BUY".equals(fills.get(0).side()) ? "LONG" : "SHORT";
        BigDecimal startRatio = metadataDecimal(fills.get(0), "startRatio");
        BigDecimal startPrice = fills.get(0).price();
        Instant startedAt = fills.get(0).time();
        long durationSeconds = Math.max(0L, now.getEpochSecond() - startedAt.getEpochSecond());
        BigDecimal profitReduceBoostAveragePrice = weightedAveragePrice(
                profitReduceBoostValue,
                profitReduceBoostExtraQty
        );
        BigDecimal lossAddBoostAveragePrice = weightedAveragePrice(lossAddBoostValue, lossAddBoostExtraQty);
        BigDecimal profitReleaseReserveQty = profitReduceBoostExtraQty
                .subtract(permanentProfitReduceQty, MATH_CONTEXT)
                .subtract(lossAddBoostExtraQty, MATH_CONTEXT)
                .max(BigDecimal.ZERO);
        return new CurrentCycleSummary(
                latest.symbol(),
                startedAt,
                latest.time(),
                now,
                durationSeconds,
                buyCount,
                sellCount,
                buyCount + sellCount,
                buyQty,
                sellQty,
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                cumulativeTradingFee,
                cumulativeFundingFee,
                profitableReduceQty,
                profitReduceBoostExtraQty,
                profitReduceBoostAveragePrice,
                permanentProfitReduceQty,
                lossAddBoostExtraQty,
                lossAddBoostAveragePrice,
                profitReleaseReserveQty,
                profitReduceCarryQty,
                lossAddCarryQty,
                startRatio,
                startPrice,
                latestFillPrice,
                marketPrice,
                entryDirection,
                fillPricePnl,
                markPricePnl
        );
    }

    private BigDecimal weightedAveragePrice(BigDecimal value, BigDecimal qty) {
        return qty == null || qty.signum() <= 0
                ? BigDecimal.ZERO
                : value.divide(qty, MATH_CONTEXT);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal metadataDecimal(FillRecord fill, String key) {
        if (fill == null || fill.configJson() == null || fill.configJson().isBlank()) {
            return null;
        }
        try {
            Object value = OBJECT_MAPPER.readValue(fill.configJson(), MAP_TYPE).get(key);
            if (value == null) {
                return null;
            }
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isCycleInitial(FillRecord fill) {
        return fill != null && isCycleInitial(fill.clientOrderId(), fill.configJson());
    }

    private boolean isCycleInitial(String clientOrderId, String configJson) {
        if (!ClientOrderIds.isInitialPositionOrder(clientOrderId)) {
            return false;
        }
        if (configJson == null || configJson.isBlank()) {
            return true;
        }
        try {
            Object merged = OBJECT_MAPPER.readValue(configJson, MAP_TYPE).get("mergedIntoRecoveredCycle");
            return !Boolean.parseBoolean(String.valueOf(merged));
        } catch (Exception ignored) {
            return true;
        }
    }

    private BigDecimal cumulativeProfit(
            BigDecimal cumulativeQty,
            BigDecimal cumulativeValue,
            BigDecimal cumulativeFee,
            BigDecimal price
    ) {
        return cumulativeQty.multiply(price, MATH_CONTEXT)
                .subtract(cumulativeValue, MATH_CONTEXT)
                .subtract(cumulativeFee, MATH_CONTEXT);
    }

    private List<FillRecord> query(String sql, int limit) {
        List<FillRecord> records = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(record(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read fill records", e);
        }
        return records;
    }

    private void initialize() {
        String sql = """
                create table if not exists fills (
                  execution_id text primary key,
                  fill_time_millis integer not null,
                  symbol text not null,
                  side text not null,
                  qty text not null,
                  price text not null,
                  net_qty text not null,
                  avg_entry_price text not null,
                  realized_pnl text not null,
                  floating_pnl_at_fill text not null,
                  total_pnl_at_fill text not null,
                  client_order_id text not null
                )
                """;
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            ensureColumn(connection, "value", "text not null default '0'");
            ensureColumn(connection, "fee", "text not null default '0'");
            ensureColumn(connection, "fee_currency", "text not null default 'USDT'");
            ensureColumn(connection, "cumulative_qty", "text not null default '0'");
            ensureColumn(connection, "cumulative_value", "text not null default '0'");
            ensureColumn(connection, "cumulative_fee", "text not null default '0'");
            ensureColumn(connection, "config_json", "text");
            statement.execute("create index if not exists idx_fills_time on fills(fill_time_millis desc)");
            statement.execute("create index if not exists idx_fills_symbol_time on fills(symbol, fill_time_millis desc)");
            statement.execute("""
                    create unique index if not exists idx_funding_fee_once
                      on fills(symbol, side, fill_time_millis)
                     where side = 'FUNDING_FEE'
                    """);
            backfillDerivedColumns(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize fill database", e);
        }
    }

    private void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("pragma table_info(fills)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        if (!columns.contains(column)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table fills add column " + column + " " + definition);
            }
        }
    }

    private void backfillDerivedColumns(Connection connection) throws SQLException {
        backfillDerivedColumns(connection, null);
    }

    private void backfillDerivedColumns(Connection connection, String targetSymbol) throws SQLException {
        String selectSql = """
                select rowid, symbol, side, qty, price, fee, fee_currency, client_order_id, config_json
                  from fills
                 %s
                 order by symbol asc, fill_time_millis asc, rowid asc
                """.formatted(targetSymbol == null || targetSymbol.isBlank() ? "" : "where symbol = ?");
        String updateSql = """
                update fills
                   set value = ?, cumulative_qty = ?, cumulative_value = ?, cumulative_fee = ?,
                       net_qty = ?, floating_pnl_at_fill = ?, total_pnl_at_fill = ?
                 where rowid = ?
                """;
        BigDecimal cumulativeQty = BigDecimal.ZERO;
        BigDecimal cumulativeValue = BigDecimal.ZERO;
        BigDecimal cumulativeFee = BigDecimal.ZERO;
        String currentSymbol = null;
        String currentInitialClientOrderId = null;
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            if (targetSymbol != null && !targetSymbol.isBlank()) {
                select.setString(1, targetSymbol.trim().toUpperCase());
            }
            try (ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                String symbol = rs.getString("symbol");
                if (!symbol.equals(currentSymbol)) {
                    currentSymbol = symbol;
                    cumulativeQty = BigDecimal.ZERO;
                    cumulativeValue = BigDecimal.ZERO;
                    cumulativeFee = BigDecimal.ZERO;
                    currentInitialClientOrderId = null;
                }
                String side = rs.getString("side");
                String clientOrderId = rs.getString("client_order_id");
                if (isCycleInitial(clientOrderId, rs.getString("config_json"))
                        && !clientOrderId.equals(currentInitialClientOrderId)) {
                    cumulativeQty = BigDecimal.ZERO;
                    cumulativeValue = BigDecimal.ZERO;
                    cumulativeFee = BigDecimal.ZERO;
                    currentInitialClientOrderId = clientOrderId;
                }
                BigDecimal qty = decimal(rs.getString("qty"));
                BigDecimal price = decimal(rs.getString("price"));
                BigDecimal value = "CYCLE_END".equals(side) ? BigDecimal.ZERO : qty.multiply(price);
                BigDecimal fee = decimal(rs.getString("fee"));
                String feeCurrency = rs.getString("fee_currency");
                BigDecimal netQtyDelta = netQtyDelta(symbol, side, qty, fee, feeCurrency);
                if ("BUY".equals(side)) {
                    cumulativeQty = cumulativeQty.add(netQtyDelta);
                    cumulativeValue = cumulativeValue.add(value);
                    cumulativeFee = cumulativeFee.add(fee);
                } else if ("SELL".equals(side)) {
                    cumulativeQty = cumulativeQty.add(netQtyDelta);
                    cumulativeValue = cumulativeValue.subtract(value);
                    cumulativeFee = cumulativeFee.add(fee);
                } else if ("FUNDING_FEE".equals(side)) {
                    cumulativeFee = cumulativeFee.subtract(fee);
                }
                BigDecimal profit = cumulativeQty.multiply(price).subtract(cumulativeValue).subtract(cumulativeFee);
                update.setString(1, text(value));
                update.setString(2, text(cumulativeQty));
                update.setString(3, text(cumulativeValue));
                update.setString(4, text(cumulativeFee));
                update.setString(5, text(cumulativeQty));
                update.setString(6, text(profit));
                update.setString(7, text(profit));
                update.setLong(8, rs.getLong("rowid"));
                update.addBatch();
            }
            }
            update.executeBatch();
        }
    }

    private FillRecord record(ResultSet rs) throws SQLException {
        return new FillRecord(
                Instant.ofEpochMilli(rs.getLong("fill_time_millis")),
                rs.getString("symbol"),
                rs.getString("side"),
                decimal(rs.getString("qty")),
                decimal(rs.getString("price")),
                decimal(rs.getString("value")),
                decimal(rs.getString("fee")),
                rs.getString("fee_currency"),
                decimal(rs.getString("cumulative_qty")),
                decimal(rs.getString("cumulative_value")),
                decimal(rs.getString("cumulative_fee")),
                decimal(rs.getString("net_qty")),
                decimal(rs.getString("avg_entry_price")),
                decimal(rs.getString("realized_pnl")),
                decimal(rs.getString("floating_pnl_at_fill")),
                decimal(rs.getString("total_pnl_at_fill")),
                rs.getString("client_order_id"),
                rs.getString("execution_id"),
                rs.getString("config_json")
        );
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static String executionId(FillRecord record) {
        if (record.executionId() != null && !record.executionId().isBlank()) {
            return record.executionId();
        }
        return record.clientOrderId() + "@" + toMillis(record.time()) + "@" + text(record.qty()) + "@" + text(record.price());
    }

    private static long toMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static String text(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal netQtyDelta(String symbol, String side, BigDecimal qty, BigDecimal fee, String feeCurrency) {
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return BigDecimal.ZERO;
        }
        TradeSide tradeSide = "BUY".equals(side) ? TradeSide.BUY : TradeSide.SELL;
        return new FillEvent(
                "",
                symbol,
                "",
                tradeSide,
                qty == null ? BigDecimal.ZERO : qty,
                BigDecimal.ZERO,
                Instant.EPOCH,
                BigDecimal.ZERO,
                fee == null ? BigDecimal.ZERO : fee,
                feeCurrency
        ).netQtyDelta();
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
