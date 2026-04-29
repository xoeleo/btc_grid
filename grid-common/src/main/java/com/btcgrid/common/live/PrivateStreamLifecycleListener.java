package com.btcgrid.common.live;

public interface PrivateStreamLifecycleListener {

    void onConnected();

    void onDisconnected(int code, String reason);

    void onStreamError(Throwable throwable);
}
