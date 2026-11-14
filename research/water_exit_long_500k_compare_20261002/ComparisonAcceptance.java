package com.btcgrid.app;
import java.math.BigDecimal;
import java.nio.file.Path;

public class ComparisonAcceptance {
    static final long T=1727827200000L;
    static BigDecimal n(String p){return new BigDecimal(p);}
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
    static LongCapitalBacktest sim(String name,String half,String full)throws Exception{
        return new LongCapitalBacktest(Path.of("research/water_exit_long_500k_compare_20261002"),name,"OLHC","0.00014","0.00035","1",half,full);
    }
    static void point(LongCapitalBacktest b,int sec,String p){b.point(T+sec*1000,n(p),n(p),false,true);}
    static void close(LongCapitalBacktest b)throws Exception{b.trades.close();b.cycleCsv.close();b.equityCsv.close();b.fundingCsv.close();}
    public static void main(String[] args)throws Exception{
        var a=sim("acceptance_12","0.01","0.02");var b=sim("acceptance_25","0.02","0.05");
        for(var s:new LongCapitalBacktest[]{a,b}){
            point(s,0,"100000");check(s.initial.compareTo(n("500000"))==0&&s.q.compareTo(n("0.030"))==0,"500k and five cells");
            point(s,15,"101100");check(s.water.frozen(),"both pause on profit before half threshold");
        }
        BigDecimal beforeA=a.q,beforeB=b.q;
        point(a,30,"101100");point(b,30,"101100");
        check(a.q.compareTo(beforeA.divide(n("2")))==0,"1% halves");
        check(b.q.compareTo(beforeB)==0&&b.water.phase().equals("PAUSED"),"2% does not half at 1.1%");
        point(a,45,"101100");point(a,60,"101100");check(a.q.signum()==0&&a.cycles==1,"dynamic 2% clears and reconciles");
        point(b,45,"101100");point(b,60,"102200");
        check(b.q.compareTo(beforeB.divide(n("2")))==0,"2% halves");
        point(b,75,"102200");check(b.q.signum()>0&&b.water.phase().equals("PAUSED"),"updated water below 5% holds remaining half");
        point(b,90,"104000");point(b,105,"104000");check(b.q.signum()==0&&b.cycles==1,"5% clears and reconciles");
        point(b,120,"104000");check(b.q.compareTo(n("0.030"))==0,"restart always long");
        check(a.freeMargin().signum()>0&&b.freeMargin().signum()>0,"funded inventory");close(a);close(b);
        System.out.println("PASS: 500k, constant grid sizing, profit pause, distinct 1%/2% and 2%/5% thresholds, updated water, full-fill reconciliation, always-long restart");
    }
}
