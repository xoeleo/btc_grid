package com.btcgrid.app;

import com.btcgrid.strategy.model.StrategyStateSnapshot;
import java.util.Optional;

public interface StateStore {

    Optional<StrategyStateSnapshot> load();

    void save(StrategyStateSnapshot snapshot);
}
