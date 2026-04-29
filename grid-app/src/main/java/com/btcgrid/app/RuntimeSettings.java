package com.btcgrid.app;

import java.time.Duration;

public record RuntimeSettings(
        Duration marketPollInterval,
        boolean autoStartLive,
        String stateFilePath,
        String logFilePath,
        String statusFilePath,
        boolean httpEnabled,
        int httpPort,
        int reconnectBaseDelaySeconds,
        int reconnectMaxAttempts
) {
}
