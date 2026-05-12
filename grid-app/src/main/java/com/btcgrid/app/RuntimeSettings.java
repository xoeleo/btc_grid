package com.btcgrid.app;

import java.time.Duration;

public record RuntimeSettings(
        Duration marketPollInterval,
        boolean autoStartLive,
        String stateFilePath,
        String logFilePath,
        String statusFilePath,
        String fillsDbPath,
        boolean httpEnabled,
        String httpHost,
        int httpPort,
        int reconnectBaseDelaySeconds,
        int reconnectMaxAttempts
) {
}
