package com.btcgrid.bybit.adapter;

import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;

public interface BybitPrivateStreamGateway extends AutoCloseable {

    void connect(
            PrivateExecutionListener executionListener,
            PrivateFundingFeeListener fundingFeeListener,
            PrivateOrderListener orderListener,
            PrivateStreamLifecycleListener lifecycleListener
    );

    @Override
    void close();
}
