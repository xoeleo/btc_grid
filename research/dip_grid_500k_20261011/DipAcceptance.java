package com.btcgrid.app;

import com.btcgrid.common.model.*;
import java.math.BigDecimal;
import java.nio.file.Path;

/** Synthetic event tests of strategy behavior, distinct from historical performance. */
public final class DipAcceptance {
    static final long T=1727827200000L;
    static BigDecimal n(String s){return new BigDecimal(s);}
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
    static void equal(BigDecimal a,BigDecimal b,String m){check(a.compareTo(b)==0,m+": "+a+" vs "+b);}
    static DipGridBacktest sim(String name,String interval)throws Exception{
        return new DipGridBacktest(Path.of("research/dip_grid_500k_20261011"),name,"OLHC",interval);
    }
    static void point(BaselineReplay b,int seconds,String price){b.point(T+seconds*1000,n(price),n(price),false,true);}
    static void close(DipGridBacktest b)throws Exception{
        b.trades.close();b.cycleCsv.close();b.fundingCsv.close();b.equityCsv.close();
        b.bandCsv.close();b.controlsCsv.close();b.extraCycleCsv.close();b.contextCsv.close();
    }
    public static void main(String[] args)throws Exception{
        DipBands b=new DipBands(n("100000"),n("0.05"));
        check(b.reached(n("95000.1"))==0,"First band is inclusive at exactly 5%");
        b.deepest=b.reached(n("95000"));equal(b.node(),n("95000"),"5% node");equal(b.recovery(),n("97500"),"5% midpoint");
        b.deepest=b.reached(n("90000"));equal(b.node(),n("90000"),"10% is additive, not compounded");equal(b.recovery(),n("95000"),"10% midpoint");
        check(b.reached(n("98000"))==2,"Rebound never forgets deepest band");
        check(b.reached(n("84000"))==3,"Gap advances across multiple bands");
        DipBands custom=new DipBands(n("100000"),n("0.025"));custom.deepest=custom.reached(n("95000"));
        check(custom.deepest==2,"Custom interval");equal(custom.recovery(),n("97500"),"Custom interval midpoint");
        boolean invalid=false;try{new DipBands(n("100000"),n("0"));}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"Reject zero interval");

        var nativeBefore=new BaselineReplay(Path.of("research/dip_grid_500k_20261011"),"acceptance_native","OLHC","0.00014","0.00035","1","0.01","0.02");
        var pre=sim("acceptance_before_first_band","0.05");
        for(int sec:new int[]{0,15,30,45,60}){
            String px=sec==0?"100000":"101100";point(nativeBefore,sec,px);point(pre,sec,px);
            equal(pre.q,nativeBefore.q,"Before first band inventory equals baseline");
            equal(pre.equity(),nativeBefore.equity(),"Before first band economic behavior equals baseline");
            check(pre.halfEvents==nativeBefore.halfEvents&&pre.fullEvents==nativeBefore.fullEvents,"Original half/full controls before first band");
        }
        check(pre.cycles==1&&pre.halfEvents==1&&pre.fullEvents==1,"Original half and full exit preserved");
        nativeBefore.trades.close();nativeBefore.cycleCsv.close();nativeBefore.fundingCsv.close();nativeBefore.equityCsv.close();close(pre);

        var s=sim("acceptance_locked_bands","0.05");point(s,0,"100000");
        s.time=java.time.Instant.ofEpochMilli(T+15000);s.spot=n("95000");s.mark=s.spot;
        BigDecimal oldQty=s.q,oldValue=s.value,oldFees=s.fees;s.controlAtPrice();
        check(s.active()&&s.bands.deepest==1&&!s.usesWater(),"Switch to dip mode at first band");
        equal(s.q,oldQty,"Rebase doesn't add or sell BTC");equal(s.value,oldValue,"Rebase retains actual cost");equal(s.fees,oldFees,"Rebase retains all fees");equal(s.floor,oldQty,"Carried BTC locked");
        check(s.open.values().stream().noneMatch(o->o.intent.side()==TradeSide.SELL),"No grid sells when only locked inventory remains");
        point(s,30,"94000");check(s.q.compareTo(s.floor)>0,"Grid adds within active interval");
        for(var o:s.open.values())if(o.intent.side()==TradeSide.SELL)check(o.intent.qty().compareTo(s.q.subtract(s.floor))<=0,"Grid sells only new BTC");
        point(s,45,"95000.1");check(s.paused&&s.open.isEmpty(),"Above node cancels all grid orders");
        BigDecimal held=s.q;long fills=s.fills;point(s,60,"96000");equal(s.q,held,"No trading above node");check(s.fills==fills,"Paused fill count fixed");
        point(s,75,"94500");check(!s.paused&&!s.open.isEmpty(),"Below node resumes grid");
        point(s,90,"90000");check(s.bands.deepest==2,"Next 5% interval");equal(s.floor,s.q,"New interval locks all carried BTC");
        point(s,105,"95000");check(s.cycles==0,"Exactly midpoint is not above it");
        point(s,120,"95000.1");check(s.cycles==0,"Midpoint alone cannot exit below water plus 1%");
        BigDecimal threshold=s.exitThreshold();
        s.spot=threshold;s.mark=threshold;check(!s.canExit(),"Threshold compared to executable price after slippage");
        point(s,135,"110000");check(s.cycles==1&&s.q.signum()==0&&s.dipExits==1,"Both conditions liquidate all locked and new BTC");
        check(s.closedPnl.signum()>0,"Clearance net profit includes exit fee");
        point(s,150,"110000");check(s.bands.deepest==0&&s.floor.signum()==0&&s.q.compareTo(n("0.030"))==0,"Next cycle resets node and resumes original water regime");
        close(s);

        var path=sim("acceptance_intrabar","0.05");point(path,0,"100000");
        path.segment(T,T+20000,n("100000"),n("89000"),n("100000"),n("89000"));
        check(path.bands.deepest==2,"Intrabar crossings observe each band causally");
        path.segment(T+20000,T+40000,n("89000"),n("95001"),n("89000"),n("95001"));
        check(path.paused&&path.open.isEmpty(),"Intrabar rise cancels before above-node grid sell can execute");
        close(path);
        System.out.println("PASS: exact/additive/custom bands, causal crossings, native pre-band behavior, cost-preserving rebase, locked floor, paused grid, resume, strict midpoint plus executable water+1%, positive net liquidation, next-cycle reset");
    }
}
