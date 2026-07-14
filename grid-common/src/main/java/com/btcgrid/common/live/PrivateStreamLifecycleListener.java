package com.btcgrid.common.live;

public interface PrivateStreamLifecycleListener {

    void onConnected();

    void onDisconnected(int code, String reason);

    void onStreamError(Throwable throwable);

    default void onSubscribed() {
    }

    default void onMessageReceived(String op, String topic) {
    }
}
