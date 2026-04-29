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

public class BybitSdkMarketDataGateway implements BybitMarketDataGateway {

    private static final long FOUR_HOUR_MILLIS = 4L * 60L * 60L * 1000L;

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

        BigDecimal lastPrice = BybitSdkMapper.decimal(tickerList.get(0).path("lastPrice").asText());
        long responseTime = tickerRoot.path("time").asLong(Instant.now().toEpochMilli());
        List<Candle> candles = loadFourHourCandles(marketClient, symbol);
        return indicatorCalculator.calculate(symbol, candles, emaLength4h, bollLength4h, bollStdDevMultiplier)
                .map(indicators -> new MarketSnapshot(symbol, Instant.ofEpochMilli(responseTime), lastPrice, indicators))
                .orElseThrow(() -> new IllegalStateException("Insufficient 4H candles to calculate indicators for " + symbol));
    }

    public BybitSdkClientFactory clientFactory() {
        return clientFactory;
    }

    private List<Candle> loadFourHourCandles(BybitApiMarketRestClient marketClient, String symbol) {
        int limit = Math.max(emaLength4h, bollLength4h) + 20;
        JsonNode klineRoot = objectMapper.valueToTree(marketClient.getMarketLinesData(
                MarketDataRequest.builder()
                        .category(BybitSdkMapper.toCategory(category))
                        .symbol(symbol)
                        .marketInterval(MarketInterval.FOUR_HOURLY)
                        .limit(limit)
                        .build()
        ));
        JsonNode klineList = klineRoot.path("result").path("list");
        if (!klineList.isArray()) {
            throw new IllegalStateException("No kline data returned for symbol " + symbol);
        }
        List<Candle> candles = new ArrayList<>();
        for (JsonNode klineNode : klineList) {
            candles.add(BybitSdkMapper.toCandle(symbol, klineNode, FOUR_HOUR_MILLIS));
        }
        candles.sort(Comparator.comparing(Candle::openTime));
        return candles;
    }
}
