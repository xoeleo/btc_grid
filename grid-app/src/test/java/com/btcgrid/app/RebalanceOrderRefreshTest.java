package com.btcgrid.app;

import com.btcgrid.bybit.adapter.*;
import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceOrderRefreshTest {
    @TempDir Path dir;
    RebalanceRequoteTest fixture(){var f=new RebalanceRequoteTest();f.dir=dir;return f;}
    static class Query implements BybitOrderQueryGateway {
        volatile List<FillEvent> fills=List.of();
        volatile List<WorkingOrder> open=List.of();
        volatile boolean fail;
        final AtomicInteger calls=new AtomicInteger();
        public List<WorkingOrder> fetchOpenOrders(String symbol){return open;}
        public List<FillEvent> fetchRecentExecutions(String symbol,Set<String> ids){return fills;}
        public List<FillEvent> fetchExecutions(String symbol,Set<String> ids,Instant from,Instant to){
            calls.incrementAndGet();if(fail)throw new IllegalStateException("query unavailable");return fills;
        }
    }
    RebalanceLiveRunner runner(RebalanceRequoteTest f,RebalanceService s,Query query)throws Exception {
        BybitPrivateStreamGateway stream=new BybitPrivateStreamGateway(){
            public void connect(com.btcgrid.common.live.PrivateExecutionListener e,com.btcgrid.common.live.PrivateFundingFeeListener fee,
                com.btcgrid.common.live.PrivateOrderListener o,com.btcgrid.common.live.PrivateStreamLifecycleListener l){}
            public void close(){}
        };
        RebalanceLiveRunner r=new RebalanceLiveRunner(s,stream,query,null,f.registry,f.log,new AlertService(f.log),Duration.ofSeconds(15),x->{},x->{});
        for(String name:List.of("running","initialPollComplete")){
            var field=RebalanceLiveRunner.class.getDeclaredField(name);field.setAccessible(true);((AtomicBoolean)field.get(r)).set(true);
        }
        r.onSubscribed();return r;
    }
    OrderUpdateEvent update(String id,OrderStatus status,String cumulative){return new OrderUpdateEvent("BTCUSDT",id,"exchange",status,TradeSide.BUY,null,null,new BigDecimal(cumulative),Instant.now());}
    void await(java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test void terminalBeforeExecutionWaitsForEvidenceThenReplacesWithoutPeriodicPoll()throws Exception {
        var f=fixture();var s=f.service();s.restoreActiveCycle(List.of(f.fill()),f.widePair());var q=new Query();
        try(var r=runner(f,s,q)){
            r.onOrderUpdate(update("RBAL_BTCUSDT_BUY",OrderStatus.FILLED,"0.1"));
            await(()->q.calls.get()>0);assertTrue(f.orders.isEmpty());
            FillEvent fill=new FillEvent("actual-fill","BTCUSDT","RBAL_BTCUSDT_BUY",TradeSide.BUY,new BigDecimal("0.1"),new BigDecimal("90"),Instant.now());
            q.fills=List.of(fill);r.onExecution(fill);
            r.onOrderUpdate(update("RBAL_BTCUSDT_SELL",OrderStatus.CANCELED,"0"));
            await(()->f.registry.snapshot().stream().filter(o->o.status()==OrderStatus.NEW).count()==2);
            assertEquals(2,f.orders.size());
            int calls=q.calls.get();r.onOrderUpdate(update("RBAL_BTCUSDT_SELL",OrderStatus.CANCELED,"0"));
            Thread.sleep(100);assertEquals(2,f.orders.size());assertEquals(calls,q.calls.get());
        }
    }
    @Test void bothTimeoutCancelsMustBeConfirmedAndSpacingSurvivesImmediateRefresh()throws Exception {
        var f=fixture();var s=f.service();s.restoreActiveCycle(List.of(f.fill()),f.widePair());s.onPoll();assertEquals(2,f.cancels.size());
        var q=new Query();try(var r=runner(f,s,q)){
            r.onOrderUpdate(update("RBAL_BTCUSDT_BUY",OrderStatus.CANCELED,"0"));await(()->q.calls.get()>0);assertTrue(f.orders.isEmpty());
            r.onOrderUpdate(update("RBAL_BTCUSDT_SELL",OrderStatus.CANCELED,"0"));
            await(()->f.registry.snapshot().stream().filter(o->o.status()==OrderStatus.NEW).count()==2);
            f.assertPrices("91.5","108.5");
        }
    }
    @Test void queryFailureCannotRebuildFromUnreconciledTerminalEvidence()throws Exception {
        var f=fixture();var s=f.service();s.restoreActiveCycle(List.of(f.fill()),f.widePair());s.onPoll();var q=new Query();q.fail=true;
        try(var r=runner(f,s,q)){
            r.onOrderUpdate(update("RBAL_BTCUSDT_BUY",OrderStatus.CANCELED,"0"));
            r.onOrderUpdate(update("RBAL_BTCUSDT_SELL",OrderStatus.CANCELED,"0"));await(()->q.calls.get()>0);
            Thread.sleep(350);assertTrue(f.orders.isEmpty());
        }
    }
    @Test void originalPartialOrderRemainderMustFinishBeforeReplacement()throws Exception {
        var f=fixture();var s=f.service();var pair=f.widePair();s.restoreActiveCycle(List.of(f.fill()),pair);var q=new Query();
        var first=new FillEvent("part1","BTCUSDT","RBAL_BTCUSDT_BUY",TradeSide.BUY,new BigDecimal("0.05"),new BigDecimal("90"),Instant.now());
        var second=new FillEvent("part2","BTCUSDT","RBAL_BTCUSDT_BUY",TradeSide.BUY,new BigDecimal("0.05"),new BigDecimal("90"),Instant.now());
        try(var r=runner(f,s,q)){
            r.onExecution(first);q.fills=List.of(first);q.open=List.of(pair.stream().filter(o->o.side()==TradeSide.BUY).findFirst().orElseThrow());
            r.onOrderUpdate(update("RBAL_BTCUSDT_SELL",OrderStatus.CANCELED,"0"));
            await(()->q.calls.get()>0);Thread.sleep(100);assertTrue(f.orders.isEmpty());
            q.open=List.of();q.fills=List.of(first,second);
            r.onOrderUpdate(update("RBAL_BTCUSDT_BUY",OrderStatus.FILLED,"0.1"));
            await(()->f.registry.snapshot().stream().filter(o->o.status()==OrderStatus.NEW).count()==2);
            assertEquals(2,f.orders.size());
        }
    }

    @Test void unrelatedAndDuplicateTerminalEventsDoNotWakeTrading()throws Exception {
        var f=fixture();var s=f.service();s.restoreActiveCycle(List.of(f.fill()),f.widePair());var q=new Query();
        try(var r=runner(f,s,q)){
            r.onOrderUpdate(update("RBAL_UNKNOWN",OrderStatus.CANCELED,"0"));
            r.onOrderUpdate(new OrderUpdateEvent("ETHUSDT","RBAL_BTCUSDT_BUY","x",OrderStatus.CANCELED,TradeSide.BUY,null,null,BigDecimal.ZERO,Instant.now()));
            Thread.sleep(100);assertEquals(0,q.calls.get());assertTrue(f.orders.isEmpty());
        }
    }
}
