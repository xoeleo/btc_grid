package com.btcgrid.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Saved theoretical allocation reference; independent of active trading configuration. */
public final class RebalancePriceRangeStore {
    private final Path path;
    public RebalancePriceRangeStore(Path path) { this.path = path.toAbsolutePath(); }

    public record Range(BigDecimal lowPrice, BigDecimal highPrice) {
        public Range {
            validate(lowPrice); validate(highPrice);
            if (highPrice.compareTo(lowPrice) <= 0) {
                throw new IllegalArgumentException("最高价必须大于最低价");
            }
        }
        private static void validate(BigDecimal value) {
            if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("1000000000")) > 0
                    || value.scale() > 8 || value.precision() > 18) {
                throw new IllegalArgumentException("价格必须是大于0的有效数值，最多8位小数");
            }
        }
        public BigDecimal targetRatio(BigDecimal price) {
            if (price == null || price.signum() <= 0) return null;
            return highPrice.subtract(price).divide(highPrice.subtract(lowPrice), 12, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO).min(BigDecimal.ONE);
        }
    }
    public synchronized Range load() throws IOException {
        if (!Files.exists(path)) return new Range(new BigDecimal("60000"), new BigDecimal("200000"));
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) { properties.load(in); }
        try {
            return new Range(new BigDecimal(properties.getProperty("lowPrice", "")),
                    new BigDecimal(properties.getProperty("highPrice", "")));
        } catch (IllegalArgumentException e) {
            throw new IOException("保存的波段价格无效，请重新保存高低价", e);
        }
    }
    public synchronized Range save(Range range) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "price-range-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("lowPrice", range.lowPrice().stripTrailingZeros().toPlainString());
            properties.setProperty("highPrice", range.highPrice().stripTrailingZeros().toPlainString());
            try (OutputStream out = Files.newOutputStream(temporary)) { properties.store(out, "BTC theoretical price range"); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return range;
    }
}
