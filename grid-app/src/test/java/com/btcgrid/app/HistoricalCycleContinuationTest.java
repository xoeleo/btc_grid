package com.btcgrid.app;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalCycleContinuationTest {
    @TempDir Path dir;
    static final String S="RBAL:BTCUSDT";
    static final BigDecimal Z=BigDecimal.ZERO;
    final Instant start=Instant.parse("2026-11-14T00:00:00Z");
    BigDecimal n(String s){return new BigDecimal(s);}
    FillRecord row(int seconds,String side,String qty,String price,String order,String id,String metadata){
        return new FillRecord(start.plusSeconds(seconds),S,side,n(qty),n(price),n(qty).multiply(n(price)),Z,"USDT",Z,Z,Z,Z,Z,Z,Z,Z,order,id,metadata);
    }
    SqliteFillStore seed(){
        var s=new SqliteFillStore(dir.resolve("ledger.sqlite"));
        s.save(row(0,"BUY","1.532","100","RBAL_BTCUSDT_I_old","old-init","{\"startRatio\":0.8}"));
        s.save(row(10,"CYCLE_END","0","100","CYCLE_END_old","old-end","{\"targetRatio\":1,\"endReason\":\"目标资产比例达到，撤单完成，Cycle 结束并保留仓位\"}"));
        s.save(row(20,"BUY","0.03","110","RBAL_BTCUSDT_I_new","new-init","{}"));
        s.save(row(25,"FUNDING_FEE","0.03","110","FUNDING_FEE_new","new-funding","{}"));
        s.save(row(30,"CYCLE_END","0","110","CYCLE_END_new","new-end","{\"targetRatio\":1}"));
        return s;
    }
    RebalanceCycleContinuation resume(String end,int paused,int resumed){return new RebalanceCycleContinuation(end,S,start,start.plusSeconds(paused),start.plusSeconds(resumed),n("100"),BigDecimal.ONE,n("50"),n("200"),false,false);}
    @Test void selectedOldCycleStaysSeparateAcrossNewFillsPaginationFundingAndRestart(){
        var s=seed();var other=s.cycleFills("new-end");var otherEnd=s.cycleEndRecord("new-end");
        String before=s.continuationRevision(S);
        s.continueCycle(resume("old-end",10,40));
        assertNotEquals(before,s.continuationRevision(S));
        assertEquals(List.of("old-init"),s.currentCycleFills(S).stream().map(FillRecord::executionId).toList());
        assertTrue(s.excludedCycleExecutions(S).contains("new-init"));
        s.markContinuationReady("old-end");
        s.save(row(50,"SELL","0.1","120","RBAL_BTCUSDT_PS_resume","resumed-sell","{}"));
        FillRecord sell=s.currentCycleFills(S).get(1);
        assertEquals(0,n("1.432").compareTo(sell.cumulativeQty()));
        assertEquals(0,n("141.2").compareTo(sell.cumulativeValue()));
        assertEquals(other,s.cycleFills("new-end"));assertEquals(otherEnd,s.cycleEndRecord("new-end"));
        var page=s.currentCycleFillsPage(S,1,0,null,null);
        assertEquals(2,page.total());assertEquals("resumed-sell",page.items().get(0).fill().executionId());
        assertEquals(0,n("100").compareTo(page.items().get(0).previousTradingPrice()));
        assertEquals(0,n("165.2").compareTo(page.items().get(0).cumulativeTurnover()));
        assertEquals(0,s.currentCycleFundingFeesPage(S,10,0).total());
        s=new SqliteFillStore(dir.resolve("ledger.sqlite"));
        assertEquals(2,s.currentCycleFills(S).size());assertTrue(s.latestContinuation(S).ready());
        s.save(row(60,"CYCLE_END","0","100","CYCLE_END_resumed","resumed-end","{\"targetRatio\":1}"));
        assertTrue(s.currentCycleFills(S).isEmpty());assertEquals(2,s.cycleFills("resumed-end").size());
        assertEquals(other,s.cycleFills("new-end"));
        assertEquals(2,s.recentCycles(S,10).size());
        // Resume the same old root again, not the intervening .03 Cycle.
        s.continueCycle(resume("resumed-end",60,70));
        assertEquals(2,s.currentCycleFills(S).size());
        s.save(row(80,"BUY","0.01","90","RBAL_BTCUSDT_PB_again","again","{}"));
        assertEquals(0,n("1.442").compareTo(s.currentCycleFills(S).get(2).cumulativeQty()));
        assertEquals(other,s.cycleFills("new-end"));
    }
    @Test void checkpointsKeepOriginalCutoffAndForeignDuplicatesCannotStealOwnership(){
        var s=seed();s.continueCycle(resume("old-end",10,40));
        s.save(row(50,"SELL","0.1","120","RBAL_BTCUSDT_PS_resume","resumed-sell","{}"));
        assertEquals(1,s.cycleFills("old-end").size());
        var foreign=s.cycleFills("new-end").get(0);s.save(foreign);
        assertEquals(2,s.currentCycleFills(S).size());
        assertThrows(IllegalStateException.class,()->s.continueCycle(resume("new-end",30,60)));
        assertThrows(IllegalStateException.class,()->s.save(row(55,"BUY","0.03","100","RBAL_BTCUSDT_I_wrong","wrong-new","{}")));
        assertEquals(2,s.currentCycleFills(S).size());
    }
    @Test void unarchivedTradeAndWrongOriginalStartAreRejectedAtomically(){
        var s=seed();var wrong=new RebalanceCycleContinuation("old-end",S,start.plusSeconds(1),start.plusSeconds(10),start.plusSeconds(40),n("100"),BigDecimal.ONE,n("50"),n("200"),false,false);
        assertThrows(IllegalStateException.class,()->s.continueCycle(wrong));assertNull(s.latestContinuation(S));
        s.save(row(35,"SELL","0.001","100","RBAL_BTCUSDT_PS_late","late","{}"));
        assertThrows(IllegalStateException.class,()->s.continueCycle(resume("old-end",10,40)));
        assertNull(s.latestContinuation(S));
    }
}
