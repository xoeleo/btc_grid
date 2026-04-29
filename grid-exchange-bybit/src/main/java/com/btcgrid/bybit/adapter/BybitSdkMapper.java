package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.bybit.api.client.domain.CategoryType;
import com.bybit.api.client.domain.TradeOrderType;
import com.bybit.api.client.domain.trade.PositionIdx;
import com.bybit.api.client.domain.trade.Side;
import com.bybit.api.client.domain.trade.TimeInForce;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public final class BybitSdkMapper {

    private BybitSdkMapper() {
    }

    public static CategoryType toCategory(ProductCategory category) {
        return switch (category) {
            case SPOT -> CategoryType.SPOT;
            case LINEAR -> CategoryType.LINEAR;
            case INVERSE -> CategoryType.INVERSE;
            case OPTION -> CategoryType.OPTION;
        };
    }

    public static TradeOrderRequest toTradeOrderRequest(ProductCategory category, OrderIntent orderIntent) {
        TradeOrderRequest.TradeOrderRequestBuilder builder = TradeOrderRequest.builder()
                .category(toCategory(category))
                .symbol(orderIntent.symbol())
                .side(orderIntent.side() == TradeSide.BUY ? Side.BUY : Side.SELL)
                .orderType(orderIntent.type() == OrderType.MARKET ? TradeOrderType.MARKET : TradeOrderType.LIMIT)
                .qty(orderIntent.qty().stripTrailingZeros().toPlainString())
                .orderLinkId(orderIntent.clientOrderId())
                .reduceOnly(orderIntent.reduceOnly());

        if (category == ProductCategory.LINEAR || category == ProductCategory.INVERSE) {
            builder.positionIdx(PositionIdx.ONE_WAY_MODE);
        }
        if (orderIntent.type() == OrderType.LIMIT) {
            builder.price(orderIntent.price().stripTrailingZeros().toPlainString())
                    .timeInForce(TimeInForce.GOOD_TILL_CANCEL);
        } else {
            builder.timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
        }
        return builder.build();
    }

    public static InstrumentSpec toInstrumentSpec(String symbol, JsonNode instrumentNode) {
        JsonNode lotSizeFilter = instrumentNode.path("lotSizeFilter");
        JsonNode priceFilter = instrumentNode.path("priceFilter");
        return new InstrumentSpec(
                symbol,
                decimal(lotSizeFilter, "qtyStep", "0.001"),
                decimal(lotSizeFilter, "minOrderQty", "0.001"),
                decimal(priceFilter, "tickSize", "0.1"),
                decimal(priceFilter, "minPrice", "0.1")
        );
    }

    public static Candle toCandle(String symbol, JsonNode klineNode, long intervalMillis) {
        long openTime = klineNode.path(0).asLong();
        return new Candle(
                symbol,
                Instant.ofEpochMilli(openTime),
                Instant.ofEpochMilli(openTime + intervalMillis),
                decimal(klineNode.get(1).asText()),
                decimal(klineNode.get(2).asText()),
                decimal(klineNode.get(3).asText()),
                decimal(klineNode.get(4).asText()),
                decimal(klineNode.get(5).asText())
        );
    }

    public static BigDecimal decimal(JsonNode node, String field, String defaultValue) {
        JsonNode child = node.path(field);
        return decimal(child.isMissingNode() || child.isNull() ? defaultValue : child.asText());
    }

    public static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
