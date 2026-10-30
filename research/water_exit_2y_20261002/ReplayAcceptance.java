package com.btcgrid.app;

import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;

/** Small acceptance paths test lifecycle, dynamic thresholds, funding, and the cash boundary. */
public class ReplayAcceptance {
    static final long T=1727827200000L;
    static BigDecimal n(String s){return new BigDecimal(s);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void point(WaterGridBacktest b,int sec,String price){b.point(T+sec*1000,n(price),n(price),false,true);}
    static WaterGridBacktest sim(String name)throws Exception{
        return new WaterGridBacktest(Path.of("research/water_exit_2y_20261002/smoke"),name,"OLHC","0.00014","0.00035","1");
    }
    static void close(WaterGridBacktest b)throws Exception{b.trades.close();b.cycleCsv.close();b.equityCsv.close();b.fundingCsv.close();}
    public static void main(String[] args)throws Exception{
        WaterGridBacktest a=sim("acceptance_pause_resume");
        point(a,0,"100000");check(a.q.compareTo(n("0.030"))==0,"five initial cells");
        point(a,15,"100500");point(a,30,"100500");
        check(a.water.phase().equals("PAUSED")&&a.open.isEmpty(),"profit cancels and pauses grid");
        point(a,45,"99000");check(a.water.phase().equals("GRID")&&!a.open.isEmpty(),"below water resumes grid");
        long blocked=a.blocked;
        a.place(new OrderIntent("BTCUSDT","RBAL_CAPACITY_TEST",TradeSide.BUY,OrderType.LIMIT,n("2"),n("100000"),false,"capacity-test"));
        check(a.blocked==blocked+1&&!a.open.containsKey("RBAL_CAPACITY_TEST"),"unfunded buy rejected");close(a);

        WaterGridBacktest b=sim("acceptance_half_full");
        point(b,0,"100000");point(b,15,"101100");
        BigDecimal before=b.q;
        point(b,30,"101100");
        BigDecimal expected=before.subtract(b.spec.roundQtyDown(before.divide(n("2"))));
        check(b.q.compareTo(expected)==0&&b.water.phase().equals("HALF"),"one percent halves net inventory");
        BigDecimal newRate=ReplayWaterExit.rate(b.summary(),b.mark);
        check(newRate.compareTo(n("0.02"))>=0,"half fill updates water above full threshold");
        point(b,45,"101100");check(b.q.signum()==0&&b.water.phase().equals("EXITING"),"two percent clears at updated water");
        point(b,60,"101100");check(b.cycles==1&&b.cycleStart==null&&b.open.isEmpty(),"cycle ends after terminal fill evidence");
        point(b,75,"101100");check(b.q.compareTo(n("0.030"))==0&&b.cycleStart!=null,"next poll starts new cycle");
        BigDecimal qBefore=b.q,feesBefore=b.fees,waterBefore=ReplayWaterExit.water(b.summary());
        b.funding.put(T+90000,n("0.0001"));
        b.point(T+90000,n("101100"),n("101100"),true,false);
        BigDecimal expectedFunding=qBefore.multiply(n("101100")).multiply(n("0.0001"));
        check(b.fees.subtract(feesBefore).compareTo(expectedFunding)==0,"funding charged on settlement inventory");
        check(ReplayWaterExit.water(b.summary()).compareTo(waterBefore)>0,"funding updates dynamic water");
        check(b.equity().subtract(b.q.multiply(b.mark)).signum()>=0,"fully funded cash");close(b);
        System.out.println("PASS: initial sizing, pause, resume, capacity rejection, 1% half, updated 2% full, terminal completion, restart, funding water");
    }
}
