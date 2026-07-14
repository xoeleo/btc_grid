package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.FundingFeeEvent;
import com.btcgrid.common.model.ProductCategory;
import com.bybit.api.client.config.BybitApiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BybitSdkFundingFeeQueryGateway implements BybitFundingFeeQueryGateway {

    private static final String DEMO_REST_DOMAIN = "https://api-demo.bybit.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final BybitSdkClientFactory clientFactory;
    private final ProductCategory category;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BybitSdkFundingFeeQueryGateway(BybitSdkClientFactory clientFactory, ProductCategory category) {
        this.clientFactory = clientFactory;
        this.category = category;
    }

    @Override
    public List<FundingFeeEvent> fetchFundingFees(String symbol, Instant startTime, Instant endTime) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        List<FundingFeeEvent> result = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("accountType", "UNIFIED");
            params.put("category", categoryValue());
            params.put("currency", "USDT");
            params.put("type", "SETTLEMENT");
            params.put("startTime", String.valueOf(startTime.toEpochMilli()));
            params.put("endTime", String.valueOf(endTime.toEpochMilli()));
            params.put("limit", "50");
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }

            JsonNode root = signedGet("getTransactionLog", "/v5/account/transaction-log", params);
            JsonNode list = root.path("result").path("list");
            if (!list.isArray()) {
                break;
            }
            for (JsonNode node : list) {
                String rowSymbol = node.path("symbol").asText("");
                if (!symbol.equalsIgnoreCase(rowSymbol)) {
                    continue;
                }
                BigDecimal funding = decimalOrZero(node.path("funding").asText());
                if (funding.signum() == 0) {
                    continue;
                }
                String transactionTime = node.path("transactionTime").asText("");
                if (transactionTime.isBlank()) {
                    continue;
                }
                String id = node.path("id").asText("");
                Instant time = Instant.ofEpochMilli(Long.parseLong(transactionTime));
                result.add(new FundingFeeEvent(
                        id.isBlank() ? "BYBIT_SETTLEMENT_" + rowSymbol + "_" + transactionTime : "BYBIT_SETTLEMENT_" + id,
                        rowSymbol,
                        time,
                        funding,
                        node.path("currency").asText("USDT")
                ));
            }
            cursor = root.path("result").path("nextPageCursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }
        result.sort(Comparator.comparing(FundingFeeEvent::time));
        return result;
    }

    private JsonNode signedGet(String operation, String path, Map<String, String> params) {
        BybitSdkConfig config = clientFactory.config();
        try {
            String query = queryString(params);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String recvWindow = String.valueOf(config.recvWindow());
            String signature = hmacSha256(config.apiSecret(), timestamp + config.apiKey() + recvWindow + query);
            HttpRequest request = HttpRequest.newBuilder(URI.create(restDomain(config) + path + "?" + query))
                    .timeout(REQUEST_TIMEOUT)
                    .header("X-BAPI-API-KEY", config.apiKey())
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-RECV-WINDOW", recvWindow)
                    .header("X-BAPI-SIGN", signature)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(operation, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted while calling Bybit REST", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException(operation + " failed while calling Bybit REST", e);
        }
    }

    private String restDomain(BybitSdkConfig config) {
        if (config.demo()) {
            return DEMO_REST_DOMAIN;
        }
        return config.testnet() ? BybitApiConfig.TESTNET_DOMAIN : BybitApiConfig.MAINNET_DOMAIN;
    }

    private String categoryValue() {
        return switch (category) {
            case SPOT -> "spot";
            case LINEAR -> "linear";
            case INVERSE -> "inverse";
            case OPTION -> "option";
        };
    }

    private String queryString(Map<String, String> params) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            pairs.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }
        return String.join("&", pairs);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String hmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private JsonNode validateResponse(String operation, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int retCode = root.path("retCode").asInt();
            if (retCode != 0) {
                String retMsg = root.path("retMsg").asText("unknown Bybit error");
                throw new IllegalStateException(operation + " rejected by Bybit: retCode=" + retCode + ", retMsg=" + retMsg);
            }
            return root;
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException(operation + " returned an unparseable Bybit response: " + body, e);
        }
    }

    private BigDecimal decimalOrZero(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
