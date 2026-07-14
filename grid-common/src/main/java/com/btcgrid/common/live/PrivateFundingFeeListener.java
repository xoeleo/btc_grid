package com.btcgrid.common.live;

import com.btcgrid.common.model.FundingFeeEvent;

public interface PrivateFundingFeeListener {
    void onFundingFee(FundingFeeEvent fundingFeeEvent);
}
