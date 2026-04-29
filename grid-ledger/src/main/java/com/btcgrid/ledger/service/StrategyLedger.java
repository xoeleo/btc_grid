package com.btcgrid.ledger.service;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;

public interface StrategyLedger {

    StrategyLedgerSnapshot snapshot();

    void restore(StrategyLedgerSnapshot snapshot);

    void applyFill(FillEvent fillEvent);

    void markToMarket(MarketSnapshot marketSnapshot);

    void switchInventoryMode(InventoryMode inventoryMode);
}
