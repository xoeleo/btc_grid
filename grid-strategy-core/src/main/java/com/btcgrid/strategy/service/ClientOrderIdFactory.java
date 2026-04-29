package com.btcgrid.strategy.service;

import java.time.Instant;
import java.util.UUID;

public class ClientOrderIdFactory {

    public String next(String symbol, String reason) {
        return "GRID_" + symbol + "_" + reason + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
