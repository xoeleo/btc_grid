package com.btcgrid.app;

import com.btcgrid.common.model.*;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.strategy.model.*;
import com.btcgrid.strategy.service.*;
import java.math.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;

/** Independent research overlay; no changes to any main-project strategy class. */
public final class DipGridBacktest extends BaselineReplay {
    final BigDecimal interval, waterBuffer=d("0.01");
    DipBands bands;
    BigDecimal floor=ZERO;
    boolean paused, exiting;
    long bandEvents, dipExits;
    BigDecimal lastBeforeWater=ZERO,lastExecutedPrice=ZERO;
    final BufferedWriter bandCsv, controlsCsv, extraCycleCsv, contextCsv;

    DipGridBacktest(Path root,String name,String path,String interval)throws Exception {
        super(root,name,path,"0.00014","0.00035","1","0.01","0.02");
        this.interval=d(interval);
        new DipBands(ONE,this.interval); // Validate before any market replay.
        Path out=root.resolve(name);
        bandCsv=Files.newBufferedWriter(out.resolve("band_events.csv"));
        bandCsv.write("timestamp_ms,cycle_id,band,anchor,node,price,locked_qty,net_value,fees,water,recovery_price\n");
        controlsCsv=Files.newBufferedWriter(out.resolve("control_events.csv"));
        controlsCsv.write("timestamp_ms,cycle_id,event,price,node,locked_qty,qty,water,recovery_price,exit_price\n");
        extraCycleCsv=Files.newBufferedWriter(out.resolve("dip_cycles.csv"));
        extraCycleCsv.write("cycle_id,anchor,deepest_band,deepest_drop_pct,node,locked_qty,recovery_price,water_before_exit,water_exit_threshold,exit_price,reason\n");
        contextCsv=Files.newBufferedWriter(out.resolve("trade_context.csv"));
        contextCsv.write("timestamp_ms,fill_index,cycle_id,mode,deepest_band,node,locked_qty,spot,water_before,water_exit_threshold,recovery_price\n");
    }

    boolean active(){return bands!=null&&bands.deepest>0;}
    @Override boolean usesWater(){return !active();}
    @Override String controlPhase(){return active()?(exiting?"DIP_EXIT":paused?"DIP_PAUSED":"DIP_GRID"):water.phase();}

    @Override void begin(){
        bands=new DipBands(spot,interval);floor=ZERO;paused=false;exiting=false;
        super.begin();
    }

    BigDecimal waterPrice(){return q.signum()==0?ZERO:value.add(fees).divide(q,16,RoundingMode.HALF_UP);}
    BigDecimal exitThreshold(){return waterPrice().multiply(ONE.add(waterBuffer));}
    boolean canExit(){
        if(!active()||q.signum()<=0||spot.compareTo(bands.recovery())<=0)return false;
        BigDecimal px=executionPrice(TradeSide.SELL);
        return px.compareTo(exitThreshold())>0
                &&q.multiply(px).subtract(value).subtract(fees).subtract(q.multiply(px).multiply(taker)).signum()>0;
    }
    void controlEvent(String event){
        try{controlsCsv.write(time.toEpochMilli()+","+(cycles+1)+","+event+","+spot+","+bands.node()+","+floor+","+q+","+waterPrice()+","+bands.recovery()+","+executionPrice(TradeSide.SELL)+"\n");}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    void cancelGrid(){for(String id:List.copyOf(open.keySet()))cancel(id);}

    /** Reset only grid references and consecutive-fill counters; retain economic ledger and cash. */
    void reanchor(){
        cancelGrid();
        if(q.signum()==0)return;
        ledger.markToMarket(market());
        StrategyLedgerSnapshot s=ledger.snapshot();
        ledger.restore(new StrategyLedgerSnapshot(s.symbol(),s.netQty(),s.avgEntryPrice(),s.realizedPnl(),
                s.floatingPnl(),ZERO,spot,TradeSide.BUY,s.lastFillZone(),0,0,s.inventoryMode(),time));
        GridOrderPlanner planner=new GridOrderPlanner(new ClientOrderIdFactory("RBAL"),ONE,
                (m,l,p,z)->false,(m,l,p,z)->false,symbol->ProfitSizingState.empty(),symbol->rounding);
        engine=new GridStrategyEngine(config,spec,ledger,new GridTriggerEvaluator(),planner,true);
        engine.restoreRuntimeState(new StrategyRuntimeState(GridState.WAITING_TRIGGER,null,time.minusSeconds(1)));
        commands(engine.onMarket(market()));
    }

    void controlAtPrice(){
        if(cycleStart==null||bands==null)return;
        int reached=bands.reached(spot);
        if(reached>bands.deepest){
            cancelGrid();
            for(int k=bands.deepest+1;k<=reached;k++){
                bands.deepest=k;floor=q;bandEvents++;
                try{bandCsv.write(time.toEpochMilli()+","+(cycles+1)+","+k+","+bands.anchor+","+bands.node()+","+spot+","+floor+","+value+","+fees+","+waterPrice()+","+bands.recovery()+"\n");}
                catch(IOException e){throw new UncheckedIOException(e);}
            }
            paused=spot.compareTo(bands.node())>0;
            controlEvent("REBASE");
            if(!paused)reanchor();
        }
        if(!active())return;
        if(canExit()){
            cancelGrid();controlEvent("EXIT");exiting=true;dipExits++;
            BigDecimal beforeWater=waterPrice(),beforeFloor=floor;
            OrderIntent order=new OrderIntent("BTCUSDT","RBAL_DIP_EXIT_"+(cycles+1),TradeSide.SELL,
                    OrderType.MARKET,q,ZERO,true,"dip-recovery-and-water-plus-1pct");
            place(order);marketable();
            if(q.signum()!=0)throw new IllegalStateException("Dip exit did not fill completely");
            writeDipCycle(beforeWater,beforeFloor,"dip_recovery");
            super.finishCycle("dip_recovery");
            return;
        }
        boolean above=spot.compareTo(bands.node())>0;
        if(above&&!paused){paused=true;cancelGrid();controlEvent("PAUSE");}
        else if(!above&&paused){paused=false;controlEvent("RESUME");reanchor();}
    }

    void writeDipCycle(BigDecimal water,BigDecimal locked,String reason){
        try{extraCycleCsv.write((cycles+1)+","+bands.anchor+","+bands.deepest+","+interval.multiply(BigDecimal.valueOf(bands.deepest)).multiply(d("100"))+","+bands.node()+","+locked+","+bands.recovery()+","+water+","+water.multiply(ONE.add(waterBuffer))+","+(reason.equals("open")?ZERO:lastExecutedPrice)+","+reason+"\n");}
        catch(IOException e){throw new UncheckedIOException(e);}
    }

    @Override void finishCycle(String reason){
        writeDipCycle(lastBeforeWater,floor,reason);
        super.finishCycle(reason);
    }

    @Override void place(OrderIntent i){
        if(active()&&!i.clientOrderId().startsWith("RBAL_DIP_EXIT_")&&!i.clientOrderId().startsWith("RBAL_MARGIN_")){
            if(paused)return;
            if(i.side()==TradeSide.SELL){
                BigDecimal amount=spec.roundQtyDown(i.qty().min(q.subtract(floor).max(ZERO)));
                if(amount.compareTo(spec.minOrderQty())<0)return;
                if(amount.compareTo(i.qty())<0)i=new OrderIntent(i.symbol(),i.clientOrderId(),i.side(),i.type(),amount,i.price(),i.reduceOnly(),"dip-floor-capped-grid-sell");
            }
        }
        super.place(i);
    }
    @Override boolean canFill(OrderIntent i,BigDecimal px){
        if(active()&&!i.clientOrderId().startsWith("RBAL_DIP_EXIT_")&&i.side()==TradeSide.SELL
                &&q.subtract(i.qty()).compareTo(floor)<0)return false;
        return super.canFill(i,px);
    }
    @Override void accounting(FillEvent f){
        BigDecimal before=waterPrice();long beforeCount=fills;
        super.accounting(f);
        if(fills==beforeCount)return;
        lastBeforeWater=before;lastExecutedPrice=f.fillPrice();
        if(active()&&!exiting&&q.compareTo(floor)<0)throw new IllegalStateException("Grid sold locked inventory");
        try{contextCsv.write(time.toEpochMilli()+","+fills+","+(cycles+1)+","+controlPhase()+","+bands.deepest+","+bands.node()+","+floor+","+spot+","+before+","+before.multiply(ONE.add(waterBuffer))+","+bands.recovery()+"\n");}
        catch(IOException e){throw new UncheckedIOException(e);}
    }

    @Override void poll(){
        if(!active()){super.poll();return;}
        if(lastPoll==time.toEpochMilli())return;lastPoll=time.toEpochMilli();
        if(cycleStart==null){begin();marketable();return;}
        if(paused)return;
        if(q.signum()==0){finishCycle("flat");return;}
        ActiveOrderPair pair=engine.runtimeState().activeOrderPair();
        if(pair!=null)for(String id:List.copyOf(open.keySet()))if(!pair.contains(id))cancel(id);
        if(engine.runtimeState().state()==GridState.GRID_ACTIVE||engine.runtimeState().state()==GridState.REQUOTE)
            commands(engine.onMarket(market()));
        boolean complete=open.values().stream().anyMatch(o->o.intent.side()==TradeSide.BUY)
                &&(q.compareTo(floor)==0||open.values().stream().anyMatch(o->o.intent.side()==TradeSide.SELL));
        if(!complete){
            cancelGrid();
            engine.restoreRuntimeState(new StrategyRuntimeState(GridState.WAITING_TRIGGER,null,engine.runtimeState().lastTriggerTime()));
            commands(engine.recoverPairFromLastFill(market(),"dip-grid-recover-pair"));
        }
        marketable();registry.replaceAll(open.values().stream().map(SimOrder::row).toList());
        history.entrySet().removeIf(e->e.getValue().status!=OrderStatus.NEW);
    }

    @Override void point(long ts,BigDecimal p,BigDecimal m,boolean settlement,boolean doPoll){
        time=Instant.ofEpochMilli(ts);spot=p;mark=m;lastTime=ts;if(firstTime==0)firstTime=ts;
        if(settlement&&funding.containsKey(ts)){
            BigDecimal paid=q.multiply(mark).multiply(funding.get(ts));fees=fees.add(paid);cycleFunding=cycleFunding.add(paid);totalFunding=totalFunding.add(paid);
            try{fundingCsv.write(ts+","+q+","+mark+","+funding.get(ts)+","+paid+"\n");}
            catch(IOException e){throw new UncheckedIOException(e);}
        }
        enforceMargin();controlAtPrice();
        // A completed exit must wait for a strictly later scheduled poll to start another Cycle.
        if(cycleStart==null&&exiting){observe();exiting=false;lastPoll=ts;return;}
        marketable();if(doPoll)poll();observe();
    }

    BigDecimal nextExitSignal(){
        BigDecimal tick=spec.priceTick();
        BigDecimal executable=exitThreshold().divide(tick,0,RoundingMode.FLOOR).add(ONE).multiply(tick);
        BigDecimal byWater=executable.add(d("0.05")).divide(ONE.subtract(slippage),16,RoundingMode.CEILING);
        return bands.recovery().add(tick).max(byWater);
    }
    @Override void segment(long t0,long t1,BigDecimal p0,BigDecimal p1,BigDecimal m0,BigDecimal m1){
        if(p0.compareTo(p1)==0)return;
        BigDecimal current=p0;
        boolean up=p1.compareTo(p0)>0;
        for(int guard=0;guard<2000;guard++){
            BigDecimal eventPrice=null;
            for(SimOrder o:open.values()){
                if(o.intent.type()==OrderType.MARKET)continue;
                BigDecimal px=o.intent.price();
                boolean cross=up&&o.intent.side()==TradeSide.SELL&&px.compareTo(current)>0&&px.compareTo(p1)<=0
                        ||!up&&o.intent.side()==TradeSide.BUY&&px.compareTo(current)<0&&px.compareTo(p1)>=0;
                if(cross&&(eventPrice==null||(up?px.compareTo(eventPrice)<0:px.compareTo(eventPrice)>0)))eventPrice=px;
            }
            List<BigDecimal> boundaries=new ArrayList<>();
            if(cycleStart!=null&&bands!=null){
                if(!up&&bands.nextNode().signum()>0)boundaries.add(bands.nextNode());
                if(active()){
                    if(up){boundaries.add(bands.node().add(spec.priceTick()));boundaries.add(nextExitSignal());}
                    else if(paused)boundaries.add(bands.node());
                }
            }
            for(BigDecimal px:boundaries){
                boolean cross=up?px.compareTo(current)>0&&px.compareTo(p1)<=0:px.compareTo(current)<0&&px.compareTo(p1)>=0;
                if(cross&&(eventPrice==null||(up?px.compareTo(eventPrice)<0:px.compareTo(eventPrice)>0)))eventPrice=px;
            }
            if(eventPrice==null)return;
            BigDecimal frac=eventPrice.subtract(p0).divide(p1.subtract(p0),16,RoundingMode.HALF_UP);
            long ts=t0+Math.round((t1-t0)*frac.doubleValue());
            time=Instant.ofEpochMilli(ts);spot=eventPrice;mark=m0.add(m1.subtract(m0).multiply(frac));
            controlAtPrice();
            if(cycleStart==null&&exiting){exiting=false;lastPoll=ts;observe();current=eventPrice;continue;}
            marketable();observe();current=eventPrice;
        }
        throw new IllegalStateException("Dip segment event loop");
    }

    @Override void run()throws Exception{
        super.run();
        if(cycleStart!=null)writeDipCycle(waterPrice(),floor,"open");
        bandCsv.close();controlsCsv.close();extraCycleCsv.close();contextCsv.close();
        Path p=root.resolve(name).resolve("summary.json");
        Map<String,Object> result=JSON.readValue(Files.readString(p),Map.class);
        result.put("strategy","dip-grid-locked-floor");result.put("dip_interval",interval);
        result.put("water_profit_buffer",waterBuffer);result.put("band_events",bandEvents);result.put("dip_exits",dipExits);
        result.put("open_deepest_band",cycleStart==null?0:bands.deepest);
        result.put("open_locked_qty",cycleStart==null?ZERO:floor);
        Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("root scenario path dip_interval");
        new DipGridBacktest(Path.of(args[0]),args[1],args[2],args[3]).run();
    }
}
