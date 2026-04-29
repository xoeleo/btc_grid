package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;

public interface BybitMarketDataGateway {

    InstrumentSpec loadInstrumentSpec(String symbol);

    MarketSnapshot latestSnapshot(String symbol);
}
