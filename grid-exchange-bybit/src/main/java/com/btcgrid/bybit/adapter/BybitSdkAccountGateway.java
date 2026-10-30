package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.ProductCategory;
import com.bybit.api.client.domain.account.AccountType;
import com.bybit.api.client.domain.account.request.AccountDataRequest;
import com.bybit.api.client.domain.position.request.PositionDataRequest;
import com.bybit.api.client.restApi.BybitApiAccountRestClient;
import com.bybit.api.client.restApi.BybitApiPositionRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BybitSdkAccountGateway implements BybitAccountGateway {

    private final BybitSdkClientFactory clientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public BybitSdkAccountGateway(BybitSdkClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public AccountAssetSnapshot walletSnapshot(
            String accountType,
            String baseCoin,
            String quoteCoin,
            ProductCategory positionCategory,
            String symbol
    ) {
        String normalizedAccountType = normalize(accountType, "UNIFIED");
        String normalizedBaseCoin = normalize(baseCoin, "BTC");
        String normalizedQuoteCoin = normalize(quoteCoin, "USDT");
        return assetOverviewSnapshot(
                normalizedAccountType,
                normalizedBaseCoin,
                normalizedQuoteCoin,
                positionCategory,
                symbol
        );
    }

    @Override
    public AccountAssetSnapshot tradingWalletSnapshot(
            String accountType,
            String baseCoin,
            String quoteCoin,
            ProductCategory positionCategory,
            String symbol
    ) {
        String normalizedAccountType = normalize(accountType, "UNIFIED");
        String normalizedBaseCoin = normalize(baseCoin, "BTC");
        String normalizedQuoteCoin = normalize(quoteCoin, "USDT");
        return walletBalanceSnapshot(
                normalizedAccountType,
                normalizedBaseCoin,
                normalizedQuoteCoin,
                positionCategory,
                symbol,
                null
        );
    }

    private AccountAssetSnapshot assetOverviewSnapshot(
            String normalizedAccountType,
            String normalizedBaseCoin,
            String normalizedQuoteCoin,
            ProductCategory positionCategory,
            String symbol
    ) {
        JsonNode root = signedAssetOverview(normalizedBaseCoin + "," + normalizedQuoteCoin);
        int retCode = root.path("retCode").asInt();
        if (retCode != 0) {
            throw new IllegalStateException("getAssetBalanceOverview rejected by Bybit: retCode="
                    + retCode + ", retMsg=" + root.path("retMsg").asText("unknown Bybit error"));
        }
        JsonNode result = root.path("result");
        BigDecimal totalEquity = decimal(result.path("totalEquity").asText("0"));
        BigDecimal baseBalance = BigDecimal.ZERO;
        BigDecimal quoteBalance = BigDecimal.ZERO;
        JsonNode accounts = result.path("list");
        if (accounts.isArray()) {
            for (JsonNode account : accounts) {
                String type = account.path("accountType").asText("");
                if ("UnifiedTradingAccount".equalsIgnoreCase(type)
                        || "FundingAccount".equalsIgnoreCase(type)
                        || "Earn".equalsIgnoreCase(type)) {
                    baseBalance = baseBalance.add(accountCoinBalance(account, normalizedBaseCoin));
                    quoteBalance = quoteBalance.add(accountCoinBalance(account, normalizedQuoteCoin));
                }
            }
        }
        BigDecimal contractPositionQty = contractPositionQty(positionCategoryForRatio(positionCategory), symbol);
        BigDecimal netBaseQty = baseBalance.add(contractPositionQty);
        long responseTime = root.path("time").asLong(Instant.now().toEpochMilli());
        return new AccountAssetSnapshot(
                Instant.ofEpochMilli(responseTime),
                normalizedAccountType,
                normalizedBaseCoin,
                normalizedQuoteCoin,
                baseBalance,
                quoteBalance,
                contractPositionQty,
                netBaseQty,
                totalEquity
        );
    }

    private AccountAssetSnapshot walletBalanceSnapshot(
            String normalizedAccountType,
            String normalizedBaseCoin,
            String normalizedQuoteCoin,
            ProductCategory positionCategory,
            String symbol,
            RuntimeException assetOverviewFailure
    ) {
        BybitApiAccountRestClient accountClient = clientFactory.createAccountRestClient();
        AccountDataRequest request = AccountDataRequest.builder()
                .accountType(AccountType.valueOf(normalizedAccountType))
                .coin(normalizedBaseCoin + "," + normalizedQuoteCoin)
                .build();
        JsonNode root = objectMapper.valueToTree(accountClient.getWalletBalance(request));
        int retCode = root.path("retCode").asInt();
        if (retCode != 0) {
            throw new IllegalStateException("getWalletBalance rejected by Bybit: retCode="
                    + retCode + ", retMsg=" + root.path("retMsg").asText("unknown Bybit error"));
        }
        JsonNode account = root.path("result").path("list").isArray() && !root.path("result").path("list").isEmpty()
                ? root.path("result").path("list").get(0)
                : null;
        if (account == null) {
            throw new IllegalStateException("getWalletBalance returned no account list: " + root);
        }
        BigDecimal totalEquity = decimal(account.path("totalEquity").asText("0"));
        BigDecimal baseBalance = BigDecimal.ZERO;
        BigDecimal quoteBalance = BigDecimal.ZERO;
        BigDecimal usdValueSum = BigDecimal.ZERO;
        JsonNode coins = account.path("coin");
        if (coins.isArray()) {
            for (JsonNode coin : coins) {
                String coinName = coin.path("coin").asText("").toUpperCase(Locale.ROOT);
                BigDecimal walletBalance = decimal(firstNonBlank(
                        coin.path("walletBalance").asText(""),
                        coin.path("equity").asText(""),
                        "0"
                ));
                usdValueSum = usdValueSum.add(decimal(coin.path("usdValue").asText("0")));
                if (normalizedBaseCoin.equals(coinName)) {
                    baseBalance = walletBalance;
                } else if (normalizedQuoteCoin.equals(coinName)) {
                    quoteBalance = walletBalance;
                }
            }
        }
        if (totalEquity.signum() <= 0) {
            totalEquity = usdValueSum;
        }
        BigDecimal contractPositionQty = contractPositionQty(positionCategory, symbol);
        BigDecimal netBaseQty = baseBalance.add(contractPositionQty);
        long responseTime = root.path("time").asLong(Instant.now().toEpochMilli());
        AccountAssetSnapshot snapshot = new AccountAssetSnapshot(
                Instant.ofEpochMilli(responseTime),
                normalizedAccountType,
                normalizedBaseCoin,
                normalizedQuoteCoin,
                baseBalance,
                quoteBalance,
                contractPositionQty,
                netBaseQty,
                totalEquity
        );
        if (assetOverviewFailure != null) {
            assetOverviewFailure.addSuppressed(new IllegalStateException("fallback wallet snapshot used: " + snapshot));
        }
        return snapshot;
    }

    BigDecimal accountCoinBalance(JsonNode account, String coinName) {
        Optional<BigDecimal> directBalance = coinDetailBalance(account.path("coinDetail"), coinName);
        if (directBalance.isPresent()) {
            return directBalance.get();
        }
        BigDecimal categoryBalance = BigDecimal.ZERO;
        JsonNode categories = account.path("categories");
        if (categories.isArray()) {
            for (JsonNode category : categories) {
                categoryBalance = categoryBalance.add(
                        coinDetailBalance(category.path("coinDetail"), coinName).orElse(BigDecimal.ZERO)
                );
            }
        }
        return categoryBalance;
    }

    private Optional<BigDecimal> coinDetailBalance(JsonNode coinDetails, String coinName) {
        if (!coinDetails.isArray()) {
            return Optional.empty();
        }
        for (JsonNode coin : coinDetails) {
            if (coinName.equalsIgnoreCase(coin.path("coin").asText(""))) {
                return Optional.of(decimal(firstNonBlank(
                        coin.path("equity").asText(""),
                        coin.path("walletBalance").asText(""),
                        "0"
                )));
            }
        }
        return Optional.empty();
    }

    private BigDecimal contractPositionQty(ProductCategory category, String symbol) {
        if (category != ProductCategory.LINEAR && category != ProductCategory.INVERSE) {
            return BigDecimal.ZERO;
        }
        BybitApiPositionRestClient positionClient = clientFactory.createPositionRestClient();
        PositionDataRequest request = PositionDataRequest.builder()
                .category(BybitSdkMapper.toCategory(category))
                .symbol(symbol)
                .build();
        JsonNode root = objectMapper.valueToTree(positionClient.getPositionInfo(request));
        int retCode = root.path("retCode").asInt();
        if (retCode != 0) {
            throw new IllegalStateException("getPositionInfo rejected by Bybit: retCode="
                    + retCode + ", retMsg=" + root.path("retMsg").asText("unknown Bybit error"));
        }
        BigDecimal signedQty = BigDecimal.ZERO;
        JsonNode positions = root.path("result").path("list");
        if (!positions.isArray()) {
            return BigDecimal.ZERO;
        }
        for (JsonNode position : positions) {
            BigDecimal size = decimal(position.path("size").asText("0"));
            String side = position.path("side").asText("");
            if ("Buy".equalsIgnoreCase(side)) {
                signedQty = signedQty.add(size);
            } else if ("Sell".equalsIgnoreCase(side)) {
                signedQty = signedQty.subtract(size);
            }
        }
        return signedQty;
    }

    private ProductCategory positionCategoryForRatio(ProductCategory category) {
        if (category == ProductCategory.INVERSE) {
            return ProductCategory.INVERSE;
        }
        return ProductCategory.LINEAR;
    }

    private JsonNode signedAssetOverview(String coins) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("coin", coins);
            String queryString = queryString(params);
            BybitSdkConfig config = clientFactory.config();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String recvWindow = String.valueOf(config.recvWindow());
            String payload = timestamp + config.apiKey() + recvWindow + queryString;
            String signature = hmacSha256(config.apiSecret(), payload);
            URI uri = URI.create(clientFactory.tradeBaseUrl() + "/v5/asset/asset-overview?" + queryString);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("X-BAPI-API-KEY", config.apiKey())
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-RECV-WINDOW", recvWindow)
                    .header("X-BAPI-SIGN", signature)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("getAssetBalanceOverview HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("getAssetBalanceOverview interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("getAssetBalanceOverview failed: " + e.getMessage(), e);
        }
    }

    private static String queryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static String normalize(String value, String fallback) {
        return (value == null || value.isBlank() ? fallback : value.trim()).toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "0";
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}
