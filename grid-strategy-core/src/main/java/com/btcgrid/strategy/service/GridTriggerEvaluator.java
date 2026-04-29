package com.btcgrid.strategy.service;

import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;

public class GridTriggerEvaluator {

    public boolean canActivate(MarketSnapshot marketSnapshot, StrategyLedgerSnapshot ledgerSnapshot) {
        BandZone currentZone = marketSnapshot.indicators().zoneOf(marketSnapshot.lastPrice());
        return currentZone == BandZone.ABOVE_UPPER || currentZone == BandZone.BELOW_LOWER;
    }
}
