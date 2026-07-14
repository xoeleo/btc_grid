package com.btcgrid.bybit.adapter;

import com.btcgrid.common.indicator.IndicatorCalculator;
import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.ProductCategory;
import com.bybit.api.client.domain.market.MarketInterval;
import com.bybit.api.client.domain.market.request.MarketDataRequest;
import com.bybit.api.client.restApi.BybitApiMarketRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BybitSdkMarketDataGateway implements BybitMarketDataGateway {

    private static final long FOUR_HOUR_MILLIS = 4L * 60L * 60L * 1000L;
    private static final long ONE_HOUR_MILLIS = 60L * 60L * 1000L;

    private final BybitSdkClientFactory clientFactory;
    private final ProductCategory category;
    private final IndicatorCalculator indicatorCalculator;
    private final int emaLength4h;
    private final int bollLength4h;
    private final BigDecimal bollStdDevMultiplier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BybitSdkMarketDataGateway(
            BybitSdkClientFactory clientFactory,
            ProductCategory category,
            IndicatorCalculator indicatorCalculator,
            int emaLength4h,
            int bollLength4h,
            BigDecimal bollStdDevMultiplier
    ) {
        this.clientFactory = clientFactory;
        this.category = category;
        this.indicatorCalculator = indicatorCalculator;
        this.emaLength4h = emaLength4h;
        this.bollLength4h = bollLength4h;
        this.bollStdDevMultiplier = bollStdDevMultiplier;
    }

    @Override
    public InstrumentSpec loadInstrumentSpec(String symbol) {
        BybitApiMarketRestClient marketClient = clientFactory.createMarketRestClient();
        MarketDataRequest request = MarketDataRequest.builder()
                .category(BybitSdkMapper.toCategory(category))
                .symbol(symbol)
                .limit(1)
                .build();
        JsonNode root = objectMapper.valueToTree(marketClient.getInstrumentsInfo(request));
        JsonNode instruments = root.path("result").path("list");
        if (!instruments.isArray() || instruments.isEmpty()) {
            throw new IllegalStateException("No instrument info returned for symbol " + symbol);
        }
        return BybitSdkMapper.toInstrumentSpec(symbol, instruments.get(0));
    }

    @Override
    public MarketSnapshot latestSnapshot(String symbol) {
        BybitApiMarketRestClient marketClient = clientFactory.createMarketRestClient();
        JsonNode tickerRoot = objectMapper.valueToTree(marketClient.getMarketTickers(
                MarketDataRequest.builder()
                        .category(BybitSdkMapper.toCategory(category))
                        .symbol(symbol)
                        .build()
        ));
        JsonNode tickerList = tickerRoot.path("result").path("list");
        if (!tickerList.isArray() || tickerList.isEmpty()) {
            throw new IllegalStateException("No ticker returned for symbol " + symbol);
        }

        JsonNode ticker = tickerList.get(0);
        BigDecimal lastPrice = BybitSdkMapper.decimal(ticker.path("lastPrice").asText());
        BigDecimal markPrice = ticker.hasNonNull("markPrice") && !ticker.path("markPrice").asText().isBlank()
                ? BybitSdkMapper.decimal(ticker.path("markPrice").asText())
                : lastPrice;
        long responseTime = tickerRoot.path("time").asLong(Instant.now().toEpochMilli());
        List<Candle> candles = loadFourHourCandles(marketClient, symbol);
        return indicatorCalculator.calculate(symbol, candles, emaLength4h, bollLength4h, bollStdDevMultiplier)
                .map(indicators -> new MarketSnapshot(symbol, Instant.ofEpochMilli(responseTime), lastPrice, markPrice, indicators))
                .orElseThrow(() -> new IllegalStateException("Insufficient 4H candles to calculate indicators for " + symbol));
    }

    public BybitSdkClientFactory clientFactory() {
        return clientFactory;
    }

    private List<Candle> loadFourHourCandles(BybitApiMarketRestClient marketClient, String symbol) {
        int requiredFourHourCandles = Math.max(emaLength4h, bollLength4h) + 20;
        RuntimeException fourHourFailure = null;
        try {
            List<Candle> fourHourCandles = loadCandles(marketClient, symbol, MarketInterval.FOUR_HOURLY, requiredFourHourCandles, FOUR_HOUR_MILLIS);
            if (!fourHourCandles.isEmpty()) {
                return fourHourCandles;
            }
        } catch (RuntimeException e) {
            fourHourFailure = e;
        }
        List<Candle> hourlyCandles = loadCandles(marketClient, symbol, MarketInterval.HOURLY, requiredFourHourCandles * 4, ONE_HOUR_MILLIS);
        List<Candle> aggregated = aggregateFourHourCandles(symbol, hourlyCandles);
        if (aggregated.isEmpty()) {
            IllegalStateException exception = new IllegalStateException("No kline data returned for symbol " + symbol + " on 4H or 1H intervals");
            if (fourHourFailure != null) {
                exception.addSuppressed(fourHourFailure);
            }
            throw exception;
        }
        return aggregated;
    }

    private List<Candle> loadCandles(
            BybitApiMarketRestClient marketClient,
            String symbol,
            MarketInterval interval,
            int limit,
            long intervalMillis
    ) {
        JsonNode klineRoot = objectMapper.valueToTree(marketClient.getMarketLinesData(
                MarketDataRequest.builder()
                        .category(BybitSdkMapper.toCategory(category))
                        .symbol(symbol)
                        .marketInterval(interval)
                        .limit(limit)
                        .build()
        ));
        JsonNode klineList = klineRoot.path("result").path("list");
        if (!klineList.isArray()) {
            throw new IllegalStateException("No kline data returned for symbol " + symbol
                    + " interval=" + interval.getIntervalId()
                    + " response=" + klineRoot);
        }
        List<Candle> candles = new ArrayList<>();
        for (JsonNode klineNode : klineList) {
            candles.add(BybitSdkMapper.toCandle(symbol, klineNode, intervalMillis));
        }
        candles.sort(Comparator.comparing(Candle::openTime));
        return candles;
    }

    private List<Candle> aggregateFourHourCandles(String symbol, List<Candle> hourlyCandles) {
        Map<Long, List<Candle>> grouped = new TreeMap<>();
        for (Candle candle : hourlyCandles) {
            long bucket = (candle.openTime().toEpochMilli() / FOUR_HOUR_MILLIS) * FOUR_HOUR_MILLIS;
            grouped.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(candle);
        }

        List<Candle> aggregated = new ArrayList<>();
        for (Map.Entry<Long, List<Candle>> entry : grouped.entrySet()) {
            List<Candle> group = entry.getValue();
            if (group.size() < 4) {
                continue;
            }
            group.sort(Comparator.comparing(Candle::openTime));
            Candle first = group.get(0);
            Candle last = group.get(group.size() - 1);
            BigDecimal high = group.stream()
                    .map(Candle::high)
                    .max(Comparator.naturalOrder())
                    .orElse(first.high());
            BigDecimal low = group.stream()
                    .map(Candle::low)
                    .min(Comparator.naturalOrder())
                    .orElse(first.low());
            BigDecimal volume = group.stream()
                    .map(Candle::volume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            aggregated.add(new Candle(
                    symbol,
                    Instant.ofEpochMilli(entry.getKey()),
                    last.closeTime(),
                    first.open(),
                    high,
                    low,
                    last.close(),
                    volume
            ));
        }
        return aggregated;
    }
}
