package com.btcgrid.app;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.bybit.adapter.BybitSdkConfig;
import java.util.Properties;

public record GridProperties(
        GridStrategyConfig strategyConfig,
        BybitSdkConfig bybitSdkConfig,
        RuntimeSettings runtimeSettings,
        Properties rawProperties
) {
}
