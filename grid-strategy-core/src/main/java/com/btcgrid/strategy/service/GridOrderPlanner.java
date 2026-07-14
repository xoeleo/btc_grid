package com.btcgrid.strategy.service;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.config.SpacingLadder;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.strategy.model.OrderPairPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.BiPredicate;

public class GridOrderPlanner {

    private final ClientOrderIdFactory clientOrderIdFactory;
    private final BigDecimal profitableReduceSideMultiplier;
    private final ProfitStateEvaluator profitableStateEvaluator;

    @FunctionalInterface
    public interface ProfitStateEvaluator {
        boolean test(MarketSnapshot marketSnapshot, StrategyLedgerSnapshot ledgerSnapshot, BigDecimal orderPrice);
    }

    public GridOrderPlanner(ClientOrderIdFactory clientOrderIdFactory) {
        this(clientOrderIdFactory, BigDecimal.ONE);
    }

    public GridOrderPlanner(ClientOrderIdFactory clientOrderIdFactory, BigDecimal profitableReduceSideMultiplier) {
        this(
                clientOrderIdFactory,
                profitableReduceSideMultiplier,
                (marketSnapshot, ledgerSnapshot) -> ledgerSnapshot.realizedPnl().add(ledgerSnapshot.floatingPnl()).signum() > 0
        );
    }

    public GridOrderPlanner(
            ClientOrderIdFactory clientOrderIdFactory,
            BigDecimal profitableReduceSideMultiplier,
            BiPredicate<MarketSnapshot, StrategyLedgerSnapshot> profitableStateEvaluator
    ) {
        this(
                clientOrderIdFactory,
                profitableReduceSideMultiplier,
                profitableStateEvaluator == null
                        ? null
                        : (marketSnapshot, ledgerSnapshot, orderPrice) -> profitableStateEvaluator.test(marketSnapshot, ledgerSnapshot)
        );
    }

    public GridOrderPlanner(
            ClientOrderIdFactory clientOrderIdFactory,
            BigDecimal profitableReduceSideMultiplier,
            ProfitStateEvaluator profitableStateEvaluator
    ) {
        this.clientOrderIdFactory = clientOrderIdFactory;
        this.profitableReduceSideMultiplier = profitableReduceSideMultiplier == null || profitableReduceSideMultiplier.compareTo(BigDecimal.ONE) < 0
                ? BigDecimal.ONE
                : profitableReduceSideMultiplier;
        this.profitableStateEvaluator = profitableStateEvaluator == null
                ? (marketSnapshot, ledgerSnapshot, orderPrice) -> false
                : profitableStateEvaluator;
    }

    public OrderIntent planInitialOrder(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            MarketSnapshot marketSnapshot
    ) {
        return planInitialOrder(config, instrumentSpec, marketSnapshot, false);
    }

    public OrderIntent planInitialOrder(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            MarketSnapshot marketSnapshot,
            boolean ignoreBandCondition
    ) {
        BigDecimal currentPrice = marketSnapshot.lastPrice();
        boolean aboveUpper = currentPrice.compareTo(marketSnapshot.indicators().bollUpper4h()) > 0;
        boolean belowLower = currentPrice.compareTo(marketSnapshot.indicators().bollLower4h()) < 0;
        if (!ignoreBandCondition && !aboveUpper && !belowLower) {
            return null;
        }
        TradeSide side = ignoreBandCondition
                ? manualInitialSide(config)
                : bandInitialSide(aboveUpper, belowLower);
        if (config.entryDirection().isSpotLongOnly()) {
            side = TradeSide.BUY;
        }
        if (side == null) {
            return null;
        }
        if ((side == TradeSide.BUY && !config.entryDirection().allowsLongEntry())
                || (side == TradeSide.SELL && !config.entryDirection().allowsShortEntry())) {
            return null;
        }
        BigDecimal entrySteps = config.maxInitialSteps();
        BigDecimal rawQty = entrySteps.multiply(config.baseGridQty());
        BigDecimal finalQty = clampTradableQty(
                instrumentSpec,
                rawQty,
                maxAddQty(config, BigDecimal.ZERO).min(config.maxOrderQty()),
                currentPrice
        );
        if (finalQty.signum() == 0) {
            return null;
        }

        return new OrderIntent(
                marketSnapshot.symbol(),
                clientOrderIdFactory.next(marketSnapshot.symbol(), "INIT"),
                side,
                OrderType.MARKET,
                finalQty,
                null,
                false,
                "initial-position"
        );
    }

    private TradeSide manualInitialSide(GridStrategyConfig config) {
        if (config.entryDirection().allowsLongEntry() && !config.entryDirection().allowsShortEntry()) {
            return TradeSide.BUY;
        }
        if (config.entryDirection().allowsShortEntry() && !config.entryDirection().allowsLongEntry()) {
            return TradeSide.SELL;
        }
        return TradeSide.BUY;
    }

    private TradeSide bandInitialSide(boolean aboveUpper, boolean belowLower) {
        if (aboveUpper) {
            return TradeSide.SELL;
        }
        if (belowLower) {
            return TradeSide.BUY;
        }
        return null;
    }

    public OrderPairPlan planPairedOrders(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            MarketSnapshot marketSnapshot,
            StrategyLedgerSnapshot ledgerSnapshot,
            BigDecimal anchorPrice,
            BigDecimal requoteSpacing,
            boolean boostReduceSide,
            String pairReason
    ) {
        BigDecimal baseSpacing = baseSpacing(config, anchorPrice);
        BigDecimal referencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                ? marketSnapshot.lastPrice()
                : ledgerSnapshot.lastFillPrice();
        StepPlan buyStepPlan = stepPlan(marketSnapshot.lastPrice(), referencePrice, baseSpacing, ledgerSnapshot, TradeSide.BUY);
        StepPlan sellStepPlan = stepPlan(marketSnapshot.lastPrice(), referencePrice, baseSpacing, ledgerSnapshot, TradeSide.SELL);
        BigDecimal buyPlacementDistance = requoteSpacing != null
                ? requoteSpacing
                : buyStepPlan.placementDistance(baseSpacing);
        BigDecimal sellPlacementDistance = requoteSpacing != null
                ? requoteSpacing
                : sellStepPlan.placementDistance(baseSpacing);
        Placement buyPlacement = adjustedPlacement(
                instrumentSpec,
                anchorPrice,
                buyPlacementDistance,
                marketSnapshot.lastPrice(),
                TradeSide.BUY
        );
        Placement sellPlacement = adjustedPlacement(
                instrumentSpec,
                anchorPrice,
                sellPlacementDistance,
                marketSnapshot.lastPrice(),
                TradeSide.SELL
        );
        BigDecimal buyPrice = buyPlacement.price();
        BigDecimal sellPrice = sellPlacement.price();
        BigDecimal buyNormalQty = clampTradableQty(
                instrumentSpec,
                qtyFromPlacementDistance(buyPlacement.distance(), baseSpacing, config.baseGridQty()),
                config.maxOrderQty(),
                buyPrice
        );
        BigDecimal sellNormalQty = clampTradableQty(
                instrumentSpec,
                qtyFromPlacementDistance(sellPlacement.distance(), baseSpacing, config.baseGridQty()),
                config.maxOrderQty(),
                sellPrice
        );
        if (buyNormalQty.signum() == 0 && sellNormalQty.signum() == 0) {
            return null;
        }
        BigDecimal buyQty = buyNormalQty;
        BigDecimal sellQty = sellNormalQty;
        BigDecimal buyQtyBeforeProfitBoost = buyQty;
        BigDecimal sellQtyBeforeProfitBoost = sellQty;
        boolean buyReduceQtyApplied = false;
        boolean sellReduceQtyApplied = false;
        boolean buyProfitBoostApplied = false;
        boolean sellProfitBoostApplied = false;

        if (config.entryDirection().isSpotLongOnly()) {
            buyQty = capAddQty(config, instrumentSpec, buyQty, ledgerSnapshot);
            sellQty = clampTradableQty(instrumentSpec, sellNormalQty.min(ledgerSnapshot.netQty().max(BigDecimal.ZERO)), config.maxOrderQty(), sellPrice);
            sellQtyBeforeProfitBoost = sellQty;
            sellQty = profitableReduceSideQty(config, instrumentSpec, marketSnapshot, ledgerSnapshot, sellQty, sellPrice);
            sellProfitBoostApplied = sellQty.compareTo(sellQtyBeforeProfitBoost) > 0;
        } else if (ledgerSnapshot.netQty().signum() > 0) {
            buyQty = capAddQty(config, instrumentSpec, buyQty, ledgerSnapshot);
            BigDecimal reducedSellQty = config.classicReduceSizing()
                    ? BigDecimal.ZERO
                    : reduceSideQty(config, instrumentSpec, ledgerSnapshot, referencePrice, sellPrice, marketSnapshot.indicators().bollUpper4h());
            if (!config.classicReduceSizing() && reducedSellQty.compareTo(sellNormalQty) >= 0 && reducedSellQty.signum() > 0) {
                sellQty = reducedSellQty;
                sellReduceQtyApplied = true;
            } else {
                sellQty = clampTradableQty(instrumentSpec, sellNormalQty.min(ledgerSnapshot.netQty().abs()), config.maxOrderQty(), sellPrice);
            }
            if (!config.classicReduceSizing() && boostReduceSide) {
                sellQty = boostedReduceQty(config, instrumentSpec, ledgerSnapshot, sellQty).min(ledgerSnapshot.netQty().abs());
            }
            sellQtyBeforeProfitBoost = sellQty;
            sellQty = profitableReduceSideQty(config, instrumentSpec, marketSnapshot, ledgerSnapshot, sellQty, sellPrice);
            sellProfitBoostApplied = sellQty.compareTo(sellQtyBeforeProfitBoost) > 0;
        } else if (ledgerSnapshot.netQty().signum() < 0) {
            sellQty = capAddQty(config, instrumentSpec, sellQty, ledgerSnapshot);
            BigDecimal reducedBuyQty = config.classicReduceSizing()
                    ? BigDecimal.ZERO
                    : reduceSideQty(config, instrumentSpec, ledgerSnapshot, referencePrice, buyPrice, marketSnapshot.indicators().bollLower4h());
            if (!config.classicReduceSizing() && reducedBuyQty.compareTo(buyNormalQty) >= 0 && reducedBuyQty.signum() > 0) {
                buyQty = reducedBuyQty;
                buyReduceQtyApplied = true;
            } else {
                buyQty = clampTradableQty(instrumentSpec, buyNormalQty.min(ledgerSnapshot.netQty().abs()), config.maxOrderQty(), buyPrice);
            }
            if (!config.classicReduceSizing() && boostReduceSide) {
                buyQty = boostedReduceQty(config, instrumentSpec, ledgerSnapshot, buyQty).min(ledgerSnapshot.netQty().abs());
            }
            buyQtyBeforeProfitBoost = buyQty;
            buyQty = profitableReduceSideQty(config, instrumentSpec, marketSnapshot, ledgerSnapshot, buyQty, buyPrice);
            buyProfitBoostApplied = buyQty.compareTo(buyQtyBeforeProfitBoost) > 0;
        }
        if (buyQty.signum() == 0 && sellQty.signum() == 0) {
            return null;
        }

        OrderIntent buy = buyQty.signum() == 0 ? null : new OrderIntent(
                marketSnapshot.symbol(),
                clientOrderIdFactory.next(marketSnapshot.symbol(), "PAIR_BUY"),
                TradeSide.BUY,
                OrderType.LIMIT,
                buyQty,
                buyPrice,
                false,
                pairSideReason(pairReason, "buy", buyReduceQtyApplied, buyProfitBoostApplied, buyQtyBeforeProfitBoost, buyQty)
        );
        OrderIntent sell = sellQty.signum() == 0 ? null : new OrderIntent(
                marketSnapshot.symbol(),
                clientOrderIdFactory.next(marketSnapshot.symbol(), "PAIR_SELL"),
                TradeSide.SELL,
                OrderType.LIMIT,
                sellQty,
                sellPrice,
                false,
                pairSideReason(pairReason, "sell", sellReduceQtyApplied, sellProfitBoostApplied, sellQtyBeforeProfitBoost, sellQty)
        );
        return new OrderPairPlan(buy, sell);
    }

    private Placement adjustedPlacement(
            InstrumentSpec instrumentSpec,
            BigDecimal anchorPrice,
            BigDecimal placementDistance,
            BigDecimal latestPrice,
            TradeSide side
    ) {
        BigDecimal plannedPrice = side == TradeSide.BUY
                ? anchorPrice.subtract(placementDistance)
                : anchorPrice.add(placementDistance);
        BigDecimal roundedPlannedPrice = instrumentSpec.roundPrice(plannedPrice);
        if (latestPrice == null || latestPrice.signum() <= 0) {
            return new Placement(roundedPlannedPrice, placementDistance);
        }

        BigDecimal roundedLatestPrice = instrumentSpec.roundPrice(latestPrice);
        boolean marketable = side == TradeSide.BUY
                ? roundedPlannedPrice.compareTo(roundedLatestPrice) > 0
                : roundedPlannedPrice.compareTo(roundedLatestPrice) < 0;
        if (!marketable) {
            return new Placement(roundedPlannedPrice, placementDistance);
        }

        BigDecimal adjustedDistance = side == TradeSide.BUY
                ? anchorPrice.subtract(roundedLatestPrice)
                : roundedLatestPrice.subtract(anchorPrice);
        return new Placement(roundedLatestPrice, adjustedDistance);
    }

    private String pairSideReason(
            String pairReason,
            String sideTag,
            boolean reduceQtyApplied,
            boolean profitBoostApplied,
            BigDecimal baseQty,
            BigDecimal boostedQty
    ) {
        String baseReason = pairReason == null
                ? ("buy".equals(sideTag) ? "grid-buy" : "grid-sell")
                : pairReason + "|" + sideTag;
        String reason = reduceQtyApplied ? baseReason + "|reduce-qty" : baseReason;
        if (profitBoostApplied) {
            reason += "|profit-reduce-boost:base=" + baseQty.stripTrailingZeros().toPlainString()
                    + ",boosted=" + boostedQty.stripTrailingZeros().toPlainString();
        }
        return reason;
    }

    private BigDecimal reduceSideQty(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            StrategyLedgerSnapshot ledgerSnapshot,
            BigDecimal referencePrice,
            BigDecimal orderPrice,
            BigDecimal bandEdgePrice
    ) {
        BigDecimal inventory = ledgerSnapshot.netQty().abs();
        if (inventory.signum() == 0 || referencePrice == null || orderPrice == null || bandEdgePrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal denominator = ledgerSnapshot.netQty().signum() > 0
                ? bandEdgePrice.subtract(referencePrice)
                : referencePrice.subtract(bandEdgePrice);
        BigDecimal numerator = ledgerSnapshot.netQty().signum() > 0
                ? orderPrice.subtract(referencePrice)
                : referencePrice.subtract(orderPrice);
        if (numerator.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (denominator.signum() <= 0) {
            return clampQty(inventory, config.maxOrderQty());
        }
        BigDecimal rawQty = inventory
                .divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(numerator);
        return clampTradableQty(instrumentSpec, rawQty, inventory.min(config.maxOrderQty()), orderPrice);
    }

    public BigDecimal nextRequoteSpacing(
            GridStrategyConfig config,
            BigDecimal currentSpacing,
            BigDecimal referencePrice
    ) {
        BigDecimal shrunkSpacing = currentSpacing.multiply(config.gridRequoteShrinkFactor());
        BigDecimal baseSpacing = baseSpacing(config, referencePrice);
        return shrunkSpacing.max(baseSpacing);
    }

    public BigDecimal baseSpacing(GridStrategyConfig config, BigDecimal referencePrice) {
        return spacingFromRatio(config.baseGridSpacing(), referencePrice);
    }

    public OrderIntent planDeinventoryOrder(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            MarketSnapshot marketSnapshot,
            StrategyLedgerSnapshot ledgerSnapshot
    ) {
        if (ledgerSnapshot.netQty().signum() == 0) {
            return null;
        }
        BigDecimal referencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                ? marketSnapshot.lastPrice()
                : ledgerSnapshot.lastFillPrice();
        BigDecimal baseSpacing = baseSpacing(config, referencePrice);
        TradeSide side = ledgerSnapshot.netQty().signum() > 0 ? TradeSide.SELL : TradeSide.BUY;
        StepPlan stepPlan = stepPlan(marketSnapshot.lastPrice(), referencePrice, baseSpacing, ledgerSnapshot, side);
        BigDecimal normalQty = clampTradableQty(
                instrumentSpec,
                stepPlan.qty(config.baseGridQty()),
                config.maxOrderQty(),
                marketSnapshot.lastPrice()
        );
        if (normalQty.signum() == 0) {
            return null;
        }
        BigDecimal pnlRate = computePnlRate(ledgerSnapshot);
        BigDecimal boost = BigDecimal.ONE.add(
                pnlRate.multiply(config.deinventoryBoostSlope()).min(config.deinventoryBoostCap())
        );
        BigDecimal qty = tradableQty(instrumentSpec, normalQty.multiply(boost), marketSnapshot.lastPrice());
        qty = qty.min(ledgerSnapshot.netQty().abs());
        if (qty.signum() == 0) {
            return null;
        }

        return new OrderIntent(
                marketSnapshot.symbol(),
                clientOrderIdFactory.next(marketSnapshot.symbol(), "DEINV"),
                side,
                OrderType.LIMIT,
                qty,
                marketSnapshot.lastPrice(),
                !config.entryDirection().isSpotLongOnly(),
                "deinventory"
        );
    }

    private BigDecimal boostedReduceQty(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            StrategyLedgerSnapshot ledgerSnapshot,
            BigDecimal normalQty
    ) {
        BigDecimal pnlRate = computePnlRate(ledgerSnapshot).max(BigDecimal.ZERO);
        BigDecimal boost = BigDecimal.ONE.add(
                pnlRate.multiply(config.deinventoryBoostSlope()).min(config.deinventoryBoostCap())
        );
        BigDecimal boostedQty = tradableQty(instrumentSpec, normalQty.multiply(boost), ledgerSnapshot.lastFillPrice());
        BigDecimal reduceCap = ledgerSnapshot.netQty().abs();
        return clampTradableQty(instrumentSpec, boostedQty.min(reduceCap), config.maxOrderQty(), ledgerSnapshot.lastFillPrice());
    }

    private BigDecimal profitableReduceSideQty(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            MarketSnapshot marketSnapshot,
            StrategyLedgerSnapshot ledgerSnapshot,
            BigDecimal normalQty,
            BigDecimal price
    ) {
        if (normalQty == null || normalQty.signum() <= 0 || profitableReduceSideMultiplier.compareTo(BigDecimal.ONE) <= 0) {
            return normalQty;
        }
        if (!profitableStateEvaluator.test(marketSnapshot, ledgerSnapshot, price)) {
            return normalQty;
        }
        BigDecimal reduceCap = ledgerSnapshot.netQty().abs();
        BigDecimal boostedQty = tradableQty(instrumentSpec, normalQty.multiply(profitableReduceSideMultiplier), price);
        return clampTradableQty(instrumentSpec, boostedQty.min(reduceCap), config.maxOrderQty(), price);
    }

    private BigDecimal capAddQty(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            BigDecimal qty,
            StrategyLedgerSnapshot ledgerSnapshot
    ) {
        return clampTradableQty(
                instrumentSpec,
                qty,
                maxAddQty(config, ledgerSnapshot.netQty().abs()),
                ledgerSnapshot.lastFillPrice()
        );
    }

    private BigDecimal maxAddQty(GridStrategyConfig config, BigDecimal currentAbsQty) {
        BigDecimal maxAbsQty = config.maxInventoryQty().min(config.maxDirectionalInventoryQty());
        BigDecimal remaining = maxAbsQty.subtract(currentAbsQty);
        return remaining.max(BigDecimal.ZERO);
    }

    private BigDecimal placementMultiplier(StrategyLedgerSnapshot ledgerSnapshot, TradeSide orderSide) {
        int sameDirectionCount = consecutiveCount(ledgerSnapshot, orderSide);
        if (sameDirectionCount <= 1) {
            return BigDecimal.ONE;
        }
        return SpacingLadder.multiplier(sameDirectionCount);
    }

    private int consecutiveCount(StrategyLedgerSnapshot ledgerSnapshot, TradeSide orderSide) {
        return orderSide == TradeSide.BUY
                ? ledgerSnapshot.consecutiveBuyFills()
                : ledgerSnapshot.consecutiveSellFills();
    }

    private BigDecimal previousLegDistance(StrategyLedgerSnapshot ledgerSnapshot, TradeSide orderSide) {
        if (ledgerSnapshot.lastFillPrice() == null || ledgerSnapshot.previousFillPrice() == null) {
            return BigDecimal.ZERO;
        }
        if (ledgerSnapshot.lastFillPrice().signum() == 0 || ledgerSnapshot.previousFillPrice().signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (ledgerSnapshot.lastFillSide() != orderSide) {
            return BigDecimal.ZERO;
        }
        if (consecutiveCount(ledgerSnapshot, orderSide) <= 1) {
            return BigDecimal.ZERO;
        }
        return ledgerSnapshot.lastFillPrice().subtract(ledgerSnapshot.previousFillPrice()).abs();
    }

    private BigDecimal qtyFromPlacementDistance(
            BigDecimal placementDistance,
            BigDecimal baseSpacing,
            BigDecimal baseGridQty
    ) {
        if (placementDistance == null || placementDistance.signum() <= 0 || baseSpacing == null || baseSpacing.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal steps = placementDistance.divide(baseSpacing, 8, RoundingMode.HALF_UP);
        return steps.multiply(baseGridQty);
    }

    private BigDecimal clampQty(BigDecimal qty, BigDecimal maxOrderQty) {
        return qty.min(maxOrderQty);
    }

    private BigDecimal tradableQty(InstrumentSpec instrumentSpec, BigDecimal qty, BigDecimal price) {
        if (qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rounded = instrumentSpec.roundQtyDown(qty);
        if (rounded.compareTo(instrumentSpec.minOrderQty()) < 0) {
            return BigDecimal.ZERO;
        }
        if (instrumentSpec.minNotionalValue().signum() > 0 && price != null && price.signum() > 0
                && rounded.multiply(price).compareTo(instrumentSpec.minNotionalValue()) < 0) {
            rounded = instrumentSpec.roundQtyUp(instrumentSpec.minNotionalValue().divide(price, 16, RoundingMode.CEILING));
        }
        return rounded.compareTo(instrumentSpec.minOrderQty()) < 0 ? BigDecimal.ZERO : rounded;
    }

    private BigDecimal clampTradableQty(InstrumentSpec instrumentSpec, BigDecimal qty, BigDecimal maxOrderQty, BigDecimal price) {
        BigDecimal tradable = tradableQty(instrumentSpec, qty, price);
        if (tradable.signum() == 0 || maxOrderQty == null || maxOrderQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal capped = instrumentSpec.roundQtyDown(tradable.min(maxOrderQty));
        if (capped.compareTo(instrumentSpec.minOrderQty()) < 0) {
            return BigDecimal.ZERO;
        }
        if (instrumentSpec.minNotionalValue().signum() > 0 && price != null && price.signum() > 0
                && capped.multiply(price).compareTo(instrumentSpec.minNotionalValue()) < 0) {
            return BigDecimal.ZERO;
        }
        return capped;
    }

    private BigDecimal spacingFromRatio(BigDecimal spacingRatio, BigDecimal referencePrice) {
        return referencePrice.multiply(spacingRatio);
    }

    private BigDecimal computePnlRate(StrategyLedgerSnapshot ledgerSnapshot) {
        if (ledgerSnapshot.avgEntryPrice().signum() == 0 || ledgerSnapshot.netQty().signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal exposure = ledgerSnapshot.avgEntryPrice().multiply(ledgerSnapshot.netQty().abs());
        if (exposure.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return ledgerSnapshot.floatingPnl().divide(exposure, 8, RoundingMode.HALF_UP);
    }

    private StepPlan stepPlan(
            BigDecimal currentPrice,
            BigDecimal referencePrice,
            BigDecimal baseSpacing,
            StrategyLedgerSnapshot ledgerSnapshot,
            TradeSide orderSide
    ) {
        BigDecimal currentDistance = currentPrice.subtract(referencePrice).abs();
        BigDecimal previousLegDistance = previousLegDistance(ledgerSnapshot, orderSide);
        BigDecimal multiplier = placementMultiplier(ledgerSnapshot, orderSide);
        BigDecimal placementDistance = previousLegDistance.signum() > 0
                ? previousLegDistance.multiply(multiplier)
                : baseSpacing;
        BigDecimal derivedSteps = baseSpacing.signum() <= 0
                ? BigDecimal.ZERO
                : placementDistance.divide(baseSpacing, 8, RoundingMode.HALF_UP);
        return new StepPlan(previousLegDistance, multiplier, placementDistance, derivedSteps);
    }

    private record StepPlan(
            BigDecimal previousLegDistance,
            BigDecimal multiplier,
            BigDecimal placementDistance,
            BigDecimal derivedSteps
    ) {
        private BigDecimal qty(BigDecimal baseGridQty) {
            return derivedSteps.multiply(baseGridQty);
        }

        private BigDecimal placementDistance(BigDecimal baseSpacing) {
            return placementDistance;
        }
    }

    private record Placement(BigDecimal price, BigDecimal distance) {
    }
}
