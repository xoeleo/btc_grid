package com.btcgrid.app;
import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RebalanceStrategyLedgerTest {
    FillEvent fill(String id,String order,String qty,String price){return new FillEvent(id,"BTCUSDT",order,TradeSide.BUY,new BigDecimal(qty),new BigDecimal(price),Instant.now());}
    @Test void splitExecutionsPreservePreviousOrderPriceAndCountEveryCoin(){
        var l=new RebalanceStrategyLedger("BTCUSDT");
        l.applyFill(fill("init","RBAL_BTCUSDT_I_test","1","68642.8"));
        l.applyFill(fill("a","RBAL_BTCUSDT_PB_order1","0.006","68505.4"));
        var before=l.snapshot();l.applyFill(fill("b","RBAL_BTCUSDT_PB_order1","0.006","68505.4"));
        var after=l.snapshot();assertEquals(new BigDecimal("1.012"),after.netQty());
        assertEquals(before.previousFillPrice(),after.previousFillPrice());assertEquals(before.consecutiveBuyFills(),after.consecutiveBuyFills());
        assertEquals(new BigDecimal("137.4"),after.previousFillPrice().subtract(after.lastFillPrice()));
        l.applyFill(fill("c","RBAL_BTCUSDT_PB_order2","0.005","68436.9"));
        l.applyFill(fill("d","RBAL_BTCUSDT_PB_order2","0.001","68436.9"));
        assertEquals(new BigDecimal("68505.4"),l.snapshot().previousFillPrice());assertEquals(2,l.snapshot().consecutiveBuyFills());
        assertEquals(new BigDecimal("1.018"),l.snapshot().netQty());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    @Test void cycleRestoreGroupsExecutionFragmentsByOrder() throws Exception {
        var f=new RebalanceRequoteTest();f.dir=dir;var s=f.service();var start=f.start;
        s.configureContinuation(new RebalanceCycleContinuation("end","RBAL:BTCUSDT",start,start,
                start.plusMillis(1),new BigDecimal("100"),BigDecimal.ONE,new BigDecimal("50"),new BigDecimal("200"),false,true),x->{});
        var a=new FillRecord(start.plusSeconds(1),"RBAL:BTCUSDT","BUY",new BigDecimal("0.006"),new BigDecimal("90"),new BigDecimal("0.54"),BigDecimal.ZERO,"USDT",
                new BigDecimal("0.106"),new BigDecimal("10.54"),BigDecimal.ZERO,new BigDecimal("0.106"),new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"RBAL_BTCUSDT_PB_partial","part-a","{}");
        var b=new FillRecord(start.plusSeconds(2),"RBAL:BTCUSDT","BUY",new BigDecimal("0.006"),new BigDecimal("90"),new BigDecimal("0.54"),BigDecimal.ZERO,"USDT",
                new BigDecimal("0.112"),new BigDecimal("11.08"),BigDecimal.ZERO,new BigDecimal("0.112"),new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"RBAL_BTCUSDT_PB_partial","part-b","{}");
        s.restoreActiveCycle(java.util.List.of(f.fill(),a,b),java.util.List.of());
        var field=RebalanceService.class.getDeclaredField("rebalanceLedger");field.setAccessible(true);var ledger=(RebalanceStrategyLedger)field.get(s);
        assertEquals(2,ledger.snapshot().consecutiveBuyFills());assertEquals(new BigDecimal("100"),ledger.snapshot().previousFillPrice());
        ledger.applyFill(fill("c","RBAL_BTCUSDT_PB_partial","0.001","89.9"));
        assertEquals(2,ledger.snapshot().consecutiveBuyFills());assertEquals(new BigDecimal("100"),ledger.snapshot().previousFillPrice());
    }
    @Test void restartCanSeedPartiallyExecutedReferenceOrder(){
        var l=new RebalanceStrategyLedger("BTCUSDT");l.applyFill(fill("init","RBAL_BTCUSDT_I_test","1","68642.8"));
        l.applyFill(fill("a","RBAL_BTCUSDT_PB_order1","0.006","68505.4"));
        var restored=new RebalanceStrategyLedger("BTCUSDT");restored.restore(l.snapshot());restored.restoreReferenceOrder("RBAL_BTCUSDT_PB_order1");
        restored.applyFill(fill("b","RBAL_BTCUSDT_PB_order1","0.006","68505.3"));
        assertEquals(new BigDecimal("68642.8"),restored.snapshot().previousFillPrice());assertEquals(1,restored.snapshot().consecutiveBuyFills());
        assertEquals(new BigDecimal("68505.3"),restored.snapshot().lastFillPrice());
    }
}
