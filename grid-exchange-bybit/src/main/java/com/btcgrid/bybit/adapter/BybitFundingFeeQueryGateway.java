package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.FundingFeeEvent;
import java.time.Instant;
import java.util.List;

public interface BybitFundingFeeQueryGateway {

    List<FundingFeeEvent> fetchFundingFees(String symbol, Instant startTime, Instant endTime);
}
