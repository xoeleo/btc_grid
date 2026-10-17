package com.btcgrid.app;
import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;

public class ThresholdAcceptance {
    static final long T=1727827200000L;
    static BigDecimal n(String x){return new BigDecimal(x);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static ThresholdWaterBacktest sim(String name)throws Exception{
        return new ThresholdWaterBacktest(Path.of("research/water_exit_threshold_200k_20261002"),name,"OLHC","0.00014","0.00035","1",true);
    }
    static void point(ThresholdWaterBacktest s,int sec,String p){s.point(T+sec*1000,n(p),n(p),false,true);}
    static void close(ThresholdWaterBacktest s)throws Exception{s.trades.close();s.cycleCsv.close();s.equityCsv.close();s.fundingCsv.close();}
    public static void main(String[] args)throws Exception{
        var a=sim("acceptance_threshold");point(a,0,"100000");check(a.cycleStart==null,"equal threshold waits");
        point(a,15,"99000");check(a.q.compareTo(n("0.030"))==0&&a.cycleSign==1,"below threshold starts long");
        point(a,30,"100100");check(a.cycleSign==1,"crossing does not reverse active long");close(a);
        var b=sim("acceptance_short");point(b,0,"120000");check(b.q.compareTo(n("-0.030"))==0,"above threshold starts short");
        point(b,15,"118500");BigDecimal before=b.q.abs();point(b,30,"118500");
        check(b.water.phase().equals("HALF")&&b.q.abs().compareTo(before.subtract(b.spec.roundQtyDown(before.divide(n("2")))))==0,"short one percent buys back half");
        check(ReplayWaterExit.rate(b.summary(),b.mark).compareTo(n("0.02"))>=0,"short half fill updates water");
        point(b,45,"118500");check(b.q.signum()==0,"short two percent buys back all");
        point(b,60,"118500");check(b.cycles==1&&b.shortCycles==1,"short cycle ends after fill reconciliation");
        point(b,75,"99000");check(b.q.signum()>0&&b.cycleSign==1,"next cycle selects new direction");close(b);
        var c=sim("acceptance_margin_funding");point(c,0,"120000");
        c.place(new OrderIntent("BTCUSDT","RBAL_EXTRA_TEST",TradeSide.SELL,OrderType.MARKET,n("1.5"),null,false,"capacity-test"));c.marketable();
        check(c.q.abs().compareTo(n("1.530"))==0,"funded short add");
        long blocked=c.blocked;
        c.place(new OrderIntent("BTCUSDT","RBAL_CAP_TEST",TradeSide.SELL,OrderType.MARKET,n("2"),null,false,"capacity-test"));
        check(c.blocked==blocked+1,"short sale proceeds do not create borrowing capacity");
        c.point(T+15000,n("130000"),n("130000"),false,false);
        check(c.fundingCoverEvents>0&&c.freeMargin().signum()>=0,"rising short price triggers one-times exposure cover");
        BigDecimal fees=c.fees;
        c.funding.put(T+30000,n("0.0001"));c.point(T+30000,n("130000"),n("130000"),true,false);
        check(c.fees.compareTo(fees)<0,"positive funding credited to short");
        check(c.q.signum()<0&&c.freeMargin().signum()>=0,"short direction and margin conserved");close(c);
        System.out.println("PASS: strict threshold, direction locked, short 1% half / 2% full, next-cycle direction, short capacity, mark-margin cover, signed funding");
    }
}
