package com.btcgrid.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Atomic runtime storage for ordered monitor rules and the active Cycle's rule snapshot. */
public final class RebalanceMonitorStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path rulesPath;
    private final Path activePath;

    public RebalanceMonitorStore(Path statePath) {
        rulesPath = statePath.resolveSibling("rebalance-monitors.json");
        activePath = statePath.resolveSibling("rebalance-monitor-active.json");
    }

    public boolean configured() { return Files.exists(rulesPath); }

    /** Makes the existing long-below monitor visible without changing its trigger or active Cycle. */
    public synchronized void importLegacyLongBelow(RebalanceSettings settings, RebalanceState state) {
        if (configured() || !settings.enabled() || settings.mode() != RebalanceMode.AUTO_LONG_BELOW) return;
        BigDecimal triggerPrice = settings.bands().stream()
                .map(RebalanceBand::priceFloor).min(BigDecimal::compareTo)
                .orElseThrow(() -> new IllegalStateException("现有做多监控缺少触发价"));
        RebalanceMonitorRule rule = new RebalanceMonitorRule(
                "legacy-auto-long-below", "现有低于阈值做多", true,
                com.btcgrid.common.model.TradeSide.BUY, RebalanceMonitorRule.Trigger.BELOW,
                triggerPrice, settings.autoInitialSteps(), settings.autoBaseGridQty(), BigDecimal.ONE).validated();
        if (state != null && ("GRID_ACTIVE".equals(state.status()) || "GRID_OPENING".equals(state.status()))) {
            if (state.activeSide() != com.btcgrid.common.model.TradeSide.BUY)
                throw new IllegalStateException("当前 Cycle 与原做多监控方向不一致，停止自动迁移");
            if (state.targetRatio() != null && state.targetRatio().compareTo(BigDecimal.ONE) != 0)
                throw new IllegalStateException("当前 Cycle 目标比例与原做多监控不一致，停止自动迁移");
            setActive(rule);
        }
        save(List.of(rule));
    }

    public List<RebalanceMonitorRule> rules() {
        if (!configured()) return List.of();
        try {
            return JSON.readValue(rulesPath.toFile(), new TypeReference<List<RebalanceMonitorRule>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("读取监控配置失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<RebalanceMonitorRule> save(List<RebalanceMonitorRule> requested) {
        if (requested == null || requested.size() > 50) throw new IllegalArgumentException("最多保存 50 条监控");
        List<RebalanceMonitorRule> result = requested.stream().map(RebalanceMonitorRule::validated).toList();
        Set<String> ids = new HashSet<>();
        for (RebalanceMonitorRule rule : result) {
            if (!ids.add(rule.id())) throw new IllegalArgumentException("监控 ID 重复");
        }
        Optional<RebalanceMonitorRule> running = active();
        if (running.isPresent() && !ids.contains(running.orElseThrow().id()))
            throw new IllegalArgumentException("当前 Cycle 对应的规则不能删除；可先 Disable，待 Cycle 结束后再删除");
        write(rulesPath, result);
        return result;
    }

    public Optional<RebalanceMonitorRule> active() {
        if (!Files.exists(activePath)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(activePath.toFile(), RebalanceMonitorRule.class).validated());
        } catch (IOException e) {
            throw new IllegalStateException("读取当前 Cycle 监控配置失败: " + e.getMessage(), e);
        }
    }

    public Optional<RebalanceMonitorRule> activeCurrentRule() {
        return active().map(snapshot -> rules().stream()
                .filter(rule -> rule.id().equals(snapshot.id()))
                .findFirst().orElse(snapshot));
    }

    public void setActive(RebalanceMonitorRule rule) { write(activePath, rule); }

    public void clearActive() {
        try { Files.deleteIfExists(activePath); }
        catch (IOException e) { throw new IllegalStateException("清除当前 Cycle 监控配置失败", e); }
    }

    private static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
            try {
                JSON.writeValue(temporary.toFile(), value);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) {
            throw new IllegalStateException("保存监控配置失败: " + e.getMessage(), e);
        }
    }
}
