package com.btcgrid.common.live;

import com.btcgrid.common.model.FillEvent;

public interface PrivateExecutionListener {

    void onExecution(FillEvent fillEvent);
}
