package com.btcgrid.bybit.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BybitSdkAccountGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BybitSdkAccountGateway gateway = new BybitSdkAccountGateway(null);

    @Test
    void readsUnifiedAccountCoinFromCategoryLayout() throws Exception {
        JsonNode account = objectMapper.readTree("""
                {
                  "accountType": "UnifiedTradingAccount",
                  "categories": [{
                    "category": "CRYPTO",
                    "coinDetail": [{"coin": "BTC", "equity": "2.00083871"}]
                  }]
                }
                """);

        assertEquals(new BigDecimal("2.00083871"), gateway.accountCoinBalance(account, "BTC"));
    }

    @Test
    void readsEarnAndFundingLayoutsWithoutDoubleCounting() throws Exception {
        JsonNode earn = objectMapper.readTree("""
                {
                  "accountType": "Earn",
                  "categories": [{
                    "category": "Easy Earn",
                    "coinDetail": [{"coin": "BTC", "equity": "3.03563211"}]
                  }]
                }
                """);
        JsonNode funding = objectMapper.readTree("""
                {
                  "accountType": "FundingAccount",
                  "coinDetail": [{"coin": "BTC", "equity": "0.00010842"}],
                  "categories": [{"coinDetail": [{"coin": "BTC", "equity": "99"}]}]
                }
                """);

        assertEquals(new BigDecimal("3.03563211"), gateway.accountCoinBalance(earn, "BTC"));
        assertEquals(new BigDecimal("0.00010842"), gateway.accountCoinBalance(funding, "BTC"));
    }
}
