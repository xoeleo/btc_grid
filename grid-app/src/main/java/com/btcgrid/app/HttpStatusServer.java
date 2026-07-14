package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class HttpStatusServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier;
    private final LiveControlService liveControlService;
    private final EventLogger eventLogger;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public HttpStatusServer(int port, RuntimeStatusProvider runtimeStatusProvider, EventLogger eventLogger) {
        this("0.0.0.0", port, runtimeStatusProvider::snapshot, null, eventLogger);
    }

    public HttpStatusServer(
            String host,
            int port,
            Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier,
            LiveControlService liveControlService,
            EventLogger eventLogger
    ) {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create HTTP status server on " + host + ":" + port, e);
        }
        this.httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "grid-http-handler");
            thread.setDaemon(true);
            return thread;
        });
        this.httpServer.setExecutor(httpExecutor);
        this.runtimeStatusSupplier = runtimeStatusSupplier;
        this.liveControlService = liveControlService;
        this.eventLogger = eventLogger;
        registerContexts();
    }

    public void start() {
        httpServer.start();
        eventLogger.info("http.started", Map.of(
                "host", httpServer.getAddress().getHostString(),
                "port", httpServer.getAddress().getPort()
        ));
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        httpExecutor.shutdownNow();
    }

    private void registerContexts() {
        httpServer.createContext("/", this::writeIndex);
        httpServer.createContext("/status", exchange -> writeJson(exchange, runtimeStatusSupplier.get()));
        httpServer.createContext("/ledger", exchange -> writeJson(exchange, runtimeStatusSupplier.get().ledgerSnapshot()));
        httpServer.createContext("/runtime", exchange -> writeJson(exchange, runtimeStatusSupplier.get().runtimeState()));
        httpServer.createContext("/orders", exchange -> writeJson(exchange, runtimeStatusSupplier.get().workingOrders()));
        httpServer.createContext("/health", exchange -> writeJson(exchange, Map.of("ok", true)));
        if (liveControlService != null) {
            httpServer.createContext("/api/control", exchange -> writeJson(exchange, liveControlService.controlState()));
            httpServer.createContext("/api/rebalance-config", exchange -> {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, liveControlService.rebalanceConfig());
                    return;
                }
                requirePost(exchange);
                LiveControlService.RebalanceConfigRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.RebalanceConfigRequest.class);
                writeJson(exchange, liveControlService.saveRebalanceConfig(request));
            });
            httpServer.createContext("/api/rebalance-state", exchange -> writeJson(exchange, liveControlService.rebalanceState()));
            httpServer.createContext("/api/rebalance-orders", exchange -> writeJson(exchange, liveControlService.rebalanceOrders()));
            httpServer.createContext("/api/rebalance-history", exchange -> writeJson(exchange, liveControlService.rebalanceHistory()));
            httpServer.createContext("/api/rebalance-fills", exchange -> writeJson(exchange, liveControlService.rebalanceFillsPage(
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0)
            )));
            httpServer.createContext("/api/rebalance-funding-fees", exchange -> writeJson(exchange, liveControlService.rebalanceFundingFees()));
            httpServer.createContext("/api/rebalance-cycles", exchange -> writeJson(exchange, liveControlService.rebalanceCyclesPage(
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0)
            )));
            httpServer.createContext("/api/rebalance-current-cycle", exchange -> writeJson(exchange, liveControlService.rebalanceCurrentCycle()));
            httpServer.createContext("/api/rebalance-cycle-fills", exchange -> writeJson(exchange, liveControlService.rebalanceCycleFills(queryParam(exchange, "executionId"))));
            httpServer.createContext("/api/rebalance-start", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.startRebalance());
            });
            httpServer.createContext("/api/rebalance-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.stopRebalance());
            });
            httpServer.createContext("/api/fills", exchange -> writeJson(exchange, liveControlService.recentFills()));
            httpServer.createContext("/api/cycles", exchange -> writeJson(exchange, liveControlService.recentCycles()));
            httpServer.createContext("/api/current-cycle", exchange -> writeJson(exchange, liveControlService.currentCycle()));
            httpServer.createContext("/api/cycle-fills", exchange -> writeJson(exchange, liveControlService.cycleFills(queryParam(exchange, "executionId"))));
            httpServer.createContext("/api/cycle-config", exchange -> writeJson(exchange, liveControlService.cycleConfig(queryParam(exchange, "executionId"))));
            httpServer.createContext("/api/start", exchange -> {
                requirePost(exchange);
                LiveControlService.StartRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.StartRequest.class);
                writeJson(exchange, liveControlService.start(request));
            });
            httpServer.createContext("/api/stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.stop());
            });
            httpServer.createContext("/api/resume", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.resume());
            });
            httpServer.createContext("/api/flatten-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.flattenAndStop());
            });
            httpServer.createContext("/api/cancel-order", exchange -> {
                requirePost(exchange);
                LiveControlService.CancelOrderRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.CancelOrderRequest.class);
                writeJson(exchange, liveControlService.cancelOrder(request));
            });
        }
    }

    private void requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] bytes = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
            throw new IOException("Method not allowed");
        }
    }

    private void writeIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String html = "/grid".equals(path) || "/rebalance".equals(path) ? indexHtml() : landingHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private String landingHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>BTC Grid 入口</title>
                  <style>
                    * { box-sizing: border-box; }
                    body { font-family: system-ui, sans-serif; margin: 0; padding: 28px; background: #f6f3ec; color: #222; }
                    .wrap { max-width: 820px; margin: 0 auto; }
                    .panel { background: #fffaf0; border: 1px solid #dfd3bd; border-radius: 10px; padding: 22px; }
                    .entry { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin-top: 18px; }
                    a { display: block; text-decoration: none; color: #222; background: #fff; border: 1px solid #dfd3bd; border-radius: 8px; padding: 18px; }
                    a strong { display: block; font-size: 20px; margin-bottom: 8px; }
                    a span { color: #6f5a30; }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <div class="panel">
                      <h1>BTC Grid 入口</h1>
                      <div class="entry">
                        <a href="/grid"><strong>手动网格</strong><span>启动、停止、清仓、查看 Cycle 和成交。</span></a>
                        <a href="/rebalance"><strong>BTC 再平衡</strong><span>配置资产占比目标，独立监控和运行再平衡。</span></a>
                      </div>
                    </div>
                  </div>
                </body>
                </html>
                """;
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (name.equals(decode(key))) {
                return equals < 0 ? "" : decode(part.substring(equals + 1));
            }
        }
        return "";
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int queryInt(HttpExchange exchange, String name, int fallback) {
        String value = queryParam(exchange, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void writeJson(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private String indexHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>BTC Grid 控制台</title>
                  <style>
                    * { box-sizing: border-box; }
                    body { font-family: system-ui, sans-serif; margin: 0; padding: 18px; background: #f6f3ec; color: #222; }
                    .card { background: #fffaf0; border: 1px solid #dfd3bd; border-radius: 10px; padding: 18px; width: 100%; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
                    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin: 14px 0; }
                    .metric { background: #ffffff; border: 1px solid #dfd3bd; border-radius: 8px; padding: 10px 12px; }
                    .metric span { display: block; color: #726650; font-size: 12px; }
                    .metric strong { display: block; margin-top: 4px; font-size: 18px; }
                    .badge { display: inline-flex; align-items: center; min-height: 28px; padding: 0 10px; border-radius: 999px; font-size: 13px; font-weight: 800; }
                    .badge-running { background: #d7f0e4; color: #0f5c3f; }
                    .badge-idle { background: #eee6d4; color: #6f5a30; }
                    .badge-error { background: #f7d6d1; color: #8d251b; }
                    .notice { display: none; margin: 12px 0; padding: 13px 14px; border-radius: 8px; font-weight: 800; border-left: 6px solid transparent; }
                    .notice-info { display: block; background: #e8eef7; color: #26405f; border-color: #5f7ea8; }
                    .notice-ok { display: block; background: #d7f0e4; color: #0f5c3f; border-color: #229366; }
                    .notice-error { display: block; background: #f7d6d1; color: #8d251b; border-color: #c0392b; }
                    .notice-warning { display: block; background: #fff2c7; color: #744c05; border-color: #c79216; }
                    .action-banner { display: none; margin: 12px 0; padding: 14px 16px; border-radius: 8px; border: 1px solid #c7bca8; font-weight: 900; }
                    .action-banner.active { display: block; }
                    .action-banner-info { background: #e8eef7; color: #26405f; border-color: #5f7ea8; }
                    .action-banner-ok { background: #d7f0e4; color: #0f5c3f; border-color: #229366; }
                    .action-banner-error { background: #f7d6d1; color: #8d251b; border-color: #c0392b; }
                    .nav { display: flex; gap: 8px; margin: 0 0 16px; }
                    .nav a { display: inline-flex; align-items: center; min-height: 34px; padding: 0 12px; border-radius: 8px; border: 1px solid #c7bca8; color: #4f422f; text-decoration: none; font-weight: 800; background: #fff; }
                    .nav a.active { background: #176b4d; color: #fff; border-color: #176b4d; }
                    label { display: grid; gap: 5px; font-size: 13px; color: #544; }
                    input, select { padding: 9px; border: 1px solid #c7bca8; border-radius: 8px; font-size: 14px; background: white; }
                    small { color: #8a7d69; font-size: 11px; }
                    button { padding: 10px 14px; border: 0; border-radius: 9px; margin-right: 8px; cursor: pointer; font-weight: 700; }
                    button:disabled { cursor: not-allowed; opacity: 0.55; }
                    .start { background: #176b4d; color: white; }
                    .stop { background: #9b6a17; color: white; }
                    .danger { background: #a33125; color: white; }
                    .compact { padding: 6px 10px; border-radius: 7px; margin-right: 0; font-size: 12px; }
                    .metric-button { border: 0; background: transparent; color: inherit; font: inherit; font-weight: 800; padding: 0; cursor: pointer; text-align: left; }
                    .metric-button:hover { text-decoration: underline; }
                    .pager { display: flex; align-items: center; gap: 8px; margin: 8px 0 10px; }
                    .pager span { color: #6f5a30; font-size: 12px; font-weight: 700; }
                    .table-wrap { width: 100%; overflow-x: auto; border: 1px solid #dfd3bd; border-radius: 8px; background: #fffaf0; }
                    table { width: 100%; min-width: max-content; border-collapse: collapse; font-size: 13px; }
                    th, td { border-bottom: 1px solid #dfd3bd; padding: 8px; text-align: left; white-space: nowrap; }
                    th { color: #5c4b34; background: #f3ead7; }
                    td.muted { color: #8a7d69; }
                    .order-orphan { background: #fff7df; }
                    .order-role { display: inline-flex; align-items: center; min-height: 24px; padding: 0 8px; border-radius: 999px; font-size: 12px; font-weight: 800; }
                    .order-role-pair { background: #d7f0e4; color: #0f5c3f; }
                    .order-role-orphan { background: #fff2c7; color: #744c05; }
                    .section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 22px; }
                    .toggle-row { display: flex; align-items: center; gap: 8px; min-height: 38px; }
                    .toggle-row input { width: 18px; height: 18px; }
                    .pnl-pos { color: #0f6b45; font-weight: 700; }
                    .pnl-neg { color: #a33125; font-weight: 700; }
                    .pnl-flat { color: #6f5a30; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>BTC Grid 控制台</h2>
                    <p class="nav">
                      <a href="/grid">手动网格</a>
                      <a href="/rebalance">BTC 再平衡</a>
                    </p>
                    <div class="manual-section">
                    <div class="summary">
                      <div class="metric"><span>运行状态</span><strong><span id="runBadge" class="badge badge-idle">读取中</span></strong></div>
                      <div class="metric"><span>当前标的</span><strong id="activeSymbol">-</strong></div>
                      <div class="metric"><span>Cycle</span><strong id="cycleStatus">-</strong></div>
                      <div class="metric"><span>策略状态</span><strong id="strategyState">-</strong></div>
                      <div class="metric"><span>后台操作</span><strong id="operationStatus">-</strong></div>
                      <div class="metric"><span>最新价 / 标记价</span><strong id="lastPrice">-</strong></div>
                      <div class="metric"><span>挂单数量</span><strong id="orderCount">0</strong></div>
                      <div class="metric"><span>最近订单</span><strong id="lastOrderStatus">-</strong></div>
                    </div>
                    <div class="grid" id="form"></div>
                    <p>
                      <button id="startButton" class="start" onclick="start()">启动</button>
                      <button id="resumeButton" class="start" onclick="post('/api/resume')">继续运行</button>
                      <button id="stopButton" class="stop" onclick="post('/api/stop')">停止</button>
                      <button id="flattenButton" class="danger" onclick="post('/api/flatten-stop')">清仓并停止</button>
                    </p>
                    <div id="actionNotice" class="notice"></div>
                    <p>本轮结束自动停止。</p>
                    </div>
                    <div class="rebalance-section">
                    <div class="section-title">
                      <h3>BTC 再平衡配置</h3>
                      <small id="rebalanceConfigPath">-</small>
                    </div>
                    <div class="grid" id="rebalanceForm"></div>
                    <div class="summary">
                      <div class="metric"><span>当前模式</span><strong id="rebalanceMode">-</strong></div>
                      <div class="metric"><span>再平衡状态</span><strong id="rebalanceStatus">-</strong></div>
                      <div class="metric"><span>Cycle 锁</span><strong id="rebalanceCycleLock">-</strong></div>
                      <div class="metric"><span>当前价格</span><strong id="rebalanceCurrentPrice">-</strong></div>
                      <div class="metric"><span>当前 / 目标 BTC 占比</span><strong id="rebalanceRatio">-</strong></div>
                      <div class="metric"><span>现货 BTC</span><strong id="rebalanceSpotQty">-</strong></div>
                      <div class="metric"><span>合约 BTC 仓位</span><strong id="rebalanceContractQty">-</strong></div>
                      <div class="metric"><span>净 BTC 敞口</span><strong id="rebalanceNetQty">-</strong></div>
                      <div class="metric"><span>总权益 USDT</span><strong id="rebalanceEquity">-</strong></div>
                    </div>
                    <p>
                      <button id="saveRebalanceButton" class="start" onclick="saveRebalanceConfig()">保存再平衡配置</button>
                      <button id="startRebalanceButton" class="start" onclick="postRebalance('/api/rebalance-start')">启动再平衡</button>
                      <button id="stopRebalanceButton" class="stop" onclick="postRebalance('/api/rebalance-stop')">停止再平衡</button>
                    </p>
                    <div id="rebalanceActionBanner" class="action-banner"></div>
                    <div id="rebalanceNotice" class="notice"></div>
                    <h3>再平衡挂单</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>状态</th><th>标的</th><th>方向</th><th>类型</th><th>数量</th><th>价格</th><th>较上笔</th><th>减仓</th><th>客户端订单ID</th><th>交易所订单ID</th><th>操作</th></tr></thead>
                        <tbody id="rebalanceOrders"><tr><td colspan="11" class="muted">暂无再平衡挂单</td></tr></tbody>
                      </table>
                    </div>
                    <h3>当前再平衡 Cycle</h3>
                    <div class="summary">
                      <div class="metric"><span>市场价盈亏</span><strong id="rebalanceCyclePnl">-</strong></div>
                      <div class="metric"><span>开单方向</span><strong id="rebalanceCycleDirection">-</strong></div>
                      <div class="metric"><span>启动比例</span><strong id="rebalanceCycleStartRatio">-</strong></div>
                      <div class="metric"><span>当前比例</span><strong id="rebalanceCycleCurrentRatio">-</strong></div>
                      <div class="metric"><span>启动价格</span><strong id="rebalanceCycleStartPrice">-</strong></div>
                      <div class="metric"><span>当前价格</span><strong id="rebalanceCycleMarketPrice">-</strong></div>
                      <div class="metric"><span>当前价相对启动价</span><strong id="rebalanceCycleStartPriceChange">-</strong></div>
                      <div class="metric"><span>当前总资产（BTC）</span><strong id="rebalanceCycleTotalEquityBtc">-</strong></div>
                      <div class="metric"><span>成本价格</span><strong id="rebalanceCycleCostPrice">-</strong></div>
                      <div class="metric"><span>成本价相对当前价</span><strong id="rebalanceCycleCostPriceChange">-</strong></div>
                      <div class="metric"><span>预计下跌至 100% 库存价格</span><strong id="rebalanceCycleFullInventoryPrice">-</strong></div>
                      <div class="metric"><span>买入 / 卖出次数</span><strong id="rebalanceCycleCounts">-</strong></div>
                      <div class="metric"><span>买入 / 卖出数量</span><strong id="rebalanceCycleQty">-</strong></div>
                      <div class="metric"><span>持续时长</span><strong id="rebalanceCycleDuration">-</strong></div>
                      <div class="metric"><span>累计净数量</span><strong id="rebalanceCycleCumQty">-</strong></div>
                      <div class="metric"><span>累计交易手续费</span><strong id="rebalanceCycleTradingFee">-</strong></div>
                      <div class="metric"><span>累计资金费</span><strong id="rebalanceCycleFundingFee">-</strong></div>
                      <div class="metric"><span>累计净费用</span><strong id="rebalanceCycleFee">-</strong></div>
                    </div>
                    <h3>再平衡资金费记录</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>资金费</th><th>币种</th><th>价格</th><th>累计资金费</th><th>累计净费用</th><th>市场价盈亏</th><th>执行ID</th></tr></thead>
                        <tbody id="rebalanceFundingFees"><tr><td colspan="9" class="muted">当前 Cycle 暂无资金费记录</td></tr></tbody>
                      </table>
                    </div>
                    <h3>再平衡成交记录</h3>
                    <div id="rebalanceFillsPager" class="pager"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>订单ID</th></tr></thead>
                        <tbody id="rebalanceFills"><tr><td colspan="14" class="muted">暂无再平衡成交</td></tr></tbody>
                      </table>
                    </div>
                    <h3>再平衡 Cycle 历史</h3>
                    <div id="rebalanceCyclesPager" class="pager"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>结束时间</th><th>标的</th><th>方向</th><th>启动价</th><th>结束价</th><th>启动比例</th><th>结束比例</th><th>目标比例</th><th>买入/卖出次数</th><th>耗时</th><th>结束方式</th><th>总盈亏</th><th>订单ID</th><th>执行ID</th><th>操作</th></tr></thead>
                        <tbody id="rebalanceCycles"><tr><td colspan="15" class="muted">暂无再平衡 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    <h3>再平衡 Cycle 明细</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>订单ID</th></tr></thead>
                        <tbody id="rebalanceCycleDetail"><tr><td colspan="14" class="muted">请选择一个再平衡 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    </div>
                    <div class="manual-section">
                    <h3>当前挂单</h3>
                    <div id="orderNotice" class="notice"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>状态</th><th>关系</th><th>标的</th><th>方向</th><th>类型</th><th>数量</th><th>价格</th><th>减仓</th><th>客户端订单ID</th><th>交易所订单ID</th><th>操作</th></tr></thead>
                        <tbody id="orders"><tr><td colspan="11" class="muted">暂无挂单</td></tr></tbody>
                      </table>
                    </div>
                    <h3>当前 Cycle</h3>
                    <div class="summary">
                      <div class="metric"><span>市场价盈亏</span><strong id="cycleMarkPricePnl">-</strong></div>
                      <div class="metric"><span>开单方向</span><strong id="cycleEntryDirection">-</strong></div>
                      <div class="metric"><span>启动价格</span><strong id="cycleStartPrice">-</strong></div>
                      <div class="metric"><span>当前价格</span><strong id="cycleMarketPrice">-</strong></div>
                      <div class="metric"><span>买入 / 卖出次数</span><strong id="cycleCounts">-</strong></div>
                      <div class="metric"><span>买入 / 卖出数量</span><strong id="cycleQty">-</strong></div>
                      <div class="metric"><span>持续时长</span><strong id="cycleDuration">-</strong></div>
                      <div class="metric"><span>开始 / 最近成交</span><strong id="cycleTimes">-</strong></div>
                      <div class="metric"><span>累计净数量</span><strong id="cycleCumQty">-</strong></div>
                      <div class="metric"><span>累计交易手续费</span><strong id="cycleTradingFee">-</strong></div>
                      <div class="metric"><span>累计资金费</span><strong><button id="cycleFundingFee" class="metric-button" data-action="funding-detail">-</button></strong></div>
                      <div class="metric"><span>累计净费用</span><strong id="cycleFee">-</strong></div>
                      <div class="metric"><span>盈利减仓</span><strong id="cPRQ">-</strong></div>
                      <div class="metric"><span>额外减仓</span><strong id="cPBE">-</strong></div>
                    </div>
                    <h3>成交记录</h3>
                    <small id="pnlFormula">盈亏 = cumQty * 市场价 - cumValue - 净费用。</small>
                    <div class="pager">
                      <label>最低价 <input id="fillPriceMin" style="width:100px" placeholder="如 69.8"></label>
                      <label>最高价 <input id="fillPriceMax" style="width:100px" placeholder="如 70.2"></label>
                      <button class="compact" onclick="applyFillPriceFilter()">查询</button>
                      <button class="compact" onclick="clearFillPriceFilter()">清空</button>
                    </div>
                    <div class="pager">
                      <button class="compact" onclick="previousFillsPage()">上一页</button>
                      <span id="fillsPager">-</span>
                      <button class="compact" onclick="nextFillsPage()">下一页</button>
                    </div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>仓位均价</th><th>订单ID</th></tr></thead>
                        <tbody id="fills"><tr><td colspan="15">暂无成交</td></tr></tbody>
                      </table>
                    </div>
                    <h3>Cycle 历史</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>结束时间</th><th>标的</th><th>最后成交价</th><th>总盈亏</th><th>订单ID</th><th>执行ID</th><th>操作</th></tr></thead>
                        <tbody id="cycles"><tr><td colspan="7">暂无 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    <h3>Cycle 成交明细</h3>
                    <div id="cycleDetailNotice" class="notice"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>仓位均价</th><th>订单ID</th></tr></thead>
                        <tbody id="cycleDetail"><tr><td colspan="14">请选择一个历史 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    </div>
                  </div>
                """.concat("""
                  <script>
                    const pageMode = location.pathname === '/rebalance' ? 'rebalance' : 'grid';
                    function applyPageMode() {
                      document.title = pageMode === 'rebalance' ? 'BTC 再平衡控制台' : '手动网格控制台';
                      document.querySelectorAll('.manual-section').forEach(el => el.style.display = pageMode === 'grid' ? '' : 'none');
                      document.querySelectorAll('.rebalance-section').forEach(el => el.style.display = pageMode === 'rebalance' ? '' : 'none');
                      document.querySelectorAll('.nav a').forEach(a => {
                        const active = (pageMode === 'grid' && a.getAttribute('href') === '/grid') || (pageMode === 'rebalance' && a.getAttribute('href') === '/rebalance');
                        a.className = active ? 'active' : '';
                      });
                    }
                    const fields = {
                      category:'linear', symbol:'BTCUSDT', entryDirection:'long', baseGridSpacing:'0.0016', gridRequoteShrinkFactor:'0.85', baseGridQty:'0.005',
                      maxInitialSteps:'5', maxOrderQty:'0.20', maxInventoryQty:'0.15', maxDirectionalInventoryQty:'0.15',
                      emaLength4h:'20', bollLength4h:'20', bollStdDevMultiplier:'2.0',
                      pairOrderTimeoutMinutes:'15', triggerCooldownMinutes:'5'
                    };
                    const labels = {
                      category:'交易类型',
                      symbol:'交易标的',
                      entryDirection:'开单方向',
                      baseGridSpacing:'基础网格间距',
                      gridRequoteShrinkFactor:'重挂间距收缩系数',
                      baseGridQty:'基础下单数量',
                      maxInitialSteps:'开单步数',
                      maxOrderQty:'单笔最大数量',
                      maxInventoryQty:'总持仓上限',
                      maxDirectionalInventoryQty:'单方向持仓上限',
                      emaLength4h:'4小时 EMA 周期',
                      bollLength4h:'4小时布林周期',
                      bollStdDevMultiplier:'布林标准差倍数',
                      pairOrderTimeoutMinutes:'挂单超时分钟',
                      triggerCooldownMinutes:'开单冷却分钟'
                    };
                    const directionOptions = {
                      both:'多空',
                      long:'做多',
                      short:'做空',
                      spot_long_only:'现货只做多'
                    };
                    const categoryOptions = {
                      linear:'U本位合约',
                      spot:'现货'
                    };
                    const rebalanceFields = {
                      enabled:false, mode:'price_target', autoMinTotalBtc:'7', autoBaseGridQty:'0.005', autoInitialSteps:'5',
                      category:'linear', symbol:'BTCUSDT', accountType:'UNIFIED', baseCoin:'BTC', quoteCoin:'USDT',
                      priceTargets:'0:0.80,60000:0.75,65000:0.70,100000:0.50', ratioThreshold:'0.02',
                      baseGridSpacing:'0.0016', gridRequoteShrinkFactor:'0.85', baseGridQty:'0.001', maxInitialSteps:'5',
                      maxOrderQty:'0.001', maxInventoryQty:'0.15', priceOffsetRatio:'0.001', bandHysteresisRatio:'0.001',
                      orderTtlMinutes:'15', confirmationSamples:'3'
                    };
                    const rebalanceLabels = {
                      enabled:'启用再平衡',
                      mode:'运行模式',
                      autoMinTotalBtc:'自动启动最低总资产（BTC）',
                      autoBaseGridQty:'自动模式单格数量（BTC）',
                      autoInitialSteps:'自动模式初始格数',
                      category:'交易类型',
                      symbol:'交易标的',
                      accountType:'账户类型',
                      baseCoin:'基础币',
                      quoteCoin:'计价币',
                      priceTargets:'价格档位目标',
                      ratioThreshold:'触发偏差',
                      baseGridSpacing:'基础网格间距',
                      gridRequoteShrinkFactor:'重挂间距收缩系数',
                      baseGridQty:'基础下单数量',
                      maxInitialSteps:'最大初始步数',
                      maxOrderQty:'单次最大数量',
                      maxInventoryQty:'最大持仓数量',
                      priceOffsetRatio:'挂单价格偏移',
                      bandHysteresisRatio:'价格档位滞回',
                      orderTtlMinutes:'挂单超时分钟',
                      confirmationSamples:'资产确认次数'
                    };
                    const fillsPageSize = 50;
                    let fillsPage = 0;
                    const rebalancePageSize = 50;
                    let rebalanceFillsOffset = 0;
                    let rebalanceCyclesOffset = 0;
                    let latestFills = [];
                    let rebalanceDirty = false;
                    let savedRebalanceMode = 'price_target';
                    let latestRebalanceConfig = null;
                    const form = document.getElementById('form');
                    Object.entries(fields).forEach(([k,v]) => {
                      const control = k === 'entryDirection'
                        ? `<select id="${k}">${Object.entries(directionOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                        : k === 'category'
                          ? `<select id="${k}">${Object.entries(categoryOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                          : `<input id="${k}" value="${v}">`;
                      form.insertAdjacentHTML('beforeend', `<label>${labels[k] || k}${control}<small>${k}</small></label>`);
                    });
                    const rebalanceForm = document.getElementById('rebalanceForm');
                    Object.entries(rebalanceFields).forEach(([k,v]) => {
                      const control = k === 'enabled'
                        ? `<div class="toggle-row"><input id="rebalance_${k}" type="checkbox"><span>开启</span></div>`
                        : k === 'mode'
                          ? `<select id="rebalance_${k}"><option value="price_target">价格目标再平衡</option><option value="auto_cycle">自动循环网格</option></select>`
                        : k === 'category'
                          ? `<select id="rebalance_${k}">${Object.entries(categoryOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                          : `<input id="rebalance_${k}" value="${v}">`;
                      const help = k === 'priceTargets'
                        ? '格式：价格下限:目标BTC占比，例如 0:0.70,80000:0.60,100000:0.50'
                        : k === 'autoMinTotalBtc'
                          ? '仅空闲且总资产折合 BTC 严格大于此值时启动'
                        : k === 'autoBaseGridQty'
                          ? '自动模式后续网格也沿用此单格数量'
                        : k === 'autoInitialSteps'
                          ? '首笔买入量 = 单格数量 × 初始格数'
                        : `rebalance.${k}`;
                      rebalanceForm.insertAdjacentHTML('beforeend', `<label>${rebalanceLabels[k] || k}${control}<small>${help}</small></label>`);
                    });
                    rebalanceForm.addEventListener('input', () => { rebalanceDirty = true; });
                    rebalanceForm.addEventListener('change', () => { rebalanceDirty = true; });
                    document.getElementById('rebalance_mode').addEventListener('change', updateRebalanceModeUi);
                    function updateRebalanceModeUi() {
                      const auto = document.getElementById('rebalance_mode').value === 'auto_cycle';
                      ['autoMinTotalBtc', 'autoBaseGridQty', 'autoInitialSteps'].forEach(k => {
                        document.getElementById(`rebalance_${k}`).disabled = !auto;
                      });
                      ['priceTargets', 'ratioThreshold', 'bandHysteresisRatio'].forEach(k => {
                        document.getElementById(`rebalance_${k}`).disabled = auto;
                      });
                      setText('rebalanceMode', auto ? '自动循环网格' : '价格目标再平衡');
                    }
                    function payload() {
                      const out = {};
                      Object.keys(fields).forEach(k => out[k] = document.getElementById(k).value);
                      return out;
                    }
                    function applyConfig(config) {
                      Object.keys(fields).forEach(k => {
                        const input = document.getElementById(k);
                        if (input && config && config[k] !== undefined && config[k] !== null) input.value = config[k];
                      });
                    }
                    function rebalancePayload() {
                      const out = {};
                      Object.keys(rebalanceFields).forEach(k => {
                        const input = document.getElementById(`rebalance_${k}`);
                        out[k] = input.type === 'checkbox' ? input.checked : input.value;
                      });
                      return out;
                    }
                    function applyRebalanceConfig(config) {
                      if (rebalanceDirty || rebalanceForm.contains(document.activeElement)) return;
                      latestRebalanceConfig = config || latestRebalanceConfig;
                      Object.keys(rebalanceFields).forEach(k => {
                        const input = document.getElementById(`rebalance_${k}`);
                        if (!input || !config || config[k] === undefined || config[k] === null) return;
                        if (input.type === 'checkbox') input.checked = Boolean(config[k]);
                        else input.value = config[k];
                      });
                      savedRebalanceMode = config && config.mode ? config.mode : 'price_target';
                      updateRebalanceModeUi();
                      const path = document.getElementById('rebalanceConfigPath');
                      if (path) path.textContent = config && config.configFilePath ? `保存到 ${config.configFilePath}` : '-';
                    }
                    async function saveRebalanceConfig() {
                      const nextMode = document.getElementById('rebalance_mode').value;
                      if (nextMode !== savedRebalanceMode) {
                        const label = nextMode === 'auto_cycle' ? '自动循环网格' : '价格目标再平衡';
                        if (!window.confirm(`确认切换为“${label}”？运行中的 Cycle 不允许切换，原模式配置会保留。`)) return;
                      }
                      setBusy(true, 'save');
                      showRebalanceResult('info', '保存中...');
                      showRebalanceBanner('info', '保存中...');
                      try {
                        const r = await fetch('/api/rebalance-config', {method:'POST', body: JSON.stringify(rebalancePayload()), headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        if (result.ok) rebalanceDirty = false;
                        if (result.config) {
                          const active = document.activeElement;
                          if (active && rebalanceForm.contains(active)) active.blur();
                          applyRebalanceConfig(result.config);
                        }
                        showRebalanceResult(result.ok ? 'ok' : 'error', result.message || (result.ok ? '保存成功' : '保存失败'));
                        showRebalanceBanner(result.ok ? 'ok' : 'error', result.ok ? '保存完成' : '保存失败');
                      } catch (err) {
                        const message = err && err.message ? err.message : '保存失败';
                        showRebalanceResult('error', message);
                        showRebalanceBanner('error', '保存失败');
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    async function postRebalance(url) {
                      const action = url.includes('stop') ? 'stop' : 'start';
                      const actionText = action === 'stop' ? '停止再平衡' : '启动再平衡';
                      setBusy(true, action);
                      showRebalanceResult('info', `${actionText}请求处理中...`);
                      showRebalanceBanner('info', `正在${actionText}...`);
                      try {
                        const r = await fetch(url, {method:'POST', body: '{}', headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        const message = result.message || (result.ok ? '操作成功' : '操作失败');
                        showRebalanceResult(result.ok ? 'ok' : 'error', message);
                        showRebalanceBanner(result.ok ? 'ok' : 'error', result.ok ? `${actionText}完成` : `${actionText}失败`);
                      } catch (err) {
                        const message = err && err.message ? err.message : '请求失败';
                        showRebalanceResult('error', message);
                        showRebalanceBanner('error', `${actionText}失败`);
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    async function post(url, body) {
                      setBusy(true);
                      showActionResult('info', '请求处理中...');
                      try {
                        const r = await fetch(url, {method:'POST', body: JSON.stringify(body || {}), headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        showActionResult(result.ok ? 'ok' : 'error', result.message || (result.ok ? '操作成功' : '操作失败'));
                      } catch (err) {
                        showActionResult('error', err && err.message ? err.message : '请求失败');
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    function start() { post('/api/start', payload()); }
                    function cancelOrder(clientOrderId, symbol) {
                      post('/api/cancel-order', {clientOrderId, symbol});
                    }
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cancel-order"]');
                      if (!button) return;
                      cancelOrder(button.dataset.clientOrderId, button.dataset.symbol);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cycle-detail"]');
                      if (!button) return;
                      loadCycleDetail(button.dataset.executionId, button.dataset.label);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cycle-load-config"]');
                      if (!button) return;
                      loadCycleConfig(button.dataset.executionId, button.dataset.label);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-cycle-detail"]');
                      if (!button) return;
                      loadRebalanceCycleDetail(button.dataset.executionId);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-fills-page"]');
                      if (!button) return;
                      rebalanceFillsOffset = Math.max(0, rebalanceFillsOffset + Number(button.dataset.delta || 0) * rebalancePageSize);
                      refresh();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-cycles-page"]');
                      if (!button) return;
                      rebalanceCyclesOffset = Math.max(0, rebalanceCyclesOffset + Number(button.dataset.delta || 0) * rebalancePageSize);
                      refresh();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="funding-detail"]');
                      if (!button) return;
                      renderFundingDetail();
                    });
                    function setBusy(busy, action) {
                      ['startButton', 'resumeButton', 'stopButton', 'flattenButton', 'saveRebalanceButton', 'startRebalanceButton', 'stopRebalanceButton'].forEach(id => {
                        const button = document.getElementById(id);
                        if (button) button.dataset.busy = busy ? 'true' : 'false';
                        if (button && (id === 'saveRebalanceButton' || id === 'startRebalanceButton' || id === 'stopRebalanceButton')) button.disabled = busy;
                      });
                      setRebalanceButtonText(busy ? action : null);
                      document.querySelectorAll('[data-action="cancel-order"]').forEach(button => button.disabled = busy);
                      document.body.dataset.busy = busy ? 'true' : 'false';
                    }
                    function setRebalanceButtonText(action) {
                      const labels = {
                        saveRebalanceButton: action === 'save' ? '保存中...' : '保存再平衡配置',
                        startRebalanceButton: action === 'start' ? '启动中...' : '启动再平衡',
                        stopRebalanceButton: action === 'stop' ? '停止中...' : '停止再平衡'
                      };
                      Object.entries(labels).forEach(([id, text]) => {
                        const button = document.getElementById(id);
                        if (button) button.textContent = text;
                      });
                    }
                    function showActionResult(type, message) {
                      const notice = document.getElementById('actionNotice');
                      notice.className = `notice notice-${type}`;
                      notice.textContent = message;
                    }
                    function showRebalanceResult(type, message) {
                      const notice = document.getElementById('rebalanceNotice');
                      notice.className = `notice notice-${type}`;
                      notice.textContent = message;
                    }
                    function showRebalanceBanner(type, title) {
                      const banner = document.getElementById('rebalanceActionBanner');
                      if (!banner) return;
                      banner.className = `action-banner active action-banner-${type}`;
                      banner.textContent = title || '';
                    }
                    async function refresh() {
                      return pageMode === 'rebalance' ? refreshRebalance() : refreshGrid();
                    }
                    async function refreshRebalance() {
                      const [control, rebalanceConfig, rebalanceState, rebalanceOrders, rebalanceFills, rebalanceFundingFees, rebalanceCycles, rebalanceCurrentCycle] = await Promise.all([
                        fetch('/api/control').then(r=>r.json()),
                        fetch('/api/rebalance-config').then(r=>r.json()),
                        fetch('/api/rebalance-state').then(r=>r.json()),
                        fetch('/api/rebalance-orders').then(r=>r.json()),
                        fetch(`/api/rebalance-fills?limit=${rebalancePageSize}&offset=${rebalanceFillsOffset}`).then(r=>r.json()),
                        fetch('/api/rebalance-funding-fees').then(r=>r.json()),
                        fetch(`/api/rebalance-cycles?limit=${rebalancePageSize}&offset=${rebalanceCyclesOffset}`).then(r=>r.json()),
                        fetch('/api/rebalance-current-cycle').then(r=>r.json())
                      ]);
                      const rebalanceFillItems = rebalanceFills && rebalanceFills.items ? rebalanceFills.items : [];
                      const rebalanceCycleItems = rebalanceCycles && rebalanceCycles.items ? rebalanceCycles.items : [];
                      if (rebalanceConfig && rebalanceConfig.config) applyRebalanceConfig(rebalanceConfig.config);
                      renderRebalanceState(rebalanceState && rebalanceState.state ? rebalanceState.state : null, control);
                      renderRebalanceOrders((rebalanceOrders || []).filter(isActiveOrder), rebalanceCurrentCycle);
                      renderRebalanceCurrentCycle(rebalanceCurrentCycle, rebalanceState && rebalanceState.state ? rebalanceState.state : null);
                      renderRebalanceFundingFees(rebalanceFundingFees || []);
                      renderSimpleFills('rebalanceFills', rebalanceFillItems, true);
                      renderRebalancePager('rebalanceFillsPager', rebalanceFills, rebalanceFillsOffset, 'rebalance-fills-page');
                      renderRebalanceCycles(rebalanceCycleItems);
                      renderRebalancePager('rebalanceCyclesPager', rebalanceCycles, rebalanceCyclesOffset, 'rebalance-cycles-page');
                    }
                    async function refreshGrid() {
                      const [control, status, fills, cycles, currentCycle] = await Promise.all([
                        fetch('/api/control').then(r=>r.json()),
                        fetch('/status').then(r=>r.json()),
                        fetch('/api/fills').then(r=>r.json()),
                        fetch('/api/cycles').then(r=>r.json()),
                        fetch('/api/current-cycle').then(r=>r.json())
                      ]);
                      const activeOrders = (status.workingOrders || []).filter(isActiveOrder);
                      renderSummary(control, status, activeOrders);
                      renderOrders(activeOrders, status);
                      renderCurrentCycle(currentCycle);
                      renderFills(fills);
                      renderCycles(cycles);
                    }
                    function renderRebalanceState(state, control) {
                      if (!state) {
                        setText('rebalanceStatus', '-');
                        setText('rebalanceCycleLock', '-');
                        setText('rebalanceCurrentPrice', '-');
                        setText('rebalanceRatio', '-');
                        setText('rebalanceSpotQty', '-');
                        setText('rebalanceContractQty', '-');
                        setText('rebalanceNetQty', '-');
                        setText('rebalanceEquity', '-');
                        return;
                      }
                      const monitor = control && control.rebalanceRunning ? '监控运行中' : '监控未运行';
                      setText('rebalanceStatus', `${monitor} / ${state.status || '-'}${state.message ? ' / ' + state.message : ''}`);
                      setText('rebalanceCycleLock', state.status === 'GRID_ACTIVE' ? '已锁定，禁止重复开单' : '未锁定');
                      setText('rebalanceCurrentPrice', formatNumber(state.lastPrice));
                      setText('rebalanceRatio', `${formatPercent(state.currentRatio)} / ${formatPercent(state.targetRatio)}`);
                      setText('rebalanceSpotQty', formatNumber(state.baseBalance));
                      setText('rebalanceContractQty', formatNumber(state.contractPositionQty));
                      setText('rebalanceNetQty', formatNumber(state.netBaseQty));
                      setText('rebalanceEquity', formatNumber(state.totalEquityUsdt));
                    }
                    function renderRebalanceOrders(orders, cycle) {
                      const tbody = document.getElementById('rebalanceOrders');
                      if (!tbody) return;
                      if (!orders || orders.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="11" class="muted">暂无再平衡挂单</td></tr>';
                        return;
                      }
                      const latestFillPrice = Number(cycle && cycle.latestFillPrice);
                      tbody.innerHTML = orders.map(order => `
                        <tr>
                          <td>${esc(order.status)}</td>
                          <td>${esc(order.symbol)}</td>
                          <td>${formatSide(order.side)}</td>
                          <td>${esc(order.orderType)}</td>
                          <td>${esc(order.qty)}</td>
                          <td>${esc(order.price ?? '市价')}</td>
                          <td>${formatOrderPriceDiff(order, latestFillPrice)}</td>
                          <td>${order.reduceOnly ? '是' : '否'}</td>
                          <td>${esc(order.clientOrderId)}</td>
                          <td>${esc(order.exchangeOrderId || '-')}</td>
                          <td><button class="danger compact" data-action="cancel-order" data-client-order-id="${esc(order.clientOrderId)}" data-symbol="${esc(order.symbol)}">撤单</button></td>
                        </tr>
                      `).join('');
                    }
                    function renderRebalanceCurrentCycle(cycle, state) {
                      if (!cycle || !cycle.startedAt) {
                        setText('rebalanceCyclePnl', '-');
                        setText('rebalanceCycleDirection', '-');
                        setText('rebalanceCycleStartRatio', '-');
                        setText('rebalanceCycleCurrentRatio', '-');
                        setText('rebalanceCycleStartPrice', '-');
                        setText('rebalanceCycleMarketPrice', '-');
                        setText('rebalanceCycleStartPriceChange', '-');
                        setText('rebalanceCycleTotalEquityBtc', '-');
                        setText('rebalanceCycleCostPrice', '-');
                        setText('rebalanceCycleCostPriceChange', '-');
                        setText('rebalanceCycleFullInventoryPrice', '-');
                        setText('rebalanceCycleCounts', '-');
                        setText('rebalanceCycleQty', '-');
                        setText('rebalanceCycleDuration', '-');
                        setText('rebalanceCycleCumQty', '-');
                        setText('rebalanceCycleTradingFee', '-');
                        setText('rebalanceCycleFundingFee', '-');
                        setText('rebalanceCycleFee', '-');
                        return;
                      }
                      setHtml('rebalanceCyclePnl', formatPnl(cycle.markPricePnl));
                      setText('rebalanceCycleDirection', formatEntryDirection(cycle.entryDirection));
                      setText('rebalanceCycleStartRatio', formatPercent(cycle.startRatio));
                      setText('rebalanceCycleCurrentRatio', formatPercent(state && state.currentRatio));
                      setText('rebalanceCycleStartPrice', formatNumber(cycle.startPrice));
                      setText('rebalanceCycleMarketPrice', formatNumber(cycle.marketPrice));
                      const equityUsdt = Number(state && state.totalEquityUsdt);
                      const btcPrice = Number(cycle.marketPrice || (state && state.lastPrice));
                      const startPrice = Number(cycle.startPrice);
                      const cycleCostPrice = costPrice(cycle);
                      const netBtcQty = Number(state && state.netBaseQty);
                      setText('rebalanceCycleStartPriceChange', Number.isFinite(btcPrice) && Number.isFinite(startPrice) && startPrice > 0
                        ? formatPercent(btcPrice / startPrice - 1)
                        : '-');
                      setText('rebalanceCycleTotalEquityBtc', Number.isFinite(equityUsdt) && Number.isFinite(btcPrice) && btcPrice > 0
                        ? formatNumber(equityUsdt / btcPrice)
                        : '-');
                      setText('rebalanceCycleCostPrice', formatNumber(cycleCostPrice));
                      setText('rebalanceCycleCostPriceChange', Number.isFinite(cycleCostPrice) && Number.isFinite(btcPrice) && btcPrice > 0
                        ? formatPercent(cycleCostPrice / btcPrice - 1)
                        : '-');
                      setText('rebalanceCycleFullInventoryPrice', Number.isFinite(equityUsdt) && Number.isFinite(netBtcQty) && netBtcQty > 0
                        ? formatPrice2(estimateFullInventoryPrice(state, btcPrice))
                        : '-');
                      setText('rebalanceCycleCounts', `${cycle.buyCount || 0} / ${cycle.sellCount || 0}，共 ${cycle.fillCount || 0}`);
                      setText('rebalanceCycleQty', `${formatNumber(cycle.buyQty)} / ${formatNumber(cycle.sellQty)}`);
                      setText('rebalanceCycleDuration', formatDuration(cycle.durationSeconds));
                      setText('rebalanceCycleCumQty', formatNumber(cycle.cumulativeQty));
                      setText('rebalanceCycleTradingFee', formatNumber(cycle.cumulativeTradingFee));
                      setText('rebalanceCycleFundingFee', formatNumber(cycle.cumulativeFundingFee));
                      setText('rebalanceCycleFee', formatNumber(cycle.cumulativeFee));
                    }
                    function renderSimpleFills(tbodyId, fills, newestFirst = false) {
                      const tbody = document.getElementById(tbodyId);
                      if (!tbody) return;
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="14" class="muted">暂无记录</td></tr>';
                        return;
                      }
                      const priceDiffs = priceDiffMap(fills);
                      const cumulativeTurnover = cumulativeTurnoverMap(fills);
                      const rows = newestFirst ? fills : [...fills].reverse();
                      tbody.innerHTML = rows.slice(0, 200).map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(displaySymbol(f.symbol))}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(formatNumber(f.qty))}</td>
                          <td>${esc(formatNumber(f.price))}</td>
                          <td>${formatPriceDiff(priceDiffs.get(fillKey(f)))}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(cumulativeTurnover.get(fillKey(f))))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function renderRebalanceFundingFees(rows) {
                      const tbody = document.getElementById('rebalanceFundingFees');
                      if (!tbody) return;
                      if (!rows || rows.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="9" class="muted">当前 Cycle 暂无资金费记录</td></tr>';
                        return;
                      }
                      const cumulativeFunding = new Map();
                      let sum = 0;
                      [...rows].reverse().forEach(row => {
                        sum += Number(row.fee || 0);
                        cumulativeFunding.set(fillKey(row), sum);
                      });
                      tbody.innerHTML = rows.map(row => `
                        <tr>
                          <td>${formatTime(row.time)}</td>
                          <td>${esc(displaySymbol(row.symbol))}</td>
                          <td>${esc(formatNumber(row.fee))}</td>
                          <td>${esc(row.feeCurrency || '')}</td>
                          <td>${esc(formatNumber(row.price))}</td>
                          <td>${esc(formatNumber(cumulativeFunding.get(fillKey(row))))}</td>
                          <td>${esc(formatNumber(row.cumulativeFee))}</td>
                          <td>${formatPnl(row.totalPnlAtFill)}</td>
                          <td>${esc(row.executionId || '')}</td>
                        </tr>
                      `).join('');
                    }
                    function renderRebalanceCycles(cycles) {
                      const tbody = document.getElementById('rebalanceCycles');
                      if (!tbody) return;
                      if (!cycles || cycles.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="15" class="muted">暂无再平衡 Cycle</td></tr>';
                        return;
                      }
                      tbody.innerHTML = cycles.slice(0, 100).map(c => `
                        <tr>
                          <td>${formatTime(c.time)}</td>
                          <td>${esc(displaySymbol(c.symbol))}</td>
                          <td>${formatEntryDirection(c.entryDirection)}</td>
                          <td>${esc(formatNumber(c.startPrice))}</td>
                          <td>${esc(formatNumber(c.price))}</td>
                          <td>${formatPercent(c.startRatio)}</td>
                          <td>${formatPercent(c.endRatio)}</td>
                          <td>${formatPercent(c.targetRatio)}</td>
                          <td>${formatCycleCounts(c)}</td>
                          <td>${formatDuration(c.durationSeconds)}</td>
                          <td>${esc(c.endReasonLabel || c.endReason || '')}</td>
                          <td>${formatPnl(c.totalPnlAtFill)}</td>
                          <td>${esc(c.clientOrderId)}</td>
                          <td>${esc(c.executionId)}</td>
                          <td><button class="compact" data-action="rebalance-cycle-detail" data-execution-id="${esc(c.executionId)}">明细</button></td>
                        </tr>
                      `).join('');
                    }
                    function renderRebalancePager(elementId, page, offset, action) {
                      const el = document.getElementById(elementId);
                      if (!el) return;
                      const limit = Number(page && page.limit ? page.limit : rebalancePageSize);
                      const count = page && page.items ? page.items.length : 0;
                      const start = count === 0 ? 0 : offset + 1;
                      const end = offset + count;
                      const hasPrev = offset > 0;
                      const hasNext = Boolean(page && page.hasMore);
                      el.innerHTML = `
                        <button class="compact" data-action="${action}" data-delta="-1" ${hasPrev ? '' : 'disabled'}>上一页</button>
                        <span class="muted">第 ${Math.floor(offset / limit) + 1} 页，${start}-${end}</span>
                        <button class="compact" data-action="${action}" data-delta="1" ${hasNext ? '' : 'disabled'}>下一页</button>
                      `;
                    }
                    function renderSummary(control, status, activeOrders) {
                      const running = Boolean(control && control.running);
                      const flattenStop = control && control.flattenStop ? control.flattenStop : {};
                      const flattenRunning = Boolean(flattenStop.running);
                      const state = status && status.runtimeState ? status.runtimeState.state : '-';
                      const activeSymbol = (control && control.config && control.config.symbol) || (status && status.symbol) || '-';
                      const snapshot = status && status.latestMarketSnapshot ? status.latestMarketSnapshot : {};
                      const orders = status && status.workingOrders ? status.workingOrders : [];
                      const lastOrder = orders.length ? orders[orders.length - 1] : null;
                      const badge = document.getElementById('runBadge');
                      badge.textContent = running ? '运行中' : '未运行';
                      badge.className = `badge ${running ? 'badge-running' : 'badge-idle'}`;
                      document.getElementById('activeSymbol').textContent = activeSymbol;
                      document.getElementById('cycleStatus').textContent = cycleLabel(control, status);
                      document.getElementById('strategyState').textContent = state || '-';
                      document.getElementById('operationStatus').textContent = formatOperationStatus(flattenStop);
                      document.getElementById('lastPrice').textContent = snapshot.markPrice == null
                        ? (snapshot.lastPrice ?? '-')
                        : `${snapshot.lastPrice ?? '-'} / ${snapshot.markPrice}`;
                      document.getElementById('orderCount').textContent = activeOrders.length;
                      document.getElementById('lastOrderStatus').textContent = lastOrder
                        ? `${formatSide(lastOrder.side)} ${lastOrder.orderType} ${lastOrder.status}`
                        : '-';
                      document.getElementById('startButton').disabled = running;
                      document.getElementById('resumeButton').disabled = running || !Boolean(control && control.canResume);
                      document.getElementById('stopButton').disabled = !running || flattenRunning;
                      document.getElementById('flattenButton').disabled = !running || flattenRunning;
                      if (document.body.dataset.busy === 'true') {
                        document.getElementById('startButton').disabled = true;
                        document.getElementById('resumeButton').disabled = true;
                        document.getElementById('stopButton').disabled = true;
                        document.getElementById('flattenButton').disabled = true;
                      }
                    }
                    function cycleLabel(control, status) {
                      if (control && control.flattenStop && control.flattenStop.running) return '平仓停止中';
                      if (!control || !control.running) return '未运行';
                      const pair = status && status.runtimeState ? status.runtimeState.activeOrderPair : null;
                      if (pair) return '网格挂单中';
                      if (status && status.runtimeState && status.runtimeState.state === 'OPENING_FIRST_POSITION') return '开仓中';
                      return control.stopAfterCycle ? '本轮结束后停止' : '连续运行';
                    }
                    function formatOperationStatus(flattenStop) {
                      if (!flattenStop || !flattenStop.state || flattenStop.state === 'IDLE') return '-';
                      if (flattenStop.state === 'RUNNING') return flattenStop.message || '平仓并停止中';
                      if (flattenStop.state === 'COMPLETED') return flattenStop.message || '平仓并停止完成';
                      if (flattenStop.state === 'FAILED') return `失败：${flattenStop.message || '-'}`;
                      return flattenStop.message || flattenStop.state;
                    }
                    function isActiveOrder(order) {
                      if (!order || !['NEW', 'PARTIALLY_FILLED', 'UNTRIGGERED'].includes(order.status)) return false;
                      return order.orderType !== 'MARKET' || Boolean(order.exchangeOrderId);
                    }
                    function currentPairOrderIds(status) {
                      const pair = status && status.runtimeState ? status.runtimeState.activeOrderPair : null;
                      const ids = new Set();
                      if (pair && pair.buyOrder && pair.buyOrder.clientOrderId) ids.add(pair.buyOrder.clientOrderId);
                      if (pair && pair.sellOrder && pair.sellOrder.clientOrderId) ids.add(pair.sellOrder.clientOrderId);
                      return ids;
                    }
                    function renderOrders(orders, status) {
                      const tbody = document.getElementById('orders');
                      const notice = document.getElementById('orderNotice');
                      if (!orders || orders.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="11" class="muted">暂无挂单</td></tr>';
                        notice.className = 'notice';
                        notice.textContent = '';
                        return;
                      }
                      const pairIds = currentPairOrderIds(status);
                      const orphanOrders = orders.filter(order => !pairIds.has(order.clientOrderId));
                      if (orphanOrders.length > 0) {
                        notice.className = 'notice notice-warning';
                        notice.textContent = `检测到 ${orphanOrders.length} 笔非当前 pair 的活跃挂单，请核对后手动撤单或等待成交回报同步。`;
                      } else {
                        notice.className = 'notice';
                        notice.textContent = '';
                      }
                      tbody.innerHTML = orders.map(order => {
                        const inActivePair = pairIds.has(order.clientOrderId);
                        const relationLabel = inActivePair ? '当前 pair' : '非当前 pair';
                        const relationClass = inActivePair ? 'order-role-pair' : 'order-role-orphan';
                        return `
                        <tr class="${inActivePair ? '' : 'order-orphan'}">
                          <td>${esc(order.status)}</td>
                          <td><span class="order-role ${relationClass}">${relationLabel}</span></td>
                          <td>${esc(order.symbol)}</td>
                          <td>${formatSide(order.side)}</td>
                          <td>${esc(order.orderType)}</td>
                          <td>${esc(order.qty)}</td>
                          <td>${esc(order.price ?? '市价')}</td>
                          <td>${order.reduceOnly ? '是' : '否'}</td>
                          <td>${esc(order.clientOrderId)}</td>
                          <td>${esc(order.exchangeOrderId || '-')}</td>
                          <td><button class="danger compact" data-action="cancel-order" data-client-order-id="${esc(order.clientOrderId)}" data-symbol="${esc(order.symbol)}">撤单</button></td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function renderCurrentCycle(cycle) {
                      if (!cycle || !cycle.startedAt) {
                        setText('cycleMarkPricePnl', '-');
                        setText('cycleEntryDirection', '-');
                        setText('cycleStartPrice', '-');
                        setText('cycleMarketPrice', '-');
                        setText('cycleCounts', '-');
                        setText('cycleQty', '-');
                        setText('cycleDuration', '-');
                        setText('cycleTimes', '-');
                        setText('cycleCumQty', '-');
                        setText('cycleTradingFee', '-');
                        setText('cycleFee', '-');
                        setText('cycleFundingFee', '-');
                        setText('cPRQ', '-');
                        setText('cPBE', '-');
                        setText('pnlFormula', '盈亏 = cumQty * 市场价 - cumValue - 净费用。');
                        return;
                      }
                      setHtml('cycleMarkPricePnl', formatPnl(cycle.markPricePnl));
                      setText('cycleEntryDirection', formatEntryDirection(cycle.entryDirection));
                      setText('cycleStartPrice', formatNumber(cycle.startPrice));
                      setHtml('cycleMarketPrice', formatCycleMarketPrice(cycle));
                      setText('cycleCounts', `${cycle.buyCount || 0} / ${cycle.sellCount || 0}，共 ${cycle.fillCount || 0}`);
                      setText('cycleQty', `${formatNumber(cycle.buyQty)} / ${formatNumber(cycle.sellQty)}`);
                      setText('cycleDuration', formatDuration(cycle.durationSeconds));
                      setText('cycleTimes', `${formatTime(cycle.startedAt)} / ${formatTime(cycle.latestFillAt)}`);
                      setText('cycleCumQty', formatNumber(cycle.cumulativeQty));
                      setText('cycleTradingFee', formatNumber(cycle.cumulativeTradingFee));
                      setText('cycleFundingFee', formatNumber(cycle.cumulativeFundingFee));
                      setText('cycleFee', formatNumber(cycle.cumulativeFee));
                      setText('cPRQ', formatNumber(cycle.profitableReduceQty));
                      setText('cPBE', formatNumber(cycle.profitReduceBoostExtraQty));
                      setText('pnlFormula', `${cycle.symbol || '当前 Cycle'} 盈亏 = ${pnlFormulaText(cycle, cycle.marketPrice, cycle.markPricePnl)}`);
                    }
                    function renderFills(fills) {
                      const tbody = document.getElementById('fills');
                      const tradingFills = (fills || []).filter(f => f.side !== 'CYCLE_END');
                      latestFills = tradingFills;
                      const filteredFills = filterFillsByPrice(tradingFills);
                      if (filteredFills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="15">暂无成交</td></tr>';
                        setText('fillsPager', `0 / 0，${fillsCountText(filteredFills, tradingFills)}`);
                        return;
                      }
                      const cumulativeTurnover = cumulativeTurnoverMap(tradingFills);
                      const priceDiffs = priceDiffMap(tradingFills);
                      const newestFirst = [...filteredFills].reverse();
                      const totalPages = Math.max(1, Math.ceil(newestFirst.length / fillsPageSize));
                      fillsPage = Math.max(0, Math.min(fillsPage, totalPages - 1));
                      const visibleFills = newestFirst.slice(fillsPage * fillsPageSize, (fillsPage + 1) * fillsPageSize);
                      const filteredText = newestFirst.length === tradingFills.length ? '' : `，筛选自 ${fillsCountText(tradingFills, tradingFills)}`;
                      setText('fillsPager', `${fillsPage + 1} / ${totalPages}，${fillsCountText(newestFirst, tradingFills)}${filteredText}`);
                      tbody.innerHTML = visibleFills.map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(f.qty)}</td>
                          <td>${esc(f.price)}</td>
                          <td>${formatPriceDiff(priceDiffs.get(fillKey(f)))}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(cumulativeTurnover.get(fillKey(f))))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function previousFillsPage() {
                      fillsPage = Math.max(0, fillsPage - 1);
                      renderFills(latestFills);
                    }
                    function nextFillsPage() {
                      const totalPages = Math.max(1, Math.ceil((latestFills || []).length / fillsPageSize));
                      fillsPage = Math.min(totalPages - 1, fillsPage + 1);
                      renderFills(latestFills);
                    }
                    function applyFillPriceFilter() {
                      fillsPage = 0;
                      renderFills(latestFills);
                    }
                    function clearFillPriceFilter() {
                      const minInput = document.getElementById('fillPriceMin');
                      const maxInput = document.getElementById('fillPriceMax');
                      if (minInput) minInput.value = '';
                      if (maxInput) maxInput.value = '';
                      fillsPage = 0;
                      renderFills(latestFills);
                    }
                    function filterFillsByPrice(fills) {
                      const minInput = document.getElementById('fillPriceMin');
                      const maxInput = document.getElementById('fillPriceMax');
                      const min = minInput && minInput.value !== '' ? Number(minInput.value) : null;
                      const max = maxInput && maxInput.value !== '' ? Number(maxInput.value) : null;
                      if ((min === null || !Number.isFinite(min)) && (max === null || !Number.isFinite(max))) return fills;
                      return fills.filter(fill => {
                        const price = Number(fill.price);
                        if (!Number.isFinite(price)) return false;
                        if (min !== null && Number.isFinite(min) && price < min) return false;
                        if (max !== null && Number.isFinite(max) && price > max) return false;
                        return true;
                      });
                    }
                    function fillsCountText(fills, originalFills) {
                      const tradingCount = (fills || []).filter(fill => fill.side === 'BUY' || fill.side === 'SELL').length;
                      const fundingCount = (fills || []).filter(fill => fill.side === 'FUNDING_FEE').length;
                      const totalCount = (fills || []).length;
                      if (fundingCount === 0) return `成交 ${tradingCount} 笔`;
                      return `成交 ${tradingCount} 笔，资金费 ${fundingCount} 笔，共 ${totalCount} 条`;
                    }
                    function renderCycles(cycles) {
                      const tbody = document.getElementById('cycles');
                      if (!cycles || cycles.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="7">暂无 Cycle</td></tr>';
                        return;
                      }
                      tbody.innerHTML = cycles.slice(0, 100).map(c => `
                        <tr>
                          <td>${formatTime(c.time)}</td>
                          <td>${esc(c.symbol)}</td>
                          <td>${esc(formatNumber(c.price))}</td>
                          <td>${formatPnl(c.totalPnlAtFill)}</td>
                          <td>${esc(c.clientOrderId)}</td>
                          <td>${esc(c.executionId)}</td>
                          <td>
                            <button class="compact" data-action="cycle-detail" data-execution-id="${esc(c.executionId)}" data-label="${esc(`${c.symbol} ${formatTime(c.time)}`)}">明细</button>
                            <button class="compact" data-action="cycle-load-config" data-execution-id="${esc(c.executionId)}" data-label="${esc(`${c.symbol} ${formatTime(c.time)}`)}">加载参数</button>
                          </td>
                        </tr>
                      `).join('');
                    }
                    async function loadCycleConfig(executionId, label) {
                      const notice = document.getElementById('cycleDetailNotice');
                      notice.className = 'notice notice-info';
                      notice.textContent = '读取 Cycle 参数...';
                      try {
                        const result = await fetch(`/api/cycle-config?executionId=${encodeURIComponent(executionId)}`).then(r => r.json());
                        if (!result.ok) {
                          notice.className = 'notice notice-error';
                          notice.textContent = result.message || '读取 Cycle 参数失败';
                          return;
                        }
                        applyConfig(result.config);
                        notice.className = result.exact ? 'notice notice-ok' : 'notice notice-warning';
                        notice.textContent = result.exact
                          ? `${label || 'Cycle'}：参数已加载`
                          : `${label || 'Cycle'}：${result.message || '参数已尽量回填，请检查后再启动'}`;
                      } catch (err) {
                        notice.className = 'notice notice-error';
                        notice.textContent = err && err.message ? err.message : '读取 Cycle 参数失败';
                      }
                    }
                    async function loadCycleDetail(executionId, label) {
                      const notice = document.getElementById('cycleDetailNotice');
                      notice.className = 'notice notice-info';
                      notice.textContent = '读取 Cycle 成交明细...';
                      try {
                        const fills = await fetch(`/api/cycle-fills?executionId=${encodeURIComponent(executionId)}`).then(r => r.json());
                        renderCycleDetail(fills);
                        notice.className = 'notice notice-ok';
                        notice.textContent = `${label || 'Cycle'}：${fills.length} 笔成交`;
                      } catch (err) {
                        notice.className = 'notice notice-error';
                        notice.textContent = err && err.message ? err.message : '读取 Cycle 成交明细失败';
                      }
                    }
                    async function loadRebalanceCycleDetail(executionId) {
                      try {
                        const fills = await fetch(`/api/rebalance-cycle-fills?executionId=${encodeURIComponent(executionId)}`).then(r => r.json());
                        renderSimpleFills('rebalanceCycleDetail', fills);
                      } catch (err) {
                        const tbody = document.getElementById('rebalanceCycleDetail');
                        if (tbody) tbody.innerHTML = '<tr><td colspan="13" class="muted">读取失败</td></tr>';
                      }
                    }
                    function renderCycleDetail(fills) {
                      const tbody = document.getElementById('cycleDetail');
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="14">暂无明细</td></tr>';
                        return;
                      }
                      const cumulativeTurnover = cumulativeTurnoverMap(fills);
                      tbody.innerHTML = fills.map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(f.qty)}</td>
                          <td>${esc(f.price)}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(cumulativeTurnover.get(fillKey(f))))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function fillKey(fill) {
                      return `${fill.executionId || fill.clientOrderId || ''}|${fill.time || ''}`;
                    }
                    function cumulativeTurnoverMap(fills) {
                      const sorted = [...(fills || [])]
                        .filter(f => f)
                        .sort((a, b) => Number(a.time || 0) - Number(b.time || 0));
                      let total = 0;
                      const byKey = new Map();
                      sorted.forEach(fill => {
                        if (fill.side === 'BUY' || fill.side === 'SELL') {
                          const value = Number(fill.value || 0);
                          total += Math.abs(value);
                        }
                        byKey.set(fillKey(fill), total);
                      });
                      return byKey;
                    }
                    function priceDiffMap(fills) {
                      const sorted = [...(fills || [])]
                        .filter(f => f && (f.side === 'BUY' || f.side === 'SELL'))
                        .sort((a, b) => Number(a.time || 0) - Number(b.time || 0));
                      const byKey = new Map();
                      sorted.forEach((fill, index) => {
                        if (index === 0) return;
                        const diff = Number(fill.price || 0) - Number(sorted[index - 1].price || 0);
                        if (Number.isFinite(diff)) byKey.set(fillKey(fill), diff);
                      });
                      return byKey;
                    }
                    function formatSide(side) {
                      if (side === 'BUY') return '买入';
                      if (side === 'SELL') return '卖出';
                      if (side === 'FUNDING_FEE') return '资金费';
                      if (side === 'CYCLE_END') return 'Cycle结束';
                      return esc(side || '');
                    }
                    function formatRebalanceEvent(event) {
                      if (event === 'command.place') return '下单';
                      if (event === 'command.cancel') return '撤单';
                      if (event === 'rebalance.fill.received') return '成交';
                      return esc(event || '');
                    }
                    function formatCycleCounts(cycle) {
                      const buy = Number(cycle && cycle.buyCount);
                      const sell = Number(cycle && cycle.sellCount);
                      const total = Number(cycle && cycle.fillCount);
                      const safeBuy = Number.isFinite(buy) ? buy : 0;
                      const safeSell = Number.isFinite(sell) ? sell : 0;
                      const safeTotal = Number.isFinite(total) ? total : safeBuy + safeSell;
                      return `${safeBuy} / ${safeSell}，共 ${safeTotal}`;
                    }
                    function displaySymbol(symbol) {
                      return typeof symbol === 'string' && symbol.startsWith('RBAL:') ? symbol.slice(5) : (symbol || '');
                    }
                    function formatEntryDirection(direction) {
                      if (direction === 'LONG') return '做多';
                      if (direction === 'SHORT') return '做空';
                      if (direction === 'SPOT_LONG_ONLY') return '现货只做多';
                      return '-';
                    }
                    function formatTime(value) {
                      if (!value) return '';
                      const time = typeof value === 'number' && value < 1000000000000 ? value * 1000 : value;
                      return new Date(time).toLocaleString('zh-CN', {hour12:false});
                    }
                    function formatDuration(seconds) {
                      const total = Math.max(0, Number(seconds || 0));
                      const d = Math.floor(total / 86400);
                      const h = Math.floor((total % 86400) / 3600);
                      const m = Math.floor((total % 3600) / 60);
                      if (d > 0) return `${d}天 ${h}时 ${m}分`;
                      if (h > 0) return `${h}时 ${m}分`;
                      return `${m}分`;
                    }
                    function formatPnl(value) {
                      const n = Number(value || 0);
                      const cls = n > 0 ? 'pnl-pos' : (n < 0 ? 'pnl-neg' : 'pnl-flat');
                      return `<span class="${cls}">${formatNumber(value)}</span>`;
                    }
                    function formatPriceDiff(diff) {
                      if (!Number.isFinite(diff)) return '-';
                      const cls = diff > 0 ? 'pnl-pos' : (diff < 0 ? 'pnl-neg' : 'pnl-flat');
                      const sign = diff > 0 ? '+' : '';
                      return `<span class="${cls}">${sign}${formatNumber(diff)}</span>`;
                    }
                    function formatOrderPriceDiff(order, latestFillPrice) {
                      const price = Number(order && order.price);
                      if (!Number.isFinite(price) || !Number.isFinite(latestFillPrice)) return '-';
                      return formatPriceDiff(price - latestFillPrice);
                    }
                    function formatPriceWithStartChange(price, startPrice) {
                      const current = Number(price);
                      const start = Number(startPrice);
                      if (!Number.isFinite(current)) return '-';
                      if (!Number.isFinite(start) || start === 0) return formatNumber(price);
                      const change = (current - start) / start * 100;
                      const cls = change > 0 ? 'pnl-pos' : (change < 0 ? 'pnl-neg' : 'pnl-flat');
                      const sign = change > 0 ? '+' : '';
                      return `${formatNumber(price)} <span class="${cls}">(${sign}${formatNumber(change)}%)</span>`;
                    }
                    function formatCycleMarketPrice(cycle) {
                      const priceText = formatPriceWithStartChange(cycle.marketPrice, cycle.startPrice);
                      const cost = costPrice(cycle);
                      if (cost === null || cost === undefined) return priceText;
                      return `${priceText} / 成本 ${formatNumber(cost)}`;
                    }
                    function cumulativePnlAtPrice(fill, price) {
                      const tradingFee = Number(fill.cumulativeTradingFee ?? fill.cumulativeFee ?? 0);
                      const fundingFee = Number(fill.cumulativeFundingFee || 0);
                      return Number(fill.cumulativeQty || 0) * Number(price || 0)
                        - Number(fill.cumulativeValue || 0)
                        - tradingFee
                        + fundingFee;
                    }
                    function pnlFormulaText(source, price, pnlValue) {
                      const pnl = pnlValue === undefined ? cumulativePnlAtPrice(source, price) : pnlValue;
                      const tradingFee = source.cumulativeTradingFee ?? source.cumulativeFee;
                      const fundingFee = source.cumulativeFundingFee || 0;
                      return `${formatNumber(source.cumulativeQty)} * ${formatNumber(price)} - (${formatNumber(source.cumulativeValue)}) - ${formatNumber(tradingFee)} + ${formatNumber(fundingFee)} = ${formatNumber(pnl)}`;
                    }
                    function renderFundingDetail() {
                      const rows = (latestFills || []).filter(f => f.side === 'FUNDING_FEE');
                      const tbody = document.getElementById('cycleDetail');
                      const notice = document.getElementById('cycleDetailNotice');
                      if (rows.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="14">当前 Cycle 暂无资金费记录</td></tr>';
                        notice.className = 'notice notice-warning';
                        notice.textContent = '当前 Cycle 暂无资金费记录';
                        return;
                      }
                      notice.className = 'notice notice-ok';
                      notice.textContent = `资金费明细：${rows.length} 条，合计 ${formatNumber(rows.reduce((sum, row) => sum + Number(row.fee || 0), 0))} USDT`;
                      tbody.innerHTML = rows.map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(formatNumber(f.qty))}</td>
                          <td>${esc(formatNumber(f.price))}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(f.cumulativeValue))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function formatNumber(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      if (!Number.isFinite(n)) return String(value);
                      return n.toFixed(6).replace(/\\.0+$/, '').replace(/(\\.\\d*?)0+$/, '$1');
                    }
                    function formatPrice2(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      return Number.isFinite(n) ? n.toFixed(2) : '-';
                    }
                    function estimateFullInventoryPrice(state, currentPrice) {
                      const equity = Number(state && state.totalEquityUsdt);
                      const netQty = Number(state && state.netBaseQty);
                      const spacing = Number(latestRebalanceConfig && latestRebalanceConfig.baseGridSpacing);
                      const buyQty = Number(latestRebalanceConfig && (latestRebalanceConfig.autoBaseGridQty || latestRebalanceConfig.baseGridQty));
                      if (!Number.isFinite(equity) || !Number.isFinite(netQty) || !Number.isFinite(currentPrice)
                          || !Number.isFinite(spacing) || !Number.isFinite(buyQty)
                          || equity <= 0 || netQty < 0 || currentPrice <= 0 || spacing <= 0 || spacing >= 1 || buyQty <= 0) return null;
                      let nonBtcValue = equity - netQty * currentPrice;
                      if (nonBtcValue <= 0) return currentPrice;
                      let price = currentPrice;
                      for (let step = 0; step < 100000; step += 1) {
                        price *= (1 - spacing);
                        nonBtcValue -= buyQty * price;
                        if (nonBtcValue <= 0) return price;
                      }
                      return null;
                    }
                    function formatPercent(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      if (!Number.isFinite(n)) return String(value);
                      return `${formatNumber(n * 100)}%`;
                    }
                    function costPrice(fill) {
                      const qty = Number(fill && (fill.cumulativeQty ?? fill.netQty ?? 0));
                      if (!Number.isFinite(qty) || Math.abs(qty) < 1e-12) return null;
                      const value = Number(fill.cumulativeValue || 0);
                      const fee = Number(fill.cumulativeFee || 0);
                      if (!Number.isFinite(value) || !Number.isFinite(fee)) return null;
                      return (value + fee) / qty;
                    }
                    function esc(value) {
                      return String(value ?? '').replace(/[&<>"']/g, c => ({
                        '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
                      }[c]));
                    }
                    function setText(id, value) {
                      document.getElementById(id).textContent = value;
                    }
                    function setHtml(id, value) {
                      document.getElementById(id).innerHTML = value;
                    }
                    applyPageMode();
                    refresh(); setInterval(refresh, 5000);
                  </script>
                </body>
                </html>
                """);
    }
}
