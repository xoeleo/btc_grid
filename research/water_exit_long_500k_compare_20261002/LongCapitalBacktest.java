package com.btcgrid.app;

import com.btcgrid.bybit.adapter.*;
import com.btcgrid.common.model.*;
import com.btcgrid.common.config.*;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.strategy.service.*;
import com.btcgrid.strategy.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Isolated historical replay: native production planner/engine/water controller, simulated exchange only. */
public class LongCapitalBacktest {
    static final BigDecimal ZERO=BigDecimal.ZERO, ONE=BigDecimal.ONE;
    static final ObjectMapper JSON=new ObjectMapper();
    static BigDecimal d(String s) {return new BigDecimal(s);}
    static class SimOrder {
        final OrderIntent intent; final Instant placed;
        boolean aggressive; OrderStatus status=OrderStatus.NEW; List<FillEvent> fills=new ArrayList<>();
        SimOrder(OrderIntent i,Instant t){intent=i;placed=t;}
        WorkingOrder row(){return new WorkingOrder("BTCUSDT",intent.clientOrderId(),"sim",intent.side(),intent.type(),intent.price(),intent.qty(),false,status,placed,placed,intent.reason());}
    }
    final Path root; final String name, pathMode;
    final boolean thresholdMode=false; final BigDecimal halfRate,fullRate; int cycleSign=1; long longCycles=0,shortCycles=0;
    final BigDecimal threshold=d("100000");
    final BigDecimal maker,taker,slippage,initial=d("500000");
    final InstrumentSpec spec=new InstrumentSpec("BTCUSDT",d("0.001"),d("0.001"),d("0.1"),d("0.1"),d("5"));
    GridStrategyConfig config=gridConfig(EntryDirection.LONG_ONLY);
    GridStrategyConfig gridConfig(EntryDirection direction){return new GridStrategyConfig(ProductCategory.LINEAR,"BTCUSDT",direction,
        d("0.001"),d("0.85"),d("0.006"),d("5"),d("0.1"),d("5"),d("5"),100,20,20,d("2"),d("0.01"),d("10"),d("0.30"),true,Duration.ofMinutes(15),Duration.ZERO);}
    final RebalanceSettings settings=new RebalanceSettings(true,ProductCategory.LINEAR,"BTCUSDT","UNIFIED","BTC","USDT",
        List.of(new RebalanceBand(ZERO,ONE)),d("0.01"),d("0.001"),d("0.85"),d("0.006"),d("5"),d("0.1"),d("5"),d("0.001"),d("0.001"),Duration.ofMinutes(15),1,"unused","unused");
    final Map<String,SimOrder> history=new LinkedHashMap<>(), open=new LinkedHashMap<>();
    final Set<String> applied=new HashSet<>(); final WorkingOrderRegistry registry=new WorkingOrderRegistry();
    QuantityRoundingState rounding=QuantityRoundingState.empty();
    InMemoryStrategyLedger ledger; GridStrategyEngine engine; ConfiguredWaterExit water;
    Instant cycleStart,time; BigDecimal spot=ZERO,mark=ZERO,q=ZERO,value=ZERO,fees=ZERO;
    BigDecimal closedPnl=ZERO,totalTrading=ZERO,totalFunding=ZERO,cycleFunding=ZERO,cycleTrading=ZERO;
    BigDecimal peakEquity=d("500000"),maxDD=ZERO,maxDDPct=ZERO,maxQty=ZERO,maxNotional=ZERO,minEquity=d("500000");
    long fundingCoverEvents=0; BigDecimal fundingCoverQty=ZERO,minFreeCash=d("500000");
    long fills=0,cycles=0,halfEvents=0,fullEvents=0,gridFlat=0,blocked=0,points=0,win=0,loss=0,cycleBuys=0,cycleSells=0;
    long lastPoll=Long.MIN_VALUE,lastTime=0,firstTime=0,maxCycleMs=0; double exposureSum=0;
    String lastPhase="NONE"; boolean cycleExitWasFull=false; long prevObserve=0; double prevExposure=0;
    final BufferedWriter trades,cycleCsv,equityCsv,fundingCsv;
    Map<Long,BigDecimal> funding=new HashMap<>();
    Map<String,Double> monthEquity=new LinkedHashMap<>();
    LongCapitalBacktest(Path root,String name,String pathMode,String maker,String taker,String slip,String half,String full) throws Exception {
        this.halfRate=d(half);this.fullRate=d(full);
        this.root=root;this.name=name;this.pathMode=pathMode;this.maker=d(maker);this.taker=d(taker);this.slippage=d(slip).divide(d("10000"));
        Path out=root.resolve(name); Files.createDirectories(out);
        fundingCsv=Files.newBufferedWriter(out.resolve("funding_events.csv"));fundingCsv.write("timestamp_ms,qty,mark,rate,paid\n");
        trades=Files.newBufferedWriter(out.resolve("trades.csv"));trades.write("timestamp_ms,cycle_id,side,qty,price,fee,kind,net_qty,water\n");
        cycleCsv=Files.newBufferedWriter(out.resolve("cycles.csv"));cycleCsv.write("cycle_id,start_ms,end_ms,duration_hours,pnl,trading_fee,funding_paid,buys,sells,reason,direction\n");
        equityCsv=Files.newBufferedWriter(out.resolve("equity.csv"));equityCsv.write("timestamp_ms,price,mark,equity,net_qty,net_notional,cycle_pnl,closed_pnl,phase,free_margin\n");
        try(var r=Files.newBufferedReader(root.resolve("data/funding.csv"))){r.readLine();String s;while((s=r.readLine())!=null){String[] a=s.split(",");funding.put(Long.parseLong(a[0]),d(a[1]));}}
        water=new ConfiguredWaterExit(null,settings,spec,new RebalanceProfitExit.Exchange(){
            public List<WorkingOrder> openOrders(){return open.values().stream().map(SimOrder::row).toList();}
            public RebalanceProfitExit.Evidence evidence(String id){SimOrder o=history.get(id);return o==null?null:new RebalanceProfitExit.Evidence(o.row(),o.fills.stream().map(FillEvent::fillQty).reduce(ZERO,BigDecimal::add),List.copyOf(o.fills));}
            public BigDecimal bestOppositePrice(TradeSide side){return executionPrice(side);}
        },new BybitTradeGateway(){
            public BybitOrderSubmission submit(OrderIntent i){place(i);return new BybitOrderSubmission("sim",i.clientOrderId());}
            public void cancel(String symbol,String id){LongCapitalBacktest.this.cancel(id);}
        },registry,p->summary(),this::reconciledFill,halfRate,fullRate);
    }
    MarketSnapshot market(){return new MarketSnapshot("BTCUSDT",time,spot,mark,new IndicatorSnapshot(time,spot,spot,spot,spot));}
    BigDecimal cyclePnl(){return q.multiply(mark).subtract(value).subtract(fees);}
    BigDecimal equity(){return initial.add(closedPnl).add(cycleStart==null?ZERO:cyclePnl());}
    BigDecimal executionPrice(TradeSide side){
        BigDecimal p=spot.multiply(side==TradeSide.BUY?ONE.add(slippage):ONE.subtract(slippage)).add(side==TradeSide.BUY?d("0.05"):d("-0.05"));
        return p.divide(d("0.1"),0,side==TradeSide.BUY?RoundingMode.CEILING:RoundingMode.FLOOR).multiply(d("0.1"));
    }
    CurrentCycleSummary summary(){
        if(cycleStart==null)return null;
        return new CurrentCycleSummary("RBAL:BTCUSDT",cycleStart,time,time,(time.toEpochMilli()-cycleStart.toEpochMilli())/1000,
            (int)cycleBuys,(int)cycleSells,(int)(cycleBuys+cycleSells),ZERO,ZERO,q,value,fees,cycleTrading,cycleFunding.negate(),
            ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,spot,cycleSign>0?"LONG":"SHORT",cyclePnl(),cyclePnl());
    }
    void begin(){
        if(thresholdMode&&spot.compareTo(threshold)==0)return;
        cycleSign=thresholdMode&&spot.compareTo(threshold)>0?-1:1;
        config=gridConfig(cycleSign>0?EntryDirection.LONG_ONLY:EntryDirection.SHORT_ONLY);
        cycleStart=time; q=ZERO;value=ZERO;fees=ZERO;cycleTrading=ZERO;cycleFunding=ZERO;cycleBuys=0;cycleSells=0;cycleExitWasFull=false;
        history.clear();open.clear();applied.clear();registry.replaceAll(List.of());rounding=QuantityRoundingState.empty();
        ledger=new InMemoryStrategyLedger("BTCUSDT");
        GridOrderPlanner planner=new GridOrderPlanner(new ClientOrderIdFactory("RBAL"),ONE,(m,l,p,z)->false,(m,l,p,z)->false,
            symbol->ProfitSizingState.empty(),symbol->rounding);
        engine=new GridStrategyEngine(config,spec,ledger,new GridTriggerEvaluator(),planner,true);
        commands(engine.onMarket(market()));
    }
    void commands(List<StrategyCommand> commands){for(var c:commands){if(c.type()==StrategyCommandType.PLACE)place(c.orderIntent());else if(c.type()==StrategyCommandType.CANCEL)cancel(c.clientOrderId());}}
    void place(OrderIntent i){
        SimOrder o=new SimOrder(i,time);history.put(i.clientOrderId(),o);registry.trackSubmitted(i,time);
        o.aggressive=i.type()==OrderType.MARKET||i.clientOrderId().startsWith("RBAL_WATER_")||i.side()==TradeSide.BUY&&i.price().compareTo(spot)>=0||i.side()==TradeSide.SELL&&i.price().compareTo(spot)<=0;
        BigDecimal price=i.type()==OrderType.MARKET?executionPrice(i.side()):i.price();
        if(!canFill(i,price)){
            o.status=OrderStatus.EXPIRED;registry.markExpired(i.clientOrderId(),time);blocked++;return;
        }
        open.put(i.clientOrderId(),o);
    }
    BigDecimal freeMargin(){return equity().subtract(q.abs().multiply(mark));}
    boolean canFill(OrderIntent i,BigDecimal px){
        BigDecimal signed=i.qty().multiply(i.side()==TradeSide.BUY?ONE:ONE.negate());
        BigDecimal nextQ=q.add(signed);
        if(nextQ.signum()!=0&&nextQ.signum()!=cycleSign)return false;
        if(nextQ.abs().compareTo(q.abs())<=0)return true; // Closing is always permitted.
        BigDecimal rate=i.type()==OrderType.MARKET||i.clientOrderId().startsWith("RBAL_WATER_")?taker:
            (history.get(i.clientOrderId())!=null&&history.get(i.clientOrderId()).aggressive?taker:maker);
        BigDecimal nextValue=value.add(signed.multiply(px));
        BigDecimal nextFees=fees.add(i.qty().multiply(px).multiply(rate));
        BigDecimal marginPrice=cycleSign<0?mark.max(px):mark;
        BigDecimal afterEq=initial.add(closedPnl).add(nextQ.multiply(marginPrice)).subtract(nextValue).subtract(nextFees);
        return afterEq.subtract(nextQ.abs().multiply(marginPrice)).signum()>=0;
    }
    void enforceMargin(){
        for(int guard=0;guard<5&&q.signum()!=0&&freeMargin().signum()<0;guard++){
            TradeSide side=q.signum()>0?TradeSide.SELL:TradeSide.BUY;
            BigDecimal px=executionPrice(side);
            BigDecimal improvement=q.signum()>0?px.multiply(ONE.subtract(taker)):mark.multiply(d("2")).subtract(px.multiply(ONE.add(taker)));
            if(improvement.signum()<=0)throw new IllegalStateException("Invalid margin cover price");
            BigDecimal amount=freeMargin().negate().divide(improvement.multiply(spec.qtyStep()),0,RoundingMode.CEILING).multiply(spec.qtyStep()).max(spec.minOrderQty()).min(q.abs());
            for(String id:List.copyOf(open.keySet()))cancel(id);
            OrderIntent intent=new OrderIntent("BTCUSDT","RBAL_MARGIN_"+(++fundingCoverEvents),side,OrderType.MARKET,amount,ZERO,false,"no-leverage-margin-cover");
            fundingCoverQty=fundingCoverQty.add(amount);place(intent);marketable();
        }
        if(freeMargin().compareTo(d("-0.01"))<0)throw new IllegalStateException("Cannot restore 1x exposure cap");
    }
    void cancel(String id){SimOrder o=open.remove(id);if(o!=null){o.status=OrderStatus.CANCELED;registry.markCanceled(id,time);}}
    void roundingAfter(FillEvent f){
        SimOrder o=history.get(f.clientOrderId());if(o==null||o.intent.reason()==null)return;
        var m=java.util.regex.Pattern.compile("qty-rounding:raw=([^,|]+),carry=[^,|]+,rounded=([^,|]+)").matcher(o.intent.reason());
        if(m.find())rounding=rounding.afterFill(f.side(),d(m.group(1)),d(m.group(2)),o.intent.qty(),f.fillQty());
    }
    void accounting(FillEvent f){
        if(!applied.add(f.executionId()))return;
        BigDecimal sign=f.side()==TradeSide.BUY?ONE:ONE.negate();q=q.add(sign.multiply(f.fillQty()));value=value.add(sign.multiply(f.fillValue()));fees=fees.add(f.fee());
        totalTrading=totalTrading.add(f.fee());cycleTrading=cycleTrading.add(f.fee());if(f.side()==TradeSide.BUY)cycleBuys++;else cycleSells++;
        roundingAfter(f);fills++;
        if(ledger.snapshot().netQty().compareTo(q)!=0)throw new IllegalStateException("Native ledger differs from cash inventory");
        if(q.signum()!=0&&q.signum()!=cycleSign)throw new IllegalStateException("Inventory reversed within a Cycle");
        if(q.abs().compareTo(config.maxInventoryQty())>0)throw new IllegalStateException("Inventory exceeds cap");
        try{BigDecimal w=q.signum()==0?null:value.add(fees).divide(q,10,RoundingMode.HALF_UP);
            trades.write(time.toEpochMilli()+","+(cycles+1)+","+f.side()+","+f.fillQty()+","+f.fillPrice()+","+f.fee()+","+(f.clientOrderId().startsWith("RBAL_MARGIN_")?"margin_cover":f.clientOrderId().startsWith("RBAL_WATER_")?"water":f.clientOrderId().contains("_I_")?"initial":"grid")+","+q+","+(w==null?"":w)+"\n");
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    void reconciledFill(FillEvent f){if(!applied.contains(f.executionId())){ledger.applyFill(f);accounting(f);}}
    void fill(SimOrder o,BigDecimal px){
        if(!open.containsKey(o.intent.clientOrderId()))return;
        if(!canFill(o.intent,px)){
            open.remove(o.intent.clientOrderId());o.status=OrderStatus.EXPIRED;registry.markExpired(o.intent.clientOrderId(),time);blocked++;return;
        }
        open.remove(o.intent.clientOrderId());o.status=OrderStatus.FILLED;registry.markFilled(o.intent.clientOrderId(),time);
        BigDecimal rate=o.aggressive?taker:maker;
        FillEvent f=new FillEvent("sim-"+(fills+1),"BTCUSDT",o.intent.clientOrderId(),o.intent.side(),o.intent.qty(),px,time,o.intent.qty().multiply(px),o.intent.qty().multiply(px).multiply(rate),"USDT");
        o.fills.add(f);
        if(q.signum()!=0&&!water.frozen())water.beforeFill(f,market());
        if(water.frozen()||o.intent.clientOrderId().startsWith("RBAL_WATER_")){ledger.applyFill(f);accounting(f);}
        else commands(engine.onFill(f,market(),()->accounting(f)));
    }
    void marketable(){
        for(int guard=0;guard<50;guard++){
            SimOrder found=null;
            for(SimOrder o:open.values())if(o.intent.type()==OrderType.MARKET
                ||o.intent.side()==TradeSide.BUY&&o.intent.price().compareTo(spot)>=0
                ||o.intent.side()==TradeSide.SELL&&o.intent.price().compareTo(spot)<=0){found=o;break;}
            if(found==null)return;
            fill(found,found.intent.type()==OrderType.MARKET?executionPrice(found.intent.side()):found.intent.price());
        }
        throw new IllegalStateException("Marketable-order loop");
    }
    void finishCycle(String reason){
        BigDecimal pnl=cyclePnl();closedPnl=closedPnl.add(pnl);cycles++;if(cycleSign>0)longCycles++;else shortCycles++;if(pnl.signum()>0)win++;else if(pnl.signum()<0)loss++;
        long duration=time.toEpochMilli()-cycleStart.toEpochMilli();maxCycleMs=Math.max(maxCycleMs,duration);
        if(reason.equals("flat"))gridFlat++;
        try{cycleCsv.write(cycles+","+cycleStart.toEpochMilli()+","+time.toEpochMilli()+","+(duration/3600000.0)+","+pnl+","+cycleTrading+","+cycleFunding+","+cycleBuys+","+cycleSells+","+reason+","+(cycleSign>0?"LONG":"SHORT")+"\n");}catch(IOException e){throw new UncheckedIOException(e);}
        for(String id:List.copyOf(open.keySet()))cancel(id);
        cycleStart=null;q=ZERO;value=ZERO;fees=ZERO;registry.replaceAll(List.of());
    }
    void poll(){
        if(lastPoll==time.toEpochMilli())return;lastPoll=time.toEpochMilli();
        if(cycleStart==null){begin();marketable();return;}
        boolean blockedGrid=water.poll(market(),cycleStart,q.signum()==0);
        String phase=water.phase();if(!phase.equals(lastPhase)){if(phase.equals("HALF"))halfEvents++;if(phase.equals("EXITING")){fullEvents++;cycleExitWasFull=true;}lastPhase=phase;}
        if(blockedGrid){if(water.complete()){finishCycle(cycleExitWasFull?fullRate.multiply(d("100")).stripTrailingZeros().toPlainString()+"pct":"flat");}else marketable();}
        else {
            ActiveOrderPair activePair=engine.runtimeState().activeOrderPair();
            if(activePair!=null)for(String id:List.copyOf(open.keySet()))if(!activePair.contains(id))cancel(id);
            if(engine.runtimeState().state()==GridState.GRID_ACTIVE||engine.runtimeState().state()==GridState.REQUOTE)commands(engine.onMarket(market()));
            boolean complete=open.values().stream().anyMatch(o->o.intent.side()==TradeSide.BUY)&&open.values().stream().anyMatch(o->o.intent.side()==TradeSide.SELL);
            if(!complete&&q.signum()!=0){
                // Native RebalanceService recovery: cancel incomplete pair and replan from last fill.
                for(String id:List.copyOf(open.keySet()))cancel(id);
                if(engine.runtimeState().activeOrderPair()!=null||engine.runtimeState().state()==GridState.GRID_ACTIVE)
                    engine.restoreRuntimeState(new StrategyRuntimeState(GridState.WAITING_TRIGGER,null,engine.runtimeState().lastTriggerTime()));
                commands(engine.recoverPairFromLastFill(market(),"rebalance-recover-pair"));
            }
            marketable();
        }
        registry.replaceAll(open.values().stream().map(SimOrder::row).toList());
        // Once GRID is reconciled there is no outstanding terminal evidence. Bound replay memory.
        if(!water.frozen())history.entrySet().removeIf(e->e.getValue().status!=OrderStatus.NEW);
    }
    void observe(){
        enforceMargin();
        BigDecimal eq=equity();if(eq.signum()<=0)throw new IllegalStateException("Bankrupt");
        peakEquity=peakEquity.max(eq);BigDecimal dd=peakEquity.subtract(eq);maxDD=maxDD.max(dd);maxDDPct=maxDDPct.max(dd.divide(peakEquity,12,RoundingMode.HALF_UP));minEquity=minEquity.min(eq);
        maxQty=maxQty.max(q.abs());maxNotional=maxNotional.max(q.abs().multiply(mark));double ex=q.abs().multiply(mark).divide(eq,12,RoundingMode.HALF_UP).doubleValue();long now=time.toEpochMilli();if(prevObserve!=0)exposureSum+=prevExposure*(now-prevObserve);prevObserve=now;prevExposure=ex;points++;
        // Fully funded exposure: absolute marked position cannot exceed marked equity.
        BigDecimal available=eq.subtract(q.abs().multiply(mark));
        minFreeCash=minFreeCash.min(available);
        if(available.compareTo(d("-0.01"))<0)throw new IllegalStateException("Funding requires borrowing; no-leverage capacity exhausted at "+time+" free="+available);
        monthEquity.put(time.atOffset(ZoneOffset.UTC).toString().substring(0,7),eq.doubleValue());
        if(time.toEpochMilli()%3600000==0){try{equityCsv.write(time.toEpochMilli()+","+spot+","+mark+","+eq+","+q+","+q.abs().multiply(mark)+","+(cycleStart==null?ZERO:cyclePnl())+","+closedPnl+","+water.phase()+","+available+"\n");}catch(IOException e){throw new UncheckedIOException(e);}}
    }
    void point(long ts,BigDecimal p,BigDecimal m,boolean settlement,boolean doPoll){
        time=Instant.ofEpochMilli(ts);spot=p;mark=m;lastTime=ts;if(firstTime==0)firstTime=ts;
        if(settlement&&funding.containsKey(ts)){
            BigDecimal paid=q.multiply(mark).multiply(funding.get(ts));fees=fees.add(paid);cycleFunding=cycleFunding.add(paid);totalFunding=totalFunding.add(paid);
            try{fundingCsv.write(ts+","+q+","+mark+","+funding.get(ts)+","+paid+"\n");}catch(IOException e){throw new UncheckedIOException(e);}
        }
        enforceMargin();
        marketable();if(doPoll)poll();observe();
    }
    void segment(long t0,long t1,BigDecimal p0,BigDecimal p1,BigDecimal m0,BigDecimal m1){
        long cursor=t0;BigDecimal current=p0;
        for(int guard=0;guard<1000;guard++){
            SimOrder next=null;BigDecimal px=null;
            boolean up=p1.compareTo(current)>0,down=p1.compareTo(current)<0;
            for(SimOrder o:open.values()){
                if(o.intent.type()==OrderType.MARKET)continue;BigDecimal z=o.intent.price();
                boolean cross=up&&o.intent.side()==TradeSide.SELL&&z.compareTo(current)>0&&z.compareTo(p1)<=0
                    ||down&&o.intent.side()==TradeSide.BUY&&z.compareTo(current)<0&&z.compareTo(p1)>=0;
                if(cross&&(next==null||(up?z.compareTo(px)<0:z.compareTo(px)>0))){next=o;px=z;}
            }
            if(next==null)break;
            BigDecimal frac=px.subtract(p0).divide(p1.subtract(p0),16,RoundingMode.HALF_UP);
            long ts=t0+Math.round((t1-t0)*frac.doubleValue());time=Instant.ofEpochMilli(ts);spot=px;mark=m0.add(m1.subtract(m0).multiply(frac));
            fill(next,px);observe();current=px;cursor=ts;
            if(guard==999)throw new IllegalStateException("Segment fill loop");
        }
    }
    void run() throws Exception {
        long bars=0;BigDecimal firstPrice=null,lastPrice=null;long expected=0;
        try(var r=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(root.resolve("data/bars.csv.gz")))))){
            r.readLine();String line;
            while((line=r.readLine())!=null){String[] a=line.split(",");long t=Long.parseLong(a[0]);if(expected!=0&&t!=expected)throw new IllegalStateException("Missing minute");expected=t+60000;
                BigDecimal[] p={d(a[1]),d(a[2]),d(a[3]),d(a[4])},m={d(a[5]),d(a[6]),d(a[7]),d(a[8])};
                if(firstPrice==null)firstPrice=p[0];lastPrice=p[3];
                point(t,p[0],m[0],true,true);
                int s0=0;
                for(int s1:new int[]{15,20,30,40,45,60}){
                    BigDecimal p0=pathValue(p,s0),p1=pathValue(p,s1),m0=pathValue(m,s0),m1=pathValue(m,s1);
                    segment(t+s0*1000,t+s1*1000,p0,p1,m0,m1);
                    point(t+s1*1000,p1,m1,false,s1%15==0&&s1!=60);s0=s1;
                }
                bars++;if(bars%100000==0)System.out.println(name+" "+bars+" minutes equity="+equity()+" cycles="+cycles);
            }
        }
        trades.close();cycleCsv.close();equityCsv.close();fundingCsv.close();
        BigDecimal finalEquity=equity();TradeSide exitSide=q.signum()<0?TradeSide.BUY:TradeSide.SELL;BigDecimal exitPrice=executionPrice(exitSide);BigDecimal liquidationCost=q.multiply(mark.subtract(exitPrice)).add(q.abs().multiply(exitPrice).multiply(taker));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("half_rate",halfRate);result.put("full_rate",fullRate);result.put("direction_mode",thresholdMode?"LONG below 100000; SHORT above 100000; locked for each Cycle":"always LONG");result.put("completed_long_cycles",longCycles);result.put("completed_short_cycles",shortCycles);result.put("open_direction",cycleStart==null?"NONE":cycleSign>0?"LONG":"SHORT");result.put("scenario",name);result.put("path",pathMode);result.put("start_ms",firstTime);result.put("end_ms",lastTime);result.put("minutes",bars);
        result.put("initial_capital",initial);result.put("final_equity_marked",finalEquity);result.put("net_pnl",finalEquity.subtract(initial));result.put("return_pct",finalEquity.divide(initial,12,RoundingMode.HALF_UP).subtract(ONE).multiply(d("100")));
        result.put("final_equity_liquidated_estimate",finalEquity.subtract(liquidationCost));result.put("closed_cycle_pnl",closedPnl);result.put("open_cycle_pnl",cycleStart==null?ZERO:cyclePnl());result.put("open_qty",q);
        result.put("trading_fees",totalTrading);result.put("funding_paid",totalFunding);result.put("max_drawdown_usdt",maxDD);result.put("max_drawdown_pct",maxDDPct.multiply(d("100")));result.put("min_equity",minEquity);
        result.put("peak_btc_qty",maxQty);result.put("peak_notional",maxNotional);result.put("average_exposure_pct",exposureSum/(lastTime-firstTime)*100);
        result.put("completed_cycles",cycles);result.put("winning_cycles",win);result.put("losing_cycles",loss);result.put("half_events",halfEvents);result.put("full_events",fullEvents);result.put("max_completed_cycle_hours",maxCycleMs/3600000.0);
        result.put("open_cycle_hours",cycleStart==null?0:(lastTime-cycleStart.toEpochMilli())/3600000.0);result.put("fills",fills);result.put("capital_blocked_add_attempts",blocked);result.put("margin_cover_events",fundingCoverEvents);result.put("margin_cover_qty",fundingCoverQty);result.put("min_free_margin",minFreeCash);result.put("maker_fee_rate",maker);result.put("taker_fee_rate",taker);result.put("slippage_bps",slippage.multiply(d("10000")));
        result.put("btc_buy_hold_return_pct",lastPrice.divide(firstPrice,12,RoundingMode.HALF_UP).subtract(ONE).multiply(d("100")));result.put("monthly_end_equity",monthEquity);
        Files.writeString(root.resolve(name).resolve("summary.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));System.out.println(JSON.writeValueAsString(result));
    }
    BigDecimal pathValue(BigDecimal[] a,int second){
        if(pathMode.equals("CLOSE"))return a[0].add(a[3].subtract(a[0]).multiply(BigDecimal.valueOf(second)).divide(d("60"),12,RoundingMode.HALF_UP));
        int[] seq=pathMode.equals("OHLC")?new int[]{0,1,2,3}:new int[]{0,2,1,3};
        if(second==60)return a[3];int leg=second/20;int offset=second%20;
        return a[seq[leg]].add(a[seq[leg+1]].subtract(a[seq[leg]]).multiply(BigDecimal.valueOf(offset)).divide(d("20"),12,RoundingMode.HALF_UP));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=8)throw new IllegalArgumentException("root scenario path maker taker slippage_bps half_rate full_rate");
        new LongCapitalBacktest(Path.of(args[0]),args[1],args[2],args[3],args[4],args[5],args[6],args[7]).run();
    }
}
