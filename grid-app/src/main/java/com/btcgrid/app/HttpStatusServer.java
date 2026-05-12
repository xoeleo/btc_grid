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
import java.util.function.Supplier;

public class HttpStatusServer implements AutoCloseable {

    private final HttpServer httpServer;
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
            httpServer.createContext("/api/cycles", exchange -> writeJson(exchange, liveControlService.recentCycles()));
            httpServer.createContext("/api/current-cycle", exchange -> writeJson(exchange, liveControlService.currentCycle()));
            httpServer.createContext("/api/cycle-fills", exchange -> writeJson(exchange, liveControlService.cycleFills(queryParam(exchange, "executionId"))));
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
        byte[] bytes = indexHtml().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
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
                    .notice { display: none; margin: 12px 0; padding: 10px 12px; border-radius: 8px; font-weight: 700; }
                    .notice-info { display: block; background: #e8eef7; color: #26405f; border: 1px solid #c7d4e5; }
                    .notice-ok { display: block; background: #d7f0e4; color: #0f5c3f; border: 1px solid #a9d8c1; }
                    .notice-error { display: block; background: #f7d6d1; color: #8d251b; border: 1px solid #e6aaa2; }
                    .notice-warning { display: block; background: #fff2c7; color: #744c05; border: 1px solid #e3c367; }
                    label { display: grid; gap: 5px; font-size: 13px; color: #544; }
                    input, select { padding: 9px; border: 1px solid #c7bca8; border-radius: 8px; font-size: 14px; background: white; }
                    small { color: #8a7d69; font-size: 11px; }
                    button { padding: 10px 14px; border: 0; border-radius: 9px; margin-right: 8px; cursor: pointer; font-weight: 700; }
                    button:disabled { cursor: not-allowed; opacity: 0.55; }
                    .start { background: #176b4d; color: white; }
                    .stop { background: #9b6a17; color: white; }
                    .danger { background: #a33125; color: white; }
                    .compact { padding: 6px 10px; border-radius: 7px; margin-right: 0; font-size: 12px; }
                    .table-wrap { width: 100%; overflow-x: auto; border: 1px solid #dfd3bd; border-radius: 8px; background: #fffaf0; }
                    table { width: 100%; min-width: max-content; border-collapse: collapse; font-size: 13px; }
                    th, td { border-bottom: 1px solid #dfd3bd; padding: 8px; text-align: left; white-space: nowrap; }
                    th { color: #5c4b34; background: #f3ead7; }
                    td.muted { color: #8a7d69; }
                    .order-orphan { background: #fff7df; }
                    .order-role { display: inline-flex; align-items: center; min-height: 24px; padding: 0 8px; border-radius: 999px; font-size: 12px; font-weight: 800; }
                    .order-role-pair { background: #d7f0e4; color: #0f5c3f; }
                    .order-role-orphan { background: #fff2c7; color: #744c05; }
                    .pnl-pos { color: #0f6b45; font-weight: 700; }
                    .pnl-neg { color: #a33125; font-weight: 700; }
                    .pnl-flat { color: #6f5a30; }
                    pre { background: #211f1a; color: #f8f2df; padding: 14px; border-radius: 10px; overflow: auto; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>BTC Grid 控制台</h2>
                    <div class="summary">
                      <div class="metric"><span>运行状态</span><strong><span id="runBadge" class="badge badge-idle">读取中</span></strong></div>
                      <div class="metric"><span>当前标的</span><strong id="activeSymbol">-</strong></div>
                      <div class="metric"><span>Cycle</span><strong id="cycleStatus">-</strong></div>
                      <div class="metric"><span>策略状态</span><strong id="strategyState">-</strong></div>
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
                    <p>说明：通过此界面启动的策略使用经典减仓数量计算，cycle 结束后会自动停止。</p>
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
                      <div class="metric"><span>买入 / 卖出次数</span><strong id="cycleCounts">-</strong></div>
                      <div class="metric"><span>买入 / 卖出数量</span><strong id="cycleQty">-</strong></div>
                      <div class="metric"><span>持续时长</span><strong id="cycleDuration">-</strong></div>
                      <div class="metric"><span>开始 / 最近成交</span><strong id="cycleTimes">-</strong></div>
                      <div class="metric"><span>累计净数量</span><strong id="cycleCumQty">-</strong></div>
                      <div class="metric"><span>累计手续费</span><strong id="cycleFee">-</strong></div>
                    </div>
                    <h3>成交记录</h3>
                    <small id="pnlFormula">盈亏 = cumQty * 市场价 - cumValue - cumFee；成交行的市场价取本次成交价，当前 Cycle 的市场价取最新标记价。</small>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>成交额</th><th>手续费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计手续费</th><th>均价</th><th>订单ID</th></tr></thead>
                        <tbody id="fills"><tr><td colspan="13">暂无成交</td></tr></tbody>
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
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>价格</th><th>成交额</th><th>手续费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计手续费</th><th>均价</th><th>订单ID</th></tr></thead>
                        <tbody id="cycleDetail"><tr><td colspan="13">请选择一个历史 Cycle</td></tr></tbody>
                      </table>
                    </div>
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
                      setBusy(true);
                      showActionResult('info', '请求处理中...');
                      try {
                        const r = await fetch(url, {method:'POST', body: JSON.stringify(body || {}), headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        document.getElementById('status').textContent = JSON.stringify(result, null, 2);
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
                    function setBusy(busy) {
                      ['startButton', 'resumeButton', 'stopButton', 'flattenButton'].forEach(id => {
                        const button = document.getElementById(id);
                        if (button) button.dataset.busy = busy ? 'true' : 'false';
                      });
                      document.querySelectorAll('[data-action="cancel-order"]').forEach(button => button.disabled = busy);
                      document.body.dataset.busy = busy ? 'true' : 'false';
                    }
                    function showActionResult(type, message) {
                      const notice = document.getElementById('actionNotice');
                      notice.className = `notice notice-${type}`;
                      notice.textContent = message;
                    }
                    async function refresh() {
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
                      document.getElementById('status').textContent = JSON.stringify({control, status, currentCycle}, null, 2);
                    }
                    function renderSummary(control, status, activeOrders) {
                      const running = Boolean(control && control.running);
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
                      document.getElementById('lastPrice').textContent = snapshot.markPrice == null
                        ? (snapshot.lastPrice ?? '-')
                        : `${snapshot.lastPrice ?? '-'} / ${snapshot.markPrice}`;
                      document.getElementById('orderCount').textContent = activeOrders.length;
                      document.getElementById('lastOrderStatus').textContent = lastOrder
                        ? `${formatSide(lastOrder.side)} ${lastOrder.orderType} ${lastOrder.status}`
                        : '-';
                      document.getElementById('startButton').disabled = running;
                      document.getElementById('resumeButton').disabled = running || !Boolean(control && control.canResume);
                      document.getElementById('stopButton').disabled = !running;
                      document.getElementById('flattenButton').disabled = !running;
                      if (document.body.dataset.busy === 'true') {
                        document.getElementById('startButton').disabled = true;
                        document.getElementById('resumeButton').disabled = true;
                        document.getElementById('stopButton').disabled = true;
                        document.getElementById('flattenButton').disabled = true;
                      }
                    }
                    function cycleLabel(control, status) {
                      if (!control || !control.running) return '未运行';
                      const pair = status && status.runtimeState ? status.runtimeState.activeOrderPair : null;
                      if (pair) return '网格挂单中';
                      if (status && status.runtimeState && status.runtimeState.state === 'OPENING_FIRST_POSITION') return '开仓中';
                      return control.stopAfterCycle ? '本轮结束后停止' : '连续运行';
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
                        setText('cycleCounts', '-');
                        setText('cycleQty', '-');
                        setText('cycleDuration', '-');
                        setText('cycleTimes', '-');
                        setText('cycleCumQty', '-');
                        setText('cycleFee', '-');
                        setText('pnlFormula', '盈亏 = cumQty * 市场价 - cumValue - cumFee；成交行的市场价取本次成交价，当前 Cycle 的市场价取最新标记价。');
                        return;
                      }
                      setHtml('cycleMarkPricePnl', formatPnl(cycle.markPricePnl));
                      setText('cycleEntryDirection', formatEntryDirection(cycle.entryDirection));
                      setText('cycleCounts', `${cycle.buyCount || 0} / ${cycle.sellCount || 0}，共 ${cycle.fillCount || 0}`);
                      setText('cycleQty', `${formatNumber(cycle.buyQty)} / ${formatNumber(cycle.sellQty)}`);
                      setText('cycleDuration', formatDuration(cycle.durationSeconds));
                      setText('cycleTimes', `${formatTime(cycle.startedAt)} / ${formatTime(cycle.latestFillAt)}`);
                      setText('cycleCumQty', formatNumber(cycle.cumulativeQty));
                      setText('cycleFee', formatNumber(cycle.cumulativeFee));
                      setText('pnlFormula', `${cycle.symbol || '当前 Cycle'} 市场价盈亏 = ${pnlFormulaText(cycle, cycle.marketPrice, cycle.markPricePnl)}；成交行市场价取本次成交价。`);
                    }
                    function renderFills(fills) {
                      const tbody = document.getElementById('fills');
                      const tradingFills = (fills || []).filter(f => f.side !== 'CYCLE_END');
                      if (tradingFills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="13">暂无成交</td></tr>';
                        return;
                      }
                      tbody.innerHTML = tradingFills.slice(0, 50).map(f => `
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
                          <td>${esc(formatNumber(f.cumulativeValue))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
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
                          <td><button class="compact" data-action="cycle-detail" data-execution-id="${esc(c.executionId)}" data-label="${esc(`${c.symbol} ${formatTime(c.time)}`)}">明细</button></td>
                        </tr>
                      `).join('');
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
                    function renderCycleDetail(fills) {
                      const tbody = document.getElementById('cycleDetail');
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="13">暂无明细</td></tr>';
                        return;
                      }
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
                          <td>${esc(formatNumber(f.cumulativeValue))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function formatSide(side) {
                      if (side === 'BUY') return '买入';
                      if (side === 'SELL') return '卖出';
                      if (side === 'CYCLE_END') return 'Cycle结束';
                      return esc(side || '');
                    }
                    function formatEntryDirection(direction) {
                      if (direction === 'LONG') return '做多';
                      if (direction === 'SHORT') return '做空';
                      return '-';
                    }
                    function formatTime(value) {
                      if (!value) return '';
                      const time = typeof value === 'number' && value < 1000000000000 ? value * 1000 : value;
                      return new Date(time).toLocaleString('zh-CN', {hour12:false});
                    }
                    function formatDuration(seconds) {
                      const total = Math.max(0, Number(seconds || 0));
                      const h = Math.floor(total / 3600);
                      const m = Math.floor((total % 3600) / 60);
                      const s = Math.floor(total % 60);
                      if (h > 0) return `${h}小时 ${m}分`;
                      if (m > 0) return `${m}分 ${s}秒`;
                      return `${s}秒`;
                    }
                    function formatPnl(value) {
                      const n = Number(value || 0);
                      const cls = n > 0 ? 'pnl-pos' : (n < 0 ? 'pnl-neg' : 'pnl-flat');
                      return `<span class="${cls}">${formatNumber(value)}</span>`;
                    }
                    function cumulativePnlAtPrice(fill, price) {
                      return Number(fill.cumulativeQty || 0) * Number(price || 0)
                        - Number(fill.cumulativeValue || 0)
                        - Number(fill.cumulativeFee || 0);
                    }
                    function pnlFormulaText(source, price, pnlValue) {
                      const pnl = pnlValue === undefined ? cumulativePnlAtPrice(source, price) : pnlValue;
                      return `${formatNumber(source.cumulativeQty)} * ${formatNumber(price)} - (${formatNumber(source.cumulativeValue)}) - ${formatNumber(source.cumulativeFee)} = ${formatNumber(pnl)}`;
                    }
                    function formatNumber(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      if (!Number.isFinite(n)) return String(value);
                      return n.toFixed(6).replace(/\\.0+$/, '').replace(/(\\.\\d*?)0+$/, '$1');
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
                    refresh(); setInterval(refresh, 5000);
                  </script>
                </body>
                </html>
                """;
    }
}
