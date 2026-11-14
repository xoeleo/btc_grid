package com.btcgrid.app;

import com.btcgrid.bybit.adapter.*;
import com.btcgrid.common.indicator.IndicatorCalculator;
import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceContinuationTest {
    @TempDir Path dir;
    static final String SYMBOL="RBAL:BTCUSDT";
    static final BigDecimal Z=BigDecimal.ZERO;
    static BigDecimal n(String v){return new BigDecimal(v);}
    final Instant start=Instant.now().minusSeconds(86400);
    final Instant endTime=Instant.now().minusSeconds(600);

    FillRecord fill(Instant time,String side,String id,String order,String qty,String cumulative,String value){
        return new FillRecord(time,SYMBOL,side,n(qty),n("100"),n(qty).multiply(n("100")),n("0.01"),"USDT",
                n(cumulative),n(value),n("0.02"),n(cumulative),n("100"),Z,Z,Z,order,id,
                "{\"targetRatio\":1,\"endReason\":\"目标资产比例达到，撤单完成，Cycle 结束并保留仓位\"}");
    }
    FillRecord initial(){return fill(start,"BUY","initial","RBAL_BTCUSDT_I_old","0.1","0.1","10");}
    FillRecord end(){return fill(endTime,"CYCLE_END","ended","CYCLE_END_RBAL_old","0","0.1","10");}
    RebalanceCycleContinuation context(){return new RebalanceCycleContinuation("ended",SYMBOL,start,endTime,
            Instant.now(),n("100"),BigDecimal.ONE,n("80"),n("200"),false,false);}
    SqliteFillStore closedStore(){SqliteFillStore s=new SqliteFillStore(dir.resolve("fills.sqlite"));s.save(initial());s.save(end());return s;}

    @Test void resumesLedgerWithoutDeletingHistoryAndSurvivesRestart(){
        SqliteFillStore s=closedStore();
        assertTrue(s.currentCycleFills(SYMBOL).isEmpty());
        s.continueCycle(context());
        assertEquals(1,s.currentCycleFills(SYMBOL).size());
        assertEquals(0,s.recentCycles(SYMBOL,10).size());
        assertEquals(1,s.cycleFills("ended").size()); // immutable old close remains queryable
        assertFalse(s.latestContinuation(SYMBOL).ready());
        s.markContinuationReady("ended");s.rearmContinuationTarget("ended");
        s=new SqliteFillStore(dir.resolve("fills.sqlite"));
        assertEquals(start.toEpochMilli(),s.currentCycle(SYMBOL,Instant.now(),n("110")).startedAt().toEpochMilli());
        assertTrue(s.latestContinuation(SYMBOL).ready());assertTrue(s.latestContinuation(SYMBOL).targetRearmed());
        s.save(fill(Instant.now().plusSeconds(2),"SELL","continued-sell","RBAL_BTCUSDT_PS_new","0.01","0.09","9"));
        s.save(fill(Instant.now().plusSeconds(3),"CYCLE_END","ended-again","CYCLE_END_new","0","0.09","9"));
        assertEquals(2,s.cycleFills("ended-again").size());
        assertEquals(1,s.recentCycles(SYMBOL,10).size());
        assertTrue(s.currentCycleFills(SYMBOL).isEmpty());
    }
    @Test void repeatedClicksAndNewTradingAreRejectedAtomically(){
        SqliteFillStore s=closedStore();s.continueCycle(context());
        assertThrows(IllegalStateException.class,()->s.continueCycle(context()));
        assertEquals(1,s.currentCycleFills(SYMBOL).size());
        SqliteFillStore other=new SqliteFillStore(dir.resolve("other.sqlite"));other.save(initial());other.save(end());
        // Even a late/backdated execution received after the close invalidates it.
        other.save(fill(start.plusSeconds(1),"SELL","late","RBAL_BTCUSDT_PS_late","0.01","0.09","9"));
        assertThrows(IllegalStateException.class,()->other.continueCycle(context()));
        assertNull(other.latestContinuation(SYMBOL));assertEquals(1,other.recentCycles(SYMBOL,10).size());
    }
    @Test void wrongEndAndWrongPriceCannotReopenAnOlderCycle(){
        SqliteFillStore s=closedStore();
        RebalanceCycleContinuation wrong=new RebalanceCycleContinuation("ended",SYMBOL,start,endTime,
                Instant.now(),n("99"),BigDecimal.ONE,n("80"),n("200"),false,false);
        assertThrows(IllegalStateException.class,()->s.continueCycle(wrong));
        s.save(fill(Instant.now(),"CYCLE_END","newer-end","CYCLE_END_newer","0","0.1","10"));
        assertThrows(IllegalStateException.class,()->s.continueCycle(context()));
    }
    @Test void gapUsesEndedReferenceAndNoNewInitialPositionIsOpened(){
        Fixture f=new Fixture();AtomicBoolean committed=new AtomicBoolean();
        f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{});
        assertTrue(committed.get());assertEquals(start,f.service.activeCycleStartedAt());
        assertEquals(0,n("100").compareTo(f.service.gridReferencePrice()));
        assertEquals(2,f.orders.size());
        assertTrue(f.orders.stream().noneMatch(o->o.type()==OrderType.MARKET));
        OrderIntent sell=f.orders.stream().filter(o->o.side()==TradeSide.SELL).findFirst().orElseThrow();
        assertEquals(0,n("110").compareTo(sell.price()));
        assertTrue(sell.qty().compareTo(n("0.01"))>0); // ten grid distances, not one default grid
        assertFalse(f.service.continuation().targetRearmed());
        int count=f.orders.size();
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->{},()->{}));
        assertEquals(count,f.orders.size());
        // Still at 100%: do not immediately close the resumed Cycle.
        f.service.onPoll();assertEquals(0,f.ends.size());
        f.ratio=n("0.9");f.service.onPoll();assertTrue(f.service.continuation().targetRearmed());
        f.ratio=BigDecimal.ONE;f.service.onPoll();assertEquals(1,f.ends.size());
    }
    @Test void outOfRangeOrChangedPositionNeverCommitsOrOrders(){
        Fixture f=new Fixture();f.market.price=n("210");AtomicBoolean committed=new AtomicBoolean();
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{}));
        assertFalse(committed.get());assertTrue(f.orders.isEmpty());
        f.market.price=n("110");f.position=n("0.09");
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{}));
        assertFalse(committed.get());assertTrue(f.orders.isEmpty());
    }
    @Test void failedFundingReconciliationPersistsPendingWithoutPlacingOrders(){
        Fixture f=new Fixture();AtomicBoolean committed=new AtomicBoolean();
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{throw new IllegalStateException("offline");}));
        assertTrue(committed.get());assertFalse(f.service.continuation().ready());
        f.service.onPoll();assertTrue(f.orders.isEmpty());
        f.service.verifyContinuationFunding(()->{});f.service.onPoll();assertFalse(f.orders.isEmpty());
    }
    @Test void preexistingAccountPositionIsNotAddedToCycleAccounting() throws Exception {
        Fixture f=new Fixture();f.position=n("0.2");
        f.service.continueEndedCycle(List.of(initial()),end(),context(),()->{},()->{});
        assertEquals(start,f.service.activeCycleStartedAt());
        var field=RebalanceService.class.getDeclaredField("rebalanceLedger");field.setAccessible(true);
        var ledger=(com.btcgrid.ledger.service.StrategyLedger)field.get(f.service);
        assertEquals(0,n("0.1").compareTo(ledger.snapshot().netQty()));
        assertTrue(f.orders.stream().filter(o->o.side()==TradeSide.SELL).allMatch(o->o.qty().compareTo(n("0.1"))<=0));
        assertTrue(f.orders.stream().noneMatch(OrderIntent::isMarket));
    }

    @Test void delayedExecutionDuringPendingFundingIsAccountedWithoutFollowupOrders(){
        Fixture f=new Fixture();
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->{},()->{throw new IllegalStateException("offline");}));
        AtomicBoolean recorded=new AtomicBoolean();
        assertTrue(f.service.onExecution(new FillEvent("late-fill","BTCUSDT","RBAL_BTCUSDT_PS_late",TradeSide.SELL,
                n("0.01"),n("110"),Instant.now()),"test",()->recorded.set(true)));
        assertTrue(recorded.get());assertTrue(f.orders.isEmpty());assertFalse(f.service.continuation().ready());
    }

    @Test void restartBeforeFundingVerificationKeepsOrdersBlocked(){
        Fixture f=new Fixture();f.service.configureContinuation(context(),c->{});
        f.service.restoreActiveCycle(List.of(initial()),List.of());
        f.service.onPoll();assertTrue(f.orders.isEmpty());
        assertEquals(0,n("100").compareTo(f.service.gridReferencePrice()));
    }

    @Test void continuationPreservesOriginalConsecutiveFillDirection() throws Exception {
        Fixture f=new Fixture();
        FillRecord first=fill(start,"BUY","a","RBAL_BTCUSDT_I_old","0.04","0.04","4");
        FillRecord second=fill(start.plusSeconds(1),"BUY","b","RBAL_BTCUSDT_PB_old","0.06","0.1","10");
        f.service.continueEndedCycle(List.of(first,second),end(),context(),()->{},()->{});
        var field=RebalanceService.class.getDeclaredField("rebalanceLedger");field.setAccessible(true);
        var ledger=(com.btcgrid.ledger.service.StrategyLedger)field.get(f.service);
        assertEquals(2,ledger.snapshot().consecutiveBuyFills());assertEquals(0,ledger.snapshot().consecutiveSellFills());
    }

    @Test void staleQuotesAndAccountsCannotCommitContinuation(){
        Fixture f=new Fixture();AtomicBoolean committed=new AtomicBoolean();
        f.market.stale=true;
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{}));
        f.market.stale=false;f.staleAccount=true;
        assertThrows(IllegalStateException.class,()->f.service.continueEndedCycle(List.of(initial()),end(),context(),()->committed.set(true),()->{}));
        assertFalse(committed.get());assertTrue(f.orders.isEmpty());
    }
    @Test void leavingRangeAfterContinuationDoesNotAddOrdersOrCloseCycle(){
        Fixture f=new Fixture();f.service.continueEndedCycle(List.of(initial()),end(),context(),()->{},()->{});
        int before=f.orders.size();f.market.price=n("210");f.service.onPoll();
        assertEquals(before,f.orders.size());assertTrue(f.ends.isEmpty());
        assertEquals(start,f.service.activeCycleStartedAt());
    }

    @Test void controllerPreviewBindsRangeAndParametersBeforeOpeningTheLedger() throws Exception {
        Fixture f=new Fixture();SqliteFillStore store=closedStore();
        store.save(fill(endTime.plusSeconds(1),"BUY","other-init","RBAL_BTCUSDT_I_other","0.03","0.03","3"));
        store.save(fill(endTime.plusSeconds(2),"CYCLE_END","other-end","CYCLE_END_other","0","0.03","3"));
        RuntimeSettings runtime=new RuntimeSettings(Duration.ofSeconds(15),false,dir.resolve("state.json").toString(),
                dir.resolve("events.log").toString(),dir.resolve("status.json").toString(),dir.resolve("fills.sqlite").toString(),
                false,"127.0.0.1",0,1,1);
        GridProperties properties=new GridProperties(null,f.settings,new BybitSdkConfig("","",false,false,true,false,5000),runtime,new Properties());
        LiveControlService control=new LiveControlService(properties,null,null,null,null,new HashSet<>(),new RuntimeStatusProvider("",null,null,null,null,null){@Override public RuntimeStatusSnapshot snapshot(){return null;}},f.logger,
                new AlertService(f.logger),new FileStateStore(dir.resolve("state.json")),x->{},new WorkingOrderRegistry(),store);
        RebalanceLiveRunner stub=new RebalanceLiveRunner(f.service,null,new WorkingOrderRegistry(),f.logger,new AlertService(f.logger),Duration.ofSeconds(15)){
            @Override public boolean isRunning(){return true;}
            @Override public void assertContinuationReady(){}
            @Override public void continueEndedCycle(List<FillRecord> fills,FillRecord end,RebalanceCycleContinuation c,Runnable commit){
                f.service.continueEndedCycle(fills,end,c,commit,()->{});
            }
        };
        var field=LiveControlService.class.getDeclaredField("activeRebalanceRunner");field.setAccessible(true);field.set(control,stub);
        f.service.configureContinuation(null,c->{if(c.ready())store.markContinuationReady(c.endExecutionId());if(c.targetRearmed())store.rearmContinuationTarget(c.endExecutionId());});
        new FileRebalanceStateStore(Path.of(f.settings.stateFilePath())).save(new RebalanceState(true,"BTCUSDT","IDLE",BigDecimal.ONE,
                BigDecimal.ONE,n("0.9"),n("0.1"),BigDecimal.ONE,n("110"),n("110"),null,null,null,null,List.of(BigDecimal.ONE),Instant.now(),""));
        RebalancePriceRangeStore range=new RebalancePriceRangeStore(dir.resolve("rebalance-price-range.properties"));
        range.save(new RebalancePriceRangeStore.Range(n("80"),n("200")));
        Map<String,Object> preview=control.previewRebalanceContinuation("ended");
        assertEquals(true,preview.get("ok"),preview.toString());assertEquals(true,preview.get("canContinue"));
        assertFalse(f.orders.size()>0);assertNull(store.latestContinuation(SYMBOL));
        assertEquals(false,control.continueRebalanceCycle("ended","stale").get("ok"));
        assertNull(store.latestContinuation(SYMBOL));
        range.save(new RebalancePriceRangeStore.Range(n("120"),n("200")));
        Map<String,Object> outside=control.continueRebalanceCycle("ended",preview.get("confirmation").toString());
        assertEquals(false,outside.get("canContinue"));assertEquals(false,outside.get("ok"));
        assertNull(store.latestContinuation(SYMBOL));
        range.save(new RebalancePriceRangeStore.Range(n("80"),n("200")));
        Map<String,Object> result=control.continueRebalanceCycle("ended",preview.get("confirmation").toString());
        assertEquals(true,result.get("ok"),result.toString());assertEquals(2,f.orders.size());assertTrue(store.latestContinuation(SYMBOL).ready());
        assertEquals(1,store.cycleFills("other-end").size());
        assertTrue(store.currentCycleFills(SYMBOL).stream().noneMatch(fill->fill.executionId().equals("other-init")));
        assertEquals(false,control.continueRebalanceCycle("ended",preview.get("confirmation").toString()).get("ok"));
    }

    @Test void idleStreamHeartbeatAllowsContinuationButDisconnectAndStaleHeartbeatBlock() throws Exception {
        Fixture f=new Fixture();
        BybitOrderQueryGateway query=new BybitOrderQueryGateway(){
            public List<WorkingOrder> fetchOpenOrders(String symbol){return List.of();}
            public List<FillEvent> fetchRecentExecutions(String symbol,Set<String> ids){return List.of();}
        };
        RebalanceLiveRunner runner=new RebalanceLiveRunner(f.service,null,query,(symbol,from,to)->List.of(),
                new WorkingOrderRegistry(),f.logger,new AlertService(f.logger),Duration.ofSeconds(15),x->{},x->{});
        var running=RebalanceLiveRunner.class.getDeclaredField("running");running.setAccessible(true);
        ((AtomicBoolean)running.get(runner)).set(true);
        assertThrows(IllegalStateException.class,runner::assertContinuationReady);
        runner.onSubscribed();
        var last=RebalanceLiveRunner.class.getDeclaredField("lastPrivateStreamMessageAt");last.setAccessible(true);
        @SuppressWarnings("unchecked") var messageAt=(java.util.concurrent.atomic.AtomicReference<Instant>)last.get(runner);
        messageAt.set(Instant.now().minusSeconds(61));
        assertThrows(IllegalStateException.class,runner::assertContinuationReady);
        runner.onMessageReceived("pong","");
        assertDoesNotThrow(runner::assertContinuationReady);
        // Fresh transport messages cannot undo a lost authenticated subscription.
        var connected=RebalanceLiveRunner.class.getDeclaredField("privateStreamConnected");connected.setAccessible(true);
        ((AtomicBoolean)connected.get(runner)).set(false);
        runner.onMessageReceived("pong","");
        assertThrows(IllegalStateException.class,runner::assertContinuationReady);
        assertTrue(f.orders.isEmpty());
    }

    class Fixture {
        BigDecimal ratio=BigDecimal.ONE,position=n("0.1"); boolean staleAccount;
        Market market=new Market();List<OrderIntent> orders=new ArrayList<>();List<String> ends=new ArrayList<>();
        RebalanceService service; RebalanceSettings settings; EventLogger logger;
        Fixture(){
            logger=new EventLogger(){public void info(String e,Map<String,Object>p){}public void warn(String e,Map<String,Object>p){}public void error(String e,Map<String,Object>p){}};
            settings=new RebalanceSettings(true,ProductCategory.LINEAR,"BTCUSDT","UNIFIED","BTC","USDT",
                    List.of(new RebalanceBand(Z,BigDecimal.ONE)),n("0.01"),n("0.01"),n("0.85"),n("0.01"),
                    n("5"),n("1"),n("10"),n("0.001"),n("0.001"),Duration.ofMinutes(15),1,dir.resolve(UUID.randomUUID()+".properties").toString(),dir.resolve(UUID.randomUUID()+".json").toString());
            service=new RebalanceService(settings,market,(a,b,q,c,s)->new AccountAssetSnapshot(staleAccount?Instant.now().minusSeconds(60):Instant.now(),a,b,q,
                    ratio.subtract(position),Z,position,ratio,market.price),new BybitTradeGateway(){
                public BybitOrderSubmission submit(OrderIntent o){orders.add(o);return new BybitOrderSubmission(o.clientOrderId(),o.clientOrderId());}
                public void cancel(String s,String id){}
            },new WorkingOrderRegistry(),new FileRebalanceStateStore(Path.of(settings.stateFilePath())),logger,new AlertService(logger),ends::add);
            service.configureContinuation(null,c->{});
        }
    }
    static class Market extends BybitSdkMarketDataGateway {
        BigDecimal price=n("110"); boolean stale;
        Market(){super(null,ProductCategory.LINEAR,(IndicatorCalculator)null,20,20,n("2"));}
        public InstrumentSpec loadInstrumentSpec(String s){return new InstrumentSpec(s,n("0.001"),n("0.001"),n("0.1"),n("0.1"));}
        public MarketSnapshot latestSnapshot(String s){Instant now=stale?Instant.now().minusSeconds(60):Instant.now();return new MarketSnapshot(s,now,price,new IndicatorSnapshot(now,price,price,price,price));}
    }
}
