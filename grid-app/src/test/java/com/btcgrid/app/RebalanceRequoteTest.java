package com.btcgrid.app;

import com.btcgrid.bybit.adapter.*;
import com.btcgrid.common.indicator.IndicatorCalculator;
import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceRequoteTest {
    @TempDir Path dir;
    static BigDecimal n(String s){return new BigDecimal(s);}
    final Instant start=Instant.now().minusSeconds(3600);
    final EventLogger log=new EventLogger(){public void info(String e,Map<String,Object> p){}public void warn(String e,Map<String,Object> p){}public void error(String e,Map<String,Object> p){}};
    class Market extends BybitSdkMarketDataGateway {
        Instant now=Instant.now();BigDecimal price=n("100");
        Market(){super(null,ProductCategory.LINEAR,(IndicatorCalculator)null,20,20,n("2"));}
        public InstrumentSpec loadInstrumentSpec(String s){return new InstrumentSpec(s,n("0.001"),n("0.001"),n("0.1"),n("0.1"));}
        public MarketSnapshot latestSnapshot(String s){return new MarketSnapshot(s,now,price,new IndicatorSnapshot(now,n("100"),n("200"),n("100"),n("50")));}
    }
    final Market market=new Market();
    final WorkingOrderRegistry registry=new WorkingOrderRegistry();
    final List<OrderIntent> orders=new ArrayList<>();
    final List<String> cancels=new ArrayList<>();
    final BybitTradeGateway trade=new BybitTradeGateway(){
        public BybitOrderSubmission submit(OrderIntent o){orders.add(o);return new BybitOrderSubmission(o.clientOrderId(),o.clientOrderId());}
        public void cancel(String symbol,String id){cancels.add(id);}
    };
    RebalanceService service(){
        RebalanceSettings settings=new RebalanceSettings(true,ProductCategory.LINEAR,"BTCUSDT","UNIFIED","BTC","USDT",
                List.of(new RebalanceBand(BigDecimal.ZERO,BigDecimal.ONE)),n("0.01"),n("0.01"),n("0.85"),n("0.01"),n("5"),n("0.1"),n("5"),n("0.001"),n("0.001"),Duration.ofMinutes(15),1,dir.resolve("config").toString(),dir.resolve("state.json").toString());
        return new RebalanceService(settings,market,(a,b,q,c,s)->new AccountAssetSnapshot(market.now,a,b,q,n("0.4"),n("50"),n("0.1"),n("0.5"),n("100")),trade,registry,new FileRebalanceStateStore(dir.resolve("state.json")),log,new AlertService(log));
    }
    FillRecord fill(){return new FillRecord(start,"RBAL:BTCUSDT","BUY",n("0.1"),n("100"),n("10"),BigDecimal.ZERO,"USDT",n("0.1"),n("10"),BigDecimal.ZERO,n("0.1"),n("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"RBAL_BTCUSDT_I_test","initial","{\"targetRatio\":1}");}
    List<WorkingOrder> widePair(){
        List<WorkingOrder> pair=new ArrayList<>();
        for(TradeSide side:TradeSide.values()){
            WorkingOrder o=new WorkingOrder("BTCUSDT","RBAL_BTCUSDT_"+side,"exchange-"+side,side,OrderType.LIMIT,side==TradeSide.BUY?n("90"):n("110"),n("0.1"),false,OrderStatus.NEW,market.now.minusSeconds(1000),market.now.minusSeconds(1000));
            registry.trackRemote(o);pair.add(o);
        }
        return pair;
    }
    void confirm(){for(String id:new ArrayList<>(cancels))registry.applyUpdate(new OrderUpdateEvent("BTCUSDT",id,"exchange",OrderStatus.CANCELED,TradeSide.BUY,null,null,BigDecimal.ZERO,market.now));}
    void assertPrices(String buy,String sell){
        List<OrderIntent> tail=orders.subList(orders.size()-2,orders.size());
        assertEquals(0,n(buy).compareTo(tail.stream().filter(o->o.side()==TradeSide.BUY).findFirst().orElseThrow().price()));
        assertEquals(0,n(sell).compareTo(tail.stream().filter(o->o.side()==TradeSide.SELL).findFirst().orElseThrow().price()));
    }
    @Test void timeoutWaitsForBothCancelsThenShrinksAndContinuesShrinking(){
        RebalanceService s=service();s.restoreActiveCycle(List.of(fill()),widePair());s.onPoll();
        assertEquals(2,cancels.size());assertTrue(orders.isEmpty());
        s.onPoll();assertTrue(orders.isEmpty());assertEquals(2,cancels.size());
        confirm();s.onPoll();assertPrices("91.5","108.5");
        assertTrue(orders.stream().allMatch(o->o.reason().startsWith("rebalance-timeout-requote")));
        market.now=market.now.plusSeconds(901);s.onPoll();assertEquals(2,orders.size());
        confirm();s.onPoll();assertPrices("92.8","107.2");
        for(int i=0;i<18;i++){
            market.now=market.now.plusSeconds(901);s.onPoll();confirm();s.onPoll();
        }
        assertPrices("99","101");
    }
    @Test void restartDuringCancellationRetainsSpacingAndUsesFreshMarket(){
        RebalanceService s=service();s.restoreActiveCycle(List.of(fill()),widePair());s.onPoll();
        assertTrue(Files.exists(dir.resolve("state.json.requote.json")));confirm();
        RebalanceService restarted=service();restarted.restoreActiveCycle(List.of(fill()),List.of());
        market.price=n("109");restarted.onPoll();assertPrices("91.5","109");
        assertFalse(Files.exists(dir.resolve("state.json.requote.json")));
    }
    @Test void cancellationFillInvalidatesOldSpacingAndUsesNewFill(){
        RebalanceService s=service();s.restoreActiveCycle(List.of(fill()),widePair());s.onPoll();
        s.onExecution(new FillEvent("late","BTCUSDT","RBAL_BTCUSDT_BUY",TradeSide.BUY,n("0.01"),n("90"),market.now),"test");
        assertTrue(orders.isEmpty());assertFalse(Files.exists(dir.resolve("state.json.requote.json")));
        confirm();market.price=n("90");s.onPoll();assertPrices("89.1","90.9");
        assertTrue(orders.stream().noneMatch(o->o.reason().startsWith("rebalance-timeout-requote")));
    }
}
