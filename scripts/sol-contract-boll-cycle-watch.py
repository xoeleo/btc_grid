#!/usr/bin/env python3
"""Start one SOLUSDT linear grid cycle when price falls below the 5m Bollinger lower band."""

import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


BASE_URL = os.environ.get("GRID_CONTROL_URL", "http://127.0.0.1:8787").rstrip("/")
BYBIT_PUBLIC_BASE = os.environ.get("BYBIT_PUBLIC_BASE", "https://api.bybit.com").rstrip("/")
POLL_SECONDS = max(5, int(os.environ.get("POLL_SECONDS", "15")))
STATUS_FILE = os.environ.get(
    "MONITOR_STATUS_FILE",
    "/opt/btc_grid/runtime/mainnet/sol-contract-boll-monitor.json",
)
SYMBOL = "SOLUSDT"
INTERVAL_MINUTES = 5
BOLL_LENGTH = 20
BOLL_STD_MULTIPLIER = 2.0
ACTIVE_ORDER_STATUSES = {"NEW", "PARTIALLY_FILLED", "UNTRIGGERED"}

START_PAYLOAD = {
    "category": "linear",
    "symbol": SYMBOL,
    "entryDirection": "long_only",
    "baseGridSpacing": "0.001",
    "gridRequoteShrinkFactor": "0.85",
    "baseGridQty": "0.5",
    "maxInitialSteps": "5",
    "maxOrderQty": "5",
    "maxInventoryQty": "50",
    "maxDirectionalInventoryQty": "50",
    "emaLength4h": "20",
    "bollLength4h": "20",
    "bollStdDevMultiplier": "2",
    "pairOrderTimeoutMinutes": "15",
    "triggerCooldownMinutes": "5",
}


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def log(message, **fields):
    suffix = " ".join(f"{key}={value}" for key, value in fields.items())
    print(f"{now_iso()} {message}" + (f" {suffix}" if suffix else ""), flush=True)


def request_json(url, payload=None, timeout=15):
    data = None
    method = "GET"
    headers = {"user-agent": "btc-grid-sol-contract-boll-watch/1.0"}
    if payload is not None:
        data = json.dumps(payload, separators=(",", ":")).encode()
        method = "POST"
        headers["content-type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode()
    return json.loads(body) if body else None


def control_json(path, payload=None):
    return request_json(BASE_URL + path, payload)


def bybit_public(path, params):
    query = urllib.parse.urlencode(params)
    result = request_json(f"{BYBIT_PUBLIC_BASE}{path}?{query}")
    if not isinstance(result, dict) or result.get("retCode") != 0:
        raise RuntimeError(f"Bybit public API rejected {path}: {result!r}")
    return result


def completed_closes(kline_rows, server_time_ms):
    interval_ms = INTERVAL_MINUTES * 60 * 1000
    completed = []
    for row in kline_rows:
        if not isinstance(row, list) or len(row) < 5:
            continue
        start_ms = int(row[0])
        if start_ms + interval_ms <= server_time_ms:
            completed.append((start_ms, float(row[4])))
    completed.sort(key=lambda item: item[0])
    return completed


def bollinger(closes):
    if len(closes) < BOLL_LENGTH:
        raise RuntimeError(f"need {BOLL_LENGTH} completed candles, got {len(closes)}")
    window = [float(value) for value in closes[-BOLL_LENGTH:]]
    middle = sum(window) / BOLL_LENGTH
    variance = sum((value - middle) ** 2 for value in window) / BOLL_LENGTH
    deviation = math.sqrt(variance) * BOLL_STD_MULTIPLIER
    return middle + deviation, middle, middle - deviation


def load_signal():
    ticker = bybit_public(
        "/v5/market/tickers",
        {"category": "linear", "symbol": SYMBOL},
    )
    ticker_rows = ticker.get("result", {}).get("list", [])
    if not ticker_rows:
        raise RuntimeError("Bybit returned no SOLUSDT linear ticker")
    last_price = float(ticker_rows[0]["lastPrice"])
    server_time_ms = int(ticker.get("time") or time.time() * 1000)

    klines = bybit_public(
        "/v5/market/kline",
        {
            "category": "linear",
            "symbol": SYMBOL,
            "interval": str(INTERVAL_MINUTES),
            "limit": str(BOLL_LENGTH + 10),
        },
    )
    rows = klines.get("result", {}).get("list", [])
    closes = completed_closes(rows, server_time_ms)
    upper, middle, lower = bollinger([close for _, close in closes])
    latest_closed_at = closes[-1][0] if closes else None
    return {
        "lastPrice": last_price,
        "bollUpper": upper,
        "bollMiddle": middle,
        "bollLower": lower,
        "signal": last_price <= lower,
        "latestClosedCandleAt": latest_closed_at,
        "completedCandleCount": len(closes),
    }


def active_orders(status):
    orders = (status or {}).get("workingOrders") or []
    return [order for order in orders if str(order.get("status", "")).upper() in ACTIVE_ORDER_STATUSES]


def write_status(state, message, signal=None, **extra):
    payload = {
        "enabled": True,
        "strategy": "SOL_CONTRACT_5M_BOLL_LOWER",
        "state": state,
        "message": message,
        "updatedAt": now_iso(),
        "symbol": SYMBOL,
        "category": "LINEAR",
        "intervalMinutes": INTERVAL_MINUTES,
        "bollLength": BOLL_LENGTH,
        "bollStdMultiplier": BOLL_STD_MULTIPLIER,
        "baseGridSpacing": 0.001,
        "baseGridQty": 0.5,
        "initialSteps": 5,
        "initialQty": 2.5,
        "maxOrderQty": 5,
        "maxInventoryQty": 50,
        "signal": signal,
    }
    payload.update(extra)
    directory = os.path.dirname(STATUS_FILE) or "."
    os.makedirs(directory, exist_ok=True)
    temporary = STATUS_FILE + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
    os.replace(temporary, STATUS_FILE)


def inspect_idle_state():
    control = control_json("/api/control") or {}
    current_cycle = control_json("/api/current-cycle")
    status = control_json("/status") or {}
    orders = active_orders(status)
    if control.get("flattenStopLocked"):
        return False, "BLOCKED_FLATTEN_LOCK", "清仓停止锁尚未解除", control, current_cycle, orders
    if control.get("running"):
        return False, "CYCLE_RUNNING", "SOL 合约网格 Cycle 正在运行", control, current_cycle, orders
    if current_cycle:
        return False, "BLOCKED_OPEN_CYCLE", "检测到尚未结束的 Cycle", control, current_cycle, orders
    if orders:
        return False, "BLOCKED_ACTIVE_ORDERS", "检测到活动挂单，暂不启动新 Cycle", control, current_cycle, orders
    return True, "MONITORING", "等待 SOL 跌破 5 分钟布林下轨", control, current_cycle, orders


def run_once():
    idle, state, message, control, current_cycle, orders = inspect_idle_state()
    if not idle:
        write_status(
            state,
            message,
            running=bool(control.get("running")),
            currentCycle=bool(current_cycle),
            activeOrderCount=len(orders),
        )
        return

    signal = load_signal()
    if not signal["signal"]:
        write_status("MONITORING", "等待 SOL 跌破 5 分钟布林下轨", **signal)
        return

    # Recheck immediately before the live start request so a concurrent manual start cannot create two cycles.
    idle, state, message, control, current_cycle, orders = inspect_idle_state()
    if not idle:
        write_status(
            state,
            message,
            **signal,
            running=bool(control.get("running")),
            currentCycle=bool(current_cycle),
            activeOrderCount=len(orders),
        )
        return

    write_status("TRIGGERING", "已跌破下轨，正在启动 SOL 合约网格", **signal)
    result = control_json("/api/start", START_PAYLOAD) or {}
    if not result.get("ok"):
        write_status(
            "START_REJECTED",
            str(result.get("message") or "启动请求被拒绝"),
            **signal,
            startResult=result,
        )
        log("sol-contract-boll-watch.start_rejected", result=result)
        return
    write_status("CYCLE_STARTED", "SOL 合约网格 Cycle 已启动", **signal, startResult=result)
    log(
        "sol-contract-boll-watch.cycle_started",
        last_price=signal["lastPrice"],
        boll_lower=signal["bollLower"],
        initial_qty=START_PAYLOAD["baseGridQty"] + "x" + START_PAYLOAD["maxInitialSteps"],
    )


def self_test():
    closes = list(range(1, 21))
    upper, middle, lower = bollinger(closes)
    assert round(middle, 8) == 10.5
    assert lower < middle < upper
    rows = [["0", "1", "1", "1", "1"], ["300000", "2", "2", "2", "2"]]
    assert completed_closes(rows, 600000) == [(0, 1.0), (300000, 2.0)]
    print("sol-contract-boll-cycle-watch self-test passed")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    log(
        "sol-contract-boll-watch.started",
        base_url=BASE_URL,
        poll_seconds=POLL_SECONDS,
        status_file=STATUS_FILE,
    )
    while True:
        try:
            run_once()
        except urllib.error.HTTPError as error:
            message = f"HTTP {error.code}: {error.reason}"
            write_status("ERROR", message)
            log("sol-contract-boll-watch.http_error", code=error.code, reason=error.reason)
        except Exception as error:
            write_status("ERROR", str(error))
            log("sol-contract-boll-watch.error", error=repr(error))
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
