package com.btcgrid.app;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.StrategyCommandType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class RiskGuardService {

    private final GridStrategyConfig config;

    public RiskGuardService(GridStrategyConfig config) {
        this.config = config;
    }

    public List<RiskDecision> evaluate(List<StrategyCommand> commands, StrategyLedgerSnapshot ledgerSnapshot) {
        List<RiskDecision> decisions = new ArrayList<>();
        BigDecimal simulatedNetQty = ledgerSnapshot.netQty();
        for (StrategyCommand command : commands) {
            if (command.type() != StrategyCommandType.PLACE) {
                decisions.add(RiskDecision.allow(command));
                continue;
            }

            BigDecimal qty = command.orderIntent().qty();
            if (qty.compareTo(config.maxOrderQty()) > 0) {
                decisions.add(RiskDecision.block(command, "order-qty-exceeds-max"));
                continue;
            }

            TradeSide side = command.orderIntent().side();
            BigDecimal nextNetQty = simulatedNetQty.add(side == TradeSide.BUY ? qty : qty.negate());
            if (config.entryDirection().isSpotLongOnly() && nextNetQty.signum() < 0) {
                decisions.add(RiskDecision.block(command, "spot-sell-exceeds-inventory"));
                continue;
            }
            if (!command.orderIntent().reduceOnly() && nextNetQty.abs().compareTo(config.maxInventoryQty()) > 0) {
                decisions.add(RiskDecision.block(command, "net-inventory-exceeds-max"));
                continue;
            }

            if (!command.orderIntent().reduceOnly() && directionalExposure(nextNetQty).compareTo(config.maxDirectionalInventoryQty()) > 0) {
                decisions.add(RiskDecision.block(command, "directional-inventory-exceeds-max"));
                continue;
            }

            decisions.add(RiskDecision.allow(command));
            simulatedNetQty = nextNetQty;
        }
        return decisions;
    }

    private BigDecimal directionalExposure(BigDecimal netQty) {
        return netQty.abs();
    }
}
