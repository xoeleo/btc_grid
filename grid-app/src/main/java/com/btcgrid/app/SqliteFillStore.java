package com.btcgrid.app;

import com.btcgrid.common.model.ClientOrderIds;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SqliteFillStore implements FillStore {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

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
                  total_pnl_at_fill, client_order_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save fill record", e);
        }
    }

    @Override
    public synchronized List<FillRecord> recent(int limit) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id
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
    public synchronized List<FillRecord> recentCycles(int limit) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id
                  from fills
                 where side = 'CYCLE_END'
                 order by fill_time_millis desc, rowid desc
                 limit ?
                """;
        return query(sql, Math.max(1, limit));
    }

    @Override
    public synchronized List<FillRecord> cycleFills(String cycleExecutionId) {
        if (cycleExecutionId == null || cycleExecutionId.isBlank()) {
            return List.of();
        }
        FillRecord cycleEnd = cycleEnd(cycleExecutionId.trim());
        if (cycleEnd == null) {
            return List.of();
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id
                  from fills
                 where side in ('BUY', 'SELL')
                   and symbol = ?
                   and fill_time_millis < ?
                 order by fill_time_millis desc, rowid desc
                """;
        List<FillRecord> fills = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cycleEnd.symbol());
            statement.setLong(2, toMillis(cycleEnd.time()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    FillRecord record = record(rs);
                    fills.add(record);
                    if (ClientOrderIds.isInitialPositionOrder(record.clientOrderId())) {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cycle fills", e);
        }
        Collections.reverse(fills);
        return fills;
    }

    @Override
    public synchronized CurrentCycleSummary currentCycle(String symbol, Instant now, BigDecimal markPrice) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id
                  from fills
                 where side in ('BUY', 'SELL')
                   and symbol = ?
                 order by fill_time_millis desc, rowid desc
                """;
        List<FillRecord> cycleFills = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol.trim().toUpperCase());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    FillRecord record = record(rs);
                    cycleFills.add(record);
                    if (ClientOrderIds.isInitialPositionOrder(record.clientOrderId())) {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read current cycle", e);
        }
        if (cycleFills.isEmpty() || !ClientOrderIds.isInitialPositionOrder(cycleFills.get(cycleFills.size() - 1).clientOrderId())) {
            return null;
        }
        Collections.reverse(cycleFills);
        return summarizeCycle(cycleFills, now == null ? Instant.now() : now, markPrice);
    }

    private FillRecord cycleEnd(String cycleExecutionId) {
        String sql = """
                select execution_id, fill_time_millis, symbol, side, qty, price,
                       value, fee, fee_currency, cumulative_qty, cumulative_value, cumulative_fee,
                       net_qty, avg_entry_price, realized_pnl, floating_pnl_at_fill,
                       total_pnl_at_fill, client_order_id
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
                       total_pnl_at_fill, client_order_id
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
        BigDecimal cumulativeFee = BigDecimal.ZERO;
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
            if ("BUY".equals(fill.side())) {
                buyCount++;
                buyQty = buyQty.add(qty, MATH_CONTEXT);
                cumulativeQty = cumulativeQty.add(qty, MATH_CONTEXT);
                cumulativeValue = cumulativeValue.add(value, MATH_CONTEXT);
            } else if ("SELL".equals(fill.side())) {
                sellCount++;
                sellQty = sellQty.add(qty, MATH_CONTEXT);
                cumulativeQty = cumulativeQty.subtract(qty, MATH_CONTEXT);
                cumulativeValue = cumulativeValue.subtract(value, MATH_CONTEXT);
            }
            cumulativeFee = cumulativeFee.add(fee, MATH_CONTEXT);
        }
        if (latest == null) {
            return null;
        }
        BigDecimal latestFillPrice = latest.price();
        BigDecimal fillPricePnl = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, latestFillPrice);
        BigDecimal marketPrice = markPrice == null || markPrice.signum() == 0 ? latestFillPrice : markPrice;
        BigDecimal markPricePnl = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, marketPrice);
        String entryDirection = "BUY".equals(fills.get(0).side()) ? "LONG" : "SHORT";
        Instant startedAt = fills.get(0).time();
        long durationSeconds = Math.max(0L, now.getEpochSecond() - startedAt.getEpochSecond());
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
                latestFillPrice,
                marketPrice,
                entryDirection,
                fillPricePnl,
                markPricePnl
        );
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
            statement.execute("create index if not exists idx_fills_time on fills(fill_time_millis desc)");
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
        String selectSql = """
                select rowid, symbol, side, qty, price, fee, client_order_id
                  from fills
                 order by symbol asc, fill_time_millis asc, rowid asc
                """;
        String updateSql = """
                update fills
                   set value = ?, cumulative_qty = ?, cumulative_value = ?, cumulative_fee = ?,
                       floating_pnl_at_fill = ?, total_pnl_at_fill = ?
                 where rowid = ?
                """;
        BigDecimal cumulativeQty = BigDecimal.ZERO;
        BigDecimal cumulativeValue = BigDecimal.ZERO;
        BigDecimal cumulativeFee = BigDecimal.ZERO;
        String currentSymbol = null;
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rs = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            while (rs.next()) {
                String symbol = rs.getString("symbol");
                if (!symbol.equals(currentSymbol)) {
                    currentSymbol = symbol;
                    cumulativeQty = BigDecimal.ZERO;
                    cumulativeValue = BigDecimal.ZERO;
                    cumulativeFee = BigDecimal.ZERO;
                }
                String side = rs.getString("side");
                String clientOrderId = rs.getString("client_order_id");
                if (ClientOrderIds.isInitialPositionOrder(clientOrderId)) {
                    cumulativeQty = BigDecimal.ZERO;
                    cumulativeValue = BigDecimal.ZERO;
                    cumulativeFee = BigDecimal.ZERO;
                }
                BigDecimal qty = decimal(rs.getString("qty"));
                BigDecimal price = decimal(rs.getString("price"));
                BigDecimal value = "CYCLE_END".equals(side) ? BigDecimal.ZERO : qty.multiply(price);
                BigDecimal fee = decimal(rs.getString("fee"));
                if ("BUY".equals(side)) {
                    cumulativeQty = cumulativeQty.add(qty);
                    cumulativeValue = cumulativeValue.add(value);
                    cumulativeFee = cumulativeFee.add(fee);
                } else if ("SELL".equals(side)) {
                    cumulativeQty = cumulativeQty.subtract(qty);
                    cumulativeValue = cumulativeValue.subtract(value);
                    cumulativeFee = cumulativeFee.add(fee);
                }
                BigDecimal profit = cumulativeQty.multiply(price).subtract(cumulativeValue).subtract(cumulativeFee);
                update.setString(1, text(value));
                update.setString(2, text(cumulativeQty));
                update.setString(3, text(cumulativeValue));
                update.setString(4, text(cumulativeFee));
                update.setString(5, text(profit));
                update.setString(6, text(profit));
                update.setLong(7, rs.getLong("rowid"));
                update.addBatch();
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
                rs.getString("execution_id")
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

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
