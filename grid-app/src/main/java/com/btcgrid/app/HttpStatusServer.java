package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

public class HttpStatusServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier;
    private final LiveControlService liveControlService;
    private final EventLogger eventLogger;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public HttpStatusServer(int port, RuntimeStatusProvider runtimeStatusProvider, EventLogger eventLogger) {
        this(port, runtimeStatusProvider::snapshot, null, eventLogger);
    }

    public HttpStatusServer(
            int port,
            Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier,
            LiveControlService liveControlService,
            EventLogger eventLogger
    ) {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create HTTP status server on port " + port, e);
        }
        this.runtimeStatusSupplier = runtimeStatusSupplier;
        this.liveControlService = liveControlService;
        this.eventLogger = eventLogger;
        registerContexts();
    }

    public void start() {
        httpServer.start();
        eventLogger.info("http.started", Map.of("port", httpServer.getAddress().getPort()));
    }

    @Override
    public void close() {
        httpServer.stop(0);
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
            httpServer.createContext("/api/fills", exchange -> writeJson(exchange, liveControlService.recentFills()));
            httpServer.createContext("/api/start", exchange -> {
                requirePost(exchange);
                LiveControlService.StartRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.StartRequest.class);
                writeJson(exchange, liveControlService.start(request));
            });
            httpServer.createContext("/api/stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.stop());
            });
            httpServer.createContext("/api/flatten-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.flattenAndStop());
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
        byte[] bytes = indexHtml().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
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
                    body { font-family: system-ui, sans-serif; margin: 24px; background: #f6f3ec; color: #222; }
                    .card { background: #fffaf0; border: 1px solid #dfd3bd; border-radius: 14px; padding: 18px; max-width: 1100px; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
                    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin: 14px 0; }
                    .metric { background: #ffffff; border: 1px solid #dfd3bd; border-radius: 8px; padding: 10px 12px; }
                    .metric span { display: block; color: #726650; font-size: 12px; }
                    .metric strong { display: block; margin-top: 4px; font-size: 18px; }
                    .badge { display: inline-flex; align-items: center; min-height: 28px; padding: 0 10px; border-radius: 999px; font-size: 13px; font-weight: 800; }
                    .badge-running { background: #d7f0e4; color: #0f5c3f; }
                    .badge-idle { background: #eee6d4; color: #6f5a30; }
                    .badge-error { background: #f7d6d1; color: #8d251b; }
                    label { display: grid; gap: 5px; font-size: 13px; color: #544; }
                    input, select { padding: 9px; border: 1px solid #c7bca8; border-radius: 8px; font-size: 14px; background: white; }
                    small { color: #8a7d69; font-size: 11px; }
                    button { padding: 10px 14px; border: 0; border-radius: 9px; margin-right: 8px; cursor: pointer; font-weight: 700; }
                    button:disabled { cursor: not-allowed; opacity: 0.55; }
                    .start { background: #176b4d; color: white; }
                    .stop { background: #9b6a17; color: white; }
                    .danger { background: #a33125; color: white; }
                    table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
                    th, td { border-bottom: 1px solid #dfd3bd; padding: 8px; text-align: left; }
                    th { color: #5c4b34; background: #f3ead7; }
                    td.muted { color: #8a7d69; }
                    pre { background: #211f1a; color: #f8f2df; padding: 14px; border-radius: 10px; overflow: auto; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>BTC Grid 控制台</h2>
                    <div class="summary">
                      <div class="metric"><span>运行状态</span><strong><span id="runBadge" class="badge badge-idle">读取中</span></strong></div>
                      <div class="metric"><span>策略状态</span><strong id="strategyState">-</strong></div>
                      <div class="metric"><span>当前价格</span><strong id="lastPrice">-</strong></div>
                      <div class="metric"><span>挂单数量</span><strong id="orderCount">0</strong></div>
                    </div>
                    <div class="grid" id="form"></div>
                    <p>
                      <button id="startButton" class="start" onclick="start()">启动</button>
                      <button id="stopButton" class="stop" onclick="post('/api/stop')">停止</button>
                      <button id="flattenButton" class="danger" onclick="post('/api/flatten-stop')">清仓并停止</button>
                    </p>
                    <p>说明：通过此界面启动的策略使用经典减仓数量计算，cycle 结束后会自动停止。</p>
                    <h3>当前挂单</h3>
                    <table>
                      <thead><tr><th>状态</th><th>标的</th><th>方向</th><th>类型</th><th>数量</th><th>价格</th><th>减仓</th><th>客户端订单ID</th><th>交易所订单ID</th></tr></thead>
                      <tbody id="orders"><tr><td colspan="9" class="muted">暂无挂单</td></tr></tbody>
                    </table>
                    <h3>成交记录</h3>
                    <table>
                      <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>订单ID</th></tr></thead>
                      <tbody id="fills"><tr><td colspan="6">暂无成交</td></tr></tbody>
                    </table>
                    <h3>运行状态</h3>
                    <pre id="status">loading...</pre>
                  </div>
                  <script>
                    const fields = {
                      symbol:'BTCUSDT', entryDirection:'long', baseGridSpacing:'0.0016', gridRequoteShrinkFactor:'0.85', baseGridQty:'0.005',
                      maxInitialSteps:'5', maxOrderQty:'0.20', maxInventoryQty:'0.15', maxDirectionalInventoryQty:'0.15',
                      emaLength4h:'20', bollLength4h:'20', bollStdDevMultiplier:'2.0',
                      pairOrderTimeoutMinutes:'15', triggerCooldownMinutes:'5'
                    };
                    const labels = {
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
                      long:'做多',
                      short:'做空'
                    };
                    const form = document.getElementById('form');
                    Object.entries(fields).forEach(([k,v]) => {
                      const control = k === 'entryDirection'
                        ? `<select id="${k}">${Object.entries(directionOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                        : `<input id="${k}" value="${v}">`;
                      form.insertAdjacentHTML('beforeend', `<label>${labels[k] || k}${control}<small>${k}</small></label>`);
                    });
                    function payload() {
                      const out = {};
                      Object.keys(fields).forEach(k => out[k] = document.getElementById(k).value);
                      return out;
                    }
                    async function post(url, body) {
                      const r = await fetch(url, {method:'POST', body: JSON.stringify(body || {}), headers:{'content-type':'application/json'}});
                      document.getElementById('status').textContent = JSON.stringify(await r.json(), null, 2);
                      setTimeout(refresh, 500);
                    }
                    function start() { post('/api/start', payload()); }
                    async function refresh() {
                      const [control, status, fills] = await Promise.all([
                        fetch('/api/control').then(r=>r.json()),
                        fetch('/status').then(r=>r.json()),
                        fetch('/api/fills').then(r=>r.json())
                      ]);
                      const activeOrders = (status.workingOrders || []).filter(isActiveOrder);
                      renderSummary(control, status, activeOrders);
                      renderOrders(activeOrders);
                      renderFills(fills);
                      document.getElementById('status').textContent = JSON.stringify({control, status}, null, 2);
                    }
                    function renderSummary(control, status, activeOrders) {
                      const running = Boolean(control && control.running);
                      const state = status && status.runtimeState ? status.runtimeState.state : '-';
                      const snapshot = status && status.latestMarketSnapshot ? status.latestMarketSnapshot : {};
                      const badge = document.getElementById('runBadge');
                      badge.textContent = running ? '运行中' : '未运行';
                      badge.className = `badge ${running ? 'badge-running' : 'badge-idle'}`;
                      document.getElementById('strategyState').textContent = state || '-';
                      document.getElementById('lastPrice').textContent = snapshot.lastPrice ?? '-';
                      document.getElementById('orderCount').textContent = activeOrders.length;
                      document.getElementById('startButton').disabled = running;
                      document.getElementById('stopButton').disabled = !running;
                      document.getElementById('flattenButton').disabled = !running;
                    }
                    function isActiveOrder(order) {
                      if (!order || !['NEW', 'PARTIALLY_FILLED', 'UNTRIGGERED'].includes(order.status)) return false;
                      return order.orderType !== 'MARKET' || Boolean(order.exchangeOrderId);
                    }
                    function renderOrders(orders) {
                      const tbody = document.getElementById('orders');
                      if (!orders || orders.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="9" class="muted">暂无挂单</td></tr>';
                        return;
                      }
                      tbody.innerHTML = orders.map(order => `
                        <tr>
                          <td>${esc(order.status)}</td>
                          <td>${esc(order.symbol)}</td>
                          <td>${formatSide(order.side)}</td>
                          <td>${esc(order.orderType)}</td>
                          <td>${esc(order.qty)}</td>
                          <td>${esc(order.price ?? '市价')}</td>
                          <td>${order.reduceOnly ? '是' : '否'}</td>
                          <td>${esc(order.clientOrderId)}</td>
                          <td>${esc(order.exchangeOrderId || '-')}</td>
                        </tr>
                      `).join('');
                    }
                    function renderFills(fills) {
                      const tbody = document.getElementById('fills');
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="6">暂无成交</td></tr>';
                        return;
                      }
                      tbody.innerHTML = fills.slice(0, 50).map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(f.qty)}</td>
                          <td>${esc(f.price)}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function formatSide(side) {
                      if (side === 'BUY') return '买入';
                      if (side === 'SELL') return '卖出';
                      return esc(side || '');
                    }
                    function formatTime(value) {
                      if (!value) return '';
                      return new Date(value).toLocaleString('zh-CN', {hour12:false});
                    }
                    function esc(value) {
                      return String(value ?? '').replace(/[&<>"']/g, c => ({
                        '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
                      }[c]));
                    }
                    refresh(); setInterval(refresh, 5000);
                  </script>
                </body>
                </html>
                """;
    }
}
