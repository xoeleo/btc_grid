package com.btcgrid.common.model;

public enum TradeSide {
    BUY,
    SELL;

    public TradeSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
